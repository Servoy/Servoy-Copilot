/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest.outcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;
import com.servoy.j2db.persistence.AbstractBase;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.ISupportName;
import com.servoy.j2db.persistence.Solution;

/**
 * Verifies a baseline's expected outcomes ({@link OutcomeAssertion}s) against
 * the <b>real in-memory Servoy persist tree</b> of the active solution, rather
 * than against the transcript or {@code .frm}/{@code .val} file text
 * (SVY-21366).
 * <p>
 * This is the "focus on the outcome" check: it walks the active
 * {@link Solution}, finds each expected persist by kind + name (+ scope) or, for
 * child components, by kind + property matchers, and asserts the declared
 * property matchers and nested child expectations. Because it inspects the
 * resolved model, it is immune to the model reordering tools, taking a different
 * path (e.g. via a sub-agent), rephrasing prose, or emitting extra exploration
 * calls - only the resulting persists matter.
 * </p>
 * <p>
 * The checker is deliberately generic: persists are matched on
 * {@link AbstractBase#getTypeID()} + {@link ISupportName#getName()} +
 * {@link AbstractBase#getProperty(String)}, and children are walked via
 * {@link ISupportChilds#getAllObjects()}, so any Servoy-characteristic persist
 * an AI creates or modifies (forms, components, fields, value lists, relations,
 * menus, media, global/form methods and variables, calculations, aggregates)
 * can be asserted without a per-type checker.
 * </p>
 * <p>
 * The result is a structured tree ({@link Node}) carrying, for every assertion
 * and every property, the expected and the actual value plus a pass flag - so a
 * UI can render an expandable, per-assertion breakdown even on PASS - alongside
 * a flat {@code diff} string for the console / Markdown report.
 * </p>
 */
public final class PersistOutcomeChecker {

	/** A single expected-vs-actual property check. */
	public record PropCheck(String property, String expected, String actual, boolean pass) {
	}

	/**
	 * A structured result node for one assertion: whether the persist was found,
	 * the per-property checks, and nested child nodes. Mirrors the assertion tree.
	 *
	 * @param label    a human label for the asserted persist (e.g. {@code form
	 *                 'customerDetail'})
	 * @param found    whether a matching persist was located
	 * @param pass     whether this node and all its descendants passed
	 * @param message  a short status line (found / missing / must-not-exist)
	 * @param props    the per-property checks on the matched persist
	 * @param children nested child assertion results
	 */
	public record Node(String label, boolean found, boolean pass, String message, List<PropCheck> props,
			List<Node> children) {
		public Node {
			props = props == null ? Collections.emptyList() : List.copyOf(props);
			children = children == null ? Collections.emptyList() : List.copyOf(children);
		}
	}

	/**
	 * The result of checking all of a baseline's outcome assertions.
	 *
	 * @param match {@code true} if every assertion held
	 * @param diff  a flat human-readable explanation (empty when {@code match})
	 * @param nodes the structured per-assertion results (for an expandable UI)
	 */
	public record Result(boolean match, String diff, List<Node> nodes) {
		public Result {
			nodes = nodes == null ? Collections.emptyList() : List.copyOf(nodes);
		}
	}

	private final Solution solution;

	/**
	 * @param solution the active editing solution to inspect (never {@code null})
	 */
	public PersistOutcomeChecker(Solution solution) {
		this.solution = solution;
	}

	/**
	 * Checks every assertion against the active solution.
	 *
	 * @param assertions the expected outcomes (may be empty)
	 * @return the aggregate result: match flag, flat diff, and structured nodes
	 */
	public Result check(List<OutcomeAssertion> assertions) {
		if (solution == null) {
			return new Result(false, "- no active solution to check outcomes against\n", List.of()); //$NON-NLS-1$
		}
		StringBuilder diff = new StringBuilder();
		List<Node> nodes = new ArrayList<>();
		boolean ok = true;
		for (OutcomeAssertion assertion : assertions) {
			Node node = checkOne(assertion, allRootPersists(), diff, ""); //$NON-NLS-1$
			nodes.add(node);
			ok &= node.pass();
		}
		return new Result(ok, diff.toString(), nodes);
	}

	/**
	 * Checks one assertion against a candidate pool of persists (root persists at
	 * the top level, a parent's children when recursing), returning a structured
	 * {@link Node} and appending any failures to {@code diff}.
	 */
	private Node checkOne(OutcomeAssertion assertion, List<IPersist> pool, StringBuilder diff, String indent) {
		IPersist match = findMatch(assertion, pool);

		if (!assertion.exists()) {
			boolean pass = match == null;
			String msg = pass ? "correctly absent" : "exists but should NOT"; //$NON-NLS-1$ //$NON-NLS-2$
			if (!pass) {
				diff.append(indent).append("- ").append(assertion.label()).append(" exists but should NOT\n"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return new Node(assertion.label(), match != null, pass, msg, List.of(), List.of());
		}

		if (match == null) {
			diff.append(indent).append("- missing expected ").append(assertion.label()).append('\n'); //$NON-NLS-1$
			return new Node(assertion.label(), false, false, "missing", List.of(), List.of()); //$NON-NLS-1$
		}

		boolean ok = true;
		List<PropCheck> propChecks = new ArrayList<>();
		for (Map.Entry<String, ArgMatcher> entry : assertion.props().entrySet()) {
			// A still-unedited inference placeholder (e.g. a component's typeName left
			// at TYPE_NAME_PLACEHOLDER) is inert: skip it so the baseline passes on the
			// real properties (dataProviderID, ...) until the user fills in the type.
			if (isUneditedPlaceholder(entry.getValue())) {
				propChecks.add(new PropCheck(entry.getKey(), describeExpected(entry.getValue()),
						"(placeholder - not checked)", true)); //$NON-NLS-1$
				continue;
			}
			Object actual = readProperty(match, entry.getKey());
			boolean pass = entry.getValue().matches(actual);
			propChecks.add(new PropCheck(entry.getKey(), describeExpected(entry.getValue()), asString(actual), pass));
			if (!pass) {
				ok = false;
				diff.append(indent).append("- ").append(assertion.label()).append(" property '") //$NON-NLS-1$ //$NON-NLS-2$
						.append(entry.getKey()).append("' expected ").append(describeExpected(entry.getValue())) //$NON-NLS-1$
						.append(" but was ").append(asString(actual)).append('\n'); //$NON-NLS-1$
			}
		}

		List<Node> childNodes = new ArrayList<>();
		if (!assertion.children().isEmpty()) {
			List<IPersist> childPool = childrenOf(match);
			for (OutcomeAssertion child : assertion.children()) {
				Node childNode = checkOne(child, childPool, diff, indent + "  "); //$NON-NLS-1$
				childNodes.add(childNode);
				ok &= childNode.pass();
			}
		}
		return new Node(assertion.label(), true, ok, "found", propChecks, childNodes); //$NON-NLS-1$
	}

	/**
	 * @return {@code true} if the matcher still holds an inference placeholder the
	 *         user has not replaced yet (currently the component
	 *         {@code typeName} placeholder), in which case it is not asserted
	 */
	private static boolean isUneditedPlaceholder(ArgMatcher matcher) {
		Object expected = matcher.getExpected();
		return expected != null && OutcomeInference.TYPE_NAME_PLACEHOLDER.equals(String.valueOf(expected));
	}

	private static String describeExpected(ArgMatcher matcher) {
		String kind = matcher.getKind().name().toLowerCase();
		Object expected = matcher.getExpected();
		if (matcher.getKind() == ArgMatcher.Kind.PRESENT) {
			return "present"; //$NON-NLS-1$
		}
		if (matcher.getKind() == ArgMatcher.Kind.TOLERANT) {
			return asString(expected);
		}
		return kind + " " + asString(expected); //$NON-NLS-1$
	}

	private static String asString(Object value) {
		return value == null ? "(none)" : String.valueOf(value); //$NON-NLS-1$
	}

	/**
	 * Finds the first persist in {@code pool} matching the assertion's kind, name
	 * (+ scope) and property matchers. Name is compared case-insensitively; a
	 * {@code null} assertion name matches any name (used for child components
	 * identified purely by a property such as {@code dataProviderID}).
	 */
	private IPersist findMatch(OutcomeAssertion assertion, List<IPersist> pool) {
		for (IPersist persist : pool) {
			if (!kindMatches(assertion.kind(), persist)) {
				continue;
			}
			if (assertion.name() != null && !nameMatches(assertion.name(), persist)) {
				continue;
			}
			if (assertion.scope() != null && !scopeMatches(assertion.scope(), persist)) {
				continue;
			}
			if (assertion.name() == null && !propsMatch(assertion, persist)) {
				// name-less (property-identified) match must satisfy props to select it
				continue;
			}
			return persist;
		}
		return null;
	}

	private boolean propsMatch(OutcomeAssertion assertion, IPersist persist) {
		for (Map.Entry<String, ArgMatcher> entry : assertion.props().entrySet()) {
			if (!entry.getValue().matches(readProperty(persist, entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static boolean kindMatches(PersistKind kind, IPersist persist) {
		if (kind == PersistKind.OTHER) {
			return true;
		}
		return persist.getTypeID() == kind.typeId();
	}

	private static boolean nameMatches(String expectedName, IPersist persist) {
		String actual = nameOf(persist);
		return actual != null && actual.equalsIgnoreCase(expectedName);
	}

	/**
	 * Matches a scope for scoped persists (global methods/variables). Best-effort:
	 * compares the assertion scope against a {@code scopeName} property when the
	 * persist carries one; persists without a scope concept always match.
	 */
	private boolean scopeMatches(String expectedScope, IPersist persist) {
		Object scope = readProperty(persist, "scopeName"); //$NON-NLS-1$
		if (scope == null) {
			return true;
		}
		return String.valueOf(scope).equalsIgnoreCase(expectedScope);
	}

	private static String nameOf(IPersist persist) {
		if (persist instanceof ISupportName supportName) {
			return supportName.getName();
		}
		if (persist instanceof AbstractBase base) {
			Object name = base.getProperty("name"); //$NON-NLS-1$
			return name != null ? String.valueOf(name) : null;
		}
		return null;
	}

	/**
	 * Reads a property generically from a persist, trying every place a Servoy
	 * persist can hold one, in order:
	 * <ol>
	 * <li>the explicitly-set flat properties map;</li>
	 * <li>{@link AbstractBase#getProperty} (content-spec properties + defaults);</li>
	 * <li>a JavaBean getter ({@code getX}/{@code isX}) - this is where typed
	 * custom-property accessors such as {@code Form.getUseCssPosition()} and
	 * {@code Form.isResponsiveLayout()} live, which the flat map does NOT expose;</li>
	 * <li>the {@code customProperties} bag directly;</li>
	 * <li>the flattened component JSON (web components'
	 * {@code dataProviderID}/{@code text}).</li>
	 * </ol>
	 */
	private static Object readProperty(IPersist persist, String propertyName) {
		if (!(persist instanceof AbstractBase base)) {
			return null;
		}
		if ("name".equalsIgnoreCase(propertyName)) { //$NON-NLS-1$
			return nameOf(persist);
		}
		Map<String, Object> own = base.getPropertiesMap();
		if (own != null && own.containsKey(propertyName)) {
			return own.get(propertyName);
		}
		try {
			Object viaGetter = base.getProperty(propertyName);
			if (viaGetter != null) {
				return viaGetter;
			}
		} catch (RuntimeException ignore) {
			// not a content-spec property - try the other strategies below
		}
		// Typed bean getters carry custom-property-backed values the flat map hides,
		// e.g. Form.getUseCssPosition() / isResponsiveLayout() read from customProperties.
		Object viaBean = readViaBeanGetter(base, propertyName);
		if (viaBean != null) {
			return viaBean;
		}
		// The customProperties bag (form layout flags, etc.) keyed directly.
		Object viaCustom = readFromCustomProperties(base, propertyName);
		if (viaCustom != null) {
			return viaCustom;
		}
		// Web components keep model properties such as dataProviderID inside their
		// flattened JSON rather than the flat properties map.
		return readFromFlattenedJson(base, propertyName);
	}

	/**
	 * Invokes a JavaBean-style getter for {@code propertyName} ({@code getX} or
	 * {@code isX}) if the persist declares one, via reflection so this bundle does
	 * not hard-depend on every persist subtype.
	 */
	private static Object readViaBeanGetter(AbstractBase base, String propertyName) {
		if (propertyName == null || propertyName.isEmpty()) {
			return null;
		}
		String cap = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
		for (String prefix : new String[] { "get", "is" }) { //$NON-NLS-1$ //$NON-NLS-2$
			try {
				java.lang.reflect.Method m = base.getClass().getMethod(prefix + cap);
				if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
					return m.invoke(base);
				}
			} catch (NoSuchMethodException noSuch) {
				// try the next prefix
			} catch (ReflectiveOperationException | RuntimeException ignore) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Reads a key from the persist's {@code customProperties} bag (e.g. a form's
	 * {@code useCssPosition}), via reflection.
	 */
	private static Object readFromCustomProperties(AbstractBase base, String propertyName) {
		try {
			Object value = base.getCustomProperty(new String[] { propertyName });
			return value;
		} catch (RuntimeException ignore) {
			return null;
		}
	}

	/**
	 * Best-effort read of a property from a persist's flattened component JSON
	 * (e.g. a web component's {@code dataProviderID}/{@code text}), using
	 * reflection so this bundle does not hard-depend on the component JSON API.
	 */
	private static Object readFromFlattenedJson(AbstractBase base, String propertyName) {
		try {
			java.lang.reflect.Method m = base.getClass().getMethod("getFlattenedJson"); //$NON-NLS-1$
			Object json = m.invoke(base);
			if (json != null) {
				java.lang.reflect.Method opt = json.getClass().getMethod("opt", String.class); //$NON-NLS-1$
				Object value = opt.invoke(json, propertyName);
				return value;
			}
		} catch (ReflectiveOperationException | RuntimeException ignore) {
			// persist has no component JSON, or the key is absent - not an error
		}
		return null;
	}

	private List<IPersist> allRootPersists() {
		return childrenOf(solution);
	}

	/** Immediate children of a persist as a list (empty when it has none). */
	private static List<IPersist> childrenOf(IPersist persist) {
		List<IPersist> out = new ArrayList<>();
		if (persist instanceof ISupportChilds childs) {
			Iterator<IPersist> it = childs.getAllObjects();
			while (it != null && it.hasNext()) {
				IPersist child = it.next();
				if (child != null) {
					out.add(child);
				}
			}
		}
		return out;
	}
}

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
import com.servoy.j2db.persistence.Form;
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
			for (OutcomeAssertion child : assertion.children()) {
				// On a responsive form the form's immediate children are layout
				// containers (rows/columns), and the real components live nested
				// several container levels below. Matching only the direct children
				// would report every component "missing" (SVY-21366). So for a child
				// that targets a leaf element (component/field/label), search the whole
				// descendant subtree, descending THROUGH layout containers; for a child
				// that is itself a container (or any other kind), keep the direct-child
				// pool so nesting structure can still be asserted explicitly.
				List<IPersist> childPool = isLeafElementKind(child.kind()) ? descendantsOf(match)
						: childrenOf(match);
				Node childNode = checkOne(child, childPool, diff, indent + "  "); //$NON-NLS-1$
				childNodes.add(childNode);
				ok &= childNode.pass();
			}
		}

		// Optional structural (items-tree) match: serialize the matched persist to
		// its .frm JSON, normalize both sides, and compare order-independently with
		// matcher-aware leaves (SVY-21366).
		if (assertion.structure() != null) {
			Node structureNode = checkStructure(assertion, match, diff, indent);
			childNodes.add(structureNode);
			ok &= structureNode.pass();
		}

		// Optional form .less contract (form assertions only): existence, every
		// referenced class defined, SVG icon masks, colours.
		if (assertion.less() != null && match instanceof Form form) {
			Node lessNode = checkLess(assertion, form, diff, indent);
			childNodes.add(lessNode);
			ok &= lessNode.pass();
		}

		return new Node(assertion.label(), true, ok, "found", propChecks, childNodes); //$NON-NLS-1$
	}

	/**
	 * Serialises {@code match} to its {@code .frm} JSON via
	 * {@link SolutionSerializer#generateJSONObject}, normalises the actual
	 * {@code items} and the expected inline {@code .frm} identically (volatile ids
	 * / names stripped from both), and compares them with {@link StructureMatcher}
	 * (subset per object, items order-independent, SQL-LIKE on string leaves).
	 * Returns a {@link Node} describing the first divergence, if any.
	 */
	private Node checkStructure(OutcomeAssertion assertion, IPersist match, StringBuilder diff, String indent) {
		String label = assertion.label() + " form"; //$NON-NLS-1$
		try {
			com.fasterxml.jackson.databind.JsonNode actualItems = serializeItems(match);
			com.fasterxml.jackson.databind.node.ArrayNode normActual = FormStructureNormalizer
					.normalizeActual(actualItems, assertion.structure());
			com.fasterxml.jackson.databind.node.ArrayNode normExpected = FormStructureNormalizer
					.normalizeExpected(assertion.structure());
			StructureMatcher.Result r = StructureMatcher.matchItems(normExpected, normActual);
			if (r.match()) {
				return new Node(label, true, true, "form matches", List.of(), List.of()); //$NON-NLS-1$
			}
			diff.append(indent).append("- ").append(assertion.label()).append(" form: ") //$NON-NLS-1$ //$NON-NLS-2$
					.append(r.describe()).append('\n');
			return new Node(label, true, false, r.describe(),
					List.of(new PropCheck(r.path(), r.expected(), r.actual(), false)), List.of());
		} catch (RuntimeException e) {
			String msg = "could not serialize/compare form: " + e.getMessage(); //$NON-NLS-1$
			diff.append(indent).append("- ").append(assertion.label()).append(" form: ").append(msg) //$NON-NLS-1$ //$NON-NLS-2$
					.append('\n');
			return new Node(label, true, false, msg, List.of(), List.of());
		}
	}

	/**
	 * Serialises a persist to its {@code .frm} JSON (recursive, non-flattened, the
	 * on-disk shape) and returns the {@code items} array as a Jackson node. Uses
	 * {@link SolutionSerializer#generateJSONObject} then re-parses the
	 * {@code ServoyJSONObject}'s text with Jackson so the normalizer/matcher work
	 * on one JSON model.
	 */
	private static com.fasterxml.jackson.databind.JsonNode serializeItems(IPersist match) {
		try {
			com.servoy.j2db.util.ServoyJSONObject obj = com.servoy.eclipse.model.repository.SolutionSerializer
					.generateJSONObject(match, true, false,
							(com.servoy.j2db.persistence.IDeveloperRepository) match.getRootObject().getRepository(),
							true, null);
			com.fasterxml.jackson.databind.JsonNode root = JACKSON.readTree(obj.toString());
			return root.get("items"); //$NON-NLS-1$
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	/**
	 * Checks a form's {@code .less} contract: the file at
	 * {@code SolutionSerializer.getFormLESSFile(form)} exists / is non-empty, and
	 * (optionally) defines every CSS class the form's {@code .frm} references, has
	 * SVG icon masks, and defines colours. Needs the workbench ({@code IFile}), so
	 * it runs only under the PDE/workbench runner.
	 */
	private Node checkLess(OutcomeAssertion assertion, Form form, StringBuilder diff, String indent) {
		String label = assertion.label() + " .less"; //$NON-NLS-1$
		OutcomeAssertion.LessExpectation exp = assertion.less();
		List<PropCheck> checks = new ArrayList<>();
		boolean ok = true;

		String less = readFormLess(form);
		boolean exists = less != null && !less.isBlank();
		if (exp.exists()) {
			checks.add(new PropCheck("exists", "non-empty forms/" + form.getName() + ".less", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					exists ? "present" : "(absent/empty)", exists)); //$NON-NLS-1$ //$NON-NLS-2$
			ok &= exists;
		}
		if (exists && exp.definesReferencedClasses()) {
			java.util.Set<String> referenced = referencedClasses(form);
			java.util.List<String> missing = new ArrayList<>();
			for (String cls : referenced) {
				if (!definesClass(less, cls)) {
					missing.add(cls);
				}
			}
			boolean pass = missing.isEmpty();
			checks.add(new PropCheck("definesReferencedClasses", referenced.size() + " class(es)", //$NON-NLS-1$ //$NON-NLS-2$
					pass ? "all defined" : "missing: " + missing, pass)); //$NON-NLS-1$ //$NON-NLS-2$
			ok &= pass;
		}
		if (exists && exp.containsSvgIcons()) {
			boolean pass = less.contains("mask-image") && less.contains("data:image/svg+xml"); //$NON-NLS-1$ //$NON-NLS-2$
			checks.add(new PropCheck("containsSvgIcons", "SVG mask-image", //$NON-NLS-1$ //$NON-NLS-2$
					pass ? "present" : "(none)", pass)); //$NON-NLS-1$ //$NON-NLS-2$
			ok &= pass;
		}
		if (exists && exp.containsColors()) {
			boolean pass = HEX_COLOR.matcher(less).find();
			checks.add(new PropCheck("containsColors", "#rrggbb colour", //$NON-NLS-1$ //$NON-NLS-2$
					pass ? "present" : "(none)", pass)); //$NON-NLS-1$ //$NON-NLS-2$
			ok &= pass;
		}
		if (!ok) {
			for (PropCheck c : checks) {
				if (!c.pass()) {
					diff.append(indent).append("- ").append(assertion.label()).append(" .less '") //$NON-NLS-1$ //$NON-NLS-2$
							.append(c.property()).append("' expected ").append(c.expected()) //$NON-NLS-1$
							.append(" but was ").append(c.actual()).append('\n'); //$NON-NLS-1$
				}
			}
		}
		return new Node(label, exists, ok, exists ? "checked" : "missing", checks, List.of()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Reads the form's {@code .less} workspace file as text, or {@code null}. */
	private static String readFormLess(Form form) {
		try {
			org.eclipse.core.resources.IFile file = com.servoy.eclipse.model.repository.SolutionSerializer
					.getFormLESSFile(form);
			if (file == null || !file.exists()) {
				return null;
			}
			try (java.io.InputStream in = file.getContents()) {
				return new String(in.readAllBytes(),
						file.getCharset() != null ? file.getCharset() : "UTF-8"); //$NON-NLS-1$
			}
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Collects every CSS class the form's {@code .frm} references: component
	 * {@code json.styleClass}, container {@code customProperties.attributes.class},
	 * and the {@code class='...'} attributes inside tab/label {@code text} HTML.
	 */
	private static java.util.Set<String> referencedClasses(Form form) {
		java.util.Set<String> classes = new java.util.LinkedHashSet<>();
		try {
			com.fasterxml.jackson.databind.JsonNode root = JACKSON.readTree(
					com.servoy.eclipse.model.repository.SolutionSerializer.generateJSONObject(form, true, false,
							(com.servoy.j2db.persistence.IDeveloperRepository) form.getRootObject().getRepository(),
							true, null).toString());
			collectClasses(root, classes);
		} catch (Exception ignore) {
			// best effort - an unreadable form yields no referenced classes
		}
		return classes;
	}

	private static void collectClasses(com.fasterxml.jackson.databind.JsonNode node,
			java.util.Set<String> out) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			com.fasterxml.jackson.databind.JsonNode sc = node.get("styleClass"); //$NON-NLS-1$
			if (sc != null && sc.isTextual()) {
				addClasses(sc.asText(), out);
			}
			com.fasterxml.jackson.databind.JsonNode attrs = node.at("/customProperties/attributes/class"); //$NON-NLS-1$
			if (attrs != null && attrs.isTextual()) {
				addClasses(attrs.asText(), out);
			}
			com.fasterxml.jackson.databind.JsonNode text = node.get("text"); //$NON-NLS-1$
			if (text != null && text.isTextual()) {
				java.util.regex.Matcher m = CLASS_ATTR.matcher(text.asText());
				while (m.find()) {
					addClasses(m.group(1), out);
				}
			}
			node.fields().forEachRemaining(e -> collectClasses(e.getValue(), out));
		} else if (node.isArray()) {
			node.forEach(c -> collectClasses(c, out));
		}
	}

	private static void addClasses(String value, java.util.Set<String> out) {
		if (value == null) {
			return;
		}
		for (String token : value.trim().split("\\s+")) { //$NON-NLS-1$
			if (!token.isEmpty()) {
				out.add(token);
			}
		}
	}

	/** @return {@code true} if the {@code .less} text has a selector for {@code cls} */
	private static boolean definesClass(String less, String cls) {
		return less.matches("(?s).*(^|[^A-Za-z0-9_-])\\." + java.util.regex.Pattern.quote(cls) //$NON-NLS-1$
				+ "([^A-Za-z0-9_-]|$).*"); //$NON-NLS-1$
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper JACKSON = new com.fasterxml.jackson.databind.ObjectMapper();

	/** {@code class='a b'} or {@code class="a b"} inside tab/label text HTML. */
	private static final java.util.regex.Pattern CLASS_ATTR = java.util.regex.Pattern
			.compile("class\\s*=\\s*['\"]([^'\"]*)['\"]"); //$NON-NLS-1$

	/** A hex colour literal. */
	private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern
			.compile("#[0-9a-fA-F]{3,8}\\b"); //$NON-NLS-1$

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

	/**
	 * All descendants of a persist, flattened depth-first, so a component nested
	 * inside a responsive form's layout containers (rows/columns) is reachable
	 * when matching a form's declared child elements. Layout containers themselves
	 * are included in the pool (so a {@code layoutcontainer} assertion can still
	 * match one), and the walk descends into every {@link ISupportChilds}.
	 */
	private static List<IPersist> descendantsOf(IPersist persist) {
		List<IPersist> out = new ArrayList<>();
		collectDescendants(persist, out);
		return out;
	}

	private static void collectDescendants(IPersist persist, List<IPersist> out) {
		if (!(persist instanceof ISupportChilds childs)) {
			return;
		}
		Iterator<IPersist> it = childs.getAllObjects();
		while (it != null && it.hasNext()) {
			IPersist child = it.next();
			if (child == null) {
				continue;
			}
			out.add(child);
			collectDescendants(child, out);
		}
	}

	/**
	 * @return {@code true} for assertion kinds that target a leaf form element
	 *         (web component, field, graphical component/label), which on a
	 *         responsive form may be nested inside layout containers and so must
	 *         be searched for across the whole descendant subtree, not just the
	 *         parent's direct children
	 */
	private static boolean isLeafElementKind(PersistKind kind) {
		return kind == PersistKind.COMPONENT || kind == PersistKind.FIELD
				|| kind == PersistKind.GRAPHICALCOMPONENT;
	}
}

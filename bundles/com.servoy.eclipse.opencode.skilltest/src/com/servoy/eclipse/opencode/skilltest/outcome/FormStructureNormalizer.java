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

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.j2db.persistence.IDeveloperRepository;
import com.servoy.j2db.persistence.IPersist;

/**
 * Normalises a Servoy form's {@code .frm} JSON (as produced by
 * {@code SolutionSerializer.generateJSONObject}) into a canonical form for the
 * structural comparison of {@code expect.persists} form outcomes (SVY-21366).
 * <p>
 * The expected tree a baseline carries ({@code structure}) and the actual
 * generated form are run through the <b>same</b> normalisation, so they agree by
 * construction and the comparison only sees spec-derived structure, not the
 * volatile bits that legitimately differ between LLM runs.
 * </p>
 * <p>
 * Rules (confirmed against two real Jenkins-generated {@code order_tabs.frm}):
 * </p>
 * <ul>
 * <li><b>Stripped everywhere</b> - {@code uuid}, {@code typeid},
 * {@code containedForm} (a {@code uuid} cross-reference); form chrome
 * {@code navigatorID}, {@code showInMenu}, {@code size}, {@code encapsulation}
 * is kept only when the <em>expected</em> side asked for it (see below).</li>
 * <li><b>De-duplicated</b> - a component carries both a top-level
 * {@code styleClass}/{@code location} and the same inside its {@code json}; the
 * top-level copies are dropped, the {@code json} copy is the source of
 * truth.</li>
 * <li><b>{@code name} stripped unless explicit</b> - the <em>expected</em> tree
 * drives which names matter: a name on the actual side is kept only when the
 * expected node at the same position carries one. The form's own
 * {@code name} is matched separately (the assertion's {@code name}) and is not
 * part of the structure.</li>
 * <li><b>Kept</b> - the {@code items} nesting (compared order-independently by
 * {@link StructureMatcher} because the serializer sorts children by uuid),
 * {@code customProperties} ({@code layout.responsive},
 * {@code attributes.class}, {@code properties.packagename}/{@code specname}),
 * {@code typeName}, and the whole {@code json} block ({@code text},
 * {@code styleClass}, {@code location}, {@code activeTabIndex}, the tabpanel
 * {@code tabs} array with each tab's {@code text}/{@code disabled}).</li>
 * </ul>
 * <p>
 * The expected side is the tester's inline {@code .frm} JSON. It carries no
 * special matcher objects - every scalar is compared literally by
 * {@link StructureMatcher}, except that a string may be a SQL-{@code LIKE}
 * pattern. Running the expected side through the same strip keeps a hand-edited
 * expectation in step with the actual: a volatile key the tester left in by
 * copy-pasting a real {@code .frm} (a {@code uuid}, a {@code typeid}) is dropped
 * from both sides rather than forcing a mismatch.
 * </p>
 */
public final class FormStructureNormalizer { // SVY-21366 structural match

	/**
	 * Keys removed from every node: volatile ids / cross-references ({@code uuid},
	 * {@code typeid}, {@code containedForm}, and the per-tab {@code svyUUID} the
	 * serializer stamps on a tabpanel's {@code tabs}) and the persist {@code name}
	 * (an agent free-choice - {@code lbl_title}, {@code tab_overview} - never
	 * dictated by the spec). The form's own name is matched separately via the
	 * assertion's {@code name}, not the structure.
	 */
	private static final String[] ALWAYS_STRIP = { "uuid", "typeid", "containedForm", "name", "svyUUID" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	/**
	 * Top-level persist keys that merely duplicate a {@code json.*} value and so
	 * are dropped in favour of the {@code json} copy.
	 */
	private static final String[] DUP_OF_JSON = { "styleClass", "location" }; //$NON-NLS-1$ //$NON-NLS-2$

	/** Form-level chrome stripped from the root (engine/agent defaults). */
	private static final String[] FORM_CHROME = { "navigatorID", "showInMenu", "size" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private FormStructureNormalizer() {
	}

	/**
	 * Captures a persist's live {@code .frm} JSON and returns its {@code items}
	 * array <b>already stripped</b> - the inline expected {@code .frm} a baseline
	 * stores under the assertion's {@code form} key. Serialises with
	 * {@code SolutionSerializer.generateJSONObject} (recursive, non-flattened, the
	 * on-disk shape), re-parses with Jackson, then runs {@link #normalizeExpected}
	 * so what the tester edits is the same canonical form the checker compares at
	 * run time: no uuids, type ids, persist names or {@code json} dupes.
	 * <p>
	 * This is the "inspect the workspace" source behind <i>Edit expected
	 * outcomes</i> and the import/confirm screen: the editor is pre-filled from the
	 * real form that is in the workspace, and the tester tweaks it (trims keys,
	 * turns a literal into a SQL-{@code LIKE} pattern) rather than hand-writing it.
	 * </p>
	 *
	 * @param persist the live persist (a form) to capture; may be {@code null}
	 * @return the stripped {@code items} array, or {@code null} when the persist is
	 *         {@code null} or cannot be serialised (caller leaves the assertion's
	 *         {@code form} untouched)
	 */
	public static ArrayNode captureStrippedItems(IPersist persist) {
		if (persist == null) {
			return null;
		}
		try {
			com.servoy.j2db.util.ServoyJSONObject obj = com.servoy.eclipse.model.repository.SolutionSerializer
					.generateJSONObject(persist, true, false,
							(IDeveloperRepository) persist.getRootObject().getRepository(), true, null);
			JsonNode root = MAPPER.readTree(obj.toString());
			JsonNode items = root.get("items"); //$NON-NLS-1$
			if (items == null || !items.isArray()) {
				return null;
			}
			return normalizeExpected(items);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Normalises an actual form's {@code .frm} JSON for comparison against an
	 * expected {@code structure}. The expected side is normalised with
	 * {@link #normalizeExpected(JsonNode)} so {@code name} stripping can be driven
	 * by it.
	 *
	 * @param actualItems the {@code items} array of the actual form (may be
	 *                    {@code null})
	 * @param expected    unused; kept for call-site compatibility
	 * @return the normalised actual {@code items} array (never {@code null})
	 */
	public static ArrayNode normalizeActual(JsonNode actualItems, JsonNode expected) {
		return normalize(actualItems);
	}

	/**
	 * Normalises an expected {@code .frm} items array a baseline carries inline.
	 * The same keys are stripped as on the actual side, so a tester who pasted a
	 * real {@code .frm} and left the volatile ids in still lines up - the strip
	 * drops them from both sides rather than forcing a mismatch.
	 *
	 * @param expectedItems the expected {@code .frm} items array (may be
	 *                      {@code null})
	 * @return the normalised expected array (never {@code null})
	 */
	public static ArrayNode normalizeExpected(JsonNode expectedItems) {
		return normalize(expectedItems);
	}

	/** Runs {@link #normalizeNode} over each element of an items-style array. */
	private static ArrayNode normalize(JsonNode items) {
		ArrayNode out = JsonNodeFactoryHolder.FACTORY.arrayNode();
		if (items != null && items.isArray()) {
			for (JsonNode item : items) {
				out.add(normalizeNode(item));
			}
		}
		return out;
	}

	/**
	 * Normalises one node recursively, as a <b>persist-level</b> object.
	 * {@link #ALWAYS_STRIP} keys are dropped at every depth (including inside the
	 * {@code json} block and inside any array such as a tabpanel's {@code tabs}),
	 * while {@link #DUP_OF_JSON} ({@code styleClass}/{@code location}) and
	 * {@link #FORM_CHROME} are dropped only at this persist level - the copies
	 * inside {@code json} are the source of truth and must survive.
	 */
	private static JsonNode normalizeNode(JsonNode node) {
		if (node == null || !node.isObject()) {
			return node == null ? JsonNodeFactoryHolder.FACTORY.nullNode() : node;
		}
		ObjectNode src = (ObjectNode) node;
		ObjectNode out = JsonNodeFactoryHolder.FACTORY.objectNode();
		for (Iterator<Map.Entry<String, JsonNode>> it = src.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			if (isAlwaysStripped(key) || isPersistLevelStripped(key)) {
				continue;
			}
			JsonNode value = e.getValue();
			if ("items".equals(key) && value.isArray()) { //$NON-NLS-1$
				// Nested persists (layout containers / components) - persist-level rules
				// apply again to each child.
				ArrayNode items = JsonNodeFactoryHolder.FACTORY.arrayNode();
				for (JsonNode child : value) {
					items.add(normalizeNode(child));
				}
				out.set("items", items); //$NON-NLS-1$
			} else {
				// Everything else (json block, tabs arrays, scalars) is value content:
				// recurse with ALWAYS_STRIP only, never the persist-level dedup.
				out.set(key, normalizeValue(value));
			}
		}
		return out;
	}

	/**
	 * Normalises a value subtree (the {@code json} block, a {@code tabs} array, a
	 * scalar). Only {@link #ALWAYS_STRIP} applies here - so a {@code containedForm}
	 * inside {@code json.tabs[]} is removed, but a {@code styleClass} inside
	 * {@code json} is kept. Recurses through objects and <b>every</b> array.
	 */
	private static JsonNode normalizeValue(JsonNode node) {
		if (node == null) {
			return JsonNodeFactoryHolder.FACTORY.nullNode();
		}
		if (node.isObject()) {
			ObjectNode out = JsonNodeFactoryHolder.FACTORY.objectNode();
			for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> e = it.next();
				if (isAlwaysStripped(e.getKey())) {
					continue;
				}
				out.set(e.getKey(), normalizeValue(e.getValue()));
			}
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = JsonNodeFactoryHolder.FACTORY.arrayNode();
			for (JsonNode child : node) {
				out.add(normalizeValue(child));
			}
			return out;
		}
		return node;
	}

	/** uuid / typeid / containedForm - stripped at every depth. */
	private static boolean isAlwaysStripped(String key) {
		for (String k : ALWAYS_STRIP) {
			if (k.equals(key)) {
				return true;
			}
		}
		return false;
	}

	/** styleClass/location dedup and form chrome - stripped only at persist level. */
	private static boolean isPersistLevelStripped(String key) {
		for (String k : DUP_OF_JSON) {
			if (k.equals(key)) {
				return true;
			}
		}
		for (String k : FORM_CHROME) {
			if (k.equals(key)) {
				return true;
			}
		}
		return false;
	}

	/** Lazily-held shared node factory, so this class needs no constructor state. */
	private static final class JsonNodeFactoryHolder {
		static final com.fasterxml.jackson.databind.node.JsonNodeFactory FACTORY = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
	}
}

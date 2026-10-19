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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compares an expected form {@code .frm} JSON against an actual form tree (the
 * actual side normalised by {@link FormStructureNormalizer} to drop volatile
 * properties). The expected side is the real {@code .frm} JSON a tester authors
 * inline in {@code baseline.json}; nothing in it is a special matcher object -
 * every scalar is compared literally, except that a <b>string</b> may be a
 * <b>SQL-{@code LIKE} pattern</b> (SVY-21366).
 * <p>
 * Match rules:
 * </p>
 * <ul>
 * <li><b>Objects - subset</b>: every key the <em>expected</em> node has must
 * match; keys only the actual node has are ignored. A property the tester does
 * not care about is simply left out of the expected {@code .frm}.</li>
 * <li><b>{@code items} arrays - order-independent</b>: the serializer sorts a
 * persist's children by uuid, so document order is not meaningful. Each expected
 * item must match some not-yet-consumed actual item; sizes must be equal.</li>
 * <li><b>Other arrays (e.g. a tabpanel {@code tabs}) - positional</b>: these
 * carry authored order from the component model, so they are compared slot by
 * slot and must be the same length.</li>
 * <li><b>Scalar leaves</b> - a string is matched with {@link #like} ({@code %}
 * = any run of characters incl. empty, {@code _} = one character,
 * case-insensitive, anchored over the whole value; {@code \%}/{@code \_} escape
 * a literal). A string with no wildcard is a literal equals. Numbers and
 * booleans compare equal by value.</li>
 * </ul>
 * The first failure is reported as a dotted/indexed JSON path (e.g.
 * {@code items[*].json.tabs[4].disabled}) with the expected and actual values,
 * so a UI/console can point straight at what diverged.
 * <p>
 * Tier-2 (not built here): a genuinely fuzzy "does this look like the mockup"
 * verdict is out of reach of any structural rule and is left to a second-LLM
 * judge; {@link #matchItems} is the structural tier that runs first.
 */
public final class StructureMatcher { // SVY-21366 .frm subset compare with SQL-LIKE leaves

	/** The outcome of a structural comparison. */
	public record Result(boolean match, String path, String expected, String actual) {
		static Result ok() {
			return new Result(true, null, null, null);
		}

		static Result fail(String path, String expected, String actual) {
			return new Result(false, path, expected, actual);
		}

		/** @return a one-line human description of the first mismatch, or {@code null} on match */
		public String describe() {
			if (match) {
				return null;
			}
			return path + ": expected " + expected + " but was " + actual; //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private StructureMatcher() {
	}

	/**
	 * Compares an expected {@code structure} array against an actual {@code items}
	 * array (both normalised). Treated as an order-independent {@code items} match
	 * at the root.
	 *
	 * @param expected normalised expected structure array (never {@code null})
	 * @param actual   normalised actual items array (never {@code null})
	 * @return the result; {@link Result#match()} is {@code true} when every
	 *         expected node matched some actual node
	 */
	public static Result matchItems(JsonNode expected, JsonNode actual) {
		return matchUnorderedArray(expected, actual, "items"); //$NON-NLS-1$
	}

	/** Dispatches a comparison based on the expected node's JSON shape. */
	private static Result match(JsonNode expected, JsonNode actual, String path) {
		if (expected == null || expected.isValueNode() || expected.isNull()) {
			return matchLeaf(expected, actual, path);
		}
		if (expected.isObject()) {
			return matchObject(expected, actual, path);
		}
		if (expected.isArray()) {
			// A plain array that is not reached via an "items" key is positional.
			return matchPositionalArray(expected, actual, path);
		}
		return matchLeaf(expected, actual, path);
	}

	private static Result matchObject(JsonNode expected, JsonNode actual, String path) {
		if (actual == null || !actual.isObject()) {
			return Result.fail(path, "object", describe(actual)); //$NON-NLS-1$
		}
		for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			String key = e.getKey();
			JsonNode expectedChild = e.getValue();
			JsonNode actualChild = actual.get(key);
			String childPath = path.isEmpty() ? key : path + "." + key; //$NON-NLS-1$
			if ("items".equals(key)) { //$NON-NLS-1$
				Result r = matchUnorderedArray(expectedChild, actualChild, childPath);
				if (!r.match()) {
					return r;
				}
			} else {
				if (actualChild == null) {
					return Result.fail(childPath, describe(expectedChild), "(absent)"); //$NON-NLS-1$
				}
				Result r = match(expectedChild, actualChild, childPath);
				if (!r.match()) {
					return r;
				}
			}
		}
		return Result.ok();
	}

	/**
	 * Order-independent array match: every expected element must match some actual
	 * element not already consumed, and the arrays must be the same size.
	 */
	private static Result matchUnorderedArray(JsonNode expected, JsonNode actual, String path) {
		if (expected == null || !expected.isArray()) {
			return match(expected, actual, path);
		}
		if (actual == null || !actual.isArray()) {
			return Result.fail(path, "array[" + expected.size() + "]", describe(actual)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (expected.size() != actual.size()) {
			return Result.fail(path, "array of " + expected.size() + " item(s)", //$NON-NLS-1$ //$NON-NLS-2$
					"array of " + actual.size() + " item(s)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<JsonNode> remaining = new ArrayList<>();
		actual.forEach(remaining::add);
		for (int i = 0; i < expected.size(); i++) {
			JsonNode expectedItem = expected.get(i);
			int found = -1;
			Result lastReason = null;
			for (int j = 0; j < remaining.size(); j++) {
				Result r = match(expectedItem, remaining.get(j), path + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
				if (r.match()) {
					found = j;
					break;
				}
				lastReason = r;
			}
			if (found < 0) {
				// When only one actual item is left to consider, its own leaf-level
				// mismatch is the real reason - surface it instead of a generic
				// "no matching item", so the diff points at the diverging property.
				if (remaining.size() == 1 && lastReason != null) {
					return lastReason;
				}
				return Result.fail(path + "[" + i + "]", describe(expectedItem), //$NON-NLS-1$ //$NON-NLS-2$
						"no matching item among " + remaining.size() + " remaining"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			remaining.remove(found);
		}
		return Result.ok();
	}

	/** Positional array match (same length, element-by-element). */
	private static Result matchPositionalArray(JsonNode expected, JsonNode actual, String path) {
		if (actual == null || !actual.isArray()) {
			return Result.fail(path, "array[" + expected.size() + "]", describe(actual)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (expected.size() != actual.size()) {
			return Result.fail(path, "array of " + expected.size() + " item(s)", //$NON-NLS-1$ //$NON-NLS-2$
					"array of " + actual.size() + " item(s)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		for (int i = 0; i < expected.size(); i++) {
			Result r = match(expected.get(i), actual.get(i), path + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!r.match()) {
				return r;
			}
		}
		return Result.ok();
	}

	/**
	 * Compares a scalar leaf. A string is matched with SQL-{@code LIKE}
	 * ({@link #like}); numbers and booleans compare equal by value; JSON
	 * {@code null} matches a {@code null}/absent actual.
	 */
	private static Result matchLeaf(JsonNode expected, JsonNode actual, String path) {
		if (expected == null || expected.isNull()) {
			if (actual == null || actual.isNull()) {
				return Result.ok();
			}
			return Result.fail(path, "null", describe(actual)); //$NON-NLS-1$
		}
		if (actual == null || actual.isNull()) {
			return Result.fail(path, describe(expected), "(none)"); //$NON-NLS-1$
		}
		if (expected.isTextual()) {
			if (actual.isValueNode() && like(expected.asText(), actual.asText())) {
				return Result.ok();
			}
			return Result.fail(path, describe(expected), describe(actual));
		}
		if (expected.isBoolean()) {
			if (actual.isBoolean() && expected.booleanValue() == actual.booleanValue()) {
				return Result.ok();
			}
			return Result.fail(path, describe(expected), describe(actual));
		}
		if (expected.isNumber()) {
			if (actual.isNumber() && expected.asDouble() == actual.asDouble()) {
				return Result.ok();
			}
			return Result.fail(path, describe(expected), describe(actual));
		}
		// Fallback: textual equality of the rendered forms.
		if (expected.asText().equals(describe(actual))) {
			return Result.ok();
		}
		return Result.fail(path, describe(expected), describe(actual));
	}

	/**
	 * SQL-{@code LIKE} match, anchored over the whole value and case-insensitive.
	 * {@code %} matches any run of characters (including none), {@code _} matches
	 * exactly one character, and {@code \%}/{@code \_}/{@code \\} escape a literal
	 * {@code %}/{@code _}/backslash. A pattern with no unescaped wildcard reduces
	 * to a case-insensitive equals.
	 *
	 * @param pattern the expected string, possibly carrying {@code %}/{@code _}
	 * @param value   the actual string
	 * @return {@code true} if {@code value} matches {@code pattern}
	 */
	static boolean like(String pattern, String value) {
		if (pattern == null || value == null) {
			return pattern == value;
		}
		StringBuilder re = new StringBuilder("(?is)^"); //$NON-NLS-1$ - case-insensitive, dotall
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\\' && i + 1 < pattern.length()) {
				char next = pattern.charAt(++i);
				if (next == '%' || next == '_' || next == '\\') {
					re.append(java.util.regex.Pattern.quote(String.valueOf(next)));
				} else {
					re.append(java.util.regex.Pattern.quote("\\")).append(java.util.regex.Pattern.quote(String.valueOf(next))); //$NON-NLS-1$
				}
			} else if (c == '%') {
				re.append(".*"); //$NON-NLS-1$
			} else if (c == '_') {
				re.append('.');
			} else {
				re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
			}
		}
		re.append('$');
		return value.matches(re.toString());
	}

	private static String describe(JsonNode node) {
		if (node == null || node.isNull()) {
			return "(none)"; //$NON-NLS-1$
		}
		String s = node.isValueNode() ? node.asText() : node.toString();
		return s.length() > 120 ? s.substring(0, 117) + "..." : s; //$NON-NLS-1$
	}
}

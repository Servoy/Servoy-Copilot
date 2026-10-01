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
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * Parses and serialises the matcher grammar shared by the transcript
 * {@code argOverrides} and the outcome {@code props}: a JSON value is either a
 * bare scalar/array (tolerant compare) or a single-key matcher object
 * ({@code exact}, {@code equals}, {@code contains}, {@code containsAll},
 * {@code regex}, {@code present}). Centralised here so the loader, the outcome
 * model, and the assertion editor all agree on one representation (SVY-21366).
 */
public final class MatcherJson {

	private MatcherJson() {
	}

	/**
	 * Parses a matcher from a JSON value.
	 *
	 * @param node the matcher JSON (scalar, array, or single-key matcher object)
	 * @return the matcher, or {@code null} when {@code node} is {@code null}
	 */
	public static ArgMatcher parse(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isObject()) {
			if (node.has("exact")) { //$NON-NLS-1$
				return ArgMatcher.exact(toValue(node.get("exact"))); //$NON-NLS-1$
			}
			if (node.has("equals")) { //$NON-NLS-1$
				return ArgMatcher.equalsMatcher(toValue(node.get("equals"))); //$NON-NLS-1$
			}
			if (node.has("contains")) { //$NON-NLS-1$
				return ArgMatcher.contains(toValue(node.get("contains"))); //$NON-NLS-1$
			}
			if (node.has("containsAll")) { //$NON-NLS-1$
				return ArgMatcher.containsAll(toValue(node.get("containsAll"))); //$NON-NLS-1$
			}
			if (node.has("regex")) { //$NON-NLS-1$
				return ArgMatcher.regex(toValue(node.get("regex"))); //$NON-NLS-1$
			}
			if (node.has("present")) { //$NON-NLS-1$
				return ArgMatcher.present();
			}
		}
		return ArgMatcher.tolerant(toValue(node));
	}

	/**
	 * Serialises a matcher back to its JSON form: a bare value for
	 * {@code TOLERANT}, else a single-key matcher object.
	 *
	 * @param matcher the matcher (never {@code null})
	 * @param f       the node factory
	 * @return the JSON node
	 */
	public static JsonNode toJson(ArgMatcher matcher, JsonNodeFactory f) {
		Object expected = matcher.getExpected();
		switch (matcher.getKind()) {
		case PRESENT:
			return f.objectNode().put("present", true); //$NON-NLS-1$
		case EXACT:
			return f.objectNode().set("exact", valueNode(expected, f)); //$NON-NLS-1$
		case EQUALS:
			return f.objectNode().set("equals", valueNode(expected, f)); //$NON-NLS-1$
		case CONTAINS:
			return f.objectNode().set("contains", valueNode(expected, f)); //$NON-NLS-1$
		case CONTAINS_ALL:
			return f.objectNode().set("containsAll", valueNode(expected, f)); //$NON-NLS-1$
		case REGEX:
			return f.objectNode().set("regex", valueNode(expected, f)); //$NON-NLS-1$
		case TOLERANT:
		default:
			return valueNode(expected, f);
		}
	}

	private static JsonNode valueNode(Object value, JsonNodeFactory f) {
		if (value == null) {
			return f.nullNode();
		}
		if (value instanceof Boolean b) {
			return f.booleanNode(b);
		}
		if (value instanceof Integer i) {
			return f.numberNode(i);
		}
		if (value instanceof Long l) {
			return f.numberNode(l);
		}
		if (value instanceof Double d) {
			return f.numberNode(d);
		}
		if (value instanceof List<?> list) {
			var arr = f.arrayNode();
			for (Object element : list) {
				arr.add(valueNode(element, f));
			}
			return arr;
		}
		return f.textNode(String.valueOf(value));
	}

	private static Object toValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isInt() || node.isLong()) {
			return node.asLong();
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		if (node.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode child : node) {
				list.add(toValue(child));
			}
			return list;
		}
		return node.asText();
	}
}

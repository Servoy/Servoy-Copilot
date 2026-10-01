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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * Parses and serialises the {@code expect.persists} block of a
 * {@code baseline.json} sidecar to and from {@link OutcomeAssertion}s
 * (SVY-21366).
 * <p>
 * Sidecar shape:
 * </p>
 *
 * <pre>
 * "expect": {
 *   "persists": [
 *     {
 *       "kind": "form",
 *       "name": "customerDetail",
 *       "exists": true,
 *       "props": { "dataSource": "db:/example_data/customers", "useCssPosition": true },
 *       "children": [
 *         { "kind": "component", "props": { "dataProviderID": "companyname" } },
 *         { "kind": "component", "props": { "dataProviderID": "contactname" } }
 *       ]
 *     },
 *     { "kind": "valuelist", "name": "colors", "props": { "customValues": "one\ntwo\nthree" } }
 *   ]
 * }
 * </pre>
 *
 * <p>
 * A {@code props} value is either a scalar/array (compared tolerantly) or a
 * matcher object like {@code { "containsAll": [ ... ] }} / {@code { "exact":
 * "..." }} - the same matcher grammar the transcript {@code argOverrides} use.
 * </p>
 */
public final class OutcomeAssertions {

	private OutcomeAssertions() {
	}

	/**
	 * Parses the {@code expect} node into a flat, ordered list of top-level
	 * assertions (each may carry nested {@code children}).
	 *
	 * @param expectNode the {@code expect} JSON node (may be {@code null})
	 * @return the parsed assertions (never {@code null}; empty when none)
	 */
	public static List<OutcomeAssertion> parse(JsonNode expectNode) {
		List<OutcomeAssertion> result = new ArrayList<>();
		if (expectNode == null || expectNode.isNull()) {
			return result;
		}
		JsonNode persists = expectNode.get("persists"); //$NON-NLS-1$
		if (persists == null || !persists.isArray()) {
			return result;
		}
		for (JsonNode node : persists) {
			OutcomeAssertion assertion = parseAssertion(node);
			if (assertion != null) {
				result.add(assertion);
			}
		}
		return result;
	}

	private static OutcomeAssertion parseAssertion(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		PersistKind kind = PersistKind.fromToken(text(node, "kind")); //$NON-NLS-1$
		String name = text(node, "name"); //$NON-NLS-1$
		String scope = text(node, "scope"); //$NON-NLS-1$
		boolean exists = node.path("exists").asBoolean(true); //$NON-NLS-1$

		Map<String, ArgMatcher> props = new LinkedHashMap<>();
		JsonNode propsNode = node.get("props"); //$NON-NLS-1$
		if (propsNode != null && propsNode.isObject()) {
			for (Map.Entry<String, JsonNode> e : propsNode.properties()) {
				ArgMatcher matcher = MatcherJson.parse(e.getValue());
				if (matcher != null) {
					props.put(e.getKey(), matcher);
				}
			}
		}

		List<OutcomeAssertion> children = new ArrayList<>();
		JsonNode childrenNode = node.get("children"); //$NON-NLS-1$
		if (childrenNode != null && childrenNode.isArray()) {
			for (JsonNode c : childrenNode) {
				OutcomeAssertion child = parseAssertion(c);
				if (child != null) {
					children.add(child);
				}
			}
		}
		return new OutcomeAssertion(kind, name, scope, exists, props, children);
	}

	/**
	 * Serialises assertions back into an {@code expect} object node for writing
	 * into {@code baseline.json}.
	 *
	 * @param assertions the assertions to serialise (may be empty)
	 * @return an {@code expect} object node ({@code { "persists": [ ... ] }})
	 */
	public static ObjectNode toJson(List<OutcomeAssertion> assertions) {
		JsonNodeFactory f = JsonNodeFactory.instance;
		ObjectNode expect = f.objectNode();
		ArrayNode arr = expect.putArray("persists"); //$NON-NLS-1$
		if (assertions != null) {
			for (OutcomeAssertion a : assertions) {
				arr.add(assertionToJson(a, f));
			}
		}
		return expect;
	}

	private static ObjectNode assertionToJson(OutcomeAssertion a, JsonNodeFactory f) {
		ObjectNode node = f.objectNode();
		node.put("kind", a.kind().token()); //$NON-NLS-1$
		if (a.name() != null) {
			node.put("name", a.name()); //$NON-NLS-1$
		}
		if (a.scope() != null) {
			node.put("scope", a.scope()); //$NON-NLS-1$
		}
		if (!a.exists()) {
			node.put("exists", false); //$NON-NLS-1$
		}
		if (!a.props().isEmpty()) {
			ObjectNode props = node.putObject("props"); //$NON-NLS-1$
			for (Map.Entry<String, ArgMatcher> e : a.props().entrySet()) {
				props.set(e.getKey(), MatcherJson.toJson(e.getValue(), f));
			}
		}
		if (!a.children().isEmpty()) {
			ArrayNode children = node.putArray("children"); //$NON-NLS-1$
			for (OutcomeAssertion c : a.children()) {
				children.add(assertionToJson(c, f));
			}
		}
		return node;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
	}
}

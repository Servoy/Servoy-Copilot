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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Unit tests for {@link FormStructureNormalizer} - pure JSON logic, no workbench
 * (SVY-21366). Uses a trimmed copy of a real Jenkins-generated
 * {@code order_tabs.frm} items subtree as the fixture.
 */
class FormStructureNormalizerTest { // SVY-21366

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A single component as the serializer writes it: top-level + json dupes, uuid, typeid. */
	private static final String COMPONENT = """
			{
			  "location": "1,1",
			  "name": "lbl_title",
			  "styleClass": "ot-title",
			  "typeName": "bootstrapcomponents-label",
			  "typeid": 47,
			  "uuid": "45433C1B-7037-4123-B1F2-15A1EE896045",
			  "json": { "location": {"x":1,"y":1}, "styleClass": "ot-title", "text": "Order 10643" }
			}
			""";

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s);
	}

	@Test
	@DisplayName("strips uuid, typeid and the top-level styleClass/location dupes")
	void stripsVolatileAndDupes() throws Exception {
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(json(COMPONENT));
		JsonNode out = FormStructureNormalizer.normalizeActual(arr, null).get(0);

		assertNull(out.get("uuid"), "uuid must be stripped");
		assertNull(out.get("typeid"), "typeid must be stripped");
		assertNull(out.get("styleClass"), "top-level styleClass (dup of json) must be stripped");
		assertNull(out.get("location"), "top-level location (dup of json) must be stripped");
		// kept
		assertEquals("bootstrapcomponents-label", out.get("typeName").asText());
		assertEquals("ot-title", out.at("/json/styleClass").asText());
		assertEquals("Order 10643", out.at("/json/text").asText());
	}

	@Test
	@DisplayName("strips the persist name (an agent free-choice, never spec-dictated)")
	void stripsName() throws Exception {
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(json(COMPONENT));
		JsonNode out = FormStructureNormalizer.normalizeActual(arr, null).get(0);
		assertNull(out.get("name"), "persist name must be stripped");
	}

	@Test
	@DisplayName("strips containedForm and name from a tabpanel's tabs but keeps text and disabled")
	void stripsContainedFormKeepsTabText() throws Exception {
		String tabpanel = """
				{
				  "name": "tabs_panel",
				  "styleClass": "ot-tabs-panel",
				  "typeName": "bootstrapcomponents-tabpanel",
				  "typeid": 47,
				  "uuid": "3A969F2F-D848-4457-8C27-71701BD090CA",
				  "json": {
				    "activeTabIndex": 0,
				    "styleClass": "ot-tabs-panel",
				    "tabs": [
				      { "containedForm": "D8B7B55A-3FD3-4C79-80E0-89A7726DD1EE", "svyUUID": "7f9acc2a-b8f0-4880-b3bd-f3d9e008a910", "name": "tab_overview", "text": "Overview" },
				      { "disabled": true, "svyUUID": "b11712b0-d524-4a15-a764-bdebd127d95c", "name": "tab_history", "text": "History" }
				    ]
				  }
				}
				""";
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(json(tabpanel));
		JsonNode out = FormStructureNormalizer.normalizeActual(arr, null).get(0);

		JsonNode tabs = out.at("/json/tabs");
		assertEquals(2, tabs.size());
		assertNull(tabs.get(0).get("containedForm"), "containedForm must be stripped");
		assertNull(tabs.get(0).get("name"), "tab name must be stripped");
		assertNull(tabs.get(0).get("svyUUID"), "tab svyUUID must be stripped");
		assertEquals("Overview", tabs.get(0).get("text").asText());
		assertTrue(tabs.get(1).get("disabled").asBoolean(), "disabled must be kept");
		assertNull(tabs.get(1).get("svyUUID"), "tab svyUUID must be stripped");
		assertEquals("History", tabs.get(1).get("text").asText());
	}

	@Test
	@DisplayName("recurses into nested items containers")
	void recursesIntoItems() throws Exception {
		String container = """
				{
				  "customProperties": { "attributes": { "class": "ot-head" }, "properties": { "packagename": "12grid", "specname": "div" } },
				  "location": "1,1",
				  "name": "ot_head",
				  "typeid": 46,
				  "uuid": "82F7D0AD-AFE8-4A87-B115-C7A8202BD532",
				  "items": [ %s ]
				}
				""".formatted(COMPONENT);
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(json(container));
		JsonNode out = FormStructureNormalizer.normalizeActual(arr, null).get(0);

		assertNull(out.get("uuid"));
		assertNull(out.get("typeid"));
		assertNull(out.get("location"), "container top-level location must be stripped");
		assertEquals("div", out.at("/customProperties/properties/specname").asText());
		assertEquals("ot-head", out.at("/customProperties/attributes/class").asText());
		JsonNode child = out.get("items").get(0);
		assertNull(child.get("uuid"));
		assertEquals("bootstrapcomponents-label", child.get("typeName").asText());
	}

	@Test
	@DisplayName("expected side strips the same volatile keys as the actual side")
	void expectedStripsSameKeys() throws Exception {
		// A tester who pasted a real .frm into the baseline left uuid/typeid/name in;
		// the expected side must drop them too, else the paste would force a mismatch.
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(json(COMPONENT));
		JsonNode out = FormStructureNormalizer.normalizeExpected(arr).get(0);
		assertNull(out.get("uuid"), "uuid stripped on expected side");
		assertNull(out.get("typeid"), "typeid stripped on expected side");
		assertNull(out.get("name"), "name stripped on expected side");
		assertNull(out.get("styleClass"), "top-level styleClass dup stripped on expected side");
		// the json leaf survives as a plain string (no matcher grammar)
		assertEquals("Order 10643", out.at("/json/text").asText());
		assertEquals("ot-title", out.at("/json/styleClass").asText());
	}

	@Test
	@DisplayName("empty / null input yields an empty array, never null")
	void nullSafe() {
		assertEquals(0, FormStructureNormalizer.normalizeActual(null, null).size());
		assertEquals(0, FormStructureNormalizer.normalizeExpected(null).size());
		assertFalse(FormStructureNormalizer.normalizeExpected(null).isNull());
	}
}

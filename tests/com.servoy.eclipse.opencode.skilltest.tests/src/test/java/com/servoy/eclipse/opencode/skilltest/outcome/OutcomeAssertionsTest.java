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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * Unit tests for the outcome sidecar parsing/serialisation
 * ({@link OutcomeAssertions}) and the matcher grammar ({@link MatcherJson}) -
 * pure logic, no workbench (SVY-21366).
 */
public class OutcomeAssertionsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s);
	}

	@Test
	void parsesFormWithPropsAndChildren() throws Exception {
		JsonNode expect = json("""
				{ "persists": [
				  { "kind": "form", "name": "customerDetail",
				    "props": { "dataSource": "db:/example_data/customers", "useCssPosition": true },
				    "children": [
				      { "kind": "component", "props": { "dataProviderID": "companyname" } },
				      { "kind": "component", "props": { "dataProviderID": "contactname" } }
				    ] }
				] }
				""");
		List<OutcomeAssertion> parsed = OutcomeAssertions.parse(expect);
		assertEquals(1, parsed.size());
		OutcomeAssertion form = parsed.get(0);
		assertEquals(PersistKind.FORM, form.kind());
		assertEquals("customerDetail", form.name());
		assertTrue(form.exists());
		assertTrue(form.props().containsKey("dataSource"));
		assertTrue(form.props().containsKey("useCssPosition"));
		assertEquals(2, form.children().size());
		assertEquals(PersistKind.COMPONENT, form.children().get(0).kind());
	}

	@Test
	void parsesMatcherObjects() throws Exception {
		JsonNode expect = json("""
				{ "persists": [
				  { "kind": "valuelist", "name": "colors",
				    "props": { "customValues": { "containsAll": ["one","two","three"] } } }
				] }
				""");
		List<OutcomeAssertion> parsed = OutcomeAssertions.parse(expect);
		ArgMatcher m = parsed.get(0).props().get("customValues");
		assertNotNull(m);
		assertEquals(ArgMatcher.Kind.CONTAINS_ALL, m.getKind());
	}

	@Test
	void parseHandlesMissingOrEmptyExpect() {
		assertTrue(OutcomeAssertions.parse(null).isEmpty());
	}

	@Test
	void roundTripsThroughJson() throws Exception {
		String original = """
				{ "persists": [
				  { "kind": "form", "name": "f",
				    "props": { "dataSource": "db:/s/t", "useCssPosition": true },
				    "children": [ { "kind": "component", "props": { "dataProviderID": "col" } } ] },
				  { "kind": "scriptmethod", "scope": "globals", "name": "doThing" },
				  { "kind": "form", "name": "gone", "exists": false }
				] }
				""";
		List<OutcomeAssertion> parsed = OutcomeAssertions.parse(json(original));
		JsonNode reserialised = OutcomeAssertions.toJson(parsed);
		List<OutcomeAssertion> reparsed = OutcomeAssertions.parse(reserialised);
		assertEquals(parsed.size(), reparsed.size());
		assertEquals(parsed.get(0).name(), reparsed.get(0).name());
		assertEquals(parsed.get(0).children().size(), reparsed.get(0).children().size());
		assertEquals("globals", reparsed.get(1).scope());
		assertFalse(reparsed.get(2).exists());
	}
}

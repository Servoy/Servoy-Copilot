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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link StructureMatcher} - subset, order-independent {@code .frm}
 * comparison with SQL-{@code LIKE} string leaves (SVY-21366). Pure JSON, no
 * workbench.
 */
class StructureMatcherTest { // SVY-21366

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode j(String s) throws Exception {
		return MAPPER.readTree(s);
	}

	private static StructureMatcher.Result run(String expected, String actual) throws Exception {
		return StructureMatcher.matchItems(j(expected), j(actual));
	}

	@Test
	@DisplayName("matches identical items trees")
	void identical() throws Exception {
		String tree = "[ { \"typeName\": \"bootstrapcomponents-label\", \"json\": { \"text\": \"Order 10643\" } } ]";
		assertTrue(run(tree, tree).match());
	}

	@Test
	@DisplayName("items are matched order-independently (serializer sorts children by uuid)")
	void itemsUnordered() throws Exception {
		String expected = """
				[ { "typeName": "a", "json": { "text": "one" } },
				  { "typeName": "b", "json": { "text": "two" } } ]
				""";
		String actual = """
				[ { "typeName": "b", "json": { "text": "two" } },
				  { "typeName": "a", "json": { "text": "one" } } ]
				""";
		assertTrue(run(expected, actual).match(), "reordered items should still match");
	}

	@Test
	@DisplayName("a tabpanel tabs array is positional, not reordered")
	void tabsPositional() throws Exception {
		String expected = """
				[ { "typeName": "tabpanel", "json": { "tabs": [ { "text": "Overview" }, { "text": "Items" } ] } } ]
				""";
		String actualSwapped = """
				[ { "typeName": "tabpanel", "json": { "tabs": [ { "text": "Items" }, { "text": "Overview" } ] } } ]
				""";
		assertFalse(run(expected, actualSwapped).match(), "tab order must matter");
	}

	@Test
	@DisplayName("LIKE leaf: % matches a run of characters in tab text")
	void likeLeafPercent() throws Exception {
		String expected = """
				[ { "typeName": "tabpanel", "json": { "tabs": [ { "text": "%Overview%" } ] } } ]
				""";
		String actual = """
				[ { "typeName": "tabpanel", "json": { "tabs": [ { "text": "<span>Overview</span>" } ] } } ]
				""";
		assertTrue(run(expected, actual).match());
	}

	@Test
	@DisplayName("LIKE leaf: anchored pattern, _ is one char, literal has no wildcard")
	void likeLeafAnchoringAndUnderscore() throws Exception {
		// "Items%" is anchored at the start, so the span suffix is swallowed by %.
		assertTrue(run("[ { \"json\": { \"text\": \"Items%\" } } ]",
				"[ { \"json\": { \"text\": \"Items <span>3</span>\" } } ]").match());
		// "_verview" matches exactly one leading char.
		assertTrue(run("[ { \"json\": { \"text\": \"_verview\" } } ]",
				"[ { \"json\": { \"text\": \"Overview\" } } ]").match());
		// A literal (no wildcard) must equal the whole value, not merely be contained.
		assertFalse(run("[ { \"json\": { \"text\": \"Overview\" } } ]",
				"[ { \"json\": { \"text\": \"<span>Overview</span>\" } } ]").match());
		// Case-insensitive.
		assertTrue(run("[ { \"json\": { \"text\": \"overview\" } } ]",
				"[ { \"json\": { \"text\": \"Overview\" } } ]").match());
	}

	@Test
	@DisplayName("subset: a key the expected omits is not checked, extra actual keys ignored")
	void subsetIgnoresUnlistedKeys() throws Exception {
		String expected = "[ { \"typeName\": \"label\" } ]";
		String actual = "[ { \"typeName\": \"label\", \"name\": \"lbl_x\", \"json\": { \"text\": \"hi\" } } ]";
		assertTrue(run(expected, actual).match(), "unlisted actual keys must be ignored");
	}

	@Test
	@DisplayName("boolean disabled on the fifth tab is matched")
	void tabDisabled() throws Exception {
		String expected = """
				[ { "json": { "tabs": [ { "text": "Overview" }, { "text": "History", "disabled": true } ] } } ]
				""";
		String actual = """
				[ { "json": { "tabs": [ { "text": "Overview" }, { "text": "History", "disabled": true } ] } } ]
				""";
		assertTrue(run(expected, actual).match());

		String actualNotDisabled = """
				[ { "json": { "tabs": [ { "text": "Overview" }, { "text": "History", "disabled": false } ] } } ]
				""";
		StructureMatcher.Result r = run(expected, actualNotDisabled);
		assertFalse(r.match());
		assertNotNull(r.describe());
	}

	@Test
	@DisplayName("array size mismatch fails with a path")
	void sizeMismatch() throws Exception {
		StructureMatcher.Result r = run("[ { \"typeName\": \"a\" }, { \"typeName\": \"b\" } ]",
				"[ { \"typeName\": \"a\" } ]");
		assertFalse(r.match());
		assertNotNull(r.path());
	}

	@Test
	@DisplayName("mismatch reports a usable path and both values")
	void mismatchDescribes() throws Exception {
		StructureMatcher.Result r = run("[ { \"json\": { \"text\": \"Overview\" } } ]",
				"[ { \"json\": { \"text\": \"Summary\" } } ]");
		assertFalse(r.match());
		assertTrue(r.describe().contains("Overview"));
		assertTrue(r.describe().contains("Summary"));
	}
}

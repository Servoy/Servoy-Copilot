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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * Unit tests for {@link AiExpectationGeneration#sanitize(List)}: the
 * deterministic "make the raw capture a valid baseline" pass that the skip-AI
 * ("No") path and the AI capture-kept fallback both run, so a baseline never
 * ships the unusable {@value OutcomeInference#TYPE_NAME_PLACEHOLDER} placeholder
 * (SVY-21366). Pure logic, no workbench / no LLM.
 */
public class AiExpectationSanitizeTest {

	private static Map<String, ArgMatcher> props(Map<String, ArgMatcher> m) {
		return m;
	}

	@Test
	void dropsPlaceholderTypeNameButKeepsOtherProps() {
		Map<String, ArgMatcher> childProps = new LinkedHashMap<>();
		childProps.put("typeName", ArgMatcher.tolerant(OutcomeInference.TYPE_NAME_PLACEHOLDER));
		childProps.put("dataProviderID", ArgMatcher.tolerant("companyname"));
		OutcomeAssertion child = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true, childProps, List.of());
		OutcomeAssertion form = new OutcomeAssertion(PersistKind.FORM, "customerDetail", null, true,
				props(Map.of()), List.of(child));

		List<OutcomeAssertion> out = AiExpectationGeneration.sanitize(List.of(form));

		OutcomeAssertion sanitizedChild = out.get(0).children().get(0);
		assertNull(sanitizedChild.props().get("typeName"),
				"the <replace-with-component-type> placeholder typeName must be dropped");
		assertEquals("companyname", String.valueOf(sanitizedChild.props().get("dataProviderID").getExpected()),
				"the component must still be identified by its dataProviderID");
	}

	@Test
	void keepsRealTypeNameUntouched() {
		Map<String, ArgMatcher> childProps = new LinkedHashMap<>();
		childProps.put("typeName", ArgMatcher.tolerant("bootstrapcomponents-textbox"));
		OutcomeAssertion child = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true, childProps, List.of());

		List<OutcomeAssertion> out = AiExpectationGeneration.sanitize(List.of(child));

		assertEquals("bootstrapcomponents-textbox",
				String.valueOf(out.get(0).props().get("typeName").getExpected()),
				"a real typeName must be preserved");
	}

	@Test
	void leavesAssertionsWithoutPlaceholdersUnchangedInstance() {
		OutcomeAssertion form = new OutcomeAssertion(PersistKind.FORM, "f", null, true,
				props(Map.of("dataSource", ArgMatcher.tolerant("db:/s/t"))), List.of());

		List<OutcomeAssertion> out = AiExpectationGeneration.sanitize(List.of(form));

		// Nothing to strip -> the same instance is reused (no needless copying).
		assertTrue(out.get(0) == form, "an assertion with no placeholder should be passed through unchanged");
	}

	@Test
	void handlesNullAndEmpty() {
		assertNull(AiExpectationGeneration.sanitize(null));
		assertTrue(AiExpectationGeneration.sanitize(List.of()).isEmpty());
	}

	@Test
	void stripsPlaceholderNestedTwoLevelsDeep() {
		Map<String, ArgMatcher> leafProps = new LinkedHashMap<>();
		leafProps.put("typeName", ArgMatcher.tolerant(OutcomeInference.TYPE_NAME_PLACEHOLDER));
		OutcomeAssertion leaf = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true, leafProps, List.of());
		OutcomeAssertion container = new OutcomeAssertion(PersistKind.LAYOUTCONTAINER, null, null, true,
				props(Map.of()), List.of(leaf));
		OutcomeAssertion form = new OutcomeAssertion(PersistKind.FORM, "f", null, true, props(Map.of()),
				List.of(container));

		List<OutcomeAssertion> out = AiExpectationGeneration.sanitize(List.of(form));

		OutcomeAssertion sanitizedLeaf = out.get(0).children().get(0).children().get(0);
		assertFalse(sanitizedLeaf.props().containsKey("typeName"),
				"placeholder typeName must be stripped even nested two levels deep");
	}
}

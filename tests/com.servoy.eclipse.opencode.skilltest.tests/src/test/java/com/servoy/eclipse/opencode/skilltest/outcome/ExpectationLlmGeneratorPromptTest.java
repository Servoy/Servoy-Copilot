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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * Unit tests for {@link ExpectationLlmGenerator#buildPrompt} - the generation
 * prompt that drives the LLM's literal-&gt;robust rewrite (SVY-21366).
 * <p>
 * The loosening rules themselves are NOT asserted here: they are authored in the
 * {@code skilltest-expectations} skill ({@code skills4servoy}) and the prompt
 * only tells the session's agent to use that skill. What the prompt must carry is
 * the per-call envelope the skill cannot: naming the skill, the task, the
 * captured {@code expect.persists} JSON to rewrite, the mockup, and the
 * answer-format contract. Pure string assertions - no server, no LLM.
 */
public class ExpectationLlmGeneratorPromptTest {

	/** A generator bound to a dummy port; buildPrompt makes no network call. */
	private static final ExpectationLlmGenerator GEN = new ExpectationLlmGenerator(1, null);

	private static List<OutcomeAssertion> captured() {
		OutcomeAssertion tabpanel = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-tabpanel")), List.of());
		OutcomeAssertion form = new OutcomeAssertion(PersistKind.FORM, "order_tabs", null, true,
				Map.of("useCssPosition", ArgMatcher.tolerant(false)), List.of(tabpanel));
		return List.of(form);
	}

	@Test
	void promptNamesTheSkillSoTheAgentInvokesIt() {
		String prompt = GEN.buildPrompt(captured(), null);
		assertTrue(prompt.contains("`skilltest-expectations` skill"),
				"prompt must tell the agent to use the skilltest-expectations skill");
	}

	@Test
	void promptStatesTheTaskAndForbidsEchoingTheCapture() {
		String prompt = GEN.buildPrompt(captured(), null);
		assertTrue(prompt.contains("ROBUST"), "prompt must ask for a robust rewrite");
		assertTrue(prompt.contains("SQL-LIKE"), "prompt must name the SQL-LIKE loosening target");
		assertTrue(prompt.contains("returning the input unchanged is wrong"),
				"prompt must forbid echoing the capture unchanged");
	}

	@Test
	void promptEmbedsTheCapturedJson() {
		String prompt = GEN.buildPrompt(captured(), null);
		assertTrue(prompt.contains("order_tabs"), "the captured persists JSON must be embedded");
		assertTrue(prompt.contains("```json"), "the captured JSON must be fenced");
	}

	@Test
	void promptStatesTheAnswerFormat() {
		String prompt = GEN.buildPrompt(captured(), null);
		assertTrue(prompt.contains("EXACTLY ONE fenced"), "prompt must pin the one-fenced-block answer format");
		assertTrue(prompt.contains("\"persists\""), "prompt must name the expected { persists: [...] } shape");
	}

	@Test
	void promptIncludesMockupWhenPresentAndNoteWhenAbsent() {
		String withMockup = GEN.buildPrompt(captured(), "<div class=\"ot-badge\">Shipped</div>");
		assertTrue(withMockup.contains("```html"), "an html mockup must be fenced into the prompt");
		assertTrue(withMockup.contains("ot-badge"), "the mockup content must be present");

		String noMockup = GEN.buildPrompt(captured(), "   ");
		assertTrue(noMockup.contains("No mockup was provided"),
				"a blank mockup must fall back to the no-mockup note");
	}

	// --- restoreTypeNames: the deterministic guard that a typeName is never loosened (SVY-21366) ---

	private static String typeName(OutcomeAssertion a) {
		ArgMatcher m = a.props().get("typeName");
		return m != null ? String.valueOf(m.getExpected()) : null;
	}

	@Test
	void restoreTypeNameRepairsAWildcardedChildType() {
		// Captured child carries the literal type; the model loosened it to a wildcard.
		OutcomeAssertion capChild = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-button")), List.of());
		OutcomeAssertion capForm = new OutcomeAssertion(PersistKind.FORM, "f", null, true, Map.of(),
				List.of(capChild));

		OutcomeAssertion looseChild = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-%")), List.of());
		OutcomeAssertion looseForm = new OutcomeAssertion(PersistKind.FORM, "f", null, true, Map.of(),
				List.of(looseChild));

		List<OutcomeAssertion> repaired = ExpectationLlmGenerator.restoreTypeNames(List.of(capForm),
				List.of(looseForm));
		assertEquals("bootstrapcomponents-button", typeName(repaired.get(0).children().get(0)),
				"a wildcarded child typeName must be restored to the captured literal");
	}

	@Test
	void restoreTypeNameLeavesLegitimateLooseningAlone() {
		// styleClass was loosened on purpose; only typeName should be repaired.
		OutcomeAssertion cap = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-label"), "styleClass",
						ArgMatcher.tolerant("ot-title")),
				List.of());
		OutcomeAssertion loose = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-%"), "styleClass",
						ArgMatcher.tolerant("%title%")),
				List.of());

		OutcomeAssertion fixed = ExpectationLlmGenerator.restoreTypeNames(List.of(cap), List.of(loose)).get(0);
		assertEquals("bootstrapcomponents-label", typeName(fixed), "typeName restored");
		assertEquals("%title%", String.valueOf(fixed.props().get("styleClass").getExpected()),
				"a legitimately loosened styleClass must be kept");
	}

	@Test
	void restoreTypeNameKeepsAnAlreadyLiteralType() {
		OutcomeAssertion cap = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-textbox")), List.of());
		OutcomeAssertion loose = new OutcomeAssertion(PersistKind.COMPONENT, null, null, true,
				Map.of("typeName", ArgMatcher.tolerant("bootstrapcomponents-textbox")), List.of());

		OutcomeAssertion fixed = ExpectationLlmGenerator.restoreTypeNames(List.of(cap), List.of(loose)).get(0);
		assertEquals("bootstrapcomponents-textbox", typeName(fixed), "an already-literal typeName is untouched");
	}

	@Test
	void restoreTypeNameToleratesNullOrEmpty() {
		assertEquals(null, ExpectationLlmGenerator.restoreTypeNames(List.of(), null));
		assertTrue(ExpectationLlmGenerator.restoreTypeNames(List.of(), List.of()).isEmpty());
	}
}

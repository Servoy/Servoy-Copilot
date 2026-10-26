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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AcceptanceJudge} - the judge-model resolution, prompt
 * building and verdict parsing (SVY-21366). Pure logic: no server, no LLM. The
 * live judging path (HTTP to opencode) is only exercisable in the workbench.
 */
class AcceptanceJudgeTest {

	private static final AcceptanceJudge JUDGE = new AcceptanceJudge(1, null);

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

	private static final List<String> CRITERIA = List.of(
			"A form named order_tabs exists and is responsive",
			"A tab panel with 5 tabs in order: Overview, Items, Shipping, Notes, History",
			"The History tab is disabled");

	// --- judge model resolution: separate session, model MAY equal the builder ---

	@Test
	@DisplayName("a configured judge model is used as-is")
	void configuredModelUsed() {
		assertEquals("anthropic/claude", AcceptanceJudge.resolveJudgeModel("anthropic/claude", "kiro/auto"));
	}

	@Test
	@DisplayName("no configured model reuses the builder's model (same model, different session)")
	void reusesBuilderModelWhenUnconfigured() {
		assertEquals("kiro/auto", AcceptanceJudge.resolveJudgeModel(null, "kiro/auto"));
	}

	@Test
	@DisplayName("a configured model equal to the builder is allowed (not forced to differ)")
	void configuredEqualToBuilderIsAllowed() {
		assertEquals("kiro/auto", AcceptanceJudge.resolveJudgeModel("kiro/auto", "kiro/auto"));
	}

	@Test
	@DisplayName("the default is used only when neither a configured nor a builder model is known")
	void defaultOnlyWhenNothingKnown() {
		assertEquals(AcceptanceJudge.DEFAULT_JUDGE_MODEL, AcceptanceJudge.resolveJudgeModel(null, null));
		assertEquals(AcceptanceJudge.DEFAULT_JUDGE_MODEL, AcceptanceJudge.resolveJudgeModel("  ", "  "));
	}

	@Test
	@DisplayName("the judge runs as the dedicated 'Judge' agent (separate agent, not the builder's)")
	void judgeRunsAsDedicatedJudgeAgent() {
		// The judge's independence is a separate AGENT in a separate session. The
		// agent must be the dedicated 'Judge' primary agent (skills pack), not the
		// hidden read-only 'Reviewer' subagent (which cannot be a session agent and
		// would block servoy-form_getFormLayout), and never the builder's agent.
		assertEquals("Judge", AcceptanceJudge.JUDGE_AGENT);
	}

	// --- prompt building ---

	@Test
	@DisplayName("prompt names the form, lists the criteria, and points the judge at the sources to read")
	void promptCarriesFormCriteriaAndSources() {
		String p = JUDGE.buildPrompt("order_tabs", "specs/03_order_tabs.html", CRITERIA);
		assertTrue(p.contains("order_tabs"), "names the form");
		assertTrue(p.contains("forms/order_tabs.frm"), "points at the .frm");
		assertTrue(p.contains("forms/order_tabs.js"), "points at the .js (onShow)");
		assertTrue(p.contains(".less"), "points at the less/css");
		assertTrue(p.contains("specs/03_order_tabs.html"), "points at the mockup");
		assertTrue(p.contains("The History tab is disabled"), "lists every criterion");
		assertTrue(p.contains("```json"), "asks for one fenced json verdict");
		assertTrue(p.contains("\"overall\""), "specifies the verdict shape");
	}

	@Test
	@DisplayName("prompt without a mockup path omits the mockup line but still asks for the verdict")
	void promptWithoutMockup() {
		String p = JUDGE.buildPrompt("order_tabs", null, CRITERIA);
		assertFalse(p.contains("read the mockup it must reproduce"), "no mockup line when none given");
		assertTrue(p.contains("```json"));
	}

	@Test
	@DisplayName("prompt offers the screenshot tool for visual fidelity")
	void promptOffersScreenshotTool() {
		String p = JUDGE.buildPrompt("order_tabs", "specs/03_order_tabs.html", CRITERIA);
		assertTrue(p.contains("servoy-form_getFormLayout"), "names the render/screenshot tool");
		assertTrue(p.contains("screenshot=true"), "tells the judge to request the screenshot");
	}

	@Test
	@DisplayName("with no form name the judge is told to discover the form itself (no forms/null.frm)")
	void promptWithoutFormName() {
		String p = JUDGE.buildPrompt(null, null, CRITERIA);
		assertFalse(p.contains("forms/null"), "must not build a forms/null path");
		assertTrue(p.toLowerCase().contains("discover it") || p.toLowerCase().contains("locate the created form"),
				"tells the judge to find the agent-named form");
		assertTrue(p.contains("```json"), "still asks for the verdict");
	}

	// --- verdict parsing ---

	@Test
	@DisplayName("parses a fenced json verdict with per-criterion results")
	void parsesFencedVerdict() {
		String answer = """
				Here is my assessment.
				```json
				{ "overall": "pass", "criteria": [
				  { "criterion": "form exists", "pass": true, "reason": "forms/order_tabs.frm present" },
				  { "criterion": "5 tabs", "pass": true, "reason": "tabs array has 5 entries" }
				] }
				```
				""";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer);
		assertNull(v.error());
		assertTrue(v.pass());
		assertEquals(2, v.results().size());
		assertEquals("form exists", v.results().get(0).criterion());
		assertTrue(v.results().get(0).pass());
	}

	@Test
	@DisplayName("overall=pass is overridden to fail when any criterion failed")
	void overallPassButACriterionFailed() {
		String answer = """
				```json
				{ "overall": "pass", "criteria": [
				  { "criterion": "a", "pass": true, "reason": "ok" },
				  { "criterion": "b", "pass": false, "reason": "History tab not disabled" }
				] }
				```
				""";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer);
		assertFalse(v.pass(), "a failing criterion must make the overall fail");
		assertEquals(2, v.results().size());
	}

	@Test
	@DisplayName("overall is derived from criteria when the judge omits it")
	void overallDerivedWhenAbsent() {
		String answer = """
				```json
				{ "criteria": [
				  { "criterion": "a", "pass": true, "reason": "ok" },
				  { "criterion": "b", "pass": true, "reason": "ok" }
				] }
				```
				""";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer);
		assertTrue(v.pass(), "all criteria pass -> overall pass");
	}

	@Test
	@DisplayName("a boolean overall is honoured")
	void booleanOverall() {
		String answer = "{ \"overall\": false, \"criteria\": [ { \"criterion\": \"a\", \"pass\": false, \"reason\": \"x\" } ] }";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer);
		assertFalse(v.pass());
	}

	@Test
	@DisplayName("an answer with no json yields an error verdict (not a pass)")
	void noJsonIsError() {
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict("I could not complete the review.");
		assertNotNull(v.error());
		assertFalse(v.pass());
	}

	// --- reading the assistant's final text from the export (v2 `content` blocks) ---

	@Test
	@DisplayName("lastAssistantText reads v2 export `content` text blocks (not empty `parts`)")
	void lastAssistantTextReadsV2Content() throws Exception {
		// Mirrors the opencode v2 export shape: messages[].type + content[] blocks,
		// parts empty/absent. The earlier bug read `parts` and found nothing.
		String export = """
				{ "messages": [
				  { "type": "user", "content": [ { "type": "text", "text": "grade this" } ] },
				  { "type": "assistant", "parts": [],
				    "content": [ { "type": "tool", "name": "read" },
				                 { "type": "text", "text": "```json\\n{\\"overall\\":\\"pass\\",\\"criteria\\":[]}\\n```" } ] }
				] }
				""";
		String text = AcceptanceJudge.lastAssistantText(MAPPER.readTree(export));
		assertNotNull(text, "must read the assistant text from content[]");
		assertTrue(text.contains("overall"), "returns the assistant's final text answer");
	}

	@Test
	@DisplayName("lastAssistantText still reads the v1 `parts` shape")
	void lastAssistantTextReadsV1Parts() throws Exception {
		String export = """
				{ "messages": [
				  { "role": "assistant", "parts": [ { "type": "text", "text": "hello verdict" } ] }
				] }
				""";
		assertEquals("hello verdict", AcceptanceJudge.lastAssistantText(MAPPER.readTree(export)));
	}

	@Test
	@DisplayName("verdict summary is a readable one-liner")
	void summaryReadable() {
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict("""
				```json
				{ "overall": "fail", "criteria": [
				  { "criterion": "a", "pass": true, "reason": "ok" },
				  { "criterion": "b", "pass": false, "reason": "no" }
				] }
				```
				""");
		assertTrue(v.summary().contains("FAIL"));
		assertTrue(v.summary().contains("1/2"));
	}
}

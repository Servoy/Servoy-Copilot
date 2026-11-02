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
 * <p>
 * The prompt tests pin the artifact-finding contract: the prompt names the
 * targeted artifact and tells the judge to find+read it, and when no name is
 * given it tells the judge to enumerate the solution rather than assume the
 * artifact is missing.
 * </p>
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
		// hidden read-only 'Reviewer' subagent (which cannot be a session agent),
		// and never the builder's agent.
		assertEquals("Judge", AcceptanceJudge.JUDGE_AGENT);
	}

	// --- prompt building ---

	@Test
	@DisplayName("a named artifact with a source hint tells the judge the exact file(s) to read")
	void promptNamesArtifactAndGivesSourceHint() {
		String hint = "`forms/order_tabs.frm` (plus its `forms/order_tabs.js` and `forms/order_tabs.less` when present)"; // 4-arg buildPrompt
		String p = JUDGE.buildPrompt("order_tabs", hint, "specs/03_order_tabs.html", CRITERIA);
		assertTrue(p.contains("`order_tabs`"), "names the artifact");
		assertTrue(p.contains("read its source:"), "tells the judge to read its source");
		assertTrue(p.contains("forms/order_tabs.frm"), "gives the concrete file path to read");
		assertTrue(p.contains("specs/03_order_tabs.html"), "points at the mockup");
		assertTrue(p.contains("The History tab is disabled"), "lists every criterion");
	}

	@Test
	@DisplayName("a value-list source hint points at valuelists/<name>.val, not a form path")
	void valueListSourceHintIsNotFormPath() {
		String hint = "`valuelists/orderStatusVl.val`";
		String p = JUDGE.buildPrompt("orderStatusVl", hint, null, CRITERIA);
		assertTrue(p.contains("valuelists/orderStatusVl.val"), "gives the value-list file to read");
		assertFalse(p.contains("forms/orderStatusVl"), "must not assume a forms/<name>.frm path");
	}

	@Test
	@DisplayName("a named artifact with no source hint still tells the judge to find+read its source")
	void namedWithoutHintStillFindsIt() {
		String p = JUDGE.buildPrompt("someThing", null, null, CRITERIA);
		assertTrue(p.contains("`someThing`"), "names the artifact");
		assertTrue(p.toLowerCase().contains("find and read its source"), "tells the judge to find it");
	}

	@Test
	@DisplayName("prompt without a mockup path omits the mockup line but still lists the criteria")
	void promptWithoutMockup() {
		String p = JUDGE.buildPrompt("order_tabs", "`forms/order_tabs.frm`", null, CRITERIA);
		assertFalse(p.contains("Mockup to reproduce"), "no mockup line when none given");
		assertTrue(p.contains("The History tab is disabled"), "still lists the criteria");
	}

	@Test
	@DisplayName("with no name the judge is told to DISCOVER via getTarget+getProjectLayout, not assume missing or named null")
	void promptWithoutSubjectNameTellsJudgeToDiscover() {
		String p = JUDGE.buildPrompt(null, null, null, CRITERIA);
		assertFalse(p.contains("`null`"), "must never tell the judge the artifact is named null");
		assertFalse(p.contains("forms/null"), "must not build a forms/null path");
		assertTrue(p.contains("servoy-model_getTarget"), "points the judge at the active-solution tool");
		assertTrue(p.contains("servoy-workspace_getProjectLayout") || p.contains("servoy-workspace_findFiles"),
				"points the judge at a tool that lists the solution's contents");
		assertTrue(p.toLowerCase().contains("do not assume"),
				"warns the judge not to fail just because no name was given");
	}

	@Test
	@DisplayName("prompt binds the verdict to one entry per supplied criterion")
	void promptBindsOneEntryPerCriterion() {
		String p = JUDGE.buildPrompt("order_tabs", "`forms/order_tabs.frm`", null, CRITERIA);
		assertTrue(p.contains("one entry for EACH of the " + CRITERIA.size()),
				"requires exactly one verdict entry per criterion");
	}

	@Test
	@DisplayName("prompt spells out the JSON verdict shape the parser reads (pass/reason keys)")
	void promptSpellsOutVerdictShape() {
		// Without this block a real judge invented its own keys (met/evidence) - SVY-21366.
		String p = JUDGE.buildPrompt(null, null, null, CRITERIA);
		assertTrue(p.contains("```json"), "asks for one fenced json block");
		assertTrue(p.contains("\"overall\""), "names the overall key");
		assertTrue(p.contains("\"criterion\""), "names the criterion key");
		assertTrue(p.contains("\"pass\""), "names the pass key the parser reads");
		assertTrue(p.contains("\"reason\""), "names the reason key the parser reads");
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
	@DisplayName("a real judge answer using met/evidence (not pass/reason) parses as PASS with reasons")
	void parsesMetEvidenceShapeFromRealRun() {
		// Shape of a real local run (SVY-21366): the judge graded all three criteria as met,
		// but used `met`/`evidence`. The old pass-only read turned this into FAIL (0/3).
		String answer = """
				Both components are in the form, so all three criteria pass.
				```json
				{ "solution": "aiTestSolution2", "form": "buttonLabelForm", "criteria": [
				  { "id": 1, "criterion": "A new form was created", "met": true, "evidence": "forms/buttonLabelForm.frm exists" },
				  { "id": 2, "criterion": "The form contains a button", "met": true, "evidence": "button_clickme is a bootstrapcomponents-button" },
				  { "id": 3, "criterion": "The form contains a label", "met": true, "evidence": "label_hello is a bootstrapcomponents-label" }
				], "overall": "PASS" }
				```
				""";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer, List.of("a", "b", "c"));
		assertNull(v.error());
		assertTrue(v.pass(), "all three criteria met -> overall pass");
		assertEquals(3, v.results().size());
		assertTrue(v.results().stream().allMatch(AcceptanceJudge.CriterionResult::pass), "met:true reads as pass");
		assertEquals("forms/buttonLabelForm.frm exists", v.results().get(0).reason(), "evidence reads as the reason");
	}

	@Test
	@DisplayName("met:false and a 'fail' status string both read as fail")
	void metFalseAndStatusStringFail() {
		String answer = """
				```json
				{ "criteria": [
				  { "criterion": "a", "met": false, "evidence": "missing" },
				  { "criterion": "b", "status": "fail", "explanation": "no label" }
				] }
				```
				""";
		AcceptanceJudge.Verdict v = JUDGE.parseVerdict(answer, List.of("a", "b"));
		assertFalse(v.pass());
		assertFalse(v.results().get(0).pass());
		assertFalse(v.results().get(1).pass());
		assertEquals("no label", v.results().get(1).reason());
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

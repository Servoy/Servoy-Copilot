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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VerificationPlanAgent} - the prompt building and plan
 * parsing (SVY-21366). Pure logic: no server, no LLM. The live authoring path
 * (HTTP to opencode) is only exercisable in the workbench.
 */
class VerificationPlanAgentTest {

	private static final VerificationPlanAgent AGENT = new VerificationPlanAgent(1, null);

	// --- prompt ---

	@Test
	@DisplayName("prompt requires the skilltest-authoring skill and offers all three check kinds")
	void promptNamesSkillAndChecks() {
		String p = AGENT.buildPrompt("specs/03_order_tabs.html");
		assertTrue(p.contains("skilltest-authoring"), "names the authoring skill");
		assertTrue(p.contains("expectPersists"), "offers persists");
		assertTrue(p.contains("acceptanceCriteria"), "offers acceptance criteria");
		assertTrue(p.contains("jsunit"), "offers jsunit");
		assertTrue(p.contains("```json"), "asks for a fenced json answer");
	}

	@Test
	@DisplayName("prompt mentions the mockup when one is given")
	void promptMentionsMockup() {
		assertTrue(AGENT.buildPrompt("specs/03_order_tabs.html").contains("specs/03_order_tabs.html"),
				"names the mockup path");
		assertFalse(AGENT.buildPrompt(null).contains("the UI mockup the run was asked to reproduce, at"),
				"no mockup line when none is given");
	}

	// --- plan parsing ---

	@Test
	@DisplayName("parses a full plan: persists + acceptanceCriteria + jsunit")
	void parsesFullPlan() {
		String answer = """
				Here is my plan.
				```json
				{
				  "rationale": "a mockup-built form plus a valuelist",
				  "expectPersists": [ { "kind": "valuelist", "name": "vl", "props": { } } ],
				  "acceptanceCriteria": { "criteria": [ "The form reproduces the mockup" ], "judgeModel": null, "required": true },
				  "jsunit": { "scope": "skilltest_verify", "scriptName": "skilltest_verify", "scriptSource": "function test_x() { }", "method": null, "required": true }
				}
				```
				""";
		VerificationPlan plan = AGENT.parsePlan(answer);
		assertNotNull(plan);
		assertTrue(plan.hasPersists(), "has persists");
		assertTrue(plan.hasAcceptance(), "has acceptance criteria");
		assertTrue(plan.hasJsUnit(), "has jsunit");
		assertEquals("a mockup-built form plus a valuelist", plan.rationale());
	}

	@Test
	@DisplayName("acceptance-criteria-only plan is judge-authoritative (no persists, no jsunit)")
	void parsesAcceptanceOnly() {
		String answer = """
				```json
				{ "rationale": "LLM-built UI; structure too volatile to assert",
				  "acceptanceCriteria": { "criteria": [ "A tab panel with five tabs", "History disabled" ] } }
				```
				""";
		VerificationPlan plan = AGENT.parsePlan(answer);
		assertNotNull(plan);
		assertFalse(plan.hasPersists(), "no persists");
		assertTrue(plan.hasAcceptance(), "has acceptance criteria");
		assertFalse(plan.hasJsUnit(), "no jsunit");
		assertEquals(2, plan.acceptance().criteria().size());
	}

	@Test
	@DisplayName("a null judgeModel defaults to a non-builder judge model")
	void defaultsJudgeModel() {
		VerificationPlan plan = AGENT.parsePlan("""
				```json
				{ "acceptanceCriteria": { "criteria": [ "x" ], "judgeModel": null } }
				```
				""");
		assertNotNull(plan);
		assertNotNull(plan.acceptance().judgeModel(), "a default judge model is filled in");
		assertFalse(plan.acceptance().judgeModel().isBlank());
	}

	@Test
	@DisplayName("jsunit-only plan parses and defaults scope/scriptName from each other")
	void parsesJsUnitOnlyWithDefaults() {
		VerificationPlan plan = AGENT.parsePlan("""
				```json
				{ "jsunit": { "scriptSource": "function test_calc() { jsunit.assertEquals(2, 1+1); }" } }
				```
				""");
		assertNotNull(plan);
		assertTrue(plan.hasJsUnit());
		assertFalse(plan.hasPersists());
		VerificationPlan.JsUnitPlan js = plan.jsunit();
		assertNotNull(js.scope(), "scope defaulted");
		assertFalse(js.scope().isBlank());
		assertEquals(js.scope(), js.scriptName(), "scriptName defaults to the scope");
		assertTrue(js.required(), "required defaults true");
	}

	@Test
	@DisplayName("a jsunit block with no scriptSource is dropped")
	void jsUnitWithoutScriptIsDropped() {
		VerificationPlan plan = AGENT.parsePlan("""
				```json
				{ "expectPersists": [ { "kind": "form", "name": "f" } ],
				  "jsunit": { "scope": "s", "scriptSource": "" } }
				```
				""");
		assertNotNull(plan);
		assertTrue(plan.hasPersists());
		assertFalse(plan.hasJsUnit(), "empty scriptSource => no jsunit check");
	}

	@Test
	@DisplayName("a plan carrying no check at all is not a plan (null)")
	void emptyPlanIsNull() {
		assertNull(AGENT.parsePlan("```json\n{ \"rationale\": \"nothing fits\" }\n```"));
		assertNull(AGENT.parsePlan("```json\n{ \"expectPersists\": [] }\n```"));
	}

	@Test
	@DisplayName("an answer with no JSON yields no plan (null, not an empty plan)")
	void noJsonIsNull() {
		assertNull(AGENT.parsePlan("I could not analyze the session."));
	}

	@Test
	@DisplayName("a bare (unfenced) JSON object is still parsed")
	void parsesBareJson() {
		VerificationPlan plan = AGENT
				.parsePlan("{ \"acceptanceCriteria\": { \"criteria\": [ \"a\" ] } }");
		assertNotNull(plan);
		assertTrue(plan.hasAcceptance());
	}

	// --- reading the authoring answer from the export (v2 content, v1 parts) ---

	@Test
	@DisplayName("lastAssistantText reads v2 export content text blocks")
	void lastAssistantTextV2() throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		String export = """
				{ "messages": [
				  { "type": "user", "content": [ { "type": "text", "text": "author this" } ] },
				  { "type": "assistant", "parts": [],
				    "content": [ { "type": "tool", "name": "read" },
				                 { "type": "text", "text": "```json\\n{}\\n```" } ] }
				] }
				""";
		String text = VerificationPlanAgent.lastAssistantText(mapper.readTree(export));
		assertNotNull(text);
		assertTrue(text.contains("json"), "returns the assistant's final text");
	}

	@Test
	@DisplayName("lastAssistantText still reads the v1 parts shape")
	void lastAssistantTextV1() throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		String export = """
				{ "messages": [
				  { "role": "assistant", "parts": [ { "type": "text", "text": "hi plan" } ] }
				] }
				""";
		assertEquals("hi plan", VerificationPlanAgent.lastAssistantText(mapper.readTree(export)));
	}
}

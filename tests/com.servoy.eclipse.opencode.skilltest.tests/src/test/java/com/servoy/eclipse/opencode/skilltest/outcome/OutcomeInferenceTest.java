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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link OutcomeInference}: inferring expected outcomes from an
 * opencode export's {@code task_result} "Changed files" list + prompt, without
 * a workspace (SVY-21366).
 */
public class OutcomeInferenceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void infersFormFromTaskResultAndPromptHints() throws Exception {
		// Mirrors the real Orchestrator export: no direct write-tool calls; the form
		// creation is summarised in the task output's "Changed files" list, and the
		// datasource/layout/dataproviders come from the user prompt.
		String export = """
				{
				  "info": { "agent": "Orchestrator", "directory": "C:/ws" },
				  "messages": [
				    { "role": "user", "parts": [
				      { "type": "text", "text": "Create a new CSS-position form named \\"customerDetail\\" in the active solution, bound to db:/example_data/customers. Place two text fields on it: one bound to \\"companyname\\", one bound to \\"contactname\\"." }
				    ] },
				    { "role": "assistant", "parts": [
				      { "type": "tool", "tool": "task", "state": { "input": { "subagent_type": "Developer" },
				        "output": "<task_result>\\nChanged files:\\n- tst/forms/customerDetail.frm — CSS-position form\\n- tst/forms/customerDetail.js — companion\\n- tst/forms/customerDetail.less — stylesheet\\n</task_result>" } }
				    ] }
				  ]
				}
				""";
		JsonNode root = MAPPER.readTree(export);
		List<OutcomeAssertion> inferred = OutcomeInference.infer(root);

		Optional<OutcomeAssertion> form = inferred.stream()
				.filter(a -> a.kind() == PersistKind.FORM && "customerDetail".equals(a.name())).findFirst();
		assertTrue(form.isPresent(), "should infer the customerDetail form from the task_result");
		OutcomeAssertion f = form.get();
		assertNotNull(f.props().get("dataSource"), "datasource should be inferred from the prompt");
		assertNotNull(f.props().get("useCssPosition"), "layout mode should be inferred from the prompt");
		// two dataproviders -> two component children (the form name is excluded)
		long componentChildren = f.children().stream().filter(c -> c.kind() == PersistKind.COMPONENT).count();
		assertEquals(2, componentChildren, "companyname + contactname become component children");
		// each inferred component carries a typeName placeholder (real type can't be
		// inferred from the orchestrator export) plus its dataProviderID
		OutcomeAssertion firstComponent = f.children().stream().filter(c -> c.kind() == PersistKind.COMPONENT)
				.findFirst().orElseThrow();
		assertNotNull(firstComponent.props().get("typeName"), "component should carry a typeName placeholder");
		assertEquals(OutcomeInference.TYPE_NAME_PLACEHOLDER,
				String.valueOf(firstComponent.props().get("typeName").getExpected()),
				"typeName should be the placeholder until the user edits it");
		assertNotNull(firstComponent.props().get("dataProviderID"), "component should carry its dataProviderID");
	}

	@Test
	void infersValuelistFromChangedFile() throws Exception {
		String export = """
				{ "messages": [
				  { "role": "assistant", "parts": [
				    { "type": "tool", "tool": "task", "state": { "output":
				      "Changed files:\\n- sol/valuelists/colors.val" } }
				  ] }
				] }
				""";
		List<OutcomeAssertion> inferred = OutcomeInference.infer(MAPPER.readTree(export));
		assertTrue(inferred.stream().anyMatch(a -> a.kind() == PersistKind.VALUELIST && "colors".equals(a.name())));
	}

	@Test
	void emptyExportInfersNothing() throws Exception {
		assertTrue(OutcomeInference.infer(MAPPER.readTree("{ \"messages\": [] }")).isEmpty());
	}
}

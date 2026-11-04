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

package com.servoy.eclipse.opencode.skilltest.coverage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.McpToolCall;

/**
 * Unit tests for {@link CoverageExtractor} — classifying captured
 * {@link McpToolCall}s into the skills / agents / MCP-tools dimensions, always
 * adding the builder agent, tolerant argument-key probing, and graceful
 * degradation when a {@code skill}/{@code task} call carries no recognizable id
 * (SVY-21711 §3.1).
 */
public class CoverageExtractorTest {

	private static McpToolCall call(String server, String tool, Map<String, Object> args) {
		return new McpToolCall(server, tool, args, null);
	}

	@Nested
	class SkillDimension {

		@Test
		@DisplayName("a skill call records the skill id from the 'id' argument")
		void skillIdFromArgs() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "skill", Map.of("id", "sdd-java-plain"))), null);
			assertAll(() -> assertTrue(obs.skills().contains("sdd-java-plain")),
					() -> assertEquals(1, obs.skills().size()),
					// Not misclassified as an MCP tool.
					() -> assertFalse(obs.mcpTools().contains("skill")));
		}

		@Test
		@DisplayName("a skill call degrades to the literal 'skill' when no id is present")
		void skillWithoutIdDegrades() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "skill", Map.of("unrelated", "x"))), null);
			// Documented contract: the raw tool name is recorded so the dimension is
			// still non-empty rather than throwing or being silently dropped.
			assertAll(() -> assertTrue(obs.skills().contains("skill")), () -> assertEquals(1, obs.skills().size()));
		}

		@Test
		@DisplayName("skill id probing is tolerant of the alternative keys")
		void tolerantSkillKeys() {
			CoverageObservation viaSkill = CoverageExtractor.observe("b1",
					List.of(call(null, "skill", Map.of("skill", "servoy-jira"))), null);
			CoverageObservation viaName = CoverageExtractor.observe("b1",
					List.of(call(null, "skill", Map.of("name", "release-notes"))), null);
			assertAll(() -> assertTrue(viaSkill.skills().contains("servoy-jira")),
					() -> assertTrue(viaName.skills().contains("release-notes")));
		}
	}

	@Nested
	class AgentDimension {

		@Test
		@DisplayName("a task call records the dispatched agent from 'subagent_type'")
		void taskAgentFromArgs() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "task", Map.of("subagent_type", "test-writer"))), null);
			assertAll(() -> assertTrue(obs.agents().contains("test-writer")),
					() -> assertFalse(obs.mcpTools().contains("task")));
		}

		@Test
		@DisplayName("dispatched-agent probing is tolerant of the alternative keys")
		void tolerantAgentKeys() {
			CoverageObservation viaAgent = CoverageExtractor.observe("b1",
					List.of(call(null, "task", Map.of("agent", "reviewer"))), null);
			CoverageObservation viaCamel = CoverageExtractor.observe("b1",
					List.of(call(null, "task", Map.of("subagentType", "planner"))), null);
			assertAll(() -> assertTrue(viaAgent.agents().contains("reviewer")),
					() -> assertTrue(viaCamel.agents().contains("planner")));
		}

		@Test
		@DisplayName("the builder effectiveAgent is always included, trimmed")
		void builderAgentAlwaysIncluded() {
			CoverageObservation obs = CoverageExtractor.observe("b1", List.of(), "  build  ");
			assertTrue(obs.agents().contains("build"));
		}

		@Test
		@DisplayName("a task call with no recognizable agent adds nothing but does not throw")
		void taskWithoutAgentDegrades() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "task", Map.of("unrelated", "x"))), "build");
			assertNotNull(obs);
			// Documented contract: only the builder agent is present; the unrecognized
			// task contributes no agent and is NOT recorded as an MCP tool.
			assertAll(() -> assertEquals(1, obs.agents().size()), () -> assertTrue(obs.agents().contains("build")),
					() -> assertTrue(obs.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("builder agent and a dispatched sub-agent both appear")
		void builderAndDispatched() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "task", Map.of("subagent_type", "writer"))), "build");
			assertAll(() -> assertTrue(obs.agents().contains("build")),
					() -> assertTrue(obs.agents().contains("writer")), () -> assertEquals(2, obs.agents().size()));
		}

		@Test
		@DisplayName("the 'subagent' tool is also recognized as a dispatch (the real pack uses it)")
		void subagentToolRecognized() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "subagent", Map.of("subagent_type", "Orchestrator"))), "build");
			assertAll(() -> assertTrue(obs.agents().contains("Orchestrator")),
					() -> assertTrue(obs.agents().contains("build")),
					// never leaks into the MCP dimension
					() -> assertTrue(obs.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("dispatched-agent probing picks up the 'agentType' key too")
		void tolerantAgentTypeKey() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "subagent", Map.of("agentType", "Developer"))), null);
			assertTrue(obs.agents().contains("Developer"));
		}
	}

	@Nested
	class McpToolDimension {

		@Test
		@DisplayName("an ordinary MCP call records the 'server_tool' identity")
		void serverQualifiedIdentity() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call("eclipse-ide", "readProjectResource", Map.of())), null);
			assertAll(() -> assertTrue(obs.mcpTools().contains("eclipse-ide_readProjectResource")),
					() -> assertTrue(obs.skills().isEmpty()));
		}

		@Test
		@DisplayName("opencode built-in tools are dropped entirely, not recorded as MCP tools")
		void builtinsAreDropped() {
			CoverageObservation obs = CoverageExtractor.observe("b1",
					List.of(call(null, "read", Map.of()), call(null, "execute", Map.of()),
							call(null, "webfetch", Map.of()), call("servoy-workspace", "listProjects", Map.of())),
					null);
			// Only the real MCP call survives; read/execute/webfetch are built-ins.
			assertAll(() -> assertEquals(1, obs.mcpTools().size()),
					() -> assertTrue(obs.mcpTools().contains("servoy-workspace_listProjects")),
					() -> assertFalse(obs.mcpTools().contains("read")),
					() -> assertFalse(obs.mcpTools().contains("execute")));
		}

		@Test
		@DisplayName("a server-less MCP call records the bare tool name")
		void bareToolIdentity() {
			CoverageObservation obs = CoverageExtractor.observe("b1", List.of(call(null, "formatCode", Map.of())),
					null);
			assertTrue(obs.mcpTools().contains("formatCode"));
		}
	}

	@Nested
	class Defensive {

		@Test
		@DisplayName("null calls and null/blank agent yield an empty, non-null observation")
		void nullInputs() {
			CoverageObservation obs = CoverageExtractor.observe(null, null, "   ");
			assertAll(() -> assertNotNull(obs), () -> assertTrue(obs.skills().isEmpty()),
					() -> assertTrue(obs.agents().isEmpty()), () -> assertTrue(obs.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("a null element and a call with a null tool are skipped without throwing")
		void skipsNullToolCall() {
			McpToolCall nullTool = new McpToolCall("srv", null, Map.of(), null);
			// Use a non-built-in tool so it survives into the MCP dimension.
			CoverageObservation obs = CoverageExtractor.observe("b1",
					java.util.Arrays.asList(null, nullTool, call("eclipse-ide", "readProjectResource", Map.of())),
					"build");
			assertAll(() -> assertEquals(1, obs.mcpTools().size()),
					() -> assertTrue(obs.mcpTools().contains("eclipse-ide_readProjectResource")));
		}

		@Test
		@DisplayName("the baseline id is carried onto the observation")
		void carriesBaselineId() {
			CoverageObservation obs = CoverageExtractor.observe("create-valuelist-basic", List.of(), "build");
			assertEquals("create-valuelist-basic", obs.baselineId());
		}
	}
}

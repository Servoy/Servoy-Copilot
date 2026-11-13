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

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SessionTranscript} — parsing the golden export shape
 * and the message-response fallback shape, plus defensive null-safety on
 * malformed input. Tests use the production {@code fromExport(JsonNode)} /
 * {@code fromMessageResponse(JsonNode)} entry points.
 */
class SessionTranscriptTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The golden export shape committed as the create-valuelist-basic seed. */
	private static final String GOLDEN_EXPORT = "{" + "\"messages\":["
			+ "  {\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"Create a value list named colors with the custom values one, two and three.\"}]},"
			+ "  {\"role\":\"assistant\",\"parts\":[{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\","
			+ "    \"state\":{\"input\":{\"name\":\"colors\",\"values\":[\"one\",\"two\",\"three\"]},"
			+ "    \"output\":\"Created value list 'colors' with 3 custom values.\"}}]}" + "]}";

	private static JsonNode parse(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Nested
	class FromExportGolden {

		@Test
		@DisplayName("extracts the first user text part as the prompt")
		void extractsPrompt() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(GOLDEN_EXPORT));
			assertEquals("Create a value list named colors with the custom values one, two and three.",
					transcript.getPrompt());
		}

		@Test
		@DisplayName("extracts the tool call with server, tool, args and output")
		void extractsToolCall() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(GOLDEN_EXPORT));
			List<McpToolCall> calls = transcript.getToolCalls();
			assertEquals(1, calls.size());
			McpToolCall call = calls.get(0);
			assertAll(() -> assertEquals("servoy-coder", call.server()),
					() -> assertEquals("create_valuelist", call.tool()),
					() -> assertEquals("colors", call.arguments().get("name")),
					() -> assertEquals(List.of("one", "two", "three"), call.arguments().get("values")),
					() -> assertEquals("Created value list 'colors' with 3 custom values.", call.output()));
		}

		@Test
		@DisplayName("returned tool-call list is unmodifiable")
		void toolCallsUnmodifiable() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(GOLDEN_EXPORT));
			org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
					() -> transcript.getToolCalls().clear());
		}
	}

	@Nested
	class CodeModeNestedCalls {

		@Test
		@DisplayName("MCP tools invoked inside an execute part are harvested from state.metadata.toolCalls")
		void harvestsNestedExecuteCalls() {
			// opencode 2.x Code Mode: the agent calls MCP tools from inside the
			// `execute` script, so the only record is the execute part's
			// state.metadata.toolCalls[] (SVY-21711).
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"name\":\"execute\",\"state\":{\"status\":\"completed\","
					+ "\"input\":{\"code\":\"return await tools['servoy-workspace'].listProjects();\"},"
					+ "\"metadata\":{\"toolCalls\":["
					+ "{\"tool\":\"servoy-workspace.listProjects\",\"status\":\"completed\"},"
					+ "{\"tool\":\"servoy-model.getTarget\",\"status\":\"completed\"}]}}}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			List<McpToolCall> calls = transcript.getToolCalls();
			// The execute part itself is one call; its two nested MCP identities are
			// added too, split into server/tool.
			assertAll(
					() -> assertTrue(
							calls.stream().anyMatch(
									c -> "servoy-workspace".equals(c.server()) && "listProjects".equals(c.tool())),
							"servoy-workspace.listProjects captured"),
					() -> assertTrue(
							calls.stream()
									.anyMatch(c -> "servoy-model".equals(c.server()) && "getTarget".equals(c.tool())),
							"servoy-model.getTarget captured"));
		}

		@Test
		@DisplayName("an execute part with no nested toolCalls contributes only itself")
		void executeWithoutNestedCalls() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"name\":\"execute\",\"state\":{\"status\":\"completed\","
					+ "\"input\":{\"code\":\"return 1 + 1;\"}}}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			List<McpToolCall> calls = transcript.getToolCalls();
			assertAll(() -> assertEquals(1, calls.size(), "only the execute call itself"),
					() -> assertEquals("execute", calls.get(0).tool()));
		}
	}

	@Nested
	class ArgumentTypes {

		@Test
		@DisplayName("input arguments taken from top-level input when no state wrapper")
		void topLevelInput() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"servoy-coder_create_form\","
					+ "\"input\":{\"name\":\"orders\"},\"output\":\"ok\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			McpToolCall call = transcript.getToolCalls().get(0);
			assertAll(() -> assertEquals("orders", call.arguments().get("name")),
					() -> assertEquals("ok", call.output()));
		}

		@Test
		@DisplayName("numeric and boolean argument values are preserved")
		void numericAndBoolean() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"t\",\"state\":{\"input\":{\"count\":3,\"flag\":true}}}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			McpToolCall call = transcript.getToolCalls().get(0);
			assertAll(() -> assertEquals(3L, call.arguments().get("count")),
					() -> assertEquals(Boolean.TRUE, call.arguments().get("flag")));
		}
	}

	@Nested
	class FromMessageResponseFallback {

		@Test
		@DisplayName("parses a { info, parts } envelope for tool calls")
		void parsesEnvelope() {
			String json = "{\"info\":{\"role\":\"assistant\"},\"parts\":["
					+ "{\"type\":\"tool\",\"toolName\":\"servoy-coder_create_valuelist\","
					+ "\"arguments\":{\"name\":\"colors\"},\"output\":\"done\"}]}";
			SessionTranscript transcript = SessionTranscript.fromMessageResponse(parse(json));
			List<McpToolCall> calls = transcript.getToolCalls();
			assertEquals(1, calls.size());
			assertAll(() -> assertEquals("servoy-coder", calls.get(0).server()),
					() -> assertEquals("create_valuelist", calls.get(0).tool()),
					() -> assertEquals("colors", calls.get(0).arguments().get("name")));
		}
	}

	@Nested
	class DefensiveParsing {

		@Test
		@DisplayName("null root yields empty transcript without throwing")
		void nullRoot() {
			SessionTranscript transcript = SessionTranscript.fromExport(null);
			assertAll(() -> assertNull(transcript.getPrompt()), () -> assertTrue(transcript.getToolCalls().isEmpty()));
		}

		@Test
		@DisplayName("empty messages array yields empty transcript")
		void emptyMessages() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse("{\"messages\":[]}"));
			assertAll(() -> assertNull(transcript.getPrompt()), () -> assertTrue(transcript.getToolCalls().isEmpty()));
		}

		@Test
		@DisplayName("message with no parts does not throw and produces no tool calls")
		void missingParts() {
			SessionTranscript transcript = SessionTranscript
					.fromExport(parse("{\"messages\":[{\"role\":\"assistant\"}]}"));
			assertTrue(transcript.getToolCalls().isEmpty());
		}

		@Test
		@DisplayName("tool part with no state and no input is skipped without throwing")
		void toolPartWithoutStateOrInput() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals(1, transcript.getToolCalls().size());
			assertTrue(transcript.getToolCalls().get(0).arguments().isEmpty());
		}

		@Test
		@DisplayName("non-tool, non-text parts are ignored")
		void nonToolNonTextParts() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"reasoning\",\"text\":\"thinking...\"}," + "{\"type\":\"step-start\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertTrue(transcript.getToolCalls().isEmpty());
		}

		@Test
		@DisplayName("prompt is taken only from the first user text part")
		void firstUserTextOnly() {
			String json = "{\"messages\":[" + "{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"first\"}]},"
					+ "{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"second\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals("first", transcript.getPrompt());
		}

		@Test
		@DisplayName("falls back to scanning parts anywhere when no messages array")
		void fallbackWithoutMessagesArray() {
			String json = "{\"session\":{\"parts\":[" + "{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\","
					+ "\"state\":{\"input\":{\"name\":\"colors\"}}}]}}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals(1, transcript.getToolCalls().size());
			assertEquals("create_valuelist", transcript.getToolCalls().get(0).tool());
		}
	}

	@Nested
	class Duration {

		@Test
		@DisplayName("duration spans earliest created to latest completed across messages")
		void spansCreatedToCompleted() {
			String json = "{\"messages\":["
					+ "{\"info\":{\"role\":\"user\",\"time\":{\"created\":1000,\"completed\":1500}},\"parts\":[]},"
					+ "{\"info\":{\"role\":\"assistant\",\"time\":{\"created\":1600,\"completed\":4000}},\"parts\":[]}"
					+ "]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals(3000L, transcript.getDurationMillis());
		}

		@Test
		@DisplayName("duration is zero when timestamps are absent")
		void zeroWhenNoTimestamps() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(GOLDEN_EXPORT));
			assertEquals(0L, transcript.getDurationMillis());
		}

		@Test
		@DisplayName("duration is zero when only a created timestamp is present")
		void zeroWhenOnlyCreated() {
			String json = "{\"messages\":["
					+ "{\"info\":{\"role\":\"user\",\"time\":{\"created\":1000}},\"parts\":[]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals(0L, transcript.getDurationMillis());
		}
	}

	@Nested
	class AgentAndModel {

		@Test
		@DisplayName("agent and provider/model are read from top-level info")
		void readsAgentAndModel() {
			String json = "{\"info\":{\"agent\":\"Orchestrator\","
					+ "\"model\":{\"id\":\"claude-sonnet-5\",\"providerID\":\"kiro\",\"modelID\":\"claude-sonnet-5\"}},"
					+ "\"messages\":[]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertAll(() -> assertEquals("Orchestrator", transcript.getAgent()),
					() -> assertEquals("kiro/claude-sonnet-5", transcript.getModel()));
		}

		@Test
		@DisplayName("model falls back to id when modelID is absent")
		void modelIdFallback() {
			String json = "{\"info\":{\"model\":{\"id\":\"claude-sonnet-5\",\"providerID\":\"kiro\"}},"
					+ "\"messages\":[]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals("kiro/claude-sonnet-5", transcript.getModel());
		}

		@Test
		@DisplayName("agent and model are null when info is absent")
		void nullWhenNoInfo() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(GOLDEN_EXPORT));
			assertAll(() -> assertNull(transcript.getAgent()), () -> assertNull(transcript.getModel()));
		}
	}

	@Nested
	class Tokens {

		@Test
		@DisplayName("session-level info.tokens is used when present")
		void sessionLevelTokens() {
			String json = "{\"info\":{\"tokens\":{\"input\":100,\"output\":50,\"reasoning\":10,"
					+ "\"cache\":{\"read\":7,\"write\":3}}},\"messages\":[]}";
			RunMetrics.TokenUsage tokens = SessionTranscript.fromExport(parse(json)).getTokens();
			org.junit.jupiter.api.Assertions.assertNotNull(tokens, "session-level tokens parsed");
			assertAll(() -> assertEquals(100L, tokens.input()), () -> assertEquals(50L, tokens.output()),
					() -> assertEquals(10L, tokens.reasoning()), () -> assertEquals(7L, tokens.cacheRead()),
					() -> assertEquals(3L, tokens.cacheWrite()),
					() -> assertEquals(160L, tokens.total(), "total excludes cache"));
		}

		@Test
		@DisplayName("per-message info.tokens are SUMMED across ALL messages when session level is absent")
		void summedPerMessageWhenSessionAbsent() {
			// Regression guard for the fixed bug: a multi-message export must sum EVERY
			// assistant message's tokens, not only the first (SVY-21660).
			String json = "{\"messages\":["
					+ "{\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":100,\"output\":20,\"reasoning\":1,"
					+ "\"cache\":{\"read\":2,\"write\":3}}},\"parts\":[]},"
					+ "{\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":200,\"output\":30,\"reasoning\":4,"
					+ "\"cache\":{\"read\":5,\"write\":6}}},\"parts\":[]},"
					+ "{\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":300,\"output\":40,\"reasoning\":5,"
					+ "\"cache\":{\"read\":8,\"write\":9}}},\"parts\":[]}" + "]}";
			RunMetrics.TokenUsage tokens = SessionTranscript.fromExport(parse(json)).getTokens();
			org.junit.jupiter.api.Assertions.assertNotNull(tokens, "per-message tokens summed");
			assertAll(() -> assertEquals(600L, tokens.input(), "100+200+300, not just the first message"),
					() -> assertEquals(90L, tokens.output(), "20+30+40"),
					() -> assertEquals(10L, tokens.reasoning(), "1+4+5"),
					() -> assertEquals(15L, tokens.cacheRead(), "2+5+8"),
					() -> assertEquals(18L, tokens.cacheWrite(), "3+6+9"),
					() -> assertEquals(700L, tokens.total(), "600+90+10, cache excluded"));
		}

		@Test
		@DisplayName("session-level tokens are not double-counted with per-message tokens")
		void sessionLevelNoDoubleCount() {
			// The session level supplies the data, so per-message tokens must be ignored.
			String json = "{\"info\":{\"tokens\":{\"input\":100,\"output\":50,\"reasoning\":0}}," + "\"messages\":["
					+ "{\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":999,\"output\":999,\"reasoning\":999}},"
					+ "\"parts\":[]}" + "]}";
			RunMetrics.TokenUsage tokens = SessionTranscript.fromExport(parse(json)).getTokens();
			assertAll(() -> assertEquals(100L, tokens.input(), "only the session-level figure"),
					() -> assertEquals(50L, tokens.output(), "per-message tokens NOT added"),
					() -> assertEquals(150L, tokens.total()));
		}

		@Test
		@DisplayName("nested cache.read / cache.write are captured")
		void cacheCaptured() {
			String json = "{\"info\":{\"tokens\":{\"input\":1,\"output\":1,\"reasoning\":0,"
					+ "\"cache\":{\"read\":42,\"write\":7}}},\"messages\":[]}";
			RunMetrics.TokenUsage tokens = SessionTranscript.fromExport(parse(json)).getTokens();
			assertAll(() -> assertEquals(42L, tokens.cacheRead()), () -> assertEquals(7L, tokens.cacheWrite()));
		}

		@Test
		@DisplayName("absent token data yields null (unknown), never a 0 TokenUsage")
		void absentYieldsNull() {
			// No info.tokens anywhere: getTokens() must be null so a surface reads
			// "unknown" rather than a misleading 0-token run.
			assertNull(SessionTranscript.fromExport(parse(GOLDEN_EXPORT)).getTokens(), "no token data -> null");
		}

		@Test
		@DisplayName("an all-zero token payload is treated as absent (null), not a real 0")
		void allZeroYieldsNull() {
			String json = "{\"info\":{\"tokens\":{\"input\":0,\"output\":0,\"reasoning\":0,"
					+ "\"cache\":{\"read\":0,\"write\":0}}},\"messages\":["
					+ "{\"info\":{\"role\":\"assistant\",\"tokens\":{\"input\":0,\"output\":0,\"reasoning\":0}},\"parts\":[]}"
					+ "]}";
			assertNull(SessionTranscript.fromExport(parse(json)).getTokens(),
					"all-zero payload -> null (unknown), not a 0 total");
		}
	}

	/**
	 * The per-request token list, built from the shape the live opencode HTTP
	 * export really uses: every assistant message carries its own {@code tokens}
	 * object at the MESSAGE top level, a sibling of {@code id}/{@code type}/
	 * {@code agent}/{@code model}/{@code content}/{@code cost} - there is no
	 * {@code info} wrapper (SVY-21660 C, measured against a real export dump). The
	 * older {@code info.tokens} shape stays covered as the fallback.
	 * <p>
	 * This list is the ONLY source of the per-turn figures: the {@code opencode.db}
	 * reader that used to be preferred is gone, because the harness's sessions were
	 * never written to that DB at all.
	 */
	@Nested
	class RequestTokensFromExport {

		/**
		 * The real export shape: two assistant turns, each with its own top-level
		 * {@code tokens}, matching the live figures that were measured (input 49755
		 * then 68463, cache all-zero).
		 */
		private static final String REAL_EXPORT = "{\"messages\":["
				+ "{\"id\":\"msg_0\",\"type\":\"user\",\"text\":\"build it\",\"content\":[]},"
				+ "{\"id\":\"msg_1\",\"type\":\"assistant\",\"agent\":\"Orchestrator\",\"content\":[],\"cost\":0,"
				+ "\"tokens\":{\"input\":49755,\"output\":61,\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}}},"
				+ "{\"id\":\"msg_2\",\"type\":\"assistant\",\"agent\":\"Orchestrator\",\"content\":[],\"cost\":0,"
				+ "\"tokens\":{\"input\":68463,\"output\":120,\"reasoning\":5,\"cache\":{\"read\":0,\"write\":0}}}"
				+ "]}";

		@Test
		@DisplayName("one requestTokens entry per assistant message of the REAL export shape, in order, with per-request figures")
		void onePerAssistantMessageInOrder() {
			List<RunMetrics.RequestTokens> requests = SessionTranscript.fromExport(parse(REAL_EXPORT))
					.getRequestTokens();
			assertEquals(2, requests.size(), "one entry per assistant request; the user message contributes none");
			RunMetrics.RequestTokens first = requests.get(0);
			RunMetrics.RequestTokens second = requests.get(1);
			assertAll(() -> assertEquals(0, first.index(), "0-based index"),
					() -> assertEquals(49755L, first.tokens().input(), "first request's own input"),
					() -> assertEquals(61L, first.tokens().output()),
					() -> assertEquals(0L, first.tokens().cacheRead(), "the live figures carry no cache"),
					() -> assertEquals("Orchestrator", first.skill(), "agent read from the message top level"),
					() -> assertEquals(1, second.index()),
					() -> assertEquals(68463L, second.tokens().input(), "second request's own input"),
					() -> assertEquals(5L, second.tokens().reasoning()),
					() -> assertEquals("Orchestrator", second.skill()));
		}

		@Test
		@DisplayName("the summed session tokens equal the sum of the per-request entries of the REAL shape")
		void summedTokensEqualPerRequestSum() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse(REAL_EXPORT));
			RunMetrics.TokenUsage summed = transcript.getTokens();
			List<RunMetrics.RequestTokens> requests = transcript.getRequestTokens();
			long inputSum = requests.stream().mapToLong(r -> r.tokens().input()).sum();
			long outputSum = requests.stream().mapToLong(r -> r.tokens().output()).sum();
			org.junit.jupiter.api.Assertions.assertNotNull(summed, "top-level per-message tokens are summed");
			assertAll(() -> assertEquals(118218L, summed.input(), "49755+68463"),
					() -> assertEquals(118218L, inputSum, "per-request inputs sum to the same"),
					() -> assertEquals(181L, summed.output(), "61+120"), () -> assertEquals(181L, outputSum));
		}

		@Test
		@DisplayName("each request carries its own callCount and the tool calls made in it (incl. nested Code-Mode)")
		void perRequestToolCallsAndCallCount() {
			// Two assistant turns of the real shape: the first invokes MCP tools from
			// inside an execute part - the nested identities (SVY-21711) count toward
			// THIS request's callCount - the second calls one plain tool.
			String json = "{\"messages\":["
					+ "{\"id\":\"msg_1\",\"type\":\"assistant\",\"agent\":\"Orchestrator\",\"cost\":0,"
					+ "\"tokens\":{\"input\":49755,\"output\":61,\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}},"
					+ "\"content\":[{\"type\":\"tool\",\"name\":\"execute\",\"state\":{\"status\":\"completed\","
					+ "\"input\":{\"code\":\"...\"},\"metadata\":{\"toolCalls\":["
					+ "{\"tool\":\"servoy-workspace.listProjects\",\"status\":\"completed\"},"
					+ "{\"tool\":\"servoy-model.getTarget\",\"status\":\"completed\"}]}}}]},"
					+ "{\"id\":\"msg_2\",\"type\":\"assistant\",\"agent\":\"Orchestrator\",\"cost\":0,"
					+ "\"tokens\":{\"input\":68463,\"output\":120,\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}},"
					+ "\"content\":[{\"type\":\"tool\",\"name\":\"servoy-coder_create_form\","
					+ "\"state\":{\"input\":{\"name\":\"orders\"}}}]}" + "]}";
			List<RunMetrics.RequestTokens> requests = SessionTranscript.fromExport(parse(json)).getRequestTokens();
			assertEquals(2, requests.size());
			List<String> firstTools = requests.get(0).toolNames();
			List<String> secondTools = requests.get(1).toolNames();
			assertAll(() -> assertTrue(firstTools.contains("execute"), "the execute part itself: " + firstTools),
					() -> assertTrue(firstTools.contains("servoy-workspace.listProjects"),
							"the nested Code-Mode call attributed to the request: " + firstTools),
					() -> assertEquals(3, requests.get(0).callCount(),
							"callCount counts the execute part plus its two nested Code-Mode calls"),
					() -> assertEquals(1, requests.get(1).callCount(), "the second request's single plain tool call"),
					() -> assertTrue(secondTools.contains("servoy-coder.create_form"),
							"the second request's own call: " + secondTools));
		}

		@Test
		@DisplayName("absent token data yields an empty requestTokens list (never null)")
		void absentYieldsEmptyList() {
			List<RunMetrics.RequestTokens> requests = SessionTranscript.fromExport(parse(GOLDEN_EXPORT))
					.getRequestTokens();
			org.junit.jupiter.api.Assertions.assertNotNull(requests, "never null");
			assertTrue(requests.isEmpty(), "no per-request token data -> empty list");
		}

		@Test
		@DisplayName("an all-zero per-message token payload produces no request entry")
		void allZeroProducesNoEntry() {
			String json = "{\"messages\":["
					+ "{\"id\":\"msg_1\",\"type\":\"assistant\",\"tokens\":{\"input\":0,\"output\":0,"
					+ "\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}},\"content\":[]}" + "]}";
			assertTrue(SessionTranscript.fromExport(parse(json)).getRequestTokens().isEmpty(),
					"an all-zero request is not fabricated into the list");
		}

		@Test
		@DisplayName("an assistant message with ZERO tokens contributes no entry, a sibling non-zero one does")
		void zeroTokenAssistantContributesNoEntry() {
			// A zero-token assistant message must NOT fabricate an entry, while its
			// non-zero sibling does - the list is keyed on a non-zero tokens object, not
			// on the resolved role.
			String json = "{\"messages\":["
					+ "{\"id\":\"msg_1\",\"type\":\"assistant\",\"tokens\":{\"input\":0,\"output\":0,\"reasoning\":0},"
					+ "\"content\":[]},"
					+ "{\"id\":\"msg_2\",\"type\":\"assistant\",\"tokens\":{\"input\":50,\"output\":10,\"reasoning\":0},"
					+ "\"content\":[]}" + "]}";
			List<RunMetrics.RequestTokens> requests = SessionTranscript.fromExport(parse(json)).getRequestTokens();
			assertAll(() -> assertEquals(1, requests.size(), "only the non-zero assistant request"),
					() -> assertEquals(50L, requests.get(0).tokens().input(), "the non-zero request's figure"),
					() -> assertEquals(0, requests.get(0).index(), "re-indexed 0-based"));
		}

		@Test
		@DisplayName("the LEGACY info.tokens shape still builds one entry per assistant request")
		void legacyInfoTokensShapeBuildsEntries() {
			// Fallback coverage: an older export wraps the per-message figures in
			// `info.tokens` (and carries no top-level role/type, only `info.role`). The
			// build is keyed on a non-zero tokens object wherever it sits, so this shape
			// must still produce one entry per assistant request - and the agent then
			// comes from `info.agent`.
			String json = "{\"messages\":["
					+ "{\"info\":{\"role\":\"user\",\"tokens\":{\"input\":0,\"output\":0,\"reasoning\":0}},\"parts\":[]},"
					+ "{\"info\":{\"role\":\"assistant\",\"agent\":\"builder\",\"tokens\":{\"input\":100,"
					+ "\"output\":20,\"reasoning\":1,\"cache\":{\"read\":7,\"write\":8}}},\"parts\":[]},"
					+ "{\"info\":{\"role\":\"assistant\",\"agent\":\"builder\",\"tokens\":{\"input\":474000,"
					+ "\"output\":30,\"reasoning\":2,\"cache\":{\"read\":0,\"write\":0}}},\"parts\":[]}" + "]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			List<RunMetrics.RequestTokens> requests = transcript.getRequestTokens();
			RunMetrics.TokenUsage summed = transcript.getTokens();
			long inputSum = requests.stream().mapToLong(r -> r.tokens().input()).sum();
			assertAll(
					() -> assertEquals(2, requests.size(),
							"one entry per assistant request (the zero-token user message contributes none)"),
					() -> assertEquals(100L, requests.get(0).tokens().input(), "first assistant request's input"),
					() -> assertEquals(7L, requests.get(0).tokens().cacheRead(), "nested cache parsed on the request"),
					() -> assertEquals("builder", requests.get(0).skill(), "agent falls back to info.agent"),
					() -> assertEquals(474000L, requests.get(1).tokens().input(), "second assistant request's input"),
					() -> org.junit.jupiter.api.Assertions.assertNotNull(summed, "the summed total is also non-zero"),
					() -> assertEquals(474100L, summed.input(), "summed input over the two assistant requests"),
					() -> assertEquals(inputSum, summed.input(), "per-request inputs sum to the session total"));
		}
	}
}

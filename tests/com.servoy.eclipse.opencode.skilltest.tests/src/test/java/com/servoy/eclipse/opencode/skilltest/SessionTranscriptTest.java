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
 * Unit tests for {@link SessionTranscript} — parsing the golden export shape and
 * the message-response fallback shape, plus defensive null-safety on malformed
 * input. Tests use the production {@code fromExport(JsonNode)} /
 * {@code fromMessageResponse(JsonNode)} entry points.
 */
class SessionTranscriptTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The golden export shape committed as the create-valuelist-basic seed. */
	private static final String GOLDEN_EXPORT = "{"
			+ "\"messages\":["
			+ "  {\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"Create a value list named colors with the custom values one, two and three.\"}]},"
			+ "  {\"role\":\"assistant\",\"parts\":[{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\","
			+ "    \"state\":{\"input\":{\"name\":\"colors\",\"values\":[\"one\",\"two\",\"three\"]},"
			+ "    \"output\":\"Created value list 'colors' with 3 custom values.\"}}]}"
			+ "]}";

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
			assertAll(
					() -> assertEquals("servoy-coder", call.server()),
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
	class ArgumentTypes {

		@Test
		@DisplayName("input arguments taken from top-level input when no state wrapper")
		void topLevelInput() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"servoy-coder_create_form\","
					+ "\"input\":{\"name\":\"orders\"},\"output\":\"ok\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			McpToolCall call = transcript.getToolCalls().get(0);
			assertAll(
					() -> assertEquals("orders", call.arguments().get("name")),
					() -> assertEquals("ok", call.output()));
		}

		@Test
		@DisplayName("numeric and boolean argument values are preserved")
		void numericAndBoolean() {
			String json = "{\"messages\":[{\"role\":\"assistant\",\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"t\",\"state\":{\"input\":{\"count\":3,\"flag\":true}}}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			McpToolCall call = transcript.getToolCalls().get(0);
			assertAll(
					() -> assertEquals(3L, call.arguments().get("count")),
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
			assertAll(
					() -> assertEquals("servoy-coder", calls.get(0).server()),
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
			assertAll(
					() -> assertNull(transcript.getPrompt()),
					() -> assertTrue(transcript.getToolCalls().isEmpty()));
		}

		@Test
		@DisplayName("empty messages array yields empty transcript")
		void emptyMessages() {
			SessionTranscript transcript = SessionTranscript.fromExport(parse("{\"messages\":[]}"));
			assertAll(
					() -> assertNull(transcript.getPrompt()),
					() -> assertTrue(transcript.getToolCalls().isEmpty()));
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
					+ "{\"type\":\"reasoning\",\"text\":\"thinking...\"},"
					+ "{\"type\":\"step-start\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertTrue(transcript.getToolCalls().isEmpty());
		}

		@Test
		@DisplayName("prompt is taken only from the first user text part")
		void firstUserTextOnly() {
			String json = "{\"messages\":["
					+ "{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"first\"}]},"
					+ "{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"second\"}]}]}";
			SessionTranscript transcript = SessionTranscript.fromExport(parse(json));
			assertEquals("first", transcript.getPrompt());
		}

		@Test
		@DisplayName("falls back to scanning parts anywhere when no messages array")
		void fallbackWithoutMessagesArray() {
			String json = "{\"session\":{\"parts\":["
					+ "{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\","
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
			assertAll(
					() -> assertEquals("Orchestrator", transcript.getAgent()),
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
			assertAll(
					() -> assertNull(transcript.getAgent()),
					() -> assertNull(transcript.getModel()));
		}
	}
}

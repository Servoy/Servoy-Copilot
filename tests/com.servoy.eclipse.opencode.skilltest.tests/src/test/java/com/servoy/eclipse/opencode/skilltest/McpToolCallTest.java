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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link McpToolCall} — tolerant server/tool name splitting,
 * factory derivation, null-safety, and identity matching.
 */
class McpToolCallTest {

	@Nested
	class SplitToolName {

		@ParameterizedTest
		@CsvSource({
				"servoy-coder_create_valuelist, servoy-coder, create_valuelist",
				"servoy-ide_delete_form, servoy-ide, delete_form",
				"eclipse-ide_read_file, eclipse-ide, read_file" })
		@DisplayName("underscore form splits server from tool at first underscore")
		void underscoreForm(String raw, String expectedServer, String expectedTool) {
			String[] parts = McpToolCall.splitToolName(raw);
			assertAll(
					() -> assertEquals(expectedServer, parts[0]),
					() -> assertEquals(expectedTool, parts[1]));
		}

		@ParameterizedTest
		@CsvSource({
				"servoy-coder.create_valuelist, servoy-coder, create_valuelist",
				"servername.tool, servername, tool" })
		@DisplayName("dotted form splits server from tool at first dot")
		void dottedForm(String raw, String expectedServer, String expectedTool) {
			String[] parts = McpToolCall.splitToolName(raw);
			assertAll(
					() -> assertEquals(expectedServer, parts[0]),
					() -> assertEquals(expectedTool, parts[1]));
		}

		@Test
		@DisplayName("dot takes precedence over underscore when both present")
		void dotWinsOverUnderscore() {
			String[] parts = McpToolCall.splitToolName("server_name.tool_name");
			assertAll(
					() -> assertEquals("server_name", parts[0]),
					() -> assertEquals("tool_name", parts[1]));
		}

		@ParameterizedTest
		@ValueSource(strings = { "createvaluelist", "tool" })
		@DisplayName("plain name with no separator yields null server and full tool")
		void noSeparator(String raw) {
			String[] parts = McpToolCall.splitToolName(raw);
			assertAll(
					() -> assertNull(parts[0]),
					() -> assertEquals(raw, parts[1]));
		}

		@Test
		@DisplayName("null raw name yields a two-element array of nulls")
		void nullRawName() {
			String[] parts = McpToolCall.splitToolName(null);
			assertAll(
					() -> assertEquals(2, parts.length),
					() -> assertNull(parts[0]),
					() -> assertNull(parts[1]));
		}

		@Test
		@DisplayName("leading separator is not treated as a split point")
		void leadingSeparatorIgnored() {
			String[] underscore = McpToolCall.splitToolName("_tool");
			String[] dot = McpToolCall.splitToolName(".tool");
			assertAll(
					() -> assertNull(underscore[0]),
					() -> assertEquals("_tool", underscore[1]),
					() -> assertNull(dot[0]),
					() -> assertEquals(".tool", dot[1]));
		}
	}

	@Nested
	class FromRawName {

		@Test
		@DisplayName("derives server and tool and preserves arguments and output")
		void derivesFields() {
			Map<String, Object> args = Map.of("name", "colors");
			McpToolCall call = McpToolCall.fromRawName("servoy-coder_create_valuelist", args, "done");
			assertAll(
					() -> assertEquals("servoy-coder", call.server()),
					() -> assertEquals("create_valuelist", call.tool()),
					() -> assertEquals("colors", call.arguments().get("name")),
					() -> assertEquals("done", call.output()));
		}

		@ParameterizedTest
		@NullAndEmptySource
		@DisplayName("null/empty arguments become a non-null empty map")
		void nullArgumentsBecomeEmptyMap(Map<String, Object> args) {
			McpToolCall call = McpToolCall.fromRawName("tool", args, null);
			assertAll(
					() -> assertTrue(call.arguments().isEmpty()),
					() -> assertNull(call.output()));
		}

		@Test
		@DisplayName("arguments map is unmodifiable")
		void argumentsUnmodifiable() {
			McpToolCall call = McpToolCall.fromRawName("tool", Collections.emptyMap(), null);
			assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("x", "y"));
		}
	}

	@Nested
	class MatchesIdentity {

		@Test
		@DisplayName("same tool with server on both sides matches case-insensitively")
		void serverAndToolMatch() {
			McpToolCall call = new McpToolCall("servoy-coder", "create_valuelist", Map.of(), null);
			assertTrue(call.matchesIdentity("SERVOY-CODER", "CREATE_VALUELIST"));
		}

		@Test
		@DisplayName("server comparison is skipped when either side is null")
		void serverSkippedWhenNull() {
			McpToolCall call = new McpToolCall(null, "create_valuelist", Map.of(), null);
			assertAll(
					() -> assertTrue(call.matchesIdentity("servoy-coder", "create_valuelist")),
					() -> assertTrue(
							new McpToolCall("servoy-coder", "create_valuelist", Map.of(), null)
									.matchesIdentity(null, "create_valuelist")));
		}

		@Test
		@DisplayName("different servers on the same tool do not match")
		void differentServers() {
			McpToolCall call = new McpToolCall("servoy-coder", "create_valuelist", Map.of(), null);
			assertFalse(call.matchesIdentity("servoy-ide", "create_valuelist"));
		}

		@Test
		@DisplayName("different tools never match")
		void differentTools() {
			McpToolCall call = new McpToolCall("servoy-coder", "create_valuelist", Map.of(), null);
			assertFalse(call.matchesIdentity("servoy-coder", "delete_form"));
		}

		@Test
		@DisplayName("null tool on either side never matches")
		void nullTool() {
			McpToolCall call = new McpToolCall("servoy-coder", null, Map.of(), null);
			assertAll(
					() -> assertFalse(call.matchesIdentity("servoy-coder", "create_valuelist")),
					() -> assertFalse(new McpToolCall("servoy-coder", "create_valuelist", Map.of(), null)
							.matchesIdentity("servoy-coder", null)));
		}
	}
}

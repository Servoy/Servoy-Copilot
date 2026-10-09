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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TranscriptComparator} — the core "same result" logic
 * (spec §3.4): tool-set presence, tolerant/overridden argument equivalence,
 * forbidden tools, strict mode, and ordered mode.
 */
class TranscriptComparatorTest {

	private static McpToolCall call(String server, String tool, Map<String, Object> args) {
		return new McpToolCall(server, tool, args, null);
	}

	private static SessionTranscript transcript(McpToolCall... calls) {
		return new SessionTranscript("prompt", List.of(calls));
	}

	private static Baseline baseline(Baseline.Compare compare, McpToolCall... goldenCalls) {
		return new Baseline("id", "title", true, null, null, null, 3, 180, null, (Baseline.Source) null,
				(java.util.List<String>) null, (Baseline.Fixture) null, compare,
				(java.util.List<com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion>) null,
				(Baseline.JsUnitVerify) null, transcript(goldenCalls));
	}

	@Nested
	class ToolSetPresence {

		@Test
		@DisplayName("baseline tool present in actual passes")
		void present() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist", Map.of("name", "colors"))));
			assertAll(
					() -> assertTrue(r.match()),
					() -> assertTrue(r.diff().isEmpty()));
		}

		@Test
		@DisplayName("missing baseline tool fails with a readable diff")
		void missing() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-ide", "create_form", Map.of("name", "orders"))));
			assertAll(
					() -> assertFalse(r.match()),
					() -> assertTrue(r.diff().contains("create_valuelist"),
							"diff should name the missing tool"));
		}
	}

	@Nested
	class ArgumentEquivalence {

		@Test
		@DisplayName("tolerant defaults ignore case and whitespace on scalar args")
		void tolerantDefaults() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "Colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist", Map.of("name", "colors "))));
			assertTrue(r.match());
		}

		@Test
		@DisplayName("tolerant list args compare as order-independent sets")
		void tolerantListArgs() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("one", "two", "three"))));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("three", "one", "two")))));
			assertTrue(r.match());
		}

		@Test
		@DisplayName("a differing constrained argument fails")
		void differingArgFails() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist", Map.of("name", "shapes"))));
			assertFalse(r.match());
		}

		@Test
		@DisplayName("per-arg containsAll override enforces required elements")
		void containsAllOverride() {
			Baseline.Compare compare = new Baseline.Compare(false, false, List.of(),
					Map.of("servoy-coder.create_valuelist",
							Map.of("values", ArgMatcher.containsAll(List.of("one", "two", "three")))));
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("one", "two", "three"))));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("one", "two", "three", "four")))));
			assertTrue(r.match(), "extra element still satisfies containsAll");
		}

		@Test
		@DisplayName("containsAll override fails when a required element is absent")
		void containsAllOverrideMissing() {
			Baseline.Compare compare = new Baseline.Compare(false, false, List.of(),
					Map.of("servoy-coder.create_valuelist",
							Map.of("values", ArgMatcher.containsAll(List.of("one", "two", "three")))));
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("one", "two", "three"))));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist",
							Map.of("values", List.of("one", "two")))));
			assertFalse(r.match());
		}
	}

	@Nested
	class Forbidden {

		@Test
		@DisplayName("a forbidden tool present in the actual run fails")
		void forbiddenPresent() {
			Baseline.Compare compare = new Baseline.Compare(false, false,
					List.of(new Baseline.ToolRef("servoy-ide", "delete_form")), Map.of());
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-ide", "delete_form", Map.of("name", "orders"))));
			assertAll(
					() -> assertFalse(r.match()),
					() -> assertTrue(r.diff().contains("forbidden")));
		}

		@Test
		@DisplayName("a forbidden tool absent from the actual run does not fail on that account")
		void forbiddenAbsent() {
			Baseline.Compare compare = new Baseline.Compare(false, false,
					List.of(new Baseline.ToolRef("servoy-ide", "delete_form")), Map.of());
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(
					transcript(call("servoy-coder", "create_valuelist", Map.of("name", "colors"))));
			assertTrue(r.match());
		}
	}

	@Nested
	class StrictMode {

		@Test
		@DisplayName("strict:false allows extra actual tools")
		void nonStrictAllowsExtras() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders"))));
			assertTrue(r.match());
		}

		@Test
		@DisplayName("strict:true fails on any actual tool not in the baseline")
		void strictFailsOnExtras() {
			Baseline.Compare compare = new Baseline.Compare(false, true, List.of(), Map.of());
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders"))));
			assertAll(
					() -> assertFalse(r.match()),
					() -> assertTrue(r.diff().contains("strict")));
		}
	}

	@Nested
	class OrderedMode {

		@Test
		@DisplayName("unordered (default) accepts distinct tools in any order")
		void unorderedAllowsReorder() {
			Baseline b = baseline(Baseline.Compare.defaults(),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_form", Map.of("name", "orders")),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors"))));
			assertTrue(r.match());
		}

		@Test
		@DisplayName("ordered:true passes when distinct tools appear in baseline order")
		void orderedInOrderPasses() {
			Baseline.Compare compare = new Baseline.Compare(true, false, List.of(), Map.of());
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders"))));
			assertTrue(r.match());
		}

		@Test
		@DisplayName("ordered:true fails when distinct tools appear out of baseline order")
		void orderedOutOfOrderFails() {
			Baseline.Compare compare = new Baseline.Compare(true, false, List.of(), Map.of());
			Baseline b = baseline(compare,
					call("servoy-coder", "create_valuelist", Map.of("name", "colors")),
					call("servoy-coder", "create_form", Map.of("name", "orders")));
			TranscriptComparator.Result r = new TranscriptComparator(b).compare(transcript(
					call("servoy-coder", "create_form", Map.of("name", "orders")),
					call("servoy-coder", "create_valuelist", Map.of("name", "colors"))));
			assertAll(
					() -> assertFalse(r.match()),
					() -> assertTrue(r.diff().contains("order")));
		}
	}
}

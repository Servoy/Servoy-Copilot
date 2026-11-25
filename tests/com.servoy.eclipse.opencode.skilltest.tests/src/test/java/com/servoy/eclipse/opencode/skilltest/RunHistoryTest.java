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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.RunHistory.Entry;
import com.servoy.eclipse.opencode.skilltest.RunHistory.RunIdentity;
import com.servoy.eclipse.opencode.skilltest.RunMetrics.KiroUsage;
import com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage;

/**
 * Unit tests for the {@link RunHistory} writer/reader and {@link RunIdentity}
 * (SVY-21661 §3.1-3.3): line shape, omission of unknown values, no secrets,
 * append semantics incl. recovery from a truncated last line, tolerant reading
 * and the never-throwing failure path.
 */
class RunHistoryTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Instant TS = Instant.parse("2026-11-24T03:12:45.123Z");

	private static RunIdentity ciIdentity() {
		return new RunIdentity(RunHistory.SOURCE_CI, TS, "412", "servoy-copilot-skilltest", "ab12cd3",
				"2026.3.1.v20261124", "9f8e7d6");
	}

	private static RunIdentity bareIdentity() {
		return new RunIdentity(RunHistory.SOURCE_IDE, TS, null, null, null, null, null);
	}

	private static SkillTestResult fullResult(String id) {
		SkillTestResult.Trial t1 = new SkillTestResult.Trial(1, true, "", null, null, null, null, null);
		SkillTestResult.Trial t2 = new SkillTestResult.Trial(2, false, "x", null, null, null, null, null);
		return SkillTestResult.pass(id, 1).withAgent("Orchestrator").withTrials(List.of(t1, t2))
				.withMetrics(new RunMetrics(14, new TokenUsage(81234, 2210, 5, 7, 9), new KiroUsage(6, 1.4d),
						Double.valueOf(1.3d), List.of()));
	}

	private static List<String> lines(File f) throws Exception {
		return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
	}

	@Nested
	class LineShape {

		@Test
		@DisplayName("toJsonLine writes required fields, identity, trials, agent, model and the compact metrics subset")
		void fullLine() throws Exception {
			String line = RunHistory.toJsonLine(fullResult("create-form-basic"), ciIdentity(), "run-1", "kiro/auto");
			JsonNode n = MAPPER.readTree(line);
			assertAll(() -> assertFalse(line.contains("\n"), "single line"),
					() -> assertEquals(RunHistory.SCHEMA_VERSION, n.get("v").asInt()),
					() -> assertEquals("run-1", n.get("runId").asText()),
					() -> assertEquals(TS.toString(), n.get("timestamp").asText()),
					() -> assertEquals("ci", n.get("source").asText()),
					() -> assertEquals("412", n.get("buildNumber").asText()),
					() -> assertEquals("servoy-copilot-skilltest", n.get("job").asText()),
					() -> assertEquals("ab12cd3", n.get("commit").asText()),
					() -> assertEquals("2026.3.1.v20261124", n.get("pluginVersion").asText()),
					() -> assertEquals("9f8e7d6", n.get("skillsVersion").asText()),
					() -> assertEquals("create-form-basic", n.get("baselineId").asText()),
					() -> assertEquals("PASS", n.get("status").asText()),
					() -> assertEquals(1, n.get("attempts").asInt()),
					() -> assertEquals(1, n.get("trials").get("passed").asInt()),
					() -> assertEquals(2, n.get("trials").get("total").asInt()),
					() -> assertEquals("Orchestrator", n.get("agent").asText()),
					() -> assertEquals("kiro/auto", n.get("model").asText()),
					() -> assertEquals(14, n.get("metrics").get("toolCalls").asInt()),
					() -> assertEquals(81234, n.get("metrics").get("tokens").get("input").asLong()),
					() -> assertEquals(9, n.get("metrics").get("tokens").get("cacheWrite").asLong()),
					() -> assertEquals(6, n.get("metrics").get("kiro").get("requests").asInt()),
					() -> assertEquals(1.4d, n.get("metrics").get("kiro").get("estCredits").asDouble(), 1e-9),
					() -> assertFalse(n.get("metrics").has("creditsDelta"),
							"the account Δ is information only, not recorded in the history"),
					() -> assertFalse(n.get("metrics").has("requestTokens"), "requestTokens left out"));
		}

		@Test
		@DisplayName("unknown values are omitted, never written as null or 0")
		void unknownOmitted() throws Exception {
			String line = RunHistory.toJsonLine(SkillTestResult.skipped("b1", "why"), bareIdentity(), "run-1", null);
			JsonNode n = MAPPER.readTree(line);
			assertAll(() -> assertFalse(line.contains("null"), "no null literal"),
					() -> assertFalse(n.has("buildNumber")), () -> assertFalse(n.has("job")),
					() -> assertFalse(n.has("commit")), () -> assertFalse(n.has("pluginVersion")),
					() -> assertFalse(n.has("skillsVersion")), () -> assertFalse(n.has("agent")),
					() -> assertFalse(n.has("model")), () -> assertFalse(n.has("trials"), "no trials -> omitted"),
					() -> assertFalse(n.has("metrics"), "no metrics -> omitted"),
					() -> assertFalse(n.has("attempts"), "0 attempts -> omitted"),
					() -> assertEquals("SKIPPED", n.get("status").asText()));
		}

		@Test
		@DisplayName("no prompt, diff, tool arguments/outputs, JSUnit report or key ends up in the line")
		void noSecrets() {
			McpToolCall call = new McpToolCall("servoy-coder", "create_form", Map.of("apiKey", "SECRET_ARG_VALUE"),
					"SECRET_TOOL_OUTPUT");
			SkillTestResult r = SkillTestResult.fail("b1", 1, "SECRET_DIFF")
					.withTranscript("SECRET_PROMPT", List.of(call), List.of(call))
					.withJsUnit("SECRET_JSUNIT_REPORT", Boolean.FALSE)
					.withMetrics(new RunMetrics(3, null, null, null, List.of(new RunMetrics.RequestTokens(0,
							new TokenUsage(1, 1, 0, 0, 0), List.of("SECRET_TOOL_NAME"), "SECRET_SKILL"))));
			String line = RunHistory.toJsonLine(r, ciIdentity(), "run-1", "kiro/auto");
			for (String secret : List.of("SECRET_ARG_VALUE", "apiKey", "SECRET_TOOL_OUTPUT", "SECRET_DIFF",
					"SECRET_PROMPT", "SECRET_JSUNIT_REPORT", "SECRET_TOOL_NAME", "SECRET_SKILL")) {
				assertFalse(line.contains(secret), "line must not carry " + secret + ": " + line);
			}
		}
	}

	@Nested
	class Append {

		@Test
		@DisplayName("one line per result, all sharing the run id, and the file is created with its parents")
		void oneLinePerResult(@TempDir Path dir) throws Exception {
			File f = dir.resolve("deep/nested/run-history.jsonl").toFile();
			boolean ok = RunHistory.append(f, List.of(fullResult("a"), SkillTestResult.fail("b", 2, "d")), ciIdentity(),
					id -> "a".equals(id) ? "kiro/a" : null, null);
			List<String> lines = lines(f);
			JsonNode first = MAPPER.readTree(lines.get(0));
			JsonNode second = MAPPER.readTree(lines.get(1));
			assertAll(() -> assertTrue(ok), () -> assertEquals(2, lines.size()),
					() -> assertEquals(RunHistory.runId(ciIdentity()), first.get("runId").asText()),
					() -> assertEquals(first.get("runId").asText(), second.get("runId").asText(), "shared runId"),
					() -> assertEquals("kiro/a", first.get("model").asText()),
					() -> assertFalse(second.has("model"), "model function returning null omits the model"));
		}

		@Test
		@DisplayName("appending twice keeps both runs, in order")
		void appendTwice(@TempDir Path dir) {
			File f = dir.resolve("h.jsonl").toFile();
			RunIdentity first = new RunIdentity("ide", Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null,
					null);
			RunIdentity second = new RunIdentity("ide", Instant.parse("2026-01-02T00:00:00Z"), null, null, null, null,
					null);
			RunHistory.append(f, List.of(SkillTestResult.pass("b", 1)), first, null, null);
			RunHistory.append(f, List.of(SkillTestResult.fail("b", 1, "")), second, null, null);
			List<Entry> entries = RunHistory.read(f);
			assertAll(() -> assertEquals(2, entries.size()),
					() -> assertEquals(RunHistory.runId(first), entries.get(0).runId()),
					() -> assertEquals(SkillTestResult.Status.PASS, entries.get(0).status()),
					() -> assertEquals(RunHistory.runId(second), entries.get(1).runId()),
					() -> assertEquals(SkillTestResult.Status.FAIL, entries.get(1).status()));
		}

		@Test
		@DisplayName("after a truncated last line the new run starts on a fresh line and the fragment is skipped")
		void truncatedLastLine(@TempDir Path dir) throws Exception {
			File f = dir.resolve("h.jsonl").toFile();
			Files.writeString(f.toPath(), "{\"v\":1,\"runId\":\"cut", StandardCharsets.UTF_8);
			assertTrue(RunHistory.append(f, List.of(SkillTestResult.pass("b", 1)), ciIdentity(), null, null));
			List<String> lines = lines(f);
			List<Entry> entries = RunHistory.read(f);
			assertAll(() -> assertEquals(2, lines.size(), "fragment isolated on its own line"),
					() -> assertEquals("{\"v\":1,\"runId\":\"cut", lines.get(0)),
					() -> assertEquals(1, entries.size(), "fragment skipped, new line parsed"),
					() -> assertEquals("b", entries.get(0).baselineId()));
		}

		@Test
		@DisplayName("an unwritable target (a directory) returns false, logs, and does not throw")
		void unwritable(@TempDir Path dir) {
			List<String> log = new ArrayList<>();
			boolean[] ok = new boolean[1];
			assertDoesNotThrow(() -> ok[0] = RunHistory.append(dir.toFile(), List.of(SkillTestResult.pass("b", 1)),
					ciIdentity(), null, log::add));
			assertAll(() -> assertFalse(ok[0]), () -> assertFalse(log.isEmpty(), "failure logged"));
		}

		@Test
		@DisplayName("null or empty results are a no-op that returns true and creates no file")
		void nullOrEmptyResults(@TempDir Path dir) {
			File f = dir.resolve("h.jsonl").toFile();
			assertAll(() -> assertTrue(RunHistory.append(f, null, ciIdentity(), null, null)),
					() -> assertTrue(RunHistory.append(f, List.of(), ciIdentity(), null, null)),
					() -> assertFalse(f.exists(), "nothing written"));
		}

		@Test
		@DisplayName("a throwing model lookup does not drop the line (model omitted)")
		void throwingModelLookup(@TempDir Path dir) {
			File f = dir.resolve("h.jsonl").toFile();
			assertTrue(RunHistory.append(f, List.of(SkillTestResult.pass("b", 1)), ciIdentity(), id -> {
				throw new IllegalStateException("boom");
			}, null));
			List<Entry> entries = RunHistory.read(f);
			assertAll(() -> assertEquals(1, entries.size()), () -> assertNull(entries.get(0).model()));
		}
	}

	@Nested
	class Read {

		@Test
		@DisplayName("a missing file reads as an empty list")
		void missingFile(@TempDir Path dir) {
			assertAll(() -> assertTrue(RunHistory.read(dir.resolve("none.jsonl").toFile()).isEmpty()),
					() -> assertTrue(RunHistory.read(null).isEmpty()));
		}

		@Test
		@DisplayName("corrupt, blank, incomplete and unknown-status lines are skipped; valid lines survive")
		void skipsBadLines(@TempDir Path dir) throws Exception {
			File f = dir.resolve("h.jsonl").toFile();
			String good1 = RunHistory.toJsonLine(SkillTestResult.pass("b1", 1), ciIdentity(), "r1", null);
			String good2 = RunHistory.toJsonLine(SkillTestResult.fail("b2", 1, ""), ciIdentity(), "r1", null);
			String noBaseline = "{\"v\":1,\"runId\":\"r1\",\"timestamp\":\"" + TS
					+ "\",\"source\":\"ci\",\"status\":\"PASS\"}";
			String badStatus = "{\"v\":1,\"runId\":\"r1\",\"timestamp\":\"" + TS
					+ "\",\"source\":\"ci\",\"baselineId\":\"x\",\"status\":\"WEIRD\"}";
			String noVersion = "{\"runId\":\"r1\",\"timestamp\":\"" + TS
					+ "\",\"source\":\"ci\",\"baselineId\":\"x\",\"status\":\"PASS\"}";
			Files.writeString(f.toPath(),
					String.join("\n", good1, "not json {", "", noBaseline, badStatus, noVersion, good2) + "\n",
					StandardCharsets.UTF_8);
			List<Entry> entries = RunHistory.read(f);
			assertAll(() -> assertEquals(2, entries.size()), () -> assertEquals("b1", entries.get(0).baselineId()),
					() -> assertEquals("b2", entries.get(1).baselineId()));
		}

		@Test
		@DisplayName("a written line reads back with identity, trials and derived metric values")
		void roundTrip(@TempDir Path dir) {
			File f = dir.resolve("h.jsonl").toFile();
			RunHistory.append(f, List.of(fullResult("b")), ciIdentity(), id -> "kiro/auto", null);
			Entry e = RunHistory.read(f).get(0);
			assertAll(() -> assertEquals(TS, e.timestamp()), () -> assertEquals("ci", e.source()),
					() -> assertEquals("412", e.buildNumber()), () -> assertEquals("kiro/auto", e.model()),
					() -> assertEquals("Orchestrator", e.agent()),
					() -> assertEquals("2026.3.1.v20261124", e.pluginVersion()),
					() -> assertEquals("9f8e7d6", e.skillsVersion()),
					() -> assertEquals(Integer.valueOf(1), e.trialsPassed()),
					() -> assertEquals(Integer.valueOf(2), e.trialsTotal()),
					() -> assertEquals(Long.valueOf(81234 + 2210 + 5), e.totalTokens(),
							"total = input + output + reasoning"),
					() -> assertEquals(Integer.valueOf(14), e.toolCalls()),
					() -> assertEquals(1.4d, e.estCredits().doubleValue(), 1e-9),
					() -> assertNull(e.creditsDelta(), "the account Δ is not recorded"));
		}
	}

	@Nested
	class Identity {

		@Test
		@DisplayName("ci(env) takes BUILD_NUMBER, JOB_NAME and GIT_COMMIT from the injected env")
		void ciWithEnv() {
			Map<String, String> env = Map.of("BUILD_NUMBER", "412", "JOB_NAME", "job-x", "GIT_COMMIT", "abc");
			RunIdentity id = RunIdentity.ci(env::get);
			assertAll(() -> assertEquals("ci", id.source()), () -> assertEquals("412", id.buildNumber()),
					() -> assertEquals("job-x", id.job()), () -> assertEquals("abc", id.commit()),
					() -> assertNotNull(id.timestamp()),
					() -> assertTrue(RunHistory.runId(id).endsWith("-ci-412"), RunHistory.runId(id)));
		}

		@Test
		@DisplayName("ci(env) without Jenkins vars (or blank ones) leaves the build fields null")
		void ciWithoutEnv() {
			RunIdentity id = RunIdentity.ci(Map.of("BUILD_NUMBER", "  ")::get);
			assertAll(() -> assertNull(id.buildNumber()), () -> assertNull(id.job()), () -> assertNull(id.commit()),
					() -> assertTrue(RunHistory.runId(id).endsWith("-ci"), RunHistory.runId(id)));
		}

		@Test
		@DisplayName("ide(env) never carries build fields, even when the env has them")
		void ideHasNoBuildFields() {
			RunIdentity id = RunIdentity.ide(Map.of("BUILD_NUMBER", "412", "JOB_NAME", "j", "GIT_COMMIT", "c")::get);
			assertAll(() -> assertEquals("ide", id.source()), () -> assertNull(id.buildNumber()),
					() -> assertNull(id.job()), () -> assertNull(id.commit()),
					() -> assertTrue(RunHistory.runId(id).endsWith("-ide")));
		}

		@Test
		@DisplayName("runId is <timestamp>-<source>[-<buildNumber>]")
		void runIdFormat() {
			assertAll(() -> assertEquals("2026-11-24T03:12:45.123Z-ci-412", RunHistory.runId(ciIdentity())),
					() -> assertEquals("2026-11-24T03:12:45.123Z-ide", RunHistory.runId(bareIdentity())));
		}

		@Test
		@DisplayName("skills version: the system property beats the env var; blank is unset")
		void skillsVersionResolution() {
			assertAll(
					() -> assertEquals("prop",
							RunHistory.skillsVersion(Map.of(RunHistory.SKILLS_VERSION_PROPERTY, "prop")::get,
									Map.of(RunHistory.SKILLS_VERSION_ENV, "env")::get)),
					() -> assertEquals("env",
							RunHistory.skillsVersion(Map.of(RunHistory.SKILLS_VERSION_PROPERTY, " ")::get,
									Map.of(RunHistory.SKILLS_VERSION_ENV, "env")::get)),
					() -> assertNull(RunHistory.skillsVersion(k -> null, k -> null)));
		}
	}
}

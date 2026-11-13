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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.RunMetrics.KiroUsage;
import com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage;

/**
 * Round-trip tests for {@link SkillTestReporter}'s additive {@code metrics}
 * persistence (SVY-21660): metrics written by
 * {@link SkillTestReporter#writeLastResults} are read back by
 * {@link SkillTestReporter#loadLastResults} unchanged, the
 * {@code tokens}/{@code kiro} objects are omitted when unknown (so "unknown" is
 * distinguishable from a real zero), per-trial metrics round-trip, and an older
 * {@code last-results.json} written WITHOUT a {@code metrics} field still loads
 * with {@code getMetrics() == null}.
 * <p>
 * The reporter persists to a fixed path
 * ({@link SkillTestPaths#lastResultsFile()}); this test saves and restores any
 * pre-existing file around each case so a developer's real results are never
 * clobbered, and the whole suite stays headless ({@code plainjunit}).
 * </p>
 */
class SkillTestReporterMetricsTest {

	private File lastResults;
	private byte[] saved;

	@BeforeEach
	void saveExisting() {
		lastResults = SkillTestPaths.lastResultsFile();
		try {
			saved = lastResults.isFile() ? Files.readAllBytes(lastResults.toPath()) : null;
		} catch (Exception e) {
			saved = null;
		}
	}

	@AfterEach
	void restoreExisting() throws Exception {
		if (saved != null) {
			Files.write(lastResults.toPath(), saved);
		} else if (lastResults.isFile()) {
			Files.delete(lastResults.toPath());
		}
	}

	@Nested
	class RoundTrip {

		@Test
		@DisplayName("metrics present (toolCalls + tokens + kiro) survive a write/load round-trip")
		void fullMetricsRoundTrip() throws Exception {
			RunMetrics metrics = new RunMetrics(18, new TokenUsage(1234, 567, 8, 90, 12), new KiroUsage(3, 0.42d));
			SkillTestResult result = SkillTestResult.pass("full-metrics", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			Map<String, SkillTestResult> loaded = SkillTestReporter.loadLastResults();

			RunMetrics m = loaded.get("full-metrics").getMetrics();
			assertNotNull(m, "metrics restored");
			assertAll(() -> assertEquals(18, m.toolCalls()), () -> assertNotNull(m.tokens(), "tokens restored"),
					() -> assertEquals(1234L, m.tokens().input()), () -> assertEquals(567L, m.tokens().output()),
					() -> assertEquals(8L, m.tokens().reasoning()), () -> assertEquals(90L, m.tokens().cacheRead()),
					() -> assertEquals(12L, m.tokens().cacheWrite()), () -> assertNotNull(m.kiro(), "kiro restored"),
					() -> assertEquals(3, m.kiro().requests()),
					() -> assertEquals(0.42d, m.kiro().estCredits(), 0.0001d));
		}

		@Test
		@DisplayName("unknown tokens/kiro are omitted on write and read back as null (not zero)")
		void unknownOmitted() throws Exception {
			// toolCalls known; tokens and kiro unknown.
			RunMetrics metrics = new RunMetrics(7, null, null);
			SkillTestResult result = SkillTestResult.pass("partial-metrics", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			// The serialized form must not carry a tokens/kiro object for this result.
			String json = Files.readString(lastResults.toPath(), StandardCharsets.UTF_8);
			assertAll(() -> assertTrue(json.contains("\"toolCalls\" : 7"), "toolCalls written"),
					() -> assertTrue(!json.contains("\"tokens\""), "tokens object omitted when unknown"),
					() -> assertTrue(!json.contains("\"kiro\""), "kiro object omitted when unknown"));

			RunMetrics m = SkillTestReporter.loadLastResults().get("partial-metrics").getMetrics();
			assertAll(() -> assertNotNull(m, "metrics restored"), () -> assertEquals(7, m.toolCalls()),
					() -> assertNull(m.tokens(), "tokens null (unknown), not a zero TokenUsage"),
					() -> assertNull(m.kiro(), "kiro null (unknown), not a zero KiroUsage"));
		}

		@Test
		@DisplayName("per-trial metrics round-trip")
		void perTrialMetricsRoundTrip() throws Exception {
			SkillTestResult.Trial trial = new SkillTestResult.Trial(1, true, "", null, null, null, null,
					new RunMetrics(12, new TokenUsage(10, 20, 0, 0, 0), null));
			SkillTestResult result = SkillTestResult.pass("trial-metrics", 1)
					.withMetrics(new RunMetrics(12, null, null)).withTrials(List.of(trial));

			SkillTestReporter.writeLastResults(List.of(result));
			SkillTestResult loaded = SkillTestReporter.loadLastResults().get("trial-metrics");

			assertEquals(1, loaded.getTrials().size(), "one trial restored");
			RunMetrics tm = loaded.getTrials().get(0).metrics();
			assertAll(() -> assertNotNull(tm, "trial metrics restored"), () -> assertEquals(12, tm.toolCalls()),
					() -> assertNotNull(tm.tokens(), "trial tokens restored"),
					() -> assertEquals(30L, tm.tokens().total()));
		}

		@Test
		@DisplayName("creditsDelta and requestTokens[] survive a write/load round-trip (SVY-21660 B/C)")
		void creditsDeltaAndRequestTokensRoundTrip() throws Exception {
			RunMetrics metrics = new RunMetrics(18, null, null, Double.valueOf(0.73d),
					List.of(new RunMetrics.RequestTokens(0, new TokenUsage(474_000, 20, 0, 0, 0),
							List.of("servoy-coder.create_form", "execute"), "sdd"),
							new RunMetrics.RequestTokens(1, new TokenUsage(1000, 10, 0, 500, 0), List.of(), null)));
			SkillTestResult result = SkillTestResult.pass("expansion-metrics", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			RunMetrics m = SkillTestReporter.loadLastResults().get("expansion-metrics").getMetrics();

			assertNotNull(m, "metrics restored");
			assertEquals(2, m.requestTokens().size(), "both per-request entries restored");
			RunMetrics.RequestTokens r0 = m.requestTokens().get(0);
			RunMetrics.RequestTokens r1 = m.requestTokens().get(1);
			assertAll(() -> assertNotNull(m.creditsDelta(), "creditsDelta restored"),
					() -> assertEquals(0.73d, m.creditsDelta().doubleValue(), 0.0001d),
					() -> assertEquals(0, r0.index()),
					() -> assertEquals(474_000L, r0.tokens().input(), "per-request input restored"),
					() -> assertEquals(List.of("servoy-coder.create_form", "execute"), r0.toolNames(),
							"per-request tool names restored"),
					() -> assertEquals("sdd", r0.skill(), "per-request skill restored"),
					() -> assertEquals(500L, r1.tokens().cacheRead(), "nested cache restored"),
					() -> assertTrue(r1.toolNames().isEmpty(), "empty labels restored as empty"));
		}

		@Test
		@DisplayName("unknown creditsDelta/requestTokens are omitted and read back as null/empty")
		void expansionUnknownOmitted() throws Exception {
			// toolCalls known; creditsDelta and requestTokens unknown.
			RunMetrics metrics = new RunMetrics(7, null, null);
			SkillTestResult result = SkillTestResult.pass("expansion-unknown", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			String json = Files.readString(lastResults.toPath(), StandardCharsets.UTF_8);
			assertAll(() -> assertTrue(!json.contains("\"creditsDelta\""), "creditsDelta omitted when unknown"),
					() -> assertTrue(!json.contains("\"requestTokens\""), "requestTokens omitted when unknown"));

			RunMetrics m = SkillTestReporter.loadLastResults().get("expansion-unknown").getMetrics();
			assertAll(() -> assertNotNull(m), () -> assertNull(m.creditsDelta(), "creditsDelta null (unknown)"),
					() -> assertTrue(m.requestTokens().isEmpty(), "requestTokens empty (unknown)"));
		}

		@Test
		@DisplayName("creditsBefore/creditsAfter/creditsLimit survive a write/load round-trip (SVY-21660 live-data)")
		void creditsBracketRoundTrip() throws Exception {
			// The account-bracket readings (used before/after the scenario + the limit).
			RunMetrics metrics = new RunMetrics(18, null, null, Double.valueOf(0.73d), List.of(),
					Double.valueOf(100.5d), Double.valueOf(101.23d), Double.valueOf(2000.0d));
			SkillTestResult result = SkillTestResult.pass("bracket-metrics", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			RunMetrics m = SkillTestReporter.loadLastResults().get("bracket-metrics").getMetrics();

			assertNotNull(m, "metrics restored");
			assertAll(() -> assertNotNull(m.creditsBefore(), "creditsBefore restored"),
					() -> assertEquals(100.5d, m.creditsBefore().doubleValue(), 0.0001d),
					() -> assertNotNull(m.creditsAfter(), "creditsAfter restored"),
					() -> assertEquals(101.23d, m.creditsAfter().doubleValue(), 0.0001d),
					() -> assertNotNull(m.creditsLimit(), "creditsLimit restored"),
					() -> assertEquals(2000.0d, m.creditsLimit().doubleValue(), 0.0001d),
					() -> assertEquals(0.73d, m.creditsDelta().doubleValue(), 0.0001d, "the delta still round-trips"));
		}

		@Test
		@DisplayName("unknown creditsBefore/After/Limit are omitted and read back as null")
		void creditsBracketUnknownOmitted() throws Exception {
			// A five-arg metrics carries a delta but NO bracket readings.
			RunMetrics metrics = new RunMetrics(7, null, null, Double.valueOf(0.1d), List.of());
			SkillTestResult result = SkillTestResult.pass("bracket-unknown", 1).withMetrics(metrics);

			SkillTestReporter.writeLastResults(List.of(result));
			String json = Files.readString(lastResults.toPath(), StandardCharsets.UTF_8);
			assertAll(() -> assertTrue(!json.contains("\"creditsBefore\""), "creditsBefore omitted when unknown"),
					() -> assertTrue(!json.contains("\"creditsAfter\""), "creditsAfter omitted when unknown"),
					() -> assertTrue(!json.contains("\"creditsLimit\""), "creditsLimit omitted when unknown"));

			RunMetrics m = SkillTestReporter.loadLastResults().get("bracket-unknown").getMetrics();
			assertAll(() -> assertNotNull(m), () -> assertNull(m.creditsBefore(), "creditsBefore null (unknown)"),
					() -> assertNull(m.creditsAfter(), "creditsAfter null (unknown)"),
					() -> assertNull(m.creditsLimit(), "creditsLimit null (unknown)"));
		}
	}

	@Nested
	class BackwardCompatibility {

		@Test
		@DisplayName("an old last-results.json WITHOUT a metrics field loads with getMetrics() == null")
		void oldFileWithoutMetrics() throws Exception {
			// Simulate a file written by the pre-SVY-21660 reporter: no "metrics" field.
			String oldJson = "{\n" + "  \"generatedAt\" : \"2025-01-01T00:00:00\",\n" + "  \"results\" : [ {\n"
					+ "    \"baselineId\" : \"legacy\",\n" + "    \"status\" : \"PASS\",\n" + "    \"attempts\" : 1\n"
					+ "  } ]\n" + "}";
			Files.writeString(lastResults.toPath(), oldJson, StandardCharsets.UTF_8);

			Map<String, SkillTestResult> loaded = SkillTestReporter.loadLastResults();
			SkillTestResult legacy = loaded.get("legacy");
			assertAll(() -> assertNotNull(legacy, "legacy result still loads"),
					() -> assertEquals(SkillTestResult.Status.PASS, legacy.getStatus()),
					() -> assertNull(legacy.getMetrics(), "no metrics field -> getMetrics() null (unknown)"));
		}

		@Test
		@DisplayName("an old metrics node WITHOUT the credits bracket keys loads with before/after/limit null")
		void oldMetricsWithoutCreditsBracket() throws Exception {
			// Simulate a last-results.json written before the live-data follow-up: a
			// metrics node that has toolCalls but none of creditsBefore/After/Limit.
			String oldJson = "{\n" + "  \"generatedAt\" : \"2025-06-01T00:00:00\",\n" + "  \"results\" : [ {\n"
					+ "    \"baselineId\" : \"pre-bracket\",\n" + "    \"status\" : \"PASS\",\n"
					+ "    \"attempts\" : 1,\n" + "    \"metrics\" : {\n" + "      \"toolCalls\" : 9\n" + "    }\n"
					+ "  } ]\n" + "}";
			Files.writeString(lastResults.toPath(), oldJson, StandardCharsets.UTF_8);

			RunMetrics m = SkillTestReporter.loadLastResults().get("pre-bracket").getMetrics();
			assertAll(() -> assertNotNull(m, "the metrics node still loads"),
					() -> assertEquals(9, m.toolCalls(), "toolCalls read"),
					() -> assertNull(m.creditsBefore(), "absent creditsBefore -> null (unknown)"),
					() -> assertNull(m.creditsAfter(), "absent creditsAfter -> null (unknown)"),
					() -> assertNull(m.creditsLimit(), "absent creditsLimit -> null (unknown)"));
		}
	}
}

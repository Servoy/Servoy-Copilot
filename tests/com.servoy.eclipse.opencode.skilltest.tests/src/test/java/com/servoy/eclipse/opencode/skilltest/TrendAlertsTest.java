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
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.RunHistory.Entry;
import com.servoy.eclipse.opencode.skilltest.SkillTestResult.Status;
import com.servoy.eclipse.opencode.skilltest.TrendAlerts.Alert;
import com.servoy.eclipse.opencode.skilltest.TrendAlerts.AlertConfig;

/**
 * Unit tests for {@link TrendAlerts} (SVY-21661 §3.6a): each rule at its
 * default threshold and just below it, budget-driven COST_RISE with the median
 * fallback, scoping to the current run, likely cause, config overrides and the
 * {@code alerts.json} writer.
 */
class TrendAlertsTest {

	private static final String B = "base";

	/** Fluent builder for history entries; each entry is its own run. */
	private final class E {
		private final Status status;
		private String baseline = B;
		private String model = "kiro/a";
		private String skills = "s1";
		private String plugin = "p1";
		private Long tokens;
		private Integer toolCalls;
		private Double estCredits;
		private Double creditsDelta;

		E(Status status) {
			this.status = status;
		}

		E baseline(String b) {
			baseline = b;
			return this;
		}

		E model(String m) {
			model = m;
			return this;
		}

		E skills(String s) {
			skills = s;
			return this;
		}

		E plugin(String p) {
			plugin = p;
			return this;
		}

		E tokens(long t) {
			tokens = Long.valueOf(t);
			return this;
		}

		E toolCalls(int t) {
			toolCalls = Integer.valueOf(t);
			return this;
		}

		E estCredits(double c) {
			estCredits = Double.valueOf(c);
			return this;
		}

		E creditsDelta(double c) {
			creditsDelta = Double.valueOf(c);
			return this;
		}

		Entry build() {
			int i = seq++;
			return new Entry("run-" + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 60L), "ci",
					Integer.toString(i), baseline, status, null, null, null, model, plugin, skills, tokens, toolCalls,
					creditsDelta, estCredits);
		}
	}

	private int seq;

	private E e(Status s) {
		return new E(s);
	}

	private List<Entry> runs(Status... statuses) {
		List<Entry> list = new ArrayList<>();
		for (Status s : statuses) {
			list.add(e(s).build());
		}
		return list;
	}

	private static String lastRunId(List<Entry> h) {
		return h.get(h.size() - 1).runId();
	}

	private static List<Alert> eval(List<Entry> h, Map<String, List<Baseline.Budget>> budgets, AlertConfig cfg) {
		return TrendAlerts.evaluate(h, lastRunId(h), budgets, cfg);
	}

	private static List<Alert> eval(List<Entry> h) {
		return eval(h, null, null);
	}

	private static List<Alert> rule(List<Alert> alerts, String rule) {
		return alerts.stream().filter(a -> rule.equals(a.rule())).collect(Collectors.toList());
	}

	private static Map<String, List<Baseline.Budget>> budget(String metric, Integer max, Integer baselineValue,
			Integer tolerancePct) {
		return Map.of(B, List.of(new Baseline.Budget(metric, max, baselineValue, tolerancePct)));
	}

	/** Two prior runs with only the given metric unset, plus the latest. */
	private List<Entry> withLatest(Entry latest) {
		List<Entry> h = new ArrayList<>();
		h.add(e(Status.PASS).build());
		h.add(latest);
		return h;
	}

	@Nested
	class Regression {

		@Test
		@DisplayName("fires when the latest run fails after 3 passing runs")
		void firesAfterThreePasses() {
			List<Alert> a = rule(eval(runs(Status.PASS, Status.PASS, Status.PASS, Status.FAIL)),
					TrendAlerts.RULE_REGRESSION);
			assertAll(() -> assertEquals(1, a.size()), () -> assertEquals(B, a.get(0).baselineId()));
		}

		@Test
		@DisplayName("an ERROR after 3 passes is also a regression")
		void errorCounts() {
			assertEquals(1,
					rule(eval(runs(Status.PASS, Status.PASS, Status.PASS, Status.ERROR)), TrendAlerts.RULE_REGRESSION)
							.size());
		}

		@Test
		@DisplayName("does not fire after only 2 passing runs")
		void notAfterTwoPasses() {
			assertTrue(rule(eval(runs(Status.PASS, Status.PASS, Status.FAIL)), TrendAlerts.RULE_REGRESSION).isEmpty());
			assertTrue(rule(eval(runs(Status.FAIL, Status.PASS, Status.PASS, Status.FAIL)), TrendAlerts.RULE_REGRESSION)
					.isEmpty());
		}

		@Test
		@DisplayName("does not fire when the latest run passes")
		void notWhenLatestPasses() {
			assertTrue(rule(eval(runs(Status.PASS, Status.PASS, Status.PASS, Status.PASS)), TrendAlerts.RULE_REGRESSION)
					.isEmpty());
		}

		@Test
		@DisplayName("SKIPPED runs in between are ignored")
		void skippedIgnored() {
			assertEquals(1,
					rule(eval(runs(Status.PASS, Status.PASS, Status.SKIPPED, Status.PASS, Status.SKIPPED, Status.FAIL)),
							TrendAlerts.RULE_REGRESSION).size());
		}

		@Test
		@DisplayName("the regressionRuns override applies")
		void override() {
			AlertConfig cfg = AlertConfig.from(Map.of(AlertConfig.PROP_REGRESSION_RUNS, "2")::get);
			assertEquals(1,
					rule(eval(runs(Status.PASS, Status.PASS, Status.FAIL), null, cfg), TrendAlerts.RULE_REGRESSION)
							.size());
		}
	}

	@Nested
	class PassRateDrop {

		private List<Entry> twoWindows(int window, int lastPasses) {
			List<Entry> h = new ArrayList<>();
			for (int i = 0; i < window; i++) {
				h.add(e(Status.PASS).build());
			}
			// Put the failures first in the last window so the latest run passes (no
			// REGRESSION noise).
			for (int i = 0; i < window; i++) {
				h.add(e(i < window - lastPasses ? Status.FAIL : Status.PASS).build());
			}
			return h;
		}

		private AlertConfig window(int n) {
			return AlertConfig.from(Map.of(AlertConfig.PROP_WINDOW, Integer.toString(n))::get);
		}

		@Test
		@DisplayName("fires at a 20-point drop between two full default windows")
		void firesAtTwenty() {
			List<Alert> a = rule(eval(twoWindows(10, 8)), TrendAlerts.RULE_PASS_RATE_DROP);
			assertAll(() -> assertEquals(1, a.size()),
					() -> assertTrue(a.get(0).message().contains("80%"), a.get(0).message()),
					() -> assertTrue(a.get(0).message().contains("100%")));
		}

		@Test
		@DisplayName("fires at 20 points but not at 19 points (window 100)")
		void boundary() {
			assertAll(
					() -> assertEquals(1,
							rule(eval(twoWindows(100, 80), null, window(100)), TrendAlerts.RULE_PASS_RATE_DROP).size()),
					() -> assertTrue(rule(eval(twoWindows(100, 81), null, window(100)), TrendAlerts.RULE_PASS_RATE_DROP)
							.isEmpty()));
		}

		@Test
		@DisplayName("does not fire unless both windows are full")
		void needsTwoFullWindows() {
			List<Entry> h = twoWindows(10, 0);
			h.remove(0); // 19 entries: the earlier window is one short
			assertTrue(rule(eval(h), TrendAlerts.RULE_PASS_RATE_DROP).isEmpty());
		}

		@Test
		@DisplayName("the passRateDropPoints override applies")
		void override() {
			AlertConfig cfg = AlertConfig.from(Map.of(AlertConfig.PROP_PASS_RATE_DROP_POINTS, "21")::get);
			assertTrue(rule(eval(twoWindows(10, 8), null, cfg), TrendAlerts.RULE_PASS_RATE_DROP).isEmpty());
		}
	}

	@Nested
	class Flaky {

		@Test
		@DisplayName("fires at 3 pass/non-pass flips within the window")
		void firesAtThree() {
			List<Alert> a = rule(eval(runs(Status.PASS, Status.FAIL, Status.PASS, Status.FAIL)),
					TrendAlerts.RULE_FLAKY);
			assertAll(() -> assertEquals(1, a.size()), () -> assertTrue(a.get(0).message().startsWith("3 ")));
		}

		@Test
		@DisplayName("does not fire at 2 flips")
		void notAtTwo() {
			assertTrue(rule(eval(runs(Status.PASS, Status.FAIL, Status.PASS)), TrendAlerts.RULE_FLAKY).isEmpty());
		}

		@Test
		@DisplayName("flips older than the window are not counted")
		void windowed() {
			AlertConfig cfg = AlertConfig.from(Map.of(AlertConfig.PROP_WINDOW, "3")::get);
			// P F P F P P : last 3 = F P P -> 1 flip
			assertTrue(rule(
					eval(runs(Status.PASS, Status.FAIL, Status.PASS, Status.FAIL, Status.PASS, Status.PASS), null, cfg),
					TrendAlerts.RULE_FLAKY).isEmpty());
		}

		@Test
		@DisplayName("the flakyFlips override applies")
		void override() {
			AlertConfig cfg = AlertConfig.from(Map.of(AlertConfig.PROP_FLAKY_FLIPS, "2")::get);
			assertEquals(1,
					rule(eval(runs(Status.PASS, Status.FAIL, Status.PASS), null, cfg), TrendAlerts.RULE_FLAKY).size());
		}
	}

	@Nested
	class CostRiseBudget {

		@Test
		@DisplayName("tokens over a declared max fires, at the max does not")
		void tokensMax() {
			assertAll(
					() -> assertEquals(1,
							rule(eval(withLatest(e(Status.PASS).tokens(1001).build()),
									budget("tokens", 1000, null, null), null), TrendAlerts.RULE_COST_RISE).size()),
					() -> assertTrue(rule(eval(withLatest(e(Status.PASS).tokens(1000).build()),
							budget("tokens", 1000, null, null), null), TrendAlerts.RULE_COST_RISE).isEmpty()));
		}

		@Test
		@DisplayName("toolCalls over baselineValue + the budget's own tolerancePct fires; the budget tolerance beats the 30% default")
		void toolCallsOwnTolerance() {
			Map<String, List<Baseline.Budget>> b = budget("toolCalls", null, 10, 50);
			assertAll(
					() -> assertEquals(1,
							rule(eval(withLatest(e(Status.PASS).toolCalls(16).build()), b, null),
									TrendAlerts.RULE_COST_RISE).size()),
					() -> assertTrue(rule(eval(withLatest(e(Status.PASS).toolCalls(15).build()), b, null),
							TrendAlerts.RULE_COST_RISE).isEmpty()),
					// 14 would exceed 10 +30% (13) but stays within the declared 50%.
					() -> assertTrue(rule(eval(withLatest(e(Status.PASS).toolCalls(14).build()), b, null),
							TrendAlerts.RULE_COST_RISE).isEmpty()));
		}

		@Test
		@DisplayName("baselineValue without tolerancePct uses the 30% default: fires at +31%, not at +29%")
		void defaultTolerance() {
			Map<String, List<Baseline.Budget>> b = budget("tokens", null, 100, null);
			List<Alert> over = rule(eval(withLatest(e(Status.PASS).tokens(131).build()), b, null),
					TrendAlerts.RULE_COST_RISE);
			assertAll(() -> assertEquals(1, over.size()),
					() -> assertEquals("tokens 131 exceeds budget 100 +30% (+31%)", over.get(0).message()),
					() -> assertTrue(rule(eval(withLatest(e(Status.PASS).tokens(129).build()), b, null),
							TrendAlerts.RULE_COST_RISE).isEmpty()));
		}

		@Test
		@DisplayName("credits are judged on the rounded per-session estCredits, not creditsDelta")
		void creditsOnEstCredits() {
			Map<String, List<Baseline.Budget>> b = budget("credits", 5, null, null);
			assertAll(
					// 5.4 rounds to 5 (within), even though the account delta is huge.
					() -> assertTrue(
							rule(eval(withLatest(e(Status.PASS).estCredits(5.4).creditsDelta(50).build()), b, null),
									TrendAlerts.RULE_COST_RISE).isEmpty()),
					// 5.6 rounds to 6 (over), even though the account delta is 0.
					() -> assertEquals(1,
							rule(eval(withLatest(e(Status.PASS).estCredits(5.6).creditsDelta(0).build()), b, null),
									TrendAlerts.RULE_COST_RISE).size()));
		}

		@Test
		@DisplayName("a metric absent in the latest entry is skipped, never alerted")
		void absentMetricSkipped() {
			assertTrue(
					rule(eval(withLatest(e(Status.PASS).toolCalls(3).build()), budget("tokens", 1, null, null), null),
							TrendAlerts.RULE_COST_RISE).isEmpty());
		}

		@Test
		@DisplayName("the default-percent override applies only where the budget sets no tolerancePct")
		void defaultOverride() {
			AlertConfig cfg = AlertConfig.from(Map.of(AlertConfig.PROP_COST_RISE_DEFAULT_PERCENT, "10")::get);
			assertAll(
					() -> assertEquals(1,
							rule(eval(withLatest(e(Status.PASS).tokens(111).build()), budget("tokens", null, 100, null),
									cfg), TrendAlerts.RULE_COST_RISE).size()),
					() -> assertTrue(rule(eval(withLatest(e(Status.PASS).tokens(111).build()),
							budget("tokens", null, 100, Integer.valueOf(50)), cfg), TrendAlerts.RULE_COST_RISE)
							.isEmpty()));
		}

		@Test
		@DisplayName("each metric is judged separately against its own budget")
		void perMetric() {
			Map<String, List<Baseline.Budget>> b = Map.of(B, List.of(new Baseline.Budget("tokens", 100, null, null),
					new Baseline.Budget("toolCalls", 10, null, null), new Baseline.Budget("credits", 2, null, null)));
			List<Alert> a = rule(
					eval(withLatest(e(Status.PASS).tokens(101).toolCalls(11).estCredits(3).build()), b, null),
					TrendAlerts.RULE_COST_RISE);
			assertAll(() -> assertEquals(3, a.size()),
					() -> assertTrue(a.stream().anyMatch(x -> x.message().startsWith("tokens "))),
					() -> assertTrue(a.stream().anyMatch(x -> x.message().startsWith("toolCalls "))),
					() -> assertTrue(a.stream().anyMatch(x -> x.message().startsWith("credits "))));
		}
	}

	@Nested
	class CostRiseMedian {

		private List<Entry> priorTokens(long... values) {
			List<Entry> h = new ArrayList<>();
			for (long v : values) {
				h.add(e(Status.PASS).tokens(v).build());
			}
			return h;
		}

		@Test
		@DisplayName("no budget: fires at 30% above the median of the prior values, not at 29%")
		void medianBoundary() {
			List<Entry> fires = priorTokens(100, 100, 100);
			fires.add(e(Status.PASS).tokens(130).build());
			List<Entry> quiet = priorTokens(100, 100, 100);
			quiet.add(e(Status.PASS).tokens(129).build());
			List<Alert> a = rule(eval(fires), TrendAlerts.RULE_COST_RISE);
			assertAll(() -> assertEquals(1, a.size()),
					() -> assertEquals("tokens 130 is 30% above the median of the last 3 runs (100)",
							a.get(0).message()),
					() -> assertTrue(rule(eval(quiet), TrendAlerts.RULE_COST_RISE).isEmpty()));
		}

		@Test
		@DisplayName("no budget: needs at least 3 prior values")
		void needsThreePrior() {
			List<Entry> h = priorTokens(100, 100);
			h.add(e(Status.PASS).tokens(10_000).build());
			assertTrue(rule(eval(h), TrendAlerts.RULE_COST_RISE).isEmpty());
		}

		@Test
		@DisplayName("a declared budget for the metric replaces the median judgement")
		void budgetBeatsMedian() {
			List<Entry> h = priorTokens(100, 100, 100);
			h.add(e(Status.PASS).tokens(200).build());
			assertTrue(rule(eval(h, budget("tokens", 500, null, null), null), TrendAlerts.RULE_COST_RISE).isEmpty());
		}

		@Test
		@DisplayName("the median uses the middle of an even number of values")
		void evenMedian() {
			assertEquals(15d, TrendAlerts.median(List.of(Long.valueOf(20), Long.valueOf(10))), 1e-9);
		}
	}

	@Nested
	class Scope {

		@Test
		@DisplayName("a baseline without an entry in the current run never alerts")
		void notInCurrentRun() {
			List<Entry> h = runs(Status.PASS, Status.PASS, Status.PASS, Status.FAIL);
			h.add(e(Status.PASS).baseline("other").build());
			assertTrue(eval(h).stream().noneMatch(a -> B.equals(a.baselineId())));
		}

		@Test
		@DisplayName("a SKIPPED current entry never alerts")
		void skippedCurrent() {
			assertTrue(eval(runs(Status.PASS, Status.PASS, Status.PASS, Status.FAIL, Status.SKIPPED)).isEmpty());
		}

		@Test
		@DisplayName("null/empty history or run id yields no alerts")
		void degenerate() {
			assertAll(() -> assertTrue(TrendAlerts.evaluate(null, "r", null, null).isEmpty()),
					() -> assertTrue(TrendAlerts.evaluate(List.of(), "r", null, null).isEmpty()),
					() -> assertTrue(TrendAlerts.evaluate(runs(Status.FAIL), null, null, null).isEmpty()));
		}
	}

	@Nested
	class LikelyCause {

		private List<Entry> regressionWith(E latest) {
			List<Entry> h = runs(Status.PASS, Status.PASS, Status.PASS);
			h.add(latest.build());
			return h;
		}

		@Test
		@DisplayName("names a model change")
		void modelChange() {
			Alert a = rule(eval(regressionWith(e(Status.FAIL).model("kiro/b"))), TrendAlerts.RULE_REGRESSION).get(0);
			assertEquals("model changed kiro/a → kiro/b", a.likelyCause());
		}

		@Test
		@DisplayName("names a skillsVersion change")
		void skillsVersionChange() {
			Alert a = rule(eval(regressionWith(e(Status.FAIL).skills("s2"))), TrendAlerts.RULE_REGRESSION).get(0);
			assertEquals("skillsVersion changed s1 → s2", a.likelyCause());
		}

		@Test
		@DisplayName("never names a pluginVersion change (it changes on every build)")
		void pluginVersionIgnored() {
			assertNull(rule(eval(regressionWith(e(Status.FAIL).plugin("p2"))), TrendAlerts.RULE_REGRESSION).get(0)
					.likelyCause());
		}

		@Test
		@DisplayName("is absent when nothing changed")
		void noChange() {
			assertNull(rule(eval(regressionWith(e(Status.FAIL))), TrendAlerts.RULE_REGRESSION).get(0).likelyCause());
		}
	}

	@Nested
	class Config {

		@Test
		@DisplayName("defaults are 10 runs / 3 runs / 20 points / 3 flips / 30%")
		void defaults() {
			assertEquals(new AlertConfig(10, 3, 20, 3, 30), AlertConfig.from(k -> null));
		}

		@Test
		@DisplayName("valid overrides are applied")
		void overrides() {
			Map<String, String> p = Map.of(AlertConfig.PROP_WINDOW, "5", AlertConfig.PROP_REGRESSION_RUNS, " 4 ",
					AlertConfig.PROP_PASS_RATE_DROP_POINTS, "0", AlertConfig.PROP_FLAKY_FLIPS, "6",
					AlertConfig.PROP_COST_RISE_DEFAULT_PERCENT, "50");
			assertEquals(new AlertConfig(5, 4, 0, 6, 50), AlertConfig.from(p::get));
		}

		@Test
		@DisplayName("blank, non-numeric and out-of-range overrides fall back to the defaults without throwing")
		void invalidOverrides() {
			Map<String, String> p = Map.of(AlertConfig.PROP_WINDOW, "0", AlertConfig.PROP_REGRESSION_RUNS, "abc",
					AlertConfig.PROP_PASS_RATE_DROP_POINTS, "-1", AlertConfig.PROP_FLAKY_FLIPS, " ",
					AlertConfig.PROP_COST_RISE_DEFAULT_PERCENT, "1.5");
			assertAll(() -> assertEquals(AlertConfig.defaults(), AlertConfig.from(p::get)),
					() -> assertEquals(AlertConfig.defaults(), AlertConfig.from(null)),
					() -> assertEquals(AlertConfig.defaults(), AlertConfig.from(k -> {
						throw new IllegalStateException();
					})));
		}
	}

	@Nested
	class AlertsJson {

		private final ObjectMapper mapper = new ObjectMapper();

		@Test
		@DisplayName("a clean run still writes count:0 and an empty alerts array")
		void clean(@TempDir Path dir) throws Exception {
			File f = dir.resolve("out/alerts.json").toFile();
			assertTrue(TrendAlerts.writeAlertsJson(f, "run-1", List.of(), null));
			JsonNode n = mapper.readTree(Files.readString(f.toPath(), StandardCharsets.UTF_8));
			assertAll(() -> assertEquals("run-1", n.get("runId").asText()),
					() -> assertEquals(0, n.get("count").asInt()), () -> assertTrue(n.get("alerts").isArray()),
					() -> assertEquals(0, n.get("alerts").size()));
		}

		@Test
		@DisplayName("alerts are written with baseline, rule, message and likely cause (only when set)")
		void withAlerts(@TempDir Path dir) throws Exception {
			File f = dir.resolve("alerts.json").toFile();
			List<Alert> alerts = List.of(new Alert("b1", "FLAKY", "3 flips", null),
					new Alert("b2", "REGRESSION", "latest FAIL", "model changed a → b"));
			assertTrue(TrendAlerts.writeAlertsJson(f, alerts, null));
			JsonNode n = mapper.readTree(Files.readString(f.toPath(), StandardCharsets.UTF_8));
			JsonNode a0 = n.get("alerts").get(0);
			JsonNode a1 = n.get("alerts").get(1);
			assertAll(() -> assertFalse(n.has("runId"), "no run id -> omitted"),
					() -> assertEquals(2, n.get("count").asInt()),
					() -> assertEquals("b1", a0.get("baselineId").asText()),
					() -> assertEquals("FLAKY", a0.get("rule").asText()),
					() -> assertEquals("3 flips", a0.get("message").asText()), () -> assertFalse(a0.has("likelyCause")),
					() -> assertEquals("model changed a → b", a1.get("likelyCause").asText()));
		}

		@Test
		@DisplayName("an unwritable path (a directory) returns false, logs, and does not throw")
		void unwritable(@TempDir Path dir) {
			List<String> log = new ArrayList<>();
			boolean[] ok = new boolean[] { true };
			assertDoesNotThrow(() -> ok[0] = TrendAlerts.writeAlertsJson(dir.toFile(), "r", List.of(), log::add));
			assertAll(() -> assertFalse(ok[0]), () -> assertFalse(log.isEmpty()),
					() -> assertFalse(TrendAlerts.writeAlertsJson(null, "r", List.of(), null)));
		}
	}
}

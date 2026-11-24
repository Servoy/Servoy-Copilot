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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.opencode.skilltest.RunHistory.Entry;
import com.servoy.eclipse.opencode.skilltest.RunHistory.Trend;
import com.servoy.eclipse.opencode.skilltest.SkillTestResult.Status;

/**
 * Unit tests for {@link RunHistory#trends}, the detail-panel
 * {@link RunHistory#historyHtml} and the trend-window helpers
 * {@link RunHistory#parseTrendRuns} / {@link RunHistory#clampTrendRuns}
 * (SVY-21661 §3.3, §3.5, §3.5a).
 */
class RunHistoryTrendTest {

	private int seq;

	private Entry entry(String baseline, Status status, Long tokens) {
		int i = seq++;
		return new Entry("run-" + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 60L), "ci", null, baseline,
				status, null, null, null, null, null, null, tokens, null, null, null);
	}

	private List<Entry> history(String baseline, Status... statuses) {
		List<Entry> list = new ArrayList<>();
		for (Status s : statuses) {
			list.add(entry(baseline, s, null));
		}
		return list;
	}

	@Nested
	class Trends {

		@Test
		@DisplayName("pass rate covers only the last N runs; older ones are ignored")
		void lastNCutOff() {
			// 3 old FAILs, then 5 PASS: last 5 -> 100%.
			List<Entry> h = history("b", Status.FAIL, Status.FAIL, Status.FAIL, Status.PASS, Status.PASS, Status.PASS,
					Status.PASS, Status.PASS);
			Trend t = RunHistory.trends(h, 5).get("b");
			assertAll(() -> assertEquals(5, t.runs()), () -> assertEquals(5, t.passes()),
					() -> assertEquals(1.0d, t.passRate(), 1e-9), () -> assertEquals("100% (5/5) ✓✓✓✓✓", t.label()));
		}

		@Test
		@DisplayName("SKIPPED runs are excluded from numerator, denominator and sparkline")
		void skippedExcluded() {
			Trend t = RunHistory.trends(history("b", Status.PASS, Status.SKIPPED, Status.FAIL, Status.SKIPPED), 10)
					.get("b");
			assertAll(() -> assertEquals(2, t.runs()), () -> assertEquals(1, t.passes()),
					() -> assertEquals("✓✗", t.sparkline()), () -> assertEquals("50% (1/2) ✓✗", t.label()));
		}

		@Test
		@DisplayName("ERROR counts as a non-pass and renders as '!'")
		void errorIsNonPass() {
			Trend t = RunHistory.trends(history("b", Status.PASS, Status.ERROR), 10).get("b");
			assertAll(() -> assertEquals(1, t.passes()), () -> assertEquals(2, t.runs()),
					() -> assertEquals("✓!", t.sparkline()));
		}

		@Test
		@DisplayName("the sparkline runs oldest to newest")
		void sparklineOrder() {
			Trend t = RunHistory.trends(history("b", Status.FAIL, Status.PASS, Status.PASS, Status.ERROR), 10).get("b");
			assertEquals("✗✓✓!", t.sparkline());
		}

		@Test
		@DisplayName("a baseline with only SKIPPED runs labels as '—'; an empty history has no trends")
		void noHistory() {
			assertAll(() -> assertEquals("—", RunHistory.trends(history("b", Status.SKIPPED), 10).get("b").label()),
					() -> assertTrue(RunHistory.trends(List.of(), 10).isEmpty()),
					() -> assertTrue(RunHistory.trends(null, 10).isEmpty()));
		}

		@Test
		@DisplayName("avg/last tokens are computed over the considered runs that have tokens")
		void tokens() {
			List<Entry> h = List.of(entry("b", Status.PASS, Long.valueOf(999_999)), entry("b", Status.PASS, 100L),
					entry("b", Status.PASS, null), entry("b", Status.FAIL, 300L));
			Trend t = RunHistory.trends(h, 3).get("b");
			assertAll(() -> assertEquals(Long.valueOf(200), t.avgTokens(), "(100+300)/2; 999999 outside the window"),
					() -> assertEquals(Long.valueOf(300), t.lastTokens()));
		}

		@Test
		@DisplayName("no token data -> avg/last tokens null")
		void tokensUnknown() {
			Trend t = RunHistory.trends(history("b", Status.PASS), 10).get("b");
			assertAll(() -> assertNull(t.avgTokens()), () -> assertNull(t.lastTokens()));
		}

		@Test
		@DisplayName("multiple baselines are kept separate")
		void separateBaselines() {
			List<Entry> h = new ArrayList<>();
			h.add(entry("a", Status.PASS, null));
			h.add(entry("b", Status.FAIL, null));
			h.add(entry("a", Status.PASS, null));
			Map<String, Trend> trends = RunHistory.trends(h, 10);
			assertAll(() -> assertEquals("100% (2/2) ✓✓", trends.get("a").label()),
					() -> assertEquals("0% (0/1) ✗", trends.get("b").label()));
		}

		@Test
		@DisplayName("a non-default N is honoured and N < 1 is clamped to 1")
		void nonDefaultWindow() {
			List<Entry> h = history("b", Status.PASS, Status.PASS, Status.FAIL);
			assertAll(() -> assertEquals("67% (2/3) ✓✓✗", RunHistory.trends(h, 3).get("b").label()),
					() -> assertEquals("50% (1/2) ✓✗", RunHistory.trends(h, 2).get("b").label()),
					() -> assertEquals("0% (0/1) ✗", RunHistory.trends(h, 0).get("b").label()));
		}
	}

	@Nested
	class TrendWindow {

		@ParameterizedTest
		@CsvSource({ "1,1", "10,10", "1000,1000", "' 42 ',42" })
		@DisplayName("parseTrendRuns accepts integers 1..1000")
		void accepts(String raw, int expected) {
			assertEquals(Integer.valueOf(expected), RunHistory.parseTrendRuns(raw));
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "  ", "abc", "1.5", "0", "-3", "1001", "99999999999" })
		@DisplayName("parseTrendRuns rejects blank, non-numeric, 0, negative and > 1000")
		void rejects(String raw) {
			assertNull(RunHistory.parseTrendRuns(raw));
		}

		@ParameterizedTest
		@CsvSource({ "-5,1", "0,1", "1,1", "500,500", "1000,1000", "1001,1000" })
		@DisplayName("clampTrendRuns clamps into 1..1000")
		void clamps(int in, int expected) {
			assertEquals(expected, RunHistory.clampTrendRuns(in));
		}
	}

	@Nested
	class HistoryHtml {

		@Test
		@DisplayName("values are HTML-escaped")
		void escapes() {
			Entry e = new Entry("r", Instant.parse("2026-01-01T00:00:00Z"), "ci", "<b>", "x", Status.PASS, null, null,
					null, "kiro/<script>", null, null, null, null, null, null);
			String html = RunHistory.historyHtml(List.of(e), "x", 10);
			assertAll(() -> assertTrue(html.contains("kiro/&lt;script&gt;")),
					() -> assertFalse(html.contains("<script")), () -> assertTrue(html.contains("&lt;b&gt;")));
		}

		@Test
		@DisplayName("rows are newest first and limited to N; the title names N")
		void newestFirstLimited() {
			List<Entry> h = new ArrayList<>();
			for (int i = 1; i <= 5; i++) {
				h.add(new Entry("r" + i, Instant.parse("2026-01-0" + i + "T00:00:00Z"), "ci", null, "x", Status.PASS,
						null, null, null, "model-" + i, null, null, null, null, null, null));
			}
			String html = RunHistory.historyHtml(h, "x", 3);
			assertAll(() -> assertTrue(html.contains("History (last 3 runs)")),
					() -> assertTrue(html.indexOf("model-5") < html.indexOf("model-4")),
					() -> assertTrue(html.indexOf("model-4") < html.indexOf("model-3")),
					() -> assertFalse(html.contains("model-2"), "older than N omitted"),
					() -> assertFalse(html.contains("model-1"), "older than N omitted"));
		}

		@Test
		@DisplayName("shows trials, tokens and credits Δ; other baselines are not listed")
		void columns() {
			Entry mine = new Entry("r", Instant.parse("2026-01-01T00:00:00Z"), "ci", "7", "x", Status.FAIL,
					Integer.valueOf(2), Integer.valueOf(3), null, null, null, null, Long.valueOf(12345), null,
					Double.valueOf(1.256d), null);
			Entry other = new Entry("r", Instant.parse("2026-01-01T00:00:00Z"), "ci", null, "other", Status.PASS, null,
					null, null, "OTHER_MODEL", null, null, null, null, null, null);
			String html = RunHistory.historyHtml(List.of(mine, other), "x", 10);
			assertAll(() -> assertTrue(html.contains("2/3")), () -> assertTrue(html.contains("12345")),
					() -> assertTrue(html.contains("1.26")), () -> assertTrue(html.contains("ci #7")),
					() -> assertTrue(html.contains("FAIL")), () -> assertFalse(html.contains("OTHER_MODEL")));
		}

		@Test
		@DisplayName("no history for the baseline shows a 'no run history' note")
		void empty() {
			assertTrue(RunHistory.historyHtml(List.of(), "x", 10).contains("No run history yet."));
		}
	}
}

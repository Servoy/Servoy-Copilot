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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.RunMetrics.KiroUsage;
import com.servoy.eclipse.opencode.skilltest.RunMetrics.RequestTokens;
import com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage;

/**
 * Unit tests for {@link RunMetrics} and its nested {@link TokenUsage} /
 * {@link KiroUsage} (SVY-21660): the "unknown" / "(estimate)" surface labels
 * and the headline token {@link TokenUsage#total()} which excludes cache
 * read/write.
 */
class RunMetricsTest {

	@Nested
	class TotalExcludesCache {

		@Test
		@DisplayName("total() is input + output + reasoning and EXCLUDES cacheRead/cacheWrite")
		void totalExcludesCache() {
			// cacheRead=1000, cacheWrite=2000 must NOT contribute to the headline total.
			TokenUsage t = new TokenUsage(10, 20, 5, 1000, 2000);
			assertEquals(35L, t.total(), "total is 10+20+5, cache excluded");
		}

		@Test
		@DisplayName("total() is zero when input/output/reasoning are zero even with cache present")
		void totalZeroWithCacheOnly() {
			TokenUsage t = new TokenUsage(0, 0, 0, 500, 700);
			assertEquals(0L, t.total(), "cache-only payload totals 0");
		}
	}

	@Nested
	class TokensLabel {

		@Test
		@DisplayName("tokensLabel() is 'unknown' when tokens are null")
		void unknownWhenNull() {
			RunMetrics m = new RunMetrics(7, null, null);
			assertEquals("unknown", m.tokensLabel());
		}

		@Test
		@DisplayName("tokensLabel() shows the total and the in/out/reasoning breakdown")
		void showsTotalAndBreakdown() {
			RunMetrics m = new RunMetrics(7, new TokenUsage(10, 20, 5, 0, 0), null);
			String label = m.tokensLabel();
			assertAll(() -> assertTrue(label.startsWith("35 "), "leads with the total 35: " + label),
					() -> assertTrue(label.contains("in 10"), "input shown"),
					() -> assertTrue(label.contains("out 20"), "output shown"),
					() -> assertTrue(label.contains("reasoning 5"), "reasoning shown"),
					() -> assertFalse(label.contains("cache"), "no cache segment when cache is zero"));
		}

		@Test
		@DisplayName("tokensLabel() appends a cache r/w segment only when cache is non-zero")
		void cacheSegmentOnlyWhenPresent() {
			RunMetrics m = new RunMetrics(7, new TokenUsage(1, 1, 0, 3, 4), null);
			String label = m.tokensLabel();
			assertAll(() -> assertTrue(label.contains("cache r/w 3/4"), "cache segment present: " + label),
					() -> assertTrue(label.startsWith("2 "), "total still excludes cache (1+1)"));
		}
	}

	@Nested
	class KiroLabel {

		@Test
		@DisplayName("kiroLabel() is 'unknown (estimate)' when kiro is null")
		void unknownWhenNull() {
			RunMetrics m = new RunMetrics(7, null, null);
			assertEquals("unknown (estimate)", m.kiroLabel());
		}

		@Test
		@DisplayName("kiroLabel() carries the figures AND an explicit (estimate) marker")
		void carriesEstimateMarker() {
			RunMetrics m = new RunMetrics(7, null, new KiroUsage(3, 0.42d));
			String label = m.kiroLabel();
			assertAll(() -> assertTrue(label.contains("3"), "requests shown"),
					() -> assertTrue(label.contains("0.42"), "estCredits shown"),
					() -> assertTrue(label.contains("(estimate)"), "explicitly labelled an estimate: " + label));
		}
	}

	@Nested
	class RequestTokensHelpers {

		@Test
		@DisplayName("cacheReadRatio() is cacheRead/input, and 0 when input is 0")
		void cacheReadRatio() {
			RequestTokens cached = new RequestTokens(0, new TokenUsage(1000, 0, 0, 250, 0), List.of(), null);
			RequestTokens noInput = new RequestTokens(1, new TokenUsage(0, 0, 0, 500, 0), List.of(), null);
			assertAll(() -> assertEquals(0.25d, cached.cacheReadRatio(), 0.0001d, "250/1000"),
					() -> assertEquals(0d, noInput.cacheReadRatio(), 0.0001d, "0 when input is 0 (no divide-by-zero)"));
		}

		@Test
		@DisplayName("cacheReadRatio() is 0 when tokens are null")
		void cacheReadRatioNullTokens() {
			RequestTokens nullTokens = new RequestTokens(0, null, List.of(), null);
			assertEquals(0d, nullTokens.cacheReadRatio(), 0.0001d, "0 when tokens are null");
		}

		@Test
		@DisplayName("noCaching() is true for a large uncached input, false when cache is present")
		void noCaching() {
			// Large input, ~0 cache-read ratio -> the expensive full-context-resend.
			RequestTokens uncached = new RequestTokens(0, new TokenUsage(474_000, 10, 0, 0, 0), List.of(), null);
			// Large input but well cached -> not flagged.
			RequestTokens wellCached = new RequestTokens(1, new TokenUsage(474_000, 10, 0, 470_000, 0), List.of(),
					null);
			// Trivial input -> not flagged even with no cache.
			RequestTokens trivial = new RequestTokens(2, new TokenUsage(10, 5, 0, 0, 0), List.of(), null);
			assertAll(() -> assertTrue(uncached.noCaching(), "large uncached input is flagged"),
					() -> assertFalse(wellCached.noCaching(), "a well-cached request is not flagged"),
					() -> assertFalse(trivial.noCaching(), "a trivial input is not flagged"));
		}
	}

	@Nested
	class RequestRanking {

		private RunMetrics withRequests(RequestTokens... requests) {
			return new RunMetrics(5, null, null, null, List.of(requests));
		}

		@Test
		@DisplayName("mostInefficientRequest() picks the largest-input request")
		void mostInefficient() {
			RequestTokens worst = withRequests(new RequestTokens(0, new TokenUsage(100, 1, 0, 0, 0), List.of(), null),
					new RequestTokens(1, new TokenUsage(900, 1, 0, 0, 0), List.of(), null),
					new RequestTokens(2, new TokenUsage(300, 1, 0, 0, 0), List.of(), null)).mostInefficientRequest();
			assertAll(() -> org.junit.jupiter.api.Assertions.assertNotNull(worst),
					() -> assertEquals(1, worst.index(), "the 900-input request is the most inefficient"),
					() -> assertEquals(900L, worst.tokens().input()));
		}

		@Test
		@DisplayName("baseInput() is the minimum recurring input across requests")
		void baseInput() {
			RunMetrics m = withRequests(new RequestTokens(0, new TokenUsage(500, 1, 0, 0, 0), List.of(), null),
					new RequestTokens(1, new TokenUsage(120, 1, 0, 0, 0), List.of(), null),
					new RequestTokens(2, new TokenUsage(800, 1, 0, 0, 0), List.of(), null));
			assertEquals(120L, m.baseInput(), "the smallest per-request input is the fixed base");
		}

		@Test
		@DisplayName("mostInefficientRequest()/baseInput() degrade to null/-1 with no per-request data")
		void emptyDegrades() {
			RunMetrics m = new RunMetrics(5, null, null);
			assertAll(() -> org.junit.jupiter.api.Assertions.assertNull(m.mostInefficientRequest(), "null when empty"),
					() -> assertEquals(-1L, m.baseInput(), "-1 (unknown) when empty"));
		}

		@Test
		@DisplayName("requestRankingLabel() is 'unknown' when there is no per-request data")
		void rankingLabelUnknown() {
			assertEquals("unknown", new RunMetrics(5, null, null).requestRankingLabel());
		}

		@Test
		@DisplayName("requestRankingLabel() names the count, base input and most inefficient request")
		void rankingLabelRendered() {
			RunMetrics m = withRequests(
					new RequestTokens(0, new TokenUsage(1000, 10, 0, 0, 0), List.of("servoy-coder.create_form"), "sdd"),
					new RequestTokens(1, new TokenUsage(474_000, 20, 0, 0, 0), List.of("execute"), "sdd"));
			String label = m.requestRankingLabel();
			assertAll(() -> assertTrue(label.contains("2 requests"), "request count: " + label),
					() -> assertTrue(label.contains("base input 1000"), "base input shown"),
					() -> assertTrue(label.contains("request #1"), "the largest-input request is highlighted"),
					() -> assertTrue(label.contains("input 474000"), "its input shown"),
					() -> assertTrue(label.contains("no caching"), "the no-caching note when the ratio is ~0"),
					() -> assertTrue(label.contains("execute"), "its tools shown"),
					() -> assertTrue(label.contains("skill=sdd"), "its skill shown"));
		}
	}

	@Nested
	class CreditsLabel {

		@Test
		@DisplayName("creditsLabel() is 'unknown' when creditsDelta is null")
		void unknownWhenNull() {
			RunMetrics m = new RunMetrics(7, null, null, null, List.of());
			assertEquals("unknown", m.creditsLabel());
		}

		@Test
		@DisplayName("creditsLabel() renders the delta with an explicit estimate / shared-account caveat")
		void rendersDeltaWithCaveat() {
			RunMetrics m = new RunMetrics(7, null, null, Double.valueOf(0.42d), List.of());
			String label = m.creditsLabel();
			assertAll(() -> assertTrue(label.contains("0.42"), "the delta is shown: " + label),
					() -> assertTrue(label.contains("estimate"), "labelled an estimate"),
					() -> assertTrue(label.toLowerCase().contains("shared account"), "shared-account caveat present"));
		}

		@Test
		@DisplayName("creditsLabel() renders before / limit, after and Δ when the whole bracket is present")
		void rendersFullBracket() {
			// Account used before=100, after=142, limit=1000, Δ=42 (SVY-21660 live-data
			// follow-up): the surface shows "before: X / limit, after: Y, Δ: Z".
			RunMetrics m = new RunMetrics(7, null, null, Double.valueOf(42.0d), List.of(), Double.valueOf(100.0d),
					Double.valueOf(142.0d), Double.valueOf(1000.0d));
			String label = m.creditsLabel();
			assertAll(() -> assertTrue(label.contains("before: 100.0"), "before shown: " + label),
					() -> assertTrue(label.contains("/ 1000.0"), "limit shown next to before"),
					() -> assertTrue(label.contains("after: 142.0"), "after shown"),
					() -> assertTrue(label.contains("\u0394: ~42.0"), "the delta shown"),
					() -> assertTrue(label.contains("estimate"), "labelled an estimate"),
					() -> assertTrue(label.toLowerCase().contains("shared account"), "shared-account caveat present"));
		}

		@Test
		@DisplayName("creditsLabel() shows 'sync pending / unknown' for the delta when the after reading is absent")
		void syncPendingWhenAfterAbsent() {
			// The before reading and limit arrived, but the post-run sync has not landed:
			// creditsAfter and creditsDelta are both null. The bracket still renders, and
			// the delta reads the pending wording instead of a misleading 0.
			RunMetrics m = new RunMetrics(7, null, null, null, List.of(), Double.valueOf(100.0d), null,
					Double.valueOf(1000.0d));
			String label = m.creditsLabel();
			assertAll(() -> assertTrue(label.contains("before: 100.0"), "before still shown: " + label),
					() -> assertTrue(label.contains("after: unknown"), "after is unknown"),
					() -> assertTrue(label.contains("\u0394: sync pending / unknown"),
							"the delta reads 'sync pending / unknown', never 0"));
		}

		@Test
		@DisplayName("creditsLabel() is 'unknown' when neither a delta nor any bracket reading is present")
		void unknownWhenNothing() {
			RunMetrics m = new RunMetrics(7, null, null, null, List.of(), null, null, null);
			assertEquals("unknown", m.creditsLabel());
		}
	}
}

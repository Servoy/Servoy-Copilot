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

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage;

/**
 * Unit tests for {@link BudgetEvaluator} (SVY-21660): the WARN-only soft
 * budget. Every outcome is a warning string or {@link Optional#empty()} - the
 * evaluator must never signal a failure or a verdict. Covers the absolute
 * {@code max} ceiling, the {@code baselineValue}+{@code tolerancePct}
 * threshold, the default {@code toolCalls} metric, and the "tokens absent ->
 * skipped" rule.
 */
class BudgetEvaluatorTest {

	private static RunMetrics metrics(int toolCalls, TokenUsage tokens) {
		return new RunMetrics(toolCalls, tokens, null);
	}

	@Nested
	class AbsoluteMax {

		@Test
		@DisplayName("tool calls over max yields a warning naming the metric, value and budget")
		void overMax() {
			Baseline.Budget budget = new Baseline.Budget("toolCalls", Integer.valueOf(25), null, null);
			Optional<String> warning = BudgetEvaluator.evaluate(budget, metrics(31, null));
			assertTrue(warning.isPresent(), "over max warns");
			assertAll(() -> assertTrue(warning.get().contains("31"), "current value present"),
					() -> assertTrue(warning.get().contains("25"), "budget present"),
					() -> assertTrue(warning.get().toLowerCase().contains("tool"), "names the metric"));
		}

		@Test
		@DisplayName("tool calls equal to max does NOT warn (exceeds means strictly greater)")
		void equalToMax() {
			Baseline.Budget budget = new Baseline.Budget("toolCalls", Integer.valueOf(25), null, null);
			assertTrue(BudgetEvaluator.evaluate(budget, metrics(25, null)).isEmpty(), "25 is within budget 25");
		}

		@Test
		@DisplayName("tool calls under max does NOT warn")
		void underMax() {
			Baseline.Budget budget = new Baseline.Budget("toolCalls", Integer.valueOf(25), null, null);
			assertTrue(BudgetEvaluator.evaluate(budget, metrics(10, null)).isEmpty(), "under budget is quiet");
		}
	}

	@Nested
	class BaselineValueWithTolerance {

		@Test
		@DisplayName("over baselineValue * (1 + tolerancePct/100) warns")
		void overThreshold() {
			// 18 * 1.25 = 22.5 -> 23 exceeds.
			Baseline.Budget budget = new Baseline.Budget("toolCalls", null, Integer.valueOf(18), Integer.valueOf(25));
			Optional<String> warning = BudgetEvaluator.evaluate(budget, metrics(23, null));
			assertAll(() -> assertTrue(warning.isPresent(), "over threshold warns"),
					() -> assertTrue(warning.get().contains("23"), "current value present"),
					() -> assertTrue(warning.get().contains("18"), "reference present"));
		}

		@Test
		@DisplayName("within baselineValue + tolerance does NOT warn")
		void withinThreshold() {
			// 18 * 1.25 = 22.5 -> 22 is within.
			Baseline.Budget budget = new Baseline.Budget("toolCalls", null, Integer.valueOf(18), Integer.valueOf(25));
			assertTrue(BudgetEvaluator.evaluate(budget, metrics(22, null)).isEmpty(), "22 within 22.5 threshold");
		}

		@Test
		@DisplayName("a missing tolerancePct is treated as zero tolerance")
		void zeroToleranceWhenAbsent() {
			Baseline.Budget budget = new Baseline.Budget("toolCalls", null, Integer.valueOf(18), null);
			assertAll(
					() -> assertTrue(BudgetEvaluator.evaluate(budget, metrics(19, null)).isPresent(),
							"19 exceeds 18 at zero tolerance"),
					() -> assertTrue(BudgetEvaluator.evaluate(budget, metrics(18, null)).isEmpty(),
							"18 is within 18 at zero tolerance"));
		}
	}

	@Nested
	class DefaultMetricIsToolCalls {

		@Test
		@DisplayName("a blank metric defaults to toolCalls and is evaluated against tool calls")
		void blankMetricDefaultsToToolCalls() {
			// metric blank -> Budget canonicalizes to "toolCalls"; tokens are present but
			// must be ignored, the tool-call count (30 > 25) drives the warning.
			Baseline.Budget budget = new Baseline.Budget("", Integer.valueOf(25), null, null);
			Optional<String> warning = BudgetEvaluator.evaluate(budget, metrics(30, new TokenUsage(1, 1, 0, 0, 0)));
			assertAll(() -> assertTrue(warning.isPresent(), "default metric warns on tool calls"),
					() -> assertTrue(warning.get().toLowerCase().contains("tool"), "named as tool-calls"),
					() -> assertFalse(warning.get().toLowerCase().contains("token"), "not evaluated as tokens"));
		}
	}

	@Nested
	class TokensMetric {

		@Test
		@DisplayName("metric 'tokens' with null tokens is SKIPPED - no warning, no failure")
		void tokensAbsentSkipped() {
			Baseline.Budget budget = new Baseline.Budget("tokens", Integer.valueOf(1000), null, null);
			// tokens == null => the budget is skipped, never warned (never fabricate a 0).
			assertTrue(BudgetEvaluator.evaluate(budget, metrics(99, null)).isEmpty(),
					"tokens budget skipped when no token data");
		}

		@Test
		@DisplayName("metric 'tokens' over max (tokens present) warns on the token total")
		void tokensOverMax() {
			Baseline.Budget budget = new Baseline.Budget("tokens", Integer.valueOf(1000), null, null);
			// total = 800 + 400 + 0 = 1200 > 1000.
			Optional<String> warning = BudgetEvaluator.evaluate(budget,
					metrics(5, new TokenUsage(800, 400, 0, 5000, 6000)));
			assertAll(() -> assertTrue(warning.isPresent(), "over token max warns"),
					() -> assertTrue(warning.get().contains("1200"), "token total present (cache excluded)"),
					() -> assertTrue(warning.get().toLowerCase().contains("token"), "named as tokens"));
		}

		@Test
		@DisplayName("metric 'tokens' within max (tokens present) does NOT warn")
		void tokensWithinMax() {
			Baseline.Budget budget = new Baseline.Budget("tokens", Integer.valueOf(1000), null, null);
			assertTrue(BudgetEvaluator.evaluate(budget, metrics(5, new TokenUsage(400, 300, 0, 0, 0))).isEmpty(),
					"700 within 1000");
		}
	}

	@Nested
	class NoOp {

		@Test
		@DisplayName("a null budget yields no warning")
		void nullBudget() {
			assertTrue(BudgetEvaluator.evaluate(null, metrics(100, null)).isEmpty(), "no budget, no warning");
		}

		@Test
		@DisplayName("null metrics yields no warning")
		void nullMetrics() {
			Baseline.Budget budget = new Baseline.Budget("toolCalls", Integer.valueOf(1), null, null);
			assertTrue(BudgetEvaluator.evaluate(budget, null).isEmpty(), "no metrics, no warning");
		}
	}
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates an optional per-baseline soft {@link Baseline.Budget} against a
 * run's representative {@link RunMetrics} (SVY-21660).
 * <p>
 * Pure and side-effect-free. Returns an optional human-readable <b>warning</b>
 * when the run exceeds its budget - <b>never a failure</b>: a warning never
 * touches the PASS/FAIL verdict, the JUnit {@code failures}/{@code errors}
 * counts, or a {@code <testcase>} body. It is surfaced only as a
 * {@code <system-out>} line and a view annotation.
 * </p>
 * <p>
 * The default budgeted metric is {@code toolCalls} (the reliable one). When
 * {@code metric:"tokens"} is configured but the run produced no token data, the
 * budget is <b>skipped</b> (empty) - never warned - because warning on absent
 * data would be misleading.
 * </p>
 */
public final class BudgetEvaluator {

	private BudgetEvaluator() {
	}

	/**
	 * Evaluates the budget against the metrics.
	 *
	 * @param budget  the soft budget (may be {@code null} - then nothing is
	 *                evaluated)
	 * @param metrics the run's representative metrics (may be {@code null})
	 * @return a warning string when over budget, or {@link Optional#empty()} when
	 *         within budget, the budgeted metric's data is absent, or no budget is
	 *         declared. Never signals a failure.
	 */
	public static Optional<String> evaluate(Baseline.Budget budget, RunMetrics metrics) {
		if (budget == null || metrics == null) {
			return Optional.empty();
		}
		if (budget.isTokenMetric()) {
			if (metrics.tokens() == null) {
				// Never warn on absent data: a tokens budget is skipped when no token
				// data was produced (not failed, not warned).
				return Optional.empty();
			}
			return evaluateValue(budget, metrics.tokens().total(), "tokens"); //$NON-NLS-1$
		}
		if (budget.isCreditMetric()) {
			if (metrics.kiro() == null) {
				// Credits are an estimate; skip (never warn) when no per-session Kiro
				// figure was captured, same as tokens-when-absent.
				return Optional.empty();
			}
			// Budget the PER-SESSION estimate (kiro.db session_usage.est_credits for
			// this scenario's session), NOT the account-wide creditsDelta: on a shared
			// account the before/after delta also captures other concurrent usage, so
			// it over-reports a scenario's cost (SVY-21660). Round to a whole credit -
			// the budget values are whole numbers and the estimate is fractional.
			return evaluateValue(budget, Math.round(metrics.kiro().estCredits()), "credits"); //$NON-NLS-1$
		}
		// Default metric: tool calls (always present).
		return evaluateValue(budget, metrics.toolCalls(), "tool-calls"); //$NON-NLS-1$
	}

	/**
	 * Evaluates every budget in the list against the metrics and returns one
	 * combined warning (one line per over-budget metric), or
	 * {@link Optional#empty()} when none are over, the list is {@code null}/empty,
	 * or metrics are absent. Each metric is judged independently; a metric whose
	 * data is absent is skipped, never warned. Never signals a failure.
	 *
	 * @param budgets the soft budgets (may be {@code null}/empty)
	 * @param metrics the run's representative metrics (may be {@code null})
	 * @return the combined WARN text, or empty when within budget / nothing to
	 *         judge
	 */
	public static Optional<String> evaluateAll(List<Baseline.Budget> budgets, RunMetrics metrics) {
		if (budgets == null || budgets.isEmpty() || metrics == null) {
			return Optional.empty();
		}
		List<String> warnings = new ArrayList<>();
		for (Baseline.Budget budget : budgets) {
			evaluate(budget, metrics).ifPresent(warnings::add);
		}
		if (warnings.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(String.join("; ", warnings)); //$NON-NLS-1$
	}

	/**
	 * Compares a current value against the budget's absolute ceiling and/or
	 * {@code baselineValue * (1 + tolerancePct/100)} threshold, and builds a
	 * warning when exceeded.
	 */
	private static Optional<String> evaluateValue(Baseline.Budget budget, long current, String metricLabel) {
		return evaluateValue(budget, current, metricLabel, 0);
	}

	/**
	 * As {@link #evaluateValue(Baseline.Budget, long, String)} but with an explicit
	 * default tolerance applied when the budget sets a {@code baselineValue}
	 * without its own {@code tolerancePct}. The WARN-only budget warnings use
	 * {@code 0} (unchanged behaviour); the trend alerts (SVY-21661) pass their own
	 * default.
	 *
	 * @param budget           the budget (non-null)
	 * @param current          the current value of the budgeted metric
	 * @param metricLabel      the label used in the message
	 * @param defaultTolerance tolerance percentage used when the budget has none
	 * @return the over-budget message, or empty
	 */
	static Optional<String> evaluateValue(Baseline.Budget budget, long current, String metricLabel,
			int defaultTolerance) {
		if (budget == null) {
			return Optional.empty();
		}
		// Absolute ceiling.
		if (budget.max() != null && current > budget.max().intValue()) {
			int over = (int) current - budget.max().intValue();
			int pct = budget.max().intValue() > 0 ? Math.round(over * 100f / budget.max().intValue()) : 0;
			return Optional.of(metricLabel + " " + current + " exceeds budget " + budget.max() //$NON-NLS-1$ //$NON-NLS-2$
					+ " (+" + pct + "%)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		// Reference value + tolerance.
		if (budget.baselineValue() != null) {
			int tolerance = budget.tolerancePct() != null ? budget.tolerancePct().intValue() : defaultTolerance;
			double threshold = budget.baselineValue().intValue() * (1d + tolerance / 100d);
			if (current > threshold) {
				int pct = budget.baselineValue().intValue() > 0
						? Math.round((current - budget.baselineValue().intValue()) * 100f
								/ budget.baselineValue().intValue())
						: 0;
				return Optional.of(metricLabel + " " + current + " exceeds budget " //$NON-NLS-1$ //$NON-NLS-2$
						+ budget.baselineValue() + " +" + tolerance + "% (+" + pct + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		}
		return Optional.empty();
	}
}

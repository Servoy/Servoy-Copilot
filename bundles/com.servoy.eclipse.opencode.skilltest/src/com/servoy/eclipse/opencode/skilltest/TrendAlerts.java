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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Trend alerts over the skill-test run history (SVY-21661): "something is off"
 * signals evaluated by the headless CI runner after appending a run.
 * <p>
 * Rules (per baseline that has an entry in the current run, SKIPPED entries
 * ignored, entries in file/chronological order):
 * </p>
 * <ul>
 * <li>{@value #RULE_REGRESSION}: the latest run is FAIL/ERROR and the previous
 * {@code regressionRuns} (3) runs all passed.</li>
 * <li>{@value #RULE_PASS_RATE_DROP}: the pass rate of the last N runs is at
 * least {@code passRateDropPoints} (20) percentage points below the N runs
 * before them; both windows must be full.</li>
 * <li>{@value #RULE_FLAKY}: at least {@code flakyFlips} (3) pass/non-pass flips
 * within the last N runs.</li>
 * <li>{@value #RULE_COST_RISE}: the latest run exceeds the baseline's own
 * budget for a metric (same semantics as {@link BudgetEvaluator}, with a
 * default tolerance of 30% when a {@code baselineValue} has no
 * {@code tolerancePct}); without a budget for that metric, the latest value is
 * at least 30% above the median of the previous N values (needs at least
 * 3).</li>
 * </ul>
 * <p>
 * Pure except for {@link #writeAlertsJson}, which is best-effort and never
 * throws. Alerts carry no secrets: only ids, rule names, numbers and
 * model/version strings.
 * </p>
 */
public final class TrendAlerts {

	public static final String RULE_REGRESSION = "REGRESSION"; //$NON-NLS-1$
	public static final String RULE_PASS_RATE_DROP = "PASS_RATE_DROP"; //$NON-NLS-1$
	public static final String RULE_FLAKY = "FLAKY"; //$NON-NLS-1$
	public static final String RULE_COST_RISE = "COST_RISE"; //$NON-NLS-1$

	/** Minimum number of prior values the median fallback needs. */
	public static final int MEDIAN_MIN_VALUES = 3;

	/** File name of the alerts summary written next to the reports. */
	public static final String ALERTS_FILE = "alerts.json"; //$NON-NLS-1$

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private TrendAlerts() {
	}

	/**
	 * One trend alert.
	 *
	 * @param baselineId  the baseline
	 * @param rule        the rule id
	 * @param message     human-readable detail
	 * @param likelyCause e.g. {@code "model changed a → b"}, or {@code null}
	 */
	public record Alert(String baselineId, String rule, String message, String likelyCause) {
	}

	/**
	 * Alert thresholds.
	 *
	 * @param window                 runs per window (N)
	 * @param regressionRuns         passing runs that must precede a regression
	 * @param passRateDropPoints     percentage-point drop that fires
	 * @param flakyFlips             flips within the window that fire
	 * @param costRiseDefaultPercent default cost tolerance percentage
	 */
	public record AlertConfig(int window, int regressionRuns, int passRateDropPoints, int flakyFlips,
			int costRiseDefaultPercent) {

		public static final int DEFAULT_REGRESSION_RUNS = 3;
		public static final int DEFAULT_PASS_RATE_DROP_POINTS = 20;
		public static final int DEFAULT_FLAKY_FLIPS = 3;
		public static final int DEFAULT_COST_RISE_PERCENT = 30;

		public static final String PROP_WINDOW = "servoy.skilltest.alert.window"; //$NON-NLS-1$
		public static final String PROP_REGRESSION_RUNS = "servoy.skilltest.alert.regressionRuns"; //$NON-NLS-1$
		public static final String PROP_PASS_RATE_DROP_POINTS = "servoy.skilltest.alert.passRateDropPoints"; //$NON-NLS-1$
		public static final String PROP_FLAKY_FLIPS = "servoy.skilltest.alert.flakyFlips"; //$NON-NLS-1$
		public static final String PROP_COST_RISE_DEFAULT_PERCENT = "servoy.skilltest.alert.costRiseDefaultPercent"; //$NON-NLS-1$

		/** @return the default thresholds */
		public static AlertConfig defaults() {
			return new AlertConfig(RunHistory.DEFAULT_TREND_RUNS, DEFAULT_REGRESSION_RUNS,
					DEFAULT_PASS_RATE_DROP_POINTS, DEFAULT_FLAKY_FLIPS, DEFAULT_COST_RISE_PERCENT);
		}

		/** @return thresholds overridden by {@code -Dservoy.skilltest.alert.*} */
		public static AlertConfig fromSystem() {
			return from(key -> {
				try {
					return System.getProperty(key);
				} catch (SecurityException e) {
					return null;
				}
			});
		}

		/**
		 * @param lookup property lookup; blank/invalid values fall back to the defaults
		 * @return the thresholds
		 */
		public static AlertConfig from(Function<String, String> lookup) {
			return new AlertConfig(positive(lookup, PROP_WINDOW, RunHistory.DEFAULT_TREND_RUNS),
					positive(lookup, PROP_REGRESSION_RUNS, DEFAULT_REGRESSION_RUNS),
					nonNegative(lookup, PROP_PASS_RATE_DROP_POINTS, DEFAULT_PASS_RATE_DROP_POINTS),
					positive(lookup, PROP_FLAKY_FLIPS, DEFAULT_FLAKY_FLIPS),
					nonNegative(lookup, PROP_COST_RISE_DEFAULT_PERCENT, DEFAULT_COST_RISE_PERCENT));
		}

		private static int positive(Function<String, String> lookup, String key, int def) {
			Integer v = parse(lookup, key);
			return v != null && v.intValue() >= 1 ? v.intValue() : def;
		}

		private static int nonNegative(Function<String, String> lookup, String key, int def) {
			Integer v = parse(lookup, key);
			return v != null && v.intValue() >= 0 ? v.intValue() : def;
		}

		private static Integer parse(Function<String, String> lookup, String key) {
			if (lookup == null) {
				return null;
			}
			try {
				String raw = lookup.apply(key);
				return raw == null || raw.isBlank() ? null : Integer.valueOf(raw.trim());
			} catch (RuntimeException e) {
				return null;
			}
		}
	}

	/**
	 * Evaluates all rules.
	 *
	 * @param all               the full history in file order
	 * @param currentRunId      the run whose baselines are judged
	 * @param budgetsByBaseline baseline id to its declared budgets (may be
	 *                          {@code null})
	 * @param cfg               thresholds ({@code null} = defaults)
	 * @return the alerts, in history baseline order
	 */
	public static List<Alert> evaluate(List<RunHistory.Entry> all, String currentRunId,
			Map<String, List<Baseline.Budget>> budgetsByBaseline, AlertConfig cfg) {
		List<Alert> alerts = new ArrayList<>();
		if (all == null || all.isEmpty() || currentRunId == null) {
			return alerts;
		}
		AlertConfig c = cfg != null ? cfg : AlertConfig.defaults();
		for (Map.Entry<String, List<RunHistory.Entry>> group : RunHistory.byBaseline(all).entrySet()) {
			List<RunHistory.Entry> entries = group.getValue();
			int currentIdx = -1;
			for (int i = entries.size() - 1; i >= 0; i--) {
				if (currentRunId.equals(entries.get(i).runId())) {
					currentIdx = i;
					break;
				}
			}
			if (currentIdx < 0) {
				continue; // not in the current run: never alerts
			}
			RunHistory.Entry current = entries.get(currentIdx);
			if (current.status() == SkillTestResult.Status.SKIPPED) {
				continue;
			}
			// History up to and including the current entry, skipped ignored.
			List<RunHistory.Entry> hist = new ArrayList<>();
			for (int i = 0; i <= currentIdx; i++) {
				if (entries.get(i).status() != SkillTestResult.Status.SKIPPED) {
					hist.add(entries.get(i));
				}
			}
			String cause = likelyCause(hist);
			String id = group.getKey();
			regression(hist, c).ifPresent(m -> alerts.add(new Alert(id, RULE_REGRESSION, m, cause)));
			passRateDrop(hist, c).ifPresent(m -> alerts.add(new Alert(id, RULE_PASS_RATE_DROP, m, cause)));
			flaky(hist, c).ifPresent(m -> alerts.add(new Alert(id, RULE_FLAKY, m, cause)));
			List<Baseline.Budget> budgets = budgetsByBaseline != null ? budgetsByBaseline.get(id) : null;
			for (String m : costRise(hist, budgets, c)) {
				alerts.add(new Alert(id, RULE_COST_RISE, m, cause));
			}
		}
		return alerts;
	}

	private static boolean isPass(RunHistory.Entry e) {
		return e.status() == SkillTestResult.Status.PASS;
	}

	static Optional<String> regression(List<RunHistory.Entry> hist, AlertConfig c) {
		int k = c.regressionRuns();
		if (hist.size() < k + 1) {
			return Optional.empty();
		}
		RunHistory.Entry latest = hist.get(hist.size() - 1);
		if (isPass(latest)) {
			return Optional.empty();
		}
		for (int i = hist.size() - 1 - k; i < hist.size() - 1; i++) {
			if (!isPass(hist.get(i))) {
				return Optional.empty();
			}
		}
		return Optional.of("latest run is " + latest.status() + " after " + k + " passing run(s)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	static Optional<String> passRateDrop(List<RunHistory.Entry> hist, AlertConfig c) {
		int n = c.window();
		if (hist.size() < 2 * n) {
			return Optional.empty();
		}
		List<RunHistory.Entry> last = hist.subList(hist.size() - n, hist.size());
		List<RunHistory.Entry> prev = hist.subList(hist.size() - 2 * n, hist.size() - n);
		double lastRate = rate(last);
		double prevRate = rate(prev);
		double dropPoints = (prevRate - lastRate) * 100d;
		if (dropPoints + 1e-9 >= c.passRateDropPoints() && dropPoints > 0) {
			return Optional.of("pass rate " + pct(lastRate) + "% over the last " + n + " runs vs " + pct(prevRate) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ "% over the " + n + " runs before (-" + Math.round(dropPoints) + " points)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return Optional.empty();
	}

	static Optional<String> flaky(List<RunHistory.Entry> hist, AlertConfig c) {
		int n = c.window();
		List<RunHistory.Entry> last = hist.subList(Math.max(0, hist.size() - n), hist.size());
		int flips = 0;
		for (int i = 1; i < last.size(); i++) {
			if (isPass(last.get(i)) != isPass(last.get(i - 1))) {
				flips++;
			}
		}
		if (flips >= c.flakyFlips()) {
			return Optional.of(flips + " pass/fail flips within the last " + last.size() + " runs"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return Optional.empty();
	}

	private static double rate(List<RunHistory.Entry> entries) {
		if (entries.isEmpty()) {
			return 0d;
		}
		long passes = entries.stream().filter(TrendAlerts::isPass).count();
		return (double) passes / entries.size();
	}

	private static long pct(double rate) {
		return Math.round(rate * 100d);
	}

	/** The metrics COST_RISE judges, keyed by the budget metric name. */
	private enum Metric {
		TOKENS("tokens"), TOOL_CALLS("toolCalls"), CREDITS("credits"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		final String label;

		Metric(String label) {
			this.label = label;
		}

		Long value(RunHistory.Entry e) {
			return switch (this) {
			case TOKENS -> e.totalTokens();
			case TOOL_CALLS -> e.toolCalls() != null ? Long.valueOf(e.toolCalls().longValue()) : null;
			// Per-session estimate, rounded - same figure BudgetEvaluator budgets.
			case CREDITS -> e.estCredits() != null ? Long.valueOf(Math.round(e.estCredits().doubleValue())) : null;
			};
		}

		boolean matches(Baseline.Budget b) {
			return switch (this) {
			case TOKENS -> b.isTokenMetric();
			case CREDITS -> b.isCreditMetric();
			case TOOL_CALLS -> !b.isTokenMetric() && !b.isCreditMetric();
			};
		}
	}

	static List<String> costRise(List<RunHistory.Entry> hist, List<Baseline.Budget> budgets, AlertConfig c) {
		List<String> out = new ArrayList<>();
		if (hist.isEmpty()) {
			return out;
		}
		RunHistory.Entry latest = hist.get(hist.size() - 1);
		for (Metric metric : Metric.values()) {
			Long value = metric.value(latest);
			if (value == null) {
				continue;
			}
			List<Baseline.Budget> declared = new ArrayList<>();
			if (budgets != null) {
				for (Baseline.Budget b : budgets) {
					// No max and no baselineValue: unusable budget, use the median fallback.
					if (b != null && metric.matches(b) && (b.max() != null || b.baselineValue() != null)) {
						declared.add(b);
					}
				}
			}
			if (!declared.isEmpty()) {
				for (Baseline.Budget b : declared) {
					Optional<String> m = BudgetEvaluator.evaluateValue(b, value.longValue(), metric.label,
							c.costRiseDefaultPercent());
					if (m.isPresent()) {
						out.add(m.get());
						break;
					}
				}
				continue;
			}
			// No budget for this metric: median of the previous N values that have it.
			List<Long> prior = new ArrayList<>();
			for (int i = hist.size() - 2; i >= 0 && prior.size() < c.window(); i--) {
				Long v = metric.value(hist.get(i));
				if (v != null) {
					prior.add(v);
				}
			}
			if (prior.size() < MEDIAN_MIN_VALUES) {
				continue;
			}
			double median = median(prior);
			if (median <= 0) {
				continue;
			}
			double threshold = median * (1d + c.costRiseDefaultPercent() / 100d);
			if (value.longValue() + 1e-9 >= threshold) {
				long rise = Math.round((value.longValue() - median) * 100d / median);
				out.add(metric.label + " " + value + " is " + rise + "% above the median of the last " + prior.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
																														// //$NON-NLS-4$
						+ " runs (" + formatNumber(median) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return out;
	}

	static double median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int n = sorted.size();
		if (n == 0) {
			return 0d;
		}
		return n % 2 == 1 ? sorted.get(n / 2).doubleValue()
				: (sorted.get(n / 2 - 1).doubleValue() + sorted.get(n / 2).doubleValue()) / 2d;
	}

	private static String formatNumber(double d) {
		return d == Math.rint(d) ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d); //$NON-NLS-1$
	}

	/**
	 * @return a description of what changed between the latest and the previous
	 *         entry (model, skillsVersion), or {@code null}. The plugin version is
	 *         deliberately not a cause: its qualifier changes on every build, so it
	 *         would be reported on every alert and explain nothing.
	 */
	static String likelyCause(List<RunHistory.Entry> hist) {
		if (hist == null || hist.size() < 2) {
			return null;
		}
		RunHistory.Entry latest = hist.get(hist.size() - 1);
		RunHistory.Entry prev = hist.get(hist.size() - 2);
		List<String> parts = new ArrayList<>();
		change(parts, "model", prev.model(), latest.model()); //$NON-NLS-1$
		change(parts, "skillsVersion", prev.skillsVersion(), latest.skillsVersion()); //$NON-NLS-1$
		return parts.isEmpty() ? null : String.join("; ", parts); //$NON-NLS-1$
	}

	private static void change(List<String> parts, String what, String before, String after) {
		if (!Objects.equals(before, after)) {
			parts.add(what + " changed " + (before != null ? before : "?") + " \u2192 " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ (after != null ? after : "?")); //$NON-NLS-1$
		}
	}

	/**
	 * Writes {@code alerts.json} without a run id. Never throws.
	 *
	 * @see #writeAlertsJson(File, String, List, Consumer)
	 */
	public static boolean writeAlertsJson(File outFile, List<Alert> alerts, Consumer<String> log) {
		return writeAlertsJson(outFile, null, alerts, log);
	}

	/**
	 * Writes {@code {"runId":…, "count":n, "alerts":[…]}} - always, also with
	 * {@code count:0}. Best-effort, never throws.
	 *
	 * @param outFile the target file
	 * @param runId   the evaluated run (omitted when {@code null})
	 * @param alerts  the alerts (may be {@code null})
	 * @param log     diagnostic sink (may be {@code null})
	 * @return {@code true} when written
	 */
	public static boolean writeAlertsJson(File outFile, String runId, List<Alert> alerts, Consumer<String> log) {
		try {
			if (outFile == null) {
				return false;
			}
			ObjectNode root = MAPPER.createObjectNode();
			if (runId != null) {
				root.put("runId", runId); //$NON-NLS-1$
			}
			List<Alert> list = alerts != null ? alerts : List.of();
			root.put("count", list.size()); //$NON-NLS-1$
			ArrayNode arr = root.putArray("alerts"); //$NON-NLS-1$
			for (Alert a : list) {
				ObjectNode n = arr.addObject();
				n.put("baselineId", a.baselineId()); //$NON-NLS-1$
				n.put("rule", a.rule()); //$NON-NLS-1$
				n.put("message", a.message()); //$NON-NLS-1$
				if (a.likelyCause() != null) {
					n.put("likelyCause", a.likelyCause()); //$NON-NLS-1$
				}
			}
			File parent = outFile.getAbsoluteFile().getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			Files.writeString(outFile.toPath(),
					MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root),
					StandardCharsets.UTF_8);
			return true;
		} catch (IOException | RuntimeException e) {
			if (log != null) {
				try {
					log.accept("Could not write skill-test trend alerts to " + outFile + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (RuntimeException ignored) {
					// never break the caller
				}
			}
			return false;
		}
	}
}

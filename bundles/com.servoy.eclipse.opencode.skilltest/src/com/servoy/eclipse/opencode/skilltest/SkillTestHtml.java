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

import java.util.List;
import java.util.Map;

import com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker;

/**
 * Renders a {@link SkillTestResult} as a self-contained HTML page for the skill
 * test view's detail panel: the prompt that was sent, followed by the expected
 * (golden) and actual tool calls side by side, with matched/missing/extra calls
 * colour-coded. The markup is intentionally simple and inline-styled so it can
 * be restyled later without touching Java.
 */
public final class SkillTestHtml {

	private SkillTestHtml() {
	}

	/**
	 * Builds the placeholder page shown when no baseline is selected.
	 *
	 * @return an HTML document
	 */
	public static String placeholder() {
		return page("<p class=\"muted\">Select a baseline to see the conversation " //$NON-NLS-1$
				+ "(sent prompt and the tools the AI called).</p>"); //$NON-NLS-1$
	}

	/**
	 * Builds the page shown for a baseline that has not been run yet.
	 *
	 * @param baselineId the baseline id
	 * @return an HTML document
	 */
	public static String notRun(String baselineId) {
		return page("<h2>" + esc(baselineId) + "</h2><p class=\"muted\">No run yet.</p>"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Renders a full result: header, sent prompt, and expected-vs-actual tool
	 * calls.
	 *
	 * @param r the result to render
	 * @return an HTML document
	 */
	public static String render(SkillTestResult r) {
		return render(r, null);
	}

	/**
	 * Renders a full result: header, sent prompt, cost/efficiency metrics
	 * (SVY-21660), and expected-vs-actual tool calls.
	 *
	 * @param r             the result to render
	 * @param budgetWarning an optional WARN-only over-budget message (rendered as a
	 *                      non-fatal note), or {@code null} when within budget
	 * @return an HTML document
	 */
	public static String render(SkillTestResult r, String budgetWarning) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"header ").append(statusClass(r.getStatus())).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<span class=\"status\">").append(r.getStatus()).append("</span> "); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<span class=\"id\">").append(esc(r.getBaselineId())).append("</span>"); //$NON-NLS-1$ //$NON-NLS-2$
		// For a multi-trial run show the distribution (e.g. "2/3 trials passed")
		// instead of a bare attempt count.
		java.util.List<SkillTestResult.Trial> trials = r.getTrials();
		if (trials != null && trials.size() > 1) {
			sb.append("<span class=\"attempts\">").append(r.getPassCount()).append('/').append(trials.size()) //$NON-NLS-1$
					.append(" trials passed</span>"); //$NON-NLS-1$
		} else {
			sb.append("<span class=\"attempts\">").append(r.getAttempts()).append(" attempt(s)</span>"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append("</div>"); //$NON-NLS-1$

		if (r.getErrorMessage() != null && !r.getErrorMessage().isBlank()) {
			sb.append("<div class=\"error\">").append(esc(r.getErrorMessage())).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		if (r.getPrompt() != null && !r.getPrompt().isBlank()) {
			sb.append("<div class=\"section-title\">Prompt sent</div>"); //$NON-NLS-1$
			sb.append("<div class=\"bubble prompt\">").append(esc(r.getPrompt())).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		// Cost / efficiency metrics (SVY-21660): tool-call count (primary), token
		// breakdown or "unknown", Kiro requests/estCredits or "unknown (estimate)",
		// and any WARN-only over-budget note (visually distinct from FAIL).
		sb.append(metricsSection(r.getMetrics(), budgetWarning));

		// Multi-trial distribution: render each trial as its own collapsible section
		// (SVY-21366: look at the distribution, not one pass/fail). A single-trial run
		// falls through to the flat sections below.
		if (trials != null && trials.size() > 1) {
			sb.append("<div class=\"section-title\">Trials</div>"); //$NON-NLS-1$
			for (SkillTestResult.Trial t : trials) {
				boolean tp = t.pass();
				sb.append("<details").append(tp ? "" : " open") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.append("><summary class=\"section-summary\">Trial ").append(t.index()).append(" \u2014 ") //$NON-NLS-1$ //$NON-NLS-2$
						.append(tp ? "PASS" : "FAIL").append("</summary>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				if (t.outcomeNodes() != null && !t.outcomeNodes().isEmpty()) {
					for (PersistOutcomeChecker.Node node : t.outcomeNodes()) {
						sb.append(outcomeNode(node));
					}
				} else if (t.diff() != null && !t.diff().isBlank()) {
					sb.append("<div class=\"bubble outcome-fail\">").append(esc(t.diff())).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				if (t.jsUnitReport() != null && !t.jsUnitReport().isBlank()) {
					boolean jp = Boolean.TRUE.equals(t.jsUnitPass());
					sb.append("<div class=\"bubble ").append(jp ? "outcome-pass" : "outcome-fail").append("\"><pre>") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
							.append(esc(t.jsUnitReport())).append("</pre></div>"); //$NON-NLS-1$
				}
				// Per-trial cost/efficiency metrics (SVY-21660) so the spread is visible.
				if (t.metrics() != null) {
					sb.append(metricsLine(t.metrics()));
				}
				sb.append("</details>"); //$NON-NLS-1$
			}
			sb.append(toolCallsSection(r));
			return page(sb.toString());
		}

		// Outcome checks: for outcome-authoritative baselines this is the section
		// that decides PASS/FAIL (the real Servoy persist verification), so surface
		// it prominently with a per-assertion, expected-vs-actual, expandable tree.
		List<PersistOutcomeChecker.Node> nodes = r.getOutcomeNodes();
		if (nodes != null && !nodes.isEmpty()) {
			sb.append("<details open><summary class=\"section-summary\">Outcome checks</summary>"); //$NON-NLS-1$
			for (PersistOutcomeChecker.Node node : nodes) {
				sb.append(outcomeNode(node));
			}
			sb.append("</details>"); //$NON-NLS-1$
		} else {
			// No structured nodes (transcript-only baseline, or reconstructed result):
			// fall back to the flat diff / a pass confirmation.
			sb.append("<div class=\"section-title\">Outcome checks</div>"); //$NON-NLS-1$
			String diff = r.getDiff();
			if (diff != null && !diff.isBlank()) {
				sb.append("<div class=\"bubble outcome-fail\">").append(esc(diff)).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
			} else if (r.getStatus() == SkillTestResult.Status.PASS) {
				sb.append("<div class=\"bubble outcome-pass\">All expected outcomes were produced.</div>"); //$NON-NLS-1$
			} else {
				sb.append("<div class=\"muted\">(no outcome detail captured)</div>"); //$NON-NLS-1$
			}
		}

		// JSUnit behavioural verification (SVY-21366 §3.4b), when the baseline
		// declared verify.jsunit. Shown as its own section; expanded on failure.
		if (r.getJsUnitReport() != null && !r.getJsUnitReport().isBlank()) {
			boolean jsPass = Boolean.TRUE.equals(r.getJsUnitPass());
			sb.append("<details").append(jsPass ? "" : " open") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append("><summary class=\"section-summary\">JSUnit verification \u2014 ") //$NON-NLS-1$
					.append(jsPass ? "PASS" : "FAIL").append("</summary>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			sb.append("<div class=\"bubble ").append(jsPass ? "outcome-pass" : "outcome-fail").append("\"><pre>") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					.append(esc(r.getJsUnitReport())).append("</pre></div>"); //$NON-NLS-1$
			sb.append("</details>"); //$NON-NLS-1$
		}

		sb.append(toolCallsSection(r));

		return page(sb.toString());
	}

	/**
	 * Renders the collapsible "Tool calls (informational)" section (expected vs
	 * actual). Shared by the single-trial and multi-trial layouts.
	 */
	/**
	 * Renders the "Metrics" section (SVY-21660): tool-call count (primary), tokens
	 * or "unknown", Kiro requests/estCredits or "unknown (estimate)", and any
	 * WARN-only over-budget note. Shown even when metrics are {@code null} (older
	 * reconstructed results) so the "unknown" state is explicit.
	 */
	private static String metricsSection(RunMetrics m, String budgetWarning) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"section-title\">Metrics</div>"); //$NON-NLS-1$
		sb.append("<div class=\"bubble\">"); //$NON-NLS-1$
		if (m == null) {
			sb.append("Tool calls: <b>unknown</b><br>Tokens: unknown<br>Kiro: unknown (estimate)" //$NON-NLS-1$
					+ "<br>Credits used (\u0394): unknown"); //$NON-NLS-1$
		} else {
			sb.append("Tool calls: <b>").append(m.toolCalls()).append("</b><br>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("Tokens: ").append(esc(m.tokensLabel())).append("<br>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("Kiro: ").append(esc(m.kiroLabel())).append("<br>"); //$NON-NLS-1$ //$NON-NLS-2$
			// Accurate per-scenario credits Δ (SVY-21660 B), labelled an estimate on a
			// shared account.
			sb.append("Credits used (\u0394): ").append(esc(m.creditsLabel())); //$NON-NLS-1$
		}
		sb.append("</div>"); //$NON-NLS-1$
		if (budgetWarning != null && !budgetWarning.isBlank()) {
			sb.append("<div class=\"bubble budget-warn\">\u26a0 over budget: ") //$NON-NLS-1$
					.append(esc(budgetWarning)).append("</div>"); //$NON-NLS-1$
		}
		// Per-request token table (SVY-21660 C): ranked by input, the most inefficient
		// request highlighted, with cache-read ratio + base input + the "no caching"
		// note when the ratio is ~0. Per-tool cost is an attribution estimate.
		if (m != null && !m.requestTokens().isEmpty()) {
			sb.append(requestTokenTable(m));
		}
		return sb.toString();
	}

	/**
	 * Renders the per-request token table (SVY-21660 C): one row per request, with
	 * input/output/cache and the cache-read ratio, sorted by input descending so
	 * the most inefficient step is at the top (and highlighted). A caveat line
	 * states that opencode meters per request, so a per-tool figure is an
	 * attribution estimate.
	 */
	private static String requestTokenTable(RunMetrics m) {
		java.util.List<RunMetrics.RequestTokens> reqs = new java.util.ArrayList<>(m.requestTokens());
		RunMetrics.RequestTokens worst = m.mostInefficientRequest();
		reqs.sort((a, b) -> Long.compare(b.tokens() != null ? b.tokens().input() : 0,
				a.tokens() != null ? a.tokens().input() : 0));
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"section-title\">Per-request tokens (ranked by input)</div>"); //$NON-NLS-1$
		long base = m.baseInput();
		sb.append("<div class=\"muted\">Base input (re-sent every turn): ") //$NON-NLS-1$
				.append(base >= 0 ? Long.toString(base) : "unknown") //$NON-NLS-1$
				.append(". opencode meters per request - when a request makes several tool calls they share " //$NON-NLS-1$
						+ "that request's token figure, so a per-tool cost is an attribution estimate.</div>"); //$NON-NLS-1$
		sb.append("<table class=\"oc-props\"><tr><th>#</th><th>Input</th><th>Output</th>" //$NON-NLS-1$
				+ "<th>Cache read</th><th>Cache-read %</th><th>Tools / skill</th></tr>"); //$NON-NLS-1$
		for (RunMetrics.RequestTokens rt : reqs) {
			boolean isWorst = worst != null && rt.index() == worst.index();
			RunMetrics.TokenUsage t = rt.tokens();
			sb.append("<tr class=\"").append(isWorst ? "p-fail" : "p-pass").append("\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			sb.append("<td class=\"k\">").append(rt.index()).append(isWorst ? " \u26a0" : "").append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			sb.append("<td class=\"v\">").append(t != null ? t.input() : 0).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td class=\"v\">").append(t != null ? t.output() : 0).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td class=\"v\">").append(t != null ? t.cacheRead() : 0).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td class=\"v\">").append(String.format("%.1f%%", rt.cacheReadRatio() * 100d)) //$NON-NLS-1$ //$NON-NLS-2$
					.append(rt.noCaching() ? " (no caching - full context re-sent)" : "").append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			StringBuilder ts = new StringBuilder(String.join(", ", rt.toolNames())); //$NON-NLS-1$
			if (rt.skill() != null && !rt.skill().isBlank()) {
				ts.append(ts.length() > 0 ? " / " : "").append(rt.skill()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append("<td class=\"v\">").append(esc(ts.toString())).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append("</table>"); //$NON-NLS-1$
		return sb.toString();
	}

	/** A compact one-line metrics summary for a single trial (SVY-21660). */
	private static String metricsLine(RunMetrics m) {
		return "<div class=\"muted\">Metrics \u2014 tool calls: " + m.toolCalls() //$NON-NLS-1$
				+ ", tokens: " + esc(m.tokensLabel()) + ", Kiro: " + esc(m.kiroLabel()) //$NON-NLS-1$ //$NON-NLS-2$
				+ ", credits \u0394: " + esc(m.creditsLabel()) + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String toolCallsSection(SkillTestResult r) {
		// Tool calls are informational (the model may reorder/rephrase/add calls);
		// they no longer decide the result when outcome checks are present.
		// Collapsed by default so the outcome checks stay the focus.
		StringBuilder sb = new StringBuilder();
		sb.append("<details><summary class=\"section-summary\">Tool calls (informational)</summary>"); //$NON-NLS-1$
		sb.append("<div class=\"cols\">"); //$NON-NLS-1$
		// Expected column is never cost-annotated (metrics describe the actual run).
		sb.append(column("Expected", r.getExpectedCalls(), r.getActualCalls(), null)); //$NON-NLS-1$
		// Actual column only: annotate the first call of each request with its
		// approximate (request-shared) cost (SVY-21660).
		sb.append(column("Actual", r.getActualCalls(), r.getExpectedCalls(), r.getMetrics())); //$NON-NLS-1$
		sb.append("</div></details>"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Renders one outcome-assertion node as an expandable {@code <details>}: a
	 * pass/fail summary line, a table of expected-vs-actual property checks, and
	 * nested child nodes (recursively).
	 */
	private static String outcomeNode(PersistOutcomeChecker.Node node) {
		StringBuilder sb = new StringBuilder();
		String cls = node.pass() ? "oc-pass" : "oc-fail"; //$NON-NLS-1$ //$NON-NLS-2$
		String mark = node.pass() ? "\u2713" : "\u2717"; //$NON-NLS-1$ //$NON-NLS-2$
		boolean expand = !node.pass();
		sb.append("<details class=\"oc ").append(cls).append("\"").append(expand ? " open" : "").append(">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		sb.append("<summary><span class=\"oc-mark\">").append(mark).append("</span> "); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<span class=\"oc-label\">").append(esc(node.label())).append("</span> "); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<span class=\"oc-msg\">").append(esc(node.message())).append("</span></summary>"); //$NON-NLS-1$ //$NON-NLS-2$

		if (!node.props().isEmpty()) {
			sb.append("<table class=\"oc-props\"><tr><th>Property</th><th>Expected</th><th>Actual</th></tr>"); //$NON-NLS-1$
			for (PersistOutcomeChecker.PropCheck p : node.props()) {
				sb.append("<tr class=\"").append(p.pass() ? "p-pass" : "p-fail").append("\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				sb.append("<td class=\"k\">").append(esc(p.property())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td class=\"v\">").append(esc(p.expected())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td class=\"v\">").append(esc(p.actual())).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append("</table>"); //$NON-NLS-1$
		}
		for (PersistOutcomeChecker.Node child : node.children()) {
			sb.append(outcomeNode(child));
		}
		sb.append("</details>"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Renders one column of tool calls. Each call is marked as matched (present in
	 * {@code other}) or unmatched (missing/extra) by tool identity.
	 * <p>
	 * When {@code metrics} is non-{@code null} (the Actual column only), the
	 * <b>first</b> tool call of each request is annotated with that request's
	 * approximate, request-shared cost (SVY-21660): {@code "&asymp; N tok &middot;
	 * ~C cr (est; request-shared)"}, where {@code N} is the request's token total
	 * and {@code C} its input-proportional share of the scenario credit delta.
	 * Later calls in the same request carry no annotation, since tokens are metered
	 * per request and not per call. The Expected column passes {@code null} metrics
	 * and is never annotated.
	 */
	private static String column(String heading, List<McpToolCall> calls, List<McpToolCall> other, RunMetrics metrics) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"col\"><div class=\"col-head\">").append(esc(heading)).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		if (calls == null) {
			sb.append("<div class=\"muted\">(not captured)</div></div>"); //$NON-NLS-1$
			return sb.toString();
		}
		if (calls.isEmpty()) {
			sb.append("<div class=\"muted\">(no tool calls)</div></div>"); //$NON-NLS-1$
			return sb.toString();
		}
		// Per-request cost annotations (Actual column only; null/empty otherwise).
		// requestCostAnnotations.get(callIndex) is the label to attach to the FIRST
		// call of a request, or null for every other call.
		Map<Integer, String> firstCallCost = firstCallCostByIndex(metrics, calls.size());
		int callIndex = 0;
		for (McpToolCall call : calls) {
			boolean matched = other != null
					&& other.stream().anyMatch(o -> o.matchesIdentity(call.server(), call.tool()));
			sb.append("<div class=\"call ").append(matched ? "matched" : "unmatched").append("\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			sb.append("<div class=\"tool\">").append(esc(identity(call))); //$NON-NLS-1$
			String cost = firstCallCost.get(callIndex);
			if (cost != null) {
				sb.append("<span class=\"call-cost\">").append(esc(cost)).append("</span>"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append("</div>"); //$NON-NLS-1$
			callIndex++;
			if (!call.arguments().isEmpty()) {
				sb.append("<table class=\"args\">"); //$NON-NLS-1$
				for (Map.Entry<String, Object> arg : call.arguments().entrySet()) {
					sb.append("<tr><td class=\"k\">").append(esc(arg.getKey())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
					sb.append("<td class=\"v\">").append(esc(String.valueOf(arg.getValue()))).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				sb.append("</table>"); //$NON-NLS-1$
			}
			sb.append("</div>"); //$NON-NLS-1$
		}
		sb.append("</div>"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Maps call positions to a first-of-request cost annotation (SVY-21660), for
	 * the Actual column only.
	 * <p>
	 * opencode meters tokens <b>per request</b>, not per tool call, so there is no
	 * exact per-call figure: the annotation is attached to the <b>first</b> call of
	 * each request and reads {@code "&asymp; N tok &middot; ~C cr (est;
	 * request-shared)"}, where {@code N} is that request's token total
	 * ({@link RunMetrics.TokenUsage#total()}) and {@code C} is its
	 * input-proportional share of the scenario credit delta ({@code creditsDelta *
	 * requestInput / sumOfAllRequestInputs}). When the credit delta is unknown (or
	 * the total input is {@code 0}) the credit part degrades to {@code ~? cr}
	 * without fabricating a number.
	 * <p>
	 * <b>Mapping:</b> the actual calls and the per-request list are both in order,
	 * so the calls are walked while the requests are walked: each request consumes
	 * its {@link RunMetrics.RequestTokens#callCount()} calls (the exact per-request
	 * call count, nested Code-Mode calls included, carried from the transcript's
	 * {@code calls.size() - callsBefore}), and the first call consumed by a request
	 * carries its annotation. Mapping by the call count rather than the
	 * {@code toolNames()} list size is what makes the annotation render on real
	 * fan-out / Code-Mode runs, where the DB-sourced headline list has no names and
	 * nested Code-Mode names never line up with the flat Actual call list. A
	 * request with {@code callCount <= 0} (unknown count, or trailing calls not
	 * attributed to any request) is simply skipped: a blank annotation is always
	 * preferred over a wrong number.
	 *
	 * @param metrics   the run metrics, or {@code null} for no annotation (Expected
	 *                  column)
	 * @param callCount the number of actual tool calls rendered in this column
	 * @return a map from 0-based call index to its annotation; empty when there is
	 *         nothing to annotate
	 */
	private static Map<Integer, String> firstCallCostByIndex(RunMetrics metrics, int callCount) {
		if (metrics == null || metrics.requestTokens() == null || metrics.requestTokens().isEmpty()) {
			return Map.of();
		}
		List<String> perRequest = requestCostAnnotations(metrics);
		Map<Integer, String> byCallIndex = new java.util.HashMap<>();
		int callCursor = 0;
		for (int reqIdx = 0; reqIdx < metrics.requestTokens().size() && callCursor < callCount; reqIdx++) {
			RunMetrics.RequestTokens rt = metrics.requestTokens().get(reqIdx);
			// Map by the explicit per-request call count (incl. nested Code-Mode
			// calls), NOT the tool-name list size: the DB-sourced headline list has
			// no names on fan-out runs, and Code-Mode names never line up with the
			// flat Actual call list. callCount is set from the transcript's
			// calls.size()-callsBefore, so it matches the Actual column (SVY-21660).
			int consumed = rt.callCount();
			if (consumed <= 0) {
				// No calls attributable to this request (count unknown / fan-out blank):
				// cannot place its annotation unambiguously, so leave this request blank.
				continue;
			}
			// Annotate EVERY call of this turn with the turn's token figure (calls in
			// one turn share it - the label says "this turn"), not just the first, so
			// every tool-call row shows its cost.
			String label = perRequest.get(reqIdx);
			for (int i = 0; i < consumed && callCursor < callCount; i++, callCursor++) {
				byCallIndex.put(callCursor, label);
			}
		}
		return byCallIndex;
	}

	/**
	 * Builds the ordered per-request cost annotation strings (one per request, in
	 * request order), each {@code "&asymp; N tok &middot; ~C cr (est;
	 * request-shared)"} - or tokens-only with {@code ~? cr} when the scenario
	 * credit delta is unknown or the total request input is {@code 0}. The credit
	 * share is proportional to each request's input ({@code creditsDelta *
	 * requestInput / sumOfAllRequestInputs}).
	 *
	 * @param metrics the run metrics (must have a non-empty request list)
	 * @return one annotation per request, in request order
	 */
	private static List<String> requestCostAnnotations(RunMetrics metrics) {
		List<RunMetrics.RequestTokens> reqs = metrics.requestTokens();
		List<String> out = new java.util.ArrayList<>(reqs.size());
		for (RunMetrics.RequestTokens rt : reqs) {
			RunMetrics.TokenUsage t = rt.tokens();
			long tokens = t != null ? t.total() : 0L;
			// Simple + honest: show the turn's token figure on each call of that turn.
			// opencode meters per request/turn, so calls in one turn share it - hence
			// "this turn". No per-call credit (credits are a session figure, shown in
			// the Metrics block, not per call).
			out.add("\u2248 " + tokens + " tok (this turn)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return out;
	}

	private static String identity(McpToolCall call) {
		return (call.server() != null ? call.server() + "." : "") + call.tool(); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String statusClass(SkillTestResult.Status status) {
		return switch (status) {
		case PASS -> "s-pass"; //$NON-NLS-1$
		case FAIL -> "s-fail"; //$NON-NLS-1$
		case ERROR -> "s-error"; //$NON-NLS-1$
		case SKIPPED -> "s-skipped"; //$NON-NLS-1$
		};
	}

	/** Wraps body content in a styled HTML document. */
	private static String page(String body) {
		return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" //$NON-NLS-1$
				+ CSS + "</style></head><body>" + body + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The shared inline-CSS palette for the skill-test reports. Package-visible so
	 * the coverage report ({@code CoverageReportHtml}) can reuse the exact same
	 * look (SVY-21711 §3.4) - header status colours, collapsible {@code details},
	 * tables and muted text - instead of duplicating it.
	 */
	public static final String CSS = ""
			+ "body{font-family:Segoe UI,Arial,sans-serif;font-size:12px;margin:8px;color:#1e1e1e;background:#fff;}"
			+ ".header{padding:6px 8px;border-radius:4px;margin-bottom:8px;font-weight:600;}"
			+ ".header .status{font-weight:700;margin-right:6px;}"
			+ ".header .attempts{float:right;font-weight:400;opacity:.7;}"
			+ ".s-pass{background:#e6f4ea;color:#137333;}" + ".s-fail{background:#fce8e6;color:#c5221f;}"
			+ ".s-error{background:#fef7e0;color:#b06000;}" + ".s-skipped{background:#f1f3f4;color:#5f6368;}"
			+ ".error{background:#fce8e6;color:#c5221f;padding:6px 8px;border-radius:4px;margin-bottom:8px;white-space:pre-wrap;}"
			+ ".section-title{font-weight:600;margin:10px 0 4px;color:#5f6368;text-transform:uppercase;font-size:10px;letter-spacing:.5px;}"
			+ ".bubble.prompt{background:#e8f0fe;border:1px solid #d2e3fc;border-radius:8px;padding:8px 10px;white-space:pre-wrap;}"
			+ ".bubble.outcome-fail{background:#fce8e6;border:1px solid #f3c0bc;border-radius:8px;padding:8px 10px;white-space:pre-wrap;font-family:Consolas,monospace;font-size:11px;}"
			+ ".bubble.outcome-pass{background:#e6f4ea;border:1px solid #b7dfc3;border-radius:8px;padding:8px 10px;}"
			+ ".bubble.budget-warn{background:#fef7e0;border:1px solid #f9df8a;color:#8a6d00;border-radius:8px;padding:8px 10px;}"
			+ "summary.section-summary{font-weight:600;margin:10px 0 4px;color:#5f6368;text-transform:uppercase;font-size:10px;letter-spacing:.5px;cursor:pointer;}"
			+ "details.oc{border:1px solid #dadce0;border-radius:6px;margin:4px 0;padding:4px 8px;}"
			+ "details.oc.oc-pass{border-left:4px solid #137333;}"
			+ "details.oc.oc-fail{border-left:4px solid #c5221f;background:#fef7f7;}"
			+ "details.oc>summary{cursor:pointer;}" + ".oc-mark{font-weight:700;margin-right:4px;}"
			+ ".oc-pass>summary .oc-mark{color:#137333;}" + ".oc-fail>summary .oc-mark{color:#c5221f;}"
			+ ".oc-label{font-family:Consolas,monospace;font-weight:600;}"
			+ ".oc-msg{color:#5f6368;font-size:11px;margin-left:4px;}"
			+ ".oc-props{width:100%;border-collapse:collapse;margin:4px 0 4px 18px;}"
			+ ".oc-props th{text-align:left;color:#5f6368;font-size:10px;text-transform:uppercase;padding:1px 6px;border-bottom:1px solid #eee;}"
			+ ".oc-props td{vertical-align:top;padding:1px 6px;font-family:Consolas,monospace;font-size:11px;}"
			+ ".oc-props .k{color:#5f6368;white-space:nowrap;}"
			+ ".oc-props tr.p-fail td{background:#fce8e6;color:#c5221f;}" + ".oc-props tr.p-pass td.v{color:#137333;}"
			+ ".cols{display:flex;gap:8px;}" + ".col{flex:1;min-width:0;}"
			+ ".col-head{font-weight:600;margin-bottom:4px;}"
			+ ".call{border:1px solid #dadce0;border-radius:6px;padding:6px 8px;margin-bottom:6px;}"
			+ ".call.matched{border-left:4px solid #137333;}"
			+ ".call.unmatched{border-left:4px solid #c5221f;background:#fef7f7;}"
			+ ".call .tool{font-family:Consolas,monospace;font-weight:600;}"
			+ ".call-cost{float:right;color:#80868b;font-style:italic;font-weight:400;font-size:10px;margin-left:8px;}"
			+ ".args{width:100%;border-collapse:collapse;margin-top:4px;}"
			+ ".args td{vertical-align:top;padding:1px 4px;font-family:Consolas,monospace;font-size:11px;}"
			+ ".args .k{color:#5f6368;white-space:nowrap;}" + ".args .v{word-break:break-word;}"
			+ ".muted{color:#80868b;font-style:italic;}";

	/** Minimal HTML escaping for text nodes. */
	private static String esc(String s) {
		if (s == null) {
			return ""; //$NON-NLS-1$
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}
}

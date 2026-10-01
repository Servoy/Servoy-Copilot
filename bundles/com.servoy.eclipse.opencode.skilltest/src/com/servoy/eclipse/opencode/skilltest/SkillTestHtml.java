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
	private static String toolCallsSection(SkillTestResult r) {
		// Tool calls are informational (the model may reorder/rephrase/add calls);
		// they no longer decide the result when outcome checks are present.
		// Collapsed by default so the outcome checks stay the focus.
		StringBuilder sb = new StringBuilder();
		sb.append("<details><summary class=\"section-summary\">Tool calls (informational)</summary>"); //$NON-NLS-1$
		sb.append("<div class=\"cols\">"); //$NON-NLS-1$
		sb.append(column("Expected", r.getExpectedCalls(), r.getActualCalls())); //$NON-NLS-1$
		sb.append(column("Actual", r.getActualCalls(), r.getExpectedCalls())); //$NON-NLS-1$
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
	 */
	private static String column(String heading, List<McpToolCall> calls, List<McpToolCall> other) {
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
		for (McpToolCall call : calls) {
			boolean matched = other != null && other.stream()
					.anyMatch(o -> o.matchesIdentity(call.server(), call.tool()));
			sb.append("<div class=\"call ").append(matched ? "matched" : "unmatched").append("\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			sb.append("<div class=\"tool\">").append(esc(identity(call))).append("</div>"); //$NON-NLS-1$ //$NON-NLS-2$
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

	private static final String CSS = ""
			+ "body{font-family:Segoe UI,Arial,sans-serif;font-size:12px;margin:8px;color:#1e1e1e;background:#fff;}"
			+ ".header{padding:6px 8px;border-radius:4px;margin-bottom:8px;font-weight:600;}"
			+ ".header .status{font-weight:700;margin-right:6px;}"
			+ ".header .attempts{float:right;font-weight:400;opacity:.7;}"
			+ ".s-pass{background:#e6f4ea;color:#137333;}"
			+ ".s-fail{background:#fce8e6;color:#c5221f;}"
			+ ".s-error{background:#fef7e0;color:#b06000;}"
			+ ".s-skipped{background:#f1f3f4;color:#5f6368;}"
			+ ".error{background:#fce8e6;color:#c5221f;padding:6px 8px;border-radius:4px;margin-bottom:8px;white-space:pre-wrap;}"
			+ ".section-title{font-weight:600;margin:10px 0 4px;color:#5f6368;text-transform:uppercase;font-size:10px;letter-spacing:.5px;}"
			+ ".bubble.prompt{background:#e8f0fe;border:1px solid #d2e3fc;border-radius:8px;padding:8px 10px;white-space:pre-wrap;}"
			+ ".bubble.outcome-fail{background:#fce8e6;border:1px solid #f3c0bc;border-radius:8px;padding:8px 10px;white-space:pre-wrap;font-family:Consolas,monospace;font-size:11px;}"
			+ ".bubble.outcome-pass{background:#e6f4ea;border:1px solid #b7dfc3;border-radius:8px;padding:8px 10px;}"
			+ "summary.section-summary{font-weight:600;margin:10px 0 4px;color:#5f6368;text-transform:uppercase;font-size:10px;letter-spacing:.5px;cursor:pointer;}"
			+ "details.oc{border:1px solid #dadce0;border-radius:6px;margin:4px 0;padding:4px 8px;}"
			+ "details.oc.oc-pass{border-left:4px solid #137333;}"
			+ "details.oc.oc-fail{border-left:4px solid #c5221f;background:#fef7f7;}"
			+ "details.oc>summary{cursor:pointer;}"
			+ ".oc-mark{font-weight:700;margin-right:4px;}"
			+ ".oc-pass>summary .oc-mark{color:#137333;}"
			+ ".oc-fail>summary .oc-mark{color:#c5221f;}"
			+ ".oc-label{font-family:Consolas,monospace;font-weight:600;}"
			+ ".oc-msg{color:#5f6368;font-size:11px;margin-left:4px;}"
			+ ".oc-props{width:100%;border-collapse:collapse;margin:4px 0 4px 18px;}"
			+ ".oc-props th{text-align:left;color:#5f6368;font-size:10px;text-transform:uppercase;padding:1px 6px;border-bottom:1px solid #eee;}"
			+ ".oc-props td{vertical-align:top;padding:1px 6px;font-family:Consolas,monospace;font-size:11px;}"
			+ ".oc-props .k{color:#5f6368;white-space:nowrap;}"
			+ ".oc-props tr.p-fail td{background:#fce8e6;color:#c5221f;}"
			+ ".oc-props tr.p-pass td.v{color:#137333;}"
			+ ".cols{display:flex;gap:8px;}"
			+ ".col{flex:1;min-width:0;}"
			+ ".col-head{font-weight:600;margin-bottom:4px;}"
			+ ".call{border:1px solid #dadce0;border-radius:6px;padding:6px 8px;margin-bottom:6px;}"
			+ ".call.matched{border-left:4px solid #137333;}"
			+ ".call.unmatched{border-left:4px solid #c5221f;background:#fef7f7;}"
			+ ".call .tool{font-family:Consolas,monospace;font-weight:600;}"
			+ ".args{width:100%;border-collapse:collapse;margin-top:4px;}"
			+ ".args td{vertical-align:top;padding:1px 4px;font-family:Consolas,monospace;font-size:11px;}"
			+ ".args .k{color:#5f6368;white-space:nowrap;}"
			+ ".args .v{word-break:break-word;}"
			+ ".muted{color:#80868b;font-style:italic;}";

	/** Minimal HTML escaping for text nodes. */
	private static String esc(String s) {
		if (s == null) {
			return ""; //$NON-NLS-1$
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}
}

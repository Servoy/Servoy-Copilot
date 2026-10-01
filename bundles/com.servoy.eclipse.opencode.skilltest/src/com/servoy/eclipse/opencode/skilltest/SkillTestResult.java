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

/**
 * The result of running a single {@link Baseline}: an outcome, the number of
 * attempts made, and a diff (empty on PASS) describing baseline-vs-actual tool
 * calls on the best/last attempt.
 */
public final class SkillTestResult {

	/** The outcome of a single baseline run. */
	public enum Status {
		PASS, FAIL, ERROR, SKIPPED
	}

	/**
	 * One trial of a multi-trial run (SVY-21366: "run 3-5 trials and look at the
	 * distribution"). Carries that trial's own pass flag, structured outcome
	 * checks, JSUnit report and actual tool calls, so the view can render each
	 * trial separately.
	 *
	 * @param index        1-based trial number
	 * @param pass         whether this trial matched
	 * @param diff         this trial's divergence text (empty on pass)
	 * @param outcomeNodes this trial's structured outcome-check tree (may be null)
	 * @param actualCalls  this trial's actual tool calls (may be null)
	 * @param jsUnitReport this trial's JSUnit Markdown report (may be null)
	 * @param jsUnitPass   this trial's JSUnit pass flag (may be null)
	 */
	public record Trial(int index, boolean pass, String diff,
			java.util.List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> outcomeNodes,
			java.util.List<McpToolCall> actualCalls, String jsUnitReport, Boolean jsUnitPass) {
	}

	private final String baselineId;
	private final Status status;
	private final int attempts;
	private final String diff;
	private final String errorMessage;

	/** Per-trial results for a multi-trial run; empty for a single-trial run or reconstructed result. */
	private java.util.List<Trial> trials = java.util.List.of();

	/**
	 * The prompt that was sent to the AI (from the baseline), for display in the
	 * conversation view. May be {@code null} when reconstructed from persisted
	 * results.
	 */
	private String prompt;

	/**
	 * The golden (expected) transcript's tool calls, for side-by-side display. May
	 * be {@code null} when reconstructed from persisted results.
	 */
	private java.util.List<McpToolCall> expectedCalls;

	/**
	 * The actual transcript's tool calls from the best/last attempt, for
	 * side-by-side display. May be {@code null} when the run produced none (e.g.
	 * timeout) or when reconstructed from persisted results.
	 */
	private java.util.List<McpToolCall> actualCalls;

	/**
	 * The structured per-assertion outcome checks (expected vs actual persist
	 * properties), for the expandable Outcome checks panel. {@code null}/empty for
	 * transcript-only baselines or when reconstructed from persisted results.
	 */
	private java.util.List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> outcomeNodes;

	private SkillTestResult(String baselineId, Status status, int attempts, String diff, String errorMessage) {
		this.baselineId = baselineId;
		this.status = status;
		this.attempts = attempts;
		this.diff = diff;
		this.errorMessage = errorMessage;
	}

	public static SkillTestResult pass(String baselineId, int attempts) {
		return new SkillTestResult(baselineId, Status.PASS, attempts, "", null); //$NON-NLS-1$
	}

	public static SkillTestResult fail(String baselineId, int attempts, String diff) {
		return new SkillTestResult(baselineId, Status.FAIL, attempts, diff, null);
	}

	public static SkillTestResult error(String baselineId, int attempts, String errorMessage) {
		return new SkillTestResult(baselineId, Status.ERROR, attempts, "", errorMessage); //$NON-NLS-1$
	}

	public static SkillTestResult skipped(String baselineId, String reason) {
		return new SkillTestResult(baselineId, Status.SKIPPED, 0, "", reason); //$NON-NLS-1$
	}

	/**
	 * Attaches the conversation data (sent prompt, expected and actual tool calls)
	 * used by the view's transcript panel. Returns {@code this} for chaining.
	 *
	 * @param prompt        the prompt sent to the AI
	 * @param expectedCalls the golden (expected) tool calls
	 * @param actualCalls   the actual tool calls from the best/last attempt (may be
	 *                      {@code null})
	 * @return this result
	 */
	public SkillTestResult withTranscript(String prompt, java.util.List<McpToolCall> expectedCalls,
			java.util.List<McpToolCall> actualCalls) {
		this.prompt = prompt;
		this.expectedCalls = expectedCalls != null ? java.util.List.copyOf(expectedCalls) : null;
		this.actualCalls = actualCalls != null ? java.util.List.copyOf(actualCalls) : null;
		return this;
	}

	/** @return the prompt sent to the AI, or {@code null} if not captured */
	public String getPrompt() {
		return prompt;
	}

	/** @return the expected (golden) tool calls, or {@code null} if not captured */
	public java.util.List<McpToolCall> getExpectedCalls() {
		return expectedCalls;
	}

	/** @return the actual tool calls from the run, or {@code null} if not captured */
	public java.util.List<McpToolCall> getActualCalls() {
		return actualCalls;
	}

	/**
	 * Attaches the structured outcome-check results (expected vs actual per
	 * persist/property) for the expandable Outcome checks panel.
	 *
	 * @param nodes the per-assertion outcome nodes (may be {@code null})
	 * @return this result
	 */
	public SkillTestResult withOutcomeNodes(
			java.util.List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> nodes) {
		this.outcomeNodes = nodes != null ? java.util.List.copyOf(nodes) : null;
		return this;
	}

	/**
	 * @return the structured outcome checks, or {@code null} when the baseline is
	 *         transcript-only or the result was reconstructed from disk
	 */
	public java.util.List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> getOutcomeNodes() {
		return outcomeNodes;
	}

	/**
	 * Attaches the per-trial results of a multi-trial run.
	 *
	 * @param trials the trials, in run order (may be {@code null}/empty)
	 * @return this result
	 */
	public SkillTestResult withTrials(java.util.List<Trial> trials) {
		this.trials = trials != null ? java.util.List.copyOf(trials) : java.util.List.of();
		return this;
	}

	/** @return the per-trial results (never {@code null}; empty for a single-trial/reconstructed result) */
	public java.util.List<Trial> getTrials() {
		return trials;
	}

	/** @return the number of trials that passed */
	public long getPassCount() {
		return trials.stream().filter(Trial::pass).count();
	}

	/**
	 * The JSUnit verification report (formatted Markdown from
	 * {@code JSUnitRunnerService}), or {@code null} when the baseline declared no
	 * {@code verify.jsunit} or the result was reconstructed from disk.
	 */
	private String jsUnitReport;

	/** {@code TRUE}/{@code FALSE} JSUnit pass, or {@code null} when not run. */
	private Boolean jsUnitPass;

	/**
	 * Attaches the JSUnit behavioural-verification result (SVY-21366 §3.4b).
	 *
	 * @param report the formatted JSUnit Markdown report (may be {@code null})
	 * @param pass   the JSUnit pass flag, or {@code null} when not run
	 * @return this result
	 */
	public SkillTestResult withJsUnit(String report, Boolean pass) {
		this.jsUnitReport = report;
		this.jsUnitPass = pass;
		return this;
	}

	/** @return the JSUnit verification report, or {@code null} when none was run */
	public String getJsUnitReport() {
		return jsUnitReport;
	}

	/** @return the JSUnit pass flag, or {@code null} when no JSUnit verification ran */
	public Boolean getJsUnitPass() {
		return jsUnitPass;
	}

	public String getBaselineId() {
		return baselineId;
	}

	public Status getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getDiff() {
		return diff;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public boolean isPass() {
		return status == Status.PASS;
	}
}

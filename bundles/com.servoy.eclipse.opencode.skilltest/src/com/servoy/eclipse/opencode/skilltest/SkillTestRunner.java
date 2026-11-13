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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.opencode.Activator;
import com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker;
import com.servoy.j2db.persistence.Solution;

/**
 * Orchestrates the golden-file skill regression: for each {@link Baseline} it
 * ensures the embedded opencode server is up, replays the baseline's prompt in
 * a fresh session, exports the new session, and verifies the result. Modelled
 * on the JSUnit {@code JSUnitRunnerService}: shared product code invoked from
 * both the JUnit suite and the in-view actions.
 * <p>
 * Verification is <b>outcome-authoritative</b> (SVY-21366 "focus on the
 * outcome"): when a baseline declares expected outcomes
 * ({@code expect.persists}), the result is judged by
 * {@link PersistOutcomeChecker} against the real in-memory Servoy persist tree
 * of the active solution, and the transcript comparison is skipped. Only when a
 * baseline declares no expected outcomes does it fall back to the tolerant
 * {@link TranscriptComparator} over the MCP tool calls.
 * </p>
 */
public final class SkillTestRunner {

	private static final long SERVER_WAIT_MS = 120_000L;

	/**
	 * Instruction prepended to every skill-test session prompt so unattended
	 * replays never stall on a clarifying question. Scoped to the skill-test runner
	 * only - the interactive Servoy AI view is unaffected.
	 */
	private static final String NO_QUESTIONS_INSTRUCTION = "[skill-test mode] Run fully autonomously: do not ask the user any clarifying questions and do not wait for confirmation. Always proceed with the recommended/most-standard approach and complete the task end to end."; //$NON-NLS-1$

	/** How often to poll session status while waiting for a replay to finish. */
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

	/** Optional sink for progress/debug messages (e.g. the Servoy AI Console). */
	public interface Logger {
		void log(String message);
	}

	private final Logger logger;

	public SkillTestRunner() {
		this(msg -> {
			Activator activator = Activator.getInstance();
			if (activator != null) {
				activator.logToConsole(msg);
			}
		});
	}

	public SkillTestRunner(Logger logger) {
		this.logger = logger != null ? logger : msg -> {
		};
	}

	/**
	 * Runs every baseline and returns a result per baseline.
	 *
	 * @param baselines the baselines to run
	 * @return the results, in the same order
	 */
	public List<SkillTestResult> runAll(List<Baseline> baselines) {
		return runAll(baselines, () -> false);
	}

	/**
	 * Runs every baseline, stopping early when {@code cancelled} reports
	 * {@code true}. The in-flight replay is aborted and no further baselines are
	 * started; results gathered so far are returned.
	 *
	 * @param baselines the baselines to run
	 * @param cancelled supplies {@code true} when the run should stop
	 * @return the results gathered before completion or cancellation
	 */
	public List<SkillTestResult> runAll(List<Baseline> baselines, java.util.function.BooleanSupplier cancelled) {
		List<SkillTestResult> results = new ArrayList<>();
		if (baselines == null) {
			return results;
		}
		java.util.function.BooleanSupplier cancel = cancelled != null ? cancelled : () -> false;
		for (Baseline baseline : baselines) {
			if (cancel.getAsBoolean()) {
				logger.log("[skilltest] run cancelled - skipping remaining baselines"); //$NON-NLS-1$
				break;
			}
			results.add(runBaseline(baseline, cancel));
		}
		return results;
	}

	/**
	 * Runs a single baseline: replay prompt, export, verify. Passes if any attempt
	 * matches.
	 * 
	 * @param baseline the baseline to run
	 * @return the outcome
	 */
	public SkillTestResult runBaseline(Baseline baseline) {
		return runBaseline(baseline, () -> false);
	}

	/**
	 * Runs a single baseline using an EXPLICIT on-disk folder for the baseline's
	 * assets (verify scripts, etc.). Prefer this when the caller already knows the
	 * folder the baseline was loaded from (e.g. a CI
	 * {@code -Dservoy.skilltest.baselines} dir), because
	 * {@link #resolveBaselineFolder} otherwise recomputes the folder from
	 * {@link SkillTestPaths#baselinesRoot()} (the workspace path), which does NOT
	 * match when baselines live elsewhere - the cause of "verify script not found:
	 * verify/scopes/skilltest_verify.js" in CI.
	 *
	 * @param baseline       the baseline to run
	 * @param baselineFolder the baseline's folder on disk (verify-script source)
	 * @return the outcome
	 */
	public SkillTestResult runBaseline(Baseline baseline, java.io.File baselineFolder) {
		return runBaseline(baseline, () -> false, baselineFolder);
	}

	/**
	 * Runs a single baseline, cooperatively cancellable via {@code cancelled}.
	 *
	 * @param baseline  the baseline to run
	 * @param cancelled supplies {@code true} when the run should stop
	 * @return the outcome
	 */
	public SkillTestResult runBaseline(Baseline baseline, java.util.function.BooleanSupplier cancelled) {
		return runBaseline(baseline, cancelled, null);
	}

	/**
	 * @param baselineFolder explicit baseline folder, or {@code null} to resolve it
	 *                       from {@link SkillTestPaths#baselinesRoot()}
	 */
	public SkillTestResult runBaseline(Baseline baseline, java.util.function.BooleanSupplier cancelled,
			java.io.File baselineFolder) {
		java.util.function.BooleanSupplier cancel = cancelled != null ? cancelled : () -> false;
		String basePrompt = baseline.effectivePrompt();
		if (basePrompt == null || basePrompt.isBlank()) {
			return SkillTestResult.error(baseline.id(), 0, "no prompt available (empty promptOverride and export)"); //$NON-NLS-1$
		}
		// Skill-test replays run unattended, so the agent must never pause to ask the
		// user a clarifying question - it should proceed autonomously with the
		// recommended approach. This instruction is prepended only to the skill-test
		// session prompt here; it does NOT touch the shared opencode config, AGENTS.md,
		// or the interactive Servoy AI view (OpenCodeView), which open their own
		// sessions and must keep asking questions when appropriate.
		String prompt = NO_QUESTIONS_INSTRUCTION + "\n\n" + basePrompt; //$NON-NLS-1$

		int port = ensureServerReady();
		if (port <= 0) {
			return SkillTestResult.error(baseline.id(), 0, "opencode server did not become ready"); //$NON-NLS-1$
		}

		OpencodeHttpClient client = new OpencodeHttpClient(port);
		SessionExporter exporter = new SessionExporter(port);
		TranscriptComparator comparator = new TranscriptComparator(baseline);
		String effectiveAgent = resolveAgent(baseline);
		String effectiveModel = resolveModel(baseline);
		// Acceptance-criteria judge (SVY-21366): built once when the baseline declares
		// criteria, reused across trials. Grades the result in a separate session
		// (the judge model may equal the builder's effectiveModel), reading the built
		// form's sources.
		com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge judge = baseline.hasAcceptanceVerify()
				? com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge.ready(logger::log)
				: null;
		com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion judgeSubject = firstSubject(baseline.expected());
		String judgeSubjectName = judgeSubject != null ? judgeSubject.name() : null;
		String judgeSourceHint = judgeSubject != null ? judgeSubject.kind().sourceHint(judgeSubject.name()) : null;
		String judgeMockupPath = firstMockupPath(baseline);
		logger.log("[skilltest] " + baseline.id() + " replay agent=" + effectiveAgent //$NON-NLS-1$ //$NON-NLS-2$
				+ " model=" + effectiveModel); //$NON-NLS-1$

		List<McpToolCall> expectedCalls = baseline.goldenTranscript() != null
				? baseline.goldenTranscript().getToolCalls()
				: List.of();

		String lastDiff = "(no attempt produced a comparable transcript)"; //$NON-NLS-1$
		List<McpToolCall> lastActualCalls = null;
		List<PersistOutcomeChecker.Node> lastOutcomeNodes = null;
		JsUnitVerifier.Result lastJsUnit = null;
		int attempts = 0;
		// Run ALL configured trials and report the distribution (SVY-21366 /
		// skills guidance: "Run 3-5 trials per prompt and look at the distribution,
		// not one pass/fail"). Every trial runs even once one passes, so flakiness is
		// visible. The overall result is PASS only when EVERY trial matched; the kept
		// transcript/outcome favours the first failing trial (so the report shows what
		// diverged), else the last passing one. maxAttempts is clamped to at least 1.
		int maxAttempts = Math.max(1, baseline.maxAttempts());
		int passCount = 0;
		// Per-trial records for the distribution view, plus a kept representative
		// detail (prefer the first failing trial; else the last passing one).
		List<SkillTestResult.Trial> trials = new ArrayList<>();
		List<PersistOutcomeChecker.Node> keptOutcomeNodes = null;
		JsUnitVerifier.Result keptJsUnit = null;
		String keptDiff = null;
		boolean keptIsFailure = false;
		// Cost/efficiency metrics (SVY-21660): a reader for the local Kiro usage
		// snapshot, reused across trials, plus the kept representative trial's
		// metrics (selected the same way the kept detail is).
		RunMetrics keptMetrics = null;
		KiroUsageReader kiroReader = new KiroUsageReader();
		// Accurate per-scenario credit bracket (SVY-21660 B), built once per scenario.
		// The credit start is read ONCE, before the first prompt, so the bracket spans
		// the whole scenario's cost. All reads are DB-only, read-only, never throw into
		// the run, make no network call and read no token; absent driver/file ->
		// "unknown".
		KiroCreditReader creditReader = new KiroCreditReader();
		// Account used/limit come from kiro-usage.json (kiro.db has no account table,
		// confirmed against live data). creditsStart is read ONCE before the first
		// prompt; beforeStamp is the snapshot's freshness so the post-run read can
		// wait for the plugin to sync before taking creditsEnd (SVY-21660 live-data
		// follow-up).
		java.util.OptionalDouble creditsStart = creditReader.accountUsed();
		java.util.OptionalDouble creditsLimit = creditReader.accountLimit();
		java.util.OptionalLong beforeStamp = creditReader.snapshotStamp();
		logger.log("[skilltest] " + baseline.id() + " credits before (account used, estimate/shared account): " //$NON-NLS-1$ //$NON-NLS-2$
				+ (creditsStart.isPresent() ? Double.toString(creditsStart.getAsDouble()) : "unknown") //$NON-NLS-1$
				+ (creditsLimit.isPresent() ? " / limit " + creditsLimit.getAsDouble() : "")); //$NON-NLS-1$ //$NON-NLS-2$
		// The kept trial's session id and its per-request token list. The per-turn
		// token figures come from the HTTP export (SVY-21660 C): every assistant
		// message carries its own `tokens` object at the MESSAGE top level, so
		// SessionTranscript.fromExport is the single source and nothing has to outlive
		// the trial - each session is deleted in the trial's own finally, as it always
		// was.
		String keptSessionId = null;
		List<RunMetrics.RequestTokens> keptRequestTokens = List.of();
		java.util.OptionalDouble creditsEnd = java.util.OptionalDouble.empty();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			attempts = attempt;
			String sessionId = null;
			long attemptStart = System.nanoTime();
			try {
				logger.log("[skilltest] " + baseline.id() + " attempt " + attempt); //$NON-NLS-1$ //$NON-NLS-2$
				// @Before-style fixture reset: return the workspace to the golden's
				// starting state so the agent isn't blocked by its own prior output.
				new WorkspaceFixture(logger).reset(baseline.fixture());
				// Stage the baseline-owned input files (e.g. a specs/ mockup the prompt
				// refers to) into the agent's working directory AFTER the fixture reset
				// (which may delete files) and BEFORE the prompt, so the agent can read
				// them. Resolve the baseline folder the same way JSUnit verify does.
				if (!baseline.inputs().isEmpty()) {
					java.io.File inputsFolder = baselineFolder != null ? baselineFolder
							: resolveBaselineFolder(baseline);
					new WorkspaceInputs(logger).stage(baseline.inputs(), inputsFolder);
				}
				sessionId = client.createSession(baseline.title());
				// Fire-and-poll rather than one long blocking POST: an Orchestrator
				// baseline fans out into child sub-agent sessions and can run for
				// minutes, which a single held-open request cannot wait out.
				client.promptAsync(sessionId, prompt, effectiveAgent, effectiveModel);
				client.waitForCompletion(sessionId, POLL_INTERVAL, cancel);

				SessionTranscript actual;
				try {
					actual = exporter.export(sessionId);
					if (actual.getToolCalls().isEmpty()) {
						// fall back to the live message list (same shape as the export)
						actual = SessionTranscript.fromExport(client.getMessages(sessionId));
					}
				} catch (IOException | InterruptedException exportEx) {
					if (exportEx instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
					logger.log("[skilltest] export failed, using live messages: " + exportEx.getMessage()); //$NON-NLS-1$
					actual = SessionTranscript.fromExport(client.getMessages(sessionId));
				}
				lastActualCalls = actual.getToolCalls();

				// Cost/efficiency metrics (SVY-21660): the tool-call count is the
				// primary, always-reliable figure; the token usage is best-effort from
				// the export; the Kiro figure is an optional estimate read from the local
				// usage snapshot keyed by this session id. Read Kiro while the session
				// still exists, because the usage file entry keyed by session id may be
				// purged when the session is deleted at the end of this trial.
				// Per-request token list (SVY-21660 C): the export carries each
				// assistant message's own `tokens` object at the MESSAGE top level, so
				// the transcript's list is the single source - measured against a real
				// export dump, and the harness's sessions are never written to
				// opencode.db's session_v2/session_message at all, so there is no DB
				// path to prefer. Each entry already carries its own exact callCount
				// (calls.size() - callsBefore, incl. nested Code-Mode calls), the value
				// that drives the Actual column's first-call cost annotation.
				List<RunMetrics.RequestTokens> requestTokens = actual.getRequestTokens();
				RunMetrics trialMetrics = new RunMetrics(actual.getToolCalls().size(), actual.getTokens(),
						kiroReader.readForSession(sessionId).orElse(null), null, requestTokens);

				// End-of-scenario credit reading (SVY-21660 B): taken after completion
				// and while the session still exists. Overwritten each trial so the LAST
				// trial's post-completion reading is the bracket end. The kept session id +
				// its per-request list are captured here for the Δ fallback and headline.
				// The account figure lives in kiro-usage.json, which the plugin syncs only
				// periodically - so a bare read right after completion would still reflect
				// the pre-run snapshot and Δ would read ~0. Bounded-wait for the snapshot
				// to advance past the pre-run freshness stamp (or the used figure to move)
				// before reading the end, up to ~10s with the existing poll interval; a
				// never-advancing snapshot leaves creditsEnd empty ("sync pending").
				creditsEnd = waitForCreditSync(creditReader, beforeStamp, creditsStart, cancel);

				// The build is done. Re-read the active solution from disk BEFORE any
				// verification tier runs. While the agent writes a persist it can leave
				// a file transiently invalid (e.g. a two-step .frm edit that briefly has
				// a duplicate `customProperties` key); the file watcher may deserialize
				// it in that broken state and the model then holds a failed-load for a
				// persist whose file is valid again by now. A forced refresh + build
				// settle re-deserializes the now-valid files, so the outcome checker and
				// the LLM judge see what the agent actually produced, not a stale failed
				// load (SVY-21366 - the real cause behind a judge grading a built form
				// as absent).
				refreshActiveSolutionFromDisk();

				// Outcome-authoritative: when the baseline declares expected persists,
				// verify them against the real active-solution model and IGNORE the
				// transcript comparison (SVY-21366 "focus on the outcome"). Only fall
				// back to transcript comparison when no expected outcomes are declared.
				boolean match;
				String diff;
				List<PersistOutcomeChecker.Node> outcomeNodes = null;
				if (baseline.hasExpectedOutcomes()) {
					OutcomeCheckResult outcome = checkOutcomes(baseline);
					match = outcome.match();
					diff = outcome.diff();
					outcomeNodes = outcome.nodes();
				} else if (baseline.hasAcceptanceVerify()) {
					// Judge-authoritative baseline: no persist/transcript gate - the LLM
					// judge below is the sole verdict. Start matched; the judge may fail it.
					match = true;
					diff = ""; //$NON-NLS-1$
				} else {
					TranscriptComparator.Result result = comparator.compare(actual);
					match = result.match();
					diff = result.diff();
				}

				// Behavioural tier (SVY-21366 §3.4b): after the structural/transcript
				// check, optionally inject + run the baseline's JSUnit tests. A run
				// passes only if BOTH the outcome check and (a required) JSUnit pass.
				JsUnitVerifier.Result jsUnit = null;
				if (baseline.hasJsUnitVerify()) {
					java.io.File vf = baselineFolder != null ? baselineFolder : resolveBaselineFolder(baseline);
					jsUnit = new JsUnitVerifier(logger).verify(baseline, vf);
					boolean jsUnitBlocks = baseline.jsUnitVerify().required() && !jsUnit.pass();
					if (jsUnitBlocks) {
						match = false;
						diff = (diff == null ? "" : diff) //$NON-NLS-1$
								+ "\n--- JSUnit verification failed ---\n" + jsUnit.report(); //$NON-NLS-1$
					}
				}

				// Acceptance-criteria tier (SVY-21366): an automated LLM judge (a model
				// forced to differ from the builder) reviews the built form's sources
				// against the baseline's natural-language criteria. A run passes only if
				// the (required) judge also passes. Robust to layout variation the
				// structural check cannot tolerate.
				if (baseline.hasAcceptanceVerify()) {
					Baseline.AcceptanceVerify av = baseline.acceptanceVerify();
					com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge.Verdict verdict = judge != null
							? judge.judge(judgeSubjectName, judgeSourceHint, judgeMockupPath, av.criteria(),
									av.judgeModel(), effectiveModel, cancel)
							: com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge.Verdict
									.error("acceptance judge unavailable (opencode server not ready)", null); //$NON-NLS-1$
					if (av.required() && !verdict.pass()) {
						match = false;
						diff = (diff == null ? "" : diff) //$NON-NLS-1$
								+ "\n--- Acceptance criteria (LLM judge) FAILED ---\n" //$NON-NLS-1$
								+ renderVerdict(verdict);
					}
				}

				// Record this trial for the distribution view.
				trials.add(new SkillTestResult.Trial(attempt, match, match ? "" : diff, outcomeNodes, //$NON-NLS-1$
						lastActualCalls, jsUnit != null ? jsUnit.report() : null,
						jsUnit != null ? Boolean.valueOf(jsUnit.pass()) : null, trialMetrics));

				if (match) {
					passCount++;
					logger.log("[skilltest] " + baseline.id() + " trial " + attempt + "/" + maxAttempts + " PASS"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					// Keep a passing trial's detail only if no failing trial has been
					// captured yet (a failure's detail is more useful in the report).
					if (!keptIsFailure) {
						keptOutcomeNodes = outcomeNodes;
						keptJsUnit = jsUnit;
						keptDiff = "";
						keptMetrics = trialMetrics;
						keptSessionId = sessionId;
						keptRequestTokens = requestTokens;
					}
				} else {
					lastOutcomeNodes = outcomeNodes;
					lastDiff = diff;
					lastJsUnit = jsUnit;
					logger.log("[skilltest] " + baseline.id() + " trial " + attempt + "/" + maxAttempts //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ " diverged:\n" + diff); //$NON-NLS-1$
					// The first failure's detail wins and is not overwritten by later trials.
					if (!keptIsFailure) {
						keptOutcomeNodes = outcomeNodes;
						keptJsUnit = jsUnit;
						keptDiff = diff;
						keptMetrics = trialMetrics;
						keptSessionId = sessionId;
						keptRequestTokens = requestTokens;
						keptIsFailure = true;
					}
				}
			} catch (IOException ioe) {
				long elapsedSeconds = (System.nanoTime() - attemptStart) / 1_000_000_000L;
				lastDiff = "error on attempt " + attempt + " after " + elapsedSeconds + "s: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ ioe.getClass().getSimpleName() + ": " + ioe.getMessage(); //$NON-NLS-1$
				logger.log("[skilltest] " + baseline.id() + " " + lastDiff); //$NON-NLS-1$ //$NON-NLS-2$
				if (sessionId != null) {
					client.abort(sessionId);
				}
			} catch (InterruptedException ie) {
				// Cancellation (Stop) or a real interrupt: don't set the thread's
				// interrupt flag on a user Stop, just report and let runAll halt.
				String reason = cancel.getAsBoolean() ? "cancelled" : "interrupted"; //$NON-NLS-1$ //$NON-NLS-2$
				if (!cancel.getAsBoolean()) {
					Thread.currentThread().interrupt();
				}
				return SkillTestResult.error(baseline.id(), attempt, reason)
						.withTranscript(prompt, expectedCalls, lastActualCalls).withAgent(effectiveAgent);
			} finally {
				if (sessionId != null) {
					client.deleteSession(sessionId);
				}
			}
		}
		// All trials ran. Report the distribution: PASS only when every trial matched.
		String distribution = passCount + "/" + maxAttempts + " trials passed"; //$NON-NLS-1$ //$NON-NLS-2$
		logger.log("[skilltest] " + baseline.id() + " " + distribution); //$NON-NLS-1$ //$NON-NLS-2$
		// Choose the JSUnit detail to attach: the kept trial's, else the last trial's,
		// else none. Build report + pass as separate Boolean/String locals so a null
		// JSUnit (a baseline without verify.jsunit, like this one) stays null - a mixed
		// boolean/Boolean ternary would unbox null and throw (SVY-21366 NPE at
		// withJsUnit: "Cannot invoke java.lang.Boolean.booleanValue()").
		JsUnitVerifier.Result jsUnitForReport = keptJsUnit != null ? keptJsUnit : lastJsUnit;
		String jsUnitReport = jsUnitForReport != null ? jsUnitForReport.report() : null;
		Boolean jsUnitPass = jsUnitForReport != null ? Boolean.valueOf(jsUnitForReport.pass()) : null;

		// Per-turn token figures (SVY-21660 C) come from the kept trial's HTTP export,
		// captured inside the loop: each assistant message carries its own `tokens`
		// object at the MESSAGE top level, so no post-run read is needed and the
		// session can be (and is) deleted per trial. An empty list simply means
		// "unknown" (no per-call annotation).
		logger.log("[skilltest] " + baseline.id() + " per-request tokens from the export: " //$NON-NLS-1$ //$NON-NLS-2$
				+ keptRequestTokens.size() + " request(s)" //$NON-NLS-1$
				+ (keptRequestTokens.isEmpty() ? " (unknown - the export carried no per-message tokens)" : "")); //$NON-NLS-1$ //$NON-NLS-2$

		// Accurate per-scenario credits (SVY-21660 B): Δused = end - start when both
		// present and end >= start; otherwise fall back to the kept trial's
		// per-session figure; otherwise "unknown" (null). Logged with the
		// estimate/shared-account caveat. The headline RunMetrics carries this Δ plus
		// the kept trial's per-request token list (C) alongside the kept trial's
		// tool-call/token/Kiro figures.
		Double creditsDelta = computeCreditsDelta(creditsStart, creditsEnd, creditReader, keptSessionId);
		logger.log("[skilltest] " + baseline.id() + " credits before (account used): " //$NON-NLS-1$ //$NON-NLS-2$
				+ (creditsStart.isPresent() ? Double.toString(creditsStart.getAsDouble()) : "unknown") //$NON-NLS-1$
				+ "; after (account used): " //$NON-NLS-1$
				+ (creditsEnd.isPresent() ? Double.toString(creditsEnd.getAsDouble()) : "unknown (sync pending)") //$NON-NLS-1$
				+ "; Δused (estimate/shared account): " //$NON-NLS-1$
				+ (creditsDelta != null ? Double.toString(creditsDelta) : "unknown")); //$NON-NLS-1$
		Double beforeVal = creditsStart.isPresent() ? Double.valueOf(creditsStart.getAsDouble()) : null;
		Double afterVal = creditsEnd.isPresent() ? Double.valueOf(creditsEnd.getAsDouble()) : null;
		Double limitVal = creditsLimit.isPresent() ? Double.valueOf(creditsLimit.getAsDouble()) : null;
		RunMetrics headlineMetrics = withScenarioCost(keptMetrics, creditsDelta, keptRequestTokens, beforeVal, afterVal,
				limitVal);

		if (passCount == maxAttempts) {
			return SkillTestResult.pass(baseline.id(), attempts).withTranscript(prompt, expectedCalls, lastActualCalls)
					.withOutcomeNodes(keptOutcomeNodes).withJsUnit(jsUnitReport, jsUnitPass).withTrials(trials)
					.withAgent(effectiveAgent).withMetrics(headlineMetrics);
		}
		String failDiff = distribution + (keptDiff != null && !keptDiff.isBlank() ? "\n" + keptDiff : ""); //$NON-NLS-1$ //$NON-NLS-2$
		return SkillTestResult.fail(baseline.id(), attempts, failDiff)
				.withTranscript(prompt, expectedCalls, lastActualCalls)
				.withOutcomeNodes(keptOutcomeNodes != null ? keptOutcomeNodes : lastOutcomeNodes)
				.withJsUnit(jsUnitReport, jsUnitPass).withTrials(trials).withAgent(effectiveAgent)
				.withMetrics(headlineMetrics);
	}

	/**
	 * Computes the accurate per-scenario credits consumed (SVY-21660 B): the
	 * start/end account-used delta when both readings are present and
	 * {@code end >= start}; otherwise the kept trial's per-session figure;
	 * otherwise {@code null} ("unknown"). Never throws - a read failure yields
	 * {@code null}.
	 *
	 * @param start         the account-used reading before the first prompt
	 * @param end           the account-used reading after completion
	 * @param reader        the credit reader (for the per-session fallback)
	 * @param keptSessionId the kept trial's session id (fallback key, may be
	 *                      {@code null})
	 * @return the Δused, or {@code null} for "unknown"
	 */
	private static Double computeCreditsDelta(java.util.OptionalDouble start, java.util.OptionalDouble end,
			KiroCreditReader reader, String keptSessionId) {
		if (start.isPresent() && end.isPresent() && end.getAsDouble() >= start.getAsDouble()) {
			return Double.valueOf(end.getAsDouble() - start.getAsDouble());
		}
		java.util.OptionalDouble perSession = reader.creditsForSession(keptSessionId);
		return perSession.isPresent() ? Double.valueOf(perSession.getAsDouble()) : null;
	}

	/**
	 * Folds the scenario-level cost (Δused credits + the kept trial's per-request
	 * token list) onto the kept trial's {@link RunMetrics} to make the headline
	 * figure (SVY-21660 B/C). When the kept metrics are {@code null} (nothing
	 * captured) a bare metrics carrier is built so the Δ/list still surface.
	 *
	 * @param kept          the kept trial's metrics (may be {@code null})
	 * @param creditsDelta  the scenario Δused, or {@code null} for "unknown"
	 * @param requestTokens the kept trial's per-request token list (may be empty)
	 * @return the headline metrics, or {@code null} only when nothing at all is
	 *         known
	 */
	private static RunMetrics withScenarioCost(RunMetrics kept, Double creditsDelta,
			List<RunMetrics.RequestTokens> requestTokens, Double creditsBefore, Double creditsAfter,
			Double creditsLimit) {
		List<RunMetrics.RequestTokens> rt = requestTokens != null ? requestTokens : List.of();
		boolean haveBracket = creditsBefore != null || creditsAfter != null || creditsLimit != null;
		if (kept == null) {
			if (creditsDelta == null && rt.isEmpty() && !haveBracket) {
				return null;
			}
			return new RunMetrics(0, null, null, creditsDelta, rt, creditsBefore, creditsAfter, creditsLimit);
		}
		// Prefer the explicitly passed list: it is the kept trial's export-derived
		// per-request list (SVY-21660 C), captured in the loop from each assistant
		// message's own top-level `tokens`. The kept metrics' own list is the same
		// data and is only used when the passed one is empty.
		List<RunMetrics.RequestTokens> merged = !rt.isEmpty() ? rt : kept.requestTokens();
		return new RunMetrics(kept.toolCalls(), kept.tokens(), kept.kiro(), creditsDelta, merged, creditsBefore,
				creditsAfter, creditsLimit);
	}

	/**
	 * Bounded wait for the Kiro plugin to sync the account snapshot
	 * ({@code kiro-usage.json}) after a scenario (SVY-21660 live-data follow-up).
	 * The account {@code used} figure lives in the JSON, which the plugin writes
	 * only periodically, so a bare read right after completion still reflects the
	 * pre-run state. This polls the snapshot's freshness stamp until it advances
	 * past {@code beforeStamp} (or the account {@code used} moves off
	 * {@code before}), up to ~10s using the existing {@link #POLL_INTERVAL} and
	 * respecting cancellation - never a raw unbounded sleep. When the snapshot
	 * never advances within the budget, {@link java.util.OptionalDouble#empty()} is
	 * returned so the Δ reads "sync pending / unknown".
	 *
	 * @param reader      the credit reader
	 * @param beforeStamp the snapshot freshness read before the first prompt
	 * @param before      the account used read before the first prompt
	 * @param cancel      cancellation supplier
	 * @return the post-sync account used, or empty when the sync did not land
	 */
	private java.util.OptionalDouble waitForCreditSync(KiroCreditReader reader, java.util.OptionalLong beforeStamp,
			java.util.OptionalDouble before, java.util.function.BooleanSupplier cancel) {
		long budgetMs = 10_000L;
		long stepMs = Math.max(200L, POLL_INTERVAL.toMillis() / 4);
		long deadline = System.nanoTime() + budgetMs * 1_000_000L;
		java.util.OptionalDouble latest = reader.accountUsed();
		while (System.nanoTime() < deadline) {
			if (cancel.getAsBoolean()) {
				return latest;
			}
			java.util.OptionalLong nowStamp = reader.snapshotStamp();
			boolean stampAdvanced = nowStamp.isPresent()
					&& (beforeStamp.isEmpty() || nowStamp.getAsLong() > beforeStamp.getAsLong());
			java.util.OptionalDouble nowUsed = reader.accountUsed();
			boolean usedMoved = nowUsed.isPresent() && before.isPresent()
					&& nowUsed.getAsDouble() != before.getAsDouble();
			if (stampAdvanced || usedMoved) {
				return nowUsed.isPresent() ? nowUsed : latest;
			}
			latest = nowUsed.isPresent() ? nowUsed : latest;
			try {
				Thread.sleep(stepMs);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return latest;
			}
		}
		// The snapshot never advanced within the budget: the end reading is unreliable
		// (still the pre-run snapshot), so report "sync pending" rather than a false Δ.
		return java.util.OptionalDouble.empty();
	}

	/**
	 * Resolves the on-disk folder of a baseline (where its {@code verify/} scripts
	 * live), by id under the baselines root. Returns {@code null} if it cannot be
	 * resolved (the JSUnit verifier then reports the missing-script error).
	 *
	 * @param baseline the baseline whose folder to resolve
	 * @return the baseline folder, or {@code null}
	 */
	private static java.io.File resolveBaselineFolder(Baseline baseline) {
		java.io.File root = SkillTestPaths.baselinesRoot();
		if (root == null) {
			return null;
		}
		java.io.File folder = new java.io.File(root, baseline.id());
		return folder.isDirectory() ? folder : null;
	}

	/**
	 * Resolves the agent to replay with: the sidecar {@code agent} if set,
	 * otherwise the agent recorded in the golden export ({@code info.agent}).
	 * Falling back to the golden agent matters because the golden was produced by a
	 * specific agent (e.g. {@code Orchestrator}); replaying with no agent would run
	 * the server default and diverge.
	 *
	 * @param baseline the baseline being replayed
	 * @return the agent name, or {@code null} to let the server pick its default
	 */
	private static String resolveAgent(Baseline baseline) {
		if (baseline.agent() != null && !baseline.agent().isBlank()) {
			return baseline.agent();
		}
		return baseline.goldenTranscript() != null ? baseline.goldenTranscript().getAgent() : null;
	}

	/**
	 * Resolves the model to replay with: the sidecar {@code model} if set,
	 * otherwise the {@code providerID/modelID} recorded in the golden export.
	 *
	 * @param baseline the baseline being replayed
	 * @return the model spec, or {@code null} to let the server pick its default
	 */
	private static String resolveModel(Baseline baseline) {
		if (baseline.model() != null && !baseline.model().isBlank()) {
			return baseline.model();
		}
		return baseline.goldenTranscript() != null ? baseline.goldenTranscript().getModel() : null;
	}

	/**
	 * @return the name of the first {@code kind:form} expected assertion - the form
	 *         the acceptance judge should inspect ({@code forms/<name>.frm}) - or
	 *         {@code null} when the baseline declares no form assertion
	 */
	/**
	 * The first expected root-by-name persist (form, value list, relation, menu or
	 * media) the baseline declares - its name AND kind, so the judge can be handed
	 * both the name and a concrete file path to read. {@code null} when the
	 * baseline declares no named root persist (the judge then discovers what the
	 * run produced). Not form-specific. Package-private + list-based so it is
	 * unit-testable without a whole {@link Baseline}.
	 *
	 * @param expected the baseline's expected outcomes (may be {@code null}/empty)
	 * @return the first root-by-name persist, or {@code null} when none
	 */
	static com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion firstSubject(
			List<com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion> expected) {
		if (expected == null) {
			return null;
		}
		for (com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion a : expected) {
			if (a != null && a.kind() != null && a.kind().isRootByName() && a.name() != null && !a.name().isBlank()) {
				return a;
			}
		}
		return null;
	}

	/**
	 * Re-reads the active solution's files from disk and waits for the Servoy build
	 * to settle, so verification sees the persists the agent actually produced -
	 * not a stale state the file watcher captured while a file was transiently
	 * invalid mid-edit (SVY-21366). Best-effort: logs and returns on any failure
	 * rather than aborting the run.
	 */
	private void refreshActiveSolutionFromDisk() {
		try {
			IServoyModel model = ServoyModelFinder.getServoyModel();
			ServoyProject active = model != null ? model.getActiveProject() : null;
			if (active == null || active.getProject() == null) {
				return;
			}
			active.getProject().refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE,
					new org.eclipse.core.runtime.NullProgressMonitor());
			// Bounded settle: a wedged builder must not hang the whole run (SVY-21366,
			// build #59 hung on an unbounded build-settle join). Reuse JsUnitVerifier's
			// timed helper so the wait is capped and a stuck build is reported, not awaited
			// forever.
			boolean settled = JsUnitVerifier.joinBuildFamilies(180_000L);
			logger.log("[skilltest] refreshed active solution '" + active.getProject().getName() //$NON-NLS-1$
					+ (settled ? "' from disk and settled build jobs before verifying" //$NON-NLS-1$
							: "' from disk; build did not settle within 180s, verifying anyway")); //$NON-NLS-1$
		} catch (RuntimeException | org.eclipse.core.runtime.CoreException ex) {
			logger.log("[skilltest] refresh before verify failed (continuing): " + ex.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * @return the working-directory-relative path of the first HTML mockup input
	 *         (its {@code to}, or {@code from} with a leading {@code inputs/}
	 *         stripped when {@code to} is blank), or {@code null} when the baseline
	 *         stages no {@code .html}/{@code .htm} input
	 */
	private static String firstMockupPath(Baseline baseline) {
		for (Baseline.Input in : baseline.inputs()) {
			String from = in.from();
			if (from == null) {
				continue;
			}
			String lower = from.toLowerCase();
			if (!lower.endsWith(".html") && !lower.endsWith(".htm")) { //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			if (in.to() != null && !in.to().isBlank()) {
				return in.to();
			}
			return from.startsWith("inputs/") ? from.substring("inputs/".length()) : from; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return null;
	}

	/** Renders a judge verdict as a short block for the run diff/report. */
	private static String renderVerdict(com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge.Verdict verdict) {
		StringBuilder sb = new StringBuilder();
		sb.append(verdict.summary()).append('\n');
		for (com.servoy.eclipse.opencode.skilltest.outcome.AcceptanceJudge.CriterionResult r : verdict.results()) {
			sb.append("  [").append(r.pass() ? "PASS" : "FAIL").append("] ").append(r.criterion()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			if (r.reason() != null && !r.reason().isBlank()) {
				sb.append(" - ").append(r.reason()); //$NON-NLS-1$
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/**
	 * The outcome of a persist-model outcome check (match + diff + structured
	 * nodes).
	 */
	private record OutcomeCheckResult(boolean match, String diff, List<PersistOutcomeChecker.Node> nodes) {
	}

	/**
	 * Checks the baseline's expected outcomes against the active solution's real
	 * in-memory persist tree (SVY-21366). Resolves the active editing solution via
	 * the Servoy model and delegates to {@link PersistOutcomeChecker}.
	 *
	 * @param baseline the baseline whose {@code expect.persists} to verify
	 * @return the match result + a human-readable diff
	 */
	private OutcomeCheckResult checkOutcomes(Baseline baseline) {
		IServoyModel model = null;
		try {
			model = ServoyModelFinder.getServoyModel();
		} catch (RuntimeException | LinkageError ex) {
			return new OutcomeCheckResult(false, "- could not obtain the Servoy model: " + ex.getClass().getSimpleName() //$NON-NLS-1$
					+ ": " + ex.getMessage() + "\n", List.of()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (model == null) {
			return new OutcomeCheckResult(false, "- Servoy model not available (ServoyModelFinder returned null)\n", //$NON-NLS-1$
					List.of());
		}
		ServoyProject active = model.getActiveProject();
		if (active == null) {
			return new OutcomeCheckResult(false, "- no active Servoy project/solution to verify outcomes against\n", //$NON-NLS-1$
					List.of());
		}
		Solution solution = active.getEditingSolution();
		if (solution == null) {
			return new OutcomeCheckResult(false, "- active project '" + active.getProject().getName() //$NON-NLS-1$
					+ "' has no editing solution yet\n", List.of()); //$NON-NLS-1$
		}
		logger.log("[skilltest] verifying outcomes against active solution '" + solution.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
		PersistOutcomeChecker checker = new PersistOutcomeChecker(solution);
		PersistOutcomeChecker.Result result = checker.check(baseline.expected());
		return new OutcomeCheckResult(result.match(), result.diff(), result.nodes());
	}

	/**
	 * Renders results as Markdown (as JSUnit results are), including per-baseline
	 * PASS/FAIL/ERROR, attempts, and diffs.
	 *
	 * @param results the results to format
	 * @return a Markdown report
	 */
	public String formatResults(List<SkillTestResult> results) {
		int pass = 0;
		int fail = 0;
		int error = 0;
		int skipped = 0;
		for (SkillTestResult r : results) {
			switch (r.getStatus()) {
			case PASS -> pass++;
			case FAIL -> fail++;
			case ERROR -> error++;
			case SKIPPED -> skipped++;
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("# Servoy AI skill test results\n\n"); //$NON-NLS-1$
		sb.append(String.format("**%d passed, %d failed, %d error, %d skipped** (of %d)%n%n", //$NON-NLS-1$
				pass, fail, error, skipped, results.size()));
		for (SkillTestResult r : results) {
			sb.append("## ").append(r.getBaselineId()).append(" \u2014 ").append(r.getStatus()).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("attempts: ").append(r.getAttempts()).append('\n'); //$NON-NLS-1$
			if (r.getErrorMessage() != null && !r.getErrorMessage().isBlank()) {
				sb.append("error: ").append(r.getErrorMessage()).append('\n'); //$NON-NLS-1$
			}
			if (r.getDiff() != null && !r.getDiff().isBlank()) {
				sb.append("```\n").append(r.getDiff()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private int ensureServerReady() {
		Activator activator = Activator.getInstance();
		if (activator == null) {
			return -1;
		}
		if (!activator.isServerReady()) {
			activator.ensureServerStarting();
			try {
				activator.waitForServer(SERVER_WAIT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return -1;
			}
		}
		return activator.isServerReady() ? activator.getServerPort() : -1;
	}
}

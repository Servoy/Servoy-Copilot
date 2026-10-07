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
	 * Runs a single baseline, cooperatively cancellable via {@code cancelled}.
	 *
	 * @param baseline  the baseline to run
	 * @param cancelled supplies {@code true} when the run should stop
	 * @return the outcome
	 */
	public SkillTestResult runBaseline(Baseline baseline, java.util.function.BooleanSupplier cancelled) {
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
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			attempts = attempt;
			String sessionId = null;
			long attemptStart = System.nanoTime();
			try {
				logger.log("[skilltest] " + baseline.id() + " attempt " + attempt); //$NON-NLS-1$ //$NON-NLS-2$
				// @Before-style fixture reset: return the workspace to the golden's
				// starting state so the agent isn't blocked by its own prior output.
				new WorkspaceFixture(logger).reset(baseline.fixture());
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
					jsUnit = new JsUnitVerifier(logger).verify(baseline, resolveBaselineFolder(baseline));
					boolean jsUnitBlocks = baseline.jsUnitVerify().required() && !jsUnit.pass();
					if (jsUnitBlocks) {
						match = false;
						diff = (diff == null ? "" : diff) //$NON-NLS-1$
								+ "\n--- JSUnit verification failed ---\n" + jsUnit.report(); //$NON-NLS-1$
					}
				}

				// Record this trial for the distribution view.
				trials.add(new SkillTestResult.Trial(attempt, match, match ? "" : diff, outcomeNodes, //$NON-NLS-1$
						lastActualCalls, jsUnit != null ? jsUnit.report() : null,
						jsUnit != null ? Boolean.valueOf(jsUnit.pass()) : null));

				if (match) {
					passCount++;
					logger.log("[skilltest] " + baseline.id() + " trial " + attempt + "/" + maxAttempts + " PASS"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					// Keep a passing trial's detail only if no failing trial has been
					// captured yet (a failure's detail is more useful in the report).
					if (!keptIsFailure) {
						keptOutcomeNodes = outcomeNodes;
						keptJsUnit = jsUnit;
						keptDiff = "";
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
						.withTranscript(prompt, expectedCalls, lastActualCalls);
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

		if (passCount == maxAttempts) {
			return SkillTestResult.pass(baseline.id(), attempts)
					.withTranscript(prompt, expectedCalls, lastActualCalls)
					.withOutcomeNodes(keptOutcomeNodes)
					.withJsUnit(jsUnitReport, jsUnitPass)
					.withTrials(trials);
		}
		String failDiff = distribution + (keptDiff != null && !keptDiff.isBlank() ? "\n" + keptDiff : ""); //$NON-NLS-1$ //$NON-NLS-2$
		return SkillTestResult.fail(baseline.id(), attempts, failDiff)
				.withTranscript(prompt, expectedCalls, lastActualCalls)
				.withOutcomeNodes(keptOutcomeNodes != null ? keptOutcomeNodes : lastOutcomeNodes)
				.withJsUnit(jsUnitReport, jsUnitPass)
				.withTrials(trials);
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

	/** The outcome of a persist-model outcome check (match + diff + structured nodes). */
	private record OutcomeCheckResult(boolean match, String diff,
			List<PersistOutcomeChecker.Node> nodes) {
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
			return new OutcomeCheckResult(false,
					"- could not obtain the Servoy model: " + ex.getClass().getSimpleName() //$NON-NLS-1$
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

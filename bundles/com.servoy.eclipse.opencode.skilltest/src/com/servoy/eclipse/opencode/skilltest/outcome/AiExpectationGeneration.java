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

package com.servoy.eclipse.opencode.skilltest.outcome;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;
import com.servoy.eclipse.opencode.skilltest.Baseline;

/**
 * UI glue that offers "Generate expectations with AI" and runs
 * {@link ExpectationLlmGenerator} <b>off the UI thread in a background
 * {@link Job}</b>, returning the robust assertions through a callback - the
 * single entry point both the import/confirm flow and the <i>Edit expected
 * outcomes</i> action use (SVY-21366).
 * <p>
 * The tester is asked once whether to run the AI pass:
 * </p>
 * <ul>
 * <li><b>Yes</b> - a cancelable background Job calls the embedded opencode LLM
 * (create session, prompt, poll to idle; seconds to a minute). Eclipse stays
 * fully responsive and the Job can be cancelled from the Progress view. When it
 * finishes, the robust assertions are delivered on the UI thread.</li>
 * <li><b>No</b> - no LLM call; the raw captured assertions are used
 * immediately. They are still passed through {@link #sanitize} first, so the
 * result is a <em>valid, working</em> baseline (the inference
 * {@value OutcomeInference#TYPE_NAME_PLACEHOLDER} placeholders are removed) -
 * just literal/strict rather than loosened into SQL-{@code LIKE} patterns. "No"
 * therefore always produces something usable, never a baseline full of
 * placeholders.</li>
 * </ul>
 * <p>
 * Whatever comes back - the LLM's rewrite or the plain capture on any failure /
 * cancel - is always {@link #sanitize sanitized} before it is handed to the
 * caller, so a silent LLM failure that falls back to the capture can never leave
 * an unusable {@value OutcomeInference#TYPE_NAME_PLACEHOLDER} placeholder in the
 * baseline either.
 * </p>
 */
public final class AiExpectationGeneration {

	private AiExpectationGeneration() {
		// no instances
	}

	/**
	 * Runs the AI baseline-authoring agent ({@link VerificationPlanAgent}) in a
	 * cancelable background {@link Job} and delivers its proposed
	 * {@link VerificationPlan} to {@code onResult} <b>on the UI thread</b>
	 * (SVY-21366). The call returns immediately; {@code onResult} is invoked later.
	 * <p>
	 * The agent reads the export + built artifacts + mockup itself and decides
	 * which of the three check kinds (expect.persists / acceptanceCriteria /
	 * jsunit) fit this run. Its drafted {@code expect.persists} is then
	 * <b>loosened</b> by {@link ExpectationLlmGenerator} (the
	 * {@code skilltest-expectations} skill): the agent-chosen styleClass/text
	 * leaves - which drift run-to-run - become anchored SQL-{@code LIKE} patterns,
	 * while the acceptance-criteria and JSUnit checks are left untouched. On any
	 * failure (no server, unparseable answer) the agent degrades to a persists-only
	 * plan inferred offline and the loosening is skipped, so this always delivers a
	 * usable plan - never {@code null}.
	 * </p>
	 *
	 * @param shell      the parent shell (may be {@code null})
	 * @param exportFile the imported export JSON (never {@code null})
	 * @param mockupFile the UI mockup HTML file the run reproduces, or {@code null}
	 *                   - its name is passed to the agent and its HTML to the
	 *                   persist loosener
	 * @param logger     a diagnostic sink; may be {@code null}
	 * @param onResult   receives the proposed plan on the UI thread
	 */
	public static void chooseAndPlan(Shell shell, File exportFile, File mockupFile,
			VerificationPlanAgent.Logger logger, Consumer<VerificationPlan> onResult) {
		String mockupPath = mockupFile != null ? mockupFile.getName() : null;
		String mockupHtml = readMockup(mockupFile);
		AtomicReference<VerificationPlan> result = new AtomicReference<>(VerificationPlanAgent.fallback(exportFile));
		Job job = new Job("Analyzing the session to propose skill-test checks\u2026") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Analyzing the session to propose skill-test checks\u2026", //$NON-NLS-1$
						IProgressMonitor.UNKNOWN);
				try {
					// 1. The authoring agent decides WHICH checks fit and drafts each
					// (persists / acceptanceCriteria / jsunit). On any failure analyze()
					// has already returned the offline persists-only fallback.
					VerificationPlanAgent agent = VerificationPlanAgent.ready(logger);
					VerificationPlan plan = agent != null ? agent.analyze(exportFile, mockupPath, monitor::isCanceled)
							: result.get();
					// 2. Loosen the DRAFTED persists (the `skilltest-expectations` skill):
					// the agent's expect.persists is still literal - agent-chosen
					// styleClass/text leaves drift run-to-run - so turn the volatile
					// leaves into anchored SQL-LIKE patterns before the human reviews.
					// Leaves acceptanceCriteria/jsunit untouched; a failure keeps the
					// literal persists (SVY-21366).
					if (plan.hasPersists() && !monitor.isCanceled()) {
						ExpectationLlmGenerator loosener = ExpectationLlmGenerator.ready(logger::log);
						if (loosener != null) {
							java.util.List<OutcomeAssertion> loosened = loosener.generate(plan.persists(), mockupHtml,
									monitor::isCanceled);
							plan = new VerificationPlan(loosened, plan.acceptance(), plan.jsunit(), plan.rationale());
						}
					}
					result.set(plan);
					return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
				} finally {
					monitor.done();
				}
			}
		};
		job.setUser(true);
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				VerificationPlan delivered = result.get();
				Display display = Display.getDefault();
				if (display == null || display.isDisposed()) {
					return;
				}
				display.asyncExec(() -> {
					if (shell != null && shell.isDisposed()) {
						return;
					}
					onResult.accept(delivered);
				});
			}
		});
		job.schedule();
	}

	/**
	 * Asks the tester whether to run the AI pass, runs it (if asked) in a
	 * cancelable background Job, and delivers the result to {@code onResult}
	 * <b>on the UI thread</b>. The call returns immediately; {@code onResult} is
	 * invoked later for the AI path and synchronously for the "No" / empty path.
	 * <p>
	 * The result handed to {@code onResult} is always {@link #sanitize sanitized}
	 * and never {@code null} when {@code captured} is not, so the caller can open
	 * the editor / write the baseline straight from it.
	 * </p>
	 *
	 * @param shell      the parent shell (may be {@code null})
	 * @param captured   the literal captured assertions (never {@code null})
	 * @param mockupFile the mockup HTML file the forms reproduce, or {@code null}
	 * @param logger     a diagnostic sink; may be {@code null}
	 * @param onResult   receives the final assertions on the UI thread
	 */
	public static void chooseAndGenerate(Shell shell, List<OutcomeAssertion> captured, File mockupFile,
			ExpectationLlmGenerator.Logger logger, Consumer<List<OutcomeAssertion>> onResult) {
		if (captured == null || captured.isEmpty()) {
			onResult.accept(captured);
			return;
		}
		boolean go = MessageDialog.openQuestion(shell, "Generate expectations with AI", //$NON-NLS-1$
				"Ask the AI to turn the captured form(s) into robust expectations " //$NON-NLS-1$
						+ "(replace component-type placeholders, loosen agent-chosen styleClass/text into LIKE patterns, " //$NON-NLS-1$
						+ "keep typeName/disabled/structure)?\n\n" //$NON-NLS-1$
						+ "Yes runs the AI in the background (cancelable, Eclipse stays usable).\n" //$NON-NLS-1$
						+ "No uses the raw captured structure as-is.\n\n" //$NON-NLS-1$
						+ "You can still edit the result before saving."); //$NON-NLS-1$
		if (!go) {
			// No AI pass: use the raw capture, but still drop the unusable
			// <replace-with-component-type> placeholders so the baseline is valid.
			onResult.accept(sanitize(captured));
			return;
		}
		runInJob(shell, captured, mockupFile, logger, onResult);
	}

	/**
	 * Schedules the LLM generation in a cancelable background {@link Job} and, when
	 * it finishes, delivers the sanitized result to {@code onResult} on the UI
	 * thread. On cancel or any failure the sanitized capture is delivered instead.
	 */
	private static void runInJob(Shell shell, List<OutcomeAssertion> captured, File mockupFile,
			ExpectationLlmGenerator.Logger logger, Consumer<List<OutcomeAssertion>> onResult) {
		String mockupHtml = readMockup(mockupFile);
		AtomicReference<List<OutcomeAssertion>> result = new AtomicReference<>(captured);
		Job job = new Job("Generating robust skill-test expectations with AI\u2026") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Generating robust expectations with AI\u2026", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
				try {
					ExpectationLlmGenerator gen = ExpectationLlmGenerator.ready(logger);
					if (gen == null) {
						// server not available: keep the capture (sanitized on delivery)
						return Status.OK_STATUS;
					}
					result.set(gen.generate(captured, mockupHtml, monitor::isCanceled));
					return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
				} finally {
					monitor.done();
				}
			}
		};
		job.setUser(true);
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				// Always sanitize whatever we end up with (AI rewrite, or the capture
				// kept on cancel / unavailable / failure), then deliver on the UI thread.
				List<OutcomeAssertion> delivered = sanitize(result.get());
				Display display = Display.getDefault();
				if (display == null || display.isDisposed()) {
					return;
				}
				display.asyncExec(() -> {
					if (shell != null && shell.isDisposed()) {
						return;
					}
					onResult.accept(delivered);
				});
			}
		});
		job.schedule();
	}

	/**
	 * Resolves the baseline's declared mockup file, if any: the first
	 * {@code inputs} entry whose {@code from} ends in {@code .html}/{@code .htm},
	 * resolved against the baseline folder. Returns {@code null} when the baseline
	 * declares no HTML input or the folder is unknown.
	 *
	 * @param baseline       the baseline (may be {@code null})
	 * @param baselineFolder the baseline folder on disk (may be {@code null})
	 * @return the mockup file, or {@code null}
	 */
	public static File defaultMockup(Baseline baseline, File baselineFolder) {
		if (baseline == null || baselineFolder == null) {
			return null;
		}
		for (Baseline.Input in : baseline.inputs()) {
			String from = in.from();
			if (from == null) {
				continue;
			}
			String lower = from.toLowerCase();
			if (lower.endsWith(".html") || lower.endsWith(".htm")) { //$NON-NLS-1$ //$NON-NLS-2$
				File f = new File(baselineFolder, from);
				if (f.isFile()) {
					return f;
				}
			}
		}
		return null;
	}

	/**
	 * Deterministically turns a raw capture into a <b>valid</b> expectation by
	 * dropping every {@value OutcomeInference#TYPE_NAME_PLACEHOLDER} placeholder:
	 * a {@code typeName} matcher whose expected value is the placeholder is removed
	 * (the component is still identified by its other props, e.g.
	 * {@code dataProviderID}). Applied recursively to nested children. Everything
	 * else - names, props, structure, the {@code less} block - is carried over
	 * unchanged.
	 * <p>
	 * This is what makes the "No" (skip AI) path, and the capture-kept fallback of
	 * the AI path, produce something usable rather than a baseline that would
	 * never match because of the literal {@code <replace-with-component-type>}
	 * string.
	 * </p>
	 *
	 * @param assertions the assertions to sanitize (may be {@code null})
	 * @return a new, sanitized list (never {@code null} when the input is not)
	 */
	public static List<OutcomeAssertion> sanitize(List<OutcomeAssertion> assertions) {
		if (assertions == null) {
			return null;
		}
		List<OutcomeAssertion> out = new ArrayList<>(assertions.size());
		for (OutcomeAssertion a : assertions) {
			out.add(sanitizeOne(a));
		}
		return out;
	}

	private static OutcomeAssertion sanitizeOne(OutcomeAssertion a) {
		if (a == null) {
			return null;
		}
		Map<String, ArgMatcher> props = stripPlaceholderProps(a.props());
		List<OutcomeAssertion> children = sanitize(a.children());
		boolean propsChanged = props != null;
		boolean childrenChanged = children != null && !children.equals(a.children());
		if (!propsChanged && !childrenChanged) {
			return a;
		}
		return new OutcomeAssertion(a.kind(), a.name(), a.scope(), a.exists(),
				propsChanged ? props : a.props(),
				childrenChanged ? children : a.children(),
				a.structure(), a.less());
	}

	/**
	 * @return a copy of {@code props} with any placeholder {@code typeName} matcher
	 *         removed, or {@code null} when nothing needed removing
	 */
	private static Map<String, ArgMatcher> stripPlaceholderProps(Map<String, ArgMatcher> props) {
		if (props == null || props.isEmpty()) {
			return null;
		}
		ArgMatcher typeName = props.get("typeName"); //$NON-NLS-1$
		if (typeName == null || !isPlaceholder(typeName.getExpected())) {
			return null;
		}
		Map<String, ArgMatcher> copy = new LinkedHashMap<>(props);
		copy.remove("typeName"); //$NON-NLS-1$
		return copy;
	}

	private static boolean isPlaceholder(Object expected) {
		return expected != null && OutcomeInference.TYPE_NAME_PLACEHOLDER.equals(String.valueOf(expected));
	}

	private static String readMockup(File mockupFile) {
		if (mockupFile == null || !mockupFile.isFile()) {
			return null;
		}
		try {
			return java.nio.file.Files.readString(mockupFile.toPath());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}

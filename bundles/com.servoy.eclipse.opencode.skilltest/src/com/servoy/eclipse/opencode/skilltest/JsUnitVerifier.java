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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.core.resources.IProject;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Behavioural verification tier for a baseline (SVY-21366 §3.4b): after the
 * skill run and the {@code expect.persists} outcome check, this injects the
 * baseline-owned JSUnit test scripts into the active solution and runs them via
 * the existing {@link JSUnitRunnerService}.
 * <p>
 * The key design point is that the tests belong to the <b>baseline</b>, not the
 * solution: an {@code empty}-source baseline has no test scope of its own, so the
 * verify scripts live in the baseline folder and are copied into the active
 * solution project here (into the project-relative path each declares, e.g.
 * {@code scopes/skilltest_verify.js}). They are discarded when the workspace is
 * cleaned before the next baseline. Injection happens <b>after</b> the skill run
 * so the skill never sees or depends on the test harness.
 * </p>
 * <p>
 * {@link JSUnitRunnerService} formats via {@code Display.syncExec}, so this must
 * be called on the runner's background thread (never the UI thread) - which is
 * where {@link SkillTestRunner} already runs.
 * </p>
 */
final class JsUnitVerifier {

	/** Outcome of a JSUnit verification: pass flag + the formatted Markdown report. */
	record Result(boolean pass, String report) {
	}

	private final SkillTestRunner.Logger logger;

	JsUnitVerifier(SkillTestRunner.Logger logger) {
		this.logger = logger;
	}

	/**
	 * Injects the baseline's verify scripts (if any) into the active solution,
	 * runs the declared JSUnit scope/method, and returns pass + report. The pass
	 * flag reflects the JSUnit outcome only; {@link Baseline.JsUnitVerify#required()}
	 * handling (whether a JSUnit failure fails the baseline) is applied by the
	 * caller.
	 *
	 * @param baseline      the baseline being verified (must declare
	 *                      {@code verify.jsunit})
	 * @param baselineFolder the baseline's folder on disk (source of the scripts)
	 * @return the verification result
	 */
	Result verify(Baseline baseline, File baselineFolder) {
		Baseline.JsUnitVerify v = baseline.jsUnitVerify();
		if (v == null) {
			return new Result(true, ""); //$NON-NLS-1$
		}
		ServoyProject active = resolveActiveServoyProject();
		if (active == null || active.getEditingSolution() == null) {
			return new Result(false, "- JSUnit verify: no active Servoy solution to run against\n"); //$NON-NLS-1$
		}
		String injectedScope;
		try {
			injectedScope = injectScopeFiles(v.scripts(), baselineFolder, active.getProject());
			// Build + settle so the Servoy builder parses the new root .js into the
			// solution model (registering the scope + its test_ methods) before the
			// JSUnit launch reads that model.
			active.getProject().build(org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,
					new NullProgressMonitor());
			settleBuildJobs();
		} catch (Exception e) {
			return new Result(false, "- JSUnit verify: could not inject test script(s): " + e.getMessage() + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		// If the baseline declared a scope run it, else run the scope we just created
		// (so an empty-source solution runs exactly the injected tests).
		String scope = v.scope() != null && !v.scope().isBlank() && !"ALL".equalsIgnoreCase(v.scope()) //$NON-NLS-1$
				? v.scope()
				: (injectedScope != null ? injectedScope : "ALL"); //$NON-NLS-1$
		logger.log("[skilltest] running JSUnit verify scope=" + scope //$NON-NLS-1$
				+ (v.method() != null ? " method=" + v.method() : "")); //$NON-NLS-1$ //$NON-NLS-2$
		JSUnitRunnerService runner = new JSUnitRunnerService();
		String report;
		try {
			report = v.method() != null && !v.method().isBlank()
					? runner.runTestMethod(v.method(), scope, v.timeoutSeconds())
					: runner.runTests(scope, v.timeoutSeconds());
		} catch (Exception | LinkageError runEx) {
			// The JSUnit SmartClient may fail to start (e.g. getClientInfo() null when
			// the solution references a DB datasource that is not configured, or the
			// app server is not up). Catch it so it reports as a JSUnit FAIL instead
			// of crashing with a modal dialog that blocks headless/CI runs.
			String msg = runEx.getClass().getSimpleName() + ": " + runEx.getMessage(); //$NON-NLS-1$
			logger.log("[skilltest] JSUnit verify crashed: " + msg); //$NON-NLS-1$
			return new Result(false, "JSUnit SmartClient failed to start: " + msg); //$NON-NLS-1$
		}
		boolean pass = isPass(report);
		logger.log("[skilltest] JSUnit verify " + (pass ? "PASS" : "FAIL")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		// Running JSUnit brings the DLTK "Script Unit Test" view to the front; bring
		// the Skill Tests view back on top so the run stays the focus.
		bringSkillTestViewToTop();
		return new Result(pass, report);
	}

	/**
	 * Re-activates the Servoy AI Skill Tests view on the UI thread, so the DLTK
	 * testing view that JSUnit forces to the front does not stay on top. Best
	 * effort: no-op when there is no workbench/active page (headless CI).
	 */
	private static void bringSkillTestViewToTop() {
		try {
			org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
			if (display == null || display.isDisposed()) {
				return;
			}
			display.asyncExec(() -> {
				try {
					org.eclipse.ui.IWorkbench wb = org.eclipse.ui.PlatformUI.getWorkbench();
					if (wb == null) {
						return;
					}
					org.eclipse.ui.IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
					org.eclipse.ui.IWorkbenchPage page = win != null ? win.getActivePage() : null;
					if (page == null) {
						return;
					}
					org.eclipse.ui.IViewPart view = page.findView(
							"com.servoy.eclipse.opencode.skilltest.SkillTestView"); //$NON-NLS-1$
					if (view != null) {
						page.bringToTop(view);
					}
				} catch (RuntimeException ignore) {
					// workbench not available / view not open - leave focus as-is
				}
			});
		} catch (RuntimeException | LinkageError ignore) {
			// no UI (headless) - nothing to re-focus
		}
	}

	/**
	 * Injects each baseline verify script as a Servoy <b>global scope file in the
	 * solution project ROOT</b> (e.g. {@code skilltest_verify.js}), which is exactly
	 * how Servoy stores a global scope: a {@code <scope>.js} directly under the
	 * solution project. The Servoy builder then parses it into the in-memory
	 * solution model (registering the scope + its {@code test_} methods) that the
	 * JSUnit client reads — and it shows up under <b>Scopes</b> in the Solution
	 * Explorer.
	 * <p>
	 * The earlier attempt wrote the file under a {@code scopes/} subfolder, which is
	 * NOT a recognized global-scope location, so it never registered as a scope and
	 * JSUnit found no tests ("no solution?"). Global scopes live in the project
	 * root, named {@code <scopeName>.js}.
	 *
	 * @return the scope name the tests were injected into (the first script's file
	 *         base name), or {@code null} when there were no scripts to inject
	 */
	private String injectScopeFiles(List<String> scripts, File baselineFolder, IProject solutionProject)
			throws Exception {
		if (scripts == null || scripts.isEmpty()) {
			return null; // run tests already present in the (folder/git) solution
		}
		String firstScope = null;
		for (String rel : scripts) {
			File src = new File(baselineFolder, rel);
			if (!src.isFile()) {
				throw new java.io.IOException("verify script not found: " + src); //$NON-NLS-1$
			}
			// The scope name is the file's base name; the file must sit in the project
			// root as "<scopeName>.js" to be recognized as a global scope.
			String base = src.getName();
			if (base.toLowerCase().endsWith(".js")) { //$NON-NLS-1$
				base = base.substring(0, base.length() - ".js".length()); //$NON-NLS-1$
			}
			String rootFileName = base + ".js"; //$NON-NLS-1$
			String content = Files.readString(src.toPath(), StandardCharsets.UTF_8);
			writeRootFile(solutionProject, rootFileName, content);
			logger.log("[skilltest] injected scope file '" + rootFileName //$NON-NLS-1$
					+ "' into solution root '" + solutionProject.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
			if (firstScope == null) {
				firstScope = base;
			}
		}
		solutionProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		return firstScope;
	}

	/** Writes/overwrites a UTF-8 file directly in the project root. */
	private static void writeRootFile(IProject project, String fileName, String content)
			throws org.eclipse.core.runtime.CoreException {
		ResourcesPlugin.getWorkspace().run((org.eclipse.core.resources.IWorkspaceRunnable) monitor -> {
			org.eclipse.core.resources.IFile file = project.getFile(fileName);
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			if (file.exists()) {
				file.setContents(new java.io.ByteArrayInputStream(bytes), true, true, monitor);
			} else {
				file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
			}
		}, new NullProgressMonitor());
	}

	/**
	 * Maps a baseline-relative script path to a display name by stripping a leading
	 * {@code verify/} namespacing segment and any {@code scopes/} prefix. Used by
	 * the view's JSUnit editor.
	 */
	static String toSolutionRelativePath(String baselineRelative) {
		String p = baselineRelative.replace('\\', '/');
		if (p.startsWith("verify/")) { //$NON-NLS-1$
			p = p.substring("verify/".length()); //$NON-NLS-1$
		}
		return p;
	}

	/** Waits for auto/manual build jobs to finish after injecting the test script. */
	private static void settleBuildJobs() {
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		try {
			jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
			jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new NullProgressMonitor());
		} catch (org.eclipse.core.runtime.OperationCanceledException | InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static ServoyProject resolveActiveServoyProject() {
		IServoyModel model;
		try {
			model = ServoyModelFinder.getServoyModel();
		} catch (RuntimeException | LinkageError ex) {
			return null;
		}
		return model != null ? model.getActiveProject() : null;
	}

	/**
	 * Reads the pass/fail from the {@link JSUnitRunnerService} Markdown report,
	 * which formats results as a table
	 * {@code | Passed | Failed | Errors | Ignored |} followed by a values row like
	 * {@code | **1** | **1** | **0** | **0** |} and, on failures, a
	 * {@code **Failed / Error tests:**} section with {@code FAIL <name>} lines.
	 * <p>
	 * The run passes only when it is not a runner-level error, contains the results
	 * header, the Failed and Errors counts are both zero, and there is no
	 * {@code FAIL }/{@code ERROR } test line. Conservative: anything unparseable is
	 * treated as NOT passing.
	 */
	static boolean isPass(String report) {
		if (report == null || report.isBlank()) {
			return false;
		}
		String r = report.trim();
		if (r.startsWith("Error")) { //$NON-NLS-1$
			return false;
		}
		if (!r.contains("JSUnit Test Results")) { //$NON-NLS-1$
			return false;
		}
		// A per-test failure/error line is an immediate fail (covers the
		// "**Failed / Error tests:**" section with "FAIL <name>" / "ERROR <name>").
		if (java.util.regex.Pattern.compile("(?m)^\\s*(FAIL|ERROR)\\b").matcher(r).find()) { //$NON-NLS-1$
			return false;
		}
		// Parse the counts row: | Passed | Failed | Errors | Ignored | then a row of
		// four numbers (possibly wrapped in ** markdown bold). Fail if Failed>0 or
		// Errors>0.
		java.util.regex.Matcher row = java.util.regex.Pattern
				.compile("\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|") //$NON-NLS-1$
				.matcher(r);
		if (row.find()) {
			int failed = Integer.parseInt(row.group(2));
			int errors = Integer.parseInt(row.group(3));
			return failed == 0 && errors == 0;
		}
		// No parseable counts row and no FAIL/ERROR line: fall back to the old
		// word-based heuristic (any "N failures/errors" phrase fails).
		String lower = r.toLowerCase();
		if (lower.matches("(?s).*\\b([1-9]\\d*)\\s+(failure|failures|error|errors)\\b.*")) { //$NON-NLS-1$
			return false;
		}
		return true;
	}
}

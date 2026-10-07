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

		// A baseline may inject its own test script, run test files that already exist in the
		// solution, or BOTH (SVY-21366). Inject first (so the injected scope registers in the
		// model), then run the injected scope and every existing-script scope in one aggregated
		// pass. The two lists are de-duped by scope so an entry that names the injected script
		// is not run twice.
		int effectiveTimeout = v.effectiveTimeoutSeconds();

		// 1. Inject the baseline-owned script(s), if any.
		String injectedScope = null;
		if (!v.scripts().isEmpty()) {
			try {
				injectedScope = injectScopeFiles(v.scripts(), baselineFolder, active.getProject());
				// Build + settle so the Servoy builder parses the new root .js into the
				// solution model (registering the scope + its test_ methods) before the
				// JSUnit launch reads that model.
				active.getProject().build(org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,
						new NullProgressMonitor());
				settleBuildJobs();
			} catch (Exception e) {
				return new Result(false,
						"- JSUnit verify: could not inject test script(s): " + e.getMessage() + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		// 2. Build the ordered, de-duplicated list of scopes to run.
		//    - the injected scope (the declared scope, or the scope the injected file created);
		//    - each existing solution test file mapped to its scope.
		java.util.LinkedHashSet<String> scopes = new java.util.LinkedHashSet<>();
		if (!v.scripts().isEmpty()) {
			String injectRun = v.scope() != null && !v.scope().isBlank() && !"ALL".equalsIgnoreCase(v.scope()) //$NON-NLS-1$
					? v.scope()
					: (injectedScope != null ? injectedScope : "ALL"); //$NON-NLS-1$
			if (injectRun != null && !injectRun.isBlank()) {
				scopes.add(injectRun);
			}
		}
		java.util.List<String> unmappable = new java.util.ArrayList<>();
		for (String rel : v.existingScripts()) {
			String s = scopeForSolutionPath(rel);
			if (s == null) {
				unmappable.add(rel);
			} else {
				scopes.add(s); // LinkedHashSet de-dupes against the injected scope
			}
		}
		if (scopes.isEmpty() && unmappable.isEmpty()) {
			// Nothing injected and nothing existing: fall back to running everything, preserving
			// the previous empty-source behaviour.
			scopes.add("ALL"); //$NON-NLS-1$
		}

		// 3. Run each scope, aggregating. Pass only if every scope passes.
		JSUnitRunnerService runner = new JSUnitRunnerService();
		StringBuilder report = new StringBuilder();
		boolean allPass = unmappable.isEmpty();
		for (String rel : unmappable) {
			report.append("- JSUnit verify: cannot map '").append(rel) //$NON-NLS-1$
					.append("' to a runnable scope (expected a root <scope>.js or forms/<form>.js)\n"); //$NON-NLS-1$
		}
		// A single scope keeps the historical single-report shape; multiple scopes get a header
		// per scope so each result is identifiable.
		boolean multi = scopes.size() > 1 || !unmappable.isEmpty();
		DebugConfirmationGuard guard = DebugConfirmationGuard.suppress(logger);
		try {
			for (String scope : scopes) {
				logger.log("[skilltest] running JSUnit verify scope=" + scope //$NON-NLS-1$
						+ (v.method() != null ? " method=" + v.method() : "") //$NON-NLS-1$ //$NON-NLS-2$
						+ " timeout=" + effectiveTimeout + "s" //$NON-NLS-1$ //$NON-NLS-2$
						+ (effectiveTimeout != v.timeoutSeconds()
								? " (includes warmup from " + v.warmupTimeoutSeconds() + "s)" //$NON-NLS-1$ //$NON-NLS-2$
								: "")); //$NON-NLS-1$
				String one;
				try {
					one = v.method() != null && !v.method().isBlank()
							? runner.runTestMethod(v.method(), scope, effectiveTimeout)
							: runner.runTests(scope, effectiveTimeout);
				} catch (Exception | LinkageError runEx) {
					// The JSUnit SmartClient may fail to start (e.g. getClientInfo() null when the
					// solution references a DB datasource that is not configured, or the app server
					// is not up). Catch it so it reports as a JSUnit FAIL instead of crashing with a
					// modal dialog that blocks headless/CI runs.
					String msg = runEx.getClass().getSimpleName() + ": " + runEx.getMessage(); //$NON-NLS-1$
					logger.log("[skilltest] JSUnit verify crashed for scope '" + scope + "': " + msg); //$NON-NLS-1$ //$NON-NLS-2$
					allPass = false;
					if (multi) {
						report.append("### ").append(scope).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
					}
					report.append("JSUnit SmartClient failed to start: ").append(msg).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
					continue;
				}
				boolean pass = isPass(one);
				allPass = allPass && pass;
				if (multi) {
					report.append("### ").append(scope).append(" - ").append(pass ? "PASS" : "FAIL") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
							.append("\n"); //$NON-NLS-1$
				}
				report.append(one).append("\n"); //$NON-NLS-1$
			}
		} finally {
			guard.restore();
		}
		logger.log("[skilltest] JSUnit verify " + (allPass ? "PASS" : "FAIL")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		// Running JSUnit brings the DLTK "Script Unit Test" view to the front; bring
		// the Skill Tests view back on top so the run stays the focus.
		bringSkillTestViewToTop();
		return new Result(allPass, report.toString());
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
	 * Maps a solution-relative {@code .js} path to the JSUnit scope string the runner takes.
	 * A root-level {@code <name>.js} is the global scope {@code name}; {@code forms/<form>.js}
	 * (or any {@code .../<form>.js} under a {@code forms} segment) is the form {@code form}.
	 *
	 * @return the scope/form name, or {@code null} when the path is not a recognizable test file
	 */
	static String scopeForSolutionPath(String solutionRelative) {
		if (solutionRelative == null) {
			return null;
		}
		String p = solutionRelative.replace('\\', '/').trim();
		while (p.startsWith("/")) { //$NON-NLS-1$
			p = p.substring(1);
		}
		if (p.isEmpty() || !p.toLowerCase().endsWith(".js")) { //$NON-NLS-1$
			return null;
		}
		String base = p.substring(0, p.length() - ".js".length()); //$NON-NLS-1$
		int slash = base.lastIndexOf('/');
		// forms/<form>.js (the form's scripting file) -> run the form
		if (slash >= 0) {
			String parent = base.substring(0, slash);
			String name = base.substring(slash + 1);
			if (parent.equalsIgnoreCase("forms") || parent.toLowerCase().endsWith("/forms")) { //$NON-NLS-1$ //$NON-NLS-2$
				return name;
			}
			// Any other nested .js is not a recognizable global scope / form test file.
			return null;
		}
		// root-level <scope>.js -> global scope
		return base;
	}

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
	 * The run passes only when it is not a runner-level error, did not time out,
	 * contains the results header, at least one test actually ran, the Failed and
	 * Errors counts are both zero, and there is no {@code FAIL }/{@code ERROR } test
	 * line. Conservative: anything unparseable is treated as NOT passing.
	 * <p>
	 * A timed-out run with zero executed tests is explicitly an ERROR, never a pass:
	 * {@link JSUnitRunnerService#runTests} prefixes a timeout with
	 * {@code "Error - Timed out while running!"} (caught by the {@code Error} guard),
	 * but a run whose scope registered no {@code test_} methods in time produces a
	 * clean {@code | **0** | **0** | **0** | **0** |} row with "All 0 test(s)
	 * passed!" — the false-pass this guard closes (SVY-21366).
	 */
	static boolean isPass(String report) {
		if (report == null || report.isBlank()) {
			return false;
		}
		String r = report.trim();
		if (r.startsWith("Error")) { //$NON-NLS-1$
			return false;
		}
		// A timeout marker anywhere in the report (partial results follow it) is a
		// failure, not a pass, even though the embedded results table may show no
		// failures/errors.
		if (r.contains("Timed out while running")) { //$NON-NLS-1$
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
		// Errors>0, and ALSO fail when nothing ran at all (passed+failed+errors == 0):
		// a 0/0/0 run means the scope registered no test_ methods (the solution/NG
		// bundle was not built in time), which "All 0 test(s) passed!" wrongly reported
		// as a pass before (SVY-21366).
		java.util.regex.Matcher row = java.util.regex.Pattern
				.compile("\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|\\s*\\*{0,2}(\\d+)\\*{0,2}\\s*\\|") //$NON-NLS-1$
				.matcher(r);
		if (row.find()) {
			int passed = Integer.parseInt(row.group(1));
			int failed = Integer.parseInt(row.group(2));
			int errors = Integer.parseInt(row.group(3));
			if (passed == 0 && failed == 0 && errors == 0) {
				return false; // nothing executed - not a pass
			}
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

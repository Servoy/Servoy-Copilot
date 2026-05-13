/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

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
package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.dltk.testing.DLTKTestingPlugin;
import org.eclipse.dltk.testing.model.ITestCaseElement;
import org.eclipse.dltk.testing.model.ITestElement;
import org.eclipse.dltk.testing.model.ITestElementContainer;
import org.eclipse.dltk.testing.model.ITestRunSession;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.jsunit.actions.RunJSUnitHandler;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.test.TestTarget;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.util.Pair;

/**
 * Service for launching JSUnit test runs and collecting results via the DLTK testing model.
 * Wraps the existing Servoy JSUnit Eclipse launch infrastructure.
 */
public class JSUnitRunnerService
{
	private static final long POLL_INTERVAL_MS = 500;

	private static final JSUnitRunnerService INSTANCE = new JSUnitRunnerService();

	private JSUnitRunnerService()
	{
	}

	public static JSUnitRunnerService getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Runs JSUnit tests for the active Servoy solution and returns formatted results.
	 *
	 * @param scopeOrAll "ALL" or null for all tests; "MODULES" to run each module separately;
	 *                   "FORMS" to run each form in the main solution separately;
	 *                   a scope/form/module name for a specific target
	 * @param timeoutSeconds maximum seconds to wait for each test run to complete
	 * @return formatted test results markdown, or an error message
	 */
	public String runTests(String scopeOrAll, int timeoutSeconds)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
		{
			return "Error: No active Servoy project found. Please open a Servoy solution.";
		}

		Solution activeSolution = activeProject.getSolution();
		if (activeSolution == null)
		{
			return "Error: No active Servoy solution found.";
		}


		try
		{
			String keyword = scopeOrAll != null ? scopeOrAll.trim().toUpperCase() : "";

			// MODULES: run each module's tests in a separate launch so that main-solution
			// tests are excluded. TestTarget(activeSolution) runs the full flattened solution
			// (main + all modules) and cannot isolate module-only tests.
			if ("MODULES".equals(keyword))
			{
				FlattenedSolution flattenedSolution = activeProject.getEditingFlattenedSolution();
				Solution[] modules = flattenedSolution != null ? flattenedSolution.getModules() : null;

				if (modules == null || modules.length == 0)
				{
					return "No modules found in the active solution.";
				}

				List<String> names = new ArrayList<>();
				List<ITestRunSession> sessions = new ArrayList<>();
				for (Solution module : modules)
				{
					ITestRunSession session = runForTarget(new TestTarget(module), timeoutSeconds);
					names.add(module.getName());
					sessions.add(session);
				}

				String[] result = new String[1];
				Display.getDefault().syncExec(() -> result[0] = formatGroupedResults("Modules", names, sessions));
				return result[0];
			}

			// FORMS: run each form's tests in a separate launch so that global-scope
			// tests are excluded. Only launches forms that have at least one test method
			// (a method whose name starts with "test") — avoids Servoy's "The selection does
			// not have jsunit tests" warning for non-test forms.
			if ("FORMS".equals(keyword))
			{
				Iterator<Form> it = activeSolution.getForms(null, true);
				List<String> names = new ArrayList<>();
				List<Form> forms = new ArrayList<>();
				while (it.hasNext())
				{
					Form f = it.next();
					if (hasTestMethods(f))
					{
						names.add(f.getName());
						forms.add(f);
					}
					else
					{
						ServoyLog.logWarning("[JSUnitRunner] skipping form " + f.getName() + " (no test methods)", null);
					}
				}

				if (forms.isEmpty())
				{
					return "No forms with test methods found in the active solution.";
				}

				List<ITestRunSession> sessions = new ArrayList<>();
				for (Form form : forms)
				{
					ITestRunSession session = runForTarget(new TestTarget(form), timeoutSeconds);
					sessions.add(session);
				}

				String[] result = new String[1];
				Display.getDefault().syncExec(() -> result[0] = formatGroupedResults("Forms", names, sessions));
				return result[0];
			}

			// Normal single-target run.
			TestTarget target = buildTestTarget(scopeOrAll, activeProject);
			ITestRunSession session = runForTarget(target, timeoutSeconds);

			if (session == null)
			{
				return "Error: Test run timed out after " + timeoutSeconds + " seconds. " +
					"Ensure the Servoy Application Server is running and the solution starts in JSUnit mode.";
			}

			String[] result = new String[1];
			Display.getDefault().syncExec(() -> result[0] = formatResults(session));
			return result[0];
		}
		catch (CoreException e)
		{
			ServoyLog.logError("Error creating or launching JSUnit configuration", e);
			return "Error launching JSUnit tests: " + e.getMessage();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return "Error: Test run was interrupted.";
		}
	}

	/**
	 * Returns true if the given form has at least one method whose name starts with "test".
	 * Used to skip forms that have no JSUnit tests before launching a test run, avoiding
	 * Servoy's "The selection does not have jsunit tests" warning.
	 */
	private static boolean hasTestMethods(Form form)
	{
		Iterator<ScriptMethod> methods = form.getScriptMethods(false);
		while (methods.hasNext())
		{
			if (methods.next().getName().startsWith("test"))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Launches a JSUnit test run for the given target, waits for results, terminates the launch,
	 * and returns the DLTK test session. Returns {@code null} if the run times out.
	 * <p>
	 * Must be called from a non-UI thread — RunJSUnitHandler uses Display.syncExec internally
	 * and would deadlock if invoked from the UI/Display thread.
	 */
	private ITestRunSession runForTarget(TestTarget target, int timeoutSeconds) throws CoreException, InterruptedException
	{
		ILaunchConfiguration config = new RunJSUnitHandler().findSmartClientTestLaunchConfiguration(target);

		// Snapshot existing sessions on the UI thread before launching.
		// DLTK's session list is written exclusively on the UI thread; reading it from a
		// background thread without synchronization yields stale (empty) data due to Java's
		// memory model. Display.syncExec ensures we read the current state.
		Set<ITestRunSession> sessionsBefore = new HashSet<>();
		Display.getDefault().syncExec(() -> {
			for (Object s : DLTKTestingPlugin.getModel().getTestRunSessions())
			{
				if (s instanceof ITestRunSession ts)
				{
					sessionsBefore.add(ts);
				}
			}
		});

		// This method runs on the AI worker thread (not the UI/Display thread), which is required:
		// RunJSUnitHandler uses Display.syncExec internally and would deadlock if called from the UI thread.
		ILaunch launch = config.launch(ILaunchManager.DEBUG_MODE, null);

		try
		{
			return waitForSession(sessionsBefore, timeoutSeconds * 1000L);
		}
		finally
		{
			// Always terminate the launch — the SmartClient does not stop on its own after tests complete.
			if (!launch.isTerminated())
			{
				try
				{
					launch.terminate();
				}
				catch (Exception ignored)
				{
					ServoyLog.logWarning("Could not terminate JSUnit launch after tests", null);
				}
			}
		}
	}

	private TestTarget buildTestTarget(String scopeOrAll, ServoyProject activeProject)
	{
		Solution activeSolution = activeProject.getSolution();

		if (scopeOrAll == null || "ALL".equalsIgnoreCase(scopeOrAll.trim()))
		{
			return new TestTarget(activeSolution);
		}

		// Note: "MODULES" and "FORMS" are handled upstream in runTests() before buildTestTarget() is called.

		String scopeName = scopeOrAll.trim();
		if (scopeName.endsWith(".js"))
		{
			scopeName = scopeName.substring(0, scopeName.length() - 3);
		}

		// Strip any path prefix — Servoy expects just the scope/form name, not a file path.
		// e.g. "forms/dateCalculation" or "/testcase_calculations/forms/dateCalculation" → "dateCalculation"
		int lastSlash = scopeName.lastIndexOf('/');
		if (lastSlash >= 0)
		{
			scopeName = scopeName.substring(lastSlash + 1);
		}

		// Strip dot-prefix notation — e.g. "forms.tab1" → "tab1"
		int lastDot = scopeName.lastIndexOf('.');
		if (lastDot >= 0)
		{
			scopeName = scopeName.substring(lastDot + 1);
		}

		// Check if the name refers to a form — forms require TestTarget(Form), not TestTarget(Pair).
		// Using the global scope constructor for a form name causes "no jsunit tests" error because
		// addAllFormTests is skipped when getGlobalScopeToTest() is non-null.
		//
		// Try getEditingFlattenedSolution() first (preferred).
		// If it is transiently null, fall back to iterating activeSolution.getForms() directly so
		// that the form target is never silently downgraded to a global-scope target.
		Form form = null;
		FlattenedSolution flattenedSolution = activeProject.getEditingFlattenedSolution();
		if (flattenedSolution != null)
		{
			form = flattenedSolution.getForm(scopeName);
		}
		if (form == null)
		{
			Iterator<Form> it = activeSolution.getForms(null, true);
			while (it.hasNext())
			{
				Form candidate = it.next();
				if (scopeName.equals(candidate.getName()))
				{
					form = candidate;
					break;
				}
			}
		}
		if (form != null)
		{
			return new TestTarget(form);
		}

		// Check if the name refers to a module — modules require TestTarget(Solution), not TestTarget(Pair).
		// A module name like "calculations_module" would otherwise fall through to the global-scope
		// constructor and produce the "Error initializing jsunit (no solution?)" failure.
		if (flattenedSolution != null)
		{
			Solution[] modules = flattenedSolution.getModules();
			if (modules != null)
			{
				for (Solution module : modules)
				{
					if (scopeName.equalsIgnoreCase(module.getName()))
					{
						return new TestTarget(module);
					}
				}
			}
		}

		return new TestTarget(new Pair<>(activeSolution, scopeName));
	}

	private ITestRunSession waitForSession(Set<ITestRunSession> sessionsBefore, long timeoutMs) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;

		// The DLTK TestRunSession is created immediately when the launch starts, but its
		// RemoteTestRunnerClient socket receives no connection (the JDT JUnit runner connects on a
		// separate port), so the session reaches Completed with 0 children within ~300ms.
		// Seconds later, ScriptUnitTestRunNotifier bridges JUnit events to the DLTK session via
		// ITestingClient, transitioning it: Completed(0) → Running(N) → Completed(N).
		// We must therefore wait not just for a terminal state but for children to appear.
		//
		// CHILDREN_WAIT_MS: if a terminal session has 0 children for this long, accept 0 as the
		// real answer (genuinely empty scope, or ScriptUnitTestRunNotifier never fired).
		ITestRunSession[] terminalSession = new ITestRunSession[1];
		long[] terminalFoundAt = new long[] { -1 };
		final long CHILDREN_WAIT_MS = 30_000;

		// Poll for any new session (not in the pre-launch snapshot) that has finished.
		// Accept any terminal state (not NOT_STARTED and not RUNNING): covers both STOPPED
		// (cancelled run) and Completed (normal finish, as used by DLTK 5.1.1.202603161157).
		// Polls run via Display.syncExec to read DLTK's session list on the UI thread —
		// the same thread that writes it — guaranteeing Java memory model visibility.
		while (System.currentTimeMillis() < deadline)
		{
			ITestRunSession[] found = new ITestRunSession[1];

			Display.getDefault().syncExec(() -> {
				for (Object entry : DLTKTestingPlugin.getModel().getTestRunSessions())
				{
					if (entry instanceof ITestRunSession candidate)
					{
						if (!sessionsBefore.contains(candidate) &&
							!ITestElement.ProgressState.NOT_STARTED.equals(candidate.getProgressState()) &&
							!ITestElement.ProgressState.RUNNING.equals(candidate.getProgressState()))
						{
							found[0] = candidate;
							break;
						}
					}
				}
			});

			if (found[0] != null)
			{
				if (terminalSession[0] == null)
				{
					terminalSession[0] = found[0];
					terminalFoundAt[0] = System.currentTimeMillis();
				}

				ITestElement[] fc = found[0].getChildren();
				int childCount = fc == null ? -1 : fc.length;
				long waitedMs = System.currentTimeMillis() - terminalFoundAt[0];

				if (childCount > 0)
				{
					return found[0];
				}

				if (waitedMs >= CHILDREN_WAIT_MS)
				{
					return found[0]; // genuinely empty — accept 0 children
				}
			}

			Thread.sleep(POLL_INTERVAL_MS);
		}

		// Primary timeout: return whatever terminal session we found, or null if none appeared.
		return terminalSession[0];
	}

	/**
	 * Formats combined test results for a multi-target run (MODULES or FORMS).
	 * Skips entries with 0 tests to keep output concise.
	 * Must be called on the UI thread (DLTK session data is written on the UI thread).
	 *
	 * @param groupLabel label for the result header, e.g. "Modules" or "Forms"
	 * @param names      names of the targets (module names or form names)
	 * @param sessions   corresponding DLTK sessions; may contain nulls for timed-out runs
	 */
	private String formatGroupedResults(String groupLabel, List<String> names, List<ITestRunSession> sessions)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("**JSUnit Test Results \u2014 ").append(groupLabel).append("**\n\n");

		long totalPassed = 0, totalFailed = 0, totalErrors = 0, totalIgnored = 0;
		List<String> details = new ArrayList<>();

		for (int i = 0; i < names.size(); i++)
		{
			String name = names.get(i);
			ITestRunSession session = sessions.get(i);

			if (session == null)
			{
				details.add("\n**" + name + "** \u2014 \u23f1 timed out");
				continue;
			}

			List<ITestCaseElement> testCases = new ArrayList<>();
			collectTestCases(session.getChildren(), testCases);

			if (testCases.isEmpty())
			{
				continue; // skip entries with no tests — keep output concise
			}

			long passed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.OK).count();
			long failed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.FAILURE).count();
			long errors = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.ERROR).count();
			long ignored = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.IGNORED).count();

			totalPassed += passed;
			totalFailed += failed;
			totalErrors += errors;
			totalIgnored += ignored;

			StringBuilder detail = new StringBuilder();
			detail.append("\n**").append(name).append("** \u2014 ");
			detail.append("\u2705 ").append(passed).append("  \u274c ").append(failed).append("  \ud83d\udca5 ").append(errors);
			if (ignored > 0)
			{
				detail.append("  \u23ed ").append(ignored);
			}

			for (ITestCaseElement testCase : testCases)
			{
				ITestElement.Result result = testCase.getTestResult(false);
				if (result != ITestElement.Result.FAILURE && result != ITestElement.Result.ERROR)
				{
					continue;
				}

				String icon = result == ITestElement.Result.FAILURE ? "\u274c" : "\ud83d\udca5";
				detail.append("\n  ").append(icon).append(" ").append(testCase.getTestName());

				ITestElement.FailureTrace trace = testCase.getFailureTrace();
				if (trace != null)
				{
					if (trace.getExpected() != null)
					{
						detail.append(" \u2014 expected: ").append(trace.getExpected())
							.append(", actual: ").append(trace.getActual());
					}
					else if (trace.getTrace() != null)
					{
						String[] lines = trace.getTrace().split("\n", 3);
						if (lines.length > 0)
						{
							detail.append(": ").append(lines[0].trim());
						}
					}
				}
			}
			details.add(detail.toString());
		}

		if (details.isEmpty())
		{
			sb.append("No tests found.");
			return sb.toString();
		}

		// Aggregate summary table
		sb.append("| \u2705 Passed | \u274c Failed | \ud83d\udca5 Errors | \u23ed Ignored |\n");
		sb.append("|:---------:|:---------:|:---------:|:---------:|\n");
		sb.append("| **").append(totalPassed).append("**");
		sb.append(" | **").append(totalFailed).append("**");
		sb.append(" | **").append(totalErrors).append("**");
		sb.append(" | **").append(totalIgnored).append("** |\n");
		sb.append("\n**Per ").append(groupLabel.toLowerCase()).append(":**");
		for (String detail : details)
		{
			sb.append(detail).append("\n");
		}

		return sb.toString();
	}

	private String formatResults(ITestRunSession session)
	{
		List<ITestCaseElement> testCases = new ArrayList<>();
		collectTestCases(session.getChildren(), testCases);

		long passed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.OK).count();
		long failed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.FAILURE).count();
		long errors = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.ERROR).count();
		long ignored = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.IGNORED).count();

		StringBuilder sb = new StringBuilder();
		sb.append("**JSUnit Test Results**\n\n");

		// Summary table — each count gets its own labelled cell so the chat view
		// renders them as visually distinct coloured columns.
		sb.append("| \u2705 Passed | \u274c Failed | \ud83d\udca5 Errors | \u23ed Ignored |\n");
		sb.append("|:---------:|:---------:|:---------:|:---------:|\n");
		sb.append("| **").append(passed).append("**");
		sb.append(" | **").append(failed).append("**");
		sb.append(" | **").append(errors).append("**");
		sb.append(" | **").append(ignored).append("** |\n");

		if (failed == 0 && errors == 0)
		{
			sb.append("\n\u2705 All ").append(passed).append(" test(s) passed!");
			return sb.toString();
		}

		sb.append("\n**Failed / Error tests:**\n");
		for (ITestCaseElement testCase : testCases)
		{
			ITestElement.Result result = testCase.getTestResult(false);
			if (result != ITestElement.Result.FAILURE && result != ITestElement.Result.ERROR)
			{
				continue;
			}

			String icon = result == ITestElement.Result.FAILURE ? "\u274c" : "\ud83d\udca5";
			sb.append("\n").append(icon).append(" ").append(testCase.getTestName()).append("\n");

			ITestElement.FailureTrace trace = testCase.getFailureTrace();
			if (trace != null)
			{
				if (trace.getExpected() != null)
				{
					sb.append("   Expected: ").append(trace.getExpected()).append("\n");
					sb.append("   Actual:   ").append(trace.getActual()).append("\n");
				}
				String traceText = trace.getTrace();
				if (traceText != null)
				{
					String[] lines = traceText.split("\n", 6);
					for (int i = 0; i < Math.min(lines.length, 5); i++)
					{
						sb.append("   ").append(lines[i].trim()).append("\n");
					}
				}
			}
		}

		return sb.toString();
	}

	private void collectTestCases(ITestElement[] elements, List<ITestCaseElement> results)
	{
		for (ITestElement element : elements)
		{
			if (element instanceof ITestElementContainer container)
			{
				collectTestCases(container.getChildren(), results);
			}
			else if (element instanceof ITestCaseElement testCase)
			{
				results.add(testCase);
			}
		}
	}
}

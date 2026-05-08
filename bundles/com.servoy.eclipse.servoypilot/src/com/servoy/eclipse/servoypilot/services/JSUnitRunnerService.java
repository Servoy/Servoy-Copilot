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
	 * @param scopeOrAll "ALL" or null for all tests; a scope name (with or without .js) for a specific scope
	 * @param timeoutSeconds maximum seconds to wait for the test run to complete
	 * @return formatted test results markdown, or an error message
	 */
	public String runTests(String scopeOrAll, int timeoutSeconds)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
			return "Error: No active Servoy project found. Please open a Servoy solution.";

		Solution activeSolution = activeProject.getSolution();
		if (activeSolution == null)
			return "Error: No active Servoy solution found.";

		try
		{
			TestTarget target = buildTestTarget(scopeOrAll, activeProject);
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
						sessionsBefore.add(ts);
				}
			});

			// This method runs on the AI worker thread (not the UI/Display thread), which is required:
			// RunClientTests uses Display.syncExec internally and would deadlock if called from the UI thread.
			ILaunch launch = config.launch(ILaunchManager.DEBUG_MODE, null);

			try
			{
				// Wait for the DLTK test session to appear and be populated with results.
				ITestRunSession session = waitForSession(sessionsBefore, timeoutSeconds * 1000L);

				if (session == null)
					return "Error: Test run timed out after " + timeoutSeconds + " seconds. " +
						"Ensure the Servoy Application Server is running and the solution starts in JSUnit mode.";

				// Read the session result tree on the UI thread — DLTK writes session data
				// exclusively on the UI thread, so reading from a background thread yields
				// stale/empty children due to Java memory model visibility.
				String[] result = new String[1];
				Display.getDefault().syncExec(() -> result[0] = formatResults(session));
				return result[0];
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

	private TestTarget buildTestTarget(String scopeOrAll, ServoyProject activeProject)
	{
		Solution activeSolution = activeProject.getSolution();

		if (scopeOrAll == null || "ALL".equalsIgnoreCase(scopeOrAll.trim()))
			return new TestTarget(activeSolution);

		String scopeName = scopeOrAll.trim();
		if (scopeName.endsWith(".js"))
			scopeName = scopeName.substring(0, scopeName.length() - 3);

		// Strip any path prefix — Servoy expects just the scope/form name, not a file path.
		// e.g. "forms/dateCalculation" or "/testcase_calculations/forms/dateCalculation" → "dateCalculation"
		int lastSlash = scopeName.lastIndexOf('/');
		if (lastSlash >= 0)
			scopeName = scopeName.substring(lastSlash + 1);

		// Strip dot-prefix notation — e.g. "forms.tab1" → "tab1"
		int lastDot = scopeName.lastIndexOf('.');
		if (lastDot >= 0)
			scopeName = scopeName.substring(lastDot + 1);

		// Check if the name refers to a form — forms require TestTarget(Form), not TestTarget(Pair).
		// Using the global scope constructor for a form name causes "no jsunit tests" error because
		// addAllFormTests is skipped when getGlobalScopeToTest() is non-null.
		//
		// Try getEditingFlattenedSolution() first (preferred, already validated in Fix 4).
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
			return new TestTarget(form);

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
					return found[0];

				if (waitedMs >= CHILDREN_WAIT_MS)
					return found[0]; // genuinely empty — accept 0 children
			}

			Thread.sleep(POLL_INTERVAL_MS);
		}

		// Primary timeout: return whatever terminal session we found, or null if none appeared.
		return terminalSession[0];
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
		sb.append("| ✅ Passed | ❌ Failed | 💥 Errors | ⏭ Ignored |\n");
		sb.append("|:---------:|:---------:|:---------:|:---------:|\n");
		sb.append("| **").append(passed).append("**");
		sb.append(" | **").append(failed).append("**");
		sb.append(" | **").append(errors).append("**");
		sb.append(" | **").append(ignored).append("** |\n");

		if (failed == 0 && errors == 0)
		{
			sb.append("\n✅ All ").append(passed).append(" test(s) passed!");
			return sb.toString();
		}

		sb.append("\n**Failed / Error tests:**\n");
		for (ITestCaseElement testCase : testCases)
		{
			ITestElement.Result result = testCase.getTestResult(false);
			if (result != ITestElement.Result.FAILURE && result != ITestElement.Result.ERROR)
				continue;

			String icon = result == ITestElement.Result.FAILURE ? "❌" : "💥";
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
				collectTestCases(container.getChildren(), results);
			else if (element instanceof ITestCaseElement testCase)
				results.add(testCase);
		}
	}
}

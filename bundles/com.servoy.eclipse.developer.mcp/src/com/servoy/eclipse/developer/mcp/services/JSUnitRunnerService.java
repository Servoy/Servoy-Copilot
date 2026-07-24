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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

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
import org.eclipse.e4.core.di.annotations.Creatable;
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
@Creatable
@SuppressWarnings("restriction")
public class JSUnitRunnerService
{
	private static final long POLL_INTERVAL_MS = 500;

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
			if ("MODULES".equalsIgnoreCase(scopeOrAll != null ? scopeOrAll.trim() : ""))
			{
				FlattenedSolution flattenedSolution = activeProject.getEditingFlattenedSolution();
				Solution[] modules = flattenedSolution != null ? flattenedSolution.getModules() : null;
				if (modules == null || modules.length == 0)
					return "No modules found in the active solution.";

				List<String> moduleNames = new ArrayList<>();
				List<ITestRunSession> moduleSessions = new ArrayList<>();
				for (Solution module : modules)
				{
					ITestRunSession session = runForTarget(new TestTarget(module), timeoutSeconds);
					moduleNames.add(module.getName());
					moduleSessions.add(session);
				}

				String[] result = new String[1];
				Display.getDefault().syncExec(() -> result[0] = formatGroupedResults("Modules", moduleNames, moduleSessions));
				return result[0];
			}

			if ("FORMS".equalsIgnoreCase(scopeOrAll != null ? scopeOrAll.trim() : ""))
			{
				List<String> formNames = new ArrayList<>();
				List<ITestRunSession> formSessions = new ArrayList<>();

				Iterator<Form> formIt = activeSolution.getForms(null, true);
				while (formIt != null && formIt.hasNext())
				{
					Form form = formIt.next();
					ITestRunSession session = runForTarget(new TestTarget(form), timeoutSeconds);
					formNames.add(form.getName());
					formSessions.add(session);
				}

				if (formNames.isEmpty())
					return "No forms found in the active solution.";

				String[] result = new String[1];
				Display.getDefault().syncExec(() -> result[0] = formatGroupedResults("Forms", formNames, formSessions));
				return result[0];
			}

			TestTarget target = buildTestTarget(scopeOrAll, activeProject);
			ITestRunSession session = runForTarget(target, timeoutSeconds);

			if (session == null)
				return "Error: Test run timed out after " + timeoutSeconds + " seconds. " +
					"Ensure the Servoy Application Server is running and the solution starts in JSUnit mode.";

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

	public String runTestMethod(String testMethodName, String scopeOrAll, int timeoutSeconds)
	{
		if (testMethodName == null || testMethodName.isBlank())
			return "Error: testMethodName must not be empty.";

		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
			return "Error: No active Servoy project found. Please open a Servoy solution.";

		Solution activeSolution = activeProject.getSolution();
		if (activeSolution == null)
			return "Error: No active Servoy solution found.";

		try
		{
			TestTarget target = buildTestTarget(scopeOrAll, activeProject);
			ITestRunSession session = runForTarget(target, timeoutSeconds);

			if (session == null)
				return "Error: Test run timed out after " + timeoutSeconds + " seconds. " +
					"Ensure the Servoy Application Server is running and the solution starts in JSUnit mode.";

			List<ITestCaseElement> allCases = new ArrayList<>();
			Display.getDefault().syncExec(() -> collectTestCases(session.getChildren(), allCases));

			String lowerMethod = testMethodName.toLowerCase();
			List<ITestCaseElement> matches = allCases.stream()
				.filter(t -> t.getTestName() != null && t.getTestName().toLowerCase().contains(lowerMethod))
				.collect(java.util.stream.Collectors.toList());

			if (matches.isEmpty())
			{
				String available = allCases.stream()
					.map(ITestCaseElement::getTestName)
					.collect(java.util.stream.Collectors.joining(", "));
				return "Error: No test named '" + testMethodName + "' found in scope '" + scopeOrAll +
					"'. Tests found: " + available;
			}

			String[] result = new String[1];
			Display.getDefault().syncExec(() -> result[0] = formatSingleMethodResult(testMethodName, matches));
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

	private String formatSingleMethodResult(String methodName, List<ITestCaseElement> matches)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("**runTestMethod: ").append(methodName).append("**\n\n");

		boolean multipleMatches = matches.size() > 1;

		for (ITestCaseElement testCase : matches)
		{
			ITestElement.Result result = testCase.getTestResult(false);

			String resultLabel;
			if (result == ITestElement.Result.OK)
				resultLabel = "PASS";
			else if (result == ITestElement.Result.FAILURE)
				resultLabel = "FAIL";
			else if (result == ITestElement.Result.ERROR)
				resultLabel = "ERROR";
			else
				resultLabel = "IGNORED";

			if (multipleMatches)
				sb.append(testCase.getTestName()).append(": ");

			sb.append("Result: ").append(resultLabel).append("\n");

			ITestElement.FailureTrace trace = testCase.getFailureTrace();
			if (trace != null)
			{
				if (result == ITestElement.Result.FAILURE && trace.getExpected() != null)
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

	private ITestRunSession runForTarget(TestTarget target, int timeoutSeconds) throws CoreException, InterruptedException
	{
		ILaunchConfiguration config = new RunJSUnitHandler().findSmartClientTestLaunchConfiguration(target);
		System.out.println("[DIAG-RUNNER] runForTarget: config=" + config.getName() + " timeout=" + timeoutSeconds + "s");

		Set<ITestRunSession> sessionsBefore = new HashSet<>();
		Display.getDefault().syncExec(() -> {
			for (Object s : DLTKTestingPlugin.getModel().getTestRunSessions())
			{
				if (s instanceof ITestRunSession ts)
					sessionsBefore.add(ts);
			}
		});
		System.out.println("[DIAG-RUNNER] sessionsBefore count=" + sessionsBefore.size());

		ILaunch launch = config.launch(ILaunchManager.RUN_MODE, null);
		System.out.println("[DIAG-RUNNER] launch created, terminated=" + launch.isTerminated());
		try
		{
			ITestRunSession result = waitForSession(sessionsBefore, timeoutSeconds * 1000L);
			System.out.println("[DIAG-RUNNER] waitForSession returned: " + (result == null ? "null" : result.getTestRunName() + " children=" + (result.getChildren() == null ? "null" : result.getChildren().length)));
			return result;
		}
		finally
		{
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
			return new TestTarget(activeSolution);

		String scopeName = scopeOrAll.trim();
		if (scopeName.endsWith(".js"))
			scopeName = scopeName.substring(0, scopeName.length() - 3);

		int lastSlash = scopeName.lastIndexOf('/');
		if (lastSlash >= 0)
			scopeName = scopeName.substring(lastSlash + 1);

		int lastDot = scopeName.lastIndexOf('.');
		if (lastDot >= 0)
			scopeName = scopeName.substring(lastDot + 1);

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

		if (flattenedSolution != null)
		{
			Solution[] modules = flattenedSolution.getModules();
			if (modules != null)
			{
				for (Solution module : modules)
				{
					if (scopeName.equalsIgnoreCase(module.getName()))
						return new TestTarget(module);
				}
			}
		}

		return new TestTarget(new Pair<>(activeSolution, scopeName));
	}

	private ITestRunSession waitForSession(Set<ITestRunSession> sessionsBefore, long timeoutMs) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;

		ITestRunSession[] terminalSession = new ITestRunSession[1];
		long[] terminalFoundAt = new long[] { -1 };
		final long CHILDREN_WAIT_MS = 30_000;
		int pollCount = 0;

		while (System.currentTimeMillis() < deadline)
		{
			ITestRunSession[] found = new ITestRunSession[1];
			int[] totalSessions = new int[1];
			int[] newSessions = new int[1];
			StringBuilder[] diagInfo = { new StringBuilder() };

			Display.getDefault().syncExec(() -> {
				for (Object entry : DLTKTestingPlugin.getModel().getTestRunSessions())
				{
					if (entry instanceof ITestRunSession candidate)
					{
						totalSessions[0]++;
						if (!sessionsBefore.contains(candidate))
						{
							newSessions[0]++;
							diagInfo[0].append("  new session: name=").append(candidate.getTestRunName())
								.append(" progress=").append(candidate.getProgressState())
								.append(" children=").append(candidate.getChildren() == null ? "null" : candidate.getChildren().length)
								.append("\n");
							if (!ITestElement.ProgressState.NOT_STARTED.equals(candidate.getProgressState()) &&
								!ITestElement.ProgressState.RUNNING.equals(candidate.getProgressState()))
							{
								found[0] = candidate;
							}
						}
					}
				}
			});

			pollCount++;
			if (pollCount <= 3 || (pollCount % 20 == 0) || found[0] != null)
			{
				System.out.println("[DIAG-WAITSESSION] poll#" + pollCount + " total=" + totalSessions[0] + " new=" + newSessions[0] + " foundTerminal=" + (found[0] != null));
				if (diagInfo[0].length() > 0)
					System.out.print(diagInfo[0]);
			}

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
					System.out.println("[DIAG-WAITSESSION] returning session with " + childCount + " children after " + waitedMs + "ms");
					return found[0];
				}

				if (waitedMs >= CHILDREN_WAIT_MS)
				{
					System.out.println("[DIAG-WAITSESSION] CHILDREN_WAIT_MS expired, returning session with " + childCount + " children");
					return found[0];
				}
			}

			Thread.sleep(POLL_INTERVAL_MS);
		}

		System.out.println("[DIAG-WAITSESSION] TIMEOUT after " + pollCount + " polls, terminalSession=" + (terminalSession[0] == null ? "null" : terminalSession[0].getTestRunName()));
		return terminalSession[0];
	}

	private String formatGroupedResults(String groupType, List<String> names, List<ITestRunSession> sessions)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("**JSUnit Test Results - ").append(groupType).append("**\n\n");

		long totalPassed = 0, totalFailed = 0, totalErrors = 0, totalIgnored = 0;
		List<String> details = new ArrayList<>();

		for (int i = 0; i < names.size(); i++)
		{
			String name = names.get(i);
			ITestRunSession session = sessions.get(i);

			if (session == null)
			{
				details.add("\n**" + name + "** - timed out");
				continue;
			}

			List<ITestCaseElement> testCases = new ArrayList<>();
			collectTestCases(session.getChildren(), testCases);

			long passed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.OK).count();
			long failed = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.FAILURE).count();
			long errors = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.ERROR).count();
			long ignored = testCases.stream().filter(t -> t.getTestResult(false) == ITestElement.Result.IGNORED).count();

			totalPassed += passed;
			totalFailed += failed;
			totalErrors += errors;
			totalIgnored += ignored;

			StringBuilder detail = new StringBuilder();
			detail.append("\n**").append(name).append("** - ");
			detail.append("Passed: ").append(passed).append("  Failed: ").append(failed).append("  Errors: ").append(errors);
			if (ignored > 0) detail.append("  Ignored: ").append(ignored);

			for (ITestCaseElement testCase : testCases)
			{
				ITestElement.Result result = testCase.getTestResult(false);
				if (result != ITestElement.Result.FAILURE && result != ITestElement.Result.ERROR)
					continue;

				String icon = result == ITestElement.Result.FAILURE ? "FAIL" : "ERROR";
				detail.append("\n  ").append(icon).append(" ").append(testCase.getTestName());

				ITestElement.FailureTrace trace = testCase.getFailureTrace();
				if (trace != null)
				{
					if (trace.getExpected() != null)
					{
						detail.append(" - expected: ").append(trace.getExpected())
							.append(", actual: ").append(trace.getActual());
					}
					else if (trace.getTrace() != null)
					{
						String[] lines = trace.getTrace().split("\n", 3);
						if (lines.length > 0) detail.append(": ").append(lines[0].trim());
					}
				}
			}
			details.add(detail.toString());
		}

		sb.append("| Passed | Failed | Errors | Ignored |\n");
		sb.append("|:------:|:------:|:------:|:-------:|\n");
		sb.append("| **").append(totalPassed).append("**");
		sb.append(" | **").append(totalFailed).append("**");
		sb.append(" | **").append(totalErrors).append("**");
		sb.append(" | **").append(totalIgnored).append("** |\n");
		sb.append("\n**Per ").append(groupType.toLowerCase()).append(":**");
		for (String detail : details)
			sb.append(detail).append("\n");

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

		sb.append("| Passed | Failed | Errors | Ignored |\n");
		sb.append("|:------:|:------:|:------:|:-------:|\n");
		sb.append("| **").append(passed).append("**");
		sb.append(" | **").append(failed).append("**");
		sb.append(" | **").append(errors).append("**");
		sb.append(" | **").append(ignored).append("** |\n");

		if (failed == 0 && errors == 0)
		{
			sb.append("\nAll ").append(passed).append(" test(s) passed!");
			return sb.toString();
		}

		sb.append("\n**Failed / Error tests:**\n");
		for (ITestCaseElement testCase : testCases)
		{
			ITestElement.Result result = testCase.getTestResult(false);
			if (result != ITestElement.Result.FAILURE && result != ITestElement.Result.ERROR)
				continue;

			String icon = result == ITestElement.Result.FAILURE ? "FAIL" : "ERROR";
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

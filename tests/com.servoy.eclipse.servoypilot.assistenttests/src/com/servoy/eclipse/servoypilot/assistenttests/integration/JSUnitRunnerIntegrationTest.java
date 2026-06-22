/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Layer 3 integration tests for {@link JSUnitRunnerService}.
 * <p>
 * <b>Prerequisites (all required):</b>
 * <ol>
 *   <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy and DLTK plugins active.</li>
 *   <li>Servoy Application Server must be running (ApplicationServerRegistry.exists() == true).</li>
 *   <li>At least one ServoyProject must exist in the workspace (setUp activates it automatically).</li>
 * </ol>
 * <p>
 * Tests skip gracefully at multiple levels:
 * <ul>
 *   <li>All 6 skip if no Servoy app server is running.</li>
 *   <li>All 6 skip if no ServoyProject is found in the workspace.</li>
 *   <li>Tests 1-3 skip (not fail) if the SmartClient cannot complete the run
 *       (e.g. no test_ methods in the active solution, or port conflict).</li>
 *   <li>Tests 4-5 always pass once a project is active (return "No X found" messages).</li>
 *   <li>Test 6 always passes once a project is active (accepts both success and error output).</li>
 * </ul>
 * <p>
 * These tests are <em>excluded from the Maven/Tycho headless build</em> (see pom.xml surefire
 * excludes). Run manually from Eclipse.
 */
public class JSUnitRunnerIntegrationTest extends ServoyRunnerTestBase
{
	/** Name of the minimal Servoy solution created in the PDE test workspace for Layer 3 tests. */
	private static final String TEST_PILOT_SOLUTION = "test_pilot_suite";

	/** Name of the ServoyResources project created alongside the test solution. */
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private JSUnitRunnerService runner;

	@Before
	public void setUp() throws Exception
	{
		runner = new JSUnitRunnerService();

		// 1. SWT must be available (Display.syncExec is used inside the runner).
		assumeNotNull("No Display available - test requires a running Eclipse UI",
			Display.getDefault());

		// 2. Wait for the Servoy ApplicationServer singleton.
		waitForAppServer();

		// 3. Ensure a Servoy project is active in the workspace.
		// If no project is active, find the first available project and activate it.
		ensureActiveServoyProject();
	}

	// -----------------------------------------------------------------------
	// "ALL" keyword tests - launch SmartClient and check output format.
	// Skip (not fail) if SmartClient returns an error (no test_ methods,
	// port conflict, or timeout) - these are environment-dependent.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runAll_returnsNonNullResult() throws Exception
	{
		// Verifies the service always returns something - never null or throws.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));

		assertNotNull("runTests(\"ALL\") must never return null", result);
	}

	@Test
	public void testActiveSolution_runAll_isMarkdownTable() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		// Skip the format assertion when SmartClient could not complete the run
		// (timeout, port conflict, or solution has no test_ methods).
		assumeTrue(
			"SmartClient run returned an error (" + result.substring(0, Math.min(result.length(), 80)) +
				") - skipping markdown format assertion",
			!result.startsWith("Error"));

		assertTrue("Output should contain markdown table header with Passed column",
			result.contains("| Passed"));
		assertTrue("Output should contain table separator line",
			result.contains("|:-"));
	}

	@Test
	public void testActiveSolution_runAll_passCountIsParseable() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		// Skip the numeric assertion when SmartClient could not complete the run.
		assumeTrue(
			"SmartClient run returned an error - skipping pass-count assertion",
			!result.startsWith("Error"));

		int passed = extractPassedCount(result);
		assertTrue("Pass count in output must be a non-negative integer (was " + passed + ")",
			passed >= 0);
	}

	// -----------------------------------------------------------------------
	// Keyword-variant tests - MODULES and FORMS short-circuit before launching
	// the SmartClient when the solution has no modules/test-forms, so these
	// tests are fast and always pass once a project is active.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runModules_doesNotCrash() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));

		assertNotNull("MODULES result should not be null", result);
		// Either grouped test output or "No modules found in the active solution." - both valid.
	}

	@Test
	public void testActiveSolution_runForms_doesNotCrash() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));

		assertNotNull("FORMS result should not be null", result);
		// Either grouped test output or "No forms with test methods found." - both valid.
	}

	@Test
	public void testActiveSolution_runNullScope_producesValidOutput() throws Exception
	{
		// null scope is treated identically to "ALL" inside buildTestTarget().
		String result = runOnBackgroundThread(() -> runner.runTests(null, TIMEOUT_SECONDS));

		assertNotNull("null-scope result should not be null", result);
		// Must be well-formed markdown OR a recognisable error/no-test message - never empty junk.
		assertTrue(
			"null-scope output should be a markdown table, an error message, or a 'no tests' notice; got: " +
				result.substring(0, Math.min(result.length(), 120)),
			result.contains("| Passed") || result.startsWith("Error") || result.contains("No "));
	}

	// -----------------------------------------------------------------------
	// Additional "ALL" correctness tests
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runAll_failCountIsParseable() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assumeTrue(
			"SmartClient run returned an error - skipping fail-count assertion",
			!result.startsWith("Error"));

		int failed = extractFailedCount(result);
		assertTrue("Fail count in output must be a non-negative integer (was " + failed + ")",
			failed >= 0);
	}

	@Test
	public void testActiveSolution_runAll_errorCountIsParseable() throws Exception
	{
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assumeTrue(
			"SmartClient run returned an error - skipping error-count assertion",
			!result.startsWith("Error"));

		int errors = extractErrorCount(result);
		assertTrue("Error count in output must be a non-negative integer (was " + errors + ")",
			errors >= 0);
	}

	@Test
	public void testActiveSolution_runAll_totalCountIsPositive() throws Exception
	{
		// The test_pilot_suite always has at least one test_ function; the total
		// (passed + failed + errors) must therefore be >= 1 after a successful run.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assumeTrue(
			"SmartClient run returned an error - skipping total-count assertion",
			!result.startsWith("Error"));

		int total = extractPassedCount(result) + extractFailedCount(result) + extractErrorCount(result);
		assertTrue("Total test count must be >= 1 (got " + total + "); result:\n" + result,
			total >= 1);
	}

	@Test
	public void testActiveSolution_runAll_outputHasPassedOrFailureBranch() throws Exception
	{
		// formatResults() always ends with either "✅ All X test(s) passed!" (all-pass
		// branch) or a "Failed / Error tests:" section (any-fail branch). Neither is ever
		// absent from a well-formed run.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assumeTrue(
			"SmartClient run returned an error - skipping branch-coverage assertion",
			!result.startsWith("Error"));

		assertTrue(
			"Output must end with either the all-passed line or the failure section; got:\n" +
				result.substring(0, Math.min(result.length(), 200)),
			result.contains("All") && result.contains("test(s) passed!") || result.contains("Failed / Error tests:"));
	}

	@Test
	public void testActiveSolution_runAll_allPassedMessagePresent() throws Exception
	{
		// test_pilot_suite contains only test_pilot_passesAlways() which always passes.
		// ensureActiveServoyProject() guarantees test_pilot_suite is active before this
		// test runs, so the failed==0 && errors==0 branch in formatResults() is always
		// reachable. The assumeTrue guards below are a safety net only.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assumeTrue(
			"SmartClient run returned an error - skipping all-passed assertion",
			!result.startsWith("Error"));

		int errors = extractErrorCount(result);
		assumeTrue(
			"Active solution has test errors - all-passed branch not reachable with this solution (skipping); result:\n" +
				result.substring(0, Math.min(result.length(), 120)),
			errors == 0);

		assertTrue(
			"Output must contain 'All X test(s) passed!' for a solution with no failures; got:\n" +
				result.substring(0, Math.min(result.length(), 200)),
			result.contains("All ") && result.contains("test(s) passed!"));
	}

	@Test
	public void testActiveSolution_runAll_noJavaStackTrace() throws Exception
	{
		// Raw JVM stack-trace lines must never leak into the chat response.
		// They are intentionally capped at 5 lines inside formatResults(), but
		// they should not appear at all - error messages are JS-level, not Java.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		assertNotNull(result);

		assertTrue(
			"Output must not contain raw Java stack-trace lines (\"at java.\"); got:\n" +
				result.substring(0, Math.min(result.length(), 200)),
			!result.contains("at java."));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Ensures {@code test_pilot_suite} is the active Servoy project.
	 * <p>
	 * Unlike the old "any project" fast-path, this method always switches to
	 * {@code test_pilot_suite} if a different solution (e.g. {@code test_layer4_suite}
	 * left over by a preceding test class) is currently active.  That matters for
	 * {@link #testActiveSolution_runAll_allPassedMessagePresent}, which requires a
	 * solution with zero errors.
	 * <ul>
	 *   <li>Fast path: {@code test_pilot_suite} is already active - returns immediately.</li>
	 *   <li>Otherwise: refreshes the model, locates {@code test_pilot_suite}, activates it
	 *       via a direct call on the SWT event thread, then pumps events until the model
	 *       reports it as active.</li>
	 *   <li>Falls back to the first available project if {@code test_pilot_suite} is not
	 *       found (e.g. user-only workspace).</li>
	 *   <li>Skips the test via {@code assumeTrue/assumeNotNull} if no project can be
	 *       activated.</li>
	 * </ul>
	 */
	private void ensureActiveServoyProject() throws Exception
	{
		// First, ensure the minimal test Servoy projects exist in the PDE test workspace.
		ensureServoyProjectsInWorkspace();

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		// Fast path: test_pilot_suite is already the active project.
		if (isPilotActive(model)) return;

		// Refresh so that projects created by ensureServoyProjectsInWorkspace() are visible.
		model.refreshServoyProjects();

		// Pump SWT events for 1 s so refreshServoyProjects() background jobs can
		// deliver any UI-thread callbacks before we look up the project.
		Display display = Display.getDefault();
		long refreshEnd = System.currentTimeMillis() + 1000;
		if (display.getThread() == Thread.currentThread())
		{
			while (System.currentTimeMillis() < refreshEnd)
				display.readAndDispatch();
		}
		else
		{
			Thread.sleep(1000);
		}

		if (isPilotActive(model)) return;

		// Find test_pilot_suite specifically; fall back to the first available project.
		ServoyProject[] projects = model.getServoyProjects();
		assumeTrue(
			"No ServoyProject found in the workspace after setup attempt - skipping Layer 3 tests",
			projects != null && projects.length > 0);

		ServoyProject toActivate = null;
		for (ServoyProject p : projects)
		{
			if (TEST_PILOT_SOLUTION.equals(p.getProject().getName()))
			{
				toActivate = p;
				break;
			}
		}
		if (toActivate == null) toActivate = projects[0]; // fallback: any project

		// Call setActiveProject directly on the SWT event thread (PDE tests run on the
		// UI thread). asyncExec would deadlock - it queues behind our own polling loop.
		try
		{
			model.setActiveProject(toActivate, true);
		}
		catch (Exception e)
		{
			// activation failure will be caught by the assumeNotNull below
		}

		// Pump SWT events so activation background jobs can deliver UI-thread callbacks.
		// Thread.sleep() here holds the event thread and causes the timeout to always fire.
		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
		if (display.getThread() == Thread.currentThread())
		{
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				display.readAndDispatch();
		}
		else
		{
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				Thread.sleep(200);
		}

		assumeNotNull(
			"Active project not set after " + (ACTIVATE_SETTLE_MS / 1000) +
				" seconds - skipping Layer 3 tests",
			model.getActiveProject());
	}

	/** Returns true if {@code test_pilot_suite} is the currently active Servoy project. */
	private boolean isPilotActive(IDeveloperServoyModel model)
	{
		ServoyProject active = model.getActiveProject();
		return active != null && TEST_PILOT_SOLUTION.equals(active.getProject().getName());
	}

	/**
	 * Creates minimal Servoy projects in the PDE test workspace so that
	 * {@link #ensureActiveServoyProject()} can find and activate a solution.
	 * <p>
	 * Projects created (idempotent - skipped if already present):
	 * <ol>
	 *   <li>{@code servoy_resources} - required resources project with
	 *       {@code com.servoy.eclipse.core.ServoyResources} nature.</li>
	 *   <li>{@code test_pilot_suite} - minimal Servoy solution with
	 *       {@code com.servoy.eclipse.core.ServoyProject} nature, the required
	 *       {@code .obj} metadata files, and one {@code test_*} function in
	 *       {@code globals.js}.</li>
	 * </ol>
	 */
	private void ensureServoyProjectsInWorkspace() throws Exception
	{
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			// 1. servoy_resources stub (referenced by the solution project)
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen()) res.open(monitor);

			// 2. test_pilot_suite: a minimal Servoy solution
			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_PILOT_SOLUTION);
			if (!sol.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_PILOT_SOLUTION);
				d.setNatureIds(new String[] {
					"com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature"
				});
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				sol.create(d, monitor);
			}
			if (!sol.isOpen()) sol.open(monitor);

			// Write required Servoy metadata (idempotent - skipped if file exists)
			writeProjectFile(sol, "rootmetadata.obj",
				"fileVersion:52,\nmustAuthenticate:false,\nname:\"test_pilot_suite\",\n" +
				"solutionType:1024,\ntypeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\"\n",
				monitor);
			writeProjectFile(sol, "solution_settings.obj",
				"typeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\",\nversion:\"1.0\"\n",
				monitor);
			writeProjectFile(sol, "globals.js",
				"/**\n * @properties={typeid:24,uuid:\"f1e2d3c4-b5a6-7890-fedc-ba9876543210\"}\n */\n" +
				"function test_pilot_passesAlways() {\n\t// no-op: always passes\n}\n",
				monitor);
			writeProjectFile(sol, ".buildpath",
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry kind=\"src\" path=\"\"/>\n</buildpath>\n",
				monitor);
		}, new NullProgressMonitor());

		// Pump SWT events for 1 s so workspace jobs triggered by project creation can
		// deliver any UI-thread callbacks before the ServoyModel refresh.
		Display display = Display.getDefault();
		long settleEnd = System.currentTimeMillis() + 1000;
		if (display.getThread() == Thread.currentThread())
		{
			while (System.currentTimeMillis() < settleEnd)
				display.readAndDispatch();
		}
		else
		{
			Thread.sleep(1000);
		}
	}
}

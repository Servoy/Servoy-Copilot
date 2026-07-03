/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import com.servoy.j2db.persistence.AbstractRepository;

/**
 * Integration tests for {@link JSUnitRunnerService#runTestMethod(String, String, int)}.
 * <p>
 * <b>Prerequisites (all required):</b>
 * <ol>
 * <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy and DLTK plugins active.</li>
 * <li>Servoy Application Server must be running (ApplicationServerRegistry.exists() == true).</li>
 * <li>At least one ServoyProject must exist in the workspace (setUp activates it automatically).</li>
 * </ol>
 * <p>
 * Tests skip gracefully:
 * <ul>
 * <li>All tests skip if no Servoy app server is running.</li>
 * <li>All tests skip if no ServoyProject is found in the workspace.</li>
 * <li>Tests that invoke the headless client skip (not fail) if the client cannot complete the run.</li>
 * </ul>
 * <p>
 * These tests are <em>excluded from the Maven/Tycho headless build</em> (see pom.xml surefire excludes).
 * Run manually from Eclipse using {@code RunTestMethodIntegrationTest_mac.launch}.
 */
public class RunTestMethodIntegrationTest extends ServoyRunnerTestBase
{
	/** Name of the minimal Servoy solution created in the PDE test workspace. */
	private static final String TEST_PILOT_SOLUTION = "test_pilot_suite";

	/** Name of the ServoyResources project created alongside the test solution. */
	private static final String SERVOY_RESOURCES = "servoy_resources";

	/**
	 * The known-passing test method in {@code test_pilot_suite/globals.js}.
	 * This function is always present (created by ensureServoyProjectsInWorkspace).
	 */
	private static final String KNOWN_PASSING_METHOD = "test_pilot_passesAlways";

	private JSUnitRunnerService runner;

	@Before
	public void setUp() throws Exception
	{
		runner = new JSUnitRunnerService();

		// 1. SWT must be available.
		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		// 2. Wait for the Servoy ApplicationServer singleton.
		waitForAppServer();

		// 3. Ensure the test Servoy projects exist and are active.
		ensureActiveServoyProject();
	}

	// -----------------------------------------------------------------------
	// Null / blank input guard tests
	// These exercise the service's own validation; no headless client needed.
	// -----------------------------------------------------------------------

	@Test
	public void testRunTestMethod_nullMethodName_returnsError()
	{
		String result = runner.runTestMethod(null, "ALL", TIMEOUT_SECONDS);
		assertNotNull(result);
		assertTrue("Null method name should return an error message starting with 'Error:'",
			result.startsWith("Error:"));
	}

	@Test
	public void testRunTestMethod_blankMethodName_returnsError()
	{
		String result = runner.runTestMethod("   ", "ALL", TIMEOUT_SECONDS);
		assertNotNull(result);
		assertTrue("Blank method name should return an error message starting with 'Error:'",
			result.startsWith("Error:"));
	}

	// -----------------------------------------------------------------------
	// Live headless-client tests
	// -----------------------------------------------------------------------

	@Test
	public void testRunTestMethod_knownMethod_returnsNonNullResult() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull("runTestMethod must never return null", result);
	}

	@Test
	public void testRunTestMethod_knownMethod_doesNotReturnGenericError() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);
		assertFalse("runTestMethod should not return a launch error for a known method; got: " + result,
			result.startsWith("Error: Test run timed out") || result.startsWith("Error launching"));
	}

	@Test
	public void testRunTestMethod_knownMethod_resultContainsMethodName() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);

		// Skip if headless client could not complete (timeout / port conflict).
		assertTrue("headless client run returned an error - skipping method-name assertion",
			!result.startsWith("Error"));

		assertTrue(
			"Output must reference the test method name '" + KNOWN_PASSING_METHOD + "'; got:\n"
				+ result.substring(0, Math.min(result.length(), 200)),
			result.toLowerCase().contains(KNOWN_PASSING_METHOD.toLowerCase()));
	}

	@Test
	public void testRunTestMethod_knownMethod_passedResultContainsPassed() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);

		// Skip if headless client could not complete.
		assertTrue("headless client run returned an error - skipping pass-status assertion",
			!result.startsWith("Error"));

		// The known method always passes; the output must contain PASS, PASSED, or passed.
		assertTrue(
			"Output for an always-passing method should indicate it passed; got:\n"
				+ result.substring(0, Math.min(result.length(), 200)),
			result.contains("PASS") || result.contains("passed"));
	}

	@Test
	public void testRunTestMethod_knownMethod_noJavaStackTrace() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);

		assertTrue("Output must not contain raw Java stack-trace lines (\"at java.\"); got:\n"
			+ result.substring(0, Math.min(result.length(), 200)), !result.contains("at java."));
	}

	@Test
	public void testRunTestMethod_unknownMethodName_returnsNotFoundError() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod("nonExistentMethod_xyz_abc_123", "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);

		// Skip if the headless client itself failed (timeout etc.).
		assertTrue("headless client run returned a launch error - skipping not-found assertion",
			!result.startsWith("Error: Test run timed out") && !result.startsWith("Error launching"));

		assertTrue(
			"Unknown method name should produce an error or a 'not found' message; got:\n"
				+ result.substring(0, Math.min(result.length(), 200)),
			result.startsWith("Error:"));
	}

	@Test
	public void testRunTestMethod_withScopeAll_returnsValidOutput() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull("runTestMethod with scope=ALL must not return null", result);
		assertFalse("runTestMethod with scope=ALL should not return empty string", result.isBlank());
	}

	@Test
	public void testRunTestMethod_withNullScope_returnsValidOutput() throws Exception
	{
		// null scope falls through to ALL inside buildTestTarget()
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, null, TIMEOUT_SECONDS));

		assertNotNull("runTestMethod with scope=null must not return null", result);
		assertFalse("runTestMethod with scope=null should not return empty string", result.isBlank());
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Ensures {@code test_pilot_suite} is the active Servoy project.
	 * Delegates project creation to {@link #ensureServoyProjectsInWorkspace()} and then
	 * activates the solution via the ServoyModel.
	 */
	private void ensureActiveServoyProject() throws Exception
	{
		ensureServoyProjectsInWorkspace();

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		if (isPilotActive(model))
			return;

		model.refreshServoyProjects();

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

		if (isPilotActive(model))
			return;

		ServoyProject[] projects = model.getServoyProjects();
		assertTrue("No ServoyProject found in the workspace after setup attempt - skipping integration tests",
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
		if (toActivate == null)
			toActivate = projects[0];

		try
		{
			model.setActiveProject(toActivate, true);
		}
		catch (Exception e)
		{
			// activation failure caught by assertNotNull below
		}

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

		assertNotNull("Active project not set after " + (ACTIVATE_SETTLE_MS / 1000)
			+ " seconds - integration tests cannot proceed", model.getActiveProject());
	}

	/** Returns true if {@code test_pilot_suite} is the currently active Servoy project. */
	private boolean isPilotActive(IDeveloperServoyModel model)
	{
		ServoyProject active = model.getActiveProject();
		return active != null && TEST_PILOT_SOLUTION.equals(active.getProject().getName());
	}

	/**
	 * Creates minimal Servoy projects in the PDE test workspace (idempotent).
	 * Same structure as used by {@link JSUnitRunnerIntegrationTest}.
	 */
	private void ensureServoyProjectsInWorkspace() throws Exception
	{
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen())
				res.open(monitor);

			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_PILOT_SOLUTION);
			if (!sol.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_PILOT_SOLUTION);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				sol.create(d, monitor);
			}
			if (!sol.isOpen())
				sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
				"fileVersion:" + AbstractRepository.repository_version
					+ ",\nmustAuthenticate:false,\nname:\"test_pilot_suite\",\n"
					+ "solutionType:1024,\ntypeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\"\n",
				monitor);
			writeProjectFile(sol, "solution_settings.obj",
				"typeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, "globals.js",
				"/**\n * @properties={typeid:24,uuid:\"f1e2d3c4-b5a6-7890-fedc-ba9876543210\"}\n */\n"
					+ "function test_pilot_passesAlways() {\n\t// no-op: always passes\n}\n",
				monitor);
			writeProjectFile(sol, ".buildpath",
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry kind=\"src\" path=\"\"/>\n</buildpath>\n",
				monitor);
		}, new NullProgressMonitor());

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

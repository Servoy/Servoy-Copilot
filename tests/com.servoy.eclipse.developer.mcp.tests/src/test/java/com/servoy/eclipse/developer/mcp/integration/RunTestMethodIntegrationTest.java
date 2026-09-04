/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;

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
 * The active solution ({@code test_pilot_suite}) contains exactly one test:
 * {@code test_pilot_passesAlways()} which always passes.
 * <p>
 * These tests are <em>excluded from the Maven/Tycho headless build</em> (see pom.xml surefire excludes), but run in a PDE ui verision of tycho junit tests.
 * Run manually from Eclipse using {@code AllDeveloperMcpIntegrationTests.launch} or variants of it.
 */
public class RunTestMethodIntegrationTest extends ServoyRunnerTestBase
{
	private static final String TEST_SOLUTION = "test_pilot_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	/**
	 * The known-passing test method in {@code test_pilot_suite/globals.js}.
	 * This function is always present.
	 */
	private static final String KNOWN_PASSING_METHOD = "test_pilot_passesAlways";

	private JSUnitRunnerService runner;

	public RunTestMethodIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

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
		assertEquals("Null method name should return the exact validation error",
			"Error: testMethodName must not be empty.", result);
	}

	@Test
	public void testRunTestMethod_blankMethodName_returnsError()
	{
		String result = runner.runTestMethod("   ", "ALL", TIMEOUT_SECONDS);
		assertNotNull(result);
		assertEquals("Blank method name should return the exact validation error",
			"Error: testMethodName must not be empty.", result);
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
		assertFalse("runTestMethod should not return a runner-level error for a known method; got: " + result,
			result.startsWith("Error:"));
	}

	@Test
	public void testRunTestMethod_knownMethod_resultContainsMethodName() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);
		assertFalse("runTestMethod must not return a runner-level error", result.startsWith("Error:"));
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
		assertFalse("runTestMethod must not return a runner-level error", result.startsWith("Error:"));
		// The known method always passes; output must contain the PASS result label.
		assertTrue(
			"Output for an always-passing method should indicate it passed; got:\n"
				+ result.substring(0, Math.min(result.length(), 200)),
			result.contains("Result: PASS"));
	}

	@Test
	public void testRunTestMethod_knownMethod_noJavaStackTrace() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod(KNOWN_PASSING_METHOD, "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);
		assertFalse("runTestMethod must not return a runner-level error", result.startsWith("Error:"));
		assertFalse("Output must not contain raw Java stack-trace lines (\"at java.\"); got:\n"
			+ result.substring(0, Math.min(result.length(), 200)), result.contains("at java."));
	}

	@Test
	public void testRunTestMethod_unknownMethodName_returnsNotFoundError() throws Exception
	{
		String result = runOnBackgroundThread(
			() -> runner.runTestMethod("nonExistentMethod_xyz_abc_123", "ALL", TIMEOUT_SECONDS));

		assertNotNull(result);
		assertTrue(
			"Unknown method name should produce a 'not found' error message; got:\n"
				+ result.substring(0, Math.min(result.length(), 200)),
			result.startsWith("Error: No test named 'nonExistentMethod_xyz_abc_123' found in scope 'ALL'."));
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
	 */
	private void ensureActiveServoyProject() throws Exception
	{
		ensureTestSolutionInWorkspace(null, (solPrj, monitor) -> {
			try {
				writeProjectFile(solPrj, "globals.js",
						"/**\n * @properties={typeid:24,uuid:\"f1e2d3c4-b5a6-7890-fedc-ba9876543210\"}\n */\n"
							+ "function test_pilot_passesAlways() {\n\t// no-op: always passes\n}\n",
						monitor);
			} catch (CoreException e) {
				fail("Can't create the globals.js file: " + e.getMessage());
			}
		});
		ensureActiveProject();
	}

}

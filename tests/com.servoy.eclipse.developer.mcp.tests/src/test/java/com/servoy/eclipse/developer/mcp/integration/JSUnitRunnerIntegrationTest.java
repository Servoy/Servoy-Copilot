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
import static org.junit.Assert.fail;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;

/**
 * Layer 3 integration tests for {@link JSUnitRunnerService}.
 * <p>
 * <b>Prerequisites (all required):</b>
 * <ol>
 * <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy and DLTK
 * plugins active.</li>
 * <li>Servoy Application Server must be running
 * (ApplicationServerRegistry.exists() == true).</li>
 * <li>At least one ServoyProject must exist in the workspace (setUp activates
 * it automatically).</li>
 * </ol>
 * <p>
 * Tests skip gracefully at multiple levels:
 * <ul>
 * <li>All 6 skip if no Servoy app server is running.</li>
 * <li>All 6 skip if no ServoyProject is found in the workspace.</li>
 * <li>Tests 1-3 skip (not fail) if the test client cannot complete the run
 * (e.g. no test_ methods in the active solution, or port conflict).</li>
 * <li>Tests 4-5 always pass once a project is active (return "No X found"
 * messages).</li>
 * <li>Test 6 always passes once a project is active (accepts both success and
 * error output).</li>
 * </ul>
 * <p>
 * These tests are <em>excluded from the Maven/Tycho headless build</em> (see
 * pom.xml surefire excludes). Run manually from Eclipse.
 */
public class JSUnitRunnerIntegrationTest extends ServoyRunnerTestBase {
	/**
	 * Name of the minimal Servoy solution created in the PDE test workspace for
	 * Layer 3 tests.
	 */
	private static final String TEST_PILOT_SOLUTION = "test_pilot_suite";
	private static final String RESOURCES_PRJ = "servoy_resources";

	private JSUnitRunnerService runner;

	public JSUnitRunnerIntegrationTest() {
		super(TEST_PILOT_SOLUTION, RESOURCES_PRJ);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_PILOT_SOLUTION, RESOURCES_PRJ);
		waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception {
		runner = new JSUnitRunnerService();

		// 1. SWT must be available (Display.syncExec is used inside the runner).
		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		// 2. Wait for the Servoy ApplicationServer singleton.
		TestUtilitiesClass.waitForAppServer();

		// 3. Ensure a Servoy project is active in the workspace.
		ensureTestSolutionInWorkspace(null, (solProject, monitor) -> {
			try {
				writeProjectFile(solProject, "globals.js",
						"/**\n * @properties={typeid:24,uuid:\"f1e2d3c4-b5a6-7890-fedc-ba9876543210\"}\n */\n"
								+ "function test_pilot_passesAlways() {\n\t// no-op: always passes\n}\n",
						monitor);
			} catch (CoreException e) {
				fail("Can't write globals.js: " + e.getMessage());
			}
		});
		
		// 4. The solution must be active in Servoy Developer at the end of this
		ensureActiveProject();
	}

	// -----------------------------------------------------------------------
	// "ALL" keyword tests - launch test client and check output format.
	// Skip (not fail) if test client returns an error (no test_ methods,
	// port conflict, or timeout) - these are environment-dependent.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runAll() throws Exception {
		// Verifies the service always returns something - never null or throws.
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));

		assertNotNull("runTests(\"ALL\") must never return null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));

		// Skip the format assertion when test client could not complete the run-
		// (timeout, port conflict, or solution has no test_ methods).
		assertTrue("test client run returned an error (" + result.substring(0, Math.min(result.length(), 80))
				+ ") - markdown format assertion failed", !result.startsWith("Error"));

		assertTrue("Output should contain markdown table header with Passed column", result.contains("| Passed"));
		assertTrue("Output should contain table separator line", result.contains("|:-"));

		// Skip the numeric assertion when test client could not complete the run.
		assertTrue("test client run returned an error - pass-count assertion failed", !result.startsWith("Error"));

		int passed = extractPassedCount(result);
		assertTrue("Pass count in output must be a non-negative integer (was " + passed + ")", passed >= 0);
		
		assertTrue("test client run returned an error - skipping fail-count assertion",
				!result.startsWith("Error"));

		int failed = extractFailedCount(result);
		assertTrue("Fail count in output must be a non-negative integer (was " + failed + ")", failed >= 0);

		assertTrue("test client run returned an error - skipping error-count assertion",
				!result.startsWith("Error"));

		int errors = extractErrorCount(result);
		assertTrue("Error count in output must be a non-negative integer (was " + errors + ")", errors >= 0);

		assertTrue("test client run returned an error - skipping total-count assertion",
				!result.startsWith("Error"));

		int total = extractPassedCount(result) + extractFailedCount(result) + extractErrorCount(result);
		assertTrue("Total test count must be >= 1 (got " + total + "); result:\n" + result, total >= 1);
		
		
		// formatResults() always ends with either "✅ All X test(s) passed!" (all-pass
		// branch) or a "Failed / Error tests:" section (any-fail branch). Neither is
		// ever
		// absent from a well-formed run.
		assertTrue("test client run returned an error - skipping branch-coverage assertion",
				!result.startsWith("Error"));

		assertTrue(
				"Output must end with either the all-passed line or the failure section; got:\n"
						+ result.substring(0, Math.min(result.length(), 200)),
				result.contains("All") && result.contains("test(s) passed!")
						|| result.contains("Failed / Error tests:"));
		
		// test_pilot_suite contains only test_pilot_passesAlways() which always passes.
		// ensureActiveServoyProject() guarantees test_pilot_suite is active before this
		// test runs, so the failed==0 && errors==0 branch in formatResults() is always
		// reachable. The assumeTrue guards below are a safety net only.
		assertTrue("test client run returned an error - skipping all-passed assertion",
				!result.startsWith("Error"));

		errors = extractErrorCount(result);
		assertTrue(
				"Active solution has test errors - all-passed branch not reachable with this solution (skipping); result:\n"
						+ result.substring(0, Math.min(result.length(), 120)),
				errors == 0);

		assertTrue(
				"Output must contain 'All X test(s) passed!' for a solution with no failures; got:\n"
						+ result.substring(0, Math.min(result.length(), 200)),
				result.contains("All ") && result.contains("test(s) passed!"));
		
		// Raw JVM stack-trace lines must never leak into the chat response.
		// They are intentionally capped at 5 lines inside formatResults(), but
		// they should not appear at all - error messages are JS-level, not Java.
		assertTrue("Output must not contain raw Java stack-trace lines (\"at java.\"); got:\n"
				+ result.substring(0, Math.min(result.length(), 200)), !result.contains("at java."));
	}

	// -----------------------------------------------------------------------
	// Keyword-variant tests - MODULES and FORMS short-circuit before launching
	// the test client when the solution has no modules/test-forms, so these
	// tests are fast and always pass once a project is active.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runModules_doesNotCrash() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));

		assertNotNull("MODULES result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		// Either grouped test output or "No modules found in the active solution." -
		// both valid.
	}

	@Test
	public void testActiveSolution_runForms_doesNotCrash() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));

		assertNotNull("FORMS result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		// Either grouped test output or "No forms with test methods found." - both
		// valid.
	}

	@Test
	public void testActiveSolution_runNullScope_producesValidOutput() throws Exception {
		// null scope is treated identically to "ALL" inside buildTestTarget().
		String result = runOnBackgroundThread(() -> runner.runTests(null, TIMEOUT_SECONDS));

		assertNotNull("null-scope result should not be null", result);
		// Must be well-formed markdown OR a recognisable 'no tests' notice - never
		// empty junk.
		assertTrue(
				"null-scope output should be a markdown table or a 'no tests' notice; got: "
						+ result.substring(0, Math.min(result.length(), 120)),
				result.contains("| Passed") || result.startsWith("Error") || result.contains("No "));
	}

}

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
 * The active solution ({@code test_pilot_suite}) contains exactly one test
 * method: {@code test_pilot_passesAlways()} which always passes.
 * <p>
 * These tests are <em>excluded from the Maven/Tycho headless build</em> (see
 * pom.xml surefire excludes); they run on Jenkins via a PDE tycho test run with UI.
 * 
 * Run them manually from Eclipse using AllDeveloperMcpIntegrationTests.launch or variants of it.
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
	// "ALL" keyword tests - test_pilot_suite has exactly 1 test that always
	// passes, so the output is deterministic.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runAll() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));

		assertNotNull("runTests(\"ALL\") must never return null", result);
		assertFalse("runTests(\"ALL\") must not start with 'Error:'", result.startsWith("Error"));

		// test_pilot_suite has exactly 1 always-passing test.
		assertEquals("Expected passed=1", 1, extractPassedCount(result));
		assertEquals("Expected failed=0", 0, extractFailedCount(result));
		assertEquals("Expected errors=0", 0, extractErrorCount(result));

		assertTrue("Output must contain the all-passed footer",
				result.contains("All 1 test(s) passed!"));
		assertTrue("Output must contain the markdown table header",
				result.contains("| Passed"));
		assertFalse("Output must not contain raw Java stack-trace lines",
				result.contains("at java."));
	}

	// -----------------------------------------------------------------------
	// Keyword-variant tests - MODULES and FORMS short-circuit before launching
	// the test client when the solution has no modules/test-forms.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runModules_returnsNoModulesMessage() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));

		assertNotNull("MODULES result should not be null", result);
		// test_pilot_suite has no modules - service returns this exact message.
		assertEquals("Expected the exact 'no modules' message",
				"No modules found in the active solution.", result);
	}

	@Test
	public void testActiveSolution_runForms_returnsNoFormsMessage() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));

		assertNotNull("FORMS result should not be null", result);
		// test_pilot_suite has no forms - service returns this exact message.
		assertEquals("Expected the exact 'no forms' message",
				"No forms found in the active solution.", result);
	}

	@Test
	public void testActiveSolution_runNullScope_producesValidOutput() throws Exception {
		// null scope is treated identically to "ALL" inside buildTestTarget().
		String result = runOnBackgroundThread(() -> runner.runTests(null, TIMEOUT_SECONDS));

		assertNotNull("null-scope result should not be null", result);
		assertFalse("null-scope result must not start with 'Error:'", result.startsWith("Error"));

		// null scope = ALL: same output as testActiveSolution_runAll.
		assertEquals("Expected passed=1 for null-scope (same as ALL)", 1, extractPassedCount(result));
		assertEquals("Expected failed=0 for null-scope", 0, extractFailedCount(result));
		assertEquals("Expected errors=0 for null-scope", 0, extractErrorCount(result));
		assertTrue("Output must contain the all-passed footer",
				result.contains("All 1 test(s) passed!"));
	}

}

/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.ServoyRunnerTestBaseCopy;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
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
 * Run them manually from Eclipse using PDEIntegrationTestsBaseCopy.launch or variants of it.
 */
public class JSUnitRunnerIntegrationTest extends ServoyRunnerTestBaseCopy {
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

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_PILOT_SOLUTION, RESOURCES_PRJ);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		runner = new JSUnitRunnerService();

		// 1. SWT must be available (Display.syncExec is used inside the runner).
		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		// 2. Wait for the Servoy ApplicationServer singleton.
		TestUtilitiesClassCopy.waitForAppServer();

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

		assertNotNull(result, "runTests(\"ALL\") must never return null");
		assertFalse(result.startsWith("Error"), "runTests(\"ALL\") must not start with 'Error:'");

		// test_pilot_suite has exactly 1 always-passing test.
		assertEquals(1, extractPassedCount(result), "Expected passed=1");
		assertEquals(0, extractFailedCount(result), "Expected failed=0");
		assertEquals(0, extractErrorCount(result), "Expected errors=0");

		// ALL runs the whole main solution as a single test run (excludeModules), so it renders
		// via the single-session format with the all-passed footer for a clean run.
		assertTrue(result.contains("All 1 test(s) passed!"),
				"Output must contain the all-passed footer");
		assertTrue(result.contains("| Passed"),
				"Output must contain the markdown table header");
		assertFalse(result.contains("at java."),
				"Output must not contain raw Java stack-trace lines");
	}

	// -----------------------------------------------------------------------
	// Keyword-variant tests - MODULES and FORMS short-circuit before launching
	// the test client when the solution has no modules/test-forms.
	// -----------------------------------------------------------------------

	@Test
	public void testActiveSolution_runModules_returnsNoModulesMessage() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));

		assertNotNull(result, "MODULES result should not be null");
		// test_pilot_suite has no modules - service returns this exact message.
		assertEquals("No modules found in the active solution.", result,
				"Expected the exact 'no modules' message");
	}

	@Test
	public void testActiveSolution_runForms_returnsNoFormsMessage() throws Exception {
		String result = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));

		assertNotNull(result, "FORMS result should not be null");
		// test_pilot_suite has no forms - service returns this exact message.
		assertEquals("No forms found in the active solution.", result,
				"Expected the exact 'no forms' message");
	}

	@Test
	public void testActiveSolution_runNullScope_producesValidOutput() throws Exception {
		// null scope is treated identically to "ALL" inside buildTestTarget().
		String result = runOnBackgroundThread(() -> runner.runTests(null, TIMEOUT_SECONDS));

		assertNotNull(result, "null-scope result should not be null");
		assertFalse(result.startsWith("Error"), "null-scope result must not start with 'Error:'");

		// null scope = ALL: same output as testActiveSolution_runAll.
		assertEquals(1, extractPassedCount(result), "Expected passed=1 for null-scope (same as ALL)");
		assertEquals(0, extractFailedCount(result), "Expected failed=0 for null-scope");
		assertEquals(0, extractErrorCount(result), "Expected errors=0 for null-scope");
		// null scope = ALL: single-session output, same as testActiveSolution_runAll.
		assertTrue(result.contains("All 1 test(s) passed!"),
				"Output must contain the all-passed footer");
	}

}

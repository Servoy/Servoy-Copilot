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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.j2db.util.UUID;

/**
 * Layer 4 integration tests for {@link JSUnitRunnerService} -- MODULES and FORMS grouped modes.
 * <p>
 * These tests cover the {@code formatGroupedResults()} path, which requires a solution that
 * actually has modules and forms with {@code test_} methods. Earlier test classes only exercise
 * the "No modules/forms found" early-exit paths.
 * <p>
 * <b>Solution structure created programmatically:</b>
 * <ul>
 *   <li>{@code test_grouped_module} -- a module (solutionType:2) with 2 passing tests in globals.js</li>
 *   <li>{@code test_grouped_suite} -- parent solution (solutionType:1) that declares the module
 *       in solution_settings.obj and contains one form ({@code test_form_alpha}) with 2 passing tests</li>
 * </ul>
 * <p>
 * Expected MODULES result: <b>2 passed, 0 failed, 0 errors</b> (from test_grouped_module).<br>
 * Expected FORMS result:   <b>2 passed, 0 failed, 0 errors</b> (from test_form_alpha).
 * <p>
 * <b>Prerequisites:</b>
 * <ol>
 *   <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy and DLTK plugins active.</li>
 *   <li>Servoy Application Server must be running ({@code ApplicationServerRegistry.exists() == true}).</li>
 * </ol>
 */
public class JSUnitRunnerGroupedTest extends ServoyRunnerTestBase
{
	/** Parent solution name. */
	private static final String TEST_GROUPED_SOLUTION = "test_grouped_suite";

	/** Module name -- declared in the parent's solution_settings.obj. */
	private static final String TEST_GROUPED_MODULE = "test_grouped_module";

	/** Form name -- placed in the parent solution's forms/ directory. */
	private static final String TEST_FORM_NAME = "test_form_alpha";

	/** Shared ServoyResources project (shared with other test classes). */
	private static final String SERVOY_RESOURCES = "servoy_resources";

	// -----------------------------------------------------------------------
	// Module globals.js: 2 passing tests, 0 failures
	// -----------------------------------------------------------------------
	private static final String MODULE_GLOBALS_JS =
		"/**\n * @properties={typeid:24,uuid:\"22222222-3333-4444-5555-666666666661\"}\n */\n" +
		"function test_module_addition() {\n" +
		"\tif (1 + 2 !== 3) throw new Error('Expected 1+2 to equal 3');\n" +
		"}\n\n" +
		"/**\n * @properties={typeid:24,uuid:\"22222222-3333-4444-5555-666666666662\"}\n */\n" +
		"function test_module_string() {\n" +
		"\tif ('a' + 'b' !== 'ab') throw new Error('Expected string concat to equal ab');\n" +
		"}\n";

	// -----------------------------------------------------------------------
	// Form .frm file: minimal Servoy form definition (no dataSource)
	// -----------------------------------------------------------------------
	private static final String FORM_FRM =
		"items:[\n{\nheight:480,\npartType:5,\ntypeid:19,\n" +
		"uuid:\"22222222-3333-4444-5555-777777777770\"\n}\n],\n" +
		"name:\"" + TEST_FORM_NAME + "\",\n" +
		"showInMenu:false,\n" +
		"size:\"640,480\",\n" +
		"typeid:3,\n" +
		"uuid:\"22222222-3333-4444-5555-777777777771\"\n";

	// -----------------------------------------------------------------------
	// Form .js file: 2 passing tests
	// -----------------------------------------------------------------------
	private static final String FORM_JS =
		"/**\n * @properties={typeid:24,uuid:\"22222222-3333-4444-5555-888888888881\"}\n */\n" +
		"function test_form_alpha_passes() {\n" +
		"\t// always passes -- no-op\n" +
		"}\n\n" +
		"/**\n * @properties={typeid:24,uuid:\"22222222-3333-4444-5555-888888888882\"}\n */\n" +
		"function test_form_alpha_string() {\n" +
		"\tif ('x' + 'y' !== 'xy') throw new Error('Expected xy');\n" +
		"}\n";

	private JSUnitRunnerService runner;

	/**
	 * Cached result of {@code runTests("MODULES")} -- computed once per JVM session.
	 * Avoids launching the SmartClient for each of the many @Test methods.
	 */
	private static String cachedModulesResult;

	/**
	 * Cached result of {@code runTests("FORMS")} -- computed once per JVM session.
	 */
	private static String cachedFormsResult;

	/** Guards one-time class setup inside @Before (JUnit 4 has no @BeforeClass with instance access). */
	private static boolean classSetUpDone = false;

	/** Per-test references to the cached results. */
	private String modulesResult;
	private String formsResult;

	public JSUnitRunnerGroupedTest() {
		super(TEST_GROUPED_SOLUTION, SERVOY_RESOURCES);
	}

	@Before
	public void setUp() throws Exception
	{
		runner = new JSUnitRunnerService();

		// 1. SWT must be available.
		assumeNotNull("No Display available - test requires a running Eclipse UI",
			Display.getDefault());

		// 2. Skip if no Servoy app server.
		waitForAppServer();

		// 3. One-time setup: create projects, activate, run MODULES + FORMS.
		if (!classSetUpDone)
		{
			classSetUpDone = true;
			ensureGroupedProjectsInWorkspace();
			ensureActiveProject();
			cachedModulesResult = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));
			cachedFormsResult = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));
		}

		modulesResult = cachedModulesResult;
		formsResult = cachedFormsResult;

		// Guard: if class setup failed (project activation timed out), both results are
		// null. Skip gracefully rather than NPE in every test method.
		assumeNotNull("Class setup did not complete (activation failed) - skipping", modulesResult);
	}

	// -----------------------------------------------------------------------
	// MODULES -- runner-level correctness
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_modules_resultIsNotNull()
	{
		assertNotNull("runTests(\"MODULES\") must not return null", modulesResult);
	}

	@Test
	public void testGrouped_modules_resultIsNotError()
	{
		assertFalse(
			"runTests(\"MODULES\") must not be a runner-level error; result:\n" +
				modulesResult.substring(0, Math.min(modulesResult.length(), 120)),
			modulesResult.startsWith("Error"));
	}

	// -----------------------------------------------------------------------
	// MODULES -- output format
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_modules_headerPresent()
	{
		// formatGroupedResults() emits "**JSUnit Test Results -- Modules**"
		assertTrue("MODULES result must contain the grouped header",
			modulesResult.contains("JSUnit Test Results") && modulesResult.contains("Modules"));
	}

	@Test
	public void testGrouped_modules_summaryTablePresent()
	{
		assertTrue("MODULES result must contain the markdown summary table",
			modulesResult.contains("| Passed"));
	}

	@Test
	public void testGrouped_modules_perModulesSectionPresent()
	{
		// formatGroupedResults("Modules", ...) appends "**Per modules:**"
		assertTrue("MODULES result must contain the '**Per modules:**' section",
			modulesResult.contains("**Per modules:**"));
	}

	@Test
	public void testGrouped_modules_moduleNameInOutput()
	{
		assertTrue("MODULES result must mention the module name '" + TEST_GROUPED_MODULE + "'",
			modulesResult.contains(TEST_GROUPED_MODULE));
	}

	// -----------------------------------------------------------------------
	// MODULES -- test counts
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_modules_passedCountIsTwo()
	{
		assertEquals(
			"Expected 2 passing tests from " + TEST_GROUPED_MODULE + "; result:\n" + modulesResult,
			2, extractPassedCount(modulesResult));
	}

	@Test
	public void testGrouped_modules_failedCountIsZero()
	{
		assertEquals(
			"Expected 0 failures from " + TEST_GROUPED_MODULE + "; result:\n" + modulesResult,
			0, extractFailedCount(modulesResult));
	}

	@Test
	public void testGrouped_modules_errorCountIsZero()
	{
		assertEquals(
			"Expected 0 errors from " + TEST_GROUPED_MODULE + "; result:\n" + modulesResult,
			0, extractErrorCount(modulesResult));
	}

	@Test
	public void testGrouped_modules_ignoredCountIsZero()
	{
		assertEquals(
			"Expected 0 ignored tests; result:\n" + modulesResult,
			0, extractCount(modulesResult, 3));
	}

	// -----------------------------------------------------------------------
	// FORMS -- runner-level correctness
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_forms_resultIsNotNull()
	{
		assertNotNull("runTests(\"FORMS\") must not return null", formsResult);
	}

	@Test
	public void testGrouped_forms_resultIsNotError()
	{
		assertFalse(
			"runTests(\"FORMS\") must not be a runner-level error; result:\n" +
				formsResult.substring(0, Math.min(formsResult.length(), 120)),
			formsResult.startsWith("Error"));
	}

	// -----------------------------------------------------------------------
	// FORMS -- output format
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_forms_headerPresent()
	{
		// formatGroupedResults() emits "**JSUnit Test Results -- Forms**"
		assertTrue("FORMS result must contain the grouped header",
			formsResult.contains("JSUnit Test Results") && formsResult.contains("Forms"));
	}

	@Test
	public void testGrouped_forms_summaryTablePresent()
	{
		assertTrue("FORMS result must contain the markdown summary table",
			formsResult.contains("| Passed"));
	}

	@Test
	public void testGrouped_forms_perFormsSectionPresent()
	{
		// formatGroupedResults("Forms", ...) appends "**Per forms:**"
		assertTrue("FORMS result must contain the '**Per forms:**' section",
			formsResult.contains("**Per forms:**"));
	}

	@Test
	public void testGrouped_forms_formNameInOutput()
	{
		assertTrue("FORMS result must mention the form name '" + TEST_FORM_NAME + "'",
			formsResult.contains(TEST_FORM_NAME));
	}

	// -----------------------------------------------------------------------
	// FORMS -- test counts
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_forms_passedCountIsTwo()
	{
		assertEquals(
			"Expected 2 passing tests from " + TEST_FORM_NAME + "; result:\n" + formsResult,
			2, extractPassedCount(formsResult));
	}

	@Test
	public void testGrouped_forms_failedCountIsZero()
	{
		assertEquals(
			"Expected 0 failures from " + TEST_FORM_NAME + "; result:\n" + formsResult,
			0, extractFailedCount(formsResult));
	}

	@Test
	public void testGrouped_forms_errorCountIsZero()
	{
		assertEquals(
			"Expected 0 errors from " + TEST_FORM_NAME + "; result:\n" + formsResult,
			0, extractErrorCount(formsResult));
	}

	@Test
	public void testGrouped_forms_ignoredCountIsZero()
	{
		assertEquals(
			"Expected 0 ignored tests; result:\n" + formsResult,
			0, extractCount(formsResult, 3));
	}

	// -----------------------------------------------------------------------
	// Setup helpers
	// -----------------------------------------------------------------------

	/**
	 * Creates the following projects in the PDE test workspace (idempotent):
	 * <ol>
	 *   <li>{@code servoy_resources} -- shared stub (may already exist from other test classes)</li>
	 *   <li>{@code test_grouped_module} -- solutionType:2 module with 2 passing tests in globals.js</li>
	 *   <li>{@code test_grouped_suite} -- solutionType:1 parent that declares the module and
	 *       contains one form ({@code test_form_alpha}) with 2 passing tests</li>
	 * </ol>
	 * JavaScript files are always force-written so content changes are picked up between runs.
	 */
	private void ensureGroupedProjectsInWorkspace() throws Exception
	{
		// module
		ensureSolutionInWorkspace(TEST_GROUPED_MODULE, UUID.randomUUID().toString(), SERVOY_RESOURCES, 
			(modulePrj, monitor) -> {
				// Force-write globals.js so content changes are always picked up.
				try {
					forceWriteFile(modulePrj.getFile("globals.js"), MODULE_GLOBALS_JS, monitor);
				} catch (CoreException e) {
					fail("Cannot write module globals.js: " + e.getMessage());
				}
			});

		// main solution
		ensureTestSolutionInWorkspace((solPrj, monitor) -> {
			try {
				writeProjectFile(solPrj, "solution_settings.obj",
						"modulesNames:\"" + TEST_GROUPED_MODULE + "\",\n" +
						"typeid:43,\nuuid:\"" + solutionUUID + "\",\nversion:\"1.0\"\n",
						monitor);
				// Parent globals.js has no test_ methods -- scope is intentionally empty.
				writeProjectFile(solPrj, "globals.js",
					"// No test methods in parent globals -- tests live in the module and form.\n",
					monitor);
	
				// Form files
				IFolder formsDir = solPrj.getFolder("forms");
				if (!formsDir.exists()) formsDir.create(true, true, monitor);
	
				// Force-write both form files so content changes are always picked up.
				forceWriteFile(solPrj.getFile("forms/" + TEST_FORM_NAME + ".frm"), FORM_FRM, monitor);
				forceWriteFile(solPrj.getFile("forms/" + TEST_FORM_NAME + ".js"), FORM_JS, monitor);
			} catch (CoreException e) {
				fail("Cannot write main solution details: " + e.getMessage());
			}
		});

		// Wait for workspace auto-build (DLTK indexing) to complete before running tests.
		ResourcesPlugin.getWorkspace().build(
			org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,
			new NullProgressMonitor());
		waitForWorkspaceBuildJobs();
	}

	/**
	 * Writes a file, creating it if absent or replacing its contents if already present.
	 * Unlike {@link #writeProjectFile}, this always ensures the latest content is on disk.
	 */
	private static void forceWriteFile(IFile file, String content,
		org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException
	{
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes);
		if (file.exists())
			file.setContents(stream, true, false, monitor);
		else
			file.create(stream, true, monitor);
	}
}

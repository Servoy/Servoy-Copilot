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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
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

	/**
	 * Cached result of {@code runTests("ALL")} -- computed once per JVM session.
	 * Used by the SVY-21414 §8 tests: {@code ALL} must fan out over the main solution's own
	 * scopes and forms only, and must NOT descend into its modules.
	 */
	private static String cachedAllResult;

	/** Guards one-time class setup inside @Before (JUnit 4 has no @BeforeClass with instance access). */
	private static boolean classSetUpDone = false;

	/** Per-test references to the cached results. */
	private String modulesResult;
	private String formsResult;
	private String allResult;

	public JSUnitRunnerGroupedTest() {
		super(TEST_GROUPED_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_GROUPED_SOLUTION, TEST_GROUPED_MODULE, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception
	{
		runner = new JSUnitRunnerService();

		// 1. SWT must be available.
		assertNotNull("No Display available - test requires a running Eclipse UI",
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
			cachedAllResult = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		}

		modulesResult = cachedModulesResult;
		formsResult = cachedFormsResult;
		allResult = cachedAllResult;

		// Guard: if class setup failed (project activation timed out), both results are
		// null. Skip gracefully rather than NPE in every test method.
		assertNotNull("Class setup did not complete (activation failed) - skipping", modulesResult);
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
	// SVY-21414 -- ALL runs the whole active (flattened) solution as a SINGLE
	// test run, rendered via the single-session formatResults format.
	//
	// NOTE: an earlier attempt made ALL exclude modules in the runner (a fan-out,
	// then a TestTarget.excludeModules engine flag). Both were REVERTED: ALL uses
	// the original new TestTarget(activeSolution), i.e. the whole flattened
	// solution INCLUDING modules. "Run all = only the active solution, don't
	// switch/enumerate other solutions" is now enforced in the agent skill
	// (skill4servoy), not the Java runner. So ALL here includes the module tests.
	//
	// Fixture: test_grouped_suite (main) declares module test_grouped_module
	// (2 tests: test_module_addition, test_module_string) and contains form
	// test_form_alpha (2 tests: test_form_alpha_passes, test_form_alpha_string).
	// Its parent globals.js has NO test_ methods.
	//
	// Expected ALL result: 4 passed (2 form + 2 module), 0 failed, 0 errors,
	// single-session format (NOT a per-target fan-out).
	// -----------------------------------------------------------------------

	@Test
	public void testGrouped_all_resultIsNotNull()
	{
		assertNotNull("runTests(\"ALL\") must not return null", allResult);
	}

	@Test
	public void testGrouped_all_resultIsNotError()
	{
		assertFalse(
			"runTests(\"ALL\") must not be a runner-level error; result:\n" +
				allResult.substring(0, Math.min(allResult.length(), 120)),
			allResult.startsWith("Error"));
	}

	/**
	 * §8.5: ALL runs the whole main solution as a SINGLE test run (excludeModules), so it is
	 * rendered via the single-session {@code formatResults(...)} format --
	 * {@code "**JSUnit Test Results**"} with a summary table -- NOT the grouped per-target format.
	 */
	@Test
	public void testGrouped_all_renderedAsSingleSolutionRun()
	{
		assertTrue("ALL result must contain the single-run JSUnit results header; result:\n" + allResult,
			allResult.contains("**JSUnit Test Results**"));
		assertFalse("ALL must NOT be a grouped fan-out (no per-solution/per-form breakdown); result:\n" + allResult,
			allResult.contains("**Per solution:**"));
	}

	@Test
	public void testGrouped_all_summaryTablePresent()
	{
		assertTrue("ALL result must contain the markdown summary table; result:\n" + allResult,
			allResult.contains("| Passed"));
	}

	/**
	 * ALL runs the whole flattened solution as a single session: the main solution's 2 form
	 * tests PLUS the module's 2 tests = 4 passing. The completion-wait fix (§3) means the full
	 * count is reported, not a truncated first batch.
	 */
	@Test
	public void testGrouped_all_reportsFullFlattenedCount()
	{
		// ALL = new TestTarget(activeSolution) = whole flattened solution (main + modules).
		// 2 form tests + 2 module tests = 4. (Module exclusion is enforced in the agent skill,
		// not the runner, so the runner-level ALL includes modules.)
		assertEquals(
			"ALL must report the full flattened-solution count (2 form + 2 module); result:\n" + allResult,
			4, extractPassedCount(allResult));
	}

	@Test
	public void testGrouped_all_failedCountIsZero()
	{
		assertEquals("ALL must report 0 failures; result:\n" + allResult,
			0, extractFailedCount(allResult));
	}

	@Test
	public void testGrouped_all_errorCountIsZero()
	{
		assertEquals("ALL must report 0 errors; result:\n" + allResult,
			0, extractErrorCount(allResult));
	}

	/**
	 * ALL descends into the solution's modules (whole flattened solution). A clean run reports
	 * only counts, not individual test names, so this asserts the combined count includes the
	 * module tests: 4 passed = 2 form + 2 module. (Runner-level module exclusion was reverted;
	 * "only the active solution, don't switch" now lives in the agent skill.)
	 */
	@Test
	public void testGrouped_all_includesModuleTests()
	{
		assertEquals(
			"ALL must include the module tests in the flattened-solution count; result:\n" + allResult,
			4, extractPassedCount(allResult));
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
		ensureSolutionInWorkspace(TEST_GROUPED_MODULE, UUID.randomUUID().toString(), SERVOY_RESOURCES, null,
			(modulePrj, monitor) -> {
				// Force-write globals.js so content changes are always picked up.
				try {
					forceWriteFile(modulePrj.getFile("globals.js"), MODULE_GLOBALS_JS, monitor);
				} catch (CoreException e) {
					fail("Cannot write module globals.js: " + e.getMessage());
				}
			});

		// main solution
		ensureTestSolutionInWorkspace(new String[] { TEST_GROUPED_MODULE }, (solPrj, monitor) -> {
			try {
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

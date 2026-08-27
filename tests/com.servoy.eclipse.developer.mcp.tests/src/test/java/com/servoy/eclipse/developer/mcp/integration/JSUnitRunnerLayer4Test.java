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
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;

/**
 * Layer 4 integration tests for {@link JSUnitRunnerService}.
 * <p>
 * Unlike the Layer 3 tests (which use a no-op solution and skip on headless client failure),
 * Layer 4 uses a solution with <em>real JSUnit assertions</em> and <b>hard-asserts</b>
 * on all outcomes once the Servoy application server is confirmed running.
 * <p>
 * <b>Prerequisites:</b>
 * <ol>
 *   <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy and DLTK plugins active.</li>
 *   <li>Servoy Application Server must be running ({@code ApplicationServerRegistry.exists() == true}).</li>
 * </ol>
 * <p>
 * The test solution ({@code test_layer4_suite}) is created programmatically. It contains:
 * <ul>
 *   <li>{@code test_mathAddition()} - {@code assertEquals(4, 2 + 2)} - always <b>PASS</b></li>
 *   <li>{@code test_stringConcatenation()} - {@code assertEquals("helloworld", "hello" + "world")} - always <b>PASS</b></li>
 *   <li>{@code test_booleanComparison()} - {@code assertTrue(1 < 2)} - always <b>PASS</b></li>
 *   <li>{@code test_mathFailure()} - {@code assertEquals(5, 2 + 2)} - always <b>FAIL</b> (intentional)</li>
 * </ul>
 * Expected result: <b>3 passed, 1 failed, 0 errors</b>.
 */
public class JSUnitRunnerLayer4Test extends ServoyRunnerTestBase
{

	/** globals.js content: 3 passing tests + 1 intentionally failing test. */
	private static final String GLOBALS_JS =
		// ---- inline assertion helper (no test_ prefix, won't be run as a test) ----
		"/**\n * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555550\"}\n */\n" +
		"function _l4_assertEqual(expected, actual) {\n" +
		"\tif (expected !== actual)\n" +
		"\t\tthrow new Error('Expected: <' + expected + '> but was: <' + actual + '>');\n" +
		"}\n\n" +
		// ---- test_mathAddition: 2+2 == 4  -> PASS ----
		"/**\n * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555551\"}\n */\n" +
		"function test_mathAddition() {\n" +
		"\t_l4_assertEqual(4, 2 + 2);\n" +
		"}\n\n" +
		// ---- test_stringConcatenation: 'hello'+'world' == 'helloworld'  -> PASS ----
		"/**\n * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555552\"}\n */\n" +
		"function test_stringConcatenation() {\n" +
		"\t_l4_assertEqual('helloworld', 'hello' + 'world');\n" +
		"}\n\n" +
		// ---- test_booleanComparison: 1 < 2  -> PASS ----
		"/**\n * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555553\"}\n */\n" +
		"function test_booleanComparison() {\n" +
		"\tif (!(1 < 2)) throw new Error('Expected 1 < 2 to be true');\n" +
		"}\n\n" +
		// ---- test_mathFailure: 5 != 2+2  -> intentional ERROR ----
		"/**\n * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555554\"}\n */\n" +
		"function test_mathFailure() {\n" +
		"\t// Intentional: 5 != 4 -> throws Error -> reported as ?? ERROR\n" +
		"\t_l4_assertEqual(5, 2 + 2);\n" +
		"}\n";

	private JSUnitRunnerService runner;

	/**
	 * Cached result of {@code runTests("ALL")} - computed once for the whole class,
	 * not once per test method. Avoids launching the headless client 10 times.
	 */
	private static String cachedAllResult;

	/** Guards one-time class setup inside @Before to keep JUnit 4 instance methods. */
	private static boolean classSetUpDone = false;

	/** allResult for the current test - set from the cache in setUp(). */
	private String allResult;

	public JSUnitRunnerLayer4Test() {
		super("test_layer4_suite", "servoy_resources");
	}

	@Before
	public void setUp() throws Exception
	{
		runner = new JSUnitRunnerService();

		// 1. SWT must be available.
		assertNotNull("No Display available - test requires a running Eclipse UI",
			Display.getDefault());

		// 2. Skip if no Servoy app server - same guard as Layer 3.
		waitForAppServer();

		// 3. One-time setup: create solution, activate it, run the headless client once.
		if (!classSetUpDone)
		{
			classSetUpDone = true; // Set first - prevents re-running if later steps throw
			ensureTestSolutionInWorkspace((solPrj, monitor) -> {
				try {
					// Always force-write globals.js so content changes (e.g. new test functions)
					// are picked up even when the file already exists from a previous test run.
					IFile globalsFile = solPrj.getFile("globals.js");
					byte[] globalsBytes = GLOBALS_JS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
					java.io.ByteArrayInputStream globalsStream = new java.io.ByteArrayInputStream(globalsBytes);
					if (globalsFile.exists())
							globalsFile.setContents(globalsStream, true, false, monitor);
					else
						globalsFile.create(globalsStream, true, monitor);
				} catch (CoreException e) {
					fail("Cannot create globals file: " + e.getMessage());
				}
			});
			ensureActiveProject();
			cachedAllResult = runOnBackgroundThread(() -> runner.runTests("ALL", TIMEOUT_SECONDS));
		}

		allResult = cachedAllResult;
		assertNotNull("Class setup did not complete - allResult is null", allResult);
	}

	// -----------------------------------------------------------------------
	// Summary counts
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_passCountIsThree()
	{
		assertEquals(
			"Expected exactly 3 passing tests (test_mathAddition, test_stringConcatenation, test_booleanComparison); result:\n" + allResult,
			3, extractPassedCount(allResult));
	}

	@Test
	public void testLayer4_failCountIsZero()
	{
		assertEquals(
			"Expected 0 JSUnit failures (intentional problem shows as ERROR, not FAILURE); result:\n" + allResult,
			0, extractFailedCount(allResult));
	}

	@Test
	public void testLayer4_errorCountIsOne()
	{
		assertEquals(
			"Expected exactly 1 error (test_mathFailure throws intentionally); result:\n" + allResult,
			1, extractErrorCount(allResult));
	}

	// -----------------------------------------------------------------------
	// Output format
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_summaryTablePresent()
	{
		assertTrue("Output must contain the markdown table header",
			allResult.contains("| Passed"));
		assertTrue("Output must contain the table separator line",
			allResult.contains("|:-"));
	}

	@Test
	public void testLayer4_failedSectionPresent()
	{
		assertTrue("Output must contain a 'Failed / Error tests:' section when failures exist",
			allResult.contains("Failed / Error tests:"));
	}

	// -----------------------------------------------------------------------
	// Test-name and error-detail presence
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_failureTestNameInOutput()
	{
		assertTrue("Result must mention test_mathFailure in the failure section",
			allResult.contains("test_mathFailure"));
	}

	@Test
	public void testLayer4_errorMessageContainsExpectedValue()
	{
		// _l4_assertEqual throws: "Expected: <5> but was: <4>"
		assertTrue("Error output must include the expected value from the intentional failure",
			allResult.contains("Expected: <5>"));
	}

	@Test
	public void testLayer4_errorMessageContainsActualValue()
	{
		assertTrue("Error output must include the actual value from the intentional failure",
			allResult.contains("but was: <4>"));
	}

	@Test
	public void testLayer4_outputContainsResultsHeader()
	{
		assertTrue("Output must start with the JSUnit results header",
			allResult.contains("**JSUnit Test Results**"));
	}

	// -----------------------------------------------------------------------
	// Scope variant: "globals" should return the same counts as "ALL"
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_globalsScope_sameCounts() throws Exception
	{
		String globalsResult = runOnBackgroundThread(() -> runner.runTests("globals", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"globals\") must not return null", globalsResult);

		int allPassed = extractPassedCount(allResult);
		int allFailed = extractFailedCount(allResult);

		assertEquals(
			"runTests(\"globals\") passed-count must equal runTests(\"ALL\"); globals result:\n" + globalsResult,
			allPassed, extractPassedCount(globalsResult));
		assertEquals(
			"runTests(\"globals\") failed-count must equal runTests(\"ALL\"); globals result:\n" + globalsResult,
			allFailed, extractFailedCount(globalsResult));
	}

	// -----------------------------------------------------------------------
	// Scope name normalisation variants
	// (exercises buildTestTarget() string-stripping branches)
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_dotJsExtension_sameAsAll() throws Exception
	{
		// buildTestTarget() strips a trailing ".js" before resolving the scope name.
		// "globals.js" -> "globals" -> global scope -> same counts as ALL.
		String result = runOnBackgroundThread(() -> runner.runTests("globals.js", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"globals.js\") must not return null", result);
		assertFalse("runTests(\"globals.js\") must not be a runner-level error; result:\n" +
			result.substring(0, Math.min(result.length(), 120)),
			result.startsWith("Error"));
		assertEquals(
			"Passed count for \"globals.js\" must equal \"ALL\"; result:\n" + result,
			extractPassedCount(allResult), extractPassedCount(result));
		assertEquals(
			"Error count for \"globals.js\" must equal \"ALL\"; result:\n" + result,
			extractErrorCount(allResult), extractErrorCount(result));
	}

	@Test
	public void testLayer4_slashPrefixPath_sameAsAll() throws Exception
	{
		// buildTestTarget() strips everything up to and including the last '/'.
		// "some/path/globals" -> "globals" -> global scope -> same counts as ALL.
		String result = runOnBackgroundThread(() -> runner.runTests("some/path/globals", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"some/path/globals\") must not return null", result);
		assertFalse("runTests(\"some/path/globals\") must not be a runner-level error; result:\n" +
			result.substring(0, Math.min(result.length(), 120)),
			result.startsWith("Error"));
		assertEquals(
			"Passed count for \"some/path/globals\" must equal \"ALL\"; result:\n" + result,
			extractPassedCount(allResult), extractPassedCount(result));
		assertEquals(
			"Error count for \"some/path/globals\" must equal \"ALL\"; result:\n" + result,
			extractErrorCount(allResult), extractErrorCount(result));
	}

	@Test
	public void testLayer4_dotNotation_sameAsAll() throws Exception
	{
		// buildTestTarget() strips everything up to and including the last '.'.
		// "scopes.globals" -> "globals" -> global scope -> same counts as ALL.
		String result = runOnBackgroundThread(() -> runner.runTests("scopes.globals", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"scopes.globals\") must not return null", result);
		assertFalse("runTests(\"scopes.globals\") must not be a runner-level error; result:\n" +
			result.substring(0, Math.min(result.length(), 120)),
			result.startsWith("Error"));
		assertEquals(
			"Passed count for \"scopes.globals\" must equal \"ALL\"; result:\n" + result,
			extractPassedCount(allResult), extractPassedCount(result));
		assertEquals(
			"Error count for \"scopes.globals\" must equal \"ALL\"; result:\n" + result,
			extractErrorCount(allResult), extractErrorCount(result));
	}

	// -----------------------------------------------------------------------
	// Additional count / table correctness
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_ignoredCountIsZero()
	{
		// The layer4 suite has no @Ignore / ignored tests; the 4th table column must be 0.
		assertEquals(
			"Expected ignored count = 0; result:\n" + allResult,
			0, extractCount(allResult, 3));
	}

	@Test
	public void testLayer4_totalTestCountIsFour()
	{
		// 3 pass + 0 fail + 1 error = 4 test functions in globals.js.
		int total = extractPassedCount(allResult) + extractFailedCount(allResult) + extractErrorCount(allResult);
		assertEquals(
			"Expected total test count = 4 (3 pass + 1 error); result:\n" + allResult,
			4, total);
	}

	// -----------------------------------------------------------------------
	// Output format / icon correctness
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_errorEmojiPresent()
	{
		// formatResults() uses "ERROR" prefix for ERROR results, not emojis.
		assertTrue("Output must contain the ERROR marker for the intentional error",
			allResult.contains("ERROR"));
	}

	@Test
	public void testLayer4_allPassedMessageAbsent()
	{
		// "? All X test(s) passed!" is only emitted when failed == 0 && errors == 0.
		// With 1 error this branch must be skipped entirely.
		assertFalse(
			"The 'all passed' success line must NOT appear when errors > 0; result:\n" + allResult,
			allResult.contains("All") && allResult.contains("test(s) passed!"));
	}

	@Test
	public void testLayer4_resultIsNotAnErrorMessage()
	{
		// The runner itself must not have failed (e.g. no active project, timeout).
		// Only an individual test should have errored.
		assertFalse(
			"Result must not start with 'Error:' (runner-level failure); result:\n" +
				allResult.substring(0, Math.min(allResult.length(), 120)),
			allResult.startsWith("Error"));
	}

	@Test
	public void testLayer4_errorDetailIsFullMessage()
	{
		// _l4_assertEqual throws: "Expected: <5> but was: <4>"
		// The full message must appear as one unbroken string in the trace.
		assertTrue("Error detail must contain the complete thrown message as one string",
			allResult.contains("Expected: <5> but was: <4>"));
	}

	// -----------------------------------------------------------------------
	// Keyword variants on the layer4 solution
	// -----------------------------------------------------------------------

	@Test
	public void testLayer4_formsScope_doesNotCrash() throws Exception
	{
		// test_layer4_suite has no forms with test_ methods.
		// runTests("FORMS") must return the "No forms" notice, never null or throw.
		String result = runOnBackgroundThread(() -> runner.runTests("FORMS", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"FORMS\") must not return null for layer4 solution", result);
	}

	@Test
	public void testLayer4_modulesScope_doesNotCrash() throws Exception
	{
		// test_layer4_suite has no modules.
		// runTests("MODULES") must return the "No modules" notice, never null or throw.
		String result = runOnBackgroundThread(() -> runner.runTests("MODULES", TIMEOUT_SECONDS));
		assertNotNull("runTests(\"MODULES\") must not return null for layer4 solution", result);
	}

}

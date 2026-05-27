/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.TestFileService;

/**
 * Layer 2 PDE integration tests for {@link TestFileService#addTestMethod}.
 * <p>
 * Covers all scenarios from unittest.txt for the addTestMethod tool:
 * <ul>
 *   <li>Return value: "Added" on first call, "Updated" on second call, contains method name</li>
 *   <li>Body wrapping: body-only code is wrapped with function declaration and closing brace</li>
 *   <li>Defensive body extraction: if AI passes a complete function, body is extracted and NOT nested</li>
 *   <li>@properties annotation: typeid:24 present, UUID has correct format, each method has unique UUID</li>
 *   <li>Multi-line body: all lines preserved correctly</li>
 *   <li>Nested braces in body: brace matching is correct</li>
 *   <li>Multi-method coexistence: up to 3+ distinct methods can coexist</li>
 *   <li>Duplicate prevention (Fix 13): calling the same name N times yields exactly 1 occurrence</li>
 *   <li>Update: second call replaces body, old body no longer present</li>
 *   <li>Error conditions: non-existent file, non-existent solution, invalid name prefix</li>
 *   <li>Special JSUnit names (setUp, tearDown) rejected because they lack the test_ prefix</li>
 *   <li>Empty body: creates a syntactically valid empty function</li>
 * </ul>
 */
public class AddTestMethodIntegrationTest
{   
	// Neutral name - NOT an existing Servoy solution so Servoy never tries to
	// load it and show blocking error dialogs in the PDE test workspace.
	private static final String SOLUTION_NAME = "test_layer2_workspace";
	private static final String TEST_FILE_NAME = "test_addmethodtest.js";

	private TestFileService service;  
	private ServoyProject servoyProject;

	@Before
	public void setUp()
	{
		service = TestFileService.getInstance();

		// Create a plain project in the test workspace (no Servoy natures/builders).
		// IWorkspace.run() acquires the workspace lock atomically, avoiding
		// ResourceException from concurrent Servoy startup jobs holding the lock.
		try
		{
			final IProject[] holder = new IProject[1];
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
				IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(SOLUTION_NAME);
				if (!p.exists())
				{
					IProjectDescription desc = ResourcesPlugin.getWorkspace()
						.newProjectDescription(SOLUTION_NAME);
					p.create(desc, monitor);
				}
				if (!p.isOpen())
				{
					p.open(monitor);
				}
				holder[0] = p;
			}, new NullProgressMonitor());
			servoyProject = new ServoyProject();
			servoyProject.setProject(holder[0]);
		}
		catch (Throwable e)
		{
			System.err.println("[AddTestMethodIntegrationTest.setUp] FAILED: " +
				e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace(System.err);
			servoyProject = null;
		}
		assumeNotNull("Failed to create test project in workspace - skipping Layer 2 tests",
			servoyProject);

		// Clean slate: OS-level delete then refresh, consistent with CreateTestFileIntegrationTest.
		deleteFileIfExists(TEST_FILE_NAME);

		// Create a fresh test file before each test
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);
	}

	@After
	public void tearDown()
	{
		deleteFileIfExists(TEST_FILE_NAME);
	}

	// -----------------------------------------------------------------------
	// Return value format
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_firstAdd_returnsAdded() throws Exception
	{
		String result = service.addTestMethod(TEST_FILE_NAME, "test_newMethod",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		assertTrue("First add should say 'Added': " + result, result.contains("Added"));
	}

	@Test
	public void testAddTestMethod_secondCall_returnsUpdated() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_existingMethod",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);
		String result = service.addTestMethod(TEST_FILE_NAME, "test_existingMethod",
			"    jsunit.assertFalse(false);", SOLUTION_NAME);

		assertTrue("Second call on existing method should say 'Updated': " + result,
			result.contains("Updated"));
	}

	@Test
	public void testAddTestMethod_returnValueContainsMethodName() throws Exception
	{
		String result = service.addTestMethod(TEST_FILE_NAME, "test_namedReturn",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		assertFalse("Should succeed: " + result, result.startsWith("Error"));
		assertTrue("Return value should contain the method name: " + result,
			result.contains("test_namedReturn"));
	}

	// -----------------------------------------------------------------------
	// Body wrapping (unittest.txt §2: testCode is body-only; tool adds declaration)
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_methodAppearsInFile() throws Exception
	{
		String result = service.addTestMethod(TEST_FILE_NAME, "test_simple",
			"    jsunit.assertEquals(\"1+1\", 2, 1 + 1);", SOLUTION_NAME);

		assertFalse("addTestMethod should succeed but returned: " + result, result.startsWith("Error"));
		assertTrue("Result should contain the method name", result.contains("test_simple"));

		String fileContent = readFile(TEST_FILE_NAME);
		assertTrue("Function should be in the file", fileContent.contains("function test_simple("));
	}

	@Test
	public void testAddTestMethod_bodyOnlyCode_wrappedWithFunctionDeclaration() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_wrapped",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		// The service must have added the function keyword and opening brace
		assertTrue("File should contain function declaration",
			content.contains("function test_wrapped() {"));
		// The function must be closed
		assertTrue("File should contain closing brace", content.contains("}"));
	}

	@Test
	public void testAddTestMethod_completeFunctionPassedAsCode_noNestedDeclaration() throws Exception
	{
		// unittest.txt §2: if AI accidentally passes a complete function declaration
		// instead of body-only, the service must extract the body and NOT create
		// a nested "function test_x() { function test_x() { ... } }" structure.
		String fullFunction = "function test_defarg() {\n    jsunit.assertEquals(\"x\", 1, 1);\n}";
		service.addTestMethod(TEST_FILE_NAME, "test_defarg", fullFunction, SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		// Exactly one declaration - never two (nested)
		assertEquals("Exactly one function declaration must exist after body-extraction",
			1, countOccurrences(content, "function test_defarg("));
	}

	// -----------------------------------------------------------------------
	// @properties annotation (unittest.txt §4: typeid:24, UUID format, uniqueness)
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_hasPropertiesAnnotation() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_annotated",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String fileContent = readFile(TEST_FILE_NAME);

		assertTrue("File should contain @properties={typeid:24",
			fileContent.contains("@properties={typeid:24"));
	}

	@Test
	public void testAddTestMethod_methodHasTypeid24InAnnotation() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_typeid",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		// unittest.txt: typeid 24 = function
		assertTrue("@properties should have typeid:24", content.contains("typeid:24"));
	}

	@Test
	public void testAddTestMethod_uuidFormatIsValid() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_uuidfmt",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		// generateUUID() = UUID.randomUUID().toString().toUpperCase()
		// Standard UUID pattern: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX (uppercase hex)
		Pattern p = Pattern.compile(
			"uuid:\"([A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12})\"");
		assertTrue("UUID in @properties must match standard UUID format (uppercase)",
			p.matcher(content).find());
	}

	@Test
	public void testAddTestMethod_twoMethodsHaveDifferentUuids() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_uuid1",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_uuid2",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		Pattern p = Pattern.compile("uuid:\"([^\"]+)\"");
		Matcher m = p.matcher(content);
		List<String> uuids = new ArrayList<>();
		while (m.find())
			uuids.add(m.group(1));

		assertTrue("Should have at least two UUIDs in the file", uuids.size() >= 2);
		assertEquals("All UUIDs must be unique (no duplicates)",
			uuids.size(), new HashSet<>(uuids).size());
	}

	// -----------------------------------------------------------------------
	// Body content: multi-line, nested braces
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_multiLineBody_allLinesPresent() throws Exception
	{
		// unittest.txt §2: Arrange-Act-Assert pattern with multi-line body
		String body = "    // Arrange\n" +
			"    var x = 5;\n" +
			"    // Act\n" +
			"    var y = x * 2;\n" +
			"    // Assert\n" +
			"    jsunit.assertEquals(\"doubled\", 10, y);";
		service.addTestMethod(TEST_FILE_NAME, "test_multiline", body, SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		assertTrue("Arrange comment missing", content.contains("// Arrange"));
		assertTrue("Var x line missing", content.contains("var x = 5;"));
		assertTrue("Act comment missing", content.contains("// Act"));
		assertTrue("Var y line missing", content.contains("var y = x * 2;"));
		assertTrue("Assert comment missing", content.contains("// Assert"));
		assertTrue("Assertion missing", content.contains("jsunit.assertEquals(\"doubled\", 10, y)"));
	}

	@Test
	public void testAddTestMethod_bodyWithNestedBraces_braceMatchingCorrect() throws Exception
	{
		// unittest.txt §2: body can contain if/else blocks with nested braces
		String body = "    var result;\n" +
			"    if (true) {\n" +
			"        result = 1;\n" +
			"    } else {\n" +
			"        result = 0;\n" +
			"    }\n" +
			"    jsunit.assertEquals(\"nested braces\", 1, result);";
		service.addTestMethod(TEST_FILE_NAME, "test_nestedbr", body, SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		assertTrue("if-branch missing", content.contains("if (true) {"));
		assertTrue("else-branch missing", content.contains("} else {"));
		assertTrue("Assertion missing",
			content.contains("jsunit.assertEquals(\"nested braces\", 1, result)"));
		// Function declaration must still exist (nested braces must not close it prematurely)
		assertTrue("Function declaration must still exist",
			content.contains("function test_nestedbr() {"));
	}

	@Test
	public void testAddTestMethod_emptyBody_createsValidFunction() throws Exception
	{
		// Empty body is valid: creates function test_x() { }
		String result = service.addTestMethod(TEST_FILE_NAME, "test_empty",
			"", SOLUTION_NAME);

		assertFalse("Empty body should still succeed: " + result, result.startsWith("Error"));

		String content = readFile(TEST_FILE_NAME);
		assertTrue("Function declaration should exist for empty-body method",
			content.contains("function test_empty() {"));
	}

	// -----------------------------------------------------------------------
	// Multi-method coexistence
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_twoDistinctMethodsCoexist() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_alpha",
			"    jsunit.assertEquals(1, 1);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_beta",
			"    jsunit.assertEquals(2, 2);", SOLUTION_NAME);

		String fileContent = readFile(TEST_FILE_NAME);

		assertTrue("test_alpha should be in file", fileContent.contains("function test_alpha("));
		assertTrue("test_beta should be in file", fileContent.contains("function test_beta("));
	}

	@Test
	public void testAddTestMethod_threeMethodsAllCoexist() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_alpha",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_beta",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_gamma",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		assertTrue("test_alpha missing", content.contains("function test_alpha("));
		assertTrue("test_beta missing", content.contains("function test_beta("));
		assertTrue("test_gamma missing", content.contains("function test_gamma("));
	}

	// -----------------------------------------------------------------------
	// Duplicate prevention (Fix 13 regression) and update behaviour
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_calledTwice_onlyOneOccurrence() throws Exception
	{
		// Fix 13 regression: calling addTestMethod twice with the same name should
		// replace the first occurrence, not append a second one.
		service.addTestMethod(TEST_FILE_NAME, "test_dup",
			"    jsunit.assertEquals(\"v1\", 1, 1);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_dup",
			"    jsunit.assertEquals(\"v2\", 2, 2);", SOLUTION_NAME);

		String fileContent = readFile(TEST_FILE_NAME);

		assertEquals("Fix 13: exactly one occurrence after two calls",
			1, countOccurrences(fileContent, "function test_dup("));
	}

	@Test
	public void testAddTestMethod_calledThreeTimes_exactlyOneOccurrence() throws Exception
	{
		// Extended Fix 13: three calls should also result in exactly one occurrence
		service.addTestMethod(TEST_FILE_NAME, "test_triple",
			"    jsunit.assertEquals(\"v1\", 1, 1);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_triple",
			"    jsunit.assertEquals(\"v2\", 2, 2);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_triple",
			"    jsunit.assertEquals(\"v3\", 3, 3);", SOLUTION_NAME);

		String content = readFile(TEST_FILE_NAME);

		assertEquals("Exactly one occurrence after three calls",
			1, countOccurrences(content, "function test_triple("));
	}

	@Test
	public void testAddTestMethod_secondCallUpdatesBody() throws Exception
	{
		service.addTestMethod(TEST_FILE_NAME, "test_update",
			"    jsunit.assertEquals(\"v1\", 1, 1);", SOLUTION_NAME);
		service.addTestMethod(TEST_FILE_NAME, "test_update",
			"    jsunit.assertEquals(\"v2\", 99, 99);", SOLUTION_NAME);

		String fileContent = readFile(TEST_FILE_NAME);

		assertTrue("File should contain new body (v2)", fileContent.contains("99, 99"));
		assertFalse("File should not contain old body (v1)", fileContent.contains("\"v1\","));
	}

	// -----------------------------------------------------------------------
	// Error conditions (unittest.txt §5)
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_invalidName_noTestPrefix_returnsError() throws Exception
	{
		// unittest.txt: method name must start with test_
		String result = service.addTestMethod(TEST_FILE_NAME, "doSomething",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		assertTrue("Method name without test_ prefix should return Error: " + result,
			result.startsWith("Error"));
		assertTrue("Error should mention 'test_'", result.contains("test_"));
	}

	@Test
	public void testAddTestMethod_setUpNotAllowed_returnsError() throws Exception
	{
		// 'setUp' is a valid JSUnit special function, but addTestMethod requires the
		// test_ prefix.  This enforces the rule from unittest.txt §3.
		String result = service.addTestMethod(TEST_FILE_NAME, "setUp",
			"    // initialization code", SOLUTION_NAME);

		assertTrue("setUp should return Error (no test_ prefix): " + result,
			result.startsWith("Error"));
		assertTrue("Error should mention 'test_' prefix", result.contains("test_"));
	}

	@Test
	public void testAddTestMethod_onNonExistentFile_returnsError() throws Exception
	{
		// unittest.txt §2: the test file must already exist (created by createTestFile)
		String result = service.addTestMethod("test_doesnotexist.js", "test_something",
			"    jsunit.assertTrue(true);", SOLUTION_NAME);

		assertTrue("Non-existent file should return Error: " + result, result.startsWith("Error"));
		assertTrue("Error should mention 'does not exist'",
			result.toLowerCase().contains("does not exist"));
	}

	@Test
	public void testAddTestMethod_solutionNotFound_returnsError() throws Exception
	{
		String result = service.addTestMethod(TEST_FILE_NAME, "test_something",
			"    jsunit.assertTrue(true);", "nonexistent_solution_xyz");

		assertTrue("Non-existent solution should return Error: " + result,
			result.startsWith("Error"));
		assertTrue("Error should mention solution not found",
			result.toLowerCase().contains("not found"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Reads the named file from the test project and returns its content as a UTF-8 string.
	 * Uses try-with-resources to properly close the InputStream and release the file handle
	 * (important on Windows, where open handles block subsequent deletion).
	 */
	private String readFile(String fileName) throws Exception
	{
		IFile file = servoyProject.getProject().getFile(fileName);
		try (java.io.InputStream is = file.getContents())
		{
			return new String(is.readAllBytes(), "UTF-8");
		}
	}

	private int countOccurrences(String text, String sub)
	{
		int count = 0;
		int idx = 0;
		while ((idx = text.indexOf(sub, idx)) != -1)
		{
			count++;
			idx += sub.length();
		}
		return count;
	}

	/**
	 * OS-level delete for the named file, then refreshes Eclipse's resource cache.
	 */
	private void deleteFileIfExists(String fileName)
	{
		if (servoyProject == null) return;
		try
		{
			IProject project = servoyProject.getProject();
			java.io.File f = new java.io.File(project.getLocation().toFile(), fileName);
			if (f.exists()) f.delete();
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		}
		catch (Exception ignored)
		{
		}
	}
}

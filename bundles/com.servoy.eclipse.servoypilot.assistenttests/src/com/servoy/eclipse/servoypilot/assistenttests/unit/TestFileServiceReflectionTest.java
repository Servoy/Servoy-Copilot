/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.TestFileService;

/**
 * Layer 1 unit tests for the private string-manipulation methods of {@link TestFileService}.
 * <p>
 * All methods under test are pure Java/string logic with no Eclipse or Servoy dependencies.
 * Access is via reflection ({@code setAccessible(true)}) to avoid requiring a production
 * code refactoring just to expose them for testing.
 * <p>
 * These tests cover the core algorithms of the unit test assistant:
 * <ul>
 *   <li>{@code removeAllOccurrences} - brace-depth removal of functions + JSDoc blocks</li>
 *   <li>{@code generateTestMethod} - @properties annotation generation</li>
 *   <li>{@code extractFunctionBody} - defensive body extractor</li>
 *   <li>{@code generateTestFileHeader} - file header generation</li>
 * </ul>
 */
public class TestFileServiceReflectionTest
{
	private TestFileService service;
	private Method removeAllOccurrences;
	private Method generateTestMethod;
	private Method extractFunctionBody;
	private Method generateTestFileHeader;

	@Before
	public void setUp() throws Exception
	{
		// Instantiate via reflection - constructor is private (singleton guard)
		Constructor<TestFileService> ctor = TestFileService.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		service = ctor.newInstance();

		removeAllOccurrences = TestFileService.class.getDeclaredMethod("removeAllOccurrences", String.class, String.class);
		removeAllOccurrences.setAccessible(true);

		generateTestMethod = TestFileService.class.getDeclaredMethod("generateTestMethod", String.class, String.class);
		generateTestMethod.setAccessible(true);

		extractFunctionBody = TestFileService.class.getDeclaredMethod("extractFunctionBody", String.class, String.class);
		extractFunctionBody.setAccessible(true);

		generateTestFileHeader = TestFileService.class.getDeclaredMethod("generateTestFileHeader");
		generateTestFileHeader.setAccessible(true);
	}

	// -----------------------------------------------------------------------
	// removeAllOccurrences
	// -----------------------------------------------------------------------

	@Test
	public void testRemoveAllOccurrences_singleOccurrenceWithJsdoc() throws Exception
	{
		String content =
			"/**\n * @properties={typeid:24,uuid:\"AAAA\"}\n */\nfunction test_foo() {\n    jsunit.assertTrue(true);\n}\n";
		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertFalse("Function declaration should be removed", result.contains("function test_foo("));
		assertFalse("JSDoc @properties should be removed", result.contains("@properties={typeid:24,uuid:\"AAAA\"}"));
	}

	/**
	 * THE FIX 13 SCENARIO: two identical occurrences of the same function name
	 * (can happen when the AI appends instead of replacing). Both must be removed.
	 */
	@Test
	public void testRemoveAllOccurrences_twoDuplicates_bothRemoved() throws Exception
	{
		String content =
			"/**\n * @properties={typeid:24,uuid:\"AAAA\"}\n */\nfunction test_foo() {\n    // v1\n}\n\n" +
			"/**\n * @properties={typeid:24,uuid:\"BBBB\"}\n */\nfunction test_foo() {\n    // v2\n}\n";

		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertFalse("Both occurrences of test_foo should be removed", result.contains("function test_foo("));
		assertEquals("Remaining content should be blank", "", result.trim());
	}

	@Test
	public void testRemoveAllOccurrences_nameNotPresent_contentUnchanged() throws Exception
	{
		String content = "function test_bar() {\n    jsunit.assertTrue(true);\n}\n";
		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertEquals("Content should be unchanged when name is not found", content, result);
	}

	@Test
	public void testRemoveAllOccurrences_otherMethodsUntouched() throws Exception
	{
		String content =
			"/**\n * @properties={typeid:24,uuid:\"AAAA\"}\n */\nfunction test_alpha() {\n    jsunit.assertEquals(1, 1);\n}\n\n" +
			"/**\n * @properties={typeid:24,uuid:\"BBBB\"}\n */\nfunction test_beta() {\n    jsunit.assertEquals(2, 2);\n}\n";

		String result = (String)removeAllOccurrences.invoke(service, content, "test_alpha");

		assertFalse("test_alpha should be removed", result.contains("function test_alpha("));
		assertTrue("test_beta should remain intact", result.contains("function test_beta("));
		assertTrue("test_beta JSDoc should remain intact", result.contains("@properties={typeid:24,uuid:\"BBBB\"}"));
	}

	@Test
	public void testRemoveAllOccurrences_noJsdocPreceding() throws Exception
	{
		// A function without any preceding JSDoc should still be removed
		String content = "function test_foo() {\n    jsunit.assertTrue(true);\n}\n";
		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertFalse("Function should be removed even without preceding JSDoc", result.contains("function test_foo("));
	}

	@Test
	public void testRemoveAllOccurrences_nestedBracesHandled() throws Exception
	{
		// Function body with nested braces must be removed correctly (not stop at first })
		String content =
			"/**\n * @properties={typeid:24,uuid:\"AAAA\"}\n */\n" +
			"function test_nested() {\n" +
			"    if (true) { jsunit.assertTrue(true); }\n" +
			"}\n";

		String result = (String)removeAllOccurrences.invoke(service, content, "test_nested");

		assertFalse("Function with nested braces should be fully removed", result.contains("function test_nested("));
	}

	// -----------------------------------------------------------------------
	// generateTestMethod
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateTestMethod_hasPropertiesAnnotationWithTypeid24() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue("Should contain @properties={typeid:24", result.contains("@properties={typeid:24"));
	}

	@Test
	public void testGenerateTestMethod_hasUuidInAnnotation() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue("Should contain uuid: key in @properties", result.contains(",uuid:\""));
		String uuid = extractUuid(result);
		assertNotNull("UUID should be extractable", uuid);
		assertFalse("UUID should not be empty", uuid.isEmpty());
	}

	@Test
	public void testGenerateTestMethod_eachCallGeneratesUniqueUuid() throws Exception
	{
		String result1 = (String)generateTestMethod.invoke(service, "test_a", "    jsunit.assertTrue(true);");
		String result2 = (String)generateTestMethod.invoke(service, "test_b", "    jsunit.assertTrue(true);");

		String uuid1 = extractUuid(result1);
		String uuid2 = extractUuid(result2);
		assertNotEquals("Each call should produce a different UUID", uuid1, uuid2);
	}

	@Test
	public void testGenerateTestMethod_functionDeclarationContainsMethodName() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue("Should contain 'function test_myFunc()'", result.contains("function test_myFunc()"));
	}

	@Test
	public void testGenerateTestMethod_bodyIsEmbedded() throws Exception
	{
		String body = "    jsunit.assertEquals(\"expect\", 42, 40 + 2);";
		String result = (String)generateTestMethod.invoke(service, "test_calc", body);

		assertTrue("Body content should appear inside the generated function", result.contains(body));
	}

	@Test
	public void testGenerateTestMethod_wrapsBodyWhenFullFunctionPassed() throws Exception
	{
		// If AI passes a full function declaration, the body should be extracted
		String fullDecl = "function test_calc() {\n    jsunit.assertEquals(5, 2 + 3);\n}";
		String result = (String)generateTestMethod.invoke(service, "test_calc", fullDecl);

		// Only one function declaration should appear
		int count = countOccurrences(result, "function test_calc(");
		assertEquals("Only one function declaration should exist in output", 1, count);
	}

	// -----------------------------------------------------------------------
	// extractFunctionBody
	// -----------------------------------------------------------------------

	@Test
	public void testExtractFunctionBody_stripsOuterDeclaration() throws Exception
	{
		String code = "function test_foo() {\n    jsunit.assertEquals(1, 1);\n}";
		String result = (String)extractFunctionBody.invoke(service, code, "test_foo");

		assertFalse("Result should not start with 'function'", result.trim().startsWith("function"));
		assertTrue("Body content should be retained", result.contains("jsunit.assertEquals(1, 1)"));
	}

	@Test
	public void testExtractFunctionBody_bareBody_returnedUnchanged() throws Exception
	{
		String body = "    jsunit.assertEquals(1, 1);\n    jsunit.assertTrue(true);";
		String result = (String)extractFunctionBody.invoke(service, body, "test_foo");

		assertEquals("Bare body (no function wrapper) should be returned as-is", body, result);
	}

	@Test
	public void testExtractFunctionBody_nestedBraces_outerPairUsed() throws Exception
	{
		String code = "function test_foo() {\n    if (x) { return { a: 1 }; }\n}";
		String result = (String)extractFunctionBody.invoke(service, code, "test_foo");

		assertTrue("Nested if block should be in extracted body", result.contains("if (x)"));
		assertTrue("Nested object literal should be in extracted body", result.contains("return { a: 1 }"));
	}

	@Test
	public void testExtractFunctionBody_nullInput_returnsNull() throws Exception
	{
		// Pass null via Object array to avoid varargs ambiguity
		String result = (String)extractFunctionBody.invoke(service, new Object[] { null, "test_foo" });

		assertNull("null input should return null", result);
	}

	@Test
	public void testExtractFunctionBody_emptyInput_returnsEmpty() throws Exception
	{
		String result = (String)extractFunctionBody.invoke(service, "", "test_foo");

		// Empty string satisfies trim().isEmpty() check -> returned as-is
		assertEquals("Empty string should be returned as-is", "", result);
	}

	// -----------------------------------------------------------------------
	// generateTestFileHeader
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateTestFileHeader_notNull() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertNotNull("Header should not be null", result);
		assertFalse("Header should not be empty", result.trim().isEmpty());
	}

	@Test
	public void testGenerateTestFileHeader_isBlockComment() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertTrue("Header should open with block comment /**", result.startsWith("/**"));
		assertTrue("Header should contain closing */", result.contains("*/"));
	}

	@Test
	public void testGenerateTestFileHeader_mentionsServoy() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertTrue("Header should mention Servoy or JSUnit", result.contains("Servoy") || result.contains("JSUnit"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/** Extracts the UUID value from a generated method string (content of uuid:"..."). */
	private String extractUuid(String generatedMethod)
	{
		int uuidStart = generatedMethod.indexOf("uuid:\"");
		if (uuidStart < 0) return null;
		int contentStart = uuidStart + 6;
		int contentEnd = generatedMethod.indexOf("\"", contentStart);
		if (contentEnd < 0) return null;
		return generatedMethod.substring(contentStart, contentEnd);
	}

	/** Counts non-overlapping occurrences of a substring. */
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
}

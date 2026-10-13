/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

	@BeforeEach
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

		assertFalse(result.contains("function test_foo("), "Function declaration should be removed");
		assertFalse(result.contains("@properties={typeid:24,uuid:\"AAAA\"}"), "JSDoc @properties should be removed");
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

		assertFalse(result.contains("function test_foo("), "Both occurrences of test_foo should be removed");
		assertEquals("", result.trim(), "Remaining content should be blank");
	}

	@Test
	public void testRemoveAllOccurrences_nameNotPresent_contentUnchanged() throws Exception
	{
		String content = "function test_bar() {\n    jsunit.assertTrue(true);\n}\n";
		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertEquals(content, result, "Content should be unchanged when name is not found");
	}

	@Test
	public void testRemoveAllOccurrences_otherMethodsUntouched() throws Exception
	{
		String content =
			"/**\n * @properties={typeid:24,uuid:\"AAAA\"}\n */\nfunction test_alpha() {\n    jsunit.assertEquals(1, 1);\n}\n\n" +
			"/**\n * @properties={typeid:24,uuid:\"BBBB\"}\n */\nfunction test_beta() {\n    jsunit.assertEquals(2, 2);\n}\n";

		String result = (String)removeAllOccurrences.invoke(service, content, "test_alpha");

		assertFalse(result.contains("function test_alpha("), "test_alpha should be removed");
		assertTrue(result.contains("function test_beta("), "test_beta should remain intact");
		assertTrue(result.contains("@properties={typeid:24,uuid:\"BBBB\"}"), "test_beta JSDoc should remain intact");
	}

	@Test
	public void testRemoveAllOccurrences_noJsdocPreceding() throws Exception
	{
		// A function without any preceding JSDoc should still be removed
		String content = "function test_foo() {\n    jsunit.assertTrue(true);\n}\n";
		String result = (String)removeAllOccurrences.invoke(service, content, "test_foo");

		assertFalse(result.contains("function test_foo("), "Function should be removed even without preceding JSDoc");
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

		assertFalse(result.contains("function test_nested("), "Function with nested braces should be fully removed");
	}

	// -----------------------------------------------------------------------
	// generateTestMethod
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateTestMethod_hasPropertiesAnnotationWithTypeid24() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue(result.contains("@properties={typeid:24"), "Should contain @properties={typeid:24");
	}

	@Test
	public void testGenerateTestMethod_hasUuidInAnnotation() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue(result.contains(",uuid:\""), "Should contain uuid: key in @properties");
		String uuid = extractUuid(result);
		assertNotNull(uuid, "UUID should be extractable");
		assertFalse(uuid.isEmpty(), "UUID should not be empty");
	}

	@Test
	public void testGenerateTestMethod_eachCallGeneratesUniqueUuid() throws Exception
	{
		String result1 = (String)generateTestMethod.invoke(service, "test_a", "    jsunit.assertTrue(true);");
		String result2 = (String)generateTestMethod.invoke(service, "test_b", "    jsunit.assertTrue(true);");

		String uuid1 = extractUuid(result1);
		String uuid2 = extractUuid(result2);
		assertNotEquals(uuid1, uuid2, "Each call should produce a different UUID");
	}

	@Test
	public void testGenerateTestMethod_functionDeclarationContainsMethodName() throws Exception
	{
		String result = (String)generateTestMethod.invoke(service, "test_myFunc", "    jsunit.assertTrue(true);");

		assertTrue(result.contains("function test_myFunc()"), "Should contain 'function test_myFunc()'");
	}

	@Test
	public void testGenerateTestMethod_bodyIsEmbedded() throws Exception
	{
		String body = "    jsunit.assertEquals(\"expect\", 42, 40 + 2);";
		String result = (String)generateTestMethod.invoke(service, "test_calc", body);

		assertTrue(result.contains(body), "Body content should appear inside the generated function");
	}

	@Test
	public void testGenerateTestMethod_wrapsBodyWhenFullFunctionPassed() throws Exception
	{
		// If AI passes a full function declaration, the body should be extracted
		String fullDecl = "function test_calc() {\n    jsunit.assertEquals(5, 2 + 3);\n}";
		String result = (String)generateTestMethod.invoke(service, "test_calc", fullDecl);

		// Only one function declaration should appear
		int count = countOccurrences(result, "function test_calc(");
		assertEquals(1, count, "Only one function declaration should exist in output");
	}

	// -----------------------------------------------------------------------
	// extractFunctionBody
	// -----------------------------------------------------------------------

	@Test
	public void testExtractFunctionBody_stripsOuterDeclaration() throws Exception
	{
		String code = "function test_foo() {\n    jsunit.assertEquals(1, 1);\n}";
		String result = (String)extractFunctionBody.invoke(service, code, "test_foo");

		assertFalse(result.trim().startsWith("function"), "Result should not start with 'function'");
		assertTrue(result.contains("jsunit.assertEquals(1, 1)"), "Body content should be retained");
	}

	@Test
	public void testExtractFunctionBody_bareBody_returnedUnchanged() throws Exception
	{
		String body = "    jsunit.assertEquals(1, 1);\n    jsunit.assertTrue(true);";
		String result = (String)extractFunctionBody.invoke(service, body, "test_foo");

		assertEquals(body, result, "Bare body (no function wrapper) should be returned as-is");
	}

	@Test
	public void testExtractFunctionBody_nestedBraces_outerPairUsed() throws Exception
	{
		String code = "function test_foo() {\n    if (x) { return { a: 1 }; }\n}";
		String result = (String)extractFunctionBody.invoke(service, code, "test_foo");

		assertTrue(result.contains("if (x)"), "Nested if block should be in extracted body");
		assertTrue(result.contains("return { a: 1 }"), "Nested object literal should be in extracted body");
	}

	@Test
	public void testExtractFunctionBody_nullInput_returnsNull() throws Exception
	{
		// Pass null via Object array to avoid varargs ambiguity
		String result = (String)extractFunctionBody.invoke(service, new Object[] { null, "test_foo" });

		assertNull(result, "null input should return null");
	}

	@Test
	public void testExtractFunctionBody_emptyInput_returnsEmpty() throws Exception
	{
		String result = (String)extractFunctionBody.invoke(service, "", "test_foo");

		// Empty string satisfies trim().isEmpty() check -> returned as-is
		assertEquals("", result, "Empty string should be returned as-is");
	}

	// -----------------------------------------------------------------------
	// generateTestFileHeader
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateTestFileHeader_notNull() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertNotNull(result, "Header should not be null");
		assertFalse(result.trim().isEmpty(), "Header should not be empty");
	}

	@Test
	public void testGenerateTestFileHeader_isBlockComment() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertTrue(result.startsWith("/**"), "Header should open with block comment /**");
		assertTrue(result.contains("*/"), "Header should contain closing */");
	}

	@Test
	public void testGenerateTestFileHeader_mentionsServoy() throws Exception
	{
		String result = (String)generateTestFileHeader.invoke(service);

		assertTrue(result.contains("Servoy") || result.contains("JSUnit"), "Header should mention Servoy or JSUnit");
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

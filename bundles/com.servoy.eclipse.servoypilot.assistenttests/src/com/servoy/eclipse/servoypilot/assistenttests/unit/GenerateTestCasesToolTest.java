/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.servoypilot.tools.testgeneration.IGenerateTestCasesTool;

/**
 * Layer 1 unit tests for {@link IGenerateTestCasesTool}.
 * <p>
 * Tests the private static {@code extractParameters} helper and the public
 * {@code generateTestCases} default method, both of which are pure string/template
 * logic with no Eclipse or Servoy dependencies.
 */
public class GenerateTestCasesToolTest
{
	/** Minimal anonymous implementation - the interface has no abstract methods. */
	private IGenerateTestCasesTool tool;
	private Method extractParameters;

	@Before
	public void setUp() throws Exception
	{
		tool = new IGenerateTestCasesTool()
		{
		};

		extractParameters = IGenerateTestCasesTool.class.getDeclaredMethod("extractParameters",
			String.class, String.class);
		extractParameters.setAccessible(true);
	}

	// -----------------------------------------------------------------------
	// extractParameters (private static)
	// -----------------------------------------------------------------------

	@Test
	public void testExtractParameters_noParams_returnsEmptyArray() throws Exception
	{
		String code = "function doSomething() { return 42; }";
		String[] result = (String[])extractParameters.invoke(null, code, "doSomething");

		assertNotNull(result);
		assertEquals("No parameters expected", 0, result.length);
	}

	@Test
	public void testExtractParameters_oneParam() throws Exception
	{
		String code = "function double(n) { return n * 2; }";
		String[] result = (String[])extractParameters.invoke(null, code, "double");

		assertNotNull(result);
		assertEquals("One parameter expected", 1, result.length);
		assertEquals("n", result[0]);
	}

	@Test
	public void testExtractParameters_multipleParams() throws Exception
	{
		String code = "function addNumbers(a, b, c) { return a + b + c; }";
		String[] result = (String[])extractParameters.invoke(null, code, "addNumbers");

		assertNotNull(result);
		assertEquals("Three parameters expected", 3, result.length);
		assertEquals("a", result[0]);
		assertEquals("b", result[1]);
		assertEquals("c", result[2]);
	}

	@Test
	public void testExtractParameters_whitespaceAroundParams() throws Exception
	{
		String code = "function foo( x ,  y ) { return x + y; }";
		String[] result = (String[])extractParameters.invoke(null, code, "foo");

		assertNotNull(result);
		assertEquals("Two parameters expected", 2, result.length);
		assertEquals("x", result[0].trim());
		assertEquals("y", result[1].trim());
	}

	@Test
	public void testExtractParameters_nullCode_returnsEmptyArray() throws Exception
	{
		String[] result = (String[])extractParameters.invoke(null, new Object[] { null, "foo" });

		assertNotNull(result);
		assertEquals("Null code should return empty array", 0, result.length);
	}

	@Test
	public void testExtractParameters_functionNotFound_returnsEmptyArray() throws Exception
	{
		String code = "function bar(x) { return x; }";
		String[] result = (String[])extractParameters.invoke(null, code, "foo"); // different name

		assertNotNull(result);
		assertEquals("Not-found function should return empty array", 0, result.length);
	}

	// -----------------------------------------------------------------------
	// generateTestCases (default method)
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateTestCases_outputContainsFunctionName() throws Exception
	{
		String code = "function calculate(a, b) { return a + b; }";
		String result = tool.generateTestCases(code, "calculate");

		assertNotNull(result);
		assertTrue("Output should mention the function name", result.contains("calculate"));
	}

	@Test
	public void testGenerateTestCases_outputContainsHappyPath() throws Exception
	{
		String code = "function greet(name) { return 'Hello ' + name; }";
		String result = tool.generateTestCases(code, "greet");

		assertTrue("Output should contain happy path section", result.contains("Happy Path"));
	}

	@Test
	public void testGenerateTestCases_outputContainsEdgeCases() throws Exception
	{
		String code = "function greet(name) { return 'Hello ' + name; }";
		String result = tool.generateTestCases(code, "greet");

		assertTrue("Output should contain edge case section", result.contains("Edge Case"));
	}

	@Test
	public void testGenerateTestCases_outputContainsTestPrefix() throws Exception
	{
		String code = "function calculate(a, b) { return a + b; }";
		String result = tool.generateTestCases(code, "calculate");

		assertTrue("Suggested test names should use test_ prefix", result.contains("test_calculate"));
	}

	@Test
	public void testGenerateTestCases_withParams_includesNullCheck() throws Exception
	{
		String code = "function process(input) { return input.trim(); }";
		String result = tool.generateTestCases(code, "process");

		assertTrue("Should suggest null parameter test", result.contains("Null") || result.contains("null"));
	}
}

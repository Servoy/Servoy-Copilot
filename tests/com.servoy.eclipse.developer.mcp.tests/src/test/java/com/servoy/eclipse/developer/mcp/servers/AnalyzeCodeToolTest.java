/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the private static {@code extractFunctionName} helper
 * in {@link ServoyTestingServer}.
 * <p>
 * This is pure string parsing logic: regex-based extraction of the function
 * identifier from JavaScript source code.
 */
public class AnalyzeCodeToolTest
{
	private Method extractFunctionName;

	@Before
	public void setUp() throws Exception
	{
		extractFunctionName = ServoyTestingServer.class.getDeclaredMethod("extractFunctionName",
			String.class);
		extractFunctionName.setAccessible(true);
	}

	@Test
	public void testExtractFunctionName_standardDeclaration() throws Exception
	{
		String code = "function myFunc() { return 42; }";
		String result = (String)extractFunctionName.invoke(null, code);

		assertEquals("myFunc", result);
	}

	@Test
	public void testExtractFunctionName_withParameters() throws Exception
	{
		String code = "function addNumbers(a, b) { return a + b; }";
		String result = (String)extractFunctionName.invoke(null, code);

		assertEquals("addNumbers", result);
	}

	@Test
	public void testExtractFunctionName_withJsdocAbove() throws Exception
	{
		String code = "/**\n * @properties={typeid:24,uuid:\"ABC\"}\n */\nfunction test_example() {\n    jsunit.assertTrue(true);\n}";
		String result = (String)extractFunctionName.invoke(null, code);

		assertEquals("test_example", result);
	}

	@Test
	public void testExtractFunctionName_withLeadingWhitespace() throws Exception
	{
		String code = "   function   spaced() { }";
		String result = (String)extractFunctionName.invoke(null, code);

		assertEquals("spaced", result);
	}

	@Test
	public void testExtractFunctionName_nullInput_returnsNull() throws Exception
	{
		String result = (String)extractFunctionName.invoke(null, new Object[] { null });

		assertNull("null input should return null", result);
	}

	@Test
	public void testExtractFunctionName_noFunctionKeyword_returnsNull() throws Exception
	{
		String code = "var x = 42;";
		String result = (String)extractFunctionName.invoke(null, code);

		assertNull("Code without 'function' keyword should return null", result);
	}

	@Test
	public void testExtractFunctionName_multiLineFunctionBody() throws Exception
	{
		String code = "function calculate(a, b, c) {\n    var x = a + b;\n    return x * c;\n}";
		String result = (String)extractFunctionName.invoke(null, code);

		assertEquals("calculate", result);
	}
}

/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;

/**
 * Unit tests for {@link ServoyTestingServer} tool annotations and method structure.
 * <p>
 * Tests the tool interface structure and annotations without requiring
 * the full Servoy runtime. These are plain JUnit tests that verify the
 * MCP tool contract via reflection.
 */
public class ShowFormInBrowserToolTest
{
	private Class<ServoyTestingServer> serverClass;

	@Before
	public void setUp()
	{
		serverClass = ServoyTestingServer.class;
	}

	@Test
	public void testClass_hasShowFormInBrowserMethod() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertNotNull("showFormInBrowser method should exist", method);
	}

	@Test
	public void testClass_hasCheckNGClientStatusMethod() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertNotNull("checkNGClientStatus method should exist", method);
	}

	@Test
	public void testShowFormInBrowser_hasToolAnnotation() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull("showFormInBrowser should have @Tool annotation", annotation);
		assertTrue("@Tool description should mention browser",
			annotation.description().toLowerCase().contains("browser"));
	}

	@Test
	public void testCheckNGClientStatus_hasToolAnnotation() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull("checkNGClientStatus should have @Tool annotation", annotation);
		assertTrue("@Tool description should mention NG client",
			annotation.description().toLowerCase().contains("ng client"));
	}

	@Test
	public void testShowFormInBrowser_returnsString() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertEquals("Return type should be String", String.class, method.getReturnType());
	}

	@Test
	public void testCheckNGClientStatus_returnsString() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertEquals("Return type should be String", String.class, method.getReturnType());
	}

	@Test
	public void testShowFormInBrowser_hasOneParameter() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertEquals("showFormInBrowser should have 1 parameter", 1, method.getParameterCount());
		assertEquals("Parameter should be String", String.class, method.getParameterTypes()[0]);
	}

	@Test
	public void testCheckNGClientStatus_hasNoParameters() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertEquals("checkNGClientStatus should have 0 parameters", 0, method.getParameterCount());
	}

	@Test
	public void testServerClass_isNotInterface()
	{
		// Unlike the old IShowFormInBrowserTool interface, ServoyTestingServer is a concrete class
		assertTrue("ServoyTestingServer should be a class, not an interface", !serverClass.isInterface());
	}

	@Test
	public void testShowFormInBrowser_toolName() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		Tool annotation = method.getAnnotation(Tool.class);
		assertEquals("Tool name should be 'showFormInBrowser'", "showFormInBrowser", annotation.name());
	}

	@Test
	public void testCheckNGClientStatus_toolName() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		Tool annotation = method.getAnnotation(Tool.class);
		assertEquals("Tool name should be 'checkNGClientStatus'", "checkNGClientStatus", annotation.name());
	}
}

/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;

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

	@BeforeEach
	public void setUp()
	{
		serverClass = ServoyTestingServer.class;
	}

	@Test
	public void testClass_hasShowFormInBrowserMethod() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertNotNull(method, "showFormInBrowser method should exist");
	}

	@Test
	public void testClass_hasCheckNGClientStatusMethod() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertNotNull(method, "checkNGClientStatus method should exist");
	}

	@Test
	public void testShowFormInBrowser_hasToolAnnotation() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull(annotation, "showFormInBrowser should have @Tool annotation");
		assertTrue(annotation.description().toLowerCase().contains("browser"),
			"@Tool description should mention browser");
	}

	@Test
	public void testCheckNGClientStatus_hasToolAnnotation() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull(annotation, "checkNGClientStatus should have @Tool annotation");
		assertTrue(annotation.description().toLowerCase().contains("ng client"),
			"@Tool description should mention NG client");
	}

	@Test
	public void testShowFormInBrowser_returnsString() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertEquals(String.class, method.getReturnType(), "Return type should be String");
	}

	@Test
	public void testCheckNGClientStatus_returnsString() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertEquals(String.class, method.getReturnType(), "Return type should be String");
	}

	@Test
	public void testShowFormInBrowser_hasOneParameter() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		assertEquals(1, method.getParameterCount(), "showFormInBrowser should have 1 parameter");
		assertEquals(String.class, method.getParameterTypes()[0], "Parameter should be String");
	}

	@Test
	public void testCheckNGClientStatus_hasNoParameters() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		assertEquals(0, method.getParameterCount(), "checkNGClientStatus should have 0 parameters");
	}

	@Test
	public void testServerClass_isNotInterface()
	{
		// Unlike the old IShowFormInBrowserTool interface, ServoyTestingServer is a concrete class
		assertTrue(!serverClass.isInterface(), "ServoyTestingServer should be a class, not an interface");
	}

	@Test
	public void testShowFormInBrowser_toolName() throws Exception
	{
		Method method = serverClass.getMethod("showFormInBrowser", String.class);
		Tool annotation = method.getAnnotation(Tool.class);
		assertEquals("showFormInBrowser", annotation.name(), "Tool name should be 'showFormInBrowser'");
	}

	@Test
	public void testCheckNGClientStatus_toolName() throws Exception
	{
		Method method = serverClass.getMethod("checkNGClientStatus");
		Tool annotation = method.getAnnotation(Tool.class);
		assertEquals("checkNGClientStatus", annotation.name(), "Tool name should be 'checkNGClientStatus'");
	}
}

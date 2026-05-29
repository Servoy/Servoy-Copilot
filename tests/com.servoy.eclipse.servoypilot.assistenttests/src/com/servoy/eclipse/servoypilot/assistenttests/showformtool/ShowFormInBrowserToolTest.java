/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.showformtool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.servoypilot.tools.workspace.IShowFormInBrowserTool;

import dev.langchain4j.agent.tool.Tool;

/**
 * Unit tests for {@link IShowFormInBrowserTool}.
 * <p>
 * Tests the tool interface structure and annotations without requiring
 * the full Servoy runtime. These are plain JUnit tests.
 */
public class ShowFormInBrowserToolTest
{
	private Class<IShowFormInBrowserTool> toolClass;

	@Before
	public void setUp()
	{
		toolClass = IShowFormInBrowserTool.class;
	}

	@Test
	public void testInterface_hasShowFormInBrowserMethod() throws Exception
	{
		Method method = toolClass.getMethod("showFormInBrowser", String.class);
		assertNotNull("showFormInBrowser method should exist", method);
	}

	@Test
	public void testInterface_hasCheckNGClientStatusMethod() throws Exception
	{
		Method method = toolClass.getMethod("checkNGClientStatus");
		assertNotNull("checkNGClientStatus method should exist", method);
	}

	@Test
	public void testShowFormInBrowser_hasToolAnnotation() throws Exception
	{
		Method method = toolClass.getMethod("showFormInBrowser", String.class);
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull("showFormInBrowser should have @Tool annotation", annotation);
		assertTrue("@Tool description should mention browser",
			annotation.value()[0].toLowerCase().contains("browser"));
	}

	@Test
	public void testCheckNGClientStatus_hasToolAnnotation() throws Exception
	{
		Method method = toolClass.getMethod("checkNGClientStatus");
		Tool annotation = method.getAnnotation(Tool.class);
		assertNotNull("checkNGClientStatus should have @Tool annotation", annotation);
		assertTrue("@Tool description should mention NG client",
			annotation.value()[0].toLowerCase().contains("ng client"));
	}

	@Test
	public void testShowFormInBrowser_returnsString() throws Exception
	{
		Method method = toolClass.getMethod("showFormInBrowser", String.class);
		assertEquals("Return type should be String", String.class, method.getReturnType());
	}

	@Test
	public void testCheckNGClientStatus_returnsString() throws Exception
	{
		Method method = toolClass.getMethod("checkNGClientStatus");
		assertEquals("Return type should be String", String.class, method.getReturnType());
	}

	@Test
	public void testShowFormInBrowser_hasOneParameter() throws Exception
	{
		Method method = toolClass.getMethod("showFormInBrowser", String.class);
		assertEquals("showFormInBrowser should have 1 parameter", 1, method.getParameterCount());
		assertEquals("Parameter should be String", String.class, method.getParameterTypes()[0]);
	}

	@Test
	public void testCheckNGClientStatus_hasNoParameters() throws Exception
	{
		Method method = toolClass.getMethod("checkNGClientStatus");
		assertEquals("checkNGClientStatus should have 0 parameters", 0, method.getParameterCount());
	}

	@Test
	public void testInterface_isInterface()
	{
		assertTrue("IShowFormInBrowserTool should be an interface", toolClass.isInterface());
	}

	@Test
	public void testInterface_methodsAreDefault() throws Exception
	{
		Method showForm = toolClass.getMethod("showFormInBrowser", String.class);
		assertTrue("showFormInBrowser should be a default method", showForm.isDefault());

		Method checkStatus = toolClass.getMethod("checkNGClientStatus");
		assertTrue("checkNGClientStatus should be a default method", checkStatus.isDefault());
	}
}

package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

public class ServoyTestingServerTest
{
	@Test
	public void testServoyTestingServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyTestingServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyTestingServer must have @McpServer annotation", ann);
		assertEquals("servoy-test", ann.name());
	}

	@Test
	public void testServoyTestingServer_hasNineToolMethods()
	{
		long toolCount = Arrays.stream(ServoyTestingServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.count();
		assertEquals("ServoyTestingServer should have 9 @Tool methods", 9, toolCount);
	}

	@Test
	public void testServoyTestingServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyTestingServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyTestingServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyTestingServer_hasTestFormTool()
	{
		assertTrue("ServoyTestingServer must have a 'testForm' tool", hasToolNamed("testForm"));
	}

	@Test
	public void testServoyTestingServer_hasShowAndTestTool()
	{
		assertTrue("ServoyTestingServer must have a 'showAndTest' tool", hasToolNamed("showAndTest"));
	}

	@Test
	public void testServoyTestingServer_hasGenerateFormSpecTool()
	{
		assertTrue("ServoyTestingServer must have a 'generateFormSpec' tool", hasToolNamed("generateFormSpec"));
	}

	@Test
	public void testServoyTestingServer_hasRunJsUnitTestsTool()
	{
		assertTrue("ServoyTestingServer must have a 'runJsUnitTests' tool", hasToolNamed("runJsUnitTests"));
	}

	@Test
	public void testServoyTestingServer_hasShowFormInBrowserTool()
	{
		assertTrue("ServoyTestingServer must have a 'showFormInBrowser' tool", hasToolNamed("showFormInBrowser"));
	}

	@Test
	public void testServoyTestingServer_hasScreenshotFormTool()
	{
		assertTrue("ServoyTestingServer must have a 'screenshotForm' tool", hasToolNamed("screenshotForm"));
	}

	@Test
	public void testServoyTestingServer_hasCheckNGClientStatusTool()
	{
		assertTrue("ServoyTestingServer must have a 'checkNGClientStatus' tool", hasToolNamed("checkNGClientStatus"));
	}

	@Test
	public void testServoyTestingServer_allToolsHaveDescriptions()
	{
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.collect(Collectors.toList());

		for (Method m : toolMethods)
		{
			Tool tool = m.getAnnotation(Tool.class);
			assertFalse("Tool '" + tool.name() + "' must have a non-empty description",
				tool.description().isEmpty());
		}
	}

	@Test
	public void testServoyTestingServer_allToolsReturnString()
	{
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.collect(Collectors.toList());

		for (Method m : toolMethods)
		{
			assertEquals("Tool method '" + m.getName() + "' must return String",
				String.class, m.getReturnType());
		}
	}

	@Test
	public void testServoyTestingServer_testFormHasFormNameParam()
	{
		Method method = findToolMethod("testForm");
		assertNotNull(method);
		assertEquals("testForm must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("testForm parameter must have @ToolParam", param);
		assertFalse("testForm @ToolParam must have description", param.description().isEmpty());
	}

	@Test
	public void testServoyTestingServer_showAndTestHasFormNameParam()
	{
		Method method = findToolMethod("showAndTest");
		assertNotNull(method);
		assertEquals("showAndTest must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("showAndTest parameter must have @ToolParam", param);
	}

	@Test
	public void testServoyTestingServer_generateFormSpecHasFormNameParam()
	{
		Method method = findToolMethod("generateFormSpec");
		assertNotNull(method);
		assertEquals("generateFormSpec must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("generateFormSpec parameter must have @ToolParam", param);
	}

	@Test
	public void testServoyTestingServer_runJsUnitTestsHasTwoParams()
	{
		Method method = findToolMethod("runJsUnitTests");
		assertNotNull(method);
		assertEquals("runJsUnitTests must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_screenshotFormHasTwoParams()
	{
		Method method = findToolMethod("screenshotForm");
		assertNotNull(method);
		assertEquals("screenshotForm must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_checkNGClientStatusHasNoParams()
	{
		Method method = findToolMethod("checkNGClientStatus");
		assertNotNull(method);
		assertEquals("checkNGClientStatus must have 0 parameters", 0, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_canBeInstantiated()
	{
		ServoyTestingServer server = new ServoyTestingServer();
		assertNotNull("ServoyTestingServer must be instantiable via no-arg constructor", server);
	}

	@Test
	public void testServoyTestingServer_showFormInBrowserDescriptionMentionsSpec()
	{
		Method method = findToolMethod("showFormInBrowser");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("showFormInBrowser description should mention spec auto-generation",
			tool.description().contains("spec"));
	}

	private boolean hasToolNamed(String name)
	{
		return Arrays.stream(ServoyTestingServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName)
	{
		return Arrays.stream(ServoyTestingServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name()))
			.findFirst().orElse(null);
	}
}

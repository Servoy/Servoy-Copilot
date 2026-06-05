package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

public class ServoyWpmServerTest
{
	@Test
	public void testServoyWpmServer_hasCorrectAnnotation()
	{
		McpServer ann = ServoyWpmServer.class.getAnnotation(McpServer.class);
		assertNotNull("ServoyWpmServer must have @McpServer annotation", ann);
		assertEquals("servoy-wpm", ann.name());
	}

	@Test
	public void testServoyWpmServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyWpmServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyWpmServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyWpmServer_canBeInstantiated()
	{
		ServoyWpmServer server = new ServoyWpmServer();
		assertNotNull("ServoyWpmServer must be instantiable via no-arg constructor", server);
	}

	@Test
	public void testServoyWpmServer_hasSearchPackagesTool()
	{
		assertTrue("ServoyWpmServer must have a 'searchPackages' tool", hasToolNamed("searchPackages"));
	}

	@Test
	public void testServoyWpmServer_hasInstallPackageTool()
	{
		assertTrue("ServoyWpmServer must have an 'installPackage' tool", hasToolNamed("installPackage"));
	}

	@Test
	public void testServoyWpmServer_allToolsHaveDescriptions()
	{
		List<Method> toolMethods = getToolMethods();

		for (Method m : toolMethods)
		{
			Tool tool = m.getAnnotation(Tool.class);
			assertFalse("Tool '" + tool.name() + "' must have a non-empty description",
				tool.description().isEmpty());
		}
	}

	@Test
	public void testServoyWpmServer_allToolsReturnString()
	{
		List<Method> toolMethods = getToolMethods();

		for (Method m : toolMethods)
		{
			assertEquals("Tool method '" + m.getName() + "' must return String",
				String.class, m.getReturnType());
		}
	}

	@Test
	public void testServoyWpmServer_toolNamesAreUnique()
	{
		List<Method> toolMethods = getToolMethods();
		Set<String> names = new HashSet<>();

		for (Method m : toolMethods)
		{
			Tool tool = m.getAnnotation(Tool.class);
			assertTrue("Duplicate tool name: " + tool.name(), names.add(tool.name()));
		}
	}

	@Test
	public void testServoyWpmServer_allParametersHaveToolParam()
	{
		List<Method> toolMethods = getToolMethods();

		for (Method m : toolMethods)
		{
			for (Parameter p : m.getParameters())
			{
				ToolParam tp = p.getAnnotation(ToolParam.class);
				assertNotNull("Parameter '" + p.getName() + "' in tool '" + m.getName() +
					"' must have @ToolParam", tp);
				assertFalse("@ToolParam description must not be empty for parameter '" +
					p.getName() + "' in tool '" + m.getName() + "'",
					tp.description().isEmpty());
			}
		}
	}

	@Test
	public void testServoyWpmServer_searchPackagesHasTwoParams()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		assertEquals("searchPackages must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyWpmServer_searchPackagesQueryIsRequired()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue("searchPackages 'query' param must be required", param.required());
	}

	@Test
	public void testServoyWpmServer_searchPackagesTypeIsOptional()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse("searchPackages 'packageType' param must be optional", param.required());
	}

	@Test
	public void testServoyWpmServer_installPackageHasThreeParams()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		assertEquals("installPackage must have 3 parameters", 3, method.getParameterCount());
	}

	@Test
	public void testServoyWpmServer_installPackageNameIsRequired()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue("installPackage 'packageName' param must be required", param.required());
	}

	@Test
	public void testServoyWpmServer_installPackageVersionIsOptional()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse("installPackage 'version' param must be optional", param.required());
	}

	@Test
	public void testServoyWpmServer_installPackageSolutionIsOptional()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[2].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse("installPackage 'solutionName' param must be optional", param.required());
	}

	private List<Method> getToolMethods()
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.collect(Collectors.toList());
	}

	private boolean hasToolNamed(String name)
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName)
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name()))
			.findFirst().orElse(null);
	}
}

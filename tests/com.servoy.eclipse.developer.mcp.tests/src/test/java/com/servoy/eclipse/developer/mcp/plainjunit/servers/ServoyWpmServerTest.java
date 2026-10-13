package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServer;

public class ServoyWpmServerTest
{
	@Test
	public void testServoyWpmServer_hasCorrectAnnotation()
	{
		McpServer ann = ServoyWpmServer.class.getAnnotation(McpServer.class);
		assertNotNull(ann, "ServoyWpmServer must have @McpServer annotation");
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
		assertTrue(found, "ServoyWpmServer must be registered in McpServerBuiltins");
	}

	@Test
	public void testServoyWpmServer_canBeInstantiated()
	{
		ServoyWpmServer server = new ServoyWpmServer();
		assertNotNull(server, "ServoyWpmServer must be instantiable via no-arg constructor");
	}

	@Test
	public void testServoyWpmServer_hasSearchPackagesTool()
	{
		assertTrue(hasToolNamed("searchPackages"), "ServoyWpmServer must have a 'searchPackages' tool");
	}

	@Test
	public void testServoyWpmServer_hasInstallPackageTool()
	{
		assertTrue(hasToolNamed("installPackage"), "ServoyWpmServer must have an 'installPackage' tool");
	}

	@Test
	public void testServoyWpmServer_hasUninstallPackageTool()
	{
		assertTrue(hasToolNamed("uninstallPackage"), "ServoyWpmServer must have an 'uninstallPackage' tool");
	}

	@Test
	public void testServoyWpmServer_uninstallPackageNameIsRequired()
	{
		Method method = findToolMethod("uninstallPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue(param.required(), "uninstallPackage 'packageName' param must be required");
	}

	@Test
	public void testServoyWpmServer_uninstallPackageHasThreeParams()
	{
		Method method = findToolMethod("uninstallPackage");
		assertNotNull(method);
		assertEquals(3, method.getParameterCount(), "uninstallPackage must have 3 parameters");
	}

	@Test
	public void testServoyWpmServer_uninstallPackageSolutionIsOptional()
	{
		Method method = findToolMethod("uninstallPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "uninstallPackage 'solutionName' param must be optional");
	}

	@Test
	public void testServoyWpmServer_uninstallPackageForceIsOptional()
	{
		Method method = findToolMethod("uninstallPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[2].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "uninstallPackage 'force' param must be optional");
	}

	@Test
	public void testServoyWpmServer_hasGetAvailableWebPackagesTool()
	{
		assertTrue(hasToolNamed("getAvailableWebPackages"), "ServoyWpmServer must have a 'getAvailableWebPackages' tool");
	}

	@Test
	public void testServoyWpmServer_hasGetInstalledPackagesTool()
	{
		assertTrue(hasToolNamed("getInstalledPackages"), "ServoyWpmServer must have a 'getInstalledPackages' tool");
	}

	@Test
	public void testServoyWpmServer_hasGetComponentsTool()
	{
		assertTrue(hasToolNamed("getComponents"), "ServoyWpmServer must have a 'getComponents' tool");
	}

	@Test
	public void testServoyWpmServer_hasGetComponentSpecTool()
	{
		assertTrue(hasToolNamed("getComponentSpec"), "ServoyWpmServer must have a 'getComponentSpec' tool");
	}

	@Test
	public void testServoyWpmServer_hasGetComponentDocsTool()
	{
		assertTrue(hasToolNamed("getComponentDocs"), "ServoyWpmServer must have a 'getComponentDocs' tool");
	}

	@Test
	public void testServoyWpmServer_getComponentSpecObjectNameIsRequired()
	{
		Method method = findToolMethod("getComponentSpec");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue(param.required(), "getComponentSpec 'objectName' param must be required");
	}

	@Test
	public void testServoyWpmServer_getComponentsServicesIsOptional()
	{
		Method method = findToolMethod("getComponents");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "getComponents 'services' param must be optional");
	}

	@Test
	public void testServoyWpmServer_allToolsHaveDescriptions()
	{
		List<Method> toolMethods = getToolMethods();

		for (Method m : toolMethods)
		{
			Tool tool = m.getAnnotation(Tool.class);
			assertFalse(tool.description().isEmpty(),
				"Tool '" + tool.name() + "' must have a non-empty description");
		}
	}

	@Test
	public void testServoyWpmServer_allToolsReturnString()
	{
		List<Method> toolMethods = getToolMethods();

		for (Method m : toolMethods)
		{
			assertEquals(String.class, m.getReturnType(),
				"Tool method '" + m.getName() + "' must return String");
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
			assertTrue(names.add(tool.name()), "Duplicate tool name: " + tool.name());
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
				assertNotNull(tp, "Parameter '" + p.getName() + "' in tool '" + m.getName() +
					"' must have @ToolParam");
				assertFalse(tp.description().isEmpty(),
					"@ToolParam description must not be empty for parameter '" +
					p.getName() + "' in tool '" + m.getName() + "'");
			}
		}
	}

	@Test
	public void testServoyWpmServer_searchPackagesHasTwoParams()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(), "searchPackages must have 2 parameters");
	}

	@Test
	public void testServoyWpmServer_searchPackagesQueryIsRequired()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue(param.required(), "searchPackages 'query' param must be required");
	}

	@Test
	public void testServoyWpmServer_searchPackagesTypeIsOptional()
	{
		Method method = findToolMethod("searchPackages");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "searchPackages 'packageType' param must be optional");
	}

	@Test
	public void testServoyWpmServer_installPackageHasThreeParams()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		assertEquals(3, method.getParameterCount(), "installPackage must have 3 parameters");
	}

	@Test
	public void testServoyWpmServer_installPackageNameIsRequired()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertTrue(param.required(), "installPackage 'packageName' param must be required");
	}

	@Test
	public void testServoyWpmServer_installPackageVersionIsOptional()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "installPackage 'version' param must be optional");
	}

	@Test
	public void testServoyWpmServer_installPackageSolutionIsOptional()
	{
		Method method = findToolMethod("installPackage");
		assertNotNull(method);
		ToolParam param = method.getParameters()[2].getAnnotation(ToolParam.class);
		assertNotNull(param);
		assertFalse(param.required(), "installPackage 'solutionName' param must be optional");
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

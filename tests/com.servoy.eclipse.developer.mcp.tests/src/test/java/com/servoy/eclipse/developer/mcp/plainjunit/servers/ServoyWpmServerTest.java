/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServer;
import com.servoy.eclipse.developer.mcp.services.ComponentSpecService;
import com.servoy.eclipse.developer.mcp.services.WpmService;
import com.servoy.eclipse.developer.mcp.services.WpmService.ScoredPackage;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyWpmServer}'s <em>own</em>
 * logic - without a live Eclipse workspace, SPM repositories or loaded specs.
 * <p>
 * {@code ServoyWpmServer} is a thin tool wrapper over two collaborators
 * ({@link WpmService} for the package tools, {@link ComponentSpecService} for the
 * component/spec/doc tools). It owns no model lookup, so there is no
 * {@code getServoyModel()} seam to add; the server exposes an {@code @Inject}
 * constructor that takes both collaborators, which these tests use to supply
 * Mockito mocks (no reflection or production change needed). The collaborators are
 * stubbed only at the boundary to drive the server's own branches:
 * </p>
 * <ul>
 * <li>the {@code Boolean} {@literal ->} primitive flag mapping ({@code services},
 * {@code onlyUpdates}, {@code force}) including the {@code null}-means-false path;</li>
 * <li>the "empty result" messages (with and without the {@code packageType} /
 * {@code packageName} suffix);</li>
 * <li>the {@link StringBuilder} formatting of a non-empty result list
 * (name / displayName / type / description / version / installed), driven with
 * genuine {@link JSONObject}s;</li>
 * <li>the "not found" / {@code toString(2)} pass-through of the component/spec/doc
 * tools;</li>
 * <li>the {@code RuntimeException} wrapping ({@code "Failed to ...: <message>"})
 * each tool applies when its collaborator throws.</li>
 * </ul>
 * <p>
 * The collaborators' real behaviour (the SPM network calls, the installed-package
 * walk, the parsed-{@code .spec} introspection) needs the workbench / an active
 * solution and stays in the {@code integrationpde} suite.
 * </p>
 *
 * @since 2026.11
 */
public class ServoyWpmServerTest
{
	private static ServoyWpmServer serverWith(WpmService wpm, ComponentSpecService specs)
	{
		return new ServoyWpmServer(wpm, specs);
	}

	// =======================================================================
	// annotation / registration / @Tool contract (no collaborators needed)
	// =======================================================================

	@Nested
	class AnnotationAndContract
	{
		@Test
		public void hasCorrectAnnotation()
		{
			McpServer ann = ServoyWpmServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyWpmServer must have @McpServer annotation");
			assertEquals("servoy-wpm", ann.name());
		}

		@Test
		public void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
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
		public void canBeInstantiated()
		{
			ServoyWpmServer server = new ServoyWpmServer();
			assertNotNull(server, "ServoyWpmServer must be instantiable via no-arg constructor");
		}

		@Test
		public void hasSearchPackagesTool()
		{
			assertTrue(hasToolNamed("searchPackages"), "ServoyWpmServer must have a 'searchPackages' tool");
		}

		@Test
		public void hasInstallPackageTool()
		{
			assertTrue(hasToolNamed("installPackage"), "ServoyWpmServer must have an 'installPackage' tool");
		}

		@Test
		public void hasUninstallPackageTool()
		{
			assertTrue(hasToolNamed("uninstallPackage"), "ServoyWpmServer must have an 'uninstallPackage' tool");
		}

		@Test
		public void uninstallPackageNameIsRequired()
		{
			Method method = findToolMethod("uninstallPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertTrue(param.required(), "uninstallPackage 'packageName' param must be required");
		}

		@Test
		public void uninstallPackageHasThreeParams()
		{
			Method method = findToolMethod("uninstallPackage");
			assertNotNull(method);
			assertEquals(3, method.getParameterCount(), "uninstallPackage must have 3 parameters");
		}

		@Test
		public void uninstallPackageSolutionIsOptional()
		{
			Method method = findToolMethod("uninstallPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "uninstallPackage 'solutionName' param must be optional");
		}

		@Test
		public void uninstallPackageForceIsOptional()
		{
			Method method = findToolMethod("uninstallPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[2].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "uninstallPackage 'force' param must be optional");
		}

		@Test
		public void hasGetAvailableWebPackagesTool()
		{
			assertTrue(hasToolNamed("getAvailableWebPackages"), "ServoyWpmServer must have a 'getAvailableWebPackages' tool");
		}

		@Test
		public void hasGetInstalledPackagesTool()
		{
			assertTrue(hasToolNamed("getInstalledPackages"), "ServoyWpmServer must have a 'getInstalledPackages' tool");
		}

		@Test
		public void hasGetComponentsTool()
		{
			assertTrue(hasToolNamed("getComponents"), "ServoyWpmServer must have a 'getComponents' tool");
		}

		@Test
		public void hasGetComponentSpecTool()
		{
			assertTrue(hasToolNamed("getComponentSpec"), "ServoyWpmServer must have a 'getComponentSpec' tool");
		}

		@Test
		public void hasGetComponentDocsTool()
		{
			assertTrue(hasToolNamed("getComponentDocs"), "ServoyWpmServer must have a 'getComponentDocs' tool");
		}

		@Test
		public void getComponentSpecObjectNameIsRequired()
		{
			Method method = findToolMethod("getComponentSpec");
			assertNotNull(method);
			ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertTrue(param.required(), "getComponentSpec 'objectName' param must be required");
		}

		@Test
		public void getComponentsServicesIsOptional()
		{
			Method method = findToolMethod("getComponents");
			assertNotNull(method);
			ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "getComponents 'services' param must be optional");
		}

		@Test
		public void allToolsHaveDescriptions()
		{
			for (Method m : getToolMethods())
			{
				Tool tool = m.getAnnotation(Tool.class);
				assertFalse(tool.description().isEmpty(), "Tool '" + tool.name() + "' must have a non-empty description");
			}
		}

		@Test
		public void allToolsReturnString()
		{
			for (Method m : getToolMethods())
			{
				assertEquals(String.class, m.getReturnType(), "Tool method '" + m.getName() + "' must return String");
			}
		}

		@Test
		public void toolNamesAreUnique()
		{
			Set<String> names = new HashSet<>();
			for (Method m : getToolMethods())
			{
				Tool tool = m.getAnnotation(Tool.class);
				assertTrue(names.add(tool.name()), "Duplicate tool name: " + tool.name());
			}
		}

		@Test
		public void allParametersHaveToolParam()
		{
			for (Method m : getToolMethods())
			{
				for (Parameter p : m.getParameters())
				{
					ToolParam tp = p.getAnnotation(ToolParam.class);
					assertNotNull(tp, "Parameter '" + p.getName() + "' in tool '" + m.getName() + "' must have @ToolParam");
					assertFalse(tp.description().isEmpty(),
						"@ToolParam description must not be empty for parameter '" + p.getName() + "' in tool '" + m.getName() + "'");
				}
			}
		}

		@Test
		public void searchPackagesHasTwoParams()
		{
			Method method = findToolMethod("searchPackages");
			assertNotNull(method);
			assertEquals(2, method.getParameterCount(), "searchPackages must have 2 parameters");
		}

		@Test
		public void searchPackagesQueryIsRequired()
		{
			Method method = findToolMethod("searchPackages");
			assertNotNull(method);
			ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertTrue(param.required(), "searchPackages 'query' param must be required");
		}

		@Test
		public void searchPackagesTypeIsOptional()
		{
			Method method = findToolMethod("searchPackages");
			assertNotNull(method);
			ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "searchPackages 'packageType' param must be optional");
		}

		@Test
		public void installPackageHasThreeParams()
		{
			Method method = findToolMethod("installPackage");
			assertNotNull(method);
			assertEquals(3, method.getParameterCount(), "installPackage must have 3 parameters");
		}

		@Test
		public void installPackageNameIsRequired()
		{
			Method method = findToolMethod("installPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertTrue(param.required(), "installPackage 'packageName' param must be required");
		}

		@Test
		public void installPackageVersionIsOptional()
		{
			Method method = findToolMethod("installPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[1].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "installPackage 'version' param must be optional");
		}

		@Test
		public void installPackageSolutionIsOptional()
		{
			Method method = findToolMethod("installPackage");
			assertNotNull(method);
			ToolParam param = method.getParameters()[2].getAnnotation(ToolParam.class);
			assertNotNull(param);
			assertFalse(param.required(), "installPackage 'solutionName' param must be optional");
		}
	}

	// =======================================================================
	// searchPackages - empty message, result formatting, exception wrap
	// =======================================================================

	@Nested
	class SearchPackages
	{
		@Test
		@DisplayName("searchPackages reports no matches without a type suffix when packageType is absent")
		void emptyResultNoType() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.searchPackages("calendar", null)).thenReturn(Collections.<ScoredPackage> emptyList());
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("No packages found matching 'calendar'.", server.searchPackages("calendar", null));
		}

		@Test
		@DisplayName("searchPackages names the type in the no-matches message when packageType is given")
		void emptyResultWithType() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.searchPackages("calendar", "Web-Component")).thenReturn(Collections.<ScoredPackage> emptyList());
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.searchPackages("calendar", "Web-Component");
			assertTrue(result.contains("No packages found matching 'calendar'"), result);
			assertTrue(result.contains("with type 'Web-Component'"), result);
		}

		@Test
		@DisplayName("searchPackages renders name/displayName/type/description/version/installed for each hit")
		void formatsResults() throws Exception
		{
			JSONObject pkg = new JSONObject();
			pkg.put("name", "aggrid");
			pkg.put("displayName", "AG Grid");
			pkg.put("packageType", "Web-Component");
			pkg.put("description", "A data grid");
			pkg.put("releases", new JSONArray().put(new JSONObject().put("version", "2.3.0")));
			pkg.put("installed", "2.2.0");

			WpmService wpm = mock(WpmService.class);
			when(wpm.searchPackages("grid", null)).thenReturn(List.of(new ScoredPackage(pkg, 10)));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.searchPackages("grid", null);
			assertTrue(result.contains("Found 1 package(s) matching 'grid':"), result);
			assertTrue(result.contains("**aggrid**"), result);
			assertTrue(result.contains("(AG Grid)"), result);
			assertTrue(result.contains("Type: Web-Component"), result);
			assertTrue(result.contains("Description: A data grid"), result);
			assertTrue(result.contains("Latest version: 2.3.0"), result);
			assertTrue(result.contains("Installed: 2.2.0"), result);
		}

		@Test
		@DisplayName("searchPackages omits the (displayName) when it equals the name")
		void displayNameSuppressedWhenSameAsName() throws Exception
		{
			JSONObject pkg = new JSONObject();
			pkg.put("name", "aggrid");
			pkg.put("displayName", "aggrid");
			pkg.put("packageType", "Web-Component");

			WpmService wpm = mock(WpmService.class);
			when(wpm.searchPackages("grid", null)).thenReturn(List.of(new ScoredPackage(pkg, 10)));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.searchPackages("grid", null);
			assertTrue(result.contains("**aggrid**"), result);
			assertFalse(result.contains("(aggrid)"), "displayName equal to name must not be repeated: " + result);
		}

		@Test
		@DisplayName("searchPackages wraps a collaborator failure in a RuntimeException naming the operation")
		void wrapsException() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.searchPackages("x", null)).thenThrow(new RuntimeException("network down"));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.searchPackages("x", null));
			assertTrue(ex.getMessage().contains("Failed to search SPM packages"), ex.getMessage());
			assertTrue(ex.getMessage().contains("network down"), ex.getMessage());
		}
	}

	// =======================================================================
	// installPackage / uninstallPackage - pass-through, flag mapping, wrap
	// =======================================================================

	@Nested
	class InstallUninstall
	{
		@Test
		@DisplayName("installPackage returns the service result unchanged")
		void installPassesThrough() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.installPackage("aggrid", "2.3.0", "mysol")).thenReturn("Installed aggrid 2.3.0");
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("Installed aggrid 2.3.0", server.installPackage("aggrid", "2.3.0", "mysol"));
			verify(wpm).installPackage("aggrid", "2.3.0", "mysol");
		}

		@Test
		@DisplayName("installPackage wraps a collaborator failure naming the package")
		void installWrapsException() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.installPackage("aggrid", null, null)).thenThrow(new RuntimeException("conflict"));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.installPackage("aggrid", null, null));
			assertTrue(ex.getMessage().contains("Failed to install package 'aggrid'"), ex.getMessage());
			assertTrue(ex.getMessage().contains("conflict"), ex.getMessage());
		}

		@Test
		@DisplayName("uninstallPackage maps a null force to false")
		void uninstallNullForceIsFalse() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.uninstallPackage("aggrid", null, false)).thenReturn("Removed aggrid");
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("Removed aggrid", server.uninstallPackage("aggrid", null, null));
			verify(wpm).uninstallPackage("aggrid", null, false);
		}

		@Test
		@DisplayName("uninstallPackage forwards force=true to the service")
		void uninstallForceTrue() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.uninstallPackage("aggrid", "mysol", true)).thenReturn("Force-removed aggrid");
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("Force-removed aggrid", server.uninstallPackage("aggrid", "mysol", Boolean.TRUE));
			verify(wpm).uninstallPackage("aggrid", "mysol", true);
		}

		@Test
		@DisplayName("uninstallPackage wraps a collaborator failure naming the package")
		void uninstallWrapsException() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.uninstallPackage("aggrid", null, false)).thenThrow(new RuntimeException("has dependents"));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.uninstallPackage("aggrid", null, null));
			assertTrue(ex.getMessage().contains("Failed to uninstall package 'aggrid'"), ex.getMessage());
			assertTrue(ex.getMessage().contains("has dependents"), ex.getMessage());
		}
	}

	// =======================================================================
	// getAvailableWebPackages / getInstalledPackages - lists & appendPackageSummary
	// =======================================================================

	@Nested
	class ListPackages
	{
		@Test
		@DisplayName("getAvailableWebPackages maps a null onlyUpdates to false and reports an empty result")
		void availableEmptyNullOnlyUpdates() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.getAvailableWebPackages(null, false)).thenReturn(Collections.<JSONObject> emptyList());
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("No available packages found.", server.getAvailableWebPackages(null, null));
			verify(wpm).getAvailableWebPackages(null, false);
		}

		@Test
		@DisplayName("getAvailableWebPackages names the type in the empty message and forwards onlyUpdates=true")
		void availableEmptyWithTypeAndOnlyUpdates() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.getAvailableWebPackages("Web-Service", true)).thenReturn(Collections.<JSONObject> emptyList());
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.getAvailableWebPackages("Web-Service", Boolean.TRUE);
			assertTrue(result.contains("No available packages found"), result);
			assertTrue(result.contains("with type 'Web-Service'"), result);
			verify(wpm).getAvailableWebPackages("Web-Service", true);
		}

		@Test
		@DisplayName("getAvailableWebPackages renders each package with its latest version")
		void availableFormatsWithLatest() throws Exception
		{
			JSONObject pkg = new JSONObject();
			pkg.put("name", "aggrid");
			pkg.put("packageType", "Web-Component");
			pkg.put("description", "grid");
			pkg.put("releases", new JSONArray().put(new JSONObject().put("version", "3.0.0")));

			WpmService wpm = mock(WpmService.class);
			when(wpm.getAvailableWebPackages(null, false)).thenReturn(List.of(pkg));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.getAvailableWebPackages(null, null);
			assertTrue(result.contains("Found 1 available package(s):"), result);
			assertTrue(result.contains("**aggrid**"), result);
			assertTrue(result.contains("Latest version: 3.0.0"), result);
		}

		@Test
		@DisplayName("getAvailableWebPackages wraps a collaborator failure")
		void availableWrapsException() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.getAvailableWebPackages(null, false)).thenThrow(new RuntimeException("boom"));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getAvailableWebPackages(null, null));
			assertTrue(ex.getMessage().contains("Failed to list available web packages"), ex.getMessage());
		}

		@Test
		@DisplayName("getInstalledPackages reports an empty result with no type suffix")
		void installedEmpty() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.getInstalledPackages(null)).thenReturn(Collections.<JSONObject> emptyList());
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			assertEquals("No installed packages found.", server.getInstalledPackages(null));
		}

		@Test
		@DisplayName("getInstalledPackages renders the installed version and 'Installed in' module")
		void installedFormatsWithModule() throws Exception
		{
			JSONObject pkg = new JSONObject();
			pkg.put("name", "aggrid");
			pkg.put("packageType", "Web-Component");
			pkg.put("installed", "2.2.0");
			pkg.put("activeSolution", "myModule");

			WpmService wpm = mock(WpmService.class);
			when(wpm.getInstalledPackages(null)).thenReturn(List.of(pkg));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			String result = server.getInstalledPackages(null);
			assertTrue(result.contains("Found 1 installed package(s):"), result);
			assertTrue(result.contains("**aggrid**"), result);
			assertTrue(result.contains("Installed version: 2.2.0"), result);
			assertTrue(result.contains("Installed in: myModule"), result);
		}

		@Test
		@DisplayName("getInstalledPackages wraps a collaborator failure")
		void installedWrapsException() throws Exception
		{
			WpmService wpm = mock(WpmService.class);
			when(wpm.getInstalledPackages(null)).thenThrow(new RuntimeException("boom"));
			ServoyWpmServer server = serverWith(wpm, mock(ComponentSpecService.class));

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getInstalledPackages(null));
			assertTrue(ex.getMessage().contains("Failed to list installed packages"), ex.getMessage());
		}
	}

	// =======================================================================
	// getComponents / getComponentSpec / getComponentDocs - spec collaborator
	// =======================================================================

	@Nested
	class ComponentTools
	{
		@Test
		@DisplayName("getComponents maps a null services flag to false and reports no components")
		void componentsEmptyNullServices()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.listObjects(isNull(), eq(false))).thenReturn(new JSONArray());
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			String result = server.getComponents(null, null);
			assertTrue(result.contains("No components found"), result);
			assertTrue(result.contains("Are the specs loaded?"), result);
			verify(specs).listObjects(null, false);
		}

		@Test
		@DisplayName("getComponents with services=true reports 'No services found' and names the package")
		void componentsEmptyServicesWithPackage()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.listObjects(eq("bootstrapcomponents"), eq(true))).thenReturn(new JSONArray());
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			String result = server.getComponents("bootstrapcomponents", Boolean.TRUE);
			assertTrue(result.contains("No services found"), result);
			assertTrue(result.contains("in package 'bootstrapcomponents'"), result);
		}

		@Test
		@DisplayName("getComponents returns the JSON array pretty-printed for a non-empty result")
		void componentsReturnsPrettyJson()
		{
			JSONArray objects = new JSONArray().put(new JSONObject().put("name", "button"));
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.listObjects(null, false)).thenReturn(objects);
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			assertEquals(objects.toString(2), server.getComponents(null, Boolean.FALSE));
		}

		@Test
		@DisplayName("getComponents wraps a collaborator failure")
		void componentsWrapsException()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.listObjects(null, false)).thenThrow(new RuntimeException("no state"));
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getComponents(null, null));
			assertTrue(ex.getMessage().contains("Failed to list components"), ex.getMessage());
		}

		@Test
		@DisplayName("getComponentSpec returns a 'not found' message when the spec is null")
		void specNotFound()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectSpec("ghost", false)).thenReturn(null);
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			assertEquals("No component spec found for 'ghost'.", server.getComponentSpec("ghost", null));
		}

		@Test
		@DisplayName("getComponentSpec returns the spec JSON pretty-printed when found (service variant)")
		void specFoundService()
		{
			JSONObject spec = new JSONObject().put("name", "svc");
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectSpec("svc", true)).thenReturn(spec);
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			assertEquals(spec.toString(2), server.getComponentSpec("svc", Boolean.TRUE));
		}

		@Test
		@DisplayName("getComponentSpec wraps a collaborator failure naming the object")
		void specWrapsException()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectSpec("x", false)).thenThrow(new RuntimeException("parse error"));
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getComponentSpec("x", null));
			assertTrue(ex.getMessage().contains("Failed to get component spec for 'x'"), ex.getMessage());
		}

		@Test
		@DisplayName("getComponentDocs returns a 'not found' message when the docs are null")
		void docsNotFound()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectDocs("ghost", false)).thenReturn(null);
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			assertEquals("No component found for 'ghost'.", server.getComponentDocs("ghost", null));
		}

		@Test
		@DisplayName("getComponentDocs returns the docs JSON pretty-printed when found")
		void docsFound()
		{
			JSONObject docs = new JSONObject().put("doc", "hello");
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectDocs("button", false)).thenReturn(docs);
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			assertEquals(docs.toString(2), server.getComponentDocs("button", Boolean.FALSE));
		}

		@Test
		@DisplayName("getComponentDocs wraps a collaborator failure naming the object")
		void docsWrapsException()
		{
			ComponentSpecService specs = mock(ComponentSpecService.class);
			when(specs.getObjectDocs("x", false)).thenThrow(new RuntimeException("io error"));
			ServoyWpmServer server = serverWith(mock(WpmService.class), specs);

			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getComponentDocs("x", null));
			assertTrue(ex.getMessage().contains("Failed to get component docs for 'x'"), ex.getMessage());
		}
	}

	// =======================================================================
	// helpers
	// =======================================================================

	private static List<Method> getToolMethods()
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.collect(Collectors.toList());
	}

	private static boolean hasToolNamed(String name)
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private static Method findToolMethod(String toolName)
	{
		return Arrays.stream(ServoyWpmServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name()))
			.findFirst().orElse(null);
	}
}

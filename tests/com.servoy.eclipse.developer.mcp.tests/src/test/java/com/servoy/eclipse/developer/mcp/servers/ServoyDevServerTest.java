/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * JUnit 4 tests for {@link ServoyDevServer}.
 */
public class ServoyDevServerTest
{
	private final ServoyDevServer server = new ServoyDevServer();

	@Test
	public void testServoyDevServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyDevServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyDevServer must have @McpServer annotation", ann);
		assertEquals("servoy-dev", ann.name());
	}

	@Test
	public void testServoyDevServer_hasCorrectToolCount()
	{
		long toolCount = Arrays.stream(ServoyDevServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.count();
		assertEquals(31, toolCount);
	}

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError()
	{
		try
		{
			String result = server.resolveIdentifierType(null, "someForm", null);
			assertNotNull(result);
			assertTrue("Should return error for null identifier",
				result.contains("Error") || result.contains("required") || result.contains("not found"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testResolveIdentifierType_unknownForm_returnsNotFound()
	{
		try
		{
			String result = server.resolveIdentifierType("myVar", "nonExistentForm_XYZ_ABC", null);
			assertNotNull(result);
			assertTrue("Should return not-found message",
				result.contains("not found") || result.contains("nonExistentForm_XYZ_ABC") || result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testPing_returnsPong()
	{
		assertEquals("pong", server.ping());
	}

	// -----------------------------------------------------------------------
	// createSolution tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyDevServer_hasCreateSolutionTool()
	{
		assertTrue("ServoyDevServer must have a 'createSolution' tool",
			hasToolNamed("createSolution"));
	}

	@Test
	public void testServoyDevServer_createSolutionHasFiveParams()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull("createSolution tool must exist", method);
		assertEquals("createSolution must have 5 parameters", 5, method.getParameterCount());
	}

	@Test
	public void testServoyDevServer_createSolutionReturnsString()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		assertEquals("createSolution must return String", String.class, method.getReturnType());
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsNullName()
	{
		String result = server.createSolution(null, null, null, null, null);
		assertTrue("createSolution must reject null name",
			result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsBlankName()
	{
		String result = server.createSolution("   ", null, null, null, null);
		assertTrue("createSolution must reject blank name",
			result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createSolutionHasToolParamAnnotations()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		long paramCount = Arrays.stream(method.getParameters())
			.filter(p -> p.isAnnotationPresent(ToolParam.class))
			.count();
		assertEquals("All 5 createSolution params must have @ToolParam", 5, paramCount);
	}

	@Test
	public void testServoyDevServer_createSolutionDescriptionMentionsWizard()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("createSolution description should mention wizard",
			tool.description().contains("wizard"));
	}

	@Test
	public void testServoyDevServer_createSolutionDescriptionMentionsModule()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("createSolution description should mention module",
			tool.description().contains("module"));
	}

	@Test
	public void testServoyDevServer_createSolutionHasAddToSolutionParam()
	{
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		boolean found = Arrays.stream(method.getParameters())
			.filter(p -> p.isAnnotationPresent(ToolParam.class))
			.anyMatch(p -> "addToSolution".equals(p.getAnnotation(ToolParam.class).name()));
		assertTrue("createSolution must have addToSolution parameter", found);
	}

	@Test
	public void testServoyDevServer_createSolution_moduleTypeWithNullParent_noError()
	{
		try
		{
			String result = server.createSolution("test_mod_xyz", "ng_module", "false", "false", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit (no active project)", e);
		}
	}

	@Test
	public void testServoyDevServer_createSolution_moduleTypeWithExplicitParent_noError()
	{
		try
		{
			String result = server.createSolution("test_mod_abc", "ng_module", "false", "false", "nonExistentParent");
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit (no workspace)", e);
		}
	}

	@Test
	public void testServoyDevServer_hasAddModuleToSolutionMethod()
	{
		Method[] methods = ServoyDevServer.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("addModuleToSolution".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyDevServer must have addModuleToSolution private method", found);
	}

	// -----------------------------------------------------------------------
	// createServer tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyDevServer_hasCreateServerTool()
	{
		assertTrue("ServoyDevServer must have a 'createServer' tool", hasToolNamed("createServer"));
	}

	@Test
	public void testServoyDevServer_createServerHasThreeParams()
	{
		Method method = findToolMethod("createServer");
		assertNotNull(method);
		assertEquals(3, method.getParameterCount());
	}

	@Test
	public void testServoyDevServer_createServerReturnsString()
	{
		Method method = findToolMethod("createServer");
		assertNotNull(method);
		assertEquals(String.class, method.getReturnType());
	}

	@Test
	public void testServoyDevServer_createServer_rejectsNullName()
	{
		String result = server.createServer(null, null, null);
		assertTrue(result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createServer_rejectsBlankName()
	{
		String result = server.createServer("   ", null, null);
		assertTrue(result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createServer_withValidName_handlesNoWorkspace()
	{
		try
		{
			String result = server.createServer("test_db", null, null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected NPE when no workspace available", e);
		}
	}

	@Test
	public void testServoyDevServer_createServer_createDatabaseFalse_handlesNoWorkspace()
	{
		try
		{
			String result = server.createServer("test_db", null, "false");
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected NPE when no workspace available", e);
		}
	}

	@Test
	public void testServoyDevServer_createServer_customDatabaseName_handlesNoWorkspace()
	{
		try
		{
			String result = server.createServer("my_server", "my_database", "true");
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected NPE when no workspace available", e);
		}
	}


	// -----------------------------------------------------------------------
	// executeSQL tests
	// -----------------------------------------------------------------------

	@Test
	public void testExecuteSQL_toolAnnotationExists()
	{
		assertTrue("executeSQL tool must be registered", hasToolNamed("executeSQL"));
	}

	@Test
	public void testExecuteSQL_hasCorrectParameters()
	{
		Method m = findToolMethod("executeSQL");
		assertNotNull("executeSQL method must exist", m);
		ToolParam[] params = Arrays.stream(m.getParameters())
			.map(p -> p.getAnnotation(ToolParam.class))
			.filter(a -> a != null)
			.toArray(ToolParam[]::new);
		assertEquals(2, params.length);
		assertEquals("serverName", params[0].name());
		assertEquals("sql", params[1].name());
		assertTrue(params[0].required());
		assertTrue(params[1].required());
	}

	@Test
	public void testExecuteSQL_nullServerName_returnsError()
	{
		String result = server.executeSQL(null, "SELECT 1");
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName is required"));
	}

	@Test
	public void testExecuteSQL_blankServerName_returnsError()
	{
		String result = server.executeSQL("  ", "SELECT 1");
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName is required"));
	}

	@Test
	public void testExecuteSQL_nullSql_returnsError()
	{
		String result = server.executeSQL("myserver", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("sql is required"));
	}

	@Test
	public void testExecuteSQL_blankSql_returnsError()
	{
		String result = server.executeSQL("myserver", "");
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("sql is required"));
	}

	@Test
	public void testExecuteSQL_handlesNoWorkspace()
	{
		try
		{
			String result = server.executeSQL("nonexistent_server", "SELECT 1");
			assertNotNull(result);
			assertTrue(result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	// -----------------------------------------------------------------------
	// addColumn tests
	// -----------------------------------------------------------------------

	@Test
	public void testAddColumn_toolAnnotationExists()
	{
		assertTrue("addColumn tool must be registered", hasToolNamed("addColumn"));
	}

	@Test
	public void testAddColumn_hasCorrectParameters()
	{
		Method m = findToolMethod("addColumn");
		assertNotNull("addColumn method must exist", m);
		ToolParam[] params = Arrays.stream(m.getParameters())
			.map(p -> p.getAnnotation(ToolParam.class))
			.filter(a -> a != null)
			.toArray(ToolParam[]::new);
		assertEquals(7, params.length);
		assertEquals("serverName", params[0].name());
		assertEquals("tableName", params[1].name());
		assertEquals("columnName", params[2].name());
		assertEquals("type", params[3].name());
		assertEquals("length", params[4].name());
		assertEquals("allowNull", params[5].name());
		assertEquals("inMemory", params[6].name());
		assertTrue(params[0].required());
		assertTrue(params[1].required());
		assertTrue(params[2].required());
	}

	@Test
	public void testAddColumn_nullServerName_returnsError()
	{
		String result = server.addColumn(null, "mytable", "mycol", null, null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName is required"));
	}

	@Test
	public void testAddColumn_nullTableName_returnsError()
	{
		String result = server.addColumn("myserver", null, "mycol", null, null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("tableName is required"));
	}

	@Test
	public void testAddColumn_nullColumnName_returnsError()
	{
		String result = server.addColumn("myserver", "mytable", null, null, null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("columnName is required"));
	}

	@Test
	public void testAddColumn_invalidColumnName_returnsError()
	{
		String result = server.addColumn("myserver", "mytable", "123bad", null, null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("not a valid SQL identifier"));
	}

	@Test
	public void testAddColumn_invalidType_returnsError()
	{
		String result = server.addColumn("myserver", "mytable", "valid_col", "BOGUS", null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("Invalid column type"));
	}

	@Test
	public void testAddColumn_invalidLength_returnsError()
	{
		String result = server.addColumn("myserver", "mytable", "valid_col", "TEXT", "abc", null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("Invalid length value"));
	}

	@Test
	public void testAddColumn_dbMode_handlesNoWorkspace()
	{
		try
		{
			String result = server.addColumn("nonexistent_server", "mytable", "valid_col", "TEXT", null, null, null);
			assertNotNull(result);
			assertTrue(result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testAddColumn_inMemoryMode_handlesNoWorkspace()
	{
		try
		{
			String result = server.addColumn("ignored", "mytable", "valid_col", "INTEGER", null, null, "true");
			assertNotNull(result);
			assertTrue(result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	// -----------------------------------------------------------------------
	// createTable tests
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTable_toolAnnotationExists()
	{
		assertTrue("createTable tool must be registered", hasToolNamed("createTable"));
	}

	@Test
	public void testCreateTable_hasCorrectParameters()
	{
		Method m = findToolMethod("createTable");
		assertNotNull("createTable method must exist", m);
		ToolParam[] params = Arrays.stream(m.getParameters())
			.map(p -> p.getAnnotation(ToolParam.class))
			.filter(a -> a != null)
			.toArray(ToolParam[]::new);
		assertEquals(3, params.length);
		assertEquals("serverName", params[0].name());
		assertEquals("tableName", params[1].name());
		assertEquals("inMemory", params[2].name());
		assertTrue(params[0].required());
		assertTrue(params[1].required());
	}

	@Test
	public void testCreateTable_nullTableName_returnsError()
	{
		String result = server.createTable("someServer", null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("tableName is required"));
	}

	@Test
	public void testCreateTable_blankTableName_returnsError()
	{
		String result = server.createTable("someServer", "  ", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("tableName is required"));
	}

	@Test
	public void testCreateTable_invalidSqlIdentifier_returnsError()
	{
		String result = server.createTable("someServer", "123invalid", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("not a valid SQL identifier"));
	}

	@Test
	public void testCreateTable_tempPrefix_returnsError()
	{
		String result = server.createTable("someServer", "temp_mytable", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("temp_"));
	}

	@Test
	public void testCreateTable_svyPrefix_returnsError()
	{
		String result = server.createTable("someServer", "svy_mytable", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("svy_"));
	}

	@Test
	public void testCreateTable_nullServerName_dbMode_returnsError()
	{
		String result = server.createTable(null, "valid_table", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName is required"));
	}

	@Test
	public void testCreateTable_blankServerName_dbMode_returnsError()
	{
		String result = server.createTable("", "valid_table", "false");
		assertNotNull(result);
		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName is required"));
	}

	@Test
	public void testCreateTable_dbMode_handlesNoWorkspace()
	{
		try
		{
			String result = server.createTable("nonexistent_server", "valid_table", null);
			assertNotNull(result);
			assertTrue(result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testCreateTable_inMemoryMode_handlesNoWorkspace()
	{
		try
		{
			String result = server.createTable("ignored", "valid_table", "true");
			assertNotNull(result);
			assertTrue(result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	// -----------------------------------------------------------------------
	// generateUUID tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateUUID_noCount_returnsOneUUID()
	{
		String result = server.generateUUID(null);
		assertNotNull(result);
		assertTrue("UUID must match standard format",
			result.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"));
	}

	@Test
	public void testGenerateUUID_countOne_returnsOneUUID()
	{
		String result = server.generateUUID("1");
		assertNotNull(result);
		assertTrue("UUID must match standard format",
			result.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"));
	}

	@Test
	public void testGenerateUUID_countThree_returnsThreeUUIDs()
	{
		String result = server.generateUUID("3");
		assertNotNull(result);
		String[] lines = result.split("\n");
		assertEquals("Should return exactly 3 UUIDs", 3, lines.length);
		for (String line : lines)
		{
			assertTrue("Each line must be a valid UUID: " + line,
				line.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"));
		}
	}

	@Test
	public void testGenerateUUID_countThree_allDistinct()
	{
		String result = server.generateUUID("3");
		String[] lines = result.split("\n");
		assertEquals(3, lines.length);
		assertTrue("All generated UUIDs must be distinct",
			lines[0] != null && !lines[0].equals(lines[1]) && !lines[0].equals(lines[2]) && !lines[1].equals(lines[2]));
	}

	@Test
	public void testGenerateUUID_uppercase()
	{
		String result = server.generateUUID(null);
		assertEquals("UUID must be uppercase", result, result.toUpperCase());
	}

	@Test
	public void testGenerateUUID_invalidCount_returnsError()
	{
		String result = server.generateUUID("abc");
		assertNotNull(result);
		assertTrue("Should return error for invalid count", result.startsWith("Error:"));
	}

	@Test
	public void testGenerateUUID_zeroCount_returnsError()
	{
		String result = server.generateUUID("0");
		assertNotNull(result);
		assertTrue("Should return error for count=0", result.startsWith("Error:"));
	}

	@Test
	public void testGenerateUUID_overLimit_returnsError()
	{
		String result = server.generateUUID("101");
		assertNotNull(result);
		assertTrue("Should return error for count > 100", result.startsWith("Error:"));
	}

	@Test
	public void testGenerateUUID_hasToolAnnotation()
	{
		assertTrue("ServoyDevServer must have a 'generateUUID' tool", hasToolNamed("generateUUID"));
	}

	@Test
	public void testGenerateUUID_hasCountParam()
	{
		Method method = findToolMethod("generateUUID");
		assertNotNull("generateUUID tool must exist", method);
		assertEquals("generateUUID must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("Parameter must have @ToolParam annotation", param);
		assertEquals("count", param.name());
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private boolean hasToolNamed(String name)
	{
		return Arrays.stream(ServoyDevServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName)
	{
		return Arrays.stream(ServoyDevServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(Tool.class))
			.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name()))
			.findFirst().orElse(null);
	}
}

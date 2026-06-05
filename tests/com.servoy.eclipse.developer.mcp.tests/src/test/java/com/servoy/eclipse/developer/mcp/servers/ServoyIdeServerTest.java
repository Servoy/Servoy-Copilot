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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * JUnit 4 tests for {@link ServoyIdeServer}.
 * <p>
 * Tests that do not require a live Eclipse workspace:
 * annotation/registration checks, parameter parsing, error paths for missing projects.
 * </p>
 * <p>
 * Happy-path tests (readProjectResource, fileSearch, etc. with real workspace)
 * are covered by the curl endpoint tests against Servoy Developer.
 * </p>
 */
public class ServoyIdeServerTest
{
	private final ServoyIdeServer server = new ServoyIdeServer(
		new com.servoy.eclipse.developer.mcp.services.ProjectService(),
		new com.servoy.eclipse.developer.mcp.services.WorkspaceService(),
		new com.servoy.eclipse.developer.mcp.services.MarkdownService(),
		new com.servoy.eclipse.developer.mcp.services.IdeStateService());

	// --- Annotation and registration checks ---

	@Test
	public void testServoyIdeServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyIdeServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyIdeServer must have @McpServer annotation", ann);
		assertEquals("servoy-ide", ann.name());
	}

	@Test
	public void testServoyIdeServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyIdeServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyIdeServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyIdeServer_canBeInstantiated()
	{
		ServoyIdeServer instance = new ServoyIdeServer(
			new com.servoy.eclipse.developer.mcp.services.ProjectService(),
			new com.servoy.eclipse.developer.mcp.services.WorkspaceService(),
			new com.servoy.eclipse.developer.mcp.services.MarkdownService(),
			new com.servoy.eclipse.developer.mcp.services.IdeStateService());
		assertNotNull(instance);
	}


	// --- Tool name uniqueness ---

	@Test
	public void testToolNames_areUnique()
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		java.util.Arrays.stream(ServoyIdeServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.forEach(m -> {
				String name = m.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.Tool.class).name();
				assertFalse("Duplicate tool name: " + name, names.contains(name));
				names.add(name);
			});
	}

	// --- Error paths for missing projects ---

	@Test
	public void testGetProjectLayout_missingProject()
	{
		String result = server.getProjectLayout("NonExistentProject_XYZ", null, null);
		assertNotNull(result);
		assertTrue("Should report project not found",
			result.contains("not found") || result.contains("NonExistentProject_XYZ"));
	}

	@Test
	public void testGetProjectProperties_missingProject()
	{
		String result = server.getProjectProperties("NonExistentProject_XYZ");
		assertNotNull(result);
		assertTrue("Should report project not found",
			result.contains("not found") || result.contains("Error"));
	}

	@Test
	public void testReadProjectResource_missingProject()
	{
		try
		{
			server.readProjectResource("NonExistentProject_XYZ", "some/file.txt", null, null, null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testFileSearch_nullText_throws()
	{
		try
		{
			server.fileSearch(null, null);
			fail("Should throw on null containingText");
		}
		catch (IllegalArgumentException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testFileSearch_blankText_throws()
	{
		try
		{
			server.fileSearch("   ", null);
			fail("Should throw on blank containingText");
		}
		catch (IllegalArgumentException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testFileSearchRegExp_nullPattern_throws()
	{
		try
		{
			server.fileSearchRegExp(null, null);
			fail("Should throw on null pattern");
		}
		catch (IllegalArgumentException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testSearchAndReplace_nullText_throws()
	{
		try
		{
			server.searchAndReplace(null, "replacement", null);
			fail("Should throw on null containingText");
		}
		catch (IllegalArgumentException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testSearchAndReplace_nullReplacement_throws()
	{
		try
		{
			server.searchAndReplace("text", null, null);
			fail("Should throw on null replacementText");
		}
		catch (IllegalArgumentException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	// --- getMarkdownOutline/getMarkdownSection error paths ---

	@Test
	public void testGetMarkdownOutline_missingProject()
	{
		try
		{
			server.getMarkdownOutline("NonExistentProject_XYZ", "README.md");
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found") || e.getMessage().contains("Project"));
		}
	}

	@Test
	public void testGetMarkdownSection_missingProject()
	{
		try
		{
			server.getMarkdownSection("NonExistentProject_XYZ", "README.md", "1", null);
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	// --- getCompilationErrors ---

	@Test
	public void testGetCompilationErrors_allProjects()
	{
		String result = server.getCompilationErrors(null, null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Compilation Problems"));
	}

	@Test
	public void testGetCompilationErrors_missingProject()
	{
		String result = server.getCompilationErrors("NonExistentProject_XYZ", null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("not found"));
	}

	// --- readFileContext, getFileOutline, readFunction ---

	@Test
	public void testReadFileContext_missingProject()
	{
		try
		{
			server.readFileContext("NonExistentProject_XYZ", "some/file.js", "10", null);
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testReadFileContext_nullCenterLine_throws()
	{
		try
		{
			server.readFileContext("SomeProject", "some/file.js", null, null);
			fail("Should throw NullPointerException for null centerLine");
		}
		catch (NullPointerException | NumberFormatException e)
		{
			// expected
		}
	}

	@Test
	public void testGetFileOutline_missingProject()
	{
		try
		{
			server.getFileOutline("NonExistentProject_XYZ", "some/file.js");
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testReadFunction_missingProject()
	{
		try
		{
			server.readFunction("NonExistentProject_XYZ", "some/file.js", "myFunction");
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	// --- getFileInfo ---

	@Test
	public void testGetFileInfo_missingFile_returnsError()
	{
		String result = server.getFileInfo("NonExistentProject_XYZ", "some/file.js");
		assertNotNull(result);
		assertTrue("Should report file not found",
			result.contains("not found") || result.contains("Error") || result.contains("does not exist"));
	}

	// --- readFileRanges ---

	@Test
	public void testReadFileRanges_missingProject()
	{
		try
		{
			server.readFileRanges("NonExistentProject_XYZ", "some/file.js", "1-10");
			fail("Should throw for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}
}

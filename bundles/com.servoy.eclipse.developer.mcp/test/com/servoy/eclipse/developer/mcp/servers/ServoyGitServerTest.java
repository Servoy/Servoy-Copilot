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
 * JUnit 4 tests for {@link ServoyGitServer}.
 * <p>
 * Tests that do not require a live Eclipse workspace:
 * annotation/registration checks, error paths for missing projects.
 * </p>
 * <p>
 * Happy-path tests (gitStatus, gitLog, etc. with real repos) are covered
 * by the curl endpoint tests against Servoy Developer.
 * </p>
 */
public class ServoyGitServerTest
{
	private final ServoyGitServer server = new ServoyGitServer();

	// --- Annotation and registration checks ---

	@Test
	public void testServoyGitServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyGitServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyGitServer must have @McpServer annotation", ann);
		assertEquals("servoy-git", ann.name());
	}

	@Test
	public void testServoyGitServer_hasThirteenToolMethods()
	{
		long toolCount = java.util.Arrays.stream(ServoyGitServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(
				com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.count();
		assertEquals("ServoyGitServer must have exactly 13 @Tool methods", 13, toolCount);
	}

	@Test
	public void testServoyGitServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyGitServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyGitServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyGitServer_canBeInstantiated()
	{
		ServoyGitServer instance = new ServoyGitServer();
		assertNotNull(instance);
	}

	// --- Error paths for missing projects ---

	private static void assertProjectError(RuntimeException e)
	{
		// In the Plugin Test workspace, a non-existent project may either:
		// - throw "Project not found: ..." (project.exists() == false), or
		// - throw "Project is not mapped to a Git repository: ..." (no EGit mapping)
		// Both are valid outcomes. We just verify a non-null message is present.
		assertNotNull("Exception must have a message", e.getMessage());
	}

	@Test
	public void testGitStatus_missingProject()
	{
		try
		{
			server.gitStatus("NonExistentProject_XYZ");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitLog_missingProject()
	{
		try
		{
			server.gitLog("NonExistentProject_XYZ", null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitDiff_missingProject()
	{
		try
		{
			server.gitDiff("NonExistentProject_XYZ", null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitAdd_missingProject()
	{
		try
		{
			server.gitAdd("NonExistentProject_XYZ", ".");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitCommit_missingProject()
	{
		try
		{
			server.gitCommit("NonExistentProject_XYZ", "test message");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitBranch_missingProject()
	{
		try
		{
			server.gitBranch("NonExistentProject_XYZ", null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitCreateBranch_missingProject()
	{
		try
		{
			server.gitCreateBranch("NonExistentProject_XYZ", "new-branch", null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitCheckout_missingProject()
	{
		try
		{
			server.gitCheckout("NonExistentProject_XYZ", "main");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitReset_missingProject()
	{
		try
		{
			server.gitReset("NonExistentProject_XYZ", ".");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitStash_missingProject()
	{
		try
		{
			server.gitStash("NonExistentProject_XYZ", null);
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertProjectError(e);
		}
	}

	@Test
	public void testGitStashPop_missingProject()
	{
		try
		{
			server.gitStashPop("NonExistentProject_XYZ");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testGitStashList_missingProject()
	{
		try
		{
			server.gitStashList("NonExistentProject_XYZ");
			fail("Should throw RuntimeException for missing project");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	// --- Tool name uniqueness ---

	@Test
	public void testToolNames_areUnique()
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		java.util.Arrays.stream(ServoyGitServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.forEach(m -> {
				String name = m.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.Tool.class).name();
				assertFalse("Duplicate tool name: " + name, names.contains(name));
				names.add(name);
			});
		assertEquals(13, names.size());
	}
}

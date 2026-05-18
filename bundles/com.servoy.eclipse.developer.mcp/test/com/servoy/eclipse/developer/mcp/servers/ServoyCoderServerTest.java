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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.guard.ServoyFileFormatProtectedException;

/**
 * JUnit 4 tests for {@link ServoyCoderServer}.
 * <p>
 * Tests that do not require a live Eclipse workspace:
 * file-format guard refusals, null-input errors, annotation/registration checks.
 * </p>
 * <p>
 * Happy-path tests (createFile, replaceString, etc. with real files) are covered
 * by the curl endpoint tests against Servoy Developer.
 * </p>
 */
public class ServoyCoderServerTest
{
	private final ServoyCoderServer server = new ServoyCoderServer();

	// --- File-format guard tests ---

	@Test
	public void testReplaceString_frm_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "forms/test.frm", "old", "new", null, null));
	}

	@Test
	public void testInsertIntoFile_frm_throws()
	{
		assertGuardFires(() -> server.insertIntoFile("MyProject", "forms/test.frm", "content", "1"));
	}

	@Test
	public void testReplaceFileContent_frm_throws()
	{
		assertGuardFires(() -> server.replaceFileContent("MyProject", "forms/test.frm", "content"));
	}

	@Test
	public void testDeleteLinesInFile_frm_throws()
	{
		assertGuardFires(() -> server.deleteLinesInFile("MyProject", "forms/test.frm", "1", "2"));
	}

	@Test
	public void testApplyPatch_frm_throws()
	{
		assertGuardFires(() -> server.applyPatch("MyProject", "forms/test.frm", "@@ -1,1 +1,1 @@\n-old\n+new"));
	}

	@Test
	public void testReplaceString_obj_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "solution_settings.obj", "old", "new", null, null));
	}

	@Test
	public void testReplaceString_tbl_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "datasources/db/server/table.tbl", "old", "new", null, null));
	}

	@Test
	public void testReplaceString_val_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "valuelists/myList.val", "old", "new", null, null));
	}

	@Test
	public void testReplaceString_rel_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "relations/myRel.rel", "old", "new", null, null));
	}

	@Test
	public void testReplaceString_dbi_throws()
	{
		assertGuardFires(() -> server.replaceString("MyProject", "datasources/db/server/table.dbi", "old", "new", null, null));
	}

	// --- undoEdit is exempt from guard ---

	@Test
	public void testUndoEdit_frm_doesNotThrowGuard()
	{
		// undoEdit is exempt â it should throw a RuntimeException about missing project,
		// NOT a ServoyFileFormatProtectedException
		try
		{
			server.undoEdit("NonExistentProject", "forms/test.frm");
			fail("Should throw RuntimeException about missing project");
		}
		catch (ServoyFileFormatProtectedException e)
		{
			fail("undoEdit must NOT trigger the file-format guard: " + e.getMessage());
		}
		catch (RuntimeException e)
		{
			// Expected â project not found, not a guard error
			assertNotNull(e.getMessage());
			assertFalse("Must not be a guard error", e.getMessage().contains("Refusing to edit"));
		}
	}

	// --- Null-input error paths ---

	@Test
	public void testReplaceString_nullProject_throws()
	{
		try
		{
			server.replaceString(null, "scopes/globals.js", "old", "new", null, null);
			fail("Should throw on null projectName");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testCreateFile_nullProject_throws()
	{
		try
		{
			server.createFile(null, "scopes/globals.js", "content");
			fail("Should throw on null projectName");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	// --- Annotation and registration checks ---

	@Test
	public void testServoyCoderServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyCoderServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyCoderServer must have @McpServer annotation", ann);
		assertFalse("@McpServer name must not be empty", ann.name().isBlank());
		assertTrue("@McpServer name must be 'servoy-coder'", "servoy-coder".equals(ann.name()));
	}

	@Test
	public void testServoyCoderServer_hasElevenToolMethods()
	{
		long toolCount = java.util.Arrays.stream(ServoyCoderServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(
				com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.count();
		assertTrue("ServoyCoderServer must have exactly 11 @Tool methods, found: " + toolCount,
			toolCount == 11);
	}

	@Test
	public void testServoyCoderServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyCoderServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyCoderServer must be registered in McpServerBuiltins", found);
	}

	// --- Helper ---

	private static void assertGuardFires(Runnable action)
	{
		try
		{
			action.run();
			fail("Expected RuntimeException wrapping ServoyFileFormatProtectedException");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue("Error message must contain 'Refusing to edit Servoy structural file'",
				e.getMessage().contains("Refusing to edit Servoy structural file"));
		}
	}
}

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;

/**
 * JUnit 4 tests for {@link ServoyCoderServer}.
 * <p>
 * Tests that do not require a live Eclipse workspace: null-input errors,
 * annotation/registration checks, scope path guard (SVY-21203), JS validation
 * (SVY-21113).
 * </p>
 */
public class ServoyCoderServerTest {
	private final ServoyCoderServer server = new ServoyCoderServer(
			new com.servoy.eclipse.developer.mcp.services.CodeEditingService());

	// --- Null-input error paths ---

	@Test
	public void testReplaceString_nullProject_throws() {
		try {
			server.replaceString(null, "scopes/globals.js", "old", "new", null, null);
			fail("Should throw on null projectName");
		} catch (RuntimeException e) {
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testCreateFile_nullProject_throws() {
		// Use a root-level path so the scope guard does not fire first
		try {
			server.createFile(null, "globals.js", "content");
			fail("Should throw on null projectName");
		} catch (RuntimeException e) {
			assertNotNull(e.getMessage());
		}
	}

	// --- Annotation and registration checks ---

	@Test
	public void testServoyCoderServer_hasCorrectAnnotation() {
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyCoderServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyCoderServer must have @McpServer annotation", ann);
		assertFalse("@McpServer name must not be empty", ann.name().isBlank());
		assertTrue("@McpServer name must be 'servoy-coder'", "servoy-coder".equals(ann.name()));
	}

	@Test
	public void testServoyCoderServer_registeredInBuiltins() {
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
			if (cls == ServoyCoderServer.class) {
				found = true;
				break;
			}
		}
		assertTrue("ServoyCoderServer must be registered in McpServerBuiltins", found);
	}

	// --- SVY-21203: scope path guard ---

	@Test
	public void testGuardScopePath_lowercase_scopes_dir_blocked() {
		String result = ServoyCoderServer.guardScopePath("scopes/globals.js");
		assertNotNull("Should block scopes/ subdirectory", result);
		assertTrue("Error message should mention solution root", result.contains("solution"));
		assertTrue("Error message should mention the correct file name", result.contains("globals.js"));
	}

	@Test
	public void testGuardScopePath_uppercase_Scopes_dir_blocked() {
		String result = ServoyCoderServer.guardScopePath("Scopes/utils.js");
		assertNotNull("Should block Scopes/ subdirectory (case-insensitive)", result);
	}

	@Test
	public void testGuardScopePath_nested_scopes_dir_blocked() {
		String result = ServoyCoderServer.guardScopePath("module/scopes/utils.js");
		assertNotNull("Should block nested scopes/ directory", result);
	}

	@Test
	public void testGuardScopePath_rootLevel_allowed() {
		assertNull("Root-level .js should be allowed", ServoyCoderServer.guardScopePath("globals.js"));
	}

	@Test
	public void testGuardScopePath_formsDir_allowed() {
		// forms/ is a different Servoy artifact — not blocked by scope guard
		assertNull("forms/ is not a scopes directory", ServoyCoderServer.guardScopePath("forms/myForm.js"));
	}

	@Test
	public void testGuardScopePath_nonJsFile_allowed() {
		assertNull("Non-.js files should not be blocked", ServoyCoderServer.guardScopePath("scopes/config.json"));
	}

	@Test
	public void testGuardScopePath_null_allowed() {
		assertNull("null path should not throw", ServoyCoderServer.guardScopePath(null));
	}

	@Test
	public void testGuardScopePath_windowsBackslash_blocked() {
		String result = ServoyCoderServer.guardScopePath("Scopes\\globals.js");
		assertNotNull("Should normalise backslashes and block Scopes\\ directory", result);
	}

	@Test
	public void testGuardScopePath_scopeNameContainsScopes_allowed() {
		// File named 'myscopes.js' at root should NOT be blocked — the word appears in
		// the
		// filename, not as a parent directory segment
		assertNull("'myscopes.js' at root should not be blocked", ServoyCoderServer.guardScopePath("myscopes.js"));
	}

	// -----------------------------------------------------------------------
	// SVY-21179: renameFile tool removed from ServoyCoderServer
	// -----------------------------------------------------------------------

	@Test
	public void testRenameFile_toolNotRegisteredOnCoderServer() {
		for (java.lang.reflect.Method candidate : ServoyCoderServer.class.getDeclaredMethods()) {
			Tool ann = candidate.getAnnotation(Tool.class);
			if (ann != null && "renameFile".equals(ann.name())) {
				assertTrue("renameFile must NOT be registered as a @Tool on ServoyCoderServer "
						+ "(it was merged into ServoyDevServer.renameFile)", false);
			}
		}
		// reaching here means no renameFile @Tool found on ServoyCoderServer â
		// correct
		assertTrue(true);
	}

}
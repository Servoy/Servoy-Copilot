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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;

public class DocumentationToolsIntegrationTest extends TestUtilitiesClass {

	private static final String TEST_SCOPE_FILE = "globals.js";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public DocumentationToolsIntegrationTest() {
		super("test_documentation_suite", "servoy_resources");
	}

	@Before
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, (sol, monitor) -> {
			try {
				writeProjectFile(sol, "scopes/" + TEST_SCOPE_FILE,
						"/**\n * @type {String}\n */\nvar testVariable = 'hello';\n\n/**\n * @param {String} name\n * @return {String}\n */\nfunction testFunction(name) {\n\treturn 'Hello ' + name;\n}\n",
						monitor);
			} catch (CoreException e) {
				throw new RuntimeException(e);
			}
		});
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	@Test
	public void testGetDocumentationForTypeMember_success() {
		String result = devServer.getDocumentationForTypeMember("JSApplication", "getUUID");
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation header", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testGetDocumentationForTypeMember_nullType_returnsError() {
		String result = devServer.getDocumentationForTypeMember(null, "getUUID");
		assertTrue("Should return error for null typeName", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_nullMember_returnsError() {
		String result = devServer.getDocumentationForTypeMember("application", null);
		assertTrue("Should return error for null memberName", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_unknownType_returnsError() {
		String result = devServer.getDocumentationForTypeMember("nonExistentType_xyz", "someMethod");
		assertTrue("Should return error for unknown type", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_unknownMember_returnsError() {
		String result = devServer.getDocumentationForTypeMember("application", "nonExistentMethod_xyz");
		assertTrue("Should return error for unknown member", result.startsWith("Error"));
	}

	@Test
	public void testGetAvailableMembersForType_success() {
		String result = devServer.getAvailableMembersForType("JSApplication", null);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain members header", result.contains("AVAILABLE MEMBERS FOR TYPE:"));
		assertTrue("Result should contain total found", result.contains("Total found:"));
	}

	@Test
	public void testGetAvailableMembersForType_withFilter() {
		String result = devServer.getAvailableMembersForType("JSApplication", "get.*");
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain filter info", result.contains("Filter: get.*"));
	}

	@Test
	public void testGetAvailableMembersForType_nullType_returnsError() {
		String result = devServer.getAvailableMembersForType(null, null);
		assertTrue("Should return error for null typeName", result.startsWith("Error"));
	}

	@Test
	public void testGetAvailableMembersForType_unknownType_returnsError() {
		String result = devServer.getAvailableMembersForType("nonExistentType_xyz", null);
		assertTrue("Should return error for unknown type", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_success() {
		String result = devServer.getDocumentationForIdentifiers("application", TEST_SCOPE_FILE);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation section", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_nullIdentifiers_returnsError() {
		String result = devServer.getDocumentationForIdentifiers(null, TEST_SCOPE_FILE);
		assertTrue("Should return error for null identifiers", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_nullFilePath_returnsError() {
		String result = devServer.getDocumentationForIdentifiers("application", null);
		assertTrue("Should return error for null filePath", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_multipleIdentifiers() {
		String result = devServer.getDocumentationForIdentifiers("application,databaseManager", TEST_SCOPE_FILE);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation section", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testApplyDocumentations_nullFilePath_returnsError() {
		String result = devServer.applyDocumentations(null,
				"[{\"startLine\":0,\"endLine\":0,\"jsdoc\":\"/** test */\"}]");
		assertTrue("Should return error for null filePath", result.startsWith("Error"));
	}

	@Test
	public void testApplyDocumentations_nullItemsJson_returnsError() {
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, null);
		assertTrue("Should return error for null itemsJson", result.startsWith("Error"));
	}

	@Test
	public void testApplyDocumentations_emptyItemsJson_returnsError() {
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, "[]");
		assertNotNull(result);
		assertTrue("Should return error for empty items", result.contains("Error"));
	}

	@Test
	public void testApplyDocumentations_insertDocumentation() {
		String itemsJson = "[{\"startLine\":0,\"endLine\":0,\"startSentence\":\"\",\"endSentence\":\"\",\"jsdoc\":\"/**\\n * Test documentation\\n */\"}]";
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, itemsJson);
		assertNotNull(result);
		assertFalse("applyDocumentations should not return a top-level error: " + result, result.startsWith("Error"));
	}

}

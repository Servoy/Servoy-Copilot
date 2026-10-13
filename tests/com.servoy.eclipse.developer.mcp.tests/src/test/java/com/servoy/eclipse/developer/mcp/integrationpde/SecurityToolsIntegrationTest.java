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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;

public class SecurityToolsIntegrationTest extends TestUtilitiesClassCopy {

	private static final String TEST_SOLUTION = "test_security_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String TEST_FORM = "securityTestForm";
	private static final String TEST_PERMISSION = "TestPermission";
	private static final String TEST_USER = "testSecUser";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public SecurityToolsIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");

		String formResult = devServer.createForm(TEST_FORM, null, "800", "600", null, null, null, null);
		assertTrue(!formResult.startsWith("Error") || formResult.contains("already exists"),
				"Form creation should succeed or already exist: " + formResult);
	}

	@Test
	public void testListUsers_returnsResult() {
		String result = devServer.listUsers();
		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "listUsers should not return an error");
	}

	@Test
	public void testCreateUser_success() {
		String uniqueUser = TEST_USER + "_" + System.currentTimeMillis();
		String result = devServer.createUser(uniqueUser, "password123", null);
		assertFalse(result.startsWith("Error"), "createUser should not return an error: " + result);
		assertTrue(result.contains("created successfully"), "Result should confirm creation");
		assertTrue(result.contains("UID:"), "Result should contain UID");
	}

	@Test
	public void testCreateUser_nullName_returnsError() {
		String result = devServer.createUser(null, "password123", null);
		assertTrue(result.startsWith("Error"), "Should return error for null userName");
	}

	@Test
	public void testCreateUser_nullPassword_returnsError() {
		String result = devServer.createUser("someUser", null, null);
		assertTrue(result.startsWith("Error"), "Should return error for null password");
	}

	@Test
	public void testChangeUserName_success() {
		String uniqueUser = "renameMe_" + System.currentTimeMillis();
		String createResult = devServer.createUser(uniqueUser, "pass123", null);
		assertFalse(createResult.startsWith("Error"), "User creation prerequisite failed: " + createResult);

		String newName = "renamed_" + System.currentTimeMillis();
		String result = devServer.changeUserName(uniqueUser, newName);
		assertFalse(result.startsWith("Error"), "changeUserName should not return an error: " + result);
		assertTrue(result.contains("successfully"), "Result should confirm rename");
	}

	@Test
	public void testChangeUserName_notFound_returnsError() {
		String result = devServer.changeUserName("nonExistentUser_xyz", "newName");
		assertTrue(result.startsWith("Error"), "Should return error for non-existent user");
	}

	@Test
	public void testSetUserPassword_success() {
		String uniqueUser = "pwdUser_" + System.currentTimeMillis();
		String createResult = devServer.createUser(uniqueUser, "oldPass", null);
		assertFalse(createResult.startsWith("Error"), "User creation prerequisite failed: " + createResult);

		String result = devServer.setUserPassword(uniqueUser, "newPass123");
		assertFalse(result.startsWith("Error"), "setUserPassword should not return an error: " + result);
		assertTrue(result.contains("successfully"), "Result should confirm password change");
	}

	@Test
	public void testSetUserPassword_notFound_returnsError() {
		String result = devServer.setUserPassword("nonExistentUser_xyz", "newPass");
		assertTrue(result.startsWith("Error"), "Should return error for non-existent user");
	}

	@Test
	public void testCreatePermission_success() {
		String uniquePermission = TEST_PERMISSION + "_" + System.currentTimeMillis();
		String result = devServer.createPermission(uniquePermission);
		assertFalse(result.startsWith("Error"), "createPermission should not return an error: " + result);
		assertTrue(result.contains("created successfully"), "Result should confirm creation");
	}

	@Test
	public void testCreatePermission_nullName_returnsError() {
		String result = devServer.createPermission(null);
		assertTrue(result.startsWith("Error"), "Should return error for null permissionName");
	}

	@Test
	public void testCreatePermission_duplicate_returnsError() {
		String uniquePermission = "dupPerm_" + System.currentTimeMillis();
		String first = devServer.createPermission(uniquePermission);
		assertFalse(first.startsWith("Error"), "First creation should succeed: " + first);

		String second = devServer.createPermission(uniquePermission);
		assertTrue(second.startsWith("Error"), "Duplicate permission should return error");
	}

	@Test
	public void testGetFormSecurity_returnsResult() {
		String uniquePermission = "secPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.getFormSecurity(uniquePermission, TEST_FORM, TEST_SOLUTION);
		assertFalse(result.startsWith("Error"), "getFormSecurity should not return an error: " + result);
		assertTrue(result.contains(TEST_FORM), "Result should reference the form");
	}

	@Test
	public void testGetFormSecurity_nullPermission_returnsError() {
		String result = devServer.getFormSecurity(null, TEST_FORM, TEST_SOLUTION);
		assertTrue(result.startsWith("Error"), "Should return error for null permission");
	}

	@Test
	public void testGetFormSecurity_nullForm_returnsError() {
		String result = devServer.getFormSecurity("SomePermission", null, TEST_SOLUTION);
		assertTrue(result.startsWith("Error"), "Should return error for null form");
	}

	@Test
	public void testSetFormElementAccess_onForm() {
		String uniquePermission = "accessPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, TEST_FORM, null, "true", "true",
				TEST_SOLUTION);
		assertFalse(result.startsWith("Error"), "setFormElementAccess should not return an error: " + result);
		assertTrue(result.contains("Access set"), "Result should confirm access set");
	}

	@Test
	public void testSetFormElementAccess_invalidCombination_returnsError() {
		String uniquePermission = "invalidPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, TEST_FORM, null, "false", "true",
				TEST_SOLUTION);
		assertTrue(result.startsWith("Error"), "Should return error for invalid viewable/accessible combination");
		assertTrue(result.contains("Invalid combination"), "Error should mention invalid combination");
	}

	@Test
	public void testSetFormElementAccess_formNotFound_returnsError() {
		String uniquePermission = "nfPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, "nonExistentForm_xyz", null, "true", "true",
				TEST_SOLUTION);
		assertTrue(result.startsWith("Error"), "Should return error for non-existent form");
	}

	@Test
	public void testSetFormSecurityBulk_success() {
		String uniquePermission = "bulkPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String accessEntries = "[{\"viewable\":true,\"accessible\":true}]";
		String result = devServer.setFormSecurityBulk(uniquePermission, TEST_FORM, accessEntries, TEST_SOLUTION);
		assertFalse(result.startsWith("Error"), "setFormSecurityBulk should not return an error: " + result);
		assertTrue(result.contains("entries applied successfully"), "Result should confirm bulk update");
	}

	@Test
	public void testSetFormSecurityBulk_nullEntries_returnsError() {
		String result = devServer.setFormSecurityBulk("SomePerm", TEST_FORM, null, TEST_SOLUTION);
		assertTrue(result.startsWith("Error"), "Should return error for null accessEntries");
	}

	@Test
	public void testSetFormSecurityBulk_invalidCombination_reportsError() {
		String uniquePermission = "bulkInvalid_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String accessEntries = "[{\"viewable\":false,\"accessible\":true}]";
		String result = devServer.setFormSecurityBulk(uniquePermission, TEST_FORM, accessEntries, TEST_SOLUTION);
		assertFalse(result.startsWith("Error"), "Should not be a top-level error");
		assertTrue(result.contains("Invalid combination"), "Result should report invalid combination error");
	}

}

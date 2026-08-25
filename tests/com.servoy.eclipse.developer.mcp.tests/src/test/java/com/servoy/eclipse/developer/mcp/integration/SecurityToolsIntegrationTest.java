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

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;

public class SecurityToolsIntegrationTest extends TestUtilitiesClass {

	private static final String TEST_SOLUTION = "test_security_suite";
	private static final String TEST_FORM = "securityTestForm";
	private static final String TEST_PERMISSION = "TestPermission";
	private static final String TEST_USER = "testSecUser";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public SecurityToolsIntegrationTest() {
		super(TEST_SOLUTION, "servoy_resources");
	}

	@Before
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);

		String formResult = devServer.createForm(TEST_FORM, null, "800", "600", null, null, null, null);
		assertTrue("Form creation should succeed or already exist: " + formResult,
				!formResult.startsWith("Error") || formResult.contains("already exists"));
	}

	@Test
	public void testListUsers_returnsResult() {
		String result = devServer.listUsers();
		assertNotNull(result);
		assertFalse("listUsers should not return an error", result.startsWith("Error"));
	}

	@Test
	public void testCreateUser_success() {
		String uniqueUser = TEST_USER + "_" + System.currentTimeMillis();
		String result = devServer.createUser(uniqueUser, "password123", null);
		assertFalse("createUser should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm creation", result.contains("created successfully"));
		assertTrue("Result should contain UID", result.contains("UID:"));
	}

	@Test
	public void testCreateUser_nullName_returnsError() {
		String result = devServer.createUser(null, "password123", null);
		assertTrue("Should return error for null userName", result.startsWith("Error"));
	}

	@Test
	public void testCreateUser_nullPassword_returnsError() {
		String result = devServer.createUser("someUser", null, null);
		assertTrue("Should return error for null password", result.startsWith("Error"));
	}

	@Test
	public void testChangeUserName_success() {
		String uniqueUser = "renameMe_" + System.currentTimeMillis();
		String createResult = devServer.createUser(uniqueUser, "pass123", null);
		assertFalse("User creation prerequisite failed: " + createResult, createResult.startsWith("Error"));

		String newName = "renamed_" + System.currentTimeMillis();
		String result = devServer.changeUserName(uniqueUser, newName);
		assertFalse("changeUserName should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm rename", result.contains("successfully"));
	}

	@Test
	public void testChangeUserName_notFound_returnsError() {
		String result = devServer.changeUserName("nonExistentUser_xyz", "newName");
		assertTrue("Should return error for non-existent user", result.startsWith("Error"));
	}

	@Test
	public void testSetUserPassword_success() {
		String uniqueUser = "pwdUser_" + System.currentTimeMillis();
		String createResult = devServer.createUser(uniqueUser, "oldPass", null);
		assertFalse("User creation prerequisite failed: " + createResult, createResult.startsWith("Error"));

		String result = devServer.setUserPassword(uniqueUser, "newPass123");
		assertFalse("setUserPassword should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm password change", result.contains("successfully"));
	}

	@Test
	public void testSetUserPassword_notFound_returnsError() {
		String result = devServer.setUserPassword("nonExistentUser_xyz", "newPass");
		assertTrue("Should return error for non-existent user", result.startsWith("Error"));
	}

	@Test
	public void testCreatePermission_success() {
		String uniquePermission = TEST_PERMISSION + "_" + System.currentTimeMillis();
		String result = devServer.createPermission(uniquePermission);
		assertFalse("createPermission should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm creation", result.contains("created successfully"));
	}

	@Test
	public void testCreatePermission_nullName_returnsError() {
		String result = devServer.createPermission(null);
		assertTrue("Should return error for null permissionName", result.startsWith("Error"));
	}

	@Test
	public void testCreatePermission_duplicate_returnsError() {
		String uniquePermission = "dupPerm_" + System.currentTimeMillis();
		String first = devServer.createPermission(uniquePermission);
		assertFalse("First creation should succeed: " + first, first.startsWith("Error"));

		String second = devServer.createPermission(uniquePermission);
		assertTrue("Duplicate permission should return error", second.startsWith("Error"));
	}

	@Test
	public void testGetFormSecurity_returnsResult() {
		String uniquePermission = "secPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.getFormSecurity(uniquePermission, TEST_FORM, TEST_SOLUTION);
		assertFalse("getFormSecurity should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should reference the form", result.contains(TEST_FORM));
	}

	@Test
	public void testGetFormSecurity_nullPermission_returnsError() {
		String result = devServer.getFormSecurity(null, TEST_FORM, TEST_SOLUTION);
		assertTrue("Should return error for null permission", result.startsWith("Error"));
	}

	@Test
	public void testGetFormSecurity_nullForm_returnsError() {
		String result = devServer.getFormSecurity("SomePermission", null, TEST_SOLUTION);
		assertTrue("Should return error for null form", result.startsWith("Error"));
	}

	@Test
	public void testSetFormElementAccess_onForm() {
		String uniquePermission = "accessPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, TEST_FORM, null, "true", "true",
				TEST_SOLUTION);
		assertFalse("setFormElementAccess should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm access set", result.contains("Access set"));
	}

	@Test
	public void testSetFormElementAccess_invalidCombination_returnsError() {
		String uniquePermission = "invalidPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, TEST_FORM, null, "false", "true",
				TEST_SOLUTION);
		assertTrue("Should return error for invalid viewable/accessible combination", result.startsWith("Error"));
		assertTrue("Error should mention invalid combination", result.contains("Invalid combination"));
	}

	@Test
	public void testSetFormElementAccess_formNotFound_returnsError() {
		String uniquePermission = "nfPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String result = devServer.setFormElementAccess(uniquePermission, "nonExistentForm_xyz", null, "true", "true",
				TEST_SOLUTION);
		assertTrue("Should return error for non-existent form", result.startsWith("Error"));
	}

	@Test
	public void testSetFormSecurityBulk_success() {
		String uniquePermission = "bulkPerm_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String accessEntries = "[{\"viewable\":true,\"accessible\":true}]";
		String result = devServer.setFormSecurityBulk(uniquePermission, TEST_FORM, accessEntries, TEST_SOLUTION);
		assertFalse("setFormSecurityBulk should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should confirm bulk update", result.contains("entries applied successfully"));
	}

	@Test
	public void testSetFormSecurityBulk_nullEntries_returnsError() {
		String result = devServer.setFormSecurityBulk("SomePerm", TEST_FORM, null, TEST_SOLUTION);
		assertTrue("Should return error for null accessEntries", result.startsWith("Error"));
	}

	@Test
	public void testSetFormSecurityBulk_invalidCombination_reportsError() {
		String uniquePermission = "bulkInvalid_" + System.currentTimeMillis();
		devServer.createPermission(uniquePermission);

		String accessEntries = "[{\"viewable\":false,\"accessible\":true}]";
		String result = devServer.setFormSecurityBulk(uniquePermission, TEST_FORM, accessEntries, TEST_SOLUTION);
		assertFalse("Should not be a top-level error", result.startsWith("Error"));
		assertTrue("Result should report invalid combination error", result.contains("Invalid combination"));
	}

}

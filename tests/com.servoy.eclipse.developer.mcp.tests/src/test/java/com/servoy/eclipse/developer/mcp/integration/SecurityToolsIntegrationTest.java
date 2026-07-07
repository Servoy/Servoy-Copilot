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

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class SecurityToolsIntegrationTest {
	private static final String TEST_SOLUTION = "test_security_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String TEST_FORM = "securityTestForm";
	private static final String TEST_PERMISSION = "TestPermission";
	private static final String TEST_USER = "testSecUser";

	private static final long APP_SERVER_POLL_MS = 30_000;
	private static final long ACTIVATE_SETTLE_MS = 5000;

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);

		String formResult = devServer.createForm(TEST_FORM, null, "800", "600", null, null, null);
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

	private void waitForAppServer() throws InterruptedException {
		if (appServerAvailableCache == null) {
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline) {
				Thread.sleep(500);
			}
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assertTrue("Servoy application server not started", appServerAvailableCache);
	}

	private void ensureTestSolutionInWorkspace() throws Exception {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen())
				res.open(monitor);

			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_SOLUTION);
			if (!sol.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_SOLUTION);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
						"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				sol.create(d, monitor);
			}
			if (!sol.isOpen())
				sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ TEST_SOLUTION + "\",\n"
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"c1c2c3d4-e5f6-7890-abcd-323456789abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"c1c2c3d4-e5f6-7890-abcd-323456789abc\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
					monitor);
		}, new NullProgressMonitor());

		pumpEvents(1000);
	}

	private void ensureActiveProject() throws Exception {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		ServoyProject active = model.getActiveProject();
		if (active != null && TEST_SOLUTION.equals(active.getProject().getName()))
			return;

		model.refreshServoyProjects();
		pumpEvents(1000);

		ServoyProject[] projects = model.getServoyProjects();
		assertTrue("No ServoyProject found in workspace", projects != null && projects.length > 0);

		ServoyProject toActivate = null;
		for (ServoyProject p : projects) {
			if (TEST_SOLUTION.equals(p.getProject().getName())) {
				toActivate = p;
				break;
			}
		}
		if (toActivate == null)
			toActivate = projects[0];

		try {
			model.setActiveProject(toActivate, true);
		} catch (Exception e) {
		}

		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
		Display display = Display.getDefault();
		if (display.getThread() == Thread.currentThread()) {
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				display.readAndDispatch();
		} else {
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				Thread.sleep(200);
		}

		assertNotNull("Active project not set", model.getActiveProject());
	}

	private void writeProjectFile(IProject project, String fileName, String content,
			org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
		org.eclipse.core.resources.IFile file = project.getFile(fileName);
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (file.exists()) {
			file.setContents(new java.io.ByteArrayInputStream(bytes), true, false, monitor);
		} else {
			file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
		}
	}

	private void pumpEvents(long ms) {
		try {
			Display display = Display.getDefault();
			long end = System.currentTimeMillis() + ms;
			if (display.getThread() == Thread.currentThread()) {
				while (System.currentTimeMillis() < end)
					display.readAndDispatch();
			} else {
				Thread.sleep(ms);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

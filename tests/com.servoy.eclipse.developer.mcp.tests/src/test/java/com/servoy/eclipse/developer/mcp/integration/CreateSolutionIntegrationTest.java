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
import org.eclipse.core.resources.IFile;
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
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class CreateSolutionIntegrationTest {
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private ServoyDevServer devServer;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureResourcesProject();
	}

	@Test
	public void testCreateSolution_success() throws Exception {
		String solName = "testCreateSol_" + System.currentTimeMillis();

		try {
			String result = devServer.createSolution(solName, "ng_client", "false", "true", null);

			assertNotNull(result);
			assertTrue("Should indicate created: " + result,
					result.contains("Created") || result.contains("success") || result.contains(solName));

			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			assertTrue("Solution project should exist in workspace", project.exists());
		} finally {
			cleanupProject(solName);
		}
	}

	@Test
	public void testCreateSolution_andActivate() throws Exception {
		String solName = "testCreateActivate_" + System.currentTimeMillis();

		try {
			String result = devServer.createSolution(solName, "ng_client", "true", "true", null);

			assertNotNull(result);
			assertTrue("Should indicate created and activated: " + result,
					result.contains("Created") || result.contains("activated") || result.contains(solName));

			pumpEvents(ACTIVATE_SETTLE_MS);

			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			assertNotNull("Should have an active project after activation", activeProject);
		} finally {
			cleanupProject(solName);
		}
	}

	@Test
	public void testCreateSolution_module() throws Exception {
		String parentName = "testModParent_" + System.currentTimeMillis();
		String moduleName = "testModule_" + System.currentTimeMillis();

		try {
			devServer.createSolution(parentName, "ng_client", "true", "false", null);
			pumpEvents(ACTIVATE_SETTLE_MS);

			String result = devServer.createSolution(moduleName, "ng_module", "false", "false", parentName);

			assertNotNull(result);
			assertFalse("Should not start with Error: " + result, result.startsWith("Error"));

			IProject moduleProject = ResourcesPlugin.getWorkspace().getRoot().getProject(moduleName);
			assertTrue("Module project should exist", moduleProject.exists());
		} finally {
			cleanupProject(moduleName);
			cleanupProject(parentName);
		}
	}

	@Test
	public void testCreateSolution_nullName_returnsError() {
		String result = devServer.createSolution(null, "ng_client", "false", "true", null);

		assertNotNull(result);
		assertTrue("Should return error for null name: " + result, result.contains("Error"));
	}

	@Test
	public void testCreateSolution_blankName_returnsError() {
		String result = devServer.createSolution("   ", "ng_client", "false", "true", null);

		assertNotNull(result);
		assertTrue("Should return error for blank name: " + result, result.contains("Error"));
	}

	@Test
	public void testCreateSolution_alreadyExists() throws Exception {
		String solName = "testExistingSol_" + System.currentTimeMillis();

		try {
			devServer.createSolution(solName, "ng_client", "false", "true", null);
			String result = devServer.createSolution(solName, "ng_client", "false", "true", null);

			assertNotNull(result);
			assertTrue("Should indicate already exists: " + result, result.contains("already exists"));
		} finally {
			cleanupProject(solName);
		}
	}

	@Test
	public void testActivateSolution_success() throws Exception {
		String solName = "testActivateSol_" + System.currentTimeMillis();

		try {
			devServer.createSolution(solName, "ng_client", "false", "true", null);

			String result = devServer.activateSolution(solName);

			assertNotNull(result);
			assertFalse("Should not be an error: " + result, result.startsWith("Error"));

			pumpEvents(ACTIVATE_SETTLE_MS);

			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			assertNotNull("Should have active project after activation", activeProject);
		} finally {
			cleanupProject(solName);
		}
	}

	@Test
	public void testActivateSolution_getCompilationErrors_waitsForBuild() throws Exception {
		String solA = "testBuildA_" + System.currentTimeMillis();
		String solB = "testBuildB_" + System.currentTimeMillis();

		try {
			devServer.createSolution(solB, "ng_client", "false", "true", null);

			IProject projectB = ResourcesPlugin.getWorkspace().getRoot().getProject(solB);
			assertTrue("Project B should exist", projectB.exists());

			IFile jsFile = projectB.getFile("globals.js");
			jsFile.create(new java.io.ByteArrayInputStream(
				"function broken( { return; }".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				true, new NullProgressMonitor());

			devServer.createSolution(solA, "ng_client", "true", "true", null);
			pumpEvents(ACTIVATE_SETTLE_MS);

			devServer.activateSolution(solB);

			ServoyIdeServer ideServer = new ServoyIdeServer(
				new com.servoy.eclipse.developer.mcp.services.ProjectService(),
				new com.servoy.eclipse.developer.mcp.services.WorkspaceService(),
				new com.servoy.eclipse.developer.mcp.services.MarkdownService(),
				new com.servoy.eclipse.developer.mcp.services.IdeStateService());

			String result = ideServer.getCompilationErrors(solB, "ERROR", null, null, "true");

			assertNotNull(result);
			assertTrue("Should find syntax error after switching solution", result.contains("ERROR"));
			assertTrue("Should reference the broken file", result.contains("globals.js"));
		} finally {
			cleanupProject(solB);
			cleanupProject(solA);
		}
	}

	@Test
	public void testActivateSolution_nonExistent_returnsError() {
		String result = devServer.activateSolution("nonExistentSolution_XYZ_99999");

		assertNotNull(result);
		assertTrue("Should return error for non-existent solution: " + result,
				result.contains("Error") || result.contains("not found") || result.contains("does not exist"));
	}

	@Test
	public void testActivateSolution_nullName_returnsError() {
		String result = devServer.activateSolution(null);

		assertNotNull(result);
		assertTrue("Should return error for null name: " + result, result.contains("Error"));
	}

	@Test
	public void testCreateSolution_serviceType() throws Exception {
		String solName = "testServiceSol_" + System.currentTimeMillis();

		try {
			String result = devServer.createSolution(solName, "service", "false", "false", null);

			assertNotNull(result);
			assertTrue("Should indicate created: " + result,
					result.contains("Created") || result.contains("success") || result.contains(solName));

			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			assertTrue("Service solution project should exist", project.exists());
		} finally {
			cleanupProject(solName);
		}
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

	private void ensureResourcesProject() throws Exception {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen())
				res.open(monitor);
		}, new NullProgressMonitor());
	}

	private void cleanupProject(String projectName) {
		try {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (project.exists()) {
				project.delete(true, new NullProgressMonitor());
			}
		} catch (Exception e) {
			// best effort cleanup
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

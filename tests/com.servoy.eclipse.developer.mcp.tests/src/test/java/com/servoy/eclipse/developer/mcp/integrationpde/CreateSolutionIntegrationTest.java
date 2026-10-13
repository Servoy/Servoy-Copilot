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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.model.nature.ServoyProject;

public class CreateSolutionIntegrationTest extends TestUtilitiesClassCopy {
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private ServoyDevServer devServer;

	public CreateSolutionIntegrationTest() {
		super(null, null); // we test for sol and res. prj here, we don't need those super utilities that use those
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureResourcesProject();
	}

	@Test
	public void testCreateSolution_success() throws Exception {
		String solName = "testCreateSol_" + System.currentTimeMillis();

		try {
			String result = devServer.createSolution(solName, "ng_client", "false", "true", null);

			assertNotNull(result);
			assertTrue(result.contains("Created") || result.contains("success") || result.contains(solName),
					"Should indicate created: " + result);

			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			assertTrue(project.exists(), "Solution project should exist in workspace");
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
			assertTrue(result.contains("Created") || result.contains("activated") || result.contains(solName),
					"Should indicate created and activated: " + result);

			pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
				IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
				ServoyProject activeProject = model.getActiveProject();
				assertNotNull(activeProject, "Should have an active project after activation");
			});
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
			pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
				String result = devServer.createSolution(moduleName, "ng_module", "false", "false", parentName);

				assertNotNull(result);
				assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);

				IProject moduleProject = ResourcesPlugin.getWorkspace().getRoot().getProject(moduleName);
				assertTrue(moduleProject.exists(), "Module project should exist");
			});
		} finally {
			cleanupProject(moduleName);
			cleanupProject(parentName);
		}
	}

	@Test
	public void testCreateSolution_nullName_returnsError() {
		String result = devServer.createSolution(null, "ng_client", "false", "true", null);

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null name: " + result);
	}

	@Test
	public void testCreateSolution_blankName_returnsError() {
		String result = devServer.createSolution("   ", "ng_client", "false", "true", null);

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for blank name: " + result);
	}

	@Test
	public void testCreateSolution_alreadyExists() throws Exception {
		String solName = "testExistingSol_" + System.currentTimeMillis();

		try {
			devServer.createSolution(solName, "ng_client", "false", "true", null);
			String result = devServer.createSolution(solName, "ng_client", "false", "true", null);

			assertNotNull(result);
			assertTrue(result.contains("already exists"), "Should indicate already exists: " + result);
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
			assertFalse(result.startsWith("Error"), "Should not be an error: " + result);

			pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
				IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
				ServoyProject activeProject = model.getActiveProject();
				assertNotNull(activeProject, "Should have active project after activation");
			});
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
			assertTrue(projectB.exists(), "Project B should exist");

			IFile jsFile = projectB.getFile("globals.js");
			jsFile.create(new java.io.ByteArrayInputStream(
				"function broken( { return; }".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				true, new NullProgressMonitor());

			devServer.createSolution(solA, "ng_client", "true", "true", null);

			devServer.activateSolution(solB);

			ensureSolutionReadyAndOptionallyActive(solA, false);
			ensureSolutionReadyAndOptionallyActive(solB, true);

			ServoyIdeServer ideServer = new ServoyIdeServer(
				new com.servoy.eclipse.developer.mcp.services.ProjectService(),
				new com.servoy.eclipse.developer.mcp.services.WorkspaceService(),
				new com.servoy.eclipse.developer.mcp.services.MarkdownService(),
				new com.servoy.eclipse.developer.mcp.services.IdeStateService());

			String result = ideServer.getCompilationErrors(solB, "ERROR", null, null, "true");

			assertNotNull(result);
			assertTrue(result.contains("ERROR"), "Should find syntax error after switching solution");
			assertTrue(result.contains("globals.js"), "Should reference the broken file");
		} finally {
			cleanupProject(solB);
			cleanupProject(solA);
		}
	}

	@Test
	public void testActivateSolution_nonExistent_returnsError() {
		String result = devServer.activateSolution("nonExistentSolution_XYZ_99999");

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found") || result.contains("does not exist"),
				"Should return error for non-existent solution: " + result);
	}

	@Test
	public void testActivateSolution_nullName_returnsError() {
		String result = devServer.activateSolution(null);

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null name: " + result);
	}

	@Test
	public void testCreateSolution_serviceType() throws Exception {
		String solName = "testServiceSol_" + System.currentTimeMillis();

		try {
			String result = devServer.createSolution(solName, "service", "false", "false", null);

			assertNotNull(result);
			assertTrue(result.contains("Created") || result.contains("success") || result.contains(solName),
					"Should indicate created: " + result);

			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			assertTrue(project.exists(), "Service solution project should exist");
		} finally {
			cleanupProject(solName);
		}
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
}

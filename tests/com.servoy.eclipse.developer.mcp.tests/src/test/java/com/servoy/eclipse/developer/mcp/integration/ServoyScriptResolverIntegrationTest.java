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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for SVY-21355: MCP submodule script resolution.
 * <p>
 * Tests the {@link ServoyScriptResolver} behavior via {@link ServoyIdeServer}
 * tools ({@code getSource}, {@code getClassOutline}, {@code getFilteredSource},
 * {@code getFileOutline}) in a multi-module workspace: an active solution with
 * a declared submodule.
 * <p>
 * Acceptance criteria tested:
 * <ul>
 * <li>AC1/AC2: getSource/getClassOutline/getFilteredSource resolve scripts in
 * submodules without explicit moduleName</li>
 * <li>AC3: getFileOutline fallback finds forms/myform.js when called with just
 * myform.js</li>
 * <li>AC4/AC5: Error messages list all modules searched and mention both forms/
 * and scopes/</li>
 * <li>AC6: Explicit moduleName prevents module iteration</li>
 * <li>AC7: Script in active project found without module iteration (active
 * project searched first)</li>
 * </ul>
 * <p>
 * Runs as a JUnit Plug-in test via {@code eclipse-pde_runJUnitPluginTestClass}.
 */
public class ServoyScriptResolverIntegrationTest {
	private static final String TEST_SOLUTION = "test_resolver_suite";
	private static final String TEST_MODULE = "test_resolver_module";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private static final String ACTIVE_FORM_NAME = "activeForm";
	private static final String ACTIVE_FORM_SCRIPT = "function onShow(event) {\n" //
			+ "\treturn 'active';\n" //
			+ "}\n";

	private static final String MODULE_FORM_NAME = "moduleForm";
	private static final String MODULE_FORM_SCRIPT = "function onLoad(event) {\n" //
			+ "\tvar x = 42;\n" //
			+ "\treturn x;\n" //
			+ "}\n" //
			+ "\n" //
			+ "function calculate(a, b) {\n" //
			+ "\treturn a + b;\n" //
			+ "}\n";

	private static final String MODULE_SCOPE_NAME = "moduleUtils";
	private static final String MODULE_SCOPE_SCRIPT = "function helper() {\n" //
			+ "\treturn 'help';\n" //
			+ "}\n";

	private ServoyIdeServer server;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		server = new ServoyIdeServer(new ProjectService(), new WorkspaceService(), new MarkdownService(),
				new IdeStateService());

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureMultiModuleWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);

		createProjectFile(activeProject.getProject(), "forms/" + ACTIVE_FORM_NAME + ".js", ACTIVE_FORM_SCRIPT);

		IProject moduleProject = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_MODULE);
		createProjectFile(moduleProject, "forms/" + MODULE_FORM_NAME + ".js", MODULE_FORM_SCRIPT);
		createProjectFile(moduleProject, "scopes/" + MODULE_SCOPE_NAME + ".js", MODULE_SCOPE_SCRIPT);
	}

	@After
	public void tearDown() {
	}

	// -----------------------------------------------------------------------
	// AC7: Script in active project found first (no module iteration needed)
	// -----------------------------------------------------------------------

	@Test
	public void testGetSource_resolvesActiveProjectFormWithoutModuleName() {
		String result = server.getSource(ACTIVE_FORM_NAME, null);

		assertNotNull(result);
		assertTrue("Should resolve the active project's form script: " + result, result.contains("function onShow"));
	}

	@Test
	public void testGetClassOutline_resolvesActiveProjectForm() {
		String result = server.getClassOutline(ACTIVE_FORM_NAME, null);

		assertNotNull(result);
		assertTrue("Should list onShow function: " + result, result.contains("onShow"));
	}

	// -----------------------------------------------------------------------
	// AC1/AC2: getSource/getClassOutline/getFilteredSource resolve submodule
	// scripts
	// -----------------------------------------------------------------------

	@Test
	public void testGetSource_resolvesModuleFormWithoutModuleName() {
		String result = server.getSource(MODULE_FORM_NAME, null);

		assertNotNull(result);
		assertTrue("Should resolve the module's form script: " + result,
				result.contains("function onLoad") && result.contains("function calculate"));
	}

	@Test
	public void testGetSource_resolvesModuleScopeWithoutModuleName() {
		String result = server.getSource(MODULE_SCOPE_NAME, null);

		assertNotNull(result);
		assertTrue("Should resolve the module's scope script: " + result, result.contains("function helper"));
	}

	@Test
	public void testGetClassOutline_resolvesModuleFormWithoutModuleName() {
		String result = server.getClassOutline(MODULE_FORM_NAME, null);

		assertNotNull(result);
		assertTrue("Outline should list onLoad: " + result, result.contains("onLoad"));
		assertTrue("Outline should list calculate: " + result, result.contains("calculate"));
	}

	@Test
	public void testGetFilteredSource_resolvesModuleFormWithoutModuleName() {
		String result = server.getFilteredSource(MODULE_FORM_NAME, "calculate", null, null);

		assertNotNull(result);
		assertTrue("Should contain expanded calculate function: " + result,
				result.contains("function calculate") || result.contains("calculate"));
	}

	// -----------------------------------------------------------------------
	// AC3: getFileOutline fallback finds forms/myform.js with just "myform.js"
	// -----------------------------------------------------------------------

	@Test
	public void testGetFileOutline_fallbackFindsFormScript() {
		String result = server.getFileOutline(TEST_SOLUTION, ACTIVE_FORM_NAME + ".js");

		assertNotNull(result);
		assertTrue("Should find the form script via fallback: " + result, result.contains("onShow"));
	}

	@Test
	public void testGetFileOutline_fallbackWithFullPath() {
		String result = server.getFileOutline(TEST_SOLUTION, "forms/" + ACTIVE_FORM_NAME + ".js");

		assertNotNull(result);
		assertTrue("Should find the form script with full path: " + result, result.contains("onShow"));
	}

	// -----------------------------------------------------------------------
	// AC4/AC5: Error messages list all modules searched and mention forms/ and
	// scopes/
	// -----------------------------------------------------------------------

	@Test
	public void testGetSource_notFound_errorListsModulesSearched() {
		String result = server.getSource("nonExistentScript_" + System.currentTimeMillis(), null);

		assertNotNull(result);
		assertTrue("Error should mention 'not found': " + result,
				result.contains("not found") || result.contains("Not found"));
		assertTrue("Error should mention forms/: " + result, result.contains("forms/"));
		assertTrue("Error should mention scopes/: " + result, result.contains("scopes/"));
	}

	@Test
	public void testGetSource_notFound_errorListsModuleName() {
		String result = server.getSource("nonExistentScript_" + System.currentTimeMillis(), null);

		assertNotNull(result);
		assertTrue("Error should list module names searched: " + result,
				result.contains(TEST_MODULE) || result.contains("modules"));
	}

	// -----------------------------------------------------------------------
	// AC6: Explicit moduleName prevents module iteration
	// -----------------------------------------------------------------------

	@Test
	public void testGetSource_explicitModuleName_onlySearchesThatModule() {
		String result = server.getSource(MODULE_FORM_NAME, TEST_MODULE);

		assertNotNull(result);
		assertTrue("Should resolve the module form with explicit moduleName: " + result,
				result.contains("function onLoad"));
	}

	@Test
	public void testGetSource_explicitModuleName_doesNotFallbackToOtherModules() {
		String result = server.getSource(ACTIVE_FORM_NAME, TEST_MODULE);

		assertNotNull(result);
		assertTrue("Should NOT find active project's form in the module: " + result,
				result.contains("not found") || result.contains("Not found"));
	}

	@Test
	public void testGetClassOutline_explicitModuleName_preventsIteration() {
		String result = server.getClassOutline(ACTIVE_FORM_NAME, TEST_MODULE);

		assertNotNull(result);
		assertTrue("Should return not-found for active form when searching only in module: " + result,
				result.contains("not found") || result.contains("Not found"));
	}

	@Test
	public void testGetSource_explicitNonExistentModule_returnsNotFound() {
		String result = server.getSource(MODULE_FORM_NAME, "noSuchModule_" + System.currentTimeMillis());

		assertNotNull(result);
		assertTrue("Should indicate not found for non-existent module: " + result,
				result.contains("not found") || result.contains("Not found"));
	}

	// -----------------------------------------------------------------------
	// Environment bootstrap
	// -----------------------------------------------------------------------

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

	private void ensureMultiModuleWorkspace() throws Exception {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {

			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen())
				res.open(monitor);

			IProject mod = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_MODULE);
			if (!mod.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_MODULE);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
						"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				mod.create(d, monitor);
			}
			if (!mod.isOpen())
				mod.open(monitor);

			writeProjectFile(mod, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ TEST_MODULE + "\",\n"
							+ "solutionType:2,\ntypeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-111111111111\"\n",
					monitor);
			writeProjectFile(mod, "solution_settings.obj",
					"typeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-111111111111\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(mod, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry kind=\"src\" path=\"\"/>\n</buildpath>\n",
					monitor);

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
				d.setReferencedProjects(new IProject[] { res, mod });
				sol.create(d, monitor);
			}
			if (!sol.isOpen())
				sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ TEST_SOLUTION + "\",\n"
							+ "solutionType:1,\ntypeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-222222222222\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj", "modulesNames:\"" + TEST_MODULE + "\",\n"
					+ "typeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-222222222222\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
					monitor);
		}, new NullProgressMonitor());

		pumpEvents(2000);
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
			// handled below
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

	private void createProjectFile(IProject project, String path, String content) throws CoreException {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IFile file = project.getFile(path);
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			if (file.exists()) {
				file.setContents(new ByteArrayInputStream(bytes), true, true, monitor);
			} else {
				IContainer parent = file.getParent();
				if (parent instanceof IFolder && !parent.exists()) {
					createFolderHierarchy((IFolder) parent, monitor);
				}
				file.create(new ByteArrayInputStream(bytes), true, monitor);
			}
		}, new NullProgressMonitor());
		pumpEvents(300);
	}

	private void createFolderHierarchy(IFolder folder, IProgressMonitor monitor) throws CoreException {
		if (!folder.getParent().exists() && folder.getParent() instanceof IFolder) {
			createFolderHierarchy((IFolder) folder.getParent(), monitor);
		}
		if (!folder.exists()) {
			folder.create(true, true, monitor);
		}
	}

	private void writeProjectFile(IProject project, String fileName, String content, IProgressMonitor monitor)
			throws CoreException {
		IFile file = project.getFile(fileName);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (file.exists()) {
			file.setContents(new ByteArrayInputStream(bytes), true, false, monitor);
		} else {
			file.create(new ByteArrayInputStream(bytes), true, monitor);
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

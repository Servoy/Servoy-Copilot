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
import java.io.File;
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
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for the ServoyIdeServer read/navigation tools that need a
 * live Eclipse workspace and (for {@code getSource}) an active Servoy solution:
 * {@code getSource}, {@code getFileOutline}, {@code readFunction},
 * {@code readFileRanges}, {@code readFileContext}, {@code searchAndReplace},
 * {@code openProject} and {@code getConsoleOutput}.
 * <p>
 * The tools are backed by {@code ServoyScriptResolver} (DLTK-aware),
 * {@code WorkspaceService} (IFile access) and {@code ProjectService}
 * (ResourcesPlugin project import), so a running Eclipse workbench is required.
 * This runs as a JUnit Plug-in test. The active-solution bootstrap mirrors
 * {@link CreateArtifactsIntegrationTest}; the plain workspace/file assertions
 * mirror {@link ServoyIdeServerWorkspaceIntegrationTest}.
 */
public class ServoyIdeServerReadIntegrationTest {
	private static final String TEST_SOLUTION = "test_ide_read_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private static final String FORM_NAME = "readTestForm";
	private static final String FORM_SCRIPT = "function onLoad(event) {\n" //
			+ "\tvar total = compute(1, 2);\n" //
			+ "\treturn total;\n" //
			+ "}\n" //
			+ "\n" //
			+ "function compute(a, b) {\n" //
			+ "\tvar sum = a + b;\n" //
			+ "\treturn sum;\n" //
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
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);

		// A JS form script for getSource / getFileOutline / readFunction.
		createProjectFile(activeProject.getProject(), "forms/" + FORM_NAME + ".js", FORM_SCRIPT);
	}

	@After
	public void tearDown() {
		// leave the shared solution project in place for reuse by other suites;
		// only remove the transient imported project (handled in that test).
	}

	// -----------------------------------------------------------------------
	// getSource (Servoy scope/form scriptfile resolution)
	// -----------------------------------------------------------------------

	@Test
	public void testGetSource_resolvesFormScript() {
		String result = server.getSource(FORM_NAME, null);

		assertNotNull(result);
		assertTrue("getSource should return the form script body: " + result,
				result.contains("function onLoad") && result.contains("function compute"));
	}

	@Test
	public void testGetSource_unknownName_returnsNotFound() {
		String result = server.getSource("no_such_form_" + System.currentTimeMillis(), null);

		assertNotNull(result);
		assertTrue("Should indicate the script was not found: " + result, result.contains("not found"));
	}

	@Test
	public void testGetSource_blankName_throws() {
		try {
			server.getSource("   ", null);
			assertTrue("Expected RuntimeException for blank name", false);
		} catch (RuntimeException e) {
			assertTrue("Message should mention 'name' is required: " + e.getMessage(),
					e.getMessage() != null && e.getMessage().contains("name"));
		}
	}

	// -----------------------------------------------------------------------
	// getFileOutline + readFunction
	// -----------------------------------------------------------------------

	@Test
	public void testGetFileOutline_listsFunctions() {
		String result = server.getFileOutline(TEST_SOLUTION, "forms/" + FORM_NAME + ".js");

		assertNotNull(result);
		assertTrue("Outline should list onLoad: " + result, result.contains("onLoad"));
		assertTrue("Outline should list compute: " + result, result.contains("compute"));
	}

	@Test
	public void testReadFunction_returnsFunctionBody() {
		String result = server.readFunction(TEST_SOLUTION, "forms/" + FORM_NAME + ".js", "compute");

		assertNotNull(result);
		assertTrue("Should return the compute function: " + result, result.contains("function compute"));
		assertTrue("Should include the function body: " + result, result.contains("var sum = a + b"));
		assertFalse("Should not include the other function body: " + result, result.contains("compute(1, 2)"));
	}

	// -----------------------------------------------------------------------
	// readFileRanges + readFileContext
	// -----------------------------------------------------------------------

	@Test
	public void testReadFileRanges_returnsRequestedWindow() {
		String result = server.readFileRanges(TEST_SOLUTION, "forms/" + FORM_NAME + ".js", "1-1,6-6");

		assertNotNull(result);
		assertTrue("Range header should be present: " + result, result.contains("Lines 1-1"));
		assertTrue("First range should contain onLoad declaration: " + result, result.contains("function onLoad"));
		assertTrue("Second range should contain compute declaration: " + result, result.contains("function compute"));
	}

	@Test
	public void testReadFileContext_returnsWindowAroundCenter() {
		String result = server.readFileContext(TEST_SOLUTION, "forms/" + FORM_NAME + ".js", "2", "1");

		assertNotNull(result);
		assertTrue("Should report the center line: " + result, result.contains("Center line: 2"));
		assertTrue("Window should include the compute call on line 2: " + result, result.contains("compute(1, 2)"));
	}

	// -----------------------------------------------------------------------
	// searchAndReplace
	// -----------------------------------------------------------------------

	@Test
	public void testSearchAndReplace_replacesTextInFile() throws Exception {
		String fileName = "search_target_" + System.currentTimeMillis() + ".txt";
		String unique = "REPLACEME_" + System.currentTimeMillis();
		createProjectFile(activeProject.getProject(), fileName, "alpha " + unique + " omega\n");

		String result = server.searchAndReplace(unique, "DONE", "*.txt");

		assertNotNull(result);
		assertTrue("Should report replacements: " + result, result.contains("replacement"));

		IFile file = activeProject.getProject().getFile(fileName);
		String content = readFileContent(file);
		assertTrue("File should contain the replacement text: " + content, content.contains("DONE"));
		assertFalse("File should no longer contain the original text: " + content, content.contains(unique));
	}

	@Test
	public void testSearchAndReplace_noMatch_returnsMessage() {
		String result = server.searchAndReplace("no_such_text_" + System.currentTimeMillis(), "x", "*.txt");

		assertNotNull(result);
		assertTrue("Should report no matches: " + result, result.contains("No matches found"));
	}

	// -----------------------------------------------------------------------
	// openProject
	// -----------------------------------------------------------------------

	@Test
	public void testOpenProject_importsDirectory() throws Exception {
		String projectName = "imported_ide_read_" + System.currentTimeMillis();
		File dir = new File(System.getProperty("java.io.tmpdir"), projectName);
		assertTrue("Test dir should be creatable", dir.mkdirs() || dir.isDirectory());

		try {
			String result = server.openProject(dir.getAbsolutePath());

			assertNotNull(result);
			assertTrue("Should indicate the project was imported/opened: " + result,
					result.contains("imported") || result.contains("already"));

			IProject imported = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			assertTrue("Imported project should exist in the workspace", imported.exists());
		} finally {
			IProject imported = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (imported.exists()) {
				imported.delete(false, true, new NullProgressMonitor());
			}
			dir.delete();
		}
	}

	@Test
	public void testOpenProject_missingDirectory_returnsError() {
		String result = server.openProject(
				new File(System.getProperty("java.io.tmpdir"), "does_not_exist_" + System.currentTimeMillis())
						.getAbsolutePath());

		assertNotNull(result);
		assertTrue("Should error for missing directory: " + result,
				result.contains("Error") && result.contains("does not exist"));
	}

	// -----------------------------------------------------------------------
	// getConsoleOutput (smoke)
	// -----------------------------------------------------------------------

	@Test
	public void testGetConsoleOutput_smoke() {
		String result = server.getConsoleOutput(null, "50", "false", "false");

		assertNotNull(result);
		assertTrue("Should return the console-output header: " + result, result.contains("Console Output"));
	}

	// -----------------------------------------------------------------------
	// Environment bootstrap (mirrors CreateArtifactsIntegrationTest)
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"e3d4f5a6-b7c8-9012-cdef-445678901abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"e3d4f5a6-b7c8-9012-cdef-445678901abc\",\nversion:\"1.0\"\n", monitor);
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

	private String readFileContent(IFile file) throws Exception {
		try (java.io.InputStream in = file.getContents()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

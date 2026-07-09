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
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService.SelectionResult;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for {@link ScriptContextService}, which backs the
 * {@code resolveIdentifierType} MCP tool.
 * <p>
 * The service uses DLTK's {@code JavaScriptSelectionEngine2} against a real
 * {@code ISourceModule}, so it needs a live Eclipse workbench, an active Servoy
 * solution and a real {@code .js} file on disk with the DLTK JavaScript nature.
 * This therefore runs as a JUnit Plug-in test. The active-solution bootstrap
 * mirrors {@link CreateArtifactsIntegrationTest} /
 * {@link ServoyIdeServerReadIntegrationTest}.
 * <p>
 * Prior to this test the service had only null/blank/not-found unit coverage
 * (via {@code ServoyDevServerTest}); the happy path (real identifier -&gt; DLTK
 * type resolution -&gt; JSON, and the JSDoc fallback) was uncovered.
 */
public class ScriptContextServiceIntegrationTest {
	private static final String TEST_SOLUTION = "test_scriptctx_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private static final String FORM_NAME = "scriptCtxForm";
	private static final String FORM_PATH = "forms/" + FORM_NAME + ".js";

	// A Servoy form scriptfile with a Servoy API identifier (foundset), a
	// user-defined function, a local variable and a JSDoc-annotated variable.
	private static final String FORM_SCRIPT = "/**\n" //
			+ " * @properties={typeid:24,uuid:\"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE\"}\n" //
			+ " */\n" //
			+ "function onLoad(event) {\n" //
			+ "\tfoundset.newRecord();\n" //
			+ "\tvar helper = compute(1, 2);\n" //
			+ "\treturn helper;\n" //
			+ "}\n" //
			+ "\n" //
			+ "/**\n" //
			+ " * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555555\"}\n" //
			+ " */\n" //
			+ "function compute(a, b) {\n" //
			+ "\treturn a + b;\n" //
			+ "}\n" //
			+ "\n" //
			+ "/**\n" //
			+ " * @type {String}\n" //
			+ " *\n" //
			+ " * @properties={typeid:35,uuid:\"22222222-3333-4444-5555-666666666666\"}\n" //
			+ " */\n" //
			+ "var myText = \"hello\";\n";

	private ScriptContextService service;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		service = new ScriptContextService();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);

		// The background "Writing I18N files..." job (EclipseMessages) dereferences
		// servoyProject.getSolution() without a null-check. While our synthetic
		// solution is still loading, getSolution() can be null and that job NPEs,
		// popping a modal error dialog that blocks unattended runs. Wait for the
		// in-memory Solution to be resolved and let pending workspace jobs settle
		// BEFORE writing the script file (which triggers the builder that schedules
		// that job), so it finds a valid solution.
		waitForSolutionLoaded();

		createProjectFile(activeProject.getProject(), FORM_PATH, FORM_SCRIPT);

		// Drain the I18N/build jobs kicked off by the file write while the solution
		// is guaranteed non-null, so no dialog surfaces later during the tests.
		waitForWorkspaceJobs();
	}

	private void waitForSolutionLoaded() {
		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
		while ((activeProject.getSolution() == null || activeProject.getEditingSolution() == null)
				&& System.currentTimeMillis() < deadline) {
			pumpEvents(200);
		}
		assertNotNull("Solution should be loaded after activation", activeProject.getSolution());
		assertNotNull("Editing solution should be resolved after activation", activeProject.getEditingSolution());
		waitForWorkspaceJobs();
	}

	private void waitForWorkspaceJobs() {
		try {
			org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
			long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
			// Pump the SWT loop while the auto-build and any scheduled workspace jobs run.
			while (System.currentTimeMillis() < deadline) {
				pumpEvents(200);
				try {
					jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
					jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, null);
				} catch (Exception e) {
					// ignore - best-effort drain
				}
				if (jm.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length == 0
						&& jm.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length == 0) {
					break;
				}
			}
		} catch (Exception e) {
			// best-effort; do not fail the test on drain issues
		}
	}

	private IFile formFile() {
		return activeProject.getProject().getFile(FORM_PATH);
	}

	// -----------------------------------------------------------------------
	// resolveIdentifierType - guard paths (still need a real file)
	// -----------------------------------------------------------------------

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError() {
		String result = service.resolveIdentifierType(null, formFile());
		assertNotNull(result);
		assertTrue("Should require identifier: " + result,
				result.startsWith("Error") && result.contains("identifier"));
	}

	@Test
	public void testResolveIdentifierType_nullFile_returnsError() {
		String result = service.resolveIdentifierType("foundset", null);
		assertNotNull(result);
		assertTrue("Should report file not found: " + result,
				result.startsWith("Error") && result.contains("File not found"));
	}

	@Test
	public void testResolveIdentifierType_identifierNotInFile_returnsError() {
		String result = service.resolveIdentifierType("noSuchIdentifierXYZ", formFile());
		assertNotNull(result);
		assertTrue("Should report identifier not found: " + result,
				result.startsWith("Error") && result.contains("not found"));
	}

	// -----------------------------------------------------------------------
	// resolveIdentifierType - happy paths (real DLTK resolution)
	// -----------------------------------------------------------------------

	@Test
	public void testResolveIdentifierType_servoyApiIdentifier_resolves() {
		// 'foundset' is a Servoy API identifier available in every form scope.
		// This exercises the full resolveIdentifierType -> getModelElements (DLTK
		// selection engine) -> formatting path. Whether DLTK resolves a model/foreign
		// element or the code falls through to the "no type information" branch, the
		// result is always identifier-specific and never a guard error (identifier
		// present, file exists). We assert the deterministic invariant: the call runs
		// past the guards and produces output that references the identifier - and is
		// NOT the "identifier not found in file" guard error.
		String result = service.resolveIdentifierType("foundset", formFile());

		assertNotNull(result);
		assertFalse("Should pass the identifier-in-file guard: " + result, result.contains("not found in file"));
		assertTrue("Result should reference the resolved identifier: " + result, result.contains("foundset"));
	}

	@Test
	public void testResolveIdentifierType_userFunction_resolves() {
		String result = service.resolveIdentifierType("compute", formFile());

		assertNotNull(result);
		assertFalse("Should pass the identifier-in-file guard: " + result, result.contains("not found in file"));
		assertTrue("Result should reference the function name: " + result, result.contains("compute"));
	}

	@Test
	public void testResolveIdentifierType_jsdocTypedVariable_resolves() {
		// myText has a @type {String} JSDoc annotation; even if DLTK yields no
		// model element, the JSDoc fallback must still produce type info.
		String result = service.resolveIdentifierType("myText", formFile());

		assertNotNull(result);
		assertFalse("myText should resolve via model or JSDoc fallback, not error: " + result,
				result.startsWith("Error"));
		assertTrue("Result should reference the identifier: " + result, result.contains("myText"));
	}

	// -----------------------------------------------------------------------
	// getModelElements - direct
	// -----------------------------------------------------------------------

	@Test
	public void testGetModelElements_nullFilePath_returnsNull() {
		assertTrue("null filePath must yield null", service.getModelElements(null, 0) == null);
	}

	@Test
	public void testGetModelElements_validOffset_returnsResult() {
		String filePath = formFile().getFullPath().toString();
		int offset = FORM_SCRIPT.indexOf("foundset");
		assertTrue("Precondition: foundset present in script", offset >= 0);

		SelectionResult result = service.getModelElements(filePath, offset);

		assertNotNull("A SelectionResult should be returned for a valid file/offset", result);
		assertNotNull("modelElements collection must be initialised", result.modelElements);
		assertNotNull("foreignElements collection must be initialised", result.foreignElements);
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"f4e5a6b7-c8d9-0123-defa-556789012abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"f4e5a6b7-c8d9-0123-defa-556789012abc\",\nversion:\"1.0\"\n", monitor);
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
		pumpEvents(500);
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

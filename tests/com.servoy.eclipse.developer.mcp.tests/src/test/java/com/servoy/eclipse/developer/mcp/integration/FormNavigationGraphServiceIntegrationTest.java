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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphService;
import com.servoy.eclipse.developer.mcp.services.NavigationEdge;
import com.servoy.eclipse.developer.mcp.services.NavigationGraph;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for {@link FormNavigationGraphService#buildFullGraph()},
 * specifically its script-analysis pass ({@code augmentWithScriptAnalysis} -&gt;
 * {@code scanProjectScripts} -&gt; {@code analyzeScriptFile}).
 * <p>
 * {@code buildFullGraph} resolves the active solution via
 * {@code ServoyModelManager} and walks the project's {@code .js} resources, so a
 * live Eclipse workbench with an active Servoy solution is required. This runs
 * as a JUnit Plug-in test. The bootstrap mirrors
 * {@link CreateArtifactsIntegrationTest}.
 * <p>
 * The pure regex/graph helpers are already unit-covered by
 * {@code FormNavigationGraphServiceTest} / {@code NavigationGraphTest}; this
 * test exercises the previously-uncovered path of analysing a REAL script file
 * on disk and building navigation edges from it.
 */
public class FormNavigationGraphServiceIntegrationTest {
	private static final String TEST_SOLUTION = "test_navgraph_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private static final String SOURCE_FORM = "navSource";
	private static final String SOURCE_PATH = "forms/" + SOURCE_FORM + ".js";

	// A script exercising several navigation patterns analyzeScriptFile detects:
	// navigateToForm(forms.X), the variable-stored window .show(forms.X) dialog
	// pattern (createWindow + var.show), and showFormPopup(element, forms.X).
	private static final String SOURCE_SCRIPT = "function goDetail() {\n" //
			+ "\tscopes.nav.navigateToForm(forms.navDetail);\n" //
			+ "}\n" //
			+ "\n" //
			+ "function openDialog() {\n" //
			+ "\tvar dlgWin = application.createWindow(\"dlg\", JSWindow.MODAL_DIALOG);\n" //
			+ "\tdlgWin.show(forms.navDialog);\n" //
			+ "}\n" //
			+ "\n" //
			+ "function openPopup(element) {\n" //
			+ "\tplugins.window.showFormPopup(element, forms.navPopup, null, null);\n" //
			+ "}\n";

	private FormNavigationGraphService service;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		service = new FormNavigationGraphService();

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

		createProjectFile(activeProject.getProject(), SOURCE_PATH, SOURCE_SCRIPT);

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

	// -----------------------------------------------------------------------
	// buildFullGraph -> analyzeScriptFile
	// -----------------------------------------------------------------------

	@Test
	public void testBuildFullGraph_returnsNonNullGraph() {
		NavigationGraph graph = service.buildFullGraph();
		assertNotNull("buildFullGraph must never return null for an active solution", graph);
	}

	@Test
	public void testBuildFullGraph_analyzesScriptAndAddsNavigationEdges() {
		NavigationGraph graph = service.buildFullGraph();
		assertNotNull(graph);

		List<NavigationEdge> edges = graph.getEdgesFrom(SOURCE_FORM);
		assertNotNull("Edges list from the source form must not be null", edges);
		assertTrue("Script analysis should produce at least one navigation edge from " + SOURCE_FORM + ": " + edges,
				!edges.isEmpty());

		assertTrue("navigateToForm(forms.navDetail) should yield an edge to navDetail: " + edges,
				hasEdgeTo(edges, "navDetail"));
		assertTrue("showFormInDialog(forms.navDialog) should yield an edge to navDialog: " + edges,
				hasEdgeTo(edges, "navDialog"));
		assertTrue("showFormPopup(...forms.navPopup) should yield an edge to navPopup: " + edges,
				hasEdgeTo(edges, "navPopup"));
	}

	@Test
	public void testBuildFullGraph_dialogEdgeHasDynamicConfidence() {
		NavigationGraph graph = service.buildFullGraph();
		NavigationEdge dialogEdge = null;
		for (NavigationEdge e : graph.getEdgesFrom(SOURCE_FORM)) {
			if ("navDialog".equals(e.getTo())) {
				dialogEdge = e;
				break;
			}
		}
		assertNotNull("Expected a dialog edge to navDialog", dialogEdge);
		assertTrue("Script-derived edges are dynamic-confidence: " + dialogEdge.getConfidence(),
				"dynamic".equals(dialogEdge.getConfidence()));
		assertTrue("Dialog edge should have containerType 'dialog': " + dialogEdge.getContainerType(),
				"dialog".equals(dialogEdge.getContainerType()));
	}

	private static boolean hasEdgeTo(List<NavigationEdge> edges, String target) {
		for (NavigationEdge e : edges) {
			if (target.equals(e.getTo()))
				return true;
		}
		return false;
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"a5b6c7d8-e9f0-1234-efab-667890123abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"a5b6c7d8-e9f0-1234-efab-667890123abc\",\nversion:\"1.0\"\n", monitor);
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

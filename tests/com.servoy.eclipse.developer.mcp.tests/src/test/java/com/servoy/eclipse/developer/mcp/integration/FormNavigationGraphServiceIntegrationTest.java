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

import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphService;
import com.servoy.eclipse.developer.mcp.services.NavigationEdge;
import com.servoy.eclipse.developer.mcp.services.NavigationGraph;
import com.servoy.eclipse.model.nature.ServoyProject;

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
public class FormNavigationGraphServiceIntegrationTest extends TestUtilitiesClass {
	private static final String TEST_SOLUTION = "test_navgraph_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

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

	public FormNavigationGraphServiceIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception {
		service = new FormNavigationGraphService();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();

		writeProjectFileInWorkspaceRun(activeProject.getProject(), SOURCE_PATH, SOURCE_SCRIPT);

		// Drain the I18N/build jobs kicked off by the file write while the solution
		// is guaranteed non-null, so no dialog surfaces later during the tests.
		waitForWorkspaceBuildJobs();
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

}

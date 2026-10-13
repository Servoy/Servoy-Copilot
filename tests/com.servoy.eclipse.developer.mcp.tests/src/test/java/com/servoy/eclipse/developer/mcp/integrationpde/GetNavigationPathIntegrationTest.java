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
import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;

/**
 * Integration test for the {@code getNavigationPath} E2E-planning tool on
 * {@link ServoyTestingServer}.
 * <p>
 * {@code getNavigationPath} calls {@code FormNavigationGraphService.buildFullGraph()}
 * (which resolves the active solution and analyses its scripts) and then
 * {@code NavigationGraph.findPath(...)}, so it needs a live Eclipse workbench
 * with an active Servoy solution and a real seeded form script. This runs as a
 * JUnit Plug-in test; the bootstrap mirrors
 * {@link FormNavigationGraphServiceIntegrationTest}.
 * <p>
 * The pure path-finding logic is unit-covered by {@code NavigationGraphTest};
 * this test exercises the end-to-end server tool against a real graph built from
 * a real script, plus its guard/error branches (missing target, undeterminable
 * main form, unreachable target).
 */
public class GetNavigationPathIntegrationTest extends TestUtilitiesClassCopy {
	private static final String TEST_SOLUTION = "test_navpath_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String SOURCE_FORM = "navPathSource";
	private static final String SOURCE_PATH = "forms/" + SOURCE_FORM + ".js";

	// Seeds a single deterministic navigation edge navPathSource -> navPathTarget
	// via the navigateToForm(forms.X) pattern that analyzeScriptFile recognises.
	private static final String SOURCE_SCRIPT = "function goTarget() {\n" //
			+ "\tscopes.nav.navigateToForm(forms.navPathTarget);\n" //
			+ "}\n";

	private ServoyTestingServer testingServer;

	public GetNavigationPathIntegrationTest() {
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
		testingServer = new ServoyTestingServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, (solPrj, monitor) -> {
			try {
				writeProjectFileInWorkspaceRun(solPrj, SOURCE_PATH, SOURCE_SCRIPT);
			} catch (CoreException e) {
				fail("Cannot write script file: " + e.getMessage());
			}
		});
		ensureActiveProject();

		// Drain the I18N/build jobs kicked off by the file write while the solution
		// is guaranteed non-null, so no dialog surfaces later during the tests.
		waitForWorkspaceBuildJobs();
	}

	// -----------------------------------------------------------------------
	// guard paths
	// -----------------------------------------------------------------------

	@Test
	public void testGetNavigationPath_nullTarget_returnsError() {
		String result = testingServer.getNavigationPath(null, SOURCE_FORM);
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("targetForm"),
				"Should require targetForm: " + result);
	}

	@Test
	public void testGetNavigationPath_blankTarget_returnsError() {
		String result = testingServer.getNavigationPath("   ", SOURCE_FORM);
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("targetForm"),
				"Should require targetForm: " + result);
	}

	@Test
	public void testGetNavigationPath_unreachableTarget_returnsNoPath() {
		String result = testingServer.getNavigationPath("noSuchForm_" + System.currentTimeMillis(), SOURCE_FORM);
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("No navigation path found"),
				"Should report no path found: " + result);
	}

	// -----------------------------------------------------------------------
	// happy path (real graph built from the seeded script)
	// -----------------------------------------------------------------------

	@Test
	public void testGetNavigationPath_seededEdge_returnsPathJson() {
		String result = testingServer.getNavigationPath("navPathTarget", SOURCE_FORM);

		assertNotNull(result);
		assertFalse(result.startsWith("Error"),
				"A path should be found from the seeded edge, not an error: " + result);
		// getNavigationPath returns JSON: { "mainForm":..., "pathTo":..., "steps":[ { "from":.., "to":.. } ] }
		assertTrue(result.contains("\"steps\""), "Result should be JSON with a steps array: " + result);
		assertTrue(result.contains(SOURCE_FORM), "Result should reference the start form: " + result);
		assertTrue(result.contains("navPathTarget"), "Result should reference the target form: " + result);
	}

}

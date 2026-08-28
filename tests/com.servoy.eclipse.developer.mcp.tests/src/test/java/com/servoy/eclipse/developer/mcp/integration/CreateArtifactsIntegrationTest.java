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

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

public class CreateArtifactsIntegrationTest extends TestUtilitiesClass {
	private static final String TEST_SOLUTION = "test_artifacts_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public CreateArtifactsIntegrationTest() {
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
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	@Test
	public void testCreateForm_success() {
		String formName = "testForm_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "css", "640", "480", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form should exist in active solution", form);
	}

	@Test
	public void testCreateForm_responsive() {
		String formName = "testFormResp_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "responsive", "800", "600", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Responsive form should exist", form);
	}

	@Test
	public void testCreateForm_duplicate_returnsError() {
		String formName = "testFormDup_" + System.currentTimeMillis();

		devServer.createForm(formName, "css", "640", "480", null, null, null, null);
		String result = devServer.createForm(formName, "css", "640", "480", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result,
				result.contains("Error") || result.contains("already exists"));
	}

	@Test
	public void testCreateForm_withEvents() {
		String formName = "testFormEvents_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "css", "640", "480", null, null,
				"onLoad:initForm,onShow:refreshData", null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form with events should exist", form);
	}

	@Test
	public void testCreateForm_nullName_returnsError() {
		String result = devServer.createForm(null, "css", "640", "480", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for null name: " + result, result.contains("Error"));
	}

	@Test
	public void testCreateRelation_success() {
		String relName = "testRel_" + System.currentTimeMillis();

		String result = devServer.createRelation(relName, "db:/mem/table1", "db:/mem/table2", null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(relName));

		Solution solution = activeProject.getEditingSolution();
		Relation rel = solution.getRelation(relName);
		assertNotNull("Relation should exist in active solution", rel);
	}

	@Test
	public void testCreateRelation_withColumns() {
		String relName = "testRelCols_" + System.currentTimeMillis();

		String result = devServer.createRelation(relName, "db:/mem/table1", "db:/mem/table2", "id", "parent_id",
				"inner");

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(relName));

		Relation rel = activeProject.getEditingSolution().getRelation(relName);
		assertNotNull("Relation with columns should exist", rel);
	}

	@Test
	public void testCreateRelation_duplicate_returnsError() {
		String relName = "testRelDup_" + System.currentTimeMillis();

		devServer.createRelation(relName, "db:/mem/table1", "db:/mem/table2", null, null, null);
		String result = devServer.createRelation(relName, "db:/mem/table1", "db:/mem/table2", null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result,
				result.contains("Error") || result.contains("already exists"));
	}

	@Test
	public void testCreateRelation_nullName_returnsError() {
		String result = devServer.createRelation(null, "db:/mem/table1", "db:/mem/table2", null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for null name: " + result, result.contains("Error"));
	}

	@Test
	public void testCreateValueList_custom_success() {
		String vlName = "testVL_" + System.currentTimeMillis();

		String result = devServer.createValueList(vlName, "custom", "Active\nInactive\nPending", null, null, null,
				null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(vlName));

		Solution solution = activeProject.getEditingSolution();
		ValueList vl = solution.getValueList(vlName);
		assertNotNull("ValueList should exist in active solution", vl);
	}

	@Test
	public void testCreateValueList_database() {
		String vlName = "testVLDB_" + System.currentTimeMillis();

		String result = devServer.createValueList(vlName, "database", null, "db:/mem/table1", null, "name", "id");

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(vlName));

		ValueList vl = activeProject.getEditingSolution().getValueList(vlName);
		assertNotNull("Database ValueList should exist", vl);
	}

	@Test
	public void testCreateValueList_duplicate_returnsError() {
		String vlName = "testVLDup_" + System.currentTimeMillis();

		devServer.createValueList(vlName, "custom", "A\nB", null, null, null, null);
		String result = devServer.createValueList(vlName, "custom", "C\nD", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result,
				result.contains("Error") || result.contains("already exists"));
	}

	@Test
	public void testCreateValueList_nullName_returnsError() {
		String result = devServer.createValueList(null, "custom", "A\nB", null, null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for null name: " + result, result.contains("Error"));
	}

}

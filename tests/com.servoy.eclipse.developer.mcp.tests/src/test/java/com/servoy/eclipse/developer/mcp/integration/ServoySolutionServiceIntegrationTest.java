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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Integration tests for {@link ServoySolutionService}.
 * Requires Eclipse platform + Servoy runtime with an active solution.
 */
public class ServoySolutionServiceIntegrationTest extends TestUtilitiesClass
{
	private ServoySolutionService service;
	
	public ServoySolutionServiceIntegrationTest() {
		super("testSolForServoySolutionServiceIntegrationTest", "servoy_resources");
	}

	@Before
	public void setUp() throws Exception
	{
		service = new ServoySolutionService();
		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());
		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		// Seed the test solution with representative data via the Servoy solution API
		// (must run after ensureActiveProject() so the model has the solution loaded)
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();

		// one form (CSS layout, no datasource) — exercises the listForms / findForm paths
		if (solution.getForm("testSolSvcForm") == null)
			new ServoyArtifactCreationService().createForm("testSolSvcForm", "css", 640, 480, null, null, null);

		// one relation — exercises the listRelations path
		if (solution.getRelation("testSolSvcRelation") == null)
		{
			Relation rel = solution.createNewRelation(validator, "testSolSvcRelation",
				"db:/mem/table1", "db:/mem/table2", 1);
			activeProject.saveEditingSolutionNodes(new IPersist[] { rel }, true);
		}

		// one custom valuelist — exercises the listValueLists path
		if (solution.getValueList("testSolSvcValueList") == null)
		{
			ValueList vl = solution.createNewValueList(validator, "testSolSvcValueList");
			vl.setCustomValues("Alpha\nBeta\nGamma");
			activeProject.saveEditingSolutionNodes(new IPersist[] { vl }, true);
		}

		waitForWorkspaceBuildJobs();
	}

	// -------------------------------------------------------------------------
	// listForms
	// -------------------------------------------------------------------------

	@Test
	public void testListForms_all_returnsContent()
	{
		String result = service.listForms("all");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		// Either lists forms or says "No forms found"
		assertTrue("Should contain forms or no-forms message: " + result,
			result.contains("Forms in") || result.contains("No forms found"));
	}

	@Test
	public void testListForms_current_returnsContent()
	{
		String result = service.listForms("current");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue("Should contain forms or no-forms message: " + result,
			result.contains("Forms in") || result.contains("No forms found"));
	}

	@Test
	public void testListForms_nullScope_returnsAll()
	{
		String result = service.listForms(null);

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
	}

	// -------------------------------------------------------------------------
	// findForm
	// -------------------------------------------------------------------------

	@Test
	public void testFindForm_blankQuery_returnsAll()
	{
		String result = service.findForm("");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		// Either finds forms or says no forms
		assertTrue("Should contain results or no-match message: " + result,
			result.contains("Forms matching") || result.contains("No forms found"));
	}

	@Test
	public void testFindForm_nullQuery_returnsAll()
	{
		String result = service.findForm(null);

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
	}

	@Test
	public void testFindForm_nonExistentName_returnsNoMatch()
	{
		String result = service.findForm("xyzNonExistentForm99999");

		assertNotNull(result);
		assertTrue("Should report no match: " + result,
			result.contains("No forms found matching") || result.contains("0 found"));
	}

	// -------------------------------------------------------------------------
	// listRelations
	// -------------------------------------------------------------------------

	@Test
	public void testListRelations_all_returnsContent()
	{
		String result = service.listRelations("all");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue("Should contain relations or no-relations message: " + result,
			result.contains("Relations in") || result.contains("No relations found"));
	}

	@Test
	public void testListRelations_current_returnsContent()
	{
		String result = service.listRelations("current");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
	}

	// -------------------------------------------------------------------------
	// listValueLists
	// -------------------------------------------------------------------------

	@Test
	public void testListValueLists_all_returnsContent()
	{
		String result = service.listValueLists("all");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue("Should contain valuelists or no-valuelists message: " + result,
			result.contains("ValueLists") || result.contains("No valuelists found"));
	}

	@Test
	public void testListValueLists_current_returnsContent()
	{
		String result = service.listValueLists("current");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
	}

	// -------------------------------------------------------------------------
	// listStyles
	// -------------------------------------------------------------------------

	@Test
	public void testListStyles_all_returnsContent()
	{
		String result = service.listStyles("all");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue("Should contain styles header: " + result, result.contains("Styles:"));
	}

	@Test
	public void testListStyles_current_returnsContent()
	{
		String result = service.listStyles("current");

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue("Should contain styles header: " + result, result.contains("Styles:"));
	}

	// -------------------------------------------------------------------------
	// deleteForms - negative cases (don't delete real forms)
	// -------------------------------------------------------------------------

	@Test
	public void testDeleteForms_nonExistent_returnsNotFound()
	{
		String result = service.deleteForms(List.of("nonExistentFormXYZ_12345"));

		assertNotNull(result);
		assertTrue("Should report not found: " + result, result.contains("Not found"));
	}

	@Test
	public void testDeleteForms_emptyList_returnsMessage()
	{
		String result = service.deleteForms(Collections.emptyList());

		assertNotNull(result);
		assertTrue("Should report no forms specified: " + result,
			result.contains("No form") || result.contains("specified"));
	}

	@Test
	public void testDeleteForms_nullNames_skipsGracefully()
	{
		String result = service.deleteForms(Arrays.asList((String) null, "   "));

		assertNotNull(result);
		// Null/blank names should be skipped
		assertTrue("Should report no forms to delete or not found: " + result,
			result.contains("No form") || result.contains("specified"));
	}

	// -------------------------------------------------------------------------
	// deleteRelations - negative cases
	// -------------------------------------------------------------------------

	@Test
	public void testDeleteRelations_nonExistent_returnsNotFound()
	{
		String result = service.deleteRelations(List.of("nonExistentRelXYZ_12345"));

		assertNotNull(result);
		assertTrue("Should report not found: " + result, result.contains("Not found"));
	}

	@Test
	public void testDeleteRelations_emptyList_returnsMessage()
	{
		String result = service.deleteRelations(Collections.emptyList());

		assertNotNull(result);
		assertTrue("Should report no relations specified: " + result,
			result.contains("No relation") || result.contains("specified"));
	}

	// -------------------------------------------------------------------------
	// deleteValueLists - negative cases
	// -------------------------------------------------------------------------

	@Test
	public void testDeleteValueLists_nonExistent_returnsNotFound()
	{
		String result = service.deleteValueLists(List.of("nonExistentVLXYZ_12345"));

		assertNotNull(result);
		assertTrue("Should report not found: " + result, result.contains("Not found"));
	}

	@Test
	public void testDeleteValueLists_emptyList_returnsMessage()
	{
		String result = service.deleteValueLists(Collections.emptyList());

		assertNotNull(result);
		assertTrue("Should report no valuelists specified: " + result,
			result.contains("No valuelist") || result.contains("specified"));
	}

}

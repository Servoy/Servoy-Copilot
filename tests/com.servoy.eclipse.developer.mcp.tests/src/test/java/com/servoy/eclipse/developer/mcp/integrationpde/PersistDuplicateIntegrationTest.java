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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Solution;

public class PersistDuplicateIntegrationTest extends TestUtilitiesClassCopy {
	private static final String TEST_SOLUTION = "test_duplicate_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private PersistDuplicateService duplicateService;
	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public PersistDuplicateIntegrationTest() {
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
		duplicateService = new PersistDuplicateService();
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// Form duplication tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicateForm_success() throws Exception {
		String formName = "dupFormTest_" + System.currentTimeMillis();
		String newName = formName + "_dup";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, newName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + newName + "\""), "Should contain duplicated name: " + result);

		assertNotNull(activeProject.getEditingSolution().getForm(newName), "Duplicated form should exist");
	}

	@Test
	public void testDuplicateForm_viaTool() throws Exception {
		String formName = "dupFormTool_" + System.currentTimeMillis();
		String newName = formName + "_dup";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = devServer.duplicatePersist("form", formName, newName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Tool should indicate success: " + result);
	}

	@Test
	public void testDuplicateForm_autoName() throws Exception {
		String formName = "dupFormAuto_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, null, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + formName + "_copy\""),
				"Should auto-name with _copy suffix: " + result);

		assertNotNull(activeProject.getEditingSolution().getForm(formName + "_copy"), "Auto-named form should exist");
	}

	@Test
	public void testDuplicateForm_autoName_incrementsWhenCopyExists() throws Exception {
		String formName = "dupFormAutoInc_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(formName + "_copy", "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");
		assertNotNull(activeProject.getEditingSolution().getForm(formName + "_copy"),
				"Copy form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, null, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + formName + "_copy2\""),
				"Should auto-name with _copy2 suffix: " + result);
	}

	// -----------------------------------------------------------------------
	// Relation duplication tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicateRelation_success() throws Exception {
		String relName = "dupRel_" + System.currentTimeMillis();
		String newRelName = relName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/table1",
				"db:/mem/table2", 1);
		assertNotNull(rel, "Relation creation should succeed");
		activeProject.saveEditingSolutionNodes(new IPersist[] { rel }, true);

		String result = duplicateService.duplicatePersist("relation", relName, newRelName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + newRelName + "\""),
				"Should contain duplicated name: " + result);

		assertNotNull(solution.getRelation(newRelName), "Duplicated relation should exist");
	}

	@Test
	public void testDuplicateRelation_viaTool() throws Exception {
		String relName = "dupRelTool_" + System.currentTimeMillis();
		String newRelName = relName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/table1",
				"db:/mem/table2", 1);
		assertNotNull(rel, "Relation creation should succeed");
		activeProject.saveEditingSolutionNodes(new IPersist[] { rel }, true);

		String result = devServer.duplicatePersist("relation", relName, newRelName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// ValueList duplication tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicateValueList_success() throws Exception {
		String vlName = "dupVL_" + System.currentTimeMillis();
		String newVlName = vlName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull(vl, "ValueList creation should succeed");
		activeProject.saveEditingSolutionNodes(new IPersist[] { vl }, true);

		String result = duplicateService.duplicatePersist("valuelist", vlName, newVlName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + newVlName + "\""),
				"Should contain duplicated name: " + result);

		assertNotNull(solution.getValueList(newVlName), "Duplicated valuelist should exist");
	}

	@Test
	public void testDuplicateValueList_viaTool() throws Exception {
		String vlName = "dupVLTool_" + System.currentTimeMillis();
		String newVlName = vlName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull(vl, "ValueList creation should succeed");
		activeProject.saveEditingSolutionNodes(new IPersist[] { vl }, true);

		String result = devServer.duplicatePersist("valuelist", vlName, newVlName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// Media duplication tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicateMedia_success() throws Exception {
		String mediaName = "dupMedia_" + System.currentTimeMillis() + ".png";
		String newMediaName = "dupMedia_" + System.currentTimeMillis() + "_dup.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull(media, "Media creation should succeed");
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00, 0x01, 0x02, 0x03 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);

		String result = duplicateService.duplicatePersist("media", mediaName, newMediaName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Should indicate success: " + result);
		assertTrue(result.contains("\"duplicated\":\"" + newMediaName + "\""),
				"Should contain duplicated name: " + result);
	}

	@Test
	public void testDuplicateMedia_viaTool() throws Exception {
		String mediaName = "dupMediaTool_" + System.currentTimeMillis() + ".png";
		String newMediaName = "dupMediaTool_" + System.currentTimeMillis() + "_dup.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull(media, "Media creation should succeed");
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);

		String result = devServer.duplicatePersist("media", mediaName, newMediaName, null, null);

		assertNotNull(result);
		assertTrue(result.contains("\"status\":\"ok\""), "Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// Error handling tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicatePersist_formNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("form", "nonExistentForm_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testDuplicatePersist_relationNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("relation", "nonExistentRel_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testDuplicatePersist_valuelistNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("valuelist", "nonExistentVL_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testDuplicatePersist_mediaNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("media",
				"nonExistentMedia_" + System.currentTimeMillis() + ".png", "newName.png", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testDuplicatePersist_invalidName_returnsError() throws Exception {
		String formName = "dupFormInvalid_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, "invalid name with spaces", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("Invalid name"), "Should mention invalid name: " + result);
	}

	@Test
	public void testDuplicatePersist_duplicateName_returnsError() throws Exception {
		String formName = "dupFormDupName_" + System.currentTimeMillis();
		String existingName = formName + "_existing";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(existingName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");
		assertNotNull(activeProject.getEditingSolution().getForm(existingName),
				"Existing form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, existingName, null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("already exists"), "Should mention already exists: " + result);
	}

	@Test
	public void testDuplicatePersist_unsupportedType_returnsError() {
		String result = duplicateService.duplicatePersist("unsupported", "someName", "newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("Unsupported"), "Should mention unsupported: " + result);
	}

	@Test
	public void testDuplicatePersist_nullType_returnsError() {
		String result = duplicateService.duplicatePersist(null, "someName", "newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("required"), "Should mention required: " + result);
	}

	@Test
	public void testDuplicatePersist_nullName_returnsError() {
		String result = duplicateService.duplicatePersist("form", null, "newName", null, null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("required"), "Should mention required: " + result);
	}

	@Test
	public void testDuplicatePersist_solutionNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("form", "someForm", "newName", "nonExistentSolution_xyz",
				null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testDuplicatePersist_destinationSolutionNotFound_returnsError() throws Exception {
		String formName = "dupFormDestErr_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = duplicateService.duplicatePersist("form", formName, "newName", null,
				"nonExistentDestination_xyz");

		assertNotNull(result);
		assertTrue(result.startsWith("Error:"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

}

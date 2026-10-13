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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.PersistRenameService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.util.UUID;

/**
 * Integration tests for {@link PersistRenameService} - tests renaming of forms,
 * relations, valuelists, and scopes with a real Servoy workspace.
 *
 * These tests require a running Servoy application server and create a test
 * solution. They fail fast when the environment is not
 * available.
 */
public class RenamePersistIntegrationTest extends TestUtilitiesClassCopy {
	private static final String TEST_SOLUTION = "test_rename_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private PersistRenameService renameService;
	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public RenamePersistIntegrationTest() {
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
		renameService = new PersistRenameService();
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// Form rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameForm_success() throws Exception {
		String formName = "renameTestForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "Form creation should succeed");

		String result = renameService.renameForm(formName, newName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success");

		Form renamedForm = activeProject.getEditingSolution().getForm(newName);
		assertNotNull(renamedForm, "Form should exist with new name");
	}

	@Test
	public void testRenameForm_notFound_returnsError() throws Exception {
		String result = renameService.renameForm("nonExistentForm_XYZ_12345", "newName", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent form");
	}

	@Test
	public void testRenameForm_viaTool() throws Exception {
		String formName = "toolRenameForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		String result = devServer.renameFile(formName, newName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Tool should indicate success");
	}

	// -----------------------------------------------------------------------
	// Relation rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameRelation_notFound_returnsError() throws Exception {
		String result = renameService.renameRelation("nonExistentRelation_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent relation");
	}

	@Test
	public void testRenameRelation_success() throws Exception {
		String relName = "testRel_" + System.currentTimeMillis();
		String newRelName = relName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/table1",
				"db:/mem/table2", 1);
		assertNotNull(rel, "Relation creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { rel }, true);

		String result = renameService.renameRelation(relName, newRelName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		com.servoy.j2db.persistence.Relation renamed = solution.getRelation(newRelName);
		assertNotNull(renamed, "Relation should exist with new name");
	}

	@Test
	public void testRenameRelation_viaTool() throws Exception {
		String relName = "toolRel_" + System.currentTimeMillis();
		String newRelName = relName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/t1",
				"db:/mem/t2", 1);
		assertNotNull(rel, "Relation creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { rel }, true);

		String result = devServer.renameFile(relName, newRelName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// ValueList rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameValueList_notFound_returnsError() throws Exception {
		String result = renameService.renameValueList("nonExistentVL_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent valuelist");
	}

	@Test
	public void testRenameValueList_success() throws Exception {
		String vlName = "testVL_" + System.currentTimeMillis();
		String newVlName = vlName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull(vl, "ValueList creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { vl }, true);

		String result = renameService.renameValueList(vlName, newVlName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		com.servoy.j2db.persistence.ValueList renamed = solution.getValueList(newVlName);
		assertNotNull(renamed, "ValueList should exist with new name");
	}

	@Test
	public void testRenameValueList_viaTool() throws Exception {
		String vlName = "toolVL_" + System.currentTimeMillis();
		String newVlName = vlName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull(vl, "ValueList creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { vl }, true);

		String result = devServer.renameFile(vlName, newVlName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// Media rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMedia_notFound_returnsError() throws Exception {
		String result = renameService.renameMedia("nonExistentMedia_XYZ.png", "newName.png", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent media");
	}

	@Test
	public void testRenameMedia_success() throws Exception {
		String mediaName = "testMedia_" + System.currentTimeMillis() + ".png";
		String newMediaName = "testMedia_" + System.currentTimeMillis() + "_renamed.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull(media, "Media creation should succeed");
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media }, true);

		String result = renameService.renameMedia(mediaName, newMediaName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		com.servoy.j2db.persistence.Media renamed = solution.getMedia(newMediaName);
		assertNotNull(renamed, "Media should exist with new name");
	}

	@Test
	public void testRenameMedia_duplicateName_returnsError() throws Exception {
		String mediaName1 = "mediaDup1_" + System.currentTimeMillis() + ".png";
		String mediaName2 = "mediaDup2_" + System.currentTimeMillis() + ".png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media1 = solution.createNewMedia(validator, mediaName1);
		com.servoy.j2db.persistence.Media media2 = solution.createNewMedia(validator, mediaName2);
		assertNotNull(media1, "Media1 creation should succeed");
		assertNotNull(media2, "Media2 creation should succeed");
		media1.setPermMediaData(new byte[] { 0x01 });
		media2.setPermMediaData(new byte[] { 0x02 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media1, media2 }, true);

		String result = renameService.renameMedia(mediaName1, mediaName2, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("already exists"),
				"Should return error for duplicate: " + result);
	}

	@Test
	public void testRenameMedia_viaTool() throws Exception {
		String mediaName = "toolMedia_" + System.currentTimeMillis() + ".png";
		String newMediaName = "toolMedia_" + System.currentTimeMillis() + "_renamed.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull(media, "Media creation should succeed");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media }, true);

		String result = devServer.renameFile(mediaName, newMediaName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// Scope rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameScope_notFound_returnsError() throws Exception {
		String result = renameService.renameScope("nonExistentScope_XYZ", "newScope", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent scope");
	}

	@Test
	public void testRenameScope_success() throws Exception {
		String scopeName = "testScope_" + System.currentTimeMillis();
		IProject project = activeProject.getProject();
		IFile scopeFile = project.getFile(scopeName + ".js");
		scopeFile.create(new ByteArrayInputStream("// scope file".getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		assertTrue(scopeFile.exists(), "Scope file should exist");

		String newScopeName = scopeName + "_renamed";
		String result = renameService.renameScope(scopeName, newScopeName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully"), "Should indicate success: " + result);

		IFile oldFile = project.getFile(scopeName + ".js");
		IFile newFile = project.getFile(newScopeName + ".js");
		assertFalse(oldFile.exists(), "Old scope file should not exist");
		assertTrue(newFile.exists(), "New scope file should exist");
	}

	// -----------------------------------------------------------------------
	// Form rename with spec files tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameForm_withSpecFiles_renames_allFiles() throws Exception {
		String formName = "specTestForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "Form creation should succeed");

		IProject project = activeProject.getProject();

		// SVY-21171: the .spec.cy.js now lives outside the solution project tree in the
		// workspace-relative cy-form dir, so create/assert it via java.nio.file paths
		// resolved by the generator rather than as a project IFile.
		FormSpecGenerator specGenerator = new FormSpecGenerator();
		java.nio.file.Path oldSpecCyPath = specGenerator.getOldSpecFilePath(formName);
		String solutionName = activeProject.getSolution().getName();
		java.nio.file.Path newSpecCyPath = specGenerator.getSpecFilePath(newName, solutionName);
		java.nio.file.Files.createDirectories(oldSpecCyPath.getParent());
		java.nio.file.Files.deleteIfExists(newSpecCyPath);
		java.nio.file.Files.writeString(oldSpecCyPath, "describe('" + formName + "', () => {});",
				StandardCharsets.UTF_8);

		java.nio.file.Path oldSpecJsPath = specGenerator.getOldSetupFilePath(formName);
		java.nio.file.Path newSpecJsPath = specGenerator.getSetupFilePath(newName, solutionName);
		java.nio.file.Files.createDirectories(oldSpecJsPath.getParent());
		java.nio.file.Files.writeString(oldSpecJsPath, "function setUp() {}", StandardCharsets.UTF_8);

		assertTrue(java.nio.file.Files.exists(oldSpecCyPath), ".spec.cy.js should exist before rename");
		assertTrue(java.nio.file.Files.exists(oldSpecJsPath), ".spec.js should exist before rename");

		String result = renameService.renameForm(formName, newName, activeProject);
		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());

		assertFalse(java.nio.file.Files.exists(oldSpecCyPath), "Old .spec.cy.js should not exist");
		assertTrue(java.nio.file.Files.exists(newSpecCyPath), "New .spec.cy.js should exist");

		assertFalse(java.nio.file.Files.exists(oldSpecJsPath), "Old .spec.js should not exist");
		assertTrue(java.nio.file.Files.exists(newSpecJsPath), "New .spec.js should exist");

		Form renamedForm = activeProject.getEditingSolution().getForm(newName);
		assertNotNull(renamedForm, "Form should exist with new name");
		assertNull(activeProject.getEditingSolution().getForm(formName), "Form should not exist with old name");
	}

	@Test
	public void testRenameForm_withOnlyJsFile_renames_jsFile() throws Exception {
		String formName = "jsOnlyForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form creation should succeed");

		IProject project = activeProject.getProject();
		IFile jsFile = project.getFile("forms/" + formName + ".js");
		jsFile.create(new ByteArrayInputStream("// form js".getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());

		String result = renameService.renameForm(formName, newName, activeProject);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		assertNotNull(activeProject.getEditingSolution().getForm(newName), "Form should exist with new name");
	}

	// -----------------------------------------------------------------------
	// Menu rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMenu_notFound_returnsError() throws Exception {
		String result = renameService.renameMenu("nonExistentMenu_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent menu");
	}

	@Test
	public void testRenameMenu_success() throws Exception {
		String menuName = "testMenu_" + System.currentTimeMillis();
		String newMenuName = menuName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull(menu, "Menu creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu }, true);

		String result = renameService.renameMenu(menuName, newMenuName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		boolean foundRenamed = false;
		java.util.Iterator<Menu> iter = solution.getMenus(false);
		while (iter.hasNext()) {
			if (newMenuName.equals(iter.next().getName())) {
				foundRenamed = true;
				break;
			}
		}
		assertTrue(foundRenamed, "Menu should exist with new name");
	}

	// -----------------------------------------------------------------------
	// MenuItem rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMenuItem_notFound_returnsError() throws Exception {
		String result = renameService.renameMenuItem("nonExistentMenuItem_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent menuitem");
	}

	@Test
	public void testRenameMenuItem_success() throws Exception {
		String menuName = "menuForItem_" + System.currentTimeMillis();
		String itemName = "testItem_" + System.currentTimeMillis();
		String newItemName = itemName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull(menu, "Menu creation should succeed");

		MenuItem menuItem = menu.createNewMenuItem(itemName);
		assertNotNull(menuItem, "MenuItem creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu, menuItem }, true);

		String result = renameService.renameMenuItem(itemName, newItemName, activeProject);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);
	}

	@Test
	public void testRenameMenuItem_viaTool() throws Exception {
		String menuName = "menuForToolItem_" + System.currentTimeMillis();
		String itemName = "toolItem_" + System.currentTimeMillis();
		String newItemName = itemName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull(menu, "Menu creation should succeed");

		MenuItem menuItem = menu.createNewMenuItem(itemName);
		assertNotNull(menuItem, "MenuItem creation should succeed");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu, menuItem }, true);

		String result = devServer.renameFile(itemName, newItemName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Tool should indicate success: " + result);
	}

	// -----------------------------------------------------------------------
	// Solution rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameSolution_notFound_returnsError() {
		String result = renameService.renameSolution("nonExistentSolution_XYZ_99999", "newSolName");

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent solution");
	}

	@Test
	public void testRenameSolution_duplicateName_returnsError() {
		String result = renameService.renameSolution(TEST_SOLUTION, SERVOY_RESOURCES);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("already exists"),
				"Should return error for duplicate name");
	}

	// -----------------------------------------------------------------------
	// renameFile tool dispatch tests (via ServoyDevServer)
	// -----------------------------------------------------------------------

	@Test
	public void testRenameFile_unknownName_returnsError() {
		String result = devServer.renameFile("nonExistentArtifact_XYZ_unknown", "newName");

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should start with Error for unknown name");
		assertTrue(result.contains("not found") || result.contains("No artifact"), "Should mention not found");
	}

	@Test
	public void testRenameFile_sameName_returnsError() {
		String result = devServer.renameFile("myForm", "myForm");

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should start with Error for same name");
		assertTrue(result.contains("same"), "Should mention same");
	}

	@Test
	public void testRenameFile_nullOldName_returnsError() {
		String result = devServer.renameFile(null, "newName");

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should start with Error for null oldName");
		assertTrue(result.contains("required") || result.contains("oldName"), "Should mention required or oldName");
	}

	@Test
	public void testRenameFile_nullNewName_returnsError() {
		String result = devServer.renameFile("oldName", null);

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should start with Error for null newName");
		assertTrue(result.contains("required") || result.contains("newName"), "Should mention required or newName");
	}

	@Test
	public void testRenameFile_newNameWithPathSeparator_returnsError() {
		String result = devServer.renameFile("someForm", "bad/name");

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should start with Error for path in newName");
		assertTrue(result.contains("bare") || result.contains("path"), "Should mention bare name or path");
	}

	@Test
	public void testRenameSolution_success() throws Exception {
		String solName = "renameSolTest_" + System.currentTimeMillis();
		String newSolName = solName + "_renamed";

		ensureSolutionInWorkspace(solName, UUID.randomUUID().toString(), SERVOY_RESOURCES, null, null);

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		model.refreshServoyProjects();
		pumpEventsUntil(4000, () -> {
			ServoyProject solProject = model.getServoyProject(solName);
			assertNotNull(solProject, "Solution project should be created");
		});

		String result = renameService.renameSolution(solName, newSolName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"), "Should indicate success: " + result);

		IProject newProject[] = { null };
		pumpEventsUntil(2000, () -> {
			model.refreshServoyProjects();

			IProject oldProject = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			assertFalse(oldProject.exists(), "Old project should not exist after rename");

			newProject[0] = ResourcesPlugin.getWorkspace().getRoot().getProject(newSolName);
			assertTrue(newProject[0].exists(), "New project should exist after rename");
		});

		// cleanup
		try {
			newProject[0].delete(true, new NullProgressMonitor());
		} catch (Exception e) {
			// best effort
		}
	}

	@Test
	public void testRenameSolution_updatesModuleReferences() throws Exception {
		String moduleName = "renModRef_" + System.currentTimeMillis();
		String parentName = "renModParent_" + System.currentTimeMillis();
		String newModuleName = moduleName + "_renamed";

		ensureSolutionInWorkspace(moduleName, UUID.randomUUID().toString(), SERVOY_RESOURCES, null, null);
		ensureSolutionInWorkspace(parentName, UUID.randomUUID().toString(), SERVOY_RESOURCES, null, null);

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		model.refreshServoyProjects();
		Solution parentSol[] = { null };
		ServoyProject parentProject[] = { null };
		pumpEventsUntil(4000, () -> {
			ServoyProject moduleProject = model.getServoyProject(moduleName);
			parentProject[0] = model.getServoyProject(parentName);
			assertNotNull(moduleProject, "Module project should exist");
			assertNotNull(parentProject[0], "Parent project should exist");
			parentSol[0] = parentProject[0].getEditingSolution();
			assertNotNull(parentSol[0], "Parent editing solution should exist");
			assertNotNull(moduleProject.getEditingSolution(), "Module editing solution should exist");
		});

		parentSol[0].setModulesNames(moduleName);
		parentProject[0].saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { parentSol[0] }, true);
		
		waitForWorkspaceBuildJobs();

		String result = renameService.renameSolution(moduleName, newModuleName);
		assertNotNull(result);
		assertTrue(result.contains("successfully"), "Rename should succeed: " + result);
		
		model.refreshServoyProjects();
		
		pumpEventsUntil(1000, () -> {
			ServoyProject updatedParent = model.getServoyProject(parentName);
			if (updatedParent != null && updatedParent.getEditingSolution() != null) {
				String modules = updatedParent.getEditingSolution().getModulesNames();
				assertNotNull(modules, "Parent should still have modules");
				assertTrue(modules.contains(newModuleName), "Module reference should be updated to new name: " + modules);
				assertFalse(modules.contains(moduleName) && !modules.contains(newModuleName),
						"Old module name should not be in references: " + modules);
			}
		});

		// cleanup
		try {
			IProject renamedMod = ResourcesPlugin.getWorkspace().getRoot().getProject(newModuleName);
			if (renamedMod.exists())
				renamedMod.delete(true, new NullProgressMonitor());
			IProject par = ResourcesPlugin.getWorkspace().getRoot().getProject(parentName);
			if (par.exists())
				par.delete(true, new NullProgressMonitor());
		} catch (Exception e) {
			// best effort
		}
	}

	// -----------------------------------------------------------------------
	// New SVY-21179 integration tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameForm_toExistingFormName_returnsError() throws Exception {
		String formName1 = "dupForm1_" + System.currentTimeMillis();
		String formName2 = "dupForm2_" + System.currentTimeMillis();

		ServoyArtifactCreationService creationService = new ServoyArtifactCreationService();
		creationService.createForm(formName1, "css", 640, 480, null, null, null);
		creationService.createForm(formName2, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName1), "Form1 should exist");
		assertNotNull(activeProject.getEditingSolution().getForm(formName2), "Form2 should exist");

		String result = renameService.renameByName(formName1, formName2);

		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "Should return error when renaming to existing name: " + result);
	}

	@Test
	public void testRenameRelation_toExistingRelationName_returnsError() throws Exception {
		String relName1 = "dupRel1_" + System.currentTimeMillis();
		String relName2 = "dupRel2_" + System.currentTimeMillis();

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel1 = solution.createNewRelation(validator, relName1, "db:/mem/t1",
				"db:/mem/t2", 1);
		com.servoy.j2db.persistence.Relation rel2 = solution.createNewRelation(validator, relName2, "db:/mem/t1",
				"db:/mem/t2", 1);
		assertNotNull(rel1, "Relation1 should exist");
		assertNotNull(rel2, "Relation2 should exist");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { rel1, rel2 }, true);

		String result = renameService.renameByName(relName1, relName2);

		assertNotNull(result);
		assertTrue(result.startsWith("Error"),
				"Should return error when renaming to existing relation name: " + result);
	}

	@Test
	public void testRenameFile_rawFile_success() throws Exception {
		String fileName = "rawTestFile_" + System.currentTimeMillis() + ".json";
		String newFileName = "rawTestFileRenamed_" + System.currentTimeMillis() + ".json";

		org.eclipse.core.resources.IFile rawFile = activeProject.getProject().getFile(fileName);
		rawFile.create(new java.io.ByteArrayInputStream("{\"test\":true}".getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		assertTrue(rawFile.exists(), "Raw file should exist before rename");

		String result = renameService.renameByName(fileName, newFileName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Should succeed renaming raw file: " + result);
		assertFalse(activeProject.getProject().getFile(fileName).exists(), "Old file should not exist");
		assertTrue(activeProject.getProject().getFile(newFileName).exists(), "New file should exist");
	}

	@Test
	public void testRenameFile_workspaceRelativePath_success() throws Exception {
		String formName = "wsRelForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull(activeProject.getEditingSolution().getForm(formName), "Form should exist");

		String wsRelPath = activeProject.getProject().getName() + "/forms/" + formName + ".frm";
		String result = renameService.renameByName(wsRelPath, newName);

		assertNotNull(result);
		assertTrue(result.contains("successfully") || result.contains("Renamed"),
				"Should succeed with workspace-relative path: " + result);
		assertNotNull(activeProject.getEditingSolution().getForm(newName), "Form should exist with new name");
		assertNull(activeProject.getEditingSolution().getForm(formName), "Form should not exist with old name");
	}

}

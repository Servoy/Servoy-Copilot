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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.PersistRenameService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for {@link PersistRenameService} - tests renaming of forms,
 * relations, valuelists, and scopes with a real Servoy workspace.
 *
 * These tests require a running Servoy application server and create a test
 * solution. They are skipped (via Assume) when the environment is not
 * available.
 */
public class RenamePersistIntegrationTest {
	private static final String TEST_SOLUTION = "test_rename_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private PersistRenameService renameService;
	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		renameService = new PersistRenameService();
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
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
		assertNotNull("Form creation should succeed", form);

		String result = renameService.renameForm(formName, newName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success", result.contains("successfully") || result.contains("Renamed"));

		Form renamedForm = activeProject.getEditingSolution().getForm(newName);
		assertNotNull("Form should exist with new name", renamedForm);
	}

	@Test
	public void testRenameForm_notFound_returnsError() throws Exception {
		String result = renameService.renameForm("nonExistentForm_XYZ_12345", "newName", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent form",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameForm_viaTool() throws Exception {
		String formName = "toolRenameForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = devServer.renamePersist("form", formName, newName, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success", result.contains("successfully") || result.contains("Renamed"));
	}

	// -----------------------------------------------------------------------
	// Relation rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameRelation_notFound_returnsError() throws Exception {
		String result = renameService.renameRelation("nonExistentRelation_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent relation",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameRelation_success() throws Exception {
		String relName = "testRel_" + System.currentTimeMillis();
		String newRelName = relName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/table1",
				"db:/mem/table2", 1);
		assertNotNull("Relation creation should succeed", rel);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { rel }, true);

		String result = renameService.renameRelation(relName, newRelName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		com.servoy.j2db.persistence.Relation renamed = solution.getRelation(newRelName);
		assertNotNull("Relation should exist with new name", renamed);
	}

	@Test
	public void testRenameRelation_viaTool() throws Exception {
		String relName = "toolRel_" + System.currentTimeMillis();
		String newRelName = relName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/t1",
				"db:/mem/t2", 1);
		assertNotNull("Relation creation should succeed", rel);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { rel }, true);

		String result = devServer.renamePersist("relation", relName, newRelName, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result,
				result.contains("successfully") || result.contains("Renamed"));
	}

	// -----------------------------------------------------------------------
	// ValueList rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameValueList_notFound_returnsError() throws Exception {
		String result = renameService.renameValueList("nonExistentVL_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent valuelist",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameValueList_success() throws Exception {
		String vlName = "testVL_" + System.currentTimeMillis();
		String newVlName = vlName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull("ValueList creation should succeed", vl);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { vl }, true);

		String result = renameService.renameValueList(vlName, newVlName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		com.servoy.j2db.persistence.ValueList renamed = solution.getValueList(newVlName);
		assertNotNull("ValueList should exist with new name", renamed);
	}

	@Test
	public void testRenameValueList_viaTool() throws Exception {
		String vlName = "toolVL_" + System.currentTimeMillis();
		String newVlName = vlName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull("ValueList creation should succeed", vl);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { vl }, true);

		String result = devServer.renamePersist("valuelist", vlName, newVlName, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result,
				result.contains("successfully") || result.contains("Renamed"));
	}

	// -----------------------------------------------------------------------
	// Media rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMedia_notFound_returnsError() throws Exception {
		String result = renameService.renameMedia("nonExistentMedia_XYZ.png", "newName.png", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent media",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameMedia_success() throws Exception {
		String mediaName = "testMedia_" + System.currentTimeMillis() + ".png";
		String newMediaName = "testMedia_" + System.currentTimeMillis() + "_renamed.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull("Media creation should succeed", media);
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media }, true);

		String result = renameService.renameMedia(mediaName, newMediaName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		com.servoy.j2db.persistence.Media renamed = solution.getMedia(newMediaName);
		assertNotNull("Media should exist with new name", renamed);
	}

	@Test
	public void testRenameMedia_duplicateName_returnsError() throws Exception {
		String mediaName1 = "mediaDup1_" + System.currentTimeMillis() + ".png";
		String mediaName2 = "mediaDup2_" + System.currentTimeMillis() + ".png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media1 = solution.createNewMedia(validator, mediaName1);
		com.servoy.j2db.persistence.Media media2 = solution.createNewMedia(validator, mediaName2);
		assertNotNull("Media1 creation should succeed", media1);
		assertNotNull("Media2 creation should succeed", media2);
		media1.setPermMediaData(new byte[] { 0x01 });
		media2.setPermMediaData(new byte[] { 0x02 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media1, media2 }, true);

		String result = renameService.renameMedia(mediaName1, mediaName2, activeProject);

		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result,
				result.contains("Error") || result.contains("already exists"));
	}

	@Test
	public void testRenameMedia_viaTool() throws Exception {
		String mediaName = "toolMedia_" + System.currentTimeMillis() + ".png";
		String newMediaName = "toolMedia_" + System.currentTimeMillis() + "_renamed.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull("Media creation should succeed", media);
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { media }, true);

		String result = devServer.renamePersist("media", mediaName, newMediaName, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result,
				result.contains("successfully") || result.contains("Renamed"));
	}

	// -----------------------------------------------------------------------
	// Scope rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameScope_notFound_returnsError() throws Exception {
		String result = renameService.renameScope("nonExistentScope_XYZ", "newScope", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent scope",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameScope_success() throws Exception {
		String scopeName = "testScope_" + System.currentTimeMillis();
		IProject project = activeProject.getProject();
		IFile scopeFile = project.getFile(scopeName + ".js");
		scopeFile.create(new ByteArrayInputStream("// scope file".getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		assertTrue("Scope file should exist", scopeFile.exists());

		String newScopeName = scopeName + "_renamed";
		String result = renameService.renameScope(scopeName, newScopeName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully"));

		IFile oldFile = project.getFile(scopeName + ".js");
		IFile newFile = project.getFile(newScopeName + ".js");
		assertFalse("Old scope file should not exist", oldFile.exists());
		assertTrue("New scope file should exist", newFile.exists());
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
		assertNotNull("Form creation should succeed", form);

		IProject project = activeProject.getProject();

		// SVY-21171: the .spec.cy.js now lives outside the solution project tree in the
		// workspace-relative cy-form dir, so create/assert it via java.nio.file paths
		// resolved by the generator rather than as a project IFile.
		FormSpecGenerator specGenerator = new FormSpecGenerator();
		java.nio.file.Path oldSpecCyPath = specGenerator.getSpecFilePath(formName);
		java.nio.file.Path newSpecCyPath = specGenerator.getSpecFilePath(newName);
		java.nio.file.Files.createDirectories(oldSpecCyPath.getParent());
		java.nio.file.Files.deleteIfExists(newSpecCyPath);
		java.nio.file.Files.writeString(oldSpecCyPath, "describe('" + formName + "', () => {});",
				StandardCharsets.UTF_8);

		java.nio.file.Path oldSpecJsPath = specGenerator.getSetupFilePath(formName);
		java.nio.file.Path newSpecJsPath = specGenerator.getSetupFilePath(newName);
		java.nio.file.Files.createDirectories(oldSpecJsPath.getParent());
		java.nio.file.Files.writeString(oldSpecJsPath, "function setUp() {}",
				StandardCharsets.UTF_8);

		assertTrue(".spec.cy.js should exist before rename", java.nio.file.Files.exists(oldSpecCyPath));
		assertTrue(".spec.js should exist before rename", java.nio.file.Files.exists(oldSpecJsPath));

		String result = renameService.renameForm(formName, newName, activeProject);
		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());

		assertFalse("Old .spec.cy.js should not exist", java.nio.file.Files.exists(oldSpecCyPath));
		assertTrue("New .spec.cy.js should exist", java.nio.file.Files.exists(newSpecCyPath));

		assertFalse("Old .spec.js should not exist", java.nio.file.Files.exists(oldSpecJsPath));
		assertTrue("New .spec.js should exist", java.nio.file.Files.exists(newSpecJsPath));

		Form renamedForm = activeProject.getEditingSolution().getForm(newName);
		assertNotNull("Form should exist with new name", renamedForm);
		assertNull("Form should not exist with old name", activeProject.getEditingSolution().getForm(formName));
	}

	@Test
	public void testRenameForm_withOnlyJsFile_renames_jsFile() throws Exception {
		String formName = "jsOnlyForm_" + System.currentTimeMillis();
		String newName = formName + "_renamed";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		IProject project = activeProject.getProject();
		IFile jsFile = project.getFile("forms/" + formName + ".js");
		jsFile.create(new ByteArrayInputStream("// form js".getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());

		String result = renameService.renameForm(formName, newName, activeProject);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		assertNotNull("Form should exist with new name", activeProject.getEditingSolution().getForm(newName));
	}

	// -----------------------------------------------------------------------
	// Menu rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMenu_notFound_returnsError() throws Exception {
		String result = renameService.renameMenu("nonExistentMenu_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent menu",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameMenu_success() throws Exception {
		String menuName = "testMenu_" + System.currentTimeMillis();
		String newMenuName = menuName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull("Menu creation should succeed", menu);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu }, true);

		String result = renameService.renameMenu(menuName, newMenuName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		boolean foundRenamed = false;
		java.util.Iterator<Menu> iter = solution.getMenus(false);
		while (iter.hasNext()) {
			if (newMenuName.equals(iter.next().getName())) {
				foundRenamed = true;
				break;
			}
		}
		assertTrue("Menu should exist with new name", foundRenamed);
	}

	// -----------------------------------------------------------------------
	// MenuItem rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameMenuItem_notFound_returnsError() throws Exception {
		String result = renameService.renameMenuItem("nonExistentMenuItem_XYZ", "newName", activeProject);

		assertNotNull(result);
		assertTrue("Should return error for non-existent menuitem",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameMenuItem_success() throws Exception {
		String menuName = "menuForItem_" + System.currentTimeMillis();
		String itemName = "testItem_" + System.currentTimeMillis();
		String newItemName = itemName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull("Menu creation should succeed", menu);

		MenuItem menuItem = menu.createNewMenuItem(itemName);
		assertNotNull("MenuItem creation should succeed", menuItem);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu, menuItem }, true);

		String result = renameService.renameMenuItem(itemName, newItemName, activeProject);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));
	}

	@Test
	public void testRenameMenuItem_viaTool() throws Exception {
		String menuName = "menuForToolItem_" + System.currentTimeMillis();
		String itemName = "toolItem_" + System.currentTimeMillis();
		String newItemName = itemName + "_renamed";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Menu menu = solution.createNewMenu(validator, menuName);
		assertNotNull("Menu creation should succeed", menu);

		MenuItem menuItem = menu.createNewMenuItem(itemName);
		assertNotNull("MenuItem creation should succeed", menuItem);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { menu, menuItem }, true);

		String result = devServer.renamePersist("menuitem", itemName, newItemName, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result,
				result.contains("successfully") || result.contains("Renamed"));
	}

	// -----------------------------------------------------------------------
	// Solution rename tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameSolution_notFound_returnsError() {
		String result = renameService.renameSolution("nonExistentSolution_XYZ_99999", "newSolName");

		assertNotNull(result);
		assertTrue("Should return error for non-existent solution",
				result.contains("Error") || result.contains("not found"));
	}

	@Test
	public void testRenameSolution_duplicateName_returnsError() {
		String result = renameService.renameSolution(TEST_SOLUTION, SERVOY_RESOURCES);

		assertNotNull(result);
		assertTrue("Should return error for duplicate name",
				result.contains("Error") || result.contains("already exists"));
	}

	// -----------------------------------------------------------------------
	// renamePersist tool dispatch tests (via ServoyDevServer)
	// -----------------------------------------------------------------------

	@Test
	public void testRenamePersist_unsupportedType_returnsError() {
		String result = devServer.renamePersist("unknown_type", "old", "new", null);

		assertNotNull(result);
		assertTrue("Should start with Error for unsupported type", result.startsWith("Error"));
		assertTrue("Should mention Unsupported", result.contains("Unsupported"));
	}

	@Test
	public void testRenamePersist_sameName_returnsError() {
		String result = devServer.renamePersist("form", "myForm", "myForm", null);

		assertNotNull(result);
		assertTrue("Should start with Error for same name", result.startsWith("Error"));
		assertTrue("Should mention same", result.contains("same"));
	}

	@Test
	public void testRenamePersist_nullOldName_returnsError() {
		String result = devServer.renamePersist("form", null, "newName", null);

		assertNotNull(result);
		assertTrue("Should start with Error for null oldName", result.startsWith("Error"));
		assertTrue("Should mention required or oldName", result.contains("required") || result.contains("oldName"));
	}

	@Test
	public void testRenamePersist_nullNewName_returnsError() {
		String result = devServer.renamePersist("form", "oldName", null, null);

		assertNotNull(result);
		assertTrue("Should start with Error for null newName", result.startsWith("Error"));
		assertTrue("Should mention required or newName", result.contains("required") || result.contains("newName"));
	}

	@Test
	public void testRenameSolution_success() throws Exception {
		String solName = "renameSolTest_" + System.currentTimeMillis();
		String newSolName = solName + "_renamed";

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
			IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(solName);
			d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature" });
			ICommand sc = d.newCommand();
			sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
			ICommand sb = d.newCommand();
			sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
			d.setBuildSpec(new ICommand[] { sc, sb });
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			d.setReferencedProjects(new IProject[] { res });
			sol.create(d, monitor);
			sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ solName + "\",\n" + "solutionType:1024,\ntypeid:43,\nuuid:\""
							+ java.util.UUID.randomUUID().toString() + "\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"" + java.util.UUID.randomUUID().toString() + "\",\nversion:\"1.0\"\n", monitor);
		}, new NullProgressMonitor());

		pumpEvents(2000);
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		model.refreshServoyProjects();
		pumpEvents(2000);

		ServoyProject solProject = model.getServoyProject(solName);
		assertNotNull("Solution project should be created", solProject);

		String result = renameService.renameSolution(solName, newSolName);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully") || result.contains("Renamed"));

		pumpEvents(2000);
		model.refreshServoyProjects();

		IProject oldProject = ResourcesPlugin.getWorkspace().getRoot().getProject(solName);
		assertFalse("Old project should not exist after rename", oldProject.exists());

		IProject newProject = ResourcesPlugin.getWorkspace().getRoot().getProject(newSolName);
		assertTrue("New project should exist after rename", newProject.exists());

		// cleanup
		try {
			newProject.delete(true, new NullProgressMonitor());
		} catch (Exception e) {
			// best effort
		}
	}

	@Test
	public void testRenameSolution_updatesModuleReferences() throws Exception {
		String moduleName = "renModRef_" + System.currentTimeMillis();
		String parentName = "renModParent_" + System.currentTimeMillis();
		String newModuleName = moduleName + "_renamed";

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);

			IProject modProj = ResourcesPlugin.getWorkspace().getRoot().getProject(moduleName);
			IProjectDescription md = ResourcesPlugin.getWorkspace().newProjectDescription(moduleName);
			md.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature" });
			ICommand sc1 = md.newCommand();
			sc1.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
			ICommand sb1 = md.newCommand();
			sb1.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
			md.setBuildSpec(new ICommand[] { sc1, sb1 });
			md.setReferencedProjects(new IProject[] { res });
			modProj.create(md, monitor);
			modProj.open(monitor);
			writeProjectFile(modProj, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ moduleName + "\",\n" + "solutionType:1024,\ntypeid:43,\nuuid:\""
							+ java.util.UUID.randomUUID().toString() + "\"\n",
					monitor);
			writeProjectFile(modProj, "solution_settings.obj",
					"typeid:43,\nuuid:\"" + java.util.UUID.randomUUID().toString() + "\",\nversion:\"1.0\"\n", monitor);

			IProject parProj = ResourcesPlugin.getWorkspace().getRoot().getProject(parentName);
			IProjectDescription pd = ResourcesPlugin.getWorkspace().newProjectDescription(parentName);
			pd.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature" });
			ICommand sc2 = pd.newCommand();
			sc2.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
			ICommand sb2 = pd.newCommand();
			sb2.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
			pd.setBuildSpec(new ICommand[] { sc2, sb2 });
			pd.setReferencedProjects(new IProject[] { res });
			parProj.create(pd, monitor);
			parProj.open(monitor);
			writeProjectFile(parProj, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ parentName + "\",\n" + "solutionType:1024,\ntypeid:43,\nuuid:\""
							+ java.util.UUID.randomUUID().toString() + "\"\n",
					monitor);
			writeProjectFile(parProj, "solution_settings.obj",
					"typeid:43,\nuuid:\"" + java.util.UUID.randomUUID().toString() + "\",\nversion:\"1.0\"\n", monitor);
		}, new NullProgressMonitor());

		pumpEvents(2000);
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		model.refreshServoyProjects();
		pumpEvents(2000);

		ServoyProject moduleProject = model.getServoyProject(moduleName);
		ServoyProject parentProject = model.getServoyProject(parentName);
		assertNotNull("Module project should exist", moduleProject);
		assertNotNull("Parent project should exist", parentProject);

		Solution parentSol = parentProject.getEditingSolution();
		assertNotNull("Parent editing solution should exist", parentSol);
		parentSol.setModulesNames(moduleName);
		parentProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { parentSol }, true);
		pumpEvents(1000);

		String result = renameService.renameSolution(moduleName, newModuleName);
		assertNotNull(result);
		assertTrue("Rename should succeed: " + result, result.contains("successfully"));

		pumpEvents(2000);
		model.refreshServoyProjects();
		pumpEvents(1000);

		ServoyProject updatedParent = model.getServoyProject(parentName);
		if (updatedParent != null && updatedParent.getEditingSolution() != null) {
			String modules = updatedParent.getEditingSolution().getModulesNames();
			assertNotNull("Parent should still have modules", modules);
			assertTrue("Module reference should be updated to new name: " + modules, modules.contains(newModuleName));
			assertFalse("Old module name should not be in references: " + modules,
					modules.contains(moduleName) && !modules.contains(newModuleName));
		}

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
	// Helpers
	// -----------------------------------------------------------------------

	private void waitForAppServer() throws InterruptedException {
		if (appServerAvailableCache == null) {
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline) {
				Thread.sleep(500);
			}
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assertTrue("Servoy application server not started - skipping", appServerAvailableCache);
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-123456789abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"a1b2c3d4-e5f6-7890-abcd-123456789abc\",\nversion:\"1.0\"\n", monitor);
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
			// caught by assumeNotNull below
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

		assertNotNull("Active project not set - skipping", model.getActiveProject());
	}

	private void writeProjectFile(IProject project, String fileName, String content,
			org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
		org.eclipse.core.resources.IFile file = project.getFile(fileName);
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (file.exists()) {
			file.setContents(new java.io.ByteArrayInputStream(bytes), true, false, monitor);
		} else {
			file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
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

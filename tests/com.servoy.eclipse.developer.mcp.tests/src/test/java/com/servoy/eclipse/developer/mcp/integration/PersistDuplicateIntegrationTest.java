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
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.ICommand;
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
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class PersistDuplicateIntegrationTest {
	private static final String TEST_SOLUTION = "test_duplicate_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private PersistDuplicateService duplicateService;
	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		duplicateService = new PersistDuplicateService();
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	// -----------------------------------------------------------------------
	// Form duplication tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicateForm_success() throws Exception {
		String formName = "dupFormTest_" + System.currentTimeMillis();
		String newName = formName + "_dup";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = duplicateService.duplicatePersist("form", formName, newName, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should contain duplicated name: " + result, result.contains("\"duplicated\":\"" + newName + "\""));

		assertNotNull("Duplicated form should exist", activeProject.getEditingSolution().getForm(newName));
	}

	@Test
	public void testDuplicateForm_viaTool() throws Exception {
		String formName = "dupFormTool_" + System.currentTimeMillis();
		String newName = formName + "_dup";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = devServer.duplicatePersist("form", formName, newName, null, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result, result.contains("\"status\":\"ok\""));
	}

	@Test
	public void testDuplicateForm_autoName() throws Exception {
		String formName = "dupFormAuto_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = duplicateService.duplicatePersist("form", formName, null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should auto-name with _copy suffix: " + result,
				result.contains("\"duplicated\":\"" + formName + "_copy\""));

		assertNotNull("Auto-named form should exist", activeProject.getEditingSolution().getForm(formName + "_copy"));
	}

	@Test
	public void testDuplicateForm_autoName_incrementsWhenCopyExists() throws Exception {
		String formName = "dupFormAutoInc_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(formName + "_copy", "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));
		assertNotNull("Copy form creation should succeed",
				activeProject.getEditingSolution().getForm(formName + "_copy"));

		String result = duplicateService.duplicatePersist("form", formName, null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should auto-name with _copy2 suffix: " + result,
				result.contains("\"duplicated\":\"" + formName + "_copy2\""));
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
		assertNotNull("Relation creation should succeed", rel);
		activeProject.saveEditingSolutionNodes(new IPersist[] { rel }, true);

		String result = duplicateService.duplicatePersist("relation", relName, newRelName, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should contain duplicated name: " + result,
				result.contains("\"duplicated\":\"" + newRelName + "\""));

		assertNotNull("Duplicated relation should exist", solution.getRelation(newRelName));
	}

	@Test
	public void testDuplicateRelation_viaTool() throws Exception {
		String relName = "dupRelTool_" + System.currentTimeMillis();
		String newRelName = relName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Relation rel = solution.createNewRelation(validator, relName, "db:/mem/table1",
				"db:/mem/table2", 1);
		assertNotNull("Relation creation should succeed", rel);
		activeProject.saveEditingSolutionNodes(new IPersist[] { rel }, true);

		String result = devServer.duplicatePersist("relation", relName, newRelName, null, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result, result.contains("\"status\":\"ok\""));
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
		assertNotNull("ValueList creation should succeed", vl);
		activeProject.saveEditingSolutionNodes(new IPersist[] { vl }, true);

		String result = duplicateService.duplicatePersist("valuelist", vlName, newVlName, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should contain duplicated name: " + result,
				result.contains("\"duplicated\":\"" + newVlName + "\""));

		assertNotNull("Duplicated valuelist should exist", solution.getValueList(newVlName));
	}

	@Test
	public void testDuplicateValueList_viaTool() throws Exception {
		String vlName = "dupVLTool_" + System.currentTimeMillis();
		String newVlName = vlName + "_dup";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ValueList vl = solution.createNewValueList(validator, vlName);
		assertNotNull("ValueList creation should succeed", vl);
		activeProject.saveEditingSolutionNodes(new IPersist[] { vl }, true);

		String result = devServer.duplicatePersist("valuelist", vlName, newVlName, null, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result, result.contains("\"status\":\"ok\""));
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
		assertNotNull("Media creation should succeed", media);
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00, 0x01, 0x02, 0x03 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);

		String result = duplicateService.duplicatePersist("media", mediaName, newMediaName, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"status\":\"ok\""));
		assertTrue("Should contain duplicated name: " + result,
				result.contains("\"duplicated\":\"" + newMediaName + "\""));
	}

	@Test
	public void testDuplicateMedia_viaTool() throws Exception {
		String mediaName = "dupMediaTool_" + System.currentTimeMillis() + ".png";
		String newMediaName = "dupMediaTool_" + System.currentTimeMillis() + "_dup.png";

		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.Media media = solution.createNewMedia(validator, mediaName);
		assertNotNull("Media creation should succeed", media);
		media.setMimeType("image/png");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);

		String result = devServer.duplicatePersist("media", mediaName, newMediaName, null, null);

		assertNotNull(result);
		assertTrue("Tool should indicate success: " + result, result.contains("\"status\":\"ok\""));
	}

	// -----------------------------------------------------------------------
	// Error handling tests
	// -----------------------------------------------------------------------

	@Test
	public void testDuplicatePersist_formNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("form", "nonExistentForm_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testDuplicatePersist_relationNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("relation", "nonExistentRel_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testDuplicatePersist_valuelistNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("valuelist", "nonExistentVL_" + System.currentTimeMillis(),
				"newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testDuplicatePersist_mediaNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("media",
				"nonExistentMedia_" + System.currentTimeMillis() + ".png", "newName.png", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testDuplicatePersist_invalidName_returnsError() throws Exception {
		String formName = "dupFormInvalid_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = duplicateService.duplicatePersist("form", formName, "invalid name with spaces", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention invalid name: " + result, result.contains("Invalid name"));
	}

	@Test
	public void testDuplicatePersist_duplicateName_returnsError() throws Exception {
		String formName = "dupFormDupName_" + System.currentTimeMillis();
		String existingName = formName + "_existing";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(existingName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));
		assertNotNull("Existing form creation should succeed",
				activeProject.getEditingSolution().getForm(existingName));

		String result = duplicateService.duplicatePersist("form", formName, existingName, null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention already exists: " + result, result.contains("already exists"));
	}

	@Test
	public void testDuplicatePersist_unsupportedType_returnsError() {
		String result = duplicateService.duplicatePersist("unsupported", "someName", "newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention unsupported: " + result, result.contains("Unsupported"));
	}

	@Test
	public void testDuplicatePersist_nullType_returnsError() {
		String result = duplicateService.duplicatePersist(null, "someName", "newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention required: " + result, result.contains("required"));
	}

	@Test
	public void testDuplicatePersist_nullName_returnsError() {
		String result = duplicateService.duplicatePersist("form", null, "newName", null, null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention required: " + result, result.contains("required"));
	}

	@Test
	public void testDuplicatePersist_solutionNotFound_returnsError() {
		String result = duplicateService.duplicatePersist("form", "someForm", "newName", "nonExistentSolution_xyz",
				null);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testDuplicatePersist_destinationSolutionNotFound_returnsError() throws Exception {
		String formName = "dupFormDestErr_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		assertNotNull("Form creation should succeed", activeProject.getEditingSolution().getForm(formName));

		String result = duplicateService.duplicatePersist("form", formName, "newName", null,
				"nonExistentDestination_xyz");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.startsWith("Error:"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"b2c3d4e5-f6a7-8901-bcde-234567890abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"b2c3d4e5-f6a7-8901-bcde-234567890abc\",\nversion:\"1.0\"\n", monitor);
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

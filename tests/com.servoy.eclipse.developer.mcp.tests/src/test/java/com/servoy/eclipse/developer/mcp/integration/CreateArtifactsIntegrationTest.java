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
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class CreateArtifactsIntegrationTest {
	private static final String TEST_SOLUTION = "test_artifacts_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	@Test
	public void testCreateForm_success() {
		String formName = "testForm_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "css", "640", "480", null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form should exist in active solution", form);
	}

	@Test
	public void testCreateForm_responsive() {
		String formName = "testFormResp_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "responsive", "800", "600", null, null, null);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Responsive form should exist", form);
	}

	@Test
	public void testCreateForm_duplicate_returnsError() {
		String formName = "testFormDup_" + System.currentTimeMillis();

		devServer.createForm(formName, "css", "640", "480", null, null, null);
		String result = devServer.createForm(formName, "css", "640", "480", null, null, null);

		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result,
				result.contains("Error") || result.contains("already exists"));
	}

	@Test
	public void testCreateForm_withEvents() {
		String formName = "testFormEvents_" + System.currentTimeMillis();

		String result = devServer.createForm(formName, "css", "640", "480", null, null,
				"onLoad:initForm,onShow:refreshData");

		assertNotNull(result);
		assertTrue("Should indicate success: " + result,
				result.contains("Created") || result.contains("success") || result.contains(formName));

		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form with events should exist", form);
	}

	@Test
	public void testCreateForm_nullName_returnsError() {
		String result = devServer.createForm(null, "css", "640", "480", null, null, null);

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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"b1b2c3d4-e5f6-7890-abcd-223456789abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"b1b2c3d4-e5f6-7890-abcd-223456789abc\",\nversion:\"1.0\"\n", monitor);
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

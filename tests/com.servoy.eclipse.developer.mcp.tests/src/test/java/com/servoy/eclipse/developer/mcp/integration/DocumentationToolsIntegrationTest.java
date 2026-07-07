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

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFolder;
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
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class DocumentationToolsIntegrationTest {
	private static final String TEST_SOLUTION = "test_documentation_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String TEST_SCOPE_FILE = "globals.js";

	private static final long APP_SERVER_POLL_MS = 30_000;
	private static final long ACTIVATE_SETTLE_MS = 5000;

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
	public void testGetDocumentationForTypeMember_success() {
		String result = devServer.getDocumentationForTypeMember("JSApplication", "getUUID");
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation header", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testGetDocumentationForTypeMember_nullType_returnsError() {
		String result = devServer.getDocumentationForTypeMember(null, "getUUID");
		assertTrue("Should return error for null typeName", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_nullMember_returnsError() {
		String result = devServer.getDocumentationForTypeMember("application", null);
		assertTrue("Should return error for null memberName", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_unknownType_returnsError() {
		String result = devServer.getDocumentationForTypeMember("nonExistentType_xyz", "someMethod");
		assertTrue("Should return error for unknown type", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForTypeMember_unknownMember_returnsError() {
		String result = devServer.getDocumentationForTypeMember("application", "nonExistentMethod_xyz");
		assertTrue("Should return error for unknown member", result.startsWith("Error"));
	}

	@Test
	public void testGetAvailableMembersForType_success() {
		String result = devServer.getAvailableMembersForType("JSApplication", null);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain members header", result.contains("AVAILABLE MEMBERS FOR TYPE:"));
		assertTrue("Result should contain total found", result.contains("Total found:"));
	}

	@Test
	public void testGetAvailableMembersForType_withFilter() {
		String result = devServer.getAvailableMembersForType("JSApplication", "get.*");
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain filter info", result.contains("Filter: get.*"));
	}

	@Test
	public void testGetAvailableMembersForType_nullType_returnsError() {
		String result = devServer.getAvailableMembersForType(null, null);
		assertTrue("Should return error for null typeName", result.startsWith("Error"));
	}

	@Test
	public void testGetAvailableMembersForType_unknownType_returnsError() {
		String result = devServer.getAvailableMembersForType("nonExistentType_xyz", null);
		assertTrue("Should return error for unknown type", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_success() {
		String result = devServer.getDocumentationForIdentifiers("application", TEST_SCOPE_FILE);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation section", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_nullIdentifiers_returnsError() {
		String result = devServer.getDocumentationForIdentifiers(null, TEST_SCOPE_FILE);
		assertTrue("Should return error for null identifiers", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_nullFilePath_returnsError() {
		String result = devServer.getDocumentationForIdentifiers("application", null);
		assertTrue("Should return error for null filePath", result.startsWith("Error"));
	}

	@Test
	public void testGetDocumentationForIdentifiers_multipleIdentifiers() {
		String result = devServer.getDocumentationForIdentifiers("application,databaseManager", TEST_SCOPE_FILE);
		assertNotNull(result);
		assertFalse("Should not return an error: " + result, result.startsWith("Error"));
		assertTrue("Result should contain documentation section", result.contains("DOCUMENTATION FOR:"));
	}

	@Test
	public void testApplyDocumentations_nullFilePath_returnsError() {
		String result = devServer.applyDocumentations(null,
				"[{\"startLine\":0,\"endLine\":0,\"jsdoc\":\"/** test */\"}]");
		assertTrue("Should return error for null filePath", result.startsWith("Error"));
	}

	@Test
	public void testApplyDocumentations_nullItemsJson_returnsError() {
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, null);
		assertTrue("Should return error for null itemsJson", result.startsWith("Error"));
	}

	@Test
	public void testApplyDocumentations_emptyItemsJson_returnsError() {
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, "[]");
		assertNotNull(result);
		assertTrue("Should return error for empty items", result.contains("Error"));
	}

	@Test
	public void testApplyDocumentations_insertDocumentation() {
		String itemsJson = "[{\"startLine\":0,\"endLine\":0,\"startSentence\":\"\",\"endSentence\":\"\",\"jsdoc\":\"/**\\n * Test documentation\\n */\"}]";
		String result = devServer.applyDocumentations(TEST_SCOPE_FILE, itemsJson);
		assertNotNull(result);
		assertFalse("applyDocumentations should not return a top-level error: " + result, result.startsWith("Error"));
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"d1d2d3d4-e5f6-7890-abcd-423456789abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"d1d2d3d4-e5f6-7890-abcd-423456789abc\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
					monitor);

			IFolder scopesFolder = sol.getFolder("scopes");
			if (!scopesFolder.exists())
				scopesFolder.create(true, true, monitor);

			writeProjectFile(sol, "scopes/" + TEST_SCOPE_FILE,
					"/**\n * @type {String}\n */\nvar testVariable = 'hello';\n\n/**\n * @param {String} name\n * @return {String}\n */\nfunction testFunction(name) {\n\treturn 'Hello ' + name;\n}\n",
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

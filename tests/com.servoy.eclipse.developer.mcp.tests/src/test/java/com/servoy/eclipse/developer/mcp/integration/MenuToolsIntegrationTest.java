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

/**
 * Integration tests for the ServoyDevServer menu management tools (SVY-21114):
 * {@code listMenus}, {@code getMenuStructure}, {@code createMenu},
 * {@code createMenuItem}, {@code updateMenu}, {@code updateMenuItem},
 * {@code deleteMenu} and {@code deleteMenuItem}.
 * <p>
 * The backing {@link com.servoy.eclipse.developer.mcp.services.MenuService}
 * resolves the active solution via {@code ServoyModelManager} and manipulates
 * {@code Menu}/{@code MenuItem} persists, so a real active Servoy solution is
 * required. This test therefore runs as a JUnit Plug-in test inside a
 * PDE-launched Eclipse instance with a running Servoy application server.
 * <p>
 * The setUp mirrors the proven pattern in
 * {@link CreateArtifactsIntegrationTest}.
 */
public class MenuToolsIntegrationTest {
	private static final String TEST_SOLUTION = "test_menu_suite";
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

	// -----------------------------------------------------------------------
	// Happy-path lifecycle
	// -----------------------------------------------------------------------

	@Test
	public void testMenuLifecycle_createListStructureItemUpdateDelete() {
		String menuName = "menu_" + System.currentTimeMillis();
		String itemName = "item_" + System.currentTimeMillis();

		// createMenu
		String created = devServer.createMenu(menuName, "menu-style", "public");
		assertNotNull(created);
		assertTrue("Menu should be created: " + created, created.contains("created successfully"));

		// listMenus shows it
		String list = devServer.listMenus("all");
		assertNotNull(list);
		assertTrue("listMenus should contain the new menu: " + list, list.contains(menuName));

		// getMenuStructure returns it
		String structure = devServer.getMenuStructure(menuName);
		assertNotNull(structure);
		assertTrue("Structure should reference the menu: " + structure, structure.contains(menuName));
		assertTrue("Structure should show items section: " + structure, structure.contains("Items"));

		// createMenuItem under the menu
		String itemCreated = devServer.createMenuItem(menuName, itemName, null, "My Item", "tooltip", "item-style",
				"fas fa-home", "true");
		assertNotNull(itemCreated);
		assertTrue("Item should be created: " + itemCreated,
				itemCreated.contains("created in menu") && itemCreated.contains(itemName));

		// getMenuStructure now lists the item
		String structureWithItem = devServer.getMenuStructure(menuName);
		assertTrue("Structure should now list the item: " + structureWithItem, structureWithItem.contains(itemName));

		// updateMenu mutates styleClass
		String menuUpdated = devServer.updateMenu(menuName, "new-menu-style", "module_private");
		assertNotNull(menuUpdated);
		assertTrue("Menu should be updated: " + menuUpdated, menuUpdated.contains("updated successfully"));

		// updateMenuItem mutates properties
		String itemUpdated = devServer.updateMenuItem(menuName, itemName, "Updated Text", "new tooltip",
				"new-item-style", "fas fa-cog", "false");
		assertNotNull(itemUpdated);
		assertTrue("Item should be updated: " + itemUpdated, itemUpdated.contains("updated successfully"));

		// deleteMenuItem
		String itemDeleted = devServer.deleteMenuItem(menuName, itemName);
		assertNotNull(itemDeleted);
		assertTrue("Item should be deleted: " + itemDeleted, itemDeleted.contains("deleted from menu"));

		// item gone from structure
		String structureAfterItemDelete = devServer.getMenuStructure(menuName);
		assertFalse("Structure should no longer list the deleted item: " + structureAfterItemDelete,
				structureAfterItemDelete.contains(itemName));

		// deleteMenu
		String menuDeleted = devServer.deleteMenu(menuName);
		assertNotNull(menuDeleted);
		assertTrue("Menu should be deleted: " + menuDeleted, menuDeleted.contains("deleted successfully"));

		// menu removed from model
		String listAfter = devServer.listMenus("all");
		assertNotNull(listAfter);
		assertFalse("listMenus should no longer contain the deleted menu: " + listAfter, listAfter.contains(menuName));
	}

	@Test
	public void testCreateMenuItem_nestedUnderParent() {
		String menuName = "menuNest_" + System.currentTimeMillis();
		String parentItem = "parent_" + System.currentTimeMillis();
		String childItem = "child_" + System.currentTimeMillis();

		devServer.createMenu(menuName, null, null);
		String parentCreated = devServer.createMenuItem(menuName, parentItem, null, "Parent", null, null, null, null);
		assertTrue("Parent item should be created: " + parentCreated, parentCreated.contains("created in menu"));

		String childCreated = devServer.createMenuItem(menuName, childItem, parentItem, "Child", null, null, null,
				null);
		assertNotNull(childCreated);
		assertTrue("Child should be created under parent: " + childCreated,
				childCreated.contains("under parent") && childCreated.contains(parentItem));

		String structure = devServer.getMenuStructure(menuName);
		assertTrue("Structure should contain both items: " + structure,
				structure.contains(parentItem) && structure.contains(childItem));

		devServer.deleteMenu(menuName);
	}

	@Test
	public void testListMenus_currentScope() {
		String menuName = "menuCur_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);

		String list = devServer.listMenus("current");
		assertNotNull(list);
		assertTrue("current scope should include the active-solution menu: " + list, list.contains(menuName));

		devServer.deleteMenu(menuName);
	}

	// -----------------------------------------------------------------------
	// Error paths
	//
	// NOTE: pure null/blank parameter-guard validation (createMenu /
	// getMenuStructure /
	// createMenuItem / updateMenu / deleteMenu / deleteMenuItem with null args)
	// returns an
	// error BEFORE any workspace/model access and is already covered by the
	// plain-JUnit
	// ServoyDevServerTest (testCreateMenu_rejectsNullName,
	// testGetMenuStructure_rejectsNullName,
	// testCreateMenuItem_rejectsNullMenuName, etc.). To avoid duplication, the
	// cases below only
	// cover error paths that REQUIRE a live active solution: duplicate detection
	// and not-found
	// resolution of menus / items / parents.
	// -----------------------------------------------------------------------

	@Test
	public void testCreateMenu_duplicate_returnsError() {
		String menuName = "menuDup_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);
		String result = devServer.createMenu(menuName, null, null);
		assertNotNull(result);
		assertTrue("Should error on duplicate: " + result,
				result.contains("Error") && result.contains("already exists"));

		devServer.deleteMenu(menuName);
	}

	@Test
	public void testGetMenuStructure_notFound_returnsError() {
		String result = devServer.getMenuStructure("no_such_menu_" + System.currentTimeMillis());
		assertNotNull(result);
		assertTrue("Should error on unknown menu: " + result, result.contains("Error") && result.contains("not found"));
	}

	@Test
	public void testCreateMenuItem_blankItemName_returnsError() {
		String menuName = "menuBlankItem_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);

		String result = devServer.createMenuItem(menuName, "   ", null, null, null, null, null, null);
		assertNotNull(result);
		assertTrue("Should error on blank itemName: " + result,
				result.startsWith("Error") && result.contains("itemName"));

		devServer.deleteMenu(menuName);
	}

	@Test
	public void testCreateMenuItem_menuNotFound_returnsError() {
		String result = devServer.createMenuItem("no_such_menu_" + System.currentTimeMillis(), "item", null, null, null,
				null, null, null);
		assertNotNull(result);
		assertTrue("Should error when menu missing: " + result,
				result.contains("Error") && result.contains("not found"));
	}

	@Test
	public void testCreateMenuItem_parentNotFound_returnsError() {
		String menuName = "menuNoParent_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);

		String result = devServer.createMenuItem(menuName, "child", "missing_parent", null, null, null, null, null);
		assertNotNull(result);
		assertTrue("Should error when parent item missing: " + result,
				result.contains("Error") && result.contains("Parent menu item") && result.contains("not found"));

		devServer.deleteMenu(menuName);
	}

	@Test
	public void testUpdateMenu_notFound_returnsError() {
		String result = devServer.updateMenu("no_such_menu_" + System.currentTimeMillis(), "x", null);
		assertNotNull(result);
		assertTrue("Should error when menu missing: " + result,
				result.contains("Error") && result.contains("not found"));
	}

	@Test
	public void testUpdateMenuItem_itemNotFound_returnsError() {
		String menuName = "menuUpdItemMissing_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);

		String result = devServer.updateMenuItem(menuName, "missing_item", "text", null, null, null, null);
		assertNotNull(result);
		assertTrue("Should error when item missing: " + result,
				result.contains("Error") && result.contains("not found"));

		devServer.deleteMenu(menuName);
	}

	@Test
	public void testDeleteMenu_notFound_returnsError() {
		String result = devServer.deleteMenu("no_such_menu_" + System.currentTimeMillis());
		assertNotNull(result);
		assertTrue("Should error when menu missing: " + result,
				result.contains("Error") && result.contains("not found"));
	}

	@Test
	public void testDeleteMenuItem_itemNotFound_returnsError() {
		String menuName = "menuDelItemMissing_" + System.currentTimeMillis();
		devServer.createMenu(menuName, null, null);

		String result = devServer.deleteMenuItem(menuName, "missing_item");
		assertNotNull(result);
		assertTrue("Should error when item missing: " + result,
				result.contains("Error") && result.contains("not found"));

		devServer.deleteMenu(menuName);
	}

	// -----------------------------------------------------------------------
	// Environment bootstrap (mirrors CreateArtifactsIntegrationTest)
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
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"d2c3e4f5-a6b7-8901-bcde-334567890abc\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"d2c3e4f5-a6b7-8901-bcde-334567890abc\",\nversion:\"1.0\"\n", monitor);
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

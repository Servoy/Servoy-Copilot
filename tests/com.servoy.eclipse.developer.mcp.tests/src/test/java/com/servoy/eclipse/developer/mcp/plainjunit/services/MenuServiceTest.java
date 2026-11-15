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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.MenuService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit unit tests for {@link MenuService}'s <em>own</em> logic - the
 * no-active-solution guards, the "menu not found" / "menu item not found"
 * paths, {@code createMenu}/{@code createMenuItem} parameter validation, the
 * empty-list formatting of {@code listMenus}, and the pure recursive menu-item
 * helpers ({@code countMenuItems}, {@code appendMenuItems},
 * {@code findMenuItemRecursive}) - with the Servoy model boundary stubbed via
 * Mockito.
 * <p>
 * The service obtains the model through its {@code getServoyModel()} seam, which
 * this test overrides with a mock {@link IDeveloperServoyModel}. The Servoy core
 * the model exposes ({@link ServoyProject}, {@link Solution}, {@link Menu},
 * {@link MenuItem}) is only stubbed to return empty/null at the boundary so the
 * service's own branches are exercised - the core's behaviour itself is NOT
 * under test here (that belongs in servoy-eclipse). The actual create/update/
 * delete model mutation needs the real model and stays for the integration
 * tests.
 * </p>
 *
 * @since 2026.06
 */
class MenuServiceTest
{
	private static final String NO_ACTIVE = "Error: No active Servoy solution project found";

	/** A {@link MenuService} whose model seam returns the supplied mock. */
	private static MenuService serviceWith(IDeveloperServoyModel model)
	{
		return new MenuService()
		{
			@Override
			protected IDeveloperServoyModel getServoyModel()
			{
				return model;
			}
		};
	}

	/** A model with no active project at all. */
	private static IDeveloperServoyModel modelWithNoActiveProject()
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		when(model.getActiveProject()).thenReturn(null);
		return model;
	}

	/** A model with an active project whose editing solution is null. */
	private static IDeveloperServoyModel modelWithNullEditingSolution()
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyProject project = mock(ServoyProject.class);
		when(model.getActiveProject()).thenReturn(project);
		when(project.getEditingSolution()).thenReturn(null);
		return model;
	}

	/**
	 * A model with an active project + editing solution named "mySolution", no
	 * modules and no menus. Enough to drive findMenu -> null and the empty-list
	 * formatting.
	 */
	private static IDeveloperServoyModel modelWithEmptyActiveSolution()
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyProject project = mock(ServoyProject.class);
		Solution solution = mock(Solution.class);
		when(model.getActiveProject()).thenReturn(project);
		lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
		when(project.getEditingSolution()).thenReturn(solution);
		lenient().when(solution.getName()).thenReturn("mySolution");
		lenient().when(solution.getMenus(false)).thenReturn(Collections.<Menu> emptyList().iterator());
		return model;
	}

	@Nested
	class NoActiveSolutionGuards
	{
		@Test
		@DisplayName("listMenus reports no active solution when there is no active project")
		void listMenusNoActiveProject()
		{
			assertEquals(NO_ACTIVE, serviceWith(modelWithNoActiveProject()).listMenus(null));
		}

		@Test
		@DisplayName("listMenus reports no active solution when the editing solution is null")
		void listMenusNullEditingSolution()
		{
			assertEquals(NO_ACTIVE, serviceWith(modelWithNullEditingSolution()).listMenus(null));
		}

		@Test
		@DisplayName("createMenu reports no active solution when there is no active project")
		void createMenuNoActiveProject()
		{
			assertEquals(NO_ACTIVE, serviceWith(modelWithNoActiveProject()).createMenu("m", null, null));
		}

		@Test
		@DisplayName("deleteMenu reports no active solution when there is no active project")
		void deleteMenuNoActiveProject()
		{
			assertEquals(NO_ACTIVE, serviceWith(modelWithNoActiveProject()).deleteMenu("m"));
		}
	}

	@Nested
	class ParameterValidation
	{
		@Test
		@DisplayName("getMenuStructure requires a menuName")
		void getMenuStructureBlankName()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: menuName is required", service.getMenuStructure(null));
			assertEquals("Error: menuName is required", service.getMenuStructure("   "));
		}

		@Test
		@DisplayName("createMenu requires a name")
		void createMenuBlankName()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu name is required", service.createMenu(null, null, null));
			assertEquals("Error: Menu name is required", service.createMenu("  ", null, null));
		}

		@Test
		@DisplayName("createMenuItem requires menuName and itemName")
		void createMenuItemBlankNames()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: menuName is required", service.createMenuItem(null, "it", null, null, null, null, null, null));
			assertEquals("Error: itemName is required", service.createMenuItem("m", null, null, null, null, null, null, null));
			assertEquals("Error: itemName is required", service.createMenuItem("m", "  ", null, null, null, null, null, null));
		}

		@Test
		@DisplayName("updateMenu requires a name")
		void updateMenuBlankName()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu name is required", service.updateMenu(null, null, null));
			assertEquals("Error: Menu name is required", service.updateMenu("   ", null, null));
		}

		@Test
		@DisplayName("updateMenuItem requires menuName and itemName")
		void updateMenuItemBlankNames()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: menuName is required", service.updateMenuItem(null, "it", null, null, null, null, null));
			assertEquals("Error: itemName is required", service.updateMenuItem("m", "  ", null, null, null, null, null));
		}

		@Test
		@DisplayName("deleteMenu requires a name")
		void deleteMenuBlankName()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu name is required", service.deleteMenu(null));
			assertEquals("Error: Menu name is required", service.deleteMenu("   "));
		}

		@Test
		@DisplayName("deleteMenuItem requires menuName and itemName")
		void deleteMenuItemBlankNames()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: menuName is required", service.deleteMenuItem(null, "it"));
			assertEquals("Error: itemName is required", service.deleteMenuItem("m", "  "));
		}
	}

	@Nested
	class MenuNotFound
	{
		@Test
		@DisplayName("getMenuStructure reports a menu that is not in the model as not found")
		void getMenuStructureNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found", service.getMenuStructure("missing"));
		}

		@Test
		@DisplayName("createMenuItem reports the menu as not found before touching the project")
		void createMenuItemMenuNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found",
				service.createMenuItem("missing", "it", null, null, null, null, null, null));
		}

		@Test
		@DisplayName("updateMenu reports the menu as not found")
		void updateMenuNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found", service.updateMenu("missing", "sc", null));
		}

		@Test
		@DisplayName("updateMenuItem reports the menu as not found")
		void updateMenuItemMenuNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found",
				service.updateMenuItem("missing", "it", "t", null, null, null, null));
		}

		@Test
		@DisplayName("deleteMenu reports the menu as not found")
		void deleteMenuNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found", service.deleteMenu("missing"));
		}

		@Test
		@DisplayName("deleteMenuItem reports the menu as not found")
		void deleteMenuItemMenuNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("Error: Menu 'missing' not found", service.deleteMenuItem("missing", "it"));
		}
	}

	@Nested
	class MenuItemNotFound
	{
		/**
		 * A model whose active solution contains a single menu named
		 * "mainMenu" with no items. Enough so findMenu succeeds but
		 * findMenuItemRecursive returns null.
		 */
		private IDeveloperServoyModel modelWithEmptyMenu(String menuName)
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			Menu menu = mock(Menu.class);
			when(model.getActiveProject()).thenReturn(project);
			when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
			when(project.getEditingSolution()).thenReturn(solution);
			lenient().when(solution.getName()).thenReturn("mySolution");
			when(solution.getMenus(false)).thenReturn(Arrays.asList(menu).iterator());
			lenient().when(menu.getName()).thenReturn(menuName);
			lenient().when(menu.getAllObjects()).thenReturn(Collections.<IPersist> emptyList().iterator());
			return model;
		}

		@Test
		@DisplayName("updateMenuItem reports the item as not found when the menu has no such item")
		void updateMenuItemItemNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyMenu("mainMenu"));
			assertEquals("Error: MenuItem 'ghost' not found in menu 'mainMenu'",
				service.updateMenuItem("mainMenu", "ghost", "t", null, null, null, null));
		}

		@Test
		@DisplayName("deleteMenuItem reports the item as not found when the menu has no such item")
		void deleteMenuItemItemNotFound()
		{
			MenuService service = serviceWith(modelWithEmptyMenu("mainMenu"));
			assertEquals("Error: MenuItem 'ghost' not found in menu 'mainMenu'",
				service.deleteMenuItem("mainMenu", "ghost"));
		}
	}

	@Nested
	class ListMenusFormatting
	{
		@Test
		@DisplayName("listMenus reports no menus for an empty solution, scope-aware")
		void emptyAcrossModules()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("No menus found in the active solution and modules", service.listMenus(null));
		}

		@Test
		@DisplayName("listMenus with scope 'current' names the active solution in the empty message")
		void emptyCurrentScope()
		{
			MenuService service = serviceWith(modelWithEmptyActiveSolution());
			assertEquals("No menus found in 'mySolution'", service.listMenus("current"));
		}

		@Test
		@DisplayName("listMenus formats a menu with its item count and styleClass")
		void listsOneMenu()
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			Menu menu = mock(Menu.class);
			when(model.getActiveProject()).thenReturn(project);
			when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getName()).thenReturn("mySolution");
			when(solution.getMenus(false)).thenReturn(Arrays.asList(menu).iterator());
			when(menu.getName()).thenReturn("mainMenu");
			when(menu.getStyleClass()).thenReturn("nav");
			// one top-level item, no nesting
			MenuItem item = mock(MenuItem.class);
			when(menu.getAllObjects()).thenReturn(Arrays.<IPersist> asList(item).iterator());
			when(item.getAllObjects()).thenReturn(Collections.<IPersist> emptyList().iterator());

			String result = serviceWith(model).listMenus("current");
			assertTrue(result.startsWith("Menus in 'mySolution' (1):"), "got: " + result);
			assertTrue(result.contains("1. mainMenu"), "got: " + result);
			assertTrue(result.contains("(styleClass: nav)"), "got: " + result);
			assertTrue(result.contains("- 1 item\n"), "singular item label; got: " + result);
		}
	}

	// ---- pure recursive helpers (reflection, no workbench) ----------------

	@Nested
	class RecursiveHelpers
	{
		private final MenuService service = new MenuService();

		/** Builds a MenuItem mock named {@code name} whose children are {@code children}. */
		private MenuItem itemNamed(String name, IPersist... children)
		{
			MenuItem item = mock(MenuItem.class);
			final List<IPersist> list = new ArrayList<>(Arrays.asList(children));
			lenient().when(item.getName()).thenReturn(name);
			// getAllObjects is called repeatedly during recursion, so return a fresh iterator each time
			lenient().when(item.getAllObjects()).thenAnswer(inv -> list.iterator());
			return item;
		}

		private ISupportChilds parentWith(IPersist... children)
		{
			ISupportChilds parent = mock(ISupportChilds.class);
			final List<IPersist> list = new ArrayList<>(Arrays.asList(children));
			// getAllObjects is called repeatedly during recursion, so return a fresh iterator each time
			lenient().when(parent.getAllObjects()).thenAnswer(inv -> list.iterator());
			return parent;
		}

		private int countMenuItems(ISupportChilds parent) throws Exception
		{
			Method m = MenuService.class.getDeclaredMethod("countMenuItems", ISupportChilds.class);
			m.setAccessible(true);
			return (int)m.invoke(service, parent);
		}

		private MenuItem findMenuItemRecursive(ISupportChilds parent, String name) throws Exception
		{
			Method m = MenuService.class.getDeclaredMethod("findMenuItemRecursive", ISupportChilds.class, String.class);
			m.setAccessible(true);
			return (MenuItem)m.invoke(service, parent, name);
		}

		private String appendMenuItems(ISupportChilds parent, String indent) throws Exception
		{
			Method m = MenuService.class.getDeclaredMethod("appendMenuItems", ISupportChilds.class, StringBuilder.class, String.class);
			m.setAccessible(true);
			StringBuilder sb = new StringBuilder();
			m.invoke(service, parent, sb, indent);
			return sb.toString();
		}

		@Test
		@DisplayName("countMenuItems counts nested menu items across the whole tree")
		void countsNested() throws Exception
		{
			// mainMenu
			//   a
			//     a1
			//   b
			MenuItem a1 = itemNamed("a1");
			MenuItem a = itemNamed("a", a1);
			MenuItem b = itemNamed("b");
			ISupportChilds menu = parentWith(a, b);
			assertEquals(3, countMenuItems(menu));
		}

		@Test
		@DisplayName("countMenuItems ignores non-MenuItem children")
		void countsIgnoringOtherPersists() throws Exception
		{
			IPersist other = mock(IPersist.class);
			MenuItem a = itemNamed("a");
			ISupportChilds menu = parentWith(other, a);
			assertEquals(1, countMenuItems(menu));
		}

		@Test
		@DisplayName("countMenuItems returns 0 for an empty parent")
		void countsEmpty() throws Exception
		{
			assertEquals(0, countMenuItems(parentWith()));
		}

		@Test
		@DisplayName("findMenuItemRecursive finds a deeply nested item by name")
		void findsNested() throws Exception
		{
			MenuItem deep = itemNamed("deep");
			MenuItem mid = itemNamed("mid", deep);
			MenuItem top = itemNamed("top", mid);
			ISupportChilds menu = parentWith(top);
			assertEquals("deep", findMenuItemRecursive(menu, "deep").getName());
			assertEquals("mid", findMenuItemRecursive(menu, "mid").getName());
			assertEquals("top", findMenuItemRecursive(menu, "top").getName());
		}

		@Test
		@DisplayName("findMenuItemRecursive returns null when nothing matches")
		void findsNothing() throws Exception
		{
			MenuItem a = itemNamed("a");
			ISupportChilds menu = parentWith(a);
			assertTrue(findMenuItemRecursive(menu, "zzz") == null);
		}

		@Test
		@DisplayName("appendMenuItems renders nesting with indentation and the DISABLED/text markers")
		void appendsFormatted() throws Exception
		{
			MenuItem child = mock(MenuItem.class);
			lenient().when(child.getName()).thenReturn("child");
			lenient().when(child.getText()).thenReturn(null);
			lenient().when(child.getEnabled()).thenReturn(true);
			lenient().when(child.getAllObjects()).thenReturn(Collections.<IPersist> emptyList().iterator());

			MenuItem parentItem = mock(MenuItem.class);
			lenient().when(parentItem.getName()).thenReturn("file");
			lenient().when(parentItem.getText()).thenReturn("File");
			lenient().when(parentItem.getEnabled()).thenReturn(false);
			lenient().when(parentItem.getAllObjects()).thenReturn(Arrays.<IPersist> asList((IPersist)child).iterator());

			ISupportChilds menu = parentWith(parentItem);
			String out = appendMenuItems(menu, "  ");

			assertTrue(out.contains("  - file (text: \"File\") [DISABLED]\n"), "got:\n" + out);
			// child is indented two more spaces and enabled (no DISABLED marker)
			assertTrue(out.contains("    - child\n"), "got:\n" + out);
			assertFalse(out.contains("- child") && out.substring(out.indexOf("- child")).startsWith("- child [DISABLED]"),
				"enabled child should not be marked disabled; got:\n" + out);
		}
	}
}

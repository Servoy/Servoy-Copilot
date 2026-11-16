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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.MenuService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.RepositoryException;
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

	// ---- shared builders for the model-side create/update/delete/list/structure branches ----

	/**
	 * Holds the mocks of a single-solution, single-menu model so a test can both
	 * drive the service and verify the service's decisions on the exact mocks.
	 */
	private static class Fixture
	{
		final IDeveloperServoyModel model;
		final ServoyProject project;
		final Solution solution;

		Fixture(IDeveloperServoyModel model, ServoyProject project, Solution solution)
		{
			this.model = model;
			this.project = project;
			this.solution = solution;
		}
	}

	/**
	 * A model with an active project + editing solution named "mySolution" and no
	 * modules, whose {@code getMenus(false)} yields the supplied menus (a fresh
	 * iterator each call, since the service iterates several times). The name
	 * validator and {@code saveEditingSolutionNodes} are stubbed to succeed.
	 */
	private static Fixture fixtureWithMenus(Menu... menus)
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyProject project = mock(ServoyProject.class);
		Solution solution = mock(Solution.class);
		when(model.getActiveProject()).thenReturn(project);
		lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
		lenient().when(model.getNameValidator()).thenReturn(mock(IValidateName.class));
		when(project.getEditingSolution()).thenReturn(solution);
		lenient().when(solution.getName()).thenReturn("mySolution");
		final List<Menu> list = new ArrayList<>(Arrays.asList(menus));
		lenient().when(solution.getMenus(false)).thenAnswer(inv -> list.iterator());
		return new Fixture(model, project, solution);
	}

	/** A Menu mock named {@code name} with the given top-level children. */
	private static Menu menuNamed(String name, IPersist... children)
	{
		Menu menu = mock(Menu.class);
		final List<IPersist> list = new ArrayList<>(Arrays.asList(children));
		lenient().when(menu.getName()).thenReturn(name);
		lenient().when(menu.getAllObjects()).thenAnswer(inv -> list.iterator());
		return menu;
	}

	/**
	 * A MenuItem mock named {@code name} with the given children. The item is also
	 * an {@link ISupportChilds} (its own declared supertype) so the service's
	 * {@code (ISupportChilds) item.getParent()} chains never hit a ClassCastException;
	 * {@code getParent()} returns the supplied parent.
	 */
	private static MenuItem itemNamed(String name, ISupportChilds parent, IPersist... children)
	{
		MenuItem item = mock(MenuItem.class);
		final List<IPersist> list = new ArrayList<>(Arrays.asList(children));
		lenient().when(item.getName()).thenReturn(name);
		lenient().when(item.getAllObjects()).thenAnswer(inv -> list.iterator());
		lenient().when(item.getParent()).thenReturn(parent);
		return item;
	}

	@Nested
	class CreateMenuModelSide
	{
		@Test
		@DisplayName("createMenu refuses a name that already exists, before mutating anything")
		void alreadyExists() throws Exception
		{
			Fixture f = fixtureWithMenus(menuNamed("dup"));
			String result = serviceWith(f.model).createMenu("dup", null, null);
			assertEquals("Error: Menu 'dup' already exists", result);
			verify(f.solution, never()).createNewMenu(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
		}

		@Test
		@DisplayName("createMenu creates the menu, applies styleClass + module_private encapsulation and saves")
		void createsWithEncapsulationAndStyle() throws Exception
		{
			Fixture f = fixtureWithMenus();
			Menu created = mock(Menu.class);
			when(f.solution.createNewMenu(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("m"))).thenReturn(created);

			String result = serviceWith(f.model).createMenu("m", "nav", "module_private");
			assertEquals("Menu 'm' created successfully.", result);
			verify(created).setStyleClass("nav");
			verify(created).setEncapsulation(2);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { created }, true);
		}

		@Test
		@DisplayName("createMenu with a non-module encapsulation sets public (0) and no styleClass")
		void createsPublicNoStyle() throws Exception
		{
			Fixture f = fixtureWithMenus();
			Menu created = mock(Menu.class);
			when(f.solution.createNewMenu(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("m"))).thenReturn(created);

			String result = serviceWith(f.model).createMenu("m", "  ", "public");
			assertEquals("Menu 'm' created successfully.", result);
			verify(created, never()).setStyleClass(org.mockito.ArgumentMatchers.anyString());
			verify(created).setEncapsulation(0);
		}

		@Test
		@DisplayName("createMenu surfaces a RepositoryException from the model as an Error message")
		void repositoryException() throws Exception
		{
			Fixture f = fixtureWithMenus();
			when(f.solution.createNewMenu(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("m")))
				.thenThrow(new RepositoryException("boom"));
			String result = serviceWith(f.model).createMenu("m", null, null);
			assertEquals("Error: boom", result);
		}
	}

	@Nested
	class CreateMenuItemModelSide
	{
		@Test
		@DisplayName("createMenuItem on a top-level menu creates the item, applies properties and saves")
		void topLevelWithProperties() throws Exception
		{
			Menu menu = menuNamed("mainMenu");
			Fixture f = fixtureWithMenus(menu);
			MenuItem created = mock(MenuItem.class);
			when(menu.createNewMenuItem("it")).thenReturn(created);

			String result = serviceWith(f.model).createMenuItem("mainMenu", "it", null, "Text", "Tip", "sc", "icon", "false");
			assertEquals("MenuItem 'it' created in menu 'mainMenu'.", result);
			verify(created).setText("Text");
			verify(created).setToolTipText("Tip");
			verify(created).setStyleClass("sc");
			verify(created).setIconStyleClass("icon");
			verify(created).setEnabled(false);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { menu }, true);
		}

		@Test
		@DisplayName("createMenuItem under a named parent item creates it on the parent and notes the parent")
		void underParent() throws Exception
		{
			MenuItem parentItem = itemNamed("file", null);
			Menu menu = menuNamed("mainMenu", parentItem);
			Fixture f = fixtureWithMenus(menu);
			MenuItem created = mock(MenuItem.class);
			when(parentItem.createNewMenuItem("child")).thenReturn(created);

			String result = serviceWith(f.model).createMenuItem("mainMenu", "child", "file", null, null, null, null, null);
			assertEquals("MenuItem 'child' created in menu 'mainMenu' under parent 'file'.", result);
			verify(parentItem).createNewMenuItem("child");
			verify(menu, never()).createNewMenuItem(org.mockito.ArgumentMatchers.anyString());
		}

		@Test
		@DisplayName("createMenuItem reports a missing parent item without creating anything")
		void parentNotFound() throws Exception
		{
			Menu menu = menuNamed("mainMenu");
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).createMenuItem("mainMenu", "child", "ghostParent", null, null, null, null, null);
			assertEquals("Error: Parent menu item 'ghostParent' not found in menu 'mainMenu'", result);
			verify(menu, never()).createNewMenuItem(org.mockito.ArgumentMatchers.anyString());
		}

		@Test
		@DisplayName("createMenuItem surfaces a RepositoryException as an Error message")
		void repositoryException() throws Exception
		{
			Menu menu = menuNamed("mainMenu");
			Fixture f = fixtureWithMenus(menu);
			when(menu.createNewMenuItem("it")).thenThrow(new RepositoryException("nope"));
			String result = serviceWith(f.model).createMenuItem("mainMenu", "it", null, null, null, null, null, null);
			assertEquals("Error: nope", result);
		}
	}

	@Nested
	class UpdateMenuModelSide
	{
		@Test
		@DisplayName("updateMenu with no properties reports nothing to update and does not save")
		void nothingToUpdate() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenu("m", null, null);
			assertEquals("No properties to update. Provide styleClass or encapsulation.", result);
			verify(f.project, never()).saveEditingSolutionNodes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("updateMenu sets a blank styleClass to null and saves")
		void blankStyleClassBecomesNull() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenu("m", "  ", null);
			assertEquals("Menu 'm' updated successfully.", result);
			verify(menu).setStyleClass(null);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { menu }, true);
		}

		@Test
		@DisplayName("updateMenu applies styleClass + public encapsulation and saves")
		void styleAndEncapsulation() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenu("m", "nav", "public");
			assertEquals("Menu 'm' updated successfully.", result);
			verify(menu).setStyleClass("nav");
			verify(menu).setEncapsulation(0);
		}

		@Test
		@DisplayName("updateMenu surfaces a RepositoryException on save as an Error message")
		void repositoryException() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			doThrow(new RepositoryException("saveFail")).when(f.project)
				.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			String result = serviceWith(f.model).updateMenu("m", "nav", null);
			assertEquals("Error: saveFail", result);
		}
	}

	@Nested
	class UpdateMenuItemModelSide
	{
		@Test
		@DisplayName("updateMenuItem with no properties reports nothing to update and does not save")
		void nothingToUpdate() throws Exception
		{
			MenuItem item = itemNamed("it", null);
			Menu menu = menuNamed("m", item);
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenuItem("m", "it", null, null, null, null, null);
			assertEquals("No properties to update.", result);
			verify(f.project, never()).saveEditingSolutionNodes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("updateMenuItem applies all properties (blank text/tooltip become null) and saves")
		void appliesProperties() throws Exception
		{
			MenuItem item = itemNamed("it", null);
			Menu menu = menuNamed("m", item);
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenuItem("m", "it", "  ", "Tip", "sc", "icon", "true");
			assertEquals("MenuItem 'it' in menu 'm' updated successfully.", result);
			verify(item).setText(null);
			verify(item).setToolTipText("Tip");
			verify(item).setStyleClass("sc");
			verify(item).setIconStyleClass("icon");
			verify(item).setEnabled(true);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { menu }, true);
		}

		@Test
		@DisplayName("updateMenuItem finds a nested item and updates it")
		void updatesNestedItem() throws Exception
		{
			MenuItem deep = itemNamed("deep", null);
			MenuItem top = itemNamed("top", null, deep);
			Menu menu = menuNamed("m", top);
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).updateMenuItem("m", "deep", "Hi", null, null, null, null);
			assertEquals("MenuItem 'deep' in menu 'm' updated successfully.", result);
			verify(deep).setText("Hi");
		}

		@Test
		@DisplayName("updateMenuItem surfaces a RepositoryException on save as an Error message")
		void repositoryException() throws Exception
		{
			MenuItem item = itemNamed("it", null);
			Menu menu = menuNamed("m", item);
			Fixture f = fixtureWithMenus(menu);
			doThrow(new RepositoryException("saveFail")).when(f.project)
				.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			String result = serviceWith(f.model).updateMenuItem("m", "it", "Hi", null, null, null, null);
			assertEquals("Error: saveFail", result);
		}
	}

	@Nested
	class DeleteMenuModelSide
	{
		@Test
		@DisplayName("deleteMenu removes the menu from the solution and saves")
		void deletes() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).deleteMenu("m");
			assertEquals("Menu 'm' deleted successfully.", result);
			verify(f.solution).removeChild(menu);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { menu }, false);
		}

		@Test
		@DisplayName("deleteMenu reports a missing menu without removing anything")
		void notFound() throws Exception
		{
			Fixture f = fixtureWithMenus(menuNamed("other"));
			String result = serviceWith(f.model).deleteMenu("m");
			assertEquals("Error: Menu 'm' not found", result);
			verify(f.solution, never()).removeChild(org.mockito.ArgumentMatchers.any());
		}

		@Test
		@DisplayName("deleteMenu surfaces a RepositoryException on save as an Error message")
		void repositoryException() throws Exception
		{
			Menu menu = menuNamed("m");
			Fixture f = fixtureWithMenus(menu);
			doThrow(new RepositoryException("saveFail")).when(f.project)
				.saveEditingSolutionNodes(new IPersist[] { menu }, false);
			String result = serviceWith(f.model).deleteMenu("m");
			assertEquals("Error: saveFail", result);
		}
	}

	@Nested
	class DeleteMenuItemModelSide
	{
		@Test
		@DisplayName("deleteMenuItem removes the item from its parent and saves")
		void deletes() throws Exception
		{
			Menu menu = menuNamed("m");
			MenuItem item = itemNamed("it", menu);
			// wire the menu to actually contain the item so findMenuItemRecursive locates it
			final List<IPersist> list = new ArrayList<>(Arrays.asList((IPersist) item));
			lenient().when(menu.getAllObjects()).thenAnswer(inv -> list.iterator());
			Fixture f = fixtureWithMenus(menu);

			String result = serviceWith(f.model).deleteMenuItem("m", "it");
			assertEquals("MenuItem 'it' deleted from menu 'm' successfully.", result);
			verify(menu).removeChild(item);
			verify(f.project).saveEditingSolutionNodes(new IPersist[] { menu }, true);
		}

		@Test
		@DisplayName("deleteMenuItem surfaces a RepositoryException on save as an Error message")
		void repositoryException() throws Exception
		{
			Menu menu = menuNamed("m");
			MenuItem item = itemNamed("it", menu);
			final List<IPersist> list = new ArrayList<>(Arrays.asList((IPersist) item));
			lenient().when(menu.getAllObjects()).thenAnswer(inv -> list.iterator());
			Fixture f = fixtureWithMenus(menu);
			doThrow(new RepositoryException("saveFail")).when(f.project)
				.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			String result = serviceWith(f.model).deleteMenuItem("m", "it");
			assertEquals("Error: saveFail", result);
		}
	}

	@Nested
	class ListMenusAndStructureModelSide
	{
		@Test
		@DisplayName("getMenuStructure renders the menu header, encapsulation and nested items")
		void structureWithItems() throws Exception
		{
			MenuItem child = mock(MenuItem.class);
			lenient().when(child.getName()).thenReturn("child");
			lenient().when(child.getEnabled()).thenReturn(true);
			lenient().when(child.getAllObjects()).thenReturn(Collections.<IPersist> emptyList().iterator());
			MenuItem file = mock(MenuItem.class);
			lenient().when(file.getName()).thenReturn("file");
			lenient().when(file.getText()).thenReturn("File");
			lenient().when(file.getEnabled()).thenReturn(true);
			lenient().when(file.getAllObjects()).thenReturn(Arrays.<IPersist> asList((IPersist) child).iterator());

			Menu menu = menuNamed("mainMenu", file);
			lenient().when(menu.getStyleClass()).thenReturn("nav");
			lenient().when(menu.getEncapsulation()).thenReturn(2);
			Fixture f = fixtureWithMenus(menu);

			String result = serviceWith(f.model).getMenuStructure("mainMenu");
			assertTrue(result.startsWith("Menu: mainMenu\n"), "got:\n" + result);
			assertTrue(result.contains("styleClass: nav"), "got:\n" + result);
			assertTrue(result.contains("encapsulation: module_private"), "got:\n" + result);
			assertTrue(result.contains("- file (text: \"File\")"), "got:\n" + result);
			assertTrue(result.contains("- child"), "got:\n" + result);
		}

		@Test
		@DisplayName("getMenuStructure reports public encapsulation for a 0-encapsulation menu")
		void structurePublicEncapsulation() throws Exception
		{
			Menu menu = menuNamed("m");
			lenient().when(menu.getEncapsulation()).thenReturn(0);
			Fixture f = fixtureWithMenus(menu);
			String result = serviceWith(f.model).getMenuStructure("m");
			assertTrue(result.contains("encapsulation: public"), "got:\n" + result);
		}

		@Test
		@DisplayName("listMenus across modules tags module menus and uses the solution-and-modules header")
		void listsAcrossModules() throws Exception
		{
			// active solution with one menu
			Menu mainMenu = menuNamed("mainMenu");
			Fixture f = fixtureWithMenus(mainMenu);

			// a module with its own solution and one menu
			ServoyProject module = mock(ServoyProject.class);
			Solution moduleSolution = mock(Solution.class);
			lenient().when(module.getEditingSolution()).thenReturn(moduleSolution);
			lenient().when(moduleSolution.getName()).thenReturn("myModule");
			Menu moduleMenu = menuNamed("modMenu");
			lenient().when(moduleSolution.getMenus(false)).thenAnswer(inv -> Arrays.asList(moduleMenu).iterator());
			when(f.model.getModulesOfActiveProject()).thenReturn(new ServoyProject[] { module });

			String result = serviceWith(f.model).listMenus(null);
			assertTrue(result.startsWith("Menus in solution 'mySolution' and modules (2):"), "got:\n" + result);
			assertTrue(result.contains("mainMenu"), "got:\n" + result);
			assertTrue(result.contains("modMenu [myModule]"), "module menu is tagged with its solution; got:\n" + result);
		}
	}

	@Nested
	class FindMenuAcrossModules
	{
		@Test
		@DisplayName("findMenu (via getMenuStructure) locates a menu that lives in a module, not the active solution")
		void findsMenuInModule() throws Exception
		{
			// active solution has no matching menu
			Fixture f = fixtureWithMenus();

			ServoyProject module = mock(ServoyProject.class);
			Solution moduleSolution = mock(Solution.class);
			lenient().when(module.getEditingSolution()).thenReturn(moduleSolution);
			lenient().when(moduleSolution.getName()).thenReturn("myModule");
			Menu moduleMenu = menuNamed("modMenu");
			lenient().when(moduleMenu.getEncapsulation()).thenReturn(0);
			lenient().when(moduleSolution.getMenus(false)).thenAnswer(inv -> Arrays.asList(moduleMenu).iterator());
			when(f.model.getModulesOfActiveProject()).thenReturn(new ServoyProject[] { module });

			String result = serviceWith(f.model).getMenuStructure("modMenu");
			assertTrue(result.startsWith("Menu: modMenu\n"), "got:\n" + result);
		}
	}
}

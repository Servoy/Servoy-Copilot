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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.repository.EclipseUserManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.MenuService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.server.shared.SecurityInfo;
import com.servoy.j2db.util.UUID;

/**
 * Plain-JUnit (Jupiter) unit tests for the <em>own</em> parse / dispatch /
 * format / error branches of {@link ServoyDevServer}'s <b>security</b> and
 * <b>menu</b> {@code @Tool} bodies - without a live Eclipse workspace, model,
 * DLTK or app server.
 * <p>
 * This file is dedicated to the security and menu tool bodies and is kept
 * separate from {@code ServoyDevServerTest} on purpose (parallel coverage work
 * by several agents touches different tool families; no file is shared). It
 * never edits {@code ServoyDevServer} and introduces no production seam: each
 * test constructs the server via its default constructor and, where a tool
 * delegates to a swappable collaborator, reflection-swaps the {@code private
 * final} field for a Mockito mock - exactly as {@code ServoyDevServerTest} and
 * {@code ServoyCoderServerTest} do.
 * </p>
 * <p>
 * <b>What is reachable in plain JUnit (tested here):</b>
 * </p>
 * <ul>
 * <li><b>Security tools</b> ({@code createUser}, {@code changeUserName},
 * {@code setUserPassword}, {@code createPermission}, {@code getFormSecurity},
 * {@code setFormElementAccess}, {@code setFormSecurityBulk}) - every
 * blank-argument guard and the {@code viewable}/{@code accessible} combination
 * guard. These run and return <em>before</em> the first
 * {@code getUserManager()} call, so no static model is touched.</li>
 * <li><b>Menu tools</b> ({@code listMenus}, {@code getMenuStructure},
 * {@code createMenu}, {@code createMenuItem}, {@code updateMenu},
 * {@code updateMenuItem}, {@code deleteMenu}, {@code deleteMenuItem}) - each is
 * a thin dispatcher onto the {@link MenuService} collaborator field. With that
 * field mocked, the server's own argument mapping and result pass-through are
 * asserted per tool.</li>
 * </ul>
 * <p>
 * <b>What is left for the integrationpde suite (and why):</b> the security
 * tools reach the user manager through the private {@code getUserManager()},
 * which calls {@code ServoyModelManager.getServoyModelManager().getServoyModel()
 * .getUserManager()}. There is <em>no swappable collaborator field</em> for the
 * user manager, and that call triggers {@code ServoyModelManager.<clinit>}
 * (which touches {@code ResourcesPlugin.getWorkspace()}), so it only resolves
 * inside a running workbench. Consequently every branch <em>past</em> the blank
 * / combination guards - the "User manager not available" / duplicate /
 * not-found paths, the {@code getFormSecurity} table rendering, the
 * {@code setFormElementAccess} success path, and crucially
 * {@code setFormSecurityBulk}'s {@code accessEntries} JSON parsing (the
 * per-entry {@code viewable}/{@code accessible} read, the malformed-entry and
 * partial-failure reporting) - sits <em>after</em> {@code getUserManager()} and
 * {@code resolveFormForSecurity()} in the method body, so it cannot be driven
 * without the static model. Those stay integration tests. These tests
 * deliberately never call any security tool with valid-enough arguments to pass
 * the guards, so {@code ServoyModelManager.<clinit>} is never triggered.
 * </p>
 *
 * @since 2026.11
 */
class ServoyDevServerSecurityMenuBranchesTest
{
	// =======================================================================
	// reflection helper (no production seam / visibility widening)
	// =======================================================================

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyDevServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	/** A server with its {@code menuService} swapped for the given mock. */
	private static ServoyDevServer withMenu(MenuService menu)
	{
		ServoyDevServer server = new ServoyDevServer();
		setField(server, "menuService", menu);
		return server;
	}

	// =======================================================================
	// Model seam (SVY-21114 follow-up): ServoyDevServer#getServoyModel() is a
	// protected seam that the security tool bodies reach the user manager and
	// the solution/form model through (getUserManager() -> getServoyModel()
	// .getUserManager(); resolveFormForSecurity() / resolveSecuritySolutionName()
	// -> getServoyModel().getActiveProject()/getServoyProject()/
	// getModulesOfActiveProject()). Overriding it with a Mockito-mocked
	// IDeveloperServoyModel drives the MODEL-SIDE branches of those tools
	// WITHOUT a workbench, DLTK or app server.
	//
	// A hard boundary remains: the user-manager <em>mutation</em> calls
	// (createUser/getUserUID/changeUserName/setPassword/createGroup/
	// setFormSecurityAccess) all pass getSecurityClientId(), whose
	// ApplicationServerRegistry.get() blocks (waits) when no app server is
	// registered. So every test here deliberately stops short of a successful
	// mutation: it drives the "user manager not available", not-found,
	// no-active-solution, element-not-found, empty-security-info rendering and
	// all-skipped-bulk paths - each of which returns BEFORE any
	// getSecurityClientId() call. The success paths (and the actual manager
	// mutation) stay in the integrationpde suite.
	// =======================================================================

	/** Subclass whose {@link ServoyDevServer#getServoyModel()} returns the given mock. */
	private static final class ModelServer extends ServoyDevServer
	{
		private final IDeveloperServoyModel model;

		ModelServer(IDeveloperServoyModel model)
		{
			this.model = model;
		}

		@Override
		protected IDeveloperServoyModel getServoyModel()
		{
			return model;
		}
	}

	/**
	 * A mocked model whose {@code getUserManager()} returns the given manager
	 * (may be {@code null} to drive the "User manager not available" branch) and
	 * whose active-project / module lookups resolve to {@code null} / empty by
	 * default.
	 */
	private static IDeveloperServoyModel modelWith(EclipseUserManager userManager)
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		// IDeveloperServoyModel#getUserManager() returns EclipseUserManager (a subtype of
		// WorkspaceUserManager); the production getUserManager() widens it back to
		// WorkspaceUserManager. The mock must therefore be an EclipseUserManager - a plain
		// WorkspaceUserManager mock is NOT a subtype and the production cast would fail.
		when(model.getUserManager()).thenReturn(userManager);
		when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
		return model;
	}

	/** An active ServoyProject whose editing solution resolves {@code formName} to {@code form} (null = not found). */
	private static ServoyProject activeProjectResolving(String formName, Form form)
	{
		Solution solution = mock(Solution.class);
		// Solution#getForm and #getName are concrete methods whose bodies walk the (unstubbed)
		// persistence model; doReturn(...).when(...) stubs them WITHOUT executing the real body
		// (unlike when(mock.getForm(..)), which would invoke it during stubbing).
		doReturn(form).when(solution).getForm(formName);
		doReturn("activeSol").when(solution).getName();
		ServoyProject project = mock(ServoyProject.class);
		doReturn(solution).when(project).getEditingSolution();
		return project;
	}

	/** A Form mock with the given name whose {@code getAllObjects()} yields the given children. */
	private static Form formNamed(String name, List<IPersist> children)
	{
		Form form = mock(Form.class);
		// doReturn/doAnswer avoid running Form's concrete getName/getUUID/getAllObjects bodies
		// (which touch the unstubbed persistence model) during stubbing.
		doReturn(name).when(form).getName();
		doReturn(UUID.randomUUID()).when(form).getUUID();
		doAnswer(inv -> children.iterator()).when(form).getAllObjects();
		return form;
	}

	// =======================================================================
	// getFormSecurity - model-side branches (past the user manager grab)
	// =======================================================================

	@Nested
	class GetFormSecurityModelSide
	{
		@Test
		@DisplayName("getFormSecurity reports 'User manager not available' when the model has no user manager")
		void noUserManager()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available", server.getFormSecurity("perm", "frm", null));
		}

		@Test
		@DisplayName("getFormSecurity reports the form-not-found error (active solution) when the form does not resolve")
		void formNotFoundActive()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			// build the project (which itself stubs inner mocks) BEFORE the outer stubbing starts,
			// otherwise the nested doReturn()/when() runs mid-stubbing -> UnfinishedStubbingException
			ServoyProject active = activeProjectResolving("ghost", null);
			doReturn(active).when(model).getActiveProject();

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: Form 'ghost' not found", server.getFormSecurity("perm", "ghost", null));
		}

		@Test
		@DisplayName("getFormSecurity appends the solution name to the not-found error when a solutionName was given")
		void formNotFoundNamedSolution()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("ghost", null);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: Form 'ghost' not found in solution 'sol'",
				server.getFormSecurity("perm", "ghost", "sol"));
		}

		@Test
		@DisplayName("getFormSecurity renders the default-access message when the manager returns no security infos")
		void noExplicitRights()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			doReturn(Collections.emptyList()).when(um).getSecurityInfos("perm", form);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject active = activeProjectResolving("orders", form);
			doReturn(active).when(model).getActiveProject();

			ServoyDevServer server = new ModelServer(model);
			String result = server.getFormSecurity("perm", "orders", null);
			assertTrue(result.startsWith("Form security for 'orders' with permission 'perm':"), "got: " + result);
			assertTrue(result.contains("No explicit access rights set. Default access applies (VIEWABLE + ACCESSIBLE)."),
				"got: " + result);
		}

		@Test
		@DisplayName("getFormSecurity renders a markdown table of the manager's security infos, resolving the form UUID to '(form: name)'")
		void rendersTable()
		{
			Form form = formNamed("orders", Collections.emptyList());
			String formUid = form.getUUID().toString();
			// VIEWABLE(1)+ACCESSIBLE(2) = 3 on the form element itself
			SecurityInfo info = new SecurityInfo(formUid, 3);
			EclipseUserManager um = mock(EclipseUserManager.class);
			doReturn(List.of(info)).when(um).getSecurityInfos("perm", form);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject active = activeProjectResolving("orders", form);
			doReturn(active).when(model).getActiveProject();

			ServoyDevServer server = new ModelServer(model);
			String result = server.getFormSecurity("perm", "orders", null);
			assertTrue(result.contains("| Element | Viewable | Accessible | Access |"), "got: " + result);
			assertTrue(result.contains("| (form: orders) | true | true | 3 |"), "got: " + result);
		}
	}

	// =======================================================================
	// setFormElementAccess - model-side branches (past the combination guard)
	// =======================================================================

	@Nested
	class SetFormElementAccessModelSide
	{
		@Test
		@DisplayName("setFormElementAccess reports 'User manager not available' when the model has no user manager")
		void noUserManager()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available",
				server.setFormElementAccess("perm", "frm", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess reports 'No active solution found' when no solution resolves")
		void noActiveSolution()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			when(model.getActiveProject()).thenReturn(null); // resolveSecuritySolutionName -> null

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: No active solution found",
				server.setFormElementAccess("perm", "frm", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess reports the form-not-found error once a solution name is supplied but the form is missing")
		void formNotFound()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			// solutionName given -> resolveSecuritySolutionName returns it directly (non-null);
			// resolveFormForSecurity uses getServoyProject(sol) -> form null
			ServoyProject project = activeProjectResolving("ghost", null);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: Form 'ghost' not found in solution 'sol'",
				server.setFormElementAccess("perm", "ghost", "el", "true", "true", "sol"));
		}

		@Test
		@DisplayName("setFormElementAccess reports the element-not-found error when the named element is absent from the form")
		void elementNotFound()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("orders", form);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: Element 'missingBtn' not found in form 'orders'",
				server.setFormElementAccess("perm", "orders", "missingBtn", "true", "true", "sol"));
		}
	}

	// =======================================================================
	// setFormSecurityBulk - model-side branches (all-skipped entries only; a
	// successful entry would reach getSecurityClientId() and block, so every
	// entry below is an invalid-combination or element-not-found skip)
	// =======================================================================

	@Nested
	class SetFormSecurityBulkModelSide
	{
		@Test
		@DisplayName("setFormSecurityBulk reports 'User manager not available' when the model has no user manager")
		void noUserManager()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available",
				server.setFormSecurityBulk("perm", "frm", "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk reports 'No active solution found' when no solution resolves")
		void noActiveSolution()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			when(model.getActiveProject()).thenReturn(null);

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: No active solution found",
				server.setFormSecurityBulk("perm", "frm", "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk reports the form-not-found error when the form is missing")
		void formNotFound()
		{
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("ghost", null);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			assertEquals("Error: Form 'ghost' not found in solution 'sol'",
				server.setFormSecurityBulk("perm", "ghost", "[]", "sol"));
		}

		@Test
		@DisplayName("setFormSecurityBulk surfaces a malformed-JSON accessEntries as an Error from the JSON parser")
		void malformedJson()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("orders", form);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			String result = server.setFormSecurityBulk("perm", "orders", "not json", "sol");
			assertTrue(result.startsWith("Error:"), "got: " + result);
		}

		@Test
		@DisplayName("setFormSecurityBulk reports 0 applied with a per-entry invalid-combination error (no entry reaches the manager)")
		void invalidCombinationEntry()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("orders", form);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			String entries = "[{\"elementName\":\"btn1\",\"viewable\":false,\"accessible\":true}]";
			String result = server.setFormSecurityBulk("perm", "orders", entries, "sol");
			assertTrue(result.startsWith("Bulk access update on form 'orders' for permission 'perm':"), "got: " + result);
			assertTrue(result.contains("0/1 entries applied successfully."), "got: " + result);
			assertTrue(result.contains("Errors:"), "got: " + result);
			assertTrue(result.contains("Invalid combination for 'btn1': accessible=true has no effect when viewable=false"),
				"got: " + result);
		}

		@Test
		@DisplayName("setFormSecurityBulk reports a per-entry element-not-found error (no entry reaches the manager)")
		void elementNotFoundEntry()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("orders", form);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			// viewable=false, accessible=false -> passes the combination guard but the element is missing
			String entries = "[{\"elementName\":\"ghostBtn\",\"viewable\":false,\"accessible\":false}]";
			String result = server.setFormSecurityBulk("perm", "orders", entries, "sol");
			assertTrue(result.contains("0/1 entries applied successfully."), "got: " + result);
			assertTrue(result.contains("Element 'ghostBtn' not found"), "got: " + result);
		}

		@Test
		@DisplayName("setFormSecurityBulk reports two skipped entries (invalid combination + missing element) as 0/2 with both errors")
		void twoSkippedEntries()
		{
			Form form = formNamed("orders", Collections.emptyList());
			EclipseUserManager um = mock(EclipseUserManager.class);
			IDeveloperServoyModel model = modelWith(um);
			ServoyProject project = activeProjectResolving("orders", form);
			doReturn(project).when(model).getServoyProject("sol");

			ServoyDevServer server = new ModelServer(model);
			String entries = "[" +
				"{\"elementName\":\"btn1\",\"viewable\":false,\"accessible\":true}," +
				"{\"elementName\":\"ghostBtn\",\"viewable\":false,\"accessible\":false}]";
			String result = server.setFormSecurityBulk("perm", "orders", entries, "sol");
			assertTrue(result.contains("0/2 entries applied successfully."), "got: " + result);
			assertTrue(result.contains("Invalid combination for 'btn1'"), "got: " + result);
			assertTrue(result.contains("Element 'ghostBtn' not found"), "got: " + result);
		}
	}

	// =======================================================================
	// createUser / changeUserName / setUserPassword / createPermission -
	// the "User manager not available" model-side branch (past the blank
	// guards, before any getSecurityClientId() mutation call that would block)
	// =======================================================================

	@Nested
	class UserManagerNotAvailable
	{
		@Test
		@DisplayName("createUser reports 'User manager not available' once its blank guards pass and the model has no user manager")
		void createUser()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available", server.createUser("bob", "pw", null));
		}

		@Test
		@DisplayName("changeUserName reports 'User manager not available' once its blank guards pass")
		void changeUserName()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available", server.changeUserName("old", "new"));
		}

		@Test
		@DisplayName("setUserPassword reports 'User manager not available' once its blank guards pass")
		void setUserPassword()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available", server.setUserPassword("bob", "pw"));
		}

		@Test
		@DisplayName("createPermission reports 'User manager not available' once its blank guard passes")
		void createPermission()
		{
			ServoyDevServer server = new ModelServer(modelWith(null));
			assertEquals("Error: User manager not available", server.createPermission("admins"));
		}

		@Test
		@DisplayName("the 'User manager not available' branch does not consult the active project (manager is grabbed first)")
		void managerCheckedBeforeProject()
		{
			IDeveloperServoyModel model = modelWith(null);
			ServoyDevServer server = new ModelServer(model);

			assertEquals("Error: User manager not available", server.createUser("bob", "pw", null));
			verify(model).getUserManager();
			verify(model, org.mockito.Mockito.never()).getActiveProject();
		}
	}

	// =======================================================================
	// createUser - blank guards (return before getUserManager())
	// =======================================================================

	@Nested
	class CreateUser
	{
		@Test
		@DisplayName("createUser rejects a null userName before touching the model")
		void nullUserName()
		{
			assertEquals("Error: userName is required", new ServoyDevServer().createUser(null, "pw", null));
		}

		@Test
		@DisplayName("createUser rejects a blank (whitespace) userName")
		void blankUserName()
		{
			assertEquals("Error: userName is required", new ServoyDevServer().createUser("   ", "pw", "uid"));
		}

		@Test
		@DisplayName("createUser rejects a null password once userName is present")
		void nullPassword()
		{
			assertEquals("Error: password is required", new ServoyDevServer().createUser("bob", null, null));
		}

		@Test
		@DisplayName("createUser rejects an empty password once userName is present")
		void emptyPassword()
		{
			assertEquals("Error: password is required", new ServoyDevServer().createUser("bob", "", "uid"));
		}
	}

	// =======================================================================
	// changeUserName - blank guards
	// =======================================================================

	@Nested
	class ChangeUserName
	{
		@Test
		@DisplayName("changeUserName rejects a null oldName")
		void nullOldName()
		{
			assertEquals("Error: oldName is required", new ServoyDevServer().changeUserName(null, "new"));
		}

		@Test
		@DisplayName("changeUserName rejects a blank oldName")
		void blankOldName()
		{
			assertEquals("Error: oldName is required", new ServoyDevServer().changeUserName("  ", "new"));
		}

		@Test
		@DisplayName("changeUserName rejects a null newName once oldName is present")
		void nullNewName()
		{
			assertEquals("Error: newName is required", new ServoyDevServer().changeUserName("old", null));
		}

		@Test
		@DisplayName("changeUserName rejects a blank newName once oldName is present")
		void blankNewName()
		{
			assertEquals("Error: newName is required", new ServoyDevServer().changeUserName("old", "   "));
		}
	}

	// =======================================================================
	// setUserPassword - blank guards
	// =======================================================================

	@Nested
	class SetUserPassword
	{
		@Test
		@DisplayName("setUserPassword rejects a null userName")
		void nullUserName()
		{
			assertEquals("Error: userName is required", new ServoyDevServer().setUserPassword(null, "pw"));
		}

		@Test
		@DisplayName("setUserPassword rejects a blank userName")
		void blankUserName()
		{
			assertEquals("Error: userName is required", new ServoyDevServer().setUserPassword("   ", "pw"));
		}

		@Test
		@DisplayName("setUserPassword rejects a null newPassword once userName is present")
		void nullNewPassword()
		{
			assertEquals("Error: newPassword is required", new ServoyDevServer().setUserPassword("bob", null));
		}

		@Test
		@DisplayName("setUserPassword rejects an empty newPassword once userName is present")
		void emptyNewPassword()
		{
			assertEquals("Error: newPassword is required", new ServoyDevServer().setUserPassword("bob", ""));
		}
	}

	// =======================================================================
	// createPermission - blank guard
	// =======================================================================

	@Nested
	class CreatePermission
	{
		@Test
		@DisplayName("createPermission rejects a null permissionName")
		void nullName()
		{
			assertEquals("Error: permissionName is required", new ServoyDevServer().createPermission(null));
		}

		@Test
		@DisplayName("createPermission rejects a blank permissionName")
		void blankName()
		{
			assertEquals("Error: permissionName is required", new ServoyDevServer().createPermission("   "));
		}
	}

	// =======================================================================
	// getFormSecurity - blank guards (return before getUserManager())
	// =======================================================================

	@Nested
	class GetFormSecurity
	{
		@Test
		@DisplayName("getFormSecurity rejects a null permissionName")
		void nullPermission()
		{
			assertEquals("Error: permissionName is required", new ServoyDevServer().getFormSecurity(null, "frm", null));
		}

		@Test
		@DisplayName("getFormSecurity rejects a blank permissionName")
		void blankPermission()
		{
			assertEquals("Error: permissionName is required", new ServoyDevServer().getFormSecurity("  ", "frm", "sol"));
		}

		@Test
		@DisplayName("getFormSecurity rejects a null formName once permissionName is present")
		void nullForm()
		{
			assertEquals("Error: formName is required", new ServoyDevServer().getFormSecurity("perm", null, null));
		}

		@Test
		@DisplayName("getFormSecurity rejects a blank formName once permissionName is present")
		void blankForm()
		{
			assertEquals("Error: formName is required", new ServoyDevServer().getFormSecurity("perm", "   ", "sol"));
		}
	}

	// =======================================================================
	// setFormElementAccess - blank guards + viewable/accessible parsing guard
	// =======================================================================

	@Nested
	class SetFormElementAccess
	{
		@Test
		@DisplayName("setFormElementAccess rejects a null permissionName")
		void nullPermission()
		{
			assertEquals("Error: permissionName is required",
				new ServoyDevServer().setFormElementAccess(null, "frm", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess rejects a blank permissionName")
		void blankPermission()
		{
			assertEquals("Error: permissionName is required",
				new ServoyDevServer().setFormElementAccess("  ", "frm", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess rejects a null formName once permissionName is present")
		void nullForm()
		{
			assertEquals("Error: formName is required",
				new ServoyDevServer().setFormElementAccess("perm", null, "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess rejects a blank formName once permissionName is present")
		void blankForm()
		{
			assertEquals("Error: formName is required",
				new ServoyDevServer().setFormElementAccess("perm", "  ", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess rejects accessible=true together with viewable=false (combination guard)")
		void invalidCombinationTrueAccessibleFalseViewable()
		{
			String result = new ServoyDevServer().setFormElementAccess("perm", "frm", "el", "false", "true", null);
			assertTrue(result.startsWith("Error: Invalid combination"), "got: " + result);
			assertTrue(result.contains("accessible=true has no effect when viewable=false"), "got: " + result);
		}

		@Test
		@DisplayName("setFormElementAccess parses a non-'true' viewable literal as false, still tripping the combination guard when accessible is true")
		void nonTrueViewableLiteralParsesAsFalse()
		{
			// Boolean.parseBoolean treats anything other than a case-insensitive "true" as false,
			// so viewable="no" parses to false. With accessible="TRUE" (parsed true) the
			// !viewable && accessible combination guard still fires - this exercises the
			// viewable/accessible String->boolean parsing branch (non-literal 'true' values) and
			// case-insensitive 'TRUE' handling, all before any model access.
			String result = new ServoyDevServer().setFormElementAccess("perm", "frm", "el", "no", "TRUE", null);
			assertTrue(result.startsWith("Error: Invalid combination"), "got: " + result);
		}
	}

	// =======================================================================
	// setFormSecurityBulk - blank guards (return before getUserManager())
	// =======================================================================

	@Nested
	class SetFormSecurityBulk
	{
		@Test
		@DisplayName("setFormSecurityBulk rejects a null permissionName")
		void nullPermission()
		{
			assertEquals("Error: permissionName is required",
				new ServoyDevServer().setFormSecurityBulk(null, "frm", "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk rejects a blank permissionName")
		void blankPermission()
		{
			assertEquals("Error: permissionName is required",
				new ServoyDevServer().setFormSecurityBulk("  ", "frm", "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk rejects a null formName once permissionName is present")
		void nullForm()
		{
			assertEquals("Error: formName is required",
				new ServoyDevServer().setFormSecurityBulk("perm", null, "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk rejects a blank formName once permissionName is present")
		void blankForm()
		{
			assertEquals("Error: formName is required",
				new ServoyDevServer().setFormSecurityBulk("perm", "   ", "[]", null));
		}

		@Test
		@DisplayName("setFormSecurityBulk rejects a null accessEntries once permission+form are present")
		void nullAccessEntries()
		{
			assertEquals("Error: accessEntries is required",
				new ServoyDevServer().setFormSecurityBulk("perm", "frm", null, null));
		}

		@Test
		@DisplayName("setFormSecurityBulk rejects a blank accessEntries once permission+form are present")
		void blankAccessEntries()
		{
			assertEquals("Error: accessEntries is required",
				new ServoyDevServer().setFormSecurityBulk("perm", "frm", "   ", null));
		}
	}

	// =======================================================================
	// Menu tools - pure dispatch onto the mocked MenuService (one per tool)
	// =======================================================================

	@Nested
	class MenuToolDispatch
	{
		@Test
		@DisplayName("listMenus passes its scope straight to MenuService.listMenus and returns the result")
		void listMenusDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.listMenus("current")).thenReturn("the menus");
			ServoyDevServer server = withMenu(menu);

			assertEquals("the menus", server.listMenus("current"));
			verify(menu).listMenus("current");
		}

		@Test
		@DisplayName("listMenus passes a null scope through unchanged (default-scope branch handled by the service)")
		void listMenusNullScope()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.listMenus(null)).thenReturn("all menus");
			ServoyDevServer server = withMenu(menu);

			assertEquals("all menus", server.listMenus(null));
			verify(menu).listMenus(null);
		}

		@Test
		@DisplayName("getMenuStructure passes menuName straight to MenuService.getMenuStructure")
		void getMenuStructureDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.getMenuStructure("mainMenu")).thenReturn("structure text");
			ServoyDevServer server = withMenu(menu);

			assertEquals("structure text", server.getMenuStructure("mainMenu"));
			verify(menu).getMenuStructure("mainMenu");
		}

		@Test
		@DisplayName("createMenu maps name/styleClass/encapsulation onto MenuService.createMenu")
		void createMenuDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.createMenu("main", "btn-primary", "module_private")).thenReturn("created main");
			ServoyDevServer server = withMenu(menu);

			assertEquals("created main", server.createMenu("main", "btn-primary", "module_private"));
			verify(menu).createMenu("main", "btn-primary", "module_private");
		}

		@Test
		@DisplayName("createMenu forwards null optional args (styleClass/encapsulation) unchanged")
		void createMenuNullOptionals()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.createMenu("main", null, null)).thenReturn("created default");
			ServoyDevServer server = withMenu(menu);

			assertEquals("created default", server.createMenu("main", null, null));
			verify(menu).createMenu("main", null, null);
		}

		@Test
		@DisplayName("createMenuItem maps all eight args (parent/text/tip/style/icon/enabled) onto MenuService.createMenuItem")
		void createMenuItemDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.createMenuItem("main", "home", "parentItem", "Home", "Go home", "cls", "fas fa-home", "true"))
				.thenReturn("item created");
			ServoyDevServer server = withMenu(menu);

			assertEquals("item created",
				server.createMenuItem("main", "home", "parentItem", "Home", "Go home", "cls", "fas fa-home", "true"));
			verify(menu).createMenuItem("main", "home", "parentItem", "Home", "Go home", "cls", "fas fa-home", "true");
		}

		@Test
		@DisplayName("createMenuItem forwards null optional args (parent/enabled/icon) unchanged")
		void createMenuItemNullOptionals()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.createMenuItem("main", "home", null, null, null, null, null, null)).thenReturn("minimal item");
			ServoyDevServer server = withMenu(menu);

			assertEquals("minimal item",
				server.createMenuItem("main", "home", null, null, null, null, null, null));
			verify(menu).createMenuItem("main", "home", null, null, null, null, null, null);
		}

		@Test
		@DisplayName("updateMenu maps name/styleClass/encapsulation onto MenuService.updateMenu")
		void updateMenuDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.updateMenu("main", "", "public")).thenReturn("updated");
			ServoyDevServer server = withMenu(menu);

			assertEquals("updated", server.updateMenu("main", "", "public"));
			verify(menu).updateMenu("main", "", "public");
		}

		@Test
		@DisplayName("updateMenuItem maps all seven args onto MenuService.updateMenuItem")
		void updateMenuItemDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.updateMenuItem("main", "home", "Home2", "tip2", "cls2", "fas fa-cog", "false"))
				.thenReturn("item updated");
			ServoyDevServer server = withMenu(menu);

			assertEquals("item updated",
				server.updateMenuItem("main", "home", "Home2", "tip2", "cls2", "fas fa-cog", "false"));
			verify(menu).updateMenuItem("main", "home", "Home2", "tip2", "cls2", "fas fa-cog", "false");
		}

		@Test
		@DisplayName("deleteMenu passes name straight to MenuService.deleteMenu")
		void deleteMenuDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.deleteMenu("main")).thenReturn("deleted");
			ServoyDevServer server = withMenu(menu);

			assertEquals("deleted", server.deleteMenu("main"));
			verify(menu).deleteMenu("main");
		}

		@Test
		@DisplayName("deleteMenuItem maps menuName/itemName onto MenuService.deleteMenuItem")
		void deleteMenuItemDispatch()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.deleteMenuItem("main", "home")).thenReturn("item deleted");
			ServoyDevServer server = withMenu(menu);

			assertEquals("item deleted", server.deleteMenuItem("main", "home"));
			verify(menu).deleteMenuItem("main", "home");
		}

		@Test
		@DisplayName("a menu tool returns the collaborator's error string verbatim (error pass-through)")
		void errorPassThrough()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.deleteMenu("ghost")).thenReturn("Error: Menu 'ghost' not found");
			ServoyDevServer server = withMenu(menu);

			assertEquals("Error: Menu 'ghost' not found", server.deleteMenu("ghost"));
			verify(menu).deleteMenu("ghost");
		}

		@Test
		@DisplayName("createMenu touches only the one collaborator method it dispatches to")
		void dispatchTouchesOnlyThatMethod()
		{
			MenuService menu = mock(MenuService.class);
			when(menu.createMenu("main", null, null)).thenReturn("ok");
			ServoyDevServer server = withMenu(menu);

			server.createMenu("main", null, null);
			verify(menu).createMenu("main", null, null);
			// no other MenuService interaction occurred as part of this single dispatch
			org.mockito.Mockito.verifyNoMoreInteractions(menu);
		}
	}

	// =======================================================================
	// A security-guard rejection must NOT dispatch to the menu collaborator
	// (sanity: guards and menu dispatch are independent server paths)
	// =======================================================================

	@Nested
	class CrossConcern
	{
		@Test
		@DisplayName("a security guard rejection returns without any menuService interaction")
		void securityGuardDoesNotTouchMenu()
		{
			MenuService menu = mock(MenuService.class);
			ServoyDevServer server = withMenu(menu);

			assertEquals("Error: userName is required", server.createUser(null, "pw", null));
			verifyNoInteractions(menu);
		}
	}
}

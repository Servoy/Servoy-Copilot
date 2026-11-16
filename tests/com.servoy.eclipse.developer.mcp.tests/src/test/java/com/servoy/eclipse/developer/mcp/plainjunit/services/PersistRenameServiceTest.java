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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Method;
import java.util.Collections;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.PersistRenameService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportName;
import com.servoy.j2db.persistence.ISupportUpdateableName;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link PersistRenameService}'s <em>own</em>
 * logic - input validation, the editing-solution / "not found" / "already exists"
 * guards, the simple-name resolution + ambiguity report, and the rename-dispatch +
 * success-message formatting - with the Servoy model boundary stubbed via Mockito.
 * <p>
 * The service reaches the model through the overridable {@code getServoyModel()}
 * seam, which the {@link #serviceWith(IDeveloperServoyModel)} helper overrides with
 * a mocked {@link IDeveloperServoyModel}; the public per-type rename methods receive
 * a mocked {@link ServoyProject} directly. The Servoy core these hand back
 * ({@link Solution}, {@link Form}, ...) is only stubbed at the boundary so the
 * service's branches run - the core's behaviour itself is NOT under test here
 * (that belongs in servoy-eclipse).
 * </p>
 * <p>
 * <b>What stays for the integrationpde suite (not reachable here).</b> Everything
 * that touches the real Eclipse workspace or the app-server registry:
 * {@code renameScope} (IFile.move), {@code rawFileRename} (IFile.move), the
 * IFile-resolution branches of {@code renameByPath} (findFilesForLocationURI /
 * IFile.exists), {@code renameFormSpecFiles} (real java.nio file moves), and
 * {@code renameSolution}'s workspace-runnable project move +
 * {@code ApplicationServerRegistry}/{@code EclipseRepository} reference rewrite.
 * Only {@code renameSolution}'s pre-workspace "solution not found" guard is
 * reachable here (it fails before any ResourcesPlugin/registry work).
 * </p>
 *
 * @since 2026.11
 */
class PersistRenameServiceTest {
	/** A {@link PersistRenameService} whose model seam returns the supplied mock. */
	private static PersistRenameService serviceWith(IDeveloperServoyModel model) {
		return new PersistRenameService() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	/** A plain service (seam never reached in the test path). */
	private final PersistRenameService service = new PersistRenameService();

	/** A mocked ServoyProject whose editingSolution and eclipse-project name are set. */
	private static ServoyProject projectWith(Solution editingSolution, String projectName) {
		ServoyProject project = mock(ServoyProject.class);
		when(project.getEditingSolution()).thenReturn(editingSolution);
		IProject eclipseProject = mock(IProject.class);
		when(eclipseProject.getName()).thenReturn(projectName);
		when(project.getProject()).thenReturn(eclipseProject);
		return project;
	}

	// =====================================================================
	// renameByName - input validation (no model touched)
	// =====================================================================

	@Nested
	class RenameByNameInputValidation {
		@Test
		@DisplayName("renameByName rejects null/blank oldName and newName and identical names")
		void rejectsBadInputs() {
			assertTrue(service.renameByName(null, "newName").contains("oldName is required"));
			assertTrue(service.renameByName("  ", "newName").contains("oldName is required"));
			assertTrue(service.renameByName("oldName", null).contains("newName is required"));
			assertTrue(service.renameByName("oldName", "   ").contains("newName is required"));
			assertTrue(service.renameByName("same", "same").contains("the same"));
		}

		@Test
		@DisplayName("renameByName rejects a newName that contains a path separator")
		void rejectsNewNameWithSeparator() {
			assertTrue(service.renameByName("art", "bad/name").contains("bare name"));
			assertTrue(service.renameByName("art", "bad\\name").contains("bare name"));
		}
	}

	// =====================================================================
	// renameBySimpleName - active-solution guard, 0/ambiguous/single dispatch
	// =====================================================================

	@Nested
	class RenameBySimpleName {
		@Test
		@DisplayName("renameByName reports no active solution when there is no active project")
		void noActiveProject() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			assertEquals("Error: No active solution found.", serviceWith(model).renameByName("x", "y"));
		}

		@Test
		@DisplayName("renameByName with zero model matches and no raw file reports 'No artifact or file named'")
		void zeroMatchesNoRawFile() {
			Solution solution = emptySolution();
			ServoyProject active = projectWith(solution, "sol");
			IFile ghostJs = nonExistentFile();
			// active.getProject().findMember(oldName) -> null (no raw file)
			when(active.getProject().findMember("ghost")).thenReturn(null);
			// scope .js probe
			when(active.getProject().getFile("ghost.js")).thenReturn(ghostJs);

			IDeveloperServoyModel model = modelWith(active, "sol");
			String result = serviceWith(model).renameByName("ghost", "newName");
			assertEquals("Error: No artifact or file named 'ghost' found in the active solution or project.", result);
		}

		@Test
		@DisplayName("renameByName with two model matches (form + relation) returns the ambiguity report")
		void ambiguousMatches() {
			Solution solution = emptySolution();
			when(solution.getForm("dup")).thenReturn(mock(Form.class));
			when(solution.getRelation("dup")).thenReturn(mock(Relation.class));
			ServoyProject active = projectWith(solution, "sol");
			IFile dupJs = nonExistentFile();
			when(active.getProject().getFile("dup.js")).thenReturn(dupJs);

			IDeveloperServoyModel model = modelWith(active, "sol");
			String result = serviceWith(model).renameByName("dup", "newName");
			assertTrue(result.contains("Ambiguous name 'dup'"), "got: " + result);
			assertTrue(result.contains("form 'dup' in 'sol'"), "got: " + result);
			assertTrue(result.contains("relation 'dup' in 'sol'"), "got: " + result);
		}

		@Test
		@DisplayName("renameByName with a single form match dispatches to renameForm and returns its success message")
		void singleFormMatchDispatches() throws Exception {
			Solution solution = emptySolution();
			Form form = mock(Form.class);
			when(form.getName()).thenReturn("myForm");
			when(solution.getForm("myForm")).thenReturn(form);
			when(solution.getName()).thenReturn("sol");

			ServoyProject active = projectWith(solution, "sol");
			IFile myFormJs = nonExistentFile();
			when(active.getProject().getFile("myForm.js")).thenReturn(myFormJs);
			when(active.getSolution()).thenReturn(solution);

			IDeveloperServoyModel model = modelWith(active, "sol");
			IValidateName validator = mock(IValidateName.class);
			when(model.getNameValidator()).thenReturn(validator);

			String result = serviceWith(model).renameByName("myForm", "newForm");
			// doUpdateName success message (renameForm then attempts spec-file rename,
			// which is a no-op/caught when no files exist - stays off the real path).
			assertEquals("Renamed form 'myForm' to 'newForm' successfully.", result);
			verify(form).updateName(eq(validator), eq("newForm"));
			verify(active).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
		}
	}

	// =====================================================================
	// Per-type rename guards + success (ServoyProject passed directly)
	// =====================================================================

	@Nested
	class RenameFormGuards {
		@Test
		@DisplayName("renameForm reports when the editing solution cannot be obtained")
		void noEditingSolution() throws Exception {
			ServoyProject project = projectWith(null, "sol");
			assertEquals("Error: Cannot get editing solution.", service.renameForm("f", "g", project));
		}

		@Test
		@DisplayName("renameForm reports a form not present in the solution as not found")
		void formNotFound() throws Exception {
			Solution solution = mock(Solution.class);
			when(solution.getForm("f")).thenReturn(null);
			ServoyProject project = projectWith(solution, "sol");
			assertEquals("Error: Form 'f' not found in solution 'sol'.", service.renameForm("f", "g", project));
		}
	}

	@Nested
	class RenameRelationGuards {
		@Test
		@DisplayName("renameRelation guards null editing solution and a missing relation")
		void guards() throws Exception {
			assertEquals("Error: Cannot get editing solution.",
					service.renameRelation("r", "s", projectWith(null, "sol")));

			Solution solution = mock(Solution.class);
			when(solution.getRelation("r")).thenReturn(null);
			assertEquals("Error: Relation 'r' not found in solution 'sol'.",
					service.renameRelation("r", "s", projectWith(solution, "sol")));
		}

		@Test
		@DisplayName("renameRelation renames via the seam validator and reports success")
		void success() throws Exception {
			Solution solution = mock(Solution.class);
			Relation relation = mock(Relation.class);
			when(relation.getName()).thenReturn("r");
			when(solution.getRelation("r")).thenReturn(relation);
			ServoyProject project = projectWith(solution, "sol");
			IValidateName validator = mock(IValidateName.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getNameValidator()).thenReturn(validator);

			String result = serviceWith(model).renameRelation("r", "s", project);
			assertEquals("Renamed relation 'r' to 's' successfully.", result);
			verify(relation).updateName(eq(validator), eq("s"));
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
		}
	}

	@Nested
	class RenameValueListGuards {
		@Test
		@DisplayName("renameValueList guards null editing solution and a missing valuelist")
		void guards() throws Exception {
			assertEquals("Error: Cannot get editing solution.",
					service.renameValueList("v", "w", projectWith(null, "sol")));

			Solution solution = mock(Solution.class);
			when(solution.getValueList("v")).thenReturn(null);
			assertEquals("Error: ValueList 'v' not found in solution 'sol'.",
					service.renameValueList("v", "w", projectWith(solution, "sol")));
		}
	}

	@Nested
	class RenameMenuGuards {
		@Test
		@DisplayName("renameMenu guards null editing solution and a menu not found among the solution menus")
		void guards() throws Exception {
			assertEquals("Error: Cannot get editing solution.",
					service.renameMenu("m", "n", projectWith(null, "sol")));

			Solution solution = mock(Solution.class);
			when(solution.getMenus(false)).thenReturn(Collections.<Menu> emptyList().iterator());
			assertEquals("Error: Menu 'm' not found in solution 'sol'.",
					service.renameMenu("m", "n", projectWith(solution, "sol")));
		}
	}

	@Nested
	class RenameMenuItemGuards {
		@Test
		@DisplayName("renameMenuItem guards null editing solution and a missing menu item")
		void guards() throws Exception {
			assertEquals("Error: Cannot get editing solution.",
					service.renameMenuItem("mi", "ni", projectWith(null, "sol")));

			Solution solution = mock(Solution.class);
			when(solution.getMenus(false)).thenReturn(Collections.<Menu> emptyList().iterator());
			assertEquals("Error: MenuItem 'mi' not found in solution 'sol'.",
					service.renameMenuItem("mi", "ni", projectWith(solution, "sol")));
		}
	}

	@Nested
	class RenameMediaGuards {
		@Test
		@DisplayName("renameMedia guards null editing solution and a media not found")
		void notFound() {
			assertEquals("Error: Cannot get editing solution.",
					service.renameMedia("img.png", "other.png", projectWith(null, "sol")));

			Solution solution = mock(Solution.class);
			when(solution.getMedias(false)).thenReturn(Collections.<Media> emptyList().iterator());
			assertEquals("Error: Media 'img.png' not found in solution 'sol'.",
					service.renameMedia("img.png", "other.png", projectWith(solution, "sol")));
		}

		@Test
		@DisplayName("renameMedia reports an already-existing target media as an error")
		void alreadyExists() {
			Solution solution = mock(Solution.class);
			Media existing = mock(Media.class);
			when(existing.getName()).thenReturn("img.png");
			when(solution.getMedias(false)).thenReturn(Collections.singletonList(existing).iterator());
			when(solution.getMedia("other.png")).thenReturn(mock(Media.class));
			ServoyProject project = projectWith(solution, "sol");

			assertEquals("Error: Media 'other.png' already exists.",
					service.renameMedia("img.png", "other.png", project));
		}

		@Test
		@DisplayName("renameMedia renames in the model and reports success when the target is free")
		void success() throws Exception {
			Solution solution = mock(Solution.class);
			Media existing = mock(Media.class);
			when(existing.getName()).thenReturn("img.png");
			when(solution.getMedias(false)).thenReturn(Collections.singletonList(existing).iterator());
			when(solution.getMedia("other.png")).thenReturn(null);
			ServoyProject project = projectWith(solution, "sol");

			String result = service.renameMedia("img.png", "other.png", project);
			assertEquals("Renamed media 'img.png' to 'other.png' successfully.", result);
			verify(existing).setName("other.png");
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
		}
	}

	// =====================================================================
	// renameSolution - the pre-workspace guard reachable via the seam
	// =====================================================================

	@Nested
	class RenameSolutionGuard {
		@Test
		@DisplayName("renameSolution reports a solution that is not known to the model as not found")
		void solutionNotFound() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getServoyProject("old")).thenReturn(null);
			assertEquals("Error: Solution 'old' not found.", serviceWith(model).renameSolution("old", "new"));
		}
	}

	// =====================================================================
	// doUpdateName - the "does not support renaming" guard (private helper)
	// =====================================================================

	@Nested
	class DoUpdateNameGuard {
		@Test
		@DisplayName("doUpdateName reports a persist that is not ISupportUpdateableName as unsupported")
		void unsupportedPersist() throws Exception {
			// A persist that is NOT ISupportUpdateableName -> early guard, model never touched.
			IPersist persist = mock(IPersist.class);
			Method m = PersistRenameService.class.getDeclaredMethod("doUpdateName", IPersist.class, String.class,
					ServoyProject.class, String.class);
			m.setAccessible(true);
			Object result = m.invoke(service, persist, "new", mock(ServoyProject.class), "Form");
			assertEquals("Error: Form does not support renaming.", result);
		}

		@Test
		@DisplayName("doUpdateName on a renameable persist uses the seam validator, updates the name and saves")
		void renameablePersist() throws Exception {
			// Mock that is cast to ISupportUpdateableName AND ISupportName in the body.
			ISupportUpdateableName persist = mock(ISupportUpdateableName.class,
					withSettings().extraInterfaces(ISupportName.class, IPersist.class));
			when(((ISupportName) persist).getName()).thenReturn("old");
			ServoyProject project = mock(ServoyProject.class);
			IValidateName validator = mock(IValidateName.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getNameValidator()).thenReturn(validator);

			Method m = PersistRenameService.class.getDeclaredMethod("doUpdateName", IPersist.class, String.class,
					ServoyProject.class, String.class);
			m.setAccessible(true);
			Object result = m.invoke(serviceWith(model), persist, "new", project, "Relation");

			assertEquals("Renamed relation 'old' to 'new' successfully.", result);
			verify(persist).updateName(eq(validator), eq("new"));
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
		}
	}

	// =====================================================================
	// buildAmbiguousMessage + withSameExtension (pure helpers)
	// =====================================================================

	@Nested
	class BuildAmbiguousMessage {
		@Test
		@DisplayName("buildAmbiguousMessage lists each match and a path hint")
		void listsMatches() {
			java.util.List<String[]> matches = new java.util.ArrayList<>();
			matches.add(new String[] { "form", "art", "solA", null });
			matches.add(new String[] { "relation", "art", "solB", null });
			matches.add(new String[] { "menuitem", "art", "solA", "mainMenu" });

			String result = service.buildAmbiguousMessage("art", matches, "activeSol");
			assertTrue(result.contains("Ambiguous name 'art'"));
			assertTrue(result.contains("form 'art' in 'solA'"));
			assertTrue(result.contains("relation 'art' in 'solB'"));
			assertTrue(result.contains("menuitem 'art' in menu 'mainMenu' (solution 'solA')"));
			assertTrue(result.contains("activeSol/forms/art"));
		}
	}

	@Nested
	class WithSameExtension {
		private java.nio.file.Path invoke(java.nio.file.Path oldFile, java.util.List<String> extensions,
				String solutionName, String newFormName) throws Exception {
			Method m = PersistRenameService.class.getDeclaredMethod("withSameExtension", java.nio.file.Path.class,
					java.util.List.class, String.class, String.class);
			m.setAccessible(true);
			return (java.nio.file.Path) m.invoke(null, oldFile, extensions, solutionName, newFormName);
		}

		@Test
		@DisplayName("withSameExtension keeps the matched extension and null-guards the edge cases")
		void keepsExtensionAndGuards() throws Exception {
			assertEquals(java.nio.file.Paths.get("cy-form", "sol.newForm.spec.cy.ts"),
					invoke(java.nio.file.Paths.get("cy-form", "sol.oldForm.spec.cy.ts"),
							FormSpecGenerator.SPEC_CY_EXTENSIONS, "sol", "newForm"));
			assertEquals(java.nio.file.Paths.get("cy-form", "sol.newForm.spec.cy.js"),
					invoke(java.nio.file.Paths.get("cy-form", "sol.oldForm.spec.cy.js"),
							FormSpecGenerator.SPEC_CY_EXTENSIONS, "sol", "newForm"));
			assertNull(invoke(null, FormSpecGenerator.SPEC_CY_EXTENSIONS, "sol", "newForm"));
			assertNull(invoke(java.nio.file.Paths.get("cy-form", "sol.oldForm.txt"),
					FormSpecGenerator.SPEC_CY_EXTENSIONS, "sol", "newForm"));
			assertNull(invoke(java.nio.file.Paths.get("sol.oldForm.spec.cy.ts"),
					FormSpecGenerator.SPEC_CY_EXTENSIONS, "sol", "newForm"));
		}
	}

	// =====================================================================
	// helpers
	// =====================================================================

	/**
	 * A model whose active project is {@code active}. The active solution is its own
	 * single "module" (so the flattened-project scan yields exactly {@code active}),
	 * and no solution is known by the artifact {@code oldName}s used in these tests,
	 * so the solution-type ambiguity branch does not fire.
	 */
	private static IDeveloperServoyModel modelWith(ServoyProject active, String name) {
		Solution editingSolution = active.getEditingSolution();
		when(editingSolution.getName()).thenReturn(name);
		// ServoyProject.getModules() includes the solution itself as the first element.
		when(active.getModules()).thenReturn(new Solution[] { editingSolution });

		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		when(model.getActiveProject()).thenReturn(active);
		// unknown names (the artifact oldName) -> no project, so no "solution" match
		when(model.getServoyProject(any())).thenReturn(null);
		// the active solution's own name resolves back to the active project (module scan)
		when(model.getServoyProject(name)).thenReturn(active);
		return model;
	}

	/** A solution with no forms/relations/valuelists/menus/medias. */
	private static Solution emptySolution() {
		Solution solution = mock(Solution.class);
		when(solution.getForm(any())).thenReturn(null);
		when(solution.getRelation(any())).thenReturn(null);
		when(solution.getValueList(any())).thenReturn(null);
		when(solution.getMenus(anyBoolean())).thenAnswer(i -> Collections.<Menu> emptyList().iterator());
		when(solution.getMedias(anyBoolean())).thenAnswer(i -> Collections.<Media> emptyList().iterator());
		return solution;
	}

	/** An IFile mock that reports it does not exist. */
	private static IFile nonExistentFile() {
		IFile file = mock(IFile.class);
		when(file.exists()).thenReturn(false);
		return file;
	}

}

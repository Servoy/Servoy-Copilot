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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link PersistDuplicateService}'s
 * <em>own</em> logic - parameter validation, source/destination solution
 * resolution, the source-persist "not found" lookups, the new-name
 * validation/duplicate guards, type dispatch and the result/error strings -
 * with the Servoy model boundary stubbed via Mockito.
 * <p>
 * The service reaches the model through its overridable
 * {@code protected IDeveloperServoyModel getServoyModel()} seam (mirroring
 * {@code ServoyDevServer}/{@code CodeAnalysisService}); this test overrides it
 * to return a mocked {@link IDeveloperServoyModel}. The core objects the model
 * hands back ({@link ServoyProject}, {@link Solution}, {@link Form}, ...) are
 * only stubbed at the boundary so the service's own branches run - their real
 * behaviour is NOT under test here (that belongs in servoy-eclipse).
 * </p>
 * <p>
 * <b>What stays for the integrationpde suite.</b> The actual duplicate build is
 * {@code PersistCloner.intelligentClonePersist(...)} - a static that walks /
 * mutates the real persistence model - and {@code copyFormFiles} touches
 * {@code ResourcesPlugin.getWorkspace()}. These tests therefore stop <em>before</em>
 * a valid new name reaches the cloner: every path driven here returns on a
 * guard (bad param, solution-not-found, persist-not-found, invalid-identifier
 * or duplicate-name) or exercises a pure helper via reflection. A real clone +
 * file copy is the integration test's job.
 * </p>
 *
 * @since 2026.11
 */
@DisplayName("PersistDuplicateService")
public class PersistDuplicateServiceTest
{
	// =======================================================================
	// seam subclass - overrides getServoyModel() to return a supplied mock so
	// the model-side branches run without a live workbench. No production
	// visibility widening: private helpers are reached by reflection.
	// =======================================================================

	/** A {@link PersistDuplicateService} whose model seam returns the given mock. */
	private static PersistDuplicateService serviceWith(IDeveloperServoyModel model)
	{
		return new PersistDuplicateService()
		{
			@Override
			protected IDeveloperServoyModel getServoyModel()
			{
				return model;
			}
		};
	}

	/** A {@link ServoyProject} whose {@code getProject().getName()} is the given name. */
	private static ServoyProject projectNamed(String name)
	{
		ServoyProject project = mock(ServoyProject.class);
		IProject iproject = mock(IProject.class);
		doReturn(name).when(iproject).getName();
		doReturn(iproject).when(project).getProject();
		return project;
	}

	/** Attaches an (empty) editing solution to the project and returns it. */
	private static Solution emptyEditingSolution(ServoyProject project)
	{
		Solution solution = mock(Solution.class);
		doReturn(solution).when(project).getEditingSolution();
		return solution;
	}

	// =======================================================================
	// parameter validation - no model touched
	// =======================================================================

	@Nested
	@DisplayName("Instantiation and API")
	class InstantiationAndApi
	{
		@Test
		@DisplayName("has duplicatePersist method with the expected (String x5) -> String signature")
		void hasDuplicatePersistMethod()
		{
			Method m = assertDoesNotThrow(() -> PersistDuplicateService.class.getMethod("duplicatePersist",
				String.class, String.class, String.class, String.class, String.class));
			assertEquals(String.class, m.getReturnType());
		}
	}

	@Nested
	@DisplayName("Parameter validation")
	class ParameterValidation
	{
		@ParameterizedTest(name = "persistType=''{0}'' is rejected before the model is touched")
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("rejects null, empty or blank persistType")
		void rejectsInvalidPersistType(String persistType)
		{
			// model seam returns null: a failure here would NPE, proving the guard runs first
			String result = serviceWith(null).duplicatePersist(persistType, "myForm", "newForm", null, null);
			assertEquals("Error: persistType is required.", result);
		}

		@ParameterizedTest(name = "name=''{0}'' is rejected before the model is touched")
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("rejects null, empty or blank name")
		void rejectsInvalidName(String name)
		{
			String result = serviceWith(null).duplicatePersist("form", name, "newForm", null, null);
			assertEquals("Error: name is required.", result);
		}
	}

	// =======================================================================
	// solution resolution - model seam exercised
	// =======================================================================

	@Nested
	@DisplayName("Solution resolution")
	class SolutionResolution
	{
		@Test
		@DisplayName("a named source solution that the model does not know reports it as not found (active label omitted)")
		void sourceSolutionNotFound()
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(null).when(model).getServoyProject("missing");

			String result = serviceWith(model).duplicatePersist("form", "f", "f_copy", "missing", null);

			assertEquals("Error: Solution 'missing' not found.", result);
		}

		@Test
		@DisplayName("a null source solution falls back to the active project and reports 'active' when there is none")
		void activeSolutionNotFound()
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(null).when(model).getActiveProject();

			String result = serviceWith(model).duplicatePersist("form", "f", "f_copy", null, null);

			assertEquals("Error: Solution 'active' not found.", result);
		}

		@Test
		@DisplayName("a known source but unknown explicit destination solution reports the destination as not found")
		void destinationSolutionNotFound()
		{
			ServoyProject source = projectNamed("src");
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(source).when(model).getServoyProject("src");
			doReturn(null).when(model).getServoyProject("destMissing");

			String result = serviceWith(model).duplicatePersist("form", "f", "f_copy", "src", "destMissing");

			assertEquals("Error: Solution 'destMissing' not found.", result);
		}
	}

	// =======================================================================
	// type dispatch + source-persist not-found - model seam exercised
	// =======================================================================

	@Nested
	@DisplayName("Type dispatch and source-persist lookup")
	class TypeDispatchAndLookup
	{
		/** A model whose active project has an empty editing solution (nothing found). */
		private IDeveloperServoyModel modelWithEmptyActiveSolution()
		{
			ServoyProject project = projectNamed("mySolution");
			emptyEditingSolution(project);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			return model;
		}

		@ParameterizedTest(name = "unsupported persistType=''{0}''")
		@ValueSource(strings = { "unknown", "script", "calculation", "aggregate", "datasource" })
		@DisplayName("returns the unsupported-type error for a persistType the switch does not handle")
		void rejectsUnsupportedPersistType(String persistType)
		{
			String result = modelServerDuplicate(persistType, "something", "something_copy");
			assertAll(() -> assertTrue(result.startsWith("Error: Unsupported persistType '" + persistType + "'"),
				"got: " + result),
				() -> assertTrue(result.contains("Supported: form, relation, valuelist, media."), "got: " + result));
		}

		@Test
		@DisplayName("form dispatch with an absent form reports it as not found in the named solution")
		void formNotFound()
		{
			String result = modelServerDuplicate("form", "myForm", "myForm_copy");
			assertEquals("Error: Form 'myForm' not found in solution 'mySolution'.", result);
		}

		@Test
		@DisplayName("relation dispatch with an absent relation reports it as not found")
		void relationNotFound()
		{
			String result = modelServerDuplicate("relation", "myRelation", "myRelation_copy");
			assertEquals("Error: Relation 'myRelation' not found in solution 'mySolution'.", result);
		}

		@Test
		@DisplayName("valuelist dispatch with an absent valuelist reports it as not found")
		void valueListNotFound()
		{
			String result = modelServerDuplicate("valuelist", "myVL", "myVL_copy");
			assertEquals("Error: ValueList 'myVL' not found in solution 'mySolution'.", result);
		}

		@Test
		@DisplayName("media dispatch with no matching media reports it as not found")
		void mediaNotFound()
		{
			IDeveloperServoyModel model = modelWithEmptyActiveSolution();
			Solution solution = model.getActiveProject().getEditingSolution();
			doReturn(java.util.Collections.<com.servoy.j2db.persistence.Media> emptyIterator())
				.when(solution).getMedias(false);

			String result = serviceWith(model).duplicatePersist("media", "myMedia", "myMedia_copy", null, null);

			assertEquals("Error: Media 'myMedia' not found in solution 'mySolution'.", result);
		}

		@Test
		@DisplayName("persistType is matched case-insensitively (FORM dispatches to the form handler, not 'unsupported')")
		void persistTypeCaseInsensitive()
		{
			String result = modelServerDuplicate("FORM", "myForm", "myForm_copy");
			assertEquals("Error: Form 'myForm' not found in solution 'mySolution'.", result);
			assertFalse(result.contains("Unsupported"), "FORM must not be treated as unsupported; got: " + result);
		}

		/** Runs duplicatePersist against an empty active solution named 'mySolution'. */
		private String modelServerDuplicate(String persistType, String name, String newName)
		{
			return serviceWith(modelWithEmptyActiveSolution()).duplicatePersist(persistType, name, newName, null, null);
		}
	}

	// =======================================================================
	// new-name validation + duplicate guard - stops BEFORE PersistCloner
	// =======================================================================

	@Nested
	@DisplayName("New-name validation and duplicate guard")
	class NewNameValidationAndDuplicateGuard
	{
		/**
		 * A model whose active project 'mySolution' has an editing solution that
		 * returns {@code form} for {@code getForm(sourceName)}, so dispatch reaches
		 * the name-resolution step for a present source form.
		 */
		private IDeveloperServoyModel modelWithForm(String sourceName, Form form)
		{
			ServoyProject project = projectNamed("mySolution");
			Solution solution = emptyEditingSolution(project);
			doReturn(form).when(solution).getForm(sourceName);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			return model;
		}

		@ParameterizedTest(name = "invalid identifier newName=''{0}'' is rejected before cloning")
		@ValueSource(strings = { "has space", "123start", "special@char", "with-dash" })
		@DisplayName("rejects a non-identifier explicit newName for a non-media persist")
		void rejectsInvalidIdentifierNewName(String invalidName)
		{
			Form form = mock(Form.class);
			IDeveloperServoyModel model = modelWithForm("src", form);

			String result = serviceWith(model).duplicatePersist("form", "src", invalidName, null, null);

			assertAll(() -> assertTrue(result.startsWith("Error: Invalid name '" + invalidName + "'"), "got: " + result),
				() -> assertTrue(result.contains("must be a valid identifier"), "got: " + result));
		}

		@Test
		@DisplayName("a duplicate explicit newName (already present in the destination) is rejected before cloning")
		void rejectsDuplicateNewName()
		{
			Form sourceForm = mock(Form.class);
			ServoyProject project = projectNamed("mySolution");
			Solution solution = emptyEditingSolution(project);
			doReturn(sourceForm).when(solution).getForm("src");
			// the destination (== source project here) already has a form by the requested new name
			doReturn(mock(Form.class)).when(solution).getForm("taken");
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();

			String result = serviceWith(model).duplicatePersist("form", "src", "taken", null, null);

			assertEquals("Error: A form named 'taken' already exists.", result);
		}
	}

	// =======================================================================
	// pure helpers via reflection (no model)
	// =======================================================================

	@Nested
	@DisplayName("resolveNewName helper")
	class ResolveNewNameHelper
	{
		private Method resolveNewName() throws Exception
		{
			Method m = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class, String.class,
				String.class, ServoyProject.class);
			m.setAccessible(true);
			return m;
		}

		@ParameterizedTest(name = "invalid identifier ''{0}'' is rejected for a non-media type")
		@ValueSource(strings = { "has space", "123start", "special@char" })
		@DisplayName("rejects non-identifier names for non-media persists without touching the project")
		void rejectsInvalidIdentifiers(String invalidName) throws Exception
		{
			// project is null: the identifier guard returns before any project lookup
			String result = (String) resolveNewName().invoke(serviceWith(null), invalidName, "original", "form", null);
			assertTrue(result.startsWith("Error: Invalid name"), "got: " + result);
		}

		@ParameterizedTest(name = "media name ''{0}'' is NOT identifier-checked")
		@ValueSource(strings = { "my image.png", "logo-v2.svg" })
		@DisplayName("skips the identifier check for media and proceeds to the duplicate lookup")
		void mediaSkipsIdentifierCheck(String mediaName) throws Exception
		{
			ServoyProject project = projectNamed("mySolution");
			Solution solution = emptyEditingSolution(project);
			doReturn(java.util.Collections.<com.servoy.j2db.persistence.Media> emptyIterator())
				.when(solution).getMedias(false);

			String result = (String) resolveNewName().invoke(serviceWith(null), mediaName, "orig", "media", project);

			// not rejected as an invalid identifier; the (unique) media name is accepted and trimmed
			assertFalse(result.startsWith("Error:"), "media name must not be identifier-rejected; got: " + result);
			assertEquals(mediaName, result);
		}

		@Test
		@DisplayName("a valid explicit name is trimmed when it is unique in the destination")
		void trimsUniqueValidName() throws Exception
		{
			ServoyProject project = projectNamed("mySolution");
			emptyEditingSolution(project); // getForm(..) returns null => unique

			String result = (String) resolveNewName().invoke(serviceWith(null), "  validName  ", "orig", "form", project);

			assertEquals("validName", result);
		}

		@Test
		@DisplayName("a blank newName generates the '<original>_copy' candidate when it is free")
		void generatesCopySuffixWhenFree() throws Exception
		{
			ServoyProject project = projectNamed("mySolution");
			emptyEditingSolution(project); // nothing exists => first candidate is free

			String result = (String) resolveNewName().invoke(serviceWith(null), "   ", "myForm", "form", project);

			assertEquals("myForm_copy", result);
		}

		@Test
		@DisplayName("a blank newName falls through to '<original>_copy2' when '<original>_copy' is taken")
		void generatesNumberedCopyWhenFirstTaken() throws Exception
		{
			ServoyProject project = projectNamed("mySolution");
			Solution solution = emptyEditingSolution(project);
			doReturn(mock(Form.class)).when(solution).getForm("myForm_copy"); // first candidate taken
			// myForm_copy2 is free (unstubbed getForm returns null)

			String result = (String) resolveNewName().invoke(serviceWith(null), null, "myForm", "form", project);

			assertEquals("myForm_copy2", result);
		}
	}

	@Nested
	@DisplayName("checkNameExists helper")
	class CheckNameExistsHelper
	{
		private Method checkNameExists() throws Exception
		{
			Method m = PersistDuplicateService.class.getDeclaredMethod("checkNameExists", String.class, String.class,
				ServoyProject.class);
			m.setAccessible(true);
			return m;
		}

		@Test
		@DisplayName("returns null (no clash) when the destination has no editing solution")
		void nullEditingSolutionIsNoClash() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			doReturn(null).when(project).getEditingSolution();

			Object result = checkNameExists().invoke(serviceWith(null), "anyName", "form", project);

			assertNotNull(project);
			assertEquals(null, result, "a missing editing solution counts as 'not existing'");
		}

		@Test
		@DisplayName("reports a clash for a form the destination already has")
		void reportsFormClash() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			doReturn(solution).when(project).getEditingSolution();
			doReturn(mock(Form.class)).when(solution).getForm("dup");

			Object result = checkNameExists().invoke(serviceWith(null), "dup", "form", project);

			assertEquals("Error: A form named 'dup' already exists.", result);
		}

		@Test
		@DisplayName("reports a clash for a relation the destination already has")
		void reportsRelationClash() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			doReturn(solution).when(project).getEditingSolution();
			doReturn(mock(Relation.class)).when(solution).getRelation("dup");

			Object result = checkNameExists().invoke(serviceWith(null), "dup", "relation", project);

			assertEquals("Error: A relation named 'dup' already exists.", result);
		}

		@Test
		@DisplayName("reports a clash for a valuelist the destination already has")
		void reportsValueListClash() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			doReturn(solution).when(project).getEditingSolution();
			doReturn(mock(ValueList.class)).when(solution).getValueList("dup");

			Object result = checkNameExists().invoke(serviceWith(null), "dup", "valuelist", project);

			assertEquals("Error: A valuelist named 'dup' already exists.", result);
		}

		@Test
		@DisplayName("returns null for an unknown persistType (default switch branch -> exists=false)")
		void unknownTypeIsNoClash() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			doReturn(solution).when(project).getEditingSolution();

			Object result = checkNameExists().invoke(serviceWith(null), "whatever", "bogusType", project);

			assertEquals(null, result);
		}
	}

	@Nested
	@DisplayName("findMedia helper")
	class FindMediaHelper
	{
		private Method findMedia() throws Exception
		{
			Method m = PersistDuplicateService.class.getDeclaredMethod("findMedia", Solution.class, String.class);
			m.setAccessible(true);
			return m;
		}

		@Test
		@DisplayName("returns the media whose name matches and null when none match")
		void matchesByName() throws Exception
		{
			com.servoy.j2db.persistence.Media wanted = mock(com.servoy.j2db.persistence.Media.class);
			doReturn("logo.png").when(wanted).getName();
			com.servoy.j2db.persistence.Media other = mock(com.servoy.j2db.persistence.Media.class);
			doReturn("other.png").when(other).getName();
			Solution solution = mock(Solution.class);
			doReturn(java.util.List.of(other, wanted).iterator()).when(solution).getMedias(false);

			Object hit = findMedia().invoke(serviceWith(null), solution, "logo.png");
			assertEquals(wanted, hit);

			Solution solution2 = mock(Solution.class);
			doReturn(java.util.List.of(other).iterator()).when(solution2).getMedias(false);
			Object miss = findMedia().invoke(serviceWith(null), solution2, "logo.png");
			assertEquals(null, miss);
		}
	}

	// =======================================================================
	// JSON formatting helpers (no model)
	// =======================================================================

	@Nested
	@DisplayName("JSON response helpers")
	class JsonResponseHelpers
	{
		@Test
		@DisplayName("successJson returns the expected ok envelope")
		void successJsonFormat() throws Exception
		{
			Method successJson = PersistDuplicateService.class.getDeclaredMethod("successJson", String.class,
				String.class, String.class);
			successJson.setAccessible(true);

			String result = (String) successJson.invoke(serviceWith(null), "myForm_copy", "form", "mySolution");
			assertEquals(
				"{\"status\":\"ok\",\"duplicated\":\"myForm_copy\",\"persistType\":\"form\",\"solution\":\"mySolution\"}",
				result);
		}

		@Test
		@DisplayName("escapeJson escapes backslashes and quotes and maps null to the empty string")
		void escapeJsonHandlesSpecialChars() throws Exception
		{
			Method escapeJson = PersistDuplicateService.class.getDeclaredMethod("escapeJson", String.class);
			escapeJson.setAccessible(true);
			PersistDuplicateService svc = serviceWith(null);

			assertAll(() -> assertEquals("", escapeJson.invoke(svc, (String) null), "null -> empty"),
				() -> assertEquals("simple", escapeJson.invoke(svc, "simple"), "no-op for simple string"),
				() -> assertEquals("with\\\\slash", escapeJson.invoke(svc, "with\\slash"), "escapes backslash"),
				() -> assertEquals("with\\\"quote", escapeJson.invoke(svc, "with\"quote"), "escapes quote"));
		}
	}
}

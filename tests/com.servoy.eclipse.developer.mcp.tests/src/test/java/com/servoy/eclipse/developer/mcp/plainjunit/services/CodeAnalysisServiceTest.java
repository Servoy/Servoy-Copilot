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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.CodeAnalysisService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit unit tests for {@link CodeAnalysisService}'s <em>own</em> logic -
 * the active-solution guards, element-name resolution, {@code maxDepth} parsing
 * and the "not found/could not resolve" result messages - with the Servoy model
 * boundary stubbed via Mockito.
 * <p>
 * The service obtains the model through its {@code getServoyModel()} seam, which
 * this test overrides with a mock {@link IDeveloperServoyModel}. The Servoy core
 * that the model exposes ({@link ServoyProject}, {@link Solution}, ...) is only
 * stubbed to return empty/null at the boundary so the service's own branches are
 * exercised - the core's behaviour itself is NOT under test here (that belongs in
 * servoy-eclipse). Everything that actually walks the model/DLTK/workspace (the
 * reference search, hierarchy traversal, quick-fix application) stays for the
 * integration tests.
 * </p>
 *
 * @since 2026.06
 */
class CodeAnalysisServiceTest {
	/** A {@link CodeAnalysisService} whose model seam returns the supplied mock. */
	private static CodeAnalysisService serviceWith(IDeveloperServoyModel model) {
		return new CodeAnalysisService() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	/** A model with an active project + editing solution, no modules, no forms/methods. */
	private static IDeveloperServoyModel modelWithEmptyActiveSolution() {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyProject project = mock(ServoyProject.class);
		Solution solution = mock(Solution.class);
		when(model.getActiveProject()).thenReturn(project);
		when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
		when(project.getEditingSolution()).thenReturn(solution);
		// Unstubbed getScriptMethod/getScriptVariable/getForm return null by default;
		// getForms must yield an (empty) iterator so collectAllForms does not NPE.
		when(solution.getForms(null, false)).thenReturn(Collections.<Form> emptyList().iterator());
		return model;
	}

	@Nested
	class ActiveSolutionGuard {
		@Test
		@DisplayName("findReferences reports no active solution when there is no active project")
		void findReferencesNoActiveProject() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			String result = serviceWith(model).findReferences("forms.myForm", "doIt");
			assertEquals("No active solution. Open a Servoy solution first.", result);
		}

		@Test
		@DisplayName("findReferences reports no active solution when the active project has no editing solution")
		void findReferencesNoEditingSolution() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getEditingSolution()).thenReturn(null);
			String result = serviceWith(model).findReferences("forms.myForm", "doIt");
			assertEquals("No active solution. Open a Servoy solution first.", result);
		}

		@Test
		@DisplayName("getTypeHierarchy and getMethodCallHierarchy share the no-active-solution guard")
		void otherToolsGuard() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			CodeAnalysisService service = serviceWith(model);
			assertEquals("No active solution. Open a Servoy solution first.", service.getTypeHierarchy("myForm"));
			assertEquals("No active solution. Open a Servoy solution first.",
					service.getMethodCallHierarchy("forms.myForm", "doIt", null, "3"));
		}
	}

	@Nested
	class FindReferencesOwnLogic {
		@Test
		@DisplayName("findReferences with no elementName asks for one")
		void noElementName() {
			CodeAnalysisService service = serviceWith(modelWithEmptyActiveSolution());
			String result = service.findReferences("forms.myForm", null);
			assertTrue(result.startsWith("No elementName provided."), "got: " + result);
			assertEquals(result, service.findReferences("forms.myForm", "   "),
					"blank elementName behaves the same as null");
		}

		@Test
		@DisplayName("findReferences reports 'could not resolve' when the element is not found in the model")
		void couldNotResolve() {
			CodeAnalysisService service = serviceWith(modelWithEmptyActiveSolution());
			String result = service.findReferences("forms.myForm", "doIt");
			assertTrue(result.startsWith("Could not resolve 'doIt' in 'forms.myForm'."), "got: " + result);
			assertTrue(result.contains("across 1 project(s)"),
					"active solution with no modules counts as 1 project; got: " + result);
		}
	}

	@Nested
	class MethodCallHierarchyParsing {
		@Test
		@DisplayName("getMethodCallHierarchy parses maxDepth and falls back to the could-not-resolve message")
		void maxDepthVariants() {
			CodeAnalysisService service = serviceWith(modelWithEmptyActiveSolution());
			// Valid, invalid (ignored -> default) and null maxDepth all reach the
			// unresolved-method message without throwing.
			for (String maxDepth : new String[] { "2", "abc", null, "  " }) {
				String result = service.getMethodCallHierarchy("forms.myForm", "doIt", null, maxDepth);
				assertTrue(result.startsWith("Could not resolve method 'doIt' in 'forms.myForm'."),
						"maxDepth=" + maxDepth + " got: " + result);
			}
		}
	}

	@Nested
	class TypeHierarchyOwnLogic {
		@Test
		@DisplayName("getTypeHierarchy reports a form that is not in the model as not found")
		void formNotFound() {
			CodeAnalysisService service = serviceWith(modelWithEmptyActiveSolution());
			String result = service.getTypeHierarchy("forms.myForm");
			assertEquals("Form 'myForm' not found in the active solution or its modules.", result);
		}
	}

	@Nested
	class ResolveSimpleName {
		private String resolveSimpleName(String name) throws Exception {
			Method m = CodeAnalysisService.class.getDeclaredMethod("resolveSimpleName", String.class);
			m.setAccessible(true);
			return (String) m.invoke(null, name);
		}

		@Test
		@DisplayName("resolveSimpleName strips only the forms./scopes. prefixes and trims")
		void stripsKnownPrefixes() throws Exception {
			assertEquals("", resolveSimpleName(null));
			assertEquals("myForm", resolveSimpleName("forms.myForm"));
			assertEquals("globals", resolveSimpleName("scopes.globals"));
			assertEquals("myForm", resolveSimpleName("  forms.myForm  "));
			// An unknown prefix / plain name is returned as-is (trimmed), NOT misrouted.
			assertEquals("myForm.myMethod", resolveSimpleName("forms.myForm.myMethod"));
			assertEquals("plain", resolveSimpleName("plain"));
		}
	}
}

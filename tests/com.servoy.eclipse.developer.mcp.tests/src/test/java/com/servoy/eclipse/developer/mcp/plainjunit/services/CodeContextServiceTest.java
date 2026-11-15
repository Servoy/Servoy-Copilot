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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;

import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.ParameterKind;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.dto.IdentifierContext.IdentifierKind;
import com.servoy.eclipse.developer.mcp.services.CodeContextService;
import com.servoy.j2db.documentation.IFunctionDocumentation;
import com.servoy.j2db.documentation.IParameterDocumentation;

/**
 * Plain-JUnit unit tests for {@link CodeContextService}'s <em>pure</em>
 * formatter/mapper helpers - the string-to-kind mapping, the class-name-to-
 * scripting-name mapping, the solution-function doc cleaner, the signature and
 * JSDoc formatting over a mocked {@link IFunctionDocumentation}, and the web
 * object method/property formatting over mocked DLTK
 * {@link org.eclipse.dltk.javascript.typeinfo.model.Method}/{@link Property}.
 * <p>
 * Every helper under test takes its inputs as parameters (never via the static
 * Servoy model), so <b>no production seam is needed and no production code is
 * changed</b> - the collaborators that cannot be constructed are Mockito mocks
 * stubbed only at the boundary. The DLTK/model-traversal entry points
 * ({@code getCodeContext}, {@code extractIdentifierContext},
 * {@code locateModelElement}, the inferencer-visitor paths) need real DLTK/JSNode
 * and stay for the integration tests.
 * </p>
 * <p>
 * The private helpers are reached by reflection, mirroring the pilot
 * {@code CodeAnalysisServiceTest} style.
 * </p>
 *
 * @since 2026.06
 */
class CodeContextServiceTest {
	private static final CodeContextService SERVICE = new CodeContextService();

	private static Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
		Method m = CodeContextService.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(SERVICE, args);
	}

	@Nested
	class DetermineIdentifierKind {
		private IdentifierKind kind(String typeName) throws Exception {
			return (IdentifierKind) invokePrivate("determineIdentifierKind", new Class<?>[] { String.class }, typeName);
		}

		@Test
		@DisplayName("web component / web service prefixes map to their kinds")
		void prefixes() throws Exception {
			assertEquals(IdentifierKind.WEB_COMPONENT, kind("RuntimeWebComponent<bootstrapcomponents-button>"));
			assertEquals(IdentifierKind.WEB_SERVICE, kind("WebService<servoydefault-foundset>"));
		}

		@Test
		@DisplayName("the exact type 'Function' is a solution function, everything else is the Servoy API")
		void functionAndDefault() throws Exception {
			assertEquals(IdentifierKind.SOLUTION_FUNCTION, kind("Function"));
			assertEquals(IdentifierKind.SERVOY_API, kind("JSApplication"));
			assertEquals(IdentifierKind.SERVOY_API, kind("Anything"));
			// A substring match is NOT enough - only the exact "Function" is a solution function.
			assertEquals(IdentifierKind.SERVOY_API, kind("FunctionLike"));
		}
	}

	@Nested
	class MapClassNameToScriptingName {
		private String map(String className) throws Exception {
			return (String) invokePrivate("mapClassNameToScriptingName", new Class<?>[] { String.class }, className);
		}

		@Test
		@DisplayName("known JS* class names map to their scripting names")
		void knownNames() throws Exception {
			assertEquals("application", map("JSApplication"));
			assertEquals("databaseManager", map("JSDatabaseManager"));
			assertEquals("security", map("JSSecurity"));
			assertEquals("i18n", map("JSI18N"));
			assertEquals("utils", map("JSUtils"));
			assertEquals("controller", map("JSForm"));
			assertEquals("eventsManager", map("JSEventsManager"));
			assertEquals("solutionModel", map("JSSolutionModel"));
		}

		@Test
		@DisplayName("an unknown class name and null map to null")
		void unknownAndNull() throws Exception {
			assertEquals(null, map("JSUnknown"));
			assertEquals(null, map(null));
		}
	}

	@Nested
	class FormatSolutionFunctionDoc {
		private String format(String doc) throws Exception {
			return (String) invokePrivate("formatSolutionFunctionDoc", new Class<?>[] { String.class }, doc);
		}

		@Test
		@DisplayName("null and blank input yield the empty string")
		void nullAndBlank() throws Exception {
			assertEquals("", format(null));
			assertEquals("", format("   "));
			assertEquals("", format("\n\n  \n"));
		}

		@Test
		@DisplayName("@properties lines and blank lines are dropped, remaining lines are trimmed and joined")
		void dropsPropertiesAndBlanks() throws Exception {
			String doc = "  First line  \n@properties=foo,bar\n\n  Second line\n";
			assertEquals("First line\nSecond line", format(doc));
		}

		@Test
		@DisplayName("a doc of only @properties / blank lines collapses to empty")
		void onlyProperties() throws Exception {
			assertEquals("", format("@properties=a\n\n   \n@properties=b"));
		}
	}

	@Nested
	class ContainsIdentifier {
		private boolean contains(String[] filters, String name) throws Exception {
			return (boolean) invokePrivate("containsIdentifier", new Class<?>[] { String[].class, String.class }, filters, name);
		}

		@Test
		@DisplayName("null filter array or null identifier name is never a match")
		void nullArgs() throws Exception {
			assertFalse(contains(null, "foo"));
			assertFalse(contains(new String[] { "foo" }, null));
		}

		@Test
		@DisplayName("an exact filter entry matches")
		void exactMatch() throws Exception {
			assertTrue(contains(new String[] { "application", "utils" }, "application"));
			assertFalse(contains(new String[] { "application" }, "utils"));
		}

		@Test
		@DisplayName("the base of a dotted filter matches the bare identifier")
		void dottedBaseMatches() throws Exception {
			// filter "application.output" -> base "application" matches identifier "application"
			assertTrue(contains(new String[] { "application.output" }, "application"));
		}

		@Test
		@DisplayName("a filter that is the identifier followed by a dot matches")
		void identifierPrefixMatches() throws Exception {
			// identifier "application" matches filter "application.output" via the startsWith branch too
			assertTrue(contains(new String[] { "application.output.extra" }, "application"));
		}

		@Test
		@DisplayName("null entries inside the filter array are skipped and no false match is produced")
		void skipsNullEntriesAndNoMatch() throws Exception {
			assertFalse(contains(new String[] { null, "other" }, "application"));
		}
	}

	@Nested
	class ExtractSignature {
		private String extract(IFunctionDocumentation fdoc) throws Exception {
			return (String) invokePrivate("extractSignature", new Class<?>[] { IFunctionDocumentation.class }, fdoc);
		}

		@Test
		@DisplayName("null documentation yields the empty string")
		void nullDoc() throws Exception {
			assertEquals("", extract(null));
		}

		@Test
		@DisplayName("the signature is the part after the first space of the full JS signature")
		void splitsAfterReturnType() throws Exception {
			IFunctionDocumentation fdoc = mock(IFunctionDocumentation.class);
			when(fdoc.getFullJSTranslatedSignature(true, false)).thenReturn("String getValue(key)");
			assertEquals("getValue(key)", extract(fdoc));
		}

		@Test
		@DisplayName("a signature with no space falls back to mainName()")
		void fallbackToMainName() throws Exception {
			IFunctionDocumentation fdoc = mock(IFunctionDocumentation.class);
			when(fdoc.getFullJSTranslatedSignature(true, false)).thenReturn("doIt()");
			when(fdoc.getMainName()).thenReturn("doIt");
			assertEquals("doIt()", extract(fdoc));
		}

		@Test
		@DisplayName("a null full signature falls back to mainName()")
		void nullSignatureFallsBack() throws Exception {
			IFunctionDocumentation fdoc = mock(IFunctionDocumentation.class);
			when(fdoc.getFullJSTranslatedSignature(true, false)).thenReturn(null);
			when(fdoc.getMainName()).thenReturn("doIt");
			assertEquals("doIt()", extract(fdoc));
		}
	}

	@Nested
	class FormatFunctionSignatureAndDocs {
		private String format(IFunctionDocumentation fdoc, int mandatory, String id) throws Exception {
			return (String) invokePrivate("formatFunctionSignatureAndDocs",
					new Class<?>[] { IFunctionDocumentation.class, int.class, String.class }, fdoc, mandatory, id);
		}

		/** A minimal function doc: not deprecated, no return, no parameters, no sample. */
		private IFunctionDocumentation bareDoc() {
			IFunctionDocumentation fdoc = mock(IFunctionDocumentation.class);
			when(fdoc.isDeprecated()).thenReturn(false);
			when(fdoc.getReturnedType()).thenReturn(null);
			when(fdoc.getArguments()).thenReturn(new LinkedHashMap<String, IParameterDocumentation>());
			return fdoc;
		}

		@Test
		@DisplayName("null documentation yields the empty string")
		void nullDoc() throws Exception {
			assertEquals("", format(null, 0, "x"));
		}

		@Test
		@DisplayName("the tooltip description is emitted as-is")
		void tooltipOnly() throws Exception {
			IFunctionDocumentation fdoc = bareDoc();
			when(fdoc.getDescription(org.mockito.ArgumentMatchers.any())).thenReturn("Does a thing.");
			assertEquals("Does a thing.", format(fdoc, 0, "x"));
		}

		@Test
		@DisplayName("a deprecated function is prefixed with @deprecated plus its text")
		void deprecated() throws Exception {
			IFunctionDocumentation fdoc = bareDoc();
			when(fdoc.isDeprecated()).thenReturn(true);
			when(fdoc.getDeprecatedText()).thenReturn("use foo() instead");
			String result = format(fdoc, 0, "x");
			assertTrue(result.startsWith("@deprecated use foo() instead\n"), "got: " + result);
		}

		@Test
		@DisplayName("a deprecated function with no text still emits the @deprecated marker")
		void deprecatedNoText() throws Exception {
			IFunctionDocumentation fdoc = bareDoc();
			when(fdoc.isDeprecated()).thenReturn(true);
			when(fdoc.getDeprecatedText()).thenReturn(null);
			String result = format(fdoc, 0, "x");
			assertTrue(result.startsWith("@deprecated\n"), "got: " + result);
		}

		@Test
		@DisplayName("a non-blank sample is emitted under an @sample heading")
		void sample() throws Exception {
			IFunctionDocumentation fdoc = bareDoc();
			when(fdoc.getSample(org.mockito.ArgumentMatchers.any())).thenReturn("x.foo();");
			String result = format(fdoc, 0, "x");
			assertTrue(result.contains("\n@sample\nx.foo();"), "got: " + result);
		}

		@Test
		@DisplayName("a parameter is rendered with its name, and optional params past the mandatory count are bracketed")
		void parametersOptionalMarking() throws Exception {
			IFunctionDocumentation fdoc = bareDoc();
			IParameterDocumentation p0 = mock(IParameterDocumentation.class);
			when(p0.getName()).thenReturn("key");
			when(p0.getType()).thenReturn((Class) String.class);
			when(p0.getDescription()).thenReturn("the key");
			IParameterDocumentation p1 = mock(IParameterDocumentation.class);
			when(p1.getName()).thenReturn("flag");
			when(p1.getType()).thenReturn((Class) Boolean.class);
			when(p1.getDescription()).thenReturn(null);

			LinkedHashMap<String, IParameterDocumentation> params = new LinkedHashMap<>();
			params.put("key", p0);
			params.put("flag", p1);
			when(fdoc.getArguments()).thenReturn(params);

			// mandatory = 1 -> first param plain, second param bracketed optional
			String result = format(fdoc, 1, "x");
			assertTrue(result.contains("@param {"), "got: " + result);
			assertTrue(result.contains("} key the key"), "mandatory param plain; got: " + result);
			assertTrue(result.contains("[flag] optional"), "optional param bracketed; got: " + result);
		}
	}

	@Nested
	class FormatWebObjectMethod {
		private String format(org.eclipse.dltk.javascript.typeinfo.model.Method method, String id) throws Exception {
			return (String) invokePrivate("formatWebObjectMethod",
					new Class<?>[] { org.eclipse.dltk.javascript.typeinfo.model.Method.class, String.class }, method, id);
		}

		private Parameter param(String name, ParameterKind kind) {
			Parameter p = mock(Parameter.class);
			when(p.getName()).thenReturn(name);
			when(p.getKind()).thenReturn(kind);
			return p;
		}

		@Test
		@DisplayName("null method yields the empty string")
		void nullMethod() throws Exception {
			assertEquals("", format(null, "elem"));
		}

		@Test
		@DisplayName("a no-arg method renders as id.name() with no trailing description")
		void noArgsNoDescription() throws Exception {
			org.eclipse.dltk.javascript.typeinfo.model.Method method = mock(org.eclipse.dltk.javascript.typeinfo.model.Method.class);
			when(method.getName()).thenReturn("doIt");
			when(method.getParameters()).thenReturn(emptyEList());
			when(method.getDescription()).thenReturn(null);
			assertEquals("elem.doIt()", format(method, "elem"));
		}

		@Test
		@DisplayName("parameters are comma-separated and optional ones get a '?' suffix; a description is appended")
		void paramsAndOptionalAndDescription() throws Exception {
			// Build the (stubbed) parameter mocks BEFORE the when(...) call - creating/
			// stubbing a mock inside a thenReturn argument trips Mockito's
			// UnfinishedStubbingException.
			Parameter a = param("a", ParameterKind.NORMAL);
			Parameter b = param("b", ParameterKind.OPTIONAL);
			org.eclipse.dltk.javascript.typeinfo.model.Method method = mock(org.eclipse.dltk.javascript.typeinfo.model.Method.class);
			when(method.getName()).thenReturn("doIt");
			when(method.getParameters()).thenReturn(eListOf(a, b));
			when(method.getDescription()).thenReturn("does it");
			assertEquals("elem.doIt(a, b?)\ndoes it", format(method, "elem"));
		}

		@Test
		@DisplayName("a blank description is not appended")
		void blankDescriptionIgnored() throws Exception {
			org.eclipse.dltk.javascript.typeinfo.model.Method method = mock(org.eclipse.dltk.javascript.typeinfo.model.Method.class);
			when(method.getName()).thenReturn("doIt");
			when(method.getParameters()).thenReturn(emptyEList());
			when(method.getDescription()).thenReturn("   ");
			assertEquals("elem.doIt()", format(method, "elem"));
		}
	}

	@Nested
	class FormatWebObjectProperty {
		private String format(Property property, String id) throws Exception {
			return (String) invokePrivate("formatWebObjectProperty",
					new Class<?>[] { Property.class, String.class }, property, id);
		}

		@Test
		@DisplayName("null property yields the empty string")
		void nullProperty() throws Exception {
			assertEquals("", format(null, "elem"));
		}

		@Test
		@DisplayName("a property with no description renders as id.name")
		void noDescription() throws Exception {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getDescription()).thenReturn(null);
			assertEquals("elem.size", format(property, "elem"));
		}

		@Test
		@DisplayName("a property description is appended on a new line")
		void withDescription() throws Exception {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getDescription()).thenReturn("the size");
			assertEquals("elem.size\nthe size", format(property, "elem"));
		}
	}

	// ------------------------------------------------------------------
	// EList helpers for the DLTK Method.getParameters() return type
	// ------------------------------------------------------------------

	private static org.eclipse.emf.common.util.EList<Parameter> emptyEList() {
		return new org.eclipse.emf.common.util.BasicEList<>();
	}

	private static org.eclipse.emf.common.util.EList<Parameter> eListOf(Parameter... params) {
		org.eclipse.emf.common.util.BasicEList<Parameter> list = new org.eclipse.emf.common.util.BasicEList<>();
		list.addAll(Arrays.asList(params));
		return list;
	}
}

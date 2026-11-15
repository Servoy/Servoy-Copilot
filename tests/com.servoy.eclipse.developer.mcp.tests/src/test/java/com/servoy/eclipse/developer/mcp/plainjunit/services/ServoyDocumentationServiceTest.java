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

import java.util.Arrays;

import org.eclipse.dltk.javascript.typeinfo.model.JSType;
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.ServoyDocumentationService;

/**
 * Plain-JUnit unit tests for {@link ServoyDocumentationService}'s <em>pure</em>
 * formatter/mapper helpers - the class-name-to-scriptingName mapping, the
 * lightweight member-signature formatting, and the full member-documentation
 * formatting over mocked DLTK {@link Method}/{@link Property}/{@link Parameter}
 * /{@link Type}.
 * <p>
 * Every method under test takes its inputs as parameters (never via the static
 * Servoy model), so <b>no production seam is needed and no production code is
 * changed</b> - the DLTK collaborators that cannot be constructed are Mockito
 * mocks stubbed only at the boundary (the same approach proven by the sibling
 * {@code CodeContextServiceTest}). This class has no DLTK/type-registry
 * traversal of its own: the registry walk that <em>produces</em> these
 * {@link Member} objects lives in the MCP server wrapper and stays for the
 * integration tests.
 * </p>
 * <p>
 * All three methods are public, so they are invoked directly (no reflection
 * needed). {@link Parameter}'s {@code getName()}/{@code getType()} and
 * {@link Member}'s {@code getName()}/{@code getDescription()}/
 * {@code isDeprecated()}/{@code getType()} are inherited from the DLTK
 * super-interfaces; Mockito stubs them on the concrete mock.
 * </p>
 *
 * @since 2026.06
 */
class ServoyDocumentationServiceTest {
	private static final ServoyDocumentationService SERVICE = new ServoyDocumentationService();

	// ------------------------------------------------------------------
	// Mock-building helpers
	// ------------------------------------------------------------------

	/** A DLTK {@link JSType} whose {@code getName()} returns the given name. */
	private static JSType type(String name) {
		JSType t = mock(JSType.class);
		when(t.getName()).thenReturn(name);
		return t;
	}

	/** A {@link Parameter} with a name and an optional type (null type = untyped). */
	private static Parameter param(String name, String typeName) {
		// Build the (possibly nested) JSType mock FIRST - creating/stubbing a mock
		// inside an open when(...).thenReturn(...) trips Mockito's
		// UnfinishedStubbingException (same wart noted in CodeContextServiceTest).
		JSType paramType = typeName == null ? null : type(typeName);
		Parameter p = mock(Parameter.class);
		when(p.getName()).thenReturn(name);
		when(p.getType()).thenReturn(paramType);
		return p;
	}

	private static org.eclipse.emf.common.util.EList<Parameter> eListOf(Parameter... params) {
		org.eclipse.emf.common.util.BasicEList<Parameter> list = new org.eclipse.emf.common.util.BasicEList<>();
		list.addAll(Arrays.asList(params));
		return list;
	}

	// ==================================================================
	// mapClassNameToScriptingName
	// ==================================================================

	@Nested
	class MapClassNameToScriptingName {
		@Test
		@DisplayName("every known JS* class name maps to its scriptingName")
		void knownNames() {
			assertEquals("application", SERVICE.mapClassNameToScriptingName("JSApplication"));
			assertEquals("databaseManager", SERVICE.mapClassNameToScriptingName("JSDatabaseManager"));
			assertEquals("security", SERVICE.mapClassNameToScriptingName("JSSecurity"));
			assertEquals("i18n", SERVICE.mapClassNameToScriptingName("JSI18N"));
			assertEquals("utils", SERVICE.mapClassNameToScriptingName("JSUtils"));
			assertEquals("controller", SERVICE.mapClassNameToScriptingName("JSForm"));
			assertEquals("eventsManager", SERVICE.mapClassNameToScriptingName("JSEventsManager"));
			assertEquals("solutionModel", SERVICE.mapClassNameToScriptingName("JSSolutionModel"));
		}

		@Test
		@DisplayName("an unknown class name maps to null")
		void unknown() {
			assertEquals(null, SERVICE.mapClassNameToScriptingName("JSUnknown"));
			assertEquals(null, SERVICE.mapClassNameToScriptingName(""));
		}

		@Test
		@DisplayName("a null class name maps to null (guard)")
		void nullName() {
			assertEquals(null, SERVICE.mapClassNameToScriptingName(null));
		}
	}

	// ==================================================================
	// formatMemberSignature
	// ==================================================================

	@Nested
	class FormatMemberSignature {
		@Test
		@DisplayName("a null member yields the empty string (guard)")
		void nullMember() {
			assertEquals("", SERVICE.formatMemberSignature(null));
		}

		@Test
		@DisplayName("a no-arg method with a return type renders name(): ReturnType")
		void noArgMethodWithReturn() {
			JSType returnType = type("Boolean");
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("loadAllRecords");
			when(method.getParameters()).thenReturn(eListOf());
			when(method.getType()).thenReturn(returnType);
			assertEquals("loadAllRecords(): Boolean", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("a no-arg void method (null return type) renders name()")
		void noArgMethodNoReturn() {
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("clear");
			when(method.getParameters()).thenReturn(eListOf());
			when(method.getType()).thenReturn(null);
			assertEquals("clear()", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("a method with a null parameter list renders name()")
		void nullParameterList() {
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("refresh");
			when(method.getParameters()).thenReturn(null);
			when(method.getType()).thenReturn(null);
			assertEquals("refresh()", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("a typed parameter renders name:Type and the return type follows")
		void typedParameter() {
			Parameter query = param("query", "QBSelect");
			JSType returnType = type("JSFoundSet");
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("getFoundSet");
			when(method.getParameters()).thenReturn(eListOf(query));
			when(method.getType()).thenReturn(returnType);
			assertEquals("getFoundSet(query:QBSelect): JSFoundSet", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("an untyped parameter (null type) renders just its name")
		void untypedParameter() {
			Parameter value = param("value", null);
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("log");
			when(method.getParameters()).thenReturn(eListOf(value));
			when(method.getType()).thenReturn(null);
			assertEquals("log(value)", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("multiple parameters are comma-separated")
		void multipleParameters() {
			Parameter key = param("key", "String");
			Parameter val = param("val", "Object");
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("setValue");
			when(method.getParameters()).thenReturn(eListOf(key, val));
			when(method.getType()).thenReturn(null);
			assertEquals("setValue(key:String, val:Object)", SERVICE.formatMemberSignature(method));
		}

		@Test
		@DisplayName("a property with a type renders name: Type")
		void propertyWithType() {
			JSType propType = type("Number");
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("length");
			when(property.getType()).thenReturn(propType);
			assertEquals("length: Number", SERVICE.formatMemberSignature(property));
		}

		@Test
		@DisplayName("a property with no type renders just its name")
		void propertyNoType() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("length");
			when(property.getType()).thenReturn(null);
			assertEquals("length", SERVICE.formatMemberSignature(property));
		}
	}

	// ==================================================================
	// formatMemberDocumentation
	// ==================================================================

	@Nested
	class FormatMemberDocumentation {
		@Test
		@DisplayName("a null member yields the empty string (guard)")
		void nullMember() {
			assertEquals("", SERVICE.formatMemberDocumentation(null, "application"));
		}

		@Test
		@DisplayName("the signature line is prefixed with the owning type name")
		void signatureLineHasTypeName() {
			Parameter msg = param("msg", "String");
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("output");
			when(method.getParameters()).thenReturn(eListOf(msg));
			when(method.getType()).thenReturn(null);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "application");
			assertTrue(result.startsWith("SIGNATURE: application.output(msg:String)\n\n"), "got: " + result);
		}

		@Test
		@DisplayName("a non-blank description is emitted under a DESCRIPTION heading")
		void descriptionEmitted() {
			JSType propType = type("Number");
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getType()).thenReturn(propType);
			when(property.getDescription()).thenReturn("The size of the collection.");
			when(property.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(property, "foundset");
			assertTrue(result.contains("DESCRIPTION:\nThe size of the collection.\n\n"), "got: " + result);
		}

		@Test
		@DisplayName("a blank description is not emitted")
		void blankDescriptionSkipped() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn("   ");
			when(property.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(property, "foundset");
			assertFalse(result.contains("DESCRIPTION:"), "got: " + result);
		}

		@Test
		@DisplayName("a null description is not emitted")
		void nullDescriptionSkipped() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn(null);
			when(property.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(property, "foundset");
			assertFalse(result.contains("DESCRIPTION:"), "got: " + result);
		}

		@Test
		@DisplayName("method parameters are listed under PARAMETERS, with type in parentheses")
		void parametersListed() {
			Parameter key = param("key", "String");
			Parameter val = param("val", null);
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("setValue");
			when(method.getParameters()).thenReturn(eListOf(key, val));
			when(method.getType()).thenReturn(null);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "x");
			assertTrue(result.contains("PARAMETERS:\n"), "got: " + result);
			assertTrue(result.contains("  - key (String)\n"), "typed param; got: " + result);
			assertTrue(result.contains("  - val\n"), "untyped param has no parens; got: " + result);
		}

		@Test
		@DisplayName("a method with no parameters omits the PARAMETERS section")
		void noParametersSectionWhenEmpty() {
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("clear");
			when(method.getParameters()).thenReturn(eListOf());
			when(method.getType()).thenReturn(null);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "x");
			assertFalse(result.contains("PARAMETERS:"), "got: " + result);
		}

		@Test
		@DisplayName("a null parameter list omits the PARAMETERS section")
		void noParametersSectionWhenNull() {
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("clear");
			when(method.getParameters()).thenReturn(null);
			when(method.getType()).thenReturn(null);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "x");
			assertFalse(result.contains("PARAMETERS:"), "got: " + result);
		}

		@Test
		@DisplayName("a method return type is emitted as a RETURNS line")
		void returnsEmitted() {
			JSType returnType = type("Boolean");
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("loadAllRecords");
			when(method.getParameters()).thenReturn(eListOf());
			when(method.getType()).thenReturn(returnType);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "foundset");
			assertTrue(result.contains("RETURNS: Boolean\n"), "got: " + result);
		}

		@Test
		@DisplayName("a void method (null return type) omits the RETURNS line")
		void noReturnsWhenVoid() {
			Method method = mock(Method.class);
			when(method.getName()).thenReturn("clear");
			when(method.getParameters()).thenReturn(eListOf());
			when(method.getType()).thenReturn(null);
			when(method.getDescription()).thenReturn(null);
			when(method.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(method, "foundset");
			assertFalse(result.contains("RETURNS:"), "got: " + result);
		}

		@Test
		@DisplayName("a property is never given PARAMETERS or RETURNS sections")
		void propertyHasNoMethodSections() {
			JSType propType = type("Number");
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getType()).thenReturn(propType);
			when(property.getDescription()).thenReturn(null);
			when(property.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(property, "foundset");
			assertFalse(result.contains("PARAMETERS:"), "got: " + result);
			assertFalse(result.contains("RETURNS:"), "got: " + result);
		}

		@Test
		@DisplayName("a deprecated member with no 'deprecated' in its description gets the generic deprecation note")
		void deprecatedGenericNote() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("oldThing");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn("An old thing.");
			when(property.isDeprecated()).thenReturn(true);

			String result = SERVICE.formatMemberDocumentation(property, "x");
			assertTrue(result.contains("\n[DEPRECATED] This member is deprecated.\n"), "got: " + result);
		}

		@Test
		@DisplayName("a deprecated member whose description already mentions 'deprecated' gets the bare marker")
		void deprecatedDescriptionMentionsIt() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("oldThing");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn("This is Deprecated, use newThing.");
			when(property.isDeprecated()).thenReturn(true);

			String result = SERVICE.formatMemberDocumentation(property, "x");
			assertTrue(result.contains("\n[DEPRECATED]\n"), "bare marker expected; got: " + result);
			assertFalse(result.contains("This member is deprecated."), "generic note should be suppressed; got: " + result);
		}

		@Test
		@DisplayName("a deprecated member with a null description gets the generic note")
		void deprecatedNullDescription() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("oldThing");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn(null);
			when(property.isDeprecated()).thenReturn(true);

			String result = SERVICE.formatMemberDocumentation(property, "x");
			assertTrue(result.contains("\n[DEPRECATED] This member is deprecated.\n"), "got: " + result);
		}

		@Test
		@DisplayName("a non-deprecated member emits no deprecation marker")
		void notDeprecated() {
			Property property = mock(Property.class);
			when(property.getName()).thenReturn("size");
			when(property.getType()).thenReturn(null);
			when(property.getDescription()).thenReturn(null);
			when(property.isDeprecated()).thenReturn(false);

			String result = SERVICE.formatMemberDocumentation(property, "x");
			assertFalse(result.contains("[DEPRECATED]"), "got: " + result);
		}
	}
}

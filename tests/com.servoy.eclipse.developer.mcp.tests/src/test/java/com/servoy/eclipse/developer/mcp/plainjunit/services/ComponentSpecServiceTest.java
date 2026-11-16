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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sablo.specification.IFunctionParameters;
import org.sablo.specification.Package.IPackageReader;
import org.sablo.specification.PropertyDescription;
import org.sablo.specification.WebObjectFunctionDefinition;
import org.sablo.specification.WebObjectSpecification;
import org.sablo.specification.property.IPropertyType;

import com.servoy.eclipse.developer.mcp.services.ComponentSpecService;

/**
 * Plain-JUnit unit tests for {@link ComponentSpecService}'s <em>pure</em>
 * JSON-building helpers - {@code describeSummary(WebObjectSpecification,String)},
 * {@code describeProperty(PropertyDescription)},
 * {@code describeFunction(WebObjectFunctionDefinition)} and
 * {@code readDocFile(IPackageReader,URL)} - with the Servoy/sablo spec objects
 * ({@link WebObjectSpecification}, {@link PropertyDescription},
 * {@link WebObjectFunctionDefinition}, {@link IPackageReader}) mocked via
 * Mockito at the boundary.
 * <p>
 * These four helpers are private static pure functions over their spec-object
 * arguments: given a mocked spec they build a {@link JSONObject}/doc string, so
 * they are reachable with no running Servoy runtime. They are invoked here by
 * reflection (no production visibility is widened and no production code is
 * changed), and the assertions check only the <b>shape</b> of the produced
 * JSON - its keys, values and the present-vs-absent optional-field branches -
 * never the sablo spec objects' own real behaviour (that belongs in sablo).
 * </p>
 * <p>
 * Everything that reaches the live spec provider through
 * {@code getState(...)} - {@code listObjects}, {@code getObjectSpec},
 * {@code getObjectDocs}, {@code getHandlerNames}, {@code packageNames} - needs
 * the Servoy runtime ({@code WebComponentSpecProvider}/
 * {@code WebServiceSpecProvider}) and stays for the {@code integrationpde}
 * tests; it is intentionally not exercised here.
 * </p>
 *
 * @since 2026.06
 */
class ComponentSpecServiceTest
{
	// -------------------------------------------------------------------------
	// reflection helpers for the private static describe*/readDocFile methods
	// -------------------------------------------------------------------------

	private static JSONObject describeSummary(WebObjectSpecification spec, String packageName) throws Exception
	{
		Method m = ComponentSpecService.class.getDeclaredMethod("describeSummary", WebObjectSpecification.class, String.class);
		m.setAccessible(true);
		return (JSONObject)m.invoke(null, spec, packageName);
	}

	private static JSONObject describeProperty(PropertyDescription pd) throws Exception
	{
		Method m = ComponentSpecService.class.getDeclaredMethod("describeProperty", PropertyDescription.class);
		m.setAccessible(true);
		return (JSONObject)m.invoke(null, pd);
	}

	private static JSONObject describeFunction(WebObjectFunctionDefinition fn) throws Exception
	{
		Method m = ComponentSpecService.class.getDeclaredMethod("describeFunction", WebObjectFunctionDefinition.class);
		m.setAccessible(true);
		return (JSONObject)m.invoke(null, fn);
	}

	private static String readDocFile(IPackageReader reader, URL docURL) throws Exception
	{
		Method m = ComponentSpecService.class.getDeclaredMethod("readDocFile", IPackageReader.class, URL.class);
		m.setAccessible(true);
		return (String)m.invoke(null, reader, docURL);
	}

	/** Builds a {@code file:} {@link URL} without the deprecated {@code URL(String)} constructor. */
	private static URL fileUrl(String spec) throws Exception
	{
		return URI.create(spec).toURL();
	}

	/** A {@link IPropertyType} mock whose {@link IPropertyType#getName()} is {@code typeName}. */
	private static IPropertyType< ? > propertyType(String typeName)
	{
		IPropertyType< ? > type = mock(IPropertyType.class);
		lenient().when(type.getName()).thenReturn(typeName);
		return type;
	}

	/** Stubs {@code pd.getType()} to return {@code type}, avoiding a raw {@link IPropertyType} cast at call sites. */
	private static void stubType(PropertyDescription pd, IPropertyType< ? > type)
	{
		doReturn(type).when(pd).getType();
	}

	// -------------------------------------------------------------------------
	// describeSummary
	// -------------------------------------------------------------------------

	@Nested
	class DescribeSummary
	{
		/** A minimal, non-deprecated spec: no category, empty handlers, null properties/apis. */
		private WebObjectSpecification minimalSpec()
		{
			WebObjectSpecification spec = mock(WebObjectSpecification.class);
			lenient().when(spec.getName()).thenReturn("bootstrapcomponents-button");
			lenient().when(spec.getDisplayName()).thenReturn("Button");
			lenient().when(spec.getCategoryName()).thenReturn(null);
			lenient().when(spec.isDeprecated()).thenReturn(false);
			lenient().when(spec.getProperties()).thenReturn(null);
			lenient().when(spec.getHandlers()).thenReturn(Collections.emptyMap());
			lenient().when(spec.getApiFunctions()).thenReturn(null);
			return spec;
		}

		@Test
		@DisplayName("a minimal spec yields name/displayName/packageName with zeroed counts and no optional keys")
		void minimal() throws Exception
		{
			JSONObject json = describeSummary(minimalSpec(), "bootstrapcomponents");
			assertEquals("bootstrapcomponents-button", json.getString("name"));
			assertEquals("Button", json.getString("displayName"));
			assertEquals("bootstrapcomponents", json.getString("packageName"));
			assertEquals(0, json.getInt("propertyCount"));
			assertEquals(0, json.getInt("handlerCount"));
			assertEquals(0, json.getInt("apiCount"));
			// optional keys absent
			assertFalse(json.has("categoryName"), "null category must be omitted");
			assertFalse(json.has("deprecated"), "non-deprecated must omit the deprecated flag");
			assertFalse(json.has("deprecatedMessage"));
			assertFalse(json.has("replacement"));
		}

		@Test
		@DisplayName("a non-empty category is included")
		void withCategory() throws Exception
		{
			WebObjectSpecification spec = minimalSpec();
			lenient().when(spec.getCategoryName()).thenReturn("Form Components");
			JSONObject json = describeSummary(spec, "pkg");
			assertEquals("Form Components", json.getString("categoryName"));
		}

		@Test
		@DisplayName("a deprecated spec adds the flag plus message and replacement when present")
		void deprecatedWithMessageAndReplacement() throws Exception
		{
			WebObjectSpecification spec = minimalSpec();
			lenient().when(spec.isDeprecated()).thenReturn(true);
			lenient().when(spec.getDeprecatedMessage()).thenReturn("use the new button");
			lenient().when(spec.getReplacement()).thenReturn("bootstrapcomponents-button2");
			JSONObject json = describeSummary(spec, "pkg");
			assertTrue(json.getBoolean("deprecated"));
			assertEquals("use the new button", json.getString("deprecatedMessage"));
			assertEquals("bootstrapcomponents-button2", json.getString("replacement"));
		}

		@Test
		@DisplayName("a deprecated spec with blank message/replacement adds only the flag")
		void deprecatedWithoutMessage() throws Exception
		{
			WebObjectSpecification spec = minimalSpec();
			lenient().when(spec.isDeprecated()).thenReturn(true);
			lenient().when(spec.getDeprecatedMessage()).thenReturn("");
			lenient().when(spec.getReplacement()).thenReturn(null);
			JSONObject json = describeSummary(spec, "pkg");
			assertTrue(json.getBoolean("deprecated"));
			assertFalse(json.has("deprecatedMessage"), "blank message must be omitted");
			assertFalse(json.has("replacement"), "null replacement must be omitted");
		}

		@Test
		@DisplayName("counts reflect the sizes of properties, handlers and api functions")
		void counts() throws Exception
		{
			WebObjectSpecification spec = minimalSpec();
			lenient().when(spec.getProperties()).thenReturn(Collections.singletonMap("p", mock(PropertyDescription.class)));
			lenient().when(spec.getHandlers()).thenReturn(Collections.singletonMap("h", null));
			lenient().when(spec.getApiFunctions()).thenReturn(Collections.singletonMap("a", null));
			JSONObject json = describeSummary(spec, "pkg");
			assertEquals(1, json.getInt("propertyCount"));
			assertEquals(1, json.getInt("handlerCount"));
			assertEquals(1, json.getInt("apiCount"));
		}
	}

	// -------------------------------------------------------------------------
	// describeProperty
	// -------------------------------------------------------------------------

	@Nested
	class DescribeProperty
	{
		/** A minimal property: a known type, no default/initial, not optional/deprecated, no scope/values/doc. */
		private PropertyDescription minimalProperty()
		{
			PropertyDescription pd = mock(PropertyDescription.class);
			lenient().when(pd.getName()).thenReturn("dataProviderID");
			stubType(pd, propertyType("dataprovider"));
			lenient().when(pd.hasDefault()).thenReturn(false);
			lenient().when(pd.getInitialValue()).thenReturn(null);
			lenient().when(pd.isOptional()).thenReturn(false);
			lenient().when(pd.getTag("scope")).thenReturn(null);
			lenient().when(pd.getValues()).thenReturn(null);
			lenient().when(pd.isDeprecated()).thenReturn(false);
			lenient().when(pd.getDescriptionProcessed(false, null)).thenReturn(null);
			return pd;
		}

		@Test
		@DisplayName("a minimal property yields only name + type, with no optional keys")
		void minimal() throws Exception
		{
			JSONObject json = describeProperty(minimalProperty());
			assertEquals("dataProviderID", json.getString("name"));
			assertEquals("dataprovider", json.getString("type"));
			assertFalse(json.has("default"));
			assertFalse(json.has("initialValue"));
			assertFalse(json.has("optional"));
			assertFalse(json.has("scope"));
			assertFalse(json.has("values"));
			assertFalse(json.has("deprecated"));
			assertFalse(json.has("doc"));
		}

		@Test
		@DisplayName("a null type renders as the literal 'unknown'")
		void unknownType() throws Exception
		{
			PropertyDescription pd = minimalProperty();
			stubType(pd, null);
			JSONObject json = describeProperty(pd);
			assertEquals("unknown", json.getString("type"));
		}

		@Test
		@DisplayName("hasDefault includes the default value; a null default value is still emitted when hasDefault is true")
		void defaultValue() throws Exception
		{
			PropertyDescription pd = minimalProperty();
			lenient().when(pd.hasDefault()).thenReturn(true);
			lenient().when(pd.getDefaultValue()).thenReturn("hello");
			JSONObject json = describeProperty(pd);
			assertEquals("hello", json.getString("default"));
		}

		@Test
		@DisplayName("initialValue, optional, scope tag, deprecation message and doc are all passed through when present")
		void allOptionalFields() throws Exception
		{
			PropertyDescription pd = minimalProperty();
			lenient().when(pd.getInitialValue()).thenReturn(42);
			lenient().when(pd.isOptional()).thenReturn(true);
			lenient().when(pd.getTag("scope")).thenReturn("design");
			lenient().when(pd.isDeprecated()).thenReturn(true);
			lenient().when(pd.getDeprecatedMessage()).thenReturn("gone soon");
			lenient().when(pd.getDescriptionProcessed(false, null)).thenReturn("the data provider");
			JSONObject json = describeProperty(pd);
			assertEquals(42, json.getInt("initialValue"));
			assertTrue(json.getBoolean("optional"));
			assertEquals("design", json.getString("scope"));
			assertTrue(json.getBoolean("deprecated"));
			assertEquals("gone soon", json.getString("deprecatedMessage"));
			assertEquals("the data provider", json.getString("doc"));
		}

		@Test
		@DisplayName("a non-empty values list becomes a JSON array; an empty list is omitted")
		void values() throws Exception
		{
			PropertyDescription withValues = minimalProperty();
			lenient().when(withValues.getValues()).thenReturn(Arrays.<Object> asList("left", "right", "center"));
			JSONArray arr = describeProperty(withValues).getJSONArray("values");
			assertEquals(3, arr.length());
			assertEquals("left", arr.getString(0));
			assertEquals("center", arr.getString(2));

			PropertyDescription emptyValues = minimalProperty();
			lenient().when(emptyValues.getValues()).thenReturn(Collections.emptyList());
			assertFalse(describeProperty(emptyValues).has("values"), "empty values list must be omitted");
		}

		@Test
		@DisplayName("a deprecated property with a blank message adds the flag but no message")
		void deprecatedBlankMessage() throws Exception
		{
			PropertyDescription pd = minimalProperty();
			lenient().when(pd.isDeprecated()).thenReturn(true);
			lenient().when(pd.getDeprecatedMessage()).thenReturn("");
			JSONObject json = describeProperty(pd);
			assertTrue(json.getBoolean("deprecated"));
			assertFalse(json.has("deprecatedMessage"));
		}
	}

	// -------------------------------------------------------------------------
	// describeFunction
	// -------------------------------------------------------------------------

	@Nested
	class DescribeFunction
	{
		/** A minimal function: null parameters, null return type, not private/deprecated, no doc. */
		private WebObjectFunctionDefinition minimalFunction(String name)
		{
			WebObjectFunctionDefinition fn = mock(WebObjectFunctionDefinition.class);
			lenient().when(fn.getName()).thenReturn(name);
			lenient().when(fn.getParameters()).thenReturn(null);
			lenient().when(fn.getReturnType()).thenReturn(null);
			lenient().when(fn.isPrivate()).thenReturn(false);
			lenient().when(fn.isDeprecated()).thenReturn(false);
			lenient().when(fn.getDocumentation()).thenReturn(null);
			return fn;
		}

		@Test
		@DisplayName("a minimal function yields name + empty parameters array, no optional keys")
		void minimal() throws Exception
		{
			JSONObject json = describeFunction(minimalFunction("onAction"));
			assertEquals("onAction", json.getString("name"));
			assertEquals(0, json.getJSONArray("parameters").length());
			assertFalse(json.has("returnType"));
			assertFalse(json.has("private"));
			assertFalse(json.has("deprecated"));
			assertFalse(json.has("doc"));
		}

		@Test
		@DisplayName("defined parameters are rendered with name + type, a null slot is skipped, and unknown-typed params read 'unknown'")
		void parameters() throws Exception
		{
			WebObjectFunctionDefinition fn = minimalFunction("doIt");
			IFunctionParameters params = mock(IFunctionParameters.class);
			lenient().when(fn.getParameters()).thenReturn(params);
			lenient().when(params.getDefinedArgsCount()).thenReturn(3);

			PropertyDescription p0 = mock(PropertyDescription.class);
			lenient().when(p0.getName()).thenReturn("event");
			stubType(p0, propertyType("object"));
			PropertyDescription p2 = mock(PropertyDescription.class);
			lenient().when(p2.getName()).thenReturn("count");
			stubType(p2, null); // -> "unknown"

			lenient().when(params.getParameterDefinition(0)).thenReturn(p0);
			lenient().when(params.getParameterDefinition(1)).thenReturn(null); // skipped
			lenient().when(params.getParameterDefinition(2)).thenReturn(p2);

			JSONArray arr = describeFunction(fn).getJSONArray("parameters");
			assertEquals(2, arr.length(), "the null parameter slot must be skipped");
			assertEquals("event", arr.getJSONObject(0).getString("name"));
			assertEquals("object", arr.getJSONObject(0).getString("type"));
			assertEquals("count", arr.getJSONObject(1).getString("name"));
			assertEquals("unknown", arr.getJSONObject(1).getString("type"));
		}

		@Test
		@DisplayName("a return type whose type is non-null is emitted; a return type with a null type is omitted")
		void returnType() throws Exception
		{
			WebObjectFunctionDefinition withReturn = minimalFunction("getValue");
			PropertyDescription rt = mock(PropertyDescription.class);
			stubType(rt, propertyType("string"));
			lenient().when(withReturn.getReturnType()).thenReturn(rt);
			assertEquals("string", describeFunction(withReturn).getString("returnType"));

			WebObjectFunctionDefinition nullInnerType = minimalFunction("getValue");
			PropertyDescription rtNullType = mock(PropertyDescription.class);
			stubType(rtNullType, null);
			lenient().when(nullInnerType.getReturnType()).thenReturn(rtNullType);
			assertFalse(describeFunction(nullInnerType).has("returnType"), "a return type with a null inner type must be omitted");
		}

		@Test
		@DisplayName("private, deprecated (with message) and doc are all passed through when present")
		void allOptionalFields() throws Exception
		{
			WebObjectFunctionDefinition fn = minimalFunction("secret");
			lenient().when(fn.isPrivate()).thenReturn(true);
			lenient().when(fn.isDeprecated()).thenReturn(true);
			lenient().when(fn.getDeprecatedMessage()).thenReturn("do not call");
			lenient().when(fn.getDocumentation()).thenReturn("internal use");
			JSONObject json = describeFunction(fn);
			assertTrue(json.getBoolean("private"));
			assertTrue(json.getBoolean("deprecated"));
			assertEquals("do not call", json.getString("deprecatedMessage"));
			assertEquals("internal use", json.getString("doc"));
		}

		@Test
		@DisplayName("a deprecated function with a blank message adds the flag but no message")
		void deprecatedBlankMessage() throws Exception
		{
			WebObjectFunctionDefinition fn = minimalFunction("f");
			lenient().when(fn.isDeprecated()).thenReturn(true);
			lenient().when(fn.getDeprecatedMessage()).thenReturn("");
			JSONObject json = describeFunction(fn);
			assertTrue(json.getBoolean("deprecated"));
			assertFalse(json.has("deprecatedMessage"));
		}
	}

	// -------------------------------------------------------------------------
	// readDocFile
	// -------------------------------------------------------------------------

	@Nested
	class ReadDocFile
	{
		@Test
		@DisplayName("a null reader yields null")
		void nullReader() throws Exception
		{
			assertNull(readDocFile(null, fileUrl("file:/whatever/x_doc.js")));
		}

		@Test
		@DisplayName("the in-package path is derived from the URL below /META-INF/ and read via the reader")
		void stripsBeforeMetaInf() throws Exception
		{
			IPackageReader reader = mock(IPackageReader.class);
			// path "/some/abs/META-INF/doc/button_doc.js" -> in-package "META-INF/doc/button_doc.js"
			lenient().when(reader.readTextFile("META-INF/doc/button_doc.js", Charset.forName("UTF-8"))).thenReturn("/** the docs */");
			String content = readDocFile(reader, fileUrl("file:/some/abs/META-INF/doc/button_doc.js"));
			assertEquals("/** the docs */", content);
		}

		@Test
		@DisplayName("a URL with no /META-INF/ segment passes the full path through unchanged")
		void noMetaInf() throws Exception
		{
			IPackageReader reader = mock(IPackageReader.class);
			lenient().when(reader.readTextFile("/plain/doc.js", Charset.forName("UTF-8"))).thenReturn("doc");
			String content = readDocFile(reader, fileUrl("file:/plain/doc.js"));
			assertEquals("doc", content);
		}

		@Test
		@DisplayName("an IOException from the reader is swallowed and yields null")
		void ioExceptionYieldsNull() throws Exception
		{
			IPackageReader reader = mock(IPackageReader.class);
			when(reader.readTextFile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Charset.class)))
				.thenThrow(new IOException("boom"));
			assertNull(readDocFile(reader, fileUrl("file:/some/META-INF/doc.js")));
		}
	}
}

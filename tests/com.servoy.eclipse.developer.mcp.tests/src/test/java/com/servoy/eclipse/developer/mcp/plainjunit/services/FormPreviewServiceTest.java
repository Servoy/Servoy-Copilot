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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sablo.specification.PropertyDescription;
import org.sablo.specification.SpecProviderState;
import org.sablo.specification.WebObjectSpecification;
import org.sablo.specification.property.IPropertyType;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.FormPreviewService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.LayoutContainer;
import com.servoy.j2db.persistence.WebComponent;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link FormPreviewService}'s <em>own</em>
 * validation logic - without a live Eclipse workspace, model, spec provider or
 * embedded browser.
 * <p>
 * {@code FormPreviewService} has two sides. Its <b>render/screenshot</b> side
 * (showFormInBrowser / screenshotForm / buildScreenshotSpec, which drive the
 * external browser and the bundled Cypress runner) genuinely needs the workbench
 * and stays in the {@code integrationpde} suite. Its <b>validation</b> side is
 * our own logic over mockable model / spec / marker objects: the type-mismatch
 * check, the web-component / layout-container spec walk, the problem-marker
 * collection, the orchestration over those, and the small filesystem helpers
 * that read the Cypress console log and locate the screenshot file. Those are
 * what these tests drive.
 * </p>
 * <p>
 * The one production change is a behaviour-preserving {@code getServoyModel()}
 * seam (mirroring {@code ServoyDevServer.getServoyModel()}): every test here
 * subclasses the service and overrides it to return a Mockito-mocked
 * {@link IDeveloperServoyModel}, stubbing the {@link ServoyProject} /
 * {@link FlattenedSolution} / {@link Form} it hands back just enough to drive
 * our branches. The {@code org.sablo} spec objects
 * ({@link SpecProviderState}, {@link WebObjectSpecification},
 * {@link PropertyDescription}, {@link IPropertyType}) and the
 * {@code org.eclipse.core.resources} marker objects ({@link IResource},
 * {@link IMarker}) are mocked at the boundary; the private helpers are invoked
 * by reflection so no production visibility is widened. The assertions check
 * only our validation output - the error strings and the null-vs-non-null
 * branches - never the real spec provider or the real render.
 * </p>
 *
 * @since 2026.11
 */
class FormPreviewServiceTest
{
	// =======================================================================
	// seam-overriding subclass + reflection helpers
	// =======================================================================

	/**
	 * A {@link FormPreviewService} whose {@code getServoyModel()} seam returns a
	 * supplied mocked model, so the validation branches run with no workbench.
	 */
	private static final class TestableFormPreviewService extends FormPreviewService
	{
		private final IDeveloperServoyModel model;

		TestableFormPreviewService(IDeveloperServoyModel model)
		{
			this.model = model;
		}

		@Override
		protected IDeveloperServoyModel getServoyModel()
		{
			return model;
		}
	}

	private static String checkTypeMismatch(FormPreviewService svc, PropertyDescription pd, Object value) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("checkTypeMismatch", PropertyDescription.class, Object.class);
		m.setAccessible(true);
		return (String)m.invoke(svc, pd, value);
	}

	private static void validateWebComponents(FormPreviewService svc, Iterator<WebComponent> it, SpecProviderState specState,
		List<String> errors) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("validateWebComponents", Iterator.class, SpecProviderState.class, List.class);
		m.setAccessible(true);
		m.invoke(svc, it, specState, errors);
	}

	private static void validateLayoutContainers(FormPreviewService svc, Iterator<LayoutContainer> it, SpecProviderState specState,
		List<String> errors) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("validateLayoutContainers", Iterator.class, SpecProviderState.class, List.class);
		m.setAccessible(true);
		m.invoke(svc, it, specState, errors);
	}

	private static void collectErrorMarkers(FormPreviewService svc, IResource resource, int depth, List<String> errors) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("collectErrorMarkers", IResource.class, int.class, List.class);
		m.setAccessible(true);
		m.invoke(svc, resource, depth, errors);
	}

	private static String validateFormProperties(FormPreviewService svc, ServoyProject activeProject, String formName) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("validateFormProperties", ServoyProject.class, String.class);
		m.setAccessible(true);
		return (String)m.invoke(svc, activeProject, formName);
	}

	private static String readBrowserConsoleErrors(FormPreviewService svc, Path file) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("readBrowserConsoleErrors", Path.class);
		m.setAccessible(true);
		return (String)m.invoke(svc, file);
	}

	private static Path findScreenshotFile(FormPreviewService svc, Path screenshotsDir, String formName) throws Exception
	{
		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		return (Path)m.invoke(svc, screenshotsDir, formName);
	}

	private static FormPreviewService plainService()
	{
		// validation-side helpers that don't touch the model seam can run on the bare service
		return new FormPreviewService();
	}

	// spec-object mock builders -------------------------------------------------

	private static PropertyDescription pdWithType(String typeName)
	{
		PropertyDescription pd = mock(PropertyDescription.class);
		if (typeName == null)
		{
			lenient().when(pd.getType()).thenReturn(null);
		}
		else
		{
			@SuppressWarnings("unchecked")
			IPropertyType<Object> type = mock(IPropertyType.class);
			lenient().when(type.getName()).thenReturn(typeName);
			lenient().doReturn(type).when(pd).getType();
		}
		return pd;
	}

	// =======================================================================
	// checkTypeMismatch(PropertyDescription, Object) - pure type check
	// =======================================================================

	@Nested
	@DisplayName("checkTypeMismatch")
	class CheckTypeMismatch
	{
		@Test
		@DisplayName("null / JSON.NULL / JSONObject / JSONArray values are never a mismatch")
		void nullAndStructuredValuesAreSkipped() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType("boolean");
			assertNull(checkTypeMismatch(svc, pd, null));
			assertNull(checkTypeMismatch(svc, pd, JSONObject.NULL));
			assertNull(checkTypeMismatch(svc, pd, new JSONObject()));
			assertNull(checkTypeMismatch(svc, pd, new JSONArray()));
		}

		@Test
		@DisplayName("defaultValue Boolean but non-boolean value reports expected boolean")
		void defaultBooleanMismatch() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType(null);
			when(pd.getDefaultValue()).thenReturn(Boolean.TRUE);
			String msg = checkTypeMismatch(svc, pd, "yes");
			assertNotNull(msg);
			assertTrue(msg.contains("expected boolean"), msg);
			assertTrue(msg.contains("String"), msg);
			assertTrue(msg.contains("yes"), msg);
		}

		@Test
		@DisplayName("defaultValue Boolean and a boolean value is fine")
		void defaultBooleanOk() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType(null);
			when(pd.getDefaultValue()).thenReturn(Boolean.FALSE);
			assertNull(checkTypeMismatch(svc, pd, Boolean.TRUE));
		}

		@Test
		@DisplayName("defaultValue Number but a String value reports expected number")
		void defaultNumberMismatch() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType(null);
			when(pd.getDefaultValue()).thenReturn(Integer.valueOf(3));
			String msg = checkTypeMismatch(svc, pd, "abc");
			assertNotNull(msg);
			assertTrue(msg.contains("expected number"), msg);
			assertTrue(msg.contains("abc"), msg);
		}

		@Test
		@DisplayName("type-name boolean/enabled/visible/protected with non-boolean value is a mismatch")
		void typeNameBooleanMismatch() throws Exception
		{
			FormPreviewService svc = plainService();
			for (String t : new String[] { "boolean", "enabled", "visible", "protected" })
			{
				PropertyDescription pd = pdWithType(t);
				when(pd.getDefaultValue()).thenReturn(null);
				String msg = checkTypeMismatch(svc, pd, "x");
				assertNotNull(msg, "type " + t + " should mismatch a String");
				assertTrue(msg.contains("expected boolean"), msg);
			}
		}

		@Test
		@DisplayName("type-name int/integer with a String value reports expected integer")
		void typeNameIntegerMismatch() throws Exception
		{
			FormPreviewService svc = plainService();
			for (String t : new String[] { "int", "integer" })
			{
				PropertyDescription pd = pdWithType(t);
				when(pd.getDefaultValue()).thenReturn(null);
				String msg = checkTypeMismatch(svc, pd, "notnum");
				assertNotNull(msg, "type " + t + " should mismatch a String");
				assertTrue(msg.contains("expected integer"), msg);
				assertTrue(msg.contains("notnum"), msg);
			}
		}

		@Test
		@DisplayName("a boolean value for a boolean type, and an int value for an int type, are fine")
		void typeNameMatchingValuesOk() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription b = pdWithType("boolean");
			when(b.getDefaultValue()).thenReturn(null);
			assertNull(checkTypeMismatch(svc, b, Boolean.TRUE));

			PropertyDescription i = pdWithType("integer");
			when(i.getDefaultValue()).thenReturn(null);
			assertNull(checkTypeMismatch(svc, i, Integer.valueOf(7)));
		}

		@Test
		@DisplayName("null type and no telling default value yields no mismatch")
		void nullTypeNoMismatch() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType(null);
			when(pd.getDefaultValue()).thenReturn("someDefault");
			assertNull(checkTypeMismatch(svc, pd, "anything"));
		}

		@Test
		@DisplayName("an unrelated type name (e.g. 'string') is never reported")
		void unrelatedTypeOk() throws Exception
		{
			FormPreviewService svc = plainService();
			PropertyDescription pd = pdWithType("string");
			when(pd.getDefaultValue()).thenReturn(null);
			assertNull(checkTypeMismatch(svc, pd, "hello"));
		}
	}

	// =======================================================================
	// validateWebComponents / validateLayoutContainers - spec walk
	// =======================================================================

	@Nested
	@DisplayName("validateWebComponents / validateLayoutContainers")
	class ValidateComponents
	{
		private WebComponent webComponent(String typeName, String name, JSONObject json)
		{
			WebComponent wc = mock(WebComponent.class);
			lenient().when(wc.getTypeName()).thenReturn(typeName);
			lenient().when(wc.getName()).thenReturn(name);
			lenient().when(wc.getFlattenedJson()).thenReturn(json);
			return wc;
		}

		private SpecProviderState specWith(String typeName, Map<String, PropertyDescription> props)
		{
			WebObjectSpecification spec = mock(WebObjectSpecification.class);
			lenient().when(spec.getProperties()).thenReturn(props);
			SpecProviderState state = mock(SpecProviderState.class);
			lenient().when(state.getWebObjectSpecification(typeName)).thenReturn(spec);
			return state;
		}

		@Test
		@DisplayName("a known-bad boolean property produces a TYPE MISMATCH error naming the component and property")
		void collectsMismatchError() throws Exception
		{
			FormPreviewService svc = plainService();

			Map<String, PropertyDescription> props = new LinkedHashMap<>();
			props.put("enabled", pdWithType("boolean"));
			SpecProviderState state = specWith("bootstrapcomponents-button", props);

			JSONObject json = new JSONObject();
			json.put("enabled", "yes"); // wrong type - a String where boolean is expected
			WebComponent wc = webComponent("bootstrapcomponents-button", "myButton", json);

			List<String> errors = new ArrayList<>();
			validateWebComponents(svc, Collections.singletonList(wc).iterator(), state, errors);

			assertEquals(1, errors.size(), errors.toString());
			String e = errors.get(0);
			assertTrue(e.contains("[TYPE MISMATCH]"), e);
			assertTrue(e.contains("myButton"), e);
			assertTrue(e.contains("enabled"), e);
		}

		@Test
		@DisplayName("a correctly typed property produces no error")
		void okPropertyNoError() throws Exception
		{
			FormPreviewService svc = plainService();

			Map<String, PropertyDescription> props = new LinkedHashMap<>();
			props.put("enabled", pdWithType("boolean"));
			SpecProviderState state = specWith("comp", props);

			JSONObject json = new JSONObject();
			json.put("enabled", true);
			WebComponent wc = webComponent("comp", "ok", json);

			List<String> errors = new ArrayList<>();
			validateWebComponents(svc, Collections.singletonList(wc).iterator(), state, errors);
			assertTrue(errors.isEmpty(), errors.toString());
		}

		@Test
		@DisplayName("null type name, missing spec, and null flattened JSON are each skipped")
		void skipsUnresolvable() throws Exception
		{
			FormPreviewService svc = plainService();
			SpecProviderState state = mock(SpecProviderState.class);
			lenient().when(state.getWebObjectSpecification("known")).thenReturn(mock(WebObjectSpecification.class));

			WebComponent nullType = webComponent(null, "a", new JSONObject());
			WebComponent noSpec = webComponent("unknown", "b", new JSONObject());
			WebComponent nullJson = webComponent("known", "c", null);

			List<String> errors = new ArrayList<>();
			validateWebComponents(svc, java.util.Arrays.asList(nullType, noSpec, nullJson).iterator(), state, errors);
			assertTrue(errors.isEmpty(), errors.toString());
		}

		@Test
		@DisplayName("component name falls back to the type name when the component has no name")
		void nameFallsBackToType() throws Exception
		{
			FormPreviewService svc = plainService();
			Map<String, PropertyDescription> props = new LinkedHashMap<>();
			props.put("enabled", pdWithType("boolean"));
			SpecProviderState state = specWith("the-type", props);

			JSONObject json = new JSONObject();
			json.put("enabled", "no");
			WebComponent wc = webComponent("the-type", null, json);

			List<String> errors = new ArrayList<>();
			validateWebComponents(svc, Collections.singletonList(wc).iterator(), state, errors);
			assertEquals(1, errors.size(), errors.toString());
			assertTrue(errors.get(0).contains("the-type"), errors.get(0));
		}

		@Test
		@DisplayName("a property absent from the flattened JSON is not checked")
		void absentPropertyNotChecked() throws Exception
		{
			FormPreviewService svc = plainService();
			Map<String, PropertyDescription> props = new LinkedHashMap<>();
			props.put("enabled", pdWithType("boolean"));
			SpecProviderState state = specWith("comp", props);

			JSONObject json = new JSONObject(); // does NOT contain "enabled"
			json.put("other", "whatever");
			WebComponent wc = webComponent("comp", "x", json);

			List<String> errors = new ArrayList<>();
			validateWebComponents(svc, Collections.singletonList(wc).iterator(), state, errors);
			assertTrue(errors.isEmpty(), errors.toString());
		}

		@Test
		@DisplayName("layout containers recurse into their web components and nested containers")
		void layoutContainersRecurse() throws Exception
		{
			FormPreviewService svc = plainService();
			Map<String, PropertyDescription> props = new LinkedHashMap<>();
			props.put("enabled", pdWithType("boolean"));
			SpecProviderState state = specWith("comp", props);

			JSONObject badJson = new JSONObject();
			badJson.put("enabled", "bad");
			WebComponent nestedWc = webComponent("comp", "deep", badJson);

			LayoutContainer inner = mock(LayoutContainer.class);
			lenient().when(inner.getWebComponents()).thenReturn(Collections.singletonList(nestedWc).iterator());
			lenient().when(inner.getLayoutContainers()).thenReturn(Collections.<LayoutContainer> emptyList().iterator());

			LayoutContainer outer = mock(LayoutContainer.class);
			lenient().when(outer.getWebComponents()).thenReturn(Collections.<WebComponent> emptyList().iterator());
			lenient().when(outer.getLayoutContainers()).thenReturn(Collections.singletonList(inner).iterator());

			List<String> errors = new ArrayList<>();
			validateLayoutContainers(svc, Collections.singletonList(outer).iterator(), state, errors);
			assertEquals(1, errors.size(), errors.toString());
			assertTrue(errors.get(0).contains("deep"), errors.get(0));
		}
	}

	// =======================================================================
	// collectErrorMarkers(IResource, depth, errors) - marker collection
	// =======================================================================

	@Nested
	@DisplayName("collectErrorMarkers")
	class CollectErrorMarkers
	{
		private IMarker marker(int severity, String message, int line) throws Exception
		{
			IMarker m = mock(IMarker.class);
			lenient().when(m.getAttribute(IMarker.SEVERITY, -1)).thenReturn(severity);
			lenient().when(m.getAttribute(IMarker.MESSAGE, "Unknown error")).thenReturn(message);
			lenient().when(m.getAttribute(IMarker.LINE_NUMBER, -1)).thenReturn(line);
			return m;
		}

		@Test
		@DisplayName("only ERROR-severity markers are collected, with the line number formatted")
		void collectsOnlyErrorsWithLine() throws Exception
		{
			FormPreviewService svc = plainService();
			IMarker err = marker(IMarker.SEVERITY_ERROR, "boom", 42);
			IMarker warn = marker(IMarker.SEVERITY_WARNING, "careful", 7);

			IResource res = mock(IResource.class);
			when(res.findMarkers(eq(IMarker.PROBLEM), anyBoolean(), anyInt()))
				.thenReturn(new IMarker[] { err, warn });

			List<String> errors = new ArrayList<>();
			collectErrorMarkers(svc, res, IResource.DEPTH_ZERO, errors);

			assertEquals(1, errors.size(), errors.toString());
			assertEquals("- [ERROR] boom (line 42)", errors.get(0));
		}

		@Test
		@DisplayName("an ERROR marker with no line number omits the line suffix")
		void errorWithoutLine() throws Exception
		{
			FormPreviewService svc = plainService();
			IMarker err = marker(IMarker.SEVERITY_ERROR, "no line", -1);

			IResource res = mock(IResource.class);
			when(res.findMarkers(eq(IMarker.PROBLEM), anyBoolean(), anyInt()))
				.thenReturn(new IMarker[] { err });

			List<String> errors = new ArrayList<>();
			collectErrorMarkers(svc, res, IResource.DEPTH_ZERO, errors);
			assertEquals(1, errors.size(), errors.toString());
			assertEquals("- [ERROR] no line", errors.get(0));
		}

		@Test
		@DisplayName("no markers yields an empty collection")
		void noMarkers() throws Exception
		{
			FormPreviewService svc = plainService();
			IResource res = mock(IResource.class);
			when(res.findMarkers(eq(IMarker.PROBLEM), anyBoolean(), anyInt()))
				.thenReturn(new IMarker[0]);

			List<String> errors = new ArrayList<>();
			collectErrorMarkers(svc, res, IResource.DEPTH_ZERO, errors);
			assertTrue(errors.isEmpty(), errors.toString());
		}
	}

	// =======================================================================
	// validateFormProperties(ServoyProject, formName) - orchestration
	// =======================================================================

	@Nested
	@DisplayName("validateFormProperties")
	class ValidateFormProperties
	{
		@Test
		@DisplayName("a null editing flattened solution returns null (nothing to validate)")
		void nullFlattenedSolution() throws Exception
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(null).when(model).getEditingFlattenedSolution(org.mockito.ArgumentMatchers.any());

			FormPreviewService svc = new TestableFormPreviewService(model);
			assertNull(validateFormProperties(svc, project, "someForm"));
		}

		@Test
		@DisplayName("a form that does not exist in the flattened solution returns null")
		void formNotFound() throws Exception
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			FlattenedSolution fs = mock(FlattenedSolution.class);
			doReturn(fs).when(model).getEditingFlattenedSolution(org.mockito.ArgumentMatchers.any());
			doReturn(null).when(fs).getForm("missing");

			FormPreviewService svc = new TestableFormPreviewService(model);
			assertNull(validateFormProperties(svc, project, "missing"));
		}
	}

	// =======================================================================
	// readBrowserConsoleErrors(Path) - console log reader
	// =======================================================================

	@Nested
	@DisplayName("readBrowserConsoleErrors")
	class ReadBrowserConsoleErrors
	{
		@Test
		@DisplayName("a missing file yields null")
		void missingFile(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			assertNull(readBrowserConsoleErrors(svc, dir.resolve("does-not-exist.log")));
		}

		@Test
		@DisplayName("an empty / whitespace-only file yields null")
		void emptyFile(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			Path f = dir.resolve("empty.log");
			Files.writeString(f, "   \n  ", StandardCharsets.UTF_8);
			assertNull(readBrowserConsoleErrors(svc, f));
		}

		@Test
		@DisplayName("a file with content is returned under the 'Browser console errors:' header")
		void withContent(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			Path f = dir.resolve("errors.log");
			Files.writeString(f, "[console.error] boom", StandardCharsets.UTF_8);
			String result = readBrowserConsoleErrors(svc, f);
			assertNotNull(result);
			assertTrue(result.startsWith("Browser console errors:"), result);
			assertTrue(result.contains("[console.error] boom"), result);
		}
	}

	// =======================================================================
	// findScreenshotFile(Path, formName) - filesystem lookup
	// =======================================================================

	@Nested
	@DisplayName("findScreenshotFile")
	class FindScreenshotFile
	{
		@Test
		@DisplayName("finds the png inside the exact spec folder for the form")
		void findsInSpecFolder(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			Path specFolder = dir.resolve("_screenshot_orders.cy.js");
			Files.createDirectories(specFolder);
			Path png = specFolder.resolve("orders.png");
			Files.writeString(png, "x", StandardCharsets.UTF_8);

			Path found = findScreenshotFile(svc, dir, "orders");
			assertNotNull(found);
			assertEquals("orders.png", found.getFileName().toString());
		}

		@Test
		@DisplayName("does not pick the wrong form when one name is a substring of another")
		void disambiguatesSubstringFormNames(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			// a png for "orders" in its spec folder - looking up "order" must NOT match it
			Path ordersFolder = dir.resolve("_screenshot_orders.cy.js");
			Files.createDirectories(ordersFolder);
			Files.writeString(ordersFolder.resolve("orders.png"), "x", StandardCharsets.UTF_8);

			Path found = findScreenshotFile(svc, dir, "order");
			assertNull(found, found == null ? "" : found.toString());
		}

		@Test
		@DisplayName("matches on the exact leaf name <form>.png as a fallback")
		void matchesLeafName(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			Path sub = dir.resolve("some-other-folder");
			Files.createDirectories(sub);
			Files.writeString(sub.resolve("customers.png"), "x", StandardCharsets.UTF_8);

			Path found = findScreenshotFile(svc, dir, "customers");
			assertNotNull(found);
			assertEquals("customers.png", found.getFileName().toString());
		}

		@Test
		@DisplayName("returns null when no matching screenshot exists")
		void noneFound(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			Files.writeString(dir.resolve("unrelated.png"), "x", StandardCharsets.UTF_8);
			assertNull(findScreenshotFile(svc, dir, "theForm"));
		}

		@Test
		@DisplayName("returns null when the screenshots directory does not exist")
		void missingDir(@TempDir Path dir) throws Exception
		{
			FormPreviewService svc = plainService();
			assertNull(findScreenshotFile(svc, dir.resolve("nope"), "theForm"));
		}
	}
}

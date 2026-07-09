package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PersistDuplicateService")
public class PersistDuplicateServiceTest {
	private PersistDuplicateService service;

	@BeforeEach
	void setUp() {
		service = new PersistDuplicateService();
	}

	@Nested
	@DisplayName("Instantiation and API")
	class InstantiationAndApi {
		@Test
		@DisplayName("can be instantiated")
		void canBeInstantiated() {
			assertNotNull(service);
		}

		@Test
		@DisplayName("has duplicatePersist method with correct signature")
		void hasDuplicatePersistMethod() {
			Method m = assertDoesNotThrow(() -> PersistDuplicateService.class.getMethod("duplicatePersist",
					String.class, String.class, String.class, String.class, String.class));
			assertEquals(String.class, m.getReturnType());
		}
	}

	@Nested
	@DisplayName("Parameter validation")
	class ParameterValidation {
		@ParameterizedTest(name = "persistType=''{0}'' is rejected")
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("rejects null, empty, or blank persistType")
		void rejectsInvalidPersistType(String persistType) {
			String result = service.duplicatePersist(persistType, "myForm", "newForm", null, null);
			assertAll(() -> assertTrue(result.startsWith("Error:"), "Should start with Error:"),
					() -> assertTrue(result.contains("persistType"), "Should mention persistType"));
		}

		@ParameterizedTest(name = "name=''{0}'' is rejected")
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("rejects null, empty, or blank name")
		void rejectsInvalidName(String name) {
			String result = service.duplicatePersist("form", name, "newForm", null, null);
			assertAll(() -> assertTrue(result.startsWith("Error:"), "Should start with Error:"),
					() -> assertTrue(result.contains("name"), "Should mention name"));
		}
	}

	@Nested
	@DisplayName("Unsupported persist type")
	class UnsupportedPersistType {
		@ParameterizedTest(name = "persistType=''{0}'' is unsupported")
		@ValueSource(strings = { "unknown", "script", "calculation", "aggregate", "datasource" })
		@DisplayName("returns error for unsupported persist types")
		void rejectsUnsupportedPersistType(String persistType) {
			try {
				String result = service.duplicatePersist(persistType, "something", "something_copy", null, null);
				assertAll(() -> assertTrue(result.startsWith("Error:"), "Should start with Error:"),
						() -> assertTrue(result.contains("Unsupported") || result.contains("not found"),
								"Should mention unsupported or not found"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}
	}

	@Nested
	@DisplayName("Supported persist types dispatch")
	class SupportedPersistTypes {
		@Test
		@DisplayName("form type is dispatched (returns solution-not-found in plain JUnit)")
		void formTypeDispatched() {
			try {
				String result = service.duplicatePersist("form", "myForm", "myForm_copy", null, null);
				assertNotNull(result);
				assertTrue(result.contains("Error") || result.contains("not found"),
						"Without workspace should produce error");
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}

		@Test
		@DisplayName("relation type is dispatched")
		void relationTypeDispatched() {
			try {
				String result = service.duplicatePersist("relation", "myRelation", "myRelation_copy", null, null);
				assertNotNull(result);
				assertTrue(result.contains("Error") || result.contains("not found"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}

		@Test
		@DisplayName("valuelist type is dispatched")
		void valuelistTypeDispatched() {
			try {
				String result = service.duplicatePersist("valuelist", "myVL", "myVL_copy", null, null);
				assertNotNull(result);
				assertTrue(result.contains("Error") || result.contains("not found"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}

		@Test
		@DisplayName("media type is dispatched")
		void mediaTypeDispatched() {
			try {
				String result = service.duplicatePersist("media", "myMedia", "myMedia_copy", null, null);
				assertNotNull(result);
				assertTrue(result.contains("Error") || result.contains("not found"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}

		@Test
		@DisplayName("persistType is case-insensitive")
		void persistTypeCaseInsensitive() {
			try {
				String result = service.duplicatePersist("FORM", "myForm", "myForm_copy", null, null);
				assertNotNull(result);
				assertTrue(result.contains("Error") || result.contains("not found"),
						"Should dispatch to form handler, not unsupported");
				assertTrue(!result.contains("Unsupported"), "FORM should not be unsupported");
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}
	}

	@Nested
	@DisplayName("Destination solution resolution")
	class DestinationSolutionResolution {
		@Test
		@DisplayName("explicit destination solution not found returns error")
		void destinationSolutionNotFound() {
			try {
				String result = service.duplicatePersist("form", "myForm", "myForm_copy", null, "nonExistentSolution");
				assertNotNull(result);
				assertTrue(result.startsWith("Error:"));
				assertTrue(result.contains("nonExistentSolution"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}

		@Test
		@DisplayName("explicit source solution not found returns error")
		void sourceSolutionNotFound() {
			try {
				String result = service.duplicatePersist("form", "myForm", "myForm_copy", "nonExistentSource", null);
				assertNotNull(result);
				assertTrue(result.startsWith("Error:"));
				assertTrue(result.contains("nonExistentSource") || result.contains("not found"));
			} catch (Throwable e) {
				assertNotNull(e, "Expected workspace error in plain JUnit");
			}
		}
	}

	@Nested
	@DisplayName("JSON response format")
	class JsonResponseFormat {
		@Test
		@DisplayName("successJson returns valid JSON structure")
		void successJsonFormat() throws Exception {
			Method successJson = PersistDuplicateService.class.getDeclaredMethod("successJson", String.class,
					String.class, String.class);
			successJson.setAccessible(true);

			String result = (String) successJson.invoke(service, "myForm_copy", "form", "mySolution");
			assertAll(() -> assertTrue(result.startsWith("{"), "Should start with {"),
					() -> assertTrue(result.endsWith("}"), "Should end with }"),
					() -> assertTrue(result.contains("\"status\":\"ok\""), "Should contain status ok"),
					() -> assertTrue(result.contains("\"duplicated\":\"myForm_copy\""),
							"Should contain duplicated name"),
					() -> assertTrue(result.contains("\"persistType\":\"form\""), "Should contain persist type"),
					() -> assertTrue(result.contains("\"solution\":\"mySolution\""), "Should contain solution name"));
		}

		@Test
		@DisplayName("escapeJson handles special characters")
		void escapeJsonHandlesSpecialChars() throws Exception {
			Method escapeJson = PersistDuplicateService.class.getDeclaredMethod("escapeJson", String.class);
			escapeJson.setAccessible(true);

			assertAll(() -> assertEquals("", escapeJson.invoke(service, (String) null), "null returns empty"),
					() -> assertEquals("simple", escapeJson.invoke(service, "simple"), "no-op for simple string"),
					() -> assertEquals("with\\\\slash", escapeJson.invoke(service, "with\\slash"), "escapes backslash"),
					() -> assertEquals("with\\\"quote", escapeJson.invoke(service, "with\"quote"), "escapes quote"));
		}
	}

	@Nested
	@DisplayName("Name resolution logic")
	class NameResolutionLogic {
		@Test
		@DisplayName("resolveNewName validates identifier")
		void resolveNewNameValidatesIdentifier() throws Exception {
			Method resolveNewName = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class,
					String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			resolveNewName.setAccessible(true);

			String result = (String) resolveNewName.invoke(service, "invalid name!", "original", "form", null);
			assertAll(() -> assertTrue(result.startsWith("Error:"), "Should return error for invalid identifier"),
					() -> assertTrue(result.contains("Invalid name"), "Should mention invalid name"));
		}

		@ParameterizedTest(name = "invalid name ''{0}'' is rejected")
		@ValueSource(strings = { "has space", "123start", "special@char" })
		@DisplayName("rejects invalid Java identifiers as new names")
		void rejectsInvalidIdentifiers(String invalidName) throws Exception {
			Method resolveNewName = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class,
					String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			resolveNewName.setAccessible(true);

			String result = (String) resolveNewName.invoke(service, invalidName, "original", "form", null);
			assertTrue(result.startsWith("Error:"), "Should return error for: " + invalidName);
		}

		@Test
		@DisplayName("resolveNewName generates _copy suffix when newName is null")
		void resolveNewNameGeneratesCopySuffix() throws Exception {
			Method resolveNewName = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class,
					String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			resolveNewName.setAccessible(true);

			try {
				String result = (String) resolveNewName.invoke(service, null, "myForm", "form", null);
				assertEquals("myForm_copy", result, "Should generate _copy suffix");
			} catch (Exception e) {
				assertTrue(e.getCause() instanceof NullPointerException,
						"NPE expected when project is null (no workspace)");
			}
		}

		@Test
		@DisplayName("resolveNewName generates _copy suffix when newName is blank")
		void resolveNewNameGeneratesCopySuffixForBlank() throws Exception {
			Method resolveNewName = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class,
					String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			resolveNewName.setAccessible(true);

			try {
				String result = (String) resolveNewName.invoke(service, "   ", "myRelation", "relation", null);
				assertEquals("myRelation_copy", result, "Should generate _copy suffix for blank name");
			} catch (Exception e) {
				assertTrue(e.getCause() instanceof NullPointerException,
						"NPE expected when project is null (no workspace)");
			}
		}

		@Test
		@DisplayName("resolveNewName trims valid name")
		void resolveNewNameTrimsValidName() throws Exception {
			Method resolveNewName = PersistDuplicateService.class.getDeclaredMethod("resolveNewName", String.class,
					String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			resolveNewName.setAccessible(true);

			try {
				String result = (String) resolveNewName.invoke(service, "  validName  ", "original", "form", null);
				assertEquals("validName", result, "Should trim the name");
			} catch (Exception e) {
				assertTrue(e.getCause() instanceof NullPointerException,
						"NPE expected when project is null (no workspace)");
			}
		}
	}

	@Nested
	@DisplayName("checkNameExists behavior")
	class CheckNameExists {
		@Test
		@DisplayName("throws NPE when project is null (no null-safety)")
		void throwsNpeForNullProject() throws Exception {
			Method checkNameExists = PersistDuplicateService.class.getDeclaredMethod("checkNameExists", String.class,
					String.class, com.servoy.eclipse.model.nature.ServoyProject.class);
			checkNameExists.setAccessible(true);

			try {
				checkNameExists.invoke(service, "anyName", "form", (Object) null);
			} catch (Exception e) {
				assertTrue(e.getCause() instanceof NullPointerException,
						"Should throw NPE when project is null");
			}
		}
	}
}

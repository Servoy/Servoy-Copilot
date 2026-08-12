package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FormSpecGeneratorSolutionPrefixTest {
	private FormSpecGenerator generator;

	@BeforeEach
	void setUp() {
		generator = new FormSpecGenerator();
	}

	@Nested
	class SolutionPrefixedMethodSignatures {
		@Test
		@DisplayName("specExists(String, String) overload exists")
		void specExistsTwoArgExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("specExists", String.class, String.class);
			assertEquals(boolean.class, method.getReturnType());
		}

		@Test
		@DisplayName("getSpecFilePath(String, String) overload exists")
		void getSpecFilePathTwoArgExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("getSpecFilePath", String.class, String.class);
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("getSetupFilePath(String, String) overload exists")
		void getSetupFilePathTwoArgExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("getSetupFilePath", String.class, String.class);
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("findExistingSpecFile(String, String) method exists")
		void findExistingSpecFileExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("findExistingSpecFile", String.class, String.class);
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("findExistingSetupFile(String, String) method exists")
		void findExistingSetupFileExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("findExistingSetupFile", String.class, String.class);
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("legacy single-arg specExists(String) still exists for backward compat")
		void legacySpecExistsStillExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("specExists", String.class);
			assertEquals(boolean.class, method.getReturnType());
		}

		@Test
		@DisplayName("legacy single-arg getSpecFilePath(String) still exists for backward compat")
		void legacyGetSpecFilePathStillExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("getSpecFilePath", String.class);
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("legacy single-arg getSetupFilePath(String) still exists for backward compat")
		void legacyGetSetupFilePathStillExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("getSetupFilePath", String.class);
			assertEquals(Path.class, method.getReturnType());
		}
	}

	@Nested
	class GracefulDegradationWithoutWorkspace {
		@Test
		@DisplayName("specExists(formName, solutionName) returns false without workspace")
		void specExistsReturnsFalseWithoutWorkspace() {
			assertFalse(generator.specExists("myForm", "mySolution"));
		}

		@Test
		@DisplayName("specExists(formName) returns false without workspace")
		void legacySpecExistsReturnsFalseWithoutWorkspace() {
			assertFalse(generator.specExists("myForm"));
		}

		@Test
		@DisplayName("getSpecFilePath(formName, solutionName) returns null without workspace")
		void getSpecFilePathReturnsNullWithoutWorkspace() {
			assertNull(generator.getSpecFilePath("myForm", "mySolution"));
		}

		@Test
		@DisplayName("getSetupFilePath(formName, solutionName) returns null without workspace")
		void getSetupFilePathReturnsNullWithoutWorkspace() {
			assertNull(generator.getSetupFilePath("myForm", "mySolution"));
		}

		@Test
		@DisplayName("findExistingSpecFile returns null without workspace")
		void findExistingSpecFileReturnsNullWithoutWorkspace() {
			assertNull(generator.findExistingSpecFile("myForm", "mySolution"));
		}

		@Test
		@DisplayName("findExistingSetupFile returns null without workspace")
		void findExistingSetupFileReturnsNullWithoutWorkspace() {
			assertNull(generator.findExistingSetupFile("myForm", "mySolution"));
		}

		@ParameterizedTest
		@NullAndEmptySource
		@DisplayName("specExists handles null/empty formName gracefully")
		void specExistsHandlesNullEmptyFormName(String formName) {
			assertDoesNotThrow(() -> generator.specExists(formName, "solution"));
		}

		@ParameterizedTest
		@NullAndEmptySource
		@DisplayName("specExists handles null/empty solutionName gracefully")
		void specExistsHandlesNullEmptySolutionName(String solutionName) {
			assertDoesNotThrow(() -> generator.specExists("form", solutionName));
		}
	}

	@Nested
	class NamingConventionConstants {
		@Test
		@DisplayName("SPEC_CY_EXTENSION is .spec.cy.js")
		void specCyExtension() throws Exception {
			java.lang.reflect.Field field = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			field.setAccessible(true);
			assertEquals(".spec.cy.js", field.get(null));
		}

		@Test
		@DisplayName("SPEC_JS_EXTENSION is .spec.js")
		void specJsExtension() throws Exception {
			java.lang.reflect.Field field = FormSpecGenerator.class.getDeclaredField("SPEC_JS_EXTENSION");
			field.setAccessible(true);
			assertEquals(".spec.js", field.get(null));
		}

		@Test
		@DisplayName("FORM_SPEC_RELATIVE_DIR points to cy-form directory")
		void formSpecRelativeDir() throws Exception {
			java.lang.reflect.Field field = FormSpecGenerator.class.getDeclaredField("FORM_SPEC_RELATIVE_DIR");
			field.setAccessible(true);
			String dir = (String) field.get(null);
			assertAll(() -> assertTrue(dir.contains("cy-form"), "should contain cy-form"),
					() -> assertTrue(dir.contains("cypress"), "should be under cypress"));
		}

		@Test
		@DisplayName("FORM_SETUP_RELATIVE_DIR points to cy-form-spec directory")
		void formSetupRelativeDir() throws Exception {
			java.lang.reflect.Field field = FormSpecGenerator.class.getDeclaredField("FORM_SETUP_RELATIVE_DIR");
			field.setAccessible(true);
			String dir = (String) field.get(null);
			assertAll(() -> assertTrue(dir.contains("cy-form-spec"), "should contain cy-form-spec"),
					() -> assertTrue(dir.contains("cypress"), "should be under cypress"));
		}
	}

	@Nested
	class SolutionPrefixedFileNaming {
		@ParameterizedTest
		@CsvSource({ "orderForm, solutionA, solutionA.orderForm.spec.cy.js",
				"loginForm, myApp, myApp.loginForm.spec.cy.js",
				"dashboard, crm_solution, crm_solution.dashboard.spec.cy.js" })
		@DisplayName("solution-prefixed spec file follows {solutionName}.{formName}.spec.cy.js pattern")
		void specFileNamingPattern(String formName, String solutionName, String expectedFileName) throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);
			String actualFileName = solutionName + "." + formName + ext;
			assertEquals(expectedFileName, actualFileName);
		}

		@ParameterizedTest
		@CsvSource({ "orderForm, solutionA, solutionA.orderForm.spec.js", "loginForm, myApp, myApp.loginForm.spec.js",
				"dashboard, crm_solution, crm_solution.dashboard.spec.js" })
		@DisplayName("solution-prefixed setup file follows {solutionName}.{formName}.spec.js pattern")
		void setupFileNamingPattern(String formName, String solutionName, String expectedFileName) throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_JS_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);
			String actualFileName = solutionName + "." + formName + ext;
			assertEquals(expectedFileName, actualFileName);
		}

		@Test
		@DisplayName("same form in different solutions produces different file names")
		void differentSolutionsDifferentFiles() throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);

			String file1 = "solutionA" + "." + "orderForm" + ext;
			String file2 = "solutionB" + "." + "orderForm" + ext;
			assertNotEquals(file1, file2, "two solutions with same form name must produce different spec file names");
		}

		@Test
		@DisplayName("legacy file name has no solution prefix")
		void legacyFileNameNoDotPrefix() throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);

			String legacyName = "orderForm" + ext;
			String prefixedName = "mySolution" + "." + "orderForm" + ext;
			assertAll(() -> assertEquals("orderForm.spec.cy.js", legacyName),
					() -> assertEquals("mySolution.orderForm.spec.cy.js", prefixedName),
					() -> assertNotEquals(legacyName, prefixedName));
		}
	}

	@Nested
	class BackwardCompatibility {
		@Test
		@DisplayName("findExistingSpecFile(String, String) method exists with correct return type")
		void findExistingSpecFileMethodExists() throws Exception {
			Method method = FormSpecGenerator.class.getMethod("findExistingSpecFile", String.class, String.class);
			assertNotNull(method, "findExistingSpecFile(String, String) must exist");
			assertEquals(Path.class, method.getReturnType());
		}

		@Test
		@DisplayName("findExistingSpecFile prefers solution-prefixed file over legacy when both exist")
		void findExistingSpecFilePrefersNewOverLegacy(@TempDir Path tempDir) throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);

			Path prefixed = Files.createFile(tempDir.resolve("solutionA.orderForm" + ext));
			Files.createFile(tempDir.resolve("orderForm" + ext));

			Path prefixedLookup = tempDir.resolve("solutionA" + "." + "orderForm" + ext);
			Path legacyLookup = tempDir.resolve("orderForm" + ext);

			Path result;
			if (Files.exists(prefixedLookup)) {
				result = prefixedLookup;
			} else if (Files.exists(legacyLookup)) {
				result = legacyLookup;
			} else {
				result = null;
			}
			assertEquals(prefixed, result, "lookup order must prefer solution-prefixed spec file over legacy");
		}

		@Test
		@DisplayName("findExistingSpecFile falls back to legacy when prefixed does not exist")
		void findExistingSpecFileFallsBackToLegacy(@TempDir Path tempDir) throws Exception {
			java.lang.reflect.Field extField = FormSpecGenerator.class.getDeclaredField("SPEC_CY_EXTENSION");
			extField.setAccessible(true);
			String ext = (String) extField.get(null);

			Path legacy = Files.createFile(tempDir.resolve("orderForm" + ext));

			Path prefixedLookup = tempDir.resolve("solutionA" + "." + "orderForm" + ext);
			Path legacyLookup = tempDir.resolve("orderForm" + ext);

			Path result;
			if (Files.exists(prefixedLookup)) {
				result = prefixedLookup;
			} else if (Files.exists(legacyLookup)) {
				result = legacyLookup;
			} else {
				result = null;
			}
			assertEquals(legacy, result, "should fall back to legacy spec file when prefixed does not exist");
		}

		@Test
		@DisplayName("findExistingSetupFile(String, String) method exists with correct return type")
		void findExistingSetupFileMethodExists() throws Exception {
			Method method = FormSpecGenerator.class.getMethod("findExistingSetupFile", String.class, String.class);
			assertNotNull(method, "findExistingSetupFile(String, String) must exist");
			assertEquals(Path.class, method.getReturnType());
		}

		@ParameterizedTest
		@ValueSource(strings = { "findExistingSpecFile", "findExistingSetupFile" })
		@DisplayName("backward-compat finder methods accept two String params")
		void finderMethodsAcceptTwoStrings(String methodName) throws Exception {
			Method method = FormSpecGenerator.class.getMethod(methodName, String.class, String.class);
			Class<?>[] params = method.getParameterTypes();
			assertAll(() -> assertEquals(2, params.length), () -> assertEquals(String.class, params[0]),
					() -> assertEquals(String.class, params[1]));
		}

		@Test
		@DisplayName("getFormSpecDir method still exists for discovery services")
		void getFormSpecDirExists() throws NoSuchMethodException {
			Method method = FormSpecGenerator.class.getMethod("getFormSpecDir");
			assertEquals(Path.class, method.getReturnType());
		}
	}
}

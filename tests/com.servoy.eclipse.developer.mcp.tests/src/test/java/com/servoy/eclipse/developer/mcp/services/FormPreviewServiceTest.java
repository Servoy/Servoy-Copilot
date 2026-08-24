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
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class FormPreviewServiceTest {

	private FormPreviewService service;
	private Path tempDir;

	@BeforeEach
	void setUp() throws Exception {
		service = new FormPreviewService();
		tempDir = Files.createTempDirectory("formPreviewTest");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (Exception e) {
					/* ignore */
				}
			});
		}
	}

	// --- Input validation: screenshotForm ---

	@Nested
	class ScreenshotFormInputValidation {

		@Test
		@DisplayName("null form name returns Error: null or empty")
		void testScreenshotForm_nullFormName_returnsError() {
			String result = service.screenshotForm(null, 5);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		@Test
		@DisplayName("empty form name returns Error: null or empty")
		void testScreenshotForm_emptyFormName_returnsError() {
			String result = service.screenshotForm("", 5);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		@Test
		@DisplayName("blank form name returns Error: null or empty")
		void testScreenshotForm_blankFormName_returnsError() {
			String result = service.screenshotForm("   ", 5);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		@Test
		@DisplayName("screenshotForm method exists with (String, int) signature returning String")
		void testScreenshotFormMethodExists() throws NoSuchMethodException {
			Method m = FormPreviewService.class.getDeclaredMethod("screenshotForm", String.class, int.class);
			assertNotNull(m);
			assertEquals(String.class, m.getReturnType());
		}
	}

	// --- Input validation: showFormInBrowser (SVY-21195 AC1) ---

	@Nested
	class ShowFormInBrowserInputValidation {

		@Test
		@DisplayName("null form name returns Error: null or empty")
		void testShowFormInBrowser_nullFormName_returnsError() {
			String result = service.showFormInBrowser(null, false);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		@Test
		@DisplayName("empty string form name returns Error: null or empty")
		void testShowFormInBrowser_emptyFormName_returnsError() {
			String result = service.showFormInBrowser("", false);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		@Test
		@DisplayName("blank form name returns Error: null or empty")
		void testShowFormInBrowser_blankFormName_returnsError() {
			String result = service.showFormInBrowser("  ", false);
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Should start with 'Error:'");
			assertTrue(result.contains("null or empty"), "Should mention null or empty");
		}

		/**
		 * AC1: showFormInBrowser must NOT block when marker errors are present.
		 * Without an active project the call fails at the project-lookup step — not
		 * at a marker gate. This verifies that no hard marker gate fires before the
		 * project check, i.e. the implementation does not early-return an error due
		 * to markers before it even tries to obtain the project.
		 */
		@Test
		@DisplayName("showFormInBrowser fails at project lookup not at a marker gate (AC1)")
		void testShowFormInBrowser_noActiveProject_failsAtProjectLookupNotMarkerGate() {
			// No Servoy model / active project in a plain unit-test JVM
			String result = service.showFormInBrowser("anyForm", false);
			assertNotNull(result);
			// Must NOT contain marker-gate error strings
			assertFalse(result.contains("has problem markers"),
				"showFormInBrowser must not return a hard marker-gate error (AC1)");
			assertFalse(result.contains("has property type mismatches"),
				"showFormInBrowser must not return a hard property-validation error (AC1)");
			// Should return an Error: but only due to missing project/server, not markers
			assertTrue(result.startsWith("Error:"),
				"showFormInBrowser should return Error: when no project is active");
		}
	}

	// --- No Playwright code remains ---

	@Nested
	class NoPlaywrightCode {

		@Test
		void testNoPlaywrightFields() {
			List<String> playwrightFields = Arrays.stream(FormPreviewService.class.getDeclaredFields())
				.map(Field::getName)
				.filter(name -> name.toLowerCase().contains("playwright"))
				.collect(Collectors.toList());
			assertTrue(playwrightFields.isEmpty(),
				"No fields should reference Playwright but found: " + playwrightFields);
		}

		@Test
		void testNoPlaywrightMethods() {
			List<String> playwrightMethods = Arrays.stream(FormPreviewService.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.toLowerCase().contains("playwright"))
				.collect(Collectors.toList());
			assertTrue(playwrightMethods.isEmpty(),
				"No methods should reference Playwright but found: " + playwrightMethods);
		}

		@Test
		void testNoPlaywrightInnerClasses() {
			List<String> playwrightClasses = Arrays.stream(FormPreviewService.class.getDeclaredClasses())
				.map(Class::getSimpleName)
				.filter(name -> name.toLowerCase().contains("playwright"))
				.collect(Collectors.toList());
			assertTrue(playwrightClasses.isEmpty(),
				"No inner classes should reference Playwright but found: " + playwrightClasses);
		}

		@Test
		void testNoPlaywrightDirOrInstallMethods() {
			Method[] methods = FormPreviewService.class.getDeclaredMethods();
			boolean hasGetPlaywrightDir = Arrays.stream(methods).anyMatch(m -> m.getName().equals("getPlaywrightDir"));
			boolean hasEnsurePlaywright = Arrays.stream(methods)
				.anyMatch(m -> m.getName().equals("ensurePlaywrightInstalled"));
			assertFalse(hasGetPlaywrightDir, "getPlaywrightDir should not exist");
			assertFalse(hasEnsurePlaywright, "ensurePlaywrightInstalled should not exist");
		}

		@Test
		void testNoPlaywrightDirConstant() {
			List<String> constants = Arrays.stream(FormPreviewService.class.getDeclaredFields())
				.map(Field::getName)
				.filter(name -> name.contains("PLAYWRIGHT"))
				.collect(Collectors.toList());
			assertTrue(constants.isEmpty(),
				"PLAYWRIGHT_DIR constant should not exist but found: " + constants);
		}
	}

	// --- Shared Cypress installation ---

	@Nested
	class CypressIntegration {

		@Test
		void testReferencesFormSpecRunnerInBytecode() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is, "Should be able to load FormPreviewService class bytes");
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains("FormSpecRunner"),
				"FormPreviewService bytecode should reference FormSpecRunner");
		}
	}

	// --- RuntimeErrorCapture usage ---

	@Nested
	class RuntimeErrorCaptureUsage {

		@Test
		void testReferencesRuntimeErrorCaptureInBytecode() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is, "Should be able to load FormPreviewService class bytes");
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains("RuntimeErrorCapture"),
				"FormPreviewService bytecode should reference RuntimeErrorCapture");
		}

		@Test
		void testRuntimeErrorCaptureIsAutoCloseable() {
			assertTrue(AutoCloseable.class.isAssignableFrom(RuntimeErrorCapture.class),
				"RuntimeErrorCapture must implement AutoCloseable");
		}
	}

	// --- findScreenshotFile ---

	@Nested
	class FindScreenshotFile {

		@Test
		@DisplayName("finds PNG matching form name in root screenshot dir")
		void testFindScreenshotFile_findsPngMatchingFormName() throws Exception {
			Path pngFile = tempDir.resolve("myForm.png");
			Files.writeString(pngFile, "fake png");

			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			m.setAccessible(true);
			Path result = (Path)m.invoke(service, tempDir, "myForm");

			assertNotNull(result, "Should find the screenshot file");
			assertTrue(result.toString().endsWith(".png"), "Should end with .png");
			assertTrue(result.getFileName().toString().contains("myForm"), "Should contain form name");
		}

		@Test
		@DisplayName("finds PNG in subdirectory")
		void testFindScreenshotFile_findsPngInSubdirectory() throws Exception {
			Path subDir = tempDir.resolve("_screenshot_testForm.cy.js");
			Files.createDirectories(subDir);
			Path pngFile = subDir.resolve("testForm.png");
			Files.writeString(pngFile, "fake png");

			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			m.setAccessible(true);
			Path result = (Path)m.invoke(service, tempDir, "testForm");

			assertNotNull(result, "Should find screenshot in subdirectory");
			assertTrue(result.toString().endsWith(".png"), "Should end with .png");
		}

		@Test
		@DisplayName("returns null when no matching PNG exists")
		void testFindScreenshotFile_returnsNullWhenNoMatch() throws Exception {
			Files.writeString(tempDir.resolve("otherForm.png"), "fake png");

			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			m.setAccessible(true);
			Path result = (Path)m.invoke(service, tempDir, "myForm");

			assertNull(result, "Should return null when no matching screenshot exists");
		}

		@Test
		@DisplayName("returns null for empty directory")
		void testFindScreenshotFile_returnsNullForEmptyDir() throws Exception {
			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			m.setAccessible(true);
			Path result = (Path)m.invoke(service, tempDir, "anyForm");

			assertNull(result, "Should return null for empty directory");
		}

		@Test
		@DisplayName("ignores non-PNG files")
		void testFindScreenshotFile_ignoresNonPngFiles() throws Exception {
			Files.writeString(tempDir.resolve("myForm.jpg"), "fake jpg");
			Files.writeString(tempDir.resolve("myForm.txt"), "fake txt");

			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			m.setAccessible(true);
			Path result = (Path)m.invoke(service, tempDir, "myForm");

			assertNull(result, "Should ignore non-png files");
		}

		@Test
		void testFindScreenshotFileIsPrivate() throws NoSuchMethodException {
			Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
			assertNotNull(m);
			assertTrue(Modifier.isPrivate(m.getModifiers()), "findScreenshotFile should be private");
		}
	}

	// --- checkFormMarkers signature and behaviour (SVY-21195) ---

	@Nested
	class CheckFormMarkers {

		/**
		 * checkFormMarkers is a pure informational collector: it takes only the project and
		 * the form name. The frmFileOnly gating flag was removed because problem markers no
		 * longer block anything (SVY-21195).
		 */
		@Test
		@DisplayName("checkFormMarkers(ServoyProject, String) must exist, be private and return String")
		void testCheckFormMarkersIsInformationalCollector() {
			final Method m = findDeclaredMethod("checkFormMarkers");
			assertNotNull(m, "checkFormMarkers must exist");
			assertAll(
				() -> assertEquals(2, m.getParameterCount(),
					"checkFormMarkers must take exactly (ServoyProject, String) - the frmFileOnly gate flag was removed"),
				() -> assertEquals(String.class, m.getParameterTypes()[1], "second parameter must be the form name String"),
				() -> assertTrue(Modifier.isPrivate(m.getModifiers()), "checkFormMarkers must be private"),
				() -> assertEquals(String.class, m.getReturnType(), "checkFormMarkers must return String"));
		}

		/**
		 * No overload may reintroduce a boolean gating flag.
		 */
		@Test
		@DisplayName("no checkFormMarkers overload takes a boolean gating flag")
		void testCheckFormMarkersHasNoBooleanFlag() {
			for (Method candidate : FormPreviewService.class.getDeclaredMethods()) {
				if ("checkFormMarkers".equals(candidate.getName())) {
					for (Class<?> param : candidate.getParameterTypes()) {
						assertFalse(boolean.class.equals(param),
							"checkFormMarkers must not take a boolean gating flag - markers are informational only");
					}
				}
			}
		}

		/**
		 * AC5: checkFormMarkers must no longer reference IFolder (the bogus
		 * forms/&lt;name&gt;/ directory check has been removed).
		 */
		@Test
		@DisplayName("checkFormMarkers does not reference IFolder (no forms/<name>/ folder check) (AC5)")
		void testCheckFormMarkersNoFolderCheck() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is);
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertFalse(constantPool.contains("IFolder"),
				"FormPreviewService must not reference IFolder (no forms/<name>/ folder check)");
		}

		/**
		 * AC7: base path derived from SolutionSerializer.getFilePath().
		 */
		@Test
		@DisplayName("checkFormMarkers derives file paths from SolutionSerializer (AC7)")
		void testCheckFormMarkersReferencesSolutionSerializer() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is);
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains("SolutionSerializer"),
				"FormPreviewService bytecode must reference SolutionSerializer for file path derivation");
		}

		/**
		 * AC6: checkFormMarkers checks the .sec file in collect mode.
		 * DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT (".sec") is a
		 * compile-time constant that gets inlined into the class bytecode.
		 */
		@Test
		@DisplayName("checkFormMarkers references .sec extension for security file check (AC6)")
		void testCheckFormMarkersReferencesDataModelManager() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is);
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains(".sec"),
				"FormPreviewService bytecode must contain \".sec\" extension for security file check");
		}

		/**
		 * The shared getFormFiles helper enumerates the files that can belong to a form
		 * (.frm, .js, .sec) in one place, so both callers stop duplicating that knowledge.
		 */
		@Test
		@DisplayName("getFormFiles helper exists, is private and returns a List")
		void testGetFormFilesHelperExists() {
			final Method m = findDeclaredMethod("getFormFiles");
			assertNotNull(m, "getFormFiles must exist as a shared form-file enumeration helper");
			assertAll(
				() -> assertTrue(Modifier.isPrivate(m.getModifiers()), "getFormFiles must be private"),
				() -> assertEquals(java.util.List.class, m.getReturnType(), "getFormFiles must return a List of files"));
		}

		/**
		 * All three marker-carrying form file extensions must be present in the bytecode.
		 * They are compile-time String constants, so the compiler inlines them.
		 */
		@Test
		@DisplayName("form file enumeration covers .frm, .js and .sec")
		void testFormFileEnumerationCoversAllMarkerCarryingFiles() throws Exception {
			String constantPool = readConstantPool();
			assertAll(
				() -> assertTrue(constantPool.contains(".frm"), ".frm (form model) must be enumerated"),
				() -> assertTrue(constantPool.contains(".js"), ".js (form script) must be enumerated"),
				() -> assertTrue(constantPool.contains(".sec"), ".sec (form security) must be enumerated"));
		}

		/**
		 * .less files carry no problem markers, so they must not be enumerated.
		 */
		@Test
		@DisplayName("form file enumeration excludes .less (no markers are produced on it)")
		void testFormFileEnumerationExcludesLess() throws Exception {
			assertFalse(readConstantPool().contains(".less"),
				".less must not be enumerated - no builder produces problem markers on it");
		}
	}

	/**
	 * Returns the first declared method of FormPreviewService with the given name, or
	 * {@code null} when no such method exists.
	 */
	private static Method findDeclaredMethod(String name) {
		for (Method candidate : FormPreviewService.class.getDeclaredMethods()) {
			if (name.equals(candidate.getName())) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Reads the FormPreviewService class file and returns its raw bytes decoded as
	 * ISO-8859-1, which makes the constant pool's String literals greppable.
	 */
	private static String readConstantPool() throws Exception {
		String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
		try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
			assertNotNull(is, "Should be able to load FormPreviewService class bytes");
			return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
		}
	}

	// --- showFormInBrowser gate behaviour (SVY-21195 AC1) ---

	@Nested
	class ShowFormInBrowserGateBehaviour {

		/**
		 * AC1: showFormInBrowser appends findings as a "Warning:" section instead of
		 * returning an "Error:" blocking response. Verified structurally by checking
		 * that the class bytecode contains the success prefix and the warning-append
		 * literal — both of which are string constants in the implementation.
		 */
		@Test
		@DisplayName("showFormInBrowser bytecode shows warnings are appended, not blocking (AC1)")
		void testShowFormInBrowserAppendsWarningsNotBlocking() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is, "Should be able to load FormPreviewService class bytes");
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains("Opened form '"),
				"showFormInBrowser must produce an 'Opened form ' success message");
			assertTrue(constantPool.contains("\n\nWarning: "),
				"showFormInBrowser must append findings as '\\n\\nWarning: ' — not block with Error:");
		}
	}

	// --- validateFormProperties gate behaviour (SVY-21195 AC4) ---

	@Nested
	class ValidateFormPropertiesGateBehaviour {

		@Test
		@DisplayName("validateFormProperties method is private and returns String")
		void testValidateFormPropertiesIsPrivate() {
			Method m = null;
			for (Method candidate : FormPreviewService.class.getDeclaredMethods()) {
				if ("validateFormProperties".equals(candidate.getName()) && candidate.getParameterCount() == 2) {
					m = candidate;
					break;
				}
			}
			assertNotNull(m, "validateFormProperties(ServoyProject, String) must exist");
			assertTrue(Modifier.isPrivate(m.getModifiers()), "validateFormProperties must be private");
			assertEquals(String.class, m.getReturnType(), "validateFormProperties must return String");
		}

		/**
		 * AC4: screenshotForm blocks when validateFormProperties detects type
		 * mismatches. The error message built by validateFormProperties contains
		 * "property type mismatches" — verifiable in the class constant pool.
		 */
		@Test
		@DisplayName("validateFormProperties produces 'property type mismatches' error string (AC4)")
		void testValidateFormPropertiesPropertyTypeMismatchErrorString() throws Exception {
			String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
			byte[] classBytes;
			try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
				assertNotNull(is, "Should be able to load FormPreviewService class bytes");
				classBytes = is.readAllBytes();
			}
			String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(constantPool.contains("property type mismatches"),
				"validateFormProperties must build an error string containing 'property type mismatches' (AC4)");
		}
	}

	// --- collectErrorMarkers ---

	@Nested
	class CollectErrorMarkers {

		@Test
		@DisplayName("collectErrorMarkers method is private")
		void testCollectErrorMarkersIsPrivate() {
			Method m = null;
			for (Method candidate : FormPreviewService.class.getDeclaredMethods()) {
				if ("collectErrorMarkers".equals(candidate.getName())) {
					m = candidate;
					break;
				}
			}
			assertNotNull(m, "collectErrorMarkers method must exist");
			assertTrue(Modifier.isPrivate(m.getModifiers()), "collectErrorMarkers must be private");
		}
	}
}

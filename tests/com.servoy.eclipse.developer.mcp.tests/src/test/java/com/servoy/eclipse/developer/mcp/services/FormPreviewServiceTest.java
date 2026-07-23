package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FormPreviewServiceTest {
	private FormPreviewService service;
	private Path tempDir;

	@Before
	public void setUp() throws Exception {
		service = new FormPreviewService();
		tempDir = Files.createTempDirectory("formPreviewTest");
	}

	@After
	public void tearDown() throws Exception {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception e) {
					/* ignore */ }
			});
		}
	}

	// --- Input validation ---

	@Test
	public void testScreenshotForm_nullFormName_returnsError() {
		String result = service.screenshotForm(null, 5);
		assertNotNull(result);
		assertTrue("Should start with 'Error:'", result.startsWith("Error:"));
		assertTrue("Should mention null or empty", result.contains("null or empty"));
	}

	@Test
	public void testScreenshotForm_emptyFormName_returnsError() {
		String result = service.screenshotForm("", 5);
		assertNotNull(result);
		assertTrue("Should start with 'Error:'", result.startsWith("Error:"));
		assertTrue("Should mention null or empty", result.contains("null or empty"));
	}

	@Test
	public void testScreenshotForm_blankFormName_returnsError() {
		String result = service.screenshotForm("   ", 5);
		assertNotNull(result);
		assertTrue("Should start with 'Error:'", result.startsWith("Error:"));
		assertTrue("Should mention null or empty", result.contains("null or empty"));
	}

	// --- No Playwright code remains (AC2, AC9) ---

	@Test
	public void testNoPlaywrightFields() {
		List<String> playwrightFields = Arrays.stream(FormPreviewService.class.getDeclaredFields()).map(Field::getName)
				.filter(name -> name.toLowerCase().contains("playwright")).collect(Collectors.toList());
		assertTrue("No fields should reference Playwright but found: " + playwrightFields, playwrightFields.isEmpty());
	}

	@Test
	public void testNoPlaywrightMethods() {
		List<String> playwrightMethods = Arrays.stream(FormPreviewService.class.getDeclaredMethods())
				.map(Method::getName).filter(name -> name.toLowerCase().contains("playwright"))
				.collect(Collectors.toList());
		assertTrue("No methods should reference Playwright but found: " + playwrightMethods,
				playwrightMethods.isEmpty());
	}

	@Test
	public void testNoPlaywrightInnerClasses() {
		List<String> playwrightClasses = Arrays.stream(FormPreviewService.class.getDeclaredClasses())
				.map(Class::getSimpleName).filter(name -> name.toLowerCase().contains("playwright"))
				.collect(Collectors.toList());
		assertTrue("No inner classes should reference Playwright but found: " + playwrightClasses,
				playwrightClasses.isEmpty());
	}

	@Test
	public void testNoPlaywrightDirOrInstallMethods() {
		Method[] methods = FormPreviewService.class.getDeclaredMethods();
		boolean hasGetPlaywrightDir = Arrays.stream(methods).anyMatch(m -> m.getName().equals("getPlaywrightDir"));
		boolean hasEnsurePlaywright = Arrays.stream(methods)
				.anyMatch(m -> m.getName().equals("ensurePlaywrightInstalled"));
		assertTrue("getPlaywrightDir should not exist", !hasGetPlaywrightDir);
		assertTrue("ensurePlaywrightInstalled should not exist", !hasEnsurePlaywright);
	}

	@Test
	public void testNoPlaywrightDirConstant() {
		List<String> constants = Arrays.stream(FormPreviewService.class.getDeclaredFields()).map(Field::getName)
				.filter(name -> name.contains("PLAYWRIGHT")).collect(Collectors.toList());
		assertTrue("PLAYWRIGHT_DIR constant should not exist but found: " + constants, constants.isEmpty());
	}

	// --- Shared Cypress installation (AC10) ---

	@Test
	public void testReferencesFormSpecRunnerInBytecode() throws Exception {
		String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
		byte[] classBytes;
		try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
			assertNotNull("Should be able to load FormPreviewService class bytes", is);
			classBytes = is.readAllBytes();
		}
		String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertTrue("FormPreviewService bytecode should reference FormSpecRunner",
				constantPool.contains("FormSpecRunner"));
	}

	// --- RuntimeErrorCapture usage (AC8) ---

	@Test
	public void testReferencesRuntimeErrorCaptureInBytecode() throws Exception {
		String classResource = FormPreviewService.class.getName().replace('.', '/') + ".class";
		byte[] classBytes;
		try (var is = FormPreviewService.class.getClassLoader().getResourceAsStream(classResource)) {
			assertNotNull("Should be able to load FormPreviewService class bytes", is);
			classBytes = is.readAllBytes();
		}
		String constantPool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertTrue("FormPreviewService bytecode should reference RuntimeErrorCapture",
				constantPool.contains("RuntimeErrorCapture"));
	}

	@Test
	public void testRuntimeErrorCaptureIsAutoCloseable() {
		assertTrue("RuntimeErrorCapture must implement AutoCloseable",
				AutoCloseable.class.isAssignableFrom(RuntimeErrorCapture.class));
	}

	// --- findScreenshotFile ---

	@Test
	public void testFindScreenshotFile_findsPngMatchingFormName() throws Exception {
		Path pngFile = tempDir.resolve("myForm.png");
		Files.writeString(pngFile, "fake png");

		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		Path result = (Path) m.invoke(service, tempDir, "myForm");

		assertNotNull("Should find the screenshot file", result);
		assertTrue("Should end with .png", result.toString().endsWith(".png"));
		assertTrue("Should contain form name", result.getFileName().toString().contains("myForm"));
	}

	@Test
	public void testFindScreenshotFile_findsPngInSubdirectory() throws Exception {
		Path subDir = tempDir.resolve("_screenshot_testForm.cy.js");
		Files.createDirectories(subDir);
		Path pngFile = subDir.resolve("testForm.png");
		Files.writeString(pngFile, "fake png");

		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		Path result = (Path) m.invoke(service, tempDir, "testForm");

		assertNotNull("Should find screenshot in subdirectory", result);
		assertTrue("Should end with .png", result.toString().endsWith(".png"));
	}

	@Test
	public void testFindScreenshotFile_returnsNullWhenNoMatch() throws Exception {
		Files.writeString(tempDir.resolve("otherForm.png"), "fake png");

		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		Path result = (Path) m.invoke(service, tempDir, "myForm");

		assertNull("Should return null when no matching screenshot exists", result);
	}

	@Test
	public void testFindScreenshotFile_returnsNullForEmptyDir() throws Exception {
		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		Path result = (Path) m.invoke(service, tempDir, "anyForm");

		assertNull("Should return null for empty directory", result);
	}

	@Test
	public void testFindScreenshotFile_ignoresNonPngFiles() throws Exception {
		Files.writeString(tempDir.resolve("myForm.jpg"), "fake jpg");
		Files.writeString(tempDir.resolve("myForm.txt"), "fake txt");

		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		m.setAccessible(true);
		Path result = (Path) m.invoke(service, tempDir, "myForm");

		assertNull("Should ignore non-png files", result);
	}

	// --- screenshotForm method structure (AC1) ---

	@Test
	public void testScreenshotFormMethodExists() throws NoSuchMethodException {
		Method m = FormPreviewService.class.getDeclaredMethod("screenshotForm", String.class, int.class);
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	public void testFindScreenshotFileIsPrivate() throws NoSuchMethodException {
		Method m = FormPreviewService.class.getDeclaredMethod("findScreenshotFile", Path.class, String.class);
		assertNotNull(m);
		assertTrue("findScreenshotFile should be private", Modifier.isPrivate(m.getModifiers()));
	}
}

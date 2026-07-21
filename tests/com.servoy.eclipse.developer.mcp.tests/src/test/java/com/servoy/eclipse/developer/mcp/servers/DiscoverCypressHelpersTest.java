package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the private {@code discoverCypressHelpers} method in
 * {@link ServoyTestingServer}. Uses reflection to invoke the private method
 * directly with filesystem fixtures.
 */
public class DiscoverCypressHelpersTest {

	private Method discoverMethod;
	private ServoyTestingServer server;
	private Path tempDir;

	@Before
	public void setUp() throws Exception {
		discoverMethod = ServoyTestingServer.class.getDeclaredMethod("discoverCypressHelpers",
				java.nio.file.Path.class);
		discoverMethod.setAccessible(true);
		server = ServoyTestingServer.class.getDeclaredConstructor().newInstance();
		tempDir = Files.createTempDirectory("discover-helpers-test");
	}

	@After
	public void tearDown() throws Exception {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private String invoke(Path supportDir) throws Exception {
		return (String) discoverMethod.invoke(server, supportDir);
	}

	// --- Basic behavior ---

	@Test
	public void testReturnsNullForMissingDir() throws Exception {
		Path missing = tempDir.resolve("does-not-exist");
		String result = invoke(missing);
		assertNull(result);
	}

	@Test
	public void testReturnsEmptyForScaffoldOnly() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Files.createDirectories(supportDir);
		Files.writeString(supportDir.resolve("commands.js"),
				"// Add custom Cypress commands here.\n// See docs\n", StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertNotNull(result);
		assertTrue("Should skip scaffold-only files", result.isBlank());
	}

	@Test
	public void testReturnsContentForJsFiles() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Files.createDirectories(supportDir);
		Files.writeString(supportDir.resolve("commands.js"),
				"Cypress.Commands.add('login', () => { cy.visit('/'); });\n", StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertTrue(result.contains("commands.js"));
		assertTrue(result.contains("Cypress.Commands.add('login'"));
	}

	@Test
	public void testReturnsContentForTsFiles() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Files.createDirectories(supportDir);
		Files.writeString(supportDir.resolve("scccLogin.ts"),
				"export function defaultLoginTestOne(): void {\n  cy.visit('/');\n}\n",
				StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertTrue(result.contains("scccLogin.ts"));
		assertTrue(result.contains("defaultLoginTestOne"));
	}

	@Test
	public void testExcludesE2eFiles() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Files.createDirectories(supportDir);
		Files.writeString(supportDir.resolve("e2e.js"), "import './commands';\n", StandardCharsets.UTF_8);
		Files.writeString(supportDir.resolve("e2e.ts"), "import './commands';\n", StandardCharsets.UTF_8);
		Files.writeString(supportDir.resolve("commands.js"),
				"Cypress.Commands.add('test', () => {});\n", StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertFalse(result.contains("e2e.js"));
		assertFalse(result.contains("e2e.ts"));
		assertTrue(result.contains("commands.js"));
	}

	// --- Recursive scanning ---

	@Test
	public void testDiscoversFilesInSubdirectories() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Path subDir = supportDir.resolve("helpers");
		Files.createDirectories(subDir);
		Files.writeString(subDir.resolve("navigation.ts"),
				"export function navigateTo(): void { cy.get('.nav').click(); }\n",
				StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertNotNull(result);
		assertTrue("Should show relative path including subdirectory",
				result.contains("helpers/navigation.ts") || result.contains("helpers\\navigation.ts"));
		assertTrue(result.contains("navigateTo"));
	}

	@Test
	public void testDiscoversDeepNestedFiles() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Path deepDir = supportDir.resolve("util").resolve("auth");
		Files.createDirectories(deepDir);
		Files.writeString(deepDir.resolve("oauth.ts"),
				"export function oauthLogin(): void { cy.request('/oauth'); }\n",
				StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertNotNull(result);
		assertTrue("Should show full relative path",
				result.contains("util/auth/oauth.ts") || result.contains("util\\auth\\oauth.ts"));
		assertTrue(result.contains("oauthLogin"));
	}

	@Test
	public void testCombinesRootAndSubdirFiles() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Files.createDirectories(supportDir);
		Files.writeString(supportDir.resolve("commands.ts"),
				"Cypress.Commands.add('login', () => {});\n", StandardCharsets.UTF_8);

		Path subDir = supportDir.resolve("helpers");
		Files.createDirectories(subDir);
		Files.writeString(subDir.resolve("navigation.ts"),
				"export function addOnsServoyAI(): void {}\n", StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertTrue(result.contains("commands.ts"));
		assertTrue(result.contains("Cypress.Commands.add('login'"));
		assertTrue(result.contains("navigation.ts"));
		assertTrue(result.contains("addOnsServoyAI"));
	}

	@Test
	public void testExcludesE2eInSubdirs() throws Exception {
		Path supportDir = tempDir.resolve("support");
		Path subDir = supportDir.resolve("nested");
		Files.createDirectories(subDir);
		Files.writeString(subDir.resolve("e2e.js"), "import './commands';\n", StandardCharsets.UTF_8);
		Files.writeString(subDir.resolve("real.js"),
				"Cypress.Commands.add('x', () => {});\n", StandardCharsets.UTF_8);

		String result = invoke(supportDir);

		assertFalse(result.contains("e2e.js"));
		assertTrue(result.contains("real.js"));
	}
}

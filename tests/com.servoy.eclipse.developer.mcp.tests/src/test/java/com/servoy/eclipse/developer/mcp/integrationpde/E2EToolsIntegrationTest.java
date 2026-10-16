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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;

/**
 * Integration tests for the ServoyTestingServer E2E tools: {@code listE2ETests}
 * and {@code testE2E}.
 * <p>
 * These tools walk {@code jenkins-custom/e2e-test-scripts/cypress/e2e/} under
 * the workspace root (via {@code ResourcesPlugin}) so a running Eclipse
 * workbench is required. This runs as a JUnit Plug-in test.
 * <p>
 * <b>Scope note.</b> {@code testE2E} normally launches a full headless Cypress
 * run which is heavy and non-deterministic in this environment (needs a running
 * NG client and an installed Cypress binary). Rather than a live browser run we
 * assert the wiring/guard path: when no {@code .cy.js} spec exists for the
 * target form, {@code testE2E} must return a clear "spec file not found" error.
 * This mirrors how the accepted {@link CypressFormTestingIntegrationTest}
 * exercises the form-testing pipeline while keeping the assertion
 * deterministic. A live headless run is intentionally out of scope here.
 * {@code showAndTestE2E} (headed browser) is out of scope entirely per the
 * spec.
 * <p>
 * This test owns the {@code cypress/e2e} sibling directory (multi-form E2E
 * specs). The form-testing suite uses the separate {@code cypress/cy-form} and
 * {@code cypress/cy-form-spec} directories, so cleaning up {@code cypress/e2e}
 * here does not disturb other suites.
 */
public class E2EToolsIntegrationTest extends TestUtilitiesClassCopy {

	private ServoyTestingServer testingServer;

	public E2EToolsIntegrationTest() {
		super(null, null); // this test class does not use solution & res. project
	}

	@BeforeEach
	public void setUp() throws Exception {
		testingServer = new ServoyTestingServer();
		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");
		waitForAppServer();
	}

	@AfterEach
	public void tearDown() throws Exception {
		deleteE2eDir();
	}

	// -----------------------------------------------------------------------
	// listE2ETests - both branches
	// -----------------------------------------------------------------------

	@Test
	public void testListE2ETests_noDirectory_returnsClearMessage() throws Exception {
		deleteE2eDir();
		assertFalse(Files.exists(e2eDir()), "Precondition: e2e directory should be absent");

		String result = testingServer.listE2ETests();

		assertNotNull(result);
		assertTrue(result.contains("No E2E tests found") && result.contains("does not exist"),
				() -> "Should give a clear no-directory message: " + result);
	}

	@Test
	public void testListE2ETests_withSeededSpec_returnsIt() throws Exception {
		String formName = "e2eSeeded_" + System.currentTimeMillis();
		seedSpec(formName);

		String result = testingServer.listE2ETests();

		assertNotNull(result);
		assertTrue(result.contains("E2E Tests"), () -> "Should report the E2E tests header: " + result);
		assertTrue(result.contains(formName), () -> "Should list the seeded form name: " + result);
	}

	@Test
	public void testListE2ETests_emptyDirectory_returnsNoFilesMessage() throws Exception {
		// Directory exists but contains no .cy.js/.cy.ts files.
		Files.createDirectories(e2eDir());

		String result = testingServer.listE2ETests();

		assertNotNull(result);
		assertTrue(result.contains("No E2E test files found"), "Should report no test files found: " + result);
	}

	// -----------------------------------------------------------------------
	// testE2E - guard/wiring path (no live browser run; see class javadoc)
	// -----------------------------------------------------------------------

	@Test
	public void testTestE2E_missingSpec_returnsClearError() throws Exception {
		deleteE2eDir();
		String formName = "e2eMissing_" + System.currentTimeMillis();

		String result = testingServer.testE2E(formName);

		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("not found") && result.contains(formName),
				"Should return a clear error when no spec exists: " + result);
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private Path e2eDir() {
		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		return workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e");
	}

	private void seedSpec(String formName) throws IOException {
		Path dir = e2eDir();
		Files.createDirectories(dir);
		Path spec = dir.resolve(formName + ".cy.js");
		String content = "describe('" + formName + "', () => {\n"
				+ "  it('loads', () => { expect(true).to.equal(true); });\n" + "});\n";
		Files.write(spec, content.getBytes(StandardCharsets.UTF_8));
	}

	private void deleteE2eDir() throws IOException {
		Path dir = e2eDir();
		if (!Files.exists(dir))
			return;
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				Files.delete(d);
				return FileVisitResult.CONTINUE;
			}
		});
	}

}

package com.servoy.eclipse.developer.mcp.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Discovers Cypress test files for Servoy forms in both locations:
 * <ul>
 * <li>Form tests:
 * {@code {workspace}/jenkins-custom/e2e-test-scripts/cypress/cy-form/*.spec.cy.js}</li>
 * <li>E2E tests:
 * {@code {workspace}/jenkins-custom/e2e-test-scripts/cypress/e2e/*.cy.js} (or
 * .cy.ts)</li>
 * </ul>
 *
 * Note: discovery is workspace-wide, not per-solution. All solutions in the
 * workspace share the single cy-form and e2e directories.
 */
public class CypressTestDiscoveryService {
	private static final String SPEC_CY_EXTENSION = ".spec.cy.js";
	private static final String E2E_CY_JS_EXTENSION = ".cy.js";
	private static final String E2E_CY_TS_EXTENSION = ".cy.ts";

	private final FormSpecGenerator specGenerator = new FormSpecGenerator();

	/**
	 * Determines the type of Cypress test that exists for a given form.
	 *
	 * @return {@link TestType#FORM} if a form spec exists, {@link TestType#E2E} if
	 *         an E2E spec exists, or {@link TestType#NONE} if no test exists.
	 */
	public TestType getTestType(String formName) {
		if (hasFormTest(formName)) {
			return TestType.FORM;
		}
		if (hasE2ETest(formName)) {
			return TestType.E2E;
		}
		return TestType.NONE;
	}

	/**
	 * Returns true if ANY type of Cypress test exists for this form (form test or
	 * E2E test).
	 */
	public boolean hasTest(String formName) {
		return hasFormTest(formName) || hasE2ETest(formName);
	}

	/**
	 * Checks for a form-level spec test in cypress/cy-form/.
	 */
	public boolean hasFormTest(String formName) {
		Path testsDir = specGenerator.getFormSpecDir();
		if (testsDir == null || !Files.isDirectory(testsDir)) {
			return false;
		}
		return Files.exists(testsDir.resolve(formName + SPEC_CY_EXTENSION));
	}

	/**
	 * Checks for an E2E test in cypress/e2e/ (supports .cy.js and .cy.ts).
	 */
	public boolean hasE2ETest(String formName) {
		Path e2eDir = resolveE2EDir();
		if (e2eDir == null || !Files.isDirectory(e2eDir)) {
			return false;
		}
		// Check direct match first (flat structure)
		if (Files.exists(e2eDir.resolve(formName + E2E_CY_JS_EXTENSION))
				|| Files.exists(e2eDir.resolve(formName + E2E_CY_TS_EXTENSION))) {
			return true;
		}
		// Check recursively (nested subdirectories)
		try (Stream<Path> walk = Files.walk(e2eDir)) {
			return walk.anyMatch(p -> {
				String fileName = p.getFileName().toString();
				return fileName.equals(formName + E2E_CY_JS_EXTENSION)
						|| fileName.equals(formName + E2E_CY_TS_EXTENSION);
			});
		} catch (IOException e) {
			return false;
		}
	}

	public List<String> discoverAllTestForms() {
		Path testsDir = specGenerator.getFormSpecDir();
		if (testsDir == null || !Files.isDirectory(testsDir)) {
			return Collections.emptyList();
		}
		try (Stream<Path> files = Files.list(testsDir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(SPEC_CY_EXTENSION)).map(p -> {
				String fileName = p.getFileName().toString();
				return fileName.substring(0, fileName.length() - SPEC_CY_EXTENSION.length());
			}).toList();
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	public List<String> discoverAllE2ETests() {
		Path e2eDir = resolveE2EDir();
		if (e2eDir == null || !Files.isDirectory(e2eDir)) {
			return Collections.emptyList();
		}
		try (Stream<Path> walk = Files.walk(e2eDir)) {
			return walk.filter(p -> {
				String name = p.getFileName().toString();
				return name.endsWith(E2E_CY_JS_EXTENSION) || name.endsWith(E2E_CY_TS_EXTENSION);
			}).map(p -> {
				String fileName = p.getFileName().toString();
				if (fileName.endsWith(E2E_CY_JS_EXTENSION)) {
					return fileName.substring(0, fileName.length() - E2E_CY_JS_EXTENSION.length());
				}
				return fileName.substring(0, fileName.length() - E2E_CY_TS_EXTENSION.length());
			}).toList();
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	public List<String> discoverSolutionE2ETests() {
		Path e2eDir = resolveE2EDir();
		if (e2eDir == null || !Files.isDirectory(e2eDir)) {
			return Collections.emptyList();
		}
		// Scope to the active solution's subdirectory
		com.servoy.eclipse.model.nature.ServoyProject servoyProject = com.servoy.eclipse.core.ServoyModelManager
				.getServoyModelManager().getServoyModel().getActiveProject();
		if (servoyProject == null || servoyProject.getProject() == null) {
			return Collections.emptyList();
		}
		Path solutionDir = e2eDir.resolve(servoyProject.getProject().getName());
		if (!Files.isDirectory(solutionDir)) {
			return Collections.emptyList();
		}
		try (Stream<Path> walk = Files.walk(solutionDir)) {
			return walk.filter(p -> {
				String name = p.getFileName().toString();
				return name.endsWith(E2E_CY_JS_EXTENSION) || name.endsWith(E2E_CY_TS_EXTENSION);
			}).map(p -> {
				String fileName = p.getFileName().toString();
				if (fileName.endsWith(E2E_CY_JS_EXTENSION)) {
					return fileName.substring(0, fileName.length() - E2E_CY_JS_EXTENSION.length());
				}
				return fileName.substring(0, fileName.length() - E2E_CY_TS_EXTENSION.length());
			}).toList();
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}


	public boolean hasAnyTest() {
		Path testsDir = specGenerator.getFormSpecDir();
		if (testsDir != null && Files.isDirectory(testsDir)) {
			try (Stream<Path> files = Files.list(testsDir)) {
				if (files.anyMatch(p -> p.getFileName().toString().endsWith(SPEC_CY_EXTENSION))) {
					return true;
				}
			} catch (IOException e) {
				// fall through to E2E check
			}
		}
		Path e2eDir = resolveE2EDir();
		if (e2eDir != null && Files.isDirectory(e2eDir)) {
			try (Stream<Path> files = Files.list(e2eDir)) {
				return files.anyMatch(p -> {
					String name = p.getFileName().toString();
					return name.endsWith(E2E_CY_JS_EXTENSION) || name.endsWith(E2E_CY_TS_EXTENSION);
				});
			} catch (IOException e) {
				// fall through
			}
		}
		return false;
	}

	private Path resolveE2EDir() {
		try {
			Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
			return workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress")
					.resolve("e2e");
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Enum representing the type of Cypress test found for a form.
	 */
	public enum TestType {
		/** No test exists */
		NONE,
		/** Form-level spec test in cypress/cy-form/ */
		FORM,
		/** E2E navigation test in cypress/e2e/ */
		E2E
	}
}

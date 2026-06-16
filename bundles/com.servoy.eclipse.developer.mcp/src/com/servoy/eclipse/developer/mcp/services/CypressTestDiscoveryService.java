package com.servoy.eclipse.developer.mcp.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class CypressTestDiscoveryService {
	private static final String SPEC_CY_EXTENSION = ".spec.cy.js";

	private final FormSpecGenerator specGenerator = new FormSpecGenerator();

	public boolean hasTest(String formName) {
		Path testsDir = specGenerator.getFormsDir();
		if (testsDir == null || !Files.isDirectory(testsDir)) {
			return false;
		}
		return Files.exists(testsDir.resolve(formName + SPEC_CY_EXTENSION));
	}

	public List<String> discoverAllTestForms() {
		Path testsDir = specGenerator.getFormsDir();
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

	public boolean hasAnyTest() {
		Path testsDir = specGenerator.getFormsDir();
		if (testsDir == null || !Files.isDirectory(testsDir)) {
			return false;
		}
		try (Stream<Path> files = Files.list(testsDir)) {
			return files.anyMatch(p -> p.getFileName().toString().endsWith(SPEC_CY_EXTENSION));
		} catch (IOException e) {
			return false;
		}
	}
}

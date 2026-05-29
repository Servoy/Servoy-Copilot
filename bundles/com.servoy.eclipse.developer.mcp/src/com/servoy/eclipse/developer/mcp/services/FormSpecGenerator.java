package com.servoy.eclipse.developer.mcp.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Generates Playwright test spec files (.spec.pw.js) and Servoy setUp/tearDown scripts (.spec.js)
 * for Servoy forms. Files are written to {solutionProject}/medias/pwtests/ which is
 * excluded from the DLTK buildpath (no StackOverflow on modern JS syntax) and accessible
 * via readProjectResource.
 */
@Creatable
@SuppressWarnings("restriction")
public class FormSpecGenerator
{
	private static final String PWTESTS_DIR = "medias/pwtests";

	private static final Pattern DATA_SOURCE_PATTERN = Pattern.compile("\"dataSource\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ELEMENT_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("\"typeName\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern DATA_PROVIDER_PATTERN = Pattern.compile("\"dataProviderID\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * Generates spec files for the given form.
	 * Files are written to {solutionProject}/medias/pwtests/{formName}.spec.pw.js
	 * and {solutionProject}/medias/pwtests/{formName}.spec.js
	 */
	public String generateSpec(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project.";
			}

			IProject project = activeProject.getProject();
			IFile frmFile = project.getFile("forms/" + formName + ".frm");
			if (!frmFile.exists())
			{
				return "Error: Form file not found: forms/" + formName + ".frm";
			}

			String solutionName = activeProject.getSolution().getName();
			Path pwTestsDir = getPwTestsDir(solutionName);
			Files.createDirectories(pwTestsDir);

			Path pwSpecPath = pwTestsDir.resolve(formName + ".spec.pw.js");
			Path setupSpecPath = pwTestsDir.resolve(formName + ".spec.js");

			if (Files.exists(pwSpecPath) && Files.exists(setupSpecPath))
			{
				return "Spec files already exist: medias/pwtests/" + formName + ".spec.pw.js and .spec.js";
			}

			String frmContent = new String(Files.readAllBytes(frmFile.getLocation().toFile().toPath()), StandardCharsets.UTF_8);
			FormMetadata metadata = parseFrmFile(frmContent, formName);

			StringBuilder result = new StringBuilder();

			if (!Files.exists(pwSpecPath))
			{
				String pwContent = generateSpecContent(metadata);
				Files.writeString(pwSpecPath, pwContent, StandardCharsets.UTF_8);
				result.append("Created: medias/pwtests/").append(formName)
					.append(".spec.pw.js (").append(metadata.namedElements.size()).append(" element assertions)\n");
			}

			if (!Files.exists(setupSpecPath))
			{
				String setupContent = generateSetupContent(metadata);
				Files.writeString(setupSpecPath, setupContent, StandardCharsets.UTF_8);
				result.append("Created: medias/pwtests/").append(formName)
					.append(".spec.js (setUp/tearDown for data setup)");
			}

			return result.toString().trim();
		}
		catch (Exception e)
		{
			return "Error generating spec: " + e.getMessage();
		}
	}

	/**
	 * Checks if a .spec.pw.js file already exists for the given form.
	 */
	public boolean specExists(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return false;
			String solutionName = activeProject.getSolution().getName();
			Path pwSpecPath = getPwTestsDir(solutionName).resolve(formName + ".spec.pw.js");
			return Files.exists(pwSpecPath);
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Returns the path to the spec file for a given form.
	 */
	public Path getSpecFilePath(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return null;
			String solutionName = activeProject.getSolution().getName();
			return getPwTestsDir(solutionName).resolve(formName + ".spec.pw.js");
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Returns the pwtests directory for the given solution: {projectLocation}/medias/pwtests/
	 */
	public Path getPwTestsDir(String solutionName)
	{
		ServoyProject project = ServoyModelManager.getServoyModelManager().getServoyModel().getServoyProject(solutionName);
		if (project != null)
		{
			return project.getProject().getLocation().toFile().toPath().resolve(PWTESTS_DIR);
		}
		// Fallback: workspace root
		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		return workspaceRoot.resolve(solutionName).resolve(PWTESTS_DIR);
	}

	private FormMetadata parseFrmFile(String content, String formName)
	{
		FormMetadata metadata = new FormMetadata();
		metadata.formName = formName;

		Matcher dsMatcher = DATA_SOURCE_PATTERN.matcher(content);
		if (dsMatcher.find())
		{
			metadata.dataSource = dsMatcher.group(1);
		}

		String[] items = content.split("\\{");
		for (String item : items)
		{
			Matcher nameMatcher = ELEMENT_NAME_PATTERN.matcher(item);
			Matcher typeMatcher = TYPE_NAME_PATTERN.matcher(item);
			Matcher dpMatcher = DATA_PROVIDER_PATTERN.matcher(item);

			if (nameMatcher.find())
			{
				String name = nameMatcher.group(1);
				if (name.equals(formName)) continue;

				ElementInfo elem = new ElementInfo();
				elem.name = name;
				elem.typeName = typeMatcher.find() ? typeMatcher.group(1) : null;
				elem.dataProviderID = dpMatcher.find() ? dpMatcher.group(1) : null;
				elem.isWebComponent = item.contains("\"typeid\":47");
				elem.isButton = item.contains("\"typeid\":7") && item.contains("onActionMethodID");
				elem.isLabel = item.contains("\"typeid\":7") && !item.contains("onActionMethodID");

				metadata.namedElements.add(elem);
			}
		}

		return metadata;
	}

	/**
	 * Generates the Playwright spec content. No @properties annotation (not a Servoy file).
	 * Includes a navigateToForm helper with retry logic (Servoy session cleanup delay).
	 * Uses two describe blocks with minimal navigations:
	 * - "static checks" (combined into one test)
	 * - "interactions" (button clicks in a single test)
	 */
	private String generateSpecContent(FormMetadata metadata)
	{
		StringBuilder sb = new StringBuilder();

		sb.append("const { test, expect } = require('@playwright/test');\n\n");

		String baseUrl = getFormUrl(metadata.formName);

		// --- navigateToForm helper with retry (handles Servoy session cleanup delay) ---
		sb.append("const formUrl = '").append(baseUrl).append("';\n\n");
		sb.append("async function navigateToForm(page) {\n");
		sb.append("  for (let attempt = 0; attempt < 3; attempt++) {\n");
		sb.append("    try {\n");
		sb.append("      await page.goto(formUrl, { timeout: 30000 });\n");
		sb.append("      await page.waitForLoadState('domcontentloaded');\n");
		sb.append("      await page.locator('[data-cy^=\"").append(metadata.formName).append(".\"]').first().waitFor({ state: 'visible', timeout: 15000 });\n");
		sb.append("      return;\n");
		sb.append("    } catch (e) {\n");
		sb.append("      if (attempt === 2) throw e;\n");
		sb.append("      await page.waitForTimeout(3000);\n");
		sb.append("    }\n");
		sb.append("  }\n");
		sb.append("}\n\n");

		// --- Static checks (combined into one test to minimize navigations) ---
		sb.append("test.describe('").append(metadata.formName).append(" - static checks', () => {\n\n");

		sb.append("  test('loads without errors and all elements are visible', async ({ page }) => {\n");
		sb.append("    await navigateToForm(page);\n");
		sb.append("    await expect(page.locator('.svy-error, .error-overlay')).not.toBeVisible();\n");

		List<ElementInfo> visibleElements = metadata.namedElements.stream()
			.filter(e -> e.isWebComponent || e.isButton || e.isLabel)
			.limit(8)
			.toList();

		for (ElementInfo elem : visibleElements)
		{
			sb.append("    await expect(page.locator('[data-cy=\"").append(metadata.formName).append(".").append(elem.name).append("\"]')).toBeVisible();\n");
		}
		sb.append("  });\n\n");

		sb.append("});\n\n");

		// --- Interaction tests (single page load per test, with retry) ---
		List<ElementInfo> buttons = metadata.namedElements.stream()
			.filter(e -> e.isButton || (e.typeName != null && e.typeName.contains("button")))
			.limit(3)
			.toList();

		if (!buttons.isEmpty())
		{
			sb.append("test.describe('").append(metadata.formName).append(" - interactions', () => {\n\n");

			sb.append("  test('buttons are clickable', async ({ page }) => {\n");
			sb.append("    await navigateToForm(page);\n");

			for (ElementInfo button : buttons)
			{
				sb.append("    const ").append(button.name).append(" = page.locator('[data-cy=\"").append(metadata.formName).append(".").append(button.name).append("\"]');\n");
				sb.append("    await expect(").append(button.name).append(").toBeVisible();\n");
				sb.append("    await expect(").append(button.name).append(").toBeEnabled();\n");
			}
			sb.append("  });\n\n");

			sb.append("});\n");
		}

		return sb.toString();
	}


	/**
	 * Returns a relative URL path for the form preview.
	 * The baseURL (host + port + solution path) is provided by playwright.config.js.
	 */
	private String getFormUrl(String formName)
	{
		return "?formpreview=" + formName + "&svy_testmode=true";
	}

	/**
	 * Generates the Servoy setUp/tearDown script (.spec.js).
	 * This file DOES have @properties annotations since it's a Servoy scope file.
	 */
	private String generateSetupContent(FormMetadata metadata)
	{
		StringBuilder sb = new StringBuilder();

		sb.append("/**\n");
		sb.append(" * Form test setup/teardown for: ").append(metadata.formName).append("\n");
		if (metadata.dataSource != null)
		{
			sb.append(" * DataSource: ").append(metadata.dataSource).append("\n");
		}
		sb.append(" *\n");
		sb.append(" * This file runs inside the Servoy runtime BEFORE the Playwright assertions.\n");
		sb.append(" * Use spec_setUp() to prepare test data (load records, set variables, etc.)\n");
		sb.append(" * Use spec_tearDown() to clean up after tests.\n");
		sb.append(" */\n\n");

		sb.append("/**\n");
		sb.append(" * @properties={typeid:24,uuid:\"").append(UUID.randomUUID()).append("\"}\n");
		sb.append(" */\n");
		sb.append("function spec_setUp() {\n");
		if (metadata.dataSource != null)
		{
			sb.append("\t// DataSource: ").append(metadata.dataSource).append("\n");
			sb.append("\t// Load specific records for testing:\n");
			sb.append("\t// foundset.loadAllRecords();\n");
			sb.append("\t// Or filter to specific test data:\n");
			sb.append("\t// foundset.find();\n");
			sb.append("\t// foundset.search();\n");
		}
		else
		{
			sb.append("\t// No dataSource on this form - set up form variables or other state\n");
		}
		sb.append("}\n\n");

		sb.append("/**\n");
		sb.append(" * @properties={typeid:24,uuid:\"").append(UUID.randomUUID()).append("\"}\n");
		sb.append(" */\n");
		sb.append("function spec_tearDown() {\n");
		sb.append("\t// Clean up test data if needed\n");
		sb.append("\t// databaseManager.rollbackEditedRecords();\n");
		sb.append("}\n");

		return sb.toString();
	}

	private static class FormMetadata
	{
		String formName;
		String dataSource;
		List<ElementInfo> namedElements = new ArrayList<>();
	}

	private static class ElementInfo
	{
		String name;
		String typeName;
		String dataProviderID;
		boolean isWebComponent;
		boolean isButton;
		boolean isLabel;
	}
}

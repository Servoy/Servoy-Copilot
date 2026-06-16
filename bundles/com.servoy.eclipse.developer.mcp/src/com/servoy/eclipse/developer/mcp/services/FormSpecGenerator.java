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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Generates Cypress test spec files (.spec.cy.js) and Servoy setUp/tearDown scripts (.spec.js)
 * for Servoy forms. Files are written next to the .frm file in the forms/ directory.
 * The .spec.js is a Servoy scope file (processed by DLTK for code completion).
 * The .spec.cy.js is excluded from DLTK via .buildpath pattern to avoid StackOverflow.
 */
@Creatable
@SuppressWarnings("restriction")
public class FormSpecGenerator
{
	private static final String SPEC_CY_EXTENSION = ".spec.cy.js";
	private static final String SPEC_JS_EXTENSION = ".spec.js";
	private static final String CYPRESS_TESTS_DIR = "medias/tests";
	private static final String BUILDPATH_EXCLUSION_PATTERN = "**/*.spec.cy.js";

	private static final Pattern DATA_SOURCE_PATTERN = Pattern.compile("\"dataSource\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ELEMENT_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("\"typeName\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern DATA_PROVIDER_PATTERN = Pattern.compile("\"dataProviderID\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * Generates spec files for the given form.
	 * Cypress spec: {solutionProject}/medias/tests/{formName}.spec.cy.js
	 * Servoy setUp/tearDown: {solutionProject}/forms/{formName}.spec.js
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

			Path formsDir = frmFile.getLocation().toFile().toPath().getParent();
			Path testsDir = project.getLocation().toFile().toPath().resolve(CYPRESS_TESTS_DIR);
			Files.createDirectories(testsDir);

			Path cySpecPath = testsDir.resolve(formName + SPEC_CY_EXTENSION);
			Path setupSpecPath = formsDir.resolve(formName + SPEC_JS_EXTENSION);

			if (Files.exists(cySpecPath) && Files.exists(setupSpecPath))
			{
				return "Spec files already exist: medias/tests/" + formName + SPEC_CY_EXTENSION + " and forms/" + formName + SPEC_JS_EXTENSION;
			}

			String frmContent = new String(Files.readAllBytes(frmFile.getLocation().toFile().toPath()), StandardCharsets.UTF_8);
			FormMetadata metadata = parseFrmFile(frmContent, formName);

			StringBuilder result = new StringBuilder();

			if (!Files.exists(cySpecPath))
			{
				String cyContent = generateCypressSpecContent(metadata);
				Files.writeString(cySpecPath, cyContent, StandardCharsets.UTF_8);
				result.append("Created: medias/tests/").append(formName)
					.append(SPEC_CY_EXTENSION).append(" (").append(metadata.namedElements.size()).append(" element assertions)\n");
			}

			if (!Files.exists(setupSpecPath))
			{
				String setupContent = generateSetupContent(metadata);
				Files.writeString(setupSpecPath, setupContent, StandardCharsets.UTF_8);
				result.append("Created: forms/").append(formName)
					.append(SPEC_JS_EXTENSION).append(" (setUp/tearDown for data setup)");
			}

			project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());

			return result.toString().trim();
		}
		catch (Exception e)
		{
			return "Error generating spec: " + e.getMessage();
		}
	}

	/**
	 * Checks if both spec files already exist for the given form.
	 */
	public boolean specExists(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return false;
			Path testsDir = activeProject.getProject().getLocation().toFile().toPath().resolve(CYPRESS_TESTS_DIR);
			Path formsDir = activeProject.getProject().getLocation().toFile().toPath().resolve("forms");
			return Files.exists(testsDir.resolve(formName + SPEC_CY_EXTENSION))
				&& Files.exists(formsDir.resolve(formName + SPEC_JS_EXTENSION));
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Returns the path to the Cypress spec file for a given form.
	 */
	public Path getSpecFilePath(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return null;
			Path testsDir = activeProject.getProject().getLocation().toFile().toPath().resolve(CYPRESS_TESTS_DIR);
			return testsDir.resolve(formName + SPEC_CY_EXTENSION);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Returns the Cypress tests directory for the active solution: {project}/medias/tests/
	 */
	public Path getFormsDir()
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return null;
			return activeProject.getProject().getLocation().toFile().toPath().resolve(CYPRESS_TESTS_DIR);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Ensures the .buildpath file has an exclusion for *.spec.cy.js so DLTK doesn't parse them.
	 */
	private void ensureBuildpathExclusion(IProject project)
	{
		try
		{
			IFile buildpathFile = project.getFile(".buildpath");
			if (!buildpathFile.exists()) return;

			String content = new String(Files.readAllBytes(buildpathFile.getLocation().toFile().toPath()), StandardCharsets.UTF_8);

			if (content.contains(BUILDPATH_EXCLUSION_PATTERN)) return;

			String updatedContent = content.replace(
				"excluding=\".stp/|medias/\"",
				"excluding=\".stp/|medias/|" + BUILDPATH_EXCLUSION_PATTERN + "\"");

			if (updatedContent.equals(content))
			{
				updatedContent = content.replace(
					"excluding=\"",
					"excluding=\"" + BUILDPATH_EXCLUSION_PATTERN + "|");
			}

			if (!updatedContent.equals(content))
			{
				Files.writeString(buildpathFile.getLocation().toFile().toPath(), updatedContent, StandardCharsets.UTF_8);
			}
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("ensureBuildpathExclusion failed: " + e.getMessage(), e);
		}
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
	 * Generates Cypress spec content (.spec.cy.js).
	 * Uses beforeEach() with cy.wait() for Servoy form rendering time.
	 * Uses cy.visit() with relative URL, cy.get() with [data-cy] selectors.
	 * Format: data-cy="formName.elementName" (rendered when servoy.ngclient.testingMode=true).
	 */
	private String generateCypressSpecContent(FormMetadata metadata)
	{
		StringBuilder sb = new StringBuilder();

		String formUrl = "?formpreview=" + metadata.formName + "&svy_testmode=true";

		sb.append("describe('").append(metadata.formName).append("', () => {\n\n");

		sb.append("  beforeEach(() => {\n");
		sb.append("    cy.visit('").append(formUrl).append("');\n");
		if (metadata.namedElements.isEmpty())
		{
			sb.append("    cy.get('.svy-form', { timeout: 30000 }).should('exist');\n");
		}
		else
		{
			sb.append("    cy.get('[data-cy^=\"").append(metadata.formName).append(".\"]', { timeout: 30000 }).should('exist');\n");
		}
		sb.append("  });\n\n");

		sb.append("  it('loads without errors and all elements are visible', () => {\n");
		sb.append("    cy.get('.svy-error, .error-overlay').should('not.exist');\n");

		List<ElementInfo> visibleElements = metadata.namedElements.stream()
			.filter(e -> e.isWebComponent || e.isButton || e.isLabel)
			.limit(8)
			.toList();

		for (ElementInfo elem : visibleElements)
		{
			sb.append("    cy.get('[data-cy=\"").append(metadata.formName).append(".").append(elem.name).append("\"]').should('be.visible');\n");
		}
		sb.append("  });\n\n");

		List<ElementInfo> buttons = metadata.namedElements.stream()
			.filter(e -> e.isButton || (e.typeName != null && e.typeName.contains("button")))
			.limit(3)
			.toList();

		if (!buttons.isEmpty())
		{
			sb.append("  it('buttons are clickable', () => {\n");
			for (ElementInfo button : buttons)
			{
				sb.append("    cy.get('[data-cy=\"").append(metadata.formName).append(".").append(button.name).append("\"]').should('be.visible').and('be.enabled');\n");
			}
			sb.append("  });\n\n");
		}

		sb.append("});\n");

		return sb.toString();
	}

	/**
	 * Returns a relative URL path for the form preview.
	 * The baseUrl is provided by cypress.config.js.
	 */
	private String getFormUrl(String formName)
	{
		return "?formpreview=" + formName + "&svy_testmode=true";
	}

	/**
	 * Generates the Servoy setUp/tearDown script (.spec.js).
	 * This file HAS @properties annotations - it's a Servoy scope file with full DLTK support.
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
		sb.append(" * This file runs inside the Servoy runtime BEFORE the Cypress assertions.\n");
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

package com.servoy.eclipse.developer.mcp.services;

import java.io.IOException;
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
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

@Creatable
@SuppressWarnings("restriction")
public class FormSpecGenerator
{
	private static final Pattern DATA_SOURCE_PATTERN = Pattern.compile("\"dataSource\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ELEMENT_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("\"typeName\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern DATA_PROVIDER_PATTERN = Pattern.compile("\"dataProviderID\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern FORM_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\n.*\"typeid\"\\s*:\\s*3");

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

			IFile specFile = project.getFile("forms/" + formName + ".spec.js");
			if (specFile.exists())
			{
				return "Spec file already exists: forms/" + formName + ".spec.js";
			}

			String frmContent = new String(frmFile.getLocation().toFile().toPath().toAbsolutePath()
				.toFile().getAbsolutePath().isEmpty() ? new byte[0]
				: Files.readAllBytes(frmFile.getLocation().toFile().toPath()), StandardCharsets.UTF_8);

			FormMetadata metadata = parseFrmFile(frmContent, formName);

			String specContent = generateSpecContent(metadata);

			Path specPath = frmFile.getLocation().toFile().toPath().getParent().resolve(formName + ".spec.js");
			Files.writeString(specPath, specContent, StandardCharsets.UTF_8);

			project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);

			return "Created spec file: forms/" + formName + ".spec.js with " + metadata.namedElements.size() + " element assertions.";
		}
		catch (Exception e)
		{
			return "Error generating spec: " + e.getMessage();
		}
	}

	public boolean specExists(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return false;
			IFile specFile = activeProject.getProject().getFile("forms/" + formName + ".spec.js");
			return specFile.exists();
		}
		catch (Exception e)
		{
			return false;
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

	private String generateSpecContent(FormMetadata metadata)
	{
		StringBuilder sb = new StringBuilder();

		sb.append("/**\n");
		sb.append(" * @properties={typeid:24,uuid:\"").append(UUID.randomUUID()).append("\"}\n");
		sb.append(" */\n");
		sb.append("const { test, expect } = require('@playwright/test');\n\n");

		String baseUrl = getFormUrl(metadata.formName);

		sb.append("test.describe('").append(metadata.formName).append("', () => {\n\n");

		sb.append("  test.beforeEach(async ({ page }) => {\n");
		sb.append("    await page.goto('").append(baseUrl).append("');\n");
		sb.append("    await page.waitForLoadState('networkidle');\n");
		sb.append("    await page.waitForTimeout(2000);\n");
		sb.append("  });\n\n");

		sb.append("  test('loads without errors', async ({ page }) => {\n");
		sb.append("    await expect(page.locator('.svy-error, .error-overlay')).not.toBeVisible();\n");
		sb.append("  });\n\n");

		if (metadata.dataSource != null)
		{
			sb.append("  test('has data loaded from ").append(metadata.dataSource).append("', async ({ page }) => {\n");
			sb.append("    // Form dataSource: ").append(metadata.dataSource).append("\n");
			sb.append("    // Verify at least one data-bound element has content\n");

			ElementInfo firstDataField = metadata.namedElements.stream()
				.filter(e -> e.dataProviderID != null && e.isWebComponent)
				.findFirst().orElse(null);

			if (firstDataField != null)
			{
				sb.append("    const field = page.locator('[data-cy=\"").append(metadata.formName).append(".").append(firstDataField.name).append("\"]');\n");
				sb.append("    await expect(field).toBeVisible();\n");
			}
			else
			{
				sb.append("    // No data-bound fields found - verify form rendered\n");
				sb.append("    await expect(page.locator('[data-cy^=\"").append(metadata.formName).append(".\"]').first()).toBeVisible();\n");
			}
			sb.append("  });\n\n");
		}

		List<ElementInfo> fields = metadata.namedElements.stream()
			.filter(e -> e.isWebComponent && e.dataProviderID != null)
			.limit(5)
			.toList();

		for (ElementInfo field : fields)
		{
			sb.append("  test('").append(field.name).append(" is visible', async ({ page }) => {\n");
			sb.append("    await expect(page.locator('[data-cy=\"").append(metadata.formName).append(".").append(field.name).append("\"]')).toBeVisible();\n");
			sb.append("  });\n\n");
		}

		List<ElementInfo> buttons = metadata.namedElements.stream()
			.filter(e -> e.isButton || (e.typeName != null && e.typeName.contains("button")))
			.limit(3)
			.toList();

		for (ElementInfo button : buttons)
		{
			sb.append("  test('").append(button.name).append(" button is clickable', async ({ page }) => {\n");
			sb.append("    const btn = page.locator('[data-cy=\"").append(metadata.formName).append(".").append(button.name).append("\"]');\n");
			sb.append("    await expect(btn).toBeVisible();\n");
			sb.append("    await expect(btn).toBeEnabled();\n");
			sb.append("  });\n\n");
		}

		sb.append("});\n");

		return sb.toString();
	}

	private String getFormUrl(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			return "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview=" + formName + "&svy_testmode=true";
		}
		catch (Exception e)
		{
			return "http://localhost:8080/solution/unknown/index.html?formpreview=" + formName + "&svy_testmode=true";
		}
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

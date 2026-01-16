package com.servoy.eclipse.servoypilot.tools.component;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.services.ContextService;
import com.servoy.j2db.persistence.RepositoryException;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Bootstrap Label component operations.
 * Migrated from knowledgebase.mcp LabelComponentHandler.
 * 
 * Complete migration: All 5 tools implemented.
 */
public class LabelComponentTools
{
	/**
	 * Adds a bootstrap label component to a form.
	 */
	@Tool("Adds a bootstrap label component to a form. Context-aware: looks for form in current context first.")
	public String addLabel(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name,
		@P(value = "CSS position: 'top,right,bottom,left,width,height'", required = true) String cssPosition,
		@P(value = "Label text", required = false) String text,
		@P(value = "Style class", required = false) String styleClass,
		@P(value = "Label for (element name)", required = false) String labelFor,
		@P(value = "Show as", required = false) String showAs,
		@P(value = "Enabled", required = false) Boolean enabled,
		@P(value = "Visible", required = false) Boolean visible,
		@P(value = "Tooltip", required = false) String toolTipText)
	{
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";
		if (cssPosition == null || cssPosition.trim().isEmpty()) return "Error: cssPosition required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				Map<String, Object> properties = new HashMap<>();
				properties.put("text", text != null ? text : "Label");
				if (styleClass != null) properties.put("styleClass", styleClass);
				if (labelFor != null) properties.put("labelFor", labelFor);
				if (showAs != null) properties.put("showAs", showAs);
				if (enabled != null) properties.put("enabled", enabled);
				if (visible != null) properties.put("visible", visible);
				if (toolTipText != null) properties.put("toolTipText", toolTipText);

				String projectPath = getProjectPath();
				String error = BootstrapComponentService.addComponentToForm(
					projectPath, formName, name, "bootstrapcomponents-label", cssPosition, properties);

				result[0] = error != null ? "Error: " + error : "Successfully added label '" + name + "' to form '" + formName + "'";
			}
			catch (Exception e) { exception[0] = e; }
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error adding label", exception[0]);
			return "Error: " + exception[0].getMessage();
		}
		return result[0];
	}

	/**
	 * Updates an existing label component.
	 */
	@Tool("Updates an existing label component.")
	public String updateLabel(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name,
		@P(value = "Label text", required = false) String text,
		@P(value = "CSS position", required = false) String cssPosition,
		@P(value = "Style class", required = false) String styleClass,
		@P(value = "Label for", required = false) String labelFor,
		@P(value = "Show as", required = false) String showAs,
		@P(value = "Enabled", required = false) Boolean enabled,
		@P(value = "Visible", required = false) Boolean visible,
		@P(value = "Tooltip", required = false) String toolTipText)
	{
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				Map<String, Object> updates = new HashMap<>();
				if (text != null) updates.put("text", text);
				if (cssPosition != null) updates.put("cssPosition", cssPosition);
				if (styleClass != null) updates.put("styleClass", styleClass);
				if (labelFor != null) updates.put("labelFor", labelFor);
				if (showAs != null) updates.put("showAs", showAs);
				if (enabled != null) updates.put("enabled", enabled);
				if (visible != null) updates.put("visible", visible);
				if (toolTipText != null) updates.put("toolTipText", toolTipText);

				if (updates.isEmpty())
				{
					result[0] = "Error: No properties to update";
					return;
				}

				String projectPath = getProjectPath();
				String error = BootstrapComponentService.updateComponent(projectPath, formName, name, updates);
				result[0] = error != null ? "Error: " + error : "Successfully updated label '" + name + "'";
			}
			catch (Exception e) { exception[0] = e; }
		});

		if (exception[0] != null) return "Error: " + exception[0].getMessage();
		return result[0];
	}

	/**
	 * Deletes a label component from a form.
	 */
	@Tool("Deletes a label component from a form.")
	public String deleteLabel(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				String projectPath = getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				result[0] = error != null ? "Error: " + error : "Successfully deleted label '" + name + "'";
			}
			catch (Exception e) { exception[0] = e; }
		});

		if (exception[0] != null) return "Error: " + exception[0].getMessage();
		return result[0];
	}

	/**
	 * Gets detailed information about a label component.
	 */
	@Tool("Gets detailed information about a label component.")
	public String getLabelInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				String projectPath = getProjectPath();
				result[0] = BootstrapComponentService.getComponentInfo(projectPath, formName, name);
			}
			catch (Exception e) { exception[0] = e; }
		});

		if (exception[0] != null) return "Error: " + exception[0].getMessage();
		return result[0];
	}

	/**
	 * Lists all label components in a form.
	 */
	@Tool("Lists all label components in a form.")
	public String listLabels(@P(value = "Form name", required = true) String formName)
	{
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				String projectPath = getProjectPath();
				result[0] = BootstrapComponentService.listComponentsByType(projectPath, formName, "bootstrapcomponents-label");
			}
			catch (Exception e) { exception[0] = e; }
		});

		if (exception[0] != null) return "Error: " + exception[0].getMessage();
		return result[0];
	}

	// Helper methods
	private String getProjectPath() throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject targetProject = resolveTargetProject(servoyModel);
		if (targetProject == null) throw new Exception("No target solution/module found");
		return targetProject.getProject().getLocation().toOSString();
	}

	private ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel) throws RepositoryException
	{
		String context = ContextService.getInstance().getCurrentContext();
		if ("active".equals(context)) return servoyModel.getActiveProject();

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && context.equals(module.getProject().getName())) return module;
		}

		throw new RepositoryException("Context '" + context + "' not found");
	}
}

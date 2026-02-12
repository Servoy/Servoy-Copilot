package com.servoy.eclipse.servoypilot.tools.component;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;
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
		if (formName != null && !formName.trim().isEmpty() && 
		    name != null && !name.trim().isEmpty() && 
		    cssPosition != null && !cssPosition.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("addLabel", () -> {
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

				return error != null ? "Error: " + error : "Successfully added label '" + name + "' to form '" + formName + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";
		return "Error: cssPosition required";
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
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("updateLabel", () -> {
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
					return "Error: No properties to update";
				}

				String projectPath = getProjectPath();
				String error = BootstrapComponentService.updateComponent(projectPath, formName, name, updates);
				return error != null ? "Error: " + error : "Successfully updated label '" + name + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	/**
	 * Deletes a label component from a form.
	 */
	@Tool("Deletes a label component from a form.")
	public String deleteLabel(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("deleteLabel", () -> {
				String projectPath = getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				return error != null ? "Error: " + error : "Successfully deleted label '" + name + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	/**
	 * Gets detailed information about a label component.
	 */
	@Tool("Gets detailed information about a label component.")
	public String getLabelInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("getLabelInfo", () -> {
				String projectPath = getProjectPath();
				return BootstrapComponentService.getComponentInfo(projectPath, formName, name);
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	/**
	 * Lists all label components in a form.
	 */
	@Tool("Lists all label components in a form.")
	public String listLabels(@P(value = "Form name", required = true) String formName)
	{
		if (formName != null && !formName.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("listLabels", () -> {
				String projectPath = getProjectPath();
				return BootstrapComponentService.listComponentsByType(projectPath, formName, "bootstrapcomponents-label");
			});
		}
		
		return "Error: formName required";
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
		String target = TargetService.getInstance().getCurrentTarget();
		if ("active".equals(target)) return servoyModel.getActiveProject();

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && target.equals(module.getProject().getName())) return module;
		}

		throw new RepositoryException("Target '" + target + "' not found");
	}
}

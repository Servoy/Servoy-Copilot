package com.servoy.eclipse.servoypilot.tools.component;

import java.util.HashMap;
import java.util.Map;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;
import com.servoy.j2db.persistence.RepositoryException;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Bootstrap Button component operations.
 * Migrated from knowledgebase.mcp ButtonComponentHandler.
 * 
 * Complete migration: All 5 tools implemented.
 */
public class ButtonComponentTools
{
	@Tool("Adds a bootstrap button component to a form. Context-aware: looks for form in current context first.")
	public String addButton(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name,
		@P(value = "CSS position: 'top,right,bottom,left,width,height'", required = true) String cssPosition,
		@P(value = "Button text", required = false) String text,
		@P(value = "Style class", required = false) String styleClass,
		@P(value = "Image style class", required = false) String imageStyleClass,
		@P(value = "Trailing image style class", required = false) String trailingImageStyleClass,
		@P(value = "Show as", required = false) String showAs,
		@P(value = "Tab sequence", required = false) Integer tabSeq,
		@P(value = "Enabled", required = false) Boolean enabled,
		@P(value = "Visible", required = false) Boolean visible,
		@P(value = "Tooltip", required = false) String toolTipText)
	{
		if (formName != null && !formName.trim().isEmpty() && 
		    name != null && !name.trim().isEmpty() && 
		    cssPosition != null && !cssPosition.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("addButton", () -> {
				Map<String, Object> properties = new HashMap<>();
				properties.put("text", text != null ? text : "Button");
				if (styleClass != null) properties.put("styleClass", styleClass);
				if (imageStyleClass != null) properties.put("imageStyleClass", imageStyleClass);
				if (trailingImageStyleClass != null) properties.put("trailingImageStyleClass", trailingImageStyleClass);
				if (showAs != null) properties.put("showAs", showAs);
				if (tabSeq != null) properties.put("tabSeq", tabSeq);
				if (enabled != null) properties.put("enabled", enabled);
				if (visible != null) properties.put("visible", visible);
				if (toolTipText != null) properties.put("toolTipText", toolTipText);

				String projectPath = getProjectPath();
				String error = BootstrapComponentService.addComponentToForm(
					projectPath, formName, name, "bootstrapcomponents-button", cssPosition, properties);

				return error != null ? "Error: " + error : "Successfully added button '" + name + "' to form '" + formName + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		if (name == null || name.trim().isEmpty()) return "Error: name required";
		return "Error: cssPosition required";
	}

	@Tool("Updates an existing button component.")
	public String updateButton(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name,
		@P(value = "Button text", required = false) String text,
		@P(value = "CSS position", required = false) String cssPosition,
		@P(value = "Style class", required = false) String styleClass,
		@P(value = "Image style class", required = false) String imageStyleClass,
		@P(value = "Trailing image style class", required = false) String trailingImageStyleClass,
		@P(value = "Show as", required = false) String showAs,
		@P(value = "Tab sequence", required = false) Integer tabSeq,
		@P(value = "Enabled", required = false) Boolean enabled,
		@P(value = "Visible", required = false) Boolean visible,
		@P(value = "Tooltip", required = false) String toolTipText)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("updateButton", () -> {
				Map<String, Object> updates = new HashMap<>();
				if (text != null) updates.put("text", text);
				if (cssPosition != null) updates.put("cssPosition", cssPosition);
				if (styleClass != null) updates.put("styleClass", styleClass);
				if (imageStyleClass != null) updates.put("imageStyleClass", imageStyleClass);
				if (trailingImageStyleClass != null) updates.put("trailingImageStyleClass", trailingImageStyleClass);
				if (showAs != null) updates.put("showAs", showAs);
				if (tabSeq != null) updates.put("tabSeq", tabSeq);
				if (enabled != null) updates.put("enabled", enabled);
				if (visible != null) updates.put("visible", visible);
				if (toolTipText != null) updates.put("toolTipText", toolTipText);

				if (updates.isEmpty())
				{
					return "Error: No properties to update";
				}

				String projectPath = getProjectPath();
				String error = BootstrapComponentService.updateComponent(projectPath, formName, name, updates);
				return error != null ? "Error: " + error : "Successfully updated button '" + name + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	@Tool("Deletes a button component from a form.")
	public String deleteButton(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("deleteButton", () -> {
				String projectPath = getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				return error != null ? "Error: " + error : "Successfully deleted button '" + name + "'";
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	@Tool("Gets detailed information about a button component.")
	public String getButtonInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("getButtonInfo", () -> {
				String projectPath = getProjectPath();
				return BootstrapComponentService.getComponentInfo(projectPath, formName, name);
			});
		}
		
		if (formName == null || formName.trim().isEmpty()) return "Error: formName required";
		return "Error: name required";
	}

	@Tool("Lists all button components in a form.")
	public String listButtons(@P(value = "Form name", required = true) String formName)
	{
		if (formName != null && !formName.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("listButtons", () -> {
				String projectPath = getProjectPath();
				return BootstrapComponentService.listComponentsByType(projectPath, formName, "bootstrapcomponents-button");
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
			if (module.getProject().getName().equals(target)) return module;
		}

		throw new RepositoryException("Target '" + target + "' not found");
	}
}

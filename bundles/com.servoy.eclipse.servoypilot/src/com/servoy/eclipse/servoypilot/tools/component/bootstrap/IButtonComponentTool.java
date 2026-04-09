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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.component.bootstrap;

import java.util.HashMap;
import java.util.Map;

import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.tools.component.ComponentToolsHelper;
import com.servoy.eclipse.servoypilot.tools.core.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IButtonComponentTool
{
	@Tool("Adds a bootstrap button component to a form. Context-aware: looks for form in current context first.")
	default String addButton(
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
				if (styleClass != null)
				{
					properties.put("styleClass", styleClass);
				}
				if (imageStyleClass != null)
				{
					properties.put("imageStyleClass", imageStyleClass);
				}
				if (trailingImageStyleClass != null)
				{
					properties.put("trailingImageStyleClass", trailingImageStyleClass);
				}
				if (showAs != null)
				{
					properties.put("showAs", showAs);
				}
				if (tabSeq != null)
				{
					properties.put("tabSeq", tabSeq);
				}
				if (enabled != null)
				{
					properties.put("enabled", enabled);
				}
				if (visible != null)
				{
					properties.put("visible", visible);
				}
				if (toolTipText != null)
				{
					properties.put("toolTipText", toolTipText);
				}

				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				String error = BootstrapComponentService.addComponentToForm(
					projectPath, formName, name, "bootstrapcomponents-button", cssPosition, properties);
				return error != null ? "Error: " + error : "Successfully added button '" + name + "' to form '" + formName + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		if (name == null || name.trim().isEmpty())
		{
			return "Error: name required";
		}
		return "Error: cssPosition required";
	}

	@Tool("Updates an existing button component.")
	default String updateButton(
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
				if (text != null)
				{
					updates.put("text", text);
				}
				if (cssPosition != null)
				{
					updates.put("cssPosition", cssPosition);
				}
				if (styleClass != null)
				{
					updates.put("styleClass", styleClass);
				}
				if (imageStyleClass != null)
				{
					updates.put("imageStyleClass", imageStyleClass);
				}
				if (trailingImageStyleClass != null)
				{
					updates.put("trailingImageStyleClass", trailingImageStyleClass);
				}
				if (showAs != null)
				{
					updates.put("showAs", showAs);
				}
				if (tabSeq != null)
				{
					updates.put("tabSeq", tabSeq);
				}
				if (enabled != null)
				{
					updates.put("enabled", enabled);
				}
				if (visible != null)
				{
					updates.put("visible", visible);
				}
				if (toolTipText != null)
				{
					updates.put("toolTipText", toolTipText);
				}

				if (updates.isEmpty())
				{
					return "Error: No properties to update";
				}

				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				String error = BootstrapComponentService.updateComponent(projectPath, formName, name, updates);
				return error != null ? "Error: " + error : "Successfully updated button '" + name + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Deletes a button component from a form.")
	default String deleteButton(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("deleteButton", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				return error != null ? "Error: " + error : "Successfully deleted button '" + name + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Gets detailed information about a button component.")
	default String getButtonInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Button name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("getButtonInfo", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				return BootstrapComponentService.getComponentInfo(projectPath, formName, name);
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Lists all button components in a form.")
	default String listButtons(@P(value = "Form name", required = true) String formName)
	{
		if (formName != null && !formName.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("listButtons", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				return BootstrapComponentService.listComponentsByType(projectPath, formName, "bootstrapcomponents-button");
			});
		}

		return "Error: formName required";
	}
}

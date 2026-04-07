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
package com.servoy.eclipse.servoypilot.tools.component.bootstrap.label;

import java.util.HashMap;
import java.util.Map;

import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.tools.component.ComponentToolsHelper;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface ILabelComponentTool
{
	@Tool("Adds a bootstrap label component to a form. Context-aware: looks for form in current context first.")
	default String addLabel(
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
				if (styleClass != null)
				{
					properties.put("styleClass", styleClass);
				}
				if (labelFor != null)
				{
					properties.put("labelFor", labelFor);
				}
				if (showAs != null)
				{
					properties.put("showAs", showAs);
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
					projectPath, formName, name, "bootstrapcomponents-label", cssPosition, properties);
				return error != null ? "Error: " + error : "Successfully added label '" + name + "' to form '" + formName + "'";
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

	@Tool("Updates an existing label component.")
	default String updateLabel(
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
				if (labelFor != null)
				{
					updates.put("labelFor", labelFor);
				}
				if (showAs != null)
				{
					updates.put("showAs", showAs);
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
				return error != null ? "Error: " + error : "Successfully updated label '" + name + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Deletes a label component from a form.")
	default String deleteLabel(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("deleteLabel", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				return error != null ? "Error: " + error : "Successfully deleted label '" + name + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Gets detailed information about a label component.")
	default String getLabelInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Label name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("getLabelInfo", () -> {
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

	@Tool("Lists all label components in a form.")
	default String listLabels(@P(value = "Form name", required = true) String formName)
	{
		if (formName != null && !formName.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("listLabels", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				return BootstrapComponentService.listComponentsByType(projectPath, formName, "bootstrapcomponents-label");
			});
		}

		return "Error: formName required";
	}
}

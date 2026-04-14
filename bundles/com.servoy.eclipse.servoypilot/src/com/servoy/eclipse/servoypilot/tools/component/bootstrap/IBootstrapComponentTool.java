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

import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.tools.component.ComponentToolsHelper;
import com.servoy.eclipse.servoypilot.tools.core.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Base interface for all Bootstrap component tool interfaces.
 *
 * Provides shared @Tool defaults for operations that are type-agnostic across all
 * bootstrap component types: delete and getInfo (both work by name, type is irrelevant).
 *
 * Leaf interfaces (e.g. IButtonComponentTool, ILabelComponentTool) must:
 * - implement the abstract getComponentType() to return their specific type string
 * - declare their own @Tool methods for add, update, and list (which are component-specific)
 *
 * ToolComposer discovers @Tool methods from the full interface hierarchy —
 * leaf @Tool declarations win over base @Tool declarations on the same signature.
 */
public interface IBootstrapComponentTool
{
	/**
	 * Returns the bootstrap component type string for this component
	 * (e.g. "bootstrapcomponents-button", "bootstrapcomponents-label").
	 * Abstract — every leaf interface must provide a default implementation.
	 */
	String getComponentType();

	@Tool("Deletes a bootstrap component from a form.")
	default String deleteComponent(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Component name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("deleteComponent", () -> {
				String projectPath = ComponentToolsHelper.getInstance().getProjectPath();
				String error = BootstrapComponentService.deleteComponent(projectPath, formName, name);
				return error != null ? "Error: " + error : "Successfully deleted component '" + name + "' from form '" + formName + "'";
			});
		}

		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName required";
		}
		return "Error: name required";
	}

	@Tool("Gets detailed information about a bootstrap component.")
	default String getComponentInfo(
		@P(value = "Form name", required = true) String formName,
		@P(value = "Component name", required = true) String name)
	{
		if (formName != null && !formName.trim().isEmpty() && name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("getComponentInfo", () -> {
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
}

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
package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.List;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.TargetService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface ITargetTool
{
	@Tool("Returns the current target (active solution or module name) and lists all available targets.")
	default String getTarget()
	{
		try
		{
			TargetService targetService = TargetService.getInstance();
			String currentTarget = targetService.getCurrentTarget();

			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return "Error: No active Servoy project";

			List<String> availableTargets = targetService.getAvailableTargets(activeProject);

			StringBuilder result = new StringBuilder();
			result.append("Current Target: ").append(currentTarget).append("\n\n");
			result.append("Available Targets:\n");
			for (String target : availableTargets)
			{
				if (target.equals(currentTarget)) result.append("  - ").append(target).append(" [CURRENT]\n");
				else result.append("  - ").append(target).append("\n");
			}
			result.append("\nNote: Target determines where new items (valuelists, forms, etc.) will be created.\n");
			result.append("Use setTarget to change the current target.");
			return result.toString();
		}
		catch (Exception e)
		{
			return "Error getting target: " + e.getMessage();
		}
	}

	@Tool("Sets the current target to 'active' (active solution) or a specific module name. " +
		"This determines where new items will be created.")
	default String setTarget(
		@P(value = "Target name: 'active' for active solution, or module name", required = true) String target)
	{
		if (target == null || target.trim().isEmpty()) return "Error: target parameter is required";

		try
		{
			TargetService targetService = TargetService.getInstance();
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null) return "Error: No active Servoy project";

			List<String> availableTargets = targetService.getAvailableTargets(activeProject);
			if (!availableTargets.contains(target))
			{
				StringBuilder error = new StringBuilder();
				error.append("Error: Target '").append(target).append("' not found.\n\nAvailable targets:\n");
				for (String availTarget : availableTargets) error.append("  - ").append(availTarget).append("\n");
				return error.toString();
			}

			targetService.setCurrentTarget(target);
			return "Target switched to: " + target + "\n\n" +
				"New items (valuelists, forms, etc.) will now be created in " +
				("active".equals(target) ? "the active solution" : "module '" + target + "'");
		}
		catch (Exception e)
		{
			return "Error setting target: " + e.getMessage();
		}
	}
}

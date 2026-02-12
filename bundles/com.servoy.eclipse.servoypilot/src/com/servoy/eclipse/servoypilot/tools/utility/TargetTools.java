package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.List;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.TargetService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for target management operations.
 * Migrated from knowledgebase.mcp ContextToolHandler (renamed to TargetTools).
 * 
 * Complete migration: All 2 tools implemented.
 */
public class TargetTools
{
	/**
	 * Gets the current target and available targets.
	 */
	@Tool("Returns the current target (active solution or module name) and lists all available targets.")
	public String getTarget()
	{
		try
		{
			TargetService targetService = TargetService.getInstance();
			String currentTarget = targetService.getCurrentTarget();

			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project";
			}

			List<String> availableTargets = targetService.getAvailableTargets(activeProject);

			StringBuilder result = new StringBuilder();
			result.append("Current Target: ").append(currentTarget).append("\n\n");
			result.append("Available Targets:\n");

			for (String target : availableTargets)
			{
				if (target.equals(currentTarget))
				{
					result.append("  - ").append(target).append(" [CURRENT]\n");
				}
				else
				{
					result.append("  - ").append(target).append("\n");
				}
			}

			result.append("\n");
			result.append("Note: Target determines where new items (valuelists, forms, etc.) will be created.\n");
			result.append("Use setTarget to change the current target.");

			return result.toString();
		}
		catch (Exception e)
		{
			return "Error getting target: " + e.getMessage();
		}
	}

	/**
	 * Sets the current target to a specific solution or module.
	 */
	@Tool("Sets the current target to 'active' (active solution) or a specific module name. " +
		"This determines where new items will be created.")
	public String setTarget(
		@P(value = "Target name: 'active' for active solution, or module name", required = true) String target)
	{
		if (target == null || target.trim().isEmpty())
		{
			return "Error: target parameter is required";
		}

		try
		{
			TargetService targetService = TargetService.getInstance();
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();

			if (activeProject == null)
			{
				return "Error: No active Servoy project";
			}

			// Validate target exists
			List<String> availableTargets = targetService.getAvailableTargets(activeProject);
			if (!availableTargets.contains(target))
			{
				StringBuilder error = new StringBuilder();
				error.append("Error: Target '").append(target).append("' not found.\n\n");
				error.append("Available targets:\n");
				for (String availTarget : availableTargets)
				{
					error.append("  - ").append(availTarget).append("\n");
				}
				return error.toString();
			}

			// Set target
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

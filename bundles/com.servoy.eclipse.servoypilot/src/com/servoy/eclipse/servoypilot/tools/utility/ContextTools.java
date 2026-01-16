package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.List;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.ContextService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for context management operations.
 * Migrated from knowledgebase.mcp ContextToolHandler.
 * 
 * Complete migration: All 2 tools implemented.
 */
public class ContextTools
{
	/**
	 * Gets the current context and available contexts.
	 */
	@Tool("Returns the current context (active solution or module name) and lists all available contexts.")
	public String getContext()
	{
		try
		{
			ContextService contextService = ContextService.getInstance();
			String currentContext = contextService.getCurrentContext();

			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project";
			}

			List<String> availableContexts = contextService.getAvailableContexts(activeProject);

			StringBuilder result = new StringBuilder();
			result.append("Current Context: ").append(currentContext).append("\n\n");
			result.append("Available Contexts:\n");

			for (String context : availableContexts)
			{
				if (context.equals(currentContext))
				{
					result.append("  - ").append(context).append(" [CURRENT]\n");
				}
				else
				{
					result.append("  - ").append(context).append("\n");
				}
			}

			result.append("\n");
			result.append("Note: Context determines where new items (valuelists, forms, etc.) will be created.\n");
			result.append("Use setContext to change the current context.");

			return result.toString();
		}
		catch (Exception e)
		{
			return "Error getting context: " + e.getMessage();
		}
	}

	/**
	 * Sets the current context to a specific solution or module.
	 */
	@Tool("Sets the current context to 'active' (active solution) or a specific module name. " +
		"This determines where new items will be created.")
	public String setContext(
		@P(value = "Context name: 'active' for active solution, or module name", required = true) String context)
	{
		if (context == null || context.trim().isEmpty())
		{
			return "Error: context parameter is required";
		}

		try
		{
			ContextService contextService = ContextService.getInstance();
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();

			if (activeProject == null)
			{
				return "Error: No active Servoy project";
			}

			// Validate context exists
			List<String> availableContexts = contextService.getAvailableContexts(activeProject);
			if (!availableContexts.contains(context))
			{
				StringBuilder error = new StringBuilder();
				error.append("Error: Context '").append(context).append("' not found.\n\n");
				error.append("Available contexts:\n");
				for (String availContext : availableContexts)
				{
					error.append("  - ").append(availContext).append("\n");
				}
				return error.toString();
			}

			// Set context
			contextService.setCurrentContext(context);

			return "Context switched to: " + context + "\n\n" +
				"New items (valuelists, forms, etc.) will now be created in " +
				("active".equals(context) ? "the active solution" : "module '" + context + "'");
		}
		catch (Exception e)
		{
			return "Error setting context: " + e.getMessage();
		}
	}
}

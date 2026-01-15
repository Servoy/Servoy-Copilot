package com.servoy.eclipse.servoypilot.services;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Solution;

/**
 * Service for managing current context (active solution or module).
 * Migrated from knowledgebase.mcp ContextService.
 * 
 * PILOT: Minimal implementation for context management.
 * Tracks which solution/module is the current target for create operations.
 */
public class ContextService
{
	private static ServoyProject currentContextProject = null;

	/**
	 * Get the current context project.
	 * If no context is explicitly set, returns the active project.
	 * 
	 * @return Current context project or active project as fallback
	 */
	public static ServoyProject getCurrentContextProject()
	{
		if (currentContextProject != null)
		{
			return currentContextProject;
		}

		// Fallback to active project
		return ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
	}

	/**
	 * Set the current context project explicitly.
	 * 
	 * @param project The project to set as current context
	 */
	public static void setCurrentContextProject(ServoyProject project)
	{
		currentContextProject = project;
	}

	/**
	 * Get the current context solution.
	 * 
	 * @return Current context solution or null if no context
	 */
	public static Solution getCurrentContextSolution()
	{
		ServoyProject project = getCurrentContextProject();
		return project != null ? project.getEditingSolution() : null;
	}

	/**
	 * Reset context to active project.
	 */
	public static void resetContext()
	{
		currentContextProject = null;
	}
}

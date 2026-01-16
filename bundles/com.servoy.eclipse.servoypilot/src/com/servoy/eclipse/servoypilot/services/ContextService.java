package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.List;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Manages the current context for write operations.
 * Migrated from knowledgebase.mcp ContextService.
 * Context determines which solution/module will receive new items.
 */
public class ContextService
{
	private static ContextService instance;
	private String currentContext = null; // null = active solution

	public static synchronized ContextService getInstance()
	{
		if (instance == null)
		{
			instance = new ContextService();
		}
		return instance;
	}

	/**
	 * Get current context. Returns "active" for active solution,
	 * or module name like "Module_A".
	 */
	public String getCurrentContext()
	{
		return currentContext != null ? currentContext : "active";
	}

	/**
	 * Set current context. Use "active" for active solution,
	 * or module name. Null is treated as "active".
	 */
	public void setCurrentContext(String context)
	{
		this.currentContext = ("active".equals(context) || context == null) ? null : context;
	}

	/**
	 * Reset to active solution context.
	 * Called on solution activation.
	 */
	public void resetToActiveSolution()
	{
		this.currentContext = null;
	}

	/**
	 * Get list of available contexts (active solution + modules).
	 */
	public List<String> getAvailableContexts(ServoyProject activeProject)
	{
		List<String> contexts = new ArrayList<>();
		contexts.add("active");

		if (activeProject != null)
		{
			ServoyProject[] modules = getModuleProjects(activeProject);
			for (ServoyProject module : modules)
			{
				contexts.add(module.getProject().getName());
			}
		}

		return contexts;
	}

	private ServoyProject[] getModuleProjects(ServoyProject activeProject)
	{
		try
		{
			return ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
		}
		catch (Exception e)
		{
			return new ServoyProject[0];
		}
	}

	// ===== Static helper methods for backward compatibility with pilot code =====

	/**
	 * Get the current context project.
	 * If no context is explicitly set, returns the active project.
	 * 
	 * @return Current context project or active project as fallback
	 */
	public static ServoyProject getCurrentContextProject()
	{
		String context = getInstance().getCurrentContext();
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		
		if ("active".equals(context) || context == null)
		{
			return activeProject;
		}
		
		// Find module by name
		ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && context.equals(module.getProject().getName()))
			{
				return module;
			}
		}
		
		// Fallback to active if module not found
		return activeProject;
	}
}

package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.List;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Manages the current target for write operations.
 * Migrated from knowledgebase.mcp ContextService (renamed to TargetService).
 * Target determines which solution/module will receive new items.
 */
public class TargetService
{
	private static TargetService instance;
	private String currentTarget = null; // null = active solution

	public static synchronized TargetService getInstance()
	{
		if (instance == null)
		{
			instance = new TargetService();
		}
		return instance;
	}

	/**
	 * Get current target. Returns "active" for active solution,
	 * or module name like "Module_A".
	 */
	public String getCurrentTarget()
	{
		return currentTarget != null ? currentTarget : "active";
	}

	/**
	 * Set current target. Use "active" for active solution,
	 * or module name. Null is treated as "active".
	 */
	public void setCurrentTarget(String target)
	{
		this.currentTarget = ("active".equals(target) || target == null) ? null : target;
	}

	/**
	 * Reset to active solution target.
	 * Called on solution activation.
	 */
	public void resetToActiveSolution()
	{
		this.currentTarget = null;
	}

	/**
	 * Get list of available targets (active solution + modules).
	 */
	public List<String> getAvailableTargets(ServoyProject activeProject)
	{
		List<String> targets = new ArrayList<>();
		targets.add("active");

		if (activeProject != null)
		{
			ServoyProject[] modules = getModuleProjects(activeProject);
			for (ServoyProject module : modules)
			{
				targets.add(module.getProject().getName());
			}
		}

		return targets;
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
	 * Get the current target project.
	 * If no target is explicitly set, returns the active project.
	 * 
	 * @return Current target project or active project as fallback
	 */
	public static ServoyProject getCurrentTargetProject()
	{
		String target = getInstance().getCurrentTarget();
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		
		if ("active".equals(target) || target == null)
		{
			return activeProject;
		}
		
		// Find module by name
		ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && target.equals(module.getProject().getName()))
			{
				return module;
			}
		}
		
		// Fallback to active if module not found
		return activeProject;
	}
}

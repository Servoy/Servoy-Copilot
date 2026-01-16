package com.servoy.eclipse.servoypilot.tools.core;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.ContextService;
import com.servoy.eclipse.servoypilot.services.StyleService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Servoy Style (CSS/LESS) operations.
 * Migrated from knowledgebase.mcp StyleHandler.
 * 
 * Complete migration: All 3 main tools implemented.
 */
public class StyleTools
{
	/**
	 * Lists all CSS class names in a LESS file.
	 */
	@Tool("Lists all CSS class names in a LESS file. Optional scope: 'current' for context only, 'all' for solution + modules.")
	public String getStyles(
		@P(value = "LESS file name (defaults to <solution-name>.less)", required = false) String lessFileName,
		@P(value = "Scope: 'current' or 'all' (default 'current')", required = false) String scope)
	{
		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = listStylesImpl(lessFileName, scope != null ? scope : "current");
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error listing styles", exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Adds or updates a CSS class in a LESS file.
	 */
	@Tool("Adds or updates a CSS class in a LESS file. Context-aware: style added to current context.")
	public String openStyle(
		@P(value = "CSS class name (without dot)", required = true) String className,
		@P(value = "CSS content (rules)", required = true) String cssContent,
		@P(value = "LESS file name (defaults to <solution-name>.less)", required = false) String lessFileName)
	{
		if (className == null || className.trim().isEmpty()) return "Error: className parameter is required";
		if (cssContent == null || cssContent.trim().isEmpty()) return "Error: cssContent parameter is required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = addOrUpdateStyleImpl(className, cssContent, lessFileName);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error adding/updating style: " + className, exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Deletes a CSS class from a LESS file.
	 */
	@Tool("Deletes a CSS class from a LESS file in the current context.")
	public String deleteStyle(
		@P(value = "CSS class name (without dot)", required = true) String className,
		@P(value = "LESS file name (defaults to <solution-name>.less)", required = false) String lessFileName)
	{
		if (className == null || className.trim().isEmpty()) return "Error: className parameter is required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = deleteStyleImpl(className, lessFileName);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error deleting style: " + className, exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	// =============================================
	// IMPLEMENTATION: listStyles
	// =============================================

	private String listStylesImpl(String lessFileName, String scope) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null)
		{
			throw new Exception("No active Servoy solution project found");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		if ("current".equals(scope))
		{
			// List from current context only
			String projectPath = targetProject.getProject().getLocation().toOSString();
			String solutionName = targetProject.getSolution().getName();

			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
				? lessFileName
				: solutionName + ".less";

			String stylesList = StyleService.listStyles(projectPath, solutionName, lessFileName);

			StringBuilder result = new StringBuilder();
			result.append("Styles in '").append(contextDisplay).append("' (file: ").append(targetFile).append("):\n\n");
			result.append(stylesList);

			return result.toString();
		}
		else
		{
			// List from all contexts
			StringBuilder result = new StringBuilder();
			result.append("Styles in all contexts:\n\n");

			// Current context
			String projectPath = targetProject.getProject().getLocation().toOSString();
			String solutionName = targetProject.getSolution().getName();
			String stylesList = StyleService.listStyles(projectPath, solutionName, lessFileName);

			result.append("=== ").append(contextDisplay).append(" ===\n");
			result.append(stylesList).append("\n\n");

			// Active solution (if different)
			if (!targetProject.equals(servoyProject))
			{
				projectPath = servoyProject.getProject().getLocation().toOSString();
				solutionName = servoyProject.getSolution().getName();
				stylesList = StyleService.listStyles(projectPath, solutionName, lessFileName);

				result.append("=== ").append(servoyProject.getProject().getName()).append(" (active solution) ===\n");
				result.append(stylesList).append("\n\n");
			}

			// Modules
			ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
			for (ServoyProject module : modules)
			{
				if (module != null && !module.equals(targetProject) && !module.equals(servoyProject))
				{
					projectPath = module.getProject().getLocation().toOSString();
					solutionName = module.getSolution().getName();
					stylesList = StyleService.listStyles(projectPath, solutionName, lessFileName);

					result.append("=== ").append(module.getProject().getName()).append(" ===\n");
					result.append(stylesList).append("\n\n");
				}
			}

			return result.toString();
		}
	}

	// =============================================
	// IMPLEMENTATION: openStyle (add/update)
	// =============================================

	private String addOrUpdateStyleImpl(String className, String cssContent, String lessFileName) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null)
		{
			throw new Exception("No active Servoy solution project found");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		// Remove leading dot if provided
		if (className.startsWith("."))
		{
			className = className.substring(1);
		}

		// Search for existing style across all contexts (UPDATE vs CREATE detection)
		String foundInContext = null;
		String foundProjectPath = null;
		String foundSolutionName = null;

		// Check current context first
		String projectPath = targetProject.getProject().getLocation().toOSString();
		String solutionName = targetProject.getSolution().getName();
		String checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

		if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
		{
			foundInContext = targetContext;
			foundProjectPath = projectPath;
			foundSolutionName = solutionName;
		}

		// If not found in current context, search active solution
		if (foundInContext == null && !targetProject.equals(servoyProject))
		{
			projectPath = servoyProject.getProject().getLocation().toOSString();
			solutionName = servoyProject.getSolution().getName();
			checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

			if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
			{
				foundInContext = "active";
				foundProjectPath = projectPath;
				foundSolutionName = solutionName;
			}
		}

		// If not found, search modules
		if (foundInContext == null)
		{
			ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
			for (ServoyProject module : modules)
			{
				if (module != null && !module.equals(targetProject) && !module.equals(servoyProject))
				{
					projectPath = module.getProject().getLocation().toOSString();
					solutionName = module.getSolution().getName();
					checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

					if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
					{
						foundInContext = module.getProject().getName();
						foundProjectPath = projectPath;
						foundSolutionName = solutionName;
						break;
					}
				}
			}
		}

		// Determine operation type
		boolean isUpdate = foundInContext != null;
		boolean needsApproval = isUpdate && !foundInContext.equals(targetContext);

		if (needsApproval)
		{
			String foundLocationDisplay = "active".equals(foundInContext)
				? servoyProject.getProject().getName() + " (active solution)"
				: foundInContext;

			return "Current context: " + contextDisplay + "\n\n" +
				"Style class '" + className + "' found in " + foundLocationDisplay + ".\n" +
				"Current context is " + contextDisplay + ".\n\n" +
				"To update this style, I need to switch to " + foundLocationDisplay + ".\n" +
				"Do you want to proceed?\n\n" +
				"[If yes, I will: setContext({context: \"" + foundInContext + "\"}) then update style]";
		}

		// Add/update in current context
		projectPath = targetProject.getProject().getLocation().toOSString();
		solutionName = targetProject.getSolution().getName();

		String error = StyleService.addOrUpdateStyle(projectPath, solutionName, lessFileName, className, cssContent);

		if (error != null)
		{
			return "Error: " + error;
		}

		String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
			? lessFileName
			: solutionName + ".less";

		if (isUpdate && foundInContext.equals(targetContext))
		{
			return "Successfully updated style class '" + className + "' in " + contextDisplay + " (file: " + targetFile + ")";
		}
		else
		{
			return "Successfully created style class '" + className + "' in " + contextDisplay + " (file: " + targetFile + ")";
		}
	}

	// =============================================
	// IMPLEMENTATION: deleteStyle
	// =============================================

	private String deleteStyleImpl(String className, String lessFileName) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null)
		{
			throw new Exception("No active Servoy solution project found");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		// Remove leading dot if provided
		if (className.startsWith("."))
		{
			className = className.substring(1);
		}

		// Delete from current context only
		String projectPath = targetProject.getProject().getLocation().toOSString();
		String solutionName = targetProject.getSolution().getName();

		String error = StyleService.deleteStyle(projectPath, solutionName, lessFileName, className);

		if (error != null)
		{
			// Check if it exists in other contexts
			String foundInContext = findStyleInOtherContexts(className, lessFileName, servoyModel, targetProject, servoyProject);

			if (foundInContext != null)
			{
				String foundLocationDisplay = "active".equals(foundInContext)
					? servoyProject.getProject().getName() + " (active solution)"
					: foundInContext;

				return "Current context: " + contextDisplay + "\n\n" +
					"Style class '" + className + "' not found in current context.\n" +
					"However, it exists in " + foundLocationDisplay + ".\n\n" +
					"To delete it, use: setContext({context: \"" + foundInContext + "\"}) then deleteStyle again";
			}

			return "Error: " + error;
		}

		String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
			? lessFileName
			: solutionName + ".less";

		return "Successfully deleted style class '" + className + "' from " + contextDisplay + " (file: " + targetFile + ")";
	}

	// =============================================
	// HELPER METHODS
	// =============================================

	private ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel)
	{
		String context = ContextService.getInstance().getCurrentContext();
		ServoyProject activeProject = servoyModel.getActiveProject();

		if ("active".equals(context) || context == null) return activeProject;

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && context.equals(module.getProject().getName())) return module;
		}

		return activeProject;
	}

	private String findStyleInOtherContexts(String className, String lessFileName,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject, ServoyProject servoyProject)
	{
		// Check active solution (if different from target)
		if (!targetProject.equals(servoyProject))
		{
			String projectPath = servoyProject.getProject().getLocation().toOSString();
			String solutionName = servoyProject.getSolution().getName();
			String checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

			if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
			{
				return "active";
			}
		}

		// Check modules
		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && !module.equals(targetProject) && !module.equals(servoyProject))
			{
				String projectPath = module.getProject().getLocation().toOSString();
				String solutionName = module.getSolution().getName();
				String checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

				if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
				{
					return module.getProject().getName();
				}
			}
		}

		return null;
	}
}

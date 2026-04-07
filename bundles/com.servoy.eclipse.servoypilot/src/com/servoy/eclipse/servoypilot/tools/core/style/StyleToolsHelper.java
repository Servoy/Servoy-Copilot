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
package com.servoy.eclipse.servoypilot.tools.core.style;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.StyleService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.tools.core.CoreToolsHelper;

/**
 * Singleton helper providing implementation logic for style tool interfaces.
 */
public class StyleToolsHelper
{
	private static final StyleToolsHelper INSTANCE = new StyleToolsHelper();

	private StyleToolsHelper()
	{
	}

	public static StyleToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String listStylesImpl(String lessFileName, String scope) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if ("current".equals(scope))
			{
				String projectPath = targetProject.getProject().getLocation().toOSString();
				String solutionName = targetProject.getSolution().getName();
				String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty()) ? lessFileName : solutionName + ".less";
				String stylesList = StyleService.listStyles(projectPath, solutionName, lessFileName);

				StringBuilder result = new StringBuilder();
				result.append("Styles in '").append(contextDisplay).append("' (file: ").append(targetFile).append("):\n\n");
				result.append(stylesList);
				return result.toString();
			}
			else
			{
				StringBuilder result = new StringBuilder();
				result.append("Styles in all contexts:\n\n");

				String projectPath = targetProject.getProject().getLocation().toOSString();
				String solutionName = targetProject.getSolution().getName();
				result.append("=== ").append(contextDisplay).append(" ===\n");
				result.append(StyleService.listStyles(projectPath, solutionName, lessFileName)).append("\n\n");

				if (!targetProject.equals(servoyProject))
				{
					projectPath = servoyProject.getProject().getLocation().toOSString();
					solutionName = servoyProject.getSolution().getName();
					result.append("=== ").append(servoyProject.getProject().getName()).append(" (active solution) ===\n");
					result.append(StyleService.listStyles(projectPath, solutionName, lessFileName)).append("\n\n");
				}

				ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
				for (ServoyProject module : modules)
				{
					if (module != null && !module.equals(targetProject) && !module.equals(servoyProject))
					{
						projectPath = module.getProject().getLocation().toOSString();
						solutionName = module.getSolution().getName();
						result.append("=== ").append(module.getProject().getName()).append(" ===\n");
						result.append(StyleService.listStyles(projectPath, solutionName, lessFileName)).append("\n\n");
					}
				}

				return result.toString();
			}
		}

		throw new Exception("No active Servoy solution project found");
	}

	public String addOrUpdateStyleImpl(String className, String cssContent, String lessFileName) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (className.startsWith("."))
			{
				className = className.substring(1);
			}

			String foundInContext = null;

			String projectPath = targetProject.getProject().getLocation().toOSString();
			String solutionName = targetProject.getSolution().getName();
			String checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);

			if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
			{
				foundInContext = target;
			}

			if (foundInContext == null && !targetProject.equals(servoyProject))
			{
				projectPath = servoyProject.getProject().getLocation().toOSString();
				solutionName = servoyProject.getSolution().getName();
				checkResult = StyleService.getStyle(projectPath, solutionName, lessFileName, className);
				if (!checkResult.startsWith("Class '") || !checkResult.contains("not found"))
				{
					foundInContext = "active";
				}
			}

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
							break;
						}
					}
				}
			}

			boolean isUpdate = foundInContext != null;
			boolean needsApproval = isUpdate && !foundInContext.equals(target);

			if (needsApproval)
			{
				String foundLocationDisplay = "active".equals(foundInContext)
					? servoyProject.getProject().getName() + " (active solution)" : foundInContext;
				return "Current context: " + contextDisplay + "\n\n" +
					"Style class '" + className + "' found in " + foundLocationDisplay + ".\n" +
					"To update this style, I need to switch to " + foundLocationDisplay + ".\n" +
					"Do you want to proceed?\n\n" +
					"[If yes, I will: setTarget({target: \"" + foundInContext + "\"}) then update style]";
			}

			projectPath = targetProject.getProject().getLocation().toOSString();
			solutionName = targetProject.getSolution().getName();
			String error = StyleService.addOrUpdateStyle(projectPath, solutionName, lessFileName, className, cssContent);

			if (error != null)
			{
				return "Error: " + error;
			}

			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty()) ? lessFileName : solutionName + ".less";

			if (isUpdate)
			{
				return "Successfully updated style class '" + className + "' in " + contextDisplay + " (file: " + targetFile + ")";
			}
			return "Successfully created style class '" + className + "' in " + contextDisplay + " (file: " + targetFile + ")";
		}

		throw new Exception("No active Servoy solution project found");
	}

	public String deleteStyleImpl(String className, String lessFileName) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (className.startsWith("."))
			{
				className = className.substring(1);
			}

			String projectPath = targetProject.getProject().getLocation().toOSString();
			String solutionName = targetProject.getSolution().getName();
			String error = StyleService.deleteStyle(projectPath, solutionName, lessFileName, className);

			if (error != null)
			{
				String foundInContext = findStyleInOtherContexts(className, lessFileName, servoyModel, targetProject, servoyProject);
				if (foundInContext != null)
				{
					String foundLocationDisplay = "active".equals(foundInContext)
						? servoyProject.getProject().getName() + " (active solution)" : foundInContext;
					return "Current context: " + contextDisplay + "\n\n" +
						"Style class '" + className + "' not found in current target.\n" +
						"However, it exists in " + foundLocationDisplay + ".\n\n" +
						"To delete it, use: setTarget({target: \"" + foundInContext + "\"}) then deleteStyle again";
				}
				return "Error: " + error;
			}

			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty()) ? lessFileName : solutionName + ".less";
			return "Successfully deleted style class '" + className + "' from " + contextDisplay + " (file: " + targetFile + ")";
		}

		throw new Exception("No active Servoy solution project found");
	}

	private String findStyleInOtherContexts(String className, String lessFileName,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject, ServoyProject servoyProject)
	{
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

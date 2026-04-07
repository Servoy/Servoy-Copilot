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
package com.servoy.eclipse.servoypilot.tools.core.valuelist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseRepository;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.services.ValueListService;
import com.servoy.eclipse.servoypilot.tools.core.CoreToolsHelper;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Singleton helper providing implementation logic for value list tool interfaces.
 */
public class ValueListToolsHelper
{
	private static final ValueListToolsHelper INSTANCE = new ValueListToolsHelper();

	private ValueListToolsHelper()
	{
	}

	public static ValueListToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String listValueLists(String scope)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			Solution activeSolution = activeProject.getEditingSolution();
			if (activeSolution != null)
			{
				StringBuilder result = new StringBuilder();
				result.append("ValueLists:\n\n");
				boolean currentOnly = "current".equalsIgnoreCase(scope);
				int totalCount = 0;

				if (currentOnly)
				{
					ServoyProject contextProject = TargetService.getCurrentTargetProject();
					if (contextProject != null)
					{
						Solution contextSolution = contextProject.getEditingSolution();
						if (contextSolution != null)
						{
							String solutionName = contextSolution.getName();
							Iterator<ValueList> valuelists = contextSolution.getValueLists(false);
							int count = 0;
							while (valuelists.hasNext())
							{
								result.append(formatValueListInfo(valuelists.next(), solutionName));
								count++;
								totalCount++;
							}
							if (count == 0)
							{
								result.append("  (No valuelists in ").append(solutionName).append(")\n\n");
							}
						}
					}
					else
					{
						return "Error: No current target set";
					}
				}
				else
				{
					List<Solution> solutions = new ArrayList<>();
					solutions.add(activeSolution);
					ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
					for (ServoyProject module : modules)
					{
						if (module != null && module.getEditingSolution() != null)
						{
							solutions.add(module.getEditingSolution());
						}
					}
					for (Solution solution : solutions)
					{
						String solutionName = solution.getName();
						Iterator<ValueList> valuelists = solution.getValueLists(false);
						int count = 0;
						while (valuelists.hasNext())
						{
							result.append(formatValueListInfo(valuelists.next(), solutionName));
							count++;
							totalCount++;
						}
						if (count == 0)
						{
							result.append("  (No valuelists in ").append(solutionName).append(")\n\n");
						}
					}
				}

				result.insert(0, "Total: " + totalCount + " valuelist(s)\n\n");
				return result.toString();
			}
			return "Error: No active solution";
		}
		return "Error: No active Servoy project";
	}

	public String openOrCreateValueList(String name, List<String> customValues, String dataSource,
		String relationName, String globalMethod, String displayColumn, String returnColumn,
		Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[ValueListToolsHelper] Processing valuelist: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (targetProject != null && targetProject.getEditingSolution() != null)
			{
				boolean isCreateOperation = (customValues != null && !customValues.isEmpty()) ||
					(dataSource != null && !dataSource.trim().isEmpty()) ||
					(relationName != null && !relationName.trim().isEmpty()) ||
					(globalMethod != null && !globalMethod.trim().isEmpty());

				ValueList valueList = null;
				List<ValueList> allMatchingValueLists = new ArrayList<>();
				List<String> valueListLocations = new ArrayList<>();

				if (!isCreateOperation)
				{
					collectMatchingValueLists(name, target, servoyProject, servoyModel, targetProject,
						allMatchingValueLists, valueListLocations);
					if (!allMatchingValueLists.isEmpty())
					{
						valueList = allMatchingValueLists.get(0);
					}
				}
				else
				{
					valueList = targetProject.getEditingSolution().getValueList(name);
					if (valueList != null)
					{
						allMatchingValueLists.add(valueList);
						valueListLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
					}
				}

				boolean isNewValueList = false;
				boolean propertiesModified = false;

				if (!allMatchingValueLists.isEmpty())
				{
					if (properties != null && !properties.isEmpty())
					{
						ValueList valueListInCurrentContext = targetProject.getEditingSolution().getValueList(name);
						if (valueListInCurrentContext == null)
						{
							String foundLocation = findValueListLocation(name, servoyProject, servoyModel, targetProject);
							if (foundLocation != null)
							{
								String locationDisplay = "active".equals(foundLocation)
									? servoyProject.getProject().getName() + " (active solution)" : foundLocation;
								return "Current context: " + contextDisplay + "\n\n" +
									"ValueList '" + name + "' found in " + locationDisplay + ".\n" +
									"To update this valuelist's properties, I need to switch to " + locationDisplay + ".\n" +
									"Do you want to proceed?\n\n" +
									"[If yes, I will: setTarget({target: \"" + foundLocation + "\"}) then update properties]";
							}
						}
						else
						{
							ValueListService.updateValueListProperties(valueList, properties);
							propertiesModified = true;
						}
					}
				}
				else if (isCreateOperation)
				{
					valueList = ValueListService.createValueListInProject(targetProject, name, customValues, dataSource,
						relationName, globalMethod, displayColumn, returnColumn, properties);
					allMatchingValueLists.add(valueList);
					valueListLocations.add(contextDisplay);
					isNewValueList = true;
				}
				else
				{
					throw new RepositoryException("ValueList '" + name + "' not found. To create it, provide one of: " +
						"customValues, dataSource, relationName, or globalMethod.");
				}

				if (isNewValueList)
				{
					final ValueList valueListToOpen = valueList;
					UIThreadHelper.asyncExec("openValueListEditor", () -> EditorUtil.openValueListEditor(valueListToOpen, true));
				}
				else
				{
					final List<ValueList> valueListsToOpen = new ArrayList<>(allMatchingValueLists);
					UIThreadHelper.asyncExec("openValueListEditors", () -> {
						for (ValueList vl : valueListsToOpen)
						{
							EditorUtil.openValueListEditor(vl, true);
						}
					});
				}

				StringBuilder result = new StringBuilder();
				if (isNewValueList)
				{
					result.append("ValueList '").append(name).append("' created successfully in ").append(contextDisplay);
					if (customValues != null && !customValues.isEmpty())
					{
						result.append(" (CUSTOM with ").append(customValues.size()).append(" values)");
					}
					else if (globalMethod != null)
					{
						result.append(" (GLOBAL_METHOD: ").append(globalMethod).append(")");
					}
					else if (relationName != null)
					{
						result.append(" (RELATED: ").append(relationName).append(")");
					}
					else if (dataSource != null)
					{
						result.append(" (DATABASE: ").append(dataSource).append(")");
					}
				}
				else
				{
					if (allMatchingValueLists.size() == 1)
					{
						result.append("ValueList '").append(name).append("' opened successfully");
						result.append(" (from ").append(valueListLocations.get(0)).append(")");
						if (propertiesModified)
						{
							result.append(". Properties updated");
						}
					}
					else
					{
						result.append("ValueList '").append(name).append("' found in ")
							.append(allMatchingValueLists.size()).append(" locations. Opened all:\n");
						for (int i = 0; i < allMatchingValueLists.size(); i++)
						{
							result.append("  - ").append(valueListLocations.get(i)).append("\n");
						}
					}
					result.append("\n[Context remains: ").append(contextDisplay).append("]");
				}

				return result.toString();
			}

			throw new RepositoryException("No target solution/module found for target: " + target);
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	public String deleteValueListsImpl(List<String> names) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			List<String> deletedValueLists = new ArrayList<>();
			List<String> notFoundValueLists = new ArrayList<>();
			List<String> needsApproval = new ArrayList<>();
			Map<String, String> approvalLocations = new HashMap<>();
			List<ValueList> valueListsToDelete = new ArrayList<>();

			for (String name : names)
			{
				if (name == null || name.trim().isEmpty())
				{
					continue;
				}

				ValueList valueList = targetProject.getEditingSolution().getValueList(name);
				String foundInContext = null;

				if (valueList != null)
				{
					foundInContext = target;
					valueListsToDelete.add(valueList);
				}
				else
				{
					if (!targetProject.equals(servoyProject))
					{
						valueList = servoyProject.getEditingSolution().getValueList(name);
						if (valueList != null)
						{
							foundInContext = "active";
						}
					}
					if (valueList == null)
					{
						ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
						for (ServoyProject module : modules)
						{
							if (module != null && module.getEditingSolution() != null && !module.equals(targetProject))
							{
								valueList = module.getEditingSolution().getValueList(name);
								if (valueList != null)
								{
									foundInContext = module.getProject().getName();
									break;
								}
							}
						}
					}
					if (valueList != null)
					{
						needsApproval.add(name);
						approvalLocations.put(name, foundInContext);
					}
					else
					{
						notFoundValueLists.add(name);
					}
				}
			}

			if (!needsApproval.isEmpty())
			{
				StringBuilder approvalMsg = new StringBuilder();
				approvalMsg.append("Current context: ").append(contextDisplay).append("\n\n");
				if (needsApproval.size() == 1)
				{
					String valueListName = needsApproval.get(0);
					String location = approvalLocations.get(valueListName);
					String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;
					approvalMsg.append("ValueList '").append(valueListName).append("' found in ").append(locationDisplay).append(".\n");
					approvalMsg.append("Current context is ").append(contextDisplay).append(".\n\n");
					approvalMsg.append("To delete this valuelist, I need to switch to ").append(locationDisplay).append(".\n");
					approvalMsg.append("Do you want to proceed?\n\n");
					approvalMsg.append("[If yes, I will: setTarget({target: \"").append(location).append("\"}) then delete]");
				}
				else
				{
					approvalMsg.append("Multiple valuelists found in different locations:\n");
					for (String valueListName : needsApproval)
					{
						String location = approvalLocations.get(valueListName);
						String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;
						approvalMsg.append("  - ").append(valueListName).append(" (in ").append(locationDisplay).append(")\n");
					}
					approvalMsg.append("\nPlease switch target explicitly using setTarget({target: \"module_name\"})");
				}
				if (!valueListsToDelete.isEmpty())
				{
					approvalMsg.append("\n\nNote: Can delete from current target without approval: ");
					approvalMsg.append(String.join(", ", valueListsToDelete.stream().map(ValueList::getName).toArray(String[]::new)));
				}
				return approvalMsg.toString();
			}

			if (!valueListsToDelete.isEmpty())
			{
				EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();
				for (ValueList valueList : valueListsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(valueList.getUUID());
					if (editingNode == null)
					{
						editingNode = valueList;
					}
					repository.deleteObject(editingNode);
				}
				for (ValueList valueList : valueListsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(valueList.getUUID());
					if (editingNode == null)
					{
						editingNode = valueList;
					}
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deletedValueLists.add(valueList.getName());
				}
			}

			StringBuilder result = new StringBuilder();
			if (!deletedValueLists.isEmpty())
			{
				result.append("Successfully deleted ").append(deletedValueLists.size()).append(" valuelist(s): ");
				result.append(String.join(", ", deletedValueLists));
			}
			if (!notFoundValueLists.isEmpty())
			{
				if (result.length() > 0)
				{
					result.append("\n\n");
				}
				result.append("ValueLists not found (").append(notFoundValueLists.size()).append("): ");
				result.append(String.join(", ", notFoundValueLists));
			}
			if (deletedValueLists.isEmpty() && notFoundValueLists.isEmpty())
			{
				result.append("No valuelists specified for deletion");
			}

			return result.toString();
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	private void collectMatchingValueLists(String name, String target, ServoyProject servoyProject,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject,
		List<ValueList> allMatchingValueLists, List<String> valueListLocations)
	{
		ValueList valueListInTarget = targetProject.getEditingSolution().getValueList(name);
		if (valueListInTarget != null)
		{
			allMatchingValueLists.add(valueListInTarget);
			valueListLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
		}
		if (!targetProject.equals(servoyProject))
		{
			ValueList valueListInActive = servoyProject.getEditingSolution().getValueList(name);
			if (valueListInActive != null && !allMatchingValueLists.contains(valueListInActive))
			{
				allMatchingValueLists.add(valueListInActive);
				valueListLocations.add(servoyProject.getProject().getName() + " (active solution)");
			}
		}
		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && module.getEditingSolution() != null &&
				!module.equals(targetProject) && !module.equals(servoyProject))
			{
				ValueList valueListInModule = module.getEditingSolution().getValueList(name);
				if (valueListInModule != null && !allMatchingValueLists.contains(valueListInModule))
				{
					allMatchingValueLists.add(valueListInModule);
					valueListLocations.add(module.getProject().getName());
				}
			}
		}
	}

	private String findValueListLocation(String name, ServoyProject servoyProject,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject)
	{
		if (!targetProject.equals(servoyProject) && servoyProject.getEditingSolution().getValueList(name) != null)
		{
			return "active";
		}

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && module.getEditingSolution() != null && !module.equals(targetProject))
			{
				if (module.getEditingSolution().getValueList(name) != null)
				{
					return module.getProject().getName();
				}
			}
		}
		return null;
	}

	private String formatValueListInfo(ValueList vl, String origin)
	{
		StringBuilder info = new StringBuilder();
		info.append("  - ").append(vl.getName()).append(" [").append(origin).append("]");
		String customValues = vl.getCustomValues();
		if (customValues != null && !customValues.trim().isEmpty() && customValues.contains("("))
		{
			info.append(" (Type: GLOBAL_METHOD)");
		}
		else if (vl.getRelationName() != null && !vl.getRelationName().trim().isEmpty())
		{
			info.append(" (Type: DATABASE/RELATED, Relation: ").append(vl.getRelationName()).append(")");
		}
		else if (vl.getDataSource() != null && !vl.getDataSource().trim().isEmpty())
		{
			info.append(" (Type: DATABASE/TABLE, DataSource: ").append(vl.getDataSource()).append(")");
		}
		else if (customValues != null && !customValues.trim().isEmpty())
		{
			info.append(" (Type: CUSTOM)");
		}
		info.append("\n");
		return info.toString();
	}
}

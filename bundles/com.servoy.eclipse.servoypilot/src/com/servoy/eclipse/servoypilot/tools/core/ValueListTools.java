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
package com.servoy.eclipse.servoypilot.tools.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseRepository;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.services.ValueListService;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Servoy ValueList operations.
 */
public class ValueListTools
{
	/**
	 * Opens an existing valuelist or creates a new one.
	 * Supports 4 types: CUSTOM, DATABASE (table), DATABASE (related), GLOBAL_METHOD.
	 */
	@Tool("Opens an existing valuelist or creates a new valuelist. Supports 4 types: CUSTOM, DATABASE (table), DATABASE (related), GLOBAL_METHOD. " +
		"[CONTEXT-AWARE for CREATE] When creating a new valuelist, it will be created in the current target (active solution or module). " +
		"Use getContext to check where it will be created, setContext to change target location.")
	public String openValueList(
		@P(value = "ValueList name", required = true) String name,
		@P(value = "Custom values array (for CUSTOM type)", required = false) List<String> customValues,
		@P(value = "DataSource (format: 'server_name/table_name' or 'db:/server_name/table_name' for DATABASE type)", required = false) String dataSource,
		@P(value = "Relation name (for DATABASE/RELATED type)", required = false) String relationName,
		@P(value = "Global method name (for GLOBAL_METHOD type, e.g. 'scopes.globals.getCountries')", required = false) String globalMethod,
		@P(value = "Display column name", required = false) String displayColumn,
		@P(value = "Return column name", required = false) String returnColumn,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties)
	{
		if (name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("openValueList",
				() -> openOrCreateValueList(name, customValues, dataSource, relationName,
					globalMethod, displayColumn, returnColumn, properties));
		}
		
		return "Error: name parameter is required";
	}

	/**
	 * Deletes one or more existing valuelists.
	 */
	@Tool("Deletes one or more existing valuelists. Requires approval if valuelist not in current target.")
	public String deleteValueLists(
		@P(value = "Array of valuelist names to delete", required = true) List<String> names)
	{
		if (names != null && !names.isEmpty())
		{
			return UIThreadHelper.syncExec("deleteValueLists",
				() -> deleteValueListsImpl(names));
		}
		
		return "Error: names parameter is required (array of valuelist names)";
	}

	/**
	 * Lists valuelists in the active solution and its modules.
	 * 
	 * @param scope Optional scope: 'current' (context only) or 'all' (solution + modules). Default: 'all'
	 * @return Formatted string with valuelist information
	 */
	@Tool("Lists valuelists in the active solution and its modules. " +
		"Optional scope parameter: 'current' returns valuelists from current target only, " +
		"'all' returns valuelists from active solution and all modules (default).")
	public String getValueLists(
		@P(value = "Scope: 'current' for target only, 'all' for solution + modules (default 'all')", required = false) String scope)
	{
		return UIThreadHelper.syncExec("getValueLists",
			() -> listValueLists(scope));
	}

	/**
	 * Implementation of valuelist listing logic.
	 */
	private String listValueLists(String scope)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			Solution activeSolution = activeProject.getEditingSolution();
			if (activeSolution != null) {
				StringBuilder result = new StringBuilder();
				result.append("ValueLists:\n\n");

				// Determine scope
				boolean currentOnly = "current".equalsIgnoreCase(scope);
				int totalCount = 0;

				if (currentOnly)
				{
					// Current context only
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
								ValueList vl = valuelists.next();
								result.append(formatValueListInfo(vl, solutionName));
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
					// All scope: active solution + modules
					List<Solution> solutions = new java.util.ArrayList<>();
					solutions.add(activeSolution);

					// Add modules
					if (activeSolution.getModulesNames() != null)
					{
						String[] moduleNames = activeSolution.getModulesNames().split(",");
						ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
						for (ServoyProject module : modules)
						{
							if (module != null && module.getEditingSolution() != null)
							{
								solutions.add(module.getEditingSolution());
							}
						}
					}

					// List valuelists from each solution
					for (Solution solution : solutions)
					{
						String solutionName = solution.getName();
						Iterator<ValueList> valuelists = solution.getValueLists(false);
						int count = 0;

						while (valuelists.hasNext())
						{
							ValueList vl = valuelists.next();
							result.append(formatValueListInfo(vl, solutionName));
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

	/**
	 * Format valuelist information for display.
	 */
	private String formatValueListInfo(ValueList vl, String origin)
	{
		StringBuilder info = new StringBuilder();
		info.append("  - ").append(vl.getName());
		info.append(" [").append(origin).append("]");

		// Add type information
		int addEmptyValue = vl.getAddEmptyValue();
		String globalMethod = vl.getCustomValues();

		if (globalMethod != null && !globalMethod.trim().isEmpty() && globalMethod.contains("("))
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
		else if (globalMethod != null && !globalMethod.trim().isEmpty())
		{
			info.append(" (Type: CUSTOM)");
		}

		info.append("\n");
		return info.toString();
	}

	// =============================================
	// IMPLEMENTATION: openValueList
	// =============================================

	private String openOrCreateValueList(String name, List<String> customValues, String dataSource,
		String relationName, String globalMethod, String displayColumn, String returnColumn,
		Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[ValueListTools] Processing valuelist: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			// Resolve target project based on current target
			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (targetProject != null && targetProject.getEditingSolution() != null)
			{

		// Determine if this is a READ or CREATE operation
		boolean hasCustom = (customValues != null && !customValues.isEmpty());
		boolean hasDatabase = (dataSource != null && !dataSource.trim().isEmpty());
		boolean hasRelated = (relationName != null && !relationName.trim().isEmpty());
		boolean hasGlobalMethod = (globalMethod != null && !globalMethod.trim().isEmpty());
		boolean isCreateOperation = hasCustom || hasDatabase || hasRelated || hasGlobalMethod;

		ValueList valueList = null;
		List<ValueList> allMatchingValueLists = new ArrayList<>();
		List<String> valueListLocations = new ArrayList<>();

		if (!isCreateOperation)
		{
			// READ operation: Search ALL contexts
			ValueList valueListInTarget = targetProject.getEditingSolution().getValueList(name);
			if (valueListInTarget != null)
			{
				allMatchingValueLists.add(valueListInTarget);
				valueListLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
				valueList = valueListInTarget;
			}

			// Search in active solution (if different from target)
			if (!targetProject.equals(servoyProject))
			{
				ValueList valueListInActive = servoyProject.getEditingSolution().getValueList(name);
				if (valueListInActive != null && !allMatchingValueLists.contains(valueListInActive))
				{
					allMatchingValueLists.add(valueListInActive);
					valueListLocations.add(servoyProject.getProject().getName() + " (active solution)");
					if (valueList == null) valueList = valueListInActive;
				}
			}

			// Search in all modules
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
						if (valueList == null) valueList = valueListInModule;
					}
				}
			}
		}
		else
		{
			// CREATE operation: Search current target only
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
			ServoyLog.logInfo("[ValueListTools] Found " + allMatchingValueLists.size() + " matching valuelist(s): " + name);

			// If properties are provided (UPDATE operation), check if valuelist is in current target
			if (properties != null && !properties.isEmpty())
			{
				ValueList valueListInCurrentContext = targetProject.getEditingSolution().getValueList(name);

				if (valueListInCurrentContext == null)
				{
					// UPDATE operation but valuelist not in current target - need approval
					String foundLocation = findValueListLocation(name, servoyProject, servoyModel, targetProject);

					if (foundLocation != null)
					{
						String locationDisplay = "active".equals(foundLocation) ? servoyProject.getProject().getName() + " (active solution)" : foundLocation;
						return "Current context: " + contextDisplay + "\n\n" +
							"ValueList '" + name + "' found in " + locationDisplay + ".\n" +
							"Current context is " + contextDisplay + ".\n\n" +
							"To update this valuelist's properties, I need to switch to " + locationDisplay + ".\n" +
							"Do you want to proceed?\n\n" +
							"[If yes, I will: setTarget({target: \"" + foundLocation + "\"}) then update properties]";
					}
				}
				else
				{
					// ValueList in current target - can update
					ServoyLog.logInfo("[ValueListTools] Updating valuelist properties");
					ValueListService.updateValueListProperties(valueList, properties);
					propertiesModified = true;
				}
			}
		}
		else if (isCreateOperation)
		{
			// ValueList doesn't exist - create it in current target
			ServoyLog.logInfo("[ValueListTools] ValueList doesn't exist, creating in " + target + ": " + name);

			if (!hasCustom && !hasDatabase && !hasRelated && !hasGlobalMethod)
			{
				throw new RepositoryException("ValueList '" + name + "' not found. To create it, provide one of: " +
					"customValues (array), dataSource (string), relationName (string), or globalMethod (string).");
			}

			valueList = ValueListService.createValueListInProject(targetProject, name, customValues, dataSource,
				relationName, globalMethod, displayColumn, returnColumn, properties);
			allMatchingValueLists.add(valueList);
			valueListLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
			isNewValueList = true;
		}
		else
		{
			// READ operation but valuelist not found anywhere
			throw new RepositoryException("ValueList '" + name + "' not found. To create it, provide one of: " +
				"customValues (array), dataSource (string), relationName (string), or globalMethod (string).");
		}

		// Open valuelist(s) in editor
		if (isNewValueList)
		{
			final ValueList valueListToOpen = valueList;
			UIThreadHelper.asyncExec("openValueListEditor", 
				() -> EditorUtil.openValueListEditor(valueListToOpen, true));
		}
		else if (!allMatchingValueLists.isEmpty())
		{
			final List<ValueList> valueListsToOpen = new ArrayList<>(allMatchingValueLists);
			UIThreadHelper.asyncExec("openValueListEditors", () -> {
				for (ValueList vlToOpen : valueListsToOpen)
				{
					EditorUtil.openValueListEditor(vlToOpen, true);
				}
			});
		}

		// Build result message
		StringBuilder result = new StringBuilder();

		if (isNewValueList)
		{
			result.append("ValueList '").append(name).append("' created successfully in ").append(contextDisplay);
			if (customValues != null && !customValues.isEmpty()) result.append(" (CUSTOM with ").append(customValues.size()).append(" values)");
			else if (globalMethod != null) result.append(" (GLOBAL_METHOD: ").append(globalMethod).append(")");
			else if (relationName != null) result.append(" (RELATED: ").append(relationName).append(")");
			else if (dataSource != null) result.append(" (DATABASE: ").append(dataSource).append(")");
		}
		else
		{
			if (allMatchingValueLists.size() == 1)
			{
				result.append("ValueList '").append(name).append("' opened successfully");
				result.append(" (from ").append(valueListLocations.get(0)).append(")");
				if (propertiesModified) result.append(". Properties updated");
			}
			else
			{
				result.append("ValueList '").append(name).append("' found in ").append(allMatchingValueLists.size()).append(" locations. Opened all:\n");
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

	// =============================================
	// IMPLEMENTATION: deleteValueLists
	// =============================================

	private String deleteValueListsImpl(List<String> names) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			// Get current target
			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			List<String> deletedValueLists = new ArrayList<>();
			List<String> notFoundValueLists = new ArrayList<>();
			List<String> needsApproval = new ArrayList<>();
			Map<String, String> approvalLocations = new HashMap<>();
			List<ValueList> valueListsToDelete = new ArrayList<>();

			// Find valuelists and check if they're in current target
			for (String name : names)
			{
				if (name == null || name.trim().isEmpty()) continue;

				ValueList valueList = targetProject.getEditingSolution().getValueList(name);
				String foundInContext = null;

				if (valueList != null)
				{
					foundInContext = target;
					valueListsToDelete.add(valueList);
				}
				else
				{
					// Search other locations
					if (!targetProject.equals(servoyProject))
					{
						valueList = servoyProject.getEditingSolution().getValueList(name);
						if (valueList != null) foundInContext = "active";
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

			// If any items need approval, return approval request message
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
					approvalMsg.append("\nCurrent context is ").append(contextDisplay).append(".\n");
					approvalMsg.append("Please switch target explicitly using setTarget({target: \"module_name\"})");
				}

				if (!valueListsToDelete.isEmpty())
				{
					approvalMsg.append("\n\nNote: Can delete from current target without approval: ");
					approvalMsg.append(String.join(", ", valueListsToDelete.stream().map(vl -> vl.getName()).toArray(String[]::new)));
				}

				return approvalMsg.toString();
			}

			// Delete valuelists (all are in current target)
			if (!valueListsToDelete.isEmpty())
			{
				EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();

				try
				{
					for (ValueList valueList : valueListsToDelete)
					{
						IPersist editingNode = servoyProject.getEditingPersist(valueList.getUUID());
						if (editingNode == null) editingNode = valueList;
						repository.deleteObject(editingNode);
						ServoyLog.logInfo("[ValueListTools] Called deleteObject for valuelist: " + valueList.getName());
					}

					for (ValueList valueList : valueListsToDelete)
					{
						IPersist editingNode = servoyProject.getEditingPersist(valueList.getUUID());
						if (editingNode == null) editingNode = valueList;
						servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
						deletedValueLists.add(valueList.getName());
						ServoyLog.logInfo("[ValueListTools] Successfully deleted valuelist: " + valueList.getName());
					}
				}
				catch (Exception e)
				{
					ServoyLog.logError("[ValueListTools] FAILED to delete valuelists", e);
					throw new RepositoryException("Failed to delete valuelists. Error: " + e.getMessage(), e);
				}
			}

			// Build result message
			StringBuilder result = new StringBuilder();

			if (!deletedValueLists.isEmpty())
			{
				result.append("Successfully deleted ").append(deletedValueLists.size()).append(" valuelist(s): ");
				result.append(String.join(", ", deletedValueLists));
			}

			if (!notFoundValueLists.isEmpty())
			{
				if (result.length() > 0) result.append("\n\n");
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

	// =============================================
	// HELPER METHODS
	// =============================================

	/**
	 * Resolve target project based on current target.
	 */
	private ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel)
	{
		String target = TargetService.getInstance().getCurrentTarget();
		ServoyProject activeProject = servoyModel.getActiveProject();

		if ("active".equals(target) || target == null)
		{
			return activeProject;
		}

		// Find module by name
		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && target.equals(module.getProject().getName()))
			{
				return module;
			}
		}

		return activeProject; // Fallback
	}

	/**
	 * Find which solution/module contains a valuelist.
	 */
	private String findValueListLocation(String name, ServoyProject servoyProject, IDeveloperServoyModel servoyModel, ServoyProject targetProject)
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
}

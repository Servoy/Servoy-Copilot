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
import com.servoy.eclipse.servoypilot.services.ContextService;
import com.servoy.eclipse.servoypilot.services.RelationService;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Servoy Relation operations.
 * Migrated from knowledgebase.mcp RelationToolHandler.
 * 
 * Complete migration: All 3 main tools implemented.
 */
public class RelationTools
{
	/**
	 * Lists relations in the active solution and its modules.
	 */
	@Tool("Lists relations in the active solution and its modules. Optional scope parameter: 'current' for context only, 'all' for solution + modules (default).")
	public String getRelations(
		@P(value = "Scope: 'current' for context only, 'all' for solution + modules (default 'all')", required = false) String scope)
	{
		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = listRelationsImpl(scope != null ? scope : "all");
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error listing relations", exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Opens an existing relation or creates a new relation.
	 */
	@Tool("Opens an existing relation or creates a new relation between two tables. Context-aware: when creating, relation created in current context.")
	public String openRelation(
		@P(value = "Relation name", required = true) String name,
		@P(value = "Primary datasource (format: 'server_name/table_name' or 'db:/server_name/table_name')", required = false) String primaryDataSource,
		@P(value = "Foreign datasource (format: 'server_name/table_name' or 'db:/server_name/table_name')", required = false) String foreignDataSource,
		@P(value = "Primary column name", required = false) String primaryColumn,
		@P(value = "Foreign column name", required = false) String foreignColumn,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties)
	{
		if (name == null || name.trim().isEmpty()) return "Error: name parameter is required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = openOrCreateRelation(name, primaryDataSource, foreignDataSource,
					primaryColumn, foreignColumn, properties);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error opening/creating relation: " + name, exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Deletes one or more existing relations.
	 */
	@Tool("Deletes one or more existing relations. Requires approval if relation not in current context.")
	public String deleteRelations(
		@P(value = "Array of relation names to delete", required = true) List<String> names)
	{
		if (names == null || names.isEmpty()) return "Error: names parameter is required (array of relation names)";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = deleteRelationsImpl(names);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error deleting relations", exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	// =============================================
	// IMPLEMENTATION: listRelations
	// =============================================

	private String listRelationsImpl(String scope) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No active Servoy solution project found");
		}

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		String contextName = null;

		List<Relation> relations = new ArrayList<>();

		if ("current".equals(scope))
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String context = ContextService.getInstance().getCurrentContext();
			contextName = "active".equals(context) ? activeSolutionName : context;

			Iterator<Relation> relationsIterator = targetProject.getEditingSolution().getRelations(false);
			while (relationsIterator.hasNext())
			{
				relations.add(relationsIterator.next());
			}
		}
		else
		{
			Iterator<Relation> activeRelations = servoyProject.getEditingSolution().getRelations(false);
			while (activeRelations.hasNext())
			{
				relations.add(activeRelations.next());
			}

			ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
			for (ServoyProject module : modules)
			{
				if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
				{
					Iterator<Relation> moduleRelations = module.getEditingSolution().getRelations(false);
					while (moduleRelations.hasNext())
					{
						relations.add(moduleRelations.next());
					}
				}
			}
		}

		if (relations.isEmpty())
		{
			return "No relations found" + ("current".equals(scope) ? " in '" + contextName + "'" : " in the active solution");
		}

		StringBuilder result = new StringBuilder();

		if ("current".equals(scope))
		{
			result.append("Relations in '").append(contextName).append("' (").append(relations.size()).append("):\n\n");
		}
		else
		{
			result.append("Relations in solution '").append(activeSolutionName).append("' and modules (").append(relations.size()).append("):\n\n");
		}

		int count = 1;
		for (Relation relation : relations)
		{
			String solutionName = getSolutionName(relation);
			String originInfo = formatOrigin(solutionName, activeSolutionName);

			result.append(count).append(". ").append(relation.getName()).append(originInfo);
			result.append("\n   Primary: ").append(relation.getPrimaryDataSource());
			result.append("\n   Foreign: ").append(relation.getForeignDataSource());

			String joinType = relation.getJoinType() == com.servoy.base.query.IQueryConstants.INNER_JOIN ? "INNER" : "LEFT OUTER";
			result.append(" (").append(joinType).append(" JOIN)");

			result.append("\n");
			count++;
		}

		return result.toString();
	}

	// =============================================
	// IMPLEMENTATION: openRelation
	// =============================================

	private String openOrCreateRelation(String name, String primaryDataSource, String foreignDataSource,
		String primaryColumn, String foreignColumn, Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[RelationTools] Processing relation: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No active Servoy solution project found");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		if (targetProject == null || targetProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No target solution/module found for context: " + targetContext);
		}

		// Determine if this is a READ or CREATE operation
		boolean hasDataSources = (primaryDataSource != null && !primaryDataSource.trim().isEmpty()) &&
			(foreignDataSource != null && !foreignDataSource.trim().isEmpty());
		boolean isCreateOperation = hasDataSources;

		// Search for existing relations
		List<Relation> allMatchingRelations = new ArrayList<>();
		List<String> relationLocations = new ArrayList<>();

		Relation relationInTarget = targetProject.getEditingSolution().getRelation(name);
		if (relationInTarget != null)
		{
			allMatchingRelations.add(relationInTarget);
			relationLocations.add("active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext);
		}

		if (!targetProject.equals(servoyProject))
		{
			Relation relationInActive = servoyProject.getEditingSolution().getRelation(name);
			if (relationInActive != null && !allMatchingRelations.contains(relationInActive))
			{
				allMatchingRelations.add(relationInActive);
				relationLocations.add(servoyProject.getProject().getName() + " (active solution)");
			}
		}

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && module.getEditingSolution() != null &&
				!module.equals(targetProject) && !module.equals(servoyProject))
			{
				Relation relationInModule = module.getEditingSolution().getRelation(name);
				if (relationInModule != null && !allMatchingRelations.contains(relationInModule))
				{
					allMatchingRelations.add(relationInModule);
					relationLocations.add(module.getProject().getName());
				}
			}
		}

		boolean isNewRelation = false;
		Relation relation = null;

		if (!allMatchingRelations.isEmpty())
		{
			relation = allMatchingRelations.get(0);

			// Apply properties if provided
			if (properties != null && !properties.isEmpty())
			{
				Relation relationInCurrentContext = targetProject.getEditingSolution().getRelation(name);

				if (relationInCurrentContext == null)
				{
					// UPDATE operation but relation not in current context - need approval
					String foundLocation = findRelationLocation(name, servoyProject, servoyModel, targetProject);

					if (foundLocation != null)
					{
						String locationDisplay = "active".equals(foundLocation) ? servoyProject.getProject().getName() + " (active solution)" : foundLocation;
						return "Current context: " + contextDisplay + "\n\n" +
							"Relation '" + name + "' found in " + locationDisplay + ".\n" +
							"Current context is " + contextDisplay + ".\n\n" +
							"To update this relation's properties, I need to switch to " + locationDisplay + ".\n" +
							"Do you want to proceed?\n\n" +
							"[If yes, I will: setContext({context: \"" + foundLocation + "\"}) then update properties]";
					}
				}
				else
				{
					// Relation in current context - can update
					RelationService.updateRelationProperties(relation, properties);
				}
			}
		}
		else if (isCreateOperation)
		{
			// Create new relation in current context
			relation = RelationService.createRelationInProject(targetProject, name, primaryDataSource, foreignDataSource,
				primaryColumn, foreignColumn, properties);
			allMatchingRelations.add(relation);
			relationLocations.add(contextDisplay);
			isNewRelation = true;
		}
		else
		{
			throw new RepositoryException("Relation '" + name + "' not found. To create it, provide primaryDataSource and foreignDataSource.");
		}

		// Open relation in editor
		if (isNewRelation)
		{
			final Relation relationToOpen = relation;
			Display.getDefault().asyncExec(() -> EditorUtil.openRelationEditor(relationToOpen));
		}
		else if (!allMatchingRelations.isEmpty())
		{
			final List<Relation> relationsToOpen = new ArrayList<>(allMatchingRelations);
			Display.getDefault().asyncExec(() -> {
				for (Relation r : relationsToOpen) EditorUtil.openRelationEditor(r);
			});
		}

		// Build result message
		StringBuilder result = new StringBuilder();

		if (isNewRelation)
		{
			result.append("Relation '").append(name).append("' created successfully in ").append(contextDisplay);
			result.append("\n  Primary: ").append(primaryDataSource);
			result.append("\n  Foreign: ").append(foreignDataSource);
			if (primaryColumn != null && foreignColumn != null)
			{
				result.append("\n  Mapping: ").append(primaryColumn).append(" = ").append(foreignColumn);
			}
		}
		else
		{
			if (allMatchingRelations.size() == 1)
			{
				result.append("Relation '").append(name).append("' opened successfully");
				result.append(" (from ").append(relationLocations.get(0)).append(")");
			}
			else
			{
				result.append("Relation '").append(name).append("' found in ").append(allMatchingRelations.size()).append(" locations. Opened all:\n");
				for (int i = 0; i < allMatchingRelations.size(); i++)
				{
					result.append("  - ").append(relationLocations.get(i)).append("\n");
				}
			}
			result.append("\n[Context remains: ").append(contextDisplay).append("]");
		}

		return result.toString();
	}

	// =============================================
	// IMPLEMENTATION: deleteRelations
	// =============================================

	private String deleteRelationsImpl(List<String> names) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No active Servoy solution project found");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		List<String> deletedRelations = new ArrayList<>();
		List<String> notFoundRelations = new ArrayList<>();
		List<String> needsApproval = new ArrayList<>();
		Map<String, String> approvalLocations = new HashMap<>();
		List<Relation> relationsToDelete = new ArrayList<>();

		// Find relations and check if they're in current context
		for (String name : names)
		{
			if (name == null || name.trim().isEmpty()) continue;

			Relation relation = targetProject.getEditingSolution().getRelation(name);
			String foundInContext = null;

			if (relation != null)
			{
				foundInContext = targetContext;
				relationsToDelete.add(relation);
			}
			else
			{
				// Search other locations
				if (!targetProject.equals(servoyProject))
				{
					relation = servoyProject.getEditingSolution().getRelation(name);
					if (relation != null) foundInContext = "active";
				}

				if (relation == null)
				{
					ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
					for (ServoyProject module : modules)
					{
						if (module != null && module.getEditingSolution() != null && !module.equals(targetProject))
						{
							relation = module.getEditingSolution().getRelation(name);
							if (relation != null)
							{
								foundInContext = module.getProject().getName();
								break;
							}
						}
					}
				}

				if (relation != null)
				{
					needsApproval.add(name);
					approvalLocations.put(name, foundInContext);
				}
				else
				{
					notFoundRelations.add(name);
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
				String relationName = needsApproval.get(0);
				String location = approvalLocations.get(relationName);
				String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;

				approvalMsg.append("Relation '").append(relationName).append("' found in ").append(locationDisplay).append(".\n");
				approvalMsg.append("Current context is ").append(contextDisplay).append(".\n\n");
				approvalMsg.append("To delete this relation, I need to switch to ").append(locationDisplay).append(".\n");
				approvalMsg.append("Do you want to proceed?\n\n");
				approvalMsg.append("[If yes, I will: setContext({context: \"").append(location).append("\"}) then delete]");
			}
			else
			{
				approvalMsg.append("Multiple relations found in different locations:\n");
				for (String relationName : needsApproval)
				{
					String location = approvalLocations.get(relationName);
					String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;
					approvalMsg.append("  - ").append(relationName).append(" (in ").append(locationDisplay).append(")\n");
				}
				approvalMsg.append("\nCurrent context is ").append(contextDisplay).append(".\n");
				approvalMsg.append("Please switch context explicitly using setContext");
			}

			if (!relationsToDelete.isEmpty())
			{
				approvalMsg.append("\n\nNote: Can delete from current context without approval: ");
				approvalMsg.append(String.join(", ", relationsToDelete.stream().map(r -> r.getName()).toArray(String[]::new)));
			}

			return approvalMsg.toString();
		}

		// Delete relations (all are in current context)
		if (!relationsToDelete.isEmpty())
		{
			EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();

			try
			{
				for (Relation relation : relationsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(relation.getUUID());
					if (editingNode == null) editingNode = relation;
					repository.deleteObject(editingNode);
					ServoyLog.logInfo("[RelationTools] Called deleteObject for relation: " + relation.getName());
				}

				for (Relation relation : relationsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(relation.getUUID());
					if (editingNode == null) editingNode = relation;
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deletedRelations.add(relation.getName());
					ServoyLog.logInfo("[RelationTools] Successfully deleted relation: " + relation.getName());
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("[RelationTools] FAILED to delete relations", e);
				throw new RepositoryException("Failed to delete relations. Error: " + e.getMessage(), e);
			}
		}

		// Build result message
		StringBuilder result = new StringBuilder();

		if (!deletedRelations.isEmpty())
		{
			result.append("Successfully deleted ").append(deletedRelations.size()).append(" relation(s): ");
			result.append(String.join(", ", deletedRelations));
		}

		if (!notFoundRelations.isEmpty())
		{
			if (result.length() > 0) result.append("\n\n");
			result.append("Relations not found (").append(notFoundRelations.size()).append("): ");
			result.append(String.join(", ", notFoundRelations));
		}

		if (deletedRelations.isEmpty() && notFoundRelations.isEmpty())
		{
			result.append("No relations specified for deletion");
		}

		return result.toString();
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

	private String findRelationLocation(String name, ServoyProject servoyProject, IDeveloperServoyModel servoyModel, ServoyProject targetProject)
	{
		if (!targetProject.equals(servoyProject) && servoyProject.getEditingSolution().getRelation(name) != null)
		{
			return "active";
		}

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && module.getEditingSolution() != null && !module.equals(targetProject))
			{
				if (module.getEditingSolution().getRelation(name) != null)
				{
					return module.getProject().getName();
				}
			}
		}

		return null;
	}

	private String getSolutionName(IPersist persist)
	{
		try
		{
			IRootObject rootObject = persist.getRootObject();
			if (rootObject instanceof Solution solution) return solution.getName();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[RelationTools] Error getting solution name", e);
		}
		return "unknown";
	}

	private String formatOrigin(String solutionName, String activeSolutionName)
	{
		if (solutionName.equals(activeSolutionName)) return " (in: active solution)";
		else return " (in: " + solutionName + ")";
	}
}

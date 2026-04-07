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
package com.servoy.eclipse.servoypilot.tools.core.relation;

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
import com.servoy.eclipse.servoypilot.services.RelationService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.tools.core.CoreToolsHelper;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;

/**
 * Singleton helper providing implementation logic for relation tool interfaces.
 */
public class RelationToolsHelper
{
	private static final RelationToolsHelper INSTANCE = new RelationToolsHelper();

	private RelationToolsHelper()
	{
	}

	public static RelationToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String listRelationsImpl(String scope) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			String activeSolutionName = servoyProject.getEditingSolution().getName();
			String contextName = null;
			List<Relation> relations = new ArrayList<>();

			if ("current".equals(scope))
			{
				ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
				String target = TargetService.getInstance().getCurrentTarget();
				contextName = "active".equals(target) ? activeSolutionName : target;

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

			CoreToolsHelper helper = CoreToolsHelper.getInstance();
			int count = 1;
			for (Relation relation : relations)
			{
				String solutionName = helper.getSolutionName(relation);
				String originInfo = helper.formatOrigin(solutionName, activeSolutionName);

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

		throw new RepositoryException("No active Servoy solution project found");
	}

	public String openOrCreateRelation(String name, String primaryDataSource, String foreignDataSource,
		String primaryColumn, String foreignColumn, Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[RelationToolsHelper] Processing relation: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (targetProject != null && targetProject.getEditingSolution() != null)
			{
				boolean isCreateOperation = (primaryDataSource != null && !primaryDataSource.trim().isEmpty()) &&
					(foreignDataSource != null && !foreignDataSource.trim().isEmpty());

				List<Relation> allMatchingRelations = new ArrayList<>();
				List<String> relationLocations = new ArrayList<>();

				collectMatchingRelations(name, target, servoyProject, servoyModel, targetProject, allMatchingRelations, relationLocations);

				boolean isNewRelation = false;
				Relation relation = null;

				if (!allMatchingRelations.isEmpty())
				{
					relation = allMatchingRelations.get(0);
					if (properties != null && !properties.isEmpty())
					{
						Relation relationInCurrentContext = targetProject.getEditingSolution().getRelation(name);
						if (relationInCurrentContext == null)
						{
							String foundLocation = findRelationLocation(name, servoyProject, servoyModel, targetProject);
							if (foundLocation != null)
							{
								String locationDisplay = "active".equals(foundLocation)
									? servoyProject.getProject().getName() + " (active solution)" : foundLocation;
								return "Current context: " + contextDisplay + "\n\n" +
									"Relation '" + name + "' found in " + locationDisplay + ".\n" +
									"To update this relation's properties, I need to switch to " + locationDisplay + ".\n" +
									"Do you want to proceed?\n\n" +
									"[If yes, I will: setTarget({target: \"" + foundLocation + "\"}) then update properties]";
							}
						}
						else
						{
							RelationService.updateRelationProperties(relation, properties);
						}
					}
				}
				else if (isCreateOperation)
				{
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

				if (isNewRelation)
				{
					final Relation relationToOpen = relation;
					UIThreadHelper.asyncExec("openRelationEditor", () -> EditorUtil.openRelationEditor(relationToOpen));
				}
				else
				{
					final List<Relation> relationsToOpen = new ArrayList<>(allMatchingRelations);
					UIThreadHelper.asyncExec("openRelationEditors", () -> {
						for (Relation r : relationsToOpen)
						{
							EditorUtil.openRelationEditor(r);
						}
					});
				}

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
						result.append("Relation '").append(name).append("' found in ").append(allMatchingRelations.size())
							.append(" locations. Opened all:\n");
						for (int i = 0; i < allMatchingRelations.size(); i++)
						{
							result.append("  - ").append(relationLocations.get(i)).append("\n");
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

	public String deleteRelationsImpl(List<String> names) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = CoreToolsHelper.getInstance().resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			List<String> deletedRelations = new ArrayList<>();
			List<String> notFoundRelations = new ArrayList<>();
			List<String> needsApproval = new ArrayList<>();
			Map<String, String> approvalLocations = new HashMap<>();
			List<Relation> relationsToDelete = new ArrayList<>();

			for (String name : names)
			{
				if (name == null || name.trim().isEmpty())
				{
					continue;
				}

				Relation relation = targetProject.getEditingSolution().getRelation(name);
				String foundInContext = null;

				if (relation != null)
				{
					foundInContext = target;
					relationsToDelete.add(relation);
				}
				else
				{
					if (!targetProject.equals(servoyProject))
					{
						relation = servoyProject.getEditingSolution().getRelation(name);
						if (relation != null)
						{
							foundInContext = "active";
						}
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
					approvalMsg.append("[If yes, I will: setTarget({target: \"").append(location).append("\"}) then delete]");
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
					approvalMsg.append("\nPlease switch target explicitly using setContext");
				}
				if (!relationsToDelete.isEmpty())
				{
					approvalMsg.append("\n\nNote: Can delete from current target without approval: ");
					approvalMsg.append(String.join(", ", relationsToDelete.stream().map(Relation::getName).toArray(String[]::new)));
				}
				return approvalMsg.toString();
			}

			if (!relationsToDelete.isEmpty())
			{
				EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();
				for (Relation relation : relationsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(relation.getUUID());
					if (editingNode == null)
					{
						editingNode = relation;
					}
					repository.deleteObject(editingNode);
				}
				for (Relation relation : relationsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(relation.getUUID());
					if (editingNode == null)
					{
						editingNode = relation;
					}
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deletedRelations.add(relation.getName());
				}
			}

			StringBuilder result = new StringBuilder();
			if (!deletedRelations.isEmpty())
			{
				result.append("Successfully deleted ").append(deletedRelations.size()).append(" relation(s): ");
				result.append(String.join(", ", deletedRelations));
			}
			if (!notFoundRelations.isEmpty())
			{
				if (result.length() > 0)
				{
					result.append("\n\n");
				}
				result.append("Relations not found (").append(notFoundRelations.size()).append("): ");
				result.append(String.join(", ", notFoundRelations));
			}
			if (deletedRelations.isEmpty() && notFoundRelations.isEmpty())
			{
				result.append("No relations specified for deletion");
			}

			return result.toString();
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	private void collectMatchingRelations(String name, String target, ServoyProject servoyProject,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject,
		List<Relation> allMatchingRelations, List<String> relationLocations)
	{
		Relation relationInTarget = targetProject.getEditingSolution().getRelation(name);
		if (relationInTarget != null)
		{
			allMatchingRelations.add(relationInTarget);
			relationLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
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
	}

	private String findRelationLocation(String name, ServoyProject servoyProject,
		IDeveloperServoyModel servoyModel, ServoyProject targetProject)
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
}

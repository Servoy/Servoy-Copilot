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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import com.servoy.eclipse.servoypilot.services.FormService;
import com.servoy.eclipse.servoypilot.services.RelationService;
import com.servoy.eclipse.servoypilot.services.StyleService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.services.ValueListService;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.ClientVersion;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Singleton helper providing shared logic for all core tool interfaces
 * (FormTools, RelationTools, ValueListTools, StyleTools).
 */
public class CoreToolsHelper
{
	private static final CoreToolsHelper INSTANCE = new CoreToolsHelper();

	private CoreToolsHelper()
	{
	}

	public static CoreToolsHelper getInstance()
	{
		return INSTANCE;
	}

	// -------------------------------------------------------------------------
	// Shared utilities
	// -------------------------------------------------------------------------

	public ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel)
	{
		String target = TargetService.getInstance().getCurrentTarget();
		ServoyProject activeProject = servoyModel.getActiveProject();

		if ("active".equals(target) || target == null)
		{
			return activeProject;
		}

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && target.equals(module.getProject().getName()))
			{
				return module;
			}
		}

		return activeProject;
	}

	public String getSolutionName(IPersist persist)
	{
		try
		{
			IRootObject rootObject = persist.getRootObject();
			if (rootObject instanceof Solution solution)
			{
				return solution.getName();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("[CoreToolsHelper] Error getting solution name", e);
		}
		return "unknown";
	}

	public String formatOrigin(String solutionName, String activeSolutionName)
	{
		if (solutionName.equals(activeSolutionName))
		{
			return " (in: active solution)";
		}
		return " (in: " + solutionName + ")";
	}

	// -------------------------------------------------------------------------
	// Form operations
	// -------------------------------------------------------------------------

	public String listFormsImpl(String scope) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			String activeSolutionName = servoyProject.getEditingSolution().getName();
			String contextName = null;
			List<Form> forms = new ArrayList<>();

			if ("current".equals(scope))
			{
				ServoyProject targetProject = resolveTargetProject(servoyModel);
				String target = TargetService.getInstance().getCurrentTarget();
				contextName = "active".equals(target) ? activeSolutionName : target;

				Iterator<Form> formsIterator = targetProject.getEditingSolution().getForms(null, false);
				while (formsIterator.hasNext())
				{
					forms.add(formsIterator.next());
				}
			}
			else
			{
				Iterator<Form> activeForms = servoyProject.getEditingSolution().getForms(null, false);
				while (activeForms.hasNext())
				{
					forms.add(activeForms.next());
				}

				ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
				for (ServoyProject module : modules)
				{
					if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
					{
						Iterator<Form> moduleForms = module.getEditingSolution().getForms(null, false);
						while (moduleForms.hasNext())
						{
							forms.add(moduleForms.next());
						}
					}
				}
			}

			if (forms.isEmpty())
			{
				return "No forms found" + ("current".equals(scope) ? " in '" + contextName + "'" : " in the active solution");
			}

			StringBuilder result = new StringBuilder();
			if ("current".equals(scope))
			{
				result.append("Forms in '").append(contextName).append("' (").append(forms.size()).append("):\n\n");
			}
			else
			{
				result.append("Forms in solution '").append(activeSolutionName).append("' and modules (").append(forms.size()).append("):\n\n");
			}

			int count = 1;
			for (Form form : forms)
			{
				String solutionName = getSolutionName(form);
				String originInfo = formatOrigin(solutionName, activeSolutionName);

				result.append(count).append(". ").append(form.getName()).append(originInfo);

				if (form.getDataSource() != null && !form.getDataSource().trim().isEmpty())
				{
					result.append(" - DataSource: ").append(form.getDataSource());
				}

				String formType = form.isResponsiveLayout() ? "responsive"
					: (form.getUseCssPosition() != null && form.getUseCssPosition() ? "css" : "absolute");
				result.append(" (").append(formType).append(", ").append(form.getWidth()).append("x").append(form.getHeight()).append(")");
				result.append("\n");
				count++;
			}

			return result.toString();
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	public String openOrCreateForm(String name, boolean create, int width, int height, String style,
		String dataSource, String extendsFormName, Boolean setAsMainForm, Map<String, Object> properties,
		Map<String, String> events) throws RepositoryException
	{
		ServoyLog.logInfo("[CoreToolsHelper] Processing form: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			if (!style.equals("css") && !style.equals("responsive"))
			{
				throw new RepositoryException("Invalid style value: " + style + ". Must be 'css' or 'responsive'.");
			}

			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			if (targetProject != null && targetProject.getEditingSolution() != null)
			{
				List<Form> allMatchingForms = new ArrayList<>();
				List<String> formLocations = new ArrayList<>();

				Form formInTarget = targetProject.getEditingSolution().getForm(name);
				if (formInTarget != null)
				{
					allMatchingForms.add(formInTarget);
					formLocations.add("active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target);
				}

				if (!targetProject.equals(servoyProject))
				{
					Form formInActive = servoyProject.getEditingSolution().getForm(name);
					if (formInActive != null && !allMatchingForms.contains(formInActive))
					{
						allMatchingForms.add(formInActive);
						formLocations.add(servoyProject.getProject().getName() + " (active solution)");
					}
				}

				ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
				for (ServoyProject module : modules)
				{
					if (module != null && module.getEditingSolution() != null &&
						!module.equals(targetProject) && !module.equals(servoyProject))
					{
						Form formInModule = module.getEditingSolution().getForm(name);
						if (formInModule != null && !allMatchingForms.contains(formInModule))
						{
							allMatchingForms.add(formInModule);
							formLocations.add(module.getProject().getName());
						}
					}
				}

				boolean isNewForm = false;
				Form form = null;

				if (!allMatchingForms.isEmpty())
				{
					form = allMatchingForms.get(0);
				}
				else if (create)
				{
					form = FormService.createFormInProject(targetProject, name, width, height, style, dataSource);
					allMatchingForms.add(form);
					formLocations.add(contextDisplay);
					isNewForm = true;
				}
				else
				{
					throw new RepositoryException("Form '" + name + "' not found. Use create=true to create it.");
				}

				if (properties != null && !properties.isEmpty())
				{
					FormService.applyFormProperties(form, properties);
					targetProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
				}

				if (events != null && !events.isEmpty())
				{
					FormService.applyFormEvents(form, events, targetProject);
				}

				if (extendsFormName != null && !extendsFormName.trim().isEmpty())
				{
					FormService.setFormParent(form, extendsFormName, servoyProject);
					targetProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
				}

				if (setAsMainForm != null && setAsMainForm)
				{
					try
					{
						boolean useNewAPI = ClientVersion.getMajorVersion() > 2025 ||
							(ClientVersion.getMajorVersion() == 2025 && ClientVersion.getMiddleVersion() >= 12);
						if (useNewAPI)
						{
							Method setFirstFormID = Solution.class.getMethod("setFirstFormID", String.class);
							setFirstFormID.invoke(servoyProject.getEditingSolution(), form.getUUID().toString());
						}
						else
						{
							Method setFirstFormID = Solution.class.getMethod("setFirstFormID", int.class);
							Method getID = Form.class.getMethod("getID");
							setFirstFormID.invoke(servoyProject.getEditingSolution(), getID.invoke(form));
						}
						servoyProject.saveEditingSolutionNodes(new IPersist[] { servoyProject.getEditingSolution() }, true);
					}
					catch (NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException e)
					{
						ServoyLog.logError("Error setFirstFormID on solution of form " + form, e);
					}
				}

				if (isNewForm)
				{
					final Form formToOpen = form;
					UIThreadHelper.asyncExec("openFormEditor", () -> EditorUtil.openFormDesignEditor(formToOpen));
				}
				else
				{
					final List<Form> formsToOpen = new ArrayList<>(allMatchingForms);
					UIThreadHelper.asyncExec("openFormEditors", () -> {
						for (Form f : formsToOpen)
						{
							EditorUtil.openFormDesignEditor(f);
						}
					});
				}

				StringBuilder result = new StringBuilder();
				if (isNewForm)
				{
					result.append("Form '").append(name).append("' created successfully in ").append(contextDisplay);
					result.append(" (").append(style).append(", ").append(width).append("x").append(height).append(")");
					if (dataSource != null)
					{
						result.append("\n  DataSource: ").append(dataSource);
					}
				}
				else
				{
					if (allMatchingForms.size() == 1)
					{
						result.append("Form '").append(name).append("' opened successfully");
						result.append(" (from ").append(formLocations.get(0)).append(")");
					}
					else
					{
						result.append("Form '").append(name).append("' found in ").append(allMatchingForms.size()).append(" locations. Opened all:\n");
						for (int i = 0; i < allMatchingForms.size(); i++)
						{
							result.append("  - ").append(formLocations.get(i)).append("\n");
						}
					}
					result.append("\n[Context remains: ").append(contextDisplay).append("]");
				}

				return result.toString();
			}

			throw new RepositoryException("No target solution/module found for context: " + target);
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	public String deleteFormsImpl(List<String> names) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String target = TargetService.getInstance().getCurrentTarget();
			String contextDisplay = "active".equals(target) ? targetProject.getProject().getName() + " (active solution)" : target;

			List<String> deletedForms = new ArrayList<>();
			List<String> notFoundForms = new ArrayList<>();
			List<String> needsApproval = new ArrayList<>();
			Map<String, String> approvalLocations = new HashMap<>();
			List<Form> formsToDelete = new ArrayList<>();

			for (String name : names)
			{
				if (name == null || name.trim().isEmpty())
				{
					continue;
				}

				Form form = targetProject.getEditingSolution().getForm(name);
				String foundInContext = null;

				if (form != null)
				{
					foundInContext = target;
					formsToDelete.add(form);
				}
				else
				{
					if (!targetProject.equals(servoyProject))
					{
						form = servoyProject.getEditingSolution().getForm(name);
						if (form != null)
						{
							foundInContext = "active";
						}
					}

					if (form == null)
					{
						ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
						for (ServoyProject module : modules)
						{
							if (module != null && module.getEditingSolution() != null && !module.equals(targetProject))
							{
								form = module.getEditingSolution().getForm(name);
								if (form != null)
								{
									foundInContext = module.getProject().getName();
									break;
								}
							}
						}
					}

					if (form != null)
					{
						needsApproval.add(name);
						approvalLocations.put(name, foundInContext);
					}
					else
					{
						notFoundForms.add(name);
					}
				}
			}

			if (!needsApproval.isEmpty())
			{
				StringBuilder approvalMsg = new StringBuilder();
				approvalMsg.append("Current context: ").append(contextDisplay).append("\n\n");

				if (needsApproval.size() == 1)
				{
					String formName = needsApproval.get(0);
					String location = approvalLocations.get(formName);
					String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;
					approvalMsg.append("Form '").append(formName).append("' found in ").append(locationDisplay).append(".\n");
					approvalMsg.append("Current context is ").append(contextDisplay).append(".\n\n");
					approvalMsg.append("To delete this form, I need to switch to ").append(locationDisplay).append(".\n");
					approvalMsg.append("Do you want to proceed?\n\n");
					approvalMsg.append("[If yes, I will: setContext({context: \"").append(location).append("\"}) then delete]");
				}
				else
				{
					approvalMsg.append("Multiple forms found in different locations:\n");
					for (String formName : needsApproval)
					{
						String location = approvalLocations.get(formName);
						String locationDisplay = "active".equals(location) ? servoyProject.getProject().getName() + " (active solution)" : location;
						approvalMsg.append("  - ").append(formName).append(" (in ").append(locationDisplay).append(")\n");
					}
					approvalMsg.append("\nCurrent context is ").append(contextDisplay).append(".\n");
					approvalMsg.append("Please switch context explicitly using setContext");
				}

				if (!formsToDelete.isEmpty())
				{
					approvalMsg.append("\n\nNote: Can delete from current context without approval: ");
					approvalMsg.append(String.join(", ", formsToDelete.stream().map(Form::getName).toArray(String[]::new)));
				}

				return approvalMsg.toString();
			}

			if (!formsToDelete.isEmpty())
			{
				EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();
				for (Form form : formsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(form.getUUID());
					if (editingNode == null)
					{
						editingNode = form;
					}
					repository.deleteObject(editingNode);
				}
				for (Form form : formsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(form.getUUID());
					if (editingNode == null)
					{
						editingNode = form;
					}
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deletedForms.add(form.getName());
				}
			}

			StringBuilder result = new StringBuilder();
			if (!deletedForms.isEmpty())
			{
				result.append("Successfully deleted ").append(deletedForms.size()).append(" form(s): ");
				result.append(String.join(", ", deletedForms));
			}
			if (!notFoundForms.isEmpty())
			{
				if (result.length() > 0)
				{
					result.append("\n\n");
				}
				result.append("Forms not found (").append(notFoundForms.size()).append("): ");
				result.append(String.join(", ", notFoundForms));
			}
			if (deletedForms.isEmpty() && notFoundForms.isEmpty())
			{
				result.append("No forms specified for deletion");
			}

			return result.toString();
		}

		throw new RepositoryException("No active Servoy solution project found");
	}

	// -------------------------------------------------------------------------
	// Relation operations
	// -------------------------------------------------------------------------

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
				ServoyProject targetProject = resolveTargetProject(servoyModel);
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

		throw new RepositoryException("No active Servoy solution project found");
	}

	public String openOrCreateRelation(String name, String primaryDataSource, String foreignDataSource,
		String primaryColumn, String foreignColumn, Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[CoreToolsHelper] Processing relation: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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

	// -------------------------------------------------------------------------
	// Style operations
	// -------------------------------------------------------------------------

	public String listStylesImpl(String lessFileName, String scope) throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null)
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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

	// -------------------------------------------------------------------------
	// ValueList operations
	// -------------------------------------------------------------------------

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
		ServoyLog.logInfo("[CoreToolsHelper] Processing valuelist: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject != null && servoyProject.getEditingSolution() != null)
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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
			ServoyProject targetProject = resolveTargetProject(servoyModel);
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

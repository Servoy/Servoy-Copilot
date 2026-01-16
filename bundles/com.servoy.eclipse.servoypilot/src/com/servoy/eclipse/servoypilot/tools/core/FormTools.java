package com.servoy.eclipse.servoypilot.tools.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import com.servoy.eclipse.servoypilot.services.FormService;
import com.servoy.eclipse.ui.util.EditorUtil;
import com.servoy.j2db.ClientVersion;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Servoy Form operations.
 * Migrated from knowledgebase.mcp FormToolHandler.
 * 
 * Complete migration: All 3 main tools implemented.
 */
public class FormTools
{
	/**
	 * Lists forms in the active solution and its modules.
	 */
	@Tool("Lists forms in the active solution and its modules. Optional scope parameter: 'current' for context only, 'all' for solution + modules (default).")
	public String getForms(
		@P(value = "Scope: 'current' for context only, 'all' for solution + modules (default 'all')", required = false) String scope)
	{
		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = listFormsImpl(scope != null ? scope : "all");
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error listing forms", exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Opens an existing form or creates a new form.
	 */
	@Tool("Opens an existing form or creates a new form. Context-aware: when create=true, form created in current context.")
	public String openForm(
		@P(value = "Form name", required = true) String name,
		@P(value = "Create if doesn't exist (default: false)", required = false) Boolean create,
		@P(value = "Form width (default: 640)", required = false) Integer width,
		@P(value = "Form height (default: 480)", required = false) Integer height,
		@P(value = "Form style: 'css' or 'responsive' (default: 'css')", required = false) String style,
		@P(value = "DataSource (format: 'db:/server_name/table_name')", required = false) String dataSource,
		@P(value = "Parent form name (for inheritance)", required = false) String extendsForm,
		@P(value = "Set as main form (default: false)", required = false) Boolean setAsMainForm,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties)
	{
		if (name == null || name.trim().isEmpty()) return "Error: name parameter is required";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				boolean shouldCreate = create != null && create;
				int formWidth = width != null ? width : 640;
				int formHeight = height != null ? height : 480;
				String formStyle = style != null ? style : "css";

				result[0] = openOrCreateForm(name, shouldCreate, formWidth, formHeight, formStyle,
					dataSource, extendsForm, setAsMainForm, properties);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error opening/creating form: " + name, exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Deletes one or more existing forms.
	 */
	@Tool("Deletes one or more existing forms. Requires approval if form not in current context.")
	public String deleteForms(
		@P(value = "Array of form names to delete", required = true) List<String> names)
	{
		if (names == null || names.isEmpty()) return "Error: names parameter is required (array of form names)";

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = deleteFormsImpl(names);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error deleting forms", exception[0]);
			return "Error: " + exception[0].getMessage();
		}

		return result[0];
	}

	// =============================================
	// IMPLEMENTATION: listForms
	// =============================================

	private String listFormsImpl(String scope) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No active Servoy solution project found");
		}

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		String contextName = null;

		List<Form> forms = new ArrayList<>();

		if ("current".equals(scope))
		{
			ServoyProject targetProject = resolveTargetProject(servoyModel);
			String context = ContextService.getInstance().getCurrentContext();
			contextName = "active".equals(context) ? activeSolutionName : context;

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

// for now disabled this main form marking thing for the AI, why does it need this? most of the time the main form says nothing (just some form with some tabpanels)
// the biggest problem is that for 2024 and 2025 LTS this first form is still a id not a uuid.
// so to support both we could use reflection?
// also this uses the servoyProject is this really a normal main solution?  Modules don't have a first form..		
		// Get main form if set
//		String mainFormUUID = servoyProject.getEditingSolution().getFirstFormID();
//		String mainFormName = null;
//		if (mainFormUUID != null)
//		{
//			Form mainForm = servoyProject.getEditingSolution().getForm(mainFormUUID);
//			if (mainForm != null) mainFormName = mainForm.getName();
//		}

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

			result.append(count).append(". ").append(form.getName());

//			if (mainFormName != null && form.getName().equals(mainFormName))
//			{
//				result.append(" [MAIN FORM]");
//			}

			result.append(originInfo);

			if (form.getDataSource() != null && !form.getDataSource().trim().isEmpty())
			{
				result.append(" - DataSource: ").append(form.getDataSource());
			}

			String formType = form.isResponsiveLayout() ? "responsive" : (form.getUseCssPosition() != null && form.getUseCssPosition() ? "css" : "absolute");
			result.append(" (").append(formType).append(", ").append(form.getWidth()).append("x").append(form.getHeight()).append(")");

			result.append("\n");
			count++;
		}

		return result.toString();
	}

	// =============================================
	// IMPLEMENTATION: openForm
	// =============================================

	private String openOrCreateForm(String name, boolean create, int width, int height, String style,
		String dataSource, String extendsForm, Boolean setAsMainForm, Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[FormTools] Processing form: " + name);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No active Servoy solution project found");
		}

		// Validate style
		if (!style.equals("css") && !style.equals("responsive"))
		{
			throw new RepositoryException("Invalid style value: " + style + ". Must be 'css' or 'responsive'.");
		}

		ServoyProject targetProject = resolveTargetProject(servoyModel);
		String targetContext = ContextService.getInstance().getCurrentContext();
		String contextDisplay = "active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext;

		if (targetProject == null || targetProject.getEditingSolution() == null)
		{
			throw new RepositoryException("No target solution/module found for context: " + targetContext);
		}

		// Search for existing forms
		List<Form> allMatchingForms = new ArrayList<>();
		List<String> formLocations = new ArrayList<>();

		Form formInTarget = targetProject.getEditingSolution().getForm(name);
		if (formInTarget != null)
		{
			allMatchingForms.add(formInTarget);
			formLocations.add("active".equals(targetContext) ? targetProject.getProject().getName() + " (active solution)" : targetContext);
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
			// Create new form in current context
			form = FormService.createFormInProject(targetProject, name, width, height, style, dataSource);
			allMatchingForms.add(form);
			formLocations.add(contextDisplay);
			isNewForm = true;
		}
		else
		{
			throw new RepositoryException("Form '" + name + "' not found. Use create=true to create it.");
		}

		// Apply properties if provided
		if (!isNewForm && properties != null && !properties.isEmpty())
		{
			FormService.applyFormProperties(form, properties);
			targetProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
		}

		// Set parent form if specified
		if (extendsForm != null && !extendsForm.trim().isEmpty())
		{
			FormService.setFormParent(form, extendsForm, servoyProject);
			targetProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
		}

		// Set as main form if specified
		if (setAsMainForm != null && setAsMainForm)
		{
			try
			{
				if (ClientVersion.getMajorVersion() >= 2025 && ClientVersion.getMiddleVersion() >= 12)
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
				ServoyLog.logError("Error setFirstFormID on solution of form " + form  , e);
			}
		}

		// Open form in editor
		if (isNewForm)
		{
			final Form formToOpen = form;
			Display.getDefault().asyncExec(() -> EditorUtil.openFormDesignEditor(formToOpen));
		}
		else if (!allMatchingForms.isEmpty())
		{
			final List<Form> formsToOpen = new ArrayList<>(allMatchingForms);
			Display.getDefault().asyncExec(() -> {
				for (Form f : formsToOpen) EditorUtil.openFormDesignEditor(f);
			});
		}

		// Build result message
		StringBuilder result = new StringBuilder();

		if (isNewForm)
		{
			result.append("Form '").append(name).append("' created successfully in ").append(contextDisplay);
			result.append(" (").append(style).append(", ").append(width).append("x").append(height).append(")");
			if (dataSource != null) result.append("\n  DataSource: ").append(dataSource);
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

	// =============================================
	// IMPLEMENTATION: deleteForms
	// =============================================

	private String deleteFormsImpl(List<String> names) throws RepositoryException
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

		List<String> deletedForms = new ArrayList<>();
		List<String> notFoundForms = new ArrayList<>();
		List<String> needsApproval = new ArrayList<>();
		Map<String, String> approvalLocations = new HashMap<>();
		List<Form> formsToDelete = new ArrayList<>();

		// Find forms and check if they're in current context
		for (String name : names)
		{
			if (name == null || name.trim().isEmpty()) continue;

			Form form = targetProject.getEditingSolution().getForm(name);
			String foundInContext = null;

			if (form != null)
			{
				foundInContext = targetContext;
				formsToDelete.add(form);
			}
			else
			{
				// Search other locations
				if (!targetProject.equals(servoyProject))
				{
					form = servoyProject.getEditingSolution().getForm(name);
					if (form != null) foundInContext = "active";
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

		// If any items need approval, return approval request message
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
				approvalMsg.append(String.join(", ", formsToDelete.stream().map(f -> f.getName()).toArray(String[]::new)));
			}

			return approvalMsg.toString();
		}

		// Delete forms (all are in current context)
		if (!formsToDelete.isEmpty())
		{
			EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();

			try
			{
				for (Form form : formsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(form.getUUID());
					if (editingNode == null) editingNode = form;
					repository.deleteObject(editingNode);
					ServoyLog.logInfo("[FormTools] Called deleteObject for form: " + form.getName());
				}

				for (Form form : formsToDelete)
				{
					IPersist editingNode = servoyProject.getEditingPersist(form.getUUID());
					if (editingNode == null) editingNode = form;
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deletedForms.add(form.getName());
					ServoyLog.logInfo("[FormTools] Successfully deleted form: " + form.getName());
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("[FormTools] FAILED to delete forms", e);
				throw new RepositoryException("Failed to delete forms. Error: " + e.getMessage(), e);
			}
		}

		// Build result message
		StringBuilder result = new StringBuilder();

		if (!deletedForms.isEmpty())
		{
			result.append("Successfully deleted ").append(deletedForms.size()).append(" form(s): ");
			result.append(String.join(", ", deletedForms));
		}

		if (!notFoundForms.isEmpty())
		{
			if (result.length() > 0) result.append("\n\n");
			result.append("Forms not found (").append(notFoundForms.size()).append("): ");
			result.append(String.join(", ", notFoundForms));
		}

		if (deletedForms.isEmpty() && notFoundForms.isEmpty())
		{
			result.append("No forms specified for deletion");
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

	private String getSolutionName(IPersist persist)
	{
		try
		{
			IRootObject rootObject = persist.getRootObject();
			if (rootObject instanceof Solution solution) return solution.getName();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[FormTools] Error getting solution name", e);
		}
		return "unknown";
	}

	private String formatOrigin(String solutionName, String activeSolutionName)
	{
		if (solutionName.equals(activeSolutionName)) return " (in: active solution)";
		else return " (in: " + solutionName + ")";
	}
}

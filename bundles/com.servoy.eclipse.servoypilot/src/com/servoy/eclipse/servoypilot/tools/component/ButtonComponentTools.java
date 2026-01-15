package com.servoy.eclipse.servoypilot.tools.component;

import java.util.Iterator;

import org.eclipse.swt.widgets.Display;
import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.BootstrapComponentService;
import com.servoy.eclipse.servoypilot.services.ContextService;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Solution;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Bootstrap Button component operations.
 * Migrated from knowledgebase.mcp ButtonComponentHandler.
 * 
 * PILOT: Only listButtons tool migrated as example.
 * TODO: Migrate remaining tools: addButton, updateButton, deleteButton, getButtonInfo
 */
public class ButtonComponentTools
{
	/**
	 * Lists all button components in a form.
	 * 
	 * @param formName The form name
	 * @return JSON formatted string with button information
	 */
	@Tool("Lists all button components in a form with their details. " +
		"Context-aware: searches for form in current context first, then falls back to active solution and modules.")
	public String listButtons(
		@P(value = "Form name", required = true) String formName)
	{
		if (formName == null || formName.trim().isEmpty())
		{
			return "Error: formName parameter is required";
		}

		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = listButtonsImpl(formName);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			return "Error listing buttons: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Implementation of button listing logic.
	 */
	private String listButtonsImpl(String formName) throws Exception
	{
		// Find form using context-aware search
		Form form = findForm(formName);
		if (form == null)
		{
			return "Error: Form '" + formName + "' not found";
		}

		// Get all button components from the form
		JSONArray buttons = new JSONArray();
		Iterator<IPersist> iterator = form.getAllObjects();

		while (iterator.hasNext())
		{
			IPersist persist = iterator.next();
			if (BootstrapComponentService.isButtonComponent(persist))
			{
				JSONObject buttonInfo = BootstrapComponentService.getButtonInfo(persist);
				if (buttonInfo != null)
				{
					buttons.put(buttonInfo);
				}
			}
		}

		JSONObject response = new JSONObject();
		response.put("formName", formName);
		response.put("buttonCount", buttons.length());
		response.put("buttons", buttons);

		return response.toString(2);
	}

	/**
	 * Context-aware form finder.
	 * Searches current context first, then active solution and modules.
	 */
	private Form findForm(String formName) throws Exception
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
		{
			throw new Exception("No active Servoy project");
		}

		// Try current context first
		ServoyProject contextProject = ContextService.getCurrentContextProject();
		if (contextProject != null)
		{
			Solution contextSolution = contextProject.getEditingSolution();
			if (contextSolution != null)
			{
				Form form = contextSolution.getForm(formName);
				if (form != null)
				{
					return form;
				}
			}
		}

		// Fallback: search active solution
		Solution activeSolution = activeProject.getEditingSolution();
		if (activeSolution != null)
		{
			Form form = activeSolution.getForm(formName);
			if (form != null)
			{
				return form;
			}

			// Search modules
			if (activeSolution.getModulesNames() != null)
			{
				ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
				for (ServoyProject module : modules)
				{
					if (module != null && module.getEditingSolution() != null)
					{
						form = module.getEditingSolution().getForm(formName);
						if (form != null)
						{
							return form;
						}
					}
				}
			}
		}

		return null;
	}
}

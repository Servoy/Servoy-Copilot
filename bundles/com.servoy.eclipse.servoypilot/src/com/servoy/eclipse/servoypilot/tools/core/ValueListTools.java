package com.servoy.eclipse.servoypilot.tools.core;

import java.util.Iterator;
import java.util.List;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.ContextService;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for Servoy ValueList operations.
 * Migrated from knowledgebase.mcp ValueListToolHandler.
 * 
 * PILOT: Only getValueLists tool migrated as example.
 * TODO: Migrate remaining tools: openValueList, deleteValueLists
 */
public class ValueListTools
{
	/**
	 * Lists valuelists in the active solution and its modules.
	 * 
	 * @param scope Optional scope: 'current' (context only) or 'all' (solution + modules). Default: 'all'
	 * @return Formatted string with valuelist information
	 */
	@Tool("Lists valuelists in the active solution and its modules. " +
		"Optional scope parameter: 'current' returns valuelists from current context only, " +
		"'all' returns valuelists from active solution and all modules (default).")
	public String getValueLists(
		@P(value = "Scope: 'current' for context only, 'all' for solution + modules (default 'all')", required = false) String scope)
	{
		final String[] result = new String[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = listValueLists(scope);
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			return "Error listing valuelists: " + exception[0].getMessage();
		}

		return result[0];
	}

	/**
	 * Implementation of valuelist listing logic.
	 */
	private String listValueLists(String scope)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
		{
			return "Error: No active Servoy project";
		}

		Solution activeSolution = activeProject.getEditingSolution();
		if (activeSolution == null)
		{
			return "Error: No active solution";
		}

		StringBuilder result = new StringBuilder();
		result.append("ValueLists:\n\n");

		// Determine scope
		boolean currentOnly = "current".equalsIgnoreCase(scope);
		int totalCount = 0;

		if (currentOnly)
		{
			// Current context only
			ServoyProject contextProject = ContextService.getCurrentContextProject();
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
				return "Error: No current context set";
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
}

/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseRepository;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Service for listing and deleting Servoy solution artifacts (forms, relations, valuelists, styles).
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.tools.core.CoreToolsHelper}.
 * Simplified: no TargetService dependency - uses active solution + modules directly.
 * No UI operations (no EditorUtil calls).
 * </p>
 */
@Creatable
public class ServoySolutionService
{
	// -------------------------------------------------------------------------
	// Forms
	// -------------------------------------------------------------------------

	public String listForms(String scope)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		List<Form> forms = new ArrayList<>();
		boolean currentOnly = "current".equalsIgnoreCase(scope);

		if (currentOnly)
		{
			Iterator<Form> formsIterator = servoyProject.getEditingSolution().getForms(null, false);
			while (formsIterator.hasNext()) forms.add(formsIterator.next());
		}
		else
		{
			Iterator<Form> activeForms = servoyProject.getEditingSolution().getForms(null, false);
			while (activeForms.hasNext()) forms.add(activeForms.next());

			for (ServoyProject module : servoyModel.getModulesOfActiveProject())
			{
				if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
				{
					Iterator<Form> moduleForms = module.getEditingSolution().getForms(null, false);
					while (moduleForms.hasNext()) forms.add(moduleForms.next());
				}
			}
		}

		if (forms.isEmpty())
			return "No forms found" + (currentOnly ? " in '" + activeSolutionName + "'" : " in the active solution");

		StringBuilder result = new StringBuilder();
		result.append("Forms in ").append(currentOnly ? "'" + activeSolutionName + "'" : "solution '" + activeSolutionName + "' and modules")
			.append(" (").append(forms.size()).append("):\n\n");

		int count = 1;
		for (Form form : forms)
		{
			String origin = getSolutionName(form);
			result.append(count++).append(". ").append(form.getName()).append(formatOrigin(origin, activeSolutionName));
			if (form.getDataSource() != null && !form.getDataSource().trim().isEmpty())
				result.append(" - DataSource: ").append(form.getDataSource());
			String formType = form.isResponsiveLayout() ? "responsive"
				: (form.getUseCssPosition() != null && form.getUseCssPosition() ? "css" : "absolute");
			result.append(" (").append(formType).append(")");
			result.append("\n");
		}
		return result.toString();
	}

	/**
	 * Finds forms matching a natural-language query by searching form name, title text, comment, and
	 * data source. Returns all forms when query is blank. Results are sorted: exact name match first,
	 * then name/title contains match, then datasource/comment contains match.
	 */
	public String findForm(String query)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		String queryLower = (query == null) ? "" : query.toLowerCase().trim();

		// collect all forms from active solution + modules
		List<Form> allForms = new ArrayList<>();
		Iterator<Form> activeForms = servoyProject.getEditingSolution().getForms(null, false);
		while (activeForms.hasNext()) allForms.add(activeForms.next());
		for (ServoyProject module : servoyModel.getModulesOfActiveProject())
		{
			if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
			{
				Iterator<Form> moduleForms = module.getEditingSolution().getForms(null, false);
				while (moduleForms.hasNext()) allForms.add(moduleForms.next());
			}
		}

		// score each form: 3=exact name, 2=name/title contains, 1=datasource/comment contains, 0=no match
		List<Form> matched = new ArrayList<>();
		List<Integer> scores = new ArrayList<>();
		for (Form form : allForms)
		{
			int score = scoreForm(form, queryLower);
			if (queryLower.isEmpty() || score > 0)
			{
				matched.add(form);
				scores.add(score);
			}
		}

		// sort by score descending (simple insertion-sort since lists are small)
		for (int i = 1; i < matched.size(); i++)
		{
			Form f = matched.get(i);
			int s = scores.get(i);
			int j = i - 1;
			while (j >= 0 && scores.get(j) < s)
			{
				matched.set(j + 1, matched.get(j));
				scores.set(j + 1, scores.get(j));
				j--;
			}
			matched.set(j + 1, f);
			scores.set(j + 1, s);
		}

		if (matched.isEmpty())
		{
			return "No forms found matching '" + query + "'.\n"
				+ "Tip: use listForms to see all available forms, or try a shorter/different search term.";
		}

		StringBuilder result = new StringBuilder();
		result.append("# Forms matching '").append(queryLower.isEmpty() ? "(all)" : query)
			.append("' (").append(matched.size()).append(" found)\n\n");

		for (int i = 0; i < matched.size(); i++)
		{
			Form form = matched.get(i);
			String origin = getSolutionName(form);
			result.append("## ").append(form.getName()).append("\n");
			String title = form.getTitleText();
			if (title != null && !title.isBlank())
				result.append("- **title:** ").append(title).append("\n");
			result.append("- **internal name:** `").append(form.getName()).append("`\n");
			result.append("- **module:** ").append(origin).append(origin.equals(activeSolutionName) ? " (active)" : "").append("\n");
			String ds = form.getDataSource();
			if (ds != null && !ds.isBlank() && !"-none-".equals(ds))
				result.append("- **dataSource:** ").append(ds).append("\n");
			result.append("- **layout:** ").append(getFormLayoutType(form)).append("\n");
			String comment = form.getComment();
			if (comment != null && !comment.isBlank())
				result.append("- **comment:** ").append(comment).append("\n");
			result.append("\n");
		}
		return result.toString();
	}

	private static int scoreForm(Form form, String queryLower)
	{
		if (queryLower.isEmpty()) return 1;
		String name = form.getName() != null ? form.getName().toLowerCase() : "";
		String title = form.getTitleText() != null ? form.getTitleText().toLowerCase() : "";
		String ds = form.getDataSource() != null ? form.getDataSource().toLowerCase() : "";
		String comment = form.getComment() != null ? form.getComment().toLowerCase() : "";
		if (name.equals(queryLower) || title.equals(queryLower)) return 3;
		if (name.contains(queryLower) || title.contains(queryLower)) return 2;
		if (ds.contains(queryLower) || comment.contains(queryLower)) return 1;
		// also try matching individual words from the query
		String[] words = queryLower.split("\\s+");
		int wordMatches = 0;
		for (String word : words)
		{
			if (word.length() < 3) continue;
			if (name.contains(word) || title.contains(word)) wordMatches++;
		}
		if (wordMatches > 0) return wordMatches;
		return 0;
	}

	private static String getFormLayoutType(Form form)
	{
		if (form.isResponsiveLayout()) return "responsive";
		if (Boolean.TRUE.equals(form.getUseCssPosition())) return "css";
		return "absolute";
	}

	public String deleteForms(List<String> names)
	{
		return deleteArtifacts(names, "form", (project, name) -> project.getEditingSolution().getForm(name));
	}

	// -------------------------------------------------------------------------
	// Relations
	// -------------------------------------------------------------------------

	public String listRelations(String scope)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		List<Relation> relations = new ArrayList<>();
		boolean currentOnly = "current".equalsIgnoreCase(scope);

		if (currentOnly)
		{
			Iterator<Relation> iter = servoyProject.getEditingSolution().getRelations(false);
			while (iter.hasNext()) relations.add(iter.next());
		}
		else
		{
			Iterator<Relation> activeRels = servoyProject.getEditingSolution().getRelations(false);
			while (activeRels.hasNext()) relations.add(activeRels.next());

			for (ServoyProject module : servoyModel.getModulesOfActiveProject())
			{
				if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
				{
					Iterator<Relation> moduleRels = module.getEditingSolution().getRelations(false);
					while (moduleRels.hasNext()) relations.add(moduleRels.next());
				}
			}
		}

		if (relations.isEmpty())
			return "No relations found" + (currentOnly ? " in '" + activeSolutionName + "'" : " in the active solution");

		StringBuilder result = new StringBuilder();
		result.append("Relations in ").append(currentOnly ? "'" + activeSolutionName + "'" : "solution '" + activeSolutionName + "' and modules")
			.append(" (").append(relations.size()).append("):\n\n");

		int count = 1;
		for (Relation relation : relations)
		{
			String origin = getSolutionName(relation);
			result.append(count++).append(". ").append(relation.getName()).append(formatOrigin(origin, activeSolutionName));
			result.append("\n   Primary: ").append(relation.getPrimaryDataSource());
			result.append("\n   Foreign: ").append(relation.getForeignDataSource());
			String joinType = relation.getJoinType() == 1 ? "INNER" : "LEFT OUTER";
			result.append(" (").append(joinType).append(" JOIN)");
			result.append("\n");
		}
		return result.toString();
	}

	public String deleteRelations(List<String> names)
	{
		return deleteArtifacts(names, "relation", (project, name) -> project.getEditingSolution().getRelation(name));
	}

	// -------------------------------------------------------------------------
	// ValueLists
	// -------------------------------------------------------------------------

	public String listValueLists(String scope)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		boolean currentOnly = "current".equalsIgnoreCase(scope);
		StringBuilder result = new StringBuilder();
		int totalCount = 0;

		if (currentOnly)
		{
			Iterator<ValueList> iter = servoyProject.getEditingSolution().getValueLists(false);
			while (iter.hasNext())
			{
				result.append(formatValueListInfo(iter.next(), activeSolutionName));
				totalCount++;
			}
		}
		else
		{
			List<Solution> solutions = new ArrayList<>();
			solutions.add(servoyProject.getEditingSolution());
			for (ServoyProject module : servoyModel.getModulesOfActiveProject())
			{
				if (module != null && module.getEditingSolution() != null)
					solutions.add(module.getEditingSolution());
			}
			for (Solution solution : solutions)
			{
				Iterator<ValueList> iter = solution.getValueLists(false);
				while (iter.hasNext())
				{
					result.append(formatValueListInfo(iter.next(), solution.getName()));
					totalCount++;
				}
			}
		}

		if (totalCount == 0)
			return "No valuelists found" + (currentOnly ? " in '" + activeSolutionName + "'" : " in the active solution");

		result.insert(0, "ValueLists (" + totalCount + "):\n\n");
		return result.toString();
	}

	public String deleteValueLists(List<String> names)
	{
		return deleteArtifacts(names, "valuelist", (project, name) -> project.getEditingSolution().getValueList(name));
	}

	// -------------------------------------------------------------------------
	// Styles (simplified - lists .less file existence per solution)
	// -------------------------------------------------------------------------

	public String listStyles(String scope)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = servoyProject.getEditingSolution().getName();
		boolean currentOnly = "current".equalsIgnoreCase(scope);
		StringBuilder result = new StringBuilder();
		result.append("Styles:\n\n");

		if (currentOnly)
		{
			appendStyleInfo(result, servoyProject);
		}
		else
		{
			appendStyleInfo(result, servoyProject);
			for (ServoyProject module : servoyModel.getModulesOfActiveProject())
			{
				if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
					appendStyleInfo(result, module);
			}
		}
		return result.toString();
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	@FunctionalInterface
	private interface ArtifactFinder
	{
		IPersist find(ServoyProject project, String name);
	}

	private String deleteArtifacts(List<String> names, String artifactType, ArtifactFinder finder)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();

		if (servoyProject == null || servoyProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		List<String> deleted = new ArrayList<>();
		List<String> notFound = new ArrayList<>();

		for (String name : names)
		{
			if (name == null || name.trim().isEmpty()) continue;

			IPersist artifact = finder.find(servoyProject, name);
			if (artifact == null)
			{
				// Search in modules
				for (ServoyProject module : servoyModel.getModulesOfActiveProject())
				{
					if (module != null && module.getEditingSolution() != null && !module.equals(servoyProject))
					{
						artifact = finder.find(module, name);
						if (artifact != null) break;
					}
				}
			}

			if (artifact != null)
			{
				try
				{
					EclipseRepository repository = (EclipseRepository)servoyProject.getEditingSolution().getRepository();
					IPersist editingNode = servoyProject.getEditingPersist(artifact.getUUID());
					if (editingNode == null) editingNode = artifact;
					repository.deleteObject(editingNode);
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingNode }, true);
					deleted.add(name);
				}
				catch (RepositoryException e)
				{
					ServoyLog.logError("Error deleting " + artifactType + ": " + name, e);
					notFound.add(name + " (error: " + e.getMessage() + ")");
				}
			}
			else
			{
				notFound.add(name);
			}
		}

		StringBuilder result = new StringBuilder();
		if (!deleted.isEmpty())
			result.append("Successfully deleted ").append(deleted.size()).append(" ").append(artifactType).append("(s): ")
				.append(String.join(", ", deleted));
		if (!notFound.isEmpty())
		{
			if (result.length() > 0) result.append("\n\n");
			result.append("Not found (").append(notFound.size()).append("): ").append(String.join(", ", notFound));
		}
		if (deleted.isEmpty() && notFound.isEmpty())
			result.append("No ").append(artifactType).append("s specified for deletion");
		return result.toString();
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
			ServoyLog.logError("ServoySolutionService: error getting solution name", e);
		}
		return "unknown";
	}

	private String formatOrigin(String solutionName, String activeSolutionName)
	{
		if (solutionName.equals(activeSolutionName)) return " (in: active solution)";
		return " (in: " + solutionName + ")";
	}

	private String formatValueListInfo(ValueList vl, String origin)
	{
		StringBuilder info = new StringBuilder();
		info.append("  - ").append(vl.getName()).append(" [").append(origin).append("]");
		if (vl.getRelationName() != null && !vl.getRelationName().trim().isEmpty())
			info.append(" (Type: DATABASE/RELATED, Relation: ").append(vl.getRelationName()).append(")");
		else if (vl.getDataSource() != null && !vl.getDataSource().trim().isEmpty())
			info.append(" (Type: DATABASE/TABLE, DataSource: ").append(vl.getDataSource()).append(")");
		else if (vl.getCustomValues() != null && !vl.getCustomValues().trim().isEmpty())
			info.append(" (Type: CUSTOM)");
		info.append("\n");
		return info.toString();
	}

	private void appendStyleInfo(StringBuilder result, ServoyProject project)
	{
		String solutionName = project.getEditingSolution().getName();
		String lessFile = solutionName + ".less";
		org.eclipse.core.resources.IFile file = project.getProject().getFile("medias/" + lessFile);
		if (file.exists())
			result.append("  - ").append(solutionName).append(": ").append(lessFile).append(" (exists)\n");
		else
			result.append("  - ").append(solutionName).append(": no .less file\n");
	}
}

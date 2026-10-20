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
import com.servoy.j2db.FlattenedSolution;
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

		// legacy ordering: stable sort on the collapsed score (tier, or word-match count when the tier is 0)
		List<FormCandidate> matched = collectCandidates(servoyProject, queryLower);
		matched.sort((a, b) -> Integer.compare(legacyScore(b, queryLower), legacyScore(a, queryLower)));

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
			Form form = matched.get(i).form();
			String origin = matched.get(i).module();
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

	/** A form that matched a query, with its structured relevance. */
	public record FormCandidate(Form form, String module, int tier, int wordMatches)
	{
	}

	public static final String ERR_NO_ACTIVE_SOLUTION = "Error: No active Servoy solution project found";

	/** Index of the tier in the array returned by {@link #score}. */
	static final int TIER = 0;
	/** Index of the word-match count in the array returned by {@link #score}. */
	static final int WORD_MATCHES = 1;

	/**
	 * Returns forms matching the query, best first, with the structured relevance: tier 3 = exact name/title,
	 * 2 = name/title contains, 1 = datasource/comment contains, 0 = matched on individual words only. Sorted by
	 * (tier, wordMatches) descending; equal pairs keep the solution/module order. A blank query returns all forms
	 * (tier 0). Returns an empty list when there is no active solution.
	 */
	public List<FormCandidate> rankForms(String query)
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();
		if (servoyProject == null || servoyProject.getEditingSolution() == null) return new ArrayList<>();

		String queryLower = (query == null) ? "" : query.toLowerCase().trim();
		List<FormCandidate> candidates = collectCandidates(servoyProject, queryLower);
		candidates.sort(ServoySolutionService::compareCandidates);
		return candidates;
	}

	/** Descending comparison on (tier, wordMatches). */
	static int compareCandidates(FormCandidate a, FormCandidate b)
	{
		int byTier = Integer.compare(b.tier(), a.tier());
		return byTier != 0 ? byTier : Integer.compare(b.wordMatches(), a.wordMatches());
	}

	/** All matching forms of the active solution and its modules, in collection order (not sorted). */
	private List<FormCandidate> collectCandidates(ServoyProject servoyProject, String queryLower)
	{
		List<Form> allForms = new ArrayList<>();
		FlattenedSolution fs = servoyProject.getEditingFlattenedSolution(); // the active solution and all its modules
		if (fs == null) return new ArrayList<>();
		Iterator<Form> forms = fs.getForms(false);
		while (forms.hasNext()) allForms.add(forms.next());

		List<FormCandidate> matched = new ArrayList<>();
		for (Form form : allForms)
		{
			int[] score = score(form.getName(), form.getTitleText(), form.getDataSource(), form.getComment(), queryLower);
			if (queryLower.isEmpty() || score[TIER] > 0 || score[WORD_MATCHES] > 0)
			{
				matched.add(new FormCandidate(form, getSolutionName(form), score[TIER], score[WORD_MATCHES]));
			}
		}
		return matched;
	}

	/**
	 * The collapsed single-int score the original {@code findForm} sorted on: the tier when it is above 0, otherwise
	 * the word-match count, and 1 for a blank query. Kept only so the markdown order of {@code findForm} does not change.
	 */
	private static int legacyScore(FormCandidate candidate, String queryLower)
	{
		if (queryLower.isEmpty()) return 1;
		return candidate.tier() > 0 ? candidate.tier() : candidate.wordMatches();
	}

	/**
	 * Pure relevance scoring. Returns {tier, wordMatches}: tier 3 = exact name/title, 2 = name/title contains,
	 * 1 = datasource/comment contains, 0 = none of these. wordMatches counts the query words (3+ characters) found
	 * in the name or title, and is only computed when the tier is 0. A blank query returns {0, 0}.
	 *
	 * @param queryLower the lower-cased, trimmed query
	 */
	static int[] score(String name, String title, String ds, String comment, String queryLower)
	{
		if (queryLower == null || queryLower.isEmpty()) return new int[] { 0, 0 };
		String n = name != null ? name.toLowerCase() : "";
		String t = title != null ? title.toLowerCase() : "";
		String d = ds != null ? ds.toLowerCase() : "";
		String c = comment != null ? comment.toLowerCase() : "";
		if (n.equals(queryLower) || t.equals(queryLower)) return new int[] { 3, 0 };
		if (n.contains(queryLower) || t.contains(queryLower)) return new int[] { 2, 0 };
		if (d.contains(queryLower) || c.contains(queryLower)) return new int[] { 1, 0 };
		// also try matching individual words from the query
		String[] words = queryLower.split("\\s+");
		int wordMatches = 0;
		for (String word : words)
		{
			if (word.length() < 3) continue;
			if (n.contains(word) || t.contains(word)) wordMatches++;
		}
		return new int[] { 0, wordMatches };
	}

	static String getFormLayoutType(Form form)
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

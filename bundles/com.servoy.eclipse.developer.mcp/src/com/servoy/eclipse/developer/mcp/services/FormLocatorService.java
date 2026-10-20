/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsFormatter.FormHeader;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService.FormCandidate;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;

/**
 * Resolves a described UI location (the user's wording) to a form, how to reach it from the main form and its
 * interactive elements, in one call. Never guesses silently: when several forms are equally good matches the
 * result is {@code ambiguous} and carries no path and no elements.
 * <p>
 * The decision itself ({@link #resolve}) is pure and has no Servoy/Eclipse types in its signature.
 * </p>
 */
@Creatable
public class FormLocatorService
{
	public static final String STATUS_RESOLVED = "resolved";
	public static final String STATUS_AMBIGUOUS = "ambiguous";
	public static final String STATUS_NOT_FOUND = "notFound";

	/** Maximum number of candidates returned to the caller. */
	public static final int MAX_CANDIDATES = 5;

	static final String ERR_QUERY_REQUIRED = "Error: query parameter is required";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One ranked form, with the structured relevance it was ranked on. */
	public record ScoredName(String name, String title, String module, String dataSource, String layout, int tier, int wordMatches)
	{
		public ScoredName(String name, int tier, int wordMatches)
		{
			this(name, null, null, null, null, tier, wordMatches);
		}

		boolean sameScore(ScoredName other)
		{
			return tier == other.tier && wordMatches == other.wordMatches;
		}
	}

	/** The outcome of {@link #resolve}: the status and the candidates to show (at most {@value #MAX_CANDIDATES}). */
	public record Resolution(String status, List<ScoredName> candidates)
	{
		public ScoredName resolvedForm()
		{
			return STATUS_RESOLVED.equals(status) ? candidates.get(0) : null;
		}
	}

	private final ServoySolutionService solutionService;
	private final FormNavigationGraphService navigationService;
	private final FormInteractiveElementsService elementsService;

	public FormLocatorService()
	{
		this(new ServoySolutionService(), new FormNavigationGraphService(), new FormInteractiveElementsService());
	}

	FormLocatorService(ServoySolutionService solutionService, FormNavigationGraphService navigationService,
		FormInteractiveElementsService elementsService)
	{
		this.solutionService = solutionService;
		this.navigationService = navigationService;
		this.elementsService = elementsService;
	}

	/**
	 * Pure decision on an already ranked list (best first).
	 * <ul>
	 * <li>empty: {@code notFound}</li>
	 * <li>one entry, or the best (tier, wordMatches) strictly greater than the second best: {@code resolved}, the
	 * candidates are the top {@value #MAX_CANDIDATES} in rank order</li>
	 * <li>two or more entries share the top (tier, wordMatches): {@code ambiguous}, the candidates are exactly the tied
	 * entries (capped at {@value #MAX_CANDIDATES}) in rank order</li>
	 * </ul>
	 */
	public static Resolution resolve(List<ScoredName> ranked)
	{
		if (ranked == null || ranked.isEmpty()) return new Resolution(STATUS_NOT_FOUND, List.of());

		ScoredName best = ranked.get(0);
		List<ScoredName> tied = new ArrayList<>();
		for (ScoredName candidate : ranked)
		{
			if (!candidate.sameScore(best)) break;
			tied.add(candidate);
		}
		if (tied.size() > 1)
		{
			return new Resolution(STATUS_AMBIGUOUS, List.copyOf(tied.subList(0, Math.min(tied.size(), MAX_CANDIDATES))));
		}
		return new Resolution(STATUS_RESOLVED, List.copyOf(ranked.subList(0, Math.min(ranked.size(), MAX_CANDIDATES))));
	}

	/**
	 * Locates the form described by {@code query}.
	 *
	 * @return the JSON payload, or a plain {@code Error: ...} string
	 */
	public String locate(String query, boolean includeElements) throws com.fasterxml.jackson.core.JsonProcessingException
	{
		if (query == null || query.isBlank()) return ERR_QUERY_REQUIRED;

		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null) return ServoySolutionService.ERR_NO_ACTIVE_SOLUTION;

		List<ScoredName> ranked = new ArrayList<>();
		for (FormCandidate candidate : solutionService.rankForms(query))
		{
			ranked.add(toScoredName(candidate));
		}
		Resolution resolution = resolve(ranked);

		ObjectNode result = MAPPER.createObjectNode();
		result.put("status", resolution.status());
		result.put("query", query.trim());
		result.set("candidates", candidatesNode(resolution.candidates()));

		if (STATUS_NOT_FOUND.equals(resolution.status()))
		{
			result.put("hint", "No form matches the query. Widen or shorten the query, or call findForm with a datasource name.");
		}
		else if (STATUS_AMBIGUOUS.equals(resolution.status()))
		{
			result.put("hint", "Several forms match equally well. Ask the user which one is meant, or call getNavigationPath / " +
				"getFormInteractiveElements with one of the candidate names.");
		}
		else
		{
			ScoredName form = resolution.resolvedForm();
			result.set("form", formNode(form));

			NavigationGraph graph = navigationService.buildFullGraph();
			String mainForm = navigationService.getMainFormName();
			if (mainForm != null) result.put("mainForm", mainForm);
			else result.putNull("mainForm");
			result.put("reachability", locatedReachability(graph, mainForm, form.name()));
			ArrayNode path = result.putArray("pathTo");
			if (mainForm != null)
			{
				for (NavigationEdge step : graph.findPath(mainForm, form.name()))
				{
					path.add(FormNavigationGraphService.edgeToJson(MAPPER, step));
				}
			}
			if (includeElements)
			{
				ObjectNode elements = elementsService.getElementsNode(form.name(), false, FormInteractiveElementsFormatter.DEFAULT_MAX_ELEMENTS);
				if (elements != null) result.set("elements", elements);
				else result.putArray("notes").add("elements unavailable: form '" + form.name() + "' was not found in the editing flattened solution");
			}
		}
		return MAPPER.writeValueAsString(result);
	}

	/**
	 * Reachability of a form that was resolved from the solution model. The graph only knows forms that appear in a
	 * navigation edge, so {@code unknownForm} from the graph means "nothing links to it" here, not "the name is wrong":
	 * the form exists, hence it is reported as {@code unreachable}.
	 */
	public static String locatedReachability(NavigationGraph graph, String mainForm, String formName)
	{
		String reachability = FormNavigationGraphService.describeReachability(graph, mainForm, formName);
		return FormNavigationGraphService.REACHABILITY_UNKNOWN_FORM.equals(reachability) ? FormNavigationGraphService.REACHABILITY_UNREACHABLE : reachability;
	}

	private static ScoredName toScoredName(FormCandidate candidate)
	{
		Form form = candidate.form();
		FormHeader header = FormInteractiveElementsService.header(form);
		return new ScoredName(form.getName(), header.title(), candidate.module(), header.dataSource(), header.layout(), candidate.tier(),
			candidate.wordMatches());
	}

	private static ArrayNode candidatesNode(List<ScoredName> candidates)
	{
		ArrayNode array = MAPPER.createArrayNode();
		for (ScoredName candidate : candidates)
		{
			ObjectNode node = formNode(candidate);
			node.put("tier", candidate.tier());
			array.add(node);
		}
		return array;
	}

	private static ObjectNode formNode(ScoredName form)
	{
		ObjectNode node = MAPPER.createObjectNode();
		node.put("name", form.name());
		putOrNull(node, "title", form.title());
		putOrNull(node, "module", form.module());
		putOrNull(node, "dataSource", form.dataSource());
		putOrNull(node, "layout", form.layout());
		return node;
	}

	private static void putOrNull(ObjectNode node, String field, String value)
	{
		if (value == null) node.putNull(field);
		else node.put(field, value);
	}
}

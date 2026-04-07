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
package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.knowledgebase.KnowledgeBaseManager;
import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService.SearchResult;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.tools.utility.KnowledgeToolsHelper.CategoryMatch;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IKnowledgeTool
{
	@Tool("Retrieves Servoy documentation and tools for specified action queries. " +
		"Provide action phrases like 'create form', 'add buttons', 'create relation' as either " +
		"a JSON array or comma-separated string. Each query should be a simple 2-4 word phrase.")
	default String getKnowledge(
		@P(value = "Action queries - either JSON array [\"query1\", \"query2\"] or comma-separated \"query1, query2\"", required = true) String queries)
	{
		List<String> queryList = KnowledgeToolsHelper.getInstance().parseQueries(queries);

		if (queryList.isEmpty())
		{
			return "Error: queries parameter is required. Provide action phrases like 'create form', 'add buttons', etc.";
		}

		try
		{
			ServoyEmbeddingService embeddingService = KnowledgeBaseManager.getEmbeddingService();

			if (!embeddingService.hasEmbeddings())
			{
				ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
				if (activeProject != null)
				{
					try
					{
						KnowledgeBaseManager.loadKnowledgeBasesForSolution(activeProject);
					}
					catch (Exception e)
					{
						ServoyLog.logError("[IKnowledgeTool] Failed to load knowledge base: " + e.getMessage(), e);
						return "Error: Failed to load knowledge base. " + e.getMessage();
					}
				}
				else
				{
					return "Error: No active Servoy solution. Please activate a solution first.";
				}
			}

			Map<String, CategoryMatch> categoryMatches = new LinkedHashMap<>();

			for (String query : queryList)
			{
				List<SearchResult> results = embeddingService.search(query, 3);
				for (SearchResult result : results)
				{
					String intent = result.metadata.get("intent");
					if (intent != null && !intent.equals("PASS_THROUGH"))
					{
						if (!categoryMatches.containsKey(intent))
						{
							categoryMatches.put(intent, new CategoryMatch(intent, query, result.score));
						}
						else
						{
							CategoryMatch existing = categoryMatches.get(intent);
							if (result.score > existing.bestScore)
							{
								existing.bestScore = result.score;
								existing.matchedQuery = query;
							}
						}
					}
				}
			}

			StringBuilder response = new StringBuilder();
			response.append("=== SERVOY KNOWLEDGE FOR YOUR ACTION LIST ===\n\n");
			response.append("Analyzed ").append(queryList.size()).append(" action queries.\n");
			response.append("Found ").append(categoryMatches.size()).append(" relevant Servoy categories.\n\n");

			if (categoryMatches.isEmpty())
			{
				response.append("[!!! NO MATCHING SERVOY CATEGORIES FOUND !!!]\n\n");
				response.append("Your queries:\n");
				for (String query : queryList) response.append("  - \"").append(query).append("\"\n");
				response.append("\nNo specific Servoy tools or documentation found for these queries.\n\n");
				response.append("IMPORTANT GUIDANCE:\n");
				response.append("- If this is a GENERAL PROGRAMMING question (JavaScript, algorithms, debugging, etc.),\n");
				response.append("  you can answer using your general knowledge without Servoy-specific tools.\n");
				response.append("- If this requires SERVOY-SPECIFIC features (forms, relations, valueLists, etc.),\n");
				response.append("  but we don't have tools yet, inform the user this feature is not yet available.\n");
				response.append("- If this is COMPLETELY UNRELATED to programming/Servoy (weather, cooking, etc.),\n");
				response.append("  politely inform the user you're specialized for Servoy development.\n");
			}
			else
			{
				response.append("=============================================================================\n");
				response.append("=== AVAILABLE TOOLS & KNOWLEDGE ===\n");
				response.append("=============================================================================\n\n");

				String projectName = null;
				try
				{
					ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
					if (activeProject != null) projectName = activeProject.getProject().getName();
				}
				catch (Exception e)
				{
					// ignore
				}

				int categoryNum = 1;
				for (CategoryMatch match : categoryMatches.values())
				{
					response.append("--- Category ").append(categoryNum++).append(": ").append(match.category).append(" ---\n");
					response.append("Matched query: \"").append(match.matchedQuery).append("\"\n");
					response.append("Confidence: ").append(String.format("%.1f%%", match.bestScore * 100)).append("\n\n");

					String rules = RulesCache.getRules(match.category, projectName);
					if (rules != null && !rules.isEmpty())
					{
						response.append(rules).append("\n\n");
					}
					else
					{
						response.append("[NOT YET IMPLEMENTED]\n\n");
						response.append("This category was matched by similarity search, but tools for ").append(match.category)
							.append(" are not yet available.\n");
						response.append("This feature is planned for future implementation.\n\n");
						response.append("For now, inform the user that this functionality is coming soon.\n\n");
					}

					response.append("=============================================================================\n\n");
				}
			}

			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[IKnowledgeTool] Error in getKnowledge: " + e.getMessage(), e);
			return "Error processing queries: " + e.getMessage();
		}
	}
}

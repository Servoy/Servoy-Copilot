package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.knowledgebase.KnowledgeBaseManager;
import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService.SearchResult;
import com.servoy.eclipse.model.nature.ServoyProject;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Knowledge retrieval tools for AI assistant.
 * Provides access to Servoy documentation, best practices, and tool instructions
 * via semantic search and knowledge base lookups.
 * 
 * Migrated from knowledgebase.mcp KnowledgeToolHandler.
 */
public class KnowledgeTools
{
	private static final ILog logger = ILog.of(KnowledgeTools.class);

	/**
	 * Retrieves Servoy documentation and tool instructions for specified action queries.
	 * 
	 * This tool performs semantic search across the Servoy knowledge base to find
	 * relevant documentation, best practices, and available tools for the requested actions.
	 * 
	 * Each query should be a simple 2-4 word phrase describing one action type,
	 * such as "create form", "add buttons", "create relation", "style component", etc.
	 * 
	 * The tool will:
	 * 1. Perform similarity search for each query
	 * 2. Match queries to known Servoy categories
	 * 3. Return detailed documentation and tool instructions for matched categories
	 * 4. Indicate when functionality is not yet implemented
	 * 
	 * @param queries Action phrases to look up. Can be a JSON array string like ["create form", "add buttons"]
	 *                or a comma-separated string like "create form, add buttons"
	 * @return Comprehensive knowledge base response with tools and instructions
	 */
	@Tool("Retrieves Servoy documentation and tools for specified action queries. " +
		"Provide action phrases like 'create form', 'add buttons', 'create relation' as either " +
		"a JSON array or comma-separated string. Each query should be a simple 2-4 word phrase.")
	public String getKnowledge(
		@P(value = "Action queries - either JSON array [\"query1\", \"query2\"] or comma-separated \"query1, query2\"", 
		   required = true) String queries)
	{
		// Parse queries from input
		List<String> queryList = parseQueries(queries);

		if (queryList.isEmpty())
		{
			return "Error: queries parameter is required. Provide action phrases like 'create form', 'add buttons', etc.";
		}

		try
		{
			// Get embedding service
			ServoyEmbeddingService embeddingService = KnowledgeBaseManager.getEmbeddingService();

			// Track matched categories and their contexts
			Map<String, CategoryMatch> categoryMatches = new LinkedHashMap<>();

			// For each query, do similarity search
			for (String query : queryList)
			{
				// Search with top 3 results to allow multiple category matches
				List<SearchResult> results = embeddingService.search(query, 3);

				for (SearchResult result : results)
				{
					String intent = result.metadata.get("intent");
					if (intent != null && !intent.equals("PASS_THROUGH"))
					{
						// Track this category
						if (!categoryMatches.containsKey(intent))
						{
							categoryMatches.put(intent, new CategoryMatch(intent, query, result.score));
						}
						else
						{
							// Update if better score
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

			// Build response with matched categories
			StringBuilder response = new StringBuilder();
			response.append("=== SERVOY KNOWLEDGE FOR YOUR ACTION LIST ===\n\n");
			response.append("Analyzed ").append(queryList.size()).append(" action queries.\n");
			response.append("Found ").append(categoryMatches.size()).append(" relevant Servoy categories.\n\n");

			if (categoryMatches.isEmpty())
			{
				response.append("[!!! NO MATCHING SERVOY CATEGORIES FOUND !!!]\n\n");
				response.append("Your queries:\n");
				for (String query : queryList)
				{
					response.append("  - \"").append(query).append("\"\n");
				}
				response.append("\nThese don't match any known Servoy categories.\n");
				response.append("Either this is not a Servoy-related request, or you need to rephrase your action queries.\n");
			}
			else
			{
				response.append("=============================================================================\n");
				response.append("=== AVAILABLE TOOLS & KNOWLEDGE ===\n");
				response.append("=============================================================================\n\n");

				// Get active project name for variable substitution
				String projectName = null;
				try
				{
					ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
					if (activeProject != null)
					{
						projectName = activeProject.getProject().getName();
					}
				}
				catch (Exception e)
				{
					// Ignore - will use null project name
				}

				int categoryNum = 1;
				for (CategoryMatch match : categoryMatches.values())
				{
					response.append("--- Category ").append(categoryNum++).append(": ").append(match.category).append(" ---\n");
					response.append("Matched query: \"").append(match.matchedQuery).append("\"\n");
					response.append("Confidence: ").append(String.format("%.1f%%", match.bestScore * 100)).append("\n\n");

					// Load actual rules content from RulesCache with project name substitution
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
			logger.error("Error in getKnowledge: " + e.getMessage(), e);
			return "Error processing queries: " + e.getMessage();
		}
	}

	/**
	 * Parse queries from various input formats.
	 * Supports:
	 * - JSON array: ["query1", "query2"]
	 * - Comma-separated: "query1, query2"
	 * - Single query: "query1"
	 */
	private List<String> parseQueries(String queriesInput)
	{
		List<String> result = new ArrayList<>();

		if (queriesInput == null || queriesInput.isBlank())
		{
			return result;
		}

		queriesInput = queriesInput.trim();

		// Try to parse as JSON array
		if (queriesInput.startsWith("[") && queriesInput.endsWith("]"))
		{
			try
			{
				ObjectMapper mapper = new ObjectMapper();
				String[] parsed = mapper.readValue(queriesInput, String[].class);
				for (String query : parsed)
				{
					if (query != null && !query.isBlank())
					{
						result.add(query.trim());
					}
				}
				return result;
			}
			catch (Exception e)
			{
				// Fall through to comma-separated parsing
			}
		}

		// Parse as comma-separated or single value
		String[] split = queriesInput.split(",");
		for (String query : split)
		{
			String trimmed = query.trim();
			if (!trimmed.isEmpty())
			{
				result.add(trimmed);
			}
		}

		return result;
	}

	/**
	 * Helper class to track category matches during knowledge retrieval.
	 */
	private static class CategoryMatch
	{
		String category;
		String matchedQuery;
		double bestScore;

		CategoryMatch(String category, String matchedQuery, double bestScore)
		{
			this.category = category;
			this.matchedQuery = matchedQuery;
			this.bestScore = bestScore;
		}
	}
}

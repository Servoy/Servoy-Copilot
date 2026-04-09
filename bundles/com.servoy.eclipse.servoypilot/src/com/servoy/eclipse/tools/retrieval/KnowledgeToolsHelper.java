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
package com.servoy.eclipse.tools.retrieval;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Singleton helper providing shared logic for utility knowledge tool interfaces.
 */
public class KnowledgeToolsHelper
{
	private static final KnowledgeToolsHelper INSTANCE = new KnowledgeToolsHelper();

	private KnowledgeToolsHelper()
	{
	}

	public static KnowledgeToolsHelper getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Tracks a category match during knowledge retrieval.
	 */
	public static class CategoryMatch
	{
		public String category;
		public String matchedQuery;
		public double bestScore;

		public CategoryMatch(String category, String matchedQuery, double bestScore)
		{
			this.category = category;
			this.matchedQuery = matchedQuery;
			this.bestScore = bestScore;
		}
	}

	public List<String> parseQueries(String queriesInput)
	{
		List<String> result = new ArrayList<>();

		if (queriesInput == null || queriesInput.isBlank())
		{
			return result;
		}

		queriesInput = queriesInput.trim();

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
				// fall through to comma-separated parsing
			}
		}

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
}

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
package com.servoy.eclipse.servoypilot.ai;

import java.util.List;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.googleai.GoogleAiGeminiModelCatalog;
import dev.langchain4j.model.openai.OpenAiModelCatalog;

public class AIModelTools
{

	private static final ILog logger = ILog.of(AIModelTools.class);

	private static List<ModelDescription> cachedOpenAIModels = null;

	private static List<ModelDescription> cachedGeminiModels = null;

	public static List<ModelDescription> getOpenAIModels(String apiKey)
	{
		if (cachedOpenAIModels == null)
		{
			try
			{
				cachedOpenAIModels = OpenAiModelCatalog.builder().apiKey(apiKey).build().listModels();

			}
			catch (Exception e)
			{
				logger.error("Error fetching OpenAI models", e);
			}
		}
		if (cachedOpenAIModels != null)
		{
			return cachedOpenAIModels;
		}
		return List.of();
	}

	public static List<ModelDescription> getGeminiModels(String apiKey)
	{
		if (cachedGeminiModels == null)
		{
			try
			{
				cachedGeminiModels = GoogleAiGeminiModelCatalog.builder().apiKey(apiKey).build().listModels();

			}
			catch (Exception e)
			{
				logger.error("Error fetching Gemini models", e);
			}
		}
		if (cachedGeminiModels != null)
		{
			return cachedGeminiModels;
		}
		return List.of();
	}
}

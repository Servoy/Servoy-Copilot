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
				cachedOpenAIModels = OpenAiModelCatalog.builder().apiKey(apiKey).build().listModels().stream().filter(AIModelTools::isOpenAiChatCompatible)
					.toList();

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

	// Helper method based on 2026 naming standards
	private static boolean isOpenAiChatCompatible(ModelDescription description)
	{
		String modelId = description.name().toLowerCase();
		// Flagship chat models
		if (modelId.startsWith("gpt-") || modelId.startsWith("o1") || modelId.startsWith("o3"))
		{
			// Exclude the 'instruct' and 'codex' variants which use the old completion API
			return !modelId.contains("-instruct") && !modelId.contains("-codex");
		}

		// Fine-tuned chat models usually start with 'ft:gpt-'
		if (modelId.startsWith("ft:gpt-"))
		{
			return true;
		}

		return false;
	}

	public static List<ModelDescription> getGeminiModels(String apiKey)
	{
		if (cachedGeminiModels == null)
		{
			try
			{
				cachedGeminiModels = GoogleAiGeminiModelCatalog.builder().apiKey(apiKey).build().listModels().stream()
					.filter(AIModelTools::isGeminiChatCompatible).toList();

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

	private static boolean isGeminiChatCompatible(ModelDescription model)
	{
		String id = model.name().toLowerCase();
		String display = (model.displayName() != null) ? model.displayName().toLowerCase() : "";

		// 1. REMOVE IMAGE GENERATORS (Nano Banana)
		if (id.contains("-image") || display.contains("banana") || display.contains("image"))
		{
			return false;
		}

		// 2. REMOVE TEXT-TO-SPEECH (TTS)
		if (id.contains("-tts"))
		{
			return false;
		}

		// 3. REMOVE NATIVE AUDIO (Gemini Live Models)
		// These expect WebSocket/Streaming audio and don't play nice with standard Chat interfaces
		if (id.contains("-audio") || id.contains("-live"))
		{
			return false;
		}

		// 4. REMOVE EMBEDDINGS
		if (id.contains("embedding"))
		{
			return false;
		}

		// 5. REMOVE GEMMA (dont support the tooling)
		if (id.startsWith("gemma"))
		{
			return false;
		}

		// 6. WHITELIST: Standard reasoning/chat models
		// We want Gemini Pro, Flash, and the "it" (instruction) version of Gemma
		return id.contains("pro") || id.contains("flash") || id.contains("-it");
	}
}

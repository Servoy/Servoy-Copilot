package com.servoy.eclipse.servoypilot.ai;

import java.util.List;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.googleai.GoogleAiGeminiModelCatalog;
import dev.langchain4j.model.openai.OpenAiModelCatalog;

public class AIModelTools {

	private static final ILog logger = ILog.of(AIModelTools.class);

	private static List<ModelDescription> cachedOpenAIModels = null;

	private static List<ModelDescription> cachedGeminiModels = null;

	public static List<ModelDescription> getOpenAIModels(String apiKey) {
		if (cachedOpenAIModels == null) {
			try {
				cachedOpenAIModels = OpenAiModelCatalog.builder().apiKey(apiKey).build().listModels();

			} catch (Exception e) {
				logger.error("Error fetching OpenAI models", e);
			}
		}
		if (cachedOpenAIModels != null) {
			return cachedOpenAIModels;
		}
		return List.of();
	}

	public static List<ModelDescription> getGeminiModels(String apiKey) {
		if (cachedGeminiModels == null) {
			try {
				cachedGeminiModels = GoogleAiGeminiModelCatalog.builder().apiKey(apiKey).build().listModels();

			} catch (Exception e) {
				logger.error("Error fetching Gemini models", e);
			}
		}
		if (cachedGeminiModels != null) {
			return cachedGeminiModels;
		}
		return List.of();
	}
}

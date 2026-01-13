package com.servoy.eclipse.servoypilot.ai;

import java.util.List;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.googleai.GoogleAiGeminiModelCatalog;
import dev.langchain4j.model.openai.OpenAiModelCatalog;

public class AIModelTools {
	
	private static final ILog logger = ILog.of(AIModelTools.class);
	
	public static List<ModelDescription> getOpenAIModels(String apiKey) {
		try {
			return OpenAiModelCatalog.builder().apiKey(apiKey).build().listModels();
		} catch (Exception e) {
			logger.error("Error fetching OpenAI models", e);
		}
		return List.of();
	}
	
	public static List<ModelDescription> getGeminiModels(String apiKey) {
		try {
			return GoogleAiGeminiModelCatalog.builder().apiKey(apiKey).build().listModels();
		} catch (Exception e) {
			logger.error("Error fetching Gemini models", e);
		}
		return List.of();
	}
}

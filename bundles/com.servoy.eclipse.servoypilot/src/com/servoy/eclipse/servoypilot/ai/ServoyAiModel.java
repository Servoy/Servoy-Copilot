package com.servoy.eclipse.servoypilot.ai;

import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;

public class ServoyAiModel {

	private final Assistant assistant;

	public ServoyAiModel(AiConfiguration conf) {
		assistant = switch (conf.getSelectedModel()) {
			case OPENAI -> createAiServices(createOpenAIModel(conf));
			case GEMINI -> createAiServices(createGeminiModel(conf));
		};
	}

	private OpenAiStreamingChatModel createOpenAIModel(AiConfiguration conf) {
		return OpenAiStreamingChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build();
	}


	private GoogleAiGeminiStreamingChatModel createGeminiModel(AiConfiguration conf) {
		return GoogleAiGeminiStreamingChatModel.builder().apiKey(conf.getApiKey()).modelName(conf.getModel()).build();
	}
	
	private Assistant createAiServices(StreamingChatModel model) {
		AiServices<Assistant> builder = AiServices.builder(Assistant.class);
		builder.streamingChatModel(model);
//		builder.tools(null); // TODO add tools
//		builder.systemMessageProvider(null); // TODO add system message provider
		return builder.build();
	}

	public Assistant getAssistant() {
		return assistant;
	}
}

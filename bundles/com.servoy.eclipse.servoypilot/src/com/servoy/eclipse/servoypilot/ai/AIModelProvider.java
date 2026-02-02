package com.servoy.eclipse.servoypilot.ai;

import com.servoy.eclipse.core.ai.ChatModel;
import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

public class AIModelProvider implements com.servoy.eclipse.core.ai.AIModelProvider
{
	@Override
	public ChatModel createChatModel(String systemPrompt)
	{
		AiConfiguration conf = new AiConfiguration();
		return switch (conf.getSelectedModel())
		{
			case OPENAI -> createModel(createOpenAIModel(conf), systemPrompt);
			case GEMINI -> createModel(createGeminiModel(conf), systemPrompt);
			case NONE -> null;
		};
	}

	private ChatModel createModel(dev.langchain4j.model.chat.ChatModel model, String systemPrompt)
	{
		AiServices<ChatModel> builder = AiServices.builder(ChatModel.class);
		builder.chatModel(model);
		builder.systemMessageProvider(memoryId -> systemPrompt);
		return builder.build();
	}


	private OpenAiChatModel createOpenAIModel(AiConfiguration conf)
	{
		return OpenAiChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build(); // hard coded once per chat model, completion must be fast
	}

	private GoogleAiGeminiChatModel createGeminiModel(AiConfiguration conf)
	{
		return GoogleAiGeminiChatModel.builder().apiKey(conf.getApiKey()).modelName(conf.getModel()).build(); // hard coded once per chat model, completion must be fast
	}

}

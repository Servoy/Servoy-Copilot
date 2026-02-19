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

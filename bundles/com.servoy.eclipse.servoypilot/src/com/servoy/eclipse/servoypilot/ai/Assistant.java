package com.servoy.eclipse.servoypilot.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;

public interface Assistant {
	TokenStream chat(UserMessage userMessage);
}
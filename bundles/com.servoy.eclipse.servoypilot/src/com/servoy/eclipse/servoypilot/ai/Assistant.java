package com.servoy.eclipse.servoypilot.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface Assistant
{
	TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
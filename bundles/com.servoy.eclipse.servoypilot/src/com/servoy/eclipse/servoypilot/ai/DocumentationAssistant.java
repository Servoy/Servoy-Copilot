package com.servoy.eclipse.servoypilot.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface DocumentationAssistant
{
	TokenStream generateDocumentation(@MemoryId String memoryId, @UserMessage String userMessage);
}

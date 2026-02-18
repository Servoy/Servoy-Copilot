package com.servoy.eclipse.servoypilot.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * Common interface for all conversational assistants in the chat view.
 * Provides unified operations for assistant management and interaction.
 */
public interface IAssistant
{
	/**
	 * Execute a request with the assistant (send message and get streaming response)
	 * 
	 * @param memoryId the memory ID (typically solution name + assistant suffix)
	 * @param request the user's request or auto-generated prompt
	 * @return TokenStream for streaming response
	 */
	TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request);

	/**
	 * Clear the conversation memory for a specific memory ID
	 * 
	 * @param memoryId the memory ID to clear
	 */
	default void clearMemory(String memoryId)
	{
		// Default: no-op (assistants can override if they manage memory)
	}

	/**
	 * Get the assistant type
	 * 
	 * @return the assistant type enum value
	 */
	AssistantType getType();

	/**
	 * Get the display name shown in the UI
	 * 
	 * @return human-readable assistant name
	 */
	String getDisplayName();
}

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

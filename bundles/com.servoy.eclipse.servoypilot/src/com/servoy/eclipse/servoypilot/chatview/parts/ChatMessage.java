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
package com.servoy.eclipse.servoypilot.chatview.parts;

import dev.langchain4j.data.message.Content;

/**
 * Represents a chat message with an ID, role, number of tokens, and the message
 * content.
 */
public class ChatMessage
{

	private final String id;

	private final String role;

	private Content content;

	/**
	 * Constructs a ChatMessage with the given ID and role.
	 * 
	 * @param id   The unique identifier for the chat message
	 * @param role The role associated with the chat message (e.g., "user",
	 *             "assistant")
	 */
	public ChatMessage(String id, String role, Content content)
	{
		this.id = id;
		this.role = role;
		this.content = content;
	}

	/**
	 * Retrieves the message content.
	 * 
	 * @return The message content
	 */
	public Content getContent()
	{
		return content;
	}


	/**
	 * Retrieves the unique identifier.
	 * 
	 * @return The ID of the chat message
	 */
	public String getId()
	{
		return id;
	}

	/**
	 * Retrieves the role associated with the chat message.
	 * 
	 * @return The role of the chat message
	 */
	public String getRole()
	{
		return role;
	}

	protected void setContent(Content content)
	{
		this.content = content;
	}

}

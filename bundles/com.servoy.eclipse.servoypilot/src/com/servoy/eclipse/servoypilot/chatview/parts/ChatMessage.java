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

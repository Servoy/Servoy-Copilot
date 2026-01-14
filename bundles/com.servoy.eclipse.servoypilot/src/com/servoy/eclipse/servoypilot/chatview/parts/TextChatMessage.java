package com.servoy.eclipse.servoypilot.chatview.parts;

import dev.langchain4j.data.message.TextContent;

public class TextChatMessage extends ChatMessage
{

	public TextChatMessage(String id, String role)
	{
		super(id, role, null);
	}

	public TextChatMessage(String id, String role, String content)
	{
		super(id, role, TextContent.from(content));
	}

	@Override
	public TextContent getContent()
	{
		return (TextContent)super.getContent();
	}


	public void appendContent(String partial)
	{
		TextContent content = getContent();
		if (content == null)
		{
			setContent(partial);
			return;
		}
		setContent(TextContent.from(getContent().text() + partial));
	}

	public void setContent(String content)
	{
		setContent(TextContent.from(content));
	}
}

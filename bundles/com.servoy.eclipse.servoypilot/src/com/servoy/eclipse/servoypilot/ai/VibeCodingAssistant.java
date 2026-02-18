package com.servoy.eclipse.servoypilot.ai;

public interface VibeCodingAssistant extends IAssistant
{
	@Override
	default AssistantType getType()
	{
		return AssistantType.CHAT;
	}

	@Override
	default String getDisplayName()
	{
		return AssistantType.CHAT.getDisplayName();
	}
}

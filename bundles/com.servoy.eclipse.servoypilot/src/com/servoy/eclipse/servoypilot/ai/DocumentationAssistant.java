package com.servoy.eclipse.servoypilot.ai;

public interface DocumentationAssistant extends IAssistant
{
	@Override
	default AssistantType getType()
	{
		return AssistantType.DOCUMENTATION;
	}

	@Override
	default String getDisplayName()
	{
		return AssistantType.DOCUMENTATION.getDisplayName();
	}
}
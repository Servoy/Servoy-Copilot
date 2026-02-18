package com.servoy.eclipse.servoypilot.ai;

public enum AssistantType
{
	CHAT("VibeCoding Assistant", "-chat"),
	DOCUMENTATION("Documentation Assistant", "-documentation");

	private final String displayName;
	private final String memorySuffix;

	AssistantType(String displayName, String memorySuffix)
	{
		this.displayName = displayName;
		this.memorySuffix = memorySuffix;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getMemorySuffix()
	{
		return memorySuffix;
	}

	public static AssistantType fromIndex(int index)
	{
		if (index >= 0 && index < values().length)
		{
			return values()[index];
		}
		return CHAT; // Default
	}
}

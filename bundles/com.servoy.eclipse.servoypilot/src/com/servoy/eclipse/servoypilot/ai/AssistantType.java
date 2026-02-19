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

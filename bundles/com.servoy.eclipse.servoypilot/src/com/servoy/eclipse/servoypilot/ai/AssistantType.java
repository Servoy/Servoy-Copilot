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

import com.servoy.eclipse.servoypilot.Activator;

public enum AssistantType
{
	DEVELOPMENT("Development Assistant", "-devs"),
	DOCUMENTATION("Documentation Assistant", "-documentation"),
	QUICKFIX("QuickFix Assistant", "-quickfix"),
	EXPLAIN("Explain Assistant", "-explain"),
	REVIEW("Review Assistant", "-review"),
	UNIT_TEST("Unit Test Assistant", "-unittest"),
	QUERY_BUILDER("Query Builder Assistant", "-querybuilder");

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
		return DEVELOPMENT; // Default
	}

	public IAssistant getModel()
	{
		switch (this)
		{
			case DEVELOPMENT :
				return Activator.getDefault().getServoyAiModel().getDevelopmentAssistant();
			case DOCUMENTATION :
				return Activator.getDefault().getServoyAiModel().getDocumentationAssistant();
			case QUICKFIX :
				return Activator.getDefault().getServoyAiModel().getQuickFixAssistant();
			case EXPLAIN :
				return Activator.getDefault().getServoyAiModel().getExplainAssistant();
			case REVIEW :
				return Activator.getDefault().getServoyAiModel().getReviewAssistant();
			case UNIT_TEST :
				return Activator.getDefault().getServoyAiModel().getUnitTestAssistant();
			case QUERY_BUILDER :
				return Activator.getDefault().getServoyAiModel().getQueryBuilderAssistant();
		}
		return null;
	}
}
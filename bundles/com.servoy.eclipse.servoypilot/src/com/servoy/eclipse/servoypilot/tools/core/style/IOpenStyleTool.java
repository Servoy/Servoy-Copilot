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
package com.servoy.eclipse.servoypilot.tools.core.style;

import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IOpenStyleTool
{
	@Tool("Adds or updates a CSS class in a LESS file. Context-aware: style added to current target.")
	default String openStyle(
		@P(value = "CSS class name (without dot)", required = true) String className,
		@P(value = "CSS content (rules)", required = true) String cssContent,
		@P(value = "LESS file name (defaults to <solution-name>.less)", required = false) String lessFileName)
	{
		if (className != null && !className.trim().isEmpty() && cssContent != null && !cssContent.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("openStyle",
				() -> StyleToolsHelper.getInstance().addOrUpdateStyleImpl(className, cssContent, lessFileName));
		}

		if (className == null || className.trim().isEmpty()) return "Error: className parameter is required";
		return "Error: cssContent parameter is required";
	}
}

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

public interface IGetStylesTool
{
	@Tool("Lists all CSS class names in a LESS file. Optional scope: 'current' for context only, 'all' for solution + modules.")
	default String getStyles(
		@P(value = "LESS file name (defaults to <solution-name>.less)", required = false) String lessFileName,
		@P(value = "Scope: 'current' or 'all' (default 'current')", required = false) String scope)
	{
		return UIThreadHelper.syncExec("getStyles",
			() -> StyleToolsHelper.getInstance().listStylesImpl(lessFileName, scope != null ? scope : "current"));
	}
}

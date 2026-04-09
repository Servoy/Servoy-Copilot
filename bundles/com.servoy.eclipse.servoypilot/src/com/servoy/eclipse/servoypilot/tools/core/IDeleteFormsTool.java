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
package com.servoy.eclipse.servoypilot.tools.core;

import java.util.List;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IDeleteFormsTool
{
	@Tool("Deletes one or more existing forms. Requires approval if form not in current context.")
	default String deleteForms(
		@P(value = "Array of form names to delete", required = true) List<String> names)
	{
		if (names != null && !names.isEmpty())
		{
			return UIThreadHelper.syncExec("deleteForms",
				() -> CoreToolsHelper.getInstance().deleteFormsImpl(names));
		}

		return "Error: names parameter is required (array of form names)";
	}
}

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
import java.util.Map;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IOpenValueListTool
{
	@Tool("Opens an existing valuelist or creates a new valuelist. Supports 4 types: CUSTOM, DATABASE (table), DATABASE (related), GLOBAL_METHOD. " +
		"[CONTEXT-AWARE for CREATE] When creating a new valuelist, it will be created in the current target (active solution or module). " +
		"Use getContext to check where it will be created, setContext to change target location.")
	default String openValueList(
		@P(value = "ValueList name", required = true) String name,
		@P(value = "Custom values array (for CUSTOM type)", required = false) List<String> customValues,
		@P(value = "DataSource (format: 'server_name/table_name' or 'db:/server_name/table_name' for DATABASE type)", required = false) String dataSource,
		@P(value = "Relation name (for DATABASE/RELATED type)", required = false) String relationName,
		@P(value = "Global method name (for GLOBAL_METHOD type, e.g. 'scopes.globals.getCountries')", required = false) String globalMethod,
		@P(value = "Display column name", required = false) String displayColumn,
		@P(value = "Return column name", required = false) String returnColumn,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties)
	{
		if (name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("openValueList",
				() -> CoreToolsHelper.getInstance().openOrCreateValueList(name, customValues, dataSource,
					relationName, globalMethod, displayColumn, returnColumn, properties));
		}

		return "Error: name parameter is required";
	}
}

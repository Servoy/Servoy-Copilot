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
package com.servoy.eclipse.servoypilot.tools.core.relation;

import java.util.Map;

import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IOpenRelationTool
{
	@Tool("Opens an existing relation or creates a new relation between two tables. Context-aware: when creating, relation created in current target.")
	default String openRelation(
		@P(value = "Relation name", required = true) String name,
		@P(value = "Primary datasource (format: 'server_name/table_name' or 'db:/server_name/table_name')", required = false) String primaryDataSource,
		@P(value = "Foreign datasource (format: 'server_name/table_name' or 'db:/server_name/table_name')", required = false) String foreignDataSource,
		@P(value = "Primary column name", required = false) String primaryColumn,
		@P(value = "Foreign column name", required = false) String foreignColumn,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties)
	{
		if (name != null && !name.trim().isEmpty())
		{
			return UIThreadHelper.syncExec("openRelation",
				() -> RelationToolsHelper.getInstance().openOrCreateRelation(name, primaryDataSource, foreignDataSource,
					primaryColumn, foreignColumn, properties));
		}

		return "Error: name parameter is required";
	}
}

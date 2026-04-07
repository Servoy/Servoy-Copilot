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
package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.servoy.eclipse.servoypilot.services.DatabaseSchemaService;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.ITable;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IDatabaseTool
{
	@Tool("Lists all tables in a database server. Returns table names for the specified server.")
	default String listTables(
		@P(value = "Database server name", required = true) String serverName)
	{
		if (serverName != null && !serverName.trim().isEmpty())
		{
			try
			{
				IServerInternal server = DatabaseSchemaService.getServer(serverName);
				if (server != null)
				{
					List<String> tables = DatabaseSchemaService.getTableNames(server);
					StringBuilder result = new StringBuilder();
					result.append("Database Server: ").append(serverName).append("\n");
					result.append("Tables (").append(tables.size()).append("):\n\n");
					if (tables.isEmpty()) result.append("(No tables found)\n");
					for (String tableName : tables) result.append("  - ").append(tableName).append("\n");
					return result.toString();
				}
				return "Error: Database server '" + serverName + "' not found";
			}
			catch (Exception e)
			{
				return "Error listing tables: " + e.getMessage();
			}
		}

		return "Error: serverName parameter is required";
	}

	@Tool("Retrieves comprehensive information about a database table including columns, primary keys, and metadata.")
	default String getTableInfo(
		@P(value = "Database server name", required = true) String serverName,
		@P(value = "Table name", required = true) String tableName)
	{
		if (serverName != null && !serverName.trim().isEmpty())
		{
			if (tableName != null && !tableName.trim().isEmpty())
			{
				try
				{
					IServerInternal server = DatabaseSchemaService.getServer(serverName);
					if (server != null)
					{
						ITable table = DatabaseSchemaService.getTable(server, tableName);
						if (table != null)
						{
							StringBuilder result = new StringBuilder();
							result.append("Table: ").append(table.getSQLName()).append("\n");
							result.append("DataSource: ").append(table.getDataSource()).append("\n\n");
							result.append("Columns:\n\n");

							Collection<Column> columns = DatabaseSchemaService.getColumns(table);
							if (columns != null && !columns.isEmpty())
							{
								int colNum = 1;
								Set<String> pkNames = DatabaseSchemaService.getPrimaryKeyNames(table);
								for (Column col : columns)
								{
									try
									{
										String colName = col.getName();
										String colTypeName = "UNKNOWN";
										try
										{
											Object columnTypeObj = col.getColumnType();
											if (columnTypeObj != null) colTypeName = columnTypeObj.toString();
										}
										catch (Exception typeEx)
										{
											colTypeName = "ERROR: " + typeEx.getMessage();
										}
										result.append(colNum).append(". ");
										result.append("Name: ").append(colName).append("\n");
										result.append("   Type: ").append(colTypeName).append("\n");
										result.append("   Primary Key: ").append(pkNames.contains(colName)).append("\n\n");
										colNum++;
									}
									catch (Exception colEx)
									{
										result.append(colNum).append(". Name: [ERROR - ").append(colEx.getMessage()).append("]\n");
										result.append("   Type: UNKNOWN\n   Primary Key: false\n\n");
										colNum++;
									}
								}
								return result.toString();
							}
							result.append("(No columns found)\n");
							return result.toString();
						}
						return "Error: Table '" + tableName + "' not found in server '" + serverName + "'";
					}
					return "Error: Database server '" + serverName + "' not found";
				}
				catch (Exception e)
				{
					return "Error getting table info: " + e.getMessage();
				}
			}
			return "Error: tableName parameter is required";
		}

		return "Error: serverName parameter is required";
	}
}

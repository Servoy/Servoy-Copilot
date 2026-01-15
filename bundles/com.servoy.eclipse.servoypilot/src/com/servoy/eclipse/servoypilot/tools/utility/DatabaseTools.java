package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.List;

import com.servoy.eclipse.servoypilot.services.DatabaseSchemaService;
import com.servoy.j2db.persistence.IServerInternal;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for database schema operations.
 * Migrated from knowledgebase.mcp DatabaseToolHandler.
 * 
 * PILOT: Only listTables tool migrated as example.
 * TODO: Migrate remaining tools: getTableInfo
 */
public class DatabaseTools
{
	/**
	 * Lists all tables in a database server.
	 * 
	 * @param serverName Database server name
	 * @return Formatted string with table names
	 */
	@Tool("Lists all tables in a database server. Returns table names for the specified server.")
	public String listTables(
		@P(value = "Database server name", required = true) String serverName)
	{
		if (serverName == null || serverName.trim().isEmpty())
		{
			return "Error: serverName parameter is required";
		}

		try
		{
			IServerInternal server = DatabaseSchemaService.getServer(serverName);
			if (server == null)
			{
				return "Error: Database server '" + serverName + "' not found";
			}

			List<String> tables = DatabaseSchemaService.getTableNames(server);
			StringBuilder result = new StringBuilder();
			result.append("Database Server: ").append(serverName).append("\n");
			result.append("Tables (").append(tables.size()).append("):\n\n");

			if (tables.isEmpty())
			{
				result.append("(No tables found)\n");
			}
			else
			{
				for (String tableName : tables)
				{
					result.append("  - ").append(tableName).append("\n");
				}
			}

			return result.toString();
		}
		catch (Exception e)
		{
			return "Error listing tables: " + e.getMessage();
		}
	}
}

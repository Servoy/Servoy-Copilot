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

/**
 * Tools for database schema operations.
 * Migrated from knowledgebase.mcp DatabaseToolHandler.
 * 
 * Complete migration: All 2 tools implemented.
 */
public class DatabaseTools
{
	/**
	 * Lists all tables in a database server.
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

	/**
	 * Gets detailed information about a database table including columns, primary keys, and metadata.
	 */
	@Tool("Retrieves comprehensive information about a database table including columns, primary keys, and metadata.")
	public String getTableInfo(
		@P(value = "Database server name", required = true) String serverName,
		@P(value = "Table name", required = true) String tableName)
	{
		if (serverName == null || serverName.trim().isEmpty())
		{
			return "Error: serverName parameter is required";
		}

		if (tableName == null || tableName.trim().isEmpty())
		{
			return "Error: tableName parameter is required";
		}

		try
		{
			IServerInternal server = DatabaseSchemaService.getServer(serverName);
			if (server == null)
			{
				return "Error: Database server '" + serverName + "' not found";
			}

			ITable table = DatabaseSchemaService.getTable(server, tableName);
			if (table == null)
			{
				return "Error: Table '" + tableName + "' not found in server '" + serverName + "'";
			}

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
					result.append(colNum).append(". ");
					result.append("Name: ").append(col.getName()).append("\n");

					String colTypeName = col.getColumnType() != null ? col.getColumnType().toString() : "UNKNOWN";
					result.append("   Type: ").append(colTypeName).append("\n");

					boolean isPK = pkNames.contains(col.getName());
					result.append("   Primary Key: ").append(isPK).append("\n\n");
					colNum++;
				}
			}
			else
			{
				result.append("(No columns found)\n");
			}

			return result.toString();
		}
		catch (Exception e)
		{
			return "Error getting table info: " + e.getMessage();
		}
	}
}

package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.IServerManagerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for accessing database schema metadata.
 * Migrated from knowledgebase.mcp DatabaseSchemaService.
 * 
 * Complete implementation with core methods.
 * Provides reusable methods for querying tables, columns, primary keys, and foreign key relationships.
 */
public class DatabaseSchemaService
{
	/**
	 * Get a database server by name.
	 */
	public static IServerInternal getServer(String serverName)
	{
		if (serverName == null || serverName.trim().isEmpty())
		{
			return null;
		}

		try
		{
			IServerManagerInternal serverManager = ApplicationServerRegistry.get().getServerManager();
			return (IServerInternal)serverManager.getServer(serverName, false, false);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[DatabaseSchemaService] Failed to get server '" + serverName + "': " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Get list of all table names in a server.
	 */
	public static List<String> getTableNames(IServerInternal server)
	{
		if (server == null)
		{
			return new ArrayList<>();
		}

		try
		{
			return server.getTableNames(false);
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("[DatabaseSchemaService] Failed to get table names: " + e.getMessage(), e);
			return new ArrayList<>();
		}
	}

	/**
	 * Get a specific table from a server.
	 */
	public static ITable getTable(IServerInternal server, String tableName)
	{
		if (server == null || tableName == null || tableName.trim().isEmpty())
		{
			return null;
		}

		try
		{
			return server.getTable(tableName);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[DatabaseSchemaService] Failed to get table '" + tableName + "': " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Get all columns for a table.
	 */
	public static Collection<Column> getColumns(ITable table)
	{
		if (table == null)
		{
			return new ArrayList<>();
		}

		try
		{
			return table.getColumns();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[DatabaseSchemaService] Failed to get columns: " + e.getMessage(), e);
			return new ArrayList<>();
		}
	}

	/**
	 * Get primary key column names for a table.
	 */
	public static Set<String> getPrimaryKeyNames(ITable table)
	{
		Set<String> pkNames = new HashSet<>();

		if (table == null)
		{
			return pkNames;
		}

		try
		{
			List<Column> pkColumns = table.getRowIdentColumns();
			if (pkColumns != null)
			{
				for (Column col : pkColumns)
				{
					pkNames.add(col.getName());
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("[DatabaseSchemaService] Failed to get primary keys: " + e.getMessage(), e);
		}

		return pkNames;
	}
}

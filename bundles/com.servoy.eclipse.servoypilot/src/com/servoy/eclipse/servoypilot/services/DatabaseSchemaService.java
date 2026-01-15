package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.List;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.IServerManagerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for accessing database schema metadata.
 * Migrated from knowledgebase.mcp DatabaseSchemaService.
 * 
 * PILOT: Minimal implementation with core methods.
 * Provides reusable methods for querying tables, columns, primary keys, and foreign key relationships.
 */
public class DatabaseSchemaService
{
	/**
	 * Get a database server by name.
	 *
	 * @param serverName the database server name
	 * @return IServerInternal instance or null if not found
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
	 *
	 * @param server the database server
	 * @return list of table names (empty list if none found)
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
	 *
	 * @param server the database server
	 * @param tableName the table name
	 * @return ITable instance or null if not found
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
}

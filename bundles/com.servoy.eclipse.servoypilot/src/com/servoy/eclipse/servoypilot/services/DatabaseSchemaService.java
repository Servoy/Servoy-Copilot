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

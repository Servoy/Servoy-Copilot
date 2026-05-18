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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.servers.MemoryServer;
import com.servoy.eclipse.developer.mcp.servers.TimeServer;

/**
 * Registry of all built-in MCP server implementation classes.
 *
 * <p>To add a new server endpoint, create a class annotated with {@link McpServer}
 * and add it to {@link #BUILT_IN_SERVER_CLASSES}.</p>
 */
public class McpServerBuiltins
{
	/**
	 * The canonical list of all built-in MCP server classes.
	 * Add new server classes here as they are implemented.
	 */
	public static final Class<?>[] BUILT_IN_SERVER_CLASSES = {
		TimeServer.class,
		MemoryServer.class,
		// Phase 2+: more server classes will be added here, e.g.:
		// ServoyIdeServer.class,
		// ServoyCoderServer.class,
		// ServoyRunnerServer.class,
		// ServoyContextServer.class,
		// ServoyGitServer.class,
		// ServoyDevServer.class,
	};

	/**
	 * Instantiates one instance of each registered server class.
	 * Returns an empty list until server classes are added to {@link #BUILT_IN_SERVER_CLASSES}.
	 */
	public static List<Object> createServerInstances()
	{
		if (BUILT_IN_SERVER_CLASSES.length == 0)
		{
			return Collections.emptyList();
		}
		List<Object> instances = new ArrayList<>(BUILT_IN_SERVER_CLASSES.length);
		for (Class<?> clazz : BUILT_IN_SERVER_CLASSES)
		{
			try
			{
				instances.add(clazz.getDeclaredConstructor().newInstance());
			}
			catch (Exception e)
			{
				throw new RuntimeException("Failed to instantiate MCP server: " + clazz.getName(), e);
			}
		}
		return instances;
	}
}

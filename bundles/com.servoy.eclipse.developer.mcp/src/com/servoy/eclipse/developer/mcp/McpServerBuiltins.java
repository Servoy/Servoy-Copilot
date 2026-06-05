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

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.servers.MemoryServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyContextServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServer;
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
		ServoyContextServer.class,
		ServoyCoderServer.class,
		ServoyIdeServer.class,
		ServoyGitServer.class,
		ServoyDevServer.class,
		ServoyTestingServer.class,
		ServoyWpmServer.class,
	};

	/**
	 * Instantiates one instance of each registered server class using E4 DI.
	 * Falls back to plain reflection if no E4 context is available (e.g. in tests).
	 */
	public static List<Object> createServerInstances()
	{
		return createServerInstances(null);
	}

	/**
	 * Instantiates one instance of each registered server class.
	 * When {@code context} is non-null, uses {@link ContextInjectionFactory#make} so
	 * {@code @Inject} fields on server and service classes are fulfilled.
	 * Falls back to plain reflection when {@code context} is null (tests, headless).
	 */
	public static List<Object> createServerInstances(IEclipseContext context)
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
				Object instance = (context != null)
					? ContextInjectionFactory.make(clazz, context)
					: clazz.getDeclaredConstructor().newInstance();
				instances.add(instance);
			}
			catch (Exception e)
			{
				throw new RuntimeException("Failed to instantiate MCP server: " + clazz.getName(), e);
			}
		}
		return instances;
	}
}

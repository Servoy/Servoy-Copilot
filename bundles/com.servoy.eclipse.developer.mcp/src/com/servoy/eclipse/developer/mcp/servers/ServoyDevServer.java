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
package com.servoy.eclipse.developer.mcp.servers;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;

/**
 * MCP server for Servoy Developer tools (stub for future development).
 */
@Creatable
@McpServer(name = "servoy-dev")
public class ServoyDevServer
{
	public ServoyDevServer()
	{
	}

	@Tool(name = "ping", description = "Returns a simple pong response to verify the servoy-dev endpoint is alive.", type = "object")
	public String ping()
	{
		return "pong";
	}
}

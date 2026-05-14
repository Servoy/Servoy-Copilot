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

import org.eclipse.ui.IStartup;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * IStartup implementation — triggers early activation of this plugin so that
 * McpServiceProvider is registered before any MCP client connects.
 */
public class McpStartup implements IStartup
{
	@Override
	public void earlyStartup()
	{
		// Touching Activator.getDefault() is enough to ensure the bundle is activated.
		// The actual MCP server lifecycle is managed by McpServerRegistry, which is
		// initialised lazily on first use from McpServiceProvider.
		ServoyLog.logInfo("Servoy Developer MCP Server: early startup complete.");
	}
}

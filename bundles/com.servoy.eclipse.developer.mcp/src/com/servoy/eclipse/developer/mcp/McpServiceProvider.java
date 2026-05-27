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

import java.util.Collections;
import java.util.Set;

import org.apache.tomcat.starter.IServicesProvider;
import org.apache.tomcat.starter.ServletInstance;

import com.servoy.eclipse.model.util.ServoyLog;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Registers the MCP servlet instances with Servoy's embedded Tomcat.
 * <p>
 * Tomcat calls {@link #getServletInstances(String)} at startup, before the E4
 * workbench is fully initialised. This class therefore bootstraps
 * {@link McpServerRegistry} directly when the singleton is not yet available.
 * </p>
 */
public class McpServiceProvider implements IServicesProvider
{
	@Override
	public Set<ServletInstance> getServletInstances(String context)
	{
		try
		{
			McpServerRegistry registry = McpServerRegistry.getInstance();
			if (registry == null)
			{
				// Tomcat is starting before McpStartup has run — bootstrap now
				ServoyLog.logInfo("Servoy Developer MCP: bootstrapping registry from McpServiceProvider.");
				Activator activator = Activator.getDefault();
				if (activator == null)
				{
					ServoyLog.logWarning("Servoy Developer MCP: Activator not yet available — no servlets registered.", null);
					return Collections.emptySet();
				}
				registry = activator.make(McpServerRegistry.class);
			}
			return registry.getServletInstances();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Servoy Developer MCP: failed to get servlet instances", e);
			return Collections.emptySet();
		}
	}
}

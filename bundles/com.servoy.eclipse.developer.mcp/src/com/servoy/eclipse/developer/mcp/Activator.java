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

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Bundle activator for the Servoy Developer MCP Server plugin.
 */
public class Activator implements BundleActivator
{
	public static final String PLUGIN_ID = "com.servoy.eclipse.developer.mcp";

	private static Activator instance;
	private ScopedPreferenceStore preferenceStore;

	public static Activator getDefault()
	{
		return instance;
	}

	@Override
	public void start(BundleContext context) throws Exception
	{
		instance = this;
		ServoyLog.logInfo("Servoy Developer MCP Server plugin started.");
	}

	@Override
	public void stop(BundleContext context) throws Exception
	{
		McpServerRegistry.getInstance().shutdown();
		preferenceStore = null;
		instance = null;
		ServoyLog.logInfo("Servoy Developer MCP Server plugin stopped.");
	}

	public IPreferenceStore getPreferenceStore()
	{
		if (preferenceStore == null)
		{
			preferenceStore = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);
		}
		return preferenceStore;
	}
}

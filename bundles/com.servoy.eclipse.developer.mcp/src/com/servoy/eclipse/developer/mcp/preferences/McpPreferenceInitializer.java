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
package com.servoy.eclipse.developer.mcp.preferences;

import java.util.UUID;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.servoy.eclipse.developer.mcp.Activator;

/**
 * Initialises default preference values for the Servoy Developer MCP Server plugin.
 * Generates a random UUID bearer token on first run and persists it permanently.
 */
public class McpPreferenceInitializer extends AbstractPreferenceInitializer
{
	@Override
	public void initializeDefaultPreferences()
	{
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();

		// Generate and permanently save a token if none exists yet
		String existing = store.getString(McpPreferenceConstants.MCP_AUTH_TOKEN);
		if (existing == null || existing.isBlank())
		{
			store.setValue(McpPreferenceConstants.MCP_AUTH_TOKEN, UUID.randomUUID().toString());
		}
	}
}

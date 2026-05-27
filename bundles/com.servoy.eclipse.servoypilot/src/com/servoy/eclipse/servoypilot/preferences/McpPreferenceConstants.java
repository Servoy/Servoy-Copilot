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
package com.servoy.eclipse.servoypilot.preferences;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Preference keys and defaults for MCP Servers In Use configuration.
 *
 * Key scheme:
 *   mcp.servers.json               ... raw JSON text of the configured MCP servers
 *   mcp.registry.url               ... URL of the MCP registry
 *   mcp.server.<serverName>\u001Fdisabled                              ... boolean, server-level enable
 *   mcp.tool.<serverName>\u001F<toolName>\u001Fdisabled                     ... boolean, tool-level enable
 *   mcp.tool.<serverName>\u001F<toolName>\u001F<AssistantType.name()>\u001Fdisabled      ... boolean, per-agent enable
 */
public final class McpPreferenceConstants
{

	public static final String MCP_SERVERS_JSON = "mcp.servers.json";
	public static final String MCP_REGISTRY_URL = "mcp.registry.url";

	public static final String DEFAULT_REGISTRY_URL = "https://registry.modelcontextprotocol.io"; // https://api.mcp.github.com would be another one but there are others

	/** Separator used for compound keys (server/tool/agent) to avoid collisions with dots in names. */
	public static final String KEY_SEPARATOR = "\u001F";

	/** Prefix for server-level enabled flags. Append <serverName>.enabled */
	public static final String MCP_SERVER_ENABLED_PREFIX = "mcp.server.";
	public static final String DISABLED_SUFFIX = KEY_SEPARATOR + "disabled";

	/** Prefix for tool-level flags. Append <serverName>.<toolName>.enabled */
	public static final String MCP_TOOL_PREFIX = "mcp.tool.";

	/** Default JSON shown when no config has been saved yet. */
	public static final String DEFAULT_SERVERS_JSON = "{}";

	private McpPreferenceConstants()
	{
		// Utility class
	}

	public static void initializeDefaults(IPreferenceStore store)
	{
		store.setDefault(MCP_SERVERS_JSON, DEFAULT_SERVERS_JSON);
		store.setDefault(MCP_REGISTRY_URL, DEFAULT_REGISTRY_URL);
	}

	// --- Key builders ---

	// we use here disabled instead of enabled to store stuff because the eclipse pref. store
	// would remove the value if you do set (false) - so a default for it, and we want if nothing is
	// stored to be default - enabled; so if nothing is stored (if we stored enabled instead of disabled
	// that would mean for us a "true") or false is stored, contains returns "false" -
	// so we need the value for "nothing stored"/default value to match the default value we want to read

	/** Returns the preference key for a server-level disabled flag. */
	public static String serverDisabledKey(String serverName)
	{
		return MCP_SERVER_ENABLED_PREFIX + sanitize(serverName) + DISABLED_SUFFIX;
	}

	/** Returns the preference key for a tool-level disabled flag. */
	public static String toolDisabledKey(String serverName, String toolName)
	{
		return MCP_TOOL_PREFIX + sanitize(serverName) + KEY_SEPARATOR + sanitize(toolName) + DISABLED_SUFFIX;
	}

	/** Returns the preference key for a per-agent tool disabled flag. */
	public static String toolAgentDisableddKey(String serverName, String toolName, String assistantTypeName)
	{
		return MCP_TOOL_PREFIX + sanitize(serverName) + KEY_SEPARATOR + sanitize(toolName) + KEY_SEPARATOR + assistantTypeName + DISABLED_SUFFIX;
	}

	/**
	 * Returns a prefix that matches all preference keys belonging to a given server.
	 * Use to enumerate and delete all prefs for a removed server.
	 */
	public static String serverKeyPrefix(String serverName)
	{
		return MCP_SERVER_ENABLED_PREFIX + sanitize(serverName) + KEY_SEPARATOR;
	}

	/**
	 * Returns a prefix that matches all tool preference keys belonging to a given server.
	 */
	public static String serverToolKeyPrefix(String serverName)
	{
		return MCP_TOOL_PREFIX + sanitize(serverName) + KEY_SEPARATOR;
	}

	/**
	 * Sanitizes a server/tool name so it is safe to embed in a preference key.
	 * Replaces characters that could confuse key parsing with underscores.
	 */
	public static String sanitize(String name)
	{
		if (name == null)
		{
			return "_";
		}
		// Allow alphanumeric, dash, slash, underscore. Replace everything else.
		return name.replaceAll("[^a-zA-Z0-9\\-_/]", "_");
	}
}

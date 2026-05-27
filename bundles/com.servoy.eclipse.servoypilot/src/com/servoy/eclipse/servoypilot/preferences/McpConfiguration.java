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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.AssistantType;

/**
 * Reads MCP configuration from the Eclipse preference store.
 *
 * JSON format:
 * {
 *   "your.url.example/sample-remote-mcp" : {
 *     "type" : "streamable-http",
 *     "url" : "https://example.com/mcp",
 *     "headers" : {
 *       "Authorization" : "Bearer <your-token-here>"
 *     }
 *   },
 *   "your.url.example/sample-stdio-mcp" : {
 *     "type" : "stdio",
 *     "command" : "npx",
 *     "args" : [ "-y", "@example/sample-stdio-mcp" ]
 *   }
 * }
 */
public class McpConfiguration
{

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Parsed representation of a single MCP server entry from the JSON config. */
	public static class McpServerConfig
	{
		public final String name;
		public final String type; // "http" or "stdio"
		public final String url; // for http/sse type
		public final String command; // for stdio type
		public final List<String> args;
		public final List<String[]> env; // list of [key, value] pairs
		public final Map<String, String> headers; // HTTP headers (http type only)

		public McpServerConfig(String name, String type, String url, String command,
			List<String> args, List<String[]> env)
		{
			this(name, type, url, command, args, env, null);
		}

		public McpServerConfig(String name, String type, String url, String command,
			List<String> args, List<String[]> env, Map<String, String> headers)
		{
			this.name = name;
			this.type = type;
			this.url = url;
			this.command = command;
			this.args = args != null ? Collections.unmodifiableList(args) : Collections.emptyList();
			this.env = env != null ? Collections.unmodifiableList(env) : Collections.emptyList();
			this.headers = headers != null && !headers.isEmpty()
				? Collections.unmodifiableMap(headers) : Collections.emptyMap();
		}

		public boolean isStdio()
		{
			return "stdio".equalsIgnoreCase(type);
		}

		public boolean isHttp()
		{
			return "http".equalsIgnoreCase(type) || "sse".equalsIgnoreCase(type) || "streamable-http".equalsIgnoreCase(type);
		}
	}

	// --- Preference store accessors ---

	private IPreferenceStore store()
	{
		return Activator.getDefault().getPreferenceStore();
	}

	public String getServersJson()
	{
		return store().getString(McpPreferenceConstants.MCP_SERVERS_JSON);
	}

	public String getRegistryUrl()
	{
		String url = store().getString(McpPreferenceConstants.MCP_REGISTRY_URL);
		if (url == null || url.isBlank())
		{
			return McpPreferenceConstants.DEFAULT_REGISTRY_URL;
		}
		return url;
	}

	// --- Parsed config ---

	/**
	 * Parses the stored JSON and returns a list of McpServerConfig entries.
	 * Returns empty list if JSON is blank or invalid.
	 */
	public List<McpServerConfig> getConfiguredServers()
	{
		String json = getServersJson();
		if (json == null || json.isBlank() || json.equals(McpPreferenceConstants.DEFAULT_SERVERS_JSON))
		{
			return Collections.emptyList();
		}

		try
		{
			JsonNode root = MAPPER.readTree(json);
			if (!root.isObject())
			{
				return Collections.emptyList();
			}

			List<McpServerConfig> result = new ArrayList<>();
			root.properties().forEach(entry -> {
				String name = entry.getKey();
				JsonNode node = entry.getValue();
				String type = node.path("type").asText("http");
				String url = node.path("url").asText(null);
				String command = node.path("command").asText(null);

				List<String> args = new ArrayList<>();
				JsonNode argsNode = node.path("args");
				if (argsNode.isArray())
				{
					argsNode.forEach(a -> args.add(a.asText()));
				}

				List<String[]> env = new ArrayList<>();
				JsonNode envNode = node.path("env");
				if (envNode.isObject())
				{
					envNode.properties().forEach(e -> env.add(new String[] { e.getKey(), e.getValue().asText() }));
				}


				Map<String, String> headers = new LinkedHashMap<>();
				JsonNode headersNode = node.path("headers");
				if (headersNode.isObject())
				{
					headersNode.properties().forEach(h -> headers.put(h.getKey(), h.getValue().asText()));
				}

				result.add(new McpServerConfig(name, type, url, command, args, env, headers));
			});

			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("McpConfiguration: failed to parse MCP servers JSON", e);
			return Collections.emptyList();
		}
	}

	// --- Enable/disable flags ---

	public boolean isServerEnabled(String serverName)
	{
		// by default, if key is not set, then the value for the key below would be false
		String serverDisabledKey = McpPreferenceConstants.serverDisabledKey(serverName);
		return !store().getBoolean(serverDisabledKey);
	}

	public boolean isToolEnabled(String serverName, String toolName)
	{
		// by default, if key is not set, then the value for the key below would be false
		String toolDisabledKey = McpPreferenceConstants.toolDisabledKey(serverName, toolName);
		return !store().getBoolean(toolDisabledKey);
	}

	public boolean isToolEnabledForAgent(String serverName, String toolName, AssistantType assistantType)
	{
		// by default, if key is not set, then the value for the key below would be false
		String toolDisabledForAgentKey = McpPreferenceConstants.toolAgentDisableddKey(serverName, toolName, assistantType.name());
		return !store().getBoolean(toolDisabledForAgentKey);
	}

	// --- Mutators (used by the preference page) ---

	public void setServerEnabled(String serverName, boolean enabled)
	{
		store().setValue(McpPreferenceConstants.serverDisabledKey(serverName), !enabled);
	}

	public void setToolEnabled(String serverName, String toolName, boolean enabled)
	{
		store().setValue(McpPreferenceConstants.toolDisabledKey(serverName, toolName), !enabled);
	}

	public void setToolEnabledForAgent(String serverName, String toolName, AssistantType assistantType, boolean enabled)
	{
		store().setValue(
			McpPreferenceConstants.toolAgentDisableddKey(serverName, toolName, assistantType.name()),
			!enabled);
	}

	/**
	 * Removes all preference keys associated with a server that is no longer configured.
	 * Does NOT modify errored servers — callers must check first.
	 */
	public void deleteServerPreferences(String serverName)
	{
		String serverPrefix = McpPreferenceConstants.serverKeyPrefix(serverName);
		String toolPrefix = McpPreferenceConstants.serverToolKeyPrefix(serverName);

		// setToDefault effectively removes custom values
		store().setToDefault(McpPreferenceConstants.serverDisabledKey(serverName));

		// We cannot enumerate all tool keys from the store directly, but we can remove the
		// server-level key. Tool-level keys will be orphaned but harmless; they start with
		// the server prefix so we can identify them if the store supports key enumeration.
		// For ScopedPreferenceStore (backed by org.eclipse.core.runtime.preferences) we can
		// use the OSGi preferences node to enumerate and delete.
		try
		{
			org.osgi.service.prefs.Preferences node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
				.getNode(Activator.PLUGIN_ID);
			for (String key : node.keys())
			{
				if (key.startsWith(serverPrefix) || key.startsWith(toolPrefix))
				{
					node.remove(key);
				}
			}
			node.flush();
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("McpConfiguration: could not fully clean up prefs for server: " + serverName, e);
		}
	}

	/**
	 * Saves the MCP servers JSON and registry URL to the preference store, and cleans up
	 * preferences for servers that are no longer present in the new JSON.
	 * Does NOT touch prefs for servers that errored (their names are passed in erroredServers).
	 */
	public void saveServersJson(String json, String registryUrl, List<String> previousServerNames,
		List<String> erroredServerNames)
	{
		store().setValue(McpPreferenceConstants.MCP_SERVERS_JSON, json != null ? json : McpPreferenceConstants.DEFAULT_SERVERS_JSON);
		store().setValue(McpPreferenceConstants.MCP_REGISTRY_URL, registryUrl != null ? registryUrl : McpPreferenceConstants.DEFAULT_REGISTRY_URL);

		// Parse the new JSON to find current server names
		List<McpServerConfig> current = getConfiguredServers();
		List<String> currentNames = new ArrayList<>();
		for (McpServerConfig cfg : current)
		{
			currentNames.add(cfg.name);
		}

		// Delete prefs for servers that were previously configured but are no longer present,
		// unless they are in the errored list (we leave those untouched as specified)
		for (String prevName : previousServerNames)
		{
			if (!currentNames.contains(prevName) && !erroredServerNames.contains(prevName))
			{
				deleteServerPreferences(prevName);
			}
		}
	}

	/**
	 * Merges a single server JSON snippet (object value) into the existing JSON,
	 * keyed by serverName. Used by the registry browser "Use" button.
	 * Returns the merged JSON string.
	 */
	public static String mergeServerIntoJson(String existingJson, String serverName, JsonNode serverNode)
	{
		try
		{
			ObjectNode root;
			if (existingJson == null || existingJson.isBlank() ||
				existingJson.equals(McpPreferenceConstants.DEFAULT_SERVERS_JSON))
			{
				root = MAPPER.createObjectNode();
			}
			else
			{
				JsonNode parsed = MAPPER.readTree(existingJson);
				root = parsed.isObject() ? (ObjectNode)parsed : MAPPER.createObjectNode();
			}
			root.set(serverName, serverNode);
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("McpConfiguration: failed to merge server into JSON", e);
			return existingJson;
		}
	}

	/**
	 * Removes a server entry from the JSON by name.
	 * Returns the modified JSON string.
	 */
	public static String removeServerFromJson(String existingJson, String serverName)
	{
		try
		{
			if (existingJson == null || existingJson.isBlank())
			{
				return McpPreferenceConstants.DEFAULT_SERVERS_JSON;
			}

			JsonNode parsed = MAPPER.readTree(existingJson);
			if (!parsed.isObject())
			{
				return existingJson;
			}

			ObjectNode root = (ObjectNode)parsed;
			root.remove(serverName);
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("McpConfiguration: failed to remove server from JSON", e);
			return existingJson;
		}
	}

	/**
	 * Returns the names of all server keys in the given JSON, or empty list on error.
	 */
	public static List<String> parseServerNames(String json)
	{
		if (json == null || json.isBlank() || json.equals(McpPreferenceConstants.DEFAULT_SERVERS_JSON))
		{
			return Collections.emptyList();
		}

		try
		{
			JsonNode root = MAPPER.readTree(json);
			if (!root.isObject())
			{
				return Collections.emptyList();
			}

			List<String> names = new ArrayList<>();
			root.fieldNames().forEachRemaining(names::add);
			return names;
		}
		catch (Exception e)
		{
			return Collections.emptyList();
		}
	}
}
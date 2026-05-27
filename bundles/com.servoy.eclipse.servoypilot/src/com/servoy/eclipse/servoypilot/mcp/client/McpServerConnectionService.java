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
package com.servoy.eclipse.servoypilot.mcp.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.preferences.McpConfiguration;
import com.servoy.eclipse.servoypilot.preferences.McpConfiguration.McpServerConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolExecutor;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Manages connections to configured MCP servers and caches their tool lists.
 *
 * Lifecycle:
 * - Connections are created lazily (on first access per server).
 * - The preference page forces a reconnect by calling {@link #reconnectAll(List)}.
 * - Agents call {@link #getToolsForServer(String)} at model-build time.
 * - On plugin stop, {@link #closeAll()} cleans up all clients.
 *
 * Each server connection result is stored as a {@link McpServerResult}, which may be
 * successful (containing tools) or failed (containing an error message).
 */
public class McpServerConnectionService
{

	/** Result of attempting to connect to a single MCP server. */
	public static class McpServerResult
	{
		public final String serverName;
		public final boolean success;
		/** Non-null when success=true. */
		public final List<ToolSpecification> tools;
		/** Non-null when success=false. */
		public final String errorMessage;
		/** The live client, kept open for tool execution. Null when success=false. */
		public final McpClient client;

		private McpServerResult(String serverName, List<ToolSpecification> tools, McpClient client)
		{
			this.serverName = serverName;
			this.success = true;
			this.tools = Collections.unmodifiableList(tools);
			this.errorMessage = null;
			this.client = client;
		}

		private McpServerResult(String serverName, String errorMessage)
		{
			this.serverName = serverName;
			this.success = false;
			this.tools = Collections.emptyList();
			this.errorMessage = errorMessage;
			this.client = null;
		}
	}

	private static final Duration TOOL_EXEC_TIMEOUT = Duration.ofSeconds(60);

	private final McpConfiguration config;

	/**
	 * Cache of results keyed by server name. Populated lazily or via reconnectAll().
	 * ConcurrentHashMap for thread safety between the preference page (UI thread) and
	 * agent model-build (may run on background thread).
	 */
	private final ConcurrentHashMap<String, McpServerResult> results = new ConcurrentHashMap<>();

	public McpServerConnectionService(McpConfiguration config)
	{
		this.config = config;
	}

	/**
	 * Returns the cached result for the given server, connecting if not yet connected.
	 * This is the lazy path used by agents at model-build time.
	 */
	public McpServerResult getOrConnect(String serverName)
	{
		return results.computeIfAbsent(serverName, name -> {
			McpServerConfig cfg = findConfig(name);
			if (cfg == null)
			{
				return new McpServerResult(name, "Server '" + name + "' not found in current configuration");
			}
			return connect(cfg);
		});
	}

	/**
	 * Returns the cached result if available, without triggering a new connection.
	 * Returns null if not yet connected.
	 */
	public McpServerResult getCached(String serverName)
	{
		return results.get(serverName);
	}

	/**
	 * Connects (or reconnects) to all servers in the provided config list.
	 * Closes any existing clients for those servers first.
	 * Called by the preference page when the page opens or config changes.
	 *
	 * This method is synchronous — callers should invoke it from a background thread.
	 */
	public List<McpServerResult> reconnectAll(List<McpServerConfig> servers)
	{
		// Close and remove results for any server in the new list (we'll re-probe them)
		for (McpServerConfig cfg : servers)
		{
			McpServerResult existing = results.remove(cfg.name);
			if (existing != null && existing.client != null)
			{
				closeClient(existing.client, cfg.name);
			}
		}

		List<McpServerResult> newResults = new ArrayList<>();
		for (McpServerConfig cfg : servers)
		{
			McpServerResult connectionResut = results.computeIfAbsent(cfg.name, name -> {
				return connect(cfg);
			});
			newResults.add(connectionResut);
		}
		return newResults;
	}

	/**
	 * Returns all currently cached results (may be a partial set if lazy loading).
	 */
	public Map<String, McpServerResult> getAllCachedResults()
	{
		return Collections.unmodifiableMap(new HashMap<>(results));
	}

	/**
	 * Removes results for servers that are no longer in the configuration.
	 * Also closes their clients.
	 */
	public void removeStaleServers(List<String> currentServerNames)
	{
		List<String> stale = new ArrayList<>();
		for (String name : results.keySet())
		{
			if (!currentServerNames.contains(name))
			{
				stale.add(name);
			}
		}
		for (String name : stale)
		{
			McpServerResult result = results.remove(name);
			if (result != null && result.client != null)
			{
				closeClient(result.client, name);
			}
		}
	}

	/**
	 * Returns a ToolExecutor for a specific tool on a connected server.
	 * Returns null if the server has no live client or the tool is not found.
	 */
	public ToolExecutor getToolExecutor(String serverName, ToolSpecification toolSpec)
	{
		McpServerResult result = results.get(serverName);
		if (result == null || !result.success || result.client == null)
		{
			return null;
		}
		return new McpToolExecutor(result.client);
	}

	/** Closes all open MCP clients. Call on plugin stop. */
	public void closeAll()
	{
		for (Map.Entry<String, McpServerResult> entry : results.entrySet())
		{
			if (entry.getValue().client != null)
			{
				closeClient(entry.getValue().client, entry.getKey());
			}
		}
		results.clear();
	}

	// --- Private helpers ---

	private McpServerConfig findConfig(String serverName)
	{
		for (McpServerConfig cfg : config.getConfiguredServers())
		{
			if (cfg.name.equals(serverName))
			{
				return cfg;
			}
		}
		return null;
	}

	private McpServerResult connect(McpServerConfig cfg)
	{
		try
		{
			McpClient client;
			if (cfg.isHttp())
			{
				client = connectHttp(cfg);
			}
			else if (cfg.isStdio())
			{
				client = connectStdio(cfg);
			}
			else
			{
				return new McpServerResult(cfg.name, "Unsupported transport type: '" + cfg.type +
					"'. Use 'http' or 'stdio' (or 'sse').");
			}

			// Fetch tools list
			List<ToolSpecification> tools = client.listTools();
			ServoyLog.logInfo("McpServerConnectionService: connected to '" + cfg.name +
				"', found " + tools.size() + " tool(s)");
			return new McpServerResult(cfg.name, tools, client);
		}
		catch (Exception e)
		{
			String reason = buildErrorMessage(cfg, e);
			ServoyLog.logWarning("McpServerConnectionService: failed to connect to '" + cfg.name + "': " + reason, e);
			return new McpServerResult(cfg.name, reason);
		}
	}

	private McpClient connectHttp(McpServerConfig cfg) throws Exception
	{
		if (cfg.url == null || cfg.url.isBlank())
		{
			throw new IllegalArgumentException("HTTP MCP server '" + cfg.name + "' has no 'url' configured");
		}

		StreamableHttpMcpTransport.Builder transportBuilder = StreamableHttpMcpTransport.builder()
			.url(cfg.url);
		if (cfg.headers != null && !cfg.headers.isEmpty())
		{
			transportBuilder.customHeaders(cfg.headers);
		}

		return DefaultMcpClient.builder()
			.transport(transportBuilder.build())
			.toolExecutionTimeout(TOOL_EXEC_TIMEOUT)
			.build();
	}


	private McpClient connectStdio(McpServerConfig cfg) throws Exception
	{
		if (cfg.command == null || cfg.command.isBlank())
		{
			throw new IllegalArgumentException("Stdio MCP server '" + cfg.name + "' has no 'command' configured");
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(cfg.command);
		cmd.addAll(cfg.args);

		Map<String, String> env = new HashMap<>();
		for (String[] kv : cfg.env)
		{
			if (kv.length == 2)
			{
				env.put(kv[0], kv[1]);
			}
		}

		StdioMcpTransport transport = StdioMcpTransport.builder()
			.command(cmd)
			.environment(env)
			.logEvents(false)
			.build();

		return DefaultMcpClient.builder()
			.transport(transport)
			.toolExecutionTimeout(TOOL_EXEC_TIMEOUT)
			.build();
	}

	private String buildErrorMessage(McpServerConfig cfg, Exception e)
	{
		String msg = e.getMessage();
		if (msg == null)
		{
			msg = e.getClass().getSimpleName();
		}

		// Provide a more actionable hint for stdio servers with a missing command
		if (cfg.isStdio() && cfg.command != null && !cfg.command.isBlank())
		{
			boolean looksLikeMissingCommand = msg.toLowerCase().contains("no such file") ||
				msg.toLowerCase().contains("cannot run") ||
				msg.toLowerCase().contains("error=2") ||
				e instanceof java.io.IOException;
			if (looksLikeMissingCommand)
			{
				return "'" + cfg.command +
					"' needs to be available in the system PATH for this to work. " +
					"Install it separately and restart Servoy Developer if needed. (Detail: " + msg + ")";
			}
		}

		return msg;
	}

	private void closeClient(McpClient client, String serverName)
	{
		try
		{
			client.close();
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("McpServerConnectionService: error closing client for '" + serverName + "'", e);
		}
	}
}
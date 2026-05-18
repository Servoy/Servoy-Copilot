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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.cache.CachedEntry;
import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCache;
import com.servoy.eclipse.developer.mcp.services.LocalHistoryService;

/**
 * MCP server providing workspace context tools for the Servoy Developer MCP endpoint.
 * <p>
 * Endpoint: {@code /svymcp/servoy-context}
 * </p>
 * <p>
 * Tools:
 * <ul>
 *   <li>{@code listCachedResources} â lists all resources in the Servoy resource cache</li>
 *   <li>{@code getCachedResource} â returns content of a specific cached resource by URI</li>
 *   <li>{@code getCacheStats} â cache statistics</li>
 *   <li>{@code getFileHistory} â lists Eclipse Local History versions of a file</li>
 *   <li>{@code getFileHistoryContent} â returns content of a specific history version</li>
 *   <li>{@code compareWithHistory} â unified diff between current file and a history version</li>
 *   <li>{@code restoreFileVersion} â intentionally not implemented (dummy, returns error)</li>
 * </ul>
 * </p>
 */
@McpServer(name = "servoy-context")
public class ServoyContextServer
{
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.systemDefault());

	private final ServoyResourceCache cache = ServoyResourceCache.getInstance();
	private final LocalHistoryService localHistoryService = new LocalHistoryService();

	// --- Cache tools ---

	@Tool(name = "listCachedResources",
		description = "Lists all resources currently cached in the Servoy Developer MCP workspace context. "
			+ "Shows URIs, types, version numbers, timestamps, and token estimates. "
			+ "The cache is populated by tools that read workspace files (e.g. readProjectResource in servoy-ide). "
			+ "Use this to see what files the AI agent has recently accessed.",
		type = "object")
	public String listCachedResources()
	{
		Map<String, CachedEntry> all = cache.getAll();
		if (all.isEmpty())
		{
			return "No resources cached. Use servoy-ide tools (readProjectResource, getCurrentlyOpenedFile, etc.) "
				+ "to load resources into the cache.";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("# Cached Resources\n\n");
		sb.append(cache.getStats()).append("\n\n");
		sb.append(String.format("%-50s  %-20s  %-5s  %-20s  %s\n",
			"URI", "Type", "Ver", "Cached At", "Tokens"));
		sb.append("-".repeat(110)).append("\n");

		for (CachedEntry e : all.values())
		{
			String uri = truncate(e.uri(), 50);
			sb.append(String.format("%-50s  %-20s  v%-4d  %-20s  ~%d\n",
				uri,
				e.type(),
				e.version(),
				TIMESTAMP_FMT.format(e.cachedAt()),
				e.estimateTokens()));
		}

		return sb.toString();
	}

	@Tool(name = "getCachedResource",
		description = "Gets the content of a specific cached resource by URI without re-reading from disk. "
			+ "Use listCachedResources first to see available URIs. "
			+ "Returns the cached version â fast, no I/O.",
		type = "object")
	public String getCachedResource(
		@ToolParam(name = "resourceUri",
			description = "The URI of the cached resource (e.g. 'workspace:///ProjectName/scopes/utils.js')",
			required = true) String resourceUri)
	{
		return cache.get(resourceUri)
			.map(e -> {
				StringBuilder sb = new StringBuilder();
				sb.append("# ").append(e.displayName())
					.append(" (v").append(e.version())
					.append(", cached ").append(TIMESTAMP_FMT.format(e.cachedAt()))
					.append(")\n\n");
				sb.append(e.content());
				return sb.toString();
			})
			.orElse("Resource not found in cache: " + resourceUri
				+ "\nUse listCachedResources to see available URIs.");
	}

	@Tool(name = "getCacheStats",
		description = "Gets resource cache statistics: number of resources, token usage, and limits.",
		type = "object")
	public String getCacheStats()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("# Resource Cache Statistics\n\n");
		sb.append(cache.getStats()).append("\n\n");

		if (!cache.isEmpty())
		{
			sb.append("## Entries\n");
			sb.append(cache.toSummary());
		}

		return sb.toString();
	}

	// --- Local History tools ---

	@Tool(name = "getFileHistory",
		description = "Lists the Local History versions of a file maintained by Eclipse. "
			+ "Shows timestamps and sizes for each historical version. "
			+ "Eclipse automatically saves file history on every modification through the IDE.",
		type = "object")
	public String getFileHistory(
		@ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
		@ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
		@ToolParam(name = "maxEntries", description = "Maximum number of history entries to show (default: 20)", required = false) String maxEntries)
	{
		return localHistoryService.getFileHistory(projectName, filePath, maxEntries);
	}

	@Tool(name = "getFileHistoryContent",
		description = "Gets the content of a specific Local History version of a file. "
			+ "Use getFileHistory first to see available versions and their indices.",
		type = "object")
	public String getFileHistoryContent(
		@ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
		@ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
		@ToolParam(name = "index", description = "The history index (0 = most recent, from getFileHistory)", required = true) String index)
	{
		return localHistoryService.getFileHistoryContent(projectName, filePath, index);
	}

	@Tool(name = "compareWithHistory",
		description = "Shows a unified diff between the current file content and a Local History version. "
			+ "Use getFileHistory to find the version index.",
		type = "object")
	public String compareWithHistory(
		@ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
		@ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
		@ToolParam(name = "index", description = "The history index to compare against (0 = most recent, from getFileHistory)", required = true) String index)
	{
		return localHistoryService.compareWithHistory(projectName, filePath, index);
	}

	@Tool(name = "restoreFileVersion",
		description = "NOT IMPLEMENTED â restoring a file from Local History is intentionally disabled in "
			+ "Servoy Developer MCP because Servoy structural files (.frm, .obj, .tbl, .val, .rel, .dbi) "
			+ "encode UUID cross-references that break when restored as plain text. "
			+ "Use the Servoy editor's Team â Show Local History menu instead.",
		type = "object")
	public String restoreFileVersion(
		@ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
		@ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
		@ToolParam(name = "index", description = "The history index to restore (0 = most recent, from getFileHistory)", required = true) String index)
	{
		throw new RuntimeException(
			"restoreFileVersion is intentionally not implemented in Servoy Developer MCP â "
				+ "restoring history for Servoy structural files can break UUID cross-references. "
				+ "Use the Servoy editor's Team â Show Local History menu to restore files manually.");
	}

	// --- helpers ---

	private static String truncate(String s, int maxLen)
	{
		if (s == null || s.length() <= maxLen) return s;
		return "..." + s.substring(s.length() - maxLen + 3);
	}
}

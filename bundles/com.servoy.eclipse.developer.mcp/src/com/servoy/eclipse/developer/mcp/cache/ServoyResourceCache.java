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
package com.servoy.eclipse.developer.mcp.cache;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight LRU resource cache for the Servoy Developer MCP server.
 * <p>
 * Stores workspace file content read by MCP tools so that AI agents can
 * inspect what has been accessed without re-reading from disk.
 * </p>
 * <p>
 * URIs use the scheme {@code workspace:///ProjectName/path/to/file}.
 * No JDT dependency â Servoy Developer is not a Java IDE.
 * </p>
 * <p>
 * This is a process-scoped singleton. It is populated as a side-effect
 * of tools that read workspace files (e.g. {@code readProjectResource}).
 * </p>
 */
public class ServoyResourceCache
{
	private static final int MAX_ENTRIES = 20;
	private static final int MAX_TOTAL_TOKENS = 100_000;

	private static final ServoyResourceCache INSTANCE = new ServoyResourceCache();

	/** Access-order LRU map. */
	private final Map<String, CachedEntry> entries = Collections.synchronizedMap(
		new LinkedHashMap<>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest)
			{
				return size() > MAX_ENTRIES;
			}
		});

	private ServoyResourceCache()
	{
	}

	public static ServoyResourceCache getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Stores or updates a cache entry.
	 *
	 * @param uri         workspace URI, e.g. {@code workspace:///MyProject/scopes/utils.js}
	 * @param displayName short display name (typically the file name)
	 * @param type        resource type label, e.g. {@code WORKSPACE_FILE}
	 * @param content     the file content
	 */
	public synchronized void put(String uri, String displayName, String type, String content)
	{
		CachedEntry existing = entries.get(uri);
		int version = existing != null ? existing.version() + 1 : 1;

		// Evict by total tokens before adding
		evictByTokensIfNeeded(content != null ? content.length() / 4 : 0);

		entries.put(uri, new CachedEntry(uri, displayName, type, content, Instant.now(), version));
	}

	/**
	 * Returns the cached entry for the given URI, if present.
	 */
	public synchronized Optional<CachedEntry> get(String uri)
	{
		return Optional.ofNullable(entries.get(uri));
	}

	/**
	 * Returns a snapshot of all cached entries (insertion/access order).
	 */
	public synchronized Map<String, CachedEntry> getAll()
	{
		return new LinkedHashMap<>(entries);
	}

	/**
	 * Returns true if the cache contains no entries.
	 */
	public synchronized boolean isEmpty()
	{
		return entries.isEmpty();
	}

	/**
	 * Returns the number of cached entries.
	 */
	public synchronized int size()
	{
		return entries.size();
	}

	/**
	 * Returns total estimated token count across all entries.
	 */
	public synchronized int estimateTotalTokens()
	{
		return entries.values().stream().mapToInt(CachedEntry::estimateTokens).sum();
	}

	/**
	 * Returns a one-line stats string.
	 */
	public synchronized String getStats()
	{
		return String.format("Resources: %d/%d, Tokens: ~%d/%d",
			entries.size(), MAX_ENTRIES,
			estimateTotalTokens(), MAX_TOTAL_TOKENS);
	}

	/**
	 * Returns a multi-line summary of all cached entries.
	 */
	public synchronized String toSummary()
	{
		if (entries.isEmpty()) return "(none)";
		StringBuilder sb = new StringBuilder();
		for (CachedEntry e : entries.values())
		{
			sb.append("â¢ ").append(e.toSummary()).append("\n");
		}
		return sb.toString();
	}

	// --- private helpers ---

	private void evictByTokensIfNeeded(int incomingTokens)
	{
		while (!entries.isEmpty() && estimateTotalTokens() + incomingTokens > MAX_TOTAL_TOKENS)
		{
			String oldest = entries.keySet().iterator().next();
			entries.remove(oldest);
		}
	}
}

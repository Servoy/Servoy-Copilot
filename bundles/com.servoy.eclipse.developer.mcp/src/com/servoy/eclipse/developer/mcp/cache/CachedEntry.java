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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * An immutable snapshot of a cached workspace resource.
 * <p>
 * Keyed by a {@code workspace:///ProjectName/path/to/file} URI string.
 * Populated by MCP tools that read workspace files (e.g. readProjectResource).
 * </p>
 */
public record CachedEntry(
	String uri,
	String displayName,
	String type,
	String content,
	Instant cachedAt,
	int version)
{
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.systemDefault());

	/**
	 * Rough token estimate: ~4 characters per token.
	 */
	public int estimateTokens()
	{
		return content != null ? content.length() / 4 : 0;
	}

	/**
	 * One-line summary for display.
	 */
	public String toSummary()
	{
		return String.format("%-50s  %-20s  v%-4d  %-20s  ~%d tokens",
			truncate(uri, 50),
			type,
			version,
			TIMESTAMP_FMT.format(cachedAt),
			estimateTokens());
	}

	private static String truncate(String s, int maxLen)
	{
		if (s == null || s.length() <= maxLen) return s;
		return "..." + s.substring(s.length() - maxLen + 3);
	}
}

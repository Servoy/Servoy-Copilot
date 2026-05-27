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
package com.servoy.eclipse.developer.mcp.guard;

import java.util.Set;

/**
 * Refuses destructive text edits on Servoy structural file formats.
 * <p>
 * AI-driven MCP tools call {@link #assertEditable(String)} before any write.
 * On a forbidden extension the call throws {@link ServoyFileFormatProtectedException},
 * which the tool maps to a JSON-RPC error so AI agents get an explicit signal.
 * </p>
 */
public final class ServoyFileGuard
{
	private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(
		".frm", ".obj", ".tbl", ".val", ".rel", ".dbi"
	);

	private ServoyFileGuard()
	{
	}

	/**
	 * Asserts that the given file path is safe to edit as plain text.
	 *
	 * @param path the file path to check (may be relative or absolute)
	 * @throws ServoyFileFormatProtectedException if the path ends with a Servoy structural extension
	 */
	public static void assertEditable(String path)
	{
		if (path == null) return;
		String lower = path.toLowerCase();
		for (String ext : FORBIDDEN_EXTENSIONS)
		{
			if (lower.endsWith(ext))
			{
				throw new ServoyFileFormatProtectedException(path, ext);
			}
		}
	}

	/**
	 * Returns {@code true} if the given path ends with a Servoy structural extension.
	 */
	public static boolean isProtected(String path)
	{
		if (path == null) return false;
		String lower = path.toLowerCase();
		for (String ext : FORBIDDEN_EXTENSIONS)
		{
			if (lower.endsWith(ext)) return true;
		}
		return false;
	}
}

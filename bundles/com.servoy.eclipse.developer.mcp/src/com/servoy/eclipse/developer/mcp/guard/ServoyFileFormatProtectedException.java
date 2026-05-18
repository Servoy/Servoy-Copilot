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

/**
 * Thrown by {@link ServoyFileGuard} when a tool tries to perform a destructive
 * text edit on a Servoy structural file.
 * <p>
 * Tool implementations catch this and translate it to a JSON-RPC error so that
 * AI agents receive an explicit, actionable refusal message.
 * </p>
 */
public class ServoyFileFormatProtectedException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	private final String path;
	private final String extension;

	public ServoyFileFormatProtectedException(String path, String extension)
	{
		super("Refusing to edit Servoy structural file: " + path
			+ " (extension '" + extension + "' is protected â Servoy file"
			+ " formats encode UUIDs and cross-file references that break"
			+ " when edited as plain text). Use the Servoy editor instead.");
		this.path = path;
		this.extension = extension;
	}

	public String getPath()
	{
		return path;
	}

	public String getExtension()
	{
		return extension;
	}
}

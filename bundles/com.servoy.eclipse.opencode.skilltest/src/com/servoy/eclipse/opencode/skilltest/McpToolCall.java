/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode.skilltest;

import java.util.Collections;
import java.util.Map;

/**
 * An immutable record of a single MCP tool call extracted from an opencode
 * session transcript.
 * <p>
 * The {@code server} is the best-effort MCP server name (may be {@code null} if
 * the tool name did not carry a server prefix), {@code tool} is the tool name,
 * {@code arguments} is the (possibly empty) map of input arguments, and
 * {@code output} is the textual output of the call (may be {@code null}).
 * </p>
 *
 * @param server    the MCP server name, or {@code null} when unknown
 * @param tool      the tool name (never {@code null})
 * @param arguments the input arguments (never {@code null})
 * @param output    the tool output text, or {@code null}
 */
public record McpToolCall(String server, String tool, Map<String, Object> arguments, String output) {

	public McpToolCall {
		arguments = arguments == null ? Collections.emptyMap() : Collections.unmodifiableMap(arguments);
	}

	/**
	 * Splits a raw opencode tool name into a {@code [server, tool]} pair.
	 * <p>
	 * opencode names MCP tools either as {@code servername_tool} (underscore) or
	 * {@code servername.tool} (dot). This method is best-effort: if a separator is
	 * found the part before it becomes the server and the remainder the tool; if no
	 * separator is present the whole string is the tool and the server is
	 * {@code null}.
	 * </p>
	 *
	 * @param rawName the raw tool name from the transcript
	 * @return a two-element array {@code [server, tool]}; server may be {@code null}
	 */
	public static String[] splitToolName(String rawName) {
		if (rawName == null) {
			return new String[] { null, null };
		}
		int dot = rawName.indexOf('.');
		if (dot > 0 && dot < rawName.length() - 1) {
			return new String[] { rawName.substring(0, dot), rawName.substring(dot + 1) };
		}
		int underscore = rawName.indexOf('_');
		if (underscore > 0 && underscore < rawName.length() - 1) {
			return new String[] { rawName.substring(0, underscore), rawName.substring(underscore + 1) };
		}
		return new String[] { null, rawName };
	}

	/**
	 * Convenience factory that derives {@code server} and {@code tool} from a raw
	 * tool name via {@link #splitToolName(String)}.
	 *
	 * @param rawName   the raw (possibly server-prefixed) tool name
	 * @param arguments the input arguments
	 * @param output    the tool output text
	 * @return a new {@link McpToolCall}
	 */
	public static McpToolCall fromRawName(String rawName, Map<String, Object> arguments, String output) {
		String[] parts = splitToolName(rawName);
		return new McpToolCall(parts[0], parts[1], arguments, output);
	}

	/**
	 * @return {@code true} if this call's server + tool identity equals the given
	 *         pair (server comparison is skipped when either side is {@code null})
	 */
	public boolean matchesIdentity(String otherServer, String otherTool) {
		if (tool == null || otherTool == null) {
			return false;
		}
		if (!tool.equalsIgnoreCase(otherTool)) {
			return false;
		}
		if (server == null || otherServer == null) {
			return true;
		}
		return server.equalsIgnoreCase(otherServer);
	}
}

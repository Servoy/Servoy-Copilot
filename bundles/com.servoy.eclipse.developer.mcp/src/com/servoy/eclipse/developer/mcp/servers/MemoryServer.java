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

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * MCP server providing memory/thinking tools for AI agents.
 */
@McpServer(name = "memory")
public class MemoryServer
{
	@Tool(name = "think", description = "Use this tool to think about something. It will not obtain new information or perform changes, but will put your thought into a log, so that it is accessible to you. Use it for complex reasoning or as memory cache when you need to store some temporary information that you may consider useful to complete the task.", type = "object")
	public String think(
		@ToolParam(name = "thought", description = "A thought or information worth using in solving a task", required = true) String thought)
	{
		return thought;
	}

	@Tool(name = "completion_meta", description = "Internal sink for code completion. Use this tool to output any non-code text (markdown, explanations, reasoning, meta commentary) instead of writing it into the completion CONTENT stream. The code completion CONTENT stream must contain ONLY the exact source code to insert.", type = "object")
	public String completionMeta(
		@ToolParam(name = "text", description = "Non-code meta text that should not appear in the completion output", required = true) String text)
	{
		return text;
	}
}

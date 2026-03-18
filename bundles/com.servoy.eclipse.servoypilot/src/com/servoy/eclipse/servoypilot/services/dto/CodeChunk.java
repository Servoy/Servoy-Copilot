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
package com.servoy.eclipse.servoypilot.services.dto;

/**
 * Represents a chunk of code read from a JavaScript file.
 * Used by CodeChunkReader to return file content in manageable portions (max 200 lines).
 */
public class CodeChunk
{
	private final String filePath;
	private final int startLine;
	private final int endLine;
	private final int totalChunks;
	private final int chunkNumber; // -1 for direct mode (not chunk-based)
	private final String content;
	private final boolean isLast;

	public CodeChunk(String filePath, int startLine, int endLine, int totalChunks, int chunkNumber, String content, boolean isLast)
	{
		this.filePath = filePath;
		this.startLine = startLine;
		this.endLine = endLine;
		this.totalChunks = totalChunks;
		this.chunkNumber = chunkNumber;
		this.content = content;
		this.isLast = isLast;
	}

	public String getFilePath()
	{
		return filePath;
	}

	public int getStartLine()
	{
		return startLine;
	}

	public int getEndLine()
	{
		return endLine;
	}

	public int getTotalChunks()
	{
		return totalChunks;
	}

	public int getChunkNumber()
	{
		return chunkNumber;
	}

	public String getContent()
	{
		return content;
	}

	public boolean isLast()
	{
		return isLast;
	}

	/**
	 * Format for AI tool output.
	 */
	public String toFormattedString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("=== CODE CHUNK ===\n\n");
		sb.append("FILE: ").append(filePath).append("\n");
		sb.append("LINES: ").append(startLine).append("-").append(endLine).append("\n");

		if (chunkNumber >= 0)
		{
			sb.append("CHUNK: ").append(chunkNumber + 1).append(" of ").append(totalChunks).append("\n");
		}
		else
		{
			sb.append("TOTAL CHUNKS: ").append(totalChunks).append("\n");
		}

		if (isLast)
		{
			sb.append("(LAST CHUNK)\n");
		}

		sb.append("\n--- CODE ---\n");
		sb.append(content);

		if (!content.endsWith("\n"))
		{
			sb.append("\n");
		}

		sb.append("--- END CODE ---\n");

		return sb.toString();
	}
}

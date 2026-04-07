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
package com.servoy.eclipse.servoypilot.tools.filereading;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IReadFileLinesTool
{
	@Tool("Reads specific lines from a file. Use this for large files or when you only need to see a specific section.")
	default String readFileLines(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Starting line number (1-based). If omitted, starts from beginning.", required = false) Integer startLine,
		@P(value = "Ending line number (1-based, inclusive). If omitted, reads to end or max 500 lines.", required = false) Integer endLine)
	{
		FileReadingToolsHelper helper = FileReadingToolsHelper.getInstance();
		try
		{
			IFile file = helper.resolveFile(filePath);
			if (file == null || !file.exists())
			{
				return helper.createErrorResponse("File not found: " + filePath);
			}

			if (!file.isSynchronized(IResource.DEPTH_ZERO))
			{
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}

			String[] allLines = helper.getCachedFileLines(file);
			int totalLines = allLines.length;

			int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
			int end = (endLine != null && endLine > 0) ? endLine : Math.min(start + 500, totalLines);

			if (start >= totalLines)
			{
				return helper.createErrorResponse("Start line " + (start + 1) + " exceeds file length (" + totalLines + " lines)");
			}

			end = Math.min(end, totalLines);

			StringBuilder content = new StringBuilder();
			for (int i = start; i < end; i++)
			{
				content.append(i + 1).append(": ").append(allLines[i]);
				if (i < end - 1)
				{
					content.append("\n");
				}
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(totalLines),
				"startLine", String.valueOf(start + 1),
				"endLine", String.valueOf(end),
				"linesReturned", String.valueOf(end - start),
				"content", content.toString());
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error reading file lines: " + e.getMessage());
		}
	}
}

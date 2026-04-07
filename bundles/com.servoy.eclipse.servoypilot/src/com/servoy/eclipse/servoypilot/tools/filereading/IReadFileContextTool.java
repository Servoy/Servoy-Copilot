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

public interface IReadFileContextTool
{
	@Tool("Reads lines around a specific line number (smart windowing). " +
		"Perfect for analyzing errors at a specific line without reading the entire file. " +
		"Returns lines from centerLine-windowSize to centerLine+windowSize.")
	default String readFileContext(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "The line number to center the reading window on (1-based)", required = true) int centerLine,
		@P(value = "Number of lines to read before and after centerLine. Default is 30.", required = false) Integer windowSize)
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

			if (centerLine < 1 || centerLine > totalLines)
			{
				return helper.createErrorResponse("Center line " + centerLine + " is out of bounds (file has " + totalLines + " lines)");
			}

			int window = (windowSize != null && windowSize > 0) ? windowSize : 30;
			int startLine = Math.max(1, centerLine - window);
			int endLine = Math.min(totalLines, centerLine + window);
			int startIndex = startLine - 1;
			int endIndex = endLine;

			StringBuilder content = new StringBuilder();
			for (int i = startIndex; i < endIndex; i++)
			{
				content.append(i + 1).append(": ").append(allLines[i]);
				if (i < endIndex - 1)
				{
					content.append("\n");
				}
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(totalLines),
				"centerLine", String.valueOf(centerLine),
				"windowSize", String.valueOf(window),
				"startLine", String.valueOf(startLine),
				"endLine", String.valueOf(endLine),
				"linesReturned", String.valueOf(endLine - startLine + 1),
				"content", content.toString());
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error reading file context: " + e.getMessage());
		}
	}
}

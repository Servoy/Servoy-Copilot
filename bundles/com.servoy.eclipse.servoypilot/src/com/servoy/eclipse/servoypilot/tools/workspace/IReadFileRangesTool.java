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
package com.servoy.eclipse.servoypilot.tools.workspace;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IReadFileRangesTool
{
	@Tool("Reads multiple non-contiguous line ranges from a file in a single call. " +
		"Useful for reading several error locations or stack trace lines at once without multiple tool calls. " +
		"Provide ranges as comma-separated pairs: '10-20,50-60,100-110'")
	default String readFileRanges(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Comma-separated line ranges in format 'start1-end1,start2-end2' (e.g., '10-20,50-60')", required = true) String ranges)
	{
		WorkspaceToolsHelper helper = WorkspaceToolsHelper.getInstance();
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

			String[] rangePairs = ranges.split(",");
			StringBuilder content = new StringBuilder();
			int totalLinesRead = 0;
			int rangeCount = 0;

			for (String rangePair : rangePairs)
			{
				rangePair = rangePair.trim();
				String[] parts = rangePair.split("-");

				if (parts.length != 2)
				{
					return helper.createErrorResponse("Invalid range format: '" + rangePair + "'. Expected format: 'start-end'");
				}

				try
				{
					int start = Integer.parseInt(parts[0].trim());
					int end = Integer.parseInt(parts[1].trim());

					if (start < 1 || start > totalLines)
					{
						return helper.createErrorResponse("Start line " + start + " is out of bounds (file has " + totalLines + " lines)");
					}
					if (end < start)
					{
						return helper.createErrorResponse("End line " + end + " is less than start line " + start);
					}

					end = Math.min(end, totalLines);

					if (rangeCount > 0)
					{
						content.append("\n--- Range ").append(rangeCount + 1).append(" ---\n");
					}

					for (int i = start - 1; i < end; i++)
					{
						content.append(i + 1).append(": ").append(allLines[i]);
						if (i < end - 1)
						{
							content.append("\n");
						}
						totalLinesRead++;
					}

					rangeCount++;
				}
				catch (NumberFormatException e)
				{
					return helper.createErrorResponse("Invalid line number in range: '" + rangePair + "'");
				}
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(totalLines),
				"rangesRequested", String.valueOf(rangeCount),
				"linesReturned", String.valueOf(totalLinesRead),
				"content", content.toString());
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error reading file ranges: " + e.getMessage());
		}
	}
}

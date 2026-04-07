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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetFileInfoTool
{
	@Tool("Gets metadata about a file without reading its full content. Use this to check file size before reading.")
	default String getFileInfo(
		@P(value = "File path relative to workspace or project", required = true) String filePath)
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

			long size = file.getLocation().toFile().length();

			int lineCount = 0;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				while (reader.readLine() != null)
				{
					lineCount++;
				}
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"name", file.getName(),
				"extension", file.getFileExtension() != null ? file.getFileExtension() : "",
				"size", String.valueOf(size),
				"lines", String.valueOf(lineCount),
				"readable", String.valueOf(size <= FileReadingToolsHelper.MAX_FILE_SIZE),
				"lastModified", String.valueOf(file.getLocalTimeStamp()));
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error getting file info: " + e.getMessage());
		}
	}
}

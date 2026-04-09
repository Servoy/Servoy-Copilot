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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IReadFileTool
{
	@Tool("Reads the full content of a file from the workspace. Use this to understand complete file structure.")
	default String readFile(
		@P(value = "File path relative to workspace or project (e.g., 'forms/myForm.js' or 'projectName/forms/myForm.js')", required = true) String filePath)
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

			long size = file.getLocation().toFile().length();
			if (size > WorkspaceToolsHelper.MAX_FILE_SIZE)
			{
				return helper.createErrorResponse("File too large (" + size + " bytes). Use readFileLines to read specific sections.");
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				String[] lines = reader.lines().toArray(String[]::new);
				int lineCount = lines.length;

				StringBuilder numberedContent = new StringBuilder();
				for (int i = 0; i < lines.length; i++)
				{
					numberedContent.append(i + 1).append(": ").append(lines[i]);
					if (i < lines.length - 1)
					{
						numberedContent.append("\n");
					}
				}

				return helper.buildJsonResponse(true, null,
					"filePath", file.getFullPath().toString(),
					"project", file.getProject().getName(),
					"lines", String.valueOf(lineCount),
					"size", String.valueOf(size),
					"content", numberedContent.toString());
			}
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error reading file: " + e.getMessage());
		}
	}
}

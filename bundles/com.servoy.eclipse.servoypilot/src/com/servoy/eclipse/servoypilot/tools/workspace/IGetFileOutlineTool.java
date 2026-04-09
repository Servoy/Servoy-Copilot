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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetFileOutlineTool
{
	@Tool("Gets an outline of functions/methods in a file without reading full content. " +
		"Returns function names with their starting line numbers. " +
		"Useful for navigating large files or tracing stack traces.")
	default String getFileOutline(
		@P(value = "File path relative to workspace or project", required = true) String filePath)
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

			Pattern functionPattern = Pattern.compile(
				"^\\s*(?:function\\s+(\\w+)|(?:var|let|const)\\s+(\\w+)\\s*=\\s*function|(?:async\\s+)?function\\s+(\\w+)|(\\w+)\\s*:\\s*function)");

			StringBuilder outline = new StringBuilder();
			int functionCount = 0;

			for (int i = 0; i < allLines.length; i++)
			{
				Matcher matcher = functionPattern.matcher(allLines[i]);
				if (matcher.find())
				{
					String functionName = null;
					for (int g = 1; g <= matcher.groupCount(); g++)
					{
						if (matcher.group(g) != null)
						{
							functionName = matcher.group(g);
							break;
						}
					}

					if (functionName != null)
					{
						outline.append("Line ").append(i + 1).append(": ").append(functionName).append("()\n");
						functionCount++;
					}
				}
			}

			if (functionCount == 0)
			{
				outline.append("No functions found in file.");
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(allLines.length),
				"functionsFound", String.valueOf(functionCount),
				"outline", outline.toString().trim());
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error getting file outline: " + e.getMessage());
		}
	}
}

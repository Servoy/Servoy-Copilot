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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IReadFunctionTool
{
	@Tool("Reads a complete function/method definition from a file by function name. " +
		"Finds the function and returns all its lines. " +
		"Useful for understanding a specific function mentioned in stack traces.")
	default String readFunction(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Name of the function to read (without parentheses)", required = true) String functionName)
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

			Pattern functionPattern = Pattern.compile(
				"^\\s*(?:function\\s+" + Pattern.quote(functionName) +
				"|(?:var|let|const)\\s+" + Pattern.quote(functionName) + "\\s*=\\s*function" +
				"|(?:async\\s+)?function\\s+" + Pattern.quote(functionName) +
				"|" + Pattern.quote(functionName) + "\\s*:\\s*function)");

			int functionStartLine = -1;
			for (int i = 0; i < allLines.length; i++)
			{
				if (functionPattern.matcher(allLines[i]).find())
				{
					functionStartLine = i;
					break;
				}
			}

			if (functionStartLine == -1)
			{
				return helper.createErrorResponse("Function '" + functionName + "' not found in file");
			}

			int braceCount = 0;
			int functionEndLine = functionStartLine;
			boolean inFunction = false;

			for (int i = functionStartLine; i < allLines.length; i++)
			{
				for (char c : allLines[i].toCharArray())
				{
					if (c == '{')
					{
						braceCount++;
						inFunction = true;
					}
					else if (c == '}')
					{
						braceCount--;
						if (inFunction && braceCount == 0)
						{
							functionEndLine = i;
							break;
						}
					}
				}

				if (inFunction && braceCount == 0)
				{
					break;
				}
			}

			StringBuilder content = new StringBuilder();
			for (int i = functionStartLine; i <= functionEndLine; i++)
			{
				content.append(i + 1).append(": ").append(allLines[i]);
				if (i < functionEndLine)
				{
					content.append("\n");
				}
			}

			return helper.buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"functionName", functionName,
				"startLine", String.valueOf(functionStartLine + 1),
				"endLine", String.valueOf(functionEndLine + 1),
				"linesReturned", String.valueOf(functionEndLine - functionStartLine + 1),
				"content", content.toString());
		}
		catch (Exception e)
		{
			return helper.createErrorResponse("Error reading function: " + e.getMessage());
		}
	}
}

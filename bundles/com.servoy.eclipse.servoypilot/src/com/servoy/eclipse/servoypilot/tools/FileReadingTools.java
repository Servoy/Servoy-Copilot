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
package com.servoy.eclipse.servoypilot.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for reading file contents from the workspace.
 * Allows AI assistants to read files, file chunks, and get file metadata.
 */
public class FileReadingTools
{
	private static final int MAX_FILE_SIZE = 100000; // 100KB limit for safety

	@Tool("Reads the full content of a file from the workspace. Use this to understand complete file structure.")
	public String readFile(
		@P(value = "File path relative to workspace or project (e.g., 'forms/myForm.js' or 'projectName/forms/myForm.js')", required = true) String filePath)
	{
		try
		{
			IFile file = resolveFile(filePath);
			if (file == null || !file.exists())
			{
				return createErrorResponse("File not found: " + filePath);
			}

			if (!file.isSynchronized(IResource.DEPTH_ZERO))
			{
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}

			// Check file size
			long size = file.getLocation().toFile().length();
			if (size > MAX_FILE_SIZE)
			{
				return createErrorResponse("File too large (" + size + " bytes). Use readFileLines to read specific sections.");
			}

			// Read file content
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				String content = reader.lines().collect(Collectors.joining("\n"));
				int lineCount = content.split("\n").length;

				// Build JSON manually to avoid Jackson access restrictions
				return buildJsonResponse(true, null, 
					"filePath", file.getFullPath().toString(),
					"project", file.getProject().getName(),
					"lines", String.valueOf(lineCount),
					"size", String.valueOf(size),
					"content", content);
			}
		}
		catch (Exception e)
		{
			return createErrorResponse("Error reading file: " + e.getMessage());
		}
	}

	@Tool("Reads specific lines from a file. Use this for large files or when you only need to see a specific section.")
	public String readFileLines(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Starting line number (1-based). If omitted, starts from beginning.", required = false) Integer startLine,
		@P(value = "Ending line number (1-based, inclusive). If omitted, reads to end or max 500 lines.", required = false) Integer endLine)
	{
		try
		{
			IFile file = resolveFile(filePath);
			if (file == null || !file.exists())
			{
				return createErrorResponse("File not found: " + filePath);
			}

			if (!file.isSynchronized(IResource.DEPTH_ZERO))
			{
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}

			// Read all lines
			String[] allLines;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				allLines = reader.lines().toArray(String[]::new);
			}

			int totalLines = allLines.length;

			// Parse line range (1-based indexing)
			int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
			int end = (endLine != null && endLine > 0) ? endLine : Math.min(start + 500, totalLines);

			// Validate range
			if (start >= totalLines)
			{
				return createErrorResponse("Start line " + (start + 1) + " exceeds file length (" + totalLines + " lines)");
			}

			end = Math.min(end, totalLines);

			// Extract lines
			StringBuilder content = new StringBuilder();
			for (int i = start; i < end; i++)
			{
				content.append(allLines[i]);
				if (i < end - 1)
				{
					content.append("\n");
				}
			}

			// Build JSON manually to avoid Jackson access restrictions
			return buildJsonResponse(true, null,
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
			return createErrorResponse("Error reading file lines: " + e.getMessage());
		}
	}

	@Tool("Gets metadata about a file without reading its full content. Use this to check file size before reading.")
	public String getFileInfo(
		@P(value = "File path relative to workspace or project", required = true) String filePath)
	{
		try
		{
			IFile file = resolveFile(filePath);
			if (file == null || !file.exists())
			{
				return createErrorResponse("File not found: " + filePath);
			}

			if (!file.isSynchronized(IResource.DEPTH_ZERO))
			{
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}

			long size = file.getLocation().toFile().length();

			// Count lines efficiently
			int lineCount = 0;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				while (reader.readLine() != null)
				{
					lineCount++;
				}
			}

			// Build JSON manually to avoid Jackson access restrictions
			return buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"name", file.getName(),
				"extension", file.getFileExtension() != null ? file.getFileExtension() : "",
				"size", String.valueOf(size),
				"lines", String.valueOf(lineCount),
				"readable", String.valueOf(size <= MAX_FILE_SIZE),
				"lastModified", String.valueOf(file.getLocalTimeStamp()));
		}
		catch (Exception e)
		{
			return createErrorResponse("Error getting file info: " + e.getMessage());
		}
	}

	/**
	 * Resolves a file path to an IFile.
	 * Handles both absolute workspace paths and relative project paths.
	 */
	private IFile resolveFile(String filePath)
	{
		if (filePath == null || filePath.trim().isEmpty())
		{
			return null;
		}

		filePath = filePath.trim();
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		// Try as workspace-relative path first (e.g., "/projectName/path/to/file.js")
		IFile file = root.getFile(new Path(filePath));
		if (file.exists())
		{
			return file;
		}

		// Try without leading slash
		if (filePath.startsWith("/"))
		{
			file = root.getFile(new Path(filePath.substring(1)));
			if (file.exists())
			{
				return file;
			}
		}

		// Try as project-relative path in active project
		try
		{
			IServoyModel servoyModel = ServoyModelFinder.getServoyModel();
			if (servoyModel != null && servoyModel.getActiveProject() != null)
			{
				IProject activeProject = servoyModel.getActiveProject().getProject();
				file = activeProject.getFile(new Path(filePath));
				if (file.exists())
				{
					return file;
				}
			}
		}
		catch (Exception e)
		{
			// Continue trying other methods
		}

		// Try splitting path into project/rest
		int firstSlash = filePath.indexOf('/');
		if (firstSlash > 0)
		{
			String projectName = filePath.substring(0, firstSlash);
			String restOfPath = filePath.substring(firstSlash + 1);

			IProject project = root.getProject(projectName);
			if (project.exists())
			{
				file = project.getFile(new Path(restOfPath));
				if (file.exists())
				{
					return file;
				}
			}
		}

		return null;
	}

	private String createErrorResponse(String message)
	{
		return buildJsonResponse(false, message);
	}

	/**
	 * Build a simple JSON response manually to avoid Jackson access restrictions in Eclipse.
	 * @param success whether the operation succeeded
	 * @param errorMessage error message if success is false (can be null)
	 * @param keyValuePairs pairs of key-value strings (key1, value1, key2, value2, ...)
	 * @return JSON string
	 */
	private String buildJsonResponse(boolean success, String errorMessage, String... keyValuePairs)
	{
		StringBuilder json = new StringBuilder("{");
		json.append("\"success\":").append(success);
		
		if (!success && errorMessage != null)
		{
			json.append(",\"error\":").append(jsonEscape(errorMessage));
		}
		
		// Add key-value pairs
		for (int i = 0; i < keyValuePairs.length; i += 2)
		{
			if (i + 1 < keyValuePairs.length)
			{
				String key = keyValuePairs[i];
				String value = keyValuePairs[i + 1];
				json.append(",\"").append(key).append("\":");
				
				// Check if value is a number or boolean
				if (value != null && (value.equals("true") || value.equals("false") || value.matches("\\d+")))
				{
					json.append(value);
				}
				else
				{
					json.append(jsonEscape(value != null ? value : ""));
				}
			}
		}
		
		json.append("}");
		return json.toString();
	}

	/**
	 * Escape a string for JSON (handles quotes, newlines, etc.)
	 */
	private String jsonEscape(String text)
	{
		if (text == null)
		{
			return "\"\"";
		}
		
		StringBuilder escaped = new StringBuilder("\"");
		for (char c : text.toCharArray())
		{
			switch (c)
			{
				case '"' :
					escaped.append("\\\"");
					break;
				case '\\' :
					escaped.append("\\\\");
					break;
				case '\n' :
					escaped.append("\\n");
					break;
				case '\r' :
					escaped.append("\\r");
					break;
				case '\t' :
					escaped.append("\\t");
					break;
				default :
					if (c < 32)
					{
						escaped.append(String.format("\\u%04x", (int)c));
					}
					else
					{
						escaped.append(c);
					}
			}
		}
		escaped.append("\"");
		return escaped.toString();
	}
}

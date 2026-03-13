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
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
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
	private static final int CACHE_SIZE = 50; // Cache up to 50 files

	// LRU cache for file contents (key: file path, value: lines array + timestamp)
	private static final Map<String, CachedFileContent> fileCache = new LinkedHashMap<String, CachedFileContent>(CACHE_SIZE, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CachedFileContent> eldest)
		{
			return size() > CACHE_SIZE;
		}
	};

	/**
	 * Cached file content with timestamp for invalidation
	 */
	private static class CachedFileContent
	{
		final String[] lines;
		final long timestamp;

		CachedFileContent(String[] lines, long timestamp)
		{
			this.lines = lines;
			this.timestamp = timestamp;
		}
	}

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

			// Read file content WITH LINE NUMBERS for easier AI parsing
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				String[] lines = reader.lines().toArray(String[]::new);
				int lineCount = lines.length;
				
				// Add line numbers to each line
				StringBuilder numberedContent = new StringBuilder();
				for (int i = 0; i < lines.length; i++)
				{
					numberedContent.append(i + 1).append(": ").append(lines[i]);
					if (i < lines.length - 1)
					{
						numberedContent.append("\n");
					}
				}

				// Build JSON manually to avoid Jackson access restrictions
				return buildJsonResponse(true, null, 
					"filePath", file.getFullPath().toString(),
					"project", file.getProject().getName(),
					"lines", String.valueOf(lineCount),
					"size", String.valueOf(size),
					"content", numberedContent.toString());
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

			// Use cached file reading
			String[] allLines = getCachedFileLines(file);
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

			// Extract lines WITH LINE NUMBERS for easier AI parsing
			StringBuilder content = new StringBuilder();
			for (int i = start; i < end; i++)
			{
				int lineNumber = i + 1; // Convert to 1-based line number
				content.append(lineNumber).append(": ").append(allLines[i]);
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

	@Tool("Reads lines around a specific line number (smart windowing). " +
		"Perfect for analyzing errors at a specific line without reading the entire file. " +
		"Returns lines from centerLine-windowSize to centerLine+windowSize.")
	public String readFileContext(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "The line number to center the reading window on (1-based)", required = true) int centerLine,
		@P(value = "Number of lines to read before and after centerLine. Default is 30.", required = false) Integer windowSize)
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

		// Use cached file reading
		String[] allLines = getCachedFileLines(file);
			int totalLines = allLines.length;
			if (centerLine < 1 || centerLine > totalLines)
			{
				return createErrorResponse("Center line " + centerLine + " is out of bounds (file has " + totalLines + " lines)");
			}

			// Default window size is 30 lines before and after
			int window = (windowSize != null && windowSize > 0) ? windowSize : 30;

			// Calculate range (1-based line numbers, convert to 0-based array indices)
			int startLine = Math.max(1, centerLine - window);
			int endLine = Math.min(totalLines, centerLine + window);

			// Convert to 0-based indices for array access
			int startIndex = startLine - 1;
			int endIndex = endLine;

			// Extract lines WITH LINE NUMBERS
			StringBuilder content = new StringBuilder();
			for (int i = startIndex; i < endIndex; i++)
			{
				int lineNumber = i + 1; // Convert back to 1-based line number
				content.append(lineNumber).append(": ").append(allLines[i]);
				if (i < endIndex - 1)
				{
					content.append("\n");
				}
			}

			// Build JSON response
			return buildJsonResponse(true, null,
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
			return createErrorResponse("Error reading file context: " + e.getMessage());
		}
	}

	@Tool("Gets an outline of functions/methods in a file without reading full content. " +
		"Returns function names with their starting line numbers. " +
		"Useful for navigating large files or tracing stack traces.")
	public String getFileOutline(
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

			// Use cached file reading
			String[] allLines = getCachedFileLines(file);

			// Find function definitions using regex patterns
			// Supports: function name(...), name: function(...), var name = function(...)
			java.util.regex.Pattern functionPattern = java.util.regex.Pattern.compile(
				"^\\s*(?:function\\s+(\\w+)|(?:var|let|const)\\s+(\\w+)\\s*=\\s*function|(?:async\\s+)?function\\s+(\\w+)|(\\w+)\\s*:\\s*function)");

			StringBuilder outline = new StringBuilder();
			int functionCount = 0;

			for (int i = 0; i < allLines.length; i++)
			{
				String line = allLines[i];
				java.util.regex.Matcher matcher = functionPattern.matcher(line);
				
				if (matcher.find())
				{
					// Extract function name (can be in different capture groups)
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
						int lineNumber = i + 1;
						outline.append("Line ").append(lineNumber).append(": ").append(functionName).append("()\n");
						functionCount++;
					}
				}
			}

			if (functionCount == 0)
			{
				outline.append("No functions found in file.");
			}

			return buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(allLines.length),
				"functionsFound", String.valueOf(functionCount),
				"outline", outline.toString().trim());
		}
		catch (Exception e)
		{
			return createErrorResponse("Error getting file outline: " + e.getMessage());
		}
	}

	@Tool("Reads a complete function/method definition from a file by function name. " +
		"Finds the function and returns all its lines. " +
		"Useful for understanding a specific function mentioned in stack traces.")
	public String readFunction(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Name of the function to read (without parentheses)", required = true) String functionName)
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

			// Use cached file reading
			String[] allLines = getCachedFileLines(file);

			// Find function start
			java.util.regex.Pattern functionPattern = java.util.regex.Pattern.compile(
				"^\\s*(?:function\\s+" + java.util.regex.Pattern.quote(functionName) + 
				"|(?:var|let|const)\\s+" + java.util.regex.Pattern.quote(functionName) + "\\s*=\\s*function" +
				"|(?:async\\s+)?function\\s+" + java.util.regex.Pattern.quote(functionName) +
				"|" + java.util.regex.Pattern.quote(functionName) + "\\s*:\\s*function)");

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
				return createErrorResponse("Function '" + functionName + "' not found in file");
			}

			// Find function end by counting braces
			int braceCount = 0;
			int functionEndLine = functionStartLine;
			boolean inFunction = false;

			for (int i = functionStartLine; i < allLines.length; i++)
			{
				String line = allLines[i];
				
				for (char c : line.toCharArray())
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

			// Extract function lines WITH LINE NUMBERS
			StringBuilder content = new StringBuilder();
			for (int i = functionStartLine; i <= functionEndLine; i++)
			{
				int lineNumber = i + 1;
				content.append(lineNumber).append(": ").append(allLines[i]);
				if (i < functionEndLine)
				{
					content.append("\n");
				}
			}

			return buildJsonResponse(true, null,
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
			return createErrorResponse("Error reading function: " + e.getMessage());
		}
	}

	@Tool("Reads multiple non-contiguous line ranges from a file in a single call. " +
		"Useful for reading several error locations or stack trace lines at once without multiple tool calls. " +
		"Provide ranges as comma-separated pairs: '10-20,50-60,100-110'")
	public String readFileRanges(
		@P(value = "File path relative to workspace or project", required = true) String filePath,
		@P(value = "Comma-separated line ranges in format 'start1-end1,start2-end2' (e.g., '10-20,50-60')", required = true) String ranges)
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

			// Use cached file reading
			String[] allLines = getCachedFileLines(file);
			int totalLines = allLines.length;

			// Parse ranges
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
					return createErrorResponse("Invalid range format: '" + rangePair + "'. Expected format: 'start-end'");
				}

				try
				{
					int start = Integer.parseInt(parts[0].trim());
					int end = Integer.parseInt(parts[1].trim());

					// Validate range
					if (start < 1 || start > totalLines)
					{
						return createErrorResponse("Start line " + start + " is out of bounds (file has " + totalLines + " lines)");
					}
					if (end < start)
					{
						return createErrorResponse("End line " + end + " is less than start line " + start);
					}

					end = Math.min(end, totalLines);

					// Add range header
					if (rangeCount > 0)
					{
						content.append("\n--- Range ").append(rangeCount + 1).append(" ---\n");
					}

					// Extract lines WITH LINE NUMBERS
					for (int i = start - 1; i < end; i++)
					{
						int lineNumber = i + 1;
						content.append(lineNumber).append(": ").append(allLines[i]);
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
					return createErrorResponse("Invalid line number in range: '" + rangePair + "'");
				}
			}

			return buildJsonResponse(true, null,
				"filePath", file.getFullPath().toString(),
				"project", file.getProject().getName(),
				"totalLines", String.valueOf(totalLines),
				"rangesRequested", String.valueOf(rangeCount),
				"linesReturned", String.valueOf(totalLinesRead),
				"content", content.toString());
		}
		catch (Exception e)
		{
			return createErrorResponse("Error reading file ranges: " + e.getMessage());
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
	 * Gets cached file lines or reads from disk if not cached/stale.
	 * Invalidates cache if file modification timestamp changed.
	 */
	private String[] getCachedFileLines(IFile file) throws Exception
	{
		String cacheKey = file.getFullPath().toString();
		long currentTimestamp = file.getLocalTimeStamp();

		synchronized (fileCache)
		{
			CachedFileContent cached = fileCache.get(cacheKey);
			
			// Check if cache is valid (exists and timestamp matches)
			if (cached != null && cached.timestamp == currentTimestamp)
			{
				return cached.lines;
			}

			// Cache miss or stale - read from disk
			String[] lines;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				lines = reader.lines().toArray(String[]::new);
			}

			// Update cache
			fileCache.put(cacheKey, new CachedFileContent(lines, currentTimestamp));
			return lines;
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

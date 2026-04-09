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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;

/**
 * Singleton helper providing shared logic for all workspace tool interfaces.
 * Covers file resolution, LRU caching, JSON building, and input parsing utilities.
 */
public class WorkspaceToolsHelper
{
	public static final int MAX_FILE_SIZE = 100000; // 100KB limit for safety
	private static final int CACHE_SIZE = 50; // Cache up to 50 files

	private static final WorkspaceToolsHelper INSTANCE = new WorkspaceToolsHelper();

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
	 * Cached file content with timestamp for invalidation.
	 */
	public static class CachedFileContent
	{
		public final String[] lines;
		public final long timestamp;

		public CachedFileContent(String[] lines, long timestamp)
		{
			this.lines = lines;
			this.timestamp = timestamp;
		}
	}

	private WorkspaceToolsHelper()
	{
	}

	public static WorkspaceToolsHelper getInstance()
	{
		return INSTANCE;
	}

	// -------------------------------------------------------------------------
	// File reading / caching
	// -------------------------------------------------------------------------

	/**
	 * Gets cached file lines or reads from disk if not cached/stale.
	 * Invalidates cache if file modification timestamp changed.
	 */
	public String[] getCachedFileLines(IFile file) throws Exception
	{
		String cacheKey = file.getFullPath().toString();
		long currentTimestamp = file.getLocalTimeStamp();

		synchronized (fileCache)
		{
			CachedFileContent cached = fileCache.get(cacheKey);

			if (cached != null && cached.timestamp == currentTimestamp)
			{
				return cached.lines;
			}

			String[] lines;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				lines = reader.lines().toArray(String[]::new);
			}

			fileCache.put(cacheKey, new CachedFileContent(lines, currentTimestamp));
			return lines;
		}
	}

	/**
	 * Resolves a file path to an IFile.
	 * Handles workspace-relative, project-relative, and active-project paths.
	 */
	public IFile resolveFile(String filePath)
	{
		if (filePath == null || filePath.trim().isEmpty())
		{
			return null;
		}

		filePath = filePath.trim();
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		IFile file = root.getFile(new Path(filePath));
		if (file.exists())
		{
			return file;
		}

		if (filePath.startsWith("/"))
		{
			file = root.getFile(new Path(filePath.substring(1)));
			if (file.exists())
			{
				return file;
			}
		}

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
			// continue trying other methods
		}

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

	// -------------------------------------------------------------------------
	// Input parsing
	// -------------------------------------------------------------------------

	/**
	 * Normalizes file name patterns from JSON array, quoted string, or comma-separated input.
	 */
	public String[] normalizeFileNamePatterns(String fileNamePatterns)
	{
		if (fileNamePatterns == null || fileNamePatterns.isBlank())
		{
			return new String[0];
		}

		fileNamePatterns = fileNamePatterns.trim();

		if (fileNamePatterns.startsWith("[") && fileNamePatterns.endsWith("]"))
		{
			try
			{
				ObjectMapper mapper = new ObjectMapper();
				return mapper.readValue(fileNamePatterns, String[].class);
			}
			catch (Exception e)
			{
				// fall through to comma-separated handling
			}
		}

		if (fileNamePatterns.startsWith("\"") && fileNamePatterns.endsWith("\""))
		{
			fileNamePatterns = fileNamePatterns.substring(1, fileNamePatterns.length() - 1);
		}

		String[] split = fileNamePatterns.split(",");
		List<String> result = new ArrayList<>();
		for (String s : split)
		{
			String clean = s.trim();
			if (!clean.isEmpty())
			{
				result.add(clean);
			}
		}
		return result.toArray(new String[0]);
	}

	// -------------------------------------------------------------------------
	// JSON utilities
	// -------------------------------------------------------------------------

	public String createErrorResponse(String message)
	{
		return buildJsonResponse(false, message);
	}

	/**
	 * Builds a simple JSON response manually to avoid Jackson access restrictions in Eclipse.
	 */
	public String buildJsonResponse(boolean success, String errorMessage, String... keyValuePairs)
	{
		StringBuilder json = new StringBuilder("{");
		json.append("\"success\":").append(success);

		if (!success && errorMessage != null)
		{
			json.append(",\"error\":").append(jsonEscape(errorMessage));
		}

		for (int i = 0; i < keyValuePairs.length; i += 2)
		{
			if (i + 1 < keyValuePairs.length)
			{
				String key = keyValuePairs[i];
				String value = keyValuePairs[i + 1];
				json.append(",\"").append(key).append("\":");

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
	 * Escapes a string for use as a JSON value.
	 */
	public String jsonEscape(String text)
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

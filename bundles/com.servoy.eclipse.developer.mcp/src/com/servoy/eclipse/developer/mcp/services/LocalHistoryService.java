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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Provides Eclipse Local History operations for MCP tools.
 * <p>
 * Ported from AssistAI's {@code LocalHistoryService}. Differences:
 * <ul>
 *   <li>No {@code AiIgnoreService} â access control is not needed in Servoy Developer MCP</li>
 *   <li>No {@code UISynchronize} / editor refresh â the MCP server runs headless</li>
 *   <li>{@code restoreFileVersion} is intentionally not implemented (see {@code ServoyContextServer})</li>
 * </ul>
 * </p>
 */
public class LocalHistoryService
{
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.systemDefault());

	/**
	 * Lists Local History versions of a file.
	 *
	 * @param projectName the Eclipse project name
	 * @param filePath    path relative to the project root
	 * @param maxEntries  maximum entries to show (default 20); pass null for default
	 * @return formatted table of history entries
	 */
	public String getFileHistory(String projectName, String filePath, String maxEntries)
	{
		IFile file = resolveFile(projectName, filePath);

		int limit = 20;
		if (maxEntries != null && !maxEntries.isBlank())
		{
			try
			{
				limit = Integer.parseInt(maxEntries.trim());
			}
			catch (NumberFormatException e)
			{
				// keep default
			}
		}

		try
		{
			IFileState[] history = file.getHistory(null);
			if (history == null || history.length == 0)
			{
				return "No local history found for " + filePath;
			}

			StringBuilder sb = new StringBuilder();
			sb.append("# Local History for ").append(filePath).append("\n\n");
			sb.append(String.format("%-6s  %-20s  %s\n", "Index", "Timestamp", "Size"));
			sb.append("-".repeat(50)).append("\n");

			int count = Math.min(history.length, limit);
			for (int i = 0; i < count; i++)
			{
				IFileState state = history[i];
				Instant ts = Instant.ofEpochMilli(state.getModificationTime());
				String size;
				try
				{
					size = state.exists() ? formatSize(state.getContents().available()) : "deleted";
				}
				catch (Exception e)
				{
					size = "unknown";
				}
				sb.append(String.format("%-6d  %-20s  %s\n", i, TIMESTAMP_FMT.format(ts), size));
			}

			if (history.length > count)
			{
				sb.append("\n(").append(history.length - count).append(" older entries not shown)\n");
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error reading local history for " + filePath + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Returns the content of a specific Local History version.
	 *
	 * @param projectName the Eclipse project name
	 * @param filePath    path relative to the project root
	 * @param index       0-based index (0 = most recent)
	 * @return file content with line numbers
	 */
	public String getFileHistoryContent(String projectName, String filePath, String index)
	{
		IFile file = resolveFile(projectName, filePath);
		int idx = parseIndex(index);

		try
		{
			IFileState[] history = file.getHistory(null);
			if (history == null || history.length == 0)
			{
				return "No local history found for " + filePath;
			}
			if (idx < 0 || idx >= history.length)
			{
				return "Invalid index " + idx + ". Valid range: 0-" + (history.length - 1);
			}

			IFileState state = history[idx];
			Instant ts = Instant.ofEpochMilli(state.getModificationTime());
			String content = new String(readInputStream(state.getContents()),
				Charset.forName(file.getCharset()));

			StringBuilder sb = new StringBuilder();
			sb.append("# ").append(filePath).append(" @ ").append(TIMESTAMP_FMT.format(ts)).append("\n\n");

			String[] lines = content.split("\n", -1);
			for (int i = 0; i < lines.length; i++)
			{
				sb.append(String.format("%5d\t%s\n", i + 1, lines[i]));
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error reading history content: " + e.getMessage(), e);
		}
	}

	/**
	 * Shows a unified diff between the current file and a Local History version.
	 *
	 * @param projectName the Eclipse project name
	 * @param filePath    path relative to the project root
	 * @param index       0-based index (0 = most recent)
	 * @return unified diff text
	 */
	public String compareWithHistory(String projectName, String filePath, String index)
	{
		IFile file = resolveFile(projectName, filePath);
		int idx = parseIndex(index);

		try
		{
			IFileState[] history = file.getHistory(null);
			if (history == null || history.length == 0)
			{
				return "No local history found for " + filePath;
			}
			if (idx < 0 || idx >= history.length)
			{
				return "Invalid index " + idx + ". Valid range: 0-" + (history.length - 1);
			}

			IFileState state = history[idx];
			Instant ts = Instant.ofEpochMilli(state.getModificationTime());

			String charset = file.getCharset();
			String oldContent = new String(readInputStream(state.getContents()), Charset.forName(charset));
			String newContent = new String(readInputStream(file.getContents()), Charset.forName(charset));

			String[] oldLines = oldContent.split("\n", -1);
			String[] newLines = newContent.split("\n", -1);

			StringBuilder sb = new StringBuilder();
			sb.append("# Diff: ").append(filePath).append("\n");
			sb.append("# Current vs. ").append(TIMESTAMP_FMT.format(ts))
				.append(" (index ").append(idx).append(")\n\n");
			sb.append("--- ").append(filePath).append(" (").append(TIMESTAMP_FMT.format(ts)).append(")\n");
			sb.append("+++ ").append(filePath).append(" (current)\n");

			appendSimpleDiff(sb, oldLines, newLines);

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error comparing with history: " + e.getMessage(), e);
		}
	}

	// --- private helpers ---

	private IFile resolveFile(String projectName, String filePath)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");

		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists() || !project.isOpen())
		{
			throw new RuntimeException("Project '" + projectName + "' not found or not open");
		}

		IFile file = project.getFile(IPath.fromPath(java.nio.file.Path.of(filePath)));
		if (!file.exists())
		{
			throw new RuntimeException("File '" + filePath + "' not found in project '" + projectName + "'");
		}
		return file;
	}

	private int parseIndex(String index)
	{
		Objects.requireNonNull(index, "index is required");
		try
		{
			return Integer.parseInt(index.trim());
		}
		catch (NumberFormatException e)
		{
			throw new RuntimeException("Invalid index: " + index);
		}
	}

	private static byte[] readInputStream(InputStream inputStream) throws IOException
	{
		ByteArrayOutputStream result = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		int length;
		while ((length = inputStream.read(buffer)) != -1)
		{
			result.write(buffer, 0, length);
		}
		return result.toByteArray();
	}

	private void appendSimpleDiff(StringBuilder sb, String[] oldLines, String[] newLines)
	{
		int maxLen = Math.max(oldLines.length, newLines.length);
		int contextSize = 3;
		boolean inHunk = false;
		int hunkStart = -1;

		for (int i = 0; i < maxLen; i++)
		{
			String oldLine = i < oldLines.length ? oldLines[i] : null;
			String newLine = i < newLines.length ? newLines[i] : null;
			boolean different = !Objects.equals(oldLine, newLine);

			if (different)
			{
				if (!inHunk)
				{
					int start = Math.max(0, i - contextSize);
					sb.append(String.format("@@ -%d +%d @@\n", start + 1, start + 1));
					for (int c = start; c < i; c++)
					{
						sb.append(" ").append(c < oldLines.length ? oldLines[c] : "").append("\n");
					}
					inHunk = true;
					hunkStart = i;
				}
				if (oldLine != null) sb.append("-").append(oldLine).append("\n");
				if (newLine != null) sb.append("+").append(newLine).append("\n");
			}
			else if (inHunk)
			{
				if (i - hunkStart > contextSize * 2)
				{
					for (int c = 0; c < contextSize && (hunkStart + c) < i; c++)
					{
						int ci = hunkStart + c;
						if (ci < oldLines.length)
						{
							sb.append(" ").append(oldLines[ci]).append("\n");
						}
					}
					inHunk = false;
				}
				else
				{
					sb.append(" ").append(oldLine).append("\n");
					hunkStart = i;
				}
			}
		}
	}

	private String formatSize(long bytes)
	{
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}
}

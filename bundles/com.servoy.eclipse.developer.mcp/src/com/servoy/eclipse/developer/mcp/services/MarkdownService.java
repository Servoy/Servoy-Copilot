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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

/**
 * Provides Markdown outline and section reading for MCP tools.
 * <p>
 * Ported from AssistAI's {@code MarkdownService} â no changes needed, pure Eclipse resources API.
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class MarkdownService
{
	private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
	private static final Pattern SETEXT_H1 = Pattern.compile("^={3,}\\s*$");
	private static final Pattern SETEXT_H2 = Pattern.compile("^-{3,}\\s*$");

	private record HeadingInfo(int level, String text, int lineNumber) {}

	private List<String> readFileLines(String projectName, String resourcePath)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Project not found: " + projectName);
		if (!project.isOpen())
			throw new RuntimeException("Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("File not found: " + resourcePath);

		try
		{
			return WorkspaceService.readFileLines(file);
		}
		catch (IOException | CoreException e)
		{
			throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
		}
	}

	private List<HeadingInfo> parseHeadings(List<String> lines)
	{
		List<HeadingInfo> headings = new ArrayList<>();
		boolean inCodeFence = false;

		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			String trimmed = line.trim();

			if (trimmed.startsWith("```") || trimmed.startsWith("~~~"))
			{
				inCodeFence = !inCodeFence;
				continue;
			}
			if (inCodeFence) continue;

			Matcher atxMatcher = ATX_HEADING.matcher(line);
			if (atxMatcher.matches())
			{
				int level = atxMatcher.group(1).length();
				String text = atxMatcher.group(2).trim();
				headings.add(new HeadingInfo(level, text, i + 1));
				continue;
			}

			if (!trimmed.isEmpty() && i + 1 < lines.size())
			{
				String nextLine = lines.get(i + 1).trim();
				if (SETEXT_H1.matcher(nextLine).matches())
					headings.add(new HeadingInfo(1, trimmed, i + 1));
				else if (SETEXT_H2.matcher(nextLine).matches())
					headings.add(new HeadingInfo(2, trimmed, i + 1));
			}
		}

		return headings;
	}

	public String getOutline(String projectName, String resourcePath)
	{
		List<String> lines = readFileLines(projectName, resourcePath);
		List<HeadingInfo> headings = parseHeadings(lines);

		if (headings.isEmpty())
			return "No headings found in " + resourcePath + " (" + lines.size() + " lines total).";

		var sb = new StringBuilder();
		sb.append("Markdown outline of ").append(resourcePath)
			.append(" (").append(lines.size()).append(" lines total)\n\n");

		for (int i = 0; i < headings.size(); i++)
		{
			HeadingInfo h = headings.get(i);
			int endLine = (i + 1 < headings.size()) ? headings.get(i + 1).lineNumber() - 1 : lines.size();
			int sectionLines = endLine - h.lineNumber() + 1;
			String indent = "  ".repeat(h.level() - 1);
			sb.append(indent).append("#".repeat(h.level())).append(" ").append(h.text())
				.append("  [line ").append(h.lineNumber()).append(", ").append(sectionLines).append(" lines]\n");
		}

		return sb.toString();
	}

	public String getSection(String projectName, String resourcePath, String heading, boolean includeSubsections)
	{
		List<String> lines = readFileLines(projectName, resourcePath);
		List<HeadingInfo> headings = parseHeadings(lines);

		if (headings.isEmpty())
			return "No headings found in " + resourcePath;

		HeadingInfo target = null;
		int targetIndex = -1;

		try
		{
			int index = Integer.parseInt(heading.trim()) - 1;
			if (index >= 0 && index < headings.size())
			{
				target = headings.get(index);
				targetIndex = index;
			}
		}
		catch (NumberFormatException e)
		{
			// Not a number â search by text
		}

		if (target == null)
		{
			String headingLower = heading.toLowerCase().trim();
			for (int i = 0; i < headings.size(); i++)
			{
				if (headings.get(i).text().toLowerCase().contains(headingLower))
				{
					target = headings.get(i);
					targetIndex = i;
					break;
				}
			}
		}

		if (target == null)
			return "Heading not found: '" + heading + "'. Use getMarkdownOutline to see available headings.";

		int startLine = target.lineNumber();
		int endLine = lines.size();

		for (int i = targetIndex + 1; i < headings.size(); i++)
		{
			HeadingInfo next = headings.get(i);
			if (includeSubsections)
			{
				if (next.level() <= target.level())
				{
					endLine = next.lineNumber() - 1;
					break;
				}
			}
			else
			{
				endLine = next.lineNumber() - 1;
				break;
			}
		}

		int width = String.valueOf(lines.size()).length();
		var sb = new StringBuilder();
		for (int i = startLine - 1; i < endLine && i < lines.size(); i++)
			sb.append(String.format("%" + width + "d\t%s\n", i + 1, lines.get(i)));

		return sb.toString();
	}
}

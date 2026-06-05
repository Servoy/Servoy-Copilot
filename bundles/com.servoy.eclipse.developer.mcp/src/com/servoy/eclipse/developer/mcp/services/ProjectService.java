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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * Provides project layout, properties, and listing for MCP tools.
 * <p>
 * Ported from AssistAI's {@code ProjectService}. Differences:
 * <ul>
 *   <li>No JDT dependency â Java-specific nature detection is replaced with generic nature listing.</li>
 *   <li>No {@code AiIgnoreService}.</li>
 * </ul>
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class ProjectService
{
	public String listProjects()
	{
		StringBuilder result = new StringBuilder();
		result.append("# Available Projects in Workspace\n\n");

		try
		{
			IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
			if (projects.length == 0)
				return "No projects found in the workspace.";

			for (IProject project : projects)
			{
				result.append("- **").append(project.getName()).append("**");
				result.append(" (").append(project.isOpen() ? "Open" : "Closed").append(")");

				if (project.isOpen())
				{
					try
					{
						String[] natures = project.getDescription().getNatureIds();
						if (natures.length > 0)
						{
							// Detect common natures for display
							List<String> detected = new ArrayList<>();
							for (String nature : natures)
							{
								if (nature.contains("jdt.core")) detected.add("Java");
								else if (nature.contains("m2e")) detected.add("Maven");
								else if (nature.contains("pde")) detected.add("PDE");
								else if (nature.contains("ServoyProject")) detected.add("Servoy");
								else if (nature.contains("wst.common.project.facet")) detected.add("Web");
							}
							if (!detected.isEmpty())
								result.append(" - Project Type: ").append(String.join(", ", detected));
							else
								result.append(" - Other Nature IDs: ").append(String.join(", ", natures));
						}
						else
						{
							result.append(" - Generic Project (no specific natures)");
						}
					}
					catch (CoreException e)
					{
						result.append(" - Error determining project nature");
					}
				}
				result.append("\n");
			}
		}
		catch (Exception e)
		{
			return "Error retrieving projects: " + e.getMessage();
		}

		return result.toString();
	}

	public String openProject(String directoryPath)
	{
		try
		{
			File directory = new File(directoryPath);
			if (!directory.exists())
				return "Error: Directory does not exist: " + directoryPath;
			if (!directory.isDirectory())
				return "Error: Path is not a directory: " + directoryPath;

			IProgressMonitor monitor = new NullProgressMonitor();
			File projectFile = new File(directory, ".project");

			IProjectDescription description;
			if (projectFile.exists())
			{
				org.eclipse.core.runtime.IPath projectFilePath = org.eclipse.core.runtime.Path.fromOSString(projectFile.getAbsolutePath());
				description = ResourcesPlugin.getWorkspace().loadProjectDescription(projectFilePath);
			}
			else
			{
				String projectName = directory.getName();
				description = ResourcesPlugin.getWorkspace().newProjectDescription(projectName);
				org.eclipse.core.runtime.IPath locationPath = org.eclipse.core.runtime.Path.fromOSString(directory.getAbsolutePath());
				description.setLocation(locationPath);
			}

			String projectName = description.getName();
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);

			if (project.exists())
			{
				if (!project.isOpen())
				{
					project.open(monitor);
					return "Project '" + projectName + "' was already in workspace but closed. Opened successfully.";
				}
				return "Project '" + projectName + "' is already open in the workspace.";
			}

			project.create(description, monitor);
			project.open(monitor);

			return "Project '" + projectName + "' imported and opened successfully from: " + directoryPath;
		}
		catch (CoreException e)
		{
			return "Error opening project: " + e.getMessage();
		}
	}

	public String getProjectLayout(String projectName, String scopePath, int maxDepth)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			return "Project '" + projectName + "' not found.";

		StringBuilder result = new StringBuilder();
		try
		{
			IResource startResource = project;
			if (scopePath != null && !scopePath.isBlank())
			{
				IResource scoped = project.findMember(scopePath);
				if (scoped == null || !scoped.exists())
					return "Error: Path '" + scopePath + "' not found in project '" + projectName + "'.";
				startResource = scoped;
				result.append("# Project Structure: ").append(projectName).append("/").append(scopePath).append("\n\n");
			}
			else
			{
				result.append("# Project Structure: ").append(projectName).append("\n\n");
			}

			int effectiveMaxDepth = maxDepth > 0 ? maxDepth : Integer.MAX_VALUE;
			collectResources(startResource, 0, effectiveMaxDepth, result);
		}
		catch (CoreException e)
		{
			return "Error retrieving project layout: " + e.getMessage();
		}

		return result.toString();
	}

	public String getProjectProperties(String projectName)
	{
		try
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (project == null || !project.exists())
				return "Error: Project '" + projectName + "' not found.";
			if (!project.isOpen())
				return "Error: Project '" + projectName + "' is closed.";

			StringBuilder result = new StringBuilder();
			result.append("# Project Properties: ").append(projectName).append("\n\n");

			String[] natures = project.getDescription().getNatureIds();
			result.append("## Project Natures\n");
			for (String nature : natures)
				result.append("- ").append(nature).append("\n");
			result.append("\n");

			result.append("## Build Commands\n");
			Arrays.stream(project.getDescription().getBuildSpec())
				.forEach(cmd -> result.append("- ").append(cmd.getBuilderName()).append("\n"));
			result.append("\n");

			result.append("## Referenced Projects\n");
			IProject[] refs = project.getReferencedProjects();
			if (refs.length == 0)
				result.append("(none)\n");
			else
				Arrays.stream(refs).forEach(ref -> result.append("- ").append(ref.getName()).append("\n"));

			return result.toString();
		}
		catch (CoreException e)
		{
			return "Error retrieving project properties: " + e.getMessage();
		}
	}

	// --- Private helpers ---

	private static void collectResources(IResource resource, int depth, int maxDepth, StringBuilder result)
		throws CoreException
	{
		if (depth > maxDepth) return;

		String indent = "  ".repeat(depth);
		if (resource instanceof IContainer container)
		{
			if (depth > 0)
				result.append(indent).append(resource.getName()).append("/\n");

			IResource[] members = container.members();
			Arrays.sort(members, (a, b) -> {
				// Folders first, then files
				boolean aIsContainer = a instanceof IContainer;
				boolean bIsContainer = b instanceof IContainer;
				if (aIsContainer != bIsContainer) return aIsContainer ? -1 : 1;
				return a.getName().compareToIgnoreCase(b.getName());
			});

			for (IResource member : members)
			{
				// Skip hidden resources
				if (member.getName().startsWith(".")) continue;
				collectResources(member, depth + 1, maxDepth, result);
			}
		}
		else
		{
			result.append(indent).append(resource.getName()).append("\n");
		}
	}
}

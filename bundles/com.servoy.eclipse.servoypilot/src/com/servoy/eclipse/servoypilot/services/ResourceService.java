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
package com.servoy.eclipse.servoypilot.services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

import com.servoy.eclipse.servoypilot.dto.ResourceToolResult;
import com.servoy.eclipse.servoypilot.util.ResourceUtilities;

public class ResourceService
{
	private static final ILog logger = ILog.of(ResourceService.class);

	private static final ResourceService INSTANCE = new ResourceService();

	public static ResourceService getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Finds workspace files matching the given glob patterns.
	 *
	 * @param fileNamePatterns Glob patterns (e.g. "*.java", "pom.xml"). If omitted, defaults to "*".
	 * @param maxResults Maximum number of results to return (<=0 means default 200)
	 * @return List of workspace-relative file paths
	 */
	public List<String> findFiles(String[] fileNamePatterns, Integer maxResults)
	{
		Pattern fileNamePattern = ResourceUtilities.globPatternsToRegex(fileNamePatterns);

		int limit = (maxResults == null || maxResults <= 0) ? 200 : maxResults.intValue();

		IResource[] roots = getOpenProjectsAsRoots();
		if (roots.length == 0)
		{
			return List.of();
		}

		TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
		TextSearchEngine engine = TextSearchEngine.createDefault();

		List<String> matches = new ArrayList<>();

		TextSearchRequestor requestor = new TextSearchRequestor()
		{
			@Override
			public boolean acceptFile(IFile file) throws CoreException
			{
				if (matches.size() >= limit)
				{
					return false; // stop accepting more files
				}

				return file != null && file.isAccessible();
			}

			@Override
			public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
			{
				// We only need the file, not match positions.
				IFile file = matchAccess.getFile();
				if (file != null)
				{
					String path = file.getFullPath().toString();
					if (!matches.contains(path))
					{
						matches.add(path);
					}
				}

				return matches.size() < limit;
			}
		};

		try
		{
			// Search for a pattern that matches at least one char, to force the engine to scan.
			// (We only care about file enumeration, the fileNamePattern already limits the scope.)
			engine.search(scope, requestor, Pattern.compile("."), null);
			return matches;
		}
		catch (Exception e)
		{
			logger.error("Error finding files: " + e.getMessage(), e);
			throw new RuntimeException("Error finding files: " + ExceptionUtils.getRootCauseMessage(e), e);
		}
	}

	private static IResource[] getOpenProjectsAsRoots()
	{
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		List<IResource> roots = new ArrayList<>();
		for (IProject project : projects)
		{
			if (project != null && project.exists() && project.isOpen())
			{
				roots.add(project);
			}
		}
		return roots.toArray(IResource[]::new);
	}

	/**
	 * Reads the content of a text resource from a specified project.
	 * 
	 * @param projectName The name of the project containing the resource
	 * @param filePath The path to the resource file relative to the project root
	 * @return The content of the resource as a formatted string
	 */
	public String readProjectResource(String projectName, String filePath)
	{
		// Get the project
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
		{
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		}

		if (!project.isOpen())
		{
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
		}

		// Get the resource
		IPath path = IPath.fromPath(Path.of(filePath));
		IFile file = project.getFile(path);

		if (!file.exists())
		{
			throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
		}
		try
		{
			String lang = ResourceUtilities.getResourceFileType(file);
			// Prepare the response
			StringBuilder response = new StringBuilder();
			response.append("# Content of ").append(filePath).append(" in project ").append(projectName).append("\n\n");
			response.append("```");
			response.append(lang);
			response.append(ResourceUtilities.readFileContent(file));
			response.append("\n```\n");
			return response.toString();

		}
		catch (IOException | CoreException e)
		{
			throw new RuntimeException(e);
		}

	}

	/**
	 * Reads the content of a text resource with resource metadata for caching.
	 * 
	 * @param projectName The name of the project containing the resource
	 * @param filePath The path to the resource file relative to the project root
	 * @return ResourceToolResult with content and cacheable descriptor,
	 *         or a transient result if there was an error
	 */
	public ResourceToolResult readProjectResourceWithResource(String projectName, String filePath)
	{
		final String toolName = "readProjectResource";

		// Get the project
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
		{
			return ResourceToolResult.transientResult("Error: Project '" + projectName + "' not found.", toolName);
		}

		if (!project.isOpen())
		{
			return ResourceToolResult.transientResult("Error: Project '" + projectName + "' is closed.", toolName);
		}

		// Get the resource
		IPath path = IPath.fromPath(Path.of(filePath));
		IFile file = project.getFile(path);

		if (!file.exists())
		{
			return ResourceToolResult.transientResult(
				"Error: File '" + filePath + "' does not exist in project '" + projectName + "'.", toolName);
		}

		try
		{
			String lang = ResourceUtilities.getResourceFileType(file);

			// Prepare the response
			StringBuilder content = new StringBuilder();
			content.append("# Content of ").append(filePath).append(" in project ").append(projectName).append("\n\n");
			content.append("```").append(lang).append("\n");
			content.append(ResourceUtilities.readFileContent(file));
			content.append("\n```\n");

			// Return cacheable result with IFile reference
			return ResourceToolResult.fromFile(file, content.toString(), toolName);

		}
		catch (IOException | CoreException e)
		{
			logger.error("Error reading resource: " + e.getMessage(), e);
			return ResourceToolResult.transientResult("Error reading file: " + e.getMessage(), toolName);
		}
	}

	/**
	 * Gets all problems (errors and warnings) from the Eclipse Problems view for workspace resources.
	 * 
	 * @param severity Filter by severity: "ERROR", "WARNING", "INFO", or null for all
	 * @param projectName Optional project name to limit results to a specific project
	 * @param filePattern Optional file pattern (glob) to filter files, e.g., "*.js"
	 * @param maxResults Maximum number of results to return (default: 100)
	 * @return List of problem descriptions with file, line, severity, and message in the format [SEVERITY] /path/to/file:lineNumber - message
	 */
	public List<String> getProblems(String severity, String projectName, String filePattern, Integer maxResults)
	{
		List<String> problems = new ArrayList<>();
		int limit = (maxResults == null || maxResults <= 0) ? 100 : maxResults.intValue();

		try
		{
			// Determine the severity filter
			int severityMask = 0;
			if (severity != null && !severity.isBlank())
			{
				String sev = severity.trim().toUpperCase();
				switch (sev)
				{
					case "ERROR" :
						severityMask = IMarker.SEVERITY_ERROR;
						break;
					case "WARNING" :
						severityMask = IMarker.SEVERITY_WARNING;
						break;
					case "INFO" :
						severityMask = IMarker.SEVERITY_INFO;
						break;
					default :
						// Invalid severity - treat as all
						severityMask = -1;
				}
			}

			// Determine the scope (specific project or all projects)
			IResource[] roots;
			if (projectName != null && !projectName.isBlank())
			{
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.trim());
				if (project == null || !project.exists() || !project.isOpen())
				{
					return List.of("Error: Project '" + projectName + "' not found or not open.");
				}
				roots = new IResource[] { project };
			}
			else
			{
				roots = getOpenProjectsAsRoots();
			}

			if (roots.length == 0)
			{
				return List.of("No open projects found.");
			}

			// Compile file pattern if provided
			Pattern fileNamePattern = null;
			if (filePattern != null && !filePattern.isBlank())
			{
				fileNamePattern = ResourceUtilities.globPatternsToRegex(new String[] { filePattern.trim() });
			}

			// Collect markers from all resources
			for (IResource root : roots)
			{
				if (problems.size() >= limit)
				{
					break;
				}

				IMarker[] markers = root.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

				for (IMarker marker : markers)
				{
					if (problems.size() >= limit)
					{
						break;
					}

					// Filter by severity if specified
					if (severityMask > 0)
					{
						int markerSeverity = marker.getAttribute(IMarker.SEVERITY, -1);
						if (markerSeverity != severityMask)
						{
							continue;
						}
					}

					// Filter by file pattern if specified
					IResource resource = marker.getResource();
					if (fileNamePattern != null && resource instanceof IFile)
					{
						String fileName = resource.getName();
						if (!fileNamePattern.matcher(fileName).matches())
						{
							continue;
						}
					}

					// Extract marker information
					String message = marker.getAttribute(IMarker.MESSAGE, "");
					int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
					int markerSeverity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					String severityStr = getSeverityString(markerSeverity);
					String filePath = resource.getFullPath().toString();

					// Format: [SEVERITY] file:line - message
					String problem = String.format("[%s] %s:%d - %s",
						severityStr,
						filePath,
						lineNumber > 0 ? lineNumber : 0,
						message);

					problems.add(problem);
				}
			}

			if (problems.isEmpty())
			{
				return List.of("No problems found matching the specified criteria.");
			}

			return problems;
		}
		catch (CoreException e)
		{
			logger.error("Error retrieving problems: " + e.getMessage(), e);
			return List.of("Error retrieving problems: " + ExceptionUtils.getRootCauseMessage(e));
		}
	}

	/**
	 * Helper method to convert severity integer to string
	 */
	private String getSeverityString(int severity)
	{
		switch (severity)
		{
			case IMarker.SEVERITY_ERROR :
				return "ERROR";
			case IMarker.SEVERITY_WARNING :
				return "WARNING";
			case IMarker.SEVERITY_INFO :
				return "INFO";
			default :
				return "UNKNOWN";
		}
	}

}

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
package com.servoy.eclipse.developer.mcp.servers;

import java.util.List;
import java.util.Optional;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchAndReplaceResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResult;

/**
 * MCP server providing IDE integration tools for the Servoy Developer MCP endpoint.
 * <p>
 * Endpoint: {@code /svymcp/servoy-ide}
 * </p>
 * <p>
 * Provides project browsing, file reading, text search, markdown navigation,
 * editor state, console output, and compilation error queries.
 * </p>
 * <p>
 * {@code searchAndReplace} is the only destructive tool; it delegates guard checks
 * to {@link WorkspaceService} which aborts the entire batch if any matched file
 * is a Servoy structural file.
 * </p>
 * <p>
 * Excluded (Java/JDT-only): {@code getSource}, {@code getClassOutline}, {@code getMethodSource},
 * {@code getFilteredSource}, {@code getJavaDoc}, {@code getTypeHierarchy}, {@code getMethodCallHierarchy},
 * {@code findReferences}, {@code getImportSuggestions}, {@code executeQuickFix}.
 * </p>
 */
@McpServer(name = "servoy-ide")
public class ServoyIdeServer
{
	private final ProjectService projectService = new ProjectService();
	private final WorkspaceService workspaceService = new WorkspaceService();
	private final MarkdownService markdownService = new MarkdownService();
	private final IdeStateService ideStateService = new IdeStateService();

	@Tool(name = "listProjects",
		description = "List all available projects in the workspace with their detected natures (Java, Maven, Servoy, etc.).",
		type = "object")
	public String listProjects()
	{
		return projectService.listProjects();
	}

	@Tool(name = "getProjectLayout",
		description = "Get the file and folder structure of a specified project in a hierarchical format. "
			+ "For large projects, use scopePath to limit to a subdirectory and/or maxDepth to limit tree depth.",
		type = "object")
	public String getProjectLayout(
		@ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName,
		@ToolParam(name = "scopePath", description = "Optional path relative to the project root to limit the listing (e.g., 'src/main/java/com/example'). If omitted, shows the entire project.", required = false) String scopePath,
		@ToolParam(name = "maxDepth", description = "Optional maximum depth of the directory tree to display (e.g., '3' for 3 levels deep). If omitted, shows all levels.", required = false) String maxDepth)
	{
		int depth = Optional.ofNullable(maxDepth).map(Integer::parseInt).orElse(-1);
		return projectService.getProjectLayout(projectName, scopePath, depth);
	}

	@Tool(name = "getProjectProperties",
		description = "Retrieves the properties and configuration of a specified project.",
		type = "object")
	public String getProjectProperties(
		@ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName)
	{
		return projectService.getProjectProperties(projectName);
	}

	@Tool(name = "readProjectResource",
		description = "Read the content of a text resource from a specified project. "
			+ "Supports line numbers and reading specific line ranges.",
		type = "object")
	public String readProjectResource(
		@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
		@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
		@ToolParam(name = "showLineNumbers", description = "If 'true', prepends line numbers to each line. Default: 'false'", required = false) String showLineNumbers,
		@ToolParam(name = "startLine", description = "Optional 1-based start line to read from. If omitted, reads from the beginning.", required = false) String startLine,
		@ToolParam(name = "endLine", description = "Optional 1-based end line to read to (inclusive). If omitted, reads to the end.", required = false) String endLine)
	{
		boolean lineNumbers = Optional.ofNullable(showLineNumbers).map(Boolean::parseBoolean).orElse(false);
		int start = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(0);
		int end = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(0);
		return workspaceService.readProjectResource(projectName, resourcePath, lineNumbers, start, end);
	}

	@Tool(name = "findFiles",
		description = "Finds workspace files matching the given glob patterns.",
		type = "object")
	public String findFiles(
		@ToolParam(name = "fileNamePatterns", description = "Comma-separated glob patterns (e.g. '*.java, *.xml'). If omitted, defaults to '*'.", required = false) String fileNamePatterns,
		@ToolParam(name = "maxResults", description = "Maximum number of results to return (default: 200)", required = false) String maxResults)
	{
		String[] patterns = parsePatterns(fileNamePatterns);
		int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(200);
		List<String> files = workspaceService.findFiles(patterns, limit);

		if (files.isEmpty()) return "No files found matching the specified patterns.";

		StringBuilder result = new StringBuilder();
		result.append("# Found ").append(files.size()).append(" file(s)\n\n");
		for (String file : files)
			result.append("- ").append(file).append("\n");
		return result.toString();
	}

	@Tool(name = "fileSearch",
		description = "Searches for a plain substring in workspace files using Eclipse's text search engine.",
		type = "object")
	public String fileSearch(
		@ToolParam(name = "containingText", description = "Text that must be contained in a line (plain substring, not regex)", required = true) String containingText,
		@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchResult> results = workspaceService.fileSearch(containingText, patterns);
		return formatSearchResults(results);
	}

	@Tool(name = "fileSearchRegExp",
		description = "Searches workspace files using a Java regular expression via Eclipse's text search engine.",
		type = "object")
	public String fileSearchRegExp(
		@ToolParam(name = "pattern", description = "Java regular expression", required = true) String pattern,
		@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchResult> results = workspaceService.fileSearchRegExp(pattern, patterns);
		return formatSearchResults(results);
	}

	@Tool(name = "searchAndReplace",
		description = "Search and replace across multiple files in the workspace using Eclipse's text search engine. "
			+ "Aborts the entire operation if any matched file is a Servoy structural file (.frm, .obj, .tbl, .val, .rel, .dbi).",
		type = "object")
	public String searchAndReplace(
		@ToolParam(name = "containingText", description = "Plain text to find (not regex)", required = true) String containingText,
		@ToolParam(name = "replacementText", description = "Replacement text (can be empty)", required = true) String replacementText,
		@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchAndReplaceResult> results = workspaceService.searchAndReplace(containingText, replacementText, patterns);

		if (results.isEmpty()) return "No matches found for '" + containingText + "'.";

		StringBuilder sb = new StringBuilder();
		sb.append("# Search and Replace Results\n\n");
		int totalReplacements = 0;
		for (SearchAndReplaceResult r : results)
		{
			sb.append("- ").append(r.filePath()).append(": ").append(r.replacementsMade()).append(" replacement(s)\n");
			totalReplacements += r.replacementsMade();
		}
		sb.append("\nTotal: ").append(totalReplacements).append(" replacement(s) in ").append(results.size()).append(" file(s).\n");
		return sb.toString();
	}

	@Tool(name = "getMarkdownOutline",
		description = "Returns the heading structure (table of contents) of a Markdown file with line numbers and section sizes. "
			+ "Use this to understand a large Markdown document before fetching specific sections with getMarkdownSection.",
		type = "object")
	public String getMarkdownOutline(
		@ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
		@ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root (e.g., 'docs/README.md')", required = true) String resourcePath)
	{
		return markdownService.getOutline(projectName, resourcePath);
	}

	@Tool(name = "getMarkdownSection",
		description = "Reads a specific section from a Markdown file by heading name or index. "
			+ "Returns the section content with line numbers. Use getMarkdownOutline first to see available headings.",
		type = "object")
	public String getMarkdownSection(
		@ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
		@ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root", required = true) String resourcePath,
		@ToolParam(name = "heading", description = "The heading to find - either a 1-based index from the outline, or a text substring to match (case-insensitive)", required = true) String heading,
		@ToolParam(name = "includeSubsections", description = "If 'true', includes all subsections under the matched heading. If 'false', returns only the content up to the next heading of any level. Default: true", required = false) String includeSubsections)
	{
		boolean includeSubs = Optional.ofNullable(includeSubsections).map(Boolean::parseBoolean).orElse(true);
		return markdownService.getSection(projectName, resourcePath, heading, includeSubs);
	}

	@Tool(name = "getCurrentlyOpenedFile",
		description = "Gets information about the currently active file in the Eclipse editor.",
		type = "object")
	public String getCurrentlyOpenedFile()
	{
		return ideStateService.getCurrentlyOpenedFile();
	}

	@Tool(name = "getEditorSelection",
		description = "Gets the currently selected text or lines in the active editor.",
		type = "object")
	public String getEditorSelection()
	{
		return ideStateService.getEditorSelection();
	}

	@Tool(name = "getConsoleOutput",
		description = "Retrieves the recent output from Eclipse console(s).",
		type = "object")
	public String getConsoleOutput(
		@ToolParam(name = "consoleName", description = "Name of the specific console to retrieve (optional, leave empty for most recent console)", required = false) String consoleName,
		@ToolParam(name = "maxLines", description = "Maximum number of lines to retrieve (default: 100)", required = false) String maxLines,
		@ToolParam(name = "includeAllConsoles", description = "Whether to include output from all available consoles (default: false)", required = false) String includeAllConsoles)
	{
		int lines = Optional.ofNullable(maxLines).map(Integer::parseInt).orElse(100);
		boolean allConsoles = Optional.ofNullable(includeAllConsoles).map(Boolean::parseBoolean).orElse(false);
		return ideStateService.getConsoleOutput(consoleName, lines, allConsoles);
	}

	@Tool(name = "getCompilationErrors",
		description = "Retrieves compilation errors and problems from the current workspace or a specific project.",
		type = "object")
	public String getCompilationErrors(
		@ToolParam(name = "projectName", description = "The name of the specific project to check (optional, leave empty for all projects)", required = false) String projectName,
		@ToolParam(name = "severity", description = "Filter by severity level: 'ERROR', 'WARNING', or 'ALL' (default)", required = false) String severity,
		@ToolParam(name = "maxResults", description = "Maximum number of problems to return (default: 50)", required = false) String maxResults)
	{
		int max = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(50);
		return ideStateService.getCompilationErrors(projectName, severity, max);
	}

	// --- Private helpers ---

	private static String[] parsePatterns(String fileNamePatterns)
	{
		if (fileNamePatterns == null || fileNamePatterns.isBlank()) return new String[0];
		String[] parts = fileNamePatterns.split(",");
		String[] trimmed = new String[parts.length];
		for (int i = 0; i < parts.length; i++)
			trimmed[i] = parts[i].trim();
		return trimmed;
	}

	private static String formatSearchResults(List<SearchResult> results)
	{
		if (results.isEmpty()) return "No matches found.";

		StringBuilder sb = new StringBuilder();
		sb.append("# Search Results (").append(results.size()).append(" match(es))\n\n");
		for (SearchResult r : results)
		{
			sb.append("- ").append(r.filePath()).append(":").append(r.lineNumber());
			sb.append(" â ").append(r.lineContent().trim()).append("\n");
		}
		return sb.toString();
	}
}

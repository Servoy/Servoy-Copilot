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

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.FileStructureService;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchAndReplaceResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResult;

/**
 * MCP server providing IDE integration tools for the Servoy Developer MCP
 * endpoint.
 * <p>
 * Endpoint: {@code /mcp/servoy-ide}
 * </p>
 * <p>
 * Provides project browsing, file reading, text search, markdown navigation,
 * editor state, console output, and compilation error queries.
 * </p>
 * <p>
 * {@code searchAndReplace} is the only destructive tool; it delegates guard
 * checks to {@link WorkspaceService} which aborts the entire batch if any
 * matched file is a Servoy structural file.
 * </p>
 * <p>
 * Excluded (Java/JDT-only, removed): {@code getJavaDoc},
 * {@code getImportSuggestions}, {@code getTypeHierarchy},
 * {@code getMethodCallHierarchy}, {@code findReferences},
 * {@code executeQuickFix}.
 * </p>
 */
@Creatable
@McpServer(name = "servoy-ide")
public class ServoyIdeServer {
	@Inject
	private ProjectService projectService;
	@Inject
	private WorkspaceService workspaceService;
	@Inject
	private MarkdownService markdownService;
	@Inject
	private IdeStateService ideStateService;
	@Inject
	private ServoyScriptResolver servoyScriptResolver;
	@Inject
	private FileStructureService fileStructureService;

	/** Default constructor - required by E4 DI (ContextInjectionFactory.make). */
	public ServoyIdeServer() {
	}

	/** Testing constructor - initialises services directly without E4 DI. */
	public ServoyIdeServer(ProjectService projectService, WorkspaceService workspaceService,
			MarkdownService markdownService, IdeStateService ideStateService) {
		this.projectService = projectService;
		this.workspaceService = workspaceService;
		this.markdownService = markdownService;
		this.ideStateService = ideStateService;
		this.servoyScriptResolver = new ServoyScriptResolver();
		this.fileStructureService = new FileStructureService();
	}

	@Tool(name = "listProjects", description = "List all available projects in the workspace with their detected natures (Java, Maven, Servoy, etc.).", type = "object")
	public String listProjects() {
		return projectService.listProjects();
	}

	@Tool(name = "openProject", description = "Opens/imports a project into the Eclipse workspace from a directory path. If the directory contains a .project file, it imports the project as-is. If not, a basic .project is created and the directory is imported as a generic project.", type = "object")
	public String openProject(
			@ToolParam(name = "directoryPath", description = "The absolute filesystem path to the directory to open as a project", required = true) String directoryPath) {
		return projectService.openProject(directoryPath);
	}

	@Tool(name = "getProjectLayout", description = "Get the file and folder structure of a specified project in a hierarchical format. "
			+ "For large projects, use scopePath to limit to a subdirectory and/or maxDepth to limit tree depth.", type = "object")
	public String getProjectLayout(
			@ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName,
			@ToolParam(name = "scopePath", description = "Optional path relative to the project root to limit the listing (e.g., 'src/main/java/com/example'). If omitted, shows the entire project.", required = false) String scopePath,
			@ToolParam(name = "maxDepth", description = "Optional maximum depth of the directory tree to display (e.g., '3' for 3 levels deep). If omitted, shows all levels.", required = false) String maxDepth) {
		int depth = Optional.ofNullable(maxDepth).map(Integer::parseInt).orElse(-1);
		return projectService.getProjectLayout(projectName, scopePath, depth);
	}

	@Tool(name = "getProjectProperties", description = "Retrieves the properties and configuration of a specified project.", type = "object")
	public String getProjectProperties(
			@ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName) {
		return projectService.getProjectProperties(projectName);
	}

	@Tool(name = "readProjectResource", description = "Read the content of a text resource from a specified project. "
			+ "Supports line numbers and reading specific line ranges.", type = "object")
	public String readProjectResource(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
			@ToolParam(name = "showLineNumbers", description = "If 'true', prepends line numbers to each line. Default: 'false'", required = false) String showLineNumbers,
			@ToolParam(name = "startLine", description = "Optional 1-based start line to read from. If omitted, reads from the beginning.", required = false) String startLine,
			@ToolParam(name = "endLine", description = "Optional 1-based end line to read to (inclusive). If omitted, reads to the end.", required = false) String endLine) {
		boolean lineNumbers = Optional.ofNullable(showLineNumbers).map(Boolean::parseBoolean).orElse(false);
		int start = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(0);
		int end = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(0);
		return workspaceService.readProjectResource(projectName, resourcePath, lineNumbers, start, end);
	}

	@Tool(name = "getFileInfo", description = "Gets metadata about a file without reading its full content: size in bytes, line count, and existence. "
			+ "Use this to check file size before deciding whether to read it in full or in ranges.", type = "object")
	public String getFileInfo(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath) {
		WorkspaceService.FileInfo info = workspaceService.getFileInfo(projectName, resourcePath);
		if (!info.exists())
			return "Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.";

		StringBuilder sb = new StringBuilder();
		sb.append("# File Info\n\n");
		sb.append("- **Path:** ").append(info.fullPath()).append("\n");
		sb.append("- **Project:** ").append(info.projectName()).append("\n");
		sb.append("- **File name:** ").append(info.fileName()).append("\n");
		sb.append("- **Size:** ").append(info.sizeBytes()).append(" bytes\n");
		sb.append("- **Lines:** ").append(info.lineCount()).append("\n");
		if (info.lineCount() > WorkspaceService.MAX_LINES_DEFAULT)
			sb.append("- **Note:** File exceeds ").append(WorkspaceService.MAX_LINES_DEFAULT).append(
					" lines. Use startLine/endLine in readProjectResource or readFileRanges to read specific sections.\n");
		return sb.toString();
	}

	@Tool(name = "readFileRanges", description = "Reads multiple non-contiguous line ranges from a file in a single call. "
			+ "Useful for reading several locations at once (e.g. multiple stack trace lines) without multiple tool calls. "
			+ "Provide ranges as comma-separated pairs: '10-20,50-60,100-110'.", type = "object")
	public String readFileRanges(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
			@ToolParam(name = "ranges", description = "Comma-separated line ranges in format 'start1-end1,start2-end2' (e.g. '10-20,50-60'). Line numbers are 1-based.", required = true) String ranges) {
		List<WorkspaceService.RangeResult> results = workspaceService.readFileRanges(projectName, resourcePath, ranges);

		StringBuilder sb = new StringBuilder();
		sb.append("# File Ranges: ").append(resourcePath).append("\n\n");
		for (WorkspaceService.RangeResult r : results) {
			sb.append("## Lines ").append(r.startLine()).append("-").append(r.endLine()).append("\n\n");
			sb.append("```\n").append(r.content()).append("```\n\n");
		}
		return sb.toString();
	}

	@Tool(name = "readFileContext", description = "Reads lines around a specific line number (smart windowing). "
			+ "Perfect for analyzing errors at a specific line without reading the entire file. "
			+ "Returns lines from centerLine-windowSize to centerLine+windowSize.", type = "object")
	public String readFileContext(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
			@ToolParam(name = "centerLine", description = "The line number to center the reading window on (1-based)", required = true) String centerLine,
			@ToolParam(name = "windowSize", description = "Number of lines to read before and after centerLine. Default: 30.", required = false) String windowSize) {
		int center = Integer.parseInt(centerLine);
		int window = Optional.ofNullable(windowSize).map(Integer::parseInt).orElse(30);
		WorkspaceService.FileContextResult r = workspaceService.readFileContext(projectName, resourcePath, center,
				window);

		StringBuilder sb = new StringBuilder();
		sb.append("# File Context: ").append(resourcePath).append("\n\n");
		sb.append("Center line: ").append(r.centerLine()).append(", showing lines ").append(r.startLine()).append("-")
				.append(r.endLine()).append(" of ").append(r.totalLines()).append("\n\n");
		sb.append("```\n").append(r.content()).append("```\n");
		return sb.toString();
	}

	@Tool(name = "getFileOutline", description = "Gets an outline of functions/methods in a file without reading full content. "
			+ "Returns function names with their starting line numbers. "
			+ "Useful for navigating large files before using readFunction or readProjectResource.", type = "object")
	public String getFileOutline(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath) {
		List<WorkspaceService.OutlineEntry> entries = workspaceService.getFileOutline(projectName, resourcePath);

		if (entries.isEmpty())
			return "No functions found in '" + resourcePath + "'.";

		StringBuilder sb = new StringBuilder();
		sb.append("# File Outline: ").append(resourcePath).append("\n\n");
		sb.append("Found ").append(entries.size()).append(" function(s):\n\n");
		for (WorkspaceService.OutlineEntry e : entries)
			sb.append("- Line ").append(e.lineNumber()).append(": ").append(e.functionName()).append("()\n");
		return sb.toString();
	}

	@Tool(name = "readFunction", description = "Reads a complete function/method definition from a file by function name. "
			+ "Finds the function and returns all its lines using brace matching. "
			+ "Use getFileOutline first to discover available function names.", type = "object")
	public String readFunction(
			@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
			@ToolParam(name = "functionName", description = "Name of the function to read (without parentheses)", required = true) String functionName) {
		WorkspaceService.FunctionResult r = workspaceService.readFunction(projectName, resourcePath, functionName);

		StringBuilder sb = new StringBuilder();
		sb.append("# Function: ").append(r.functionName()).append("\n\n");
		sb.append("File: ").append(r.fullPath()).append(", lines ").append(r.startLine()).append("-")
				.append(r.endLine()).append("\n\n");
		sb.append("```javascript\n").append(r.content()).append("```\n");
		return sb.toString();
	}

	@Tool(name = "findFiles", description = "Finds workspace files matching the given glob patterns.", type = "object")
	public String findFiles(
			@ToolParam(name = "fileNamePatterns", description = "Comma-separated glob patterns (e.g. '*.java, *.xml'). If omitted, defaults to '*'.", required = false) String fileNamePatterns,
			@ToolParam(name = "maxResults", description = "Maximum number of results to return (default: 200)", required = false) String maxResults) {
		String[] patterns = parsePatterns(fileNamePatterns);
		int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(200);
		List<String> files = workspaceService.findFiles(patterns, limit);

		if (files.isEmpty())
			return "No files found matching the specified patterns.";

		StringBuilder result = new StringBuilder();
		result.append("# Found ").append(files.size()).append(" file(s)\n\n");
		for (String file : files)
			result.append("- ").append(file).append("\n");
		return result.toString();
	}

	@Tool(name = "fileSearch", description = "Searches for a plain substring in workspace files using Eclipse's text search engine.", type = "object")
	public String fileSearch(
			@ToolParam(name = "containingText", description = "Text that must be contained in a line (plain substring, not regex)", required = true) String containingText,
			@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns) {
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchResult> results = workspaceService.fileSearch(containingText, patterns);
		return formatSearchResults(results);
	}

	@Tool(name = "fileSearchRegExp", description = "Searches workspace files using a Java regular expression via Eclipse's text search engine.", type = "object")
	public String fileSearchRegExp(
			@ToolParam(name = "pattern", description = "Java regular expression", required = true) String pattern,
			@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns) {
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchResult> results = workspaceService.fileSearchRegExp(pattern, patterns);
		return formatSearchResults(results);
	}

	@Tool(name = "searchAndReplace", description = "Search and replace across multiple files in the workspace using Eclipse's text search engine. "
			+ "Aborts the entire operation if any matched file is a Servoy structural file (.frm, .obj, .tbl, .val, .rel, .dbi).", type = "object")
	public String searchAndReplace(
			@ToolParam(name = "containingText", description = "Plain text to find (not regex)", required = true) String containingText,
			@ToolParam(name = "replacementText", description = "Replacement text (can be empty)", required = true) String replacementText,
			@ToolParam(name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. '*.java,*.xml'). If omitted, all files are searched.", required = false) String fileNamePatterns) {
		String[] patterns = parsePatterns(fileNamePatterns);
		List<SearchAndReplaceResult> results = workspaceService.searchAndReplace(containingText, replacementText,
				patterns);

		if (results.isEmpty())
			return "No matches found for '" + containingText + "'.";

		StringBuilder sb = new StringBuilder();
		sb.append("# Search and Replace Results\n\n");
		int totalReplacements = 0;
		for (SearchAndReplaceResult r : results) {
			sb.append("- ").append(r.filePath()).append(": ").append(r.replacementsMade()).append(" replacement(s)\n");
			totalReplacements += r.replacementsMade();
		}
		sb.append("\nTotal: ").append(totalReplacements).append(" replacement(s) in ").append(results.size())
				.append(" file(s).\n");
		return sb.toString();
	}

	@Tool(name = "getMarkdownOutline", description = "Returns the heading structure (table of contents) of a Markdown file with line numbers and section sizes. "
			+ "Use this to understand a large Markdown document before fetching specific sections with getMarkdownSection.", type = "object")
	public String getMarkdownOutline(
			@ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root (e.g., 'docs/README.md')", required = true) String resourcePath) {
		return markdownService.getOutline(projectName, resourcePath);
	}

	@Tool(name = "getMarkdownSection", description = "Reads a specific section from a Markdown file by heading name or index. "
			+ "Returns the section content with line numbers. Use getMarkdownOutline first to see available headings.", type = "object")
	public String getMarkdownSection(
			@ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
			@ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root", required = true) String resourcePath,
			@ToolParam(name = "heading", description = "The heading to find - either a 1-based index from the outline, or a text substring to match (case-insensitive)", required = true) String heading,
			@ToolParam(name = "includeSubsections", description = "If 'true', includes all subsections under the matched heading. If 'false', returns only the content up to the next heading of any level. Default: true", required = false) String includeSubsections) {
		boolean includeSubs = Optional.ofNullable(includeSubsections).map(Boolean::parseBoolean).orElse(true);
		return markdownService.getSection(projectName, resourcePath, heading, includeSubs);
	}

	@Tool(name = "getCurrentlyOpenedFile", description = "Gets information about the currently active file in the Eclipse editor.", type = "object")
	public String getCurrentlyOpenedFile() {
		return ideStateService.getCurrentlyOpenedFile();
	}

	@Tool(name = "getEditorSelection", description = "Gets the currently selected text or lines in the active editor.", type = "object")
	public String getEditorSelection() {
		return ideStateService.getEditorSelection();
	}

	@Tool(name = "getConsoleOutput", description = "Retrieves the recent output from Eclipse console(s).", type = "object")
	public String getConsoleOutput(
			@ToolParam(name = "consoleName", description = "Name of the specific console to retrieve (optional, leave empty for most recent console)", required = false) String consoleName,
			@ToolParam(name = "maxLines", description = "Maximum number of lines to retrieve (default: 100)", required = false) String maxLines,
			@ToolParam(name = "includeAllConsoles", description = "Whether to include output from all available consoles (default: false)", required = false) String includeAllConsoles,
			@ToolParam(name = "clear", description = "Whether to clear the console(s) after reading (default: false)", required = false) String clear) {
		int lines = Optional.ofNullable(maxLines).map(Integer::parseInt).orElse(100);
		boolean allConsoles = Optional.ofNullable(includeAllConsoles).map(Boolean::parseBoolean).orElse(false);
		boolean shouldClear = Optional.ofNullable(clear).map(Boolean::parseBoolean).orElse(false);
		return ideStateService.getConsoleOutput(consoleName, lines, allConsoles, shouldClear);
	}

	@Tool(name = "getCompilationErrors", description = "Retrieves compilation errors and problems from the current workspace or a specific project.", type = "object")
	public String getCompilationErrors(
			@ToolParam(name = "projectName", description = "The name of the specific project to check (optional, leave empty for all projects)", required = false) String projectName,
			@ToolParam(name = "severity", description = "Filter by severity level: 'ERROR', 'WARNING', 'INFO', or 'ALL' (default)", required = false) String severity,
			@ToolParam(name = "maxResults", description = "Maximum number of problems to return (default: 50)", required = false) String maxResults,
			@ToolParam(name = "filePattern", description = "Optional glob pattern to filter by file name (e.g. '*.js', '*.frm'). If omitted, all files are included.", required = false) String filePattern) {
		int max = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(50);
		return ideStateService.getCompilationErrors(projectName, severity, max, filePattern);
	}

	// --- Private helpers ---

	private static String[] parsePatterns(String fileNamePatterns) {
		if (fileNamePatterns == null || fileNamePatterns.isBlank())
			return new String[0];
		String[] parts = fileNamePatterns.split(",");
		String[] trimmed = new String[parts.length];
		for (int i = 0; i < parts.length; i++)
			trimmed[i] = parts[i].trim();
		return trimmed;
	}

	private static String formatSearchResults(List<SearchResult> results) {
		if (results.isEmpty())
			return "No matches found.";

		StringBuilder sb = new StringBuilder();
		sb.append("# Search Results (").append(results.size()).append(" match(es))\n\n");
		for (SearchResult r : results) {
			sb.append("- ").append(r.filePath()).append(":").append(r.lineNumber());
			sb.append(" - ").append(r.lineContent().trim()).append("\n");
		}
		return sb.toString();
	}

	@Tool(name = "getSource", description = "Get the JavaScript source for a Servoy form or scope by name.", type = "object")
	public String getSource(
			@ToolParam(name = "name", description = "Form name or scope name (e.g. 'customers', 'utils')", required = true) String name,
			@ToolParam(name = "moduleName", description = "Module name to search in. If omitted, searches in the active solution.", required = false) String moduleName) {
		if (name == null || name.isBlank())
			throw new RuntimeException("Error: 'name' is required.");

		org.eclipse.core.resources.IFile file = servoyScriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return servoyScriptResolver.buildNotFoundMessage(name, moduleName);

		String projectName = file.getProject().getName();
		String resourcePath = file.getProjectRelativePath().toString();
		return workspaceService.readProjectResource(projectName, resourcePath, true, 0, 0);
	}

	@Tool(name = "getClassOutline", description = "Returns a compact outline of a Servoy JavaScript file: all function/method names with line numbers and parameter names. "
			+ "Much more token-efficient than reading the full file. "
			+ "Accepts a form name (e.g. 'customers'), scope name (e.g. 'utils'), or project-relative path. "
			+ "Use this first, then getMethodSource for specific functions.", type = "object")
	public String getClassOutline(
			@ToolParam(name = "name", description = "Form name, scope name, or project-relative path (e.g. 'customers', 'utils', 'forms/customers.js')", required = true) String name,
			@ToolParam(name = "moduleName", description = "Module/project name to search in. If omitted, searches in the active solution.", required = false) String moduleName) {
		org.eclipse.core.resources.IFile file = servoyScriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return servoyScriptResolver.buildNotFoundMessage(name, moduleName);

		FileStructureService.FileStructure structure = fileStructureService.analyzeFile(file);
		return structure.toFormattedString();
	}

	@Tool(name = "getMethodSource", description = "Returns the source code of a specific function by name from a Servoy JavaScript file. "
			+ "Accepts a form name, scope name, or project-relative path. "
			+ "Use getClassOutline first to discover available function names.", type = "object")
	public String getMethodSource(
			@ToolParam(name = "name", description = "Form name, scope name, or project-relative path (e.g. 'customers', 'utils', 'forms/customers.js')", required = true) String name,
			@ToolParam(name = "methodNames", description = "Comma-separated function names to retrieve (e.g. 'onLoad,saveRecord')", required = true) String methodNames,
			@ToolParam(name = "moduleName", description = "Module/project name to search in. If omitted, searches in the active solution.", required = false) String moduleName,
			@ToolParam(name = "includeJavadoc", description = "Not used - kept for API compatibility.", required = false) String includeJavadoc) {
		org.eclipse.core.resources.IFile file = servoyScriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return servoyScriptResolver.buildNotFoundMessage(name, moduleName);

		if (methodNames == null || methodNames.isBlank())
			return "Error: 'methodNames' is required.";

		StringBuilder sb = new StringBuilder();
		for (String methodName : methodNames.split(",")) {
			String trimmed = methodName.trim();
			if (trimmed.isBlank())
				continue;
			try {
				WorkspaceService.FunctionResult r = workspaceService.readFunction(file.getProject().getName(),
						file.getProjectRelativePath().toString(), trimmed);
				sb.append("## ").append(r.functionName()).append(" (lines ").append(r.startLine()).append("-")
						.append(r.endLine()).append(")\n\n");
				sb.append("```javascript\n").append(r.content()).append("```\n\n");
			} catch (RuntimeException e) {
				sb.append("## ").append(trimmed).append("\n\n");
				sb.append(e.getMessage()).append("\n\n");
			}
		}
		return sb.toString();
	}

	@Tool(name = "getFilteredSource", description = "Returns the source of a Servoy JavaScript file with selective function expansion. "
			+ "Functions listed in 'methodNames' are shown in full; all others are collapsed to their signature and line number. "
			+ "Accepts a form name, scope name, or project-relative path.", type = "object")
	public String getFilteredSource(
			@ToolParam(name = "name", description = "Form name, scope name, or project-relative path (e.g. 'customers', 'utils', 'forms/customers.js')", required = true) String name,
			@ToolParam(name = "methodNames", description = "Comma-separated function names to fully expand. Functions not listed are collapsed to their signature.", required = false) String methodNames,
			@ToolParam(name = "moduleName", description = "Module/project name to search in. If omitted, searches in the active solution.", required = false) String moduleName,
			@ToolParam(name = "excludeImports", description = "Not used - kept for API compatibility.", required = false) String excludeImports) {
		org.eclipse.core.resources.IFile file = servoyScriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return servoyScriptResolver.buildNotFoundMessage(name, moduleName);

		String projectName = file.getProject().getName();
		String resourcePath = file.getProjectRelativePath().toString();

		// Get outline for all symbols
		java.util.List<WorkspaceService.OutlineEntry> outline = workspaceService.getFileOutline(projectName,
				resourcePath);

		// Parse requested methods
		java.util.Set<String> expand = new java.util.HashSet<>();
		if (methodNames != null && !methodNames.isBlank())
			for (String m : methodNames.split(","))
				expand.add(m.trim());

		StringBuilder sb = new StringBuilder();
		sb.append("# ").append(resourcePath).append("\n\n");

		if (outline.isEmpty()) {
			// No symbols found - return full file
			return workspaceService.readProjectResource(projectName, resourcePath, true, 0, 0);
		}

		for (WorkspaceService.OutlineEntry entry : outline) {
			if (expand.isEmpty() || expand.contains(entry.functionName())) {
				// Expand fully
				try {
					WorkspaceService.FunctionResult r = workspaceService.readFunction(projectName, resourcePath,
							entry.functionName());
					sb.append("```javascript\n").append(r.content()).append("```\n\n");
				} catch (RuntimeException e) {
					sb.append("- Line ").append(entry.lineNumber()).append(": ").append(entry.functionName())
							.append("() [could not read]\n");
				}
			} else {
				// Collapsed
				sb.append("- Line ").append(entry.lineNumber()).append(": ").append(entry.functionName())
						.append("()\n");
			}
		}
		return sb.toString();
	}
}
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
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;
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
@Creatable
@McpServer(name = "servoy-ide")
public class ServoyIdeServer
{
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

	/** Default constructor — required by E4 DI (ContextInjectionFactory.make). */
	public ServoyIdeServer() { }

	/** Testing constructor — initialises services directly without E4 DI. */
	ServoyIdeServer(ProjectService projectService, WorkspaceService workspaceService,
		MarkdownService markdownService, IdeStateService ideStateService)
	{
		this.projectService = projectService;
		this.workspaceService = workspaceService;
		this.markdownService = markdownService;
		this.ideStateService = ideStateService;
		this.servoyScriptResolver = new ServoyScriptResolver();
	}

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

	// --- Dummy tools (JDT-only — not available in Servoy Developer) ---

	private static final String JDT_NOT_AVAILABLE =
		"Not available in Servoy Developer MCP: this tool requires JDT (Java Development Tools) " +
		"which is not present in the Servoy Developer runtime. Use the Eclipse IDE MCP endpoint instead.";

	private static final String MAVEN_NOT_AVAILABLE =
		"Not available in Servoy Developer MCP: this tool requires Maven integration " +
		"which is not applicable in the Servoy Developer runtime.";

	private static final String JUNIT_NOT_AVAILABLE =
		"Not available in Servoy Developer MCP: this tool requires the JUnit test runner " +
		"which is not applicable in the Servoy Developer runtime.";

	@Tool(name = "formatCode", description = "Formats code according to the current Eclipse formatter settings. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String formatCode(
		@ToolParam(name = "code", description = "The code to be formatted", required = true) String code,
		@ToolParam(name = "projectName", description = "Optional project name to use project-specific formatter settings", required = false) String projectName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getJavaDoc", description = "Get the JavaDoc for the given compilation unit. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getJavaDoc(
		@ToolParam(name = "fullyQualifiedName", description = "A fully qualified name of the compilation unit", required = true) String fullyQualifiedName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getSource", description = "Get the JavaScript source for a Servoy form or scope by name.", type = "object")
	public String getSource(
		@ToolParam(name = "name", description = "Form name or scope name (e.g. 'customers', 'utils')", required = true) String name,
		@ToolParam(name = "moduleName", description = "Module name to search in. If omitted, searches in the active solution.", required = false) String moduleName)
	{
		if (name == null || name.isBlank())
			throw new RuntimeException("Error: 'name' is required.");

		org.eclipse.core.resources.IFile file = servoyScriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return servoyScriptResolver.buildNotFoundMessage(name, moduleName);

		String projectName = file.getProject().getName();
		String resourcePath = file.getProjectRelativePath().toString();
		return workspaceService.readProjectResource(projectName, resourcePath, true, 0, 0);
	}

	@Tool(name = "getClassOutline", description = "Returns a compact outline of a Java class. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getClassOutline(
		@ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name", required = true) String fullyQualifiedClassName,
		@ToolParam(name = "includeFields", description = "Whether to include field declarations (default: true)", required = false) String includeFields)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getMethodSource", description = "Returns the source code of specific method(s) with line numbers. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getMethodSource(
		@ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name", required = true) String fullyQualifiedClassName,
		@ToolParam(name = "methodNames", description = "Comma-separated method names to retrieve", required = true) String methodNames,
		@ToolParam(name = "methodSignature", description = "Optional parameter type hint to disambiguate overloaded methods", required = false) String methodSignature,
		@ToolParam(name = "includeJavadoc", description = "Whether to include Javadoc comments (default: true)", required = false) String includeJavadoc)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getFilteredSource", description = "Returns source code with optional import exclusion and selective method expansion. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getFilteredSource(
		@ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name", required = true) String fullyQualifiedClassName,
		@ToolParam(name = "excludeImports", description = "Whether to collapse the import block (default: true)", required = false) String excludeImports,
		@ToolParam(name = "methodNames", description = "Comma-separated method names to fully expand", required = false) String methodNames)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getMethodCallHierarchy", description = "Retrieves the call hierarchy for a specified method. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getMethodCallHierarchy(
		@ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the method", required = true) String fullyQualifiedClassName,
		@ToolParam(name = "methodName", description = "The name of the method to analyze", required = true) String methodName,
		@ToolParam(name = "methodSignature", description = "The signature of the method (optional)", required = false) String methodSignature,
		@ToolParam(name = "maxDepth", description = "Maximum depth of the call hierarchy to retrieve (default: 3)", required = false) String maxDepth)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getTypeHierarchy", description = "Retrieves the type hierarchy for a given Java class or interface. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getTypeHierarchy(
		@ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class", required = true) String fullyQualifiedClassName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "findReferences", description = "Finds all references/usages of a Java type, method, or field. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String findReferences(
		@ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the element", required = true) String fullyQualifiedClassName,
		@ToolParam(name = "elementName", description = "Optional method or field name to search for", required = false) String elementName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "getImportSuggestions", description = "Finds import candidates for unresolved types in a Java file. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String getImportSuggestions(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "executeQuickFix", description = "Applies a specific quick fix proposal to a compilation problem. NOT AVAILABLE in Servoy Developer — requires JDT.", type = "object")
	public String executeQuickFix(
		@ToolParam(name = "markerId", description = "The Marker ID of the problem", required = true) String markerId,
		@ToolParam(name = "proposalIndex", description = "The 0-based index of the quick fix proposal to apply", required = true) String proposalIndex)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	// --- Dummy tools (JUnit runner — not applicable in Servoy Developer) ---

	@Tool(name = "findTestClasses", description = "Finds all test classes in a project. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String findTestClasses(
		@ToolParam(name = "projectName", description = "The exact Eclipse project name to search", required = true) String projectName)
	{
		throw new RuntimeException(JUNIT_NOT_AVAILABLE);
	}

	@Tool(name = "runAllTests", description = "Runs all JUnit tests in a specified project. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String runAllTests(
		@ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test classes", required = true) String projectName,
		@ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
		@ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage. Default: false", required = false) String withCoverage)
	{
		throw new RuntimeException(JUNIT_NOT_AVAILABLE);
	}

	@Tool(name = "runPackageTests", description = "Runs all JUnit tests in a specific package. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String runPackageTests(
		@ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test classes", required = true) String projectName,
		@ToolParam(name = "packageName", description = "The fully qualified package name", required = true) String packageName,
		@ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
		@ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage. Default: false", required = false) String withCoverage)
	{
		throw new RuntimeException(JUNIT_NOT_AVAILABLE);
	}

	@Tool(name = "runClassTests", description = "Runs all JUnit tests in a specific test class. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String runClassTests(
		@ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class", required = true) String projectName,
		@ToolParam(name = "className", description = "The fully qualified class name", required = true) String className,
		@ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
		@ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage. Default: false", required = false) String withCoverage)
	{
		throw new RuntimeException(JUNIT_NOT_AVAILABLE);
	}

	@Tool(name = "runTestMethod", description = "Runs a single JUnit test method. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String runTestMethod(
		@ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class", required = true) String projectName,
		@ToolParam(name = "className", description = "The fully qualified class name", required = true) String className,
		@ToolParam(name = "methodName", description = "The test method name without parentheses", required = true) String methodName,
		@ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout,
		@ToolParam(name = "withCoverage", description = "If 'true', runs tests with code coverage. Default: false", required = false) String withCoverage)
	{
		throw new RuntimeException(JUNIT_NOT_AVAILABLE);
	}

	// --- Dummy tools (Maven — not applicable in Servoy Developer) ---

	@Tool(name = "listMavenProjects", description = "Lists all available Maven projects in the workspace. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String listMavenProjects()
	{
		throw new RuntimeException(MAVEN_NOT_AVAILABLE);
	}

	@Tool(name = "runMavenBuild", description = "Runs a Maven build with the specified goals on a project. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String runMavenBuild(
		@ToolParam(name = "projectName", description = "The name of the project to build", required = true) String projectName,
		@ToolParam(name = "goals", description = "The Maven goals to execute", required = true) String goals,
		@ToolParam(name = "profiles", description = "Optional Maven profiles to activate", required = false) String profiles,
		@ToolParam(name = "timeout", description = "Maximum time in seconds to wait for build completion (0 for no timeout)", required = false) String timeout)
	{
		throw new RuntimeException(MAVEN_NOT_AVAILABLE);
	}

	@Tool(name = "getEffectivePom", description = "Gets the effective POM for a Maven project. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String getEffectivePom(
		@ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
	{
		throw new RuntimeException(MAVEN_NOT_AVAILABLE);
	}

	@Tool(name = "getProjectDependencies", description = "Gets Maven project dependencies. NOT AVAILABLE in Servoy Developer.", type = "object")
	public String getProjectDependencies(
		@ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
	{
		throw new RuntimeException(MAVEN_NOT_AVAILABLE);
	}
}

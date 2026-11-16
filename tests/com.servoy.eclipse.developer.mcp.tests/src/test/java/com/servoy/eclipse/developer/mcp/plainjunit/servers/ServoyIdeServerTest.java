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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.McpServerBuiltins;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.services.CodeAnalysisService;
import com.servoy.eclipse.developer.mcp.services.FileStructureService;
import com.servoy.eclipse.developer.mcp.services.FileStructureService.FileStructure;
import com.servoy.eclipse.developer.mcp.services.FileStructureService.SymbolInfo;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FileContextResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FileInfo;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FunctionResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.OutlineEntry;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.RangeResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResults;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyIdeServer}'s <em>own</em>
 * logic - without a live Eclipse workspace.
 * <p>
 * {@code ServoyIdeServer} is a thin {@code @McpServer} tool wrapper: every
 * {@code @Tool} method forwards to one of seven collaborator services
 * ({@link ProjectService}, {@link WorkspaceService}, {@link MarkdownService},
 * {@link IdeStateService}, {@link ServoyScriptResolver},
 * {@link FileStructureService}, {@link CodeAnalysisService}). Its only own logic
 * is the <em>tool-arg -&gt; delegate-call</em> marshalling - the
 * {@code String}-to-{@code int}/{@code boolean} parses (maxDepth/maxResults/
 * startLine/endLine/centerLine/windowSize/maxLines, showLineNumbers/
 * includeAllConsoles/clear/waitForBuild/includeSubsections), the
 * {@code name}/{@code methodNames} blank guards, the resolver-not-found
 * short-circuit, the markdown/search/outline/function/context result formatting,
 * and the straight result pass-through.
 * </p>
 * <p>
 * The four E4-DI collaborators are supplied through the server's public testing
 * constructor {@code ServoyIdeServer(ProjectService, WorkspaceService,
 * MarkdownService, IdeStateService)}; the three it creates eagerly
 * ({@code servoyScriptResolver}, {@code fileStructureService},
 * {@code codeAnalysisService}) are swapped for Mockito mocks via reflection, so
 * no production visibility is widened and no seam is introduced for the test.
 * The collaborators are stubbed only at the boundary - their real behaviour
 * (workspace/model/console/editor/DLTK) needs the workbench and stays in the
 * {@code integrationpde} suite. These tests never touch
 * {@code ServoyModelManager} / {@code ResourcesPlugin}; the {@code IFile}/
 * {@code IProject} they hand around are themselves mocks.
 * </p>
 *
 * @since 2026.11
 */
class ServoyIdeServerTest
{
	private ProjectService projectService;
	private WorkspaceService workspaceService;
	private MarkdownService markdownService;
	private IdeStateService ideStateService;
	private ServoyScriptResolver scriptResolver;
	private FileStructureService fileStructureService;
	private CodeAnalysisService codeAnalysisService;
	private ServoyIdeServer server;

	@BeforeEach
	void setUp()
	{
		projectService = mock(ProjectService.class);
		workspaceService = mock(WorkspaceService.class);
		markdownService = mock(MarkdownService.class);
		ideStateService = mock(IdeStateService.class);
		scriptResolver = mock(ServoyScriptResolver.class);
		fileStructureService = mock(FileStructureService.class);
		codeAnalysisService = mock(CodeAnalysisService.class);

		server = new ServoyIdeServer(projectService, workspaceService, markdownService, ideStateService);
		// swap the three eagerly-constructed collaborators for mocks
		setField(server, "servoyScriptResolver", scriptResolver);
		setField(server, "fileStructureService", fileStructureService);
		setField(server, "codeAnalysisService", codeAnalysisService);
	}

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyIdeServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	private static Object readField(Object target, String name)
	{
		try
		{
			Field f = ServoyIdeServer.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}

	/** A mocked {@link IFile} whose project name and project-relative path are stubbed. */
	private static IFile mockFile(String projectName, String projectRelativePath)
	{
		IFile file = mock(IFile.class);
		IProject project = mock(IProject.class);
		lenient().when(project.getName()).thenReturn(projectName);
		lenient().when(file.getProject()).thenReturn(project);
		IPath path = mock(IPath.class);
		lenient().when(path.toString()).thenReturn(projectRelativePath);
		lenient().when(file.getProjectRelativePath()).thenReturn(path);
		return file;
	}

	// =======================================================================
	// Annotation / registration / construction (no delegate interaction)
	// =======================================================================

	@Nested
	class AnnotationAndRegistration
	{
		@Test
		@DisplayName("ServoyIdeServer carries the @McpServer(name = \"servoy-ide\") annotation")
		void hasCorrectAnnotation()
		{
			McpServer ann = ServoyIdeServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyIdeServer must have @McpServer annotation");
			assertFalse(ann.name().isBlank(), "@McpServer name must not be empty");
			assertEquals("servoy-ide", ann.name(), "@McpServer name must be 'servoy-ide'");
		}

		@Test
		@DisplayName("ServoyIdeServer is registered in McpServerBuiltins")
		void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (cls == ServoyIdeServer.class)
				{
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyIdeServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("every @Tool name on the server is unique")
		void toolNamesAreUnique()
		{
			Set<String> names = new HashSet<>();
			Arrays.stream(ServoyIdeServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class))
				.forEach(m -> {
					String name = m.getAnnotation(Tool.class).name();
					assertFalse(names.contains(name), "Duplicate tool name: " + name);
					names.add(name);
				});
			assertTrue(names.size() > 0, "server must expose at least one @Tool");
		}

		@Test
		@DisplayName("the testing constructor wires the four supplied services and eagerly creates the other three")
		void testingConstructorWiresDelegates()
		{
			ServoyIdeServer s = new ServoyIdeServer(projectService, workspaceService, markdownService, ideStateService);
			assertSame(projectService, readField(s, "projectService"));
			assertSame(workspaceService, readField(s, "workspaceService"));
			assertSame(markdownService, readField(s, "markdownService"));
			assertSame(ideStateService, readField(s, "ideStateService"));
			assertNotNull(readField(s, "servoyScriptResolver"), "resolver should be created eagerly");
			assertNotNull(readField(s, "fileStructureService"), "file-structure service should be created eagerly");
			assertNotNull(readField(s, "codeAnalysisService"), "code-analysis service should be created eagerly");
		}
	}

	// =======================================================================
	// ProjectService delegation
	// =======================================================================

	@Nested
	class ProjectDelegation
	{
		@Test
		@DisplayName("listProjects delegates to ProjectService and passes the result through unchanged")
		void listProjects()
		{
			when(projectService.listProjects()).thenReturn("PROJECTS");
			assertEquals("PROJECTS", server.listProjects());
			verify(projectService).listProjects();
		}

		@Test
		@DisplayName("openProject forwards the directory path verbatim")
		void openProject()
		{
			when(projectService.openProject("/path/to/dir")).thenReturn("OPENED");
			assertEquals("OPENED", server.openProject("/path/to/dir"));
			verify(projectService).openProject("/path/to/dir");
		}

		@Test
		@DisplayName("getProjectLayout defaults maxDepth to -1 when the argument is null")
		void layoutDefaultsDepthMinusOne()
		{
			when(projectService.getProjectLayout("proj", "src", -1)).thenReturn("LAYOUT");
			assertEquals("LAYOUT", server.getProjectLayout("proj", "src", null));
			verify(projectService).getProjectLayout("proj", "src", -1);
		}

		@Test
		@DisplayName("getProjectLayout parses the supplied maxDepth")
		void layoutParsesDepth()
		{
			when(projectService.getProjectLayout("proj", null, 3)).thenReturn("LAYOUT");
			assertEquals("LAYOUT", server.getProjectLayout("proj", null, "3"));
			verify(projectService).getProjectLayout("proj", null, 3);
		}

		@Test
		@DisplayName("getProjectLayout propagates a NumberFormatException for a non-numeric maxDepth and never calls the delegate")
		void layoutRejectsNonNumericDepth()
		{
			assertThrows(NumberFormatException.class, () -> server.getProjectLayout("proj", null, "deep"));
			verify(projectService, never()).getProjectLayout(anyString(), any(), anyInt());
		}

		@Test
		@DisplayName("getProjectProperties forwards the project name verbatim")
		void getProjectProperties()
		{
			when(projectService.getProjectProperties("proj")).thenReturn("PROPS");
			assertEquals("PROPS", server.getProjectProperties("proj"));
			verify(projectService).getProjectProperties("proj");
		}
	}

	// =======================================================================
	// WorkspaceService delegation - readProjectResource boolean/int parsing
	// =======================================================================

	@Nested
	class ReadProjectResourceParsing
	{
		@Test
		@DisplayName("readProjectResource defaults showLineNumbers=false and start/end=0 when omitted")
		void defaults()
		{
			when(workspaceService.readProjectResource("proj", "a.js", false, 0, 0)).thenReturn("CONTENT");
			assertEquals("CONTENT", server.readProjectResource("proj", "a.js", null, null, null));
			verify(workspaceService).readProjectResource("proj", "a.js", false, 0, 0);
		}

		@Test
		@DisplayName("readProjectResource parses showLineNumbers=\"true\" and the start/end line numbers")
		void parsesAll()
		{
			when(workspaceService.readProjectResource("proj", "a.js", true, 10, 20)).thenReturn("CONTENT");
			assertEquals("CONTENT", server.readProjectResource("proj", "a.js", "true", "10", "20"));
			verify(workspaceService).readProjectResource("proj", "a.js", true, 10, 20);
		}

		@Test
		@DisplayName("readProjectResource treats a non-\"true\" showLineNumbers as false")
		void nonTrueLineNumbersIsFalse()
		{
			when(workspaceService.readProjectResource("proj", "a.js", false, 0, 0)).thenReturn("C");
			assertEquals("C", server.readProjectResource("proj", "a.js", "yes", null, null));
			verify(workspaceService).readProjectResource("proj", "a.js", false, 0, 0);
		}

		@Test
		@DisplayName("readProjectResource propagates a NumberFormatException for a non-numeric startLine")
		void rejectsNonNumericStart()
		{
			assertThrows(NumberFormatException.class, () -> server.readProjectResource("proj", "a.js", null, "x", null));
			verify(workspaceService, never()).readProjectResource(anyString(), anyString(), anyBoolean(), anyInt(), anyInt());
		}
	}

	// =======================================================================
	// WorkspaceService delegation - getFileInfo formatting
	// =======================================================================

	@Nested
	class GetFileInfoFormatting
	{
		@Test
		@DisplayName("getFileInfo returns the not-found error when the file does not exist")
		void notFound()
		{
			when(workspaceService.getFileInfo("proj", "missing.js"))
				.thenReturn(new FileInfo("/proj/missing.js", "proj", "missing.js", -1, -1, false));
			String result = server.getFileInfo("proj", "missing.js");
			assertTrue(result.startsWith("Error:"), "got: " + result);
			assertTrue(result.contains("missing.js"), "got: " + result);
		}

		@Test
		@DisplayName("getFileInfo renders size and line count for an existing file under the default line cap")
		void existingSmall()
		{
			when(workspaceService.getFileInfo("proj", "a.js"))
				.thenReturn(new FileInfo("/proj/a.js", "proj", "a.js", 1234, 42, true));
			String result = server.getFileInfo("proj", "a.js");
			assertTrue(result.contains("# File Info"), "got: " + result);
			assertTrue(result.contains("1234 bytes"), "got: " + result);
			assertTrue(result.contains("**Lines:** 42"), "got: " + result);
			assertFalse(result.contains("exceeds"), "small file must not carry the large-file note; got: " + result);
		}

		@Test
		@DisplayName("getFileInfo appends the large-file note once the line count exceeds the default cap")
		void largeFileNote()
		{
			int big = WorkspaceService.MAX_LINES_DEFAULT + 1;
			when(workspaceService.getFileInfo("proj", "big.js"))
				.thenReturn(new FileInfo("/proj/big.js", "proj", "big.js", 99999, big, true));
			String result = server.getFileInfo("proj", "big.js");
			assertTrue(result.contains("exceeds"), "large file must carry the note; got: " + result);
			assertTrue(result.contains(String.valueOf(WorkspaceService.MAX_LINES_DEFAULT)), "note names the cap; got: " + result);
		}
	}

	// =======================================================================
	// WorkspaceService delegation - readFileRanges / readFileContext / outline / function formatting
	// =======================================================================

	@Nested
	class RangeAndContextFormatting
	{
		@Test
		@DisplayName("readFileRanges forwards the raw ranges string and renders each returned range")
		void readFileRanges()
		{
			when(workspaceService.readFileRanges("proj", "a.js", "1-2,5-6"))
				.thenReturn(List.of(new RangeResult(1, 2, "code1"), new RangeResult(5, 6, "code2")));
			String result = server.readFileRanges("proj", "a.js", "1-2,5-6");
			verify(workspaceService).readFileRanges("proj", "a.js", "1-2,5-6");
			assertTrue(result.contains("## Lines 1-2"), "got: " + result);
			assertTrue(result.contains("code1"), "got: " + result);
			assertTrue(result.contains("## Lines 5-6"), "got: " + result);
		}

		@Test
		@DisplayName("readFileContext defaults windowSize to 30 when omitted and parses centerLine")
		void readFileContextDefaultWindow()
		{
			when(workspaceService.readFileContext("proj", "a.js", 50, 30))
				.thenReturn(new FileContextResult("/proj/a.js", 100, 50, 30, 20, 80, "ctx"));
			String result = server.readFileContext("proj", "a.js", "50", null);
			verify(workspaceService).readFileContext("proj", "a.js", 50, 30);
			assertTrue(result.contains("Center line: 50"), "got: " + result);
			assertTrue(result.contains("ctx"), "got: " + result);
		}

		@Test
		@DisplayName("readFileContext parses the supplied windowSize")
		void readFileContextParsesWindow()
		{
			when(workspaceService.readFileContext("proj", "a.js", 10, 5))
				.thenReturn(new FileContextResult("/proj/a.js", 100, 10, 5, 5, 15, "ctx"));
			server.readFileContext("proj", "a.js", "10", "5");
			verify(workspaceService).readFileContext("proj", "a.js", 10, 5);
		}

		@Test
		@DisplayName("readFileContext propagates a NumberFormatException for a non-numeric centerLine")
		void readFileContextRejectsNonNumericCenter()
		{
			assertThrows(NumberFormatException.class, () -> server.readFileContext("proj", "a.js", "middle", null));
			verify(workspaceService, never()).readFileContext(anyString(), anyString(), anyInt(), anyInt());
		}

		@Test
		@DisplayName("getFileOutline reports 'no functions' when the delegate returns an empty list")
		void outlineEmpty()
		{
			when(workspaceService.getFileOutline("proj", "a.js")).thenReturn(List.of());
			String result = server.getFileOutline("proj", "a.js");
			assertTrue(result.contains("No functions found"), "got: " + result);
		}

		@Test
		@DisplayName("getFileOutline renders the function entries with their line numbers")
		void outlineEntries()
		{
			when(workspaceService.getFileOutline("proj", "a.js"))
				.thenReturn(List.of(new OutlineEntry(5, "onLoad"), new OutlineEntry(20, "save")));
			String result = server.getFileOutline("proj", "a.js");
			assertTrue(result.contains("Found 2 function(s)"), "got: " + result);
			assertTrue(result.contains("Line 5: onLoad()"), "got: " + result);
			assertTrue(result.contains("Line 20: save()"), "got: " + result);
		}

		@Test
		@DisplayName("readFunction renders the function body returned by the delegate")
		void readFunction()
		{
			when(workspaceService.readFunction("proj", "a.js", "onLoad"))
				.thenReturn(new FunctionResult("/proj/a.js", "onLoad", 5, 12, "function onLoad() {}\n"));
			String result = server.readFunction("proj", "a.js", "onLoad");
			verify(workspaceService).readFunction("proj", "a.js", "onLoad");
			assertTrue(result.contains("# Function: onLoad"), "got: " + result);
			assertTrue(result.contains("lines 5-12"), "got: " + result);
			assertTrue(result.contains("function onLoad()"), "got: " + result);
		}
	}

	// =======================================================================
	// findFiles - parsePatterns + maxResults parsing, formatting
	// =======================================================================

	@Nested
	class FindFiles
	{
		@Test
		@DisplayName("findFiles passes an empty pattern array and the default limit 200 when both args are null")
		void defaults()
		{
			when(workspaceService.findFiles(new String[0], 200)).thenReturn(List.of("a.js", "b.js"));
			String result = server.findFiles(null, null);
			verify(workspaceService).findFiles(new String[0], 200);
			assertTrue(result.contains("Found 2 file(s)"), "got: " + result);
			assertTrue(result.contains("- a.js"), "got: " + result);
		}

		@Test
		@DisplayName("findFiles splits and trims the comma-separated patterns and parses maxResults")
		void parsesPatternsAndLimit()
		{
			when(workspaceService.findFiles(new String[] { "*.java", "*.xml" }, 50)).thenReturn(List.of("x.java"));
			server.findFiles(" *.java , *.xml ", "50");
			verify(workspaceService).findFiles(new String[] { "*.java", "*.xml" }, 50);
		}

		@Test
		@DisplayName("findFiles reports 'no files' when the delegate returns an empty list")
		void empty()
		{
			when(workspaceService.findFiles(any(String[].class), anyInt())).thenReturn(List.of());
			String result = server.findFiles("*.none", null);
			assertTrue(result.contains("No files found"), "got: " + result);
		}
	}

	// =======================================================================
	// fileSearch / fileSearchRegExp - parseMaxResults + formatSearchResults
	// =======================================================================

	@Nested
	class SearchTools
	{
		@Test
		@DisplayName("fileSearch forwards containingText, the parsed cap and patterns and formats the matches")
		void fileSearchFormats()
		{
			SearchResults results = new SearchResults(
				List.of(new SearchResult("a.js", 3, "  var x = 1;  "), new SearchResult("b.js", 9, "foo")), false);
			when(workspaceService.fileSearch("needle", 10, new String[] { "*.js" })).thenReturn(results);
			String out = server.fileSearch("needle", "*.js", "10");
			verify(workspaceService).fileSearch("needle", 10, new String[] { "*.js" });
			assertTrue(out.contains("# Search Results (2 match(es))"), "got: " + out);
			assertTrue(out.contains("a.js:3 - var x = 1;"), "line content must be trimmed; got: " + out);
			assertTrue(out.contains("b.js:9 - foo"), "got: " + out);
		}

		@Test
		@DisplayName("fileSearch uses the SEARCH_MAX_RESULTS_DEFAULT when maxResults is null")
		void fileSearchDefaultCap()
		{
			when(workspaceService.fileSearch(eq("needle"), eq(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT), any(String[].class)))
				.thenReturn(new SearchResults(List.of(), false));
			server.fileSearch("needle", null, null);
			verify(workspaceService).fileSearch("needle", WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, new String[0]);
		}

		@Test
		@DisplayName("fileSearch falls back to the default cap for a non-numeric maxResults instead of throwing")
		void fileSearchNonNumericCapFallsBack()
		{
			when(workspaceService.fileSearch(eq("needle"), eq(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT), any(String[].class)))
				.thenReturn(new SearchResults(List.of(), false));
			server.fileSearch("needle", null, "lots");
			verify(workspaceService).fileSearch("needle", WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, new String[0]);
		}

		@Test
		@DisplayName("fileSearch reports 'No matches found.' for an empty result set")
		void fileSearchEmpty()
		{
			when(workspaceService.fileSearch(anyString(), anyInt(), any(String[].class)))
				.thenReturn(new SearchResults(List.of(), false));
			assertEquals("No matches found.", server.fileSearch("needle", null, null));
		}

		@Test
		@DisplayName("fileSearch marks the result as truncated when the delegate reports a capped search")
		void fileSearchTruncated()
		{
			when(workspaceService.fileSearch(anyString(), anyInt(), any(String[].class)))
				.thenReturn(new SearchResults(List.of(new SearchResult("a.js", 1, "x")), true));
			String out = server.fileSearch("needle", null, null);
			assertTrue(out.contains("truncated"), "got: " + out);
		}

		@Test
		@DisplayName("fileSearchRegExp forwards the pattern/cap/patterns and formats the matches")
		void regExpFormats()
		{
			when(workspaceService.fileSearchRegExp("a.+b", 5, new String[] { "*.js" }))
				.thenReturn(new SearchResults(List.of(new SearchResult("a.js", 1, "aXb")), false));
			String out = server.fileSearchRegExp("a.+b", "*.js", "5");
			verify(workspaceService).fileSearchRegExp("a.+b", 5, new String[] { "*.js" });
			assertTrue(out.contains("a.js:1 - aXb"), "got: " + out);
		}
	}

	// =======================================================================
	// searchAndReplace - formatting + empty case
	// =======================================================================

	@Nested
	class SearchAndReplace
	{
		@Test
		@DisplayName("searchAndReplace reports 'no matches' when the delegate returns an empty list")
		void empty()
		{
			when(workspaceService.searchAndReplace(eq("old"), eq("new"), any(String[].class))).thenReturn(List.of());
			String result = server.searchAndReplace("old", "new", null);
			assertTrue(result.contains("No matches found for 'old'"), "got: " + result);
		}

		@Test
		@DisplayName("searchAndReplace totals the replacements across the returned files")
		void totals()
		{
			when(workspaceService.searchAndReplace("old", "new", new String[] { "*.js" }))
				.thenReturn(List.of(
					new WorkspaceService.SearchAndReplaceResult("a.js", 2, 2),
					new WorkspaceService.SearchAndReplaceResult("b.js", 3, 3)));
			String result = server.searchAndReplace("old", "new", "*.js");
			verify(workspaceService).searchAndReplace("old", "new", new String[] { "*.js" });
			assertTrue(result.contains("a.js: 2 replacement(s)"), "got: " + result);
			assertTrue(result.contains("b.js: 3 replacement(s)"), "got: " + result);
			assertTrue(result.contains("Total: 5 replacement(s) in 2 file(s)"), "got: " + result);
		}
	}

	// =======================================================================
	// MarkdownService delegation
	// =======================================================================

	@Nested
	class MarkdownDelegation
	{
		@Test
		@DisplayName("getMarkdownOutline forwards project/path verbatim and passes the result through")
		void outline()
		{
			when(markdownService.getOutline("proj", "docs/README.md")).thenReturn("OUTLINE");
			assertEquals("OUTLINE", server.getMarkdownOutline("proj", "docs/README.md"));
			verify(markdownService).getOutline("proj", "docs/README.md");
		}

		@Test
		@DisplayName("getMarkdownSection defaults includeSubsections to true when omitted")
		void sectionDefaultsTrue()
		{
			when(markdownService.getSection("proj", "docs/README.md", "Intro", true)).thenReturn("SECTION");
			assertEquals("SECTION", server.getMarkdownSection("proj", "docs/README.md", "Intro", null));
			verify(markdownService).getSection("proj", "docs/README.md", "Intro", true);
		}

		@Test
		@DisplayName("getMarkdownSection parses includeSubsections=\"false\"")
		void sectionParsesFalse()
		{
			when(markdownService.getSection("proj", "docs/README.md", "2", false)).thenReturn("SECTION");
			assertEquals("SECTION", server.getMarkdownSection("proj", "docs/README.md", "2", "false"));
			verify(markdownService).getSection("proj", "docs/README.md", "2", false);
		}
	}

	// =======================================================================
	// IdeStateService delegation - console / editor / errors parsing
	// =======================================================================

	@Nested
	class IdeStateDelegation
	{
		@Test
		@DisplayName("getCurrentlyOpenedFile delegates with no arguments")
		void currentlyOpenedFile()
		{
			when(ideStateService.getCurrentlyOpenedFile()).thenReturn("FILE");
			assertEquals("FILE", server.getCurrentlyOpenedFile());
			verify(ideStateService).getCurrentlyOpenedFile();
		}

		@Test
		@DisplayName("getEditorSelection delegates with no arguments")
		void editorSelection()
		{
			when(ideStateService.getEditorSelection()).thenReturn("SELECTION");
			assertEquals("SELECTION", server.getEditorSelection());
			verify(ideStateService).getEditorSelection();
		}

		@Test
		@DisplayName("getConsoleOutput defaults lines=100 and the three booleans to false when all optional args are null")
		void consoleDefaults()
		{
			when(ideStateService.getConsoleOutput(null, 100, false, false)).thenReturn("CONSOLE");
			assertEquals("CONSOLE", server.getConsoleOutput(null, null, null, null));
			verify(ideStateService).getConsoleOutput(null, 100, false, false);
		}

		@Test
		@DisplayName("getConsoleOutput parses maxLines, includeAllConsoles and clear")
		void consoleParsesAll()
		{
			when(ideStateService.getConsoleOutput("c1", 250, true, true)).thenReturn("CONSOLE");
			assertEquals("CONSOLE", server.getConsoleOutput("c1", "250", "true", "true"));
			verify(ideStateService).getConsoleOutput("c1", 250, true, true);
		}

		@Test
		@DisplayName("getCompilationErrors defaults maxResults=50 and waitForBuild=true when omitted")
		void errorsDefaults()
		{
			when(ideStateService.getCompilationErrors("proj", "ERROR", 50, "*.js", true)).thenReturn("ERRORS");
			assertEquals("ERRORS", server.getCompilationErrors("proj", "ERROR", null, "*.js", null));
			verify(ideStateService).getCompilationErrors("proj", "ERROR", 50, "*.js", true);
		}

		@Test
		@DisplayName("getCompilationErrors treats waitForBuild=\"false\" (case-insensitive) as not waiting")
		void errorsNoWait()
		{
			when(ideStateService.getCompilationErrors(null, null, 50, null, false)).thenReturn("ERRORS");
			assertEquals("ERRORS", server.getCompilationErrors(null, null, null, null, "FALSE"));
			verify(ideStateService).getCompilationErrors(null, null, 50, null, false);
		}

		@Test
		@DisplayName("getCompilationErrors parses a supplied maxResults")
		void errorsParsesMax()
		{
			when(ideStateService.getCompilationErrors(null, null, 5, null, true)).thenReturn("ERRORS");
			assertEquals("ERRORS", server.getCompilationErrors(null, null, "5", null, "true"));
			verify(ideStateService).getCompilationErrors(null, null, 5, null, true);
		}
	}

	// =======================================================================
	// getSource - blank guard, resolver-not-found short-circuit, delegation
	// =======================================================================

	@Nested
	class GetSource
	{
		@Test
		@DisplayName("getSource throws for a null name before touching the resolver")
		void nullNameThrows()
		{
			RuntimeException ex = assertThrows(RuntimeException.class, () -> server.getSource(null, null));
			assertTrue(ex.getMessage().contains("'name' is required"), "got: " + ex.getMessage());
			verify(scriptResolver, never()).resolveScript(anyString(), any());
		}

		@Test
		@DisplayName("getSource throws for a blank name before touching the resolver")
		void blankNameThrows()
		{
			assertThrows(RuntimeException.class, () -> server.getSource("   ", null));
			verify(scriptResolver, never()).resolveScript(anyString(), any());
		}

		@Test
		@DisplayName("getSource returns the resolver's not-found message when the script cannot be resolved")
		void notFound()
		{
			when(scriptResolver.resolveScript("ghost", "mod")).thenReturn(null);
			when(scriptResolver.buildNotFoundMessage("ghost", "mod")).thenReturn("Not found: ghost");
			assertEquals("Not found: ghost", server.getSource("ghost", "mod"));
			verify(workspaceService, never()).readProjectResource(anyString(), anyString(), anyBoolean(), anyInt(), anyInt());
		}

		@Test
		@DisplayName("getSource reads the resolved file's project resource with line numbers")
		void resolvedReadsResource()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.readProjectResource("myProj", "forms/customers.js", true, 0, 0)).thenReturn("SRC");
			assertEquals("SRC", server.getSource("customers", null));
			verify(workspaceService).readProjectResource("myProj", "forms/customers.js", true, 0, 0);
		}
	}

	// =======================================================================
	// getClassOutline - resolver-not-found short-circuit + FileStructure formatting
	// =======================================================================

	@Nested
	class GetClassOutline
	{
		@Test
		@DisplayName("getClassOutline returns the resolver's not-found message when unresolved")
		void notFound()
		{
			when(scriptResolver.resolveScript("ghost", null)).thenReturn(null);
			when(scriptResolver.buildNotFoundMessage("ghost", null)).thenReturn("Not found: ghost");
			assertEquals("Not found: ghost", server.getClassOutline("ghost", null));
			verify(fileStructureService, never()).analyzeFile(any());
		}

		@Test
		@DisplayName("getClassOutline analyzes the resolved file and returns the structure's formatted outline")
		void resolvedFormatsStructure()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			FileStructure structure = new FileStructure("forms/customers.js",
				List.of(new SymbolInfo("onLoad", org.eclipse.dltk.core.IModelElement.METHOD, 0, 0, 7, new String[] { "event" })));
			when(fileStructureService.analyzeFile(file)).thenReturn(structure);
			String result = server.getClassOutline("customers", null);
			verify(fileStructureService).analyzeFile(file);
			assertTrue(result.contains("# File Structure: forms/customers.js"), "got: " + result);
			assertTrue(result.contains("Line 7: onLoad(event)"), "got: " + result);
		}
	}

	// =======================================================================
	// getMethodSource - resolver-not-found, blank methodNames guard, per-method format
	// =======================================================================

	@Nested
	class GetMethodSource
	{
		@Test
		@DisplayName("getMethodSource returns the resolver's not-found message when unresolved")
		void notFound()
		{
			when(scriptResolver.resolveScript("ghost", null)).thenReturn(null);
			when(scriptResolver.buildNotFoundMessage("ghost", null)).thenReturn("Not found: ghost");
			assertEquals("Not found: ghost", server.getMethodSource("ghost", "onLoad", null, null));
		}

		@Test
		@DisplayName("getMethodSource requires a non-blank methodNames after the file resolves")
		void blankMethodNames()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			String result = server.getMethodSource("customers", "   ", null, null);
			assertTrue(result.startsWith("Error:") && result.contains("methodNames"), "got: " + result);
			verify(workspaceService, never()).readFunction(anyString(), anyString(), anyString());
		}

		@Test
		@DisplayName("getMethodSource requires a non-null methodNames after the file resolves")
		void nullMethodNames()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			String result = server.getMethodSource("customers", null, null, null);
			assertTrue(result.startsWith("Error:") && result.contains("methodNames"), "got: " + result);
			verify(workspaceService, never()).readFunction(anyString(), anyString(), anyString());
		}

		@Test
		@DisplayName("getMethodSource renders each requested function and skips blank names in the list")
		void rendersEachMethod()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.readFunction("myProj", "forms/customers.js", "onLoad"))
				.thenReturn(new FunctionResult("/p/a.js", "onLoad", 1, 3, "function onLoad(){}\n"));
			when(workspaceService.readFunction("myProj", "forms/customers.js", "save"))
				.thenReturn(new FunctionResult("/p/a.js", "save", 5, 9, "function save(){}\n"));
			String result = server.getMethodSource("customers", "onLoad, , save", null, null);
			assertTrue(result.contains("## onLoad (lines 1-3)"), "got: " + result);
			assertTrue(result.contains("## save (lines 5-9)"), "got: " + result);
			verify(workspaceService).readFunction("myProj", "forms/customers.js", "onLoad");
			verify(workspaceService).readFunction("myProj", "forms/customers.js", "save");
		}

		@Test
		@DisplayName("getMethodSource catches a per-function RuntimeException and renders its message under the name")
		void perFunctionErrorCaught()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.readFunction("myProj", "forms/customers.js", "ghost"))
				.thenThrow(new RuntimeException("Function 'ghost' not found"));
			String result = server.getMethodSource("customers", "ghost", null, null);
			assertTrue(result.contains("## ghost"), "got: " + result);
			assertTrue(result.contains("Function 'ghost' not found"), "the per-function error is rendered, not thrown; got: " + result);
		}
	}

	// =======================================================================
	// getFilteredSource - resolver-not-found, empty-outline full-file fallback, expand/collapse
	// =======================================================================

	@Nested
	class GetFilteredSource
	{
		@Test
		@DisplayName("getFilteredSource returns the resolver's not-found message when unresolved")
		void notFound()
		{
			when(scriptResolver.resolveScript("ghost", null)).thenReturn(null);
			when(scriptResolver.buildNotFoundMessage("ghost", null)).thenReturn("Not found: ghost");
			assertEquals("Not found: ghost", server.getFilteredSource("ghost", null, null, null));
		}

		@Test
		@DisplayName("getFilteredSource falls back to the full file when the outline is empty")
		void emptyOutlineFullFile()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.getFileOutline("myProj", "forms/customers.js")).thenReturn(List.of());
			when(workspaceService.readProjectResource("myProj", "forms/customers.js", true, 0, 0)).thenReturn("FULL");
			assertEquals("FULL", server.getFilteredSource("customers", null, null, null));
			verify(workspaceService).readProjectResource("myProj", "forms/customers.js", true, 0, 0);
		}

		@Test
		@DisplayName("getFilteredSource expands only the requested functions and collapses the rest to a signature line")
		void expandRequestedCollapseRest()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.getFileOutline("myProj", "forms/customers.js"))
				.thenReturn(List.of(new OutlineEntry(5, "onLoad"), new OutlineEntry(20, "save")));
			when(workspaceService.readFunction("myProj", "forms/customers.js", "onLoad"))
				.thenReturn(new FunctionResult("/p/a.js", "onLoad", 5, 9, "function onLoad(){}\n"));
			String result = server.getFilteredSource("customers", "onLoad", null, null);
			assertTrue(result.contains("function onLoad()"), "requested function expanded; got: " + result);
			assertTrue(result.contains("Line 20: save()"), "unrequested function collapsed; got: " + result);
			verify(workspaceService).readFunction("myProj", "forms/customers.js", "onLoad");
			verify(workspaceService, never()).readFunction("myProj", "forms/customers.js", "save");
		}

		@Test
		@DisplayName("getFilteredSource with a blank (non-null) methodNames expands every function in the outline")
		void blankMethodNamesExpandsAll()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.getFileOutline("myProj", "forms/customers.js"))
				.thenReturn(List.of(new OutlineEntry(5, "onLoad")));
			when(workspaceService.readFunction("myProj", "forms/customers.js", "onLoad"))
				.thenReturn(new FunctionResult("/p/a.js", "onLoad", 5, 9, "function onLoad(){}\n"));
			String result = server.getFilteredSource("customers", "   ", null, null);
			assertTrue(result.contains("function onLoad()"), "got: " + result);
			verify(workspaceService).readFunction("myProj", "forms/customers.js", "onLoad");
		}

		@Test
		@DisplayName("getFilteredSource with no methodNames expands every function in the outline")
		void noMethodNamesExpandsAll()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.getFileOutline("myProj", "forms/customers.js"))
				.thenReturn(List.of(new OutlineEntry(5, "onLoad")));
			when(workspaceService.readFunction("myProj", "forms/customers.js", "onLoad"))
				.thenReturn(new FunctionResult("/p/a.js", "onLoad", 5, 9, "function onLoad(){}\n"));
			String result = server.getFilteredSource("customers", null, null, null);
			assertTrue(result.contains("function onLoad()"), "got: " + result);
			verify(workspaceService).readFunction("myProj", "forms/customers.js", "onLoad");
		}

		@Test
		@DisplayName("getFilteredSource collapses a function whose body cannot be read with a '[could not read]' note")
		void couldNotReadCollapses()
		{
			IFile file = mockFile("myProj", "forms/customers.js");
			when(scriptResolver.resolveScript("customers", null)).thenReturn(file);
			when(workspaceService.getFileOutline("myProj", "forms/customers.js"))
				.thenReturn(List.of(new OutlineEntry(5, "onLoad")));
			when(workspaceService.readFunction("myProj", "forms/customers.js", "onLoad"))
				.thenThrow(new RuntimeException("boom"));
			String result = server.getFilteredSource("customers", "onLoad", null, null);
			assertTrue(result.contains("[could not read]"), "got: " + result);
		}
	}

	// =======================================================================
	// CodeAnalysisService delegation
	// =======================================================================

	@Nested
	class CodeAnalysisDelegation
	{
		@Test
		@DisplayName("findReferences forwards fullyQualifiedClassName/elementName and passes the result through")
		void findReferences()
		{
			when(codeAnalysisService.findReferences("forms.myForm", "doIt")).thenReturn("REFS");
			assertEquals("REFS", server.findReferences("forms.myForm", "doIt"));
			verify(codeAnalysisService).findReferences("forms.myForm", "doIt");
		}

		@Test
		@DisplayName("getTypeHierarchy forwards the form name verbatim")
		void getTypeHierarchy()
		{
			when(codeAnalysisService.getTypeHierarchy("forms.base")).thenReturn("HIERARCHY");
			assertEquals("HIERARCHY", server.getTypeHierarchy("forms.base"));
			verify(codeAnalysisService).getTypeHierarchy("forms.base");
		}

		@Test
		@DisplayName("getMethodCallHierarchy forwards class/method/signature/maxDepth verbatim")
		void getMethodCallHierarchy()
		{
			when(codeAnalysisService.getMethodCallHierarchy("forms.myForm", "doIt", "sig", "3")).thenReturn("CALLS");
			assertEquals("CALLS", server.getMethodCallHierarchy("forms.myForm", "doIt", "sig", "3"));
			verify(codeAnalysisService).getMethodCallHierarchy("forms.myForm", "doIt", "sig", "3");
		}

		@Test
		@DisplayName("executeQuickFix parses the markerId (long) and proposalIndex (int) before delegating")
		void executeQuickFixParsesArgs()
		{
			when(codeAnalysisService.executeQuickFix(42L, -1)).thenReturn("FIXES");
			assertEquals("FIXES", server.executeQuickFix("42", "-1"));
			verify(codeAnalysisService).executeQuickFix(42L, -1);
		}

		@Test
		@DisplayName("executeQuickFix propagates a NumberFormatException for a non-numeric markerId and never delegates")
		void executeQuickFixRejectsNonNumericMarker()
		{
			assertThrows(NumberFormatException.class, () -> server.executeQuickFix("not-a-marker", "0"));
			verify(codeAnalysisService, never()).executeQuickFix(org.mockito.ArgumentMatchers.anyLong(), anyInt());
		}
	}

	// =======================================================================
	// Delegate exceptions propagate unchanged for the thin pass-through tools
	// =======================================================================

	@Nested
	class DelegateExceptionPropagation
	{
		@Test
		@DisplayName("a RuntimeException thrown by a delegate propagates out of the thin wrapper unchanged")
		void propagatesUnchanged()
		{
			RuntimeException boom = new RuntimeException("Project not found: X");
			when(projectService.getProjectProperties("missing")).thenThrow(boom);
			RuntimeException thrown = assertThrows(RuntimeException.class, () -> server.getProjectProperties("missing"));
			assertSame(boom, thrown, "the server must not wrap or swallow the delegate's exception");
		}
	}

	// =======================================================================
	// Private static pure helpers (reflection): parseMaxResults / parsePatterns / formatSearchResults
	// =======================================================================

	@Nested
	class PureHelpers
	{
		private int parseMaxResults(String maxResults) throws Exception
		{
			Method m = ServoyIdeServer.class.getDeclaredMethod("parseMaxResults", String.class);
			m.setAccessible(true);
			return (int)m.invoke(null, maxResults);
		}

		private String[] parsePatterns(String fileNamePatterns) throws Exception
		{
			Method m = ServoyIdeServer.class.getDeclaredMethod("parsePatterns", String.class);
			m.setAccessible(true);
			return (String[])m.invoke(null, fileNamePatterns);
		}

		private String formatSearchResults(SearchResults results) throws Exception
		{
			Method m = ServoyIdeServer.class.getDeclaredMethod("formatSearchResults", SearchResults.class);
			m.setAccessible(true);
			return (String)m.invoke(null, results);
		}

		@Test
		@DisplayName("parseMaxResults returns the default for null, blank or non-numeric input")
		void parseMaxResultsDefaults() throws Exception
		{
			assertEquals(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, parseMaxResults(null));
			assertEquals(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, parseMaxResults("   "));
			assertEquals(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, parseMaxResults("lots"));
		}

		@Test
		@DisplayName("parseMaxResults parses a valid trimmed number")
		void parseMaxResultsParses() throws Exception
		{
			assertEquals(25, parseMaxResults("  25  "));
		}

		@Test
		@DisplayName("parsePatterns returns an empty array for null or blank input")
		void parsePatternsEmpty() throws Exception
		{
			assertEquals(0, parsePatterns(null).length);
			assertEquals(0, parsePatterns("   ").length);
		}

		@Test
		@DisplayName("parsePatterns splits on commas and trims each pattern")
		void parsePatternsSplitsAndTrims() throws Exception
		{
			assertArrayEquals(new String[] { "*.java", "*.xml", "*.frm" }, parsePatterns(" *.java , *.xml ,*.frm"));
		}

		@Test
		@DisplayName("formatSearchResults reports 'No matches found.' for an empty match list")
		void formatEmpty() throws Exception
		{
			assertEquals("No matches found.", formatSearchResults(new SearchResults(List.of(), false)));
		}

		@Test
		@DisplayName("formatSearchResults renders the non-truncated header and each trimmed match")
		void formatNonTruncated() throws Exception
		{
			String out = formatSearchResults(new SearchResults(
				List.of(new SearchResult("a.js", 3, "   var x;   ")), false));
			assertTrue(out.contains("# Search Results (1 match(es))"), "got: " + out);
			assertTrue(out.contains("a.js:3 - var x;"), "got: " + out);
			assertFalse(out.contains("truncated"), "got: " + out);
		}

		@Test
		@DisplayName("formatSearchResults renders the truncated header when the search was capped")
		void formatTruncated() throws Exception
		{
			String out = formatSearchResults(new SearchResults(
				List.of(new SearchResult("a.js", 3, "var x;")), true));
			assertTrue(out.contains("truncated"), "got: " + out);
			assertTrue(out.contains("first 1 match(es) shown"), "got: " + out);
		}

		private void assertArrayEquals(String[] expected, String[] actual)
		{
			assertEquals(Arrays.asList(expected), Arrays.asList(actual));
		}
	}
}

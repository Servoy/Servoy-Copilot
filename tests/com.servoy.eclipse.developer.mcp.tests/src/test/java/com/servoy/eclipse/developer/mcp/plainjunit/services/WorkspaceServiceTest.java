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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.servoy.eclipse.developer.mcp.ResourceNotFoundException;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FileContextResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FileInfo;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.FunctionResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.OutlineEntry;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.RangeResult;

/**
 * Plain-JUnit unit tests for {@link WorkspaceService}'s <em>own</em> logic - the
 * line/offset math, occurrence counting, glob-to-regex translation, {@code IFile}
 * content reading, and the line-number rendering / range-parsing / context-window /
 * file-info / outline / brace-matching logic that the file-reading tools perform.
 * <p>
 * Following the "test Copilot, mock Eclipse core" principle (see AGENTS.md "Test
 * strategy"), the Eclipse workspace is mocked at the boundary: the service's
 * {@code getWorkspaceRoot()} seam is overridden to return a mocked
 * {@link IWorkspaceRoot} whose {@code getProject(..)} yields a mocked
 * {@link IProject} and whose {@code getFile(..)} yields a mocked {@link IFile}
 * with canned bytes. We assert only OUR parsing/offset/range/format output, never
 * Eclipse's real file or search behaviour.
 * </p>
 * <p>
 * What stays for the integration suite (not unit-reachable here): everything that
 * runs Eclipse's static {@code TextSearchEngine.createDefault()} over real
 * workspace roots - {@code findFiles}, {@code fileSearch(RegExp)},
 * {@code searchAndReplace}, the private {@code search(..)} and
 * {@code getOpenProjectsAsRoots()} - and {@code readProjectResource}'s
 * {@code java.nio.file} workspace-root fallback (its happy path is driven here via
 * the mocked project; the fallback is covered with a {@link org.junit.jupiter.api.io.TempDir}).
 * </p>
 *
 * @since 2026.06
 */
class WorkspaceServiceTest {

	// ---------------------------------------------------------------------------------------------
	// Reflection helpers for the private/static helpers (no visibility widening in production).
	// ---------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static List<String> splitLines(String content) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("splitLines", String.class);
		m.setAccessible(true);
		return (List<String>) m.invoke(null, content);
	}

	private static int[] lineStartOffsets(String content) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("lineStartOffsets", String.class);
		m.setAccessible(true);
		return (int[]) m.invoke(null, content);
	}

	private static int lineIndexFor(int[] starts, int offset) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("lineIndexFor", int[].class, int.class);
		m.setAccessible(true);
		return (int) m.invoke(null, starts, offset);
	}

	private static int countOccurrences(String text, String needle) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("countOccurrences", String.class, String.class);
		m.setAccessible(true);
		return (int) m.invoke(null, text, needle);
	}

	private static String globToRegex(String glob) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("globToRegex", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, glob);
	}

	private static List<String> readFileLines(IFile file) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("readFileLines", IFile.class);
		m.setAccessible(true);
		return (List<String>) m.invoke(null, file);
	}

	private static String readFileContent(IFile file) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("readFileContent", IFile.class);
		m.setAccessible(true);
		return (String) m.invoke(null, file);
	}

	private static int replaceInFile(IFile file, String containing, String replacement) throws Exception {
		Method m = WorkspaceService.class.getDeclaredMethod("replaceInFile", IFile.class, String.class, String.class);
		m.setAccessible(true);
		try {
			return (int) m.invoke(null, file, containing, replacement);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception ex) throw ex;
			throw e;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Mock scaffolding: a service whose workspace root resolves to a mocked IFile with canned bytes.
	// ---------------------------------------------------------------------------------------------

	/**
	 * A mocked IFile that hands back {@code contentSupplier()} on each getContents() call. Only the
	 * IFile interface is used by the methods under test (no IResource/IContainer cast), so a plain
	 * mock(IFile.class) is sufficient - no extraInterfaces needed.
	 */
	private static IFile mockFile(String fullPath, String name, String charset, boolean exists, String content) {
		IFile file = mock(IFile.class);
		when(file.exists()).thenReturn(exists);
		when(file.getName()).thenReturn(name);
		IPath p = IPath.fromOSString(fullPath);
		when(file.getFullPath()).thenReturn(p);
		try {
			when(file.getCharset()).thenReturn(charset);
			if (content != null) {
				when(file.getContents()).thenAnswer(inv -> new ByteArrayInputStream(content.getBytes(charset)));
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return file;
	}

	/** A WorkspaceService whose seam resolves projectName -> project(exists) -> getFile(any) -> file. */
	private static WorkspaceService serviceReturning(IFile file, boolean projectExists, boolean projectOpen) {
		IWorkspaceRoot root = mock(IWorkspaceRoot.class);
		IProject project = mock(IProject.class);
		when(project.exists()).thenReturn(projectExists);
		when(project.isOpen()).thenReturn(projectOpen);
		when(root.getProject(anyString())).thenReturn(project);
		when(project.getFile(any(IPath.class))).thenReturn(file);
		try {
			when(project.hasNature(anyString())).thenReturn(false);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return new WorkspaceService() {
			@Override
			protected IWorkspaceRoot getWorkspaceRoot() {
				return root;
			}
		};
	}

	private static WorkspaceService serviceReturning(IFile file) {
		return serviceReturning(file, true, true);
	}

	// =============================================================================================
	// TIER A - pure helpers, no mock/seam.
	// =============================================================================================

	@Nested
	class SplitLinesAndOffsets {
		@Test
		void splitLinesHandlesLfCrlfAndLoneCr() throws Exception {
			assertEquals(List.of("a", "b", "c"), splitLines("a\nb\nc"));
			assertEquals(List.of("a", "b", "c"), splitLines("a\r\nb\r\nc"));
			assertEquals(List.of("a", "b", "c"), splitLines("a\rb\rc"));
		}

		@Test
		void splitLinesDropsSingleTrailingNewlineButKeepsContentLines() throws Exception {
			// trailing newline -> no phantom empty final line
			assertEquals(List.of("a", "b"), splitLines("a\nb\n"));
			assertEquals(List.of("a", "b"), splitLines("a\r\nb\r\n"));
			// a blank line in the MIDDLE is preserved
			assertEquals(List.of("a", "", "b"), splitLines("a\n\nb"));
		}

		@Test
		void splitLinesEmptyAndNull() throws Exception {
			assertTrue(splitLines("").isEmpty());
			assertTrue(splitLines(null).isEmpty());
			assertEquals(List.of("only"), splitLines("only"));
		}

		@Test
		void lineStartOffsetsCountCrlfAsTwoChars() throws Exception {
			// "ab\r\ncd" -> line0 @0, line1 @4 (\r and \n both counted)
			assertArrayEquals(new int[] { 0, 4 }, lineStartOffsets("ab\r\ncd"));
			// LF: "ab\ncd" -> line0 @0, line1 @3
			assertArrayEquals(new int[] { 0, 3 }, lineStartOffsets("ab\ncd"));
			// lone CR: "ab\rcd" -> line0 @0, line1 @3
			assertArrayEquals(new int[] { 0, 3 }, lineStartOffsets("ab\rcd"));
		}

		@Test
		void lineStartOffsetsEmptyAndTrailingNewline() throws Exception {
			assertArrayEquals(new int[0], lineStartOffsets(""));
			assertArrayEquals(new int[0], lineStartOffsets(null));
			// trailing newline produces no start past the end (parallel to splitLines dropping it)
			assertArrayEquals(new int[] { 0 }, lineStartOffsets("a\n"));
			assertArrayEquals(new int[] { 0, 2 }, lineStartOffsets("a\nb"));
		}

		@Test
		void splitLinesAndOffsetsStayParallelForCrlf() throws Exception {
			String content = "alpha\r\nbeta\r\ngamma";
			List<String> lines = splitLines(content);
			int[] starts = lineStartOffsets(content);
			assertEquals(lines.size(), starts.length, "one start per line");
			// each start indexes the right line text in the original content
			for (int i = 0; i < lines.size(); i++) {
				assertTrue(content.startsWith(lines.get(i), starts[i]),
						"line " + i + " must begin at its recorded offset");
			}
		}
	}

	@Nested
	class LineIndexFor {
		@Test
		void mapsOffsetToContainingLine() throws Exception {
			int[] starts = { 0, 4, 8 }; // three lines
			assertEquals(0, lineIndexFor(starts, 0));
			assertEquals(0, lineIndexFor(starts, 3));
			assertEquals(1, lineIndexFor(starts, 4));
			assertEquals(1, lineIndexFor(starts, 7));
			assertEquals(2, lineIndexFor(starts, 8));
		}

		@Test
		void offsetPastEndBelongsToLastLine() throws Exception {
			int[] starts = { 0, 4, 8 };
			assertEquals(2, lineIndexFor(starts, 999));
		}

		@Test
		void negativeOffsetOrEmptyStartsReturnMinusOne() throws Exception {
			assertEquals(-1, lineIndexFor(new int[] { 0, 4 }, -1));
			assertEquals(-1, lineIndexFor(new int[0], 5));
			assertEquals(-1, lineIndexFor(null, 5));
		}
	}

	@Nested
	class CountOccurrences {
		@Test
		void countsNonOverlapping() throws Exception {
			assertEquals(3, countOccurrences("a.a.a", "a"));
			assertEquals(2, countOccurrences("abcabc", "bc"));
		}

		@Test
		void nonOverlappingAdvancesByNeedleLength() throws Exception {
			// "aaaa" with needle "aa" -> 2 (non-overlapping), not 3
			assertEquals(2, countOccurrences("aaaa", "aa"));
		}

		@Test
		void noneAndEmptyInputs() throws Exception {
			assertEquals(0, countOccurrences("abc", "x"));
			assertEquals(0, countOccurrences("", "a"));
			assertEquals(0, countOccurrences("abc", ""));
			assertEquals(0, countOccurrences(null, "a"));
			assertEquals(0, countOccurrences("abc", null));
		}
	}

	@Nested
	class GlobTranslation {
		@Test
		void starBecomesDotStarAndQuestionBecomesDot() throws Exception {
			assertEquals(".*\\.java", globToRegex("*.java"));
			assertEquals("foo.", globToRegex("foo?"));
		}

		@Test
		void regexMetacharsAreEscaped() throws Exception {
			assertEquals("\\.\\^\\$\\+\\{\\}\\[\\]\\(\\)\\|\\\\",
					globToRegex(".^$+{}[]()|\\"));
		}

		@Test
		void blankOrNullGlobDefaultsToStar() throws Exception {
			assertEquals(".*", globToRegex(null));
			assertEquals(".*", globToRegex("   "));
			assertEquals(".*", globToRegex(""));
		}

		@Test
		void globPatternsToRegexAnchorsAndJoinsWithAlternation() {
			Pattern p = WorkspaceService.globPatternsToRegex("*.java", "*.xml");
			assertEquals("^(?:.*\\.java|.*\\.xml)$", p.pattern());
			assertTrue(p.matcher("Foo.java").matches());
			assertTrue(p.matcher("beans.xml").matches());
			assertFalse(p.matcher("Foo.jav").matches(), "anchored $ must reject a partial match");
			assertFalse(p.matcher("xFoo.txt").matches());
		}

		@Test
		void globPatternsToRegexNullOrEmptyMatchesEverything() {
			assertEquals(".*", WorkspaceService.globPatternsToRegex().pattern());
			assertEquals(".*", WorkspaceService.globPatternsToRegex((String[]) null).pattern());
			assertTrue(WorkspaceService.globPatternsToRegex().matcher("anything.at.all").matches());
		}
	}

	// helper: JUnit 5 has no assertArrayEquals import pull-through above; use Assertions directly
	private static void assertArrayEquals(int[] expected, int[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}

	// =============================================================================================
	// TIER B - take an IFile directly (mock it).
	// =============================================================================================

	@Nested
	class ReadFileContentAndLines {
		@Test
		void readFileContentReturnsExactBytesDecoded() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "line1\nline2\n");
			assertEquals("line1\nline2\n", readFileContent(file));
		}

		@Test
		void readFileLinesSplitsOnNewlinesDroppingTrailing() throws Exception {
			// BufferedReader.readLine drops the final newline; two content lines expected
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "line1\nline2\n");
			assertEquals(List.of("line1", "line2"), readFileLines(file));
		}

		@Test
		void readFileLinesHandlesCrlf() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "a\r\nb\r\nc");
			assertEquals(List.of("a", "b", "c"), readFileLines(file));
		}
	}

	@Nested
	class ReplaceInFile {
		@Test
		void replacesAllOccurrencesAndWritesBack() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "foo bar foo baz foo");
			int count = replaceInFile(file, "foo", "QUX");
			assertEquals(3, count, "three occurrences counted");

			ArgumentCaptor<ByteArrayInputStream> captor = ArgumentCaptor.forClass(ByteArrayInputStream.class);
			verify(file).setContents(captor.capture(), eqForce(), (org.eclipse.core.runtime.IProgressMonitor) any());
			String written = new String(captor.getValue().readAllBytes(), StandardCharsets.UTF_8);
			assertEquals("QUX bar QUX baz QUX", written);
			verify(file).refreshLocal(IResource.DEPTH_ZERO, null);
		}

		@Test
		void noOccurrencesLeavesFileUntouched() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "nothing here");
			int count = replaceInFile(file, "absent", "X");
			assertEquals(0, count);
			verify(file, times(0)).setContents((java.io.InputStream) any(), eqForce(), (org.eclipse.core.runtime.IProgressMonitor) any());
		}

		@Test
		void nonExistentFileReturnsZero() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", false, null);
			assertEquals(0, replaceInFile(file, "x", "y"));
			assertEquals(0, replaceInFile(null, "x", "y"));
		}

		private static int eqForce() {
			return org.mockito.ArgumentMatchers.eq(IResource.FORCE);
		}

		/**
		 * searchAndReplace is a write like any other and must go through WriteAccessValidator (SVY-21303,
		 * rounds 3 and 4): under a pessimistic VCS a read-only file has to be checked out before it is
		 * written. A read-only file whose workspace refuses the edit must therefore not be written.
		 */
		@Test
		void aRefusedEditOnAReadOnlyFileIsNotWritten() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "foo");
			when(file.isReadOnly()).thenReturn(true);

			org.eclipse.core.resources.IWorkspace workspace = mock(org.eclipse.core.resources.IWorkspace.class);
			when(workspace.validateEdit(any(IFile[].class), any()))
				.thenReturn(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR, "test", "checked out by someone else"));

			try (org.mockito.MockedStatic<org.eclipse.core.resources.ResourcesPlugin> plugin =
				org.mockito.Mockito.mockStatic(org.eclipse.core.resources.ResourcesPlugin.class)) {
				plugin.when(org.eclipse.core.resources.ResourcesPlugin::getWorkspace).thenReturn(workspace);

				RuntimeException refused = assertThrows(RuntimeException.class, () -> replaceInFile(file, "foo", "bar"));
				assertTrue(refused.getMessage().contains("checked out by someone else"), refused.getMessage());
			}
			verify(file, times(0)).setContents((java.io.InputStream) any(), eqForce(), (org.eclipse.core.runtime.IProgressMonitor) any());
		}

		@Test
		void aWritableFileSkipsTheValidationAndIsWritten() throws Exception {
			IFile file = mockFile("/p/a.txt", "a.txt", "UTF-8", true, "foo");
			when(file.isReadOnly()).thenReturn(false);

			assertEquals(1, replaceInFile(file, "foo", "bar"));
			verify(file).setContents((java.io.InputStream) any(), eqForce(), (org.eclipse.core.runtime.IProgressMonitor) any());
		}
	}

	// =============================================================================================
	// TIER C - resolve-via-root, then own processing (seam).
	// =============================================================================================

	@Nested
	class ReadProjectResource {
		@Test
		void rendersLineNumbersAndHeaderWithRange() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "one\ntwo\nthree\nfour");
			WorkspaceService svc = serviceReturning(file);
			String out = svc.readProjectResource("p", "f.txt", true, 2, 3);
			assertTrue(out.contains("(lines 2-3 of 4)"), out);
			assertTrue(out.contains("2\ttwo"), out);
			assertTrue(out.contains("3\tthree"), out);
			assertFalse(out.contains("one"), "line 1 must be outside the 2-3 slice");
			assertFalse(out.contains("four"), "line 4 must be outside the 2-3 slice");
		}

		@Test
		void withoutLineNumbersEmitsRawLines() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "alpha\nbeta");
			WorkspaceService svc = serviceReturning(file);
			String out = svc.readProjectResource("p", "f.txt", false, 1, 2);
			assertTrue(out.contains("\nalpha\n"), out);
			assertFalse(out.matches("(?s).*\\d\\talpha.*"), "no line-number prefix when showLineNumbers=false");
		}

		@Test
		void defaultEndClampsToMaxLinesDefault() {
			// build a file larger than MAX_LINES_DEFAULT so the truncation note fires
			StringBuilder sb = new StringBuilder();
			int n = WorkspaceService.MAX_LINES_DEFAULT + 50;
			for (int i = 1; i <= n; i++) sb.append("L").append(i).append("\n");
			IFile file = mockFile("/p/big.txt", "big.txt", "UTF-8", true, sb.toString());
			WorkspaceService svc = serviceReturning(file);
			String out = svc.readProjectResource("p", "big.txt", true, 0, 0);
			assertTrue(out.contains("(lines 1-" + WorkspaceService.MAX_LINES_DEFAULT + " of " + n + ")"), out);
			assertTrue(out.contains("[truncated at " + WorkspaceService.MAX_LINES_DEFAULT + " lines"), out);
		}

		@Test
		void missingProjectThrowsResourceNotFound() {
			// project.exists()=false AND no FS fallback (getLocation() null-ish via real workspace) -> throws
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(false);
			when(root.getProject(anyString())).thenReturn(project);
			when(root.getLocation()).thenReturn(IPath.fromOSString(System.getProperty("java.io.tmpdir")));
			WorkspaceService svc = new WorkspaceService() {
				@Override
				protected IWorkspaceRoot getWorkspaceRoot() {
					return root;
				}
			};
			assertThrows(ResourceNotFoundException.class,
					() -> svc.readProjectResource("nope", "does/not/exist-" + System.nanoTime() + ".txt", true, 0, 0));
		}

		@Test
		void closedProjectThrows() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "x");
			WorkspaceService svc = serviceReturning(file, true, false);
			assertThrows(ResourceNotFoundException.class, () -> svc.readProjectResource("p", "f.txt", true, 0, 0));
		}
	}

	@Nested
	class GetFileInfo {
		@Test
		void missingProjectReportsNonExistentInfo() {
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(false);
			when(root.getProject(anyString())).thenReturn(project);
			WorkspaceService svc = new WorkspaceService() {
				@Override
				protected IWorkspaceRoot getWorkspaceRoot() {
					return root;
				}
			};
			FileInfo info = svc.getFileInfo("gone", "sub/dir/file.js");
			assertFalse(info.exists());
			assertEquals(-1, info.lineCount());
			assertEquals(-1, info.sizeBytes());
			assertEquals("file.js", info.fileName());
			assertEquals("gone", info.projectName());
		}

		@Test
		void missingFileReportsNonExistentInfo() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", false, null);
			WorkspaceService svc = serviceReturning(file);
			FileInfo info = svc.getFileInfo("p", "f.txt");
			assertFalse(info.exists());
			assertEquals(-1, info.lineCount());
			assertEquals("f.txt", info.fileName());
		}
	}

	@Nested
	class ReadFileRanges {
		@Test
		void parsesMultipleRangesAndNumbersLines() {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= 20; i++) sb.append("line").append(i).append("\n");
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, sb.toString());
			WorkspaceService svc = serviceReturning(file);
			List<RangeResult> ranges = svc.readFileRanges("p", "f.txt", "2-4, 10-11");
			assertEquals(2, ranges.size());
			assertEquals(2, ranges.get(0).startLine());
			assertEquals(4, ranges.get(0).endLine());
			assertTrue(ranges.get(0).content().contains("3\tline3"), ranges.get(0).content());
			assertEquals(10, ranges.get(1).startLine());
			assertEquals(11, ranges.get(1).endLine());
		}

		@Test
		void clampsRangesToFileBounds() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "a\nb\nc");
			WorkspaceService svc = serviceReturning(file);
			List<RangeResult> ranges = svc.readFileRanges("p", "f.txt", "0-999");
			assertEquals(1, ranges.get(0).startLine(), "start clamps up to 1");
			assertEquals(3, ranges.get(0).endLine(), "end clamps down to totalLines");
		}

		@Test
		void blankRangesRejected() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "a\nb");
			WorkspaceService svc = serviceReturning(file);
			assertThrows(IllegalArgumentException.class, () -> svc.readFileRanges("p", "f.txt", "  "));
			assertThrows(IllegalArgumentException.class, () -> svc.readFileRanges("p", "f.txt", null));
		}

		@Test
		void malformedRangeRejected() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "a\nb\nc\nd\ne");
			WorkspaceService svc = serviceReturning(file);
			assertThrows(IllegalArgumentException.class, () -> svc.readFileRanges("p", "f.txt", "1_5"));
			assertThrows(IllegalArgumentException.class, () -> svc.readFileRanges("p", "f.txt", "5-2"));
		}
	}

	@Nested
	class ReadFileContext {
		@Test
		void windowsAroundCenterLine() {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= 100; i++) sb.append("L").append(i).append("\n");
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, sb.toString());
			WorkspaceService svc = serviceReturning(file);
			FileContextResult ctx = svc.readFileContext("p", "f.txt", 50, 5);
			assertEquals(100, ctx.totalLines());
			assertEquals(45, ctx.startLine());
			assertEquals(55, ctx.endLine());
			assertEquals(50, ctx.centerLine());
			assertTrue(ctx.content().contains("50\tL50"), ctx.content());
		}

		@Test
		void windowClampsAtFileStartAndDefaultsWindowSize() {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= 10; i++) sb.append("L").append(i).append("\n");
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, sb.toString());
			WorkspaceService svc = serviceReturning(file);
			// windowSize <= 0 defaults to 30; around line 2 that clamps to [1, 10]
			FileContextResult ctx = svc.readFileContext("p", "f.txt", 2, 0);
			assertEquals(1, ctx.startLine());
			assertEquals(10, ctx.endLine());
			assertEquals(30, ctx.windowSize());
		}

		@Test
		void centerOutOfBoundsThrows() {
			IFile file = mockFile("/p/f.txt", "f.txt", "UTF-8", true, "a\nb\nc");
			WorkspaceService svc = serviceReturning(file);
			assertThrows(RuntimeException.class, () -> svc.readFileContext("p", "f.txt", 99, 5));
			assertThrows(RuntimeException.class, () -> svc.readFileContext("p", "f.txt", 0, 5));
		}
	}

	@Nested
	class GetFileOutline {
		@Test
		void extractsFunctionDeclarationsOfEachForm() {
			String js = String.join("\n",
					"function foo() {",
					"  var x = 1;",
					"}",
					"async function bar() {}",
					"var baz = function() {};",
					"qux: function() {}");
			IFile file = mockFile("/p/s.js", "s.js", "UTF-8", true, js);
			WorkspaceService svc = serviceReturning(file);
			List<OutlineEntry> outline = svc.getFileOutline("p", "s.js");
			List<String> names = outline.stream().map(OutlineEntry::functionName).toList();
			assertTrue(names.contains("foo"), names.toString());
			assertTrue(names.contains("bar"), names.toString());
			assertTrue(names.contains("baz"), names.toString());
			assertTrue(names.contains("qux"), names.toString());
			// first entry is "foo" at line 1
			assertEquals("foo", outline.get(0).functionName());
			assertEquals(1, outline.get(0).lineNumber());
		}

		@Test
		void emptyOutlineForFileWithNoFunctions() {
			IFile file = mockFile("/p/s.js", "s.js", "UTF-8", true, "var a = 1;\nvar b = 2;");
			WorkspaceService svc = serviceReturning(file);
			assertTrue(svc.getFileOutline("p", "s.js").isEmpty());
		}
	}

	@Nested
	class ReadFunction {
		@Test
		void readsFullBodyViaBraceMatching() {
			String js = String.join("\n",
					"function outer() {",
					"  if (x) {",
					"    doThing();",
					"  }",
					"}",
					"function after() {}");
			IFile file = mockFile("/p/s.js", "s.js", "UTF-8", true, js);
			WorkspaceService svc = serviceReturning(file);
			FunctionResult fr = svc.readFunction("p", "s.js", "outer");
			assertEquals("outer", fr.functionName());
			assertEquals(1, fr.startLine());
			assertEquals(5, fr.endLine(), "brace matching must stop at the function's closing brace");
			assertTrue(fr.content().contains("doThing();"), fr.content());
			assertFalse(fr.content().contains("after"), "the next function must not leak in");
		}

		@Test
		void missingFunctionThrows() {
			IFile file = mockFile("/p/s.js", "s.js", "UTF-8", true, "function present() {}");
			WorkspaceService svc = serviceReturning(file);
			assertThrows(ResourceNotFoundException.class, () -> svc.readFunction("p", "s.js", "absent"));
		}

		@Test
		void blankFunctionNameRejected() {
			IFile file = mockFile("/p/s.js", "s.js", "UTF-8", true, "function present() {}");
			WorkspaceService svc = serviceReturning(file);
			assertThrows(IllegalArgumentException.class, () -> svc.readFunction("p", "s.js", "  "));
		}
	}

	// =============================================================================================
	// Optional: the java.nio fallback in readProjectResource, driven with a real temp dir.
	// =============================================================================================

	@Nested
	class WorkspaceRootFallback {
		@Test
		void readsWorkspaceRootLevelFileWhenProjectMissing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
				throws Exception {
			// Simulate a workspace-root-level directory (project.exists()=false triggers the FS fallback).
			java.nio.file.Path dir = tmp.resolve("jenkins-custom");
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Path f = dir.resolve("note.txt");
			java.nio.file.Files.writeString(f, "hello\nworld\n", StandardCharsets.UTF_8);

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(false);
			when(root.getProject(anyString())).thenReturn(project);
			when(root.getLocation()).thenReturn(IPath.fromOSString(tmp.toString()));
			WorkspaceService svc = new WorkspaceService() {
				@Override
				protected IWorkspaceRoot getWorkspaceRoot() {
					return root;
				}
			};
			String out = svc.readProjectResource("jenkins-custom", "note.txt", true, 0, 0);
			assertNotNull(out);
			assertTrue(out.contains("in jenkins-custom"), out);
			assertTrue(out.contains("1\thello"), out);
			assertTrue(out.contains("2\tworld"), out);
		}
	}
}

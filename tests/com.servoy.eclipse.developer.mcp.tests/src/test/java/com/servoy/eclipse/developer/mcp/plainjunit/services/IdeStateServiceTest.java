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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Optional;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.TextConsole;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.IdeStateService;

/**
 * Plain-JUnit unit tests for {@link IdeStateService}'s <em>own</em> logic -
 * console selection and line-tailing, editor file/selection extraction, and
 * marker severity/maxResults/filePattern filtering plus the markdown formatting
 * and all guard messages - with the Eclipse boundary stubbed via Mockito.
 * <p>
 * The service reaches Eclipse only through a handful of overridable seams
 * ({@code getConsoleManager}, {@code getActiveEditor}, {@code getWorkspaceRoot},
 * {@code getProject}, {@code findMarkers}, {@code joinAutoBuild}). This test
 * subclasses the service, overrides those seams to return Mockito mocks, and
 * drives the service's branches on canned inputs. In the spirit of the project's
 * test strategy, this does NOT assert Eclipse behaviour (whether a console,
 * editor or marker really exists) - it asserts Copilot's own selection, filter
 * and formatting logic on mocked inputs. Anything that genuinely needs the live
 * workbench (a real console, a real editor, a real build/markers) stays in the
 * integration suite.
 * </p>
 * <p>
 * Mockito note: the production body casts some mocks to a second interface
 * ({@code console instanceof TextConsole}, {@code editor instanceof ITextEditor},
 * {@code editorInput instanceof IFileEditorInput}). The Eclipse JUnit runner
 * masks the resulting {@code ClassCastException}s but Maven does not, so such
 * mocks are created directly as the cast-to type or with
 * {@code withSettings().extraInterfaces(...)}.
 * </p>
 *
 * @since 2026.11
 */
class IdeStateServiceTest {

	// --- Console ---

	@Nested
	@DisplayName("getConsoleOutput")
	class GetConsoleOutput {

		/** A service whose console-manager seam returns the given manager. */
		private IdeStateService serviceWith(IConsoleManager manager) {
			return new IdeStateService() {
				@Override
				protected IConsoleManager getConsoleManager() {
					return manager;
				}
			};
		}

		/** A {@link TextConsole} mock named {@code name} whose document returns {@code text}. */
		private TextConsole textConsole(String name, String text) {
			TextConsole console = mock(TextConsole.class);
			when(console.getName()).thenReturn(name);
			IDocument document = mock(IDocument.class);
			when(document.get()).thenReturn(text);
			when(console.getDocument()).thenReturn(document);
			return console;
		}

		private IConsoleManager managerWith(IConsole... consoles) {
			IConsoleManager manager = mock(IConsoleManager.class);
			when(manager.getConsoles()).thenReturn(consoles);
			return manager;
		}

		@Test
		@DisplayName("no consoles -> 'No consoles available' message")
		void noConsoles() {
			String out = serviceWith(managerWith()).getConsoleOutput(null, 100, false, false);
			assertTrue(out.contains("No consoles available."), out);
		}

		@Test
		@DisplayName("default selects only the most recent (last) console")
		void mostRecentOnly() {
			TextConsole first = textConsole("First", "a\nb");
			TextConsole last = textConsole("Last", "x\ny");
			String out = serviceWith(managerWith(first, last)).getConsoleOutput(null, 100, false, false);
			assertTrue(out.contains("## Console: Last"), out);
			assertFalse(out.contains("## Console: First"), out);
		}

		@Test
		@DisplayName("includeAllConsoles selects every console")
		void includeAll() {
			TextConsole first = textConsole("First", "a");
			TextConsole last = textConsole("Last", "x");
			String out = serviceWith(managerWith(first, last)).getConsoleOutput(null, 100, true, false);
			assertTrue(out.contains("## Console: First"), out);
			assertTrue(out.contains("## Console: Last"), out);
		}

		@Test
		@DisplayName("consoleName is a contains-filter across consoles")
		void consoleNameContainsFilter() {
			TextConsole a = textConsole("Servoy Log", "s");
			TextConsole b = textConsole("Build Console", "b");
			String out = serviceWith(managerWith(a, b)).getConsoleOutput("Build", 100, false, false);
			assertTrue(out.contains("## Console: Build Console"), out);
			assertFalse(out.contains("## Console: Servoy Log"), out);
		}

		@Test
		@DisplayName("consoleName matching nothing -> not-found message")
		void consoleNameNotFound() {
			TextConsole a = textConsole("Servoy Log", "s");
			String out = serviceWith(managerWith(a)).getConsoleOutput("Nope", 100, false, false);
			assertEquals("No console found with name containing: Nope", out);
		}

		@Test
		@DisplayName("line-tailing keeps the last maxLines lines")
		void lineTailing() {
			TextConsole c = textConsole("C", "l1\nl2\nl3\nl4\nl5");
			String out = serviceWith(managerWith(c)).getConsoleOutput(null, 2, false, false);
			assertTrue(out.contains("l4"), out);
			assertTrue(out.contains("l5"), out);
			assertFalse(out.contains("l1"), out);
			assertFalse(out.contains("l2"), out);
			assertFalse(out.contains("l3"), out);
		}

		@Test
		@DisplayName("maxLines larger than available keeps all lines (start clamps to 0)")
		void maxLinesClamp() {
			TextConsole c = textConsole("C", "a\nb");
			String out = serviceWith(managerWith(c)).getConsoleOutput(null, 999, false, false);
			assertTrue(out.contains("a"), out);
			assertTrue(out.contains("b"), out);
		}

		@Test
		@DisplayName("maxLines < 1 falls back to the default and does not drop content")
		void maxLinesDefault() {
			TextConsole c = textConsole("C", "only");
			String out = serviceWith(managerWith(c)).getConsoleOutput(null, 0, false, false);
			assertTrue(out.contains("only"), out);
		}

		@Test
		@DisplayName("clear=true clears the text console")
		void clearConsole() {
			TextConsole c = textConsole("C", "x");
			serviceWith(managerWith(c)).getConsoleOutput(null, 100, false, true);
			verify(c, times(1)).clearConsole();
		}

		@Test
		@DisplayName("clear=false does not clear the console")
		void noClear() {
			TextConsole c = textConsole("C", "x");
			serviceWith(managerWith(c)).getConsoleOutput(null, 100, false, false);
			verify(c, never()).clearConsole();
		}

		@Test
		@DisplayName("non-text console reports the unsupported-type note")
		void nonTextConsole() {
			IConsole plain = mock(IConsole.class);
			when(plain.getName()).thenReturn("Plain");
			String out = serviceWith(managerWith(plain)).getConsoleOutput(null, 100, false, false);
			assertTrue(out.contains("## Console: Plain"), out);
			assertTrue(out.contains("Console type not supported"), out);
		}
	}

	// --- Editor: currently opened file ---

	@Nested
	@DisplayName("getCurrentlyOpenedFile")
	class GetCurrentlyOpenedFile {

		private IdeStateService serviceWith(Optional<IEditorPart> editor) {
			return new IdeStateService() {
				@Override
				protected Optional<IEditorPart> getActiveEditor() {
					return editor;
				}
			};
		}

		@Test
		@DisplayName("no active editor -> 'No active editor found' message")
		void noEditor() {
			String out = serviceWith(Optional.empty()).getCurrentlyOpenedFile();
			assertEquals("No active editor found.", out);
		}

		@Test
		@DisplayName("non-file editor input -> 'not a file' message")
		void notAFileInput() {
			// Input is an IEditorInput that is NOT an IFileEditorInput.
			IEditorInput input = mock(IEditorInput.class);
			IEditorPart editor = mock(IEditorPart.class);
			when(editor.getEditorInput()).thenReturn(input);
			String out = serviceWith(Optional.of(editor)).getCurrentlyOpenedFile();
			assertEquals("Active editor input is not a file.", out);
		}

		@Test
		@DisplayName("file editor input -> project/path/full-path markdown")
		void fileInput() {
			org.eclipse.core.resources.IFile file = mock(org.eclipse.core.resources.IFile.class);
			IProject project = mock(IProject.class);
			when(project.getName()).thenReturn("MyProject");
			when(file.getProject()).thenReturn(project);
			IPath relPath = mock(IPath.class);
			when(relPath.toString()).thenReturn("forms/foo.js");
			when(file.getProjectRelativePath()).thenReturn(relPath);
			IPath fullPath = mock(IPath.class);
			when(fullPath.toString()).thenReturn("/MyProject/forms/foo.js");
			when(file.getFullPath()).thenReturn(fullPath);

			// The editor input is both IEditorInput and IFileEditorInput (production casts it).
			IFileEditorInput input = mock(IFileEditorInput.class);
			when(input.getFile()).thenReturn(file);
			IEditorPart editor = mock(IEditorPart.class);
			when(editor.getEditorInput()).thenReturn(input);

			String out = serviceWith(Optional.of(editor)).getCurrentlyOpenedFile();
			assertTrue(out.contains("# Currently Opened File:"), out);
			assertTrue(out.contains("**Project:** MyProject"), out);
			assertTrue(out.contains("**Path:** forms/foo.js"), out);
			assertTrue(out.contains("**Full path:** /MyProject/forms/foo.js"), out);
		}
	}

	// --- Editor: selection ---

	@Nested
	@DisplayName("getEditorSelection")
	class GetEditorSelection {

		private IdeStateService serviceWith(Optional<IEditorPart> editor) {
			return new IdeStateService() {
				@Override
				protected Optional<IEditorPart> getActiveEditor() {
					return editor;
				}
			};
		}

		/** A mock that is both IEditorPart and ITextEditor, with the given selection. */
		private IEditorPart textEditorWith(ISelection selection) {
			IEditorPart editor = mock(IEditorPart.class, withSettings().extraInterfaces(ITextEditor.class));
			ISelectionProvider provider = mock(ISelectionProvider.class);
			when(provider.getSelection()).thenReturn(selection);
			when(((ITextEditor) editor).getSelectionProvider()).thenReturn(provider);
			return editor;
		}

		@Test
		@DisplayName("no active editor -> 'No active editor found' message")
		void noEditor() {
			String out = serviceWith(Optional.empty()).getEditorSelection();
			assertEquals("No active editor found.", out);
		}

		@Test
		@DisplayName("non-text editor -> 'not a text editor' message")
		void notATextEditor() {
			IEditorPart editor = mock(IEditorPart.class);
			String out = serviceWith(Optional.of(editor)).getEditorSelection();
			assertEquals("Active editor is not a text editor.", out);
		}

		@Test
		@DisplayName("empty selection -> 'No text is currently selected' message")
		void emptySelection() {
			ISelection selection = mock(ISelection.class);
			when(selection.isEmpty()).thenReturn(true);
			String out = serviceWith(Optional.of(textEditorWith(selection))).getEditorSelection();
			assertEquals("No text is currently selected in the editor.", out);
		}

		@Test
		@DisplayName("non-text selection -> 'not a text selection' message")
		void nonTextSelection() {
			ISelection selection = mock(ISelection.class);
			when(selection.isEmpty()).thenReturn(false);
			String out = serviceWith(Optional.of(textEditorWith(selection))).getEditorSelection();
			assertEquals("The current selection is not a text selection.", out);
		}

		@Test
		@DisplayName("text selection -> 1-based line range, length and selected text")
		void textSelection() {
			// ITextSelection must also answer isEmpty() -> false (production checks it first).
			ITextSelection selection = mock(ITextSelection.class);
			when(selection.isEmpty()).thenReturn(false);
			when(selection.getStartLine()).thenReturn(4); // 0-based -> reported as 5
			when(selection.getEndLine()).thenReturn(6); // 0-based -> reported as 7
			when(selection.getLength()).thenReturn(12);
			when(selection.getText()).thenReturn("some code");

			String out = serviceWith(Optional.of(textEditorWith(selection))).getEditorSelection();
			assertTrue(out.contains("Selection from line: 5"), out);
			assertTrue(out.contains("to: 7"), out);
			assertTrue(out.contains("length: 12"), out);
			assertTrue(out.contains("=== BEGIN selected ==="), out);
			assertTrue(out.contains("some code"), out);
			assertTrue(out.contains("=== END selected text ==="), out);
		}

		@Test
		@DisplayName("selection text without trailing newline gets one appended")
		void appendsNewline() {
			ITextSelection selection = mock(ITextSelection.class);
			when(selection.isEmpty()).thenReturn(false);
			when(selection.getStartLine()).thenReturn(0);
			when(selection.getEndLine()).thenReturn(0);
			when(selection.getLength()).thenReturn(3);
			when(selection.getText()).thenReturn("abc");

			String out = serviceWith(Optional.of(textEditorWith(selection))).getEditorSelection();
			assertTrue(out.contains("abc\n=== END selected text ==="), out);
		}
	}

	// --- Compilation errors (markers) ---

	@Nested
	@DisplayName("getCompilationErrors")
	class GetCompilationErrors {

		/**
		 * A service that never joins a real build, resolves projects/markers from
		 * the supplied canned data, and treats a null canned project as missing.
		 */
		private IdeStateService serviceWith(IProject cannedProject, IMarker[] markers) {
			return new IdeStateService() {
				@Override
				protected void joinAutoBuild() {
					// no-op: never block on a real build in a unit test
				}

				@Override
				protected IResource getWorkspaceRoot() {
					return mock(IResource.class);
				}

				@Override
				protected IProject getProject(String projectName) {
					if (cannedProject != null) return cannedProject;
					IProject missing = mock(IProject.class);
					when(missing.exists()).thenReturn(false);
					return missing;
				}

				@Override
				protected IMarker[] findMarkers(IResource scope) {
					return markers;
				}
			};
		}

		private IProject existingProject() {
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(true);
			return project;
		}

		/** A problem marker with severity, line, message, type and id, on a resource with the given full path. */
		private IMarker marker(int severity, int line, String message, String type, long id, String fullPath) throws Exception {
			IMarker marker = mock(IMarker.class);
			when(marker.getAttribute(IMarker.SEVERITY, -1)).thenReturn(severity);
			when(marker.getAttribute(IMarker.LINE_NUMBER, -1)).thenReturn(line);
			when(marker.getAttribute(IMarker.MESSAGE, "")).thenReturn(message);
			when(marker.getType()).thenReturn(type);
			when(marker.getId()).thenReturn(id);
			IResource resource = mock(IResource.class);
			IPath path = mock(IPath.class);
			when(path.toString()).thenReturn(fullPath);
			when(resource.getFullPath()).thenReturn(path);
			when(marker.getResource()).thenReturn(resource);
			return marker;
		}

		@Test
		@DisplayName("missing project -> 'not found' error")
		void projectNotFound() {
			String out = serviceWith(null, new IMarker[0]).getCompilationErrors("Ghost", "ALL", 50, null, false);
			assertEquals("Error: Project 'Ghost' not found.", out);
		}

		@Test
		@DisplayName("no markers -> 'No problems found' message")
		void noMarkers() {
			String out = serviceWith(existingProject(), new IMarker[0])
				.getCompilationErrors("P", "ALL", 50, null, false);
			assertTrue(out.contains("No problems found with the specified criteria."), out);
		}

		@Test
		@DisplayName("ALL severity reports every marker with its label and location")
		void allSeverities() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 10, "boom", "org.eclipse.jdt.core.problem", 1, "/P/A.java"),
				marker(IMarker.SEVERITY_WARNING, 20, "meh", "org.eclipse.jdt.core.problem", 2, "/P/B.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ALL", 50, null, false);
			assertTrue(out.contains("**ERROR**"), out);
			assertTrue(out.contains("/P/A.java:10"), out);
			assertTrue(out.contains("boom"), out);
			assertTrue(out.contains("**WARNING**"), out);
			assertTrue(out.contains("/P/B.java:20"), out);
			assertTrue(out.contains("Found 2 problem(s)."), out);
		}

		@Test
		@DisplayName("severity=ERROR filters out non-errors")
		void severityErrorFilter() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 10, "boom", "t", 1, "/P/A.java"),
				marker(IMarker.SEVERITY_WARNING, 20, "meh", "t", 2, "/P/B.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ERROR", 50, null, false);
			assertTrue(out.contains("boom"), out);
			assertFalse(out.contains("meh"), out);
			assertTrue(out.contains("Found 1 problem(s)."), out);
		}

		@Test
		@DisplayName("severity=WARNING keeps only warnings")
		void severityWarningFilter() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 10, "boom", "t", 1, "/P/A.java"),
				marker(IMarker.SEVERITY_WARNING, 20, "meh", "t", 2, "/P/B.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "WARNING", 50, null, false);
			assertFalse(out.contains("boom"), out);
			assertTrue(out.contains("meh"), out);
			assertTrue(out.contains("Found 1 problem(s)."), out);
		}

		@Test
		@DisplayName("maxResults caps the number of reported problems")
		void maxResultsCap() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 1, "e1", "t", 1, "/P/A.java"),
				marker(IMarker.SEVERITY_ERROR, 2, "e2", "t", 2, "/P/B.java"),
				marker(IMarker.SEVERITY_ERROR, 3, "e3", "t", 3, "/P/C.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ALL", 2, null, false);
			assertTrue(out.contains("e1"), out);
			assertTrue(out.contains("e2"), out);
			assertFalse(out.contains("e3"), out);
			assertTrue(out.contains("Found 2 problem(s)."), out);
		}

		@Test
		@DisplayName("filePattern glob keeps only matching resource paths")
		void filePatternFilter() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 1, "inJava", "t", 1, "/P/src/A.java"),
				marker(IMarker.SEVERITY_ERROR, 2, "inJs", "t", 2, "/P/forms/B.js"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ALL", 50, "*.java", false);
			assertTrue(out.contains("inJava"), out);
			assertFalse(out.contains("inJs"), out);
			assertTrue(out.contains("Found 1 problem(s)."), out);
		}

		@Test
		@DisplayName("INFO severity label and omitted line number for non-positive lines")
		void infoNoLine() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_INFO, -1, "note", "t", 7, "/P/A.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ALL", 50, null, false);
			assertTrue(out.contains("**INFO**"), out);
			assertTrue(out.contains("/P/A.java"), out);
			assertFalse(out.contains("/P/A.java:"), out);
			assertTrue(out.contains("Marker ID: 7"), out);
		}

		@Test
		@DisplayName("maxResults <= 0 falls back to the default and still reports markers")
		void maxResultsDefault() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 1, "e1", "t", 1, "/P/A.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors("P", "ALL", 0, null, false);
			assertTrue(out.contains("e1"), out);
			assertTrue(out.contains("Found 1 problem(s)."), out);
		}

		@Test
		@DisplayName("no projectName uses the workspace root scope")
		void workspaceScope() throws Exception {
			IMarker[] markers = {
				marker(IMarker.SEVERITY_ERROR, 1, "e1", "t", 1, "/P/A.java"),
			};
			String out = serviceWith(existingProject(), markers)
				.getCompilationErrors(null, "ALL", 50, null, false);
			assertFalse(out.contains("Project:"), out);
			assertTrue(out.contains("e1"), out);
		}
	}
}

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Collections;
import java.util.List;

import org.eclipse.dltk.compiler.problem.DefaultProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServer;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService;
import com.servoy.eclipse.developer.mcp.services.JsCodeValidatorService;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyCoderServer}'s <em>own</em>
 * logic - without a live Eclipse workspace.
 * <p>
 * {@code ServoyCoderServer} is a thin tool wrapper: it does not grab the static
 * {@code ServoyModelManager} model itself, it <em>delegates</em> to collaborators
 * ({@link CodeEditingService}, {@link ServoySolutionService},
 * {@link JsCodeValidatorService}). So there is no {@code getServoyModel()} seam to
 * add here; instead these tests drive the server's own branches - parameter
 * parsing (line-number {@code Integer.parseInt}), the {@code deleteFile} dispatch /
 * routing (plain file vs. {@code .frm}/{@code .rel}/{@code .val} Servoy artifact vs.
 * the form-{@code .js}-companion rejection and blank guard), the scope-path guard
 * (SVY-21203), and the {@code appendJsValidation} formatting (SVY-21113) - with the
 * three collaborators stubbed via Mockito.
 * </p>
 * <p>
 * The {@link CodeEditingService} collaborator is supplied through the server's
 * package-visible testing constructor; the {@code private final}
 * {@link ServoySolutionService} / {@link JsCodeValidatorService} fields are swapped
 * for mocks via reflection (so no production visibility is widened and no seam is
 * introduced just for the test). The collaborators are stubbed only at the boundary
 * to drive the server's branches; their own behaviour (the real file edit, the real
 * Servoy-repository delete, the real DLTK validation) needs the workbench / model /
 * DLTK and stays in the {@code integrationpde} suite.
 * </p>
 *
 * @since 2026.11
 */
class ServoyCoderServerTest
{
	/**
	 * Builds a {@link ServoyCoderServer} whose {@link CodeEditingService} is the
	 * supplied mock and whose {@code solutionService} / {@code jsValidator}
	 * {@code private final} fields are replaced (via reflection) with the supplied
	 * mocks.
	 */
	private static ServoyCoderServer serverWith(CodeEditingService editing, ServoySolutionService solution, JsCodeValidatorService validator)
	{
		ServoyCoderServer server = new ServoyCoderServer(editing);
		setField(server, "solutionService", solution);
		setField(server, "jsValidator", validator);
		return server;
	}

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyCoderServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	/** A validator mock that reports no syntax problems for any content. */
	private static JsCodeValidatorService cleanValidator()
	{
		JsCodeValidatorService v = mock(JsCodeValidatorService.class);
		lenient().when(v.validate(anyString())).thenReturn(Collections.<DefaultProblem> emptyList());
		return v;
	}

	// =======================================================================
	// Annotation / registration (no collaborators needed)
	// =======================================================================

	@Nested
	class AnnotationAndRegistration
	{
		@Test
		@DisplayName("ServoyCoderServer carries the @McpServer(name = \"servoy-coder\") annotation")
		void hasCorrectAnnotation()
		{
			com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyCoderServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
			assertNotNull(ann, "ServoyCoderServer must have @McpServer annotation");
			assertFalse(ann.name().isBlank(), "@McpServer name must not be empty");
			assertEquals("servoy-coder", ann.name(), "@McpServer name must be 'servoy-coder'");
		}

		@Test
		@DisplayName("ServoyCoderServer is registered in McpServerBuiltins")
		void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (cls == ServoyCoderServer.class)
				{
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyCoderServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("renameFile is NOT a @Tool on ServoyCoderServer (SVY-21179 moved it to ServoyDevServer)")
		void renameFileNotRegistered()
		{
			for (Method candidate : ServoyCoderServer.class.getDeclaredMethods())
			{
				Tool ann = candidate.getAnnotation(Tool.class);
				assertFalse(ann != null && "renameFile".equals(ann.name()),
					"renameFile must NOT be registered as a @Tool on ServoyCoderServer");
			}
		}
	}

	// =======================================================================
	// SVY-21203: scope-path guard (static, pure)
	// =======================================================================

	@Nested
	class ScopePathGuard
	{
		@Test
		@DisplayName("a lowercase scopes/ directory is blocked and the message names the root-level fix")
		void lowercaseScopesBlocked()
		{
			String result = ServoyCoderServer.guardScopePath("scopes/globals.js");
			assertNotNull(result, "Should block scopes/ subdirectory");
			assertTrue(result.contains("solution"), "Error message should mention solution root");
			assertTrue(result.contains("globals.js"), "Error message should mention the correct file name");
		}

		@Test
		@DisplayName("an uppercase Scopes/ directory is blocked (case-insensitive)")
		void uppercaseScopesBlocked()
		{
			assertNotNull(ServoyCoderServer.guardScopePath("Scopes/utils.js"), "Should block Scopes/ subdirectory");
		}

		@Test
		@DisplayName("a nested .../scopes/ directory is blocked")
		void nestedScopesBlocked()
		{
			assertNotNull(ServoyCoderServer.guardScopePath("module/scopes/utils.js"), "Should block nested scopes/ directory");
		}

		@Test
		@DisplayName("a root-level .js file is allowed")
		void rootLevelAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath("globals.js"), "Root-level .js should be allowed");
		}

		@Test
		@DisplayName("a forms/ .js file is allowed (not a scopes directory)")
		void formsDirAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath("forms/myForm.js"), "forms/ is not a scopes directory");
		}

		@Test
		@DisplayName("a non-.js file under scopes/ is allowed")
		void nonJsFileAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath("scopes/config.json"), "Non-.js files should not be blocked");
		}

		@Test
		@DisplayName("a null path is allowed")
		void nullAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath(null), "null path should not throw");
		}

		@Test
		@DisplayName("Windows backslashes are normalised and the Scopes\\ directory is blocked")
		void windowsBackslashBlocked()
		{
			assertNotNull(ServoyCoderServer.guardScopePath("Scopes\\globals.js"), "Should normalise backslashes and block Scopes\\ directory");
		}

		@Test
		@DisplayName("a file whose name merely contains 'scopes' is allowed")
		void scopeNameContainsScopesAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath("myscopes.js"), "'myscopes.js' at root should not be blocked");
		}

		@Test
		@DisplayName("leading slashes in the path are stripped before the root check")
		void leadingSlashRootAllowed()
		{
			assertNull(ServoyCoderServer.guardScopePath("/globals.js"), "a leading slash should not make a root file look nested");
		}
	}

	// =======================================================================
	// createFile - the scope guard fires before the editing service is touched
	// =======================================================================

	@Nested
	class CreateFile
	{
		@Test
		@DisplayName("createFile returns the scope-guard error and never calls the editing service")
		void scopeGuardShortCircuits()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			String result = server.createFile("proj", "scopes/globals.js", "x = 1;");
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "got: " + result);
			verify(editing, never()).createFile(anyString(), anyString(), anyString());
		}

		@Test
		@DisplayName("createFile delegates to the editing service for an acceptable path")
		void delegatesForAcceptablePath()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.createFile("proj", "globals.js", "x = 1;")).thenReturn("Created file: globals.js");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			String result = server.createFile("proj", "globals.js", "x = 1;");
			assertEquals("Created file: globals.js", result);
			verify(editing).createFile("proj", "globals.js", "x = 1;");
		}
	}

	// =======================================================================
	// deleteFile - dispatch / routing (all reachable without the workbench)
	// =======================================================================

	@Nested
	class DeleteFileDispatch
	{
		@Test
		@DisplayName("deleteFile requires a non-blank filePath")
		void blankFilePathRejected()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			assertEquals("Error: filePath parameter is required", server.deleteFile("proj", null));
			assertEquals("Error: filePath parameter is required", server.deleteFile("proj", "   "));
		}

		@Test
		@DisplayName("deleteFile rejects deleting a form's .js companion and names the .frm to delete instead")
		void formJsCompanionRejected()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			String result = server.deleteFile("proj", "forms/orders.js");
			assertTrue(result.startsWith("Error:"), "got: " + result);
			assertTrue(result.contains("orders"), "should name the form; got: " + result);
			assertTrue(result.contains("forms/orders.frm"), "should point at the .frm; got: " + result);
		}

		@Test
		@DisplayName("deleteFile normalises backslashes before the form-.js-companion check")
		void formJsCompanionBackslashNormalised()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			String result = server.deleteFile("proj", "forms\\orders.js");
			assertTrue(result.startsWith("Error:") && result.contains("forms/orders.frm"), "got: " + result);
		}

		@Test
		@DisplayName("deleteFile routes a .frm path to ServoySolutionService.deleteForms with the bare form name")
		void frmRoutedToDeleteForms()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.deleteForms(List.of("orders"))).thenReturn("Deleted form orders");
			ServoyCoderServer server = serverWith(editing, solution, cleanValidator());

			assertEquals("Deleted form orders", server.deleteFile("proj", "forms/orders.frm"));
			verify(solution).deleteForms(List.of("orders"));
			verify(editing, never()).deleteFile(anyString(), anyString());
		}

		@Test
		@DisplayName("deleteFile routes a .rel path to ServoySolutionService.deleteRelations")
		void relRoutedToDeleteRelations()
		{
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.deleteRelations(List.of("orders_to_items"))).thenReturn("Deleted relation");
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), solution, cleanValidator());

			assertEquals("Deleted relation", server.deleteFile("proj", "relations/orders_to_items.rel"));
			verify(solution).deleteRelations(List.of("orders_to_items"));
		}

		@Test
		@DisplayName("deleteFile routes a .val path to ServoySolutionService.deleteValueLists")
		void valRoutedToDeleteValueLists()
		{
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.deleteValueLists(List.of("countries"))).thenReturn("Deleted valuelist");
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), solution, cleanValidator());

			assertEquals("Deleted valuelist", server.deleteFile("proj", "valuelists/countries.val"));
			verify(solution).deleteValueLists(List.of("countries"));
		}

		@Test
		@DisplayName("deleteFile artifact routing is case-insensitive on the extension")
		void frmExtensionCaseInsensitive()
		{
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.deleteForms(List.of("orders"))).thenReturn("Deleted form orders");
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), solution, cleanValidator());

			assertEquals("Deleted form orders", server.deleteFile("proj", "forms/orders.FRM"));
			verify(solution).deleteForms(List.of("orders"));
		}

		@Test
		@DisplayName("deleteFile routes a plain file to the editing service and leaves the solution service untouched")
		void plainFileRoutedToEditingService()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(editing.deleteFile("proj", "media/logo.png")).thenReturn("Deleted media/logo.png");
			ServoyCoderServer server = serverWith(editing, solution, cleanValidator());

			assertEquals("Deleted media/logo.png", server.deleteFile("proj", "media/logo.png"));
			verify(editing).deleteFile("proj", "media/logo.png");
			verify(solution, never()).deleteForms(any());
		}
	}

	// =======================================================================
	// stripExtension - private static pure helper (reflection)
	// =======================================================================

	@Nested
	class StripExtension
	{
		private String stripExtension(String path, String ext) throws Exception
		{
			Method m = ServoyCoderServer.class.getDeclaredMethod("stripExtension", String.class, String.class);
			m.setAccessible(true);
			return (String)m.invoke(null, path, ext);
		}

		@Test
		@DisplayName("stripExtension returns the bare name from a nested path")
		void bareNameFromNestedPath() throws Exception
		{
			assertEquals("orders", stripExtension("forms/orders.frm", ".frm"));
		}

		@Test
		@DisplayName("stripExtension returns the bare name from a root-level path")
		void bareNameFromRootPath() throws Exception
		{
			assertEquals("globals", stripExtension("globals.val", ".val"));
		}

		@Test
		@DisplayName("stripExtension keeps only the last segment of a deeply nested path")
		void onlyLastSegment() throws Exception
		{
			assertEquals("rel1", stripExtension("a/b/c/rel1.rel", ".rel"));
		}
	}

	// =======================================================================
	// Line-number parsing in the editing tools
	// =======================================================================

	@Nested
	class LineNumberParsing
	{
		@Test
		@DisplayName("insertIntoFile defaults to line 1 when the line argument is null")
		void insertDefaultsToLineOne()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.insertIntoFile(eq("proj"), eq("f.txt"), eq("x"), eq(1))).thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("x");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("ok", server.insertIntoFile("proj", "f.txt", "x", null));
			verify(editing).insertIntoFile("proj", "f.txt", "x", 1);
		}

		@Test
		@DisplayName("insertIntoFile parses the supplied 1-based line number")
		void insertParsesLine()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.insertIntoFile(eq("proj"), eq("f.txt"), eq("x"), eq(5))).thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("x");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("ok", server.insertIntoFile("proj", "f.txt", "x", "5"));
			verify(editing).insertIntoFile("proj", "f.txt", "x", 5);
		}

		@Test
		@DisplayName("insertIntoFile propagates a NumberFormatException for a non-numeric line")
		void insertRejectsNonNumericLine()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			assertThrows(NumberFormatException.class, () -> server.insertIntoFile("proj", "f.txt", "x", "notaline"));
		}

		@Test
		@DisplayName("replaceString passes null start/end line numbers through when omitted")
		void replaceStringNullRange()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceStringInFile(eq("proj"), eq("f.txt"), eq("a"), eq("b"), isNull(), isNull(), eq(CodeEditingService.Occurrence.UNIQUE)))
				.thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("b");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("ok", server.replaceString("proj", "f.txt", "a", "b", null, null, null));
			verify(editing).replaceStringInFile("proj", "f.txt", "a", "b", null, null, CodeEditingService.Occurrence.UNIQUE);
		}

		@Test
		@DisplayName("replaceString defaults occurrence to UNIQUE and parses a supplied occurrence case-insensitively")
		void replaceStringParsesOccurrence()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceStringInFile(anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), any())).thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("b");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			server.replaceString("proj", "f.txt", "a", "b", null, null, "all");
			verify(editing).replaceStringInFile("proj", "f.txt", "a", "b", null, null, CodeEditingService.Occurrence.ALL);
		}

		@Test
		@DisplayName("replaceString refuses an unknown occurrence value instead of guessing")
		void replaceStringRejectsUnknownOccurrence()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> server.replaceString("proj", "f.txt", "a", "b", null, null, "EVERYTHING"));
			assertTrue(problem.getMessage().contains("Invalid occurrence"), problem.getMessage());
		}

		@Test
		@DisplayName("replaceString parses supplied start and end line numbers")
		void replaceStringParsesRange()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceStringInFile(eq("proj"), eq("f.txt"), eq("a"), eq("b"), eq(2), eq(7), eq(CodeEditingService.Occurrence.UNIQUE)))
				.thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("b");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("ok", server.replaceString("proj", "f.txt", "a", "b", "2", "7", null));
			verify(editing).replaceStringInFile("proj", "f.txt", "a", "b", 2, 7, CodeEditingService.Occurrence.UNIQUE);
		}

		@Test
		@DisplayName("deleteLinesInFile parses the required start and end line numbers")
		void deleteLinesParsesRange()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.deleteLinesInFile(eq("proj"), eq("f.txt"), eq(3), eq(9))).thenReturn("ok");
			lenient().when(editing.readFileContent(anyString(), anyString())).thenReturn("");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("ok", server.deleteLinesInFile("proj", "f.txt", "3", "9"));
			verify(editing).deleteLinesInFile("proj", "f.txt", 3, 9);
		}

		@Test
		@DisplayName("deleteLinesInFile propagates a NumberFormatException for a non-numeric bound")
		void deleteLinesRejectsNonNumeric()
		{
			ServoyCoderServer server = serverWith(mock(CodeEditingService.class), mock(ServoySolutionService.class), cleanValidator());
			assertThrows(NumberFormatException.class, () -> server.deleteLinesInFile("proj", "f.txt", "x", "9"));
		}
	}

	// =======================================================================
	// Plain pass-through tools
	// =======================================================================

	@Nested
	class PassThroughTools
	{
		@Test
		@DisplayName("undoEdit delegates straight to the editing service")
		void undoEditDelegates()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.undoEdit("proj", "f.txt")).thenReturn("restored");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("restored", server.undoEdit("proj", "f.txt"));
			verify(editing).undoEdit("proj", "f.txt");
		}

		@Test
		@DisplayName("createDirectories delegates straight to the editing service")
		void createDirectoriesDelegates()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.createDirectories("proj", "a/b")).thenReturn("made a/b");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("made a/b", server.createDirectories("proj", "a/b"));
			verify(editing).createDirectories("proj", "a/b");
		}

		@Test
		@DisplayName("moveResource delegates straight to the editing service")
		void moveResourceDelegates()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.moveResource("proj", "a.txt", "sub")).thenReturn("moved");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("moved", server.moveResource("proj", "a.txt", "sub"));
			verify(editing).moveResource("proj", "a.txt", "sub");
		}
	}

	// =======================================================================
	// SVY-21113: appendJsValidation formatting (private, driven through the
	// write tools, with a mocked DLTK validator)
	// =======================================================================

	@Nested
	class JsValidationFormatting
	{
		/**
		 * A real {@link DefaultProblem} - it carries final getters
		 * ({@code getSourceLineNumber}/{@code getMessage}) that Mockito cannot
		 * stub, so the server's formatting is driven with a genuine instance
		 * whose {@code line} and {@code message} are what
		 * {@code appendJsValidation} reads.
		 */
		private DefaultProblem problem(int line, String message)
		{
			return new DefaultProblem(message, (org.eclipse.dltk.compiler.problem.IProblemIdentifier)null, new String[0],
				(org.eclipse.dltk.compiler.problem.ProblemSeverity)null, 0, 0, line);
		}

		@Test
		@DisplayName("replaceFileContent appends nothing for a .js file with no syntax problems")
		void noProblemsNoAppendForJs()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceFileContent("proj", "globals.js", "var x = 1;")).thenReturn("Replaced globals.js");
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), cleanValidator());

			assertEquals("Replaced globals.js", server.replaceFileContent("proj", "globals.js", "var x = 1;"));
		}

		@Test
		@DisplayName("replaceFileContent appends the DLTK syntax problems for a .js file")
		void appendsProblemsForJs()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceFileContent("proj", "globals.js", "var x =;")).thenReturn("Replaced globals.js");
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			when(validator.validate("var x =;")).thenReturn(List.of(problem(1, "missing expression"), problem(3, "unexpected token")));
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), validator);

			String result = server.replaceFileContent("proj", "globals.js", "var x =;");
			assertTrue(result.startsWith("Replaced globals.js"), "original result kept; got: " + result);
			assertTrue(result.contains("JavaScript syntax issues detected:"), "got: " + result);
			assertTrue(result.contains("Line 1: missing expression"), "got: " + result);
			assertTrue(result.contains("Line 3: unexpected token"), "got: " + result);
		}

		@Test
		@DisplayName("a non-.js file is never validated, even when the validator would report problems")
		void nonJsFileNotValidated()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceFileContent("proj", "readme.txt", "whatever")).thenReturn("Replaced readme.txt");
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), validator);

			assertEquals("Replaced readme.txt", server.replaceFileContent("proj", "readme.txt", "whatever"));
			verify(validator, never()).validate(anyString());
		}

		@Test
		@DisplayName("an Error: result from the write is returned unchanged and never validated")
		void errorResultNotValidated()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.replaceFileContent("proj", "globals.js", "x")).thenReturn("Error: file not found");
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), validator);

			assertEquals("Error: file not found", server.replaceFileContent("proj", "globals.js", "x"));
			verify(validator, never()).validate(anyString());
		}

		@Test
		@DisplayName("applyPatch validates the re-read file content for a .js file and appends problems")
		void applyPatchValidatesReReadContent()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.applyPatch("proj", "globals.js", "@@ patch @@")).thenReturn("Patched globals.js");
			when(editing.readFileContent("proj", "globals.js")).thenReturn("var y =;");
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			when(validator.validate("var y =;")).thenReturn(List.of(problem(2, "bad")));
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), validator);

			String result = server.applyPatch("proj", "globals.js", "@@ patch @@");
			assertTrue(result.contains("Patched globals.js"), "got: " + result);
			assertTrue(result.contains("Line 2: bad"), "got: " + result);
			verify(validator).validate("var y =;");
		}

		@Test
		@DisplayName("createFile validates the written content directly (not a re-read) for a .js file")
		void createFileValidatesWrittenContent()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			when(editing.createFile("proj", "globals.js", "bad(")).thenReturn("Created globals.js");
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			when(validator.validate("bad(")).thenReturn(List.of(problem(1, "unterminated")));
			ServoyCoderServer server = serverWith(editing, mock(ServoySolutionService.class), validator);

			String result = server.createFile("proj", "globals.js", "bad(");
			assertTrue(result.contains("Line 1: unterminated"), "got: " + result);
			verify(validator).validate("bad(");
			verify(editing, never()).readFileContent(anyString(), anyString());
		}
	}

	// =======================================================================
	// Constructor seam
	// =======================================================================

	@Nested
	class TestingConstructor
	{
		@Test
		@DisplayName("the testing constructor wires the supplied editing service and leaves the default collaborators in place")
		void testingConstructorWiresEditingService()
		{
			CodeEditingService editing = mock(CodeEditingService.class);
			ServoyCoderServer server = new ServoyCoderServer(editing);
			assertNotNull(server, "server should construct with the injected editing service");
			// the default solution/validator collaborators are created eagerly and are non-null
			assertSame(editing, readField(server, "codeEditingService"));
			assertNotNull(readField(server, "solutionService"));
			assertNotNull(readField(server, "jsValidator"));
		}

		private Object readField(Object target, String name)
		{
			try
			{
				Field f = ServoyCoderServer.class.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			}
			catch (ReflectiveOperationException e)
			{
				throw new RuntimeException(e);
			}
		}
	}
}

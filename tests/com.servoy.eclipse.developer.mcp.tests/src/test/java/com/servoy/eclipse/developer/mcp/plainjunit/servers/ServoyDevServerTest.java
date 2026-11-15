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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.dltk.compiler.problem.DefaultProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.FilePathResolver;
import com.servoy.eclipse.developer.mcp.services.FormatValidatorService;
import com.servoy.eclipse.developer.mcp.services.JsCodeValidatorService;
import com.servoy.eclipse.developer.mcp.services.MenuService;
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateService;
import com.servoy.eclipse.developer.mcp.services.PersistRenameService;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyDevServer}'s <em>own</em>
 * logic - without a live Eclipse workspace, model, DLTK or app server.
 * <p>
 * {@code ServoyDevServer} is a large {@code @Tool} wrapper. Its tool methods
 * open with parameter-validation / guard branches (blank-argument checks,
 * SQL-identifier and reserved-prefix checks, numeric-argument parsing, the
 * {@code viewable}/{@code accessible} combination guard, etc.) that all return
 * <em>before</em> the static {@code ServoyModelManager.getServoyModelManager()
 * .getServoyModel()} grab or the {@code ApplicationServerRegistry} lookup is
 * ever reached. Those branches, together with the server's pure private helpers
 * ({@code parseSolutionType} / {@code getSolutionTypeName} switch mapping,
 * {@code escapeJson}, {@code toJsonArray} / {@code toJsonModifiedArray},
 * {@code addColumnsFromSpec} spec parsing), and the result-formatting of the
 * tools that purely delegate to a collaborator service, are what these tests
 * drive.
 * </p>
 * <p>
 * Like {@code ServoyCoderServerTest}, no production change / seam is introduced:
 * the server exposes only its default constructor, so each test instantiates it
 * and swaps the {@code private final} collaborator fields it needs
 * ({@link ScriptContextService}, {@link ServoyScriptResolver},
 * {@link FilePathResolver}, {@link JsCodeValidatorService},
 * {@link FormatValidatorService}, {@link MenuService},
 * {@link PersistRenameService}, {@link PersistDuplicateService}) for Mockito
 * mocks via reflection. The collaborators are stubbed only at the boundary to
 * drive the server's own branches / formatting; their real behaviour (the DLTK
 * resolution, the real validation, the real Servoy-repository
 * rename/duplicate/menu work) needs the workbench / model / DLTK and stays in
 * the {@code integrationpde} suite - as do every tool's happy paths, which reach
 * the static model / server-registry.
 * </p>
 *
 * @since 2026.11
 */
class ServoyDevServerTest
{
	// =======================================================================
	// reflection helpers (no production seam / visibility widening)
	// =======================================================================

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyDevServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	private static Object invokePrivate(Object target, String name, Class< ? >[] types, Object... args)
	{
		try
		{
			Method m = ServoyDevServer.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m.invoke(target, args);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to invoke '" + name + "'", e);
		}
	}

	/** A real {@link DefaultProblem} - carries final getters Mockito cannot stub. */
	private static DefaultProblem problem(int line, String message)
	{
		return new DefaultProblem(message, (org.eclipse.dltk.compiler.problem.IProblemIdentifier)null, new String[0],
			(org.eclipse.dltk.compiler.problem.ProblemSeverity)null, 0, 0, line);
	}

	// =======================================================================
	// Annotation / registration (no collaborators needed)
	// =======================================================================

	@Nested
	class AnnotationAndRegistration
	{
		@Test
		@DisplayName("ServoyDevServer carries the @McpServer(name = \"servoy-dev\") annotation")
		void hasCorrectAnnotation()
		{
			McpServer ann = ServoyDevServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyDevServer must have @McpServer annotation");
			assertEquals("servoy-dev", ann.name(), "@McpServer name must be 'servoy-dev'");
		}

		@Test
		@DisplayName("ServoyDevServer is registered in McpServerBuiltins")
		void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (cls == ServoyDevServer.class)
				{
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyDevServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("ping returns pong")
		void pingReturnsPong()
		{
			assertEquals("pong", new ServoyDevServer().ping());
		}
	}

	// =======================================================================
	// Pure private helpers: solution-type mapping (reflection)
	// =======================================================================

	@Nested
	class SolutionTypeMapping
	{
		private int parseSolutionType(String type)
		{
			return (int)invokePrivate(new ServoyDevServer(), "parseSolutionType", new Class[] { String.class }, type);
		}

		private String getSolutionTypeName(int type)
		{
			return (String)invokePrivate(new ServoyDevServer(), "getSolutionTypeName", new Class[] { int.class },
				Integer.valueOf(type));
		}

		@Test
		@DisplayName("parseSolutionType defaults a null / blank / unknown type to NG_CLIENT_ONLY")
		void defaultsToNgClient()
		{
			int ngClient = com.servoy.j2db.persistence.SolutionMetaData.NG_CLIENT_ONLY;
			assertEquals(ngClient, parseSolutionType(null));
			assertEquals(ngClient, parseSolutionType("  "));
			assertEquals(ngClient, parseSolutionType("nonsense"));
		}

		@Test
		@DisplayName("parseSolutionType maps each recognised alias case-insensitively")
		void mapsRecognisedAliases()
		{
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.NG_CLIENT_ONLY, parseSolutionType("NG_Client"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.NG_CLIENT_ONLY, parseSolutionType("ng_client_only"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.NG_MODULE, parseSolutionType("ng_module"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.SERVICE, parseSolutionType("service"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.PRE_IMPORT_HOOK, parseSolutionType("pre_import_hook"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.PRE_IMPORT_HOOK,
				parseSolutionType("pre-import hook module"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.POST_IMPORT_HOOK, parseSolutionType("post_import_hook"));
			assertEquals(com.servoy.j2db.persistence.SolutionMetaData.MODULE, parseSolutionType("module"));
		}

		@Test
		@DisplayName("getSolutionTypeName is the inverse name for each known type and falls back to ng_client")
		void namesKnownTypes()
		{
			assertEquals("module", getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.MODULE));
			assertEquals("ng_module", getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.NG_MODULE));
			assertEquals("ng_client", getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.NG_CLIENT_ONLY));
			assertEquals("service", getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.SERVICE));
			assertEquals("pre_import_hook", getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.PRE_IMPORT_HOOK));
			assertEquals("post_import_hook",
				getSolutionTypeName(com.servoy.j2db.persistence.SolutionMetaData.POST_IMPORT_HOOK));
			assertEquals("ng_client", getSolutionTypeName(-12345), "an unknown numeric type falls back to ng_client");
		}
	}

	// =======================================================================
	// Pure private helpers: JSON escaping / array building (reflection)
	// =======================================================================

	@Nested
	class JsonHelpers
	{
		private String escapeJson(String s)
		{
			return (String)invokePrivate(new ServoyDevServer(), "escapeJson", new Class[] { String.class }, s);
		}

		private String toJsonArray(List<String> list)
		{
			return (String)invokePrivate(new ServoyDevServer(), "toJsonArray", new Class[] { List.class }, list);
		}

		@Test
		@DisplayName("escapeJson returns an empty string for null")
		void escapeNull()
		{
			assertEquals("", escapeJson(null));
		}

		@Test
		@DisplayName("escapeJson escapes backslash, quote, newline, carriage return and tab")
		void escapeSpecials()
		{
			assertEquals("a\\\\b\\\"c\\n\\r\\t", escapeJson("a\\b\"c\n\r\t"));
		}

		@Test
		@DisplayName("escapeJson leaves plain text unchanged")
		void escapePlain()
		{
			assertEquals("plain text 123", escapeJson("plain text 123"));
		}

		@Test
		@DisplayName("toJsonArray renders an empty list as []")
		void emptyArray()
		{
			assertEquals("[]", toJsonArray(List.of()));
		}

		@Test
		@DisplayName("toJsonArray quotes, comma-separates and escapes each element")
		void populatedArray()
		{
			assertEquals("[\"a\",\"b\\\"c\"]", toJsonArray(List.of("a", "b\"c")));
		}
	}

	// =======================================================================
	// syncDbiWithDatabase - blank serverName guard (returns before any static)
	// =======================================================================

	@Nested
	class SyncDbiGuard
	{
		@Test
		@DisplayName("syncDbiWithDatabase reports a JSON error when serverName is blank")
		void blankServerNameRejected()
		{
			ServoyDevServer server = new ServoyDevServer();
			String expected = "{\"errors\":[\"serverName is required\"]}";
			assertEquals(expected, server.syncDbiWithDatabase(null, "t"));
			assertEquals(expected, server.syncDbiWithDatabase("   ", null));
		}
	}

	// =======================================================================
	// createSolution - blank solutionName guard
	// =======================================================================

	@Nested
	class CreateSolutionGuard
	{
		@Test
		@DisplayName("createSolution requires a non-blank solutionName")
		void blankSolutionNameRejected()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: solutionName is required.",
				server.createSolution(null, "ng_client", "true", "true", null));
			assertEquals("Error: solutionName is required.",
				server.createSolution("  ", null, null, null, null));
		}
	}

	// =======================================================================
	// Documentation tools - parameter guards (return before the TypeCreator)
	// =======================================================================

	@Nested
	class DocumentationGuards
	{
		@Test
		@DisplayName("getDocumentationForTypeMember requires typeName then memberName")
		void typeMemberGuards()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: typeName parameter is required",
				server.getDocumentationForTypeMember(null, "m"));
			assertEquals("Error: typeName parameter is required",
				server.getDocumentationForTypeMember("  ", "m"));
			assertEquals("Error: memberName parameter is required",
				server.getDocumentationForTypeMember("application", null));
			assertEquals("Error: memberName parameter is required",
				server.getDocumentationForTypeMember("application", "   "));
		}

		@Test
		@DisplayName("getAvailableMembersForType requires typeName")
		void availableMembersGuard()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: typeName parameter is required", server.getAvailableMembersForType(null, null));
			assertEquals("Error: typeName parameter is required", server.getAvailableMembersForType("  ", "get.*"));
		}

		@Test
		@DisplayName("getDocumentationForIdentifiers requires identifiers then filePath")
		void identifiersGuards()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: identifiers parameter is required",
				server.getDocumentationForIdentifiers(null, "f.js"));
			assertEquals("Error: filePath parameter is required",
				server.getDocumentationForIdentifiers("databaseManager", null));
		}

		@Test
		@DisplayName("getDocumentationForIdentifiers reports 'no valid identifiers' for an all-separator list before touching the resolver")
		void noValidIdentifiers()
		{
			ServoyDevServer server = new ServoyDevServer();
			FilePathResolver resolver = mock(FilePathResolver.class);
			setField(server, "filePathResolver", resolver);

			assertEquals("Error: no valid identifiers in input",
				server.getDocumentationForIdentifiers(" , , ", "f.js"));
			verifyNoInteractions(resolver);
		}

		@Test
		@DisplayName("getDocumentationForIdentifiers returns the resolver's not-found message when the file does not resolve")
		void identifiersFileNotFound()
		{
			ServoyDevServer server = new ServoyDevServer();
			FilePathResolver resolver = mock(FilePathResolver.class);
			when(resolver.resolveFile("missing.js")).thenReturn(null);
			when(resolver.buildNotFoundMessage("missing.js")).thenReturn("NOT FOUND: missing.js");
			setField(server, "filePathResolver", resolver);

			assertEquals("NOT FOUND: missing.js",
				server.getDocumentationForIdentifiers("databaseManager", "missing.js"));
		}

		@Test
		@DisplayName("applyDocumentations requires filePath then itemsJson")
		void applyDocumentationsGuards()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: filePath is required", server.applyDocumentations(null, "[]"));
			assertEquals("Error: itemsJson is required", server.applyDocumentations("utils.js", "  "));
		}

		@Test
		@DisplayName("applyDocumentations returns the resolver's not-found message when the file does not resolve")
		void applyDocumentationsFileNotFound()
		{
			ServoyDevServer server = new ServoyDevServer();
			FilePathResolver resolver = mock(FilePathResolver.class);
			when(resolver.resolveFile("gone.js")).thenReturn(null);
			when(resolver.buildNotFoundMessage("gone.js")).thenReturn("NOPE");
			setField(server, "filePathResolver", resolver);

			assertEquals("NOPE", server.applyDocumentations("gone.js", "[{}]"));
		}
	}

	// =======================================================================
	// resolveIdentifierType - delegates through the two mocked collaborators
	// =======================================================================

	@Nested
	class ResolveIdentifierType
	{
		@Test
		@DisplayName("resolveIdentifierType returns the resolver's not-found message when the script does not resolve")
		void scriptNotFound()
		{
			ServoyDevServer server = new ServoyDevServer();
			ServoyScriptResolver resolver = mock(ServoyScriptResolver.class);
			when(resolver.resolveScript("customers", "mod")).thenReturn(null);
			when(resolver.buildNotFoundMessage("customers", "mod")).thenReturn("script not found");
			setField(server, "scriptResolver", resolver);
			ScriptContextService ctx = mock(ScriptContextService.class);
			setField(server, "scriptContextService", ctx);

			assertEquals("script not found", server.resolveIdentifierType("foundset", "customers", "mod"));
			verify(ctx, never()).resolveIdentifierType(anyString(), org.mockito.ArgumentMatchers.any());
		}

		@Test
		@DisplayName("resolveIdentifierType delegates to the context service once the script resolves")
		void delegatesWhenResolved()
		{
			ServoyDevServer server = new ServoyDevServer();
			IFile file = mock(IFile.class);
			ServoyScriptResolver resolver = mock(ServoyScriptResolver.class);
			when(resolver.resolveScript("customers", null)).thenReturn(file);
			setField(server, "scriptResolver", resolver);
			ScriptContextService ctx = mock(ScriptContextService.class);
			when(ctx.resolveIdentifierType("foundset", file)).thenReturn("type: JSFoundSet");
			setField(server, "scriptContextService", ctx);

			assertEquals("type: JSFoundSet", server.resolveIdentifierType("foundset", "customers", null));
			verify(ctx).resolveIdentifierType("foundset", file);
		}
	}

	// =======================================================================
	// validate - guard + formatting of the mocked DLTK problems
	// =======================================================================

	@Nested
	class ValidateTool
	{
		@Test
		@DisplayName("validate requires non-blank code")
		void blankCodeRejected()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: code parameter is required", server.validate(null));
			assertEquals("Error: code parameter is required", server.validate("   "));
		}

		@Test
		@DisplayName("validate reports success when the validator finds no problems")
		void validCode()
		{
			ServoyDevServer server = new ServoyDevServer();
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			when(validator.validate("var x = 1;")).thenReturn(List.of());
			setField(server, "jsCodeValidator", validator);

			assertEquals("Valid: code parses successfully.", server.validate("var x = 1;"));
		}

		@Test
		@DisplayName("validate formats the problem count, messages and line numbers")
		void invalidCode()
		{
			ServoyDevServer server = new ServoyDevServer();
			JsCodeValidatorService validator = mock(JsCodeValidatorService.class);
			when(validator.validate("var x =;")).thenReturn(List.of(problem(2, "missing expression"), problem(0, "stray")));
			setField(server, "jsCodeValidator", validator);

			String result = server.validate("var x =;");
			assertTrue(result.startsWith("Invalid: 2 problem(s) found."), "got: " + result);
			assertTrue(result.contains("1. missing expression (line 2)"), "got: " + result);
			// a non-positive line number is omitted from the formatted line
			assertTrue(result.contains("2. stray"), "got: " + result);
			assertFalse(result.contains("stray (line"), "non-positive line must not be printed; got: " + result);
		}
	}

	// =======================================================================
	// validateFormat - formatting of the mocked FormatValidatorService result
	// =======================================================================

	@Nested
	class ValidateFormatTool
	{
		@Test
		@DisplayName("validateFormat renders a valid result with its display/edit formats and flags")
		void rendersValid()
		{
			ServoyDevServer server = new ServoyDevServer();
			FormatValidatorService fvs = mock(FormatValidatorService.class);
			when(fvs.validateFormat("dd/MM/yyyy", "DATETIME")).thenReturn(
				new FormatValidatorService.ValidationResult(true, "DATETIME", "dd/MM/yyyy", "dd-MM-yyyy",
					List.of("MASK", "UPPERCASE"), null));
			setField(server, "formatValidatorService", fvs);

			String result = server.validateFormat("dd/MM/yyyy", "DATETIME");
			assertTrue(result.startsWith("Valid format for DATETIME"), "got: " + result);
			assertTrue(result.contains("- Display format: dd/MM/yyyy"), "got: " + result);
			assertTrue(result.contains("- Edit format:    dd-MM-yyyy"), "got: " + result);
			assertTrue(result.contains("- Flags:          MASK, UPPERCASE"), "got: " + result);
			assertFalse(result.contains("Error:"), "got: " + result);
		}

		@Test
		@DisplayName("validateFormat renders an invalid result with its error line")
		void rendersInvalid()
		{
			ServoyDevServer server = new ServoyDevServer();
			FormatValidatorService fvs = mock(FormatValidatorService.class);
			when(fvs.validateFormat("zzz", "NUMBER")).thenReturn(
				new FormatValidatorService.ValidationResult(false, "NUMBER", null, null, List.of(), "bad pattern 'zzz'"));
			setField(server, "formatValidatorService", fvs);

			String result = server.validateFormat("zzz", "NUMBER");
			assertTrue(result.startsWith("Invalid format for NUMBER"), "got: " + result);
			assertTrue(result.contains("Error: bad pattern 'zzz'"), "got: " + result);
			assertFalse(result.contains("- Display format:"), "no display line when null; got: " + result);
		}
	}

	// =======================================================================
	// validateFormElementFormat - blank formName guard
	// =======================================================================

	@Nested
	class ValidateFormElementFormatGuard
	{
		@Test
		@DisplayName("validateFormElementFormat requires a non-blank formName")
		void blankFormNameRejected()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: formName parameter is required",
				server.validateFormElementFormat(null, "el", "sol"));
			assertEquals("Error: formName parameter is required",
				server.validateFormElementFormat("  ", null, null));
		}
	}

	// =======================================================================
	// generateUUID - count parsing / range / formatting (fully pure)
	// =======================================================================

	@Nested
	class GenerateUuid
	{
		private final java.util.regex.Pattern UUID_LINE = java.util.regex.Pattern
			.compile("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}");

		@Test
		@DisplayName("generateUUID returns a single uppercase UUID for a null / blank / '1' count")
		void singleUuid()
		{
			ServoyDevServer server = new ServoyDevServer();
			for (String count : new String[] { null, "  ", "1" })
			{
				String result = server.generateUUID(count);
				assertFalse(result.contains("\n"), "single UUID must be one line; got: " + result);
				assertTrue(UUID_LINE.matcher(result).matches(), "got: " + result);
			}
		}

		@Test
		@DisplayName("generateUUID returns one uppercase UUID per line for a count > 1")
		void multipleUuids()
		{
			String result = new ServoyDevServer().generateUUID("3");
			String[] lines = result.split("\n");
			assertEquals(3, lines.length, "got: " + result);
			for (String line : lines)
				assertTrue(UUID_LINE.matcher(line).matches(), "got: " + line);
		}

		@Test
		@DisplayName("generateUUID rejects a non-numeric count")
		void nonNumericCount()
		{
			assertEquals("Error: count must be a positive integer.", new ServoyDevServer().generateUUID("abc"));
		}

		@Test
		@DisplayName("generateUUID rejects a count outside 1..100")
		void outOfRangeCount()
		{
			ServoyDevServer server = new ServoyDevServer();
			assertEquals("Error: count must be between 1 and 100.", server.generateUUID("0"));
			assertEquals("Error: count must be between 1 and 100.", server.generateUUID("101"));
		}
	}

	// =======================================================================
	// Database-schema tools - parameter guards (return before the registry)
	// =======================================================================

	@Nested
	class DatabaseToolGuards
	{
		private final ServoyDevServer server = new ServoyDevServer();

		@Test
		@DisplayName("listTables requires a non-blank serverName")
		void listTablesGuard()
		{
			assertEquals("Error: serverName parameter is required", server.listTables(null));
			assertEquals("Error: serverName parameter is required", server.listTables("  "));
		}

		@Test
		@DisplayName("getTableInfo requires serverName then tableName")
		void getTableInfoGuard()
		{
			assertEquals("Error: serverName parameter is required", server.getTableInfo(null, "t"));
			assertEquals("Error: tableName parameter is required", server.getTableInfo("srv", "  "));
		}

		@Test
		@DisplayName("executeSQL requires serverName then sql")
		void executeSqlGuard()
		{
			assertEquals("Error: serverName is required", server.executeSQL(null, "select 1"));
			assertEquals("Error: sql is required", server.executeSQL("srv", "  "));
		}

		@Test
		@DisplayName("addColumn requires serverName, tableName and columnName")
		void addColumnBlankGuards()
		{
			assertEquals("Error: serverName is required", server.addColumn(null, "t", "c", null, null, null, null));
			assertEquals("Error: tableName is required", server.addColumn("srv", "  ", "c", null, null, null, null));
			assertEquals("Error: columnName is required", server.addColumn("srv", "t", null, null, null, null, null));
		}

		@Test
		@DisplayName("addColumn rejects a column name that is not a valid SQL identifier")
		void addColumnInvalidIdentifier()
		{
			String result = server.addColumn("srv", "t", "1bad name", null, null, null, null);
			assertTrue(result.startsWith("Error:") && result.contains("not a valid SQL identifier"), "got: " + result);
		}

		@Test
		@DisplayName("addColumn rejects an unknown column type before reaching the model")
		void addColumnInvalidType()
		{
			String result = server.addColumn("srv", "t", "col", "FLOATY", null, null, null);
			assertTrue(result.startsWith("Error: Invalid column type 'FLOATY'"), "got: " + result);
		}

		@Test
		@DisplayName("addColumn rejects a non-numeric length before reaching the model")
		void addColumnInvalidLength()
		{
			String result = server.addColumn("srv", "t", "col", "TEXT", "notanumber", null, null);
			assertEquals("Error: Invalid length value 'notanumber'", result);
		}

		@Test
		@DisplayName("createTable requires a tableName")
		void createTableBlankName()
		{
			assertEquals("Error: tableName is required", server.createTable("srv", null, "false", null));
		}

		// createTable's SQL-identifier / temp_ / svy_ prefix rules run AFTER a ServoyModelManager
		// access, so they trigger ServoyModelManager.<clinit> (ResourcesPlugin.getWorkspace) and are
		// only reachable with a live workbench - that guard stays in the integrationpde suite. Only
		// the two early "required" guards below are plainjunit-safe (they return before the model).

		@Test
		@DisplayName("createTable requires a serverName for a database (non in-memory) table")
		void createTableNeedsServerForDb()
		{
			assertEquals("Error: serverName is required for database tables",
				server.createTable(null, "orders", "false", null));
		}

		@Test
		@DisplayName("createServer requires a non-blank serverName")
		void createServerGuard()
		{
			assertEquals("Error: serverName is required", server.createServer(null, "db", "true"));
			assertEquals("Error: serverName is required", server.createServer("  ", null, null));
		}
	}

	// =======================================================================
	// addColumnsFromSpec - private spec parsing (reflection, mocked ITable)
	// =======================================================================

	@Nested
	class AddColumnsFromSpec
	{
		private String addColumnsFromSpec(com.servoy.j2db.persistence.ITable table, String spec)
		{
			return (String)invokePrivate(new ServoyDevServer(), "addColumnsFromSpec",
				new Class[] { com.servoy.j2db.persistence.ITable.class, String.class,
					com.servoy.j2db.persistence.IValidateName.class },
				table, spec, mock(com.servoy.j2db.persistence.IValidateName.class));
		}

		@Test
		@DisplayName("addColumnsFromSpec returns an empty string for a null / blank spec")
		void blankSpec()
		{
			assertEquals("", addColumnsFromSpec(mock(com.servoy.j2db.persistence.ITable.class), null));
			assertEquals("", addColumnsFromSpec(mock(com.servoy.j2db.persistence.ITable.class), "   "));
		}

		@Test
		@DisplayName("addColumnsFromSpec reports an invalid spec that has no type part")
		void invalidSpec()
		{
			String result = addColumnsFromSpec(mock(com.servoy.j2db.persistence.ITable.class), "justname");
			assertTrue(result.contains("Invalid column spec 'justname'"), "got: " + result);
		}

		@Test
		@DisplayName("addColumnsFromSpec reports an unknown column type")
		void invalidType()
		{
			String result = addColumnsFromSpec(mock(com.servoy.j2db.persistence.ITable.class), "col:FLOATY");
			assertTrue(result.contains("Invalid type 'FLOATY' for column 'col'"), "got: " + result);
		}

		@Test
		@DisplayName("addColumnsFromSpec reports a column that already exists without creating it")
		void columnAlreadyExists()
		{
			com.servoy.j2db.persistence.ITable table = mock(com.servoy.j2db.persistence.ITable.class);
			when(table.getColumn("id")).thenReturn(mock(com.servoy.j2db.persistence.Column.class));
			String result = addColumnsFromSpec(table, "id:INTEGER");
			assertTrue(result.contains("Column 'id' already exists"), "got: " + result);
		}
	}

	// =======================================================================
	// Security tools - parameter guards (return before the user manager)
	// =======================================================================

	@Nested
	class SecurityToolGuards
	{
		private final ServoyDevServer server = new ServoyDevServer();

		@Test
		@DisplayName("createUser requires userName then password")
		void createUserGuards()
		{
			assertEquals("Error: userName is required", server.createUser(null, "pw", null));
			assertEquals("Error: password is required", server.createUser("bob", "", null));
		}

		@Test
		@DisplayName("changeUserName requires oldName then newName")
		void changeUserNameGuards()
		{
			assertEquals("Error: oldName is required", server.changeUserName("  ", "new"));
			assertEquals("Error: newName is required", server.changeUserName("old", null));
		}

		@Test
		@DisplayName("setUserPassword requires userName then newPassword")
		void setUserPasswordGuards()
		{
			assertEquals("Error: userName is required", server.setUserPassword(null, "pw"));
			assertEquals("Error: newPassword is required", server.setUserPassword("bob", ""));
		}

		@Test
		@DisplayName("createPermission requires a permissionName")
		void createPermissionGuard()
		{
			assertEquals("Error: permissionName is required", server.createPermission("   "));
		}

		@Test
		@DisplayName("getFormSecurity requires permissionName then formName")
		void getFormSecurityGuards()
		{
			assertEquals("Error: permissionName is required", server.getFormSecurity(null, "frm", null));
			assertEquals("Error: formName is required", server.getFormSecurity("perm", "  ", null));
		}

		@Test
		@DisplayName("setFormElementAccess requires permissionName then formName")
		void setFormElementAccessBlankGuards()
		{
			assertEquals("Error: permissionName is required",
				server.setFormElementAccess(null, "frm", "el", "true", "true", null));
			assertEquals("Error: formName is required",
				server.setFormElementAccess("perm", "  ", "el", "true", "true", null));
		}

		@Test
		@DisplayName("setFormElementAccess rejects accessible=true together with viewable=false")
		void setFormElementAccessInvalidCombination()
		{
			String result = server.setFormElementAccess("perm", "frm", "el", "false", "true", null);
			assertTrue(result.startsWith("Error: Invalid combination"), "got: " + result);
		}

		@Test
		@DisplayName("setFormSecurityBulk requires permissionName, formName and accessEntries")
		void setFormSecurityBulkGuards()
		{
			assertEquals("Error: permissionName is required",
				server.setFormSecurityBulk(null, "frm", "[]", null));
			assertEquals("Error: formName is required",
				server.setFormSecurityBulk("perm", "  ", "[]", null));
			assertEquals("Error: accessEntries is required",
				server.setFormSecurityBulk("perm", "frm", "  ", null));
		}
	}

	// =======================================================================
	// Pure-delegation tools - mapped args in, collaborator result out
	// =======================================================================

	@Nested
	class DelegationTools
	{
		@Test
		@DisplayName("renameFile delegates straight to PersistRenameService.renameByName")
		void renameFileDelegates()
		{
			ServoyDevServer server = new ServoyDevServer();
			PersistRenameService rename = mock(PersistRenameService.class);
			when(rename.renameByName("old", "new")).thenReturn("renamed");
			setField(server, "persistRenameService", rename);

			assertEquals("renamed", server.renameFile("old", "new"));
			verify(rename).renameByName("old", "new");
		}

		@Test
		@DisplayName("duplicatePersist delegates straight to PersistDuplicateService.duplicatePersist")
		void duplicatePersistDelegates()
		{
			ServoyDevServer server = new ServoyDevServer();
			PersistDuplicateService dup = mock(PersistDuplicateService.class);
			when(dup.duplicatePersist("form", "orders", "orders_copy", "sol", "destSol")).thenReturn("duplicated");
			setField(server, "persistDuplicateService", dup);

			assertEquals("duplicated", server.duplicatePersist("form", "orders", "orders_copy", "sol", "destSol"));
			verify(dup).duplicatePersist("form", "orders", "orders_copy", "sol", "destSol");
		}

		@Test
		@DisplayName("the menu tools delegate straight to MenuService with their arguments")
		void menuToolsDelegate()
		{
			ServoyDevServer server = new ServoyDevServer();
			MenuService menu = mock(MenuService.class);
			lenient().when(menu.listMenus("all")).thenReturn("menus");
			lenient().when(menu.getMenuStructure("main")).thenReturn("structure");
			lenient().when(menu.createMenu("main", "cls", "public")).thenReturn("created");
			lenient().when(menu.createMenuItem("main", "it", "parent", "txt", "tip", "cls", "icon", "true"))
				.thenReturn("item created");
			lenient().when(menu.updateMenu("main", "cls2", "module_private")).thenReturn("updated");
			lenient().when(menu.updateMenuItem("main", "it", "t", "tip", "cls", "icon", "false"))
				.thenReturn("item updated");
			lenient().when(menu.deleteMenu("main")).thenReturn("deleted");
			lenient().when(menu.deleteMenuItem("main", "it")).thenReturn("item deleted");
			setField(server, "menuService", menu);

			assertEquals("menus", server.listMenus("all"));
			assertEquals("structure", server.getMenuStructure("main"));
			assertEquals("created", server.createMenu("main", "cls", "public"));
			assertEquals("item created", server.createMenuItem("main", "it", "parent", "txt", "tip", "cls", "icon", "true"));
			assertEquals("updated", server.updateMenu("main", "cls2", "module_private"));
			assertEquals("item updated", server.updateMenuItem("main", "it", "t", "tip", "cls", "icon", "false"));
			assertEquals("deleted", server.deleteMenu("main"));
			assertEquals("item deleted", server.deleteMenuItem("main", "it"));

			verify(menu).listMenus("all");
			verify(menu).createMenuItem("main", "it", "parent", "txt", "tip", "cls", "icon", "true");
			verify(menu).deleteMenuItem("main", "it");
		}
	}
}

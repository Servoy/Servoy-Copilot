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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.cache.CachedEntry;
import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCache;
import com.servoy.eclipse.developer.mcp.servers.ServoyContextServer;
import com.servoy.eclipse.developer.mcp.services.LocalHistoryService;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyContextServer}'s <em>own</em>
 * logic - without a live Eclipse workspace or real Local History.
 * <p>
 * {@code ServoyContextServer} is a thin MCP tool wrapper over two collaborators:
 * the process-scoped {@link ServoyResourceCache} singleton (for the cache tools)
 * and a {@link LocalHistoryService} (for the Local History tools). It owns no
 * {@code ServoyModelManager} lookup, so there is no {@code getServoyModel()} seam
 * to add; the server exposes a package-visible testing constructor
 * {@code ServoyContextServer(LocalHistoryService)} which these tests use to supply
 * a Mockito mock (no production change needed). The {@code cache} tools run against
 * the real in-memory singleton - it is a plain {@link java.util.LinkedHashMap}, no
 * workspace or OSGi state - cleared before each test via reflection.
 * </p>
 * <p>
 * The server's branches exercised here:
 * </p>
 * <ul>
 * <li>the pure static {@code truncate(String, int)} helper - all branches
 * (null / under-limit / exactly-at-limit / over-limit ellipsis-prefix / tiny
 * {@code maxLen}), reached by reflection;</li>
 * <li>{@code listCachedResources} - the empty "no resources cached" hint and the
 * non-empty table (header, stats line, per-entry row with the {@code TIMESTAMP_FMT}
 * timestamp and {@code ~tokens});</li>
 * <li>{@code getCachedResource} - the "not found in cache" message and the
 * {@code displayName}/{@code version}/{@code cachedAt}/content rendering of a hit;</li>
 * <li>{@code getCacheStats} - the empty stats and the non-empty "Entries" section;</li>
 * <li>{@code getFileHistory} / {@code getFileHistoryContent} / {@code compareWithHistory}
 * - that each tool forwards its exact arguments to {@link LocalHistoryService} and
 * returns its result unchanged (mocked at the boundary), plus the exception
 * pass-through.</li>
 * </ul>
 * <p>
 * The real Local History walk and the {@code ResourcesPlugin} / workspace
 * collaboration in {@link LocalHistoryService} itself need a live workbench and
 * stay in the {@code integrationpde} suite.
 * </p>
 *
 * @since 2026.11
 */
public class ServoyContextServerTest
{
	/** Same pattern/zone {@code ServoyContextServer.TIMESTAMP_FMT} uses. */
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.systemDefault());

	private ServoyContextServer server;
	private LocalHistoryService history;

	@BeforeEach
	public void setUp() throws Exception
	{
		history = mock(LocalHistoryService.class);
		server = new ServoyContextServer(history);
		clearCache();
	}

	/** Clears the singleton cache's backing map between tests (no workspace). */
	private static void clearCache() throws Exception
	{
		ServoyResourceCache cache = ServoyResourceCache.getInstance();
		Field entriesField = ServoyResourceCache.class.getDeclaredField("entries");
		entriesField.setAccessible(true);
		((Map< ? , ? >)entriesField.get(cache)).clear();
	}

	// =======================================================================
	// truncate(String, int) - pure static helper (reflection, all branches)
	// =======================================================================

	@Nested
	class Truncate
	{
		private String truncate(String s, int maxLen) throws Exception
		{
			Method m = ServoyContextServer.class.getDeclaredMethod("truncate", String.class, int.class);
			m.setAccessible(true);
			return (String)m.invoke(null, s, maxLen);
		}

		@Test
		@DisplayName("truncate returns null unchanged")
		void nullReturnedAsIs() throws Exception
		{
			assertNull(truncate(null, 10));
		}

		@Test
		@DisplayName("truncate returns a string shorter than maxLen unchanged")
		void underLimitReturnedAsIs() throws Exception
		{
			assertEquals("abc", truncate("abc", 10));
		}

		@Test
		@DisplayName("truncate returns a string exactly maxLen long unchanged (boundary)")
		void exactlyAtLimitReturnedAsIs() throws Exception
		{
			assertEquals("abcde", truncate("abcde", 5));
		}

		@Test
		@DisplayName("truncate prefixes with '...' and keeps the trailing (maxLen-3) chars when over the limit")
		void overLimitTruncatesWithEllipsisPrefix() throws Exception
		{
			// "workspace:///a/b/c/file.js" is 26 chars; maxLen 10 keeps the last 7 chars.
			String s = "workspace:///a/b/c/file.js";
			String result = truncate(s, 10);
			assertEquals(10, result.length(), "result must be exactly maxLen long: " + result);
			assertTrue(result.startsWith("..."), "over-limit result must start with the ellipsis: " + result);
			assertEquals("..." + s.substring(s.length() - 7), result);
			assertTrue(result.endsWith("file.js"), "must keep the tail of the string: " + result);
		}

		@Test
		@DisplayName("truncate with maxLen 3 yields just the ellipsis (empty tail)")
		void maxLenThreeYieldsEllipsisOnly() throws Exception
		{
			assertEquals("...", truncate("abcdef", 3));
		}
	}

	// =======================================================================
	// listCachedResources - empty hint and non-empty table formatting
	// =======================================================================

	@Nested
	class ListCachedResources
	{
		@Test
		@DisplayName("listCachedResources returns the 'no resources cached' hint when the cache is empty")
		void emptyHint()
		{
			String result = server.listCachedResources();
			assertNotNull(result);
			assertTrue(result.contains("No resources cached"), result);
			assertTrue(result.contains("servoy-ide"), "should mention servoy-ide tools when empty: " + result);
			assertTrue(result.contains("readProjectResource"), "should mention readProjectResource: " + result);
		}

		@Test
		@DisplayName("listCachedResources renders the header, stats line and a per-entry row with the timestamp and token estimate")
		void nonEmptyTable()
		{
			ServoyResourceCache.getInstance().put("workspace:///MyProject/scopes/globals.js", "globals.js", "WORKSPACE_FILE",
				"var x = 1;");
			CachedEntry entry = ServoyResourceCache.getInstance().get("workspace:///MyProject/scopes/globals.js").get();

			String result = server.listCachedResources();
			assertNotNull(result);
			assertTrue(result.contains("# Cached Resources"), "header: " + result);
			assertTrue(result.contains("Resources: 1/20"), "stats line: " + result);
			assertTrue(result.contains("URI"), "column header: " + result);
			assertTrue(result.contains("WORKSPACE_FILE"), "type column: " + result);
			assertTrue(result.contains(TIMESTAMP_FMT.format(entry.cachedAt())), "formatted timestamp: " + result);
			assertTrue(result.contains("~" + entry.estimateTokens()), "token estimate: " + result);
		}

		@Test
		@DisplayName("listCachedResources truncates a long URI with the ellipsis prefix in the row")
		void longUriTruncated()
		{
			String longUri = "workspace:///VeryLongProjectName/some/deeply/nested/directory/structure/finalFile.js";
			ServoyResourceCache.getInstance().put(longUri, "finalFile.js", "WORKSPACE_FILE", "x");

			String result = server.listCachedResources();
			// 50-char cap keeps the last 47 chars of the URI, prefixed with the "..." ellipsis.
			assertTrue(result.contains("..." + longUri.substring(longUri.length() - 47)),
				"long URI should be shown truncated with the tail kept: " + result);
			assertTrue(result.contains("finalFile.js"), "the kept tail must include the file name: " + result);
		}
	}

	// =======================================================================
	// getCachedResource - not-found message and hit rendering
	// =======================================================================

	@Nested
	class GetCachedResource
	{
		@Test
		@DisplayName("getCachedResource reports 'not found in cache' and suggests listCachedResources for an unknown URI")
		void notFound()
		{
			String result = server.getCachedResource("workspace:///NoProject/no.js");
			assertNotNull(result);
			assertTrue(result.contains("Resource not found in cache: workspace:///NoProject/no.js"), result);
			assertTrue(result.contains("listCachedResources"), "should suggest listCachedResources: " + result);
		}

		@Test
		@DisplayName("getCachedResource renders the displayName, version, cachedAt and the raw content for a hit")
		void foundRendersEntry()
		{
			ServoyResourceCache.getInstance().put("workspace:///P/test.js", "test.js", "WORKSPACE_FILE", "// test content");
			CachedEntry entry = ServoyResourceCache.getInstance().get("workspace:///P/test.js").get();

			String result = server.getCachedResource("workspace:///P/test.js");
			assertNotNull(result);
			assertTrue(result.contains("# test.js"), "should show the display name header: " + result);
			assertTrue(result.contains("v1"), "should show the version: " + result);
			assertTrue(result.contains("cached " + TIMESTAMP_FMT.format(entry.cachedAt())), "should show the cached timestamp: " + result);
			assertTrue(result.contains("// test content"), "should include the content: " + result);
		}
	}

	// =======================================================================
	// getCacheStats - empty stats and non-empty "Entries" section
	// =======================================================================

	@Nested
	class GetCacheStats
	{
		@Test
		@DisplayName("getCacheStats shows the stats header and 0/20 with an empty cache, and no Entries section")
		void emptyStats()
		{
			String result = server.getCacheStats();
			assertNotNull(result);
			assertTrue(result.contains("# Resource Cache Statistics"), result);
			assertTrue(result.contains("Resources: 0/20"), "should show 0 resources: " + result);
			assertFalse(result.contains("## Entries"), "no Entries section when empty: " + result);
		}

		@Test
		@DisplayName("getCacheStats appends the Entries summary when the cache is non-empty")
		void nonEmptyStats()
		{
			ServoyResourceCache.getInstance().put("workspace:///P/x.js", "x.js", "WORKSPACE_FILE", "hello world");

			String result = server.getCacheStats();
			assertNotNull(result);
			assertTrue(result.contains("Resources: 1/20"), "should show 1 resource: " + result);
			assertTrue(result.contains("## Entries"), "should include the Entries section: " + result);
			assertTrue(result.contains("x.js") || result.contains("workspace:///P/x.js"), "Entries should name the resource: " + result);
		}
	}

	// =======================================================================
	// Local History tools - argument forwarding + result pass-through (mock)
	// =======================================================================

	@Nested
	class LocalHistoryTools
	{
		@Test
		@DisplayName("getFileHistory forwards (projectName, filePath, maxEntries) to the service and returns its result")
		void getFileHistoryForwards()
		{
			when(history.getFileHistory("MyProject", "forms/test.js", "5")).thenReturn("history listing");

			assertEquals("history listing", server.getFileHistory("MyProject", "forms/test.js", "5"));
			verify(history).getFileHistory("MyProject", "forms/test.js", "5");
		}

		@Test
		@DisplayName("getFileHistory passes a null maxEntries straight through (the service applies the default)")
		void getFileHistoryNullMaxEntries()
		{
			when(history.getFileHistory(eq("MyProject"), eq("forms/test.js"), isNull())).thenReturn("default listing");

			assertEquals("default listing", server.getFileHistory("MyProject", "forms/test.js", null));
			verify(history).getFileHistory("MyProject", "forms/test.js", null);
		}

		@Test
		@DisplayName("getFileHistory lets a service exception propagate unchanged")
		void getFileHistoryPropagatesException()
		{
			when(history.getFileHistory(isNull(), eq("forms/test.js"), isNull()))
				.thenThrow(new RuntimeException("projectName is required"));

			RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
				() -> server.getFileHistory(null, "forms/test.js", null));
			assertTrue(ex.getMessage().contains("projectName is required"), ex.getMessage());
		}

		@Test
		@DisplayName("getFileHistoryContent forwards (projectName, filePath, index) and returns the service result")
		void getFileHistoryContentForwards()
		{
			when(history.getFileHistoryContent("MyProject", "forms/test.js", "0")).thenReturn("old content");

			assertEquals("old content", server.getFileHistoryContent("MyProject", "forms/test.js", "0"));
			verify(history).getFileHistoryContent("MyProject", "forms/test.js", "0");
		}

		@Test
		@DisplayName("getFileHistoryContent lets a service exception propagate unchanged")
		void getFileHistoryContentPropagatesException()
		{
			when(history.getFileHistoryContent(isNull(), eq("forms/test.js"), eq("0")))
				.thenThrow(new RuntimeException("projectName is required"));

			RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
				() -> server.getFileHistoryContent(null, "forms/test.js", "0"));
			assertTrue(ex.getMessage().contains("projectName is required"), ex.getMessage());
		}

		@Test
		@DisplayName("compareWithHistory forwards (projectName, filePath, index) and returns the service result")
		void compareWithHistoryForwards()
		{
			when(history.compareWithHistory("MyProject", "forms/test.js", "2")).thenReturn("unified diff");

			assertEquals("unified diff", server.compareWithHistory("MyProject", "forms/test.js", "2"));
			verify(history).compareWithHistory("MyProject", "forms/test.js", "2");
		}

		@Test
		@DisplayName("compareWithHistory lets a service exception propagate unchanged")
		void compareWithHistoryPropagatesException()
		{
			when(history.compareWithHistory(isNull(), eq("forms/test.js"), eq("0")))
				.thenThrow(new RuntimeException("projectName is required"));

			RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
				() -> server.compareWithHistory(null, "forms/test.js", "0"));
			assertTrue(ex.getMessage().contains("projectName is required"), ex.getMessage());
		}
	}

	// =======================================================================
	// CachedEntry record - the bits the server's formatting relies on
	// =======================================================================

	@Nested
	class CachedEntryRecord
	{
		@Test
		@DisplayName("CachedEntry.estimateTokens is roughly length/4 and positive for non-empty content")
		void estimateTokens()
		{
			CachedEntry entry = new CachedEntry("workspace:///P/e.js", "e.js", "WORKSPACE_FILE", "a".repeat(40), Instant.now(), 1);
			assertEquals(10, entry.estimateTokens(), "40 chars / 4 = 10 tokens");
		}

		@Test
		@DisplayName("CachedEntry.toSummary contains the display name / type")
		void toSummaryContainsType()
		{
			CachedEntry entry = new CachedEntry("workspace:///P/summary.js", "summary.js", "WORKSPACE_FILE", "x", Instant.now(), 1);
			String summary = entry.toSummary();
			assertNotNull(summary);
			assertTrue(summary.contains("WORKSPACE_FILE"), "summary should contain the type: " + summary);
		}
	}

	// =======================================================================
	// Annotation / registration / @Tool contract
	// =======================================================================

	@Nested
	class AnnotationAndContract
	{
		@Test
		@DisplayName("ServoyContextServer carries the @McpServer(name = \"servoy-context\") annotation")
		void hasCorrectAnnotation()
		{
			McpServer ann = ServoyContextServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyContextServer must have @McpServer annotation");
			assertFalse(ann.name().isBlank(), "@McpServer name must not be empty");
			assertEquals("servoy-context", ann.name(), "@McpServer name must be 'servoy-context'");
		}

		@Test
		@DisplayName("ServoyContextServer is registered in McpServerBuiltins")
		void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (cls == ServoyContextServer.class)
				{
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyContextServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("ServoyContextServer can be built via its no-arg constructor (E4 DI path)")
		void noArgConstructor()
		{
			assertNotNull(new ServoyContextServer(), "no-arg constructor must work");
		}

		@Test
		@DisplayName("the testing constructor wires the supplied LocalHistoryService")
		void testingConstructorWiresService() throws Exception
		{
			LocalHistoryService svc = mock(LocalHistoryService.class);
			ServoyContextServer s = new ServoyContextServer(svc);
			Field f = ServoyContextServer.class.getDeclaredField("localHistoryService");
			f.setAccessible(true);
			assertSame(svc, f.get(s));
		}

		@Test
		@DisplayName("every @Tool method on ServoyContextServer returns String and has a non-empty description")
		void toolMethodsContract()
		{
			int toolCount = 0;
			for (Method m : ServoyContextServer.class.getMethods())
			{
				Tool tool = m.getAnnotation(Tool.class);
				if (tool == null) continue;
				toolCount++;
				assertEquals(String.class, m.getReturnType(), "tool '" + tool.name() + "' must return String");
				assertFalse(tool.description().isBlank(), "tool '" + tool.name() + "' must have a description");
			}
			assertTrue(toolCount >= 6, "ServoyContextServer should expose at least 6 tools, found " + toolCount);
		}
	}
}

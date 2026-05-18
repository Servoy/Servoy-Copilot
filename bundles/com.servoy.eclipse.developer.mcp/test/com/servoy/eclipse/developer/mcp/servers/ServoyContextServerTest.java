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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.cache.CachedEntry;
import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCache;

/**
 * JUnit 4 tests for {@link ServoyContextServer}.
 * <p>
 * Tests that do not require a live Eclipse workspace (no {@code ResourcesPlugin} calls):
 * cache tools and the {@code restoreFileVersion} dummy.
 * </p>
 * <p>
 * Tests for {@code getFileHistory}, {@code getFileHistoryContent}, and
 * {@code compareWithHistory} with real files are covered by the curl endpoint
 * tests against Servoy Developer (those require a live workspace).
 * </p>
 */
public class ServoyContextServerTest
{
	private ServoyContextServer server;

	@Before
	public void setUp() throws Exception
	{
		server = new ServoyContextServer();
		// Clear the singleton cache between tests
		ServoyResourceCache cache = ServoyResourceCache.getInstance();
		Field entriesField = ServoyResourceCache.class.getDeclaredField("entries");
		entriesField.setAccessible(true);
		((Map<?, ?>)entriesField.get(cache)).clear();
	}

	// --- Cache tools ---

	@Test
	public void testListCachedResources_empty()
	{
		String result = server.listCachedResources();
		assertNotNull(result);
		assertTrue("Should mention servoy-ide tools when empty",
			result.contains("servoy-ide"));
		assertTrue("Should mention readProjectResource",
			result.contains("readProjectResource"));
	}

	@Test
	public void testListCachedResources_withEntry()
	{
		ServoyResourceCache.getInstance().put(
			"workspace:///MyProject/scopes/globals.js",
			"globals.js", "WORKSPACE_FILE", "var x = 1;");

		String result = server.listCachedResources();
		assertNotNull(result);
		assertTrue("Should list the cached URI", result.contains("globals.js"));
		assertTrue("Should show Cached Resources header", result.contains("Cached Resources"));
	}

	@Test
	public void testGetCacheStats_empty()
	{
		String result = server.getCacheStats();
		assertNotNull(result);
		assertTrue("Should contain stats header", result.contains("Cache Statistics"));
		assertTrue("Should show 0 resources", result.contains("0/20"));
	}

	@Test
	public void testGetCachedResource_notFound()
	{
		String result = server.getCachedResource("workspace:///NoProject/no.js");
		assertNotNull(result);
		assertTrue("Should say not found", result.contains("not found in cache"));
		assertTrue("Should suggest listCachedResources", result.contains("listCachedResources"));
	}

	@Test
	public void testGetCachedResource_found()
	{
		ServoyResourceCache.getInstance().put(
			"workspace:///P/test.js", "test.js", "WORKSPACE_FILE", "// test content");

		String result = server.getCachedResource("workspace:///P/test.js");
		assertNotNull(result);
		assertTrue("Should contain file content", result.contains("// test content"));
		assertTrue("Should show version", result.contains("v1"));
	}

	@Test
	public void testGetCacheStats_withEntry()
	{
		ServoyResourceCache.getInstance().put(
			"workspace:///P/x.js", "x.js", "WORKSPACE_FILE", "hello world");

		String result = server.getCacheStats();
		assertNotNull(result);
		assertTrue("Should show 1 resource", result.contains("1/20"));
	}

	// --- Dummy tool ---

	@Test
	public void testRestoreFileVersion_alwaysThrows()
	{
		try
		{
			server.restoreFileVersion("MyProject", "forms/test.frm", "0");
			fail("restoreFileVersion must always throw RuntimeException");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
			assertTrue("Error must mention UUID cross-references",
				e.getMessage().contains("UUID cross-references"));
			assertTrue("Error must mention intentionally not implemented",
				e.getMessage().contains("intentionally not implemented"));
		}
	}

	@Test
	public void testRestoreFileVersion_throwsForAnyInput()
	{
		// Must throw regardless of project/file/index values
		try
		{
			server.restoreFileVersion("AnyProject", "any/file.js", "99");
			fail("restoreFileVersion must always throw");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	// --- Error path tests for LocalHistoryService (null/missing project) ---

	@Test
	public void testGetFileHistory_nullProject_throws()
	{
		try
		{
			server.getFileHistory(null, "forms/test.js", null);
			fail("Should throw on null projectName");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testGetFileHistoryContent_nullProject_throws()
	{
		try
		{
			server.getFileHistoryContent(null, "forms/test.js", "0");
			fail("Should throw on null projectName");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testCompareWithHistory_nullProject_throws()
	{
		try
		{
			server.compareWithHistory(null, "forms/test.js", "0");
			fail("Should throw on null projectName");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testGetFileHistory_nullFilePath_throws()
	{
		try
		{
			server.getFileHistory("MyProject", null, null);
			fail("Should throw on null filePath");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	// --- CachedEntry record tests ---

	@Test
	public void testCachedEntry_estimateTokens()
	{
		// 40 chars / 4 = 10 tokens
		String content = "a".repeat(40);
		ServoyResourceCache.getInstance().put("workspace:///P/e.js", "e.js", "WORKSPACE_FILE", content);
		CachedEntry entry = ServoyResourceCache.getInstance().get("workspace:///P/e.js").get();
		assertTrue("Token estimate should be > 0", entry.estimateTokens() > 0);
	}

	@Test
	public void testCachedEntry_toSummary_containsDisplayName()
	{
		ServoyResourceCache.getInstance().put("workspace:///P/summary.js", "summary.js", "WORKSPACE_FILE", "x");
		CachedEntry entry = ServoyResourceCache.getInstance().get("workspace:///P/summary.js").get();
		String summary = entry.toSummary();
		assertNotNull(summary);
		assertTrue("Summary should contain display name", summary.contains("summary.js"));
	}

	@Test
	public void testCachedEntry_cachedAt_notNull()
	{
		ServoyResourceCache.getInstance().put("workspace:///P/ts.js", "ts.js", "WORKSPACE_FILE", "x");
		CachedEntry entry = ServoyResourceCache.getInstance().get("workspace:///P/ts.js").get();
		assertNotNull("cachedAt must not be null", entry.cachedAt());
	}

	// --- McpServerBuiltins registration test ---

	@Test
	public void testServoyContextServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == ServoyContextServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("ServoyContextServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyContextServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyContextServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyContextServer must have @McpServer annotation", ann);
		assertFalse("@McpServer name must not be empty", ann.name().isBlank());
		assertTrue("@McpServer name must be 'servoy-context'",
			"servoy-context".equals(ann.name()));
	}

	@Test
	public void testServoyContextServer_hasSevenToolMethods()
	{
		long toolCount = java.util.Arrays.stream(ServoyContextServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(
				com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.count();
		assertTrue("ServoyContextServer must have exactly 7 @Tool methods, found: " + toolCount,
			toolCount == 7);
	}
}

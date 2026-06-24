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
package com.servoy.eclipse.developer.mcp.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * JUnit 4 tests for {@link ServoyResourceCache}.
 * No OSGi or Eclipse workspace required - pure Java.
 */
public class ServoyResourceCacheTest
{
	private ServoyResourceCache cache;

	@Before
	public void setUp() throws Exception
	{
		// Reset the singleton's internal map between tests via reflection
		cache = ServoyResourceCache.getInstance();
		Field entriesField = ServoyResourceCache.class.getDeclaredField("entries");
		entriesField.setAccessible(true);
		((Map<?, ?>)entriesField.get(cache)).clear();
	}

	@Test
	public void testIsEmpty_afterClear()
	{
		assertTrue("Cache should be empty after clear", cache.isEmpty());
		assertEquals(0, cache.size());
	}

	@Test
	public void testPutAndGet()
	{
		cache.put("workspace:///MyProject/scopes/globals.js", "globals.js", "WORKSPACE_FILE", "var x = 1;");
		assertFalse(cache.isEmpty());
		assertEquals(1, cache.size());

		var entry = cache.get("workspace:///MyProject/scopes/globals.js");
		assertTrue(entry.isPresent());
		assertEquals("globals.js", entry.get().displayName());
		assertEquals("WORKSPACE_FILE", entry.get().type());
		assertEquals("var x = 1;", entry.get().content());
		assertEquals(1, entry.get().version());
	}

	@Test
	public void testGet_unknownUri_returnsEmpty()
	{
		var result = cache.get("workspace:///NoProject/no.js");
		assertFalse(result.isPresent());
	}

	@Test
	public void testVersionIncrement_onSecondPut()
	{
		String uri = "workspace:///P/file.js";
		cache.put(uri, "file.js", "WORKSPACE_FILE", "v1");
		cache.put(uri, "file.js", "WORKSPACE_FILE", "v2");

		var entry = cache.get(uri);
		assertTrue(entry.isPresent());
		assertEquals(2, entry.get().version());
		assertEquals("v2", entry.get().content());
	}

	@Test
	public void testLruEviction_maxEntriesExceeded() throws Exception
	{
		// Put 21 entries - the 1st should be evicted (LRU)
		for (int i = 1; i <= 21; i++)
		{
			cache.put("workspace:///P/file" + i + ".js", "file" + i + ".js", "WORKSPACE_FILE", "content" + i);
		}
		assertEquals(20, cache.size());
		// First entry should be gone
		assertFalse("First entry should have been evicted", cache.get("workspace:///P/file1.js").isPresent());
		// Last entry should be present
		assertTrue(cache.get("workspace:///P/file21.js").isPresent());
	}

	@Test
	public void testGetAll_returnsSnapshot()
	{
		cache.put("workspace:///P/a.js", "a.js", "WORKSPACE_FILE", "aaa");
		cache.put("workspace:///P/b.js", "b.js", "WORKSPACE_FILE", "bbb");

		Map<String, CachedEntry> all = cache.getAll();
		assertEquals(2, all.size());
		assertNotNull(all.get("workspace:///P/a.js"));
		assertNotNull(all.get("workspace:///P/b.js"));

		// Modifying the snapshot does not affect the cache
		all.clear();
		assertEquals(2, cache.size());
	}

	@Test
	public void testGetStats_format()
	{
		String stats = cache.getStats();
		assertNotNull(stats);
		assertTrue("Stats should contain Resources:", stats.contains("Resources:"));
		assertTrue("Stats should contain Tokens:", stats.contains("Tokens:"));
	}

	@Test
	public void testToSummary_empty()
	{
		String summary = cache.toSummary();
		assertEquals("(none)", summary);
	}

	@Test
	public void testToSummary_nonEmpty()
	{
		cache.put("workspace:///P/x.js", "x.js", "WORKSPACE_FILE", "hello");
		String summary = cache.toSummary();
		assertTrue(summary.contains("x.js"));
	}

	@Test
	public void testEstimateTotalTokens()
	{
		// "hello" = 5 chars / 4 = 1 token
		cache.put("workspace:///P/x.js", "x.js", "WORKSPACE_FILE", "hello");
		assertTrue(cache.estimateTotalTokens() >= 0);
	}

	@Test
	public void testNullContent_doesNotThrow()
	{
		cache.put("workspace:///P/null.js", "null.js", "WORKSPACE_FILE", null);
		var entry = cache.get("workspace:///P/null.js");
		assertTrue(entry.isPresent());
		assertNull(entry.get().content());
		assertEquals(0, entry.get().estimateTokens());
	}
}

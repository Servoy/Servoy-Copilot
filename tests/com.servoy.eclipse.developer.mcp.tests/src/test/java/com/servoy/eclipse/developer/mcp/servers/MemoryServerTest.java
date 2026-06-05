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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JUnit 4 tests for {@link MemoryServer}.
 */
public class MemoryServerTest
{
	private final MemoryServer server = new MemoryServer();

	@Test
	public void testMemoryServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			MemoryServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("MemoryServer must have @McpServer annotation", ann);
		assertEquals("memory", ann.name());
	}

	@Test
	public void testMemoryServer_hasTwoToolMethods()
	{
		long toolCount = java.util.Arrays.stream(MemoryServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(
				com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.count();
		assertEquals(2, toolCount);
	}

	@Test
	public void testMemoryServer_registeredInBuiltins()
	{
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			if (cls == MemoryServer.class)
			{
				found = true;
				break;
			}
		}
		assertTrue("MemoryServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testThink_returnsInput()
	{
		String thought = "This is a complex reasoning step";
		assertEquals(thought, server.think(thought));
	}

	@Test
	public void testThink_emptyString()
	{
		assertEquals("", server.think(""));
	}

	@Test
	public void testThink_nullInput()
	{
		assertEquals(null, server.think(null));
	}

	@Test
	public void testCompletionMeta_returnsInput()
	{
		String meta = "Some explanation text";
		assertEquals(meta, server.completionMeta(meta));
	}

	@Test
	public void testCompletionMeta_emptyString()
	{
		assertEquals("", server.completionMeta(""));
	}
}

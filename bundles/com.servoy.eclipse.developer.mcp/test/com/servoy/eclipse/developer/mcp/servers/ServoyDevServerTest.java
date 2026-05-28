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
 * JUnit 4 tests for {@link ServoyDevServer}.
 */
public class ServoyDevServerTest
{
	private final ServoyDevServer server = new ServoyDevServer();

	@Test
	public void testServoyDevServer_hasCorrectAnnotation()
	{
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann =
			ServoyDevServer.class.getAnnotation(
				com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyDevServer must have @McpServer annotation", ann);
		assertEquals("servoy-dev", ann.name());
	}

	@Test
	public void testServoyDevServer_hasCorrectToolCount()
	{
		long toolCount = java.util.Arrays.stream(ServoyDevServer.class.getMethods())
			.filter(m -> m.isAnnotationPresent(
				com.servoy.eclipse.developer.mcp.annotations.Tool.class))
			.count();
		assertEquals(5, toolCount);
	}

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError()
	{
		String result = server.resolveIdentifierType(null, "someForm", null);
		assertNotNull(result);
		assertTrue("Should return error for null identifier",
			result.contains("Error") || result.contains("required"));
	}

	@Test
	public void testResolveIdentifierType_unknownForm_returnsNotFound()
	{
		String result = server.resolveIdentifierType("myVar", "nonExistentForm_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue("Should return not-found message",
			result.contains("not found") || result.contains("nonExistentForm_XYZ_ABC") || result.contains("Error"));
	}

	@Test
	public void testPing_returnsPong()
	{
		assertEquals("pong", server.ping());
	}
}

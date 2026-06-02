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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;

/**
 * Integration tests for {@link ServoyIdeServer} that require a running Eclipse Workbench.
 * <p>
 * These tests exercise methods that depend on {@code ServoyScriptResolver} and
 * {@code ServoyModelManager}, which require the Workbench to be active.
 * They must be run with {@code uitestapplication} or inside a live Servoy Developer instance.
 * </p>
 */
public class ServoyIdeServerIntegrationTest
{
	private final ServoyIdeServer server = new ServoyIdeServer(
		new ProjectService(),
		new WorkspaceService(),
		new MarkdownService(),
		new IdeStateService());

	@Test
	public void testGetClassOutline_nullName_throws()
	{
		try
		{
			server.getClassOutline(null, null);
			fail("Should throw for null name");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testGetClassOutline_unknownScript_returnsNotFound()
	{
		String result = server.getClassOutline("nonExistentForm_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue("Should return not-found message",
			result.contains("not found") || result.contains("nonExistentForm_XYZ_ABC") || result.contains("Error"));
	}

	@Test
	public void testGetMethodSource_nullName_throws()
	{
		try
		{
			server.getMethodSource(null, "someMethod", null, null);
			fail("Should throw for null name");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}

	@Test
	public void testGetMethodSource_nullMethodNames_returnsError()
	{
		String result = server.getMethodSource("nonExistentForm_XYZ", null, null, null);
		assertNotNull(result);
	}

	@Test
	public void testGetFilteredSource_nullName_throws()
	{
		try
		{
			server.getFilteredSource(null, null, null, null);
			fail("Should throw for null name");
		}
		catch (RuntimeException e)
		{
			assertNotNull(e.getMessage());
		}
	}
}

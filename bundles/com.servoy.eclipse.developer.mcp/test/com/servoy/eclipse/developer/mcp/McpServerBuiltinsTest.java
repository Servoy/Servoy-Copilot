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
package com.servoy.eclipse.developer.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.servers.MemoryServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyContextServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.servers.TimeServer;

/**
 * JUnit 4 tests for {@link McpServerBuiltins}.
 */
public class McpServerBuiltinsTest
{
	@Test
	public void testBuiltInServerClasses_notEmpty()
	{
		assertTrue(McpServerBuiltins.BUILT_IN_SERVER_CLASSES.length > 0);
	}

	@Test
	public void testBuiltInServerClasses_containsAllExpected()
	{
		Class<?>[] expected = {
			TimeServer.class,
			MemoryServer.class,
			ServoyContextServer.class,
			ServoyCoderServer.class,
			ServoyIdeServer.class,
			ServoyGitServer.class,
			ServoyDevServer.class,
		};
		for (Class<?> cls : expected)
		{
			boolean found = false;
			for (Class<?> registered : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (registered == cls)
				{
					found = true;
					break;
				}
			}
			assertTrue(cls.getSimpleName() + " must be registered in BUILT_IN_SERVER_CLASSES", found);
		}
	}

	@Test
	public void testAllBuiltInClasses_haveMcpServerAnnotation()
	{
		for (Class<?> cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			McpServer ann = cls.getAnnotation(McpServer.class);
			assertNotNull(cls.getSimpleName() + " must have @McpServer annotation", ann);
			assertTrue(cls.getSimpleName() + " @McpServer name must not be blank", !ann.name().isBlank());
		}
	}

	@Test
	public void testAllBuiltInClasses_haveUniqueNames()
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		for (Class<?> cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			String name = cls.getAnnotation(McpServer.class).name();
			assertTrue("Duplicate @McpServer name: " + name, names.add(name));
		}
	}

	@Test
	public void testCreateServerInstances_nullContext()
	{
		List<Object> instances = McpServerBuiltins.createServerInstances(null);
		assertNotNull(instances);
		assertEquals(McpServerBuiltins.BUILT_IN_SERVER_CLASSES.length, instances.size());
		for (int i = 0; i < instances.size(); i++)
		{
			assertNotNull("Instance at index " + i + " must not be null", instances.get(i));
			assertEquals(McpServerBuiltins.BUILT_IN_SERVER_CLASSES[i], instances.get(i).getClass());
		}
	}

	@Test
	public void testCreateServerInstances_noArgConstructor()
	{
		List<Object> instances = McpServerBuiltins.createServerInstances();
		assertNotNull(instances);
		assertEquals(McpServerBuiltins.BUILT_IN_SERVER_CLASSES.length, instances.size());
	}

	@Test
	public void testAllBuiltInClasses_havePublicNoArgConstructor()
	{
		for (Class<?> cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
		{
			try
			{
				cls.getDeclaredConstructor();
			}
			catch (NoSuchMethodException e)
			{
				fail(cls.getSimpleName() + " must have a no-arg constructor for E4 DI");
			}
		}
	}
}

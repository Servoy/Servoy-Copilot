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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;

/**
 * JUnit 4 tests for {@link McpServerFactory}.
 */
public class McpServerFactoryTest
{
	@McpServer(name = "factory-test")
	public static class ValidServer
	{
		@Tool(name = "echo", description = "Echoes input", type = "object")
		public String echo(
			@ToolParam(name = "input", description = "Text to echo", required = true) String input)
		{
			return input;
		}
	}

	public static class NoAnnotationServer
	{
		@Tool(name = "noop", description = "Does nothing", type = "object")
		public String noop()
		{
			return "";
		}
	}

	@McpServer(name = "empty-server")
	public static class EmptyServer
	{
	}

	@Test
	public void testGetInstance_notNull()
	{
		assertNotNull(McpServerFactory.getInstance());
	}

	@Test
	public void testGetInstance_sameInstance()
	{
		assertTrue(McpServerFactory.getInstance() == McpServerFactory.getInstance());
	}

	@Test
	public void testCreateSyncServer_validServer()
	{
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/factory-test")
				.build();

		McpSyncServer syncServer = McpServerFactory.getInstance()
			.createSyncServer(new ValidServer(), transport);
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}

	@Test
	public void testCreateSyncServer_noAnnotation_throws()
	{
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/no-ann")
				.build();

		try
		{
			McpServerFactory.getInstance().createSyncServer(new NoAnnotationServer(), transport);
			fail("Should throw IllegalArgumentException for missing @McpServer");
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage().contains("missing @McpServer"));
		}
	}

	@Test
	public void testCreateSyncServer_emptyServer()
	{
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/empty-server")
				.build();

		McpSyncServer syncServer = McpServerFactory.getInstance()
			.createSyncServer(new EmptyServer(), transport);
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}

	@Test
	public void testCreateSyncServer_withExcludedTools()
	{
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/excluded")
				.build();

		McpSyncServer syncServer = McpServerFactory.getInstance()
			.createSyncServer(new ValidServer(), transport, java.util.List.of("echo"));
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}
}

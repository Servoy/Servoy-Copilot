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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Creates {@link McpSyncServer} instances from annotated server implementation objects.
 *
 * <p>Server classes must be annotated with {@link com.servoy.eclipse.developer.mcp.annotations.McpServer}.
 * Tool methods must be annotated with {@link com.servoy.eclipse.developer.mcp.annotations.Tool}.
 * Tool parameters must be annotated with {@link ToolParam}.</p>
 */
public class McpServerFactory
{
	private static final McpServerFactory INSTANCE = new McpServerFactory();

	private McpServerFactory()
	{
	}

	public static McpServerFactory getInstance()
	{
		return INSTANCE;
	}

	public McpSyncServer createSyncServer(Object serverImpl,
		HttpServletStreamableServerTransportProvider transportProvider)
	{
		return createSyncServer(serverImpl, transportProvider, Collections.emptyList());
	}

	public McpSyncServer createSyncServer(Object serverImpl,
		HttpServletStreamableServerTransportProvider transportProvider,
		Collection<String> excludedTools)
	{
		requireMcpServerAnnotation(serverImpl);

		McpSchema.Implementation info = createImplementationInfo(serverImpl);
		McpSchema.ServerCapabilities capabilities = createCapabilities();
		List<SyncToolSpecification> toolSpecs = createToolSpecifications(serverImpl, excludedTools);

		return McpServer.sync(transportProvider)
			.serverInfo(info)
			.capabilities(capabilities)
			.tools(toolSpecs)
			.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
			.jsonSchemaValidator(new io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier().get())
			.build();
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private McpSchema.Implementation createImplementationInfo(Object serverImpl)
	{
		String serverName = serverImpl.getClass()
			.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class)
			.name();
		String version = Optional.ofNullable(FrameworkUtil.getBundle(McpServerFactory.class))
			.map(Bundle::getVersion)
			.map(Object::toString)
			.orElse("2026.3.0");
		return new McpSchema.Implementation(serverName, version);
	}

	private McpSchema.ServerCapabilities createCapabilities()
	{
		return McpSchema.ServerCapabilities.builder()
			.logging()
			.prompts(false)
			.resources(false, false)
			.tools(true)
			.build();
	}

	private List<SyncToolSpecification> createToolSpecifications(Object serverImpl,
		Collection<String> excludedTools)
	{
		var excluded = java.util.Set.copyOf(excludedTools);
		var executor = new ToolExecutor(serverImpl);
		var tools = extractAnnotatedTools(executor.getFunctions());

		if (tools.isEmpty())
		{
			Platform.getLog(McpServerFactory.class).warn("No @Tool methods found on " + serverImpl.getClass().getName());
		}

		return tools.stream()
			.filter(tool -> !excluded.contains(tool.name()))
			.map(tool -> McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> executeCallTool(executor, tool, request.arguments()))
				.build())
			.collect(Collectors.toList());
	}

	private List<Tool> extractAnnotatedTools(Method... methods)
	{
		var tools = new ArrayList<Tool>();
		for (Method method : methods)
		{
			var toolAnn = method.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.Tool.class);
			if (toolAnn == null) continue;

			var properties = new LinkedHashMap<String, Object>();
			var required = new ArrayList<String>();

			for (var param : method.getParameters())
			{
				ToolParam paramAnn = param.getAnnotation(ToolParam.class);
				if (paramAnn != null)
				{
					String name = ToolExecutor.toParamName(param);
					properties.put(name, Map.of(
						"type", paramAnn.type(),
						"description", paramAnn.description()));
					if (paramAnn.required()) required.add(name);
				}
			}

			McpSchema.JsonSchema schema =
				new McpSchema.JsonSchema(toolAnn.type(), properties, required, false, null, null);
			tools.add(new Tool(toolAnn.name(), toolAnn.name(), toolAnn.description(), schema, null, null, null));
		}
		return tools;
	}

	private CallToolResult executeCallTool(ToolExecutor executor, Tool tool, Map<String, Object> args)
	{
		try
		{
			Object result = executor.call(tool.name(), args).get();
			String text = Optional.ofNullable(result).map(Object::toString).orElse("");
			var content = new McpSchema.TextContent(
				new McpSchema.Annotations(List.of(McpSchema.Role.ASSISTANT), 0.0), text);
			return McpSchema.CallToolResult.builder().addContent(content).isError(false).build();
		}
		catch (Exception e)
		{
			Platform.getLog(McpServerFactory.class).error(e.getMessage(), e);
			Throwable root = e;
			while (root.getCause() != null) root = root.getCause();
			String cause = root.getMessage() != null ? root.getMessage() : e.getClass().getSimpleName();
			var content = new McpSchema.TextContent("Error: " + cause);
			return McpSchema.CallToolResult.builder().addContent(content).isError(true).build();
		}
	}

	private void requireMcpServerAnnotation(Object serverImpl)
	{
		if (serverImpl.getClass()
			.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class) == null)
		{
			throw new IllegalArgumentException(
				"Not an MCP server (missing @McpServer annotation): " + serverImpl.getClass().getName());
		}
	}
}

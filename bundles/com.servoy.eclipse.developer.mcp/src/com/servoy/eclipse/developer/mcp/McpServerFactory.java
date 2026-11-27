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
import java.util.TreeSet;
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

		// Missing-required-param validation is handled by validateRequiredParams() at
		// handler level to produce recoverable tool errors instead of protocol-level
		// exceptions. The jsonMapper/jsonSchemaValidator below are still supplied
		// explicitly (rather than relying on McpJsonDefaults' OSGi ServiceLoader/DS
		// lookup) because mcp-json-jackson2 is embedded on this bundle's
		// Bundle-ClassPath rather than wired in as a real OSGi bundle, so its
		// Service-Component declarations are never processed and the ServiceLoader
		// lookup fails with a ServiceConfigurationError.
		// @formatter:off
		McpSyncServer server = McpServer.sync(transportProvider)
			.serverInfo(info)
			.capabilities(capabilities)
			.tools(toolSpecs)
			.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
			.jsonSchemaValidator(new io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier().get())
			.build();
		// @formatter:on

		registerCancelledNotificationHandler(transportProvider);

		return server;
	}

	@SuppressWarnings("unchecked")
	private void registerCancelledNotificationHandler(
		HttpServletStreamableServerTransportProvider transportProvider)
	{
		try
		{
			java.lang.reflect.Field sessionFactoryField =
				HttpServletStreamableServerTransportProvider.class.getDeclaredField("sessionFactory");
			sessionFactoryField.setAccessible(true);
			Object sessionFactory = sessionFactoryField.get(transportProvider);
			if (sessionFactory instanceof io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory factory)
			{
				java.lang.reflect.Field handlersField =
					io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory.class
						.getDeclaredField("notificationHandlers");
				handlersField.setAccessible(true);
				Map<String, io.modelcontextprotocol.server.McpNotificationHandler> handlers =
					(Map<String, io.modelcontextprotocol.server.McpNotificationHandler>)handlersField.get(factory);
				handlers.put("notifications/cancelled", (exchange, params) -> reactor.core.publisher.Mono.empty());
			}
		}
		catch (Exception e)
		{
			Platform.getLog(McpServerFactory.class).warn(
				"Could not register notifications/cancelled handler: " + e.getMessage());
		}
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
			org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(McpServerFactory.class);
			if (bundle != null)
			{
				Platform.getLog(McpServerFactory.class).warn("No @Tool methods found on " + serverImpl.getClass().getName());
			}
		}

		return tools.stream()
			.filter(tool -> !excluded.contains(tool.name()))
			.map(tool -> McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> {
					Map<String, Object> args = request.arguments();
					String validationError = validateCall(tool, args);
					if (validationError != null)
					{
						var content = new McpSchema.TextContent("Error: " + validationError);
						return McpSchema.CallToolResult.builder().addContent(content).isError(true).build();
					}
					return executeCallTool(executor, tool, args);
				})
				.build())
			.collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	public static String validateRequiredParams(Tool tool, Map<String, Object> args)
	{
		var schema = tool.inputSchema();
		if (schema == null) return null;
		var requiredList = (List<String>)schema.get("required");
		if (requiredList == null || requiredList.isEmpty())
		{
			return null;
		}

		var missing = new ArrayList<String>();
		for (String param : requiredList)
		{
			if (args == null || !args.containsKey(param) || args.get(param) == null)
			{
				missing.add(param);
			}
		}

		if (missing.isEmpty())
		{
			return null;
		}

		var received = (args != null) ? args.keySet().stream().sorted().collect(Collectors.toList()) : List.of();
		return "Missing required parameter(s) for tool '" + tool.name() + "': " + missing + ". " + "Received parameters: " + received + ". " +
			"Please re-call the tool with all required parameters.";
	}

	/**
	 * Validates a tool call: missing required parameters and unknown (undeclared) parameters.
	 *
	 * @param tool the tool being called
	 * @param args the arguments supplied by the caller (may be null)
	 * @return the missing-required and unknown-parameter messages joined by a single space, whichever applies, or {@code null} when the call is valid
	 */
	public static String validateCall(Tool tool, Map<String, Object> args)
	{
		String required = validateRequiredParams(tool, args);
		String unknown = validateUnknownParams(tool, args);
		if (required != null && unknown != null) return required + " " + unknown;
		return required != null ? required : unknown;
	}

	/**
	 * Returns an error message when the call supplies argument names the tool does not declare, or {@code null} when all supplied names are declared.
	 *
	 * @param tool the tool being called
	 * @param args the arguments supplied by the caller (may be null)
	 * @return the error message, or {@code null} when every supplied argument name is declared by the tool
	 */
	public static String validateUnknownParams(Tool tool, Map<String, Object> args)
	{
		var schema = tool.inputSchema();
		if (schema == null || args == null || args.isEmpty()) return null;

		var declared = new TreeSet<String>();
		if (schema.get("properties") instanceof Map< ? , ? > properties)
		{
			for (Object key : properties.keySet())
			{
				declared.add(String.valueOf(key));
			}
		}

		var unknown = args.keySet().stream().filter(key -> !declared.contains(key)).sorted().collect(Collectors.toList());
		if (unknown.isEmpty()) return null;

		String accepted = declared.isEmpty() ? "[] (this tool takes no parameters)." : new ArrayList<>(declared) + ".";
		return "Unknown parameter(s) for tool '" + tool.name() + "': " + unknown + ". Accepted parameters: " + accepted +
			" Please re-call the tool using only the accepted parameter names.";
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
						"type", schemaType(paramAnn),
						"description", paramAnn.description()));
					if (paramAnn.required()) required.add(name);
				}
			}

			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", toolAnn.type());
			schema.put("properties", properties);
			schema.put("required", required);
			tools.add(new Tool(toolAnn.name(), toolAnn.name(), toolAnn.description(), schema, null, null, null));
		}
		return tools;
	}

	/**
	 * The JSON-schema {@code type} published for a parameter: the declared type, or
	 * {@code [type, "string"]} when {@link ToolParam#acceptString()} lets callers still send a string.
	 * Package-visible and static so it can be unit-tested without an MCP runtime.
	 */
	static Object schemaType(ToolParam paramAnn)
	{
		String type = paramAnn.type();
		if (paramAnn.acceptString() && !"string".equals(type))
		{
			return List.of(type, "string");
		}
		return type;
	}

	/**
	 * Removes credentials embedded in any URL in the text. A tool failure can carry one in its
	 * message (JGit's transport exceptions print the remote URL), and this message goes to the model
	 * and into session transcripts. Applied here, to every tool, rather than in each service, so a
	 * failure thrown from anywhere is covered whichever exception type it is. Package-visible and
	 * static so it can be unit-tested without an MCP runtime.
	 */
	static String redactUrls(String text)
	{
		return com.servoy.eclipse.developer.mcp.services.GitService.redactUrlsIn(text);
	}

	/**
	 * The text a failed tool call reports to the model: {@code "Error: "} plus the message of the
	 * ROOT cause of the chain, with any URL credentials removed. The root cause is used because the
	 * wrappers a service adds only repeat it, but it is also where JGit's transport exceptions put
	 * the remote URL, so it is exactly the message that has to be redacted.
	 */
	public static String errorText(Throwable failure)
	{
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		String cause = root.getMessage() != null ? root.getMessage() : failure.getClass().getSimpleName();
		return "Error: " + redactUrls(cause);
	}

	/**
	 * A copy of the exception chain whose messages are redacted, with the original stack traces and
	 * the original exception class names kept for diagnosis. The original is never logged: its
	 * message, and the messages of its causes, are what carry the URL.
	 */
	static Throwable redactedCopy(Throwable original)
	{
		if (original == null) return null;
		Throwable cause = original.getCause() == original ? null : redactedCopy(original.getCause());
		String message = original.getClass().getName() + (original.getMessage() == null ? "" : ": " + redactUrls(original.getMessage()));
		Throwable copy = new RuntimeException(message, cause);
		copy.setStackTrace(original.getStackTrace());
		return copy;
	}

	private CallToolResult executeCallTool(ToolExecutor executor, Tool tool, Map<String, Object> args)
	{
		try
		{
			Object result = executor.call(tool.name(), args).get();
			var builder = McpSchema.CallToolResult.builder().isError(false);
			for (McpSchema.Content content : toContents(result))
			{
				builder.addContent(content);
			}
			return builder.build();
		}
		catch (Exception e)
		{
			Throwable root = e;
			while (root.getCause() != null) root = root.getCause();
			if (!(root instanceof com.servoy.eclipse.developer.mcp.ResourceNotFoundException))
			{
				// Expected "not found" outcomes are not real errors - don't spam the Eclipse log.
				// Logged through a redacted copy: a JGit failure message is the remote URL, which may
				// carry a token, and the log is read by people and attached to bug reports.
				Platform.getLog(McpServerFactory.class).error(redactUrls(e.getMessage()), redactedCopy(e));
			}
			var content = new McpSchema.TextContent(errorText(e));
			return McpSchema.CallToolResult.builder().addContent(content).isError(true).build();
		}
	}

	/**
	 * Maps a tool method's return value to the MCP content parts of a successful result.
	 * <p>
	 * A tool that returns an {@link McpToolResult} yields a text content part plus, when it
	 * carries an image, an image content part; any other return value (the common case) yields a
	 * single text content part from its {@code toString()} (or the empty string when
	 * {@code null}). Package-visible and static so it can be unit-tested without an MCP runtime.
	 * </p>
	 *
	 * @param result the value returned by the tool method
	 * @return the ordered content parts to attach to the {@link CallToolResult}
	 */
	public static List<McpSchema.Content> toContents(Object result)
	{
		var annotations = new McpSchema.Annotations(List.of(McpSchema.Role.ASSISTANT), 0.0);
		if (result instanceof McpToolResult toolResult)
		{
			var contents = new ArrayList<McpSchema.Content>(2);
			contents.add(new McpSchema.TextContent(annotations, toolResult.text()));
			if (toolResult.hasImage())
			{
				contents.add(
					McpSchema.ImageContent.builder(toolResult.imageData(), toolResult.imageMimeType()).build());
			}
			return contents;
		}
		String text = Optional.ofNullable(result).map(Object::toString).orElse("");
		return List.of(new McpSchema.TextContent(annotations, text));
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
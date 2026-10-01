/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest.headless;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Small client for calling a Servoy Developer MCP tool over HTTP from the
 * headless skill-test runner - e.g. {@code servoy-dev/createSolution} and
 * {@code activateSolution}.
 * <p>
 * This is the same streamable-HTTP + explicit-supplier setup proven by
 * {@code McpProbeApplication}: the MCP SDK's {@code ServiceLoader} discovery of
 * its JSON mapper / schema validator does not work across OSGi bundles, so we
 * hand the client concrete Jackson instances. The bundle session bearer token is
 * attached; internal {@code @servoy.com} logins bypass auth anyway.
 * </p>
 */
public final class McpToolClient {

	private final String base;
	private final SetupLogger logger;

	public McpToolClient(int webPort, SetupLogger logger) {
		this.base = "http://127.0.0.1:" + webPort; //$NON-NLS-1$
		this.logger = logger != null ? logger : msg -> {
		};
	}

	/**
	 * Calls {@code toolName} on the {@code servoy-dev} MCP server with the given
	 * arguments and returns the textual result. Throws on an error result.
	 *
	 * @param toolName the MCP tool (e.g. {@code "createSolution"})
	 * @param args     the tool arguments
	 * @return the tool's text result (best-effort string form)
	 * @throws Exception if the call fails or the tool reports an error
	 */
	public String callDevTool(String toolName, Map<String, Object> args) throws Exception {
		String endpoint = "/dev_mcp/servoy-dev"; //$NON-NLS-1$
		String token = readSessionAuthToken();
		ObjectMapper om = new ObjectMapper();
		McpSyncClient client = null;
		try {
			HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
					.endpoint(endpoint)
					.jsonMapper(new JacksonMcpJsonMapper(om))
					.httpRequestCustomizer((builder, method, uri, body, context) -> {
						if (token != null && !token.isBlank()) {
							builder.header("Authorization", "Bearer " + token); //$NON-NLS-1$ //$NON-NLS-2$
						}
					})
					.build();
			client = McpClient.sync(transport)
					.jsonSchemaValidator(new DefaultJsonSchemaValidator(om))
					.requestTimeout(Duration.ofMinutes(2))
					.build();
			client.initialize();
			CallToolResult result = client.callTool(CallToolRequest.builder(toolName).arguments(args).build());
			String text = describe(result);
			if (result != null && Boolean.TRUE.equals(result.isError())) {
				throw new IllegalStateException("MCP tool '" + toolName + "' error: " + text); //$NON-NLS-1$ //$NON-NLS-2$
			}
			logger.log("[skilltest] MCP " + toolName + " -> " + text); //$NON-NLS-1$ //$NON-NLS-2$
			return text;
		} finally {
			if (client != null) {
				try {
					client.closeGracefully();
				} catch (Throwable ignore) {
					// best effort
				}
			}
		}
	}

	private static String describe(CallToolResult result) {
		if (result == null || result.content() == null || result.content().isEmpty()) {
			return "(no content)"; //$NON-NLS-1$
		}
		return result.content().toString();
	}

	/**
	 * Reads {@code Activator.SESSION_AUTH_TOKEN} from the developer.mcp bundle
	 * reflectively (that package is not exported). Returns {@code null} if
	 * unavailable - fine, since internal logins bypass auth.
	 */
	private static String readSessionAuthToken() {
		try {
			org.osgi.framework.Bundle b = org.eclipse.core.runtime.Platform
					.getBundle("com.servoy.eclipse.developer.mcp"); //$NON-NLS-1$
			if (b == null) {
				return null;
			}
			Class<?> act = b.loadClass("com.servoy.eclipse.developer.mcp.Activator"); //$NON-NLS-1$
			Object v = act.getField("SESSION_AUTH_TOKEN").get(null); //$NON-NLS-1$
			return v == null ? null : v.toString();
		} catch (Throwable t) {
			return null;
		}
	}
}

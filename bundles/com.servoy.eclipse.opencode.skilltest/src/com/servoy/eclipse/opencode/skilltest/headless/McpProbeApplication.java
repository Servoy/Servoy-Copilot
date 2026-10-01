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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import com.servoy.eclipse.model.util.ModelUtils;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

/**
 * Standalone diagnostic application that reports how/whether the MCP servers
 * come up, so we can see the real topology (which servers, which port/path,
 * whether they need the workbench) without the whole skill-test pipeline.
 * <p>
 * It does the bare minimum: report UI-disabled + workbench state, wait for the
 * Servoy application server + embedded web server, then enumerate the
 * {@code developer.mcp} bundle state and probe the known MCP endpoints on the
 * web-server port. It never disables the UI, so - when launched as a
 * RuntimeWorkbench - the workbench comes up and MCP should register.
 * </p>
 * <p>
 * Invoke via a PDE RuntimeWorkbench launch with
 * {@code -application com.servoy.eclipse.opencode.skilltest.mcpProbe}.
 * </p>
 */
public class McpProbeApplication implements IApplication {

	private static final long WAIT_MS = 180_000L;

	@Override
	public Object start(IApplicationContext context) throws Exception {
		return probe();
	}

	/**
	 * The probe body, callable both as a bare {@code IApplication} and from the
	 * workbench {@code IStartup} ({@link McpProbeStartup}).
	 */
	public int probe() throws Exception {
		log("=== MCP PROBE ===");
		log("UI disabled? " + ModelUtils.isUIDisabled() + " | workbench running? " + isWorkbenchRunning());

		// Wait for the app server (started by the workbench when UI is enabled).
		log("waiting for application server (up to " + (WAIT_MS / 1000) + "s)...");
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (ApplicationServerRegistry.get() == null && System.currentTimeMillis() < deadline) {
			Thread.sleep(1_000L);
		}
		boolean asUp = ApplicationServerRegistry.get() != null;
		log("application server up? " + asUp + " | workbench running? " + isWorkbenchRunning());
		if (!asUp) {
			log("app server never started - stopping probe.");
			return EXIT_OK;
		}

		int webPort = -1;
		long portDeadline = System.currentTimeMillis() + 60_000L;
		while (webPort <= 0 && System.currentTimeMillis() < portDeadline) {
			webPort = ApplicationServerRegistry.get().getWebServerPort();
			if (webPort <= 0) {
				Thread.sleep(1_000L);
			}
		}
		log("web server port: " + webPort);

		// developer.mcp bundle state.
		try {
			org.osgi.framework.Bundle b = org.eclipse.core.runtime.Platform
					.getBundle("com.servoy.eclipse.developer.mcp"); //$NON-NLS-1$
			log("developer.mcp bundle state: " + (b == null ? "<not found>" : b.getState() + " (4=ACTIVE)"));
		} catch (Throwable t) {
			log("developer.mcp not resolvable: " + t);
		}

		// Give MCP a moment to register after the workbench is up.
		Thread.sleep(10_000L);

		// Bare-GET reachability probe (400 = servlet present, 404 = not registered).
		probe("http://127.0.0.1:" + webPort + "/dev_mcp/servoy-dev");

		// Real MCP handshake: speak the streamable-HTTP protocol so we get a proper
		// initialize + tools/list (this is the "HTTP 200" success, not a bare GET).
		mcpHandshake(webPort, "/dev_mcp/servoy-dev");
		mcpHandshake(webPort, "/dev_mcp/servoy-ide");

		log("workbench running (final)? " + isWorkbenchRunning());
		log("=== MCP PROBE DONE ===");
		return EXIT_OK;
	}

	/**
	 * Connects a real MCP client to the endpoint and runs the initialize +
	 * tools/list handshake. Success here is the meaningful "MCP works" signal (the
	 * underlying HTTP is 200 for the streamable exchange); a bare GET only ever
	 * yields 400. Uses the session bearer token; internal {@code @servoy.com}
	 * logins bypass auth anyway.
	 */
	private void mcpHandshake(int webPort, String endpoint) {
		String base = "http://127.0.0.1:" + webPort;
		// Internal @servoy.com logins bypass the bearer check, so a token is usually
		// unnecessary; read it reflectively if the MCP bundle exposes it, else omit.
		String token = readSessionAuthToken();
		log("  handshake " + base + endpoint + " starting (token=" + (token == null ? "none" : "present") + ")...");
		McpSyncClient client = null;
		try {
			// Supply BOTH the Jackson JSON mapper and the schema validator explicitly:
			// the SDK's ServiceLoader discovery of these suppliers does not work across
			// OSGi bundles ("No McpJsonMapperSupplier / JsonSchemaValidatorSupplier
			// available"), so we hand the client concrete instances.
			ObjectMapper om = new ObjectMapper();
			log("    building transport...");
			HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
					.endpoint(endpoint)
					.jsonMapper(new JacksonMcpJsonMapper(om))
					.httpRequestCustomizer((builder, method, uri, body, context) -> {
						if (token != null && !token.isBlank()) {
							builder.header("Authorization", "Bearer " + token);
						}
					})
					.build();
			log("    building client...");
			client = McpClient.sync(transport)
					.jsonSchemaValidator(new DefaultJsonSchemaValidator(om))
					.requestTimeout(Duration.ofSeconds(30))
					.build();
			log("    initialize()...");
			InitializeResult init = client.initialize();
			log("    listTools()...");
			ListToolsResult tools = client.listTools();
			log("  handshake " + base + endpoint + " -> OK (server=" + init.serverInfo().name()
					+ ", tools=" + tools.tools().size() + ")");
			tools.tools().forEach(t -> log("      tool: " + t.name()));
		} catch (Throwable t) {
			log("  handshake " + base + endpoint + " -> FAILED: " + t.getClass().getName() + ": "
					+ t.getMessage());
			Throwable cause = t.getCause();
			while (cause != null) {
				log("      caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
				cause = cause.getCause();
			}
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

	@Override
	public void stop() {
		// no-op
	}

	private static void probe(String url) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setRequestMethod("GET");
			c.setConnectTimeout(2000);
			c.setReadTimeout(2000);
			int code = c.getResponseCode();
			// 404 = servlet not registered. Any other status (e.g. 400 from a bare GET
			// against a JSON-RPC/streamable endpoint) means the MCP servlet IS present.
			String verdict = (code == 404) ? " (NOT registered)" : " (REGISTERED - responds)";
			log("  probe " + url + " -> HTTP " + code + verdict);
		} catch (IOException e) {
			log("  probe " + url + " -> UNREACHABLE (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	/**
	 * Reads {@code Activator.SESSION_AUTH_TOKEN} from the developer.mcp bundle
	 * reflectively, so we don't need that (non-exported) package on our classpath.
	 * Returns {@code null} if unavailable - fine, since internal logins bypass auth.
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

	private static boolean isWorkbenchRunning() {
		try {
			return org.eclipse.ui.PlatformUI.isWorkbenchRunning();
		} catch (Throwable t) {
			return false;
		}
	}

	private static void log(String msg) {
		System.out.println("[mcp-probe] " + msg);
	}
}

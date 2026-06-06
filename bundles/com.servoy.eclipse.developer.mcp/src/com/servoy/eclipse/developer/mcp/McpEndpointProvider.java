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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.opencode.IMcpEndpointProvider;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Implements {@link IMcpEndpointProvider} to expose all built-in MCP endpoints
 * registered by this plugin to the
 * {@code com.servoy.eclipse.opencode.mcpEndpoint}
 * extension point.
 *
 * <p>
 * The auth token is ephemeral: it is generated fresh at each Eclipse startup
 * ({@link Activator#SESSION_AUTH_TOKEN}) and never persisted to disk.
 * </p>
 *
 * <p>
 * {@link #getUrls()} blocks until the MCP server registry is initialized
 * (i.e. Tomcat has finished registering the MCP servlets), polling every 200 ms
 * with a 30-second timeout. If the timeout expires an empty list is returned
 * and
 * a warning is logged.
 * </p>
 */
public class McpEndpointProvider implements IMcpEndpointProvider {
	private static final long INIT_TIMEOUT_MS = 30_000;
	private static final long POLL_INTERVAL_MS = 200;

	@Override
	public List<String> getUrls() {
		if (!waitForRegistry()) {
			ServoyLog.logWarning(
					"McpEndpointProvider: MCP servers not initialized within " +
							INIT_TIMEOUT_MS / 1000 + "s â?? returning no URLs",
					null);
			return Collections.emptyList();
		}
		int port = ApplicationServerRegistry.get().getWebServerPort();
		List<String> urls = new ArrayList<>();
		for (Class<?> clazz : McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
			McpServer ann = clazz.getAnnotation(McpServer.class);
			if (ann != null) {
				urls.add("http://localhost:" + port +
						McpServerRegistry.MCP_PATH_PREFIX + "/" + ann.name());
			}
		}
		return Collections.unmodifiableList(urls);
	}

	@Override
	public String getAuthToken() {
		return Activator.SESSION_AUTH_TOKEN;
	}

	private boolean waitForRegistry() {
		long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			McpServerRegistry registry = McpServerRegistry.getInstance();
			if (registry != null && registry.isInitialized())
				return true;
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}
}

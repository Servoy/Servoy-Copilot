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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.tomcat.starter.ServletInstance;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;

import com.servoy.eclipse.developer.mcp.auth.BearerTokenAuthenticationFilter;
import com.servoy.eclipse.model.util.ServoyLog;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServlet;

/**
 * Manages the lifecycle of MCP server endpoints and exposes them as
 * {@link ServletInstance}s to be registered with Servoy's embedded Tomcat.
 *
 * <p>
 * Each built-in MCP server gets its own transport provider servlet mapped to
 * {@code /mcp/{serverName}/}. A bearer-token filter is applied to all
 * endpoints.
 * </p>
 *
 * <p>
 * This class is managed by E4 dependency injection. Use
 * {@link Activator#make(Class)} to obtain the singleton instance, or
 * {@link #getInstance()} for backward compatibility.
 * </p>
 */
@Creatable
@Singleton
public class McpServerRegistry {
	/**
	 * URL prefix for all MCP endpoints - distinct from the existing /mcp path
	 * used by workflows.
	 */
	public static final String MCP_PATH_PREFIX = "/dev_mcp";

	/**
	 * Named servlet wrapper classes - one per planned endpoint. Tomcat uses
	 * getClass().getSimpleName() as the wrapper name, so each registered
	 * ServletInstance must be an instance of a distinct class. These named inner
	 * classes provide that uniqueness without touching TomcatStartStop.
	 */
	public static class ServoyIdeServlet extends BearerTokenAuthenticationFilter {
		public ServoyIdeServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyCoderServlet extends BearerTokenAuthenticationFilter {
		public ServoyCoderServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyRunnerServlet extends BearerTokenAuthenticationFilter {
		public ServoyRunnerServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyContextServlet extends BearerTokenAuthenticationFilter {
		public ServoyContextServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyGitServlet extends BearerTokenAuthenticationFilter {
		public ServoyGitServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyDevServlet extends BearerTokenAuthenticationFilter {
		public ServoyDevServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class MemoryServlet extends BearerTokenAuthenticationFilter {
		public MemoryServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class TimeServlet extends BearerTokenAuthenticationFilter {
		public TimeServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyTestServlet extends BearerTokenAuthenticationFilter {
		public ServoyTestServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyWpmServlet extends BearerTokenAuthenticationFilter {
		public ServoyWpmServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyMediaServlet extends BearerTokenAuthenticationFilter {
		public ServoyMediaServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	public static class ServoyI18nServlet extends BearerTokenAuthenticationFilter {
		public ServoyI18nServlet(String token, HttpServlet delegate) {
			super(token, delegate);
		}
	}

	/** Maps server name - named servlet class to use as the Tomcat wrapper. */
	private static final Map<String, java.util.function.BiFunction<String, HttpServlet, BearerTokenAuthenticationFilter>> SERVLET_FACTORIES;
	static {
		SERVLET_FACTORIES = new HashMap<>();
		SERVLET_FACTORIES.put("servoy-ide", (t, d) -> new ServoyIdeServlet(t, d));
		SERVLET_FACTORIES.put("servoy-coder", (t, d) -> new ServoyCoderServlet(t, d));
		SERVLET_FACTORIES.put("servoy-runner", (t, d) -> new ServoyRunnerServlet(t, d));
		SERVLET_FACTORIES.put("servoy-context", (t, d) -> new ServoyContextServlet(t, d));
		SERVLET_FACTORIES.put("servoy-git", (t, d) -> new ServoyGitServlet(t, d));
		SERVLET_FACTORIES.put("servoy-dev", (t, d) -> new ServoyDevServlet(t, d));
		SERVLET_FACTORIES.put("memory", (t, d) -> new MemoryServlet(t, d));
		SERVLET_FACTORIES.put("time", (t, d) -> new TimeServlet(t, d));
		SERVLET_FACTORIES.put("servoy-test", (t, d) -> new ServoyTestServlet(t, d));
		SERVLET_FACTORIES.put("servoy-wpm", (t, d) -> new ServoyWpmServlet(t, d));
		SERVLET_FACTORIES.put("servoy-media", (t, d) -> new ServoyMediaServlet(t, d));
		SERVLET_FACTORIES.put("servoy-i18n", (t, d) -> new ServoyI18nServlet(t, d));
	}

	private static volatile McpServerRegistry instance;

	private final List<McpSyncServer> syncServers = new CopyOnWriteArrayList<>();
	private final List<ServletInstance> servletInstances = new ArrayList<>();
	private final JacksonMcpJsonMapperSupplier jsonMapperSupplier = new JacksonMcpJsonMapperSupplier();
	private volatile boolean initialized = false;

	public McpServerRegistry() {
		instance = this;
	}

	/**
	 * Returns the E4-managed singleton instance. Available after {@link McpStartup}
	 * has bootstrapped the registry.
	 */
	public static McpServerRegistry getInstance() {
		return instance;
	}

	@PostConstruct
	void init() {
		ServoyLog.logInfo("Servoy Developer MCP Server: E4 registry initialized.");
	}

	/**
	 * Returns the set of servlet instances to be registered with Tomcat.
	 * Initialises on first call.
	 */
	public synchronized Set<ServletInstance> getServletInstances() {
		if (!initialized) {
			initialize();
		}
		return Collections.unmodifiableSet(Set.copyOf(servletInstances));
	}

	/**
	 * Returns {@code true} once {@link #initialize()} has completed successfully.
	 */
	public boolean isInitialized() {
		return initialized;
	}

	private void initialize() {
		String token = Activator.SESSION_AUTH_TOKEN;

		List<Object> serverImpls = McpServerBuiltins.createServerInstances(Activator.getDefault().getEclipseContext());

		for (Object impl : serverImpls) {
			com.servoy.eclipse.developer.mcp.annotations.McpServer ann = impl.getClass()
					.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
			if (ann == null)
				continue;

			String serverName = ann.name();
			String endpointPath = MCP_PATH_PREFIX + "/" + serverName;

			HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
					.builder().jsonMapper(jsonMapperSupplier.get()).mcpEndpoint(endpointPath).build();

			McpSyncServer syncServer = McpServerFactory.getInstance().createSyncServer(impl, transport);
			syncServers.add(syncServer);

			var factory = SERVLET_FACTORIES.get(serverName);
			if (factory == null) {
				ServoyLog.logWarning("Servoy Developer MCP: no named servlet class for server '" + serverName
						+ "' - skipping endpoint " + endpointPath, null);
				continue;
			}

			BearerTokenAuthenticationFilter authFilter = factory.apply(token, transport);
			servletInstances.add(new ServletInstance(authFilter, endpointPath + "/*"));

			ServoyLog.logInfo("Servoy Developer MCP: registered endpoint " + endpointPath);
		}

		initialized = true;
		ServoyLog.logInfo("Servoy Developer MCP Server: initialized " + syncServers.size() + " server(s).");
	}

	@PostWorkbenchClose
	void shutdown() {
		syncServers.forEach(s -> {
			try {
				s.closeGracefully();
			} catch (Exception e) {
				ServoyLog.logError("Error closing MCP server", e);
			}
		});
		syncServers.clear();
		servletInstances.clear();
		initialized = false;
		ServoyLog.logInfo("Servoy Developer MCP Server: shutdown complete.");
	}
}

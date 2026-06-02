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

package com.servoy.eclipse.opencode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Builds, merges, and writes {@code opencode.json} from MCP endpoint
 * contributions.
 * <p>
 * All methods are package-private static so they can be exercised from
 * {@code com.servoy.eclipse.opencode.tests} without an OSGi runtime.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
class McpConfigWriter {

	static final String ENV_PORT = "MCP_PORT"; //$NON-NLS-1$
	static final String ENV_TOKEN = "MCP_AUTH_TOKEN"; //$NON-NLS-1$
	static final String SCHEMA_URL = "https://opencode.ai/config.json"; //$NON-NLS-1$

	private static final String EXTENSION_POINT_ID = "com.servoy.eclipse.opencode.mcpEndpoint"; //$NON-NLS-1$
	private static final String ELEMENT_ENDPOINT = "endpoint"; //$NON-NLS-1$
	private static final String ATTR_CLASS = "class"; //$NON-NLS-1$

	/**
	 * Test-only injection point. When non-{@code null}, {@link #collectProviders()}
	 * returns a copy of this list instead of querying the OSGi extension registry.
	 * Must be reset to {@code null} after each test to restore normal behaviour.
	 */
	// package-private for testing
	static volatile List<IMcpEndpointProvider> testProvidersOverride = null;

	/** Collects all registered providers via the extension point registry. */
	static List<IMcpEndpointProvider> collectProviders() {
		List<IMcpEndpointProvider> override = testProvidersOverride;
		if (override != null) {
			return new ArrayList<>(override);
		}
		List<IMcpEndpointProvider> providers = new ArrayList<>();
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			return providers;
		}
		IConfigurationElement[] elements = registry.getConfigurationElementsFor(EXTENSION_POINT_ID);
		for (IConfigurationElement element : elements) {
			if (ELEMENT_ENDPOINT.equals(element.getName())) {
				try {
					Object obj = element.createExecutableExtension(ATTR_CLASS);
					if (obj instanceof IMcpEndpointProvider provider) {
						providers.add(provider);
					} else {
						ServoyLog.logError(
								"McpConfigWriter: contributed class does not implement IMcpEndpointProvider: " + //$NON-NLS-1$
										element.getAttribute(ATTR_CLASS),
								null);
					}
				} catch (Exception e) {
					ServoyLog.logError("McpConfigWriter: failed to instantiate endpoint provider: " + //$NON-NLS-1$
							element.getAttribute(ATTR_CLASS), e);
				}
			}
		}
		return providers;
	}

	/**
	 * Extracts the port number from a URL such as
	 * {@code http://localhost:8085/mcp/eclipse-ide}.
	 *
	 * @throws IllegalArgumentException if the URL has no explicit port
	 */
	static int extractPort(String url) {
		try {
			URI uri = new URI(url);
			int port = uri.getPort();
			if (port < 0) {
				throw new IllegalArgumentException("URL has no explicit port: " + url); //$NON-NLS-1$
			}
			return port;
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid URL: " + url, e); //$NON-NLS-1$
		}
	}

	/**
	 * Derives the MCP server name from a URL by taking the last non-empty
	 * path segment. {@code ".../mcp/eclipse-ide"} → {@code "eclipse-ide"}.
	 */
	static String serverNameFromUrl(String url) {
		try {
			URI uri = new URI(url);
			String path = uri.getPath();
			if (path == null || path.isEmpty()) {
				throw new IllegalArgumentException("URL has no path: " + url); //$NON-NLS-1$
			}
			// Remove trailing slash if present
			if (path.endsWith("/")) //$NON-NLS-1$
			{
				path = path.substring(0, path.length() - 1);
			}
			int lastSlash = path.lastIndexOf('/');
			if (lastSlash < 0 || lastSlash == path.length() - 1) {
				throw new IllegalArgumentException("Cannot derive server name from URL path: " + url); //$NON-NLS-1$
			}
			return path.substring(lastSlash + 1);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid URL: " + url, e); //$NON-NLS-1$
		}
	}

	/**
	 * Builds the template URL by replacing the port with {@code {env:MCP_PORT}}.
	 * {@code "http://localhost:8085/mcp/eclipse-ide"}
	 * → {@code "http://localhost:{env:MCP_PORT}/mcp/eclipse-ide"}
	 */
	static String templateUrl(String url) {
		int port = extractPort(url);
		return url.replace(":" + port + "/", ":{env:" + ENV_PORT + "}/"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Builds the environment variable map for the given providers.
	 * Returns a map containing {@code MCP_PORT} and, if any provider returns
	 * a non-null auth token, {@code MCP_AUTH_TOKEN}.
	 *
	 * @return unmodifiable map; empty if {@code providers} is empty or all
	 *         return empty URL lists
	 */
	static Map<String, String> buildEnvVars(List<IMcpEndpointProvider> providers) {
		if (providers.isEmpty()) {
			return Collections.emptyMap();
		}

		String port = null;
		String token = null;

		for (IMcpEndpointProvider provider : providers) {
			List<String> urls = provider.getUrls();
			if (urls != null) {
				for (String url : urls) {
					if (url != null && !url.isEmpty() && port == null) {
						try {
							port = String.valueOf(extractPort(url));
						} catch (IllegalArgumentException e) {
							ServoyLog.logError("McpConfigWriter: cannot extract port from URL: " + url, e); //$NON-NLS-1$
						}
					}
				}
			}
			if (token == null && provider.getAuthToken() != null) {
				token = provider.getAuthToken();
			}
		}

		if (port == null) {
			return Collections.emptyMap();
		}

		Map<String, String> envVars = new HashMap<>();
		envVars.put(ENV_PORT, port);
		if (token != null) {
			envVars.put(ENV_TOKEN, token);
		}
		return Collections.unmodifiableMap(envVars);
	}

	/**
	 * Merges contributed endpoints into the opencode.json at {@code configFile}.
	 * Creates the file (and parent directories) if absent; merges into the
	 * existing content if present.
	 *
	 * @param providers  collected extension point contributions
	 * @param configFile target path, e.g.
	 *                   {@code ~/.servoy/opencode/opencode.json}
	 */
	static void mergeConfig(List<IMcpEndpointProvider> providers, Path configFile) throws IOException {
		if (providers.isEmpty()) {
			return;
		}

		// Collect all contributed entries: serverName -> {templateUrl, authToken}
		Map<String, String[]> contributed = new LinkedHashMap<>();
		for (IMcpEndpointProvider provider : providers) {
			List<String> urls = provider.getUrls();
			if (urls == null)
				continue;
			String authToken = provider.getAuthToken();
			for (String url : urls) {
				if (url == null || url.isEmpty())
					continue;
				try {
					String serverName = serverNameFromUrl(url);
					String tmplUrl = templateUrl(url);
					contributed.put(serverName, new String[] { tmplUrl, authToken });
				} catch (IllegalArgumentException e) {
					ServoyLog.logError("McpConfigWriter: skipping invalid URL: " + url, e); //$NON-NLS-1$
				}
			}
		}

		if (contributed.isEmpty()) {
			return;
		}

		// Determine desired auth header value (same for all providers per spec)
		String desiredAuthHeader = null;
		for (String[] entry : contributed.values()) {
			if (entry[1] != null) {
				desiredAuthHeader = "Bearer {env:" + ENV_TOKEN + "}"; //$NON-NLS-1$ //$NON-NLS-2$
				break;
			}
		}

		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(SerializationFeature.INDENT_OUTPUT);

		ObjectNode root;
		if (Files.exists(configFile)) {
			try {
				root = (ObjectNode)mapper.readTree(configFile.toFile());
			} catch (IOException e) {
				ServoyLog.logError("McpConfigWriter: cannot read existing opencode.json, will regenerate", e); //$NON-NLS-1$
				root = mapper.createObjectNode();
			}
		} else {
			root = mapper.createObjectNode();
		}

		if (!root.has("$schema")) root.put("$schema", SCHEMA_URL); //$NON-NLS-1$
		if (!root.has("mcp")) root.putObject("mcp"); //$NON-NLS-1$
		ObjectNode mcp = (ObjectNode)root.get("mcp"); //$NON-NLS-1$

		// Collect all URL values already present in the mcp section
		Set<String> existingUrls = new HashSet<>();
		mcp.fields().forEachRemaining(e -> {
			JsonNode urlNode = e.getValue().get("url"); //$NON-NLS-1$
			if (urlNode != null) existingUrls.add(urlNode.asText());
		});

		for (Map.Entry<String, String[]> entry : contributed.entrySet()) {
			String serverName = entry.getKey();
			String tmplUrl = entry.getValue()[0];
			String authToken = entry.getValue()[1];

			if (existingUrls.contains(tmplUrl)) {
				continue; // URL already present under some name, nothing to do
			}

			ObjectNode server = mapper.createObjectNode();
			server.put("type", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
			server.put("url", tmplUrl); //$NON-NLS-1$
			if (authToken != null && desiredAuthHeader != null) {
				server.putObject("headers").put("Authorization", desiredAuthHeader); //$NON-NLS-1$ //$NON-NLS-2$
			}
			mcp.set(serverName, server);
		}

		Path parent = configFile.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Files.writeString(configFile, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
	}

	/** Private constructor – static utility class. */
	private McpConfigWriter() {
	}
}

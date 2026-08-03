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
package com.servoy.eclipse.developer.mcp.auth;

import java.io.IOException;

import com.servoy.eclipse.model.util.ServoyLog;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Jakarta Servlet {@link Filter} that enforces Bearer token authentication on
 * all MCP endpoints. Wraps a delegate {@link Servlet} so it can be registered
 * as a single {@code ServletInstance} via
 * {@link org.apache.tomcat.starter.IServicesProvider}.
 *
 * <p>
 * Unlike a Tomcat {@code ValveBase}, a filter is scoped to a specific servlet
 * mapping and does NOT intercept unrelated Servoy Tomcat requests.
 * </p>
 */
public class BearerTokenAuthenticationFilter extends HttpServlet implements Filter {
	private static final long serialVersionUID = 1L;

	private final String expectedToken;
	private final HttpServlet delegate;

	/**
	 * @param expectedToken the bearer token that clients must present; if blank,
	 *                      all requests are rejected
	 * @param delegate      the servlet to forward authenticated requests to
	 */
	public BearerTokenAuthenticationFilter(String expectedToken, HttpServlet delegate) {
		this.expectedToken = expectedToken;
		this.delegate = delegate;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (!authenticate(request, response))
			return;
		try {
			delegate.service(request, response);
		} catch (Exception e) {
			if (!response.isCommitted()) {
				String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				ServoyLog.logError("MCP request processing error: " + msg, e);
				String escaped = new String(
						com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(msg));
				response.setStatus(HttpServletResponse.SC_OK);
				response.setContentType("application/json");
				response.getWriter()
						.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,"
								+ "\"message\":\"JSON parsing failed: " + escaped + ". "
								+ "Please ensure the request body is valid JSON.\"},\"id\":null}");
			}
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!authenticate((HttpServletRequest) request, (HttpServletResponse) response))
			return;
		chain.doFilter(request, response);
	}

	private boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Servoy internal developers (@servoy.com) bypass token auth entirely
		if (isServoyInternalUser())
			return true;

		String authHeader = request.getHeader("Authorization");

		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			ServoyLog.logWarning("MCP auth rejected [" + request.getRequestURI() + "]: expected 'Bearer <token>', got: "
					+ (authHeader == null ? "<no Authorization header>" : "'" + authHeader.split(" ")[0] + " ...'"),
					null);
			sendUnauthorized(response, "Missing or invalid Authorization header");
			return false;
		}

		String token = authHeader.substring(7);
		if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token)) {
			ServoyLog.logWarning("MCP auth rejected [" + request.getRequestURI() + "]: token mismatch"
					+ " (received length=" + token.length() + ", expected length="
					+ (expectedToken == null ? "null" : expectedToken.length()) + ")", null);
			sendUnauthorized(response, "Invalid token");
			return false;
		}

		return true;
	}

	private static boolean isServoyInternalUser() {
		try {
			org.eclipse.equinox.security.storage.ISecurePreferences node = org.eclipse.equinox.security.storage.SecurePreferencesFactory
					.getDefault().node(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY);
			String username = node.get(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_USERNAME, null);
			return username != null && username.toLowerCase().endsWith("@servoy.com"); //$NON-NLS-1$
		} catch (Exception e) {
			return false;
		}
	}

	private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json");
		response.getWriter().write("{\"error\": \"" + message + "\"}");
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// nothing to initialise
	}

	@Override
	public void destroy() {
		// nothing to clean up
	}
}

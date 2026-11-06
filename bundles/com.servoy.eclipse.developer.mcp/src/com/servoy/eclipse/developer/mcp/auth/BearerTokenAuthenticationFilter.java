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
import java.net.InetAddress;

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
public class BearerTokenAuthenticationFilter extends HttpServlet implements Filter
{
	private static final long serialVersionUID = 1L;

	private final String expectedToken;
	private final HttpServlet delegate;

	/**
	 * @param expectedToken the bearer token that clients must present; if blank,
	 *                      all requests are rejected
	 * @param delegate      the servlet to forward authenticated requests to
	 */
	public BearerTokenAuthenticationFilter(String expectedToken, HttpServlet delegate)
	{
		this.expectedToken = expectedToken;
		this.delegate = delegate;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
		throws ServletException, IOException
	{
		if (!authenticate(request, response))
			return;
		try
		{
			delegate.service(request, response);
		}
		catch (Exception e)
		{
			if (!response.isCommitted())
			{
				String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				ServoyLog.logError("MCP request processing error: " + msg, e);
				String escaped = new String(
					com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(msg));
				response.setStatus(HttpServletResponse.SC_OK);
				response.setContentType("application/json");
				response.getWriter()
					.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700," + "\"message\":\"JSON parsing failed: " + escaped + ". " +
						"Please ensure the request body is valid JSON.\"},\"id\":null}");
			}
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
		throws IOException, ServletException
	{
		if (!authenticate((HttpServletRequest)request, (HttpServletResponse)response))
			return;
		chain.doFilter(request, response);
	}

	@SuppressWarnings("boxing")
	private boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		// Off-box requests must look absent: deny before anything else, using only the
		// transport-level peer address (never a forwarded/host header, which a remote
		// attacker controls freely).
		if (!isLoopbackRequest(request))
		{
			ServoyLog.logWarning("MCP auth rejected [" + request.getRequestURI() + "]: non-loopback remote address '" + request.getRemoteAddr() + "'", null);
			sendNotFound(response);
			return false;
		}

		// Servoy internal developers (@servoy.com) bypass token auth entirely, independent
		// of whether an svyAI key is present (seat-less internal users / headless skilltest).
		if (isServoyInternalUser())
			return true;

		// svyAI entitlement signal: the svyAI key is set only for entitled logins. Absent or
		// blank => not entitled => the endpoint must look absent. Sourced via the shared
		// accessor (secure-store first, -D GENAI_API_KEY fallback), never a system property.
		String apiKey = com.servoy.eclipse.opencode.ProviderConfigWriter.getApiKey();
		if (apiKey == null || apiKey.isBlank())
		{
			ServoyLog.logWarning(
				"MCP auth rejected [" + request.getRequestURI() + "]: no svyAI entitlement (GENAI_API_KEY absent)",
				null);
			sendNotFound(response);
			return false;
		}

		String authHeader = request.getHeader("Authorization");

		if (authHeader == null || !authHeader.startsWith("Bearer "))
		{
			ServoyLog.logWarning(
				"MCP auth rejected [" + request.getRequestURI() + "]: expected 'Bearer <token>', got: " +
					(authHeader == null ? "<no Authorization header>" : "'" + authHeader.split(" ")[0] + " ...'"),
				null);
			sendNotFound(response);
			return false;
		}

		String token = authHeader.substring(7);
		if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token))
		{
			ServoyLog.logWarning("MCP auth rejected [" + request.getRequestURI() + "]: token mismatch" + " (received length=" + token.length() +
				", expected length=" + (expectedToken == null ? "null" : expectedToken.length()) + ")", null);
			sendNotFound(response);
			return false;
		}

		return true;
	}

	/**
	 * Returns whether the request's transport-level remote address is a loopback
	 * address ({@code 127.0.0.1}, {@code ::1}, {@code 0:0:0:0:0:0:0:1}). Uses ONLY
	 * {@link HttpServletRequest#getRemoteAddr()} (the actual TCP peer) and never any
	 * request header. Fails closed: any exception, parse failure, or null/blank
	 * address is treated as non-loopback.
	 */
	private boolean isLoopbackRequest(HttpServletRequest request)
	{
		try
		{
			String remoteAddr = request.getRemoteAddr();
			// Fail closed: a null/blank address must NOT be treated as loopback.
			// InetAddress.getByName(null)/("") would otherwise resolve to the loopback
			// address and wrongly pass the gate.
			if (remoteAddr == null || remoteAddr.isBlank())
				return false;
			return InetAddress.getByName(remoteAddr).isLoopbackAddress();
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Package-visible identity seam: whether the logged-in Developer user is a
	 * Servoy-internal ({@code @servoy.com}) developer. The default reads the login
	 * username from secure storage; a test may override it to fake the decision
	 * without touching {@code ISecurePreferences}.
	 */
	protected boolean isServoyInternalUser()
	{
		try
		{
			org.eclipse.equinox.security.storage.ISecurePreferences node = org.eclipse.equinox.security.storage.SecurePreferencesFactory
				.getDefault().node(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY);
			String username = node.get(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_USERNAME, null);
			return username != null && username.toLowerCase().endsWith("@servoy.com"); //$NON-NLS-1$
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Deny by emitting a bare container 404 so the endpoint is indistinguishable
	 * from a path that was never mapped: no {@code WWW-Authenticate}/{@code Allow}
	 * header, no JSON body.
	 */
	private void sendNotFound(HttpServletResponse response) throws IOException
	{
		response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException
	{
		// nothing to initialise
	}

	@Override
	public void destroy()
	{
		// nothing to clean up
	}
}

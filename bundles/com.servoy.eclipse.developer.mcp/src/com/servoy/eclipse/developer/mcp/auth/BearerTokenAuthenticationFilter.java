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
import java.net.URI;

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

		// DNS-rebinding guard: a malicious web page can make a browser issue a request to
		// 127.0.0.1 (so the loopback peer check above passes), but the browser still sends
		// the attacker's site as the Origin. Legitimate MCP clients (CLI tools, the embedded
		// browser's same-origin calls) either send no Origin or a loopback one. If an Origin
		// IS present and does not resolve to loopback, the endpoint must look absent.
		if (!isOriginLocalOrAbsent(request))
		{
			ServoyLog.logWarning("MCP auth rejected [" + request.getRequestURI() + "]: non-local Origin '" + request.getHeader("Origin") + "'", null);
			sendNotFound(response);
			return false;
		}

		// Servoy internal developers (@servoy.com) bypass BOTH the svyAI entitlement check
		// and the bearer-token check entirely. This is on purpose (commit 1c4b7bc): internal
		// devs connect from external MCP tools that have no way to obtain the rotating bearer
		// token (it lives only in the env of the opencode process Developer starts). The
		// reviewer's fake-offline-login concern is closed inside isServoyInternalUser(), which
		// now requires a server-verified login token in addition to the @servoy.com username,
		// so a username saved while Cloud was unreachable does NOT grant this bypass.
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
	 * DNS-rebinding guard. Returns {@code true} when the request carries no
	 * {@code Origin} header (the normal case for non-browser MCP clients and the
	 * embedded browser's same-origin calls) or an {@code Origin} whose host is a
	 * loopback address. Returns {@code false} for any present {@code Origin} that
	 * is not loopback.
	 *
	 * <p>
	 * The loopback peer check ({@link #isLoopbackRequest}) is not enough on its
	 * own: a malicious web page can use DNS rebinding so the browser connects to
	 * {@code 127.0.0.1} (passing the peer check) while the page itself is served
	 * from the attacker's domain. The browser still reports that domain as the
	 * {@code Origin}, so an {@code Origin} that is present and not loopback is the
	 * tell-tale of such a cross-site request. Fails closed: an unparsable
	 * {@code Origin} is treated as non-local.
	 * </p>
	 */
	private boolean isOriginLocalOrAbsent(HttpServletRequest request)
	{
		String origin = request.getHeader("Origin"); //$NON-NLS-1$
		// No Origin: legitimate non-browser client (CLI, embedded tooling). Allow.
		if (origin == null || origin.isBlank())
			return true;
		try
		{
			String host = URI.create(origin).getHost();
			// Fail closed: an Origin we cannot resolve a host from is not local.
			if (host == null || host.isBlank())
				return false;
			return InetAddress.getByName(host).isLoopbackAddress();
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Package-visible identity seam: whether the logged-in Developer user is a
	 * verified Servoy-internal ({@code @servoy.com}) developer. The default reads
	 * the login username <em>and</em> the server-issued login token from secure
	 * storage; a test may override it to fake the decision without touching
	 * {@code ISecurePreferences}.
	 *
	 * <p>
	 * A matching {@code @servoy.com} username alone is NOT sufficient: the login
	 * dialog saves the username on a first login even when Servoy Cloud is
	 * unreachable (the relaxed offline-login path), so a user could block the Cloud
	 * host, type any {@code x@servoy.com} address and otherwise get the internal
	 * bypass with no server verification. Requiring a non-blank
	 * {@code SERVOY_LOGIN_TOKEN} — which is only ever stored after an HTTP 200 from
	 * Cloud — ties the bypass to a server-verified login, so a faked offline login
	 * does not qualify.
	 * </p>
	 */
	protected boolean isServoyInternalUser()
	{
		try
		{
			org.eclipse.equinox.security.storage.ISecurePreferences node = org.eclipse.equinox.security.storage.SecurePreferencesFactory
				.getDefault().node(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY);
			String username = node.get(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_USERNAME, null);
			if (username == null || !username.toLowerCase().endsWith("@servoy.com")) //$NON-NLS-1$
				return false;
			// Require a server-verified login: the token is only stored after a Cloud
			// HTTP 200, so a relaxed offline login (username saved, Cloud unreachable)
			// does NOT grant the internal bypass.
			String loginToken = node.get(com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.SERVOY_LOGIN_TOKEN, null);
			return loginToken != null && !loginToken.isBlank();
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

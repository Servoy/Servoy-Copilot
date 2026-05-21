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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JUnit 4 tests for {@link BearerTokenAuthenticationFilter}.
 */
public class BearerTokenAuthenticationFilterTest
{
	private static final String VALID_TOKEN = "test-token-12345";

	private boolean delegateCalled;

	private final HttpServlet delegate = new HttpServlet()
	{
		private static final long serialVersionUID = 1L;

		@Override
		public void service(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
		{
			delegateCalled = true;
		}
	};

	private final BearerTokenAuthenticationFilter filter =
		new BearerTokenAuthenticationFilter(VALID_TOKEN, delegate);

	@Test
	public void testValidToken_delegateIsCalled() throws Exception
	{
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		filter.service(request, response);

		assertTrue("Delegate should be called for valid token", delegateCalled);
	}

	@Test
	public void testMissingAuthHeader_returns401() throws Exception
	{
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		filter.service(request, response);

		assertEquals(401, response.getStatus());
		assertTrue(response.getBody().contains("Missing or invalid Authorization header"));
	}

	@Test
	public void testInvalidToken_returns401() throws Exception
	{
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		request.setHeader("Authorization", "Bearer wrong-token");
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		filter.service(request, response);

		assertEquals(401, response.getStatus());
		assertTrue(response.getBody().contains("Invalid token"));
	}

	@Test
	public void testNonBearerScheme_returns401() throws Exception
	{
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		request.setHeader("Authorization", "Basic dXNlcjpwYXNz");
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		filter.service(request, response);

		assertEquals(401, response.getStatus());
		assertTrue(response.getBody().contains("Missing or invalid Authorization header"));
	}

	@Test
	public void testBlankExpectedToken_rejectsAll() throws Exception
	{
		BearerTokenAuthenticationFilter blankFilter =
			new BearerTokenAuthenticationFilter("", delegate);
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		request.setHeader("Authorization", "Bearer anything");
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		blankFilter.service(request, response);

		assertEquals(401, response.getStatus());
	}

	@Test
	public void testNullExpectedToken_rejectsAll() throws Exception
	{
		BearerTokenAuthenticationFilter nullFilter =
			new BearerTokenAuthenticationFilter(null, delegate);
		delegateCalled = false;
		FakeHttpServletRequest request = new FakeHttpServletRequest();
		request.setHeader("Authorization", "Bearer anything");
		FakeHttpServletResponse response = new FakeHttpServletResponse();

		nullFilter.service(request, response);

		assertEquals(401, response.getStatus());
	}

	// --- Minimal fake implementations ---

	private static class FakeHttpServletRequest extends FakeServletRequest implements HttpServletRequest
	{
		private final Map<String, String> headers = new HashMap<>();

		void setHeader(String name, String value)
		{
			headers.put(name, value);
		}

		@Override
		public String getHeader(String name)
		{
			return headers.get(name);
		}

		@Override public String getAuthType() { return null; }
		@Override public jakarta.servlet.http.Cookie[] getCookies() { return null; }
		@Override public long getDateHeader(String name) { return 0; }
		@Override public java.util.Enumeration<String> getHeaders(String name) { return null; }
		@Override public java.util.Enumeration<String> getHeaderNames() { return null; }
		@Override public int getIntHeader(String name) { return 0; }
		@Override public String getMethod() { return "POST"; }
		@Override public String getPathInfo() { return null; }
		@Override public String getPathTranslated() { return null; }
		@Override public String getContextPath() { return ""; }
		@Override public String getQueryString() { return null; }
		@Override public String getRemoteUser() { return null; }
		@Override public boolean isUserInRole(String role) { return false; }
		@Override public java.security.Principal getUserPrincipal() { return null; }
		@Override public String getRequestedSessionId() { return null; }
		@Override public String getRequestURI() { return "/svymcp/test"; }
		@Override public StringBuffer getRequestURL() { return new StringBuffer("http://localhost:8183/svymcp/test"); }
		@Override public String getServletPath() { return "/svymcp/test"; }
		@Override public jakarta.servlet.http.HttpSession getSession(boolean create) { return null; }
		@Override public jakarta.servlet.http.HttpSession getSession() { return null; }
		@Override public String changeSessionId() { return null; }
		@Override public boolean isRequestedSessionIdValid() { return false; }
		@Override public boolean isRequestedSessionIdFromCookie() { return false; }
		@Override public boolean isRequestedSessionIdFromURL() { return false; }
		@Override public boolean authenticate(HttpServletResponse response) { return false; }
		@Override public void login(String username, String password) {}
		@Override public void logout() {}
		@Override public java.util.Collection<jakarta.servlet.http.Part> getParts() { return null; }
		@Override public jakarta.servlet.http.Part getPart(String name) { return null; }
		@Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
	}

	private static class FakeServletRequest implements jakarta.servlet.ServletRequest
	{
		@Override public Object getAttribute(String name) { return null; }
		@Override public java.util.Enumeration<String> getAttributeNames() { return null; }
		@Override public String getCharacterEncoding() { return null; }
		@Override public void setCharacterEncoding(String env) {}
		@Override public int getContentLength() { return 0; }
		@Override public long getContentLengthLong() { return 0; }
		@Override public String getContentType() { return null; }
		@Override public jakarta.servlet.ServletInputStream getInputStream() { return null; }
		@Override public String getParameter(String name) { return null; }
		@Override public java.util.Enumeration<String> getParameterNames() { return null; }
		@Override public String[] getParameterValues(String name) { return null; }
		@Override public java.util.Map<String, String[]> getParameterMap() { return null; }
		@Override public String getProtocol() { return "HTTP/1.1"; }
		@Override public String getScheme() { return "http"; }
		@Override public String getServerName() { return "localhost"; }
		@Override public int getServerPort() { return 8183; }
		@Override public java.io.BufferedReader getReader() { return null; }
		@Override public String getRemoteAddr() { return "127.0.0.1"; }
		@Override public String getRemoteHost() { return "localhost"; }
		@Override public void setAttribute(String name, Object o) {}
		@Override public void removeAttribute(String name) {}
		@Override public java.util.Locale getLocale() { return java.util.Locale.getDefault(); }
		@Override public java.util.Enumeration<java.util.Locale> getLocales() { return null; }
		@Override public boolean isSecure() { return false; }
		@Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
		@Override public int getRemotePort() { return 0; }
		@Override public String getLocalName() { return "localhost"; }
		@Override public String getLocalAddr() { return "127.0.0.1"; }
		@Override public int getLocalPort() { return 8183; }
		@Override public jakarta.servlet.ServletContext getServletContext() { return null; }
		@Override public jakarta.servlet.AsyncContext startAsync() { return null; }
		@Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) { return null; }
		@Override public boolean isAsyncStarted() { return false; }
		@Override public boolean isAsyncSupported() { return false; }
		@Override public jakarta.servlet.AsyncContext getAsyncContext() { return null; }
		@Override public jakarta.servlet.DispatcherType getDispatcherType() { return jakarta.servlet.DispatcherType.REQUEST; }
		@Override public String getRequestId() { return ""; }
		@Override public String getProtocolRequestId() { return ""; }
		@Override public jakarta.servlet.ServletConnection getServletConnection() { return null; }
	}

	private static class FakeHttpServletResponse implements HttpServletResponse
	{
		private int status = 200;
		private String contentType;
		private final StringWriter bodyWriter = new StringWriter();
		private final PrintWriter writer = new PrintWriter(bodyWriter);

		@Override
		public int getStatus()
		{
			return status;
		}

		String getBody()
		{
			writer.flush();
			return bodyWriter.toString();
		}

		@Override public void setStatus(int sc) { this.status = sc; }
		@Override public PrintWriter getWriter() { return writer; }
		@Override public void setContentType(String type) { this.contentType = type; }
		@Override public String getContentType() { return contentType; }

		@Override public void addCookie(jakarta.servlet.http.Cookie cookie) {}
		@Override public boolean containsHeader(String name) { return false; }
		@Override public String encodeURL(String url) { return url; }
		@Override public String encodeRedirectURL(String url) { return url; }
		@Override public void sendError(int sc, String msg) {}
		@Override public void sendError(int sc) {}
		@Override public void sendRedirect(String location) {}
		@Override public void sendRedirect(String location, int sc, boolean clearBuffer) {}
		@Override public void setDateHeader(String name, long date) {}
		@Override public void addDateHeader(String name, long date) {}
		@Override public void setHeader(String name, String value) {}
		@Override public void addHeader(String name, String value) {}
		@Override public void setIntHeader(String name, int value) {}
		@Override public void addIntHeader(String name, int value) {}
		@Override public java.util.Collection<String> getHeaders(String name) { return java.util.List.of(); }
		@Override public java.util.Collection<String> getHeaderNames() { return java.util.List.of(); }
		@Override public String getHeader(String name) { return null; }
		@Override public String getCharacterEncoding() { return "UTF-8"; }
		@Override public jakarta.servlet.ServletOutputStream getOutputStream() { return null; }
		@Override public void setCharacterEncoding(String charset) {}
		@Override public void setContentLength(int len) {}
		@Override public void setContentLengthLong(long len) {}
		@Override public void setBufferSize(int size) {}
		@Override public int getBufferSize() { return 0; }
		@Override public void flushBuffer() {}
		@Override public void resetBuffer() {}
		@Override public boolean isCommitted() { return false; }
		@Override public void reset() {}
		@Override public void setLocale(java.util.Locale loc) {}
		@Override public java.util.Locale getLocale() { return java.util.Locale.getDefault(); }
	}
}

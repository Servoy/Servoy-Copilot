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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService;
import com.sun.net.httpserver.HttpServer;

/**
 * Plain JUnit tests (no OSGi, no browser) for the server-error probe behind SVY-21670: when a form never renders,
 * getFormLayout asks the {@code /formtemplate} route why. A local JDK {@link HttpServer} stands in for that route.
 */
class FormLayoutServerErrorProbeTest
{
	private HttpServer server;

	@BeforeEach
	void startServer() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", exchange -> {
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.createContext("/failed", exchange -> {
			// what FormTemplateHttpEndpoint does via response.sendError(500, message): Tomcat renders the message
			// into its default error page (HTML-escaped), not into the status line
			byte[] page = ("<html><body><h1>HTTP Status 500 - Internal Server Error</h1>"
				+ "<p><b>Type</b> Status Report</p>"
				+ "<p><b>Message</b> Can&#39;t render form template: lf_cards - ClassCastException: class java.lang.String "
				+ "cannot be cast to class org.json.JSONObject (caused by ClassCastException) at FormElementHelper.addFormComponentProperties(FormElementHelper.java:1024)</p>"
				+ "<p><b>Description</b> The server encountered an unexpected condition.</p></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8");
			exchange.sendResponseHeaders(500, page.length);
			exchange.getResponseBody().write(page);
			exchange.close();
		});
		server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer()
	{
		server.stop(0);
	}

	private String url(String path)
	{
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	@Test
	@DisplayName("SVY-21670: an HTTP 500 from the render route is reported, not swallowed into a timeout")
	void serverError_isReported()
	{
		String error = FormLayoutInspectionService.probeServerError(url("/failed"));

		assertNotNull(error, "a 500 from the route must be reported");
		assertTrue(error.startsWith("HTTP 500"), "the status is named: " + error);
		// the cause from the error page body must reach the caller, HTML entities decoded
		assertTrue(error.contains("Can't render form template: lf_cards"), "the form is named: " + error);
		assertTrue(error.contains("ClassCastException: class java.lang.String cannot be cast to class org.json.JSONObject"),
			"the exception and its message are included: " + error);
		assertFalse(error.contains("<"), "no HTML leaks into the message: " + error);
	}

	@Test
	@DisplayName("SVY-21670: the message is extracted from a Tomcat error page, and a page without one yields nothing")
	void extractErrorMessage_readsTomcatPage()
	{
		assertEquals("a &b <c>", FormLayoutInspectionService.extractErrorMessage("<p><b>Message</b> a &amp;b &lt;c&gt;</p>"));
		assertEquals("", FormLayoutInspectionService.extractErrorMessage("<html>nothing useful</html>"));
		assertEquals("", FormLayoutInspectionService.extractErrorMessage(null));
	}

	@Test
	@DisplayName("SVY-21670: an HTTP 404 (form not found by the route) is reported too")
	void notFound_isReported()
	{
		assertTrue(FormLayoutInspectionService.probeServerError(url("/missing")).startsWith("HTTP 404"));
	}

	@Test
	@DisplayName("SVY-21670: a normal 200 answer is not an error, so a genuinely slow render keeps the timeout message")
	void success_isNotAnError()
	{
		assertNull(FormLayoutInspectionService.probeServerError(url("/ok")));
	}

	@Test
	@DisplayName("SVY-21670: an unreachable server yields no error text instead of an exception")
	void unreachable_returnsNull()
	{
		int closedPort = server.getAddress().getPort();
		server.stop(0);

		assertNull(FormLayoutInspectionService.probeServerError("http://127.0.0.1:" + closedPort + "/x"));
		assertEquals(null, FormLayoutInspectionService.probeServerError("not a url"));
	}
}

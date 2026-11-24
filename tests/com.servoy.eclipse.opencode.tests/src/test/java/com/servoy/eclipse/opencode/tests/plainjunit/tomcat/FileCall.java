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

package com.servoy.eclipse.opencode.tests.plainjunit.tomcat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;
import com.servoy.eclipse.opencode.tomcat.ServletTestAccess;
import com.servoy.eclipse.opencode.tomcat.WorkspaceFileReader;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Drives {@link OpencodeChatServlet#service} for the {@code /file} and
 * {@code /rest_api} guard paths with dynamic-proxy request/response fakes that
 * understand headers, the {@code path} parameter(s) and a request body.
 */
public final class FileCall {
	public static final String DEFAULT_HOST = "localhost:8183";

	private FileCall() {
	}

	/** What the servlet wrote back. */
	public static final class Reply {
		public int status = HttpServletResponse.SC_OK;
		public int errorStatus = -1;
		public int contentLength = -1;
		public String contentType;
		public final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		public final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		public String body() {
			return bytes.toString(StandardCharsets.UTF_8);
		}
	}

	public static OpencodeChatServlet servlet(Supplier<String> project, WorkspaceFileReader reader) {
		return servlet(() -> 5000, () -> true, project, reader);
	}

	public static OpencodeChatServlet servlet(java.util.function.IntSupplier port, java.util.function.BooleanSupplier ready,
			Supplier<String> project, WorkspaceFileReader reader) {
		OpencodeChatServlet s = new OpencodeChatServlet(port, () -> null, ready, t -> false, project, p -> null);
		if (reader != null)
			ServletTestAccess.setFileReader(s, reader);
		return s;
	}

	/** GET {@code /file?path=<value>} with a loopback Host and no other headers. */
	public static Reply get(OpencodeChatServlet s, String path) throws Exception {
		return call(s, "GET", "/file", Map.of("Host", DEFAULT_HOST), new String[] { path }, null, null);
	}

	public static Reply call(OpencodeChatServlet s, String method, String pathInfo, Map<String, String> headers,
			String[] pathParams, String query, String body) throws Exception {
		Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		h.putAll(headers);
		byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
		Reply reply = new Reply();
		PrintWriter[] writer = new PrintWriter[1];
		HttpServletResponse resp = fakeResponse(reply, writer);
		s.service(fakeRequest(method, pathInfo, h, pathParams, query, bodyBytes), resp);
		if (writer[0] != null)
			writer[0].flush();
		return reply;
	}

	private static HttpServletRequest fakeRequest(String method, String pathInfo, Map<String, String> headers,
			String[] pathParams, String query, byte[] body) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "getPathInfo":
				return pathInfo;
			case "getMethod":
				return method;
			case "getQueryString":
				return query;
			case "getHeader":
				return headers.get(args[0]);
			case "getParameterValues":
				return "path".equals(args[0]) ? pathParams : null;
			case "getInputStream":
				return new BytesIn(body);
			case "startAsync":
				return Proxy.newProxyInstance(FileCall.class.getClassLoader(),
						new Class<?>[] { jakarta.servlet.AsyncContext.class },
						(p2, m2, a2) -> defaultValue(m2.getReturnType()));
			case "getHeaderNames":
				return Collections.enumeration(headers.keySet());
			case "getHeaders":
				String v = headers.get(args[0]);
				return Collections.enumeration(v == null ? java.util.List.of() : java.util.List.of(v));
			default:
				return defaultValue(m.getReturnType());
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(FileCall.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, h);
	}

	private static HttpServletResponse fakeResponse(Reply reply, PrintWriter[] writer) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "setStatus":
				reply.status = ((Integer) args[0]).intValue();
				return null;
			case "getStatus":
				return Integer.valueOf(reply.status);
			case "sendError":
				reply.errorStatus = ((Integer) args[0]).intValue();
				reply.status = reply.errorStatus;
				return null;
			case "setContentType":
				reply.contentType = (String) args[0];
				return null;
			case "getContentType":
				return reply.contentType;
			case "setContentLength":
				reply.contentLength = ((Integer) args[0]).intValue();
				return null;
			case "setHeader":
				reply.headers.put((String) args[0], (String) args[1]);
				return null;
			case "getOutputStream":
				return new BytesOut(reply.bytes);
			case "getWriter":
				if (writer[0] == null)
					writer[0] = new PrintWriter(new OutputStreamWriter(reply.bytes, StandardCharsets.UTF_8));
				return writer[0];
			default:
				return defaultValue(m.getReturnType());
			}
		};
		return (HttpServletResponse) Proxy.newProxyInstance(FileCall.class.getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, h);
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class)
			return Boolean.FALSE;
		if (type == int.class)
			return Integer.valueOf(0);
		if (type == long.class)
			return Long.valueOf(0);
		return null;
	}

	private static final class BytesIn extends ServletInputStream {
		private final java.io.ByteArrayInputStream in;

		BytesIn(byte[] data) {
			this.in = new java.io.ByteArrayInputStream(data);
		}

		@Override
		public int read() {
			return in.read();
		}

		@Override
		public boolean isFinished() {
			return in.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class BytesOut extends ServletOutputStream {
		private final ByteArrayOutputStream out;

		BytesOut(ByteArrayOutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) {
			out.write(b);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
			throw new UnsupportedOperationException();
		}
	}
}

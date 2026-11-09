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

package com.servoy.eclipse.opencode.tests.plainjunit.integration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Calls {@link OpencodeChatServlet#service} the way the chat UI's HTTP requests
 * reach it, without Tomcat: dynamic-proxy fakes of the request and response
 * (the pattern of {@code OpencodeChatServletGuardTest}), carrying a JSON body
 * and capturing status and body. Only for the plain request/response API paths;
 * the {@code /rest_api/event} SSE stream needs an async container and is not
 * covered.
 *
 * @author mvid
 * @since 2026.12
 */
final class ServletCall {
	private ServletCall() {
	}

	/** What the servlet wrote back. */
	static final class Reply {
		int status = HttpServletResponse.SC_OK;
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		String body() {
			return bytes.toString(StandardCharsets.UTF_8);
		}
	}

	/**
	 * @param pathInfo the path under the servlet mount, e.g.
	 *                 {@code /rest_api/session}
	 * @param body     the JSON request body, or {@code null} for none
	 */
	static Reply call(OpencodeChatServlet servlet, String method, String pathInfo, String body) throws Exception {
		byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
		Reply reply = new Reply();
		servlet.service(fakeRequest(method, pathInfo, bodyBytes), fakeResponse(reply));
		return reply;
	}

	private static HttpServletRequest fakeRequest(String method, String pathInfo, byte[] body) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "getPathInfo":
				return pathInfo;
			case "getMethod":
				return method;
			case "getInputStream":
				return new BytesInputStream(body);
			case "getHeaderNames":
				return Collections.enumeration(List.of("Content-Type"));
			case "getHeaders":
				return Collections.enumeration(List.of("application/json"));
			default:
				return defaultValue(m.getReturnType());
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(ServletCall.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, h);
	}

	private static HttpServletResponse fakeResponse(Reply reply) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "setStatus":
				reply.status = ((Integer) args[0]).intValue();
				return null;
			case "getStatus":
				return Integer.valueOf(reply.status);
			case "getOutputStream":
				return new BytesOutputStream(reply.bytes);
			default:
				return defaultValue(m.getReturnType());
			}
		};
		return (HttpServletResponse) Proxy.newProxyInstance(ServletCall.class.getClassLoader(),
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

	private static final class BytesInputStream extends ServletInputStream {
		private final ByteArrayInputStream in;

		BytesInputStream(byte[] data) {
			this.in = new ByteArrayInputStream(data);
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

	private static final class BytesOutputStream extends ServletOutputStream {
		private final ByteArrayOutputStream out;

		BytesOutputStream(ByteArrayOutputStream out) {
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

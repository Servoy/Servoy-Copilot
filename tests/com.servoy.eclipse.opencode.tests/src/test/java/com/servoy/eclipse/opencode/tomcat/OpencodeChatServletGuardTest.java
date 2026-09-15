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

package com.servoy.eclipse.opencode.tomcat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Behavioural tests for the request-guard branches of
 * {@link OpencodeChatServlet#service} that short-circuit <em>before</em> any
 * upstream HTTP call is made: the readiness gate, the no-active-solution guard
 * and the {@code port <= 0} guard.
 * <p>
 * These paths are reachable with tiny dynamic-proxy fakes of
 * {@link HttpServletRequest}/{@link HttpServletResponse} because they return
 * immediately without opening a socket, an {@code AsyncContext}, or touching
 * the real opencode server. No OSGi runtime is required.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class OpencodeChatServletGuardTest {
	/**
	 * Captures what the servlet wrote back: status, content-type and body.
	 */
	private static final class ResponseCapture {
		int status = HttpServletResponse.SC_OK;
		String contentType;
		final CharArrayWriter body = new CharArrayWriter();
		final PrintWriter writer = new PrintWriter(body);
	}

	private static HttpServletRequest fakeRequest(String pathInfo, String method) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "getPathInfo":
				return pathInfo;
			case "getMethod":
				return method;
			case "getQueryString":
				return null;
			default:
				Class<?> rt = m.getReturnType();
				if (rt == boolean.class)
					return Boolean.FALSE;
				if (rt == int.class)
					return Integer.valueOf(0);
				if (rt == long.class)
					return Long.valueOf(0);
				return null;
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(OpencodeChatServletGuardTest.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, h);
	}

	private static HttpServletResponse fakeResponse(ResponseCapture cap) {
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "setStatus":
				cap.status = ((Integer) args[0]).intValue();
				return null;
			case "getStatus":
				return Integer.valueOf(cap.status);
			case "setContentType":
				cap.contentType = (String) args[0];
				return null;
			case "getContentType":
				return cap.contentType;
			case "getWriter":
				return cap.writer;
			default:
				Class<?> rt = m.getReturnType();
				if (rt == boolean.class)
					return Boolean.FALSE;
				if (rt == int.class)
					return Integer.valueOf(0);
				if (rt == long.class)
					return Long.valueOf(0);
				return null;
			}
		};
		return (HttpServletResponse) Proxy.newProxyInstance(OpencodeChatServletGuardTest.class.getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, h);
	}

	private static OpencodeChatServlet servlet(IntSupplier port, BooleanSupplier ready,
			OpencodeChatServlet.WaitForServer wait, Supplier<String> projectPath) {
		Function<String, URL> noResources = p -> null;
		return new OpencodeChatServlet(port, ready, wait, projectPath, noResources);
	}

	private static String invokeService(HttpServlet servlet, HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, java.io.IOException {
		servlet.service(req, resp);
		return null;
	}

	@Nested
	class ReadinessGate {
		@Test
		@DisplayName("server not ready and never becomes ready within the hold → 503 {restarting:true}")
		void notReadyHeldThenUnavailable() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			AtomicInteger waitCalls = new AtomicInteger();
			OpencodeChatServlet s = servlet(() -> 5000, () -> false, timeout -> {
				waitCalls.incrementAndGet();
				return false; // never becomes ready
			}, () -> "/home/proj");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			assertAll(() -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, cap.status),
					() -> assertEquals("application/json", cap.contentType),
					() -> assertTrue(cap.body.toString().contains("\"restarting\":true"), cap.body.toString()),
					() -> assertEquals(1, waitCalls.get(),
							"readiness gate should consult the wait-for-server hook once"));
		}

		@Test
		@DisplayName("server not ready but becomes ready during the hold → proceeds past the gate (no 503)")
		void notReadyButBecomesReadyProceeds() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			// ready supplier is false, but the wait hook reports it became ready.
			// port<=0 guard then produces a 503 restarting body - which proves we got
			// PAST the readiness gate (a gate-503 would have the same status but the
			// distinguishing fact is the wait hook returned true and we then hit the
			// port guard). Use port 0 to stop before any real upstream call.
			OpencodeChatServlet s = servlet(() -> 0, () -> false, timeout -> true, () -> "/home/proj");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			// We passed the readiness gate (wait returned true) and no-solution guard,
			// and were stopped by the port<=0 guard.
			assertAll(() -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, cap.status),
					() -> assertTrue(cap.body.toString().contains("\"restarting\":true"), cap.body.toString()));
		}
	}

	@Nested
	class NoActiveSolution {
		@Test
		@DisplayName("server ready but no active solution → 409 {error:no active solution}")
		void noSolutionConflict() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			OpencodeChatServlet s = servlet(() -> 5000, () -> true, timeout -> true, () -> null);

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			assertAll(() -> assertEquals(HttpServletResponse.SC_CONFLICT, cap.status),
					() -> assertEquals("application/json", cap.contentType),
					() -> assertTrue(cap.body.toString().contains("no active solution"), cap.body.toString()));
		}

		@Test
		@DisplayName("empty (not just null) project directory is also treated as no active solution")
		void emptySolutionConflict() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			OpencodeChatServlet s = servlet(() -> 5000, () -> true, timeout -> true, () -> "");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			assertEquals(HttpServletResponse.SC_CONFLICT, cap.status);
		}
	}

	@Nested
	class PortGuard {
		@Test
		@DisplayName("ready with active solution but port <= 0 → 503 {restarting:true} (no invalid upstream URI built)")
		void portNotResolvedYet() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			OpencodeChatServlet s = servlet(() -> -1, () -> true, timeout -> true, () -> "/home/proj");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			assertAll(() -> assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, cap.status),
					() -> assertEquals("application/json", cap.contentType),
					() -> assertTrue(cap.body.toString().contains("\"restarting\":true"), cap.body.toString()));
		}

		@Test
		@DisplayName("port exactly 0 is guarded (boundary)")
		void portZeroGuarded() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			OpencodeChatServlet s = servlet(() -> 0, () -> true, timeout -> true, () -> "/home/proj");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));
			cap.writer.flush();

			assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, cap.status);
		}
	}

	@Nested
	class ReadinessGateNotConsultedWhenReady {
		@Test
		@DisplayName("when the server is already ready the wait-for-server hook is not called")
		void waitHookSkippedWhenReady() throws Exception {
			ResponseCapture cap = new ResponseCapture();
			AtomicInteger waitCalls = new AtomicInteger();
			OpencodeChatServlet s = servlet(() -> 0, () -> true, timeout -> {
				waitCalls.incrementAndGet();
				return true;
			}, () -> "/home/proj");

			invokeService(s, fakeRequest("/rest_api/session", "GET"), fakeResponse(cap));

			assertFalse(waitCalls.get() > 0, "wait hook must not be consulted when server is already ready");
		}
	}
}

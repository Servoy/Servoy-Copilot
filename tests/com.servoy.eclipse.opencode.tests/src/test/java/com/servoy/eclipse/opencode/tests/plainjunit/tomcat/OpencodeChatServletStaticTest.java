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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Behavioural tests for the static-asset serving branch of
 * {@link OpencodeChatServlet#service} ({@code GET}/{@code HEAD} of the bundled
 * Angular app): the method guard, the hashed-asset / no-cache cache policy, the
 * SPA fallback to {@code index.html}, the {@code 404} when nothing (not even
 * index) resolves, and the byte-exact stream copy.
 * <p>
 * The servlet resolves resources through an injected
 * {@code Function<String,URL>}; the tests point that at real files on disk in a
 * temp "dist" folder, so no OSGi bundle or Tomcat container is involved. The
 * HTTP request/response are tiny dynamic-proxy fakes, and the response output
 * stream is a {@link ByteArrayOutputStream} wrapped in a minimal
 * {@link ServletOutputStream}.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class OpencodeChatServletStaticTest {
	private Path distRoot;

	@BeforeEach
	void createDist() throws IOException {
		distRoot = Files.createTempDirectory("opencode-dist-");
	}

	@AfterEach
	void cleanupDist() throws IOException {
		if (distRoot != null) {
			Files.walk(distRoot).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best effort temp cleanup
				}
			});
		}
	}

	/** Writes a file under {@code /webui-dist/<rel>} and returns its path. */
	private File writeAsset(String rel, byte[] content) throws IOException {
		// The servlet asks for "/webui-dist/<asset>"; mirror that layout under the
		// temp root so the resolver can map the request path to a real file.
		File f = new File(distRoot.toFile(), "webui-dist" + rel);
		f.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(content);
		}
		return f;
	}

	/** A resolver that maps "/webui-dist/..." bundle paths onto the temp dist root. */
	private Function<String, URL> fileResolver() {
		return bundlePath -> {
			File f = new File(distRoot.toFile(), bundlePath.startsWith("/") ? bundlePath.substring(1) : bundlePath);
			if (!f.isFile())
				return null;
			try {
				return f.toURI().toURL();
			} catch (Exception e) {
				return null;
			}
		};
	}

	private OpencodeChatServlet servlet(Function<String, URL> resolver) {
		IntSupplier port = () -> 5000;
		Supplier<String> noPassword = () -> null;
		BooleanSupplier ready = () -> true;
		OpencodeChatServlet.WaitForServer wait = timeout -> true;
		Supplier<String> projectPath = () -> "/home/proj";
		return new OpencodeChatServlet(port, noPassword, ready, wait, projectPath, resolver);
	}

	// ----- request/response fakes -------------------------------------------

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
		return (HttpServletRequest) Proxy.newProxyInstance(OpencodeChatServletStaticTest.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, h);
	}

	/** Captures status, headers, content-type, content-length and body bytes. */
	private static final class ResponseCapture {
		int status = HttpServletResponse.SC_OK;
		int sendErrorStatus = -1;
		String contentType;
		int contentLength = -1;
		final Map<String, String> headers = new HashMap<>();
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
	}

	private static ServletOutputStream servletOut(ByteArrayOutputStream sink) {
		return new ServletOutputStream() {
			@Override
			public void write(int b) {
				sink.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				sink.write(b, off, len);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
				// not used in these tests
			}
		};
	}

	private static HttpServletResponse fakeResponse(ResponseCapture cap) {
		ServletOutputStream out = servletOut(cap.body);
		InvocationHandler h = (proxy, m, args) -> {
			switch (m.getName()) {
			case "setStatus":
				cap.status = ((Integer) args[0]).intValue();
				return null;
			case "getStatus":
				return Integer.valueOf(cap.status);
			case "sendError":
				cap.sendErrorStatus = ((Integer) args[0]).intValue();
				cap.status = cap.sendErrorStatus;
				return null;
			case "setContentType":
				cap.contentType = (String) args[0];
				return null;
			case "getContentType":
				return cap.contentType;
			case "setContentLength":
				cap.contentLength = ((Integer) args[0]).intValue();
				return null;
			case "setHeader":
				cap.headers.put((String) args[0], (String) args[1]);
				return null;
			case "getHeader":
				return cap.headers.get(args[0]);
			case "getOutputStream":
				return out;
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
		return (HttpServletResponse) Proxy.newProxyInstance(OpencodeChatServletStaticTest.class.getClassLoader(),
				new Class<?>[] { HttpServletResponse.class }, h);
	}

	private static void service(OpencodeChatServlet s, HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		// HttpServlet#service(HttpServletRequest,HttpServletResponse) is protected;
		// invoke it reflectively from this package-mate test and unwrap the real
		// cause so the servlet's own ServletException/IOException surface.
		Method m;
		try {
			m = jakarta.servlet.http.HttpServlet.class.getDeclaredMethod("service", HttpServletRequest.class,
					HttpServletResponse.class);
			m.setAccessible(true);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		try {
			m.invoke(s, req, resp);
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable c = e.getCause();
			if (c instanceof ServletException se)
				throw se;
			if (c instanceof IOException ioe)
				throw ioe;
			if (c instanceof RuntimeException re)
				throw re;
			if (c instanceof Error err)
				throw err;
			throw new RuntimeException(c);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	// ----- tests ------------------------------------------------------------

	@Nested
	class MethodGuard {
		@Test
		@DisplayName("POST to a static path is 405 Method Not Allowed")
		void postIsMethodNotAllowed() throws Exception {
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/index.html", "POST"), fakeResponse(cap));
			assertEquals(HttpServletResponse.SC_METHOD_NOT_ALLOWED, cap.sendErrorStatus);
		}

		@Test
		@DisplayName("HEAD is allowed (not 405) and still resolves the asset")
		void headIsAllowed() throws Exception {
			writeAsset("/index.html", "<html></html>".getBytes(StandardCharsets.UTF_8));
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/", "HEAD"), fakeResponse(cap));
			assertAll(() -> assertEquals(-1, cap.sendErrorStatus, "HEAD must not be rejected as 405"),
					() -> assertEquals(HttpServletResponse.SC_OK, cap.status));
		}
	}

	@Nested
	class AssetServing {
		@Test
		@DisplayName("root path serves index.html byte-for-byte with no-cache")
		void rootServesIndex() throws Exception {
			byte[] html = "<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8);
			writeAsset("/index.html", html);
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/", "GET"), fakeResponse(cap));
			assertAll(() -> assertArrayEquals(html, cap.body.toByteArray(), "index body must be copied verbatim"),
					() -> assertEquals("no-cache", cap.headers.get("Cache-Control"),
							"index.html must never be long-cached"),
					() -> assertEquals(html.length, cap.contentLength));
		}

		@Test
		@DisplayName("a content-hashed asset is served with the immutable long-cache header")
		void hashedAssetLongCached() throws Exception {
			byte[] js = "console.log(1)".getBytes(StandardCharsets.UTF_8);
			writeAsset("/main-ABCD1234.js", js);
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/main-ABCD1234.js", "GET"), fakeResponse(cap));
			assertAll(() -> assertArrayEquals(js, cap.body.toByteArray()),
					() -> assertEquals("public, max-age=31536000, immutable", cap.headers.get("Cache-Control"),
							"a hashed asset should be immutably cached"));
		}

		@Test
		@DisplayName("a non-hashed asset is served with no-cache")
		void nonHashedAssetNoCache() throws Exception {
			writeAsset("/favicon.ico", new byte[] { 1, 2, 3, 4 });
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/favicon.ico", "GET"), fakeResponse(cap));
			assertEquals("no-cache", cap.headers.get("Cache-Control"));
		}
	}

	@Nested
	class SpaFallback {
		@Test
		@DisplayName("an unknown deep-link path falls back to index.html (SPA routing)")
		void deepLinkFallsBackToIndex() throws Exception {
			byte[] html = "<html>app</html>".getBytes(StandardCharsets.UTF_8);
			writeAsset("/index.html", html);
			// no file for /session/abc - must fall back to index.html
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/session/abc", "GET"), fakeResponse(cap));
			assertAll(() -> assertArrayEquals(html, cap.body.toByteArray(), "SPA fallback must serve index.html"),
					() -> assertEquals("no-cache", cap.headers.get("Cache-Control"),
							"the SPA fallback is index.html, which must not be long-cached even for a hashed-looking URL"));
		}

		@Test
		@DisplayName("even a hashed-looking unknown path falls back to index.html with no-cache")
		void hashedLookingMissingFallsBack() throws Exception {
			byte[] html = "<html>app</html>".getBytes(StandardCharsets.UTF_8);
			writeAsset("/index.html", html);
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/missing-DEADBEEF12.js", "GET"), fakeResponse(cap));
			assertAll(() -> assertArrayEquals(html, cap.body.toByteArray()),
					() -> assertEquals("no-cache", cap.headers.get("Cache-Control")));
		}
	}

	@Nested
	class NotFound {
		@Test
		@DisplayName("404 when neither the asset nor index.html resolves")
		void noIndexNoAssetIs404() throws Exception {
			// empty dist: nothing resolves, not even index.html
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/anything.js", "GET"), fakeResponse(cap));
			assertEquals(HttpServletResponse.SC_NOT_FOUND, cap.sendErrorStatus);
		}

		@Test
		@DisplayName("a null resource resolver yields 404 (no resources available at all)")
		void nullResolverIs404() throws Exception {
			OpencodeChatServlet s = servlet(p -> null);
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/", "GET"), fakeResponse(cap));
			assertAll(() -> assertEquals(HttpServletResponse.SC_NOT_FOUND, cap.sendErrorStatus),
					() -> assertNull(cap.contentType, "no content-type should be set when nothing is served"));
		}
	}

	@Nested
	class TraversalNeutralized {
		@Test
		@DisplayName("a path-traversal attempt is neutralized and served from index.html, never escaping the root")
		void traversalServesIndex() throws Exception {
			byte[] html = "<html>safe</html>".getBytes(StandardCharsets.UTF_8);
			writeAsset("/index.html", html);
			OpencodeChatServlet s = servlet(fileResolver());
			ResponseCapture cap = new ResponseCapture();
			service(s, fakeRequest("/../../etc/passwd", "GET"), fakeResponse(cap));
			// normalizeAssetPath() collapses the traversal to /index.html, so the SPA
			// index is served and no file outside the dist root is ever reachable.
			assertArrayEquals(html, cap.body.toByteArray(),
					"a traversal path must resolve to index.html, not an out-of-root file");
		}
	}
}

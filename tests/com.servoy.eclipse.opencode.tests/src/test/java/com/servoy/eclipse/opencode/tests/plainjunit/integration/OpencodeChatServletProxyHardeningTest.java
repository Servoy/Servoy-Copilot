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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.tests.plainjunit.tomcat.FileCall;
import com.servoy.eclipse.opencode.tests.plainjunit.tomcat.FileCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Proves with a fake upstream (JDK {@link HttpServer} on loopback) what really
 * reaches opencode through the BFF proxy: the client can never choose the
 * directory (query, {@code x-opencode-directory}, body {@code location},
 * {@code workspace}), never supplies {@code authorization}, and blocked
 * {@code fs} paths produce no upstream request at all.
 */
class OpencodeChatServletProxyHardeningTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String BFF_DIR = "D:\\proj\\bff";
	private static final String EVIL = "C:\\Users\\victim";
	private static final String PASSWORD = "s3cret";

	/** One request as the fake upstream saw it. */
	private static final class Seen {
		String method;
		String rawPath;
		String rawQuery;
		Map<String, List<String>> headers = new HashMap<>();
		String body;

		List<String> header(String name) {
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name))
					return e.getValue();
			}
			return List.of();
		}

		List<String> queryValues(String name) {
			List<String> out = new ArrayList<>();
			if (rawQuery == null)
				return out;
			for (String pair : rawQuery.split("&")) {
				int eq = pair.indexOf('=');
				String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
				if (k.equalsIgnoreCase(name))
					out.add(URLDecoder.decode(eq >= 0 ? pair.substring(eq + 1) : "", StandardCharsets.UTF_8));
			}
			return out;
		}
	}

	private HttpServer upstream;
	private final List<Seen> seen = new CopyOnWriteArrayList<>();
	private OpencodeChatServlet servlet;

	@BeforeEach
	void startUpstream() throws IOException {
		upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		upstream.createContext("/", this::handle);
		upstream.start();
		servlet = new OpencodeChatServlet(() -> upstream.getAddress().getPort(), () -> PASSWORD, () -> true, t -> true,
				() -> BFF_DIR, p -> null);
	}

	@AfterEach
	void stopUpstream() {
		upstream.stop(0);
	}

	private void handle(HttpExchange ex) throws IOException {
		Seen s = new Seen();
		s.method = ex.getRequestMethod();
		s.rawPath = ex.getRequestURI().getRawPath();
		s.rawQuery = ex.getRequestURI().getRawQuery();
		ex.getRequestHeaders().forEach((k, v) -> s.headers.put(k, new ArrayList<>(v)));
		s.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		seen.add(s);
		byte[] out;
		if (s.rawPath.equals("/api/event")) {
			ex.getResponseHeaders().add("Content-Type", "text/event-stream");
			out = "data: {}\n\n".getBytes(StandardCharsets.UTF_8);
		} else {
			ex.getResponseHeaders().add("Content-Type", "application/json");
			out = "{}".getBytes(StandardCharsets.UTF_8);
		}
		ex.sendResponseHeaders(200, out.length);
		ex.getResponseBody().write(out);
		ex.close();
	}

	private Seen only() {
		assertEquals(1, seen.size(), "expected exactly one upstream request, saw " + seen.size());
		return seen.get(0);
	}

	private static String basic() {
		return "Basic " + Base64.getEncoder().encodeToString(("opencode:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
	}

	private Reply call(String method, String pathInfo, Map<String, String> headers, String query, String body)
			throws Exception {
		return FileCall.call(servlet, method, pathInfo, headers, null, query, body);
	}

	private static Map<String, String> evilHeaders() {
		Map<String, String> h = new HashMap<>();
		h.put("x-opencode-directory", EVIL);
		h.put("X-Opencode-Workspace", "wrk_evil");
		h.put("Authorization", "Basic ZXZpbDpldmls");
		h.put("Cookie", "sid=evil");
		h.put("Origin", "http://evil.example");
		h.put("Content-Type", "application/json");
		return h;
	}

	private void assertBffOnly(Seen s) {
		List<String> dirs = s.queryValues("directory");
		assertAll(() -> assertEquals(List.of(BFF_DIR), dirs, "directory query: " + s.rawQuery),
				() -> assertTrue(s.queryValues("workspace").isEmpty(), "workspace query: " + s.rawQuery),
				() -> assertEquals(List.of(BFF_DIR), s.header("x-opencode-directory")),
				() -> assertEquals(List.of(basic()), s.header("authorization")),
				() -> assertTrue(s.header("x-opencode-workspace").isEmpty()),
				() -> assertTrue(s.header("cookie").isEmpty()), () -> assertTrue(s.header("origin").isEmpty()),
				() -> assertFalse(String.valueOf(s.rawQuery).contains("victim")),
				() -> assertFalse(String.valueOf(s.rawQuery).contains("evil")));
	}

	@Test
	@DisplayName("GET: client directory/workspace query, x-opencode-directory, authorization, cookie and origin never reach upstream")
	void getOverwritten() throws Exception {
		Reply r = call("GET", "/rest_api/session", evilHeaders(),
				"directory=C%3A%5CUsers%5Cvictim&Directory=x&workspace=wrk_evil&limit=5&director%79=y", null);
		assertEquals(200, r.status);
		Seen s = only();
		assertAll(() -> assertEquals("/api/session", s.rawPath), () -> assertBffOnly(s),
				() -> assertEquals(List.of("5"), s.queryValues("limit")));
	}

	@Test
	@DisplayName("a request without any client directory still gets exactly the BFF directory and BFF authorization")
	void plainGet() throws Exception {
		call("GET", "/rest_api/session", new HashMap<>(), null, null);
		assertBffOnly(only());
	}

	@Test
	@DisplayName("POST /rest_api/session: a client body location is replaced by the BFF directory")
	void postBodyLocationOverwritten() throws Exception {
		Reply r = call("POST", "/rest_api/session", evilHeaders(), "directory=" + EVIL,
				"{\"location\":{\"directory\":\"C:\\\\Users\\\\victim\"},\"title\":\"t\"}");
		assertEquals(200, r.status);
		Seen s = only();
		JsonNode body = JSON.readTree(s.body);
		assertAll(() -> assertBffOnly(s),
				() -> assertEquals(BFF_DIR, body.path("location").path("directory").asText()),
				() -> assertEquals("t", body.path("title").asText()),
				() -> assertFalse(s.body.contains("victim"), s.body));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/rest_api/session/", "/rest_api/session//", "/rest_api//session", "/rest_api//session//",
			"/rest_api/Session" })
	@DisplayName("trailing/double-slash and case variants of POST session still get the body location overwritten")
	void slashVariantsOverwritten(String pathInfo) throws Exception {
		call("POST", pathInfo, evilHeaders(), null, "{\"location\":{\"directory\":\"" + "C:\\\\Users\\\\victim\"}}");
		Seen s = only();
		assertEquals(BFF_DIR, JSON.readTree(s.body).path("location").path("directory").asText(),
				"upstream path " + s.rawPath + " body " + s.body);
	}

	@Test
	@DisplayName("POST session.import: the body location is replaced too")
	void importOverwritten() throws Exception {
		call("POST", "/rest_api/experimental/session/import", evilHeaders(), null,
				"{\"location\":{\"directory\":\"C:\\\\Users\\\\victim\"},\"data\":1}");
		Seen s = only();
		JsonNode body = JSON.readTree(s.body);
		assertAll(() -> assertEquals("/api/experimental/session/import", s.rawPath),
				() -> assertEquals(BFF_DIR, body.path("location").path("directory").asText()),
				() -> assertEquals(1, body.path("data").asInt()));
	}

	@Test
	@DisplayName("POST with an empty body on session.create gets a fresh body with the BFF location")
	void emptyBodyGetsLocation() throws Exception {
		call("POST", "/rest_api/session", new HashMap<>(), null, null);
		assertEquals(BFF_DIR, JSON.readTree(only().body).path("location").path("directory").asText());
	}

	@Test
	@DisplayName("a body of another endpoint is forwarded untouched")
	void otherBodyUntouched() throws Exception {
		String payload = "{\"location\":{\"directory\":\"x\"}}";
		call("POST", "/rest_api/session/ses_1/prompt", new HashMap<>(), null, payload);
		Seen s = only();
		assertAll(() -> assertEquals(payload, s.body), () -> assertBffOnly(s));
	}

	@Test
	@DisplayName("the SSE stream /rest_api/event also gets only the BFF directory/authorization")
	void sseOverwritten() throws Exception {
		Map<String, String> h = evilHeaders();
		h.put("Accept", "text/event-stream");
		call("GET", "/rest_api/event", h, "directory=C%3A%5CUsers%5Cvictim&workspace=wrk_evil", null);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (seen.isEmpty() && System.nanoTime() < deadline)
			Thread.sleep(25);
		assertFalse(seen.isEmpty(), "the SSE request never reached the fake upstream");
		Seen s = seen.get(0);
		assertAll(() -> assertEquals("/api/event", s.rawPath), () -> assertBffOnly(s));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/rest_api/fs/read/x", "/rest_api/fs/list", "/rest_api/experimental/fs/write",
			"/rest_api/%2566s/read/x", "/rest_api/%66s/read/x", "/rest_api/FS/read", "/rest_api//fs//read",
			"/rest_api/session/../fs/read", "/rest_api/a\\fs", "/rest_api/./fs" })
	@DisplayName("blocked paths are 403 and the fake upstream sees NO request")
	void blockedNeverReachUpstream(String pathInfo) throws Exception {
		Reply r = call("GET", pathInfo, evilHeaders(), "directory=" + EVIL, null);
		Reply post = call("POST", pathInfo, evilHeaders(), null, "{}");
		Thread.sleep(150);
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals(403, post.status),
				() -> assertEquals("{\"error\":\"forbidden\"}", r.body()), () -> assertEquals(0, seen.size()));
	}

	@Test
	@DisplayName("a client '#' in the query cannot swallow the BFF directory (it stays one query param upstream)")
	void fragmentCannotSwallowDirectory() throws Exception {
		call("GET", "/rest_api/session", new HashMap<>(), "a=1#b=2", null);
		Seen s = only();
		assertNotNull(s.rawQuery);
		assertEquals(List.of(BFF_DIR), s.queryValues("directory"), s.rawQuery);
	}
}

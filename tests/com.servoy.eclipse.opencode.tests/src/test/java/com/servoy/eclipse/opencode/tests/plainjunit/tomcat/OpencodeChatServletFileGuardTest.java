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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.opencode.tests.plainjunit.tomcat.FileCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;
import com.servoy.eclipse.opencode.tomcat.WorkspaceFileReader;

/**
 * Request-guard and HTTP-contract tests of {@code GET /servoy_ai/file} (spec
 * 3.1.4 / section 6) and of the blocked {@code rest_api/fs/**} proxy paths,
 * driven through {@link OpencodeChatServlet#service} with dynamic-proxy
 * request/response fakes (see {@link FileCall}).
 */
class OpencodeChatServletFileGuardTest {
	private static final String JSON_UTF8 = "application/json;charset=UTF-8";

	@TempDir
	Path tmp;

	Path ws;
	WorkspaceFileReader reader;
	OpencodeChatServlet servlet;

	@BeforeEach
	void setUp() throws IOException {
		ws = Files.createDirectories(tmp.resolve("ws"));
		Files.createDirectories(ws.resolve("handoff/t"));
		Files.writeString(ws.resolve("handoff/t/spec.md"), "# \"quoted\" \\ spec\n", StandardCharsets.UTF_8);
		Files.createDirectories(ws.resolve(".git"));
		Files.writeString(ws.resolve(".git/config"), "[core]", StandardCharsets.UTF_8);
		Files.write(ws.resolve("bin.dat"), new byte[] { 1, 0, 2 });
		reader = new WorkspaceFileReader(tmp.resolve("home/.servoy"), 64);
		servlet = FileCall.servlet(() -> ws.toString(), reader);
	}

	private static Map<String, String> host(String host) {
		Map<String, String> m = new HashMap<>();
		if (host != null)
			m.put("Host", host);
		return m;
	}

	private Reply get(String path) throws Exception {
		return FileCall.get(servlet, path);
	}

	private static void assertJsonHeaders(Reply r) {
		assertAll(() -> assertEquals(JSON_UTF8, r.contentType),
				() -> assertEquals("no-store", r.headers.get("Cache-Control")),
				() -> assertEquals("nosniff", r.headers.get("X-Content-Type-Options")),
				() -> assertEquals(r.bytes.size(), r.contentLength, "Content-Length must match the body"));
	}

	// ----- methods ----------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "POST", "PUT", "DELETE", "PATCH", "OPTIONS" })
	@DisplayName("other methods on /file are 405 with 'Allow: GET, HEAD', an empty body and no supplier call")
	void methodNotAllowed(String method) throws Exception {
		AtomicInteger supplierCalls = new AtomicInteger();
		OpencodeChatServlet s = FileCall.servlet(() -> {
			supplierCalls.incrementAndGet();
			return ws.toString();
		}, reader);
		Reply r = FileCall.call(s, method, "/file", host(FileCall.DEFAULT_HOST), new String[] { "x" }, null, "body");
		assertAll(() -> assertEquals(405, r.status), () -> assertEquals("GET, HEAD", r.headers.get("Allow")),
				() -> assertEquals(0, r.bytes.size()), () -> assertEquals(0, supplierCalls.get()));
	}

	@Test
	@DisplayName("HEAD answers like GET (status and headers) with an empty body")
	void headLikeGet() throws Exception {
		Reply g = get("handoff/t/spec.md");
		Reply h = FileCall.call(servlet, "HEAD", "/file", host(FileCall.DEFAULT_HOST),
				new String[] { "handoff/t/spec.md" }, null, null);
		assertAll(() -> assertEquals(200, g.status), () -> assertEquals(g.status, h.status),
				() -> assertEquals(g.headers, h.headers), () -> assertEquals(g.contentLength, h.contentLength),
				() -> assertEquals(0, h.bytes.size()), () -> assertTrue(g.bytes.size() > 0));
	}

	@Test
	@DisplayName("HEAD runs the full validation: a refused path is 403 with no body")
	void headValidates() throws Exception {
		Reply h = FileCall.call(servlet, "HEAD", "/file", host(FileCall.DEFAULT_HOST), new String[] { ".git/config" },
				null, null);
		assertAll(() -> assertEquals(403, h.status), () -> assertEquals(0, h.bytes.size()));
	}

	// ----- active solution --------------------------------------------------

	@Test
	@DisplayName("supplier null, empty or throwing -> 409 no_active_solution (the reader is never reached)")
	void noActiveSolution() throws Exception {
		OpencodeChatServlet nul = FileCall.servlet(() -> null, reader);
		OpencodeChatServlet empty = FileCall.servlet(() -> "", reader);
		OpencodeChatServlet boom = FileCall.servlet(() -> {
			throw new IllegalStateException("boom " + ws);
		}, reader);
		for (OpencodeChatServlet s : new OpencodeChatServlet[] { nul, empty, boom }) {
			Reply r = FileCall.get(s, "handoff/t/spec.md");
			assertAll(() -> assertEquals(409, r.status), () -> assertEquals("{\"error\":\"no_active_solution\"}", r.body()),
					() -> assertJsonHeaders(r));
		}
	}

	// ----- parameter --------------------------------------------------------

	@Test
	@DisplayName("no path, two path values, blank and empty path -> 400 bad_request")
	void badRequest() throws Exception {
		Map<String, String> h = host(FileCall.DEFAULT_HOST);
		Reply none = FileCall.call(servlet, "GET", "/file", h, null, null, null);
		Reply two = FileCall.call(servlet, "GET", "/file", h, new String[] { "a", "b" }, null, null);
		Reply same = FileCall.call(servlet, "GET", "/file", h, new String[] { "handoff/t/spec.md", "handoff/t/spec.md" },
				null, null);
		Reply zero = FileCall.call(servlet, "GET", "/file", h, new String[0], null, null);
		Reply nullValue = FileCall.call(servlet, "GET", "/file", h, new String[] { null }, null, null);
		Reply blank = get("   ");
		Reply empty = get("");
		for (Reply r : new Reply[] { none, two, same, zero, nullValue, blank, empty }) {
			assertAll(() -> assertEquals(400, r.status), () -> assertEquals("{\"error\":\"bad_request\"}", r.body()),
					() -> assertJsonHeaders(r));
		}
	}

	// ----- 200 --------------------------------------------------------------

	@Test
	@DisplayName("200 carries the exact JSON (escaped content, relative path) and the three fixed headers")
	void success() throws Exception {
		Path file = ws.resolve("handoff/t/spec.md");
		long modified = Files.getLastModifiedTime(file).toMillis();
		String content = "# \"quoted\" \\ spec\n";
		Reply r = get("handoff/t/spec.md");
		String expected = "{\"path\":\"handoff/t/spec.md\",\"name\":\"spec.md\",\"size\":"
				+ content.getBytes(StandardCharsets.UTF_8).length + ",\"modified\":" + modified
				+ ",\"content\":\"# \\\"quoted\\\" \\\\ spec\\n\"}";
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(expected, r.body()),
				() -> assertJsonHeaders(r), () -> assertNoRoot(r.body()));
	}

	// ----- contract of the non-200 statuses --------------------------------

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "../x|403|forbidden", ".git/config|403|forbidden",
			"handoff/t/gone.md|404|not_found", "handoff|404|not_found", "bin.dat|415|binary" })
	@DisplayName("403/404/415 bodies and headers are exactly the contract and never contain the root or the path")
	void errorContract(String path, int status, String error) throws Exception {
		Reply r = get(path);
		assertAll(() -> assertEquals(status, r.status), () -> assertEquals("{\"error\":\"" + error + "\"}", r.body()),
				() -> assertJsonHeaders(r), () -> assertNoRoot(r.body()),
				() -> assertFalse(r.body().contains(path), "requested path leaked: " + r.body()));
	}

	@Test
	@DisplayName("413 too_large for a file above the reader limit")
	void tooLarge() throws Exception {
		Files.writeString(ws.resolve("large.txt"), "x".repeat(65), StandardCharsets.UTF_8);
		Reply r = get("large.txt");
		assertAll(() -> assertEquals(413, r.status), () -> assertEquals("{\"error\":\"too_large\"}", r.body()),
				() -> assertJsonHeaders(r), () -> assertNoRoot(r.body()));
	}

	@Test
	@DisplayName("a refused request never echoes the root in any spelling (case, slash style, JSON-escaped)")
	void neverLeaksRoot() throws Exception {
		String[] inputs = { "../x", ws.resolve("nope.md").toString(), tmp.resolve("other/x").toString(), ".git/config",
				"handoff/t/gone.md", "a:b", "bin.dat" };
		for (String input : inputs) {
			Reply r = get(input);
			assertAll("input " + input, () -> assertNotOk(r), () -> assertNoRoot(r.body()),
					() -> assertFalse(r.body().contains(tmp.getFileName().toString()), r.body()));
		}
	}

	private static void assertNotOk(Reply r) {
		assertTrue(r.status >= 400, "expected an error status but got " + r.status);
	}

	private void assertNoRoot(String body) {
		String lower = body.toLowerCase(Locale.ROOT);
		String root = ws.toString();
		String fwd = root.replace('\\', '/').toLowerCase(Locale.ROOT);
		String back = root.replace('/', '\\').toLowerCase(Locale.ROOT);
		String escaped = back.replace("\\", "\\\\");
		assertFalse(lower.contains(fwd) || lower.contains(back) || lower.contains(escaped),
				"body contains the root: " + body);
	}

	@Test
	@DisplayName("403 body is byte-identical for an existing and a missing .git/config")
	void gitBodyIdentical() throws Exception {
		Reply existing = get(".git/config");
		Reply missing = get(".git/nothing-here");
		assertAll(() -> assertEquals(403, existing.status), () -> assertEquals(existing.body(), missing.body()));
	}

	// ----- Host / Sec-Fetch-Site / Origin ----------------------------------

	@ParameterizedTest
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"localhost:8183|NULL|NULL|200",
			"127.0.0.1:8183|NULL|NULL|200",
			"[::1]:8183|NULL|NULL|200",
			"LOCALHOST:8183|NULL|NULL|200",
			"localhost:8183|same-origin|NULL|200",
			"localhost:8183|NULL|http://localhost:8183|200",
			"127.0.0.1:8183|same-origin|http://127.0.0.1:8183|200",
			"evil.example|NULL|NULL|403",
			"127.0.0.1.evil.com|NULL|NULL|403",
			"localhost.evil.com:8183|NULL|NULL|403",
			"NULL|NULL|NULL|403",
			"localhost:8183|cross-site|NULL|403",
			"localhost:8183|same-site|NULL|403",
			"localhost:8183|none|NULL|403",
			"localhost:8183|NULL|http://evil.example|403",
			"localhost:8183|NULL|http://localhost:9|403",
			"localhost:8183|same-origin|http://evil.example|403" })
	@DisplayName("Host / Sec-Fetch-Site / Origin table: only a loopback same-origin request reaches the reader")
	void originTable(String host, String site, String origin, int status) throws Exception {
		Map<String, String> h = host(host);
		if (site != null)
			h.put("Sec-Fetch-Site", site);
		if (origin != null)
			h.put("Origin", origin);
		Reply r = FileCall.call(servlet, "GET", "/file", h, new String[] { "handoff/t/spec.md" }, null, null);
		assertEquals(status, r.status, r.body());
		if (status == 403) {
			assertAll(() -> assertEquals("{\"error\":\"forbidden\"}", r.body()), () -> assertJsonHeaders(r));
		}
	}

	@Test
	@DisplayName("a bad Host is refused before the supplier or the reader is consulted")
	void badHostBeforeSupplier() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		OpencodeChatServlet s = FileCall.servlet(() -> {
			calls.incrementAndGet();
			return ws.toString();
		}, reader);
		Reply r = FileCall.call(s, "GET", "/file", host("evil.example"), new String[] { "handoff/t/spec.md" }, null,
				null);
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals(0, calls.get()));
	}

	// ----- blocked proxy paths ---------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "/rest_api/fs/read/x", "/rest_api/fs/list", "/rest_api/experimental/fs/write",
			"/rest_api/FS/read", "/rest_api//fs/read", "/rest_api/%66s/read", "/rest_api/%2566s/read",
			"/rest_api/session/../fs/read" })
	@DisplayName("blocked api paths are 403 JSON even with port 0 and serverReady=false, and never wait for the server")
	void blockedApiPaths(String pathInfo) throws Exception {
		AtomicInteger waits = new AtomicInteger();
		AtomicInteger supplierCalls = new AtomicInteger();
		OpencodeChatServlet s = new OpencodeChatServlet(() -> 0, () -> null, () -> false, t -> {
			waits.incrementAndGet();
			return false;
		}, () -> {
			supplierCalls.incrementAndGet();
			return ws.toString();
		}, p -> null);
		Map<String, String> h = new HashMap<>();
		h.put("x-opencode-directory", "C:\\Users");
		Reply r = FileCall.call(s, "GET", pathInfo, h, null, "directory=C%3A%5CUsers", null);
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals("{\"error\":\"forbidden\"}", r.body()),
				() -> assertJsonHeaders(r), () -> assertEquals(0, waits.get()),
				() -> assertEquals(0, supplierCalls.get()));
	}

	@Test
	@DisplayName("a non-blocked api path with port 0 is NOT 403 (the block is specific to fs paths)")
	void unblockedApiPathPassesGuard() throws Exception {
		OpencodeChatServlet s = FileCall.servlet(() -> 0, () -> true, () -> ws.toString(), null);
		Reply r = FileCall.call(s, "GET", "/rest_api/session", new HashMap<>(), null, null, null);
		assertEquals(503, r.status);
	}

	@Test
	@DisplayName("/file does not need the readiness gate or the port")
	void fileIgnoresReadiness() throws Exception {
		OpencodeChatServlet s = FileCall.servlet(() -> -1, () -> false, () -> ws.toString(), reader);
		Reply r = FileCall.get(s, "handoff/t/spec.md");
		assertEquals(200, r.status);
	}
}

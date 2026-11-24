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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.opencode.tests.plainjunit.tomcat.FileCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

/**
 * Request-guard and HTTP-contract tests of {@code GET /servoy_ai/usage},
 * driven through {@link OpencodeChatServlet#service} with the dynamic-proxy
 * fakes of {@link FileCall}.
 */
class OpencodeChatServletUsageGuardTest {
	private static final String JSON_UTF8 = "application/json;charset=UTF-8";
	private static final String UNAVAILABLE = "{\"available\":false}";
	private static final String SNAPSHOT = "{\"available\":true,\"used\":381.29,\"limit\":10000.0,\"stale\":false}";

	private final AtomicInteger waitCalls = new AtomicInteger();

	/** A servlet that is not ready and has no port, so any gate use would show. */
	private OpencodeChatServlet servlet(Supplier<String> usage) {
		return new OpencodeChatServlet(() -> -1, () -> null, () -> false, t -> {
			waitCalls.incrementAndGet();
			return false;
		}, () -> null, p -> null, () -> null, usage);
	}

	private static Map<String, String> headers(String host, String site, String origin) {
		Map<String, String> m = new HashMap<>();
		if (host != null)
			m.put("Host", host);
		if (site != null)
			m.put("Sec-Fetch-Site", site);
		if (origin != null)
			m.put("Origin", origin);
		return m;
	}

	private Reply call(OpencodeChatServlet s, String method, Map<String, String> h) throws Exception {
		return FileCall.call(s, method, "/usage", h, null, null, null);
	}

	@Test
	@DisplayName("GET answers 200 with the supplier JSON, JSON content type, no-store and nosniff")
	void getAnswersSupplierJson() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(SNAPSHOT, r.body()),
				() -> assertEquals(JSON_UTF8, r.contentType),
				() -> assertEquals("no-store", r.headers.get("Cache-Control")),
				() -> assertEquals("nosniff", r.headers.get("X-Content-Type-Options")),
				() -> assertEquals(r.bytes.size(), r.contentLength));
	}

	@Test
	@DisplayName("HEAD answers like GET with an empty body")
	void headHasNoBody() throws Exception {
		OpencodeChatServlet s = servlet(() -> SNAPSHOT);
		Reply g = call(s, "GET", headers(FileCall.DEFAULT_HOST, null, null));
		Reply h = call(s, "HEAD", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, h.status), () -> assertEquals(0, h.bytes.size()),
				() -> assertEquals(g.contentLength, h.contentLength), () -> assertEquals(g.headers, h.headers));
	}

	@ParameterizedTest
	@ValueSource(strings = { "POST", "PUT", "DELETE", "PATCH", "OPTIONS" })
	@DisplayName("other methods are 405 with 'Allow: GET, HEAD', an empty body and no supplier call")
	void methodNotAllowed(String method) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		OpencodeChatServlet s = servlet(() -> {
			calls.incrementAndGet();
			return SNAPSHOT;
		});
		Reply r = call(s, method, headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(405, r.status), () -> assertEquals("GET, HEAD", r.headers.get("Allow")),
				() -> assertEquals(0, r.bytes.size()), () -> assertEquals(0, calls.get()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "evil.example.com", "evil.example.com:8183", "192.168.1.5:8183", "localhost.evil.com" })
	@DisplayName("a non-loopback Host is 403 forbidden and the supplier is not called")
	void nonLoopbackHost(String host) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		OpencodeChatServlet s = servlet(() -> {
			calls.incrementAndGet();
			return SNAPSHOT;
		});
		Reply r = call(s, "GET", headers(host, null, null));
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals("{\"error\":\"forbidden\"}", r.body()),
				() -> assertEquals(0, calls.get()));
	}

	@Test
	@DisplayName("a missing Host is 403")
	void missingHost() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET", headers(null, null, null));
		assertEquals(403, r.status);
	}

	@Test
	@DisplayName("a cross-site Sec-Fetch-Site is 403")
	void crossSite() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET", headers(FileCall.DEFAULT_HOST, "cross-site", null));
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals("{\"error\":\"forbidden\"}", r.body()));
	}

	@Test
	@DisplayName("a same-origin Sec-Fetch-Site is accepted")
	void sameOriginAccepted() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET", headers(FileCall.DEFAULT_HOST, "same-origin", null));
		assertEquals(200, r.status);
	}

	@Test
	@DisplayName("a foreign Origin is 403")
	void foreignOrigin() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET",
				headers(FileCall.DEFAULT_HOST, null, "http://evil.example.com"));
		assertAll(() -> assertEquals(403, r.status), () -> assertEquals("{\"error\":\"forbidden\"}", r.body()));
	}

	@Test
	@DisplayName("a throwing supplier still answers 200 {\"available\":false}")
	void throwingSupplier() throws Exception {
		Reply r = call(servlet(() -> {
			throw new IllegalStateException("boom");
		}), "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(UNAVAILABLE, r.body()),
				() -> assertEquals(JSON_UTF8, r.contentType));
	}

	@Test
	@DisplayName("a null or empty supplier result answers {\"available\":false}")
	void emptySupplierResult() throws Exception {
		Reply nul = call(servlet(() -> null), "GET", headers(FileCall.DEFAULT_HOST, null, null));
		Reply empty = call(servlet(() -> ""), "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(UNAVAILABLE, nul.body()), () -> assertEquals(UNAVAILABLE, empty.body()));
	}

	@Test
	@DisplayName("is answered with the server not ready and port -1, without ever waiting for the server")
	void noReadinessGate() throws Exception {
		Reply r = call(servlet(() -> SNAPSHOT), "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(SNAPSHOT, r.body()),
				() -> assertEquals(0, waitCalls.get()));
	}

	@Test
	@DisplayName("the 6-argument constructor answers {\"available\":false}")
	void sixArgConstructor() throws Exception {
		OpencodeChatServlet s = new OpencodeChatServlet(() -> -1, () -> null, () -> false, t -> false, () -> null,
				p -> null);
		Reply r = call(s, "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(UNAVAILABLE, r.body()));
	}

	@Test
	@DisplayName("the 7-argument constructor answers {\"available\":false}")
	void sevenArgConstructor() throws Exception {
		OpencodeChatServlet s = new OpencodeChatServlet(() -> -1, () -> null, () -> false, t -> false, () -> null,
				p -> null, () -> "crm");
		Reply r = call(s, "GET", headers(FileCall.DEFAULT_HOST, null, null));
		assertAll(() -> assertEquals(200, r.status), () -> assertEquals(UNAVAILABLE, r.body()));
	}

	@Test
	@DisplayName("/usage does not change the not-ready 503 of the proxied rest_api paths")
	void restApiStillGated() throws Exception {
		OpencodeChatServlet s = servlet(() -> SNAPSHOT);
		Reply r = FileCall.call(s, "GET", "/rest_api/session", headers(FileCall.DEFAULT_HOST, null, null), null, null,
				null);
		assertEquals(503, r.status);
	}

	@Test
	@DisplayName("/solution keeps answering through its own supplier next to /usage")
	void solutionUnchanged() throws Exception {
		OpencodeChatServlet s = new OpencodeChatServlet(() -> -1, () -> null, () -> false, t -> false, () -> null,
				p -> null, () -> "crm_app", () -> SNAPSHOT);
		Reply r = FileCall.call(s, "GET", "/solution", headers(FileCall.DEFAULT_HOST, null, null), null, null, null);
		assertAll(() -> assertEquals(200, r.status), () -> assertTrue(r.body().contains("crm_app"), r.body()));
	}
}

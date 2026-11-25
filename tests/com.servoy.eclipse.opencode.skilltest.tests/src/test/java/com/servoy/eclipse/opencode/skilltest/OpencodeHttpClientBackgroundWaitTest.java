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

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests that {@link OpencodeHttpClient#waitForCompletion} waits for background
 * subagent work (opencode 2.4.1+), not only for the root session's own turn.
 * <p>
 * A loopback {@link HttpServer} stands in for opencode: the root's {@code /wait}
 * is idle at once (204), while {@code GET /session/active} keeps listing a
 * child session as running for a number of polls - the shape where the
 * Orchestrator's turn has ended but its background Developer is still working.
 * </p>
 */
class OpencodeHttpClientBackgroundWaitTest {

	private static final String ROOT = "ses_root"; //$NON-NLS-1$
	private static final String CHILD = "ses_child"; //$NON-NLS-1$
	private static final String GRANDCHILD = "ses_grandchild"; //$NON-NLS-1$

	private HttpServer server;
	private int port;
	/** How many more GET /session/active answers still list the running sessions. */
	private final AtomicInteger activeAnswersLeft = new AtomicInteger();
	/** The session ids GET /session/active reports as running while answers remain. */
	private volatile Set<String> running = Set.of();
	/** How many more GET /inbox answers still report one queued item for the root. */
	private final AtomicInteger inboxAnswersLeft = new AtomicInteger();
	private final AtomicInteger activeCalls = new AtomicInteger();
	private final Set<String> interrupted = ConcurrentHashMap.newKeySet();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
		server.createContext("/", this::handle); //$NON-NLS-1$
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getQuery();
		int status = 200;
		String body = "{}"; //$NON-NLS-1$
		if (path.endsWith("/wait")) { //$NON-NLS-1$
			status = 204; // the root's own turn is over at once
			body = null;
		} else if (path.equals("/api/session/active")) { //$NON-NLS-1$
			activeCalls.incrementAndGet();
			Set<String> ids = activeAnswersLeft.getAndDecrement() > 0 ? running : Set.of();
			StringBuilder data = new StringBuilder();
			for (String id : ids) {
				if (data.length() > 0) {
					data.append(',');
				}
				data.append('"').append(id).append("\":{\"type\":\"running\"}"); //$NON-NLS-1$
			}
			body = "{\"data\":{" + data + "}}"; //$NON-NLS-1$ //$NON-NLS-2$
		} else if (path.endsWith("/inbox")) { //$NON-NLS-1$
			body = inboxAnswersLeft.getAndDecrement() > 0
					? "{\"data\":[{\"id\":\"msg_1\",\"type\":\"synthetic\"}]}" //$NON-NLS-1$
					: "{\"data\":[]}"; //$NON-NLS-1$
		} else if (path.equals("/api/session") && query != null) { //$NON-NLS-1$
			if (query.contains("parentID=" + ROOT)) { //$NON-NLS-1$
				body = "{\"data\":[{\"id\":\"" + CHILD + "\",\"parentID\":\"" + ROOT + "\"}]}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else if (query.contains("parentID=" + CHILD)) { //$NON-NLS-1$
				body = "{\"data\":[{\"id\":\"" + GRANDCHILD + "\",\"parentID\":\"" + CHILD + "\"}]}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else {
				body = "{\"data\":[]}"; //$NON-NLS-1$
			}
		} else if (path.endsWith("/interrupt")) { //$NON-NLS-1$
			String[] seg = path.split("/"); //$NON-NLS-1$
			interrupted.add(seg[seg.length - 2]);
		}
		if (body == null) {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	@Timeout(30)
	@DisplayName("keeps waiting while a background child session is still running after the root went idle")
	void waitsForRunningChild() throws Exception {
		running = Set.of(CHILD);
		activeAnswersLeft.set(3);
		OpencodeHttpClient client = new OpencodeHttpClient(port);

		client.waitForCompletion(ROOT, Duration.ofMillis(20), () -> false, Duration.ofMinutes(5), Duration.ofMinutes(5));

		// 3 answers with the child running, then 2 quiet ones confirm completion.
		assertTrue(activeCalls.get() >= 5,
				"must keep polling /session/active until the child stopped, polls: " + activeCalls.get()); //$NON-NLS-1$
	}

	@Test
	@Timeout(30)
	@DisplayName("also waits for a running grandchild (nested subagent)")
	void waitsForRunningGrandchild() throws Exception {
		running = Set.of(GRANDCHILD);
		activeAnswersLeft.set(2);
		OpencodeHttpClient client = new OpencodeHttpClient(port);

		client.waitForCompletion(ROOT, Duration.ofMillis(20), () -> false, Duration.ofMinutes(5), Duration.ofMinutes(5));

		assertTrue(activeCalls.get() >= 4,
				"must wait for the nested subagent too, polls: " + activeCalls.get()); //$NON-NLS-1$
	}

	@Test
	@Timeout(30)
	@DisplayName("keeps waiting while the root has a queued background result in its inbox")
	void waitsForQueuedInboxResult() throws Exception {
		inboxAnswersLeft.set(2);
		OpencodeHttpClient client = new OpencodeHttpClient(port);

		client.waitForCompletion(ROOT, Duration.ofMillis(20), () -> false, Duration.ofMinutes(5), Duration.ofMinutes(5));

		// 2 inbox answers with a queued item, then 2 quiet checks: 4 tree checks in all.
		assertTrue(inboxAnswersLeft.get() < 0,
				"both queued inbox answers must have been seen before returning"); //$NON-NLS-1$
		assertEquals(4, activeCalls.get(), "two busy (inbox) checks plus two quiet ones"); //$NON-NLS-1$
	}

	@Test
	@Timeout(30)
	@DisplayName("returns after two quiet checks when nothing runs in the background")
	void returnsWhenTreeIsQuiet() throws Exception {
		OpencodeHttpClient client = new OpencodeHttpClient(port);

		client.waitForCompletion(ROOT, Duration.ofMillis(20), () -> false, Duration.ofMinutes(5), Duration.ofMinutes(5));

		assertEquals(2, activeCalls.get(), "a quiet tree is confirmed with exactly two checks"); //$NON-NLS-1$
	}

	@Test
	@Timeout(30)
	@DisplayName("the per-attempt cap still fires while a child runs forever, and the whole tree is interrupted")
	void capInterruptsTheWholeTree() {
		running = Set.of(CHILD);
		activeAnswersLeft.set(Integer.MAX_VALUE);
		OpencodeHttpClient client = new OpencodeHttpClient(port);

		IOException failure = assertThrows(IOException.class, () -> client.waitForCompletion(ROOT,
				Duration.ofMillis(20), () -> false, Duration.ofMinutes(5), Duration.ofMillis(400)));

		assertTrue(failure.getMessage().contains("per-attempt cap"), failure.getMessage()); //$NON-NLS-1$
		assertTrue(interrupted.containsAll(List.of(ROOT, CHILD, GRANDCHILD)),
				"root and every descendant must be interrupted, got " + interrupted); //$NON-NLS-1$
	}
}

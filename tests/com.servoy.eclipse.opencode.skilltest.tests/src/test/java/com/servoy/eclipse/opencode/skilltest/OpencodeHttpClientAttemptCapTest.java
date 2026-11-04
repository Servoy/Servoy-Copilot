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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

/**
 * Tests the hard per-attempt wall-clock cap in
 * {@link OpencodeHttpClient#waitForCompletion(String, Duration, java.util.function.BooleanSupplier, Duration, Duration)}
 * (SVY-21366, build #64).
 * <p>
 * The failure being pinned: a session that is wedged server-side while still
 * answering {@code 503 still busy} resets the stall guard on every sample, so
 * the stall guard can never fire and the wait blocks until the CI pipeline
 * timeout kills the whole run. The cap is measured from the start of the wait
 * and is never reset, so it bounds that case.
 * </p>
 * <p>
 * A loopback {@link HttpServer} stands in for the opencode server and always
 * answers 503 to the {@code /wait} route, which is exactly the "busy forever"
 * shape. No workbench, no real opencode.
 * </p>
 */
class OpencodeHttpClientAttemptCapTest {

	private HttpServer server;
	private int port;
	private final AtomicInteger waitCalls = new AtomicInteger();
	private final AtomicInteger abortCalls = new AtomicInteger();

	@BeforeEach
	void startAlwaysBusyServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
		server.createContext("/", exchange -> { //$NON-NLS-1$
			String path = exchange.getRequestURI().getPath();
			int status;
			if (path.endsWith("/wait")) { //$NON-NLS-1$
				waitCalls.incrementAndGet();
				status = 503; // always "still busy" - never goes idle
			} else if (path.endsWith("/interrupt")) { //$NON-NLS-1$
				// opencode 2.x renamed /abort to /interrupt; OpencodeHttpClient.abort()
				// posts to this route, so this is what proves the wedged session was
				// actually told to stop rather than merely abandoned.
				abortCalls.incrementAndGet();
				status = 200;
			} else {
				status = 200;
			}
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	@Timeout(30)
	@DisplayName("the per-attempt cap fires on a session that stays busy forever, and aborts it")
	void capFiresOnPermanentlyBusySession() {
		OpencodeHttpClient client = new OpencodeHttpClient(port);
		// Stall guard long (would never fire); cap short so the test is quick. This is
		// build #64's shape in miniature: busy forever, stall guard useless.
		IOException failure = assertThrows(IOException.class,
				() -> client.waitForCompletion("ses_busy", Duration.ofMillis(50), () -> false, //$NON-NLS-1$
						Duration.ofMinutes(60), Duration.ofMillis(300)));

		assertTrue(failure.getMessage().contains("per-attempt cap"), //$NON-NLS-1$
				"should report the cap as the reason, was: " + failure.getMessage()); //$NON-NLS-1$
		assertTrue(abortCalls.get() > 0, "the wedged session must be aborted"); //$NON-NLS-1$
	}

	@Test
	@Timeout(30)
	@DisplayName("a non-positive cap disables the bound: only cancellation ends a busy-forever wait")
	void zeroCapDisablesTheBound() {
		OpencodeHttpClient client = new OpencodeHttpClient(port);
		// With the cap disabled, a permanently-busy session is genuinely unbounded:
		// every 503 RESETS the stall guard, so the guard can never fire either. That
		// is precisely build #64's failure mode, so this test must not wait it out -
		// it cancels after a moment and asserts the wait ended by cancellation rather
		// than by the cap, proving the cap was really off and not silently defaulted.
		long cancelAfter = System.nanoTime() + Duration.ofMillis(400).toNanos();
		assertThrows(InterruptedException.class,
				() -> client.waitForCompletion("ses_busy", Duration.ofMillis(50), //$NON-NLS-1$
						() -> System.nanoTime() > cancelAfter, Duration.ofMinutes(60), Duration.ZERO));
	}

	@Test
	@DisplayName("the default cap is positive, so an attempt is always bounded")
	void defaultCapIsEnabled() {
		assertFalse(OpencodeHttpClient.DEFAULT_ATTEMPT_TIMEOUT.isZero(),
				"the default per-attempt cap must be set, else a wedged run is unbounded"); //$NON-NLS-1$
		assertFalse(OpencodeHttpClient.DEFAULT_ATTEMPT_TIMEOUT.isNegative(),
				"the default per-attempt cap must be positive"); //$NON-NLS-1$
	}
}

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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A thin {@link java.net.http.HttpClient} + Jackson wrapper around the embedded
 * opencode server's HTTP API. The server binds {@code 127.0.0.1} only and the
 * Servoy launch sets no {@code OPENCODE_SERVER_PASSWORD}, so plain HTTP on
 * localhost is used with no auth.
 * <p>
 * Prompts are driven asynchronously ({@link #promptAsync} +
 * {@link #waitForCompletion}) rather than with one long blocking request,
 * because a replayed baseline (especially an Orchestrator one that spawns child
 * sub-agent sessions) can run for minutes.
 * </p>
 */
public final class OpencodeHttpClient {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Default stall guard for {@link #waitForCompletion}: if a session makes no
	 * {@code busy} progress for this long it is treated as wedged and aborted.
	 * Generous so legitimately long Orchestrator runs never trip it; overridable
	 * via the {@code servoy.skilltest.stallTimeoutSeconds} system property.
	 */
	static final Duration DEFAULT_STALL_TIMEOUT = Duration
			.ofSeconds(Long.getLong("servoy.skilltest.stallTimeoutSeconds", 3600L)); //$NON-NLS-1$

	private final String baseUrl;
	private final HttpClient httpClient;

	/**
	 * @param port the port the embedded opencode server listens on
	 */
	/**
	 * opencode 2.x serves its HTTP API under an {@code /api} prefix (e.g.
	 * {@code POST /api/session}); the pre-2.x routes ({@code /session}) now return
	 * HTTP 405. All request paths below are built with this prefix.
	 */
	private static final String API = "/api"; //$NON-NLS-1$

	/** Fixed HTTP basic-auth username opencode uses when a server password is set. */
	private static final String SERVER_AUTH_USER = "opencode"; //$NON-NLS-1$

	/** {@code Authorization: Basic ...} header value, or {@code null} when no password is set. */
	private final String authHeader;

	public OpencodeHttpClient(int port) {
		this.baseUrl = "http://127.0.0.1:" + port; //$NON-NLS-1$
		// Force HTTP/1.1: the JDK client defaults to HTTP/2 and attempts an h2
		// upgrade on the localhost connection. The opencode server mishandles that
		// upgrade for POST-with-body, so the request stalls until the request timeout
		// even though the same call over HTTP/1.1 returns in milliseconds.
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10)).build();
		this.authHeader = resolveAuthHeader();
	}

	/**
	 * Builds the {@code Authorization: Basic opencode:<password>} header from the
	 * server password the opencode bundle generated for this launch. opencode 2.x
	 * secures its {@code /api} routes with {@code OPENCODE_SERVER_PASSWORD}, so
	 * without this every request returns HTTP 401. The password is read
	 * reflectively from {@code com.servoy.eclipse.opencode.Activator} so this
	 * bundle does not need a compile dependency on its internals beyond what it
	 * already has; returns {@code null} when unavailable (server without a
	 * password), in which case no auth header is sent.
	 */
	private static String resolveAuthHeader() {
		try {
			com.servoy.eclipse.opencode.Activator activator = com.servoy.eclipse.opencode.Activator.getInstance();
			String password = activator != null ? activator.getServerPassword() : null;
			if (password == null || password.isBlank()) {
				return null;
			}
			String credentials = SERVER_AUTH_USER + ":" + password; //$NON-NLS-1$
			return "Basic " + java.util.Base64.getEncoder() //$NON-NLS-1$
					.encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (RuntimeException | LinkageError ex) {
			return null;
		}
	}

	/**
	 * Returns a request builder for {@code path} with the auth header applied (when
	 * a server password is set) and the given timeout.
	 */
	private HttpRequest.Builder request(String path, Duration timeout) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
		if (authHeader != null) {
			b.header("authorization", authHeader); //$NON-NLS-1$
		}
		return b;
	}

	/**
	 * @return {@code true} if the server responds to a root request
	 */
	public boolean health() {
		try {
			HttpRequest request = request("/", Duration.ofSeconds(10)).GET().build(); //$NON-NLS-1$
			HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
			return response.statusCode() >= 200 && response.statusCode() < 500;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}

	/**
	 * Creates a new session.
	 *
	 * @param title an optional session title
	 * @return the created session id
	 * @throws IOException          on transport or protocol failure
	 * @throws InterruptedException if interrupted while waiting
	 */
	public String createSession(String title) throws IOException, InterruptedException {
		ObjectNode body = MAPPER.createObjectNode();
		if (title != null) {
			body.put("title", title); //$NON-NLS-1$
		}
		HttpRequest request = request(API + "/session", Duration.ofSeconds(30)) //$NON-NLS-1$
				.header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
				.POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
		HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("createSession failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonNode node = MAPPER.readTree(response.body());
		// opencode 2.x wraps the response in a { data: { id, ... } } envelope.
		JsonNode data = node.get("data"); //$NON-NLS-1$
		JsonNode id = data != null ? data.get("id") : node.get("id"); //$NON-NLS-1$
		if (id == null || id.isNull()) {
			throw new IOException("createSession: no id in response: " + response.body()); //$NON-NLS-1$
		}
		return id.asText();
	}

	/**
	 * Sends a prompt to a session asynchronously via
	 * {@code POST /session/:id/prompt_async}. The server accepts the prompt
	 * (HTTP 204) and starts the run in the background, returning immediately
	 * instead of holding the connection open for the whole assistant turn.
	 * <p>
	 * An Orchestrator baseline fans out into child sub-agent sessions and can run
	 * for many minutes, which a single blocking POST cannot reliably wait out (it
	 * surfaces as a client-side "request timed out"). Callers pair this with
	 * {@link #waitForCompletion} and then export the session.
	 * </p>
	 *
	 * @param sessionId the session id
	 * @param prompt    the prompt text to send
	 * @param agent     optional agent name (omitted when {@code null})
	 * @param model     optional model id (omitted when {@code null}/blank)
	 * @throws IOException          on transport or protocol failure
	 * @throws InterruptedException if interrupted while waiting
	 */
	public void promptAsync(String sessionId, String prompt, String agent, String model)
			throws IOException, InterruptedException {
		// opencode 2.x prompt body: top-level `text` (no parts[]), `agent`, `model`
		// (a { providerID, modelID } object), and `delivery` to run in the
		// background so this POST returns as soon as the run is accepted rather than
		// holding the connection for the whole assistant turn.
		ObjectNode body = MAPPER.createObjectNode();
		body.put("text", prompt); //$NON-NLS-1$
		// Session.Inbox.Delivery is the enum ["steer","queue"]; "queue" runs the
		// prompt as a queued turn so this POST returns once accepted rather than
		// holding the connection for the whole assistant turn.
		body.put("delivery", "queue"); //$NON-NLS-1$ //$NON-NLS-2$
		if (agent != null) {
			body.put("agent", agent); //$NON-NLS-1$
		}
		if (model != null && !model.isBlank()) {
			body.set("model", toModelNode(model)); //$NON-NLS-1$
		}
		// With delivery=background the prompt returns quickly with an accept, after
		// which waitForCompletion polls status. Use a generous timeout so the initial
		// accept is never cut off on a cold session (first provider call / MCP tool
		// discovery); the real long wait is handled by the poll loop, not this request.
		HttpRequest request = request(API + "/session/" + enc(sessionId) + "/prompt", Duration.ofMinutes(5)) //$NON-NLS-1$ //$NON-NLS-2$
				.header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
				.POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
		HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("promptAsync failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Returns the run status of a session from {@code GET /session/status}, which
	 * reports a map of {@code sessionID -> { type }}.
	 * <p>
	 * The server reports {@code "busy"} while a run is in flight and
	 * {@code "retry"} between provider retries. When a session becomes idle it is
	 * <b>dropped from the map entirely</b> rather than reported as {@code "idle"};
	 * this method returns {@code "idle"} in that case so callers see a single
	 * terminal value.
	 *
	 * @param sessionId the session id
	 * @return one of {@code "busy"}, {@code "retry"}, or {@code "idle"} (idle also
	 *         covers an absent entry)
	 * @throws IOException          on transport or protocol failure
	 * @throws InterruptedException if interrupted while waiting
	 */
	public String getStatus(String sessionId) throws IOException, InterruptedException {
		// opencode 2.x has no /session/status map; a session's run state is read from
		// GET /api/session/:id, whose `time.completed` is set once the run is done.
		// While busy `completed` is absent/null; treat that as "busy" and a present
		// `completed` (or a missing session) as "idle".
		HttpRequest request = request(API + "/session/" + enc(sessionId), Duration.ofSeconds(10)).GET().build(); //$NON-NLS-1$
		HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return "idle"; //$NON-NLS-1$
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("getStatus failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonNode body = MAPPER.readTree(response.body());
		// Unwrap the 2.x { data: { ... } } envelope when present.
		JsonNode session = body.has("data") ? body.get("data") : body; //$NON-NLS-1$ //$NON-NLS-2$
		JsonNode time = session.get("time"); //$NON-NLS-1$
		JsonNode completed = time != null ? time.get("completed") : null; //$NON-NLS-1$
		boolean done = completed != null && !completed.isNull();
		return done ? "idle" : "busy"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Blocks until a session started with {@link #promptAsync} reaches an idle
	 * state, polling {@link #getStatus}. There is <b>no overall time budget</b>:
	 * this waits as long as the run takes and only returns once the session is
	 * idle (a replayed Orchestrator baseline that spawns child sub-agents can run
	 * for many minutes, and imposing a budget was the source of spurious
	 * "timed out" failures).
	 * <p>
	 * One edge case is handled: right after {@code prompt_async} accepts, the
	 * session may not yet appear as {@code busy} (a startup race), so this waits to
	 * observe {@code busy} at least once before treating idle as terminal, up to a
	 * short startup grace window. Once {@code busy} has been seen, the first
	 * non-busy status ends the wait.
	 * </p>
	 *
	 * @param sessionId    the session id
	 * @param pollInterval how often to poll status
	 * @throws InterruptedException if interrupted while waiting
	 * @throws IOException          on transport failure while polling
	 */
	public void waitForCompletion(String sessionId, Duration pollInterval)
			throws IOException, InterruptedException {
		waitForCompletion(sessionId, pollInterval, () -> false);
	}

	/**
	 * Like {@link #waitForCompletion(String, Duration)} but cooperatively
	 * cancellable: when {@code cancelled} reports {@code true} the in-flight run is
	 * {@link #abort(String) aborted} and an {@link InterruptedException} is thrown
	 * so the caller can stop. Checked on every poll, so a Stop action takes effect
	 * within one poll interval.
	 *
	 * @param sessionId    the session id
	 * @param pollInterval how often to poll status
	 * @param cancelled    supplies {@code true} when the run should be stopped
	 * @throws InterruptedException if interrupted or cancelled while waiting
	 * @throws IOException          on transport failure while polling
	 */
	public void waitForCompletion(String sessionId, Duration pollInterval, java.util.function.BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		waitForCompletion(sessionId, pollInterval, cancelled, DEFAULT_STALL_TIMEOUT);
	}

	/**
	 * Like {@link #waitForCompletion(String, Duration, java.util.function.BooleanSupplier)}
	 * but with a stall guard: if the session reports no forward progress (stays
	 * non-busy without ever having gone idle, i.e. the run appears wedged
	 * server-side) for longer than {@code stallTimeout}, this throws so the caller
	 * can record an ERROR instead of blocking forever.
	 * <p>
	 * The guard is <b>reset on every observed {@code busy}/{@code retry} sample</b>,
	 * so a legitimately long Orchestrator run that keeps working (spawning
	 * sub-agents, calling tools) never trips it - only a session that stops making
	 * progress for the whole {@code stallTimeout} window does. Pass a
	 * zero/negative duration to disable the guard (unbounded wait).
	 *
	 * @param sessionId    the session id
	 * @param pollInterval how often to poll status
	 * @param cancelled    supplies {@code true} when the run should be stopped
	 * @param stallTimeout maximum time to tolerate with no {@code busy} progress
	 *                     before aborting; {@code <= 0} disables the guard
	 * @throws InterruptedException if interrupted or cancelled while waiting
	 * @throws IOException          on transport failure, or if the stall timeout is
	 *                              exceeded
	 */
	/**
	 * Blocks until a session becomes idle using the opencode 2.x
	 * {@code POST /api/experimental/session/:id/wait} endpoint (returns 204 when
	 * idle). Replaces the old {@code GET /session/status} poll loop: v2 has no
	 * {@code time.completed} field on the session object, so polling the session
	 * cannot detect completion.
	 * <p>
	 * The endpoint blocks server-side; to keep cancellation responsive and to
	 * honour the stall guard, this sends short-lived (15 s) requests in a loop,
	 * checking the cancel supplier and the stall deadline between calls:
	 * <ul>
	 *   <li>204 → idle, return.</li>
	 *   <li>503 (still busy) / HTTP timeout → try again (stall guard resets on
	 *       503 since the server confirms the run is in progress).</li>
	 *   <li>other error → throw.</li>
	 * </ul>
	 *
	 * @param sessionId    the session id
	 * @param pollInterval ignored (kept for API compat; the wait call itself blocks)
	 * @param cancelled    supplies {@code true} when the run should be stopped
	 * @param stallTimeout maximum time to tolerate with no progress before aborting
	 * @throws InterruptedException if interrupted or cancelled
	 * @throws IOException          on transport failure or stall timeout
	 */
	public void waitForCompletion(String sessionId, Duration pollInterval, java.util.function.BooleanSupplier cancelled,
			Duration stallTimeout) throws IOException, InterruptedException {
		boolean guard = stallTimeout != null && !stallTimeout.isZero() && !stallTimeout.isNegative();
		long stallNanos = guard ? stallTimeout.toNanos() : 0L;
		long stallDeadlineNanos = System.nanoTime() + stallNanos;
		Duration callTimeout = Duration.ofSeconds(15);
		while (true) {
			if (cancelled != null && cancelled.getAsBoolean()) {
				abort(sessionId);
				throw new InterruptedException("cancelled"); //$NON-NLS-1$
			}
			try {
				HttpRequest request = request(
						API + "/experimental/session/" + enc(sessionId) + "/wait", callTimeout) //$NON-NLS-1$ //$NON-NLS-2$
						.POST(BodyPublishers.noBody()).build();
				HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
				if (response.statusCode() == 204 || response.statusCode() == 200) {
					return; // idle
				}
				if (response.statusCode() == 503) {
					// still busy: server explicitly tells us the run is in progress
					stallDeadlineNanos = System.nanoTime() + stallNanos; // progress: reset stall guard
				} else if (response.statusCode() == 404) {
					return; // session gone → treat as idle
				} else {
					throw new IOException("wait failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} catch (java.net.http.HttpTimeoutException timeout) {
				// call timed out (server still processing) — retry
			}
			if (guard && System.nanoTime() > stallDeadlineNanos) {
				abort(sessionId);
				throw new IOException("opencode session '" + sessionId + "' stalled: no progress for " //$NON-NLS-1$ //$NON-NLS-2$
						+ stallTimeout.toSeconds() + "s"); //$NON-NLS-1$
			}
		}
	}

	/**
	 * Fetches all messages of a session via {@code GET /session/:id/message} and
	 * wraps them in a {@code { "messages": [...] }} envelope so the result can be
	 * parsed by {@link SessionTranscript#fromExport}. Used as a fallback source of
	 * tool calls when the native {@code opencode export} yields nothing.
	 *
	 * @param sessionId the session id
	 * @return a JSON object with a {@code messages} array
	 * @throws IOException          on transport or protocol failure
	 * @throws InterruptedException if interrupted while waiting
	 */
	public JsonNode getMessages(String sessionId) throws IOException, InterruptedException {
		HttpRequest request = request(API + "/session/" + enc(sessionId) + "/message", Duration.ofSeconds(30)) //$NON-NLS-1$ //$NON-NLS-2$
				.GET().build();
		HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("getMessages failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		JsonNode parsed = MAPPER.readTree(response.body());
		// Unwrap the 2.x { data: [...] } envelope when present.
		JsonNode messages = parsed.has("data") ? parsed.get("data") : parsed; //$NON-NLS-1$ //$NON-NLS-2$
		ObjectNode envelope = MAPPER.createObjectNode();
		envelope.set("messages", messages); //$NON-NLS-1$
		return envelope;
	}

	/**
	 * Aborts an in-flight message on a session.
	 *
	 * @param sessionId the session id
	 */
	public void abort(String sessionId) {
		try {
			// opencode 2.x: /abort is now /interrupt.
			HttpRequest request = request(API + "/session/" + enc(sessionId) + "/interrupt", Duration.ofSeconds(10)) //$NON-NLS-1$ //$NON-NLS-2$
					.POST(BodyPublishers.noBody()).build();
			httpClient.send(request, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Deletes a session.
	 *
	 * @param sessionId the session id
	 */
	public void deleteSession(String sessionId) {
		try {
			HttpRequest request = request(API + "/session/" + enc(sessionId), Duration.ofSeconds(10)) //$NON-NLS-1$
					.DELETE().build();
			httpClient.send(request, BodyHandlers.discarding());
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static String enc(String segment) {
		return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Builds the {@code { providerID, modelID }} object the opencode server
	 * expects for the {@code model} field (a bare string is rejected with
	 * "Improperly formed request."). Accepts either {@code "provider/model"} or a
	 * bare model id (in which case only {@code modelID} is set and the server
	 * falls back to its default provider).
	 *
	 * @param model the model spec, e.g. {@code "kiro/auto"} or {@code "auto"}
	 * @return the {@code model} JSON node to embed in the message body
	 */
	private static ObjectNode toModelNode(String model) {
		ObjectNode node = MAPPER.createObjectNode();
		int slash = model.indexOf('/');
		if (slash > 0 && slash < model.length() - 1) {
			node.put("providerID", model.substring(0, slash)); //$NON-NLS-1$
			node.put("modelID", model.substring(slash + 1)); //$NON-NLS-1$
		} else {
			node.put("modelID", model); //$NON-NLS-1$
		}
		return node;
	}
}

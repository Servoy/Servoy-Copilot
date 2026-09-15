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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.util.MimeTypes;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Backend-for-frontend (BFF) servlet for the Servoy AI chat UI.
 * <p>
 * Mounted at {@code /servoy_ai/*} on the Servoy Developer's embedded Tomcat.
 * Serves the Angular application shipped in the bundle under
 * {@code /webui-dist/} and proxies the opencode-cli HTTP + SSE API (running on
 * a loopback port) under {@code /servoy_ai/rest_api/**}. Routing everything
 * through a single same-origin servlet avoids the browser same-origin policy
 * (crucial for {@code EventSource}), and centralises project-directory
 * injection, the cold-start readiness gate, and any future auth header
 * injection.
 * </p>
 * <p>
 * The servlet is constructed with suppliers so it can reach bundle runtime
 * state (the opencode port, readiness, the active project directory) without
 * relying on container no-arg instantiation.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpencodeChatServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/** Servlet mount path (also the Angular base href). */
	public static final String BASE_PATH = "/servoy_ai";

	/** Sub-path under the mount reserved for the proxied opencode API. */
	static final String API_PREFIX = "/rest_api";

	/** Bundle-relative directory that holds the built Angular application. */
	private static final String WEBUI_DIST = "/webui-dist";

	/** SSE heartbeat comment interval. */
	private static final long SSE_HEARTBEAT_INTERVAL_MS = 20_000L;
	/**
	 * SSE stall timeout: if no upstream bytes are received within this window the
	 * upstream is torn down. Kept well above the heartbeat interval so a healthy
	 * but idle stream (kept alive by heartbeats) is never killed.
	 */
	private static final long SSE_STALL_TIMEOUT_MS = 120_000L;

	/** Pre-encoded SSE heartbeat comment written on each heartbeat tick. */
	private static final byte[] HEARTBEAT_BYTES = ":heartbeat\n\n".getBytes(StandardCharsets.UTF_8);

	/** Upstream connection timeout for proxied API requests. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** How long the readiness gate holds a request while opencode is starting. */
	private static final long READINESS_HOLD_MAX_MS = 6_000L;

	/**
	 * Request headers that must never be forwarded verbatim to the upstream (hop by
	 * hop or otherwise recomputed by the HTTP client).
	 */
	private static final Set<String> HOP_BY_HOP_REQUEST_HEADERS = Set.of("host", "connection", "keep-alive",
			"proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
			"content-length", "accept-encoding");

	/**
	 * Response headers that must never be copied back to the client (hop by hop or
	 * content-encoding related, since we forward the decoded body).
	 */
	private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of("connection", "keep-alive",
			"proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
			"content-length", "content-encoding");

	private final IntSupplier portSupplier;
	private final BooleanSupplier serverReadySupplier;
	private final transient WaitForServer waitForServer;
	private final Supplier<String> projectPathSupplier;
	private final transient java.util.function.Function<String, URL> resourceResolver;

	private transient volatile HttpClient httpClient;

	/**
	 * Shared, lazily created scheduler driving SSE heartbeats and stall detection
	 * for all active event streams. Daemon-threaded so it never blocks JVM
	 * shutdown.
	 */
	private transient volatile ScheduledExecutorService sseScheduler;

	/**
	 * Waits for the opencode server to become ready.
	 */
	@FunctionalInterface
	public interface WaitForServer {
		/**
		 * Blocks up to {@code timeoutMs} for the server to be ready.
		 *
		 * @return {@code true} if the server became ready within the timeout
		 */
		boolean waitForServer(long timeoutMs) throws InterruptedException;
	}

	/**
	 * @param portSupplier        resolves the opencode loopback port (lazily, per
	 *                            request, as it is only known after startup)
	 * @param serverReadySupplier whether the opencode server is ready now
	 * @param waitForServer       blocks until the server is ready (readiness gate)
	 * @param projectPathSupplier resolves the single active Servoy project
	 *                            directory, or {@code null} if no solution is
	 *                            active
	 * @param resourceResolver    resolves a bundle-relative resource path to a URL;
	 *                            the argument is the path (e.g.
	 *                            {@code /webui-dist/index.html})
	 */
	public OpencodeChatServlet(IntSupplier portSupplier, BooleanSupplier serverReadySupplier,
			WaitForServer waitForServer, Supplier<String> projectPathSupplier,
			java.util.function.Function<String, URL> resourceResolver) {
		this.portSupplier = portSupplier;
		this.serverReadySupplier = serverReadySupplier;
		this.waitForServer = waitForServer;
		this.projectPathSupplier = projectPathSupplier;
		this.resourceResolver = resourceResolver;
	}

	// -----------------------------------------------------------------------
	// Route classification (package-visible for unit tests)
	// -----------------------------------------------------------------------

	/**
	 * @return {@code true} if the given path-info targets the proxied opencode API
	 *         (i.e. starts with {@link #API_PREFIX})
	 */
	static boolean isApiRequest(String pathInfo) {
		if (pathInfo == null)
			return false;
		return pathInfo.equals(API_PREFIX) || pathInfo.startsWith(API_PREFIX + "/");
	}

	/**
	 * @return {@code true} if the given path-info targets the opencode SSE event
	 *         stream ({@code /rest_api/event})
	 */
	static boolean isEventStreamRequest(String pathInfo) {
		if (pathInfo == null)
			return false;
		return pathInfo.equals(API_PREFIX + "/event");
	}

	/**
	 * Strips the {@link #API_PREFIX} from the servlet path-info, yielding the
	 * upstream opencode path (always starting with {@code /}).
	 */
	static String toUpstreamPath(String pathInfo) {
		if (pathInfo == null)
			return "/";
		String rest = pathInfo.substring(API_PREFIX.length());
		if (rest.isEmpty())
			return "/";
		return rest;
	}

	/**
	 * The opencode-cli is directory-scoped; the BFF always injects the single
	 * Servoy project directory so the front-end never carries directory state.
	 *
	 * @param upstreamPath the path (may include an existing query string)
	 * @param directory    the project directory, or {@code null}
	 * @return the path with the directory query param ensured
	 */
	static String injectDirectory(String upstreamPath, String directory) {
		if (directory == null || directory.isEmpty())
			return upstreamPath;
		int q = upstreamPath.indexOf('?');
		String query = q >= 0 ? upstreamPath.substring(q + 1) : "";
		if (hasDirectoryParam(query))
			return upstreamPath;
		String encoded = URLEncoder.encode(directory, StandardCharsets.UTF_8);
		String sep = q >= 0 ? (query.isEmpty() ? "" : "&") : "?";
		return upstreamPath + sep + "directory=" + encoded;
	}

	private static boolean hasDirectoryParam(String query) {
		if (query == null || query.isEmpty())
			return false;
		for (String pair : query.split("&")) {
			if (pair.equals("directory") || pair.startsWith("directory="))
				return true;
		}
		return false;
	}

	// -----------------------------------------------------------------------
	// HttpServlet entry points
	// -----------------------------------------------------------------------

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String pathInfo = req.getPathInfo();
		if (isApiRequest(pathInfo)) {
			handleApi(req, resp, pathInfo);
		} else {
			serveStatic(req, resp, pathInfo);
		}
	}

	// -----------------------------------------------------------------------
	// Static asset serving (Angular dist with SPA fallback)
	// -----------------------------------------------------------------------

	private void serveStatic(HttpServletRequest req, HttpServletResponse resp, String pathInfo) throws IOException {
		if (!"GET".equalsIgnoreCase(req.getMethod()) && !"HEAD".equalsIgnoreCase(req.getMethod())) {
			resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		String assetPath = normalizeAssetPath(pathInfo);
		URL resource = resolveResource(WEBUI_DIST + assetPath);
		boolean spaFallback = false;
		if (resource == null) {
			// SPA deep-link support: unknown non-asset paths fall back to index.html.
			resource = resolveResource(WEBUI_DIST + "/index.html");
			spaFallback = true;
		}
		if (resource == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String contentType = MimeTypes.guessContentTypeFromName(spaFallback ? "index.html" : assetPath);
		if (contentType != null)
			resp.setContentType(contentType);

		// Long-cache hashed assets; never cache index.html so new builds are picked up.
		if (!spaFallback && isHashedAsset(assetPath)) {
			resp.setHeader("Cache-Control", "public, max-age=31536000, immutable");
		} else {
			resp.setHeader("Cache-Control", "no-cache");
		}

		URLConnection uc = resource.openConnection();
		int length = uc.getContentLength();
		if (length >= 0)
			resp.setContentLength(length);
		try (InputStream in = uc.getInputStream(); OutputStream out = resp.getOutputStream()) {
			in.transferTo(out);
			out.flush();
		}
	}

	/**
	 * Maps a servlet path-info to a bundle asset path. An empty or root path-info
	 * (i.e. the app root) resolves to {@code /index.html}.
	 */
	static String normalizeAssetPath(String pathInfo) {
		if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
			return "/index.html";
		}
		// Normalize away any traversal and ensure a single leading slash.
		String cleaned = URI.create("/").resolve(pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo)
				.normalize().getPath();
		if (!cleaned.startsWith("/"))
			cleaned = "/" + cleaned;
		if (cleaned.contains(".."))
			return "/index.html";
		return cleaned;
	}

	/**
	 * A hashed asset carries an 8+ hex chunk before its extension (e.g.
	 * main-AB12CD34.js).
	 */
	static boolean isHashedAsset(String assetPath) {
		return assetPath.matches(".*-[0-9A-Za-z]{8,}\\.[a-zA-Z0-9]+$");
	}

	private URL resolveResource(String bundlePath) {
		return resourceResolver != null ? resourceResolver.apply(bundlePath) : null;
	}

	// -----------------------------------------------------------------------
	// API proxy
	// -----------------------------------------------------------------------

	private void handleApi(HttpServletRequest req, HttpServletResponse resp, String pathInfo) throws IOException {
		// Readiness gate: hold the request briefly while opencode is starting
		// rather than 503-ing immediately (avoids client backoff storms).
		if (!serverReadySupplier.getAsBoolean()) {
			boolean ready = false;
			try {
				ready = waitForServer.waitForServer(READINESS_HOLD_MAX_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!ready) {
				resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				resp.setContentType("application/json");
				resp.getWriter().write("{\"restarting\":true}");
				return;
			}
		}

		String directory = projectPathSupplier.get();
		if (directory == null || directory.isEmpty()) {
			resp.setStatus(HttpServletResponse.SC_CONFLICT);
			resp.setContentType("application/json");
			resp.getWriter().write("{\"error\":\"no active solution\"}");
			return;
		}

		int port = portSupplier.getAsInt();
		if (port <= 0) {
			// Port not resolved yet (server not started) - fail clearly instead of
			// building an invalid upstream URL with a -1 port.
			resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			resp.setContentType("application/json");
			resp.getWriter().write("{\"restarting\":true}");
			return;
		}
		String upstreamPath = injectDirectory(toUpstreamPath(pathInfo) + queryString(req), directory);
		URI target;
		try {
			target = new URI("http", null, "127.0.0.1", port, null, null, null).resolve(upstreamPath);
		} catch (URISyntaxException e) {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "bad upstream uri");
			return;
		}

		if (isEventStreamRequest(pathInfo)) {
			forwardSse(req, resp, target, directory);
		} else {
			forwardRequest(req, resp, target, directory);
		}
	}

	private static String queryString(HttpServletRequest req) {
		String q = req.getQueryString();
		return q != null && !q.isEmpty() ? "?" + q : "";
	}

	private void forwardRequest(HttpServletRequest req, HttpServletResponse resp, URI target, String directory)
			throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(4));

		byte[] body = readBody(req);
		String method = req.getMethod().toUpperCase(Locale.ROOT);
		if ("GET".equals(method) || "HEAD".equals(method) || body == null || body.length == 0) {
			builder.method(method, BodyPublishers.noBody());
		} else {
			builder.method(method, BodyPublishers.ofByteArray(body));
		}

		copyRequestHeaders(req, builder);
		// opencode-cli also accepts a directory header; inject it alongside the
		// query param so directory-scoping is unconditional.
		builder.header("x-opencode-directory", directory);
		builder.header("accept-encoding", "identity");

		HttpResponse<InputStream> upstream;
		try {
			upstream = client().send(builder.build(), BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return;
		} catch (IOException e) {
			resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			resp.setContentType("application/json");
			resp.getWriter().write("{\"error\":\"opencode service unavailable\"}");
			return;
		}

		resp.setStatus(upstream.statusCode());
		copyResponseHeaders(upstream, resp);
		try (InputStream in = upstream.body(); OutputStream out = resp.getOutputStream()) {
			in.transferTo(out);
			out.flush();
		}
	}

	// -----------------------------------------------------------------------
	// SSE streaming
	// -----------------------------------------------------------------------

	private void forwardSse(HttpServletRequest req, HttpServletResponse resp, URI target, String directory) {
		AsyncContext async = req.startAsync();
		// The async context itself does not time out; the pump's own stall deadline
		// and the client-disconnect AsyncListener manage the lifecycle.
		async.setTimeout(0);

		Thread pump = new Thread(() -> pumpSse(async, resp, target, directory), "opencode-sse-" + target.getPort());
		pump.setDaemon(true);
		pump.start();
	}

	private void pumpSse(AsyncContext async, HttpServletResponse resp, URI target, String directory) {
		// Guards teardown so it runs exactly once regardless of which path triggers it
		// (normal end, upstream error, client disconnect, stall, or heartbeat failure).
		AtomicBoolean closed = new AtomicBoolean(false);
		// Last time we successfully observed activity from the upstream; used by the
		// stall watchdog to tear down a wedged (silent) upstream.
		long[] lastActivity = { System.currentTimeMillis() };
		// Serialises writes to the single response output stream between the pump
		// thread (data) and the scheduler thread (heartbeats).
		Object writeLock = new Object();

		CompletableFuture<HttpResponse<InputStream>> upstreamFuture;
		try {
			HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofDays(1))
					.header("accept", "text/event-stream").header("cache-control", "no-cache")
					.header("x-opencode-directory", directory).GET().build();
			upstreamFuture = client().sendAsync(request, BodyHandlers.ofInputStream());
		} catch (RuntimeException e) {
			ServoyLog.logError("OpencodeChatServlet: SSE upstream request build failed for " + target, e);
			safeComplete(async);
			return;
		}

		// Tears down everything once: cancels the upstream future (breaking a parked
		// blocking read), closes the upstream body, and completes the async context.
		Runnable teardown = () -> {
			if (!closed.compareAndSet(false, true))
				return;
			upstreamFuture.cancel(true);
			upstreamFuture.thenAccept(r -> {
				try {
					r.body().close();
				} catch (IOException ignored) {
					// upstream already closed
				}
			});
			safeComplete(async);
		};

		// When the client disconnects (or the async context errors/times out), Tomcat
		// fires this listener; tearing down cancels the upstream and unblocks the pump.
		async.addListener(new AsyncListener() {
			@Override
			public void onComplete(AsyncEvent event) {
				closed.set(true);
			}

			@Override
			public void onTimeout(AsyncEvent event) {
				teardown.run();
			}

			@Override
			public void onError(AsyncEvent event) {
				teardown.run();
			}

			@Override
			public void onStartAsync(AsyncEvent event) {
				// no-op
			}
		});

		HttpResponse<InputStream> upstream;
		try {
			upstream = upstreamFuture.join();
		} catch (CancellationException | CompletionException e) {
			// Cancelled (client already gone) or connection failed - normal for SSE.
			teardown.run();
			return;
		}

		ScheduledFuture<?> heartbeat = null;
		try {
			resp.setStatus(upstream.statusCode());
			resp.setContentType("text/event-stream");
			resp.setHeader("Cache-Control", "no-cache");
			resp.setHeader("Connection", "keep-alive");
			resp.setHeader("X-Accel-Buffering", "no");

			ServletOutputStream out = resp.getOutputStream();
			resp.flushBuffer();

			// Independent heartbeat + stall watchdog. Heartbeats keep the connection
			// alive during idle periods; a heartbeat write that fails (client gone)
			// tears everything down, as does an upstream that goes silent past the
			// stall deadline.
			heartbeat = scheduler().scheduleWithFixedDelay(() -> {
				if (closed.get())
					return;
				if (System.currentTimeMillis() - lastActivity[0] > SSE_STALL_TIMEOUT_MS) {
					teardown.run();
					return;
				}
				try {
					synchronized (writeLock) {
						out.write(HEARTBEAT_BYTES);
						out.flush();
					}
				} catch (IOException e) {
					// Client disconnected - discovered on the heartbeat write.
					teardown.run();
				}
			}, SSE_HEARTBEAT_INTERVAL_MS, SSE_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

			byte[] buffer = new byte[8192];
			try (InputStream in = upstream.body()) {
				int read;
				while (!closed.get() && (read = in.read(buffer)) != -1) {
					if (read > 0) {
						synchronized (writeLock) {
							out.write(buffer, 0, read);
							out.flush();
						}
						lastActivity[0] = System.currentTimeMillis();
					}
				}
			}
		} catch (IOException e) {
			// Client disconnected or upstream dropped - normal for SSE.
		} finally {
			if (heartbeat != null)
				heartbeat.cancel(false);
			teardown.run();
		}
	}

	private static void safeComplete(AsyncContext async) {
		try {
			async.complete();
		} catch (RuntimeException ignored) {
			// async already completed
		}
	}

	// -----------------------------------------------------------------------
	// Header / body helpers
	// -----------------------------------------------------------------------

	private static byte[] readBody(HttpServletRequest req) throws IOException {
		try (InputStream in = req.getInputStream()) {
			return in.readAllBytes();
		}
	}

	private static void copyRequestHeaders(HttpServletRequest req, HttpRequest.Builder builder) {
		java.util.Enumeration<String> names = req.getHeaderNames();
		if (names == null)
			return;
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			if (HOP_BY_HOP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
				continue;
			java.util.Enumeration<String> values = req.getHeaders(name);
			while (values.hasMoreElements()) {
				try {
					builder.header(name, values.nextElement());
				} catch (IllegalArgumentException ignored) {
					// restricted header - skip
				}
			}
		}
	}

	private static void copyResponseHeaders(HttpResponse<InputStream> upstream, HttpServletResponse resp) {
		upstream.headers().map().forEach((name, values) -> {
			if (HOP_BY_HOP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
				return;
			for (String value : values) {
				resp.addHeader(name, value);
			}
		});
	}

	private HttpClient client() {
		HttpClient c = httpClient;
		if (c == null) {
			synchronized (this) {
				c = httpClient;
				if (c == null) {
					// Pin to HTTP/1.1: opencode-cli serves cleartext HTTP/1.1 and
					// does not support the HTTP/2 (h2c) upgrade the JDK client
					// attempts by default. With the default (HTTP_2) version every
					// request that carries a body (POST /session, /prompt_async, ...)
					// hangs until it times out, while bodyless GETs happen to succeed.
					// Forcing 1.1 avoids the failed upgrade handshake entirely.
					c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT)
							.build();
					httpClient = c;
				}
			}
		}
		return c;
	}

	private ScheduledExecutorService scheduler() {
		ScheduledExecutorService s = sseScheduler;
		if (s == null) {
			synchronized (this) {
				s = sseScheduler;
				if (s == null) {
					ThreadFactory tf = r -> {
						Thread t = new Thread(r, "opencode-sse-heartbeat");
						t.setDaemon(true);
						return t;
					};
					s = Executors.newSingleThreadScheduledExecutor(tf);
					sseScheduler = s;
				}
			}
		}
		return s;
	}

	@Override
	public void destroy() {
		ScheduledExecutorService s = sseScheduler;
		if (s != null) {
			s.shutdownNow();
			sseScheduler = null;
		}
		super.destroy();
	}
}

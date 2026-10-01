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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.Activator;

/**
 * Exports an opencode session via the 2.x HTTP API
 * ({@code GET /api/experimental/session/:id/export}), strips a leading non-JSON
 * line (opencode #12130 quirk), and parses the result into a
 * {@link SessionTranscript}. Replaces the earlier shell-based
 * {@code RunOpencodeCommand.exportSession} approach.
 */
public final class SessionExporter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final int port;
	private final HttpClient httpClient;
	private final String authHeader;

	/**
	 * @param port the port the embedded opencode server listens on
	 */
	public SessionExporter(int port) {
		this.port = port;
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10)).build();
		this.authHeader = resolveAuthHeader();
	}

	/**
	 * Exports the given session and parses it into a transcript.
	 *
	 * @param sessionId the session to export
	 * @return the parsed transcript
	 * @throws IOException          if the request fails or the output cannot be
	 *                              parsed
	 * @throws InterruptedException if interrupted while waiting
	 */
	public SessionTranscript export(String sessionId) throws IOException, InterruptedException {
		String json = exportRaw(sessionId);
		JsonNode node = MAPPER.readTree(BaselineLoader.stripLeadingNonJson(json));
		return SessionTranscript.fromExport(node);
	}

	/**
	 * Exports the given session and returns the cleaned JSON string (leading
	 * non-JSON line stripped). Used by the recorder to persist {@code export.json}.
	 *
	 * @param sessionId the session to export
	 * @return the export JSON text
	 * @throws IOException          if the request fails
	 * @throws InterruptedException if interrupted while waiting
	 */
	public String exportRaw(String sessionId) throws IOException, InterruptedException {
		String enc = java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8);
		String url = "http://127.0.0.1:" + port + "/api/experimental/session/" + enc + "/export"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30)).GET();
		if (authHeader != null) {
			builder.header("authorization", authHeader); //$NON-NLS-1$
		}
		HttpResponse<String> response = httpClient.send(builder.build(), BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("export failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		// Unwrap the 2.x { data: ... } envelope when present.
		String body = response.body();
		try {
			JsonNode parsed = MAPPER.readTree(body);
			if (parsed.has("data")) { //$NON-NLS-1$
				body = MAPPER.writeValueAsString(parsed.get("data")); //$NON-NLS-1$
			}
		} catch (Exception ignore) {
			// body is already raw JSON, use as-is
		}
		return BaselineLoader.stripLeadingNonJson(body);
	}

	/**
	 * Resolves the server port from the opencode Activator.
	 *
	 * @return the port, or -1 if unavailable
	 */
	public static int resolvePort() {
		Activator activator = Activator.getInstance();
		return activator != null && activator.isServerReady() ? activator.getServerPort() : -1;
	}

	private static String resolveAuthHeader() {
		try {
			Activator activator = Activator.getInstance();
			String password = activator != null ? activator.getServerPassword() : null;
			if (password == null || password.isBlank()) {
				return null;
			}
			String credentials = "opencode:" + password; //$NON-NLS-1$
			return "Basic " + java.util.Base64.getEncoder() //$NON-NLS-1$
					.encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (RuntimeException | LinkageError ex) {
			return null;
		}
	}
}

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

package com.servoy.eclipse.opencode.skilltest.headless;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Headless equivalent of {@code ServoyLoginDialog.getLoginToken}: performs the
 * Servoy Cloud login over HTTP (no UI) and sets the {@code GENAI_API_KEY} and
 * {@code SERVOY_SKILLS_ZIP} system properties the opencode server requires.
 * <p>
 * The opencode/kiro credentials are issued by the Servoy Cloud login, not
 * hardcoded: a Basic-auth POST to the "crowd" auth endpoint returns a JSON
 * {@code { token, svy_ai_key, skill_endpoint }}, from which:
 * </p>
 * <ul>
 * <li>{@code GENAI_API_KEY} = {@code svy_ai_key} (the provider key), and</li>
 * <li>{@code SERVOY_SKILLS_ZIP} = {@code <apiBase>/<skill_endpoint>?loginToken=<token>}
 * (the skills-zip URL, authenticated with the login token).</li>
 * </ul>
 * <p>
 * Because those tokens are session-bound (note the {@code ?loginToken=} on the
 * skills-zip URL), CI logs in immediately before each run rather than caching a
 * static key. The opencode bundle reads both values via
 * {@code System.getProperty}, so this sets system properties (not env vars).
 * </p>
 */
public final class CloudLogin {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Base of the Servoy Cloud REST API; overridable via {@code -Dservoy.api.url}. */
	private static final String API_BASE = System.getProperty("servoy.api.url", //$NON-NLS-1$
			"https://middleware-prod.unifiedui.servoy-cloud.eu") + "/servoy-service/rest_ws/api/"; //$NON-NLS-1$ //$NON-NLS-2$

	/** The developer auth ("crowd") endpoint that mints the login token + AI key. */
	private static final String CROWD_URL = API_BASE + "developer_auth/getAuthToken"; //$NON-NLS-1$

	private CloudLogin() {
	}

	/**
	 * Logs in to Servoy Cloud with the given service-account credentials and sets
	 * {@code GENAI_API_KEY} / {@code SERVOY_SKILLS_ZIP} system properties from the
	 * response. Existing values are respected: if {@code GENAI_API_KEY} is already
	 * set (e.g. passed via {@code -D}) the login is skipped entirely, allowing a
	 * pre-provisioned key to override.
	 *
	 * @param username the Cloud service-account username
	 * @param password the Cloud service-account password
	 * @throws IOException          if login fails or the response is malformed
	 * @throws InterruptedException if interrupted while waiting for the response
	 */
	public static void loginAndSetProperties(String username, String password)
			throws IOException, InterruptedException {
		if (isSet("GENAI_API_KEY")) { //$NON-NLS-1$
			// Credentials already provided out-of-band; don't overwrite.
			return;
		}
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IOException("Servoy Cloud login requires -cloudUser and -cloudPass (or a pre-set GENAI_API_KEY)"); //$NON-NLS-1$
		}

		String auth = username + ":" + password; //$NON-NLS-1$
		String authHeader = "Basic " + Base64.getEncoder() //$NON-NLS-1$
				.encodeToString(auth.getBytes(StandardCharsets.ISO_8859_1));

		String crowdUrl = System.getProperty("servoy.test.crowd.url", CROWD_URL); //$NON-NLS-1$
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(30)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(crowdUrl))
				.timeout(Duration.ofSeconds(60))
				.header("Authorization", authHeader) //$NON-NLS-1$
				.header("Accept", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
				.GET().build();

		HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Servoy Cloud login failed: HTTP " + response.statusCode() + " " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
		}

		JsonNode json = MAPPER.readTree(response.body());
		String token = text(json, "token"); //$NON-NLS-1$
		String svyAiKey = text(json, "svy_ai_key"); //$NON-NLS-1$
		String skillEndpoint = text(json, "skill_endpoint"); //$NON-NLS-1$

		if (svyAiKey == null || svyAiKey.isBlank()) {
			throw new IOException("Servoy Cloud login succeeded but returned no 'svy_ai_key'"); //$NON-NLS-1$
		}
		System.setProperty("GENAI_API_KEY", svyAiKey); //$NON-NLS-1$

		if (!isSet("SERVOY_SKILLS_ZIP") && skillEndpoint != null && !skillEndpoint.isBlank() && token != null) { //$NON-NLS-1$
			System.setProperty("SERVOY_SKILLS_ZIP", API_BASE + skillEndpoint + "?loginToken=" + token); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static boolean isSet(String prop) {
		String v = System.getProperty(prop);
		return v != null && !v.isBlank();
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v != null && !v.isNull() ? v.asText() : null;
	}
}

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

import static com.servoy.eclipse.opencode.tests.plainjunit.integration.ServletCall.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.tests.plainjunit.integration.ServletCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

/**
 * Drives {@link OpencodeChatServlet} in front of a <em>real</em>
 * {@code opencode serve} process (no LLM provider, no credentials needed) and
 * checks the session create/list round trip that SVY-21461 broke.
 * <p>
 * opencode 2.x reads the target directory of {@code session.create} from the
 * JSON body ({@code location.directory}) and falls back to its own working
 * directory, while {@code session.list} filters on the {@code ?directory=}
 * query parameter. A servlet that does not splice the project directory into
 * the create body makes a freshly created session vanish from the list. A fake
 * upstream cannot catch a change in that contract; only the real opencode can.
 * See {@link RealOpencodeServer} for how opencode is obtained and started.
 * </p>
 *
 * @author mvid
 * @since 2026.12
 */
class OpencodeChatServletRealOpencodeTest {
	private static final Pattern SESSION_ID = Pattern.compile("\"id\"\\s*:\\s*\"(ses_[^\"]+)\"");

	@TempDir
	static Path sandbox;

	private static RealOpencodeServer opencode;

	@BeforeAll
	static void startOpencode() throws Exception {
		opencode = RealOpencodeServer.start(sandbox, null);
	}

	@AfterAll
	static void stopOpencode() throws InterruptedException {
		if (opencode != null)
			opencode.close();
	}

	@Test
	@DisplayName("session created through the servlet shows up in the servlet's session list, under the project directory")
	void createdSessionIsListedUnderProjectDirectory() throws Exception {
		OpencodeChatServlet servlet = opencode.servlet();

		Reply created = call(servlet, "POST", "/rest_api/session", "{}");
		assertEquals(200, created.status, "create failed: " + created.body());
		String sessionId = sessionId(created.body());
		assertTrue(created.body().contains(location(opencode.projectDir)),
				"session must be created under the project directory " + opencode.projectDir + " but was: "
						+ created.body());

		Reply listed = call(servlet, "GET", "/rest_api/session", null);
		assertEquals(200, listed.status, "list failed: " + listed.body());
		assertTrue(listed.body().contains(sessionId),
				"session " + sessionId + " was created but is missing from the list: " + listed.body());
	}

	@Test
	@DisplayName("a session created by two separate create calls: both are listed (list is not just 'the last one')")
	void everyCreatedSessionIsListed() throws Exception {
		OpencodeChatServlet servlet = opencode.servlet();

		String first = sessionId(call(servlet, "POST", "/rest_api/session", "{}").body());
		String second = sessionId(call(servlet, "POST", "/rest_api/session", "{}").body());

		String list = call(servlet, "GET", "/rest_api/session", null).body();
		assertTrue(list.contains(first), "first session missing from list: " + list);
		assertTrue(list.contains(second), "second session missing from list: " + list);
	}

	@Test
	@DisplayName("control: creating directly on opencode WITHOUT location lands outside the project, so the servlet's body injection is still needed")
	void createWithoutLocationIsNotListedForProject() throws Exception {
		// Bypasses the servlet on purpose. If this ever fails, opencode changed the
		// session.create contract (it now honours ?directory=, or defaults elsewhere)
		// and OpencodeChatServlet#ensureBodyLocation should be re-evaluated.
		String query = "?directory=" + RealOpencodeServer.encode(opencode.projectDir);
		HttpResponse<String> created = opencode.direct("POST", "/api/session" + query, "{}");
		assertEquals(200, created.statusCode(), created.body());
		String sessionId = sessionId(created.body());
		assertFalse(created.body().contains(location(opencode.projectDir)),
				"opencode now places a location-less session under the requested directory: " + created.body());

		HttpResponse<String> listed = opencode.direct("GET", "/api/session" + query, null);
		assertEquals(200, listed.statusCode(), listed.body());
		assertFalse(listed.body().contains(sessionId),
				"location-less session unexpectedly listed for the project directory: " + listed.body());
	}

	private static String sessionId(String json) {
		Matcher m = SESSION_ID.matcher(json);
		assertTrue(m.find(), "no session id in: " + json);
		return m.group(1);
	}

	private static String location(Path dir) {
		return "\"location\":{\"directory\":\"" + OpencodeChatServlet.jsonEscape(dir.toString()) + "\"}";
	}
}

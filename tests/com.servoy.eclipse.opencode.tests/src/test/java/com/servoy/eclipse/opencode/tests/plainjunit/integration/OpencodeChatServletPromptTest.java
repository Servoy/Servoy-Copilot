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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.tests.plainjunit.integration.ServletCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

/**
 * The second iteration of the SVY-21521 harness: a prompt sent through
 * {@link OpencodeChatServlet} to a real opencode gets a model reply, and the
 * session gets a generated title.
 * <p>
 * The model is {@code opencode/big-pickle}, as both {@code model} and
 * {@code small_model} (the one opencode generates titles with). It is the same
 * model the shipped skill4servoy configs already use as {@code small_model}, is
 * free and needs no key, so this runs anywhere opencode's service is reachable.
 * It does depend on that external service: a failure here can be the service,
 * not the servlet, and the message says which call failed.
 * </p>
 * <p>
 * The reply is read by polling {@code GET /session/:id/message} through the
 * servlet, not from the {@code /rest_api/event} SSE stream the chat UI uses
 * (that path needs an async servlet container).
 * </p>
 *
 * @author mvid
 * @since 2026.12
 */
class OpencodeChatServletPromptTest {
	private static final String OPENCODE_CONFIG = """
			{
			  "$schema": "https://opencode.ai/config.json",
			  "model": "opencode/big-pickle",
			  "small_model": "opencode/big-pickle",
			  "autoupdate": false,
			  "share": "disabled",
			  "snapshot": false
			}
			""";
	private static final String PROMPT = "What is the capital of France? Answer in one word.";
	private static final Duration TURN_TIMEOUT = Duration.ofMinutes(2);
	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	static Path sandbox;

	private static RealOpencodeServer opencode;

	@BeforeAll
	static void startOpencode() throws Exception {
		opencode = RealOpencodeServer.start(sandbox, OPENCODE_CONFIG);
	}

	@AfterAll
	static void stopOpencode() throws InterruptedException {
		if (opencode != null)
			opencode.close();
	}

	@Test
	@DisplayName("prompt sent through the servlet gets a model reply, and the session gets a generated title")
	void promptGetsReplyAndTitle() throws Exception {
		OpencodeChatServlet servlet = opencode.servlet();

		Reply created = call(servlet, "POST", "/rest_api/session", "{}");
		assertEquals(200, created.status, "create failed: " + created.body());
		String sessionId = JSON.readTree(created.body()).path("data").path("id").asText();
		assertFalse(sessionId.isEmpty(), "no session id in: " + created.body());

		Reply prompted = call(servlet, "POST", "/rest_api/session/" + sessionId + "/prompt",
				JSON.writeValueAsString(JSON.createObjectNode().put("text", PROMPT)));
		assertEquals(200, prompted.status, "prompt failed: " + prompted.body());

		String reply = null;
		String title = null;
		String messages = "";
		String sessions = "";
		long deadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline && (reply == null || title == null)) {
			Thread.sleep(1000);
			messages = call(servlet, "GET", "/rest_api/session/" + sessionId + "/message", null).body();
			reply = finishedAssistantText(messages);
			sessions = call(servlet, "GET", "/rest_api/session", null).body();
			title = titleOf(sessions, sessionId);
		}

		assertNotNull(reply, "no finished assistant reply within " + TURN_TIMEOUT.toSeconds() + "s. Messages: "
				+ messages + "\nopencode log:\n" + opencode.log());
		assertTrue(reply.toLowerCase(Locale.ROOT).contains("paris"), "unexpected reply to '" + PROMPT + "': " + reply);
		assertNotNull(title, "session " + sessionId + " got no title within " + TURN_TIMEOUT.toSeconds()
				+ "s. Sessions: " + sessions + "\nopencode log:\n" + opencode.log());
	}

	/**
	 * @return the text of a finished assistant message, or {@code null} while the
	 *         turn is still running; fails as soon as the turn reports an error
	 */
	private static String finishedAssistantText(String messagesJson) throws Exception {
		for (JsonNode message : JSON.readTree(messagesJson).path("data")) {
			if (!"assistant".equals(message.path("type").asText()))
				continue;
			if (message.has("error"))
				fail("the model turn failed: " + message);
			if (!message.has("finish"))
				return null;
			StringBuilder text = new StringBuilder();
			for (JsonNode part : message.path("content")) {
				if ("text".equals(part.path("type").asText()))
					text.append(part.path("text").asText());
			}
			return text.isEmpty() ? null : text.toString();
		}
		return null;
	}

	private static String titleOf(String sessionsJson, String sessionId) throws Exception {
		for (JsonNode session : JSON.readTree(sessionsJson).path("data")) {
			if (sessionId.equals(session.path("id").asText())) {
				String title = session.path("title").asText("").trim();
				return title.isEmpty() ? null : title;
			}
		}
		return null;
	}
}

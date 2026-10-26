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

package com.servoy.eclipse.opencode.skilltest.outcome;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.OpencodeHttpClient;

/**
 * Turns a <b>captured</b> set of {@link OutcomeAssertion}s into a <b>robust</b>
 * one by asking the embedded opencode LLM to loosen the agent-chosen parts
 * (SVY-21366).
 * <p>
 * The deterministic capture ({@link ExpectedOutcomeCapture} /
 * {@link FormStructureNormalizer}) produces a faithful but <em>literal</em>
 * expectation: it strips volatile ids and persist names, but copies styleClass
 * values, component texts and tab labels verbatim. Those are the agent's free
 * choice (the exact CSS class name, the span markup around a tab's count) and
 * drift run-to-run, so a literal expectation fails on the next run even though
 * the form is "the same". This class hands that literal capture, plus the UI
 * mockup it was built from, to the LLM and asks it to rewrite the whole
 * {@code expect.persists} into a tolerant form:
 * </p>
 * <ul>
 * <li>replace every {@code <replace-with-component-type>} placeholder with the
 * real component type read from the captured form,</li>
 * <li>turn agent-chosen text / styleClass values into SQL-{@code LIKE} patterns
 * on the stable part ({@code %Items%3%}, {@code %ot-badge%}),</li>
 * <li>keep the stable, spec-meaningful bits literal: {@code typeName},
 * {@code disabled}, the layout nesting and tab order.</li>
 * </ul>
 * <p>
 * The judgment of what is stable vs. volatile lives in the
 * <b>{@code skilltest-expectations} skill</b>, not here - the prompt only tells
 * the session's agent to follow it, so the rules can be tuned in prose without
 * a code change (the "b2" design).
 * </p>
 * <p>
 * Every failure path - no server, server not ready, HTTP error, the model
 * returning something that is not parseable {@code expect.persists} - is
 * swallowed and the <b>original captured assertions are returned unchanged</b>,
 * so authoring never hard-breaks on the LLM: the tester just gets the literal
 * capture to tweak by hand, exactly as before this feature existed.
 * </p>
 */
public final class ExpectationLlmGenerator {

	/** How often the completion wait polls the session status. */
	private static final Duration POLL = Duration.ofSeconds(2);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Matches a fenced ```json ... ``` block (or a bare ``` ... ``` block) in the
	 * assistant's answer, so the JSON can be lifted out of surrounding prose.
	 */
	private static final Pattern FENCED_JSON = Pattern.compile(
			"```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL); //$NON-NLS-1$

	private final OpencodeHttpClient client;
	private final Logger logger;

	/** Sink for a one-line progress / diagnostic message (never {@code null}). */
	public interface Logger {
		void log(String message);
	}

	/** Max time to wait for the opencode server to come ready (ms). */
	private static final long SERVER_WAIT_MS = 120_000L;

	/**
	 * @param port   the embedded opencode server port (from
	 *               {@code Activator.getServerPort()})
	 * @param logger a diagnostic sink; {@code null} is tolerated (becomes a no-op)
	 */
	public ExpectationLlmGenerator(int port, Logger logger) {
		this.client = new OpencodeHttpClient(port);
		this.logger = logger != null ? logger : m -> {
		};
	}

	/**
	 * Creates a generator bound to the running embedded opencode server, starting
	 * it and waiting for readiness if needed (the same path the skill-test runner
	 * uses). Returns {@code null} when the plugin is not in a running workbench or
	 * the server never became ready - the caller then keeps the plain capture.
	 *
	 * @param logger a diagnostic sink; may be {@code null}
	 * @return a ready generator, or {@code null} when no server is available
	 */
	public static ExpectationLlmGenerator ready(Logger logger) {
		com.servoy.eclipse.opencode.Activator activator = com.servoy.eclipse.opencode.Activator.getInstance();
		if (activator == null) {
			return null;
		}
		if (!activator.isServerReady()) {
			activator.ensureServerStarting();
			try {
				activator.waitForServer(SERVER_WAIT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		if (!activator.isServerReady()) {
			return null;
		}
		int port = activator.getServerPort();
		return port > 0 ? new ExpectationLlmGenerator(port, logger) : null;
	}

	/**
	 * Generates a robust {@code expect.persists} from the captured assertions and
	 * the mockup, via the LLM. Blocking: run it off the UI thread.
	 *
	 * @param captured the literal captured assertions (never {@code null})
	 * @param mockupHtml the UI mockup HTML the forms reproduce, or {@code null} /
	 *                   blank when the baseline declares none (generation still
	 *                   runs, from the captured form alone)
	 * @param cancel   polled cooperatively; when it reports {@code true} the run is
	 *                 aborted and the captured assertions are returned
	 * @return the LLM's robust assertions, or - on any failure - {@code captured}
	 *         unchanged (never {@code null})
	 */
	public List<OutcomeAssertion> generate(List<OutcomeAssertion> captured, String mockupHtml, BooleanSupplier cancel) {
		if (captured == null || captured.isEmpty()) {
			return captured;
		}
		BooleanSupplier notCancelled = cancel != null ? cancel : () -> false;
		String sessionId = null;
		try {
			if (!client.health()) {
				logger.log("[skilltest] AI expectation generation skipped: opencode server not ready"); //$NON-NLS-1$
				return captured;
			}
			String prompt = buildPrompt(captured, mockupHtml);
			sessionId = client.createSession("Skill-test expectation generation"); //$NON-NLS-1$
			logger.log("[skilltest] AI expectation generation: prompting session " + sessionId); //$NON-NLS-1$
			// Default agent + server-default model ("kiro auto"): a utility call, not
			// tied to the baseline's replay agent/model.
			client.promptAsync(sessionId, prompt, null, null);
			client.waitForCompletion(sessionId, POLL, notCancelled);
			// Read from the EXPORT (inlines each message's `content`), NOT the
			// /message list, which returns metadata with empty parts in opencode v2
			// (the "model returned no text" bug - SVY-21366).
			String answer = lastAssistantText(client.getExport(sessionId));
			if (answer == null || answer.isBlank()) {
				logger.log("[skilltest] AI expectation generation: the model returned no text; keeping capture"); //$NON-NLS-1$
				return captured;
			}
			List<OutcomeAssertion> parsed = parseAnswer(answer);
			// Deterministic guard: a component's `typeName` is its stable identity and
			// must stay literal. If the model wrongly loosened it (e.g.
			// `bootstrapcomponents-button` -> `bootstrapcomponents-%`), restore it from
			// the captured assertions, which the loosener was told to keep structurally
			// 1:1 (SVY-21366). The skill forbids this, but the skill is advisory; this
			// repair is the hard rule.
			parsed = restoreTypeNames(captured, parsed);
			if (parsed == null || parsed.isEmpty()) {
				// Not silent: say the answer arrived but carried no parseable
				// expect.persists, and show its head so a prompt/format regression is
				// diagnosable from the log rather than looking like "Yes did nothing".
				logger.log("[skilltest] AI expectation generation: model answered (" + answer.length() //$NON-NLS-1$
						+ " chars) but no parseable expect.persists was found; keeping capture. Answer head: " //$NON-NLS-1$
						+ head(answer));
				return captured;
			}
			logger.log("[skilltest] AI expectation generation produced " + parsed.size() + " assertion(s)"); //$NON-NLS-1$ //$NON-NLS-2$
			return parsed;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.log("[skilltest] AI expectation generation cancelled; keeping capture"); //$NON-NLS-1$
			return captured;
		} catch (RuntimeException | java.io.IOException ex) {
			logger.log("[skilltest] AI expectation generation failed (" + ex.getClass().getSimpleName() + ": " //$NON-NLS-1$ //$NON-NLS-2$
					+ ex.getMessage() + "); keeping capture"); //$NON-NLS-1$
			return captured;
		} finally {
			if (sessionId != null) {
				client.deleteSession(sessionId);
			}
		}
	}

	/**
	 * The skill whose prose carries the authoritative loosening rules. Authored in
	 * the {@code skills4servoy} repo and shipped via {@code SERVOY_SKILLS_ZIP},
	 * from which it is extracted to {@code ~/.servoy/opencode/skills/<name>/}. The
	 * prompt names it so the session's agent invokes it; the rules live there, not
	 * here (SVY-21366).
	 */
	static final String SKILL_NAME = "skilltest-expectations"; //$NON-NLS-1$

	/**
	 * Builds the generation prompt. The judgment of what to loosen is authored
	 * once, in the {@code skilltest-expectations} skill (the {@code skills4servoy}
	 * repo): the prompt only tells the session's agent to use that skill, so tuning
	 * the rules needs no code change. The prompt itself carries the per-call
	 * envelope the skill cannot: the task, the captured {@code expect.persists}
	 * JSON to rewrite, the mockup HTML, and the answer-format contract.
	 */
	String buildPrompt(List<OutcomeAssertion> captured, String mockupHtml) {
		String capturedJson;
		try {
			capturedJson = MAPPER.writerWithDefaultPrettyPrinter()
					.writeValueAsString(OutcomeAssertions.toJson(captured));
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			capturedJson = "{\"persists\":[]}"; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		sb.append("[skill-test mode] Use the `skilltest-expectations` skill. Run fully autonomously: do not ask ") //$NON-NLS-1$
				.append("any questions and do not wait for confirmation.\n\n"); //$NON-NLS-1$
		sb.append("I am setting up the expected outcomes for a Servoy skill-test baseline. Below is the expectation ") //$NON-NLS-1$
				.append("captured from the form(s) the agent actually built, with volatile ids and persist names ") //$NON-NLS-1$
				.append("already stripped. It is still too LITERAL and would fail on the next run: styleClass values, ") //$NON-NLS-1$
				.append("component texts and tab labels are the agent's free choice and drift between runs, and every ") //$NON-NLS-1$
				.append("`<replace-with-component-type>` is a placeholder you must replace with the real component ") //$NON-NLS-1$
				.append("type read from the captured form.\n\n"); //$NON-NLS-1$
		sb.append("Following the `skilltest-expectations` skill, rewrite the WHOLE expect.persists into a ROBUST ") //$NON-NLS-1$
				.append("form. You MUST actually loosen the volatile string leaves into SQL-LIKE patterns - ") //$NON-NLS-1$
				.append("returning the input unchanged is wrong.\n\n"); //$NON-NLS-1$
		if (mockupHtml != null && !mockupHtml.isBlank()) {
			sb.append("The UI mockup the form reproduces (the source of truth for which text/counts are intended, ") //$NON-NLS-1$
					.append("vs. which markup is volatile chrome):\n\n```html\n") //$NON-NLS-1$
					.append(mockupHtml.strip()).append("\n```\n\n"); //$NON-NLS-1$
		} else {
			sb.append("(No mockup was provided; work from the captured form alone.)\n\n"); //$NON-NLS-1$
		}
		sb.append("Captured expectation to rewrite:\n\n```json\n").append(capturedJson).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("Answer with EXACTLY ONE fenced ```json block containing the rewritten object ") //$NON-NLS-1$
				.append("`{ \"persists\": [ ... ] }` (same structure, the volatile string leaves turned into LIKE ") //$NON-NLS-1$
				.append("patterns) and nothing else after it."); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Parses the model's answer into assertions: lifts the fenced JSON block (or,
	 * failing that, the outermost {@code { ... }}), reads the {@code persists}
	 * array via {@link OutcomeAssertions#parse}.
	 *
	 * @param answer the assistant's text (may be {@code null})
	 * @return the parsed assertions, or {@code null} when nothing parseable was
	 *         found
	 */
	/**
	 * Restores every wrongly-loosened component {@code typeName} in the loosened
	 * assertions from the captured ones (SVY-21366). A {@code typeName} is a
	 * component's stable identity and must stay literal; when the model emitted a
	 * wildcard there (e.g. {@code bootstrapcomponents-%}) this walks the captured
	 * and loosened trees in lockstep - the loosener keeps the structure 1:1 - and
	 * copies the captured literal {@code typeName} back. Any other loosening the
	 * model did (styleClass, text) is preserved. On a structural mismatch the
	 * unmatched part is left as the model produced it.
	 *
	 * @param captured the literal captured assertions (never {@code null})
	 * @param loosened the model's loosened assertions (may be {@code null})
	 * @return the loosened assertions with literal {@code typeName}s restored, or
	 *         {@code loosened} unchanged when it is {@code null}/empty
	 */
	static List<OutcomeAssertion> restoreTypeNames(List<OutcomeAssertion> captured, List<OutcomeAssertion> loosened) {
		if (loosened == null || loosened.isEmpty() || captured == null || captured.isEmpty()) {
			return loosened;
		}
		List<OutcomeAssertion> out = new java.util.ArrayList<>(loosened.size());
		for (int i = 0; i < loosened.size(); i++) {
			OutcomeAssertion loose = loosened.get(i);
			OutcomeAssertion cap = i < captured.size() ? captured.get(i) : null;
			out.add(repairTypeName(cap, loose));
		}
		return out;
	}

	private static OutcomeAssertion repairTypeName(OutcomeAssertion cap, OutcomeAssertion loose) {
		if (loose == null) {
			return null;
		}
		ArgMatcher capType = cap != null && cap.props() != null ? cap.props().get("typeName") : null; //$NON-NLS-1$
		ArgMatcher looseType = loose.props() != null ? loose.props().get("typeName") : null; //$NON-NLS-1$
		java.util.Map<String, ArgMatcher> props = loose.props();
		if (looseType != null && isWildcarded(looseType) && capType != null && !isWildcarded(capType)) {
			props = new java.util.LinkedHashMap<>(loose.props());
			props.put("typeName", capType); // restore the literal component type //$NON-NLS-1$
		}
		List<OutcomeAssertion> capChildren = cap != null ? cap.children() : java.util.List.of();
		List<OutcomeAssertion> looseChildren = loose.children();
		List<OutcomeAssertion> children = looseChildren;
		if (looseChildren != null && !looseChildren.isEmpty()) {
			children = new java.util.ArrayList<>(looseChildren.size());
			for (int i = 0; i < looseChildren.size(); i++) {
				OutcomeAssertion capChild = capChildren != null && i < capChildren.size() ? capChildren.get(i) : null;
				children.add(repairTypeName(capChild, looseChildren.get(i)));
			}
		}
		return new OutcomeAssertion(loose.kind(), loose.name(), loose.scope(), loose.exists(), props, children,
				loose.structure(), loose.less());
	}

	/** @return true when the matcher's expected value carries a SQL-LIKE wildcard. */
	private static boolean isWildcarded(ArgMatcher matcher) {
		if (matcher == null || matcher.getExpected() == null) {
			return false;
		}
		String v = String.valueOf(matcher.getExpected());
		return v.indexOf('%') >= 0 || v.indexOf('_') >= 0;
	}

	/** The first ~200 chars of a string, single-lined, for a diagnostic log. */
	private static String head(String s) {
		String flat = s.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
		return flat.length() > 200 ? flat.substring(0, 200) + "\u2026" : flat; //$NON-NLS-1$
	}

	List<OutcomeAssertion> parseAnswer(String answer) {
		if (answer == null || answer.isBlank()) {
			return null;
		}
		String jsonText = extractJson(answer);
		if (jsonText == null) {
			return null;
		}
		try {
			JsonNode root = MAPPER.readTree(jsonText);
			// OutcomeAssertions.parse takes the `expect` node ({ persists: [...] }).
			List<OutcomeAssertion> parsed = OutcomeAssertions.parse(root);
			return parsed.isEmpty() ? null : parsed;
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return null;
		}
	}

	/**
	 * Extracts the JSON object text from an assistant answer: prefers a fenced
	 * ```json block, else falls back to the substring from the first {@code &#123;}
	 * to the last {@code &#125;}.
	 */
	static String extractJson(String answer) {
		Matcher m = FENCED_JSON.matcher(answer);
		if (m.find()) {
			return m.group(1);
		}
		int first = answer.indexOf('{');
		int last = answer.lastIndexOf('}');
		if (first >= 0 && last > first) {
			return answer.substring(first, last + 1);
		}
		return null;
	}

	/**
	 * Returns the concatenated {@code text} parts of the last assistant message in
	 * a {@code { messages: [...] }} envelope (the shape
	 * {@link OpencodeHttpClient#getMessages} returns), or {@code null} when there
	 * is no assistant text.
	 */
	static String lastAssistantText(JsonNode messagesEnvelope) {
		if (messagesEnvelope == null) {
			return null;
		}
		JsonNode messages = messagesEnvelope.get("messages"); //$NON-NLS-1$
		if (messages == null || !messages.isArray() || messages.isEmpty()) {
			return null;
		}
		// Walk from the end to the last message whose role is assistant.
		for (int i = messages.size() - 1; i >= 0; i--) {
			JsonNode msg = messages.get(i);
			if (!isAssistant(msg)) {
				continue;
			}
			String text = concatTextParts(msg);
			if (text != null && !text.isBlank()) {
				return text;
			}
		}
		return null;
	}

	private static boolean isAssistant(JsonNode msg) {
		// v1: role on the message; v2: role/type on the message or its `info`.
		JsonNode info = msg.get("info"); //$NON-NLS-1$
		String role = textAt(msg, "role"); //$NON-NLS-1$
		if (role == null && info != null) {
			role = textAt(info, "role"); //$NON-NLS-1$
		}
		String type = textAt(msg, "type"); //$NON-NLS-1$
		return "assistant".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(type); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String concatTextParts(JsonNode msg) {
		// opencode v2 export: text blocks live under `content`; v1: under `parts`.
		JsonNode parts = msg.get("content"); //$NON-NLS-1$
		if (parts == null || !parts.isArray()) {
			parts = msg.get("parts"); //$NON-NLS-1$
		}
		if (parts == null || !parts.isArray()) {
			// Some shapes carry a bare top-level `text`.
			return textAt(msg, "text"); //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode part : parts) {
			String type = textAt(part, "type"); //$NON-NLS-1$
			if (type != null && type.equalsIgnoreCase("text")) { //$NON-NLS-1$
				String t = textAt(part, "text"); //$NON-NLS-1$
				if (t != null) {
					sb.append(t);
				}
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String textAt(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.get(field);
		return v != null && v.isValueNode() && !v.isNull() ? v.asText() : null;
	}
}

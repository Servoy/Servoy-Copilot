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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.OpencodeHttpClient;

/**
 * Grades a skill run's result against a baseline's natural-language acceptance
 * criteria by asking a <b>separate</b> LLM (the "judge") to review the built
 * form's source artifacts - the way an SDD code-review agent signs off, rather
 * than asserting a frozen persist tree a valid run reproduces a different way
 * (SVY-21366).
 * <p>
 * The judge runs as a <b>separate agent in its own session</b>: a fresh
 * {@code createSession} prompted as the dedicated read-only {@link #JUDGE_AGENT
 * Judge} agent, distinct from the builder's agent. The prompt hands it the
 * natural-language criteria and tells it to <b>read for itself</b> the mockup it
 * was asked to reproduce, the built form's {@code .frm}/{@code .js}, the form
 * and solution {@code .less}/{@code css}, and whatever else it needs, then
 * return a structured per-criterion verdict. Nothing is serialized into the
 * prompt - the judge inspects the real files.
 * </p>
 * <p>
 * The separate Reviewer agent + fresh session is what keeps the judge distinct
 * from the run it grades (a different persona reviewing cold, like the SDD
 * reviewer). The judge <b>model</b> need not differ from the builder's - a
 * same-model review in a separate agent/session is a consistency check; for a
 * decorrelated review name a different {@code judgeModel}. When the baseline
 * names none, the runner reuses the builder's model, or the
 * {@link #DEFAULT_JUDGE_MODEL} when the builder model is unknown.
 * </p>
 * <p>
 * Every failure path - no server, HTTP error, an unparseable answer - yields a
 * {@link Verdict} whose {@link Verdict#error()} is set, so the runner can decide
 * (per the baseline's {@code required} flag) whether that fails the baseline or
 * is merely reported.
 * </p>
 */
public final class AcceptanceJudge {

	/** Judge model used only when neither the baseline nor the builder names one. */
	public static final String DEFAULT_JUDGE_MODEL = "kiro/claude-sonnet-4-6"; //$NON-NLS-1$

	/**
	 * The dedicated agent the judge runs as - the read-only {@code Judge} primary
	 * agent (defined in the skills pack), distinct from the builder's agent.
	 * Combined with the fresh session, this is what makes the judge a <b>separate
	 * agent in its own session</b> rather than the ambient default agent grading
	 * the run (SVY-21366). A dedicated primary agent (not the hidden {@code
	 * Reviewer} subagent) is used so the judge can run as a top-level session agent
	 * and still reach the read + {@code servoy-form_getFormLayout} tools it needs.
	 */
	static final String JUDGE_AGENT = "Judge"; //$NON-NLS-1$

	/** How often the completion wait polls the session status. */
	private static final Duration POLL = Duration.ofSeconds(2);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Max time to wait for the opencode server to come ready (ms). */
	private static final long SERVER_WAIT_MS = 120_000L;

	/** Matches a fenced ```json ... ``` block (or a bare ``` ... ``` block). */
	private static final Pattern FENCED_JSON = Pattern.compile(
			"```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL); //$NON-NLS-1$

	/** Sink for a one-line progress / diagnostic message (never {@code null}). */
	public interface Logger {
		void log(String message);
	}

	private final OpencodeHttpClient client;
	private final Logger logger;

	/**
	 * The judge's verdict on one criterion.
	 *
	 * @param criterion the criterion text (echoed back by the judge)
	 * @param pass      whether the judge found it satisfied
	 * @param reason    the judge's short justification
	 */
	public record CriterionResult(String criterion, boolean pass, String reason) {
	}

	/**
	 * The judge's overall verdict.
	 *
	 * @param pass    {@code true} when every graded criterion passed
	 * @param results per-criterion results (never {@code null})
	 * @param error   a transport/parse error description, or {@code null} when the
	 *                judge produced a verdict; when set, {@code pass} is
	 *                {@code false} and the caller decides whether that is fatal
	 * @param raw     the judge's raw answer text, for logging/diagnosis
	 */
	public record Verdict(boolean pass, List<CriterionResult> results, String error, String raw) {

		public Verdict {
			results = results == null ? List.of() : List.copyOf(results);
		}

		public static Verdict error(String message, String raw) {
			return new Verdict(false, List.of(), message, raw);
		}

		/** @return a one-line summary for the run report. */
		public String summary() {
			if (error != null) {
				return "judge error: " + error; //$NON-NLS-1$
			}
			long passed = results.stream().filter(CriterionResult::pass).count();
			return (pass ? "PASS" : "FAIL") + " (" + passed + "/" + results.size() + " criteria)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		}
	}

	/**
	 * @param port   the embedded opencode server port
	 * @param logger a diagnostic sink; {@code null} becomes a no-op
	 */
	public AcceptanceJudge(int port, Logger logger) {
		this.client = new OpencodeHttpClient(port);
		this.logger = logger != null ? logger : m -> {
		};
	}

	/**
	 * Binds a judge to the running embedded opencode server (starting / waiting
	 * for it if needed), or returns {@code null} when no server is available.
	 *
	 * @param logger a diagnostic sink; may be {@code null}
	 * @return a ready judge, or {@code null}
	 */
	public static AcceptanceJudge ready(Logger logger) {
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
		return port > 0 ? new AcceptanceJudge(port, logger) : null;
	}

	/**
	 * Chooses the model to judge with: the configured {@code judgeModel} when set,
	 * otherwise the builder's own model (the judge may reuse it - independence comes
	 * from the separate session, not a different model), and the
	 * {@link #DEFAULT_JUDGE_MODEL} only when neither is known.
	 *
	 * @param configured   the baseline's {@code judgeModel} (may be {@code null})
	 * @param builderModel the model that built the form (may be {@code null} =
	 *                     server default)
	 * @return the judge model to use (never {@code null})
	 */
	static String resolveJudgeModel(String configured, String builderModel) {
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		if (builderModel != null && !builderModel.isBlank()) {
			return builderModel.trim();
		}
		return DEFAULT_JUDGE_MODEL;
	}

	/**
	 * Runs the judge against the criteria and returns its verdict. Blocking: run
	 * off the UI thread.
	 *
	 * @param formName     the form the run was asked to build (e.g.
	 *                     {@code order_tabs})
	 * @param mockupPath   the working-dir-relative path of the mockup the form
	 *                     reproduces (e.g. {@code specs/03_order_tabs.html}), or
	 *                     {@code null} when the baseline declares none
	 * @param criteria     the natural-language acceptance criteria (never empty)
	 * @param judgeModel   the baseline's configured judge model (may be
	 *                     {@code null})
	 * @param builderModel the model that built the form (may be {@code null})
	 * @param cancel       polled cooperatively; aborts the run when {@code true}
	 * @return the verdict; on any failure an {@link Verdict#error error} verdict
	 */
	public Verdict judge(String formName, String mockupPath, List<String> criteria, String judgeModel,
			String builderModel, BooleanSupplier cancel) {
		if (criteria == null || criteria.isEmpty()) {
			return Verdict.error("no criteria to judge", null); //$NON-NLS-1$
		}
		BooleanSupplier notCancelled = cancel != null ? cancel : () -> false;
		String model = resolveJudgeModel(judgeModel, builderModel);
		String sessionId = null;
		try {
			if (!client.health()) {
				return Verdict.error("opencode server not ready", null); //$NON-NLS-1$
			}
			String prompt = buildPrompt(formName, mockupPath, criteria);
			sessionId = client.createSession("Skill-test acceptance judge"); //$NON-NLS-1$
			logger.log("[skilltest] acceptance judge: grading " + criteria.size() + " criterion(s) for form '" //$NON-NLS-1$ //$NON-NLS-2$
					+ formName + "' as the " + JUDGE_AGENT + " agent, model " + model + " (session " + sessionId //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ ")"); //$NON-NLS-1$
			// Run as the dedicated Judge agent in this fresh session (separate
			// agent + separate session). The model may equal the builder's.
			client.promptAsync(sessionId, prompt, JUDGE_AGENT, model);
			client.waitForCompletion(sessionId, POLL, notCancelled);
			// Read the verdict from the EXPORT (inlines each message's `content`),
			// NOT the /message list endpoint, which returns metadata with empty parts
			// in opencode v2 - the cause of an earlier "judge returned no text" even
			// though the judge had answered (SVY-21366).
			com.fasterxml.jackson.databind.JsonNode messages = client.getExport(sessionId);
			String answer = lastAssistantText(messages);
			if (answer == null || answer.isBlank()) {
				// Not opaque: describe what the session actually returned (message count,
				// roles, part types, any error) so a no-text run is diagnosable instead of
				// just "returned no text".
				String shape = describeMessages(messages);
				logger.log("[skilltest] acceptance judge: no assistant text with model " + model //$NON-NLS-1$
						+ "; session shape: " + shape); //$NON-NLS-1$
				return Verdict.error("judge returned no text (model " + model + "); " + shape, null); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Verdict v = parseVerdict(answer);
			logger.log("[skilltest] acceptance judge verdict: " + v.summary()); //$NON-NLS-1$
			return v;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return Verdict.error("cancelled", null); //$NON-NLS-1$
		} catch (RuntimeException | java.io.IOException ex) {
			return Verdict.error(ex.getClass().getSimpleName() + ": " + ex.getMessage(), null); //$NON-NLS-1$
		} finally {
			if (sessionId != null) {
				client.deleteSession(sessionId);
			}
		}
	}

	/**
	 * Builds the judge prompt: it names the form and mockup, lists the criteria,
	 * and instructs the judge to READ the relevant source files itself (the
	 * {@code .frm}, the form and solution {@code .less}/{@code css}, the form's
	 * {@code .js} incl. its {@code onShow} handler) and answer with one fenced
	 * JSON verdict. No file contents are embedded - the judge inspects them.
	 */
	String buildPrompt(String formName, String mockupPath, List<String> criteria) {
		StringBuilder sb = new StringBuilder();
		sb.append("[skill-test mode] You are an automated acceptance judge. Run fully autonomously: do not ask ") //$NON-NLS-1$
				.append("questions, do not wait for confirmation, do not modify any file. If a relevant skill ") //$NON-NLS-1$
				.append("(e.g. skilltest-expectations) is available, you may use it.\n\n"); //$NON-NLS-1$
		boolean haveForm = formName != null && !formName.isBlank();
		if (haveForm) {
			sb.append("A previous agent was asked to build a Servoy form named `").append(formName) //$NON-NLS-1$
					.append("`. "); //$NON-NLS-1$
		} else {
			sb.append("A previous agent was asked to build a Servoy form in the active solution (its name was its ") //$NON-NLS-1$
					.append("own choice - discover it with your tools, e.g. list the solution's forms). "); //$NON-NLS-1$
		}
		sb.append("Your job is to decide, criterion by criterion, whether the built form meets the acceptance ") //$NON-NLS-1$
				.append("criteria below. Judge the RESULT, not how it was built.\n\n"); //$NON-NLS-1$
		sb.append("Inspect the actual result yourself using your tools - do not assume. As needed:\n"); //$NON-NLS-1$
		if (haveForm) {
			sb.append("  - read the form `forms/").append(formName).append(".frm` (components, tab panel, tabs);\n") //$NON-NLS-1$ //$NON-NLS-2$
					.append("  - read the form's `forms/").append(formName).append(".js` (handlers such as `onShow`);\n") //$NON-NLS-1$ //$NON-NLS-2$
					.append("  - read the form's `forms/").append(formName).append(".less` and the solution-level ") //$NON-NLS-1$ //$NON-NLS-2$
					.append("`.less`/`.css` (styleClasses, colours, SVG icon masks);\n"); //$NON-NLS-1$
		} else {
			sb.append("  - locate the created form, then read its `.frm`, its `.js` (handlers such as `onShow`), ") //$NON-NLS-1$
					.append("and the form + solution `.less`/`.css` (styleClasses, colours, SVG icon masks);\n"); //$NON-NLS-1$
		}
		sb.append("  - OPTIONALLY, to judge VISUAL fidelity, call the `servoy-form_getFormLayout` tool with ") //$NON-NLS-1$
				.append("`screenshot=true` for the form to see how it actually renders (design-time, no data), ") //$NON-NLS-1$
				.append("and compare the pixels") //$NON-NLS-1$
				.append(mockupPath != null && !mockupPath.isBlank() ? " to the mockup." : "."); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append('\n');
		if (mockupPath != null && !mockupPath.isBlank()) {
			sb.append("  - read the mockup it must reproduce, at `").append(mockupPath.trim()).append("`.\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append('\n');
		sb.append("Acceptance criteria:\n"); //$NON-NLS-1$
		for (int i = 0; i < criteria.size(); i++) {
			sb.append(i + 1).append(". ").append(criteria.get(i)).append('\n'); //$NON-NLS-1$
		}
		sb.append('\n');
		sb.append("Grade each criterion independently as pass or fail, with a short concrete reason citing what ") //$NON-NLS-1$
				.append("you found in the sources. Be strict: a criterion the sources do not clearly satisfy is a ") //$NON-NLS-1$
				.append("fail. Answer with EXACTLY ONE fenced ```json block and nothing after it, of the form:\n\n") //$NON-NLS-1$
				.append("```json\n") //$NON-NLS-1$
				.append("{\n  \"overall\": \"pass\" | \"fail\",\n  \"criteria\": [\n") //$NON-NLS-1$
				.append("    { \"criterion\": \"<the criterion text>\", \"pass\": true | false, \"reason\": \"<why>\" }\n") //$NON-NLS-1$
				.append("  ]\n}\n```\n"); //$NON-NLS-1$
		sb.append("`overall` must be `pass` only if every criterion passes."); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Parses the judge's answer into a {@link Verdict}: lifts the fenced JSON (or
	 * the outermost {@code { ... }}), reads {@code overall} and the
	 * {@code criteria} array. When {@code overall} is absent it is derived from the
	 * per-criterion results (all must pass).
	 *
	 * @param answer the judge's text (may be {@code null})
	 * @return a verdict; an {@link Verdict#error error} verdict when nothing
	 *         parseable was found
	 */
	Verdict parseVerdict(String answer) {
		String json = extractJson(answer);
		if (json == null) {
			return Verdict.error("no JSON verdict found in the judge's answer", answer); //$NON-NLS-1$
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			List<CriterionResult> results = new ArrayList<>();
			JsonNode arr = root.get("criteria"); //$NON-NLS-1$
			if (arr != null && arr.isArray()) {
				for (JsonNode c : arr) {
					String criterion = textAt(c, "criterion"); //$NON-NLS-1$
					boolean pass = c.path("pass").asBoolean(false); //$NON-NLS-1$
					String reason = textAt(c, "reason"); //$NON-NLS-1$
					results.add(new CriterionResult(criterion, pass, reason));
				}
			}
			boolean overall;
			JsonNode overallNode = root.get("overall"); //$NON-NLS-1$
			if (overallNode != null && !overallNode.isNull()) {
				overall = overallNode.isBoolean() ? overallNode.booleanValue()
						: "pass".equalsIgnoreCase(overallNode.asText().trim()); //$NON-NLS-1$
			} else {
				overall = !results.isEmpty() && results.stream().allMatch(CriterionResult::pass);
			}
			// A stated overall=pass is still only trustworthy if no criterion failed.
			if (overall && results.stream().anyMatch(r -> !r.pass())) {
				overall = false;
			}
			return new Verdict(overall, results, null, answer);
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return Verdict.error("could not parse the judge's JSON verdict: " + e.getMessage(), answer); //$NON-NLS-1$
		}
	}

	/**
	 * A compact description of a {@code { messages: [...] }} envelope for the
	 * no-assistant-text diagnostic: message count and, for the last message, its
	 * role/type, its part types, and any {@code error} field. Lets a no-text run be
	 * told apart (empty session vs tool-calls-only vs an error turn vs a parse
	 * mismatch) without dumping the whole transcript.
	 */
	static String describeMessages(JsonNode messagesEnvelope) {
		if (messagesEnvelope == null) {
			return "no messages envelope"; //$NON-NLS-1$
		}
		JsonNode messages = messagesEnvelope.get("messages"); //$NON-NLS-1$
		if (messages == null || !messages.isArray()) {
			return "no messages array"; //$NON-NLS-1$
		}
		if (messages.isEmpty()) {
			return "0 messages"; //$NON-NLS-1$
		}
		JsonNode last = messages.get(messages.size() - 1);
		JsonNode info = last.get("info"); //$NON-NLS-1$
		String role = textAt(last, "role"); //$NON-NLS-1$
		if (role == null && info != null) {
			role = textAt(info, "role"); //$NON-NLS-1$
		}
		String type = textAt(last, "type"); //$NON-NLS-1$
		String error = textAt(last, "error"); //$NON-NLS-1$
		if (error == null && info != null) {
			error = textAt(info, "error"); //$NON-NLS-1$
		}
		StringBuilder partTypes = new StringBuilder();
		JsonNode parts = last.get("parts"); //$NON-NLS-1$
		if (parts != null && parts.isArray()) {
			for (JsonNode p : parts) {
				if (partTypes.length() > 0) {
					partTypes.append(','); //$NON-NLS-1$
				}
				partTypes.append(textAt(p, "type")); //$NON-NLS-1$
			}
		}
		// Enumerate EVERY message's type + part-types (+ whether any text part carries
		// content), so a no-text run is pinned down: assistant text present but
		// mis-parsed, vs a run that ended on tool calls with no final text answer.
		StringBuilder all = new StringBuilder();
		for (int i = 0; i < messages.size(); i++) {
			JsonNode m = messages.get(i);
			JsonNode mi = m.get("info"); //$NON-NLS-1$
			String mrole = textAt(m, "role"); //$NON-NLS-1$
			if (mrole == null && mi != null) {
				mrole = textAt(mi, "role"); //$NON-NLS-1$
			}
			String mtype = textAt(m, "type"); //$NON-NLS-1$
			StringBuilder pt = new StringBuilder();
			boolean anyText = false;
			JsonNode mparts = m.get("parts"); //$NON-NLS-1$
			if (mparts != null && mparts.isArray()) {
				for (JsonNode p : mparts) {
					String ptype = textAt(p, "type"); //$NON-NLS-1$
					if (pt.length() > 0) {
						pt.append(','); //$NON-NLS-1$
					}
					pt.append(ptype);
					if ("text".equalsIgnoreCase(ptype)) { //$NON-NLS-1$
						String t = textAt(p, "text"); //$NON-NLS-1$
						if (t != null && !t.isBlank()) {
							anyText = true;
						}
					}
				}
			}
			all.append("\n    #").append(i).append(" role=").append(mrole).append(" type=").append(mtype) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append(" parts=[").append(pt).append("]").append(anyText ? " hasText" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		return messages.size() + " message(s); last role=" + role + " type=" + type //$NON-NLS-1$ //$NON-NLS-2$
				+ " parts=[" + partTypes + "]" //$NON-NLS-1$ //$NON-NLS-2$
				+ (error != null ? " error=" + error : "") //$NON-NLS-1$ //$NON-NLS-2$
				+ all;
	}

	/** Prefers a fenced ```json block, else the substring from first '{' to last '}'. */
	static String extractJson(String answer) {
		if (answer == null || answer.isBlank()) {
			return null;
		}
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
	 * Concatenated {@code text} parts of the last assistant message in the
	 * {@code { messages: [...] }} envelope {@link OpencodeHttpClient#getMessages}
	 * returns, or {@code null} when there is no assistant text.
	 */
	static String lastAssistantText(JsonNode messagesEnvelope) {
		if (messagesEnvelope == null) {
			return null;
		}
		JsonNode messages = messagesEnvelope.get("messages"); //$NON-NLS-1$
		if (messages == null || !messages.isArray() || messages.isEmpty()) {
			return null;
		}
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

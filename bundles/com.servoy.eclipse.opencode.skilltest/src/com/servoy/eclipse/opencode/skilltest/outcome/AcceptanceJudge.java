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
 * criteria by asking a <b>separate</b> LLM (the "judge") to review the produced
 * artifact's source files - the way an SDD code-review agent signs off, rather
 * than asserting a frozen persist tree a valid run reproduces a different way
 * (SVY-21366).
 * <p>
 * The judge runs as a <b>separate agent in its own session</b>: a fresh
 * {@code createSession} prompted as the dedicated read-only {@link #JUDGE_AGENT
 * Judge} agent, distinct from the builder's agent. The prompt hands it the
 * natural-language criteria (plus the targeted artifact name and an optional
 * mockup path) and the {@code Judge} agent's own instructions tell it to
 * <b>read for itself</b> the produced artifact's source files - a form's
 * {@code .frm}/{@code .js}/{@code .less}, or any other persist - and whatever
 * else it needs, then return a structured per-criterion verdict. Nothing is
 * serialized into the prompt - the judge inspects the real files.
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
	 * and still reach the read + component-spec tools it needs.
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
	 * @param builderModel the model that built the artifact (may be {@code null} =
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
	 * @param subjectName  the primary artifact the run targeted - a form,
	 *                     relation, valuelist, menu, media, ... (e.g.
	 *                     {@code order_tabs}), or {@code null} when the baseline
	 *                     names none
	 * @param sourceHint   a concrete phrase naming the solution file(s) to read
	 *                     for that artifact (from {@code PersistKind.sourceHint}),
	 *                     or {@code null} when there is no single predictable file
	 * @param mockupPath   the working-dir-relative path of a mockup to reproduce
	 *                     (e.g. {@code specs/03_order_tabs.html}), or
	 *                     {@code null} when the baseline declares none
	 * @param criteria     the natural-language acceptance criteria (never empty)
	 * @param judgeModel   the baseline's configured judge model (may be
	 *                     {@code null})
	 * @param builderModel the model that built the artifact (may be {@code null})
	 * @param cancel       polled cooperatively; aborts the run when {@code true}
	 * @return the verdict; on any failure an {@link Verdict#error error} verdict
	 */
	public Verdict judge(String subjectName, String sourceHint, String mockupPath, List<String> criteria,
			String judgeModel, String builderModel, BooleanSupplier cancel) {
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
			String prompt = buildPrompt(subjectName, sourceHint, mockupPath, criteria);
			sessionId = client.createSession("Skill-test acceptance judge"); //$NON-NLS-1$
			logger.log("[skilltest] acceptance judge: grading " + criteria.size() + " criterion(s) for '" //$NON-NLS-1$ //$NON-NLS-2$
					+ subjectName + "' as the " + JUDGE_AGENT + " agent, model " + model + " (session " + sessionId //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
			Verdict v = parseVerdict(answer, criteria);
			// The judge must grade EVERY criterion. A verdict that graded none (or
			// fewer than asked) is not a real pass/fail - it is the judge not doing
			// the job (empty `criteria[]`), which must never read as PASS. Treat a
			// short count as an error so the run fails loudly instead of green-washing.
			if (v.error() == null && (v.results() == null || v.results().size() != criteria.size())) {
				int graded = v.results() == null ? 0 : v.results().size();
				logger.log("[skilltest] acceptance judge: graded " + graded + " of " + criteria.size() //$NON-NLS-1$ //$NON-NLS-2$
						+ " criterion(s) - incomplete verdict, failing"); //$NON-NLS-1$
				// Log the judge's RAW answer so an incomplete verdict is diagnosable
				// straight from the Servoy log - no need for the session to survive. Shows
				// whether the judge returned an empty `criteria[]`, a refusal, a wrong
				// shape, or said it could not find the artifact.
				String raw = answer.length() > 4000 ? answer.substring(0, 4000) + "...[truncated]" : answer; //$NON-NLS-1$
				logger.log("[skilltest] acceptance judge raw answer (incomplete verdict):\n" + raw); //$NON-NLS-1$
				return Verdict.error("judge graded " + graded + " of " + criteria.size() //$NON-NLS-1$ //$NON-NLS-2$
						+ " criteria (incomplete verdict)", answer); //$NON-NLS-1$
			}
			logger.log("[skilltest] acceptance judge verdict: " + v.summary()); //$NON-NLS-1$
			if (!v.pass()) {
				// A failing verdict is only actionable with the judge's own words:
				// log the raw answer so it is visible in the Servoy AI log.
				String raw = answer.length() > 4000 ? answer.substring(0, 4000) + "...[truncated]" : answer; //$NON-NLS-1$
				logger.log("[skilltest] acceptance judge raw answer (failing verdict):\n" + raw); //$NON-NLS-1$
			}
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
	 * Builds the judge prompt. This carries ONLY the per-run facts the judge
	 * cannot know from its own instructions: the name of the main artifact the run
	 * produced (a form, relation, valuelist, menu, media, ... - a hint, may be
	 * absent), the acceptance criteria, and the optional mockup path. How to judge
	 * - autonomy, which sources/specs to read, strictness, and the required JSON
	 * verdict shape - lives in the {@code Judge} agent definition ({@code Judge.md}
	 * in the skills pack), so it is NOT repeated here (it would otherwise be
	 * prepended to every judge request and drift from the agent). The verdict shape
	 * {@code parseVerdict} expects is defined there too.
	 *
	 * @param subjectName the primary artifact name the baseline targets, or
	 *                    {@code null}/blank when the baseline names none (the judge
	 *                    then discovers what the run produced)
	 * @param sourceHint  a concrete phrase naming the solution file(s) to open for
	 *                    the named artifact (from {@code PersistKind.sourceHint}),
	 *                    or {@code null} when there is no single predictable file.
	 *                    A path to read is what makes the judge reliable - the
	 *                    original form-only prompt worked because it said "read
	 *                    forms/<name>.frm", not "search by name" (SVY-21366)
	 */
	String buildPrompt(String subjectName, String sourceHint, String mockupPath, List<String> criteria) {
		StringBuilder sb = new StringBuilder();
		sb.append("A previous agent carried out a Servoy skill-test task in the active solution."); //$NON-NLS-1$
		if (subjectName != null && !subjectName.isBlank()) {
			sb.append(" The main artifact it targeted is named `").append(subjectName).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
			if (sourceHint != null && !sourceHint.isBlank()) {
				sb.append(" - read its source: ").append(sourceHint).append("."); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				sb.append(" - find and read its source in the active solution."); //$NON-NLS-1$
			}
			sb.append('\n');
		} else {
			sb.append(" The agent chose the artifact's name itself, so you must DISCOVER what it produced before ") //$NON-NLS-1$
					.append("you judge - do not assume anything is missing because you were given no name. Call ") //$NON-NLS-1$
					.append("`servoy-model_getTarget` for the active solution, then `servoy-workspace_getProjectLayout` ") //$NON-NLS-1$
					.append("(or `servoy-workspace_findFiles`) on it to list what it now contains - its `forms/*.frm`, ") //$NON-NLS-1$
					.append("`valuelists/*.val`, `relations/*.rel`, `menus/*.men` and scope `*.js` files - find the ") //$NON-NLS-1$
					.append("one the task produced, and read it with `servoy-workspace_readProjectResource`.\n"); //$NON-NLS-1$
		}
		sb.append("Decide, criterion by criterion, whether the result meets the acceptance criteria below.\n\n"); //$NON-NLS-1$
		if (mockupPath != null && !mockupPath.isBlank()) {
			sb.append("Mockup to reproduce: `").append(mockupPath.trim()).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append("Acceptance criteria:\n"); //$NON-NLS-1$
		for (int i = 0; i < criteria.size(); i++) {
			sb.append(i + 1).append(". ").append(criteria.get(i)).append('\n'); //$NON-NLS-1$
		}
		// The verdict shape is restated here, next to the parser that reads it: a
		// run without it answered with its own keys ("met"/"evidence") and said the
		// prompt "didn't spell out the JSON block format" (SVY-21366).
		sb.append("\nAnswer with EXACTLY ONE fenced ```json block and nothing after it, with one entry for EACH of the ") //$NON-NLS-1$
				.append(criteria.size()).append(" criteria above, in this shape:\n\n") //$NON-NLS-1$
				.append("```json\n") //$NON-NLS-1$
				.append("{\n  \"overall\": \"pass\" | \"fail\",\n  \"criteria\": [\n") //$NON-NLS-1$
				.append("    { \"criterion\": \"<the criterion text>\", \"pass\": true | false, \"reason\": \"<why>\" }\n") //$NON-NLS-1$
				.append("  ]\n}\n```\n"); //$NON-NLS-1$
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
		return parseVerdict(answer, null);
	}

	/**
	 * As {@link #parseVerdict(String)}, but with the criteria that were asked so a
	 * verdict entry that omits its {@code criterion} text is backfilled
	 * positionally (entry <em>i</em> -&gt; {@code criteria[i]}). The judge is told
	 * to echo each criterion, but models often return a bare
	 * {@code {pass, reason}}; the criterion text is authoritative from here, not
	 * from the model, so the report never shows {@code [FAIL] null}.
	 *
	 * @param answer   the judge's text (may be {@code null})
	 * @param criteria the criteria that were graded, for positional backfill (may
	 *                 be {@code null} to skip backfill)
	 */
	Verdict parseVerdict(String answer, List<String> criteria) {
		String json = extractJson(answer);
		if (json == null) {
			return Verdict.error("no JSON verdict found in the judge's answer", answer); //$NON-NLS-1$
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			List<CriterionResult> results = new ArrayList<>();
			JsonNode arr = root.get("criteria"); //$NON-NLS-1$
			if (arr != null && arr.isArray()) {
				int i = 0;
				for (JsonNode c : arr) {
					String criterion = textAt(c, "criterion"); //$NON-NLS-1$
					if ((criterion == null || criterion.isBlank()) && criteria != null && i < criteria.size()) {
						criterion = criteria.get(i); // model omitted it - fill from what was asked
					}
					boolean pass = criterionPassed(c);
					String reason = firstText(c, "reason", "evidence", "explanation", "reasoning", "why"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					results.add(new CriterionResult(criterion, pass, reason));
					i++;
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

	/** The first non-blank text value among {@code fields}, or {@code null}. */
	private static String firstText(JsonNode node, String... fields) {
		for (String f : fields) {
			String t = textAt(node, f);
			if (t != null && !t.isBlank()) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Whether a verdict entry passed. Judges do not reliably use the documented
	 * {@code pass} key - a real run answered {@code "met": true} and the old
	 * {@code pass}-only read silently turned three passing criteria into FAIL
	 * (SVY-21366). Accepts {@code pass}/{@code passed}/{@code met}/{@code ok}/
	 * {@code satisfied} as a boolean or a "pass"/"true"/"yes"/"met" string, and a
	 * {@code result}/{@code status}/{@code verdict} string; absent means fail.
	 */
	static boolean criterionPassed(JsonNode c) {
		if (c == null) {
			return false;
		}
		for (String f : new String[] { "pass", "passed", "met", "ok", "satisfied", "result", "status", "verdict" }) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			JsonNode v = c.get(f);
			if (v == null || v.isNull()) {
				continue;
			}
			if (v.isBoolean()) {
				return v.booleanValue();
			}
			String s = v.asText().trim().toLowerCase(java.util.Locale.ROOT);
			return s.equals("pass") || s.equals("passed") || s.equals("true") || s.equals("yes") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					|| s.equals("met") || s.equals("ok"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return false;
	}
}

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

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.Baseline;
import com.servoy.eclipse.opencode.skilltest.OpencodeHttpClient;

/**
 * An AI baseline-authoring agent (SVY-21366): given a freshly imported opencode
 * session export, it <b>analyzes</b> what the run built and <b>decides which
 * verification checks are appropriate</b> - expected persists, a JSUnit script,
 * and/or an LLM-judge acceptance-criteria set - then <b>generates</b> the
 * concrete content for each, returning a {@link VerificationPlan} the human
 * reviews, edits and approves before the baseline is written.
 * <p>
 * It runs as its own embedded-opencode session with its own tools and skills, in
 * the active solution's working directory, the same way {@link AcceptanceJudge}
 * does. The prompt hands it the export path (and the UI mockup path when one is
 * given) and tells it to <b>read for itself</b> the prompt the builder was given,
 * the tool calls it made, and the artifacts it produced (the {@code .frm}/
 * {@code .js}/{@code .less}, valuelists, relations, scopes, …), then follow the
 * <b>{@code skilltest-authoring} skill</b> - which carries the authoritative
 * rules for when each check kind fits and how to generate it - and answer with
 * one fenced JSON plan. Nothing is serialized into the prompt; the agent
 * inspects the real files.
 * </p>
 * <p>
 * Every failure path - no server, HTTP error, an unparseable answer - degrades to
 * a <b>persists-only</b> plan built offline from {@link OutcomeInference}, so
 * import never hard-breaks: the human still gets a usable (if literal) starting
 * point to edit by hand, exactly as before this agent existed. A degraded plan
 * is logged so a missing {@code skilltest-authoring} skill is diagnosable.
 * </p>
 */
public final class VerificationPlanAgent {

	/** The skill whose prose carries the authoritative check-selection + generation rules. */
	static final String SKILL_NAME = "skilltest-authoring"; //$NON-NLS-1$

	/** How often the completion wait polls the session status. */
	private static final Duration POLL = Duration.ofSeconds(2);

	/** Max time to wait for the opencode server to come ready (ms). */
	private static final long SERVER_WAIT_MS = 120_000L;

	private static final ObjectMapper MAPPER = new ObjectMapper();

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
	 * @param port   the embedded opencode server port
	 * @param logger a diagnostic sink; {@code null} becomes a no-op
	 */
	public VerificationPlanAgent(int port, Logger logger) {
		this.client = new OpencodeHttpClient(port);
		this.logger = logger != null ? logger : m -> {
		};
	}

	/**
	 * Binds an agent to the running embedded opencode server (starting / waiting
	 * for it if needed), or returns {@code null} when no server is available.
	 *
	 * @param logger a diagnostic sink; may be {@code null}
	 * @return a ready agent, or {@code null}
	 */
	public static VerificationPlanAgent ready(Logger logger) {
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
		return port > 0 ? new VerificationPlanAgent(port, logger) : null;
	}

	/**
	 * Analyzes the export and returns a proposed {@link VerificationPlan}.
	 * Blocking: run off the UI thread. On any failure the offline persists-only
	 * fallback ({@link #fallback(File)}) is returned, never {@code null}.
	 *
	 * @param exportFile the imported export JSON (read by the agent + the fallback)
	 * @param mockupPath the working-dir-relative path of the UI mockup the run
	 *                   reproduces, or {@code null} when none
	 * @param cancel     polled cooperatively; aborts the run when {@code true}
	 * @return the proposed plan (never {@code null})
	 */
	public VerificationPlan analyze(File exportFile, String mockupPath, BooleanSupplier cancel) {
		BooleanSupplier notCancelled = cancel != null ? cancel : () -> false;
		String sessionId = null;
		try {
			if (!client.health()) {
				logger.log("[skilltest] plan agent skipped: opencode server not ready; using inference fallback"); //$NON-NLS-1$
				return fallback(exportFile);
			}
			String prompt = buildPrompt(mockupPath);
			sessionId = client.createSession("Skill-test baseline authoring"); //$NON-NLS-1$
			logger.log("[skilltest] plan agent: analyzing the export to propose verification checks (session " //$NON-NLS-1$
					+ sessionId + ")"); //$NON-NLS-1$
			// Default agent + server-default model: an authoring utility call.
			client.promptAsync(sessionId, prompt, null, null);
			client.waitForCompletion(sessionId, POLL, notCancelled);
			// Read from the EXPORT (inlines each message's `content`), NOT the
			// /message list, which returns metadata with empty parts in opencode v2
			// (SVY-21366).
			String answer = lastAssistantText(client.getExport(sessionId));
			if (answer == null || answer.isBlank()) {
				logger.log("[skilltest] plan agent returned no text (is the '" + SKILL_NAME //$NON-NLS-1$
						+ "' skill installed?); using inference fallback"); //$NON-NLS-1$
				return fallback(exportFile);
			}
			VerificationPlan plan = parsePlan(answer);
			if (plan == null) {
				logger.log("[skilltest] plan agent: no parseable plan in the answer (" + answer.length() //$NON-NLS-1$
						+ " chars); using inference fallback. Answer head: " + head(answer)); //$NON-NLS-1$
				return fallback(exportFile);
			}
			logger.log("[skilltest] plan agent proposed: " + summarize(plan)); //$NON-NLS-1$
			return plan;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			logger.log("[skilltest] plan agent cancelled; using inference fallback"); //$NON-NLS-1$
			return fallback(exportFile);
		} catch (RuntimeException | java.io.IOException ex) {
			logger.log("[skilltest] plan agent failed (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
					+ "); using inference fallback"); //$NON-NLS-1$
			return fallback(exportFile);
		} finally {
			if (sessionId != null) {
				client.deleteSession(sessionId);
			}
		}
	}

	/**
	 * The offline fallback: a persists-only plan inferred from the export alone
	 * ({@link OutcomeInference#infer(File)}), with no acceptance / JSUnit check.
	 * Always usable; the human edits it by hand.
	 */
	public static VerificationPlan fallback(File exportFile) {
		List<OutcomeAssertion> inferred = OutcomeInference.infer(exportFile);
		return VerificationPlan.persistsOnly(inferred,
				"Proposed offline from the export (the authoring agent was unavailable): a literal structural " //$NON-NLS-1$
						+ "capture to review and edit."); //$NON-NLS-1$
	}

	/**
	 * Builds the authoring prompt. The decision/generation rules live in the
	 * {@code skilltest-authoring} skill (the {@code skills4servoy} repo), not here;
	 * the prompt names the skill, gives the per-call envelope (the export + optional
	 * mockup to inspect) and the answer-format contract.
	 */
	String buildPrompt(String mockupPath) {
		StringBuilder sb = new StringBuilder();
		sb.append("[skill-test mode] You are an automated skill-test baseline author. Use the `").append(SKILL_NAME) //$NON-NLS-1$
				.append("` skill - it carries the authoritative rules for which verification checks fit a run and ") //$NON-NLS-1$
				.append("how to generate each. Run fully autonomously: do not ask questions, do not wait for ") //$NON-NLS-1$
				.append("confirmation, do not modify any file.\n\n"); //$NON-NLS-1$
		sb.append("A previous agent built something in the active solution from a prompt. The recorded session is ") //$NON-NLS-1$
				.append("the opencode export at `export.json` in this baseline's folder (the current working ") //$NON-NLS-1$
				.append("directory's sibling, or ask your tools). Your job is to decide which verification checks ") //$NON-NLS-1$
				.append("best prove this run succeeded, and generate them.\n\n"); //$NON-NLS-1$
		sb.append("Inspect the actual result yourself using your tools - do not assume. As needed:\n"); //$NON-NLS-1$
		sb.append("  - read the builder's prompt and tool calls in the export;\n"); //$NON-NLS-1$
		sb.append("  - list the solution's forms/valuelists/relations/scopes and read the artifacts the run ") //$NON-NLS-1$
				.append("produced (the `.frm`, the `.js`, the form + solution `.less`/`.css`);\n"); //$NON-NLS-1$
		sb.append("  - OPTIONALLY call `servoy-form_getFormLayout` with `screenshot=true` to see how a built form ") //$NON-NLS-1$
				.append("renders.\n"); //$NON-NLS-1$
		if (mockupPath != null && !mockupPath.isBlank()) {
			sb.append("  - read the UI mockup the run was asked to reproduce, at `").append(mockupPath.trim()) //$NON-NLS-1$
					.append("`.\n"); //$NON-NLS-1$
		}
		sb.append('\n');
		sb.append("Following the `").append(SKILL_NAME).append("` skill, choose any combination of these three ") //$NON-NLS-1$ //$NON-NLS-2$
				.append("checks (include only those that genuinely fit; omit a check by leaving it out):\n"); //$NON-NLS-1$
		sb.append("  - `expectPersists`: deterministic structural artifacts (valuelist values, a relation, a scope ") //$NON-NLS-1$
				.append("method, a form's datasource binding, bound components). Omit when the structure is LLM-") //$NON-NLS-1$
				.append("built UI that legitimately varies run-to-run.\n"); //$NON-NLS-1$
		sb.append("  - `acceptanceCriteria`: natural-language criteria graded by a separate LLM judge - the right ") //$NON-NLS-1$
				.append("check for visual/layout fidelity where structure is too brittle. Write each criterion as ") //$NON-NLS-1$
				.append("an OUTCOME statement, never an implementation prescription.\n"); //$NON-NLS-1$
		sb.append("  - `jsunit`: a name-tolerant `test_` script proving behaviour the structure cannot. Query the ") //$NON-NLS-1$
				.append("solutionModel/runtime; never hard-code the element names the builder chose.\n\n"); //$NON-NLS-1$
		sb.append("Answer with EXACTLY ONE fenced ```json block and nothing after it, of the form:\n\n"); //$NON-NLS-1$
		sb.append("```json\n") //$NON-NLS-1$
				.append("{\n") //$NON-NLS-1$
				.append("  \"rationale\": \"<one short paragraph: why this mix of checks fits this run>\",\n") //$NON-NLS-1$
				.append("  \"expectPersists\": [ { \"kind\": \"form\", \"name\": \"...\", \"props\": { }, \"children\": [ ] } ],\n") //$NON-NLS-1$
				.append("  \"acceptanceCriteria\": { \"criteria\": [ \"...\" ], \"judgeModel\": null, \"required\": true },\n") //$NON-NLS-1$
				.append("  \"jsunit\": { \"scope\": \"skilltest_verify\", \"scriptName\": \"skilltest_verify\",\n") //$NON-NLS-1$
				.append("    \"scriptSource\": \"function test_xxx() { /* name-tolerant */ }\", \"method\": null, \"required\": true }\n") //$NON-NLS-1$
				.append("}\n```\n"); //$NON-NLS-1$
		sb.append("Leave out any of `expectPersists` / `acceptanceCriteria` / `jsunit` you are not proposing. ") //$NON-NLS-1$
				.append("`expectPersists` uses the same shape as a baseline's `expect.persists`."); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Parses the agent's answer into a {@link VerificationPlan}: lifts the fenced
	 * JSON (or the outermost {@code { ... }}), reads {@code rationale},
	 * {@code expectPersists} (reusing {@link OutcomeAssertions#parse} by wrapping it
	 * as {@code { persists: [...] }}), {@code acceptanceCriteria} and {@code jsunit}.
	 *
	 * @param answer the agent's text (may be {@code null})
	 * @return a plan, or {@code null} when nothing parseable was found
	 */
	VerificationPlan parsePlan(String answer) {
		String json = extractJson(answer);
		if (json == null) {
			return null;
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			String rationale = textAt(root, "rationale"); //$NON-NLS-1$
			List<OutcomeAssertion> persists = parsePersists(root.get("expectPersists")); //$NON-NLS-1$
			Baseline.AcceptanceVerify acceptance = parseAcceptance(root.get("acceptanceCriteria")); //$NON-NLS-1$
			VerificationPlan.JsUnitPlan jsunit = parseJsUnit(root.get("jsunit")); //$NON-NLS-1$
			// A plan carrying nothing at all is not a plan.
			if (persists.isEmpty() && acceptance == null && jsunit == null) {
				return null;
			}
			return new VerificationPlan(persists, acceptance, jsunit, rationale);
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return null;
		}
	}

	/** Parses an {@code expectPersists} array by feeding {@code { persists: [...] }} to {@link OutcomeAssertions}. */
	private static List<OutcomeAssertion> parsePersists(JsonNode expectPersists) {
		if (expectPersists == null || !expectPersists.isArray()) {
			return List.of();
		}
		com.fasterxml.jackson.databind.node.ObjectNode expect = MAPPER.createObjectNode();
		expect.set("persists", expectPersists); //$NON-NLS-1$
		return OutcomeAssertions.parse(expect);
	}

	private static Baseline.AcceptanceVerify parseAcceptance(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		JsonNode arr = node.get("criteria"); //$NON-NLS-1$
		if (arr == null || !arr.isArray() || arr.isEmpty()) {
			return null;
		}
		java.util.List<String> criteria = new java.util.ArrayList<>();
		for (JsonNode c : arr) {
			if (c != null && c.isValueNode() && !c.asText().isBlank()) {
				criteria.add(c.asText().trim());
			}
		}
		if (criteria.isEmpty()) {
			return null;
		}
		// Leave judgeModel null unless the agent named one: the runner then reuses
		// the builder's model in a separate session (the judge need not differ).
		String judgeModel = textAt(node, "judgeModel"); //$NON-NLS-1$
		if (judgeModel != null && judgeModel.isBlank()) {
			judgeModel = null;
		}
		boolean required = node.path("required").asBoolean(true); //$NON-NLS-1$
		return new Baseline.AcceptanceVerify(criteria, judgeModel, required);
	}

	private static VerificationPlan.JsUnitPlan parseJsUnit(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		String scriptSource = textAt(node, "scriptSource"); //$NON-NLS-1$
		if (scriptSource == null || scriptSource.isBlank()) {
			return null;
		}
		String scope = textAt(node, "scope"); //$NON-NLS-1$
		String scriptName = textAt(node, "scriptName"); //$NON-NLS-1$
		if (scriptName == null || scriptName.isBlank()) {
			scriptName = scope != null && !scope.isBlank() ? scope : "skilltest_verify"; //$NON-NLS-1$
		}
		if (scope == null || scope.isBlank()) {
			scope = scriptName;
		}
		String method = textAt(node, "method"); //$NON-NLS-1$
		boolean required = node.path("required").asBoolean(true); //$NON-NLS-1$
		return new VerificationPlan.JsUnitPlan(scope, scriptName, scriptSource, method, required);
	}

	/** A one-line summary of a plan for the run log. */
	static String summarize(VerificationPlan plan) {
		StringBuilder sb = new StringBuilder();
		if (plan.hasPersists()) {
			sb.append("expect.persists(").append(plan.persists().size()).append(") "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (plan.hasAcceptance()) {
			sb.append("acceptanceCriteria(").append(plan.acceptance().criteria().size()).append(") "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (plan.hasJsUnit()) {
			sb.append("jsunit "); //$NON-NLS-1$
		}
		if (sb.length() == 0) {
			sb.append("(no checks)"); //$NON-NLS-1$
		}
		return sb.toString().trim();
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

	/** The first ~200 chars of a string, single-lined, for a diagnostic log. */
	private static String head(String s) {
		String flat = s.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
		return flat.length() > 200 ? flat.substring(0, 200) + "\u2026" : flat; //$NON-NLS-1$
	}

	/**
	 * Concatenated {@code text} content blocks of the last assistant message in the
	 * export envelope {@link OpencodeHttpClient#getExport} returns (v2 {@code content},
	 * v1 {@code parts} fallback), or {@code null} when there is no assistant text.
	 */
	static String lastAssistantText(JsonNode exportRoot) {
		if (exportRoot == null) {
			return null;
		}
		JsonNode messages = exportRoot.get("messages"); //$NON-NLS-1$
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

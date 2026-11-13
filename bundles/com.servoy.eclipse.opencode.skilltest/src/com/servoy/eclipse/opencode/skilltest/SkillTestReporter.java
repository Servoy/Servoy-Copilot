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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Persists skill-test run results to the workspace reports directory
 * (SVY-21366, §3.9), following the EclEmma/JaCoCo split of canonical data vs.
 * derived human report:
 * <ul>
 * <li>{@code reports/last-results.json} — the <b>canonical</b>,
 * machine-readable result keyed by baseline id (the {@code jacoco.exec}
 * analogue). Overwritten on every run and reloaded on view open so the
 * last-result column survives an IDE restart.</li>
 * <li>{@code reports/run-<timestamp>.md} — a <b>derived</b> human-readable
 * Markdown report (the HTML-report analogue), one per run, kept for
 * history.</li>
 * </ul>
 * <p>
 * The {@code reports/} directory is derived/regenerable and is git-ignored.
 * </p>
 */
public final class SkillTestReporter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"); //$NON-NLS-1$

	private SkillTestReporter() {
	}

	/**
	 * Writes both the canonical {@code last-results.json} and a timestamped
	 * Markdown report for a completed run.
	 *
	 * @param results        the run's results (order preserved)
	 * @param markdownReport the pre-formatted Markdown (from
	 *                       {@link SkillTestRunner#formatResults(List)})
	 * @return the Markdown report file that was written
	 * @throws IOException if either file cannot be written
	 */
	public static File writeRun(List<SkillTestResult> results, String markdownReport) throws IOException {
		writeLastResults(results);
		File reportFile = new File(SkillTestPaths.reportsRoot(),
				"run-" + LocalDateTime.now().format(TIMESTAMP) + ".md"); //$NON-NLS-1$ //$NON-NLS-2$
		Files.writeString(reportFile.toPath(), markdownReport != null ? markdownReport : "", //$NON-NLS-1$
				StandardCharsets.UTF_8);
		return reportFile;
	}

	/**
	 * Overwrites {@code last-results.json} with the given results.
	 *
	 * @param results the results to persist as canonical data
	 * @throws IOException if the file cannot be written
	 */
	public static void writeLastResults(List<SkillTestResult> results) throws IOException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("generatedAt", LocalDateTime.now().toString()); //$NON-NLS-1$
		ArrayNode arr = root.putArray("results"); //$NON-NLS-1$
		if (results != null) {
			for (SkillTestResult r : results) {
				ObjectNode node = arr.addObject();
				node.put("baselineId", r.getBaselineId()); //$NON-NLS-1$
				node.put("status", r.getStatus().name()); //$NON-NLS-1$
				node.put("attempts", r.getAttempts()); //$NON-NLS-1$
				if (r.getDiff() != null) {
					node.put("diff", r.getDiff()); //$NON-NLS-1$
				}
				if (r.getErrorMessage() != null) {
					node.put("errorMessage", r.getErrorMessage()); //$NON-NLS-1$
				}
				// Persist the builder agent so the coverage step (SVY-21711) can attribute
				// the builder's effectiveAgent back to each baseline after the run.
				if (r.getAgent() != null) {
					node.put("agent", r.getAgent()); //$NON-NLS-1$
				}
				// Persist the full detail so the view's detail panel survives a restart:
				// the sent prompt, the expected/actual tool calls, the structured outcome
				// checks, and the JSUnit report. Without these the reloaded result shows
				// "(not captured)" placeholders.
				if (r.getPrompt() != null) {
					node.put("prompt", r.getPrompt()); //$NON-NLS-1$
				}
				writeToolCalls(node, "expectedCalls", r.getExpectedCalls()); //$NON-NLS-1$
				writeToolCalls(node, "actualCalls", r.getActualCalls()); //$NON-NLS-1$
				writeOutcomeNodes(node, r.getOutcomeNodes());
				if (r.getJsUnitReport() != null) {
					node.put("jsUnitReport", r.getJsUnitReport()); //$NON-NLS-1$
				}
				if (r.getJsUnitPass() != null) {
					node.put("jsUnitPass", r.getJsUnitPass().booleanValue()); //$NON-NLS-1$
				}
				// Cost/efficiency metrics (SVY-21660): written additively. tokens/kiro
				// objects are omitted entirely when unknown so a reader distinguishes
				// "unknown" from a real zero.
				writeMetrics(node, r.getMetrics());
				// Per-trial distribution.
				if (r.getTrials() != null && !r.getTrials().isEmpty()) {
					ArrayNode trialsArr = node.putArray("trials"); //$NON-NLS-1$
					for (SkillTestResult.Trial t : r.getTrials()) {
						ObjectNode tn = trialsArr.addObject();
						tn.put("index", t.index()); //$NON-NLS-1$
						tn.put("pass", t.pass()); //$NON-NLS-1$
						if (t.diff() != null) {
							tn.put("diff", t.diff()); //$NON-NLS-1$
						}
						writeOutcomeNodes(tn, t.outcomeNodes());
						writeToolCalls(tn, "actualCalls", t.actualCalls()); //$NON-NLS-1$
						if (t.jsUnitReport() != null) {
							tn.put("jsUnitReport", t.jsUnitReport()); //$NON-NLS-1$
						}
						if (t.jsUnitPass() != null) {
							tn.put("jsUnitPass", t.jsUnitPass().booleanValue()); //$NON-NLS-1$
						}
						writeMetrics(tn, t.metrics());
					}
				}
			}
		}
		ObjectMapper writer = MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT);
		Files.writeString(SkillTestPaths.lastResultsFile().toPath(), writer.writeValueAsString(root),
				StandardCharsets.UTF_8);
	}

	/**
	 * Serialises a tool-call list to {@code field} as an array of
	 * {server,tool,arguments,output}.
	 */
	private static void writeToolCalls(ObjectNode parent, String field, List<McpToolCall> calls) {
		if (calls == null) {
			return;
		}
		ArrayNode arr = parent.putArray(field);
		for (McpToolCall c : calls) {
			ObjectNode n = arr.addObject();
			if (c.server() != null) {
				n.put("server", c.server()); //$NON-NLS-1$
			}
			n.put("tool", c.tool()); //$NON-NLS-1$
			if (c.arguments() != null && !c.arguments().isEmpty()) {
				n.set("arguments", MAPPER.valueToTree(c.arguments())); //$NON-NLS-1$
			}
			if (c.output() != null) {
				n.put("output", c.output()); //$NON-NLS-1$
			}
		}
	}

	/**
	 * Serialises the cost/efficiency metrics to {@code metrics} (SVY-21660).
	 * Nothing is written when {@code metrics} is {@code null}. {@code toolCalls} is
	 * always written when metrics exist; the {@code tokens}/{@code kiro} objects
	 * are <b>omitted entirely</b> when unknown, so a reader distinguishes "unknown"
	 * from a real zero.
	 */
	private static void writeMetrics(ObjectNode parent, RunMetrics metrics) {
		if (metrics == null) {
			return;
		}
		ObjectNode m = parent.putObject("metrics"); //$NON-NLS-1$
		m.put("toolCalls", metrics.toolCalls()); //$NON-NLS-1$
		RunMetrics.TokenUsage t = metrics.tokens();
		if (t != null) {
			ObjectNode tn = m.putObject("tokens"); //$NON-NLS-1$
			tn.put("input", t.input()); //$NON-NLS-1$
			tn.put("output", t.output()); //$NON-NLS-1$
			tn.put("reasoning", t.reasoning()); //$NON-NLS-1$
			tn.put("cacheRead", t.cacheRead()); //$NON-NLS-1$
			tn.put("cacheWrite", t.cacheWrite()); //$NON-NLS-1$
		}
		RunMetrics.KiroUsage k = metrics.kiro();
		if (k != null) {
			ObjectNode kn = m.putObject("kiro"); //$NON-NLS-1$
			kn.put("requests", k.requests()); //$NON-NLS-1$
			kn.put("estCredits", k.estCredits()); //$NON-NLS-1$
		}
		// Scope-expansion fields (SVY-21660 B/C): written additively, omitted when
		// unknown so old readers and old files are unaffected.
		if (metrics.creditsDelta() != null) {
			m.put("creditsDelta", metrics.creditsDelta().doubleValue()); //$NON-NLS-1$
		}
		// Account bracket readings (SVY-21660 live-data follow-up): before/after
		// account used + the account limit, each omitted when unknown.
		if (metrics.creditsBefore() != null) {
			m.put("creditsBefore", metrics.creditsBefore().doubleValue()); //$NON-NLS-1$
		}
		if (metrics.creditsAfter() != null) {
			m.put("creditsAfter", metrics.creditsAfter().doubleValue()); //$NON-NLS-1$
		}
		if (metrics.creditsLimit() != null) {
			m.put("creditsLimit", metrics.creditsLimit().doubleValue()); //$NON-NLS-1$
		}
		if (!metrics.requestTokens().isEmpty()) {
			ArrayNode rt = m.putArray("requestTokens"); //$NON-NLS-1$
			for (RunMetrics.RequestTokens req : metrics.requestTokens()) {
				ObjectNode rn = rt.addObject();
				rn.put("index", req.index()); //$NON-NLS-1$
				RunMetrics.TokenUsage rtok = req.tokens();
				if (rtok != null) {
					ObjectNode tok = rn.putObject("tokens"); //$NON-NLS-1$
					tok.put("input", rtok.input()); //$NON-NLS-1$
					tok.put("output", rtok.output()); //$NON-NLS-1$
					tok.put("reasoning", rtok.reasoning()); //$NON-NLS-1$
					tok.put("cacheRead", rtok.cacheRead()); //$NON-NLS-1$
					tok.put("cacheWrite", rtok.cacheWrite()); //$NON-NLS-1$
				}
				if (!req.toolNames().isEmpty()) {
					ArrayNode tn = rn.putArray("toolNames"); //$NON-NLS-1$
					for (String name : req.toolNames()) {
						tn.add(name);
					}
				}
				if (req.skill() != null) {
					rn.put("skill", req.skill()); //$NON-NLS-1$
				}
				// Per-request call count (SVY-21660): written additively so the
				// round-trip preserves the value that drives the Actual column's
				// first-call cost annotation. Absent in older files -> read back as 0.
				rn.put("callCount", req.callCount()); //$NON-NLS-1$
			}
		}
	}

	/**
	 * Rebuilds the cost/efficiency metrics from a persisted {@code metrics} node
	 * (SVY-21660), or {@code null} when absent (older files lack it -&gt;
	 * "unknown"). Absent {@code tokens}/{@code kiro} sub-objects become
	 * {@code null} ("unknown").
	 */
	private static RunMetrics readMetrics(JsonNode node) {
		if (node == null || !node.has("metrics")) { //$NON-NLS-1$
			return null;
		}
		JsonNode m = node.get("metrics"); //$NON-NLS-1$
		if (m == null || !m.isObject()) {
			return null;
		}
		int toolCalls = m.path("toolCalls").asInt(); //$NON-NLS-1$
		RunMetrics.TokenUsage tokens = null;
		JsonNode tn = m.get("tokens"); //$NON-NLS-1$
		if (tn != null && tn.isObject()) {
			tokens = new RunMetrics.TokenUsage(tn.path("input").asLong(), tn.path("output").asLong(), //$NON-NLS-1$ //$NON-NLS-2$
					tn.path("reasoning").asLong(), tn.path("cacheRead").asLong(), tn.path("cacheWrite").asLong()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		RunMetrics.KiroUsage kiro = null;
		JsonNode kn = m.get("kiro"); //$NON-NLS-1$
		if (kn != null && kn.isObject()) {
			kiro = new RunMetrics.KiroUsage(kn.path("requests").asInt(), kn.path("estCredits").asDouble()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		// Scope-expansion fields (SVY-21660 B/C): read with has(...) guards; absent in
		// an older file -> null/empty -> "unknown".
		Double creditsDelta = m.has("creditsDelta") ? Double.valueOf(m.get("creditsDelta").asDouble()) : null; //$NON-NLS-1$ //$NON-NLS-2$
		List<RunMetrics.RequestTokens> requestTokens = readRequestTokens(m.get("requestTokens")); //$NON-NLS-1$
		Double creditsBefore = m.has("creditsBefore") ? Double.valueOf(m.get("creditsBefore").asDouble()) : null; //$NON-NLS-1$ //$NON-NLS-2$
		Double creditsAfter = m.has("creditsAfter") ? Double.valueOf(m.get("creditsAfter").asDouble()) : null; //$NON-NLS-1$ //$NON-NLS-2$
		Double creditsLimit = m.has("creditsLimit") ? Double.valueOf(m.get("creditsLimit").asDouble()) : null; //$NON-NLS-1$ //$NON-NLS-2$
		return new RunMetrics(toolCalls, tokens, kiro, creditsDelta, requestTokens, creditsBefore, creditsAfter,
				creditsLimit);
	}

	/**
	 * Rebuilds the per-request token list from a persisted {@code requestTokens}
	 * array (SVY-21660 C), or an empty list when absent (older files -&gt;
	 * "unknown").
	 */
	private static List<RunMetrics.RequestTokens> readRequestTokens(JsonNode arr) {
		List<RunMetrics.RequestTokens> list = new java.util.ArrayList<>();
		if (arr == null || !arr.isArray()) {
			return list;
		}
		for (JsonNode n : arr) {
			int index = n.path("index").asInt(); //$NON-NLS-1$
			RunMetrics.TokenUsage tokens = null;
			JsonNode tn = n.get("tokens"); //$NON-NLS-1$
			if (tn != null && tn.isObject()) {
				tokens = new RunMetrics.TokenUsage(tn.path("input").asLong(), tn.path("output").asLong(), //$NON-NLS-1$ //$NON-NLS-2$
						tn.path("reasoning").asLong(), tn.path("cacheRead").asLong(), tn.path("cacheWrite").asLong()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			List<String> toolNames = new java.util.ArrayList<>();
			JsonNode names = n.get("toolNames"); //$NON-NLS-1$
			if (names != null && names.isArray()) {
				for (JsonNode nm : names) {
					toolNames.add(nm.asText());
				}
			}
			// callCount is additive (SVY-21660): absent in an older file -> 0 -> that
			// request simply carries no first-call cost annotation on reload.
			int callCount = n.path("callCount").asInt(); //$NON-NLS-1$
			list.add(new RunMetrics.RequestTokens(index, tokens, toolNames, text(n, "skill"), callCount)); //$NON-NLS-1$
		}
		return list;
	}

	/** Serialises the outcome-check node tree to {@code outcomeNodes}. */
	private static void writeOutcomeNodes(ObjectNode parent,
			List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> nodes) {
		if (nodes == null) {
			return;
		}
		ArrayNode arr = parent.putArray("outcomeNodes"); //$NON-NLS-1$
		for (var node : nodes) {
			arr.add(outcomeNodeToJson(node));
		}
	}

	private static ObjectNode outcomeNodeToJson(
			com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node node) {
		ObjectNode n = MAPPER.createObjectNode();
		n.put("label", node.label()); //$NON-NLS-1$
		n.put("found", node.found()); //$NON-NLS-1$
		n.put("pass", node.pass()); //$NON-NLS-1$
		n.put("message", node.message()); //$NON-NLS-1$
		ArrayNode props = n.putArray("props"); //$NON-NLS-1$
		for (var p : node.props()) {
			ObjectNode pn = props.addObject();
			pn.put("property", p.property()); //$NON-NLS-1$
			pn.put("expected", p.expected()); //$NON-NLS-1$
			pn.put("actual", p.actual()); //$NON-NLS-1$
			pn.put("pass", p.pass()); //$NON-NLS-1$
		}
		ArrayNode children = n.putArray("children"); //$NON-NLS-1$
		for (var child : node.children()) {
			children.add(outcomeNodeToJson(child));
		}
		return n;
	}

	/**
	 * Reloads the canonical {@code last-results.json} into a map keyed by baseline
	 * id, so a view can restore its last-result column without re-running.
	 *
	 * @return baseline id → result (empty if the file is absent or unreadable)
	 */
	public static Map<String, SkillTestResult> loadLastResults() {
		Map<String, SkillTestResult> map = new LinkedHashMap<>();
		File file = SkillTestPaths.lastResultsFile();
		if (!file.isFile()) {
			return map;
		}
		try {
			JsonNode root = MAPPER.readTree(file);
			JsonNode arr = root.get("results"); //$NON-NLS-1$
			if (arr == null || !arr.isArray()) {
				return map;
			}
			for (JsonNode node : arr) {
				String id = text(node, "baselineId"); //$NON-NLS-1$
				if (id == null) {
					continue;
				}
				SkillTestResult.Status status = parseStatus(text(node, "status")); //$NON-NLS-1$
				int attempts = node.has("attempts") ? node.get("attempts").asInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$
				String diff = text(node, "diff"); //$NON-NLS-1$
				String error = text(node, "errorMessage"); //$NON-NLS-1$
				SkillTestResult result = toResult(id, status, attempts, diff, error);
				// Restore the full detail panel data from the persisted node.
				result.withTranscript(text(node, "prompt"), //$NON-NLS-1$
						readToolCalls(node.get("expectedCalls")), //$NON-NLS-1$
						readToolCalls(node.get("actualCalls"))); //$NON-NLS-1$
				result.withOutcomeNodes(readOutcomeNodes(node.get("outcomeNodes"))); //$NON-NLS-1$
				String jsReport = text(node, "jsUnitReport"); //$NON-NLS-1$
				Boolean jsPass = node.has("jsUnitPass") ? Boolean.valueOf(node.get("jsUnitPass").asBoolean()) : null; //$NON-NLS-1$ //$NON-NLS-2$
				if (jsReport != null || jsPass != null) {
					result.withJsUnit(jsReport, jsPass);
				}
				result.withTrials(readTrials(node.get("trials"))); //$NON-NLS-1$
				result.withAgent(text(node, "agent")); //$NON-NLS-1$
				result.withMetrics(readMetrics(node));
				map.put(id, result);
			}
		} catch (IOException e) {
			// Corrupt/partial file: degrade to no history rather than fail the view.
			return map;
		}
		return map;
	}

	private static SkillTestResult toResult(String id, SkillTestResult.Status status, int attempts, String diff,
			String error) {
		return switch (status) {
		case PASS -> SkillTestResult.pass(id, attempts);
		case FAIL -> SkillTestResult.fail(id, attempts, diff != null ? diff : ""); //$NON-NLS-1$
		case ERROR -> SkillTestResult.error(id, attempts, error != null ? error : ""); //$NON-NLS-1$
		case SKIPPED -> SkillTestResult.skipped(id, error != null ? error : ""); //$NON-NLS-1$
		};
	}

	private static SkillTestResult.Status parseStatus(String value) {
		if (value == null) {
			return SkillTestResult.Status.ERROR;
		}
		try {
			return SkillTestResult.Status.valueOf(value);
		} catch (IllegalArgumentException e) {
			return SkillTestResult.Status.ERROR;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() ? value.asText() : null;
	}

	/** Rebuilds the per-trial list from a persisted array (or empty if absent). */
	private static List<SkillTestResult.Trial> readTrials(JsonNode arr) {
		List<SkillTestResult.Trial> trials = new java.util.ArrayList<>();
		if (arr == null || !arr.isArray()) {
			return trials;
		}
		for (JsonNode n : arr) {
			int index = n.path("index").asInt(); //$NON-NLS-1$
			boolean pass = n.path("pass").asBoolean(); //$NON-NLS-1$
			String diff = text(n, "diff"); //$NON-NLS-1$
			String jsReport = text(n, "jsUnitReport"); //$NON-NLS-1$
			Boolean jsPass = n.has("jsUnitPass") ? Boolean.valueOf(n.get("jsUnitPass").asBoolean()) : null; //$NON-NLS-1$ //$NON-NLS-2$
			trials.add(new SkillTestResult.Trial(index, pass, diff, readOutcomeNodes(n.get("outcomeNodes")), //$NON-NLS-1$
					readToolCalls(n.get("actualCalls")), jsReport, jsPass, readMetrics(n))); //$NON-NLS-1$
		}
		return trials;
	}

	/**
	 * Rebuilds a tool-call list from a persisted array (or {@code null} if absent).
	 */
	private static List<McpToolCall> readToolCalls(JsonNode arr) {
		if (arr == null || !arr.isArray()) {
			return null;
		}
		List<McpToolCall> calls = new java.util.ArrayList<>();
		for (JsonNode n : arr) {
			String server = text(n, "server"); //$NON-NLS-1$
			String tool = text(n, "tool"); //$NON-NLS-1$
			String output = text(n, "output"); //$NON-NLS-1$
			Map<String, Object> args = new LinkedHashMap<>();
			JsonNode argNode = n.get("arguments"); //$NON-NLS-1$
			if (argNode != null && argNode.isObject()) {
				args = MAPPER.convertValue(argNode, Map.class);
			}
			calls.add(new McpToolCall(server, tool, args, output));
		}
		return calls;
	}

	/**
	 * Rebuilds the outcome-check node tree from a persisted array (or
	 * {@code null}).
	 */
	private static List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> readOutcomeNodes(
			JsonNode arr) {
		if (arr == null || !arr.isArray()) {
			return null;
		}
		List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> nodes = new java.util.ArrayList<>();
		for (JsonNode n : arr) {
			nodes.add(jsonToOutcomeNode(n));
		}
		return nodes;
	}

	private static com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node jsonToOutcomeNode(
			JsonNode n) {
		List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.PropCheck> props = new java.util.ArrayList<>();
		JsonNode propsArr = n.get("props"); //$NON-NLS-1$
		if (propsArr != null && propsArr.isArray()) {
			for (JsonNode p : propsArr) {
				props.add(new com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.PropCheck(
						text(p, "property"), text(p, "expected"), text(p, "actual"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						p.path("pass").asBoolean())); //$NON-NLS-1$
			}
		}
		List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> children = new java.util.ArrayList<>();
		JsonNode childrenArr = n.get("children"); //$NON-NLS-1$
		if (childrenArr != null && childrenArr.isArray()) {
			for (JsonNode c : childrenArr) {
				children.add(jsonToOutcomeNode(c));
			}
		}
		return new com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node(text(n, "label"), //$NON-NLS-1$
				n.path("found").asBoolean(), n.path("pass").asBoolean(), //$NON-NLS-1$ //$NON-NLS-2$
				text(n, "message"), props, children); //$NON-NLS-1$
	}
}

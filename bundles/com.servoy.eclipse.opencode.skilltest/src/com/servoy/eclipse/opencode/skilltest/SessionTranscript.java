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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A parsed opencode session transcript: the user prompt and the list of MCP
 * tool calls made during the session.
 * <p>
 * Transcripts can be built from a native {@code opencode export} JSON document
 * (primary source) or from the {@code parts} array returned by the
 * {@code POST /session/:id/message} HTTP call (fallback source). Parsing is
 * deliberately tolerant: opencode's JSON shape varies between versions, so the
 * extraction walks the tree defensively and never throws on missing fields.
 * </p>
 */
public final class SessionTranscript {

	private final String prompt;
	private final List<McpToolCall> toolCalls;
	private final long durationMillis;
	private final String agent;
	private final String model;
	private final String directory;
	private final RunMetrics.TokenUsage tokens;
	private final List<RunMetrics.RequestTokens> requestTokens;

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls) {
		this(prompt, toolCalls, 0L, null, null, null, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis) {
		this(prompt, toolCalls, durationMillis, null, null, null, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model) {
		this(prompt, toolCalls, durationMillis, agent, model, null, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model, String directory) {
		this(prompt, toolCalls, durationMillis, agent, model, directory, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model, String directory, RunMetrics.TokenUsage tokens) {
		this(prompt, toolCalls, durationMillis, agent, model, directory, tokens, null);
	}

	/**
	 * Full constructor (SVY-21660 expansion C): also carries the ordered
	 * per-request token list built from the export - the single source of the
	 * per-turn token figures the runner reports.
	 *
	 * @param requestTokens the ordered per-request token list, or {@code null}
	 *                      /empty when none was resolvable
	 */
	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model, String directory, RunMetrics.TokenUsage tokens,
			List<RunMetrics.RequestTokens> requestTokens) {
		this.prompt = prompt;
		this.toolCalls = toolCalls == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(toolCalls));
		this.durationMillis = Math.max(0L, durationMillis);
		this.agent = agent;
		this.model = model;
		this.directory = directory;
		this.tokens = tokens;
		this.requestTokens = requestTokens == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(requestTokens));
	}

	/**
	 * @return the first user text part found, or {@code null} if none
	 */
	public String getPrompt() {
		return prompt;
	}

	/**
	 * @return an unmodifiable list of the MCP tool calls in transcript order
	 */
	public List<McpToolCall> getToolCalls() {
		return toolCalls;
	}

	/**
	 * The wall-clock duration of the golden session, derived from the span between
	 * the earliest message {@code created} and the latest message {@code completed}
	 * timestamp in the export. Used to size the replay timeout relative to what the
	 * recorded run actually took, rather than reusing a fixed configured value.
	 *
	 * @return the session duration in milliseconds, or {@code 0} if unknown
	 */
	public long getDurationMillis() {
		return durationMillis;
	}

	/**
	 * @return the agent that produced the golden session (from the export
	 *         {@code info.agent}), or {@code null} if not recorded
	 */
	public String getAgent() {
		return agent;
	}

	/**
	 * @return the model that produced the golden session, formatted as
	 *         {@code providerID/modelID} (from the export {@code info.model}), or
	 *         {@code null} if not recorded
	 */
	public String getModel() {
		return model;
	}

	/**
	 * @return the working directory the golden session ran in (from the export
	 *         {@code info.directory}), or {@code null} if not recorded. This is the
	 *         workspace root under which the solution projects live.
	 */
	public String getDirectory() {
		return directory;
	}

	/**
	 * The best-effort token usage parsed from the export's {@code info.tokens}
	 * (session-level or summed per-message), or {@code null} when nothing
	 * resolvable was found (SVY-21660). {@code null} means "unknown" - never a
	 * misleading {@code 0}; an all-zero payload is treated as absent.
	 *
	 * @return the token usage, or {@code null} when unknown
	 */
	public RunMetrics.TokenUsage getTokens() {
		return tokens;
	}

	/**
	 * The ordered per-request token list parsed from the export (SVY-21660
	 * expansion C) - one entry per assistant message/request, each carrying that
	 * request's tokens and the tool calls / skill it performed. This is the
	 * <b>only</b> source for per-request attribution: the HTTP export carries each
	 * assistant message's own {@code tokens} object at the MESSAGE top level. Never
	 * {@code null}; empty when nothing resolvable was found.
	 *
	 * @return the per-request token list (never {@code null})
	 */
	public List<RunMetrics.RequestTokens> getRequestTokens() {
		return requestTokens;
	}

	/**
	 * Parses a native {@code opencode export} JSON document into a transcript.
	 * <p>
	 * The export JSON typically has a top-level {@code messages} array; each
	 * message has {@code role} and a {@code parts} array. This walks all parts,
	 * extracting the first user text as the prompt and every tool part as an
	 * {@link McpToolCall}. If a {@code messages} array is not found it falls back
	 * to scanning for any {@code parts} arrays anywhere in the tree.
	 * </p>
	 *
	 * @param exportRoot the root JSON node of the export
	 * @return a parsed transcript (never {@code null})
	 */
	public static SessionTranscript fromExport(JsonNode exportRoot) {
		if (exportRoot == null) {
			return new SessionTranscript(null, Collections.emptyList());
		}
		List<McpToolCall> calls = new ArrayList<>();
		String[] promptHolder = new String[1];
		long minCreated = Long.MAX_VALUE;
		long maxCompleted = Long.MIN_VALUE;

		// The agent/model may live either on the session-level `info` (older/other
		// exports) or, in the opencode v2 export shape, only on each assistant
		// message's own `info` (`messages[].info.agent` / `.model`) with the session
		// `info` carrying none. Read the session level first, then fall back to the
		// first assistant message that declares them - otherwise the replay sees
		// agent=null/model=null and runs the server default instead of the agent and
		// model the golden was actually recorded with (SVY-21366).
		JsonNode sessionInfo = exportRoot.get("info"); //$NON-NLS-1$
		String directory = sessionInfo != null ? textValue(sessionInfo, "directory") : null; //$NON-NLS-1$
		String agent = sessionInfo != null ? textValue(sessionInfo, "agent") : null; //$NON-NLS-1$
		String model = sessionInfo != null ? modelSpec(sessionInfo.get("model")) : null; //$NON-NLS-1$

		// Token usage (SVY-21660), best-effort and version-tolerant: read the
		// session-level `info.tokens` first; if it carries nothing, sum each
		// message's `info.tokens` (opencode v2 carries tokens per assistant message,
		// the session `info` often none) - mirroring how agent/model are resolved
		// session-first then per-message. A long[5] accumulator holds
		// input/output/reasoning/cacheRead/cacheWrite so a missing/renamed field
		// never throws; left all-zero it is treated as absent (-> null -> "unknown").
		long[] tokenTotals = new long[5];
		accumulateTokens(sessionInfo != null ? sessionInfo.get("tokens") : null, tokenTotals); //$NON-NLS-1$
		// Per-request token list (SVY-21660 expansion C): one entry per assistant
		// message/request, each carrying that request's own tokens and the tool
		// calls/skill collected for it. Built UNCONDITIONALLY from each message's own
		// tokens object, independent of the session-vs-per-message summing above.
		List<RunMetrics.RequestTokens> requestTokens = new ArrayList<>();
		// Did the session level actually supply non-zero token data? Track that in a
		// SEPARATE flag so the "sum per-message tokens" decision does not depend on
		// the accumulator itself - otherwise the first non-zero message would flip the
		// guard false and every later message would be skipped, under-counting a
		// multi-turn run to only its first assistant turn (SVY-21660).
		boolean sessionSuppliedTokens = !isAllZero(tokenTotals);

		JsonNode messages = exportRoot.get("messages"); //$NON-NLS-1$
		if (messages != null && messages.isArray()) {
			for (JsonNode message : messages) {
				// opencode 2.x message shape: { id, type, text?, content:[...] } where
				// tool calls live in `content` (type:"tool", name:.., state:..). The
				// pre-2.x shape was { role, parts:[...] } (or { info, parts }). Support
				// both: prefer `content` (v2), else `parts` (v1).
				String role = textValue(message, "role"); //$NON-NLS-1$
				String v2Type = textValue(message, "type"); //$NON-NLS-1$
				JsonNode info = message.get("info"); //$NON-NLS-1$
				// v2 export: agent/model are on each assistant message's `info`, not the
				// session `info`. Take them from the first message that declares them,
				// so a session that only records them per-message still replays with the
				// right agent + model instead of the server default.
				if (info != null) {
					if (agent == null) {
						agent = textValue(info, "agent"); //$NON-NLS-1$
					}
					if (model == null) {
						model = modelSpec(info.get("model")); //$NON-NLS-1$
					}
				}
				if (role == null && v2Type != null) {
					role = "assistant".equals(v2Type) ? "assistant" : v2Type; //$NON-NLS-1$ //$NON-NLS-2$
				}
				if (role == null && info != null) {
					role = textValue(info, "role"); //$NON-NLS-1$
				}
				JsonNode timeNode = info != null ? info.get("time") : message.get("time"); //$NON-NLS-1$ //$NON-NLS-2$
				long created = longValue(timeNode, "created"); //$NON-NLS-1$
				long completed = longValue(timeNode, "completed"); //$NON-NLS-1$
				if (created > 0) {
					minCreated = Math.min(minCreated, created);
				}
				if (completed > 0) {
					maxCompleted = Math.max(maxCompleted, completed);
				}
				// v2 user message carries its prompt on top-level `text`.
				if (promptHolder[0] == null && "user".equalsIgnoreCase(role)) { //$NON-NLS-1$
					String topText = textValue(message, "text"); //$NON-NLS-1$
					if (topText != null && !topText.isBlank()) {
						promptHolder[0] = topText;
					}
				}
				JsonNode content = message.get("content"); //$NON-NLS-1$
				JsonNode parts = message.get("parts"); //$NON-NLS-1$
				// Track where this message's tool calls begin so the per-request entry
				// (SVY-21660 C) captures exactly the calls made in this request.
				int callsBefore = calls.size();
				collectFromParts(content != null ? content : parts, role, promptHolder, calls);
				// Each request's own token figures live at the MESSAGE's top level in
				// the real opencode export - a sibling of id/type/agent/model/content/
				// cost: { "type":"assistant", "agent":"Orchestrator", "cost":0,
				// "tokens":{"input":49755,"output":61,...} } - with NO `info` wrapper.
				// Older exports wrap them in `info.tokens`. Read the top level first and
				// fall back to `info`, so both shapes are measured (SVY-21660 - verified
				// against a real export dump: reading only `info.tokens` left the
				// per-request list empty while the session total, which really is at
				// exportRoot.info.tokens, kept working).
				JsonNode msgTokens = message.get("tokens"); //$NON-NLS-1$
				if ((msgTokens == null || !msgTokens.isObject()) && info != null) {
					msgTokens = info.get("tokens"); //$NON-NLS-1$
				}
				// Sum per-message tokens only when the session level carried none, so a
				// session that records tokens solely per assistant message is still
				// measured. Gate on sessionSuppliedTokens (fixed once, before the walk)
				// and accumulate UNCONDITIONALLY for every message - gating on the
				// accumulator itself would skip every message after the first non-zero
				// one and under-count a multi-turn run (SVY-21660).
				if (!sessionSuppliedTokens) {
					accumulateTokens(msgTokens, tokenTotals);
				}
				// Build one per-request entry per message carrying a non-zero tokens
				// object (SVY-21660 C). Do NOT gate on the resolved `role` string: a
				// real export's assistant message may carry no top-level `role`/`type`
				// at all, and a non-zero tokens object is itself the reliable marker of
				// an assistant request - user/system messages carry none.
				if (msgTokens != null && msgTokens.isObject()) {
					long[] one = new long[5];
					accumulateTokens(msgTokens, one);
					if (!isAllZero(one)) {
						List<String> toolNames = new ArrayList<>();
						for (int i = callsBefore; i < calls.size(); i++) {
							toolNames.add(identity(calls.get(i)));
						}
						// The exact number of actual tool calls this request made,
						// INCLUDING nested Code-Mode calls (collectFromParts /
						// collectNestedCodeModeCalls appended them to `calls`). This call
						// count - not the tool-name list size - is what drives the Actual
						// column's first-call cost annotation mapping, so it lines up on
						// real fan-out / Code-Mode runs (SVY-21660).
						int callCount = calls.size() - callsBefore;
						// The agent sits beside `tokens` at the message's top level in the
						// real export; `info.agent` is the older shape.
						String requestAgent = textValue(message, "agent"); //$NON-NLS-1$
						if (requestAgent == null) {
							requestAgent = textValue(info, "agent"); //$NON-NLS-1$
						}
						requestTokens.add(new RunMetrics.RequestTokens(requestTokens.size(),
								new RunMetrics.TokenUsage(one[0], one[1], one[2], one[3], one[4]), toolNames,
								requestAgent, callCount));
					}
				}
			}
		} else {
			collectPartsRecursively(exportRoot, promptHolder, calls);
		}
		long duration = (minCreated != Long.MAX_VALUE && maxCompleted != Long.MIN_VALUE && maxCompleted > minCreated)
				? maxCompleted - minCreated
				: 0L;
		// All-zero (or nothing found) -> null -> "unknown": a missing measurement must
		// never read as a free 0-token run (SVY-21660).
		RunMetrics.TokenUsage tokens = isAllZero(tokenTotals) ? null
				: new RunMetrics.TokenUsage(tokenTotals[0], tokenTotals[1], tokenTotals[2], tokenTotals[3],
						tokenTotals[4]);
		return new SessionTranscript(promptHolder[0], calls, duration, agent, model, directory, tokens, requestTokens);
	}

	/** The {@code server.tool} identity of a call, for the per-request table. */
	private static String identity(McpToolCall call) {
		return (call.server() != null ? call.server() + "." : "") + call.tool(); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Parses the {@code parts} array (or a {@code { info, parts }} envelope) from a
	 * {@code POST /session/:id/message} response into a transcript. Used as a
	 * fallback when the native export is unavailable.
	 *
	 * @param messageResponse the response JSON node
	 * @return a parsed transcript (never {@code null})
	 */
	/**
	 * Formats an export {@code model} node as {@code providerID/modelID}. The node
	 * is either an object ({@code { providerID, modelID|id }}) or a plain string.
	 *
	 * @param modelNode the {@code model} JSON node (may be {@code null})
	 * @return the {@code providerID/modelID} spec, or {@code null} when absent
	 */
	private static String modelSpec(JsonNode modelNode) {
		if (modelNode == null || modelNode.isNull()) {
			return null;
		}
		if (modelNode.isObject()) {
			String providerId = textValue(modelNode, "providerID"); //$NON-NLS-1$
			String modelId = textValue(modelNode, "modelID"); //$NON-NLS-1$
			if (modelId == null) {
				modelId = textValue(modelNode, "id"); //$NON-NLS-1$
			}
			if (modelId == null) {
				return null;
			}
			return providerId != null ? providerId + "/" + modelId : modelId; //$NON-NLS-1$
		}
		if (modelNode.isValueNode()) {
			String text = modelNode.asText();
			return text != null && !text.isBlank() ? text : null;
		}
		return null;
	}

	public static SessionTranscript fromMessageResponse(JsonNode messageResponse) {
		if (messageResponse == null) {
			return new SessionTranscript(null, Collections.emptyList());
		}
		List<McpToolCall> calls = new ArrayList<>();
		String[] promptHolder = new String[1];
		JsonNode parts = messageResponse.get("parts"); //$NON-NLS-1$
		if (parts != null) {
			collectFromParts(parts, null, promptHolder, calls);
		} else {
			collectPartsRecursively(messageResponse, promptHolder, calls);
		}
		return new SessionTranscript(promptHolder[0], calls);
	}

	private static void collectPartsRecursively(JsonNode node, String[] promptHolder, List<McpToolCall> calls) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			JsonNode parts = node.get("parts"); //$NON-NLS-1$
			if (parts != null && parts.isArray()) {
				collectFromParts(parts, textValue(node, "role"), promptHolder, calls); //$NON-NLS-1$
			}
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				if (!"parts".equals(entry.getKey())) { //$NON-NLS-1$
					collectPartsRecursively(entry.getValue(), promptHolder, calls);
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				collectPartsRecursively(child, promptHolder, calls);
			}
		}
	}

	private static void collectFromParts(JsonNode parts, String role, String[] promptHolder, List<McpToolCall> calls) {
		if (parts == null || !parts.isArray()) {
			return;
		}
		for (JsonNode part : parts) {
			String type = textValue(part, "type"); //$NON-NLS-1$
			if (type != null && type.equalsIgnoreCase("text")) { //$NON-NLS-1$
				if (promptHolder[0] == null && (role == null || role.equalsIgnoreCase("user"))) { //$NON-NLS-1$
					String text = textValue(part, "text"); //$NON-NLS-1$
					if (text != null && !text.isBlank()) {
						promptHolder[0] = text;
					}
				}
			} else if (isToolPart(type, part)) {
				McpToolCall call = extractToolCall(part);
				if (call != null) {
					calls.add(call);
				}
				// opencode 2.x Code Mode: MCP tools are invoked from inside the
				// `execute` tool's script (e.g. tools["servoy-workspace"].listProjects()),
				// so they never appear as their own top-level tool parts - the only
				// record is the execute part's state.metadata.toolCalls[]. Harvest those
				// nested identities too, otherwise every Code-Mode MCP call is invisible
				// to both the coverage report and the golden comparator (SVY-21711).
				collectNestedCodeModeCalls(part, calls);
			}
		}
	}

	/**
	 * Extracts the MCP tool identities a Code Mode {@code execute} part invoked
	 * from its script, recorded in {@code state.metadata.toolCalls[]} as
	 * {@code { tool: "server.tool", status: .. }}. Each becomes an
	 * {@link McpToolCall} (server/tool split from the {@code server.tool} name);
	 * arguments/output are not available at this granularity. A part with no such
	 * metadata contributes nothing.
	 *
	 * @param part  a tool part (any tool; only {@code execute}-style parts carry
	 *              the nested metadata, so a guard on the name is unnecessary)
	 * @param calls the accumulator to add the nested calls to
	 */
	private static void collectNestedCodeModeCalls(JsonNode part, List<McpToolCall> calls) {
		JsonNode state = part.get("state"); //$NON-NLS-1$
		JsonNode metadata = state != null ? state.get("metadata") : part.get("metadata"); //$NON-NLS-1$ //$NON-NLS-2$
		JsonNode toolCalls = metadata != null ? metadata.get("toolCalls") : null; //$NON-NLS-1$
		if (toolCalls == null || !toolCalls.isArray()) {
			return;
		}
		for (JsonNode entry : toolCalls) {
			String name = firstText(entry, "tool", "toolName", "name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (name == null || name.isBlank()) {
				continue;
			}
			calls.add(McpToolCall.fromRawName(name, Collections.emptyMap(), null));
		}
	}

	private static boolean isToolPart(String type, JsonNode part) {
		if (type != null) {
			String lower = type.toLowerCase();
			if (lower.contains("tool")) { //$NON-NLS-1$
				return true;
			}
		}
		return part.has("tool") || part.has("toolName"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static McpToolCall extractToolCall(JsonNode part) {
		// v2 names the tool in `name`; pre-2.x used `tool`/`toolName`.
		String rawName = firstText(part, "tool", "toolName", "name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		JsonNode state = part.get("state"); //$NON-NLS-1$
		JsonNode inputNode = firstNode(part, "input", "arguments", "args"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (inputNode == null && state != null) {
			inputNode = firstNode(state, "input", "arguments", "args"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		String output = firstText(part, "output"); //$NON-NLS-1$
		if (output == null && state != null) {
			output = firstText(state, "output", "result"); //$NON-NLS-1$ //$NON-NLS-2$
			// v2 tool state carries its result in a content[] block array, not a
			// plain `output` string; flatten the text blocks (mirrors the webui's
			// normalizeToolState).
			if (output == null) {
				output = flattenContentText(state.get("content")); //$NON-NLS-1$
			}
		}
		Map<String, Object> arguments = toMap(inputNode);
		if (rawName == null && arguments.isEmpty() && output == null) {
			return null;
		}
		return McpToolCall.fromRawName(rawName, arguments, output);
	}

	/**
	 * Flattens a v2 {@code content:[{type:"text",text:..}]} block array into one
	 * string.
	 */
	private static String flattenContentText(JsonNode content) {
		if (content == null || !content.isArray()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : content) {
			String text = textValue(block, "text"); //$NON-NLS-1$
			if (text != null && !text.isBlank()) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(text);
			}
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static Map<String, Object> toMap(JsonNode node) {
		Map<String, Object> map = new LinkedHashMap<>();
		if (node == null || !node.isObject()) {
			return map;
		}
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			map.put(entry.getKey(), toJavaValue(entry.getValue()));
		}
		return map;
	}

	private static Object toJavaValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isInt() || node.isLong()) {
			return node.asLong();
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		if (node.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode child : node) {
				list.add(toJavaValue(child));
			}
			return list;
		}
		if (node.isObject()) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				map.put(entry.getKey(), toJavaValue(entry.getValue()));
			}
			return map;
		}
		return node.asText();
	}

	private static String textValue(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isValueNode() ? value.asText() : null;
	}

	private static long longValue(JsonNode node, String field) {
		if (node == null) {
			return 0L;
		}
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asLong() : 0L;
	}

	/**
	 * Nested, null-tolerant long read: {@code node.get(outer).get(inner)}. Returns
	 * {@code 0} on any missing/renamed level so a version drift never throws
	 * (SVY-21660).
	 */
	private static long longValue(JsonNode node, String outer, String inner) {
		if (node == null) {
			return 0L;
		}
		JsonNode nested = node.get(outer);
		return longValue(nested, inner);
	}

	/**
	 * Accumulates a {@code tokens} node's best-effort sub-fields into
	 * {@code totals} ([input, output, reasoning, cacheRead, cacheWrite]). Tolerates
	 * a missing/renamed field (adds {@code 0});
	 * {@code cache.read}/{@code cache.write} are read from the nested {@code cache}
	 * object (SVY-21660).
	 */
	private static void accumulateTokens(JsonNode tokensNode, long[] totals) {
		if (tokensNode == null || !tokensNode.isObject()) {
			return;
		}
		totals[0] += longValue(tokensNode, "input"); //$NON-NLS-1$
		totals[1] += longValue(tokensNode, "output"); //$NON-NLS-1$
		totals[2] += longValue(tokensNode, "reasoning"); //$NON-NLS-1$
		totals[3] += longValue(tokensNode, "cache", "read"); //$NON-NLS-1$ //$NON-NLS-2$
		totals[4] += longValue(tokensNode, "cache", "write"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** @return {@code true} when every accumulator entry is {@code 0}. */
	private static boolean isAllZero(long[] totals) {
		for (long t : totals) {
			if (t != 0L) {
				return false;
			}
		}
		return true;
	}

	private static String firstText(JsonNode node, String... fields) {
		if (node == null) {
			return null;
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value != null && value.isValueNode() && !value.isNull()) {
				return value.asText();
			}
		}
		return null;
	}

	private static JsonNode firstNode(JsonNode node, String... fields) {
		if (node == null) {
			return null;
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value != null && !value.isNull()) {
				return value;
			}
		}
		return null;
	}
}

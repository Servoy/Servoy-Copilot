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

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls) {
		this(prompt, toolCalls, 0L, null, null, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis) {
		this(prompt, toolCalls, durationMillis, null, null, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model) {
		this(prompt, toolCalls, durationMillis, agent, model, null);
	}

	public SessionTranscript(String prompt, List<McpToolCall> toolCalls, long durationMillis, String agent,
			String model, String directory) {
		this.prompt = prompt;
		this.toolCalls = toolCalls == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(toolCalls));
		this.durationMillis = Math.max(0L, durationMillis);
		this.agent = agent;
		this.model = model;
		this.directory = directory;
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

		JsonNode sessionInfo = exportRoot.get("info"); //$NON-NLS-1$
		String agent = sessionInfo != null ? textValue(sessionInfo, "agent") : null; //$NON-NLS-1$
		String directory = sessionInfo != null ? textValue(sessionInfo, "directory") : null; //$NON-NLS-1$
		String model = null;
		if (sessionInfo != null) {
			JsonNode modelNode = sessionInfo.get("model"); //$NON-NLS-1$
			if (modelNode != null && modelNode.isObject()) {
				String providerId = textValue(modelNode, "providerID"); //$NON-NLS-1$
				String modelId = textValue(modelNode, "modelID"); //$NON-NLS-1$
				if (modelId == null) {
					modelId = textValue(modelNode, "id"); //$NON-NLS-1$
				}
				if (modelId != null) {
					model = providerId != null ? providerId + "/" + modelId : modelId; //$NON-NLS-1$
				}
			} else if (modelNode != null && modelNode.isValueNode()) {
				model = modelNode.asText();
			}
		}

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
				collectFromParts(content != null ? content : parts, role, promptHolder, calls);
			}
		} else {
			collectPartsRecursively(exportRoot, promptHolder, calls);
		}
		long duration = (minCreated != Long.MAX_VALUE && maxCompleted != Long.MIN_VALUE && maxCompleted > minCreated)
				? maxCompleted - minCreated
				: 0L;
		return new SessionTranscript(promptHolder[0], calls, duration, agent, model, directory);
	}

	/**
	 * Parses the {@code parts} array (or a {@code { info, parts }} envelope) from a
	 * {@code POST /session/:id/message} response into a transcript. Used as a
	 * fallback when the native export is unavailable.
	 *
	 * @param messageResponse the response JSON node
	 * @return a parsed transcript (never {@code null})
	 */
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
			}
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

	/** Flattens a v2 {@code content:[{type:"text",text:..}]} block array into one string. */
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

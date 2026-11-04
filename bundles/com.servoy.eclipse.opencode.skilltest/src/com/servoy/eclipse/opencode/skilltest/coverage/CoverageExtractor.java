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

package com.servoy.eclipse.opencode.skilltest.coverage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.servoy.eclipse.opencode.skilltest.McpToolCall;

/**
 * Turns a baseline's captured tool calls plus its resolved builder agent into a
 * {@link CoverageObservation} (SVY-21711 §3.1, as corrected).
 * <p>
 * Classification of each {@link McpToolCall}:
 * </p>
 * <ul>
 * <li>{@code tool == "skill"} -&gt; the skill dimension (skill id probed from
 * the call's arguments; if no id is found, the raw {@code skill} tool name is
 * recorded so the dimension is still non-empty),</li>
 * <li>{@code tool == "task"} <b>or {@code "subagent"}</b> -&gt; the agents
 * dimension (the dispatched agent name probed from the arguments - the real
 * pack dispatches via the {@code subagent} tool),</li>
 * <li>an opencode built-in/core tool ({@code read}, {@code write},
 * {@code execute}, ...) -&gt; dropped entirely, never any dimension, so
 * built-ins do not pollute the MCP dimension,</li>
 * <li>otherwise -&gt; the MCP-tools dimension, as a {@code server_tool}
 * identity (or a bare {@code tool} when the server is {@code null}).</li>
 * </ul>
 * <p>
 * The builder's {@code effectiveAgent} is always added to the agents dimension.
 * Argument-key probing is deliberately tolerant - opencode's argument shapes
 * vary - mirroring {@code SessionTranscript}'s defensive style.
 * </p>
 */
public final class CoverageExtractor {

	/** The {@code skill}-tool name that signals a skill load. */
	private static final String SKILL_TOOL = "skill"; //$NON-NLS-1$

	/** The {@code task}-tool name that signals a sub-agent dispatch. */
	private static final String TASK_TOOL = "task"; //$NON-NLS-1$

	/**
	 * The {@code subagent}-tool name that signals a sub-agent dispatch (the real
	 * pack uses this rather than {@code task}).
	 */
	private static final String SUBAGENT_TOOL = "subagent"; //$NON-NLS-1$

	/** Argument keys that may carry the loaded skill id on a {@code skill} call. */
	private static final String[] SKILL_ID_KEYS = { "id", "skill", "skillId", "name" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/**
	 * Argument keys that may carry the dispatched agent name on a {@code task} /
	 * {@code subagent} call.
	 */
	private static final String[] AGENT_KEYS = { "subagent_type", "subagentType", "agentType", "agent", "agentName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			"name" }; //$NON-NLS-1$

	/**
	 * opencode built-in/core and dispatch/skill tools that are <b>not</b> MCP
	 * tools. A call whose tool name matches one of these (case-insensitively) is
	 * dropped from the MCP dimension entirely - it is neither a skill, nor an
	 * agent, nor an MCP tool - so built-ins never surface as off-catalog MCP noise.
	 */
	private static final Set<String> BUILTIN_TOOLS = Set.of("read", "write", "edit", "bash", "shell", "glob", "grep", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			"list", "ls", "webfetch", "websearch", "execute", "question", "patch", "todoread", "todowrite", "task", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
			"subagent", "skill", "invalid"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private CoverageExtractor() {
	}

	/**
	 * Builds the per-baseline observation from the captured tool calls and the
	 * resolved builder agent.
	 *
	 * @param baselineId     the baseline id (may be {@code null})
	 * @param actualCalls    the tool calls captured for the run (may be
	 *                       {@code null}/empty)
	 * @param effectiveAgent the builder's resolved agent (may be
	 *                       {@code null}/blank)
	 * @return a non-{@code null} observation
	 */
	public static CoverageObservation observe(String baselineId, List<McpToolCall> actualCalls, String effectiveAgent) {
		Set<String> skills = new LinkedHashSet<>();
		Set<String> agents = new LinkedHashSet<>();
		Set<String> mcpTools = new LinkedHashSet<>();

		// The builder agent always counts as "an agent that ran".
		if (effectiveAgent != null && !effectiveAgent.isBlank()) {
			agents.add(effectiveAgent.trim());
		}

		if (actualCalls != null) {
			for (McpToolCall call : actualCalls) {
				if (call == null || call.tool() == null) {
					continue;
				}
				String tool = call.tool();
				if (SKILL_TOOL.equalsIgnoreCase(tool)) {
					String skillId = probe(call.arguments(), SKILL_ID_KEYS);
					skills.add(skillId != null ? skillId : SKILL_TOOL);
				} else if (TASK_TOOL.equalsIgnoreCase(tool) || SUBAGENT_TOOL.equalsIgnoreCase(tool)) {
					String agent = probe(call.arguments(), AGENT_KEYS);
					if (agent != null) {
						agents.add(agent);
					}
				} else if (isBuiltin(tool)) {
					// opencode built-in/core tool: not an MCP tool, drop it entirely.
					continue;
				} else {
					mcpTools.add(identity(call));
				}
			}
		}
		return new CoverageObservation(baselineId, skills, agents, mcpTools);
	}

	/** @return {@code true} if the tool name is an opencode built-in/core tool. */
	private static boolean isBuiltin(String tool) {
		return tool != null && BUILTIN_TOOLS.contains(tool.toLowerCase(Locale.ROOT));
	}

	/**
	 * @return the identity of a real MCP call: {@code server_tool} (opencode's
	 *         canonical form), or a bare {@code tool} when the server is
	 *         {@code null}. The underscore form matches the reflected catalog
	 *         identity; {@link CoverageReport} canonicalizes both sides so a
	 *         {@code server.tool} observation still matches a {@code server_tool}
	 *         catalog entry.
	 */
	private static String identity(McpToolCall call) {
		return call.server() != null ? call.server() + "_" + call.tool() : call.tool(); //$NON-NLS-1$
	}

	/**
	 * Tolerantly probes an argument map for the first non-blank textual value under
	 * any of the candidate keys.
	 *
	 * @param arguments the call arguments (may be {@code null})
	 * @param keys      the candidate keys, in preference order
	 * @return the first matching trimmed value, or {@code null} when none found
	 */
	private static String probe(Map<String, Object> arguments, String[] keys) {
		if (arguments == null || arguments.isEmpty()) {
			return null;
		}
		for (String key : keys) {
			Object value = arguments.get(key);
			if (value instanceof String s && !s.isBlank()) {
				return s.trim();
			}
		}
		return null;
	}
}

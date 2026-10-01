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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares an actual {@link SessionTranscript} against a baseline's golden
 * transcript according to the "same result" definition in the spec (Â§3.4):
 * tool-set presence, tolerant (or overridden) argument equivalence, optional
 * ordered/strict modes, and a forbidden-tool check. Returns a structured
 * {@link Result} carrying a human-readable diff.
 * <p>
 * Free-text / natural-language arguments (a subagent {@code task} prompt, a
 * {@code description}, a {@code message}, etc.) are <b>prose</b> and are NOT
 * compared by default: the model legitimately rephrases them between runs while
 * producing the same outcome, so comparing them turns an equivalent run into a
 * spurious FAIL (Â§3.4: "prose ... is never compared"). A baseline can still opt
 * any such argument back into comparison by naming it in {@code argOverrides}.
 * </p>
 */
public final class TranscriptComparator {

	/**
	 * Argument names treated as free-text prose and ignored by the default
	 * comparison. These carry natural-language model output (task delegation
	 * prompts, human-readable descriptions/titles/messages) that varies between
	 * runs without changing the outcome. Compared only when a baseline lists them
	 * explicitly in {@code argOverrides}.
	 */
	private static final Set<String> PROSE_ARG_NAMES = Set.of(
			"prompt", //$NON-NLS-1$
			"description", //$NON-NLS-1$
			"message", //$NON-NLS-1$
			"title", //$NON-NLS-1$
			"reason", //$NON-NLS-1$
			"summary", //$NON-NLS-1$
			"instructions", //$NON-NLS-1$
			"text", //$NON-NLS-1$
			"content", //$NON-NLS-1$
			"comment", //$NON-NLS-1$
			"explanation"); //$NON-NLS-1$

	private static boolean isProseArg(String argName) {
		return argName != null && PROSE_ARG_NAMES.contains(argName.toLowerCase(Locale.ROOT));
	}

	/**
	 * The outcome of a comparison.
	 *
	 * @param match {@code true} if the actual run produced the same result
	 * @param diff  a human-readable explanation (empty when {@code match})
	 */
	public record Result(boolean match, String diff) {
	}

	private final Baseline baseline;

	public TranscriptComparator(Baseline baseline) {
		this.baseline = baseline;
	}

	/**
	 * Compares {@code actual} against the baseline's golden transcript.
	 *
	 * @param actual the transcript produced by the current run
	 * @return the structured comparison result
	 */
	public Result compare(SessionTranscript actual) {
		Baseline.Compare cfg = baseline.compare();
		List<McpToolCall> expectedCalls = baseline.goldenTranscript() != null
				? baseline.goldenTranscript().getToolCalls()
				: List.of();
		List<McpToolCall> actualCalls = actual != null ? actual.getToolCalls() : List.of();

		StringBuilder diff = new StringBuilder();
		boolean ok = true;

		// 1. Forbidden calls
		for (Baseline.ToolRef forbidden : cfg.forbidden()) {
			for (McpToolCall call : actualCalls) {
				if (call.matchesIdentity(forbidden.server(), forbidden.tool())) {
					ok = false;
					diff.append("- forbidden tool called: ").append(identity(forbidden.server(), forbidden.tool())) //$NON-NLS-1$
							.append('\n');
				}
			}
		}

		// 2. Each expected call must be matched by an actual call
		List<McpToolCall> remaining = new ArrayList<>(actualCalls);
		int lastMatchedIndex = -1;
		for (McpToolCall expected : expectedCalls) {
			int matchIndex = findMatch(expected, remaining, cfg);
			if (matchIndex < 0) {
				ok = false;
				diff.append("- missing or mismatched expected call: ") //$NON-NLS-1$
						.append(describe(expected)).append('\n');
				continue;
			}
			if (cfg.ordered()) {
				int absoluteIndex = actualCalls.indexOf(remaining.get(matchIndex));
				if (absoluteIndex < lastMatchedIndex) {
					ok = false;
					diff.append("- call out of expected order: ").append(describe(expected)).append('\n'); //$NON-NLS-1$
				}
				lastMatchedIndex = absoluteIndex;
			}
			remaining.remove(matchIndex);
		}

		// 3. Strict mode: no extra calls allowed
		if (cfg.strict() && !remaining.isEmpty()) {
			ok = false;
			for (McpToolCall extra : remaining) {
				diff.append("- unexpected extra call (strict): ").append(describe(extra)).append('\n'); //$NON-NLS-1$
			}
		}

		return new Result(ok, diff.toString());
	}

	private int findMatch(McpToolCall expected, List<McpToolCall> candidates, Baseline.Compare cfg) {
		for (int i = 0; i < candidates.size(); i++) {
			McpToolCall candidate = candidates.get(i);
			if (candidate.matchesIdentity(expected.server(), expected.tool())
					&& argumentsMatch(expected, candidate, cfg)) {
				return i;
			}
		}
		return -1;
	}

	private boolean argumentsMatch(McpToolCall expected, McpToolCall actual, Baseline.Compare cfg) {
		var overrides = overridesFor(expected, cfg);
		for (var entry : expected.arguments().entrySet()) {
			String argName = entry.getKey();
			Object expectedValue = entry.getValue();
			Object actualValue = actual.arguments().get(argName);
			ArgMatcher matcher = overrides != null ? overrides.get(argName) : null;
			if (matcher != null) {
				if (!matcher.matches(actualValue)) {
					return false;
				}
			} else if (isProseArg(argName)) {
				// Prose (task prompt, description, ...) is never compared by default;
				// the model rephrases it while producing the same outcome. A baseline
				// that cares can pin it via argOverrides (handled by the branch above).
				continue;
			} else if (!ArgMatcher.tolerantEquals(expectedValue, actualValue)) {
				return false;
			}
		}
		// argOverrides may reference args the baseline transcript did not capture
		if (overrides != null) {
			for (var overrideEntry : overrides.entrySet()) {
				if (!expected.arguments().containsKey(overrideEntry.getKey())) {
					Object actualValue = actual.arguments().get(overrideEntry.getKey());
					if (!overrideEntry.getValue().matches(actualValue)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private java.util.Map<String, ArgMatcher> overridesFor(McpToolCall expected, Baseline.Compare cfg) {
		if (cfg.argOverrides().isEmpty()) {
			return null;
		}
		if (expected.server() != null) {
			var byDot = cfg.argOverrides().get(expected.server() + "." + expected.tool()); //$NON-NLS-1$
			if (byDot != null) {
				return byDot;
			}
			var byUnderscore = cfg.argOverrides().get(expected.server() + "_" + expected.tool()); //$NON-NLS-1$
			if (byUnderscore != null) {
				return byUnderscore;
			}
		}
		return cfg.argOverrides().get(expected.tool());
	}

	private static String describe(McpToolCall call) {
		return identity(call.server(), call.tool()) + " args=" + call.arguments(); //$NON-NLS-1$
	}

	private static String identity(String server, String tool) {
		return (server != null ? server + "." : "") + tool; //$NON-NLS-1$ //$NON-NLS-2$
	}
}

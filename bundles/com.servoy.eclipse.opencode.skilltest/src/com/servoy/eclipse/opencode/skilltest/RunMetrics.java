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

import java.util.List;

/**
 * Immutable cost/efficiency metrics captured for one skill-test run
 * (SVY-21660).
 * <p>
 * A top-level value type (not nested on {@link SkillTestResult}) so
 * {@link SessionTranscript} can build the {@link TokenUsage} part without
 * depending on {@link SkillTestResult}, and so the value type stays reusable.
 * </p>
 * <ul>
 * <li>{@link #toolCalls} is the <b>primary</b>, always-reliable,
 * provider-independent metric (the number of MCP tool calls, including nested
 * Code-Mode {@code execute} calls). Never negative.</li>
 * <li>{@link #tokens} is <b>best-effort</b> token usage. {@code null} means the
 * export carried no resolvable token data, so every token surface must read
 * "unknown" - never a misleading {@code 0}.</li>
 * <li>{@link #kiro} is an <b>optional, best-effort</b> estimate read from the
 * local {@code kiro-usage.json} snapshot. {@code null} means the usage file was
 * absent or the session was not found, so Kiro surfaces read "unknown".</li>
 * </ul>
 *
 * <p>
 * The scope expansion (SVY-21660 A&ndash;D) adds <b>additive</b> fields:
 * {@link #creditsDelta} - the accurate per-scenario Kiro credits consumed
 * (&Delta;used, an estimate on a shared account; {@code null} &rarr;
 * "unknown"), and {@link #requestTokens} - the ordered per-request token list
 * used to rank the most inefficient step ({@code null}/empty &rarr; "unknown").
 * The account-bracket revision (SVY-21660 live-data follow-up) additionally
 * carries {@link #creditsBefore} / {@link #creditsAfter} /
 * {@link #creditsLimit} - the account {@code used} read before / after the
 * scenario and the account {@code limit} - so a surface can show "before: X /
 * limit", "after: Y", "&Delta;: Z" and not only the delta. All degrade to
 * "unknown" exactly like {@link #tokens}/{@link #kiro} when their source is
 * absent.
 * </p>
 *
 * @param toolCalls     the number of tool calls (the primary metric;
 *                      {@code >= 0})
 * @param tokens        the best-effort token usage, or {@code null} for
 *                      "unknown"
 * @param kiro          the optional Kiro estimate, or {@code null} for
 *                      "unknown"
 * @param creditsDelta  the accurate per-scenario Kiro credits consumed
 *                      (&Delta;used; an estimate), or {@code null} for
 *                      "unknown"
 * @param requestTokens the ordered per-request token list (one per assistant
 *                      request), or {@code null}/empty for "unknown"
 * @param creditsBefore the account {@code used} read before the first prompt,
 *                      or {@code null} for "unknown"
 * @param creditsAfter  the account {@code used} read after completion, or
 *                      {@code null} for "unknown"
 * @param creditsLimit  the account {@code limit}, or {@code null} for "unknown"
 */
public record RunMetrics(int toolCalls, TokenUsage tokens, KiroUsage kiro, Double creditsDelta,
		List<RequestTokens> requestTokens, Double creditsBefore, Double creditsAfter, Double creditsLimit) {

	/**
	 * Canonical constructor; normalises {@link #requestTokens} to an unmodifiable
	 * list (never {@code null}) so callers can iterate without a guard.
	 */
	public RunMetrics {
		requestTokens = requestTokens == null ? List.of() : List.copyOf(requestTokens);
	}

	/**
	 * Iteration-1 convenience constructor: the expansion fields default to
	 * "unknown" ({@code creditsDelta == null}, empty {@code requestTokens}, all
	 * bracket readings {@code null}). Keeps every pre-expansion
	 * {@code new RunMetrics(toolCalls, tokens, kiro)} call site compiling.
	 *
	 * @param toolCalls the number of tool calls (the primary metric; {@code >= 0})
	 * @param tokens    the best-effort token usage, or {@code null} for "unknown"
	 * @param kiro      the optional Kiro estimate, or {@code null} for "unknown"
	 */
	public RunMetrics(int toolCalls, TokenUsage tokens, KiroUsage kiro) {
		this(toolCalls, tokens, kiro, null, List.of(), null, null, null);
	}

	/**
	 * Five-arg convenience constructor: the account before/after/limit bracket
	 * readings default to "unknown" ({@code null}). The headline
	 * {@link #creditsDelta} and the per-request list are still carried. Keeps the
	 * existing five-arg expansion call sites and persistence round-trip compiling.
	 *
	 * @param toolCalls     the number of tool calls (the primary metric;
	 *                      {@code >= 0})
	 * @param tokens        the best-effort token usage, or {@code null} for
	 *                      "unknown"
	 * @param kiro          the optional Kiro estimate, or {@code null} for
	 *                      "unknown"
	 * @param creditsDelta  the accurate per-scenario Kiro credits consumed, or
	 *                      {@code null} for "unknown"
	 * @param requestTokens the ordered per-request token list, or {@code null}
	 *                      /empty for "unknown"
	 */
	public RunMetrics(int toolCalls, TokenUsage tokens, KiroUsage kiro, Double creditsDelta,
			List<RequestTokens> requestTokens) {
		this(toolCalls, tokens, kiro, creditsDelta, requestTokens, null, null, null);
	}

	/**
	 * Best-effort token usage parsed from an opencode export's {@code info.tokens}.
	 * Cache read/write are captured and shown in the breakdown but are
	 * <b>excluded</b> from {@link #total()} (decision 1 in the spec): cache is a
	 * cost modifier, not new tokens.
	 *
	 * @param input      input (prompt) tokens
	 * @param output     output (completion) tokens
	 * @param reasoning  reasoning tokens
	 * @param cacheRead  cache-read tokens (shown, not in {@link #total()})
	 * @param cacheWrite cache-write tokens (shown, not in {@link #total()})
	 */
	public record TokenUsage(long input, long output, long reasoning, long cacheRead, long cacheWrite) {

		/**
		 * The headline token total: {@code input + output + reasoning}. Cache
		 * read/write are deliberately excluded (decision 1).
		 *
		 * @return the total of input, output and reasoning tokens
		 */
		public long total() {
			return input + output + reasoning;
		}
	}

	/**
	 * An apportioned Kiro usage estimate read from the local snapshot file. Always
	 * labelled an estimate in every surface (on a shared account other users' usage
	 * mixes in), and never an exact per-dollar figure.
	 *
	 * @param requests   the number of Kiro requests attributed to the session
	 * @param estCredits the estimated credits attributed to the session
	 */
	public record KiroUsage(int requests, double estCredits) {
	}

	/**
	 * One assistant request's token figure (SVY-21660 expansion C), with the tool
	 * calls / skill that request performed, so the report can rank requests and
	 * highlight the most inefficient step.
	 * <p>
	 * <b>Honest caveat:</b> opencode meters tokens <b>per request</b>, not per tool
	 * call. When a request contains several tool calls they all share this one
	 * request's token figure, so a per-tool cost is an <b>attribution/estimate</b>,
	 * not an exact split. Every surface that shows the per-request table states
	 * this.
	 * </p>
	 *
	 * @param index     the 0-based request index in session order
	 * @param tokens    this request's token usage (never {@code null})
	 * @param toolNames the tool call names in this request (may be empty)
	 * @param skill     the skill active in this request, or {@code null}
	 * @param callCount number of tool calls made in this request (incl. nested
	 *                  Code-Mode calls); drives the first-call cost annotation
	 *                  mapping. {@code >= 0}; {@code 0} when the count is unknown.
	 */
	public record RequestTokens(int index, TokenUsage tokens, List<String> toolNames, String skill, int callCount) {

		/** Canonical constructor; normalises {@link #toolNames} to a safe list. */
		public RequestTokens {
			toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
		}

		/**
		 * Backward-compatible convenience constructor for call sites that have no
		 * explicit per-request call count: defaults {@link #callCount} to {@code 0}
		 * (unknown), so the first-call cost annotation is simply not placed for that
		 * request rather than being mis-attributed.
		 *
		 * @param index     the 0-based request index in session order
		 * @param tokens    this request's token usage
		 * @param toolNames the tool call names in this request (may be empty)
		 * @param skill     the skill active in this request, or {@code null}
		 */
		public RequestTokens(int index, TokenUsage tokens, List<String> toolNames, String skill) {
			this(index, tokens, toolNames, skill, 0);
		}

		/**
		 * The cache-read ratio {@code cacheRead / input} - the fraction of this
		 * request's input that was served from cache. {@code 0} when input is {@code 0}
		 * or tokens are absent. A value near {@code 0} with a large input is the
		 * signature of the expensive "no caching, full context re-sent" request.
		 *
		 * @return the cache-read ratio in {@code [0, 1+]}, or {@code 0} when undefined
		 */
		public double cacheReadRatio() {
			if (tokens == null || tokens.input() <= 0L) {
				return 0d;
			}
			return (double) tokens.cacheRead() / (double) tokens.input();
		}

		/**
		 * Whether this request shows effectively no caching: a non-trivial input with a
		 * cache-read ratio near zero (the full context was re-sent uncached).
		 *
		 * @return {@code true} when the request re-sent a large uncached input
		 */
		public boolean noCaching() {
			return tokens != null && tokens.input() > 1000L && cacheReadRatio() < 0.01d;
		}
	}

	/**
	 * The accurate per-scenario credit-delta label (SVY-21660 expansion B):
	 * "unknown" when {@link #creditsDelta} is {@code null}, otherwise the delta
	 * with an explicit estimate / shared-account caveat.
	 *
	 * @return the credits label (never {@code null})
	 */
	public String creditsLabel() {
		// Surface before + after + Δ when the bracket readings are present (the
		// account used/limit from kiro-usage.json), not only the delta - so a
		// reader sees "before: X / limit, after: Y, Δ: Z" (SVY-21660 live-data
		// follow-up). Each part degrades to "unknown" independently.
		boolean haveBracket = creditsBefore != null || creditsAfter != null || creditsLimit != null;
		if (creditsDelta == null && !haveBracket) {
			return "unknown"; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		if (haveBracket) {
			sb.append("before: ").append(creditsBefore != null ? creditsBefore.toString() : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
			if (creditsLimit != null) {
				sb.append(" / ").append(creditsLimit); //$NON-NLS-1$
			}
			sb.append(", after: ").append(creditsAfter != null ? creditsAfter.toString() : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append(", "); //$NON-NLS-1$
		}
		sb.append("\u0394: "); //$NON-NLS-1$
		sb.append(creditsDelta != null ? "~" + creditsDelta : "sync pending / unknown"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append(" credits (estimate; shared account)"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * The most inefficient request - the one with the largest input (the context
	 * paid in full that turn). {@code null} when there is no per-request data.
	 *
	 * @return the costliest request, or {@code null} for "unknown"
	 */
	public RequestTokens mostInefficientRequest() {
		RequestTokens worst = null;
		for (RequestTokens rt : requestTokens) {
			if (rt.tokens() == null) {
				continue;
			}
			if (worst == null || rt.tokens().input() > worst.tokens().input()) {
				worst = rt;
			}
		}
		return worst;
	}

	/**
	 * The fixed base input: the minimum recurring input across requests - the
	 * context that is re-sent every turn. {@code -1} when there is no per-request
	 * data.
	 *
	 * @return the minimum per-request input, or {@code -1} for "unknown"
	 */
	public long baseInput() {
		long min = Long.MAX_VALUE;
		for (RequestTokens rt : requestTokens) {
			if (rt.tokens() != null) {
				min = Math.min(min, rt.tokens().input());
			}
		}
		return min == Long.MAX_VALUE ? -1L : min;
	}

	/**
	 * A compact per-request ranking label (SVY-21660 expansion C/D), rendered
	 * identically by the view, HTML and JUnit surfaces: "unknown" when there is no
	 * per-request data, otherwise the request count plus the most inefficient
	 * request (its index, input, cache-read ratio and tools/skill).
	 *
	 * @return the per-request ranking label (never {@code null})
	 */
	public String requestRankingLabel() {
		if (requestTokens.isEmpty()) {
			return "unknown"; //$NON-NLS-1$
		}
		RequestTokens worst = mostInefficientRequest();
		StringBuilder sb = new StringBuilder();
		sb.append(requestTokens.size()).append(" requests"); //$NON-NLS-1$
		long base = baseInput();
		if (base >= 0) {
			sb.append(", base input ").append(base); //$NON-NLS-1$
		}
		if (worst != null && worst.tokens() != null) {
			sb.append("; most inefficient: request #").append(worst.index()) //$NON-NLS-1$
					.append(" input ").append(worst.tokens().input()) //$NON-NLS-1$
					.append(String.format(", cache-read %.1f%%", worst.cacheReadRatio() * 100d)); //$NON-NLS-1$
			if (worst.noCaching()) {
				sb.append(" (no caching - full context re-sent every turn)"); //$NON-NLS-1$
			}
			if (!worst.toolNames().isEmpty()) {
				sb.append(" tools=").append(String.join(",", worst.toolNames())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (worst.skill() != null && !worst.skill().isBlank()) {
				sb.append(" skill=").append(worst.skill()); //$NON-NLS-1$
			}
		}
		return sb.toString();
	}

	/**
	 * The token surface label, rendered identically by the view, HTML and JUnit
	 * surfaces: "unknown" when {@link #tokens} is {@code null}, otherwise the total
	 * with a short breakdown.
	 *
	 * @return the token label (never {@code null})
	 */
	public String tokensLabel() {
		if (tokens == null) {
			return "unknown"; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		sb.append(tokens.total()).append(" (in ").append(tokens.input()) //$NON-NLS-1$
				.append(", out ").append(tokens.output()) //$NON-NLS-1$
				.append(", reasoning ").append(tokens.reasoning()); //$NON-NLS-1$
		if (tokens.cacheRead() > 0 || tokens.cacheWrite() > 0) {
			sb.append(", cache r/w ").append(tokens.cacheRead()).append('/').append(tokens.cacheWrite()); //$NON-NLS-1$
		}
		sb.append(')');
		return sb.toString();
	}

	/**
	 * The Kiro surface label: "unknown (estimate)" when {@link #kiro} is
	 * {@code null}, otherwise the request/credit figures with an explicit
	 * "(estimate)" tag.
	 *
	 * @return the Kiro label (never {@code null})
	 */
	public String kiroLabel() {
		if (kiro == null) {
			return "unknown (estimate)"; //$NON-NLS-1$
		}
		return kiro.requests() + " requests, ~" + kiro.estCredits() + " credits (estimate)"; //$NON-NLS-1$ //$NON-NLS-2$
	}
}

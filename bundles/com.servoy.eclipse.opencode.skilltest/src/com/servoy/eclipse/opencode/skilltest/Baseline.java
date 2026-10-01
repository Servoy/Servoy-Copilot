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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;

/**
 * A golden-fixture baseline: the {@code baseline.json} sidecar metadata plus the
 * parsed golden {@link SessionTranscript} loaded from its {@code export.json}.
 * <p>
 * The prompt to replay is {@link #promptOverride()} if set, otherwise the prompt
 * extracted from the golden transcript. Compare tuning lives in the nested
 * {@link Compare} record.
 * </p>
 * <p>
 * A baseline may also declare {@link #expected() expected outcomes}
 * ({@code expect.persists} in the sidecar): Servoy persists that must exist in
 * the active solution after the replay. When present, the runner treats these
 * as authoritative and the transcript comparison becomes informational
 * (SVY-21366 "focus on the outcome").
 * </p>
 */
public final class Baseline {

	/**
	 * A tool identity (server + tool) used in {@code forbidden} lists.
	 *
	 * @param server the MCP server name (may be {@code null} to match any server)
	 * @param tool   the tool name
	 */
	public record ToolRef(String server, String tool) {
	}

	/**
	 * Declares where the baseline's initial-state solution(s) come from, so the
	 * runner can return the workspace to a clean, known starting point before each
	 * replay. Parsed from the {@code precondition.source} block of the sidecar.
	 * <ul>
	 * <li>{@code GIT} - clone {@code location} (optionally at {@code ref}) and
	 * import the solution project(s) found in it.</li>
	 * <li>{@code FOLDER} - import the solution project(s) found under the
	 * {@code location} folder on disk.</li>
	 * <li>{@code EMPTY} - create a new empty solution named after the baseline's
	 * {@code solution} (via the MCP {@code createSolution} tool).</li>
	 * </ul>
	 *
	 * @param type     the source kind (never {@code null})
	 * @param location git repo URL or on-disk folder path; {@code null} for
	 *                 {@code EMPTY}
	 * @param ref      optional git branch/tag/commit; {@code null} = default branch
	 */
	public record Source(Type type, String location, String ref) {

		public enum Type {
			GIT, FOLDER, EMPTY
		}

		public Source {
			if (type == null) {
				type = Type.EMPTY;
			}
		}

		/** @return an EMPTY source (create a fresh empty solution). */
		public static Source empty() {
			return new Source(Type.EMPTY, null, null);
		}
	}

	/**
	 * The workspace fixture to reset before each replay attempt, JUnit
	 * {@code @Before}-style, so every run starts from the same state the golden was
	 * recorded against. Parsed from the {@code precondition} block of the sidecar.
	 * <p>
	 * All paths are relative to the {@code solution} project's root.
	 * {@code delete} paths are removed if present (files the agent creates, e.g.
	 * {@code forms/test1_.frm}); {@code restore} paths are reverted to their
	 * committed git {@code HEAD} content (tracked files the agent modifies, e.g.
	 * {@code solution_settings.obj}).
	 * </p>
	 *
	 * @param solution the Servoy solution/project name whose working tree to reset
	 * @param delete   project-relative paths to delete if present
	 * @param restore  project-relative paths to revert to git {@code HEAD}
	 */
	public record Fixture(String solution, List<String> delete, List<String> restore) {

		public Fixture {
			delete = delete == null ? Collections.emptyList() : List.copyOf(delete);
			restore = restore == null ? Collections.emptyList() : List.copyOf(restore);
		}

		/** @return {@code true} if there is nothing to reset */
		public boolean isEmpty() {
			return delete.isEmpty() && restore.isEmpty();
		}
	}

	/**
	 * Comparison tuning parsed from the {@code compare} block of the sidecar.
	 *
	 * @param ordered      whether tool-call order must match the baseline
	 * @param strict       whether any tool not in the baseline fails the run
	 * @param forbidden    tools that must never be called
	 * @param argOverrides per-tool, per-argument matcher overrides keyed by
	 *                     {@code "server.tool"} (or just {@code "tool"})
	 */
	public record Compare(boolean ordered, boolean strict, List<ToolRef> forbidden,
			Map<String, Map<String, ArgMatcher>> argOverrides) {

		public Compare {
			forbidden = forbidden == null ? Collections.emptyList() : List.copyOf(forbidden);
			argOverrides = argOverrides == null ? Collections.emptyMap() : Map.copyOf(argOverrides);
		}

		public static Compare defaults() {
			return new Compare(false, false, Collections.emptyList(), Collections.emptyMap());
		}
	}

	/**
	 * Optional behavioural verification via Servoy JSUnit, parsed from the
	 * {@code verify.jsunit} block of the sidecar (SVY-21366 §3.4b). The tests are
	 * owned by the <b>baseline</b>, not the solution: {@link #scripts} are copied
	 * from the baseline folder into the active solution after the skill run, then
	 * {@link #scope}/{@link #method} tell {@code JSUnitRunnerService} what to run.
	 *
	 * @param scripts        baseline-relative {@code .js} files to inject into the
	 *                       active solution before running (path each declares is
	 *                       relative to the solution project root, e.g.
	 *                       {@code scopes/skilltest_verify.js}); empty = run tests
	 *                       already present in the (folder/git) solution
	 * @param scope          the scope/form/{@code ALL}/{@code MODULES}/{@code FORMS}
	 *                       to run
	 * @param method         optional single {@code test_} method to run, else all
	 *                       in {@code scope}
	 * @param timeoutSeconds JSUnit run timeout
	 * @param required       if {@code false}, JSUnit failures are reported but do
	 *                       not fail the baseline
	 */
	public record JsUnitVerify(List<String> scripts, String scope, String method, int timeoutSeconds,
			boolean required) {

		public JsUnitVerify {
			scripts = scripts == null ? Collections.emptyList() : List.copyOf(scripts);
		}
	}

	private final String id;
	private final String title;
	private final boolean active;
	private final String promptOverride;
	private final String agent;
	private final String model;
	private final int maxAttempts;
	private final int timeoutSeconds;
	private final String solution;
	private final Source source;
	private final List<String> cleanProjects;
	private final Fixture fixture;
	private final Compare compare;
	private final List<OutcomeAssertion> expected;
	private final JsUnitVerify jsUnitVerify;
	private final SessionTranscript goldenTranscript;

	public Baseline(String id, String title, boolean active, String promptOverride, String agent, String model,
			int maxAttempts, int timeoutSeconds, String solution, Source source, List<String> cleanProjects,
			Fixture fixture, Compare compare, List<OutcomeAssertion> expected, JsUnitVerify jsUnitVerify,
			SessionTranscript goldenTranscript) {
		this.id = id;
		this.title = title;
		this.active = active;
		this.promptOverride = promptOverride;
		this.agent = agent;
		this.model = model;
		this.maxAttempts = maxAttempts;
		this.timeoutSeconds = timeoutSeconds;
		this.solution = solution;
		this.source = source == null ? Source.empty() : source;
		this.cleanProjects = cleanProjects == null ? Collections.emptyList() : List.copyOf(cleanProjects);
		this.fixture = fixture;
		this.compare = compare == null ? Compare.defaults() : compare;
		this.expected = expected == null ? Collections.emptyList() : List.copyOf(expected);
		this.jsUnitVerify = jsUnitVerify;
		this.goldenTranscript = goldenTranscript;
	}

	public String id() {
		return id;
	}

	public String title() {
		return title;
	}

	public boolean active() {
		return active;
	}

	public String promptOverride() {
		return promptOverride;
	}

	public String agent() {
		return agent;
	}

	public String model() {
		return model;
	}

	public int maxAttempts() {
		return maxAttempts;
	}

	public int timeoutSeconds() {
		return timeoutSeconds;
	}

	public String solution() {
		return solution;
	}

	/**
	 * @return additional project names to clean (delete with content) before setup,
	 *         alongside {@link #solution()} - typically the Servoy resources project
	 *         (e.g. {@code servoy_resources}) and any modules, mirroring the
	 *         integration tests' {@code deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES)}.
	 *         Parsed from {@code precondition.cleanProjects}. Never {@code null}.
	 */
	public List<String> cleanProjects() {
		return cleanProjects;
	}

	/**
	 * @return where the baseline's initial-state solution(s) come from (never
	 *         {@code null}; defaults to {@link Source#empty()})
	 */
	public Source source() {
		return source;
	}

	/**
	 * @return the workspace fixture to reset before each attempt, or {@code null}
	 *         if the baseline declares none
	 */
	public Fixture fixture() {
		return fixture;
	}

	public Compare compare() {
		return compare;
	}

	/**
	 * @return the expected outcome assertions ({@code expect.persists}); never
	 *         {@code null}. When non-empty the runner checks these against the
	 *         active solution's persist tree and treats them as authoritative.
	 */
	public List<OutcomeAssertion> expected() {
		return expected;
	}

	/**
	 * @return {@code true} if this baseline declares expected outcomes, in which
	 *         case outcome checking is authoritative over transcript comparison
	 */
	public boolean hasExpectedOutcomes() {
		return !expected.isEmpty();
	}

	/**
	 * @return the JSUnit behavioural verification declared by this baseline
	 *         ({@code verify.jsunit}), or {@code null} if none
	 */
	public JsUnitVerify jsUnitVerify() {
		return jsUnitVerify;
	}

	/**
	 * @return {@code true} if this baseline declares a JSUnit verification step to
	 *         run after the skill run + outcome check
	 */
	public boolean hasJsUnitVerify() {
		return jsUnitVerify != null;
	}

	public SessionTranscript goldenTranscript() {
		return goldenTranscript;
	}

	/**
	 * @return the prompt to replay: the override if present, else the golden
	 *         transcript's prompt
	 */
	public String effectivePrompt() {
		if (promptOverride != null && !promptOverride.isBlank()) {
			return promptOverride;
		}
		return goldenTranscript != null ? goldenTranscript.getPrompt() : null;
	}
}

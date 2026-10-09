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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.servoy.eclipse.opencode.Activator;

/**
 * The opt-in, real-LLM Servoy AI skill regression suite (SVY-21366).
 * <p>
 * For each active baseline this replays the baseline's prompt against the
 * <b>current</b> skills through the embedded "Servoy AI" (opencode) server and
 * asserts the run produces the <b>same result</b> — the same MCP tool calls
 * with equivalent arguments (never comparing model prose). It delegates all of
 * that to the reusable {@link SkillTestRunner} product engine.
 * <p>
 * <b>Opt-in / cost:</b> this is disabled by default. It emits real dynamic
 * tests only when launched with {@code -Dservoy.ai.skilltests=true}
 * <em>and</em> both {@code GENAI_API_KEY} and {@code SERVOY_SKILLS_ZIP} are
 * configured <em>and</em> at least one active baseline is found. Otherwise the
 * {@code @TestFactory} returns {@link Stream#empty()} — zero tests reported,
 * which is the honest, cost-free behaviour for a real-LLM suite (not a
 * misleading skip-to-green).
 * <p>
 * <b>How to run in Servoy Developer:</b> open Developer with a solution active
 * and the Servoy AI view working (so the embedded server + skills are
 * configured), then Run As → JUnit <b>Plug-in</b> Test (tooling:
 * {@code eclipse-pde_runJUnitPluginTestClass}) with VM args
 * {@code -Dservoy.ai.skilltests=true}. {@code GENAI_API_KEY} /
 * {@code SERVOY_SKILLS_ZIP} are already set by the product when Servoy AI is
 * enabled; pass them as {@code -D} for a bare PDE launch. It needs an
 * OSGi/workbench runtime and a live embedded server, so it cannot run via the
 * plain JUnit launcher.
 * <p>
 * The spec (§3.1) refers to this runnable suite as {@code SkillScenarioTests};
 * it is named {@code SkillScenarioIntegrationTest} here so the
 * {@code IntegrationTest} suffix signals it is a PDE plug-in test per
 * {@code AGENTS.md}.
 */
public class SkillScenarioIntegrationTest extends AbstractSkillScenarioTest {

	private static final long SERVER_WAIT_MS = 120_000L;

	@TestFactory
	Stream<DynamicTest> skillScenarios() {
		if (!enabled) {
			log(disabledReason + " -> emitting zero tests (by design)."); //$NON-NLS-1$
			return Stream.empty();
		}

		int port = ensureServerReadyOrMinusOne(SERVER_WAIT_MS);
		if (port <= 0) {
			// When the user explicitly enabled the suite, a server that never comes
			// up is a real failure, not a silent skip.
			return Stream.of(DynamicTest.dynamicTest("servoy-ai-server-ready", //$NON-NLS-1$
					() -> org.junit.jupiter.api.Assertions.fail(
							"Servoy AI skill suite is enabled but the embedded opencode server did not become ready within " //$NON-NLS-1$
									+ SERVER_WAIT_MS
									+ "ms. Open the Servoy AI view / ensure GENAI_API_KEY + SERVOY_SKILLS_ZIP are configured."))); //$NON-NLS-1$
		}

		File root = resolveBaselinesRoot();
		if (root == null) {
			log("No baselines root found (neither the fragment 'baselines/' folder nor ~/.servoy/opencode/skilltests) -> emitting zero tests."); //$NON-NLS-1$
			return Stream.empty();
		}

		List<Baseline> baselines = BaselineLoader.loadActive(root);
		if (baselines.isEmpty()) {
			log("No active baselines under " + root.getAbsolutePath() + " -> emitting zero tests."); //$NON-NLS-1$ //$NON-NLS-2$
			return Stream.empty();
		}

		SkillTestRunner runner = new SkillTestRunner();
		return baselines.stream().map(baseline -> DynamicTest.dynamicTest(displayName(baseline), () -> {
			SkillTestResult result = runner.runBaseline(baseline);
			org.junit.jupiter.api.Assertions.assertTrue(result.isPass(), () -> failureMessage(result));
		}));
	}

	private static String displayName(Baseline baseline) {
		String title = baseline.title();
		if (title != null && !title.isBlank()) {
			return baseline.id() + " - " + title; //$NON-NLS-1$
		}
		return baseline.id();
	}

	private static String failureMessage(SkillTestResult result) {
		StringBuilder sb = new StringBuilder();
		sb.append("Baseline '").append(result.getBaselineId()).append("' failed after ") //$NON-NLS-1$ //$NON-NLS-2$
				.append(result.getAttempts()).append(" attempt(s). Status: ").append(result.getStatus()); //$NON-NLS-1$
		String error = result.getErrorMessage();
		if (error != null && !error.isBlank()) {
			sb.append('\n').append("Error: ").append(error); //$NON-NLS-1$
		}
		String diff = result.getDiff();
		if (diff != null && !diff.isBlank()) {
			sb.append('\n').append("Diff (baseline vs actual tool calls):\n").append(diff); //$NON-NLS-1$
		}
		return sb.toString();
	}

	private static void log(String message) {
		Activator activator = Activator.getInstance();
		if (activator != null) {
			activator.logToConsole(message);
		}
		System.out.println("[Servoy AI skill suite] " + message); //$NON-NLS-1$
	}
}

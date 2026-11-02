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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guard test (SVY-21659): the committed scenario matrix prompt library must
 * stay consistent and must not rot.
 * <p>
 * The prompt library is a single human-readable markdown file,
 * {@code bundles/com.servoy.eclipse.opencode.skilltest/scenarios/SCENARIO-MATRIX.md},
 * carrying the solution setups and the twenty scenarios as copy-ready fenced
 * prompt blocks, plus the input artifacts the prompts reference under
 * {@code scenarios/inputs/}. The prompts <b>are</b> the deliverable; there is
 * no production Java helper. This test parses the matrix and fails on drift, in
 * the spirit of {@code OpencodePnpmApprovalTest}:
 * </p>
 * <ul>
 * <li>every scenario {@code ###} section has a non-empty fenced prompt
 * block;</li>
 * <li>every setup section has a non-empty fenced setup prompt;</li>
 * <li>the scenario count matches the stated total (lock-step anti-rot) and so
 * does the setup count;</li>
 * <li>every "starting state" that names a setup id refers to a setup section
 * that exists in the doc;</li>
 * <li>every input file the doc references exists on disk under
 * {@code scenarios/}.</li>
 * </ul>
 * <p>
 * No OSGi runtime is required: the committed bundle resource files are read
 * directly from disk, located by walking up from the test's working directory
 * to the repository root (the directory containing {@code bundles/}) — the same
 * approach {@code OpencodePnpmApprovalTest} uses.
 * </p>
 */
class ScenarioMatrixTest {

	/** Lock-step counts: the matrix must carry exactly these many prompts. */
	private static final int EXPECTED_SCENARIO_COUNT = 20;
	private static final int EXPECTED_SETUP_COUNT = 2;

	private static final String BUNDLE_SCENARIOS_REL = "bundles/com.servoy.eclipse.opencode.skilltest/scenarios";

	/** The scenario ids, in matrix order. */
	private static final List<String> SCENARIO_IDS = List.of("create-form-css-position", "create-form-responsive",
			"create-form-order-tabs-from-mockup", "create-valuelist-custom", "create-valuelist-db",
			"create-valuelist-global", "create-relation", "datalayer-open-order-totals", "datalayer-bulk-archive-save",
			"security-tenant-query-clean", "security-rawsql-injection", "reviewer-defect-find",
			"orchestrator-form-plus-unittest", "svycreateformtest-orders", "enhance-order-total-field",
			"svyfixstacktrace-order-totals", "fix-authenticator-not-called", "e2e-form-cypress-test",
			"modernize-method-syntax", "optimize-query-in-loop");

	/** The setup ids, in matrix order. */
	private static final List<String> SETUP_IDS = List.of("setup-crm-basic", "setup-auth-login");

	@Test
	@DisplayName("every scenario section has a non-empty fenced prompt block")
	void everyScenarioHasAPrompt() throws Exception {
		String matrix = readMatrix();
		for (String id : SCENARIO_IDS) {
			String prompt = fencedBlockAfterHeading(matrix, id);
			assertTrue(prompt != null && !prompt.isBlank(),
					"scenario '" + id + "' must have a non-empty fenced prompt block in SCENARIO-MATRIX.md");
		}
	}

	@Test
	@DisplayName("every setup section has a non-empty fenced setup prompt")
	void everySetupHasAPrompt() throws Exception {
		String matrix = readMatrix();
		for (String id : SETUP_IDS) {
			String prompt = fencedBlockAfterHeading(matrix, id);
			assertTrue(prompt != null && !prompt.isBlank(),
					"setup '" + id + "' must have a non-empty fenced setup prompt block in SCENARIO-MATRIX.md");
		}
	}

	@Test
	@DisplayName("the scenario and setup counts match the stated totals (lock-step anti-rot)")
	void countsAreLockStep() throws Exception {
		String matrix = readMatrix();
		int scenarioSections = countHeadings(matrix, SCENARIO_IDS);
		int setupSections = countHeadings(matrix, SETUP_IDS);
		assertTrue(scenarioSections == EXPECTED_SCENARIO_COUNT, "expected " + EXPECTED_SCENARIO_COUNT
				+ " known scenario sections but found " + scenarioSections
				+ " — dropping or renaming a prompt must update SCENARIO_IDS/EXPECTED_SCENARIO_COUNT (SVY-21659)");
		assertTrue(setupSections == EXPECTED_SETUP_COUNT,
				"expected " + EXPECTED_SETUP_COUNT + " known setup sections but found " + setupSections
						+ " — dropping or renaming a setup must update SETUP_IDS/EXPECTED_SETUP_COUNT (SVY-21659)");
		// Symmetric guard: an UNKNOWN scenario id added to the matrix must also be
		// registered here, so count the actual scenario headings (every "### <id>"
		// whose id is not a known setup) and require it to equal the known total.
		int actualScenarioHeadings = countScenarioHeadings(matrix);
		assertTrue(actualScenarioHeadings == EXPECTED_SCENARIO_COUNT,
				"SCENARIO-MATRIX.md has " + actualScenarioHeadings + " scenario sections but " + EXPECTED_SCENARIO_COUNT
						+ " are registered — a new scenario must be added to SCENARIO_IDS and"
						+ " EXPECTED_SCENARIO_COUNT bumped (SVY-21659)");
	}

	@Test
	@DisplayName("every setup id a scenario starts from exists as a setup section")
	void everyReferencedSetupExists() throws Exception {
		String matrix = readMatrix();
		Set<String> referenced = referencedSetupIds(matrix);
		assertFalse(referenced.isEmpty(),
				"no setup-backed scenarios found — the matrix should name at least one setup as a starting state");
		for (String setupId : referenced) {
			assertTrue(SETUP_IDS.contains(setupId), "a scenario's starting state names setup '" + setupId
					+ "' but that is not a known setup section in SCENARIO-MATRIX.md");
		}
	}

	@Test
	@DisplayName("every input file the matrix references exists on disk under scenarios/")
	void everyReferencedInputExists() throws Exception {
		File scenariosDir = resolveScenariosDir();
		String matrix = readMatrix();
		Set<String> inputs = referencedInputPaths(matrix);
		assertFalse(inputs.isEmpty(), "the matrix should reference at least one input file under inputs/");
		for (String rel : inputs) {
			File f = new File(scenariosDir, rel);
			assertTrue(f.isFile(), "input file referenced by SCENARIO-MATRIX.md is missing on disk: " + rel
					+ " (expected at " + f + ")");
		}
	}

	// --- helpers ---

	private static String readMatrix() throws Exception {
		File matrix = new File(resolveScenariosDir(), "SCENARIO-MATRIX.md");
		assertTrue(matrix.isFile(), "SCENARIO-MATRIX.md must exist at " + matrix);
		return Files.readString(matrix.toPath(), StandardCharsets.UTF_8);
	}

	/**
	 * Returns the first fenced ``` code block that follows the {@code ###} (or
	 * {@code ####}) heading whose text is exactly {@code id}, or {@code null} if
	 * the heading or the block is absent.
	 */
	static String fencedBlockAfterHeading(String markdown, String id) {
		String[] lines = markdown.split("\r\n|\r|\n", -1);
		int i = indexOfHeading(lines, id);
		if (i < 0) {
			return null;
		}
		// Find the opening fence after the heading, but stop at the next heading.
		for (int j = i + 1; j < lines.length; j++) {
			String stripped = lines[j].strip();
			if (stripped.startsWith("#")) {
				return null; // next heading reached before any fence
			}
			if (stripped.startsWith("```")) {
				StringBuilder body = new StringBuilder();
				for (int k = j + 1; k < lines.length; k++) {
					if (lines[k].strip().startsWith("```")) {
						return body.toString();
					}
					body.append(lines[k]).append('\n');
				}
				return null; // unterminated fence
			}
		}
		return null;
	}

	private static int indexOfHeading(String[] lines, String id) {
		for (int i = 0; i < lines.length; i++) {
			String stripped = lines[i].strip();
			if (stripped.startsWith("#")) {
				String text = stripped.replaceFirst("^#+\\s*", "");
				if (text.equals(id)) {
					return i;
				}
			}
		}
		return -1;
	}

	private static int countHeadings(String markdown, List<String> ids) {
		String[] lines = markdown.split("\r\n|\r|\n", -1);
		int count = 0;
		for (String id : ids) {
			if (indexOfHeading(lines, id) >= 0) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Counts the actual scenario sections: every {@code ### <id>} heading whose id
	 * is not one of the setup ids. A scenario added to the matrix without being
	 * registered in {@link #SCENARIO_IDS} is caught by this (symmetric anti-rot),
	 * where {@link #countHeadings} only catches removals/renames.
	 */
	private static int countScenarioHeadings(String markdown) {
		String[] lines = markdown.split("\r\n|\r|\n", -1);
		int count = 0;
		for (String line : lines) {
			String stripped = line.strip();
			if (stripped.startsWith("### ") && !stripped.startsWith("#### ")) {
				String text = stripped.replaceFirst("^#+\\s*", "");
				if (!SETUP_IDS.contains(text)) {
					count++;
				}
			}
		}
		return count;
	}

	// e.g. "Starting state: `setup-crm-basic`" or "name `setup-auth-login`"
	private static final Pattern SETUP_REF = Pattern.compile("`(setup-[a-z0-9-]+)`");

	private static Set<String> referencedSetupIds(String markdown) {
		Set<String> ids = new LinkedHashSet<>();
		Matcher m = SETUP_REF.matcher(markdown);
		while (m.find()) {
			ids.add(m.group(1));
		}
		// The setup section headings themselves also match; keep only the ones used
		// as a scenario starting state, i.e. those appearing after "Starting state:".
		Set<String> startingStates = new LinkedHashSet<>();
		Matcher s = Pattern.compile("Starting state:\\s*`(setup-[a-z0-9-]+)`").matcher(markdown);
		while (s.find()) {
			startingStates.add(s.group(1));
		}
		return startingStates.isEmpty() ? ids : startingStates;
	}

	// e.g. inputs/specs/03_order_tabs.html or inputs/setups/setup-crm-basic.md
	private static final Pattern INPUT_REF = Pattern.compile("\\binputs/[A-Za-z0-9_./-]+\\.(?:html|md)\\b");

	private static Set<String> referencedInputPaths(String markdown) {
		Set<String> paths = new LinkedHashSet<>();
		Matcher m = INPUT_REF.matcher(markdown);
		while (m.find()) {
			// Skip relative-up references that belong to the setup specs' own back-links
			// (e.g. ../../SCENARIO-MATRIX.md) — those start with ../ and won't match here
			// because the pattern is anchored at "inputs/".
			paths.add(m.group());
		}
		return paths;
	}

	/**
	 * Locates the committed {@code scenarios/} folder by walking up from several
	 * candidate starting points to the repository root. The test's working
	 * directory at runtime is not guaranteed, so this tries both the JVM working
	 * directory and the {@code user.dir} system property. Fails loudly (never
	 * silently skips) when the folder genuinely cannot be found.
	 */
	private static File resolveScenariosDir() {
		List<File> starts = new ArrayList<>();
		starts.add(new File("").getAbsoluteFile());
		starts.add(new File(System.getProperty("user.dir", ".")).getAbsoluteFile());
		for (File start : starts) {
			File dir = start;
			while (dir != null) {
				File candidate = new File(dir, BUNDLE_SCENARIOS_REL);
				if (new File(candidate, "SCENARIO-MATRIX.md").isFile()) {
					return candidate;
				}
				dir = dir.getParentFile();
			}
		}
		return fail("Could not locate " + BUNDLE_SCENARIOS_REL + " by walking up from the test working directory ("
				+ new File("").getAbsolutePath()
				+ "). The guard test needs the committed SCENARIO-MATRIX.md and inputs — see SVY-21659.");
	}
}

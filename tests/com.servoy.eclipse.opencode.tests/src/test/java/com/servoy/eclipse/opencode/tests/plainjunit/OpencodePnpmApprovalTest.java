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

package com.servoy.eclipse.opencode.tests.plainjunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guard test (SVY-21679): the committed pnpm build-approval config must stay in
 * lock-step with the pinned {@code @opencode/cli} version.
 * <p>
 * The plugin ships two committed resource files next to each other:
 * {@code bundles/com.servoy.eclipse.opencode/opencode/package.json} (which pins
 * {@code @opencode/cli} to an exact version) and
 * {@code bundles/com.servoy.eclipse.opencode/opencode/pnpm-workspace.yaml}
 * (whose {@code allowBuilds} map version-scopes the build-script approval as
 * {@code '@opencode/cli@<version>': true}). Because the approval key carries
 * the version, bumping the pin in {@code package.json} without updating
 * {@code pnpm-workspace.yaml} silently reverts the install to skipping the
 * postinstall script - leaving the 229-byte placeholder binary and the
 * {@code CreateProcess error=216} launch failure. This test fails exactly on
 * that drift.
 * </p>
 * <p>
 * No OSGi runtime is required: the committed bundle resource files are read
 * directly from disk, located by walking up from the test's working directory
 * to the repository root (the directory containing {@code bundles/}).
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpencodePnpmApprovalTest {
	private static final String OPENCODE_DEP = "@opencode/cli";

	private static final String BUNDLE_OPENCODE_REL = "bundles/com.servoy.eclipse.opencode/opencode";

	// --- the committed-config guard ---

	@Test
	public void committedConfig_allowBuildsMatchesPinnedVersion() throws Exception {
		File opencodeResDir = resolveBundleOpencodeDir();

		File packageJson = new File(opencodeResDir, "package.json");
		File pnpmWorkspace = new File(opencodeResDir, "pnpm-workspace.yaml");
		assertTrue(packageJson.isFile(), "committed package.json must exist at " + packageJson);
		assertTrue(pnpmWorkspace.isFile(), "committed pnpm-workspace.yaml must exist at " + pnpmWorkspace);

		String pinnedVersion = parsePinnedOpencodeVersion(
				Files.readString(packageJson.toPath(), StandardCharsets.UTF_8));
		assertNotNull(pinnedVersion,
				"package.json must declare a pinned " + OPENCODE_DEP + " version under dependencies");

		Map<String, Boolean> allowBuilds = parseAllowBuilds(
				Files.readString(pnpmWorkspace.toPath(), StandardCharsets.UTF_8));

		String expectedKey = OPENCODE_DEP + "@" + pinnedVersion;
		assertEquals(Boolean.TRUE, allowBuilds.get(expectedKey), "pnpm-workspace.yaml allowBuilds must list '"
				+ expectedKey
				+ "': true; bumping the version in package.json requires updating pnpm-workspace.yaml - see SVY-21679 / update-opencode skill");

		// No stale @opencode/cli@<otherVersion> approval left behind: the ONLY
		// @opencode/cli@* key must be the pinned one.
		for (String key : allowBuilds.keySet()) {
			if (key.startsWith(OPENCODE_DEP + "@")) {
				assertEquals(expectedKey, key,
						"pnpm-workspace.yaml allowBuilds has a stale " + OPENCODE_DEP + " approval '" + key
								+ "'; it must only list the pinned '" + expectedKey
								+ "' - see SVY-21679 / update-opencode skill");
			}
		}
	}

	// --- parsing-helper unit tests (prove the guard logic itself, inline) ---

	@Test
	public void parsePinnedOpencodeVersion_extractsDependencyValue() {
		String pkg = "{\n  \"name\": \"servoy-opencode-host\",\n  \"dependencies\": {\n    \"" + OPENCODE_DEP
				+ "\": \"2.1.1\"\n  }\n}";
		assertEquals("2.1.1", parsePinnedOpencodeVersion(pkg), "should read the exact pinned version");
	}

	@Test
	public void parseAllowBuilds_collectsOnlyTrueKeys() {
		String yaml = "strictDepBuilds: true\nallowBuilds:\n  '" + OPENCODE_DEP
				+ "@2.1.1': true\n  'some-other-dep@1.0.0': false\n";
		Map<String, Boolean> map = parseAllowBuilds(yaml);
		assertEquals(Boolean.TRUE, map.get(OPENCODE_DEP + "@2.1.1"), "the allowed key maps to true");
		assertEquals(Boolean.FALSE, map.get("some-other-dep@1.0.0"), "a false key is captured as false");
	}

	@Test
	public void guardLogic_matchingPair_passes() {
		String pkg = "{\"dependencies\":{\"" + OPENCODE_DEP + "\":\"2.1.1\"}}";
		String yaml = "allowBuilds:\n  '" + OPENCODE_DEP + "@2.1.1': true\n";
		assertTrue(isConsistent(pkg, yaml), "a matching version/approval pair must be consistent");
	}

	@Test
	public void guardLogic_mismatchedPair_fails() {
		// package.json pins 2.1.2 but pnpm-workspace.yaml still approves 2.1.1 ->
		// the guard must report drift.
		String pkg = "{\"dependencies\":{\"" + OPENCODE_DEP + "\":\"2.1.2\"}}";
		String yaml = "allowBuilds:\n  '" + OPENCODE_DEP + "@2.1.1': true\n";
		assertFalse(isConsistent(pkg, yaml), "a bumped pin with a stale allowBuilds key must be detected as drift");
	}

	@Test
	public void guardLogic_staleExtraKey_fails() {
		// The pinned version is approved, but a stale older approval lingers.
		String pkg = "{\"dependencies\":{\"" + OPENCODE_DEP + "\":\"2.1.1\"}}";
		String yaml = "allowBuilds:\n  '" + OPENCODE_DEP + "@2.1.1': true\n  '" + OPENCODE_DEP + "@2.0.9': true\n";
		assertFalse(isConsistent(pkg, yaml), "a leftover stale @opencode/cli approval must be detected as drift");
	}

	// --- helpers ---

	/**
	 * Applies the same consistency rule as the committed-config guard against
	 * in-memory file contents, so the guard logic can be proven without the on-disk
	 * files.
	 */
	private static boolean isConsistent(String packageJson, String pnpmWorkspaceYaml) {
		String pinned = parsePinnedOpencodeVersion(packageJson);
		if (pinned == null)
			return false;
		Map<String, Boolean> allowBuilds = parseAllowBuilds(pnpmWorkspaceYaml);
		String expectedKey = OPENCODE_DEP + "@" + pinned;
		if (!Boolean.TRUE.equals(allowBuilds.get(expectedKey)))
			return false;
		for (String key : allowBuilds.keySet()) {
			if (key.startsWith(OPENCODE_DEP + "@") && !expectedKey.equals(key))
				return false;
		}
		return true;
	}

	private static final Pattern OPENCODE_VERSION = Pattern
			.compile("\"" + Pattern.quote(OPENCODE_DEP) + "\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * Returns the value of {@code dependencies["@opencode/cli"]} or {@code null}.
	 */
	static String parsePinnedOpencodeVersion(String packageJson) {
		Matcher m = OPENCODE_VERSION.matcher(packageJson);
		return m.find() ? m.group(1) : null;
	}

	// Matches an allowBuilds entry like: '@opencode/cli@2.1.1': true (quotes
	// optional)
	private static final Pattern ALLOW_BUILD_ENTRY = Pattern
			.compile("^\\s+['\"]?([^'\"\\s:]+)['\"]?\\s*:\\s*(true|false)\\s*$");

	/**
	 * Collects the {@code allowBuilds} map from a {@code pnpm-workspace.yaml}: each
	 * quoted {@code name@version} key under the {@code allowBuilds:} block mapped
	 * to its boolean value. A line/regex parse is sufficient - no YAML library
	 * needed.
	 */
	static Map<String, Boolean> parseAllowBuilds(String yaml) {
		Map<String, Boolean> result = new LinkedHashMap<>();
		boolean inAllowBuilds = false;
		for (String raw : yaml.split("\r\n|\r|\n", -1)) {
			String line = raw;
			if (line.isBlank() || line.strip().startsWith("#"))
				continue;
			if (!line.startsWith(" ") && !line.startsWith("\t")) {
				// A new top-level key ends the allowBuilds block.
				inAllowBuilds = line.strip().startsWith("allowBuilds:");
				continue;
			}
			if (!inAllowBuilds)
				continue;
			Matcher m = ALLOW_BUILD_ENTRY.matcher(line);
			if (m.matches()) {
				result.put(m.group(1), Boolean.valueOf(m.group(2)));
			}
		}
		return result;
	}

	/**
	 * Locates the committed {@code bundles/com.servoy.eclipse.opencode/opencode}
	 * resource folder by walking up from several candidate starting points to the
	 * repository root (the first ancestor that contains {@code bundles/}). The
	 * test's working directory at runtime is not guaranteed, so this tries both the
	 * JVM working directory and the {@code user.dir} system property. Fails loudly
	 * (never silently skips) when the files genuinely cannot be found.
	 */
	private static File resolveBundleOpencodeDir() {
		for (File start : new File[] { new File("").getAbsoluteFile(),
				new File(System.getProperty("user.dir", ".")).getAbsoluteFile() }) {
			File dir = start;
			while (dir != null) {
				File candidate = new File(dir, BUNDLE_OPENCODE_REL);
				if (new File(candidate, "package.json").isFile()
						&& new File(candidate, "pnpm-workspace.yaml").isFile()) {
					return candidate;
				}
				dir = dir.getParentFile();
			}
		}
		return fail("Could not locate " + BUNDLE_OPENCODE_REL + " by walking up from the test working directory ("
				+ new File("").getAbsolutePath()
				+ "). The guard test needs the committed package.json and pnpm-workspace.yaml - see SVY-21679.");
	}
}

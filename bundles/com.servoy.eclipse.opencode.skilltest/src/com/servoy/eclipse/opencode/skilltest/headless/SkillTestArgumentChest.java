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

package com.servoy.eclipse.opencode.skilltest.headless;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line arguments for the headless skill-test runner.
 * <p>
 * This runner is a plain {@code IApplication} (no longer a workspace exporter),
 * so it parses its own arguments instead of extending the exporter argument
 * base. Each baseline brings its own {@code setup.servoy} initial state, which
 * the runner imports per baseline; there is therefore no single mandatory
 * {@code -s} solution to activate up front.
 * </p>
 * <ul>
 * <li>{@code -data <workspace>} — the Servoy workspace location (mandatory).</li>
 * <li>{@code -as <application_server>} — the Servoy application_server directory.</li>
 * <li>{@code -p <properties>} — settings/properties file to load.</li>
 * <li>{@code -baselines <dir>} — baselines root; default is the workspace
 * {@code servoy_ai_skilltests/baselines} resolved by {@code SkillTestPaths}.</li>
 * <li>{@code -o <dir>} / {@code -outputDir <dir>} — JUnit XML output directory.</li>
 * <li>{@code -cloudUser} / {@code -cloudPass} — Servoy Cloud service-account
 * credentials for the headless login (see {@link CloudLogin}). Optional when
 * {@code GENAI_API_KEY} is already set via {@code -D}.</li>
 * </ul>
 */
public class SkillTestArgumentChest {

	private static final String DEFAULT_APP_SERVER_DIR = "../../application_server"; //$NON-NLS-1$
	private static final String DEFAULT_OUTPUT_DIR = "./test-results"; //$NON-NLS-1$

	private String appServerDir = DEFAULT_APP_SERVER_DIR;
	private String settingsFile;
	private Path baselinesDir;
	private Path outputDir;
	private String cloudUser;
	private String cloudPass;
	private boolean mustShowHelp;

	public SkillTestArgumentChest(String[] args) {
		Map<String, String> map = toMap(args);
		if (map.containsKey("help") || map.containsKey("?")) { //$NON-NLS-1$ //$NON-NLS-2$
			mustShowHelp = true;
		}
		// NOTE: -data is an Equinox framework argument (it sets the workspace /
		// instance location) and is consumed by the launcher, so it never appears in
		// the application args. The workspace is therefore taken from the running
		// platform (ResourcesPlugin), not parsed here.
		if (map.containsKey("as")) { //$NON-NLS-1$
			appServerDir = map.get("as"); //$NON-NLS-1$
		}
		settingsFile = map.get("p"); //$NON-NLS-1$
		if (map.containsKey("baselines")) { //$NON-NLS-1$
			baselinesDir = Paths.get(map.get("baselines")); //$NON-NLS-1$
		}
		String outArg = map.containsKey("outputDir") ? map.get("outputDir") : map.get("o"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (outArg != null && !outArg.isBlank()) {
			outputDir = Paths.get(outArg);
		}
		cloudUser = map.get("cloudUser"); //$NON-NLS-1$
		cloudPass = map.get("cloudPass"); //$NON-NLS-1$
	}

	/**
	 * Builds the configuration from system properties, for the workbench
	 * {@code IStartup} path where there are no {@code -application} args. Keys:
	 * {@code servoy.skilltest.as}, {@code .p}, {@code .baselines},
	 * {@code .outputDir}, {@code .cloudUser}, {@code .cloudPass}. All optional;
	 * credentials also fall back to {@code -DGENAI_API_KEY} via {@link CloudLogin}.
	 *
	 * @return a chest populated from {@code -Dservoy.skilltest.*} properties
	 */
	public static SkillTestArgumentChest fromSystemProperties() {
		SkillTestArgumentChest c = new SkillTestArgumentChest(new String[0]);
		String as = System.getProperty("servoy.skilltest.as"); //$NON-NLS-1$
		if (as != null && !as.isBlank()) {
			c.appServerDir = as;
		}
		c.settingsFile = emptyToNull(System.getProperty("servoy.skilltest.p")); //$NON-NLS-1$
		String baselines = System.getProperty("servoy.skilltest.baselines"); //$NON-NLS-1$
		if (baselines != null && !baselines.isBlank()) {
			c.baselinesDir = Paths.get(baselines);
		}
		String out = System.getProperty("servoy.skilltest.outputDir"); //$NON-NLS-1$
		if (out != null && !out.isBlank()) {
			c.outputDir = Paths.get(out);
		}
		c.cloudUser = emptyToNull(System.getProperty("servoy.skilltest.cloudUser")); //$NON-NLS-1$
		c.cloudPass = emptyToNull(System.getProperty("servoy.skilltest.cloudPass")); //$NON-NLS-1$
		return c;
	}

	private static String emptyToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}

	/**
	 * Parses {@code -key value} / bare {@code -flag} arguments into a map, ignoring
	 * the platform args ({@code -os}, {@code -ws}, {@code -arch}, {@code -nl}) that
	 * Equinox prepends. A token starting with {@code -} begins a new key; the next
	 * non-{@code -} token is its value (else it is a bare flag).
	 */
	private static Map<String, String> toMap(String[] args) {
		Map<String, String> map = new HashMap<>();
		if (args == null) {
			return map;
		}
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (a == null || !a.startsWith("-")) { //$NON-NLS-1$
				continue;
			}
			String key = a.substring(1);
			if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("-")) { //$NON-NLS-1$
				map.put(key, args[i + 1]);
				i++;
			} else {
				map.put(key, ""); //$NON-NLS-1$
			}
		}
		return map;
	}

	public boolean mustShowHelp() {
		return mustShowHelp;
	}

	public boolean isInvalid() {
		// The workspace comes from the platform (-data is a framework arg), so there
		// is no mandatory application argument to validate; help is the only reason to
		// treat the invocation as "nothing to do".
		return false;
	}

	/** @return the application_server directory ({@code -as}), defaulted if absent */
	public String getAppServerDir() {
		return appServerDir;
	}

	/** @return the settings/properties file ({@code -p}), or {@code null} */
	public String getSettingsFileName() {
		return settingsFile;
	}

	/**
	 * @return the baselines root directory, or {@code null} to let the runner fall
	 *         back to the workspace default
	 */
	public File getBaselinesDir() {
		return baselinesDir != null ? baselinesDir.toFile() : null;
	}

	/** @return the JUnit XML output directory (falls back to a default) */
	public Path getOutputDir() {
		return outputDir != null ? outputDir : Paths.get(DEFAULT_OUTPUT_DIR);
	}

	/** @return the Servoy Cloud service-account username, or {@code null} */
	public String getCloudUser() {
		return cloudUser;
	}

	/** @return the Servoy Cloud service-account password, or {@code null} */
	public String getCloudPass() {
		return cloudPass;
	}

	public String getHelpMessage() {
		return "Servoy AI Skill Test Runner. Replays skill-test baselines headlessly for CI.\n" //$NON-NLS-1$
				+ "USAGE:\n" //$NON-NLS-1$
				+ "   -data <workspace_location>  (mandatory)\n" //$NON-NLS-1$
				+ "   [-as <application_server_dir>]  default: " + DEFAULT_APP_SERVER_DIR + "\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "   [-p <properties_file>]\n" //$NON-NLS-1$
				+ "   [-baselines <dir>]  default: <workspace>/servoy_ai_skilltests/baselines\n" //$NON-NLS-1$
				+ "   [-o <dir> | -outputDir <dir>]  JUnit XML output. Default: " + DEFAULT_OUTPUT_DIR + "\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "   [-cloudUser <user> -cloudPass <pass>]  Servoy Cloud service account for the\n" //$NON-NLS-1$
				+ "        headless login that provides GENAI_API_KEY + SERVOY_SKILLS_ZIP. Optional if\n" //$NON-NLS-1$
				+ "        GENAI_API_KEY is already set via -D.\n" //$NON-NLS-1$
				+ "\nEXIT codes: 0 - all passed (or none active), 1 - one or more diverged, 2 - infra error, 3 - invalid arguments"; //$NON-NLS-1$
	}
}
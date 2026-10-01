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

/**
 * Central resolver for where skill-test fixtures and results live on disk
 * (SVY-21366, §3.9).
 * <p>
 * Baselines and reports live <b>under the Servoy workspace root</b> (e.g.
 * {@code C:\Users\merae\servoy_workspace_2026_9}) rather than under
 * {@code ~/.servoy/}, so they travel with the workspace and can be
 * version-controlled. The layout mirrors EclEmma/JaCoCo's split of committed
 * fixtures vs. derived, regenerable output:
 * </p>
 *
 * <pre>
 * &lt;workspace&gt;/servoy_ai_skilltests/
 * ├── baselines/                 committed fixtures (source of truth)
 * │   └── &lt;id&gt;/{export.json, baseline.json}
 * └── reports/                   derived, git-ignored (like target/)
 *     ├── last-results.json      canonical machine-readable results
 *     └── run-&lt;timestamp&gt;.md      human-readable report, one per run
 * </pre>
 * <p>
 * The workspace root is resolved via
 * {@code ResourcesPlugin.getWorkspace().getRoot().getLocation()}. When no
 * Eclipse workspace is available (e.g. plain unit tests running outside an OSGi
 * runtime), it falls back to {@code ~/.servoy/opencode/skilltests} so the
 * helper never throws.
 * </p>
 */
public final class SkillTestPaths {

	/** Root folder name created under the workspace. */
	public static final String ROOT_DIR = "servoy_ai_skilltests"; //$NON-NLS-1$
	public static final String BASELINES_DIR = "baselines"; //$NON-NLS-1$
	public static final String REPORTS_DIR = "reports"; //$NON-NLS-1$
	public static final String LAST_RESULTS_FILE = "last-results.json"; //$NON-NLS-1$

	private SkillTestPaths() {
	}

	/**
	 * @return the workspace root ({@code .../servoy_workspace_...}) if an Eclipse
	 *         workspace is available, otherwise {@code ~/.servoy/opencode}
	 */
	public static File workspaceRoot() {
		File resourcesRoot = resolveEclipseWorkspaceRoot();
		if (resourcesRoot != null) {
			return resourcesRoot;
		}
		// Fallback for non-OSGi unit tests: keep a self-contained root under the
		// user home so nothing NPEs when ResourcesPlugin is unavailable.
		return new File(System.getProperty("user.home"), //$NON-NLS-1$
				".servoy" + File.separator + "opencode"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Resolves {@code ResourcesPlugin.getWorkspace().getRoot().getLocation()}
	 * reflectively-safe: this class is referenced from plain unit tests where the
	 * Eclipse resources runtime is not started, so a hard failure there must
	 * degrade to {@code null} rather than propagate.
	 *
	 * @return the workspace root directory, or {@code null} if unavailable
	 */
	private static File resolveEclipseWorkspaceRoot() {
		try {
			org.eclipse.core.runtime.IPath location = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
					.getRoot().getLocation();
			return location != null ? location.toFile() : null;
		} catch (IllegalStateException | LinkageError e) {
			// Workspace closed, or resources bundle not started (unit test JVM).
			return null;
		}
	}

	/** @return {@code <workspace>/servoy_ai_skilltests} */
	public static File skillTestsRoot() {
		return new File(workspaceRoot(), ROOT_DIR);
	}

	/**
	 * @return the baselines root ({@code <workspace>/servoy_ai_skilltests/baselines});
	 *         created if absent
	 */
	public static File baselinesRoot() {
		File dir = new File(skillTestsRoot(), BASELINES_DIR);
		dir.mkdirs();
		return dir;
	}

	/**
	 * @return the reports root ({@code <workspace>/servoy_ai_skilltests/reports});
	 *         created if absent. This directory is derived/regenerable and should
	 *         be git-ignored.
	 */
	public static File reportsRoot() {
		File dir = new File(skillTestsRoot(), REPORTS_DIR);
		boolean created = !dir.exists();
		dir.mkdirs();
		if (created) {
			// The reports dir is derived/regenerable output (JaCoCo-style). It lives in
			// the user's Servoy workspace, which may itself be a git repo, so drop a
			// self-contained .gitignore that ignores everything here rather than relying
			// on any particular outer repo's ignore file.
			File gitignore = new File(dir, ".gitignore"); //$NON-NLS-1$
			if (!gitignore.exists()) {
				try {
					java.nio.file.Files.writeString(gitignore.toPath(), "*\n", //$NON-NLS-1$
							java.nio.charset.StandardCharsets.UTF_8);
				} catch (java.io.IOException ignored) {
					// Non-fatal: worst case the reports are visible to git.
				}
			}
		}
		return dir;
	}

	/** @return the canonical machine-readable results file (may not yet exist) */
	public static File lastResultsFile() {
		return new File(reportsRoot(), LAST_RESULTS_FILE);
	}
}

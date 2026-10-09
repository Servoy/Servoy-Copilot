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
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.osgi.framework.Bundle;

import com.servoy.eclipse.opencode.Activator;

/**
 * Base class for the opt-in, real-LLM Servoy AI skill regression suite
 * (SVY-21366).
 * <p>
 * This suite drives the reusable {@link SkillTestRunner} engine (product code
 * in {@code com.servoy.eclipse.opencode}) over the committed baselines. Because
 * it runs a real LLM through the embedded "Servoy AI" (opencode) server, it is
 * slow, token-costing, and non-deterministic — so it is <b>disabled by
 * default</b> and must be explicitly enabled.
 * <p>
 * It is enabled only when <b>all</b> of the following are true:
 * <ul>
 * <li>the guard system property {@code -Dservoy.ai.skilltests=true} is
 * set;</li>
 * <li>{@code GENAI_API_KEY} is available (system property or environment);</li>
 * <li>{@code SERVOY_SKILLS_ZIP} is available (system property or
 * environment).</li>
 * </ul>
 * When not enabled, the {@code @TestFactory} in
 * {@link SkillScenarioIntegrationTest} emits <b>zero</b> dynamic tests — an
 * honest no-op that reports nothing and costs nothing, rather than a misleading
 * skip-to-green.
 * <p>
 * Because it needs an OSGi runtime, a live embedded server, and (typically) an
 * active Servoy solution, it is a JUnit <b>Plug-in</b> test: launch it with
 * {@code eclipse-pde_runJUnitPluginTestClass} (Run As → JUnit Plug-in Test)
 * inside a Servoy Developer workbench, not the plain JUnit launcher.
 */
public abstract class AbstractSkillScenarioTest {

	/** Guard property that must be {@code true} to enable the real-LLM suite. */
	protected static final String GUARD_PROPERTY = "servoy.ai.skilltests"; //$NON-NLS-1$

	private static final String GENAI_API_KEY = "GENAI_API_KEY"; //$NON-NLS-1$
	private static final String SERVOY_SKILLS_ZIP = "SERVOY_SKILLS_ZIP"; //$NON-NLS-1$

	/**
	 * {@code true} when the guard property and both required keys are present.
	 * Recorded in {@link #ensurePrereqs()}; the {@code @TestFactory} decides what
	 * to do with it. Never used to {@code Assume}-skip.
	 */
	protected static boolean enabled;

	/**
	 * Human-readable reason the suite is disabled (or {@code null} when enabled).
	 */
	protected static String disabledReason;

	@BeforeAll
	static void ensurePrereqs() {
		boolean guard = "true".equalsIgnoreCase(System.getProperty(GUARD_PROPERTY)); //$NON-NLS-1$
		boolean hasKey = isPresent(GENAI_API_KEY);
		boolean hasSkills = isPresent(SERVOY_SKILLS_ZIP);
		enabled = guard && hasKey && hasSkills;
		if (!enabled) {
			StringBuilder sb = new StringBuilder("Servoy AI skill suite disabled:"); //$NON-NLS-1$
			if (!guard)
				sb.append(" -D").append(GUARD_PROPERTY).append("=true not set;"); //$NON-NLS-1$ //$NON-NLS-2$
			if (!hasKey)
				sb.append(' ').append(GENAI_API_KEY).append(" absent;"); //$NON-NLS-1$
			if (!hasSkills)
				sb.append(' ').append(SERVOY_SKILLS_ZIP).append(" absent;"); //$NON-NLS-1$
			disabledReason = sb.toString();
		} else {
			disabledReason = null;
		}
	}

	private static boolean isPresent(String key) {
		String v = System.getProperty(key);
		if (v == null || v.isBlank()) {
			v = System.getenv(key);
		}
		return v != null && !v.isBlank();
	}

	/**
	 * Ensures the embedded opencode / Servoy AI server is started and blocks until
	 * it is ready, using the same handshake {@code OpenCodeView} uses. Never sleeps
	 * in a poll loop — {@link Activator#waitForServer(long)} blocks correctly.
	 *
	 * @param timeoutMs how long to wait for readiness
	 * @return the server port, or {@code -1} if the server never became ready
	 */
	protected static int ensureServerReadyOrMinusOne(long timeoutMs) {
		Activator activator = Activator.getInstance();
		if (activator == null) {
			return -1;
		}
		try {
			activator.ensureServerStarting();
			if (activator.waitForServer(timeoutMs)) {
				return activator.getServerPort();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return -1;
	}

	/**
	 * Resolves the folder(s) that hold baseline fixtures. Prefers the fragment's
	 * committed {@code baselines/} folder (located via the {@code opencode.tests}
	 * bundle) and falls back to (or additionally includes) the user directory
	 * {@code ~/.servoy/opencode/skilltests}. Returns the first existing directory,
	 * or {@code null} when neither exists.
	 *
	 * @return an existing baselines root directory, or {@code null}
	 */
	protected static File resolveBaselinesRoot() {
		File bundled = resolveBundledBaselines();
		if (bundled != null && bundled.isDirectory()) {
			return bundled;
		}
		File userRoot = resolveUserBaselines();
		if (userRoot != null && userRoot.isDirectory()) {
			return userRoot;
		}
		return null;
	}

	private static File resolveBundledBaselines() {
		try {
			Bundle bundle = Platform.getBundle("com.servoy.eclipse.opencode.tests"); //$NON-NLS-1$
			if (bundle == null) {
				return null;
			}
			URL entry = bundle.getEntry("baselines/"); //$NON-NLS-1$
			if (entry == null) {
				return null;
			}
			URL fileUrl = FileLocator.toFileURL(entry);
			return new File(fileUrl.getPath());
		} catch (Exception e) {
			return null;
		}
	}

	private static File resolveUserBaselines() {
		String home = System.getProperty("user.home"); //$NON-NLS-1$
		if (home == null || home.isBlank()) {
			return null;
		}
		return new File(home, ".servoy/opencode/skilltests"); //$NON-NLS-1$
	}
}

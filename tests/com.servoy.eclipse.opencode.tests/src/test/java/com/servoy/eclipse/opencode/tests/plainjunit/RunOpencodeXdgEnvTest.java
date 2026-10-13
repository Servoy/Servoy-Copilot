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

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.RunOpencodeCommand;

/**
 * Unit tests for the package-visible helper
 * {@link RunOpencodeCommand#buildServoyXdgEnv()}.
 * <p>
 * No OSGi runtime is required - the method is pure Java and reads only
 * {@code System} properties.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class RunOpencodeXdgEnvTest {
	private static final String ESCAPE_HATCH_PROPERTY = "opencode.use.default.xdg";

	/**
	 * Ensure the escape-hatch property is cleared after every test that sets it.
	 */
	@AfterEach
	public void clearEscapeHatch() {
		System.clearProperty(ESCAPE_HATCH_PROPERTY);
	}

	// --- default (no escape hatch) ---

	@Test
	public void buildServoyXdgEnv_returnsExactlyFourEntries() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertEquals(4, env.size(), "Expected exactly 4 XDG environment variables (config, data, state, cache)");
	}

	@Test
	public void buildServoyXdgEnv_containsXdgConfigHome() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertTrue(env.containsKey("XDG_CONFIG_HOME"), "Map must contain XDG_CONFIG_HOME");
	}

	@Test
	public void buildServoyXdgEnv_containsXdgDataHome() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertTrue(env.containsKey("XDG_DATA_HOME"), "Map must contain XDG_DATA_HOME");
	}

	@Test
	public void buildServoyXdgEnv_containsXdgStateHome() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertTrue(env.containsKey("XDG_STATE_HOME"), "Map must contain XDG_STATE_HOME");
	}

	@Test
	public void buildServoyXdgEnv_containsXdgCacheHome() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertTrue(env.containsKey("XDG_CACHE_HOME"), "Map must contain XDG_CACHE_HOME");
	}

	@Test
	public void buildServoyXdgEnv_cacheHomeMatchesExpectedPath() {
		String expected = System.getProperty("user.home") + File.separator + ".servoy";
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertEquals(expected, env.get("XDG_CACHE_HOME"), "XDG_CACHE_HOME must equal {user.home}/.servoy");
	}

	@Test
	public void buildServoyXdgEnv_allValuesAreEqual() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		String config = env.get("XDG_CONFIG_HOME");
		String data = env.get("XDG_DATA_HOME");
		String state = env.get("XDG_STATE_HOME");
		String cache = env.get("XDG_CACHE_HOME");
		assertEquals(config, data, "XDG_CONFIG_HOME and XDG_DATA_HOME must point to the same directory");
		assertEquals(config, state, "XDG_CONFIG_HOME and XDG_STATE_HOME must point to the same directory");
		assertEquals(config, cache, "XDG_CONFIG_HOME and XDG_CACHE_HOME must point to the same directory");
	}

	@Test
	public void buildServoyXdgEnv_pathEndsWithDotServoy() {
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		String configHome = env.get("XDG_CONFIG_HOME");
		assertNotNull(configHome);
		assertTrue(configHome.endsWith(".servoy"), "XDG_CONFIG_HOME must end with '.servoy'");
	}

	@Test
	public void buildServoyXdgEnv_configHomeMatchesExpectedPath() {
		String expected = System.getProperty("user.home") + File.separator + ".servoy";
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertEquals(expected, env.get("XDG_CONFIG_HOME"), "XDG_CONFIG_HOME must equal {user.home}/.servoy");
	}

	@Test
	public void buildServoyXdgEnv_dataHomeMatchesExpectedPath() {
		String expected = System.getProperty("user.home") + File.separator + ".servoy";
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertEquals(expected, env.get("XDG_DATA_HOME"), "XDG_DATA_HOME must equal {user.home}/.servoy");
	}

	@Test
	public void buildServoyXdgEnv_stateHomeMatchesExpectedPath() {
		String expected = System.getProperty("user.home") + File.separator + ".servoy";
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertEquals(expected, env.get("XDG_STATE_HOME"), "XDG_STATE_HOME must equal {user.home}/.servoy");
	}

	// --- escape hatch ---

	@Test
	public void buildServoyXdgEnv_escapehatchTrue_returnsEmptyMap() {
		System.setProperty(ESCAPE_HATCH_PROPERTY, "true");
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertTrue(env.isEmpty(), "Escape hatch must cause buildServoyXdgEnv() to return an empty map");
	}

	@Test
	public void buildServoyXdgEnv_escapehatchFalse_returnsNonEmptyMap() {
		System.setProperty(ESCAPE_HATCH_PROPERTY, "false");
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertFalse(env.isEmpty(), "Escape hatch=false must NOT suppress XDG overrides");
	}

	@Test
	public void buildServoyXdgEnv_escapehatchNotSet_returnsNonEmptyMap() {
		// Ensure property is absent (cleared by @After if a previous test set it)
		System.clearProperty(ESCAPE_HATCH_PROPERTY);
		Map<String, String> env = RunOpencodeCommand.buildServoyXdgEnv();
		assertFalse(env.isEmpty(), "Absent escape-hatch property must not suppress XDG overrides");
	}
}

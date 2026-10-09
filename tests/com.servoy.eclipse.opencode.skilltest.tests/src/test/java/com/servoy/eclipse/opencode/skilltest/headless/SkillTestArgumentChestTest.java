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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SkillTestArgumentChest} - the {@code -key value} / bare-flag
 * argument parsing and the system-property path the CI/workbench runner relies on
 * (SVY-21366). Pure parsing, no workbench.
 */
public class SkillTestArgumentChestTest {

	@Nested
	class ArgParsing {

		@Test
		@DisplayName("-key value pairs are parsed into the typed getters")
		void keyValuePairs() {
			SkillTestArgumentChest c = new SkillTestArgumentChest(new String[] {
					"-as", "/srv/appserver",
					"-baselines", "/data/baselines",
					"-outputDir", "/data/reports",
					"-cloudUser", "svc", "-cloudPass", "secret" });
			assertAll(
					() -> assertEquals("/srv/appserver", c.getAppServerDir()),
					() -> assertEquals("/data/baselines", c.getBaselinesDir().getPath().replace('\\', '/')),
					() -> assertEquals("/data/reports", c.getOutputDir().toString().replace('\\', '/')),
					() -> assertEquals("svc", c.getCloudUser()),
					() -> assertEquals("secret", c.getCloudPass()),
					() -> assertFalse(c.mustShowHelp()),
					// isInvalid is always false: the workspace comes from the platform, not an arg.
					() -> assertFalse(c.isInvalid()));
		}

		@Test
		@DisplayName("-o is an alias for -outputDir")
		void outputDirAlias() {
			SkillTestArgumentChest c = new SkillTestArgumentChest(new String[] { "-o", "/tmp/out" });
			assertEquals("/tmp/out", c.getOutputDir().toString().replace('\\', '/'));
		}

		@Test
		@DisplayName("-help / -? set mustShowHelp")
		void helpFlag() {
			assertTrue(new SkillTestArgumentChest(new String[] { "-help" }).mustShowHelp());
			assertTrue(new SkillTestArgumentChest(new String[] { "-?" }).mustShowHelp());
		}

		@Test
		@DisplayName("empty / null args: appserver + outputDir fall back to defaults; baselines/cloud stay null")
		void emptyArgs() {
			SkillTestArgumentChest c = new SkillTestArgumentChest(new String[0]);
			assertAll(
					// appServerDir and outputDir have built-in defaults; they are NOT null.
					() -> assertEquals("../../application_server", c.getAppServerDir()),
					() -> assertEquals("test-results", c.getOutputDir().getFileName().toString()),
					// baselines + cloud creds have no default.
					() -> assertNull(c.getBaselinesDir()),
					() -> assertNull(c.getCloudUser()),
					() -> assertFalse(c.mustShowHelp()));
			// null array must not throw.
			assertFalse(new SkillTestArgumentChest(null).mustShowHelp());
		}

		@Test
		@DisplayName("Equinox platform args (-os/-ws/-arch/-nl) and non-dash tokens do not become values")
		void ignoresPlatformNoise() {
			// -os linux is parsed as key 'os'=value 'linux' by the generic parser, but it must
			// not leak into any of the skilltest getters; a bare trailing flag gets "".
			SkillTestArgumentChest c = new SkillTestArgumentChest(new String[] {
					"-os", "linux", "-ws", "gtk", "-as", "/srv", "stray", "-help" });
			assertAll(
					() -> assertEquals("/srv", c.getAppServerDir()),
					() -> assertTrue(c.mustShowHelp()),
					// outputDir was not given, so it is the default (not an -os/-ws leak).
					() -> assertEquals("test-results", c.getOutputDir().getFileName().toString()));
		}
	}

	@Nested
	class SystemProperties {

		@Test
		@DisplayName("fromSystemProperties reads the -Dservoy.skilltest.* properties")
		void readsSystemProps() {
			String[] keys = { "servoy.skilltest.as", "servoy.skilltest.baselines",
					"servoy.skilltest.outputDir", "servoy.skilltest.cloudUser", "servoy.skilltest.cloudPass" };
			String[] saved = new String[keys.length];
			for (int i = 0; i < keys.length; i++) {
				saved[i] = System.getProperty(keys[i]);
			}
			try {
				System.setProperty("servoy.skilltest.as", "/srv/as");
				System.setProperty("servoy.skilltest.baselines", "/data/bl");
				System.setProperty("servoy.skilltest.outputDir", "/data/out");
				System.setProperty("servoy.skilltest.cloudUser", "u");
				System.setProperty("servoy.skilltest.cloudPass", "p");

				SkillTestArgumentChest c = SkillTestArgumentChest.fromSystemProperties();
				assertAll(
						() -> assertEquals("/srv/as", c.getAppServerDir()),
						() -> assertEquals("/data/bl", c.getBaselinesDir().getPath().replace('\\', '/')),
						() -> assertEquals("/data/out", c.getOutputDir().toString().replace('\\', '/')),
						() -> assertEquals("u", c.getCloudUser()),
						() -> assertEquals("p", c.getCloudPass()));
			} finally {
				for (int i = 0; i < keys.length; i++) {
					if (saved[i] == null) {
						System.clearProperty(keys[i]);
					} else {
						System.setProperty(keys[i], saved[i]);
					}
				}
			}
		}

		@Test
		@DisplayName("blank cloud user/pass system properties become null (not empty strings)")
		void blankPropsAreNull() {
			String savedUser = System.getProperty("servoy.skilltest.cloudUser");
			try {
				System.setProperty("servoy.skilltest.cloudUser", "   ");
				assertNull(SkillTestArgumentChest.fromSystemProperties().getCloudUser());
			} finally {
				if (savedUser == null) {
					System.clearProperty("servoy.skilltest.cloudUser");
				} else {
					System.setProperty("servoy.skilltest.cloudUser", savedUser);
				}
			}
		}
	}
}

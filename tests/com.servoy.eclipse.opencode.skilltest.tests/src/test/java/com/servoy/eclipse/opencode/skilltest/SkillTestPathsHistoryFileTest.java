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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for
 * {@link SkillTestPaths#resolveHistoryFile(String, String, File)} (SVY-21661
 * §3.2): system property beats env var beats the
 * {@code reports/run-history.jsonl} default; blank values count as unset.
 */
class SkillTestPathsHistoryFileTest {

	private static final File REPORTS = new File("reports-root");

	@Test
	@DisplayName("the system property beats the env var")
	void propertyBeatsEnv() {
		assertEquals(new File("/data/prop.jsonl"),
				SkillTestPaths.resolveHistoryFile(" /data/prop.jsonl ", "/data/env.jsonl", REPORTS));
	}

	@Test
	@DisplayName("the env var beats the default when the property is unset")
	void envBeatsDefault() {
		assertEquals(new File("/data/env.jsonl"), SkillTestPaths.resolveHistoryFile(null, "/data/env.jsonl", REPORTS));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("a blank property is treated as unset (falls through to the env var)")
	void blankPropertyUnset(String prop) {
		assertEquals(new File("/data/env.jsonl"), SkillTestPaths.resolveHistoryFile(prop, "/data/env.jsonl", REPORTS));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	@DisplayName("blank property and env var fall back to reports/run-history.jsonl")
	void defaultFile(String blank) {
		assertEquals(new File(REPORTS, SkillTestPaths.HISTORY_FILE),
				SkillTestPaths.resolveHistoryFile(blank, blank, REPORTS));
	}
}

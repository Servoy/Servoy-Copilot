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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.SkillTestResult.Status;

/**
 * Unit tests for {@link SkillTestResult} — the factory methods, status flags,
 * and field population for each outcome.
 */
class SkillTestResultTest {

	@Nested
	class Pass {

		@Test
		@DisplayName("pass is PASS, isPass, has no diff and no error")
		void pass() {
			SkillTestResult r = SkillTestResult.pass("cv", 2);
			assertAll(
					() -> assertEquals("cv", r.getBaselineId()),
					() -> assertEquals(Status.PASS, r.getStatus()),
					() -> assertTrue(r.isPass()),
					() -> assertEquals(2, r.getAttempts()),
					() -> assertTrue(r.getDiff().isEmpty()),
					() -> org.junit.jupiter.api.Assertions.assertNull(r.getErrorMessage()));
		}
	}

	@Nested
	class Fail {

		@Test
		@DisplayName("fail is FAIL, not isPass, and carries the diff")
		void fail() {
			SkillTestResult r = SkillTestResult.fail("cv", 3, "- missing tool: create_valuelist");
			assertAll(
					() -> assertEquals(Status.FAIL, r.getStatus()),
					() -> assertFalse(r.isPass()),
					() -> assertEquals(3, r.getAttempts()),
					() -> assertTrue(r.getDiff().contains("create_valuelist")));
		}
	}

	@Nested
	class Error {

		@Test
		@DisplayName("error is ERROR, not isPass, and carries the error message")
		void error() {
			SkillTestResult r = SkillTestResult.error("cv", 1, "server not reachable");
			assertAll(
					() -> assertEquals(Status.ERROR, r.getStatus()),
					() -> assertFalse(r.isPass()),
					() -> assertEquals("server not reachable", r.getErrorMessage()));
		}
	}

	@Nested
	class Skipped {

		@Test
		@DisplayName("skipped is SKIPPED with zero attempts and the reason in the error message")
		void skipped() {
			SkillTestResult r = SkillTestResult.skipped("cv", "guard property not set");
			assertAll(
					() -> assertEquals(Status.SKIPPED, r.getStatus()),
					() -> assertFalse(r.isPass()),
					() -> assertEquals(0, r.getAttempts()),
					() -> assertEquals("guard property not set", r.getErrorMessage()));
		}
	}
}

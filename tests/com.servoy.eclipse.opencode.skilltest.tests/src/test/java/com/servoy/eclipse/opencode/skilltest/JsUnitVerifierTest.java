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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsUnitVerifier#isPass(String)} — the pass/fail decision
 * read from the {@link com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService}
 * Markdown report. Focus on the SVY-21366 regression where a run that executed
 * zero tests (a timed-out cold start that never built the NG bundle) was wrongly
 * reported as a pass via "All 0 test(s) passed!".
 */
class JsUnitVerifierTest {

	private static String results(int passed, int failed, int errors, int ignored) {
		return "**JSUnit Test Results**\n\n"
				+ "| Passed | Failed | Errors | Ignored |\n"
				+ "|:------:|:------:|:------:|:-------:|\n"
				+ "| **" + passed + "** | **" + failed + "** | **" + errors + "** | **" + ignored + "** |\n";
	}

	@Nested
	class Passes {

		@Test
		@DisplayName("a run with at least one passed test and no failures/errors passes")
		void allGreen() {
			assertTrue(JsUnitVerifier.isPass(results(3, 0, 0, 0)
					+ "\nAll 3 test(s) passed!"));
		}

		@Test
		@DisplayName("passed with some ignored still passes")
		void passedWithIgnored() {
			assertTrue(JsUnitVerifier.isPass(results(2, 0, 0, 1)
					+ "\nAll 2 test(s) passed!"));
		}
	}

	@Nested
	class Fails {

		@Test
		@DisplayName("zero executed tests (0/0/0) is NOT a pass even with the 'All 0 passed' line (SVY-21366)")
		void zeroTestsIsNotPass() {
			assertFalse(JsUnitVerifier.isPass(results(0, 0, 0, 0)
					+ "\nAll 0 test(s) passed!"));
		}

		@Test
		@DisplayName("a timed-out run with partial results is NOT a pass")
		void timeoutIsNotPass() {
			String report = "Error - Timed out while running! Partial results follow:\n"
					+ results(0, 0, 0, 0) + "\nAll 0 test(s) passed!";
			assertFalse(JsUnitVerifier.isPass(report));
		}

		@Test
		@DisplayName("a timeout marker embedded in an otherwise-green report is NOT a pass")
		void timeoutMarkerIsNotPass() {
			String report = results(5, 0, 0, 0)
					+ "\nTimed out while running after some tests reported";
			assertFalse(JsUnitVerifier.isPass(report));
		}

		@Test
		@DisplayName("failures fail")
		void failuresFail() {
			assertFalse(JsUnitVerifier.isPass(results(2, 1, 0, 0)
					+ "\n**Failed / Error tests:**\n\nFAIL test_foo\n"));
		}

		@Test
		@DisplayName("errors fail")
		void errorsFail() {
			assertFalse(JsUnitVerifier.isPass(results(2, 0, 1, 0)
					+ "\n**Failed / Error tests:**\n\nERROR test_bar\n"));
		}

		@Test
		@DisplayName("a runner-level Error string fails")
		void runnerErrorFails() {
			assertFalse(JsUnitVerifier.isPass(
					"Error: Test run timed out after 180 seconds. Ensure the Servoy Application Server is running"));
		}

		@Test
		@DisplayName("null / blank / unparseable reports fail (conservative)")
		void unparseableFails() {
			assertFalse(JsUnitVerifier.isPass(null));
			assertFalse(JsUnitVerifier.isPass(""));
			assertFalse(JsUnitVerifier.isPass("something with no results header"));
		}
	}
}

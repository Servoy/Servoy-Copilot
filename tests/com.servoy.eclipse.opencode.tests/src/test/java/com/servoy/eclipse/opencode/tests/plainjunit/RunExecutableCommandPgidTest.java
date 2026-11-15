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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.RunExecutableCommand;

/**
 * Unit tests for {@code RunExecutableCommand.parsePgidFromStat(String)}
 * (SVY-21643, the build #80 group-kill safety guard).
 * <p>
 * The pgid (field 5, {@code pgrp}) of a {@code /proc/<pid>/stat} line is what
 * {@code RunExecutableCommand} uses to prove the {@code setsid}-launched opencode
 * child genuinely leads its own process group ({@code pgid == pid}) before it
 * will ever send a negative-pid group {@code SIGTERM}. If that parse is wrong,
 * the whole guard that protects the Jenkins controller's process group is wrong,
 * so the gnarly "{@code comm} may contain spaces and parentheses" case is pinned
 * here.
 * </p>
 * <p>
 * {@code parsePgidFromStat} is package-private in {@code com.servoy.eclipse.opencode}
 * and is invoked reflectively from this (different-package) plain JUnit test. It
 * is pure string parsing - no {@code /proc}, no process, no OSGi runtime needed.
 * </p>
 *
 * @since 2026.09
 */
class RunExecutableCommandPgidTest {

	private static Long parsePgidFromStat(String stat) {
		try {
			Method m = RunExecutableCommand.class.getDeclaredMethod("parsePgidFromStat", String.class);
			m.setAccessible(true);
			return (Long) m.invoke(null, stat);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Could not invoke RunExecutableCommand.parsePgidFromStat(String)", e);
		}
	}

	@Test
	@DisplayName("reads pgrp from a plain stat line")
	void parsesSimpleStatLine() {
		// pid=1234 comm=(opencode) state=S ppid=1 pgrp=1234 ...
		String stat = "1234 (opencode) S 1 1234 1234 0 -1 ...";
		assertEquals(Long.valueOf(1234L), parsePgidFromStat(stat), "pgrp is the field after state and ppid");
	}

	@Test
	@DisplayName("pgrp differs from pid when setsid exec'd in place (the unsafe case)")
	void parsesPgrpThatDiffersFromPid() {
		// pid=1234 but pgrp=42 (the JVM's / Jenkins' group) -> caller must NOT group-kill
		String stat = "1234 (opencode) S 1 42 42 0 -1 ...";
		assertEquals(Long.valueOf(42L), parsePgidFromStat(stat),
				"must read the real pgrp even when it does not equal the pid");
	}

	@Test
	@DisplayName("comm with spaces and parentheses does not shift the fields")
	void parsesCommContainingSpacesAndParens() {
		// comm = "opencode (serve)" - embedded spaces + an inner ')': the parse must
		// split on the LAST ')' or the field offsets shift and pgrp is misread.
		String stat = "999 (opencode (serve)) R 1 999 999 0 -1 ...";
		assertEquals(Long.valueOf(999L), parsePgidFromStat(stat),
				"splitting on the last ')' keeps state/ppid/pgrp aligned when comm has parens/spaces");
	}

	@Test
	@DisplayName("null / blank / truncated lines parse to null (fail-safe)")
	void nullForUnparseableLines() {
		assertNull(parsePgidFromStat(null), "null input -> null");
		assertNull(parsePgidFromStat(""), "empty input -> null");
		assertNull(parsePgidFromStat("1234 (opencode)"), "no fields after comm -> null");
		assertNull(parsePgidFromStat("1234 (opencode) S 1"), "truncated before pgrp -> null");
	}

	@Test
	@DisplayName("non-numeric pgrp field parses to null (fail-safe)")
	void nullForNonNumericPgrp() {
		String stat = "1234 (opencode) S 1 x 0 -1 ...";
		assertNull(parsePgidFromStat(stat), "a non-integer pgrp must fail safe to null, not throw");
	}
}

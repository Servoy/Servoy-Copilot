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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.RunExecutableCommand;

/**
 * Unit tests for {@code RunExecutableCommand.closeStdinLease()} (SVY-21643).
 * <p>
 * {@code closeStdinLease()} closes the write end of the child's stdin pipe,
 * giving a {@code opencode serve --stdio} server its stdin EOF (the Windows
 * graceful-shutdown signal). The spec requires it to be <b>null-safe</b> (a
 * no-op when the process never started) and <b>idempotent</b> (safe to call
 * repeatedly), and it must never throw out of IDE shutdown.
 * </p>
 * <p>
 * These tests construct a {@link RunExecutableCommand} <b>without launching any
 * process</b> and assert the close call does not throw - deliberately avoiding
 * a real process launch so the pure teardown logic is exercised in isolation,
 * with no OSGi runtime needed.
 * </p>
 *
 * @since 2026.09
 */
class RunExecutableCommandCloseStdinLeaseTest {

	private static RunExecutableCommand newCommandWithoutLaunching() {
		// A plausible-looking but never-started command; runCommand() is never called,
		// so no process exists and getProcess() stays null.
		File fakeExecutable = new File("opencode-does-not-run");
		return new RunExecutableCommand(fakeExecutable, List.of("serve", "--stdio"), null);
	}

	@Test
	void getProcess_isNullBeforeLaunch() {
		RunExecutableCommand cmd = newCommandWithoutLaunching();
		assertNull(cmd.getProcess(),
				"No process must exist before runCommand() is called - the null-safe precondition");
	}

	@Test
	void closeStdinLease_doesNotThrow_whenNoProcessStarted() {
		RunExecutableCommand cmd = newCommandWithoutLaunching();
		assertDoesNotThrow(cmd::closeStdinLease,
				"closeStdinLease() must be null-safe when no process was ever started");
	}

	@Test
	void closeStdinLease_isIdempotent_whenCalledTwice() {
		RunExecutableCommand cmd = newCommandWithoutLaunching();
		assertDoesNotThrow(() -> {
			cmd.closeStdinLease();
			cmd.closeStdinLease();
		}, "closeStdinLease() must be idempotent - a second call must not throw");
	}

	@Test
	void closeStdinLease_isIdempotent_whenCalledManyTimes() {
		RunExecutableCommand cmd = newCommandWithoutLaunching();
		assertDoesNotThrow(() -> {
			for (int i = 0; i < 5; i++) {
				cmd.closeStdinLease();
			}
		}, "Repeated closeStdinLease() calls must all be safe no-ops once the lease is closed");
	}

	@Test
	void closeStdinLease_leavesProcessNull_whenNoProcessStarted() {
		RunExecutableCommand cmd = newCommandWithoutLaunching();
		cmd.closeStdinLease();
		assertNull(cmd.getProcess(), "closeStdinLease() must not fabricate a process when none was started");
	}
}

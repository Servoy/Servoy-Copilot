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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.headless.BaselineWatchdog.Outcome;
import com.servoy.eclipse.opencode.skilltest.headless.BaselineWatchdog.State;

class BaselineWatchdogTest {

	@Test
	@DisplayName("a task that finishes in time returns its value")
	void completesWithValue() {
		Outcome<String> outcome = BaselineWatchdog.run("ok", cancelled -> "done", 5_000L, 1_000L); //$NON-NLS-1$ //$NON-NLS-2$

		assertEquals(State.COMPLETED, outcome.state());
		assertEquals("done", outcome.value()); //$NON-NLS-1$
		assertNull(outcome.failure());
	}

	@Test
	@DisplayName("a task that throws is COMPLETED with the failure, not a timeout")
	void capturesFailure() {
		Outcome<String> outcome = BaselineWatchdog.run("boom", cancelled -> { //$NON-NLS-1$
			throw new IllegalStateException("setup broke"); //$NON-NLS-1$
		}, 5_000L, 1_000L);

		assertEquals(State.COMPLETED, outcome.state());
		assertNull(outcome.value());
		assertInstanceOf(IllegalStateException.class, outcome.failure());
	}

	@Test
	@DisplayName("a task that overruns but honours cancel/interrupt is TIMED_OUT_STOPPED")
	void overrunStopped() {
		AtomicBoolean ended = new AtomicBoolean();
		Outcome<String> outcome = BaselineWatchdog.run("slow", cancelled -> { //$NON-NLS-1$
			try {
				// Ends either by seeing the cancel flag or by the interrupt (sleep throws).
				while (!cancelled.getAsBoolean()) {
					Thread.sleep(10L);
				}
			} finally {
				ended.set(true);
			}
			return null;
		}, 200L, 2_000L);

		assertEquals(State.TIMED_OUT_STOPPED, outcome.state());
		assertTrue(ended.get(), "the task must have been stopped"); //$NON-NLS-1$
	}

	@Test
	@DisplayName("a task that ignores cancel and interrupt is TIMED_OUT_HUNG")
	void overrunHung() {
		AtomicBoolean release = new AtomicBoolean();
		try {
			Outcome<String> outcome = BaselineWatchdog.run("hung", cancelled -> { //$NON-NLS-1$
				while (!release.get()) {
					Thread.onSpinWait(); // deliberately ignores cancel and interrupts
				}
				return null;
			}, 200L, 200L);

			assertEquals(State.TIMED_OUT_HUNG, outcome.state());
		} finally {
			release.set(true); // let the daemon worker end
		}
	}
}

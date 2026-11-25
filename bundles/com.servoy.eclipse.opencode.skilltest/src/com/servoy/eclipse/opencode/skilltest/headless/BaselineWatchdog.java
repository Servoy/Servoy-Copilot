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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runs one baseline on its own daemon thread with a hard wall-clock limit, so a
 * single wedged baseline (for example a JSUnit launch that never starts) cannot
 * consume the whole forked-process timeout and take the run history, the trend
 * report and the p2 deploy down with it.
 * <p>
 * On timeout the task is asked to stop (its {@code cancelled} supplier turns
 * {@code true} and the thread is interrupted) and given a short grace period.
 * The outcome tells the caller whether the task finished, was stopped, or is
 * still running ({@link State#TIMED_OUT_HUNG}); in the last case the caller must
 * not start more work against the same workbench.
 * </p>
 */
final class BaselineWatchdog {

	/** How the watched task ended. */
	enum State {
		/** The task returned (or threw) within the limit. */
		COMPLETED,
		/** The limit was exceeded, but the task stopped within the grace period. */
		TIMED_OUT_STOPPED,
		/** The limit was exceeded and the task is still running after the grace period. */
		TIMED_OUT_HUNG
	}

	/** The work to watch; {@code cancelled} turns {@code true} on timeout. */
	@FunctionalInterface
	interface Task<T> {
		T call(BooleanSupplier cancelled) throws Exception;
	}

	/**
	 * @param state   how the task ended
	 * @param value   the task's result when {@link State#COMPLETED} without failure
	 * @param failure what the task threw when {@link State#COMPLETED} with a failure
	 */
	record Outcome<T>(State state, T value, Throwable failure) {
	}

	private BaselineWatchdog() {
	}

	/**
	 * @param name      used in the worker thread name
	 * @param task      the work to run
	 * @param timeoutMs the hard limit for the task
	 * @param graceMs   how long to wait for the task to stop after the limit
	 * @return the outcome, never {@code null}
	 */
	static <T> Outcome<T> run(String name, Task<T> task, long timeoutMs, long graceMs) {
		AtomicBoolean cancelled = new AtomicBoolean();

		final class Holder {
			volatile T value;
			volatile Throwable failure;
		}
		Holder holder = new Holder();

		Thread worker = new Thread(() -> {
			try {
				holder.value = task.call(cancelled::get);
			} catch (Throwable t) { // report everything, including Errors, to the caller
				holder.failure = t;
			}
		}, "servoy-skilltest-baseline-" + name); //$NON-NLS-1$
		worker.setDaemon(true);
		worker.start();

		if (join(worker, timeoutMs)) {
			return new Outcome<>(State.COMPLETED, holder.value, holder.failure);
		}
		cancelled.set(true);
		worker.interrupt();
		if (join(worker, graceMs)) {
			return new Outcome<>(State.TIMED_OUT_STOPPED, null, null);
		}
		return new Outcome<>(State.TIMED_OUT_HUNG, null, null);
	}

	private static boolean join(Thread thread, long millis) {
		try {
			thread.join(Math.max(1L, millis));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return !thread.isAlive();
	}
}

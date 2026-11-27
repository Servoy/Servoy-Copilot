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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Bounded, cancel-aware wait for the workspace build job families.
 * <p>
 * {@link IJobManager#join(Object, IProgressMonitor)} has no timed overload and
 * also waits for jobs that are only queued or sleeping. A build job that is
 * scheduled but never starts therefore makes a plain join with a
 * {@link NullProgressMonitor} wait forever - seen in SVY-21659, where a
 * {@code customercard-form-issues} replay sat 36 minutes in
 * {@code SolutionSetup.settleAfterActivate} with no thread building and the
 * view's Stop having no effect.
 * </p>
 * <p>
 * {@code join} polls its monitor's {@link IProgressMonitor#isCanceled()} while it
 * waits, so the bound needs no helper thread: the monitor used here reports
 * cancelled once the deadline has passed or the run's cancel signal is set. On
 * a timeout the jobs still pending are logged by name and state, so the job that
 * never ran is identifiable from the log.
 * </p>
 */
public final class BuildSettle {

	/** How a {@link #join} ended. */
	public enum Result {
		/** All families finished within the time limit. */
		SETTLED,
		/** The time limit passed while jobs were still pending. */
		TIMED_OUT,
		/** The run was cancelled (or the thread interrupted) while waiting. */
		CANCELLED
	}

	/** System property overriding {@link #DEFAULT_TIMEOUT_MS}, in seconds. */
	public static final String TIMEOUT_PROPERTY = "servoy.skilltest.settleTimeoutSeconds"; //$NON-NLS-1$

	/** Default cap for one settle (both families together): 5 minutes. */
	public static final long DEFAULT_TIMEOUT_MS = 300_000L;

	private static final Object[] BUILD_FAMILIES = { ResourcesPlugin.FAMILY_AUTO_BUILD,
			ResourcesPlugin.FAMILY_MANUAL_BUILD };

	private BuildSettle() {
	}

	/**
	 * @return the configured settle cap in ms ({@value #TIMEOUT_PROPERTY}, else
	 *         {@link #DEFAULT_TIMEOUT_MS})
	 */
	public static long configuredTimeoutMs() {
		Long seconds = Long.getLong(TIMEOUT_PROPERTY);
		return seconds != null && seconds.longValue() > 0 ? seconds.longValue() * 1000L : DEFAULT_TIMEOUT_MS;
	}

	/**
	 * Joins the auto- and manual-build families of the platform job manager.
	 *
	 * @param timeoutMs total time allowed for both families
	 * @param cancelled the run's cancel signal (may be {@code null})
	 * @param log       sink for the timeout warning (may be {@code null})
	 * @return how the wait ended
	 */
	public static Result joinBuildFamilies(long timeoutMs, BooleanSupplier cancelled, Consumer<String> log) {
		return join(Job.getJobManager(), BUILD_FAMILIES, timeoutMs, cancelled, log, System::nanoTime);
	}

	/**
	 * Joins each family in turn under one shared deadline.
	 *
	 * @param jm        the job manager to join on
	 * @param families  the job families, joined in order
	 * @param timeoutMs total time allowed for all families
	 * @param cancelled the run's cancel signal (may be {@code null})
	 * @param log       sink for the timeout warning (may be {@code null})
	 * @param nanoClock time source, injectable for tests
	 * @return how the wait ended
	 */
	static Result join(IJobManager jm, Object[] families, long timeoutMs, BooleanSupplier cancelled,
			Consumer<String> log, LongSupplier nanoClock) {
		BooleanSupplier isCancelled = cancelled != null ? cancelled : () -> false;
		Consumer<String> sink = log != null ? log : msg -> {
		};
		long deadline = nanoClock.getAsLong() + timeoutMs * 1_000_000L;
		BooleanSupplier timedOut = () -> nanoClock.getAsLong() - deadline >= 0;
		IProgressMonitor monitor = cancelMonitor(() -> isCancelled.getAsBoolean() || timedOut.getAsBoolean());
		for (Object family : families) {
			try {
				jm.join(family, monitor);
			} catch (OperationCanceledException e) {
				if (isCancelled.getAsBoolean()) {
					return Result.CANCELLED;
				}
				sink.accept("[skilltest] WARN: build jobs did not settle within " + (timeoutMs / 1000) //$NON-NLS-1$
						+ "s; continuing. Still pending: " + describePending(jm, families)); //$NON-NLS-1$
				return Result.TIMED_OUT;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Result.CANCELLED;
			}
		}
		return Result.SETTLED;
	}

	/**
	 * @param cancelled supplies {@code true} when the operation should stop
	 * @return a monitor whose {@link IProgressMonitor#isCanceled()} follows
	 *         {@code cancelled} (or an explicit {@code setCanceled(true)})
	 */
	public static IProgressMonitor cancelMonitor(BooleanSupplier cancelled) {
		return new NullProgressMonitor() {
			@Override
			public boolean isCanceled() {
				return super.isCanceled() || cancelled.getAsBoolean();
			}
		};
	}

	/**
	 * @return the jobs of {@code families} still known to the job manager, as
	 *         {@code "name (STATE)"}, or {@code "none"}
	 */
	static String describePending(IJobManager jm, Object[] families) {
		List<String> pending = new ArrayList<>();
		for (Object family : families) {
			Job[] jobs = jm.find(family);
			if (jobs == null) {
				continue;
			}
			for (Job job : jobs) {
				pending.add(job.getName() + " (" + stateName(job.getState()) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return pending.isEmpty() ? "none" : String.join(", ", pending); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String stateName(int state) {
		return switch (state) {
			case Job.RUNNING -> "RUNNING"; //$NON-NLS-1$
			case Job.WAITING -> "WAITING"; //$NON-NLS-1$
			case Job.SLEEPING -> "SLEEPING"; //$NON-NLS-1$
			case Job.NONE -> "NONE"; //$NON-NLS-1$
			default -> "state " + state; //$NON-NLS-1$
		};
	}
}

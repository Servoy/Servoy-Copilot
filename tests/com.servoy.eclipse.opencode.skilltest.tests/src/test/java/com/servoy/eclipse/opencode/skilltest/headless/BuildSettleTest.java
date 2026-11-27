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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildSettleTest {

	private static final Object FAMILY_A = new Object();
	private static final Object FAMILY_B = new Object();
	private static final Object[] FAMILIES = { FAMILY_A, FAMILY_B };

	/**
	 * A job that is never scheduled, so {@link Job#getState()} is {@code NONE} and
	 * it never runs; only its name and state are read by the code under test.
	 */
	private static Job job(String name) {
		return new Job(name) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				return Status.OK_STATUS;
			}
		};
	}

	/**
	 * A fake job manager. {@code join} returns at once for a settled family; for
	 * the "stuck" family it behaves like the real one when a job is queued but
	 * never runs: it polls the monitor and throws
	 * {@link OperationCanceledException} once it reports cancelled. A dynamic
	 * proxy is used because {@code IJobManager} is a large interface and only
	 * {@code join}/{@code find} matter here (Mockito is not on this fragment's
	 * classpath).
	 */
	private static IJobManager fakeJobManager(Object stuckFamily, Job[] pending, List<Object> joined) {
		return (IJobManager) Proxy.newProxyInstance(BuildSettleTest.class.getClassLoader(),
				new Class<?>[] { IJobManager.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "join" -> { //$NON-NLS-1$
							Object family = args[0];
							IProgressMonitor monitor = (IProgressMonitor) args[1];
							joined.add(family);
							if (family == stuckFamily) {
								while (!monitor.isCanceled()) {
									Thread.sleep(5);
								}
								throw new OperationCanceledException();
							}
							return null;
						}
						case "find" -> { //$NON-NLS-1$
							return args[0] == stuckFamily ? pending : new Job[0];
						}
						default -> throw new UnsupportedOperationException(method.getName());
					}
				});
	}

	@Test
	@DisplayName("both families settle: SETTLED, joined in order, nothing logged")
	void settles() {
		List<Object> joined = new ArrayList<>();
		List<String> log = new ArrayList<>();

		BuildSettle.Result result = BuildSettle.join(fakeJobManager(null, new Job[0], joined), FAMILIES, 5_000L,
				() -> false, log::add, System::nanoTime);

		assertEquals(BuildSettle.Result.SETTLED, result);
		assertEquals(List.of(FAMILY_A, FAMILY_B), joined);
		assertTrue(log.isEmpty(), "no warning expected: " + log); //$NON-NLS-1$
	}

	@Test
	@DisplayName("a job that never runs: TIMED_OUT after the cap, the pending job is logged by name and state")
	void timesOutAndLogsPending() {
		List<Object> joined = new ArrayList<>();
		List<String> log = new ArrayList<>();
		IJobManager jm = fakeJobManager(FAMILY_A, new Job[] { job("Building workspace") }, joined); //$NON-NLS-1$

		long start = System.nanoTime();
		BuildSettle.Result result = BuildSettle.join(jm, FAMILIES, 200L, () -> false, log::add, System::nanoTime);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		assertEquals(BuildSettle.Result.TIMED_OUT, result);
		assertTrue(elapsedMs >= 190L && elapsedMs < 5_000L, "returned after " + elapsedMs + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(List.of(FAMILY_A), joined, "stops at the stuck family"); //$NON-NLS-1$
		assertEquals(1, log.size());
		assertTrue(log.get(0).contains("did not settle"), log.get(0)); //$NON-NLS-1$
		assertTrue(log.get(0).contains("Building workspace (NONE)"), log.get(0)); //$NON-NLS-1$
	}

	@Test
	@DisplayName("a cancel ends the wait at once: CANCELLED, no timeout warning")
	void cancelEndsWait() {
		List<Object> joined = new ArrayList<>();
		List<String> log = new ArrayList<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		IJobManager jm = fakeJobManager(FAMILY_A, new Job[] { job("Building workspace") }, joined); //$NON-NLS-1$
		Thread canceller = new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			cancelled.set(true);
		});
		canceller.start();

		long start = System.nanoTime();
		BuildSettle.Result result = BuildSettle.join(jm, FAMILIES, 60_000L, cancelled::get, log::add,
				System::nanoTime);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		assertEquals(BuildSettle.Result.CANCELLED, result);
		assertTrue(elapsedMs < 5_000L, "returned after " + elapsedMs + " ms, long before the 60 s cap"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(log.isEmpty(), "a cancel is not a timeout: " + log); //$NON-NLS-1$
	}

	@Test
	@DisplayName("cancelMonitor follows the supplier and an explicit setCanceled")
	void cancelMonitor() {
		AtomicBoolean flag = new AtomicBoolean();
		IProgressMonitor monitor = BuildSettle.cancelMonitor(flag::get);
		assertFalse(monitor.isCanceled());
		flag.set(true);
		assertTrue(monitor.isCanceled());

		IProgressMonitor explicit = BuildSettle.cancelMonitor(() -> false);
		explicit.setCanceled(true);
		assertTrue(explicit.isCanceled());
	}

	@Test
	@DisplayName("configuredTimeoutMs: default 5 min, overridable in seconds, non-positive ignored")
	void configuredTimeout() {
		String old = System.getProperty(BuildSettle.TIMEOUT_PROPERTY);
		try {
			System.clearProperty(BuildSettle.TIMEOUT_PROPERTY);
			assertEquals(BuildSettle.DEFAULT_TIMEOUT_MS, BuildSettle.configuredTimeoutMs());
			System.setProperty(BuildSettle.TIMEOUT_PROPERTY, "42"); //$NON-NLS-1$
			assertEquals(42_000L, BuildSettle.configuredTimeoutMs());
			System.setProperty(BuildSettle.TIMEOUT_PROPERTY, "0"); //$NON-NLS-1$
			assertEquals(BuildSettle.DEFAULT_TIMEOUT_MS, BuildSettle.configuredTimeoutMs());
		} finally {
			if (old == null) {
				System.clearProperty(BuildSettle.TIMEOUT_PROPERTY);
			} else {
				System.setProperty(BuildSettle.TIMEOUT_PROPERTY, old);
			}
		}
	}

	@Test
	@DisplayName("stateName maps the Job state constants")
	void stateNames() {
		assertEquals("RUNNING", BuildSettle.stateName(Job.RUNNING)); //$NON-NLS-1$
		assertEquals("WAITING", BuildSettle.stateName(Job.WAITING)); //$NON-NLS-1$
		assertEquals("SLEEPING", BuildSettle.stateName(Job.SLEEPING)); //$NON-NLS-1$
		assertEquals("NONE", BuildSettle.stateName(Job.NONE)); //$NON-NLS-1$
	}
}

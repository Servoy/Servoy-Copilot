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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.opencode.Activator;

/**
 * Unit tests for the server-ready coordination state accessors/mutators of
 * {@link Activator} that delegate to its inner {@code OpencodeServerState} and
 * for the plain job/command setters.
 * <p>
 * These exercise only the portions of {@link Activator} that do <em>not</em>
 * need an OSGi runtime: the latch/port/password/ready state and the two
 * reference setters. The activator's no-arg {@link org.eclipse.core.runtime.Plugin}
 * constructor is used directly ({@code new Activator()}), so no
 * {@code BundleContext} is involved. The package-private delegating methods
 * ({@code serverStarted}, {@code setServerPassword}, {@code setServerJob},
 * {@code setServerCommand}) live in the production package, so they are reached
 * by reflection rather than by widening their visibility.
 * </p>
 * <p>
 * The lifecycle methods that require OSGi or a real OS process
 * ({@code start}/{@code stop}/{@code stopServer} and the kill helpers) are
 * intentionally out of scope and covered elsewhere.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class ActivatorStateTest {
	/**
	 * Marks the activator's server state ready on the given port. We drive the
	 * extracted {@code OpencodeServerState} directly rather than calling
	 * {@code Activator.serverStarted(int)} because that method also writes to the
	 * plugin log, which needs a real OSGi logging bundle this plain-JUnit run does
	 * not have. The observable effect on the state accessors under test is the
	 * same.
	 */
	private static void serverStarted(Activator activator, int port) throws Exception {
		Object state = field(activator, "serverState");
		Method m = state.getClass().getDeclaredMethod("serverStarted", int.class);
		m.setAccessible(true);
		m.invoke(state, Integer.valueOf(port));
	}

	private static void setServerPassword(Activator activator, String password) throws Exception {
		Method m = Activator.class.getDeclaredMethod("setServerPassword", String.class);
		m.setAccessible(true);
		m.invoke(activator, password);
	}

	private static void setServerJob(Activator activator, org.eclipse.core.runtime.jobs.Job job) throws Exception {
		Method m = Activator.class.getDeclaredMethod("setServerJob", org.eclipse.core.runtime.jobs.Job.class);
		m.setAccessible(true);
		m.invoke(activator, job);
	}

	private static void setServerCommand(Activator activator, IRunNPMCommand cmd) throws Exception {
		Method m = Activator.class.getDeclaredMethod("setServerCommand", IRunNPMCommand.class);
		m.setAccessible(true);
		m.invoke(activator, cmd);
	}

	private static Object field(Activator activator, String name) throws Exception {
		java.lang.reflect.Field f = Activator.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(activator);
	}

	/** A no-op {@link IRunNPMCommand} dynamic-proxy stub (no real process). */
	private static IRunNPMCommand stubCommand() {
		InvocationHandler h = (proxy, m, args) -> {
			Class<?> rt = m.getReturnType();
			if (rt == boolean.class)
				return Boolean.FALSE;
			if (rt == int.class)
				return Integer.valueOf(0);
			return null;
		};
		return (IRunNPMCommand) Proxy.newProxyInstance(ActivatorStateTest.class.getClassLoader(),
				new Class<?>[] { IRunNPMCommand.class }, h);
	}

	@Nested
	class ReadyState {
		@Test
		@DisplayName("a fresh activator is not ready and reports the default port")
		void initiallyNotReady() {
			Activator activator = new Activator();
			assertFalse(activator.isServerReady(), "a fresh activator should not be ready");
			assertTrue(activator.getServerPort() > 0,
					"default port should be positive (RunOpencodeCommand.DEFAULT_PORT)");
		}

		@Test
		@DisplayName("serverStarted() flips isServerReady() to true and updates the port")
		void serverStartedSignalsReady() throws Exception {
			Activator activator = new Activator();
			serverStarted(activator, 4199);
			assertTrue(activator.isServerReady(), "activator should be ready after serverStarted()");
			assertEquals(4199, activator.getServerPort(), "port should be the one passed to serverStarted()");
		}

		@Test
		@DisplayName("serverStarted() is idempotent for the latch and keeps the last port")
		void serverStartedIsIdempotent() throws Exception {
			Activator activator = new Activator();
			serverStarted(activator, 4199);
			serverStarted(activator, 4200); // second call: latch already down, port updated
			assertTrue(activator.isServerReady());
			assertEquals(4200, activator.getServerPort());
		}
	}

	@Nested
	class WaitForServer {
		@Test
		@DisplayName("waitForServer() returns immediately when already ready")
		void returnsTrueWhenReady() throws Exception {
			Activator activator = new Activator();
			serverStarted(activator, 4096);
			assertTrue(activator.waitForServer(100), "should return true at once when already ready");
		}

		@Test
		@DisplayName("waitForServer() times out and returns false when the server never starts")
		void timesOutWhenNotStarted() throws Exception {
			Activator activator = new Activator();
			assertFalse(activator.waitForServer(60), "should return false when the server never becomes ready");
		}

		@Test
		@DisplayName("waitForServer() unblocks when a concurrent serverStarted() fires")
		void unblocksOnConcurrentStart() throws Exception {
			Activator activator = new Activator();
			java.util.concurrent.atomic.AtomicReference<Throwable> starterError = new java.util.concurrent.atomic.AtomicReference<>();
			Thread starter = new Thread(() -> {
				try {
					Thread.sleep(80);
					serverStarted(activator, 4096);
				} catch (Throwable t) {
					starterError.set(t);
				}
			});
			starter.setDaemon(true);
			starter.start();
			assertTrue(activator.waitForServer(2000), "should return true once a concurrent start fires");
			assertNull(starterError.get(), "the concurrent starter thread must not fail");
		}
	}

	@Nested
	class Password {
		@Test
		@DisplayName("password is null until set")
		void passwordInitiallyNull() {
			Activator activator = new Activator();
			assertNull(activator.getServerPassword(), "password should be null before it is set");
		}

		@Test
		@DisplayName("setServerPassword() round-trips through getServerPassword()")
		void passwordRoundTrips() throws Exception {
			Activator activator = new Activator();
			setServerPassword(activator, "s3cr3t");
			assertEquals("s3cr3t", activator.getServerPassword());
		}

		@Test
		@DisplayName("the last setServerPassword() wins")
		void lastPasswordWins() throws Exception {
			Activator activator = new Activator();
			setServerPassword(activator, "first");
			setServerPassword(activator, "second");
			assertEquals("second", activator.getServerPassword());
		}
	}

	@Nested
	class JobAndCommandSetters {
		@Test
		@DisplayName("setServerJob(null) stores the (null) reference without touching OSGi")
		void setServerJobNull() throws Exception {
			Activator activator = new Activator();
			setServerJob(activator, null);
			assertNull(field(activator, "serverJob"), "serverJob should be the stored null reference");
		}

		@Test
		@DisplayName("setServerCommand() stores the command and leaves state unchanged outside OSGi")
		void setServerCommandStoresReference() throws Exception {
			Activator activator = new Activator();
			IRunNPMCommand cmd = stubCommand();
			setServerCommand(activator, cmd);
			assertSame(cmd, field(activator, "serverCommand"), "serverCommand should hold the passed reference");
			// getOpencodeDir() cannot resolve getStateLocation() without an OSGi
			// runtime; the failure is swallowed, leaving the cached dir null.
			assertNull(field(activator, "cachedOpencodeDir"),
					"opencode dir cannot be resolved without OSGi, so it stays null");
		}

		@Test
		@DisplayName("setServerCommand() does not flip the server-ready state")
		void setServerCommandDoesNotAffectReadiness() throws Exception {
			Activator activator = new Activator();
			setServerCommand(activator, stubCommand());
			assertFalse(activator.isServerReady(),
					"registering the command must not mark the server ready");
		}
	}
}

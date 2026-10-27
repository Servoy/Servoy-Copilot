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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.RunOpencodeCommand;

/**
 * Unit tests for the package-visible per-platform serve-args helper
 * {@code RunOpencodeCommand.computeServeArgs(int, boolean)} (SVY-21643).
 * <p>
 * The helper decides the {@code opencode serve} argument list per platform:
 * Windows adds {@code --stdio} (so closing the child's stdin pipe becomes a
 * graceful-shutdown signal), while POSIX omits it (it ends via a group
 * {@code SIGTERM}). Both platforms must keep {@code --port <port>} and
 * {@code --hostname 127.0.0.1} so the HTTP port stays bound for the readiness
 * watchdog and the BFF servlet.
 * </p>
 * <p>
 * {@code computeServeArgs} is package-private in
 * {@code com.servoy.eclipse.opencode} and is therefore invoked reflectively
 * from this (different-package) plain JUnit test. No OSGi runtime is required -
 * the method is pure argument computation.
 * </p>
 *
 * @since 2026.09
 */
class ComputeServeArgsTest {
	private static final int PORT = 4242;

	@SuppressWarnings("unchecked")
	private static List<String> computeServeArgs(int port, boolean isWindows) {
		try {
			Method m = RunOpencodeCommand.class.getDeclaredMethod("computeServeArgs", int.class, boolean.class);
			m.setAccessible(true);
			return (List<String>) m.invoke(null, Integer.valueOf(port), Boolean.valueOf(isWindows));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Could not invoke RunOpencodeCommand.computeServeArgs(int, boolean)", e);
		}
	}

	// --- Windows branch (adds --stdio) ---

	@Test
	void windows_argsContainStdioFlag() {
		List<String> args = computeServeArgs(PORT, true);
		assertTrue(args.contains("--stdio"),
				"Windows serve args must contain --stdio so stdin-close is a shutdown signal");
	}

	@Test
	void windows_argsStartWithServeThenStdio() {
		List<String> args = computeServeArgs(PORT, true);
		assertEquals("serve", args.get(0), "First arg must be 'serve'");
		assertEquals("--stdio", args.get(1), "On Windows --stdio must immediately follow 'serve'");
	}

	@Test
	void windows_exactArgumentOrder() {
		List<String> args = computeServeArgs(PORT, true);
		assertEquals(List.of("serve", "--stdio", "--port", String.valueOf(PORT), "--hostname", "127.0.0.1"), args,
				"Windows serve args must be exactly: serve --stdio --port <port> --hostname 127.0.0.1");
	}

	// --- POSIX branch (no --stdio) ---

	@Test
	void posix_argsDoNotContainStdioFlag() {
		List<String> args = computeServeArgs(PORT, false);
		assertFalse(args.contains("--stdio"), "POSIX serve args must NOT contain --stdio (it ends via group SIGTERM)");
	}

	@Test
	void posix_argsStartWithServeThenPort() {
		List<String> args = computeServeArgs(PORT, false);
		assertEquals("serve", args.get(0), "First arg must be 'serve'");
		assertEquals("--port", args.get(1), "On POSIX --port must immediately follow 'serve' (no --stdio in between)");
	}

	@Test
	void posix_exactArgumentOrder() {
		List<String> args = computeServeArgs(PORT, false);
		assertEquals(List.of("serve", "--port", String.valueOf(PORT), "--hostname", "127.0.0.1"), args,
				"POSIX serve args must be exactly: serve --port <port> --hostname 127.0.0.1");
	}

	// --- port / hostname preserved on both platforms ---

	@Test
	void bothPlatforms_preservePortValue() {
		int port = 51234;
		List<String> win = computeServeArgs(port, true);
		List<String> posix = computeServeArgs(port, false);
		int winIdx = win.indexOf("--port");
		int posixIdx = posix.indexOf("--port");
		assertTrue(winIdx >= 0 && winIdx + 1 < win.size(), "Windows args must carry a --port <value> pair");
		assertTrue(posixIdx >= 0 && posixIdx + 1 < posix.size(), "POSIX args must carry a --port <value> pair");
		assertEquals(String.valueOf(port), win.get(winIdx + 1), "Windows --port value must equal the requested port");
		assertEquals(String.valueOf(port), posix.get(posixIdx + 1), "POSIX --port value must equal the requested port");
	}

	@Test
	void bothPlatforms_preserveHostnameLoopback() {
		List<String> win = computeServeArgs(PORT, true);
		List<String> posix = computeServeArgs(PORT, false);
		int winIdx = win.indexOf("--hostname");
		int posixIdx = posix.indexOf("--hostname");
		assertTrue(winIdx >= 0 && winIdx + 1 < win.size(), "Windows args must carry a --hostname <value> pair");
		assertTrue(posixIdx >= 0 && posixIdx + 1 < posix.size(), "POSIX args must carry a --hostname <value> pair");
		assertEquals("127.0.0.1", win.get(winIdx + 1), "Windows --hostname value must be the loopback 127.0.0.1");
		assertEquals("127.0.0.1", posix.get(posixIdx + 1), "POSIX --hostname value must be the loopback 127.0.0.1");
	}

	@Test
	void bothPlatforms_startWithServeSubcommand() {
		assertEquals("serve", computeServeArgs(PORT, true).get(0),
				"Windows args must start with the 'serve' subcommand");
		assertEquals("serve", computeServeArgs(PORT, false).get(0),
				"POSIX args must start with the 'serve' subcommand");
	}

	@Test
	void onlyDifferenceBetweenPlatformsIsStdio() {
		List<String> win = computeServeArgs(PORT, true);
		List<String> posix = computeServeArgs(PORT, false);
		assertEquals(posix.size() + 1, win.size(),
				"Windows args must be exactly one element longer than POSIX (the --stdio flag)");
	}
}

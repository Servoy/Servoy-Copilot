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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.RunOpencodeCommand;

/**
 * Unit tests for the pure, package-private static helpers of
 * {@link RunOpencodeCommand}: {@code resolveOpencodeExecutable(File)} and
 * {@code findFreePort(int)}.
 * <p>
 * No OSGi runtime, no process launch and no watchdog thread are involved - only
 * file-system layout (via {@link TempDir}) and a real local
 * {@link ServerSocket} bind for the free-port scan. The two methods are
 * package-private in {@code com.servoy.eclipse.opencode}; this test lives in a
 * different package, so they are reached reflectively and production visibility
 * is left unchanged.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
class RunOpencodeCommandTest {

	@TempDir
	Path tmp;

	private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final String EXE_NAME = IS_WINDOWS ? "opencode.exe" : "opencode"; //$NON-NLS-1$ //$NON-NLS-2$

	private static File resolveOpencodeExecutable(File opencodeDir) throws Exception {
		Method m = RunOpencodeCommand.class.getDeclaredMethod("resolveOpencodeExecutable", File.class); //$NON-NLS-1$
		m.setAccessible(true);
		return (File) m.invoke(null, opencodeDir);
	}

	private static int findFreePort(int startPort) throws Exception {
		Method m = RunOpencodeCommand.class.getDeclaredMethod("findFreePort", int.class); //$NON-NLS-1$
		m.setAccessible(true);
		return (int) m.invoke(null, startPort);
	}

	@Nested
	class ResolveOpencodeExecutable {

		@Test
		@DisplayName("null install dir ⇒ null")
		void nullDir_returnsNull() throws Exception {
			assertNull(resolveOpencodeExecutable(null));
		}

		@Test
		@DisplayName("Nothing on disk ⇒ null")
		void nothingPresent_returnsNull() throws Exception {
			File dir = Files.createDirectory(tmp.resolve("install")).toFile(); //$NON-NLS-1$
			assertNull(resolveOpencodeExecutable(dir), "no candidate exists ⇒ null");
		}

		@Test
		@DisplayName("Preferred @opencode/cli/bin executable is returned")
		void cliBinPresent_returned() throws Exception {
			File dir = Files.createDirectory(tmp.resolve("install")).toFile(); //$NON-NLS-1$
			File binDir = new File(dir, "node_modules/@opencode/cli/bin"); //$NON-NLS-1$
			assertTrue(binDir.mkdirs());
			File exe = new File(binDir, EXE_NAME);
			assertTrue(exe.createNewFile());

			File resolved = resolveOpencodeExecutable(dir);
			assertNotNull(resolved);
			assertEquals(exe.getCanonicalFile(), resolved.getCanonicalFile(),
					"the @opencode/cli/bin executable must be chosen");
		}

		@Test
		@DisplayName("Falls back to the node_modules/.bin shim when the cli bin is absent")
		void shimFallback_returned() throws Exception {
			File dir = Files.createDirectory(tmp.resolve("install")).toFile(); //$NON-NLS-1$
			File shimDir = new File(dir, "node_modules/.bin"); //$NON-NLS-1$
			assertTrue(shimDir.mkdirs());
			File shim = new File(shimDir, EXE_NAME);
			assertTrue(shim.createNewFile());

			File resolved = resolveOpencodeExecutable(dir);
			assertNotNull(resolved);
			assertEquals(shim.getCanonicalFile(), resolved.getCanonicalFile(),
					"the .bin shim is the fallback candidate");
		}

		@Test
		@DisplayName("Prefers the cli bin over the shim when both exist")
		void bothPresent_prefersCliBin() throws Exception {
			File dir = Files.createDirectory(tmp.resolve("install")).toFile(); //$NON-NLS-1$
			File binDir = new File(dir, "node_modules/@opencode/cli/bin"); //$NON-NLS-1$
			File shimDir = new File(dir, "node_modules/.bin"); //$NON-NLS-1$
			assertTrue(binDir.mkdirs());
			assertTrue(shimDir.mkdirs());
			File exe = new File(binDir, EXE_NAME);
			File shim = new File(shimDir, EXE_NAME);
			assertTrue(exe.createNewFile());
			assertTrue(shim.createNewFile());

			File resolved = resolveOpencodeExecutable(dir);
			assertEquals(exe.getCanonicalFile(), resolved.getCanonicalFile(),
					"the @opencode/cli/bin candidate must win over the shim");
		}

		@Test
		@DisplayName("A directory (not a file) at the candidate path is not accepted")
		void candidateIsDirectory_returnsNull() throws Exception {
			File dir = Files.createDirectory(tmp.resolve("install")).toFile(); //$NON-NLS-1$
			// Make the candidate path a directory, not a regular file.
			File asDir = new File(dir, "node_modules/@opencode/cli/bin/" + EXE_NAME); //$NON-NLS-1$
			assertTrue(asDir.mkdirs());

			assertNull(resolveOpencodeExecutable(dir), "a directory at the candidate path must be rejected");
		}
	}

	@Nested
	class FindFreePort {

		@Test
		@DisplayName("A free preferred port is returned as-is")
		void freePort_returnedUnchanged() throws Exception {
			int probe;
			try (ServerSocket ss = new ServerSocket(0)) {
				probe = ss.getLocalPort();
			}
			// probe is very likely free again immediately after close.
			int result = findFreePort(probe);
			assertTrue(result >= probe && result < probe + 100,
					"must return a port within the scan window starting at the preferred port");
		}

		@Test
		@DisplayName("Occupied preferred port ⇒ a higher free port in the window is chosen")
		void occupiedPort_scansUpward() throws Exception {
			// Hold a port open so the scan must step past it.
			try (ServerSocket held = new ServerSocket(0)) {
				int occupied = held.getLocalPort();
				int result = findFreePort(occupied);
				assertTrue(result > occupied && result < occupied + 100,
						"the scan must skip the occupied port and pick a higher free one");

				// The chosen port must actually be bindable.
				try (ServerSocket check = new ServerSocket(result)) {
					assertEquals(result, check.getLocalPort());
				}
			}
		}

		@Test
		@DisplayName("Returned port is bindable")
		void returnedPort_isBindable() throws Exception {
			int result = findFreePort(45000);
			try (ServerSocket ss = new ServerSocket(result)) {
				assertEquals(result, ss.getLocalPort(), "the returned port must be free to bind");
			}
		}
	}
}

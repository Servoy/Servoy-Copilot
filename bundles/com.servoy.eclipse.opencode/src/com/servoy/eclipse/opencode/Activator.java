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

package com.servoy.eclipse.opencode;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsoleManager;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ngclient.ui.EclipseIOConsole;
import com.servoy.eclipse.ngclient.ui.IConsole;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.RunNPMCommand;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

/**
 * Plugin activator for {@code com.servoy.eclipse.opencode}.
 * <p>
 * On startup, schedules {@link OpencodeFolderCreatorJob} (unless the
 * {@code opencode.url} system property is set, which means an external server
 * is used). Holds a reference to the running {@link RunOpencodeCommand} job and
 * the inner {@link RunNPMCommand} so both can be cancelled cleanly on shutdown.
 * </p>
 * <p>
 * Server-ready coordination state is delegated to {@link OpencodeServerState}
 * so that the latch/port logic can be unit-tested without an OSGi runtime.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class Activator extends Plugin {
	public static final String PLUGIN_ID = "com.servoy.eclipse.opencode";

	private static Activator instance;

	/**
	 * The outer Eclipse Job that owns the server lifecycle (used to cancel its
	 * monitor on shutdown).
	 */
	private volatile Job serverJob;

	/**
	 * The inner RunNPMCommand that wraps the OS process (used to kill the process
	 * tree on shutdown).
	 */
	private volatile IRunNPMCommand serverCommand;

	/** Holds the CountDownLatch and port - extracted for testability. */
	private final OpencodeServerState serverState = new OpencodeServerState(RunOpencodeCommand.DEFAULT_PORT);

	private IConsole aiConsole;

	public synchronized IConsole getConsole() {
		if (aiConsole == null) {
			try {
				URL imageUrl = getBundle().getEntry("/icons/aichat.png");
				EclipseIOConsole eclipseConsole = new EclipseIOConsole("Servoy AI Console", "servoyAiConsole",
						imageUrl != null ? ImageDescriptor.createFromURL(imageUrl) : null);
				IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
				consoleManager.addConsoles(new org.eclipse.ui.console.IOConsole[] { eclipseConsole });
				aiConsole = eclipseConsole;
			} catch (NullPointerException e) {
				return null;
			}
		}
		return aiConsole;
	}

	public void logToConsole(String message) {
		IConsole c = getConsole();
		if (c != null) {
			try {
				StringOutputStream out = c.outputStream();
				out.write("[Servoy AI] " + message + "\n");
				out.close();
			} catch (IOException ignored) {
			}
		}
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;

		// Setup is deferred until the user has both logged in and has an active
		// solution.
		// OpenCodeView.initUrl() calls ensureServerStarting() when all conditions are
		// met.
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		stopServer();
		instance = null;
		super.stop(context);
	}

	public static Activator getInstance() {
		return instance;
	}

	// --- server lifecycle ---

	private volatile boolean setupStarted = false;

	/**
	 * Schedules {@link OpencodeFolderCreatorJob} the first time it is called
	 * (idempotent). Must be called only after login is complete and an active
	 * solution is present.
	 */
	public void ensureServerStarting() {
		String urlOverride = System.getProperty(OpencodePerspective.URL_PROPERTY);
		if (urlOverride != null)
			return; // external server, nothing to do
		if (setupStarted)
			return;
		setupStarted = true;
		log(IStatus.INFO, "OpenCode: prerequisites met Ã¢ scheduling setup job."); //$NON-NLS-1$
		new OpencodeFolderCreatorJob().schedule();
	}

	/**
	 * Called by {@link RunOpencodeCommand} once the server is ready to accept
	 * connections.
	 */
	void serverStarted(int port) {
		log(IStatus.INFO, "OpenCode server ready on port " + port + ".");
		serverState.serverStarted(port);
	}

	/**
	 * Blocks until the opencode server is ready or the timeout elapses.
	 *
	 * @return {@code true} if the server started within the timeout
	 */
	public boolean waitForServer(long timeoutMs) throws InterruptedException {
		return serverState.waitForServer(timeoutMs);
	}

	public int getServerPort() {
		return serverState.getServerPort();
	}

	public boolean isServerReady() {
		return serverState.isReady();
	}

	/**
	 * Registers the outer {@link RunOpencodeCommand} job. Called by
	 * {@link RunOpencodeCommand#run} before the server process is launched so that
	 * {@link #stopServer()} can cancel the job's own monitor, which in turn makes
	 * {@code monitor.isCanceled()} return {@code true} in the retry-guard check.
	 */
	void setServerJob(Job job) {
		this.serverJob = job;
	}

	void setServerCommand(IRunNPMCommand cmd) {
		this.serverCommand = cmd;
	}

	/**
	 * Stops the running opencode server process (called from {@link #stop}).
	 * <p>
	 * The outer {@link RunOpencodeCommand} job is cancelled <em>first</em> so its
	 * {@code monitor.isCanceled()} returns {@code true}. This ensures the retry
	 * guard in {@code run()} sees the cancellation and does not schedule another
	 * attempt after the process is killed.
	 * </p>
	 * <p>
	 * Then {@link RunNPMCommand#cancel()} is called to set that job's cancel flag,
	 * followed by {@link #killProcessTree} to kill the OS process directly. We
	 * bypass {@code canceling()} in {@code RunNPMCommand} because during Eclipse
	 * shutdown the ngclient.ui activator is already null, causing
	 * {@code canceling()} to NPE before ever touching the process, which leaves the
	 * {@code readLine()} loop blocked.
	 * </p>
	 */
	public void stopServer() {
		ServoyLog.logInfo("OpenCode: stopServer() called");
		// Cancel the outer job first so monitor.isCanceled() == true in
		// RunOpencodeCommand.run()
		Job job = serverJob;
		if (job != null) {
			ServoyLog.logInfo("OpenCode: cancelling server job");
			job.cancel();
			serverJob = null;
		} else {
			ServoyLog.logInfo("OpenCode: serverJob was null");
		}

		IRunNPMCommand cmd = serverCommand;
		if (cmd != null) {
			ServoyLog.logInfo("OpenCode: cancelling server command and killing process tree");
			cmd.cancel();
			killProcessTree(cmd);
			serverCommand = null;
		} else {
			ServoyLog.logInfo("OpenCode: serverCommand was null");
		}
	}

	/**
	 * Kills the OS process (and its entire descendant tree) wrapped inside
	 * {@code cmd}.
	 * <p>
	 * On Windows, uses {@code taskkill /F /T} to kill detached children that
	 * Java's {@code process.descendants()} cannot see (e.g. bun spawned by
	 * opencode). On other platforms, walks the Java process tree.
	 * </p>
	 * <p>
	 * After killing, closes the process streams to unblock the
	 * {@code readLine()} loop in {@code RunNPMCommand.runCommand()}.
	 * </p>
	 */
	private void killProcessTree(IRunNPMCommand cmd) {
		Process process = cmd.getProcess();
		if (process == null) {
			ServoyLog.logInfo("OpenCode: killProcessTree - process is null");
			return;
		}

		long pid = process.pid();
		ServoyLog.logInfo("OpenCode: killing process tree, root PID: " + pid + ", alive: " + process.isAlive());

		boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
		if (isWindows) {
			try {
				Process taskkill = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
						.redirectErrorStream(true).start();
				taskkill.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
				ServoyLog.logInfo("OpenCode: taskkill /F /T /PID " + pid + " completed");
			} catch (Exception e) {
				ServoyLog.logInfo("OpenCode: taskkill failed: " + e.getMessage());
			}
		} else {
			try {
				new ProcessBuilder("pkill", "-9", "-P", String.valueOf(pid))
						.redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
				ServoyLog.logInfo("OpenCode: pkill -9 -P " + pid + " completed");
				new ProcessBuilder("kill", "-9", String.valueOf(pid))
						.redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
				ServoyLog.logInfo("OpenCode: kill -9 " + pid + " completed");
			} catch (Exception e) {
				ServoyLog.logInfo("OpenCode: pkill/kill failed: " + e.getMessage());
			}
		}

		// Fallback: if the process is still alive, use Java API
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			ServoyLog.logInfo("OpenCode: PID " + pid + " still alive after OS kill, using Java API fallback");
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}

		// taskkill /T (and the JDK's ProcessHandle.descendants()) only see processes
		// that Windows/the JVM still track as children/descendants of pid at the
		// moment they run. npm/opencode commonly launches through an intermediate
		// shell (e.g. "cmd.exe /c opencode serve"); if that shell has already
		// exited or been reparented by the time taskkill runs, the real opencode.exe
		// (and any bun.exe it spawns) becomes an orphan with no traceable parent and
		// survives both the taskkill tree-kill and the Java descendants() fallback.
		// Sweep for any leftover process whose command line points at *this*
		// bundle's managed opencode install directory, independent of any
		// parent/child relationship. This directory is always the same
		// workspace-relative OSGi state location - {workspace}/.metadata/.plugins/
		// com.servoy.eclipse.opencode/opencode - whether Eclipse is running from
		// source or from an installed Servoy Developer product, so the sweep works
		// identically in both cases.
		killOrphansUnderOpencodeDir(isWindows);

		// Close streams to unblock readLine() in RunNPMCommand.runCommand().
		//
		// IMPORTANT: this must never be done synchronously on this thread. On Windows,
		// closing a stream's underlying handle while another thread has a pending
		// blocking read() on that same handle causes CloseHandle to block until that
		// read completes. If taskkill did not manage to kill a detached grandchild
		// (npm/bun can spawn processes outside the tracked tree) the pipe's write end
		// may still be open somewhere, so the read never completes - and neither would
		// this close() call. Since this method runs on the OSGi "Framework stop"
		// thread during Eclipse shutdown, a blocked close() here would hang the entire
		// IDE shutdown forever (the main thread waits on SystemModule.waitForStop()
		// for this bundle's stop() to return). Doing the close on a daemon thread
		// guarantees stopServer()/stop() always return promptly, even if the OS-level
		// close never unblocks.
		Thread closer = new Thread(() -> {
			try {
				process.getInputStream().close();
			} catch (Exception ignored) {
			}
			try {
				process.getErrorStream().close();
			} catch (Exception ignored) {
			}
			ServoyLog.logInfo("OpenCode: process streams closed for PID: " + pid);
		}, "OpenCode-ProcessStreamCloser");
		closer.setDaemon(true);
		closer.start();
		ServoyLog.logInfo("OpenCode: process stream close scheduled (async) for PID: " + pid);
	}

	/**
	 * Force-kills any remaining OS process whose command line references this
	 * bundle's managed opencode install directory ({@code {stateLocation}/opencode}),
	 * regardless of parent/child relationship. This is a safety net for processes
	 * orphaned by {@link #killProcessTree} (see the comment there for why that can
	 * happen), so leftover {@code opencode.exe} / {@code bun.exe} processes don't
	 * keep running - and keep the port bound - after Eclipse shuts down.
	 * <p>
	 * The marker directory is this bundle's OSGi state location
	 * ({@code getStateLocation()/opencode}), which resolves the same way whether
	 * Eclipse is launched from source (workspace {@code .metadata/.plugins/...})
	 * or from an installed Servoy Developer product (product's
	 * {@code configuration/.../ .metadata/.plugins/...}) - both cases go through
	 * the same {@code Plugin#getStateLocation()} API, so this sweep works
	 * identically for both.
	 * </p>
	 */
	private void killOrphansUnderOpencodeDir(boolean isWindows) {
		File opencodeDir = getOpencodeDir();
		if (opencodeDir == null) {
			ServoyLog.logInfo("OpenCode: orphan sweep skipped - opencode dir unknown");
			return;
		}
		String marker = opencodeDir.getAbsolutePath();
		try {
			if (isWindows) {
				String escaped = marker.replace("'", "''");
				String script = "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('"
						+ escaped
						+ "') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
				Process ps = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
						.redirectErrorStream(true).start();
				ps.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
			} else {
				Process pkill = new ProcessBuilder("pkill", "-9", "-f", marker).redirectErrorStream(true).start();
				pkill.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
			}
			ServoyLog.logInfo("OpenCode: orphan sweep completed for dir: " + marker);
		} catch (Exception e) {
			ServoyLog.logInfo("OpenCode: orphan sweep failed: " + e.getMessage());
		}
	}

	/**
	 * @return the opencode install directory managed by
	 *         {@link OpencodeFolderCreatorJob} / {@link RunOpencodeCommand} (i.e.
	 *         {@code {stateLocation}/opencode}), or {@code null} if the state
	 *         location is not available (e.g. plugin not properly started, as in
	 *         some unit tests).
	 */
	private File getOpencodeDir() {
		try {
			return new File(getStateLocation().toFile(), "opencode");
		} catch (Exception e) {
			return null;
		}
	}

	// --- logging helpers ---

	/**
	 * Writes directly to this plugin's log (i.e. {@code .metadata/.log}) using our
	 * own bundle ID. Safe to call during {@link #stop} because the platform log
	 * outlives individual plugin activators.
	 */
	private void log(int severity, String message) {
		ILog log = getLog();
		if (log != null) {
			log.log(new Status(severity, PLUGIN_ID, message));
		}
	}
}

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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

/**
 * Runs a plain OS executable and adapts it to the {@link IRunNPMCommand}
 * contract, so {@link RunOpencodeCommand} can launch the opencode server the
 * same way it launched the {@code npm exec} command, and
 * {@code Activator.stopServer()} can keep stopping it through the identical
 * {@link #getProcess()} / {@link #cancel()} calls.
 * <p>
 * Unlike {@code RunNPMCommand} this does <em>not</em> go through Node/npm: the
 * opencode CLI ships as a native executable, so we start it directly. That has
 * two benefits over {@code npm exec -- opencode serve}:
 * <ul>
 * <li>The process's working directory can be the Servoy project root while the
 * executable still lives in the managed install directory - opencode derives
 * its default {@code location} (and therefore where a newly created session is
 * stored) from {@code process.cwd()}, so this is what makes a created session
 * land under the project instead of the install/state folder.</li>
 * <li>No intermediate shell/Node process sits between us and opencode, so the
 * child that must be killed on shutdown is the process we started - the source
 * of the detached-orphan problem the npm path had.</li>
 * </ul>
 * Only the members {@link RunOpencodeCommand} and {@code Activator} actually
 * use are meaningful ({@link #runCommand}, {@link #getExitCode},
 * {@link #getProcess}, {@link #cancel}, {@link #setExtraEnvironment},
 * {@link #setOutputStream}); the job-scheduling members of the interface are
 * not used for the server command (it is driven synchronously from
 * {@link RunOpencodeCommand#run}) and are implemented as no-ops.
 */
public class RunExecutableCommand implements IRunNPMCommand {

	private final File executable;
	private final List<String> arguments;
	private final File workingDirectory;

	private final Map<String, String> extraEnvironment = new java.util.HashMap<>();
	private StringOutputStream outputStream;

	private volatile Process process;
	private volatile boolean cancelled;
	private int exitCode = -1;

	/**
	 * On POSIX, set when the launch wrapped the executable in {@code setsid} so the
	 * launched process is the leader of a new process group whose pgid equals its
	 * pid. When {@code true}, {@link #terminateGroupGracefully()} can signal the
	 * whole group via {@code kill -TERM -<pid>}.
	 */
	private volatile boolean newProcessGroupLeader;

	/**
	 * Guards against closing the stdin lease more than once (idempotency).
	 */
	private volatile boolean stdinLeaseClosed;

	/**
	 * @param executable       the executable to run (absolute path)
	 * @param arguments        the arguments passed after the executable
	 * @param workingDirectory the process working directory, or {@code null} to
	 *                         inherit the JVM's
	 */
	public RunExecutableCommand(File executable, List<String> arguments, File workingDirectory) {
		this.executable = executable;
		this.arguments = List.copyOf(arguments);
		this.workingDirectory = workingDirectory;
	}

	@Override
	public void runCommand(IProgressMonitor monitor) throws IOException, InterruptedException {
		StringOutputStream console = outputStream;
		if (monitor != null && monitor.isCanceled()) {
			exitCode = RunNPMCommandExit.CANCELLED;
			return;
		}

		List<String> command = new ArrayList<>();
		boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
		if (!isWindows && setsidAvailable()) {
			// POSIX: launch opencode as the leader of a new session/process group via
			// setsid, so its pgid equals its pid. That lets Activator.stopServer()
			// signal the whole group (opencode + bun + MCP children) with a single
			// SIGTERM/SIGKILL to -<pid> during graceful shutdown. setsid execs the
			// target directly (it is not already a group leader as a JVM child), so
			// the pid getProcess() reports is the group leader.
			command.add("setsid");
			command.add(executable.getAbsolutePath());
			command.addAll(arguments);
			newProcessGroupLeader = true;
		} else {
			command.add(executable.getAbsolutePath());
			command.addAll(arguments);
			newProcessGroupLeader = false;
		}

		ProcessBuilder builder = new ProcessBuilder(command);
		if (workingDirectory != null) {
			builder.directory(workingDirectory);
		}
		builder.environment().putAll(extraEnvironment);
		// Keep the child's stdin as a JVM-held pipe (the ProcessBuilder default), so
		// process.getOutputStream() is a usable write end. Closing that write end
		// (closeStdinLease()) gives `opencode serve --stdio` its stdin EOF, which is
		// the Windows graceful-shutdown signal. Do NOT redirect stdin to INHERIT.
		builder.redirectInput(ProcessBuilder.Redirect.PIPE);
		// Merge stderr into stdout so a single reader captures everything, exactly
		// like the npm command did.
		builder.redirectErrorStream(true);

		writeConsole(console, "\n---- Launching opencode:\n" + String.join(" ", command));
		writeConsole(console,
				"In dir: " + (workingDirectory != null ? workingDirectory.getAbsolutePath() : "<inherited>"));

		process = builder.start();

		// Stream the merged output to the console until the process closes it.
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				writeConsole(console, line);
			}
		} catch (IOException e) {
			// Stream torn down (e.g. process killed on shutdown) - not fatal.
			if (!cancelled) {
				ServoyLog.logInfo("OpenCode: error reading server output: " + e.getMessage());
			}
		}

		process.waitFor();
		exitCode = cancelled ? RunNPMCommandExit.CANCELLED : process.exitValue();
		writeConsole(console, "opencode exited with code " + exitCode);
	}

	private static void writeConsole(StringOutputStream console, String message) {
		if (console == null) {
			return;
		}
		try {
			console.write(message + "\n");
		} catch (IOException ignored) {
			// Console gone - nothing useful to do.
		}
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	@Override
	public Process getProcess() {
		return process;
	}

	@Override
	public boolean cancel() {
		cancelled = true;
		Process p = process;
		if (p != null) {
			p.destroy();
		}
		return true;
	}

	/**
	 * Closes the write end of the child's stdin pipe, giving a
	 * {@code opencode serve --stdio} server its stdin EOF. opencode's scoped server
	 * effect ends on that EOF, which cancels running turns and stops its MCP child
	 * process trees - the Windows graceful-shutdown mechanism.
	 * <p>
	 * Null-safe (no-op when the process never started) and idempotent (safe to call
	 * repeatedly); never throws out of shutdown.
	 */
	public void closeStdinLease() {
		if (stdinLeaseClosed) {
			return;
		}
		stdinLeaseClosed = true;
		Process p = process;
		if (p == null) {
			return;
		}
		try {
			p.getOutputStream().close();
			ServoyLog.logInfo("OpenCode: stdin lease closed (graceful --stdio shutdown signal)");
		} catch (Exception e) {
			// Stream already gone or process dead - graceful signal is best-effort.
			ServoyLog.logInfo("OpenCode: closeStdinLease() - nothing to close: " + e.getMessage());
		}
	}

	/**
	 * POSIX graceful group termination: sends {@code SIGTERM} to opencode's process
	 * group so opencode and its children (bun, MCP servers) all receive it and can
	 * dispose cleanly. Only effective when the launch put opencode in its own
	 * process group (via {@code setsid}), in which case its pgid equals the tracked
	 * pid and {@code kill -TERM -<pid>} reaches the whole group.
	 *
	 * @return {@code true} if a SIGTERM was issued to the group, {@code false}
	 *         otherwise. Never throws.
	 */
	public boolean terminateGroupGracefully() {
		Process p = process;
		if (p == null || !newProcessGroupLeader) {
			return false;
		}
		long pid = p.pid();
		try {
			// Negative pid targets the whole process group whose pgid == pid.
			new ProcessBuilder("kill", "-TERM", "-" + pid).redirectErrorStream(true).start().waitFor(5,
					java.util.concurrent.TimeUnit.SECONDS);
			ServoyLog.logInfo("OpenCode: sent SIGTERM to process group -" + pid + " (graceful shutdown)");
			return true;
		} catch (Exception e) {
			ServoyLog.logInfo("OpenCode: kill -TERM -" + pid + " failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * @return {@code true} if a {@code setsid} executable can be found on PATH, so
	 *         the POSIX launch can start opencode as a new-process-group leader.
	 */
	private static boolean setsidAvailable() {
		try {
			Process which = new ProcessBuilder("which", "setsid").redirectErrorStream(true).start();
			boolean finished = which.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
			return finished && which.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public void setExtraEnvironment(Map<String, String> environment) {
		extraEnvironment.clear();
		if (environment != null) {
			extraEnvironment.putAll(environment);
		}
	}

	@Override
	public void setOutputStream(StringOutputStream outputStream) {
		this.outputStream = outputStream;
	}

	// --- Job-scheduling members of the interface: not used for the server
	// command, which RunOpencodeCommand drives directly via runCommand(). ---

	@Override
	public void setUser(boolean user) {
		// No-op: this command is not scheduled as a standalone job.
	}

	@Override
	public void schedule() {
		throw new UnsupportedOperationException("RunExecutableCommand is run directly, not scheduled");
	}

	@Override
	public void join() {
		// No-op: nothing to join, runCommand() is synchronous.
	}

	/** Exit-code constant mirror to avoid a hard dependency on RunNPMCommand's. */
	static final class RunNPMCommandExit {
		static final int CANCELLED = com.servoy.eclipse.ngclient.ui.RunNPMCommand.EXIT_CODE_CANCELLED;

		private RunNPMCommandExit() {
		}
	}
}

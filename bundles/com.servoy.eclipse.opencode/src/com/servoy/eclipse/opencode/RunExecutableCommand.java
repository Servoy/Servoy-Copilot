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
	 * Upper bound (SVY-21643) on how long {@link #runCommand} waits for the child to
	 * exit after its output stream has closed, before force-killing the whole tree.
	 * The output stream closing already signals the child is on its way out, so this
	 * only guards the pathological case where a grandchild keeps the process (and
	 * thus this non-daemon Job thread, and the forked JVM) alive.
	 */
	private static final long PROCESS_EXIT_WAIT_MS = 5000L;

	/**
	 * On POSIX, set when the launch wrapped the executable in {@code setsid} and the
	 * child was verified to lead its own process group. Only informational now:
	 * {@link #terminateGracefully()} signals exact pids and no longer sends a group
	 * signal, so nothing depends on this flag for safety (SVY-21643).
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
			// SIGTERM to -<pid> during graceful shutdown. setsid execs the target
			// directly (it is not already a group leader as a JVM child), so the pid
			// getProcess() reports is the group leader.
			//
			// SAFETY (build #80): we must NEVER send a negative-pid group signal
			// unless the child really is its own group leader. Plain `setsid <exe>`
			// forks (making a new group) ONLY when setsid is itself already a group
			// leader; otherwise it exec()s the target IN PLACE, leaving it in the
			// CALLER's group. Under Jenkins (no job control) the forked workbench JVM
			// is not a group leader, so `setsid` without this check could exec
			// opencode straight into Jenkins' own process group - and the later
			// `kill -TERM -<pid>` would then hit the ENTIRE Jenkins group, taking down
			// the controller JVM (build #80: Jenkins exit 143 / Jetty shutdown ~0.5s
			// after the JSUnit-verify teardown). So after launch we VERIFY the child's
			// real pgid actually equals its pid (i.e. it leads its own group) before
			// allowing any group-targeted kill; see verifyIsGroupLeader().
			//
			// NOTE (SVY-21643): the reasoning above turned out to be wrong - the real
			// cause was /usr/bin/kill parsing the negative pid as options (missing `--`),
			// which became kill(-1). Shutdown now signals exact pids via ProcessHandle
			// (terminateGracefully()), so no group signal is sent at all; the setsid
			// launch stays only to give opencode its own session.
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

		// Opt-in: record the launch-time process topology (pid/pgid/sid of the child
		// and of this JVM) so a later shutdown log can be read against it (SVY-21643).
		ShutdownDiagnostics.logLaunchTopology(process, newProcessGroupLeader);

		// Confirm the setsid child really leads its own process group (pgid == pid)
		// before we ever allow a group-targeted kill. If setsid exec'd in place (it
		// was not a group leader, e.g. under Jenkins), the child inherited the JVM's
		// group and a negative-pid signal would hit the whole Jenkins tree - so demote
		// to per-process kills in that case (build #80).
		if (newProcessGroupLeader) {
			newProcessGroupLeader = verifyIsGroupLeader(process.pid());
		}

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

		// Bounded wait (SVY-21643): never block the (non-daemon) server Job thread on
		// an unbounded waitFor(). If the child survived the shutdown signals (a bun/node
		// grandchild kept the group alive on CI), an unbounded wait here holds this
		// thread - and therefore the forked workbench JVM - open forever, which hung the
		// headless skilltest build for hours (build #76). Once cancelled, force-kill the
		// whole tree and stop waiting so the thread returns and the JVM can exit.
		if (!process.waitFor(PROCESS_EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
			ServoyLog.logInfo("OpenCode: process did not exit within " + PROCESS_EXIT_WAIT_MS
					+ " ms of its output stream closing - force-killing the tree");
			forceKillTree();
			// Give the force-kill a brief moment; do not wait unbounded again.
			process.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		exitCode = cancelled ? RunNPMCommandExit.CANCELLED
				: (process.isAlive() ? RunNPMCommandExit.CANCELLED : process.exitValue());
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
		markCancelled();
		// Force-kill the whole tree and break the pipe the reader loop is blocked on,
		// so the (non-daemon) server Job thread in runCommand() unwinds promptly
		// instead of sitting in readLine()/waitFor() on a surviving child (SVY-21643).
		forceKillTree();
		return true;
	}

	/**
	 * Flags this command as cancelled WITHOUT killing anything, so
	 * {@link #runCommand} reports {@code CANCELLED} (no retry) and treats a torn-down
	 * output stream as expected. Used by {@code Activator.stopServer()} when its own
	 * {@code killProcessTree()} does the forced kill, so the tree is not SIGKILLed
	 * twice. Idempotent.
	 */
	public void markCancelled() {
		cancelled = true;
	}

	/**
	 * Force-terminates the launched process and its descendant tree, and closes its
	 * output stream so the {@code readLine()} loop in {@link #runCommand} returns
	 * (SVY-21643). Best-effort and idempotent; never throws out of shutdown. On
	 * POSIX the process is a group leader (setsid), so {@link Process#descendants()}
	 * plus a group-targeted kill reach bun/node/MCP grandchildren; the extra
	 * {@code destroyForcibly()} on each handle is the cross-platform safety net.
	 */
	private void forceKillTree() {
		Process p = process;
		if (p == null) {
			return;
		}
		try {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
		} catch (RuntimeException ignored) {
			// descendants() can race with exit; ignore.
		}
		p.destroyForcibly();
		// Closing the child's stdout unblocks the readLine() loop even if a detached
		// grandchild still holds the write end; this must not block the caller, so a
		// close that hangs (Windows, pending read on the same handle) is isolated on a
		// daemon thread - exactly as Activator.killProcessTree does.
		Thread closer = new Thread(() -> {
			try {
				p.getInputStream().close();
			} catch (Exception ignored) {
				// stream already gone - nothing to do
			}
		}, "OpenCode-ExecStreamCloser");
		closer.setDaemon(true);
		closer.start();
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
	 * POSIX graceful termination: sends {@code SIGTERM} to opencode's descendants
	 * (bun, MCP servers) and then to opencode itself, through
	 * {@link ProcessHandle#destroy()} - one direct syscall per exact pid - so they
	 * can dispose cleanly. The caller then waits a grace window before escalating to
	 * a forced kill.
	 * <p>
	 * No external {@code kill} binary and no negative-pid (process-group) signal is
	 * ever used: that is what took down the Jenkins controller (SVY-21643, see the
	 * comment in the body).
	 * </p>
	 *
	 * @return {@code true} if the SIGTERM to opencode itself was issued,
	 *         {@code false} when no process was started or it was already gone.
	 *         Never throws.
	 */
	public boolean terminateGracefully() {
		Process p = process;
		if (p == null) {
			return false;
		}
		long pid = p.pid();
		// SVY-21643 root cause (builds #80/#81/#83): this used to run the external
		// `kill -TERM -<pid>` binary to signal opencode's process group, WITHOUT the
		// `--` that POSIX requires before a negative pid. procps-ng's /usr/bin/kill
		// then parses the negative pid as options, and on the build server
		// `kill -TERM -1993873` became kill(-1, SIGTERM) - "signal every process this
		// user may signal" - which SIGTERM'd the Jenkins controller, the workbench and
		// mvn all at once (confirmed with strace: `kill -TERM -2023865` issued
		// kill(-2, SIGTERM)). Whether it hit -1 depended on the pid's digits, which is
		// why earlier runs survived.
		//
		// So NEVER shell out to kill. Signal the exact pids through ProcessHandle
		// instead: destroy() is a direct SIGTERM syscall to one pid, with no command
		// line to parse and no group / -1 targeting possible. Descendants first, then
		// opencode itself, so opencode still gets its graceful SIGTERM and its children
		// (bun / MCP servers) are not orphaned. Not gated on the process-group check any
		// more: no group signal is sent, so the SIGTERM must always go out.
		//
		// POSIX only: on Windows ProcessHandle.destroy() is a hard TerminateProcess, so
		// the caller uses closeStdinLease() there instead.
		ShutdownDiagnostics.logSignal("graceful SIGTERM via ProcessHandle (descendants + root)", "TERM",
				String.valueOf(pid));
		try {
			p.descendants().forEach(ProcessHandle::destroy);
		} catch (RuntimeException ignored) {
			// descendants() can race with exit; the root destroy below still runs.
		}
		boolean sent = p.toHandle().destroy();
		ServoyLog.logInfo("OpenCode: sent SIGTERM to opencode pid " + pid + " and its descendants (graceful shutdown)");
		return sent;
	}

	/**
	 * Verifies, right after launch, that the {@code setsid}-launched child actually
	 * became its own process-group leader (i.e. its pgid equals its pid). This is
	 * the guard against the build #80 failure: when {@code setsid} is not itself a
	 * group leader (as under Jenkins, which has no job control) it exec()s the
	 * target in place, leaving it in the JVM's - and therefore Jenkins' - process
	 * group. A negative-pid group kill in that state would terminate the whole
	 * Jenkins tree.
	 *
	 * @param pid the launched child's pid
	 * @return {@code true} only if the child provably leads its own group and that
	 *         group differs from this JVM's; {@code false} on any doubt
	 */
	private static boolean verifyIsGroupLeader(long pid) {
		boolean safe = isSafeOwnGroupLeader(pid);
		if (safe) {
			ServoyLog.logInfo("OpenCode: verified opencode pid " + pid
					+ " leads its own process group - group shutdown enabled");
		} else {
			ServoyLog.logInfo("OpenCode: opencode pid " + pid
					+ " did NOT become its own group leader (setsid exec'd in place?) - "
					+ "group kill DISABLED, will use per-process kill (protects a shared/Jenkins group)");
		}
		return safe;
	}

	/**
	 * @return {@code true} iff process {@code pid} is alive, is its own process-group
	 *         leader ({@code pgid == pid}), and that group is <b>not</b> this JVM's
	 *         own process group. Any read failure or ambiguity yields {@code false},
	 *         so a group-targeted kill is only ever issued when it is provably safe.
	 */
	private static boolean isSafeOwnGroupLeader(long pid) {
		Long childPgid = readPgid(pid);
		if (childPgid == null || childPgid.longValue() != pid) {
			return false; // unknown, or not a group leader -> unsafe
		}
		Long ownPgid = readPgid(currentPid());
		// If we cannot read our own pgid, be conservative only when it would collide;
		// here we simply require the child's group to differ from ours when known.
		if (ownPgid != null && ownPgid.longValue() == childPgid.longValue()) {
			return false; // child shares the JVM's group -> never group-kill
		}
		return true;
	}

	/**
	 * Reads a process's group id from {@code /proc/<pid>/stat} (field 5, pgrp). This
	 * avoids spawning {@code ps} and is accurate on Linux (the CI platform). Returns
	 * {@code null} when the file is missing/unreadable or cannot be parsed.
	 */
	private static Long readPgid(long pid) {
		java.nio.file.Path stat = java.nio.file.Path.of("/proc", String.valueOf(pid), "stat");
		if (!java.nio.file.Files.isReadable(stat)) {
			return null;
		}
		try {
			return parsePgidFromStat(java.nio.file.Files.readString(stat, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Parses the process-group id (field 5, {@code pgrp}) out of the contents of a
	 * Linux {@code /proc/<pid>/stat} line. Package-visible and pure so it can be unit
	 * tested without a real {@code /proc} (SVY-21643, build #80 guard).
	 * <p>
	 * The format is {@code pid (comm) state ppid pgrp ...}. {@code comm} is the
	 * executable name and may itself contain spaces and parentheses (e.g.
	 * {@code (my proc)}), so this splits on the <b>last</b> {@code ')'} and reads the
	 * whitespace-separated fields after it: {@code state(0) ppid(1) pgrp(2)}.
	 *
	 * @param stat the raw stat line
	 * @return the pgrp, or {@code null} when the line is missing, truncated, or the
	 *         pgrp field is not an integer
	 */
	static Long parsePgidFromStat(String stat) {
		if (stat == null) {
			return null;
		}
		int close = stat.lastIndexOf(')');
		if (close < 0 || close + 2 >= stat.length()) {
			return null;
		}
		String[] rest = stat.substring(close + 2).trim().split("\\s+");
		// rest[0]=state, rest[1]=ppid, rest[2]=pgrp
		if (rest.length < 3) {
			return null;
		}
		try {
			return Long.valueOf(rest[2]);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** @return this JVM's own pid. */
	private static long currentPid() {
		return ProcessHandle.current().pid();
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

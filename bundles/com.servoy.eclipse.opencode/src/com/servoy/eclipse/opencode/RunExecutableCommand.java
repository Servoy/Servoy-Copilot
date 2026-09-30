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
 * Only the members {@link RunOpencodeCommand} and {@code Activator} actually use
 * are meaningful ({@link #runCommand}, {@link #getExitCode}, {@link #getProcess},
 * {@link #cancel}, {@link #setExtraEnvironment}, {@link #setOutputStream}); the
 * job-scheduling members of the interface are not used for the server command
 * (it is driven synchronously from {@link RunOpencodeCommand#run}) and are
 * implemented as no-ops.
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
		command.add(executable.getAbsolutePath());
		command.addAll(arguments);

		ProcessBuilder builder = new ProcessBuilder(command);
		if (workingDirectory != null) {
			builder.directory(workingDirectory);
		}
		builder.environment().putAll(extraEnvironment);
		// Merge stderr into stdout so a single reader captures everything, exactly
		// like the npm command did.
		builder.redirectErrorStream(true);

		writeConsole(console, "\n---- Launching opencode:\n" + String.join(" ", command));
		writeConsole(console, "In dir: " + (workingDirectory != null ? workingDirectory.getAbsolutePath() : "<inherited>"));

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

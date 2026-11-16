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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Opt-in diagnostic logging for the opencode server shutdown, added for
 * SVY-21643 after build #80/#81 showed the Jenkins controller JVM dying around
 * the time the skill-test run tore the opencode server down. The whole point is
 * to make the next unattended CI failure answer two questions from the log
 * alone - <b>who triggered the shutdown</b> and <b>which OS processes/groups did
 * we actually signal</b> - so the cause (a leaked group-kill of ours vs. an
 * external host event like an OOM-kill or a Jenkins restart) is provable instead
 * of guessed.
 * <p>
 * Everything here is <b>gated behind {@code -Dopencode.shutdown.debug=true}</b>
 * (the skill-test launcher sets it). When the flag is off, every method is a
 * cheap no-op: no process enumeration, no {@code /proc} reads, nothing logged.
 * So this is safe to leave compiled into the product and costs nothing in normal
 * Developer use.
 * </p>
 * <p>
 * All output goes to {@link ServoyLog} (the plugin's {@code .metadata/.log},
 * which on CI is {@code target/work/data/.metadata/.log}) with a stable
 * {@code [opencode-shutdown-diag]} prefix so it greps cleanly out of a noisy
 * workbench log. Everything is best-effort and never throws out of shutdown.
 * </p>
 *
 * @since 2026.09
 */
final class ShutdownDiagnostics {

	/** System property that turns this diagnostic logging on. */
	static final String DEBUG_PROPERTY = "opencode.shutdown.debug"; //$NON-NLS-1$

	private static final String PREFIX = "[opencode-shutdown-diag] "; //$NON-NLS-1$

	private ShutdownDiagnostics() {
	}

	/** @return {@code true} when {@code -Dopencode.shutdown.debug=true} is set. */
	static boolean enabled() {
		return Boolean.getBoolean(DEBUG_PROPERTY);
	}

	/** Logs a single diagnostic line (no-op when disabled). Never throws. */
	static void log(String message) {
		if (!enabled()) {
			return;
		}
		try {
			ServoyLog.logInfo(PREFIX + message);
		} catch (RuntimeException | LinkageError ignore) {
			// logging must never break shutdown
		}
	}

	/**
	 * Logs the launch-time process identity of the opencode child and of this JVM,
	 * so a later shutdown log can be read against a known baseline: which pid/pgid/
	 * sid opencode got, and crucially whether that group/session differs from this
	 * (workbench) JVM's - the thing that decides whether a group-targeted kill is
	 * safe. Call right after the child process is started.
	 *
	 * @param child               the launched opencode process
	 * @param requestedGroupLeader whether the launch asked for a new process group
	 *                            (via {@code setsid})
	 */
	static void logLaunchTopology(Process child, boolean requestedGroupLeader) {
		if (!enabled() || child == null) {
			return;
		}
		try {
			long childPid = child.pid();
			long jvmPid = ProcessHandle.current().pid();
			log("LAUNCH opencode child pid=" + childPid //$NON-NLS-1$
					+ " pgid=" + str(readStatField(childPid, StatField.PGRP)) //$NON-NLS-1$
					+ " sid=" + str(readStatField(childPid, StatField.SESSION)) //$NON-NLS-1$
					+ " ppid=" + str(readStatField(childPid, StatField.PPID)) //$NON-NLS-1$
					+ " comm=" + str(readComm(childPid)) //$NON-NLS-1$
					+ " | requestedNewGroup(setsid)=" + requestedGroupLeader); //$NON-NLS-1$
			log("LAUNCH this JVM pid=" + jvmPid //$NON-NLS-1$
					+ " pgid=" + str(readStatField(jvmPid, StatField.PGRP)) //$NON-NLS-1$
					+ " sid=" + str(readStatField(jvmPid, StatField.SESSION)) //$NON-NLS-1$
					+ " ppid=" + str(readStatField(jvmPid, StatField.PPID))); //$NON-NLS-1$
			// The decisive comparison, spelled out so it is unmissable in the log.
			Long childPgid = readStatField(childPid, StatField.PGRP);
			Long jvmPgid = readStatField(jvmPid, StatField.PGRP);
			if (childPgid != null && jvmPgid != null) {
				boolean shareGroup = childPgid.longValue() == jvmPgid.longValue();
				boolean ownLeader = childPgid.longValue() == childPid;
				log("LAUNCH group analysis: opencode leadsOwnGroup=" + ownLeader //$NON-NLS-1$
						+ " sharesJvmGroup=" + shareGroup //$NON-NLS-1$
						+ (shareGroup
								? " -> a group-wide kill WOULD reach this JVM's group; group-kill must stay disabled" //$NON-NLS-1$
								: " -> opencode is isolated; a group-wide kill is safe")); //$NON-NLS-1$
			}
		} catch (RuntimeException | LinkageError ignore) {
			// best-effort
		}
	}

	/**
	 * Logs WHO is triggering a shutdown and WHY, with a short stack so the trigger
	 * (OSGi {@code Activator.stop} during IDE/framework shutdown, the skill-test
	 * {@code SkillTestStartup} finally block, a manual stop, ...) is identifiable
	 * from the log. Call at the very top of {@code stopServer()}.
	 *
	 * @param reason a short human label for the caller/trigger
	 */
	static void logShutdownTrigger(String reason) {
		if (!enabled()) {
			return;
		}
		log("SHUTDOWN TRIGGERED: " + reason + " | onThread=" //$NON-NLS-1$ //$NON-NLS-2$
				+ Thread.currentThread().getName());
		// A trimmed caller stack: enough to name the trigger, not the whole workbench.
		try {
			StackTraceElement[] st = Thread.currentThread().getStackTrace();
			String frames = java.util.Arrays.stream(st)
					.skip(2) // skip getStackTrace + this method
					.limit(12)
					.map(e -> "    at " + e) //$NON-NLS-1$
					.collect(Collectors.joining("\n")); //$NON-NLS-1$
			log("SHUTDOWN caller stack:\n" + frames); //$NON-NLS-1$
		} catch (RuntimeException ignore) {
			// best-effort
		}
	}

	/**
	 * Logs the full descendant tree of the opencode root process right before any
	 * kill, with each process's pid and - on Linux - its pgid/sid read from
	 * {@code /proc}. This is the "before" snapshot: it shows exactly what exists
	 * and which groups/sessions those processes belong to, so the subsequent
	 * per-signal logs can be read against it and any process that is NOT part of
	 * opencode's own group stands out.
	 *
	 * @param root the tracked opencode process (may be {@code null})
	 */
	static void logProcessTreeSnapshot(Process root) {
		if (!enabled()) {
			return;
		}
		if (root == null) {
			log("SNAPSHOT: no tracked opencode process (root == null)"); //$NON-NLS-1$
			return;
		}
		try {
			long rootPid = root.pid();
			log("SNAPSHOT root opencode pid=" + rootPid + " alive=" + root.isAlive() //$NON-NLS-1$ //$NON-NLS-2$
					+ describeProc(rootPid));
			List<ProcessHandle> descendants = root.descendants().collect(Collectors.toList());
			if (descendants.isEmpty()) {
				log("SNAPSHOT root has no live descendants"); //$NON-NLS-1$
			} else {
				for (ProcessHandle h : descendants) {
					log("SNAPSHOT   descendant pid=" + h.pid() //$NON-NLS-1$
							+ describeProc(h.pid())
							+ " cmd=" + str(h.info().commandLine().orElse(null))); //$NON-NLS-1$
				}
			}
		} catch (RuntimeException | LinkageError ignore) {
			// best-effort
		}
	}

	/**
	 * Logs the exact target of a signal we are about to send: whether it is a
	 * whole-process-group signal (a negative pid) or a single process, the signal
	 * name, and the pid/pgid it resolves to. This is the heart of the diagnostic -
	 * it records precisely what opencode shutdown told the OS to kill, so a leaked
	 * group-kill of the Jenkins group would be caught red-handed.
	 *
	 * @param description what the call is (e.g. "graceful group SIGTERM")
	 * @param signal      the signal name (e.g. "TERM", "9")
	 * @param target      the kill target as passed to {@code kill} (e.g. "-12345"
	 *                    for a group, "12345" for a single pid)
	 */
	static void logSignal(String description, String signal, String target) {
		if (!enabled()) {
			return;
		}
		boolean groupTargeted = target != null && target.startsWith("-"); //$NON-NLS-1$
		String pidPart = groupTargeted ? target.substring(1) : target;
		String extra = ""; //$NON-NLS-1$
		try {
			long pid = Long.parseLong(pidPart);
			extra = describeProc(pid);
		} catch (NumberFormatException ignore) {
			// target is not a bare pid (e.g. a -f marker) - nothing to resolve
		}
		log("SIGNAL " + description + ": kill -" + signal + " " + target //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ " [" + (groupTargeted ? "WHOLE PROCESS GROUP" : "single process") + "]" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				+ extra);
	}

	/**
	 * Logs a pattern-based sweep target (e.g. {@code pkill -9 -f <marker>} /
	 * {@code taskkill} via a command-line match), which does not resolve to a
	 * single pid.
	 */
	static void logSweep(String description, String command, String pattern) {
		if (!enabled()) {
			return;
		}
		log("SWEEP " + description + ": " + command + " matching '" + pattern + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	// --- /proc helpers (Linux CI) ---

	private enum StatField {
		PPID(1), PGRP(2), SESSION(3);

		final int indexAfterComm;

		StatField(int indexAfterComm) {
			this.indexAfterComm = indexAfterComm;
		}
	}

	/** Builds a " pgid=.. sid=.. comm=.." suffix for a pid, empty when unknown. */
	private static String describeProc(long pid) {
		Long pgid = readStatField(pid, StatField.PGRP);
		Long sid = readStatField(pid, StatField.SESSION);
		String comm = readComm(pid);
		if (pgid == null && sid == null && comm == null) {
			return ""; //$NON-NLS-1$ // non-Linux or already gone
		}
		return " (pgid=" + str(pgid) + " sid=" + str(sid) + " comm=" + str(comm) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/**
	 * Reads a numeric field from {@code /proc/<pid>/stat}. The format is
	 * {@code pid (comm) state ppid pgrp session ...}; {@code comm} can contain
	 * spaces/parens so parsing splits on the last {@code ')'}. Returns {@code null}
	 * when the file is absent/unreadable (non-Linux, or the process already exited)
	 * or the field cannot be parsed.
	 */
	private static Long readStatField(long pid, StatField field) {
		String stat = readProc(pid, "stat"); //$NON-NLS-1$
		if (stat == null) {
			return null;
		}
		int close = stat.lastIndexOf(')');
		if (close < 0 || close + 2 >= stat.length()) {
			return null;
		}
		String[] rest = stat.substring(close + 2).trim().split("\\s+"); //$NON-NLS-1$
		if (rest.length <= field.indexAfterComm) {
			return null;
		}
		try {
			return Long.valueOf(rest[field.indexAfterComm]);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Reads the executable name from {@code /proc/<pid>/comm}, trimmed; null if unavailable. */
	private static String readComm(long pid) {
		String comm = readProc(pid, "comm"); //$NON-NLS-1$
		return comm == null ? null : comm.strip();
	}

	private static String readProc(long pid, String file) {
		try {
			Path p = Path.of("/proc", String.valueOf(pid), file); //$NON-NLS-1$
			if (!Files.isReadable(p)) {
				return null;
			}
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (RuntimeException | java.io.IOException e) {
			return null;
		}
	}

	private static String str(Object value) {
		return value == null ? "?" : value.toString(); //$NON-NLS-1$
	}
}

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.ngclient.ui.IConsole;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

class ExportSessionJob extends Job {
	private final File opencodeDir;
	private final String projectPath;
	private final int port;
	private final String trackedSessionId;
	private final File targetFile;

	/**
	 * Test-only injection point. When non-{@code null}, {@link #run} uses this
	 * factory to create the {@link IRunNPMCommand} instead of going through the
	 * ngclient.ui Activator. Must be reset to {@code null} after each test.
	 */
	// package-private for testing
	@FunctionalInterface
	interface NpmCommandFactory {
		IRunNPMCommand create(File workDir, List<String> args);
	}

	static volatile NpmCommandFactory testCommandFactory = null;

	/**
	 * Test-only flag. When {@code true}, {@link #notifyUi} skips the
	 * {@link Display} call so tests can run without an SWT event loop.
	 * Must be reset to {@code false} after each test.
	 */
	// package-private for testing
	static volatile boolean testNotifyUiSuppressed = false;

	ExportSessionJob(File opencodeDir, String projectPath, int port, String trackedSessionId, File targetFile) {
		super("Exporting opencode session"); //$NON-NLS-1$
		this.opencodeDir = opencodeDir;
		this.projectPath = projectPath;
		this.port = port;
		this.trackedSessionId = trackedSessionId;
		this.targetFile = targetFile;
		setUser(true);
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		String sessionId = trackedSessionId != null ? trackedSessionId
				: OpenCodeUtil.findLastSessionId(port, projectPath);
		if (sessionId == null) {
			notifyUi(false, "No opencode session found for this project.", null); //$NON-NLS-1$
			return Status.OK_STATUS;
		}

		IRunNPMCommand cmd;
		NpmCommandFactory factory = testCommandFactory;
		if (factory != null) {
			cmd = factory.create(opencodeDir, buildExportCommandArgs(sessionId));
		} else {
			com.servoy.eclipse.ngclient.ui.Activator ngActivator = com.servoy.eclipse.ngclient.ui.Activator.getInstance();
			if (ngActivator == null) {
				notifyUi(false, "Node.js Activator not available.", null); //$NON-NLS-1$
				return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Node.js Activator not available"); //$NON-NLS-1$
			}
			cmd = ngActivator.createNPMCommand(opencodeDir, buildExportCommandArgs(sessionId));
		}

		Activator activator = Activator.getInstance();
		if (activator == null) {
			return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Activator not available"); //$NON-NLS-1$
		}

		Map<String, String> env = new HashMap<>(RunOpencodeCommand.buildServoyXdgEnv());
		env.put("PWD", projectPath); //$NON-NLS-1$
		cmd.setExtraEnvironment(env);

		StringBuilder captured = new StringBuilder();
		IConsole console = activator.getConsole();
		StringOutputStream consoleOut = console != null ? console.outputStream() : null;
		cmd.setOutputStream(new CapturingOutputStream(captured, consoleOut));

		try {
			cmd.runCommand(monitor);
		} catch (IOException | InterruptedException e) {
			notifyUi(false, "Export failed: " + e.getMessage(), null); //$NON-NLS-1$
			return Status.OK_STATUS;
		}

		if (cmd.getExitCode() != 0) {
			notifyUi(false, "opencode export exited with code " + cmd.getExitCode() + ". See Servoy AI Console.", null); //$NON-NLS-1$ //$NON-NLS-2$
			return Status.OK_STATUS;
		}

		String json = stripNonJsonPreamble(captured.toString());
		try {
			Files.writeString(targetFile.toPath(), json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			notifyUi(false, "Failed to write export file: " + e.getMessage(), null); //$NON-NLS-1$
			return Status.OK_STATUS;
		}

		activator.logToConsole("Session exported to: " + targetFile.getAbsolutePath()); //$NON-NLS-1$
		notifyUi(true, targetFile.getAbsolutePath(), targetFile);
		return Status.OK_STATUS;
	}

	static List<String> buildExportCommandArgs(String sessionId) {
		return List.of("exec", "--", "opencode", "export", sessionId, "--sanitize"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	static String stripNonJsonPreamble(String raw) {
		int braceIdx = raw.indexOf('{');
		int bracketIdx = raw.indexOf('[');
		int jsonStart = -1;
		if (braceIdx >= 0 && bracketIdx >= 0) {
			jsonStart = Math.min(braceIdx, bracketIdx);
		} else if (braceIdx >= 0) {
			jsonStart = braceIdx;
		} else if (bracketIdx >= 0) {
			jsonStart = bracketIdx;
		}
		if (jsonStart > 0) {
			return raw.substring(jsonStart);
		}
		return raw;
	}

	private void notifyUi(boolean success, String detail, File savedFile) {
		Activator activator = Activator.getInstance();
		if (activator != null) {
			activator.logToConsole(success ? "Export complete: " + detail : "Export error: " + detail); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (testNotifyUiSuppressed) {
			return;
		}
		Display.getDefault().asyncExec(() -> {
			if (success && savedFile != null) {
				MessageDialog dialog = new MessageDialog(Display.getDefault().getActiveShell(), "Export complete", //$NON-NLS-1$
						null, "Session exported to:\n" + savedFile.getAbsolutePath(), //$NON-NLS-1$
						MessageDialog.INFORMATION, new String[] { "Show in Explorer", "OK" }, //$NON-NLS-1$ //$NON-NLS-2$
						1);
				if (dialog.open() == 0) {
					OpenCodeView.revealInFileExplorer(savedFile);
				}
			} else {
				MessageDialog.openWarning(Display.getDefault().getActiveShell(), "Export session", //$NON-NLS-1$
						detail);
			}
		});
	}

	private static class CapturingOutputStream implements StringOutputStream {
		private final StringBuilder captured;
		private final StringOutputStream delegate;

		CapturingOutputStream(StringBuilder captured, StringOutputStream delegate) {
			this.captured = captured;
			this.delegate = delegate;
		}

		@Override
		public void write(CharSequence chars) throws IOException {
			captured.append(chars);
			if (delegate != null) {
				delegate.write(chars);
			}
		}

		@Override
		public void close() throws IOException {
			if (delegate != null) {
				delegate.close();
			}
		}
	}
}

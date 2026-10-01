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

import org.eclipse.ui.IStartup;

/**
 * Runs the Servoy AI skill-test baselines once the Eclipse workbench has
 * started, then terminates the process with the skill-test exit code.
 * <p>
 * The skill test needs the Servoy MCP tools, which (via
 * {@code com.servoy.eclipse.core}) require a running workbench. A bare
 * {@code org.eclipse.core.runtime.applications} application does <b>not</b> start
 * a workbench, so the runner is launched as the <b>product / workbench
 * application</b> ({@code org.eclipse.ui.ide.workbench}) and this
 * {@link IStartup} fires after the workbench is up (so core + app server + MCP
 * are coming up). It then runs the baselines on a background thread and, when
 * done, stops the opencode server and halts the JVM with the exit code.
 * </p>
 * <p>
 * Only runs when {@code -Dservoy.skilltest.run=true} (or the
 * {@code SERVOY_SKILLTEST_RUN} env var), so opening Servoy Developer normally
 * does not trigger a skill-test run.
 * </p>
 */
public class SkillTestStartup implements IStartup {

	/** Enable the run: {@code -Dservoy.skilltest.run=true} (or env SERVOY_SKILLTEST_RUN=true). */
	public static final String RUN_PROPERTY = "servoy.skilltest.run"; //$NON-NLS-1$
	public static final String RUN_ENV = "SERVOY_SKILLTEST_RUN"; //$NON-NLS-1$

	@Override
	public void earlyStartup() {
		if (!isRunRequested()) {
			return;
		}
		Thread t = new Thread(SkillTestStartup::runAndExit, "servoy-skilltest-runner"); //$NON-NLS-1$
		t.setDaemon(false);
		t.start();
	}

	private static boolean isRunRequested() {
		if (Boolean.parseBoolean(System.getProperty(RUN_PROPERTY, "false"))) { //$NON-NLS-1$
			return true;
		}
		return Boolean.parseBoolean(System.getenv().getOrDefault(RUN_ENV, "false")); //$NON-NLS-1$
	}

	private static void runAndExit() {
		int exit = 2; // infra failure by default
		try {
			exit = new SkillTestHeadlessRunner().runInWorkbench();
		} catch (Throwable t) {
			System.err.println("[skilltest] run failed: " + t.getMessage());
			t.printStackTrace();
		} finally {
			stopOpencodeServer();
			shutdownAppServer();
			System.out.println("[skilltest] run complete, exit code " + exit + ".");
			Runtime.getRuntime().halt(exit);
		}
	}

	/**
	 * Stops the embedded opencode server (killing its node process tree) before we
	 * {@code halt()} the JVM. {@code halt} skips shutdown hooks and bundle
	 * {@code stop()} methods, so without this the opencode/node child is orphaned
	 * and keeps its port held, forcing the next run to a different port.
	 */
	private static void stopOpencodeServer() {
		try {
			com.servoy.eclipse.opencode.Activator opencode = com.servoy.eclipse.opencode.Activator.getInstance();
			if (opencode != null) {
				System.out.println("[skilltest] stopping opencode server...");
				opencode.stopServer();
				Thread.sleep(2_000L);
			}
		} catch (Throwable t) {
			System.err.println("[skilltest] opencode stop failed: " + t);
		}
	}

	/**
	 * Shuts down the Servoy application server / embedded Tomcat so its web-server
	 * port (8183) is released before {@code halt()}. Otherwise a subsequent run can
	 * hit a {@code BindException: Address already in use}.
	 */
	private static void shutdownAppServer() {
		try {
			var as = com.servoy.j2db.server.shared.ApplicationServerRegistry.get();
			if (as != null) {
				System.out.println("[skilltest] shutting down application server...");
				as.doNativeShutdown();
				Thread.sleep(2_000L);
			}
		} catch (Throwable t) {
			System.err.println("[skilltest] app server shutdown failed: " + t);
		}
	}
}

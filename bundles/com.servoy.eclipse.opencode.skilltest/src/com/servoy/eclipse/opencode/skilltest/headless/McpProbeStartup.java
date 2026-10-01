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
 * Runs the MCP probe once the Eclipse workbench has started, so we can see how
 * the MCP servers come up when a workbench is present - but without a bare
 * {@code -application}. Launched via the <b>workbench application</b>
 * ({@code org.eclipse.ui.ide.workbench}) so the workbench (and therefore core +
 * app server + MCP) starts, while the OS window can be hidden by running with a
 * headless display (Xvfb on CI / offscreen locally).
 * <p>
 * Only fires when {@code -Dservoy.mcpprobe.run=true}, so opening Servoy
 * Developer normally does not trigger a probe.
 * </p>
 */
public class McpProbeStartup implements IStartup {

	public static final String RUN_PROPERTY = "servoy.mcpprobe.run"; //$NON-NLS-1$

	@Override
	public void earlyStartup() {
		if (!Boolean.parseBoolean(System.getProperty(RUN_PROPERTY, "false"))) { //$NON-NLS-1$
			return;
		}
		Thread t = new Thread(() -> {
			int code = 0;
			try {
				code = new McpProbeApplication().probe();
			} catch (Throwable e) {
				System.err.println("[mcp-probe] failed: " + e);
			} finally {
				stopOpencodeServer();
				System.out.println("[mcp-probe] done, halting.");
				Runtime.getRuntime().halt(code);
			}
		}, "mcp-probe"); //$NON-NLS-1$
		t.setDaemon(false);
		t.start();
	}

	/**
	 * Stops the embedded opencode server (killing its node process tree) before we
	 * {@code halt()} the JVM. {@code halt} skips shutdown hooks and bundle
	 * {@code stop()} methods, so without this the opencode/node child is orphaned
	 * and keeps its port (4096) held, forcing the next run to a different port.
	 */
	private static void stopOpencodeServer() {
		try {
			com.servoy.eclipse.opencode.Activator opencode = com.servoy.eclipse.opencode.Activator.getInstance();
			if (opencode != null) {
				System.out.println("[mcp-probe] stopping opencode server...");
				opencode.stopServer();
				Thread.sleep(2_000L);
			}
		} catch (Throwable t) {
			System.err.println("[mcp-probe] opencode stop failed: " + t);
		}
	}
}

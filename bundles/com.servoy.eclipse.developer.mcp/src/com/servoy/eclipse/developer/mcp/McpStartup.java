/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * IStartup implementation â triggers early activation of this plugin and
 * bootstraps the {@link McpServerRegistry} via E4 dependency injection once
 * the workbench window is available.
 */
public class McpStartup implements IStartup
{
	@Override
	public void earlyStartup()
	{
		Display display = PlatformUI.getWorkbench().getDisplay();
		display.asyncExec(() -> waitForWorkbenchAndBootstrap(display));
	}

	private void waitForWorkbenchAndBootstrap(Display display)
	{
		if (PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null)
		{
			display.timerExec(500, () -> waitForWorkbenchAndBootstrap(display));
			return;
		}
		Activator.getDefault().make(McpServerRegistry.class);
		ServoyLog.logInfo("Servoy Developer MCP Server: E4 bootstrap complete.");
	}
}

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

import java.util.UUID;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Bundle activator for the Servoy Developer MCP Server plugin.
 * Extends AbstractUIPlugin to participate in E4 dependency injection.
 */
public class Activator extends AbstractUIPlugin {
	public static final String PLUGIN_ID = "com.servoy.eclipse.developer.mcp";

	/** Bearer token for this Eclipse session. Generated fresh on every startup. */
	public static final String SESSION_AUTH_TOKEN = UUID.randomUUID().toString();

	private static Activator instance;

	public static Activator getDefault() {
		return instance;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
		ServoyLog.logInfo("Servoy Developer MCP Server plugin started.");
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		instance = null;
		super.stop(context);
		ServoyLog.logInfo("Servoy Developer MCP Server plugin stopped.");
	}

	/**
	 * Creates an instance of the given class using E4 dependency injection.
	 * The instance is created in the workbench's Eclipse context, enabling
	 * full E4 lifecycle support (@PostConstruct, @PostWorkbenchClose, etc.).
	 */
	public <T> T make(Class<T> clazz) {
		IEclipseContext context = getEclipseContext();
		return ContextInjectionFactory.make(clazz, context);
	}

	public IEclipseContext getEclipseContext() {
		try {
			return PlatformUI.getWorkbench().getService(IEclipseContext.class);
		} catch (Exception e) {
			ServoyLog.logWarning("Workbench context not available, falling back to OSGi context.", e);
			return EclipseContextFactory.getServiceContext(getBundle().getBundleContext());
		}
	}
}

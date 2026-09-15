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

package com.servoy.eclipse.opencode.tomcat;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.apache.tomcat.starter.IServicesProvider;
import org.apache.tomcat.starter.ServletInstance;

import com.servoy.eclipse.opencode.Activator;
import com.servoy.eclipse.opencode.OpenCodeUtil;

/**
 * Registers the {@link OpencodeChatServlet} on the Servoy Developer's embedded
 * Tomcat via the {@code org.apache.tomcat.serviceprovider} extension point.
 * <p>
 * Only the root context {@code ""} is served, consistent with the other Servoy
 * providers. The servlet is handed to Tomcat as an already-constructed
 * {@link ServletInstance} so it can capture the opencode port supplier and the
 * project-path resolver at construction time (a container-instantiated
 * annotated servlet could not reach that bundle state).
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class ServicesProvider implements IServicesProvider {
	@Override
	public Set<ServletInstance> getServletInstances(String context) {
		// opencode chat UI is only served for the root context in developer.
		if ("".equals(context)) {
			HashSet<ServletInstance> set = new HashSet<>();
			set.add(new ServletInstance(new OpencodeChatServlet(ServicesProvider::resolvePort,
					ServicesProvider::isServerReady, ServicesProvider::waitForServer,
					OpenCodeUtil::getActiveProjectPath, ServicesProvider::resolveBundleResource),
					OpencodeChatServlet.BASE_PATH + "/*"));
			return set;
		}
		return null;
	}

	private static int resolvePort() {
		Activator activator = Activator.getInstance();
		return activator != null ? activator.getServerPort() : -1;
	}

	private static boolean isServerReady() {
		Activator activator = Activator.getInstance();
		return activator != null && activator.isServerReady();
	}

	private static boolean waitForServer(long timeoutMs) throws InterruptedException {
		Activator activator = Activator.getInstance();
		return activator != null && activator.waitForServer(timeoutMs);
	}

	private static URL resolveBundleResource(String bundlePath) {
		Activator activator = Activator.getInstance();
		if (activator == null || activator.getBundle() == null)
			return null;
		return activator.getBundle().getEntry(bundlePath);
	}
}

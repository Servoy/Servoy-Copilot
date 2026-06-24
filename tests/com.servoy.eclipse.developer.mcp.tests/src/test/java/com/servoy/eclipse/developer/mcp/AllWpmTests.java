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

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServerTest;
import com.servoy.eclipse.developer.mcp.services.WpmServiceTest;

/**
 * Focused JUnit 4 suite for the Servoy Package Manager (servoy-wpm) tooling only.
 * <p>
 * Lets the WPM changes be verified quickly without running the whole
 * {@link AllDeveloperMcpTests} suite. Run via the {@code WpmTests_mac.launch}
 * configuration (Run As - JUnit Plug-in Test).
 */
@RunWith(Suite.class)
@SuiteClasses({
	WpmServiceTest.class,
	ServoyWpmServerTest.class,
})
public class AllWpmTests
{
}

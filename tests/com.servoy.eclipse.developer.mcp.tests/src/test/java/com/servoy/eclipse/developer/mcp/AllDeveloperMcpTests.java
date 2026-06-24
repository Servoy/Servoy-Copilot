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

import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCacheTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyContextServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServerTest;
import com.servoy.eclipse.developer.mcp.servers.AnalyzeCodeToolTest;
import com.servoy.eclipse.developer.mcp.servers.GenerateTestCasesToolTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServerTest;
import com.servoy.eclipse.developer.mcp.servers.ShowFormInBrowserToolTest;
import com.servoy.eclipse.developer.mcp.services.FormSpecGeneratorTest;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunnerTest;
import com.servoy.eclipse.developer.mcp.services.WpmServiceTest;

/**
 * JUnit 4 test suite for all Servoy Developer MCP tests.
 * Run via: Run As - JUnit Plugin Test
 */
@RunWith(Suite.class)
@SuiteClasses({
	ServoyResourceCacheTest.class,
	ServoyContextServerTest.class,
	ServoyCoderServerTest.class,
	ServoyIdeServerTest.class,
	ServoyGitServerTest.class,
	ServoyTestingServerTest.class,
	FormSpecGeneratorTest.class,
	FormSpecRunnerTest.class,
	AnalyzeCodeToolTest.class,
	GenerateTestCasesToolTest.class,
	ServoyMediaServerTest.class,
	ShowFormInBrowserToolTest.class,
	ServoyWpmServerTest.class,
	WpmServiceTest.class,
})
public class AllDeveloperMcpTests
{
}

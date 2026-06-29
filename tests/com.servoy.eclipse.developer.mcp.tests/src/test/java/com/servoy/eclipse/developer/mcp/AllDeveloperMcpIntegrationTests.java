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

import com.servoy.eclipse.developer.mcp.integration.AddTestMethodIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CodeAnalysisIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CreateTestFileIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CypressConsoleUtilIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CypressFormTestingIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerGroupedTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerLayer4Test;
import com.servoy.eclipse.developer.mcp.integration.PersistDuplicateIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.RenamePersistIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyDevServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyIdeServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyWpmServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ShowFormInBrowserIntegrationTest;

/**
 * JUnit 4 test suite for all Servoy Developer MCP integration tests.
 * <p>
 * These tests require a running Servoy Application Server, a configured
 * Eclipse workspace with Servoy projects, and (for some tests) the NG client
 * runtime. They must be run as JUnit Plug-in Tests inside a PDE-launched
 * Eclipse instance, NOT as headless surefire tests.
 * <p>
 * Run via: Run As - JUnit Plug-in Test (using DeveloperMcpIntegrationTests.launch)
 */
@RunWith(Suite.class)
@SuiteClasses({
	AddTestMethodIntegrationTest.class,
	CodeAnalysisIntegrationTest.class,
	CreateTestFileIntegrationTest.class,
	CypressConsoleUtilIntegrationTest.class,
	CypressFormTestingIntegrationTest.class,
	JSUnitRunnerIntegrationTest.class,
	JSUnitRunnerGroupedTest.class,
	JSUnitRunnerLayer4Test.class,
	PersistDuplicateIntegrationTest.class,
	RenamePersistIntegrationTest.class,
	ServoyDevServerIntegrationTest.class,
	ServoyIdeServerIntegrationTest.class,
	ServoyWpmServerIntegrationTest.class,
	ShowFormInBrowserIntegrationTest.class,
})
public class AllDeveloperMcpIntegrationTests
{
}

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
import com.servoy.eclipse.developer.mcp.integration.CodeContextServiceIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ContextServerHistoryIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CreateArtifactsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CreateSolutionIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CreateTestFileIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CypressConsoleUtilIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.CypressFormTestingIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.DatabaseToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.DocumentationToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.E2EToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.FormNavigationGraphServiceIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.GetNavigationPathIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerGroupedTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.JSUnitRunnerLayer4Test;
import com.servoy.eclipse.developer.mcp.integration.MenuToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.PersistDuplicateIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.RenamePersistIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.RunTestMethodIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ScriptContextServiceIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.SecurityToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyCoderServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyDevServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyGitServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyI18nServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyIdeServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyIdeServerReadIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyMediaServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyIdeServerWorkspaceIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoySolutionServiceIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ServoyWpmServerIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ShowFormInBrowserIntegrationTest;
import com.servoy.eclipse.developer.mcp.integration.ValidationToolsIntegrationTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServerTest;

/**
 * JUnit 4 test suite for all Servoy Developer MCP integration tests.
 * <p>
 * These tests require a running Servoy Application Server, a configured Eclipse
 * workspace with Servoy projects, and (for some tests) the NG client runtime.
 * They must be run as JUnit Plug-in Tests inside a PDE-launched Eclipse
 * instance, NOT as headless surefire tests.
 * <p>
 * Run via: Run As - JUnit Plug-in Test (using
 * DeveloperMcpIntegrationTests.launch)
 */
@RunWith(Suite.class)
@SuiteClasses({ AddTestMethodIntegrationTest.class, CodeAnalysisIntegrationTest.class,
		CodeContextServiceIntegrationTest.class, ContextServerHistoryIntegrationTest.class,
		CreateArtifactsIntegrationTest.class,
		CreateSolutionIntegrationTest.class, CreateTestFileIntegrationTest.class,
		CypressConsoleUtilIntegrationTest.class, CypressFormTestingIntegrationTest.class,
		DatabaseToolsIntegrationTest.class, DocumentationToolsIntegrationTest.class, JSUnitRunnerIntegrationTest.class,
		JSUnitRunnerGroupedTest.class, JSUnitRunnerLayer4Test.class, PersistDuplicateIntegrationTest.class,
		RenamePersistIntegrationTest.class, RunTestMethodIntegrationTest.class, SecurityToolsIntegrationTest.class,
		ServoyCoderServerIntegrationTest.class, ServoyDevServerIntegrationTest.class,
		ServoyGitServerIntegrationTest.class, ServoyI18nServerIntegrationTest.class,
		ServoyIdeServerIntegrationTest.class, ServoyIdeServerWorkspaceIntegrationTest.class,
		ServoyMediaServerIntegrationTest.class, ServoySolutionServiceIntegrationTest.class,
		ServoyWpmServerIntegrationTest.class,
		ShowFormInBrowserIntegrationTest.class, ServoyCoderServerTest.class,
		ServoyDevServerTest.class, ServoyIdeServerTest.class, McpServerFactoryTest.class, McpServerBuiltinsTest.class,
		ValidationToolsIntegrationTest.class, MenuToolsIntegrationTest.class, ServoyIdeServerReadIntegrationTest.class, E2EToolsIntegrationTest.class, ScriptContextServiceIntegrationTest.class, FormNavigationGraphServiceIntegrationTest.class, GetNavigationPathIntegrationTest.class, })
public class AllDeveloperMcpIntegrationTests {
}

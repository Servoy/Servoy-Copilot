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

import com.servoy.eclipse.developer.mcp.auth.BearerTokenAuthenticationFilterTest;
import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCacheTest;
import com.servoy.eclipse.developer.mcp.servers.AnalyzeCodeToolTest;
import com.servoy.eclipse.developer.mcp.servers.GenerateTestCasesToolTest;
import com.servoy.eclipse.developer.mcp.servers.MemoryServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyContextServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServerTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServerTest;
import com.servoy.eclipse.developer.mcp.servers.ShowFormInBrowserToolTest;
import com.servoy.eclipse.developer.mcp.servers.TimeServerTest;
import com.servoy.eclipse.developer.mcp.services.DocumentationValidatorServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormatValidatorServiceTest;
import com.servoy.eclipse.developer.mcp.services.JSUnitCoverageServiceTest;
import com.servoy.eclipse.developer.mcp.services.PersistRenameServiceTest;
import com.servoy.eclipse.developer.mcp.services.ResolvedElementsProcessorTest;
import com.servoy.eclipse.developer.mcp.services.TestFileServiceReflectionTest;
import com.servoy.eclipse.developer.mcp.services.WpmServiceTest;

/**
 * JUnit 4 test suite for ALL plain JUnit tests in the Servoy Developer MCP
 * test project. These tests do NOT require a running Eclipse workbench or
 * Servoy app server — they are pure unit tests using reflection and mocking.
 * <p>
 * Run via: Run As &gt; JUnit Test (NOT JUnit Plug-in Test).
 * <p>
 * For integration tests requiring Eclipse + Servoy runtime, see
 * {@link AllDeveloperMcpIntegrationTests}.
 * <p>
 * Note: The following test classes are excluded from this JUnit 4 suite:
 * <ul>
 * <li>JUnit 5 tests (run by package with JUnit 5 runner): actions.*, CypressTestDiscoveryServiceTest,
 *     FormatValidatorServiceTest</li>
 * <li>Need Eclipse platform (in AllDeveloperMcpIntegrationTests): ServoyDevServerTest, McpServerFactoryTest</li>
 * <li>Package-private visibility (run individually): ServoyI18nServerTest,
 *     FormNavigationGraphServiceTest, FormPreviewServiceTest, GitServiceDiffTest,
 *     NavigationGraphTest, PersistDuplicateServiceTest</li>
 * </ul>
 */
@RunWith(Suite.class)
@SuiteClasses({
	// root package
	ToolExecutorTest.class,
	// auth
	BearerTokenAuthenticationFilterTest.class,
	// cache
	ServoyResourceCacheTest.class,
	// servers
	AnalyzeCodeToolTest.class,
	GenerateTestCasesToolTest.class,
	MemoryServerTest.class,
	ServoyCoderServerTest.class,
	ServoyContextServerTest.class,
	ServoyGitServerTest.class,
	ServoyMediaServerTest.class,
	ServoyTestingServerTest.class,
	ServoyWpmServerTest.class,
	ShowFormInBrowserToolTest.class,
	TimeServerTest.class,
	// services
	DocumentationValidatorServiceTest.class,
	JSUnitCoverageServiceTest.class,
	PersistRenameServiceTest.class,
	ResolvedElementsProcessorTest.class,
	TestFileServiceReflectionTest.class,
	WpmServiceTest.class,
})
public class AllDeveloperMcpTests {
}

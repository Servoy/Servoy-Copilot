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

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

import com.servoy.eclipse.developer.mcp.actions.CypressConsoleUtilTest;
import com.servoy.eclipse.developer.mcp.actions.CypressEditorInputPropertyTesterTest;
import com.servoy.eclipse.developer.mcp.actions.CypressTestAdapterFactoryTest;
import com.servoy.eclipse.developer.mcp.actions.CypressTestPropertyTesterTest;
import com.servoy.eclipse.developer.mcp.actions.RunAllCypressFormTestsHandlerTest;
import com.servoy.eclipse.developer.mcp.actions.RunCypressFormTestHandlerTest;
import com.servoy.eclipse.developer.mcp.headless.CypressFormTestArgumentChestTest;
import com.servoy.eclipse.developer.mcp.headless.JUnitXmlReporterTest;
import com.servoy.eclipse.developer.mcp.services.RunCypressFormTestsLauncherTest;
import com.servoy.eclipse.developer.mcp.servers.ServoyI18nServerTest;
import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormPreviewServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormatValidatorServiceTest;
import com.servoy.eclipse.developer.mcp.services.NavigationGraphTest;
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateServiceTest;

/**
 * JUnit 5 (Jupiter) platform suite for the plain-unit test classes that use the
 * JUnit Jupiter API and therefore cannot be referenced from the JUnit 4
 * {@code @RunWith(Suite.class)} aggregate in {@link AllDeveloperMcpTests}.
 * <p>
 * Before SVY-21187 these classes were committed but not referenced by any
 * aggregate suite, so a suite-driven CI run silently skipped them. They are all
 * pure unit tests (reflection, regex, JGit tempdirs, filesystem tempdirs) with
 * no dependency on a running Eclipse workbench or Servoy application server:
 * <ul>
 * <li>{@code services.*}: CypressTestDiscoveryServiceTest,
 * FormatValidatorServiceTest, FormNavigationGraphServiceTest,
 * FormPreviewServiceTest, NavigationGraphTest,
 * PersistDuplicateServiceTest</li>
 * <li>{@code servers.*}: ServoyI18nServerTest</li>
 * <li>{@code headless.*}: CypressFormTestArgumentChestTest,
 * JUnitXmlReporterTest</li>
 * <li>{@code actions.*}: CypressConsoleUtilTest,
 * CypressEditorInputPropertyTesterTest, CypressTestAdapterFactoryTest,
 * CypressTestPropertyTesterTest, RunAllCypressFormTestsHandlerTest,
 * RunCypressFormTestHandlerTest</li>
 * </ul>
 * The NavigationGraphTest / FormNavigationGraphServiceTest tests that touch
 * {@code ServoyTestingServer} guard against a missing Servoy model by catching
 * {@code Throwable}, so they remain safe to run without the workbench.
 * <p>
 * {@code GitServiceDiffTest} is deliberately NOT included: it relies on the
 * JUnit Jupiter {@code @TempDir} extension, and the {@code @TempDir} runtime
 * resolved in this test fragment is incompatible with the JUnit version used
 * here ({@code NoSuchMethodError: TempDir.deletionStrategy()}). This affects the
 * class whether run standalone or via a suite, so it is a pre-existing
 * environment/classpath issue independent of SVY-21187; adding it to the suite
 * would only propagate that failure. It is left out until the @TempDir
 * dependency mismatch is resolved.
 * <p>
 * Run via: Run As &gt; JUnit Test (uses the JUnit Platform / Jupiter engine).
 */
@Suite
@SuiteDisplayName("Developer MCP - Jupiter unit tests")
@SelectClasses({
		// services
		CypressTestDiscoveryServiceTest.class,
		FormatValidatorServiceTest.class,
		FormNavigationGraphServiceTest.class,
		FormPreviewServiceTest.class,
		NavigationGraphTest.class,
		PersistDuplicateServiceTest.class,
		// servers
		ServoyI18nServerTest.class,
		// headless
		CypressFormTestArgumentChestTest.class,
		JUnitXmlReporterTest.class,
		RunCypressFormTestsLauncherTest.class,
		// actions
		CypressConsoleUtilTest.class,
		CypressEditorInputPropertyTesterTest.class,
		CypressTestAdapterFactoryTest.class,
		CypressTestPropertyTesterTest.class,
		RunAllCypressFormTestsHandlerTest.class,
		RunCypressFormTestHandlerTest.class,
})
public class AllDeveloperMcpJupiterUnitTests {
}

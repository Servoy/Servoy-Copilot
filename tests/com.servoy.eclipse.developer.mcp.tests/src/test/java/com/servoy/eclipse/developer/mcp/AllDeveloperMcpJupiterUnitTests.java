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

import com.servoy.eclipse.developer.mcp.servers.ServoyI18nServerTest;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormPreviewServiceTest;
import com.servoy.eclipse.developer.mcp.services.FormatValidatorServiceTest;
import com.servoy.eclipse.developer.mcp.services.NavigationGraphTest;
import com.servoy.eclipse.developer.mcp.services.PersistDuplicateServiceTest;

@Suite
@SuiteDisplayName("Developer MCP - Jupiter unit tests")
@SelectClasses({
		// services
		FormatValidatorServiceTest.class,
		FormNavigationGraphServiceTest.class,
		FormPreviewServiceTest.class,
		NavigationGraphTest.class,
		PersistDuplicateServiceTest.class,
		// servers
		ServoyI18nServerTest.class,
})
public class AllDeveloperMcpJupiterUnitTests {
}

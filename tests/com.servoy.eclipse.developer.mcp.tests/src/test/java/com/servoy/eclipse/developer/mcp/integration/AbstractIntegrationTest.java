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
package com.servoy.eclipse.developer.mcp.integration;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.servoy.eclipse.ngclient.ui.NodeFolderCreatorJob;

/**
 * Base class for integration tests that activate Servoy solutions.
 * <p>
 * Uses JUnit 4 {@code @BeforeClass}/{@code @AfterClass} because the integration
 * tests in this suite use JUnit 4. If this class is ever migrated to JUnit 5
 * {@code @BeforeAll}/{@code @AfterAll}, the lifecycle methods must remain
 * {@code static} and {@code public} — and
 * {@code AbstractIntegrationTestBaseTest} relies on calling them directly.
 * </p>
 * <p>
 * Disables {@link NodeFolderCreatorJob} before the test class runs and restores
 * the default (enabled) state in teardown. Test classes that genuinely need the
 * node copy (e.g. {@code CypressFormTestingIntegrationTest}) should override
 * {@code disableNodeFolderCreatorJob()} and call
 * {@link NodeFolderCreatorJob#setDisabled(boolean) setDisabled(false)} instead.
 * </p>
 */
public abstract class AbstractIntegrationTest extends TestUtilitiesClass {
	
	public AbstractIntegrationTest(String testSolutionName, String servoyResourcesProjectName) {
		super(testSolutionName, servoyResourcesProjectName);
	}

	/**
	 * Disables the node folder copy/npm cycle before any test in this class runs.
	 * Override in subclasses that require the node folder to be set up.
	 */
	@BeforeClass
	public static void disableNodeFolderCreatorJob() {
		NodeFolderCreatorJob.setDisabled(true);
	}

	/**
	 * Restores the default (enabled) state after all tests in this class have run,
	 * so subsequent test classes are unaffected.
	 */
	@AfterClass
	public static void restoreNodeFolderCreatorJob() {
		NodeFolderCreatorJob.setDisabled(false);
	}
}

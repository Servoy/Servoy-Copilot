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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.ngclient.ui.NodeFolderCreatorJob;

/**
 * Unit tests for {@link AbstractIntegrationTest}'s static lifecycle hooks
 * (SVY-21284). These tests call the public static methods directly — the same
 * methods that JUnit 4's {@code @BeforeClass} / {@code @AfterClass} annotations
 * invoke at suite time — so no OSGi runtime or workbench is required.
 */
public class AbstractIntegrationTestBaseTest
{
	/** Original toggle state, captured before each test and restored after. */
	private boolean stateBeforeTest;

	@BeforeEach
	void captureState()
	{
		stateBeforeTest = NodeFolderCreatorJob.isDisabled();
	}

	@AfterEach
	void restoreState()
	{
		NodeFolderCreatorJob.setDisabled(stateBeforeTest);
	}

	@Nested
	class DisableHook
	{
		@Test
		@org.junit.jupiter.api.DisplayName("disableNodeFolderCreatorJob() sets NodeFolderCreatorJob.isDisabled() to true")
		void disableHookSetsDisabledTrue()
		{
			NodeFolderCreatorJob.setDisabled(false); // start from known state
			AbstractIntegrationTest.disableNodeFolderCreatorJob();
			assertTrue(NodeFolderCreatorJob.isDisabled(),
				"After disableNodeFolderCreatorJob(), NodeFolderCreatorJob must report disabled=true");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("disableNodeFolderCreatorJob() is idempotent when already disabled")
		void disableHookIsIdempotent()
		{
			NodeFolderCreatorJob.setDisabled(true);
			AbstractIntegrationTest.disableNodeFolderCreatorJob();
			assertTrue(NodeFolderCreatorJob.isDisabled(),
				"Repeated disable calls must leave disabled=true");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("disableNodeFolderCreatorJob() does not throw")
		void disableHookDoesNotThrow()
		{
			assertDoesNotThrow(AbstractIntegrationTest::disableNodeFolderCreatorJob);
		}
	}

	@Nested
	class RestoreHook
	{
		@Test
		@org.junit.jupiter.api.DisplayName("restoreNodeFolderCreatorJob() sets NodeFolderCreatorJob.isDisabled() to false")
		void restoreHookSetsDisabledFalse()
		{
			NodeFolderCreatorJob.setDisabled(true); // start from known state
			AbstractIntegrationTest.restoreNodeFolderCreatorJob();
			assertFalse(NodeFolderCreatorJob.isDisabled(),
				"After restoreNodeFolderCreatorJob(), NodeFolderCreatorJob must report disabled=false");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("restoreNodeFolderCreatorJob() is idempotent when already enabled")
		void restoreHookIsIdempotent()
		{
			NodeFolderCreatorJob.setDisabled(false);
			AbstractIntegrationTest.restoreNodeFolderCreatorJob();
			assertFalse(NodeFolderCreatorJob.isDisabled(),
				"Repeated restore calls must leave disabled=false");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("restoreNodeFolderCreatorJob() does not throw")
		void restoreHookDoesNotThrow()
		{
			assertDoesNotThrow(AbstractIntegrationTest::restoreNodeFolderCreatorJob);
		}
	}

	@Nested
	class LifecyclePair
	{
		@Test
		@org.junit.jupiter.api.DisplayName("disable then restore leaves the toggle at false")
		void disableThenRestoreLeavesEnabled()
		{
			NodeFolderCreatorJob.setDisabled(false);

			AbstractIntegrationTest.disableNodeFolderCreatorJob();
			assertTrue(NodeFolderCreatorJob.isDisabled(), "Must be disabled after setup hook");

			AbstractIntegrationTest.restoreNodeFolderCreatorJob();
			assertFalse(NodeFolderCreatorJob.isDisabled(), "Must be re-enabled after teardown hook");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("multiple disable/restore cycles are consistent")
		void multipleLifecycleCyclesAreConsistent()
		{
			for (int i = 0; i < 5; i++)
			{
				AbstractIntegrationTest.disableNodeFolderCreatorJob();
				assertTrue(NodeFolderCreatorJob.isDisabled(), "Cycle " + i + ": must be disabled after setup");

				AbstractIntegrationTest.restoreNodeFolderCreatorJob();
				assertFalse(NodeFolderCreatorJob.isDisabled(), "Cycle " + i + ": must be enabled after teardown");
			}
		}
	}

	@Nested
	class DefaultValueContract
	{
		@Test
		@org.junit.jupiter.api.DisplayName("NodeFolderCreatorJob default (false) is unaffected by non-test code paths")
		void defaultValueIsFalseAfterRestore()
		{
			// Verifies the spec requirement: "The default disabled field value remains
			// false (enabled) so non-test Servoy Developer launches are unaffected."
			AbstractIntegrationTest.restoreNodeFolderCreatorJob();
			assertFalse(NodeFolderCreatorJob.isDisabled(),
				"After restoreNodeFolderCreatorJob(), the default must be false (enabled)");
		}
	}
}

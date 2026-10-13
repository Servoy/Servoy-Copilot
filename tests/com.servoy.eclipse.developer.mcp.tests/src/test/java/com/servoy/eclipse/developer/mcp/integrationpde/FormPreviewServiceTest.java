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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.services.FormPreviewService;

public class FormPreviewServiceTest extends TestUtilitiesClassCopy {

	private FormPreviewService service;

	protected FormPreviewServiceTest() {
		super(null, null);
	}
	
	@BeforeEach
	void setUp() throws Exception {
		service = new FormPreviewService();
		
		waitForAppServer();
		ensureActiveProject(); // ensure that active project is null - see constructor
	}

	// --- Input validation: showFormInBrowser (SVY-21195 AC1) ---

	@Nested
	class ShowFormInBrowserInputValidation {

		/**
		 * AC1: showFormInBrowser must NOT block when marker errors are present.
		 * Without an active project the call fails at the project-lookup step — not
		 * at a marker gate. This verifies that no hard marker gate fires before the
		 * project check, i.e. the implementation does not early-return an error due
		 * to markers before it even tries to obtain the project.
		 */
		@Test
		@DisplayName("showFormInBrowser fails at project lookup not at a marker gate (AC1)")
		void testShowFormInBrowser_noActiveProject_failsAtProjectLookupNotMarkerGate() {
			// No Servoy model / active project in a plain unit-test JVM
			String result = service.showFormInBrowser("anyForm", false);
			assertNotNull(result);
			// Must NOT contain marker-gate error strings
			assertFalse(result.contains("has problem markers"),
				"showFormInBrowser must not return a hard marker-gate error (AC1)");
			assertFalse(result.contains("has property type mismatches"),
				"showFormInBrowser must not return a hard property-validation error (AC1)");
			// Should return an Error: but only due to missing project/server, not markers
			assertTrue(result.startsWith("Error:"),
				"showFormInBrowser should return Error: when no project is active");
		}
	}

}

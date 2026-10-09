/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsUnitVerifier#scopeForSolutionPath(String)} - mapping a
 * solution-relative test file path to the JSUnit scope the runner takes (SVY-21366).
 */
class ScopeForSolutionPathTest {

	@Test
	@DisplayName("a root-level <scope>.js is the global scope name")
	void rootScope() {
		assertEquals("globals", JsUnitVerifier.scopeForSolutionPath("globals.js"));
		assertEquals("skilltest_verify", JsUnitVerifier.scopeForSolutionPath("skilltest_verify.js"));
	}

	@Test
	@DisplayName("forms/<form>.js is the form name (slash or backslash, with leading slash)")
	void formScript() {
		assertEquals("customers", JsUnitVerifier.scopeForSolutionPath("forms/customers.js"));
		assertEquals("customers", JsUnitVerifier.scopeForSolutionPath("forms\\customers.js"));
		assertEquals("customers", JsUnitVerifier.scopeForSolutionPath("/forms/customers.js"));
	}

	@Test
	@DisplayName("case-insensitive on the forms segment and the .js extension")
	void caseInsensitive() {
		assertEquals("c", JsUnitVerifier.scopeForSolutionPath("Forms/c.JS"));
	}

	@Test
	@DisplayName("non-js, blank, null, and unrecognized nesting map to null")
	void unmappable() {
		assertNull(JsUnitVerifier.scopeForSolutionPath(null));
		assertNull(JsUnitVerifier.scopeForSolutionPath(""));
		assertNull(JsUnitVerifier.scopeForSolutionPath("forms/customers.frm"));
		assertNull(JsUnitVerifier.scopeForSolutionPath("relations/x.js")); // not a scope/form location
		assertNull(JsUnitVerifier.scopeForSolutionPath("a/b/c.js"));
	}
}

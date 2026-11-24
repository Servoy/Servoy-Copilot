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

package com.servoy.eclipse.opencode.tests.plainjunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.opencode.OpenCodeUtil;

class OpenCodeUtilTest {
	@Test
	@DisplayName("normalizeSolutionName returns null for null")
	void nullName() {
		assertNull(OpenCodeUtil.normalizeSolutionName(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "   ", "\t\n" })
	@DisplayName("normalizeSolutionName returns null for blank names")
	void blankName(String name) {
		assertNull(OpenCodeUtil.normalizeSolutionName(name));
	}

	@Test
	@DisplayName("normalizeSolutionName keeps a normal name")
	void normalName() {
		assertEquals("crm_app", OpenCodeUtil.normalizeSolutionName("crm_app"));
	}

	@Test
	@DisplayName("normalizeSolutionName trims surrounding whitespace only")
	void trims() {
		assertEquals("my app", OpenCodeUtil.normalizeSolutionName("  my app \t"));
	}
}

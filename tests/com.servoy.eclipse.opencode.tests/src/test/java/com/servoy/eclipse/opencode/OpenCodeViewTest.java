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

package com.servoy.eclipse.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OpenCodeViewTest {
	@Nested
	class ParseReportedSessionId {
		@Test
		@DisplayName("returns null for null arguments")
		void nullArgs() {
			assertNull(OpenCodeView.parseReportedSessionId(null));
		}

		@Test
		@DisplayName("returns null for empty arguments array")
		void emptyArgs() {
			assertNull(OpenCodeView.parseReportedSessionId(new Object[0]));
		}

		@Test
		@DisplayName("returns null when first argument is not a String")
		void nonStringArg() {
			assertNull(OpenCodeView.parseReportedSessionId(new Object[] { Integer.valueOf(42) }));
		}

		@Test
		@DisplayName("returns null when first argument is an empty string")
		void emptyStringArg() {
			assertNull(OpenCodeView.parseReportedSessionId(new Object[] { "" }));
		}

		@Test
		@DisplayName("returns the session ID for a valid string argument")
		void validSessionId() {
			assertEquals("session-abc-123", OpenCodeView.parseReportedSessionId(new Object[] { "session-abc-123" }));
		}

		@Test
		@DisplayName("only considers the first element when multiple arguments are present")
		void multipleArgs() {
			assertEquals("first", OpenCodeView.parseReportedSessionId(new Object[] { "first", "second" }));
		}

		@Test
		@DisplayName("returns null when first element is null")
		void nullFirstElement() {
			assertNull(OpenCodeView.parseReportedSessionId(new Object[] { null }));
		}
	}
}

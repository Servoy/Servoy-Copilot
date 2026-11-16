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
package com.servoy.eclipse.developer.mcp.plainjunit.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.dto.IdentifierContext;
import com.servoy.eclipse.developer.mcp.dto.IdentifierContext.IdentifierKind;

/**
 * Plain-JUnit unit tests for the pure DTO {@link IdentifierContext}.
 * <p>
 * Every member is reached through its public API - the static
 * {@link IdentifierContext#create(String, String, String, IdentifierKind)}
 * factory, the four getters, {@link IdentifierContext#hasDocumentation()},
 * {@link IdentifierContext#toFormattedString()},
 * {@link IdentifierContext#toFormattedXML()} and
 * {@link IdentifierContext#toString()} - so <b>no production change, no seam
 * and no mock are needed</b>. The class is immutable and has no workbench
 * dependency.
 * </p>
 *
 * @since 2026.06
 */
class IdentifierContextTest {

	@Nested
	@DisplayName("create() null-coalescing factory")
	class Create {

		@Test
		@DisplayName("keeps every supplied non-null value")
		void keepsSuppliedValues() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "doc text",
				IdentifierKind.SERVOY_API);
			assertEquals("foundset", ctx.getName());
			assertEquals("JSFoundSet", ctx.getTypeName());
			assertEquals("doc text", ctx.getDocumentation());
			assertSame(IdentifierKind.SERVOY_API, ctx.getKind());
		}

		@Test
		@DisplayName("null name becomes empty string")
		void nullNameBecomesEmpty() {
			assertEquals("", IdentifierContext.create(null, "T", "d", IdentifierKind.WEB_COMPONENT).getName());
		}

		@Test
		@DisplayName("null typeName becomes \"Unknown\"")
		void nullTypeBecomesUnknown() {
			assertEquals("Unknown", IdentifierContext.create("n", null, "d", IdentifierKind.WEB_SERVICE).getTypeName());
		}

		@Test
		@DisplayName("null documentation becomes empty string")
		void nullDocBecomesEmpty() {
			assertEquals("", IdentifierContext.create("n", "T", null, IdentifierKind.SOLUTION_FUNCTION).getDocumentation());
		}

		@Test
		@DisplayName("null kind becomes UNKNOWN")
		void nullKindBecomesUnknown() {
			assertSame(IdentifierKind.UNKNOWN, IdentifierContext.create("n", "T", "d", null).getKind());
		}

		@Test
		@DisplayName("all nulls coalesce together")
		void allNullsCoalesce() {
			IdentifierContext ctx = IdentifierContext.create(null, null, null, null);
			assertEquals("", ctx.getName());
			assertEquals("Unknown", ctx.getTypeName());
			assertEquals("", ctx.getDocumentation());
			assertSame(IdentifierKind.UNKNOWN, ctx.getKind());
		}
	}

	@Nested
	@DisplayName("hasDocumentation()")
	class HasDocumentation {

		@Test
		@DisplayName("true for non-blank documentation")
		void trueForNonBlank() {
			assertTrue(IdentifierContext.create("n", "T", "hello", IdentifierKind.SERVOY_API).hasDocumentation());
		}

		@Test
		@DisplayName("false for null documentation (coalesced to empty)")
		void falseForNull() {
			assertFalse(IdentifierContext.create("n", "T", null, IdentifierKind.SERVOY_API).hasDocumentation());
		}

		@Test
		@DisplayName("false for empty documentation")
		void falseForEmpty() {
			assertFalse(IdentifierContext.create("n", "T", "", IdentifierKind.SERVOY_API).hasDocumentation());
		}

		@Test
		@DisplayName("false for whitespace-only documentation")
		void falseForBlank() {
			assertFalse(IdentifierContext.create("n", "T", "   \t\n ", IdentifierKind.SERVOY_API).hasDocumentation());
		}
	}

	@Nested
	@DisplayName("toFormattedString()")
	class FormattedString {

		@Test
		@DisplayName("with documentation appends it on a new line")
		void withDoc() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "the active foundset",
				IdentifierKind.SERVOY_API);
			assertEquals("foundset: JSFoundSet\nthe active foundset", ctx.toFormattedString());
		}

		@Test
		@DisplayName("without documentation is just name: type")
		void withoutDoc() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "", IdentifierKind.SERVOY_API);
			assertEquals("foundset: JSFoundSet", ctx.toFormattedString());
		}

		@Test
		@DisplayName("blank documentation is treated as no documentation")
		void blankDocOmitted() {
			IdentifierContext ctx = IdentifierContext.create("n", "T", "   ", IdentifierKind.UNKNOWN);
			assertEquals("n: T", ctx.toFormattedString());
		}
	}

	@Nested
	@DisplayName("toFormattedXML()")
	class FormattedXml {

		@Test
		@DisplayName("with documentation adds a description element")
		void withDoc() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "doc", IdentifierKind.SERVOY_API);
			assertEquals("<type>foundset: JSFoundSet</type>\n<description>doc</description>\n", ctx.toFormattedXML());
		}

		@Test
		@DisplayName("without documentation emits only the type element")
		void withoutDoc() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "", IdentifierKind.SERVOY_API);
			assertEquals("<type>foundset: JSFoundSet</type>\n", ctx.toFormattedXML());
		}

		@Test
		@DisplayName("special characters are emitted verbatim (no XML escaping)")
		void noEscaping() {
			IdentifierContext ctx = IdentifierContext.create("a<b", "T&T", "x > y", IdentifierKind.UNKNOWN);
			assertEquals("<type>a<b: T&T</type>\n<description>x > y</description>\n", ctx.toFormattedXML());
		}
	}

	@Nested
	@DisplayName("toString()")
	class ToString {

		@Test
		@DisplayName("renders name, type and kind")
		void rendersFields() {
			IdentifierContext ctx = IdentifierContext.create("foundset", "JSFoundSet", "doc", IdentifierKind.WEB_COMPONENT);
			assertEquals("IdentifierContext{name='foundset', type='JSFoundSet', kind=WEB_COMPONENT}", ctx.toString());
		}
	}

	@Test
	@DisplayName("every IdentifierKind round-trips through getKind()")
	void everyKind() {
		for (IdentifierKind kind : IdentifierKind.values()) {
			assertSame(kind, IdentifierContext.create("n", "T", "d", kind).getKind());
		}
	}
}

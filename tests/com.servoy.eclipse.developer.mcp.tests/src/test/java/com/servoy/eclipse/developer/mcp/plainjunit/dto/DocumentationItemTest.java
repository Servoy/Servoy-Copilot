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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.dto.DocumentationItem;

/**
 * Plain-JUnit unit tests for the pure record {@link DocumentationItem}.
 * <p>
 * The compact canonical constructor carries all the validation, and
 * {@link DocumentationItem#isInsert()} is a pure predicate over the record
 * components, so every branch is reachable by directly constructing the record
 * - <b>no production change, no seam and no mock are needed</b>. The record has
 * no workbench dependency.
 * </p>
 *
 * @since 2026.06
 */
class DocumentationItemTest {

	private static final String JSDOC = "/** doc */";

	@Nested
	@DisplayName("canonical constructor - happy path")
	class Construction {

		@Test
		@DisplayName("keeps every supplied component")
		void keepsComponents() {
			DocumentationItem item = new DocumentationItem(3, 7, "startSent", "endSent", JSDOC);
			assertEquals(3, item.startLine());
			assertEquals(7, item.endLine());
			assertEquals("startSent", item.startSentence());
			assertEquals("endSent", item.endSentence());
			assertEquals(JSDOC, item.jsdoc());
		}

		@Test
		@DisplayName("startLine == endLine is allowed")
		void equalLinesAllowed() {
			DocumentationItem item = new DocumentationItem(5, 5, "", "", JSDOC);
			assertEquals(5, item.startLine());
			assertEquals(5, item.endLine());
		}

		@Test
		@DisplayName("startLine == 0 is allowed (not negative)")
		void zeroStartLineAllowed() {
			assertEquals(0, new DocumentationItem(0, 0, "", "", JSDOC).startLine());
		}

		@Test
		@DisplayName("multi-line JSDoc with surrounding whitespace is accepted")
		void trimmedMultilineJsdocAccepted() {
			DocumentationItem item = new DocumentationItem(1, 2, "a", "b", "\n  /**\n * x\n */  \n");
			assertTrue(item.jsdoc().contains("/**"));
		}
	}

	@Nested
	@DisplayName("canonical constructor - validation")
	class Validation {

		@Test
		@DisplayName("negative startLine is rejected")
		void negativeStartLine() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(-1, 5, "", "", JSDOC));
		}

		@Test
		@DisplayName("endLine < startLine is rejected")
		void endBeforeStart() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(5, 4, "", "", JSDOC));
		}

		@Test
		@DisplayName("null startSentence is rejected")
		void nullStartSentence() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, null, "", JSDOC));
		}

		@Test
		@DisplayName("null endSentence is rejected")
		void nullEndSentence() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", null, JSDOC));
		}

		@Test
		@DisplayName("null jsdoc is rejected")
		void nullJsdoc() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", "", null));
		}

		@Test
		@DisplayName("blank jsdoc is rejected")
		void blankJsdoc() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", "", "   "));
		}

		@Test
		@DisplayName("jsdoc not starting with /** is rejected")
		void jsdocWrongStart() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", "", "doc */"));
		}

		@Test
		@DisplayName("jsdoc not ending with */ is rejected")
		void jsdocWrongEnd() {
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", "", "/** doc"));
		}

		@Test
		@DisplayName("overly long bad jsdoc is still rejected (truncated message branch)")
		void jsdocWrongLong() {
			String longBad = "/** " + "x".repeat(200); // starts with /** but does not end with */
			assertThrows(IllegalArgumentException.class,
				() -> new DocumentationItem(1, 2, "", "", longBad));
		}
	}

	@Nested
	@DisplayName("isInsert()")
	class IsInsert {

		@Test
		@DisplayName("true when single line and both sentences empty")
		void insertCase() {
			assertTrue(new DocumentationItem(4, 4, "", "", JSDOC).isInsert());
		}

		@Test
		@DisplayName("false when line range spans multiple lines")
		void falseWhenRange() {
			assertFalse(new DocumentationItem(4, 6, "", "", JSDOC).isInsert());
		}

		@Test
		@DisplayName("false when startSentence is non-empty")
		void falseWhenStartSentence() {
			assertFalse(new DocumentationItem(4, 4, "s", "", JSDOC).isInsert());
		}

		@Test
		@DisplayName("false when endSentence is non-empty")
		void falseWhenEndSentence() {
			assertFalse(new DocumentationItem(4, 4, "", "e", JSDOC).isInsert());
		}
	}
}

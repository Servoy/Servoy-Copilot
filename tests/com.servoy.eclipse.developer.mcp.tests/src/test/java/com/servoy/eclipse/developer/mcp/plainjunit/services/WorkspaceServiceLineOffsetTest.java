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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.WorkspaceService;

/**
 * Tests the offset-to-line mapping behind the search tools' reported line numbers - the fix for the
 * CRLF line drift named in AssistAI's {@code a6dc32d} commit message (SVY-21303).
 * <p>
 * The old implementation rebuilt each line's start offset as {@code line.length() + 1}, which
 * assumes a one-character delimiter. In a CRLF file every line is one character short of that, so
 * the computed offsets fell progressively behind the real ones and the reported line number drifted
 * - roughly one line per 45 in normally-sized source, which is exactly the kind of error that looks
 * like a correct answer. {@code lineStartOffsets} measures the real content instead, so these tests
 * assert the mapping against a CRLF file long enough for the old arithmetic to be visibly wrong.
 * </p>
 */
public class WorkspaceServiceLineOffsetTest
{
	@Nested
	@DisplayName("splitLines")
	class SplitLines
	{
		@Test
		@DisplayName("splits on LF, CRLF and a lone CR alike")
		void splitsEveryDelimiter()
		{
			assertAll(
				() -> assertEquals(List.of("a", "b", "c"), WorkspaceService.splitLines("a\nb\nc")),
				() -> assertEquals(List.of("a", "b", "c"), WorkspaceService.splitLines("a\r\nb\r\nc")),
				() -> assertEquals(List.of("a", "b", "c"), WorkspaceService.splitLines("a\rb\rc")),
				() -> assertEquals(List.of("a", "b", "c"), WorkspaceService.splitLines("a\r\nb\nc")));
		}

		@Test
		@DisplayName("a trailing newline does not produce a phantom last line")
		void trailingNewlineIsNotALine()
		{
			assertAll(
				() -> assertEquals(List.of("a", "b"), WorkspaceService.splitLines("a\nb\n")),
				() -> assertEquals(List.of("a", "b"), WorkspaceService.splitLines("a\r\nb\r\n")));
		}

		@Test
		@DisplayName("empty and null content yield no lines")
		void emptyContent()
		{
			assertAll(
				() -> assertEquals(List.of(), WorkspaceService.splitLines("")),
				() -> assertEquals(List.of(), WorkspaceService.splitLines(null)));
		}

		@Test
		@DisplayName("an interior blank line is kept")
		void blankLineIsKept()
		{
			assertEquals(List.of("a", "", "b"), WorkspaceService.splitLines("a\n\nb"));
		}
	}

	@Nested
	@DisplayName("lineStartOffsets")
	class LineStartOffsets
	{
		@Test
		@DisplayName("LF offsets advance by the line length plus one")
		void lfOffsets()
		{
			assertArrayEqualsAsList(new int[] { 0, 2, 4 }, WorkspaceService.lineStartOffsets("a\nb\nc"));
		}

		@Test
		@DisplayName("CRLF offsets advance by the line length plus TWO - the bug this fixes")
		void crlfOffsets()
		{
			// "a\r\nb\r\nc": line 0 at 0, line 1 at 3, line 2 at 6. The old length()+1 arithmetic
			// would have said 0, 2, 4 - already two characters adrift after two lines.
			assertArrayEqualsAsList(new int[] { 0, 3, 6 }, WorkspaceService.lineStartOffsets("a\r\nb\r\nc"));
		}

		@Test
		@DisplayName("a lone CR counts as one character")
		void loneCrOffsets()
		{
			assertArrayEqualsAsList(new int[] { 0, 2, 4 }, WorkspaceService.lineStartOffsets("a\rb\rc"));
		}

		@Test
		@DisplayName("mixed delimiters are each measured as themselves")
		void mixedOffsets()
		{
			// "a\r\nbb\nc": 0, then 3 (after CRLF), then 6 (after "bb" + LF).
			assertArrayEqualsAsList(new int[] { 0, 3, 6 }, WorkspaceService.lineStartOffsets("a\r\nbb\nc"));
		}

		@Test
		@DisplayName("a trailing newline adds no further line start")
		void trailingNewlineAddsNoStart()
		{
			assertArrayEqualsAsList(new int[] { 0, 3 }, WorkspaceService.lineStartOffsets("a\r\nb\r\n"));
		}

		@Test
		@DisplayName("empty and null content yield no offsets")
		void emptyContent()
		{
			assertAll(
				() -> assertEquals(0, WorkspaceService.lineStartOffsets("").length),
				() -> assertEquals(0, WorkspaceService.lineStartOffsets(null).length));
		}

		private void assertArrayEqualsAsList(int[] expected, int[] actual)
		{
			assertEquals(asList(expected), asList(actual));
		}

		private List<Integer> asList(int[] values)
		{
			List<Integer> list = new java.util.ArrayList<>();
			for (int value : values)
				list.add(value);
			return list;
		}
	}

	@Nested
	@DisplayName("lineIndexFor")
	class LineIndexFor
	{
		@Test
		@DisplayName("an offset at a line start maps to that line")
		void offsetAtLineStart()
		{
			int[] starts = WorkspaceService.lineStartOffsets("a\r\nb\r\nc");
			assertAll(
				() -> assertEquals(0, WorkspaceService.lineIndexFor(starts, 0)),
				() -> assertEquals(1, WorkspaceService.lineIndexFor(starts, 3)),
				() -> assertEquals(2, WorkspaceService.lineIndexFor(starts, 6)));
		}

		@Test
		@DisplayName("an offset inside a line maps to that line, not the next")
		void offsetInsideLine()
		{
			int[] starts = WorkspaceService.lineStartOffsets("alpha\r\nbeta\r\ngamma");
			assertAll(
				() -> assertEquals(0, WorkspaceService.lineIndexFor(starts, 3)),
				() -> assertEquals(1, WorkspaceService.lineIndexFor(starts, 9)),
				() -> assertEquals(2, WorkspaceService.lineIndexFor(starts, 15)));
		}

		@Test
		@DisplayName("an offset on the delimiter itself still belongs to the line it ends")
		void offsetOnDelimiter()
		{
			// "alpha\r\nbeta": the \r is at 5 and the \n at 6; both are still line 0.
			int[] starts = WorkspaceService.lineStartOffsets("alpha\r\nbeta");
			assertAll(
				() -> assertEquals(0, WorkspaceService.lineIndexFor(starts, 5)),
				() -> assertEquals(0, WorkspaceService.lineIndexFor(starts, 6)),
				() -> assertEquals(1, WorkspaceService.lineIndexFor(starts, 7)));
		}

		@Test
		@DisplayName("a negative offset or empty content has no line")
		void noLine()
		{
			assertAll(
				() -> assertEquals(-1, WorkspaceService.lineIndexFor(new int[] { 0 }, -1)),
				() -> assertEquals(-1, WorkspaceService.lineIndexFor(new int[0], 0)),
				() -> assertEquals(-1, WorkspaceService.lineIndexFor(null, 0)));
		}

		@Test
		@DisplayName("an offset past the end belongs to the last line")
		void offsetPastEnd()
		{
			int[] starts = WorkspaceService.lineStartOffsets("a\r\nb");
			assertEquals(1, WorkspaceService.lineIndexFor(starts, 999));
		}
	}

	@Nested
	@DisplayName("the CRLF drift this fixes")
	class CrlfDrift
	{
		/** Rebuilds the line starts the way the OLD code did, so the drift is measurable. */
		private int[] oldStyleLineStarts(List<String> lines)
		{
			int[] starts = new int[lines.size()];
			int charCount = 0;
			for (int i = 0; i < lines.size(); i++)
			{
				starts[i] = charCount;
				charCount += lines.get(i).length() + 1;
			}
			return starts;
		}

		@Test
		@DisplayName("over 200 CRLF lines the old arithmetic drifts and the new mapping does not")
		void driftOverALongFile()
		{
			StringBuilder content = new StringBuilder();
			List<String> expectedLines = new java.util.ArrayList<>();
			for (int i = 0; i < 200; i++)
			{
				String line = "line " + i + " of the file";
				expectedLines.add(line);
				content.append(line).append("\r\n");
			}
			String text = content.toString();

			int[] realStarts = WorkspaceService.lineStartOffsets(text);
			int[] oldStarts = oldStyleLineStarts(expectedLines);

			// Pick a match offset in the middle of line 150 and ask both mappings which line it is.
			int offsetInLine150 = realStarts[150] + 3;
			int newAnswer = WorkspaceService.lineIndexFor(realStarts, offsetInLine150);
			int oldAnswer = WorkspaceService.lineIndexFor(oldStarts, offsetInLine150);

			assertAll(
				() -> assertEquals(expectedLines, WorkspaceService.splitLines(text),
					"the split must recover every line"),
				() -> assertEquals(150, newAnswer, "the real content gives the right line"),
				() -> assertTrue(oldAnswer > 150,
					"the old length()+1 arithmetic must drift PAST the right line - it reported " +
						oldAnswer + " for line 150, which is the bug"));
		}

		@Test
		@DisplayName("an LF file is unaffected either way - the fix is CRLF-specific")
		void lfFileIsUnaffected()
		{
			StringBuilder content = new StringBuilder();
			List<String> expectedLines = new java.util.ArrayList<>();
			for (int i = 0; i < 200; i++)
			{
				String line = "line " + i + " of the file";
				expectedLines.add(line);
				content.append(line).append("\n");
			}
			String text = content.toString();

			int[] realStarts = WorkspaceService.lineStartOffsets(text);
			int[] oldStarts = oldStyleLineStarts(expectedLines);

			int offsetInLine150 = realStarts[150] + 3;
			assertAll(
				() -> assertEquals(150, WorkspaceService.lineIndexFor(realStarts, offsetInLine150)),
				() -> assertEquals(150, WorkspaceService.lineIndexFor(oldStarts, offsetInLine150),
					"with LF the two agree, which is why the bug went unnoticed"));
		}
	}
}

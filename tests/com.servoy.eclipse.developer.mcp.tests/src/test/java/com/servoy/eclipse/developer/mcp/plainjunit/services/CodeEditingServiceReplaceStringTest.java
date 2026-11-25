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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.CodeEditingService;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService.Occurrence;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService.ReplaceOutcome;

/**
 * Tests the decision behind {@code replaceString} (SVY-21786): which occurrence of {@code oldString}
 * is replaced, when the call is refused, and how a near-miss is matched.
 * <p>
 * The old code ran {@code String.replace} over the range, which rewrote EVERY occurrence and reported
 * plain success. {@link CodeEditingService#replaceInLines} is the whole decision extracted so it can be
 * driven without a workspace; the end-to-end behaviour is covered by {@code ServoyCoderServerIntegrationTest}.
 * </p>
 */
public class CodeEditingServiceReplaceStringTest
{
	private static ReplaceOutcome replace(List<String> lines, String oldString, String newString, Occurrence occurrence)
	{
		return CodeEditingService.replaceInLines(lines, 0, lines.size() - 1, oldString, newString, occurrence);
	}

	@Nested
	@DisplayName("a string that occurs once")
	class SingleMatch
	{
		@Test
		@DisplayName("is replaced and reported as an exact match")
		void replacedExactly()
		{
			ReplaceOutcome outcome = replace(List.of("hello world", "foo bar", "baz"), "foo bar", "replaced", Occurrence.UNIQUE);

			assertAll(
				() -> assertEquals(List.of("hello world", "replaced", "baz"), outcome.lines()),
				() -> assertEquals(1, outcome.replacements()),
				() -> assertNull(outcome.matchedBy(), "an exact match must not be reported as fuzzy"));
		}

		@Test
		@DisplayName("a multi-line string that matches exactly is replaced and can change the line count")
		void multiLineExact()
		{
			ReplaceOutcome outcome = replace(List.of("a", "b", "c", "d"), "b\nc", "X\nY\nZ", Occurrence.UNIQUE);

			assertEquals(List.of("a", "X", "Y", "Z", "d"), outcome.lines());
		}

		@Test
		@DisplayName("a match inside a line replaces only that part of the line")
		void partialLine()
		{
			ReplaceOutcome outcome = replace(List.of("int count = 1;"), "count", "total", Occurrence.UNIQUE);

			assertEquals(List.of("int total = 1;"), outcome.lines());
		}

		@Test
		@DisplayName("nothing matching returns null so the caller can report not-found")
		void notFound()
		{
			assertNull(replace(List.of("alpha", "beta"), "gamma", "x", Occurrence.UNIQUE));
		}
	}

	@Nested
	@DisplayName("a string that occurs more than once is REFUSED, not silently replaced everywhere")
	class Ambiguous
	{
		@Test
		@DisplayName("two occurrences are refused and the message names both lines - the bug this fixes")
		void twoOccurrencesRefused()
		{
			List<String> lines = List.of("x = 1;", "foo();", "y = 2;", "foo();");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(lines, "foo();", "bar();", Occurrence.UNIQUE));

			assertAll(
				() -> assertTrue(problem.getMessage().contains("2 locations"), problem.getMessage()),
				() -> assertTrue(problem.getMessage().contains("lines 2, 4"), problem.getMessage()),
				() -> assertTrue(problem.getMessage().contains("occurrence"), "should say how to proceed: " + problem.getMessage()));
		}

		@Test
		@DisplayName("a null occurrence behaves as UNIQUE")
		void nullOccurrenceIsUnique()
		{
			assertThrows(RuntimeException.class, () -> replace(List.of("a", "a"), "a", "b", null));
		}

		@Test
		@DisplayName("a near-miss that matches at two indentation levels is refused, never rewritten at both")
		void sameTextAtTwoIndentationLevels()
		{
			List<String> lines = List.of(
				"if (a) {",
				"    return false;",
				"}",
				"for (;;) {",
				"        return false;",
				"}");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(lines, "return false;", "return true;", Occurrence.UNIQUE));

			assertTrue(problem.getMessage().contains("lines 2, 5"), problem.getMessage());
		}

		@Test
		@DisplayName("overlapping occurrences count as ambiguous")
		void overlappingOccurrences()
		{
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(List.of("aaa"), "aa", "X", Occurrence.UNIQUE));

			assertTrue(problem.getMessage().contains("2 locations"), problem.getMessage());
		}

		@Test
		@DisplayName("line numbers are file line numbers even when the search range starts later")
		void lineNumbersAreRelativeToFile()
		{
			List<String> lines = List.of("a", "foo", "b", "foo", "c", "foo");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.replaceInLines(lines, 2, 5, "foo", "bar", Occurrence.UNIQUE));

			assertTrue(problem.getMessage().contains("lines 4, 6"), problem.getMessage());
		}

		@Test
		@DisplayName("a long candidate list is truncated")
		void longCandidateListTruncated()
		{
			List<String> lines = java.util.Collections.nCopies(30, "dup");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(lines, "dup", "x", Occurrence.UNIQUE));

			assertAll(
				() -> assertTrue(problem.getMessage().contains("30 locations"), problem.getMessage()),
				() -> assertTrue(problem.getMessage().contains("..."), problem.getMessage()));
		}

		@Test
		@DisplayName("narrowing the line range to one occurrence makes the call succeed")
		void narrowedRangeDisambiguates()
		{
			List<String> lines = List.of("foo", "x", "foo", "y");

			ReplaceOutcome outcome = CodeEditingService.replaceInLines(lines, 2, 3, "foo", "bar", Occurrence.UNIQUE);

			assertEquals(List.of("foo", "x", "bar", "y"), outcome.lines());
		}

		@Test
		@DisplayName("an occurrence outside the line range is not counted")
		void outsideRangeIgnored()
		{
			List<String> lines = List.of("foo", "x", "foo");

			ReplaceOutcome outcome = CodeEditingService.replaceInLines(lines, 0, 1, "foo", "bar", Occurrence.UNIQUE);

			assertEquals(List.of("bar", "x", "foo"), outcome.lines());
		}
	}

	@Nested
	@DisplayName("the caller opts in to more than one replacement")
	class OptIn
	{
		private final List<String> lines = List.of("foo", "x", "foo", "y", "foo");

		@Test
		@DisplayName("FIRST replaces only the first occurrence")
		void first()
		{
			ReplaceOutcome outcome = replace(lines, "foo", "bar", Occurrence.FIRST);

			assertAll(
				() -> assertEquals(List.of("bar", "x", "foo", "y", "foo"), outcome.lines()),
				() -> assertEquals(1, outcome.replacements()));
		}

		@Test
		@DisplayName("LAST replaces only the last occurrence")
		void last()
		{
			ReplaceOutcome outcome = replace(lines, "foo", "bar", Occurrence.LAST);

			assertEquals(List.of("foo", "x", "foo", "y", "bar"), outcome.lines());
		}

		@Test
		@DisplayName("ALL replaces every occurrence and reports how many")
		void all()
		{
			ReplaceOutcome outcome = replace(lines, "foo", "bar", Occurrence.ALL);

			assertAll(
				() -> assertEquals(List.of("bar", "x", "bar", "y", "bar"), outcome.lines()),
				() -> assertEquals(3, outcome.replacements()));
		}

		@Test
		@DisplayName("ALL skips an occurrence that overlaps one it already replaced")
		void allSkipsOverlap()
		{
			ReplaceOutcome outcome = replace(List.of("aaa"), "aa", "X", Occurrence.ALL);

			assertAll(
				() -> assertEquals(List.of("Xa"), outcome.lines()),
				() -> assertEquals(1, outcome.replacements()));
		}

		@Test
		@DisplayName("ALL on a single occurrence is fine")
		void allOnSingle()
		{
			ReplaceOutcome outcome = replace(List.of("a", "b"), "b", "c", Occurrence.ALL);

			assertEquals(List.of("a", "c"), outcome.lines());
		}
	}

	@Nested
	@DisplayName("an empty oldString")
	class EmptyOldString
	{
		@Test
		@DisplayName("is refused - String.replace would insert newString between every character")
		void refused()
		{
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(List.of("abc"), "", "X", Occurrence.ALL));

			assertTrue(problem.getMessage().contains("must not be empty"), problem.getMessage());
		}
	}

	@Nested
	@DisplayName("whitespace-tolerant fallback")
	class Tolerant
	{
		@Test
		@DisplayName("a CRLF oldString matches an LF-split file and is reported as a line-ending match")
		void crlfOldString()
		{
			ReplaceOutcome outcome = replace(List.of("one", "two", "three", "four"), "two\r\nthree", "TWO\r\nTHREE", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertAll(
				() -> assertEquals(List.of("one", "TWO", "THREE", "four"), outcome.lines()),
				() -> assertEquals("line-ending", outcome.matchedBy()));
		}

		@Test
		@DisplayName("trailing whitespace on the file's lines is ignored")
		void trailingWhitespaceInFile()
		{
			ReplaceOutcome outcome = replace(List.of("keep", "alpha   ", "beta\t", "gamma"), "alpha\nbeta", "ALPHA\nBETA", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertAll(
				() -> assertEquals(List.of("keep", "ALPHA", "BETA", "gamma"), outcome.lines()),
				() -> assertEquals("line-ending and trailing-whitespace", outcome.matchedBy()));
		}

		@Test
		@DisplayName("trailing whitespace in oldString is ignored")
		void trailingWhitespaceInOldString()
		{
			ReplaceOutcome outcome = replace(List.of("alpha", "beta", "gamma"), "alpha  \nbeta  ", "A\nB", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertEquals(List.of("A", "B", "gamma"), outcome.lines());
		}

		@Test
		@DisplayName("an oldString ending in a line break selects whole lines and does not add a blank line")
		void oldStringEndingInNewline()
		{
			// The file lines carry trailing whitespace, so only the tolerant tier can match. The trailing
			// break must not become a required empty line, and the replacement's own trailing break must
			// not become an extra blank line.
			ReplaceOutcome outcome = replace(List.of("alpha  ", "beta", "gamma"), "alpha\nbeta\n", "A\nB\n", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertEquals(List.of("A", "B", "gamma"), outcome.lines());
		}

		@Test
		@DisplayName("deleting the last line with a trailing-newline oldString and an empty newString removes it, leaving no blank line")
		void deleteLastLineWithTrailingNewline()
		{
			// The file's last line has no terminator in the line list, so "key=Delete\n" cannot match
			// exactly and falls to the tolerant tier; it must still delete the line, not blank it.
			ReplaceOutcome outcome = replace(List.of("a=1", "button.delete=Delete"), "button.delete=Delete\n", "", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertEquals(List.of("a=1"), outcome.lines());
		}

		@Test
		@DisplayName("deleting a middle line with a trailing-newline oldString and an empty newString removes it")
		void deleteMiddleLineWithTrailingNewline()
		{
			ReplaceOutcome outcome = replace(List.of("a=1", "button.delete=Delete", "b=2"), "button.delete=Delete\n", "", Occurrence.UNIQUE);

			assertEquals(List.of("a=1", "b=2"), outcome.lines());
		}

		@Test
		@DisplayName("an exact match is preferred: the tolerant tier does not run when the exact one found something")
		void exactPreferred()
		{
			ReplaceOutcome outcome = replace(List.of("alpha", "beta", "gamma"), "beta", "BETA", Occurrence.UNIQUE);

			assertNull(outcome.matchedBy());
		}

		@Test
		@DisplayName("a genuine text difference is still not found")
		void realDifferenceStillNotFound()
		{
			assertNull(replace(List.of("alpha", "beta", "gamma"), "alpha\nDELTA", "X\nY", Occurrence.UNIQUE));
		}

		@Test
		@DisplayName("indentation is never relaxed - a differently indented block is not found")
		void indentationNeverRelaxed()
		{
			List<String> lines = List.of("if (x) {", "    a();", "}");

			assertNull(replace(lines, "if (x) {\na();\n}", "if (y) {\nb();\n}", Occurrence.UNIQUE));
		}

		@Test
		@DisplayName("two tolerant matches are refused under UNIQUE, like exact ones")
		void ambiguousTolerantMatchRefused()
		{
			List<String> lines = List.of("start", "foo  ", "bar", "middle", "foo", "bar  ", "end");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> replace(lines, "foo\nbar", "FOO\nBAR", Occurrence.UNIQUE));

			assertTrue(problem.getMessage().contains("lines 2, 5"), problem.getMessage());
		}

		@Test
		@DisplayName("an exact hit does not hide a second location that only matches ignoring trailing whitespace")
		void exactHitDoesNotHideTolerantLocation()
		{
			// "foo\nbar" is an exact substring at line 5 only; line 2 matches just once trailing
			// whitespace is ignored. FIRST/LAST/UNIQUE must not silently pick the exact one.
			List<String> lines = List.of("start", "foo  ", "bar", "middle", "foo", "bar  ", "end");

			for (Occurrence occurrence : new Occurrence[] { Occurrence.UNIQUE, Occurrence.FIRST, Occurrence.LAST })
			{
				RuntimeException problem = assertThrows(RuntimeException.class,
					() -> replace(lines, "foo\nbar", "FOO\nBAR", occurrence), occurrence.name());
				assertTrue(problem.getMessage().contains("lines 2, 5"), occurrence + ": " + problem.getMessage());
			}
		}

		@Test
		@DisplayName("ALL replaces every tolerant match")
		void allTolerantMatches()
		{
			List<String> lines = List.of("start", "foo  ", "bar", "middle", "foo", "bar  ", "end");

			ReplaceOutcome outcome = replace(lines, "foo\nbar", "FOO\nBAR", Occurrence.ALL);

			assertAll(
				() -> assertEquals(List.of("start", "FOO", "BAR", "middle", "FOO", "BAR", "end"), outcome.lines()),
				() -> assertEquals(2, outcome.replacements()));
		}

		@Test
		@DisplayName("a partial false start before the real block does not confuse the match")
		void falseStart()
		{
			ReplaceOutcome outcome = replace(List.of("A", "X", "A ", "B", "C", "D"), "A\nB\nC", "A\nBEE\nCEE", Occurrence.UNIQUE);

			assertNotNull(outcome);
			assertEquals(List.of("A", "X", "A", "BEE", "CEE", "D"), outcome.lines());
		}

		@Test
		@DisplayName("the tolerant tier respects the line range")
		void tolerantRespectsRange()
		{
			List<String> lines = List.of("foo  ", "bar", "foo  ", "bar");

			ReplaceOutcome outcome = CodeEditingService.replaceInLines(lines, 2, 3, "foo\nbar", "X\nY", Occurrence.UNIQUE);

			assertEquals(List.of("foo  ", "bar", "X", "Y"), outcome.lines());
		}
	}

	@Nested
	@DisplayName("Occurrence.parse")
	class ParseOccurrence
	{
		@Test
		@DisplayName("null and blank default to UNIQUE")
		void defaults()
		{
			assertAll(
				() -> assertEquals(Occurrence.UNIQUE, Occurrence.parse(null)),
				() -> assertEquals(Occurrence.UNIQUE, Occurrence.parse("  ")));
		}

		@Test
		@DisplayName("values are case-insensitive and trimmed")
		void caseInsensitive()
		{
			assertAll(
				() -> assertEquals(Occurrence.ALL, Occurrence.parse("all")),
				() -> assertEquals(Occurrence.FIRST, Occurrence.parse(" First ")),
				() -> assertEquals(Occurrence.LAST, Occurrence.parse("LAST")));
		}

		@Test
		@DisplayName("an unknown value is refused and the valid ones are listed")
		void unknownRefused()
		{
			RuntimeException problem = assertThrows(RuntimeException.class, () -> Occurrence.parse("EVERYTHING"));

			assertTrue(problem.getMessage().contains("UNIQUE, FIRST, LAST or ALL"), problem.getMessage());
		}
	}
}

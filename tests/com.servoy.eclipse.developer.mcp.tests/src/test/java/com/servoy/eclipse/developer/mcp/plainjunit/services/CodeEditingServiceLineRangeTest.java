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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.CodeEditingService;

/**
 * Tests the line-range validation behind {@code replaceString} - the fix for the silent clamping
 * named in AssistAI's {@code a6dc32d} commit message (SVY-21303).
 * <p>
 * The old code read the range as {@code Math.max(0, startLine - 1)} and
 * {@code Math.min(totalLines - 1, endLine - 1)}, so a bound outside the file was quietly pulled
 * inside it. Asking to replace within lines 10-20 of a 15-line file therefore edited lines 10-15
 * and reported success - the caller had excluded those lines by naming a range, and was told
 * nothing. A caller that names a range the file cannot satisfy is wrong about the file, so it is
 * refused instead.
 * </p>
 * <p>
 * {@code requireLineRangeInFile} is the whole decision, extracted so it can be driven without a
 * workspace; the end-to-end behaviour (the file staying untouched after a refusal) is covered by
 * {@code ServoyCoderServerIntegrationTest}.
 * </p>
 */
public class CodeEditingServiceLineRangeTest
{
	@Nested
	@DisplayName("ranges the file can satisfy")
	class Accepted
	{
		@Test
		@DisplayName("a range inside the file is accepted")
		void rangeInsideFile()
		{
			assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, 2, 5));
		}

		@Test
		@DisplayName("the whole file is accepted")
		void wholeFile()
		{
			assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, 1, 10));
		}

		@Test
		@DisplayName("the LAST line is in range - the refusal must not be off by one")
		void lastLineIsInRange()
		{
			assertAll(
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, 10, 10)),
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(1, 1, 1)));
		}

		@Test
		@DisplayName("a single line is accepted")
		void singleLine()
		{
			assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, 4, 4));
		}

		@Test
		@DisplayName("omitting both bounds means the whole file and is always accepted")
		void bothBoundsOmitted()
		{
			assertAll(
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, null, null)),
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(1, null, null)));
		}

		@Test
		@DisplayName("omitting one bound leaves it at the file's edge")
		void oneBoundOmitted()
		{
			assertAll(
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, 3, null)),
				() -> assertDoesNotThrow(() -> CodeEditingService.requireLineRangeInFile(10, null, 3)));
		}
	}

	@Nested
	@DisplayName("ranges the file cannot satisfy are REFUSED, not clamped")
	class Refused
	{
		@Test
		@DisplayName("an endLine past the end of the file is refused - the bug this fixes")
		void endLineBeyondFile()
		{
			// The old code clamped this to line 15 and edited lines 10-15 silently.
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.requireLineRangeInFile(15, 10, 20));
			assertAll(
				() -> assertTrue(problem.getMessage().contains("End line 20"),
					"Should name the bad bound: " + problem.getMessage()),
				() -> assertTrue(problem.getMessage().contains("beyond the end of the file"),
					"Should say why: " + problem.getMessage()),
				() -> assertTrue(problem.getMessage().contains("15"),
					"Should say how long the file actually is: " + problem.getMessage()));
		}

		@Test
		@DisplayName("a startLine past the end of the file is refused")
		void startLineBeyondFile()
		{
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.requireLineRangeInFile(10, 99, null));
			assertTrue(problem.getMessage().contains("Start line 99"),
				"Should name the bad bound: " + problem.getMessage());
		}

		@Test
		@DisplayName("one line past the end is already out of range - the boundary is exact")
		void oneLinePastEnd()
		{
			assertAll(
				() -> assertThrows(RuntimeException.class,
					() -> CodeEditingService.requireLineRangeInFile(10, 11, null)),
				() -> assertThrows(RuntimeException.class,
					() -> CodeEditingService.requireLineRangeInFile(10, null, 11)));
		}

		@Test
		@DisplayName("line 0 is not a line, so it is refused rather than floored to 1")
		void zeroIsNotALine()
		{
			// The old code read startLine 0 as Math.max(0, -1) = 0, i.e. line 1, accepting an input
			// that names no line at all.
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.requireLineRangeInFile(10, 0, null));
			assertTrue(problem.getMessage().contains("1-based"),
				"Should say lines are 1-based: " + problem.getMessage());
		}

		@Test
		@DisplayName("a negative line is refused")
		void negativeIsNotALine()
		{
			assertAll(
				() -> assertThrows(RuntimeException.class,
					() -> CodeEditingService.requireLineRangeInFile(10, -5, null)),
				() -> assertThrows(RuntimeException.class,
					() -> CodeEditingService.requireLineRangeInFile(10, null, -1)));
		}

		@Test
		@DisplayName("an inverted range is refused")
		void invertedRange()
		{
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.requireLineRangeInFile(10, 8, 3));
			assertTrue(problem.getMessage().contains("greater than end line"),
				"Should say the bounds are inverted: " + problem.getMessage());
		}

		@Test
		@DisplayName("an out-of-range bound is reported before an inversion, so the message names the real fault")
		void outOfRangeBeatsInversion()
		{
			// Both wrong: endLine is past the end AND the range is inverted once clamped. The
			// out-of-range bound is the caller's actual mistake, so that is what it hears about.
			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> CodeEditingService.requireLineRangeInFile(5, 4, 99));
			assertTrue(problem.getMessage().contains("End line 99"),
				"Should report the out-of-range bound: " + problem.getMessage());
		}
	}
}

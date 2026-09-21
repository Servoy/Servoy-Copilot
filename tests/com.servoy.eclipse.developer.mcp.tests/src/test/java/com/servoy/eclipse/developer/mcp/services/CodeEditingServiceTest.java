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
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Data-driven tests for the pure patch-application algorithm in
 * {@link CodeEditingService} (SVY-21472). Drives the package-private static
 * algorithm ({@code parseHunks}, {@code applyUnifiedDiff}/{@code applyHunk},
 * the line-ending helpers and {@code joinLines}) directly on in-memory
 * {@code String}/{@code List<String>} inputs - no Eclipse workspace, no
 * {@code IFile} - so it mirrors the production round-trip exactly.
 */
public class CodeEditingServiceTest {

	/**
	 * Splits file text into lines exactly the way production's
	 * {@code readFileLines} ({@code BufferedReader.readLine()}) does: line
	 * terminators (\n, \r, \r\n) are removed and a final terminator does NOT yield
	 * a trailing empty line.
	 */
	private static List<String> splitLikeReadFileLines(String fileText) {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new StringReader(fileText))) {
			String line;
			while ((line = reader.readLine()) != null)
				lines.add(line);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return lines;
	}

	/**
	 * Applies a patch to file text mirroring the production round-trip and returns
	 * the raw result.
	 */
	private static String applyRoundTrip(String fileText, String patch) {
		return applyRoundTrip(fileText, patch, null);
	}

	private static String applyRoundTrip(String fileText, String patch, List<String> driftNotesOut) {
		List<String> lines = splitLikeReadFileLines(fileText);
		String eol = CodeEditingService.detectDominantLineEnding(fileText);
		boolean trailing = CodeEditingService.endsWithNewline(fileText);
		List<String> result = CodeEditingService.applyUnifiedDiff(lines, patch, driftNotesOut);
		return CodeEditingService.joinLines(result, eol, trailing);
	}

	@Nested
	class Location {

		@Test
		@DisplayName("placeholder header far past the ±50 window is located by full-file fallback")
		void placeholderHeaderFullFileFallback() {
			StringBuilder file = new StringBuilder();
			for (int i = 1; i <= 200; i++)
				file.append("line").append(i).append("\n");
			// The real target (line 150) is well outside the hint (line 1) ±50 window.
			String patch = "@@ -1 +1 @@\n" + " line149\n" + "-line150\n" + "+CHANGED150\n" + " line151\n";
			String result = applyRoundTrip(file.toString(), patch);
			assertTrue(result.contains("line149\nCHANGED150\nline151\n"), "target block should be patched");
			assertFalse(result.contains("line150\n"), "old line150 should be gone");
		}

		@Test
		@DisplayName("unique long context far from hint applies via full-file fallback")
		void uniqueLongContextFarFromHint() {
			StringBuilder file = new StringBuilder();
			for (int i = 1; i <= 300; i++)
				file.append("row").append(i).append("\n");
			String patch = "@@ -1 +1 @@\n" + " row200\n" + " row201\n" + "-row202\n" + "+ROW202\n" + " row203\n";
			String result = applyRoundTrip(file.toString(), patch);
			assertTrue(result.contains("row201\nROW202\nrow203\n"));
			assertFalse(result.contains("row202\n"));
		}

		@Test
		@DisplayName("malformed header `@@ @@` is rejected quoting the header, never line 0")
		void malformedHeaderRejected() {
			String file = "alpha\nbeta\ngamma\n";
			String patch = "@@ @@\n" + "-beta\n" + "+BETA\n";
			RuntimeException ex = assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
			assertAll(() -> assertTrue(ex.getMessage().contains("Malformed hunk header"), "should say malformed"),
					() -> assertTrue(ex.getMessage().contains("@@ @@"), "should quote the offending header"),
					() -> assertFalse(ex.getMessage().contains("line 0"), "must never report line 0"));
		}

		@Test
		@DisplayName("multi-occurrence + placeholder header throws ambiguity listing candidates, file unchanged")
		void multiOccurrenceAmbiguity() {
			StringBuilder file = new StringBuilder();
			// A unique preamble long enough that the ambiguous block sits outside the hint
			// ±50 window.
			for (int i = 1; i <= 60; i++)
				file.append("pre").append(i).append("\n");
			// First occurrence of the ambiguous block.
			file.append("dupA\n").append("dupB\n");
			for (int i = 1; i <= 10; i++)
				file.append("mid").append(i).append("\n");
			// Second identical occurrence.
			file.append("dupA\n").append("dupB\n");
			String original = file.toString();

			String patch = "@@ -1 +1 @@\n" + " dupA\n" + "-dupB\n" + "+DUPB\n";
			RuntimeException ex = assertThrows(RuntimeException.class, () -> applyRoundTrip(original, patch));
			assertAll(() -> assertTrue(ex.getMessage().contains("matches 2 locations"), "should state the count"),
					() -> assertTrue(ex.getMessage().contains("did not disambiguate"), "should explain"),
					// The two identical blocks sit at 1-based lines 61 and 73.
					() -> assertTrue(ex.getMessage().contains("lines 61, 73"),
							"should list the candidate line numbers"));
		}
	}

	@Nested
	class LineEndings {

		@Test
		@DisplayName("CRLF file + LF-only patch keeps CRLF output")
		void crlfFileLfPatchStaysCrlf() {
			String file = "one\r\ntwo\r\nthree\r\n";
			String patch = "@@ -1 +1 @@\n" + " one\n" + "-two\n" + "+TWO\n" + " three\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\r\nTWO\r\nthree\r\n", result);
		}

		@Test
		@DisplayName("LF file + CRLF patch applies and keeps LF output")
		void lfFileCrlfPatchStaysLf() {
			// SPEC ACCEPTANCE CRITERION: an LF file patched with a CRLF patch remains LF.
			// parseHunks splits the patch on "\r?\n" so a CRLF-delimited patch does not
			// leave a trailing '\r' on the @@ header (which would otherwise fail the regex
			// and flag every hunk malformed) or on the context/added lines. The file's own
			// dominant ending (LF) is preserved on write-back.
			String file = "one\ntwo\nthree\n";
			String patch = "@@ -1 +1 @@\r\n" + " one\r\n" + "-two\r\n" + "+TWO\r\n" + " three\r\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\nTWO\nthree\n", result);
		}

		@Test
		@DisplayName("CR-only (old-Mac) file keeps CR output")
		void crOnlyFileStaysCr() {
			String file = "one\rtwo\rthree\r";
			String patch = "@@ -1 +1 @@\n" + " one\n" + "-two\n" + "+TWO\n" + " three\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\rTWO\rthree\r", result);
		}

		@Test
		@DisplayName("mixed-ending file (majority CRLF) normalizes to CRLF")
		void mixedMajorityCrlfNormalizes() {
			// 3 CRLF vs 1 LF -> dominant CRLF.
			String file = "a\r\nb\r\nc\r\nd\ne\r\n";
			String patch = "@@ -1 +1 @@\n" + " a\n" + "-b\n" + "+B\n" + " c\n";
			String result = applyRoundTrip(file, patch);
			// Every terminator becomes CRLF on re-join.
			assertEquals("a\r\nB\r\nc\r\nd\r\ne\r\n", result);
		}

		@Test
		@DisplayName("CRLF/LF tie resolves to LF")
		void crlfLfTieResolvesToLf() {
			// 2 CRLF vs 2 LF -> tie -> LF.
			String file = "a\r\nb\nc\r\nd\n";
			String patch = "@@ -1 +1 @@\n" + " a\n" + "-b\n" + "+B\n" + " c\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("a\nB\nc\nd\n", result);
		}

		@Test
		@DisplayName("file with no trailing newline keeps none")
		void noTrailingNewlineKept() {
			String file = "one\ntwo\nthree";
			String patch = "@@ -1 +1 @@\n" + " one\n" + "-two\n" + "+TWO\n" + " three\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\nTWO\nthree", result);
		}

		@Test
		@DisplayName("file with trailing newline keeps it")
		void trailingNewlineKept() {
			String file = "one\ntwo\nthree\n";
			String patch = "@@ -1 +1 @@\n" + " one\n" + "-two\n" + "+TWO\n" + " three\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\nTWO\nthree\n", result);
		}

		@ParameterizedTest(name = "detectDominantLineEnding: {0}")
		@MethodSource("com.servoy.eclipse.developer.mcp.services.CodeEditingServiceTest#dominantLineEndingCases")
		@DisplayName("detectDominantLineEnding unit cases")
		void detectDominant(String name, String content, String expected) {
			assertEquals(expected, CodeEditingService.detectDominantLineEnding(content));
		}
	}

	static Stream<Arguments> dominantLineEndingCases() {
		return Stream.of(Arguments.of("pure LF", "a\nb\nc\n", "\n"),
				Arguments.of("pure CRLF", "a\r\nb\r\nc\r\n", "\r\n"), Arguments.of("pure CR", "a\rb\rc\r", "\r"),
				Arguments.of("tie CRLF vs LF -> LF", "a\r\nb\n", "\n"), Arguments.of("empty -> LF", "", "\n"),
				Arguments.of("single unterminated line -> LF", "hello", "\n"),
				// A CRLF must not be double-counted as a lone CR: 2 CRLF, 0 CR, 0 LF -> CRLF.
				Arguments.of("CRLF not double-counted as CR", "x\r\ny\r\n", "\r\n"));
	}

	@Nested
	class JoinLines {

		@ParameterizedTest(name = "joinLines eol={1} trailing={2}")
		@MethodSource("com.servoy.eclipse.developer.mcp.services.CodeEditingServiceTest#joinLinesCases")
		@DisplayName("joins with the given eol and trailing state")
		void joins(List<String> lines, String eol, boolean trailing, String expected) {
			assertEquals(expected, CodeEditingService.joinLines(lines, eol, trailing));
		}
	}

	static Stream<Arguments> joinLinesCases() {
		List<String> abc = List.of("a", "b", "c");
		return Stream.of(Arguments.of(abc, "\n", false, "a\nb\nc"), Arguments.of(abc, "\n", true, "a\nb\nc\n"),
				Arguments.of(abc, "\r\n", false, "a\r\nb\r\nc"), Arguments.of(abc, "\r\n", true, "a\r\nb\r\nc\r\n"),
				Arguments.of(abc, "\r", false, "a\rb\rc"), Arguments.of(abc, "\r", true, "a\rb\rc\r"));
	}

	@Nested
	class FuzzShouldMatch {

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.servoy.eclipse.developer.mcp.services.CodeEditingServiceTest#fuzzShouldMatchCases")
		@DisplayName("whitespace-tolerant context still applies")
		void shouldMatch(String name, String file, String patch, String expected) {
			assertEquals(expected, applyRoundTrip(file, patch));
		}
	}

	static Stream<Arguments> fuzzShouldMatchCases() {
		return Stream.of(
				// Tier 2: context differs only by trailing whitespace.
				Arguments.of("trailing whitespace on context", "foo   \nbar\nbaz\n",
						"@@ -1 +1 @@\n" + " foo\n" + "-bar\n" + "+BAR\n" + " baz\n", "foo   \nBAR\nbaz\n"),
				// Tier 3: file uses tabs, patch uses spaces (leading indent differs).
				Arguments.of("file tabs, patch spaces", "\tfoo\n\tbar\n\tbaz\n",
						"@@ -1 +1 @@\n" + " foo\n" + "-bar\n" + "+BAR\n" + " baz\n", "\tfoo\n\tBAR\n\tbaz\n"),
				// Tier 3 reverse: file uses spaces, patch uses tabs.
				Arguments.of("file spaces, patch tabs", "    foo\n    bar\n    baz\n",
						"@@ -1 +1 @@\n" + "\tfoo\n" + "-\tbar\n" + "+\tBAR\n" + "\tbaz\n",
						"    foo\n    BAR\n    baz\n"),
				// Tier 3: file re-indented an extra level.
				Arguments.of("file re-indented extra level", "        foo\n        bar\n        baz\n",
						"@@ -1 +1 @@\n" + "    foo\n" + "-    bar\n" + "+    BAR\n" + "    baz\n",
						"        foo\n        BAR\n        baz\n"),
				// Tier 3: mixed tabs+spaces indentation differing, same text.
				Arguments.of("mixed tabs+spaces indent differs", "\t  foo\n\t  bar\n\t  baz\n",
						"@@ -1 +1 @@\n" + "  \tfoo\n" + "-  \tbar\n" + "+  \tBAR\n" + "  \tbaz\n",
						"\t  foo\n\t  BAR\n\t  baz\n"),
				// Tier 3: several context lines differ in ws but real text lines up.
				Arguments.of("multi-line ws differing on several lines", "  aaa\n\tbbb  \n   ccc\nddd\n",
						"@@ -1 +1 @@\n" + " aaa\n" + " bbb\n" + "-ccc\n" + "+CCC\n" + " ddd\n",
						"  aaa\n\tbbb  \n   CCC\nddd\n"),
				// Tier 4: drop first/last context line - outer context absent but inner block
				// matches.
				Arguments.of("drop-outer-context (extra outer context not in file)", "middle1\nmiddle2\nmiddle3\n",
						"@@ -1 +1 @@\n" + " NOT_IN_FILE_TOP\n" + " middle1\n" + "-middle2\n" + "+MIDDLE2\n"
								+ " middle3\n" + " NOT_IN_FILE_BOTTOM\n",
						"middle1\nMIDDLE2\nmiddle3\n"));
	}

	@Nested
	class FuzzShouldNotMatch {

		@Test
		@DisplayName("context-not-found failure carries searched range, expected block and actual lines at the hint")
		void evidenceRichFailureMessage() {
			// A real text difference so nothing matches at any tier, forcing the
			// evidence-rich failure. The hint is line 1 (placeholder header).
			String file = "top\nactualMiddle\nbottom\n";
			String patch = "@@ -1 +1 @@\n" + " top\n" + "-expectedMiddle\n" + "+CHANGED\n" + " bottom\n";
			RuntimeException ex = assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
			String msg = ex.getMessage();
			assertAll(
					// Searched-range evidence section (hint +/- 50 window then whole file).
					() -> assertTrue(msg.contains("Searched the hint \u00b1 50 lines"),
							"should report the searched range"),
					// Expected block section.
					() -> assertTrue(msg.contains("Expected block (context + removed lines"),
							"should include the expected block section"),
					() -> assertTrue(msg.contains("expectedMiddle"), "expected block should list the sought line"),
					// Actual lines at the hint section.
					() -> assertTrue(msg.contains("Actual lines at the hint (from line"),
							"should include the actual lines at the hint section"),
					() -> assertTrue(msg.contains("actualMiddle"), "actual section should list the file's line"));
		}

		@Test
		@DisplayName("real non-whitespace text difference is not matched")
		void realTextDiffers() {
			String file = "foo\nbar\nbaz\n";
			String patch = "@@ -1 +1 @@\n" + " foo\n" + "-QUX\n" + "+QUUX\n" + " baz\n";
			assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
		}

		@Test
		@DisplayName("interior (mid-line) whitespace collapse is not matched")
		void interiorWhitespaceNotCollapsed() {
			// File "a b" vs patch "a b" differ only by INTERIOR ws; tier 3 is
			// strip-leading+trailing only.
			String file = "top\na  b\nbottom\n";
			String patch = "@@ -1 +1 @@\n" + " top\n" + "-a b\n" + "+A B\n" + " bottom\n";
			assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
		}

		@Test
		@DisplayName("reflowed/merged lines across newlines are not matched")
		void reflowNotMatched() {
			// File keeps two separate lines; patch expects them merged into one - must not
			// match.
			String file = "alpha\nbeta\ngamma\n";
			String patch = "@@ -1 +1 @@\n" + "-alpha beta\n" + "+MERGED\n" + " gamma\n";
			assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
		}

		@Test
		@DisplayName("data-loss regression: mismatched removal must not delete surrounding lines")
		void dataLossRegression() {
			String file = "alpha\nbeta\ngamma\n";
			// Header claims line 1; removes OLDA (not in file), beta, keeps gamma context.
			String patch = "@@ -1 +1 @@\n" + "-OLDA\n" + "-beta\n" + " gamma\n";
			// Must fail (context not found) and never return a mutated list that dropped
			// beta/gamma.
			RuntimeException ex = assertThrows(RuntimeException.class, () -> applyRoundTrip(file, patch));
			assertTrue(
					ex.getMessage().contains("Could not find matching context")
							|| ex.getMessage().contains("did not disambiguate"),
					"should be a context-not-found style failure, not a silent mutation");
		}
	}

	@Nested
	class WriteBack {

		@Test
		@DisplayName("fuzzy match keeps file indentation on context and re-indents added lines")
		void reindentAddedToFileIndentation() {
			// File uses 8-space indent; patch uses 4-space indent (tier 3 content match).
			String file = "        keepTop\n        removeMe\n        keepBottom\n";
			String patch = "@@ -1 +1 @@\n" + "    keepTop\n" + "-    removeMe\n" + "+    addedLine\n"
					+ "    keepBottom\n";
			String result = applyRoundTrip(file, patch);
			// Context lines keep the FILE's 8-space indent; added line re-indented to 8
			// spaces.
			assertEquals("        keepTop\n        addedLine\n        keepBottom\n", result);
		}

		@Test
		@DisplayName("added line re-indents against an adjacent removed line's indent reference")
		void addedLineReindentsAgainstRemovedLine() {
			// The removed line (" onlyLine") supplies the expected indent (" ") and the
			// file's
			// actual indent ("\t") at the splice point. Tier 3 matches on stripped content,
			// then the
			// added line (" newLine", 6 spaces) has its expected-indent prefix (" ")
			// swapped for
			// the file's indent ("\t"), yielding "\t" + " newLine".
			String file = "\tonlyLine\n";
			String patch = "@@ -1 +1 @@\n" + "-  onlyLine\n" + "+      newLine\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("\t    newLine\n", result);
		}

		@Test
		@DisplayName("pure insertion (no context/removed lines) keeps the added line's own indentation")
		void pureInsertionKeepsOwnIndent() {
			// A hunk with only added lines takes the pure-insertion path: no adjacent
			// context/removed
			// line exists to relate the indent to, so the added line is inserted verbatim.
			String file = "alpha\nbeta\n";
			String patch = "@@ -1 +1 @@\n" + "+      insertedLine\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("      insertedLine\nalpha\nbeta\n", result);
		}
	}

	@Nested
	class Drift {

		@Test
		@DisplayName("fuzzy-tier success reports fuzzy matching and the affected line")
		void fuzzyTierReportsDrift() {
			String file = "\tfoo\n\tbar\n\tbaz\n";
			String patch = "@@ -1 +1 @@\n" + " foo\n" + "-bar\n" + "+BAR\n" + " baz\n";
			List<String> driftNotes = new ArrayList<>();
			String result = applyRoundTrip(file, patch, driftNotes);
			assertEquals("\tfoo\n\tBAR\n\tbaz\n", result);
			assertEquals(1, driftNotes.size(), "one drift note expected");
			String note = driftNotes.get(0);
			assertAll(() -> assertTrue(note.contains("fuzzy matching"), "should mention fuzzy matching"),
					() -> assertTrue(note.contains("leading/trailing whitespace"), "should name the tier"),
					() -> assertTrue(note.contains("line 1"), "should name the line the hunk landed on (1-based)"));
		}

		@Test
		@DisplayName("exact-tier success produces no drift note")
		void exactTierNoDrift() {
			String file = "foo\nbar\nbaz\n";
			String patch = "@@ -1 +1 @@\n" + " foo\n" + "-bar\n" + "+BAR\n" + " baz\n";
			List<String> driftNotes = new ArrayList<>();
			String result = applyRoundTrip(file, patch, driftNotes);
			assertEquals("foo\nBAR\nbaz\n", result);
			assertTrue(driftNotes.isEmpty(), "exact match should not signal drift");
		}

		@Test
		@DisplayName("drop-outer-context (tier 4) success signals drift")
		void tier4SignalsDrift() {
			String file = "middle1\nmiddle2\nmiddle3\n";
			String patch = "@@ -1 +1 @@\n" + " NOT_IN_FILE_TOP\n" + " middle1\n" + "-middle2\n" + "+MIDDLE2\n"
					+ " middle3\n" + " NOT_IN_FILE_BOTTOM\n";
			List<String> driftNotes = new ArrayList<>();
			String result = applyRoundTrip(file, patch, driftNotes);
			assertEquals("middle1\nMIDDLE2\nmiddle3\n", result);
			assertFalse(driftNotes.isEmpty(), "dropped outer context should signal drift");
			assertTrue(driftNotes.get(0).contains("fuzzy matching"));
		}

		@Test
		@DisplayName("originalCount mismatch still applies when content matches (no strict-count gate)")
		void originalCountMismatchStillApplies() {
			// Header count says 1 but the actual context+removed block is 3 lines; content
			// matches exactly.
			String file = "one\ntwo\nthree\n";
			String patch = "@@ -1,1 +1,1 @@\n" + " one\n" + "-two\n" + "+TWO\n" + " three\n";
			String result = applyRoundTrip(file, patch);
			assertEquals("one\nTWO\nthree\n", result);
		}

		@Test
		@DisplayName("originalCount disagreement on a fuzzy success appends the drift header-count suffix")
		void originalCountDisagreementDriftSuffix() {
			// Tier 3 (leading-ws) fuzzy match: file uses a tab indent, patch uses none.
			// The @@ header claims count 5 but the actual context+removed block is only 3
			// lines (2 context + 1 removed), so spliceHunk must append the header-count
			// disagreement suffix to the fuzzy drift note.
			String file = "\tfoo\n\tbar\n\tbaz\n";
			String patch = "@@ -1,5 +1,5 @@\n" + " foo\n" + "-bar\n" + "+BAR\n" + " baz\n";
			List<String> driftNotes = new ArrayList<>();
			String result = applyRoundTrip(file, patch, driftNotes);
			assertEquals("\tfoo\n\tBAR\n\tbaz\n", result);
			assertEquals(1, driftNotes.size(), "one drift note expected");
			String note = driftNotes.get(0);
			assertAll(() -> assertTrue(note.contains("fuzzy matching"), "should still note fuzzy matching"),
					() -> assertTrue(note.contains("Header count 5"), "should quote the header count"),
					() -> assertTrue(note.contains("disagreed with the actual context+removed line count 3"),
							"should quote the actual count in the disagreement suffix"));
		}
	}

	@Nested
	class ParseHunks {

		@Test
		@DisplayName("valid header is parsed with start and count")
		void validHeaderParsed() {
			List<CodeEditingService.DiffHunk> hunks = CodeEditingService
					.parseHunks("@@ -10,3 +10,4 @@\n foo\n-bar\n+baz\n");
			assertEquals(1, hunks.size());
			CodeEditingService.DiffHunk h = hunks.get(0);
			assertAll(() -> assertTrue(h.headerParsed, "header should parse"), () -> assertEquals(10, h.originalStart),
					() -> assertEquals(3, h.originalCount));
		}

		@Test
		@DisplayName("malformed header flagged not parsed with sentinel start")
		void malformedHeaderFlagged() {
			List<CodeEditingService.DiffHunk> hunks = CodeEditingService.parseHunks("@@ @@\n-bar\n+baz\n");
			assertEquals(1, hunks.size());
			CodeEditingService.DiffHunk h = hunks.get(0);
			assertAll(() -> assertFalse(h.headerParsed, "malformed header should not parse"),
					() -> assertEquals(-1, h.originalStart, "sentinel start"),
					() -> assertEquals("@@ @@", h.headerLine));
		}

		@Test
		@DisplayName("header without explicit count defaults originalCount to 1")
		void headerDefaultCount() {
			List<CodeEditingService.DiffHunk> hunks = CodeEditingService.parseHunks("@@ -5 +5 @@\n foo\n");
			CodeEditingService.DiffHunk h = hunks.get(0);
			assertAll(() -> assertTrue(h.headerParsed), () -> assertEquals(5, h.originalStart),
					() -> assertEquals(1, h.originalCount));
		}
	}
}

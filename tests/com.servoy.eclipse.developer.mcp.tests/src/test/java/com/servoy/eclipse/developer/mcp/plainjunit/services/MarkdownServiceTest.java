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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.MarkdownService;

/**
 * Plain-JUnit unit tests for {@link MarkdownService}'s <em>pure</em> logic - the
 * ATX/setext heading parsing ({@code parseHeadings(List)}) and the outline /
 * section rendering ({@link MarkdownService#getOutline} /
 * {@link MarkdownService#getSection}).
 * <p>
 * {@code parseHeadings} is a private instance method over a plain
 * {@code List<String>}, reached by reflection (mirroring the
 * {@code FileStructureServiceTest} / {@code CodeAnalysisServiceTest} style). The
 * {@code HeadingInfo} record it returns is a private nested type, so each result
 * is read back by reflection into a {@code level/text/line} triple.
 * </p>
 * <p>
 * {@code getOutline} / {@code getSection} both call {@code readFileLines(..)},
 * the only workbench touch in the service. To exercise their pure rendering
 * without a running workbench, the single minimal production seam - making
 * {@code readFileLines} {@code protected} - lets this test subclass the service
 * and feed a crafted {@code List<String>}. The real workspace file read stays
 * for the integration tests.
 * </p>
 *
 * @since 2026.06
 */
class MarkdownServiceTest {
	/** A {@link MarkdownService} whose {@code readFileLines} seam returns the supplied lines. */
	private static MarkdownService serviceWith(List<String> lines) {
		return new MarkdownService() {
			@Override
			protected List<String> readFileLines(String projectName, String resourcePath) {
				return lines;
			}
		};
	}

	// --- reflection into parseHeadings + the private HeadingInfo record ---

	/** A plain mirror of the private {@code HeadingInfo} record. */
	private record Heading(int level, String text, int line) {}

	@SuppressWarnings("unchecked")
	private static List<Heading> parseHeadings(List<String> lines) throws Exception {
		Method m = MarkdownService.class.getDeclaredMethod("parseHeadings", List.class);
		m.setAccessible(true);
		List<Object> raw = (List<Object>) m.invoke(new MarkdownService(), lines);
		List<Heading> result = new ArrayList<>();
		for (Object info : raw) {
			Method level = info.getClass().getDeclaredMethod("level");
			Method text = info.getClass().getDeclaredMethod("text");
			Method lineNumber = info.getClass().getDeclaredMethod("lineNumber");
			level.setAccessible(true);
			text.setAccessible(true);
			lineNumber.setAccessible(true);
			result.add(new Heading((int) level.invoke(info), (String) text.invoke(info), (int) lineNumber.invoke(info)));
		}
		return result;
	}

	@Nested
	class ParseHeadings {
		@Test
		@DisplayName("ATX headings are parsed with their level (# .. ######) and 1-based line number")
		void atxLevels() throws Exception {
			List<Heading> h = parseHeadings(List.of("# One", "## Two", "### Three", "###### Six"));
			assertEquals(4, h.size());
			assertEquals(new Heading(1, "One", 1), h.get(0));
			assertEquals(new Heading(2, "Two", 2), h.get(1));
			assertEquals(new Heading(3, "Three", 3), h.get(2));
			assertEquals(new Heading(6, "Six", 4), h.get(3));
		}

		@Test
		@DisplayName("a trailing run of # and surrounding whitespace is stripped from the heading text")
		void atxTrailingHashes() throws Exception {
			List<Heading> h = parseHeadings(List.of("## Closed heading ##", "#   Spaced   "));
			assertEquals(2, h.size());
			assertEquals(new Heading(2, "Closed heading", 1), h.get(0));
			assertEquals(new Heading(1, "Spaced", 2), h.get(1));
		}

		@Test
		@DisplayName("seven or more hashes is not an ATX heading")
		void sevenHashesIsNotAtx() throws Exception {
			assertTrue(parseHeadings(List.of("####### TooDeep")).isEmpty());
		}

		@Test
		@DisplayName("a '#' with no space before the text is not an ATX heading")
		void hashWithoutSpace() throws Exception {
			assertTrue(parseHeadings(List.of("#NoSpace")).isEmpty());
		}

		@Test
		@DisplayName("setext H1 (=== underline) and H2 (--- underline) are parsed on the title line")
		void setext() throws Exception {
			List<Heading> h = parseHeadings(List.of("Title One", "===", "Title Two", "---"));
			assertEquals(2, h.size());
			assertEquals(new Heading(1, "Title One", 1), h.get(0));
			assertEquals(new Heading(2, "Title Two", 3), h.get(1));
		}

		@Test
		@DisplayName("a setext underline needs at least three characters; two are ignored")
		void setextNeedsThree() throws Exception {
			assertTrue(parseHeadings(List.of("Title", "==")).isEmpty());
			assertTrue(parseHeadings(List.of("Title", "--")).isEmpty());
		}

		@Test
		@DisplayName("headings inside a fenced code block (``` or ~~~) are NOT parsed")
		void codeFenceIsSkipped() throws Exception {
			List<Heading> h = parseHeadings(List.of(
					"# Real",
					"```",
					"# Not a heading",
					"## Also not",
					"```",
					"## After fence"));
			assertEquals(2, h.size());
			assertEquals(new Heading(1, "Real", 1), h.get(0));
			assertEquals(new Heading(2, "After fence", 6), h.get(1));
		}

		@Test
		@DisplayName("a tilde fence (~~~) opens and closes a code block the same way")
		void tildeFence() throws Exception {
			List<Heading> h = parseHeadings(List.of("~~~", "# hidden", "~~~", "# visible"));
			assertEquals(1, h.size());
			assertEquals(new Heading(1, "visible", 4), h.get(0));
		}

		@Test
		@DisplayName("an empty or blank-only list yields no headings")
		void emptyAndBlank() throws Exception {
			assertTrue(parseHeadings(List.of()).isEmpty());
			assertTrue(parseHeadings(List.of("", "   ", "\t")).isEmpty());
		}

		@Test
		@DisplayName("a setext underline on the last line (no following title use) is ignored as a heading of its own")
		void setextUnderlineAtEndHasNoFollowup() throws Exception {
			// "===" as the very first/only non-title line cannot promote a preceding title, and is
			// itself not an ATX heading, so nothing is produced.
			assertTrue(parseHeadings(List.of("===")).isEmpty());
		}
	}

	@Nested
	class GetOutline {
		@Test
		@DisplayName("no headings yields the 'No headings found' message with the total line count")
		void noHeadings() {
			MarkdownService service = serviceWith(List.of("plain text", "more text"));
			assertEquals("No headings found in doc.md (2 lines total).", service.getOutline("P", "doc.md"));
		}

		@Test
		@DisplayName("the outline lists each heading indented by level with its line number and section length")
		void rendersOutline() {
			List<String> lines = List.of(
					"# Top",           // 1
					"intro",           // 2
					"## Sub A",        // 3
					"body a",          // 4
					"## Sub B",        // 5
					"body b",          // 6
					"last");           // 7
			MarkdownService service = serviceWith(lines);
			String expected = "Markdown outline of doc.md (7 lines total)\n\n"
					+ "# Top  [line 1, 2 lines]\n"
					+ "  ## Sub A  [line 3, 2 lines]\n"
					+ "  ## Sub B  [line 5, 3 lines]\n";
			assertEquals(expected, service.getOutline("P", "doc.md"));
		}

		@Test
		@DisplayName("the last heading's section runs to the end of the file")
		void lastSectionRunsToEnd() {
			List<String> lines = List.of("# Only", "a", "b", "c");
			MarkdownService service = serviceWith(lines);
			String out = service.getOutline("P", "doc.md");
			assertTrue(out.contains("# Only  [line 1, 4 lines]\n"), "got: " + out);
		}
	}

	@Nested
	class GetSection {
		private List<String> doc() {
			return List.of(
					"# Intro",         // 1
					"intro body",      // 2
					"## Details",      // 3
					"detail body",     // 4
					"### Deeper",      // 5
					"deep body",       // 6
					"## Other",        // 7
					"other body");     // 8
		}

		@Test
		@DisplayName("no headings yields the 'No headings found' message")
		void noHeadings() {
			MarkdownService service = serviceWith(List.of("plain", "text"));
			assertEquals("No headings found in doc.md", service.getSection("P", "doc.md", "Intro", true));
		}

		@Test
		@DisplayName("an unknown heading yields the 'Heading not found' message")
		void headingNotFound() {
			MarkdownService service = serviceWith(doc());
			assertEquals("Heading not found: 'Nope'. Use getMarkdownOutline to see available headings.",
					service.getSection("P", "doc.md", "Nope", true));
		}

		@Test
		@DisplayName("includeSubsections=true returns the heading and everything until the next same-or-higher level")
		void withSubsections() {
			MarkdownService service = serviceWith(doc());
			// '## Details' (line 3) with subsections runs until '## Other' (line 7), i.e. lines 3..6.
			String expected = "3\t## Details\n"
					+ "4\tdetail body\n"
					+ "5\t### Deeper\n"
					+ "6\tdeep body\n";
			assertEquals(expected, service.getSection("P", "doc.md", "Details", true));
		}

		@Test
		@DisplayName("includeSubsections=false stops at the very next heading of any level")
		void withoutSubsections() {
			MarkdownService service = serviceWith(doc());
			// '## Details' (line 3) without subsections stops at '### Deeper' (line 5), i.e. lines 3..4.
			String expected = "3\t## Details\n"
					+ "4\tdetail body\n";
			assertEquals(expected, service.getSection("P", "doc.md", "Details", false));
		}

		@Test
		@DisplayName("a numeric heading selects the 1-based nth heading from the outline")
		void numericIndex() {
			MarkdownService service = serviceWith(doc());
			// Heading index 1 == '# Intro' (line 1); without subsections it stops at '## Details' (line 3), lines 1..2.
			String expected = "1\t# Intro\n"
					+ "2\tintro body\n";
			assertEquals(expected, service.getSection("P", "doc.md", "1", false));
		}

		@Test
		@DisplayName("an out-of-range numeric heading falls back to a text search and is not found")
		void numericOutOfRange() {
			MarkdownService service = serviceWith(doc());
			String result = service.getSection("P", "doc.md", "99", true);
			assertTrue(result.startsWith("Heading not found: '99'."), "got: " + result);
		}

		@Test
		@DisplayName("heading text match is case-insensitive and substring-based")
		void caseInsensitiveSubstring() {
			MarkdownService service = serviceWith(doc());
			String result = service.getSection("P", "doc.md", "deep", false);
			assertEquals("5\t### Deeper\n6\tdeep body\n", result);
		}

		@Test
		@DisplayName("the first text match wins when several headings contain the term")
		void firstTextMatchWins() {
			List<String> lines = List.of("# Report alpha", "a", "# Report beta", "b");
			MarkdownService service = serviceWith(lines);
			String result = service.getSection("P", "doc.md", "report", false);
			// First match is '# Report alpha' (line 1), stops at '# Report beta' (line 3): lines 1..2.
			assertEquals("1\t# Report alpha\n2\ta\n", result);
			assertFalse(result.contains("beta"), "should not spill into the second match");
		}

		@Test
		@DisplayName("the line-number column is zero-padded to the width of the total line count")
		void lineNumberColumnWidth() {
			// 12 lines -> width 2, so single-digit line numbers are right-aligned in a 2-char field.
			List<String> lines = new ArrayList<>();
			lines.add("# Head"); // line 1
			for (int i = 2; i <= 12; i++) lines.add("body " + i);
			MarkdownService service = serviceWith(lines);
			String result = service.getSection("P", "doc.md", "Head", true);
			assertTrue(result.startsWith(" 1\t# Head\n"), "got: " + result);
			assertTrue(result.contains("12\tbody 12\n"), "got: " + result);
		}
	}
}

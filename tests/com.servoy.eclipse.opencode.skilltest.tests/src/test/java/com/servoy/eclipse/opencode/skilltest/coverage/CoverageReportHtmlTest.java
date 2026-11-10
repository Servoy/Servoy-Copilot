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

package com.servoy.eclipse.opencode.skilltest.coverage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CoverageReportHtml#render(CoverageReport)} — a
 * self-contained {@code <!DOCTYPE html>} document carrying the three dimension
 * sections, a NOT-COVERED-YET block when gaps exist, and HTML-escaped item
 * names (SVY-21711 §3.4).
 */
public class CoverageReportHtmlTest {

	private static CoverageObservation obs(String id, Set<String> skills, Set<String> agents, Set<String> tools) {
		return new CoverageObservation(id, skills, agents, tools);
	}

	@Nested
	class DocumentShape {

		@Test
		@DisplayName("output is a complete <!DOCTYPE html> document")
		void completeDocument() {
			CoverageReport report = CoverageReport.aggregate("2026-01-01T00-00-00", List.of(), PackCatalog.empty());
			String html = CoverageReportHtml.render(report);
			assertAll(() -> assertTrue(html.startsWith("<!DOCTYPE html"), "must start with the doctype"),
					() -> assertTrue(html.contains("<html"), "has an html element"),
					() -> assertTrue(html.contains("<body"), "has a body"),
					() -> assertTrue(html.trim().endsWith("</html>"), "closes the html element"));
		}

		@Test
		@DisplayName("a null report still renders a complete document")
		void nullReport() {
			String html = CoverageReportHtml.render(null);
			assertAll(() -> assertTrue(html.startsWith("<!DOCTYPE html")),
					() -> assertTrue(html.trim().endsWith("</html>")));
		}

		@Test
		@DisplayName("the three dimension section headings are present")
		void dimensionSections() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read"))),
					new PackCatalog(Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read")));
			String html = CoverageReportHtml.render(report);
			assertAll(() -> assertTrue(html.contains("Skills"), "skills section"),
					() -> assertTrue(html.contains("Agents"), "agents section"),
					() -> assertTrue(html.contains("MCP tools"), "mcp-tools section"));
		}
	}

	@Nested
	class NotCoveredBlock {

		@Test
		@DisplayName("a NOT COVERED YET block appears when the catalog has gaps")
		void notCoveredShownWhenGaps() {
			CoverageReport report = CoverageReport.aggregate("ts", List.of(obs("b1", Set.of("s1"), Set.of(), Set.of())),
					new PackCatalog(Set.of("s1", "s2"), Set.of(), Set.of()));
			String html = CoverageReportHtml.render(report);
			assertAll(() -> assertTrue(html.contains("NOT COVERED YET"), "the gap block must appear"),
					() -> assertTrue(html.contains("s2"), "the uncovered item must be listed"));
		}

		@Test
		@DisplayName("no NOT COVERED YET block when every dimension is fully covered")
		void notCoveredAbsentWhenFull() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read"))),
					new PackCatalog(Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read")));
			String html = CoverageReportHtml.render(report);
			assertFalse(html.contains("NOT COVERED YET"), "fully covered report must not emit a gap block");
		}
	}

	@Nested
	class Escaping {

		@Test
		@DisplayName("HTML special characters in item names are escaped")
		void escapesSpecialCharacters() {
			String nasty = "a<b>&\"c";
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of(nasty), Set.of(), Set.of())),
					new PackCatalog(Set.of(nasty), Set.of(), Set.of()));
			String html = CoverageReportHtml.render(report);
			assertAll(() -> assertTrue(html.contains("a&lt;b&gt;&amp;&quot;c"), "escaped form present"),
					() -> assertFalse(html.contains("a<b>&\"c"), "raw unescaped form absent"));
		}

		@Test
		@DisplayName("a special-char baseline id is escaped in the breakdown")
		void escapesBaselineId() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("<b1>", Set.of("s1"), Set.of(), Set.of())),
					new PackCatalog(Set.of("s1"), Set.of(), Set.of()));
			String html = CoverageReportHtml.render(report);
			assertAll(() -> assertTrue(html.contains("&lt;b1&gt;")), () -> assertFalse(html.contains("<b1>")));
		}
	}

	@Nested
	class Summary {

		@Test
		@DisplayName("per-dimension coverage counts appear in the rendered page")
		void coverageCountsShown() {
			CoverageReport report = CoverageReport.aggregate("ts", List.of(obs("b1", Set.of("s1"), Set.of(), Set.of())),
					new PackCatalog(Set.of("s1", "s2"), Set.of(), Set.of()));
			String html = CoverageReportHtml.render(report);
			// 1 of 2 skills covered.
			assertTrue(html.contains("1/2"), "the covered/catalog count must be shown");
		}

		@Test
		@DisplayName("a dimension with a gap renders an amber (partial) badge via inline style")
		void partialBadgeWhenGap() {
			CoverageReport report = CoverageReport.aggregate("ts", List.of(obs("b1", Set.of("s1"), Set.of(), Set.of())),
					new PackCatalog(Set.of("s1", "s2"), Set.of(), Set.of()));
			String html = CoverageReportHtml.render(report);
			// The amber (partial) badge uses the #b06000 foreground on #fef7e0.
			assertTrue(html.contains("#b06000"), "a dimension below 100% must carry the amber badge colour inline");
		}

		@Test
		@DisplayName("a fully covered dimension renders a green (full) badge via inline style")
		void fullBadgeWhenComplete() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read"))),
					new PackCatalog(Set.of("s1"), Set.of("build"), Set.of()));
			String html = CoverageReportHtml.render(report);
			// The green (full) badge uses the #137333 foreground on #e6f4ea.
			assertTrue(html.contains("#137333"), "a 100%-covered dimension must carry the green badge colour inline");
		}
	}

	@Nested
	class FileName {

		private static CoverageReport emptyReport() {
			return CoverageReport.aggregate("ts", List.of(), PackCatalog.empty());
		}

		@Test
		@DisplayName("a null fileName writes the timestamped coverage-<ts>.html (IDE history)")
		void timestampedByDefault(@TempDir Path dir) throws IOException {
			java.io.File out = CoverageReportHtml.write(emptyReport(), dir);
			assertAll(() -> assertEquals("coverage-ts.html", out.getName(), "uses the report timestamp"),
					() -> assertTrue(out.isFile(), "the file was written"));
		}

		@Test
		@DisplayName("a fixed fileName is used verbatim and a second write overwrites it (CI: one report per build)")
		void fixedNameOverwrites(@TempDir Path dir) throws IOException {
			java.io.File first = CoverageReportHtml.write(emptyReport(), dir, "coverage.html");
			java.io.File second = CoverageReportHtml.write(emptyReport(), dir, "coverage.html");
			assertAll(() -> assertEquals("coverage.html", first.getName(), "verbatim fixed name"),
					() -> assertEquals(first.getAbsolutePath(), second.getAbsolutePath(),
							"the second write targets the same file (overwrite, not a new file)"),
					() -> assertEquals(1,
							dir.toFile().listFiles((d, n) -> n.startsWith("coverage") && n.endsWith(".html")).length,
							"exactly one coverage html exists after two runs"));
		}
	}

	@Nested
	class InlineStyling {

		@Test
		@DisplayName("styled elements carry inline style= attributes (Jenkins CSP strips <style> blocks)")
		void carriesInlineStyleAttributes() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of("build"), Set.of("servoy-workspace_listProjects"))),
					new PackCatalog(Set.of("s1"), Set.of("build"), Set.of()));
			String html = CoverageReportHtml.render(report);
			assertAll(
					// the header must carry its colours inline so it renders under a strict CSP
					() -> assertTrue(html.contains("style=\""), "elements must carry inline style attributes"),
					() -> assertTrue(html.contains("#137333"), "the green header colour must be present inline"),
					// no inline <style> sheet relied upon
					() -> assertFalse(html.contains("<style"), "the report must not depend on a <style> block"));
		}
	}
}

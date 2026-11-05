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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.BaselineBreakdown;
import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.Dimension;

/**
 * Unit tests for {@link CoverageReport} — folding per-baseline
 * {@link CoverageObservation}s against a {@link PackCatalog}: the covered /
 * not-covered-yet / off-catalog math per dimension, per-item attribution to the
 * baseline ids that drove each item, the per-baseline breakdown, and the
 * covered/catalog counts and percentages incl. the empty-catalog case
 * (SVY-21711 §3.3).
 */
public class CoverageReportTest {

	private static CoverageObservation obs(String id, Set<String> skills, Set<String> agents, Set<String> tools) {
		return new CoverageObservation(id, skills, agents, tools);
	}

	@Nested
	class CoveredMath {

		@Test
		@DisplayName("covered = observed ∩ catalog; not-covered = catalog − observed")
		void coveredAndNotCovered() {
			PackCatalog catalog = new PackCatalog(Set.of("s1", "s2", "s3"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of(), Set.of()), obs("b2", Set.of("s2"), Set.of(), Set.of())),
					catalog);
			Dimension skills = report.getSkills();
			assertAll(() -> assertTrue(skills.coveredToBaselines().containsKey("s1")),
					() -> assertTrue(skills.coveredToBaselines().containsKey("s2")),
					() -> assertFalse(skills.coveredToBaselines().containsKey("s3")),
					() -> assertTrue(skills.notCovered().contains("s3")),
					() -> assertEquals(1, skills.notCovered().size()), () -> assertEquals(2, skills.coveredCount()),
					() -> assertEquals(3, skills.catalogCount()));
		}

		@Test
		@DisplayName("off-catalog = observed − catalog, surfaced separately")
		void offCatalog() {
			PackCatalog catalog = new PackCatalog(Set.of("s1"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1", "rogue"), Set.of(), Set.of())), catalog);
			Dimension skills = report.getSkills();
			assertAll(() -> assertTrue(skills.offCatalog().contains("rogue")),
					() -> assertFalse(skills.offCatalog().contains("s1")),
					() -> assertFalse(skills.notCovered().contains("rogue")));
		}

		@Test
		@DisplayName("matching between observed and catalog is case-insensitive")
		void caseInsensitiveMatch() {
			PackCatalog catalog = new PackCatalog(Set.of("Build"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("build"), Set.of(), Set.of())), catalog);
			Dimension skills = report.getSkills();
			assertAll(() -> assertEquals(1, skills.coveredCount()), () -> assertTrue(skills.notCovered().isEmpty()),
					() -> assertTrue(skills.offCatalog().isEmpty()));
		}

		@Test
		@DisplayName("an observed 'server.tool' matches a catalog 'server_tool' (MCP separator canonicalization)")
		void mcpSeparatorCanonicalization() {
			// Catalog uses the reflected underscore form; the observed call used a dot.
			PackCatalog catalog = new PackCatalog(Set.of(), Set.of(), Set.of("servoy-workspace_listProjects"));
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of(), Set.of(), Set.of("servoy-workspace.listProjects"))), catalog);
			Dimension tools = report.getMcpTools();
			assertAll(() -> assertEquals(1, tools.coveredCount()),
					() -> assertTrue(tools.coveredToBaselines().containsKey("servoy-workspace_listProjects")),
					() -> assertTrue(tools.notCovered().isEmpty()),
					// the dot form is NOT surfaced as off-catalog since it canonically matches
					() -> assertTrue(tools.offCatalog().isEmpty()));
		}

		@Test
		@DisplayName("MCP dimension is observed-based: an EMPTY catalog still covers every observed tool, none not-covered")
		void mcpDimensionIsObservedBased() {
			// The PackCatalog carries NO mcp tools (the reader no longer reflects); the
			// real MCP denominator is the union of the identities the suite observed.
			PackCatalog catalog = new PackCatalog(Set.of(), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of(), Set.of(), Set.of("servoy-workspace_listProjects")),
							obs("b2", Set.of(), Set.of(), Set.of("servoy-model_getTarget"))),
					catalog);
			Dimension tools = report.getMcpTools();
			assertAll(
					// both observed tools become the catalog and are covered
					() -> assertEquals(Set.of("servoy-workspace_listProjects", "servoy-model_getTarget"),
							new java.util.HashSet<>(tools.coveredToBaselines().keySet())),
					() -> assertEquals(Set.of("b1"), tools.coveredToBaselines().get("servoy-workspace_listProjects")),
					() -> assertEquals(Set.of("b2"), tools.coveredToBaselines().get("servoy-model_getTarget")),
					() -> assertEquals(2, tools.catalogCount()), () -> assertEquals(2, tools.coveredCount()),
					// nothing left uncovered and nothing off-catalog (observed == catalog)
					() -> assertTrue(tools.notCovered().isEmpty()), () -> assertTrue(tools.offCatalog().isEmpty()),
					() -> assertEquals(100, tools.percentage()));
		}

		@Test
		@DisplayName("MCP observed-based: a 'server.tool' and a 'server_tool' form canonicalize to one covered entry")
		void mcpObservedFormsCanonicalizeToOne() {
			PackCatalog catalog = new PackCatalog(Set.of(), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of(), Set.of(), Set.of("servoy-workspace.listProjects")),
							obs("b2", Set.of(), Set.of(), Set.of("servoy-workspace_listProjects"))),
					catalog);
			Dimension tools = report.getMcpTools();
			assertAll(() -> assertEquals(1, tools.catalogCount()), () -> assertEquals(1, tools.coveredCount()),
					() -> assertTrue(tools.coveredToBaselines().containsKey("servoy-workspace_listProjects")),
					// both baselines attributed to the single canonical entry
					() -> assertEquals(Set.of("b1", "b2"),
							tools.coveredToBaselines().get("servoy-workspace_listProjects")),
					() -> assertTrue(tools.notCovered().isEmpty()), () -> assertTrue(tools.offCatalog().isEmpty()));
		}

		@Test
		@DisplayName("the separator canonicalization only applies to MCP tools, not skills/agents")
		void canonicalizationDoesNotTouchSkills() {
			// A skill id that happens to contain a dot must NOT be treated as server.tool.
			PackCatalog catalog = new PackCatalog(Set.of("a.b"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("a_b"), Set.of(), Set.of())), catalog);
			Dimension skills = report.getSkills();
			// a_b != a.b for skills (no canonicalization), so a.b stays not-covered.
			assertAll(() -> assertEquals(0, skills.coveredCount()),
					() -> assertTrue(skills.notCovered().contains("a.b")),
					() -> assertTrue(skills.offCatalog().contains("a_b")));
		}
	}

	@Nested
	class Attribution {

		@Test
		@DisplayName("each covered item is attributed to the baselines that exercised it")
		void perItemAttribution() {
			PackCatalog catalog = new PackCatalog(Set.of("s1", "s2"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts", List.of(obs("b1", Set.of("s1"), Set.of(), Set.of()),
					obs("b2", Set.of("s1", "s2"), Set.of(), Set.of())), catalog);
			Dimension skills = report.getSkills();
			assertAll(() -> assertEquals(Set.of("b1", "b2"), skills.coveredToBaselines().get("s1")),
					() -> assertEquals(Set.of("b2"), skills.coveredToBaselines().get("s2")));
		}

		@Test
		@DisplayName("an observation with a null baseline id does not attribute coverage")
		void nullBaselineIdNotAttributed() {
			PackCatalog catalog = new PackCatalog(Set.of("s1"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts", List.of(obs(null, Set.of("s1"), Set.of(), Set.of())),
					catalog);
			Dimension skills = report.getSkills();
			// No baseline id to attribute to, so s1 remains not-covered.
			assertAll(() -> assertEquals(0, skills.coveredCount()),
					() -> assertTrue(skills.notCovered().contains("s1")));
		}
	}

	@Nested
	class Breakdown {

		@Test
		@DisplayName("per-baseline breakdown lists what each baseline contributed, in run order")
		void perBaselineBreakdown() {
			PackCatalog catalog = new PackCatalog(Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read"));
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1"), Set.of("build"), Set.of("eclipse-ide.read")),
							obs("b2", Set.of(), Set.of("writer"), Set.of())),
					catalog);
			List<BaselineBreakdown> breakdowns = report.getBreakdowns();
			assertAll(() -> assertEquals(2, breakdowns.size()),
					() -> assertEquals("b1", breakdowns.get(0).baselineId()),
					() -> assertTrue(breakdowns.get(0).skills().contains("s1")),
					() -> assertTrue(breakdowns.get(0).agents().contains("build")),
					() -> assertTrue(breakdowns.get(0).mcpTools().contains("eclipse-ide.read")),
					() -> assertEquals("b2", breakdowns.get(1).baselineId()),
					() -> assertTrue(breakdowns.get(1).agents().contains("writer")));
		}
	}

	@Nested
	class Percentages {

		@Test
		@DisplayName("percentage is covered/catalog rounded to a whole number")
		void percentageRounded() {
			PackCatalog catalog = new PackCatalog(Set.of("s1", "s2", "s3"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1", "s2"), Set.of(), Set.of())), catalog);
			// 2 / 3 = 66.67 -> 67
			assertEquals(67, report.getSkills().percentage());
		}

		@Test
		@DisplayName("a fully covered dimension is 100%")
		void fullyCovered() {
			PackCatalog catalog = new PackCatalog(Set.of("s1", "s2"), Set.of(), Set.of());
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("s1", "s2"), Set.of(), Set.of())), catalog);
			assertEquals(100, report.getSkills().percentage());
		}

		@Test
		@DisplayName("an empty catalog (0/0) reports 100% — nothing left uncovered")
		void emptyCatalogIsHundred() {
			CoverageReport report = CoverageReport.aggregate("ts",
					List.of(obs("b1", Set.of("off"), Set.of(), Set.of())), PackCatalog.empty());
			Dimension skills = report.getSkills();
			assertAll(() -> assertEquals(0, skills.catalogCount()), () -> assertEquals(0, skills.coveredCount()),
					() -> assertEquals(100, skills.percentage()),
					// The observed-but-uncatalogued item still surfaces as off-catalog.
					() -> assertTrue(skills.offCatalog().contains("off")));
		}
	}

	@Nested
	class Defensive {

		@Test
		@DisplayName("null observations and null catalog yield a non-null, empty report")
		void nullInputs() {
			CoverageReport report = CoverageReport.aggregate(null, null, null);
			assertAll(() -> assertEquals(3, report.getDimensions().size()),
					() -> assertEquals(0, report.getSkills().catalogCount()),
					() -> assertTrue(report.getBreakdowns().isEmpty()));
		}

		@Test
		@DisplayName("the three dimensions are returned in skills/agents/mcp order")
		void dimensionOrder() {
			CoverageReport report = CoverageReport.aggregate("ts", List.of(), PackCatalog.empty());
			List<Dimension> dims = report.getDimensions();
			assertAll(() -> assertEquals("Skills", dims.get(0).name()),
					() -> assertEquals("Agents", dims.get(1).name()),
					() -> assertEquals("MCP tools", dims.get(2).name()));
		}
	}
}

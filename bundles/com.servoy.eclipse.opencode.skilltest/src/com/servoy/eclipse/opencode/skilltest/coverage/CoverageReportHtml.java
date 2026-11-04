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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

import com.servoy.eclipse.opencode.skilltest.SkillTestHtml;
import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.BaselineBreakdown;
import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.Dimension;

/**
 * Renders a {@link CoverageReport} as a self-contained HTML page in the same
 * visual style as the per-run report (SVY-21711 §3.4). It reuses the exact
 * {@link SkillTestHtml#CSS} palette - header status colours, collapsible
 * {@code details}, tables, muted text - and adds only a few coverage-specific
 * rules so the two reports look like one family.
 */
public final class CoverageReportHtml {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"); //$NON-NLS-1$

	/** Coverage-specific additions layered on top of the shared palette. */
	private static final String EXTRA_CSS = "" + ".badges{margin:8px 0;}"
			+ ".badge{display:inline-block;padding:3px 8px;border-radius:10px;margin-right:6px;font-weight:600;font-size:11px;}"
			+ ".badge.full{background:#e6f4ea;color:#137333;}" + ".badge.partial{background:#fef7e0;color:#b06000;}"
			+ ".cov-table{width:100%;border-collapse:collapse;margin:4px 0;}"
			+ ".cov-table th{text-align:left;color:#5f6368;font-size:10px;text-transform:uppercase;padding:2px 6px;border-bottom:1px solid #eee;}"
			+ ".cov-table td{vertical-align:top;padding:2px 6px;font-size:11px;border-bottom:1px solid #f1f3f4;}"
			+ ".cov-table td.item{font-family:Consolas,monospace;}"
			+ ".cov-table td.count{text-align:right;color:#5f6368;white-space:nowrap;}"
			+ ".notcovered{background:#fce8e6;border:1px solid #f3c0bc;border-radius:6px;padding:6px 8px;margin:4px 0;}"
			+ ".notcovered .nc-title{font-weight:700;color:#c5221f;margin-bottom:4px;}"
			+ ".notcovered ul{margin:0;padding-left:18px;}"
			+ ".notcovered li{font-family:Consolas,monospace;font-size:11px;color:#c5221f;}"
			+ ".offcatalog{margin:4px 0;}"
			+ ".offcatalog .oc-title{font-weight:600;color:#5f6368;font-size:10px;text-transform:uppercase;margin-bottom:2px;}";

	private CoverageReportHtml() {
	}

	/**
	 * Best-effort end-to-end coverage emission: builds a per-baseline observation
	 * from each result's captured {@code actualCalls} + persisted builder
	 * {@code agent}, reads the {@link PackCatalog}, aggregates into a
	 * {@link CoverageReport} and writes {@code coverage-<timestamp>.html} into the
	 * given directory (SVY-21711 §3.5).
	 * <p>
	 * Coverage is purely informational: this method never throws - any failure is
	 * logged and swallowed so it can never fail the suite run or change the exit
	 * code. The caller chooses {@code targetDir}: the headless {@code outputDir} in
	 * CI (so Jenkins archives it alongside the JUnit XML) or
	 * {@code SkillTestPaths.reportsRoot()} in-IDE.
	 * </p>
	 *
	 * @param results   the completed suite results (each carries its
	 *                  {@code actualCalls} and builder {@code agent})
	 * @param timestamp the suite timestamp label (may be {@code null})
	 * @param targetDir the directory to write the coverage HTML into
	 * @param logger    a diagnostic sink (may be {@code null})
	 * @return the written file, or {@code null} when nothing was written
	 */
	public static File emitCoverage(java.util.List<com.servoy.eclipse.opencode.skilltest.SkillTestResult> results,
			String timestamp, Path targetDir, java.util.function.Consumer<String> logger) {
		try {
			java.util.List<CoverageObservation> observations = new java.util.ArrayList<>();
			if (results != null) {
				for (com.servoy.eclipse.opencode.skilltest.SkillTestResult r : results) {
					observations.add(CoverageExtractor.observe(r.getBaselineId(), r.getActualCalls(), r.getAgent()));
				}
			}
			PackCatalog catalog = PackCatalogReader.read();
			CoverageReport report = CoverageReport.aggregate(timestamp, observations, catalog);
			File written = write(report, targetDir);
			if (logger != null) {
				logger.accept("Wrote skill-test coverage report: " + written); //$NON-NLS-1$
			}
			return written;
		} catch (Exception | LinkageError e) {
			// Best-effort: coverage must never fail the run or change the exit code.
			if (logger != null) {
				logger.accept("Could not write skill-test coverage report: " + e.getMessage()); //$NON-NLS-1$
			}
			return null;
		}
	}

	/**
	 * Renders the full coverage report page.
	 *
	 * @param report the aggregated report (may be {@code null})
	 * @return a complete {@code <!DOCTYPE html>} document
	 */
	public static String render(CoverageReport report) {
		StringBuilder sb = new StringBuilder();
		String ts = report != null && report.getTimestamp() != null ? report.getTimestamp() : ""; //$NON-NLS-1$

		sb.append("<div class=\"header s-pass\">"); //$NON-NLS-1$
		sb.append("<span class=\"status\">Skill-test coverage</span> "); //$NON-NLS-1$
		sb.append("<span class=\"id\">").append(esc(ts)).append("</span>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("</div>"); //$NON-NLS-1$

		if (report == null) {
			sb.append("<p class=\"muted\">No coverage data.</p>"); //$NON-NLS-1$
			return page(sb.toString());
		}

		// Summary badges per dimension.
		sb.append("<div class=\"badges\">"); //$NON-NLS-1$
		for (Dimension d : report.getDimensions()) {
			sb.append(badge(d));
		}
		sb.append("</div>"); //$NON-NLS-1$

		// Per-dimension sections.
		for (Dimension d : report.getDimensions()) {
			sb.append(dimensionSection(d));
		}

		// Per-baseline breakdown.
		sb.append(breakdownSection(report));

		return page(sb.toString());
	}

	/**
	 * Writes the report to {@code coverage-<timestamp>.html} in the given directory
	 * and returns the written file. The {@code <timestamp>} uses the same
	 * convention as the run report's {@code run-<timestamp>.md}.
	 *
	 * @param report    the aggregated report
	 * @param targetDir the directory to write into (created if absent)
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	public static File write(CoverageReport report, Path targetDir) throws IOException {
		Files.createDirectories(targetDir);
		String stamp = report != null && report.getTimestamp() != null ? report.getTimestamp()
				: LocalDateTime.now().format(TIMESTAMP);
		File out = targetDir.resolve("coverage-" + stamp + ".html").toFile(); //$NON-NLS-1$ //$NON-NLS-2$
		Files.writeString(out.toPath(), render(report), StandardCharsets.UTF_8);
		return out;
	}

	private static String badge(Dimension d) {
		boolean full = d.percentage() >= 100;
		String cls = full ? "badge full" : "badge partial"; //$NON-NLS-1$ //$NON-NLS-2$
		return "<span class=\"" + cls + "\">" + esc(d.name()) + " " + d.coveredCount() + "/" + d.catalogCount() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				+ "</span>"; //$NON-NLS-1$
	}

	private static String dimensionSection(Dimension d) {
		StringBuilder sb = new StringBuilder();
		sb.append("<details open><summary class=\"section-summary\">").append(esc(d.name())).append(" \u2014 ") //$NON-NLS-1$ //$NON-NLS-2$
				.append(d.coveredCount()).append('/').append(d.catalogCount()).append(" (").append(d.percentage()) //$NON-NLS-1$
				.append("%)</summary>"); //$NON-NLS-1$

		// Covered table: item -> count -> baseline ids.
		Map<String, Set<String>> covered = d.coveredToBaselines();
		if (covered.isEmpty()) {
			sb.append("<div class=\"muted\">(nothing covered yet)</div>"); //$NON-NLS-1$
		} else {
			sb.append("<table class=\"cov-table\"><tr><th>Item</th><th>Count</th><th>Baselines</th></tr>"); //$NON-NLS-1$
			for (Map.Entry<String, Set<String>> entry : covered.entrySet()) {
				Set<String> baselines = entry.getValue();
				sb.append("<tr><td class=\"item\">").append(esc(entry.getKey())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td class=\"count\">").append(baselines.size()).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td>").append(esc(String.join(", ", baselines))).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			sb.append("</table>"); //$NON-NLS-1$
		}

		// NOT COVERED YET list (expanded/visible by default when non-empty).
		Set<String> notCovered = d.notCovered();
		if (!notCovered.isEmpty()) {
			sb.append("<div class=\"notcovered\"><div class=\"nc-title\">NOT COVERED YET (") //$NON-NLS-1$
					.append(notCovered.size()).append(")</div><ul>"); //$NON-NLS-1$
			for (String item : notCovered) {
				sb.append("<li>").append(esc(item)).append("</li>"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			sb.append("</ul></div>"); //$NON-NLS-1$
		}

		// Off-catalog (observed but not in the catalog) - informational.
		Set<String> offCatalog = d.offCatalog();
		if (!offCatalog.isEmpty()) {
			sb.append("<div class=\"offcatalog\"><div class=\"oc-title\">Off-catalog (observed, not in catalog)</div>"); //$NON-NLS-1$
			sb.append("<div class=\"muted\">").append(esc(String.join(", ", offCatalog))).append("</div></div>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		sb.append("</details>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static String breakdownSection(CoverageReport report) {
		StringBuilder sb = new StringBuilder();
		sb.append("<details><summary class=\"section-summary\">Per-baseline breakdown</summary>"); //$NON-NLS-1$
		if (report.getBreakdowns().isEmpty()) {
			sb.append("<div class=\"muted\">(no baselines in this run)</div></details>"); //$NON-NLS-1$
			return sb.toString();
		}
		sb.append("<table class=\"cov-table\"><tr><th>Baseline</th><th>Skills</th><th>Agents</th>" //$NON-NLS-1$
				+ "<th>MCP tools</th></tr>"); //$NON-NLS-1$
		for (BaselineBreakdown b : report.getBreakdowns()) {
			sb.append("<tr><td class=\"item\">").append(esc(b.baselineId())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td>").append(esc(join(b.skills()))).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td>").append(esc(join(b.agents()))).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<td>").append(esc(join(b.mcpTools()))).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append("</table></details>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static String join(Set<String> values) {
		if (values == null || values.isEmpty()) {
			return "\u2014"; //$NON-NLS-1$
		}
		return String.join(", ", values); //$NON-NLS-1$
	}

	/** Wraps body content in a styled HTML document reusing the shared palette. */
	private static String page(String body) {
		return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" //$NON-NLS-1$
				+ SkillTestHtml.CSS + EXTRA_CSS + "</style></head><body>" + body + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Minimal HTML escaping for text nodes (same approach as
	 * {@code SkillTestHtml.esc}).
	 */
	private static String esc(String s) {
		if (s == null) {
			return ""; //$NON-NLS-1$
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}
}

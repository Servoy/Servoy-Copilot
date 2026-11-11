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

import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.BaselineBreakdown;
import com.servoy.eclipse.opencode.skilltest.coverage.CoverageReport.Dimension;

/**
 * Renders a {@link CoverageReport} as a self-contained HTML page (SVY-21711
 * §3.4).
 * <p>
 * <b>Jenkins CSP note:</b> Jenkins serves archived build artifacts under a
 * strict Content-Security-Policy that strips any inline {@code <style>} block,
 * so a report that relied on a {@code <style>} sheet rendered completely
 * unstyled there. To survive the CSP with no server change, every element that
 * needs styling to be readable carries its visual rules as an inline
 * {@code style="..."} attribute instead; the page is fully readable with no
 * {@code <style>} block at all. The inline style strings are kept as
 * {@code STYLE_*} constants so the builders stay readable and literals are not
 * repeated.
 * </p>
 */
public final class CoverageReportHtml {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"); //$NON-NLS-1$

	// Inline style strings (see the Jenkins CSP note above). Visually equivalent
	// to the previous classed version: green header on a light-green band, a red
	// accent on the NOT-COVERED block, muted grey secondary text.

	private static final String STYLE_BODY = "font-family:Segoe UI,Arial,sans-serif;font-size:13px;color:#202124;" //$NON-NLS-1$
			+ "margin:16px;background:#fff;"; //$NON-NLS-1$

	private static final String STYLE_HEADER = "background:#e6f4ea;color:#137333;padding:8px 12px;border-radius:6px;" //$NON-NLS-1$
			+ "font-weight:600;margin-bottom:12px;"; //$NON-NLS-1$

	private static final String STYLE_HEADER_ID = "color:#5f6368;font-weight:400;margin-left:8px;"; //$NON-NLS-1$

	private static final String STYLE_BADGES = "margin:8px 0;"; //$NON-NLS-1$

	private static final String STYLE_BADGE_BASE = "display:inline-block;padding:3px 8px;border-radius:10px;" //$NON-NLS-1$
			+ "margin-right:6px;font-weight:600;font-size:11px;"; //$NON-NLS-1$

	private static final String STYLE_BADGE_FULL = STYLE_BADGE_BASE + "background:#e6f4ea;color:#137333;"; //$NON-NLS-1$

	private static final String STYLE_BADGE_PARTIAL = STYLE_BADGE_BASE + "background:#fef7e0;color:#b06000;"; //$NON-NLS-1$

	private static final String STYLE_SECTION_SUMMARY = "font-weight:600;font-size:14px;margin:12px 0 4px;"; //$NON-NLS-1$

	private static final String STYLE_TABLE = "border-collapse:collapse;margin:4px 0;width:100%;"; //$NON-NLS-1$

	private static final String STYLE_TH = "text-align:left;color:#5f6368;font-size:10px;text-transform:uppercase;" //$NON-NLS-1$
			+ "padding:2px 6px;border-bottom:1px solid #eee;"; //$NON-NLS-1$

	private static final String STYLE_TD = "vertical-align:top;padding:2px 6px;font-size:11px;" //$NON-NLS-1$
			+ "border-bottom:1px solid #f1f3f4;"; //$NON-NLS-1$

	private static final String STYLE_TD_ITEM = STYLE_TD + "font-family:Consolas,monospace;"; //$NON-NLS-1$

	private static final String STYLE_TD_COUNT = STYLE_TD + "text-align:right;color:#5f6368;white-space:nowrap;"; //$NON-NLS-1$

	private static final String STYLE_NOTCOVERED = "background:#fce8e6;border:1px solid #f3c0bc;border-radius:6px;" //$NON-NLS-1$
			+ "padding:6px 8px;margin:4px 0;"; //$NON-NLS-1$

	private static final String STYLE_NC_TITLE = "font-weight:700;color:#c5221f;margin-bottom:4px;"; //$NON-NLS-1$

	private static final String STYLE_NC_LIST = "margin:0;padding-left:18px;"; //$NON-NLS-1$

	private static final String STYLE_NC_ITEM = "font-family:Consolas,monospace;font-size:11px;color:#c5221f;"; //$NON-NLS-1$

	private static final String STYLE_OFFCATALOG = "margin:4px 0;"; //$NON-NLS-1$

	private static final String STYLE_OC_TITLE = "font-weight:600;color:#5f6368;font-size:10px;" //$NON-NLS-1$
			+ "text-transform:uppercase;margin-bottom:2px;"; //$NON-NLS-1$

	private static final String STYLE_MUTED = "color:#5f6368;"; //$NON-NLS-1$

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
		return emitCoverage(results, timestamp, targetDir, null, logger);
	}

	/**
	 * As
	 * {@link #emitCoverage(java.util.List, String, Path, java.util.function.Consumer)},
	 * but writes to a fixed {@code fileName} when given so repeated runs overwrite
	 * the same file instead of accumulating one timestamped report per run. CI
	 * passes {@code coverage.html} (so the archived artifacts hold a single, latest
	 * coverage report); the in-IDE path passes {@code null} to keep the timestamped
	 * history under {@code reports/}.
	 *
	 * @param results   the completed suite results
	 * @param timestamp the suite timestamp label (may be {@code null})
	 * @param targetDir the directory to write into
	 * @param fileName  the exact file name, or {@code null} for the timestamped
	 *                  default
	 * @param logger    a diagnostic sink (may be {@code null})
	 * @return the written file, or {@code null} when nothing was written
	 */
	public static File emitCoverage(java.util.List<com.servoy.eclipse.opencode.skilltest.SkillTestResult> results,
			String timestamp, Path targetDir, String fileName, java.util.function.Consumer<String> logger) {
		try {
			java.util.List<CoverageObservation> observations = new java.util.ArrayList<>();
			if (results != null) {
				for (com.servoy.eclipse.opencode.skilltest.SkillTestResult r : results) {
					observations.add(CoverageExtractor.observe(r.getBaselineId(), r.getActualCalls(), r.getAgent()));
				}
			}
			PackCatalog catalog = PackCatalogReader.read();
			// Real MCP denominator (SVY-21711): the full set of tool identities the pack's
			// configured MCP servers expose, derived purely from code - the extracted
			// opencode.json mcp-rename map joined with developer.mcp @McpServer/@Tool
			// reflection (see PackCatalogReader.readMcpToolDenominator). Best-effort - any
			// failure yields an empty set, in which case CoverageReport.aggregate falls
			// back to the observed-union denominator so the report never regresses. This
			// must never fail the run.
			Set<String> mcpDenominator = PackCatalogReader.readMcpToolDenominator();
			if (logger != null) {
				logger.accept("MCP tool denominator for coverage: " + mcpDenominator.size() + " tool(s)"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			CoverageReport report = CoverageReport.aggregate(timestamp, observations, catalog, mcpDenominator);
			File written = write(report, targetDir, fileName);
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

		sb.append("<div style=\"").append(STYLE_HEADER).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<span>Skill-test coverage</span> "); //$NON-NLS-1$
		sb.append("<span style=\"").append(STYLE_HEADER_ID).append("\">").append(esc(ts)).append("</span>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		sb.append("</div>"); //$NON-NLS-1$

		if (report == null) {
			sb.append("<p style=\"").append(STYLE_MUTED).append("\">No coverage data.</p>"); //$NON-NLS-1$ //$NON-NLS-2$
			return page(sb.toString());
		}

		// Summary badges per dimension.
		sb.append("<div style=\"").append(STYLE_BADGES).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
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
		return write(report, targetDir, null);
	}

	/**
	 * Writes the coverage report HTML into {@code targetDir}.
	 * <p>
	 * When {@code fileName} is {@code null} the report is written to a timestamped
	 * {@code coverage-<timestamp>.html} - suitable for the in-IDE {@code reports/}
	 * directory where a history of runs is wanted. When a {@code fileName} is given
	 * it is used verbatim (e.g. {@code coverage.html}), so repeated runs OVERWRITE
	 * the same file - suitable for CI, where each build should leave exactly one
	 * archived coverage report rather than accumulating a new timestamped file per
	 * build.
	 * </p>
	 *
	 * @param report    the aggregated report (may be {@code null})
	 * @param targetDir the directory to write into (created if absent)
	 * @param fileName  the exact file name to use, or {@code null} for the
	 *                  timestamped default
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	public static File write(CoverageReport report, Path targetDir, String fileName) throws IOException {
		Files.createDirectories(targetDir);
		String name;
		if (fileName != null && !fileName.isBlank()) {
			name = fileName.trim();
		} else {
			String stamp = report != null && report.getTimestamp() != null ? report.getTimestamp()
					: LocalDateTime.now().format(TIMESTAMP);
			name = "coverage-" + stamp + ".html"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		File out = targetDir.resolve(name).toFile();
		Files.writeString(out.toPath(), render(report), StandardCharsets.UTF_8);
		return out;
	}

	private static String badge(Dimension d) {
		boolean full = d.percentage() >= 100;
		String style = full ? STYLE_BADGE_FULL : STYLE_BADGE_PARTIAL;
		return "<span style=\"" + style + "\">" + esc(d.name()) + " " + d.coveredCount() + "/" + d.catalogCount() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				+ "</span>"; //$NON-NLS-1$
	}

	private static String dimensionSection(Dimension d) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div style=\"").append(STYLE_SECTION_SUMMARY).append("\">").append(esc(d.name())).append(" \u2014 ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append(d.coveredCount()).append('/').append(d.catalogCount()).append(" (").append(d.percentage()) //$NON-NLS-1$
				.append("%)</div>"); //$NON-NLS-1$

		// Covered table: item -> count -> baseline ids.
		Map<String, Set<String>> covered = d.coveredToBaselines();
		if (covered.isEmpty()) {
			sb.append("<div style=\"").append(STYLE_MUTED).append("\">(nothing covered yet)</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			sb.append("<table style=\"").append(STYLE_TABLE).append("\"><tr>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<th style=\"").append(STYLE_TH).append("\">Item</th>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<th style=\"").append(STYLE_TH).append("\">Count</th>"); //$NON-NLS-1$ //$NON-NLS-2$
			sb.append("<th style=\"").append(STYLE_TH).append("\">Baselines</th></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
			for (Map.Entry<String, Set<String>> entry : covered.entrySet()) {
				Set<String> baselines = entry.getValue();
				sb.append("<tr><td style=\"").append(STYLE_TD_ITEM).append("\">").append(esc(entry.getKey())) //$NON-NLS-1$ //$NON-NLS-2$
						.append("</td>"); //$NON-NLS-1$
				sb.append("<td style=\"").append(STYLE_TD_COUNT).append("\">").append(baselines.size()).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				sb.append("<td style=\"").append(STYLE_TD).append("\">").append(esc(String.join(", ", baselines))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						.append("</td></tr>"); //$NON-NLS-1$
			}
			sb.append("</table>"); //$NON-NLS-1$
		}

		// NOT COVERED YET list (expanded/visible by default when non-empty).
		Set<String> notCovered = d.notCovered();
		if (!notCovered.isEmpty()) {
			sb.append("<div style=\"").append(STYLE_NOTCOVERED).append("\"><div style=\"").append(STYLE_NC_TITLE) //$NON-NLS-1$ //$NON-NLS-2$
					.append("\">NOT COVERED YET (").append(notCovered.size()).append(")</div><ul style=\"") //$NON-NLS-1$ //$NON-NLS-2$
					.append(STYLE_NC_LIST).append("\">"); //$NON-NLS-1$
			for (String item : notCovered) {
				sb.append("<li style=\"").append(STYLE_NC_ITEM).append("\">").append(esc(item)).append("</li>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			sb.append("</ul></div>"); //$NON-NLS-1$
		}

		// Off-catalog (observed but not in the catalog) - informational.
		Set<String> offCatalog = d.offCatalog();
		if (!offCatalog.isEmpty()) {
			sb.append("<div style=\"").append(STYLE_OFFCATALOG).append("\"><div style=\"").append(STYLE_OC_TITLE) //$NON-NLS-1$ //$NON-NLS-2$
					.append("\">Off-catalog (observed, not in catalog)</div>"); //$NON-NLS-1$
			sb.append("<div style=\"").append(STYLE_MUTED).append("\">").append(esc(String.join(", ", offCatalog))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append("</div></div>"); //$NON-NLS-1$
		}

		return sb.toString();
	}

	private static String breakdownSection(CoverageReport report) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div style=\"").append(STYLE_SECTION_SUMMARY).append("\">Per-baseline breakdown</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		if (report.getBreakdowns().isEmpty()) {
			sb.append("<div style=\"").append(STYLE_MUTED).append("\">(no baselines in this run)</div>"); //$NON-NLS-1$ //$NON-NLS-2$
			return sb.toString();
		}
		sb.append("<table style=\"").append(STYLE_TABLE).append("\"><tr>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<th style=\"").append(STYLE_TH).append("\">Baseline</th>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<th style=\"").append(STYLE_TH).append("\">Skills</th>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<th style=\"").append(STYLE_TH).append("\">Agents</th>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("<th style=\"").append(STYLE_TH).append("\">MCP tools</th></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
		for (BaselineBreakdown b : report.getBreakdowns()) {
			sb.append("<tr><td style=\"").append(STYLE_TD_ITEM).append("\">").append(esc(b.baselineId())) //$NON-NLS-1$ //$NON-NLS-2$
					.append("</td>"); //$NON-NLS-1$
			sb.append("<td style=\"").append(STYLE_TD).append("\">").append(esc(join(b.skills()))).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			sb.append("<td style=\"").append(STYLE_TD).append("\">").append(esc(join(b.agents()))).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			sb.append("<td style=\"").append(STYLE_TD).append("\">").append(esc(join(b.mcpTools()))) //$NON-NLS-1$ //$NON-NLS-2$
					.append("</td></tr>"); //$NON-NLS-1$
		}
		sb.append("</table>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static String join(Set<String> values) {
		if (values == null || values.isEmpty()) {
			return "\u2014"; //$NON-NLS-1$
		}
		return String.join(", ", values); //$NON-NLS-1$
	}

	/**
	 * Wraps body content in a minimal HTML document. All visual styling is carried
	 * by inline {@code style="..."} attributes on the generated elements (see the
	 * Jenkins CSP note on the class) so the page renders correctly even when the
	 * server strips inline {@code <style>} blocks; a single {@code style} attribute
	 * on {@code <body>} sets the base font/colour.
	 */
	private static String page(String body) {
		return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body style=\"" //$NON-NLS-1$
				+ STYLE_BODY + "\">" + body + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
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

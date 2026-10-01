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

package com.servoy.eclipse.opencode.skilltest.headless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.servoy.eclipse.opencode.skilltest.SkillTestResult;

/**
 * Writes a standard JUnit XML report from a list of {@link SkillTestResult}s so
 * CI systems (the Jenkins JUnit plugin) can display skill-test results. Mirrors
 * the shape of the Cypress form-test reporter: one {@code <testcase>} per
 * baseline, {@code <failure>} for a diverged run (carrying the tool-call diff),
 * {@code <error>} for an infrastructure/timeout error.
 */
public final class JUnitXmlReporter {

	private JUnitXmlReporter() {
	}

	/**
	 * Writes {@code TEST-<suiteName>.xml} into {@code outputDir}.
	 *
	 * @param outputDir the directory to write the report into (created if absent)
	 * @param suiteName the JUnit suite name
	 * @param results   the per-baseline results
	 * @return the report file written
	 * @throws IOException if the file cannot be written
	 */
	public static Path writeReport(Path outputDir, String suiteName, List<SkillTestResult> results) throws IOException {
		Files.createDirectories(outputDir);

		int tests = results.size();
		int failures = 0;
		int errors = 0;
		for (SkillTestResult r : results) {
			switch (r.getStatus()) {
			case FAIL -> failures++;
			case ERROR -> errors++;
			default -> {
				// pass / skipped: not counted as failure or error
			}
			}
		}

		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
		xml.append("<testsuite name=\"").append(esc(suiteName)).append('"'); //$NON-NLS-1$
		xml.append(" tests=\"").append(tests).append('"'); //$NON-NLS-1$
		xml.append(" failures=\"").append(failures).append('"'); //$NON-NLS-1$
		xml.append(" errors=\"").append(errors).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$

		for (SkillTestResult r : results) {
			String id = r.getBaselineId();
			xml.append("  <testcase name=\"").append(esc(id)).append('"'); //$NON-NLS-1$
			xml.append(" classname=\"skilltest.").append(esc(id)).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
			switch (r.getStatus()) {
			case SKIPPED -> xml.append("    <skipped/>\n"); //$NON-NLS-1$
			case FAIL -> {
				xml.append("    <failure message=\"") //$NON-NLS-1$
						.append(esc("diverged after " + r.getAttempts() + " attempt(s)")).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				xml.append(cdata(failureBody(r)));
				xml.append("\n    </failure>\n"); //$NON-NLS-1$
			}
			case PASS -> {
				// On pass, still surface a JSUnit report (if any) as system-out so the
				// behavioural verification is visible in the Jenkins report.
				if (r.getJsUnitReport() != null && !r.getJsUnitReport().isBlank()) {
					xml.append("    <system-out>\n").append(cdata("JSUnit verification (PASS):\n" //$NON-NLS-1$ //$NON-NLS-2$
							+ r.getJsUnitReport())).append("\n    </system-out>\n"); //$NON-NLS-1$
				}
			}
			case ERROR -> {
				xml.append("    <error message=\"").append(esc(nullToEmpty(r.getErrorMessage()))).append("\">\n"); //$NON-NLS-1$ //$NON-NLS-2$
				xml.append(cdata(nullToEmpty(r.getErrorMessage())));
				xml.append("\n    </error>\n"); //$NON-NLS-1$
			}
			}
			xml.append("  </testcase>\n"); //$NON-NLS-1$
		}
		xml.append("</testsuite>\n"); //$NON-NLS-1$

		Path reportFile = outputDir.resolve("TEST-" + suiteName + ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
		Files.writeString(reportFile, xml.toString(), StandardCharsets.UTF_8);
		return reportFile;
	}

	/**
	 * Builds the {@code <failure>} body: the full structured outcome breakdown
	 * (every assertion, and every property's expected-vs-actual) when the baseline
	 * is outcome-based, so the Jenkins report shows the same detail as the view's
	 * Outcome checks panel; otherwise the flat transcript diff.
	 */
	private static String failureBody(SkillTestResult r) {
		List<com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node> nodes = r.getOutcomeNodes();
		if (nodes == null || nodes.isEmpty()) {
			return nullToEmpty(r.getDiff());
		}
		StringBuilder sb = new StringBuilder("Outcome checks:\n"); //$NON-NLS-1$
		for (var node : nodes) {
			appendNode(sb, node, "  "); //$NON-NLS-1$
		}
		String flat = nullToEmpty(r.getDiff());
		if (!flat.isBlank()) {
			sb.append('\n').append("Summary of mismatches:\n").append(flat); //$NON-NLS-1$
		}
		appendJsUnit(sb, r);
		return sb.toString();
	}

	/** Appends the JSUnit verification report to a failure body when one ran. */
	private static void appendJsUnit(StringBuilder sb, SkillTestResult r) {
		if (r.getJsUnitReport() != null && !r.getJsUnitReport().isBlank()) {
			sb.append('\n').append("JSUnit verification (") //$NON-NLS-1$
					.append(Boolean.TRUE.equals(r.getJsUnitPass()) ? "PASS" : "FAIL").append("):\n") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append(r.getJsUnitReport());
		}
	}

	/** Renders one outcome node (and its property checks + children) as indented text. */
	private static void appendNode(StringBuilder sb,
			com.servoy.eclipse.opencode.skilltest.outcome.PersistOutcomeChecker.Node node, String indent) {
		sb.append(indent).append(node.pass() ? "[PASS] " : "[FAIL] ") //$NON-NLS-1$ //$NON-NLS-2$
				.append(node.label()).append(" - ").append(node.message()).append('\n'); //$NON-NLS-1$
		for (var p : node.props()) {
			sb.append(indent).append("    ").append(p.pass() ? "ok " : "XX ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append(p.property()).append(": expected ").append(p.expected()) //$NON-NLS-1$
					.append(", actual ").append(p.actual()).append('\n'); //$NON-NLS-1$
		}
		for (var child : node.children()) {
			appendNode(sb, child, indent + "  "); //$NON-NLS-1$
		}
	}

	private static String cdata(String text) {
		if (text == null || text.isEmpty()) {
			return "<![CDATA[]]>"; //$NON-NLS-1$
		}
		return "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static String esc(String text) {
		if (text == null) {
			return ""; //$NON-NLS-1$
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				.replace("\"", "&quot;").replace("'", "&apos;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s; //$NON-NLS-1$
	}
}

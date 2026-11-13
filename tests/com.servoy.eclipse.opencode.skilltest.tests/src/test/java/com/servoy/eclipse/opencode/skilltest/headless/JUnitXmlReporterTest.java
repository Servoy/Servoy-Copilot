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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.skilltest.SkillTestResult;

/**
 * Unit tests for {@link JUnitXmlReporter} - the {@code TEST-<suite>.xml} the
 * Jenkins JUnit plugin consumes (SVY-21366). Verifies the file name, the suite
 * counts, the per-status elements, and XML escaping/CDATA so a bad character in
 * a baseline id or diff cannot produce malformed XML that breaks the CI report.
 */
public class JUnitXmlReporterTest {

	@Test
	@DisplayName("writes TEST-<suite>.xml with correct suite counts and per-status elements")
	void writesReport(@TempDir Path dir) throws Exception {
		List<SkillTestResult> results = List.of(SkillTestResult.pass("passing-one", 1),
				SkillTestResult.fail("failing-one", 2, "diff goes here"),
				SkillTestResult.error("erroring-one", 1, "boom"), SkillTestResult.skipped("skipped-one", "inactive"));

		Path report = JUnitXmlReporter.writeReport(dir, "skilltest", results);

		assertTrue(Files.isRegularFile(report), "report file written");
		assertTrue(report.getFileName().toString().equals("TEST-skilltest.xml"), "named TEST-<suite>.xml");

		String xml = Files.readString(report);
		assertAll(() -> assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "xml decl"),
				() -> assertTrue(xml.contains("<testsuite name=\"skilltest\""), "suite name"),
				() -> assertTrue(xml.contains("tests=\"4\""), "4 tests"),
				() -> assertTrue(xml.contains("failures=\"1\""), "1 failure"),
				() -> assertTrue(xml.contains("errors=\"1\""), "1 error"),
				() -> assertTrue(xml.contains("classname=\"skilltest.passing-one\""), "testcase classname"),
				() -> assertTrue(xml.contains("<skipped/>"), "skipped element"),
				() -> assertTrue(xml.contains("<failure message="), "failure element"),
				() -> assertTrue(xml.contains("<error message="), "error element"),
				() -> assertTrue(xml.contains("</testsuite>"), "closed suite"));
	}

	@Test
	@DisplayName("a passing baseline with a JSUnit report surfaces it as system-out")
	void passWithJsUnitSystemOut(@TempDir Path dir) throws Exception {
		SkillTestResult pass = SkillTestResult.pass("with-jsunit", 1)
				.withJsUnit("**JSUnit Test Results**\nAll 3 test(s) passed!", Boolean.TRUE);

		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(pass)));
		assertAll(() -> assertTrue(xml.contains("<system-out>"), "system-out present"),
				() -> assertTrue(xml.contains("JSUnit verification (PASS)"), "jsunit report included"));
	}

	@Test
	@DisplayName("special characters in ids/messages are XML-escaped in attributes")
	void escapesAttributes(@TempDir Path dir) throws Exception {
		// A baseline id and error with <, >, &, ", ' - must not break the XML.
		SkillTestResult err = SkillTestResult.error("a<b>&\"'c", 1, "msg <with> & \"quotes\"");
		// A suite name with filename-illegal chars must still produce a file (sanitized
		// name)
		// AND keep the raw name XML-escaped in the <testsuite name=...> attribute.
		java.nio.file.Path report = JUnitXmlReporter.writeReport(dir, "suite&<name>", List.of(err));
		assertTrue(report.getFileName().toString().equals("TEST-suite__name_.xml"), "filename sanitized");
		String xml = Files.readString(report);
		assertAll(() -> assertTrue(xml.contains("name=\"suite&amp;&lt;name&gt;\""), "suite name escaped in attr"),
				() -> assertTrue(xml.contains("skilltest.a&lt;b&gt;&amp;&quot;&apos;c"), "id escaped in classname"),
				() -> assertTrue(xml.contains("&lt;with&gt;"), "error message escaped in attribute"),
				// no raw unescaped angle bracket from our data leaked into an attribute value
				() -> assertTrue(isWellFormed(xml), "result parses as well-formed XML"));
	}

	@Test
	@DisplayName("a diff containing ]]> does not break the CDATA section")
	void cdataNestingEscaped(@TempDir Path dir) throws Exception {
		SkillTestResult fail = SkillTestResult.fail("cdata-case", 1, "before ]]> after");
		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(fail)));
		assertAll(
				// the ]]> is split so no CDATA section is prematurely closed
				() -> assertTrue(xml.contains("]]]]><![CDATA[>"), "nested ]]> split"),
				() -> assertTrue(isWellFormed(xml), "result parses as well-formed XML"));
	}

	@Test
	@DisplayName("a metrics line appears in system-out on PASS and on FAIL (SVY-21660)")
	void metricsLineOnEveryStatus(@TempDir Path dir) throws Exception {
		com.servoy.eclipse.opencode.skilltest.RunMetrics passMetrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				7, new com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage(10, 20, 0, 0, 0), null);
		com.servoy.eclipse.opencode.skilltest.RunMetrics failMetrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				31, null, null);
		SkillTestResult pass = SkillTestResult.pass("metrics-pass", 1).withMetrics(passMetrics);
		SkillTestResult fail = SkillTestResult.fail("metrics-fail", 2, "diverged").withMetrics(failMetrics);

		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(pass, fail)));
		assertAll(() -> assertTrue(xml.contains("Metrics: tool calls=7"), "pass metrics line present"),
				() -> assertTrue(xml.contains("Metrics: tool calls=31"), "fail metrics line present"),
				() -> assertTrue(xml.contains("tokens=30"), "pass token total surfaced"),
				() -> assertTrue(xml.contains("tokens=unknown"), "fail token shows unknown, never 0"));
	}

	@Test
	@DisplayName("the credits-delta line and per-request ranking appear in system-out on PASS and FAIL (SVY-21660 B/C/D)")
	void creditsAndRankingInSystemOut(@TempDir Path dir) throws Exception {
		// A PASS with an accurate credit delta and a ranked per-request list; the most
		// inefficient request re-sends a large uncached input.
		com.servoy.eclipse.opencode.skilltest.RunMetrics passMetrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				7, new com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage(10, 20, 0, 0, 0), null,
				Double.valueOf(0.42d),
				List.of(new com.servoy.eclipse.opencode.skilltest.RunMetrics.RequestTokens(0,
						new com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage(1000, 10, 0, 0, 0), List.of(),
						null),
						new com.servoy.eclipse.opencode.skilltest.RunMetrics.RequestTokens(1,
								new com.servoy.eclipse.opencode.skilltest.RunMetrics.TokenUsage(474_000, 20, 0, 0, 0),
								List.of("execute"), "sdd")));
		// A FAIL with no expansion data -> both must read "unknown", never a 0.
		com.servoy.eclipse.opencode.skilltest.RunMetrics failMetrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				31, null, null);
		SkillTestResult pass = SkillTestResult.pass("expansion-pass", 1).withMetrics(passMetrics);
		SkillTestResult fail = SkillTestResult.fail("expansion-fail", 2, "diverged").withMetrics(failMetrics);

		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(pass, fail)));
		assertAll(() -> assertTrue(xml.contains("Credits used"), "credits-delta line present"),
				() -> assertTrue(xml.contains("0.42"), "the delta figure surfaced on PASS"),
				() -> assertTrue(xml.contains("Per-request tokens:"), "per-request ranking line present"),
				() -> assertTrue(xml.contains("request #1"), "the most inefficient request highlighted"),
				() -> assertTrue(xml.contains("input 474000"), "its input surfaced"),
				// The FAIL's absent expansion data degrades to "unknown", never 0.
				() -> assertTrue(xml.contains("Credits used (\u0394): unknown"), "fail credits shows unknown, never 0"),
				() -> assertTrue(xml.contains("Per-request tokens: unknown"), "fail ranking shows unknown, never 0"));
	}

	@Test
	@DisplayName("the before/limit/after credit bracket surfaces in system-out (SVY-21660 live-data)")
	void creditBracketInSystemOut(@TempDir Path dir) throws Exception {
		// A PASS carrying the full account bracket (used before/after + the limit) and
		// the delta: the credits line must render "before: X / limit, after: Y, Δ: Z".
		com.servoy.eclipse.opencode.skilltest.RunMetrics metrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				7, null, null, Double.valueOf(0.73d), List.of(), Double.valueOf(100.5d), Double.valueOf(101.23d),
				Double.valueOf(2000.0d));
		SkillTestResult pass = SkillTestResult.pass("bracket-surface", 1).withMetrics(metrics);

		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(pass)));
		assertAll(() -> assertTrue(xml.contains("before: 100.5"), "the before reading surfaces"),
				() -> assertTrue(xml.contains("/ 2000.0"), "the limit surfaces next to before"),
				() -> assertTrue(xml.contains("after: 101.23"), "the after reading surfaces"),
				() -> assertTrue(xml.contains("\u0394: ~0.73"), "the delta surfaces"));
	}

	@Test
	@DisplayName("a budget warning appears in system-out")
	void budgetWarningInSystemOut(@TempDir Path dir) throws Exception {
		com.servoy.eclipse.opencode.skilltest.RunMetrics metrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				31, null, null);
		SkillTestResult pass = SkillTestResult.pass("over-budget", 1).withMetrics(metrics);
		Map<String, String> warnings = Map.of("over-budget", "tool-calls 31 exceeds budget 25 (+24%)");

		String xml = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(pass), warnings));
		assertAll(() -> assertTrue(xml.contains("Budget warning:"), "budget warning label present"),
				() -> assertTrue(xml.contains("tool-calls 31 exceeds budget 25 (+24%)"), "warning text present"));
	}

	@Test
	@DisplayName("metrics and budget warnings do NOT change failures/errors counts or a FAIL testcase body")
	void countsAndTestcaseUnchangedByMetrics(@TempDir Path dir) throws Exception {
		com.servoy.eclipse.opencode.skilltest.RunMetrics failMetrics = new com.servoy.eclipse.opencode.skilltest.RunMetrics(
				99, null, null);
		SkillTestResult fail = SkillTestResult.fail("budgeted-fail", 3, "the divergence diff text")
				.withMetrics(failMetrics);
		Map<String, String> warnings = Map.of("budgeted-fail", "tool-calls 99 exceeds budget 25 (+296%)");

		// With metrics + a budget warning attached.
		String withMetrics = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(fail), warnings));
		// Baseline: the SAME failing result with no metrics and no warnings.
		SkillTestResult plainFail = SkillTestResult.fail("budgeted-fail", 3, "the divergence diff text");
		String plain = Files.readString(JUnitXmlReporter.writeReport(dir, "s", List.of(plainFail)));

		assertAll(
				// A FAIL is still exactly one failure, zero errors, regardless of
				// metrics/warning.
				() -> assertTrue(withMetrics.contains("failures=\"1\""), "one failure"),
				() -> assertTrue(withMetrics.contains("errors=\"0\""), "zero errors"),
				// The <failure> element and its CDATA body are byte-for-byte identical to the
				// no-metrics baseline: metrics/warnings live only in <system-out>.
				() -> assertTrue(withMetrics.contains("<failure message="), "failure element kept"),
				() -> assertEquals(failureSection(plain), failureSection(withMetrics),
						"the <testcase>...<failure>...</failure> body is unchanged by metrics/warnings"),
				() -> assertTrue(withMetrics.contains("<system-out>"), "metrics still surfaced via system-out"));
	}

	/**
	 * The <failure>...</failure> block, used to prove metrics never alter the
	 * failure body.
	 */
	private static String failureSection(String xml) {
		int start = xml.indexOf("<failure message=");
		int end = xml.indexOf("</failure>");
		return start >= 0 && end >= 0 ? xml.substring(start, end) : xml;
	}

	/** Parses the XML to prove the reporter never emits a malformed document. */
	private static boolean isWellFormed(String xml) {
		try {
			javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
			f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}

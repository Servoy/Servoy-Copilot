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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.skilltest.SkillTestResult;

/**
 * Unit tests for {@link JUnitXmlReporter} - the {@code TEST-<suite>.xml} the Jenkins
 * JUnit plugin consumes (SVY-21366). Verifies the file name, the suite counts, the
 * per-status elements, and XML escaping/CDATA so a bad character in a baseline id or
 * diff cannot produce malformed XML that breaks the CI report.
 */
public class JUnitXmlReporterTest {

	@Test
	@DisplayName("writes TEST-<suite>.xml with correct suite counts and per-status elements")
	void writesReport(@TempDir Path dir) throws Exception {
		List<SkillTestResult> results = List.of(
				SkillTestResult.pass("passing-one", 1),
				SkillTestResult.fail("failing-one", 2, "diff goes here"),
				SkillTestResult.error("erroring-one", 1, "boom"),
				SkillTestResult.skipped("skipped-one", "inactive"));

		Path report = JUnitXmlReporter.writeReport(dir, "skilltest", results);

		assertTrue(Files.isRegularFile(report), "report file written");
		assertTrue(report.getFileName().toString().equals("TEST-skilltest.xml"), "named TEST-<suite>.xml");

		String xml = Files.readString(report);
		assertAll(
				() -> assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "xml decl"),
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
		assertAll(
				() -> assertTrue(xml.contains("<system-out>"), "system-out present"),
				() -> assertTrue(xml.contains("JSUnit verification (PASS)"), "jsunit report included"));
	}

	@Test
	@DisplayName("special characters in ids/messages are XML-escaped in attributes")
	void escapesAttributes(@TempDir Path dir) throws Exception {
		// A baseline id and error with <, >, &, ", ' - must not break the XML.
		SkillTestResult err = SkillTestResult.error("a<b>&\"'c", 1, "msg <with> & \"quotes\"");
		// A suite name with filename-illegal chars must still produce a file (sanitized name)
		// AND keep the raw name XML-escaped in the <testsuite name=...> attribute.
		java.nio.file.Path report = JUnitXmlReporter.writeReport(dir, "suite&<name>", List.of(err));
		assertTrue(report.getFileName().toString().equals("TEST-suite__name_.xml"), "filename sanitized");
		String xml = Files.readString(report);
		assertAll(
				() -> assertTrue(xml.contains("name=\"suite&amp;&lt;name&gt;\""), "suite name escaped in attr"),
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

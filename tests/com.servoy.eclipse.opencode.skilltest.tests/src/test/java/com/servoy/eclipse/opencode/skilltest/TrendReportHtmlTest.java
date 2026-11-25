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

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.skilltest.RunHistory.Entry;
import com.servoy.eclipse.opencode.skilltest.SkillTestResult.Status;
import com.servoy.eclipse.opencode.skilltest.TrendAlerts.Alert;
import com.servoy.eclipse.opencode.skilltest.report.ReportCss;

/**
 * Unit tests for {@link TrendReportHtml} (SVY-21661 §3.6b): CSP-safe output (no
 * JS, no inline CSS, one relative stylesheet link, no remote resources), the
 * report writer, escaping, alerts banner, per-baseline sections, change markers
 * and the chart helpers.
 */
class TrendReportHtmlTest {

	private int seq;

	private Entry entry(String baseline, Status status, String model, Long tokens, Double estCredits) {
		int i = seq++;
		return new Entry("run-" + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 60L), "ci",
				Integer.toString(100 + i), baseline, status, null, null, null, model, null, "s1", tokens, null,
				null, estCredits);
	}

	/** A history rich enough to exercise every chart path, with hostile strings. */
	private List<Entry> richHistory() {
		List<Entry> h = new ArrayList<>();
		h.add(entry("alpha", Status.PASS, "kiro/a", Long.valueOf(1000), Double.valueOf(0.5)));
		h.add(entry("alpha", Status.FAIL, "kiro/a", Long.valueOf(1500), Double.valueOf(0.7)));
		h.add(entry("alpha", Status.PASS, "kiro/b\" onload=\"x", Long.valueOf(1200), null));
		h.add(entry("beta", Status.PASS, null, null, null));
		return h;
	}

	private String richHtml() {
		return TrendReportHtml.render(richHistory(),
				List.of(new Alert("alpha", "FLAKY", "3 flips <script>", "model changed a → b")), 10);
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
			n++;
		}
		return n;
	}

	@Nested
	class CspSafety {

		@Test
		@DisplayName("no JavaScript: no <script, no on*= event attributes, no javascript: URLs")
		void noJavaScript() {
			String html = richHtml();
			assertAll(() -> assertFalse(html.toLowerCase().contains("<script"), "no script element"),
					() -> assertFalse(
							Pattern.compile("\\son[a-z]+\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE).matcher(html).find(),
							"no event-handler attribute"),
					() -> assertFalse(html.toLowerCase().contains("javascript:"), "no javascript: URL"));
		}

		@Test
		@DisplayName("no inline CSS anywhere (incl. SVG): no <style and no style=")
		void noInlineCss() {
			String html = richHtml();
			assertAll(() -> assertTrue(html.contains("<svg"), "charts present so SVG is covered"),
					() -> assertFalse(html.toLowerCase().contains("<style"), "no style block"),
					() -> assertFalse(html.toLowerCase().contains("style="), "no style attribute"));
		}

		@Test
		@DisplayName("exactly one relative skilltest-report.css link and no http(s) resource references")
		void oneRelativeStylesheet() {
			String html = richHtml();
			assertAll(
					() -> assertEquals(1, count(html, "<link rel=\"stylesheet\" href=\"skilltest-report.css\">"),
							"exactly one relative stylesheet link"),
					() -> assertEquals(1, count(html, "<link"), "no other link elements"),
					() -> assertFalse(Pattern.compile("(src|href)\\s*=\\s*\"https?://").matcher(html).find(),
							"no remote resource references"));
		}
	}

	@Nested
	class Content {

		@Test
		@DisplayName("values are HTML-escaped (model, alert message)")
		void escaped() {
			String html = richHtml();
			assertAll(() -> assertTrue(html.contains("kiro/b&quot; onload=&quot;x"), "model escaped"),
					() -> assertTrue(html.contains("3 flips &lt;script&gt;"), "alert message escaped"));
		}

		@Test
		@DisplayName("the alerts banner lists each alert with rule and likely cause")
		void alertsBanner() {
			String html = richHtml();
			assertAll(() -> assertTrue(html.contains("class=\"tr-alerts\"")),
					() -> assertTrue(html.contains("1 trend alert(s)")), () -> assertTrue(html.contains("FLAKY")),
					() -> assertTrue(html.contains("likely cause: model changed a → b")),
					() -> assertFalse(html.contains("No trend alerts")));
		}

		@Test
		@DisplayName("without alerts a green 'No trend alerts' line is shown instead of the banner")
		void noAlerts() {
			String html = TrendReportHtml.render(richHistory(), List.of(), 10);
			assertAll(() -> assertTrue(html.contains("<p class=\"tr-ok\">No trend alerts</p>")),
					() -> assertFalse(html.contains("class=\"tr-alerts\"")));
		}

		@Test
		@DisplayName("one section per baseline, and the summary carries the trend label")
		void sectionPerBaseline() {
			String html = richHtml();
			assertAll(() -> assertEquals(2, count(html, "<div class=\"tr-baseline\">")),
					() -> assertTrue(html.contains("<h3 class=\"tr-id\">alpha</h3>")),
					() -> assertTrue(html.contains("<h3 class=\"tr-id\">beta</h3>")),
					() -> assertTrue(html.contains("67% (2/3) ✓✗✓"), "alpha trend label in the summary"));
		}

		@Test
		@DisplayName("a baseline with < 2 runs gets a 'not enough history' note instead of charts")
		void notEnoughHistory() {
			String html = TrendReportHtml.render(List.of(entry("solo", Status.PASS, null, null, null)), null, 10);
			assertAll(() -> assertTrue(html.contains("Not enough history for charts yet")),
					() -> assertFalse(html.contains("<svg")));
		}

		@Test
		@DisplayName("a model change emits a dashed marker with a tooltip naming the change")
		void changeMarker() {
			String html = richHtml();
			assertAll(() -> assertTrue(html.contains("stroke-dasharray=\"3,3\"")),
					() -> assertTrue(html.contains("<title>model kiro/a → kiro/b&quot; onload=&quot;x</title>")));
		}

		@Test
		@DisplayName("no model/skills change -> no change marker")
		void noChangeMarker() {
			List<Entry> h = List.of(entry("a", Status.PASS, "m", null, null), entry("a", Status.FAIL, "m", null, null));
			assertFalse(TrendReportHtml.render(h, null, 10).contains("stroke-dasharray"));
		}

		@Test
		@DisplayName("empty or null history still renders a complete document")
		void emptyHistory() {
			String html = TrendReportHtml.render(null, null, 0);
			assertAll(() -> assertTrue(html.startsWith("<!DOCTYPE html>")), () -> assertTrue(html.endsWith("</html>")),
					() -> assertTrue(html.contains("No run history yet.")));
		}
	}

	@Nested
	class ChartHelpers {

		@Test
		@DisplayName("scale maps the first/last index to the plot edges and min/max to bottom/top")
		void scale() {
			double right = TrendReportHtml.CHART_WIDTH - TrendReportHtml.PAD_RIGHT;
			double bottom = TrendReportHtml.CHART_HEIGHT - TrendReportHtml.PAD_BOTTOM;
			TrendReportHtml.Point first = TrendReportHtml.scale(0, 3, 0, 0, 100);
			TrendReportHtml.Point last = TrendReportHtml.scale(2, 3, 100, 0, 100);
			TrendReportHtml.Point mid = TrendReportHtml.scale(1, 3, 50, 0, 100);
			assertAll(() -> assertEquals(TrendReportHtml.PAD_LEFT, first.x(), 1e-9),
					() -> assertEquals(bottom, first.y(), 1e-9), () -> assertEquals(right, last.x(), 1e-9),
					() -> assertEquals(TrendReportHtml.PAD_TOP, last.y(), 1e-9),
					() -> assertEquals((TrendReportHtml.PAD_LEFT + right) / 2d, mid.x(), 1e-9),
					() -> assertEquals((TrendReportHtml.PAD_TOP + bottom) / 2d, mid.y(), 1e-9));
		}

		@Test
		@DisplayName("scale clamps out-of-range values and centres a single point")
		void scaleEdges() {
			double bottom = TrendReportHtml.CHART_HEIGHT - TrendReportHtml.PAD_BOTTOM;
			assertAll(() -> assertEquals(TrendReportHtml.PAD_TOP, TrendReportHtml.scale(0, 2, 500, 0, 100).y(), 1e-9),
					() -> assertEquals(bottom, TrendReportHtml.scale(0, 2, -5, 0, 100).y(), 1e-9),
					() -> assertEquals(TrendReportHtml.PAD_LEFT
							+ (TrendReportHtml.CHART_WIDTH - TrendReportHtml.PAD_LEFT - TrendReportHtml.PAD_RIGHT) / 2d,
							TrendReportHtml.scale(0, 1, 50, 0, 100).x(), 1e-9));
		}

		@Test
		@DisplayName("polylinePoints skips null values and keeps the index spacing")
		void polylinePoints() {
			List<Double> values = new ArrayList<>();
			values.add(Double.valueOf(0));
			values.add(null);
			values.add(Double.valueOf(100));
			String pts = TrendReportHtml.polylinePoints(values, 0, 100);
			Matcher m = Pattern.compile("[0-9.]+,[0-9.]+").matcher(pts);
			int n = 0;
			while (m.find()) {
				n++;
			}
			int points = n;
			assertAll(() -> assertEquals(2, points), () -> assertTrue(pts.startsWith("36.0,102.0"), pts),
					() -> assertTrue(pts.endsWith("592.0,8.0"), pts));
		}

		@Test
		@DisplayName("rollingPassRate is the pass rate of the window ending at each run")
		void rollingPassRate() {
			List<Entry> runs = List.of(entry("a", Status.PASS, null, null, null),
					entry("a", Status.FAIL, null, null, null), entry("a", Status.FAIL, null, null, null),
					entry("a", Status.PASS, null, null, null));
			assertEquals(List.of(100d, 50d, 0d, 50d), TrendReportHtml.rollingPassRate(runs, 2));
		}

		@Test
		@DisplayName("normalize scales to 0..100 of the maximum and keeps nulls")
		void normalize() {
			List<Double> in = new ArrayList<>();
			in.add(Double.valueOf(50));
			in.add(null);
			in.add(Double.valueOf(200));
			List<Double> out = TrendReportHtml.normalize(in);
			assertAll(() -> assertEquals(25d, out.get(0), 1e-9), () -> assertEquals(null, out.get(1)),
					() -> assertEquals(100d, out.get(2), 1e-9));
		}

		@Test
		@DisplayName("changeMarkers flags runs whose model or skillsVersion changed")
		void changeMarkers() {
			List<Entry> runs = List.of(entry("a", Status.PASS, "m1", null, null),
					entry("a", Status.PASS, "m1", null, null), entry("a", Status.PASS, "m2", null, null));
			assertEquals(List.of(Integer.valueOf(2)), TrendReportHtml.changeMarkers(runs));
		}
	}

	@Nested
	class WriteReport {

		@Test
		@DisplayName("writes trend.html and a non-empty skilltest-report.css (resource on the class path)")
		void writesBoth(@TempDir Path dir) throws Exception {
			File out = dir.resolve("reports").toFile();
			assertTrue(TrendReportHtml.writeReport(out, "<html>x</html>", null));
			Path html = out.toPath().resolve(TrendReportHtml.FILE_NAME);
			Path css = out.toPath().resolve(ReportCss.FILE_NAME);
			assertAll(() -> assertEquals("<html>x</html>", Files.readString(html, StandardCharsets.UTF_8)),
					() -> assertTrue(Files.size(css) > 0, "stylesheet copied and non-empty"),
					() -> assertTrue(Files.readString(css, StandardCharsets.UTF_8).contains(".tr-alerts"),
							"it is the shared report stylesheet"));
		}

		@Test
		@DisplayName("an unwritable target returns false and does not throw")
		void unwritable(@TempDir Path dir) throws Exception {
			File notADir = dir.resolve("file.txt").toFile();
			Files.writeString(notADir.toPath(), "x");
			List<String> log = new ArrayList<>();
			boolean[] ok = new boolean[] { true };
			assertDoesNotThrow(() -> ok[0] = TrendReportHtml.writeReport(notADir, "<html/>", log::add));
			assertAll(() -> assertFalse(ok[0]), () -> assertFalse(log.isEmpty()),
					() -> assertFalse(TrendReportHtml.writeReport(null, "<html/>", null)));
		}
	}
}

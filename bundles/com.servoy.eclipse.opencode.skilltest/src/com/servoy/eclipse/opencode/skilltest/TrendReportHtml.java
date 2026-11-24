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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.servoy.eclipse.opencode.skilltest.report.ReportCss;

/**
 * Renders the skill-test run history as a CSP-safe trend report
 * ({@code trend.html}, SVY-21661).
 * <p>
 * <b>CSP-safe by construction</b> (Jenkins serves artifacts with
 * {@code default-src 'none'; img-src 'self'; style-src 'self'}): no JavaScript
 * at all (no {@code <script>}, no event-handler attributes, no
 * {@code javascript:} URLs), no inline CSS (no {@code <style>} block and no
 * inline style attributes, also not in the SVG), and no external network
 * resources. Styling comes only from {@code class} attributes plus the shared,
 * relatively linked {@code skilltest-report.css} ({@link ReportCss}). Charts
 * are inline {@code <svg>} markup coloured through presentation attributes
 * ({@code fill}, {@code stroke}, ...), with SVG {@code <title>} tooltips. The
 * page stays readable unstyled.
 * </p>
 */
public final class TrendReportHtml {

	/** The report file name. */
	public static final String FILE_NAME = "trend.html"; //$NON-NLS-1$

	static final int CHART_WIDTH = 600;
	static final int CHART_HEIGHT = 120;
	static final int PAD_LEFT = 36;
	static final int PAD_RIGHT = 8;
	static final int PAD_TOP = 8;
	static final int PAD_BOTTOM = 18;

	private TrendReportHtml() {
	}

	/** A chart point in SVG coordinates. */
	public record Point(double x, double y) {
	}

	/**
	 * Renders the full report.
	 *
	 * @param all    the full history in file order (may be {@code null})
	 * @param alerts the current alerts (may be {@code null})
	 * @param window the trend window (clamped to at least 1)
	 * @return a complete HTML document
	 */
	public static String render(List<RunHistory.Entry> all, List<TrendAlerts.Alert> alerts, int window) {
		int n = Math.max(1, window);
		List<RunHistory.Entry> entries = all != null ? all : List.of();
		List<TrendAlerts.Alert> alertList = alerts != null ? alerts : List.of();
		Map<String, List<RunHistory.Entry>> byBaseline = RunHistory.byBaseline(entries);
		Set<String> runIds = new LinkedHashSet<>();
		for (RunHistory.Entry e : entries) {
			runIds.add(e.runId());
		}
		RunHistory.Entry latest = entries.isEmpty() ? null : entries.get(entries.size() - 1);

		StringBuilder sb = new StringBuilder();
		// 1. Header.
		sb.append("<h1 class=\"tr-header\">Skill-test trend report<span class=\"tr-header-meta\">generated ") //$NON-NLS-1$
				.append(esc(Instant.now().toString())).append("</span></h1>"); //$NON-NLS-1$
		sb.append("<p class=\"muted\">"); //$NON-NLS-1$
		if (latest != null) {
			sb.append("Latest run: ").append(esc(latest.runId())); //$NON-NLS-1$
			if (latest.buildNumber() != null) {
				sb.append(" (build #").append(esc(latest.buildNumber())).append(')'); //$NON-NLS-1$
			}
			sb.append(" &middot; "); //$NON-NLS-1$
		}
		sb.append(runIds.size()).append(" run(s), ").append(byBaseline.size()) //$NON-NLS-1$
				.append(" baseline(s) in history &middot; trend window ").append(n).append(" runs</p>"); //$NON-NLS-1$ //$NON-NLS-2$

		// 2. Alerts banner.
		sb.append(alertsBanner(alertList));

		// 3. Summary table.
		sb.append("<h2>Summary</h2>"); //$NON-NLS-1$
		if (byBaseline.isEmpty()) {
			sb.append("<p class=\"muted\">No run history yet.</p>"); //$NON-NLS-1$
		} else {
			sb.append("<table class=\"tr-table\"><tr><th>Baseline</th><th>Latest status</th><th>Trend (last ") //$NON-NLS-1$
					.append(n).append(")</th><th>Runs</th><th>Last model</th></tr>"); //$NON-NLS-1$
			for (Map.Entry<String, List<RunHistory.Entry>> g : byBaseline.entrySet()) {
				List<RunHistory.Entry> list = g.getValue();
				RunHistory.Entry last = list.get(list.size() - 1);
				sb.append("<tr><td class=\"tr-id\">").append(esc(g.getKey())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td class=\"").append(statusClass(last.status())).append("\">") //$NON-NLS-1$ //$NON-NLS-2$
						.append(esc(String.valueOf(last.status()))).append("</td>"); //$NON-NLS-1$
				sb.append("<td>").append(esc(RunHistory.trend(list, n).label())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td>").append(list.size()).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
				sb.append("<td>").append(esc(last.model() != null ? last.model() : "\u2014")).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			sb.append("</table>"); //$NON-NLS-1$
		}

		// 4. Per baseline charts.
		for (Map.Entry<String, List<RunHistory.Entry>> g : byBaseline.entrySet()) {
			sb.append(baselineSection(g.getKey(), g.getValue(), n));
		}
		return page(sb.toString());
	}

	private static String alertsBanner(List<TrendAlerts.Alert> alerts) {
		if (alerts.isEmpty()) {
			return "<p class=\"tr-ok\">No trend alerts</p>"; //$NON-NLS-1$
		}
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"tr-alerts\"><div class=\"tr-alerts-title\">").append(alerts.size()) //$NON-NLS-1$
				.append(" trend alert(s)</div><ul>"); //$NON-NLS-1$
		for (TrendAlerts.Alert a : alerts) {
			sb.append("<li><span class=\"tr-id\">").append(esc(a.baselineId())).append("</span> ") //$NON-NLS-1$ //$NON-NLS-2$
					.append("<span class=\"tr-alert-rule\">").append(esc(a.rule())).append("</span>: ") //$NON-NLS-1$ //$NON-NLS-2$
					.append(esc(a.message()));
			if (a.likelyCause() != null) {
				sb.append(" <span class=\"tr-alert-cause\">(likely cause: ").append(esc(a.likelyCause())) //$NON-NLS-1$
						.append(")</span>"); //$NON-NLS-1$
			}
			sb.append("</li>"); //$NON-NLS-1$
		}
		sb.append("</ul></div>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static String baselineSection(String baselineId, List<RunHistory.Entry> list, int window) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"tr-baseline\"><h3 class=\"tr-id\">").append(esc(baselineId)).append("</h3>"); //$NON-NLS-1$ //$NON-NLS-2$
		List<RunHistory.Entry> runs = new ArrayList<>();
		for (RunHistory.Entry e : list) {
			if (e.status() != SkillTestResult.Status.SKIPPED) {
				runs.add(e);
			}
		}
		if (runs.size() < 2) {
			sb.append("<p class=\"muted\">Not enough history for charts yet (").append(runs.size()) //$NON-NLS-1$
					.append(" run).</p></div>"); //$NON-NLS-1$
			return sb.toString();
		}
		List<Integer> markers = changeMarkers(runs);
		List<String> labels = xLabels(runs);

		// Rolling pass rate (0..100) over the window.
		List<Double> passRate = rollingPassRate(runs, window);
		sb.append("<div class=\"tr-chart-title\">Pass rate % (rolling, last ").append(window).append(" runs)</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append(chart(List.of(passRate), new String[] { "#137333" }, 0d, 100d, labels, markers, runs, //$NON-NLS-1$
				"pass rate")); //$NON-NLS-1$

		// Tokens and credits delta over all runs (each scaled to its own maximum).
		List<Double> tokens = new ArrayList<>();
		List<Double> credits = new ArrayList<>();
		boolean anyTokens = false;
		boolean anyCredits = false;
		for (RunHistory.Entry e : runs) {
			tokens.add(e.totalTokens() != null ? Double.valueOf(e.totalTokens().doubleValue()) : null);
			credits.add(e.creditsDelta());
			anyTokens |= e.totalTokens() != null;
			anyCredits |= e.creditsDelta() != null;
		}
		sb.append("<div class=\"tr-chart-title\">Tokens / credits \u0394 (each scaled to its own maximum)</div>"); //$NON-NLS-1$
		if (!anyTokens && !anyCredits) {
			sb.append("<p class=\"muted\">No token or credit data recorded.</p>"); //$NON-NLS-1$
		} else {
			List<Double> tokensPct = normalize(tokens);
			List<Double> creditsPct = normalize(credits);
			sb.append(chart(List.of(tokensPct, creditsPct), new String[] { "#1a73e8", "#b06000" }, 0d, 100d, //$NON-NLS-1$ //$NON-NLS-2$
					labels, markers, runs, "cost")); //$NON-NLS-1$
			sb.append("<div class=\"tr-legend\">blue: tokens (max ").append(formatMax(tokens)) //$NON-NLS-1$
					.append("), amber: credits \u0394 (max ").append(formatMax(credits)) //$NON-NLS-1$
					.append("); dashed grey lines mark a model or skills version change</div>"); //$NON-NLS-1$
		}
		sb.append("</div>"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Rolling pass rate in percent: for run {@code i}, the pass rate of the
	 * {@code window} runs ending at {@code i}.
	 */
	static List<Double> rollingPassRate(List<RunHistory.Entry> runs, int window) {
		int n = Math.max(1, window);
		List<Double> out = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			int from = Math.max(0, i - n + 1);
			int passes = 0;
			for (int j = from; j <= i; j++) {
				if (runs.get(j).status() == SkillTestResult.Status.PASS) {
					passes++;
				}
			}
			out.add(Double.valueOf(passes * 100d / (i - from + 1)));
		}
		return out;
	}

	/** Scales values to 0..100 of their own maximum; {@code null} stays null. */
	static List<Double> normalize(List<Double> values) {
		double max = 0;
		for (Double v : values) {
			if (v != null && v.doubleValue() > max) {
				max = v.doubleValue();
			}
		}
		List<Double> out = new ArrayList<>();
		for (Double v : values) {
			out.add(v == null ? null : Double.valueOf(max > 0 ? v.doubleValue() * 100d / max : 0d));
		}
		return out;
	}

	/**
	 * @return the indices of runs whose model or skillsVersion differs from the
	 *         previous run
	 */
	static List<Integer> changeMarkers(List<RunHistory.Entry> runs) {
		List<Integer> out = new ArrayList<>();
		for (int i = 1; i < runs.size(); i++) {
			RunHistory.Entry a = runs.get(i - 1);
			RunHistory.Entry b = runs.get(i);
			if (!Objects.equals(a.model(), b.model()) || !Objects.equals(a.skillsVersion(), b.skillsVersion())) {
				out.add(Integer.valueOf(i));
			}
		}
		return out;
	}

	private static List<String> xLabels(List<RunHistory.Entry> runs) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			String b = runs.get(i).buildNumber();
			out.add(b != null ? "#" + b : Integer.toString(i + 1)); //$NON-NLS-1$
		}
		return out;
	}

	/**
	 * Scales a series index/value to SVG coordinates. {@code count} points are
	 * spread evenly over the plot width; {@code value} is mapped linearly from
	 * {@code [min,max]} to the plot height (higher value = smaller y).
	 */
	static Point scale(int index, int count, double value, double min, double max) {
		double plotW = CHART_WIDTH - PAD_LEFT - PAD_RIGHT;
		double plotH = CHART_HEIGHT - PAD_TOP - PAD_BOTTOM;
		double x = count <= 1 ? PAD_LEFT + plotW / 2d : PAD_LEFT + plotW * index / (count - 1);
		double range = max - min;
		double clamped = Math.max(min, Math.min(max, value));
		double frac = range > 0 ? (clamped - min) / range : 0d;
		double y = PAD_TOP + plotH * (1d - frac);
		return new Point(x, y);
	}

	/**
	 * Builds the {@code points} attribute value of an SVG polyline; {@code null}
	 * values are skipped.
	 */
	static String polylinePoints(List<Double> values, double min, double max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			Double v = values.get(i);
			if (v == null) {
				continue;
			}
			Point p = scale(i, values.size(), v.doubleValue(), min, max);
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(fmt(p.x())).append(',').append(fmt(p.y()));
		}
		return sb.toString();
	}

	private static String chart(List<List<Double>> series, String[] colors, double min, double max, List<String> labels,
			List<Integer> markers, List<RunHistory.Entry> runs, String what) {
		int count = labels.size();
		StringBuilder sb = new StringBuilder();
		sb.append("<svg class=\"tr-chart\" width=\"").append(CHART_WIDTH) //$NON-NLS-1$
				.append("\" height=\"").append(CHART_HEIGHT).append("\" viewBox=\"0 0 ").append(CHART_WIDTH) //$NON-NLS-1$ //$NON-NLS-2$
				.append(' ').append(CHART_HEIGHT).append("\" role=\"img\"><title>").append(esc(what)) //$NON-NLS-1$
				.append("</title>"); //$NON-NLS-1$
		// Axes and grid.
		double top = scale(0, count, max, min, max).y();
		double bottom = scale(0, count, min, min, max).y();
		double right = CHART_WIDTH - PAD_RIGHT;
		sb.append(line(PAD_LEFT, top, right, top, "#eeeeee", null)); //$NON-NLS-1$
		sb.append(line(PAD_LEFT, bottom, right, bottom, "#bdc1c6", null)); //$NON-NLS-1$
		sb.append(text(PAD_LEFT - 4, top + 3, "end", formatNumber(max))); //$NON-NLS-1$
		sb.append(text(PAD_LEFT - 4, bottom + 3, "end", formatNumber(min))); //$NON-NLS-1$
		// Change markers.
		for (Integer m : markers) {
			Point p = scale(m.intValue(), count, min, min, max);
			RunHistory.Entry prev = runs.get(m.intValue() - 1);
			RunHistory.Entry cur = runs.get(m.intValue());
			sb.append("<line x1=\"").append(fmt(p.x())).append("\" y1=\"").append(fmt(top)).append("\" x2=\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					.append(fmt(p.x())).append("\" y2=\"").append(fmt(bottom)) //$NON-NLS-1$
					.append("\" stroke=\"#9aa0a6\" stroke-width=\"1\" stroke-dasharray=\"3,3\"><title>") //$NON-NLS-1$
					.append(esc(changeText(prev, cur))).append("</title></line>"); //$NON-NLS-1$
		}
		// Series.
		for (int s = 0; s < series.size(); s++) {
			List<Double> values = series.get(s);
			String color = colors[s % colors.length];
			String pts = polylinePoints(values, min, max);
			if (!pts.isEmpty()) {
				sb.append("<polyline fill=\"none\" stroke=\"").append(color) //$NON-NLS-1$
						.append("\" stroke-width=\"2\" points=\"").append(pts).append("\"/>"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			for (int i = 0; i < values.size(); i++) {
				Double v = values.get(i);
				if (v == null) {
					continue;
				}
				Point p = scale(i, count, v.doubleValue(), min, max);
				sb.append("<circle cx=\"").append(fmt(p.x())).append("\" cy=\"").append(fmt(p.y())) //$NON-NLS-1$ //$NON-NLS-2$
						.append("\" r=\"2.5\" fill=\"").append(color).append("\"><title>") //$NON-NLS-1$ //$NON-NLS-2$
						.append(esc(labels.get(i) + " " + runs.get(i).status() + ": " + formatNumber(v.doubleValue()))) //$NON-NLS-1$ //$NON-NLS-2$
						.append("</title></circle>"); //$NON-NLS-1$
			}
		}
		// X labels (thinned to at most ~12).
		int step = Math.max(1, (int) Math.ceil(count / 12d));
		for (int i = 0; i < count; i += step) {
			Point p = scale(i, count, min, min, max);
			sb.append(text(p.x(), CHART_HEIGHT - 4, "middle", labels.get(i))); //$NON-NLS-1$
		}
		sb.append("</svg>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static String changeText(RunHistory.Entry prev, RunHistory.Entry cur) {
		List<String> parts = new ArrayList<>();
		if (!Objects.equals(prev.model(), cur.model())) {
			parts.add("model " + prev.model() + " \u2192 " + cur.model()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!Objects.equals(prev.skillsVersion(), cur.skillsVersion())) {
			parts.add("skills " + prev.skillsVersion() + " \u2192 " + cur.skillsVersion()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return String.join("; ", parts); //$NON-NLS-1$
	}

	private static String line(double x1, double y1, double x2, double y2, String stroke, String title) {
		StringBuilder sb = new StringBuilder();
		sb.append("<line x1=\"").append(fmt(x1)).append("\" y1=\"").append(fmt(y1)).append("\" x2=\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append(fmt(x2)).append("\" y2=\"").append(fmt(y2)).append("\" stroke=\"").append(stroke) //$NON-NLS-1$ //$NON-NLS-2$
				.append("\" stroke-width=\"1\""); //$NON-NLS-1$
		if (title != null) {
			sb.append("><title>").append(esc(title)).append("</title></line>"); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			sb.append("/>"); //$NON-NLS-1$
		}
		return sb.toString();
	}

	private static String text(double x, double y, String anchor, String value) {
		return "<text x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" text-anchor=\"" + anchor //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ "\" fill=\"#5f6368\" font-size=\"9\">" + esc(value) + "</text>"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static String formatMax(List<Double> values) {
		double max = 0;
		boolean any = false;
		for (Double v : values) {
			if (v != null) {
				any = true;
				max = Math.max(max, v.doubleValue());
			}
		}
		return any ? formatNumber(max) : "\u2014"; //$NON-NLS-1$
	}

	private static String formatNumber(double d) {
		return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.2f", d); //$NON-NLS-1$
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.1f", d); //$NON-NLS-1$
	}

	private static String statusClass(SkillTestResult.Status status) {
		if (status == null) {
			return "tr-s-skipped"; //$NON-NLS-1$
		}
		return switch (status) {
		case PASS -> "tr-s-pass"; //$NON-NLS-1$
		case FAIL -> "tr-s-fail"; //$NON-NLS-1$
		case ERROR -> "tr-s-error"; //$NON-NLS-1$
		case SKIPPED -> "tr-s-skipped"; //$NON-NLS-1$
		};
	}

	private static String page(String body) {
		return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Skill-test trend report</title>" //$NON-NLS-1$
				+ ReportCss.linkTag() + "</head><body>" + body + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Writes {@code trend.html} and {@code skilltest-report.css} into
	 * {@code outDir}. Best-effort, never throws.
	 *
	 * @param outDir the target directory
	 * @param html   the rendered page
	 * @param log    diagnostic sink (may be {@code null})
	 * @return {@code true} when both files were written
	 */
	public static boolean writeReport(File outDir, String html, Consumer<String> log) {
		try {
			if (outDir == null) {
				return false;
			}
			Path dir = outDir.toPath();
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(FILE_NAME), html != null ? html : "", StandardCharsets.UTF_8); //$NON-NLS-1$
			boolean css = ReportCss.writeCss(dir, log);
			if (log != null) {
				log.accept("Wrote skill-test trend report: " + dir.resolve(FILE_NAME)); //$NON-NLS-1$
			}
			return css;
		} catch (IOException | RuntimeException e) {
			if (log != null) {
				try {
					log.accept("Could not write skill-test trend report into " + outDir + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (RuntimeException ignored) {
					// never break the caller
				}
			}
			return false;
		}
	}

	/** HTML escaping for text and attribute values. */
	static String esc(String s) {
		return RunHistory.esc(s);
	}
}

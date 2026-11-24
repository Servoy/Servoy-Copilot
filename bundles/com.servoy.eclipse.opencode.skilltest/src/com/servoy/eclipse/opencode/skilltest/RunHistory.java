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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Append-only skill-test run history (SVY-21661).
 * <p>
 * Every completed run - from the IDE Skill Test view or the headless CI runner
 * - appends <b>one compact JSON line per baseline result</b> to a
 * {@code run-history.jsonl} file (see {@link SkillTestPaths#historyFile()}).
 * All lines of one run share a {@code runId}. The line holds only run identity,
 * status, attempts/trials and a compact metrics subset - never prompt text,
 * diffs, tool arguments/outputs or credentials.
 * </p>
 * <p>
 * Everything here is best-effort: {@link #append} never throws, and
 * {@link #read} skips blank, corrupt, partially written or incomplete lines.
 * </p>
 */
public final class RunHistory {

	/** Schema version written into every line. */
	public static final int SCHEMA_VERSION = 1;

	/** Default number of runs a trend covers. */
	public static final int DEFAULT_TREND_RUNS = 10;

	/** Lower bound of the configurable trend window. */
	public static final int MIN_TREND_RUNS = 1;

	/** Upper bound of the configurable trend window. */
	public static final int MAX_TREND_RUNS = 1000;

	/** Source value for runs started from the IDE view. */
	public static final String SOURCE_IDE = "ide"; //$NON-NLS-1$

	/** Source value for runs started by the headless CI runner. */
	public static final String SOURCE_CI = "ci"; //$NON-NLS-1$

	/** System property carrying the skills version (e.g. skill4servoy commit). */
	public static final String SKILLS_VERSION_PROPERTY = "servoy.skilltest.skillsVersion"; //$NON-NLS-1$

	/** Env var carrying the skills version when the property is not set. */
	public static final String SKILLS_VERSION_ENV = "SKILLTEST_SKILLS_VERSION"; //$NON-NLS-1$

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String NO_TREND = "\u2014"; //$NON-NLS-1$

	private RunHistory() {
	}

	/**
	 * Identity of one run, shared by all its history lines.
	 *
	 * @param source        {@code ide} or {@code ci}
	 * @param timestamp     the run instant (UTC)
	 * @param buildNumber   CI build number, or {@code null}
	 * @param job           CI job name, or {@code null}
	 * @param commit        CI git commit, or {@code null}
	 * @param pluginVersion skilltest bundle version, or {@code null}
	 * @param skillsVersion skills version, or {@code null}
	 */
	public record RunIdentity(String source, Instant timestamp, String buildNumber, String job, String commit,
			String pluginVersion, String skillsVersion) {

		/** @return the identity of an IDE run (no build fields) */
		public static RunIdentity ide() {
			return ide(System::getenv);
		}

		/**
		 * @param env env lookup (testable)
		 * @return the identity of an IDE run (no build fields)
		 */
		public static RunIdentity ide(Function<String, String> env) {
			return new RunIdentity(SOURCE_IDE, Instant.now(), null, null, null, bundleVersion(),
					RunHistory.skillsVersion(System::getProperty, env));
		}

		/** @return the identity of a CI run, reading Jenkins env vars */
		public static RunIdentity ci() {
			return ci(System::getenv);
		}

		/**
		 * @param env env lookup (testable)
		 * @return the identity of a CI run
		 */
		public static RunIdentity ci(Function<String, String> env) {
			return new RunIdentity(SOURCE_CI, Instant.now(), lookup(env, "BUILD_NUMBER"), //$NON-NLS-1$
					lookup(env, "JOB_NAME"), lookup(env, "GIT_COMMIT"), bundleVersion(), //$NON-NLS-1$ //$NON-NLS-2$
					RunHistory.skillsVersion(System::getProperty, env));
		}
	}

	/**
	 * One parsed history line.
	 */
	public record Entry(String runId, Instant timestamp, String source, String buildNumber, String baselineId,
			SkillTestResult.Status status, Integer trialsPassed, Integer trialsTotal, String agent, String model,
			String pluginVersion, String skillsVersion, Long totalTokens, Integer toolCalls, Double creditsDelta,
			Double estCredits) {
	}

	/**
	 * Pass-rate trend of one baseline over its most recent runs.
	 *
	 * @param runs       non-skipped runs considered
	 * @param passes     PASS runs among them
	 * @param passRate   {@code passes / runs} (0 when no runs)
	 * @param sparkline  status glyphs oldest to newest
	 * @param avgTokens  average total tokens of the considered runs that have it,
	 *                   or {@code null}
	 * @param lastTokens total tokens of the newest considered run, or {@code null}
	 */
	public record Trend(int runs, int passes, double passRate, String sparkline, Long avgTokens, Long lastTokens) {

		/** @return e.g. {@code "80% (4/5) ✓✓✗✓✓"}, or {@code "—"} with no runs */
		public String label() {
			if (runs <= 0) {
				return NO_TREND;
			}
			return Math.round(passRate * 100) + "% (" + passes + "/" + runs + ") " + sparkline; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	// --- identity helpers -----------------------------------------------------

	private static String lookup(Function<String, String> f, String key) {
		if (f == null) {
			return null;
		}
		try {
			String v = f.apply(key);
			return v == null || v.isBlank() ? null : v.trim();
		} catch (RuntimeException e) {
			return null;
		}
	}

	static String skillsVersion(Function<String, String> props, Function<String, String> env) {
		String v = lookup(props, SKILLS_VERSION_PROPERTY);
		return v != null ? v : lookup(env, SKILLS_VERSION_ENV);
	}

	private static String bundleVersion() {
		try {
			org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(RunHistory.class);
			return bundle != null ? bundle.getVersion().toString() : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * @param id the run identity
	 * @return {@code <timestamp>-<source>[-<buildNumber>]}
	 */
	public static String runId(RunIdentity id) {
		if (id == null) {
			return Instant.now().toString();
		}
		StringBuilder sb = new StringBuilder();
		sb.append(id.timestamp() != null ? id.timestamp().toString() : Instant.now().toString());
		sb.append('-').append(id.source());
		if (id.buildNumber() != null && !id.buildNumber().isBlank()) {
			sb.append('-').append(id.buildNumber());
		}
		return sb.toString();
	}

	// --- writing --------------------------------------------------------------

	/**
	 * Builds the single compact JSON line (without trailing newline) for one
	 * result. Unknown values are omitted, never written as {@code null}/0.
	 *
	 * @param r     the result
	 * @param id    the run identity
	 * @param runId the shared run id
	 * @param model the model the baseline ran with, or {@code null}
	 * @return the JSON line (contains no line breaks)
	 */
	public static String toJsonLine(SkillTestResult r, RunIdentity id, String runId, String model) {
		ObjectNode n = MAPPER.createObjectNode();
		n.put("v", SCHEMA_VERSION); //$NON-NLS-1$
		n.put("runId", runId); //$NON-NLS-1$
		n.put("timestamp", id.timestamp() != null ? id.timestamp().toString() : Instant.now().toString()); //$NON-NLS-1$
		n.put("source", id.source()); //$NON-NLS-1$
		putIfPresent(n, "buildNumber", id.buildNumber()); //$NON-NLS-1$
		putIfPresent(n, "job", id.job()); //$NON-NLS-1$
		putIfPresent(n, "commit", id.commit()); //$NON-NLS-1$
		putIfPresent(n, "pluginVersion", id.pluginVersion()); //$NON-NLS-1$
		putIfPresent(n, "skillsVersion", id.skillsVersion()); //$NON-NLS-1$
		n.put("baselineId", r.getBaselineId()); //$NON-NLS-1$
		n.put("status", r.getStatus().name()); //$NON-NLS-1$
		if (r.getAttempts() > 0) {
			n.put("attempts", r.getAttempts()); //$NON-NLS-1$
		}
		if (r.getTrials() != null && !r.getTrials().isEmpty()) {
			ObjectNode t = n.putObject("trials"); //$NON-NLS-1$
			t.put("passed", r.getPassCount()); //$NON-NLS-1$
			t.put("total", r.getTrials().size()); //$NON-NLS-1$
		}
		putIfPresent(n, "agent", r.getAgent()); //$NON-NLS-1$
		putIfPresent(n, "model", model); //$NON-NLS-1$
		RunMetrics m = r.getMetrics();
		if (m != null) {
			ObjectNode mn = n.putObject("metrics"); //$NON-NLS-1$
			mn.put("toolCalls", m.toolCalls()); //$NON-NLS-1$
			RunMetrics.TokenUsage tok = m.tokens();
			if (tok != null) {
				ObjectNode tn = mn.putObject("tokens"); //$NON-NLS-1$
				tn.put("input", tok.input()); //$NON-NLS-1$
				tn.put("output", tok.output()); //$NON-NLS-1$
				tn.put("reasoning", tok.reasoning()); //$NON-NLS-1$
				tn.put("cacheRead", tok.cacheRead()); //$NON-NLS-1$
				tn.put("cacheWrite", tok.cacheWrite()); //$NON-NLS-1$
			}
			RunMetrics.KiroUsage k = m.kiro();
			if (k != null) {
				ObjectNode kn = mn.putObject("kiro"); //$NON-NLS-1$
				kn.put("requests", k.requests()); //$NON-NLS-1$
				kn.put("estCredits", k.estCredits()); //$NON-NLS-1$
			}
			if (m.creditsDelta() != null) {
				mn.put("creditsDelta", m.creditsDelta().doubleValue()); //$NON-NLS-1$
			}
		}
		try {
			return MAPPER.writeValueAsString(n);
		} catch (IOException e) {
			// ObjectNode serialisation cannot realistically fail; keep the signature
			// free of checked exceptions.
			throw new IllegalStateException(e);
		}
	}

	private static void putIfPresent(ObjectNode n, String field, String value) {
		if (value != null && !value.isBlank()) {
			n.put(field, value);
		}
	}

	/**
	 * Appends one line per result to the history file. Never throws.
	 *
	 * @param file            the history file (parents are created)
	 * @param results         the run's results; null/empty is a no-op
	 * @param id              the run identity
	 * @param modelByBaseline baseline id to model (may be {@code null} or return
	 *                        {@code null})
	 * @param log             diagnostic sink (may be {@code null})
	 * @return {@code true} when written (or nothing to write), {@code false} on
	 *         failure
	 */
	public static boolean append(File file, List<SkillTestResult> results, RunIdentity id,
			Function<String, String> modelByBaseline, Consumer<String> log) {
		return append(file, results, id, runId(id), modelByBaseline, log);
	}

	/**
	 * As {@link #append(File, List, RunIdentity, Function, Consumer)} but with an
	 * explicit {@code runId} (so the caller knows the run id to evaluate alerts
	 * for). Never throws.
	 */
	public static boolean append(File file, List<SkillTestResult> results, RunIdentity id, String runId,
			Function<String, String> modelByBaseline, Consumer<String> log) {
		if (results == null || results.isEmpty()) {
			return true;
		}
		try {
			if (file == null || id == null) {
				logMsg(log, "Run history not written: no history file / run identity."); //$NON-NLS-1$
				return false;
			}
			StringBuilder sb = new StringBuilder();
			int written = 0;
			for (SkillTestResult r : results) {
				if (r == null || r.getBaselineId() == null || r.getStatus() == null) {
					continue;
				}
				String model = null;
				if (modelByBaseline != null) {
					try {
						model = modelByBaseline.apply(r.getBaselineId());
					} catch (RuntimeException e) {
						model = null;
					}
				}
				try {
					sb.append(toJsonLine(r, id, runId, model)).append('\n');
					written++;
				} catch (RuntimeException e) {
					// One malformed result must not drop the rest of the run's history.
					logMsg(log, "Skipped run history line for " + r.getBaselineId() + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			if (sb.length() == 0) {
				return true;
			}
			File parent = file.getAbsoluteFile().getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.READ); FileLock lock = ch.lock()) {
				long size = ch.size();
				String payload = sb.toString();
				if (size > 0) {
					ByteBuffer last = ByteBuffer.allocate(1);
					ch.read(last, size - 1);
					if (last.get(0) != (byte) '\n') {
						// A previous write was cut off: isolate the fragment on its own line.
						payload = "\n" + payload; //$NON-NLS-1$
					}
				}
				ByteBuffer buf = ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));
				long pos = size;
				while (buf.hasRemaining()) {
					pos += ch.write(buf, pos);
				}
				ch.force(false);
			}
			logMsg(log, "Appended " + written + " line(s) to run history: " + file); //$NON-NLS-1$ //$NON-NLS-2$
			return true;
		} catch (IOException | RuntimeException e) {
			logMsg(log, "Could not append skill-test run history to " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
	}

	private static void logMsg(Consumer<String> log, String msg) {
		if (log != null) {
			try {
				log.accept(msg);
			} catch (RuntimeException ignored) {
				// logging must never break the caller
			}
		}
	}

	// --- reading --------------------------------------------------------------

	/**
	 * Reads the history in file order. Missing/unreadable file gives an empty list;
	 * bad lines are skipped. Never throws.
	 *
	 * @param file the history file (may be {@code null})
	 * @return the parsed entries
	 */
	public static List<Entry> read(File file) {
		List<Entry> entries = new ArrayList<>();
		if (file == null || !file.isFile()) {
			return entries;
		}
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				Entry e = parseLine(line);
				if (e != null) {
					entries.add(e);
				}
			}
		} catch (IOException | RuntimeException e) {
			// Degrade to what was read so far.
		}
		return entries;
	}

	/**
	 * Parses one history line, or {@code null} when blank, corrupt, missing a
	 * required field or carrying an unknown status.
	 */
	static Entry parseLine(String line) {
		if (line == null || line.isBlank()) {
			return null;
		}
		JsonNode n;
		try {
			n = MAPPER.readTree(line);
		} catch (IOException | RuntimeException e) {
			return null;
		}
		if (n == null || !n.isObject()) {
			return null;
		}
		String runId = text(n, "runId"); //$NON-NLS-1$
		String ts = text(n, "timestamp"); //$NON-NLS-1$
		String source = text(n, "source"); //$NON-NLS-1$
		String baselineId = text(n, "baselineId"); //$NON-NLS-1$
		String statusText = text(n, "status"); //$NON-NLS-1$
		if (!n.has("v") || runId == null || ts == null || source == null || baselineId == null //$NON-NLS-1$
				|| statusText == null) {
			return null;
		}
		Instant timestamp;
		SkillTestResult.Status status;
		try {
			timestamp = Instant.parse(ts);
			status = SkillTestResult.Status.valueOf(statusText);
		} catch (DateTimeParseException | IllegalArgumentException e) {
			return null;
		}
		Integer trialsPassed = null;
		Integer trialsTotal = null;
		JsonNode trials = n.get("trials"); //$NON-NLS-1$
		if (trials != null && trials.isObject() && trials.has("passed") && trials.has("total")) { //$NON-NLS-1$ //$NON-NLS-2$
			trialsPassed = Integer.valueOf(trials.get("passed").asInt()); //$NON-NLS-1$
			trialsTotal = Integer.valueOf(trials.get("total").asInt()); //$NON-NLS-1$
		}
		Long totalTokens = null;
		Integer toolCalls = null;
		Double creditsDelta = null;
		Double estCredits = null;
		JsonNode m = n.get("metrics"); //$NON-NLS-1$
		if (m != null && m.isObject()) {
			if (m.has("toolCalls")) { //$NON-NLS-1$
				toolCalls = Integer.valueOf(m.get("toolCalls").asInt()); //$NON-NLS-1$
			}
			JsonNode t = m.get("tokens"); //$NON-NLS-1$
			if (t != null && t.isObject()) {
				totalTokens = Long.valueOf(t.path("input").asLong() + t.path("output").asLong() //$NON-NLS-1$ //$NON-NLS-2$
						+ t.path("reasoning").asLong()); //$NON-NLS-1$
			}
			JsonNode k = m.get("kiro"); //$NON-NLS-1$
			if (k != null && k.isObject() && k.has("estCredits")) { //$NON-NLS-1$
				estCredits = Double.valueOf(k.get("estCredits").asDouble()); //$NON-NLS-1$
			}
			if (m.has("creditsDelta")) { //$NON-NLS-1$
				creditsDelta = Double.valueOf(m.get("creditsDelta").asDouble()); //$NON-NLS-1$
			}
		}
		return new Entry(runId, timestamp, source, text(n, "buildNumber"), baselineId, status, trialsPassed, //$NON-NLS-1$
				trialsTotal, text(n, "agent"), text(n, "model"), text(n, "pluginVersion"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				text(n, "skillsVersion"), totalTokens, toolCalls, creditsDelta, estCredits); //$NON-NLS-1$
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String s = value.asText();
		return s == null || s.isBlank() ? null : s;
	}

	// --- trends ---------------------------------------------------------------

	/**
	 * Groups entries by baseline, preserving file (chronological) order.
	 *
	 * @param entries the history entries (may be {@code null})
	 * @return baseline id to its entries
	 */
	public static Map<String, List<Entry>> byBaseline(List<Entry> entries) {
		Map<String, List<Entry>> map = new LinkedHashMap<>();
		if (entries != null) {
			for (Entry e : entries) {
				if (e != null) {
					map.computeIfAbsent(e.baselineId(), k -> new ArrayList<>()).add(e);
				}
			}
		}
		return map;
	}

	/**
	 * Computes the per-baseline trend over the most recent {@code lastN}
	 * non-skipped entries.
	 *
	 * @param entries history entries in file order
	 * @param lastN   window size (values &lt; 1 are clamped to 1)
	 * @return baseline id to trend
	 */
	public static Map<String, Trend> trends(List<Entry> entries, int lastN) {
		int n = Math.max(1, lastN);
		Map<String, Trend> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<Entry>> e : byBaseline(entries).entrySet()) {
			out.put(e.getKey(), trend(e.getValue(), n));
		}
		return out;
	}

	/**
	 * Computes one baseline's trend over its most recent {@code lastN} non-skipped
	 * entries (entries in chronological order).
	 */
	public static Trend trend(List<Entry> baselineEntries, int lastN) {
		List<Entry> considered = lastNonSkipped(baselineEntries, Math.max(1, lastN));
		int passes = 0;
		StringBuilder spark = new StringBuilder();
		long tokenSum = 0;
		int tokenCount = 0;
		Long lastTokens = null;
		for (Entry e : considered) {
			if (e.status() == SkillTestResult.Status.PASS) {
				passes++;
			}
			spark.append(glyph(e.status()));
			if (e.totalTokens() != null) {
				tokenSum += e.totalTokens().longValue();
				tokenCount++;
			}
		}
		if (!considered.isEmpty()) {
			lastTokens = considered.get(considered.size() - 1).totalTokens();
		}
		int runs = considered.size();
		double rate = runs > 0 ? (double) passes / runs : 0d;
		Long avg = tokenCount > 0 ? Long.valueOf(Math.round((double) tokenSum / tokenCount)) : null;
		return new Trend(runs, passes, rate, spark.toString(), avg, lastTokens);
	}

	/** @return the last {@code n} non-skipped entries, oldest first */
	static List<Entry> lastNonSkipped(List<Entry> entries, int n) {
		List<Entry> nonSkipped = new ArrayList<>();
		if (entries != null) {
			for (Entry e : entries) {
				if (e != null && e.status() != SkillTestResult.Status.SKIPPED) {
					nonSkipped.add(e);
				}
			}
		}
		int from = Math.max(0, nonSkipped.size() - n);
		return new ArrayList<>(nonSkipped.subList(from, nonSkipped.size()));
	}

	/** @return the sparkline glyph for a status */
	static String glyph(SkillTestResult.Status status) {
		if (status == null) {
			return "?"; //$NON-NLS-1$
		}
		return switch (status) {
		case PASS -> "\u2713"; //$NON-NLS-1$
		case FAIL -> "\u2717"; //$NON-NLS-1$
		case ERROR -> "!"; //$NON-NLS-1$
		case SKIPPED -> "-"; //$NON-NLS-1$
		};
	}

	// --- trend window ---------------------------------------------------------

	/**
	 * Parses a user-entered trend window.
	 *
	 * @param raw the raw text
	 * @return the value when it is an integer in 1..1000, else {@code null}
	 */
	public static Integer parseTrendRuns(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			int v = Integer.parseInt(raw.trim());
			return v >= MIN_TREND_RUNS && v <= MAX_TREND_RUNS ? Integer.valueOf(v) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** @return {@code value} clamped into 1..1000 */
	public static int clampTrendRuns(int value) {
		return Math.max(MIN_TREND_RUNS, Math.min(MAX_TREND_RUNS, value));
	}

	// --- detail panel HTML ----------------------------------------------------

	/**
	 * Builds the "History (last N runs)" HTML fragment for one baseline, newest
	 * first. Uses classes of the detail panel's own stylesheet. All values are
	 * HTML-escaped.
	 *
	 * @param entries    the full history
	 * @param baselineId the selected baseline
	 * @param lastN      the number of runs to show (clamped to at least 1)
	 * @return an HTML fragment
	 */
	public static String historyHtml(List<Entry> entries, String baselineId, int lastN) {
		int n = Math.max(1, lastN);
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"section-title\">History (last ").append(n).append(" runs)</div>"); //$NON-NLS-1$ //$NON-NLS-2$
		List<Entry> mine = byBaseline(entries).getOrDefault(baselineId, Collections.emptyList());
		if (mine.isEmpty()) {
			sb.append("<p class=\"muted\">No run history yet.</p>"); //$NON-NLS-1$
			return sb.toString();
		}
		List<Entry> recent = new ArrayList<>(mine.subList(Math.max(0, mine.size() - n), mine.size()));
		Collections.reverse(recent);
		sb.append("<table class=\"args\"><tr>"); //$NON-NLS-1$
		for (String h : new String[] { "Timestamp", "Source", "Status", "Trials", "Model", "Tokens", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				"Credits \u0394" }) { //$NON-NLS-1$
			sb.append("<td class=\"k\">").append(esc(h)).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sb.append("</tr>"); //$NON-NLS-1$
		for (Entry e : recent) {
			sb.append("<tr>"); //$NON-NLS-1$
			cell(sb, e.timestamp() != null ? e.timestamp().toString() : null);
			cell(sb, e.source() + (e.buildNumber() != null ? " #" + e.buildNumber() : "")); //$NON-NLS-1$ //$NON-NLS-2$
			cell(sb, e.status() != null ? e.status().name() : null);
			cell(sb, e.trialsTotal() != null ? e.trialsPassed() + "/" + e.trialsTotal() : null); //$NON-NLS-1$
			cell(sb, e.model());
			cell(sb, e.totalTokens() != null ? e.totalTokens().toString() : null);
			cell(sb, e.creditsDelta() != null ? String.format(java.util.Locale.ROOT, "%.2f", e.creditsDelta()) : null); //$NON-NLS-1$
			sb.append("</tr>"); //$NON-NLS-1$
		}
		sb.append("</table>"); //$NON-NLS-1$
		return sb.toString();
	}

	private static void cell(StringBuilder sb, String value) {
		sb.append("<td class=\"v\">").append(value != null ? esc(value) : "\u2014").append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Minimal HTML escaping (same approach as {@code SkillTestHtml.esc}). */
	static String esc(String s) {
		if (s == null) {
			return ""; //$NON-NLS-1$
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
				.replace("'", "&#39;"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}

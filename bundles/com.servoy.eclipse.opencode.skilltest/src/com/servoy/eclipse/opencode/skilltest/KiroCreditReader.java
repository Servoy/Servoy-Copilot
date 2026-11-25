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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Reads the <b>accurate</b> per-scenario Kiro credit figures written by the
 * {@code @servoy/opencode-kiro-auth} plugin (SVY-21660 expansion A/B).
 * <p>
 * The two credit figures live in <b>two different stores</b>, confirmed against
 * live data:
 * </p>
 * <ul>
 * <li><b>Account used/limit → {@code kiro-usage.json}.</b> The account's
 * available-credit figure ({@code accounts[].used} / {@code .limit} /
 * {@code .pct}) exists <b>only</b> in the JSON snapshot: {@code kiro.db} has no
 * account table, only {@code session_usage}. So {@link #accountUsed()} reads
 * the JSON {@code accounts[].used} (summed across accounts).
 * {@link #snapshotStamp()} exposes the snapshot's freshness
 * ({@code accounts[].updatedAt}, else top-level {@code writtenAt}) so the
 * runner can wait for a post-run sync before reading the end of the
 * bracket.</li>
 * <li><b>Per-session requests/est_credits → {@code kiro.db}
 * {@code session_usage}.</b> {@link #usageForSessions(Collection)} sums
 * {@code requests} / {@code est_credits} over the scenario's root session and
 * its subagent sessions. This is the scenario's metered cost, the figure the
 * reports use. The account used/limit bracket is shown for information
 * only.</li>
 * </ul>
 * <p>
 * When {@code KIRO_CONFIG_DIR} is set it is the only dir probed, so a file from
 * another location can never be read in its place.
 * </p>
 * <p>
 * Separate from {@link KiroUsageReader} (which keeps reading the per-session
 * {@code estCredits} estimate from {@code kiro-usage.json} for its own
 * iteration-1 purpose).
 * </p>
 * <p>
 * Config-dir source of truth: the dir is resolved through
 * {@link com.servoy.eclipse.opencode.OpenCodeUtil#getServoyOpencodeConfigDir()}
 * - the exact method the opencode bundle itself uses to decide where to write
 * {@code kiro.db} / {@code kiro-usage.json} - so the writer and this reader
 * agree by construction in the same JVM. A secondary fallback probe covers a
 * no-OSGi {@code plainjunit} test (where the method returns no usable dir), and
 * the candidate-dir list is injectable so a test can point it at a temp dir
 * with fixtures.
 * </p>
 * <p>
 * The DB is opened <b>read-only</b> ({@code mode=ro}, which still reads the
 * plugin's WAL), and every DB / file access is wrapped so an absent
 * driver / absent file / locked DB / malformed row / malformed JSON degrades to
 * {@link OptionalDouble#empty()} ("unknown"). <b>No network, no token/bearer
 * read, never throws into the run.</b>
 * </p>
 */
public final class KiroCreditReader {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static {
		// Best-effort explicit driver registration: under OSGi the JDBC
		// service-loader may not pick the Xerial driver up automatically. Guarded and
		// non-fatal - a failure here just means every read degrades to "unknown".
		try {
			Class.forName("org.sqlite.JDBC"); //$NON-NLS-1$
		} catch (Throwable t) { // NOSONAR - deliberately swallow: absent driver -> "unknown"
			// ignore: readers degrade to empty when the driver is unavailable
		}
	}

	/** The ordered candidate config dirs to probe; first existing wins. */
	private final List<File> candidateDirs;

	/**
	 * Production reader: resolves the config dir from the single source of truth.
	 */
	public KiroCreditReader() {
		this(defaultCandidateDirs());
	}

	/**
	 * Test/seam constructor: probes exactly the given candidate dirs in order.
	 *
	 * @param candidateDirs the ordered candidate config dirs (first existing wins)
	 */
	public KiroCreditReader(List<File> candidateDirs) {
		this.candidateDirs = candidateDirs == null ? List.of() : List.copyOf(candidateDirs);
	}

	/**
	 * Builds the ordered candidate config-dir list: the single-source-of-truth
	 * {@code OpenCodeUtil.getServoyOpencodeConfigDir()} first, then the secondary
	 * safety-net probes (so a no-OSGi {@code plainjunit} test still resolves). Kept
	 * package-visible so {@code KiroCreditReaderTest} can exercise the resolution.
	 *
	 * @return the ordered candidate dirs (never {@code null})
	 */
	static List<File> defaultCandidateDirs() {
		List<File> dirs = new ArrayList<>();
		// The kiro-auth plugin's own override is the ONLY dir when set (CI points it at a
		// per-build dir). No fallback: before the plugin has written its files there, a
		// fallback would read an older kiro-usage.json from another dir and report a
		// stale account figure (SVY-21660: CI "before" was a stale file, Δ ~1262).
		File kiroConfigDir = kiroConfigDirOverride();
		if (kiroConfigDir != null) {
			dirs.add(kiroConfigDir);
			return dirs;
		}
		File servoyConfig = servoyOpencodeConfigDir();
		if (servoyConfig != null) {
			dirs.add(servoyConfig);
		}
		String userHome = System.getProperty("user.home"); //$NON-NLS-1$
		String servoyEnv = System.getenv("SERVOY_OPENCODE_CONFIG"); //$NON-NLS-1$
		if (servoyEnv != null && !servoyEnv.isBlank()) {
			dirs.add(new File(servoyEnv));
		}
		if (userHome != null && !userHome.isBlank()) {
			dirs.add(new File(new File(new File(userHome), ".servoy"), "opencode")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String xdg = System.getenv("XDG_CONFIG_HOME"); //$NON-NLS-1$
		if (xdg != null && !xdg.isBlank()) {
			dirs.add(new File(new File(xdg), "opencode")); //$NON-NLS-1$
		}
		if (userHome != null && !userHome.isBlank()) {
			dirs.add(new File(new File(new File(userHome), ".config"), "opencode")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String appData = System.getenv("APPDATA"); //$NON-NLS-1$
		if (appData != null && !appData.isBlank()) {
			dirs.add(new File(new File(appData), "opencode")); //$NON-NLS-1$
		} else if (userHome != null && !userHome.isBlank()) {
			dirs.add(new File(new File(new File(new File(userHome), "AppData"), "Roaming"), "opencode")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return dirs;
	}

	/**
	 * The {@code KIRO_CONFIG_DIR} environment override the kiro-auth plugin honours
	 * before any other location for {@code kiro.db} / {@code kiro-usage.json}, or
	 * {@code null} when it is not set.
	 */
	static File kiroConfigDirOverride() {
		String dir = System.getenv("KIRO_CONFIG_DIR"); //$NON-NLS-1$
		return dir == null || dir.isBlank() ? null : new File(dir);
	}

	/**
	 * The Servoy opencode config dir via the single source of truth, or
	 * {@code null} when the method is unavailable (e.g. a no-OSGi test). Never
	 * throws.
	 */
	static File servoyOpencodeConfigDir() {
		try {
			return com.servoy.eclipse.opencode.OpenCodeUtil.getServoyOpencodeConfigDir().toFile();
		} catch (Throwable t) { // NOSONAR - no OSGi (plainjunit): fall back to the probe list
			return null;
		}
	}

	/**
	 * The account's currently-{@code used} credits (SVY-21660 B), summed from the
	 * {@code kiro-usage.json} snapshot's {@code accounts[].used} (across every
	 * account) - the account-used figure used to bracket a scenario.
	 * <p>
	 * This is read from the <b>JSON</b>, not {@code kiro.db}: confirmed against
	 * live data, {@code kiro.db} has no account table (only {@code session_usage}),
	 * so the account's available-credit figure lives only in the JSON snapshot.
	 * </p>
	 *
	 * @return the used-credits reading, or {@link OptionalDouble#empty()} when the
	 *         file is absent / unreadable / carries no account ("unknown"). Never
	 *         throws.
	 */
	public OptionalDouble accountUsed() {
		JsonNode root = readUsageJson();
		if (root == null) {
			return OptionalDouble.empty();
		}
		JsonNode accounts = root.get("accounts"); //$NON-NLS-1$
		if (accounts == null || !accounts.isArray() || accounts.isEmpty()) {
			return OptionalDouble.empty();
		}
		double sum = 0d;
		boolean any = false;
		for (JsonNode account : accounts) {
			JsonNode used = account != null ? account.get("used") : null; //$NON-NLS-1$
			if (used != null && used.isNumber()) {
				sum += used.asDouble();
				any = true;
			}
		}
		return any ? OptionalDouble.of(sum) : OptionalDouble.empty();
	}

	/**
	 * The account's {@code limit} from the {@code kiro-usage.json} snapshot (summed
	 * across accounts), exposed so a surface can show "before: used / limit". Read
	 * from the JSON for the same reason as {@link #accountUsed()}.
	 *
	 * @return the credit limit, or {@link OptionalDouble#empty()} when absent
	 *         ("unknown"). Never throws.
	 */
	public OptionalDouble accountLimit() {
		JsonNode root = readUsageJson();
		if (root == null) {
			return OptionalDouble.empty();
		}
		JsonNode accounts = root.get("accounts"); //$NON-NLS-1$
		if (accounts == null || !accounts.isArray() || accounts.isEmpty()) {
			return OptionalDouble.empty();
		}
		double sum = 0d;
		boolean any = false;
		for (JsonNode account : accounts) {
			JsonNode limit = account != null ? account.get("limit") : null; //$NON-NLS-1$
			if (limit != null && limit.isNumber()) {
				sum += limit.asDouble();
				any = true;
			}
		}
		return any ? OptionalDouble.of(sum) : OptionalDouble.empty();
	}

	/**
	 * The {@code kiro-usage.json} snapshot's freshness stamp (SVY-21660 B): the
	 * largest {@code accounts[].updatedAt}, falling back to the top-level
	 * {@code writtenAt}. The runner reads this before a scenario and polls it after
	 * completion, waiting for the plugin to sync (the stamp advancing past the
	 * pre-run value) before reading the end of the credit bracket - otherwise the
	 * snapshot still reflects the pre-run account state and Δ reads ~0.
	 * <p>
	 * The stamp may be an epoch-millis number or an ISO-8601 string; both are
	 * reduced to a comparable {@code long} ({@link Long#MIN_VALUE} parsed from the
	 * ISO instant's epoch millis). A value that is neither is ignored.
	 * </p>
	 *
	 * @return the freshness stamp as epoch millis, or {@link OptionalLong#empty()}
	 *         when absent / unparseable ("unknown"). Never throws.
	 */
	public OptionalLong snapshotStamp() {
		JsonNode root = readUsageJson();
		if (root == null) {
			return OptionalLong.empty();
		}
		long best = Long.MIN_VALUE;
		boolean any = false;
		JsonNode accounts = root.get("accounts"); //$NON-NLS-1$
		if (accounts != null && accounts.isArray()) {
			for (JsonNode account : accounts) {
				OptionalLong stamp = parseStamp(account != null ? account.get("updatedAt") : null); //$NON-NLS-1$
				if (stamp.isPresent()) {
					best = Math.max(best, stamp.getAsLong());
					any = true;
				}
			}
		}
		if (!any) {
			OptionalLong written = parseStamp(root.get("writtenAt")); //$NON-NLS-1$
			if (written.isPresent()) {
				best = written.getAsLong();
				any = true;
			}
		}
		return any ? OptionalLong.of(best) : OptionalLong.empty();
	}

	/**
	 * Parses a freshness stamp node that is either an epoch-millis number or an
	 * ISO-8601 instant string into epoch millis. Any other / unparseable value
	 * yields {@link OptionalLong#empty()}. Never throws.
	 */
	private static OptionalLong parseStamp(JsonNode node) {
		if (node == null || node.isNull()) {
			return OptionalLong.empty();
		}
		if (node.isNumber()) {
			return OptionalLong.of(node.asLong());
		}
		if (node.isTextual()) {
			String text = node.asText();
			if (text == null || text.isBlank()) {
				return OptionalLong.empty();
			}
			try {
				return OptionalLong.of(java.time.Instant.parse(text).toEpochMilli());
			} catch (java.time.format.DateTimeParseException ex) {
				// Try a plain epoch-millis string before giving up.
				try {
					return OptionalLong.of(Long.parseLong(text.trim()));
				} catch (NumberFormatException nfe) {
					return OptionalLong.empty();
				}
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * Reads and parses {@code kiro-usage.json} from the first candidate dir that
	 * has it, tolerantly: an absent / unreadable / malformed file yields
	 * {@code null} ("unknown"). Never throws.
	 */
	private JsonNode readUsageJson() {
		File json = resolveFile("kiro-usage.json"); //$NON-NLS-1$
		if (json == null) {
			return null;
		}
		try {
			return MAPPER.readTree(json);
		} catch (java.io.IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The per-session consumed credits (SVY-21660 B fallback) from {@code kiro.db}
	 * {@code session_usage}, summing {@code est_credits} across the matching
	 * {@code session_id} rows.
	 *
	 * @param sessionId the opencode session id (may be {@code null}/blank)
	 * @return the per-session credits, or {@link OptionalDouble#empty()} when
	 *         unavailable / not found ("unknown"). Never throws.
	 */
	public OptionalDouble creditsForSession(String sessionId) {
		Optional<RunMetrics.KiroUsage> usage = usageForSessions(
				sessionId == null ? List.of() : List.of(sessionId));
		return usage.isPresent() ? OptionalDouble.of(usage.get().estCredits()) : OptionalDouble.empty();
	}

	/**
	 * The metered Kiro usage of a scenario (SVY-21660): requests and
	 * {@code est_credits} from {@code kiro.db} {@code session_usage}, summed over
	 * every given session id and every account row. Pass the root session plus its
	 * subagent sessions: each subagent runs in its own opencode session and the
	 * plugin books its requests and credits under that session's id.
	 * <p>
	 * The plugin books the credits Kiro metered for each response on its
	 * (session, account) row, so this is the scenario's own cost: not affected by
	 * other users of a shared account and not dependent on the periodic account
	 * sync.
	 * </p>
	 *
	 * @param sessionIds the session ids to sum (blank / {@code null} entries are
	 *                   ignored)
	 * @return the summed usage, or {@link Optional#empty()} when no id is given,
	 *         the DB is absent / unreadable, or it has no row for any of the ids
	 *         ("unknown"). Never throws.
	 */
	public Optional<RunMetrics.KiroUsage> usageForSessions(Collection<String> sessionIds) {
		Set<String> ids = new LinkedHashSet<>();
		if (sessionIds != null) {
			for (String id : sessionIds) {
				if (id != null && !id.isBlank()) {
					ids.add(id);
				}
			}
		}
		if (ids.isEmpty()) {
			ServoyLog.logInfo("[skilltest] KiroCreditReader: no session id given - credits unknown."); //$NON-NLS-1$
			return Optional.empty();
		}
		File db = resolveFile("kiro.db"); //$NON-NLS-1$
		if (db == null) {
			ServoyLog.logInfo("[skilltest] KiroCreditReader: kiro.db not found under any candidate dir " //$NON-NLS-1$
					+ candidateDirs + " - credits unknown."); //$NON-NLS-1$
			return Optional.empty();
		}
		String placeholders = String.join(",", Collections.nCopies(ids.size(), "?")); //$NON-NLS-1$ //$NON-NLS-2$
		String sql = "SELECT COUNT(*), SUM(requests), SUM(est_credits) FROM session_usage WHERE session_id IN (" //$NON-NLS-1$
				+ placeholders + ")"; //$NON-NLS-1$
		try (Connection conn = DriverManager.getConnection(readOnlyUrl(db));
				PreparedStatement ps = conn.prepareStatement(sql)) {
			int i = 1;
			for (String id : ids) {
				ps.setString(i++, id);
			}
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getInt(1) > 0) {
					int requests = rs.getInt(2);
					double credits = rs.getDouble(3);
					ServoyLog.logInfo("[skilltest] KiroCreditReader: " + db.getAbsolutePath() + " " + ids.size() //$NON-NLS-1$ //$NON-NLS-2$
							+ " session(s) " + ids + " -> " + rs.getInt(1) + " row(s), " + requests //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ " requests, " + credits + " est_credits."); //$NON-NLS-1$ //$NON-NLS-2$
					return Optional.of(new RunMetrics.KiroUsage(requests, credits));
				}
			}
			ServoyLog.logInfo("[skilltest] KiroCreditReader: " + db.getAbsolutePath() //$NON-NLS-1$
					+ " has no session_usage rows for " + ids + " - credits unknown."); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (SQLException | RuntimeException | LinkageError e) {
			// Absent driver / locked / malformed: degrade to "unknown".
			ServoyLog.logWarning("[skilltest] KiroCreditReader: reading " + db.getAbsolutePath() //$NON-NLS-1$
					+ " failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
					+ ") - credits unknown.", e); //$NON-NLS-1$
		}
		return Optional.empty();
	}

	/**
	 * The JDBC URL opening {@code db} read-only. {@code mode=ro}, NOT
	 * {@code immutable=1}: the plugin keeps {@code kiro.db} in WAL mode, and on a
	 * fresh DB (every CI build) the tables and rows still live in
	 * {@code kiro.db-wal}. {@code immutable=1} ignores the WAL and reported
	 * "no such table: session_usage"; {@code mode=ro} reads it without writing.
	 */
	static String readOnlyUrl(File db) {
		return "jdbc:sqlite:file:" + db.getAbsolutePath().replace('\\', '/') + "?mode=ro"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * @param fileName the file to look for ({@code kiro.db} or
	 *                 {@code kiro-usage.json})
	 * @return the first existing file of that name under a candidate dir, or
	 *         {@code null} when none exists.
	 */
	private File resolveFile(String fileName) {
		for (File dir : candidateDirs) {
			if (dir == null) {
				continue;
			}
			File file = new File(dir, fileName);
			if (file.isFile()) {
				return file;
			}
		}
		return null;
	}
}

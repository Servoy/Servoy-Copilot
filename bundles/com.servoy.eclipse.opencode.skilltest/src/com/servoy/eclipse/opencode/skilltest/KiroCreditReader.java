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
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * {@code session_usage}.</b> {@link #creditsForSession(String)} stays on the
 * DB, summing {@code est_credits} across the matching {@code session_id} rows -
 * that part reflects the scenario that just finished and works today.</li>
 * </ul>
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
 * The DB is opened <b>read-only</b> ({@code immutable=1}) so the plugin's WAL
 * writer is never disturbed, and every DB / file access is wrapped so an absent
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
		if (sessionId == null || sessionId.isBlank()) {
			return OptionalDouble.empty();
		}
		File db = resolveFile("kiro.db"); //$NON-NLS-1$
		if (db == null) {
			return OptionalDouble.empty();
		}
		String sql = "SELECT SUM(est_credits) FROM session_usage WHERE session_id = ?"; //$NON-NLS-1$
		String url = "jdbc:sqlite:file:" + db.getAbsolutePath().replace('\\', '/') + "?immutable=1"; //$NON-NLS-1$ //$NON-NLS-2$
		try (Connection conn = DriverManager.getConnection(url); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, sessionId);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					double sum = rs.getDouble(1);
					if (!rs.wasNull()) {
						return OptionalDouble.of(sum);
					}
				}
			}
		} catch (SQLException | RuntimeException | LinkageError e) {
			// Absent driver / locked / malformed: degrade to "unknown".
			return OptionalDouble.empty();
		}
		return OptionalDouble.empty();
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

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link KiroCreditReader} (SVY-21660 expansion A/B, live-data
 * follow-up): the account credit figures now come from a <b>real</b>
 * {@code kiro-usage.json} snapshot written into a {@code @TempDir}, while the
 * per-session figure still comes from a <b>real</b> {@code kiro.db} SQLite
 * fixture built with the live {@code session_usage} schema through the bundled
 * {@code org.xerial:sqlite-jdbc} driver (not a mock).
 * <p>
 * This pins the "account figure from JSON, per-session figure from DB" split
 * that the fix introduced:
 * </p>
 * <ul>
 * <li>{@link KiroCreditReader#accountUsed()} sums {@code accounts[].used} from
 * the JSON (across every account);</li>
 * <li>{@link KiroCreditReader#accountLimit()} sums {@code accounts[].limit}
 * from the JSON;</li>
 * <li>{@link KiroCreditReader#snapshotStamp()} takes the largest
 * {@code accounts[].updatedAt} (epoch-millis OR ISO-8601), else the top-level
 * {@code writtenAt};</li>
 * <li>{@link KiroCreditReader#creditsForSession(String)} STILL reads
 * {@code kiro.db} {@code session_usage} - so a dir with only a {@code kiro.db}
 * (no JSON) yields an empty account figure but a working per-session figure,
 * and vice-versa;</li>
 * <li>absent / malformed JSON degrades every account accessor to empty without
 * throwing.</li>
 * </ul>
 * <p>
 * All tests point the reader at a temp dir via the injectable candidate-list
 * seam ({@code new KiroCreditReader(List<File>)}) so they stay headless
 * ({@code plainjunit}).
 * </p>
 */
class KiroCreditReaderTest {

	/**
	 * Writes a {@code kiro-usage.json} with the given raw JSON body under
	 * {@code dir}.
	 *
	 * @param dir  the config dir to create {@code kiro-usage.json} in
	 * @param json the raw JSON content
	 * @return the created file
	 */
	private static File writeUsageJson(Path dir, String json) throws Exception {
		File file = dir.resolve("kiro-usage.json").toFile();
		Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
		return file;
	}

	/**
	 * Creates a {@code kiro.db} under {@code dir} with the live
	 * {@code session_usage} schema and inserts the given rows, through the real
	 * SQLite driver.
	 *
	 * @param dir  the config dir to create {@code kiro.db} in
	 * @param rows each row as {@code {sessionId, accountId, estCredits}}
	 * @return the created {@code kiro.db} file
	 */
	private static File createKiroDb(Path dir, Object[]... rows) throws Exception {
		File db = dir.resolve("kiro.db").toFile();
		String url = "jdbc:sqlite:" + db.getAbsolutePath().replace('\\', '/');
		try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
			st.executeUpdate(
					"CREATE TABLE session_usage (" + "session_id TEXT, account_id TEXT, title TEXT, directory TEXT, "
							+ "requests INTEGER, est_credits REAL, first_used TEXT, last_used TEXT)");
			for (Object[] row : rows) {
				String sessionId = (String) row[0];
				String accountId = (String) row[1];
				double est = ((Number) row[2]).doubleValue();
				st.executeUpdate("INSERT INTO session_usage "
						+ "(session_id, account_id, title, directory, requests, est_credits, first_used, last_used) "
						+ "VALUES ('" + sessionId + "', '" + accountId + "', 't', 'd', 1, " + est + ", 'f', 'l')");
			}
		}
		return db;
	}

	@Nested
	class AccountUsedFromJson {

		@Test
		@DisplayName("accountUsed() reads accounts[].used from kiro-usage.json (single account)")
		void singleAccount(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[{\"id\":\"acc1\",\"used\":123.5,\"limit\":1000.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));

			OptionalDouble used = reader.accountUsed();
			assertAll(() -> assertTrue(used.isPresent(), "a real kiro-usage.json resolves"),
					() -> assertEquals(123.5d, used.getAsDouble(), 0.0001d, "accounts[0].used"));
		}

		@Test
		@DisplayName("accountUsed() sums accounts[].used across multiple accounts")
		void multipleAccountsSummed(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[" + "{\"id\":\"acc1\",\"used\":100.0,\"limit\":500.0},"
					+ "{\"id\":\"acc2\",\"used\":25.5,\"limit\":500.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertEquals(125.5d, reader.accountUsed().getAsDouble(), 0.0001d, "100.0 + 25.5 across both accounts");
		}

		@Test
		@DisplayName("accountLimit() sums accounts[].limit from the JSON")
		void accountLimit(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[" + "{\"id\":\"acc1\",\"used\":10.0,\"limit\":500.0},"
					+ "{\"id\":\"acc2\",\"used\":20.0,\"limit\":750.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertEquals(1250.0d, reader.accountLimit().getAsDouble(), 0.0001d, "500.0 + 750.0");
		}

		@Test
		@DisplayName("an empty accounts array yields empty (unknown) for used and limit")
		void emptyAccountsIsUnknown(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertAll(() -> assertTrue(reader.accountUsed().isEmpty(), "no accounts -> unknown used"),
					() -> assertTrue(reader.accountLimit().isEmpty(), "no accounts -> unknown limit"));
		}
	}

	@Nested
	class SnapshotStamp {

		@Test
		@DisplayName("snapshotStamp() parses an epoch-millis updatedAt (largest wins)")
		void epochMillisUpdatedAt(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[" + "{\"id\":\"acc1\",\"used\":1.0,\"updatedAt\":1700000000000},"
					+ "{\"id\":\"acc2\",\"used\":2.0,\"updatedAt\":1700000005000}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			OptionalLong stamp = reader.snapshotStamp();
			assertAll(() -> assertTrue(stamp.isPresent(), "a stamp resolves"),
					() -> assertEquals(1700000005000L, stamp.getAsLong(), "the largest updatedAt wins"));
		}

		@Test
		@DisplayName("snapshotStamp() parses an ISO-8601 updatedAt string")
		void isoUpdatedAt(@TempDir Path dir) throws Exception {
			String iso = "2026-01-02T03:04:05Z";
			long expected = Instant.parse(iso).toEpochMilli();
			writeUsageJson(dir, "{\"accounts\":[{\"id\":\"acc1\",\"used\":1.0,\"updatedAt\":\"" + iso + "\"}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertEquals(expected, reader.snapshotStamp().getAsLong(), "ISO-8601 reduced to epoch millis");
		}

		@Test
		@DisplayName("snapshotStamp() falls back to the top-level writtenAt when no account updatedAt")
		void writtenAtFallback(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"writtenAt\":1700000009000,\"accounts\":[{\"id\":\"acc1\",\"used\":1.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertEquals(1700000009000L, reader.snapshotStamp().getAsLong(), "top-level writtenAt used as fallback");
		}

		@Test
		@DisplayName("snapshotStamp() is empty (unknown) when no stamp is present, never throws")
		void absentStampIsUnknown(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"accounts\":[{\"id\":\"acc1\",\"used\":1.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertTrue(assertDoesNotThrow(reader::snapshotStamp).isEmpty(), "no updatedAt/writtenAt -> unknown");
		}
	}

	@Nested
	class AccountFromJsonSessionFromDb {

		@Test
		@DisplayName("a dir with ONLY kiro.db (no JSON): account figure unknown, per-session still works")
		void dbOnlyNoJson(@TempDir Path dir) throws Exception {
			// No kiro-usage.json: the account figure must be unknown (it is NOT read
			// from kiro.db), but creditsForSession STILL reads the DB.
			createKiroDb(dir, new Object[] { "ses_a", "acc1", 1.25d }, new Object[] { "ses_a", "acc2", 0.75d },
					new Object[] { "ses_b", "acc1", 9.0d });
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));

			OptionalDouble session = reader.creditsForSession("ses_a");
			assertAll(() -> assertTrue(reader.accountUsed().isEmpty(), "account figure NOT read from kiro.db"),
					() -> assertTrue(reader.accountLimit().isEmpty(), "account limit NOT read from kiro.db"),
					() -> assertTrue(reader.snapshotStamp().isEmpty(), "stamp NOT read from kiro.db"),
					() -> assertTrue(session.isPresent(), "ses_a found in the DB"),
					() -> assertEquals(2.0d, session.getAsDouble(), 0.0001d, "1.25 + 0.75 from session_usage"));
		}

		@Test
		@DisplayName("the account figure comes from JSON and the per-session figure from DB, in the same dir")
		void accountFromJsonSessionFromDb(@TempDir Path dir) throws Exception {
			// Both stores present: account used comes from the JSON (999, not the DB
			// rows), and creditsForSession comes from the DB.
			writeUsageJson(dir, "{\"accounts\":[{\"id\":\"acc1\",\"used\":999.0,\"limit\":2000.0}]}");
			createKiroDb(dir, new Object[] { "ses_a", "acc1", 3.5d });
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));

			assertAll(() -> assertEquals(999.0d, reader.accountUsed().getAsDouble(), 0.0001d, "account used from JSON"),
					() -> assertEquals(2000.0d, reader.accountLimit().getAsDouble(), 0.0001d,
							"account limit from JSON"),
					() -> assertEquals(3.5d, reader.creditsForSession("ses_a").getAsDouble(), 0.0001d,
							"per-session from kiro.db"));
		}
	}

	@Nested
	class CreditsForSession {

		@Test
		@DisplayName("creditsForSession sums est_credits across the account rows of that session")
		void sumsSessionRows(@TempDir Path dir) throws Exception {
			createKiroDb(dir, new Object[] { "ses_a", "acc1", 1.25d }, new Object[] { "ses_a", "acc2", 0.75d },
					new Object[] { "ses_b", "acc1", 9.0d });
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));

			OptionalDouble a = reader.creditsForSession("ses_a");
			assertAll(() -> assertTrue(a.isPresent(), "ses_a found"),
					() -> assertEquals(2.0d, a.getAsDouble(), 0.0001d, "1.25 + 0.75, not ses_b's 9.0"));
		}

		@Test
		@DisplayName("a session absent from the table yields empty (unknown)")
		void missingSessionIsUnknown(@TempDir Path dir) throws Exception {
			createKiroDb(dir, new Object[] { "ses_a", "acc1", 1.0d });
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertTrue(reader.creditsForSession("ses_missing").isEmpty(), "unknown session -> unknown");
		}

		@Test
		@DisplayName("a null or blank session id yields empty (unknown)")
		void nullOrBlankSessionIsUnknown(@TempDir Path dir) throws Exception {
			createKiroDb(dir, new Object[] { "ses_a", "acc1", 1.0d });
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertAll(() -> assertTrue(reader.creditsForSession(null).isEmpty(), "null id -> unknown"),
					() -> assertTrue(reader.creditsForSession("  ").isEmpty(), "blank id -> unknown"));
		}
	}

	@Nested
	class DegradesToUnknown {

		@Test
		@DisplayName("no JSON and no DB under any candidate dir -> all empty (unknown), never throws")
		void allAbsent(@TempDir Path dir) {
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertAll(() -> assertTrue(assertDoesNotThrow(reader::accountUsed).isEmpty(), "absent JSON -> unknown"),
					() -> assertTrue(assertDoesNotThrow(reader::accountLimit).isEmpty(), "absent JSON -> unknown"),
					() -> assertTrue(assertDoesNotThrow(reader::snapshotStamp).isEmpty(), "absent JSON -> unknown"),
					() -> assertTrue(assertDoesNotThrow(() -> reader.creditsForSession("ses_a")).isEmpty(),
							"absent db -> unknown"));
		}

		@Test
		@DisplayName("a malformed kiro-usage.json -> account accessors empty (unknown), never throws")
		void malformedJson(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "this is not json at all {{{");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertAll(() -> assertTrue(assertDoesNotThrow(reader::accountUsed).isEmpty(), "malformed JSON -> unknown"),
					() -> assertTrue(assertDoesNotThrow(reader::accountLimit).isEmpty(), "malformed JSON -> unknown"),
					() -> assertTrue(assertDoesNotThrow(reader::snapshotStamp).isEmpty(), "malformed JSON -> unknown"));
		}

		@Test
		@DisplayName("a JSON with no accounts key -> account used/limit empty (unknown), never throws")
		void noAccountsKey(@TempDir Path dir) throws Exception {
			writeUsageJson(dir, "{\"somethingElse\":42}");
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertAll(() -> assertTrue(assertDoesNotThrow(reader::accountUsed).isEmpty(), "no accounts -> unknown"),
					() -> assertTrue(assertDoesNotThrow(reader::accountLimit).isEmpty(), "no accounts -> unknown"));
		}

		@Test
		@DisplayName("a malformed non-DB file named kiro.db -> creditsForSession empty (unknown), never throws")
		void malformedDb(@TempDir Path dir) throws Exception {
			Files.writeString(dir.resolve("kiro.db"), "this is not a sqlite database", StandardCharsets.UTF_8);
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertTrue(assertDoesNotThrow(() -> reader.creditsForSession("ses_a")).isEmpty(),
					"malformed db -> unknown");
		}

		@Test
		@DisplayName("a kiro.db with no session_usage table -> creditsForSession empty (unknown), never throws")
		void noSessionUsageTable(@TempDir Path dir) throws Exception {
			File db = dir.resolve("kiro.db").toFile();
			String url = "jdbc:sqlite:" + db.getAbsolutePath().replace('\\', '/');
			try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
				st.executeUpdate("CREATE TABLE plugin_instances (id TEXT)");
			}
			KiroCreditReader reader = new KiroCreditReader(List.of(dir.toFile()));
			assertTrue(assertDoesNotThrow(() -> reader.creditsForSession("ses_a")).isEmpty(),
					"missing table -> unknown");
		}
	}

	@Nested
	class ConfigDirSeam {

		@Test
		@DisplayName("a ~/.servoy/opencode-style temp dir resolves the JSON (cross-platform)")
		void servoyStyleDirResolves(@TempDir Path home) throws Exception {
			// Mimic <user.home>/.servoy/opencode/kiro-usage.json.
			Path configDir = home.resolve(".servoy").resolve("opencode");
			Files.createDirectories(configDir);
			writeUsageJson(configDir, "{\"accounts\":[{\"id\":\"acc1\",\"used\":3.0,\"limit\":9.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(configDir.toFile()));
			assertEquals(3.0d, reader.accountUsed().getAsDouble(), 0.0001d, "servoy-style config dir resolves");
		}

		@Test
		@DisplayName("first existing candidate dir wins over a later one")
		void firstExistingDirWins(@TempDir Path root) throws Exception {
			Path first = root.resolve("first");
			Path second = root.resolve("second");
			Files.createDirectories(first);
			Files.createDirectories(second);
			writeUsageJson(first, "{\"accounts\":[{\"id\":\"acc1\",\"used\":1.0}]}");
			writeUsageJson(second, "{\"accounts\":[{\"id\":\"acc1\",\"used\":99.0}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(first.toFile(), second.toFile()));
			assertEquals(1.0d, reader.accountUsed().getAsDouble(), 0.0001d, "took the FIRST dir, not the second");
		}

		@Test
		@DisplayName("a candidate dir with no kiro-usage.json is skipped for a later one that has it")
		void skipsDirWithoutJson(@TempDir Path root) throws Exception {
			Path empty = root.resolve("empty");
			Path withJson = root.resolve("withJson");
			Files.createDirectories(empty);
			Files.createDirectories(withJson);
			writeUsageJson(withJson, "{\"accounts\":[{\"id\":\"acc1\",\"used\":2.5}]}");
			KiroCreditReader reader = new KiroCreditReader(List.of(empty.toFile(), withJson.toFile()));
			assertEquals(2.5d, reader.accountUsed().getAsDouble(), 0.0001d,
					"falls through to the dir that has kiro-usage.json");
		}
	}

	@Nested
	class DefaultCandidates {

		@Test
		@DisplayName("defaultCandidateDirs() is non-empty and never throws")
		void defaultCandidatesShape() {
			List<File> dirs = assertDoesNotThrow(KiroCreditReader::defaultCandidateDirs);
			assertFalse(dirs.isEmpty(), "at least one candidate config dir");
		}
	}

	/**
	 * Asserts the real driver can open a db at all (fails loudly if not present).
	 */
	@Test
	@DisplayName("the bundled org.xerial SQLite driver is on the classpath")
	void driverPresent(@TempDir Path dir) throws SQLException {
		File db = dir.resolve("probe.db").toFile();
		String url = "jdbc:sqlite:" + db.getAbsolutePath().replace('\\', '/');
		try (Connection conn = DriverManager.getConnection(url)) {
			assertTrue(conn.isValid(2), "a jdbc:sqlite: connection opened with the bundled driver");
		}
	}
}

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

package com.servoy.eclipse.opencode.tests.plainjunit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.KiroUsageSnapshotReader;

/**
 * Plain-JUnit tests of {@link KiroUsageSnapshotReader}: account choice, field
 * whitelist, staleness rule and tolerance for every kind of broken input.
 */
class KiroUsageSnapshotReaderTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String UNAVAILABLE = "{\"available\":false}";
	private static final long NOW = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli();
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;
	private static final Set<String> WHITELIST = Set.of("available", "used", "limit", "pct", "resetAt", "updatedAt",
		"stale");

	@TempDir
	Path dir;

	private KiroUsageSnapshotReader reader() {
		return new KiroUsageSnapshotReader(() -> dir);
	}

	private void write(String json) throws IOException {
		Files.writeString(dir.resolve("kiro-usage.json"), json, StandardCharsets.UTF_8);
	}

	private JsonNode readNode() throws IOException {
		return MAPPER.readTree(reader().read(dir, NOW));
	}

	private static String account(boolean healthy, String used, String limit, long resetAt, String updatedAt) {
		return "{\"isHealthy\":" + healthy + ",\"used\":" + used + (limit == null ? "" : ",\"limit\":" + limit) +
			",\"pct\":4,\"resetAt\":" + resetAt + (updatedAt == null ? "" : ",\"updatedAt\":" + updatedAt) + "}";
	}

	private static String file(String... accounts) {
		return "{\"accounts\":[" + String.join(",", accounts) + "]}";
	}

	@Test
	@DisplayName("maps a healthy account to the whitelisted numbers")
	void mapsHealthyAccount() throws IOException {
		long resetAt = NOW + 5 * DAY;
		long updatedAt = NOW - HOUR;
		write(file(account(true, "381.29", "10000", resetAt, Long.toString(updatedAt))));
		JsonNode n = readNode();
		assertAll(() -> assertTrue(n.path("available").asBoolean()),
			() -> assertEquals(381.29, n.path("used").asDouble(), 0.0001),
			() -> assertEquals(10000, n.path("limit").asDouble(), 0.0001),
			() -> assertEquals(4, n.path("pct").asDouble(), 0.0001),
			() -> assertEquals(resetAt, n.path("resetAt").asLong()),
			() -> assertEquals(updatedAt, n.path("updatedAt").asLong()),
			() -> assertFalse(n.path("stale").asBoolean()));
	}

	@Test
	@DisplayName("uses the first healthy account when several are healthy")
	void firstHealthyChosen() throws IOException {
		write(file(account(true, "111", "1000", NOW + DAY, Long.toString(NOW)),
			account(true, "222", "2000", NOW + DAY, Long.toString(NOW))));
		assertEquals(111, readNode().path("used").asDouble(), 0.0001);
	}

	@Test
	@DisplayName("skips an unhealthy account in front of a healthy one")
	void unhealthySkipped() throws IOException {
		write(file(account(false, "111", "1000", NOW + DAY, Long.toString(NOW)),
			account(true, "222", "2000", NOW + DAY, Long.toString(NOW))));
		assertEquals(222, readNode().path("used").asDouble(), 0.0001);
	}

	@Test
	@DisplayName("no healthy account is unavailable")
	void noHealthyAccount() throws IOException {
		write(file(account(false, "111", "1000", NOW + DAY, Long.toString(NOW))));
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
		write(file());
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("an absent file is unavailable")
	void absentFile() {
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("an empty file is unavailable")
	void emptyFile() throws IOException {
		write("");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("malformed JSON is unavailable")
	void malformedJson() throws IOException {
		write("{\"accounts\":[{");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("a wrong shape (array root, accounts not an array, scalar accounts) is unavailable")
	void wrongShape() throws IOException {
		write("[1,2,3]");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
		write("{\"accounts\":{\"isHealthy\":true}}");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
		write("{\"accounts\":[1,\"x\",null]}");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
		write("{\"noaccounts\":true}");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("a string isHealthy is not treated as healthy")
	void stringHealthyIgnored() throws IOException {
		write("{\"accounts\":[{\"isHealthy\":\"true\",\"used\":5}]}");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("an oversize file is unavailable even when it is valid JSON")
	void oversizeFile() throws IOException {
		String padding = "x".repeat((int)KiroUsageSnapshotReaderTestAccess.maxBytes() + 10);
		write("{\"accounts\":[" + account(true, "1", "10", NOW + DAY, Long.toString(NOW)) + "],\"pad\":\"" + padding +
			"\"}");
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("a null config dir is unavailable, also through the supplier")
	void nullConfigDir() {
		assertEquals(UNAVAILABLE, reader().read(null, NOW));
		assertEquals(UNAVAILABLE, new KiroUsageSnapshotReader(() -> null).readJson(NOW));
		assertEquals(UNAVAILABLE, new KiroUsageSnapshotReader(null).readJson(NOW));
	}

	@Test
	@DisplayName("a throwing supplier is unavailable and never propagates")
	void throwingSupplier() {
		assertEquals(UNAVAILABLE, new KiroUsageSnapshotReader(() -> {
			throw new IllegalStateException("boom");
		}).readJson(NOW));
	}

	@Test
	@DisplayName("a directory named kiro-usage.json is unavailable")
	void directoryInsteadOfFile() throws IOException {
		Files.createDirectory(dir.resolve("kiro-usage.json"));
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
	}

	@Test
	@DisplayName("a missing limit or limit 0 is omitted")
	void limitOmitted() throws IOException {
		write(file(account(true, "5", null, NOW + DAY, Long.toString(NOW))));
		assertFalse(readNode().has("limit"));
		write(file(account(true, "5", "0", NOW + DAY, Long.toString(NOW))));
		assertFalse(readNode().has("limit"));
		write(file(account(true, "5", "-3", NOW + DAY, Long.toString(NOW))));
		assertFalse(readNode().has("limit"));
	}

	@Test
	@DisplayName("a non-numeric used is omitted")
	void nonNumericUsedOmitted() throws IOException {
		write("{\"accounts\":[{\"isHealthy\":true,\"used\":\"lots\",\"limit\":10}]}");
		JsonNode n = readNode();
		assertAll(() -> assertTrue(n.path("available").asBoolean()), () -> assertFalse(n.has("used")));
	}

	@Test
	@DisplayName("updatedAt accepts epoch millis and an ISO-8601 string")
	void updatedAtFormats() throws IOException {
		long expected = Instant.parse("2026-11-24T10:30:00Z").toEpochMilli();
		write(file(account(true, "1", "10", NOW + DAY, Long.toString(expected))));
		assertEquals(expected, readNode().path("updatedAt").asLong());
		write(file(account(true, "1", "10", NOW + DAY, "\"2026-11-24T10:30:00Z\"")));
		assertEquals(expected, readNode().path("updatedAt").asLong());
		write(file(account(true, "1", "10", NOW + DAY, "\"2026-11-24 10:30:00Z\"")));
		assertEquals(expected, readNode().path("updatedAt").asLong());
		write(file(account(true, "1", "10", NOW + DAY, "\"2026-11-24 10:30Z\"")));
		assertEquals(expected, readNode().path("updatedAt").asLong());
	}

	@Test
	@DisplayName("an unparsable updatedAt is omitted and the snapshot is stale")
	void unparsableUpdatedAt() throws IOException {
		write(file(account(true, "1", "10", NOW + DAY, "\"yesterday\"")));
		JsonNode n = readNode();
		assertAll(() -> assertFalse(n.has("updatedAt")), () -> assertTrue(n.path("stale").asBoolean()));
	}

	@Test
	@DisplayName("falls back to the top-level writtenAt when the account has no updatedAt")
	void writtenAtFallback() throws IOException {
		long written = NOW - 2 * HOUR;
		write("{\"writtenAt\":" + written + ",\"accounts\":[" + account(true, "1", "10", NOW + DAY, null) + "]}");
		JsonNode n = readNode();
		assertAll(() -> assertEquals(written, n.path("updatedAt").asLong()), () -> assertFalse(n.path("stale").asBoolean()));
	}

	@Test
	@DisplayName("stale: resetAt in the past is true, a future resetAt with a fresh updatedAt is false")
	void staleByReset() throws IOException {
		write(file(account(true, "1", "10", NOW - 1, Long.toString(NOW - HOUR))));
		assertTrue(readNode().path("stale").asBoolean());
		write(file(account(true, "1", "10", NOW + 1, Long.toString(NOW - HOUR))));
		assertFalse(readNode().path("stale").asBoolean());
	}

	@Test
	@DisplayName("stale: updatedAt older than 24 hours is true, just inside is false")
	void staleByAge() throws IOException {
		write(file(account(true, "1", "10", NOW + DAY, Long.toString(NOW - DAY - 1))));
		assertTrue(readNode().path("stale").asBoolean());
		write(file(account(true, "1", "10", NOW + DAY, Long.toString(NOW - DAY + HOUR))));
		assertFalse(readNode().path("stale").asBoolean());
	}

	@Test
	@DisplayName("stale: a missing updatedAt is true")
	void staleWhenUpdatedAtMissing() throws IOException {
		write(file(account(true, "1", "10", NOW + DAY, null)));
		assertTrue(readNode().path("stale").asBoolean());
	}

	@Test
	@DisplayName("the output has only whitelisted keys and none of the account identity or other fields")
	void fieldWhitelist() throws IOException {
		String healthy = "{\"isHealthy\":true,\"used\":381.29,\"limit\":10000,\"pct\":4,\"resetAt\":" + (NOW + DAY) +
			",\"updatedAt\":" + (NOW - HOUR) + ",\"email\":\"MARKER_EMAIL@leak.test\",\"id\":\"MARKER_ID_123\"," +
			"\"region\":\"MARKER_REGION\",\"token\":\"MARKER_TOKEN\"}";
		write("{\"writtenAt\":" + NOW + ",\"directory\":\"C:/MARKER_DIR\",\"sessions\":[{\"estCredits\":\"MARKER_SESSION\"}]," +
			"\"pluginInstances\":[\"MARKER_PLUGIN\"],\"pluginVersion\":\"MARKER_VERSION\",\"accounts\":[" + healthy + "]}");
		String out = reader().read(dir, NOW);
		JsonNode n = MAPPER.readTree(out);
		java.util.List<String> keys = new java.util.ArrayList<>();
		n.fieldNames().forEachRemaining(keys::add);
		assertAll(() -> assertTrue(WHITELIST.containsAll(keys), "unexpected keys: " + keys),
			() -> assertTrue(keys.containsAll(Set.of("available", "used", "limit", "pct", "resetAt", "updatedAt", "stale"))),
			() -> assertFalse(out.contains("MARKER"), out), () -> assertFalse(out.contains("leak.test"), out),
			() -> assertFalse(out.contains("email"), out), () -> assertFalse(out.contains("\"id\""), out),
			() -> assertFalse(out.contains("directory"), out), () -> assertFalse(out.contains("region"), out),
			() -> assertFalse(out.contains("sessions"), out), () -> assertFalse(out.contains("plugin"), out));
	}

	@Test
	@DisplayName("only kiro-usage.json is read: other files in the directory never reach the output")
	void onlyUsageFileRead() throws IOException {
		Files.writeString(dir.resolve("auth.json"), "{\"MARKER_AUTH\":\"secret-token\"}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("kiro.json"), "{\"accounts\":[{\"isHealthy\":true,\"used\":999}]}",
			StandardCharsets.UTF_8);
		assertEquals(UNAVAILABLE, reader().read(dir, NOW));
		write(file(account(true, "5", "10", NOW + DAY, Long.toString(NOW))));
		String out = reader().read(dir, NOW);
		assertAll(() -> assertFalse(out.contains("MARKER_AUTH")), () -> assertFalse(out.contains("secret-token")),
			() -> assertEquals(5, MAPPER.readTree(out).path("used").asDouble(), 0.0001));
	}

	@Test
	@DisplayName("readJson resolves the directory through the supplier and reads the same snapshot")
	void readJsonUsesSupplier() throws IOException {
		write(file(account(true, "7", "10", NOW + DAY, Long.toString(NOW))));
		JsonNode n = MAPPER.readTree(reader().readJson(NOW));
		assertAll(() -> assertTrue(n.path("available").asBoolean()), () -> assertEquals(7, n.path("used").asDouble(), 0.0001));
	}

	/** The documented size cap of the reader (1 MiB), restated so the oversize fixture is just above it. */
	private static final class KiroUsageSnapshotReaderTestAccess {
		static long maxBytes() {
			return 1024L * 1024L;
		}
	}
}

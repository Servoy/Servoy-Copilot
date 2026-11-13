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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.skilltest.RunMetrics.KiroUsage;

/**
 * Unit tests for {@link KiroUsageReader} (SVY-21660): matching a session by id,
 * degrading silently to "unknown" ({@link Optional#empty()}) for absent /
 * missing / malformed input, and the <b>cross-platform</b> candidate resolution
 * (Linux/macOS {@code ~/.config}-style and Windows {@code %APPDATA%}-style
 * locations, first-existing-wins) so the figure resolves on non-Windows CI too.
 * <p>
 * All tests point the reader at temp files via the injectable candidate-list
 * seam ({@code new KiroUsageReader(List<File>)}) so they stay headless
 * ({@code plainjunit}).
 * </p>
 */
class KiroUsageReaderTest {

	private static final String USAGE_JSON = "{\"version\":1,\"sessions\":["
			+ "{\"sessionId\":\"ses_one\",\"requests\":3,\"estCredits\":0.42},"
			+ "{\"sessionId\":\"ses_two\",\"requests\":10,\"estCredits\":1.5}" + "]}";

	private static File write(Path dir, String name, String content) throws Exception {
		File f = dir.resolve(name).toFile();
		Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
		return f;
	}

	@Nested
	class MatchBySession {

		@Test
		@DisplayName("a matching sessionId returns its requests and estCredits")
		void matchesSession(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));

			Optional<KiroUsage> result = reader.readForSession("ses_one");
			assertTrue(result.isPresent(), "session found");
			assertAll(() -> assertEquals(3, result.get().requests()),
					() -> assertEquals(0.42d, result.get().estCredits(), 0.0001d));
		}

		@Test
		@DisplayName("a second sessionId in the same file resolves to its own figures")
		void matchesSecondSession(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));

			Optional<KiroUsage> result = reader.readForSession("ses_two");
			assertAll(() -> assertTrue(result.isPresent(), "session found"),
					() -> assertEquals(10, result.get().requests()),
					() -> assertEquals(1.5d, result.get().estCredits(), 0.0001d));
		}
	}

	@Nested
	class DegradesToUnknown {

		@Test
		@DisplayName("no existing candidate file -> empty (unknown)")
		void fileAbsent(@TempDir Path dir) {
			File missing = dir.resolve("nope.json").toFile();
			KiroUsageReader reader = new KiroUsageReader(List.of(missing));
			assertTrue(reader.readForSession("ses_one").isEmpty(), "absent file is unknown");
		}

		@Test
		@DisplayName("a present file whose sessions do not include the id -> empty (unknown)")
		void sessionNotFound(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			assertTrue(reader.readForSession("ses_missing").isEmpty(), "unknown session is unknown");
		}

		@Test
		@DisplayName("malformed JSON -> empty (unknown), never throws")
		void malformedJson(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", "{ this is not json ]]]");
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			Optional<KiroUsage> result = assertDoesNotThrow(() -> reader.readForSession("ses_one"));
			assertTrue(result.isEmpty(), "malformed file is unknown");
		}

		@Test
		@DisplayName("a null or blank sessionId -> empty (unknown)")
		void nullOrBlankSession(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			assertAll(() -> assertTrue(reader.readForSession(null).isEmpty(), "null id is unknown"),
					() -> assertTrue(reader.readForSession("  ").isEmpty(), "blank id is unknown"));
		}

		@Test
		@DisplayName("a file with no sessions array -> empty (unknown)")
		void noSessionsArray(@TempDir Path dir) throws Exception {
			File usage = write(dir, "kiro-usage.json", "{\"version\":1}");
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			assertTrue(reader.readForSession("ses_one").isEmpty(), "missing sessions[] is unknown");
		}
	}

	@Nested
	class CrossPlatformCandidateResolution {

		@Test
		@DisplayName("a Linux/macOS ~/.config/opencode-style candidate resolves (non-Windows CI)")
		void resolvesConfigStyle(@TempDir Path dir) throws Exception {
			// ~/.config/opencode/kiro-usage.json shape.
			Path configDir = dir.resolve(".config").resolve("opencode");
			Files.createDirectories(configDir);
			File usage = write(configDir, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			assertTrue(reader.readForSession("ses_one").isPresent(), "config-style location resolves");
		}

		@Test
		@DisplayName("a Windows %APPDATA%/opencode-style candidate resolves")
		void resolvesAppDataStyle(@TempDir Path dir) throws Exception {
			// %APPDATA%/opencode/kiro-usage.json shape.
			Path appData = dir.resolve("AppData").resolve("Roaming").resolve("opencode");
			Files.createDirectories(appData);
			File usage = write(appData, "kiro-usage.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(usage));
			assertTrue(reader.readForSession("ses_two").isPresent(), "appdata-style location resolves");
		}

		@Test
		@DisplayName("first existing candidate wins over later existing candidates")
		void firstExistingWins(@TempDir Path dir) throws Exception {
			// Both exist; the reader must take the FIRST one in the list.
			File first = write(dir, "first.json",
					"{\"sessions\":[{\"sessionId\":\"ses_one\",\"requests\":1,\"estCredits\":0.1}]}");
			File second = write(dir, "second.json",
					"{\"sessions\":[{\"sessionId\":\"ses_one\",\"requests\":99,\"estCredits\":9.9}]}");
			KiroUsageReader reader = new KiroUsageReader(List.of(first, second));
			Optional<KiroUsage> result = reader.readForSession("ses_one");
			assertAll(() -> assertTrue(result.isPresent(), "resolved"),
					() -> assertEquals(1, result.get().requests(), "took the FIRST candidate, not the second"));
		}

		@Test
		@DisplayName("a non-existing earlier candidate is skipped for a later existing one")
		void skipsMissingEarlierCandidate(@TempDir Path dir) throws Exception {
			File missing = dir.resolve("absent.json").toFile();
			File present = write(dir, "present.json", USAGE_JSON);
			KiroUsageReader reader = new KiroUsageReader(List.of(missing, present));
			assertTrue(reader.readForSession("ses_one").isPresent(), "falls through to the existing candidate");
		}
	}

	@Nested
	class DefaultCandidates {

		@Test
		@DisplayName("defaultCandidates() is non-empty and every entry ends with opencode/kiro-usage.json")
		void defaultCandidatesShape() {
			List<File> candidates = KiroUsageReader.defaultCandidates();
			assertFalse(candidates.isEmpty(), "at least one platform default");
			for (File f : candidates) {
				String path = f.getPath().replace('\\', '/');
				assertTrue(path.endsWith("opencode/kiro-usage.json"),
						"candidate points at opencode/kiro-usage.json: " + path);
			}
		}
	}
}

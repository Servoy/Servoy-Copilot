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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BaselineLoader} and {@link Baseline} — loading a
 * baseline folder ({@code baseline.json} + {@code export.json}), the
 * {@code active} filter, {@code effectivePrompt()}, compare parsing, and
 * malformed-input behaviour. Also covers {@link BaselineLoader#stripLeadingNonJson(String)}.
 */
class BaselineLoaderTest {

	private static final String GOLDEN_EXPORT = "{"
			+ "\"messages\":["
			+ "  {\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"Create a value list named colors with the custom values one, two and three.\"}]},"
			+ "  {\"role\":\"assistant\",\"parts\":[{\"type\":\"tool\",\"tool\":\"servoy-coder_create_valuelist\","
			+ "    \"state\":{\"input\":{\"name\":\"colors\",\"values\":[\"one\",\"two\",\"three\"]},"
			+ "    \"output\":\"Created value list 'colors' with 3 custom values.\"}}]}"
			+ "]}";

	private static File writeBaseline(Path root, String id, String sidecarJson, String exportJson)
			throws IOException {
		File folder = root.resolve(id).toFile();
		assertTrue(folder.mkdirs());
		Files.writeString(new File(folder, "baseline.json").toPath(), sidecarJson, StandardCharsets.UTF_8);
		if (exportJson != null) {
			Files.writeString(new File(folder, "export.json").toPath(), exportJson, StandardCharsets.UTF_8);
		}
		return folder;
	}

	private static String sidecar(String id, boolean active, String promptOverride) {
		String prompt = promptOverride == null ? "null" : "\"" + promptOverride + "\"";
		return "{"
				+ "\"id\":\"" + id + "\","
				+ "\"title\":\"Title " + id + "\","
				+ "\"active\":" + active + ","
				+ "\"export\":\"export.json\","
				+ "\"promptOverride\":" + prompt + ","
				+ "\"maxAttempts\":3,"
				+ "\"timeoutSeconds\":180,"
				+ "\"precondition\":{\"solution\":\"aiTestSolution\"},"
				+ "\"compare\":{"
				+ "  \"ordered\":false,\"strict\":false,"
				+ "  \"forbidden\":[{\"server\":\"servoy-ide\",\"tool\":\"delete_form\"}],"
				+ "  \"argOverrides\":{\"servoy-coder.create_valuelist\":{\"values\":{\"containsAll\":[\"one\",\"two\",\"three\"]}}}"
				+ "}}";
	}

	@Nested
	class LoadSingle {

		@Test
		@DisplayName("parses all sidecar metadata and the golden export")
		void parsesMetadata(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "create-valuelist-basic",
					sidecar("create-valuelist-basic", true, "Create colors."), GOLDEN_EXPORT);

			Baseline b = BaselineLoader.load(folder);

			assertAll(
					() -> assertEquals("create-valuelist-basic", b.id()),
					() -> assertEquals("Title create-valuelist-basic", b.title()),
					() -> assertTrue(b.active()),
					() -> assertEquals("Create colors.", b.promptOverride()),
					() -> assertNull(b.agent()),
					() -> assertNull(b.model()),
					() -> assertEquals(3, b.maxAttempts()),
					() -> assertEquals(180, b.timeoutSeconds()),
					() -> assertEquals("aiTestSolution", b.solution()));
		}

		@Test
		@DisplayName("parses the compare block: forbidden and argOverrides")
		void parsesCompare(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv", sidecar("cv", true, null), GOLDEN_EXPORT);
			Baseline.Compare compare = BaselineLoader.load(folder).compare();
			assertAll(
					() -> assertFalse(compare.ordered()),
					() -> assertFalse(compare.strict()),
					() -> assertEquals(1, compare.forbidden().size()),
					() -> assertEquals("delete_form", compare.forbidden().get(0).tool()),
					() -> assertTrue(compare.argOverrides().containsKey("servoy-coder.create_valuelist")));
		}

		@Test
		@DisplayName("the golden transcript's prompt and tool calls are loaded from export.json")
		void goldenTranscriptLoaded(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv", sidecar("cv", true, null), GOLDEN_EXPORT);
			SessionTranscript golden = BaselineLoader.load(folder).goldenTranscript();
			assertAll(
					() -> assertEquals(
							"Create a value list named colors with the custom values one, two and three.",
							golden.getPrompt()),
					() -> assertEquals(1, golden.getToolCalls().size()),
					() -> assertEquals("create_valuelist", golden.getToolCalls().get(0).tool()));
		}
	}

	@Nested
	class FixtureParsing {

		private String sidecarWithFixture(String preconditionBody) {
			return "{"
					+ "\"id\":\"fx\",\"title\":\"fx\",\"active\":true,\"export\":\"export.json\","
					+ "\"promptOverride\":\"p\",\"maxAttempts\":3,\"timeoutSeconds\":180,"
					+ "\"precondition\":" + preconditionBody + ","
					+ "\"compare\":{\"ordered\":false,\"strict\":false,\"forbidden\":[],\"argOverrides\":{}}}";
		}

		@Test
		@DisplayName("parses delete and restore paths into the fixture")
		void parsesDeleteAndRestore(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "fx", sidecarWithFixture(
					"{\"solution\":\"test\",\"delete\":[\"forms/test1_.frm\",\"forms/test1_.js\"],"
							+ "\"restore\":[\"solution_settings.obj\"]}"),
					GOLDEN_EXPORT);
			Baseline.Fixture fx = BaselineLoader.load(folder).fixture();
			assertAll(
					() -> assertNotNull(fx),
					() -> assertEquals("test", fx.solution()),
					() -> assertEquals(List.of("forms/test1_.frm", "forms/test1_.js"), fx.delete()),
					() -> assertEquals(List.of("solution_settings.obj"), fx.restore()),
					() -> assertFalse(fx.isEmpty()));
		}

		@Test
		@DisplayName("fixture is null when precondition has only a solution (no delete/restore)")
		void nullWhenNoResetPaths(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "fx", sidecarWithFixture("{\"solution\":\"test\"}"), GOLDEN_EXPORT);
			assertNull(BaselineLoader.load(folder).fixture());
		}

		@Test
		@DisplayName("fixture delete list is unmodifiable")
		void deleteUnmodifiable(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "fx",
					sidecarWithFixture("{\"solution\":\"test\",\"delete\":[\"a\"]}"), GOLDEN_EXPORT);
			Baseline.Fixture fx = BaselineLoader.load(folder).fixture();
			org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
					() -> fx.delete().add("b"));
		}
	}

	@Nested
	class EffectivePrompt {

		@Test
		@DisplayName("returns the override when set")
		void overrideWins(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv", sidecar("cv", true, "override prompt"), GOLDEN_EXPORT);
			assertEquals("override prompt", BaselineLoader.load(folder).effectivePrompt());
		}

		@Test
		@DisplayName("falls back to the export prompt when no override")
		void fallbackToExportPrompt(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv", sidecar("cv", true, null), GOLDEN_EXPORT);
			assertEquals(
					"Create a value list named colors with the custom values one, two and three.",
					BaselineLoader.load(folder).effectivePrompt());
		}
	}

	@Nested
	class ActiveFilter {

		@Test
		@DisplayName("loadActive returns only active baselines; loadAll returns every one")
		void filtersInactive(@TempDir Path root) throws IOException {
			writeBaseline(root, "active-one", sidecar("active-one", true, null), GOLDEN_EXPORT);
			writeBaseline(root, "inactive-one", sidecar("inactive-one", false, null), GOLDEN_EXPORT);

			List<Baseline> active = BaselineLoader.loadActive(root.toFile());
			List<Baseline> all = BaselineLoader.loadAll(root.toFile());

			assertAll(
					() -> assertEquals(1, active.size()),
					() -> assertEquals("active-one", active.get(0).id()),
					() -> assertEquals(2, all.size()));
		}

		@Test
		@DisplayName("active defaults to true when the field is absent")
		void activeDefaultsTrue(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv",
					"{\"id\":\"cv\",\"export\":\"export.json\"}", GOLDEN_EXPORT);
			assertTrue(BaselineLoader.load(folder).active());
		}
	}

	@Nested
	class MissingAndMalformed {

		@Test
		@DisplayName("a non-directory root yields an empty list")
		void nonDirectoryRoot() {
			assertTrue(BaselineLoader.loadAll(new File("does-not-exist-xyz")).isEmpty());
		}

		@Test
		@DisplayName("folders without a baseline.json are skipped")
		void folderWithoutSidecarSkipped(@TempDir Path root) throws IOException {
			assertTrue(root.resolve("empty").toFile().mkdirs());
			assertTrue(BaselineLoader.loadAll(root.toFile()).isEmpty());
		}

		@Test
		@DisplayName("a missing export.json yields an empty golden transcript, not a failure")
		void missingExportYieldsEmptyTranscript(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "cv", sidecar("cv", true, "prompt"), null);
			Baseline b = BaselineLoader.load(folder);
			assertAll(
					() -> assertTrue(b.goldenTranscript().getToolCalls().isEmpty()),
					() -> assertNull(b.goldenTranscript().getPrompt()),
					() -> assertEquals("prompt", b.effectivePrompt()));
		}

		@Test
		@DisplayName("a malformed baseline.json surfaces as an IllegalStateException from loadAll")
		void malformedSidecarThrows(@TempDir Path root) throws IOException {
			writeBaseline(root, "bad", "{ this is not json", GOLDEN_EXPORT);
			assertThrows(IllegalStateException.class, () -> BaselineLoader.loadAll(root.toFile()));
		}
	}

	@Nested
	class JsUnitVerifyParsing {

		private String sidecarWithVerify(String verifyJsunitBody) {
			return "{"
					+ "\"id\":\"jv\",\"title\":\"jv\",\"active\":true,\"export\":\"export.json\","
					+ "\"promptOverride\":\"p\",\"maxAttempts\":3,\"timeoutSeconds\":180,"
					+ "\"precondition\":{\"solution\":\"test\"},"
					+ "\"compare\":{\"ordered\":false,\"strict\":false,\"forbidden\":[],\"argOverrides\":{}},"
					+ "\"verify\":{\"jsunit\":" + verifyJsunitBody + "}}";
		}

		@Test
		@DisplayName("parses warmupTimeoutSeconds and separates it from the test timeout")
		void parsesWarmupTimeout(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "jv", sidecarWithVerify(
					"{\"scripts\":[\"verify/skilltest_verify.js\"],\"scope\":\"skilltest_verify\","
							+ "\"method\":null,\"timeoutSeconds\":60,\"warmupTimeoutSeconds\":300,\"required\":true}"),
					GOLDEN_EXPORT);
			Baseline.JsUnitVerify v = BaselineLoader.load(folder).jsUnitVerify();
			assertAll(
					() -> assertNotNull(v),
					() -> assertEquals(60, v.timeoutSeconds()),
					() -> assertEquals(300, v.warmupTimeoutSeconds()),
					// the verifier launches with the larger of the two
					() -> assertEquals(300, v.effectiveTimeoutSeconds()),
					() -> assertTrue(v.required()));
		}

		@Test
		@DisplayName("warmupTimeoutSeconds defaults to 300 when absent")
		void warmupDefaults(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "jv", sidecarWithVerify(
					"{\"scripts\":[\"verify/skilltest_verify.js\"],\"scope\":\"skilltest_verify\","
							+ "\"method\":null,\"timeoutSeconds\":180,\"required\":true}"),
					GOLDEN_EXPORT);
			Baseline.JsUnitVerify v = BaselineLoader.load(folder).jsUnitVerify();
			assertAll(
					() -> assertEquals(180, v.timeoutSeconds()),
					() -> assertEquals(300, v.warmupTimeoutSeconds()),
					() -> assertEquals(300, v.effectiveTimeoutSeconds()));
		}

		@Test
		@DisplayName("existingScripts are parsed and mark the verify as using existing files")
		void parsesExistingScripts(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "jv", sidecarWithVerify(
					"{\"scripts\":[],\"existingScripts\":[\"globals.js\",\"forms/customers.js\"],"
							+ "\"scope\":\"ALL\",\"method\":null,\"timeoutSeconds\":180,\"required\":true}"),
					GOLDEN_EXPORT);
			Baseline.JsUnitVerify v = BaselineLoader.load(folder).jsUnitVerify();
			assertAll(
					() -> assertNotNull(v),
					() -> assertTrue(v.usesExistingScripts()),
					() -> assertTrue(v.scripts().isEmpty()),
					() -> assertEquals(List.of("globals.js", "forms/customers.js"), v.existingScripts()));
		}

		@Test
		@DisplayName("absent existingScripts defaults to empty (inject mode)")
		void existingScriptsDefaultEmpty(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "jv", sidecarWithVerify(
					"{\"scripts\":[\"verify/skilltest_verify.js\"],\"scope\":\"skilltest_verify\","
							+ "\"method\":null,\"timeoutSeconds\":180,\"required\":true}"),
					GOLDEN_EXPORT);
			Baseline.JsUnitVerify v = BaselineLoader.load(folder).jsUnitVerify();
			assertAll(
					() -> assertFalse(v.usesExistingScripts()),
					() -> assertTrue(v.existingScripts().isEmpty()));
		}

		@Test
		@DisplayName("effectiveTimeoutSeconds is the test timeout when it exceeds the warmup budget")
		void testTimeoutWinsWhenLarger(@TempDir Path root) throws IOException {
			File folder = writeBaseline(root, "jv", sidecarWithVerify(
					"{\"scripts\":[],\"scope\":\"ALL\",\"method\":null,"
							+ "\"timeoutSeconds\":600,\"warmupTimeoutSeconds\":300,\"required\":true}"),
					GOLDEN_EXPORT);
			Baseline.JsUnitVerify v = BaselineLoader.load(folder).jsUnitVerify();
			assertEquals(600, v.effectiveTimeoutSeconds());
		}
	}

	@Nested
	class StripLeadingNonJson {

		@Test
		@DisplayName("a leading status line before the JSON object is stripped")
		void stripsLeadingStatusLine() {
			String raw = "Exporting session abc123...\n" + GOLDEN_EXPORT;
			assertTrue(BaselineLoader.stripLeadingNonJson(raw).startsWith("{"));
		}

		@Test
		@DisplayName("already-clean JSON is returned untouched (leading whitespace trimmed)")
		void cleanJsonUntouched() {
			assertEquals(GOLDEN_EXPORT, BaselineLoader.stripLeadingNonJson(GOLDEN_EXPORT));
		}

		@Test
		@DisplayName("a leading array bracket is detected too")
		void detectsArray() {
			assertEquals("[1,2,3]", BaselineLoader.stripLeadingNonJson("noise [1,2,3]"));
		}

		@Test
		@DisplayName("null input yields an empty JSON object without crashing")
		void nullInput() {
			assertEquals("{}", BaselineLoader.stripLeadingNonJson(null));
		}

		@Test
		@DisplayName("all-non-JSON input yields an empty JSON object without crashing")
		void allNonJson() {
			assertEquals("{}", BaselineLoader.stripLeadingNonJson("no json here at all"));
		}
	}
}

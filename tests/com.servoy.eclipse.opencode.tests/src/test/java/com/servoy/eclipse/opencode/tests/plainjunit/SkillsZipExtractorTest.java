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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.OpenCodeUtil;
import com.servoy.eclipse.opencode.SkillsZipExtractor;

/**
 * Unit tests for the {@link SkillsZipExtractor} and
 * {@link OpenCodeUtil} utility classes.
 * <p>
 * No OSGi runtime required -- all methods under test are pure Java operating on
 * in-memory values or temporary file-system resources. Methods that call
 * {@code ApplicationServerRegistry} are exercised with an empty server list
 * (the registry is absent outside OSGi).
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class SkillsZipExtractorTest {
	@TempDir
	Path tmp;

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Creates a zip at a temp path with alternating (name, content) varargs. Names
	 * ending with "/" are written as directory entries.
	 */
	private Path createZip(String... nameContentPairs) throws IOException {
		Path zipPath = Files.createFile(tmp.resolve("test.zip"));
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
			for (int i = 0; i < nameContentPairs.length; i += 2) {
				String name = nameContentPairs[i];
				String content = nameContentPairs[i + 1];
				if (name.endsWith("/")) {
					zos.putNextEntry(new ZipEntry(name));
					zos.closeEntry();
				} else {
					zos.putNextEntry(new ZipEntry(name));
					zos.write(content.getBytes(StandardCharsets.UTF_8));
					zos.closeEntry();
				}
			}
		}
		return zipPath;
	}

	/** Opens the zip at the given path as a fresh ByteArrayInputStream. */
	private ByteArrayInputStream zipStream(Path zipPath) throws IOException {
		return new ByteArrayInputStream(Files.readAllBytes(zipPath));
	}

	private static final String SAMPLE_AGENTS_MD = "# Agent Instructions\n" + "\n" + "```yaml\n"
			+ "servoy_version: \"<servoy_version>\"\n" + "postgres_version: \"<postgres_version>\"\n" + "databases:\n"
			+ "  active: [\"<database>\"]\n" + "  ignore: []\n" + "```\n" + "\n" + "## Project-specific rules\n"
			+ "Do not break things.\n";

	// -----------------------------------------------------------------------
	// getSkillsZipSource
	// -----------------------------------------------------------------------

	/** AC: When SERVOY_SKILLS_ZIP is not set, return null. */
	@Test
	public void getSkillsZipPath_propertyAbsent_returnsNull() {
		System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		assertNull(SkillsZipExtractor.getSkillsZipSource());
	}

	/** Property set but file does not exist -- null. */
	@Test
	public void getSkillsZipPath_nonExistentFile_returnsNull() {
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, "/no/such/file.zip");
		try {
			assertNull(SkillsZipExtractor.getSkillsZipSource());
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/** Property set to an existing file -- non-null source returned. */
	@Test
	public void getSkillsZipPath_existingFile_returnsPath() throws IOException {
		Path zip = createZip("opencode.json", "{}");
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, zip.toString());
		try {
			assertNotNull(SkillsZipExtractor.getSkillsZipSource());
			assertEquals(zip.toString(), SkillsZipExtractor.getSkillsZipSource());
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/** openZipStream with a file path opens the file as a readable InputStream. */
	@Test
	public void openZipStream_filePath_openReadableStream() throws IOException {
		Path zip = createZip("opencode.json", "{\"model\":\"test\"}");
		try (java.io.InputStream is = SkillsZipExtractor.openZipStream(zip.toString())) {
			assertNotNull(is, "stream must not be null");
			byte[] bytes = is.readAllBytes();
			assertTrue(bytes.length > 0, "stream must contain zip bytes");
		}
	}

	// -----------------------------------------------------------------------
	// extractToConfigDir -- opencode.json
	// -----------------------------------------------------------------------

	/** AC: zip with opencode.json -- returns true and file is written. */
	@Test
	public void extractToConfigDir_withOpencodeJson_returnsTrueAndWritesFile() throws IOException {
		Path zip = createZip("opencode.json", "{\"model\":\"test\"}");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		boolean result = SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(result, "should return true when opencode.json is present");
		assertTrue(Files.exists(configDir.resolve("opencode.json")), "opencode.json must be created");
		String content = Files.readString(configDir.resolve("opencode.json"), StandardCharsets.UTF_8);
		assertEquals("{\"model\":\"test\"}", content);
	}

	/**
	 * AC: zip without opencode.json -- returns false (ProviderConfigWriter not
	 * skipped).
	 */
	@Test
	public void extractToConfigDir_withoutOpencodeJson_returnsFalse() throws IOException {
		Path zip = createZip(".opencode/rules.md", "# rules");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		boolean result = SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertFalse(result, "should return false when opencode.json is absent");
		assertFalse(Files.exists(configDir.resolve("opencode.json")), "opencode.json must NOT be created");
	}

	// -----------------------------------------------------------------------
	// extractToConfigDir -- .opencode/ subdirectory
	// -----------------------------------------------------------------------

	/** AC: .opencode/ entries are extracted preserving relative paths. */
	@Test
	public void extractToConfigDir_opencodeSubdir_extracted() throws IOException {
		Path zip = createZip(".opencode/rules.md", "agent rules", ".opencode/sub/other.txt", "sub content");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(Files.exists(configDir.resolve(".opencode/rules.md")), ".opencode/rules.md must exist");
		assertEquals("agent rules", Files.readString(configDir.resolve(".opencode/rules.md"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(configDir.resolve(".opencode/sub/other.txt")), ".opencode/sub/other.txt must exist");
	}

	/** AC: existing .opencode/ directory is fully deleted before re-extraction. */
	@Test
	public void extractToConfigDir_existingOpencodeSubdir_deletedFirst() throws IOException {
		Path configDir = Files.createDirectory(tmp.resolve("config"));
		Path oldFile = configDir.resolve(".opencode/old_file.txt");
		Files.createDirectories(oldFile.getParent());
		Files.writeString(oldFile, "old content", StandardCharsets.UTF_8);

		// New zip does NOT contain old_file.txt
		Path zip = createZip(".opencode/new_file.txt", "new content");
		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertFalse(Files.exists(oldFile), "old file must be deleted");
		assertTrue(Files.exists(configDir.resolve(".opencode/new_file.txt")), "new file must exist");
	}

	// -----------------------------------------------------------------------
	// readZipEntry
	// -----------------------------------------------------------------------

	/** Entry exists -- correct UTF-8 content returned. */
	@Test
	public void readZipEntry_entryExists_returnsContent() throws IOException {
		Path zip = createZip("AGENTS.MD", "# Hello\nWorld");

		String result = SkillsZipExtractor.readZipEntry(zipStream(zip), "AGENTS.MD");

		assertEquals("# Hello\nWorld", result);
	}

	/** Entry absent -- null returned. */
	@Test
	public void readZipEntry_entryAbsent_returnsNull() throws IOException {
		Path zip = createZip("opencode.json", "{}");

		assertNull(SkillsZipExtractor.readZipEntry(zipStream(zip), "AGENTS.MD"));
	}

	/**
	 * Entry name matching is case-insensitive: zip has "AGENTS.md", we ask for
	 * "AGENTS.MD".
	 */
	@Test
	public void readZipEntry_caseInsensitive_findsEntry() throws IOException {
		Path zip = createZip("AGENTS.md", "# Hello");

		String result = SkillsZipExtractor.readZipEntry(zipStream(zip), "AGENTS.MD");

		assertEquals("# Hello", result, "Entry with different case must still be found");
	}

	/**
	 * extractToConfigDir recognises opencode.json regardless of case in the zip.
	 */
	@Test
	public void extractToConfigDir_opencodeJsonMixedCase_extracted() throws IOException {
		Path zip = createZip("Opencode.Json", "{\"model\":\"test\"}");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		boolean result = SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(result, "should return true for case-variant opencode.json");
		assertTrue(Files.exists(configDir.resolve("opencode.json")), "opencode.json must be created");
	}

	/**
	 * writeOrUpdateAgentsMd finds AGENTS.MD with lower-case extension in the zip.
	 */
	@Test
	public void writeOrUpdateAgentsMd_agentsMdLowerCase_createsFile() throws IOException {
		Path zip = createZip("AGENTS.md", SAMPLE_AGENTS_MD);
		Path projectRoot = Files.createDirectory(tmp.resolve("project"));

		SkillsZipExtractor.writeOrUpdateAgentsMd(zipStream(zip), projectRoot);

		assertTrue(
				Files.exists(projectRoot.resolve("AGENTS.MD")), "AGENTS.MD must be created even when zip entry is AGENTS.md");
	}

	// -----------------------------------------------------------------------
	// updateAgentsYaml -- scalar fields
	// -----------------------------------------------------------------------

	/** servoy_version field is replaced with the supplied value. */
	@Test
	public void updateAgentsYaml_replacesServoyVersion() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6",
				Collections.emptyList());

		assertTrue(result.contains("servoy_version: \"2026.06\""), "servoy_version must be updated");
		assertFalse(result.contains("<servoy_version>"), "placeholder must be removed");
	}

	/** postgres_version field is replaced with the supplied value. */
	@Test
	public void updateAgentsYaml_replacesPostgresVersion() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6",
				Collections.emptyList());

		assertTrue(result.contains("postgres_version: \"17.6\""), "postgres_version must be updated");
		assertFalse(result.contains("<postgres_version>"), "placeholder must be removed");
	}

	/** databases.active array is replaced with the supplied list. */
	@Test
	public void updateAgentsYaml_replacesDatabasesActive_singleDatabase() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6", List.of("example_db"));

		assertTrue(result.contains("active: [\"example_db\"]"), "active list must contain example_db");
		assertFalse(result.contains("<database>"), "placeholder must be removed");
	}

	/** Multiple databases produce a proper YAML inline list. */
	@Test
	public void updateAgentsYaml_replacesDatabasesActive_multipleDatabases() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6",
				Arrays.asList("db1", "db2", "db3"));

		assertTrue(result.contains("active: [\"db1\", \"db2\", \"db3\"]"), "all databases must appear");
	}

	/** Empty database list produces empty YAML inline list. */
	@Test
	public void updateAgentsYaml_emptyDatabaseList_producesEmptyArray() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6",
				Collections.emptyList());

		assertTrue(result.contains("active: []"), "empty list must produce []");
	}

	/** All other content (headings, rules, ignore list) is preserved unchanged. */
	@Test
	public void updateAgentsYaml_preservesOtherContent() {
		String result = SkillsZipExtractor.updateAgentsYaml(SAMPLE_AGENTS_MD, "2026.06", "17.6", List.of("mydb"));

		assertTrue(result.contains("# Agent Instructions"), "heading preserved");
		assertTrue(result.contains("Do not break things."), "user rule preserved");
		assertTrue(result.contains("ignore: []"), "ignore list preserved");
	}

	/** A field absent from the input is NOT inserted (deliberate user deletion). */
	@Test
	public void updateAgentsYaml_missingField_notInserted() {
		String noPostgres = SAMPLE_AGENTS_MD.replace("postgres_version: \"<postgres_version>\"\n", "");

		String result = SkillsZipExtractor.updateAgentsYaml(noPostgres, "2026.06", "17.6", Collections.emptyList());

		assertFalse(result.contains("postgres_version"), "absent field must not be inserted");
	}

	/**
	 * Real-values round-trip: AGENTS.MD already has concrete values from a previous
	 * startup; updateAgentsYaml replaces them with the new values while preserving
	 * all custom user content.
	 */
	@Test
	public void updateAgentsYaml_existingRealValues_replacedWithNewValues() {
		String existing = "# Agent Instructions\n" + "\n" + "```yaml\n" + "servoy_version: \"2025.03\"\n"
				+ "postgres_version: \"16.1\"\n" + "databases:\n" + "  active: [\"old_db\"]\n" + "  ignore: []\n"
				+ "```\n" + "\n" + "## My Custom Rules\n" + "Keep data safe.\n";

		String result = SkillsZipExtractor.updateAgentsYaml(existing, "2026.06", "17.6",
				Arrays.asList("new_db1", "new_db2"));

		// Old values gone
		assertFalse(result.contains("2025.03"), "old servoy version must be replaced");
		assertFalse(result.contains("16.1"), "old postgres version must be replaced");
		assertFalse(result.contains("\"old_db\""), "old database must be replaced");
		// New values present
		assertTrue(result.contains("servoy_version: \"2026.06\""), "new servoy version must appear");
		assertTrue(result.contains("postgres_version: \"17.6\""), "new postgres version must appear");
		assertTrue(result.contains("active: [\"new_db1\", \"new_db2\"]"), "new databases must appear");
		// Custom content untouched
		assertTrue(result.contains("## My Custom Rules"), "custom heading must be preserved");
		assertTrue(result.contains("Keep data safe."), "custom rule must be preserved");
		assertTrue(result.contains("ignore: []"), "ignore list must be preserved");
	}

	// -----------------------------------------------------------------------
	// writeOrUpdateAgentsMd -- file create / update
	// -----------------------------------------------------------------------

	/** AC: AGENTS.MD absent -- created from zip content, then YAML updated. */
	@Test
	public void writeOrUpdateAgentsMd_fileAbsent_createsFile() throws IOException {
		Path zip = createZip("AGENTS.MD", SAMPLE_AGENTS_MD);
		Path projectRoot = Files.createDirectory(tmp.resolve("project"));

		SkillsZipExtractor.writeOrUpdateAgentsMd(zipStream(zip), projectRoot);

		Path agentsMd = projectRoot.resolve("AGENTS.MD");
		assertTrue(Files.exists(agentsMd), "AGENTS.MD must be created");
		String content = Files.readString(agentsMd, StandardCharsets.UTF_8);
		assertFalse(content.contains("<servoy_version>"), "placeholder must be replaced");
		assertFalse(content.contains("<postgres_version>"), "placeholder must be replaced");
	}

	/**
	 * AC: AGENTS.MD present -- only YAML block updated, other content unchanged.
	 */
	@Test
	public void writeOrUpdateAgentsMd_filePresent_updatesYamlOnly() throws IOException {
		Path zip = createZip("AGENTS.MD", SAMPLE_AGENTS_MD);
		Path projectRoot = Files.createDirectory(tmp.resolve("project"));
		Path agentsMd = projectRoot.resolve("AGENTS.MD");

		// Write existing file with customisation
		String existing = SAMPLE_AGENTS_MD + "\n## My Custom Rules\nDo not delete data.\n";
		Files.writeString(agentsMd, existing, StandardCharsets.UTF_8);

		SkillsZipExtractor.writeOrUpdateAgentsMd(zipStream(zip), projectRoot);

		String content = Files.readString(agentsMd, StandardCharsets.UTF_8);
		assertTrue(content.contains("Do not delete data."), "custom content must be preserved");
		assertFalse(content.contains("<servoy_version>"), "placeholder must be replaced");
	}

	/** AGENTS.MD absent in zip -- no file created at project root. */
	@Test
	public void writeOrUpdateAgentsMd_agentsMdAbsentInZip_noFileCreated() throws IOException {
		Path zip = createZip("opencode.json", "{}");
		Path projectRoot = Files.createDirectory(tmp.resolve("project"));

		SkillsZipExtractor.writeOrUpdateAgentsMd(zipStream(zip), projectRoot);

		assertFalse(Files.exists(projectRoot.resolve("AGENTS.MD")), "AGENTS.MD must not be created when absent in zip");
	}

	// -----------------------------------------------------------------------
	// OpenCodeUtil.findGitRoot
	// -----------------------------------------------------------------------

	/** Walks up from a subdirectory to find the .git root. */
	@Test
	public void findGitRoot_gitDirPresent_returnsRoot() throws IOException {
		Path root = Files.createDirectory(tmp.resolve("repo"));
		Files.createDirectories(root.resolve(".git"));
		Path subDir = root.resolve("src/main/java");
		Files.createDirectories(subDir);

		Path found = OpenCodeUtil.findGitRoot(subDir);

		assertEquals(root, found, "should find the git root");
	}

	/** No .git directory in ancestry -- null returned. */
	@Test
	public void findGitRoot_noGitDir_returnsNull() throws IOException {
		Path dir = Files.createDirectory(tmp.resolve("no-git"));

		assertNull(OpenCodeUtil.findGitRoot(dir), "should return null when no .git found");
	}

	// -----------------------------------------------------------------------
	// getPostgresVersion
	// -----------------------------------------------------------------------

	/**
	 * AC: When the ApplicationServerRegistry is unavailable (outside OSGi, as in
	 * this test), getPostgresVersion() must return the POSTGRES_VERSION fallback
	 * constant rather than throwing.
	 */
	@Test
	public void getPostgresVersion_registryUnavailable_returnsFallbackConstant() {
		// ApplicationServerRegistry.exists() returns false outside OSGi
		String version = SkillsZipExtractor.getPostgresVersion();
		assertEquals(SkillsZipExtractor.POSTGRES_VERSION,
				version, "must fall back to POSTGRES_VERSION when registry is absent");
	}

	/**
	 * AC: The POSTGRES_VERSION constant is present and non-blank (spec section
	 * 3.3).
	 */
	@Test
	public void getPostgresVersion_fallbackConstantNonBlank() {
		assertNotNull(SkillsZipExtractor.POSTGRES_VERSION, "POSTGRES_VERSION must not be null");
		assertFalse(SkillsZipExtractor.POSTGRES_VERSION.isBlank(), "POSTGRES_VERSION must not be blank");
	}

	/**
	 * The fallback constant has the expected major.minor format
	 * (digit(s).digit(s)).
	 */
	@Test
	public void getPostgresVersion_fallbackConstantMatchesMajorMinorFormat() {
		assertTrue(
				SkillsZipExtractor.POSTGRES_VERSION.matches("\\d+\\.\\d+"), "POSTGRES_VERSION must match major.minor format");
	}

	// -----------------------------------------------------------------------
	// SKILL_OVERWRITE_PROPERTY constant and overwrite-skip logic
	// -----------------------------------------------------------------------

	/** AC: The SKILL_OVERWRITE_PROPERTY constant has the expected value. */
	@Test
	public void skillOverwriteProperty_hasExpectedValue() {
		assertEquals("SERVOY_SKILL_OVERWRITE", SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
	}

	@Test
	public void shouldSkipExtraction_whenPropertyFalseLowerCase_returnsTrue() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "false");
			assertTrue(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyFalseMixedCase_returnsTrue() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "False");
			assertTrue(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyFalseUpperCase_returnsTrue() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "FALSE");
			assertTrue(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyTrue_returnsFalse() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "true");
			assertFalse(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyNotSet_returnsFalse() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			assertFalse(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyBlank_returnsFalse() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "");
			assertFalse(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	@Test
	public void shouldSkipExtraction_whenPropertyArbitraryValue_returnsFalse() {
		String original = System.getProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
		try {
			System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, "xyz");
			assertFalse(SkillsZipExtractor.shouldSkipExtraction());
		} finally {
			if (original == null)
				System.clearProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY);
			else
				System.setProperty(SkillsZipExtractor.SKILL_OVERWRITE_PROPERTY, original);
		}
	}

	// -----------------------------------------------------------------------
	// getSkillsZipSource -- SERVOY_SKILLS_ZIP guard (preserving pre-set value)
	// -----------------------------------------------------------------------

	/**
	 * AC: When SERVOY_SKILLS_ZIP is blank (empty string), getSkillsZipSource
	 * returns null.
	 */
	@Test
	public void getSkillsZipSource_blankProperty_returnsNull() {
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, "");
		try {
			assertNull(SkillsZipExtractor.getSkillsZipSource());
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/**
	 * AC: When SERVOY_SKILLS_ZIP is whitespace-only, getSkillsZipSource returns
	 * null.
	 */
	@Test
	public void getSkillsZipSource_whitespaceProperty_returnsNull() {
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, "   ");
		try {
			assertNull(SkillsZipExtractor.getSkillsZipSource());
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/**
	 * AC: Pre-set SERVOY_SKILLS_ZIP pointing to an existing file is preserved by
	 * getSkillsZipSource.
	 */
	@Test
	public void getSkillsZipSource_preSetToExistingFile_preservesValue() throws IOException {
		Path customZip = createZip(".opencode/skill.md", "custom skill");
		String customPath = customZip.toString();
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, customPath);
		try {
			assertEquals(customPath, SkillsZipExtractor.getSkillsZipSource(), "Pre-set value must be returned as-is");
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	// -----------------------------------------------------------------------
	// extractToConfigDir -- flat zip structure (agents/, skills/, etc. at root)
	// -----------------------------------------------------------------------

	@Test
	public void extractToConfigDir_flatStructure_agentsAndSkillsExtracted() throws IOException {
		Path zip = createZip("agents/foo.md", "agent content", "skills/bar.md", "skill content", "commands/baz.md",
				"command content", "plugins/qux.md", "plugin content");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(Files.exists(configDir.resolve("agents/foo.md")), "agents/foo.md must exist");
		assertEquals("agent content", Files.readString(configDir.resolve("agents/foo.md"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(configDir.resolve("skills/bar.md")), "skills/bar.md must exist");
		assertEquals("skill content", Files.readString(configDir.resolve("skills/bar.md"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(configDir.resolve("commands/baz.md")), "commands/baz.md must exist");
		assertEquals("command content", Files.readString(configDir.resolve("commands/baz.md"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(configDir.resolve("plugins/qux.md")), "plugins/qux.md must exist");
		assertEquals("plugin content", Files.readString(configDir.resolve("plugins/qux.md"), StandardCharsets.UTF_8));
	}

	@Test
	public void extractToConfigDir_existingAgentsDir_cleanedBeforeExtraction() throws IOException {
		Path configDir = Files.createDirectory(tmp.resolve("config"));
		Path oldFile = configDir.resolve("agents/old.md");
		Files.createDirectories(oldFile.getParent());
		Files.writeString(oldFile, "old agent", StandardCharsets.UTF_8);

		Path zip = createZip("agents/new.md", "new agent");
		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertFalse(Files.exists(oldFile), "old agents/old.md must be deleted");
		assertTrue(Files.exists(configDir.resolve("agents/new.md")), "agents/new.md must exist");
		assertEquals("new agent", Files.readString(configDir.resolve("agents/new.md"), StandardCharsets.UTF_8));
	}

	@Test
	public void extractToConfigDir_sessionsDir_notDeleted() throws IOException {
		Path configDir = Files.createDirectory(tmp.resolve("config"));
		Path sessionsFile = configDir.resolve("sessions/state.json");
		Files.createDirectories(sessionsFile.getParent());
		Files.writeString(sessionsFile, "{\"active\":true}", StandardCharsets.UTF_8);

		Path zip = createZip("agents/foo.md", "agent content", "skills/bar.md", "skill content");
		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(Files.exists(sessionsFile), "sessions/state.json must be preserved");
		assertEquals("{\"active\":true}", Files.readString(sessionsFile, StandardCharsets.UTF_8));
	}

	@Test
	public void extractToConfigDir_rootLevelFile_overwritten() throws IOException {
		Path configDir = Files.createDirectory(tmp.resolve("config"));
		Path existingFile = configDir.resolve("config.yaml");
		Files.writeString(existingFile, "old: value", StandardCharsets.UTF_8);

		Path zip = createZip("config.yaml", "new: value");
		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertTrue(Files.exists(existingFile), "config.yaml must exist");
		assertEquals("new: value", Files.readString(existingFile, StandardCharsets.UTF_8));
	}

	@Test
	public void extractToConfigDir_agentsMdInZip_notExtractedToConfigDir() throws IOException {
		Path zip = createZip("AGENTS.MD", "# should not appear", "agents/foo.md", "agent content");
		Path configDir = Files.createDirectory(tmp.resolve("config"));

		SkillsZipExtractor.extractToConfigDir(zipStream(zip), configDir);

		assertFalse(Files.exists(configDir.resolve("AGENTS.MD")), "AGENTS.MD must NOT be extracted to configDir");
		assertTrue(Files.exists(configDir.resolve("agents/foo.md")), "agents/foo.md must still be extracted");
	}

}

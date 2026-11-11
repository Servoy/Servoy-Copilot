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

package com.servoy.eclipse.opencode.skilltest.coverage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PackCatalogReader} — reading skills from
 * {@code skills/<id>/}, agents from {@code agents/<name>.md}, and the
 * {@code mcp} block <b>server</b> names from {@code opencode.json} (a hint
 * only), under a config dir overridable via the
 * {@code servoy.skilltest.configDir} system property, with every source
 * independently guarded so a missing/malformed source degrades to an empty set
 * rather than throwing (SVY-21711 §3.2).
 * <p>
 * {@link PackCatalog#mcpTools()} from {@code read} holds only the
 * {@code opencode.json} mcp server-name hints. The real per-tool MCP
 * denominator is {@link PackCatalogReader#readMcpToolDenominator(Path)}, covered
 * by the {@code McpToolDenominator} nested class below: it joins the
 * {@code opencode.json} mcp-rename map with developer.mcp
 * {@code @McpServer}/{@code @Tool} reflection to emit
 * {@code <packServerName>_<toolName>} identities.
 * </p>
 */
public class PackCatalogReaderTest {

	private String savedProperty;

	@AfterEach
	void restoreProperty() {
		if (savedProperty == null) {
			System.clearProperty(PackCatalogReader.CONFIG_DIR_PROPERTY);
		} else {
			System.setProperty(PackCatalogReader.CONFIG_DIR_PROPERTY, savedProperty);
		}
	}

	private void overrideConfigDir(Path dir) {
		savedProperty = System.getProperty(PackCatalogReader.CONFIG_DIR_PROPERTY);
		System.setProperty(PackCatalogReader.CONFIG_DIR_PROPERTY, dir.toString());
	}

	private static void writeSkill(Path configDir, String id) throws IOException {
		// Real pack layout uses the PLURAL skills/ directory.
		Path skillDir = configDir.resolve("skills").resolve(id);
		Files.createDirectories(skillDir);
		Files.writeString(skillDir.resolve("SKILL.md"), "# " + id, StandardCharsets.UTF_8);
	}

	private static void writeAgent(Path configDir, String name) throws IOException {
		// Real pack layout uses the PLURAL agents/ directory.
		Path agentDir = configDir.resolve("agents");
		Files.createDirectories(agentDir);
		Files.writeString(agentDir.resolve(name + ".md"), "# " + name, StandardCharsets.UTF_8);
	}

	private static void writeOpencodeJson(Path configDir, String json) throws IOException {
		Files.writeString(configDir.resolve("opencode.json"), json, StandardCharsets.UTF_8);
	}

	@Nested
	class FullCatalog {

		@Test
		@DisplayName("reads plural skills/ folders, plural agents/ markdown files, and mcp server keys")
		void readsAllThreeDimensions(@TempDir Path configDir) throws IOException {
			writeSkill(configDir, "sdd-java-plain");
			writeSkill(configDir, "servoy-jira");
			writeAgent(configDir, "build");
			writeAgent(configDir, "test-writer");
			// mcpTools() holds only the opencode.json mcp SERVER names (a hint) - no
			// per-tool reflection any more.
			writeOpencodeJson(configDir,
					"{\"mcp\":{\"servoy-workspace\":{\"type\":\"remote\"},\"servoy-model\":{\"type\":\"remote\"}}}");

			overrideConfigDir(configDir);
			PackCatalog catalog = PackCatalogReader.read();

			assertAll(
					() -> assertEquals(java.util.Set.of("sdd-java-plain", "servoy-jira"),
							new java.util.HashSet<>(catalog.skills())),
					() -> assertEquals(java.util.Set.of("build", "test-writer"),
							new java.util.HashSet<>(catalog.agents())),
					// The mcp block's server names are the hint set; no reflected per-tool list.
					() -> assertEquals(java.util.Set.of("servoy-workspace", "servoy-model"),
							new java.util.HashSet<>(catalog.mcpTools())));
		}

		@Test
		@DisplayName("read(Path) honours an explicit directory over the system property")
		void readExplicitDir(@TempDir Path configDir) throws IOException {
			writeSkill(configDir, "only-skill");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertTrue(catalog.skills().contains("only-skill"));
		}

		@Test
		@DisplayName("agents union the agents/ markdown basenames with the opencode.json 'agent' block keys")
		void agentsUnionJsonBlock(@TempDir Path configDir) throws IOException {
			// Only the Orchestrator ships as a file; build/plan are config-only agents.
			writeAgent(configDir, "Orchestrator");
			writeOpencodeJson(configDir, "{\"agent\":{\"build\":{},\"plan\":{},\"Orchestrator\":{}}}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.agents().contains("Orchestrator")),
					() -> assertTrue(catalog.agents().contains("build")),
					() -> assertTrue(catalog.agents().contains("plan")),
					// the file basename and the matching config key do not double-count
					() -> assertEquals(3, catalog.agents().size()));
		}

		@Test
		@DisplayName("a non-object 'agent' block contributes no agents and does not throw")
		void agentBlockNotObject(@TempDir Path configDir) throws IOException {
			writeAgent(configDir, "reviewer");
			writeOpencodeJson(configDir, "{\"agent\":\"not-an-object\"}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.agents().contains("reviewer")),
					() -> assertEquals(1, catalog.agents().size()));
		}

		@Test
		@DisplayName("mcpTools holds the opencode.json mcp SERVER names, not a reflected per-tool list")
		void mcpToolsAreServerNameHints(@TempDir Path configDir) throws IOException {
			// The reader no longer reflects developer.mcp; it returns the bare mcp server
			// names as a hint. No per-tool "server_tool" identities are produced here.
			writeOpencodeJson(configDir,
					"{\"mcp\":{\"servoy-form\":{\"type\":\"remote\"},\"servoy-workspace\":{\"type\":\"remote\"}}}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.mcpTools().contains("servoy-form")),
					() -> assertTrue(catalog.mcpTools().contains("servoy-workspace")),
					// no reflected per-tool identity
					() -> assertFalse(catalog.mcpTools().contains("servoy-form_getFormLayout")),
					() -> assertEquals(2, catalog.mcpTools().size()));
		}

		@Test
		@DisplayName("only .md files count as agents; other files are ignored")
		void ignoresNonMarkdownAgents(@TempDir Path configDir) throws IOException {
			writeAgent(configDir, "reviewer");
			Files.writeString(configDir.resolve("agents").resolve("README.txt"), "x", StandardCharsets.UTF_8);
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.agents().contains("reviewer")),
					() -> assertFalse(catalog.agents().contains("README")),
					() -> assertEquals(1, catalog.agents().size()));
		}

		@Test
		@DisplayName("only sub-directories count as skills; loose files under skill/ are ignored")
		void ignoresLooseSkillFiles(@TempDir Path configDir) throws IOException {
			writeSkill(configDir, "real-skill");
			Files.writeString(configDir.resolve("skills").resolve("stray.md"), "x", StandardCharsets.UTF_8);
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.skills().contains("real-skill")),
					() -> assertEquals(1, catalog.skills().size()));
		}

		@Test
		@DisplayName("internal harness skills (skilltest-authoring/expectations) are excluded from the catalog")
		void excludesInternalHarnessSkills(@TempDir Path configDir) throws IOException {
			writeSkill(configDir, "servoy-orchestrator");
			writeSkill(configDir, "skilltest-authoring");
			writeSkill(configDir, "skilltest-expectations");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.skills().contains("servoy-orchestrator"), "a real skill stays"),
					() -> assertFalse(catalog.skills().contains("skilltest-authoring"),
							"internal authoring skill is excluded"),
					() -> assertFalse(catalog.skills().contains("skilltest-expectations"),
							"internal expectations skill is excluded"),
					() -> assertEquals(1, catalog.skills().size(), "only the one real skill remains"));
		}

		@Test
		@DisplayName("internal harness agents (Baseline-Author/Judge) are excluded from the catalog")
		void excludesInternalHarnessAgents(@TempDir Path configDir) throws IOException {
			// from agents/ markdown files
			writeAgent(configDir, "Orchestrator");
			writeAgent(configDir, "Baseline-Author");
			writeAgent(configDir, "Judge");
			// and from the opencode.json agent block (same names, different casing)
			writeOpencodeJson(configDir, "{\"agent\":{\"Developer\":{},\"baseline-author\":{},\"judge\":{}}}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.agents().contains("Orchestrator"), "a real file agent stays"),
					() -> assertTrue(catalog.agents().contains("Developer"), "a real config agent stays"),
					() -> assertFalse(catalog.agents().contains("Baseline-Author"),
							"internal Baseline-Author (file) is excluded"),
					() -> assertFalse(catalog.agents().contains("Judge"), "internal Judge (file) is excluded"),
					() -> assertFalse(catalog.agents().contains("baseline-author"),
							"internal baseline-author (config) is excluded"),
					() -> assertFalse(catalog.agents().contains("judge"), "internal judge (config) is excluded"),
					() -> assertEquals(2, catalog.agents().size(), "only the two real agents remain"));
		}
	}

	@Nested
	class MissingOrMalformedSources {

		// The MCP dimension here is just the opencode.json mcp SERVER-name hint set;
		// there is no reflection, so a missing/malformed opencode.json simply yields an
		// empty mcpTools() set (never throws).

		@Test
		@DisplayName("an absent config dir yields empty skills/agents/mcp")
		void absentConfigDir(@TempDir Path configDir) {
			Path missing = configDir.resolve("does-not-exist");
			PackCatalog catalog = PackCatalogReader.read(missing);
			assertAll(() -> assertNotNull(catalog), () -> assertTrue(catalog.skills().isEmpty()),
					() -> assertTrue(catalog.agents().isEmpty()), () -> assertTrue(catalog.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("a null config dir yields an empty catalog")
		void nullConfigDir() {
			PackCatalog catalog = PackCatalogReader.read((Path) null);
			assertAll(() -> assertNotNull(catalog), () -> assertTrue(catalog.skills().isEmpty()),
					() -> assertTrue(catalog.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("missing skills/ and agents/ subtrees degrade to empty sets; mcp server hints still read")
		void missingSkillAndAgentDirs(@TempDir Path configDir) throws IOException {
			// Only opencode.json present — no skills/ or agents/ directories.
			writeOpencodeJson(configDir, "{\"mcp\":{\"servoy-form\":{}}}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertTrue(catalog.skills().isEmpty()), () -> assertTrue(catalog.agents().isEmpty()),
					// the mcp block's server name is kept verbatim as a hint
					() -> assertTrue(catalog.mcpTools().contains("servoy-form")),
					() -> assertFalse(catalog.mcpTools().contains("servoy-form_getFormLayout")));
		}

		@Test
		@DisplayName("a missing opencode.json yields empty mcp hints and the file skills")
		void missingOpencodeJson(@TempDir Path configDir) throws IOException {
			writeSkill(configDir, "s1");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			// No opencode.json, so no mcp server hints; skills still read from the tree.
			assertAll(() -> assertTrue(catalog.skills().contains("s1")),
					() -> assertTrue(catalog.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("a malformed opencode.json does not throw; mcp hints degrade to empty")
		void malformedOpencodeJson(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, "{ this is : not valid json ");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertAll(() -> assertNotNull(catalog), () -> assertTrue(catalog.mcpTools().isEmpty()));
		}

		@Test
		@DisplayName("opencode.json without an mcp block yields no mcp server hints")
		void opencodeJsonWithoutMcp(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, "{\"$schema\":\"https://opencode.ai/config.json\"}");
			PackCatalog catalog = PackCatalogReader.read(configDir);
			assertTrue(catalog.mcpTools().isEmpty());
		}
	}

	@Nested
	class ConfigDirResolution {

		@Test
		@DisplayName("resolveConfigDir honours the system-property override")
		void resolveHonoursOverride(@TempDir Path configDir) {
			overrideConfigDir(configDir);
			assertEquals(configDir, PackCatalogReader.resolveConfigDir());
		}

		@Test
		@DisplayName("resolveConfigDir falls back to ~/.servoy/opencode when unset")
		void resolveFallsBack() {
			savedProperty = System.getProperty(PackCatalogReader.CONFIG_DIR_PROPERTY);
			System.clearProperty(PackCatalogReader.CONFIG_DIR_PROPERTY);
			Path expected = Path.of(System.getProperty("user.home"), ".servoy", "opencode");
			assertEquals(expected, PackCatalogReader.resolveConfigDir());
		}
	}

	/**
	 * Tests for {@link PackCatalogReader#readMcpToolDenominator(Path)} — the real
	 * per-tool MCP denominator, built from the {@code opencode.json} mcp-rename map
	 * (each entry's {@code url} last path segment is the developer.mcp
	 * {@code @McpServer} name) joined with developer.mcp
	 * {@code @McpServer}/{@code @Tool} reflection over
	 * {@code McpServerBuiltins.BUILT_IN_SERVER_CLASSES}, each identity emitted in
	 * the {@code <packServerName>_<toolName>} form seen in session exports.
	 * <p>
	 * This reflection needs developer.mcp on the classpath. The tests fragment
	 * Require-Bundles it, so these assertions expect the real reflected tool names.
	 * If reflection were unavailable in this JVM the method returns an empty set
	 * (asserted by the defensive cases below), never throwing.
	 * </p>
	 */
	@Nested
	class McpToolDenominator {

		@Test
		@DisplayName("maps each pack server to its dev server via the url last segment and emits <packServer>_<tool>")
		void mapsPackServerToDevServerTools(@TempDir Path configDir) throws IOException {
			// servoy-workspace -> servoy-ide, servoy-model -> servoy-dev. The last path
			// segment of each url is the developer.mcp @McpServer name.
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "servoy-workspace":{"type":"remote","url":"http://localhost:{env:MCP_PORT}/dev_mcp/servoy-ide"},
					  "servoy-model":{"type":"remote","url":"http://localhost:{env:MCP_PORT}/dev_mcp/servoy-dev"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(
					// listProjects is a confirmed @Tool on ServoyIdeServer (@McpServer
					// name="servoy-ide"), renamed to the servoy-workspace pack server.
					() -> assertTrue(denominator.contains("servoy-workspace_listProjects"),
							"servoy-ide.listProjects renamed to servoy-workspace"),
					// getTableInfo is a confirmed @Tool on ServoyDevServer (@McpServer
					// name="servoy-dev"), renamed to the servoy-model pack server.
					() -> assertTrue(denominator.contains("servoy-model_getTableInfo"),
							"servoy-dev.getTableInfo renamed to servoy-model"),
					// The developer.mcp-native namespace must NOT appear - the identity uses
					// the PACK server name, not the developer.mcp @McpServer name.
					() -> assertFalse(denominator.contains("servoy-ide_listProjects"),
							"the developer.mcp-native name is the wrong namespace"),
					// A built-in opencode tool is never a developer.mcp @Tool.
					() -> assertFalse(denominator.contains("servoy-workspace_read")),
					() -> assertFalse(denominator.contains("read")),
					// Nothing should be empty when reflection is available.
					() -> assertFalse(denominator.isEmpty(), "reflection resolved developer.mcp @Tools"));
		}

		@Test
		@DisplayName("only pack servers present in opencode.json contribute to the denominator")
		void onlyConfiguredPackServersContribute(@TempDir Path configDir) throws IOException {
			// Only servoy-model (-> servoy-dev) is configured; servoy-ide tools must NOT
			// appear under any pack name since servoy-workspace is not in the mcp block.
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "servoy-model":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-dev"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(() -> assertTrue(denominator.contains("servoy-model_getTableInfo")),
					// no identity for the unconfigured servoy-ide server, under any name
					() -> assertFalse(denominator.contains("servoy-workspace_listProjects")),
					() -> assertTrue(denominator.stream().allMatch(id -> id.startsWith("servoy-model_")),
							"every identity is namespaced by the one configured pack server"));
		}

		@Test
		@DisplayName("a pack server whose url maps to no known developer.mcp server contributes nothing")
		void unknownDevServerContributesNothing(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "servoy-workspace":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-ide"},
					  "mystery":{"type":"remote","url":"http://localhost:1/dev_mcp/no-such-server"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(() -> assertTrue(denominator.contains("servoy-workspace_listProjects")),
					// the unknown dev server yields no identities at all
					() -> assertTrue(denominator.stream().noneMatch(id -> id.startsWith("mystery_"))));
		}

		@Test
		@DisplayName("the whole configured pack rename map resolves to a large, non-empty denominator")
		void fullPackResolves(@TempDir Path configDir) throws IOException {
			// The real pack rename map (from the extracted opencode.json): every pack
			// server and the developer.mcp server its url points at.
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "time":{"type":"remote","url":"http://localhost:1/dev_mcp/time"},
					  "scratchpad":{"type":"remote","url":"http://localhost:1/dev_mcp/memory"},
					  "servoy-history":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-context"},
					  "servoy-editor":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-coder"},
					  "servoy-workspace":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-ide"},
					  "servoy-git":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-git"},
					  "servoy-model":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-dev"},
					  "servoy-test":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-test"},
					  "servoy-form":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-form"},
					  "servoy-packages":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-wpm"},
					  "servoy-media":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-media"},
					  "servoy-i18n":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-i18n"},
					  "servoy-debug":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-debug"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(() -> assertFalse(denominator.isEmpty(), "the full pack resolves a non-empty denominator"),
					// a few confirmed renamed identities across several servers
					() -> assertTrue(denominator.contains("servoy-workspace_listProjects")),
					() -> assertTrue(denominator.contains("servoy-model_getTableInfo")),
					() -> assertTrue(denominator.contains("servoy-model_getTarget")),
					() -> assertTrue(denominator.contains("servoy-form_getFormLayout")),
					// every emitted identity is in <packServer>_<tool> form (has a separator)
					() -> assertTrue(denominator.stream().allMatch(id -> id.indexOf('_') > 0)),
					// 13 servers with ~158 tools total -> comfortably more than 50
					() -> assertTrue(denominator.size() > 50, "a realistic full denominator is large"));
		}

		@Test
		@DisplayName("a url with a trailing slash / query still resolves the dev server from the last segment")
		void trailingSlashAndQuery(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "servoy-workspace":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-ide/"},
					  "servoy-model":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-dev?x=1"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(() -> assertTrue(denominator.contains("servoy-workspace_listProjects")),
					() -> assertTrue(denominator.contains("servoy-model_getTableInfo")));
		}

		@Test
		@DisplayName("a missing / malformed opencode.json yields an empty set, never throws")
		void missingOrMalformedYieldsEmpty(@TempDir Path configDir) throws IOException {
			assertAll(
					// no opencode.json at all
					() -> assertTrue(PackCatalogReader.readMcpToolDenominator(configDir).isEmpty()),
					// a null config dir
					() -> assertTrue(PackCatalogReader.readMcpToolDenominator((Path) null).isEmpty()));
			writeOpencodeJson(configDir, "{ this is : not valid json ");
			assertTrue(PackCatalogReader.readMcpToolDenominator(configDir).isEmpty());
		}

		@Test
		@DisplayName("an opencode.json without an mcp block yields an empty denominator")
		void noMcpBlockYieldsEmpty(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, "{\"$schema\":\"https://opencode.ai/config.json\"}");
			assertTrue(PackCatalogReader.readMcpToolDenominator(configDir).isEmpty());
		}

		@Test
		@DisplayName("an mcp entry with no usable url contributes nothing (no NPE)")
		void entryWithoutUrl(@TempDir Path configDir) throws IOException {
			writeOpencodeJson(configDir, """
					{"mcp":{
					  "servoy-workspace":{"type":"remote","url":"http://localhost:1/dev_mcp/servoy-ide"},
					  "broken":{"type":"remote"}
					}}""");
			java.util.Set<String> denominator = PackCatalogReader.readMcpToolDenominator(configDir);
			assertAll(() -> assertTrue(denominator.contains("servoy-workspace_listProjects")),
					() -> assertTrue(denominator.stream().noneMatch(id -> id.startsWith("broken_"))));
		}
	}
}

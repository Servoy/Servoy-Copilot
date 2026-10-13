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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.opencode.IMcpEndpointProvider;
import com.servoy.eclipse.opencode.McpConfigWriter;

/**
 * Unit tests for the package-private {@link McpConfigWriter} utility class.
 * <p>
 * No OSGi runtime is required - all methods under test are pure Java and
 * operate on in-memory values or temporary file-system resources. The
 * {@link McpConfigWriter#collectProviders()} method is excluded because it
 * calls {@code Platform.getExtensionRegistry()} which requires an OSGi
 * container.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class McpConfigWriterTest {
	@TempDir
	Path tmp;

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/** Creates a simple {@link IMcpEndpointProvider} from literals. */
	private static IMcpEndpointProvider provider(List<String> urls, String token) {
		return new IMcpEndpointProvider() {
			@Override
			public List<String> getUrls() {
				return urls;
			}

			@Override
			public String getAuthToken() {
				return token;
			}
		};
	}

	// -----------------------------------------------------------------------
	// extractPort
	// -----------------------------------------------------------------------

	@Test
	public void extractPort_standardUrl_returnsCorrectPort() {
		assertEquals(8085, McpConfigWriter.extractPort("http://localhost:8085/mcp/eclipse-ide"));
	}

	@Test
	public void extractPort_alternativePort_returnsCorrectPort() {
		assertEquals(9090, McpConfigWriter.extractPort("http://localhost:9090/mcp/tool"));
	}

	@Test
	public void extractPort_highPortNumber_returnsCorrectPort() {
		assertEquals(65535, McpConfigWriter.extractPort("http://localhost:65535/mcp/tool"));
	}

	@Test
	public void extractPort_noExplicitPort_throwsIllegalArgumentException() {
		assertThrows(IllegalArgumentException.class, () -> McpConfigWriter.extractPort("http://localhost/mcp/eclipse-ide"));
	}

	@Test
	public void extractPort_invalidUrl_throwsIllegalArgumentException() {
		assertThrows(IllegalArgumentException.class, () -> McpConfigWriter.extractPort("not a url at all"));
	}

	// -----------------------------------------------------------------------
	// serverNameFromUrl
	// -----------------------------------------------------------------------

	@Test
	public void serverNameFromUrl_standardPath_returnsLastSegment() {
		assertEquals("eclipse-ide", McpConfigWriter.serverNameFromUrl("http://localhost:8085/mcp/eclipse-ide"));
	}

	@Test
	public void serverNameFromUrl_differentTool_returnsCorrectName() {
		assertEquals("eclipse-coder", McpConfigWriter.serverNameFromUrl("http://localhost:8085/mcp/eclipse-coder"));
	}

	@Test
	public void serverNameFromUrl_trailingSlash_returnsLastNonEmptySegment() {
		assertEquals("eclipse-ide", McpConfigWriter.serverNameFromUrl("http://localhost:8085/mcp/eclipse-ide/"));
	}

	@Test
	public void serverNameFromUrl_singlePathSegment_returnsThatSegment() {
		assertEquals("tool", McpConfigWriter.serverNameFromUrl("http://localhost:8085/tool"));
	}

	// -----------------------------------------------------------------------
	// templateUrl
	// -----------------------------------------------------------------------

	@Test
	public void templateUrl_replacesPortWithEnvRef() {
		assertEquals(
				"http://localhost:{env:MCP_PORT}/mcp/eclipse-ide",
				McpConfigWriter.templateUrl("http://localhost:8085/mcp/eclipse-ide"));
	}

	@Test
	public void templateUrl_differentPort_replacesCorrectly() {
		assertEquals(
				"http://localhost:{env:MCP_PORT}/mcp/eclipse-coder",
				McpConfigWriter.templateUrl("http://localhost:9090/mcp/eclipse-coder"));
	}

	@Test
	public void templateUrl_resultContainsEnvMcpPortPlaceholder() {
		String result = McpConfigWriter.templateUrl("http://localhost:8085/mcp/eclipse-ide");
		assertTrue(result.contains("{env:MCP_PORT}"), "Template URL must contain {env:MCP_PORT}");
	}

	@Test
	public void templateUrl_resultDoesNotContainOriginalPort() {
		String result = McpConfigWriter.templateUrl("http://localhost:8085/mcp/eclipse-ide");
		assertFalse(result.contains(":8085/"), "Original port :8085 must not remain in template URL");
	}

	@Test
	public void templateUrl_pathIsPreservedExactly() {
		String result = McpConfigWriter.templateUrl("http://localhost:8085/mcp/eclipse-ide");
		assertTrue(result.endsWith("/mcp/eclipse-ide"), "Path /mcp/eclipse-ide must be preserved");
	}

	// -----------------------------------------------------------------------
	// buildEnvVars
	// -----------------------------------------------------------------------

	@Test
	public void buildEnvVars_emptyProviderList_returnsEmptyMap() {
		assertTrue(McpConfigWriter.buildEnvVars(Collections.emptyList()).isEmpty());
	}

	@Test
	public void buildEnvVars_providerWithUrls_mapContainsMcpPort() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertTrue(env.containsKey(McpConfigWriter.ENV_PORT), "MCP_PORT must be present");
	}

	@Test
	public void buildEnvVars_providerWithUrls_mcpPortValueIsExtractedPort() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertEquals("8085", env.get(McpConfigWriter.ENV_PORT));
	}

	@Test
	public void buildEnvVars_providerWithAuthToken_mapContainsMcpAuthToken() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "secret");
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertTrue(env.containsKey(McpConfigWriter.ENV_TOKEN), "MCP_AUTH_TOKEN must be present when token is provided");
	}

	@Test
	public void buildEnvVars_providerWithAuthToken_tokenValueStoredCorrectly() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "secret");
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertEquals("secret", env.get(McpConfigWriter.ENV_TOKEN));
	}

	@Test
	public void buildEnvVars_providerWithNullToken_mapDoesNotContainMcpAuthToken() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertFalse(env.containsKey(McpConfigWriter.ENV_TOKEN), "MCP_AUTH_TOKEN must be absent when token is null");
	}

	@Test
	public void buildEnvVars_providerWithEmptyUrlList_returnsEmptyMap() {
		IMcpEndpointProvider p = provider(Collections.emptyList(), "token");
		assertTrue(McpConfigWriter.buildEnvVars(List.of(p)).isEmpty());
	}

	@Test
	public void buildEnvVars_multipleProviders_mcpPortFromFirstUrl() {
		IMcpEndpointProvider p1 = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		IMcpEndpointProvider p2 = provider(List.of("http://localhost:8085/mcp/eclipse-coder"), null);
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p1, p2));
		assertEquals("8085", env.get(McpConfigWriter.ENV_PORT));
	}

	@Test
	public void buildEnvVars_tokenInSecondProvider_tokenPresent() {
		IMcpEndpointProvider p1 = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		IMcpEndpointProvider p2 = provider(List.of("http://localhost:8085/mcp/eclipse-coder"), "mytoken");
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p1, p2));
		assertEquals("mytoken", env.get(McpConfigWriter.ENV_TOKEN));
	}

	@Test
	public void buildEnvVars_returnedMap_isUnmodifiable() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "token");
		Map<String, String> env = McpConfigWriter.buildEnvVars(List.of(p));
		assertThrows(UnsupportedOperationException.class, () -> env.put("EXTRA", "value")); // must throw
	}

	// -----------------------------------------------------------------------
	// mergeConfig - fresh file creation
	// -----------------------------------------------------------------------

	@Test
	public void mergeConfig_emptyProviders_doesNotCreateFile() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		McpConfigWriter.mergeConfig(Collections.emptyList(), configFile);
		assertFalse(Files.exists(configFile), "No file should be created when providers list is empty");
	}

	@Test
	public void mergeConfig_fileAbsent_createsFile() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		assertTrue(Files.exists(configFile), "opencode.json should be created");
	}

	@Test
	public void mergeConfig_freshFile_containsSchemaUrl() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains(McpConfigWriter.SCHEMA_URL), "$schema field must be present");
	}

	@Test
	public void mergeConfig_freshFile_urlUsesEnvPortTemplate() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"), "URL must contain {env:MCP_PORT}");
	}

	@Test
	public void mergeConfig_freshFile_hardcodedPortAbsent() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertFalse(content.contains(":8085/"), "Hardcoded port must not appear in the generated URL");
	}

	@Test
	public void mergeConfig_freshFile_serverNameDerivedFromUrlPath() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""), "Server name 'eclipse-ide' must appear as JSON key");
	}

	@Test
	public void mergeConfig_freshFile_withToken_authHeaderPresent() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "tok123");
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("Authorization"), "Authorization header must be present");
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_TOKEN + "}"), "MCP_AUTH_TOKEN env ref must appear");
	}

	@Test
	public void mergeConfig_freshFile_withoutToken_noAuthHeader() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertFalse(content.contains("Authorization"), "Authorization header must NOT appear when no token");
	}

	@Test
	public void mergeConfig_freshFile_multipleUrls_allServerNamesPresent() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(
				List.of("http://localhost:8085/mcp/eclipse-ide", "http://localhost:8085/mcp/eclipse-coder"), "tok");
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""));
		assertTrue(content.contains("\"eclipse-coder\""));
	}

	@Test
	public void mergeConfig_createsParentDirectoriesIfAbsent() throws IOException {
		Path deep = tmp.resolve("a/b/c/opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), deep);
		assertTrue(Files.exists(deep), "File must be created even when parent dirs are missing");
	}

	// -----------------------------------------------------------------------
	// mergeConfig - merge into existing file
	// -----------------------------------------------------------------------

	@Test
	public void mergeConfig_existingFileUpToDate_correctEntriesStillPresent() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "tok");

		// First write
		McpConfigWriter.mergeConfig(List.of(p), configFile);
		// Second write - should be idempotent
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""));
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"));
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_TOKEN + "}"));
	}

	@Test
	public void mergeConfig_existingFileWithUserEntry_userEntryPreserved() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		String existing = "{\n" +
				"  \"$schema\": \"https://opencode.ai/config.json\",\n" +
				"  \"mcp\": {\n" +
				"    \"atlassian\": {\n" +
				"      \"type\": \"remote\",\n" +
				"      \"url\": \"https://mcp.atlassian.com/v1/mcp\"\n" +
				"    }\n" +
				"  }\n" +
				"}\n";
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "tok");
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"atlassian\""), "User-added 'atlassian' entry must be preserved");
		assertTrue(content.contains("\"eclipse-ide\""), "Contributed 'eclipse-ide' entry must be added");
	}

	@Test
	public void mergeConfig_existingFileWithEmptyMcpBlock_entryAdded() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		Files.writeString(configFile, "{\n  \"mcp\": {}\n}\n", StandardCharsets.UTF_8);

		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""), "Missing 'eclipse-ide' must be added to empty mcp block");
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"), "URL must use env template");
	}

	@Test
	public void mergeConfig_existingFileWithHardcodedPortUrl_urlUpdatedToTemplate() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		String stale = "{\n  \"mcp\": {\n    \"eclipse-ide\": {\n" +
				"      \"type\": \"remote\",\n" +
				"      \"url\": \"http://localhost:8085/mcp/eclipse-ide\"\n" +
				"    }\n  }\n}\n";
		Files.writeString(configFile, stale, StandardCharsets.UTF_8);

		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"), "URL must be updated to template form");
		assertFalse(
				content.contains("\"http://localhost:8085/mcp/eclipse-ide\""), "Hardcoded port must be gone after update");
	}

	@Test
	public void mergeConfig_existingFileWithNoMcpBlock_mcpBlockAddedWithEntry() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		String noMcp = "{\n  \"$schema\": \"" + McpConfigWriter.SCHEMA_URL + "\"\n}\n";
		Files.writeString(configFile, noMcp, StandardCharsets.UTF_8);

		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(
				content.contains("\"eclipse-ide\""), "eclipse-ide must be added even if mcp block was absent");
	}

	@Test
	public void mergeConfig_existingFileWithoutSchema_schemaFieldAdded() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		Files.writeString(configFile, "{\n  \"mcp\": {}\n}\n", StandardCharsets.UTF_8);

		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains(McpConfigWriter.SCHEMA_URL), "$schema must be added to file that lacked it");
	}

	@Test
	public void mergeConfig_existingEntryWithRandomNameButSameUrl_preservedAndNewOnesAdded() throws IOException {
		Path configFile = tmp.resolve("opencode.json");
		// Existing file has a server with a random name, but its URL matches one of the contributed URLs
		String existing = "{\n" +
				"  \"$schema\": \"" + McpConfigWriter.SCHEMA_URL + "\",\n" +
				"  \"mcp\": {\n" +
				"    \"my-random-name\": {\n" +
				"      \"type\": \"remote\",\n" +
				"      \"url\": \"http://localhost:{env:" + McpConfigWriter.ENV_PORT + "}/mcp/eclipse-ide\"\n" +
				"    }\n" +
				"  }\n" +
				"}\n";
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		// Contributed: eclipse-ide (URL already present under "my-random-name") + eclipse-coder (new)
		IMcpEndpointProvider p = provider(
				List.of("http://localhost:8085/mcp/eclipse-ide", "http://localhost:8085/mcp/eclipse-coder"), null);
		McpConfigWriter.mergeConfig(List.of(p), configFile);

		ObjectMapper mapper = new ObjectMapper();
		ObjectNode mcp = (ObjectNode)((ObjectNode)mapper.readTree(configFile.toFile())).get("mcp");

		assertEquals(2, mcp.size(), "mcp section must have exactly 2 entries");
		assertTrue(mcp.has("my-random-name"), "Existing random server name must be preserved");
		assertTrue(mcp.has("eclipse-coder"), "New server 'eclipse-coder' must be added");
		assertFalse(mcp.has("eclipse-ide"),
				"'eclipse-ide' key must not appear — its URL was already present under a different name");
	}

	// -----------------------------------------------------------------------
	// collectProviders - test-seam (OSGi extension point bypass)
	// -----------------------------------------------------------------------

	/**
	 * Clears the test-seam override after each test so that no state leaks
	 * into subsequent tests. Uses a separate @AfterEach to keep it alongside the
	 * tests that use it rather than mixed into the TemporaryFolder teardown.
	 */
	@AfterEach
	public void clearTestProvidersOverride() {
		McpConfigWriter.testProvidersOverride = null;
	}

	@Test
	public void collectProviders_withOverride_returnsInjectedList() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.testProvidersOverride = List.of(p);

		List<IMcpEndpointProvider> result = McpConfigWriter.collectProviders();

		assertEquals(1, result.size(), "collectProviders must return the injected provider list");
		assertSame(p, result.get(0), "Returned provider must be the same instance");
	}

	@Test
	public void collectProviders_withOverride_returnsDefensiveCopy() {
		IMcpEndpointProvider p = provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null);
		McpConfigWriter.testProvidersOverride = List.of(p);

		List<IMcpEndpointProvider> result = McpConfigWriter.collectProviders();
		result.clear(); // mutate the returned list

		// Original override must be unchanged
		assertEquals(1,
				McpConfigWriter.testProvidersOverride.size(), "testProvidersOverride must not be mutated by callers");
	}

	@Test
	public void collectProviders_withoutOverride_returnsEmptyListWhenNoOsgi() {
		// No OSGi runtime in this test environment: Platform.getExtensionRegistry()
		// returns null, so collectProviders() must return an empty list gracefully.
		McpConfigWriter.testProvidersOverride = null;
		List<IMcpEndpointProvider> result = McpConfigWriter.collectProviders();
		assertNotNull(result, "collectProviders must never return null");
		assertTrue(result.isEmpty(), "Without OSGi, collectProviders must return an empty list");
	}

	// -----------------------------------------------------------------------
	// Full pipeline: collectProviders (mocked) + mergeConfig + buildEnvVars
	// -----------------------------------------------------------------------

	@Test
	public void fullPipeline_twoUrlsWithToken_fileContainsBothServerNames() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						"authToken123"));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""), "eclipse-ide server name must be present");
		assertTrue(content.contains("\"eclipse-coder\""), "eclipse-coder server name must be present");
	}

	@Test
	public void fullPipeline_twoUrlsWithToken_urlsUseEnvPortTemplate() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						"authToken123"));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"),
				"URL must use {env:MCP_PORT} template");
		assertFalse(content.contains(":8085/"), "Hardcoded port :8085 must not appear in the file");
	}

	@Test
	public void fullPipeline_twoUrlsWithToken_authHeadersPresentForBothEntries() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						"authToken123"));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("Authorization"), "Authorization header must be present");
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_TOKEN + "}"),
				"MCP_AUTH_TOKEN env ref must be present");
		// Both entries share the same auth header - verify it appears at least twice
		int first = content.indexOf("Authorization");
		int second = content.indexOf("Authorization", first + 1);
		assertTrue(second > first, "Authorization header must appear for each of the two entries");
	}

	@Test
	public void fullPipeline_twoUrlsWithToken_schemaFieldPresent() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						"authToken123"));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains(McpConfigWriter.SCHEMA_URL),
				"$schema must be present in the generated file");
	}

	@Test
	public void fullPipeline_twoUrlsWithToken_envVarsContainPortAndToken() {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						"authToken123"));

		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		Map<String, String> env = McpConfigWriter.buildEnvVars(providers);

		assertEquals("8085", env.get(McpConfigWriter.ENV_PORT), "MCP_PORT must be 8085");
		assertEquals("authToken123",
				env.get(McpConfigWriter.ENV_TOKEN), "MCP_AUTH_TOKEN must match the injected token");
	}

	@Test
	public void fullPipeline_twoProvidersWithSamePort_envVarsCorrect() {
		// Two separate providers contributing different endpoints on the same port
		McpConfigWriter.testProvidersOverride = List.of(
				provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "sharedToken"),
				provider(List.of("http://localhost:8085/mcp/eclipse-coder"), null));

		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		Map<String, String> env = McpConfigWriter.buildEnvVars(providers);

		assertEquals("8085", env.get(McpConfigWriter.ENV_PORT));
		assertEquals("sharedToken", env.get(McpConfigWriter.ENV_TOKEN));
	}

	@Test
	public void fullPipeline_twoProvidersWithSamePort_bothServerNamesInFile() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(List.of("http://localhost:8085/mcp/eclipse-ide"), "sharedToken"),
				provider(List.of("http://localhost:8085/mcp/eclipse-coder"), null));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"eclipse-ide\""));
		assertTrue(content.contains("\"eclipse-coder\""));
	}

	@Test
	public void fullPipeline_noToken_noAuthHeaderInFile() throws IOException {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:8085/mcp/eclipse-ide",
								"http://localhost:8085/mcp/eclipse-coder"),
						null));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertFalse(content.contains("Authorization"),
				"No Authorization header must appear when no auth token provided");
	}

	@Test
	public void fullPipeline_noToken_envVarsHasNoAuthToken() {
		McpConfigWriter.testProvidersOverride = List.of(
				provider(List.of("http://localhost:8085/mcp/eclipse-ide"), null));

		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		Map<String, String> env = McpConfigWriter.buildEnvVars(providers);

		assertFalse(env.containsKey(McpConfigWriter.ENV_TOKEN),
				"MCP_AUTH_TOKEN must be absent when no token provided");
		assertEquals("8085", env.get(McpConfigWriter.ENV_PORT), "MCP_PORT must still be populated");
	}

	@Test
	public void fullPipeline_emptyProviders_doesNotCreateFile() throws IOException {
		McpConfigWriter.testProvidersOverride = Collections.emptyList();

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);

		assertFalse(
				Files.exists(configFile), "No opencode.json must be created when provider list is empty");
	}

	@Test
	public void fullPipeline_envVarsAndFileAreConsistent() throws IOException {
		// Verify that the port written into the file matches the port in the env map
		McpConfigWriter.testProvidersOverride = List.of(
				provider(
						List.of("http://localhost:9999/mcp/eclipse-ide",
								"http://localhost:9999/mcp/eclipse-coder"),
						"myToken"));

		Path configFile = tmp.resolve("opencode.json");
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		McpConfigWriter.mergeConfig(providers, configFile);
		Map<String, String> env = McpConfigWriter.buildEnvVars(providers);

		assertEquals("9999",
				env.get(McpConfigWriter.ENV_PORT), "MCP_PORT must reflect the actual URL port");
		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertFalse(content.contains(":9999/"),
				"The actual port 9999 must not appear hard-coded in the file");
		assertTrue(content.contains("{env:" + McpConfigWriter.ENV_PORT + "}"),
				"File must contain the env-var placeholder, not the literal port");
	}
}

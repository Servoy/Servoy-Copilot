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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.McpConfigWriter;
import com.servoy.eclipse.opencode.ProviderConfigWriter;

/**
 * Unit tests for the package-private {@link ProviderConfigWriter} utility
 * class.
 * <p>
 * No OSGi runtime is required - all methods under test are pure Java and
 * operate on in-memory values or temporary file-system resources.
 * </p>
 * <p>
 * SVY-21750 moved the provider key out of the {@code GENAI_API_KEY} JVM system
 * property and into the Servoy login secure-store node. The accessor
 * {@link ProviderConfigWriter#getApiKey()} now reads the secure-store seam
 * first and only falls back to the {@code -D GENAI_API_KEY} system property.
 * Plain JUnit has no live {@code SecurePreferences} keyring, so these tests
 * substitute the package-private {@code secureStoreReader} supplier seam (via
 * reflection - the test lives in a different package from the seam) to simulate
 * "node has the value" / "node is empty" / "keyring throws", and exercise the
 * {@code -D} fallback with {@code System.setProperty}/{@code clearProperty}.
 * Both the seam and the system property are saved and restored around every
 * test so no static state leaks.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
class ProviderConfigWriterTest {

	@TempDir
	Path tmp;

	/** The seam's production value, restored after every test. */
	private Supplier<String> originalSecureStoreReader;
	/** The {@code -D GENAI_API_KEY} value, restored after every test. */
	private String savedApiKeyProperty;

	@BeforeEach
	void saveSeamAndProperty() throws Exception {
		originalSecureStoreReader = getSecureStoreReader();
		savedApiKeyProperty = System.getProperty(ProviderConfigWriter.ENV_API_KEY);
		// Start every test from a known baseline: the -D property is cleared; the seam
		// is set per test.
		System.clearProperty(ProviderConfigWriter.ENV_API_KEY);
	}

	@AfterEach
	void restoreSeamAndProperty() throws Exception {
		setSecureStoreReader(originalSecureStoreReader);
		if (savedApiKeyProperty == null) {
			System.clearProperty(ProviderConfigWriter.ENV_API_KEY);
		} else {
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, savedApiKeyProperty);
		}
	}

	// -----------------------------------------------------------------------
	// Reflection helpers for the package-private secure-store seam.
	// The seam lives in package com.servoy.eclipse.opencode; this test is in
	// com.servoy.eclipse.opencode.tests.plainjunit, so it is reached reflectively.
	// -----------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Supplier<String> getSecureStoreReader() throws Exception {
		Field field = ProviderConfigWriter.class.getDeclaredField("secureStoreReader"); //$NON-NLS-1$
		field.setAccessible(true);
		return (Supplier<String>) field.get(null);
	}

	private static void setSecureStoreReader(Supplier<String> reader) throws Exception {
		Field field = ProviderConfigWriter.class.getDeclaredField("secureStoreReader"); //$NON-NLS-1$
		field.setAccessible(true);
		field.set(null, reader);
	}

	// =======================================================================
	// getApiKey() - secure-store-first / -D fallback precedence (AC3-AC5)
	// =======================================================================

	@Nested
	class GetApiKey {

		@Test
		@DisplayName("Seam returns a non-blank value ⇒ getApiKey() returns it (AC3)")
		void seamValue_returnedFromSecureStore() throws Exception {
			setSecureStoreReader(() -> "secure-key-xyz"); //$NON-NLS-1$
			assertEquals("secure-key-xyz", ProviderConfigWriter.getApiKey(), //$NON-NLS-1$
					"getApiKey() must return the secure-store value");
		}

		@Test
		@DisplayName("Seam value wins over a different -D GENAI_API_KEY (secure-node precedence) (AC3)")
		void seamValue_winsOverProperty() throws Exception {
			setSecureStoreReader(() -> "secure-key-xyz"); //$NON-NLS-1$
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			assertEquals("secure-key-xyz", ProviderConfigWriter.getApiKey(), //$NON-NLS-1$
					"the secure-store value must take precedence over the -D property");
		}

		@Test
		@DisplayName("Seam returns null ⇒ falls back to -D GENAI_API_KEY (AC4)")
		void seamNull_fallsBackToProperty() throws Exception {
			setSecureStoreReader(() -> null);
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			assertEquals("prop-key-abc", ProviderConfigWriter.getApiKey(), //$NON-NLS-1$
					"getApiKey() must fall back to the -D property when the seam yields null");
		}

		@Test
		@DisplayName("Seam returns blank ⇒ falls back to -D GENAI_API_KEY (AC4)")
		void seamBlank_fallsBackToProperty() throws Exception {
			setSecureStoreReader(() -> "   "); //$NON-NLS-1$
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			assertEquals("prop-key-abc", ProviderConfigWriter.getApiKey(), //$NON-NLS-1$
					"a blank secure-store value must not count as present");
		}

		@Test
		@DisplayName("Seam null and no -D property ⇒ getApiKey() returns null (AC4)")
		void seamNull_noProperty_returnsNull() throws Exception {
			setSecureStoreReader(() -> null);
			// -D property cleared by @BeforeEach.
			assertNull(ProviderConfigWriter.getApiKey(), "getApiKey() must be null when neither source has a value");
		}

		@Test
		@DisplayName("Seam blank and -D property blank ⇒ getApiKey() returns null (blank not present) (AC4)")
		void seamBlank_propertyBlank_returnsNull() throws Exception {
			setSecureStoreReader(() -> "   "); //$NON-NLS-1$
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "   "); //$NON-NLS-1$
			assertNull(ProviderConfigWriter.getApiKey(), "a blank -D property must not count as present");
		}

		@Test
		@DisplayName("Seam throws, -D property set ⇒ getApiKey() does not throw, falls back to property (AC5)")
		void seamThrows_fallsBackToProperty() throws Exception {
			setSecureStoreReader(() -> {
				throw new RuntimeException("simulated locked keyring");
			});
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			assertEquals("prop-key-abc", assertDoesNotThrow(ProviderConfigWriter::getApiKey), //$NON-NLS-1$
					"a throwing seam must fall through to the -D property, never propagate");
		}

		@Test
		@DisplayName("Seam throws, no -D property ⇒ getApiKey() does not throw, returns null (AC5)")
		void seamThrows_noProperty_returnsNull() throws Exception {
			setSecureStoreReader(() -> {
				throw new RuntimeException("simulated locked keyring");
			});
			// -D property cleared by @BeforeEach.
			assertNull(assertDoesNotThrow(ProviderConfigWriter::getApiKey),
					"a throwing seam with no -D property must yield null, never propagate");
		}
	}

	// =======================================================================
	// buildProviderEnvVars() - sources from the accessor (AC6)
	// =======================================================================

	@Nested
	class BuildProviderEnvVars {

		@Test
		@DisplayName("Seam value present ⇒ singleton {GENAI_API_KEY: <seam value>} (AC6)")
		void seamValue_returnsSingletonMap() throws Exception {
			setSecureStoreReader(() -> "secure-key-xyz"); //$NON-NLS-1$
			Map<String, String> env = ProviderConfigWriter.buildProviderEnvVars();
			assertEquals(1, env.size(), "Map must contain exactly one entry");
			assertEquals("secure-key-xyz", env.get(ProviderConfigWriter.ENV_API_KEY), //$NON-NLS-1$
					"value must come from the secure-store seam");
		}

		@Test
		@DisplayName("Seam null, -D property set ⇒ singleton {GENAI_API_KEY: <property>} (AC6)")
		void propertyFallback_returnsSingletonMap() throws Exception {
			setSecureStoreReader(() -> null);
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			Map<String, String> env = ProviderConfigWriter.buildProviderEnvVars();
			assertEquals(1, env.size(), "Map must contain exactly one entry");
			assertTrue(env.containsKey(ProviderConfigWriter.ENV_API_KEY), "GENAI_API_KEY must be present");
			assertEquals("prop-key-abc", env.get(ProviderConfigWriter.ENV_API_KEY), //$NON-NLS-1$
					"value must come from the -D fallback");
		}

		@Test
		@DisplayName("Seam null and no -D property ⇒ empty map (AC6)")
		void noValue_returnsEmptyMap() throws Exception {
			setSecureStoreReader(() -> null);
			// -D property cleared by @BeforeEach.
			Map<String, String> env = ProviderConfigWriter.buildProviderEnvVars();
			assertTrue(env.isEmpty(), "Map must be empty when no value is available");
		}

		@Test
		@DisplayName("Seam blank and -D property blank ⇒ empty map (AC6)")
		void blankValue_returnsEmptyMap() throws Exception {
			setSecureStoreReader(() -> "   "); //$NON-NLS-1$
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "   "); //$NON-NLS-1$
			Map<String, String> env = ProviderConfigWriter.buildProviderEnvVars();
			assertTrue(env.isEmpty(), "Map must be empty when only blank values are available");
		}

		@Test
		@DisplayName("Seam throws ⇒ no throw; falls back to -D property for the map (AC5/AC6)")
		void seamThrows_fallsBackToPropertyMap() throws Exception {
			setSecureStoreReader(() -> {
				throw new RuntimeException("simulated locked keyring");
			});
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "prop-key-abc"); //$NON-NLS-1$
			Map<String, String> env = assertDoesNotThrow(ProviderConfigWriter::buildProviderEnvVars);
			assertEquals("prop-key-abc", env.get(ProviderConfigWriter.ENV_API_KEY), //$NON-NLS-1$
					"a throwing seam must not break the env-var build; it falls back to -D");
		}
	}

	// =======================================================================
	// readSecureStoreValue() - direct, no keyring: must not throw (AC5)
	// =======================================================================

	@Nested
	class ReadSecureStoreValue {

		/**
		 * The real secure-store read must swallow any failure and return null in a
		 * plain-JUnit JVM (no live {@code SecurePreferences} keyring) rather than
		 * throwing. Invoked reflectively - it is package-private in a different
		 * package.
		 */
		@Test
		@DisplayName("Real readSecureStoreValue() returns null (never throws) with no keyring (AC5)")
		void readSecureStoreValue_noKeyring_returnsNullNeverThrows() throws Exception {
			Method m = ProviderConfigWriter.class.getDeclaredMethod("readSecureStoreValue"); //$NON-NLS-1$
			m.setAccessible(true);
			Object result = assertDoesNotThrow(() -> m.invoke(null),
					"readSecureStoreValue() must swallow secure-store failures, never throw");
			assertNull(result, "with no live keyring the secure-store read must report no value (null)");
		}
	}

	// =======================================================================
	// mergeProviderConfig - file does not exist: no-op
	// =======================================================================

	/**
	 * When the config file does not exist, mergeProviderConfig() is a no-op: no
	 * file is created.
	 */
	@Test
	@DisplayName("mergeProviderConfig(): absent file ⇒ no file created")
	void mergeProviderConfig_fileAbsent_noFileCreated() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$

		ProviderConfigWriter.mergeProviderConfig(configFile);

		assertFalse(Files.exists(configFile), "No file must be created when the config file does not exist");
	}

	// =======================================================================
	// mergeProviderConfig - existing file with $schema: unchanged
	// =======================================================================

	/**
	 * A file that already has the $schema key is returned unchanged (skip-write).
	 */
	@Test
	@DisplayName("mergeProviderConfig(): $schema already present ⇒ file unchanged")
	void mergeProviderConfig_schemaAlreadyPresent_fileUnchanged() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$
		String existing = "{\n" + //$NON-NLS-1$
				"  \"$schema\": \"https://opencode.ai/config.json\",\n" + //$NON-NLS-1$
				"  \"mcp\": {}\n" + //$NON-NLS-1$
				"}\n"; //$NON-NLS-1$
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);
		long modifiedBefore = Files.getLastModifiedTime(configFile).toMillis();

		try {
			Thread.sleep(50);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}

		ProviderConfigWriter.mergeProviderConfig(configFile);

		String after = Files.readString(configFile, StandardCharsets.UTF_8);
		assertEquals(existing, after, "File content must not change when $schema is already present");
		assertEquals(modifiedBefore, Files.getLastModifiedTime(configFile).toMillis(), "File must not be rewritten");
	}

	// =======================================================================
	// mergeProviderConfig - existing file without $schema: schema inserted
	// =======================================================================

	/**
	 * A file without the $schema key gets the schema inserted; all other keys are
	 * left untouched.
	 */
	@Test
	@DisplayName("mergeProviderConfig(): $schema missing ⇒ schema inserted, rest preserved")
	void mergeProviderConfig_schemaMissing_schemaInserted() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$
		String existing = "{\n" + //$NON-NLS-1$
				"  \"mcp\": {\n" + //$NON-NLS-1$
				"    \"eclipse-ide\": {\n" + //$NON-NLS-1$
				"      \"type\": \"remote\",\n" + //$NON-NLS-1$
				"      \"url\": \"http://localhost:{env:MCP_PORT}/mcp/eclipse-ide\"\n" + //$NON-NLS-1$
				"    }\n" + //$NON-NLS-1$
				"  }\n" + //$NON-NLS-1$
				"}\n"; //$NON-NLS-1$
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		ProviderConfigWriter.mergeProviderConfig(configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"$schema\""), "$schema must be inserted"); //$NON-NLS-1$
		assertTrue(content.contains(McpConfigWriter.SCHEMA_URL), "$schema URL must be correct");
		// Existing content preserved
		assertTrue(content.contains("\"mcp\""), "mcp block must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("\"eclipse-ide\""), "eclipse-ide entry must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("http://localhost:{env:MCP_PORT}/mcp/eclipse-ide"), "MCP URL must be preserved"); //$NON-NLS-1$
	}

	// =======================================================================
	// mergeProviderConfig - provider/model keys from zip are preserved as-is
	// =======================================================================

	/**
	 * The simplified mergeProviderConfig() does NOT touch provider, model, or
	 * small_model keys. A file containing those keys from the zip is left
	 * byte-for-byte unchanged (beyond any $schema insertion).
	 */
	@Test
	@DisplayName("mergeProviderConfig(): provider/model keys from zip are not touched")
	void mergeProviderConfig_providerKeysFromZip_notTouched() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$
		String existing = "{\n" + //$NON-NLS-1$
				"  \"$schema\": \"https://opencode.ai/config.json\",\n" + //$NON-NLS-1$
				"  \"provider\": {\n" + //$NON-NLS-1$
				"    \"litellm\": { \"name\": \"FromZip\" }\n" + //$NON-NLS-1$
				"  },\n" + //$NON-NLS-1$
				"  \"model\": \"litellm/zip-model\",\n" + //$NON-NLS-1$
				"  \"small_model\": \"litellm/zip-small\"\n" + //$NON-NLS-1$
				"}\n"; //$NON-NLS-1$
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		ProviderConfigWriter.mergeProviderConfig(configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertEquals(existing, content, "File must be byte-for-byte unchanged");
		assertTrue(content.contains("FromZip"), "Zip provider must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("zip-model"), "Zip model must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("zip-small"), "Zip small_model must be preserved"); //$NON-NLS-1$
	}

	// =======================================================================
	// mergeProviderConfig - user-added keys preserved
	// =======================================================================

	/**
	 * User-added top-level keys (e.g. theme, keybindings) survive a
	 * mergeProviderConfig() call untouched.
	 */
	@Test
	@DisplayName("mergeProviderConfig(): user-added top-level keys are preserved")
	void mergeProviderConfig_userAddedKeys_preserved() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$
		String existing = "{\n" + //$NON-NLS-1$
				"  \"$schema\": \"https://opencode.ai/config.json\",\n" + //$NON-NLS-1$
				"  \"theme\": \"dark\",\n" + //$NON-NLS-1$
				"  \"keybindings\": \"vim\"\n" + //$NON-NLS-1$
				"}\n"; //$NON-NLS-1$
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		ProviderConfigWriter.mergeProviderConfig(configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains("\"theme\""), "theme key must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("\"dark\""), "theme value must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("\"keybindings\""), "keybindings key must be preserved"); //$NON-NLS-1$
		assertTrue(content.contains("\"vim\""), "keybindings value must be preserved"); //$NON-NLS-1$
	}

	// =======================================================================
	// mergeProviderConfig - mcp block byte-for-byte unchanged
	// =======================================================================

	/**
	 * An mcp block present before the call is byte-for-byte identical after the
	 * call.
	 */
	@Test
	@DisplayName("mergeProviderConfig(): existing mcp block is byte-for-byte preserved")
	void mergeProviderConfig_mcpBlockUnchanged() throws IOException {
		Path configFile = tmp.resolve("opencode.json"); //$NON-NLS-1$
		String mcpBlock = "    \"eclipse-ide\": {\n" + //$NON-NLS-1$
				"      \"type\": \"remote\",\n" + //$NON-NLS-1$
				"      \"url\": \"http://localhost:{env:MCP_PORT}/mcp/eclipse-ide\",\n" + //$NON-NLS-1$
				"      \"headers\": {\n" + //$NON-NLS-1$
				"        \"Authorization\": \"Bearer {env:MCP_AUTH_TOKEN}\"\n" + //$NON-NLS-1$
				"      }\n" + //$NON-NLS-1$
				"    }"; //$NON-NLS-1$
		String existing = "{\n" + //$NON-NLS-1$
				"  \"$schema\": \"https://opencode.ai/config.json\",\n" + //$NON-NLS-1$
				"  \"mcp\": {\n" + //$NON-NLS-1$
				mcpBlock + "\n" + //$NON-NLS-1$
				"  }\n" + //$NON-NLS-1$
				"}\n"; //$NON-NLS-1$
		Files.writeString(configFile, existing, StandardCharsets.UTF_8);

		ProviderConfigWriter.mergeProviderConfig(configFile);

		String content = Files.readString(configFile, StandardCharsets.UTF_8);
		assertTrue(content.contains(mcpBlock), "The mcp block content must be byte-for-byte preserved");
	}
}

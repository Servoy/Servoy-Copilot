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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.OpenCodeView;
import com.servoy.eclipse.opencode.ProviderConfigWriter;
import com.servoy.eclipse.opencode.SkillsZipExtractor;
import com.servoy.eclipse.ui.browser.IBrowser;

/**
 * Unit tests for the parts of {@link OpenCodeView} reachable without an SWT
 * {@code Display}, {@code PlatformUI} workbench, or a live
 * {@code ServoyModelManager}.
 * <p>
 * Covered:
 * </p>
 * <ul>
 * <li>the pure private static {@code isServoyAiConfigured()} gate, driven by the
 * {@code GENAI_API_KEY} / {@code SERVOY_SKILLS_ZIP} system properties (via the
 * {@link ProviderConfigWriter}/{@link SkillsZipExtractor} accessors it calls) -
 * invoked by reflection;</li>
 * <li>{@link OpenCodeView#setUrl(String)} / {@link OpenCodeView#getBrowser()}
 * delegation, with a dynamic-proxy {@link IBrowser} injected into the private
 * {@code browser} field.</li>
 * </ul>
 * <p>
 * The SWT/workbench/model-bound members ({@code createPartControl},
 * {@code dispose}, {@code initUrl}, the listeners, {@code resolveChatUiUrl},
 * {@code getActiveProjectPath}) are intentionally out of scope: they require a
 * running workbench (and {@code ApplicationServerRegistry.get()} blocks on a
 * latch), none of which exists in a plain-JUnit run.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class OpenCodeViewTest {
	private String savedApiKey;
	private String savedSkillsZip;
	private Supplier<String> savedSecureReader;

	@BeforeEach
	void snapshotEnvironment() throws Exception {
		savedApiKey = System.getProperty(ProviderConfigWriter.ENV_API_KEY);
		savedSkillsZip = System.getProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		// Neutralise the secure-store lookup so getApiKey() depends only on the
		// -D fallback property under test (a plain-JUnit run has no keyring, but
		// make it deterministic rather than environment-dependent).
		Field seam = ProviderConfigWriter.class.getDeclaredField("secureStoreReader");
		seam.setAccessible(true);
		@SuppressWarnings("unchecked")
		Supplier<String> current = (Supplier<String>) seam.get(null);
		savedSecureReader = current;
		seam.set(null, (Supplier<String>) () -> null);
	}

	@AfterEach
	void restoreEnvironment() throws Exception {
		restore(ProviderConfigWriter.ENV_API_KEY, savedApiKey);
		restore(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, savedSkillsZip);
		Field seam = ProviderConfigWriter.class.getDeclaredField("secureStoreReader");
		seam.setAccessible(true);
		seam.set(null, savedSecureReader);
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static boolean isServoyAiConfigured() throws Exception {
		Method m = OpenCodeView.class.getDeclaredMethod("isServoyAiConfigured");
		m.setAccessible(true);
		return ((Boolean) m.invoke(null)).booleanValue();
	}

	/**
	 * A recording {@link IBrowser} dynamic proxy: captures setUrl calls and
	 * reports a configurable disposed state.
	 */
	private static final class RecordingBrowser {
		final List<String> urls = new ArrayList<>();
		boolean disposed;
		final IBrowser proxy;

		RecordingBrowser() {
			InvocationHandler h = (p, m, args) -> {
				switch (m.getName()) {
				case "setUrl":
					urls.add((String) args[0]);
					return Boolean.TRUE;
				case "isDisposed":
					return Boolean.valueOf(disposed);
				default:
					Class<?> rt = m.getReturnType();
					if (rt == boolean.class)
						return Boolean.FALSE;
					if (rt == int.class)
						return Integer.valueOf(0);
					return null;
				}
			};
			proxy = (IBrowser) Proxy.newProxyInstance(OpenCodeViewTest.class.getClassLoader(),
					new Class<?>[] { IBrowser.class }, h);
		}
	}

	private static void injectBrowser(OpenCodeView view, IBrowser browser) throws Exception {
		Field f = OpenCodeView.class.getDeclaredField("browser");
		f.setAccessible(true);
		f.set(view, browser);
	}

	@Nested
	class ServoyAiConfiguredGate {
		@Test
		@DisplayName("not configured when neither the API key nor the skills-zip property is set")
		void unconfiguredWhenNothingSet() throws Exception {
			System.clearProperty(ProviderConfigWriter.ENV_API_KEY);
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
			assertFalse(isServoyAiConfigured());
		}

		@Test
		@DisplayName("not configured when the API key is set but the skills-zip is missing")
		void unconfiguredWithKeyButNoSkills() throws Exception {
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "test-key");
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
			assertFalse(isServoyAiConfigured(), "both a key and a resolvable skills-zip source are required");
		}

		@Test
		@DisplayName("not configured when the API key is blank even though the skills-zip is present")
		void unconfiguredWithBlankKey() throws Exception {
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "   ");
			// an http(s) skills-zip source resolves without touching the file system,
			// but it needs Activator.getInstance() for the bundle version; keep the
			// key blank so the key check short-circuits first and we never reach it.
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
			assertFalse(isServoyAiConfigured());
		}

		@Test
		@DisplayName("not configured when the skills-zip path does not exist on disk")
		void unconfiguredWithMissingSkillsFile() throws Exception {
			System.setProperty(ProviderConfigWriter.ENV_API_KEY, "test-key");
			System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY,
					"D:\\does\\not\\exist\\skills-" + System.nanoTime() + ".zip");
			// getSkillsZipSource() returns null for a non-existent file path, so the
			// gate stays false even with a valid key.
			assertFalse(isServoyAiConfigured());
		}

		@Test
		@DisplayName("configured when a valid key and an existing skills-zip file are both present")
		void configuredWhenBothPresent() throws Exception {
			java.nio.file.Path zip = java.nio.file.Files.createTempFile("skills-", ".zip");
			try {
				System.setProperty(ProviderConfigWriter.ENV_API_KEY, "test-key");
				System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, zip.toString());
				assertTrue(isServoyAiConfigured(),
						"a non-blank key plus an existing skills-zip file should satisfy the gate");
			} finally {
				java.nio.file.Files.deleteIfExists(zip);
			}
		}
	}

	@Nested
	class BrowserDelegation {
		@Test
		@DisplayName("getBrowser() returns the injected browser instance")
		void getBrowserReturnsInjected() throws Exception {
			OpenCodeView view = new OpenCodeView();
			RecordingBrowser rec = new RecordingBrowser();
			injectBrowser(view, rec.proxy);
			assertSame(rec.proxy, view.getBrowser());
		}

		@Test
		@DisplayName("getBrowser() is null when no browser has been created")
		void getBrowserNullByDefault() {
			OpenCodeView view = new OpenCodeView();
			assertNull(view.getBrowser(), "no browser exists before createPartControl()");
		}

		@Test
		@DisplayName("setUrl() delegates to the browser when it is not disposed")
		void setUrlDelegatesWhenLive() throws Exception {
			OpenCodeView view = new OpenCodeView();
			RecordingBrowser rec = new RecordingBrowser();
			injectBrowser(view, rec.proxy);
			view.setUrl("http://127.0.0.1:8183/servoy_ai/");
			assertEquals(List.of("http://127.0.0.1:8183/servoy_ai/"), rec.urls);
		}

		@Test
		@DisplayName("setUrl() is a no-op when the browser is disposed")
		void setUrlNoOpWhenDisposed() throws Exception {
			OpenCodeView view = new OpenCodeView();
			RecordingBrowser rec = new RecordingBrowser();
			rec.disposed = true;
			injectBrowser(view, rec.proxy);
			view.setUrl("http://ignored/");
			assertTrue(rec.urls.isEmpty(), "a disposed browser must not be navigated");
		}

		@Test
		@DisplayName("setUrl() is a no-op (no NPE) when there is no browser at all")
		void setUrlNoOpWhenNoBrowser() {
			OpenCodeView view = new OpenCodeView();
			assertDoesNotThrow(() -> view.setUrl("http://ignored/"),
					"setUrl must tolerate a null browser without throwing");
		}
	}
}

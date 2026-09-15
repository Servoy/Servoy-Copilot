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

package com.servoy.eclipse.opencode.tomcat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the pure request-routing, path-resolution, directory-injection
 * and static-asset helpers of {@link OpencodeChatServlet}.
 * <p>
 * These tests exercise only the package-private static helpers — no OSGi
 * runtime, Tomcat container, or live opencode server is required. The class
 * lives in the {@code com.servoy.eclipse.opencode.tomcat} package to reach the
 * package-visible helpers.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class OpencodeChatServletTest {
	@Nested
	class RouteClassification {
		@Test
		@DisplayName("null path-info is not an API request")
		void nullPathIsNotApi() {
			assertFalse(OpencodeChatServlet.isApiRequest(null));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/rest_api", "/rest_api/", "/rest_api/session", "/rest_api/session/abc/message",
				"/rest_api/event" })
		@DisplayName("paths under the API prefix are API requests")
		void apiPrefixedPathsAreApi(String pathInfo) {
			assertTrue(OpencodeChatServlet.isApiRequest(pathInfo));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/", "/index.html", "/main-ABCD1234.js", "/rest_apinot", "/rest_apix/session",
				"/assets/logo.png" })
		@DisplayName("non-API-prefixed paths are static requests")
		void nonApiPathsAreNotApi(String pathInfo) {
			assertFalse(OpencodeChatServlet.isApiRequest(pathInfo));
		}

		@Test
		@DisplayName("'/rest_api' bare and '/rest_apinot' are distinguished (prefix must be followed by '/' or be exact)")
		void prefixBoundaryIsRespected() {
			assertAll(() -> assertTrue(OpencodeChatServlet.isApiRequest("/rest_api")),
					() -> assertFalse(OpencodeChatServlet.isApiRequest("/rest_apinot")),
					() -> assertFalse(OpencodeChatServlet.isApiRequest("/rest_api_extra")));
		}

		@Test
		@DisplayName("only exactly '/rest_api/event' is the SSE event stream")
		void eventStreamExactMatch() {
			assertAll(() -> assertTrue(OpencodeChatServlet.isEventStreamRequest("/rest_api/event")),
					() -> assertFalse(OpencodeChatServlet.isEventStreamRequest("/rest_api/event/")),
					() -> assertFalse(OpencodeChatServlet.isEventStreamRequest("/rest_api/eventual")),
					() -> assertFalse(OpencodeChatServlet.isEventStreamRequest("/rest_api/session")),
					() -> assertFalse(OpencodeChatServlet.isEventStreamRequest(null)));
		}
	}

	@Nested
	class UpstreamPathMapping {
		@ParameterizedTest
		@NullSource
		@DisplayName("null path-info maps to root upstream path")
		void nullMapsToRoot(String pathInfo) {
			assertEquals("/", OpencodeChatServlet.toUpstreamPath(pathInfo));
		}

		@Test
		@DisplayName("bare '/rest_api' strips to root upstream path")
		void barePrefixStripsToRoot() {
			assertEquals("/", OpencodeChatServlet.toUpstreamPath("/rest_api"));
		}

		@ParameterizedTest
		@CsvSource({ "/rest_api/session,/session", "/rest_api/session/abc/message,/session/abc/message",
				"/rest_api/event,/event", "/rest_api/find/file,/find/file" })
		@DisplayName("API prefix is stripped, remainder preserved")
		void prefixIsStripped(String pathInfo, String expected) {
			assertEquals(expected, OpencodeChatServlet.toUpstreamPath(pathInfo));
		}
	}

	@Nested
	class DirectoryInjection {
		@Test
		@DisplayName("null directory leaves the path unchanged")
		void nullDirectoryLeavesPathUnchanged() {
			assertEquals("/session", OpencodeChatServlet.injectDirectory("/session", null));
		}

		@Test
		@DisplayName("empty directory leaves the path unchanged")
		void emptyDirectoryLeavesPathUnchanged() {
			assertEquals("/session", OpencodeChatServlet.injectDirectory("/session", ""));
		}

		@Test
		@DisplayName("directory appended as new query param when none present")
		void directoryAppendedWhenNoQuery() {
			assertEquals("/session?directory=%2Fhome%2Fproj",
					OpencodeChatServlet.injectDirectory("/session", "/home/proj"));
		}

		@Test
		@DisplayName("directory appended with '&' when query already present")
		void directoryAppendedWhenQueryPresent() {
			assertEquals("/session?limit=10&directory=%2Fhome%2Fproj",
					OpencodeChatServlet.injectDirectory("/session?limit=10", "/home/proj"));
		}

		@Test
		@DisplayName("directory is URL-encoded (spaces, special chars)")
		void directoryIsUrlEncoded() {
			String result = OpencodeChatServlet.injectDirectory("/session", "C:\\my proj\\a&b");
			assertTrue(result.startsWith("/session?directory="), "expected directory param, got: " + result);
			assertFalse(result.contains(" "), "space must be encoded");
			assertFalse(result.endsWith("a&b"), "raw '&' must be encoded, not left literal");
		}

		@Test
		@DisplayName("client cannot spoof directory: existing directory param is NOT overridden")
		void clientCannotOverrideDirectory() {
			String result = OpencodeChatServlet.injectDirectory("/session?directory=/evil", "/home/proj");
			assertEquals("/session?directory=/evil", result,
					"servlet must not append a second directory param when one is already present");
		}

		@Test
		@DisplayName("existing directory param among others is detected and not duplicated")
		void existingDirectoryAmongOthersNotDuplicated() {
			String result = OpencodeChatServlet.injectDirectory("/session?limit=5&directory=/x&foo=1", "/home/proj");
			assertEquals("/session?limit=5&directory=/x&foo=1", result);
		}

		@Test
		@DisplayName("a param merely containing 'directory' as substring does not block injection")
		void similarlyNamedParamDoesNotBlockInjection() {
			String result = OpencodeChatServlet.injectDirectory("/session?mydirectory=1", "/home/proj");
			assertTrue(result.contains("&directory=%2Fhome%2Fproj"),
					"a param like 'mydirectory' must not be mistaken for 'directory'; got: " + result);
		}

		@Test
		@DisplayName("empty query string (trailing '?') still appends directory without a stray '&'")
		void emptyQueryStringAppendsWithoutSeparator() {
			String result = OpencodeChatServlet.injectDirectory("/session?", "/home/proj");
			assertEquals("/session?directory=%2Fhome%2Fproj", result);
		}
	}

	@Nested
	class StaticAssetPathResolution {
		@ParameterizedTest
		@ValueSource(strings = { "", "/" })
		@DisplayName("root path-info resolves to index.html")
		void rootResolvesToIndex(String pathInfo) {
			assertEquals("/index.html", OpencodeChatServlet.normalizeAssetPath(pathInfo));
		}

		@ParameterizedTest
		@NullSource
		@DisplayName("null path-info resolves to index.html")
		void nullResolvesToIndex(String pathInfo) {
			assertEquals("/index.html", OpencodeChatServlet.normalizeAssetPath(pathInfo));
		}

		@Test
		@DisplayName("a normal asset path is preserved with a single leading slash")
		void normalAssetPreserved() {
			assertAll(
					() -> assertEquals("/main-ABCD1234.js",
							OpencodeChatServlet.normalizeAssetPath("/main-ABCD1234.js")),
					() -> assertEquals("/assets/logo.png", OpencodeChatServlet.normalizeAssetPath("assets/logo.png")));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/../etc/passwd", "../etc/passwd", "/assets/../../etc/passwd", "/a/../../etc/passwd",
				"/../../../../windows/system32/config" })
		@DisplayName("path traversal vectors are neutralized to index.html (never escape the dist root)")
		void traversalVectorsNeutralized(String pathInfo) {
			String result = OpencodeChatServlet.normalizeAssetPath(pathInfo);
			assertAll(() -> assertFalse(result.contains(".."), "result must not contain '..': " + result),
					() -> assertFalse(result.contains("etc/passwd"), "traversal target must not survive: " + result),
					() -> assertFalse(result.contains("system32"), "traversal target must not survive: " + result));
		}

		@Test
		@DisplayName("a nested path that normalizes cleanly within the root is preserved")
		void cleanNestedPathPreserved() {
			assertEquals("/assets/img/logo.png", OpencodeChatServlet.normalizeAssetPath("/assets/img/logo.png"));
		}

		@Test
		@DisplayName("intra-path '.' segments are collapsed without escaping the root")
		void dotSegmentsCollapsed() {
			String result = OpencodeChatServlet.normalizeAssetPath("/assets/./logo.png");
			assertAll(() -> assertTrue(result.startsWith("/"), "must have leading slash: " + result),
					() -> assertFalse(result.contains(".."), "must not contain '..': " + result));
		}
	}

	@Nested
	class HashedAssetDetection {
		@ParameterizedTest
		@ValueSource(strings = { "/main-ABCD1234.js", "/polyfills-0a1b2c3d.js", "/styles-DEADBEEF99.css",
				"/chunk-1234567890abcdef.js" })
		@DisplayName("content-hashed asset names are recognized")
		void hashedAssetsRecognized(String assetPath) {
			assertTrue(OpencodeChatServlet.isHashedAsset(assetPath), assetPath + " should be treated as hashed");
		}

		@ParameterizedTest
		@ValueSource(strings = { "/index.html", "/main.js", "/styles.css", "/favicon.ico", "/assets/logo.png",
				"/app-123.js" })
		@DisplayName("un-hashed / short-suffix asset names are not recognized as hashed")
		void nonHashedAssetsNotRecognized(String assetPath) {
			assertFalse(OpencodeChatServlet.isHashedAsset(assetPath), assetPath + " should NOT be treated as hashed");
		}
	}
}

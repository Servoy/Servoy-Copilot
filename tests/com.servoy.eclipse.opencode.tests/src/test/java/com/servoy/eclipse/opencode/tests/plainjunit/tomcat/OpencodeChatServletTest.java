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

package com.servoy.eclipse.opencode.tests.plainjunit.tomcat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;
import com.servoy.eclipse.opencode.tomcat.ServletTestAccess;

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
		@DisplayName("'/solution' is a solution request and not an API request")
		void solutionPathIsSolution() {
			assertAll(() -> assertTrue(OpencodeChatServlet.isSolutionRequest("/solution")),
					() -> assertFalse(OpencodeChatServlet.isApiRequest("/solution")));
		}

		@ParameterizedTest
		@ValueSource(strings = { "", "/", "/rest_api/solution", "/solution/x", "/Solution", "/solution/", "solution" })
		@DisplayName("other paths are not solution requests")
		void otherPathsAreNotSolution(String pathInfo) {
			assertFalse(OpencodeChatServlet.isSolutionRequest(pathInfo));
		}

		@Test
		@DisplayName("null path-info is not a solution request")
		void nullIsNotSolution() {
			assertFalse(OpencodeChatServlet.isSolutionRequest(null));
		}

		@Test
		@DisplayName("null path-info is not an API request")
		void nullPathIsNotApi() {
			assertFalse(OpencodeChatServlet.isApiRequest(null));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/rest_api", "/rest_api/", "/rest_api/session", "/rest_api/session/abc/message",
				"/rest_api/event", "/rest_api/session/ses_x/form", "/rest_api/session/ses_x/form/frm_y/reply",
				"/rest_api/session/ses_x/form/frm_y" })
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
		@DisplayName("null path-info maps to the upstream API root '/api'")
		void nullMapsToRoot(String pathInfo) {
			assertEquals("/api", OpencodeChatServlet.toUpstreamPath(pathInfo));
		}

		@Test
		@DisplayName("bare '/rest_api' maps to the upstream API root '/api'")
		void barePrefixStripsToRoot() {
			assertEquals("/api", OpencodeChatServlet.toUpstreamPath("/rest_api"));
		}

		@ParameterizedTest
		@CsvSource({ "/rest_api/session,/api/session", "/rest_api/session/abc/message,/api/session/abc/message",
				"/rest_api/event,/api/event", "/rest_api/find/file,/api/find/file",
				"/rest_api/session/ses_x/form,/api/session/ses_x/form",
				"/rest_api/session/ses_x/form/frm_y/reply,/api/session/ses_x/form/frm_y/reply",
				"/rest_api/session/ses_x/form/frm_y,/api/session/ses_x/form/frm_y" })
		@DisplayName("API prefix is rewritten to the upstream '/api' prefix, remainder preserved")
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
		@DisplayName("client cannot spoof directory: the client param is removed and replaced by the BFF value")
		void clientCannotOverrideDirectory() {
			String result = OpencodeChatServlet.injectDirectory("/session?directory=/evil", "/home/proj");
			assertEquals("/session?directory=%2Fhome%2Fproj", result);
		}

		@Test
		@DisplayName("client directory among other params is removed, the others keep their order, one BFF value is appended")
		void existingDirectoryAmongOthersReplaced() {
			String result = OpencodeChatServlet.injectDirectory("/session?limit=5&directory=/x&foo=1", "/home/proj");
			assertEquals("/session?limit=5&foo=1&directory=%2Fhome%2Fproj", result);
		}

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"/s?directory=C%3A%5CUsers|/p|/s?directory=%2Fp",
				"/s?Directory=x&limit=1|/p|/s?limit=1&directory=%2Fp",
				"/s?director%79=x|/p|/s?directory=%2Fp",
				"/s?directory=a&directory=b&directory|/p|/s?directory=%2Fp",
				"/s?workspace=w&a=1|/p|/s?a=1&directory=%2Fp",
				"/s?WORKSPACE=w|/p|/s?directory=%2Fp",
				"/s?a=1&DIRECTORY=x&b=2|/p|/s?a=1&b=2&directory=%2Fp" })
		@DisplayName("every client directory/workspace spelling is replaced by exactly one BFF directory")
		void clientDirectoryAlwaysReplaced(String input, String directory, String expected) {
			assertEquals(expected, OpencodeChatServlet.injectDirectory(input, directory));
		}

		@Test
		@DisplayName("without a BFF directory the client directory/workspace params are still stripped")
		void clientDirectoryStrippedWithoutBffDirectory() {
			assertAll(() -> assertEquals("/s", OpencodeChatServlet.injectDirectory("/s?directory=x", null)),
					() -> assertEquals("/s?a=1", OpencodeChatServlet.injectDirectory("/s?workspace=w&a=1", "")));
		}

		@Test
		@DisplayName("a client directory is never present in the result, only the BFF one")
		void clientDirectoryNotPresentInResult() {
			String result = OpencodeChatServlet.injectDirectory("/s?directory=C%3A%5CUsers%5Cevil", "/p");
			assertAll(() -> assertFalse(result.contains("evil"), result),
					() -> assertEquals(1, result.split("directory=", -1).length - 1, result));
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
	class BodyLocationInjection {
		private static final ObjectMapper JSON = new ObjectMapper();

		private static String utf8(byte[] bytes) {
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		}

		private static byte[] bytes(String s) {
			return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		}

		@Test
		@DisplayName("only session.create and session.import carry the directory in the body")
		void endpointDetection() {
			assertAll(
					() -> assertTrue(OpencodeChatServlet.isBodyLocationEndpoint("/api/session"),
							"session.create must be a body-location endpoint"),
					() -> assertTrue(OpencodeChatServlet.isBodyLocationEndpoint("/api/session?directory=%2Fx"),
							"a query string must not defeat detection"),
					() -> assertTrue(
							OpencodeChatServlet.isBodyLocationEndpoint("/api/experimental/session/import"),
							"session.import must be a body-location endpoint"),
					() -> assertFalse(OpencodeChatServlet.isBodyLocationEndpoint("/api/session/ses_1"),
							"a per-session endpoint (session.get) must not be treated as create"),
					() -> assertFalse(OpencodeChatServlet.isBodyLocationEndpoint("/api/session/ses_1/fork"),
							"session.fork must not be treated as create"),
					() -> assertFalse(OpencodeChatServlet.isBodyLocationEndpoint("/api/config"),
							"unrelated endpoints must not be affected"),
					() -> assertFalse(OpencodeChatServlet.isBodyLocationEndpoint(null)));
		}

		@Test
		@DisplayName("empty or null body becomes a fresh object carrying only the location")
		void emptyBodyGetsLocation() {
			String expected = "{\"location\":{\"directory\":\"/home/proj\"}}";
			assertAll(
					() -> assertEquals(expected, utf8(OpencodeChatServlet.ensureBodyLocation(null, "/home/proj"))),
					() -> assertEquals(expected,
							utf8(OpencodeChatServlet.ensureBodyLocation(new byte[0], "/home/proj"))),
					() -> assertEquals(expected,
							utf8(OpencodeChatServlet.ensureBodyLocation(bytes("   "), "/home/proj"))));
		}

		@Test
		@DisplayName("empty object body gets a location with no stray comma")
		void emptyObjectGetsLocation() {
			assertEquals("{\"location\":{\"directory\":\"/home/proj\"}}",
					utf8(OpencodeChatServlet.ensureBodyLocation(bytes("{}"), "/home/proj")));
		}

		@Test
		@DisplayName("location is added to a body, existing members are kept")
		void existingMembersPreserved() throws Exception {
			JsonNode node = JSON.readTree(
					OpencodeChatServlet.ensureBodyLocation(bytes("{\"title\":\"hi\"}"), "/home/proj"));
			assertAll(() -> assertEquals("hi", node.path("title").asText()),
					() -> assertEquals("/home/proj", node.path("location").path("directory").asText()),
					() -> assertEquals(2, node.size()));
		}

		@Test
		@DisplayName("a client-supplied location is replaced by the BFF directory, other members are kept")
		void explicitLocationOverwritten() throws Exception {
			String original = "{\"location\":{\"directory\":\"/other\",\"x\":1},\"title\":\"t\"}";
			JsonNode node = JSON.readTree(OpencodeChatServlet.ensureBodyLocation(bytes(original), "/home/proj"));
			assertAll(() -> assertEquals("/home/proj", node.path("location").path("directory").asText()),
					() -> assertFalse(node.path("location").has("x"), "the client location must be dropped entirely"),
					() -> assertEquals("t", node.path("title").asText()),
					() -> assertEquals(2, node.size()));
		}

		@ParameterizedTest
		@ValueSource(strings = { "{\"location\":null}", "{\"location\":\"/evil\"}", "{\"location\":[1]}",
				"{\"location\":{}}" })
		@DisplayName("a non-object / null / empty client location is also replaced")
		void oddLocationValuesOverwritten(String original) throws Exception {
			JsonNode node = JSON.readTree(OpencodeChatServlet.ensureBodyLocation(bytes(original), "/home/proj"));
			assertEquals("/home/proj", node.path("location").path("directory").asText());
		}

		@ParameterizedTest
		@ValueSource(strings = { "not json", "[1,2]", "\"str\"", "42", "{\"a\":" })
		@DisplayName("a body that is not a JSON object (or does not parse) is returned unchanged")
		void nonObjectBodyUnchanged(String original) {
			assertArrayEquals(bytes(original), OpencodeChatServlet.ensureBodyLocation(bytes(original), "/home/proj"));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/api/Session", "/api/session/", "/api/session/?x=1", "/api/SESSION//",
				"/api/experimental/session/import/", "/api/Experimental/Session/Import?directory=x" })
		@DisplayName("endpoint detection tolerates case, trailing slashes and a query")
		void endpointVariantsDetected(String upstreamPath) {
			assertTrue(OpencodeChatServlet.isBodyLocationEndpoint(upstreamPath), upstreamPath);
		}

		@ParameterizedTest
		@ValueSource(strings = { "/api/sessions", "/api/session/x", "/api/experimental/session", "/api",
				"/api/session/import", "", "/session" })
		@DisplayName("other upstream paths are not body-location endpoints")
		void otherEndpointsNotDetected(String upstreamPath) {
			assertFalse(OpencodeChatServlet.isBodyLocationEndpoint(upstreamPath), upstreamPath);
		}

		@Test
		@DisplayName("Windows backslashes in the directory are JSON-escaped")
		void windowsPathEscaped() {
			String result = utf8(OpencodeChatServlet.ensureBodyLocation(bytes("{}"), "C:\\temp\\ws"));
			assertAll(
					() -> assertTrue(result.contains("C:\\\\temp\\\\ws"),
							"backslashes must be doubled for valid JSON; got: " + result),
					() -> assertFalse(result.contains("C:\\temp\\ws\""),
							"a raw single backslash would make the body invalid JSON; got: " + result));
		}

		@Test
		@DisplayName("jsonEscape escapes quotes and backslashes")
		void jsonEscapeBasics() {
			assertAll(() -> assertEquals("C:\\\\x", OpencodeChatServlet.jsonEscape("C:\\x")),
					() -> assertEquals("a\\\"b", OpencodeChatServlet.jsonEscape("a\"b")));
		}
	}

	@Nested
	class FileRoute {
		@Test
		@DisplayName("only exactly '/file' is the file reader route and it is not an API request")
		void fileRouteExact() {
			assertAll(() -> assertTrue(OpencodeChatServlet.isFileRequest("/file")),
					() -> assertFalse(OpencodeChatServlet.isApiRequest("/file")),
					() -> assertFalse(OpencodeChatServlet.isFileRequest(null)));
		}

		@ParameterizedTest
		@ValueSource(strings = { "/file/", "/File", "/FILE", "/rest_api/file", "/files", "/file?x=1", "file", "",
				"/", "/solution", "/file/x" })
		@DisplayName("every other path is not the file reader route")
		void otherPathsNotFile(String pathInfo) {
			assertFalse(OpencodeChatServlet.isFileRequest(pathInfo), pathInfo);
		}
	}

	@Nested
	class UsageRoute {
		@Test
		@DisplayName("only exactly '/usage' is the usage route, and it is neither an API nor a file request")
		void usageRouteExact() {
			assertAll(() -> assertTrue(OpencodeChatServlet.isUsageRequest("/usage")),
					() -> assertFalse(OpencodeChatServlet.isApiRequest("/usage")),
					() -> assertFalse(OpencodeChatServlet.isFileRequest("/usage")),
					() -> assertFalse(OpencodeChatServlet.isUsageRequest(null)));
		}

		@ParameterizedTest
		@ValueSource(strings = { "", "/", "/usage/x", "/usage/", "/rest_api/usage", "/Usage", "/USAGE", "usage",
				"/usages", "/file", "/solution" })
		@DisplayName("every other path is not the usage route")
		void otherPathsNotUsage(String pathInfo) {
			assertFalse(OpencodeChatServlet.isUsageRequest(pathInfo), pathInfo);
		}
	}

	@Nested
	class BlockedApiPaths {
		@ParameterizedTest
		@ValueSource(strings = { "/rest_api/fs/read/x", "/rest_api/fs", "/rest_api/fs/", "/rest_api/FS/list",
				"/rest_api/Fs/list", "/rest_api/experimental/fs/write", "/rest_api//fs/read", "/rest_api/./fs",
				"/rest_api/session/../fs/read", "/rest_api/..", "/rest_api/.", "/rest_api/session/%2e%2e/x",
				"/rest_api/%66s/read", "/rest_api/%2566s/read", "/rest_api/a\\b", "/rest_api/a\\fs",
				"/rest_api/a\0b", "/rest_api/a\nb", "/rest_api/a\tb", "/rest_api/a\u007fb",
				"/rest_api/session/ses_1/%66s" })
		@DisplayName("fs segments, dot segments and smuggled segments (%, backslash, control char) are blocked")
		void blocked(String pathInfo) {
			assertTrue(OpencodeChatServlet.isBlockedApiPath(pathInfo), pathInfo);
		}

		@ParameterizedTest
		@ValueSource(strings = { "/rest_api", "/rest_api/", "/rest_api/session", "/rest_api/session/fsx",
				"/rest_api/event", "/rest_api/fsx", "/rest_api/xfs", "/rest_api/session/ses_1/message",
				"/rest_api/session/ses_1:x@y", "/rest_api/a.b", "/rest_api/..a", "/rest_api/a..", "/solution",
				"/file", "/index.html" })
		@DisplayName("ordinary API paths (and non-API paths) are not blocked")
		void notBlocked(String pathInfo) {
			assertFalse(OpencodeChatServlet.isBlockedApiPath(pathInfo), pathInfo);
		}

		@Test
		@DisplayName("null is not blocked")
		void nullNotBlocked() {
			assertFalse(OpencodeChatServlet.isBlockedApiPath(null));
		}
	}

	@Nested
	class SafeUpstreamPath {
		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"/rest_api|/api",
				"/rest_api/|/api",
				"/rest_api/session|/api/session",
				"/rest_api/session/|/api/session",
				"/rest_api//session///abc//|/api/session/abc",
				"/rest_api/a b|/api/a%20b",
				"/rest_api/\u00e9|/api/%C3%A9",
				"/rest_api/a%2Fb|/api/a%252Fb",
				"/rest_api/a?b#c|/api/a%3Fb%23c",
				"/rest_api/a;b+c|/api/a%3Bb%2Bc",
				"/rest_api/a\\b|/api/a%5Cb",
				"/rest_api/session/ses_1:x@y-z.w~v|/api/session/ses_1:x@y-z.w~v" })
		@DisplayName("empty segments are dropped and every segment is percent-encoded exactly once")
		void safePath(String pathInfo, String expected) {
			assertEquals(expected, OpencodeChatServlet.toSafeUpstreamPath(pathInfo));
		}

		@Test
		@DisplayName("null path-info maps to the upstream root")
		void nullMapsToRoot() {
			assertEquals("/api", OpencodeChatServlet.toSafeUpstreamPath(null));
		}

		@Test
		@DisplayName("the result never contains a query, fragment or raw space")
		void noInjection() {
			String result = OpencodeChatServlet.toSafeUpstreamPath("/rest_api/x?directory=/evil#frag y");
			assertAll(() -> assertFalse(result.contains("?"), result), () -> assertFalse(result.contains("#"), result),
					() -> assertFalse(result.contains(" "), result));
		}
	}

	@Nested
	class StripDirectoryParam {
		@ParameterizedTest
		@CsvSource(delimiter = '|', value = {
				"directory=x|",
				"Directory=x|",
				"DIRECTORY=x|",
				"director%79=x|",
				"%64irectory=x|",
				"directory=a&directory=b|",
				"directory|",
				"directory=|",
				"%20directory=x|",
				"+directory=x|",
				"workspace=w|",
				"Workspace|",
				"limit=1&directory=x&b=2|limit=1&b=2",
				"a=1&&b=2|a=1&b=2",
				"a=directory|a=directory",
				"mydirectory=1|mydirectory=1",
				"directory%3D=1|directory%3D=1",
				"bad%zz=1&directory=x|bad%zz=1" })
		@DisplayName("directory/workspace params are removed (decoded, case-insensitive), others are kept in order")
		void strip(String query, String expected) {
			assertEquals(expected == null ? "" : expected, OpencodeChatServlet.stripDirectoryParam(query));
		}

		@Test
		@DisplayName("null and empty queries give an empty string")
		void nullAndEmpty() {
			assertAll(() -> assertEquals("", OpencodeChatServlet.stripDirectoryParam(null)),
					() -> assertEquals("", OpencodeChatServlet.stripDirectoryParam("")));
		}
	}

	@Nested
	class ForwardableHeaders {
		@ParameterizedTest
		@ValueSource(strings = { "X-OpenCode-Directory", "x-opencode-directory", "X-OPENCODE-DIRECTORY",
				"Authorization", "AUTHORIZATION", "Cookie", "cookie", "Origin", "origin", "X-Opencode-Workspace",
				"x-opencode-workspace", "Connection", "Host", "host", "Keep-Alive", "Proxy-Authenticate",
				"Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade", "Content-Length",
				"Accept-Encoding" })
		@DisplayName("hop-by-hop, BFF-owned and client-identity headers are never forwarded")
		void notForwarded(String name) {
			assertFalse(OpencodeChatServlet.isForwardableRequestHeader(name), name);
		}

		@ParameterizedTest
		@ValueSource(strings = { "content-type", "Content-Type", "accept", "Accept-Language", "Last-Event-ID",
				"Cache-Control", "User-Agent", "X-Custom" })
		@DisplayName("ordinary headers are forwarded")
		void forwarded(String name) {
			assertTrue(OpencodeChatServlet.isForwardableRequestHeader(name), name);
		}

		@Test
		@DisplayName("a null header name is not forwarded")
		void nullNotForwarded() {
			assertFalse(OpencodeChatServlet.isForwardableRequestHeader(null));
		}
	}

	@Nested
	class LoopbackRequestCheck {
		@ParameterizedTest
		@CsvSource(delimiter = '|', nullValues = "NULL", value = {
				"localhost:8183|NULL|NULL",
				"127.0.0.1:8183|NULL|NULL",
				"[::1]:8183|NULL|NULL",
				"LOCALHOST|NULL|NULL",
				"localhost|same-origin|NULL",
				"localhost|SAME-ORIGIN|NULL",
				"127.0.0.1:8183|NULL|http://127.0.0.1:8183",
				"localhost:8183|same-origin|http://LOCALHOST:8183" })
		@DisplayName("loopback Host with absent or same-origin Sec-Fetch-Site/Origin is accepted")
		void accepted(String host, String site, String origin) {
			assertTrue(ServletTestAccess.isSameOriginLoopbackRequest(host, site, origin));
		}

		@ParameterizedTest
		@CsvSource(delimiter = '|', nullValues = "NULL", value = {
				"NULL|NULL|NULL",
				"|NULL|NULL",
				"evil.example|NULL|NULL",
				"127.0.0.1.evil.com|NULL|NULL",
				"localhost.evil.com:8183|NULL|NULL",
				"evil.example:8183|NULL|NULL",
				"[::1|NULL|NULL",
				"[::2]:8183|NULL|NULL",
				"localhost:8183|cross-site|NULL",
				"localhost:8183|same-site|NULL",
				"localhost:8183|none|NULL",
				"localhost:8183|NULL|http://evil.example",
				"localhost:8183|NULL|http://localhost:9999",
				"localhost:8183|NULL|null",
				"localhost:8183|same-origin|http://evil.example" })
		@DisplayName("bad Host, cross-site Sec-Fetch-Site or foreign Origin is refused")
		void refused(String host, String site, String origin) {
			assertFalse(ServletTestAccess.isSameOriginLoopbackRequest(host, site, origin));
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

	@Nested
	class BasicAuthHeader {
		/**
		 * {@code basicAuthHeader(String)} is package-private on
		 * {@link OpencodeChatServlet} (in {@code com.servoy.eclipse.opencode.tomcat}).
		 * These tests live in a different package, so it is reached by reflection
		 * rather than by widening the production method's visibility.
		 */
		private static String basicAuthHeader(String password) throws Exception {
			java.lang.reflect.Method m = OpencodeChatServlet.class.getDeclaredMethod("basicAuthHeader", String.class);
			m.setAccessible(true);
			return (String) m.invoke(null, password);
		}

		@Test
		@DisplayName("null password yields no Authorization header (unsecured server)")
		void nullPasswordNoHeader() throws Exception {
			assertEquals(null, basicAuthHeader(null));
		}

		@Test
		@DisplayName("empty password yields no Authorization header")
		void emptyPasswordNoHeader() throws Exception {
			assertEquals(null, basicAuthHeader(""));
		}

		@Test
		@DisplayName("a password produces a Basic header over the fixed 'opencode' user")
		void passwordProducesBasicHeader() throws Exception {
			// opencode:s3cr3t -> base64
			String expected = "Basic " + java.util.Base64.getEncoder()
					.encodeToString("opencode:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			assertEquals(expected, basicAuthHeader("s3cr3t"));
		}

		@Test
		@DisplayName("the header is a decodable 'user:password' credential pair")
		void headerDecodesToUserColonPassword() throws Exception {
			String header = basicAuthHeader("p@ss:word");
			assertTrue(header.startsWith("Basic "), header);
			String decoded = new String(java.util.Base64.getDecoder().decode(header.substring("Basic ".length())),
					java.nio.charset.StandardCharsets.UTF_8);
			assertEquals("opencode:p@ss:word", decoded,
					"only the first colon separates user from password; the rest belongs to the password");
		}
	}
}

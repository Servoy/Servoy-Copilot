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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.opencode.tests.plainjunit.tomcat.FileCall.Reply;
import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;
import com.servoy.eclipse.opencode.tomcat.WorkspaceFileReader;
import com.servoy.eclipse.opencode.tomcat.WorkspaceFileReader.Result;
import com.servoy.eclipse.opencode.tomcat.WorkspaceFileReader.Status;

/**
 * The security matrix of {@link WorkspaceFileReader} (SVY-21851 spec section 6).
 * <p>
 * Every non-OK outcome is also pushed through the real servlet JSON mapping
 * ({@code GET /file}) and must produce the exact fixed body of its status, so
 * no root, resolved path or requested string can leak and the 403 body is
 * identical for every cause. Links are real junctions (Windows) or symlinks
 * (elsewhere); a link that cannot be created FAILS the test.
 * </p>
 */
class WorkspaceFileReaderTest {
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
			.contains("win");
	private static final boolean CASE_INSENSITIVE = WINDOWS
			|| System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	private static final int MIB2 = 2 * 1024 * 1024;

	@TempDir
	Path tmp;

	Path ws;
	Path outside;
	Path home;
	Path servoyDir;
	WorkspaceFileReader reader;
	private final List<Path> links = new ArrayList<>();

	@BeforeEach
	void fixture() throws IOException {
		ws = Files.createDirectories(tmp.resolve("ws"));
		write(ws.resolve("handoff/t/spec.md"), "# spec\n");
		write(ws.resolve("src/a.js"), "var a = 1;\n");
		write(ws.resolve(".git/config"), "[core]\n");
		write(tmp.resolve("ws-evil/secret.txt"), "evil");
		write(tmp.resolve("outside/secret.txt"), "secret");
		outside = tmp.resolve("outside");
		home = Files.createDirectories(tmp.resolve("home"));
		write(home.resolve(".servoy/opencode/opencode.json"), "{}");
		servoyDir = home.resolve(".servoy");
		reader = new WorkspaceFileReader(servoyDir, WorkspaceFileReader.DEFAULT_MAX_BYTES);
	}

	@AfterEach
	void removeLinks() throws IOException {
		// remove the link itself (never its target) before the TempDir cleanup runs
		for (Path link : links) {
			if (Files.exists(link, LinkOption.NOFOLLOW_LINKS))
				Files.delete(link);
		}
	}

	// ----- helpers ----------------------------------------------------------

	private static void write(Path file, String content) throws IOException {
		write(file, content.getBytes(StandardCharsets.UTF_8));
	}

	private static void write(Path file, byte[] content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, content);
	}

	/** Junction on Windows (no privilege needed), symlink elsewhere. Fails loudly. */
	private Path link(Path link, Path target) throws Exception {
		try {
			if (WINDOWS) {
				Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
						.redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (!p.waitFor(30, TimeUnit.SECONDS))
					fail("mklink /J timed out for " + link);
				if (p.exitValue() != 0)
					fail("could not create junction " + link + " -> " + target + ": " + out);
			} else {
				Files.createSymbolicLink(link, target);
			}
		} catch (IOException | UnsupportedOperationException e) {
			fail("could not create link " + link + " -> " + target + ": " + e);
		}
		if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS))
			fail("link was not created: " + link);
		links.add(link);
		return link;
	}

	private Result read(String input) {
		return reader.read(ws.toString(), input);
	}

	private static String jsonFor(Status status) {
		return switch (status) {
		case BAD_REQUEST -> "{\"error\":\"bad_request\"}";
		case FORBIDDEN -> "{\"error\":\"forbidden\"}";
		case NOT_FOUND -> "{\"error\":\"not_found\"}";
		case TOO_LARGE -> "{\"error\":\"too_large\"}";
		case BINARY -> "{\"error\":\"binary\"}";
		case ERROR -> "{\"error\":\"read_failed\"}";
		default -> throw new IllegalArgumentException(status.name());
		};
	}

	private int httpFor(Status status) {
		return switch (status) {
		case BAD_REQUEST -> 400;
		case FORBIDDEN -> 403;
		case NOT_FOUND -> 404;
		case TOO_LARGE -> 413;
		case BINARY -> 415;
		case ERROR -> 500;
		default -> throw new IllegalArgumentException(status.name());
		};
	}

	/** Servlet mapping of a request for {@code input} against the given root. */
	private Reply viaServlet(String root, String input) throws Exception {
		OpencodeChatServlet s = FileCall.servlet(() -> root, reader);
		return FileCall.get(s, input);
	}

	/** Asserts the reader status AND that the servlet answers the fixed body of that status, leaking nothing. */
	private void expect(Status expected, String root, String input) throws Exception {
		Result r = reader.read(root, input);
		assertEquals(expected, r.getStatus(), "reader status for '" + printable(input) + "'");
		if (expected == Status.OK)
			return;
		assertAll(() -> assertNull(r.getContent(), "content must be null on non-OK"),
				() -> assertNull(r.getPath(), "path must be null on non-OK"));
		Reply reply = viaServlet(root, input);
		String body = reply.body();
		assertAll("servlet mapping for '" + printable(input) + "'",
				() -> assertEquals(httpFor(expected), reply.status),
				() -> assertEquals(jsonFor(expected), body),
				() -> assertEquals("application/json;charset=UTF-8", reply.contentType),
				() -> assertEquals("no-store", reply.headers.get("Cache-Control")),
				() -> assertEquals("nosniff", reply.headers.get("X-Content-Type-Options")),
				() -> assertNoRootLeak(body, root));
	}

	private void expect(Status expected, String input) throws Exception {
		expect(expected, ws.toString(), input);
	}

	private static void assertNull(Object value, String message) {
		org.junit.jupiter.api.Assertions.assertNull(value, message);
	}

	private static void assertNoRootLeak(String body, String root) {
		String lower = body.toLowerCase(Locale.ROOT);
		String fwd = root.replace('\\', '/').toLowerCase(Locale.ROOT);
		String back = root.replace('/', '\\').toLowerCase(Locale.ROOT);
		String escaped = back.replace("\\", "\\\\");
		assertFalse(lower.contains(fwd) || lower.contains(back) || lower.contains(escaped),
				"response must never contain the root: " + body);
	}

	private static String printable(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(s.length(), 60); i++) {
			char c = s.charAt(i);
			sb.append(c < 0x20 ? "\\u" + String.format("%04x", (int) c) : String.valueOf(c));
		}
		return sb.toString();
	}

	// ----- happy path -------------------------------------------------------

	@Test
	@DisplayName("OK result: relative '/' path without root, name, exact size, modified and content")
	void happyPathResult() {
		Result r = read("handoff/t/spec.md");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()),
				() -> assertEquals("handoff/t/spec.md", r.getPath()),
				() -> assertEquals("spec.md", r.getName()),
				() -> assertEquals("# spec\n".length(), r.getSize()),
				() -> assertEquals("# spec\n", r.getContent()),
				() -> assertTrue(r.getModified() > 0),
				() -> assertFalse(r.getPath().contains(tmp.toString())));
	}

	@Test
	@DisplayName("backslash separators are accepted on every OS and give the same '/' path")
	void backslashSeparators() {
		Result r = read("handoff\\t\\spec.md");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals("handoff/t/spec.md", r.getPath()));
	}

	@Test
	@DisplayName("a leading './' is FORBIDDEN because the '.' segment is rejected")
	void leadingDotSegmentForbidden() throws Exception {
		expect(Status.FORBIDDEN, "./handoff/t/spec.md");
	}

	@Test
	@DisplayName("upper-case path: OK on a case-insensitive file system, NOT_FOUND elsewhere, never an escape")
	void upperCasePath() throws Exception {
		Result r = read("HANDOFF/T/SPEC.MD");
		if (CASE_INSENSITIVE) {
			assertAll(() -> assertEquals(Status.OK, r.getStatus()),
					() -> assertEquals("handoff/t/spec.md", r.getPath(), "the real-path spelling is reported"));
		} else {
			expect(Status.NOT_FOUND, "HANDOFF/T/SPEC.MD");
		}
	}

	@Test
	@DisplayName("empty separators are dropped: 'handoff//t///spec.md' is OK")
	void emptySegmentsDropped() {
		assertEquals(Status.OK, read("handoff//t///spec.md").getStatus());
	}

	// ----- absolute ---------------------------------------------------------

	@Test
	@DisplayName("absolute path inside the root is converted to a relative one")
	void absoluteInside() {
		Result r = read(ws.resolve("handoff/t/spec.md").toString());
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals("handoff/t/spec.md", r.getPath()));
	}

	@Test
	@DisplayName("absolute path inside the root via its real-path spelling is OK too")
	void absoluteInsideRealSpelling() throws Exception {
		Result r = read(ws.toRealPath().resolve("src/a.js").toString());
		assertEquals(Status.OK, r.getStatus());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/etc/passwd", "C:\\Windows\\win.ini", "C:foo", "C:/Windows/win.ini", "/", "\\" })
	@DisplayName("absolute / drive paths outside the root are FORBIDDEN")
	void absoluteOutsideLiteral(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@Test
	@DisplayName("absolute path of a file next to the root is FORBIDDEN")
	void absoluteOutsideReal() throws Exception {
		expect(Status.FORBIDDEN, outside.resolve("secret.txt").toString());
	}

	@Test
	@DisplayName("sibling with the same name prefix ('ws-evil' next to 'ws') is FORBIDDEN, relative and absolute")
	void siblingPrefix() throws Exception {
		expect(Status.FORBIDDEN, "../ws-evil/secret.txt");
		expect(Status.FORBIDDEN, tmp.resolve("ws-evil/secret.txt").toString());
	}

	// ----- traversal / lexical ---------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "../outside/secret.txt", "..\\outside\\secret.txt", "a/../../outside/secret.txt",
			"src/../../outside/secret.txt", "..", ".", "src/./a.js", "src/..", "src/../src/a.js" })
	@DisplayName("traversal and dot segments are FORBIDDEN")
	void traversal(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "%2e%2e/outside/secret.txt", "%252e%252e/x", "..%2f..%2fsecret", "%2e/src/a.js",
			"src%2fa.js", "src%5ca.js", "%00" })
	@DisplayName("percent-encoded input is taken literally (never decoded again) and is NOT_FOUND")
	void encodedIsLiteral(String input) throws Exception {
		expect(Status.NOT_FOUND, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\\\\server\\share\\x", "//server/share/x", "\\\\?\\C:\\Windows\\win.ini",
			"\\\\.\\C:\\x", "\\\\?\\UNC\\s\\x", "\\/server/x", "/\\server\\x" })
	@DisplayName("UNC and device prefixes are FORBIDDEN")
	void uncPrefixes(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "src/a.js:stream", "handoff/t/spec.md::$DATA", "a:b", ":", "src/a.js:", "C:" })
	@DisplayName("colons (alternate data streams, drive-relative) are FORBIDDEN")
	void streams(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "src/a.js.", "src/a.js ", "src.\\a.js", "src /a.js", "src./a.js", "handoff/t/spec.md." })
	@DisplayName("segments with a trailing dot or space are FORBIDDEN")
	void trailingDotOrSpace(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "CON", "nul.txt", "src/COM1", "lpt9.md", "aux.", "CONIN$", "con.md.txt", "PRN", "conout$",
			"Com5.txt", "LPT0", "src/NUL", "com\u00B9", "lpt\u00B2.x", "con .txt", "nul..txt" })
	@DisplayName("Windows reserved device names are FORBIDDEN on every OS")
	void reservedNames(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "src/a.js\0.png", "src/a\njs", "\t", "src/a\rjs", "\0", "src/a\u007fjs", "src/a\u001bjs",
			"a<b", "a>b", "a\"b", "a|b", "a?b", "a*b", "src/*", "src/?.js" })
	@DisplayName("NUL, control characters and the characters < > \" | ? * are FORBIDDEN")
	void controlAndForbiddenChars(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@Test
	@DisplayName("blank and whitespace-only input is BAD_REQUEST; null too")
	void blank() throws Exception {
		assertAll(() -> assertEquals(Status.BAD_REQUEST, read("").getStatus()),
				() -> assertEquals(Status.BAD_REQUEST, read("   ").getStatus()),
				() -> assertEquals(Status.BAD_REQUEST, reader.read(ws.toString(), null).getStatus()));
		Reply emptyReply = viaServlet(ws.toString(), "");
		Reply blankReply = viaServlet(ws.toString(), "   ");
		assertAll(() -> assertEquals(400, emptyReply.status), () -> assertEquals(400, blankReply.status),
				() -> assertEquals(jsonFor(Status.BAD_REQUEST), emptyReply.body()),
				() -> assertEquals(jsonFor(Status.BAD_REQUEST), blankReply.body()));
	}

	@Test
	@DisplayName("a 2000 character name is FORBIDDEN; exactly 1024 characters pass the length rule (NOT_FOUND)")
	void longInput() throws Exception {
		expect(Status.FORBIDDEN, "a".repeat(2000));
		assertEquals(Status.FORBIDDEN, read("a".repeat(1025)).getStatus());
		// 1024 total chars, one segment: lexically fine, the file just does not exist (or the name is too long for the OS)
		Status s = read("a".repeat(1024)).getStatus();
		assertTrue(s == Status.NOT_FOUND || s == Status.FORBIDDEN || s == Status.ERROR, "unexpected: " + s);
		assertNotEquals(Status.OK, s);
	}

	@Test
	@DisplayName("8.3 aliases of .git are FORBIDDEN lexically (also when .git is absent); other short names are plain misses")
	void shortNames() throws Exception {
		expect(Status.FORBIDDEN, "GIT~1/config");
		expect(Status.FORBIDDEN, "git~2/config");
		expect(Status.FORBIDDEN, "src/Git~12");
		expect(Status.NOT_FOUND, "PROGRA~1/x");
	}

	@Test
	@DisplayName("lexical rules win over a missing root: a bad path on a missing root is FORBIDDEN, a good one NOT_FOUND")
	void lexicalBeforeDisk() throws Exception {
		String missingRoot = tmp.resolve("no-such-root").toString();
		expect(Status.FORBIDDEN, missingRoot, "../x");
		expect(Status.FORBIDDEN, missingRoot, ".git/config");
		expect(Status.NOT_FOUND, missingRoot, "src/a.js");
	}

	// ----- .git -------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { ".git/config", ".GIT/config", "src/.git/config", ".git", ".git/missing", ".Git",
			"src/.git", "handoff/.GIT/x" })
	@DisplayName(".git anywhere is FORBIDDEN whether it exists or not")
	void gitForbidden(String input) throws Exception {
		expect(Status.FORBIDDEN, input);
	}

	@Test
	@DisplayName("a .git FILE (worktree) is FORBIDDEN")
	void gitFileWorktree() throws Exception {
		write(ws.resolve("wt/.git"), "gitdir: /somewhere\n");
		expect(Status.FORBIDDEN, "wt/.git");
	}

	@Test
	@DisplayName(".git refusals are byte-identical for an existing and a missing target")
	void gitIdentical() throws Exception {
		Reply existing = viaServlet(ws.toString(), ".git/config");
		Reply missing = viaServlet(ws.toString(), ".git/nope");
		Reply otherMissing = viaServlet(ws.toString(), "src/.git/config");
		assertAll(() -> assertEquals(403, existing.status), () -> assertEquals(existing.body(), missing.body()),
				() -> assertEquals(existing.body(), otherMissing.body()),
				() -> assertEquals(existing.headers, missing.headers));
	}

	@Test
	@DisplayName("every FORBIDDEN cause maps to the identical 403 body")
	void allForbiddenCausesIdentical() throws Exception {
		Path lnk = link(ws.resolve("lnk"), outside);
		assertTrue(Files.exists(lnk));
		String[] inputs = { "../outside/x", "\\\\s\\x", "a:b", "CON", ".git/config", "src/a.js.", "lnk/secret.txt",
				"lnk/missing.txt", "\t", outside.resolve("secret.txt").toString() };
		String reference = viaServlet(ws.toString(), inputs[0]).body();
		for (String input : inputs) {
			Reply r = viaServlet(ws.toString(), input);
			assertAll("input '" + printable(input) + "'", () -> assertEquals(403, r.status),
					() -> assertEquals(reference, r.body()));
		}
	}

	// ----- missing / not regular -------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "handoff/t/gone.md", "nodir/x.md", "nodir/deep/er/x.md", "src/a.js/child" })
	@DisplayName("missing files inside the root are NOT_FOUND")
	void missing(String input) throws Exception {
		expect(Status.NOT_FOUND, input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "src", "handoff/t", "handoff", "src/" })
	@DisplayName("directories are NOT_FOUND")
	void directories(String input) throws Exception {
		expect(Status.NOT_FOUND, input);
	}

	// ----- links ------------------------------------------------------------

	@Test
	@DisplayName("link out of the root: lnk/secret.txt is FORBIDDEN, also when the target file does not exist (no oracle)")
	void linkOut() throws Exception {
		link(ws.resolve("lnk"), outside);
		expect(Status.FORBIDDEN, "lnk/secret.txt");
		expect(Status.FORBIDDEN, "lnk/missing.txt");
		expect(Status.FORBIDDEN, "lnk/nodir/deeper/missing.txt");
		Reply existing = viaServlet(ws.toString(), "lnk/secret.txt");
		Reply missing = viaServlet(ws.toString(), "lnk/missing.txt");
		assertEquals(existing.body(), missing.body());
	}

	@Test
	@DisplayName("a link to a file/directory outside the root never lets the outside content through")
	void linkToOutsideFile() throws Exception {
		if (WINDOWS) {
			// a junction can only target a directory: the file is reached through it
			link(ws.resolve("lnkfile"), outside);
			expect(Status.FORBIDDEN, "lnkfile/secret.txt");
		} else {
			link(ws.resolve("lnkfile"), outside.resolve("secret.txt"));
			expect(Status.FORBIDDEN, "lnkfile");
		}
		assertNotEquals(Status.OK, read("lnkfile/secret.txt").getStatus());
	}

	@Test
	@DisplayName("a dangling link (target removed) is FORBIDDEN, identical to a link that points outside")
	void danglingLink() throws Exception {
		Path target = Files.createDirectories(tmp.resolve("soon-gone"));
		link(ws.resolve("dangling"), target);
		link(ws.resolve("lnk"), outside);
		Files.delete(target);
		expect(Status.FORBIDDEN, "dangling");
		expect(Status.FORBIDDEN, "dangling/x.md");
		expect(Status.FORBIDDEN, "dangling/missing.txt");
		Reply dangling = viaServlet(ws.toString(), "dangling/missing.txt");
		Reply outLink = viaServlet(ws.toString(), "lnk/missing.txt");
		assertAll(() -> assertEquals(403, dangling.status), () -> assertEquals(outLink.body(), dangling.body()));
	}

	@Test
	@DisplayName("link to a directory inside the root is followed: alias/a.js is OK and reports the real path")
	void linkIn() throws Exception {
		link(ws.resolve("alias"), ws.resolve("src"));
		Result r = read("alias/a.js");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals("src/a.js", r.getPath()),
				() -> assertEquals("var a = 1;\n", r.getContent()));
	}

	@Test
	@DisplayName("link to the .git directory: the denylist applies to the REAL path")
	void linkToGit() throws Exception {
		link(ws.resolve("gitlink"), ws.resolve(".git"));
		expect(Status.FORBIDDEN, "gitlink/config");
		expect(Status.FORBIDDEN, "gitlink/missing");
		expect(Status.FORBIDDEN, "gitlink");
	}

	@Test
	@DisplayName("link to the ~/.servoy directory from inside the root is FORBIDDEN")
	void linkToServoyDir() throws Exception {
		link(ws.resolve("svlink"), servoyDir);
		expect(Status.FORBIDDEN, "svlink/opencode/opencode.json");
		expect(Status.FORBIDDEN, "svlink/opencode/missing.json");
	}

	// ----- ~/.servoy --------------------------------------------------------

	@Test
	@DisplayName("root = ancestor of ~/.servoy (the fake home): everything below .servoy is FORBIDDEN, also missing files")
	void servoyBelowRoot() throws Exception {
		String root = home.toString();
		expect(Status.FORBIDDEN, root, ".servoy/opencode/opencode.json");
		expect(Status.FORBIDDEN, root, ".servoy/opencode/missing.json");
		expect(Status.FORBIDDEN, root, ".servoy\\opencode\\opencode.json");
		expect(Status.FORBIDDEN, root, ".servoy");
		expect(Status.FORBIDDEN, root, servoyDir.resolve("opencode/opencode.json").toString());
	}

	@Test
	@DisplayName("upper-case '.SERVOY': FORBIDDEN on a case-insensitive file system, a plain miss elsewhere, never served")
	void servoyUpperCase() throws Exception {
		String root = home.toString();
		Result r = reader.read(root, ".SERVOY\\opencode\\opencode.json");
		assertNotEquals(Status.OK, r.getStatus());
		assertEquals(CASE_INSENSITIVE ? Status.FORBIDDEN : Status.NOT_FOUND, r.getStatus());
	}

	@Test
	@DisplayName("a file in the home next to .servoy is still served")
	void homeSiblingServed() throws Exception {
		write(home.resolve("notes.md"), "hi");
		assertEquals(Status.OK, reader.read(home.toString(), "notes.md").getStatus());
	}

	@Test
	@DisplayName("root = ~/.servoy/opencode itself: every path is FORBIDDEN")
	void rootInsideServoy() throws Exception {
		String root = servoyDir.resolve("opencode").toString();
		expect(Status.FORBIDDEN, root, "opencode.json");
		expect(Status.FORBIDDEN, root, "missing.json");
	}

	@Test
	@DisplayName("root = ~/.servoy reached through a link is FORBIDDEN too")
	void rootThroughServoyLink() throws Exception {
		Path lnk = link(tmp.resolve("svroot"), servoyDir);
		expect(Status.FORBIDDEN, lnk.toString(), "opencode/opencode.json");
	}

	// ----- size / content ---------------------------------------------------

	@Test
	@DisplayName("a file of exactly 2 MiB is OK, 2 MiB + 1 byte is TOO_LARGE")
	void sizeBoundary() throws Exception {
		byte[] exact = new byte[MIB2];
		java.util.Arrays.fill(exact, (byte) 'a');
		write(ws.resolve("big/exact.txt"), exact);
		byte[] over = new byte[MIB2 + 1];
		java.util.Arrays.fill(over, (byte) 'a');
		write(ws.resolve("big/over.txt"), over);
		Result ok = read("big/exact.txt");
		assertAll(() -> assertEquals(Status.OK, ok.getStatus()), () -> assertEquals(MIB2, ok.getSize()),
				() -> assertEquals(MIB2, ok.getContent().length()));
		expect(Status.TOO_LARGE, "big/over.txt");
	}

	@Test
	@DisplayName("the size limit is the configured one: a small reader refuses a 11 byte file at limit 10")
	void configuredLimit() throws Exception {
		write(ws.resolve("eleven.txt"), "12345678901");
		write(ws.resolve("ten.txt"), "1234567890");
		WorkspaceFileReader small = new WorkspaceFileReader(servoyDir, 10);
		assertAll(() -> assertEquals(Status.TOO_LARGE, small.read(ws.toString(), "eleven.txt").getStatus()),
				() -> assertEquals(Status.OK, small.read(ws.toString(), "ten.txt").getStatus()));
	}

	@Test
	@DisplayName("PNG bytes are BINARY")
	void pngIsBinary() throws Exception {
		write(ws.resolve("img.png"), new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D,
				'I', 'H', 'D', 'R' });
		expect(Status.BINARY, "img.png");
	}

	@Test
	@DisplayName("a NUL byte at position 100 of an otherwise valid text file is BINARY")
	void nulAt100() throws Exception {
		byte[] data = "x".repeat(200).getBytes(StandardCharsets.UTF_8);
		data[100] = 0;
		write(ws.resolve("withnul.txt"), data);
		expect(Status.BINARY, "withnul.txt");
	}

	@Test
	@DisplayName("invalid UTF-8 (0xC3 0x28) is BINARY, also when it sits behind the 8192 byte NUL sniff window")
	void invalidUtf8() throws Exception {
		write(ws.resolve("bad.txt"), new byte[] { (byte) 0xC3, 0x28 });
		expect(Status.BINARY, "bad.txt");
		byte[] late = new byte[9000];
		java.util.Arrays.fill(late, (byte) 'a');
		late[8998] = (byte) 0xC3;
		late[8999] = 0x28;
		write(ws.resolve("late.txt"), late);
		expect(Status.BINARY, "late.txt");
	}

	@Test
	@DisplayName("valid multi-byte UTF-8 is OK and decoded; size is the byte count")
	void multiByte() throws Exception {
		String text = "h\u00e9llo \u20ac \ud83d\ude00";
		write(ws.resolve("utf8.txt"), text);
		Result r = read("utf8.txt");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals(text, r.getContent()),
				() -> assertEquals(text.getBytes(StandardCharsets.UTF_8).length, r.getSize()));
	}

	@Test
	@DisplayName("a UTF-8 BOM is dropped from the content (but counted in the size)")
	void bom() throws Exception {
		write(ws.resolve("bom.txt"), new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i' });
		Result r = read("bom.txt");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals("hi", r.getContent()),
				() -> assertEquals(5, r.getSize()));
	}

	@Test
	@DisplayName("CRLF line endings are kept untouched")
	void crlf() throws Exception {
		write(ws.resolve("crlf.txt"), "a\r\nb\r\n");
		assertEquals("a\r\nb\r\n", read("crlf.txt").getContent());
	}

	@Test
	@DisplayName("an empty file is OK with empty content and size 0")
	void emptyFile() throws Exception {
		write(ws.resolve("empty.txt"), new byte[0]);
		Result r = read("empty.txt");
		assertAll(() -> assertEquals(Status.OK, r.getStatus()), () -> assertEquals("", r.getContent()),
				() -> assertEquals(0, r.getSize()));
	}

	// ----- root edge --------------------------------------------------------

	@Test
	@DisplayName("a missing root and a root that is a file are NOT_FOUND, an empty/null root too")
	void rootMissingOrFile() throws Exception {
		expect(Status.NOT_FOUND, tmp.resolve("nope").toString(), "src/a.js");
		expect(Status.NOT_FOUND, ws.resolve("src/a.js").toString(), "a.js");
		assertAll(() -> assertEquals(Status.NOT_FOUND, reader.read(null, "src/a.js").getStatus()),
				() -> assertEquals(Status.NOT_FOUND, reader.read("", "src/a.js").getStatus()));
	}

	@Test
	@DisplayName("root with a trailing separator and with '..' segments is canonicalised")
	void rootCanonicalised() {
		String sep = ws.getFileSystem().getSeparator();
		assertAll(() -> assertEquals(Status.OK, reader.read(ws + sep, "src/a.js").getStatus()),
				() -> assertEquals(Status.OK, reader.read(ws.resolve("src").resolve("..").toString(), "src/a.js")
						.getStatus()),
				() -> assertEquals(Status.OK, reader.read(tmp.resolve("ws-evil").resolve("..").resolve("ws").toString(),
						"handoff/t/spec.md").getStatus()));
	}

	@Test
	@DisplayName("root given in a different case: OK on a case-insensitive file system, NOT_FOUND elsewhere")
	void rootDifferentCase() {
		String upper = ws.toString().toUpperCase(Locale.ROOT);
		Status s = reader.read(upper, "src/a.js").getStatus();
		if (CASE_INSENSITIVE && Files.exists(Path.of(upper)))
			assertEquals(Status.OK, s);
		else
			assertEquals(Status.NOT_FOUND, s);
	}

	@Test
	@DisplayName("an absolute input below a root given with '..' segments is still confined to the root")
	void absoluteWithDotRoot() throws Exception {
		String root = ws.resolve("src").resolve("..").toString();
		Result r = reader.read(root, ws.resolve("handoff/t/spec.md").toString());
		assertEquals(Status.OK, r.getStatus());
		expect(Status.FORBIDDEN, root, outside.resolve("secret.txt").toString());
	}

	@Test
	@DisplayName("the reader never throws for hostile input")
	void neverThrows() {
		String[] hostile = { "\0", "\ud800", "a\udc00b", "\\\\?\\", "////", "....", "..\\..\\..", " ", "\u202e", "~",
				"~/.servoy", "$HOME", "file:///etc/passwd", "src/a.js\u0000" };
		for (String input : hostile) {
			Result r = reader.read(ws.toString(), input);
			assertNotEquals(Status.OK, r.getStatus(), "input '" + printable(input) + "'");
		}
	}
}

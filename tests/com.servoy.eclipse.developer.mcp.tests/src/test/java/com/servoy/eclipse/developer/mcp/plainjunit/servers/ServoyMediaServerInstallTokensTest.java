/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.inet.lib.less.Less;
import com.inet.lib.less.ReaderFactory;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServer;

/**
 * Headless (plain JUnit, no OSGi) tests for the {@code media_install_tokens} tool on {@link ServoyMediaServer}
 * (SVY-21615): the tool/param contract and the pure stylesheet-editing helpers. The model-dependent
 * install/update/module behaviour is covered by the integration test.
 */
class ServoyMediaServerInstallTokensTest {

	private static final String TOOL_NAME = "media_install_tokens";
	private static final String TOKENS_CSS = ":root { --brand-primary: #0066cc; }";

	private final ServoyMediaServer server = new ServoyMediaServer();

	// ---------------------------------------------------------------------
	// Tool & @ToolParam contract
	// ---------------------------------------------------------------------

	@Test
	void toolExistsAndReturnsString() {
		Method method = findInstallTokensMethod();
		assertNotNull(method, "ServoyMediaServer must have a tool named '" + TOOL_NAME + "'");
		assertEquals(String.class, method.getReturnType());
		assertEquals(3, method.getParameterCount());
	}

	@Test
	void allParamsOptional_sourcePathPreferred_mediaFileNameDefaultDocumented() {
		ToolParam content = findParam("tokensContent");
		ToolParam name = findParam("mediaFileName");
		ToolParam source = findParam("sourcePath");
		assertAll(() -> assertNotNull(content, "tokensContent param"),
				() -> assertFalse(content.required(), "tokensContent is optional (sourcePath alternative)"),
				() -> assertNotNull(source, "sourcePath param"),
				() -> assertFalse(source.required(), "sourcePath is optional (tokensContent alternative)"),
				() -> assertTrue(source.description().contains("byte for byte"), source.description()),
				() -> assertNotNull(name, "mediaFileName param"),
				() -> assertFalse(name.required(), "mediaFileName must be optional"),
				() -> assertTrue(name.description().contains("tokens.css"), "default must be documented"));
	}

	@Test
	void toolDescriptionStatesInlineImportAndIdempotency() {
		String desc = findInstallTokensMethod().getAnnotation(Tool.class).description().toLowerCase(Locale.ROOT);
		assertAll(() -> assertTrue(desc.contains("custom-properties"), desc),
				() -> assertTrue(desc.contains("@import (inline)"), desc),
				() -> assertTrue(desc.contains("exactly one"), desc),
				() -> assertTrue(desc.contains("module"), desc));
	}

	// ---------------------------------------------------------------------
	// applyImport - adding
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@NullAndEmptySource
	void emptyStylesheet_getsSingleInlineImport(String stylesheet) throws Exception {
		Object edit = applyImport(stylesheet, "app.less", "tokens.css");
		assertEquals("@import (inline) \"tokens.css\";\n", text(edit));
		assertEquals("added", outcome(edit));
	}

	@Test
	void noImports_insertedBeforeFirstRule() throws Exception {
		Object edit = applyImport("body { color: red; }\n", "app.less", "tokens.css");
		assertEquals("@import (inline) \"tokens.css\";\nbody { color: red; }\n", text(edit));
		assertEquals("added", outcome(edit));
	}

	@Test
	void insertedAfterLeadingImportBlock_restVerbatim() throws Exception {
		String css = "@import 'servoy_theme.less';\n// theme overrides\n@import 'solution_properties.less';\n"
				+ "\n:root {\n  --brand: #abc;\n}\n";
		Object edit = applyImport(css, "app.less", "tokens.css");
		assertEquals("@import 'servoy_theme.less';\n// theme overrides\n@import 'solution_properties.less';\n"
				+ "@import (inline) \"tokens.css\";\n\n:root {\n  --brand: #abc;\n}\n", text(edit));
	}

	@Test
	void insertedAfterCharset() throws Exception {
		Object edit = applyImport("@charset \"utf-8\";\nbody{}\n", "app.less", "tokens.css");
		assertEquals("@charset \"utf-8\";\n@import (inline) \"tokens.css\";\nbody{}\n", text(edit));
	}

	@Test
	void importAfterFirstRule_doesNotMoveInsertionPoint() throws Exception {
		String css = "@import 'a.less';\nbody{}\n@import 'late.less';\n";
		Object edit = applyImport(css, "app.less", "tokens.css");
		assertEquals("@import 'a.less';\n@import (inline) \"tokens.css\";\nbody{}\n@import 'late.less';\n", text(edit));
	}

	@Test
	void crlfDelimiterPreserved() throws Exception {
		Object edit = applyImport("@import 'a.less';\r\nbody{}\r\n", "app.less", "tokens.css");
		assertEquals("@import 'a.less';\r\n@import (inline) \"tokens.css\";\r\nbody{}\r\n", text(edit));
	}

	// ---------------------------------------------------------------------
	// applyImport - already present / upgrade
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "@import (inline) \"tokens.css\";", "@import (inline) 'tokens.css';",
			"  @import (less) \"tokens.css\";", "@import (inline, optional) \"./tokens.css\";" })
	void compiledInImport_isAlreadyPresent_textUnchanged(String importLine) throws Exception {
		String css = importLine + "\nbody{}\n";
		Object edit = applyImport(css, "app.less", "tokens.css");
		assertEquals("alreadyPresent", outcome(edit));
		assertEquals(css, text(edit));
	}

	@ParameterizedTest
	@ValueSource(strings = { "@import \"tokens.css\";", "@import 'tokens.css';", "@import (css) \"tokens.css\";" })
	void plainCssImport_isUpgradedInPlace(String importLine) throws Exception {
		Object edit = applyImport("@import 'a.less';\n" + importLine + "\nbody{}\n", "app.less", "tokens.css");
		assertEquals("upgraded", outcome(edit));
		assertEquals("@import 'a.less';\n@import (inline) \"tokens.css\";\nbody{}\n", text(edit));
	}

	@Test
	void twoPlainImports_collapseToOneInline() throws Exception {
		Object edit = applyImport("@import \"tokens.css\";\nbody{}\n@import 'tokens.css';\n", "app.less", "tokens.css");
		assertEquals("upgraded", outcome(edit));
		assertEquals("@import (inline) \"tokens.css\";\nbody{}\n", text(edit));
	}

	@Test
	void inlineAndPlainImport_plainIsRemoved() throws Exception {
		Object edit = applyImport("@import (inline) \"tokens.css\";\n@import \"tokens.css\";\nbody{}\n", "app.less",
				"tokens.css");
		assertEquals("upgraded", outcome(edit));
		assertEquals("@import (inline) \"tokens.css\";\nbody{}\n", text(edit));
	}

	@ParameterizedTest
	@ValueSource(strings = { "@import (reference) \"tokens.css\";", "@import (less, reference) \"tokens.css\";",
			"@import (inline, reference) \"tokens.css\";" })
	void referenceImport_isUpgradedBecauseItEmitsNothing(String importLine) throws Exception {
		Object edit = applyImport(importLine + "\nbody{}\n", "app.less", "tokens.css");
		assertEquals("upgraded", outcome(edit));
		assertEquals("@import (inline) \"tokens.css\";\nbody{}\n", text(edit));
	}

	@Test
	void rerunAfterAdd_isAlreadyPresent() throws Exception {
		Object first = applyImport("body{}\n", "app.less", "tokens.css");
		Object second = applyImport(text(first), "app.less", "tokens.css");
		assertEquals("alreadyPresent", outcome(second));
		assertEquals(text(first), text(second));
	}

	// ---------------------------------------------------------------------
	// applyImport - comments and path matching
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "// @import (inline) \"tokens.css\";\nbody{}\n",
			"/* @import (inline) \"tokens.css\"; */\nbody{}\n",
			"/*\n@import (inline) \"tokens.css\";\n*/\nbody{}\n" })
	void commentedOutImport_isIgnored(String css) throws Exception {
		Object edit = applyImport(css, "app.less", "tokens.css");
		assertEquals("added", outcome(edit));
		assertEquals("@import (inline) \"tokens.css\";\n" + css, text(edit));
	}

	@Test
	void importAfterMultiLineComment_isPlacedAfterIt() throws Exception {
		String css = "/*\n @import 'old.less';\n*/\n@import 'a.less';\nbody{}\n";
		Object edit = applyImport(css, "app.less", "tokens.css");
		assertEquals("/*\n @import 'old.less';\n*/\n@import 'a.less';\n@import (inline) \"tokens.css\";\nbody{}\n",
				text(edit));
	}

	@Test
	void sameFileNameInOtherFolder_isNotTreatedAsPresent() throws Exception {
		Object edit = applyImport("@import (inline) \"other/tokens.css\";\nbody{}\n", "app.less", "css/tokens.css");
		assertEquals("added", outcome(edit));
	}

	@Test
	void stylesheetInSubfolder_refIsRelativeToIt() throws Exception {
		Object edit = applyImport("body{}\n", "styles/app.less", "tokens.css");
		assertEquals("@import (inline) \"../tokens.css\";\nbody{}\n", text(edit));
	}

	@Test
	void relativeImportResolvingToSameMedia_isAlreadyPresent() throws Exception {
		Object edit = applyImport("@import (inline) \"../css/tokens.css\";\n", "styles/app.less", "css/tokens.css");
		assertEquals("alreadyPresent", outcome(edit));
	}

	@ParameterizedTest
	@CsvSource({ "'', tokens.css, tokens.css", "'', css/tokens.css, css/tokens.css",
			"styles/, tokens.css, ../tokens.css", "styles/, styles/tokens.css, tokens.css",
			"a/b/, a/c/tokens.css, ../c/tokens.css" })
	void relativeImportRef(String folder, String media, String expected) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("relativeImportRef", String.class, String.class);
		m.setAccessible(true);
		assertEquals(expected, m.invoke(server, folder, media));
	}

	// ---------------------------------------------------------------------
	// The generated import actually compiles the tokens into the CSS
	// ---------------------------------------------------------------------

	@Test
	void generatedImport_inlinesTokensWhenCompiledWithLess() throws Exception {
		String css = text(applyImport("body { color: red; }\n", "styles/app.less", "tokens.css"));
		String compiled = compileLikeServoy(css, "styles/app.less", Map.of("tokens.css", TOKENS_CSS));
		assertAll(() -> assertTrue(compiled.contains("--brand-primary: #0066cc"), compiled),
				() -> assertFalse(compiled.contains("@import"), compiled));
	}

	@Test
	void plainCssImport_isNotCompiledIn_whichIsWhyItIsUpgraded() throws Exception {
		String compiled = compileLikeServoy("@import \"tokens.css\";\nbody{}\n", "app.less",
				Map.of("tokens.css", TOKENS_CSS));
		assertFalse(compiled.contains("--brand-primary"), compiled);
	}

	// ---------------------------------------------------------------------
	// validateMediaName is reused for mediaFileName
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "tokens.css", "css/tokens.css", "my_tokens-v2.css" })
	void validateMediaName_acceptsTokenFileNames(String name) throws Exception {
		assertNull(validateMediaName(name), name);
	}

	@ParameterizedTest
	@ValueSource(strings = { "my tokens.css", "tokens@bad.css", "../tokens.css" })
	void validateMediaName_rejectsBadNames(String name) throws Exception {
		String result = validateMediaName(name);
		assertNotNull(result, name);
		assertTrue(result.startsWith("Error:"), result);
	}

	// ---------------------------------------------------------------------
	// resolveTokensBytes - sourcePath vs tokensContent
	// ---------------------------------------------------------------------

	@Test
	void sourcePath_absolute_isCopiedByteForByte(@TempDir Path dir) throws Exception {
		byte[] bytes = "/* gen */\r\n:root {\r\n  --a: 1px;\r\n  --\u00e9: 2px;\r\n}\r\n".getBytes(StandardCharsets.UTF_8);
		Path file = Files.write(dir.resolve("tokens.css"), bytes);
		assertArrayEquals(bytes, resolveTokensBytes(null, file.toString(), null));
	}

	@Test
	void sourcePath_relative_resolvesAgainstBaseDir(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve("design"));
		Files.write(dir.resolve("design/tokens.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		assertEquals(TOKENS_CSS, new String(resolveTokensBytes(" ", "design/tokens.css", dir), StandardCharsets.UTF_8));
	}

	@Test
	void tokensContent_isEncodedAsUtf8() throws Exception {
		assertArrayEquals(TOKENS_CSS.getBytes(StandardCharsets.UTF_8), resolveTokensBytes(TOKENS_CSS, null, null));
	}

	@Test
	void bothSources_isRejected(@TempDir Path dir) throws Exception {
		Path file = Files.write(dir.resolve("tokens.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		assertEquals("pass either sourcePath or tokensContent, not both.",
				resolveTokensBytesError(TOKENS_CSS, file.toString(), null));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void noSource_isRejected(String blank) throws Exception {
		assertEquals("sourcePath or tokensContent is required.", resolveTokensBytesError(blank, blank, null));
	}

	@Test
	void sourcePath_missingFile_isRejected(@TempDir Path dir) throws Exception {
		assertTrue(resolveTokensBytesError(null, "nope.css", dir).startsWith("sourcePath file not found"));
	}

	@Test
	void sourcePath_nonCss_isRejected(@TempDir Path dir) throws Exception {
		Path file = Files.write(dir.resolve("design-tokens.json"), "{}".getBytes(StandardCharsets.UTF_8));
		assertTrue(resolveTokensBytesError(null, file.toString(), null).startsWith("sourcePath must point to a .css file"));
	}

	@Test
	void sourcePath_emptyFile_isRejected(@TempDir Path dir) throws Exception {
		Path file = Files.write(dir.resolve("tokens.css"), " \n".getBytes(StandardCharsets.UTF_8));
		assertTrue(resolveTokensBytesError(null, file.toString(), null).startsWith("sourcePath file is empty"));
	}

	@Test
	void sourcePath_tooLarge_isRejected(@TempDir Path dir) throws Exception {
		Path file = Files.write(dir.resolve("tokens.css"), new byte[1024 * 1024 + 1]);
		assertTrue(resolveTokensBytesError(null, file.toString(), null).startsWith("sourcePath file is larger than 1 MB"));
	}

	@Test
	void sourcePath_root_isRejectedAsNotCss(@TempDir Path dir) throws Exception {
		assertTrue(resolveTokensBytesError(null, dir.getRoot().toString(), null)
				.startsWith("sourcePath must point to a .css file"));
	}

	@Test
	void sourcePath_outsideAllowedRoots_isRejected(@TempDir Path dir) throws Exception {
		Path allowed = Files.createDirectories(dir.resolve("workspace"));
		Path outside = Files.write(dir.resolve("tokens.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> resolveTokensBytes(null, outside.toString(), allowed, List.of(allowed)));
		assertTrue(e.getMessage().startsWith("sourcePath must be inside the workspace"), e.getMessage());
	}

	@Test
	void sourcePath_dotDotOutOfAllowedRoot_isRejected(@TempDir Path dir) throws Exception {
		Path allowed = Files.createDirectories(dir.resolve("workspace"));
		Files.write(dir.resolve("tokens.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> resolveTokensBytes(null, "../tokens.css", allowed, List.of(allowed)));
		assertTrue(e.getMessage().startsWith("sourcePath must be inside the workspace"), e.getMessage());
	}

	@Test
	void sourcePath_symlinkLeavingAllowedRoot_isRejected(@TempDir Path dir) throws Exception {
		Path allowed = Files.createDirectories(dir.resolve("workspace"));
		// the link targets a sibling of the allowed root, never a folder that contains the link itself, so
		// @TempDir cleanup cannot recurse through it
		Path outside = Files.createDirectories(dir.resolve("outside"));
		Path target = Files.write(outside.resolve("secret.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		Path linkedDir = allowed.resolve("linked");
		linkDirectory(linkedDir, outside);
		try {
			Path link = linkedDir.resolve(target.getFileName());
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> resolveTokensBytes(null, link.toString(), allowed, List.of(allowed)));
			assertTrue(e.getMessage().startsWith("sourcePath must be inside the workspace"), e.getMessage());
		} finally {
			// delete the link itself, not what it points at
			Files.deleteIfExists(linkedDir);
		}
	}

	@Test
	void sourcePath_relative_resolvesAgainstWorkspaceRoot(@TempDir Path dir) throws Exception {
		Path workspace = Files.createDirectories(dir.resolve("workspace"));
		Files.createDirectories(workspace.resolve("mysolution/design"));
		Files.write(workspace.resolve("mysolution/design/tokens.css"), TOKENS_CSS.getBytes(StandardCharsets.UTF_8));
		byte[] bytes = resolveTokensBytes(null, "mysolution/design/tokens.css", workspace, List.of(workspace));
		assertEquals(TOKENS_CSS, new String(bytes, StandardCharsets.UTF_8));
	}

	@Test
	void sourcePath_relativeWithoutBaseDir_isRejected() throws Exception {
		assertTrue(resolveTokensBytesError(null, "tokens.css", null).startsWith("relative sourcePath"));
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private byte[] resolveTokensBytes(String content, String sourcePath, Path baseDir) throws Exception {
		List<Path> roots = new ArrayList<>();
		if (baseDir != null)
			roots.add(baseDir);
		roots.add(Path.of(System.getProperty("java.io.tmpdir")));
		return resolveTokensBytes(content, sourcePath, baseDir, roots);
	}

	private byte[] resolveTokensBytes(String content, String sourcePath, Path baseDir, List<Path> roots)
			throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("resolveTokensBytes", String.class, String.class,
				Path.class, List.class);
		m.setAccessible(true);
		try {
			return (byte[]) m.invoke(server, content, sourcePath, baseDir, roots);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception cause)
				throw cause;
			throw e;
		}
	}

	/**
	 * Links a directory to another one. A symbolic link needs a privilege on Windows that a normal account lacks, so
	 * there a directory junction is used: it needs no privilege and escapes the folder the same way.
	 */
	private static void linkDirectory(Path link, Path target) throws Exception {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
					.redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(0, p.waitFor(), "mklink /J failed: " + out);
		} else {
			Files.createSymbolicLink(link, target);
		}
	}

	private String resolveTokensBytesError(String content, String sourcePath, Path baseDir) throws Exception {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> resolveTokensBytes(content, sourcePath, baseDir));
		return e.getMessage();
	}

	/**
	 * Compiles the way {@code JLessCompiler} does: imports resolve against the stylesheet's folder by media name.
	 */
	private static String compileLikeServoy(String less, String stylesheetName, Map<String, String> medias)
			throws Exception {
		int slash = stylesheetName.lastIndexOf('/') + 1;
		String folder = slash > 0 ? stylesheetName.substring(0, slash) : "";
		ReaderFactory reader = new ReaderFactory() {
			@Override
			public Reader create(URL url) {
				String path = url.getFile().startsWith("/") ? url.getFile().substring(1) : url.getFile();
				String resolved = java.net.URI.create(folder + path).normalize().toString();
				return new StringReader(medias.getOrDefault(resolved, ""));
			}
		};
		return Less.compile(new URL("http://localhost"), less, Collections.singletonMap(Less.REWRITE_URLS, "all"),
				reader);
	}

	private Object applyImport(String stylesheet, String stylesheetName, String media) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("applyImport", String.class, String.class, String.class);
		m.setAccessible(true);
		return m.invoke(server, stylesheet, stylesheetName, media);
	}

	private static String text(Object edit) throws Exception {
		return (String) recordComponent(edit, "text");
	}

	private static String outcome(Object edit) throws Exception {
		return (String) recordComponent(edit, "outcome");
	}

	private static Object recordComponent(Object record, String name) throws Exception {
		Method accessor = record.getClass().getDeclaredMethod(name);
		accessor.setAccessible(true);
		return accessor.invoke(record);
	}

	private String validateMediaName(String name) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("validateMediaName", String.class);
		m.setAccessible(true);
		return (String) m.invoke(server, name);
	}

	private static Method findInstallTokensMethod() {
		for (Method m : ServoyMediaServer.class.getDeclaredMethods()) {
			Tool tool = m.getAnnotation(Tool.class);
			if (tool != null && TOOL_NAME.equals(tool.name()))
				return m;
		}
		return null;
	}

	private static ToolParam findParam(String paramName) {
		Method method = findInstallTokensMethod();
		if (method == null)
			return null;
		for (Parameter p : method.getParameters()) {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			if (tp != null && paramName.equals(tp.name()))
				return tp;
		}
		return null;
	}
}

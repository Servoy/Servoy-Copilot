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

package com.servoy.eclipse.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the package-private git-ignore helpers of
 * {@link OpencodeIndexIgnorer}.
 * <p>
 * The Eclipse resource-filter side needs a running workspace and is covered by
 * the integration path; these tests exercise only the pure
 * {@code .gitignore} logic, which has no OSGi dependency.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpencodeIndexIgnorerTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	// -----------------------------------------------------------------------
	// ensureGitignoreContent (string level)
	// -----------------------------------------------------------------------

	@Test
	public void ensureGitignoreContent_nullInput_createsFileWithPattern() {
		String result = OpencodeIndexIgnorer.ensureGitignoreContent(null);
		assertEquals(OpencodeIndexIgnorer.GITIGNORE_PATTERN + "\n", result);
	}

	@Test
	public void ensureGitignoreContent_patternAlreadyPresent_returnsNull() {
		String existing = "node_modules\n" + OpencodeIndexIgnorer.GITIGNORE_PATTERN + "\n";
		assertNull(OpencodeIndexIgnorer.ensureGitignoreContent(existing));
	}

	@Test
	public void ensureGitignoreContent_appendsWhenMissing() {
		String existing = "node_modules\ntarget/\n";
		String result = OpencodeIndexIgnorer.ensureGitignoreContent(existing);
		assertTrue(result.contains(OpencodeIndexIgnorer.GITIGNORE_PATTERN));
		assertTrue(result.startsWith("node_modules\ntarget/\n"));
		assertTrue(result.endsWith(OpencodeIndexIgnorer.GITIGNORE_PATTERN + "\n"));
	}

	@Test
	public void ensureGitignoreContent_addsMissingNewlineBeforeAppend() {
		String existing = "node_modules"; // no trailing newline
		String result = OpencodeIndexIgnorer.ensureGitignoreContent(existing);
		assertEquals("node_modules\n" + OpencodeIndexIgnorer.GITIGNORE_PATTERN + "\n", result);
	}

	@Test
	public void ensureGitignoreContent_preservesCrlf() {
		String existing = "node_modules\r\ntarget/\r\n";
		String result = OpencodeIndexIgnorer.ensureGitignoreContent(existing);
		assertTrue(result.endsWith(OpencodeIndexIgnorer.GITIGNORE_PATTERN + "\r\n"));
	}

	@Test
	public void ensureGitignoreContent_equivalentSpellingNotDuplicated() {
		// Same path without the leading/trailing slash should be recognised.
		String existing = ".opencode/index\n";
		assertNull(OpencodeIndexIgnorer.ensureGitignoreContent(existing));
	}

	@Test
	public void ensureGitignoreContent_commentedPatternStillAppends() {
		// A commented-out line must not count as present.
		String existing = "# /.opencode/index/\n";
		String result = OpencodeIndexIgnorer.ensureGitignoreContent(existing);
		assertTrue(result.contains("\n" + OpencodeIndexIgnorer.GITIGNORE_PATTERN));
	}

	// -----------------------------------------------------------------------
	// computeGitignore (line-list level)
	// -----------------------------------------------------------------------

	@Test
	public void computeGitignore_emptyList_addsPattern() {
		List<String> result = OpencodeIndexIgnorer.computeGitignore(Collections.emptyList());
		assertEquals(List.of(OpencodeIndexIgnorer.GITIGNORE_PATTERN), result);
	}

	@Test
	public void computeGitignore_patternPresent_unchanged() {
		List<String> lines = Arrays.asList("node_modules", OpencodeIndexIgnorer.GITIGNORE_PATTERN);
		List<String> result = OpencodeIndexIgnorer.computeGitignore(lines);
		assertEquals(lines, result);
	}

	// -----------------------------------------------------------------------
	// ensureGitignore (file level)
	// -----------------------------------------------------------------------

	@Test
	public void ensureGitignore_createsFileWhenAbsent() throws IOException {
		Path root = tmp.getRoot().toPath();
		OpencodeIndexIgnorer.ensureGitignore(root);
		Path gitignore = root.resolve(".gitignore");
		assertTrue(Files.exists(gitignore));
		assertTrue(Files.readString(gitignore, StandardCharsets.UTF_8)
				.contains(OpencodeIndexIgnorer.GITIGNORE_PATTERN));
	}

	@Test
	public void ensureGitignore_isIdempotent() throws IOException {
		Path root = tmp.getRoot().toPath();
		OpencodeIndexIgnorer.ensureGitignore(root);
		String afterFirst = Files.readString(root.resolve(".gitignore"), StandardCharsets.UTF_8);
		OpencodeIndexIgnorer.ensureGitignore(root);
		String afterSecond = Files.readString(root.resolve(".gitignore"), StandardCharsets.UTF_8);
		assertEquals(afterFirst, afterSecond);
	}

	@Test
	public void ensureGitignore_appendsToExistingFile() throws IOException {
		Path root = tmp.getRoot().toPath();
		Path gitignore = root.resolve(".gitignore");
		Files.writeString(gitignore, "node_modules\n", StandardCharsets.UTF_8);
		OpencodeIndexIgnorer.ensureGitignore(root);
		String content = Files.readString(gitignore, StandardCharsets.UTF_8);
		assertTrue(content.startsWith("node_modules\n"));
		assertTrue(content.contains(OpencodeIndexIgnorer.GITIGNORE_PATTERN));
	}
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.opencode.OpencodeFolderCreatorJob;

/**
 * Unit tests for the package-visible helper methods of
 * {@link OpencodeFolderCreatorJob}.
 * <p>
 * No OSGi runtime is required — all tests operate on temporary file-system
 * resources created via {@link TempDir}.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpencodeFolderCreatorJobTest
{
	@TempDir
	Path tmp;

	// --- needsInstall ---

	@Test
	public void needsInstall_markerAbsent_returnsTrue() throws Exception
	{
		File marker = new File(tmp.toFile(), ".fullygenerated"); // not created
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "{}", StandardCharsets.UTF_8);

		assertTrue(
			OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{}"), "Missing marker should trigger install");
	}

	@Test
	public void needsInstall_sentinelAbsent_returnsTrue() throws Exception
	{
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = new File(tmp.toFile(), "package_copy.json"); // not created

		assertTrue(
			OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{}"), "Missing sentinel should trigger install");
	}

	@Test
	public void needsInstall_contentMatches_returnsFalse() throws Exception
	{
		String content = "{\"name\":\"servoy-opencode-host\",\"version\":\"1.0.0\"}";
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), content, StandardCharsets.UTF_8);

		assertFalse(
			OpencodeFolderCreatorJob.needsInstall(marker, sentinel, content), "Matching sentinel should NOT trigger install");
	}

	@Test
	public void needsInstall_contentDiffers_returnsTrue() throws Exception
	{
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "{\"version\":\"^1.0.0\"}", StandardCharsets.UTF_8);

		assertTrue(
			OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{\"version\":\"^2.0.0\"}"), "Changed sentinel content should trigger re-install");
	}

	@Test
	public void needsInstall_bothPresent_emptyBundle_returnsFalse() throws Exception
	{
		// Edge case: empty string matches empty string — no re-install
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "", StandardCharsets.UTF_8);

		assertFalse(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, ""));
	}

	// --- readUrlContent ---

	@Test
	public void readUrlContent_readsFileUrl() throws Exception
	{
		String expected = "{\"name\":\"test\",\"version\":\"1.0\"}";
		File f = Files.createFile(tmp.resolve("pkg.json")).toFile();
		Files.writeString(f.toPath(), expected, StandardCharsets.UTF_8);

		String actual = OpencodeFolderCreatorJob.readUrlContent(f.toURI().toURL());
		assertEquals(expected, actual, "readUrlContent should return exact file contents");
	}

	@Test
	public void readUrlContent_handlesUnicode() throws Exception
	{
		String expected = "Servoy é opencode …";
		File f = Files.createFile(tmp.resolve("unicode.txt")).toFile();
		Files.writeString(f.toPath(), expected, StandardCharsets.UTF_8);

		assertEquals(expected, OpencodeFolderCreatorJob.readUrlContent(f.toURI().toURL()));
	}

	// --- deleteDirectory ---

	@Test
	public void deleteDirectory_removesNestedStructure() throws Exception
	{
		File root = Files.createDirectory(tmp.resolve("toDelete")).toFile();
		File sub = new File(root, "sub/deep");
		sub.mkdirs();
		new File(sub, "file.txt").createNewFile();
		new File(root, "toplevel.json").createNewFile();

		assertTrue(root.exists(), "Directory should exist before deletion");
		OpencodeFolderCreatorJob.deleteDirectory(root.toPath());
		assertFalse(root.exists(), "Directory should be gone after deleteDirectory()");
	}

	@Test
	public void deleteDirectory_emptyDir() throws Exception
	{
		File dir = Files.createDirectory(tmp.resolve("empty")).toFile();
		OpencodeFolderCreatorJob.deleteDirectory(dir.toPath());
		assertFalse(dir.exists(), "Empty directory should be removed");
	}
}

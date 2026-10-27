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
public class OpencodeFolderCreatorJobTest {
	@TempDir
	Path tmp;

	// --- needsInstall ---

	@Test
	public void needsInstall_markerAbsent_returnsTrue() throws Exception {
		File marker = new File(tmp.toFile(), ".fullygenerated"); // not created
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "{}", StandardCharsets.UTF_8);

		assertTrue(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{}"),
				"Missing marker should trigger install");
	}

	@Test
	public void needsInstall_sentinelAbsent_returnsTrue() throws Exception {
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = new File(tmp.toFile(), "package_copy.json"); // not created

		assertTrue(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{}"),
				"Missing sentinel should trigger install");
	}

	@Test
	public void needsInstall_contentMatches_returnsFalse() throws Exception {
		String content = "{\"name\":\"servoy-opencode-host\",\"version\":\"1.0.0\"}";
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), content, StandardCharsets.UTF_8);

		assertFalse(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, content),
				"Matching sentinel should NOT trigger install");
	}

	@Test
	public void needsInstall_contentDiffers_returnsTrue() throws Exception {
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "{\"version\":\"^1.0.0\"}", StandardCharsets.UTF_8);

		assertTrue(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, "{\"version\":\"^2.0.0\"}"),
				"Changed sentinel content should trigger re-install");
	}

	@Test
	public void needsInstall_bothPresent_emptyBundle_returnsFalse() throws Exception {
		// Edge case: empty string matches empty string — no re-install
		File marker = Files.createFile(tmp.resolve(".fullygenerated")).toFile();
		File sentinel = Files.createFile(tmp.resolve("package_copy.json")).toFile();
		Files.writeString(sentinel.toPath(), "", StandardCharsets.UTF_8);

		assertFalse(OpencodeFolderCreatorJob.needsInstall(marker, sentinel, ""));
	}

	// --- readUrlContent ---

	@Test
	public void readUrlContent_readsFileUrl() throws Exception {
		String expected = "{\"name\":\"test\",\"version\":\"1.0\"}";
		File f = Files.createFile(tmp.resolve("pkg.json")).toFile();
		Files.writeString(f.toPath(), expected, StandardCharsets.UTF_8);

		String actual = OpencodeFolderCreatorJob.readUrlContent(f.toURI().toURL());
		assertEquals(expected, actual, "readUrlContent should return exact file contents");
	}

	@Test
	public void readUrlContent_handlesUnicode() throws Exception {
		String expected = "Servoy é opencode …";
		File f = Files.createFile(tmp.resolve("unicode.txt")).toFile();
		Files.writeString(f.toPath(), expected, StandardCharsets.UTF_8);

		assertEquals(expected, OpencodeFolderCreatorJob.readUrlContent(f.toURI().toURL()));
	}

	// --- deleteDirectory ---

	@Test
	public void deleteDirectory_removesNestedStructure() throws Exception {
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
	public void deleteDirectory_emptyDir() throws Exception {
		File dir = Files.createDirectory(tmp.resolve("empty")).toFile();
		OpencodeFolderCreatorJob.deleteDirectory(dir.toPath());
		assertFalse(dir.exists(), "Empty directory should be removed");
	}

	// --- isPlaceholderBinary ---

	@Test
	public void isPlaceholderBinary_echoMarkerFile_returnsTrue() throws Exception {
		// The ~229-byte placeholder shell script shipped inside @opencode/cli: its
		// first non-blank line starts with the echo "Error: marker.
		String placeholder = "#!/bin/sh\n" + "echo \"Error: @opencode/cli's postinstall script was not run.\"\n"
				+ "exit 1\n";
		File bin = Files.createFile(tmp.resolve("opencode")).toFile();
		Files.writeString(bin.toPath(), placeholder, StandardCharsets.UTF_8);

		assertTrue(OpencodeFolderCreatorJob.isPlaceholderBinary(bin),
				"A file whose first line is the echo \"Error: marker is the placeholder");
	}

	@Test
	public void isPlaceholderBinary_null_returnsTrue() throws Exception {
		assertTrue(OpencodeFolderCreatorJob.isPlaceholderBinary(null), "A null file is treated as placeholder/missing");
	}

	@Test
	public void isPlaceholderBinary_missingFile_returnsTrue() throws Exception {
		File missing = new File(tmp.toFile(), "does-not-exist"); // never created
		assertTrue(OpencodeFolderCreatorJob.isPlaceholderBinary(missing),
				"A non-existent file is treated as placeholder/missing");
	}

	@Test
	public void isPlaceholderBinary_smallFileNoMarker_returnsTrue() throws Exception {
		// Below the size gate, even without the echo marker -> placeholder.
		byte[] small = new byte[(int) (OpencodeFolderCreatorJob.PLACEHOLDER_MAX_SIZE_BYTES - 1)];
		File bin = Files.createFile(tmp.resolve("tiny")).toFile();
		Files.write(bin.toPath(), small);

		assertTrue(OpencodeFolderCreatorJob.isPlaceholderBinary(bin),
				"A file below PLACEHOLDER_MAX_SIZE_BYTES is a placeholder regardless of content");
	}

	@Test
	public void isPlaceholderBinary_largeRealBinary_returnsFalse() throws Exception {
		// Well above the size gate and not starting with the echo marker -> real
		// binary.
		byte[] real = new byte[20 * 1024];
		real[0] = (byte) 0x4D; // 'M' - mimic a PE header start, definitely not "echo \"Error:"
		real[1] = (byte) 0x5A; // 'Z'
		File bin = Files.createFile(tmp.resolve("real-opencode")).toFile();
		Files.write(bin.toPath(), real);

		assertFalse(OpencodeFolderCreatorJob.isPlaceholderBinary(bin),
				"A large file not starting with the echo marker is a real binary");
	}

	@Test
	public void isPlaceholderBinary_largeFileWithMarker_returnsTrue() throws Exception {
		// Marker beats size: a large file whose first non-blank line is the echo
		// marker is still the placeholder (pins the marker branch).
		StringBuilder sb = new StringBuilder();
		sb.append("echo \"Error: @opencode/cli's postinstall script was not run.\"\n");
		while (sb.length() < 20 * 1024) {
			sb.append("# padding line to push the file well past the size gate\n");
		}
		File bin = Files.createFile(tmp.resolve("big-placeholder")).toFile();
		Files.writeString(bin.toPath(), sb.toString(), StandardCharsets.UTF_8);

		assertTrue(bin.length() > OpencodeFolderCreatorJob.PLACEHOLDER_MAX_SIZE_BYTES,
				"sanity: file must be larger than the size gate");
		assertTrue(OpencodeFolderCreatorJob.isPlaceholderBinary(bin),
				"The echo marker makes a file a placeholder even above the size gate");
	}
}

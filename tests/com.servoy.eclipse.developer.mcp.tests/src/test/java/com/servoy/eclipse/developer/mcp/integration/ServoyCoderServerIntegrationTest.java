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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyCoderServer;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService;

public class ServoyCoderServerIntegrationTest {
	private static final String PROJECT_NAME = "test_coder_server_suite";

	private ServoyCoderServer coderServer;
	private ServoyDevServer devServer;
	private IProject project;

	@Before
	public void setUp() throws Exception {
		coderServer = new ServoyCoderServer(new CodeEditingService());
		devServer = new ServoyDevServer();

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
			if (!p.exists()) {
				IProjectDescription desc = ResourcesPlugin.getWorkspace().newProjectDescription(PROJECT_NAME);
				p.create(desc, monitor);
			}
			if (!p.isOpen()) {
				p.open(monitor);
			}
		}, new NullProgressMonitor());

		project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
		assertNotNull("Test project should exist", project);
		assertTrue("Test project should be open", project.isOpen());
	}

	@After
	public void tearDown() throws Exception {
		if (project != null && project.exists()) {
			try {
				project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
				IResource[] members = project.members();
				for (IResource r : members) {
					if (!".project".equals(r.getName())) {
						try {
							r.delete(true, new NullProgressMonitor());
						} catch (Exception e) {
							// Windows file lock - best effort
						}
					}
				}
			} catch (Exception e) {
				// best effort cleanup
			}
		}
	}

	@Test
	public void testCreateFile_success() {
		String result = coderServer.createFile(PROJECT_NAME, "testfile.txt", "hello world");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		IFile file = project.getFile("testfile.txt");
		assertTrue("File should exist", file.exists());
	}

	@Test
	public void testCreateFile_inSubdirectory() {
		String result = coderServer.createFile(PROJECT_NAME, "subdir/nested.txt", "nested content");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		IFile file = project.getFile("subdir/nested.txt");
		assertTrue("File should exist in subdirectory", file.exists());
	}

	@Test
	public void testCreateFile_duplicateReturnsError() {
		coderServer.createFile(PROJECT_NAME, "dup.txt", "first");

		try {
			coderServer.createFile(PROJECT_NAME, "dup.txt", "second");
			assertTrue("Should have thrown for duplicate file", false);
		} catch (RuntimeException e) {
			assertTrue("Should mention already exists: " + e.getMessage(), e.getMessage().contains("already exists"));
		}
	}

	@Test
	public void testInsertIntoFile_atBeginning() throws Exception {
		createTestFile("insert_test.txt", "line1\nline2\nline3");

		String result = coderServer.insertIntoFile(PROJECT_NAME, "insert_test.txt", "inserted", "1");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("insert_test.txt");
		assertTrue("Inserted content should be at the beginning", content.startsWith("inserted"));
		assertTrue("Original content should still be present", content.contains("line1"));
	}

	@Test
	public void testInsertIntoFile_atMiddle() throws Exception {
		createTestFile("insert_mid.txt", "line1\nline2\nline3");

		String result = coderServer.insertIntoFile(PROJECT_NAME, "insert_mid.txt", "inserted", "2");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("insert_mid.txt");
		String[] lines = content.split("\n");
		assertEquals("line1", lines[0]);
		assertEquals("inserted", lines[1]);
		assertEquals("line2", lines[2]);
	}

	@Test
	public void testReplaceString_success() throws Exception {
		createTestFile("replace_test.txt", "hello world\nfoo bar\nbaz");

		String result = coderServer.replaceString(PROJECT_NAME, "replace_test.txt", "foo bar", "replaced", null, null);

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("replace_test.txt");
		assertTrue("Should contain replacement", content.contains("replaced"));
		assertFalse("Should not contain old string", content.contains("foo bar"));
	}

	@Test
	public void testReplaceString_withLineRange() throws Exception {
		createTestFile("replace_range.txt", "aaa\nbbb\nccc\nddd");

		String result = coderServer.replaceString(PROJECT_NAME, "replace_range.txt", "bbb", "BBB", "2", "2");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("replace_range.txt");
		assertTrue("Should contain replacement", content.contains("BBB"));
		assertTrue("Other lines unchanged", content.contains("aaa") && content.contains("ccc"));
	}

	@Test
	public void testReplaceString_notFound() {
		createTestFile("replace_nf.txt", "hello world");

		try {
			coderServer.replaceString(PROJECT_NAME, "replace_nf.txt", "nonexistent", "new", null, null);
			assertTrue("Should have thrown for not-found string", false);
		} catch (RuntimeException e) {
			assertTrue("Should mention not found: " + e.getMessage(), e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testReplaceFileContent_success() throws Exception {
		createTestFile("replace_content.txt", "old content");

		String result = coderServer.replaceFileContent(PROJECT_NAME, "replace_content.txt", "brand new content");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("replace_content.txt");
		assertTrue("Should have new content", content.contains("brand new content"));
		assertFalse("Should not have old content", content.contains("old content"));
	}

	@Test
	public void testDeleteLinesInFile_success() throws Exception {
		createTestFile("delete_lines.txt", "line1\nline2\nline3\nline4");

		String result = coderServer.deleteLinesInFile(PROJECT_NAME, "delete_lines.txt", "2", "3");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("delete_lines.txt");
		assertTrue("Should contain line1", content.contains("line1"));
		assertTrue("Should contain line4", content.contains("line4"));
		assertFalse("Should not contain line2", content.contains("line2"));
		assertFalse("Should not contain line3", content.contains("line3"));
	}

	@Test
	public void testApplyPatch_success() throws Exception {
		createTestFile("patch_test.txt", "line1\nline2\nline3\n");

		String patch = "@@ -1,3 +1,3 @@\n line1\n-line2\n+patched\n line3\n";
		String result = coderServer.applyPatch(PROJECT_NAME, "patch_test.txt", patch);

		assertTrue("Should succeed: " + result, result.contains("Success"));
		String content = readFile("patch_test.txt");
		assertTrue("Should contain patched line", content.contains("patched"));
		assertFalse("Should not contain old line", content.contains("line2"));
	}

	@Test
	public void testCreateDirectories_success() {
		String result = coderServer.createDirectories(PROJECT_NAME, "dir1/dir2/dir3");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		IFolder folder = project.getFolder("dir1/dir2/dir3");
		assertTrue("Directory should exist", folder.exists());
	}

	@Test
	public void testCreateDirectories_alreadyExists() {
		coderServer.createDirectories(PROJECT_NAME, "existing_dir");
		String result = coderServer.createDirectories(PROJECT_NAME, "existing_dir");

		assertTrue("Should indicate already exists: " + result, result.contains("already exists"));
	}

	@Test
	public void testRenameFile_success() throws Exception {
		createTestFile("rename_me.txt", "content");

		String result = devServer.renameFile(PROJECT_NAME + "/rename_me.txt", "renamed.txt");

		assertTrue("Should succeed: " + result, result.contains("Success") || result.contains("successfully"));
		IFile oldFile = project.getFile("rename_me.txt");
		IFile newFile = project.getFile("renamed.txt");
		assertFalse("Old file should not exist", oldFile.exists());
		assertTrue("New file should exist", newFile.exists());
	}

	@Test
	public void testRenameFile_targetExists() throws Exception {
		createTestFile("src_file.txt", "source");
		createTestFile("target_file.txt", "target");

		String result = devServer.renameFile(PROJECT_NAME + "/src_file.txt", "target_file.txt");

		assertNotNull("Should return a result string", result);
		assertTrue("Should return error for duplicate target: " + result,
				result.startsWith("Error") && (result.contains("already exists") || result.contains("Error")));
	}

	@Test
	public void testMoveResource_success() throws Exception {
		createTestFile("move_me.txt", "moving");
		coderServer.createDirectories(PROJECT_NAME, "destination");

		String result = coderServer.moveResource(PROJECT_NAME, "move_me.txt", "destination");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		IFile oldFile = project.getFile("move_me.txt");
		IFile newFile = project.getFile("destination/move_me.txt");
		assertFalse("Old file should not exist", oldFile.exists());
		assertTrue("File should exist at destination", newFile.exists());
	}

	@Test
	public void testMoveResource_sourceNotFound() {
		try {
			coderServer.moveResource(PROJECT_NAME, "nonexistent.txt", "somewhere");
			assertTrue("Should have thrown for missing source", false);
		} catch (RuntimeException e) {
			assertTrue("Should mention not exist: " + e.getMessage(),
					e.getMessage().contains("does not exist") || e.getMessage().contains("not found"));
		}
	}

	@Test
	public void testDeleteFile_success() throws Exception {
		createTestFile("delete_me.txt", "to be deleted");

		String result = coderServer.deleteFile(PROJECT_NAME, "delete_me.txt");

		assertTrue("Should succeed: " + result, result.contains("Success"));
		IFile file = project.getFile("delete_me.txt");
		assertFalse("File should no longer exist", file.exists());
	}

	@Test
	public void testDeleteFile_nullPath() {
		String result = coderServer.deleteFile(PROJECT_NAME, null);

		assertTrue("Should return error: " + result, result.contains("Error"));
	}

	@Test
	public void testDeleteFile_blankPath() {
		String result = coderServer.deleteFile(PROJECT_NAME, "   ");

		assertTrue("Should return error: " + result, result.contains("Error"));
	}

	@Test
	public void testDeleteFile_formJsRejected() {
		String result = coderServer.deleteFile(PROJECT_NAME, "forms/myForm.js");

		assertTrue("Should return error about script companion: " + result, result.contains("Error"));
		assertTrue("Should suggest deleting .frm instead: " + result, result.contains(".frm"));
	}

	@Test
	public void testUndoEdit_noBackup() {
		// A freshly created file has no Local History, so undoEdit must report an
		// error.
		createTestFile("no_backup.txt", "original");

		try {
			String result = coderServer.undoEdit(PROJECT_NAME, "no_backup.txt");
			assertNotNull("Result should not be null", result);
			assertTrue("Should report no edit history: " + result, result.contains("No edit history"));
		} catch (RuntimeException e) {
			assertTrue("Should report no edit history: " + e.getMessage(),
					e.getMessage() != null && e.getMessage().contains("No edit history"));
		}
	}

	@Test
	public void testUndoEdit_afterEdit() throws Exception {
		// Build Local History for the file: create with v1, then overwrite with v2
		// keeping
		// history. This makes getHistory() return the v1 state so undoEdit can restore
		// it.
		createTestFile("undo_test.txt", "original content");
		setContentsKeepingHistory("undo_test.txt", "modified content");

		String content = readFile("undo_test.txt");
		assertTrue("Should be modified before undo", content.contains("modified"));

		String result = coderServer.undoEdit(PROJECT_NAME, "undo_test.txt");

		assertNotNull("Undo result should not be null", result);
		assertTrue("Undo should succeed: " + result, result.contains("Success"));

		String restored = readFile("undo_test.txt");
		assertTrue("Content should be restored to original: " + restored, restored.contains("original content"));
	}

	/**
	 * Overwrites a file's contents while preserving the prior version in Eclipse
	 * Local History (IResource.KEEP_HISTORY). This is what allows undoEdit's
	 * getHistory() call to find a previous state to restore.
	 */
	private void setContentsKeepingHistory(String path, String content) throws Exception {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IFile file = project.getFile(path);
			file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), IResource.KEEP_HISTORY,
					monitor);
		}, new NullProgressMonitor());
	}

	private void createTestFile(String path, String content) {
		try {
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				if (file.exists()) {
					file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, false,
							monitor);
				} else {
					org.eclipse.core.resources.IContainer parent = file.getParent();
					if (parent instanceof IFolder && !parent.exists()) {
						createFolderHierarchy((IFolder) parent, monitor);
					}
					file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
				}
			}, new NullProgressMonitor());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create test file: " + path, e);
		}
	}

	private void createFolderHierarchy(IFolder folder, org.eclipse.core.runtime.IProgressMonitor monitor)
			throws org.eclipse.core.runtime.CoreException {
		if (!folder.getParent().exists() && folder.getParent() instanceof IFolder) {
			createFolderHierarchy((IFolder) folder.getParent(), monitor);
		}
		if (!folder.exists()) {
			folder.create(true, true, monitor);
		}
	}

	private String readFile(String path) throws Exception {
		IFile file = project.getFile(path);
		file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
		try (java.io.InputStream is = file.getContents()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;

/**
 * Integration tests for {@link ServoyMediaServer} - tests the media_rename tool
 * with a real Servoy workspace.
 *
 * These tests require a running Servoy application server and create a test
 * solution. They must be run as JUnit Plug-in Tests.
 */
public class ServoyMediaServerIntegrationTest extends TestUtilitiesClass {

	private static final String TEST_SOLUTION = "test_media_server_suite";

	private ServoyMediaServer mediaServer;
	private ServoyProject activeProject;

	public ServoyMediaServerIntegrationTest() {
		super(TEST_SOLUTION, "servoy_resources");
	}

	@Before
	public void setUp() throws Exception {
		mediaServer = new ServoyMediaServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	@Test
	public void testMediaRename_singleFile_success() throws Exception {
		String mediaName = "testMedia_" + System.currentTimeMillis() + ".css";
		String newName = "testMedia_" + System.currentTimeMillis() + "_renamed.css";

		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, newName);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"renamed\": true"));
		assertTrue("Should contain old name: " + result, result.contains(mediaName));
		assertTrue("Should contain new name: " + result, result.contains(newName));

		Solution solution = activeProject.getEditingSolution();
		assertNotNull("Renamed media should exist", solution.getMedia(newName));
	}

	@Test
	public void testMediaRename_singleFile_notFound() throws Exception {
		String result = mediaServer.mediaRename(TEST_SOLUTION, "nonexistent_file_xyz.png", "new_name.png");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testMediaRename_singleFile_duplicateTarget() throws Exception {
		String mediaName1 = "dupSrc_" + System.currentTimeMillis() + ".png";
		String mediaName2 = "dupTgt_" + System.currentTimeMillis() + ".png";

		createMedia(mediaName1);
		createMedia(mediaName2);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName1, mediaName2);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention already exists: " + result, result.contains("already exists"));
	}

	@Test
	public void testMediaRename_singleFile_invalidName_spaces() throws Exception {
		String mediaName = "validMedia_" + System.currentTimeMillis() + ".css";
		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, "invalid name.css");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention spaces: " + result, result.contains("spaces"));
	}

	@Test
	public void testMediaRename_singleFile_invalidName_characters() throws Exception {
		String mediaName = "validMedia2_" + System.currentTimeMillis() + ".css";
		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, "invalid@name.css");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention invalid: " + result, result.contains("invalid"));
	}

	@Test
	public void testMediaRename_folderRename_success() throws Exception {
		String ts = String.valueOf(System.currentTimeMillis());
		String oldFolder = "oldfolder_" + ts + "/";
		String newFolder = "newfolder_" + ts + "/";

		createMedia(oldFolder + "file1.css");
		createMedia(oldFolder + "file2.js");
		createMedia(oldFolder + "sub/file3.png");

		String result = mediaServer.mediaRename(TEST_SOLUTION, oldFolder, newFolder);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"renamed\": true"));
		assertTrue("Should contain renamed count: " + result, result.contains("\"renamedCount\": 3"));

		Solution solution = activeProject.getEditingSolution();
		assertNotNull("file1 should be renamed", solution.getMedia(newFolder + "file1.css"));
		assertNotNull("file2 should be renamed", solution.getMedia(newFolder + "file2.js"));
		assertNotNull("file3 should be renamed", solution.getMedia(newFolder + "sub/file3.png"));
	}

	@Test
	public void testMediaRename_folderRename_noMediaFound() throws Exception {
		String result = mediaServer.mediaRename(TEST_SOLUTION, "nonexistent_folder_xyz/", "new_folder/");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention no media found: " + result, result.contains("No media found"));
	}

	@Test
	public void testMediaRename_folderRename_newFolderMissingTrailingSlash() throws Exception {
		String ts = String.valueOf(System.currentTimeMillis());
		String oldFolder = "slashtest_" + ts + "/";
		createMedia(oldFolder + "file.css");

		String result = mediaServer.mediaRename(TEST_SOLUTION, oldFolder, "noslash_" + ts);

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention trailing slash: " + result, result.contains("'/'"));
	}

	@Test
	public void testMediaRename_solutionNotFound() throws Exception {
		String result = mediaServer.mediaRename("nonexistent_solution_xyz_999", "file.css", "newfile.css");

		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
		assertTrue("Should mention not found: " + result, result.contains("not found"));
	}

	@Test
	public void testMediaRename_singleFile_withSubfolder() throws Exception {
		String ts = String.valueOf(System.currentTimeMillis());
		String mediaName = "css/style_" + ts + ".css";
		String newName = "css/style_" + ts + "_renamed.css";

		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, newName);

		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("\"renamed\": true"));

		Solution solution = activeProject.getEditingSolution();
		assertNotNull("Renamed media should exist at new path", solution.getMedia(newName));
	}

	private void createMedia(String name) throws Exception {
		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Media media = solution.createNewMedia(validator, name);
		assertNotNull("Media creation should succeed for: " + name, media);
		media.setMimeType("application/octet-stream");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);
	}

}

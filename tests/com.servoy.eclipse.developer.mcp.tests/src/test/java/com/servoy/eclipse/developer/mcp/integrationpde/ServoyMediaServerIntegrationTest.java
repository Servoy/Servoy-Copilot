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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
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
public class ServoyMediaServerIntegrationTest extends TestUtilitiesClassCopy {

	private static final String TEST_SOLUTION = "test_media_server_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private ServoyMediaServer mediaServer;
	private ServoyProject activeProject;

	public ServoyMediaServerIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		mediaServer = new ServoyMediaServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	@Test
	public void testMediaRename_singleFile_success() throws Exception {
		String mediaName = "testMedia_" + System.currentTimeMillis() + ".css";
		String newName = "testMedia_" + System.currentTimeMillis() + "_renamed.css";

		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, newName);

		assertNotNull(result);
		assertTrue(result.contains("\"renamed\": true"), "Should indicate success: " + result);
		assertTrue(result.contains(mediaName), "Should contain old name: " + result);
		assertTrue(result.contains(newName), "Should contain new name: " + result);

		Solution solution = activeProject.getEditingSolution();
		assertNotNull(solution.getMedia(newName), "Renamed media should exist");
	}

	@Test
	public void testMediaRename_singleFile_notFound() throws Exception {
		String result = mediaServer.mediaRename(TEST_SOLUTION, "nonexistent_file_xyz.png", "new_name.png");

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testMediaRename_singleFile_duplicateTarget() throws Exception {
		String mediaName1 = "dupSrc_" + System.currentTimeMillis() + ".png";
		String mediaName2 = "dupTgt_" + System.currentTimeMillis() + ".png";

		createMedia(mediaName1);
		createMedia(mediaName2);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName1, mediaName2);

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("already exists"), "Should mention already exists: " + result);
	}

	@Test
	public void testMediaRename_singleFile_invalidName_spaces() throws Exception {
		String mediaName = "validMedia_" + System.currentTimeMillis() + ".css";
		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, "invalid name.css");

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("spaces"), "Should mention spaces: " + result);
	}

	@Test
	public void testMediaRename_singleFile_invalidName_characters() throws Exception {
		String mediaName = "validMedia2_" + System.currentTimeMillis() + ".css";
		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, "invalid@name.css");

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("invalid"), "Should mention invalid: " + result);
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
		assertTrue(result.contains("\"renamed\": true"), "Should indicate success: " + result);
		assertTrue(result.contains("\"renamedCount\": 3"), "Should contain renamed count: " + result);

		Solution solution = activeProject.getEditingSolution();
		assertNotNull(solution.getMedia(newFolder + "file1.css"), "file1 should be renamed");
		assertNotNull(solution.getMedia(newFolder + "file2.js"), "file2 should be renamed");
		assertNotNull(solution.getMedia(newFolder + "sub/file3.png"), "file3 should be renamed");
	}

	@Test
	public void testMediaRename_folderRename_noMediaFound() throws Exception {
		String result = mediaServer.mediaRename(TEST_SOLUTION, "nonexistent_folder_xyz/", "new_folder/");

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("No media found"), "Should mention no media found: " + result);
	}

	@Test
	public void testMediaRename_folderRename_newFolderMissingTrailingSlash() throws Exception {
		String ts = String.valueOf(System.currentTimeMillis());
		String oldFolder = "slashtest_" + ts + "/";
		createMedia(oldFolder + "file.css");

		String result = mediaServer.mediaRename(TEST_SOLUTION, oldFolder, "noslash_" + ts);

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("'/'"), "Should mention trailing slash: " + result);
	}

	@Test
	public void testMediaRename_solutionNotFound() throws Exception {
		String result = mediaServer.mediaRename("nonexistent_solution_xyz_999", "file.css", "newfile.css");

		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
		assertTrue(result.contains("not found"), "Should mention not found: " + result);
	}

	@Test
	public void testMediaRename_singleFile_withSubfolder() throws Exception {
		String ts = String.valueOf(System.currentTimeMillis());
		String mediaName = "css/style_" + ts + ".css";
		String newName = "css/style_" + ts + "_renamed.css";

		createMedia(mediaName);

		String result = mediaServer.mediaRename(TEST_SOLUTION, mediaName, newName);

		assertNotNull(result);
		assertTrue(result.contains("\"renamed\": true"), "Should indicate success: " + result);

		Solution solution = activeProject.getEditingSolution();
		assertNotNull(solution.getMedia(newName), "Renamed media should exist at new path");
	}

	private void createMedia(String name) throws Exception {
		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		Media media = solution.createNewMedia(validator, name);
		assertNotNull(media, "Media creation should succeed for: " + name);
		media.setMimeType("application/octet-stream");
		media.setPermMediaData(new byte[] { 0x00 });
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);
	}

}

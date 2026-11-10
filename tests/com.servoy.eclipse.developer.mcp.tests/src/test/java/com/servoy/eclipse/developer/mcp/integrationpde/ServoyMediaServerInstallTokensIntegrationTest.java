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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;

/**
 * Integration tests for the {@code media_install_tokens} tool on {@link ServoyMediaServer} (SVY-21615), against a real
 * Servoy workspace with an activated solution: media creation/registration, the single inlined {@code @import} in the
 * solution stylesheet, idempotency, in-place update, writes to a module that owns the tokens media, and error paths.
 *
 * These tests require a running Servoy application server and must be run as JUnit Plug-in Tests.
 */
public class ServoyMediaServerInstallTokensIntegrationTest extends TestUtilitiesClassCopy {

	private static final String TEST_SOLUTION = "test_media_install_tokens_suite";
	private static final String TEST_MODULE = "test_media_install_tokens_module";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String STYLESHEET = TEST_SOLUTION + ".less";

	private ServoyMediaServer mediaServer;
	private ServoyProject activeProject;

	public ServoyMediaServerInstallTokensIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, TEST_MODULE, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		mediaServer = new ServoyMediaServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureSolutionInWorkspace(TEST_MODULE, UUID.randomUUID().toString(), SERVOY_RESOURCES, null, null);
		ensureTestSolutionInWorkspace(new String[] { TEST_MODULE }, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	@Test
	public void testInstall_createsTokensMedia_withBytesAndCssMimeAndUuid() throws Exception {
		String fileName = "tokens_" + System.currentTimeMillis() + ".css";
		String content = ":root { --brand-primary: #0066cc; --spacing-md: 16px; }";

		String result = mediaServer.installDesignTokens(content, fileName, null);

		assertTrue(result.contains("\"media\": \"created\""), result);
		assertTrue(result.contains("\"mediaFile\": \"" + fileName + "\""), result);
		assertTrue(result.contains("\"mediaSolution\": \"" + TEST_SOLUTION + "\""), result);

		Media media = activeProject.getEditingSolution().getMedia(fileName);
		assertNotNull(media, "tokens media must be created in the active solution");
		assertNotNull(media.getUUID(), "tokens media must have a UUID");
		assertEquals("text/css", media.getMimeType());
		assertEquals(content, new String(media.getMediaData(), StandardCharsets.UTF_8));
		assertTrue(result.contains("\"solution\": \"" + TEST_SOLUTION + "\""), result);
		assertEquals(content, readMediaFileOnDisk(activeProject, fileName), "media file must be written under medias/");
		String mediasObj = new String(Files.readAllBytes(
				activeProject.getProject().getLocation().toFile().toPath().resolve("medias.obj")),
				StandardCharsets.UTF_8);
		assertTrue(mediasObj.contains(media.getUUID().toString()), "media must be registered in medias.obj");
	}

	@Test
	public void testInstall_defaultMediaFileName_isTokensCss() throws Exception {
		String result = mediaServer.installDesignTokens(":root { --accent: #ff0000; }", null, null);

		assertTrue(result.contains("\"mediaFile\": \"tokens.css\""), result);
		assertNotNull(activeProject.getEditingSolution().getMedia("tokens.css"), "default tokens.css media");
	}

	@Test
	public void testInstall_addsExactlyOneInlineImportToSolutionStylesheet() throws Exception {
		String fileName = "tokens_import_" + System.currentTimeMillis() + ".css";
		setupSolutionStylesheet("@import 'servoy_theme.less';\nbody { color: red; }\n");

		String result = mediaServer.installDesignTokens(":root { --x: 1px; }", fileName, null);

		assertTrue(result.contains("\"import\": \"added\""), result);
		assertTrue(result.contains("\"stylesheet\": \"" + STYLESHEET + "\""), result);
		assertEquals("@import 'servoy_theme.less';\n@import (inline) \"" + fileName + "\";\nbody { color: red; }\n",
				readStylesheetText());
	}

	@Test
	public void testInstall_plainImportOfSameFile_isUpgradedToInline() throws Exception {
		String fileName = "tokens_upgrade_" + System.currentTimeMillis() + ".css";
		setupSolutionStylesheet("@import \"" + fileName + "\";\nbody { color: red; }\n");

		String result = mediaServer.installDesignTokens(":root { --x: 1px; }", fileName, null);

		assertTrue(result.contains("\"import\": \"upgraded\""), result);
		assertEquals("@import (inline) \"" + fileName + "\";\nbody { color: red; }\n", readStylesheetText());
	}

	@Test
	public void testInstall_stylesheetNotResolvable_reportsImportSkipped_mediaStillInstalled() throws Exception {
		clearSolutionStylesheet();
		String fileName = "tokens_noss_" + System.currentTimeMillis() + ".css";

		String result = mediaServer.installDesignTokens(":root { --y: 2px; }", fileName, null);

		assertTrue(result.contains("\"import\": \"skipped\""), result);
		assertTrue(result.contains("\"media\": \"created\""), result);
		assertNotNull(activeProject.getEditingSolution().getMedia(fileName), "tokens media must still be installed");
	}

	@Test
	public void testInstall_rerunSameInputs_leavesOneMediaAndOneImport() throws Exception {
		String fileName = "tokens_idem_" + System.currentTimeMillis() + ".css";
		String content = ":root { --idem: 10px; }";
		setupSolutionStylesheet("body { color: red; }\n");

		String first = mediaServer.installDesignTokens(content, fileName, null);
		assertTrue(first.contains("\"media\": \"created\""), first);
		assertTrue(first.contains("\"import\": \"added\""), first);
		String afterFirst = readStylesheetText();

		String second = mediaServer.installDesignTokens(content, fileName, null);
		assertTrue(second.contains("\"media\": \"updated\""), second);
		assertTrue(second.contains("\"import\": \"alreadyPresent\""), second);

		assertEquals(1, countMediaNamed(activeProject.getEditingSolution(), fileName), "one tokens media after re-run");
		assertEquals(afterFirst, readStylesheetText(), "stylesheet unchanged by re-run");
	}

	@Test
	public void testInstall_rerunChangedContent_updatesBytesInPlace() throws Exception {
		String fileName = "tokens_update_" + System.currentTimeMillis() + ".css";
		setupSolutionStylesheet("body { color: red; }\n");

		mediaServer.installDesignTokens(":root { --v: 1px; }", fileName, null);
		Media created = activeProject.getEditingSolution().getMedia(fileName);
		String changed = ":root { --v: 999px; --w: blue; }";
		String result = mediaServer.installDesignTokens(changed, fileName, null);

		assertTrue(result.contains("\"media\": \"updated\""), result);
		Media media = activeProject.getEditingSolution().getMedia(fileName);
		assertEquals(created.getUUID(), media.getUUID(), "same media, updated in place");
		assertEquals(1, countMediaNamed(activeProject.getEditingSolution(), fileName));
		assertEquals(changed, new String(media.getMediaData(), StandardCharsets.UTF_8));
	}

	@Test
	public void testInstall_tokensMediaOwnedByModule_isUpdatedInModule() throws Exception {
		String fileName = "tokens_module_" + System.currentTimeMillis() + ".css";
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject moduleProject = model.getServoyProject(TEST_MODULE);
		assertNotNull(moduleProject, "module project");
		Solution module = moduleProject.getEditingSolution();
		IValidateName validator = model.getNameValidator();
		Media moduleMedia = module.createNewMedia(validator, fileName);
		moduleMedia.setMimeType("text/css");
		moduleMedia.setPermMediaData(":root { --m: 1px; }".getBytes(StandardCharsets.UTF_8));
		moduleProject.saveEditingSolutionNodes(new IPersist[] { moduleMedia }, true);
		waitForWorkspaceBuildJobs();
		pumpEventsUntil(4000, () -> assertNotNull(activeProject.getEditingFlattenedSolution().getMedia(fileName),
				"module media must be visible through the flattened solution"));

		String changed = ":root { --m: 2px; }";
		String result = mediaServer.installDesignTokens(changed, fileName, null);

		assertTrue(result.contains("\"media\": \"updated\""), result);
		assertTrue(result.contains("\"mediaSolution\": \"" + TEST_MODULE + "\""), result);
		assertNull(activeProject.getEditingSolution().getMedia(fileName), "no duplicate media in the active solution");
		Media updated = moduleProject.getEditingSolution().getMedia(fileName);
		assertSame(moduleMedia, updated, "the module's own media must be the one updated");
		assertEquals(changed, new String(updated.getMediaData(), StandardCharsets.UTF_8));
		assertEquals(changed, readMediaFileOnDisk(moduleProject, fileName), "module media file on disk must be updated");
		assertFalse(Files.exists(mediaFileOnDisk(activeProject, fileName)), "no media file written into the active solution");
	}

	@Test
	public void testInstall_stylesheetOwnedByModule_importAddedInModule() throws Exception {
		String fileName = "tokens_modss_" + System.currentTimeMillis() + ".css";
		String moduleStylesheetName = "module_" + System.currentTimeMillis() + ".less";
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject moduleProject = model.getServoyProject(TEST_MODULE);
		Media moduleStylesheet = moduleProject.getEditingSolution().createNewMedia(model.getNameValidator(), moduleStylesheetName);
		moduleStylesheet.setMimeType("text/css");
		moduleStylesheet.setPermMediaData("body { color: red; }\n".getBytes(StandardCharsets.UTF_8));
		moduleProject.saveEditingSolutionNodes(new IPersist[] { moduleStylesheet }, true);
		Solution solution = activeProject.getEditingSolution();
		String previousStyleSheetID = solution.getStyleSheetID();
		solution.setStyleSheetID(moduleStylesheet.getUUID().toString());
		activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, true);
		waitForWorkspaceBuildJobs();
		pumpEventsUntil(4000, () -> assertNotNull(
				activeProject.getEditingFlattenedSolution().getMedia(moduleStylesheet.getUUID().toString()),
				"module stylesheet must be visible through the flattened solution"));
		try {
			String result = mediaServer.installDesignTokens(":root { --s: 1px; }", fileName, null);

			assertTrue(result.contains("\"stylesheet\": \"" + moduleStylesheetName + "\""), result);
			assertTrue(result.contains("\"stylesheetSolution\": \"" + TEST_MODULE + "\""), result);
			assertTrue(result.contains("\"import\": \"added\""), result);
			String expected = "@import (inline) \"" + fileName + "\";\nbody { color: red; }\n";
			assertEquals(expected, new String(moduleStylesheet.getMediaData(), StandardCharsets.UTF_8));
			assertEquals(expected, readMediaFileOnDisk(moduleProject, moduleStylesheetName),
					"module stylesheet on disk must carry the import");
		} finally {
			solution.setStyleSheetID(previousStyleSheetID);
			activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, true);
		}
	}

	@Test
	public void testInstall_stylesheetSaveFails_returnsError_andRestoresStylesheetBytes() throws Exception {
		String fileName = "tokens_ssfail_" + System.currentTimeMillis() + ".css";
		String original = "body { color: red; }\n";
		setupSolutionStylesheet(original);
		ServoyMediaServer failing = new ServoyMediaServer() {
			@Override
			protected void saveMedia(ServoyProject owner, Media media) throws RepositoryException {
				if (STYLESHEET.equals(media.getName()))
					throw new RepositoryException("simulated save failure");
				super.saveMedia(owner, media);
			}
		};

		String result = failing.installDesignTokens(":root { --f: 1px; }", fileName, null);

		assertEquals("Error: tokens media '" + fileName + "' was created in solution '" + TEST_SOLUTION
				+ "', but adding the @import to stylesheet '" + STYLESHEET + "' failed: simulated save failure", result);
		assertEquals(original, readStylesheetText(), "stylesheet bytes must be restored after a failed save");
		assertNotNull(activeProject.getEditingSolution().getMedia(fileName), "tokens media stays installed");
	}

	@Test
	public void testInstall_tokensUpdateSaveFails_returnsError_andRestoresBytesAndMime() throws Exception {
		String fileName = "tokens_updfail_" + System.currentTimeMillis() + ".css";
		mediaServer.installDesignTokens(":root { --old: 1px; }", fileName, null);
		Media media = activeProject.getEditingSolution().getMedia(fileName);
		media.setMimeType("text/plain");
		activeProject.saveEditingSolutionNodes(new IPersist[] { media }, true);
		ServoyMediaServer failing = new ServoyMediaServer() {
			@Override
			protected void saveMedia(ServoyProject owner, Media m) throws RepositoryException {
				if (fileName.equals(m.getName()))
					throw new RepositoryException("simulated save failure");
				super.saveMedia(owner, m);
			}
		};

		String result = failing.installDesignTokens(":root { --new: 2px; }", fileName, null);

		assertEquals("Error: simulated save failure (cleanup also failed: simulated save failure)", result,
				"every save of this media fails, so the restore save fails too and is reported");
		assertEquals(":root { --old: 1px; }", new String(media.getMediaData(), StandardCharsets.UTF_8),
				"bytes must be restored after a failed save");
		assertEquals("text/plain", media.getMimeType(), "mime type must be restored after a failed save");
	}

	@Test
	public void testInstall_noActiveSolution_returnsError() throws Exception {
		ensureSolutionReadyAndOptionallyActive(null, true);
		try {
			String result = mediaServer.installDesignTokens(":root { --a: 1px; }", null, null);
			assertEquals("Error: No active Servoy solution. Activate a solution first.", result);
		} finally {
			ensureActiveProject();
		}
	}

	@Test
	public void testInstall_blankContent_returnsError() throws Exception {
		String result = mediaServer.installDesignTokens("   ", "tokens.css", null);
		assertEquals("Error: sourcePath or tokensContent is required.", result);
	}

	@Test
	public void testInstall_mediaFileNameNotCss_returnsError() throws Exception {
		String result = mediaServer.installDesignTokens(":root { --a: 1px; }", "tokens.less", null);
		assertEquals("Error: mediaFileName must end in .css: tokens.less", result);
		assertNull(activeProject.getEditingSolution().getMedia("tokens.less"), "no media on error");
	}

	@Test
	public void testInstall_mediaFileNameIsStylesheet_returnsError_stylesheetUntouched() throws Exception {
		String original = "@import 'servoy_theme.less';\nbody { color: red; }\n";
		Solution solution = activeProject.getEditingSolution();
		String cssName = "main_" + System.currentTimeMillis() + ".css";
		Media cssStylesheet = solution.createNewMedia(ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator(), cssName);
		cssStylesheet.setMimeType("text/css");
		cssStylesheet.setPermMediaData(original.getBytes(StandardCharsets.UTF_8));
		String previousStyleSheetID = solution.getStyleSheetID();
		solution.setStyleSheetID(cssStylesheet.getUUID().toString());
		activeProject.saveEditingSolutionNodes(new IPersist[] { cssStylesheet, solution }, true);
		try {
			String result = mediaServer.installDesignTokens(":root { --a: 1px; }", cssName, null);

			assertTrue(result.startsWith("Error: mediaFileName '" + cssName + "' is the solution stylesheet"), result);
			assertEquals(original, readStylesheetText(), "stylesheet must be left untouched");
		} finally {
			solution.setStyleSheetID(previousStyleSheetID);
			activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, true);
		}
	}

	@Test
	public void testInstall_fromSourcePath_copiesWholeFileByteForByte() throws Exception {
		String fileName = "tokens_src_" + System.currentTimeMillis() + ".css";
		String content = "/* generated */\r\n:root {\r\n  --brand-primary: #0066cc;\r\n  --spacing-md: 16px;\r\n}\r\n";
		Path source = activeProject.getProject().getLocation().toFile().toPath()
				.resolve("svy21615_full_" + System.currentTimeMillis() + ".css");
		try {
			Files.write(source, content.getBytes(StandardCharsets.UTF_8));
			setupSolutionStylesheet("body { color: red; }\n");

			String result = mediaServer.installDesignTokens(null, fileName, source.toString());

			assertTrue(result.contains("\"media\": \"created\""), result);
			assertTrue(result.contains("\"import\": \"added\""), result);
			Media media = activeProject.getEditingSolution().getMedia(fileName);
			assertNotNull(media, "tokens media must be created from sourcePath");
			assertEquals(content, new String(media.getMediaData(), StandardCharsets.UTF_8));
		} finally {
			Files.deleteIfExists(source);
		}
	}

	@Test
	public void testInstall_relativeSourcePath_resolvesAgainstWorkspaceRoot() throws Exception {
		String fileName = "tokens_rel_" + System.currentTimeMillis() + ".css";
		String sourceName = "svy21615_src_" + System.currentTimeMillis() + ".css";
		Path projectDir = activeProject.getProject().getLocation().toFile().toPath();
		Path source = projectDir.resolve(sourceName);
		try {
			Files.write(source, ":root { --rel: 3px; }".getBytes(StandardCharsets.UTF_8));

			String result = mediaServer.installDesignTokens(null, fileName, TEST_SOLUTION + "/" + sourceName);

			assertTrue(result.contains("\"media\": \"created\""), result);
			assertEquals(":root { --rel: 3px; }",
					new String(activeProject.getEditingSolution().getMedia(fileName).getMediaData(), StandardCharsets.UTF_8));
		} finally {
			Files.deleteIfExists(source);
		}
	}

	@Test
	public void testInstall_sourcePathOutsideWorkspace_returnsError_noMediaCreated() throws Exception {
		String fileName = "tokens_outside_" + System.currentTimeMillis() + ".css";
		Path outside = Files.createTempFile("svy21615-outside-", ".css");
		try {
			Files.write(outside, ":root { --o: 1px; }".getBytes(StandardCharsets.UTF_8));

			String result = mediaServer.installDesignTokens(null, fileName, outside.toString());

			assertTrue(result.startsWith("Error: sourcePath must be inside the workspace"), result);
			assertNull(activeProject.getEditingSolution().getMedia(fileName), "no media on error");
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	public void testInstall_createSaveFailsAfterWrite_errorSaysFileWritten_retryUpdatesSameMediaOnce() throws Exception {
		String fileName = "tokens_crsave_" + System.currentTimeMillis() + ".css";
		ServoyMediaServer failing = new ServoyMediaServer() {
			@Override
			protected void saveMedia(ServoyProject owner, Media media) throws RepositoryException {
				if (fileName.equals(media.getName()))
					throw new RepositoryException("simulated save failure");
				super.saveMedia(owner, media);
			}
		};

		String result = failing.installDesignTokens(":root { --s: 1px; }", fileName, null);

		assertEquals("Error: the media file '" + fileName
				+ "' was written but saving its registration failed; run the tool again to finish it: simulated save failure",
				result);
		assertEquals(":root { --s: 1px; }", readMediaFileOnDisk(activeProject, fileName),
				"the file stays on disk, as the error says");

		String retry = mediaServer.installDesignTokens(":root { --s: 1px; }", fileName, null);
		assertTrue(retry.contains("\"media\": \"updated\""), retry);
		waitForWorkspaceBuildJobs();
		assertEquals(1, countMediaNamed(activeProject.getEditingSolution(), fileName),
				"one media in the model after the retry");
		assertEquals(1, countMediasObjEntries(activeProject, fileName), "one medias.obj entry after the retry");
	}

	@Test
	public void testInstall_createWriteFails_returnsError_nothingCreated_retryCreatesItOnce() throws Exception {
		String fileName = "tokens_crfail_" + System.currentTimeMillis() + ".css";
		ServoyMediaServer failing = new ServoyMediaServer() {
			@Override
			protected void writeNewMediaFile(ServoyProject owner, String mediaName, byte[] content)
					throws RepositoryException {
				throw new RepositoryException("simulated write failure");
			}
		};

		String result = failing.installDesignTokens(":root { --c: 1px; }", fileName, null);

		assertEquals("Error: simulated write failure", result);
		assertNull(activeProject.getEditingSolution().getMedia(fileName), "nothing may be added to the model");
		assertNull(activeProject.getEditingFlattenedSolution().getMedia(fileName),
				"not visible through the flattened solution either");
		assertFalse(Files.exists(mediaFileOnDisk(activeProject, fileName)), "no file may be left on disk");

		String retry = mediaServer.installDesignTokens(":root { --c: 1px; }", fileName, null);
		assertTrue(retry.contains("\"media\": \"created\""), retry);
		waitForWorkspaceBuildJobs();
		assertEquals(1, countMediaNamed(activeProject.getEditingSolution(), fileName),
				"one media in the model after the retry");
		assertEquals(1, countMediasObjEntries(activeProject, fileName),
				"one medias.obj entry: the resource listener and the tool must not both register it");
	}

	@Test
	public void testInstall_create_registersMediaOnceInMediasObj() throws Exception {
		String fileName = "tokens_once_" + System.currentTimeMillis() + ".css";

		String result = mediaServer.installDesignTokens(":root { --once: 1px; }", fileName, null);

		assertTrue(result.contains("\"media\": \"created\""), result);
		waitForWorkspaceBuildJobs();
		assertEquals(1, countMediaNamed(activeProject.getEditingSolution(), fileName), "one media in the model");
		assertEquals(1, countMediasObjEntries(activeProject, fileName), "one medias.obj entry");
		assertEquals(":root { --once: 1px; }", readMediaFileOnDisk(activeProject, fileName));
	}

	@Test
	public void testInstall_stylesheetIdInOtherCase_stillGuarded() throws Exception {
		String original = "body { color: red; }\n";
		Solution solution = activeProject.getEditingSolution();
		String cssName = "main_case_" + System.currentTimeMillis() + ".css";
		Media cssStylesheet = solution.createNewMedia(
				ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator(), cssName);
		cssStylesheet.setMimeType("text/css");
		cssStylesheet.setPermMediaData(original.getBytes(StandardCharsets.UTF_8));
		String previousStyleSheetID = solution.getStyleSheetID();
		solution.setStyleSheetID(cssStylesheet.getUUID().toString().toLowerCase(Locale.ROOT));
		activeProject.saveEditingSolutionNodes(new IPersist[] { cssStylesheet, solution }, true);
		try {
			String result = mediaServer.installDesignTokens(":root { --a: 1px; }", cssName, null);

			assertTrue(result.startsWith("Error: mediaFileName '" + cssName + "' is the solution stylesheet"), result);
			assertEquals(original, new String(cssStylesheet.getMediaData(), StandardCharsets.UTF_8));
		} finally {
			solution.setStyleSheetID(previousStyleSheetID);
			activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, true);
		}
	}

	@Test
	public void testInstall_updateFileWriteFails_diskRestoredToOldContent() throws Exception {
		String fileName = "tokens_diskrb_" + System.currentTimeMillis() + ".css";
		mediaServer.installDesignTokens(":root { --old: 1px; }", fileName, null);
		int[] calls = { 0 };
		ServoyMediaServer failing = new ServoyMediaServer() {
			@Override
			protected void saveMedia(ServoyProject owner, Media media) throws RepositoryException {
				super.saveMedia(owner, media);
				if (fileName.equals(media.getName()) && calls[0]++ == 0)
					throw new RepositoryException("simulated file write failure");
			}
		};

		String result = failing.installDesignTokens(":root { --new: 2px; }", fileName, null);

		assertEquals("Error: simulated file write failure", result);
		assertEquals(2, calls[0], "the restored state must be saved again");
		assertEquals(":root { --old: 1px; }", readMediaFileOnDisk(activeProject, fileName),
				"disk must be back at the old content");
	}

	@Test
	public void testInstall_missingSourcePath_returnsError_noMediaCreated() throws Exception {
		String fileName = "tokens_missing_" + System.currentTimeMillis() + ".css";
		String result = mediaServer.installDesignTokens(null, fileName, "does/not/exist_svy21615.css");

		assertTrue(result.startsWith("Error: sourcePath file not found"), result);
		assertNull(activeProject.getEditingSolution().getMedia(fileName), "no media on error");
	}

	@Test
	public void testInstall_invalidMediaName_spaces_returnsError() throws Exception {
		String result = mediaServer.installDesignTokens(":root { --a: 1px; }", "my tokens.css", null);
		assertTrue(result.startsWith("Error:"), result);
		assertTrue(result.contains("spaces"), result);
	}

	@Test
	public void testInstall_invalidMediaName_characters_returnsError() throws Exception {
		String result = mediaServer.installDesignTokens(":root { --a: 1px; }", "tokens@bad.css", null);
		assertTrue(result.startsWith("Error:"), result);
		assertTrue(result.contains("invalid"), result);
	}

	private static Path mediaFileOnDisk(ServoyProject project, String mediaName) {
		return project.getProject().getLocation().toFile().toPath().resolve("medias").resolve(mediaName);
	}

	private static String readMediaFileOnDisk(ServoyProject project, String mediaName) throws Exception {
		Path file = mediaFileOnDisk(project, mediaName);
		assertTrue(Files.isRegularFile(file), "media file must exist on disk: " + file);
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static int countMediasObjEntries(ServoyProject project, String mediaName) throws Exception {
		Path mediasObj = project.getProject().getLocation().toFile().toPath().resolve("medias.obj");
		String text = new String(Files.readAllBytes(mediasObj), StandardCharsets.UTF_8);
		String needle = "\"name\":\"" + mediaName + "\"";
		int count = 0;
		for (int i = text.replace(" ", "").indexOf(needle); i >= 0; i = text.replace(" ", "").indexOf(needle, i + 1))
			count++;
		return count;
	}

	private static int countMediaNamed(Solution solution, String fileName) {
		int count = 0;
		Iterator<Media> it = solution.getMedias(false);
		while (it.hasNext()) {
			if (fileName.equals(it.next().getName()))
				count++;
		}
		return count;
	}

	private void setupSolutionStylesheet(String cssText) throws Exception {
		Solution solution = activeProject.getEditingSolution();
		Media stylesheet = solution.getMedia(STYLESHEET);
		if (stylesheet == null) {
			IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
			stylesheet = solution.createNewMedia(validator, STYLESHEET);
			stylesheet.setMimeType("text/css");
		}
		stylesheet.setPermMediaData(cssText.getBytes(StandardCharsets.UTF_8));
		solution.setStyleSheetID(stylesheet.getUUID().toString());
		activeProject.saveEditingSolutionNodes(new IPersist[] { stylesheet, solution }, true);
	}

	private void clearSolutionStylesheet() throws Exception {
		Solution solution = activeProject.getEditingSolution();
		solution.setStyleSheetID(null);
		activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, true);
	}

	private String readStylesheetText() {
		String styleSheetID = activeProject.getEditingSolution().getStyleSheetID();
		assertNotNull(styleSheetID, "solution stylesheet id should be set for this assertion");
		FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		Media stylesheet = fs.getMedia(styleSheetID);
		assertNotNull(stylesheet, "solution stylesheet media should be resolvable");
		byte[] data = stylesheet.getMediaData();
		return data == null ? "" : new String(data, StandardCharsets.UTF_8);
	}
}

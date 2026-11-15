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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit (no OSGi / no workbench) unit tests for {@link ServoyMediaServer}'s <em>own</em>
 * logic that the companion {@link ServoyMediaServerInstallTokensTest} does not already cover:
 * the exact {@code validateMediaName} failure messages (empty / spaces / illegal char /
 * dot-segment), the {@code media_rename} guard branches reached through the private
 * {@code renameSingleMedia}/{@code renameFolderMedia} helpers (invalid new name, target already
 * exists, not-found listings, folder-prefix handling) with the Servoy model boundary stubbed via
 * Mockito, the query-string handling of an {@code @import}, and the small {@code escapeJson} helper.
 * <p>
 * The private helpers take a {@link Solution} and {@link ServoyProject} directly, so they are
 * reached with Mockito mocks and never touch {@code ServoyModelManager}'s static workbench
 * initialisation. The real media persist / model mutation ({@code saveMedia}, model writes) stays
 * in the integration test; here the mocks only drive the service's own branches and no real core
 * behaviour is asserted.
 * </p>
 */
class ServoyMediaServerTest {

	private final ServoyMediaServer server = new ServoyMediaServer();

	// ---------------------------------------------------------------------
	// validateMediaName - exact failure messages
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("validateMediaName messages")
	class ValidateMediaName {

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("blank name reports 'cannot be empty'")
		void blankIsRejected(String name) throws Exception {
			assertEquals("Error: Target name cannot be empty.", validateMediaName(name));
		}

		@Test
		@DisplayName("a space is rejected with an underscore suggestion")
		void spaceSuggestsUnderscore() throws Exception {
			assertEquals("Error: Target name 'my tokens.css' is invalid: spaces not allowed. "
					+ "Suggested: 'my_tokens.css'", validateMediaName("my tokens.css"));
		}

		@ParameterizedTest
		@ValueSource(strings = { "tokens@bad.css", "tokens#x.css", "tokens%20.css", "a=b.css" })
		@DisplayName("an illegal character is rejected with the allowed-character set")
		void illegalCharRejected(String name) throws Exception {
			String result = validateMediaName(name);
			assertNotNull(result);
			assertTrue(result.startsWith("Error: Target name '" + name + "' is invalid: only "), result);
			assertTrue(result.contains("[_a-zA-Z0-9"), result);
		}

		@ParameterizedTest
		@ValueSource(strings = { ".hidden.css", "css/.tokens.css", "folder./x.css", "a/b./c.css", "x." })
		@DisplayName("a path segment starting or ending with a dot is rejected (matches the regex, fails the dot rule)")
		void dotSegmentRejected(String name) throws Exception {
			String result = validateMediaName(name);
			assertNotNull(result, name);
			assertEquals("Error: Target name '" + name + "' is invalid: path segments cannot start or end with a dot.",
					result);
		}

		@ParameterizedTest
		@ValueSource(strings = { "tokens.css", "css/tokens.css", "a/b/c/my_tokens-v2.css", "UPPER.CSS" })
		@DisplayName("a well-formed media path passes")
		void validNamePasses(String name) throws Exception {
			assertNull(validateMediaName(name), name);
		}
	}

	// ---------------------------------------------------------------------
	// renameSingleMedia - guard branches with a mocked Solution
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("renameSingleMedia guards")
	class RenameSingleMedia {

		@Test
		@DisplayName("an invalid new name is rejected before touching the solution")
		void invalidNewNameRejected() throws Exception {
			Solution solution = mock(Solution.class);
			String result = renameSingleMedia("css/old.css", "css/new name.css", solution, project("sol"));
			assertTrue(result.startsWith("Error: Target name 'css/new name.css' is invalid: spaces not allowed"),
					result);
		}

		@Test
		@DisplayName("a blank new name is rejected")
		void blankNewNameRejected() throws Exception {
			Solution solution = mock(Solution.class);
			assertEquals("Error: Target name cannot be empty.",
					renameSingleMedia("css/old.css", "   ", solution, project("sol")));
		}

		@Test
		@DisplayName("a source media that does not exist lists the available media")
		void sourceNotFoundListsAvailable() throws Exception {
			Solution solution = mock(Solution.class);
			Iterator<Media> available = mediaIterator("css/a.css", "css/b.css");
			when(solution.getMedia("css/old.css")).thenReturn(null);
			when(solution.getMedias(true)).thenReturn(available);
			String result = renameSingleMedia("css/old.css", "css/new.css", solution, project("mysol"));
			assertTrue(result.startsWith("Error: Media 'css/old.css' not found in solution 'mysol'."), result);
			assertTrue(result.contains("css/a.css"), result);
			assertTrue(result.contains("css/b.css"), result);
		}

		@Test
		@DisplayName("a target name that already exists is rejected")
		void targetAlreadyExistsRejected() throws Exception {
			Solution solution = mock(Solution.class);
			Media old = media("css/old.css");
			Media existing = media("css/new.css");
			when(solution.getMedia("css/old.css")).thenReturn(old);
			when(solution.getMedia("css/new.css")).thenReturn(existing);
			assertEquals("Error: Media 'css/new.css' already exists at the target name.",
					renameSingleMedia("css/old.css", "css/new.css", solution, project("sol")));
		}
	}

	// ---------------------------------------------------------------------
	// renameFolderMedia - guard branches with a mocked Solution
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("renameFolderMedia guards")
	class RenameFolderMedia {

		@Test
		@DisplayName("a new folder without a trailing slash is rejected")
		void newFolderMustEndWithSlash() throws Exception {
			Solution solution = mock(Solution.class);
			assertEquals("Error: New folder name must end with '/'.",
					renameFolderMedia("images/", "images-new", solution, project("sol")));
		}

		@Test
		@DisplayName("an invalid new folder name is rejected")
		void invalidNewFolderNameRejected() throws Exception {
			Solution solution = mock(Solution.class);
			String result = renameFolderMedia("images/", "bad name/", solution, project("sol"));
			assertTrue(result.startsWith("Error: Target name 'bad name' is invalid: spaces not allowed"), result);
		}

		@Test
		@DisplayName("no media under the prefix reports the empty folder")
		void noMediaUnderPrefix() throws Exception {
			Solution solution = mock(Solution.class);
			Iterator<Media> all = mediaIterator("css/a.css", "js/b.css");
			when(solution.getMedias(false)).thenReturn(all);
			assertEquals("Error: No media found with folder prefix 'images/' in solution 'mysol'.",
					renameFolderMedia("images/", "pics/", solution, project("mysol")));
		}

		@Test
		@DisplayName("a computed target that collides with an existing media is rejected")
		void computedTargetCollisionRejected() throws Exception {
			Solution solution = mock(Solution.class);
			Media moved = media("images/icon.png");
			Media collision = media("pics/icon.png");
			when(solution.getMedias(false)).thenReturn(Collections.singletonList(moved).iterator());
			// the computed new name 'pics/icon.png' already exists as a different media
			when(solution.getMedia("pics/icon.png")).thenReturn(collision);
			assertEquals("Error: Media 'pics/icon.png' already exists at the target name.",
					renameFolderMedia("images/", "pics/", solution, project("sol")));
		}
	}

	// ---------------------------------------------------------------------
	// @import with a query string resolves to the same media (alreadyPresent)
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("an @import ref carrying a ?query still matches the same media")
	void importRefWithQueryStringMatches() throws Exception {
		Object edit = applyImport("@import (inline) \"tokens.css?v=2\";\nbody{}\n", "app.less", "tokens.css");
		assertEquals("alreadyPresent", outcome(edit));
	}

	// ---------------------------------------------------------------------
	// escapeJson
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"plain|plain",
			"with\"quote|with\\\"quote",
			"back\\slash|back\\\\slash" })
	@DisplayName("escapeJson escapes backslashes and double quotes")
	void escapeJsonEscapes(String in, String expected) throws Exception {
		assertEquals(expected, escapeJson(in));
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private String validateMediaName(String name) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("validateMediaName", String.class);
		m.setAccessible(true);
		return (String) m.invoke(server, name);
	}

	private String escapeJson(String s) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("escapeJson", String.class);
		m.setAccessible(true);
		return (String) m.invoke(server, s);
	}

	private String renameSingleMedia(String mediaName, String newName, Solution solution, ServoyProject project)
			throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("renameSingleMedia", String.class, String.class,
				Solution.class, ServoyProject.class);
		m.setAccessible(true);
		return (String) m.invoke(server, mediaName, newName, solution, project);
	}

	private String renameFolderMedia(String oldFolder, String newFolder, Solution solution, ServoyProject project)
			throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("renameFolderMedia", String.class, String.class,
				Solution.class, ServoyProject.class);
		m.setAccessible(true);
		return (String) m.invoke(server, oldFolder, newFolder, solution, project);
	}

	private Object applyImport(String stylesheet, String stylesheetName, String media) throws Exception {
		Method m = ServoyMediaServer.class.getDeclaredMethod("applyImport", String.class, String.class, String.class);
		m.setAccessible(true);
		return m.invoke(server, stylesheet, stylesheetName, media);
	}

	private static String outcome(Object edit) throws Exception {
		Method accessor = edit.getClass().getDeclaredMethod("outcome");
		accessor.setAccessible(true);
		return (String) accessor.invoke(edit);
	}

	/** A {@link ServoyProject} whose wrapped {@link IProject} reports the given name. */
	private static ServoyProject project(String name) {
		ServoyProject project = mock(ServoyProject.class);
		IProject iproject = mock(IProject.class);
		lenient().when(iproject.getName()).thenReturn(name);
		lenient().when(project.getProject()).thenReturn(iproject);
		return project;
	}

	/** A {@link Media} mock that returns the given name. */
	private static Media media(String name) {
		Media media = mock(Media.class);
		lenient().when(media.getName()).thenReturn(name);
		return media;
	}

	/** A fresh iterator of {@link Media} mocks for the given names. */
	private static Iterator<Media> mediaIterator(String... names) {
		List<Media> list = new ArrayList<>();
		for (String name : Arrays.asList(names)) {
			list.add(media(name));
		}
		return list.iterator();
	}
}

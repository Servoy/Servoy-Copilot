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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.servers.ServoyMediaServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit (no OSGi / no workbench) unit tests for the <em>model-side</em> branches of
 * {@link ServoyMediaServer}'s two tool bodies - the branches that were previously only reachable
 * from the {@code integrationpde} suite because they grab the active Servoy model as their first
 * statement. They are now reachable here through the {@code protected getServoyModel()} seam: the
 * {@link SeamServer} subclass below overrides it to return a Mockito-mocked
 * {@link IDeveloperServoyModel}, so the server's own resolution / guard / dispatch / formatting
 * logic runs without triggering {@code ServoyModelManager.<clinit>} or a live workbench.
 * <p>
 * This class is a sibling of {@link ServoyMediaServerTest} and {@link ServoyMediaServerInstallTokensTest}
 * (which it does not touch): those cover the pure helpers reached by passing a {@link Solution} /
 * {@link ServoyProject} directly ({@code validateMediaName}, {@code renameSingleMedia}/{@code
 * renameFolderMedia} guards, {@code applyImport}, {@code resolveTokensBytes}, {@code escapeJson}).
 * Here the entry point is the public tool method, so the model-boundary branches are exercised:
 * </p>
 * <ul>
 * <li><b>{@code mediaRename}</b> - the unknown-solution listing (via {@code getServoyProjects()}),
 * the null editing-solution guard, the single-file dispatch (success result formatting, the
 * not-found/already-exists guards reached through the mocked solution) and the folder dispatch
 * (trailing-slash routing, success result with {@code renamedCount}).</li>
 * <li><b>{@code installDesignTokens}</b> - the no-active-solution guard (reached before any
 * {@code ResourcesPlugin} touch) and the mediaFileName validation branches.</li>
 * </ul>
 * <p>
 * <b>What stays in {@code integrationpde}.</b> Everything from {@code mediaRename}'s
 * {@code saveEditingSolutionNodes} onward, and all of {@code installDesignTokens} past its
 * {@code ResourcesPlugin.getWorkspace()} call (the real media-file write, the medias.obj
 * registration, the stylesheet {@code @import} write) - those need a real workspace and app
 * server and are covered by {@code ServoyMediaServerIntegrationTest} /
 * {@code ServoyMediaServerInstallTokensIntegrationTest}. Here the real persist is never reached:
 * the mocked {@link ServoyProject#saveEditingSolutionNodes} does nothing, and the assertions only
 * check the server's own returned string and its calls (via {@code verify}/{@link ArgumentCaptor}).
 * </p>
 */
class ServoyMediaServerModelTest {

	// =======================================================================
	// model seam subclass - overrides getServoyModel() to return a mock so the
	// model-gated tool bodies run without a live workbench.
	// =======================================================================

	/**
	 * A {@link ServoyMediaServer} whose {@code getServoyModel()} seam returns a caller-supplied mock,
	 * so the model-side branches of {@code mediaRename} and {@code installDesignTokens} are
	 * plain-JUnit reachable.
	 */
	private static final class SeamServer extends ServoyMediaServer {
		private final IDeveloperServoyModel model;

		SeamServer(IDeveloperServoyModel model) {
			this.model = model;
		}

		@Override
		protected IDeveloperServoyModel getServoyModel() {
			return model;
		}
	}

	// ---------------------------------------------------------------------
	// mediaRename - unknown solution
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("mediaRename solution resolution")
	class SolutionResolution {

		@Test
		@DisplayName("an unknown solution lists the available solutions")
		void unknownSolutionListsAvailable() {
			ServoyProject alpha = project("alpha");
			ServoyProject beta = project("beta");
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getServoyProject("nope")).thenReturn(null);
			when(model.getServoyProjects()).thenReturn(new ServoyProject[] { alpha, beta });
			SeamServer server = new SeamServer(model);

			String result = server.mediaRename("nope", "css/old.css", "css/new.css");

			assertTrue(result.startsWith("Error: Solution 'nope' not found."), result);
			assertTrue(result.contains("alpha"), result);
			assertTrue(result.contains("beta"), result);
		}

		@Test
		@DisplayName("a resolved solution with no editing solution is rejected")
		void nullEditingSolutionRejected() {
			ServoyProject project = project("mysol");
			when(project.getEditingSolution()).thenReturn(null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getServoyProject("mysol")).thenReturn(project);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: Cannot get editing solution for 'mysol'.",
					server.mediaRename("mysol", "css/old.css", "css/new.css"));
		}
	}

	// ---------------------------------------------------------------------
	// mediaRename - single file dispatch through the public tool body
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("mediaRename single-file dispatch")
	class SingleFileDispatch {

		@Test
		@DisplayName("a successful single rename sets the new name, saves and reports renamed:true")
		void successRenamesAndSaves() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			when(project.getEditingSolution()).thenReturn(solution);
			Media media = media("css/old.css");
			when(solution.getMedia("css/old.css")).thenReturn(media);
			when(solution.getMedia("css/new.css")).thenReturn(null);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			String result = server.mediaRename("mysol", "css/old.css", "css/new.css");

			assertEquals("{ \"oldName\": \"css/old.css\", \"newName\": \"css/new.css\", \"renamed\": true }", result);
			verify(media).setName("css/new.css");
			ArgumentCaptor<IPersist[]> nodes = ArgumentCaptor.forClass(IPersist[].class);
			verify(project).saveEditingSolutionNodes(nodes.capture(), anyBoolean());
			assertEquals(1, nodes.getValue().length);
			assertEquals(media, nodes.getValue()[0]);
		}

		@Test
		@DisplayName("an unknown source media lists the available media and does not save")
		void unknownSourceListsAvailableNoSave() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			Iterator<Media> available = mediaIterator("css/a.css", "css/b.css");
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getMedia("css/old.css")).thenReturn(null);
			when(solution.getMedias(true)).thenReturn(available);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			String result = server.mediaRename("mysol", "css/old.css", "css/new.css");

			assertTrue(result.startsWith("Error: Media 'css/old.css' not found in solution 'mysol'."), result);
			assertTrue(result.contains("css/a.css"), result);
			verify(project, never()).saveEditingSolutionNodes(any(), anyBoolean());
		}

		@Test
		@DisplayName("a target that already exists is rejected and nothing is saved")
		void targetExistsRejectedNoSave() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			Media old = media("css/old.css");
			Media existing = media("css/new.css");
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getMedia("css/old.css")).thenReturn(old);
			when(solution.getMedia("css/new.css")).thenReturn(existing);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: Media 'css/new.css' already exists at the target name.",
					server.mediaRename("mysol", "css/old.css", "css/new.css"));
			verify(project, never()).saveEditingSolutionNodes(any(), anyBoolean());
		}

		@Test
		@DisplayName("an invalid new name is rejected before touching the solution")
		void invalidNewNameRejectedEarly() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			when(project.getEditingSolution()).thenReturn(solution);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			String result = server.mediaRename("mysol", "css/old.css", "css/new name.css");

			assertTrue(result.startsWith("Error: Target name 'css/new name.css' is invalid: spaces not allowed"),
					result);
			verify(solution, never()).getMedia("css/old.css");
		}
	}

	// ---------------------------------------------------------------------
	// mediaRename - folder dispatch through the public tool body
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("mediaRename folder dispatch")
	class FolderDispatch {

		@Test
		@DisplayName("a trailing-slash mediaName routes to the folder rename and reports the count")
		void folderRenameReportsCount() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			Media a = media("images/a.png");
			Media b = media("images/b.png");
			Iterator<Media> all = Arrays.asList(a, b).iterator();
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getMedias(false)).thenReturn(all);
			when(solution.getMedia("pics/a.png")).thenReturn(null);
			when(solution.getMedia("pics/b.png")).thenReturn(null);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			String result = server.mediaRename("mysol", "images/", "pics/");

			assertEquals("{ \"oldFolder\": \"images/\", \"newFolder\": \"pics/\", "
					+ "\"renamedCount\": 2, \"renamed\": true }", result);
			verify(a).setName("pics/a.png");
			verify(b).setName("pics/b.png");
			ArgumentCaptor<IPersist[]> nodes = ArgumentCaptor.forClass(IPersist[].class);
			verify(project).saveEditingSolutionNodes(nodes.capture(), anyBoolean());
			assertEquals(2, nodes.getValue().length);
		}

		@Test
		@DisplayName("a new folder without a trailing slash is rejected")
		void newFolderMustEndWithSlash() {
			ServoyProject project = project("mysol");
			when(project.getEditingSolution()).thenReturn(mock(Solution.class));
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: New folder name must end with '/'.",
					server.mediaRename("mysol", "images/", "pics"));
		}

		@Test
		@DisplayName("no media under the folder prefix reports the empty folder and does not save")
		void emptyFolderNoSave() throws Exception {
			ServoyProject project = project("mysol");
			Solution solution = mock(Solution.class);
			Iterator<Media> all = mediaIterator("css/a.css");
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getMedias(false)).thenReturn(all);
			IDeveloperServoyModel model = modelWith(project);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: No media found with folder prefix 'images/' in solution 'mysol'.",
					server.mediaRename("mysol", "images/", "pics/"));
			verify(project, never()).saveEditingSolutionNodes(any(), anyBoolean());
		}
	}

	// ---------------------------------------------------------------------
	// installDesignTokens - model guards reached before any ResourcesPlugin touch
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("installDesignTokens model guards")
	class InstallGuards {

		@Test
		@DisplayName("no active solution is rejected before anything is resolved")
		void noActiveSolutionRejected() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: No active Servoy solution. Activate a solution first.",
					server.installDesignTokens(":root{ --a: 1px; }", "tokens.css", null));
		}

		@Test
		@DisplayName("a mediaFileName that is not .css is rejected after validation passes")
		void nonCssMediaFileNameRejected() {
			ServoyProject project = project("mysol");
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			SeamServer server = new SeamServer(model);

			assertEquals("Error: mediaFileName must end in .css: tokens.txt",
					server.installDesignTokens(":root{ --a: 1px; }", "tokens.txt", null));
		}

		@Test
		@DisplayName("an invalid mediaFileName is rejected with the validation message")
		void invalidMediaFileNameRejected() {
			ServoyProject project = project("mysol");
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			SeamServer server = new SeamServer(model);

			String result = server.installDesignTokens(":root{ --a: 1px; }", "my tokens.css", null);
			assertTrue(result.startsWith("Error: Target name 'my tokens.css' is invalid: spaces not allowed"), result);
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/** A mocked model whose {@code getActiveProject()} and {@code getServoyProject(name)} return the given project. */
	private static IDeveloperServoyModel modelWith(ServoyProject project) {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		String name = project.getProject().getName();
		lenient().when(model.getServoyProject(name)).thenReturn(project);
		lenient().when(model.getActiveProject()).thenReturn(project);
		return model;
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

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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.ProjectService;

/**
 * Plain-JUnit unit tests for {@link ProjectService}'s <em>own</em> logic - the
 * workspace project listing/formatting, the recursive resource-tree walk
 * (depth/maxDepth cutoff, folders-first + case-insensitive ordering, hidden
 * "."-prefixed skipping), the project-properties formatting (natures, build
 * commands, referenced projects) and the {@code openProject} path/status
 * decision - with the Eclipse workspace boundary stubbed via Mockito.
 * <p>
 * The service obtains the workspace root through its {@code getWorkspaceRoot()}
 * seam, which this test overrides with a mock {@link IWorkspaceRoot}. The
 * Eclipse resources it hands back ({@link IProject}, {@link IFolder},
 * {@link IFile}, ...) are only stubbed to the shape our branches read; the
 * platform's real behaviour is NOT under test here (that belongs to Eclipse
 * itself). The actual workspace import/open in {@code openProject} and anything
 * that touches the real file system stays for the integration tests - only the
 * guard/branch decisions reachable without a real import are covered here.
 * </p>
 * <p>
 * Mock-type note (AGENTS.md "CRITICAL MOCK LESSON"): {@code collectResources}
 * branches on {@code resource instanceof IContainer}, so a folder is mocked as
 * {@link IFolder} (which extends {@code IContainer}) and a leaf as {@link IFile}
 * - never a bare {@code IResource} - so the real {@code instanceof} the code
 * runs resolves correctly under both the Eclipse runner and Maven.
 * </p>
 *
 * @since 2026.06
 */
class ProjectServiceTest {
	/** A {@link ProjectService} whose workspace-root seam returns the supplied mock. */
	private static ProjectService serviceWith(IWorkspaceRoot root) {
		return new ProjectService() {
			@Override
			protected IWorkspaceRoot getWorkspaceRoot() {
				return root;
			}
		};
	}

	// ---- mock builders -------------------------------------------------

	/** A mocked open project with the given name and nature ids. */
	private static IProject openProject(String name, String... natureIds) {
		IProject project = mock(IProject.class);
		lenient().when(project.getName()).thenReturn(name);
		lenient().when(project.isOpen()).thenReturn(Boolean.TRUE);
		lenient().when(project.exists()).thenReturn(Boolean.TRUE);
		try {
			IProjectDescription desc = mock(IProjectDescription.class);
			lenient().when(desc.getNatureIds()).thenReturn(natureIds);
			lenient().when(project.getDescription()).thenReturn(desc);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return project;
	}

	/** A mocked closed project with the given name. */
	private static IProject closedProject(String name) {
		IProject project = mock(IProject.class);
		lenient().when(project.getName()).thenReturn(name);
		lenient().when(project.isOpen()).thenReturn(Boolean.FALSE);
		lenient().when(project.exists()).thenReturn(Boolean.TRUE);
		return project;
	}

	/** A mocked file leaf resource (an {@link IFile}, i.e. not an {@code IContainer}). */
	private static IFile file(String name) {
		IFile file = mock(IFile.class);
		lenient().when(file.getName()).thenReturn(name);
		return file;
	}

	/** A mocked folder container ({@link IFolder} extends {@code IContainer}). */
	private static IFolder folder(String name, IResource... members) {
		IFolder f = mock(IFolder.class);
		lenient().when(f.getName()).thenReturn(name);
		lenient().when(f.exists()).thenReturn(Boolean.TRUE);
		try {
			lenient().when(f.members()).thenReturn(members);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return f;
	}

	// ====================================================================
	// listProjects
	// ====================================================================

	@Nested
	@DisplayName("listProjects")
	class ListProjects {
		@Test
		@DisplayName("empty workspace yields the no-projects message")
		void empty() {
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProjects()).thenReturn(new IProject[0]);

			String out = serviceWith(root).listProjects();

			assertEquals("No projects found in the workspace.", out);
		}

		@Test
		@DisplayName("open project with a known nature is labelled with its project type")
		void detectsKnownNature() {
			IProject alpha = openProject("Alpha", "org.eclipse.jdt.core.javanature", "org.eclipse.m2e.core.maven2Nature");
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProjects()).thenReturn(new IProject[] { alpha });

			String out = serviceWith(root).listProjects();

			assertTrue(out.contains("- **Alpha** (Open)"), out);
			assertTrue(out.contains("Project Type: Java, Maven"), out);
		}

		@Test
		@DisplayName("open project with only unknown natures lists the raw nature ids")
		void unknownNaturesListedRaw() {
			IProject beta = openProject("Beta", "com.acme.someNature");
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProjects()).thenReturn(new IProject[] { beta });

			String out = serviceWith(root).listProjects();

			assertTrue(out.contains("Other Nature IDs: com.acme.someNature"), out);
		}

		@Test
		@DisplayName("open project with no natures is labelled a generic project")
		void noNatures() {
			IProject gamma = openProject("Gamma");
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProjects()).thenReturn(new IProject[] { gamma });

			String out = serviceWith(root).listProjects();

			assertTrue(out.contains("Generic Project (no specific natures)"), out);
		}

		@Test
		@DisplayName("closed project is marked Closed and its natures are not read")
		void closedProjectNaturesSkipped() {
			IProject delta = closedProject("Delta");
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProjects()).thenReturn(new IProject[] { delta });

			String out = serviceWith(root).listProjects();

			assertTrue(out.contains("- **Delta** (Closed)"), out);
			assertFalse(out.contains("Project Type"), out);
		}
	}

	// ====================================================================
	// getProjectLayout + collectResources
	// ====================================================================

	@Nested
	@DisplayName("getProjectLayout")
	class GetProjectLayout {
		@Test
		@DisplayName("missing project yields a not-found message")
		void projectNotFound() {
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.FALSE);
			when(root.getProject("Nope")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("Nope", null, 0);

			assertEquals("Project 'Nope' not found.", out);
		}

		@Test
		@DisplayName("folders are listed before files and sorted case-insensitively")
		void foldersFirstThenFilesSorted() throws Exception {
			// members intentionally out of order: a file, then two folders
			IResource zebraFile = file("zebra.txt");
			IResource srcFolder = folder("src");
			IResource binFolder = folder("Bin");
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.members()).thenReturn(new IResource[] { zebraFile, srcFolder, binFolder });

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", null, 0);

			int binIdx = out.indexOf("Bin/");
			int srcIdx = out.indexOf("src/");
			int zebraIdx = out.indexOf("zebra.txt");
			assertTrue(binIdx >= 0 && srcIdx >= 0 && zebraIdx >= 0, out);
			// folders first (Bin before src, case-insensitive), both before the file
			assertTrue(binIdx < srcIdx, "Bin should precede src: " + out);
			assertTrue(srcIdx < zebraIdx, "folders should precede files: " + out);
		}

		@Test
		@DisplayName("hidden '.'-prefixed members are skipped")
		void hiddenMembersSkipped() throws Exception {
			IResource dotProject = file(".project");
			IResource visible = file("visible.js");
			IResource dotSettings = folder(".settings");
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.members()).thenReturn(new IResource[] { dotProject, visible, dotSettings });

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", null, 0);

			assertTrue(out.contains("visible.js"), out);
			assertFalse(out.contains(".project"), out);
			assertFalse(out.contains(".settings"), out);
		}

		@Test
		@DisplayName("maxDepth stops the walk below the given depth")
		void maxDepthCutoff() throws Exception {
			// root -> top/ -> deep.txt ; with maxDepth=1 only top/ is listed, deep.txt is cut
			IResource deep = file("deep.txt");
			IResource top = folder("top", deep);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.members()).thenReturn(new IResource[] { top });

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", null, 1);

			assertTrue(out.contains("top/"), out);
			assertFalse(out.contains("deep.txt"), "deep.txt is below maxDepth and must be cut: " + out);
		}

		@Test
		@DisplayName("maxDepth<=0 means unbounded and lists the full nested tree")
		void unboundedDepth() throws Exception {
			IResource deep = file("deep.txt");
			IResource top = folder("top", deep);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.members()).thenReturn(new IResource[] { top });

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", null, 0);

			assertTrue(out.contains("top/"), out);
			assertTrue(out.contains("deep.txt"), out);
		}

		@Test
		@DisplayName("scopePath that does not resolve yields a not-found message")
		void scopePathNotFound() throws Exception {
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.findMember("missing")).thenReturn(null);

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", "missing", 0);

			assertTrue(out.contains("Path 'missing' not found in project 'P'."), out);
		}

		@Test
		@DisplayName("scopePath scopes the walk to the resolved sub-folder and titles it")
		void scopePathScopesWalk() throws Exception {
			IResource inner = file("inner.js");
			IFolder scoped = folder("sub", inner);
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.findMember("sub")).thenReturn(scoped);

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("P")).thenReturn(project);

			String out = serviceWith(root).getProjectLayout("P", "sub", 0);

			assertTrue(out.contains("# Project Structure: P/sub"), out);
			assertTrue(out.contains("inner.js"), out);
		}
	}

	// ====================================================================
	// collectResources via reflection (private recursive helper)
	// ====================================================================

	@Nested
	@DisplayName("collectResources (private)")
	class CollectResources {
		private void collect(IResource resource, int depth, int maxDepth, StringBuilder sb) throws Exception {
			Method m = ProjectService.class.getDeclaredMethod(
				"collectResources", IResource.class, int.class, int.class, StringBuilder.class);
			m.setAccessible(true);
			m.invoke(null, resource, depth, maxDepth, sb);
		}

		@Test
		@DisplayName("a single file leaf is emitted with depth-based indentation")
		void singleFile() throws Exception {
			StringBuilder sb = new StringBuilder();
			collect(file("a.txt"), 2, Integer.MAX_VALUE, sb);
			// depth 2 -> four spaces of indent + name + newline
			assertEquals("    a.txt\n", sb.toString());
		}

		@Test
		@DisplayName("the start container (depth 0) is not itself printed, only its members")
		void startContainerHeaderSuppressedAtDepthZero() throws Exception {
			StringBuilder sb = new StringBuilder();
			collect(folder("root", file("child.txt")), 0, Integer.MAX_VALUE, sb);
			assertFalse(sb.toString().contains("root/"), sb.toString());
			assertTrue(sb.toString().contains("child.txt"), sb.toString());
		}
	}

	// ====================================================================
	// getProjectProperties
	// ====================================================================

	@Nested
	@DisplayName("getProjectProperties")
	class GetProjectProperties {
		@Test
		@DisplayName("missing project yields a not-found message")
		void notFound() {
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.FALSE);
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("X")).thenReturn(project);

			String out = serviceWith(root).getProjectProperties("X");

			assertEquals("Error: Project 'X' not found.", out);
		}

		@Test
		@DisplayName("closed project yields a closed message")
		void closed() {
			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.isOpen()).thenReturn(Boolean.FALSE);
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("X")).thenReturn(project);

			String out = serviceWith(root).getProjectProperties("X");

			assertEquals("Error: Project 'X' is closed.", out);
		}

		@Test
		@DisplayName("natures, build commands and referenced projects are all formatted")
		void formatsAllSections() throws Exception {
			IProjectDescription desc = mock(IProjectDescription.class);
			when(desc.getNatureIds()).thenReturn(new String[] { "nature.one", "nature.two" });
			ICommand build = mock(ICommand.class);
			when(build.getBuilderName()).thenReturn("com.acme.Builder");
			when(desc.getBuildSpec()).thenReturn(new ICommand[] { build });

			IProject referenced = mock(IProject.class);
			lenient().when(referenced.getName()).thenReturn("LibProject");

			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.isOpen()).thenReturn(Boolean.TRUE);
			when(project.getDescription()).thenReturn(desc);
			when(project.getReferencedProjects()).thenReturn(new IProject[] { referenced });

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("Main")).thenReturn(project);

			String out = serviceWith(root).getProjectProperties("Main");

			assertTrue(out.contains("# Project Properties: Main"), out);
			assertTrue(out.contains("## Project Natures"), out);
			assertTrue(out.contains("- nature.one"), out);
			assertTrue(out.contains("- nature.two"), out);
			assertTrue(out.contains("## Build Commands"), out);
			assertTrue(out.contains("- com.acme.Builder"), out);
			assertTrue(out.contains("## Referenced Projects"), out);
			assertTrue(out.contains("- LibProject"), out);
		}

		@Test
		@DisplayName("no referenced projects renders '(none)'")
		void noReferencedProjects() throws Exception {
			IProjectDescription desc = mock(IProjectDescription.class);
			when(desc.getNatureIds()).thenReturn(new String[0]);
			when(desc.getBuildSpec()).thenReturn(new ICommand[0]);

			IProject project = mock(IProject.class);
			when(project.exists()).thenReturn(Boolean.TRUE);
			when(project.isOpen()).thenReturn(Boolean.TRUE);
			when(project.getDescription()).thenReturn(desc);
			when(project.getReferencedProjects()).thenReturn(new IProject[0]);

			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			when(root.getProject("Main")).thenReturn(project);

			String out = serviceWith(root).getProjectProperties("Main");

			assertTrue(out.contains("## Referenced Projects"), out);
			assertTrue(out.contains("(none)"), out);
		}
	}

	// ====================================================================
	// openProject - only the path guards are unit-testable; the real
	// workspace import stays for the integration tests.
	// ====================================================================

	@Nested
	@DisplayName("openProject (path guards only)")
	class OpenProject {
		@Test
		@DisplayName("non-existent directory yields a does-not-exist error")
		void directoryMissing() {
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);
			String bogus = "Z:/definitely/not/a/real/path/" + System.nanoTime();

			String out = serviceWith(root).openProject(bogus);

			assertTrue(out.startsWith("Error: Directory does not exist:"), out);
		}

		@Test
		@DisplayName("a file path (not a directory) yields a not-a-directory error")
		void pathIsAFile() throws Exception {
			java.io.File tmp = java.io.File.createTempFile("projsvc", ".tmp");
			tmp.deleteOnExit();
			IWorkspaceRoot root = mock(IWorkspaceRoot.class);

			String out = serviceWith(root).openProject(tmp.getAbsolutePath());

			assertTrue(out.startsWith("Error: Path is not a directory:"), out);
		}
	}
}

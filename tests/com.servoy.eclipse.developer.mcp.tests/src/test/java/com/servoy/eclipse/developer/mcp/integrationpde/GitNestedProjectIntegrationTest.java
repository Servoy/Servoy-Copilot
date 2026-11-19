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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.DialogGuardBaseCopy;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * The git tools against a project that sits in a SUBFOLDER of its repository - the layout
 * Servoy-Copilot itself uses (projects under {@code bundles/} and {@code tests/}), and the one no
 * test covered.
 * <p>
 * That gap is why three defects reached {@code master}: the {@code getProject(null)} NPE Jenkins
 * caught, and the two the second review round found - read tools silently narrowing to the project,
 * and a path copied out of {@code gitStatus} being prefixed twice so {@code gitAdd} staged nothing
 * while reporting success. Every one of them needs a real {@code RepositoryMapping} with a
 * non-empty project prefix to show up, which no plain test can produce and the existing
 * {@code ServoyGitServerIntegrationTest} does not either: it inits the repository AT the project
 * folder, so the prefix there is always empty.
 * </p>
 * <p>
 * The repository root here is a scratch directory outside the workspace, and the project is created
 * with an explicit location inside it, so {@code RepositoryMapping.getRepoRelativePath} really
 * answers {@code bundles/<project>}.
 * </p>
 */
public class GitNestedProjectIntegrationTest extends DialogGuardBaseCopy
{
	private static final String PROJECT_NAME = "test_git_nested_suite";
	/** The project's path inside the repository - what every assertion below turns on. */
	private static final String PREFIX = "bundles/" + PROJECT_NAME;

	private ServoyGitServer gitServer;
	private IProject project;
	private Repository repository;
	private Path repoRoot;
	private Path projectDir;

	@BeforeEach
	public void setUp() throws Exception
	{
		TestUtilitiesClassCopy.deleteProjects(PROJECT_NAME);
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();

		// A repository root OUTSIDE the workspace, with the project nested under bundles/ - so the
		// mapping has a real prefix instead of the empty one a project-at-the-root produces.
		repoRoot = Files.createTempDirectory("svy21303-nested-repo");
		projectDir = repoRoot.resolve(PREFIX);
		Files.createDirectories(projectDir);

		try (Git git = Git.init().setDirectory(repoRoot.toFile()).call())
		{
			repository = git.getRepository();
			// A file at the repository ROOT, outside the project: the sibling that proves whether a
			// read covers the repository or only the project.
			Files.writeString(repoRoot.resolve("root-level.txt"), "root one\n");
			Files.createDirectories(repoRoot.resolve("docs"));
			Files.writeString(repoRoot.resolve("docs/notes.md"), "notes one\n");
			Files.createDirectories(projectDir.resolve("src"));
			Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo {}\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();
		}

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
			if (!p.exists())
			{
				IProjectDescription desc = ResourcesPlugin.getWorkspace().newProjectDescription(PROJECT_NAME);
				desc.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
				p.create(desc, monitor);
			}
			if (!p.isOpen()) p.open(monitor);
		}, new NullProgressMonitor());

		project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
		new ConnectProviderOperation(project, repository.getDirectory()).execute(new NullProgressMonitor());
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();

		gitServer = new ServoyGitServer(new GitService());
	}

	@AfterEach
	public void tearDown() throws Exception
	{
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();
		if (repository != null) repository.close();
		if (project != null && project.exists())
		{
			ServoyGitServerIntegrationTest.waitForPendingJobsAndUnmapToAvoidExceptions(project);
			for (int attempt = 0; attempt < 5; attempt++)
			{
				try
				{
					project.delete(true, true, new NullProgressMonitor());
					break;
				}
				catch (Exception e)
				{
					if (attempt == 4) throw e;
					Thread.sleep(500);
				}
			}
		}
		if (repoRoot != null) deleteRecursively(repoRoot.toFile());
	}

	private static void deleteRecursively(File file)
	{
		File[] children = file.listFiles();
		if (children != null) for (File child : children) deleteRecursively(child);
		file.delete();
	}

	/** The whole point of this class: the mapping must report a non-empty prefix. */
	@Test
	@DisplayName("the project really is nested - a non-empty repository prefix")
	public void theProjectIsActuallyNested()
	{
		RepositoryMapping mapping = RepositoryMapping.getMapping(project);
		assertEquals(PREFIX, mapping.getRepoRelativePath(project),
			"Without a non-empty prefix this class tests nothing the existing one does not");
	}

	// --- item 2: a path copied out of gitStatus must stage the file it names -----------------

	@Test
	@DisplayName("a path copied from gitStatus stages that file, and is not prefixed twice")
	public void repositoryRelativePathFromStatusStages() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int x; }\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		String status = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(status.contains(PREFIX + "/src/Foo.java"),
			"gitStatus prints repository-relative paths, which is the premise of this test: " + status);

		// Exactly what a caller would paste out of that output.
		String added = gitServer.gitAdd(PROJECT_NAME, null, PREFIX + "/src/Foo.java");

		String afterStatus = gitServer.gitStatus(PROJECT_NAME, null);
		assertAll(
			() -> assertTrue(added.contains("Added"), "Should report the staging: " + added),
			() -> assertTrue(afterStatus.contains("Changes to be committed"),
				"The file must actually be STAGED, not merely reported as added: " + afterStatus),
			() -> assertTrue(afterStatus.contains(PREFIX + "/src/Foo.java"),
				"and it must be the file that was named: " + afterStatus));
	}

	@Test
	@DisplayName("the project-relative form stages the same file")
	public void projectRelativePathStages() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int y; }\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		gitServer.gitAdd(PROJECT_NAME, null, "src/Foo.java");

		String status = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(status.contains("Changes to be committed") && status.contains(PREFIX + "/src/Foo.java"),
			"A project-relative path must stage the same file: " + status);
	}

	@Test
	@DisplayName("gitReset accepts the repository-relative form too")
	public void resetAcceptsRepositoryRelativeForm() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int z; }\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
		gitServer.gitAdd(PROJECT_NAME, null, "src/Foo.java");
		assertTrue(gitServer.gitStatus(PROJECT_NAME, null).contains("Changes to be committed"), "staged first");

		gitServer.gitReset(PROJECT_NAME, null, PREFIX + "/src/Foo.java");

		String status = gitServer.gitStatus(PROJECT_NAME, null);
		assertFalse(status.contains("Changes to be committed"),
			"The file must be unstaged by a path copied from gitStatus: " + status);
	}

	// --- item 1: a read tool with no filter covers the whole repository ----------------------

	@Test
	@DisplayName("gitDiff with no pathFilter covers the whole repository, not just the project")
	public void diffWithoutFilterCoversTheRepository() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int a; }\n");
		Files.writeString(repoRoot.resolve("root-level.txt"), "root two\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		String diff = gitServer.gitDiff(PROJECT_NAME, null, null, null, null, null, null);

		assertAll(
			() -> assertTrue(diff.contains("src/Foo.java"), "The project's change must appear: " + diff),
			() -> assertTrue(diff.contains("root-level.txt"),
				"A repository-root change must appear too - omitting the filter means the whole repository"));
	}

	@Test
	@DisplayName("gitDiff with '.' narrows to the project, so the caller can still ask for that")
	public void diffWithDotNarrowsToTheProject() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int b; }\n");
		Files.writeString(repoRoot.resolve("root-level.txt"), "root three\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		String diff = gitServer.gitDiff(PROJECT_NAME, null, null, ".", null, null, null);

		assertAll(
			() -> assertTrue(diff.contains("src/Foo.java"), "The project's change must appear: " + diff),
			() -> assertFalse(diff.contains("root-level.txt"),
				"'.' means the project, so a repository-root change must NOT appear"));
	}

	@Test
	@DisplayName("gitLog with no pathFilter covers the whole repository")
	public void logWithoutFilterCoversTheRepository() throws Exception
	{
		// A commit that touches ONLY a repository-root file, nothing in the project.
		Files.writeString(repoRoot.resolve("docs/notes.md"), "notes two\n");
		try (Git git = new Git(repository))
		{
			git.add().addFilepattern("docs/notes.md").call();
			git.commit().setMessage("a commit outside the project").call();
		}

		String all = gitServer.gitLog(PROJECT_NAME, null, "10", null, null);
		String narrowed = gitServer.gitLog(PROJECT_NAME, null, "10", null, ".");

		assertAll(
			() -> assertTrue(all.contains("a commit outside the project"),
				"No filter must list a commit outside the project: " + all),
			() -> assertFalse(narrowed.contains("a commit outside the project"),
				"'.' must narrow the history to the project: " + narrowed));
	}

	@Test
	@DisplayName("gitShow with no pathFilter shows the commit's whole diff")
	public void showWithoutFilterCoversTheRepository() throws Exception
	{
		Files.writeString(repoRoot.resolve("root-level.txt"), "root four\n");
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int c; }\n");
		try (Git git = new Git(repository))
		{
			git.add().addFilepattern(".").call();
			git.commit().setMessage("a commit touching both").call();
		}

		String shown = gitServer.gitShow(PROJECT_NAME, null, "HEAD", null);

		assertAll(
			() -> assertTrue(shown.contains("src/Foo.java"), "The project's part must appear: " + shown),
			() -> assertTrue(shown.contains("root-level.txt"),
				"The repository-root part must appear too when no filter was given"));
	}

	// --- round 3: an out-of-project repository path is refused, not silently prefixed --------

	/**
	 * The round-3 HIGH finding. {@code prefixOnce} turned {@code docs/spec.md} into
	 * {@code bundles/<project>/docs/spec.md} - a path that does not exist - and JGit's
	 * {@code AddCommand} does not fail on a pathspec that matches nothing, so the tool answered
	 * {@code "Added: docs/spec.md"} with an empty index. Round 2's new tool text had promised that
	 * could not happen, which made it a trap rather than a gap.
	 */
	@Test
	@DisplayName("a repository path OUTSIDE the project is refused, naming the project")
	public void outOfProjectPathIsRefused() throws Exception
	{
		Files.writeString(repoRoot.resolve("docs/notes.md"), "notes changed\n");

		IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
			() -> gitServer.gitAdd(PROJECT_NAME, null, "docs/notes.md"));

		assertAll(
			() -> assertTrue(problem.getMessage().contains("docs/notes.md"),
				"Should name the path: " + problem.getMessage()),
			() -> assertTrue(problem.getMessage().contains(PREFIX),
				"and the project it is outside of: " + problem.getMessage()),
			() -> assertTrue(problem.getMessage().contains("repo"),
				"and point at the repo-centric call: " + problem.getMessage()));
	}

	@Test
	@DisplayName("a sibling project's path is refused the same way")
	public void siblingProjectPathIsRefused() throws Exception
	{
		Files.createDirectories(repoRoot.resolve("tests/other.tests"));
		Files.writeString(repoRoot.resolve("tests/other.tests/t.txt"), "sibling\n");

		assertThrows(IllegalArgumentException.class,
			() -> gitServer.gitAdd(PROJECT_NAME, null, "tests/other.tests/t.txt"),
			"A sibling project's file is outside this project and must be refused");
	}

	@Test
	@DisplayName("gitReset refuses an out-of-project path too")
	public void outOfProjectPathIsRefusedByReset() throws Exception
	{
		assertThrows(IllegalArgumentException.class,
			() -> gitServer.gitReset(PROJECT_NAME, null, "docs/notes.md"));
	}

	/**
	 * The READ tools need the same refusal, and a wrong answer hurts more here: a write that stages
	 * nothing is at least contradicted by the next {@code gitStatus}, while a diff that says "no
	 * changes" about a file that HAS changed is simply believed. {@code bundles/<project>/docs/x.md}
	 * matched nothing, and a pathspec matching nothing is not an error - it is an empty diff.
	 */
	@Test
	@DisplayName("gitDiff refuses an out-of-project path rather than answering 'no changes'")
	public void outOfProjectPathIsRefusedByDiff() throws Exception
	{
		Files.writeString(repoRoot.resolve("docs/notes.md"), "notes changed\n");

		IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
			() -> gitServer.gitDiff(PROJECT_NAME, null, null, "docs/notes.md", null, null, null));

		assertAll(
			() -> assertTrue(problem.getMessage().contains("docs/notes.md"),
				"Should name the path: " + problem.getMessage()),
			() -> assertTrue(problem.getMessage().contains(PREFIX),
				"and the project it is outside of: " + problem.getMessage()));
	}

	@Test
	@DisplayName("gitLog and gitShow refuse one too")
	public void outOfProjectPathIsRefusedByLogAndShow()
	{
		assertAll(
			() -> assertThrows(IllegalArgumentException.class,
				() -> gitServer.gitLog(PROJECT_NAME, null, null, null, "docs/notes.md")),
			() -> assertThrows(IllegalArgumentException.class,
				() -> gitServer.gitShow(PROJECT_NAME, null, null, "docs/notes.md")));
	}

	/** A project-relative read path still works - the refusal must not break the ordinary case. */
	@Test
	@DisplayName("gitDiff on a project-relative path still reports the change")
	public void projectRelativeDiffStillWorks() throws Exception
	{
		Files.writeString(projectDir.resolve("src/Foo.java"), "class Foo { int y; }\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		String diff = gitServer.gitDiff(PROJECT_NAME, null, null, "src/Foo.java", null, null, null);

		assertTrue(diff.contains("Foo.java"), "The project's own file must still diff: " + diff);
	}

	/**
	 * The refusal must not swallow a path that merely looks out-of-project: when the project itself
	 * has a {@code docs/} folder, the project-relative reading exists and wins.
	 */
	@Test
	@DisplayName("a project-relative path that shares a name with a repo-root folder still works")
	public void projectRelativeWinsWhenItExists() throws Exception
	{
		Files.createDirectories(projectDir.resolve("docs"));
		Files.writeString(projectDir.resolve("docs/inner.md"), "inner\n");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		String added = gitServer.gitAdd(PROJECT_NAME, null, "docs/inner.md");

		String status = gitServer.gitStatus(PROJECT_NAME, null);
		assertAll(
			() -> assertTrue(added.startsWith("Added"), "Should stage it: " + added),
			() -> assertTrue(status.contains(PREFIX + "/docs/inner.md"),
				"The project's own docs/ must win over the repository root's: " + status));
	}

	/** A staging call that matches nothing must say so, not report success. */
	@Test
	@DisplayName("a path that matches nothing reports that nothing was staged")
	public void nothingMatchedIsReported()
	{
		String result = gitServer.gitAdd(PROJECT_NAME, null, "src/DoesNotExist.java");

		assertAll(
			() -> assertTrue(result.startsWith("Nothing to add"),
				"Must not claim success for an empty index: " + result),
			() -> assertTrue(result.contains("src/DoesNotExist.java"),
				"and must name what was tried: " + result));
	}

	/** The documented way to stage a repo-root file: target the repository, no projectName. */
	@Test
	@DisplayName("the repo-centric call is the way to stage a repo-root docs/ file")
	public void repoCentricStagesRepoRootFile() throws Exception
	{
		Files.writeString(repoRoot.resolve("docs/notes.md"), "notes changed\n");
		String repoName = repoRoot.getFileName().toString();

		String added = gitServer.gitAdd(null, repoName, "docs/notes.md");

		String status = gitServer.gitStatus(null, repoName);
		assertAll(
			() -> assertTrue(added.startsWith("Added"), "Should stage it: " + added),
			() -> assertTrue(status.contains("docs/notes.md"),
				"The repo-root file must be staged by the repo-centric call: " + status));
	}

	// --- the repo-centric path, which has no project to prefix from --------------------------

	@Test
	@DisplayName("targeting by repo stages a repository-relative path without a projectName")
	public void repoCentricStaging() throws Exception
	{
		Files.writeString(repoRoot.resolve("root-level.txt"), "root five\n");
		String repoName = repoRoot.getFileName().toString();

		gitServer.gitAdd(null, repoName, "root-level.txt");

		String status = gitServer.gitStatus(null, repoName);
		assertTrue(status.contains("Changes to be committed") && status.contains("root-level.txt"),
			"A repo-targeted call takes repository-relative paths as given: " + status);
	}

	/** Reads the file as the tools would see it, so a test failure shows real content. */
	@SuppressWarnings("unused")
	private String read(Path path) throws Exception
	{
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}
}

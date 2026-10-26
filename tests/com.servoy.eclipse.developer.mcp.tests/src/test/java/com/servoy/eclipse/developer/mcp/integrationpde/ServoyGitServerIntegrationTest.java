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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.DialogGuardBaseCopy;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.services.GitService;

public class ServoyGitServerIntegrationTest extends DialogGuardBaseCopy {

	private static final String PROJECT_NAME = "test_git_server_suite";

	private ServoyGitServer gitServer;
	private IProject project;
	private Repository repository;

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		TestUtilitiesClassCopy.deleteProjects(PROJECT_NAME);
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
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
		assertNotNull(project);
		assertTrue(project.isOpen());

		File projectDir = project.getLocation().toFile();
		Git git = Git.init().setDirectory(projectDir).call();
		repository = git.getRepository();
		git.close();

		ConnectProviderOperation connectOp = new ConnectProviderOperation(project, repository.getDirectory());
		connectOp.execute(new NullProgressMonitor());

		createAndCommitFile("initial.txt", "initial content", "Initial commit");

		gitServer = new ServoyGitServer(new GitService());
	}

	@AfterEach
	public void tearDown() throws Exception {
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();

		if (repository != null) {
			repository.close();
		}
		if (project != null && project.exists()) {
			// Let whatever EGit/resource-change jobs the unmap or earlier operations
			// queued actually finish before the project disappears underneath them.
			waitForPendingJobsAndUnmapToAvoidExceptions(project);

			for (int attempt = 0; attempt < 5; attempt++) {
				try {
					project.delete(true, true, new NullProgressMonitor());
					break;
				} catch (Exception e) {
					if (attempt == 4) {
						throw e;
					}
					Thread.sleep(500);
				}
			}
		}
	}

	/**
	 * Unmaps the Team provider and waits for the workspace's build and change-notification
	 * jobs to drain. Teardown that deletes a project out from under a still-running job
	 * (EGit's index diff cache, PDE's classpath container updater, etc.) turns that job's
	 * next step into a spurious failure logged against a project that, by then, no longer exists.
	 * @param projectToUnmap
	 */
	public static void waitForPendingJobsAndUnmapToAvoidExceptions(IProject projectToUnmap) throws InterruptedException {
		// Disconnect the Team provider first so EGit drops its GitProjectData for this
		// project synchronously, on this thread. Without this, EGit's resource-change
		// listener (IndexDiffCacheEntry) can still be reacting - asynchronously, on a
		// job - to the delete below when it goes looking for GitProjectData.properties
		// that the delete already removed, logging a NoSuchFileException that has
		// nothing to do with this test and everything to do with its teardown racing
		// EGit's own bookkeeping.
		try {
			if (projectToUnmap != null) {
				org.eclipse.team.core.RepositoryProvider.unmap(projectToUnmap);
			}
		} catch (Exception e) {
			// Best effort - the delete below is still attempted either way.
		}

		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();
	}

	@Test
	public void testGitStatus_cleanWorkingTree() {
		String result = gitServer.gitStatus(PROJECT_NAME, null);

		assertNotNull(result);
		assertTrue(result.contains("On branch"));
		assertTrue(result.contains("nothing to commit, working tree clean"));
	}

	@Test
	public void testGitStatus_withUntrackedFile() {
		createTestFile("untracked.txt", "untracked");

		String result = gitServer.gitStatus(PROJECT_NAME, null);

		assertTrue(result.contains("Untracked files"));
		assertTrue(result.contains("untracked.txt"));
	}

	@Test
	public void testGitStatus_withModifiedFile() {
		createTestFile("initial.txt", "modified content");

		String result = gitServer.gitStatus(PROJECT_NAME, null);

		assertTrue(result.contains("Changes not staged for commit"));
		assertTrue(result.contains("initial.txt"));
	}

	@Test
	public void testGitAdd_andCommit_andLog() {
		createTestFile("newfile.txt", "new content");

		String addResult = gitServer.gitAdd(PROJECT_NAME, null, "newfile.txt");
		assertNotNull(addResult);

		String statusAfterAdd = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusAfterAdd.contains("Changes to be committed"));
		assertTrue(statusAfterAdd.contains("newfile.txt"));

		String commitResult = gitServer.gitCommit(PROJECT_NAME, null, "Add newfile");
		assertTrue(commitResult.contains("Committed"));
		assertTrue(commitResult.contains("Add newfile"));

		String logResult = gitServer.gitLog(PROJECT_NAME, null, "5");
		assertTrue(logResult.contains("Add newfile"));
		assertTrue(logResult.contains("Initial commit"));
	}

	@Test
	public void testGitCommit_nothingStaged_isRefusedAndWritesNoCommit() throws Exception {
		// Working tree is clean after setUp()'s initial commit - nothing staged or modified.
		String statusBefore = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusBefore.contains("nothing to commit, working tree clean"));

		String headBefore = resolveHead();
		assertNotNull(headBefore);

		String commitResult = gitServer.gitCommit(PROJECT_NAME, null, "empty commit attempt");

		// The tool must refuse rather than report a committed sha (SVY-21524).
		assertNotNull(commitResult);
		assertFalse(commitResult.contains("Committed"),
				"gitCommit with nothing staged must not report a committed change, but was: " + commitResult);
		assertTrue(commitResult.contains("Nothing to commit"),
				"gitCommit with nothing staged should return a clear refusal, but was: " + commitResult);

		// And, crucially, HEAD must not have moved - no empty commit in history.
		String headAfter = resolveHead();
		assertEquals(headBefore, headAfter,
				"HEAD moved: an empty commit was written to history even though nothing was staged");
	}

	@Test
	public void testGitListRepositories_listsTheTestRepo() {
		// The test repo's working tree is the project folder, so its repo name is the folder name.
		String result = gitServer.gitListRepositories();

		assertNotNull(result);
		assertTrue(result.contains("Git repositories"),
				"gitListRepositories should list repositories, but was: " + result);
		assertTrue(result.contains(PROJECT_NAME),
				"gitListRepositories should include the test repo '" + PROJECT_NAME + "', but was: " + result);
	}

	@Test
	public void testRepoCentric_statusAndCommit_byRepoName() {
		// Target the repository by name (its working-tree folder name) with no projectName at all.
		String status = gitServer.gitStatus(null, PROJECT_NAME);
		assertNotNull(status);
		assertTrue(status.contains("On branch"),
				"repo-centric gitStatus should work with only 'repo', but was: " + status);

		createTestFile("repo_centric.txt", "content");
		gitServer.gitAdd(null, PROJECT_NAME, "repo_centric.txt");

		String commitResult = gitServer.gitCommit(null, PROJECT_NAME, "repo-centric commit");
		assertTrue(commitResult.contains("Committed"),
				"repo-centric gitCommit should commit, but was: " + commitResult);

		String logResult = gitServer.gitLog(null, PROJECT_NAME, "5");
		assertTrue(logResult.contains("repo-centric commit"));
	}

	@Test
	public void testRepoCentric_unknownRepo_isRefusedWithRepoList() {
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> gitServer.gitStatus(null, "no_such_repo_xyz"));

		String msg = ex.getMessage();
		assertNotNull(msg);
		assertTrue(msg.contains("No repository matches"),
				"an unknown repo should be refused, but was: " + msg);
		assertTrue(msg.contains(PROJECT_NAME),
				"the refusal should list the available repositories, but was: " + msg);
	}

	@Test
	public void testRepoCentric_projectNameAsRepo_resolvesViaMapping() {
		// SVY-21366: an agent (typically an LLM) that passes the Servoy SOLUTION / Eclipse PROJECT
		// name as 'repo' - rather than the repository's own working-tree folder name - must still
		// resolve, by falling back to the project's RepositoryMapping. This mirrors an empty-source
		// skilltest solution that createSolution connected to the workspace-root repo (whose repo
		// name is the workspace folder, not the solution): the agent only knows the solution name.
		// Here the mapped repo's name happens to equal PROJECT_NAME, but the point is that resolution
		// goes through the project mapping, not a repo-name match.
		String status = gitServer.gitStatus(null, PROJECT_NAME);
		assertNotNull(status);
		assertTrue(status.contains("On branch"),
				"passing the project name as 'repo' should resolve via the project mapping, but was: " + status);
	}

	@Test
	public void testGitDiff_unstaged() {
		createTestFile("initial.txt", "modified line");

		String result = gitServer.gitDiff(PROJECT_NAME, null, null);

		assertNotNull(result);
		assertTrue(result.contains("modified line"));
	}

	@Test
	public void testGitDiff_staged() {
		createTestFile("initial.txt", "staged change");
		gitServer.gitAdd(PROJECT_NAME, null, "initial.txt");

		String result = gitServer.gitDiff(PROJECT_NAME, null, "true");

		assertNotNull(result);
		assertTrue(result.contains("staged change"));
	}

	@Test
	public void testGitBranch_listsBranches() {
		String result = gitServer.gitBranch(PROJECT_NAME, null, null);

		assertNotNull(result);
		assertTrue(result.contains("master") || result.contains("main"));
	}

	@Test
	public void testGitCreateBranch_andCheckout_andDelete() {
		String createResult = gitServer.gitCreateBranch(PROJECT_NAME, null, "feature-test", null);
		assertNotNull(createResult);

		String branchList = gitServer.gitBranch(PROJECT_NAME, null, null);
		assertTrue(branchList.contains("feature-test"));

		String checkoutResult = gitServer.gitCheckout(PROJECT_NAME, null, "feature-test");
		assertNotNull(checkoutResult);

		String statusOnBranch = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusOnBranch.contains("feature-test"));

		gitServer.gitCheckout(PROJECT_NAME, null, "master");

		String deleteResult = gitServer.gitDeleteBranch(PROJECT_NAME, null, "feature-test", null);
		assertNotNull(deleteResult);

		String branchListAfter = gitServer.gitBranch(PROJECT_NAME, null, null);
		assertFalse(branchListAfter.contains("feature-test"));
	}

	@Test
	public void testGitStash_andStashList_andStashPop() {
		createTestFile("initial.txt", "stash me");

		String stashResult = gitServer.gitStash(PROJECT_NAME, null, "test stash");
		assertNotNull(stashResult);

		String statusAfterStash = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusAfterStash.contains("nothing to commit, working tree clean"));

		String stashListResult = gitServer.gitStashList(PROJECT_NAME, null);
		assertNotNull(stashListResult);

		String popResult = gitServer.gitStashPop(PROJECT_NAME, null);
		assertNotNull(popResult);

		String statusAfterPop = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusAfterPop.contains("initial.txt"));
	}

	@Test
	public void testGitReset_unstagesFile() {
		createTestFile("reset_test.txt", "to be reset");
		gitServer.gitAdd(PROJECT_NAME, null, "reset_test.txt");

		String statusBefore = gitServer.gitStatus(PROJECT_NAME, null);
		assertTrue(statusBefore.contains("Changes to be committed"));

		String resetResult = gitServer.gitReset(PROJECT_NAME, null, "reset_test.txt");
		assertNotNull(resetResult);

		String statusAfter = gitServer.gitStatus(PROJECT_NAME, null);
		assertFalse(statusAfter.contains("Changes to be committed") && statusAfter.contains("reset_test.txt")
				&& !statusAfter.contains("Untracked files"));
	}

	@Test
	public void testGitStagePatch_stagesPartialChange() {
		createTestFile("patch_file.txt", "line1\nmodified\nline3\n");
		gitServer.gitAdd(PROJECT_NAME, null, ".");
		gitServer.gitCommit(PROJECT_NAME, null, "Add patch_file");

		createTestFile("patch_file.txt", "line1\nchanged_line2\nline3\nnew_line4\n");

		String patch = "--- a/patch_file.txt\n+++ b/patch_file.txt\n@@ -1,3 +1,4 @@\n line1\n-modified\n+changed_line2\n line3\n+new_line4\n";

		String result = gitServer.gitStagePatch(PROJECT_NAME, null, patch);
		assertNotNull(result);

		String stagedDiff = gitServer.gitDiff(PROJECT_NAME, null, "true");
		assertNotNull(stagedDiff);
		assertTrue(stagedDiff.contains("changed_line2") || stagedDiff.contains("new_line4"));
	}

	private String resolveHead() {
		try {
			ObjectId head = repository.resolve("HEAD");
			return head == null ? null : head.getName();
		} catch (Exception e) {
			throw new RuntimeException("Failed to resolve HEAD", e);
		}
	}

	private void createTestFile(String path, String content) {
		try {
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				if (file.exists()) {
					file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, false,
							monitor);
				} else {
					file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
				}
			}, new NullProgressMonitor());
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create test file: " + path, e);
		}
		TestUtilitiesClassCopy.waitForWorkspaceBuildJobs();
	}

	private void createAndCommitFile(String path, String content, String message) {
		createTestFile(path, content);
		try (Git git = new Git(repository)) {
			git.add().addFilepattern(".").call();
			git.commit().setMessage(message).call();
		} catch (Exception e) {
			throw new RuntimeException("Failed to commit initial file", e);
		}
	}
}

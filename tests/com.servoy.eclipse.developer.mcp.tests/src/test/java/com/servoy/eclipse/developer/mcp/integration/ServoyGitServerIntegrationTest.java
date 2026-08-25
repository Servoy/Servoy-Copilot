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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.services.GitService;

public class ServoyGitServerIntegrationTest {
	private static final String PROJECT_NAME = "test_git_server_suite";

	private ServoyGitServer gitServer;
	private IProject project;
	private Repository repository;

	@Before
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

	@After
	public void tearDown() throws Exception {
		TestUtilitiesClass.waitForWorkspaceBuildJobs();
		
		if (repository != null) {
			repository.close();
		}
		if (project != null && project.exists()) {
			project.delete(true, true, new NullProgressMonitor());
		}
	}

	@Test
	public void testGitStatus_cleanWorkingTree() {
		String result = gitServer.gitStatus(PROJECT_NAME);

		assertNotNull(result);
		assertTrue(result.contains("On branch"));
		assertTrue(result.contains("nothing to commit, working tree clean"));
	}

	@Test
	public void testGitStatus_withUntrackedFile() {
		createTestFile("untracked.txt", "untracked");

		String result = gitServer.gitStatus(PROJECT_NAME);

		assertTrue(result.contains("Untracked files"));
		assertTrue(result.contains("untracked.txt"));
	}

	@Test
	public void testGitStatus_withModifiedFile() {
		createTestFile("initial.txt", "modified content");

		String result = gitServer.gitStatus(PROJECT_NAME);

		assertTrue(result.contains("Changes not staged for commit"));
		assertTrue(result.contains("initial.txt"));
	}

	@Test
	public void testGitAdd_andCommit_andLog() {
		createTestFile("newfile.txt", "new content");

		String addResult = gitServer.gitAdd(PROJECT_NAME, "newfile.txt");
		assertNotNull(addResult);

		String statusAfterAdd = gitServer.gitStatus(PROJECT_NAME);
		assertTrue(statusAfterAdd.contains("Changes to be committed"));
		assertTrue(statusAfterAdd.contains("newfile.txt"));

		String commitResult = gitServer.gitCommit(PROJECT_NAME, "Add newfile");
		assertTrue(commitResult.contains("Committed"));
		assertTrue(commitResult.contains("Add newfile"));

		String logResult = gitServer.gitLog(PROJECT_NAME, "5");
		assertTrue(logResult.contains("Add newfile"));
		assertTrue(logResult.contains("Initial commit"));
	}

	@Test
	public void testGitDiff_unstaged() {
		createTestFile("initial.txt", "modified line");

		String result = gitServer.gitDiff(PROJECT_NAME, null);

		assertNotNull(result);
		assertTrue(result.contains("modified line"));
	}

	@Test
	public void testGitDiff_staged() {
		createTestFile("initial.txt", "staged change");
		gitServer.gitAdd(PROJECT_NAME, "initial.txt");

		String result = gitServer.gitDiff(PROJECT_NAME, "true");

		assertNotNull(result);
		assertTrue(result.contains("staged change"));
	}

	@Test
	public void testGitBranch_listsBranches() {
		String result = gitServer.gitBranch(PROJECT_NAME, null);

		assertNotNull(result);
		assertTrue(result.contains("master") || result.contains("main"));
	}

	@Test
	public void testGitCreateBranch_andCheckout_andDelete() {
		String createResult = gitServer.gitCreateBranch(PROJECT_NAME, "feature-test", null);
		assertNotNull(createResult);

		String branchList = gitServer.gitBranch(PROJECT_NAME, null);
		assertTrue(branchList.contains("feature-test"));

		String checkoutResult = gitServer.gitCheckout(PROJECT_NAME, "feature-test");
		assertNotNull(checkoutResult);

		String statusOnBranch = gitServer.gitStatus(PROJECT_NAME);
		assertTrue(statusOnBranch.contains("feature-test"));

		gitServer.gitCheckout(PROJECT_NAME, "master");

		String deleteResult = gitServer.gitDeleteBranch(PROJECT_NAME, "feature-test", null);
		assertNotNull(deleteResult);

		String branchListAfter = gitServer.gitBranch(PROJECT_NAME, null);
		assertFalse(branchListAfter.contains("feature-test"));
	}

	@Test
	public void testGitStash_andStashList_andStashPop() {
		createTestFile("initial.txt", "stash me");

		String stashResult = gitServer.gitStash(PROJECT_NAME, "test stash");
		assertNotNull(stashResult);

		String statusAfterStash = gitServer.gitStatus(PROJECT_NAME);
		assertTrue(statusAfterStash.contains("nothing to commit, working tree clean"));

		String stashListResult = gitServer.gitStashList(PROJECT_NAME);
		assertNotNull(stashListResult);

		String popResult = gitServer.gitStashPop(PROJECT_NAME);
		assertNotNull(popResult);

		String statusAfterPop = gitServer.gitStatus(PROJECT_NAME);
		assertTrue(statusAfterPop.contains("initial.txt"));
	}

	@Test
	public void testGitReset_unstagesFile() {
		createTestFile("reset_test.txt", "to be reset");
		gitServer.gitAdd(PROJECT_NAME, "reset_test.txt");

		String statusBefore = gitServer.gitStatus(PROJECT_NAME);
		assertTrue(statusBefore.contains("Changes to be committed"));

		String resetResult = gitServer.gitReset(PROJECT_NAME, "reset_test.txt");
		assertNotNull(resetResult);

		String statusAfter = gitServer.gitStatus(PROJECT_NAME);
		assertFalse(statusAfter.contains("Changes to be committed") && statusAfter.contains("reset_test.txt")
				&& !statusAfter.contains("Untracked files"));
	}

	@Test
	public void testGitStagePatch_stagesPartialChange() {
		createTestFile("patch_file.txt", "line1\nmodified\nline3\n");
		gitServer.gitAdd(PROJECT_NAME, ".");
		gitServer.gitCommit(PROJECT_NAME, "Add patch_file");

		createTestFile("patch_file.txt", "line1\nchanged_line2\nline3\nnew_line4\n");

		String patch = "--- a/patch_file.txt\n+++ b/patch_file.txt\n@@ -1,3 +1,4 @@\n line1\n-modified\n+changed_line2\n line3\n+new_line4\n";

		String result = gitServer.gitStagePatch(PROJECT_NAME, patch);
		assertNotNull(result);

		String stagedDiff = gitServer.gitDiff(PROJECT_NAME, "true");
		assertNotNull(stagedDiff);
		assertTrue(stagedDiff.contains("changed_line2") || stagedDiff.contains("new_line4"));
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
		TestUtilitiesClass.waitForWorkspaceBuildJobs();
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

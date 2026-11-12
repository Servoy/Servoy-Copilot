package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * Tests the history-rewriting git tools ported from AssistAI's {@code aca70c0} under SVY-21303 -
 * {@code gitMerge}, {@code gitRebase}, {@code gitCherryPick} and {@code gitRevert} - driven through
 * the extracted {@code GitService} cores that take an already-resolved {@link Repository}.
 * <p>
 * The counterpart of upstream's {@code GitServiceBranchingPDETest}. These operations leave the
 * repository in a state the caller has to act on - conflict markers in the working tree, a paused
 * rebase, a staged squash - so every test asserts BOTH the reported text and the resulting
 * repository state. The rebase state machine (BEGIN / CONTINUE / SKIP / ABORT) is the reason this
 * class exists: it cannot be trusted on compile-confidence.
 * </p>
 */
public class GitServiceBranchingTest
{
	@TempDir
	Path tempDir;

	private Git git;
	private Repository repository;

	@BeforeEach
	void setUp() throws Exception
	{
		git = Git.init().setDirectory(tempDir.toFile()).call();
		repository = git.getRepository();
	}

	@AfterEach
	void tearDown()
	{
		if (git != null)
		{
			git.close();
		}
	}

	private RevCommit commitFile(String name, String content, String message) throws Exception
	{
		Files.writeString(tempDir.resolve(name), content);
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(message).call();
	}

	private String headSha() throws Exception
	{
		ObjectId head = repository.resolve(Constants.HEAD);
		return head == null ? null : head.getName();
	}

	/**
	 * Commits a base file, then branches off {@code other} and changes the SAME line on both sides,
	 * so merging or replaying one onto the other conflicts. Leaves {@code master} checked out.
	 */
	private void createDivergingBranches(String other) throws Exception
	{
		commitFile("shared.txt", "base\n", "base commit");
		String main = repository.getBranch();

		git.checkout().setCreateBranch(true).setName(other).call();
		commitFile("shared.txt", "from " + other + "\n", "change on " + other);

		git.checkout().setName(main).call();
		commitFile("shared.txt", "from " + main + "\n", "change on " + main);
	}

	/** Branches off {@code other} and changes a DIFFERENT file, so the two sides merge cleanly. */
	private void createCleanBranches(String other) throws Exception
	{
		commitFile("shared.txt", "base\n", "base commit");
		String main = repository.getBranch();

		git.checkout().setCreateBranch(true).setName(other).call();
		commitFile("other.txt", "from " + other + "\n", "add other.txt on " + other);

		git.checkout().setName(main).call();
	}

	@Nested
	@DisplayName("merge")
	class Merge
	{
		@Test
		@DisplayName("a branch ahead of the current one fast-forwards")
		void fastForwards() throws Exception
		{
			createCleanBranches("feature");

			String result = GitService.mergeIn(repository, "feature", null, false, null);

			assertAll(
				() -> assertTrue(result.contains("FAST_FORWARD"), "Should report a fast-forward: " + result),
				() -> assertTrue(Files.exists(tempDir.resolve("other.txt")),
					"The merged file must be in the working tree"));
		}

		@Test
		@DisplayName("NO_FF forces a merge commit even when a fast-forward was possible")
		void noFastForwardCreatesMergeCommit() throws Exception
		{
			createCleanBranches("feature");

			String result = GitService.mergeIn(repository, "feature", "NO_FF", false, "merge feature in");

			RevCommit head = repository.parseCommit(repository.resolve(Constants.HEAD));
			assertAll(
				() -> assertTrue(result.contains("MERGED"), "Should report a merge: " + result),
				() -> assertFalse(result.contains("FAST_FORWARD"), "NO_FF must not fast-forward: " + result),
				() -> assertEquals(2, head.getParentCount(), "A merge commit has two parents"));
		}

		@Test
		@DisplayName("merging a branch already contained reports it is up to date")
		void alreadyUpToDate() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			git.branchCreate().setName("stale").call();
			commitFile("shared.txt", "moved on\n", "second commit");

			String result = GitService.mergeIn(repository, "stale", null, false, null);

			assertTrue(result.contains("ALREADY_UP_TO_DATE"), "Should report up to date: " + result);
		}

		@Test
		@DisplayName("a squash merge leaves the result staged instead of committing it")
		void squashLeavesResultStaged() throws Exception
		{
			createCleanBranches("feature");
			String before = headSha();

			String result = GitService.mergeIn(repository, "feature", null, true, null);

			assertAll(
				() -> assertTrue(result.contains("MERGED_NOT_COMMITTED"),
					"Should say the result is not committed: " + result),
				() -> assertTrue(result.contains("gitCommit"), "Should say what to do next: " + result),
				() -> assertEquals(before, headSha(), "A squash must not move HEAD"),
				() -> assertTrue(git.status().call().getAdded().contains("other.txt"),
					"The merged file must be staged"));
		}

		@Test
		@DisplayName("squash with NO_FF is refused by JGit rather than silently doing one of them")
		void squashWithNoFastForwardIsRefused() throws Exception
		{
			createCleanBranches("feature");

			RuntimeException problem = assertThrows(RuntimeException.class,
				() -> GitService.mergeIn(repository, "feature", "NO_FF", true, null));
			assertTrue(problem.getMessage().contains("squash"),
				"Should say the two cannot be combined: " + problem.getMessage());
		}

		@Test
		@DisplayName("a conflicting merge reports CONFLICTED, names the file and leaves markers in the tree")
		void conflictReportsFilesAndLeavesMarkers() throws Exception
		{
			createDivergingBranches("feature");

			String result = GitService.mergeIn(repository, "feature", null, false, null);

			assertAll(
				() -> assertTrue(result.contains("CONFLICTED"), "Should report a conflict: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the conflicting file: " + result),
				() -> assertTrue(result.contains("gitResetToRevision"),
					"Should say how to abandon the merge: " + result),
				() -> assertTrue(git.status().call().getConflicting().contains("shared.txt"),
					"The file must be left conflicting"),
				() -> assertTrue(Files.readString(tempDir.resolve("shared.txt")).contains("<<<<<<<"),
					"The working tree must hold conflict markers"));
		}

		@Test
		@DisplayName("FF_ONLY refuses a merge that cannot fast-forward, leaving HEAD where it was")
		void ffOnlyRefusesRealMerge() throws Exception
		{
			createDivergingBranches("feature");
			String before = headSha();

			String result = GitService.mergeIn(repository, "feature", "FF_ONLY", false, null);

			assertAll(
				() -> assertTrue(result.contains("Merge FAILED") || result.contains("ABORTED"),
					"FF_ONLY must not merge: " + result),
				() -> assertEquals(before, headSha(), "HEAD must not move"));
		}

		@Test
		@DisplayName("an unresolvable ref is refused before anything is merged")
		void refusesUnresolvableRef() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");

			assertThrows(IllegalArgumentException.class,
				() -> GitService.mergeIn(repository, "no-such-branch", null, false, null));
		}

		@Test
		@DisplayName("an unknown fastForward mode is refused by name")
		void refusesUnknownFastForwardMode() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			git.branchCreate().setName("feature").call();

			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.mergeIn(repository, "feature", "SOMETIMES", false, null));
			assertTrue(problem.getMessage().contains("FF_ONLY"),
				"Should name the valid modes: " + problem.getMessage());
		}

		@Test
		@DisplayName("a missing ref is refused rather than defaulted")
		void refusesMissingRef()
		{
			assertThrows(NullPointerException.class,
				() -> GitService.mergeIn(repository, null, null, false, null));
		}
	}

	@Nested
	@DisplayName("rebase")
	class Rebase
	{
		@Test
		@DisplayName("a clean rebase replays the commit and reports OK")
		void cleanRebaseReportsOk() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String main = repository.getBranch();
			git.checkout().setCreateBranch(true).setName("feature").call();
			commitFile("feature.txt", "feature\n", "feature commit");

			git.checkout().setName(main).call();
			commitFile("main.txt", "main\n", "main commit");
			git.checkout().setName("feature").call();

			String result = GitService.rebaseIn(repository, main, null);

			assertAll(
				() -> assertTrue(result.contains("Rebase BEGIN OK"), "Should report OK: " + result),
				() -> assertTrue(Files.exists(tempDir.resolve("main.txt")),
					"The upstream commit must now be in the branch"),
				() -> assertEquals(RepositoryState.SAFE, repository.getRepositoryState(),
					"A clean rebase must not leave the repository mid-operation"));
		}

		@Test
		@DisplayName("rebasing onto an ancestor reports UP_TO_DATE")
		void upToDateReportsUpToDate() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			git.branchCreate().setName("behind").call();
			commitFile("shared.txt", "moved on\n", "second commit");

			String result = GitService.rebaseIn(repository, "behind", "BEGIN");

			assertTrue(result.contains("UP_TO_DATE"), "Should report up to date: " + result);
		}

		@Test
		@DisplayName("a conflicting rebase STOPS, names the commit and the file, and stays mid-rebase")
		void conflictStopsAndStaysMidRebase() throws Exception
		{
			createDivergingBranches("feature");
			git.checkout().setName("feature").call();

			String result = GitService.rebaseIn(repository, "master", "BEGIN");

			assertAll(
				() -> assertTrue(result.contains("STOPPED"), "Should report it stopped: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the conflicting file: " + result),
				() -> assertTrue(result.contains("CONTINUE"), "Should say what to do next: " + result),
				() -> assertNotEquals(RepositoryState.SAFE, repository.getRepositoryState(),
					"The repository must stay mid-rebase so the caller can continue"));
		}

		@Test
		@DisplayName("ABORT after a conflict puts the branch back and clears the rebase")
		void abortRestoresTheBranch() throws Exception
		{
			createDivergingBranches("feature");
			git.checkout().setName("feature").call();
			String before = headSha();
			GitService.rebaseIn(repository, "master", "BEGIN");

			String result = GitService.rebaseIn(repository, null, "ABORT");

			assertAll(
				() -> assertTrue(result.contains("ABORTED"), "Should report it aborted: " + result),
				() -> assertEquals(before, headSha(), "The original HEAD must be restored"),
				() -> assertEquals(RepositoryState.SAFE, repository.getRepositoryState(),
					"The rebase must be cleared"),
				() -> assertEquals("feature", repository.getBranch(), "The branch must be back"));
		}

		@Test
		@DisplayName("CONTINUE after resolving and staging the conflict finishes the rebase")
		void continueFinishesTheRebase() throws Exception
		{
			createDivergingBranches("feature");
			git.checkout().setName("feature").call();
			GitService.rebaseIn(repository, "master", "BEGIN");

			Files.writeString(tempDir.resolve("shared.txt"), "resolved by hand\n");
			git.add().addFilepattern("shared.txt").call();

			String result = GitService.rebaseIn(repository, null, "CONTINUE");

			assertAll(
				() -> assertTrue(result.contains("Rebase CONTINUE OK"), "Should finish: " + result),
				() -> assertEquals(RepositoryState.SAFE, repository.getRepositoryState(),
					"The rebase must be finished"),
				() -> assertEquals("resolved by hand\n", Files.readString(tempDir.resolve("shared.txt")),
					"The resolution must be what was committed"));
		}

		@Test
		@DisplayName("SKIP after a conflict drops that commit and finishes the rebase")
		void skipDropsTheCommit() throws Exception
		{
			createDivergingBranches("feature");
			git.checkout().setName("feature").call();
			GitService.rebaseIn(repository, "master", "BEGIN");

			String result = GitService.rebaseIn(repository, null, "SKIP");

			assertAll(
				() -> assertTrue(result.contains("Rebase SKIP"), "Should report the skip: " + result),
				() -> assertEquals(RepositoryState.SAFE, repository.getRepositoryState(),
					"The rebase must be finished"),
				() -> assertEquals("from master\n", Files.readString(tempDir.resolve("shared.txt")),
					"Skipping the commit keeps the upstream content"));
		}

		@Test
		@DisplayName("uncommitted changes stop a rebase before it starts")
		void uncommittedChangesStopTheRebase() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String main = repository.getBranch();
			git.checkout().setCreateBranch(true).setName("feature").call();
			commitFile("feature.txt", "feature\n", "feature commit");
			git.checkout().setName(main).call();
			commitFile("shared.txt", "moved on\n", "main commit");
			git.checkout().setName("feature").call();

			Files.writeString(tempDir.resolve("shared.txt"), "dirty\n");

			String result = GitService.rebaseIn(repository, main, "BEGIN");

			assertAll(
				() -> assertTrue(result.contains("UNCOMMITTED_CHANGES") || result.contains("BLOCKED"),
					"Should refuse to rebase over local changes: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the blocking file: " + result),
				() -> assertTrue(result.contains("stash"), "Should say what to do next: " + result));
		}

		@Test
		@DisplayName("BEGIN without an upstream is refused")
		void refusesBeginWithoutUpstream() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");

			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.rebaseIn(repository, null, "BEGIN"));
			assertTrue(problem.getMessage().contains("upstream"),
				"Should say an upstream is needed: " + problem.getMessage());
		}

		@Test
		@DisplayName("an unresolvable upstream is refused before anything is replayed")
		void refusesUnresolvableUpstream() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");

			assertThrows(IllegalArgumentException.class,
				() -> GitService.rebaseIn(repository, "no-such-branch", "BEGIN"));
		}

		@Test
		@DisplayName("an unknown operation is refused by name")
		void refusesUnknownOperation() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");

			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.rebaseIn(repository, "master", "RESUME"));
			assertTrue(problem.getMessage().contains("CONTINUE"),
				"Should name the valid operations: " + problem.getMessage());
		}

		@Test
		@DisplayName("CONTINUE with no rebase in progress is refused instead of doing something else")
		void refusesContinueWithoutRebase() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");

			assertThrows(IllegalStateException.class, () -> GitService.rebaseIn(repository, null, "CONTINUE"));
		}
	}

	@Nested
	@DisplayName("cherry-pick")
	class CherryPick
	{
		@Test
		@DisplayName("a commit from another branch is applied as a new commit")
		void appliesCommit() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String main = repository.getBranch();
			git.checkout().setCreateBranch(true).setName("feature").call();
			RevCommit pick = commitFile("feature.txt", "feature\n", "the commit to pick");

			// main has to move on, otherwise replaying the commit onto the same parent with the same
			// tree and author produces a byte-identical commit - and so the same sha.
			git.checkout().setName(main).call();
			commitFile("main.txt", "main\n", "main moves on");

			String result = GitService.applyCommitsIn(repository, true, pick.getName());

			assertAll(
				() -> assertTrue(result.contains("Cherry-pick APPLIED"), "Should report it applied: " + result),
				() -> assertTrue(result.contains("the commit to pick"), "Should name the created commit: " + result),
				() -> assertTrue(Files.exists(tempDir.resolve("feature.txt")),
					"The picked change must be in the working tree"),
				() -> assertNotEquals(pick.getName(), headSha(), "A cherry-pick creates a NEW commit"));
		}

		@Test
		@DisplayName("several commits are applied in the order given")
		void appliesSeveralCommits() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String main = repository.getBranch();
			git.checkout().setCreateBranch(true).setName("feature").call();
			RevCommit first = commitFile("one.txt", "one\n", "first to pick");
			RevCommit second = commitFile("two.txt", "two\n", "second to pick");
			git.checkout().setName(main).call();

			String result = GitService.applyCommitsIn(repository, true,
				first.getName() + ", " + second.getName());

			assertAll(
				() -> assertTrue(result.contains("APPLIED"), "Should report it applied: " + result),
				() -> assertTrue(result.contains("first to pick") && result.contains("second to pick"),
					"Should list both created commits: " + result),
				() -> assertTrue(Files.exists(tempDir.resolve("one.txt")) &&
					Files.exists(tempDir.resolve("two.txt")), "Both changes must be in the working tree"));
		}

		@Test
		@DisplayName("a conflicting cherry-pick reports CONFLICTED and names the file")
		void conflictReportsFile() throws Exception
		{
			createDivergingBranches("feature");
			ObjectId featureTip = repository.resolve("feature");

			String result = GitService.applyCommitsIn(repository, true, featureTip.getName());

			assertAll(
				() -> assertTrue(result.contains("CONFLICTED"), "Should report a conflict: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the conflicting file: " + result),
				() -> assertTrue(result.contains("gitResetToRevision"),
					"Should say how to abandon it: " + result),
				() -> assertTrue(git.status().call().getConflicting().contains("shared.txt"),
					"The file must be left conflicting"));
		}

		@Test
		@DisplayName("an unresolvable commit is refused before anything is applied")
		void refusesUnresolvableCommit() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String before = headSha();

			assertThrows(IllegalArgumentException.class,
				() -> GitService.applyCommitsIn(repository, true, "no-such-commit"));
			assertEquals(before, headSha(), "Nothing must have been applied");
		}

		@Test
		@DisplayName("an empty commit list is refused")
		void refusesEmptyCommitList()
		{
			assertAll(
				() -> assertThrows(IllegalArgumentException.class,
					() -> GitService.applyCommitsIn(repository, true, null)),
				() -> assertThrows(IllegalArgumentException.class,
					() -> GitService.applyCommitsIn(repository, true, " , ")));
		}
	}

	@Nested
	@DisplayName("revert")
	class Revert
	{
		@Test
		@DisplayName("reverting a commit undoes its change with a new commit, keeping history")
		void revertsCommit() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			RevCommit toUndo = commitFile("added.txt", "added\n", "the commit to undo");

			String result = GitService.applyCommitsIn(repository, false, toUndo.getName());

			assertAll(
				() -> assertTrue(result.contains("Revert APPLIED"), "Should report it applied: " + result),
				() -> assertFalse(Files.exists(tempDir.resolve("added.txt")),
					"The reverted file must be gone from the working tree"),
				() -> assertTrue(repository.resolve(toUndo.getName()) != null,
					"The original commit must still be in the repository - a revert keeps history"));
		}

		@Test
		@DisplayName("a conflicting revert reports CONFLICTED and names the file")
		void conflictReportsFile() throws Exception
		{
			commitFile("shared.txt", "one\n", "base commit");
			RevCommit toUndo = commitFile("shared.txt", "two\n", "the commit to undo");
			commitFile("shared.txt", "three\n", "a later change to the same line");

			String result = GitService.applyCommitsIn(repository, false, toUndo.getName());

			assertAll(
				() -> assertTrue(result.contains("CONFLICTED"), "Should report a conflict: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the conflicting file: " + result));
		}

		@Test
		@DisplayName("an unresolvable commit is refused before anything is reverted")
		void refusesUnresolvableCommit() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			String before = headSha();

			assertThrows(IllegalArgumentException.class,
				() -> GitService.applyCommitsIn(repository, false, "no-such-commit"));
			assertEquals(before, headSha(), "Nothing must have been reverted");
		}
	}
}

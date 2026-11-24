package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * Tests the working-tree-preservation contract of {@code GitService.stagePatch}, driven through its
 * extracted core {@code GitService.applyPatchToIndex(Repository, String, Runnable)}.
 * <p>
 * Staging a patch is destructive by construction: the target paths are checked out from HEAD (which
 * wipes the caller's uncommitted content from disk), the patch is applied on top, the resulting blobs
 * are written into the index, and only then is the saved content written back. Between the checkout
 * and the restore the in-memory copy is the ONLY copy of the caller's work, so the restore has to
 * happen on every exit path.
 * <p>
 * Upstream AssistAI shipped this with the restore inside the try and a catch that only rethrew, so a
 * patch that failed to apply - the ordinary outcome for stale context - destroyed uncommitted work
 * permanently. It named the bug in commit {@code a6dc32d}; our port had inherited it verbatim and it
 * is fixed under SVY-21303. These tests exist so it cannot come back: {@link
 * FailedPatch#keepsUncommittedContentWhenThePatchDoesNotApply()} fails outright against the old code.
 */
public class GitServiceStagePatchTest
{
	@TempDir
	Path tempDir;

	private Git git;
	private Repository repository;

	/** The committed content of {@code file.txt}, i.e. what a checkout from HEAD would restore. */
	private static final String COMMITTED = "alpha\nbeta\ngamma\n";

	/** Uncommitted work on top of {@link #COMMITTED} - the content that must survive. */
	private static final String UNCOMMITTED = "alpha\nbeta EDITED BY THE USER\ngamma\n";

	@BeforeEach
	void setUp() throws Exception
	{
		git = Git.init().setDirectory(tempDir.toFile()).call();
		repository = git.getRepository();

		Files.writeString(tempDir.resolve("file.txt"), COMMITTED);
		git.add().addFilepattern(".").call();
		git.commit().setMessage("initial commit").call();

		// The caller's uncommitted edit, present only in the working tree.
		Files.writeString(tempDir.resolve("file.txt"), UNCOMMITTED);
	}

	@AfterEach
	void tearDown()
	{
		if (git != null)
		{
			git.close();
		}
	}

	private String stagePatch(String patch)
	{
		return GitService.applyPatchToIndex(repository, patch, null);
	}

	private String workingTreeContent() throws Exception
	{
		return Files.readString(tempDir.resolve("file.txt"));
	}

	@Nested
	@DisplayName("when the patch does not apply")
	class FailedPatch
	{
		/**
		 * The regression test for the data-loss bug. The patch quotes context that is not in HEAD, so
		 * {@code apply()} throws after the checkout has already replaced the working tree - exactly the
		 * window in which the only copy of the caller's work is the in-memory one.
		 */
		@Test
		@DisplayName("keeps the caller's uncommitted content on disk")
		void keepsUncommittedContentWhenThePatchDoesNotApply() throws Exception
		{
			String patchWithStaleContext = """
				--- a/file.txt
				+++ b/file.txt
				@@ -1,3 +1,3 @@
				 this line is not in the file at all
				-neither is this one
				+nor this one
				 and this one is invented too
				""";

			assertThrows(RuntimeException.class, () -> stagePatch(patchWithStaleContext),
				"A patch with stale context must fail rather than be silently skipped");

			assertEquals(UNCOMMITTED, workingTreeContent(),
				"The caller's uncommitted content must still be on disk after a failed patch - " +
					"it was checked out from under them and the restore has to run on the failure path");
		}

		@Test
		@DisplayName("leaves nothing staged")
		void stagesNothingWhenThePatchDoesNotApply() throws Exception
		{
			String patchWithStaleContext = """
				--- a/file.txt
				+++ b/file.txt
				@@ -1,3 +1,3 @@
				 nonsense context
				-more nonsense
				+replacement
				 trailing nonsense
				""";

			assertThrows(RuntimeException.class, () -> stagePatch(patchWithStaleContext));

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertEquals("No changes.", staged, "A failed patch must not leave a partial staged change");
		}
	}

	/**
	 * The index rollback goes through {@code index.lock} (SVY-21303, rounds 3 and 4). It is a private
	 * helper, so it is driven through {@code applyPatchToIndex}: a patch that fails after the index was
	 * snapshotted is what triggers it.
	 */
	@Nested
	@DisplayName("the index rollback and index.lock")
	class IndexRollbackLock
	{
		private static final String STALE_PATCH = """
			--- a/file.txt
			+++ b/file.txt
			@@ -1,3 +1,3 @@
			 this line is not in the file at all
			-neither is this one
			+nor this one
			 and this one is invented too
			""";

		private Path indexFile()
		{
			return repository.getIndexFile().toPath();
		}

		private Path lockFile()
		{
			return repository.getIndexFile().toPath().resolveSibling("index.lock");
		}

		@Test
		@DisplayName("a failed patch leaves the index as it was and no index.lock behind")
		void failedPatchLeavesIndexAndNoLock() throws Exception
		{
			byte[] before = Files.readAllBytes(indexFile());

			assertThrows(RuntimeException.class, () -> stagePatch(STALE_PATCH));

			assertAll(
				() -> assertArrayEquals(before, Files.readAllBytes(indexFile()), "the index must be byte-identical"),
				() -> assertFalse(Files.exists(lockFile()), "the rollback must not leave index.lock behind"));
		}

		@Test
		@DisplayName("an index.lock held by another writer is never deleted by the rollback")
		void foreignLockIsLeftAlone() throws Exception
		{
			byte[] foreign = "held by another writer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			Files.write(lockFile(), foreign);

			assertThrows(RuntimeException.class, () -> stagePatch(STALE_PATCH),
				"the patch fails: the index is locked, or the patch does not apply");

			assertAll(
				() -> assertTrue(Files.exists(lockFile()), "the other writer's lock must survive"),
				() -> assertArrayEquals(foreign, Files.readAllBytes(lockFile()),
					"and its content must not have been overwritten by the rollback"));
		}
	}

	@Nested
	@DisplayName("when the patch applies")
	class AppliedPatch
	{
		@Test
		@DisplayName("stages the change and still restores the working tree")
		void stagesTheChangeAndRestoresTheWorkingTree() throws Exception
		{
			String patch = """
				--- a/file.txt
				+++ b/file.txt
				@@ -1,3 +1,3 @@
				 alpha
				-beta
				+beta STAGED
				 gamma
				""";

			String result = stagePatch(patch);

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertAll(
				() -> assertTrue(result.contains("Patch staged successfully"), "Should report success: " + result),
				() -> assertTrue(staged.contains("+beta STAGED"), "The patch's change must be in the index: " + staged),
				() -> assertEquals(UNCOMMITTED, workingTreeContent(),
					"The caller's uncommitted content must be back on disk, not the patched or committed version"));
		}
	}

	@Nested
	@DisplayName("when the patch affects nothing")
	class EmptyPatch
	{
		@Test
		@DisplayName("reports that and leaves the working tree alone")
		void reportsNoFilesAndLeavesTheWorkingTreeAlone() throws Exception
		{
			String result = stagePatch("");

			assertAll(
				() -> assertEquals("No files affected by patch.", result),
				() -> assertEquals(UNCOMMITTED, workingTreeContent(),
					"An empty patch must not disturb the working tree"));
		}

		@Test
		@DisplayName("blank-but-nonempty text with no file headers also affects nothing")
		void blankTextWithNoHeadersAffectsNothing() throws Exception
		{
			// A patch body that parses to zero file headers takes the same early-return branch as "".
			String result = stagePatch("not a patch, just prose\nwith two lines\n");

			assertAll(
				() -> assertEquals("No files affected by patch.", result),
				() -> assertEquals(UNCOMMITTED, workingTreeContent(),
					"A header-less body must not disturb the working tree"));
		}
	}

	@Nested
	@DisplayName("when the patch adds a brand-new file")
	class NewFilePatch
	{
		/**
		 * A patch whose old side is {@code /dev/null} creates a file that HEAD does not have. The
		 * checkout of its path is a no-op (nothing to restore from HEAD) and the index gains the new
		 * blob, exercising the add branch of the apply/index-edit loop.
		 */
		@Test
		@DisplayName("stages the new file into the index")
		void stagesANewFile() throws Exception
		{
			String patch = """
				diff --git a/added.txt b/added.txt
				new file mode 100644
				--- /dev/null
				+++ b/added.txt
				@@ -0,0 +1,2 @@
				+first new line
				+second new line
				""";

			String result = stagePatch(patch);

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertAll(
				() -> assertTrue(result.startsWith("Patch staged successfully. Files: "), "Should report success: " + result),
				() -> assertTrue(staged.contains("added.txt"), "The new file must be in the index: " + staged),
				() -> assertTrue(staged.contains("+first new line"), "The new content must be staged: " + staged),
				() -> assertEquals(UNCOMMITTED, workingTreeContent(),
					"The pre-existing file's uncommitted content must be undisturbed"));
		}
	}

	@Nested
	@DisplayName("when the patch deletes lines")
	class LineDeletionPatch
	{
		@Test
		@DisplayName("stages a hunk that only removes lines")
		void stagesADeletion() throws Exception
		{
			String patch = """
				--- a/file.txt
				+++ b/file.txt
				@@ -1,3 +1,2 @@
				 alpha
				-beta
				 gamma
				""";

			String result = stagePatch(patch);

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertAll(
				() -> assertTrue(result.startsWith("Patch staged successfully. Files: "), "Should report success: " + result),
				() -> assertTrue(staged.contains("-beta"), "The removal must be staged: " + staged),
				() -> assertFalse(staged.contains("+beta"), "The line must not be re-added: " + staged),
				() -> assertEquals(UNCOMMITTED, workingTreeContent(),
					"The caller's uncommitted content must be restored"));
		}
	}

	@Nested
	@DisplayName("when the patch has several hunks in one file")
	class MultiHunkPatch
	{
		private static final String MANY_LINES =
			"one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n";

		/**
		 * The initial commit of {@link GitServiceStagePatchTest} has only three lines, so this nested
		 * case re-seeds {@code file.txt} with ten lines, far enough apart that two edits land in two
		 * distinct hunks, then re-establishes the uncommitted working-tree content.
		 */
		@BeforeEach
		void seedManyLines() throws Exception
		{
			Files.writeString(tempDir.resolve("file.txt"), MANY_LINES);
			git.add().addFilepattern("file.txt").call();
			git.commit().setMessage("ten-line file").call();
			Files.writeString(tempDir.resolve("file.txt"), MANY_LINES); // working tree == HEAD for this case
		}

		@Test
		@DisplayName("stages both hunks")
		void stagesTwoHunks() throws Exception
		{
			String patch = """
				--- a/file.txt
				+++ b/file.txt
				@@ -1,3 +1,3 @@
				 one
				-two
				+TWO EDITED
				 three
				@@ -8,3 +8,3 @@
				 eight
				-nine
				+NINE EDITED
				 ten
				""";

			String result = stagePatch(patch);

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertAll(
				() -> assertTrue(result.startsWith("Patch staged successfully. Files: "), "Should report success: " + result),
				() -> assertTrue(staged.contains("+TWO EDITED"), "First hunk must be staged: " + staged),
				() -> assertTrue(staged.contains("+NINE EDITED"), "Second hunk must be staged: " + staged));
		}
	}

	@Nested
	@DisplayName("stageIn / unstageIn (non-patch index operations)")
	class StageAndUnstage
	{
		@Test
		@DisplayName("stageIn names the file it staged, counted from the index")
		void stageInReportsWhatItStaged() throws Exception
		{
			Files.writeString(tempDir.resolve("file.txt"), "alpha\nbeta changed\ngamma\n");

			String result = GitService.stageIn(repository, java.util.List.of("file.txt"), "file.txt");

			// Reports the INDEX's outcome, not the caller's pattern: a pathspec matching nothing is not
			// an error in JGit, so echoing the pattern claimed success for a no-op (SVY-21303, round 3).
			assertAll(
				() -> assertTrue(result.startsWith("Added 1 file(s)"), "Should count what it staged: " + result),
				() -> assertTrue(result.contains("file.txt"), "and name it: " + result));

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertTrue(staged.contains("+beta changed"), "The staged edit must be in the index: " + staged);
		}

		@Test
		@DisplayName("stageIn with an empty pathspec list stages the whole tree")
		void stageInWholeTree() throws Exception
		{
			Files.writeString(tempDir.resolve("file.txt"), "alpha\nbeta whole tree\ngamma\n");
			Files.writeString(tempDir.resolve("brand-new.txt"), "fresh\n");

			String result = GitService.stageIn(repository, java.util.List.of(), ".");

			assertAll(
				() -> assertTrue(result.startsWith("Added 2 file(s)"), "Both files must be counted: " + result),
				() -> assertTrue(result.contains("file.txt") && result.contains("brand-new.txt"),
					"and both named: " + result));

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertAll(
				() -> assertTrue(staged.contains("+beta whole tree"), "Modified file must be staged: " + staged),
				() -> assertTrue(staged.contains("brand-new.txt"), "New file must be staged: " + staged));
		}

		@Test
		@DisplayName("unstageIn removes a staged path from the index and names it")
		void unstageInRemovesStagedPath() throws Exception
		{
			Files.writeString(tempDir.resolve("file.txt"), "alpha\nbeta staged then unstaged\ngamma\n");
			git.add().addFilepattern("file.txt").call();

			String result = GitService.unstageIn(repository, java.util.List.of("file.txt"), "file.txt");

			assertAll(
				() -> assertTrue(result.startsWith("Unstaged 1 file(s)"), "Should count it: " + result),
				() -> assertTrue(result.contains("file.txt"), "and name it: " + result));

			String staged = GitService.renderDiff(repository, true, java.util.List.of(), false);
			assertEquals("No changes.", staged, "Nothing should remain staged after unstaging the only staged path");
		}

		@Test
		@DisplayName("unstageIn with an empty pathspec list unstages everything and leaves the working tree alone")
		void unstageInWholeTree() throws Exception
		{
			String edited = "alpha\nbeta whole unstage\ngamma\n";
			Files.writeString(tempDir.resolve("file.txt"), edited);
			Files.writeString(tempDir.resolve("second.txt"), "second\n");
			git.add().addFilepattern(".").call();

			// The empty list is the whole repository (a project at the repository root, or a 'repo'
			// call). It used to reset the literal path ".", which JGit does not expand, so the index
			// was untouched: round 3 reported that honestly, round 4 makes it do what gitAdd's empty
			// list does in the other direction.
			String result = GitService.unstageIn(repository, java.util.List.of(), ".");

			assertAll(
				() -> assertTrue(result.startsWith("Unstaged 2 file(s)"), "Should unstage both: " + result),
				() -> assertTrue(result.contains("file.txt") && result.contains("second.txt"), "and name them: " + result),
				() -> assertTrue(git.status().call().getAdded().isEmpty(), "nothing is left staged as new"),
				() -> assertTrue(git.status().call().getChanged().isEmpty(), "nothing is left staged as changed"),
				() -> assertEquals(edited, Files.readString(tempDir.resolve("file.txt")),
					"the working tree must not be touched"));
		}

		@Test
		@DisplayName("unstageIn with an empty pathspec list and nothing staged says so")
		void unstageInWholeTreeNothingStaged() throws Exception
		{
			String result = GitService.unstageIn(repository, java.util.List.of(), ".");

			assertAll(
				() -> assertTrue(result.startsWith("Nothing to unstage"), "A no-op must not look like success: " + result),
				() -> assertTrue(result.contains("the whole tree"), "and must say what was tried: " + result));
		}

		@Test
		@DisplayName("unstageIn with an empty pathspec list works before the first commit")
		void unstageInWholeTreeWithoutCommits() throws Exception
		{
			Path fresh = Files.createTempDirectory(tempDir, "fresh");
			try (Git empty = Git.init().setDirectory(fresh.toFile()).call())
			{
				Files.writeString(fresh.resolve("a.txt"), "a\n");
				empty.add().addFilepattern(".").call();

				String result = GitService.unstageIn(empty.getRepository(), java.util.List.of(), ".");

				assertAll(
					() -> assertTrue(result.startsWith("Unstaged 1 file(s)"), result),
					() -> assertTrue(empty.status().call().getAdded().isEmpty(), "nothing is left staged"),
					() -> assertTrue(Files.exists(fresh.resolve("a.txt")), "the file itself is kept"));
			}
		}

		@Test
		@DisplayName("unstageIn of a path that was never staged reports that nothing changed")
		void unstageInUnstagedPath() throws Exception
		{
			// Nothing staged for this path, so resetting it is a no-op - and must be reported as one.
			String result = GitService.unstageIn(repository, java.util.List.of("file.txt"), "file.txt");

			assertAll(
				() -> assertTrue(result.startsWith("Nothing to unstage"), "Should report the no-op: " + result),
				() -> assertTrue(result.contains("file.txt"), "and name the pathspec tried: " + result));
		}
	}
}

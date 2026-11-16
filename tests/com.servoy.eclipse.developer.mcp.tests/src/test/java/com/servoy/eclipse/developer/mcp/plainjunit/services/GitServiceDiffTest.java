package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * Tests for {@link GitService}'s diff rendering, driven through its extracted core
 * {@code GitService.renderDiff(Repository, boolean, List, boolean)}.
 * <p>
 * The public {@code getDiff(...)} entry points resolve an Eclipse project to a repository and
 * translate project-relative paths, which needs a workspace; {@code renderDiff} is the whole diff
 * algorithm with an already-resolved repository, so these tests exercise the REAL production code
 * against a temporary JGit repository without a workbench.
 * <p>
 * Originally (SVY-21211) this class replicated the {@code DiffCommand.setOutputStream()} pattern in
 * a private helper and asserted against that copy, so it could not catch a regression in the
 * service at all. {@code getDiff} now renders through a {@link org.eclipse.jgit.diff.DiffFormatter}
 * (SVY-21303, upstream AssistAI {@code f2f162f}) to support the {@code pathFilter} and
 * {@code ignoreWhitespace} options, and these tests call that code directly.
 */
@DisplayName("GitService getDiff pattern")
public class GitServiceDiffTest
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

	/**
	 * Calls the production diff core with no path filter and whitespace-sensitive comparison - the
	 * behaviour of the two legacy {@code getDiff} overloads.
	 */
	private String getDiff(boolean staged)
	{
		return GitService.renderDiff(repository, staged, List.of(), false);
	}

	/** Calls the production diff core with the given repository-relative path filter. */
	private String getDiffFiltered(boolean staged, String... repositoryPaths)
	{
		return GitService.renderDiff(repository, staged, List.of(repositoryPaths), false);
	}

	/** Calls the production diff core ignoring all whitespace differences. */
	private String getDiffIgnoringWhitespace(boolean staged)
	{
		return GitService.renderDiff(repository, staged, List.of(), true);
	}

	/** Commits the given content under the given file name, returning nothing. */
	private void commitFile(String name, String content) throws Exception
	{
		Files.writeString(tempDir.resolve(name), content);
		git.add().addFilepattern(".").call();
		git.commit().setMessage("commit " + name).call();
	}

	@Nested
	@DisplayName("when repository has no commits")
	class NoCommits
	{
		@Test
		@DisplayName("returns 'No commits yet.' for unstaged diff")
		void returnsNoCommitsForUnstaged() throws Exception
		{
			String result = getDiff(false);
			assertEquals("No commits yet.", result);
		}

		@Test
		@DisplayName("returns 'No commits yet.' for staged diff")
		void returnsNoCommitsForStaged() throws Exception
		{
			String result = getDiff(true);
			assertEquals("No commits yet.", result);
		}
	}

	@Nested
	@DisplayName("when there are no changes")
	class NoChanges
	{
		@BeforeEach
		void commitInitialFile() throws Exception
		{
			Path file = tempDir.resolve("hello.txt");
			Files.writeString(file, "Hello World\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();
		}

		@Test
		@DisplayName("returns 'No changes.' for unstaged diff when working tree is clean")
		void returnsNoChangesForUnstaged() throws Exception
		{
			String result = getDiff(false);
			assertEquals("No changes.", result);
		}

		@Test
		@DisplayName("returns 'No changes.' for staged diff when index matches HEAD")
		void returnsNoChangesForStaged() throws Exception
		{
			String result = getDiff(true);
			assertEquals("No changes.", result);
		}
	}

	@Nested
	@DisplayName("when there are unstaged (working-tree) changes")
	class UnstagedChanges
	{
		@BeforeEach
		void commitAndModifyFile() throws Exception
		{
			Path file = tempDir.resolve("hello.txt");
			Files.writeString(file, "Hello World\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			// Modify the file without staging
			Files.writeString(file, "Hello Modified World\n");
		}

		@Test
		@DisplayName("produces unified diff output without 'Missing blob' error")
		void producesDiffWithoutMissingBlobError() throws Exception
		{
			String result = assertDoesNotThrow(() -> getDiff(false));
			assertFalse(result.contains("Missing blob"), "Should not contain 'Missing blob' error");
		}

		@Test
		@DisplayName("diff output contains standard unified diff headers")
		void containsDiffHeaders() throws Exception
		{
			String result = getDiff(false);
			assertAll(
				() -> assertTrue(result.contains("diff --git"), "Should contain 'diff --git' header"),
				() -> assertTrue(result.contains("---"), "Should contain '---' old file marker"),
				() -> assertTrue(result.contains("+++"), "Should contain '+++' new file marker"),
				() -> assertTrue(result.contains("@@"), "Should contain '@@ ... @@' hunk header"));
		}

		@Test
		@DisplayName("diff output shows the actual content change")
		void showsContentChange() throws Exception
		{
			String result = getDiff(false);
			assertAll(
				() -> assertTrue(result.contains("-Hello World"), "Should show removed line"),
				() -> assertTrue(result.contains("+Hello Modified World"), "Should show added line"));
		}

		@Test
		@DisplayName("diff output references the correct file path")
		void referencesCorrectFilePath() throws Exception
		{
			String result = getDiff(false);
			assertTrue(result.contains("a/hello.txt"), "Should reference file path in diff header");
			assertTrue(result.contains("b/hello.txt"), "Should reference file path in diff header");
		}
	}

	@Nested
	@DisplayName("when there are staged (cached) changes")
	class StagedChanges
	{
		@BeforeEach
		void commitAndStageModification() throws Exception
		{
			Path file = tempDir.resolve("hello.txt");
			Files.writeString(file, "Hello World\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			// Modify and stage
			Files.writeString(file, "Hello Staged World\n");
			git.add().addFilepattern("hello.txt").call();
		}

		@Test
		@DisplayName("produces unified diff for staged changes without error")
		void producesStagedDiffWithoutError() throws Exception
		{
			String result = assertDoesNotThrow(() -> getDiff(true));
			assertFalse(result.contains("Missing blob"), "Should not contain 'Missing blob' error");
		}

		@Test
		@DisplayName("staged diff shows the staged content change")
		void showsStagedContentChange() throws Exception
		{
			String result = getDiff(true);
			assertAll(
				() -> assertTrue(result.contains("-Hello World"), "Should show removed line"),
				() -> assertTrue(result.contains("+Hello Staged World"), "Should show added line"));
		}

		@Test
		@DisplayName("staged diff does not show unstaged modifications")
		void doesNotShowUnstagedInStagedDiff() throws Exception
		{
			// Add another modification without staging
			Path file = tempDir.resolve("hello.txt");
			Files.writeString(file, "Hello Unstaged Extra\n");

			String result = getDiff(true);
			// Staged diff should still show the staged change, not the unstaged one
			assertTrue(result.contains("+Hello Staged World"), "Should show staged change");
			assertFalse(result.contains("Unstaged Extra"), "Should NOT show unstaged changes in staged diff");
		}
	}

	@Nested
	@DisplayName("when there are new files")
	class NewFiles
	{
		@BeforeEach
		void commitInitialThenAddNew() throws Exception
		{
			Path file = tempDir.resolve("existing.txt");
			Files.writeString(file, "existing content\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();
		}

		@Test
		@DisplayName("unstaged diff shows new tracked file content after staging")
		void showsNewFileInStagedDiff() throws Exception
		{
			Path newFile = tempDir.resolve("newfile.txt");
			Files.writeString(newFile, "brand new content\n");
			git.add().addFilepattern("newfile.txt").call();

			String result = getDiff(true);
			assertAll(
				() -> assertTrue(result.contains("newfile.txt"), "Should reference new file"),
				() -> assertTrue(result.contains("+brand new content"), "Should show new file content"));
		}
	}

	@Nested
	@DisplayName("when there are deleted files")
	class DeletedFiles
	{
		@BeforeEach
		void commitFileToBeDeleted() throws Exception
		{
			Path file = tempDir.resolve("to-delete.txt");
			Files.writeString(file, "will be deleted\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();
		}

		@Test
		@DisplayName("staged diff shows deleted file")
		void showsDeletedFileInStagedDiff() throws Exception
		{
			Files.delete(tempDir.resolve("to-delete.txt"));
			git.rm().addFilepattern("to-delete.txt").call();

			String result = getDiff(true);
			assertAll(
				() -> assertTrue(result.contains("to-delete.txt"), "Should reference deleted file"),
				() -> assertTrue(result.contains("-will be deleted"), "Should show removed content"));
		}
	}

	@Nested
	@DisplayName("when multiple files are changed")
	class MultipleFiles
	{
		@BeforeEach
		void commitMultipleFiles() throws Exception
		{
			Files.writeString(tempDir.resolve("file1.txt"), "content1\n");
			Files.writeString(tempDir.resolve("file2.txt"), "content2\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			// Modify both files
			Files.writeString(tempDir.resolve("file1.txt"), "modified1\n");
			Files.writeString(tempDir.resolve("file2.txt"), "modified2\n");
		}

		@Test
		@DisplayName("unstaged diff shows changes in all modified files")
		void showsAllFileChanges() throws Exception
		{
			String result = getDiff(false);
			assertAll(
				() -> assertTrue(result.contains("file1.txt"), "Should include file1.txt in diff"),
				() -> assertTrue(result.contains("file2.txt"), "Should include file2.txt in diff"),
				() -> assertTrue(result.contains("+modified1"), "Should show file1 change"),
				() -> assertTrue(result.contains("+modified2"), "Should show file2 change"));
		}
	}

	@Nested
	@DisplayName("when a path filter is given")
	class PathFilter
	{
		@BeforeEach
		void commitTwoFilesAndModifyBoth() throws Exception
		{
			Files.writeString(tempDir.resolve("file1.txt"), "content1\n");
			Files.writeString(tempDir.resolve("file2.txt"), "content2\n");
			Files.createDirectories(tempDir.resolve("sub"));
			Files.writeString(tempDir.resolve("sub/nested.txt"), "nested\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			Files.writeString(tempDir.resolve("file1.txt"), "modified1\n");
			Files.writeString(tempDir.resolve("file2.txt"), "modified2\n");
			Files.writeString(tempDir.resolve("sub/nested.txt"), "modified nested\n");
		}

		@Test
		@DisplayName("limits the unstaged diff to the named file")
		void limitsToNamedFile()
		{
			String result = getDiffFiltered(false, "file1.txt");
			assertAll(
				() -> assertTrue(result.contains("file1.txt"), "Should include the filtered file"),
				() -> assertFalse(result.contains("file2.txt"), "Should exclude the unfiltered file"),
				() -> assertTrue(result.contains("+modified1"), "Should show the filtered file's change"),
				() -> assertFalse(result.contains("+modified2"), "Should not show the excluded file's change"));
		}

		@Test
		@DisplayName("accepts several paths and includes each of them")
		void acceptsSeveralPaths()
		{
			String result = getDiffFiltered(false, "file1.txt", "file2.txt");
			assertAll(
				() -> assertTrue(result.contains("+modified1"), "Should show file1 change"),
				() -> assertTrue(result.contains("+modified2"), "Should show file2 change"),
				() -> assertFalse(result.contains("nested.txt"), "Should exclude the file not named"));
		}

		@Test
		@DisplayName("limits to a directory, including the files under it")
		void limitsToDirectory()
		{
			String result = getDiffFiltered(false, "sub");
			assertAll(
				() -> assertTrue(result.contains("nested.txt"), "Should include the file under the directory"),
				() -> assertFalse(result.contains("file1.txt"), "Should exclude files outside the directory"));
		}

		@Test
		@DisplayName("limits the staged diff too")
		void limitsStagedDiff() throws Exception
		{
			git.add().addFilepattern(".").call();

			String result = getDiffFiltered(true, "file1.txt");
			assertAll(
				() -> assertTrue(result.contains("+modified1"), "Should show the filtered file's staged change"),
				() -> assertFalse(result.contains("file2.txt"), "Should exclude the unfiltered file"));
		}

		@Test
		@DisplayName("an empty filter leaves every change in")
		void emptyFilterKeepsEverything()
		{
			String result = getDiffFiltered(false);
			assertAll(
				() -> assertTrue(result.contains("file1.txt"), "Should include file1.txt"),
				() -> assertTrue(result.contains("file2.txt"), "Should include file2.txt"));
		}
	}

	@Nested
	@DisplayName("when whitespace is ignored")
	class IgnoreWhitespace
	{
		@BeforeEach
		void commitThenChangeOnlyWhitespace() throws Exception
		{
			Files.writeString(tempDir.resolve("code.txt"), "alpha\nbeta\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();
		}

		@Test
		@DisplayName("a whitespace-only change shows up when whitespace matters")
		void whitespaceOnlyChangeIsReportedByDefault() throws Exception
		{
			Files.writeString(tempDir.resolve("code.txt"), "alpha   \n\tbeta\n");

			String result = getDiff(false);
			assertTrue(result.contains("code.txt"),
				"A whitespace-only change must be reported when the comparison is whitespace-sensitive");
		}

		@Test
		@DisplayName("the same change produces no hunks when whitespace is ignored")
		void whitespaceOnlyChangeProducesNoHunks() throws Exception
		{
			Files.writeString(tempDir.resolve("code.txt"), "alpha   \n\tbeta\n");

			String result = getDiffIgnoringWhitespace(false);
			// Matches what `git diff -w` prints: the comparator suppresses the whitespace-only HUNKS,
			// but the file's blob really did change, so scan() still reports the entry and its header
			// is emitted. The contract is therefore "no hunks", not "no output" - asserting
			// "No changes." here would be asserting a behaviour neither git nor upstream has.
			assertAll(
				() -> assertFalse(result.contains("@@"), "No hunk header may survive whitespace-insensitive comparison"),
				() -> assertFalse(result.contains("+alpha"), "No added line may be reported"),
				() -> assertFalse(result.contains("-alpha"), "No removed line may be reported"),
				() -> assertFalse(result.contains("+\tbeta"), "No whitespace-only addition may be reported"));
		}

		@Test
		@DisplayName("a real content change is still reported when whitespace is ignored")
		void realChangeSurvivesWhitespaceIgnoring() throws Exception
		{
			Files.writeString(tempDir.resolve("code.txt"), "alpha\ngamma\n");

			String result = getDiffIgnoringWhitespace(false);
			assertAll(
				() -> assertTrue(result.contains("code.txt"), "Should still report the file"),
				() -> assertTrue(result.contains("+gamma"), "Should still show the real change"));
		}
	}

	@Nested
	@DisplayName("when a binary file changes")
	class BinaryFile
	{
		@BeforeEach
		void commitBinaryThenChangeIt() throws Exception
		{
			byte[] original = new byte[] { 0, 1, 2, 3, 0, 4, 5, 6, 0, 7 };
			Files.write(tempDir.resolve("blob.bin"), original);
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			byte[] changed = new byte[] { 0, 9, 8, 7, 0, 6, 5, 4, 0, 3, 2, 1 };
			Files.write(tempDir.resolve("blob.bin"), changed);
		}

		@Test
		@DisplayName("reports the file as a binary difference, not line content")
		void reportsBinaryDifference() throws Exception
		{
			String result = getDiff(false);
			assertAll(
				() -> assertTrue(result.contains("blob.bin"), "Should reference the binary file: " + result),
				() -> assertTrue(result.contains("Binary files") || result.contains("GIT binary patch"),
					"Should mark the change as binary rather than emitting text hunks: " + result));
		}
	}

	@Nested
	@DisplayName("when a staged diff has no changes but the working tree does")
	class StagedIdenticalWhileWorkingTreeDiffers
	{
		@BeforeEach
		void commitThenModifyWorkingTreeOnly() throws Exception
		{
			Path file = tempDir.resolve("hello.txt");
			Files.writeString(file, "Hello World\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			// Working-tree-only edit; index still equals HEAD.
			Files.writeString(file, "Hello Working Only\n");
		}

		@Test
		@DisplayName("the staged diff is 'No changes.' even though the working tree differs")
		void stagedIsNoChangesWhileWorkingTreeDiffers() throws Exception
		{
			assertEquals("No changes.", getDiff(true), "HEAD-vs-index must be clean when only the working tree changed");
			assertTrue(getDiff(false).contains("+Hello Working Only"), "The working-tree diff must still show the edit");
		}
	}

	@Nested
	@DisplayName("renderRevisionDiff between two revisions")
	class RevisionDiff
	{
		@BeforeEach
		void buildThreeCommits() throws Exception
		{
			commitFile("file1.txt", "v1 line\n");
			commitFile("file2.txt", "other v1\n");
			// second commit edits file1 only
			Files.writeString(tempDir.resolve("file1.txt"), "v2 line\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("edit file1").call();
		}

		@Test
		@DisplayName("shows the change between HEAD~1 and HEAD")
		void diffsTwoRevisions()
		{
			String result = GitService.renderRevisionDiff(repository, "HEAD~1", "HEAD", List.of(), false);
			assertAll(
				() -> assertTrue(result.contains("file1.txt"), "Should reference the changed file: " + result),
				() -> assertTrue(result.contains("-v1 line"), "Should show the old line: " + result),
				() -> assertTrue(result.contains("+v2 line"), "Should show the new line: " + result));
		}

		@Test
		@DisplayName("a path filter limits the revision diff")
		void revisionDiffWithPathFilter()
		{
			String result = GitService.renderRevisionDiff(repository, "HEAD~1", "HEAD", List.of("file2.txt"), false);
			// file2.txt did not change between HEAD~1 and HEAD, so the filtered diff is empty.
			assertEquals("No changes.", result, "A filter on an unchanged file must yield no diff: " + result);
		}

		@Test
		@DisplayName("identical revisions produce no diff")
		void identicalRevisionsNoDiff()
		{
			String result = GitService.renderRevisionDiff(repository, "HEAD", "HEAD", List.of(), false);
			assertEquals("No changes.", result);
		}

		@Test
		@DisplayName("a blank fromRevision defaults to HEAD and compares against the working tree")
		void blankFromDefaultsToHead() throws Exception
		{
			Files.writeString(tempDir.resolve("file1.txt"), "working tree edit\n");
			// null toRevision = working tree; blank fromRevision = HEAD.
			String result = GitService.renderRevisionDiff(repository, "", null, List.of(), false);
			assertTrue(result.contains("+working tree edit"), "HEAD-vs-working-tree should show the edit: " + result);
		}
	}

	@Nested
	@DisplayName("renderCommit")
	class ShowCommit
	{
		@Test
		@DisplayName("a root commit has no Parents line and shows the added content")
		void rootCommit() throws Exception
		{
			commitFile("root.txt", "root content\n");

			String result = GitService.renderCommit(repository, "HEAD", List.of());
			assertAll(
				() -> assertTrue(result.startsWith("commit "), "Should start with the commit header: " + result),
				() -> assertTrue(result.contains("Author: "), "Should carry the author line"),
				() -> assertTrue(result.contains("Date:   "), "Should carry the date line"),
				() -> assertFalse(result.contains("Parents:"), "A root commit must have no Parents line: " + result),
				() -> assertTrue(result.contains("commit root.txt"), "Should include the commit message"),
				() -> assertTrue(result.contains("+root content"), "Should show the added content"));
		}

		@Test
		@DisplayName("a merge commit lists both parents")
		void mergeCommit() throws Exception
		{
			// base commit on the default branch
			commitFile("base.txt", "base\n");
			String mainBranch = repository.getBranch();

			// a side branch that adds its own file
			git.checkout().setCreateBranch(true).setName("side").call();
			Files.writeString(tempDir.resolve("side.txt"), "side content\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("side commit").call();

			// back to main, add a non-conflicting change, then merge side with a merge commit
			git.checkout().setName(mainBranch).call();
			Files.writeString(tempDir.resolve("main.txt"), "main content\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("main commit").call();

			git.merge()
				.include(repository.resolve("side"))
				.setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF)
				.setMessage("merge side into main")
				.call();

			String result = GitService.renderCommit(repository, "HEAD", List.of());
			assertAll(
				() -> assertTrue(result.startsWith("commit "), "Should start with the commit header: " + result),
				() -> assertTrue(result.contains("Parents: "), "A merge commit must list its parents: " + result),
				() -> assertTrue(result.indexOf(' ', result.indexOf("Parents: ") + "Parents: ".length()) > 0,
					"Parents line should carry two space-separated shas: " + result),
				() -> assertTrue(result.contains("merge side into main"), "Should include the merge message"));
		}
	}

	@Nested
	@DisplayName("discardChangesIn")
	class DiscardChanges
	{
		@BeforeEach
		void commitAndModify() throws Exception
		{
			commitFile("tracked.txt", "original\n");
			Files.writeString(tempDir.resolve("tracked.txt"), "modified in working tree\n");
		}

		@Test
		@DisplayName("restores a modified tracked file and reports it")
		void restoresModifiedFile() throws Exception
		{
			String result = GitService.discardChangesIn(repository, List.of("tracked.txt"), "tracked.txt");
			assertAll(
				() -> assertTrue(result.startsWith("Discarded changes in 1 file(s):"), "Should report one discarded file: " + result),
				() -> assertTrue(result.contains("tracked.txt"), "Should name the restored file: " + result),
				() -> assertEquals("original\n", Files.readString(tempDir.resolve("tracked.txt")),
					"The committed content must be back on disk"));
		}

		@Test
		@DisplayName("an empty pathspec discards everything modified")
		void discardsWholeTree() throws Exception
		{
			String result = GitService.discardChangesIn(repository, List.of(), ".");
			assertAll(
				() -> assertTrue(result.startsWith("Discarded changes in 1 file(s):"), "Should discard the one modified file: " + result),
				() -> assertEquals("original\n", Files.readString(tempDir.resolve("tracked.txt")),
					"The committed content must be back on disk"));
		}

		@Test
		@DisplayName("a pattern matching nothing modified reports no match")
		void patternMatchingNothing()
		{
			String result = GitService.discardChangesIn(repository, List.of("does-not-exist.txt"), "does-not-exist.txt");
			assertEquals("No modified tracked files matched: does-not-exist.txt", result);
		}
	}

	@Nested
	@DisplayName("resetToRevisionIn")
	class ResetToRevision
	{
		@BeforeEach
		void buildTwoCommitsWithWorkingEdit() throws Exception
		{
			commitFile("reset.txt", "first\n");
			Files.writeString(tempDir.resolve("reset.txt"), "second\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("second commit").call();
		}

		@Test
		@DisplayName("SOFT reset moves HEAD and reports was/now shas")
		void softReset() throws Exception
		{
			String result = GitService.resetToRevisionIn(repository, "HEAD~1", "SOFT");
			assertAll(
				() -> assertTrue(result.startsWith("Reset (SOFT) to HEAD~1."), "Should report the SOFT mode and target: " + result),
				() -> assertTrue(result.contains("was: "), "Should report the previous head: " + result),
				() -> assertTrue(result.contains("now: "), "Should report the new head: " + result),
				// SOFT keeps index and working tree, so the second-commit content is still staged.
				() -> assertTrue(GitService.renderDiff(repository, true, List.of(), false).contains("+second"),
					"SOFT reset must leave the undone change staged"));
		}

		@Test
		@DisplayName("MIXED reset keeps the working tree but unstages the change")
		void mixedReset() throws Exception
		{
			String result = GitService.resetToRevisionIn(repository, "HEAD~1", "MIXED");
			assertAll(
				() -> assertTrue(result.startsWith("Reset (MIXED) to HEAD~1."), "Should report the MIXED mode and target: " + result),
				() -> assertEquals("No changes.", GitService.renderDiff(repository, true, List.of(), false),
					"MIXED reset must leave nothing staged"),
				() -> assertTrue(GitService.renderDiff(repository, false, List.of(), false).contains("+second"),
					"MIXED reset must keep the change in the working tree"),
				() -> assertEquals("second\n", Files.readString(tempDir.resolve("reset.txt")),
					"MIXED reset must not touch the working-tree file"));
		}

		@Test
		@DisplayName("HARD reset rewrites the working tree back to the target")
		void hardReset() throws Exception
		{
			String result = GitService.resetToRevisionIn(repository, "HEAD~1", "HARD");
			assertAll(
				() -> assertTrue(result.startsWith("Reset (HARD) to HEAD~1."), "Should report the HARD mode and target: " + result),
				() -> assertEquals("first\n", Files.readString(tempDir.resolve("reset.txt")),
					"HARD reset must restore the working-tree file to the target revision"),
				() -> assertEquals("No changes.", GitService.renderDiff(repository, false, List.of(), false),
					"HARD reset must leave a clean working tree"));
		}

		@Test
		@DisplayName("a blank mode defaults to MIXED")
		void blankModeDefaultsToMixed()
		{
			String result = GitService.resetToRevisionIn(repository, "HEAD", "");
			assertTrue(result.startsWith("Reset (MIXED) to HEAD."), "A blank mode must default to MIXED: " + result);
		}

		@Test
		@DisplayName("an unknown mode is rejected with a helpful message")
		void unknownModeRejected()
		{
			IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> GitService.resetToRevisionIn(repository, "HEAD", "SIDEWAYS"));
			assertTrue(thrown.getMessage().contains("Unknown reset mode 'SIDEWAYS'"),
				"Should name the bad mode: " + thrown.getMessage());
			assertTrue(thrown.getMessage().contains("SOFT, MIXED or HARD"),
				"Should list the valid modes: " + thrown.getMessage());
		}
	}
}

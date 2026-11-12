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
}

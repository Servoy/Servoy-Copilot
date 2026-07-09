package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the JGit diff pattern used in {@link GitService#getDiff(String, boolean)}.
 * <p>
 * This validates that using {@code DiffCommand.setOutputStream()} produces correct unified
 * diff output for both working-tree (unstaged) and staged changes â the core fix for SVY-21211
 * where the previous two-stage approach caused "Missing blob" errors.
 * <p>
 * These tests exercise JGit directly (without needing Eclipse workspace) to verify the
 * diff pattern independently of workspace project resolution.
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
	 * Replicates the getDiff logic from GitService using setOutputStream.
	 * This is the FIXED pattern that resolves SVY-21211.
	 */
	private String getDiffWithOutputStream(boolean staged) throws Exception
	{
		try (ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ObjectId head = repository.resolve("HEAD");
			if (head == null)
			{
				return "No commits yet.";
			}

			var diffCommand = git.diff();
			diffCommand.setOutputStream(out);
			if (staged)
			{
				diffCommand.setCached(true);
				var headTree = prepareTreeParser(repository, head);
				diffCommand.setOldTree(headTree);
			}

			diffCommand.call();
			String result = out.toString("UTF-8");
			return result.isEmpty() ? "No changes." : result;
		}
	}

	private static CanonicalTreeParser prepareTreeParser(Repository repo, ObjectId objectId) throws IOException
	{
		try (RevWalk walk = new RevWalk(repo))
		{
			var commit = walk.parseCommit(objectId);
			var treeId = commit.getTree().getId();
			try (var reader = repo.newObjectReader())
			{
				return new CanonicalTreeParser(null, reader, treeId);
			}
		}
	}

	@Nested
	@DisplayName("when repository has no commits")
	class NoCommits
	{
		@Test
		@DisplayName("returns 'No commits yet.' for unstaged diff")
		void returnsNoCommitsForUnstaged() throws Exception
		{
			String result = getDiffWithOutputStream(false);
			assertEquals("No commits yet.", result);
		}

		@Test
		@DisplayName("returns 'No commits yet.' for staged diff")
		void returnsNoCommitsForStaged() throws Exception
		{
			String result = getDiffWithOutputStream(true);
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
			String result = getDiffWithOutputStream(false);
			assertEquals("No changes.", result);
		}

		@Test
		@DisplayName("returns 'No changes.' for staged diff when index matches HEAD")
		void returnsNoChangesForStaged() throws Exception
		{
			String result = getDiffWithOutputStream(true);
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
			String result = assertDoesNotThrow(() -> getDiffWithOutputStream(false));
			assertFalse(result.contains("Missing blob"), "Should not contain 'Missing blob' error");
		}

		@Test
		@DisplayName("diff output contains standard unified diff headers")
		void containsDiffHeaders() throws Exception
		{
			String result = getDiffWithOutputStream(false);
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
			String result = getDiffWithOutputStream(false);
			assertAll(
				() -> assertTrue(result.contains("-Hello World"), "Should show removed line"),
				() -> assertTrue(result.contains("+Hello Modified World"), "Should show added line"));
		}

		@Test
		@DisplayName("diff output references the correct file path")
		void referencesCorrectFilePath() throws Exception
		{
			String result = getDiffWithOutputStream(false);
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
			String result = assertDoesNotThrow(() -> getDiffWithOutputStream(true));
			assertFalse(result.contains("Missing blob"), "Should not contain 'Missing blob' error");
		}

		@Test
		@DisplayName("staged diff shows the staged content change")
		void showsStagedContentChange() throws Exception
		{
			String result = getDiffWithOutputStream(true);
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

			String result = getDiffWithOutputStream(true);
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

			String result = getDiffWithOutputStream(true);
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

			String result = getDiffWithOutputStream(true);
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
			String result = getDiffWithOutputStream(false);
			assertAll(
				() -> assertTrue(result.contains("file1.txt"), "Should include file1.txt in diff"),
				() -> assertTrue(result.contains("file2.txt"), "Should include file2.txt in diff"),
				() -> assertTrue(result.contains("+modified1"), "Should show file1 change"),
				() -> assertTrue(result.contains("+modified2"), "Should show file2 change"));
		}
	}
}

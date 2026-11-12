package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
	}
}

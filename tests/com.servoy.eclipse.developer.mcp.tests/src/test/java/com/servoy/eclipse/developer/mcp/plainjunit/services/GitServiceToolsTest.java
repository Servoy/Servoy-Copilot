package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * Tests the git tools ported from AssistAI's {@code aca70c0} under SVY-21303 - tags, remotes,
 * {@code gitShow}, {@code gitDiscardChanges}, {@code gitResetToRevision} and revision-to-revision
 * diffs - driven through the extracted {@code GitService} cores that take an already-resolved
 * {@link Repository}.
 * <p>
 * The public entry points resolve an Eclipse project to a repository and translate project-relative
 * paths, which needs a workspace. The cores are the whole behaviour with the repository supplied, so
 * these tests exercise the REAL production code against a temporary JGit repository without a
 * workbench - the same split {@code renderDiff} and {@code applyPatchToIndex} already use.
 */
public class GitServiceToolsTest
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

	/** Commits a file with the given content and returns the commit. */
	private RevCommit commitFile(String name, String content, String message) throws Exception
	{
		Files.writeString(tempDir.resolve(name), content);
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(message).call();
	}

	@Nested
	@DisplayName("tags")
	class Tags
	{
		@BeforeEach
		void commitSomething() throws Exception
		{
			commitFile("file.txt", "one\n", "initial commit");
		}

		@Test
		@DisplayName("an empty repository reports no tags")
		void emptyRepositoryReportsNoTags()
		{
			assertEquals("No tags.", GitService.renderTagList(repository));
		}

		@Test
		@DisplayName("a lightweight tag is created and listed without an annotation marker")
		void createsLightweightTag()
		{
			String created = GitService.createTagIn(repository, "v1.0.0", null, null);
			String listed = GitService.renderTagList(repository);

			assertAll(
				() -> assertTrue(created.contains("lightweight"), "Should say it is lightweight: " + created),
				() -> assertTrue(listed.contains("v1.0.0"), "Should list the tag: " + listed),
				() -> assertFalse(listed.contains("(annotated)"), "A lightweight tag must not be marked annotated"));
		}

		@Test
		@DisplayName("an annotated tag carries its message")
		void createsAnnotatedTag()
		{
			String created = GitService.createTagIn(repository, "v2.0.0", "the second release", null);
			String listed = GitService.renderTagList(repository);

			assertAll(
				() -> assertTrue(created.contains("annotated"), "Should say it is annotated: " + created),
				() -> assertTrue(listed.contains("(annotated)"), "Should be marked annotated: " + listed),
				() -> assertTrue(listed.contains("the second release"), "Should show the message: " + listed));
		}

		@Test
		@DisplayName("a tag can be created at an older revision, not just HEAD")
		void createsTagAtGivenRevision() throws Exception
		{
			RevCommit first = repository.parseCommit(repository.resolve("HEAD"));
			commitFile("file.txt", "two\n", "second commit");

			String created = GitService.createTagIn(repository, "v0.9.0", null, first.getName());

			assertTrue(created.contains(first.getName().substring(0, 7)),
				"The tag must point at the named revision, not HEAD: " + created);
		}

		@Test
		@DisplayName("deleting a tag reports what it pointed at and removes it")
		void deletesTag()
		{
			GitService.createTagIn(repository, "v1.0.0", null, null);

			String deleted = GitService.deleteTagIn(repository, "v1.0.0");

			assertAll(
				() -> assertTrue(deleted.contains("v1.0.0"), "Should name the deleted tag: " + deleted),
				() -> assertEquals("No tags.", GitService.renderTagList(repository), "The tag must be gone"));
		}

		@Test
		@DisplayName("deleting a tag that does not exist is refused")
		void refusesToDeleteUnknownTag()
		{
			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.deleteTagIn(repository, "v9.9.9"));
			assertTrue(problem.getMessage().contains("v9.9.9"), "Should name the tag: " + problem.getMessage());
		}

		@Test
		@DisplayName("tagging an unresolvable revision is refused")
		void refusesUnresolvableRevision()
		{
			assertThrows(IllegalArgumentException.class,
				() -> GitService.createTagIn(repository, "vX", null, "no-such-revision"));
		}
	}

	@Nested
	@DisplayName("remotes")
	class Remotes
	{
		@Test
		@DisplayName("a repository with no remotes says so")
		void reportsNoRemotes()
		{
			assertEquals("No remotes configured.", GitService.renderRemoteList(repository));
		}

		@Test
		@DisplayName("a configured remote is listed with its fetch and push URL")
		void listsConfiguredRemote() throws Exception
		{
			StoredConfig config = repository.getConfig();
			config.setString("remote", "origin", "url", "https://example.invalid/repo.git");
			config.save();

			String listed = GitService.renderRemoteList(repository);

			assertAll(
				() -> assertTrue(listed.contains("origin"), "Should name the remote: " + listed),
				() -> assertTrue(listed.contains("https://example.invalid/repo.git"), "Should show the URL: " + listed),
				() -> assertTrue(listed.contains("fetch:"), "Should label the fetch URL"),
				() -> assertTrue(listed.contains("push:"), "Should label the push URL"));
		}
	}

	@Nested
	@DisplayName("showCommit")
	class ShowCommit
	{
		@Test
		@DisplayName("a root commit is diffed against an empty tree, so its content appears as added")
		void showsRootCommitAgainstEmptyTree() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");

			String shown = GitService.renderCommit(repository, null, List.of());

			assertAll(
				() -> assertTrue(shown.contains("initial commit"), "Should include the message: " + shown),
				() -> assertTrue(shown.contains("+alpha"), "A root commit's content must show as added: " + shown),
				() -> assertFalse(shown.contains("Parents:"), "A root commit has no parents"));
		}

		@Test
		@DisplayName("a later commit is diffed against its parent and names it")
		void showsCommitAgainstParent() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			commitFile("file.txt", "beta\n", "second commit");

			String shown = GitService.renderCommit(repository, null, List.of());

			assertAll(
				() -> assertTrue(shown.contains("second commit"), "Should include the message"),
				() -> assertTrue(shown.contains("Parents: " + first.getName()), "Should name the parent: " + shown),
				() -> assertTrue(shown.contains("-alpha"), "Should show the removal"),
				() -> assertTrue(shown.contains("+beta"), "Should show the addition"));
		}

		@Test
		@DisplayName("an explicit revision shows that commit, not HEAD")
		void showsNamedRevision() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			commitFile("file.txt", "beta\n", "second commit");

			String shown = GitService.renderCommit(repository, first.getName(), List.of());

			assertAll(
				() -> assertTrue(shown.contains("initial commit"), "Should show the named commit"),
				() -> assertFalse(shown.contains("second commit"), "Should not show HEAD"));
		}

		@Test
		@DisplayName("a path filter limits which files appear in the diff")
		void limitsDiffToFilteredPaths() throws Exception
		{
			commitFile("kept.txt", "one\n", "initial commit");
			Files.writeString(tempDir.resolve("kept.txt"), "one changed\n");
			Files.writeString(tempDir.resolve("other.txt"), "two\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("touch both").call();

			String shown = GitService.renderCommit(repository, null, List.of("kept.txt"));

			assertAll(
				() -> assertTrue(shown.contains("kept.txt"), "Should include the filtered file"),
				() -> assertFalse(shown.contains("other.txt"), "Should exclude the unfiltered file"));
		}

		@Test
		@DisplayName("an unresolvable revision is refused")
		void refusesUnresolvableRevision() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");

			assertThrows(IllegalArgumentException.class,
				() -> GitService.renderCommit(repository, "no-such-revision", List.of()));
		}
	}

	@Nested
	@DisplayName("discardChanges")
	class DiscardChanges
	{
		@BeforeEach
		void commitAndModify() throws Exception
		{
			commitFile("one.txt", "one\n", "initial commit");
			Files.writeString(tempDir.resolve("two.txt"), "two\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("add two").call();
		}

		@Test
		@DisplayName("a modified tracked file is restored from the index")
		void restoresModifiedFile() throws Exception
		{
			Files.writeString(tempDir.resolve("one.txt"), "one EDITED\n");

			String result = GitService.discardChangesIn(repository, List.of(), ".");

			assertAll(
				() -> assertTrue(result.contains("one.txt"), "Should report the restored file: " + result),
				() -> assertEquals("one\n", Files.readString(tempDir.resolve("one.txt")),
					"The committed content must be back"));
		}

		@Test
		@DisplayName("a path filter restores only the named file")
		void restoresOnlyFilteredFile() throws Exception
		{
			Files.writeString(tempDir.resolve("one.txt"), "one EDITED\n");
			Files.writeString(tempDir.resolve("two.txt"), "two EDITED\n");

			GitService.discardChangesIn(repository, List.of("one.txt"), "one.txt");

			assertAll(
				() -> assertEquals("one\n", Files.readString(tempDir.resolve("one.txt")), "Should be restored"),
				() -> assertEquals("two EDITED\n", Files.readString(tempDir.resolve("two.txt")),
					"An unfiltered file must keep its modification"));
		}

		@Test
		@DisplayName("nothing modified reports that and changes nothing")
		void reportsNothingToDiscard()
		{
			String result = GitService.discardChangesIn(repository, List.of(), ".");
			assertTrue(result.startsWith("No modified tracked files matched"), "Should say nothing matched: " + result);
		}

		@Test
		@DisplayName("an untracked file is left alone")
		void leavesUntrackedFileAlone() throws Exception
		{
			Files.writeString(tempDir.resolve("untracked.txt"), "scratch\n");

			GitService.discardChangesIn(repository, List.of(), ".");

			assertTrue(Files.exists(tempDir.resolve("untracked.txt")), "An untracked file must survive");
		}
	}

	@Nested
	@DisplayName("resetToRevision")
	class ResetToRevision
	{
		@Test
		@DisplayName("SOFT moves HEAD and leaves the change staged")
		void softResetKeepsTheWorkStaged() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			commitFile("file.txt", "beta\n", "second commit");

			String result = GitService.resetToRevisionIn(repository, first.getName(), "SOFT");

			String staged = GitService.renderDiff(repository, true, List.of(), false);
			assertAll(
				() -> assertTrue(result.contains("SOFT"), "Should report the mode: " + result),
				() -> assertTrue(result.contains(first.getName()), "Should report the new HEAD"),
				() -> assertTrue(staged.contains("+beta"), "The undone commit's work must be staged: " + staged),
				() -> assertEquals("beta\n", Files.readString(tempDir.resolve("file.txt")),
					"SOFT must not touch the working tree"));
		}

		@Test
		@DisplayName("HARD moves HEAD and rewrites the working tree")
		void hardResetRewritesTheWorkingTree() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			commitFile("file.txt", "beta\n", "second commit");

			GitService.resetToRevisionIn(repository, first.getName(), "HARD");

			assertAll(
				() -> assertEquals("alpha\n", Files.readString(tempDir.resolve("file.txt")),
					"HARD must restore the working tree to the target revision"),
				() -> assertEquals("No changes.", GitService.renderDiff(repository, true, List.of(), false),
					"Nothing may be left staged"));
		}

		@Test
		@DisplayName("the default mode is MIXED")
		void defaultsToMixed() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			commitFile("file.txt", "beta\n", "second commit");

			String result = GitService.resetToRevisionIn(repository, first.getName(), null);

			assertTrue(result.contains("MIXED"), "Should default to MIXED: " + result);
		}

		@Test
		@DisplayName("an unknown mode is refused with the valid ones named")
		void refusesUnknownMode() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");

			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.resetToRevisionIn(repository, null, "SIDEWAYS"));
			assertTrue(problem.getMessage().contains("SOFT"), "Should name the valid modes: " + problem.getMessage());
		}

		@Test
		@DisplayName("an unresolvable revision is refused")
		void refusesUnresolvableRevision() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");

			assertThrows(IllegalArgumentException.class,
				() -> GitService.resetToRevisionIn(repository, "no-such-revision", "MIXED"));
		}
	}

	@Nested
	@DisplayName("revision-to-revision diff")
	class RevisionDiff
	{
		@Test
		@DisplayName("two revisions are compared in order")
		void comparesTwoRevisions() throws Exception
		{
			RevCommit first = commitFile("file.txt", "alpha\n", "initial commit");
			RevCommit second = commitFile("file.txt", "beta\n", "second commit");

			String diff = GitService.renderRevisionDiff(repository, first.getName(), second.getName(),
				List.of(), false);

			assertAll(
				() -> assertTrue(diff.contains("-alpha"), "Should show the removal: " + diff),
				() -> assertTrue(diff.contains("+beta"), "Should show the addition"));
		}

		@Test
		@DisplayName("omitting the newer side compares the revision with the working tree")
		void comparesRevisionWithWorkingTree() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");
			Files.writeString(tempDir.resolve("file.txt"), "working\n");

			String diff = GitService.renderRevisionDiff(repository, null, null, List.of(), false);

			assertTrue(diff.contains("+working"), "Should diff against the working tree: " + diff);
		}

		@Test
		@DisplayName("identical revisions report no changes")
		void identicalRevisionsReportNoChanges() throws Exception
		{
			RevCommit only = commitFile("file.txt", "alpha\n", "initial commit");

			String diff = GitService.renderRevisionDiff(repository, only.getName(), only.getName(),
				List.of(), false);

			assertEquals("No changes.", diff);
		}

		@Test
		@DisplayName("an unresolvable revision is refused")
		void refusesUnresolvableRevision() throws Exception
		{
			commitFile("file.txt", "alpha\n", "initial commit");

			assertThrows(IllegalArgumentException.class,
				() -> GitService.renderRevisionDiff(repository, "no-such-revision", null, List.of(), false));
		}

	/**
	 * The staging/unstaging half of the {@code aca70c0} path-resolution fix. The project-relative
	 * to repository-relative translation itself needs a workbench ({@code RepositoryMapping}), so
	 * what is covered here is the half that can be: given the repository-relative pathspecs the
	 * translation produces, staging and unstaging touch exactly those files and nothing else.
	 * <p>
	 * This is what the fix was about. Before it, a project-relative path was handed to JGit as given
	 * and silently matched nothing for a project under {@code bundles/} - Git does not fail on a
	 * pathspec that matches nothing - while the tool still reported success.
	 * </p>
	 */
	@Nested
	@DisplayName("staging and unstaging by repository-relative pathspec")
	class StagingByPathspec
	{
		/** Creates {@code bundles/project-a} and {@code bundles/project-b}, each with one file. */
		@BeforeEach
		void createNestedProjects() throws Exception
		{
			commitFile("root.txt", "root\n", "initial commit");
			Files.createDirectories(tempDir.resolve("bundles/project-a"));
			Files.createDirectories(tempDir.resolve("bundles/project-b"));
			Files.writeString(tempDir.resolve("bundles/project-a/a.txt"), "a\n");
			Files.writeString(tempDir.resolve("bundles/project-b/b.txt"), "b\n");
		}

		@Test
		@DisplayName("a nested project's prefix stages only that project's files")
		void stagesOnlyTheNamedProject() throws Exception
		{
			String result = GitService.stageIn(repository, List.of("bundles/project-a"), ".");

			var staged = git.status().call().getAdded();
			assertAll(
				() -> assertTrue(result.contains("Added"), "Should report what it staged: " + result),
				() -> assertTrue(staged.contains("bundles/project-a/a.txt"), "Project A's file must be staged"),
				() -> assertFalse(staged.contains("bundles/project-b/b.txt"),
					"Project B's file must NOT be staged - one project's '.' cannot reach another's files"));
		}

		@Test
		@DisplayName("an empty pathspec list means the whole tree, which is the project-at-the-root case")
		void emptyPathspecsStageEverything() throws Exception
		{
			GitService.stageIn(repository, List.of(), ".");

			var staged = git.status().call().getAdded();
			assertAll(
				() -> assertTrue(staged.contains("bundles/project-a/a.txt"), "Project A's file must be staged"),
				() -> assertTrue(staged.contains("bundles/project-b/b.txt"), "Project B's file must be staged"));
		}

		@Test
		@DisplayName("a pathspec that matches nothing stages nothing - Git does not fail on it")
		void pathspecMatchingNothingStagesNothing() throws Exception
		{
			// The pre-fix symptom: a project-relative path handed to JGit unresolved. It must be visibly
			// a no-op, which is why the resolution has to happen before JGit sees it.
			GitService.stageIn(repository, List.of("a.txt"), "a.txt");

			assertTrue(git.status().call().getAdded().isEmpty(),
				"An unresolved project-relative path matches nothing at the repository root");
		}

		@Test
		@DisplayName("a deletion of a tracked file is staged too, not only modifications")
		void stagesDeletions() throws Exception
		{
			GitService.stageIn(repository, List.of("bundles/project-a"), ".");
			git.commit().setMessage("add project A").call();
			Files.delete(tempDir.resolve("bundles/project-a/a.txt"));

			GitService.stageIn(repository, List.of("bundles/project-a"), ".");

			assertTrue(git.status().call().getRemoved().contains("bundles/project-a/a.txt"),
				"The deletion must be staged - that is what the setUpdate(true) pass is for");
		}

		@Test
		@DisplayName("unstaging a nested project's prefix leaves another project's staged files alone")
		void unstagesOnlyTheNamedProject() throws Exception
		{
			GitService.stageIn(repository, List.of(), ".");

			String result = GitService.unstageIn(repository, List.of("bundles/project-a"), ".");

			var staged = git.status().call().getAdded();
			assertAll(
				() -> assertTrue(result.contains("Unstaged"), "Should report what it unstaged: " + result),
				() -> assertFalse(staged.contains("bundles/project-a/a.txt"), "Project A's file must be unstaged"),
				() -> assertTrue(staged.contains("bundles/project-b/b.txt"),
					"Project B's file must stay staged"));
		}
	}

	/**
	 * The repository-centric path (SVY-21524) passes no {@code projectName} at all - the caller named
	 * a {@code repo} instead - so there is no project to resolve a prefix from.
	 * {@code WorkspaceRoot.getProject(null)} throws a NullPointerException from inside Eclipse
	 * ({@code ConcurrentHashMap.get}), which is what {@code gitAdd(null, "<repo>", "file.txt")} hit.
	 */
	@Nested
	@DisplayName("no project to resolve a prefix from")
	class NoProject
	{
		@Test
		@DisplayName("a null project name yields no prefix instead of throwing")
		void nullProjectNameHasNoPrefix()
		{
			assertNull(GitService.projectPrefixOrNull(null),
				"null must answer 'no project', not reach WorkspaceRoot.getProject(null)");
		}

		@Test
		@DisplayName("a blank project name is treated the same as none")
		void blankProjectNameHasNoPrefix()
		{
			assertAll(
				() -> assertNull(GitService.projectPrefixOrNull("")),
				() -> assertNull(GitService.projectPrefixOrNull("   ")));
		}
	}
	}
}

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

	/**
	 * {@code prefixOnce} is the fix for the second-round review finding: {@code gitStatus} prints
	 * repository-relative paths, so pasting one into {@code gitAdd} used to get the project prefix
	 * prepended a second time, producing {@code bundles/x/bundles/x/...} - which matches nothing,
	 * and Git does not fail on a pathspec that matches nothing, so the tool answered "Added: ..."
	 * having staged nothing. That is the bug upstream's {@code aca70c0} fixed, re-created in the
	 * opposite direction.
	 */
	@Nested
	@DisplayName("both path forms are accepted (prefixOnce)")
	class PrefixOnce
	{
		@Test
		@DisplayName("a project-relative path gets the prefix")
		void projectRelativeIsPrefixed()
		{
			assertEquals("bundles/my.project/src/Foo.java",
				GitService.prefixOnce("bundles/my.project", "src/Foo.java"));
		}

		@Test
		@DisplayName("an already-prefixed path is NOT prefixed again - the bug this fixes")
		void repositoryRelativeIsLeftAlone()
		{
			assertEquals("bundles/my.project/src/Foo.java",
				GitService.prefixOnce("bundles/my.project", "bundles/my.project/src/Foo.java"),
				"A path copied out of gitStatus must not become bundles/x/bundles/x/...");
		}

		@Test
		@DisplayName("the prefix itself is left alone")
		void prefixItselfIsLeftAlone()
		{
			assertEquals("bundles/my.project", GitService.prefixOnce("bundles/my.project", "bundles/my.project"));
		}

		@Test
		@DisplayName("a sibling project whose name merely starts the same IS still prefixed")
		void siblingWithSharedPrefixIsStillPrefixed()
		{
			// The test is on a path-segment boundary, so "bundles/my.project-tests/..." is NOT inside
			// "bundles/my.project" and must be treated as project-relative.
			assertEquals("bundles/my.project/bundles/my.project-tests/x.txt",
				GitService.prefixOnce("bundles/my.project", "bundles/my.project-tests/x.txt"),
				"A segment-boundary check must not mistake a sibling for an already-resolved path");
		}

		@Test
		@DisplayName("no prefix leaves the path as it is")
		void noPrefix()
		{
			assertAll(
				() -> assertEquals("src/Foo.java", GitService.prefixOnce(null, "src/Foo.java")),
				() -> assertEquals("src/Foo.java", GitService.prefixOnce("", "src/Foo.java")),
				() -> assertEquals("src/Foo.java", GitService.prefixOnce("   ", "src/Foo.java")));
		}
	}

	/**
	 * Staging and unstaging accept a path in EITHER form, driven through the real cores against a
	 * repository laid out like this one (projects under {@code bundles/}).
	 */
	@Nested
	@DisplayName("staging accepts a path copied from gitStatus")
	class StagingAcceptsBothForms
	{
		@BeforeEach
		void createNestedProject() throws Exception
		{
			commitFile("root.txt", "root\n", "initial commit");
			Files.createDirectories(tempDir.resolve("bundles/project-a/src"));
			Files.writeString(tempDir.resolve("bundles/project-a/src/Foo.java"), "class Foo {}\n");
		}

		@Test
		@DisplayName("the repository-relative form, as gitStatus prints it, stages the file")
		void repositoryRelativeFormStages() throws Exception
		{
			// Exactly what gitStatus would print, and what resolveWritePaths now produces for it.
			GitService.stageIn(repository, List.of(
				GitService.prefixOnce("bundles/project-a", "bundles/project-a/src/Foo.java")), "<pasted>");

			assertTrue(git.status().call().getAdded().contains("bundles/project-a/src/Foo.java"),
				"A path copied from gitStatus must stage the file it names");
		}

		@Test
		@DisplayName("the project-relative form stages the same file")
		void projectRelativeFormStages() throws Exception
		{
			GitService.stageIn(repository, List.of(
				GitService.prefixOnce("bundles/project-a", "src/Foo.java")), "src/Foo.java");

			assertTrue(git.status().call().getAdded().contains("bundles/project-a/src/Foo.java"),
				"A project-relative path must stage the same file");
		}

		@Test
		@DisplayName("a double-prefixed path matches nothing - what the bug produced")
		void doublePrefixedMatchesNothing() throws Exception
		{
			// The pre-fix behaviour, asserted so the symptom is on record: the pathspec is well-formed
			// and Git accepts it, it simply names a file that does not exist.
			GitService.stageIn(repository, List.of("bundles/project-a/bundles/project-a/src/Foo.java"), ".");

			assertTrue(git.status().call().getAdded().isEmpty(),
				"A double-prefixed path stages nothing, which is why the old behaviour looked like success");
		}
	}

	/**
	 * A read tool's blank filter means the WHOLE REPOSITORY, not the project - the first
	 * second-round review finding. Narrowing a read to the project silently changed what
	 * {@code gitDiff(projectName)} answers compared with plain {@code git diff}, and upstream
	 * AssistAI draws the same read/write distinction ({@code resolveDiffPaths} vs
	 * {@code resolvePathspecs}).
	 * <p>
	 * The resolver itself needs a workbench for the prefix, so what is driven here is the rendering
	 * layer: an empty pathspec list (what a blank filter now produces) must diff everything, while a
	 * project prefix (what {@code "."} produces) must diff only that subtree.
	 * </p>
	 */
	@Nested
	@DisplayName("a read tool's blank filter covers the whole repository")
	class ReadFilterScope
	{
		@BeforeEach
		void twoNestedProjects() throws Exception
		{
			Files.createDirectories(tempDir.resolve("bundles/project-a"));
			Files.createDirectories(tempDir.resolve("tests/project-a.tests"));
			Files.writeString(tempDir.resolve("bundles/project-a/a.txt"), "one\n");
			Files.writeString(tempDir.resolve("tests/project-a.tests/t.txt"), "one\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial commit").call();

			Files.writeString(tempDir.resolve("bundles/project-a/a.txt"), "two\n");
			Files.writeString(tempDir.resolve("tests/project-a.tests/t.txt"), "two\n");
		}

		@Test
		@DisplayName("no filter diffs both projects")
		void noFilterCoversEverything()
		{
			String diff = GitService.renderDiff(repository, false, List.of(), false);

			assertAll(
				() -> assertTrue(diff.contains("bundles/project-a/a.txt"),
					"The main project's change must appear: " + diff),
				() -> assertTrue(diff.contains("tests/project-a.tests/t.txt"),
					"The sibling project's change must appear too - a blank filter is the whole repository"));
		}

		@Test
		@DisplayName("a project prefix diffs only that project")
		void prefixNarrowsToOneProject()
		{
			String diff = GitService.renderDiff(repository, false, List.of("bundles/project-a"), false);

			assertAll(
				() -> assertTrue(diff.contains("bundles/project-a/a.txt"), "The named project must appear"),
				() -> assertFalse(diff.contains("tests/project-a.tests/t.txt"),
					"The sibling must NOT appear when a prefix was given"));
		}

		@Test
		@DisplayName("the same split holds for a commit's diff")
		void showCommitHonoursTheSameSplit() throws Exception
		{
			git.add().addFilepattern(".").call();
			git.commit().setMessage("touch both projects").call();

			String all = GitService.renderCommit(repository, "HEAD", List.of());
			String narrowed = GitService.renderCommit(repository, "HEAD", List.of("bundles/project-a"));

			assertAll(
				() -> assertTrue(all.contains("bundles/project-a/a.txt") &&
					all.contains("tests/project-a.tests/t.txt"), "No filter shows the whole commit"),
				() -> assertFalse(narrowed.contains("tests/project-a.tests/t.txt"),
					"A prefix narrows the commit's diff"));
		}
	}

	/**
	 * {@code gitRemoteList}'s output goes to a model and into session transcripts, so a token
	 * embedded in a remote URL must not be echoed (second-round review finding).
	 */
	@Nested
	@DisplayName("remote URLs are redacted")
	class RedactUserInfo
	{
		@Test
		@DisplayName("an embedded token is replaced, host and path kept")
		void tokenIsRedacted()
		{
			String redacted = GitService.redactUserInfo("https://x-access-token:ghp_secret123@github.com/o/r.git");

			assertAll(
				() -> assertFalse(redacted.contains("ghp_secret123"), "The token must be gone: " + redacted),
				() -> assertFalse(redacted.contains("x-access-token"), "The user part must be gone too: " + redacted),
				() -> assertTrue(redacted.contains("github.com/o/r.git"), "The host and path must remain"));
		}

		@Test
		@DisplayName("a password without a user is removed")
		void passwordOnly()
		{
			String redacted = GitService.redactUserInfo("https://:hunter2@example.com/r.git");
			assertFalse(redacted.contains("hunter2"), "The password must be gone: " + redacted);
		}

		@Test
		@DisplayName("a URL with no credentials is left untouched")
		void cleanUrlUnchanged()
		{
			assertAll(
				() -> assertEquals("https://github.com/o/r.git",
					GitService.redactUserInfo("https://github.com/o/r.git")),
				() -> assertEquals("/home/me/repos/r", GitService.redactUserInfo("/home/me/repos/r")));
		}

		@Test
		@DisplayName("an scp-style address keeps its user - there is no secret in it")
		void scpStyleKeepsUser()
		{
			// git@github.com:o/r.git carries a user but never a password; stripping it would make the
			// remote unusable to read, and nothing is leaked by showing it.
			String redacted = GitService.redactUserInfo("git@github.com:o/r.git");
			assertTrue(redacted.contains("github.com:o/r.git"), "The address must stay usable: " + redacted);
		}

		@Test
		@DisplayName("null and blank are returned as they are")
		void nullAndBlank()
		{
			assertAll(
				() -> assertNull(GitService.redactUserInfo(null)),
				() -> assertEquals("", GitService.redactUserInfo("")));
		}

		@Test
		@DisplayName("a '/' before the '@' ends the authority, so there is no credential to redact")
		void slashBeforeAtIsNotUserInfo()
		{
			// "https://tok/SECRET@host/r" has host "tok" and path "/SECRET@host/r" - per RFC 3986 a
			// userinfo cannot hold an unencoded '/', and git reads it that way too. So this carries
			// no credential, and is left alone DELIBERATELY: inventing one here would replace a
			// legitimate host and path with "***". The pair of this test and the two below is the
			// boundary - redact inside the authority, never outside it.
			assertEquals("https://tok/SECRET@host/r.git",
				GitService.redactUserInfo("https://tok/SECRET@host/r.git"),
				"A '/' before the '@' means the path has started, so nothing is a credential");
		}

		@Test
		@DisplayName("an '@' outside the authority is not mistaken for userinfo")
		void atSignInPathOrQueryIsNotUserInfo()
		{
			assertAll(
				() -> assertEquals("https://host/o/r@v1.git",
					GitService.redactUserInfo("https://host/o/r@v1.git"),
					"An '@' in the PATH is not a credential"),
				() -> assertEquals("https://github.com/o/r.git?x=a@b",
					GitService.redactUserInfo("https://github.com/o/r.git?x=a@b"),
					"An '@' in the QUERY is not a credential either"));
		}

		@Test
		@DisplayName("a '#' or '?' inside the userinfo does not hide the secret (round 4)")
		void hashOrQuestionMarkInUserInfoIsRedacted()
		{
			// Round 3 ended the authority at the first '/', '?' or '#', so a password or token that
			// contains one of those was cut off before the '@' and printed. The userinfo ends at the
			// LAST '@' before the first '/'.
			assertAll(
				() -> assertFalse(GitService.redactUserInfo("https://u:p#SECRET@host/r.git").contains("SECRET"),
					"a '#' in the password"),
				() -> assertFalse(GitService.redactUserInfo("https://u:p?SECRET@host/r.git").contains("SECRET"),
					"a '?' in the password"),
				() -> assertEquals("https://***@host/r.git", GitService.redactUserInfo("https://u:p#SECRET@host/r.git"),
					"host and path must survive"));
		}

		@Test
		@DisplayName("an '@' in the query of a path-carrying URL is still left alone")
		void queryAtIsStillNotUserInfo()
		{
			// The other half of the boundary above: widening the authority must not make the
			// round-3 over-redaction come back.
			assertAll(
				() -> assertEquals("https://github.com/o/r.git?x=a@b",
					GitService.redactUserInfo("https://github.com/o/r.git?x=a@b")),
				() -> assertEquals("https://github.com/o/r.git#frag@x",
					GitService.redactUserInfo("https://github.com/o/r.git#frag@x")));
		}

		@Test
		@DisplayName("the empty-authority form URIish prints for a password-only URL is redacted")
		void emptyAuthorityForm()
		{
			String redacted = GitService.redactUserInfo("https:///:tok_SECRET@host/r.git");
			assertAll(
				() -> assertFalse(redacted.contains("tok_SECRET"), "The secret must be gone: " + redacted),
				() -> assertTrue(redacted.contains("host/r.git"), "The host must remain: " + redacted));
		}

		@Test
		@DisplayName("a file:/// URL is not mistaken for the empty-authority form")
		void fileUrlUntouched()
		{
			assertEquals("file:///home/me/a@b", GitService.redactUserInfo("file:///home/me/a@b"));
		}

		@Test
		@DisplayName("an ssh:// URL and an uppercase scheme are handled")
		void otherSchemes()
		{
			assertAll(
				() -> assertTrue(GitService.redactUserInfo("ssh://git@host:22/o/r.git").contains("***@host:22"),
					"ssh:// carries its user in the authority, so it is redacted like any other"),
				() -> assertFalse(GitService.redactUserInfo("HTTPS://tok_SECRET@h/r").contains("tok_SECRET"),
					"The scheme comparison must not be case-sensitive"));
		}
	}

	/**
	 * Round 3's findings, each with the symptom asserted rather than only the fix.
	 * <p>
	 * The common thread: a tool that reports the caller's own pattern back cannot distinguish
	 * "staged it" from "matched nothing", and JGit's {@code AddCommand} does not fail on a pathspec
	 * that matches nothing - unlike the CLI, which says
	 * {@code fatal: pathspec ... did not match any files}.
	 * </p>
	 */
	@Nested
	@DisplayName("a staging report says what the index actually did")
	class StagingOutcome
	{
		@BeforeEach
		void nestedLayout() throws Exception
		{
			commitFile("root.txt", "root\n", "initial commit");
			Files.createDirectories(tempDir.resolve("bundles/project-a/src"));
			Files.createDirectories(tempDir.resolve("docs"));
			Files.writeString(tempDir.resolve("bundles/project-a/src/Foo.java"), "class Foo {}\n");
			Files.writeString(tempDir.resolve("docs/spec.md"), "spec\n");
		}

		@Test
		@DisplayName("a pathspec that matches nothing reports NOTHING staged, not success")
		void nothingMatchedIsReported() throws Exception
		{
			// Exactly what round 2's prefixOnce produced for an out-of-project path: a well-formed
			// pathspec naming a file that does not exist.
			String result = GitService.stageIn(repository,
				List.of("bundles/project-a/docs/spec.md"), "docs/spec.md");

			assertAll(
				() -> assertTrue(result.startsWith("Nothing to"),
					"Must not claim success for an empty index: " + result),
				() -> assertTrue(result.contains("bundles/project-a/docs/spec.md"),
					"Must name the pathspec that matched nothing, so the caller can see why: " + result),
				() -> assertTrue(result.contains("docs/spec.md"),
					"and the pattern that was requested: " + result),
				() -> assertTrue(git.status().call().getAdded().isEmpty(), "and nothing is staged"));
		}

		@Test
		@DisplayName("a successful stage names the files, counted from the index")
		void stagedFilesAreNamed() throws Exception
		{
			String result = GitService.stageIn(repository, List.of("bundles/project-a"), ".");

			assertAll(
				() -> assertTrue(result.startsWith("Added 1 file(s)"), "Should count what it staged: " + result),
				() -> assertTrue(result.contains("bundles/project-a/src/Foo.java"),
					"and name it: " + result));
		}

		@Test
		@DisplayName("unstaging reports the same way")
		void unstagingReportsTheSameWay() throws Exception
		{
			GitService.stageIn(repository, List.of(), ".");

			String unstaged = GitService.unstageIn(repository, List.of("bundles/project-a"), ".");
			String nothing = GitService.unstageIn(repository, List.of("bundles/project-a/nope.txt"), "nope.txt");

			assertAll(
				() -> assertTrue(unstaged.startsWith("Unstaged "), "Should report the unstaging: " + unstaged),
				() -> assertTrue(unstaged.contains("bundles/project-a/src/Foo.java"), "and name the file"),
				() -> assertTrue(nothing.startsWith("Nothing to"),
					"A pathspec matching nothing must say so: " + nothing));
		}

		@Test
		@DisplayName("a modification is reported, not only a new file")
		void modificationIsReported() throws Exception
		{
			GitService.stageIn(repository, List.of(), ".");
			git.commit().setMessage("add everything").call();
			Files.writeString(tempDir.resolve("bundles/project-a/src/Foo.java"), "class Foo { int x; }\n");

			String result = GitService.stageIn(repository, List.of("bundles/project-a"), ".");

			assertTrue(result.contains("bundles/project-a/src/Foo.java"),
				"A changed blob must count as staged, not just an added path: " + result);
		}
	}

	/**
	 * A failing remote operation must not print the remote URL, because JGit's
	 * {@code TransportException} message IS the URL - so a token-bearing remote leaked on exactly
	 * the failure such remotes hit.
	 */
	@Nested
	@DisplayName("a failure message carries no credentials")
	class SafeMessages
	{
		@Test
		@DisplayName("a URL inside free text is redacted")
		void urlInFreeTextIsRedacted()
		{
			String leaked = "https://tok_SECRET123@github.com/o/r.git: connection failed";

			String safe = GitService.redactUrlsIn(leaked);

			assertAll(
				() -> assertFalse(safe.contains("tok_SECRET123"), "The token must be gone: " + safe),
				() -> assertTrue(safe.contains("github.com/o/r.git"), "The host and path must remain"),
				() -> assertTrue(safe.contains("connection failed"), "The reason must survive"));
		}

		@Test
		@DisplayName("every URL in the message is redacted, not just the first")
		void everyUrlIsRedacted()
		{
			String safe = GitService.redactUrlsIn(
				"https://a_SECRET@h1/r failed, retrying https://b_SECRET@h2/r");

			assertAll(
				() -> assertFalse(safe.contains("a_SECRET"), "First must go: " + safe),
				() -> assertFalse(safe.contains("b_SECRET"), "Second must go too: " + safe));
		}

		@Test
		@DisplayName("safeMessage handles a null message and a null throwable")
		void safeMessageDegrades()
		{
			assertAll(
				() -> assertEquals("(no message)", GitService.safeMessage(null)),
				() -> assertEquals("RuntimeException",
					GitService.safeMessage(new RuntimeException((String)null))));
		}

		@Test
		@DisplayName("text with no URL is untouched")
		void plainTextUntouched()
		{
			assertEquals("the branch has no upstream", GitService.redactUrlsIn("the branch has no upstream"));
		}
	}

	/**
	 * The password-only remote form, through the REAL code path. Round 2's test fed
	 * {@code redactUserInfo} a raw string and passed, while production fed it
	 * {@code URIish.toString()} - which re-emits {@code https://:tok@h} as {@code https:///:tok@h},
	 * whose authority no longer parses - and the secret printed.
	 */
	@Nested
	@DisplayName("remote redaction through renderRemoteList")
	class RedactionOnTheRealPath
	{
		@BeforeEach
		void commitSomething() throws Exception
		{
			commitFile("file.txt", "one\n", "initial commit");
		}

		private void addRemote(String name, String url) throws Exception
		{
			StoredConfig config = repository.getConfig();
			config.setString("remote", name, "url", url);
			config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
			config.save();
		}

		@Test
		@DisplayName("a token as the user is redacted")
		void tokenAsUser() throws Exception
		{
			addRemote("tokuser", "https://tok_SECRET123@github.com/o/r.git");

			String listed = GitService.renderRemoteList(repository);

			assertAll(
				() -> assertFalse(listed.contains("tok_SECRET123"), "The token must be gone: " + listed),
				() -> assertTrue(listed.contains("github.com/o/r.git"), "The host must remain"));
		}

		@Test
		@DisplayName("the PASSWORD-ONLY form is redacted on the real path - round 2 leaked it here")
		void passwordOnlyOnTheRealPath() throws Exception
		{
			addRemote("passonly", "https://:pw_SECRET456@example.com/r.git");

			String listed = GitService.renderRemoteList(repository);

			assertAll(
				() -> assertFalse(listed.contains("pw_SECRET456"),
					"The password must be gone even though URIish mangles this form: " + listed),
				() -> assertTrue(listed.contains("example.com/r.git"), "The host must remain"));
		}

		@Test
		@DisplayName("user and password together are redacted")
		void userAndPassword() throws Exception
		{
			addRemote("both", "https://me:pw_SECRET789@example.com/r.git");

			String listed = GitService.renderRemoteList(repository);

			assertAll(
				() -> assertFalse(listed.contains("pw_SECRET789"), "The password must be gone: " + listed),
				() -> assertFalse(listed.contains("me:"), "The user must be gone too: " + listed));
		}

		@Test
		@DisplayName("an scp-style and a clean URL are shown as they are")
		void nothingToRedact() throws Exception
		{
			addRemote("scp", "git@github.com:o/r.git");
			addRemote("clean", "https://github.com/o/r.git");

			String listed = GitService.renderRemoteList(repository);

			assertAll(
				() -> assertTrue(listed.contains("git@github.com:o/r.git"),
					"An scp-style address must stay usable: " + listed),
				() -> assertTrue(listed.contains("https://github.com/o/r.git"),
					"A clean URL must be untouched: " + listed));
		}
	}
	}
	}

	/**
	 * A credentialed URL driven through the real fetch/pull/push code, not through the helper
	 * (round 4: the helper's own tests passed while the real paths still leaked). The remote is
	 * unreachable, so JGit fails with a TransportException whose message is the URL.
	 */
	@Nested
	@DisplayName("a credentialed remote that fails to connect")
	class FailureTextOnTheRealPath
	{
		private static final String[] URLS = {
			"https://tok_SECRET@127.0.0.1:9/n/r.git",
			"https://:tok_SECRET@127.0.0.1:9/n/r.git",
			"https://u:p#tok_SECRET@127.0.0.1:9/n/r.git",
			"https://u:p?tok_SECRET@127.0.0.1:9/n/r.git" };

		@BeforeEach
		void commitSomething() throws Exception
		{
			commitFile("file.txt", "one\n", "initial commit");
		}

		private void addRemote(String url) throws Exception
		{
			StoredConfig config = repository.getConfig();
			config.setString("remote", "origin", "url", url);
			config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
			config.save();
		}

		private String outcome(java.util.concurrent.Callable<String> call)
		{
			try
			{
				return call.call();
			}
			catch (Exception e)
			{
				// What McpServerFactory would send to the model.
				return com.servoy.eclipse.developer.mcp.McpServerFactory.errorText(e);
			}
		}

		@Test
		@DisplayName("fetch, pull and push never print the secret, whether they return or throw")
		void noSecretInAnyOutcome() throws Exception
		{
			for (String url : URLS)
			{
				addRemote(url);
				String branch = repository.getBranch();
				assertAll(url,
					() -> assertFalse(outcome(() -> GitService.fetchIn(repository, "origin", false)).contains("tok_SECRET"), "fetch"),
					() -> assertFalse(outcome(() -> GitService.pullIn(repository, "origin", null, false)).contains("tok_SECRET"), "pull"),
					() -> assertFalse(outcome(() -> GitService.pushIn(repository, "origin", branch, false, false)).contains("tok_SECRET"), "push"));
			}
		}
	}

	@Nested
	@DisplayName("McpServerFactory reports a thrown failure without credentials")
	class ThrownFailureText
	{
		@Test
		@DisplayName("the root cause's URL is redacted in the text sent to the model")
		void rootCauseIsRedacted()
		{
			Exception thrown = new RuntimeException("Failed to fetch: x",
				new RuntimeException("wrapper", new java.io.IOException("https://tok_SECRET@host/r.git: connection failed")));

			String text = com.servoy.eclipse.developer.mcp.McpServerFactory.errorText(thrown);

			assertAll(
				() -> assertFalse(text.contains("tok_SECRET"), text),
				() -> assertTrue(text.startsWith("Error: "), text),
				() -> assertTrue(text.contains("host/r.git: connection failed"), "the reason must remain: " + text));
		}

		@Test
		@DisplayName("a failure with no message falls back to the exception class name")
		void noMessage()
		{
			assertEquals("Error: IllegalStateException",
				com.servoy.eclipse.developer.mcp.McpServerFactory.errorText(new IllegalStateException()));
		}
	}
}

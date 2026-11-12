package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * Tests the remote git tools ported from AssistAI's {@code aca70c0} under SVY-21303 -
 * {@code gitFetch}, {@code gitPull} and {@code gitPush} - driven through the extracted
 * {@code GitService} cores that take an already-resolved {@link Repository}.
 * <p>
 * The counterpart of upstream's {@code GitServiceRemoteAndLayoutPDETest}. <b>No network is
 * involved:</b> the remote is a local bare repository created in {@link #setUp()}, which is how
 * upstream tests these too. That also exercises the credentials path for real - a {@code file://}
 * remote has no HTTP scheme, so {@code credentialsFor} must answer "nothing stored" and let JGit
 * proceed, and nothing may require an OSGi container.
 * </p>
 */
public class GitServiceRemoteTest
{
	@TempDir
	Path tempDir;

	private Git remoteGit;
	private Repository remote;
	private Git git;
	private Repository repository;
	private String branch;

	@BeforeEach
	void setUp() throws Exception
	{
		Path remoteDir = tempDir.resolve("remote.git");
		remoteGit = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call();
		remote = remoteGit.getRepository();

		Path localDir = tempDir.resolve("local");
		Files.createDirectories(localDir);
		git = Git.init().setDirectory(localDir.toFile()).call();
		repository = git.getRepository();
		branch = repository.getBranch();

		StoredConfig config = repository.getConfig();
		RemoteConfig origin = new RemoteConfig(config, Constants.DEFAULT_REMOTE_NAME);
		origin.addURI(new URIish(remoteDir.toUri().toString()));
		origin.addFetchRefSpec(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
		origin.update(config);
		config.save();
	}

	@AfterEach
	void tearDown()
	{
		if (git != null)
		{
			git.close();
		}
		if (remoteGit != null)
		{
			remoteGit.close();
		}
	}

	private RevCommit commitFile(String name, String content, String message) throws Exception
	{
		Files.writeString(repository.getWorkTree().toPath().resolve(name), content);
		git.add().addFilepattern(".").call();
		return git.commit().setMessage(message).call();
	}

	private String headSha() throws Exception
	{
		ObjectId head = repository.resolve(Constants.HEAD);
		return head == null ? null : head.getName();
	}

	/** Pushes the current branch so the remote has it, and records it as the upstream. */
	private void publishBranch() throws Exception
	{
		GitService.pushIn(repository, null, null, true, false);
	}

	/**
	 * Commits something directly on the bare remote, by cloning it to a scratch working tree,
	 * committing there and pushing back - so the local repository is genuinely behind.
	 */
	private RevCommit commitOnRemote(String name, String content, String message) throws Exception
	{
		Path scratch = tempDir.resolve("scratch-" + name);
		try (Git other = Git.cloneRepository()
			.setURI(remote.getDirectory().toURI().toString())
			.setDirectory(scratch.toFile())
			.call())
		{
			Files.writeString(scratch.resolve(name), content);
			other.add().addFilepattern(".").call();
			RevCommit commit = other.commit().setMessage(message).call();
			other.push().call();
			return commit;
		}
	}

	@Nested
	@DisplayName("fetch")
	class Fetch
	{
		@Test
		@DisplayName("fetching from an empty remote reports nothing moved")
		void emptyRemoteReportsUpToDate() throws Exception
		{
			String result = GitService.fetchIn(repository, null, false);

			assertTrue(result.contains("UP_TO_DATE"), "Nothing to fetch should be up to date: " + result);
		}

		@Test
		@DisplayName("a new remote branch is reported as a moved ref, and the working tree is untouched")
		void reportsMovedRefsWithoutTouchingWorkingTree() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();
			commitOnRemote("remote.txt", "remote\n", "commit made on the remote");
			String before = headSha();

			String result = GitService.fetchIn(repository, "origin", false);

			assertAll(
				() -> assertTrue(result.contains("UPDATED"), "Should report the update: " + result),
				() -> assertTrue(result.contains("refs/remotes/origin/" + branch),
					"Should name the remote-tracking ref: " + result),
				() -> assertEquals(before, headSha(), "A fetch must not move HEAD"),
				() -> assertFalse(Files.exists(repository.getWorkTree().toPath().resolve("remote.txt")),
					"A fetch must not touch the working tree"));
		}

		@Test
		@DisplayName("prune drops a remote-tracking ref the remote no longer has")
		void prunesDeletedRemoteBranch() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();
			GitService.pushIn(repository, null, null, false, false);
			git.branchCreate().setName("temporary").call();
			GitService.pushIn(repository, null, "temporary", false, false);
			GitService.fetchIn(repository, null, false);
			assertNotNull(repository.findRef("refs/remotes/origin/temporary"),
				"The remote-tracking ref must exist before it can be pruned");

			remoteGit.branchDelete().setBranchNames("temporary").setForce(true).call();
			GitService.fetchIn(repository, null, true);

			assertFalse(repository.getRefDatabase().hasRefs()
				&& repository.findRef("refs/remotes/origin/temporary") != null,
				"The stale remote-tracking ref must be pruned");
		}

		@Test
		@DisplayName("an unknown remote is reported as FAILED with the remotes to look at, not thrown")
		void unknownRemoteIsReported()
		{
			String result = GitService.fetchIn(repository, "nowhere", false);

			assertAll(
				() -> assertTrue(result.contains("FAILED"), "Should report a failure: " + result),
				() -> assertTrue(result.contains("nowhere"), "Should name the remote: " + result),
				() -> assertTrue(result.contains("gitRemoteList"), "Should say how to find a valid one: " + result));
		}
	}

	@Nested
	@DisplayName("push")
	class Push
	{
		@Test
		@DisplayName("a branch the remote does not have is pushed")
		void pushesNewBranch() throws Exception
		{
			RevCommit local = commitFile("file.txt", "local\n", "local commit");

			String result = GitService.pushIn(repository, null, null, false, false);

			assertAll(
				() -> assertTrue(result.contains("Push PUSHED"), "Should report it pushed: " + result),
				() -> assertEquals(local.getName(), remote.resolve(Constants.R_HEADS + branch).getName(),
					"The remote must now hold the local commit"));
		}

		@Test
		@DisplayName("pushing again with nothing new reports UP_TO_DATE")
		void secondPushIsUpToDate() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			GitService.pushIn(repository, null, null, false, false);

			String result = GitService.pushIn(repository, null, null, false, false);

			assertTrue(result.contains("UP_TO_DATE"), "Should report up to date: " + result);
		}

		@Test
		@DisplayName("setUpstream records the remote branch as the local branch's upstream")
		void setUpstreamRecordsTheTracking() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");

			String result = GitService.pushIn(repository, null, null, true, false);

			StoredConfig config = repository.getConfig();
			assertAll(
				() -> assertTrue(result.contains("upstream"), "Should say it recorded the upstream: " + result),
				() -> assertEquals("origin", config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
					ConfigConstants.CONFIG_KEY_REMOTE), "The remote must be recorded"),
				() -> assertEquals(Constants.R_HEADS + branch, config.getString(
					ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE),
					"The merge ref must be recorded"));
		}

		@Test
		@DisplayName("a non-fast-forward push is REJECTED, and says to pull or force")
		void nonFastForwardIsRejected() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();
			commitOnRemote("remote.txt", "remote\n", "commit made on the remote");
			commitFile("file.txt", "diverged\n", "diverging local commit");

			String result = GitService.pushIn(repository, null, null, false, false);

			assertAll(
				() -> assertTrue(result.contains("REJECTED"), "Should report the rejection: " + result),
				() -> assertTrue(result.contains("gitPull"), "Should say to pull first: " + result),
				() -> assertTrue(result.contains("force"), "Should mention force as the other option: " + result));
		}

		@Test
		@DisplayName("force overwrites the remote branch a normal push was refused on")
		void forceOverwritesTheRemote() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();
			commitOnRemote("remote.txt", "remote\n", "commit made on the remote");
			RevCommit diverged = commitFile("file.txt", "diverged\n", "diverging local commit");

			String result = GitService.pushIn(repository, null, null, false, true);

			assertAll(
				() -> assertTrue(result.contains("PUSHED"), "A forced push must go through: " + result),
				() -> assertEquals(diverged.getName(), remote.resolve(Constants.R_HEADS + branch).getName(),
					"The remote must now hold the local commit"));
		}

		@Test
		@DisplayName("a branch that does not exist locally is refused")
		void refusesUnknownLocalBranch() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");

			IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
				() -> GitService.pushIn(repository, null, "no-such-branch", false, false));
			assertTrue(problem.getMessage().contains("no-such-branch"),
				"Should name the branch: " + problem.getMessage());
		}

		@Test
		@DisplayName("an unknown remote is reported as FAILED, not thrown")
		void unknownRemoteIsReported() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");

			String result = GitService.pushIn(repository, "nowhere", null, false, false);

			assertAll(
				() -> assertTrue(result.contains("FAILED"), "Should report a failure: " + result),
				() -> assertTrue(result.contains("nowhere"), "Should name the remote: " + result));
		}
	}

	@Nested
	@DisplayName("pull")
	class Pull
	{
		@Test
		@DisplayName("a remote commit the local branch does not have fast-forwards into the working tree")
		void fastForwardsRemoteCommits() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();
			RevCommit onRemote = commitOnRemote("remote.txt", "remote\n", "commit made on the remote");

			String result = GitService.pullIn(repository, null, null, false);

			assertAll(
				() -> assertTrue(result.contains("FAST_FORWARD"), "Should report a fast-forward: " + result),
				() -> assertEquals(onRemote.getName(), headSha(), "HEAD must be the remote commit"),
				() -> assertTrue(Files.exists(repository.getWorkTree().toPath().resolve("remote.txt")),
					"The pulled file must be in the working tree"));
		}

		@Test
		@DisplayName("pulling with nothing new reports it is already up to date")
		void alreadyUpToDate() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");
			publishBranch();

			String result = GitService.pullIn(repository, null, null, false);

			assertTrue(result.contains("ALREADY_UP_TO_DATE"), "Should report up to date: " + result);
		}

		@Test
		@DisplayName("a pull that conflicts reports CONFLICTED and names the file")
		void conflictReportsFile() throws Exception
		{
			commitFile("shared.txt", "base\n", "base commit");
			publishBranch();
			commitOnRemote("shared.txt", "from the remote\n", "remote change to the shared line");
			commitFile("shared.txt", "from the local\n", "local change to the shared line");

			String result = GitService.pullIn(repository, null, null, false);

			assertAll(
				() -> assertTrue(result.contains("CONFLICTED"), "Should report the conflict: " + result),
				() -> assertTrue(result.contains("shared.txt"), "Should name the conflicting file: " + result),
				() -> assertTrue(git.status().call().getConflicting().contains("shared.txt"),
					"The file must be left conflicting"));
		}

		@Test
		@DisplayName("pulling with rebase replays the local commit on top of the remote one")
		void rebasesLocalCommits() throws Exception
		{
			commitFile("file.txt", "base\n", "base commit");
			publishBranch();
			RevCommit onRemote = commitOnRemote("remote.txt", "remote\n", "commit made on the remote");
			RevCommit localOnly = commitFile("local.txt", "local\n", "local-only commit");

			String result = GitService.pullIn(repository, null, null, true);

			RevCommit head = repository.parseCommit(repository.resolve(Constants.HEAD));
			assertAll(
				() -> assertTrue(result.contains("REBASED") || result.contains("FAST_FORWARD"),
					"Should report the rebase: " + result),
				() -> assertEquals("local-only commit", head.getShortMessage(),
					"The local commit must be replayed on top"),
				() -> assertEquals(onRemote.getName(), head.getParent(0).getName(),
					"Its parent must be the remote commit"),
				() -> assertFalse(localOnly.getName().equals(head.getName()),
					"A replayed commit gets a new sha"));
		}

		@Test
		@DisplayName("a branch with no configured upstream is refused with what to do next")
		void noUpstreamIsReported() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");

			String result = GitService.pullIn(repository, null, null, false);

			assertAll(
				() -> assertTrue(result.contains("FAILED"), "Should report a failure: " + result),
				() -> assertTrue(result.contains("remoteBranch") || result.contains("setUpstream"),
					"Should say what to do next: " + result));
		}

		@Test
		@DisplayName("an unknown remote is reported as FAILED, not thrown")
		void unknownRemoteIsReported() throws Exception
		{
			commitFile("file.txt", "local\n", "local commit");

			String result = GitService.pullIn(repository, "nowhere", branch, false);

			assertAll(
				() -> assertTrue(result.contains("FAILED"), "Should report a failure: " + result),
				() -> assertTrue(result.contains("nowhere"), "Should name the remote: " + result));
		}
	}
}

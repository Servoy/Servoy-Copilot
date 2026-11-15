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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.McpServerBuiltins;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.servers.ServoyGitServer;
import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyGitServer}'s <em>own</em>
 * logic - without a live Eclipse workspace.
 * <p>
 * {@code ServoyGitServer} is a thin tool wrapper: every {@code @Tool} method
 * forwards to its {@code private final} {@link GitService} collaborator. Its only
 * own logic is the <em>tool-arg -&gt; GitService-call</em> marshalling - the
 * {@code String}-to-{@code boolean} parses ({@code amend}/{@code all}/{@code staged}/
 * {@code ignoreWhitespace}/{@code includeRemote}/{@code force}/{@code squash}/
 * {@code prune}/{@code rebase}/{@code setUpstream}), the {@code gitLog maxCount}
 * {@link Integer#parseInt} (defaulting to 20), the {@code gitDiff} blank-aware
 * revision-vs-working-tree branch selection, and the straight result pass-through.
 * </p>
 * <p>
 * The {@link GitService} collaborator is supplied through the server's package-visible
 * testing constructor {@code ServoyGitServer(GitService)} and is a Mockito mock stubbed
 * only at the boundary. {@code GitService}'s real git behaviour (EGit/JGit against a
 * working tree, the project/repo resolution, and the exceptions it throws for a missing
 * project) is <em>not</em> exercised here - that lives in {@code GitService}'s own
 * temp-repo plainjunit tests and the {@code integrationpde} suite. These tests assert
 * exclusively what the server does with the arguments and with the delegate's result.
 * </p>
 *
 * @since 2026.11
 */
class ServoyGitServerTest
{
	private GitService git;
	private ServoyGitServer server;

	@BeforeEach
	void setUp()
	{
		git = mock(GitService.class);
		server = new ServoyGitServer(git);
	}

	// =======================================================================
	// Annotation / registration / construction (no delegate interaction)
	// =======================================================================

	@Nested
	class AnnotationAndRegistration
	{
		@Test
		@DisplayName("ServoyGitServer carries the @McpServer(name = \"servoy-git\") annotation")
		void hasCorrectAnnotation()
		{
			McpServer ann = ServoyGitServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyGitServer must have @McpServer annotation");
			assertFalse(ann.name().isBlank(), "@McpServer name must not be empty");
			assertEquals("servoy-git", ann.name(), "@McpServer name must be 'servoy-git'");
		}

		@Test
		@DisplayName("ServoyGitServer is registered in McpServerBuiltins")
		void registeredInBuiltins()
		{
			boolean found = false;
			for (Class< ? > cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES)
			{
				if (cls == ServoyGitServer.class)
				{
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyGitServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("every @Tool name on the server is unique")
		void toolNamesAreUnique()
		{
			Set<String> names = new HashSet<>();
			Arrays.stream(ServoyGitServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class))
				.forEach(m -> {
					String name = m.getAnnotation(Tool.class).name();
					assertFalse(names.contains(name), "Duplicate tool name: " + name);
					names.add(name);
				});
			assertTrue(names.size() > 0, "server must expose at least one @Tool");
		}

		@Test
		@DisplayName("the testing constructor wires the supplied GitService into the gitService field")
		void testingConstructorWiresDelegate() throws Exception
		{
			GitService delegate = mock(GitService.class);
			ServoyGitServer s = new ServoyGitServer(delegate);
			Field f = ServoyGitServer.class.getDeclaredField("gitService");
			f.setAccessible(true);
			assertSame(delegate, f.get(s), "constructor must store the supplied GitService");
		}
	}

	// =======================================================================
	// Straight pass-through tools (projectName/repo forwarded verbatim, result unchanged)
	// =======================================================================

	@Nested
	class StraightPassThrough
	{
		@Test
		@DisplayName("gitStatus forwards projectName/repo and returns the delegate result unchanged")
		void gitStatus()
		{
			when(git.getStatus("proj", "repoA")).thenReturn("STATUS");
			assertEquals("STATUS", server.gitStatus("proj", "repoA"));
			verify(git).getStatus("proj", "repoA");
		}

		@Test
		@DisplayName("gitStatus passes a null repo through untouched")
		void gitStatusNullRepo()
		{
			when(git.getStatus(eq("proj"), isNull())).thenReturn("S");
			assertEquals("S", server.gitStatus("proj", null));
			verify(git).getStatus("proj", null);
		}

		@Test
		@DisplayName("gitShow forwards projectName/repo/revision/pathFilter verbatim")
		void gitShow()
		{
			when(git.showCommit("proj", "repoA", "HEAD", "a.js")).thenReturn("SHOW");
			assertEquals("SHOW", server.gitShow("proj", "repoA", "HEAD", "a.js"));
			verify(git).showCommit("proj", "repoA", "HEAD", "a.js");
		}

		@Test
		@DisplayName("gitTagList forwards and passes through")
		void gitTagList()
		{
			when(git.listTags("proj", "repoA")).thenReturn("TAGS");
			assertEquals("TAGS", server.gitTagList("proj", "repoA"));
			verify(git).listTags("proj", "repoA");
		}

		@Test
		@DisplayName("gitTag forwards name/message/revision verbatim")
		void gitTag()
		{
			when(git.createTag("proj", "repoA", "v1", "msg", "HEAD")).thenReturn("TAGGED");
			assertEquals("TAGGED", server.gitTag("proj", "repoA", "v1", "msg", "HEAD"));
			verify(git).createTag("proj", "repoA", "v1", "msg", "HEAD");
		}

		@Test
		@DisplayName("gitDeleteTag forwards the tag name")
		void gitDeleteTag()
		{
			when(git.deleteTag("proj", "repoA", "v1")).thenReturn("DELETED");
			assertEquals("DELETED", server.gitDeleteTag("proj", "repoA", "v1"));
			verify(git).deleteTag("proj", "repoA", "v1");
		}

		@Test
		@DisplayName("gitRemoteList forwards and passes through")
		void gitRemoteList()
		{
			when(git.listRemotes("proj", "repoA")).thenReturn("REMOTES");
			assertEquals("REMOTES", server.gitRemoteList("proj", "repoA"));
			verify(git).listRemotes("proj", "repoA");
		}

		@Test
		@DisplayName("gitDiscardChanges forwards the file pattern")
		void gitDiscardChanges()
		{
			when(git.discardChanges("proj", "repoA", "src/x.js")).thenReturn("DISCARDED");
			assertEquals("DISCARDED", server.gitDiscardChanges("proj", "repoA", "src/x.js"));
			verify(git).discardChanges("proj", "repoA", "src/x.js");
		}

		@Test
		@DisplayName("gitResetToRevision forwards revision/mode verbatim")
		void gitResetToRevision()
		{
			when(git.resetToRevision("proj", "repoA", "HEAD~1", "HARD")).thenReturn("RESET");
			assertEquals("RESET", server.gitResetToRevision("proj", "repoA", "HEAD~1", "HARD"));
			verify(git).resetToRevision("proj", "repoA", "HEAD~1", "HARD");
		}

		@Test
		@DisplayName("gitAdd forwards the file pattern")
		void gitAdd()
		{
			when(git.addFiles("proj", "repoA", ".")).thenReturn("ADDED");
			assertEquals("ADDED", server.gitAdd("proj", "repoA", "."));
			verify(git).addFiles("proj", "repoA", ".");
		}

		@Test
		@DisplayName("gitCreateBranch forwards branchName/startPoint verbatim")
		void gitCreateBranch()
		{
			when(git.createBranch("proj", "repoA", "feature", "main")).thenReturn("CREATED");
			assertEquals("CREATED", server.gitCreateBranch("proj", "repoA", "feature", "main"));
			verify(git).createBranch("proj", "repoA", "feature", "main");
		}

		@Test
		@DisplayName("gitCheckout forwards the branch name")
		void gitCheckout()
		{
			when(git.checkoutBranch("proj", "repoA", "main")).thenReturn("SWITCHED");
			assertEquals("SWITCHED", server.gitCheckout("proj", "repoA", "main"));
			verify(git).checkoutBranch("proj", "repoA", "main");
		}

		@Test
		@DisplayName("gitReset forwards the file pattern to resetFiles")
		void gitReset()
		{
			when(git.resetFiles("proj", "repoA", "a.js")).thenReturn("UNSTAGED");
			assertEquals("UNSTAGED", server.gitReset("proj", "repoA", "a.js"));
			verify(git).resetFiles("proj", "repoA", "a.js");
		}

		@Test
		@DisplayName("gitStash forwards the message")
		void gitStash()
		{
			when(git.stash("proj", "repoA", "wip")).thenReturn("STASHED");
			assertEquals("STASHED", server.gitStash("proj", "repoA", "wip"));
			verify(git).stash("proj", "repoA", "wip");
		}

		@Test
		@DisplayName("gitStashPop forwards and passes through")
		void gitStashPop()
		{
			when(git.stashPop("proj", "repoA")).thenReturn("POPPED");
			assertEquals("POPPED", server.gitStashPop("proj", "repoA"));
			verify(git).stashPop("proj", "repoA");
		}

		@Test
		@DisplayName("gitStashList forwards and passes through")
		void gitStashList()
		{
			when(git.stashList("proj", "repoA")).thenReturn("STASHES");
			assertEquals("STASHES", server.gitStashList("proj", "repoA"));
			verify(git).stashList("proj", "repoA");
		}

		@Test
		@DisplayName("gitListRepositories delegates to listRepositories with no arguments")
		void gitListRepositories()
		{
			when(git.listRepositories()).thenReturn("REPOS");
			assertEquals("REPOS", server.gitListRepositories());
			verify(git).listRepositories();
		}

		@Test
		@DisplayName("gitStagePatch forwards the patch verbatim")
		void gitStagePatch()
		{
			when(git.stagePatch("proj", "repoA", "@@ patch @@")).thenReturn("STAGED");
			assertEquals("STAGED", server.gitStagePatch("proj", "repoA", "@@ patch @@"));
			verify(git).stagePatch("proj", "repoA", "@@ patch @@");
		}

		@Test
		@DisplayName("gitReadFile forwards filePath/revision verbatim")
		void gitReadFile()
		{
			when(git.readFileAtRevision("proj", "repoA", "a.js", "INDEX")).thenReturn("CONTENT");
			assertEquals("CONTENT", server.gitReadFile("proj", "repoA", "a.js", "INDEX"));
			verify(git).readFileAtRevision("proj", "repoA", "a.js", "INDEX");
		}

		@Test
		@DisplayName("gitRemove forwards the file pattern to removeFromIndex")
		void gitRemove()
		{
			when(git.removeFromIndex("proj", "repoA", "a.js")).thenReturn("REMOVED");
			assertEquals("REMOVED", server.gitRemove("proj", "repoA", "a.js"));
			verify(git).removeFromIndex("proj", "repoA", "a.js");
		}

		@Test
		@DisplayName("gitRebase forwards upstream/operation verbatim")
		void gitRebase()
		{
			when(git.rebase("proj", "repoA", "main", "CONTINUE")).thenReturn("REBASED");
			assertEquals("REBASED", server.gitRebase("proj", "repoA", "main", "CONTINUE"));
			verify(git).rebase("proj", "repoA", "main", "CONTINUE");
		}

		@Test
		@DisplayName("gitCherryPick forwards the commits verbatim")
		void gitCherryPick()
		{
			when(git.cherryPick("proj", "repoA", "sha1,sha2")).thenReturn("APPLIED");
			assertEquals("APPLIED", server.gitCherryPick("proj", "repoA", "sha1,sha2"));
			verify(git).cherryPick("proj", "repoA", "sha1,sha2");
		}

		@Test
		@DisplayName("gitRevert forwards the commits verbatim")
		void gitRevert()
		{
			when(git.revert("proj", "repoA", "sha1")).thenReturn("REVERTED");
			assertEquals("REVERTED", server.gitRevert("proj", "repoA", "sha1"));
			verify(git).revert("proj", "repoA", "sha1");
		}

		@Test
		@DisplayName("gitInit forwards the project name to initRepository")
		void gitInit()
		{
			when(git.initRepository("proj")).thenReturn("INITIALISED");
			assertEquals("INITIALISED", server.gitInit("proj"));
			verify(git).initRepository("proj");
		}
	}

	// =======================================================================
	// gitLog - maxCount parse (default 20, parse, NumberFormatException propagation)
	// =======================================================================

	@Nested
	class LogMaxCountParsing
	{
		@Test
		@DisplayName("gitLog defaults maxCount to 20 when the argument is null")
		void defaultsToTwenty()
		{
			when(git.getLog("proj", "repoA", 20, "HEAD", "a.js")).thenReturn("LOG");
			assertEquals("LOG", server.gitLog("proj", "repoA", null, "HEAD", "a.js"));
			verify(git).getLog("proj", "repoA", 20, "HEAD", "a.js");
		}

		@Test
		@DisplayName("gitLog parses the supplied maxCount")
		void parsesSuppliedCount()
		{
			when(git.getLog("proj", "repoA", 5, null, null)).thenReturn("LOG");
			assertEquals("LOG", server.gitLog("proj", "repoA", "5", null, null));
			verify(git).getLog("proj", "repoA", 5, null, null);
		}

		@Test
		@DisplayName("gitLog propagates a NumberFormatException for a non-numeric maxCount and never calls the delegate")
		void rejectsNonNumeric()
		{
			assertThrows(NumberFormatException.class, () -> server.gitLog("proj", "repoA", "lots", null, null));
			verify(git, never()).getLog(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		}
	}

	// =======================================================================
	// gitCommit - amend / all boolean parsing
	// =======================================================================

	@Nested
	class CommitBooleanParsing
	{
		@Test
		@DisplayName("gitCommit defaults amend and all to false when both arguments are null")
		void defaultsBothFalse()
		{
			when(git.commit("proj", "repoA", "msg", false, false)).thenReturn("COMMITTED");
			assertEquals("COMMITTED", server.gitCommit("proj", "repoA", "msg", null, null));
			verify(git).commit("proj", "repoA", "msg", false, false);
		}

		@Test
		@DisplayName("gitCommit parses amend=\"true\" and all=\"true\" to true")
		void parsesBothTrue()
		{
			when(git.commit("proj", "repoA", "msg", true, true)).thenReturn("COMMITTED");
			assertEquals("COMMITTED", server.gitCommit("proj", "repoA", "msg", "true", "true"));
			verify(git).commit("proj", "repoA", "msg", true, true);
		}

		@Test
		@DisplayName("gitCommit treats a non-\"true\" string as false (Boolean.parseBoolean semantics)")
		void nonTrueStringIsFalse()
		{
			when(git.commit("proj", "repoA", "msg", false, false)).thenReturn("COMMITTED");
			assertEquals("COMMITTED", server.gitCommit("proj", "repoA", "msg", "yes", "1"));
			verify(git).commit("proj", "repoA", "msg", false, false);
		}
	}

	// =======================================================================
	// gitDiff - boolean parsing + blank-aware revision-vs-working-tree branch
	// =======================================================================

	@Nested
	class DiffBranchSelection
	{
		@Test
		@DisplayName("gitDiff with no revisions compares index/working-tree via getDiff, parsing staged/ignoreWhitespace")
		void noRevisionsCallsGetDiff()
		{
			when(git.getDiff("proj", "repoA", true, "a.js", true)).thenReturn("DIFF");
			assertEquals("DIFF", server.gitDiff("proj", "repoA", "true", "a.js", "true", null, null));
			verify(git).getDiff("proj", "repoA", true, "a.js", true);
			verify(git, never()).getRevisionDiff(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("gitDiff defaults staged/ignoreWhitespace to false when null")
		void defaultsBooleansFalse()
		{
			when(git.getDiff("proj", "repoA", false, null, false)).thenReturn("DIFF");
			assertEquals("DIFF", server.gitDiff("proj", "repoA", null, null, null, null, null));
			verify(git).getDiff("proj", "repoA", false, null, false);
		}

		@Test
		@DisplayName("gitDiff with a fromRevision routes to getRevisionDiff")
		void fromRevisionCallsRevisionDiff()
		{
			when(git.getRevisionDiff("proj", "repoA", "main", null, "a.js", false)).thenReturn("RDIFF");
			assertEquals("RDIFF", server.gitDiff("proj", "repoA", null, "a.js", null, "main", null));
			verify(git).getRevisionDiff("proj", "repoA", "main", null, "a.js", false);
		}

		@Test
		@DisplayName("gitDiff with only a toRevision routes to getRevisionDiff")
		void toRevisionCallsRevisionDiff()
		{
			when(git.getRevisionDiff("proj", "repoA", null, "HEAD", null, true)).thenReturn("RDIFF");
			assertEquals("RDIFF", server.gitDiff("proj", "repoA", null, null, "true", null, "HEAD"));
			verify(git).getRevisionDiff("proj", "repoA", null, "HEAD", null, true);
		}

		@Test
		@DisplayName("gitDiff treats a BLANK fromRevision as 'no revision' and calls getDiff")
		void blankFromRevisionNotTreatedAsComparison()
		{
			when(git.getDiff("proj", "repoA", false, null, false)).thenReturn("DIFF");
			assertEquals("DIFF", server.gitDiff("proj", "repoA", null, null, null, "   ", null));
			verify(git).getDiff("proj", "repoA", false, null, false);
			verify(git, never()).getRevisionDiff(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyBoolean());
		}
	}

	// =======================================================================
	// gitBranch / gitDeleteBranch - boolean parsing
	// =======================================================================

	@Nested
	class BranchBooleanParsing
	{
		@Test
		@DisplayName("gitBranch defaults includeRemote to false when null")
		void branchDefaultsFalse()
		{
			when(git.listBranches("proj", "repoA", false)).thenReturn("BRANCHES");
			assertEquals("BRANCHES", server.gitBranch("proj", "repoA", null));
			verify(git).listBranches("proj", "repoA", false);
		}

		@Test
		@DisplayName("gitBranch parses includeRemote=\"true\"")
		void branchParsesTrue()
		{
			when(git.listBranches("proj", "repoA", true)).thenReturn("BRANCHES");
			assertEquals("BRANCHES", server.gitBranch("proj", "repoA", "true"));
			verify(git).listBranches("proj", "repoA", true);
		}

		@Test
		@DisplayName("gitDeleteBranch defaults force to false and forwards the branch name")
		void deleteBranchDefaultsFalse()
		{
			when(git.deleteBranch("proj", "repoA", "feature", false)).thenReturn("DELETED");
			assertEquals("DELETED", server.gitDeleteBranch("proj", "repoA", "feature", null));
			verify(git).deleteBranch("proj", "repoA", "feature", false);
		}

		@Test
		@DisplayName("gitDeleteBranch parses force=\"true\"")
		void deleteBranchParsesTrue()
		{
			when(git.deleteBranch("proj", "repoA", "feature", true)).thenReturn("DELETED");
			assertEquals("DELETED", server.gitDeleteBranch("proj", "repoA", "feature", "true"));
			verify(git).deleteBranch("proj", "repoA", "feature", true);
		}
	}

	// =======================================================================
	// gitMerge / gitFetch / gitPull / gitPush - remaining boolean parsing
	// =======================================================================

	@Nested
	class RemoteOperationBooleanParsing
	{
		@Test
		@DisplayName("gitMerge defaults squash to false and forwards ref/fastForward/message")
		void mergeDefaultsSquashFalse()
		{
			when(git.merge("proj", "repoA", "main", "NO_FF", false, "msg")).thenReturn("MERGED");
			assertEquals("MERGED", server.gitMerge("proj", "repoA", "main", "NO_FF", null, "msg"));
			verify(git).merge("proj", "repoA", "main", "NO_FF", false, "msg");
		}

		@Test
		@DisplayName("gitMerge parses squash=\"true\"")
		void mergeParsesSquashTrue()
		{
			when(git.merge("proj", "repoA", "main", null, true, null)).thenReturn("MERGED");
			assertEquals("MERGED", server.gitMerge("proj", "repoA", "main", null, "true", null));
			verify(git).merge("proj", "repoA", "main", null, true, null);
		}

		@Test
		@DisplayName("gitFetch defaults prune to false and forwards the remote")
		void fetchDefaultsPruneFalse()
		{
			when(git.fetch("proj", "repoA", "origin", false)).thenReturn("FETCHED");
			assertEquals("FETCHED", server.gitFetch("proj", "repoA", "origin", null));
			verify(git).fetch("proj", "repoA", "origin", false);
		}

		@Test
		@DisplayName("gitFetch parses prune=\"true\"")
		void fetchParsesPruneTrue()
		{
			when(git.fetch("proj", "repoA", "origin", true)).thenReturn("FETCHED");
			assertEquals("FETCHED", server.gitFetch("proj", "repoA", "origin", "true"));
			verify(git).fetch("proj", "repoA", "origin", true);
		}

		@Test
		@DisplayName("gitPull defaults rebase to false and forwards remote/remoteBranch")
		void pullDefaultsRebaseFalse()
		{
			when(git.pull("proj", "repoA", "origin", "main", false)).thenReturn("PULLED");
			assertEquals("PULLED", server.gitPull("proj", "repoA", "origin", "main", null));
			verify(git).pull("proj", "repoA", "origin", "main", false);
		}

		@Test
		@DisplayName("gitPull parses rebase=\"true\"")
		void pullParsesRebaseTrue()
		{
			when(git.pull("proj", "repoA", "origin", "main", true)).thenReturn("PULLED");
			assertEquals("PULLED", server.gitPull("proj", "repoA", "origin", "main", "true"));
			verify(git).pull("proj", "repoA", "origin", "main", true);
		}

		@Test
		@DisplayName("gitPush defaults setUpstream and force to false and forwards remote/branch")
		void pushDefaultsBothFalse()
		{
			when(git.push("proj", "repoA", "origin", "main", false, false)).thenReturn("PUSHED");
			assertEquals("PUSHED", server.gitPush("proj", "repoA", "origin", "main", null, null));
			verify(git).push("proj", "repoA", "origin", "main", false, false);
		}

		@Test
		@DisplayName("gitPush parses setUpstream=\"true\" and force=\"true\" independently")
		void pushParsesBothTrue()
		{
			when(git.push("proj", "repoA", "origin", "main", true, true)).thenReturn("PUSHED");
			assertEquals("PUSHED", server.gitPush("proj", "repoA", "origin", "main", "true", "true"));
			verify(git).push("proj", "repoA", "origin", "main", true, true);
		}

		@Test
		@DisplayName("gitPush parses setUpstream=\"true\" with force defaulted to false")
		void pushSetUpstreamOnly()
		{
			when(git.push("proj", "repoA", "origin", "main", true, false)).thenReturn("PUSHED");
			assertEquals("PUSHED", server.gitPush("proj", "repoA", "origin", "main", "true", null));
			verify(git).push("proj", "repoA", "origin", "main", true, false);
		}
	}

	// =======================================================================
	// Delegate exceptions propagate unchanged (the server adds no try/catch of its own)
	// =======================================================================

	@Nested
	class DelegateExceptionPropagation
	{
		@Test
		@DisplayName("a RuntimeException thrown by the delegate propagates out of the thin wrapper unchanged")
		void runtimeExceptionPropagates()
		{
			RuntimeException boom = new RuntimeException("Project not found: X");
			when(git.getStatus("missing", null)).thenThrow(boom);
			RuntimeException thrown = assertThrows(RuntimeException.class, () -> server.gitStatus("missing", null));
			assertSame(boom, thrown, "the server must not wrap or swallow the delegate's exception");
		}
	}
}

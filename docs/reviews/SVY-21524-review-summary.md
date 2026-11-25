# Review Summary — SVY-21524: servoy-git gitCommit accepts a non-project name and writes an empty commit

**Risk: ELEVATED** — the empty-commit guard (`setAllowEmpty(false)`, `GitService.java:679`) is unconditional, so it also refuses two legitimate commits that later features build on the same builder: a message-only `amend` (0cb10df7) and concluding a merge/cherry-pick/revert whose conflicts were all resolved to HEAD's version (2113e544). Both get a success-style "Nothing to commit - no staged changes." reply; in the merge case the repository is left mid-merge. Sent back to the author ("Code review problem", 2026-11-25).

Scope reviewed: Servoy-Copilot `master`, commits `334ef86b`, `948be973`, `5f03f0eb`, plus the repo-centric NPE part of `59797c9f` (`projectPrefixOrNull` / `toRepositoryRelativePath`).

Fix Version `2026.12`. Release-target check: **INDETERMINATE**. Servoy-Copilot has no `ClientVersion` (bundle `2026.3.1.qualifier`, its own version scheme); all four commits are on `origin/master`, the only live branch, but nothing maps a branch to 2026.12. To be confirmed: the Copilot build shipping with 2026.12 is cut from master.

Verification basis: code read on HEAD (lines 679/682/690); the JGit 7.5 behaviour for amend, merge-ours and the root commit comes from a scratch probe against the target-platform jar, not from a run through the MCP tools. No tests and no Spotbugs were run during the review.

## Manual test plan

Run in Servoy Developer with the servoy-git MCP server active, on a scratch git repository imported as a workspace project. Also posted on the issue.

**Verifying the fix**
1. On a clean tree, `gitCommit projectName=<proj> message="x"`: expect "Nothing to commit - no staged changes."; `gitLog` shows HEAD unchanged.
2. Change a file, `gitAdd`, `gitCommit`: expect "Committed: <sha>".
3. `gitListRepositories`, then `gitStatus repo=<listed name>` without `projectName`: expect that repository's status.
4. `gitStatus repo=doesNotExist`: expect "No repository matches 'doesNotExist'. Available repositories ...".
5. `gitStatus` with neither argument: expect "No project name given." plus the repository list.
6. svyCloud: `gitStatus projectName:"common"` and `gitCommit projectName:"common"` on a clean tree. No commit may be written; note which repository the status reports.

**Regression checks**
1. Amend reword: commit, then `gitCommit amend=true message="fixed"` with nothing staged. `gitLog` must show the new message.
2. Merge resolved to ours: branches `a` and `b` change the same line; on `a`, `gitMerge b` (CONFLICTED); restore HEAD's version, `gitAdd`, `gitCommit`. The merge must be concluded. Repeat with `gitCherryPick` and `gitRevert`.
3. Root commit: fresh repository, no commits, nothing staged, `gitCommit repo=<it>` must not write a commit.
4. Mixed targets: `gitAdd repo=<repoA> projectName=<project in repoB> filePattern="."`, then `gitStatus repo=<repoA>`; check what was staged.
5. Legacy path: fix steps 1-2 plus one `gitAdd`/`gitReset`/`gitReadFile` round using only `projectName`; behaviour unchanged.
6. Workspace-root repository (as `gitInit` creates) with several solutions: `gitAdd`/`gitCommit` by `repo`, watch for builder activity or delay.

Automated: plain JUnit `com.servoy.eclipse.developer.mcp.plainjunit` (`ServoyGitServerTest`, `GitServiceToolsTest`); PDE `ServoyGitServerIntegrationTest` with launcher `PDEIntegrationTestsBaseCopy-lts_2026` or `-release`.

## Possible improvements / follow-ups

- **Medium (blocking, sent back):** only refuse empty commits in the plain case, e.g. `setAllowEmpty(amend || repository.getRepositoryState() != RepositoryState.SAFE)`, with a plain-JUnit test for amend-reword and for concluding a resolved-to-ours merge.
- **Low:** the guard does not cover the first commit; JGit has no parent to compare against, so an empty root commit is still written. The skill4servoy line "gitCommit never writes an empty commit" (`mcp-git-and-history.md:51`) overstates this.
- **Low:** with both `repo` and `projectName`, `repo` only picks the repository; path prefixing (`resolveWritePaths`, `toRepositoryRelativePath`) and refresh still follow `projectName`. Refuse a mismatch, or ignore `projectName` when `repo` is given.
- **Low:** repo-centric writes run a synchronous `DEPTH_INFINITE` refresh of every project under the work tree. A repository at the workspace root (as `gitInit` creates) means a full-workspace refresh per call. Not measured.
- **Scope question:** `repo` and `gitListRepositories` reach every repository in EGit's `RepositoryCache`, not only those backing workspace projects. Confirm this is intended, or restrict it.
- **Open question:** the project-centric lookup is unchanged, so `gitStatus(projectName:"common")` may still answer with the enclosing repository's status. Only the empty commit is prevented.
- **Documentation drift:** the `getRepositoryByNameOrPath` Javadoc claims workspace-relative paths are accepted (only exact absolute-path match is implemented); the `gitListRepositories` description says "known to the workspace" but lists the whole EGit cache.
- **Separate check, not this case:** `stagePatch` builds `new File(workTree, header.getNewPath())`; a `../` patch header may reach outside the work tree unless JGit rejects it. Pre-existing; SVY-21524 only widens the set of repositories it can run against.
- **Separate check, not this case:** a path-shaped `repo` with different case on Windows falls through to the SVY-21366 `tryResolveByProjectName` fallback; whether that gives an assertion or the "available repositories" hint is unverified.

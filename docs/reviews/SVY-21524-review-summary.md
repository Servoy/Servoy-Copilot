# Review Summary — SVY-21524: servoy-git gitCommit accepts a non-project name and writes an empty commit

**Verdict: PASSED after the fix (`e34025c4`).** The first round was sent back as ELEVATED: the empty-commit guard (`setAllowEmpty(false)`, `GitService.java:679`) was unconditional, so it also refused a message-only `amend` and the concluding commit of a merge/cherry-pick/revert whose conflicts were resolved to HEAD's version. Both gave a success-style "Nothing to commit - no staged changes." and the merge case left the repository mid-merge. The author fixed it in `e34025c4` (Gabor Boros, 2026-11-26) with `setAllowEmpty(amend || repository.getRepositoryState() != RepositoryState.SAFE)`, extracted into `commitIn(...)`, and 4 plain-JUnit tests.

Scope reviewed: Servoy-Copilot `master`, commits `334ef86b`, `948be973`, `5f03f0eb`, the repo-centric NPE part of `59797c9f`, and the fix `e34025c4`.

Fix Version `2026.12`. Release-target check: **INDETERMINATE**. Servoy-Copilot has its own version scheme (bundle `2026.3.1.qualifier`, no `ClientVersion`); all commits are on `origin/master`, the only live branch, but nothing maps a branch to 2026.12. To be confirmed: the Copilot build shipping with 2026.12 is cut from master.

## How the findings were closed

| First-round finding | Result |
|---|---|
| Amend can't reword a commit (Medium) | Fixed, covered by `amendRewordsWithNothingStaged` |
| Merge/cherry-pick/revert resolved to HEAD can't be concluded (Medium) | Fixed, covered for merge and cherry-pick (no test of its own for revert) |
| `repo` reaches every repository in EGit's cache, not only workspace ones | Fixed: `repo`, `gitListRepositories` and the hint now use `workspaceRepositories()` (open projects only) |
| Empty first commit on an unborn branch still written (Low) | Open |
| `repo` + `projectName` for different repositories: `projectName` still drives path prefix and refresh (Low) | Acknowledged by the author, left as is |
| Repo-level mutations refresh every project under the work tree (Low) | Open |
| Documentation drift | Open |

Verification basis:
- The first-round problems were reproduced with a standalone JGit 7.5 program replaying `GitService.commit` (amend and merge/cherry-pick resolved to HEAD refused; the one-line fix made both work).
- Jenkins `developer_ai_plugin` #488 (built from `e34025c4`): all git tests pass, including the 4 new guard tests, `GitServiceBranchingTest`, `ServoyGitServerTest`, `ServoyGitServerIntegrationTest` (16/16) and `GitNestedProjectIntegrationTest` (18/18). The only failure in the build is `FormSpecBehaviourIntegrationTest`, unrelated to git.
- Not run locally after the fix. Local runs of the plain tests failed on 6 line-ending cases (`core.autocrlf=true` on the Windows machine) that pass on Jenkins. Local integration runs were blocked by a shared test-workspace folder.

## Manual test plan

Run in Servoy Developer with the servoy-git MCP server active, on a scratch git repository imported as a workspace project. Also posted on the issue.

**Verifying the fix**
1. On a clean tree, `gitCommit projectName=<proj> message="x"`: expect "Nothing to commit - no staged changes."; `gitLog` shows HEAD unchanged.
2. Change a file, `gitAdd`, `gitCommit`: expect "Committed: <sha>".
3. `gitListRepositories`, then `gitStatus repo=<listed name>` without `projectName`: expect that repository's status.
4. `gitStatus repo=doesNotExist`: expect "No repository matches 'doesNotExist'. Available repositories ...".
5. `gitStatus` with neither argument: expect "No project name given." plus the repository list.
6. svyCloud: `gitStatus projectName:"common"` and `gitCommit projectName:"common"` on a clean tree. No commit may be written; note which repository the status reports. The author says `common` is a module project mapped into the svyCloud repository, so it resolves there.

**Regression checks**
1. Amend reword: commit, then `gitCommit amend=true message="fixed"` with nothing staged. `gitLog` must show the new message.
2. Merge resolved to ours: branches `a` and `b` change the same line; on `a`, `gitMerge b` (CONFLICTED); restore HEAD's version, `gitAdd`, `gitCommit`. The merge must be concluded. Repeat with `gitCherryPick` and `gitRevert` (revert has no automated test).
3. Root commit: fresh repository, no commits, nothing staged, `gitCommit repo=<it>` must not write a commit (known open, see follow-ups).
4. Mixed targets: `gitAdd repo=<repoA> projectName=<project in repoB> filePattern="."`, then `gitStatus repo=<repoA>`; check what was staged.
5. Legacy path: fix steps 1-2 plus one `gitAdd`/`gitReset`/`gitReadFile` round using only `projectName`; behaviour unchanged.
6. Workspace-root repository (as `gitInit` creates) with several solutions: `gitAdd`/`gitCommit` by `repo`, watch for builder activity or delay.
7. Scope of `repo`: a repository that is only in the Git Repositories view and backs no open project must NOT appear in `gitListRepositories` and `repo=<it>` must be refused.

## Possible improvements / follow-ups

- **Low:** the guard does not cover the first commit; JGit has no parent to compare against, so an empty root commit is still written. The skill4servoy line "gitCommit never writes an empty commit" (`mcp-git-and-history.md:51`) overstates this.
- **Low:** with both `repo` and `projectName`, `repo` only picks the repository; path prefixing (`resolveWritePaths`, `toRepositoryRelativePath`) and refresh still follow `projectName`. Refuse a mismatch, or ignore `projectName` when `repo` is given. The author left it as a known sharp edge.
- **Low:** repo-centric writes run a synchronous `DEPTH_INFINITE` refresh of every project under the work tree. A repository at the workspace root (as `gitInit` creates) means a full-workspace refresh per call. Not measured.
- **Test gap:** no test for a revert resolved to HEAD, and none for the first-commit case.
- **Cosmetic:** in `GitService.java` the Javadoc of `getRepositoryByNameOrPath` now sits above `workspaceRepositories()`, with the new Javadoc stacked right after it, so the old comment no longer describes the method below it. The Javadoc's claim that workspace-relative paths are accepted is also still not implemented.
- **Separate check, not this case:** `stagePatch` builds `new File(workTree, header.getNewPath())`; a `../` patch header may reach outside the work tree unless JGit rejects it. Pre-existing; this case only widens the set of repositories it can run against.
- **Separate check, not this case:** a path-shaped `repo` with different case on Windows falls through to the SVY-21366 `tryResolveByProjectName` fallback; whether that gives an assertion or the "available repositories" hint is unverified.
- **Local setup:** the lts_2026 and master test launchers share one test workspace folder (`${workspace_loc}/../tests-pde-integration-base`), so they block each other and fail with "Launch was not registered to run at all". Giving each launcher its own folder would stop that.

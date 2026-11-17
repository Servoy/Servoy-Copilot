# Review Summary — SVY-21507: Workspace keeps refreshing every few seconds

**Risk: MODERATE.** The Eclipse-side fix has no test and was already wrong once (`64059f9`). It has a first-launch gap, and it edits `.project` in every workspace project that has a `.opencode` folder.

**Reviewed scope:** Servoy-Copilot, `master`, commits `cea24b0` and `64059f9`. Fix Version 2026.12 (release-target check: MATCH).

## Manual test plan

**Verifying the fix**
1. Import the git root of a Servoy solution as an Eclipse project. Delete `<root>/.opencode/index`. Start Servoy Developer, activate the solution and open the Servoy AI view. Wait about a minute and confirm the workspace no longer refreshes.
2. Open `.project` and confirm there is exactly one `<filter>` entry for `index` (regex `^\Qindex\E$`) on `.opencode`. Restart the IDE, re-open the AI view and confirm it is still one.
3. Confirm `<root>/.gitignore` has exactly one `/.opencode/index/` line and that existing content and line endings are unchanged.
4. First-launch case: delete the whole `<root>/.opencode`, start the IDE, open the AI view and watch whether the refresh churn appears. Compare with a second session after a restart.

**Regression checks**
1. Import two repos that both have a `.opencode` folder. Activate only one and run `git status` in both. Note whether the other repo's `.project` changed.
2. Use a solution whose `.gitignore` already has `.opencode/` (as written by `NewSolutionWizard`). Check whether a redundant `/.opencode/index/` line is added.
3. Use a solution outside any git repo. Check whether a stray `.gitignore` appears in the project folder.
4. With a populated `.opencode` (including `node_modules`), activate a solution and open the AI view. Watch the Jobs view for "Excluding Servoy AI index from the workspace" and try a save or build at the same time. Note any stall.
5. Check that `.opencode/commands` and `.opencode/sdd` stay visible in the workspace tree. Only `index` should be hidden.

Automated: `OpencodeIndexIgnorerTest` (plainjunit) covers only the `.gitignore` text logic. Nothing tests `ensureResourceFilter`, `addExcludeFilterIfAbsent`, the refresh calls or the Job.

## Possible improvements / follow-ups
- Scope the filter step to the active project, or at least to the project at the resolved git root. Today it touches every workspace project with a `.opencode` folder and persists a timestamp-id filter in `.project`.
- Run the `DEPTH_INFINITE` refresh only when the filter was just added. Today it runs on every `apply()` while holding the workspace-root rule.
- Close the first-launch gap. If `.opencode` does not exist when the setup job calls `apply()`, no filter is added until the view is re-entered or the IDE restarts.
- Treat an existing `.opencode/` or `.opencode` rule in `.gitignore` as already covering the index folder, so no redundant line is added.
- Only write `.gitignore` when the resolved directory really is a git root. With no `.git` found, a new `.gitignore` is created in the project folder.
- Consider appending the missing line instead of truncate-then-write for `.gitignore`.
- Add tests for the Eclipse-side filter and refresh logic. That is where `64059f9` found its bug.
- Fix the stale `OpencodeIndexIgnorer` Javadoc (helpers are `public` since `636c52d`; the filter is added when `.opencode` exists, not `.opencode/index`). Add the class to the AGENTS.md key-classes table.
- Open questions for the author:
  1. Is the intended behaviour "only when the opencode root is a known Eclipse project" (Jira comment) or the current "always write `.gitignore`, filter every project with `.opencode`"?
  2. Has `64059f9` been confirmed in a running Developer, including a first launch with no `.opencode`?
  3. Is it intended that `.project` gains a filter entry in repositories where it is version-controlled?
- Not verified: whether any Servoy resource-change listener does a UI `syncExec` while the job holds the workspace-root rule.

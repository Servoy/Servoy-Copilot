# Spec: SVY-21626 — Update Servoy AI skill pack to use repo-centric servoy-git tools (repo param + gitListRepositories)

## 1. Goal
Teach the shipped Servoy AI skill pack the new repository-centric `servoy-git` MCP contract
introduced by SVY-21524: every git tool now accepts an optional **`repo`** parameter (resolve a
repository directly by name or working-tree path, independent of any Eclipse project), and a new
**`gitListRepositories`** discovery tool exists. Update the two context files
(`mcp-tooling.md`, `tool-addressing.md`) to prefer the `repo`-centric path, document the new tool,
and relax/replace the now-obsolete "`projectName` is a SOLUTION name, never a module subdirectory"
and empty-commit-pitfall rails. `projectName` stays optional and backward-compatible, so the
project-centric guidance remains valid as a fallback — but the primary guidance moves to `repo`.

> **Note:** The edits for this ticket live in the **`skill4servoy`** repository
> (`.opencode-shippable/skills/servoy-agent-shared/context/`), not in Servoy-Copilot. This spec and
> its triage report are kept in the Servoy-Copilot `docs/` folder only because the SDD run was
> anchored in this repo.

## 2. Background

### 2.1 Why this is a documentation-only change
This is a planned follow-up to SVY-21524, not a code bug. The MCP contract already changed on
Servoy-Copilot `master`; the skill pack's prose still describes the older, narrower contract. There
is no runtime defect — the fix is purely to bring the two shipped context markdown files in line
with what the MCP now supports.

### 2.2 The release gate is confirmed OPEN
The ticket carried a hard blocker: the same skill files ship inside Servoy Developer builds
(`SERVOY_SKILLS_ZIP`), and some in-field builds may still carry the OLD git MCP (no `repo` param, no
`gitListRepositories`). Updating the skills before the new MCP shipped would have broken those
builds. **That gate is now confirmed open** — the new `servoy-git` MCP (the `repo` parameter and
`gitListRepositories`) has been released in a shipped Servoy Developer build. The full update the
ticket describes may now be made. This spec therefore follows **Approach 1 (full update)** from the
triage report.

### 2.3 The exact new MCP contract (from the Servoy-Copilot Java reference)
Read from `ServoyGitServer.java` / `GitService.java` on Servoy-Copilot `master` (SVY-21524,
commit `948be97`; empty-commit guard `334ef86`). These are the authoritative facts the skill prose
must now reflect:

**The `repo` parameter** — added to every git tool that resolves a repository. Its exact tool-param
description is:

> "Repository to target directly, by name (its working-tree folder name) or working-tree path,
> independent of any project. When given it takes precedence over projectName; use
> gitListRepositories to discover valid values. Default: resolve from projectName."

Tools that gained `repo` (all with `projectName` relaxed to `required = false`):
`gitStatus`, `gitLog`, `gitAdd`, `gitCommit`, `gitDiff`, `gitBranch`, `gitCreateBranch`,
`gitDeleteBranch`, `gitCheckout`, `gitReset`, `gitStash`, `gitStashPop`, `gitStashList`,
`gitStagePatch`. Each tool's own `projectName` description is now "The Eclipse project name.
Optional when 'repo' is given."

> Note: `gitInit` is the one git tool that still requires `projectName` (it uses it only to identify
> the workspace, then initialises at the workspace root). It did **not** gain a `repo` parameter.

**Resolution semantics** (`GitService.resolveRepository` / `getRepositoryByNameOrPath`):
- If `repo` is non-blank it takes precedence over `projectName`. It is matched against the
  workspace's open repositories: **exact match on name first** (the repository's working-tree folder
  name), **then on the working-tree path** (absolute or workspace-relative, `\`/`/` normalised).
- If `repo` matches no repository by name/path, it falls back to treating the value as an **Eclipse
  project name** and resolving the repository that project is mapped to (SVY-21366). So passing a
  solution name as `repo` still reaches the right working tree instead of being refused.
- Only a target matching nothing at all — or matching **more than one** repository by name — is
  refused. The refusal message lists the available repositories so the caller can pick the real one
  (e.g. `No repository matches '<x>'. Available repositories (pass one as 'repo'): a, b`; or
  `Repository '<x>' is ambiguous - it matches N repositories by name. Use the working-tree path
  instead.`).
- When `repo` is blank/null, the legacy project-centric path runs: resolve the repository from
  `projectName` via its `RepositoryMapping`. The two legacy refusals are unchanged:
  `Project not found: <name>` (the name is wrong) and
  `Project is not mapped to a Git repository: <name>` (the project exists but lives outside any
  repository). Both now also append the available-repositories hint.

**`gitListRepositories`** — new discovery tool, takes **no arguments**. Its tool description:

> "Lists the Git repositories known to the workspace, each with its name, working-tree path and
> current branch. Use this to discover a valid 'repo' target for the other git tools, instead of
> guessing a project name."

Its output is a list like:
```
Git repositories (N):
  <name>  path: <working-tree-path>  branch: <current-branch>
  ...
```

**Empty-commit guard** (`334ef86`) — `gitCommit` now calls `setAllowEmpty(false)`. When nothing is
staged it no longer writes an empty commit; it returns `"Nothing to commit - no staged changes."`
This makes the old "a module subdirectory produces an empty commit" pitfall warning obsolete: a
mis-targeted or empty stage no longer silently creates a bogus commit — it reports that nothing was
committed. (The repository-centric path also makes the "module subdirectory" mistake far less
likely, since `repo` targets the whole repository directly.)

### 2.4 Current prose in the two target files (what changes)

**`servoy-agent-shared/context/mcp-tooling.md`**, section "## Git — servoy-git is the only
guaranteed git path" (≈ lines 649–682):
- Lines 658–659: "Every `servoy-git` tool takes the **Eclipse `projectName`** to resolve the repo
  (one repo, many projects). `gitAdd` reaches the whole workspace…"
- Lines 661–664: the "**Here `projectName` is a SOLUTION name, never a module subdirectory**… A
  module subdirectory is not a project: the call is accepted, stages nothing, and produces an empty
  commit." rail.
- Lines 666–672: "**A valid project is not necessarily IN the repo, and the two refusals mean
  different things.**" — the `Project is not mapped…` vs `Project not found:` explanation.
- Lines 674–682: the git tool table (Working-tree state / Diff / Log / Branch / Stash list / Init /
  Stage-commit rows). No `gitListRepositories` row; no mention of `repo`.

**`servoy-agent-shared/context/tool-addressing.md`**, section about which path goes in which
argument (≈ lines 99–131):
- Line 102: "`servoy-git_*` is the exception — that one wants the solution name."
- Lines 121–123: "`servoy-git_*` is the LOCAL Servoy/Eclipse working tree — never GitHub." (keep —
  unrelated to the repo change).
- Lines 125–131: the rail "**`servoy-git_*` wants a SOLUTION name, and the workspace folder name is
  never one — call `servoy-workspace_listProjects` BEFORE the first call that takes a
  `projectName`.**" including the "never batch a name-taking git call in the same turn as
  `listProjects`" guidance.

## 3. Design

The guiding principle: make `repo` + `gitListRepositories` the **primary** way to target a git
repository, present `projectName` as an optional, still-supported fallback, and remove the two rails
that only existed to work around the project-centric limitations (the "solution name, never a module
subdirectory" rail and the empty-commit pitfall). Keep the prose's tone and density consistent with
the surrounding file (dense, rail-style, backtick-quoted tool names, no fluff).

### 3.1 `mcp-tooling.md` — section 5 (git)

**Replace the project-centric framing (lines 658–659) with repo-centric-first framing.** New prose
must state:
- The preferred way to target a repository is the **`repo`** argument: the repository's name (its
  working-tree folder name) or its working-tree path. `repo` is accepted by every git tool except
  `gitInit`, and when given it takes precedence over `projectName`.
- Discover valid `repo` values with **`gitListRepositories`** (no arguments) — it lists each
  repository's name, working-tree path and current branch. Prefer this over guessing a name.
- `projectName` still works and is optional/backward-compatible: when `repo` is omitted, the tool
  resolves the repository from the Eclipse project the way it always did (one repo, many projects).
  Reusing a `projectName` that `gitStatus` already accepted is still fine.
- `repo` is forgiving: if the value doesn't match a repository name or path, it is tried as a
  project/solution name and resolved through that project's repository mapping — so passing a
  solution name as `repo` still reaches the right working tree. Only a value matching nothing, or
  matching more than one repository by name, is refused (the refusal lists the available
  repositories; for a name clash, use the working-tree path).
- `gitAdd` still reaches the whole working tree (including repo-root `handoff/**` and `docs/**`).

**Delete the "solution name, never a module subdirectory" rail (lines 661–664).** It described a
limitation of the project-centric path that `repo` sidesteps, and its central warning (a module
subdirectory produces an empty commit) is no longer true. Optionally keep a single short sentence
noting that `projectName` (when used) is resolved as an Eclipse project name; do not re-introduce
the module-subdirectory empty-commit claim.

**Rewrite the "two refusals" paragraph (lines 666–672) so it covers both paths.** It must now
explain:
- On the `repo` path: `No repository matches '<x>'.` (the name/path is wrong — pick one from
  `gitListRepositories` or the listed available repositories) and `Repository '<x>' is ambiguous …
  Use the working-tree path instead.` (two repositories share that folder name).
- On the `projectName` path (unchanged): `Project not found: <name>` (the name is wrong) vs
  `Project is not mapped to a Git repository: <name>` (the project exists but lives outside any
  repository — take a different project, or just use `repo`).
- Keep the standing rule that re-issuing the identical call never changes a refusal — the fix is a
  different target, not a retry.

**Replace the empty-commit pitfall wherever it appears** with the new behaviour: `gitCommit` will
not write an empty commit; when nothing is staged it returns `"Nothing to commit - no staged
changes."` Frame this as a safety the tool now gives you, not a pitfall to avoid.

**Extend the git tool table (lines 674–682):**
- Add a row: `| Discover the repositories (name, path, branch) to pick a 'repo' target |
  'servoy-git_gitListRepositories' (no args) |`.
- Update the table's intro / relevant rows so the reader knows every listed tool also accepts `repo`
  (either a one-line note above the table, "every tool below also accepts an optional 'repo'…", or
  by mentioning `repo` in the rows). Keep the `gitInit` row's note that it still needs
  `projectName` and targets the workspace root.

### 3.2 `tool-addressing.md` — the addressing rails

**Line 102** ("`servoy-git_*` is the exception — that one wants the solution name"): replace with
text that says `servoy-git_*` is addressed by **repository**, not by the project-relative
`projectName`/`resourcePath` split used for file tools — preferably via the `repo` argument (a
repository name or working-tree path, from `gitListRepositories`), with `projectName` as an optional
fallback. The point of this line in context is "git is addressed differently from the file readers";
that point stays, only the *how* changes from "solution name" to "repo (or optionally a
project/solution name)".

**Lines 125–131 rail** ("`servoy-git_*` wants a SOLUTION name, and the workspace folder name is
never one — call `servoy-workspace_listProjects` BEFORE…"): rewrite to the repo-centric discovery
flow:
- The discovery call is now **`servoy-git_gitListRepositories`** (no args) — call it once to learn
  the valid `repo` targets (name + working-tree path + branch), then pass one as `repo`. This
  replaces the "must call `servoy-workspace_listProjects` first and guess a solution name" guidance.
- Keep the backward-compatible note: if you target by `projectName` instead, that name must be an
  Eclipse project (from `servoy-workspace_listProjects`), not the workspace folder you're standing
  in — but prefer `repo`, which also accepts a solution name as a fallback and lists what's available
  on a miss.
- Preserve the general batching rule in spirit: a target you only learn from a discovery call
  (`gitListRepositories` or `listProjects`) must not be guessed and batched in the same turn as that
  discovery call.

**Keep lines 121–123 (the "never call it GitHub" rail) unchanged** — it is independent of this
change.

### 3.3 Consistency sweep
After editing both files, grep the whole `servoy-agent-shared/` skill tree (and any other skill that
references `servoy-git`) for the stale phrasings so none is left contradicting the new guidance:
- "solution name, never a module subdirectory" / "module subdirectory" + "empty commit"
- "empty commit" / "produces an empty commit"
- "servoy-git" + "SOLUTION name" / "wants the solution name"
- any claim that every servoy-git tool "takes the Eclipse `projectName`"

Any additional hit in another context file gets the same repo-centric correction. (The ticket names
only these two files, but the sweep guards against a stray duplicate.)

## 4. Implementation plan
Both files are plain markdown in `skill4servoy` — edit with the generic `read`/`edit`/`write`/`grep`
tools (not the Eclipse/JDT MCP, which does not apply here).

1. **`.opencode-shippable/skills/servoy-agent-shared/context/mcp-tooling.md`**, section "## Git —
   servoy-git is the only guaranteed git path":
   1. Rewrite the framing paragraph (≈ lines 658–659) to lead with `repo` + `gitListRepositories` as
      the preferred targeting, with `projectName` as an optional backward-compatible fallback
      (§3.1). Include the exact `repo` semantics (name or working-tree path; takes precedence over
      `projectName`; solution-name fallback; ambiguity → working-tree path).
   2. Delete the "**Here `projectName` is a SOLUTION name, never a module subdirectory**…empty
      commit" rail (≈ lines 661–664). Optionally keep a one-line note that `projectName`, when used,
      is an Eclipse project name.
   3. Rewrite the "two refusals" paragraph (≈ lines 666–672) to cover both the `repo` refusals
      (`No repository matches` / `ambiguous … use the working-tree path`) and the unchanged
      `projectName` refusals (`Project not found` / `Project is not mapped to a Git repository`).
   4. Replace any "empty commit" pitfall wording with the new guard behaviour: `gitCommit` returns
      `"Nothing to commit - no staged changes."` and never writes an empty commit.
   5. In the tool table (≈ lines 674–682): add a `gitListRepositories` row, and note (intro line or
      per-row) that every listed tool also accepts an optional `repo`; keep the `gitInit` row noting
      it still requires `projectName` and has no `repo`.
2. **`.opencode-shippable/skills/servoy-agent-shared/context/tool-addressing.md`**:
   1. Edit line ≈102 so `servoy-git_*` is described as addressed by repository (`repo` preferred,
      `projectName` optional fallback), not "wants the solution name".
   2. Rewrite the rail at ≈ lines 125–131 to the `gitListRepositories`-first discovery flow, keeping
      the backward-compatible `projectName` note and the "don't batch a discovered target with its
      discovery call" guidance.
   3. Leave the "never GitHub" rail (≈ lines 121–123) untouched.
3. **Consistency sweep (§3.3):** `grep` the skill tree for the stale phrasings listed in §3.3 and
   apply the same correction to any stray hit.
4. **Self-check:** re-read both edited sections end to end to confirm the prose is internally
   consistent (no leftover "solution name, never a module subdirectory"; no empty-commit pitfall;
   `gitListRepositories` and `repo` documented; `projectName` presented as optional/backward
   compatible), and that tone/formatting matches the surrounding file.

## 5. Acceptance criteria
- [ ] `mcp-tooling.md` section 5 documents the optional **`repo`** parameter (name or working-tree
      path; precedence over `projectName`; solution-name fallback; ambiguity resolved via
      working-tree path) as the preferred way to target a git repository.
- [ ] `mcp-tooling.md` documents **`gitListRepositories`** (no args; lists name + working-tree path +
      current branch) as the discovery tool for valid `repo` targets, including a row in the git tool
      table.
- [ ] The "`projectName` is a SOLUTION name, never a module subdirectory … produces an empty commit"
      rail is removed from `mcp-tooling.md`.
- [ ] The empty-commit **pitfall** wording is replaced by the new guard behaviour (`gitCommit`
      returns `"Nothing to commit - no staged changes."` and writes no empty commit) wherever it
      appeared.
- [ ] `mcp-tooling.md`'s two-refusals guidance covers both the `repo` path
      (`No repository matches` / ambiguous-name) and the unchanged `projectName` path
      (`Project not found` / `Project is not mapped to a Git repository`).
- [ ] `tool-addressing.md` line ≈102 no longer says `servoy-git_*` "wants the solution name"; it
      describes repository-based addressing (`repo` preferred, `projectName` optional).
- [ ] `tool-addressing.md`'s discovery rail (≈125–131) leads with `servoy-git_gitListRepositories`
      instead of requiring `servoy-workspace_listProjects` + a guessed solution name, while keeping a
      backward-compatible `projectName` note.
- [ ] `projectName` is consistently presented as **optional and backward-compatible**, not removed —
      nothing in either file implies old `projectName`-based calls stop working.
- [ ] A grep of `servoy-agent-shared/` finds no remaining "solution name, never a module
      subdirectory", "produces an empty commit" pitfall, or "every servoy-git tool takes the Eclipse
      projectName" phrasings.
- [ ] The "never call it GitHub" rail in `tool-addressing.md` is unchanged.

## 6. Out of scope
- Any change to the Servoy-Copilot Java MCP code (`ServoyGitServer.java` / `GitService.java`) — the
  contract already shipped; this ticket is documentation only.
- `gitInit` gaining a `repo` parameter — it did not, and its guidance stays project/workspace-root
  based.
- Reworking non-git sections of `mcp-tooling.md` / `tool-addressing.md`, or any skill file that does
  not reference `servoy-git` (beyond the targeted consistency sweep).
- Bumping any skill-pack version/manifest or build artefact (not required by the ticket and not part
  of the doc edit).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Keep a one-line residual note that `projectName` (when used) must be an Eclipse project name, or drop project-name guidance from the body entirely and leave it only in the tool table? Spec assumes: keep a short backward-compatible note. | Reviewer | Assumption noted |
| The ticket description cites commit `8ecd167` / empty-commit fix `732e3d3`; the triage (and the Servoy-Copilot `master` reference actually read) cite `948be97` / `334ef86`. The semantics are identical; the spec documents the behaviour, not the sha. Confirm no sha needs to appear in the shipped prose (none currently does). | Reviewer | Assumption noted — no sha in skill prose |

# AssistAI upstream sync (SVY-21303)

Keeps the ported MCP service files aligned with the open-source AssistAI project,
in **both** directions:

- **upstream → Servoy** (pull in) — `assistai-sync` skill / `/assistai-sync`.
- **Servoy → upstream** (contribute back, draft PRs) — `assistai-contribute`
  skill / `/assistai-contribute`.

Both skills are **repo-local to Servoy-Copilot** — they live in `skills/` here
(picked up by the `skills.paths` entry in `opencode.json`, the same way
`skills/update-opencode` is), not in the global `servoy-eclipse/skills/`
collection, because everything they touch is inside this repository. There is
nothing to install: they are available whenever this repo is open.

See those skills for the full process. This file is the shared source of truth:
the mapping, the deliberate port differences, the two baselines, the logs, and
the ledgers.

- **Upstream (PR target):** https://github.com/gradusnikov/eclipse-chatgpt-plugin (branch `main`, MIT)
- **Servoy fork (contribute branches):** https://github.com/Servoy/eclipse-chatgpt-plugin
- **Upstream service dir:** `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services/`
- **Servoy service dir:** `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/`

## How a sync behaves (the core rule)

A sync is a **complete catch-up**, not a sampling. Upstream is JDT/Java-coupled
and the Servoy ports are JDT-free — and some ports have structurally diverged —
so an upstream change often does **not** apply cleanly. **Adapting it is the work
of the sync, not a reason to postpone it.** Every commit in range ends the run
applied, adapted-and-applied, or resolved as not-applicable / already-have with
the reason recorded; then the baseline advances to the newest commit handled, so
the next run starts cleanly after it. Nothing is carried forward as "pending".

Standing constraint: **do not restructure existing, working, Servoy-tested logic
just for parity with upstream.** Additive upstream changes are preferred; a change
that would require rewriting working code is raised with the user, and if declined
it is recorded under "Known divergences" as a conscious decision.

## Last synced upstream commit: 6e829a8ac2e47ad816bfb869dc7c20af451b0d3c (2026-05-16)

> **This is the real starting point for the first sync**, not a recent bootstrap.
> It is the upstream commit contemporaneous with the original port (Servoy
> `14afbef`, 2026-05-18, "SVY-21012 Tool & service refactoring"), so the first
> `/assistai-sync` run reviews everything upstream has done to the mapped files
> since the ports were created — **33 commits** as of 2026-11-09 (upstream tip
> `3218e3b`). Expect that first run to be a substantial session.
>
> A previous bootstrap had set this to upstream `65e4aec` (2026-10-09), which
> skipped all the history the case actually asked to reconcile; it has been
> corrected.

## Last contribute baseline: 14afbef (2026-05-18)

> The Servoy commit from which `/assistai-contribute` looks for fixes to propose
> upstream — the commit that first added the ported files, so the first run
> considers every Servoy-origin fix since the port. Candidates exclude anything
> the ported-changes ledger shows came *from* upstream, and anything already in
> the contributed ledger.
>
> A previous bootstrap recorded `5866134`, which was **orphaned by a rebase** (it
> is no longer an ancestor of `master`); both skills now validate a baseline is
> reachable before using it.

## File mapping

| Servoy file | Upstream AssistAI class |
|---|---|
| CodeEditingService.java | CodeEditingService.java |
| WorkspaceService.java | ResourceService.java |
| ProjectService.java | ProjectService.java |
| GitService.java | GitService.java |
| LocalHistoryService.java | LocalHistoryService.java |
| MarkdownService.java | MarkdownService.java |
| IdeStateService.java | ConsoleService.java + EditorService.java (merged) |

## Deliberate port differences (never reintroduce)

- No JDT dependency (no Java refactoring / formatter / organize-imports / Java-nature detection).
- No AiIgnoreService / .aiignore checks (access control is the MCP Bearer token).
- No UISynchronize / editor refresh (the Servoy MCP server is headless).
- Keep Servoy-only additions (e.g. WorkspaceService.readProjectResource populates ServoyResourceCache).

## Ported-changes ledger (upstream ↔ Servoy)

Both sides of every change actually applied or adapted, so it is traceable and
`/assistai-contribute` never proposes it back upstream. Matching on this ledger
is what prevents the echo loop — the sync log alone records only upstream SHAs.

| Upstream SHA | Servoy SHA | File(s) | Resolution |
|---|---|---|---|
| `629fdf2` | `d6e62c7` | GitService.java | **adapted** — per-repository `ReentrantLock` added to 9 mutating methods (our repo-centric overloads; lock released before `refreshAfterChange`). Covers 3 methods upstream did not have at that commit. |
| `f2f162f` | `d6e62c7` | GitService.java, ServoyGitServer.java | **adapted** — `getDiff` moved onto a `DiffFormatter` (the only way to express `ignoreWhitespace`), gained `pathFilter` + `ignoreWhitespace`, and both sides of the comparison are now explicit. Algorithm extracted as `renderDiff(Repository, ...)` so a plain test can drive the real code; `GitServiceDiffTest` was rewired off its own replica and extended to 22 tests. |
| `07d6f07` | `d6e62c7` | GitService.java, ServoyGitServer.java | **applied** — `readFileAtRevision` + new `gitReadFile` tool; reads HEAD/branch/commit/`INDEX` without touching the working tree, with a 5 MiB cap and binary / path-traversal guards. |
| `a97fdcd` | `d6e62c7` | GitService.java, ServoyGitServer.java | **applied** — `removeFromIndex` + new `gitRemove` tool (`git rm --cached`) via `DirCacheEditor.DeletePath`, under the new repository lock; `"."` means every tracked file. |
| `b720591` | `_pending_` | WriteAccessValidator.java (new), CodeEditingService.java | **adapted** — new `WriteAccessValidator` asks the team provider to check out read-only files (`IWorkspace.validateEdit`) before a write, so an editing tool no longer fails opaquely under a pessimistic VCS (Perforce / ClearCase / SVN needs-lock). All 8 write paths guarded; guarding the shared `writeFile` covers insert / replaceString / format at once. A refusal throws per our prose convention instead of upstream's `EditResult.rejected(... DiagnosticCode.READ_ONLY_RESOURCE ...)`, which needs the structured model we declined. |
| `aca70c0` (part 1: the path bug) | `1a9b722` | GitService.java | **adapted, partial** — `gitAdd`/`gitReset` handed the caller's pattern straight to JGit, which reads a pathspec from the REPOSITORY root while every tool and `gitStatus` use project-relative paths: for a project under `bundles/` a path copied from status matched nothing and still reported success. Both now resolve through `resolveProjectPaths` (the renamed `resolveDiffPaths`), where `"."` means the project's own prefix rather than the repository root. |
| `aca70c0` (part 2: the safe/local tools) | `_pending_` | GitService.java, ServoyGitServer.java | **adapted, partial** — 8 new tools: `gitShow`, `gitTagList`, `gitTag`, `gitDeleteTag`, `gitRemoteList`, `gitDiscardChanges`, `gitResetToRevision` (plus `gitReadFile`/`gitRemove` in `d6e62c7`), and 3 widened: `gitLog` (+revision, +pathFilter), `gitCommit` (+amend, +all), `gitDiff` (+fromRevision, +toRevision). Git surface 18 → 26 tools. Covered by the new `GitServiceToolsTest` (27 tests) through extracted repository-only cores. `gitDiscardChanges`'s description leads with DESTRUCTIVE AND IRREVERSIBLE and points at `gitStash`. **Part 3 (remote + history-rewriting: `gitMerge`, `gitRebase`, `gitCherryPick`, `gitRevert`, `gitFetch`, `gitPull`, `gitPush`, plus the `org.eclipse.equinox.security` manifest dependency and upstream's two PDE test classes) is still open** — so the baseline stays at `6e829a8`. |

## Known divergences (upstream change deliberately NOT adopted)

Where our implementation differs structurally and adopting upstream's version
would mean rewriting working, tested Servoy code. A conscious decision, not an
omission.

| Upstream SHA | What it changes | Why not adopted |
|---|---|---|
| `5a8e07b` | Adds a line-ending/trailing-whitespace-tolerant fuzzy fallback to `replaceString`, built on upstream's offset-based `FindReplaceDocumentAdapter` + `TextEditRequest`/`ContentRange`/`applyTextEdits` machinery. | Servoy's `CodeEditingService.replaceStringInFile` is a **different implementation** — line-based, using `rangeText.contains(oldString)` + `String.replace` over a `List<String>`. None of the upstream machinery exists here, so adopting the fix means rewriting a working, tested method. The underlying brittleness (CRLF / trailing whitespace) does apply to our version, so this is worth revisiting as its own change — but not as a silent rewrite during a sync. |
| `a6dc32d` (and `663abce`, `7219868`, which build on it) | Converts **107 tools across ten servers** from prose strings to records serialized as MCP `structuredContent` with a generated `outputSchema`; introduces `EditResult`, `ResourceReadResult`, `ContentRange`, `Diagnostic`/`DiagnosticCode`, `ResourceVersion`, `UnifiedDiffs`, `LineOffsets`, Jackson plumbing and a generated API doc with a drift test. 153 files, +26,828/−8,818. `663abce` then omits a diff that merely echoes the caller's own edit; `7219868` adds an `EditSynchronization` barrier after every write. | **It is a breaking change for every consumer of our MCP server.** The return shape changes (clients reading `content[0].text` get something else) and so does error signalling — our tools return `"Error: …"` as a *successful* response today, which this converts to a `status` + `Diagnostic`. The embedded opencode CLI, the Angular chat UI and any external agent pointed at our endpoints would all need adapting, and our own `AGENTS.md` and `McpToolParamValidationTest` encode the prose behaviour. Upstream's design is genuinely better engineering (their point that a client cannot detect our `"Error: …"` failures is a real latent bug here), so this is a cost/breakage decision, not a disagreement. If it is ever wanted, the sane path is incremental — one server at a time, or `structuredContent` added *alongside* the existing text — not a 153-file big bang. |

## Skipped — revisit

Commits the user consciously chose to postpone (needs a design decision / its own
case). Normally empty: a sync resolves everything.

| Upstream SHA | Why postponed |
|---|---|
| _(none)_ | |

## Sync log (upstream → Servoy)

- 2026-11-12 — **first real sync, part 3: `aca70c0`'s safe/local tools.** 8 new tools and 3 widened ones (see the ledger), git surface 18 → 26. Seven repository-only cores were extracted (`renderTagList`, `createTagIn`, `deleteTagIn`, `renderRemoteList`, `renderCommit`, `discardChangesIn`, `resetToRevisionIn`, `renderRevisionDiff`) so the new `GitServiceToolsTest` (27 tests) drives the real code without a workbench. Whole plain-JUnit suite: 1505/1505. Remaining open: `aca70c0`'s remote and history-rewriting tools.
- 2026-11-12 — **first real sync, part 2.** Resolved 4 more commits, leaving only `aca70c0`'s new-tool half open:
  - **`3536ca6` already-have** — it is the commit that *introduced* `applyPatch` upstream (validate context before writing, preserve the line delimiter, undo backup). We have all of it and more: SVY-21472 added tiered matching and a full-file fallback, and our undo is Eclipse Local History rather than upstream's in-memory deque, so it survives a restart.
  - **`a6dc32d` divergence** (and with it `663abce` + `7219868`, which build on it) — see Known divergences.
  - **`b720591` adapted** — write-access guard, see the ported-changes ledger.
  - **`aca70c0` partially adapted** — its path-resolution bug fix is in; its ~15 new git tools (`gitShow`, `gitTag*`, `gitRemoteList`, `gitDiscardChanges`, `gitResetToRevision`, `gitMerge`, `gitRebase`, `gitCherryPick`, `gitRevert`, `gitFetch`, `gitPull`, `gitPush`, plus `gitCommit` amend/all and `gitLog`/`gitDiff` revision support) are agreed to be ported as a second pass, the remote and history-rewriting ones with their tests.
  - **Bugs found and fixed along the way** (not upstream commits, but inherited defects the review surfaced): `stagePatch` destroyed the caller's uncommitted work when a patch failed to apply (the restore sat inside the `try` and the `catch` only rethrew — upstream named this in `a6dc32d`'s message), and the same method left a phantom staged deletion in the index because JGit's `apply()` partially mutates before throwing. Both fixed and covered by the new `GitServiceStagePatchTest` (4 tests).
- 2026-11-12 — **first real sync, part 1 of the `6e829a8..3218e3b` range (33 commits).** Resolved 25 of them:
  - **18 not-applicable** — JDT/Java-only or stripped territory: `8842151` (openProject tool), `6b611d0`, `edb25d0` (AiIgnoreService), `1accf8c` (extract type), `787065c` + `a607329` (organize imports), `8e09fb9` (MCP image content), `aa9d86f`/`a86690b`/`c583601` (upstream test runners), `40b6ad4` + `5ddaa2f` (Eclipse/Java formatter), `5e5d376` (headless test output), `3c8a9c2` (editor adapter chain), `43fc37b` (move type), `9b48a1d` (rename Java element), `7a54b38` (change method signature), `ab32be7` (merge of `b720591`, no distinct content).
  - **3 already-have** — `f1c38ce` + `44b714d` (applyPatch robustness: we did the equivalent ourselves in SVY-21472, with tiered matching and a full-file fallback), `5533694` (charset on replace: we already use `file.getCharset()` throughout), `5731cf9` (encoding + line endings: we already have `detectDominantLineEnding`/`endsWithNewline`).
  - **4 applied/adapted** — `629fdf2`, `f2f162f`, `07d6f07`, `a97fdcd` (see the ported-changes ledger). Whole plain-JUnit suite green: 1467/1467.
  - **1 divergence** — `5a8e07b` (see Known divergences).
  - **Still open: 6 commits** — `aca70c0`, `3536ca6`, `7219868`, `a6dc32d`, `663abce`, `b720591`. The baseline therefore stays at `6e829a8` until they are resolved; a sync advances it only once every commit in range is handled.
- 2026-11-09 — **baseline corrected** to `6e829a8` (2026-05-16, the upstream commit contemporaneous with the port) after review found the previous bootstrap at `65e4aec` (2026-10-09) skipped all the history the case asked to reconcile. No code synced yet; 33 mapped-file commits are now in range for the first real run. Recorded `5a8e07b` as a known divergence from the upfront assessment.
- 2026-10-09 — baseline bootstrapped at `65e4aec` (upstream tip at the time); no code changes. **Superseded** — see above.

## Contribute log (Servoy → upstream)

- 2026-11-09 — contribute baseline corrected to `14afbef` (2026-05-18, the port commit); the previous value `5866134` had been orphaned by a rebase. No PRs opened yet.
- 2026-10-09 — contribute baseline bootstrapped at `5866134` (Copilot `master` HEAD at the time); no PRs. **Superseded** — orphaned by rebase.

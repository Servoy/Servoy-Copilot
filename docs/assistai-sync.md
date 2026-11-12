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
| _(none yet — no sync has applied anything)_ | | | |

## Known divergences (upstream change deliberately NOT adopted)

Where our implementation differs structurally and adopting upstream's version
would mean rewriting working, tested Servoy code. A conscious decision, not an
omission.

| Upstream SHA | What it changes | Why not adopted |
|---|---|---|
| `5a8e07b` | Adds a line-ending/trailing-whitespace-tolerant fuzzy fallback to `replaceString`, built on upstream's offset-based `FindReplaceDocumentAdapter` + `TextEditRequest`/`ContentRange`/`applyTextEdits` machinery. | Servoy's `CodeEditingService.replaceStringInFile` is a **different implementation** — line-based, using `rangeText.contains(oldString)` + `String.replace` over a `List<String>`. None of the upstream machinery exists here, so adopting the fix means rewriting a working, tested method. The underlying brittleness (CRLF / trailing whitespace) does apply to our version, so this is worth revisiting as its own change — but not as a silent rewrite during a sync. |

## Skipped — revisit

Commits the user consciously chose to postpone (needs a design decision / its own
case). Normally empty: a sync resolves everything.

| Upstream SHA | Why postponed |
|---|---|
| _(none)_ | |

## Sync log (upstream → Servoy)

- 2026-11-09 — **baseline corrected** to `6e829a8` (2026-05-16, the upstream commit contemporaneous with the port) after review found the previous bootstrap at `65e4aec` (2026-10-09) skipped all the history the case asked to reconcile. No code synced yet; 33 mapped-file commits are now in range for the first real run. Recorded `5a8e07b` as a known divergence from the upfront assessment.
- 2026-10-09 — baseline bootstrapped at `65e4aec` (upstream tip at the time); no code changes. **Superseded** — see above.

## Contribute log (Servoy → upstream)

- 2026-11-09 — contribute baseline corrected to `14afbef` (2026-05-18, the port commit); the previous value `5866134` had been orphaned by a rebase. No PRs opened yet.
- 2026-10-09 — contribute baseline bootstrapped at `5866134` (Copilot `master` HEAD at the time); no PRs. **Superseded** — orphaned by rebase.

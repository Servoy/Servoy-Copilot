---
name: assistai-sync
description: "Use when syncing the Servoy Copilot MCP bundle's ported files with upstream changes from the open-source AssistAI project (github.com/gradusnikov/eclipse-chatgpt-plugin). Direction: upstream -> Servoy (the reverse is the assistai-contribute skill). Fetches every AssistAI commit since the recorded baseline and resolves ALL of them in one run - applying the ones that fit and adapting the ones that do not, since upstream is JDT/Java-coupled and the Servoy ports are not - then advances the baseline to the tip. Triggered by 'assistai sync', 'sync assistai', 'update ported files', 'check assistai upstream', or '/assistai-sync'. For SVY-21303."
---

# AssistAI Upstream Sync (one-way: upstream → Servoy)

You are the **orchestrator** for keeping the Servoy Copilot MCP bundle's *ported*
files in sync with the upstream open-source **AssistAI** project. The Servoy
`com.servoy.eclipse.developer.mcp` bundle was partly ported from AssistAI; that
upstream repo gets frequent commits, and this skill periodically pulls relevant
upstream changes into the Servoy ports.

**This skill's direction is upstream → Servoy.** The reverse direction (turning
Servoy-origin fixes into draft pull requests on AssistAI) is handled by the
separate **`assistai-contribute`** skill / `/assistai-contribute` command — do
not open PRs on the upstream repo from here.

## Core rule: a sync resolves EVERY commit, leaving nothing pending

A sync is a **complete catch-up**, not a sampling. Upstream is JDT/Java-coupled
and the Servoy ports are JDT-free, so an upstream change often does **not** apply
cleanly — **adapting it is the work of the sync, not a reason to postpone it**.
For every upstream commit in range, by the end of the run it is either:

- **applied** — ported as-is (it fitted), or
- **adapted and applied** — rewritten to fit the Servoy port, or
- **resolved as not-applicable / already-have** — with the reason recorded.

There is no "pending" or "deferred" bucket carried into the next run. Once every
commit in range is resolved, the baseline advances to the **newest upstream
commit handled** (normally `origin/main`'s tip), so the next sync starts cleanly
after it. Syncs are run rarely, just to keep up, so each one is expected to be a
substantial review session — that is normal and correct.

> **Only escape hatch:** if the user *consciously* chooses to skip one commit
> (e.g. it needs a Servoy design decision or its own case), record it under
> **"Skipped — revisit"** in the tracking file with the reason, so it is not lost.
> Do not create skips on your own initiative; adapt instead.

## Upstream

- **Repo:** `https://github.com/gradusnikov/eclipse-chatgpt-plugin` (AssistAI, MIT
  licensed — porting changes in is license-clean).
- **Default branch:** `main`.
- **Service source directory (upstream):**
  `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services/`

## The ported files and their upstream counterparts

All ported files live in the Servoy Copilot repo under
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/`.
The authoritative mapping and the recorded baseline SHA live in the **tracking
file** `docs/assistai-sync.md` in the Servoy Copilot repo — always read it first
and treat it as the source of truth (this skill only describes the process).

The current mapping (confirmed against upstream `main`):

| Servoy file | Upstream AssistAI class |
|---|---|
| `CodeEditingService.java` | `CodeEditingService.java` |
| `WorkspaceService.java` | `ResourceService.java` |
| `ProjectService.java` | `ProjectService.java` |
| `GitService.java` | `GitService.java` |
| `LocalHistoryService.java` | `LocalHistoryService.java` |
| `MarkdownService.java` | `MarkdownService.java` |
| `IdeStateService.java` | `ConsoleService.java` + `EditorService.java` (merged) |

> If `docs/assistai-sync.md` is missing in the Copilot repo, Phase 1 bootstraps
> it. If the upstream directory layout or a mapping has changed, update
> `docs/assistai-sync.md` AND the table above in the same run.

## The deliberate port differences (must survive every sync)

The Servoy ports intentionally dropped Java/JDT coupling and AssistAI's own
access layer. **When porting an upstream change in, re-apply these differences —
never reintroduce what was stripped:**

- **No JDT dependency** — no Java refactoring, no Java code formatter, no
  organize-imports, no Java-nature detection. AssistAI's Java-specific behaviour
  is replaced with generic Eclipse-resource behaviour.
- **No `AiIgnoreService` / `.aiignore` access checks** — access control is at the
  MCP Bearer-token layer in Servoy, so upstream `AiIgnoreService` calls are
  omitted.
- **No `UISynchronize` / editor refresh** — the Servoy MCP server runs headless,
  so upstream editor-UI refresh code is omitted.
- **Servoy additions kept** — e.g. `WorkspaceService.readProjectResource`
  populates `ServoyResourceCache` as a side-effect; preserve such Servoy-only
  additions.

Each ported file documents its own differences in its class Javadoc
(`Ported from AssistAI's {...}. Differences: ...`). Read that header before
editing and keep it accurate.

## Design principles

- **Resolve everything (see the core rule above).** Every commit in range ends
  the run applied, adapted-and-applied, or resolved with a recorded reason.
- **Review before apply.** Never apply an upstream change without showing the
  user the upstream diff and getting approval. Each upstream commit is resolved
  as one of: *applies-cleanly* / *needs-adaptation* / *not-applicable* /
  *already-have*.
- **Adapt, don't skip.** Upstream is JDT/Java-coupled; our ports are not, and
  some ports have **structurally diverged** from upstream (different
  implementation of the same method). "It does not apply cleanly" means *rewrite
  it to fit*, within the constraint below.
- **Do not rewrite working Servoy code for cosmetic parity.** The user's standing
  rule: avoid introducing regressions. Prefer **additive** upstream changes (new
  capability, new guard) which carry little risk. When adopting an upstream
  change would mean **restructuring existing, working, Servoy-tested logic**, say
  so plainly, show the cost, and let the user decide whether the fix is worth the
  rewrite — do not do it silently. If the user declines, it becomes a recorded
  **divergence** (see the tracking file), not a silent omission.
- **No silent reintroduction.** Dropping of JDT/AiIgnore/editor-UI code is
  deliberate — if an upstream change is entirely such code, resolve it
  *not-applicable* (record why).
- **Prefer Eclipse MCP tools** for all Java edits (per the repo AGENTS.md):
  `eclipse-coder_*` to edit, then the compile loop with `eclipse-ide_*`.
- **Clone upstream to a temp dir** — never add it as a sibling checkout or a
  submodule. Use `/tmp/opencode/assistai-upstream` (on Windows/PowerShell use the
  OS temp dir, e.g. `$env:TEMP\opencode\assistai-upstream`, and PowerShell
  equivalents of the shell snippets below).
- **One commit, confirmed.** Only commit on explicit user approval; subject ends
  ` [ai]` and carries `SVY-21303`.

---

## Command form

| Command | Action |
|---------|--------|
| `/assistai-sync` | Run the full sync: fetch upstream, diff since baseline, review each change, apply approved changes, update baseline. |
| `/assistai-sync <sha>` | Same, but treat `<sha>` as the baseline (override the recorded one) — e.g. to re-review from an earlier point. |

`$ARGUMENTS` may be empty, or a single upstream commit SHA / ref to use as the
baseline for this run. It never changes direction.

---

## Phase 0 — Preflight

1. **Locate the Servoy Copilot repo.** This skill operates on the
   `Servoy-Copilot` checkout (the one holding
   `bundles/com.servoy.eclipse.developer.mcp`). You are normally started from
   inside it. Confirm the bundle directory exists; if not, ask the user for the
   Copilot repo path and stop until given.

2. **Verify `git`** is available. (No `gh` needed — this is read-only against the
   public upstream over HTTPS.)

3. **Clone or refresh the upstream** into a temp dir:
   ```bash
   UP=/tmp/opencode/assistai-upstream
   if [ -d "$UP/.git" ]; then
     git -C "$UP" fetch origin main --tags
   else
     mkdir -p /tmp/opencode
     git clone --filter=blob:none https://github.com/gradusnikov/eclipse-chatgpt-plugin "$UP"
   fi
   git -C "$UP" rev-parse origin/main   # newest upstream SHA (the sync target)
   ```
   If the clone/fetch fails (offline, network), stop and tell the user — do not
   proceed on stale data.

---

## Phase 1 — Baseline

The baseline is the **upstream** AssistAI commit we last synced from. It is
recorded in `docs/assistai-sync.md` (Copilot repo) on the
`Last synced upstream commit:` line.

1. **If `$ARGUMENTS` holds a SHA/ref**, use it as the baseline for this run
   (validate it resolves in the upstream clone with `git -C "$UP" rev-parse`).

2. **Else read `docs/assistai-sync.md`** and parse the recorded baseline SHA.

3. **Bootstrap (no baseline recorded):** if the tracking file is missing or has
   no baseline SHA, create/complete `docs/assistai-sync.md` from **Appendix A**
   and set the baseline to the **upstream commit contemporaneous with the
   original port** — NOT to the current upstream HEAD. The ports were first added
   around Servoy commit `14afbef` (2026-05-18, "SVY-21012 Tool & service
   refactoring"), so the first real sync must start there to catch everything
   since. Find the upstream commit of that date:
   ```bash
   git -C "$UP" log --until=2026-05-18 -1 --format=%H origin/main
   ```
   Confirm the chosen start point with the user, then continue the run normally
   (do **not** stop — a bootstrap still performs a full sync).

4. **Validate the baseline — whichever source it came from.** Always
   `git -C "$UP" rev-parse --verify <baseline>^{commit}` the SHA, including one
   read from the tracking file, and confirm it is an ancestor of `origin/main`
   (`git -C "$UP" merge-base --is-ancestor <baseline> origin/main`). A SHA that
   does not resolve or is not an ancestor (force-push upstream, or a stale/
   rebased-away value) must **stop the run**: tell the user what it was and ask
   for the correct start point rather than silently proceeding or guessing.

   > Servoy-side SHAs recorded in this file have the same hazard: a Servoy commit
   > can be rebased away, orphaning the value. When recording a Servoy SHA,
   > prefer one that is an ancestor of the Copilot `master` tip, and re-check it
   > on read.

---

## Phase 2 — Collect upstream changes since the baseline

Restrict everything to the mapped upstream files. Let
`SVCDIR=plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services`.

1. **List commits** touching any mapped upstream file since the baseline:
   ```bash
   git -C "$UP" log --oneline <baseline>..origin/main -- \
     "$SVCDIR/CodeEditingService.java" \
     "$SVCDIR/ResourceService.java" \
     "$SVCDIR/ProjectService.java" \
     "$SVCDIR/GitService.java" \
     "$SVCDIR/LocalHistoryService.java" \
     "$SVCDIR/MarkdownService.java" \
     "$SVCDIR/ConsoleService.java" \
     "$SVCDIR/EditorService.java"
   ```
   If empty → report "already up to date at `<baseline>`", update the baseline
   date, and stop.

2. For each commit, note its SHA, subject, and which mapped upstream file(s) it
   touches. Build a short table of the pending commits and present it to the user
   so they see the scope before any review.

3. Also note any mapped upstream file that was **renamed, split, or deleted**
   upstream since the baseline (via `git -C "$UP" log --follow --name-status`),
   because that changes the mapping and must be reflected in
   `docs/assistai-sync.md`.

---

## Phase 3 — Triage each change WITH the user

Go commit-by-commit (oldest first), or group several commits that touch the same
file if that reads more clearly. For each:

1. **Show the upstream diff** for the mapped file(s):
   ```bash
   git -C "$UP" show <sha> -- "$SVCDIR/<UpstreamClass>.java"
   ```

2. **First, check whether the upstream change even maps onto our code.** Do not
   assume the Servoy file is a slimmed copy of upstream's — several methods have
   **structurally diverged** (e.g. upstream `replaceString` is offset-based on
   `FindReplaceDocumentAdapter`/`TextEditRequest`, while Servoy's
   `replaceStringInFile` is line-based on `String.replace`). Read the Servoy
   method before judging, and note that a merge commit may only restate a change
   already counted (e.g. upstream `ab32be7` is the merge of `b720591`).

3. **Resolve** it as exactly one of the following, telling the user your reasoning:
   - **applies-cleanly** — the logic matches; port it with the port differences
     re-applied. Prefer these: typically **additive** (a new method, a new guard).
   - **needs-adaptation** — the fix is relevant but our implementation differs;
     **rewrite it to fit** our code. If the adaptation would restructure existing,
     working, Servoy-tested logic, surface the cost and let the user choose
     (per the design principles). If they decline, record it as a **divergence**
     with the reason — an explicit, visible decision.
   - **not-applicable** — entirely stripped territory (JDT / refactoring /
     formatter / organize-imports / `AiIgnoreService` / `UISynchronize` /
     editor-UI), or an upstream-only method/tool the Servoy port does not have
     and does not want. Record why.
   - **already-have** — the Servoy file already contains the equivalent fix
     (e.g. the SVY-21472 applyPatch hardening may already cover an upstream
     change). Record why.

4. **Get the user's decision** for anything that applies or needs adaptation.
   When unsure whether something is applicable, ask rather than guess. Remember
   the core rule: by the end of the run **every** commit in range must carry one
   of the four resolutions — nothing is left unresolved.

Keep a running ledger (upstream SHA → file → resolution → note) for the Phase 5
report and for writing the tracking file.

---

## Phase 4 — Apply approved changes

For each change resolved as *applies-cleanly* or *needs-adaptation*:

1. **Read the current Servoy file** (`eclipse-ide_readProjectResource` or
   `read`) and the upstream version/diff, and translate the upstream change into
   the Servoy file — **re-applying the port differences**: strip any JDT /
   `AiIgnoreService` / `UISynchronize` / editor-refresh code the upstream hunk
   carries, keep Servoy-only additions, and adapt types/names (`ResourceService`
   → `WorkspaceService`, the `ConsoleService`+`EditorService` merge →
   `IdeStateService`, etc.). For a *needs-adaptation* item this is a **rewrite in
   our idiom**, not a patch application: match the surrounding Servoy style and
   keep the existing method's contract and callers intact.

2. **Edit with Eclipse MCP tools** (`eclipse-coder_applyPatch` /
   `eclipse-coder_replaceString` / `eclipse-coder_applyTextEdits`). Keep the
   `Ported from AssistAI's {...}. Differences:` Javadoc header accurate if the
   set of differences changes.

3. **Compile loop (mandatory, per AGENTS.md).** After edits:
   - `eclipse-ide_getCompilationErrors` for the bundle.
   - If errors, review quick fixes and apply safe ones with
     `eclipse-ide_executeQuickFix`; re-check until clean.
   - Also address the two highest-severity SpotBugs levels in touched code.

4. If a ported file has a corresponding test
   (`tests/com.servoy.eclipse.developer.mcp.tests` or
   `com.servoy.eclipse.opencode.tests`), run the relevant unit test for the
   touched service to confirm the port still behaves (see the repo AGENTS.md for
   how to run tests). Report results.

---

## Phase 5 — Record the new baseline & report

Only reach this phase once **every** commit in range is resolved (the core rule).
If any is still open, go back to Phase 3 — do not advance the baseline past an
unresolved commit.

1. **Update `docs/assistai-sync.md`** (Copilot repo):
   - Set `Last synced upstream commit:` to the **newest upstream commit handled**
     this run (normally `origin/main`'s tip). Since every commit in range is
     resolved, the baseline advances to the tip and the next run starts cleanly
     after it. Record the date.
   - Append a dated **sync log** entry listing each handled commit with its
     resolution (applied / adapted / not-applicable / already-have) and a
     one-line note.
   - **Record both sides for anything applied or adapted**, in the ported-changes
     ledger: `upstream <sha>` → `Servoy <sha>` (the commit that carried it in) →
     file(s). This is what makes a change traceable later and gives
     `/assistai-contribute` something to match on, so a change that came *from*
     upstream is never proposed back to it. The Servoy SHA is only known after the
     commit in step 3 — write the ledger row then (or amend the tracking file in
     the same commit and record the resulting SHA on the next run's read).
   - Add any **divergence** the user chose not to adopt to the "Known
     divergences" section, with the reason.
   - Add any commit the user **consciously skipped** to "Skipped — revisit".
   - If any mapping changed (rename/split/delete upstream), update the mapping
     table in the tracking file (and this SKILL.md's table) to match.

2. **Report to the user**: what was applied, what was adapted, what was resolved
   as not-applicable/already-have (with reasons), any recorded divergences, the
   old → new baseline SHA, and the compile/test result.

3. **Commit only on explicit approval.** Show the staged files and the proposed
   message first (per AGENTS.md pre-commit checklist: zero compilation errors,
   show-then-confirm). Suggested subject:
   ```
   SVY-21303 sync ported MCP services with AssistAI upstream <shortSha> [ai]
   ```
   Stage the touched ported file(s) **and** `docs/assistai-sync.md` together. Do
   not push unless asked.

---

## Appendix A — `docs/assistai-sync.md` template

Written into the **Servoy Copilot** repo (not the skill dir). Create it on first
run if missing:

```markdown
# AssistAI upstream sync (SVY-21303)

One-way sync (upstream → Servoy) of the ported MCP service files against the
open-source AssistAI project. Maintained by the `assistai-sync` skill / the
`/assistai-sync` command. See that skill for the process.

- **Upstream:** https://github.com/gradusnikov/eclipse-chatgpt-plugin (branch `main`, MIT)
- **Upstream service dir:** `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services/`
- **Servoy service dir:** `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/`

## Last synced upstream commit: <SHA> (<YYYY-MM-DD>)

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
`/assistai-contribute` never proposes it back upstream.

| Upstream SHA | Servoy SHA | File(s) | Resolution |
|---|---|---|---|
| _(none yet)_ | | | |

## Known divergences (upstream change deliberately NOT adopted)

Where our implementation differs structurally and adopting upstream's version
would mean rewriting working, tested Servoy code. Recorded as a conscious
decision, not an omission.

| Upstream SHA | What it changes | Why not adopted |
|---|---|---|
| _(none yet)_ | | |

## Skipped — revisit

Commits the user consciously chose to postpone (needs a design decision / its own
case). Normally empty: a sync resolves everything.

| Upstream SHA | Why postponed |
|---|---|
| _(none yet)_ | |

## Sync log

- <YYYY-MM-DD> — synced <baseline>..<tip>: N commits handled (applied / adapted / n-a / already-have).
```

---

## Versioning & future updates

This skill is meant to be updated as things change:

- **Mapping drift** — if upstream renames/splits/deletes a mapped class, update
  the mapping table here and in `docs/assistai-sync.md`.
- **New ports** — if a new file is ported from AssistAI, add it to the mapping
  (grep for `Ported from AssistAI` in the Copilot bundle to find all ports).
- **Reverse direction** — handled by the separate **`assistai-contribute`** skill
  (`/assistai-contribute`): Servoy-origin fixes become draft PRs upstream. The two
  share `docs/assistai-sync.md`; the ported-changes ledger is what stops a change
  that came *from* upstream being proposed back to it.
- **Growing divergence** — as the ports and upstream drift further apart, expect
  more *needs-adaptation* and fewer *applies-cleanly* resolutions. Keep the "Known
  divergences" section current; it is the honest record of where the two
  implementations no longer correspond.

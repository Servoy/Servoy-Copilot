# Spec: SVY-21104 — Per-workspace AI audit log for EU AI Act compliance

## 1. Goal

Regulated ISVs (healthcare, finance) using skill4servoy need traceability evidence when
they ship AI features subject to EU AI Act Article 12. skill4servoy itself is likely
Limited/Minimal risk and not directly obligated — but its regulated customers carry the
Article 12 burden. This feature gives them the trail they need without rebuilding it by
hand: an append-only, per-workspace audit log written by the skill4servoy Orchestrator.

On every skill run that changes files, the Orchestrator appends **exactly one** entry
recording what the AI did — timestamp, user, skill, ticket key, files changed, line
counts, reviewer verdict, and the proposed commit message. Whether the user actually
committed is detected on the **next** session start by comparing the working tree / git
log against the recorded message. The log is local-only (not git-tracked), append-only
on disk, and survives IDE restarts.

## 2. Background

### 2.1 Target system

The audit log is implemented in **skill4servoy** (`C:\Users\Cristian\git\skill4servoy`)
— the customer-facing AI orchestrator that ISV developers use for Servoy development.
It is **not** the Servoy-Copilot R&D SDD pipeline.

### 2.2 EU AI Act mapping — product-led, customer-enabling

skill4servoy as a coding tool is likely **Limited or Minimal risk** under the EU AI
Act's risk-based classification. It is not in the Annex III high-risk list (biometrics,
critical infrastructure, employment, credit scoring, healthcare triage). The high-risk
logging obligations (Article 12) attach to the **customer's** AI system if *they* build
something high-risk — not automatically to the coding tool they used to build it.

The audit log's value is therefore **enabling regulated customers** — who may ship
high-risk AI features and thus carry Article 12 obligations themselves — to produce
traceability evidence without rebuilding it by hand. This is a **premium
compliance-readiness selling point** (confirmed by Volaris customer signal), with legal
confirming the customer-side obligation rather than a legal requirement on skill4servoy
itself.

| Article | Relevance |
|---------|-----------|
| **Art. 12 — Automatic event logs** | The log provides the raw material customers need for their own Article 12 compliance. |
| **Art. 14 — Human oversight** | The log captures human override / cancel / commit decisions — evidence that a human monitored and could stop the AI. |

**Why git `[ai]` history alone is insufficient:**
git history is **mutable** (rebase/amend), cannot record **cancelled** or
**uncommitted** runs, and carries **no reviewer verdict** and **no retention semantics**.
A dedicated append-only log is the correct instrument for traceability — whether legally
required or product-led.

### 2.3 The "skill run" lifecycle

A skill run is one Orchestrator task from dispatch to done:

```
Phase 0 dispatch → Phase 1/2 context + complexity → Phase 3 spec (Medium/Complex)
→ Implement & Review loop (Developer → Reviewer, up to 3 iterations)
→ post-loop gates (Security / Conformance)
→ Done: propose commit message (user commits or not)
```

The ticket asks for **one audit entry per run** — a summary row written when the run
reaches a terminal state (completed or cancelled). It is not a per-phase trace.

### 2.4 Commit detection on next session (ticket requirement)

The Orchestrator never commits without explicit approval, and the user may commit
outside the agent or defer it. So "was the commit made?" cannot be known when the run
ends. Design: record the proposed commit message now with `user_committed` unknown; on
the **next** session start, compare the working tree / recent git log against the last
unresolved entry's recorded message and backfill whether the commit landed.

### 2.5 ServoyCloud export side — no contract exists yet

The ticket says the export side "lives on the ServoyCloud admin screen." A full
exploration of `svyCloudCore` (ER diagram ~700 columns, `LLM/`, `api/`, `svyAPI/`,
`dal/`, `docs/`) found **no** AI-audit schema, API, admin screen, or `.jsonl` importer.
The `LLM/` solution is an empty shell; `api/llm.js` is the **deprecated** old GPT
assistant. The `developer_llm` table logs chat Q&A/token usage, not code changes.

**Consequence:** there is no consumer contract to conform to. This spec's schema
therefore **defines** the contract ServoyCloud will later consume, rather than matching
an existing one. Field names are chosen to be the natural baseline for that future
importer.

### 2.6 Architecture / permission context

The Orchestrator is an **LLM agent driven by markdown** (`Orchestrator.md` +
`context/*.md`), not compiled code. There is no execution-lifecycle hook, no catchable
abort signal, and no file-append handle. **The audit entry is written because the prompt
instructs the agent to write it** at the right moments. Its current permission model
allows `write` to `handoff/**` and `docs/**`, and `bash` to read-only git plus
`head`/`tail`/`wc`. It already has `servoy-git_gitStatus`, `servoy-git_gitLog`,
`servoy-git_gitDiff`.

**Permission change required:** a narrow `write` allow for `.servoy/audit/**` must be
added to `Orchestrator.md` (the audit log lives outside `docs/`; see §3.1).

### 2.7 Git history findings

No prior audit-logging code exists in skill4servoy. No `.servoy/audit/` convention
exists.

## 3. Design

### 3.1 Log file location

```
<solution-root>/.servoy/audit/ai-audit.jsonl
```

- **Per-solution:** one log per active Servoy solution (not the workspace root).
- **Directory `.servoy/audit/`:** a machine-local state folder inside the solution, not
  human-facing source.
  Chosen over `docs/audit/` because the log is local-only (not git-tracked) and does not
  belong alongside source documentation.
- **Not git-tracked:** the file is `.gitignore`d. Rationale:
  - Eliminates commit noise (every AI run would dirty the file otherwise).
  - Eliminates multi-developer merge conflicts (two devs appending to the same `.jsonl`
    collide on merge — a `.jsonl` audit log is a poor fit for git because it's
    append-heavy and per-machine).
  - Eliminates the backfill-re-diff problem (§3.4 modifies the file on each session
    start, which would create another git change).
  - Tamper-evidence moves to the **ServoyCloud export** — the compliance system-of-record
    — rather than relying on git diffs.
- **Configurable:** the path is read from an `AGENTS.md` config key `audit.folder`
  (see §3.7), defaulting to `.servoy/audit` when unset.
- **Format:** JSON-lines — one JSON object per line.

### 3.2 Entry schema — ONE entry per skill run

Each line is a self-contained JSON object — a **summary row**, not a phase trace.
Field names use snake_case (the ServoyCloud-facing baseline, §2.5).

```json
{
  "version": "1",
  "entry_id": "b1e0c2a4-3f7d-4c1e-9a2b-8d6f0e1a2b3c",
  "timestamp": "2026-08-05T07:42:00Z",
  "user": "esther@regulatedisv.com",
  "skill": "build",
  "ticket_key": "MED-4021",
  "status": "completed",
  "files_changed": ["src/db/connect.js", "src/models/user.js"],
  "lines_added": 42,
  "lines_removed": 12,
  "review_outcome": "approved",
  "commit_message": "feat(db): optimize connection pooling [ai]",
  "user_committed": null,
  "committed_resolved_at": null,
  "commit_sha": null,
  "detail": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | `string` | yes | Schema version, `"1"` |
| `entry_id` | `string` | yes | UUID v4 for this run — the key used to backfill commit status next session |
| `timestamp` | `string` | yes | ISO 8601 UTC when the run reached its terminal state |
| `user` | `string` | yes | System username |
| `skill` | `string` | yes | The § ROUTES route that ran (`"build"`, `"jsunit"`, `"e2e"`, `"review"`, `"optimize"`, …) |
| `ticket_key` | `string\|null` | yes | Jira/ServoyCloud key or `"ADHOC-<desc>"` or `null` |
| `status` | `string` | yes | `"completed"` or `"cancelled"` |
| `files_changed` | `string[]` | yes | Changed file paths (relative to project root); `[]` if none |
| `lines_added` | `number` | yes | Total lines added across all files |
| `lines_removed` | `number` | yes | Total lines removed across all files |
| `review_outcome` | `string\|null` | yes | `"approved"`, `"changes_needed"`, `"overridden"`, or `null` if no review ran |
| `commit_message` | `string\|null` | yes | The commit message the Orchestrator proposed, or `null` |
| `user_committed` | `boolean\|string\|null` | yes | `true` = committed, `false` = not committed, `"discarded"` = changes externally discarded, `null` = unresolved (pending next session) |
| `committed_resolved_at` | `string\|null` | no | ISO 8601 timestamp when `user_committed` was backfilled |
| `commit_sha` | `string\|null` | no | Short SHA if the commit was detected |
| `detail` | `string\|null` | no | Free text — e.g. cancellation reason |

### 3.3 When the entry is written

The Orchestrator appends exactly one entry when a run **that changed files** reaches a
terminal state:

- **Completed run:** after the Done Criteria are met and the commit message has been
  proposed (regardless of whether the user commits). `status="completed"`,
  `user_committed=null` (resolved next session).
- **Cancelled run:** if the user aborts at any gate (spec rejected, "stop",
  override-to-abort) **after files were already changed**. `status="cancelled"`,
  partial diffs calculated from `servoy-git_gitDiff`, `user_committed` left `null`.
- **No-file-change runs** (pure `explain`, read-only `review` with no edits): **no
  entry** — the ticket scopes logging to runs that change files.

"Changed files" is determined via `servoy-git_gitStatus` / `servoy-git_gitDiff` at the
terminal point.

### 3.4 Commit detection on next session start

At the **start** of every Orchestrator session (a bootstrap step, before Phase 0
routing):

1. Check `audit.enabled` (§3.7). If disabled, skip entirely.
2. Read `.servoy/audit/ai-audit.jsonl` (if it exists).
3. For each entry with `user_committed == null` and a non-null `commit_message`
   (scan **all** unresolved entries, newest first — the user may have stacked several
   runs before committing):
   - Run `servoy-git_gitLog` and look for a commit whose subject matches the recorded
     `commit_message` (first line; tolerant of the `[ai]` tag).
   - **Match found:** backfill `user_committed=true`, set `commit_sha` +
     `committed_resolved_at`.
   - **No match, files from that entry no longer dirty (clean working tree for those
     paths):** the changes were externally discarded (e.g. via git desktop client,
     `git checkout --`, or manual revert) — backfill `user_committed="discarded"`,
     `committed_resolved_at` set.
   - **No match, files still dirty:** still pending — leave `user_committed=null`
     (re-check next session).
4. Backfill rewrites **only** the reserved `user_committed` / `commit_sha` /
   `committed_resolved_at` fields of the target line; every other field is
   byte-preserved.

> This is the single place the log is not strictly append-only. It transitions three
> explicitly-reserved "unknown" fields to "known." No recorded fact is ever overwritten.

### 3.5 Write mechanism (structured write-tool only)

The Orchestrator uses the `read` + `write` tools to perform a read-append-write cycle.
**Shell append commands (`echo`, `>>`, `Add-Content`, `Out-File -Append`) are
forbidden** — shell-escaping JSON lines is brittle and frequently produces broken JSONL.

1. `read` existing `.servoy/audit/ai-audit.jsonl` (file-not-found → start empty).
2. For a new entry: append the new JSON line. For a commit backfill (§3.4): replace the
   target line's reserved fields only.
3. `write` the full content back.

The `.servoy/audit/` directory is created by the Orchestrator on first write if it does
not exist.

### 3.6 Append-only guarantee

- New runs only ever **append** a line.
- The only permitted mutation is the next-session backfill of the reserved
  `user_committed` / `commit_sha` / `committed_resolved_at` fields on unresolved entries
  (§3.4) — an unknown → known transition, never a rewrite of a recorded fact.
- No route, skill, or instruction deletes or edits any other field of any past entry.
- Tamper-evidence rests on the append-only discipline enforced by prompt instructions,
  plus the **ServoyCloud export** as the compliance system-of-record. The local file is
  not git-tracked (§3.1), so git diffs do not provide secondary tamper evidence.

### 3.7 AGENTS.md configuration

The audit settings are read from the Agent Configuration YAML block in the workspace's
`AGENTS.md` (the same block the Orchestrator already parses at bootstrap):

```yaml
audit:
  enabled: false          # default false — opt-in for regulated teams
  folder: .servoy/audit   # optional; defaults to .servoy/audit when omitted
```

- **`enabled`:** defaults to **`false`** (opt-in). Regulated ISVs explicitly enable it.
  Non-regulated teams get no surprise files or extra overhead.
- **`folder`:** relative to project root; defaults to `.servoy/audit`.
- If the `audit` section is **absent**, logging is **disabled** (same as
  `enabled: false`).
- When `enabled: false` (or absent), no audit entries are written **and** no
  next-session backfill runs (§3.4 is skipped entirely).

### 3.8 Retention (Article 12)

Article 12 expects a defined retention policy (commonly six months). For v1, retention
**enforcement** stays out of scope — pruning conflicts with append-only and belongs on
the ServoyCloud export/admin side. The spec **acknowledges** retention by documenting
that the on-disk log grows unbounded and that pruning/rotation is a ServoyCloud-side
concern. A future `audit.retention_days` config key is reserved but not implemented in
v1.

### 3.9 Reliability profile (honest limitations)

Because the writer is prompt-driven, not compiled code:

- Write points are **hard rails** in `Orchestrator.md` — mandatory instructions, not
  soft suggestions. Reliability is verified by **testing prompt behavior**, not
  guaranteed by a `finally{}` block.
- A **hard-killed session** (crash, force-quit before the terminal turn) writes **no
  entry**. This is a known limitation versus a coded interceptor. If auditors demand
  guaranteed capture, that is a separate, larger project (a coded hook in the
  Eclipse/opencode layer) and out of scope here.

## 4. Implementation plan

All changes are in **skill4servoy** (`C:\Users\Cristian\git\skill4servoy`):

1. **Create audit context file** — `.opencode/skills/servoy-orchestrator/context/audit.md`:
   - The §3.2 schema.
   - The §3.3 "when to write one entry" rule (terminal-state, file-changing runs only).
   - The §3.4 next-session commit-detection procedure (scan all unresolved entries,
     git-log match against `commit_message`, "discarded" resolution for externally-
     discarded changes).
   - The §3.5 write mechanism: structured write-tool only; shell append **forbidden**.
   - How to collect `files_changed` / `lines_added` / `lines_removed` from
     `servoy-git_gitDiff`, and how to resolve the folder + `enabled` flag from
     `AGENTS.md` (§3.7).

2. **Update `Orchestrator.md` permissions** — add a narrow `write` allow for
   `.servoy/audit/**` (the log location is outside the existing `docs/**` scope).

3. **Update `Orchestrator.md` bootstrap** — add a step (after the git-repo check):
   "AI-audit commit backfill" per §3.4, running before Phase 0 routing. One-line pointer
   to `context/audit.md`. Gated by `audit.enabled`.

4. **Update `Orchestrator.md` Done Criteria + Commit** — after the commit message is
   proposed, append the `completed` audit entry (`user_committed=null`). Gated by
   `audit.enabled`.

5. **Update `Orchestrator.md` cancellation paths** — wherever a run can abort (spec
   rejected, "stop", the "never silently pick up unrelated work" rail): if files were
   already changed and `audit.enabled`, append a `cancelled` entry before ending.

6. **Update `SKILL.md` routing table** — add:
   `| Write / resolve the AI audit log entry | context/audit.md |`.

7. **Update `context/commit.md`** — reference the audit append that follows the
   commit-message proposal.

8. **Add `.servoy/audit/` to workspace `.gitignore`** — ensure the log is never
   accidentally committed.

9. **Document in skill4servoy `AGENTS.md`** — the audit config block (§3.7), the log
   location, schema version, and the "one entry per file-changing run + next-session
   commit resolution" behavior.

## 5. Acceptance criteria

- [ ] A skill run that changes files produces **exactly one** audit entry containing:
      timestamp, user, skill, ticket_key (when present), files_changed,
      lines_added/lines_removed, review_outcome, commit_message.
- [ ] A run the user cancels mid-way (after files changed) records a `"cancelled"`
      entry, not `"completed"`.
- [ ] A run that changes no files produces no entry.
- [ ] The log is append-only — no route or instruction removes or edits past entries,
      except the reserved next-session backfill of
      `user_committed`/`commit_sha`/`committed_resolved_at`.
- [ ] On the next session start, whether each unresolved run's commit was made is
      detected by comparing git log / working tree to the recorded `commit_message`, and
      `user_committed` is backfilled accordingly (`true`, `false`, or `"discarded"`).
- [ ] Externally-discarded changes (no matching commit + files no longer dirty) resolve
      `user_committed` to `"discarded"`.
- [ ] The log persists across IDE restarts (plain file at the configured audit folder,
      default `.servoy/audit/ai-audit.jsonl`).
- [ ] The log is **not** git-tracked (`.gitignore`d).
- [ ] Each line in the log is valid JSON (JSON-lines format).
- [ ] The audit folder and enable/disable flag are read from `AGENTS.md` with documented
      defaults (`enabled: false`, `folder: .servoy/audit`).
- [ ] When `audit.enabled` is `false` or absent, no entries are written and no
      next-session backfill runs.
- [ ] The Orchestrator uses only the structured `write` tool to append entries — shell
      append commands (`echo`, `>>`, `Add-Content`) are never used.
- [ ] The existing git `[ai]` commit convention continues to work unchanged.

## 6. Out of scope

- Export to EU AI Act artefacts (ServoyCloud admin side — no contract exists yet, §2.5).
- Streaming the log to a remote store.
- Retention **enforcement** / pruning / rotation (documented, deferred to ServoyCloud —
  §3.8).
- Per-phase / per-iteration tracing (the ticket asks for one summary entry per run).
- Guaranteed capture of hard-killed sessions (§3.9 — separate coded-hook project).
- Java/Eclipse plugin changes (written entirely by the skill/agent layer).
- Authentication or encryption of the log file.
- Changes to the Servoy-Copilot SDD pipeline (this targets skill4servoy only).
- Git-tracking of the log file (local-only by design — §3.1).
- Custom merge drivers or multi-developer conflict resolution (eliminated by local-only
  design).

## 7. Definition of done

- All acceptance criteria met.
- `context/audit.md` created; `Orchestrator.md`, `SKILL.md`, `context/commit.md`,
  `AGENTS.md` updated.
- `.servoy/audit/` added to `.gitignore`.
- A manual dry-run through one `build` task shows a single well-formed `completed` entry;
  a cancelled dry-run shows a `cancelled` entry; a second session backfills
  `user_committed`.

## 8. Open questions (non-blocking)

| Question | Owner |
|----------|-------|
| `user` field: system username, git `user.name`, or Jira/ServoyCloud account? (Spec assumes system username) | Product |
| Commit-message matching on next session: exact first-line match, or fuzzy (tolerate user edits to the proposed message)? | Engineering |
| Retention: confirm the required duration (Art. 12 commonly cites six months) so the reserved `audit.retention_days` key + ServoyCloud pruning can be scoped. | Product / Legal |
| ServoyCloud export/import: this spec **defines** the schema since none exists (§2.5). Confirm SVY-21104's export side is tracked elsewhere and will consume this schema. | Product |

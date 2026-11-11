# Triage Report — SVY-21764

**Verdict:** NEEDS_INPUT

## Reported problem

Two separate things are bundled into this ticket.

1. **Primary (observed behaviour):** In the Servoy AI view, when a subagent is
   started you can *see* it in the content/transcript view (and in the session
   list), but — per the ticket — you cannot *click in the view to jump to that
   agent's output* the way you can in OpenChamber.

2. **Follow-up (explicitly open-ended):** "Also have a look what kind of stuff we
   miss if we compare it [to the] OpenChamber view … what kind of nice stuff
   OpenChamber has are we still missing (this could result in some follow up
   cases)." The ticket itself frames this as a gap-analysis that *produces*
   follow-up cases, not a single change.

No proposed implementation is given beyond "make it clickable like OpenChamber".

## Root-cause assessment

**The primary feature already exists in the code.** Opening a subagent from the
transcript ("content view") was implemented under **SVY-21461**, commit
**5070215** ("SVY-21461 open a subagent from the chat, and keep its link live",
2026-10-05), and refined since. The wiring is complete and end-to-end:

- `message-item.component.html:60-72` renders an **"Open &lt;name&gt; subagent"**
  button whenever `isSubagentLink(part)` is true (a subagent/task tool part whose
  child session id resolves). Clicking it calls `openSubagent(part)`.
- `message-item.component.ts:51,147-167` — `openSubagent` emits the child session
  id on the `openSession` output; `isSubagentLink` is backed by
  `subagentSessionId(part)` in `part-utils.ts:187-219`.
- `message-list.component.ts:34-35` bubbles that `openSession` output up; the root
  wires it in `app.component.html:25` → `app.component.ts:45-47`
  (`onSelectSession` → `store.openSession`).
- `session-list.component.ts:92-103` auto-expands the parent branch and selects
  the child, so the jump mirrors clicking it in the sidebar tree.

So "you can't click in the view to jump to that agent's output" is **not
accurate against the current codebase** — there is a dedicated clickable
affordance that does exactly this. The ticket was **created 2026-11-09**, a month
*after* the feature shipped (2026-10-05), and the reporter (Johan Compagner) is
also the author of the implementing commit. That timing is the crux of the
ambiguity.

**Where a real gap could still be:** the existing link only appears when
`subagentSessionId(part)` resolves a child id. That id is found from
(1) live `state.metadata.sessionID` (from a `session.tool.progress` event),
(2) `input.sessionID` (a continued child), or (3) parsing the completed
`<subagent sessionID="…">` output envelope (`part-utils.ts:191-218`). If none of
those are present — e.g. a running subagent whose parent was re-seeded over REST
before the progress event was captured — **no link is shown**. OpenChamber has a
materially stronger fallback for exactly this window:
`resolveRunningTaskChildSessionId` in
`D:\git\openchamber\packages\ui\src\components\chat\message\parts\taskToolModel.ts:37-68`
reconstructs the child id by matching child sessions on `parentID` +
`time.created >= startedAt` + `agent` + the `description` used as the child
title, even when the tool part carries no id yet. The Servoy port's
`backfillToolMetadata` (chat-store.service.ts:965-983) covers the re-seed case
*only when the id was already seen live*; it has no "find the child by
matching" path. So a plausible, concrete reading of the ticket is: **the link is
missing/unreliable in the running-subagent window**, and that is a real,
fixable gap.

But that is one of several possible readings, and the ticket does not say which.

## Ticket premise check

The premise — "you can't click to jump to the subagent" — does **not** hold as
written: the click-to-open affordance exists and is wired end-to-end. The ticket
therefore cannot be taken at face value. The actionable interpretations diverge:

- **(A) It already works, reporter hadn't noticed** → NO_ACTION on clause 1.
- **(B) The link is unreliable** (doesn't appear while a subagent is still
  running, or after a re-seed) → a targeted fix: port OpenChamber's
  match-by-child-session fallback. Real work.
- **(C) The reporter wants a different interaction** — e.g. clicking the tool row
  / name itself, not a separate button below it — → a small UX change.
- **(D) Clause 2** (full OpenChamber gap analysis) → a research task that spawns
  follow-up cases; not a single code change.

These lead to materially different outcomes and the ticket lacks the detail to
rule any out — so this is genuine divergence, not something to resolve by digging
deeper.

## Approaches considered

1. **Port OpenChamber's running-subagent resolver (interpretation B).** Add a
   `resolveRunningTaskChildSessionId`-style fallback so the "Open subagent" link
   resolves a child even before the id is carried on the part.
   - Pros: closes a concrete reliability gap; matches OpenChamber behaviour the
     reporter is comparing against.
   - Cons: speculative — we have not confirmed the link is actually failing to
     appear; could be solving a problem the reporter isn't reporting.
2. **Change the click target (interpretation C):** make the whole subagent tool
   row open the child, not just the separate button.
   - Pros: cheap; arguably more discoverable.
   - Cons: we don't know this is what's wanted; collides with the row's
     existing expand/collapse click.
3. **Do the OpenChamber gap analysis and file follow-ups (clause 2).**
   - Pros: directly serves the ticket's second, explicit ask.
   - Cons: open-ended; the ticket says this "could result in follow up cases", so
     it belongs in scoped child tickets, not this one.
4. **No code change.** If the existing feature already satisfies the reporter
   (interpretation A), clause 1 is done and clause 2 is a separate exercise.
   - Pros: avoids speculative work on an already-shipped feature.
   - Cons: ignores the possibility of a real reliability gap (B).

## Recommendation

**NEEDS_INPUT.** The primary feature the ticket asks for already exists (shipped
under SVY-21461, commit 5070215), and the ticket was filed a month later by the
same person who implemented it — so before any code is written we must learn what
is actually missing. The second clause is, by the ticket's own wording, an
open-ended comparison that should spawn follow-up cases rather than drive this
one. Depth will not resolve which of interpretations A–D is intended; only the
reporter can. See the questions below.

If the answer to Q1/Q2 is "the link is unreliable while the subagent is still
running", the clear recommended fix is **Approach 1** (port OpenChamber's
match-by-child-session fallback), which is a well-scoped, evidence-backed change.

## Git history findings

- **Feature already present:** commit **5070215** "SVY-21461 open a subagent from
  the chat, and keep its link live" (Johan Compagner, 2026-10-05) introduced the
  "Open subagent" link in `message-item.component.html`, the `openSession`
  output chain, the `subagentSessionId` resolver, and the sidebar auto-expand.
  Later SVY-21461 commits (1614a0f, 5744f4c, 6023ea6, a7a6538) and SVY-21707
  (1b8541f) refined the transcript rendering.
- **Ticket vs. feature timing:** issue created 2026-11-09, i.e. ~5 weeks *after*
  the implementing commit. Reporter = assignee = commit author (Johan Compagner).
- **Comparison target:** OpenChamber (`D:\git\openchamber`, HEAD 462299d75) has a
  stronger running-subagent id resolver
  (`taskToolModel.ts:37-68 resolveRunningTaskChildSessionId`) that the Servoy
  port does not have; the port only backfills ids it already saw live
  (`chat-store.service.ts:965-983`).
- No regression and no dependency/version bump is implicated.

## Questions for the reporter

1. The "Open subagent" link already exists in the transcript (added under
   SVY-21461, commit 5070215): a clickable **"Open &lt;name&gt; subagent"** row
   beneath the subagent tool call that jumps to the child session. Is this link
   **not appearing** for you, or **not working** when clicked — or did you simply
   not realise it was there?
2. If it is unreliable: does it fail specifically **while the subagent is still
   running** (before it finishes), or also **after** it completes? (OpenChamber
   resolves the running case by matching the child session even before the id is
   attached; our port does not, so this is the most likely real gap.)
3. Would you prefer the **whole subagent tool row** to be the click target (open
   on clicking the row/name), rather than the separate "Open subagent" button
   below it?
4. For the second part of the ticket (OpenChamber feature comparison): do you
   want this triage/implementation to **produce that gap analysis and open
   follow-up cases** now, or should that be split into its own dedicated ticket
   and kept out of SVY-21764's scope?

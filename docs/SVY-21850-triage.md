# Triage Report — SVY-21850

**Verdict:** PROCEED

## Reported problem

Servoy AI: a developer can already open a subagent's session (sidebar, or the Open button on the agent card). It opens as a normal chat with a disabled composer. The user lands in a stream of tool calls and cannot see what the agent was asked, what it handed back, or how to get back.

The ticket wants the agent view of the design (attachment 78953 'ServoyAI Redesign.html' on SVY-21845, downloaded to `D:\TEMP\opencode`):

- Header "<parent session> > <agent>" with a back button that returns to the parent at the same scroll position, plus a "read-only" note.
- Block "Task from Orchestrator" on top.
- Steps with tool name; the running step has a spinner.
- Block "Report to Orchestrator", or an orange block "Returned to Orchestrator - needs a decision" when the agent stopped.
- Divider "Resumed by the Orchestrator with your answer: <answer>" between the steps before and after a continuation.
- No input. The bottom shows "You're viewing an agent session. Questions and answers go through the Orchestrator." and a button "Open parent session".

Part 6 of 9 (label servoyai-redesign). Links: SVY-21850 "Blocks" SVY-21849 and SVY-21851 (the ticket has no comments or attachments of its own). Parent SVY-21845 is In Review. User context: SVY-21851 (file reader) is blocked by this case, so leave a slot for file chips and do not build the reader.

## Root-cause assessment

This is a feature, not a defect. The data already exists; there is no agent-specific presentation.

### How a child session is opened today

- `app.component.html:105` `(openSession)="onSelectSession($event)"` (card Open button -> message-list -> app) and sidebar `(select)` both end in `ChatStore.openSession(id)` (`chat-store.service.ts:516-557`).
- `openSession` is the same code path for every session. It sets `activeSessionId`, clears `messages`, seeds the newest page via `listMessagePage`, calls `recordSyntheticResults`, filters with `isRenderableMessage` and loads pending forms. There is no "agent view" concept.
- The store already knows enough to detect a child: `activeIsSubagent` (line 393, `parentID` in the loaded session list), `activeTitle` (401) and `activeParent` (409, `{id, title}`).
- Read-only today = the composer is disabled. `app.component.html:115-127` passes `[disabled]="store.activeIsSubagent() || ..."` and the hint "You're viewing a subagent session. Open its parent session to chat."; `composer.component.html:24` shows a disabled textarea (placeholder "Chatting is disabled for subagent sessions") and a disabled attach and send button. The ticket wants the composer replaced, not disabled.
- The header already has a "Parent" back link (`app.component.html:36-49`, `onSelectSession(parent.id)`), but it only shows "Parent" and the child title, no `parent > agent` breadcrumb.

### Where Task / Steps / Report / Resumed come from

All of it is in the child session's own message list (`GET /session/:id/message`), the same endpoint `openSession` already uses. No new endpoint is needed. I did NOT verify the 2.4.1 child message shapes against a live server in this triage (see "Not verified").

- **Task from Orchestrator = the child's FIRST user message.**
  - opencode creates the child session and prompts it with the tool input `prompt` (`subagent.ts` in 2.4.1: `execute` creates the child, prompts it, starts the job, returns immediately; the `SVY-21849` spec section 2.3.A documents this).
  - V2 maps a user message's `text` into a text part (`v2-mapping.ts:51-54`), so the task is already in `messages()` as a user message with a text part. Today it renders as a normal user bubble at the top of the child transcript.
  - Alternative source: the parent's tool part `state.input.prompt` (and `input.description` as a title). That is only reachable from the parent's messages, which are not loaded when the child is open. Use the child's first user message, with `description` unavailable. It is also persistent, with no dependency on the parent being loaded.
- **Steps = the child's assistant messages' tool parts (and text/reasoning).** `message-item.component` already renders them. The part helpers in `services/part-utils.ts` (friendly tool names, argument subtitles, `isSyntheticPart`, which now also hides `<background-result` text) give "what the agent did and with which tool". A running tool part (`state.status` `pending|running|streaming`) maps to the spinner step, but only while the child is really busy (reuse the never-spinner rule: `ChildSignals.busy`).
- **Report to Orchestrator = the child's LAST assistant message text** (identical to the `<background-result>` body the parent receives, `subagent-report.ts`, `session/subagent-completion.ts` per the SVY-21849 spec 2.3.B: body = "the child's last assistant text, all text parts joined"). Two sources, and they agree:
  1. In the child itself: the final assistant text message (no fetch beyond the page already loaded).
  2. In the parent: the synthetic `<background-result kind="subagent" sessionID=<child> state=...>` that `ChatStore.backgroundResults` already stores per child id (`recordBackgroundResult` via seeding and `session.inbox.enqueued`, `chat-store.service.ts:330-336, 926`).
  Prefer (2) `childSignals().get(childId)?.result` for the status/label and `deriveAgentStatus`, and render the text of the child's last assistant message (the same text) as the report body, so the report block works even when the parent was never loaded in this page load. If the two are both present, the background-result wins on status (`state=cancelled|error`), the child's message gives the full formatted text. The report must be removed from the transcript flow and shown in the block, not twice.
- **Resumed by the Orchestrator with your answer: X = a user message in the child that is NOT the first.** When the Orchestrator continues an agent it calls `subagent` with `input.sessionID = <child>` and a new `prompt` (SVY-21849 spec 2.3.A: "sessionID present = follow-up to an existing child"); opencode prompts that same child session, so the follow-up lands as the next user message in the child, preceded by the previous round's last assistant message (the previous report). So: user message index >= 1 in the child = a resume point; its text = the answer. The divider goes at that position, and the previous round's closing text is the earlier report. The first user message is the task. This matches the design's example (steps, Stopped, divider, more steps).
  - Alternative source: the parent's tool call `input.sessionID` + `input.prompt`. Same data, but only in the parent. Use the child.
  - Caveat: a user message in a child can also be a synthetic/system injection (`[system: ...]`, `<system-reminder`, reminders). `isSyntheticPart` already filters those parts; a user message whose only parts are synthetic must not count as a resume.
  - A follow-up to a still-running child "joins its current run" (tool description in 2.4.1): then there may be no new user message. Accept: no divider in that case.

### Is the agent card status (SVY-21849) correct for this?

Checked in code and the spec fixtures: `agent-status.ts` already implements the 2.4.1 model (SVY-21849 commit `ecdfac5`):

- The tool part `completed` is treated as "launched", never "finished" (`deriveAgentStatus` rules 1-7; status comes from `ChildSignals.busy|outcome|interruptedReason|result`).
- Task/Report text uses `<background-result>` seeded and live (`session.inbox.enqueued`, not `session.synthetic`, per the spec's live check on 2.4.1).
- A failed tool is `session.tool.failed` (handled, alias `session.tool.error` kept).
- `parseReportStatus` reads the `Status:` line; `INPUT_REQUIRED` (and BLOCKED/INCOMPLETE/FAILED/REJECTED) -> `stopped`, label "Stopped — needs a decision" only for `INPUT_REQUIRED`.

So the status derivation for the agent view can be reused as is. Two gaps I noticed that this case must account for:

1. `deriveAgentStatus(part, ctx)` needs a tool PART (`isSubagentTool`), which only exists in the parent's messages. The agent view has the child session only. The status inputs (`ChildSignals`) are keyed by child id and do not need the part, but `deriveAgentStatus` also reads `input.agent`, `input.sessionID`, `description` and `tool` status from the part. In the child view there is no part. Add a part-free entry point (for example `deriveChildStatus(childSignals, { childSessionId })` extracted from rules 1, 4, 5, 7 with no `tool`/`parentLive`), or build a synthetic launch part from the child. Keep one status model (the user asked for reuse).
2. Agent NAME in the header (the design shows "Customer overview form > Developer"). The name is in the parent's tool part `input.agent`, not in the child. Candidates: the child session `title` (opencode titles it "<description> (@agent subagent)" per the sidebar test fixtures `'Reviewer subagent'`), a field on the child session object (check `Session` for an `agent` field on 2.4.1), or the child's first assistant message `info.agent`. Needs a live check; fall back to the title, then "Agent".

### Scroll position on back navigation

- `MessageListComponent` is a single instance. `openSession` empties `messages` and reseeds; the list pins to the bottom whenever `pinnedToBottom` (`message-list.component.ts:46-106`). There is NO stored scroll offset today, so going to the child and back always lands at the bottom of the parent, not where the user was.
- To satisfy "same scroll position" the position must be saved when leaving the parent for a child and restored after the parent's messages are re-seeded and rendered (an `afterRenderEffect` that applies it once). Keep it in the store (or message-list) as a `Map<sessionId, scrollTop (+ pinned flag)>`, captured on `openSession` of a child. Risks:
  - Page size: the parent is re-seeded with the newest page only; if the user scrolled far up and older pages were loaded, the saved `scrollTop` may exceed the new content height. Either restore best-effort and clamp (acceptable) or keep the parent's already-loaded messages cached (bigger change). Recommend clamp + fall back to bottom when the saved position was "pinned".
  - Streaming parent: new content may arrive while away; restore by offset from the TOP is fine unless the user was pinned to the bottom (then stay pinned).
  - The existing prepend-anchor logic (`prevFirstId`) must not fight the restore.

### Existing building blocks to reuse

| Need | Reuse |
|---|---|
| Parent/child navigation | `ChatStore.activeParent`, `activeIsSubagent`, `openSession`, header `Parent` link |
| Status, label, never-spinner | `services/agent-status.ts` + `ChatStore.childSignals`, `busySessionIds` |
| Steps rendering | `message-item.component` (tool rows, `part-utils` names/subtitles), `message-list` |
| Hiding injected text | `isSyntheticPart` (incl. `<background-result`) |
| Report text/status | `ChatStore.backgroundResults`, `parseBackgroundResult`, `parseReportStatus`, `bodySummary` |
| Theme tokens | `--svy-accent-wait` (#b86b00 light), `--svy-ok`, `--svy-idle`, `--svy-accent-strong`, `--svy-danger` (3325ff9); the design uses `--acc-fg`, `--acc-soft2`, `--acc-line` for the orange block; check these exist under `--svy-*` names |
| Open parent button | the parent link logic (`onSelectSession(parent.id)`) |
| Composer swap | `app.component.html` already branches on `store.activeIsSubagent()` |

Composer: do not extend the composer component with a mode. Replace it in `app.component.html` with a small read-only footer (`@if (store.activeIsSubagent()) { <svy-agent-footer> } @else { <svy-composer> }`). The composer's draft/attachment state is keyed per session and must keep working for the parent; a subagent session never needs a draft.

## Ticket premise check

- The ticket says "the way back is the sidebar" and the input shows a hint. Partly outdated: there is already a header "Parent" back link (`app.component.html:36`), added earlier. The real gaps are the breadcrumb, the scroll position and the blocks.
- "Steps with a spinner for the running step" holds, but a running tool part in a child session can be stale (shutdown, crash). Use the same never-spinner rule as the cards: a spinner only when the child is confirmed busy (`ChildSignals.busy === true`), else the step shows as stopped/unknown.
- "Resumed ... with your answer": in 2.4.1 the Orchestrator's follow-up is just the next user message in the child; there is no distinct "answer" field. The "answer" is the prompt text the Orchestrator sent. If the Orchestrator rephrases, the divider shows that rephrased text, not the user's literal reply. Flagged as an assumption, not a defect.
- The task text is the Orchestrator's full prompt (can be long, multi-paragraph, markdown). The design shows a short sentence. The block needs collapse/expand (line clamp with "Show more") or it will dominate the view. Not in the ticket; decide in the spec.
- "Open parent session" at the bottom duplicates the header back button. Intended by the design (the footer replaces the composer), fine.
- A top-level (non-child) session is never in agent view: `activeIsSubagent` is the discriminator. A child whose parent is not in the loaded list (orphan, see `chat-store.service.spec.ts:175`) has `activeParent()` null: show the agent view without a back link, keep "Open parent session" hidden or disabled.

## Approaches considered

1. **Agent view as a mode of the existing chat (recommended).** Keep `openSession`/`messages` and render, when `activeIsSubagent()`, an `AgentView` presentation layer built from `messages()` of the child:
   - A pure util `services/agent-view.ts` (`buildAgentView(messages, childSignals, childId) -> { task, files: [], segments: [{ steps, resume?: string }], report, status }`), with specs. Reuses `agent-status` for status and `parseReportStatus`.
   - A header component (`parent > agent`, back, read-only pill), task block (with a slot `<ng-content select="[agent-files]">` or an empty `files` list for SVY-21851), steps through the existing message-item, report block (orange when stopped), resume divider, and a read-only footer.
   - Pros: no new fetches, no new routes, live updates for free (the child's messages stream through the same store), consistent with the SVY-21849 status model. Cons: the util must group messages (first user = task, later users = resumes, last assistant text = report) and avoid double-rendering text that the report block shows; message-item has no way today to render "a step" in the design style, so a thin step presentation is needed or message-item gets an input.
2. **No code change.** The child already opens read-only with a back link and the full transcript is visible. Rejected: acceptance criteria (breadcrumb, task, report, resume divider, scroll restore, footer) are not met, and SVY-21851 depends on the slot.
3. **Separate route/component with its own fetch of the child.** Rejected: duplicates `openSession`, the SSE merge and the pagination; it would double the state. Only worth it if a live check shows the child's messages do NOT contain the task/follow-up as user messages (see below), in which case the parent tool-part input would be needed.
4. **Source the task/resume/report from the PARENT's tool parts** (the data the card already has). Rejected as primary: requires loading the parent while viewing the child, and a child opened from the sidebar has no parent messages loaded. Keep only as a fallback if a live check shows the child lacks the user prompt.

## Recommendation

PROCEED with approach 1. Points for the spec:

- New pure util + specs for grouping: task = first non-synthetic user message in the child; resumes = later non-synthetic user messages; report = last assistant message text (prefer the stored `backgroundResults` body for status, rendered text from the child); status/label/spinner from `childSignals` (reuse `agent-status`, add a part-free entry point so the child view and cards share ONE model).
- The report block is orange with title "Returned to Orchestrator - needs a decision" when the derived status is `stopped` because of an `INPUT_REQUIRED` report. Decide the title for the other stops (technical stop: interrupted, cancelled, failed, BLOCKED...). Default: generic orange "Stopped" with the reason (same default as the cards). Ask the reporter.
- File chips: render the files slot as an empty container element (`data-slot="agent-files"`, an input `files = input<AgentFile[]>([])`) in the task block. No file model/reader in this case. SVY-21851 owns it.
- Header: breadcrumb "<parent title> > <agent>", back button with the parent title in its label, a "read-only" pill. Agent name source needs a live check (title or `info.agent`, fallback "Agent").
- Scroll restore: save the parent's scroll (and pinned flag) when navigating from a parent to a child, restore after the parent's seed has rendered, clamp, and stay pinned if it was pinned. Unit-test the store/list contract with a fake element.
- Footer replaces the composer for subagent sessions: text "You're viewing an agent session. Questions and answers go through the Orchestrator." + "Open parent session" (hidden when no parent). Remove the now-unused subagent hint path in the composer wiring, or leave the composer API untouched (smaller change).
- Do not push. Do not create real model sessions in tests; use fixtures (a child with user task, assistant tool parts, a `<background-result>`, a continuation).

Open questions for the spec / reporter:

1. Title and colour of the report block for stops that are not "needs a decision" (BLOCKED, FAILED, interrupted by user, ...). Design only has "needs a decision".
2. Long task text: clamp with "Show more"? The design shows one line.
3. Should a resumed round show its own report block each (every round ends with a report) or only the last? The design example shows one report at the end and the earlier stop as a step "Stopped". Recommend: only the last round gets the report block; earlier rounds end with a "Stopped" step.
4. Orphan child (parent not in the list): hide "Open parent session".

## Not verified (to confirm live on 2.4.1 before or during implementation)

- That the child's persisted messages start with the Orchestrator's `prompt` as a user message and that a follow-up arrives as a further user message (read from the 2.4.1 source via the SVY-21849 spec, not run against a live server in this triage).
- The field that names the agent on the child (session `title` pattern, `info.agent` on the assistant message, or another).
- That a follow-up to a still-running child ("joins its current run") produces no new user message (so no divider).
- That `INPUT_REQUIRED` stop reports, a parent stopped while the child continues, and shutdown interrupts show correctly (the SVY-21849 spec lists these as not yet confirmed live).
- I did not read the full design HTML beyond its text strings for the blocks; the exact orange tokens (`--acc-fg`, `--acc-soft2`, `--acc-line`) must be mapped to the existing `--svy-*` tokens during implementation. The design contains an `agent.files` list with a `.file` button style, which is the visual for the file-chip slot.

## Git history findings

- `ecdfac5` SVY-21849: `agent-status.ts`, `agent-card.component`, store `childSignals`/`busySessionIds`/`backgroundResults`, `session.inbox.enqueued` handling. Basis for this case; its triage and spec are in `docs/`.
- `2643875` SVY-21852 (status panel) and `3325ff9` SVY-21845 (layout, theme, `--svy-*` tokens, header with the "Parent" link, `sessions` sidebar).
- `0053464`, `03031d1`: opencode 2.4.1 pin and break check (background subagent tool, `session.tool.failed`).
- Earlier cases (SVY-21764, always-visible Open link) introduced the open-subagent flow and the disabled-composer hint, which this case supersedes for subagent sessions.
- No existing commit implements an agent view, a scroll-position cache or a read-only footer.

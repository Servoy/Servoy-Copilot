# Spec: SVY-21850 — Agent session view (part 6 of 9, servoyai-redesign)

## 1. Goal
When a developer opens a subagent (child) session, show it as an agent view instead of a plain transcript with a disabled composer: a header `<parent session> > <agent>` with a back button that returns to the parent at the same scroll position, a "Task from Orchestrator" block, the agent's steps (tool name, spinner on the running step), a "Report to Orchestrator" block (orange "Returned to Orchestrator - needs a decision" when the agent stopped), a "Resumed by the Orchestrator with your answer: ..." divider between rounds, and a read-only footer with "Open parent session" instead of an input. An empty slot for file chips is left for SVY-21851 (file reader), which is NOT built here.

## 2. Background

### 2.1 Existing implementation (verified in code)
- `ChatStore.openSession(id)` (`services/chat-store.service.ts:516`) is the single path for every session: sets `activeSessionId`, clears `messages`, seeds the newest page via `listMessagePage`, filters with `isRenderableMessage`. No agent-view concept.
- `ChatStore.activeIsSubagent` (l.393, `parentID` of the session in `allSessions()`), `activeTitle` (l.401), `activeParent` (l.409, `{id, title}` or `null` for an orphan).
- `ChatStore.childSignals` (l.141, `ReadonlyMap<string, ChildSignals>`) and the private `backgroundResults` (l.126) feed `deriveAgentStatus` for the cards.
- `services/agent-status.ts` exports `deriveAgentStatus(part, ctx)`, `parseReportStatus`, `parseBackgroundResult`, types `ChildSignals`, `AgentRunStatus` (`running|done|stopped`), `AgentReportStatus`. `deriveAgentStatus` needs a tool PART (reads `input.agent`, `input.sessionID`, `description`, `tool` status, legacy `<subagent>` output). `bodySummary`, `bodyLines`, `labelOf`, `oneLine`, `INTERRUPT_SUMMARY` are module-private. `labelOf`: stopped + `INPUT_REQUIRED` => "Stopped — needs a decision", other stops => "Stopped".
- `app.component.html:36-49`: header "Parent" back button (`onSelectSession(parent.id)`); `:99-107` `<svy-message-list>`; `:115-127` `<svy-composer>` with `[disabled]="store.activeIsSubagent() || !!store.pendingForm()"` and the hint "You're viewing a subagent session. Open its parent session to chat."
- `components/message-list.component.ts` (flat under `components/`, not in a subfolder): single instance, `pinnedToBottom` boolean, `afterRenderEffect` pins to bottom or compensates for prepends (`prevFirstId`/`prevScrollHeight`). No stored scroll offset, so back navigation always lands at the bottom of the parent.
- `Session` (`models/opencode.models.ts:35`) has `id, parentID?, title?, outcome?, time?, [key: string]: unknown`; `MessageInfo` has `id, role, time?, error?, [key]: unknown`; `Part` has `type, text?, tool?, state?, synthetic?`.
- `services/part-utils.ts` exports `isSyntheticPart`, `isTextPart`, `isToolPart`, `isRenderablePart`, `toolDisplayName`, `toolSubtitle`, `isSubagentTool`, `subagentSessionId`, `upsertPart`.
- Tokens (`src/styles.scss`, light at l.83-87, dark palette further down): `--svy-acc-fg`, `--svy-acc-fg2`, `--svy-acc-soft`, `--svy-acc-soft2`, `--svy-acc-line`, `--svy-accent-wait`, `--svy-ok`, `--svy-ok-fg`, `--svy-idle`, `--svy-line`, `--svy-soft`, `--svy-surface`, `--svy-muted`, `--svy-fg2`.

### 2.2 Design mapping (ServoyAI Redesign.html / tpl.html)
The design variables map 1:1 by prefixing `--svy-`: `--acc-fg` -> `--svy-acc-fg`, `--acc-soft2` -> `--svy-acc-soft2`, `--acc-soft` -> `--svy-acc-soft`, `--acc-line` -> `--svy-acc-line`, `--ok-fg` -> `--svy-ok-fg`, `--idle` -> `--svy-idle`, `--line`/`--soft`/`--surface`/`--muted`/`--fg2` likewise. All exist in light and dark (design values: light acc-fg #9A5B00, acc-soft2 #FFFBF3, acc-line #F2D9A6; dark #F5B94A, #241C10, #5C4318). No new tokens are needed; do not hard-code colors.

### 2.3 Data sources (from the triage; opencode 2.4.1 child message shapes NOT yet verified live — see section 7)
Everything comes from the child session's own messages (already loaded by `openSession`); no new endpoint.
- Task = first non-synthetic user message of the child (its text parts).
- Resume points = later non-synthetic user messages (text = "answer", i.e. the Orchestrator's follow-up prompt, which may be rephrased).
- Steps = tool parts (and text/reasoning) of the assistant messages, per round.
- Report = text of the last assistant message of the last round (identical to the `<background-result>` body the parent receives). Status comes from `childSignals.get(childId)` (`busy`, `outcome`, `interruptedReason`, `result`).

## 3. Design

### 3.1 Part-free status entry point (`services/agent-status.ts`)
One status model for cards and agent view. Extract the rule chain of `deriveAgentStatus` into an exported part-free function:

```ts
export interface ChildStatus {
  readonly status: AgentRunStatus;
  readonly label: string;
  readonly summary: string;
  readonly report: AgentReportStatus | null;
}
export function deriveChildStatus(child: ChildSignals | undefined, opts?: { reportBody?: string }): ChildStatus
```
- Rules: `busy===true` -> running; result `error|cancelled` / outcome `failed|interrupted` / `interruptedReason` -> stopped (summary via the existing `INTERRUPT_SUMMARY`/Cancelled/Failed/Interrupted logic); result `completed|running` or outcome `succeeded` -> `parseReportStatus(body)`; stop-report -> stopped else done; no signals at all -> `stopped` with summary "Not running" (never a spinner without `busy === true`; there is no parent tool part / `parentLive` in a child view).
- `opts.reportBody` is used when `child.result` is absent but the child's last assistant text exists (the child view works even if the parent was never loaded): a body whose `parseReportStatus` is a stop report gives `stopped`, otherwise `done` only when `child.outcome === 'succeeded'` or the child is not busy and has a report body; otherwise `stopped`/"Not running".
- `deriveAgentStatus` is refactored to delegate its rules 1,4,5,7 to the shared helper (tool-part specific rules 2/6 — `tool==='error'`, launch with `parentLive` — stay in `deriveAgentStatus`). Behaviour of `deriveAgentStatus` must be unchanged (existing `agent-status.spec.ts` stays green untouched). `labelOf` and `bodySummary` are reused (kept private, shared inside the file).

### 3.2 Pure builder `services/agent-view.ts` (+ `agent-view.spec.ts`)
```ts
export interface AgentViewStep { readonly messageId: string; readonly part: Part; readonly kind: 'tool' | 'text' | 'reasoning'; readonly running: boolean; }
export interface AgentViewRound {
  readonly resume: string | null;       // null for the first round
  readonly steps: AgentViewStep[];
  readonly stoppedAtEnd: boolean;       // earlier rounds: render a final 'Stopped' step
}
export interface AgentViewReport { readonly text: string; readonly status: AgentRunStatus; readonly label: string; readonly stopped: boolean; readonly needsDecision: boolean; readonly reason: string; }
export interface AgentView {
  readonly name: string;
  readonly task: string | null;
  readonly rounds: AgentViewRound[];
  readonly report: AgentViewReport | null;
  readonly status: ChildStatus;
}
export function buildAgentView(messages: ChatMessage[], session: Session | undefined, child: ChildSignals | undefined): AgentView
```
Rules:
1. Synthetic parts are ignored via `isSyntheticPart`; a user message with no non-synthetic text part is not a task/resume (and not a step).
2. First qualifying user message -> `task` (text parts joined by `\n\n`). Each further qualifying user message starts a new round with `resume` = its text. Messages before the first user message (older page not loaded) go into round 0 with `task = null`.
3. Assistant messages after a user message belong to that round. `tool` parts -> step kind `tool`; `text` -> `text`; `reasoning` -> `reasoning`; other part types are skipped. A tool step is `running` only when `part.state.status` is `pending|running|streaming` AND `status.status === 'running'` (child confirmed busy). A non-terminal tool part while the child is not busy is shown as not running (no spinner).
4. Report: only for the last round. `text` = the text of the last assistant message of the last round (text parts joined, non-synthetic). That text part(s) are removed from the last round's steps so the text is not rendered twice; if `child.result.body` exists and the child's message has no text, use the stripped body (`parseBackgroundResult` already trims the envelope; strip with the same envelope/outstanding rules as `bodyLines`, export a small helper if needed). While `status.status === 'running'`, `report` is `null` (the trailing text stays a step). When there is neither text nor a result, `report` is `null`, except for a stopped status (interrupted/failed/cancelled) where a report block with `reason = status.summary` and empty text is still produced.
5. `stopped` = `status.status === 'stopped'`; `needsDecision` = `status.report === 'INPUT_REQUIRED'`. Title selection is done in the template: needsDecision -> "Returned to Orchestrator - needs a decision"; stopped (other) -> "Stopped" plus `reason` (default 1); otherwise "Report to Orchestrator".
6. Earlier rounds (all but the last) get `stoppedAtEnd = true` (they ended because the Orchestrator resumed them); they show a trailing "Stopped" step, no report block (default 3). Their final assistant text stays a normal text step.
7. Agent name (documented assumption, to be confirmed live): first assistant message `info['agent']` (non-empty string) -> `session['agent']` (non-empty string) -> `(@<name> subagent)` pattern in `session.title` (`/\(@([^)\s]+)\s+subagent\)/i`) -> `'Agent'`. The raw name is returned as-is (no capitalization).
8. Pure and deterministic: no `Date`, no I/O, no mutation of the inputs.

### 3.3 Components (`src/app/components/`, standalone, OnPush, signals, `inject()`; selector prefix `svy-`)
- `agent-header.component` — inputs `parentTitle: string | null`, `parentId: string | null`, `agentName: string`; output `back`. Renders breadcrumb `<parentTitle || 'Parent session'> > <agentName>` (the `>` separator is `aria-hidden`), a back button (label "Back to parent session: <title>", same chevron icon as the existing `.parent-link`), and a "read-only" pill. When `parentId` is null (orphan) the back button is not rendered (default 4) and the breadcrumb shows only the agent name. Replaces the `.header-leading`/`.header-title` content of `app.component.html` while `activeIsSubagent()`; the sessions/theme/status buttons stay.
- `agent-task.component` — input `task: string`, input `files: AgentFile[]` (default `[]`), where `AgentFile` is a minimal exported interface `{ name: string; path?: string }` declared in the component file with no behaviour. Title "Task from Orchestrator". Text rendered with the existing `svy-markdown-renderer`. Clamped to a collapsed height (CSS line clamp, 6 lines) with a "Show more"/"Show less" toggle (`aria-expanded`) shown only when the text exceeds a character/line threshold (> 8 lines or > 600 chars; the check is a pure exported helper `isLongTask(text)` so it is testable without layout). Below the text, an always-present empty container `<div data-slot="agent-files" class="agent-files">` that renders one non-interactive chip per `files()` entry (none today). No reader, no click handling, no file model (SVY-21851).
- `agent-report.component` — inputs `report: AgentViewReport`; title per 3.2 rule 5; orange variant (class `stopped`) uses `--svy-acc-soft2` background, `--svy-acc-line` border, `--svy-acc-fg` title; normal variant uses `--svy-surface`/`--svy-line` with `--svy-ok-fg` accent. Body via the markdown renderer; when `text` is empty shows `reason`. `role="region"` with `aria-label` = the title.
- `agent-resume-divider.component` — input `answer: string`; renders "Resumed by the Orchestrator with your answer: <answer>" (answer collapsed to one line with `title` carrying the full text, CSS ellipsis). Horizontal rule on both sides, `role="separator"`.
- `agent-steps` — no new component for step rows: each step reuses `svy-message-item` where possible. Because `message-item` renders a whole message, the agent view renders the child's messages round by round with the existing `svy-message-item` (filtered to non-synthetic assistant parts; the first user message and resume user messages are hidden from the transcript flow since they show in the task block/divider). The running spinner comes from the existing tool-row spinner, fed `turnBusy = (status.status === 'running')` so the never-spinner rule holds. A trailing "Stopped" step (`stoppedAtEnd`) is a simple row `<div class="agent-stopped-step">Stopped</div>`.
  - Implementation constraint: `AgentView.rounds[].steps` carries the parts; the view component builds a per-round `ChatMessage[]` (same `info`, `parts` filtered to the round's steps) and feeds `svy-message-item`, so no change to `message-item` is required. If a non-invasive input is needed on `message-item`, it must default to current behaviour.
- `agent-view.component` — the container for the child: inputs `messages`, `session`, `child` (ChildSignals), `parentTitle`, `parentId`; computes `buildAgentView` via `computed()`. Layout, top to bottom: task block, rounds (with a resume divider before each round with `resume !== null`), report block. Outputs `openSession` (bubbled from nested `message-item` cards). It owns the scroll container (`#scroll`, class `scroll`) and pins to the bottom while the child is running (same pin logic as `message-list`, reusing the same constants; extract the pin logic only if it can be done without changing `message-list` behaviour, otherwise duplicate minimally).
- `agent-footer.component` — input `canOpenParent: boolean`, output `openParent`. Text "You're viewing an agent session. Questions and answers go through the Orchestrator." and a button "Open parent session" (hidden when `canOpenParent` is false). Replaces `<svy-composer>` while `store.activeIsSubagent()`. The composer component API is left untouched (its `disabled`/`disabledHint` inputs stay for the pending-form case; the subagent hint string path simply becomes unreachable and is removed from `app.component.html`).

### 3.4 `app.component.html` wiring
```
@if (store.activeIsSubagent()) {
  <svy-agent-view ... />        // replaces <svy-message-list>
} @else {
  <svy-message-list ... />
}
...
@if (store.activeIsSubagent()) { <svy-agent-footer .../> } @else { <svy-composer .../> }
```
- The header: while `activeIsSubagent()`, render `<svy-agent-header>` in place of the `Parent` link and title; otherwise unchanged.
- `svy-form-card` for a pending form stays as is.
- Agent name and the `Session` object come from new store computeds: `activeSession` (the `Session` or `undefined`) and `activeChildSignals` (`childSignals().get(activeSessionId())`). No other store state is added for the view.

### 3.5 Scroll position restore on back navigation
- New store state: `private readonly savedScroll = new Map<string, { top: number; pinned: boolean }>()`, a method `saveScroll(sessionId: string, pos: { top: number; pinned: boolean }): void`, and `takeScroll(sessionId: string): { top: number; pinned: boolean } | undefined` (returns and deletes the entry, so it applies once).
- Capture: `message-list` gets a public method `getScrollState(): { top: number; pinned: boolean }` (reads `scrollTop` and `pinnedToBottom`). `app.component` calls it right before opening a CHILD from a non-agent view (in `onSelectSession`, when the target is a different session, the active session is not a subagent, and the target id's session has a `parentID === activeSessionId`): `store.saveScroll(activeSessionId, list.getScrollState())`. Navigating to the parent from the agent view uses the same `onSelectSession`; there is nothing to save for the agent view.
- Restore: `message-list` gets an input `restoreScroll` of type `{ top: number; pinned: boolean } | null` (set by `app.component` from `store.takeScroll(parentId)` when the parent is opened from its child; null otherwise). After the parent's messages are seeded and rendered, the existing `afterRenderEffect` applies it ONCE: if `pinned` -> keep pinning to the bottom; else `scrollTop = min(top, scrollHeight - clientHeight)` (clamp, never negative), and set `pinnedToBottom = false`. The prepend-anchor logic must not run in the same pass as the restore (skip the anchor adjust when a restore was applied) and the restore is not re-applied on later content changes.
- Restore waits until `messages().length > 0`; if the parent seed is still empty it stays pending.
- Out of scope: caching the parent's already-loaded older pages (position is restored best-effort, clamped, against the newest page only).

### 3.6 Styles
All colors via `--svy-*` tokens only (section 2.2). Blocks use the same radius/spacing as `agent-card`/`message-item` (check neighbors; no new global variables). Light and dark both rely on the tokens, no `data-theme` branches in component SCSS. Focus ring for buttons via `--svy-focus-ring`. Spinner respects `prefers-reduced-motion` like the existing tool spinner.

## 4. Implementation plan
1. `services/agent-status.ts`: add and export `deriveChildStatus`/`ChildStatus`; make `deriveAgentStatus` delegate; keep exports and behaviour unchanged.
2. `services/agent-view.ts` + `agent-view.spec.ts`: types and `buildAgentView` (fixtures: child with task, tool steps, `<background-result>`, a continuation).
3. `ChatStore`: `activeSession`, `activeChildSignals` computeds; `savedScroll` map with `saveScroll`/`takeScroll`; specs.
4. Components in `components/`: `agent-header`, `agent-task` (incl. `isLongTask`, `AgentFile`, data-slot), `agent-report`, `agent-resume-divider`, `agent-footer`, `agent-view` (+ `.html`/`.scss`/`.spec.ts` for each).
5. `message-list`: `getScrollState()`, `restoreScroll` input, one-shot restore in the `afterRenderEffect` (no behaviour change when the input is null).
6. `app.component.ts/html`: header/body/footer branching, scroll save/restore wiring in `onSelectSession`, remove the unreachable subagent hint.
7. Run `ng test --no-watch` for the touched specs, then the whole suite, then `ng build`; lint if configured (zero warnings). Production `npm run build` before commit is NOT required by this spec (no dev-watch output left behind).
8. Verify visually against the fixture child only (no real model calls, no test sessions with real model requests): optionally `?debug_view=true` unaffected.

## 5. Acceptance criteria (Vitest-testable)

### agent-status.spec.ts
- [ ] `deriveChildStatus({busy:true})` -> `running`, label "Running".
- [ ] `deriveChildStatus` with `result {state:'completed', body:'Status: COMPLETED\nDone'}` -> `done`, label "Done", `report 'COMPLETED'`.
- [ ] with a body `Status: INPUT_REQUIRED` -> `stopped`, label "Stopped — needs a decision", `report 'INPUT_REQUIRED'`.
- [ ] with `Status: BLOCKED` (or FAILED) -> `stopped`, label "Stopped".
- [ ] `result.state 'cancelled'` -> `stopped`, summary "Cancelled"; `interruptedReason 'user'` -> summary "Stopped by user"; `outcome 'failed'` -> "Failed".
- [ ] `undefined`/empty signals -> `stopped`, summary "Not running" (never `running`).
- [ ] `busy:true` wins over a stale `result`/`outcome`.
- [ ] `reportBody` with `Status: INPUT_REQUIRED` and no `child.result` -> `stopped`.
- [ ] All pre-existing `deriveAgentStatus` specs pass unchanged.

### agent-view.spec.ts
- [ ] First non-synthetic user message becomes `task`; a user message made only of `<system-reminder>`/`[system: ...]`/synthetic parts is ignored.
- [ ] A second user message creates a second round with `resume` equal to its text; the first round has `resume === null`.
- [ ] Two resumes -> three rounds, in order.
- [ ] No resume when there is only one user message.
- [ ] Tool parts become `tool` steps, text -> `text`, reasoning -> `reasoning`; other part types are dropped.
- [ ] A `running` tool part yields `running: true` only when `child.busy === true`; with `busy` absent/false the step has `running: false`.
- [ ] Report: the last assistant message text of the last round becomes `report.text` and is absent from the round's steps (not duplicated).
- [ ] While busy, `report` is `null` and the trailing text remains a step.
- [ ] `Status: INPUT_REQUIRED` report -> `report.stopped`, `report.needsDecision` true.
- [ ] `Status: BLOCKED` -> `stopped` true, `needsDecision` false.
- [ ] `Status: COMPLETED` -> `stopped` false, `needsDecision` false.
- [ ] Interrupted child with no text -> report with empty text and `reason` "Stopped by user" (or the matching summary).
- [ ] Earlier rounds have `stoppedAtEnd === true`; the last round has `false`; only the last round's text can become `report`.
- [ ] Falls back to `child.result.body` (envelope stripped) when the child's last assistant message has no text.
- [ ] Name resolution order: `info.agent` -> `session.agent` -> title `"X (@reviewer subagent)"` gives `reviewer` -> `'Agent'`.
- [ ] A `messages` array with no user message gives `task === null` and one round.
- [ ] The inputs are not mutated (deep-frozen fixtures pass).

### Components
- [ ] agent-header: shows `Parent title`, a separator and the agent name; clicking the back button emits `back` once; with `parentId === null` there is no back button; a "read-only" pill is present; back button `aria-label` contains the parent title.
- [ ] agent-task: renders "Task from Orchestrator" and the text; a long text (`isLongTask` true) shows a "Show more" button (`aria-expanded="false"`) and toggles to "Show less"; a short text shows no toggle; `[data-slot="agent-files"]` always exists, is empty with the default `files`, and renders N chips for N entries; no click handler on chips.
- [ ] `isLongTask`: false for a 3-line/100-char text, true for > 8 lines, true for > 600 chars.
- [ ] agent-report: normal -> title "Report to Orchestrator", no `stopped` class; `needsDecision` -> title "Returned to Orchestrator - needs a decision" and `stopped` class; other stop -> title "Stopped" and the reason shown; the empty-text case shows the reason.
- [ ] agent-resume-divider: renders exactly "Resumed by the Orchestrator with your answer: <answer>"; has `role="separator"`.
- [ ] agent-footer: shows "You're viewing an agent session. Questions and answers go through the Orchestrator."; there is no `textarea`/input; "Open parent session" emits `openParent`; hidden when `canOpenParent` is false.
- [ ] agent-view: for a fixture with task, one tool step, a resume and a final report the DOM order is task block, round 1, divider, round 2, report block; the divider is not rendered for a single round; a spinner exists only when the child is busy and a tool part is running; `fixture.detectChanges()` after setting signals does not throw (NG0600 guard).
- [ ] Orange block uses tokens: the component SCSS references `--svy-acc-soft2`, `--svy-acc-line` and `--svy-acc-fg` and contains no hard-coded hex colors (a spec reads the stylesheet text or asserts computed class names).

### Store / navigation / scroll
- [ ] `activeSession` returns the active `Session`; `activeChildSignals` returns the map entry for the active id.
- [ ] `saveScroll` then `takeScroll` returns the saved value once, a second `takeScroll` returns `undefined`.
- [ ] message-list `getScrollState()` returns `{ top, pinned }` from a fake element (`scrollTop`, `scrollHeight`, `clientHeight`).
- [ ] With `restoreScroll = { top: 400, pinned: false }` and `scrollHeight - clientHeight = 1000`, after render `scrollTop === 400`; with a `top` of 5000 it clamps to 1000; with `pinned: true` it ends at the bottom; applied once (a later content change does not reset it); the prepend anchor does not alter the restored value in the same pass.
- [ ] With `restoreScroll = null` the existing message-list behaviour (specs unchanged) holds.
- [ ] app.component: opening a child from its parent calls `saveScroll(parentId, ...)`; going back to the parent passes the saved state to the list; opening an unrelated session saves nothing.
- [ ] app.component: when `activeIsSubagent()` is true the composer is absent and `svy-agent-footer` + `svy-agent-view` are present; when false the composer and message-list are present and the agent components are absent.
- [ ] Orphan child (`parentID` set, parent not in the list): header has no back button, footer has no "Open parent session".

### Regression
- [ ] All existing specs (`agent-status`, `agent-card`, `chat-store`, `message-list`, `message-item`, `app.component`) pass; `ng build` succeeds.

## 6. Out of scope
- File reader / opening files from chips and the real file model (SVY-21851); only the empty `data-agent-files` slot and the `files` input.
- New opencode endpoints, fetching the parent's tool part, or showing the Task `description` (not available from the child).
- Caching the parent's already-loaded older pages across navigation.
- Sending anything from the agent view (no input, no stop/resume actions).
- Changes to the agent card visuals or the status derivation semantics (beyond the shared helper extraction).
- Pushing to the remote; tests that create real model sessions (fixtures only).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Child persisted messages: first user message is the Orchestrator `prompt`, follow-ups arrive as further user messages, a follow-up to a running child adds none (assumed no divider) — not verified live on opencode 2.4.1 | implementer (live check) | open |
| Agent name source: assumed `info.agent` on the first assistant message, then `session.agent`, then the `(@name subagent)` title pattern, then `'Agent'` | implementer (live check) | open (assumption documented) |
| Report block title for stops other than INPUT_REQUIRED | reporter | decided by default: generic orange "Stopped" with the reason, same as the cards |
| Long task text | reporter | decided by default: clamp with "Show more" |
| Report block per round or only last | reporter | decided by default: only the last round; earlier rounds end with a "Stopped" step |
| Orphan child (no parent) | reporter | decided by default: hide back button and "Open parent session" |
| The "answer" in the resume divider is the Orchestrator's prompt text, which may be a rephrasing of the user's reply | reporter | accepted assumption |
| INPUT_REQUIRED reports, parent stopped while the child continues, and shutdown interrupts are not confirmed live (carried over from SVY-21849) | implementer | open |

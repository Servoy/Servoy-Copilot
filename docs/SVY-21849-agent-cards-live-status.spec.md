# Spec: SVY-21849 — Servoy AI: show every agent the Orchestrator starts as a card with live status

> Revision for opencode **2.4.1** (pin bumped from 2.2.0). Since 2.4.1 the `subagent` tool always runs in the background, so the tool part no longer says anything about the child's progress. Status is now derived from the **child session** (busy set, outcome, interrupt reason) plus the `<background-result>` report that is delivered to the parent. Sources: opencode checkout `C:/Users/jcomp/git/opencode` at tag `v2.4.1` (paths below are relative to it unless prefixed `webui:` = `bundles/com.servoy.eclipse.opencode/webui/src/app/`), and `D:\TEMP\opencode\opencode-2.4.1-break-check.md`.

## 1. Goal

Replace the collapsible tool row for a subagent spawn with an **agent card**: agent name, tag ("new session" / "continued"), status (Running with spinner / Done in green / Stopped in orange), a one-line summary and an Open button. The card appears the moment the tool part exists, updates live from the `/event` bus, and shows the same final status when a session is reopened. A stopped agent NEVER shows a spinner.

The status logic lives in ONE pure, reusable util (`services/agent-status.ts`) so SVY-21846 (sidebar) and SVY-21850 (agent view) can reuse it. The Open button already exists in the current row, emits the child session id and is wired to the existing `openSession` flow; the card keeps that contract.

Part 5 of 9 of the redesign. Builds on SVY-21845 (3325ff9, `--svy-*` tokens) and SVY-21852 (2643875). Blocks SVY-21850 and SVY-21851.

**Assumed to be in place (done by another change, not part of this case):** pin `@opencode/cli` = `2.4.1`, and `chat-store.service.ts` handling `session.tool.failed` (alias `session.tool.error` kept) in `onEvent` (already visible at `webui:services/chat-store.service.ts:776-779`).

## 2. Background

### 2.1 Today (webui, verified in source)

- `services/part-utils.ts:175-219`: `isSubagentTool(part)` matches tool `subagent` or `task`; `subagentSessionId(part)` looks in `state.metadata.sessionID`, then `state.input.sessionID`, then the `<subagent sessionID="...">` marker in `state.output`.
- `components/message-item.component.html` (~line 40-80) and `.ts`: the subagent row is a normal tool row (caret, name, subtitle, expandable output) plus a separate `.subagent-open` "Open X subagent" button (`isSubagentLink`, `subagentLabel`, `openSubagent`, output `openSession`). SCSS `.subagent-open*` at `message-item.component.scss:251-290`.
- Chain of outputs: `message-item (openSession)` -> `message-list (openSession)` -> `app.component.html:88` `(openSession)="onSelectSession($event)"` -> `ChatStore.openSession(id)`.
- `ChatStore` (`services/chat-store.service.ts`):
  - `streamingSessions` (private signal of `ReadonlySet<string>`, line 111) is tracked per session for every session (the SSE stream is global). `session.execution.started` sets it (717-723); `session.idle` / `execution.succeeded` / `execution.failed` / `session.error` clear it (724-739). **`session.execution.interrupted` and `session.status` are NOT handled** (`default: break`, line 795), so after a user interrupt the flag is only cleared by the deprecated `session.idle`.
  - `session.tool.progress` carries the child `sessionID` in metadata; `GET /message` omits metadata of a still-running tool, worked around by `toolMetaByCallId` + `backfillToolMetadata`.
  - The session list (`api.listSessions()`, lines 285 and 316) is already fetched; `Session` (`models/opencode.models.ts:31`) is loose (`[key: string]: unknown`) and `SessionTime` has no `busy`/`idle`, `Session` has no `outcome` field typed yet.
- Synthetic message handling today:
  - `part-utils.ts:10-27` `isSyntheticPart` hides parts with `synthetic === true`, text starting with `<system-reminder`, or a bare `[system: …]` marker. It does **not** know `<background-result`.
  - V2 `synthetic` messages (`packages/schema/src/session-message.ts:94-100`: `{ ...Base, text, description?, type: "synthetic" }`, plus `metadata`) have a top-level `text` and **no `content`**. `v2-mapping.ts:36-78` `mapV2Message` only turns a `user` message's `text` into a part, so a `synthetic` message maps to `info` (spread of the raw message: `info.role === 'synthetic'`, `info.text`, `info.description`, `info.metadata` all survive) with **zero parts**. `chat-store.service.ts:1399-1404` `isRenderableMessage` drops every non-user message without a renderable part, so a seeded `<background-result>` message is **already not rendered**, but its `info.text` is discarded together with it. Live, `session.synthetic` is not in the `onEvent` switch (ignored). Conclusion: no raw rubbish today, but also no use of the result for status. Defensive filter still needed (4.x / 3.6).
- Tests of the old link: `message-item.component.spec.ts` lines ~82-130 and the describe `always-visible "Open subagent" link (SVY-21764)` lines ~512-631.

### 2.2 Git history (from triage)

- `3325ff9` SVY-21845: three-area layout, theme toggle, `--svy-*` palette in `styles.scss` (light + dark): `--svy-ok` (#1f8a4c), `--svy-ok-fg`, `--svy-accent-wait` (#b86b00), `--svy-stop` (#9a5b00), `--svy-idle`, `--svy-danger`, `--svy-accent`, `--svy-accent-strong`.
- `2643875` SVY-21852: status panel; did not touch message rendering.
- The current subagent row/link comes from earlier cases (SVY-21764 "always-visible Open subagent link").
- No commit implements an agent status.

### 2.3 opencode 2.4.1 findings (read from source at `v2.4.1`)

**A. The `subagent` tool is always background** (`packages/core/src/tool/plugin/subagent.ts`).
- `Input = { agent, description, prompt, model?, sessionID? }`. `agent` is the name; **`sessionID` present = follow-up to an existing child ("Omit to start a new one")**. There is no `background` flag and no `task_id` any more (`task_id`/`task` remain only as legacy V1/old-session shapes).
- `Output = { sessionID, status: "running", output }`. `execute` emits `context.progress({ sessionID: child.id, status: "running" })` first, prompts the child, starts the job (`subagents.start(recovery)`) and returns **immediately** with `metadata: { sessionID, status: "running" }` and output text `The subagent is working in the background (sessionID: ses_…). You will receive a <background-result kind="subagent"> message …`.
- Consequence: the parent tool part is `completed` within moments, while the child keeps running. **`completed` now means "launched", never "finished".** `metadata.status` is always the literal `"running"` and must be ignored. `metadata.sessionID` is present while running and after completion (child id for Open: unchanged and still correct).
- The old `<subagent sessionID="…">` marker no longer appears in tool output (legacy fallback only matters for sessions recorded on 2.2.0).
- "Continued" is modelled by `input.sessionID` (non-empty string), exactly as before; new children have no `input.sessionID`. `metadata.sessionID` is set in both cases and must not be used for kind. A follow-up to a running child "joins its current run" (tool description).

**B. Completion is reported to the PARENT as a synthetic message** (`packages/core/src/session/subagent-completion.ts`, `background-result.ts`, `session/execution/subagent-report.ts:58-70`).
- Text: `<background-result kind="subagent" sessionID="<child>" state="<state>" description="<input.description>">\n<body>\n</background-result>`; `metadata: { source: "subagent", childID, agent, state }`; message `type: "synthetic"` with `text`, `description`, `metadata`.
- Delivered after **every completed round** of the child (not only once), so a continued child produces several results.
- `state` (envelope attribute) is `completed` | `error` | `cancelled` | `running`; `running` = the child went idle but still has outstanding background work (`recovery.outstanding`); then `body` ends with `\n\nBackground work still running:\n- …` lines.
- `body`: completed = the child's **last assistant text** (all text parts joined) or `NO_TEXT` = "Subagent completed without a text response."; error = the error message or "Subagent failed"; cancelled = "Subagent cancelled" (or output/error). Outcome mapping: succeeded → completed, failed → error, interrupted → cancelled.
- **Shutdown interruption produces NO report** (restart recovery resumes it later, `session/execution/restart.ts:443`).
- The `[agent pack] Status:` line is part of the child's last assistant text, so it arrives in `body`. This is the one place the report text is available without fetching the child.

**C. Child session signals** (`packages/schema/src/session.ts`, `core/src/session/projector.ts:851-863`).
- `outcome?: "succeeded" | "failed" | "interrupted"` — outcome of the last completed execution; absent until the first terminal transition.
- `time.busy` = start of the latest execution, `time.idle` = end of the latest completed one; running ⇔ `busy > idle` or idle undefined.
- Shutdown interrupts do **not** write idle/outcome, so `busy > idle` can be **stale** after a crash/shutdown. Therefore `time.busy > time.idle` is a hint, not proof of running (design rule below).
- `GET /api/session/active` → `{ data: { [sessionID]: { type: "running" } } }`, absent = inactive; foreground drains owned by this process (existed in 2.2.0, unused by the webui). Whether background child drains are included is **not verified** (sectie 7).
- `session.execution.interrupted` event: `{ sessionID, reason: "user" | "shutdown" | "superseded" | "inactivity" }` (`schema/src/session-event.ts:259-263`); outcome only says "interrupted", the reason only exists in the event (live) or the experimental log.
- `session.status` bus event (ephemeral): `{ sessionID, status: {type:"idle"} | {type:"busy"} | {type:"retry",…} }`; `session.idle` is deprecated but still emitted. There is NO `GET /session/status` route in 2.4.1.

**D. Aborted tool part** (`session/runner/step.ts:66,272-277`, `publish-llm-event.ts:~373-381`). Stopping the parent fails its still-unsettled tool calls with `session.tool.failed`, `error.type === "aborted"` (message "Tool execution interrupted"; for `subagent` enriched with `(sessionID: <child>)`). Because the subagent tool part is normally already `completed`, it will NOT turn aborted for a running child; the child's own state is the truth. `error` on a subagent part now essentially means "the launch failed" (unknown agent, depth limit, denied, child not found, …).

**E. Event/field names relevant to the webui:** real failure event is `session.tool.failed` (payload `error: { type, message }`); `session.tool.error` never existed. `session.execution.started/succeeded/failed/interrupted` carry `{ sessionID }` (failed also `error`).

### 2.4 Earlier live findings (observed on 2.2.0 — kept as evidence; still valid where marked)

Read from a running 2.2.0 server through the BFF, 3 sessions (1 root, 2 children), all finished normally:

- The spawn tool is named `subagent`; input `{ agent, description, prompt }` — the agent name is in `input.agent` (NOT `subagent_type`). **Still valid in 2.4.1** (source, 2.3.A).
- Child session objects carry `outcome` (`succeeded` seen) and `time.idle`; `GET rest_api/session/:id` returns it. **Still valid**; `GET /session` (list) uses the same `Session` info (route unchanged `groups/session.ts:128`), so the outcome of every child comes with the list the store already fetches. Other outcome values were not observed live (source says failed/interrupted exist).
- 2.2.0 specifics that are **superseded**: the tool part `completed` with `state.content[0].text = <subagent sessionID state="completed">…report…</subagent>` and `metadata.status` mirroring the tool status. In 2.4.1 the report is NOT in the tool output (see 2.3.A/B).
- A Reviewer report in the 2.2.0 sample had no `Status:` line: a successful report without `Status:` is the normal case and must be `Done` (still the rule).
- `GET rest_api/session/status` answered 400 on 2.2.0; it does not exist in 2.4.1 either. Not used.

### 2.5 Report convention

"Stopped — needs a decision" is **not an opencode concept**. A handed-back agent is a child turn that finished normally (`outcome: succeeded`, `state="completed"`) whose last text ends with `Status: INPUT_REQUIRED` (also `BLOCKED`, `INCOMPLETE`, `FAILED`, `REJECTED`; `COMPLETED` for success; see `~/.servoy/opencode/agents/Developer.md`). This text convention remains the only way to get that label; opencode-level stops (interrupted/cancelled/failed/aborted) are a different, technical "Stopped".

## 3. Design

### 3.1 `services/agent-status.ts` (pure, no Angular imports)

```ts
export type AgentRunStatus = 'running' | 'done' | 'stopped';
export type AgentKind = 'new' | 'continued';
export type AgentReportStatus =
  'COMPLETED' | 'INPUT_REQUIRED' | 'BLOCKED' | 'INCOMPLETE' | 'FAILED' | 'REJECTED';
export type ChildOutcome = 'succeeded' | 'failed' | 'interrupted';
export type InterruptReason = 'user' | 'shutdown' | 'superseded' | 'inactivity';
export type BackgroundResultState = 'completed' | 'error' | 'cancelled' | 'running';

/** Everything the store knows about one child session. All fields optional = "not known". */
export interface ChildSignals {
  /** Confirmed running: streaming set (events) or /session/active. NOT time.busy>time.idle alone. */
  readonly busy?: boolean;
  /** Last completed execution (session list/updated, or live execution.succeeded/failed/interrupted). */
  readonly outcome?: ChildOutcome;
  /** Reason of the last execution.interrupted seen live (cleared when the child starts again). */
  readonly interruptedReason?: InterruptReason;
  /** Latest <background-result> delivered to the parent for this child. */
  readonly result?: { readonly state: BackgroundResultState; readonly body: string };
}

export interface AgentStatusContext {
  readonly child?: ChildSignals;
  /** The parent turn that owns this tool part is live (parent busy AND message not completed). */
  readonly parentLive?: boolean;
}

export interface AgentStatus {
  readonly status: AgentRunStatus;
  readonly kind: AgentKind;
  readonly name: string;            // agent name
  readonly label: string;           // 'Running' | 'Done' | 'Stopped — needs a decision' | 'Stopped'
  readonly summary: string;         // single line, '' when none
  readonly childSessionId: string | null;
  readonly report: AgentReportStatus | null;
}

export function parseReportStatus(output: string | undefined): AgentReportStatus | null;
export function parseBackgroundResult(text: string | undefined):
  { childId: string; state: BackgroundResultState; body: string } | null;
export function deriveAgentStatus(part: Part, ctx?: AgentStatusContext): AgentStatus;
```

`deriveAgentStatus` returns `AgentStatus` for any part for which `isSubagentTool(part)` is true (callers guard with `isSubagentTool`).

**Derivation rule (exact; the tool part is only the "launch" signal, the child is the truth):**

```
tool  = part.state?.status
c     = ctx.child ?? {}
live  = ctx.parentLive === true
noSig = c.busy === undefined && c.outcome === undefined && c.result === undefined
        && c.interruptedReason === undefined

1. if c.busy === true:                                  -> running   (child confirmed busy beats everything,
                                                                      also a stale result/outcome of an earlier round)
2. // child is not (known to be) busy from here on: running is only possible via 'launching'
3. if tool == 'error':                                  -> stopped   (launch failed / aborted; error.type 'aborted' or any)
4. if c.result?.state in (error, cancelled)
   or c.outcome in (failed, interrupted)
   or c.interruptedReason is set:                       -> stopped
5. if c.result?.state in (completed, running)
   or c.outcome == 'succeeded':
       report = parseReportStatus(result body)          // body = result.body; empty when only the outcome is known
       status = report in (INPUT_REQUIRED, BLOCKED, INCOMPLETE, FAILED, REJECTED) ? 'stopped' : 'done'
6. // nothing says it ended, nothing says it runs
   if (tool in (undefined, pending, running, streaming) or (tool == 'completed' and noSig)) and live:
                                                        -> running   (launching: the parent turn is still live,
                                                                      the child's execution.started has not arrived yet)
7. else                                                 -> stopped   (running-claim without a busy child: never a spinner;
                                                                      e.g. reopened session, shutdown interrupt without outcome,
                                                                      stale part, server restart)
```

Notes:
- **Never-spinner invariant:** `running` ⇔ `c.busy === true` OR (`live` AND tool is not terminal / no signals at all). In particular, with `parentLive` false the ONLY way to be `running` is a confirmed-busy child. A `completed` launch with an unknown child state on a reopened session is `stopped`, never `running`. The card derives the spinner from the status only; there is no separate flag.
- Rule 4 precedes 5 so `state="cancelled"`/`error` result, outcome `failed`/`interrupted` and a live interrupt reason (user, shutdown, superseded, inactivity) all give `stopped`, even if an older round had `succeeded`. A new round clears the live signals (below): `busy` flips true (rule 1), `interruptedReason` and live outcome are cleared on `execution.started`.
- `result.state === 'running'` (child idle but outstanding background work) is treated like `completed` when the child is not busy (rule 5): the card says Done; the summary shows the body's first line. Zie sectie 7 (Confirm live 4).
- A stop-`Status:` in the report wins over a `succeeded` outcome (rule 5): `Status: INPUT_REQUIRED/BLOCKED/INCOMPLETE/FAILED/REJECTED` → `stopped`. `COMPLETED`, no line, or an unknown value → `done`.
- Several tool parts can refer to the same child (a follow-up to a continued child). All of them reflect the CHILD's current state; the summary comes from the same latest result. Accepted (sectie 7, aanname).
- `label`: running → `Running`; done → `Done`; stopped with `report === 'INPUT_REQUIRED'` → `Stopped — needs a decision`; every other stopped → generic `Stopped`. The reason goes in the summary (below), not in the label.

**`parseReportStatus(output)`** — the single place for the `Status:` convention:
- Scan the lines from the end; the first (i.e. last in the text) line matching `/^\s*(?:[*_`>#\-\s]*)Status:\s*\**\s*([A-Z_]+)/i` (tolerates markdown bold/bullets such as `**Status:** INPUT_REQUIRED`) whose value, upper-cased, is one of the six known values wins. Unknown value → `null`. No line → `null`.
- Operates on the report body only. Before scanning, strip the `<background-result …>` / `</background-result>` envelope lines, the legacy `<subagent …>` / `</subagent>` lines and a trailing `Background work still running:` block (header line + following `- ` lines), so envelope attributes and the outstanding-work list can never be mistaken for the report.
- Pure and total: `undefined`/empty → `null`, never throws.

**`parseBackgroundResult(text)`** — parses the synthetic message text: `<background-result` + attributes `kind="subagent"`, `sessionID="…"`, `state="…"`; returns `null` for any other `kind` or a missing/unknown attribute; `body` = text between the opening tag line and `</background-result>`, trimmed. Also used by the store (3.3) and by `isSyntheticPart` for the defensive filter (3.6). The `description` attribute is ignored (it may contain quotes). Unknown `state` → `null`.

**Summary (one line):**
- `done`/`stopped` with a result body: the first non-empty line AFTER the `Status:` line; if the `Status:` line is the last line or absent, the first non-empty line of the body after stripping the envelope and markdown markers (`#`, `*`, `-`, `>`, backticks). Whitespace collapsed. The body `Subagent completed without a text response.` counts as empty.
- No body (only the outcome is known): `''` → fall back to `input.description`.
- Stopped without a report, in order: `tool == 'error'` → first line of `state.error.message` (or string `state.error`); `interruptedReason` → `Stopped by user` / `Interrupted by restart` / `Replaced by a newer prompt` / `Stopped after inactivity`; `result.state == 'cancelled'` → `Cancelled`; `result.state == 'error'` or `outcome == 'failed'` → first line of the error body or `Failed`; `outcome == 'interrupted'` → `Interrupted`; anything else stopped → `Not running` (stale/unknown).
- Running: `state.title` if non-empty, else `input.description`, else `''`.
- Truncate to one line: max 120 characters, cut at the last whole word where possible and suffix `…`. Never contains a newline.

**Name:** `input.agent` (2.4.1 `subagent` tool and 2.2.0 sample), else `input.subagent_type` (V1 `task`), else `input.name`, else the generic fallback `Agent`. Display unchanged.

**Kind:** `continued` when `input.sessionID` or `input.task_id` is a non-empty string, else `new`. `metadata.sessionID` is ignored for this.

**childSessionId:** `subagentSessionId(part)` from `part-utils.ts` (reused, not duplicated). In 2.4.1 the `metadata.sessionID` path (step 1) always resolves; the `<subagent sessionID=…>` marker path (step 3) is kept only for sessions recorded on 2.2.0.

### 3.2 `agent-card` component (new)

File: `components/agent-card.component.{ts,html,scss}`, selector `svy-agent-card`, standalone, `ChangeDetectionStrategy.OnPush`, signals.

```ts
readonly agent = input.required<AgentStatus>();
readonly open = output<string>();         // child sessionId
```

Layout (design look table from the prototype):

| Status | Dot / icon | Label colour | Label |
|---|---|---|---|
| running | `--svy-accent-strong` dot + spinner (`aria-hidden`) | `--svy-accent-wait` | Running |
| done | `--svy-ok` dot | `--svy-ok-fg` | Done |
| stopped | `--svy-accent-wait` dot (static, no spinner) | `--svy-accent-wait` | Stopped — needs a decision / Stopped |

Elements, in order: status dot/spinner, agent **name**, **tag** ("new session" / "continued", muted pill), **status label**, **summary** (one line, `text-overflow: ellipsis`, `title` attribute holds the full text), **Open** button.

- Open button: `<button type="button" class="agent-open" [disabled]="!agent().childSessionId" [attr.aria-label]="'Open ' + name + ' agent session'" (click)="onOpen()">Open</button>`. Always rendered, `disabled` until the child id is known. `onOpen()` emits `open` with the id only when non-null.
- Accessibility: card root `role="group"` with `aria-label="<name> agent, <tag>, <label>"`. The label text is always visible, so status is never conveyed by colour only. No per-card live region. Spinner `aria-hidden="true"`, respects `prefers-reduced-motion`. Existing tokens only (4.5:1 in light and dark).
- No hard-coded hex values: use `var(--svy-*)` only. Spinner: CSS-only rotating border (reuse an existing spinner if present).

### 3.3 Store: child signals (no behaviour break)

All additions are additive; existing `streaming`, `streamingSessions`, title refresh and `session.idle` handling keep working unchanged.

**New state (private signals unless noted):**
- `streamingSessions` (existing) stays the "busy per events" set. Public: `readonly busySessionIds = this.streamingSessions.asReadonly()` (for SVY-21846).
- `activeSessionIds: signal<ReadonlySet<string>>` — seeded from `GET rest_api/session/active` (new `OpencodeApiService.getActiveSessions(): Observable<ReadonlySet<string>>`, unwraps `{data}` keys; failure → empty set, never throws). Merged into the busy set (see below). Called on init, after `openSession`, and after the SSE stream reconnects. One request, not N.
- `liveOutcomes: signal<ReadonlyMap<string, ChildOutcome>>` — set on live `execution.succeeded` (`succeeded`), `execution.failed` (`failed`), `execution.interrupted` (`interrupted`), removed on `execution.started`. Overrides the list's `outcome` because `session.updated` may not fire for an outcome change (unverified).
- `interruptedReasons: signal<ReadonlyMap<string, InterruptReason>>` — set by `session.execution.interrupted` (`data.reason`), removed on `execution.started`.
- `backgroundResults: signal<ReadonlyMap<string, {state, body}>>` — latest `<background-result>` per child id, filled by (a) seeding: when messages of a session are seeded (`openSession`/reload), BEFORE `isRenderableMessage` drops it, every message with `info.role === 'synthetic'` (V2 `type: 'synthetic'`) whose `info.text` parses with `parseBackgroundResult` updates the map (later messages in order win); (b) live: new `case 'session.synthetic'` in `onEvent` parses `data.text` the same way (payload shape from `message-updater.ts:167-176`: `{ sessionID, text, description?, metadata? }`; unverified that the bus delivers it live — fallback below). The legacy 2.2.0 envelope (`<subagent sessionID="…" state="…">` in the tool output) is handled in `agent-status.ts` itself as a legacy result, so old sessions still get a final status.

**Busy:** `busySessionIds = streamingSessions ∪ activeSessionIds` (computed; `streamingSessions` remains the writer for events). New event handling (additive cases in `onEvent`):
- `session.execution.interrupted`: `setSessionStreaming(id,false)`, record reason + `liveOutcomes[id]='interrupted'` (also clears the active session `streaming`, fixing the "only session.idle clears it" gap), no title refresh.
- `session.status`: `busy` → streaming true; `idle` → streaming false (+ remove from `activeSessionIds`); `retry` → leave as is.
- `session.execution.started`: additionally clears `liveOutcomes`, `interruptedReasons` for that id and the id's membership in `activeSessionIds` stays.
- `session.execution.succeeded/failed` and `session.idle`: additionally write `liveOutcomes` (idle alone writes nothing; outcome comes from succeeded/failed/interrupted or the list).
- `time.busy > time.idle` from the session list is **not** used as busy (stale after shutdown, see 2.3.C); it is only recorded in the debug overlay. If live testing shows `/session/active` does not include background children (sectie 7, Confirm live 1), a documented fallback is "busy > idle AND `time.updated` within 2 minutes".

**Outcome from the session list:** `Session` model gets `outcome?: 'succeeded'|'failed'|'interrupted'` and `SessionTime` gets `busy?`, `idle?`. No new request: the list the store already loads (lines 285/316) and `session.updated` events carry them for every session including children, so reopening needs NO per-card fetch.

**Public computed `childSignals: Signal<ReadonlyMap<string, ChildSignals>>`**, built for every session that has a `parentID` in the loaded list plus any id appearing in `backgroundResults`: `busy = busySessionIds.has(id) ? true : undefined`... precisely: `busy: true` when in the busy set, else `false` once the active endpoint has been seeded at least once, else `undefined` (unknown); `outcome = liveOutcomes.get(id) ?? session.outcome`; `interruptedReason = interruptedReasons.get(id)`; `result = backgroundResults.get(id)`. Cheap (children only) and recomputed only when one of the signals changes.

**Live delivery, confirmed on 2.4.1:** the result does NOT arrive as `session.synthetic`. The subagent report is admitted through the parent's inbox (`subagent-report.ts` `deps.inbox.admit({ item: { type: 'synthetic', payload: { text, description, metadata }, delivery: 'steer' } })`) and the bus emits `session.inbox.enqueued` with `data: { sessionID, inboxID, item }`. The store therefore also handles `session.inbox.enqueued` where `item.type === 'synthetic'` and parses `item.payload.text`; `session.synthetic` stays handled as an alias. With this the card shows the real summary live, with no reload and no extra request, so the fallback below is NOT needed.

**Fallback if `session.synthetic` is not delivered live (not needed, kept for reference):** the result is picked up at the next seed; meanwhile status relies on busy/outcome (rule 4/5 still gives done/stopped without the body); summary falls back to `input.description`. To close the gap after a child finishes, the existing post-idle refresh (`scheduleTitleRefresh`, lines ~1120-1190) is NOT reused; instead, when a child that has a tool card in the ACTIVE session transitions busy → not busy, the store re-fetches ONLY the active parent session's latest messages once (bounded: one request per child completion, debounced 500 ms, active session only). Decision: implement this refresh only if live testing shows `session.synthetic` is not on `/event` (sectie 7, Confirm live 2); otherwise skip it.

**Why no per-card fetch:** the report body already reaches the webui via the parent's own message list (the synthetic message), the outcome via the session list, busy via events + one `/session/active`. A child-message fetch (last assistant message) would be N requests on reload and is only the fallback for a missing result (see section 6).

### 3.4 Wiring (inputs, no store injection in presentational components)

`message-item` has no store dependency and its specs create it without providers; keep it that way. Pass data down as inputs:

- `AppComponent` template: `<svy-message-list [childSignals]="store.childSignals()" [turnBusy]="store.streaming()" ...>`.
- `MessageListComponent`: new inputs `childSignals = input<ReadonlyMap<string, ChildSignals>>(new Map())` and `turnBusy = input<boolean>(false)`, forwarded to each `svy-message-item`.
- `MessageItemComponent`: new inputs `childSignals` (same type) and `turnBusy`. Computed `parentLive = turnBusy() && completed() == null` (only the in-progress assistant message of a busy session counts; an older message with a stale `running` part in a busy session does not spin). It builds `AgentStatus` per subagent part via `deriveAgentStatus(part, { child: childId ? childSignals().get(childId) : undefined, parentLive })`. Status updates live through the input signals (zoneless-safe: derive in a method/computed reading the signals).
- `message-item` template: for `isSubagentTool(part)` render `<svy-agent-card [agent]="agentOf(part)" (open)="openSession.emit($event)">` INSTEAD of the generic tool row and the `.subagent-open` link. All other tool rows and parts are untouched. The card is not expandable; the full report is visible by opening the child session (SVY-21850).
- Remove now-dead members from `message-item`: `isSubagentLink`, `subagentLabel`, `openSubagent`, the `.subagent-open*` SCSS and template block. `openSession` output stays (now fed by the card).

### 3.5 Live behaviour and reopened sessions

- **Appears at start:** the tool part exists at `pending`/`running` (`session.tool.input.started`/`called`); the card renders immediately as `Running` (parent turn live → rule 6), name from `input.agent` (generic `Agent` until the input is complete), summary from `input.description`, Open disabled.
- **Child id arrives:** `session.tool.progress` (metadata `sessionID`) → Open enabled. The tool part then goes `completed` almost at once (launched); the card stays `Running` as long as the child is busy (rule 1; `execution.started` of the child) or, in the short gap before it, via rule 6 while the parent turn is live.
- **Parent turn ends right after launch (normal case):** `turnBusy` goes false. If the child is busy → still `Running` (rule 1). If the child's `execution.started` was never seen and nothing is confirmed → `Stopped` (rule 7), never a spinner (sectie 7, Confirm live 3, beschrijft de mogelijke flikker van een frame).
- **Child finishes:** `execution.succeeded` → `liveOutcomes=succeeded`, busy false → `Done` (summary from `input.description` until the result arrives, then the report line). `<background-result state=completed>` arrives on the parent → body parsed → stop-`Status:` → `Stopped`/`Stopped — needs a decision`, else `Done` with the one-line summary.
- **Child stopped:** `execution.interrupted` (reason) / `execution.failed` / result `cancelled|error` → `Stopped` with the reason summary. User stops the parent: the parent turn's tool parts fail with `aborted` (not for an already-`completed` launch part); the card reflects the child via the signals, so a child that keeps running stays `Running`, a child that was interrupted shows `Stopped`.
- **Continued agent:** a follow-up (`input.sessionID`) starts a new round → `execution.started` clears the previous outcome/reason, busy true → `Running`; its own result later replaces the old one.
- **Reopened session:** the parent's messages are seeded (tool parts + synthetic messages → `backgroundResults`), the list provides `outcome`, one `GET /session/active` provides busy. Finished children show `Done`/`Stopped` with the same summary; running children show `Running` iff active; anything unconfirmed shows `Stopped` ("Not running"). No per-card request.
- **Legacy 2.2.0 sessions:** tool `completed` with a `<subagent … state="…">` envelope in the output → treated as a result (`state` mapped; `completed` default) so they keep their final status.

### 3.6 Transcript filtering of `<background-result>`

- The seeded `synthetic` message already renders nothing (no parts, dropped by `isRenderableMessage`). Keep that, and read its `info.text` for the status BEFORE it is dropped (3.3), so the drop does not lose the report.
- Add `<background-result` to `isSyntheticPart` in `part-utils.ts` (text part whose trimmed text starts with `<background-result`), next to `<system-reminder` / `[system: …]`. Defensive: if a path ever turns the synthetic message into a text part (V1 fallback `message.updated`, future mapping, user-role echo), it stays hidden. Unit-tested in `part-utils.spec.ts`; `session-export.ts` is unaffected (it exports the raw messages).
- A visible "agent finished" hint in the transcript is out of scope (the card carries it).

### 3.7 Migration of the old tests

The old link tests are migrated, not deleted:
- `message-item.component.spec.ts` ~82-130: row+link and `input.sessionID` cases → assert that a subagent part renders `svy-agent-card` (not `.tool-row`), that clicking `.agent-open` emits `openSession` with the child id (from `metadata.sessionID`, `input.sessionID`, and the legacy output marker), and that no id → Open `disabled`.
- ~512-631 describe: rename to `agent card Open button (SVY-21849, supersedes SVY-21764 link)`; keep the intent of each case: rendered on first paint without hover, emits with resolved id, clicking the card body does not emit, no id → disabled, non-subagent tool part renders no card, other tool rows keep their caret/expand behaviour (a subagent part has no caret).

## 4. Implementation plan

1. `webui/src/app/services/agent-status.ts` — types, `parseReportStatus`, `parseBackgroundResult`, `deriveAgentStatus`, summary/name/kind helpers, legacy `<subagent>` envelope handling (reuses `isSubagentTool`, `subagentSessionId` from `part-utils.ts`).
2. `webui/src/app/services/agent-status.spec.ts` — see section 5.
3. `webui/src/app/services/part-utils.ts` (+ `.spec.ts`) — `isSyntheticPart` also true for text starting with `<background-result`; no other change (`subagentSessionId` keeps its three paths; the marker path is legacy; refresh the comments that still describe the 2.2.0 output).
4. `webui/src/app/models/opencode.models.ts` — `Session.outcome`, `SessionTime.busy/idle`.
5. `webui/src/app/services/opencode-api.service.ts` (+ spec) — `getActiveSessions()` for `rest_api/session/active` (`{data: {id: {type}}}` → set of ids; error → empty set).
6. `webui/src/app/services/chat-store.service.ts` (+ spec) — `busySessionIds`, `activeSessionIds` seeding, `liveOutcomes`, `interruptedReasons`, `backgroundResults` (seed before `isRenderableMessage`, live `session.synthetic`), new cases `session.execution.interrupted` and `session.status`, additions to `execution.started/succeeded/failed/idle`, computed `childSignals`.
7. `webui/src/app/components/agent-card.component.{ts,html,scss}` + `agent-card.component.spec.ts`.
8. `webui/src/app/components/message-item.component.{ts,html,scss}` — new inputs `childSignals`, `turnBusy`; `agentOf(part)`; render card for `isSubagentTool`; remove the old link members/template/SCSS; import `AgentCardComponent`.
9. `webui/src/app/components/message-list.component.{ts,html}` — forward the two inputs.
10. `webui/src/app/app.component.html` — bind `[childSignals]="store.childSignals()"` and `[turnBusy]="store.streaming()"`; update `app.component.spec.ts` store mock (`childSignals: signal(new Map())`, `busySessionIds: signal(new Set())`).
11. `webui/src/app/components/message-item.component.spec.ts` — migrate tests (3.7) and add card integration specs; `message-list.component.spec.ts` — forwarding spec if a spec exists.
12. Run from `webui/`: `npx ng test --no-watch`, `npm run build`. Do not push.
13. Live check on 2.4.1 (not provable from source): the points in section 7 marked "confirm live".

## 5. Acceptance criteria

Functional
- [ ] Each subagent/task tool part renders as an agent card showing name (`input.agent`), tag ("new session" when no `input.sessionID`, "continued" otherwise), status label, one-line summary and an Open button; other tool rows are unchanged.
- [ ] The card appears while the tool part is `pending`/`running`, even before the child session id is known; Open is disabled until the id is known and then emits the child id.
- [ ] A launched agent (tool part `completed`) whose child is busy shows `Running` with spinner — the `completed` tool status alone never means Done.
- [ ] Done shows "Done" in green (`--svy-ok-fg`) when the child outcome is `succeeded` or the result is `completed`/`running`, and the report has no stop `Status:`.
- [ ] Stopped shows orange (`--svy-accent-wait`): "Stopped — needs a decision" only for `Status: INPUT_REQUIRED`; plain "Stopped" for `BLOCKED/INCOMPLETE/FAILED/REJECTED`, outcome `failed`/`interrupted`, result `error`/`cancelled`, `execution.interrupted` (any reason), aborted/failed tool part and any unconfirmed running claim. The summary names the reason.
- [ ] **Never-spinner:** a card whose child is not confirmed busy and whose parent turn is not live never shows a spinner, whatever the tool/part state.
- [ ] Statuses update live from `/event` without reload (child `execution.started/succeeded/failed/interrupted`, `session.status`, tool progress/success/failed, `session.synthetic`).
- [ ] After reopening a session the same cards show the same final status from persisted data (parent messages incl. synthetic results, session-list outcome, one `GET /session/active`), with NO extra request per card.
- [ ] A continued agent: a new round turns the card back to Running (previous outcome/reason cleared) and the new result replaces the old summary.
- [ ] `<background-result …>` text never appears in the transcript (seeded or live), but its report is used for the status/summary.
- [ ] `session.execution.interrupted` clears the active session's `streaming` flag (the Stop button state is correct after a user interrupt).
- [ ] Clicking Open results in `ChatStore.openSession(childId)` via the existing `openSession` output chain.
- [ ] Accessible: group `aria-label`, spinner `aria-hidden`, status readable as text, reduced-motion respected, no per-card live region; dark and light themes use only `--svy-*` tokens.

Tests (Vitest)
- [ ] `agent-status.spec.ts` — every rule of the derivation table:
  - rule 1: busy beats tool `error`, beats an older `succeeded`/`cancelled` result;
  - rule 3: tool `error` (aborted and other) without busy → stopped;
  - rule 4: result `error`, `cancelled`; outcome `failed`, `interrupted`; each `interruptedReason` (`user`, `shutdown`, `superseded`, `inactivity`) → stopped with the matching summary;
  - rule 5: outcome `succeeded` only (no body) → done; result `completed`/`running` with each of `COMPLETED`, `INPUT_REQUIRED`, `BLOCKED`, `INCOMPLETE`, `FAILED`, `REJECTED` and with no `Status:` line; stop-`Status:` beats `succeeded`; labels (`INPUT_REQUIRED` vs generic);
  - rule 6: tool pending/running/streaming + parent live → running; completed + no signals + parent live → running;
  - rule 7: the same two without parent live → stopped; completed + no signals reopened → stopped; unknown tool state → stopped;
  - new vs continued from `input.sessionID` and `input.task_id`; `metadata.sessionID` alone stays `new`; `metadata.status: 'running'` on a `completed` part is ignored;
  - `parseReportStatus` (markdown bold/bullet variants, case, last Status line wins, unknown value, empty/undefined, background-result and legacy `<subagent>` envelope stripped, `Background work still running:` block ignored);
  - `parseBackgroundResult` (all four states, other `kind` → null, unknown state → null, missing sessionID → null, multi-line body, description with quotes);
  - legacy `<subagent … state=…>` tool output still yields a final status;
  - summary (line after Status, fallback first line, `NO_TEXT` treated as empty, running uses title then description, reason summaries, error text, truncation to 120 with `…`, no newline, whitespace collapsed); name fallbacks (`agent`, `subagent_type`, `name`, `Agent`);
  - **never-spinner invariant:** exhaustive loop over tool state × busy {true,false,undefined} × parentLive × outcome × interruptedReason × result state × report values, asserting `status === 'running'` only when `busy === true` or (`parentLive` and no end signal and tool non-terminal or no signals at all), and that a `stopped`/`done` result never produces the running label.
- [ ] `agent-card.component.spec.ts`: renders each status (text, dot class); spinner present only for running and absent for done/stopped; Open disabled with no id, enabled with id, emits the id once; aria-label content; summary `title` attribute.
- [ ] `message-item.component.spec.ts`: subagent part renders the card and not the tool row/old link; `childSignals` busy → running, removing busy + parent not live → stopped; completed launch + outcome succeeded → done; result with `Status: INPUT_REQUIRED` → stopped label; migrated Open tests (3.7) pass; non-subagent rows unchanged; a synthetic message with `<background-result>` text part renders nothing.
- [ ] `part-utils.spec.ts`: `isSyntheticPart`/`isRenderablePart` false for a text part starting with `<background-result` (also with leading whitespace); prose merely mentioning it stays visible.
- [ ] `chat-store.service.spec.ts`: `busySessionIds` after child `execution.started`, dropped after `session.idle`/`execution.failed`/`execution.interrupted`/`session.status idle`; `session.status busy` sets it; `execution.interrupted` records the reason, sets `liveOutcomes=interrupted`, clears active `streaming` and does not schedule a title refresh; `execution.started` clears outcome and reason; `liveOutcomes` override the list `outcome`; `childSignals` combines busy/outcome/reason/result; seeding a session with a `synthetic` message stores the result (latest per child wins) and the message is still not in `messages()`; live `session.synthetic` stores it; `getActiveSessions` seeding (success, error → empty, unknown-until-seeded `busy: undefined`); existing `streaming`/idle/title-refresh specs unchanged and green.
- [ ] `opencode-api.service.spec.ts`: `getActiveSessions` unwraps `{data}` keys, error → empty set.
- [ ] `npx ng test --no-watch` green; `npm run build` green (no NG0600 / template errors).

## 6. Out of scope

- The agent view itself (SVY-21850), the file reader (SVY-21851), sidebar changes (SVY-21846; only note that `agent-status.ts`, `busySessionIds` and `childSignals` are the reuse points).
- Any backend/Java change, the agent pack's report format, the opencode pin bump and the `session.tool.failed` rename (done elsewhere).
- A child-message fallback fetch (last assistant message of the child) when no `<background-result>` was delivered; only revisit if the result turns out to be missing in practice. Safe default: `Done`/`Stopped` from outcome alone with `input.description` as summary.
- Showing the full report or steps inside the card; a visible transcript line for the background result; pushing.

## 7. Open questions

| Question | Owner | Status |
|---|---|---|
| Label for non-`INPUT_REQUIRED` stops (`BLOCKED`, `INCOMPLETE`, `FAILED`, `REJECTED`, interrupted, cancelled, tool error, unconfirmed): design only has "Stopped — needs a decision". Default: generic orange "Stopped", reason in the summary. | Reporter / design | Open (default applied) |
| How does a continued agent model "continue" in 2.4.1? | Developer | **Resolved:** `input.sessionID` (non-empty) = follow-up, omitted = new (`subagent.ts` Input); `task_id` is legacy; `metadata.sessionID` is always set and ignored for kind. |
| Where do the report/summary come from in 2.4.1? | Developer | **Resolved:** the synthetic `<background-result>` message on the parent (`info.text` of a `type: "synthetic"` message; body = last assistant text of the child, incl. a `Status:` line); no per-card child fetch needed. Outcome comes from the session list the store already loads. |
| Does the tool part's abort/running state still matter? | Developer | **Resolved (source):** `completed` = launched; `error` = launch failed or parent-stopped before completion (`aborted`); the child's own state decides. Confirm live (see below). |
| Replaces unverified (2) `<subagent … state=…>` attribute values | Developer | **Resolved:** superseded by `<background-result kind="subagent" state=completed|error|cancelled|running>`; legacy envelope only for old sessions. |
| Replaces unverified (3) `session.status` / `GET /session/status` | Developer | **Resolved (source):** no `GET /session/status` route; `session.status` bus event exists and is now handled; `GET /session/active` is used for seeding. |
| Child `outcome` values and whether `session.updated` fires when `outcome` changes (we also set it from live execution events). | Developer | Confirm live; safe default applied (live outcome overrides the list). |
| **Confirm live on 2.4.1 (1):** does `GET /session/active` list background child drains (not only foreground)? If not, reopening a running child shows `Stopped` ("Not running"). Safe default: events + active endpoint only; documented fallback `time.busy>time.idle` AND `time.updated` < 2 min. | Developer | Open (safe default: never a spinner) |
| **Confirm live (2):** is `session.synthetic` delivered on `/event`? | Developer | Resolved live on 2.4.1: no; the report arrives as `session.inbox.enqueued` with a synthetic item, now handled (see 3.3). Verified: a finished child shows its real summary without reload. |
| **Confirmed live on 2.4.1 (2026-11-24):** Running (spinner, `/session/active` lists the child while the parent is idle), Done (`outcome: succeeded`) and Stopped (`POST /session/:id/interrupt` gives `outcome: interrupted`, `background-result state="cancelled"`, summary "Stopped by user") all update without reload; a reopened session shows the same final status. | Developer | Done |
| **Not yet confirmed live:** the parent being stopped while the child keeps running, a shutdown interrupt, and the `Status: INPUT_REQUIRED` ("needs a decision") card. | Developer | Open (safe default: never a spinner) |
| **Confirm live (3):** ordering of child `session.execution.started` vs the parent tool part going `completed`. A gap would flicker `Stopped` for one frame when the parent turn ends first (rule 6 covers only a live parent). Safe default: accept; if visible, add a 1-2 s debounce before showing `Stopped` for a no-signal launch. | Developer | Open (default applied) |
| **Confirm live (4):** exact `state` and body for a child with outstanding background work (`state="running"`); default treats it as Done when the child is idle. Should it show Running instead? | Developer / Reporter | Open (default applied) |
| **Confirm live (5):** `execution.interrupted` for a user stop of the PARENT while the child continues (child stays busy, card stays Running) vs child also interrupted; and shutdown interrupt leaves no outcome → card shows `Stopped` / "Interrupted by restart" only if the event was seen live, else "Not running". | Developer | Open (default applied) |
| Several tool parts for the same (continued) child all show the child's current status/latest summary; older cards are not frozen to their round. | Reporter | Assumption |
| A previous round's `outcome` may show briefly as Done/Stopped between a follow-up launch and its `execution.started` (rule 5 before rule 6). | Developer | Assumption (short window) |
| Reliance on the agent-pack `Status:` convention: a format change silently turns stopped cards into Done. Mitigation: single parser + specs. Should the pack guarantee a `Status:` line as a contract? | Agent pack owner | Open |
| A completed result without a `Status:` line is shown as Done (normal for a successful agent). | Reporter | Resolved (kept) |
| Summary max length (120 chars) and the card's name fallback "Agent" when `input.agent` is absent. | Reporter | Assumption |
| `parentLive` uses "active session streaming AND message not completed". A non-active parent is never opened in the transcript, so this is sufficient. | Developer | Assumption |

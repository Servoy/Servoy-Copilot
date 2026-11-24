# Triage Report — SVY-21849

**Verdict:** PROCEED

## Reported problem

Servoy AI: every agent the Orchestrator starts should show as a card with live status, instead of a collapsible tool row.

- The card shows the agent name, "new session" or "continued", a status, a one-line summary and an Open button.
- Statuses are Running (spinner), Done (green) and Stopped, needs a decision (orange). A stopped agent never shows a spinner.
- A card appears the moment the agent starts, statuses update live via `/event`, and reopening a session shows the same cards with their final status.
- Part 5 of 9 of the redesign. It blocks SVY-21850 (agent view, Open button) and SVY-21851 (file reader). SVY-21846 (sidebar) reuses the status.

Jira links: "Blocks" SVY-21845, SVY-21850 and SVY-21851. The ticket has no comments and no subtasks.

Target design (attachment 78953 on SVY-21845). The prototype keeps only three per-agent states: `run`, `returned` and `done`.

- The card shows name, tag ("new session" or "continued"), status label, a note line (the one-line summary) and Open.
- Look table: Running is amber dot plus spinner, Stopped is `#B86B00` with the label "Stopped — needs a decision" and no spinner, Done is green `#1F8A4C`.
- The next step after a stop is a NEW card (Developer, continued) below the stopped one. The stopped card keeps its final state.

## Root-cause assessment

This is a feature, not a defect. Today a subagent spawn is a generic tool row.

- `part-utils.ts:175-219`: `isSubagentTool` matches tool `subagent` or `task`. `subagentSessionId` looks in `state.metadata.sessionID`, then `state.input.sessionID`, then the `<subagent sessionID="...">` marker in the output.
- `message-item.component.html:62-77` and `message-item.component.ts:197-217`: the row is a normal tool row plus a separate "Open X subagent" button. It has no status model, no "new/continued" and no summary.
- `chat-store.service.ts:770-778,908-938`: `session.tool.progress` and `session.tool.success`/`.error` events update the parent's tool part.
  - The child `sessionID` arrives only on the progress metadata.
  - `chat-store.service.ts:256-267`: `GET /message` does NOT return a running tool's metadata. The store works around this with `toolMetaByCallId` and `backfillToolMetadata`.
- `chat-store.service.ts:717-739`: `session.execution.started` sets streaming true. `session.idle`, `session.execution.succeeded`, `session.execution.failed` and `session.error` set it false. Streaming is tracked per session for every session, because the SSE stream is global.
- `chat-store.service.ts:61-65,209-238`: child sessions are already known through `parentID` and the session tree.

The status is a pure derivation over data the store already has. The only missing pieces are a shared derivation util, a card component, and one fetch for the child's final message when a session is reopened.

## Ticket premise check

- "A stopped agent hands back" is not an opencode concept. opencode only knows tool states and session status. A handed-back agent is, to opencode, a child turn that finished normally. The Developer's report ends with `Status: INPUT_REQUIRED` (or `BLOCKED`, `INCOMPLETE`, `FAILED`, `REJECTED`). Evidence: `~/.servoy/opencode/agents/Developer.md:417` defines `Status: COMPLETED | INPUT_REQUIRED | FAILED | REJECTED` and it also uses `BLOCKED` (lines 198, 261, 338) and `INCOMPLETE` (line 409).
  So "stopped" must be derived from the child's report text, a convention of the agent pack, not from an opencode signal. This is the main design risk.
- "Appears at the moment it starts" is feasible, but the child id (and so the Open button) arrives slightly later. It comes on the first `session.tool.progress` event. The card must render without Open until then.
- "Never asks the user" is consistent with the data. Only the Orchestrator asks, through the form card, which already exists.
- The ticket's design says "one-line summary". opencode provides none. Derive it from the child's report (see below) or from `state.title` / `input.description`.

## opencode signal findings (agent status)

Evidence sources:
- The webui models and store, which target V2 (`@opencode/cli` pinned at 2.2.0 in `opencode/package.json`).
- The V1 SDK types installed at `~/.servoy/opencode/node_modules/@opencode-ai/sdk` (1.18.23, `dist/gen/types.gen.d.ts`).
- The agent pack in `~/.servoy/opencode/agents/`.

**Not verified:** I could not read the 2.2.0 server source or its openapi. The npm package `@opencode/cli@2.2.0` is only a launcher stub (3 files), and the raw GitHub path I tried returned 404. V2 specifics below come from the webui code, which was written against the live 2.2.0 server, and from its spec fixtures. V1 specifics come from the SDK types. Anything marked "assumed" must be confirmed against a live 2.2.0 server before implementation.

### Task tool part states

- V1 SDK `types.gen.d.ts:211-262` defines `ToolState = pending | running | completed | error`.
  - `running` has `time.start` and optional `metadata`.
  - `completed` has `output`, `title`, `metadata` and `time.{start,end}`.
  - `error` has `error: string`, optional `metadata` and `time.{start,end}`.
- V2 (webui `opencode.models.ts:77-95`): `status` is `pending | running | streaming | completed | error`. Output arrives as a `content[]` array, flattened to `output`.
- Child session id:
  - It arrives as `metadata.sessionID` on `session.tool.progress`, while the child still runs (`chat-store.service.ts:908-918`).
  - It is also in the completed output marker `<subagent sessionID="ses_x" state="completed">` (`part-utils.ts:210-217`, fixtures in `message-item.component.spec.ts:90,535`). The marker's `state` attribute is a candidate signal, but I only saw the value `completed` in fixtures. Whether other values occur is unverified.
  - `GET /message` omits metadata for a still-running tool (`chat-store.service.ts:256-267`).
- "New session" vs "continued":
  - `input.sessionID` (V2) or `input.task_id` (V1) set means the Orchestrator continued an existing child.
  - `input.sessionID` is documented in `part-utils.ts:180-185` and `Orchestrator.md:322-325`: "The subagent tool takes a `sessionID` (`task_id` on V1) to continue a finished subagent".
  - It is reliable, because it is in the call input, which is persisted. Do NOT use `metadata.sessionID` for this, since it is present in both cases.
  - Absent means new session.

### Aborted or interrupted child

- V1 SDK: `MessageAbortedError` exists (`types.gen.d.ts:80-85`) and is allowed on `AssistantMessage.error` (line 106) and on `EventSessionError` (line 522).
- The typical opencode behaviour (assumed, not verified on 2.2.0) is that aborting the parent turn ends the running tool with `status: error` and a message such as "Tool execution aborted". The child's last assistant message then carries `error.name = MessageAbortedError`.
- Residual risk, which I consider real: if the server dies or the SSE stream drops at the wrong moment, a tool part can stay `running` forever in persisted data. A running part is trustworthy only while its owning session is actually busy.

### Bus events usable live

- Already handled by the store: `session.execution.started` / `succeeded` / `failed`, `session.idle`, `session.error`, `session.tool.input.started` / `called` / `progress` / `success` / `error`, `session.updated`, `session.deleted` (`chat-store.service.ts:708-778`). These are V2 names.
- V1 SDK also has `session.status` with `{type: idle | busy | retry}` (`types.gen.d.ts:396-412`), and `GET /session/status` returns a map of sessionID to status (line 1851-1856). The store does not use either today. Whether 2.2.0 still emits `session.status` is unverified.
- Child sessions emit the same session events with their own `sessionID`. That is why the store can track streaming per session for the whole tree.
- `session.error` has an optional `sessionID` (line 521), so an error event without an id cannot be attributed to a child.

### Reopened session (no live events)

- Persisted parent messages contain the task tool part with `status`, `input`, `output` and `time`. This gives: spawned or not, `new` vs `continued`, and completed or error.
- For `completed`, the part says nothing about whether the child handed back. The `output` is the child's final text (wrapped in the `<subagent …>` envelope). The `Status:` line, if present at the end of that text, is available WITHOUT an extra request. A fallback is `GET /session/:childId/message` and reading the last assistant message.
- For `running` in persisted data, check the child via the store's per-session streaming state, or `GET /session/status`. Not busy means stale, so treat it as `stopped`, not `running`.

### Reliability matrix

| Signal | Reliable? | Notes |
|---|---|---|
| tool part `status` `pending` / `running` | Yes while the parent session is busy | Can go stale if the parent session is not busy |
| tool part `status` `error` | Yes (failed or aborted) | Message text is free-form |
| tool part `status` `completed` | Yes for "child turn ended" | Does NOT mean "work done" (a handed-back agent also completes) |
| `input.sessionID` / `task_id` present | Yes | Only reliable "continued" signal |
| `metadata.sessionID` | Yes for the child id | Only after the first progress event, and not in `GET /message` until completion |
| child session streaming (`execution.started` / `idle`) | Yes live | Not available after a reload; use status fetch |
| `session.error` | Partly | `sessionID` optional, and covers any error |
| child final `Status: INPUT_REQUIRED` etc. | Convention only | The only way to tell "Done" from "Stopped, needs a decision" |
| `<subagent … state="…">` attribute | Unverified | Only `completed` seen in fixtures |

### Proposed single derivation rule

One pure function in a new `services/agent-status.ts`, shared by the card and the sidebar (SVY-21846):

```
deriveAgentStatus(toolPart, childStreaming: boolean | undefined): 'running' | 'done' | 'stopped'

state = toolPart.state.status
if state in (pending, running, streaming):
    return (parentOrChildBusy) ? 'running' : 'stopped'      // stale part never spins
if state == error:
    return 'stopped'                                        // aborted / failed
if state == completed:
    report = last 'Status:' line of output
    return report in (INPUT_REQUIRED, BLOCKED, INCOMPLETE, FAILED, REJECTED) ? 'stopped' : 'done'
```

- "Busy" means the child session is streaming per the store (`session.execution.started`, cleared by `idle` / `succeeded` / `failed` / `error`). If unknown (reloaded session), use `GET /session/status`, and without an answer treat as not busy.
- Spinner only when the result is `running`. A stopped agent can therefore never show a spinner, by construction.
- The label for `stopped` is "Stopped — needs a decision" when `INPUT_REQUIRED`, and could be a different label (for example "Stopped") for `FAILED` / `error`. The design only has the first. I recommend showing the generic orange "Stopped" for the others, and flagging this to the reporter.
- Summary line: the first non-empty text line of the child report after the `Status:` line (or `state.title` / `input.description` while running). Truncate to one line. This is a heuristic; the agent-pack report format is not an opencode contract.

## Approaches considered

1. **No code change.** Rejected. The ticket's acceptance criteria are not met today (no status, no new/continued, no summary), and 21850, 21851 and 21846 depend on this.
2. **New derivation util + card component in `message-item` (recommended).**
   - `services/agent-status.ts` is pure and unit-testable. It parses the tool part (id, new/continued, status, summary).
   - A small `agent-card` component renders it, using the existing `--svy-ok`, `--svy-ok-fg`, `--svy-accent-wait`, `--svy-stop` and `--svy-idle` tokens from 3325ff9 and 2643875.
   - The card emits an `open` output carrying the child id, which 21850 hooks.
   - The store exposes per-session streaming for children (it already tracks it) so the util gets `childStreaming`.
   - `message-item` renders the card for `isSubagentTool(part)` and keeps other tool rows unchanged.
3. **Do the derivation inline in `message-item` only.** Rejected: the user explicitly wants one reusable model, and 21846 needs the same status.
4. **Fetch the child's last message for every card.** Rejected as the default: N extra requests per reload. Use it only as a fallback when the completed `output` has no `Status:` line and the child's final state matters.

## Recommendation

PROCEED with approach 2. Points for the spec:

- Introduce the `AgentStatus` model (`running` / `done` / `stopped`, plus `kind: 'new' | 'continued'`, `summary`, `childSessionId`) in a pure util with specs. Keep the `Open` output on the card but leave the navigation to the existing `openSession` flow, so 21850 can hook into it.
- Replace the tool row for the subagent tool only. The old "Open subagent" link and its tests (`message-item.component.spec.ts:82-130, 512-631`) must be migrated to the card, not deleted silently.
- Render the card as soon as the tool part exists (`pending` / `running`, even without a child id). Open stays disabled or hidden until the id arrives.
- Never show a spinner unless `deriveAgentStatus` returns `running`. Add the stale-running protection through the child's streaming state.
- Reopening: the status must come from the persisted tool part plus its `output`. Fall back to the child's last message only when needed.
- Confirm against a live 2.2.0 server before implementing: (a) what an aborted task tool part looks like (`error` text, or stays `running`), (b) the possible values of the `<subagent state="…">` attribute, (c) whether `session.status` / `GET /session/status` exist in 2.2.0. These are the three unverified points above.
- Raise with the reporter: the "Stopped, needs a decision" label depends on the agent-pack `Status:` convention. A change in that report format silently turns stopped cards into "Done". Keep the parser in one place with a spec.

## Git history findings

- `3325ff9` SVY-21845 introduced the three-area layout, theme toggle and the `--svy-*` palette (`styles.scss`, light and dark). Relevant tokens: `--svy-ok` (#1f8a4c), `--svy-ok-fg`, `--svy-accent-wait` (#b86b00), `--svy-stop` (#9a5b00, white glyph passes 4.5:1), `--svy-idle`, `--svy-danger`.
- `2643875` SVY-21852 (status panel) did not touch message rendering. Its triage and spec in `docs/` follow the same layout as this report.
- The current subagent row and its link come from earlier cases (SVY-21764 per the spec title "always-visible Open subagent link"). Their tests must stay green or be migrated.
- No commit on master (unpushed or otherwise) implements an agent status.

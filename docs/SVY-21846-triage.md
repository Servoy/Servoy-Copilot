# Triage Report — SVY-21846

**Verdict:** PROCEED

## Reported problem

Part 2 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`; target design = `ServoyAI Redesign.html`, attachment 78953 on SVY-21845, downloaded to `D:\TEMP\opencode\svy-redesign.html`). The sidebar (`svy-session-list`) shows a flat, newest-first list with `relativeTime()` ("5m ago"), subagent children as "subagent · 5m ago" behind a twisty, and a bare "No sessions yet". It cannot show which session is busy or waiting for an answer, which state each agent is in, or find an older session.

Acceptance criteria (Jira): group by Today / Yesterday / This week / per date, with last-activity time ("09:42" today, "Nov 16 · 16:20" earlier); title search with "No sessions match."; empty state "No sessions yet" plus one explanatory line; per session running = spinner, waiting for the user = orange "needs your answer", finished = nothing; per agent running = spinner, done = green, handed back = orange "stopped"; all live. Rename / Export / Delete and "+ New session" stay as they are.

The ticket proposes no implementation. Its example ("Developer stopped, so the row reads 11:02 · needs your answer") is the design prototype's shortcut, see "Waiting-for-answer detection" below.

## Root-cause assessment

This is a feature case, not a bug. Current state (all verified in source on master):

- `components/session-list.component.{ts,html}`: inputs `tree: SessionNode[]` and `activeSessionId` only. Rows show `title(session)` and `relativeTime(session)`; children show "subagent · {time}". No search, no grouping, no status. Empty state is `<div class="empty">No sessions yet</div>` (`session-list.component.html:68-70`). The context menu (Rename/Export/Delete) and `create/select/rename/export/remove` outputs must stay.
- `app.component.html:3-11` binds only `store.sessionTree()` and `store.activeSessionId()`.
- `ChatStore.sessionTree` (`chat-store.service.ts:374-399`) is a `computed` of top-level sessions sorted by `time.updated ?? time.created` with children nested. `Session.time.updated` is the "last activity" source. Grouping can be done purely in the component/a pure util on top of this.
- Status reuse points that already exist (SVY-21849 / SVY-21850):
  - `services/agent-status.ts`: `deriveChildStatus(child: ChildSignals | undefined, { reportBody? }) -> { status: 'running'|'done'|'stopped', label, summary, report }` (line 285). This is exactly the per-agent status the sidebar needs; it is already used by `agent-view.ts:108`. No new status logic is needed.
  - `ChatStore.childSignals` (`chat-store.service.ts:153-179`): `Map<childId, ChildSignals>` for every session with a `parentID` in the loaded list (+ ids with a background result). Built from `busySessionIds` (events + `GET /session/active`), live outcomes, interrupt reasons, `Session.outcome` from the list, and `backgroundResults`.
  - `ChatStore.busySessionIds` (line 143): per-session busy set for ANY session (SSE is global), good for "running" on a top-level session.
- Per-agent display name: a child session carries `agent` (SVY-21852 spec 2.4 verified live: child session has `agent` and `model`; `Session` is `[key: string]: unknown`, so it is read as `session['agent']`). Today the child row shows only the title.

### Gaps found (these shape the design)

1. **Pending forms exist only for the ACTIVE session.** `pendingFormSig` is a single `FormInfo | null` (`chat-store.service.ts:218`). `onFormCreated` drops any form whose `sessionID !== activeSessionId` (lines 1555-1566); `onFormSettled` only clears the active one; `openSession` resets it and recovers via `listPendingForms(id)` (lines 568, 594). So the store has no idea which other session is waiting for an answer.
2. **Messages are loaded only for the active session** (`openSession` -> `listMessagePage`). `backgroundResults` (the `<background-result>` report incl. the `Status: INPUT_REQUIRED` line) is therefore only known for opened sessions, plus live `session.inbox.enqueued` / `session.synthetic` events (which are global). For a session that was never opened in this page load, `deriveChildStatus` gets no result body, so an agent that handed back with `INPUT_REQUIRED` would show as green "done" (outcome `succeeded`, no stop `Status:`), not as orange "stopped".
3. `childSignals` only holds children of sessions in the loaded list; busy is `undefined` until `/session/active` was seeded once. `seedActiveSessions()` runs only on `openSession` and after an SSE reconnect (lines 484, 574), not at bootstrap (`refreshSessions`/`bootstrap`). A sidebar that must show running state on first paint needs a seed at bootstrap.

## Waiting-for-answer detection for non-active sessions

### What "waiting for an answer" is in opencode

The Orchestrator asks the user via a Form (the `question` flow). The turn is then blocked on that form; the session stays "busy" while waiting. A form belongs to a session (`FormInfo.sessionID`, `metadata.kind === 'question'`). It is NOT a property of the session object and NOT in `Session.outcome`.

### Data and events available (verified)

| Source | What it gives | Scope |
|---|---|---|
| bus `form.created` `{ form: Info }` (`packages/schema/src/form.ts:170`; webui `onFormCreated`) | the full form incl. `sessionID` | global SSE, all sessions. Currently dropped for non-active sessions |
| bus `form.replied` `{ id, sessionID, answer }`, `form.cancelled` `{ id, sessionID }` (`form.ts:171-172`; webui `onFormSettled`) | settles a form, carries `sessionID` | global SSE |
| `GET /session/:id/form` (`api.listPendingForms(id)`, already used by `openSession`) | pending forms of ONE session | per session (N requests) |
| `GET /api/form` (`form.list`, `packages/protocol/src/groups/form.ts:12`, "List pending forms for a location", `Schema.Array(Form.Info)`) | pending forms of ALL sessions in the location, one request | location-wide. Through the BFF it would be `rest_api/form` (the BFF proxies everything under `rest_api/**` except `fs/**`). **Not yet used by the webui and not verified live** against the pinned 2.4.1 server (source read from the local opencode checkout) |
| `session.status` / `session.execution.*` / `session.inbox.enqueued` / `message.part.updated` | busy/idle, outcomes, background results, tool parts | global SSE, but carry no "waiting" information by themselves. A blocked turn emits no idle event, the session just stays busy |
| permission events | not handled by the store today; permissions are a different concept (tool approval), not the Orchestrator question the ticket means | out of scope |

There is no `question.asked/replied` event in this webui; the V2 name is `form.*`.

### Options

1. **Pending-form map in the store, seeded by `GET rest_api/form` and kept live by `form.created/replied/cancelled`.** `pendingFormSessions: Signal<ReadonlySet<string>>` (sessionIDs with at least one pending form). Seed at bootstrap and after SSE reconnect (same moments as `seedActiveSessions`, which already has the "events that arrived during the seed win" bookkeeping to copy). `onFormCreated` adds the session id for ANY session (the active-session `pendingFormSig` behaviour stays unchanged), `onFormSettled` removes it (the settled payload carries `sessionID`; when only `id` is present, track `formId -> sessionID`). Pros: exact, cheap (1 request + events), works for every session without opening it, no heuristics, survives reload. Cons: relies on `GET /api/form` working through the BFF (verify live; fallback below); needs the id->session map for settles without `sessionID`.
2. **Same map, seeded per session with `listPendingForms(id)`** for the top-level sessions that are busy (only busy sessions can be blocked on a form). Pros: only uses an endpoint that is already proven in the app. Cons: N requests on load (bounded by the number of busy sessions, usually 0-2); a bit more bookkeeping. Good fallback if option 1's route is not reachable.
3. **Heuristic from the design prototype: a session "needs your answer" when one of its agents is stopped with `INPUT_REQUIRED`** (the Jira example). Pros: no new data. Cons: wrong. A stopped child only means the agent handed back to the Orchestrator; the user is asked only if the Orchestrator then raises a form (or may simply continue another agent). It also depends on report bodies that are not loaded for unopened sessions (gap 2). Rejected as the source; the prototype only simulated state.
4. **Only the active session** (`pendingForm()`): rejected, it ignores the requirement ("not only the active session").
5. No code change: not applicable.

### Recommendation

Option 1, with option 2 as the documented fallback inside the same store method (if the location-wide request fails, query `listPendingForms` for the busy top-level sessions only). Confirm `GET rest_api/form` live against the running pinned server during implementation (open question 1).

Resulting session status (pure util, e.g. `services/session-status.ts`, unit-testable; priority first match wins):

1. `needs-answer` when the session id is in `pendingFormSessions` (a blocked turn is also busy, so this must beat running);
2. `running` when `busySessionIds.has(id)`;
3. otherwise `idle` (nothing shown).

A top-level session whose own turn is idle but whose children are still busy is still "running" in the ticket's sense (the Orchestrator waits on its agents; compare SVY-21852 3.4: the parent stays streaming). Spec phase should decide whether to additionally mark a parent as running when only a child is busy (recommended: yes, `running` if the id OR any of its children is busy, so a missed parent idle/busy flip cannot hide activity).

## How children / subagents get their agent status

- Tree: `ChatStore.sessionTree` already nests children (`Session.parentID`) under each top-level session. The sidebar keeps rendering these children behind the twisty.
- Status per child: `deriveChildStatus(childSignals.get(child.id), { reportBody })` from `services/agent-status.ts` (no second status logic). Mapping to the design: `running` -> spinner, `done` -> green, `stopped` -> orange "stopped" (label from `labelOf`; the sidebar uses the short word "stopped" for every stopped state, like the design, detail stays in the agent view).
- Wiring: pass `childSignals` (and `pendingFormSessions`, `busySessionIds`) into `svy-session-list` as inputs (presentational component, no store injection, same pattern as `message-list`/`message-item` in SVY-21849). The sidebar re-renders live because all inputs are signals.
- Name: `child['agent']` (string) with fallback to the title; `Agent` as last resort.
- Known gap (needs a decision, see recommendation 2): `reportBody` is only available for sessions whose messages were loaded (`backgroundResults`) or whose `session.inbox.enqueued` was seen live in this page load. For all other sessions a child that handed back with `Status: INPUT_REQUIRED/BLOCKED/...` would show green "done". Options: (a) accept: live = exact, cold-loaded sessions show done unless outcome says failed/interrupted (cheapest; the agent view still shows the truth when opened); (b) when a top-level session row is first expanded, fetch its newest message page once and run `recordSyntheticResults` (1 request per expanded session, bounded, lazy); (c) fetch at bootstrap for every session with children (N requests, rejected). Recommended: (b), falling back to (a) if the spec phase wants to keep SVY-21846 smaller.
- Stale-busy guard stays as in SVY-21849: `busy` only from events + `/session/active`, never from `time.busy > time.idle`. Seed `/session/active` at bootstrap too (gap 3), otherwise a reloaded page shows no spinners until a session is opened.

## Ticket premise check

The ticket proposes no solution, only behaviour. Two points to challenge:

- The Jira example derives "needs your answer" from a stopped agent. That follows the prototype, not opencode semantics (see option 3). The criterion text itself ("a session waiting for the user's answer") matches the form-based approach, which the user context also asks for.
- "stopped when it handed back to the Orchestrator" is the `Status:` report convention (SVY-21849 section 2.5): not an opencode concept, so it is only as reliable as the report text and only known where the report body is loaded (gap 2).

Smaller observations from the design (`svy-redesign.html`): empty state text is "No sessions yet" + "Every conversation you start lands here, with the agents that worked on it."; no-hit text is "No sessions match." (13 px, `--muted`, padding 16px 10px); group headers are "Today", "Yesterday", "This week", then per date; the design prototype appends " · running" / " · needs your answer" / " · done" to the meta line and tints the dot (`#E39A1C` running, `#B86B00` waiting, `--idle` done). The ticket text says finished = nothing, so no "done" word on the top-level row. The prototype's hex colours must map to the existing `--svy-*` tokens (`--svy-accent`/`--svy-accent-strong`, `--svy-accent-wait`/`--svy-acc-fg` for orange text, `--svy-ok`/`--svy-ok-fg`, `--svy-idle`); no new hard-coded colours.

## Approaches considered

1. **Presentational sidebar + pure utils + small store additions (recommended).**
   - New pure util `services/session-groups.ts`: `groupSessions(nodes, now)` -> `[{ label, items }]` (Today, Yesterday, This week, then dates), `formatActivity(ts, now)` ("09:42" same day, "Nov 16 · 16:20" otherwise), search filter on title. Time/locale injected for tests.
   - New pure util `services/session-status.ts`: top-level status (needs-answer / running / idle) on top of `deriveChildStatus`.
   - Store: `pendingFormSessions` (option 1, fallback option 2), bootstrap `seedActiveSessions`, and (optional) lazy report fetch on expand.
   - `svy-session-list`: new inputs, search input, grouped rendering, spinner/orange/green indicators, new empty state; menu and `create` untouched.
   - Pros: reuses the status model, testable in isolation, no Java change. Cons: touches the store (form tracking) and needs live verification of `GET rest_api/form`.
2. **Heuristic-only sidebar (stopped agent = needs your answer, no store change).** Pros: smallest. Cons: wrong semantics and wrong for unopened sessions, rejected.
3. **Compute everything inside `session-list.component` with store injection.** Pros: fewer inputs. Cons: breaks the presentational pattern used by the other redesign components and their spec style.
4. **No code change needed.** Not applicable: none of the criteria is met today.

## Recommendation

PROCEED with approach 1.

Scope for the spec phase (all under `bundles/com.servoy.eclipse.opencode/webui/src/app/`):

- `services/session-groups.ts` (+ spec): grouping, activity format, title filter ("No sessions match."), including edge cases (midnight boundary, week boundary, sessions without `time`, sort stable newest-first, group order). "This week" boundary definition (rolling 7 days vs calendar week) is open question 3.
- `services/session-status.ts` (+ spec): top-level status, child status via `deriveChildStatus`, short labels.
- `chat-store.service.ts` (+ spec): `pendingFormSessions` seeded via `GET rest_api/form` (fallback `listPendingForms` for busy top-level sessions), live `form.created/replied/cancelled` for every session without changing `pendingForm`/`pendingFormSig` behaviour, seed `/session/active` at bootstrap, optional lazy `recordSyntheticResults` for a session on first expand. New `OpencodeApiService.listAllPendingForms()` (+ spec), failure -> empty.
- `components/session-list.component.{ts,html,scss}` (+ new spec; none exists today): inputs `childSignals`, `busySessionIds`, `pendingFormSessions`; search field (accessible label, clear), groups with headers, status indicators with text (not colour only, `aria-hidden` spinner, `prefers-reduced-motion`), new empty state, agent names/status in child rows. Keep the menu, rename flow, `relativeTime` replacement and the auto-expand effect.
- `app.component.html` (+ spec mock): bind the new inputs. `AGENTS.md` and `.opencode/sdd/project-context.md` are not touched (parallel session).

Risks to keep in the spec: orange text must use `--svy-acc-fg`, not `--svy-accent`; the search term lives only in the component; expanded state and rename state must survive live updates; new signals must not be written during rendering (NG0600).

## Git history findings

- `3325ff9` SVY-21845: three-area layout, `--svy-*` tokens (palette used here).
- `2643875` SVY-21852: status panel; defines the "viewed session" semantics and notes that SVY-21853 will need the session object.
- SVY-21849 spec/impl: `agent-status.ts`, `busySessionIds`, `childSignals`; its out-of-scope list explicitly names SVY-21846 as the consumer of `agent-status.ts`, `busySessionIds` and `childSignals`. SVY-21850: `deriveChildStatus` extracted for the agent view.
- `session-list.component.*` predates the redesign (title/relative time/menu; SVY-21363 empty-session history spec); no spec file exists for it today.
- All preceding cases (SVY-21845, 21852, 21849, 21850, 21848, 21851, 21847) are on master per the task context. No dependency or Angular version change is relevant.

## Open questions (for the spec phase, none blocks PROCEED)

1. Does `GET rest_api/form` (location-wide `form.list`) work through the BFF on the pinned 2.4.1 server and return forms of all sessions? Verify live; otherwise use the per-session fallback.
2. How a parent row behaves when only a child is busy (recommended: running).
3. "This week": rolling last 7 days (excluding Today/Yesterday) or the calendar week? Recommended: rolling, because the design groups "Nov 11" under "This week" while "Nov 16" is under "Yesterday" in a prototype fixed at Nov 17.
4. Cold-loaded sessions and `INPUT_REQUIRED`: accept (a) or fetch lazily on expand (b), see above.

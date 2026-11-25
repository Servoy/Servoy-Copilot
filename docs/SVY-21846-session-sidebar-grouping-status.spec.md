# Spec: SVY-21846 — Sidebar: sessions grouped by day, title search, empty state, live session and agent status

Part 2 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). Target design: `ServoyAI Redesign.html`, attachment on SVY-21845 (local copy `D:\TEMP\opencode\svy-redesign.html`). Triage: `docs/SVY-21846-triage.md` (authoritative). Builds on the specs of SVY-21845 (tokens, layout), SVY-21849/21850 (`agent-status.ts`, `busySessionIds`, `childSignals`) and SVY-21852.

All paths are relative to `bundles/com.servoy.eclipse.opencode/webui/src/app/` unless stated otherwise.

## 1. Goal

The sidebar (`svy-session-list`) becomes the redesigned session overview:

- sessions grouped by day (Today / Yesterday / This week / per date), each row with its last-activity time;
- a title search with "No sessions match.";
- the empty state "No sessions yet" plus one explanatory line;
- per session: spinner while running, orange "needs your answer" while it waits for the user, nothing when finished;
- per agent (subagent child row): spinner while running, green "done", orange "stopped";
- all of this live (SSE), for every session, not only the open one.

Rename / Export / Delete (context menu, inline rename) and "+ New session" stay unchanged.

## 2. Background

### 2.1 Current state (verified in source)

- `components/session-list.component.{ts,html,scss}`: inputs `tree: SessionNode[]` and `activeSessionId`; flat newest-first list, `relativeTime()` ("5m ago"), children as "subagent · 5m ago" behind a twisty (`expandedIds` signal, auto-expand effect for an active child), bare `<div class="empty">No sessions yet</div>`. No spec file exists for the component.
- `app.component.html:3-11` binds only `store.sessionTree()` and `store.activeSessionId()`.
- `ChatStore.sessionTree` (`services/chat-store.service.ts`) = top-level sessions sorted by `time.updated ?? time.created` descending, children nested (`SessionNode = { session, children }`).
- Reusable status model: `services/agent-status.ts` — `ChildSignals`, `deriveChildStatus(child, { reportBody? }) -> { status: 'running'|'done'|'stopped', label, summary, report }`. `ChatStore.childSignals` (Map childId -> `ChildSignals`, for every loaded session with `parentID`), `ChatStore.busySessionIds` (events + `GET /session/active`). **No second status logic is introduced.**
- `seedActiveSessions()` runs only in `openSession` and after an SSE reconnect, not at bootstrap. `activeSeeded` stays false until the first seed, so `childSignals[x].busy` is `undefined` before that.
- Pending forms are tracked only for the active session (`pendingFormSig`; `onFormCreated` ignores other sessions, `onFormSettled` clears only the matching pending form; `openSession` recovers via `api.listPendingForms(id)`).
- Reports (`<background-result>` incl. `Status: INPUT_REQUIRED`) are only known for opened sessions (`recordSyntheticResults` on the loaded page) and for live `session.inbox.enqueued` / `session.synthetic` events.

### 2.2 What "needs your answer" means

The Orchestrator asks the user through a **form** (`form.created`, `FormInfo.sessionID`). The turn stays busy while blocked. A stopped agent only means "handed back to the Orchestrator", not "the user is asked". The Jira example ("Developer stopped, so the row reads 11:02 · needs your answer") follows the design prototype's shortcut; this spec uses the real signal: **a pending form for the session**. Permission requests are a different concept and out of scope.

### 2.3 Decisions taken (from the user, binding)

1. "This week" = rolling last 7 days, **excluding** Today and Yesterday.
2. A parent row is `running` when the session itself **or any of its children** is busy.
3. Cold-loaded sessions: when a top-level row is expanded for the first time, lazily fetch its newest message page and run `recordSyntheticResults`, so an `INPUT_REQUIRED` hand-back shows as orange "stopped".
4. `GET rest_api/form` (location-wide) must be **verified live** during implementation; on failure/empty result the store falls back to per-session `listPendingForms` automatically. Both paths are specified and tested.
5. Status priority for a session: `needs-answer` > `running` > `idle`.

### 2.4 Design tokens

Only existing `--svy-*` tokens (`src/styles.scss`); no new hard-coded colours:

| Use | Token |
|---|---|
| spinner | `--svy-accent-strong` |
| "needs your answer" / "stopped" text | `--svy-acc-fg` (NOT `--svy-accent`: contrast) |
| "stopped" dot | `--svy-accent-wait` |
| agent "done" dot / text | `--svy-ok` / `--svy-ok-fg` |
| group header, meta, search placeholder, "No sessions match." | `--svy-muted` |
| search field border / surface | `--svy-line`, `--svy-surface`, focus ring `--svy-focus-ring` |

## 3. Design

### 3.1 Pure util `services/session-groups.ts` (new, + spec)

No Angular, no `Date.now()` inside; `now` is always a parameter. Local time zone of the browser.

```ts
export interface SessionGroup { readonly key: string; readonly label: string; readonly items: SessionNode[]; }

export function sessionActivity(s: Session): number | null;            // time.updated ?? time.created ?? null
export function formatActivity(ts: number | null, now: number): string; // '' when null
export function groupLabel(ts: number | null, now: number): { key: string; label: string };
export function filterNodes(nodes: SessionNode[], term: string): SessionNode[];
export function groupSessions(nodes: SessionNode[], now: number): SessionGroup[];
```

Rules:

- `daysAgo` = difference in **local calendar days** between start-of-day(now) and start-of-day(ts) (DST safe: compare local y/m/d via `Date.UTC(y, m, d)` difference, not 24h arithmetic).
- Groups: `daysAgo <= 0` (also future timestamps from clock skew) -> `Today`; `1` -> `Yesterday`; `2..6` -> `This week`; `>= 7` -> one group per local date, label `Nov 10` (English short month, no leading zero; other year than `now`: `Nov 10, 2025`), key = `YYYY-MM-DD`. Sessions without any timestamp -> last group `Earlier`.
- Group order: Today, Yesterday, This week, then dates newest first, then `Earlier`. Empty groups are not returned. Inside a group items are sorted by activity descending, stable (ties keep the input order).
- `formatActivity`: same local day as `now` -> `HH:mm` 24 h zero-padded (`09:42`); otherwise `Nov 16 · 16:20` (middle dot with spaces; other year: `Nov 16, 2025 · 16:20`). No relative text ("5m ago" is removed).
- `filterNodes(nodes, term)`: `term` trimmed, case-insensitive substring on the **displayed** title (`'Untitled session'` for empty titles, same rule as `title()` in the component). A node is kept when its own title matches OR any child title matches. Empty term returns `nodes` unchanged (same reference). The children list of a kept node is not reduced (the user sees the whole agent branch).
- `groupSessions` does not filter; the component calls `filterNodes` first, then `groupSessions`.

### 3.2 Pure util `services/session-status.ts` (new, + spec)

Thin layer on top of `deriveChildStatus`; contains no status rules of its own beyond the priority.

```ts
export type SessionRunStatus = 'needs-answer' | 'running' | 'idle';
export function sessionStatus(node: SessionNode, busy: ReadonlySet<string>, pendingForms: ReadonlySet<string>): SessionRunStatus;
export type AgentRowStatus = 'running' | 'done' | 'stopped';
export function agentRowStatus(signals: ChildSignals | undefined): AgentRowStatus | null;
export function agentName(child: Session): string;
```

- `sessionStatus`: `needs-answer` if the node's id **or any child id** is in `pendingForms` (a subagent blocked on a form also waits for the user); else `running` if the id or any child id is in `busy`; else `idle`.
- `agentRowStatus`: returns `null` (no marker) when `signals` is `undefined`, or when it carries no information yet (`busy === undefined && outcome === undefined && result === undefined && interruptedReason === undefined`, i.e. before the first `/session/active` seed) — this prevents a flash of orange "stopped" on first paint. Otherwise `deriveChildStatus(signals).status` (the `result.body` is already inside the signals; `reportBody` is not needed).
- `agentName`: `child['agent']` if a non-empty string, else the child's trimmed title, else `'Agent'`.

### 3.3 Store additions (`services/chat-store.service.ts`, `services/opencode-api.service.ts`)

**API.** `OpencodeApiService.listAllPendingForms(): Observable<FormInfo[]>` — `GET ${base}/form`, accepts both `{ data: FormInfo[] }` and a bare array (`Array.isArray(r) ? r : r?.data ?? []`), **any error -> `of([])`** (never throws). Unlike `listPendingForms`, no error propagation.

**State.**

- `private readonly pendingForms = signal<ReadonlyMap<string, string>>(new Map())` — formId -> sessionID, for ALL sessions.
- `readonly pendingFormSessions = computed<ReadonlySet<string>>(...)` — distinct sessionIDs of the map; a new Set instance only when the content changed (equal function on the computed so the sidebar does not re-render needlessly).
- `pendingFormSig` / `pendingForm` / `clearForm` / `replyToForm` behaviour is **unchanged**.

**Live updates (every session).**

- `onFormCreated`: first, for any form with `id` and `sessionID`, add `id -> sessionID` to `pendingForms` (and mark it "created during seed" for 3.3 seeding); then the existing active-session logic runs unchanged (still returns early for other sessions, after the map update).
- `onFormSettled` (`form.replied` / `form.cancelled`): remove the form id from `pendingForms`. If the payload only has `id`, the map gives the session; if it only has `sessionID` and no known id, remove all entries of that session. Then the existing `clearForm(id)` runs unchanged.
- Session goes idle (`session.status` idle / the existing idle handler), `pruneSessionState(id)` (session deleted) -> drop all entries of that session. Rationale: a pending form implies a busy turn; this removes a stale "needs your answer" when a cancel event was missed. `pendingFormSig` is not touched by this.

**Seeding** (`seedSidebarState()`, new private method = `seedActiveSessions()` then `seedPendingForms()`; called from `bootstrap()` after the session list is loaded, and from the SSE `reconnected()` handler instead of the bare `seedActiveSessions()`; `openSession` keeps its own `seedActiveSessions()` call unchanged). `seedActiveSessions` must be sequenceable: let it return an `Observable<void>` (or accept a completion callback) that completes after the `activeSessionIds`/`activeSeeded` update; the existing "events that arrived during the seed win" bookkeeping stays.

`seedPendingForms()`:

1. Register a `createdMeanwhile: Map` and `settledMeanwhile: Set` tracker (same pattern as `pendingSeeds`): events arriving while the request is in flight win over the snapshot.
2. **Primary path:** `api.listAllPendingForms()`. If it returns >= 1 form, the snapshot is these forms.
3. **Fallback path:** if the primary returns `[]` (route failure and a genuinely empty location are indistinguishable by design: errors map to `[]`), query `api.listPendingForms(id)` for each **busy top-level** session (`busySessionIds` after the active seed, `!parentID`; also busy children are NOT queried: forms are raised by the Orchestrator turn). Each per-session failure is ignored (`dbg('error', ...)`). Bounded by the number of busy sessions (normally 0–2); an empty location with no busy sessions makes zero extra requests.
4. Result: `pendingForms` = snapshot entries minus `settledMeanwhile`, plus `createdMeanwhile` entries. Replaces the map completely (reconnect clears stale entries).

**Lazy report fetch (decision 3).**

- `loadSessionReports(sessionId: string): void` (public). One request per top-level session per page load: guarded by `private readonly reportsRequested = new Set<string>()`.
- No-op when `sessionId === activeSessionId()` (its page is loaded by `openSession`; mark as requested) or when already requested.
- Otherwise `api.listMessagePage(sessionId)`; on success `recordSyntheticResults(page.messages ?? [], true)` (the `older = true` mode: fills only children without an existing entry, newest message wins within the page, so live-recorded reports are never overwritten). On error: remove from `reportsRequested` (retry on next expand) and `dbg('error', ...)`; no `error` signal write (the sidebar must not raise a global error banner). It must not touch `messages`, `olderCursor`, `activeSessionId` or any per-active-session state.
- Known limitation (documented, not solved): only the newest page is scanned.

### 3.4 Component `components/session-list.component.{ts,html,scss}`

Stays presentational (no store injection).

**Inputs (new):** `childSignals: ReadonlyMap<string, ChildSignals>` (default empty), `busySessionIds: ReadonlySet<string>` (default empty), `pendingFormSessions: ReadonlySet<string>` (default empty). Existing `tree`, `activeSessionId`.
**Output (new):** `expand = output<string>()` — emitted with the top-level session id when the user expands a row via the twisty (`toggleExpand`, only on collapsed -> expanded). The auto-expand effect for an active child does not emit (the active session is already loaded).
Existing outputs `select/create/rename/export/remove` unchanged.

**State / computeds (no signal writes in render, NG0600):**

- `searchTerm = signal('')` (lives only in the component), `clock = signal(Date.now())` updated by a `setInterval` every 60 s created in the constructor and cleared via `DestroyRef.onDestroy` (so "Today"/"Yesterday" labels roll over at midnight without a reload; zoneless, signal write from the timer is fine).
- `filtered = computed(() => filterNodes(tree(), searchTerm()))`, `groups = computed(() => groupSessions(filtered(), clock()))`.
- `isEmpty = computed(() => tree().length === 0)` (kept); `noMatches = computed(() => !isEmpty() && filtered().length === 0)`.
- Effective expansion: `isExpanded(id)` = `expandedIds().has(id)` OR (search active AND the parent only matched through a child title). That second part is derived, never written to `expandedIds`, so clearing the search returns to the user's own expand state.
- Per-row helpers: `statusOf(node)` -> `sessionStatus(...)`, `activityOf(session)` -> `formatActivity(sessionActivity(s), clock())`, `agentStatusOf(child)` -> `agentRowStatus(childSignals().get(child.id))`, `agentLabelOf(child)` -> `agentName(child)`. `relativeTime()` is removed (no other user; check with grep before deleting).

**Template.**

```
<div class="sessions">
  <button class="new-session">+ New session</button>            (unchanged)
  @if (!isEmpty()) {
    <div class="search" role="search">
      <input type="search" class="search-input" aria-label="Search sessions" placeholder="Search sessions"
             [ngModel]="searchTerm()" (ngModelChange)="searchTerm.set($event)" (keydown.escape)="clearSearch()" />
      @if (searchTerm()) { <button type="button" class="search-clear" aria-label="Clear search" (click)="clearSearch()">×</button> }
    </div>
  }
  <div class="list">
    @for (group of groups(); track group.key) {
      <section class="group" [attr.aria-labelledby]="'grp-' + group.key">
        <h3 class="group-title" [id]="'grp-' + group.key">{{ group.label }}</h3>
        @for (node of group.items; track node.session.id) { ...row... }
      </section>
    }
    @if (isEmpty()) {
      <div class="empty"><div class="empty-title">No sessions yet</div>
        <div class="empty-hint">Every conversation you start lands here, with the agents that worked on it.</div></div>
    } @else if (noMatches()) {
      <div class="no-match" role="status">No sessions match.</div>
    }
  </div>
</div>
```

- Search field is placed per the prototype (below "+ New session", above the list). `clearSearch()` sets the term to `''` and returns focus to the input.
- `@for` keeps `track node.session.id` for rows and `track child.id` for agents, so DOM nodes (and the inline rename `<input>`, focus, open menu) survive live status/time updates; the rename flow, context menu and `onSelect` guard are untouched. The filter does not clear `renamingSessionId`; a session being renamed that stops matching is still shown until the rename ends (`filterNodes` result is unioned with the renaming id in the component: `filtered` also keeps the node whose id equals `renamingSessionId()`).
- Top-level row meta line: `{{ activityOf(session) }}` followed, only when relevant, by ` · needs your answer` (orange text, `--svy-acc-fg`). Running: a small spinner (`<span class="spinner" aria-hidden="true">`) at the start of the meta line plus a visually hidden `running` text (`.sr-only`), so the state is not colour/animation-only. Idle: nothing (no "done"). Rendering order for a row with status: `[spinner] 09:42` / `09:42 · needs your answer`.
- Child (agent) row: primary line `agentLabelOf(child)` (`[title]` = child title), secondary line `agent status`: `running` = spinner + visible text "running"; `done` = green dot + "done" (`--svy-ok`, text `--svy-ok-fg`); `stopped` = orange dot + "stopped" (`--svy-acc-fg`); `null` = no marker. Every state has visible text (not colour only).
- The existing `Session actions` button, `contextmenu` handler, and `[class.active]` stay on rows and child rows as today.

**Styles (`session-list.component.scss`).** Group header: small uppercase/semibold muted label (values from the prototype's `.group` rule, `--svy-muted`); `.no-match` and `.empty` use `padding: 16px 10px`, 13 px, `--svy-muted` (prototype); `.spinner` is a 10–12 px ring (`border` in `--svy-line`, top segment `--svy-accent-strong`) with a rotate animation; **`@media (prefers-reduced-motion: reduce)` disables the rotation** (static ring, the hidden/visible "running" text still conveys state). If `agent-view`/`status-panel` styles already define a spinner, reuse that class/mixin instead of duplicating. Search input: `--svy-surface` background, `--svy-line` border, focus ring `--svy-focus-ring` + visible outline (no `outline: none` without replacement). `.sr-only` visually-hidden utility (reuse an existing one if the app has it).

### 3.5 Wiring `app.component.html` (+ spec mock)

```html
<svy-session-list
  [tree]="store.sessionTree()"
  [activeSessionId]="store.activeSessionId()"
  [childSignals]="store.childSignals()"
  [busySessionIds]="store.busySessionIds()"
  [pendingFormSessions]="store.pendingFormSessions()"
  (select)="onSelectSession($event)"
  (expand)="store.loadSessionReports($event)"
  ... (create/rename/export/remove unchanged)
></svy-session-list>
```

The `app.component.spec.ts` store mock gets the three new signals/`loadSessionReports`.

### 3.6 Live verification of `GET rest_api/form` (decision 4)

During implementation, against a running Servoy Developer with the pinned 2.4.1 server (`http://127.0.0.1:8183/servoy_ai/rest_api/form`, port as shown for that instance): trigger a question in one session, switch to another, call the route (e.g. via the browser panel/`fetch`), and record in the PR/commit description (a) status code, (b) body shape (`{data:[...]}` or array), (c) whether forms of **other** sessions are included. Outcome handling:

- Works and returns all sessions' forms -> primary path is the live path; the fallback stays as code + tested safety net.
- 404/403/500 or only the current session's forms -> the fallback path carries the feature; keep `listAllPendingForms` (still harmless) and note the finding in the PR. No further decision is needed from the user.

## 4. Implementation plan

1. `services/session-groups.ts` + `session-groups.spec.ts` (3.1).
2. `services/session-status.ts` + `session-status.spec.ts` (3.2).
3. `services/opencode-api.service.ts`: `listAllPendingForms()` (+ spec case in the api spec).
4. `services/chat-store.service.ts`: `pendingForms` map + `pendingFormSessions`; `onFormCreated`/`onFormSettled` map updates; idle/prune cleanup; make `seedActiveSessions` sequenceable; `seedPendingForms` (primary + fallback + seed race bookkeeping); `seedSidebarState()` called from `bootstrap()` and `reconnected()`; `loadSessionReports()`. Extend `chat-store.service.spec.ts`.
5. `components/session-list.component.{ts,html,scss}` (3.4) + new `session-list.component.spec.ts`.
6. `app.component.html` + spec mock (3.5).
7. Live check of `rest_api/form` (3.6) and a manual pass in the running app (dev watch build per AGENTS.md "Quick debugging"), including dark theme and `prefers-reduced-motion`.
8. `npx ng test --no-watch` (whole webui suite), `ng build`, lint if configured. Before commit: production `npm run build`. Do not touch `AGENTS.md`, `.opencode/sdd/project-context.md`, `opencode.json` (parallel work).

## 5. Test scenarios (Vitest)

Use `vi.useFakeTimers()` + `vi.setSystemTime(new Date(2026, 10, 17, 12, 0))` (local time) for date logic; helper `node(id, title, updated, children = [])`.

### `session-groups.spec.ts`

1. `formatActivity`: 09:42 today -> `'09:42'`; 00:05 today -> `'00:05'`; yesterday 16:20 -> `'Nov 16 · 16:20'`; other year -> `'Nov 16, 2025 · 16:20'`; `null` -> `''`.
2. Boundary: 23:59 yesterday vs 00:00 today at `now` = 00:00:30 -> Yesterday / Today (midnight boundary).
3. Group labels: today, yesterday, 2 days ago (`This week`), 6 days ago (`This week`), 7 days ago (`Nov 10`), different year (`Nov 10, 2025`).
4. Future timestamp (now + 5 min) -> `Today`.
5. Group order: mixed input in random order -> `Today, Yesterday, This week, Nov 12, Nov 10, Earlier`; empty groups omitted; two sessions on the same old date share one group.
6. Items sorted newest-first inside a group; equal timestamps keep input order.
7. Session without `time` -> `Earlier` group, `sessionActivity` `null`; `time.created` used when `updated` missing.
8. DST day (e.g. `now` on a spring-forward Sunday): a session at 23:30 the day before is `Yesterday`.
9. `filterNodes`: case-insensitive, trimmed term; empty/whitespace term returns the same array reference; matches `Untitled session` for empty titles; parent kept when only a child title matches (children untouched); no match -> `[]`.

### `session-status.spec.ts`

1. Priority: pending + busy -> `needs-answer`; busy only -> `running`; none -> `idle`.
2. Child busy only -> parent `running`; child in `pendingForms` -> parent `needs-answer`.
3. `agentRowStatus`: `undefined` -> `null`; all-undefined signals (unseeded) -> `null`; `{busy:true}` -> `running`; `{busy:false, outcome:'succeeded'}` -> `done`; `{busy:false, outcome:'succeeded', result:{state:'completed', body:'Status: INPUT_REQUIRED ...'}}` -> `stopped`; `{outcome:'failed'}` / `{outcome:'interrupted'}` -> `stopped`. (Assert equality with `deriveChildStatus(...).status` so no divergence is possible.)
4. `agentName`: uses `agent`; falls back to title; then `'Agent'`.

### `opencode-api.service.spec.ts`

1. `listAllPendingForms` unwraps `{data}` and a bare array; HTTP 404/500 -> `[]` (no error).

### `chat-store.service.spec.ts`

1. `form.created` for a **non-active** session adds it to `pendingFormSessions`; `pendingForm()` stays `null`.
2. `form.created` for the active session -> in the set AND `pendingForm()` set (unchanged behaviour).
3. `form.replied` / `form.cancelled` (with and without `sessionID` in the payload) remove it; two forms for one session: the session stays in the set until both are settled.
4. Session idle event and session deletion remove its entries.
5. Bootstrap seeding, primary path: `listAllPendingForms` returns forms of two sessions -> both in the set; `listPendingForms` is NOT called.
6. Fallback path: `listAllPendingForms` returns `[]` (and separately: errors) while sessions `a` (busy, top-level), `b` (idle), child `c` (busy) exist -> `listPendingForms` called only for `a`; a per-session failure does not break the others; no busy sessions -> no per-session calls.
7. Seed race: a `form.created` event arriving while the snapshot request is pending survives the snapshot; a `form.replied` arriving meanwhile removes a form the snapshot still lists.
8. SSE `reconnected()` re-seeds (stale entries disappear).
9. Bootstrap now seeds `/session/active` (`getActiveSessions` called once at startup before any `openSession`; `busySessionIds` reflects it).
10. `loadSessionReports`: (a) cold session with a synthetic `<background-result>` carrying `Status: INPUT_REQUIRED` -> `childSignals` for the child has that result -> `deriveChildStatus` gives `stopped`; (b) second call for the same id makes no request; (c) active session id -> no request; (d) a live-recorded result is not overwritten by the fetched page; (e) failure -> no `error` signal, a later call retries; (f) `messages()`/`activeSessionId()` untouched.

### `session-list.component.spec.ts` (new)

1. Groups render in order with headers `Today`, `Yesterday`, `This week`, `Nov 10`; row meta shows `09:42` (today) and `Nov 16 · 16:20` (earlier); no "ago" text anywhere.
2. Search: typing `fix` shows only matching sessions and hides empty groups; case-insensitive; `No sessions match.` (role `status`) when nothing matches while the tree is non-empty; clear button (`aria-label="Clear search"`) and Escape reset the term and focus the input; the clear button is absent with an empty term; the search field is absent when the tree is empty.
3. Empty state: empty tree -> `No sessions yet` and `Every conversation you start lands here, with the agents that worked on it.`; no "No sessions match.".
4. Session status: busy -> `.spinner` present with `aria-hidden="true"` and a `.sr-only` "running"; pending form -> text `needs your answer`, no spinner; both -> only `needs your answer`; neither -> neither marker, no "done" text.
5. Agent rows (after expanding): running -> spinner + `running`; done -> `done`; stopped -> `stopped`; unseeded signals -> no marker; row shows the agent name (fallback title / `Agent`).
6. Live: change `busySessionIds` / `pendingFormSessions` / `childSignals` inputs via `fixture.componentRef.setInput` + `detectChanges` -> markers appear/disappear without re-creating the row element (same DOM node identity) and `expectation: expect(() => fixture.detectChanges()).not.toThrow()` (NG0600 guard).
7. State survives live updates: with a twisty expanded, an active search term and an inline rename input open, replacing `tree` with a new array (new activity times, new title on another session) keeps the expansion, the search term and the rename input (focus + typed text).
8. Search matching only a child title shows the parent expanded; clearing the search restores the user's own expand state.
9. `expand` output emits the parent id on expanding, not on collapsing, not from the auto-expand effect.
10. Unchanged behaviour: `select`, `create`, `rename` (Enter/Escape/blur), `export`, `remove` still emit; context menu opens on right-click and on `⋯`.
11. Midnight rollover: advancing fake timers past midnight (`vi.advanceTimersByTime`) moves a session from `Today` to `Yesterday`; the interval is cleared on destroy (no pending timers after `fixture.destroy()`).
12. a11y smoke: input has an accessible name; group `section` is labelled by its heading; every status marker has text content.

### `app.component.spec.ts`

1. Mock store exposes the new members; template binds without error; `(expand)` calls `store.loadSessionReports` with the id.

## 6. Acceptance criteria

- [ ] Sessions are grouped Today / Yesterday / This week (rolling, 2–6 days ago) / per date, newest first inside each group; empty groups are not shown.
- [ ] Today's rows show `HH:mm` (e.g. `09:42`); earlier rows show `Nov 16 · 16:20`; the old "5m ago" text is gone.
- [ ] A title search filters the list live (case-insensitive), has an accessible label and a clear control (button and Escape); `No sessions match.` shows when nothing matches.
- [ ] Empty list shows `No sessions yet` and `Every conversation you start lands here, with the agents that worked on it.`.
- [ ] A running session shows a spinner; a session blocked on a form shows orange `needs your answer`; a finished session shows nothing; `needs-answer` wins over `running`; a parent whose child is busy is running.
- [ ] "Needs your answer" works for sessions that are NOT the open one, including after a page reload (seeded at bootstrap via `GET rest_api/form`, falling back to per-session `listPendingForms` for busy top-level sessions) and live via `form.created/replied/cancelled`.
- [ ] Agent rows show spinner (running), green `done`, orange `stopped`, reusing `deriveChildStatus` (no second status logic); a cold-loaded session shows `stopped` for an `INPUT_REQUIRED` hand-back after its first expand.
- [ ] Status is never colour-only (visible or screen-reader text), spinners are `aria-hidden`, animation stops under `prefers-reduced-motion`, orange text uses `--svy-acc-fg`; no new hard-coded colours; works in light and dark theme.
- [ ] Expanded state, rename state, search term and DOM identity of rows survive live updates; no NG0600.
- [ ] `/session/active` is seeded at bootstrap (spinners on first paint after reload).
- [ ] Rename / Export / Delete, context menu and "+ New session" behave exactly as before.
- [ ] The live verification of `GET rest_api/form` is recorded (status, body shape, cross-session coverage) and both the primary and fallback paths are covered by tests.
- [ ] Whole webui test suite, `ng build` (and lint when configured) are green.

## 7. Out of scope

- Rename / Export / Delete menu, "+ New session", session selection and keyboard behaviour of rows (unchanged).
- Permission (tool approval) requests as "needs your answer".
- Fetching reports at bootstrap for all sessions, scanning older message pages for reports, pagination/virtualisation of long session lists.
- Searching message content (title only), archived-session handling, server-side search.
- Status panel, agent view and chat transcript changes (SVY-21849/21850/21852 stay as they are); the other redesign parts (3–9).
- Java/BFF changes (the BFF already proxies `rest_api/**`).
- `AGENTS.md`, `.opencode/sdd/project-context.md`, `opencode.json` (parallel work in the same working directory).

## 8. Git history

- `3325ff9` SVY-21845: three-area layout and `--svy-*` tokens (palette used here).
- `2643875` SVY-21852: status panel; defines "viewed session" semantics.
- SVY-21849 / SVY-21850: `agent-status.ts`, `busySessionIds`, `childSignals`, `deriveChildStatus`; their specs name SVY-21846 as the consumer. Stale-busy guard stays: `busy` only from events + `/session/active`, never from `time.busy > time.idle`.
- `session-list.component.*` predates the redesign (SVY-21363 empty-session history); no spec existed.

## 9. Open questions

| Question | Owner | Status |
|---|---|---|
| Does `GET rest_api/form` work through the BFF on 2.4.1 and return all sessions' forms? | Implementer (live check, 3.6) | open — fallback specified, no user decision needed |
| "This week" boundary | User | resolved: rolling, 2–6 days ago |
| Parent row when only a child is busy | User | resolved: running |
| Cold-loaded `INPUT_REQUIRED` | User | resolved: lazy fetch on first expand (3.3) |
| Year suffix for dates of other years (`Nov 10, 2025`) is not in the ticket | PM assumption | open (low risk, trivially removable) |
| Messages scanned for reports are limited to the newest page | PM assumption | accepted limitation |

## 10. Review feedback: expand chevron (Rene van Veen, Jira comment 235120)

**Problem (verified).** `.twisty` was a 1.1rem (~17 px) button with a text glyph (`▸`/`▾`) at 0.7rem (~11 px) in `--svy-muted`: tiny, low contrast, barely visible hover, no focus style.

**Change.**
- Text glyph replaced by an inline SVG chevron (16 px, `stroke: currentColor`, `aria-hidden`) that rotates 90 degrees when `aria-expanded="true"` (no second glyph); the rotation is disabled under `prefers-reduced-motion`.
- Click target 24 x 24 px (WCAG 2.5.8) through one shared variable `--twisty-size: 1.5rem`, used by `.twisty` and `.twisty-spacer`, so titles of rows without children and of agent rows stay aligned with the parent titles.
- Colour `--svy-fg2` (was `--svy-muted`), hover `--svy-hover-strong` background plus `--svy-fg`, visible `:focus-visible` outline (`--svy-accent-strong`). Tokens only; contrast is >= 3:1 in light (`#4a4740` on the sidebar) and dark (`#c9c5bc`).
- Behaviour unchanged: the click only toggles (propagation stopped, no select), `aria-expanded` / `aria-label` follow the state, a search that matches only a child title still auto-expands the row. Row height is unchanged (the row is already taller than 24 px because of the title + meta lines).

**Tests** (`session-list.component.spec.ts`, describe "twisty chevron"): svg chevron present and `aria-hidden`, no text glyph; `aria-expanded`/`aria-label` in step with the state; click does not emit `select`; spacer/child rows reserve the twisty width; auto-expand on a child-only search match.

**Live check.** Light and dark theme on a session with a subagent: 24 px target, chevron rotates, hover/focus visible.

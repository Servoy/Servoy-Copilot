# Triage Report - SVY-21847

**Verdict:** PROCEED

Note on sources: I did not call the Jira API for this report. The ticket text comes from the user context (authoritative), plus `docs/SVY-21848-triage.md` and `docs/SVY-21848-composer-command-menu.spec.md`. The design came from `D:\TEMP\opencode\redesign.html` (attachment of SVY-21845), which was already on disk. No `git blame` was run; the only git facts checked are the recent log and the SVY-21848 commit stat.

## Reported problem

Part 3 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). Today an empty chat shows one line, "What should we build in Servoy today?". It does not explain how Servoy AI works, and it does not offer the `/svy` commands as a starting point. Wanted behaviour:

- No sessions at all: a welcome screen with the title "Welcome to Servoy AI", an intro, three steps (You / Orchestrator / Agents) and four starters: `/svyImplement`, `/svyFixTicket`, `/svyFixStacktrace`, `/svyCreateE2E`.
- A new empty session for a returning user: keep the line "What should we build in Servoy today?" plus the starters.
- The screen disappears at the first message.
- A starter whose command does not exist on this server is not shown.
- Clicking a starter puts the command plus one space in the input, cursor after it, and sends nothing.
- The banner "Servoy AI is starting or no solution is active. Please wait." stays unchanged.

The ticket describes behaviour only, so there is no wrong premise to challenge.

## Root-cause assessment

This is a feature, not a bug. Current code (all under `bundles/com.servoy.eclipse.opencode/webui/src/app/`):

### Where the empty state is rendered

- `components/message-list.component.html`: `@for (message of messages()) {...} @empty { <div class="empty">What should we build in Servoy today?</div> }`. The style is `.empty` in `message-list.component.scss` (muted colour, 1.4rem, centred, padding 3rem 1rem).
- `app.component.html` renders `<svy-message-list>` for a normal session and `<svy-agent-view>` for a subagent session. The banner is `@if (store.error(); as error) { <div class="banner">...}` above them, so it is untouched by anything inside the message-list.
- The `@empty` branch is currently the only thing that distinguishes "empty". It does not distinguish "no sessions" from "empty session", and it also fires while an existing session's messages are still loading (see risks).

### How "no sessions" vs "empty new session" can be told apart

- `ChatStore.sessions` (`chat-store.service.ts:89`) = top-level sessions (`allSessions().filter(s => !s.parentID)`). **No sessions** is `sessions().length === 0`. **Empty session** is `messages().length === 0` while `sessions().length > 0`.
- The design uses the same split: `showIntro: isEmpty && list.length === 0`, `showHero: isEmpty && list.length > 0` (in `redesign.html`).
- `bootstrap()` (`chat-store.service.ts:509`) loads the list and then calls `openSession(mostRecent)` or `newSession()`. `newSession()` sets `activeSessionId = null` and `messages = []` and creates nothing on the server until the first send (lazy creation, see the comment above `newSession`). So an unsaved new session and "no sessions" both have `activeSessionId === null` and no messages. The only reliable difference is `sessions().length`.
- **There is no "sessions loaded" flag.** Before `listSessions()` returns (and when it errors, e.g. while the BFF readiness gate holds the request or fails), `sessions()` is also empty, so the intro would flash for every user at startup and would show during the "starting / no solution" banner state. A new signal in `ChatStore` (e.g. `readonly sessionsLoaded = signal(false)`, set true in the `next` branch of `bootstrap()` only) is needed to gate the whole screen. On error it stays false, so no welcome is shown next to the banner.
- Same flash risk for a returning user: `openSession(id)` sets/loads messages asynchronously. During that window `messages()` is empty although the session has content, and the hero line would flash. The spec must check how `openSession` fills `messages` (and whether a loading flag exists or is needed, e.g. `messagesLoading`) before rendering the hero for `activeSessionId !== null`. For `activeSessionId === null` (new unsaved session) no such wait exists.
- Disappearance at the first message is free: the screen lives in the `@empty` branch, so it vanishes as soon as `messages()` is non-empty. Check that `send()` appends the user message optimistically, otherwise the intro could briefly turn into the hero (the session list is refreshed after the lazy session creation). I did not verify this.

### Existing starter source (SVY-21848, commit 3946f7f)

- `services/command-catalog.service.ts`: root `CommandCatalogService`, `commands` signal (already filtered to `svy*`, whitespace-collapsed descriptions), idempotent `load()` that retries after an error.
- `services/svy-commands.ts`: pure helpers `isSvyCommand`, `toSvyCommands`, `filterSvyCommands`, `slashQuery`, `insertionText(command)` (= `'/' + name + ' '`).
- Starters must reuse these; there must be no second command list. The starter names (the 4 fixed names) are the only new constant. A starter is shown only when `catalog.commands()` contains that name, so a missing command on this server hides itself, and so does an empty catalog (error or still loading). The welcome component must call `catalog.load()` itself (the composer only loads it when the user types `/`).
- Description: use the server description from the catalog (consistent with the menu, nothing hard-coded). The design mock has its own short strings (e.g. "Build a feature from a description", "Work a Jira or ServoyCloud ticket", "Fix a bug from an error message", "Test a user flow end to end"). See the open question below.

### How the draft is set today (the mechanism to reuse)

- `ChatStore` owns the draft per session: `drafts` map keyed by session id or `NEW_SESSION_DRAFT_KEY` (`draftKey()`), `activeDraft` computed, `setActiveDraft(draft)` writes it (an empty draft removes the entry).
- `AppComponent.onDraftChange(draft)` calls `store.setActiveDraft(draft)`; `<svy-composer [draft]="store.activeDraft()" (draftChange)="onDraftChange($event)">`.
- `ComposerComponent` is controlled: `text`/`attachments` are computed from the `draft` input, and **every write goes through the private `emitDraft(text, attachments)`** which emits `draftChange`. `pickCommand(command)` (composer.component.ts:134) does `emitDraft(insertionText(command), this.attachments())`, resets `activeIndex`, `textarea().nativeElement.focus()`, `autoGrow()`. Starters should do the same thing: the same `insertionText()` and the same draft write path.
- Because the welcome screen is not inside the composer, it cannot call `pickCommand`. The draft part is simple: `store.setActiveDraft({ text: insertionText(cmd), attachments: store.activeDraft().attachments })` (keep existing attachments, replace the text, as the design does with `setState({ text })`).
- **Focus and cursor is the part that needs new code.** The textarea uses `[ngModel]="text()"`, which writes the DOM value asynchronously (after change detection / a microtask), so calling `focus()` synchronously in the click handler focuses a textarea that still has the old value and the caret is not guaranteed at the end. Needed: a small public method on the composer, e.g. `focusEnd()`, that runs after the next render (`afterNextRender` / `afterRenderEffect`, or a microtask after `detectChanges`), then `el.focus()`, `el.setSelectionRange(len, len)` and `autoGrow()`. `AppComponent` already owns both siblings and can add `viewChild(ComposerComponent)`: `onStarter(cmd)` = `store.setActiveDraft(...)` then `composer()?.focusEnd()`. Note `AppComponent` currently only has `viewChild(MessageListComponent)`/`StatusPanelComponent`, and the composer is inside an `@if/@else` (it is absent for a subagent session, where the welcome is not shown anyway), so use `viewChild` with an optional chain.
- A starter click must never call `store.send`/`sendMessage`. Only the draft is written.

### Design reference (`redesign.html`)

- Intro (no sessions): H1 "Welcome to Servoy AI" (Space Grotesk 600, 48px, "AI" in accent), intro "Describe what you need in your solution. A team of agents works it out - you only answer the questions that matter.", a 3-column grid of steps (mono label "01 · You", "02 · Orchestrator", "03 · Agents" in accent / accent / blue; title + description each):
  - 01 You: "Ask in plain words" / "Type, paste a screenshot, or pick a / command."
  - 02 Orchestrator: "Plans, delegates, asks" / "The only one who talks to you. It asks when a choice is yours."
  - 03 Agents: "Do one task, report back" / "Each agent finishes or stops and hands back. Open one to see its work."
  - Below that "Try one" label (uppercase, muted) and a 2-column grid of 4 starter cards (mono `/cmd` in accent + muted description), border `--line`, bg `--surface`, radius 12.
- Hero (empty session, returning user): H1 "What should we build in Servoy today?" (44px, "Servoy" in accent) plus a centred row of 4 pill buttons with only `/cmd` (40px high, radius 999, mono 13px, accent text). The ticket keeps the existing line text "What should we build in Servoy today?" (the user context says the line stays).
- The design's token names (`--acc-fg`, `--muted`, `--line`, `--surface`, `--bg`, `--fg`, `--blue-fg`) are the design's own; in the app they are the `--svy-*` tokens from SVY-21845 (`--svy-acc-fg`, `--svy-muted`, ... used in `message-list.component.scss`). Only existing `--svy-*` tokens should be used; check `styles.scss` for the exact names (e.g. a blue accent for step 03) and do not add new ones without need.
- The design's steps text contains a middle dot and an en dash in the mock; the app files already avoid special characters in places (see the encoding glitches in the composer placeholder), so use plain ASCII ("01 - You" or a CSS pseudo separator) unless the file encoding is confirmed UTF-8.

## Ticket premise check

The ticket proposes only behaviour. It holds up, with these findings:

1. "No sessions" vs "empty session" cannot be derived from `messages()` alone; it needs `sessions().length` and a new "sessions loaded" gate to prevent a flash at startup and in the error / "please wait" state.
2. The starter filter ("only if the command exists on this server") is already supported by the catalog from SVY-21848; it works as long as the welcome component triggers `catalog.load()`.
3. "Set the draft, cursor behind it" is not a one-liner in a controlled component with `[ngModel]`; it needs a small public `focusEnd()` on the composer called after render.

## Approaches considered

1. **New presentational `svy-welcome` component rendered in the `message-list` `@empty` branch; AppComponent handles the click (recommended).**
   - `components/welcome.component.{ts,html,scss,spec.ts}` (standalone, OnPush). Inputs: `hasSessions` (intro vs hero); it injects `CommandCatalogService` and calls `load()`. Output: `pick: SvyCommand` (or the command name).
   - A pure helper, e.g. `starterCommands(list: SvyCommand[]): SvyCommand[]` with a `STARTER_NAMES = ['svyImplement', 'svyFixTicket', 'svyFixStacktrace', 'svyCreateE2E']` constant, filters to the commands present and keeps the fixed order. Put it in a new file (e.g. `services/starters.ts` + spec) so `svy-commands.ts` stays untouched.
   - `message-list`: replace the `<div class="empty">` with `<svy-welcome ...>`, add a `hasSessions` input and a `(starter)` output that re-emits. Message-list stays a dumb list.
   - `app.component`: pass `[hasSessions]="store.sessions().length > 0"`; on `(starter)` do `store.setActiveDraft({ text: insertionText(cmd), attachments: store.activeDraft().attachments })` then `composer()?.focusEnd()`. Gate with `store.sessionsLoaded()` so nothing is shown before the list loaded.
   - `composer`: add public `focusEnd()` only (no change to existing behaviour).
   - `chat-store`: add `sessionsLoaded` signal (plus possibly a messages-loading signal if needed).
   - Pros: reuses catalog, helpers and the draft path; welcome is unit-testable; the banner and the rest of `app.component.html` are untouched except for 2 bindings. Cons: touches 4 existing files (message-list, app.component, composer, chat-store), each by a very small amount.
2. Render the welcome in `app.component.html` instead of `message-list` (`@if (empty) { <svy-welcome> } @else { <svy-message-list> }`). Pros: message-list not touched. Cons: more `app.component.html` churn and the message-list loses its own empty state; larger conflict surface with the parallel session. Not recommended.
3. Let the welcome inject `ChatStore` directly and write the draft itself, with a store-level focus request signal that the composer reacts to via an effect. Pros: no AppComponent wiring. Cons: puts UI focus state in the store, and a composer effect reacting to a counter is harder to test; the store is the largest file and a conflict magnet. Not recommended.
4. **No code change** - not viable: the acceptance criteria describe missing UI; nothing existing gives the intro, steps or starters.

## Recommendation

PROCEED with approach 1. Suggested slices for the spec:

1. `starters` helper + spec (fixed order, missing command hidden, empty catalog gives `[]`).
2. `ChatStore.sessionsLoaded` (set in the `bootstrap` `next` branch only) + store spec; check and, if needed, add a messages-loading guard for `openSession` so a returning user does not see the hero flash.
3. `svy-welcome` component (intro/hero variants, steps, starter buttons with `/name` and description, `aria` labels, `catalog.load()` on init, no starters rendered when none exist) + spec.
4. `message-list` replaces the `@empty` line with the welcome (keep the text "What should we build in Servoy today?" in the hero variant) + spec update.
5. `composer.focusEnd()` + `app.component` wiring (`viewChild(ComposerComponent)`, `onStarter`) + specs: click sets the draft to `/svyImplement `, keeps attachments, emits no send, and the textarea gets focus with the caret at the end (assert `selectionStart === value.length` after `await fixture.whenStable()`).
6. Manual check in a running Servoy Developer with: no sessions (fresh state), returning user + "new session", and the "starting / no solution" banner state (no welcome while `sessionsLoaded` is false).

Open questions for the human:

- Starter descriptions: server description from the catalog (recommended, consistent with the `/` menu) or the design's own short texts? Using the design's texts would mean a second, hard-coded text list, which the user context rules out.
- In the hero variant (returning user), show only the `/name` pills as in the design (recommended, no descriptions) or the full cards?

### Files and conflicts with the parallel SVY-21851 session (file reader)

SVY-21851 is expected to touch: `OpencodeChatServlet` (+ its tests), the reader, and the agent view (`components/agent-view.component.*`, `services/agent-view.ts`, `agent-card/agent-task/agent-report` etc.).

Likely to conflict with approach 1:

- `app.component.html` / `app.component.ts`: `svy-agent-view` is rendered there (`@if (store.activeIsSubagent())`); if 21851 adds inputs/outputs to the agent view they edit the same file. Risk: medium-low (my edit is 2 bindings in the `svy-message-list` block plus one `viewChild` and one handler, disjoint lines). Expect a trivial textual merge at worst.
- `models/opencode.models.ts` and `services/opencode-api.service.ts`: not needed by this ticket (the `/command` wrapper already exists), so avoid them.
- `services/chat-store.service.ts`: only if 21851 changes the store (a file reader could add state). Risk low, but it is the largest shared file; keep the edit to one small additive signal near `bootstrap`.

Recommended set that does NOT overlap with 21851 (all new or small additive): new `components/welcome.component.{ts,html,scss,spec.ts}`, new `services/starters.ts` + `starters.spec.ts`, `components/message-list.component.{ts,html,scss,spec.ts}` (only the empty branch), `components/composer.component.{ts,spec.ts}` (only `focusEnd()`), plus the unavoidable small additive edits in `app.component.{ts,html,spec.ts}` and `services/chat-store.service.{ts,spec.ts}`. Do NOT touch `agent-*` components, `services/agent-view.ts`, `OpencodeChatServlet`, `ServicesProvider`, `opencode-api.service.ts`, `opencode.models.ts`, global styles or tokens.

## Git history findings

- Recent master (unpushed): 53b09ca and 40866ff (SVY-21850 agent view), 3946f7f (SVY-21848: composer command menu, `svy-commands.ts`, catalog service), ecdfac5 (SVY-21849), 2643875 (SVY-21852), 0053464 (opencode 2.4.1).
- No prior spec or attempt for a welcome screen; `docs/` contains only the SVY-21848 reports on this topic. The one-line empty state in `message-list.component.html` was not blamed in detail.
- Working tree has unrelated local changes (`opencode.json`, `testresources/servoy.properties`, untracked `servoy_lts_extracted/`); none in `webui/`.

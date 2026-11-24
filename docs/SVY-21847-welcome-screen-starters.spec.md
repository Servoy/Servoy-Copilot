# Spec: SVY-21847 - Welcome screen that explains how Servoy AI works and offers the /svy starters

## 1. Goal
Part 3 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`, design: attachment `ServoyAI Redesign.html` on SVY-21845, which blocks this case). Today an empty chat shows one line, "What should we build in Servoy today?". A first-time user learns nothing about the agent team, the Orchestrator or the `/svy` commands. This change adds a welcome screen: a user with no sessions gets the full welcome (title, intro, three steps, four starters); a returning user who opens a new, empty session keeps the line "What should we build in Servoy today?" plus the starters. Clicking a starter puts the command and one space in the input, cursor behind it, and sends nothing. The screen disappears at the first message. The banner "Servoy AI is starting or no solution is active. Please wait." stays unchanged.

## 2. Background
Findings in `docs/SVY-21847-triage.md` are authoritative; approach 1 was approved by the human. The Jira ticket was read through the API for this spec (status Open, no comments, no attachments on this case, one link: blocked by SVY-21845). The ticket text matches the triage; acceptance criteria are reproduced in section 5.

### 2.1 Current code (`bundles/com.servoy.eclipse.opencode/webui/src/app/`)
- `components/message-list.component.html`: `@for (message ...) {...} @empty { <div class="empty">What should we build in Servoy today?</div> }`. `.empty` style lives in `message-list.component.scss`. Inputs today: `messages`, `canLoadOlder`, `childSignals`, `turnBusy`, `restoreScroll`.
- `app.component.html`: banner `@if (store.error(); as error) { <div class="banner">...}` sits above the `@if (store.activeIsSubagent()) { <svy-agent-view> } @else { <svy-message-list> }` block; the composer (`<svy-composer>`) is in the `@else` of a second `activeIsSubagent` block. `AppComponent` has `viewChild(MessageListComponent)`, `viewChild(StatusPanelComponent)`, a `statusButton` viewChild, `onDraftChange(draft)` -> `store.setActiveDraft(draft)`, `ngOnInit` -> `store.bootstrap()`.
- `services/chat-store.service.ts`: `sessions` = top-level sessions (`allSessions` filtered on `!parentID`); `activeSessionId`; `messages`; `error`; per-session drafts (`setActiveDraft`, `activeDraft`, `NEW_SESSION_DRAFT_KEY`); `bootstrap()` loads the list in `next` and then calls `openSession(mostRecent)` or `newSession()`; on `error` only `error.set(...)`.
- `components/composer.component.ts`: controlled component, private `emitDraft`, `pickCommand(command)` (emit draft, focus, `autoGrow()`), textarea bound with `[ngModel]="text()"` (DOM value written asynchronously by NgModel), private `autoGrow()`.
- `services/command-catalog.service.ts`: root service, `commands` signal (only `svy*`, descriptions whitespace-collapsed), idempotent `load()` that retries after an error. `services/svy-commands.ts`: `SvyCommand { name, description }`, `insertionText(command)` = `'/' + name + ' '`.

### 2.2 Investigation requested by the PM brief (read from code)
1. "No sessions" vs "empty session". `messages()` alone cannot tell them apart. `newSession()` sets `activeSessionId = null` and `messages = []`; nothing exists on the server until the first send. The only reliable difference is `sessions().length` (0 = intro, > 0 = hero). There is no "sessions loaded" flag: before `listSessions()` answers, and when it errors (BFF readiness gate, no solution), `sessions()` is also empty, so the intro would flash for every user at start-up and would show next to the "starting / no solution" banner. A new `sessionsLoaded` signal is required (section 3.1).
2. Does `openSession` flash an empty state for a returning user? **Yes.** `openSession(id)` sets `activeSessionId = id` and `messages.set([])` synchronously, then loads the newest page asynchronously via `listMessagePage(id)`; `messages` is only filled in the `next` callback. During that window `messages()` is empty for a session that has content, so an unguarded `@empty` branch would show the hero line (and its starters) for a moment on every session switch and at start-up. There is no loading flag today. On `listMessagePage` error the `next` branch never runs and `messages` stays empty next to an error banner. Smallest fix: one more private signal, `messagesLoaded`, plus a computed `welcomeReady` that the app binds (section 3.1). The same flash exists today for the old single line; this change makes it more visible, so it is fixed here.
3. Does `send()` add the user message optimistically? **Yes, when there is text or a file part.** `send()` -> `dispatch(id, parts)` -> `appendOptimisticUserMessage(id, parts)` (id `local-user-<ts>`), synchronously in the same tick that `activeSessionId` is set for a lazily created session (`createSession().subscribe(next)`: `activeSessionId.set`, `messages.set([])`, `upsertSession(session)`, `dispatch(...)`). So the welcome disappears at the first message, and `sessions().length` grows in the same tick, so the intro never turns into the hero between send and first render. Two small consequences, both accepted: (a) from the fresh session the welcome stays visible during the `createSession` round trip (the message is only appended after the session exists), (b) a prompt with neither text nor files is not rendered (cannot happen from the composer, which blocks empty sends). After `createSession` the new session id has `messagesLoaded = false`; the store must set it true there (section 3.1) so `welcomeReady` is not stuck false for a session that has nothing to load.

### 2.3 Design reference (`redesign.html`, mapped to app tokens)
- Intro (no sessions): H1 "Welcome to Servoy AI" ("AI" in accent), intro sentence, 3-column step grid (mono label in accent / accent / blue, title + description), label "Try one", 2-column grid of 4 starter cards (mono `/name` in accent + muted description, border, surface background, radius 12).
- Hero (returning user, empty session): the line "What should we build in Servoy today?" ("Servoy" in accent) plus a centred row of pill buttons with only `/name` (mono 13px, accent text, radius 999, 40px high).
- Tokens (verified in `src/styles.scss`, light and dark defined): `--svy-fg`, `--svy-fg2`, `--svy-muted`, `--svy-acc-fg`, `--svy-acc-soft`, `--svy-acc-line`, `--svy-blue-fg`, `--svy-blue-soft`, `--svy-line`, `--svy-surface`, `--svy-soft`, `--svy-focus-ring`, `--svy-mono`. No new tokens. There is no display-font token, so headings use the inherited font family.

### 2.4 Parallel work
Another session works on SVY-21851 (file reader: `OpencodeChatServlet`, reader, agent-view / `agent-*` components, `services/agent-view.ts`). A failing test that belongs to that work does not stop this work (see 4.0).

## 3. Design

### 3.1 `ChatStore` additions (`services/chat-store.service.ts`, additive only)
- `private readonly sessionsLoadedSig = signal(false)` and `readonly sessionsLoaded = this.sessionsLoadedSig.asReadonly()`. Set to `true` ONLY in the `next` branch of `bootstrap()`, before `openSession` / `newSession` is called. The `error` branch leaves it `false` (no welcome next to the banner). `refreshSessions()` does not touch it.
- `private readonly messagesLoadedSig = signal(false)`:
  - `openSession(id)`: `messagesLoadedSig.set(false)` at the top.
  - `listMessagePage` `next` (after the `activeSessionId() !== id` guard): `messagesLoadedSig.set(true)`.
  - `listMessagePage` `error`: unchanged, stays `false` (banner shown, no welcome).
  - `send()` -> `createSession` `next`: `messagesLoadedSig.set(true)` (nothing to load for a session just created).
  - `newSession()` / `afterRemoval()`: untouched (they set `activeSessionId = null`, where the flag is irrelevant).
- `readonly welcomeReady = computed(() => this.sessionsLoadedSig() && (this.activeSessionId() === null || this.messagesLoadedSig()))`.
- Edit style: add the signals next to `activeSessionId`/`messages`, add one line per place above, keep comments in the existing Javadoc style. No other change to the store.

### 3.2 Starter helper - `services/starters.ts` (+ `starters.spec.ts`)
```ts
export const STARTER_NAMES: readonly string[] = ['svyImplement', 'svyFixTicket', 'svyFixStacktrace', 'svyCreateE2E'];
export function starterCommands(list: readonly SvyCommand[]): SvyCommand[]
```
Pure, imports only the `SvyCommand` type. Returns the commands whose `name` is in `STARTER_NAMES`, in `STARTER_NAMES` order (not catalog order), skipping missing names; empty catalog gives `[]`; duplicates in the catalog yield the first match once. `svy-commands.ts` and the catalog are not changed and no second command list exists: names are the only new constant, descriptions come from the catalog.

### 3.3 `svy-welcome` component - `components/welcome.component.{ts,html,scss,spec.ts}`
Standalone, OnPush, selector `svy-welcome`. No Eclipse/DOM-global access.
- `readonly hasSessions = input(false)`; `readonly pick = output<SvyCommand>()`.
- `private readonly catalog = inject(CommandCatalogService)`; `ngOnInit` calls `this.catalog.load()` (idempotent; the composer only loads when the user types `/`). `readonly starters = computed(() => starterCommands(this.catalog.commands()))`.
- Template, a root `<section class="welcome" aria-label="Welcome">`:
  - Intro variant (`!hasSessions()`): `<h1 class="title">Welcome to Servoy <span class="accent">AI</span></h1>`; `<p class="intro">Describe what you need in your solution. A team of agents works it out - you only answer the questions that matter.</p>`; an `<ol class="steps">` with three `<li>`: label `01 - You` / title `Ask in plain words` / text `Type, paste a screenshot, or pick a / command.`; label `02 - Orchestrator` / `Plans, delegates, asks` / `The only one who talks to you. It asks when a choice is yours.`; label `03 - Agents` / `Do one task, report back` / `Each agent finishes or stops and hands back. Open one to see its work.` Labels use accent, accent, blue (`--svy-blue-fg`). Then, only when `starters().length > 0`: `<h2 class="try">Try one</h2>` and `<ul class="cards">` with one `<li><button type="button" class="card" (click)="pick.emit(cmd)"><span class="cmd">/{{ cmd.name }}</span><span class="desc">{{ cmd.description }}</span></button></li>` per starter.
  - Hero variant (`hasSessions()`): `<h1 class="title">What should we build in <span class="accent">Servoy</span> today?</h1>` (the exact text content "What should we build in Servoy today?" must be preserved; spans do not alter textContent) and, only when `starters().length > 0`, `<ul class="pills">` with `<li><button type="button" class="pill" [attr.title]="cmd.description || null" (click)="pick.emit(cmd)">/{{ cmd.name }}</button></li>`. No descriptions shown in the hero.
- A starter whose command is missing on this server is not rendered (comes from `starterCommands`). An empty or still-loading catalog renders no starters; the intro still shows its title, text and steps, the hero its line. Starters appear when the catalog arrives (small layout shift accepted).
- ASCII text only (use `-`, no middle dot / en dash / curly quotes). Buttons are native `<button>` (keyboard operable) with a visible `:focus-visible` outline using `--svy-acc-fg` and `--svy-focus-ring`. Colour contrast uses the existing token pairs only.
- SCSS: `:host { display: block; }`, `.welcome { max-width: 860px; margin: 0 auto; padding: 3rem 1rem; display: flex; flex-direction: column; gap: 1.25rem; }`; intro left aligned, hero centred; steps a 3-column grid collapsing to 1 column below ~640px; cards a 2-column grid collapsing to 1; card: border `1px solid var(--svy-line)`, background `var(--svy-surface)`, radius 12px, hover `var(--svy-surface-hover)`; cmd `font-family: var(--svy-mono)`, colour `var(--svy-acc-fg)`; desc colour `var(--svy-muted)`; pill: height 40px, radius 999px, mono 13px, accent text, border `var(--svy-acc-line)`, background `var(--svy-acc-soft)`. Reuse the old `.empty` accent colour for `.accent`.
- The component never calls the store and never emits a send event; it only emits `pick`.

### 3.4 `message-list` (only the empty branch)
- `message-list.component.ts`: add `readonly showWelcome = input<boolean>(false)`, `readonly hasSessions = input<boolean>(false)`, `readonly starter = output<SvyCommand>()`; import `WelcomeComponent` in `imports`. Nothing else changes (scroll logic untouched).
- `message-list.component.html`: replace `<div class="empty">What should we build in Servoy today?</div>` by `@if (showWelcome()) { <svy-welcome [hasSessions]="hasSessions()" (pick)="starter.emit($event)"></svy-welcome> }`. When `showWelcome()` is false the empty branch renders nothing (no flash, nothing next to the banner).
- `message-list.component.scss`: remove the now unused `.empty` rule (the accent colour moves into the welcome styles).

### 3.5 `composer.focusEnd()` (`components/composer.component.ts`, additive)
New public method, no change to existing behaviour:
```ts
focusEnd(): void
```
Uses `afterNextRender(() => queueMicrotask(() => { const el = this.textarea().nativeElement; el.focus(); const end = el.value.length; el.setSelectionRange(end, end); this.autoGrow(); }), { injector: this.injector })` with `private readonly injector = inject(Injector)`. Rationale: the draft arrives as a changed `draft` input, the DOM value is written by NgModel in a promise microtask during/after the render, so focus + caret must run after both the render and that microtask; the microtask queued from `afterNextRender` is ordered after NgModel's. If the unit test shows the value is not yet written, fall back to `setTimeout(..., 0)` inside `afterNextRender` (keep the rest). `autoGrow()` stays private and is only called from here.

### 3.6 `AppComponent` wiring (small additive edits)
- `app.component.ts`: `private readonly composer = viewChild(ComposerComponent)`; `onStarter(command: SvyCommand): void { this.store.setActiveDraft({ text: insertionText(command), attachments: this.store.activeDraft().attachments }); this.composer()?.focusEnd(); }`. Imports `insertionText`, `SvyCommand` from `./services/svy-commands`. It never calls `store.send` / `onSend`. The existing draft text is replaced (as in the design); existing attachments are kept.
- `app.component.html`: add three bindings to the existing `<svy-message-list>` block only: `[showWelcome]="store.welcomeReady()"`, `[hasSessions]="store.sessions().length > 0"`, `(starter)="onStarter($event)"`. The banner and everything else stay untouched.
- Subagent sessions: `svy-agent-view` is rendered instead of the message list, so the welcome is never shown there (the composer is absent too; `composer()` is then `undefined` and the optional chain protects).

### 3.7 Constraints
Single quotes, `inject()`, `readonly` signals, OnPush, zoneless (never write signals during template rendering, NG0600), standalone components, no new tokens, no comments in new code unless needed (store additions follow the file's existing comment style), no changes to `Attachment`, `SendPart`, API service or models.

## 4. Implementation plan

### 4.0 File boundaries (parallel work with SVY-21851)
ONLY these files may be created or modified (all under `bundles/com.servoy.eclipse.opencode/webui/src/app/`):
- NEW: `components/welcome.component.{ts,html,scss,spec.ts}`, `services/starters.ts`, `services/starters.spec.ts`.
- `components/message-list.component.{ts,html,scss,spec.ts}` - ONLY the empty branch and its inputs/output (3.4).
- `components/composer.component.{ts,spec.ts}` - ONLY `focusEnd()` and its injector (3.5).
- Small additive edits: `app.component.{ts,html,spec.ts}` (3.6) and `services/chat-store.service.{ts,spec.ts}` (3.1).

Do NOT touch: `agent-*` components, `services/agent-view.ts`, `OpencodeChatServlet` and its tests, `ServicesProvider`, `services/opencode-api.service.ts`, `models/opencode.models.ts`, `svy-commands.ts`, `command-catalog.service.ts`, global styles / tokens (`styles.scss`). If a change outside this list seems necessary, stop and report it. A failing test or build error that belongs to the SVY-21851 work (files above) does NOT stop this work: note it in the result, keep going, and only make sure the specs listed here pass. Do not commit, push or `git add`.

### 4.1 Steps (each with its tests; run from `bundles/com.servoy.eclipse.opencode/webui/`)
1. `services/starters.ts` + `starters.spec.ts`: fixed order regardless of catalog order; a missing command is skipped (e.g. catalog without `svyFixTicket` returns the other three in order); empty list gives `[]`; non-starter commands are ignored; duplicates yield one entry; returned objects keep `description`. Run `npx ng test --include="**/starters.spec.ts" --no-watch`.
2. `chat-store.service.ts` + spec (extend the existing spec's API stubs): `sessionsLoaded` false initially, stays false when `listSessions` errors (and `error` is set), true after `bootstrap()` success with and without sessions; `messagesLoaded`/`welcomeReady`: `openSession` sets it false until `listMessagePage` answers, then true; a late answer for a session the user left does not flip it; page error leaves it false; `newSession()` after bootstrap gives `welcomeReady() === true` (activeSessionId null); `send()` from a fresh session (createSession ok) leaves `welcomeReady` true and the optimistic message present; `welcomeReady` is false before bootstrap. Run `npx ng test --include="**/chat-store.service.spec.ts" --no-watch`.
3. `welcome.component.*` + spec (stub `CommandCatalogService` with a writable `commands` signal and a `load` spy): `load()` called on init; intro variant shows "Welcome to Servoy AI", the intro sentence, three steps (labels `01 - You`, `02 - Orchestrator`, `03 - Agents`) and "Try one"; hero variant shows textContent "What should we build in Servoy today?", pills with `/name` only and no description text, and no steps/"Try one"; cards show `/name` + catalog description; starters follow `STARTER_NAMES` order; a starter missing from the catalog is not rendered; an empty catalog renders no starters/"Try one" but intro and hero text remain; click on a card/pill emits `pick` with the exact `SvyCommand`; the component has no send output; `fixture.detectChanges()` after changing `hasSessions`/commands does not throw (NG0600 guard). Run `npx ng test --include="**/welcome.component.spec.ts" --no-watch`.
4. `message-list.*` + spec: `showWelcome=false` and no messages renders no `svy-welcome` and no text; `showWelcome=true` renders `svy-welcome` with `hasSessions` forwarded; a `pick` from the welcome re-emits on `starter`; with messages present no welcome is rendered even when `showWelcome=true`; existing specs stay green. Run the message-list spec.
5. `composer.focusEnd()` + `app.component.*` + specs. Composer spec: set the draft to `/svyImplement `, call `focusEnd()`, `await fixture.whenStable()`, assert `document.activeElement` is the textarea and `selectionStart === selectionEnd === textarea.value.length`; with a long multi-line draft the textarea height is recomputed; no `sendMessage`/`draftChange` emitted by `focusEnd()`. App spec: clicking a starter (or calling `onStarter`) sets the store draft to `/svyImplement ` keeping existing attachments, replaces previous text, never calls `store.send`, and focuses the composer textarea with the caret at the end; `welcomeReady` false (before bootstrap, banner state) renders no welcome; banner markup unchanged; welcome disappears after `store.messages` becomes non-empty. Run the composer and app specs.
6. Run the full suite and the build: `npx ng test --no-watch`, then `ng build`. Leave `webui-dist` as a production build (`npm run build`) before any later commit (not committing here).
7. Manual check against a running Servoy Developer (AGENTS.md "Quick debugging of the Angular frontend", `ng build --watch --configuration development`, `http://127.0.0.1:8183/servoy_ai/`): fresh state with no sessions (intro + 4 starters, sidebar "No sessions yet"); returning user -> "new session" (hero line + pills); opening an old session and reloading shows no hero flash; "starting / no solution" state shows only the banner; starter click fills the input, caret at the end, nothing sent; send hides the welcome at once; `?debug_view=true` can be used to check event timing.

## 5. Acceptance criteria
- [ ] A user with no sessions sees "Welcome to Servoy AI", one sentence of intro, the three steps (You / Orchestrator / Agents) and the four starter cards `/svyImplement`, `/svyFixTicket`, `/svyFixStacktrace`, `/svyCreateE2E`, each with the one-line description from the server.
- [ ] A user with sessions who opens a new, empty session sees "What should we build in Servoy today?" and the same starters as `/name` pills (no descriptions, no steps).
- [ ] The welcome disappears as soon as the first message is sent (optimistic user message) and does not flash while an existing session's messages are loading or after a load error.
- [ ] A starter whose command does not exist on this server is not shown; with no available starters the rest of the welcome still renders.
- [ ] Clicking a starter puts `/name ` (command plus one space) in the input, keeps existing attachments, focuses the input with the caret after the space, and sends nothing.
- [ ] Nothing of the welcome is shown before the session list has loaded or when loading failed; the banner "Servoy AI is starting or no solution is active. Please wait." and its behaviour are unchanged.
- [ ] No second command list: names come from `STARTER_NAMES`, descriptions from `CommandCatalogService`; only existing `--svy-*` tokens, ASCII text, keyboard-operable buttons with visible focus.
- [ ] Only the files in 4.0 changed; new/changed specs pass, the full `ng test --no-watch` and `ng build` succeed (failures that belong to the SVY-21851 files do not block, but are reported).

## 6. Out of scope
- Changing the `/command` API, the catalog, `svy-commands.ts`, or the list of `/svy` commands; starters beyond the four named ones; starter descriptions hard-coded in the UI.
- Sending anything on starter click; opening the command menu from a starter.
- Subagent (agent view) empty states, the sidebar "No sessions yet" text, banner text or logic, retrying `bootstrap()` after a failed start-up.
- New design tokens, global styles, the BFF servlet, Java code.
- Showing the welcome during the `createSession` round trip differently (it stays until the optimistic message is appended).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| `bootstrap()` runs once; after a start-up error `sessionsLoaded` stays false, so the welcome only appears after a reload (consistent with the banner state, same as the rest of the UI today). Accept, or retry bootstrap when the server becomes ready (separate case)? | PM | resolved: no retry needed; the BFF servlet and opencode start in the same developer instance, so the server is up before the UI loads |
| A starter click replaces any text already typed in the draft (as in the design) and keeps attachments. Confirm that replacing, not appending, is intended. | PM | resolved: replacing is intended |
| `focusEnd()` relies on the microtask ordering after NgModel's write; if the unit test shows it is flaky, switch to `setTimeout(0)` inside `afterNextRender` (no behavioural change). | Dev | open |
| `sessions().length > 0` counts archived top-level sessions; a user with only archived sessions gets the hero, not the intro. Acceptable? | PM | resolved: acceptable |

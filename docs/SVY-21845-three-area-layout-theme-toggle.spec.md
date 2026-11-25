# Spec: SVY-21845 — Servoy AI: three-area layout with a closable status panel, and a theme toggle on top of the Developer theme

Part 1 of 9 of the `servoyai-redesign` set. Blocks SVY-21846, 21847, 21848, 21849, 21852.
Triage report (authoritative, holds the full palette / dimension / contrast reference): `docs/SVY-21845-triage.md`.
Approved approach: shell-level layout state + CSS tokens, with the alias compromise (new `--svy-*` design tokens, legacy names re-pointed to them).

## 1. Goal

Turn the chat shell into three areas: sessions | chat | closable status panel. The chat area gets a header with a theme toggle and a status-panel toggle. Introduce the new light and dark palettes so the app follows the Developer theme on open and the user can flip it at runtime without losing the open conversation or typed text. Orange used as text becomes a darker, readable shade. The work is structured so SVY-21846/47/48/49/52 only consume header slots, the shell `statusOpen` signal, tokens and width CSS variables, and never touch the layout again.

## 2. Background

### 2.1 Current state (verified in code)
- `app.component.html/.scss`: 2-column grid (`260px 1fr`), `<aside class="sidebar">` with `svy-session-list`, `<main class="chat">` with `.topbar` hosting `<svy-status-panel>`, then banner, message list, form card, composer. `:host { height: 100vh }`.
- `StatusPanelComponent` owns an `open` signal, a toggle button (`.status-toggle`, with the overall dot) and an absolutely positioned 300px `.popover` holding the body (Server / Providers / MCP sections), plus 15 s polling and `overall()`.
- `ThemeService` (`services/theme.service.ts`): `resolve()` reads `?darkmode=` (`''`, `'true'`, `'1'` -> dark; any other value -> light; absent -> light), `apply()` sets `data-theme` on `<html>`, `applyResolved()` is called in `main.ts` before bootstrap (no flash). OS `prefers-color-scheme` is deliberately not consulted. Not signal based.
- `styles.scss`: legacy `--svy-*` tokens (light in `:root`, dark in `:root[data-theme='dark']`), accent `#f5a623` also used as text colour (2.03:1 on white, fails WCAG AA).
- No `@media` rules; fixed 260px sidebar column.
- No `app.component.spec.ts` and no `theme.service.spec.ts`. `status-panel.component.spec.ts` exists (12 tests, mostly data logic).

### 2.2 Prior decisions to preserve
- No light-to-dark flash: theme applied in `main.ts` before bootstrap.
- Do not consult OS `prefers-color-scheme`.
- Zoneless, OnPush, signals; never write a signal during rendering (NG0600).

## 3. Design

### 3.1 Resolved decisions (human, recorded)
| # | Question | Decision |
|---|----------|----------|
| 1 | Persist the manual toggle? | **No.** In-memory only. `?darkmode=` decides on open. No URL rewrite, no reload, no localStorage. |
| 2 | Fonts | Bundle **Inter** and **JetBrains Mono** woff2 in the webui (SIL OFL; add license attribution), with system-font fallback. **Space Grotesk deferred to SVY-21846** (brand block). |
| 3 | Status panel body | Keep the existing popover body; `StatusPanelComponent` data logic untouched (SVY-21852 redesigns it). Re-host it in the docked right column; remove only the popover wrapper and toggle-button chrome; open state moves to the shell. Keep and update its spec. |
| 4 | Status panel default | **Open.** |

### 3.2 Shell layout (`app.component.*`)
Root `.app`: `display: flex; flex-wrap: wrap; min-height: 100vh; background: var(--svy-bg); color: var(--svy-fg)`. Three children:

- `<aside class="sidebar">` (sessions): `flex: 1 1 var(--svy-sidebar-w, 260px); min-width: var(--svy-sidebar-min-w, 240px); max-width: var(--svy-sidebar-max-w, 288px); border-right: 1px solid var(--svy-line)`; `display:flex; flex-direction: column; min-height: 0`. Content (`svy-session-list`) unchanged.
- `<main class="chat">`: `flex: 999 1 var(--svy-chat-basis, 560px); min-width: 0; background: var(--svy-surface); height: 100vh; min-height: 640px; display:flex; flex-direction: column`. Contains header, banner, message list, form card, composer (all as today).
- Status area `<aside id="status-panel" aria-label="Status">`: `flex: 1 1 var(--svy-status-w, 300px); min-width: var(--svy-status-min-w, 280px); max-width: var(--svy-status-max-w, 340px); background: var(--svy-surface); border-left: 1px solid var(--svy-line); overflow: auto`. Shown only while `statusOpen()` is true (see 3.4). When hidden it takes no width, so the chat reclaims it.

The width variables (`--svy-sidebar-w`, `--svy-sidebar-min-w`, `--svy-sidebar-max-w`, `--svy-chat-basis`, `--svy-status-w`, `--svy-status-min-w`, `--svy-status-max-w`) are defined once in `styles.scss` `:root` so follow-up cases do not edit layout rules.

Responsive (revised after visual review; supersedes the original wrap-and-stack scheme, which put the status panel under the chat and produced a double scrollbar). The shell is exactly one viewport high (`:host { height: 100vh; overflow: hidden }`, `.app { height: 100% }`, `.chat { min-height: 0 }`), so the page itself never scrolls and only the message list scrolls internally:
- Viewport >= 1080px: three areas side by side, status panel docked in the row (`flex` basis/min/max from the width variables) and open by default.
- Viewport < 1080px (`@media (max-width: 1079.98px)`): the status area is no longer a row item but `position: fixed` on the right, full height, `z-index: 20`, width `min(var(--svy-status-max-w), 100vw)` with a shadow, i.e. a temporary overlay over the chat. It starts closed; the header status button (or its close button) opens/closes it.
- Sessions area (`#sessions-panel`, `aside.sidebar`) follows the same principle on the left, with its own shell signal `sessionsOpen` and a header button (`.sessions-btn`, `aria-label="Sessions"`, `aria-controls`/`aria-expanded`) at the left of the header. From 800px up it is docked in the row (fixed `--svy-sidebar-w` basis), open by default, and can be collapsed so the chat widens (the chat is pushed, nothing overlaps). Below 800px (`@media (max-width: 799.98px)`) it is a `position: fixed` left overlay (`z-index: 20`, shadow) over a `.sessions-scrim` (`z-index: 19`) that closes it on click, closed by default; selecting a session below 800px closes it. The aside stays mounted (`hidden`) so the session list is not destroyed.
- Views never stack on top of each other: they sit side by side when there is room, otherwise they become overlays. No horizontal scrollbar at 600px or 390px.
- The initial `statusOpen` / `sessionsOpen` values are decided once at load with `matchMedia('(min-width: 1080px)')` / `matchMedia('(min-width: 800px)')` (open when wide or when `matchMedia` is unavailable). They are not re-evaluated on resize (accepted, see 7).
Verify `message-list` and `composer` still get their flex sizing (`.messages { flex: 1; min-height: 0 }`).

### 3.3 Header (in `.chat`, replaces `.topbar`)
`<header class="chat-header">`: `display:flex; align-items:center; gap: 8px; padding: 12px 16px 12px 24px; border-bottom: 1px solid var(--svy-line2)`. Structure with explicit extension points for follow-ups:

1. `<div class="header-leading">` — empty slot for the back-to-parent button (SVY-21847/48). Collapses to zero width when empty (`:empty { display:none }` or not rendered).
2. `<div class="header-title">` (`flex: 1 1 auto; min-width: 0`) — slot for title (15px/600, ellipsis) over subtitle (12px mono, `--svy-muted`, ellipsis). In this case it renders the active session title if the store already exposes one cheaply, otherwise a static "Servoy AI". Title/subtitle content logic belongs to SVY-21846/47. Must not break layout when empty.
3. `<div class="header-actions">` (`margin-left: auto; display:flex; gap: 8px`): theme button, then status button.

Slots are plain marked containers in `app.component.html` (comment-free; identified by class names), not content projection, since the header is part of the root component.

**Theme button** (40x40, radius 10, transparent, `color: var(--svy-fg2)`, hover `background: var(--svy-line2)`):
- `type="button"`, `aria-label` and `title` = "Switch to light mode" when dark (sun icon), "Switch to dark mode" when light (moon icon). Inline SVG, 18px, stroke 1.8, round caps, `aria-hidden="true"`.
- Click calls `theme.toggle()`.

**Status button** (40x40, same styling, `position: relative`):
- `type="button"`, `aria-label="Status panel"`, `title="Status"`, `aria-expanded="{{statusOpen()}}"`, `aria-controls="status-panel"`. Icon: panel-right (rect x3 y4 18x16 rx2 + divider at x=15).
- 8px dot at top 8px / right 8px, ring `0 0 0 2px var(--svy-surface)`. Colour from the existing overall state of the status panel (idle grey, ok green `#1F8A4C`, warn `--svy-accent`, error `--svy-danger`). See 3.4 for how the shell reads it. Dot meaning beyond that is SVY-21852.
- Click calls `toggleStatus()`.

### 3.4 Status area and shell state
- `AppComponent` gets `readonly statusOpen = signal(<wide viewport>)` (default open on wide viewports, closed below 1080px where the area is an overlay, see 3.2) and `toggleStatus()` / `closeStatus()` methods that write it (event handlers only). It is the single source of truth that SVY-21852 consumes.
- `StatusPanelComponent` loses `open`, `toggle()`, the `.status-toggle` button and the `.popover` wrapper (and the `role="dialog"`). It keeps all data signals, `overall()`, polling, `refresh()` and the body sections. Its template becomes a plain panel: a head row (title "Status", Refresh button, and a close button `aria-label="Close status"`, 36x36, radius 8, X icon 16px, emitting a new `closed` output that the shell wires to `closeStatus()`), followed by the existing body sections unchanged.
- The component stays **instantiated for the lifetime of the shell** so polling continues and the header dot always has a live value: the aside is always in the DOM and is hidden with the `hidden` attribute (styled `display: none`) while `statusOpen()` is false. The shell reads the dot via `viewChild(StatusPanelComponent)` -> `overall()` in a `computed`. (Rationale: the design's `@if` would destroy the component and its polling; the dot would go stale.) The hidden aside is not in the accessibility tree, which matches "closed".
- When the panel transitions closed -> open the shell calls `refresh()` on it once (preserves the old "toggling open re-fetches" behaviour).
- Keyboard: Escape while focus is inside the status area closes it (optional nicety; skip if it conflicts with the composer). Closing via the panel close button returns focus to the status header button.

### 3.5 ThemeService
- Add `readonly theme = signal<Theme>('light')`. `applyResolved()` sets the signal and calls `apply()`. Add `set(theme)` (updates signal + `apply()`) and `toggle()` (flips via `set`). `resolve()` and `?darkmode=` parsing are unchanged (`''`/`'true'`/`'1'` -> dark; anything else -> light; absent -> light).
- Because `ThemeService` is `providedIn: 'root'` and `main.ts` creates a separate `new ThemeService()` before bootstrap, the DI instance must start with the same value: initialise the signal in the constructor from `resolve()` (so both instances agree) and have `AppComponent` use `inject(ThemeService)`. The `data-theme` attribute is already set by `main.ts`, so no flash. No URL rewrite, no reload, no storage.
- The toggle is a pure attribute swap: no component is re-created, so conversation state and composer text are preserved.
- Writes only from event handlers.

### 3.6 Tokens and palettes (`styles.scss`)
Add the design tokens under the `--svy-` prefix, light in `:root`, dark in `:root[data-theme='dark']`, values exactly as in the triage "Design reference" tables:

`--svy-bg`, `--svy-surface`, `--svy-fg`, `--svy-fg2`, `--svy-muted`, `--svy-line`, `--svy-line2`, `--svy-line3`, `--svy-soft`, `--svy-inv`, `--svy-on-inv`, `--svy-chip-bg`, `--svy-chip-fg`, `--svy-acc-fg`, `--svy-acc-fg2`, `--svy-acc-soft`, `--svy-acc-soft2`, `--svy-acc-line`, `--svy-blue-soft`, `--svy-blue-fg`, `--svy-blue-line`, `--svy-blue-bg`, `--svy-ok-fg`, `--svy-idle`, `--svy-ph1`, `--svy-ph2`, `--svy-drop`.

Non-themed constants (defined once in `:root`): `--svy-accent: #F5A623` (fills, borders, focus rings), `--svy-accent-strong: #E39A1C` (hover/focus border, spinner), `--svy-accent-wait: #B86B00`, `--svy-ok: #1F8A4C`, `--svy-focus-ring: rgba(245,166,35,.18)`.

Legacy aliases (re-pointed, no edit to the consuming components): `--svy-border: var(--svy-line)`, `--svy-hover: var(--svy-line2)`, `--svy-hover-strong: var(--svy-line3)`, `--svy-active: var(--svy-acc-soft)`, `--svy-surface-hover: var(--svy-soft)`. `--svy-bg`, `--svy-surface`, `--svy-fg`, `--svy-muted` take the new values directly. Keep `--svy-danger`, `--svy-stop`, `--svy-accent-hover`, `--svy-code-bg`, `--svy-mono` (mono stack now starts with the bundled JetBrains Mono). `--svy-pre-bg` / `--svy-pre-fg` and the highlight.js palettes are kept; the developer must check their contrast against the new `--svy-surface` and adjust only if a pair is below 4.5:1.

**Accent as text -> `var(--svy-acc-fg)`** (hover `--svy-acc-fg2`), replacing `var(--svy-accent, #f5a623)` used as `color` at:
`attachment-bar.component.scss:36`, `composer.component.scss:73`, `form-card.component.scss:153`, `markdown-renderer.component.scss:83,89`, `message-list.component.scss:27`, `message-item.component.scss:101,189,247,277`, `session-list.component.scss:20`, `status-panel.component.scss:98`. Re-verify each line (numbers may have drifted) with a grep on `color:.*--svy-accent`. Accent as fill/border/ring (listed in the triage) stays bright orange. Any fill that carries text (`composer` send button `:85`, `session-list` `:18,25`) must use dark text `#1C1B19` (>= 7:1). Global `a { color: var(--svy-acc-fg) } a:hover { color: var(--svy-acc-fg2) }`.

### 3.7 Fonts
- Add woff2 files for Inter (400/500/600) and JetBrains Mono (400/500) under `webui/src/assets/fonts/` (latin subset is sufficient), declared with `@font-face` in `styles.scss` using `font-display: swap` and relative URLs resolved against `baseHref` (`/servoy_ai/`). `angular.json` `assets` is currently `[]`, so add the assets glob (or place fonts under `public/` and add the standard `public` assets entry) so `ng build` emits them to `webui-dist`. Verify `OpencodeChatServlet` serves `.woff2` with a correct content type (check the servlet's mime handling; fix only if missing) and that hashed-asset caching does not break unhashed font names.
- Add `src/assets/fonts/LICENSE-Inter.txt` and `LICENSE-JetBrainsMono.txt` (SIL OFL 1.1 with the copyright notices) and mention the attribution in a short `src/assets/fonts/README.md`.
- `body { font-family: Inter, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; font-size: 14px }`. Mono token: `'JetBrains Mono', Consolas, 'Courier New', monospace`. If the font files fail to load the fallbacks apply, no layout dependence.
- Space Grotesk is NOT bundled here (SVY-21846).

### 3.8 Accessibility
- All header buttons have `aria-label`, `type="button"`, 40x40 target.
- Add a global visible keyboard focus indicator: `:focus-visible { outline: 2px solid var(--svy-accent-strong); outline-offset: 2px }` for `button, a, [role='button'], [tabindex]`. `textarea`/`input` keep `outline: none` only where the container shows `:focus-within` ring (`0 0 0 4px var(--svy-focus-ring)` plus border `--svy-accent-strong`); otherwise the focus-visible outline applies. Do not remove existing component outlines (`composer.component.scss:44`, `form-card.component.scss:97`, `session-list.component.scss:164`, markdown outlines `:76-77`) unless replaced by an equivalent visible indicator.
- Status button `aria-expanded` reflects state; `aria-controls="status-panel"` matches the aside id.
- Colour contrast: all text pairs from the triage table meet 4.5:1; the spec's new text-colour usage must not introduce pairs below that.

### 3.9 Feedback round (Rene van Veen, Jira comment 235122)

**Chat width.** The 860px cap was repeated in `message-list`, `composer` (`.input-row`, `.command-menu`, `.disabled-hint`), `form-card`, `welcome` and `agent-view` scss. It is now one token, `--svy-chat-max-w` (1100px, in `styles.scss`), used everywhere, so messages, composer, forms, welcome and agent view stay aligned and the column grows with the window up to that readable-line cap (the design uses 800px; 1100px was chosen because the chat area itself is only ~880px at 1440px with both side panels docked, so the cap only bites on very wide windows). `.chat` itself was always flexible. Sidebar (260px) and status panel (300 to 340px) keep their design widths.

**Reasoning is not shown.** Root cause of the "empty Reasoning labels": they were not empty. The collapsed row hid its caret (`opacity: 0` until hover), so a row with text looked like a dead label. Live data checked: no blank reasoning parts reach the renderer (`isBlankReasoningText` already drops `...`/whitespace). Decision: hide reasoning altogether instead of fixing the caret:
- `isRenderablePart` no longer includes reasoning, so the chat shows only text, tool rows, agent cards and files. The agent view (`agent-view.ts`) no longer emits reasoning steps. The part data is untouched: `session-export.ts` still exports reasoning parts and file refs still ignore them.
- While the model works a short "Thinking..." line (`svy-thinking`, muted, gentle opacity pulse, no animation under `prefers-reduced-motion`) replaces the old three dots. It shows only on the newest assistant message, while the session turn is busy, not completed, without error, and only while the last thing the model did is thinking (or nothing yet): it disappears as soon as an answer or tool row appears, when the turn completes, is stopped or fails.
- A finished turn that only contained reasoning no longer shows "No response was returned" (the model did respond, we just hide it); a running reasoning-only message survives a reload (`isRenderableMessage`) so the line still shows; a message with nothing visible collapses (`nothing-to-show`).

## 4. Implementation plan

1. `webui/src/styles.scss`: add the design tokens (light/dark), the constants, legacy aliases, width CSS variables, `@font-face` rules, body font, global link colours, `:focus-visible` rule.
2. Fonts: add woff2 files, licenses and README under `webui/src/assets/fonts/`; update `angular.json` `assets`; confirm the servlet serves `.woff2` correctly (and fix only if needed).
3. `services/theme.service.ts`: `theme` signal initialised from `resolve()`, `set()`, `toggle()`, `applyResolved()` updating the signal. Keep `?darkmode=` parsing as is. `main.ts` unchanged apart from nothing (still calls `applyResolved()` before bootstrap).
4. `components/status-panel.component.{ts,html,scss}`: remove `open`/`toggle`, the toggle button and the popover wrapper; add `closed` output and a close button in the head; restyle as a docked panel body (full height of its aside, borders from tokens). Keep all data logic untouched.
5. `app.component.{ts,html,scss}`: new flex layout, header with the three slots, theme and status buttons (inline SVG), `statusOpen` signal, `toggleStatus()` / `closeStatus()`, `viewChild(StatusPanelComponent)` + `statusDot` computed, refresh on reopen, hidden aside wiring, inject `ThemeService`. Remove `.topbar`.
6. Switch accent-as-text usages (3.6 list) to `--svy-acc-fg` in the component SCSS files; ensure text on accent fills is `#1C1B19`.
7. Check `message-list`, `composer`, `form-card`, `session-list` at 600px, 1000px and 1440px for horizontal overflow; adjust only the shell if a child forces a fixed width.
8. Tests (Vitest API: `describe/it/expect/vi/beforeEach`, `vi.fn()` / `vi.spyOn()`, TestBed, `provideZonelessChangeDetection`; guard NG0600 with `expect(() => fixture.detectChanges()).not.toThrow()`):
   - New `services/theme.service.spec.ts`: `resolve()` for `?darkmode=` absent / `''` / `true` / `1` / `false` / `0` / garbage; `apply()` sets `data-theme`; `applyResolved()` sets signal and attribute; `set()` and `toggle()` flip signal and attribute; toggle does not touch `window.location` or storage (spy on `history.replaceState` and `localStorage.setItem` not called); signal initial value matches `resolve()`. Reset `data-theme` and URL between tests (use `history.replaceState` to set the query).
   - New `app.component.spec.ts` (mock `ChatStore` with signals and `vi.fn()` methods, mock `StatusService` to return `of(...)`; provide stubs so child components render): three areas present by default (sidebar, main, `#status-panel`); status button `aria-expanded="true"`, `aria-controls="status-panel"`; clicking it hides the panel (`hidden`) and flips `aria-expanded` to false; clicking again re-shows it and triggers a status refresh; panel close button closes it; theme button label is "Switch to light mode" in dark / "Switch to dark mode" in light and clicking toggles `data-theme` on `document.documentElement` and the label; composer draft text and message list DOM (same element instance) survive a theme toggle; header slots (`.header-leading`, `.header-title`, `.header-actions`) exist and order is theme button then status button; no `.topbar`.
   - Update `status-panel.component.spec.ts`: remove/replace the "toggling open re-fetches" test with "refresh() re-fetches all three endpoints"; add "emits `closed` when the close button is clicked"; assert no `.status-toggle` and no `.popover`/`role=dialog`; all data-logic tests (overall states, sorting, providers, loadError) stay unchanged and green.
9. Verification (from `webui/`): `npm test`, `npm run build` (production, hashed), lint if configured. Manual/visual: with `ng build --watch --configuration development` and the live app at `http://127.0.0.1:8183/servoy_ai/`, check light and dark (`?darkmode=true`), toggle at runtime, panel open/close, and 600px width (no horizontal scroll, stacked, page scrolls vertically). Before committing run the production build so `webui-dist` is not left as a dev bundle.

## 5. Acceptance criteria
- [ ] The app shows three areas: sessions | chat | status panel; the status panel is open by default.
- [ ] A header in the chat area shows, right-aligned, a theme button followed by a status button; left side has empty-safe slots for a back button and a title/subtitle.
- [ ] The status button toggles the docked panel; the panel's own close button also closes it; closing returns its width to the chat; `aria-expanded` and `aria-controls="status-panel"` are correct; reopening refreshes the status data.
- [ ] The status panel body is the existing Server / Providers / MCP content, now docked (no popover, no floating toggle); the header dot reflects the existing overall status (idle/ok/warn/error) even while the panel is closed.
- [ ] The app starts in the theme given by `?darkmode=` (`''`, `true`, `1` -> dark; otherwise light) with no light-to-dark flash.
- [ ] The theme button flips light/dark at runtime; label and icon (sun when dark, moon when light) update; no page reload, no URL change, nothing persisted; conversation and composer text are preserved across the toggle.
- [ ] New light and dark palettes from the design are in place as `--svy-*` tokens; legacy tokens are aliased so existing components pick up the new look.
- [ ] Orange used as text is the darker `--svy-acc-fg` token everywhere listed in 3.6 and meets 4.5:1 in both themes; text on bright-orange fills is dark (>= 4.5:1).
- [ ] Inter and JetBrains Mono are bundled (with OFL license files) and served by the servlet; the UI falls back to system fonts if they fail to load.
- [ ] A visible `:focus-visible` indicator exists for all buttons/links reachable by keyboard.
- [ ] The chat content width follows the window up to `--svy-chat-max-w` (1100px); messages, composer, forms, welcome and agent view share it.
- [ ] Reasoning is never rendered in the chat or the agent view (no "Reasoning" label or text); a "Thinking..." line is shown only while the model is working and disappears when an answer or tool row shows or the turn ends; reasoning data is still in the session export.
- [ ] The sessions area can be collapsed/reopened with a header button; docked (pushing the chat) from 800px, a closed-by-default left overlay with scrim below 800px.
- [ ] At 600px viewport width there is no horizontal scrollbar and no page-level scrollbar (only the message list scrolls); views never stack vertically; below 1080px the status panel is a closed-by-default overlay opened with the header button (it does not stack under the chat); at >= 1080px the three areas sit side by side with the chat scrolling internally.
- [ ] `statusOpen` lives in the shell, and the header slots, width CSS variables and tokens exist for the follow-up cases.
- [ ] `theme.service.spec.ts` and `app.component.spec.ts` are added, `status-panel.component.spec.ts` is updated, and `npm test` and `npm run build` pass.

## 6. Out of scope
- Brand block, "New session" button, search, session grouping/restyle (SVY-21846).
- Header title/subtitle content and back-to-parent behaviour (SVY-21847/48), composer/message restyle (SVY-21849), status panel content redesign and the meaning of the status dot (SVY-21852).
- Space Grotesk font.
- Persisting the theme choice, following OS `prefers-color-scheme`, any Java-side change (other than a servlet mime fix for `.woff2` only if it turns out to be missing).
- Intro cards / commands grids from the design.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Persist the manual theme toggle across reopen | Human | resolved: no, in-memory only |
| Bundle Inter / JetBrains Mono / Space Grotesk | Human | resolved: bundle Inter + JetBrains Mono (OFL attribution), system fallback, Space Grotesk deferred to SVY-21846 |
| Status panel content in this case | Human | resolved: keep existing popover body, re-hosted docked; data logic untouched |
| Status panel default open/closed | Human | resolved: open on wide viewports; closed overlay below 1080px (human decision after visual review: stacking it under the chat made little sense) |
| Re-evaluate the open/closed default when the window is resized across 1080px | Human | deferred: only decided at load; CSS switches overlay/docked live |
| Keep the status component mounted while closed (hidden aside) so the header dot stays live, versus `@if` as in the design | Developer / reviewer | assumption in 3.4; revisit in SVY-21852 if the panel is restructured |
| Do the highlight.js and `--svy-pre-*` palettes pass 4.5:1 on the new surfaces | Developer | open, check during implementation |
| Does `OpencodeChatServlet` serve `.woff2` with the right content type | Developer | open, verify during implementation |

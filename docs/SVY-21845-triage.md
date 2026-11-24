# Triage Report — SVY-21845

**Verdict:** PROCEED

## Reported problem

Part 1 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). Blocks SVY-21846, 21847, 21848, 21849, 21852, so this case is the foundation and must stay strictly scoped.

Today (verified in code):
- `app.component.html` is a 2-column grid (`260px 1fr`, `app.component.scss`): `<aside class="sidebar">` (`svy-session-list`) + `<main class="chat">`. Status is a small `Status` button in `.topbar` that opens an absolutely-positioned 300px popover (`status-panel.component.html/.scss`, `.popover { position: absolute; width: 300px }`).
- `ThemeService` (`services/theme.service.ts`) resolves light/dark only from `?darkmode=` and writes `data-theme` on `<html>`. `main.ts` calls `new ThemeService().applyResolved()` before bootstrap (no flash). There is no runtime toggle and the service is not signal based.
- Colours are `--svy-*` tokens in `src/styles.scss` (light in `:root`, dark in `:root[data-theme='dark']`). Accent is `#f5a623` and is used as TEXT colour in many components (see below). `#f5a623` on white is only 2.03:1 (measured), which fails the 4.5:1 rule. This is the "hard to read on white" complaint.
- No `@media` rules anywhere, no horizontal-overflow handling for narrow widths (fixed 260px sidebar).

## Root-cause assessment

This is a feature/redesign case, not a defect. The "problem" is three gaps in the app shell:

1. Layout: no third area. Status content lives in a popover owned by `StatusPanelComponent` (it holds its own `open` signal, polling and the toggle button), so it cannot be a docked, closable column without moving open/close state up to the shell.
2. Theme: `ThemeService` has no mutable state. `apply()` already does the right DOM work (`data-theme` attribute), so a toggle is a small addition: a `theme` signal plus `toggle()`; the attribute swap is a pure CSS change and keeps the conversation and composer text because no component is re-created.
3. Palette: only the old token set exists. The design uses a different token vocabulary and a darker orange for text.

Accent-as-text usages that must move to the dark-orange text token (`var(--svy-accent, #f5a623)` used as `color`): `attachment-bar.component.scss:36`, `composer.component.scss:73`, `form-card.component.scss:153`, `markdown-renderer.component.scss:83,89`, `message-list.component.scss:27`, `message-item.component.scss:101,189,247,277`, `session-list.component.scss:20`, `status-panel.component.scss:98`. Accent as fill/border/focus ring (can stay the bright orange): composer send button (`:85`), form-card (`:9,68,72,98,141,208,209`), session-list (`:18,25,156`), markdown outline/border (`:76,84,95`), status dots (`status-panel.component.scss:41,56`), app banner (`app.component.scss:41-42`).

Note: `session-list.component.scss:20` / `:25` and `composer.component.scss:85-97` use accent as a button fill with presumably dark text. Phase 3 must check that text/fill pair. The design's send button is `#F5A623` fill with `#1C1B19` text (about 8:1).

## Ticket premise check

The ticket proposes: header toggle on top of `?darkmode=`, a docked closable status area, new palettes. This is sound and matches the code. Checks:

- "Keep `?darkmode=` as the start theme": correct. `main.ts` applies it before bootstrap, keep that. The toggle must only change the in-page state and must NOT rewrite the URL or reload (a reload would lose the open conversation and typed text, violating AC 2).
- Persistence: not requested. Do not persist the manual choice (AC 1 says it follows Developer "on open"). Recommend in-memory only; flag as an open question below.
- "Panel's content is SVY-21852": agree. In this case only the area plus open/close. Risk: the existing `StatusPanelComponent` bundles toggle button + popover. Do not rewrite its data logic (SVY-21852 owns that). Minimal move: keep the component as the content host of the new right column (render the old body, minus the popover wrapper and toggle button) or drop the button/popover chrome and keep a placeholder body. Either way the open state must live in the shell (a signal in `AppComponent` or a small `LayoutService`) so the header button controls it. Decide in the spec; the lowest-risk option keeps the component and its spec (`status-panel.component.spec.ts`, 7.6 KB) working by moving only the shell chrome.
- Ambiguity with SVY-21846..49: this case should deliver the header (title area + theme button + status button) as a slot the follow-ups can extend (session title/subtitle, back-to-parent button for subagent sessions at the left of the header in the design), but not their content.

## Approaches considered

1. Shell-level layout state + CSS tokens (recommended). New small header in `app.component`, signals `statusOpen` (shell) and `theme` (ThemeService), design tokens in `styles.scss`, migrate all component SCSS from old `--svy-*` names to the new ones (or keep old names as aliases of new tokens). Pros: smallest blast radius, no component re-creation on toggle, follow-up cases just consume tokens/slots. Cons: touches many SCSS files for the accent-as-text fix.
2. Keep old token names, only change their values, add a new `--svy-accent-text`. Pros: tiny diff. Cons: the design has about 25 tokens (soft/line2/line3/blue/ok/idle...) that follow-up cases (21846-21852) will need; keeping the old vocabulary makes them map by hand. Compromise (recommended in detail below): ADD the design tokens under the `--svy-*` prefix and re-point the legacy names (`--svy-fg`, `--svy-bg`, `--svy-muted`, `--svy-border`, `--svy-surface`, `--svy-accent`...) to them, so existing components change look without a mass edit, then fix only the text-colour usages listed above.
3. CSS-only toggle via `prefers-color-scheme` or a separate stylesheet. Rejected: AGENTS.md says the app must follow Eclipse, not the OS.
4. No code change. Rejected: the criteria (docked panel, toggle, contrast) are not met today and 5 cases are blocked on it.

## Recommendation

PROCEED with approach 1 (using the alias compromise from 2 for tokens).

Suggested scope for the spec (strictly this case):
- Layout (`app.component.*`): `.app` becomes three areas sessions | chat | status; header inside `.chat` with title area (left), theme toggle button and status button (right), design header order: [back-to-parent slot] [title/subtitle] ... [theme] [status]. Status area is rendered only when open (design uses `@if`), closing returns its width to the chat. The status button has `aria-expanded` / `aria-controls="status-panel"` and a status dot (dot colour/meaning belongs to SVY-21852; here a static or existing `overall()`-based dot is enough, spec decides).
- Responsive: the design has NO `@media` queries. It uses `flex-wrap: wrap` with flex-basis/min-width (see Design Reference). Equivalent behaviour at 600px: sidebar and status panel wrap onto their own rows, chat keeps `flex: 999 1 560px`, `min-width: 0`. AC 4 (no horizontal scroll at 600px) must be tested; note the design main has `min-height: 640px; height: 100vh`, and the current app uses `height: 100vh` with an inner scroll. Stacked layout means the page itself scrolls vertically, so the shell must allow that at narrow widths.
- Theme: `ThemeService` gets a `theme` signal (initialised from `resolve()`), `set(theme)`/`toggle()` that call `apply()`; label "Switch to light mode"/"Switch to dark mode"; sun icon when dark, moon when light. No URL rewrite, no reload, no persistence.
- Palette: add the design tokens (below) for both themes in `styles.scss`; set `body` font to Inter stack; map legacy tokens; switch accent-as-text usages to the dark-orange text token; keep bright orange for fills/borders/rings.
- Fonts: the design uses Inter (UI), JetBrains Mono (code/meta), Space Grotesk (brand heading). The design file embeds woff2 subsets. The Eclipse-embedded browser may be offline, so fonts must be bundled with the webui (or fall back to the system stack). Needs a decision; see questions below.

Open questions for the human (not blocking PROCEED, but the spec must pick a default):
1. Persist the manual toggle (localStorage) across reopen? Default: no (AC 1: follows Developer on open).
2. Bundle Inter / JetBrains Mono / Space Grotesk woff2 files into `webui` (licences: SIL OFL, attribution comment present in design file) or fall back to the system font stack? Default proposal: bundle Inter + JetBrains Mono, Space Grotesk only for the brand mark if the brand block is in scope (it is a sidebar element, likely SVY-21846).
3. Status panel content for this case: keep the current popover body as the panel body (recommended) or an empty placeholder?
4. Should the status panel open or closed by default? Design default `statusPanel: true` (open). Default: open.

## Design reference (extracted from `D:\TEMP\opencode\redesign.html`)

The export is a bundler page; the real markup is the JSON-encoded `__bundler/template` script (decoded copy at `D:\TEMP\opencode\tpl.html`). Styles are mostly inline; theme tokens are two CSS rules keyed on `data-theme`. The design host sets defaults via props: `theme` light, `statusPanel` true, preview 1440x960.

### Palette: light (`[data-theme=light]`, `color-scheme: light`)

| Token | Value | Use |
|---|---|---|
| --bg | #F7F6F3 | page background, sunken blocks |
| --surface | #FFFFFF | main/chat, status panel, cards, composer |
| --fg | #1C1B19 | primary text |
| --fg2 | #4A4740 | secondary text, icon buttons |
| --muted | #6B675E | meta text |
| --line | #E6E3DC | borders (sidebar/status separators, cards) |
| --line2 | #EFEDE8 | header bottom border, hover bg (.sess/.ghost) |
| --line3 | #DAD6CD | composer border, dashed borders |
| --soft | #F2F0EB | code/chip backgrounds, .row hover |
| --inv | #1C1B19 | "New session" button bg |
| --on-inv | #FFFFFF | text on --inv |
| --chip-bg / --chip-fg | #3A3834 / #F5B94A | |
| --acc-fg | #9A5B00 | ORANGE TEXT (darker shade) |
| --acc-fg2 | #6E4100 | stronger orange text, hover |
| --acc-soft | #FFF8EC | orange tint bg (hover, selected) |
| --acc-soft2 | #FFFBF3 | |
| --acc-line | #F2D9A6 | orange tint border |
| --blue-soft / --blue-fg / --blue-line / --blue-bg | #E7EEF5 / #2C4A66 / #E3E8EE / #FAFBFC | agent/subagent tags |
| --ok-fg | #145C33 | |
| --idle | #CFCAC0 | idle dot |
| --ph1 / --ph2 | #E3DFD7 / #D8D4CB | placeholders |
| --drop | rgba(255,248,236,.95) | drag-drop overlay |

### Palette: dark (`[data-theme=dark]`, `color-scheme: dark`)

--bg #141413; --surface #1C1B19; --fg #ECEAE4; --fg2 #C9C5BC; --muted #A39E93; --line #34322E; --line2 #2A2926; --line3 #403D38; --soft #262522; --inv #ECEAE4; --on-inv #1C1B19; --chip-bg #D8D4CB; --chip-fg #6E4100; --acc-fg #F5B94A; --acc-fg2 #F7C873; --acc-soft #33260F; --acc-soft2 #241C10; --acc-line #5C4318; --blue-soft #22303D; --blue-fg #A9C8E4; --blue-line #2F3A45; --blue-bg #1F2328; --ok-fg #86D9A6; --idle #5A564F; --ph1 #3A3834; --ph2 #4A4740; --drop rgba(51,38,15,.95).

### Fixed (non-themed) colours in the design

Bright orange accent: `#F5A623` (send button fill when enabled, brand icon stroke, answer button, active mode pill); `#E39A1C` (hover/focus border, spinner, running dot, usage ring and bars, drag outline); `#B86B00` (waiting dot); success green `#1F8A4C` (status dot, `box-shadow: 0 0 0 3px rgba(31,138,76,.2)`, header status dot ring `0 0 0 2px var(--surface)`); focus ring `0 0 0 4px rgba(245,166,35,.18)` on `.composer:focus-within, .search:focus-within`; send button disabled bg `var(--line2)`, text `#1C1B19`; composer shadow `0 1px 2px rgba(28,27,25,.04), 0 12px 32px -12px rgba(28,27,25,.12)`.

Global: `a{color:var(--acc-fg)} a:hover{color:var(--acc-fg2)}`; `button{font:inherit;cursor:pointer}`; `textarea,input{font:inherit}` with `:focus{outline:none}` (the design replaces it with the focus ring on the container; keep a visible focus indicator for keyboard use, the design's own buttons have none, so the spec must add `:focus-visible` outlines for accessibility); hover: `.sess:hover,.ghost:hover{background:var(--line2)}`, `.row:hover{background:var(--soft)}`, `.chip/.file:hover{border-color:#E39A1C;background:var(--acc-soft)}`, `.cmd:hover{background:var(--acc-soft)}`, `.open:hover{background:var(--blue-soft)}`.

### Fonts

- UI: `Inter, system-ui, sans-serif` (weights 400/500/600). Base body 14px (sidebar items 13px, meta 11-12px, composer textarea 15px/1.5).
- Mono: `'JetBrains Mono', monospace` for subtitle/meta, commands, tool names, code (11-13px).
- Display: `'Space Grotesk', sans-serif`, 600, `letter-spacing: -0.01em` for the brand ("Servoy" + orange "AI", 17px), 48px/1.05 `-0.03em` for the welcome heading (intro; later cases).
- Section labels: 11px, 600, uppercase, `letter-spacing: .08em`, `--muted`.

### Layout and dimensions

Root: `display:flex; flex-wrap:wrap; min-height:100vh; background:var(--bg); color:var(--fg)`; designed at 1440x960.

- Sidebar `<aside>`: `flex: 1 1 260px; max-width: 288px; min-width: 240px; border-right: 1px solid var(--line); padding: 16px 12px; gap: 12px; display:flex; column`. Contents (follow-up cases): brand row (30px rounded-9 black square with orange icon + brand text), "New session" button (44px high, radius 12, `--inv` bg), search (40px, radius 10), grouped session list (item min-height 40px radius 10; subagent rows 32px).
- Main `<main>`: `flex: 999 1 560px; min-width: 0; background: var(--surface); height: 100vh; min-height: 640px; display:flex; column`.
- Header: `display:flex; align-items:center; gap:8px; padding: 12px 16px 12px 24px; border-bottom: 1px solid var(--line2)`. Left: optional back button (36x36, margin-left -8px, only inside a subagent session), then title (15px/600, ellipsis) over subtitle (12px mono `--muted`, ellipsis). Right (starting at `margin-left:auto`): theme toggle then status toggle, both 40x40, radius 10, transparent, `--fg2`, `.ghost` hover; SVG icons 18px, stroke 1.8, round caps. Theme button: `aria-label`/`title` = "Switch to light mode" (when dark, sun icon) / "Switch to dark mode" (when light, moon icon). Status button: sidebar-panel icon (rect 3,4 18x16 rx2 + divider at x=15), `aria-expanded`, `aria-controls="status-panel"`, `aria-label="Status panel"`, `title="Status"`, with an 8px green dot at top/right 8px ringed by `--surface`.
- Content section: `flex:1; overflow:auto; padding: 32px 24px 24px`, inner column `max-width: 800px; margin: 0 auto; gap: 22px`.
- Composer: radius 20, border 1px `--line3`, padding `12px 10px 8px 14px`, buttons 40x40 (attach, "/", context ring "42%", send radius 12).
- Status panel `<aside id="status-panel" aria-label="Status">`: `flex: 1 1 300px; max-width: 340px; min-width: 280px; background: var(--surface); border-left: 1px solid var(--line); overflow:auto; column`. Panel header: padding `14px 12px 14px 16px`, bottom border `--line2`, a 9px green dot, title "Ready" (14px/600) + subtitle (12px `--muted`), and a close button (36x36 radius 8, `aria-label="Close status"`, X icon 16px) at `margin-left:auto`. Sections padded 16px with `--line2` separators (content = SVY-21852).

### Breakpoints / responsive behaviour

The design defines no `@media` breakpoints. Responsiveness comes from `flex-wrap` and the `min-width`/`flex-basis` values above: sidebar min 240, main basis 560 (grow 999, so it takes all spare space), status min 280. Main (560) plus sidebar (240) is 800px, so below that the sidebar wraps above the chat; at 600px sidebar, chat and status each take a full row (stacked), nothing is squeezed and no horizontal scrollbar appears (requires `min-width: 0` on main and the fixed-width children to be shrinkable; verify the 260px grid column in the current app is replaced by the flex scheme). The 3-column intro cards grid (`repeat(3,minmax(0,1fr))`) and 2-column commands grid are intro content for later cases.

### Contrast measured (WCAG, computed from the palette)

| Pair | Ratio |
|---|---|
| light --acc-fg #9A5B00 on --surface / --bg / --acc-soft | 5.43 / 5.02 / 5.14 |
| light --muted #6B675E on --surface / --bg / --soft | 5.63 / 5.21 / 4.95 |
| light --fg2 on surface | 9.27 |
| light --blue-fg on --blue-soft | 7.88 |
| light --ok-fg on surface | 8.05 |
| light --acc-fg2 on --acc-soft | 8.22 |
| dark --acc-fg #F5B94A on --surface / --bg / --acc-soft | 9.77 / 10.46 / 8.37 |
| dark --muted on --surface / --bg / --soft | 6.45 / 6.91 / 5.74 |
| dark --blue-fg on --blue-soft | 7.75 |
| dark --ok-fg on surface | 10.24 |
| dark --fg on surface | 14.31 |
| #1C1B19 text on #E39A1C / #F5A623 fill | 7.30 / about 8 |
| OLD accent #F5A623 as text on white | 2.03 (fails) |

All design text pairs checked pass 4.5:1. The existing `highlight.js` light/dark palettes and `--svy-pre-*` code-block colours are not part of the design and need a quick contrast check in phase 3 against the new surface colours (not re-checked here). The old light `--svy-muted #6b7075` is not in the new set; the design `--muted` replaces it.

## Implementation notes for later phases

- Token strategy: add design tokens (e.g. `--svy-acc-fg`, `--svy-acc-soft`, `--svy-line2`, `--svy-soft`, `--svy-inv`, `--svy-blue-*`, `--svy-ok-fg`, `--svy-idle`) in `styles.scss` under `:root` / `:root[data-theme='dark']` for both themes and re-point the legacy `--svy-bg/--svy-surface/--svy-fg/--svy-muted/--svy-border/--svy-hover/--svy-active/--svy-accent` to the design values so existing components change colour without edits. The fixed brand oranges stay constants (`#F5A623`, `#E39A1C`).
- Component SCSS uses fallbacks like `var(--svy-accent, #f5a623)`; the accent-as-text list above must switch to the new text token.
- Specs that depend on layout: `status-panel.component.spec.ts` (popover/toggle behaviour will change), possible `app.component` spec is absent (no `app.component.spec.ts` exists), so add one for header toggle, panel open/close and theme toggle; add `theme.service.spec.ts` (none exists). Check the `?darkmode=` parsing keeps working (`'' | 'true' | '1'` is dark).
- Follow-up friendliness: expose header as a template area with named content slots / keep title and back-button slots; keep `statusOpen` in a shell signal that SVY-21852 can consume; keep sidebar width variables (`--svy-sidebar-w`) and status width variables so later cases do not touch layout.
- AGENTS.md says the webui runs zoneless/OnPush/signals; the theme signal must be written from event handlers only (never during render).
- Coverage and build: `npm run build` and `npm test` from `webui/`; nothing Java-side needs to change (the servlet only serves `webui-dist`).

## Git history findings

- `app.component.*`, `theme.service.ts`, `styles.scss` were last touched by the SVY-21461 / SVY-21488 chat work (`4718305`, `44ae5f6`, `5070215`, `a6a2194`, `dba3888`). Nothing there is an intentional decision against a docked status panel or a toggle; `ThemeService`'s doc comment records one intentional decision to keep: do NOT consult OS `prefers-color-scheme`.
- Intentional behaviour to preserve: no light-to-dark flash (theme applied in `main.ts` before bootstrap).
- No earlier spec for this key in `docs/`. Working tree has unrelated local changes (`opencode.json`, a tests properties file, an untracked extracted-server folder), which must stay out of the commit.

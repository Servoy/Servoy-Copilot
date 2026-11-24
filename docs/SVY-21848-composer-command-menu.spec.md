# Spec: SVY-21848 — Composer command menu, image thumbnails and drag-over highlight

## 1. Goal
Part 4 of 9 of the Servoy AI chat redesign (design: attachment `ServoyAI Redesign.html` on SVY-21845). Three front-end changes in the chat composer: (1) typing `/` as the first character opens a menu of the agent pack's `/svy*` commands so users no longer need to know them by heart; (2) attached images show as thumbnails with a remove button instead of name chips; (3) dragging files over the input shows a "Drop images to attach" overlay. Enter / Shift+Enter behaviour is unchanged. The command logic is reusable by SVY-21847 (welcome starters).

## 2. Background
Findings from `docs/SVY-21848-triage.md` are authoritative.

### 2.1 Current code (`bundles/com.servoy.eclipse.opencode/webui/src/app/`)
- `components/composer.component.{ts,html,scss}`: controlled component. The draft (`text`, `attachments`) is owned by `ChatStore` and written through `draftChange` (`emitDraft`). `onKeydown` sends on Enter without Shift. `onDrop` / `onDragOver` only `preventDefault()`. `addFile` / `readImageFile` build `Attachment { filename, mime, url }`; images get a `data:` URL, other files `url = file.name`. The `+` button opens the file picker.
- `components/attachment-bar.component.{ts,html,scss}`: renders every attachment as a text chip, emits `remove(index)`; exports `Attachment`.
- No command lookup exists. `opencode-api.service.ts` unwraps V2 envelopes (`{ data }` / `{ location, data }`) in each wrapper.

### 2.2 `/command` endpoint (verified live on opencode 2.4.1)
`GET rest_api/command` -> BFF -> opencode `/api/command`, HTTP 200, `{ location, data: [{ name, description }] }`. Only `name` and `description` are returned (no source/template). 24 commands live: built-in/MCP ones (`init review goal automation call-graph definition find index peek pr-impact reindex search status visualize`) and 10 agent-pack ones, all named `svy*` (`svyCreateE2E svyCreateFormTest svyCreateJSUnit svyFixStacktrace svyFixTicket svyImplement svyModernize svyOptimize svyReviewTicket svySecurityReview`). Agent-pack commands are therefore identified by name prefix `svy`. Descriptions can contain a double space (lost dash); collapse whitespace for display. The endpoint may be unavailable while opencode starts (BFF readiness gate) or may fail: the list must then be empty without breaking the composer.

### 2.3 Design reference
Menu item: `/{{name}}` (mono 13px, min-width 150px, accent colour) + description (13px, `--fg2`/muted token). Open rule: text starts with `/` and contains no space. Filter: case-insensitive substring on name. Pick sets text to `'/' + name + ' '`. Empty state: `No command matches - Enter sends it as text.` Drag overlay: upload icon + `Drop images to attach`. The design's mock list contains non-svy commands (`new/compact/status`); the ticket excludes them, so only `svy*` are listed.

### 2.4 Parallel work
Another session works on SVY-21850 (message-list, app.component, maybe a read-only input bar). File changes here are restricted (see 4.0).

## 3. Design

### 3.1 API + model
- `models/opencode.models.ts` (additive, at END of file): `export interface CommandInfo { name: string; description?: string; }`.
- `OpencodeApiService.listCommands(): Observable<CommandInfo[]>`: `GET ${base}/command`; accepts `{ data: CommandInfo[] }` or a bare `CommandInfo[]`; anything else (null, missing data, non-array) yields `[]`. Add the method adjacent to the other list wrappers; do not reorder existing code. No catchError needed in the wrapper (the catalog handles errors), but entries without a string `name` are dropped.

### 3.2 Pure helpers — `services/svy-commands.ts`
```ts
export interface SvyCommand { name: string; description: string; }
export function isSvyCommand(name: string): boolean            // case-sensitive startsWith('svy')
export function toSvyCommands(list: CommandInfo[]): SvyCommand[] // filter isSvyCommand, description collapsed (/\s+/g -> ' ', trimmed, '' when missing), order preserved
export function filterSvyCommands(list: SvyCommand[], query: string): SvyCommand[] // case-insensitive substring on name; empty query -> all
export function slashQuery(text: string): string | null          // text starts with '/' and has no whitespace -> text.slice(1); else null ('/' -> '')
export function insertionText(command: SvyCommand): string       // '/' + name + ' '
```
Pure, no Angular imports, so SVY-21847 can reuse them.

### 3.3 `CommandCatalogService` — `services/command-catalog.service.ts`
`@Injectable({ providedIn: 'root' })`, `inject(OpencodeApiService)`.
- `private readonly _commands = signal<SvyCommand[]>([])`; `readonly commands = this._commands.asReadonly()`.
- `load()`: idempotent and lazy. First call starts one request (guard flag set before subscribing; concurrent calls do nothing). On success `_commands.set(toSvyCommands(list))`; on error the list stays `[]`. After a failure `load()` may retry on the next call (flag reset on error) so a start-up race recovers; after success it never refetches.
- Composer calls `load()` when the menu first opens (and may call it from `ngOnInit`; either is acceptable, but must be idempotent).

### 3.4 Command menu component — `components/command-menu.component.{ts,html,scss}` (`svy-command-menu`)
Presentational, standalone, OnPush. Inputs: `items: SvyCommand[]`, `activeIndex: number`, `idPrefix: string` (default `svy-cmd`). Outputs: `pick: SvyCommand`, `hover: number`. Template: `<div role="listbox" aria-label="Commands">` with `role="option"` rows, id `{{idPrefix}}-{{i}}`, `[attr.aria-selected]="i === activeIndex()"`, class `active`; row = `<span class="name">/{{name}}</span><span class="desc">{{description}}</span>`. When `items` is empty render the empty state `No command matches - Enter sends it as text.` (non-option element, `role="status"`). `mousedown` on a row calls `preventDefault()` (keeps textarea focus) and emits `pick`; `mouseenter` emits `hover(i)`. Scrollable (max-height ~240px). Active row scrolls into view when `activeIndex` changes (effect using `scrollIntoView({ block: 'nearest' })`, guarded for jsdom where it is undefined). SCSS uses existing `--svy-*` tokens only (same surface/border/radius/accent/muted tokens as the composer and session-list); no new tokens.

### 3.5 Composer changes
UI state only:
- `activeIndex = signal(0)`, `dragging = signal(false)`, private `dragDepth = 0`.
- Derived: `query = computed(() => slashQuery(this.text()))`; `menuOpen = computed(() => !disabled() && !streaming-agnostic ... )` defined as: not disabled AND `query() !== null` AND not dismissed. Dismissal: `dismissed = signal(false)` set by Escape, reset on any input change. `items = computed(() => filterSvyCommands(catalog.commands(), query() ?? ''))`. Draft is derived from `text()` so a session switch (new draft) naturally closes/updates the menu. (The `/` button and `slashOpen` were dropped; the hint is in the textarea placeholder `Ask Servoy AI… Type "/" for commands`.)
- Close conditions: text contains whitespace or does not start with `/` (query becomes null); `submit()` (reset `activeIndex`); blur does not close it; `disabled()` true (menu hidden via `menuOpen`); Escape.
- `activeIndex` clamped/reset to 0 whenever the item list changes (reset in `onInput`; read through `Math.min` in the template/keydown for safety).
- Keys (`onKeydown`), only when `menuOpen()` and `items().length > 0`: ArrowDown/ArrowUp move `activeIndex` with wrap-around and `preventDefault()`; Enter (without Shift) and Tab pick the active item; Escape closes without changing the text. When the menu is open but `items()` is empty (empty state), ArrowUp/Down are ignored, Enter falls through to the existing send path (sends the text as typed), Escape closes. When the menu is closed, `onKeydown` is exactly as before (Enter sends, Shift+Enter newline). Enter picking must `preventDefault()` and must NOT send.
- `pickCommand(command)`: `emitDraft(insertionText(command), attachments())`, reset `activeIndex`, refocus the textarea, call `autoGrow()`.
- No `/` button (dropped as visually heavy): the textarea placeholder carries the hint `Ask Servoy AI… Type "/" for commands`; the disabled placeholder is unchanged.
- Textarea ARIA: `role="combobox"`, `aria-autocomplete="list"`, `[attr.aria-expanded]="menuOpen()"`, `[attr.aria-controls]="menuOpen() ? 'svy-cmd-menu' : null"`, `[attr.aria-activedescendant]="menuOpen() && items().length ? 'svy-cmd-' + activeIndex() : null"`. The menu host element gets `id="svy-cmd-menu"`.
- The menu is rendered inside `.composer` above the input row (absolutely positioned, `bottom: 100%`, full width; `.composer` gets `position: relative` if not already). `catalog.load()` is called when `query()` first becomes non-null (on `onInput`) or on `ngOnInit` for a restored `/` draft.
- Drag: `onDragEnter(event)`: if `event.dataTransfer?.types` includes `'Files'`, `preventDefault()`, `dragDepth++`, `dragging.set(true)`. `onDragOver`: `preventDefault()` always (existing behaviour); sets `dragging` true only for Files. `onDragLeave`: `dragDepth = Math.max(0, dragDepth - 1)`; when 0 `dragging.set(false)`. `onDrop`: `preventDefault()`, `dragDepth = 0`, `dragging.set(false)`, then existing file handling. The disabled composer ignores drag highlighting (no overlay). Template binds `(dragenter)`, `(dragleave)` on `.composer`; overlay `@if (dragging())` renders `<div class="drop-overlay" aria-hidden="true">` with an inline upload SVG icon and `Drop images to attach`, `pointer-events: none`, absolutely positioned over the composer with a dashed accent border using existing tokens.
- Attachment bar usage unchanged (`removeAttachment(index)` still receives the original index).

### 3.6 Attachment bar thumbnails
- `attachment-bar.component.ts`: add `isImage(a: Attachment): boolean` = `a.mime.startsWith('image/') && a.url.startsWith('data:')`. Keep `remove` emitting the index into the ORIGINAL `attachments()` array: iterate the full list in the template and branch per item with `@if (isImage(attachment))`, so `$index` is the original index (no pre-filtered array). Keep `attachmentKey`.
- Template: images render `<span class="thumb"><img [src]="attachment.url" [alt]="attachment.filename" [title]="attachment.filename" /><button type="button" class="thumb-remove" (click)="onRemove($index)" [attr.aria-label]="'Remove ' + attachment.filename">x</button></span>`; other files keep the existing chip markup unchanged. Use the same remove glyph character as the existing chip button. No `bypassSecurityTrust*`: Angular allows `data:image/*` in `[src]`.
- SCSS: thumbnail ~56px square, `object-fit: cover`, radius/border from `--svy-*` tokens, remove button overlaid top-right, visible on focus and hover (always visible for touch/keyboard accessibility via `:focus-visible`). Container stays flex-wrap.

### 3.7 Constraints
Single quotes, no comments in new code, `inject()`, `readonly` signals, OnPush, zoneless (never write signals during template rendering, NG0600), existing `--svy-*` tokens only.

## 4. Implementation plan

### 4.0 File boundaries (parallel work with SVY-21850)
ONLY these files may be created or modified (all under `bundles/com.servoy.eclipse.opencode/webui/src/app/`): `components/composer.component.{ts,html,scss,spec.ts}`, `components/attachment-bar.component.{ts,html,scss,spec.ts}`, new `components/command-menu.component.{ts,html,scss,spec.ts}`, new `services/svy-commands.ts` + `.spec.ts`, new `services/command-catalog.service.ts` + `.spec.ts`, `services/opencode-api.service.ts` + `.spec.ts`, `models/opencode.models.ts` (additive, end of file only). Do NOT touch `message-list.*`, `app.component.*`, `chat-store.service.ts`, global styles or tokens. If a change outside this list seems necessary, stop and report it.

### 4.1 Steps (each with its tests)
1. `models/opencode.models.ts`: append `CommandInfo`. `opencode-api.service.ts`: add `listCommands()`. Tests in `opencode-api.service.spec.ts`: GET `rest_api/command`; `{ data: [...] }` unwrapped; `{ location, data }`; bare array; `{}`/null/non-array -> `[]`; entries without string name dropped.
2. `services/svy-commands.ts` + `svy-commands.spec.ts`: `isSvyCommand` (svy prefix true, `status`/`search`/`Svy`/`svg` false); `toSvyCommands` (drops non-svy, preserves order, collapses double space, missing description -> ''); `filterSvyCommands` (case-insensitive substring in the middle of the name, e.g. `fix` matches `svyFixTicket` and `svyFixStacktrace`, empty query returns all, no match returns `[]`); `slashQuery` (`'/'`->`''`, `'/svy'`->`'svy'`, `'/svy x'`->null, `'a/b'`->null, `''`->null, text with newline/tab -> null, leading space -> null); `insertionText`.
3. `services/command-catalog.service.ts` + spec (TestBed, stubbed `OpencodeApiService.listCommands`): initial empty; `load()` populates only svy commands; second `load()` after success does not call the API again; concurrent `load()` calls issue one request; error -> stays empty and a later `load()` retries.
4. `components/command-menu.component.*` + spec: renders one `role="option"` per item with `/name` and description; `aria-selected` only on active; `id` pattern; `pick` emitted on mousedown with default prevented; `hover` on mouseenter; empty items renders the empty-state text and zero options; `detectChanges` does not throw.
5. `components/attachment-bar.component.*` + spec: image attachment with `data:` URL renders `<img>` with `src`/`alt` and no chip; non-image and non-data image (name reference) stay chips; mixed list `[file, image, file, image]` clicking the remove button of the second image emits index 3 (original index) and a chip remove emits its original index; thumbnail remove button has an accessible label.
6. `components/composer.component.*` + spec (extend existing spec; stub `CommandCatalogService` with a writable commands signal; keep existing tests green):
   - menu opens when draft text is `/` and lists all svy commands; `/fix` filters (substring/case-insensitive); `/svyFixTicket ` (space) closes; `a /x` and `hello/` do not open; disabled composer never opens.
   - ArrowDown/ArrowUp change `activeIndex` with wrap at both ends and `preventDefault`; `aria-activedescendant` tracks it.
   - Enter with the menu open and matches emits `draftChange` with `'/svyName '`, does NOT emit `sendMessage`; click (mousedown) on an item does the same; Tab picks.
   - Escape closes the menu and emits no `draftChange` (text unchanged).
   - Empty state: `/zzz` shows the empty message and Enter emits `sendMessage` with text `/zzz`.
   - Menu closed: Enter sends, Shift+Enter does not send and is not prevented (regression).
   - No `Commands` button; placeholder contains `Type "/" for commands` (disabled text when disabled); `catalog.load()` on init for a `/` draft.
   - Catalog `load()` called when the menu opens.
   - Drag: `dragenter` with `types: ['Files']` shows the overlay containing `Drop images to attach`; with only `text/plain` it does not; nested enter/leave (depth counter) keeps it visible until the last leave; `drop` hides it and still adds files (image -> data URL attachment, non-image -> chip with name url); drag has no overlay when disabled.
   - `fixture.detectChanges()` after state changes does not throw (NG0600 guard).
7. Run from `webui/`: `npx ng test --include="**/svy-commands.spec.ts" --no-watch` and likewise for each new/changed spec, then `npx ng test --no-watch` (full suite) and `ng build`. Leave `webui-dist` as a production build before any commit (`npm run build`).
8. Manual check against a running Servoy Developer (see AGENTS.md "Quick debugging of the Angular frontend"): `/` opens only svy commands, thumbnails render for pasted screenshots, drag overlay does not flicker over children.

## 5. Acceptance criteria
- [ ] Typing `/` as the very first character opens a menu listing only `/svy*` commands with descriptions; non-svy commands (`init`, `status`, `search`, ...) never appear.
- [ ] Typing more characters filters by any part of the command name, case-insensitively; a `/` anywhere else, or once a space has been typed, does not open/keeps the menu closed.
- [ ] There is no `/` button; the textarea placeholder hints `Type "/" for commands`.
- [ ] ArrowUp/ArrowDown navigate with wrap-around; Enter, Tab or click inserts `/name ` and does not send; Escape closes the menu without changing the text.
- [ ] With no matches the menu shows `No command matches - Enter sends it as text.` and Enter sends the text.
- [ ] Enter sends and Shift+Enter inserts a newline exactly as before when the menu is closed.
- [ ] Menu uses `listbox`/`option` roles with `aria-activedescendant` on the textarea.
- [ ] Menu closes on blur, send, disabled and session switch; failure or absence of `/command` leaves the composer fully functional with an empty list.
- [ ] Pasted, dropped and picked images show as thumbnails with an accessible remove button that removes the correct attachment; non-image files remain name chips.
- [ ] Dragging files over the composer shows a `Drop images to attach` overlay without flicker over child elements; it disappears on leave and on drop; dropping still attaches files.
- [ ] Command logic lives in `svy-commands.ts` and `CommandCatalogService` (reusable by SVY-21847), not in the composer.
- [ ] Only the files listed in 4.0 changed; new/changed specs pass, full `ng test --no-watch` and `ng build` succeed.

## 6. Out of scope
- Listing or executing non-svy commands, command arguments/hints, server-side command filtering.
- Welcome-screen starters (SVY-21847), message-list/app changes and read-only input bar (SVY-21850).
- New design tokens, changes to the BFF servlet, sending logic (`sendPrompt`) or `Attachment` model.
- Image resizing/validation limits, previewing non-image files.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Add `GET /api/command` (shape `{ data: [{ name, description }] }`) to the `update-opencode` skill's break-check endpoint list, and optionally mention the command menu in the AGENTS.md webui section (outside this change's allowed files; do as a follow-up) | PM / maintainer | open |
| Identification of agent-pack commands is by the `svy` name prefix because 2.4.1 returns no `source`; revisit if a future opencode version exposes a source/origin field | Dev | open |
| Tab picks the active item in addition to Enter (assumed, not in the ticket); drop if Tab must keep moving focus | PM | open |

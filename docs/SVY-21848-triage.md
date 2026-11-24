# Triage Report - SVY-21848

**Verdict:** PROCEED

## Reported problem

Part 4 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). Target design: attachment `ServoyAI Redesign.html` on SVY-21845 (attachment id 78953, downloaded to `D:\TEMP\opencode\redesign.html`).

- The agent pack ships `/svy*` commands (`/svyImplement`, `/svyFixTicket`, ...) but the user must know them by heart. `svy-composer` has no command menu.
- Pasted / dropped / picked images are shown as name chips in `svy-attachment-bar`, so the user cannot see which screenshot is attached.
- Dragging over the input gives no feedback.

The ticket describes behaviour only (acceptance criteria), not an implementation. No wrong premise to challenge. Three self-contained front-end changes, no backend change needed.

## Root-cause assessment

This is a feature (UI), not a bug. Current code:

- `webui/src/app/components/composer.component.{ts,html,scss}`: controlled component; the draft (`text` + `attachments`) is owned by `ChatStore` and written via `draftChange` (`emitDraft`). `onKeydown` sends on Enter without Shift. `onDrop` / `onDragOver` only `preventDefault()`: no drag state. `addFile` / `readImageFile` build `Attachment { filename, mime, url }`; images get a `data:` URL, other files get `url = file.name` (a name reference). `+` button opens the file picker.
- `attachment-bar.component.{ts,html,scss}`: renders every attachment as a text chip with an `x` button. It already emits `remove(index)`. `Attachment` is exported from here. The `mime` and the `data:` URL already provide everything needed for thumbnails; no model change is required.
- There is no command lookup anywhere in `webui/src/app` (grep for `command` finds only tool-display code). `opencode-api.service.ts` and `status.service.ts` have no command wrapper.

### How the command list reaches the client (investigated live)

Chain: Angular `GET rest_api/command` (relative to base href `/servoy_ai/`) -> BFF `OpencodeChatServlet` (`API_PREFIX = "/rest_api"`, `isApiRequest`) -> rewrites `rest_api/x` to opencode `/api/x`, injects the active project directory + auth, holds the request behind the readiness gate -> opencode 2.4.1 `GET /api/command`.

Queried read-only against the running instance (`http://127.0.0.1:8183/servoy_ai/rest_api/command`, HTTP 200; `/info` reports `version 2.4.1`). Response shape follows the same V2 envelope as the other wrappers (`{ location, data }`):

```json
{
  "location": { "directory": "D:\\installs\\2026.3.2\\ws" },
  "data": [
    { "name": "init", "description": "guided AGENTS.md setup" },
    { "name": "svyFixTicket", "description": "Do what a ticket asks (bug or feature)  paste a Jira/ServoyCloud ticket key." }
  ]
}
```

- Fields per entry in 2.4.1: **only `name` and `description`**. No `source`, `template`, `agent`, `hints` or `id` are returned. So the client cannot distinguish "agent-pack" commands via a `source` field; it must go by name.
- Live result: 24 commands. Built-in / other: `init review goal automation`; MCP/plugin-contributed (codebase index): `call-graph definition find index peek pr-impact reindex search status visualize`. Agent pack: `svyCreateE2E svyCreateFormTest svyCreateJSUnit svyFixStacktrace svyFixTicket svyImplement svyModernize svyOptimize svyReviewTicket svySecurityReview` (10).
- Origin of the svy commands: the skills zip (`SERVOY_SKILLS_ZIP`) is extracted by `SkillsZipExtractor` into `~/.servoy/opencode/`, which contains `commands/svy*.md` (and `agents/`, `skills/`). Each file is a markdown command with front-matter (`description`, `agent: Orchestrator`) and a body starting `[cmd:svyXxx]`; the file name is the command name. The `.servoy-skills-manifest` lists `skills plugin plugins commands agents`.
- Identification rule: **name starts with `svy`** (all 10 do, and no other command does). Recommended: `/^svy[A-Z]/` or `startsWith('svy')`, case-sensitive, so a future `svyx` style or unrelated `svg` is not matched by accident. Server commands such as `status`, `search`, `review` must not be listed (the design's mock list wrongly includes `new/compact/status`; the ticket's acceptance criteria say other commands are not listed, which wins).
- Descriptions: take as-is. Note they contain a double space where an en/em dash was lost to encoding (`bug or feature)  paste ...`). Display as returned; optionally collapse whitespace in the util (`replace(/\s+/g, ' ')`).
- Failure mode: the endpoint can be unavailable while opencode starts (BFF gate) or fail; the list must then be empty (menu shows the empty state or nothing) without breaking the composer. Fetch once lazily (on first open of the menu or on app init) and cache; commands only change when the skills zip changes (restart).

### Design reference (from `redesign.html`)

- Mock data `cmdsAll` entries `{ name, desc }`; menu item markup `/{{c.name}}` (mono 13px, min-width 150px, accent colour) + `{{c.desc}}` (13px, `--fg2`).
- Open rule: `typing = !view && text.charAt(0) === '/' && text.indexOf(' ') === -1`; query = `text.slice(1).toLowerCase()`; filter = `name.toLowerCase().indexOf(q) !== -1` (substring on name, matches ticket "any part of the name"). Pick sets text to `'/' + name + ' '`. Empty state text: `No command matches - Enter sends it as text.`
- Drag overlay: upload icon + `Drop images to attach`.
- Note the design also closes the menu once a space is typed (command + space). Follow that: the menu closes once the text contains a space, so `/svyFixTicket PROJ-1` does not reopen it. This also satisfies "a `/` anywhere else never opens it".

## Ticket premise check

The ticket proposes no mechanism, only behaviour. It holds up. Two clarifications found:

1. The server returns no source/template, so "only the /svy commands of the agent pack" has to be a name-prefix filter (`svy`), not a server-side distinction. This is reliable because the pack's commands are all named `svy*` and the pack is the only producer of such names.
2. The design mock list includes non-svy commands (`new`, `compact`, `status`); the ticket explicitly excludes them. Follow the ticket.

## Approaches considered

1. **Dedicated service + util, menu inside the composer (recommended).**
   - `OpencodeApiService.listCommands()` -> `GET rest_api/command` unwrapped `{ data }` like the other wrappers (type `CommandInfo { name; description? }` in `models/opencode.models.ts`).
   - New root service `services/command-catalog.service.ts` (signal-based, `providedIn: 'root'`): loads once (lazy `load()` / idempotent), exposes `readonly commands = signal<SvyCommand[]>([])` already filtered to `svy*`, sorted/ordered as returned, plus pure helpers in a separate file `services/svy-commands.ts`: `isSvyCommand(name)`, `filterSvyCommands(list, query)` (case-insensitive substring on name), `slashQuery(text)` (returns the query when the text starts with `/` and has no whitespace yet, else `null`).
   - Composer holds only UI state: `menuOpen`, `activeIndex`, `slashButtonOpen`. Menu rendering either inline in the composer template or a small `svy-command-menu` presentational component (inputs: items, activeIndex; outputs: pick, hover).
   - Pros: SVY-21847 (welcome starters) injects the same `CommandCatalogService` and reuses `filterSvyCommands` / descriptions; unit-testable pure functions; no logic in the composer beyond key handling. Cons: one new service and one new small component.
2. Fetch and filter directly inside the composer. Pros: fewest files. Cons: duplicated by SVY-21847, logic untestable without TestBed, bloats the composer. Rejected.
3. Hard-code the 10 commands in the UI. Pros: no network. Cons: drifts from the skills zip, descriptions would diverge; rejected.
4. **No code change** - not viable: acceptance criteria describe missing functionality; there is no existing mechanism that already does this.

### Attachments + drag (same change, independent of the command list)

- `attachment-bar`: split `attachments()` into images (`mime.startsWith('image/')` and `url` is `data:`) and files. Images render as `<img [src]="url" [alt]="filename">` thumbnails with an overlaid remove button; files keep the chip. The `remove` output must keep emitting the index into the ORIGINAL array (compute from the full list, not the filtered one). Keep the existing `track` key. `data:` URLs are produced by the app itself via `FileReader`; Angular sanitises `[src]` bindings and allows `data:image/*`, so no `bypassSecurityTrust` is needed.
- Drag highlight: `composer` gets `dragging = signal(false)`; `dragenter`/`dragover` (only when `dataTransfer.types` includes `Files`) set true, `dragleave` (use a depth counter or check `relatedTarget` so child elements do not flicker) and `drop` set false. Overlay text `Drop images to attach`. Drop behaviour (non-images still become chips) is unchanged.
- Enter / Shift+Enter: only intercept Enter/Arrow/Escape/Tab-less keys when the menu is open; otherwise the existing `onKeydown` path is untouched. With an empty menu result (empty state shown) Enter must still send the text (as the empty-state message says).

## Recommendation

PROCEED with approach 1. Suggested slicing for the spec:

1. `listCommands()` + model + `command-catalog.service.ts` + `svy-commands.ts` (with specs: filter, `slashQuery`, prefix filter, error -> empty).
2. Command menu in `svy-composer` (open rules: first char `/` and no space; `/` button; arrows wrap, Enter/click inserts `'/name '`, Escape closes without changing text; `aria` roles `listbox`/`option`, `aria-activedescendant`; menu closes on blur/send/session switch; do not open when `disabled()`).
3. Thumbnails in `svy-attachment-bar` with remove buttons; non-image chips unchanged.
4. Drag-over highlight.
Use existing `--svy-*` tokens only (from SVY-21845); no new tokens unless the design needs one.

Risks / things to verify in the spec:
- `/command` is a V2 endpoint (`/api/command`); verified live on 2.4.1, but a future opencode bump could change the shape. The wrapper should tolerate a bare array as well as `{ data }` (cheap), and the `update-opencode` skill break-check list should gain this endpoint.
- Draft is per-session and owned by the store: the menu state must be derived from `text()` (draft) so a session switch closes it naturally; only `activeIndex` is local.
- Pasting a path such as `/usr/bin/x` as the very first character would open the menu; it closes as soon as a space/no-match is reached, and Enter on the empty state sends the text. A pasted text containing a space never opens it (rule `no whitespace yet`).
- Parallel session SVY-21850 touches `message-list` / `app.component` and maybe a read-only input bar: keep SVY-21848 changes within `composer.*`, `attachment-bar.*`, the new service/util/component files, `opencode-api.service.ts`, and `opencode.models.ts` to avoid conflicts; `opencode.models.ts` edits should be additive and at the end of the file.

## Git history findings

- `composer.component.ts` / `attachment-bar.component.ts`: introduced in dfb0039 (SVY-21461 custom Angular chat UI), made per-session in a6a2194 (draft owned by the store), send-button greying in fd26285. No prior spec for the slash menu or thumbnails; no earlier attempt to revert.
- Recent redesign commits on master (unpushed): SVY-21845 (3325ff9 layout/theme/tokens), SVY-21852 (2643875), opencode 2.4.1 pin, SVY-21849 (ecdfac5 agent cards). The `.servoy/opencode/commands/*.md` agent-pack commands are not in this repo's history; they come from the skills zip.
- `opencode-api.service.ts` documents that opencode renamed the prompt attachment field `url` -> `uri` in 2.0.26: sending of files is already correct (`{ uri, name }`), so thumbnails need no change to `sendPrompt`.

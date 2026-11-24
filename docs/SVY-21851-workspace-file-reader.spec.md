# Spec: SVY-21851 — Read-only file reader and file chips for the Servoy AI chat (redesign part 7 of 9)

Triage: `docs/SVY-21851-triage.md` (authoritative, including its "Not verified" section). Approved approach: own BFF route + closing the proxy (triage approaches 1 + 3). Design source: attachment 78953 `ServoyAI Redesign.html` on SVY-21845 (local copy `D:\TEMP\opencode\redesign.html`).

## 1. Goal

Let the user open the files an Orchestrator/agent run produces (`handoff/<task>/spec.md`, `review.md`, changed files) in a read-only reader that slides in from the right, started from file chips under the Orchestrator message and in the agent view. The reader is only useful if it cannot be used to read anything outside the workspace, so the security boundary is enforced server side, in a new dedicated BFF endpoint. The existing proxy path that the unused `OpencodeApiService.readFile` calls is not a safe boundary and is closed in the same change.

## 2. Background

### 2.1 Why not opencode `fs.read` through the proxy

The ticket hints that the API service "can already read a file (`readFile`)". That is `GET rest_api/fs/read/<path>`, a verbatim proxy to opencode `fs.read`. It must not be used, because:

- Its boundary is the `directory` of the request, and the BFF keeps a client-supplied `directory` query param (`injectDirectory` returns early in `hasDirectoryParam`) and copies all client headers (`copyRequestHeaders`) next to its own `x-opencode-directory`. Any local caller of `http://127.0.0.1:<port>/servoy_ai/rest_api/fs/read/...` can choose the root (e.g. the home folder). Same family: `fs/list` (absolute `path`, outside the root) and `experimental/fs/write` (writes anywhere).
- No denylist (`.git`, `~/.servoy` when the root is an ancestor of home), no size cap, no text/binary handling, MIME by extension.
- "Refused" and "gone" are the same `FileNotFoundError`, but criteria 4 and 5 need different messages.
- Its semantics change per opencode release; this project pins and break-checks every bump, a security boundary should not depend on that.

Decision: a new route `GET /servoy_ai/file?path=<relative>` served by the servlet itself from disk through a new pure class `WorkspaceFileReader`, plus hardening of the proxy (section 3.2). Not verified live (triage): none of this was run against a live Servoy Developer.

### 2.2 Existing code this builds on

- `OpencodeChatServlet.service()` dispatches `isSolutionRequest` (`/solution`), `isApiRequest` (`/rest_api/**`), else static. `projectPathSupplier` (= `OpenCodeUtil.getActiveProjectPath()`: active project dir walked up to the nearest `.git`, else the project dir) is the single source of the workspace root. `ServicesProvider` constructs the servlet; constructor signatures stay.
- `jsonEscape` (public static) is the JSON string escaper to reuse.
- Webui: `OpencodeApiService` (`readFile`/`findFiles` + `FileMatch` are used by no component, only specs `opencode-api.service.spec.ts` and `chat-store.service.spec.ts:39-40`), `agent-task.component` (`AgentFile`, the empty `data-slot="agent-files"` with inert `<span class="chip">`), `agent-view.component`, `message-item.component`, `part-utils.ts`, `svy-markdown` (marked + DOMPurify + highlight.js), tokens `--svy-*` in `styles.scss` (`--svy-surface`, `--svy-fg`, `--svy-fg2`, `--svy-muted`, `--svy-line`, `--svy-soft`, `--svy-acc-*`, `--svy-mono`, `--svy-focus-ring`).
- Specs of the already merged parts to stay consistent with: `docs/SVY-21850-agent-session-view.spec.md`, `docs/SVY-21849-agent-cards-live-status.spec.md`, `docs/SVY-21845-three-area-layout-theme-toggle.spec.md`.

### 2.3 Design notes

The redesign HTML is a packed bundle; text search could not extract the visual details. From the triage: reader header with file name, workspace path and a "read-only" pill; close button `aria-label="Close file"` 40x40; file chips are buttons, 44 px high under the message and 36 px high in the agent view. The implementer MUST open `D:\TEMP\opencode\redesign.html` in a browser and compare the reader and the chips visually (light and dark), mapping every colour/radius/shadow to existing `--svy-*` tokens; no new hard-coded colours.

### 2.4 Parallel work

SVY-21847 (welcome screen) may change `message-list.*` and `composer.*`. This case must not touch those. `app.component.html`: exactly one added element. `message-item.*`: minimal (section 3.4).

## 3. Design

### 3.1 Endpoint `GET /servoy_ai/file?path=<relative>`

Routing: `static boolean isFileRequest(String pathInfo)` (`"/file".equals(pathInfo)`), checked in `service()` next to `isSolutionRequest`. Not proxied, does not need the readiness gate or the opencode port.

Request handling order (servlet `handleFile`):

1. Method: only GET and HEAD, else 405 with `Allow: GET, HEAD`. HEAD runs the full validation and sends status + headers, no body.
2. Request-origin hardening (cheap DNS-rebinding/CSRF defence, applies to `/file` only):
   - `Host` header: the host part (port stripped, case-insensitive) must be `127.0.0.1`, `localhost` or `[::1]`, else 403 `forbidden`. Missing Host -> 403.
   - If `Sec-Fetch-Site` is present it must be `same-origin`, else 403.
   - If `Origin` is present, its authority (host:port) must equal the `Host` header, else 403.
   - Absent `Sec-Fetch-Site`/`Origin` is allowed (curl, older engines). Honest limit: a local process can still call the route; it only ever gets workspace-confined text (3.1.3).
3. `root = projectPathSupplier.get()`; null/empty -> 409 `{"error":"no_active_solution"}`. A `RuntimeException` from the supplier -> 409 as well.
4. Parameter: exactly one `path` value (`getParameterValues`), else 400 `{"error":"bad_request"}`. Empty/blank -> 400. The servlet container decodes the query once; the reader NEVER decodes again.
5. `WorkspaceFileReader.read(root, path)` -> result mapped to HTTP (3.1.4).

#### 3.1.1 The workspace boundary (exact definition)

The workspace is the set of regular files whose **real path** (symlinks, junctions and 8.3 names resolved) lies below `rootReal`, where `root = projectPathSupplier.get()` (the git root of the active project, else the solution project dir) and `rootReal = Path.of(root).toRealPath()`, resolved fresh on every request, **minus** the denylist (3.1.2). The root comes ONLY from the supplier; no client input (query, header, body) can influence it. Containment is `Path.startsWith` on real paths (segment-aware; case-insensitive where the file system is), never string prefix, so `ws-evil` is not inside `ws`. If `rootReal` is not an existing directory the result is NOT_FOUND.

#### 3.1.2 Rejection and denylist rules (class `WorkspaceFileReader`)

All lexical rules are platform independent (they apply on POSIX as well) and run BEFORE any disk access, so their outcome never depends on whether the target exists. Violation -> FORBIDDEN.

Lexical (on the single-decoded input string):

- Length > 1024 chars, any char < 0x20 or 0x7F (includes NUL, CR, LF, TAB), any of `<>"|?*` -> FORBIDDEN.
- Starts with `\\` or `//` (UNC, `\\?\`, `\\.\`, `\\server\share`) -> FORBIDDEN.
- Absolute input: accepted only if `Path.of(input).isAbsolute()` on the running OS (an `InvalidPathException` -> FORBIDDEN). Anything starting with `/` or `\` that is not absolute on this OS (e.g. `/x` on Windows) -> FORBIDDEN. An absolute input is converted to relative: it must lie lexically below `root` or `rootReal` (`Path.startsWith`), the relative remainder then follows the normal pipeline; otherwise FORBIDDEN.
- Relative input: `\` and `/` are both separators on every OS; empty segments are dropped. `:` anywhere (drive-relative `C:foo`, NTFS alternate data stream `a.md:x`) -> FORBIDDEN. For an absolute input the drive colon of the root component is the only allowed `:`.
- Per segment: `.` or `..` -> FORBIDDEN; ends with `.` or a space (Windows strips them) -> FORBIDDEN; Windows reserved device names, case-insensitive, with or without extension or trailing dot: `CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9` (also the superscript digits variants), `CONIN$`, `CONOUT$` -> FORBIDDEN; segment equal (case-insensitive) to `.git` or matching `(?i)^git~\d+$` (8.3 alias of `.git`) -> FORBIDDEN.
- After normalising, the candidate `rootReal.resolve(rel).normalize()` must not be at or below the `~/.servoy` directory (lexical absolute form AND real form of the config dir, `user.home` + `.servoy`) -> FORBIDDEN. This is what refuses `~/.servoy/opencode/...` when the root is an ancestor of home, also for non-existing files.
- `%` is NOT special: `%2e%2e` reaching the reader is the literal file name `%2e%2e` and resolves to NOT_FOUND (a double-encoded traversal is not decoded a second time).

Disk phase (in this order):

1. `rootReal` must exist as directory, else NOT_FOUND. If `rootReal` is at or below the `~/.servoy` dir -> every request FORBIDDEN.
2. Find the deepest existing ancestor-or-self of `rootReal.resolve(rel)`, `toRealPath()` it; it must start with `rootReal`, else FORBIDDEN. This makes an escape through a link FORBIDDEN also when the final file does not exist (no existence oracle for outside locations).
3. If the full path does not exist -> NOT_FOUND.
4. `real = full.toRealPath()`; must start with `rootReal` (FORBIDDEN otherwise).
5. Denylist on the REAL path: refuse if any segment of `rootReal.relativize(real)` equals `.git` (case-insensitive; also a `.git` file of a worktree), or `real` is at or below the real `~/.servoy` dir, or an ancestor of `real` inside the root `Files.isSameFile` the root's `.git` / the `~/.servoy` dir (alias catch-all for 8.3, junction and case aliases). FORBIDDEN.
6. `real` must be a regular file (`readAttributes(real, BasicFileAttributes, NOFOLLOW_LINKS)`); directories, devices, pipes -> NOT_FOUND.
7. Open with `Files.newByteChannel(real, READ, NOFOLLOW_LINKS)`, re-check `real.toRealPath().equals(real)` and containment after opening (narrows the TOCTOU window; the residual race needs write access to the workspace and is documented in the Javadoc).
8. Size: read at most `max + 1` bytes, `max = 2 MiB (2*1024*1024)`; larger -> TOO_LARGE. Text only: a NUL byte in the first 8192 bytes, or a failed strict UTF-8 decode (`CharsetDecoder` with `REPORT`) -> BINARY. A leading UTF-8 BOM is dropped from `content`; line endings are left untouched; empty file is OK with `content: ""`.

Not denied on purpose (workspace content): `node_modules`, build output, `.env`-style files (see open question 4).

#### 3.1.3 Result type and API

`public final class WorkspaceFileReader` in `com.servoy.eclipse.opencode.tomcat` with a constructor `WorkspaceFileReader(Path servoyConfigDir, int maxBytes)` (injectable for tests) and `static WorkspaceFileReader createDefault()` (`~/.servoy`, 2 MiB). `Result read(String root, String requestedPath)` returns an immutable `Result` (`Status` = `OK, BAD_REQUEST, FORBIDDEN, NOT_FOUND, TOO_LARGE, BINARY, ERROR`; for OK also `path` (workspace-relative of the real file, `/` separators), `name`, `size`, `modified`, `content`). Never throws for bad input; an unexpected `IOException` -> ERROR. The servlet holds one reader created with `createDefault()` (field initialiser; an additional package-private servlet constructor taking a reader is allowed for tests; existing constructors unchanged).

#### 3.1.4 HTTP contract

All responses (success and error) carry `Content-Type: application/json;charset=UTF-8`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. File bytes are never served with a guessed MIME.

| Status | Body | When |
|---|---|---|
| 200 | `{"path":"handoff/t/spec.md","name":"spec.md","size":123,"modified":1700000000000,"content":"..."}` | OK (`path` is relative with `/`, never the root) |
| 400 | `{"error":"bad_request"}` | missing/duplicate/blank `path` |
| 403 | `{"error":"forbidden"}` | any lexical rule, outside root, denylist, bad Host/Origin/Sec-Fetch-Site; the body is byte-identical for every cause |
| 404 | `{"error":"not_found"}` | missing file inside the root, directory/non-regular, root missing |
| 405 | (empty body) + `Allow: GET, HEAD` | other methods |
| 409 | `{"error":"no_active_solution"}` | supplier null/empty |
| 413 | `{"error":"too_large"}` | > 2 MiB |
| 415 | `{"error":"binary"}` | not UTF-8 text |
| 500 | `{"error":"read_failed"}` | unexpected I/O error |

No response ever contains the root, a resolved path, an exception message or the requested string. Details go to `ServoyLog` only: the status code reason plus the requested path with control chars stripped and cut at 200 chars (no file content).

### 3.2 Closing the proxy (`rest_api/**`)

1. **Block opencode `fs`**: new `public static boolean isBlockedApiPath(String pathInfo)`. After the `/rest_api` prefix, split on `/`, drop empty segments; blocked when any segment equals `fs` (case-insensitive) — this covers `fs/read`, `fs/list`, `fs/find`, `experimental/fs/write` — or when any segment is `..` or `.`. `handleApi` answers 403 `{"error":"forbidden"}` (same JSON headers) as its FIRST step, before the readiness gate, the active-solution check and any upstream call. No webui code uses `rest_api/fs` (re-verify with a grep over `webui/src` for `/fs/`, `readFile`, `findFiles` before removing).
2. **Always overwrite the directory**: `injectDirectory(upstreamPath, directory)` removes every client `directory` query param (name compared after percent-decoding, case-insensitive, any number of occurrences) and then appends the single BFF value. New `static String stripDirectoryParam(String query)` (pure, tested). The current "keep client directory" behaviour and its tests are replaced. Applies to normal requests and to the SSE path (same `injectDirectory`).
3. **Headers**: client `x-opencode-directory` and `authorization` are never forwarded (new pure `static boolean isForwardableRequestHeader(String name)`: false for hop-by-hop headers, `x-opencode-directory`, `authorization`, case-insensitive). The BFF then sets exactly one `x-opencode-directory` and its own `authorization` with `builder.setHeader` (not `header`, which appends), also in `pumpSse`.
4. **Body location**: for the body-location endpoints (`isBodyLocationEndpoint`), a client-supplied `location` is no longer respected: parse with Jackson (already used in the bundle), replace/insert `location = {"directory": <BFF directory>}`, leave non-object/unparsable bodies unchanged. `ensureBodyLocation` keeps its signature; its test "respects explicit location" is inverted. (Implementer: check `webui/src` that no UI call sends `location` or `directory`; AGENTS.md says the front-end never carries directory state.)

### 3.3 Webui: service, API, reader

- `OpencodeApiService`: remove `readFile`, `findFiles`, `FileMatch` (and their spec cases); add `readWorkspaceFile(path: string): Observable<WorkspaceFile>` = `http.get<WorkspaceFile>(\`file?path=${encodeURIComponent(path)}\`)` — a relative URL like the other calls. Build the query string with `encodeURIComponent` (Angular `HttpParams` leaves `+` unescaped, which the container decodes as a space). `WorkspaceFile { path; name; size; modified; content }` in `models/opencode.models.ts`.
- `services/file-reader.service.ts` (`providedIn: 'root'`, signals): `state()` = `{ open: boolean, path: string|null, status: 'loading'|'ready'|'not-found'|'forbidden'|'binary'|'too-large'|'error', file: WorkspaceFile|null }`, `open(path, trigger?: HTMLElement|null)`, `close()`, `retry()`. `open` always refetches (no content cache, criterion 3) and ignores stale responses (latest request wins, also after `close`). Status mapping from `HttpErrorResponse`: 404 -> `not-found`, 403 -> `forbidden`, 415 -> `binary`, 413 -> `too-large`, other/`status 0` -> `error`. It remembers the trigger element and returns focus there on close (falls back to `document.body` if gone).
- `components/file-reader.component.{ts,html,scss,spec.ts}` selector `svy-file-reader`, standalone, OnPush, hosted once in `app.component.html` (one element, plus the import in `app.component.ts`). Renders only while `state().open`:
  - Fixed right panel with a backdrop; width `min(720px, 92vw)`, full height, slide-in transition disabled under `prefers-reduced-motion`; z-index above the status panel/session scrim. It is an overlay: no scroll lock, no change to `message-list`, so the conversation underneath keeps its position.
  - `role="dialog"`, `aria-modal="true"`, `aria-labelledby` the file name; header: file name, workspace path (the server-returned `path`, else the requested one; full path in `title`), pill "read-only", close button `aria-label="Close file"` (40x40).
  - Close by button, backdrop click, and Escape (`document:keydown.escape`, `preventDefault`, only while open; must not trigger any other Escape handler such as stop-streaming). Initial focus on the close button, Tab/Shift+Tab trapped inside the panel, focus returned to the chip on close.
  - Body: markdown (`.md`, `.markdown`) through `svy-markdown`, everything else in `<pre class="code"><code>` using `textContent` binding (monospace via `--svy-mono`, `tabindex="0"`, wraps off, scrolls). No highlighting required.
  - Markdown from a workspace file is untrusted: add an optional input `inert` (default `false`, so existing callers are unchanged) to `markdown-renderer.component.ts`; when `true`, DOMPurify additionally forbids `img`, `picture`, `source`, `iframe`, `form`, `style` and a hook drops `href` on `a` (text stays) so nothing loads or navigates the embedded browser. The reader uses `[inert]="true"`.
  - Texts (exact): not-found `This file is no longer in the workspace.`; forbidden `This file cannot be opened.`; binary `This file is not a text file and cannot be shown.`; too-large `This file is too large to show here (limit 2 MB).`; error `The file could not be read.` with a Retry button; loading `Loading…` (`aria-busy`). Messages are in an element with `role="status"` (error: `role="alert"`); the header (name, path, read-only) stays visible in every state; never an empty reader.
- `app.component.html`: exactly one added line `<svy-file-reader></svy-file-reader>` before the `@if (debugEnabled)` block.

### 3.4 Webui: file refs and chips

- `services/file-refs.ts` (pure, no Angular): `interface FileRef { path: string; name: string; caption: 'Spec' | 'Review' | 'Changed file' }`.
  - `fileRefsFromParts(parts: Part[]): FileRef[]`: only completed tool parts. Allowlist `FILE_TOOLS` (constant, extendable): `write` and `edit` (path in `state.input.filePath ?? path`), `apply_patch` (lines `*** Add File: <p>`, `*** Update File: <p>`, `*** Move to: <p>`; `*** Delete File` ignored). Read the real 2.4.1 input shape of these tools from existing parts/`part-utils.ts` before coding.
  - `fileRefsFromText(text: string): FileRef[]`: conservative regex for `handoff[\\/][A-Za-z0-9._-]+[\\/](spec|review)\.md` (case-insensitive, optionally preceded by `./`, delimited by whitespace, quotes, backticks, brackets or sentence punctuation; paths with spaces are not matched in prose).
  - Normalisation: `\` -> `/`, strip leading `./`, trim; drop empty, `..`-containing and UI-obviously-not-workspace values (UX only; the server decides). Absolute paths are kept (the server converts those inside the workspace). Dedupe case-insensitively, keep first-seen order. Caption from the base name only: `spec.md` -> Spec, `review.md` -> Review, else Changed file. No parsing of "verdict" or message wording.
  - `fileRefsFromMessages(messages)` = union over parts + text parts, used by the agent view.
- `components/file-chip.component.{ts,html,scss,spec.ts}` selector `svy-file-chip`: inputs `file: FileRef`, `size: 'md' | 'sm'` (44 px / 36 px). A real `<button type="button">` with a document icon, caption and name (ellipsis; full path in `title`), `aria-haspopup="dialog"`, click -> `FileReaderService.open(file.path, event.currentTarget)`. Visible focus ring (`--svy-focus-ring`), both themes.
- `agent-task.component`: `AgentFile` becomes an alias of `FileRef`; the `<span class="chip">` in `data-slot="agent-files"` becomes `<svy-file-chip size="sm">`; the spec assertion that the slot contains no buttons (line ~88) is updated to expect buttons that open the reader.
- `agent-view.component`: `files = computed(() => fileRefsFromMessages(messages()))` passed as `[files]` to `svy-agent-task` (the child's own messages only).
- Orchestrator message (`message-item.component`, minimal): inject a small `FileRefsService` (new, `services/file-refs.service.ts`) with `refsForMessage(message)`: refs from the message's own tool parts and text, plus the refs from the messages of the child sessions that this message's subagent tool parts point to (`subagentSessionId`) and that are ALREADY loaded in `ChatStore` (no extra fetch, no git discovery). `message-item` renders one block after its parts, only for assistant messages of a non-subagent session and only when the list is non-empty: `<div class="files" role="group" aria-label="Files">` with `svy-file-chip size="md"` items, first 6 shown, then a "+N more" button revealing the rest. `message-list.*` and `composer.*` are NOT touched; if the store has no accessor for loaded child messages, add a read-only one to `chat-store.service.ts`.
- Known limitation: Servoy MCP file tools (`eclipse-coder_*`, Code Mode scripts) take project-relative paths and the UI cannot map them to the git-root-relative workspace path; they are not in `FILE_TOOLS` (open question 2).

## 4. Implementation plan

Java (bundle `com.servoy.eclipse.opencode`, `.tomcat`):

1. New `WorkspaceFileReader` per 3.1.2/3.1.3.
2. `OpencodeChatServlet`: `isFileRequest`, `handleFile` (3.1), `isBlockedApiPath`, `stripDirectoryParam`, new `injectDirectory` behaviour, `isForwardableRequestHeader` + `setHeader` for `x-opencode-directory`/`authorization` (forward and SSE paths), body `location` overwrite (3.2). Update the class Javadoc. Constructors unchanged.
3. `AGENTS.md`: update the `OpencodeChatServlet` row (new `/file` route, blocked `rest_api/fs/**`, directory override) and the test-bundle description (new tests).

Java tests (`tests/com.servoy.eclipse.opencode.tests`, `...plainjunit.tomcat` unless noted): `WorkspaceFileReaderTest` (new), additions to `OpencodeChatServletTest` and `OpencodeChatServletGuardTest`, new `...plainjunit.integration.OpencodeChatServletProxyHardeningTest` (reuse `ServletCall`). See section 6.

Webui (`bundles/com.servoy.eclipse.opencode/webui`):

4. Models + `opencode-api.service.ts` (+spec): remove `readFile`/`findFiles`/`FileMatch`, add `readWorkspaceFile`; fix `chat-store.service.spec.ts` stubs.
5. `file-reader.service.ts` (+spec), `file-reader.component.*`, `markdown-renderer` `inert` input (+spec).
6. `file-refs.ts` (+spec), `file-refs.service.ts` (+spec), `file-chip.component.*`.
7. `agent-task` (+spec update), `agent-view` (+spec), `message-item` (+spec), `app.component.{html,ts}` (+spec: reader host present).
8. `npm test` (Vitest) and `npm run build`; Java: plain JUnit via `eclipse-ide_runJUnitTests`, compile check with `eclipse-ide_getCompilationErrors`, Spotbugs clean on new code.

Commits: explicit paths only (parallel SVY-21847 work); subject must contain `SVY-21851` and end with ` [ai]`.

## 5. Acceptance criteria

- [ ] A chip opens the reader showing file name, workspace path and "read-only"; markdown renders as markdown (images stripped, links inert), other text is monospace.
- [ ] Close by close button, click on the backdrop, and Escape; focus is trapped while open and returns to the chip; the conversation scroll position/pinning is unchanged.
- [ ] Reopening a file that changed on disk shows the new content (no content cache; `no-store`).
- [ ] A missing file shows "This file is no longer in the workspace."; a refused file shows "This file cannot be opened."; binary/too-large/error have their own messages; the reader is never empty.
- [ ] `GET /servoy_ai/file` refuses everything in the matrix of section 6 with the contract of 3.1.4, also when called directly (outside the UI).
- [ ] `rest_api/fs/**` is always 403; client `directory` query, `x-opencode-directory` and `authorization` never reach opencode; the BFF directory is the only one upstream.
- [ ] Chips appear under Orchestrator messages (spec, review, changed files from own and already-loaded child sessions) and in the agent view `agent-files` slot.
- [ ] No change to `message-list.*`/`composer.*`; `app.component.html` gains one element; `webui-dist` is a production build before committing.
- [ ] All new Java and webui tests pass without skips; no compilation errors; Spotbugs clean.

## 6. Security test matrix (plain JUnit, no skips)

Rules for all tests: JUnit Jupiter, `@DisplayName` only on methods, no `Assumptions`/`@EnabledOnOs`/`@Disabled`; fixtures under `@TempDir` (`ws/` root with `handoff/t/spec.md`, `src/a.js`, `.git/config`, `ws-evil/secret.txt` next to `ws/`, a fake home with `.servoy/opencode/opencode.json`, `outside/secret.txt` next to `ws/`); the reader is built with the fake `.servoy` dir. Where the expected result legitimately depends on the OS, the test computes the expected value from `System.getProperty("os.name")` and still asserts (never skips) that no escape happens. Links: Windows `cmd /c mklink /J <link> <target>` via `ProcessBuilder` (junction, needs no privilege), other OS `Files.createSymbolicLink`; if creation fails (exit code != 0, link missing, or exception) the test FAILS with a clear message.

`WorkspaceFileReaderTest` — expected status; "no root/path in result" is asserted on every non-OK result via the servlet JSON mapping:

| Group | Input (relative to root unless stated) | Expected |
|---|---|---|
| Happy path | `handoff/t/spec.md`; with `\` separators `handoff\t\spec.md`; `./handoff/t/spec.md`; `HANDOFF/T/SPEC.MD` | OK for first two (and third: `.` segment is FORBIDDEN, so third = FORBIDDEN); upper-case variant: OK on Windows, NOT_FOUND elsewhere, never an escape; OK result has `path` with `/`, no root |
| Absolute inside | `root.resolve("handoff/t/spec.md").toString()` | OK, converted to relative |
| Absolute outside | `outside/secret.txt` absolute; POSIX `/etc/passwd`; `C:\Windows\win.ini`; `C:foo` | FORBIDDEN |
| Traversal | `../outside/secret.txt`, `..\outside\secret.txt`, `a/../../outside/secret.txt`, `src/../../outside/secret.txt`, `..`, `.`, `src/./a.js` | FORBIDDEN |
| Encoded | `%2e%2e/outside/secret.txt` and `%252e%252e/x` passed as the reader would see them (literal) | NOT_FOUND (never decoded again); `..%2f..%2fsecret` literal | NOT_FOUND |
| UNC / device prefixes | `\\server\share\x`, `//server/share/x`, `\\?\C:\Windows\win.ini`, `\\.\C:\x`, `\\?\UNC\s\x` | FORBIDDEN |
| Streams | `src/a.js:stream`, `handoff/t/spec.md::$DATA`, `a:b` | FORBIDDEN |
| Trailing dot/space | `src/a.js.`, `src/a.js ` (trailing space), `src.\a.js`, `src /a.js` | FORBIDDEN |
| Reserved names | `CON`, `nul.txt`, `src/COM1`, `lpt9.md`, `aux.`, `CONIN$`, `con.md.txt` | FORBIDDEN (on every OS) |
| NUL / control | `src/a.js\u0000.png`, `src/a\njs`, `\t` | FORBIDDEN |
| Blank / long | `""`, `"   "` -> BAD_REQUEST; 2000-char name -> FORBIDDEN | as stated |
| 8.3 | `GIT~1/config`, `git~2/config` | FORBIDDEN (lexical, with `.git` absent); `PROGRA~1/x` | NOT_FOUND (plain missing name) |
| Sibling prefix | `../ws-evil/secret.txt`; absolute `<tmp>/ws-evil/secret.txt` with root `<tmp>/ws` | FORBIDDEN |
| Links out | link `lnk` -> `outside/` (junction/symlink), request `lnk/secret.txt` | FORBIDDEN; and `lnk/missing.txt` also FORBIDDEN (no existence oracle); link to a file outside | FORBIDDEN |
| Links in | link `alias` -> `src/` inside root, `alias/a.js` | OK (real path inside); link `gitlink` -> `.git` dir, `gitlink/config` | FORBIDDEN (denylist on real path) |
| `.git` | `.git/config`, `.GIT/config`, `src/.git/config`, `.git` (existing and missing variants), a `.git` file (worktree) | FORBIDDEN, identical to the missing case |
| `~/.servoy` | root = fake home (ancestor), `.servoy/opencode/opencode.json`, `.SERVOY\opencode\x`, a missing file under it, and the same through a link to it | FORBIDDEN; root = fake `.servoy/opencode` itself -> every path FORBIDDEN |
| Not regular | `src` (directory), `handoff/t` | NOT_FOUND |
| Missing | `handoff/t/gone.md`, `nodir/x.md` | NOT_FOUND |
| Size | file of exactly 2 MiB -> OK; 2 MiB + 1 byte | TOO_LARGE |
| Binary / UTF-8 | PNG bytes; file with NUL at byte 100; file with invalid UTF-8 (`0xC3 0x28`); valid multi-byte UTF-8; UTF-8 BOM file (BOM not in content); CRLF kept; empty file | BINARY, BINARY, BINARY, OK, OK, OK, OK |
| Identical refusal | `.git/config` existing vs missing; `lnk/secret.txt` vs `lnk/missing.txt`; `../outside/x` | JSON mapping byte-identical |
| Root edge | root missing/not a directory; root given with different case, trailing slash, `..` segments | NOT_FOUND / OK (canonicalised via `toRealPath`) |

`OpencodeChatServletTest` additions: `isFileRequest` (`/file` true; `/file/`, `/File`, `/rest_api/file`, null false); `isBlockedApiPath` table (`/rest_api/fs/read/x`, `/rest_api/fs`, `/rest_api/FS/list`, `/rest_api/experimental/fs/write`, `/rest_api//fs/read`, `/rest_api/./fs`, `/rest_api/session/../fs/read` blocked; `/rest_api/session`, `/rest_api/session/fsx`, `/rest_api/event` not blocked); `stripDirectoryParam` / `injectDirectory` table (client `directory=C%3A%5CUsers`, `Directory=`, `director%79=x`, repeated, bare `directory`, mixed with other params kept in order, no query, null/empty BFF directory); `isForwardableRequestHeader` (`X-OpenCode-Directory`, `Authorization`, `connection`, `host`-class hop-by-hop false; `content-type`, `accept` true); `ensureBodyLocation` inverted: client `location` is replaced, other members kept, non-JSON unchanged.

`OpencodeChatServletGuardTest` additions (dynamic-proxy request/response fakes, `@TempDir` root through the `projectPathSupplier`): POST/PUT/DELETE `/file` -> 405 + `Allow`; HEAD -> same status/headers, empty body; supplier null / empty / throwing -> 409 `no_active_solution`; no `path` -> 400; two `path` values -> 400; success 200 with exact JSON, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `application/json;charset=UTF-8`; 403/404/413/415 bodies and headers exactly as 3.1.4; response body never contains the root string (any case, any slash style) or the resolved path, for every non-200; 403 body byte-identical for existing vs missing `.git/config`; `Host` `evil.example`, `127.0.0.1.evil.com`, missing Host -> 403, `localhost:8183`, `127.0.0.1:8183`, `[::1]:8183` accepted; `Sec-Fetch-Site: cross-site|same-site|none` -> 403, `same-origin` accepted, absent accepted; `Origin: http://evil.example` -> 403, `Origin` equal to Host authority accepted; `/rest_api/fs/read/x` -> 403 JSON even with port 0 and `serverReady=false` (checked before the readiness gate and the upstream), `x-opencode-directory`/`directory` sent by the client on a blocked path still 403.

`OpencodeChatServletProxyHardeningTest` (`...plainjunit.integration`, fake upstream = JDK `com.sun.net.httpserver.HttpServer` on a loopback ephemeral port; real servlet driven through `ServletCall`): client `?directory=<home>` and `x-opencode-directory: <home>` and `Authorization: Basic x` on `/rest_api/session` -> upstream sees exactly one `directory` query param equal to the BFF root, exactly one `x-opencode-directory` equal to the BFF root, and only the BFF's `authorization`; same for the SSE `/rest_api/event` request; `POST /rest_api/session` with a client `{"location":{"directory":"<home>"}}` -> upstream body has the BFF directory; `GET /rest_api/fs/read/x` -> 403 and the fake upstream records NO request.

Webui (Vitest): `file-refs.spec.ts` (write/edit/apply_patch shapes, Add/Update/Move/Delete lines, errored tool parts ignored, `handoff` regex positives/negatives incl. backticks, punctuation, Windows separators, `.\`, paths with spaces in tool input kept and in prose not matched, normalisation, dedupe, captions, cap/order); `file-reader.service.spec.ts` (status mapping per HTTP code, refetch on every open, stale response ignored, focus return); `file-reader.component.spec.ts` (name/path/read-only pill, close button/backdrop/Escape, Escape does not reach other handlers, focus trap, texts for each state, markdown rendered with `img` removed and `href` dropped, code in `pre`, no empty reader); `file-chip.component.spec.ts`; `opencode-api.service.spec.ts` (URL is `file?path=` with `encodeURIComponent` incl. `+`, space, `#`, `&`, non-ASCII; `readFile`/`findFiles` gone); `markdown-renderer.component.spec.ts` (`inert` on/off); `agent-task`, `agent-view`, `message-item`, `app.component` specs updated/extended.

## 7. Out of scope

- Editing, saving, downloading, search, syntax highlighting, line numbers, live refresh while the reader is open.
- Git-based discovery of changed files; chips for Servoy MCP/Code Mode file tools (project-relative paths).
- Parsing message wording ("verdict PASS", "amended") for chip captions.
- Image/binary preview.
- Host/Origin hardening of `rest_api/**` beyond the items in 3.2, authentication of the BFF, any opencode version change or pin bump.
- Welcome screen, `message-list`, `composer` (SVY-21847).
- Not-live-verified items from the triage (opencode 2.4.1 runtime behaviour, 8.3 on CI agents).

## 8. Open questions

| Question | Owner | Status |
|---|---|---|
| Where does agent scratch live (path or convention under `handoff/`)? Until known it is covered only by the `.git` and `~/.servoy` denylist; add its pattern to 3.1.2 once defined | Reporter / Orchestrator prompt owner | open |
| Chips for Servoy MCP file tools need a project -> git-root mapping (tool paths are project-relative). Accept the v1 limitation or add a BFF mapping later? | Product | open (v1: limitation) |
| Does any webui call or opencode feature legitimately send `directory`, `location` or another directory-bearing param (query/body) that must not be overwritten? Grep `webui/src` and break-check 2.4.1 payloads before step 3.2.2/3.2.4 | Implementer | open |
| Should `.env`/credential-like files inside the workspace (outside `.git`) be denied? Default: allowed (workspace content) | Product / security | open |
| Does `toRealPath` expand 8.3 aliases on the CI agents, and is `FSUtil`-style case-insensitivity relevant? The file-identity check in 3.1.2 step 5 is the safety net; covered by the link tests, not by a live 8.3 test | Implementer | open |
| Apply the Host/Origin check to `rest_api/**` too (DNS rebinding of the proxy)? Default: not in this case | Reporter | open |
| Visual details of reader and chips come from comparing with `redesign.html` (not extractable by text search) | Implementer | open |

## 9. Decisions after spec review (user, authoritative; override the table above)

1. Agent scratch: the Orchestrator dictates where it lives; assume it is under the project root (very likely the git root). No extra denylist beyond `.git` and `~/.servoy`; closed.
2. Chips for Servoy MCP file tools: first check what the tool parts (input AND output) of `eclipse-coder_*` and Code Mode file tools really contain, in existing session data. If the full path (or something mappable to the git-root-relative path) is present, derive the chip from it; if not, a small BFF endpoint that maps a project-relative path to the workspace-relative path may be added, but ONLY under the same rules as `/file` (root from supplier, same denylist, same 403/404 contract, no path leaked outside the workspace, own tests). If neither is practical, keep the v1 limitation and record it.
3. `directory`/`location` usage by webui/opencode: investigate during implementation (grep `webui/src`, check payloads) and verify live afterwards.
4. `.env`-like files: allowed (workspace content, may be gitignored).
5. `toRealPath` / 8.3 / case behaviour: needs a live check on Windows afterwards; the junction/link tests stay mandatory.

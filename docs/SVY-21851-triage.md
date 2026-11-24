# Triage Report — SVY-21851

**Verdict:** PROCEED

## Reported problem

Part 7 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). Design: attachment 78953 'ServoyAI Redesign.html' on SVY-21845 (local copy `D:\TEMP\opencode\redesign.html`).

Symptom: the Orchestrator writes `handoff/<task>/spec.md`, the agents keep a `review.md` ledger, and the agents change solution files. The user has to find these in the Developer file tree. The chat UI has no file viewer; a non-image attachment is only a name chip. Nothing in the conversation or in an agent session points to those files.

Acceptance criteria (from the ticket):

1. A file chip opens a reader with file name, workspace path and "read-only". Markdown renders as markdown, code is monospace.
2. Close with the close button, a click outside, or Escape. The conversation underneath stays where it was.
3. A file that changed while open shows the new content when reopened.
4. Only files inside the workspace can be opened. A skill file, prompt, or a file in the home folder is refused, also when asked for outside the UI's own buttons.
5. A file that no longer exists shows "This file is no longer in the workspace." instead of an empty reader.

Chips: under the Orchestrator's message when it writes/amends the spec and when the work is done (spec.md, review.md, changed files), plus chips in the agent view of SVY-21850 (the empty `data-slot="agent-files"` in `agent-task.component.html:11`).

Links: SVY-21851 is "blocked by" SVY-21849 and SVY-21850 (both on master, not pushed). The ticket has no comments or attachments. The ticket proposes no implementation; it only says "the API service can already read a file (`readFile`)". That hint is the trap, see below.

## Root-cause assessment

This is a feature. The important finding is about the security boundary: **the existing proxy path that `readFile` uses is not a safe boundary, and opencode's own `fs.read` is only a partial one.**

### What `OpencodeApiService.readFile` calls today

- `opencode-api.service.ts:229` `readFile(path)` -> `GET rest_api/fs/read/<path>` (BFF maps it to opencode `/api/fs/read/*`). `findFiles` -> `rest_api/fs/find`. Neither is used by any component (only specs: `opencode-api.service.spec.ts:297,311`, `chat-store.service.spec.ts:39-40`).
- The BFF (`OpencodeChatServlet.handleApi`, lines 520-569) proxies ALL of `rest_api/**` verbatim, appending `directory=<active project>` only if the client did not send one (`injectDirectory`, 274-284: `hasDirectoryParam` returns early). It also copies all client request headers (`copyRequestHeaders`, 798-815) and then adds `x-opencode-directory` (599) as a second header.

### What opencode `fs.read` enforces itself

Source read in `D:\git\opencode` (branch `v2`, HEAD `2f3ba04a8b`; the tag `v2.4.1` does NOT exist in that checkout, so this is the v2 branch head, not byte-for-byte 2.4.1. The 2.4.1 break check, `D:\TEMP\opencode\opencode-2.4.1-break-check.md` lines 18-19, says `groups/fs.ts` and the read handler are unchanged in behaviour 2.2.0 -> 2.4.1, only headers/range helper). Files: `packages/protocol/src/groups/fs.ts`, `packages/server/src/handlers/fs.ts`, `packages/core/src/filesystem.ts:108-170`.

`fs.read` -> `FileSystem.read` -> `resolve(input)`:

- Rejects a path containing `\u0000`.
- `path.resolve(location.directory, input)` then `FSUtil.contains(location.directory, absolute)` (lexical check).
- `fs.realPath(absolute)`, then `FSUtil.contains(root, real)` with `root = realPath(location.directory)` (symlink check).
- Missing file AND outside-the-root both come back as the same `NotFoundError` -> `FileNotFoundError` (no oracle, but the UI also cannot tell "refused" from "gone").
- It only serves regular files (`info.type !== "File"` -> not found).
- URL decoding: `decodeRequestPath` does one `decodeURIComponent` on the pathname after the 13-char prefix.

What it does NOT do (so it is not enough on its own):

- No denylist: `.git/`, and anything else inside the root, is readable. If the root is an ancestor of `~/.servoy/opencode`, skills/prompts are readable too.
- No size cap (it streams), no text/binary handling, no UTF-8 handling; the MIME comes from the extension.
- Boundary = `location.directory`, which comes from the request. See next point.
- Siblings of the same family are far more open: `fs.list` explicitly "includes parents and siblings outside its directory" and takes an absolute `path` (`fs.ts` description + `filesystem.ts` `list`: `path.resolve(location.directory, input.path ?? ".")` with no containment check); `fs.write` (`POST /api/experimental/fs/write`) writes ANY absolute path ("Unlike read, the target is not confined to the location").

### The boundary can be spoofed through the existing proxy (pre-existing, important)

Because `injectDirectory` keeps a client-supplied `directory` query param, and the client headers are copied through, any caller that can reach `http://127.0.0.1:<tomcat>/servoy_ai/rest_api/fs/read/...` can send `?directory=C:\Users\<me>` (or `x-opencode-directory`) and have opencode treat the home folder as the location. Then `fs.read` contains-check passes against the attacker-chosen root. Combined with `fs/list` (absolute `path`) and `fs/write` (any path), the proxy already exposes read/list/write outside the workspace to any local process or any web page that can talk to the loopback Tomcat port (no auth on the BFF; CORS does not stop simple GETs from being sent, and DNS rebinding is not blocked; I did NOT test this live). This is exactly what criterion 4 ("also when asked for outside the UI's own buttons") forbids. It is not in this ticket's text but it is in its scope, because the new reader must not sit next to an open back door. I did not verify against a running server (Servoy Developer not running in this triage); everything above is from source and is marked **unverified live**.

### What exactly is the "workspace"

`OpenCodeUtil.getActiveProjectPath()` (`OpenCodeUtil.java:46-67`, used by `ServicesProvider` for the servlet's `projectPathSupplier`): the active Servoy solution project directory, **walked up to the nearest `.git` directory** (`findGitRoot`), else the project dir. So today the opencode "directory" and the agents' working root is the git root, and `handoff/<task>/spec.md` (the repo-root `handoff/` mentioned in `ServoyGitServer` gitAdd docs) lives there too.

Consequences:

- The root can contain other solutions/modules of the same repo, `.git/`, `node_modules/`, build output. Those are inside "the workspace" in the sense the agents work on, but `.git/` (config with remote URLs/tokens, objects) must not be readable by this feature.
- If no `.git` exists above the solution, root = the solution project dir. If the user's home itself is a git repo (a `~/.git` exists), the root becomes the home folder and then `~/.servoy/opencode` (skills, prompts, `opencode.json` with MCP auth token / `GENAI_API_KEY` env reference) is INSIDE the root. That edge case needs an explicit denylist, not only a root check.
- The skills/prompts live in `~/.servoy/opencode/` (config dir, `OpenCodeUtil.getServoyOpencodeConfigDir`). "Agent scratch" location is not defined anywhere I could find in this repo (grep for scratch/handoff in the opencode bundle: nothing relevant); see question 2.

### How the UI can find the paths (and why the server must not trust it)

Existing building blocks in the webui: `part-utils.ts` (`toolSubtitle` reads `state.input.filePath ?? path`, line 273-282; `isServoyInternalRead` hides `.servoy` reads), `agent-task.component` files slot (`files = input<AgentFile[]>`, chips are inert `<span class="chip">` today, `agent-task.component.html:11-15`), `message-item`, `chat-store`.

Candidate sources (all untrusted hints):

1. **Tool parts of the Orchestrator's messages**: `write`/`edit` tool `state.input.filePath`; `apply_patch` has a patch text with `*** Add File:`/`*** Update File:` lines. 2.4.1 input shapes are not verified live. Most Servoy agents write through MCP tools (`eclipse-coder_createFile`, `replaceString`, ... and Code Mode `execute` scripts); the path is then inside a tool input or a script string, so a generic `filePath`/`path` extraction will miss some. Needs a name-based allowlist of tools plus script scanning, or a fallback source.
2. **Text of the Orchestrator message**: paths such as `handoff/<task>/spec.md`, `review.md` mentioned in prose (regex on `handoff/.../(spec|review).md`). Fragile (wording changes), but the ticket's example (chip "Spec · amended with your decision", "review.md (verdict PASS)") implies the labels come from message context.
3. **Files changed by subagents**: in the child sessions' tool parts (agent view chips), not in the Orchestrator message. The Orchestrator's "work is done" message would need the union of its children's changes, which needs the child sessions loaded. Alternative: the BFF asks git for changed files (`git status`/diff vs. a baseline). Heavier; mention only as a fallback.

Whatever the UI derives, the **server revalidates every request independently** (path shape, containment, denylist). The UI never sends a root, and the reader must never be an open `fs/read` passthrough.

## Ticket premise check

- "The API service can already read a file (`readFile`)" is true but misleading. That route is a raw proxy to opencode `fs.read` with a spoofable `directory`, no denylist, no size cap, no binary handling, and it also exposes `fs.list` (outside the root) and `fs.write` (anywhere). Building the reader on it would not satisfy criterion 4 for requests made outside the UI.
- Criterion 5 ("no longer in the workspace") and criterion 4 ("refused") need different outcomes; opencode folds both into one `FileNotFoundError`. Fine for no-oracle, but the BFF route can keep the distinction (404 vs 403) without leaking paths.
- Criterion 3 ("shows the new content when reopened") means no client-side caching of file content: open = fetch, `Cache-Control: no-store`. Live-refresh while open is not required.
- Criterion 1 "code as monospace": plain `<pre>` is enough; the existing `markdown-renderer` (marked + DOMPurify + highlight.js) handles markdown. Check that markdown links/images from a workspace file cannot trigger external loads or navigate the embedded browser (links `target`/`rel`, relative images would hit `/servoy_ai/<relative>` and fall to the SPA fallback; decide to strip images or leave inert).
- "chip under the message ... when the work is done (frm_customer_overview.js, review.md)": the design mixes labels ("Spec · what we agreed", "verdict PASS") that need either parsing of the message or a convention from the Orchestrator prompt; see question 3.

## Approaches considered

1. **Own BFF route that reads from disk (recommended).** New `GET /servoy_ai/file?path=<workspace-relative>` handled by the servlet (not proxied), delegating to a new small pure class (e.g. `WorkspaceFileReader` in `...opencode.tomcat`) so it is unit-testable with plain JUnit and a temp directory. Root comes ONLY from `projectPathSupplier`. Details:
   - Method GET/HEAD only. No active solution -> 409 (same as `handleApi`). No readiness gate needed (does not touch opencode).
   - Path input: one URL decode only (what the servlet container already did for `getParameter`); never decode again. Reject: empty, NUL/control chars, absolute paths, drive letters (`C:`), UNC (`\\`, `//`), `\\?\`/`\\.\` prefixes, `:` anywhere (NTFS alternate data streams `file.txt:stream`), `..` segments after normalising both `/` and `\`, segments ending in `.` or space (Windows silently strips them), and names that are reserved device names (`CON`, `NUL`, `COM1`...). These lexical rejections happen BEFORE touching disk -> 403 regardless of whether the target exists (no oracle).
   - Resolve `root.toRealPath()` once per request and `root.resolve(rel).toRealPath()` for the target; require `real.startsWith(rootReal)` using `Path.startsWith` (segment-aware, case-insensitive on Windows; do NOT use string prefix, `/ws` vs `/ws-evil`). `toRealPath` expands 8.3 short names and follows symlinks/junctions, so a link that leaves the root is refused (403). Then denylist on the real relative path: `.git` segment (case-insensitive, also a `.git` file for worktrees), and an explicit refusal of anything under `~/.servoy` (resolved from `user.home`, real path) in case the root is an ancestor of home. Re-check `Files.isRegularFile(real, NOFOLLOW_LINKS)`; directories, devices, pipes -> 404/403.
   - TOCTOU: resolve and open the real path; compare the opened path again (or accept the residual race and document it; the attacker would need write access to the workspace).
   - Missing in root -> 404 `{"error":"not_found"}` (UI shows "This file is no longer in the workspace."). Outside/denied/symlink escape -> 403 `{"error":"forbidden"}`. Neither echoes a resolved path or the root. Generic messages only; log details with `ServoyLog`.
   - Size cap (suggest 2 MiB; truncate with a flag or 413; decide), binary detection (NUL byte in the first 8 KiB, or strict UTF-8 decode via `CharsetDecoder` with `REPORT`) -> 415 with `{"error":"binary"}` or show "binary file" in the UI; BOM handling; line endings untouched.
   - Response JSON: `{ "path": "<workspace-relative, forward slashes>", "name": "...", "size": n, "modified": epochMillis, "content": "..." }`. Headers `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Type: application/json;charset=UTF-8`. Never serve raw file bytes with a guessed MIME (stored-XSS in the same origin as the chat app).
   - Hardening to decide: reject a request whose `Host` header is not the loopback/known host, or whose `Origin`/`Sec-Fetch-Site` is cross-site (DNS rebinding, CSRF-style reads from a web page). Cheap and fits the spec of "asked for outside the UI's own buttons".
   - Pros: boundary under our control and unit-testable; the denylist, size cap and error contract are ours; independent of opencode pin bumps (an `fs.read` semantic change cannot weaken it). Cons: new Java code to maintain; reimplements containment (but that is the part we must own anyway).
2. **Use opencode `fs.read` via the proxy, harden the proxy.** Strip client `directory` query and `x-opencode-directory` header always (BFF decides the directory), then call `rest_api/fs/read/<rel>`. Pros: less code, opencode already does lexical + realpath containment and uniform not-found. Cons: no denylist (`.git`, skills if home is the root), no size/binary control, 404-only (UI cannot distinguish refusal), correctness depends on opencode internals that change per release (the project pins and break-checks every bump for exactly this reason), and `fs/list`/`fs/write` remain exposed unless also blocked. Acceptable only as a fallback.
3. **Both: own route for the reader, and close the proxy.** The BFF refuses `rest_api/fs/**` outright (403) because no UI code uses it (verified: only specs reference `readFile`/`findFiles`), and always overwrites/strips a client-provided `directory` query param and `x-opencode-directory` header on every proxied request. Pros: removes the pre-existing exposure that makes criterion 4 unachievable. Cons: slightly wider change than the UI feature; need to confirm no webui call or opencode feature legitimately passes `directory` (verify with a grep over `webui/src` at spec time; expected none, the front-end "never carries directory state" per AGENTS.md). **Recommended together with approach 1.**
4. **No code change.** Rejected: all five criteria are unmet, and the current proxy exposure is a standing finding regardless.

## Recommendation

PROCEED with approach 1 + 3.

Spec points:

- **Server (Java):** `WorkspaceFileReader` (pure, takes `root` + relative request path, returns a result type with OK/NOT_FOUND/FORBIDDEN/BINARY/TOO_LARGE) used by a new route in `OpencodeChatServlet.service()` (`/file`, next to `isSolutionRequest`), plus the proxy hardening (block `rest_api/fs/**`; strip `directory`/`x-opencode-directory` from client input). Keep the servlet constructor signature; the route reuses `projectPathSupplier`.
- **Security test matrix (plain JUnit, `plainjunit.tomcat`, `@DisplayName` on methods only, temp dir fixture, no silent skips):** normal file OK; `..` in all spellings (`..\`, `../`, `%2e%2e`, double-encoded `%252e%252e` must NOT be decoded a second time and resolves to a literal name that does not exist -> 404, not a traversal); absolute POSIX/Windows paths; drive letters; UNC; `\\?\` and `\\.\`; alternate data stream `a.md:x`; trailing dot/space; case variants of the root and of `.git`; 8.3 short names pointing outside (Windows only, create real short names or skip is NOT allowed: make the test platform-conditional via `@EnabledOnOs` is a skip, so write it so it runs everywhere or assert the lexical behaviour); symlink/junction inside root pointing outside (junction via `mklink /J` through `ProcessBuilder` on Windows, `Files.createSymbolicLink` elsewhere; fail loudly if it cannot be created); symlink inside root pointing inside root (allowed); sibling prefix dir (`ws-evil` vs `ws`); NUL byte; directory path; missing file -> 404; `.git/config` -> 403 even when missing; `~/.servoy/...` refused even when root is an ancestor of home; oversize; binary; invalid UTF-8; no active solution -> 409; method POST -> 405; response never contains the root or resolved path; 403 body identical for existing and non-existing forbidden targets.
- **Webui:** `OpencodeApiService.readWorkspaceFile(path)` calling `file?path=` (relative URL, same as other calls), replacing the unused `readFile`/`findFiles` proxy wrappers (remove them so nobody builds on them); a `file-reader` component (right slide-in panel with backdrop: close button, backdrop click, Escape, focus trap and focus return, `role="dialog"` `aria-modal`, `aria-label`), markdown via the existing `svy-markdown`, code in `<pre>`, header with file name, workspace path and a "read-only" pill, states loading / not-found ("This file is no longer in the workspace.") / forbidden ("This file cannot be opened.") / binary / too large / error; state in a small signal service (`open(path)`, `close()`), no content cache so reopening refetches (criterion 3). The conversation stays untouched because the panel is an overlay (no scroll/pin changes in message-list).
- **Chips:** new `file-chip` component (button, not span); `AgentFile` gets `path` (already has `name`, `path?`); the existing empty `data-slot="agent-files"` in `agent-task` becomes interactive; a pure util `services/file-refs.ts` derives refs from (a) tool parts with `filePath`/`path` for an explicit tool allowlist (`write`, `edit`, `apply_patch` parsed, plus the Servoy MCP file tools) and (b) a conservative regex on the Orchestrator text for `handoff/<task>/(spec|review).md`, with unit tests for paths with spaces and Windows separators; labels ("Spec", "amended") only from what the message actually states. Chips under the Orchestrator message and in the agent view reuse the same util (agent view: from the child's own messages).
- UI-side validation is only for UX (hide chips that are clearly not workspace-relative); the server is the authority. A path that is absolute and inside the workspace should be accepted by converting it to relative on the server (`root` is only known there); outside -> 403 like any other.
- Open `AGENTS.md` bullet and the `OpencodeChatServlet` Javadoc need updating (new `/file` route, blocked `rest_api/fs`, directory override stripped). Version pin/break check is not affected (no opencode change).

Files expected to change:

- Java: `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/tomcat/OpencodeChatServlet.java` (route, proxy hardening), new `.../tomcat/WorkspaceFileReader.java`; tests in `tests/com.servoy.eclipse.opencode.tests/src/test/java/.../plainjunit/tomcat/` (new `WorkspaceFileReaderTest`, additions to `OpencodeChatServletTest`/`OpencodeChatServletGuardTest`).
- Webui (new): `components/file-reader.component.{ts,html,scss,spec.ts}`, `components/file-chip.component.*`, `services/file-reader.service.ts`(+spec), `services/file-refs.ts`(+spec).
- Webui (edited, small): `services/opencode-api.service.ts` (+spec; remove `readFile`/`findFiles`, add the new call), `components/agent-task.component.{ts,html,scss,spec.ts}` (chip button; its spec currently asserts the slot has no buttons/anchors at line 88 and must change), `components/message-item.component.*` (chips under the Orchestrator message), `components/agent-view.component.*` (feed files into the slot), `models/opencode.models.ts` (`AgentFile`/`FileMatch`), `app.component.html` (one line to host `<svy-file-reader>`), `chat-store.service.ts` only if the reader state is kept there (prefer a separate service).
- Parallel work with SVY-21847 (welcome screen: `message-list`, `composer`): this case does not need either. Keep `app.component.html` to a single added element and do not touch `message-list.*` or `composer.*`. `message-item` and `app.component.*` are shared hotspots; keep edits minimal there and rebase before commit.

Open questions for the reporter / spec (defaults stated):

1. Workspace boundary: the opencode directory = git root (else the solution project folder). Default: the root = `OpenCodeUtil.getActiveProjectPath()` (git root), minus `.git` and `~/.servoy`. Alternative: restrict to the active solution folder only (stricter, but `handoff/` at the repo root and other modules in the same repo would be unreachable). Which one?
2. Where does "agent scratch" live (a path or a naming convention under `handoff/`)? It must be added to the denylist; I could not find it in this repo.
3. Chip labels ("Spec · what we agreed", "amended with your decision", "verdict PASS"): are they to be produced by the Orchestrator prompt (a convention we parse) or computed by the UI (e.g. from the file name and content)? Default: UI shows the file name plus a generic caption (Spec / Review / Changed file) derived from the path; no parsing of "verdict".
4. Which changed files get chips: only files written by the Orchestrator itself, or the union of its agents' changes? Default: tool parts of the Orchestrator's message plus those of its child sessions that are already loaded; no git-based discovery.
5. Size cap and binary handling (default 2 MiB, text only, "binary file" notice).
6. Is blocking the whole `rest_api/fs/**` and stripping `directory`/`x-opencode-directory` from client requests acceptable in this case (default yes; it is the only way to meet "also asked outside the UI's buttons")?

## Not verified

- Nothing was run against a live Servoy Developer / opencode 2.4.1 server (not running here). The opencode behaviour above comes from the source in `D:\git\opencode` branch `v2` HEAD `2f3ba04a8b` (not the exact `v2.4.1` tag, which is absent in that checkout) and the 2.4.1 break-check doc.
- That the `directory` query override and a header override really work through the BFF in practice (source says yes: `injectDirectory` early return and `copyRequestHeaders`).
- Which header wins when `x-opencode-directory` appears twice (client copy + BFF added).
- Whether `FSUtil.contains` is case-insensitive on Windows.
- Tool input shapes of `apply_patch`/MCP file tools in 2.4.1, and whether Orchestrator messages reliably mention `handoff/<task>/spec.md`.
- The design HTML was only searched for text strings: the reader has a header with name + owner pill "read-only · {{doc.owner}}", a close button (`aria-label="Close file"`, 40x40) and file chips (`.file` button, 44px high under the message, 36px high in the agent view). I did not extract the exact visual tokens; map them to the existing `--svy-*` tokens in the spec phase.
- Whether Windows 8.3 short-name and junction cases behave as expected in `toRealPath` on the CI agents (Jenkins build runs plain tests; symlink creation may need privileges on Windows, junctions do not).

## Git history findings

- `OpencodeChatServlet` directory injection and proxy: introduced with the BFF; `injectDirectory` never stripped a client value (still true on master `d977469`).
- `OpenCodeUtil.getActiveProjectPath` walking up to the git root: defines the current workspace root for opencode and the agents.
- `7888282` "Show only the file name for internal .servoy file reads" (`isServoyInternalRead`): shows that `.servoy` files are already treated as internal in the UI, consistent with refusing them server-side.
- `8c1fc0e`/`d977469` SVY-21850 added the empty `data-slot="agent-files"`; `a3776b1` SVY-21849 cards; `633c68d` opencode 2.4.1 pin and break check (fs endpoints unchanged).
- No earlier commit implements a file reader, a `/file` route or any deny/containment logic in the BFF.

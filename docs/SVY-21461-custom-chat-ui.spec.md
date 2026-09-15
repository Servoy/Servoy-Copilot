# Spec: SVY-21461 — Replace opencode web view with a custom simplified chat UI on top of opencode-cli

## 1. Goal

Today the Servoy AI Copilot (`com.servoy.eclipse.opencode`) embeds the third-party **opencode web
front-end** in a browser view and rewrites it at runtime with brittle DOM/CSS injection
(`OpenCodeBranding`) and private-URL/session scraping (`OpenCodeView`). This spec replaces that with
a **Servoy-owned Angular chat UI** we fully control, built directly on top of the documented
**opencode-cli HTTP + SSE API**. The Angular app is served from the Servoy Developer's embedded
Tomcat through a Java servlet registered via the existing `IServicesProvider` extension; that servlet
acts as a backend-for-frontend (BFF) that proxies opencode's REST + SSE endpoints. The existing
`OpenCodeView` continues to host the UI via `IBrowser`, but points at the servlet URL instead of the
opencode SPA, and the injection/scraping hacks are retired. First iteration delivers a clean chat
view (auto-growing input, nicely formatted responses), file/image attachments, new-session support,
a list of existing sessions, all scoped to the single project directory Servoy provides. Model
selection is out of scope.

## 2. Background

### 2.1 Current architecture (what is being replaced)

- `OpenCodeView` (`bundles/com.servoy.eclipse.opencode/.../OpenCodeView.java`) is a singleton
  `ViewPart` that creates an `IBrowser` (`BrowserFactory.createBrowser`) and drives it through a
  five-state machine (login → configured → dev override → active solution → server ready). On every
  `LocationEvent.changed` it re-injects `OpenCodeBranding.buildInjectScript()` (`:104`) and re-seeds
  opencode's Home "opened projects" list (`seedOpenedProjectsIfNeeded`, `:125`).
- Navigation depends on opencode's **private SPA URL shape** — a base64-encoded directory plus
  `/session[/{id}]?directory=…` (`resolveSessionUrl`, `:374`) — and on **scraping** the first `"id"`
  out of the `/session` JSON with `indexOf` (`findLastSessionId`, `:386`).
- `OpenCodeBranding.java` (~281 lines) is almost entirely `!important` CSS overrides keyed to
  opencode's internal `--v2-blue-*` token scale plus JS that hides tool wrappers by
  `[data-component="tool-part-wrapper"]`. Every one of these breaks when opencode restyles.

These are workarounds for not owning the front-end. They are exactly what the ticket asks to remove.

### 2.2 opencode server lifecycle (what stays and is reused)

- `RunOpencodeCommand` launches `npm exec -- opencode serve --port <p> --hostname 127.0.0.1`, scanning
  upward from `DEFAULT_PORT = 4096` for a free port (`findFreePort`, `:237`), redirecting XDG dirs to
  `~/.servoy/` (`buildServoyXdgEnv`, `:177`), and setting `PWD` to `OpenCodeUtil.getActiveProjectPath()`.
  A watchdog polls `http://127.0.0.1:<port>/` and calls `Activator.serverStarted(port)` when it responds.
- `Activator` tracks readiness via `OpencodeServerState` (a `CountDownLatch` + port). `waitForServer`,
  `getServerPort`, `isServerReady`, and `ensureServerStarting()` are the coordination surface.
- `OpenCodeUtil.getActiveProjectPath()` resolves the active Servoy solution's project location and
  walks up to the git root — this is the **single directory** the whole view is scoped to.

All of this is retained unchanged. The new servlet talks to the same `127.0.0.1:<port>` opencode server.

### 2.3 opencode HTTP + SSE API (the contract the BFF proxies)

From `opencode.ai/docs/server` (OpenAPI 3.1, spec at `GET /doc`). Relevant endpoints for iteration 1:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/session` | List sessions |
| `POST` | `/session` | Create a session (`{ parentID?, title? }`) |
| `GET` | `/session/:id` | Session details |
| `GET` | `/session/:id/message?limit=` | List messages (`{ info: Message, parts: Part[] }[]`) |
| `POST` | `/session/:id/message` | Send message, wait for full response (`{ messageID?, model?, agent?, parts }`) |
| `POST` | `/session/:id/prompt_async` | Send message, no wait (`204`); output arrives on the event stream |
| `POST` | `/session/:id/abort` | Abort a running turn |
| `GET` | `/event` | SSE bus stream; first event `server.connected`, then message/part deltas |
| `GET` | `/find/file?query=&directory=` | File search (for attachment picker) |
| `GET` | `/file/content?path=` | Read a file |

Notes established by the OpenChamber reference and the docs:
- opencode-cli is directory-scoped via a `directory=` query param and/or an `x-opencode-directory`
  header on requests. Iteration 1 always uses the single Servoy project directory.
- Live assistant output is streamed as **SSE part deltas on `/event`**, not returned inline from
  `prompt_async`. The UI subscribes to `/event`, filters events for the active session, and appends
  text parts as they arrive. `POST /session/:id/message` (blocking) is the simpler alternative but does
  not stream; iteration 1 uses `prompt_async` + `/event` to get progressive rendering.
- Auth: opencode `serve` can be protected with `OPENCODE_SERVER_PASSWORD` (HTTP basic). The Servoy
  instance today runs unprotected on loopback; the servlet nonetheless centralises any auth header
  injection so this can change without touching the Angular app.

### 2.4 OpenChamber as reference only (do not embed)

OpenChamber (`C:\Users\jcomp\git\openchamber`) validates this exact shape with a Node BFF instead of Java:
- `packages/web/server/lib/opencode/proxy.js` — the proxy: a generic `/api/*` → opencode passthrough,
  plus **dedicated handlers** for the streaming and list routes. Patterns worth copying (not code):
  - `forwardSseRequest` (`:461`) streams `/event` with heartbeats, backpressure
    (`writeSseChunkWithBackpressure`), an upstream-stall timeout, and `X-Accel-Buffering: no` +
    `setNoDelay(true)` so chunks are not buffered. The Java servlet must do the equivalent with
    async I/O (`AsyncContext`, flush per chunk).
  - session-list forwarding sanitises the payload to an allow-listed set of fields
    (`SESSION_LIST_ALLOWED_FIELDS`, `:213`) and strips heavy `summary.diffs` — a good default for our
    list endpoint too.
  - a **readiness gate** that *holds* a request while opencode is still starting rather than 503-ing
    immediately (`:701`), avoiding client backoff storms on cold start.
- `packages/web/src` + `packages/ui/src/components/chat` — the React chat rendering: `ChatMessage.tsx`,
  `MessageList.tsx`, `MarkdownRendererImpl.tsx`, `ChatInput.tsx`, `FileAttachment.tsx`, and
  `partUtils.ts` (which normalises/filters opencode `Part[]` — text vs. tool vs. synthetic/system-reminder
  parts). Use these as a **guide** to how message parts map to rendered blocks; reimplement in Angular.

These are large React/Vite apps with a Node server and many out-of-scope features (relay, tunnels,
mobile, guests). We reference their **shapes**, we do not import their code.

### 2.5 IServicesProvider Tomcat extension (the deployment mechanism)

The Tomcat that runs inside Servoy Developer collects `IServicesProvider` extensions at bundle start
(`org.apache.tomcat.Activator.start`, `:66-84`) and, when configuring a context, calls
`getAnnotatedClasses(ctx)` and `getServletInstances(ctx)` on each provider
(`org.apache.tomcat.Activator.java:105-142`). The interface
(`servoy-eclipse-tomcat/org.apache.tomcat/.../IServicesProvider.java`) has three default methods:

```java
default void registerServices() {}
default Set<Class<?>> getAnnotatedClasses(String context) { return null; }
default Set<ServletInstance> getServletInstances(String context) { return null; }
```

Two registration styles exist in the codebase:

1. **Annotated class** (`@WebServlet` / `@WebFilter`) returned from `getAnnotatedClasses`. Used by
   `servoy_mcp` (`McpServlet`), `ngclient.ui` (`IndexPageFilter`, a `@WebFilter(urlPatterns={"/*"})`),
   and `designer.rfb` (`ResourcesServlet`, `EditorEndpoint`, …). Only the **root context** `""` is
   handled: every provider guards with `if ("".equals(context))`.
2. **`ServletInstance` record** — `record ServletInstance(HttpServlet servletInstance, String urlPattern)`
   (`ServletInstance.java`) — returned from `getServletInstances(context)`. This lets a provider hand
   Tomcat an **already-constructed servlet instance** with a URL pattern, which is what we need because
   our servlet must hold a reference to the opencode `Activator`/port at runtime (an annotated class is
   instantiated by the container with a no-arg constructor and cannot easily reach bundle state).

**Decision:** use `getServletInstances("")` returning one `ServletInstance` for the BFF servlet mapped
to a dedicated URL pattern (see §3.4). Register the provider through the
`org.apache.tomcat.serviceprovider` extension point in `plugin.xml`. This keeps the servlet
instance-controlled (it can capture the port supplier) and confined to the root context, consistent
with `servoy_mcp`.

## 3. Design

### 3.1 Component overview

```
Eclipse JVM (Servoy Developer)
┌──────────────────────────────────────────────────────────────────────┐
│  com.servoy.eclipse.opencode bundle                                    │
│                                                                        │
│  OpenCodeView (IBrowser)  ──setUrl──▶  http://localhost:<tomcat>/       │
│                                         servoy_ai/  (Angular app)       │
│                                                                        │
│  ServicesProvider (IServicesProvider)                                  │
│    └─ getServletInstances("") ▶ ServletInstance(OpencodeChatServlet,   │
│                                    "/servoy_ai/*")                      │
│                                                                        │
│  OpencodeChatServlet (BFF)                                             │
│    • serves Angular dist/ static assets from the bundle                │
│    • proxies /servoy_ai/rest_api/** ─────────┐                              │
│    • streams  /servoy_ai/rest_api/event (SSE)│                              │
│                                          ▼                             │
│  Activator / OpencodeServerState  ── port + readiness ──▶ opencode      │
│  RunOpencodeCommand ── launches ──▶  opencode serve @ 127.0.0.1:<port> │
└──────────────────────────────────────────────────────────────────────┘
```

The Angular app is pure static content shipped in the bundle jar; all dynamic traffic goes through the
servlet, which is the single point that knows the opencode port and injects the project `directory`.

### 3.2 Angular front-end (`webui/` subdirectory of the bundle)

- **Location:** `bundles/com.servoy.eclipse.opencode/webui/` — an independent Angular workspace
  (`package.json`, `angular.json`, `tsconfig`). Its production build output goes to
  `bundles/com.servoy.eclipse.opencode/webui-dist/` (see §3.6 for build wiring), which is included in
  the bundle via `build.properties`.
- **Framework:** Angular (standalone components, current LTS matching what Servoy already builds
  elsewhere). Keep dependencies minimal: a markdown renderer (e.g. `marked` + a sanitizer) and a
  syntax highlighter for code blocks. No opencode/OpenChamber packages.
- **Routing:** the app is served under a base href of `/servoy_ai/` (matches the servlet mount). All
  API calls are relative to that base (`./rest_api/...`) so the app is location-independent.
- **Views/components (iteration 1):**
  - `ChatView` — the main surface: a scrollable message list above an auto-growing composer.
  - `MessageList` / `MessageItem` — renders each message's `Part[]`. Text parts → markdown; tool parts
    → a compact collapsed row (label only in iteration 1; body optional/collapsed); reasoning parts →
    optional muted block. Synthetic/`<system-reminder>` parts are filtered out (mirror
    OpenChamber `partUtils.ts` filtering rules).
  - `MarkdownRenderer` — renders assistant text as sanitized HTML with code-block highlighting.
    Streaming-safe: re-renders as text grows.
  - `Composer` — a `textarea` that auto-grows with content (capped max-height, then scrolls);
    Enter sends, Shift+Enter newline; a send/stop button (stop calls abort). Disabled while a turn is
    streaming until first token, then shows stop.
  - `AttachmentBar` — file/image attach button + drag-drop + paste-image. Selected files are shown as
    chips; on send they are included as message parts (see §3.3).
  - `SessionList` — a panel/drawer listing existing sessions (title + relative time), a "New session"
    action, and selection to switch the active session.
- **State/services:**
  - `OpencodeApiService` — typed wrappers over the servlet's `./rest_api/**` endpoints (list/create session,
    list messages, send prompt, abort, file search, read file).
  - `EventStreamService` — opens an `EventSource` on `./rest_api/event`, parses opencode bus events, and
    dispatches part/message deltas to the active session store. Reconnects on drop.
  - `ChatStore` — holds the active session id, ordered messages, streaming state. Seeded from
    `GET /session/:id/message` on session open, then updated live from the event stream.
- **Project scope:** the app never asks the user for a directory. The servlet injects the single
  project directory server-side (§3.4), so the front-end is inherently single-project.
- **Theming:** match the Servoy Developer look (orange accent) natively in the app's own CSS — no
  runtime injection. This replaces everything `OpenCodeBranding` did.

### 3.3 Message send + streaming flow

1. User types, optionally attaches files, presses send.
2. Front-end ensures an active session (create via `POST ./rest_api/session` if none), then calls
   `POST ./rest_api/session/:id/prompt_async` with `parts` = `[{ type: 'text', text }, …fileParts]`.
   File/image attachments are sent as file parts referencing paths under the project (picked via
   `./rest_api/find/file`) or as inline content for pasted images, following the opencode message-part
   schema. (Confirm exact file-part shape against `GET /doc` at implementation time — see Open questions.)
3. The already-open `./rest_api/event` SSE stream delivers `message.updated` / `message.part.updated`
   (or equivalent) events for that session id; `EventStreamService` appends/updates parts and the
   `MessageList` re-renders progressively.
4. Stop button → `POST ./rest_api/session/:id/abort`.

Rationale for `prompt_async` + `/event` over blocking `POST /session/:id/message`: progressive
rendering (the ticket asks for a chat that streams like OpenChamber). The blocking endpoint remains a
fallback if streaming proves problematic in iteration 1.

### 3.4 Java BFF servlet (`OpencodeChatServlet`)

A single `HttpServlet` mapped at `/servoy_ai/*`, handed to Tomcat as a `ServletInstance`. Responsibilities:

- **Static asset serving.** For non-`/rest_api/` paths under `/servoy_ai/`, serve the Angular `dist` from
  the bundle (`Bundle.getEntry("/webui-dist/…")` / `FileLocator`). Unknown paths fall back to
  `index.html` (SPA deep-link support). Set correct content types and long-cache headers for hashed
  assets. This mirrors how `ngclient.ui` serves an Angular `dist` `index.html`, but from the bundle
  rather than a solution folder.
- **API proxy.** For `/servoy_ai/rest_api/**`, forward to `http://127.0.0.1:<port>/<rest>` where `<port>`
  comes from `Activator.getServerPort()` (resolved lazily per request, since the port is only known
  after startup). Copy method, relevant request headers, and body; copy back status, headers, body.
  Use `java.net.http.HttpClient` (JDK 21) with a connection pool / keep-alive.
- **Project-scope injection.** On every proxied API request, inject the single project directory —
  add `directory=<OpenCodeUtil.getActiveProjectPath()>` as a query param and/or the
  `x-opencode-directory` header — so the front-end never carries directory state. If no active
  project, respond `409`/`503` with a small JSON body the UI shows as "no active solution".
- **Readiness gate.** If `!Activator.isServerReady()`, hold the request briefly (poll
  `waitForServer` with a bounded deadline) before returning `503 {restarting:true}`. Mirrors
  OpenChamber's readiness hold to avoid client backoff on cold start.
- **SSE streaming** for `/servoy_ai/rest_api/event`: switch the request to async
  (`req.startAsync()`), open a streaming `HttpClient` request to `/event`, and copy upstream chunks to
  the response **flushing after each write**, with `Content-Type: text/event-stream`,
  `Cache-Control: no-cache`, `X-Accel-Buffering: no`. Send periodic heartbeat comments to keep the
  connection alive; abort the upstream when the client disconnects. Model behaviour on
  `forwardSseRequest` in the OpenChamber reference (heartbeats, stall timeout, backpressure).
- **Auth passthrough.** Inject the opencode basic-auth header if/when
  `OPENCODE_SERVER_PASSWORD` is configured (single place to add it later). None today.
- **Threading.** SSE and long proxies must not tie up a single Tomcat worker synchronously — use
  async servlet APIs. The servlet holds no per-request opencode state beyond the port supplier.

#### Why the proxy is required (not optional)

The Angular app is served from the Developer Tomcat (e.g. `localhost:8182/servoy_ai/`); opencode runs
on a **different loopback port** (`127.0.0.1:<4096+>`). The browser hosting the Angular app therefore
cannot call opencode directly without hitting the **same-origin policy** — a cross-origin `fetch`/
`EventSource` from `:8182` to `:<port>` would require opencode to emit permissive CORS headers, which
we do not control and would not want to rely on. Routing everything through `/servoy_ai/rest_api/**`
on the **same origin** as the app avoids CORS entirely. This is doubly true for the SSE stream:
`EventSource` gives no way to set custom headers and is strictly same-origin-friendly, so the
`/event` stream in particular must be proxied. The proxy is thus load-bearing, not a convenience —
it is the same-origin BFF that lets a single origin serve both the static app and the API/SSE surface,
while also being the one place that injects the project `directory` and (future) auth.

### 3.5 ServicesProvider + extension registration

- New class `com.servoy.eclipse.opencode.tomcat.ServicesProvider implements IServicesProvider`,
  returning from `getServletInstances(String context)`, guarded by `if ("".equals(context))`, a single
  `new ServletInstance(new OpencodeChatServlet(...), "/servoy_ai/*")`.
- Register it via `plugin.xml` on the `org.apache.tomcat.serviceprovider` extension point (same point
  the other bundles use). This requires adding a `Require-Bundle` / import on `org.apache.tomcat`
  (the bundle that owns `IServicesProvider`, `ServletInstance`, and `jakarta.servlet.http.HttpServlet`).
- The servlet is constructed with a supplier for the opencode port and the project-path resolver, so it
  reaches bundle runtime state without relying on container no-arg instantiation.

### 3.6 Build wiring (Angular → bundle jar → Tomcat)

The Angular build is a **pure build-time concern**, fully decoupled from the runtime npm/node that
Servoy ships (the runtime npm is only used by `RunOpencodeCommand` to launch opencode — it has nothing
to do with building this UI). By the time the plugin runs in Developer, the Angular app is already a
finished, compiled artifact.

- **Maven `frontend-maven-plugin`** drives the Angular build during the module's `pom.xml`
  `generate-resources`/`prepare-package` phase: it downloads a pinned node+npm into the build's
  `target/`, runs `npm ci` and `npx ng build` (Angular CLI production build) against the `webui/`
  workspace. This is entirely self-contained on the Jenkins/Tycho/mvn build machine and does not touch
  the Servoy runtime node.
- **Only the Angular CLI build output ships in the binary — not the sources.** `ng build` emits to a
  bundle-local directory (e.g. `bundles/com.servoy.eclipse.opencode/webui-dist/`); only that directory
  is added to `build.properties` `bin.includes` (alongside `opencode/`, `resources/`) so it is packaged
  into the plugin jar and reachable via `Bundle.getEntry`. The `webui/` source tree and any
  `node_modules/`/`target/` build scratch are **excluded** from `bin.includes`.
- The Angular workspace under `webui/` is a source folder, not compiled by JDT; ensure it is excluded
  from the Java build path and from SpotBugs. `node_modules/`, `webui-dist/`, and Maven `target/` are
  git-ignored (the dist is a build product, regenerated every build — not committed).
- **Angular base href** is `/servoy_ai/` so the app and all its relative asset/API URLs resolve under
  the servlet mount regardless of the Developer Tomcat port.

### 3.7 OpenCodeView changes (retire the hacks)

- Remove `OpenCodeBranding` usage entirely from `OpenCodeView` (delete the `INJECT_CSS_JS` constant,
  the `LocationListener` that re-injects on `changed`, and `seedOpenedProjectsIfNeeded`/
  `lastSeededWorktree`). Delete `OpenCodeBranding.java` and its tests once nothing references it.
- Remove `resolveSessionUrl`, `findLastSessionId`, and the base64/`directory=` URL construction.
- In state 5 ("all conditions met"), instead of navigating to the opencode SPA URL, navigate the
  `IBrowser` to the **servlet URL**: `http://127.0.0.1:<tomcatPort>/servoy_ai/` (the Developer Tomcat
  port; resolve it the standard way other in-Developer web content is reached). The server-start /
  readiness / no-solution / login / not-configured states are unchanged — only the final target URL
  and the removal of injection differ.
- Keep the `IBrowser` abstraction rule: never touch Chromium/SWT `Browser` directly; only
  `setUrl`/`setText`/`execute`/`addBrowserFunction` on `IBrowser`. No `BrowserFunction` is needed for
  iteration 1 (all communication is HTTP between the Angular app and the servlet).

## 4. Implementation plan

1. **Scaffold the Angular app** at `bundles/com.servoy.eclipse.opencode/webui/` with base href
   `/servoy_ai/`, standalone components, a markdown renderer + sanitizer, and a code highlighter.
   Configure production build output to `../webui-dist`.
2. **Build the chat UI** components: `ChatView`, `MessageList`/`MessageItem`, `MarkdownRenderer`,
   `Composer` (auto-grow textarea, Enter/Shift+Enter, send/stop), `AttachmentBar`, `SessionList`.
   Reference OpenChamber `packages/ui/src/components/chat` for part→block mapping and
   `partUtils.ts` filtering, reimplemented in Angular.
3. **Implement front-end services:** `OpencodeApiService` (session CRUD, messages, prompt_async,
   abort, file search/read), `EventStreamService` (`EventSource` on `./rest_api/event`, reconnect),
   `ChatStore` (active session, messages, streaming state).
4. **Add `org.apache.tomcat` to the bundle manifest** (`Require-Bundle` or package imports for
   `IServicesProvider`, `ServletInstance`, `jakarta.servlet.http`).
5. **Write `OpencodeChatServlet`** (`com.servoy.eclipse.opencode.tomcat`): static asset serving from
   `webui-dist` with SPA fallback; `/rest_api/**` proxy to `127.0.0.1:<port>` with directory injection and
   readiness gate; async SSE streaming for `/rest_api/event` (heartbeats, client-disconnect abort);
   auth passthrough hook.
6. **Write `ServicesProvider`** returning the `ServletInstance("/servoy_ai/*")` for the root context,
   and register it in `plugin.xml` on `org.apache.tomcat.serviceprovider`.
7. **Rework `OpenCodeView`**: drop `OpenCodeBranding`/injection/URL-scraping; navigate `IBrowser` to
   the Developer-Tomcat servlet URL `…/servoy_ai/`; keep the login/config/no-solution/readiness states.
8. **Delete** `OpenCodeBranding.java` and update/remove its tests
   (`com.servoy.eclipse.opencode.tests`).
9. **Build wiring**: add `webui-dist/` to `build.properties` `bin.includes`; add the Angular
   production build to `pom.xml`; exclude `webui/` from the Java build path and SpotBugs.
10. **Tests**: unit-test the servlet's URL/route classification, directory injection, and readiness
    gate with plain JUnit in `com.servoy.eclipse.opencode.tests` (mock the port supplier and upstream).
    Add a **Vitest** suite in `webui/` for the Angular front-end — part-utils filtering, the REST API
    service (HttpTestingController), the SSE event stream (fake EventSource), the chat store, markdown
    sanitization, and composer key handling — with **all opencode REST/SSE calls mocked** so no real
    server is needed. Manual end-to-end in Developer.
11. **Verify** compilation (`eclipse-ide_getCompilationErrors` → zero errors) and run the opencode
    bundle tests.

## 5. Acceptance criteria

- [ ] The Servoy AI view shows a Servoy-owned chat UI (not the opencode web front-end); no
      `OpenCodeBranding` CSS/JS is injected and no opencode SPA URL/session scraping remains.
- [ ] The composer is a text area that auto-grows as the user types (capped, then scrolls); Enter
      sends, Shift+Enter inserts a newline.
- [ ] Assistant responses render as nicely formatted markdown (headings, lists, code blocks with
      highlighting) above the input, updating progressively as the response streams.
- [ ] The user can attach files and images and include them with a message.
- [ ] The user can start a new session.
- [ ] Existing sessions are listed and can be selected/opened in the view.
- [ ] The view is always scoped to the single project directory Servoy provides
      (`OpenCodeUtil.getActiveProjectPath()`); the user is never asked to choose a directory.
- [ ] The Angular app is served from the bundle through a servlet registered via `IServicesProvider`
      on the in-Developer Tomcat; the servlet proxies opencode's HTTP + SSE API on loopback.
- [ ] Cold start (opencode still starting) does not error the UI — requests are held/retried until the
      server is ready.
- [ ] The opencode server lifecycle (`RunOpencodeCommand`, `Activator`, readiness latch) is unchanged.
- [ ] Workspace compiles with zero errors; opencode bundle tests pass.

## 6. Out of scope

- Model selection / switching between models (explicitly deferred by the ticket).
- Multi-project / multiple simultaneous directories (view is single-project by design).
- OpenChamber-only features: relay, tunnels, mobile/PWA, guests, dictation/TTS, scheduled tasks,
  walkthroughs, permission auto-accept UI.
- Rich tool-call inspection UI (diffs, expandable tool bodies) beyond a minimal collapsed indicator.
- Advanced session management (rename, delete, share, fork) beyond list + select + new.
- Embedding or importing any OpenChamber code (reference only).
- **Switching opencode from npm-install to a shipped/downloaded standalone binary** (opencode is a Bun
  standalone executable and could be embedded/downloaded and executed directly, removing the runtime npm
  dependency). This is a worthwhile future direction but is a **separate follow-up ticket** — this
  ticket keeps the existing npm-based install/launch (`OpencodeFolderCreatorJob` + `RunOpencodeCommand`)
  unchanged and only builds the new UI + BFF on top of the already-running server.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Exact opencode message-part schema for **file/image attachments** in `prompt_async` (file part vs. inline base64; path vs. content). Confirm against `GET /doc` at implementation time. | Dev | open |
| How the Angular app reaches the **Developer Tomcat port** from `OpenCodeView` — the canonical way other in-Developer web content resolves the base URL (vs. hardcoding). | Dev | open |
| ~~Maven mechanism to build the Angular app.~~ **Resolved: `frontend-maven-plugin`** in the module `pom.xml` (downloads a pinned node+npm into `target/`, runs `npm ci` + `ng build`). Pure build-time; independent of the runtime npm Servoy ships. Only the `ng build` output (`webui-dist/`) is packaged into the jar — sources are not. | Dev | resolved |
| Whether iteration 1 streams via `prompt_async` + `/event` (progressive) or uses the blocking `POST /session/:id/message` first and adds streaming later. Recommendation: streaming. | Dev | open |
| ~~Whether the servlet should sanitise the session-list payload.~~ **Resolved: pass through verbatim.** "Sanitising" (as OpenChamber does) means stripping heavy/irrelevant fields from `GET /session` — notably large `summary.diffs` blobs — to an allow-list before returning them, purely a payload-size/perf optimisation for their many-clients web app. For our single loopback UI it adds complexity with no meaningful gain, so iteration 1 proxies `/session` verbatim; revisit only if the payload proves heavy. | Dev | resolved |
| Confirm the servlet URL pattern/base href (`/servoy_ai/`) does not collide with any existing root-context mapping in Developer Tomcat. | Dev | open |

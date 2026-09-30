# Form-awareness for the Servoy AI assistant — findings & recommendation

**Status:** Investigation only. No feature code written.
**Enabler:** SVY-21460 (committed in `servoy-eclipse`) — the stateless `/formtemplate/<form>.html` render route.
**This document:** the AI-consumption side (a new Jira issue should be filed once a direction is chosen; SVY-21460 is only the enabler).

## APPROVED APPROACH (decided — this is the spec target)

The open design choices below have been settled. The spec must be built to **exactly this**:

- **Goal:** an AI form-layout **self-correction loop** (see §0). AI-facing, not human-facing.
- **Bridge: C — add a value-returning `evaluate(String)` to `IBrowser`** (+ both wrappers `ChromiumWrapper`/`SwtBrowserWrapper`; the Equo/SWT backend already implements `Browser.evaluate`). Chromium stays confined to `com.servoy.eclipse.ui`. This is a change to `servoy-eclipse` (`com.servoy.eclipse.ui/.../IBrowser.java`), explicitly approved.
- **iframe placement: a HIDDEN utility `IBrowser` the tool drives** — created on demand, loads `/formtemplate/<form>.html`, waits for render-complete, `evaluate()`s the projection, then disposed. **Not** the visible chat view; no dependence on the AI view being open; no interference with the chat UI.
- **Tool return: a STRUCTURED layout projection** (not raw HTML): per component → `type`, `name`, bounding box (x/y/w/h), containing layout-container / responsive nesting, key computed styles (display, visibility, applied style classes), and text. Raw `.svy-form` `outerHTML` only on explicit request/expansion. Apply the SVY-21473 cap-inline / spill-to-temp-file convention for anything large.
- **Jira:** tracked on **SVY-21460** (the enabler case; no separate follow-up issue).
- **Delivery: one shared spec, both pipelines** — the spec covers the Java side (`IBrowser.evaluate`, the MCP tool, the projection JS it injects) and any Angular-side rendering harness, then `/sdd-java` and `/sdd-angular` implement against that single spec so the contract stays consistent.
- **Screenshots: out of scope** for now (the structured projection carries positions/styles). No `ImageContent`/chat-UI image work.

Everything below is the investigation that led here.

---

## 0. The actual goal (decided) — this is for the AI, not the human

The point of this case is a **self-correction loop for the AI**: when the assistant generates or edits Servoy forms (potentially several forms for one feature, in a short burst), it must be able to **see whether the form it just produced matches what it intended, layout-wise**, and fix it before moving on. A human being able to watch the form render is a *nice-to-have side effect*, not the goal.

Two consequences that shape everything below:
1. **A tool (cross-process handoff) is required.** The AI agent runs in a **separate process** (opencode) and cannot execute JS in our webui or touch the rendered iframe — it can only call **MCP tools**. Rendering and inspecting the iframe is pure front-end Angular and needs no backend; the *only* reason backend/tooling enters at all is to carry the rendered result across the process boundary to the agent. If this were for the human, it would be a pure-Angular feature with no tool.
2. **The AI wants a *comparable layout projection*, not a raw DOM dump.** For a fast, repeated "is this what I intended?" check, returning raw `outerHTML` is noisy and token-heavy. The high-value tool result is a **compact, structured layout description** the AI can diff against its intent: per component → type, name, position/size (bounding box), the containing layout container / responsive nesting, key computed styles (visibility, display, applied style classes), and text. Raw DOM/HTML can be an optional expansion. (Reuse the SVY-21473 cap-inline / spill-to-temp-file convention for anything large — §2.6.)

---

## 1. What the enabler actually gives us (recap, verified against the spec)

From `servoy-eclipse/docs/SVY-21460-form-template-render-route.spec.md`, the core now serves, on the Developer's embedded Tomcat (same web port `ApplicationServerRegistry.get().getWebServerPort()` — e.g. `8183`):

- **`GET /formtemplate/<formName>.html`** — a **stateless Angular shell page**. It carries, injected inline:
  - `<script id="svy-formtemplate-formstate" type="application/json">` — the `AngularFormGenerator` form-state JSON (component tree + design-time model props).
  - `<script id="svy-formtemplate-specs" type="application/json">` — per-component client-side specs.
  - a `window.formtemplateName` marker.
  - `<link rel="stylesheet" href="/formtemplate/stylesheet.css">`.
- **`GET /formtemplate/stylesheet.css`** — compiled solution CSS (LESS → CSS), design-time.

**Critical distinction for the AI question:** the `.html` is a **pre-render** page. The real `.svy-form` runtime DOM (`.svy-wrapper` absolute wrappers, nested `.svy-layoutcontainer`, real `<servoydefault-textfield>` tags, computed positions/styles) **only exists after the Angular app boots and runs in a browser**. Fetching the `.html` as text yields the shell plus the JSON blobs — **not** the resolved DOM. This single fact drives the whole recommendation below.

The core spec explicitly parks *"Embedding a browser in the Servoy AI view + AI DOM access/click/screenshot"* as an **out-of-scope follow-up** (spec §6). That follow-up is what this repo owns.

---

## 2. What this repo can already do (cited)

### 2.1 MCP tool mechanism (the likely home for a form tool)
- Tools are `@Tool`-annotated methods on `@McpServer`-annotated classes under `com.servoy.eclipse.developer.mcp/servers/` (e.g. `ServoyTestingServer`).
- `McpServerRegistry` (`McpServerRegistry.java:210-245`) builds one streamable-HTTP servlet per server, mounted on the Servoy Tomcat at `/dev_mcp/<serverName>/`, behind a bearer-token filter.
- `McpEndpointProvider` (`McpEndpointProvider.java:51-75`) implements the opencode extension point `IMcpEndpointProvider`; `McpConfigWriter` (`McpConfigWriter.java:217-304`) merges those URLs into `opencode.json`. So a new tool on an existing server is exposed to the agent **with zero new wiring**.
- **Tool results are text-only today.** `McpServerFactory.executeCallTool` (`McpServerFactory.java:254-262`) wraps `result.toString()` into a single `McpSchema.TextContent`. There is **no image/blob content path** even though the MCP SDK has `ImageContent`. A screenshot tool can therefore only return a **file path** (as `screenshotForm` already does) unless image content is added to the factory.

### 2.2 Existing form-preview / screenshot tooling (reuse vs reinvent)
`FormPreviewService` (`services/FormPreviewService.java`) + `ServoyTestingServer` tools:
- `showFormInBrowser` (`FormPreviewService.java:66`) — opens `/solution/<sol>/index.html?formpreview=<form>` in an **external** browser. Needs the **running NG client** + `servoy.ngclient.testingMode`.
- `screenshotForm` (`FormPreviewService.java:153`) — spins up **bundled Node + Cypress headless**, visits the same running-client URL, writes a PNG to disk, returns the **file path**.
- These are **heavy**: full running client, node/cypress process, real data path.

The new `/formtemplate/` route is **far lighter** (no running client, no cypress, no data, no websocket). It is the better substrate for a form-awareness tool, and it renders the *design-time* projection that maps directly back to the `.frm` — which is exactly what an authoring/editing AI wants, versus the data-filled runtime client.

### 2.3 The embedded browser abstraction (and what the Equo Chromium backend actually offers)
`com.servoy.eclipse.ui.browser.IBrowser` (in `servoy-eclipse`) is what `OpenCodeView` drives (`OpenCodeView.java:90` via `BrowserFactory.createBrowser`). What the **interface** surfaces today:
- `setUrl(...)`, `setText(html)`, `setFocus()`, `dispose()`.
- **`execute(String js)`** — runs JS **fire-and-forget**. Both backends return `void` (`SwtBrowserWrapper.java:129`, `ChromiumWrapper.java:189`). **The interface has no `evaluate()` that returns a value.**
- **`addBrowserFunction(name, IBrowserFunction)`** — registers a **JS → Java callback** (`SwtBrowserWrapper.java:104`, `ChromiumWrapper.java:164`).

**But the backends can do much more than the interface exposes.** The Chromium backend is `com.equo.chromium.swt.Browser` (`ChromiumWrapper.java:30,46`) — a **drop-in replacement for `org.eclipse.swt.browser.Browser`**. That SWT contract includes:
- **`Object evaluate(String script)`** — runs JS and **returns the result synchronously** (string/number/boolean/array). This is exactly the missing "read a value back" primitive. The wrappers simply never surfaced it; `IBrowser` only ever wired the void `execute`.
- **`getText()`** on the SWT `Browser` — returns the current **full HTML of the page** (i.e. the live, post-render DOM serialized), which is precisely the "actual DOM" an inspector would show.
- Per Equo docs, a **CDP debug port** (`-Dchromium.debug_port=8888`) and **`browser.getDevtoolsUrl()`** — full Chrome DevTools Protocol access (DOM tree, computed styles, screenshots, box model) against the running component.

**So the embedded-browser gap is smaller than it first looked:** the rendered DOM is reachable by adding an `evaluate()`/`getHtml()` to `IBrowser` (implemented via the backend's `evaluate`/`getText`, per the repo's "extend the interface, never touch Chromium directly" rule) — no `addBrowserFunction` round-trip needed, no external headless browser. The only real work is (a) surfacing those methods on `IBrowser`, and (b) having a browser instance that has actually rendered the form (either an offscreen `IBrowser`, or the **iframe-in-the-chat-view** approach below).

### 2.4 The BFF servlet
`OpencodeChatServlet` serves the Angular chat app and proxies opencode's API under `/servoy_ai/rest_api/**`. It is **not needed** for form-template access: `/formtemplate/*` is already same-origin on the same Tomcat, so a Java tool (or the chat UI) can hit it directly. No new proxy work.

### 2.5 The chat UI
`webui/src/app/services/part-utils.ts` renders only `text` / `reasoning` / `tool` parts — **no image rendering**. So even if a tool returned an image, the custom chat UI would not display it without a small front-end change.

> **Not a head start:** `webui/src/app/services/form-state.ts` (`evaluateForm`, `isVisible`, `buildFormAnswer`) is the opencode **interactive Form-card** exchange (chat-input fields + validation + `when` clauses), **not** Servoy form rendering. There is no existing Servoy-form-render code in the webui to reuse — a form-template panel/iframe would be new.

### 2.6 Precedent: the SVY-21473 `evaluate` tool spec (same repo, `docs/`)
`docs/SVY-21473-evaluate-expression-tool.spec.md` designs an `evaluate` MCP tool (run JS in the running debug client, return `{ value, console, error }`). It is a direct precedent for **where and how** a form tool should be shaped:
- Placed as an additive `@Tool` on an **existing** `@McpServer` (`servoy-dev`/`ServoyDevServer`) — no `McpServerBuiltins` registry change; every `@Tool` returns a `String` and catches its own exceptions.
- Returns a **compact JSON string**; large output is **capped inline and spilled to a temp file** whose path is returned (the env's `C:\Users\jcomp\AppData\Local\Temp\opencode`). A rendered-DOM blob should follow the same cap/spill pattern so a big form doesn't blow the token budget.
- Reuses a **proven platform engine headlessly** rather than building new plumbing — the same principle as reusing the `/formtemplate` route here.

That tool is about **runtime values**; this one is about **rendered structure/layout**. They are complementary (see §6).

---

## 3. The three directions, weighed against the above

### Direction 1 — Text-only (AI fetches the URL / the JSON blobs / the CSS)
**What it delivers:** the Angular **shell HTML**, the **form-state JSON** (clean component tree + design-time props), the **specs JSON**, and the **compiled CSS** — all as text, via a plain HTTP GET from Java (no browser).

- ✅ Cheapest by far. No browser, no node, no cypress. A Java tool just does `HttpClient` GETs against `http://127.0.0.1:<webPort>/formtemplate/...` (the same pattern the servlet/registry already assume).
- ✅ The **form-state JSON is arguably the *best* structural signal** — it is the resolved component hierarchy with property values, cleaner for an LLM to reason about than raw markup, and it maps 1:1 back to the `.frm` the AI edits.
- ✅ Deterministic, fast, no UI thread, no flakiness.
- ❌ **Not the rendered DOM.** No `.svy-wrapper` absolute positions, no `.svy-layoutcontainer` nesting as actually laid out, no computed styles, no proof the solution CSS class (e.g. a custom `.red`) actually applies. It is "the plan," not "the picture."
- ❌ The shell `.html` itself is low-value as text (it is mostly an empty Angular bootstrap); the **JSON blobs are the value**, and those are better fetched/parsed directly than by scraping the HTML.

**Verdict:** genuinely useful *and* the smallest path — but only for **structure/authoring-verification**, not for **layout/visual verification**.

### Direction 2 — Render inside our own Angular webui (same-origin iframe) and read the real DOM
**What it delivers:** the **true post-Angular DOM** (and computed styles), by rendering `/formtemplate/<form>.html` **inside the chat webui that already runs in the embedded Chromium** and reading it with plain JavaScript.

The pivotal fact: the chat UI is served at `http://127.0.0.1:<tomcatPort>/servoy_ai/` and the form template at `http://127.0.0.1:<tomcatPort>/formtemplate/<form>.html` — **same host, same port, same origin**. So an `<iframe src="/formtemplate/<form>.html">` inside the webui can be read directly in JS:
`iframe.contentDocument.querySelector('.svy-form').outerHTML`, `getComputedStyle(...)`, `getBoundingClientRect()`, etc. **No `IBrowser`, no Chromium API, no Java, no second browser, no headless, no cypress, no running NG client.**

**Who consumes the DOM decides the last mile:**

- **Human consumer** — done, entirely in Angular: a panel/route in the webui renders the iframe; the human sees the real form and the webui can read/annotate its DOM in pure JS. Nothing else needed.
- **AI consumer (the actual ask)** — the agent runs in a **separate process** and reaches Eclipse only through **MCP tools**, so the rendered DOM has to travel from "JS in the webui" to "a tool result." The **actual DOM read is always plain same-origin JS** (`iframe.contentDocument…`); the only choice is **how that JS result reaches the Java tool**. Three bridges, and **none of them strictly requires adding `evaluate` to `IBrowser`**:
  - **Bridge A — the webui pushes it through the existing BFF (no `servoy-eclipse` change).** The MCP tool `getFormDom(form)` registers a pending request and blocks briefly; the webui renders the same-origin iframe, reads `.svy-form` outerHTML + computed styles in JS, and `POST`s it to a small `OpencodeChatServlet` endpoint (it already owns `/servoy_ai/rest_api/**`); the tool picks up that POST and returns it. **All DOM access stays in JS**, touches **no** shared Eclipse-UI code, needs no `execute`/`evaluate`. Cost: a request/response rendezvous in the BFF, and the webui must be an active participant.
  - **Bridge B — `addBrowserFunction` callback (no interface change).** `IBrowser.addBrowserFunction(name, fn)` **already exists**. Injected JS reads the iframe DOM and calls that function, pushing the string **back into Java** — "evaluate in reverse." Self-contained (the tool can `execute(...)` the injection itself), and needs no new `IBrowser` method.
  - **Bridge C — add `evaluate` to `IBrowser` (Java pulls it).** The most *self-contained* and synchronous: the tool renders and then `evaluate("…outerHTML")` reads it in one call. The Equo/SWT backend already supports `Browser.evaluate`; only the `IBrowser` **interface** would gain a method. Touches `servoy-eclipse` (small, additive) and runs on the SWT UI thread.
  - **Net:** `evaluate` is the *cleanest* if we're willing to extend `IBrowser`, but **Bridge A or B deliver the same rendered DOM with zero `servoy-eclipse` changes** — the same-origin iframe is what makes all three work.

- ✅ Reuses the Chromium already on screen; the human sees the form too.
- ✅ Real resolved DOM + computed styles — exactly what an inspector shows — mapping straight back to the `.frm`.
- ✅ Fits the abstraction rule: surface `evaluate` on `IBrowser` (backend already has it); Chromium stays confined to `com.servoy.eclipse.ui`.
- ⚠️ Needs a **render-complete signal** (poll for `.svy-form`, or a small `window.__formtemplateReady` hook) before reading.
- ⚠️ `evaluate` runs on the SWT UI thread; the MCP tool (background thread) marshals via `Display.syncExec`. Manageable.
- ❗ **Screenshot is the only thing this can't do in pure JS/DOM-text.** A raster image needs either `html2canvas` in the webui's JS, or Equo CDP/`getDevtoolsUrl()`, **plus** MCP `ImageContent` support (§2.1) and chat-UI image rendering (§2.5). DOM-as-text — almost certainly enough for the AI — needs none of that.

**Verdict:** this is where the *rendered* answer lives, and rendering **inside our own webui** makes it cheap: everything is reachable in JS (same origin), the human sees it, and the only Java piece for the AI path is surfacing the already-present `evaluate` on `IBrowser`. Screenshots are a separable, optional extra.

### Direction 3 — MCP tool backed by a (headless or embedded) browser
This is not really a third *rendering* mechanism — it is the **delivery** of Direction 1 and/or Direction 2 to the agent. The agent already consumes MCP tools; a form tool belongs on an existing `@McpServer` (naturally `servoy-test`/`ServoyTestingServer`, alongside `showFormInBrowser`). The only open choice is **what the tool returns**:
- text projection (Direction 1) → trivial to wire, text-only result is fine;
- rendered DOM/screenshot (Direction 2) → needs the browser + possibly image-content support.

**Verdict:** MCP tool is the right **invocation path** regardless. Reading a raw URL directly is possible but worse — the agent would have to know the port, auth, and JSON shapes; a tool encapsulates all of that and returns a curated, token-bounded result.

---

## 4. Recommendation — the rendered-DOM tool is the target (§0 decides this)

Because the goal is an **AI layout self-correction loop**, the *rendered* result is the deliverable, not an optional later phase. The core piece is a **`getFormLayout(formName)` MCP tool** that renders the form in a same-origin iframe in our webui and returns a **compact layout projection** the AI can diff against its intent.

**The rendering + inspection is pure front-end Angular (no backend):**
- A small component in the webui holds an `<iframe>`; given a form name it builds the URL `/formtemplate/<form>.html` (same origin as the app), sets it, and lets it load.
- After a **render-complete signal** (poll for `.svy-form`, or a `window.__formtemplateReady` hook on the `svy-formtemplate` route), the app reads `iframe.contentDocument` in plain JS and builds the projection: per component → `type`, `name`, bounding box (`getBoundingClientRect`), containing `.svy-layoutcontainer` / responsive nesting, key `getComputedStyle` values (display/visibility/applied style classes), and text. Raw `outerHTML` is an optional expansion.

**The tool is only the cross-process handoff to the agent.** Pick the bridge by how much you want to touch (all deliver the same JS-read projection; **none requires `evaluate`**):
- **Bridge A (recommended) — BFF push, no `servoy-eclipse` change:** the webui POSTs the projection to a small `OpencodeChatServlet` endpoint; the `getFormLayout` tool blocks briefly and returns it. Keeps everything in the two already-active bundles (`opencode` webui + BFF, `developer.mcp` tool). This is the smallest end-to-end path *for the rendered goal*.
- **Bridge B — `addBrowserFunction` (no interface change):** injected JS pushes the projection back into Java via the existing callback.
- **Bridge C — add `evaluate` to `IBrowser`:** cleanest/synchronous, tool pulls it in one call; small additive Eclipse-UI change.

**Cheap first step, same tool surface (optional):** the tool can *start* by returning the **pre-render form-state JSON** (server-side HTTP GET of `/formtemplate/<form>.html`, parse the inline blob) with **no browser at all** — useful for structure, and a fallback when no browser/render is available. But since the decided goal is layout correctness, treat that as a fallback/first-cut, not the destination: the **rendered projection is what the AI actually needs** (only the rendered DOM shows resolved positions, responsive layout, and whether a style class actually applied).

**Screenshots (optional, later):** `html2canvas` in the webui JS, or Equo CDP / `getDevtoolsUrl()` — plus `McpServerFactory` `ImageContent` support and chat-UI image rendering. The structured layout projection is very likely enough for self-correction; **it needs none of the image plumbing.**

**Human view:** a "here's your form" panel falls out of the same iframe component for free, but it is explicitly a nice-to-have, not the driver.

**Core gap?** The core does **not** expose a post-render DOM endpoint — by design it serves a client-rendered page. With the iframe-in-Chromium approach we **don't need one**: we let the real browser render and read it back. A server-side pre-rendered DOM endpoint (Angular SSR) would only be worth asking core for if we later wanted rendered DOM **without** any browser on our side — not required for either phase here.

---

## 5. Open questions for you to decide before implementation

1. **Projection shape.** What exactly should `getFormLayout` return so the AI can *compare against its intent* cheaply? Proposal: per component → `type`, `name`, bounding box, container/responsive nesting, key computed styles (display/visibility/applied classes), text — with raw `outerHTML` as an optional expansion. Confirm the fields and the default verbosity.
2. **Which bridge (the tool handoff)?** DOM read is same-origin JS regardless; pick delivery: **A** webui POSTs the projection via the existing BFF (no `servoy-eclipse` change — recommended), **B** `addBrowserFunction` callback (no interface change), or **C** add `evaluate` to `IBrowser` (cleanest/synchronous, small Eclipse-UI change). `evaluate` is *not* required.
3. **iframe placement.** Dedicated hidden/utility component the tool drives, a visible panel in the chat view, or a route in the Angular app? The AI loop only needs it to render + be readable; visibility to the human is optional.
4. **Render-complete signal.** Add a `window.__formtemplateReady` hook (or custom event) to the `svy-formtemplate` route so the read fires exactly when Angular finishes, rather than polling for `.svy-form`? (Small core-side ask — worth it for a tight multi-form loop.)
5. **Tool home & name.** New tool on the existing `servoy-test` server (next to `showFormInBrowser`), or a dedicated `@McpServer` (e.g. `servoy-form`)? Name — `getFormLayout`?
6. **Pre-render JSON as a first cut / fallback?** Do we ship the no-browser form-state-JSON return first (fast to build, good for structure), then layer the rendered projection — or go straight to rendered since that's the decided goal?
7. **Deprecate/keep the heavy path.** Keep `showFormInBrowser`/`screenshotForm` (running-client + cypress) for runtime/data checks and position the new tool as the design-time layout path — or migrate?
8. **Screenshots.** Very likely unnecessary for self-correction (the structured projection carries positions/styles). Confirm we defer image support (`html2canvas`/CDP + `McpServerFactory` `ImageContent` + chat-UI rendering) unless a concrete need appears.
9. **Relationship to SVY-21473 `evaluate`.** That tool observes runtime *values*; this one observes rendered *layout*. Keep separate; reuse SVY-21473's cap-inline/spill-to-temp-file convention for any large output.
10. **New Jira issue.** File a follow-up referencing SVY-21460 as the enabler, scoped to the rendered `getFormLayout` loop.

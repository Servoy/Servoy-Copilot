# Spec: SVY-21460 — MCP `getFormLayout`: AI form-layout self-correction loop (hidden-browser render + full DOM & computed appearance)

> **Tracked on SVY-21460** (the enabler case — no separate follow-up issue). SVY-21460's
> *enabler* half (the stateless `GET /formtemplate/<form>.html` render route) is already
> committed in `servoy-eclipse`. **This spec is the AI-consumption follow-up** and lives in
> the **Servoy-Copilot** repo. The design and its decisions come from
> `docs/form-awareness-followup-findings.md`.

## 1. Goal

Give the Servoy AI assistant a **form-layout self-correction loop**. When the assistant
generates or edits Servoy forms (often several forms for one feature, in a short burst), it
must be able to **see how the rendered form actually looks** — its real DOM structure *and*
its resolved appearance — so it can check the result against what it intended and fix it
before moving on. This is **AI-facing**; a human being able to watch the form render is a
nice-to-have side effect, not the goal.

Deliver a single new MCP tool, **`getFormLayout`**, on a new **`servoy-form`** MCP server,
that:

1. renders the form's real runtime DOM by loading the stateless
   `GET /formtemplate/<form>.html` route in a **hidden utility browser** the tool owns,
2. waits for render-complete, then `evaluate()`s the rendered DOM of the requested subtree,
   optionally **captures a PNG screenshot** of the rendered form, and
3. returns a JSON envelope with:
   - **`html`** — the full rendered `outerHTML` of the requested subtree (the whole
     `.svy-form`, or the element matched by an optional CSS `selector`). This is the real
     rendered DOM: the actual component tags (e.g. `<bootstrapcomponents-textbox>`), their
     `id` / `data-*` identity attributes (incl. `data-svy-name`), applied classes, and nested
     structure exactly as rendered.
   - **`appearance`** — a map keyed by element (its `#id` or a CSS path) of the **resolved
     visual styles** (`color`, `background-color`, borders, font, `display`, `position`,
     `visibility`, opacity, z-index, margin/padding) from `getComputedStyle` plus the
     on-screen **bounding box** (x/y/w/h) from `getBoundingClientRect`.
   - **`screenshotFile`** — when a screenshot was captured (whole form, default on), the path
     of the saved PNG; the image is **also attached to the tool response as MCP image
     content** so a vision-capable model sees the actual rendered pixels directly.

The `appearance` values already reflect the **solution stylesheet + theme** (the live browser
applied them), which raw HTML alone cannot convey — so the AI can judge and change colours or
positioning and verify the result on the next call. Large output is capped inline and spilled
to a temp file (SVY-21473 convention). The `getFormLayout` tool is positioned (via its
description) as **the** design-time tool for verifying how a form looks, and the older
running-client screenshot/preview tools (`servoy-test_screenshotForm`,
`servoy-test_showFormInBrowser`) are marked **deprecated** in favour of it (§3.8).

> **Design note — screenshots were originally out of scope, now IN scope.** The first cut of
> this spec deferred images (the rendered DOM + computed appearance already carry structure and
> resolved look, and the MCP result pipeline was text-only). During implementation the core
> gained `IBrowser.captureScreenshot()` (Equo CEF capture that works on the hidden/offscreen
> browser), and a real screenshot the model can *see* proved valuable enough to include: it is
> captured for the whole form by default and attached as MCP `ImageContent`. Delivering it
> required a small, backward-compatible extension of the MCP result pipeline (§3.7).

## 2. Background

### 2.1 The capability gap

An authoring/editing AI can write a form's `.frm` and compile it cleanly, but the loop is
write → save → hand off; nothing in it lets the agent *see the rendered result* and check it
against intent. The `.frm` describes **the plan**. Two things are needed to understand **the
picture**:

- the **rendered DOM** — what elements actually exist and how they nest (a complex component
  like a calendar or typeahead renders a whole subtree of inner nodes, and a design-time
  property such as a style class can land on any of them), and
- the **resolved appearance** — the *computed* colour, font, borders and box after the browser
  applied the solution stylesheet, theme and inline styles. HTML gives class *names*; only the
  browser knows what those classes resolve to and where a box ends up.

`getFormLayout` returns both.

### 2.2 The enabler (already built in `servoy-eclipse`)

From `servoy-eclipse/docs/SVY-21460-form-template-render-route.spec.md`, the Servoy core now
serves, on the Developer's embedded Tomcat (web port
`ApplicationServerRegistry.get().getWebServerPort()` — e.g. `8183`):

- **`GET /formtemplate/<formName>.html`** — a **stateless Angular shell page** (no
  `clientnr`/`solution` in the URL, **no websocket** opened by the page). It carries inline a
  `window.formtemplateName` marker, the form-state JSON, per-component client-side specs, and a
  `<link rel="stylesheet" href="/formtemplate/stylesheet.css">`. It boots the `svy-formtemplate`
  Angular component and renders the **same runtime `.svy-form` DOM as `svy-form`**: the real
  component tags (e.g. `<bootstrapcomponents-textbox>`), CSS-position wrappers, responsive
  `.svy-layoutcontainer` nesting. **No designer artifacts** (`svy-id`, `designclass`, ghosts,
  wireframe, `svy-designform`).
- **`GET /formtemplate/stylesheet.css`** — the solution CSS, `text/css`, design-time produced.

**Critical fact:** the served `.html` is a **pre-render** page. The real DOM (resolved
positions/styles, laid-out component subtrees) **only exists after the Angular app boots and
runs in a browser**. Fetching the `.html` as text over HTTP yields the shell, not the resolved
DOM. This is why a real browser (not an `HttpClient` GET) must render the page before the DOM
can be read.

**Component identity is on the component tag in the rendered DOM.** Each component renders as
its Angular custom-element tag (its spec name, e.g. `<bootstrapcomponents-textbox>`) and — after
a small core enhancement landed on the LTS branch alongside this work — carries
**`data-svy-name="<componentName>"`** and **`data-svy-id="<svyMarkupId>"`** directly on the
component element, so even a *collapsed* DOM tree is self-identifying (a row of six textboxes is
distinguishable at a glance). The inner leaf additionally carries `data-cy="<formName>.<name>"`
and `id="s…"`. So the returned `html` is directly relatable back to the `.frm` and the tool needs
**no** name-injection of its own.

### 2.3 Core prerequisites/contracts (all applied in `servoy-eclipse`, NOT in this repo)

This tool consumes three additions made on the `servoy-eclipse` 2026.03 LTS branch; none is
implemented in this repo. Chromium stays confined to `com.servoy.eclipse.ui`.

1. **`IBrowser.evaluate(String)`** — a value-returning `Object evaluate(String script)` on
   `com.servoy.eclipse.ui.browser.IBrowser` (+ both wrappers, delegating to the backend
   `Browser.evaluate`). Used to read the rendered DOM. Must be called **on** the SWT display
   thread.
2. **`IBrowser.captureScreenshot()`** — `byte[] captureScreenshot()` on `IBrowser` (+ both
   wrappers). The Chromium wrapper delegates to the Equo `ChromiumBrowser.captureScreenshot()`
   (CEF capture that works even for a hidden/offscreen shell), decodes its Base64 to raw PNG
   bytes, and returns them; the plain SWT wrapper returns `null` (unsupported). **Threading
   contract is the INVERSE of `evaluate`: it must be called OFF the SWT display thread** (the
   underlying capture is async and blocks; on the UI thread it refuses and returns `null`).
3. **`data-svy-name` / `data-svy-id` on the rendered component tags** — the `svy-formtemplate`
   renderer stamps them (see 2.2). A readability enhancement, consumed but not required for the
   tool to function (identity also lives in the tag + `id` + `data-cy`).

> **`evaluate` returns the script's value, not fire-and-forget** — unlike `execute(String)`.
> The SWT/Chromium contract wraps the script and looks for a top-level `return`, so **every
> injected script in this tool must start with `return`** (a bare `(function(){…})();`
> expression statement yields `null`). This is the one non-obvious gotcha; it is why the
> injected scripts here are `return (function(){…})();`.

> **Contract dependency (a build-order fact, not a task in this repo):** `getFormLayout`
> compiles and runs only when the resolved target platform carries `IBrowser.evaluate(String)`,
> `IBrowser.captureScreenshot()` and the `/formtemplate` route (the `servoy-eclipse` changes, to
> be landed on 2026.03 LTS and merged forward). The implementation plan below covers **only this
> repo's consumer side**; no `IBrowser` / wrapper edits are made here.

### 2.4 MCP server / tool wiring in `com.servoy.eclipse.developer.mcp` (verified)

- Tools are plain methods annotated `@Tool(name, description, type)` with `@ToolParam` on each
  argument, on a class annotated `@McpServer(name)`. Every `@Tool` method returns a `String`
  and catches its own exceptions (`ServoyLog.logError(...)`, returns `"Error: " + msg`). All
  `@ToolParam` values arrive as `String` per the codebase convention (parse/default in the
  method).
- Servers are listed in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`. Each is mounted by
  `McpServerRegistry` at `/dev_mcp/<serverName>` via a per-server named
  `*Servlet extends BearerTokenAuthenticationFilter` inner class registered in
  `McpServerRegistry.SERVLET_FACTORIES` (Tomcat needs a distinct wrapper class per endpoint),
  behind a bearer-token filter. `McpEndpointProvider` derives the URL from
  `BUILT_IN_SERVER_CLASSES` × the `@McpServer` name and merges it into `opencode.json`, so a
  **new server** needs a `BUILT_IN_SERVER_CLASSES` entry + a `SERVLET_FACTORIES` entry + a new
  servlet class, but **no** `McpEndpointProvider` change.
- **Tool results were text-only before this work.** `McpServerFactory.executeCallTool(...)`
  wrapped every tool's returned `String` in a single `McpSchema.TextContent`. To attach the
  screenshot as image content, this work adds a small **backward-compatible** extension: a tool
  may return an `McpToolResult` carrier (text + optional base64 image + mime type), which the
  factory expands into a `TextContent` plus an `ImageContent`; every tool that still returns a
  plain value keeps the exact original single-`TextContent` path (see §3.7).

### 2.5 Existing form-preview tooling — relate, don't reuse

`FormPreviewService` (`services/FormPreviewService.java`) backs `showFormInBrowser` (external
browser, needs a **running NG client** + `servoy.ngclient.testingMode`) and `screenshotForm`
(bundled **Node + Cypress** headless, writes a PNG, returns the file path). Both are **heavy**
(running client, node/cypress, real data path) and `screenshotForm` returns only a file path the
model cannot see. `getFormLayout` is far lighter: the **stateless `/formtemplate` route** + a
**hidden `IBrowser`** — no running client, no cypress, no data, no websocket — and it renders the
**design-time** DOM that maps directly back to the `.frm`, returning the DOM, resolved styles and
a screenshot **the model can see**. This work therefore marks `screenshotForm` and
`showFormInBrowser` **deprecated** in their tool descriptions (kept working, but the AI is told
to prefer `getFormLayout`; `screenshotForm`'s only remaining niche is capturing the form with
**live data** in the running client — see §3.8). `FormPreviewService.checkNGClientStatus()` is
the precedent for returning a **named, actionable "not ready / here is what to do" string**
rather than hanging.

### 2.6 Precedent for output shaping — SVY-21473 `evaluate` (same repo, `docs/`)

`docs/SVY-21473-evaluate-expression-tool.spec.md` establishes the conventions mirrored here: an
additive `@Tool` returning a **compact JSON string**, with **large output capped inline and
spilled to a temp file** whose path is returned (using the platform temp dir with a unique
name). SVY-21473 observes runtime **values**; SVY-21460's `getFormLayout` observes rendered
**DOM + appearance**. Independent and complementary; reuse only the cap/spill convention.

### 2.7 Classpath / MANIFEST reality (verified)

`com.servoy.eclipse.developer.mcp/META-INF/MANIFEST.MF` **already** has
`Require-Bundle: com.servoy.eclipse.ui` (so `IBrowser` / `BrowserFactory` are visible),
`org.eclipse.ui`, and imports `com.servoy.j2db.server.shared`
(`ApplicationServerRegistry.getWebServerPort()`), `com.servoy.eclipse.model.util`
(`ServoyLog`), Jackson, etc. SWT types (`Composite`/`Shell`/`Display`/`FillLayout`) resolve
transitively via `org.eclipse.ui` / `com.servoy.eclipse.ui`. No new `Require-Bundle` was needed
(confirmed: the implementation compiles clean without a MANIFEST change).

## 3. Design

### 3.1 Tool signature and shape (new `servoy-form` / `ServoyFormServer`)

Home: a **new `@McpServer(name = "servoy-form")` server, `ServoyFormServer`**, with
`getFormLayout` as its **founding tool**. `getFormLayout` is a **design-time form-rendering**
aid — neither testing (`servoy-test`) nor the dev/runtime target (`servoy-dev`) — so a
dedicated form server is the honest semantic home and gives room to grow. One-time wiring:
a `ServoyFormServer` class; a `BUILT_IN_SERVER_CLASSES` entry; a
`ServoyFormServlet extends BearerTokenAuthenticationFilter` inner class + a
`SERVLET_FACTORIES.put("servoy-form", …)`; **no** `McpEndpointProvider` change.

```java
@Tool(name = "getFormLayout", description = <see 3.6>, type = "object")
public Object getFormLayout(
    @ToolParam(name = "formName", required = true) String formName,
    @ToolParam(name = "selector", required = false) String selector,
    @ToolParam(name = "screenshot", type = "boolean", required = false) String screenshot,
    @ToolParam(name = "timeoutSeconds", type = "integer", required = false) String timeoutSeconds)
```

- `formName` — the form to render.
- `selector` — optional CSS selector of the subtree to return; when null/blank the whole
  `.svy-form` is returned.
- `screenshot` — parsed boolean, **default true**; capture a PNG of the whole rendered form.
  **Ignored when `selector` is set** (a screenshot of a single subtree adds little).
- `timeoutSeconds` — parsed, default 15.
- **Return type is `Object`**, so the tool can return either a plain `String` (the JSON
  envelope, or a named message on failure) or an **`McpToolResult`** carrying the envelope text
  **plus the base64 PNG** when a screenshot was captured (§3.7). The envelope is
  `{ form, selector, viewport, html, appearance[, screenshotFile] }`, or, when it exceeds the
  cap, `{ form, selector, viewport, resultFile, truncated, warning[, screenshotFile] }` with the
  full envelope written to `resultFile` (3.4). On failure it returns a named, actionable message
  (3.5), never a hang.

**Migration is a follow-up, NOT this case.** Existing form tools scattered across servers
(`showFormInBrowser` / `screenshotForm` / `checkNGClientStatus` on `servoy-test`; `createForm`,
`validateFormElementFormat`, `getFormSecurity` / `setFormElementAccess` / `setFormSecurityBulk`
on `servoy-dev`) are natural future residents of `servoy-form`, but moving them is a **breaking
tool-rename** (a tool's identity is `<serverName>_<toolName>` in `opencode.json`), so it is
explicitly out of scope here (OQ-5). This case only creates `servoy-form` + `getFormLayout`.

### 3.2 The hidden-browser driver — threading + lifecycle contract

The MCP tool runs on a **background (servlet) thread**; `IBrowser` / SWT must be touched only on
the **SWT display thread**, so every browser interaction is marshalled via
`Display.getDefault().syncExec(...)`.

**Lifecycle — one reused hidden browser, calls serialized (decided).** The embedded Chromium is
**shared, single-instance state**, so the service keeps **one** hidden utility browser and
serializes `getFormLayout` calls through a **lock** (only one render/read in flight at a time).
Navigating it to a new form URL **discards** the previously rendered form — the desired
behaviour (the AI asks for one form at a time; a prior form is simply replaced). This avoids a
pile of browsers during a burst.

- **first call — create (display thread):** an **offscreen `Shell`** (`SWT.NO_TRIM`, located far
  off-screen, `setVisible(false)`), then `IBrowser browser = BrowserFactory.createBrowser(shell)`
  sized to a default viewport (1280×1024). A **utility browser the service owns**, explicitly
  **not** the visible chat view (`OpenCodeView`'s browser); held in a field behind a pool-ready
  `BrowserHandle`. If `createBrowser` throws, the freshly-opened shell is disposed before
  rethrowing (no leak).
- **each call — clear then load (display thread):** under the lock, navigate to `about:blank` and
  **confirm cleared** (no `.svy-form`, no `window.formtemplateName`) within a bounded budget, so
  a stale previous form cannot satisfy readiness during the brief window before the new page
  commits; then `setUrl(the form url)`.
- **await render-complete (bounded):** poll on the display thread (repeated short `syncExec`
  reads, pumping `Display.readAndDispatch()`) until the **form-specific** readiness predicate
  holds or `timeoutSeconds` elapses. Never `Thread.sleep` on the display thread.
- **read (display thread):** `browser.evaluate(<read JS>)` → the JSON string (3.3).
- **screenshot (BACKGROUND thread, whole form only):** after a successful read, still holding the
  lock (so the browser still shows this form) and **off** the display thread, call
  `browser.captureScreenshot()` → PNG bytes. This honours the inverse threading contract of
  `captureScreenshot` (§2.3) — it is deliberately NOT run inside `syncExec`. Best-effort: any
  failure (unsupported backend, capture error, wrong thread) yields no image and the layout is
  still returned. Skipped when a `selector` was given or `screenshot=false`.
- **release the lock.** The browser and its offscreen shell are **disposed on Display teardown**
  at workbench shutdown (via `Display.disposeExec(...)`), **not** per call.

All SWT-touching steps run inside `syncExec` (except `captureScreenshot`, which must run off the
display thread); the background thread holds the service lock across the whole render + read +
screenshot, blocks on the bounded readiness wait, then returns the assembled envelope. On timeout
it returns the named message (3.5) and leaves the reused browser in place.

> **Future-proof for a small pool (OQ-7).** The single reused instance is held behind a
> `BrowserHandle` so a bounded pool (e.g. up to ~5) can replace it later without reshaping the
> acquire → navigate → await → read → release flow. Not built now; just not designed out.

### 3.3 The injected read JS and the returned shape

All injected scripts are **`return (function(){…})();`** (2.3 gotcha) and return a JSON
**string** (single scalar across the SWT/Chromium value crossing). The requested form name and
selector are embedded as **JSON-encoded string literals** (`ObjectMapper.writeValueAsString`,
passed as `String.format` *arguments*), so a name/selector containing a quote or backslash cannot
break out of the literal or inject JS.

- **Readiness probe** (`return`s `true`/`false`): requires `window.formtemplateName === <requested
  form>` (so a stale previous form cannot trip it) **and** the requested root (`.svy-form`, or the
  selector's element) exists with at least one laid-out node (non-zero `getBoundingClientRect`).
- **Cleared probe:** `true` when no `.svy-form` and no `window.formtemplateName` remain.
- **Read** (`return`s the envelope JSON string):
  - refuses to read the wrong page: if `window.formtemplateName` ≠ requested form → `WRONG_FORM`
    sentinel (the Java side maps it to the named "did not finish rendering" message);
  - resolves the **root**: `selector ? document.querySelector(selector) : document.querySelector('.svy-form')`
    — a missing root → `NO_MATCH` (selector) / `NO_SVY_FORM` (whole form) sentinel;
  - **`html`** = `root.outerHTML` — the full rendered subtree verbatim (no filtering; the whole
    DOM, so inner component structure and any node a design-time property could target is
    present);
  - **`appearance`** = for every element in the subtree that carries an `id` or a `class` (i.e.
    anything a design-time property could target — including a complex component's inner nodes),
    a compact entry `{ box: {x,y,w,h}, styles: {…} }` from `getBoundingClientRect` +
    `getComputedStyle`, keyed by `#id` (when the element has one) or a CSS `nth-of-type` path so
    the AI can correlate an appearance entry back to a node in `html`. `styles` is a small fixed
    allow-list of visually meaningful properties (color, background-color, borders, font family/
    size/weight/style, text-align, line-height, display, position, visibility, opacity, z-index,
    margin, padding) — enough to reason about colour + layout without dumping all ~300 computed
    properties;
  - top-level: `form` (the marker), `selector` (echoed, or `.svy-form`), `viewport` (the root's
    box), `html`, `appearance`.

### 3.4 Output size + temp dir — cap inline, spill to a file (SVY-21473 convention)

The envelope (rendered HTML + appearance) is **usually large** for a real form, so a hard cap is
applied to the final JSON string. If it exceeds the cap, the full envelope is written to a file
and the tool returns a light inline summary (`form`, `selector`, `viewport`, `screenshotFile`)
plus `resultFile` (path) and `truncated: true` + a `warning`, so the AI reads the full result
with the file tools. Under the cap, the envelope is returned inline verbatim (with
`screenshotFile` added when a screenshot was captured).

**Temp directory.** Spilled files (the JSON envelope and the PNG screenshot) are written to the
plugin's **own state area**, `{workspace}/.metadata/.plugins/com.servoy.eclipse.developer.mcp/temp/`
(via `Activator.getStateLocation().append("temp")`), **not** the OS temp dir. This is a stable,
sanctioned, writable location inside Eclipse's plugin state area, so the Servoy orchestrator's
path restrictions (which may forbid the OS temp dir / anything outside the project) do not block
the agent from reading the spilled files. Falls back to the OS temp dir only if the plugin state
location is unavailable (e.g. a plain unit test with no started Activator). Names are unique by
`Files.createTempFile`.

### 3.5 Not-renderable / no-port / not-found — named, actionable message (never a hang)

Following `checkNGClientStatus`'s precedent, the tool returns a **plain, named message** (not a
timeout, not a silent no-op) when it cannot produce a result:

- **web server port ≤ 0 / unavailable** → *"The Servoy web server is not running; start Servoy
  Developer and open a solution, then try again."*
- **no active solution** → *"No active Servoy solution. Open a solution in Servoy Developer, then
  try again."*
- **form not found** → *"Form '<formName>' was not found in the active solution '<sol>'."*
- **selector matched nothing** → *"Selector '<selector>' matched no element in the rendered form
  '<formName>'."*
- **render timed out** (incl. the `WRONG_FORM` never-settled case) → *"Form '<formName>' did not
  finish rendering within Ns."*
- **no `.svy-form`** → *"Form '<formName>' loaded but produced no '.svy-form' root; it may not be
  renderable via the form template route."*

### 3.6 Tool description — position it as THE "see how a form looks" tool

The description must actively steer the AI to this tool for design-time layout/appearance
verification (over the deprecated running-client tools, §3.8). It: leads with the use-case
("SEE HOW A SERVOY FORM ACTUALLY LOOKS" — use after creating/editing a form to verify layout,
positioning, sizing, colours, styling); says to prefer it over other screenshot/preview tools;
enumerates the three return channels (**attached PNG screenshot** the model sees + `screenshotFile`
path; **`html`** with the real component tags, `id`/`data-svy-name` and structure; **`appearance`**
with resolved computed styles + boxes); describes the edit → getFormLayout → inspect → adjust
loop; states it is design-time (no data / no running client) and read-only; notes the optional
`selector` (subtree, no screenshot) and the `resultFile` spill; and ends with the "named message,
never a timeout" guarantee. (The exact prose lives in `ServoyFormServer`.)

### 3.7 Delivering the screenshot as image content — a backward-compatible pipeline extension

The MCP result pipeline wrapped every tool's `String` return in a single `TextContent`. To
attach the screenshot:

- **`McpToolResult`** (new record, `com.servoy.eclipse.developer.mcp`): `{ String text, String
  imageData, String imageMimeType }` with `text(...)` / `withImage(...)` factories and
  `hasImage()`. An optional richer return value for a tool that needs text + image.
- **`McpServerFactory.executeCallTool`**: if the tool's result is an `McpToolResult`, emit a
  `TextContent` for its `text` **and**, when `hasImage()`, an `McpSchema.ImageContent(imageData,
  imageMimeType)`. Any other return type keeps the **exact original** single-`TextContent`
  `toString()` path — so this is fully backward-compatible and no other tool/server is affected.
- **`ServoyFormServer.getFormLayout`** returns `Object`: the plain JSON `String` normally, or
  `McpToolResult.withImage(envelopeJson, base64Png, "image/png")` when a screenshot was captured,
  so the PNG is both referenced by path (`screenshotFile`) and attached as MCP image content.

> **Honest caveat.** Whether the *model* receives the image depends on the MCP client (opencode)
> forwarding `ImageContent` tool-results to the model, and the Angular chat UI's `part-utils`
> renders only text/reasoning/tool parts, so a human won't see it inline without a small UI
> change. `screenshotFile` (path) is the guaranteed-usable fallback. (Verified live: a
> vision-capable model did receive and correctly read the attached screenshot.)

### 3.8 Deprecating the older running-client tools

`servoy-test_screenshotForm` and `servoy-test_showFormInBrowser` are marked **deprecated** in
their `@Tool` descriptions (kept working — no removal, no rename): each now leads with "DEPRECATED
— prefer `servoy-form_getFormLayout`" and states its only remaining niche (`screenshotForm`:
capture with **live data** in the running client; `showFormInBrowser`: open a real browser window
for a human / prepare a Cypress spec). Wholesale removal/migration of the `servoy-test` form tools
into `servoy-form` remains a separate follow-up (OQ-5).

## 4. Implementation plan (this repo only — the consumer side)

All in `com.servoy.eclipse.developer.mcp` unless noted. **No `IBrowser` / `ChromiumWrapper` /
`SwtBrowserWrapper` edits here** (2.3 prerequisite, already applied in `servoy-eclipse`).

1. **`FormLayoutInspectionService`** (`services/`) — active-solution + form resolution; web-port
   lookup; the named not-renderable/not-found/no-port messages (3.5); the **hidden-browser
   driver** (3.2): one reused offscreen `Shell` + `BrowserFactory.createBrowser` behind a
   pool-ready `BrowserHandle`, all SWT/`IBrowser` work inside `Display.getDefault().syncExec(...)`,
   calls serialized by a lock (acquire → about:blank clear-confirm → `setUrl` → bounded polling
   await-ready → `evaluate` read → **off-thread `captureScreenshot` when requested** → release),
   browser+shell disposed on `Display` teardown; the injected **`return (…)`** readiness / cleared
   / read scripts (3.3) with the form name + selector JSON-encoded; envelope assembly +
   **cap-inline / spill-to-plugin-temp-dir** (3.4) via a shared `spillBytes(...)` used for both the
   JSON and the PNG; `tempDir()` = plugin state area `temp/`. Signature
   `getFormLayout(formName, selector, screenshot, timeoutSeconds)`; result holder
   `FormLayoutResult { json, resultFile, screenshotFile, imageBase64, error }`.
2. **`McpToolResult`** (new record, bundle root) + **`McpServerFactory.executeCallTool`** change —
   the backward-compatible text+image pipeline extension (3.7).
3. **`ServoyFormServer`** (`servers/`) — `@McpServer(name = "servoy-form")` with the
   `@Tool getFormLayout(formName, selector?, screenshot?, timeoutSeconds?)` returning `Object`,
   delegating to the service (parse `timeoutSeconds` default 15 and `screenshot` default true;
   catch exceptions → `"Error: " + msg`; return the plain JSON envelope, or an `McpToolResult`
   with the base64 PNG when captured, or the named message). Strong "see how a form looks"
   description (3.6). Register: add to `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`; add a
   `ServoyFormServlet extends BearerTokenAuthenticationFilter` inner class in `McpServerRegistry`
   and `SERVLET_FACTORIES.put("servoy-form", (t, d) -> new ServoyFormServlet(t, d))`. No
   `McpEndpointProvider` change.
4. **Deprecate the old tools** (3.8) — mark `servoy-test_screenshotForm` and
   `servoy-test_showFormInBrowser` deprecated in their `@Tool` descriptions (`ServoyTestingServer`),
   pointing at `getFormLayout`. No behaviour/removal change.
5. **MANIFEST** — no change expected (SWT/browser types resolve via the existing
   `Require-Bundle`). Add an `Import-Package` only if the compiler asks (it did not).
6. **Compile / quick-fix loop + Spotbugs** — `getCompilationErrors()` clean **at All-Projects
   scope** (a tool-signature change breaks callers in the test fragment, which a single-bundle
   check misses); fix top-two Spotbugs severities (dispose browser+shell on the failure path and
   on shutdown; null-check port/active-solution/`evaluate`/`captureScreenshot` results; lock +
   shared handle synchronization).

## 5. Tests (this repo's conventions)

Follow AGENTS.md. The pure output shaping now lives in the injected JS (runs only in a browser),
so there is **no headless-unit-testable formatter** — the tool's behaviour is verified by a **PDE
integration test**. (An earlier `FormLayoutProjectionFormatter` + its unit test were removed when
the design moved to returning the DOM verbatim.)

1. **PDE integration test (Jupiter, `*IntegrationTest`)** —
   `tests/.../integration/FormLayoutToolIntegrationTest.java`, extending `TestUtilitiesClass`
   (→ `AbstractIntegrationTest` / `DialogGuardBase`), using its helpers (`waitForAppServer`,
   `ensureTestSolutionInWorkspace`, `waitForWorkspaceBuildJobs`, …) — **never** raw `Thread.sleep`
   — and cleaning workspace projects in `@BeforeAll deleteProjectsBeforeClass()`. With a solution
   containing known forms, assert the **render + read round-trip**:
   - whole-form read: `form` echoes the request, `selector` is `.svy-form`, `viewport` has a
     non-zero box, `html` contains `svy-form` and the component's Servoy name, and `appearance` is
     a non-empty map whose entries each carry a `box` and `styles`;
   - **selector** read: a `data-cy`-based selector returns **only** the matched subtree (the
     sibling is absent from `html`), with a scoped appearance map;
   - **B1 regression:** two consecutive calls for **different** forms each return their own DOM
     (the second form's element present, the first form's element **not** leaking in);
   - not-found and blank-name → the named messages (these are valid even without the render
     prerequisite).
   The tool returns `Object`, so the test calls `getFormLayout(form, selector, screenshot,
   timeout)` with **`screenshot="false"`** for the DOM/appearance assertions (keeping them
   browser-only and deterministic) and treats the result as a `String` (`assertInstanceOf`);
   the negative-path tests likewise assert a `String` message. Because it drives the hidden
   `IBrowser` on the SWT display thread and needs the `/formtemplate` route on the running
   Developer, this test **must** run in the PDE harness, and its positive round-trip assertions
   only pass once the target platform carries the `/formtemplate` route + `IBrowser.evaluate` +
   `IBrowser.captureScreenshot` (the negative-path tests are already valid). It is registered but
   intentionally **not run** until those prerequisites are present. Registered in the integration
   `pom.xml` `<test>` block **and** in `AllDeveloperMcpIntegrationTests.@SelectClasses`.

   > A dedicated headless unit test for the text+image pipeline (`McpToolResult` →
   > `McpServerFactory` expansion) could be added, but that logic is exercised through the tool
   > and the change is small/backward-compatible; noted as optional in OQ.

## 6. Acceptance criteria

- [ ] A new `@McpServer(name = "servoy-form") ServoyFormServer` exists, added to
      `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`, with a distinct `ServoyFormServlet` +
      `SERVLET_FACTORIES.put("servoy-form", …)` in `McpServerRegistry`; its `/dev_mcp/servoy-form`
      endpoint is merged into `opencode.json` with **no** `McpEndpointProvider` change.
- [ ] A `@Tool getFormLayout(formName, selector?, screenshot?=true, timeoutSeconds?=15)` on
      `ServoyFormServer` (returning `Object`), with correct `@ToolParam` names/types.
- [ ] The tool renders `http://127.0.0.1:<webPort>/formtemplate/<form>.html` in **one reused,
      hidden `IBrowser`** created via `BrowserFactory.createBrowser(...)` on an offscreen `Shell`
      — **not** the visible chat view — with calls **serialized by a lock**; browser + shell
      disposed on `Display` teardown, not per call; the shell is disposed if `createBrowser` throws.
- [ ] All SWT / `IBrowser` interaction runs on the SWT display thread via
      `Display.getDefault().syncExec(...)`; the tool never touches `IBrowser` off the display
      thread and never `Thread.sleep`s on it; no lock↔`syncExec` deadlock.
- [ ] On reuse the browser is navigated to `about:blank` and confirmed cleared before the new form
      loads; the readiness and read scripts require `window.formtemplateName === <requested form>`
      so a stale previous form cannot be read (a wrong page → named "did not finish rendering").
- [ ] Every injected script `return`s its value (works with `IBrowser.evaluate`, not fire-and-forget).
- [ ] The result envelope carries **`html`** (the full rendered `outerHTML` of the requested
      subtree, verbatim — no filtering) and **`appearance`** (per element with an `id`/`class`: a
      bounding box + a small allow-list of resolved computed styles), plus `form`/`selector`/
      `viewport`.
- [ ] For the whole form with `screenshot=true` (default) a **PNG is captured** via
      `IBrowser.captureScreenshot()` **off the display thread**, saved to the plugin temp dir with
      its path in **`screenshotFile`**, and **attached as MCP `ImageContent`** (via `McpToolResult`
      + the `McpServerFactory` extension); capture is skipped for a `selector` subtree or
      `screenshot=false`; a capture failure degrades gracefully (layout still returned, no image).
- [ ] The `McpServerFactory` text+image extension is **backward-compatible**: a plain-`String`
      tool return still yields exactly one `TextContent`.
- [ ] An optional `selector` scopes the read to the matched subtree; a selector matching nothing
      returns the named `NO_MATCH` message.
- [ ] Large output is **capped inline and spilled to the plugin state `temp/` dir** (not the OS
      temp dir) whose path is returned in `resultFile`; screenshots go to the same dir.
- [ ] Not-renderable states (web port ≤ 0, no active solution, form not found, selector no-match,
      render timeout, no `.svy-form`) return a **named, actionable message**, never a hang.
- [ ] The tool is **read-only** and **design-time** (no data, no running client, no login, no
      websocket); it does not modify the form and does not depend on the AI view being open.
- [ ] `servoy-test_screenshotForm` and `servoy-test_showFormInBrowser` descriptions are marked
      **deprecated**, pointing at `getFormLayout` (kept working; no removal).
- [ ] The driver is **pool-ready** (single reused browser behind a handle that could become a
      bounded ~5 pool) without reshaping callers.
- [ ] Compilation clean **at All-Projects scope** (relies on `IBrowser.evaluate` +
      `IBrowser.captureScreenshot` in the resolved target platform); top-two severity Spotbugs
      clean in new code; the integration test registered in **both** the `@Suite` and the
      `pom.xml` `<test>` list.

## 7. Out of scope

- **Any `IBrowser` / `ChromiumWrapper` / `SwtBrowserWrapper` change.** `IBrowser.evaluate`,
  `IBrowser.captureScreenshot` and the `data-svy-*` render attribute are `servoy-eclipse`
  prerequisites, already applied there; this repo only *consumes* them.
- **A chat-UI image renderer.** The screenshot is attached as MCP `ImageContent` and saved to a
  file; making the Angular chat UI render an image tool-result inline (its `part-utils` shows only
  text/reasoning/tool parts) is a separate front-end change, not this case.
- **A filtered/structured component projection.** Superseded — the tool returns the DOM verbatim
  so any node (including a complex component's inner structure) is visible.
- **Migrating existing form tools into `servoy-form`, or removing the deprecated ones.** This case
  only *deprecates* `screenshotForm`/`showFormInBrowser` in their descriptions; migration/removal
  is a breaking follow-up (OQ-5).
- **The visible chat view / a human-facing form panel.** The tool uses a hidden utility browser; a
  human-visible panel is a possible future nice-to-have, not this case.
- **A multi-browser pool.** One reused browser now; pool-ready by design (OQ-7).
- **Persisting results as artifacts** beyond the cap/spill + screenshot files in the plugin temp
  dir.

## 8. Open questions

| # | Question | Owner | Status |
|---|---|---|---|
| OQ-1 | Which resolved computed-style properties to include per element, and how large the appearance map should get (all `id`/`class` elements vs. a lighter subset). | Implementer | Shipped a small visual allow-list over all `id`/`class` elements. Extend the allow-list if a real task needs more (e.g. flex/grid props); it is a one-line list. |
| OQ-2 | Offscreen browser technique — `SWT.NO_TRIM` far-off-screen `setVisible(false)` shell — and whether polling on the laid-out `.svy-form` subtree is a reliable render-complete signal in practice. | Implementer | Shipped shell + polling. Revisit only if polling proves flaky against real forms; a route-side ready marker is a possible later nicety, not required. |
| OQ-3 | Self-identifying component tag: `data-svy-name`/`data-svy-id` on the component element. | Core (servoy-eclipse) | **DONE** — landed on the LTS branch alongside this work; the tool reads it, and the returned `html` is self-identifying even collapsed. |
| OQ-4 | `data-cy` is a testing-mode attribute; is it guaranteed present on the `/formtemplate` render? | Core / verify | Present on the live render (verified). Identity is now anchored on `data-svy-name`/`data-svy-id` + the tag regardless. |
| OQ-5 | Tool home + whether to migrate existing form tools into `servoy-form`. | Decided (home) / Follow-up (migration) | **DECIDED — new `servoy-form`, `getFormLayout` only.** Migration/removal of the deprecated `servoy-test` form tools is a breaking change → explicit follow-up case. |
| OQ-6 | Keep vs deprecate — or re-point — the heavy `showFormInBrowser`/`screenshotForm` path. | Product | **DECIDED — deprecated** in-description (kept working), pointing at `getFormLayout`; `screenshotForm`'s niche is now "with live data in the running client". Removal is a later follow-up. |
| OQ-7 | One reused hidden browser vs a bounded pool (~5). Embedded Chromium is shared state. | Implementer | **DECIDED — one reused, serialized**, now; **pool-ready** (handle-based) so a ~5 pool can be added later without reshaping callers. |
| OQ-8 | Cross-repo build order: the target platform must carry `IBrowser.evaluate`, `IBrowser.captureScreenshot`, `data-svy-*` **and** the `/formtemplate` route before this tool is built/shipped. | Release (user) | User lands all on the **2026.03 LTS** branch and merges forward; confirm the update site the `launch_target_aiplugin` target resolves is at/after those commits. |
| OQ-9 | Does the MCP client (opencode) forward `ImageContent` tool-results to the model, and should the chat UI render them inline? | Verify / Product | Model reception **verified live** (a vision model read the attached screenshot). Chat-UI inline rendering is a separate optional front-end change; `screenshotFile` is the fallback. |
| OQ-10 | Add a headless unit test for the `McpToolResult` → `McpServerFactory` text+image expansion? | Implementer | **Optional.** The change is small and backward-compatible and is exercised via the tool; add a focused unit test if the pipeline grows. |

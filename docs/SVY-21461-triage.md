# Triage Report — SVY-21461

**Verdict:** PROCEED

## Reported problem

Today the Servoy AI Copilot (`com.servoy.eclipse.opencode`) embeds the **opencode web UI**
in a browser view and applies a set of UI hacks to make that third-party front-end fit
inside the Servoy Developer IDE. Those hacks live in `OpenCodeBranding` (CSS/JS injected on
every page load — an orange re-skin of opencode's `--v2-blue-*` scale, tool-call hiding, a
project-seed script) and in `OpenCodeView` (a state machine that navigates the embedded
browser to base64-encoded opencode SPA URLs like `/{encodedDir}/session/{id}?directory=…`,
plus a "seed opened projects" injection).

The ask: stop depending on the opencode web front-end and its DOM-injection hacks, and ship
our **own simplified chat UI** built directly on top of the **opencode-cli** — the same way
OpenChamber does — that we fully control.

First-iteration scope (from the ticket):
- Clean chat view: auto-growing text area.
- Nicely formatted LLM responses rendered above the input.
- File and image attachment support.
- New-session support.
- Existing sessions listed and accessible in the view.
- View is always scoped to a single project directory that Servoy provides.

Out of scope (first iteration): model selection.

## Root-cause assessment

This is a **feature request**, not a defect — there is no failing code path to fix. The
"root cause" being addressed is architectural: the current design couples our IDE view to a
front-end we do not own, forcing brittle DOM/CSS injection to make it presentable.

Evidence that the fragility is real and lives in our code:
- `OpenCodeBranding.java` (281 lines) is almost entirely `!important` CSS overrides keyed to
  opencode's internal token names (`--v2-blue-100…1200`) plus JS that hides tool wrappers by
  `data-component` selectors. Every one of these breaks the moment opencode restyles.
- `OpenCodeView.buildInjectScript()` is re-run on **every** `LocationEvent.changed`
  (`OpenCodeView.java:104`), and `seedOpenedProjectsIfNeeded()` reaches into the SPA to seed
  its Home list (`OpenCodeView.java:125`). Both are workarounds for not owning the UI.
- Navigation depends on opencode's private URL shape — base64-encoded directory + `/session`
  segments (`resolveSessionUrl`, `OpenCodeView.java:374`) and screen-scraping the first `id`
  out of the `/session` JSON with `indexOf('"id"')` (`findLastSessionId`, `:386`).

The proposed direction is technically well-founded. Opencode's `opencode serve` is a
documented headless **HTTP server exposing an OpenAPI surface** (`opencode.ai/docs/server`),
with a JS/TS SDK and community SDKs (Rust/Python) built on the same REST + SSE API. The
relevant endpoints are already exercised by our own and OpenChamber's code:
`GET /session` (list, with `directory=` / `roots=` / `limit=`), `GET /session/{id}`,
`GET /session/{id}/message`, `POST /session/{id}/prompt_async`, and the `/event` /
`/global/event` **SSE** streams for live output. OpenChamber's `packages/web/server` is
exactly a thin Node layer that proxies these (`opencode-proxy.test.js` shows
`/api/session/.../prompt_async`, `/api/global/event`, etc.) while its own front-end
(`packages/web/src`, `packages/ui`) renders the chat. That is precisely the shape the ticket
proposes, only with a Java backend instead of Node.

The deployment mechanism named in the user context is real and already used by many bundles:
the Tomcat that runs inside Servoy Developer resolves `IServicesProvider` extensions and
mounts their `ServletInstance`s (`org.apache.tomcat.starter.IServicesProvider`,
`TomcatStartStop.java:98-111`, `Activator.getServletInstances`). At least eight bundles
already register one (e.g. `com.servoy.eclipse.ngclient.ui`, `com.servoy.eclipse.core`,
`servoy_mcp`), so `com.servoy.eclipse.opencode` contributing its own servlet + serving an
Angular `dist/` from the bundle is a proven pattern, not a new mechanism.

## Ticket premise check

The premise holds up well, and the user context sharpens it into a concrete, buildable
architecture:

- **Front-end**: an Angular app in a subdirectory of `com.servoy.eclipse.opencode`, built to
  a `dist/` that ships inside the bundle jar.
- **Backend**: a Java servlet registered via the existing `IServicesProvider` /
  `@WebFilter`-style Tomcat extension, served from the in-Developer Tomcat on localhost.
- **Bridge**: Angular talks REST/SSE to the Java servlet; the servlet talks to the running
  `opencode serve` HTTP API (which `RunOpencodeCommand` already launches on a free port from
  4096, `RunOpencodeCommand.java:65`, and whose readiness `Activator` already tracks).

One design decision is worth calling out but does **not** block a spec — it is a choice
between two valid shapes:

1. **Java servlet as a real proxy/BFF** (as the ticket literally describes): Angular → Java
   servlet → opencode HTTP API. Gives us a server-side seam for auth, project scoping, and
   massaging responses.
2. **Thin backend, Angular talks to opencode directly** on `127.0.0.1:{port}`: the Java side
   only serves static assets and hands the Angular app the opencode port. Less code, but no
   server-side control point and CORS/port-coupling to manage.

The ticket's own wording ("Angular talks via a (REST) servlet on localhost with the Java
backend that starts the opencode CLI process") favours option 1, and OpenChamber validates
that shape. I recommend option 1 but the spec author should confirm.

The current embedded-browser `OpenCodeView` still has value as the **host** for the new UI:
instead of pointing `IBrowser` at opencode's SPA, it points at our servlet's URL. The
existing browser abstraction rule (never touch Chromium directly, go through
`com.servoy.eclipse.ui.browser.IBrowser`) continues to apply.

## Approaches considered

1. **Angular front-end + Java servlet backend (BFF), backend proxies opencode HTTP API; UI
   hosted in the existing `IBrowser` view.** (Ticket + user context.)
   - Pros: full control over UX; deletes the `OpenCodeBranding` injection and URL-scraping
     hacks; server-side seam for project scoping/auth; reuses proven `IServicesProvider`
     Tomcat mechanism and the existing `RunOpencodeCommand` lifecycle; mirrors OpenChamber's
     validated architecture; scope is well-bounded (chat, sessions, attachments).
   - Cons: net-new Angular app + build wiring inside a PDE bundle; must reimplement chat
     rendering, SSE streaming, attachment upload that opencode's UI gives for free; ongoing
     ownership of that UI.

2. **Angular front-end, thin Java backend that only serves assets; Angular calls opencode
   directly on localhost.**
   - Pros: least backend code; still removes the DOM hacks.
   - Cons: no server-side control point; couples the browser to the opencode port and its raw
     API/CORS; diverges from the ticket's stated intent.

3. **Reuse OpenChamber's web front-end (`packages/web` + `packages/ui`) instead of writing
   our own.**
   - Pros: a full-featured chat UI already exists.
   - Cons: it is a large React/Vite app with its own Node server and many features out of
     scope (multi-run, relay, tunnels, mobile). Embedding it re-creates the "front-end we
     don't own" problem we are trying to escape, in a heavier form. Good as a **reference**
     for how they proxy opencode, not as the thing to ship.

4. **No code change — keep the embedded opencode web view and its hacks.**
   - Pros: zero effort.
   - Cons: does not address the stated goal; leaves us exposed to every opencode UI change
     breaking the injected CSS/JS and URL scraping. The whole ticket exists because this is
     unsatisfactory.

## Recommendation

**PROCEED with Approach 1.** Build a Servoy-owned Angular chat UI in a subdirectory of
`com.servoy.eclipse.opencode`, backed by a Java servlet registered through the existing
`IServicesProvider` Tomcat extension, with the servlet proxying opencode's documented HTTP +
SSE API (`/session`, `/session/{id}/message`, `/session/{id}/prompt_async`, `/event`). Host
the built app in the existing `OpenCodeView` via `IBrowser` (pointing it at the servlet URL
instead of the opencode SPA), and retire the `OpenCodeBranding` injection and the
URL/session-scraping logic in `OpenCodeView`. Keep the project scoped to the single directory
Servoy already resolves (`OpenCodeUtil.getActiveProjectPath()`), matching the ticket's
"single project" requirement.

Use OpenChamber (`packages/web/server` for the proxy shape, `packages/web/src` + `packages/ui`
for the chat/SSE rendering patterns) purely as a **reference implementation** — not as code to
embed.

Alternatives considered and rejected as the primary path: Approach 2 (thin backend) loses the
server-side seam the ticket wants; Approach 3 (embed OpenChamber's UI) reintroduces the
unowned-front-end problem; Approach 4 (no change) fails the ticket's goal.

The spec author should confirm the one open design decision: BFF-proxy servlet (recommended,
Approach 1) vs. asset-only backend (Approach 2).

## Git history findings

None directly relevant to a root cause — this is greenfield work, not a regression. For
context, the code the feature will replace is recent (`OpenCodeView`, `OpenCodeBranding`,
`RunOpencodeCommand` carry `@since 2026.06` / 2026 copyright) and the injection/URL-scraping
approach it uses is exactly what the ticket asks to move away from. No prior spec for
SVY-21461 exists in `docs/`.

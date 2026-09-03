# SVY-21363 — Servoy AI empty session history: cause & fix

> **Status: FIXED (Servoy side).** Live investigation found the empty Home is caused by
> opencode's Home reading its "opened projects" list from browser `localStorage`, which Servoy
> never seeds. The fix injects a small idempotent script on page load that adds the active
> workspace to that list, so Home shows the solution's session history. Validated against
> **opencode-ai 1.18.26**.
>
> _(Supersedes the earlier NO_ACTION conclusion in this document's history, which was based on
> an incomplete `/api/session` bucketing theory — see §3.)_

## 1. Summary

On the Servoy AI view, the opencode Home page showed *"Nothing here yet — Create a session to
get started"* even though sessions existed. Live investigation against the running bundled
opencode server established:

- The **server is correct** — session/project queries return the data for every path form.
- Servoy's **deep-link resume works** — the view opens the last session and loads its messages.
- The empty Home is caused by a **client-side `localStorage` list not being seeded**. opencode's
  Home renders its project cards (and their session history) from the
  `opencode.global.dat:server` key, specifically the `projects.local[]` array of
  `{ worktree, expanded }` entries. Servoy deep-links straight into a session and never adds the
  workspace to that list, so clicking Home finds no project and shows the empty state.

## 2. Confirming what is NOT the cause

The original triage attributed the symptom to opencode **#21340** — the server storing session
`directory` with backslashes but the web UI querying with forward slashes. This was disproven:

- On **1.18.26**, `GET /session?directory=<path>` returns the sessions for **backslash**,
  **forward-slash**, and **double-backslash** forms alike. The exact-match bug is gone.
- `GET /project` returns all projects; `GET /project/current?directory=<either form>` resolves.
  `OpenCodeUtil.findLastSessionId` returns the last session and the view resumes it.

So #21340 is **fixed in 1.18.26**; no version bump is warranted.

## 3. How the real cause was found

The Servoy AI embedded browser (Equo/Chromium) exposes **no DevTools**. A temporary diagnostic
was added to `OpenCodeView` (tagged `TEMP DIAGNOSTIC (SVY-21363)`, since removed) that wrapped
`window.fetch`, `XMLHttpRequest`, and — critically — `localStorage.setItem`, reporting each call
to Java via `IBrowser.addBrowserFunction`.

Two facts emerged:

1. **An early theory about `/api/session` bucketing was a red herring.** Home does call
   `GET /api/session?limit=5000` (no directory filter) and the ~27 KB body does arrive with the
   sessions present. But that is not why the card is empty.

2. **The empty Home is driven by `localStorage`, not the fetch.** Capturing the manual
   *"Add project"* flow showed the web app writing the opened project into:

   ```
   key:   opencode.global.dat:server
   value: {"list":[],"projects":{"local":[{"worktree":"C:\\R_D\\servoy-workspace\\master","expanded":true}]},
           "lastProject":{"local":"C:\\R_D\\servoy-workspace\\master"},"recentlyClosed":{}}
   ```

   Once that entry exists, Home renders the project card and its sessions. With it absent (the
   default in the Servoy view, which only deep-links to a session), Home is empty.

   Persistence behavior observed: after a manual add the list survives a **view reopen** (same
   server/browser session) but is empty again after a full **server restart** unless re-seeded —
   i.e. it is client/session state, not the durable server-side project registry (`/project`,
   which does persist the project object across restarts).

## 4. The fix

Servoy already injects branding CSS/JS on every page load via
`OpenCodeView`'s `LocationListener.changed` → `OpenCodeBranding.buildInjectScript()`. The fix
adds a second injected script through the same mechanism.

- **`OpenCodeBranding.buildProjectSeedScript(String worktree)`** — builds an idempotent JS IIFE
  that reads `opencode.global.dat:server`, and if `projects.local[]` does not already contain the
  worktree, pushes `{ worktree, expanded:true }` and sets `lastProject.local`. It never removes
  other projects the user may have opened, and swallows parse errors (best-effort seeding).
- **`OpenCodeView.LocationListener.changed`** — after the branding script, resolves the active
  workspace via `OpenCodeUtil.getActiveProjectPath()` and, when non-null, runs the seed script.

### Correctness / portability notes

- The worktree is **not hardcoded**. It comes from `getActiveProjectPath()` at runtime (the git
  root of the active solution, else the project dir), so each user/machine seeds their own path.
- The path is `Path#toString()`, which uses the platform separator — matching the backslash form
  opencode itself persists on Windows (and native forward-slash form elsewhere). The same string
  already drives the server launch and deep-link, so the seeded entry and the sessions reference
  the identical path.
- The `exists` check is **case-insensitive and separator-insensitive** (`\` vs `/`), so it does
  not create a duplicate entry if opencode ever persists the worktree in a different separator
  form.

## 5. Verification

- `com.servoy.eclipse.opencode` compiles with 0 errors (pre-existing unrelated warnings remain).
- After the fix, the workspace card and its session history appear on Home.
- Confirmed persistent across repeated Servoy restarts.
- All `TEMP DIAGNOSTIC (SVY-21363)` scaffolding was removed after verification.

## 6. Known limitation (accepted for v1)

On a **truly cold profile** (empty `localStorage`), the very first page load can still show an
empty Home until the view is reopened, because the web app hydrates its in-memory store from
`localStorage` before our `changed`-time injection lands. It self-heals on the next load and is
stable thereafter. Accepted for v1 ("show something, gather feedback"). If it needs to be
eliminated, seed earlier than `LocationListener.changed` (a document-start injection hook on the
browser backend) so `localStorage` is set before the SPA hydrates.

## 7. Scope notes

- **Home scope (all projects vs active solution):** opencode's Home is a multi-project hub. The
  current fix seeds only the active workspace, but Home may still list other projects the user
  previously opened. Product decision (per ticket discussion) was to ship this and gather
  feedback before scoping Home strictly to the active solution.
- **No `opencode-ai` bump** for this cause; keep the current `~1.18.x` auto-update.
- The `opencode.global.dat:server` key and `projects.local[]` shape are **opencode internals**
  (not a public API), validated against 1.18.x. If a future opencode version changes them the
  seed silently no-ops and Home reverts to empty — revisit if that regresses.

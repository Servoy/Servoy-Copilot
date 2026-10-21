# SVY-21461 — Peer-review summary: custom Angular chat UI on opencode-cli

**Risk: MODERATE.** A large but self-contained feature — the old embedded opencode
web-view (with CSS/JS injection + SPA URL/session scraping) is cleanly replaced by a
Servoy-owned Angular chat UI served through a new ~820-line backend-for-frontend servlet
that proxies opencode-cli's HTTP + SSE on loopback. The one item needing a human decision
is the absence of an origin/CSRF guard on the servlet, whose real exposure depends on how
the Developer Tomcat connector binds.

**Scope reviewed:** Servoy-Copilot @ `dfb0039~1 .. bbb1691` (25 SVY-21461 commits, on
`master`, Fix Version 2026.12 — matches). NOTE: the 17 `com.servoy.eclipse.developer.mcp`
Java files and ~82 MCP test files in the same commit range are **not** SVY-21461 — they are
other cases (SVY-21443/21460/21473/21510/21414/21489/21523…) riding the range — and were
excluded from this review.

## Manual test plan

1. Open Developer with an active solution and Servoy AI configured; open the Servoy AI
   view. Confirm the Servoy chat UI loads (not opencode's web UI).
2. Send a prompt; confirm a formatted markdown response streams in live.
3. Attach a file and an image; confirm both are sent and appear in the transcript.
4. Click New session, switch between sessions in the sidebar; confirm each shows its own
   transcript and the view is scoped to the active project only.
5. Confirm prior sessions for the active solution appear in the sidebar (session-history
   parity with the old view).
6. **Leak check:** open/close the AI view ~10×; confirm `opencode-sse-*` threads and
   opencode connections return to baseline. Kill Developer; confirm no `opencode`/`bun`
   processes survive and the port frees.
7. **Theme:** toggle the IDE dark/light theme with the view open; note whether a reload is
   needed (expected — theme is read once from `?darkmode=`).
8. **Clean build:** build the bundle from an empty `target/`; confirm `webui-dist/index.html`
   is produced and packaged, and the view loads on a clean product build.
9. **Automated:** run the Angular Vitest specs under `webui/` and the servlet route/guard
   unit tests (`OpencodeChatServletTest`, `OpencodeChatServletGuardTest`).

## Possible improvements / follow-ups

- **Origin/CSRF guard.** `OpencodeChatServlet` has no `Origin`/`Referer`/`Sec-Fetch`/CSRF
  check (only readiness/solution/port), and injects its own basic-auth, so a forged
  same-site request needs no credential. Exposure depends on whether the Developer embedded
  Tomcat connector binds loopback-only (this change does not set the binding, and it was not
  verified here). Loopback-only → CSRF-from-local-page only (low on a dev box); routable →
  add a same-origin/nonce guard on the mutating `rest_api/**` routes.
- **opencode schema coupling.** The Angular app and servlet hard-code the pinned opencode
  server's V2 schema (`uri` attachment field, `{location,data}` envelopes, the `rest_api→/api`
  rewrite, the `location`-body splice, cursor pagination). A pin bump against a drifted
  schema fails **silently** at runtime (vanished sessions / rejected uploads / 401s), not at
  build. Confirm the `update-opencode` skill's re-check is a sufficient guardrail. (Confirm
  the current exact pin value — reports read it inconsistently as `2.0.26` vs `2.1.1`.)
- **Session-history parity.** The old Home "opened projects" seed (SVY-21363) is removed;
  verify the new directory-scoped sidebar fully covers what users previously saw.
- **If ever exposed outside the IDE:** `readBody` buffers the whole request with no size
  cap, and session export materialises the whole transcript — both fine for local
  single-user use only.

Security relevance: REVIEW NEEDED but developer-local — loopback-only upstream, fixed-arg
process launch (no shell/injection), per-launch password never logged or exposed, no
open-proxy/SSRF; SSE teardown verified idempotent and multi-path.

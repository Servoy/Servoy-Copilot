# Spec: SVY-21688 — Gate dev_mcp endpoints on svyAI entitlement (per-request, loopback-only, 404-on-deny)

> **Reviewer follow-up (implemented — see §8).** After the initial implementation a
> security review (comment 234276) found two holes in the `@servoy.com` path: (1) a faked
> **offline** login as `x@servoy.com` (username saved while the Cloud host is blocked, no
> server verification) passed `isServoyInternalUser()` and opened the MCP with no key and no
> token; (2) there was no `Origin` check, so a **DNS-rebinding** request from `127.0.0.1`
> passed the loopback peer check. Both are now closed **without removing the intentional
> token-free `@servoy.com` bypass** (commit `1c4b7bc`): `isServoyInternalUser()` additionally
> requires a server-issued login token, and an `Origin` guard rejects a present, non-loopback
> `Origin` with 404. The full, as-built gate order is in **§8**; §3 below records the original
> design for history.

## 1. Goal

Close the "E0 hole": a logged-in Servoy Developer user **without** svyAI entitlement (Cloud
login returns `svy_ai_key: null`, so `GENAI_API_KEY` is never set), and any **off-box** MCP
client, must not be able to reach the Developer MCP endpoints at
`http://localhost:<port>/dev_mcp/<server>`. A non-entitled or remote caller that would otherwise
drain the shared svyAI/LLM budget must see the endpoint as if it does not exist (a bare Tomcat
`404`), while legitimately entitled local users and Servoy-internal (`@servoy.com`) developers keep
working unchanged.

This is delivered as a **live, per-request gate** at the single `/dev_mcp` choke point
(`BearerTokenAuthenticationFilter`), plus a **cross-repo logout cleanup** so the gate actually
closes on logout rather than at next Developer restart.

This is R&D on the unreleased Servoy AI product (Jira: Critical, New Feature, Open).

## 2. Background

### 2.1 Why not the ticket's literal proposal (gate registration)

The ticket asks to gate `McpServerRegistry` servlet **registration** on entitlement. Triage
(`docs/SVY-21688-triage.md`) established, and verified against code, that registration-time gating
**is not implementable** on this platform:

- The `/dev_mcp/<server>` servlets are mounted exactly once, inside a one-shot Tomcat
  `AFTER_START_EVENT` lifecycle listener (`TomcatStartStop`), via
  `getServletInstances()` → `StandardContext.addChild(wrapper)`. This happens **at workbench
  startup, before login**, and is **never re-driven** on a later login/logout event.
- A static "register only if entitled" check in `McpServerRegistry.initialize()` would therefore
  observe "not entitled" for everyone (login has not happened yet) and permanently break the
  endpoints. There is no Servoy API to re-invoke `getServletInstances()` post-startup, so a true
  dynamic mount/unmount (E0) would need new platform plumbing — out of scope here.

So the servlets must stay registered; the entitlement/identity decision has to be made
**per request**, where the login identity and `GENAI_API_KEY` are reliably available. The developer
approved **Approach A** in full.

### 2.2 Current shape of the choke point

`com.servoy.eclipse.developer.mcp` →
`src/com/servoy/eclipse/developer/mcp/auth/BearerTokenAuthenticationFilter.java` (current:
`modificationStamp 406924`, 146 lines). It is a `HttpServlet` that **also** implements
`jakarta.servlet.Filter`, wrapping a delegate servlet; it is deliberately a servlet-mapping-scoped
filter, **not** a Tomcat `ValveBase`, so it only sees `/dev_mcp/<server>/*` requests and never
intercepts unrelated Servoy Tomcat (e.g. NG-client) requests. Both request entry points funnel
through one private `authenticate(...)`:

- `service(HttpServletRequest, HttpServletResponse)` — `if (!authenticate(request, response)) return;`
  then `delegate.service(request, response)` (wrapped in a JSON-RPC parse-error catch).
- `doFilter(ServletRequest, ServletResponse, FilterChain)` — `if (!authenticate(...)) return;` then
  `chain.doFilter(...)`.

Current `authenticate(request, response)` logic, in order:
1. `if (isServoyInternalUser()) return true;` — the `@servoy.com` bypass, **independent of the key**.
2. Read `Authorization` header; if missing or not `Bearer ` → `sendUnauthorized(..., "Missing or invalid Authorization header")` → **401** → `return false`.
3. Compare the token; if `expectedToken` null/blank or mismatch → `sendUnauthorized(..., "Invalid token")` → **401** → `return false`.
4. Otherwise `return true`.

`isServoyInternalUser()` (private static) reads the login username from `ISecurePreferences` using
`ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY` / `ServoyLoginDialog.SERVOY_LOGIN_USERNAME` and returns
`username != null && username.toLowerCase().endsWith("@servoy.com")`, swallowing any exception as
`false`. It never reads the key — confirmed this is an **identity** signal, not "has a key".

`sendUnauthorized(response, message)` sets `SC_UNAUTHORIZED` (401), content-type
`application/json`, and writes a `{"error": "..."}` body. This **announces that the endpoint
exists but is locked** — exactly what a "look absent" design must not do.

### 2.3 Entitlement signal and the logout gap (cross-repo)

`GENAI_API_KEY` (and `SERVOY_SKILLS_ZIP`) are set as JVM **system properties** by
`ServoyLoginDialog` (repo `servoy-eclipse`, bundle `com.servoy.eclipse.ui`, class
`com.servoy.eclipse.ui.dialogs.ServoyLoginDialog`) from the Cloud login 200 response
(`svy_ai_key`), with no `@servoy.com` branch client-side. Presence of a non-blank `GENAI_API_KEY`
is the svyAI entitlement signal available in this JVM.

The logout path `ServoyLoginDialog.clearSavedInfo()` currently (verified) only clears the
secure store:

```java
public static void clearSavedInfo()
{
    ISecurePreferences preferences = SecurePreferencesFactory.getDefault();
    ISecurePreferences node = preferences.node(SERVOY_LOGIN_STORE_KEY);
    node.clear();
}
```

It does **not** clear the `GENAI_API_KEY` / `SERVOY_SKILLS_ZIP` system properties. On logout the
secure-store username clears (so the `@servoy.com` bypass correctly flips off), but an entitled
**non-@servoy.com** user who logs out keeps `GENAI_API_KEY` set in the JVM, so the gate's
entitlement branch stays `true` and the endpoint **remains open until Developer restart**. Closing
this is a required part of the fix and lives in a **different git repository** (see §3.5).

## 3. Design

All deny decisions (off-box, unentitled, wrong token) must happen **before** the request reaches the
wrapped delegate (`delegate.service(...)` / `chain.doFilter(...)`), and must emit a **bare Tomcat
404** so the endpoint is indistinguishable from a path that was never mapped.

### 3.1 New evaluation order in `authenticate(request, response)`

Replace the current 3-step logic with this ordered gate. The method keeps its signature
(`private boolean authenticate(HttpServletRequest, HttpServletResponse)`, returning `true` to
proceed and `false` after it has written the response), so both `service()` and `doFilter()`
enforce it unchanged.

1. **Loopback check (FIRST).**
   - If the request's **transport-level** remote address is NOT a loopback address → deny with
     404 and `return false`.
   - Resolve via `InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()`. This
     accepts `127.0.0.1`, `::1`, and `0:0:0:0:0:0:0:1`.
   - Use **only** `request.getRemoteAddr()` (the actual TCP peer). **Never** consult
     `X-Forwarded-For`, `Host`, or any other request header — a remote attacker sets those freely.
   - **Fail closed:** any exception/parse failure (or a null/blank remote address) ⇒ treat as
     **non-loopback** ⇒ 404.

2. **`@servoy.com` bypass (INDEPENDENT of the key).**
   - `if (isServoyInternalUser()) return true;` — proceed to the delegate regardless of whether
     `GENAI_API_KEY` is set. This MUST remain an independent identity check, not a "has a key"
     check: a seat-less internal user and the headless skilltest rely on it and may have no key.
   - `isServoyInternalUser()` is unchanged.

3. **Entitlement check.**
   - Else, require the svyAI entitlement signal: `GENAI_API_KEY` system property present and
     **non-blank** (`System.getProperty("GENAI_API_KEY")`, then `!= null && !isBlank()`).
   - If absent/blank → deny with 404 and `return false` (look absent — no entitlement, no endpoint).

4. **Bearer token check (for entitled, non-internal callers).**
   - The existing bearer comparison still applies: read `Authorization`, require `Bearer `, and
     compare against `expectedToken` (reject if `expectedToken` is null/blank).
   - On a **missing/invalid header OR a token mismatch**, deny with **404** (changed from the
     current 401), and `return false`. A stale or guessed token must not be able to distinguish
     "endpoint exists, wrong token" from "no endpoint".
   - On match → `return true`.

Pseudocode:
```
authenticate(req, resp):
  if !isLoopback(req)                 -> sendNotFound(resp); return false   // off-box -> absent
  if isServoyInternalUser()           -> return true                        // @servoy.com, key-independent
  if GENAI_API_KEY blank/absent       -> sendNotFound(resp); return false   // not entitled -> absent
  // entitled, non-internal: existing bearer check, but deny == 404
  if authHeader missing/not "Bearer " -> sendNotFound(resp); return false
  if expectedToken blank || mismatch  -> sendNotFound(resp); return false
  return true

isLoopback(req):
  try { return InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress(); }
  catch (any) { return false; }   // fail closed
```

### 3.2 The 404 deny response ("look absent")

All deny paths MUST emit a response indistinguishable from an unmapped path:

- Use `response.sendError(HttpServletResponse.SC_NOT_FOUND)` (404) — **not** 401/403.
- **No** `WWW-Authenticate` header, **no** `Allow` header, **no** custom headers.
- **No** JSON error body (no `{"error":...}`). `sendError(404)` lets the container produce its
  standard 404 page, matching a never-registered path.
- Deny must be written **before** `delegate.service(...)` / `chain.doFilter(...)` and must not
  fall through to the delegate.

Replace the current `sendUnauthorized(response, message)` (401 + JSON body) accordingly. Options:
replace it with a `sendNotFound(response)` helper that calls `sendError(404)`, or inline the
`sendError` calls. The 401-with-JSON behaviour is removed entirely — there is no remaining caller
that should return 401. Keep the existing `ServoyLog` warnings (they are diagnostic and server-side
only; they do not leak to the client), but the HTTP status/headers/body on every deny must be the
bare 404.

> **Logging note:** server-side `ServoyLog` lines may still record why a request was denied
> (off-box address, no entitlement, token mismatch). Only the **HTTP response** must look absent;
> local developer-log diagnostics are fine and useful.

### 3.3 Both entry points enforce the same gate

Because both `service()` and `doFilter()` already delegate to the one private `authenticate(...)`,
updating `authenticate(...)` enforces the gate on both paths with no structural change. Keep that
single-choke-point structure; do not duplicate the gate logic into the two entry points.

### 3.4 Connector stays on 0.0.0.0 (rejected alternative)

The Tomcat connector is **not** narrowed to 127.0.0.1. The same Servoy web-server port also serves
the NG client, which is actively developed and **sometimes tested from other machines**; binding the
connector to localhost would break legitimate cross-machine NG-client testing. The loopback check is
**servlet-scoped** (it lives in the `/dev_mcp`-mapped filter only), so NG-client remote access on the
same port is unaffected. This is an explicit rejected alternative, recorded in the ticket.

> **Reverse-proxy assumption:** if a local reverse proxy ever fronts the dev server, all requests
> would appear as `127.0.0.1` and the loopback check would pass for proxied remotes. The design
> assumes no local reverse proxy in front of Servoy Developer — reasonable for a developer machine.

### 3.5 Cross-repo change — `ServoyLoginDialog` logout cleanup (DIFFERENT git repo)

> **This change is in a SEPARATE git repository from Part 1.** Part 1 is in
> `com.servoy.eclipse.developer.mcp` (repo `D:\git\Servoy-Copilot`). Part 2 is in
> `com.servoy.eclipse.ui`, class `com.servoy.eclipse.ui.dialogs.ServoyLoginDialog`, in the
> **servoy-eclipse** repo — checked out for the `lts_2026` branch at
> `D:\git\servoy_lts_latest\servoy-eclipse`.

Add property cleanup to the logout path so the gate's entitlement branch flips to `false` on logout
(not at next restart). In `clearSavedInfo()` (invoked from the `ServoyLoginStatus` logout action),
after clearing the secure store, also clear the two system properties:

```java
public static void clearSavedInfo()
{
    ISecurePreferences preferences = SecurePreferencesFactory.getDefault();
    ISecurePreferences node = preferences.node(SERVOY_LOGIN_STORE_KEY);
    node.clear();
    System.clearProperty("GENAI_API_KEY");
    System.clearProperty("SERVOY_SKILLS_ZIP");
}
```

Apply this to **every supported Servoy branch** (`release`, `lts_2026`, …) since each branch has its
own `ServoyLoginDialog`. The logout-cleanup gap is original (predates the AI key), not a regression,
so the same edit is valid on each branch.

### 3.6 Interaction with the `@servoy.com` bypass ordering — loopback FIRST (decided)

The gate puts the **loopback check before** the `@servoy.com` bypass. Rationale: even internal
(`@servoy.com`) developers normally drive the MCP from localhost, and an off-box request should look
absent regardless of who is logged in — a stricter, simpler default. If a concrete need ever arises
for an internal user to reach `/dev_mcp` cross-machine, that is a deliberate relaxation and is called
out in §7 (Open questions) rather than built in now. **Default and recommended: loopback first.**

## 4. Implementation plan

### Part 1 — `com.servoy.eclipse.developer.mcp` (main change)

1. `BearerTokenAuthenticationFilter.java`:
   - Add import `java.net.InetAddress`.
   - Add a private helper `isLoopbackRequest(HttpServletRequest request)` returning the
     `InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()` result, catching any
     exception and returning `false` (fail closed). Only `getRemoteAddr()` — no headers.
   - Rewrite `authenticate(...)` to the ordered gate in §3.1: loopback → `isServoyInternalUser()`
     bypass (independent of key) → `GENAI_API_KEY` presence/non-blank → existing bearer check with
     mismatch now denying.
   - Replace `sendUnauthorized(...)` with a 404 deny: either a `sendNotFound(HttpServletResponse)`
     helper calling `response.sendError(HttpServletResponse.SC_NOT_FOUND)`, or inline `sendError`
     at each deny site. Remove the 401 + `{"error":...}` body entirely. Keep server-side
     `ServoyLog` diagnostics.
   - Leave `service()` / `doFilter()` structure intact (both already route through
     `authenticate(...)`). Keep the delegate JSON-RPC parse-error catch untouched.
2. Post-edit: run the compile-error + quick-fix loop (`eclipse-ide_getCompilationErrors`) until the
   `com.servoy.eclipse.developer.mcp` project is error-free. Address top-two-severity SpotBugs in
   the touched code.

### Part 1 tests — `com.servoy.eclipse.developer.mcp.tests` (plainjunit)

Extend `src/test/java/com/servoy/eclipse/developer/mcp/plainjunit/auth/BearerTokenAuthenticationFilterTest.java`
(JUnit 5/6 Jupiter). Its `FakeHttpServletRequest` already stubs headers and returns `"127.0.0.1"`
from `getRemoteAddr()`; make the remote address **configurable** (constructor arg or setter) so
tests can supply loopback vs non-loopback values. Capture the response status/headers/body via the
existing response fake so a test can assert 404-with-no-headers-no-body.

Cover (see §5 for the exact expectations):
- loopback (`127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1`) + entitled + valid token ⇒ delegate invoked (allowed);
- non-loopback remote (e.g. `10.0.0.5`) ⇒ 404, delegate NOT invoked, even with a valid token and entitlement;
- `@servoy.com` internal user from loopback with **no** `GENAI_API_KEY` and **no** token ⇒ allowed;
- unentitled non-internal from loopback (no `GENAI_API_KEY`) ⇒ 404;
- entitled from loopback but **wrong/missing** bearer token ⇒ 404 (not 401);
- deny responses carry no `WWW-Authenticate` / `Allow` header and no body;
- `X-Forwarded-For` set to a loopback value while `getRemoteAddr()` is non-loopback ⇒ still 404
  (forwarded headers do not influence the decision);
- fail-closed: a `getRemoteAddr()` that cannot resolve ⇒ 404.

Drive `GENAI_API_KEY` and the `@servoy.com` username via the test's existing mechanisms; where the
secure-store/`isServoyInternalUser()` or system property cannot be set in a plain unit test, note it
and keep those cases to what the plainjunit harness can control (the triage placed this class in
plainjunit and the existing test already stubs the request). Set/clear `GENAI_API_KEY` around each
test (save/restore) so tests don't leak JVM state.

Run with the plain JUnit runner: `eclipse-ide_runJUnitTests`, `projectName =
com.servoy.eclipse.developer.mcp.tests`, `className =
com.servoy.eclipse.developer.mcp.plainjunit.auth.BearerTokenAuthenticationFilterTest`.

### Part 2 — servoy-eclipse LTS repo (cross-repo)

1. In `D:\git\servoy_lts_latest\servoy-eclipse`, edit
   `com.servoy.eclipse.ui/src/com/servoy/eclipse/ui/dialogs/ServoyLoginDialog.java`
   (`clearSavedInfo()`) per §3.5.
2. Apply the equivalent edit to each supported Servoy branch's `ServoyLoginDialog`.
3. This is a separate commit in a separate repository; it is required for the logout acceptance
   criterion (§5, last item) to hold.

## 5. Acceptance criteria

Concrete and testable (loopback = `127.0.0.1` / `::1` / `0:0:0:0:0:0:0:1` via
`InetAddress.isLoopbackAddress()`):

1. **Off-box ⇒ 404.** A request whose `getRemoteAddr()` is a non-loopback address (e.g. `10.0.0.5`)
   returns HTTP **404**, the delegate is not invoked, even when the `Authorization` bearer token is
   valid and `GENAI_API_KEY` is set.
2. **Internal user, no key ⇒ allowed.** With the login username ending in `@servoy.com`
   (`isServoyInternalUser()` true), a loopback request is allowed (delegate invoked) **even with no
   `GENAI_API_KEY` and no bearer token** — the `@servoy.com` bypass is independent of the key.
3. **Entitled + valid token ⇒ allowed.** A loopback request from a non-internal user with
   `GENAI_API_KEY` set (non-blank) and the correct bearer token is allowed (delegate invoked).
4. **Unentitled non-internal ⇒ 404.** A loopback request from a non-internal user with **no**
   (or blank) `GENAI_API_KEY` returns HTTP **404**, delegate not invoked.
5. **Wrong/missing token ⇒ 404 (not 401).** A loopback request from an entitled non-internal user
   with a missing or mismatched bearer token returns HTTP **404** (not 401), delegate not invoked.
6. **Deny carries nothing.** Every deny response (criteria 1, 4, 5) carries **no**
   `WWW-Authenticate` header, **no** `Allow` header, and an empty/standard-404 body (no JSON
   `{"error":...}`), so it is indistinguishable from an unmapped path.
7. **Forwarded headers ignored.** A request with `X-Forwarded-For` (and/or `Host`) set to a
   loopback value but a non-loopback `getRemoteAddr()` still returns **404**. The loopback decision
   uses only `getRemoteAddr()`.
8. **Fail closed.** A request whose `getRemoteAddr()` cannot be resolved by `InetAddress.getByName`
   is treated as non-loopback and returns **404**.
9. **Both entry points.** The gate yields identical allow/deny outcomes whether the request arrives
   via `service()` (HttpServlet) or `doFilter()` (Filter).
10. **Logout closes the endpoint (Part 2).** After a previously-entitled non-`@servoy.com` user logs
    out (`ServoyLoginDialog.clearSavedInfo()` runs), `GENAI_API_KEY` is cleared, so the next loopback
    request from that user returns **404** without requiring a Developer restart.

## 8. Reviewer follow-up — as-built gate (supersedes §3.1 ordering)

Security review comment 234276 raised two issues in the `@servoy.com` path and they are now
fixed in `BearerTokenAuthenticationFilter`. The intentional token-free bypass for verified
internal users (commit `1c4b7bc`, so internal devs can drive the MCP from external tools that
have no way to obtain the rotating bearer token) is **kept** — the holes are closed around it,
not by forcing the bearer token on internal users.

### 8.1 Hole 1 — faked offline login as `x@servoy.com`

`ServoyLoginDialog` saves the username on a **first** login even when Servoy Cloud is
unreachable (the relaxed offline-login path): `handleLoginTokenResponse(...)` stores
`SERVOY_LOGIN_USERNAME`/`SERVOY_LOGIN_PASSWORD` in the Cloud-unreachable branch without any
server verification. The old `isServoyInternalUser()` only checked
`username.endsWith("@servoy.com")`, so blocking the Cloud host and typing any `@servoy.com`
address granted the bypass with no key and no token.

**Fix:** `isServoyInternalUser()` now also requires a non-blank `SERVOY_LOGIN_TOKEN`. That
token is written **only** after a Cloud HTTP 200 (`handleLoginTokenResponse` OK branch and the
background cloud-retry), so a never-verified offline login does not qualify. The login token is
an opaque Cloud string; the gate can only assert its **presence** locally (a real signature /
expiry check would require svyCloud to issue a signed JWT — out of scope, tracked separately).

### 8.2 Hole 2 — DNS rebinding

The loopback **peer** check is not enough on its own: a malicious page can use DNS rebinding so
the browser connects to `127.0.0.1` (passing the peer check) while the page is served from the
attacker's domain. The browser still reports that domain as `Origin`.

**Fix:** a new `isOriginLocalOrAbsent(request)` guard — if an `Origin` header is **present** and
its host is **not** a loopback address, deny with 404. Absent `Origin` (normal for non-browser
MCP clients and same-origin embedded-browser calls) or a loopback `Origin` passes. Parsed via
`URI.create(origin).getHost()` + `InetAddress.isLoopbackAddress()`; **fails closed** (an
unparsable `Origin` is treated as non-local).

### 8.3 As-built evaluation order

```
authenticate(req, resp):
  if !isLoopbackRequest(req)          -> sendNotFound(resp); return false   // off-box -> absent
  if !isOriginLocalOrAbsent(req)      -> sendNotFound(resp); return false   // DNS-rebind -> absent
  if isServoyInternalUser()           -> return true                        // verified @servoy.com: token-free bypass
  if GENAI_API_KEY blank/absent       -> sendNotFound(resp); return false   // not entitled -> absent
  if authHeader missing/not "Bearer " -> sendNotFound(resp); return false
  if expectedToken blank || mismatch  -> sendNotFound(resp); return false
  return true

isServoyInternalUser():
  username  = secureStore[SERVOY_LOGIN_USERNAME]
  if username == null || !username.endsWithIgnoreCase("@servoy.com") -> false
  token = secureStore[SERVOY_LOGIN_TOKEN]       // only stored after a Cloud HTTP 200
  return token != null && !token.isBlank()      // fail closed on any exception
```

Key points vs. §3.1:
- The `@servoy.com` bypass stays **token-free and key-independent** (unchanged intent), but is
  now gated on a **server-verified** login token, not the username alone.
- A new `Origin` guard runs right after the loopback peer check, before identity/entitlement.
- The `GENAI_API_KEY` entitlement check and the bearer-token check apply only to the
  **non-internal** path, exactly as before — internal users are not forced to carry the token.

### 8.4 Tests

`BearerTokenAuthenticationFilterTest` (plainjunit) covers the as-built gate: `InternalUser`
asserts a verified internal user is allowed **token-free** (even with a wrong/absent token), and
a new `OriginGuard` nested class covers non-local-Origin deny (non-internal and internal),
loopback-Origin allow, absent-Origin allow, and malformed-Origin fail-closed. The new
login-token requirement reads the real secure store, so the username-set-but-token-absent case
belongs in an integrationpde test (the plain tests fake the `isServoyInternalUser()` seam);
51/51 plain tests pass.

## 6. Out of scope

- Token rotation of `SESSION_AUTH_TOKEN` (SVY-21689).
- Hiding / gating skills visibility (SVY-21691).
- True E0 dynamic mount/unmount of the `/dev_mcp` servlets on login/logout (needs new
  Tomcat-starter / lifecycle plumbing; rejected as Approach B in triage).
- Any Tomcat connector bind-address change (connector stays on 0.0.0.0; rejected as Approach D).

## 7. Open questions

1. **Internal user cross-machine access.** — **DECIDED (closed):** loopback-first is accepted. It
   is fine that a `@servoy.com` user also cannot reach `/dev_mcp` from another machine; the only
   requirement is that it works locally. The `@servoy.com` bypass stays **after** the loopback
   check. No cross-machine internal use case is supported, by design.
2. **Testing the `@servoy.com` bypass.** — **DECIDED:** do **not** test the actual
   `ISecurePreferences` secure-storage lookup (that exercises Eclipse's secure storage, not our
   gate logic). Instead, introduce a **minimal package-visible seam** so the "is this an internal
   user" decision can be fully faked in the unit test — so the *gate ordering/behaviour* (loopback →
   internal bypass → entitlement → bearer, with 404 denies) is testable with a stubbed internal-user
   answer. If a seam is undesirable for a given branch, that single branch's test may be skipped
   (documented as covered by code review), but the preferred outcome is a fakeable seam. `GENAI_API_KEY`
   is a system property and must be set/cleared with save/restore around each test so no JVM state leaks.

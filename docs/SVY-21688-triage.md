# Triage Report — SVY-21688

**Verdict:** PROCEED

MCP: gate `dev_mcp` servlet registration on svyAI entitlement (svy_ai_key from login),
except @servoy.com users. Priority **Critical**, type **New Feature**, status **Open**,
R&D on the unreleased Servoy AI product. Related: SVY-21689 (token rotation),
SVY-21691 (hide skills).

---

## Reported problem

A logged-in Servoy Developer user with **no svyAI entitlement** (Cloud returns
`svy_ai_key: null`) can still reach the Developer MCP endpoints at
`http://localhost:<port>/dev_mcp/<server>` from **any** MCP client (ServoyAI, or a foreign
Codex / Claude Code), because the `/dev_mcp` servlets are registered independently of the
svyAI config. The ticket frames this as the "E0 hole": *opencode not starting does not mean
the MCP servlets are absent.* The ticket's **proposed solution** is to gate
`McpServerRegistry` servlet **registration** on entitlement (register only when `@servoy.com`
OR svyAI-entitled), tearing down on logout / entitlement loss.

The threat is a non-entitled caller **draining** the shared svyAI/LLM budget through an
endpoint that should not exist for them.

---

## Root-cause assessment

The symptom is real and I reproduced the code path end to end. Two independent paths exist,
and only one is gated:

1. **opencode path — already gated.** `OpencodeFolderCreatorJob.run()` bails out unless
   `GENAI_API_KEY` is set and a skills-zip source exists, so without entitlement there is no
   npm install, no opencode child, no config. (Confirmed by the ticket comments; consistent
   with the bundle's documented config guard.)

2. **MCP servlet path — NOT gated.** This is the real root cause:
   - `com.servoy.eclipse.developer.mcp/src/.../McpServiceProvider.java:42-59` —
     `getServletInstances(String)` calls `McpServerRegistry.getInstance()`, bootstraps it via
     `Activator.make(...)` when null, and returns `registry.getServletInstances()`. **No
     entitlement check anywhere.**
   - `McpServerRegistry.java:195` (`getServletInstances()`) → `initialize()` (line 210) mounts
     one `BearerTokenAuthenticationFilter`-wrapped transport per built-in server at
     `MCP_PATH_PREFIX + "/" + serverName` (`/dev_mcp/<server>/*`), unconditionally.
   - The only per-request protection today is the bearer token in
     `BearerTokenAuthenticationFilter.authenticate()` (`.../auth/BearerTokenAuthenticationFilter.java:93-120`),
     which an entitled-looking or internal client passes, and which the opencode client is
     handed via `McpEndpointProvider.getAuthToken()`. Entitlement itself is never consulted
     on the MCP path.

So the endpoints exist and answer for **any logged-in user**, entitled or not — exactly the
E0 hole described.

---

## Ticket premise check

The ticket asks to gate **registration**. The prior investigation comments conclude that
registration-time gating **cannot work**, and recommend a **per-request** gate instead. I
verified every load-bearing claim in those comments against the code:

| Claim (from comments) | Verified? | Evidence |
|---|---|---|
| Servlets registered independently / ungated via `McpStartup` + `McpServiceProvider` | ✅ | `McpStartup.earlyStartup()` → `Activator.make(McpServerRegistry.class)`; `McpServiceProvider.getServletInstances()` has no gate (`McpServiceProvider.java:42-59`) |
| `getServletInstances()` is **pull-once at startup**, before login | ✅ | `servoy_release/servoy-eclipse-tomcat/org.apache.tomcat/.../TomcatStartStop.java:86-124` — called once inside the `AFTER_START_EVENT` lifecycle listener; wrappers mounted via `sc.addChild(wrapper)`. It is never re-invoked on a later login event. |
| A static "register only if entitled" check in `initialize()` would see "not entitled" for everyone and permanently break the endpoints | ✅ | Consistent with the single `AFTER_START_EVENT` call above; `initialize()` sets `initialized=true` once and is not re-run per request/login. |
| `isServoyInternalUser()` is an **independent** @servoy.com signal, not "has a key" | ✅ | `BearerTokenAuthenticationFilter` reads `SERVOY_LOGIN_USERNAME` from `ISecurePreferences` (`ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY`) and checks `endsWith("@servoy.com")` — the svyAI key is never read here. **(Reviewer follow-up — see note below — now ALSO requires a non-blank `SERVOY_LOGIN_TOKEN`, so a faked offline login does not qualify; still independent of the svyAI key.)** |
| `GENAI_API_KEY` is set purely from the Cloud response, no @servoy.com branch client-side | ✅ | `servoy_release/.../ServoyLoginDialog.java:269-273` — `System.setProperty("GENAI_API_KEY", svyAiKey)` only when `svy_ai_key` non-blank; no email branch. |
| Current deny is **401**, not 404 (announces "exists but locked") | ✅ | `BearerTokenAuthenticationFilter.sendUnauthorized()` sets `SC_UNAUTHORIZED` (401) with a JSON `{"error":...}` body. To "look absent" this must become `sendError(404)` with no body/headers. |

**Verdict on the premise:** the ticket's *problem* is correct; its *proposed solution*
(gate registration) is **not implementable** on this platform as written. The prior comments'
re-aim to a **per-request gate in `BearerTokenAuthenticationFilter`** is the right correction,
and it is **mostly contained to this repo** (one class + its test), with one necessary change
in the servoy-eclipse LTS repo (logout cleanup — see below).

### Two open questions — now answered from code

- **Does logout clear `GENAI_API_KEY` / `SERVOY_SKILLS_ZIP`?** **NO — this is a real gap.**
  The logout path is `ServoyLoginStatus.java:182` → `ServoyLoginDialog.clearSavedInfo()`
  (`servoy_release/.../ServoyLoginDialog.java:497`), which only does `node.clear()` on the
  secure-store. It does **not** `System.clearProperty("GENAI_API_KEY")` or `SERVOY_SKILLS_ZIP`.
  Consequence for the per-request gate: on logout the secure-store username is cleared, so the
  `@servoy.com` bypass correctly flips off — but an **entitled non-@servoy.com** user who logs
  out keeps `GENAI_API_KEY` set in the JVM, so the gate's "else if GENAI_API_KEY present"
  branch stays true and **the endpoint remains open until Developer restart.** A complete fix
  must add `System.clearProperty("GENAI_API_KEY")` (and `SERVOY_SKILLS_ZIP`) to the logout
  path in `ServoyLoginDialog` (servoy-eclipse repo). This is the one cross-repo change.

- **Does Servoy's embedded Tomcat expose a post-startup servlet add/remove API?** **Not in the
  path used here.** `TomcatStartStop` mounts servlets exactly once, inside a one-shot
  `AFTER_START_EVENT` listener, via `StandardContext.addChild(wrapper)` + `wrapper.addMapping`.
  Tomcat's `StandardContext` can in principle `removeChild`, but there is **no Servoy API that
  re-drives `getServletInstances()` on a login/logout event**, so a clean dynamic mount/unmount
  (true E0) would require new platform plumbing. This confirms E0 is not cheaply reachable and
  E1 is the pragmatic choice.

---

## Approaches considered

**A — Per-request gate in `BearerTokenAuthenticationFilter` (E1 dressed as E0). RECOMMENDED.**
Add a live, per-request check at the single choke point every `/dev_mcp` request passes:
```
service(req):
  if !isLocalRequest(req)        -> sendError(404); return   // off-box -> look absent
  if isServoyInternalUser()      -> delegate.service(...)     // @servoy.com (independent of key)
  else if GENAI_API_KEY present  -> (then bearer check)       // entitled
  else                           -> sendError(404); return    // not entitled -> look absent
  // bearer mismatch -> also 404 (not 401), no WWW-Authenticate/Allow, no JSON body
isLocalRequest(req): InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()  // fail closed
```
- Loopback via `InetAddress.isLoopbackAddress()` (handles `127.0.0.1`, `::1`,
  `0:0:0:0:0:0:0:1`), on transport-level `getRemoteAddr()` only — **never** `X-Forwarded-For`
  / `Host` (a remote attacker sets those freely). Fail closed on any error.
- Keep the Tomcat connector on `0.0.0.0` (the same port serves the NG client, tested from
  other machines) — the loopback check is servlet-scoped, so NG-client remote access is
  unaffected. This is the explicit decision in the ticket's second comment.
- Timing problem vanishes: not-logged-in → 404; logs in entitled → next request allowed;
  logout/loss → next request 404, with no re-registration.
- **Pros:** correct per-request semantics; contained to one class + its test; the loopback
  check also shrinks the SVY-21689 remote-drain surface. **Cons:** registration still happens
  (servlets exist, just refuse) — honest E1, not literal E0; depends on the logout-cleanup
  change for the entitlement branch to close on logout.

**B — True E0: dynamic mount/unmount on login/logout.**
Register nothing at startup; add/remove the `/dev_mcp` wrappers on `StandardContext` when a
login completes or logout fires. **Not pursued:** no existing Servoy API re-invokes
`getServletInstances()` post-startup, so this needs new Tomcat-starter plumbing and
login/logout listeners wired into a one-shot lifecycle — high blast radius for marginal gain
over A (a 404-ing registered servlet drains nothing).

**C — No code change.** Rejected: the E0 hole is real and reproducible; a non-entitled client
can hit the budget-draining endpoints today.

**D — Narrow the Tomcat connector to 127.0.0.1.** Rejected explicitly in the ticket: it would
break legitimate cross-machine NG-client testing on the shared port.

---

## Recommendation

**PROCEED with Approach A**, in two parts:

1. **This repo (`com.servoy.eclipse.developer.mcp`):** add the per-request gate to
   `BearerTokenAuthenticationFilter.authenticate()` in this order — loopback check (fail
   closed, `getRemoteAddr()` only) → `isServoyInternalUser()` bypass (kept independent of the
   key) → `GENAI_API_KEY` presence → bearer check; every deny and wrong-token path returns
   `sendError(404)` with no auth headers and no JSON body, evaluated before the delegate.
   Extend `BearerTokenAuthenticationFilterTest` (`FakeHttpServletRequest` already stubs
   headers; add `getRemoteAddr` cases: loopback allowed, non-loopback 404, internal bypass,
   entitled-with-key, unentitled 404, wrong-token 404).

2. **servoy-eclipse LTS repo (`com.servoy.eclipse.ui`):** in the logout path
   (`ServoyLoginDialog.clearSavedInfo()` / the `ServoyLoginStatus` logout action) also
   `System.clearProperty("GENAI_API_KEY")` and `System.clearProperty("SERVOY_SKILLS_ZIP")`,
   so an entitled user's endpoint actually closes on logout rather than at next restart.
   Apply to every supported Servoy branch (`release`, `lts_2026`, …) since each has its own
   `ServoyLoginDialog`.

The `@servoy.com` bypass **must stay an independent `isServoyInternalUser()` check**, not a
"has a key" check: a seat-less internal user (and the headless skilltest) may have no key.

---

## Reviewer follow-up (comment 234276) — two holes in the @servoy.com path, now closed

A security review after the initial implementation found two issues, both fixed in
`BearerTokenAuthenticationFilter` **without removing the intentional token-free `@servoy.com`
bypass** (commit `1c4b7bc` — internal devs connect from external MCP tools that cannot obtain
the rotating bearer token):

1. **Faked offline login.** `ServoyLoginDialog` saves the username on a first login even when
   Cloud is unreachable (the relaxed offline-login path), with no server verification. The old
   `isServoyInternalUser()` only matched `@servoy.com` on the username, so blocking the Cloud
   host and typing any `@servoy.com` address opened the MCP with no key and no token. **Fix:**
   `isServoyInternalUser()` now also requires a non-blank `SERVOY_LOGIN_TOKEN`, which is written
   only after a Cloud HTTP 200 — so an unverified offline login no longer qualifies. (True
   token validation — signature/expiry — would need svyCloud to issue a signed JWT; out of
   scope, tracked separately. The local check asserts presence only.)

2. **No Origin check (DNS rebinding).** A malicious page can use DNS rebinding so the browser
   connects to `127.0.0.1` (passing the loopback peer check) while the page is served from the
   attacker's domain. **Fix:** a new `isOriginLocalOrAbsent(request)` guard rejects a present,
   non-loopback `Origin` with 404 (absent or loopback `Origin` passes; fails closed on an
   unparsable value).

The reviewer's literal suggestion ("everyone goes through the bearer check") was **not** taken,
because it would break the intended use case of internal devs driving the MCP from external
tools that have no bearer token. Closing hole 1 via the login-token requirement achieves the
same security goal (a faked login cannot open the MCP) while preserving that use case. See
`docs/SVY-21688-gate-dev-mcp-entitlement.spec.md` §8 for the as-built gate order.

---

## Git history findings

- `BearerTokenAuthenticationFilter` is already structured as the single per-request choke
  point for all MCP endpoints (its own Javadoc notes it is deliberately a filter scoped to the
  servlet mapping, **not** a Tomcat `ValveBase`, so it does not intercept unrelated Servoy
  Tomcat requests). This is why the per-request gate belongs here and not in a global valve.
- The `@servoy.com` bypass is already per-request (reads the login username from
  `ISecurePreferences` each call), confirming why the ticket's own note — "do not fix in the
  auth filter alone and leave registration ungated" — is moot: registration **can't** carry
  the login identity (it runs before login), so the filter is in fact the only place the
  identity/entitlement is reliably available.
- `ServoyLoginDialog` (all of `servoy_master`, `servoy_release`, `servoy_2026.03`) sets
  `GENAI_API_KEY` from the Cloud `svy_ai_key` on 200, in both the interactive login and the
  background cloud-retry path — but neither clears it; `clearSavedInfo()` predates the AI key
  and only wipes the secure-store. The logout-cleanup gap is therefore original, not a
  regression.
- The developer.mcp sources (filter, registry, provider, startup) all carry 2026 headers and
  are the active, current implementation on the Servoy-Copilot `master` branch.

## Questions for the reporter (NEEDS_INPUT only)

Not applicable — verdict is PROCEED. (Both open questions from the prior comments were
answered from code: logout does **not** clear the properties today — must be added; and the
embedded Tomcat exposes no post-startup servlet add/remove path on the route used, so E1 is
the right choice.)

# SVY-21750 — Triage Report

**Issue:** MCP: store `svy_ai_key`/`GENAI_API_KEY` in Eclipse secure storage instead of a JVM system property
**Type:** New Feature · **Priority:** Minor · **Status:** Open · R&D on the unreleased Servoy AI product
**Linked:** SVY-21688 (Relates) — MCP entitlement gate; this supersedes its Part-2 `clearProperty` stopgap
**Scope:** Cross-repo — servoy-eclipse (`com.servoy.eclipse.ui`, writers) + Servoy-Copilot (`com.servoy.eclipse.opencode` / `com.servoy.eclipse.developer.mcp`, readers)

---

## Verdict

**PROCEED** — with Approach A (secure storage + shared accessor with `-D` fallback). The ticket's
premise is sound, the writer/reader map is fully confirmed against live source, and the hard
child-env constraint is real and satisfiable. No divergence requiring reporter input.

---

## Reported problem

The svyAI provider key is set as a JVM **system property** `GENAI_API_KEY` (from the Cloud login
response field `svy_ai_key`). A system property is passively visible in the running IDE via
**Help → About → Installation Details → Configuration** (and via the process command line / `-D`),
so the provider key is effectively on display. Eclipse `ISecurePreferences` values are **not** shown
there (encrypted, no built-in UI dumps them), and the login username/password already live in the
secure-store node `ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY`. The ask: move the key into that same
node so it is not casually readable, and so logout (`node.clear()`) wipes it automatically.

This is the **problem**. The **proposed solution** (Approach A) is already chosen by the reporter and
in the user context; this triage confirms it is implementable and names the exact parts.

---

## Root-cause assessment — confirmed writer/reader map

The "root cause" is a design choice, not a defect: the key was simply materialized the same way as
the skills-zip URL (a `System.setProperty`) because that is the cheapest way to hand a value to both
the in-JVM readers and the opencode child process. The exposure is an inherent property of system
properties, not a bug in any one line.

> **Note on paths/line numbers.** Eclipse has the servoy-eclipse checkout at
> `D:\git\servoy_2026.03\servoy-eclipse` open on branch **lts_2026**; every line number below was
> verified there and matches the ticket. (A second checkout exists at
> `D:\git\servoy_lts_latest\servoy-eclipse` on branch `lts_2025`, where `ServoyLoginDialog` does
> **not** yet contain the `GENAI_API_KEY` writers — that older branch is not in scope.)

### WRITERS (`System.setProperty("GENAI_API_KEY", svyAiKey)` → must write the secure-store node instead)

| # | Location | File : line | Context |
|---|----------|-------------|---------|
| W1 | `ServoyLoginDialog.getLoginToken(...)` lambda (interactive login, HTTP 200 branch) | `com.servoy.eclipse.ui/src/com/servoy/eclipse/ui/dialogs/ServoyLoginDialog.java:273` | Sets `GENAI_API_KEY` + conditionally `SERVOY_SKILLS_ZIP` from the login response. |
| W2 | `ServoyLoginDialog.scheduleCloudRetry()` background retry (HTTP 200 branch) | `com.servoy.eclipse.ui/src/com/servoy/eclipse/ui/dialogs/ServoyLoginDialog.java:589` | Re-sets `GENAI_API_KEY` after cloud reachability is restored. This block already has the live `node` (`preferences.node(SERVOY_LOGIN_STORE_KEY)`, line 563) in scope and already calls `node.put(SERVOY_LOGIN_TOKEN, loginToken, true)`. |
| W3 | Headless `CloudLogin.loginAndSetProperties(...)` | `bundles/com.servoy.eclipse.opencode.skilltest/src/com/servoy/eclipse/opencode/skilltest/headless/CloudLogin.java:116` | No-UI CI path; sets `GENAI_API_KEY` from `svy_ai_key`. Guarded by `if (isSet("GENAI_API_KEY")) return;` at line 82 so a pre-set `-DGENAI_API_KEY` is respected. **This bundle is not currently imported into the Eclipse workspace** (`com.servoy.eclipse.opencode.skilltest` was not in `listProjects`); edit it with the generic file tools, not the Eclipse MCP. |

Constant definitions (servoy-eclipse, `ServoyLoginDialog.java`):
- `SERVOY_LOGIN_STORE_KEY = "SERVOY_LOGIN_INFO"` — line 73
- `SERVOY_LOGIN_USERNAME = "USERNAME"` — line 74
- `SERVOY_LOGIN_PASSWORD`, `SERVOY_LOGIN_TOKEN` — lines 75-76

The node is always reached as `SecurePreferencesFactory.getDefault().node(SERVOY_LOGIN_STORE_KEY)`;
values are stored encrypted via `node.put(key, value, /*encrypt*/ true)` (e.g. username/password at
`ServoyLoginDialog.java:148-150`, token at line 150/625). A new `SVY_AI_KEY` entry under the same
node should follow the same `put(..., true)` pattern — giving it the same encryption and the same
automatic logout wipe.

### READERS (`System.getProperty("GENAI_API_KEY")` → must read via the new accessor instead)

| # | Location | File : line | What it does |
|---|----------|-------------|--------------|
| R1 | `ProviderConfigWriter.buildProviderEnvVars()` | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/ProviderConfigWriter.java:65` | Reads `System.getProperty(ENV_API_KEY)`; returns `{GENAI_API_KEY: value}` or empty map. **This is the child-env source** (see hard constraint). Owns `public static final String ENV_API_KEY = "GENAI_API_KEY"` at line 50. |
| R2 | `OpencodeFolderCreatorJob.run()` config guard | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:76-77` | Skips setup if the key is absent/blank or `SERVOY_SKILLS_ZIP` missing. |
| R3 | `OpenCodeView.isServoyAiConfigured()` | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpenCodeView.java:130` | Decides config vs. "not configured" page. |
| R4 | `BearerTokenAuthenticationFilter.authenticate()` entitlement check (SVY-21688) | `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/auth/BearerTokenAuthenticationFilter.java:120` | Presence of the key = svyAI entitlement; absent ⇒ 404. Already imports `ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY` at line 190 for the token node — so this bundle already reaches into that node. |

**Workspace-wide search confirms these four are the only production readers.** A regex search for
`getProperty(... "GENAI_API_KEY" / ENV_API_KEY)` across all `*.java` returned only R1–R4 plus their
unit tests (`ProviderConfigWriterTest`, `BearerTokenAuthenticationFilterTest`) and Javadoc. No reader
exists anywhere else in servoy-eclipse or the other repos. No code reads it via `System.getenv(...)`
either (0 matches). So dropping the system property breaks nothing beyond R1–R4 once they are
re-pointed at the accessor.

### Other secure-store node consumers (context, not readers of the key)

`SERVOY_LOGIN_STORE_KEY` is referenced by `ServoyLoginDialog` (W1/W2 + token reads), `ui/Activator`
(497, 509), `ui/ServoyLoginStatus` (57, logout at 151), `exporter.ngdesktop/ExportPage` (395), and
`BearerTokenAuthenticationFilter` (190). None read `svy_ai_key`; they read username/token. They
confirm the node is a shared, already-depended-on surface across both repos.

---

## Hard constraint — the opencode child still needs the value as an env var (CONFIRMED)

`opencode.json` references the key as `{env:GENAI_API_KEY}`, and the opencode CLI is a **separate OS
process** that cannot read Eclipse `ISecurePreferences`. Confirmed launch path:

1. `OpencodeFolderCreatorJob.run()` builds the child env:
   `Map<String,String> allEnvVars = new HashMap<>(mcpEnvVars); allEnvVars.putAll(ProviderConfigWriter.buildProviderEnvVars());`
   — `OpencodeFolderCreatorJob.java:269-270`.
2. It launches `new RunOpencodeCommand(opencodeDir, Collections.unmodifiableMap(allEnvVars)).schedule()`
   — `OpencodeFolderCreatorJob.java:281`.
3. `RunOpencodeCommand` merges that map into the child process environment:
   `Map<String,String> env = new HashMap<>(buildServoyXdgEnv()); env.putAll(additionalEnvVars);`
   — `RunOpencodeCommand.java:159-160` — and the executable is launched with `env`.

So the value **must still be materialized into the child process env at launch**, sourced from secure
storage. The single change needed on the read side is to make `buildProviderEnvVars()` (R1) read from
the new accessor instead of `System.getProperty`, and the whole child-env chain keeps working
unchanged. The child-process env is a separate, less-casual exposure than the About dialog and is
acceptable/unavoidable (stated in the ticket).

---

## Ticket premise check — is secure storage the right fix?

- **Is the About-dialog exposure real?** Yes. System properties are listed verbatim in the
  Configuration tab; secure-preferences values are not. The move removes the casual read.
- **Layering / dependency direction — OK.** Readers live in `opencode` / `developer.mcp`; the node +
  its key constant are owned by `com.servoy.eclipse.ui` (`ServoyLoginDialog`). Both Copilot bundles
  **already** depend on `com.servoy.eclipse.ui` and already reference `SERVOY_LOGIN_STORE_KEY`
  (`BearerTokenAuthenticationFilter.java:190`). So reading the key from that node introduces **no new
  dependency and no new layering violation** — it uses an edge that already exists.
- **Where should the shared accessor live?** `ProviderConfigWriter` already owns `ENV_API_KEY` and is
  already the choke point that produces the child env, so it is the natural home for a
  `getApiKey()` accessor (secure-store first, `-D` fallback). The remaining in-JVM readers
  (R2/R3/R4) call the same accessor. One subtlety: `BearerTokenAuthenticationFilter`
  (`developer.mcp`) would then depend on a helper in `opencode` — verify that bundle edge exists /
  is acceptable, or put the read-side accessor somewhere both Copilot bundles already depend on. This
  is a spec-level placement choice, not a blocker.
- **Headless `-D` fallback — required and compatible.** `CloudLogin` already honors a pre-set
  `-DGENAI_API_KEY` (`CloudLogin.java:82`), and `SkillTestArgumentChest`/`SkillTestHeadlessRunner`
  document the `-DGENAI_API_KEY` path. The accessor's documented fallback to the `-D` system property
  (when secure storage has no entry) keeps CI/headless runs working. In headless mode there may be no
  live `SecurePreferencesFactory`/keyring, so the fallback is not optional — it is load-bearing.

**Conclusion:** the premise holds; Approach A is the right fix and is strictly better than the
SVY-21688 Part-2 stopgap.

---

## Approaches considered

### (A) Secure-storage node + shared accessor with `-D` fallback — **RECOMMENDED**
- Writers W1/W2 write `node.put("SVY_AI_KEY", svyAiKey, true)` into `SERVOY_LOGIN_STORE_KEY` instead
  of `System.setProperty`. W2 already holds the node. W3 (headless) writes the same node (or keeps
  the `-D` property for no-keyring CI — see fallback).
- A new accessor (home: `ProviderConfigWriter`) reads the node first, then falls back to
  `System.getProperty("GENAI_API_KEY")` for headless/CI.
- Readers R1–R4 call the accessor instead of `System.getProperty`.
- R1 (`buildProviderEnvVars`) still materializes the value into the child env — child keeps working.
- Logout: existing `clearSavedInfo()` → `node.clear()` (`ServoyLoginDialog.java:500`) wipes the key
  for free; **supersedes SVY-21688 Part-2** `clearProperty` entirely.
- Drop all three `System.setProperty("GENAI_API_KEY", ...)` calls.
- **Pros:** removes About-dialog exposure; encrypted at rest; automatic logout wipe; no new
  dependencies; entitlement semantics unchanged. **Cons:** cross-repo change applied per supported
  Servoy branch; headless fallback must be got right; small accessor-placement decision for the
  `developer.mcp` reader.

### (B) Keep system property, only `clearProperty` on logout (the SVY-21688 Part-2 stopgap)
- Add `System.clearProperty("GENAI_API_KEY")` to logout; leave writers/readers as-is.
- **Pros:** trivial, single-repo-ish. **Cons:** does **not** fix the stated problem — the key is
  still in the About dialog for the whole logged-in session. Inferior; explicitly superseded by (A).

### (C) No code change
- **Rejected.** The exposure is real and the fix is cheap and already approved.

---

## Recommendation

Implement **Approach A**, cross-repo, per supported Servoy branch:

- **servoy-eclipse (`com.servoy.eclipse.ui`) — writers:** replace `System.setProperty("GENAI_API_KEY", svyAiKey)`
  at `ServoyLoginDialog.java:273` (W1) and `:589` (W2) with an encrypted `node.put(<SVY_AI_KEY>, svyAiKey, true)`
  into the `SERVOY_LOGIN_STORE_KEY` node (W2 already has the node; W1 can use the `node` it already
  passes to `handleLoginTokenResponse`). Add the `SVY_AI_KEY` constant next to the other
  `SERVOY_LOGIN_*` constants. Keep writing `SERVOY_SKILLS_ZIP` behavior as-is unless separately
  scoped — the ticket is about the **key**, not the skills-zip URL.
- **Servoy-Copilot — shared accessor:** add a secure-storage accessor (secure node first, `-D`
  system-property fallback, documented) with `ProviderConfigWriter` as its natural home (it owns
  `ENV_API_KEY` and builds the child env). Resolve the one placement subtlety for the
  `developer.mcp` reader (R4) so it can call the accessor without a new bundle dependency.
- **Servoy-Copilot — readers:** re-point R1 (`ProviderConfigWriter.buildProviderEnvVars:65`),
  R2 (`OpencodeFolderCreatorJob:76-77`), R3 (`OpenCodeView:130`), R4
  (`BearerTokenAuthenticationFilter:120`) at the accessor.
- **Child-env sourcing:** unchanged chain — `buildProviderEnvVars` → `allEnvVars` → `RunOpencodeCommand`
  materializes `GENAI_API_KEY` into the child process env, now sourced from secure storage.
- **`-D` fallback:** the accessor falls back to `System.getProperty("GENAI_API_KEY")` so
  `CloudLogin`/skilltest headless runs (which already honor `-DGENAI_API_KEY`) keep working even
  without a keyring.
- **Logout:** rely on existing `clearSavedInfo()` → `node.clear()`; do **not** add the SVY-21688
  Part-2 `clearProperty` — **this change supersedes it**. (SVY-21688 is still Open, so coordinate: its
  Part-2 should be dropped/closed in favor of this.)
- **Drop** every `System.setProperty("GENAI_API_KEY", ...)` (W1, W2, and W3 unless the headless
  bundle deliberately keeps the `-D` write for no-keyring CI).
- **Tests to update:** `ProviderConfigWriterTest` and `BearerTokenAuthenticationFilterTest` currently
  drive the behavior through the `GENAI_API_KEY` system property; they will need the accessor's
  seam (secure-store value vs. `-D` fallback) made testable.
- **Apply per branch:** the servoy-eclipse writer edit lands on the branch(es) the Copilot `master`
  is built against (lts_2026 confirmed present; apply to each supported branch where
  `ServoyLoginDialog` already has the writers).

---

## Git history findings

- **W1** (`ServoyLoginDialog.java:273`, interactive login) was introduced by Johan Compagner,
  commit `6f426e4384`, 2026-05-29 — the original "set `GENAI_API_KEY` from `svy_ai_key`" wiring.
- **W2** (`:589`, background cloud-retry) was added by `emera` in `3e77f2e269`, 2026-08-10, as part of
  **SVY-21312 "graceful cloud login failure when service is unreachable"** — it simply mirrored the
  W1 pattern inside the new retry loop.
- No commit anywhere states a reason the value **must** be a system property. The choice was
  convenience (hand one value to both the JVM readers and the child env the same way the
  `SERVOY_SKILLS_ZIP` URL is). Nothing in history blocks moving it to secure storage; the
  child-env requirement is satisfied by R1 continuing to produce the env map from the new source.
- The older `lts_2025` checkout does not yet carry these writers, confirming the feature is recent
  and branch-specific — reinforcing the "apply per supported branch" note.

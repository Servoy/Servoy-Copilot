# SVY-21750 — Store `svy_ai_key` / `GENAI_API_KEY` in Eclipse secure storage instead of a JVM system property

**Issue:** SVY-21750 (New Feature, Minor, Open) · **Relates:** SVY-21688 (MCP entitlement gate)
**Scope:** Cross-repo — servoy-eclipse `com.servoy.eclipse.ui` (writers) + Servoy-Copilot `com.servoy.eclipse.opencode` / `com.servoy.eclipse.developer.mcp` (readers)
**Approach:** A — secure-store node (encrypted) + shared secure-store-first/`-D`-fallback accessor
**Triage:** `docs/SVY-21750-triage.md` (authoritative; this spec is bound to its confirmed writer/reader map and Approach A)
**R&D on the unreleased Servoy AI product.**

---

## 1. Goal

Move the svyAI provider key out of the JVM **system property** `GENAI_API_KEY` and into the existing
Eclipse secure-store node `ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY` (where the login
username/password/token already live, encrypted). The key must no longer be set as a system property
(so it is no longer visible in **Help → About → Installation Details → Configuration** or via the
process `-D` command line), while the opencode child process keeps receiving it as the
`GENAI_API_KEY` environment variable, and headless/CI runs keep working through a documented `-D`
fallback.

A success is: after an interactive Cloud login the key is readable only from encrypted secure
storage (not from the About dialog), logout wipes it automatically via the existing `node.clear()`,
the SVY-21688 MCP entitlement gate keeps working off the same value, and nothing (child process,
headless CI) that needs the key breaks.

---

## 2. Background

### 2.1 Current behavior (the problem)

The Cloud login response carries `svy_ai_key`. Today it is materialized with
`System.setProperty("GENAI_API_KEY", svyAiKey)`. A JVM system property is listed verbatim in the
About → Configuration tab and is on the process command line, so the provider key is passively
readable with no effort. Eclipse `ISecurePreferences` values are **not** shown there (encrypted, no
built-in UI dumps them), and the login username/password/token already live in the secure-store node
`SecurePreferencesFactory.getDefault().node(ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY)` via
`node.put(key, value, /*encrypt*/ true)`.

### 2.2 Confirmed writer/reader map (from triage, verified against live source)

**Writers** — `System.setProperty("GENAI_API_KEY", svyAiKey)`:

| # | Location | File : line | Repo / bundle |
|---|----------|-------------|---------------|
| W1 | `ServoyLoginDialog.getLoginToken(...)` interactive-login HTTP-200 branch | `com.servoy.eclipse.ui/src/com/servoy/eclipse/ui/dialogs/ServoyLoginDialog.java:273` | servoy-eclipse / `com.servoy.eclipse.ui` |
| W2 | `ServoyLoginDialog.scheduleCloudRetry()` background cloud-retry HTTP-200 branch | `…/ServoyLoginDialog.java:589` | servoy-eclipse / `com.servoy.eclipse.ui` |
| W3 | Headless `CloudLogin.loginAndSetProperties(...)` | `bundles/com.servoy.eclipse.opencode.skilltest/src/com/servoy/eclipse/opencode/skilltest/headless/CloudLogin.java:116` | Servoy-Copilot / `opencode.skilltest` (NOT imported into the Eclipse workspace — edit with generic file tools) |

**Readers** — `System.getProperty("GENAI_API_KEY")`:

| # | Location | File : line | What it does |
|---|----------|-------------|--------------|
| R1 | `ProviderConfigWriter.buildProviderEnvVars()` | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/ProviderConfigWriter.java:65` | Produces `{GENAI_API_KEY: value}` for the opencode child env; owns `ENV_API_KEY` (line 50). |
| R2 | `OpencodeFolderCreatorJob.run()` config guard | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:76-77` | Skips setup if the key is absent/blank or `SERVOY_SKILLS_ZIP` missing. |
| R3 | `OpenCodeView.isServoyAiConfigured()` | `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpenCodeView.java:130` | Decides "configured" vs "not configured" page. |
| R4 | `BearerTokenAuthenticationFilter.authenticate()` entitlement check (SVY-21688) | `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/auth/BearerTokenAuthenticationFilter.java:120` | Key present ⇒ svyAI entitlement; absent ⇒ 404. Already reaches the same secure-store node at line 190 for the token. |

Workspace-wide search confirmed R1–R4 are the only production readers; no code reads the value via
`System.getenv(...)`.

### 2.3 Hard constraint — the opencode child still needs the value as an env var

`opencode.json` references the key as `{env:GENAI_API_KEY}`; the opencode CLI is a **separate OS
process** that cannot read Eclipse `ISecurePreferences`. Confirmed launch chain:
`OpencodeFolderCreatorJob.run()` builds `allEnvVars` including
`ProviderConfigWriter.buildProviderEnvVars()` (`OpencodeFolderCreatorJob.java:269-270`), launches
`new RunOpencodeCommand(opencodeDir, allEnvVars).schedule()` (line 281), and `RunOpencodeCommand`
merges that map into the child process environment (`RunOpencodeCommand.java:159-160`). So the value
**must still be materialized into the child env at launch** — only its source changes (secure store,
not a system property). This chain is otherwise **unchanged**.

### 2.4 Relationship to SVY-21688

SVY-21688 made presence of `GENAI_API_KEY` the svyAI entitlement signal for the per-request MCP gate,
and its **Part-2** was to add `System.clearProperty("GENAI_API_KEY")` on logout so the gate closes on
logout rather than at restart. Storing the key in secure storage is strictly better: logout already
does `node.clear()` (`ServoyLoginDialog.clearSavedInfo()` ~line 497), which now wipes the key for
free. **This change supersedes the SVY-21688 Part-2 `clearProperty` stopgap — that stopgap must be
dropped and not implemented.** SVY-21688 is still Open; coordinate so its Part-2 is removed in favor
of this.

---

## 3. Design

### 3.1 Part A — servoy-eclipse `com.servoy.eclipse.ui` (writers)

**Branch:** apply on `lts_2026` (confirmed present at `D:\git\servoy_2026.03\servoy-eclipse`, Eclipse
project `com.servoy.eclipse.ui`) and on every other supported Servoy branch whose `ServoyLoginDialog`
already carries the W1/W2 writers (e.g. `release`). The older `lts_2025` checkout does **not** carry
these writers and is out of scope.

**A.1 — new public constant.** Add next to the existing `SERVOY_LOGIN_*` constants in
`ServoyLoginDialog` (`SERVOY_LOGIN_STORE_KEY = "SERVOY_LOGIN_INFO"` line 73, `SERVOY_LOGIN_USERNAME`
line 74, `SERVOY_LOGIN_PASSWORD`/`SERVOY_LOGIN_TOKEN` lines 75-76):

```java
/** Secure-store key, under SERVOY_LOGIN_STORE_KEY, holding the svyAI provider key (svy_ai_key / GENAI_API_KEY). */
public static final String SVY_AI_KEY = "SVY_AI_KEY"; //$NON-NLS-1$
```

It is `public` so the Copilot accessor can reference the **same** key name without hardcoding the
string in two repos. (The literal value `"SVY_AI_KEY"` is the stored sub-key under the
`SERVOY_LOGIN_INFO` node; keep it distinct from the env-var name `GENAI_API_KEY`.)

**A.2 — W1 (interactive login, ~line 273).** Replace
`System.setProperty("GENAI_API_KEY", svyAiKey)` with an encrypted write into the login node, matching
the existing username/password/token pattern (`node.put(..., /*encrypt*/ true)`):

```java
node.put(SVY_AI_KEY, svyAiKey, true); // was: System.setProperty("GENAI_API_KEY", svyAiKey)
```

W1 must obtain/use the same `SecurePreferencesFactory.getDefault().node(SERVOY_LOGIN_STORE_KEY)` node
the method already uses for the token (the `node` already passed through the login-token handling);
do not introduce a second node lookup if one is already in scope.

**A.3 — W2 (background cloud-retry, ~line 589).** Same replacement. W2 **already** holds the live
node (`preferences.node(SERVOY_LOGIN_STORE_KEY)` ~line 563) and already calls
`node.put(SERVOY_LOGIN_TOKEN, loginToken, true)`, so this is a one-line swap in a block that already
writes the node.

**A.4 — `SERVOY_SKILLS_ZIP` unchanged.** Both W1 and W2 conditionally set `SERVOY_SKILLS_ZIP` as a
system property. **Leave that behavior exactly as-is** — this ticket is about the KEY only. (Noted in
Out of scope.)

**A.5 — logout.** `clearSavedInfo()` (~line 497) already does `node.clear()` on this node, so it now
wipes `SVY_AI_KEY` automatically. **Do not add any `System.clearProperty`** anywhere. State in the
code/PR that this supersedes the SVY-21688 Part-2 `clearProperty` stopgap.

**A.6 — no read-side accessor in this repo.** The read-side accessor stays in Copilot (Part B). The
only servoy-eclipse addition is the public `SVY_AI_KEY` constant + the two encrypted writes (+ the
dropped `setProperty`).

### 3.2 Part B — Servoy-Copilot shared accessor + readers

**B.1 — shared accessor (home: `ProviderConfigWriter`).** `ProviderConfigWriter` already owns
`ENV_API_KEY = "GENAI_API_KEY"` and is the choke point that builds the child env, so it is the natural
home. Add:

```java
/**
 * Returns the svyAI provider key: the encrypted value from the Servoy login secure-store node first,
 * else a fallback to the -D GENAI_API_KEY system property (headless/CI where there may be no keyring).
 * Returns null when neither is present/usable. Never throws for a missing/locked keyring — it falls
 * through to the -D fallback.
 */
public static String getApiKey() {
    String fromSecure = readFromSecureStore(); // seam, see B.4
    if (fromSecure != null && !fromSecure.isBlank()) {
        return fromSecure;
    }
    String fromProp = System.getProperty(ENV_API_KEY);
    return (fromProp != null && !fromProp.isBlank()) ? fromProp : null;
}
```

`readFromSecureStore()` does:
`SecurePreferencesFactory.getDefault().node(ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY).get(ServoyLoginDialog.SVY_AI_KEY, null)`,
wrapped so that **any** secure-store exception (`StorageException`, locked keyring, no
`SecurePreferencesFactory` in a headless container, etc.) is caught and treated as "no value" — the
accessor then falls through to the `-D` fallback rather than throwing. The accessor references
`ServoyLoginDialog.SVY_AI_KEY` and `ServoyLoginDialog.SERVOY_LOGIN_STORE_KEY` — never a hardcoded
string — so the key name lives in exactly one place across both repos.

Semantics: **secure-node-first, `-D` fallback, null/blank when neither present.** The `-D` fallback is
**load-bearing** for headless (no keyring), not optional.

`com.servoy.eclipse.opencode` already imports `org.eclipse.equinox.security.storage` usage via
`com.servoy.eclipse.ui` on its `Require-Bundle`; if the secure-storage package is not yet importable
from this bundle, add `org.eclipse.equinox.security.storage` to its `Import-Package` (verify at
implementation time — the dependency on `com.servoy.eclipse.ui` is already present).

**B.2 — re-point readers R1–R3.** Replace each `System.getProperty(ENV_API_KEY)` /
`System.getProperty("GENAI_API_KEY")` with `ProviderConfigWriter.getApiKey()`:

- **R1** `buildProviderEnvVars()` (~line 65): source the value from `getApiKey()`; still return
  `Collections.singletonMap(ENV_API_KEY, value)` (or empty map when null/blank). The child-env chain
  (`OpencodeFolderCreatorJob` → `RunOpencodeCommand`) is otherwise **unchanged** — it just sources the
  value from the accessor. Note R1 calling `getApiKey()` from within the same class is fine (same
  class). Update the Javadoc ("Reads the accessor on every call (no caching)…").
- **R2** `OpencodeFolderCreatorJob.run()` config guard (~line 76-77): use `getApiKey()` for the key
  presence check; the `SERVOY_SKILLS_ZIP` check stays as-is.
- **R3** `OpenCodeView.isServoyAiConfigured()` (~line 130): use `getApiKey()`.

**B.3 — R4 accessor placement (open question → RESOLVED).** MANIFEST check (verified):
`com.servoy.eclipse.developer.mcp`'s `Require-Bundle` **already lists `com.servoy.eclipse.opencode`**,
`com.servoy.eclipse.opencode` **exports package `com.servoy.eclipse.opencode`** (where
`ProviderConfigWriter` lives), and `developer.mcp` already `Import-Package`s
`org.eclipse.equinox.security.storage`.

**Recommendation:** R4 (`BearerTokenAuthenticationFilter.authenticate()` ~line 120) calls
`com.servoy.eclipse.opencode.ProviderConfigWriter.getApiKey()` directly. **No new bundle dependency
and no MANIFEST change are required** — the `developer.mcp → opencode` edge already exists and the
class's package is exported. This keeps one accessor, one secure-node-first/`-D`-fallback
implementation, used identically by all four readers. (The inline-read alternative — R4 reading the
secure node itself with the shared `SVY_AI_KEY` constant + `-D` fallback — is explicitly **not**
chosen, because it would duplicate the fallback logic; it would only have been necessary if the
bundle edge were absent, which it is not.)

**B.4 — testable seam for the secure-store lookup.** Plain JUnit (`*.plainjunit`) has no live
`SecurePreferences` keyring, so `getApiKey()` needs a package-visible seam for the secure-store branch,
mirroring how SVY-21688 made `isServoyInternalUser()` overridable. Options (pick one at
implementation; the first is preferred):

- Extract the secure-node read into a package-private `static` method / functional field (e.g.
  `static Supplier<String> secureStoreReader = ProviderConfigWriter::readSecureStoreValue;`) that
  tests can replace to simulate "node has the value" vs "node is empty/absent"; or
- a package-private `static String getApiKey(Supplier<String> secureStoreLookup)` overload that
  `getApiKey()` delegates to, with the production supplier wired to the real secure-node read.

Either way: the secure-node branch is unit-testable by substituting the seam; the `-D` fallback branch
is unit-testable with `System.setProperty`/`clearProperty`. Keep the seam package-visible (tests are
in `com.servoy.eclipse.opencode.tests`, a fragment-style plain-JUnit project that can see
package-visible members), never public API.

### 3.3 Part C — headless `CloudLogin` (W3) stays on the system property

**Decision:** **Leave `CloudLogin.loginAndSetProperties(...)` writing the `GENAI_API_KEY` system
property in headless mode, and leave its `if (isSet("GENAI_API_KEY")) return;` guard
(`CloudLogin.java:82`) intact.** In headless/no-UI/CI there may be no live
`SecurePreferencesFactory`/keyring, so the accessor's `-D`/system-property fallback is exactly what
picks up the value `CloudLogin` sets. Net effect:

- **Interactive / Developer:** key written to secure storage (Part A), read via the accessor's
  secure-node branch.
- **Headless / CI:** key provided via `-DGENAI_API_KEY` or set by `CloudLogin` as a system property,
  read via the accessor's `-D` fallback branch.

This keeps headless/CI unbroken without a keyring. The `opencode.skilltest` bundle is **not imported
into this Eclipse workspace**; per triage it may be **left unchanged** when the `-D` fallback is kept
— **confirmed: leave it unchanged** (no `SVY_AI_KEY` secure write there). If edited later, use generic
file tools, not the Eclipse MCP.

### 3.4 Drop the system-property writes

Remove every `System.setProperty("GENAI_API_KEY", ...)` **except** the headless W3 path (which is
deliberately kept per §3.3): W1 and W2 are replaced by the encrypted `node.put(...)`. No
`System.clearProperty` is added anywhere.

---

## 4. Implementation plan

Ordered so the Copilot side compiles against the new constant.

1. **servoy-eclipse (`com.servoy.eclipse.ui`, per supported branch — lts_2026 first):**
   add `public static final String SVY_AI_KEY` next to the `SERVOY_LOGIN_*` constants (§3.1 A.1).
2. **servoy-eclipse:** W1 (~line 273) and W2 (~line 589) → `node.put(SVY_AI_KEY, svyAiKey, true)`;
   drop both `System.setProperty("GENAI_API_KEY", …)`; leave `SERVOY_SKILLS_ZIP` and
   `clearSavedInfo()` as-is (§3.1 A.2–A.5).
3. **Servoy-Copilot `com.servoy.eclipse.opencode`:** add `ProviderConfigWriter.getApiKey()` + the
   package-visible secure-store seam (§3.1 B.1, §3.2 B.4). Add
   `org.eclipse.equinox.security.storage` to the bundle's `Import-Package` only if the secure-storage
   API is not already visible (verify).
4. **Servoy-Copilot:** re-point R1 (`buildProviderEnvVars`), R2 (`OpencodeFolderCreatorJob`),
   R3 (`OpenCodeView`) at `getApiKey()` (§3.2 B.2).
5. **Servoy-Copilot `com.servoy.eclipse.developer.mcp`:** re-point R4
   (`BearerTokenAuthenticationFilter.authenticate()`) at `ProviderConfigWriter.getApiKey()` — no
   MANIFEST change (§3.2 B.3).
6. **Leave `CloudLogin` (W3) unchanged** (§3.3).
7. **Compile-clean** each imported bundle via `eclipse-ide_getCompilationErrors` + quick-fix loop
   (per AGENTS.md §4). For servoy-eclipse use its own project.
8. **Tests (test phase, not now):** update `ProviderConfigWriterTest` and
   `BearerTokenAuthenticationFilterTest` to drive behavior through the accessor/seam (see §6).
9. **Coordinate SVY-21688:** ensure its Part-2 `clearProperty` is dropped (superseded).

Use Eclipse MCP tools for all imported Java bundles; generic file tools only for
`opencode.skilltest` and this `docs/` spec.

---

## 5. Acceptance criteria

Each marked **[unit]** (plain-JUnit testable via the seam), **[integration]** (needs workbench /
secure store / running Developer), or **[manual]**.

1. **[integration/manual]** After an interactive Cloud login, the key is present in the secure node
   `SERVOY_LOGIN_INFO` under `SVY_AI_KEY` (encrypted) and is **NOT** set as the `GENAI_API_KEY` system
   property (`System.getProperty("GENAI_API_KEY") == null` when no `-D` was passed).
2. **[manual]** Help → About → Installation Details → Configuration no longer lists `GENAI_API_KEY`.
3. **[unit]** `ProviderConfigWriter.getApiKey()` returns the secure-node value when the seam reports a
   non-blank value (regardless of the `-D` property).
4. **[unit]** `getApiKey()` returns the `-D GENAI_API_KEY` value when the secure-store seam reports
   null/blank (headless/CI fallback), and `null` when neither is present.
5. **[unit]** `getApiKey()` does not throw when the secure-store seam raises an exception (locked / no
   keyring) — it falls through to the `-D` fallback.
6. **[unit]** `buildProviderEnvVars()` returns `{GENAI_API_KEY: <getApiKey() value>}` when a value is
   available and an empty map when `getApiKey()` is null/blank.
7. **[integration]** The opencode child process still receives `GENAI_API_KEY` in its environment,
   sourced from the accessor (child-env chain unchanged).
8. **[unit]** SVY-21688 MCP entitlement gate (`BearerTokenAuthenticationFilter`) allows when
   `getApiKey()`/the seam reports the key present and denies (404) when absent — driven through the
   same seam, no keyring required.
9. **[integration]** Logout (`clearSavedInfo()` → `node.clear()`) removes `SVY_AI_KEY` so the
   entitlement gate then denies **without a restart**, and no `System.clearProperty` is involved.
10. **[integration/manual]** Headless skilltest run with `-DGENAI_API_KEY=…` still works end-to-end
    (accessor `-D` fallback + unchanged `CloudLogin`).
11. **[unit]** No production reader calls `System.getProperty("GENAI_API_KEY")` anymore (R1–R4 all go
    through `getApiKey()`); W1/W2 no longer call `System.setProperty("GENAI_API_KEY", …)`.

---

## 6. Tests to update (test-phase obligation — do not write now)

- **`ProviderConfigWriterTest`** (`com.servoy.eclipse.opencode.tests`, `*.plainjunit`): currently
  drives behavior via the `GENAI_API_KEY` system property. Re-target it at `getApiKey()`: exercise
  (a) the **secure-node branch** via the package-visible seam (substitute a supplier returning a
  value / null / throwing), and (b) the **`-D` fallback** via `System.setProperty`/`clearProperty`.
  Also cover `buildProviderEnvVars()` sourcing from the accessor. No live keyring required.
- **`BearerTokenAuthenticationFilterTest`** (`com.servoy.eclipse.developer.mcp.tests`,
  `*.plainjunit`): currently drives the entitlement branch via the `GENAI_API_KEY` system property.
  Re-target the entitlement branch through the same accessor/seam so "allow when key present / deny
  when absent" is exercised without a keyring. Keep it unit-testable.

Both must stay plain-JUnit (no OSGi/keyring); the seam is the mechanism that makes the secure-node
branch testable off a real keyring.

---

## 7. Out of scope

- **`SERVOY_SKILLS_ZIP` relocation** — stays a JVM system property; only the KEY moves (W1/W2 keep
  setting the skills-zip property).
- **Token rotation** (SVY-21689).
- **Hiding skills** (SVY-21691).
- **The SVY-21688 gate itself** — only its reader (R4) is re-pointed. The SVY-21688 **Part-2
  `clearProperty` stopgap is superseded** by this change and must be dropped there (not implemented).
- **`opencode.skilltest` / `CloudLogin` behavior change** — deliberately left unchanged (headless `-D`
  path is kept and covered by the accessor fallback).
- Changing the child-process env chain (`OpencodeFolderCreatorJob` → `RunOpencodeCommand`) beyond the
  value's source.

---

## 8. Open questions (with resolutions)

1. **Accessor placement for R4 (`developer.mcp`) — RESOLVED.** `developer.mcp` already
   `Require-Bundle`s `com.servoy.eclipse.opencode`, which **exports** the `com.servoy.eclipse.opencode`
   package, and `developer.mcp` already imports `org.eclipse.equinox.security.storage`. → R4 calls
   `ProviderConfigWriter.getApiKey()` directly; no MANIFEST change, no new dependency, no inline
   duplicate.
2. **Does headless `CloudLogin` keep the system-property write? — RESOLVED: yes.** Headless keeps the
   `-D`/system-property path (there may be no keyring); the accessor's fallback consumes it.
   `CloudLogin` is left unchanged.
3. **Testable seam for the secure-store lookup — RESOLVED in principle (§3.2 B.4).** Use a
   package-visible seam (supplier field or package-private overload) so both the secure-node branch
   and the `-D` fallback are plain-JUnit testable without a keyring. Exact form chosen at
   implementation.
4. **Which Servoy branches get the servoy-eclipse writer edit? — lts_2026 confirmed.** Apply to every
   supported branch whose `ServoyLoginDialog` already carries the W1/W2 writers (e.g. `release`);
   `lts_2025` does not carry them and is excluded. Confirm the exact branch list with the team before
   landing the servoy-eclipse change.
5. **SVY-21688 Part-2 coordination.** SVY-21688 is still Open; its Part-2 `clearProperty` must be
   dropped in favor of this. Needs a cross-ticket note/agreement (not a code change in this ticket
   beyond re-pointing R4).

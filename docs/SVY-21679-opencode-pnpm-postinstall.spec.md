# Spec: SVY-21679 — Servoy AI: opencode CLI fails to launch in pnpm mode (CreateProcess error=216) — ship pre-approved build-script config

## 1. Goal
Make the Servoy AI (opencode) install run `@opencode/cli`'s `postinstall` script — the step
that materializes the real native `opencode.exe` — on **every** end-user machine, under both
pnpm and npm, with **no per-workspace prompt and no runtime approval dance**. We do this by
**committing the package-manager build-approval config directly into the plugin** next to
`opencode/package.json`, so the managed install dir is pre-configured exactly like a locked-down
CI install: we (the plugin maintainers) decide once, at commit time, that `@opencode/cli`'s
build script is trusted; every Servoy user who then installs opencode (npm or pnpm) gets a
correctly-configured, already-allowed install. A guard test ensures that when the pinned opencode
version is bumped, the approval config is updated in lock-step.

## 2. Background

### 2.1 The bug
`OpencodeFolderCreatorJob` installs the opencode npm package into the plugin's managed state
folder `{eclipse-state}/opencode` via `ngActivator.createNPMCommand(opencodeDir, List.of("install"))`
(`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:141`).
Before the install the job copies the bundle's `/opencode/package.json` into that folder and runs
the install there (`OpencodeFolderCreatorJob.java:124-141`).

In **pnpm mode** `createNPMCommand` returns a `RunPNPMCommand`, so `install` becomes `pnpm install`.
Under pnpm 11+/12 the default `strictDepBuilds: true` blocks dependency build scripts unless they
are explicitly approved in a `pnpm-workspace.yaml` `allowBuilds` map. The opencode install dir is a
standalone folder that is **not** wired into the solution-scoped pnpm build-approvals machinery
(`WebPackagesListener` + `PnpmBuildScriptConfig`), so `@opencode/cli`'s `postinstall`
(`node ./postinstall.mjs`) — which downloads and copies the real ~209 MB native `opencode.exe` into
`node_modules/@opencode/cli/bin/` — is **skipped**. The bin is left as the 229-byte placeholder
shell script shipped in the package (first line literally
`echo "Error: @opencode/cli's postinstall script was not run."`).
`RunOpencodeCommand.resolveOpencodeExecutable` finds that placeholder and launches it directly
(`RunOpencodeCommand.java:123-128,283-298`); Windows cannot `CreateProcess` a non-PE file and
returns the misleading `error=216`, the watchdog never sees a healthy server, and the job exhausts
its 3 retries (`RunOpencodeCommand.java:183-192`).

In npm mode today the same `install` runs as `RunNPMCommand` where npm still runs lifecycle scripts
by default, so the postinstall runs — which is why the bug is pnpm-specific **today**. But npm is
moving the same direction (npm v12 blocks install scripts by default), so the fix is designed to
cover npm too.

### 2.2 Why the "configured-like-CI" approach (the approved redirect)
Rather than toggling a shared runtime demotion counter and running a `pnpm rebuild` from the job
(the original triage Approach 1), the human-approved approach is simpler and more robust:
**ship the approval config as committed files in the plugin**. `@opencode/cli` is a single,
first-party, **pinned** (`2.1.1`), trusted dependency — its build script is exactly the kind of
thing that should be allowed once, declaratively, not prompted per workspace. Committing the
config means:
- one deliberate maintainer decision ("this build script is allowed") lives in version control;
- every end-user install (npm or pnpm) is pre-configured and self-contained — no runtime job
  logic, no shared static counter, no `pnpm rebuild` step, no network-order dependency;
- it is auditable in diffs and bumped deliberately alongside the version pin.

### 2.3 pnpm allow format is version-scoped
pnpm's own `pnpm approve-builds` writes allowed dependencies into the `allowBuilds` map in
`pnpm-workspace.yaml` **keyed by `name@version`** (e.g. `'@opencode/cli@2.1.1': true`), not by
bare name — this is the same version-scoped format `PnpmBuildScriptConfig.appendAllowBuilds`
produces in servoy-eclipse (`PnpmBuildScriptConfig.java:114-141`). This is deliberate and is the
hook that makes the guard test meaningful: because the key carries the version, a bump of the
pinned `@opencode/cli` version in `package.json` **must** be accompanied by an update of the
`allowBuilds` key, or the install silently reverts to skipping the script. The guard test fails
exactly on that drift.

## 3. Design

### 3.1 Committed config files (next to `opencode/package.json`)
Add, under `bundles/com.servoy.eclipse.opencode/opencode/`, committed alongside `package.json`:

1. **`pnpm-workspace.yaml`** — the pnpm build approval, mirroring the servoy-eclipse format:
   ```yaml
   # Pre-approved build script for the opencode CLI native binary (SVY-21679).
   # @opencode/cli's postinstall downloads/copies the real native opencode executable.
   # The key is VERSION-SCOPED on purpose: it MUST match the pinned version in package.json.
   # Bumping @opencode/cli in package.json requires bumping the version here too
   # (the update-opencode skill does this; OpencodePnpmApprovalTest enforces it).
   nodeLinker: hoisted
   shamefullyHoist: true
   strictDepBuilds: true
   allowBuilds:
     '@opencode/cli@2.1.1': true
   ```
   `nodeLinker: hoisted` + `shamefullyHoist: true` match how the rest of the plugin/servoy pnpm
   installs are configured (so the hoisted `node_modules/@opencode/cli/bin` layout
   `resolveOpencodeExecutable` expects is produced). `strictDepBuilds: true` keeps the secure
   default — only the explicitly listed script is allowed.

2. **npm side (`.npmrc` in the opencode dir)** — so that on an npm that blocks scripts by default
   the opencode build script is still allowed, keeping npm mode pre-configured too. The exact key
   is confirmed during implementation against the npm version the plugin ships (`npm config ls`
   / npm docs for the shipped major); the conservative, well-supported form is to opt this
   managed, first-party install back into running its dependency's build script (e.g. an
   `ignore-scripts=false` / trusted-dependency allowance scoped to this folder). If the npm the
   plugin bundles still runs scripts by default, the `.npmrc` is a no-op safeguard and documents
   intent; it never loosens anything beyond this one managed install dir. **The npm key name is
   an open question (§7) to be pinned against the shipped npm version — do not guess.**

These files live **only** in the plugin's `opencode/` resource folder; they are copied into the
managed state dir at install time (§3.2) and are not solution files.

### 3.2 Copy the config into the managed install dir before install
`OpencodeFolderCreatorJob` already copies the bundle's `/opencode/package.json` into
`{state}/opencode` before running the install (`OpencodeFolderCreatorJob.java:128-131`). Extend
that step to also copy the committed `pnpm-workspace.yaml` and `.npmrc` from the bundle
(`activator.getBundle().getEntry("/opencode/<file>")`) into the same dir, using the existing
`readUrlContent` + `Files.writeString` pattern. Copy them unconditionally on a `needsInstall`
(clean reinstall) so they are always present and current before `install.runCommand(monitor)`
runs. No demotion counter, no `pnpm rebuild`, no `isPnpmMode()` branching in the install logic —
the package manager reads the committed config itself.

Because the key is version-scoped and the whole dir is wiped+recreated on a version bump
(`needsInstall` → `deleteDirectory` at `OpencodeFolderCreatorJob.java:116-124`), the fresh install
always sees the matching `allowBuilds` entry and runs the postinstall.

### 3.3 Install-time binary verification (diagnostic)
After the install, resolve the bin the same way `RunOpencodeCommand.resolveOpencodeExecutable`
does and verify it is a **real** binary rather than the 229-byte placeholder, so a
misconfiguration is reported loudly at install time (named log + non-OK `IStatus`) instead of
surfacing later as the opaque launch-time `error=216`. Add a package-visible, side-effect-free
helper so it is unit-testable without OSGi (mirroring `needsInstall`/`readUrlContent`/
`deleteDirectory`):
```java
static boolean isPlaceholderBinary(File binFile) // true if missing/tiny/starts with the echo "Error: marker
```
The placeholder is a ~229-byte text file whose first line starts with
`echo "Error: @opencode/cli's postinstall script was not run."`; a real binary is MB-scale and does
not start with that marker. Check: file exists, size above a small threshold, first bytes not the
marker. This check is best-effort and must be logged clearly; whether a placeholder result returns
`WARNING` and still schedules the server, or hard-stops, follows the existing install-exit handling
(a `WARNING` status, consistent with the current non-zero-exit path at `:157-161`).

### 3.4 Guard test: approval config must match the pinned version
The core of the redirect: a test that fails when the committed approval config drifts from the
pinned opencode version. It parses:
- the pinned version from `opencode/package.json` (`dependencies["@opencode/cli"]`), and
- the `allowBuilds` keys from `opencode/pnpm-workspace.yaml`,
and asserts the map contains exactly the version-scoped key `@opencode/cli@<pinnedVersion>` set to
`true` (and no stale `@opencode/cli@<otherVersion>` entry left behind). This makes a version bump
that forgets the approval config fail CI, which is precisely the safety the user asked for
("when updating opencode version in the package.json a test must also make sure that the allowed
stuff is correct").

The test reads the **committed bundle resource files** (resolved from the bundle/plugin folder on
disk), not the runtime state dir, so it runs headless in the plain-JUnit fragment with no OSGi and
no install. A lightweight line/regex parse of the YAML `allowBuilds` block is sufficient (no YAML
library dependency needed); if a parser is already available on the test classpath it may be used.

### 3.5 update-opencode skill
Update `skills/update-opencode/SKILL.md` so bumping the pin also updates these committed approval
files. Add to step 4 (the version bump): after setting the exact version in `package.json`, rewrite
the `allowBuilds` key in `opencode/pnpm-workspace.yaml` (and the npm `.npmrc` if it is
version-scoped) to the new `@opencode/cli@<version>`, and note that `OpencodePnpmApprovalTest` (the
guard test) must be run/green as part of verification (step 6). This keeps the "configured-like-CI"
invariant true across future bumps.

### 3.6 Git history (from triage)
- The install call `createNPMCommand(opencodeDir, List.of("install"))` dates from the bundle's
  original creation (`0c0fc980`, 2026-06-08) and predates pnpm mode; it was written against
  npm-only behavior where lifecycle scripts run by default. No deliberate decision to skip build
  scripts was ever made.
- The pnpm build-script gating was introduced in servoy-eclipse by SVY-21456 and reworked by
  SVY-21502 (version-scoped `allowBuilds` in a generated `pnpm-workspace.yaml`). This spec reuses
  the **same committed `allowBuilds` format** but ships it statically in the plugin rather than
  generating it per solution.
- `@opencode/cli` is pinned to `2.1.1` (`opencode/package.json:7`); the pin discipline
  (SVY-21461) is why the approval config is version-scoped and guarded by a test.

## 4. Implementation plan
1. Add `bundles/com.servoy.eclipse.opencode/opencode/pnpm-workspace.yaml` with the version-scoped
   `allowBuilds: '@opencode/cli@2.1.1': true` plus `nodeLinker: hoisted`, `shamefullyHoist: true`,
   `strictDepBuilds: true`, and the explanatory comment from §3.1.
2. Add `bundles/com.servoy.eclipse.opencode/opencode/.npmrc` with the npm-side allowance for this
   managed install dir (exact key confirmed against the shipped npm version — §7). Include a
   comment explaining it is the npm counterpart of the pnpm approval.
3. Ensure both new files are included in the built bundle: check `build.properties`
   (`bin.includes`) for `com.servoy.eclipse.opencode` and add `opencode/pnpm-workspace.yaml` /
   `opencode/.npmrc` if the `opencode/` folder is not already globbed in. Verify
   `activator.getBundle().getEntry("/opencode/pnpm-workspace.yaml")` resolves at runtime.
4. In `OpencodeFolderCreatorJob.run(...)`, in the `needsInstall` clean-slate block (after writing
   `package.json`, `:128-131`), also copy `/opencode/pnpm-workspace.yaml` and `/opencode/.npmrc`
   from the bundle into `opencodeDir`, reusing `readUrlContent` + `Files.writeString`. Factor a
   tiny `copyBundleResource(bundle, "/opencode/<name>", targetDir)` helper if it reads cleaner.
   Handle a missing bundle entry defensively (log, continue) so a packaging slip is diagnosable.
5. Add the package-visible `static boolean isPlaceholderBinary(File binFile)` helper (§3.3) and,
   after a successful install, resolve the bin (call/mirror `resolveOpencodeExecutable`) and log +
   return a `WARNING` status when the bin is still the placeholder.
6. Preserve all existing comments (notably the "no `npm update` on purpose" block at `:163-171`)
   and keep the marker-file / sentinel flow unchanged. No `isPnpmMode()` branching and no
   `pnpm rebuild` are added — the committed config drives everything.
7. Update `skills/update-opencode/SKILL.md` per §3.5.
8. Post-change: `eclipse-ide_getCompilationErrors()` → zero errors; address the two highest-severity
   SpotBugs findings in the touched Java.

### 4.1 Tests
- **Plain JUnit** (`com.servoy.eclipse.opencode.tests.plainjunit`, no OSGi):
  - **`OpencodePnpmApprovalTest`** (new) — the guard test (§3.4): parses the committed
    `opencode/package.json` pinned version and the committed `opencode/pnpm-workspace.yaml`
    `allowBuilds` map, and asserts the only `@opencode/cli@*` key is `@opencode/cli@<pinnedVersion>: true`.
    Resolve the bundle `opencode/` folder from the test's working dir / fragment host location. Use
    `@DisplayName` only on methods.
  - Extend `OpencodeFolderCreatorJobTest` to cover `isPlaceholderBinary`: a 229-byte file starting
    with the `echo "Error:` marker → placeholder; a large file without the marker → real; a missing
    file → placeholder. Use `@TempDir` as the existing tests do.
- No integration (`integrationpde`) test is required; the committed-config + guard-test + plain
  helper tests cover the regression without a full-workbench pnpm install. (Open question §7 if the
  team wants a live install smoke.)

## 5. Acceptance criteria
- [ ] `bundles/com.servoy.eclipse.opencode/opencode/pnpm-workspace.yaml` is committed with a
      version-scoped `allowBuilds: '@opencode/cli@2.1.1': true` (plus hoist settings and
      `strictDepBuilds: true`) and is packaged into the bundle (`build.properties`).
- [ ] An npm-side allowance (`.npmrc` in the opencode dir) is committed and packaged, keeping npm
      mode pre-configured for npm versions that block scripts by default.
- [ ] `OpencodeFolderCreatorJob` copies both committed files into `{state}/opencode` before running
      the install, on every clean (re)install.
- [ ] In pnpm mode, a fresh install now materializes a real native `opencode.exe` (large PE binary)
      under `node_modules/@opencode/cli/bin/`, not the 229-byte placeholder; the server launches
      without `CreateProcess error=216`; `opencode.exe --version` reports `2.1.1`.
- [ ] npm mode continues to produce a working install.
- [ ] When the resolved bin is still the placeholder/missing after install, the job logs a clear,
      named message and returns a non-OK status instead of failing opaquely at launch.
- [ ] `OpencodePnpmApprovalTest` fails if `package.json`'s pinned version and
      `pnpm-workspace.yaml`'s `allowBuilds` key diverge (verified by a deliberate temporary
      mismatch during development), and passes for the committed pair.
- [ ] A plain-JUnit test asserts `isPlaceholderBinary` (placeholder marker / real binary / missing).
- [ ] `skills/update-opencode/SKILL.md` instructs the maintainer to update the approval config on a
      version bump and to run the guard test.
- [ ] `eclipse-ide_getCompilationErrors()` reports zero errors; two highest-severity SpotBugs levels
      clean in touched code.

## 6. Out of scope
- Wiring the opencode install dir into the solution-scoped pnpm build-approvals subsystem
  (`WebPackagesListener`, `pnpm-build-approvals.json`, per-workspace approve/deny prompt, lockfile
  seed/commit-back) — explicitly rejected; the committed static config is the whole point.
- A runtime demotion window / `pnpm rebuild` from the job (original triage Approach 1) — superseded
  by the committed-config approach.
- Any change to servoy-eclipse — the fix is entirely in Servoy-Copilot (config files + job copy +
  test + skill).
- Reaching into `@opencode/cli`'s private `postinstall.mjs` directly.
- Changing opencode's pinned version or the `needsInstall`/sentinel reinstall strategy.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Exact npm config key for the `.npmrc` allowance against the npm version the plugin actually bundles (does it block scripts yet? which key — `ignore-scripts=false`, a trusted-deps list, or none needed?). Pin it against the shipped npm, do not guess. | Dev | Open — resolve by checking the bundled npm's version/docs |
| Does the opencode install dir need `nodeLinker: hoisted` + `shamefullyHoist: true` for `resolveOpencodeExecutable` to find `node_modules/@opencode/cli/bin`, or is the default layout fine? Confirm against a real pnpm install. | Dev | Open (hoist settings included as the safe default) |
| Minimum size threshold for `isPlaceholderBinary` — assert marker-absence + non-trivial size, without hardcoding 209 MB. | Dev | Open (assumption documented in §3.3) |
| Is a live pnpm-install integration smoke worth adding, or do the committed config + guard test + plain helper tests suffice given install time/network cost? | PM | Open |

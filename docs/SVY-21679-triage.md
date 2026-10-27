# Triage Report — SVY-21679

**Verdict:** PROCEED

## Reported problem
When Servoy Developer runs in pnpm mode (`servoy.jsRuntime=pnpm`), the Servoy AI
(opencode) server fails to start. Launching the native binary fails with:

```
[Servoy AI] Server process I/O error: Cannot run program "...\com.servoy.eclipse.opencode\opencode\node_modules\@opencode\cli\bin\opencode.exe": CreateProcess error=216, This version of %1 is not compatible with the version of Windows you're running.
[Servoy AI] Server exited unexpectedly (code -1) - no more retries.
```

The symptom (observed behaviour) is: in pnpm mode the launched `opencode.exe` is not a
real executable, so Windows refuses to `CreateProcess` it (the `error=216` is Windows'
generic "not a valid PE image" message, not an actual CPU-architecture mismatch).

## Root-cause assessment
The chain is confirmed from source:

1. `OpencodeFolderCreatorJob` installs the opencode package with a plain npm `install`
   verb — `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:141`:
   ```java
   IRunNPMCommand install = ngActivator.createNPMCommand(opencodeDir, List.of("install"));
   ```
   `opencodeDir` is the managed state folder `{eclipse-state}/opencode` — a standalone,
   freshly-created install directory (`OpencodeFolderCreatorJob.java:92`, cleaned and
   re-created at lines 116-124). It is **not** a Servoy solution folder.

2. In pnpm mode `createNPMCommand` returns a `RunPNPMCommand`, not a `RunNPMCommand`
   (servoy-eclipse `com.servoy.eclipse.ngclient.ui/.../Activator.java:483-491` on
   `master`). So the `install` verb is translated to `pnpm install`
   (`RunPNPMCommand.translateToPnpmArguments`, `RunPNPMCommand.java:611-623`).

3. `RunPNPMCommand` only disables `strictDepBuilds` for a run when a bundle-static
   "build-script discovery demotion" window is open
   (`RunPNPMCommand.java:74,96-99,214-220`). That window is opened **exclusively** by
   `WebPackagesListener` around the TiNG/web-package solution install
   (servoy-eclipse `WebPackagesListener.java:856-860,1111-1119`). The opencode install
   runs through none of that machinery, so it executes with pnpm's default
   `strictDepBuilds: true` and **no** `pnpm-workspace.yaml`/`allowBuilds` entry for
   `@opencode/cli` in `{state}/opencode`.

4. Under pnpm 11+/12, `strictDepBuilds: true` (the default) blocks dependency build
   scripts unless explicitly approved. `@opencode/cli`'s `postinstall` (`node
   ./postinstall.mjs`) — the step that downloads and copies the real ~209 MB native
   `opencode.exe` into `node_modules/@opencode/cli/bin/` — is therefore **skipped**.
   The bin is left as the 229-byte placeholder shell script shipped in the package
   (first line literally `echo "Error: @opencode/cli's postinstall script was not run."`).

5. `RunOpencodeCommand.resolveOpencodeExecutable` finds that placeholder file (it exists,
   it just is not a PE binary) and launches it directly
   (`RunOpencodeCommand.java:123-128,283-298`). Windows cannot `CreateProcess` a non-PE
   file and returns `error=216`. The watchdog never sees a healthy server; the job
   exhausts its 3 retries (`RunOpencodeCommand.java:184-192`).

In npm mode the same `install` runs as `RunNPMCommand` (`node npm-cli.js install`), where
npm runs dependency lifecycle scripts by default, so the postinstall runs and the real
binary is materialized — which is why the bug is pnpm-specific.

The ticket's own reproduction and manual workaround (delete `node_modules`, re-run the
install, then run `node ./postinstall.mjs` → bin becomes a real ~209 MB PE and
`opencode.exe --version` prints `opencode v2.1.1`) independently confirm this chain.

## Ticket premise check
The ticket's root-cause analysis is **correct and precise**, and the user context
("when pnpm is used we should always allow the opencode install to use a script because
that script will install the native executables") matches it exactly. Unlike many
tickets, the proposed solution here does not aim at the wrong code — it points directly
at `OpencodeFolderCreatorJob`'s install and at the existing `strictDepBuilds`
demotion/approvals mechanism in `RunPNPMCommand`/`WebPackagesListener`.

One refinement for the implementer: the full `WebPackagesListener` approvals flow
(`applyKnownPnpmBuildApprovals` + discovery + approve/deny prompt + `pnpm rebuild`) is
solution-scoped and overkill for this fixed, first-party, pinned dependency in a managed
install dir. The build-script allowance for `@opencode/cli` is a **known, trusted,
fixed** set, not something to prompt the user about per workspace. So the right-sized fix
is to let **only** this install run its dependency build script, not to wire the opencode
dir into the solution approvals subsystem.

## Approaches considered

1. **Wrap the opencode install in `RunPNPMCommand.beginBuildScriptDiscoveryDemotion()`
   / `endBuildScriptDiscoveryDemotion()` and then run `pnpm rebuild @opencode/cli`
   (pnpm mode only).**
   - Pros: reuses the exact mechanism already built for SVY-21502; the demotion window
     lets `pnpm install` complete without hard-failing on the ignored build script, and
     `pnpm rebuild` then actually runs the postinstall so the native binary is fetched.
     `rebuild` is specifically how pnpm applies an allowed/added build script after an
     install that ignored it (see `PnpmBuildScriptConfig.rebuildApprovedBuildScripts`).
     No per-workspace prompt. Smallest change, no new files.
   - Cons: the demotion counter is a cross-bundle static; the opencode install runs on a
     different job than `WebPackagesListener`, so timing must be contained (open/close in
     a try/finally strictly around this one install). Needs a `pnpm rebuild` step because
     opening the demotion window alone makes pnpm *skip* (ignore) the script, not run it.

2. **Write a minimal `pnpm-workspace.yaml` (or `.npmrc`) into `{state}/opencode` that
   allows `@opencode/cli`'s build script (`allowBuilds: '@opencode/cli': true` /
   `strictDepBuilds: false` scoped to this dir) before the install.**
   - Pros: declarative, local to the opencode dir, independent of the shared static
     counter; survives re-installs cleanly (the folder is regenerated each reinstall
     anyway). Closest in spirit to "always allow the opencode install to use a script".
   - Cons: must track pnpm's current config key names (pnpm 12 uses `allowBuilds`, not
     `onlyBuiltDependencies`); still typically needs a `pnpm rebuild` because the script
     is only *run* on a fresh install when allowed up-front, so ordering matters. Adds a
     file-generation concern to the opencode bundle.

3. **Run `node postinstall.mjs` explicitly after the install, bypassing pnpm's gate.**
   - Pros: most direct materialization of the binary.
   - Cons: reaches into `@opencode/cli`'s private install internals (script path/name is
     not a stable contract and could change on a version bump — exactly the kind of
     breakage the pin/update discipline guards against); fragile across opencode
     versions. Least aligned with the existing, maintained pnpm approvals pattern.

4. **No code change.**
   - Pros: none for pnpm users.
   - Cons: Servoy AI is fully non-functional in pnpm mode (server never starts). The
     manual workaround is per-workspace, must be redone on every reinstall/version bump,
     and is not discoverable. Not acceptable as a resolution.

## Recommendation
**PROCEED** with **Approach 1** (demotion window around the opencode `pnpm install`,
followed by a pnpm-mode-only `pnpm rebuild` of `@opencode/cli`), as the primary approach:
it reuses the already-tested SVY-21502 mechanism, keeps the allowance scoped to this one
trusted dependency with no per-workspace prompt, and is the smallest, lowest-risk change.
Approach 2 (a generated local `pnpm-workspace.yaml`/`allowBuilds` for `@opencode/cli`) is
a solid alternative if the implementer prefers a declarative, self-contained
install-dir config over toggling the shared static counter from a second job. Both should
guard on `com.servoy.eclipse.ngclient.ui.Activator.isPnpmMode()` so npm mode is untouched,
and both should verify success by checking the resulting `opencode.exe` is a real binary
(e.g. size / `--version`) rather than the placeholder. Approach 3 is a fallback only.

A note for implementation and testing: the demotion/approvals APIs
(`RunPNPMCommand.beginBuildScriptDiscoveryDemotion`,
`PnpmBuildScriptConfig.rebuildApprovedBuildScripts`) live in **servoy-eclipse**
(`com.servoy.eclipse.ngclient.ui`), which the opencode bundle already requires
(`MANIFEST.MF` line 16). The fix is in `OpencodeFolderCreatorJob` in Servoy-Copilot; no
change to servoy-eclipse is expected unless a new narrowly-scoped helper is desired there.

## Git history findings
- The opencode install call (`createNPMCommand(opencodeDir, List.of("install"))`) dates
  from the bundle's original creation (`0c0fc980`, 2026-06-08) and predates pnpm mode; it
  was written against npm-only behaviour where lifecycle scripts run by default. No
  intentional decision to skip build scripts was ever made — the pnpm gating is an
  emergent side effect of pnpm mode selecting `RunPNPMCommand`.
- The pnpm build-script gating this trips over was introduced in servoy-eclipse by
  SVY-21456 (initial `strictDepBuilds=false` stopgap) and reworked by SVY-21502 (the
  version-scoped `allowBuilds` + `beginBuildScriptDiscoveryDemotion` window + discovery/
  approve/`pnpm rebuild` flow). The opencode bundle was never wired into that flow, so in
  pnpm mode its install runs with pnpm's blocking default. Recent `RunPNPMCommand` commits
  (`2f8e5ffd`, `328eb7ba`, `86d11fd2`, all SVY-21456, 2026-10) confirm the pnpm command
  path is actively maintained and the demotion API is current.
- opencode is pinned to `@opencode/cli` `2.1.1`
  (`bundles/com.servoy.eclipse.opencode/opencode/package.json:7`); the version pin
  discipline (SVY-21461, `f6c2465`) is another reason to prefer the approvals-mechanism
  approach over reaching into the package's private `postinstall.mjs`.

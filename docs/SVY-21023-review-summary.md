# SVY-21023 — Peer review summary

**Risk: LOW** — overwhelmingly additive (two new services, nine new MCP tools, tests); the only change to existing behaviour is one additive field in `getCompilationErrors`, and the one scary item (a hard-coded bearer token) was reverted in the next commit and is not on HEAD.

**Scope reviewed:** Servoy-Copilot `ce8de70` + `addc313`, skill4servoy `4367f2c` + `19776e5` — all on `master`. Adds web-package / component-spec MCP tools (Thread A) and reinstated Servoy-native code-navigation tools (Thread B), each spanning both repos (Java tools here, endpoint + agent permissions + prompting in skill4servoy).

## Manual test plan

Steps a human still has to run (no Servoy Developer instance was driven during review).

### Verifying the fix
1. With a solution open, call `getInstalledPackages` and `getAvailableWebPackages` — confirm the installed set matches the WPM view and that available packages list those with updates / not-installed.
2. Call `getComponents` for a known package, then `getComponentSpec` and `getComponentDocs` on one component — confirm the spec (model properties, handlers, api) and the `_doc.js` text come back live and version-exact, without touching any zip.
3. Call `installPackage` then `uninstallPackage` for a throwaway package; then try uninstalling a package another installed package depends on — confirm it is refused with the dependents listed, and that `force=true` removes it anyway.
4. Introduce a compile error, call `getCompilationErrors`, copy the `Marker ID`, call `executeQuickFix` with `proposalIndex=-1` to list fixes, then apply one — confirm the marker clears.
5. Call `findReferences` / `getTypeHierarchy` / `getMethodCallHierarchy` on a form method and on a form that `extends` another — confirm module objects are included (not just the active solution's own).

### Regression checks
1. Run `getAvailableWebPackages` with the network unreachable — confirm it fails/returns gracefully rather than hanging the MCP request thread.
2. Grep agent/skill markdown and any client parser for a pattern anchored on `source: ...)` — confirm none requires the per-marker line to end right after the source id (the Marker ID field was added inside those parens).
3. Confirm existing WPM tools (`searchPackages`, `installPackage`) and existing `servoy-ide` tools still work unchanged — no existing tool was renamed, removed or retyped.

### Automated checks worth running
- Headless unit tests: `mvn -B clean verify -pl tests/com.servoy.eclipse.developer.mcp.tests -am "-Dtycho.localArtifacts=ignore" "-Dmaven.test.failure.ignore=true"` (covers `WpmServiceTest`, `ServoyWpmServerTest`).
- Integration tests: add `-Pintegration -Dservoy_install=<path>` (covers `ServoyWpmServerIntegrationTest`, `CodeAnalysisIntegrationTest`).
- A Spotbugs run over the three new/changed services (`ComponentSpecService`, `WpmService`, `CodeAnalysisService`) was not performed this session.

## Possible improvements / follow-ups

1. **Cross-repo release coupling** — the nine Java `@Tool`s are inert for agents until skill4servoy's endpoint + permissions + docs ship, and vice-versa. Both halves are present and consistent in this review; confirm the two repos release as a unit, not one ahead of the other.
2. **`getAvailableWebPackages` does a remote fetch on the MCP request thread** (`WpmService.java:317`, `includeAllRepositories=true`). Decide whether a network-blocking read tool is acceptable and whether it degrades gracefully offline.
3. **`uninstallPackage force=true` is agent-reachable and irreversibly deletes the package zip** (`WpmService.java:183`). Default refuses with dependents listed; `force` overrides. Confirm the blast radius is intended for the Developer agent.
4. **`executeQuickFix` drives `IMarkerResolution`s headlessly on the UI thread.** Someone who knows the registered quick-fixes should confirm none has a surprising side effect when driven by an agent.
5. **Stale tool-name in agent docs** — `skill4servoy/.opencode/skills/servoy-agent-shared/context/mcp-tooling.md:18` labels `executeQuickFix`/`getCompilationErrors` with the `servoy-workspace_` prefix, but the server registers as `servoy-ide` and the permissions correctly use `servoy-ide_`. The gate is correct; only the prose should be fixed to `servoy-ide_`.
6. **Bearer-token hygiene** — `addc313` committed a literal `Authorization: Bearer …` into the local `opencode.json`; it was removed in `879ddb1` and is not on HEAD. If the two SVY-21023 commits are ever cherry-picked as a standalone unit, make sure `879ddb1` rides along so the literal never ships.

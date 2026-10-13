# Spec: SVY-21284 — Drop cross-repo test-infra dependency, adopt plainjunit/integrationpde split and shared launcher copy (Servoy-Copilot)

## 1. Goal

Finish the Servoy-Copilot half of the PDE/plain-JUnit integration-test infra consolidation started under SVY-21284. Specifically: adopt the `plainjunit`/`integrationpde` package convention consistently (removing the old `@Suite`/`AllXxxTests` aggregator classes), wire a matching Maven/Tycho profile split with Jenkins CI, and replace a cross-repo OSGi `Require-Bundle` dependency on servoy-eclipse's shared test-infra bundles — which only ever worked in a live combined Eclipse workspace, not in this repo's actual Jenkins/Tycho build — with locally duplicated, clearly-marked `Copy`-suffixed classes.

## 2. Background

### 2.1 Starting point

Prior SVY-21284 work in this repo (see git history, e.g. "Related to SVY-21284" commits) already:
- Added required bundles and fixed Jenkins to fail on test failure/error instead of silently passing.
- Added "ability to always ignore some common temporary modal dialogs" during JUnit runs.
- Extracted shared PDE integration-test base classes (`TestUtilitiesClass`, `ServoyRunnerTestBase`, `DialogGuardRule`, `DialogGuardBase`, `DialogExpectation`, `TestDialogInterceptor`, `AbstractIntegrationTest`/`AbstractTitaniumIntegrationTest`) that `com.servoy.eclipse.developer.mcp.tests` consumed from servoy-eclipse's `com.servoy.eclipse.core.tests`/`com.servoy.eclipse.ngclient.ui.tests` via `Require-Bundle`, instead of duplicating them locally — avoiding duplication at the cost of a cross-repo OSGi dependency.
- Deleted the old `AllDeveloperMcpTests`/`AllDeveloperMcpIntegrationTests`/`AllWpmTests`/`AllDeveloperMcpJupiterUnitTests` `@Suite` aggregator classes and the ad-hoc `.launch` files that targeted them (`AllDeveloperMcpIntegrationTests.launch`, its `_mac` variant, a stale draft `AllEclipseCypressPDEtests.launch`), moving to the package-based split described below.

### 2.2 The cross-repo dependency problem

`com.servoy.eclipse.developer.mcp.tests`' `Require-Bundle` on servoy-eclipse's `com.servoy.eclipse.core.tests`/`com.servoy.eclipse.ngclient.ui.tests` only ever resolved because both repos were open together in one live Eclipse workspace during local PDE launches. This repo's actual Jenkins/Tycho build resolves its target platform from a p2 update site built from `com.servoy.eclipse.feature`, which — correctly — does not include any `.tests` project. So the dependency had no resolvable path in CI, meaning `com.servoy.eclipse.developer.mcp.tests` likely could not even *compile* against those shared classes on Jenkins, independent of whether any Cypress-specific stage existed.

Standing up a real artifact-publishing/consumption pipeline between the two repos' separate Tycho reactors for test-only bundles was evaluated (p2 publishing, shared local `.m2` relying on OSGi qualifiers, a Jenkinsfile-level file copy) and rejected as more infrastructure than the problem warrants. The chosen fix: remove the `Require-Bundle` dependency entirely and keep a local, explicitly-marked duplicate of each shared class instead.

## 3. Design

### 3.1 `plainjunit` / `integrationpde` package convention

`com.servoy.eclipse.developer.mcp.tests` and `com.servoy.eclipse.opencode.tests` are split into:
- `<base>.plainjunit[.*]` — pure JUnit, no OSGi/workbench.
- `<base>.integrationpde[.*]` — needs a full Eclipse workbench + OSGi container, including tests whose own methods don't call OSGi APIs directly but exercise code that transitively does (e.g. `com.servoy.eclipse.opencode.tests`' `SkillsZipExtractorTest`, which calls into `SkillsZipExtractor`/`OpenCodeUtil`, which call `Activator.getInstance()` — `null` outside OSGi).

For `com.servoy.eclipse.developer.mcp.tests` this replaces the old single `src/test/java/com/servoy/eclipse/developer/mcp/integration` + top-level-package layout entirely; there are no `@Suite`/`AllXxxTests` aggregator classes left in the project — running a package or the whole project directly executes each test class exactly once.

### 3.2 No cross-repo test-project dependency; `Copy`-suffixed duplicated utility classes

`com.servoy.eclipse.developer.mcp.tests`' `Require-Bundle` on servoy-eclipse's `com.servoy.eclipse.core.tests`/`com.servoy.eclipse.ngclient.ui.tests` is removed. Each shared base class now has its own local duplicate under `com.servoy.eclipse.developer.mcp.integrationpde.utils`, named with a `Copy` suffix:

| Copy (here) | Original (servoy-eclipse) |
|---|---|
| `TestUtilitiesClassCopy` | `com.servoy.eclipse.core.tests.integration.utils.TestUtilitiesClass` |
| `ServoyRunnerTestBaseCopy` | `com.servoy.eclipse.core.tests.integration.utils.ServoyRunnerTestBase` |
| `DialogGuardRuleCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogGuardRule` |
| `DialogGuardBaseCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogGuardBase` |
| `DialogExpectationCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogExpectation` |
| `TestDialogInterceptorCopy` | `com.servoy.eclipse.core.tests.integration.utils.TestDialogInterceptor` |
| `AbstractTitaniumIntegrationTestCopy` | `com.servoy.eclipse.ngclient.ui.tests.integrationpde.utils.AbstractTitaniumIntegrationTest` |

The `Copy` suffix is permanent, not a TODO — it's how an agent working in either repo recognizes "this class must be kept behaviorally identical to its counterpart in the other repo." Each class's Javadoc cross-references its counterpart explicitly. `com.servoy.eclipse.developer.mcp.tests`' `META-INF/MANIFEST.MF` no longer requires either servoy-eclipse bundle.

This does **not** change the convention within servoy-eclipse itself — `.tests` projects there still share infra via `Require-Bundle` internally; only the cross-repo instance of that pattern was replaced.

### 3.3 Maven/Tycho wiring for `com.servoy.eclipse.opencode.tests`

`com.servoy.eclipse.opencode.tests` previously had no Maven profile split and ran entirely as plain JUnit, with its one OSGi-dependent test (`SkillsZipExtractorTest`) failing when run that way. Added the matching `tycho-surefire-plugin` default (`plainjunit` include) + `-Pintegration` profile (`integrationpde` include, `useUIHarness=true`, real `product`/`application`) already used by `com.servoy.eclipse.developer.mcp.tests`, minus the Servoy-install-tarball extraction step (this project's `integrationpde` test doesn't need a live app server, just OSGi).

### 3.4 Jenkins CI

Added `tests/com.servoy.eclipse.opencode.tests` to the existing "Integration Tests" stage's `-pl` list in `Jenkinsfile`, alongside `tests/com.servoy.eclipse.developer.mcp.tests`:

```
mvn ... verify -Pintegration -pl tests/com.servoy.eclipse.developer.mcp.tests,tests/com.servoy.eclipse.opencode.tests -am ...
```

### 3.5 Shared launcher: `PDEIntegrationTestsBaseCopy`

The canonical shared JUnit Plug-in Test launch config, `PDEIntegrationTestsBase.launch`, lives in servoy-eclipse's `com.servoy.eclipse.core.tests`. This repo keeps its own copy, `com.servoy.eclipse.developer.mcp.tests/PDEIntegrationTestsBaseCopy.launch` — same shape (full `selected_workspace_bundles`/`selected_target_bundles` sets, VM args), differing only in its `-Dproperty-file` (points at this repo's own `testresources/servoy.properties`) and its checked-in default target. The two files are expected to be kept in sync for everything else.

`testresources/servoy.properties` itself remains a deliberate duplicate of servoy-eclipse's copy (not shared via any build dependency, for the same cross-reactor reason as 3.2) — both repos' `AGENTS.md` document this and ask that both be updated together when the content needs to change.

## 4. Implementation plan

1. Delete the `AllDeveloperMcpTests`/`AllDeveloperMcpIntegrationTests`/`AllWpmTests`/`AllDeveloperMcpJupiterUnitTests` suite classes and their associated `.launch` files.
2. Split `com.servoy.eclipse.developer.mcp.tests`' tests into `plainjunit`/`integrationpde` packages (already largely in place entering this session; completed/verified).
3. Remove the `Require-Bundle` on servoy-eclipse's `core.tests`/`ngclient.ui.tests` from `com.servoy.eclipse.developer.mcp.tests`' `MANIFEST.MF`.
4. Create the seven `Copy`-suffixed utility classes under `com.servoy.eclipse.developer.mcp.integrationpde.utils`, each a faithful duplicate of its servoy-eclipse counterpart, cross-referenced via Javadoc.
5. Update every integration test class that previously imported the shared (non-`Copy`) classes to import the local `Copy` versions instead.
6. Split `com.servoy.eclipse.opencode.tests` into `plainjunit`/`integrationpde` and add matching `pom.xml` wiring.
7. Create `PDEIntegrationTestsBaseCopy.launch`, pointed at this repo's own property-file path; verify via `eclipse-pde_runJUnitPluginTests`.
8. Add `tests/com.servoy.eclipse.opencode.tests` to the Jenkinsfile's integration-tests `-pl` list.
9. Document the convention changes (`plainjunit`/`integrationpde`, no-cross-repo-dependency, `Copy` classes, shared launcher copy) in `AGENTS.md`.
10. Verify: compile clean for `com.servoy.eclipse.developer.mcp.tests` and `com.servoy.eclipse.opencode.tests`; a full `integrationpde` PDE run (verified: 688/688 passed via `PDEIntegrationTestsBaseCopy` + `packageName` override).

## 5. Acceptance criteria

- [x] No `@Suite`/`AllXxxTests` aggregator classes remain in `com.servoy.eclipse.developer.mcp.tests`.
- [x] `com.servoy.eclipse.developer.mcp.tests` has no `Require-Bundle` on any servoy-eclipse `.tests` project.
- [x] All seven shared base classes have a local `Copy`-suffixed duplicate under `com.servoy.eclipse.developer.mcp.integrationpde.utils`, each cross-referencing its servoy-eclipse counterpart in Javadoc.
- [x] `com.servoy.eclipse.opencode.tests` has a `plainjunit`/`integrationpde` split with matching `pom.xml` wiring.
- [x] `Jenkinsfile`'s integration stage includes both `com.servoy.eclipse.developer.mcp.tests` and `com.servoy.eclipse.opencode.tests`.
- [x] `PDEIntegrationTestsBaseCopy.launch` exists and a real run against `com.servoy.eclipse.developer.mcp.integrationpde` passes (688/688).
- [x] Both affected projects compile with 0 errors.
- [ ] A full `mvn verify -Pintegration` run on the real Jenkins node (covering both projects together) — not run this session; left for the user to verify on Jenkins.

## 6. Out of scope

- Building any cross-repo artifact-publishing/consumption pipeline (p2 publish, shared `.m2`, Jenkinsfile file copy) to restore a non-duplicated dependency — explicitly rejected per the Background section.
- macOS `.launch` variants for the new shared launcher copy.
- `com.servoy.eclipse.servoypilot.assistenttests` — explicitly out of scope per earlier user instruction ("kind of deprecated"); its `MANIFEST.MF`/`PermissionToolIntegrationTest.java` changes in this diff are limited to pointing at the already-duplicated `ServoyRunnerTestBase` rather than a stray local copy, not a full convention migration.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the duplicated `Copy` classes ever be re-merged into a shared dependency if a lighter-weight cross-repo artifact mechanism becomes available later? | user | open, flagged as a future possibility, not planned |
| Jenkins job config for this repo (Xvfb, Servoy-install tarball availability) — assumed already correctly set up since the existing stage already used them for `developer.mcp.tests` | user | presumably fine, not reverified this session |

# SDD Project Context — Servoy Copilot

This is the repo-local project context consumed by the `sdd-java-eclipse` skill.
It is distilled from the repository root `AGENTS.md` (the authoritative source). If
this file and `AGENTS.md` ever disagree, `AGENTS.md` wins — update this file to match.

Every SDD phase subagent (Triage, PM, Coding, Code Review, Test Gen, Test Review) starts
with a fresh context and receives this file verbatim as its PROJECT CONTEXT.

---

## What this repository is

The **Servoy AI Copilot** plugin for the Servoy Developer IDE. An Eclipse PDE
(Plugin Development Environment) project built with **Tycho/Maven**.

**Two actively developed bundles** (make changes here):

1. `com.servoy.eclipse.developer.mcp` — the Eclipse IDE MCP server (AssistAI). Runs
   inside the Eclipse JVM and exposes IDE operations (file read/write, search,
   compilation, git, PDE tests) as MCP tools callable by external AI agents. Two-JVM
   architecture: this bundle runs inside Eclipse; the MCP client runs in the agent's JVM.

2. `com.servoy.eclipse.opencode` — the opencode AI wrapper. Installs/updates/manages the
   lifecycle of the `opencode` CLI embedded in Servoy Developer, and hosts a custom
   **Angular chat UI** (`webui/`) served by a same-origin BFF servlet (`OpencodeChatServlet`,
   in the `.tomcat` package) that proxies opencode's HTTP + SSE API under
   `/servoy_ai/rest_api/**`. Requires two system properties to activate: `GENAI_API_KEY`
   and `SERVOY_SKILLS_ZIP`.

**Reference-only bundles — DO NOT modify unless explicitly instructed:**
`com.servoy.eclipse.servoypilot`, `com.servoy.eclipse.servoypilot.langchain4j`,
`com.servoy.eclipse.servoypilot.knowledgebase`, `com.servoy.eclipse.servoypilot.assistenttests`.

**Build/distribution artifacts (active):** `com.servoy.eclipse.servoypilot.feature`
(feature packaging), `repository.site_aiplugin` (P2 update site), `launch_target_aiplugin`
(target platform — resolves against build.servoy.com + Maven Central + Servoy Maven repo;
do NOT duplicate dependencies already provided by the Servoy target).

### Key classes in `com.servoy.eclipse.opencode`

| Class | Role |
|---|---|
| `Activator` | Plugin lifecycle — schedules setup, tracks server-ready state, shuts down process tree on stop |
| `OpencodeFolderCreatorJob` | One-shot Job: installs/updates opencode via npm, extracts skills zip, merges MCP config, starts server |
| `RunOpencodeCommand` | Long-running Job: finds free port (from 4096), launches `npm exec -- opencode serve`, watchdog-polls HTTP until ready |
| `OpencodeServerState` | Latch + port holder used to signal and wait for server readiness |
| `OpenCodeView` | Eclipse ViewPart hosting the embedded browser; state machine for login / config / project activation |
| `OpencodePerspective` | Perspective factory for the "Servoy AI" layout |
| `McpConfigWriter` | Merges `IMcpEndpointProvider` contributions into `opencode.json` via Jackson; matches on URL (not server name) |
| `IMcpEndpointProvider` | Extension point interface — returns MCP endpoint URLs and optional auth token |
| `ProviderConfigWriter` | Writes `GENAI_API_KEY` env var and ensures `$schema` in `opencode.json` |
| `SkillsZipExtractor` | Extracts `SERVOY_SKILLS_ZIP` into `~/.servoy/opencode/`; updates `AGENTS.MD` with runtime versions |
| `OpenCodeUtil` | Static helpers: resolves active project path, walks up to git root |
| `OpencodeChatServlet` (`.tomcat`) | BFF servlet at `/servoy_ai/*`: serves Angular app, proxies opencode API + SSE, injects project dir, readiness gate |
| `ServicesProvider` (`.tomcat`) | Registers the servlet instance mapped to `/servoy_ai/*` |

---

## Stack & module layout

- **Java bundles:** Eclipse PDE / OSGi, built with Tycho/Maven. MANIFEST.MF `Require-Bundle` /
  `Import-Package` govern dependencies (not pom `<dependency>` blocks). Target platform is
  `launch_target_aiplugin`.
- **Angular frontend:** `com.servoy.eclipse.opencode/webui/` — a **standalone Angular 22
  project**, NOT part of the Eclipse/JDT/OSGi world. Standalone components, **zoneless**
  change detection, **signals** everywhere, `ChangeDetectionStrategy.OnPush`, SCSS,
  `marked` + `DOMPurify` + `highlight.js`, **Vitest** for unit tests. `ng build` outputs to
  `../webui-dist` (not committed / not indexed). `baseHref` is `/servoy_ai/`; all API calls
  are relative (`rest_api/...`).

---

## Which tools to use (CRITICAL)

### Java bundles → Eclipse MCP tools, never generic file/git/shell

Always prioritize Eclipse-specific MCP/PDE tools so the Eclipse index, builder, and
classpath stay in sync:

- **Read:** `eclipse-ide_readProjectResource` (not the generic `read`)
- **Create / overwrite:** `eclipse-coder_createFile` / `eclipse-coder_replaceFileContent`
- **Edit:** `eclipse-coder_applyPatch` / `eclipse-coder_insertIntoFile` /
  `eclipse-coder_replaceString` / `eclipse-coder_deleteLinesInFile`
- **Search:** `eclipse-ide_fileSearch` / `eclipse-ide_fileSearchRegExp` /
  `eclipse-ide_findFiles` / `eclipse-ide_searchTypes` / `eclipse-ide_searchMethods`
- **Orientation:** `eclipse-ide_getWorkspaceOverview` / `eclipse-ide_getPackageSummary`
- **Git:** `eclipse-git_*` (not shell `git`)
- **Compile check:** `eclipse-ide_getCompilationErrors` + `eclipse-ide_executeQuickFix`
- **Refactor:** `eclipse-coder_refactorRenameJavaType` / `refactorRenamePackage` /
  `refactorMoveJavaType`; for method/field/var renames use `eclipse-ide_findReferences`
  first, then rename consistently.
- **Tests:** `eclipse-ide_runJUnitTests` / `eclipse-ide_runClassTests` /
  `eclipse-ide_runTestMethod` (plain JUnit) or `eclipse-pde_runJUnitPluginTests`
  (integration).

### Angular subtree (`webui/`) → NOT Eclipse tools

Eclipse JDT/PDE tools do **not** apply in `webui/`. Use the `angular-cli_*` MCP tools, the
generic `read`/`write`/`edit`/`grep`/`glob` file tools, and `npm`/`vitest`/`ng` via the
shell.

### Eclipse tools are Code Mode only

`eclipse-ide`, `eclipse-coder`, `eclipse-git`, `eclipse-pde`, `eclipse-context`,
`eclipse-runner`, `time` and `angular-cli` are reachable only inside an `execute` script, as
`tools["eclipse-ide"].getCompilationErrors({ ... })`. Only `memory_*` is a normal tool call.
Every `eclipse-ide_foo` name above means `tools["eclipse-ide"].foo` inside `execute`. Look up
an exact signature with `search("<toolName>")` instead of guessing. Compose edit + compile
check in one script and return only the filtered result. For a Java symbol question
("who calls / uses this") use `findReferences` / `getMethodCallHierarchy` before `grep`.


### Browser abstraction — never depend on Chromium directly

Never reference `com.equo.chromium.swt.*` or `org.eclipse.swt.browser.Browser` /
`BrowserFunction` from feature code, and never `instanceof`-check the concrete browser type
or call `IBrowser.getBrowserInstance()`. Always go through
`com.servoy.eclipse.ui.browser.IBrowser` (`setUrl`, `setText`, `execute`,
`addBrowserFunction`, …). If `IBrowser` lacks a capability, add it to the interface and to
both wrappers (`SwtBrowserWrapper` / `ChromiumWrapper`) in `com.servoy.eclipse.ui`.

---

## Testing

### Test bundles

- `com.servoy.eclipse.opencode.tests` - OSGi fragment of the opencode bundle, split into
  `...opencode.tests.plainjunit` (pure JUnit, no OSGi runtime; covers `McpConfigWriter`,
  `OpencodeFolderCreatorJob` helpers, `OpencodeIndexIgnorer` and the `.tomcat` BFF -
  `OpencodeChatServletTest`, `OpencodeChatServletGuardTest`, `ServicesProviderTest`) and
  `...opencode.tests.integrationpde` (needs the OSGi container, e.g. `SkillsZipExtractorTest`).
- `com.servoy.eclipse.developer.mcp.tests` — OSGi fragment of the developer.mcp bundle,
  source in `src/test/java/`. **Pure JUnit 5/6 (Jupiter).** Both plain unit tests and
  integration tests that need a running Eclipse workbench + Servoy App Server.
  `...plainjunit.auth.BearerTokenAuthenticationFilterTest` covers the SVY-21688 per-request
  `/dev_mcp` gate (loopback → `@servoy.com` internal bypass → `GENAI_API_KEY` entitlement →
  bearer token, all denies a bare 404 with no `WWW-Authenticate`/`Allow` header and no body),
  faking the internal-user decision via the `isServoyInternalUser()` seam and driving
  `GENAI_API_KEY` as a save/restored system property, over both `service()` and `doFilter()`.

### Jupiter conventions (developer.mcp.tests)

- **Always write new tests as Jupiter** (`org.junit.jupiter.api.*`). No JUnit 4 remains.
- Use `org.junit.jupiter.api.Assertions` directly (message-last argument order).
- `@DisplayName` ONLY on `@Test`/`@ParameterizedTest`/`@Nested` **methods**, never on the
  top-level class (class-level display names break Jenkins/Tycho-surefire grouping).
- No `public` modifier needed. No silent skips (`Assumptions`/`assumeXxx`) — fix setup or
  fail loudly. `@Test(expected=…)` → `assertThrows(...)`; `@Rule`/`ExternalResource` → a
  JUnit 5 extension via `@ExtendWith`.
- SWT dialog guard: integration tests extend `TestUtilitiesClassCopy` -> `DialogGuardBaseCopy`
  (`@ExtendWith(DialogGuardRuleCopy.class)`), inherited automatically. Tests that intentionally
  open a dialog push `TestDialogInterceptorCopy.expect(...)` first. The `*Copy` classes live in
  `...integrationpde.utils` and mirror servoy-eclipse's originals - keep them in sync.
- Integration tests use `TestUtilitiesClassCopy` helpers (`pumpEventsUntil`,
  `waitForWorkspaceBuildJobs`, `waitForTitaniumuildJobs`) — never raw `Thread.sleep`. Each
  class cleans workspace projects in `@BeforeAll deleteProjectsBeforeClass()`.

### Test class rules (same package, mocks, minimal reflection)

- Put test classes in the **same package as the class under test** so package-private members
  are reachable without reflection (fragment test bundles share the host classloader).
- Make production code testable: package-private constructor/factory for constructor injection
  of mocks, a package-private/`protected` seam for statics, or package-private visibility on the
  member. This small behaviour-preserving production change is **allowed and preferred**; do not
  ask first. Narrowest visibility only, never `public` just for a test.
- Reflection is the last resort and **never to set `final` fields**. Justify any remaining
  reflective helper in a comment.
- Prefer Mockito unit tests over `integrationpde`; many existing integration tests can be mocks.
- Fragment test modules must run plain surefire against the host's `target/classes`, not its
  signed jar (`classpathDependencyExcludes` + `additionalClasspathElements`), otherwise a test
  class in a production package fails with `signer information does not match`.
  See `tests/com.servoy.eclipse.opencode.tests/pom.xml`.

### Test selection (no suites)

There are no `@Suite`/`AllXxxTests` aggregator classes. Tests are selected by package: put a
new test under `...plainjunit` (headless, no OSGi) or `...integrationpde` (needs the workbench /
OSGi container) and it is picked up automatically by the pom includes and by running the
package or project from the IDE. Nothing needs to be registered anywhere.

### How Maven/Tycho runs tests

- `mvn verify` = headless default surefire; `mvn verify -Pintegration` = UI harness +
  Servoy app server.
- `pom.xml` already sets `<providerHint>junit6</providerHint>` (required — the target
  platform contributes JUnit 4, so auto-detection otherwise picks junit4 → "No tests found"
  for Jupiter) and package-based `<includes>` (`**/plainjunit/**`, `**/integrationpde/**`) - no `@Suite`.
- Headless unit run:
  `mvn -B clean verify -pl tests/com.servoy.eclipse.developer.mcp.tests -am "-Dtycho.localArtifacts=ignore" "-Dmaven.test.failure.ignore=true"`
- Integration: add `-Pintegration "-Dservoy_install=<path-to-parent-of-application_server>"`.
- If the target artifact isn't in `~/.m2`: first
  `mvn -B -pl launch_target_aiplugin install "-Dtycho.localArtifacts=ignore"`.

### Running integration tests in the IDE

- `eclipse-pde_runJUnitPluginTests` — target by `className` (single or comma-separated),
  `methodName`, `packageName`, or none. `launcherName` reuses a saved launch config's
  classpath/VM args/bundles while overriding the test target.
- Discover launchers with `eclipse-runner_listLaunchConfigurations`
  (`typeFilter="junit-plugin"`). On Windows use configs WITHOUT a `_mac` suffix.
- **Known limitation:** `launcherName` + comma-separated `className` runs only one class
  per launch. Run each class in a separate call when verifying multiple.
- These tests start Servoy Developer (~60–120 s+). The initial call likely times out; poll
  `eclipse-pde_getOperationStatus` with increasing waits and use `includeResults`
  (`summary`/`results`) to watch progress.

---

## After every code change (mandatory compile loop)

1. `eclipse-ide_getCompilationErrors()` to check build state.
2. If errors, review the returned quick-fix list.
3. Apply a safe quick fix via `eclipse-ide_executeQuickFix(markerId, proposalIndex)`.
4. Re-check until clean.

**Spotbugs:** treat the two highest severity levels as blocking; fix them in new/modified
code.

---

## Commit conventions

- Subject line **must end with ` [ai]`** for AI-generated/assisted commits.
- Include the Jira key when the commit relates to a case, e.g.
  `SERVOY-293 fix NPE in WAR export copyRequiredBundles [ai]`.
- **Pre-commit checklist:** zero compilation errors (`eclipse-ide_getCompilationErrors`),
  show the proposed message + staged file list to the user, and wait for explicit approval
  before committing. Never commit without confirmation.
- SDD commit body: bullet summary + `Co-Authored-By: opencode <noreply@opencode.ai>`.
- Stage feature code + spec + tests + modified pom.xml + AGENTS.md (if test docs added).
  Exclude `opencode.json`, `.opencode/`, unrelated files.

---

## Jira integration

Jira API instructions live in `JIRA.md` at the repository root (auth via the
`ATLASSIAN_AUTH_BASIC` env var; PowerShell `Invoke-RestMethod`, never `curl`; cloud-id from
`JIRA.md`). For create/update/link/comment operations you may also use the global
`servoy-jira` skill. Do not hardcode a Jira cloud-id.

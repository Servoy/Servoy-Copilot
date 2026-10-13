# Agent Guidelines for Servoy Copilot Codebase

Welcome, AI Agent! This repository contains the **Servoy AI Copilot** plugin for the Servoy Developer IDE. It is an Eclipse PDE (Plugin Development Environment) project built with Tycho/Maven. There are two **actively developed** bundles: `com.servoy.eclipse.developer.mcp` (the Eclipse IDE MCP server) and `com.servoy.eclipse.opencode` (the opencode AI wrapper and lifecycle manager). Several other bundles exist in the repository but are no longer actively developed; they are kept for reference only. To ensure safety, consistency, and proper integration with the Eclipse workspace environment, you must adhere strictly to the following developer and automation workflows.

---

## 1. Repository Projects Analysis

This Git repository contains the following core projects/plugins:

### 1. `com.servoy.eclipse.developer.mcp` ⬅ ACTIVE
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Eclipse IDE MCP server (AssistAI) — exposes IDE tools to AI agents.
- **Key Focus:** Implements the MCP server that runs inside the Eclipse JVM and exposes Eclipse IDE operations (file read/write, search, compilation, git, PDE tests) as MCP tools callable by external AI agents.
- **Crucial Detail:** Two-JVM architecture — this bundle runs inside Eclipse; the MCP client runs in the agent's JVM. Changes here affect what tools are available to all AI agents working in this workspace.

### 2. `com.servoy.eclipse.opencode` ⬅ ACTIVE
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Opencode AI wrapper — installs, configures, and manages the lifecycle of the [opencode](https://opencode.ai) CLI tool embedded in the Servoy Developer IDE, **and hosts a custom Angular chat UI** (`webui/`) that talks to the opencode HTTP + SSE API through a same-origin BFF servlet.
- **Key Focus:** Downloads and keeps the `opencode-ai` npm package up to date (`~1.15.x`), starts the opencode HTTP server on a free port, serves the bundled Angular app in the embedded browser view, and merges MCP endpoint contributions from other bundles into `opencode.json`.
- **Custom chat UI, not opencode's own web:** The embedded browser no longer points at opencode's built-in web UI. It loads the Angular app served by `OpencodeChatServlet` at `/servoy_ai/`, which proxies opencode's API under `/servoy_ai/rest_api/**` (see the Angular frontend section below). The BFF injects the active project directory server-side and centralises the readiness gate, so the front-end never carries directory state and `EventSource` (SSE) works same-origin.
- **Crucial Detail:** Requires two system properties to activate: `GENAI_API_KEY` and `SERVOY_SKILLS_ZIP`. Without them the setup job skips entirely and the view shows a "not configured" page.
- **Key classes:**

  | Class | Role |
  |---|---|
  | `Activator` | Plugin lifecycle — schedules setup, tracks server-ready state, shuts down process tree on stop |
  | `OpencodeFolderCreatorJob` | One-shot Job: installs/updates opencode via npm, extracts skills zip, merges MCP config, starts server |
  | `RunOpencodeCommand` | Long-running Job: finds free port (from 4096), launches `npm exec -- opencode serve`, watchdog-polls HTTP until ready |
  | `OpencodeServerState` | Latch + port holder used to signal and wait for server readiness |
  | `OpenCodeView` | Eclipse ViewPart hosting the embedded browser; state machine handles login / config / project activation |
  | `OpencodePerspective` | Perspective factory for the "Servoy AI" layout |
  | `McpConfigWriter` | Collects `IMcpEndpointProvider` contributions via extension point and merges them into `opencode.json` using Jackson; matches on URL (not server name) to avoid duplicates |
  | `IMcpEndpointProvider` | Extension point interface — implementors return MCP endpoint URLs and optional auth token |
  | `ProviderConfigWriter` | Writes `GENAI_API_KEY` env var and ensures `$schema` in `opencode.json` |
  | `SkillsZipExtractor` | Extracts `SERVOY_SKILLS_ZIP` into `~/.servoy/opencode/`; updates `AGENTS.MD` in project root with runtime Servoy/Postgres versions and database names |
  | `OpenCodeUtil` | Static helpers: resolves active project path, walks up to git root |
  | `OpencodeChatServlet` (`.tomcat`) | Backend-for-frontend (BFF) servlet mounted at `/servoy_ai/*` on the embedded Tomcat. Serves the built Angular app from `/webui-dist/` (with SPA fallback + hashed-asset caching) and proxies opencode's HTTP + SSE API under `/servoy_ai/rest_api/**`, injecting the project directory and holding requests behind a readiness gate while opencode starts |
  | `ServicesProvider` (`.tomcat`) | Registers the servlet instance mapped to `/servoy_ai/*` on the Servoy web server |

- **State directory:** `{eclipse-state}/opencode/` — contains `package.json`, `node_modules/`, `package_copy.json` (version sentinel), `.fullygenerated` (install marker).
- **Config directory:** `~/.servoy/opencode/` — contains `opencode.json` (MCP + provider config) and extracted skills zip content.
- **Update strategy:** On every startup, if the bundle's `package.json` changed → full clean `npm install`; otherwise → `npm update opencode-ai` to pick up the latest `1.15.x` patch. Both steps are non-fatal.
- **Test bundle:** `com.servoy.eclipse.opencode.tests` — fragment of the opencode bundle, plain JUnit (no OSGi runtime required). Tests `McpConfigWriter` and `OpencodeFolderCreatorJob` helpers, plus the `com.servoy.eclipse.opencode.tomcat` BFF: `OpencodeChatServletTest` (route classification, upstream-path mapping, directory injection incl. spoof-prevention, static-asset path-traversal defense, hashed-asset detection), `OpencodeChatServletGuardTest` (readiness-gate / no-active-solution / port<=0 guards via dynamic-proxy request/response fakes), and `ServicesProviderTest` (`getServletInstances("")` returns one `ServletInstance` mapped to `/servoy_ai/*`; non-root contexts register nothing).

#### Angular chat frontend — `com.servoy.eclipse.opencode/webui/`

The chat UI is a standalone **Angular application** living inside the opencode bundle. It is **not** part of the Eclipse/JDT/OSGi world — treat it as an ordinary Angular project.

- **Stack:** Angular 22, standalone components, **zoneless** change detection (`provideZonelessChangeDetection`), **signals** everywhere, `ChangeDetectionStrategy.OnPush`, SCSS, `marked` + `DOMPurify` + `highlight.js` for markdown, **Vitest** for unit tests.
- **Build output:** `ng build` writes to `../webui-dist` (the folder `OpencodeChatServlet` serves). `baseHref` is `/servoy_ai/`; all API calls are relative (`rest_api/...`) so the app is location-independent. `webui-dist`, `.angular`, and `node_modules` are **not** committed / not indexed.
- **Key structure (`webui/src/app/`):**
  - `services/chat-store.service.ts` — central signal store: active session, messages, streaming state, session tree (with nested subagent children), live updates from the `/event` bus, and `exportSession` (JSON export).
  - `services/opencode-api.service.ts` — typed wrappers over `rest_api/**`.
  - `services/event-stream.service.ts` — `EventSource` on `rest_api/event`, parses opencode bus events.
  - `services/status.service.ts` — health / mcp / provider status endpoints.
  - `services/part-utils.ts` — decides which message parts render and how (text/reasoning/tool), friendly tool names + argument subtitles, filters synthetic `[system: ...]` / `<system-reminder>` parts.
  - `services/session-export.ts` — serializes a session to the exact `{ info, messages: [{ info, parts }] }` shape produced by `opencode export`.
  - `services/theme.service.ts` — reads the `darkmode` query param and applies the light/dark palette before first render.
  - `components/` — `message-list`, `message-item` (flowing transcript), `session-list` (sidebar tree + context menu), `composer`, `status-panel`, `markdown-renderer`.
- **Theme:** `OpenCodeView.resolveChatUiUrl()` appends `?darkmode=<true|false>` from the IDE theme; the app mirrors it. No light-to-dark flash because the theme is applied in `main.ts` before bootstrap.
- **Tools to use in this subtree:** the Eclipse JDT/PDE MCP tools are Java-only and do **not** apply here. For `webui/` use the **Angular CLI MCP tools** (`angular-cli_*`), the generic `read`/`write`/`edit`/`grep`/`glob` file tools, and `npm`/`vitest` via `bash`.

#### Quick debugging of the Angular frontend

Fastest inner loop when iterating on the chat UI:

1. Start a **development watch build** into the served folder (unminified, sourcemaps on, **unhashed** `main.js` so reloads always pick up changes and the browser can show real sources):
   ```
   ng build --watch --configuration development
   ```
   (run from `bundles/com.servoy.eclipse.opencode/webui`; output lands in `../webui-dist`). Each save rebuilds in ~1 s; the watch log shows compile errors without a full `npm run build`.
2. Make sure **Servoy Developer is running** and the **Servoy AI view/part is open** (this starts the embedded Tomcat + opencode server on their ports).
3. In OpenChamber, open the browser to the live app:
   ```
   http://127.0.0.1:8183/servoy_ai/
   ```
   (Port `8183` is the Servoy web server / Tomcat port shown for that instance — confirm the actual port if it differs.) Reload after each rebuild to see changes; use `browser.snapshot` / `browser.inspect` to read the DOM and computed styles when debugging layout or state.
4. Run unit tests with `npm test` (Vitest) from the `webui/` folder.
5. **Before committing**, replace the dev watch output with a clean production build: stop the watch and run `npm run build` (production, hashed, optimized) so `webui-dist` isn't left as a dev bundle. Note `webui-dist` is not committed, but the production build is what ships in the packaged plugin.

#### Debug view / SSE timing overlay (`?debug_view=true`)

The chat UI ships an opt-in, on-screen trace overlay for diagnosing live event
timing — especially the SSE bus (`rest_api/event`) → signal store → sidebar
render path. It is **off by default** and adds no runtime cost unless the flag
is set, so it can safely stay in the production build.

- **Enable it** by appending `?debug_view=true` (or `=1`) to the app URL, e.g.
  `http://127.0.0.1:8183/servoy_ai/?debug_view=true`. Anything else (or absent)
  leaves it fully disabled.
- **Why it exists:** the app is zoneless, and the raw `EventSource` callback
  fires *outside* Angular. Signal writes made there only render when a tick
  happens to run, which made bug symptoms (e.g. a lazily-generated session
  title appearing many seconds late) hard to reason about. The overlay makes the
  exact arrival/dispatch/render timeline visible without a debugger — and,
  crucially, readable from a DOM snapshot when using the OpenChamber browser
  tools, which have no console access.
- **How it works:**
  - `services/debug-log.ts` exposes a `debugEnabled` boolean (read once from the
    URL) and a `dbg(tag, msg)` function backed by a small ring buffer. When the
    flag is off, `dbg` is a cheap no-op and nothing is buffered or logged.
  - Traced points (all guarded by `debugEnabled`): `EventStreamService` logs
    every bus event's raw arrival time, whether it arrived inside the Angular
    zone, and the dispatch delta; `ChatStore.applySessionUpdate` logs when a
    `session.updated` reaches the store and the title it carries; the
    `SessionListComponent` `effect` logs whenever the rendered session tree
    actually changes.
  - `components/debug-overlay.component.ts` renders the ring buffer as a fixed
    bottom-right panel (`[data-debug-overlay]`), polling every 250 ms. It is
    only added to the DOM when `debugEnabled` is true.
- **Reading it with the browser tools:** `browser.snapshot` with selector
  `[data-debug-overlay]` returns the visible log lines (timestamped, tagged
  `[sse]` / `[store]` / `[sidebar]`). Poll it repeatedly (e.g. every ~0.5–2 s)
  after sending a prompt to catch the exact moment a title/idle event lands —
  the browser tooling exposes no console, so this overlay is the way to read the
  timeline.
- **Adding a trace:** import `dbg` from `services/debug-log` and call
  `dbg('<tag>', '<message>')`. Keep new heavy work behind `if (debugEnabled)` so
  the disabled path stays free.

---

> The following bundles are **no longer actively developed**. They are kept in the repository for reference only. Do not make changes to them unless explicitly instructed.

### 3. `com.servoy.eclipse.servoypilot` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Main plugin — AI assistant UI, tools, chat, and completion.
- **Key Focus:** Entry point for all AI-assisted developer features. Integrates the chat UI, code completion hooks, and orchestrates calls to the LLM and knowledge base bundles.

### 4. `com.servoy.eclipse.servoypilot.langchain4j` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** LangChain4j wrapper bundle — AI/LLM integration library.
- **Key Focus:** Wraps the LangChain4j library for use inside OSGi. Provides the LLM client abstraction used by the main plugin.
- **Crucial Detail:** Acts as a library bundle. Do not add UI or Eclipse-specific logic here.

### 5. `com.servoy.eclipse.servoypilot.knowledgebase` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Knowledge base indexing, ONNX embeddings, and RAG (Retrieval-Augmented Generation).
- **Key Focus:** Indexes Servoy project sources and documentation into a vector store using ONNX-based embeddings. Provides retrieval APIs consumed by the main plugin.
- **Crucial Detail:** Heavy dependency on ONNX runtime and PDFBox/Tika for document parsing.

### 6. `com.servoy.eclipse.servoypilot.assistenttests` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Servoy Developer tools for AI-assisted test generation and execution.

> The following are **active** build and distribution artifacts.

### 7. `com.servoy.eclipse.servoypilot.feature`
- **Type:** Eclipse Feature (`eclipse-feature`)
- **Main Role:** Feature packaging — bundles all plugins and platform-specific fragments for distribution.

### 8. `repository.site_aiplugin`
- **Type:** Eclipse Repository (`eclipse-repository`)
- **Main Role:** P2 update site for distribution of the Servoy Copilot feature.

### 9. `launch_target_aiplugin`
- **Type:** Target Definition
- **Main Role:** Target platform definition for building and running the plugin.
- **Crucial Detail:** Resolves against `https://build.servoy.com/latest/servoy_release/update_site/` plus Maven Central and the Servoy Maven repo. Dependencies already provided by the Servoy target must NOT be duplicated here.

---

## 1b. Test Projects & How to Run Tests

Each test project (e.g `com.servoy.eclipse.opencode.tests`, `com.servoy.eclipse.developer.mcp.tests`) has it's tests split between plain JUnit tests and plugin PDE integration JUnit tests.

- if you want to run **all the tests** in a project, use the plugin PDE integration JUnit test way of running (as the plain tests should run fine with that as well) as specified below and give only the project as a target parameter to the MCP tool.
- **suite classes are not used**: there are no `@Suite`/`AllXxxTests` aggregator classes in these projects anymore — so that they don't go out of date & running a package or the whole project directly executes each test class exactly once
- all tests use **JUnit 5/6 (Jupiter) APIs**
- **Package placement is based on what the code under test needs, not what the test method itself calls directly.** `SkillsZipExtractorTest` lives in `integrationpde` even though its own test methods don't touch OSGi APIs — `SkillsZipExtractor`/`OpenCodeUtil` transitively call `com.servoy.eclipse.opencode.Activator.getInstance()`, which returns `null` outside a running OSGi container, so the test only passes under the PDE/workbench runner. When deciding where a test belongs, check what the code it exercises needs, not just the test body.
- **Maven wiring:** has the same `plainjunit`/`integrationpde` tycho-surefire split and `-Pintegration` profile as `com.servoy.eclipse.developer.mcp.tests` (see "Maven wiring" below), and is included in the Jenkinsfile's `-Pintegration -pl` list.

**Polling:** Tests could take a long time to run. If the tool times out saying the tests are still running, poll with `eclipse-pde_getOperationStatus` using increasing wait times (e.g. 5 s, 10 s, 20 s, you decide) to reduce token usage between checks. While an operation is still running, `getOperationStatus` also surfaces intermediate `summary` and `results` — you can request those to see if everything is ok using `includeResults` to see how many tests have passed/failed so far, without waiting for the whole run. You can also check console output and the progress of what was already tested.

### Plain Junit tests located in a package with **`.plainjunit`** subfix and it's subpackages

These tests use no live Eclipse workspace or OSGi container — pure Java, reflection, and mocking.

**Runner:** Plain JUnit tool (`eclipse-ide_runJUnitTests`). When you want to run only plain JUnit tests, it is much faster to use the plain JUnit runner.
**Target to test** give it the `projectName` and the `packageName` or `className` or ... that you want to test, according to the available parameters. When running all tests in a package, make sure you pick the correct parent package to run (don't give it packages like `com.servoy.eclipse.developer.mcp.integrationpde.utils` if they have no tests, just utility methods). For example if you want to run all plain junit tests from project `com.servoy.eclipse.developer.mcp.tests` then give the project and package `com.servoy.eclipse.developer.mcp.plainjunit` as parameters.

#### Troubleshooting: JUnit 6 `NoSuchMethodError` (e.g. `Namespace.getParts()`)

If a JUnit 6 (Jupiter) test fails at runtime with:
```
java.lang.NoSuchMethodError: 'java.util.List org.junit.jupiter.api.extension.ExtensionContext$Namespace.getParts()'
```

**Root cause:** The `org.eclipse.dltk.javascript.rhino` workspace project (or others) used to bundle an older `junit-jupiter-api-5.x.jar` in its `test-libs/` folder. When PDE builds the flat classpath for a plain JUnit launch, it includes all workspace project dependencies — including rhino's test-libs. The older 5.x API jar appears earlier on the classpath than the target platform's 6.x jar, causing version conflicts.
**Fix:** Open `org.eclipse.dltk.javascript.rhino` → Java Build Path → Libraries tab → remove or uncheck the `test-libs/junit-jupiter-api-*.jar`, `test-libs/junit-4.*.jar`, and `test-libs/hamcrest-*.jar` entries. Alternatively, remove the `test-libs` folder from rhino's `.classpath` entirely. The test sources can also be removed from the Source tab if rhino tests are not being developed.

### Plugin PDE integration JUnit tests are located in a package with **`.integrationpde`** subfix and it's subpackages

These tests require a full Eclipse workbench (Servoy Developer, that also includes the Servoy App Server). They use `ResourcesPlugin`, `Display`, `ServoyModelManager`, etc.

**Runner:** Plugin PDE JUnit tool (`eclipse-pde_runJUnitPluginTests`).

**Parameters:**
- **Use the existing launch configuration as `launcherName`: `/com.servoy.eclipse.developer.mcp.tests/PDEIntegrationTestsBaseCopy.launch`** (or a copy of it from the same place that targets the correct OS you are running); that should start-up correctly a test Servoy Developer. It takes a while to start, but then it should have all that is needed for plugin PDE intgration JUnit tests operational. You can use `eclipse-runner_listLaunchConfigurations` (with `typeFilter="junit-plugin"`) to discover the available saved launch configurations. On Windows, use the configuration **without** a `_mac` suffix.
- **Target to test** give it just the `projectName` to run all tests in that project (both plainjunit and integrationpde), or the `projectName` and the `packageName` or `className` or ... that you want to test, according to the available parameters. When running all tests in a package, make sure you pick the correct parent package to run. For example if you want to run all integrationpde junit tests from project `com.servoy.eclipse.developer.mcp.tests` then give the project and package `com.servoy.eclipse.developer.mcp.integrationpde` as parameters. When you only need to verify a specific class after a code change, pass `launcherName` + `projectName` + `className`
- There are other parameters that can be used to tweak the launcher if needed.

**Polling:** these tests start the Servoy Developer (integrationpde ones) & typically take 60–120 s or more due to a long time starting it up - and the initial MCP call will likely time out before they finish. So use the polling mechanism described above.

#### Cross-repo shared launcher: `PDEIntegrationTestsBaseCopy`

The old `AllDeveloperMcpIntegrationTests.launch` / `AllDeveloperMcpIntegrationTests_mac.launch` files have been **deleted** from this project, along with the `AllDeveloperMcpTests`, `AllDeveloperMcpIntegrationTests`, `AllWpmTests`, and `AllDeveloperMcpJupiterUnitTests` suite classes themselves — this project no longer has any `@Suite`/`AllXxxTests` aggregator classes.

The canonical shared JUnit Plug-in Test launch config for full-workbench PDE integration-test runs lives in **servoy-eclipse**, at `com.servoy.eclipse.core.tests/PDEIntegrationTestsBase.launch`. This project keeps its **own copy**, `com.servoy.eclipse.developer.mcp.tests/PDEIntegrationTestsBaseCopy.launch` — same shape (full `selected_workspace_bundles`/`selected_target_bundles` sets, VM args), but its `-Dproperty-file` points at this repo's own `testresources/servoy.properties` instead of servoy-eclipse's. **Keep the two launch files in sync** when you change bundle selections, VM args, or structure — the target project/package and the property-file path are the only parts expected to differ.

### Maven/Tycho wiring for the `plainjunit`/`integrationpde` split

`com.servoy.eclipse.developer.mcp.tests` and `com.servoy.eclipse.opencode.tests` both have:
- A default-build `tycho-surefire-plugin` with `<includes><include>**/plainjunit/**/*Test.java</include></includes>` — what `mvn clean verify` runs. No OSGi/Tomcat/app-server needed.
- An `integration` Maven profile (`-Pintegration`) with `<includes><include>**/integrationpde/**/*Test.java</include></includes>`, `useUIHarness=true`, a real `product`/`application` (full workbench), and (for `developer.mcp.tests`, which needs a live app server) a `maven-antrun-plugin` step that extracts a real Servoy install from `${servoy.install.tarball.for.appserver}` before the tests run.
- The Jenkinsfile's "Integration Tests" stage runs `mvn verify -Pintegration -pl tests/com.servoy.eclipse.developer.mcp.tests,tests/com.servoy.eclipse.opencode.tests -am`.

Run plain tests: `mvn clean verify -pl tests/<project>`.
Run integration tests: `mvn verify -Pintegration -pl tests/<project> -am`.
But when developing, use the eclipse MCP tools for running tests as described above instead.

### No cross-repo test-project dependency; "Copy"-suffixed duplicated utility classes

This project's `com.servoy.eclipse.developer.mcp.tests` was meant to depend on servoy-eclipse's `com.servoy.eclipse.core.tests`/`com.servoy.eclipse.ngclient.ui.tests` via OSGi `Require-Bundle`, to reuse the shared PDE test-infra base classes (`TestUtilitiesClass`, `ServoyRunnerTestBase`, `DialogGuardRule`, `AbstractTitaniumIntegrationTest`, etc.) instead of duplicating them. **That cross-repo dependency idea has been abandoned for now.** It only ever resolved in a live combined Eclipse workspace (both repos open together); this repo's actual Jenkins/Tycho build has no path to those bundles, since servoy-eclipse's `.tests` projects are correctly excluded from `com.servoy.eclipse.feature`'s p2 update site, and standing up a proper publish/consume pipeline for test-only bundles was judged not worth the extra Jenkins infrastructure.

Instead, this project keeps its own duplicate of each shared base class, under `com.servoy.eclipse.developer.mcp.integrationpde.utils`, with a **`Copy` suffix** on every class name:

| Copy (here) | Original (servoy-eclipse) |
|---|---|
| `TestUtilitiesClassCopy` | `com.servoy.eclipse.core.tests.integration.utils.TestUtilitiesClass` |
| `ServoyRunnerTestBaseCopy` | `com.servoy.eclipse.core.tests.integration.utils.ServoyRunnerTestBase` |
| `DialogGuardRuleCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogGuardRule` |
| `DialogGuardBaseCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogGuardBase` |
| `DialogExpectationCopy` | `com.servoy.eclipse.core.tests.integration.utils.DialogExpectation` |
| `TestDialogInterceptorCopy` | `com.servoy.eclipse.core.tests.integration.utils.TestDialogInterceptor` |
| `AbstractTitaniumIntegrationTestCopy` | `com.servoy.eclipse.ngclient.ui.tests.integrationpde.utils.AbstractTitaniumIntegrationTest` |

The `Copy` suffix is intentional and permanent — it's not a TODO to remove, it's how both repos' agents recognize "this class must be kept behaviorally identical to its non-`Copy` counterpart in the other repo." **Whenever you change one of these classes, make the matching edit to its counterpart in servoy-eclipse** (same method bodies, same lifecycle behavior) — each class's Javadoc cross-references its counterpart and says so explicitly.

### Convention: where shared PDE integration-test infrastructure lives

Within servoy-eclipse, shared helpers (event-pump utilities, app-server wait, dialog guard, solution-activation base classes, etc.) live in the `.tests` plugin of whichever core plugin they are conceptually closest to (e.g. solution-activation/dialog-guard helpers → `com.servoy.eclipse.core.tests`; titanium-build-specific helpers → `com.servoy.eclipse.ngclient.ui.tests`), and `.tests` projects **within that repo** depend on them via `Require-Bundle`. That convention does not cross the repo boundary — see "No cross-repo test-project dependency" above for why this repo keeps `Copy`-suffixed duplicates instead. See servoy-eclipse's `AGENTS.md` ("Integration test infrastructure" section) for the full fragment-vs-plain-plugin rationale before adding new shared infra there.

### Conventions for new Jupiter tests

- `@DisplayName` ONLY on `@Test`/`@ParameterizedTest`/`@Nested` **methods**, never on the top-level test class (class-level display names break Jenkins/Tycho-surefire grouping — tests land in `(root)`).
- No `public` modifier needed on classes/methods.
- No silent skips (`Assumptions`/`assumeXxx`) — fix setup or fail loudly.
- `@Test(expected=…)` → `assertThrows(...)`; `@Rule`/`ExternalResource` → a JUnit 5 extension registered with `@ExtendWith`.

### SWT dialog guard (JUnit 5 extension)

`DialogGuardRule` is a JUnit 5 extension (`BeforeEachCallback`/`AfterEachCallback`) that fails a test if an unexpected SWT dialog appears (instead of hanging). It is applied via `@ExtendWith(DialogGuardRule.class)` on `DialogGuardBase`; `TestUtilitiesClass` extends `DialogGuardBase`, so every integration test inherits the guard automatically (annotations are inherited by subclasses). Tests that intentionally open a dialog push a `DialogExpectation` via `TestDialogInterceptor.expect(...)` first.

#### Troubleshooting

> **Spill-file format coverage (SVY-21577):** `FormLayoutToolIntegrationTest`'s `envelopeOf` helper, when a `getFormLayout` result spills to a temp file, now asserts the spilled file is **pretty-printed** (multiple physical lines + newline-anchored indentation, via `assertSpillIsPrettyPrinted`) and that the inline `warning` carries the three required points — absolute path, pass unchanged (no `../`), pretty-printed/greppable (via `assertSpillWarning`). These fire only on a real spill, so they catch a revert of the pretty-printer without being green-for-green. The class is PDE/Servoy integration and runs with `eclipse-pde_runJUnitPluginTests` (or `mvn verify -Pintegration`); it is still gated by the `/formtemplate` render prerequisite described in its Javadoc.

---

## 1c. Resolving solution objects — ALWAYS use the flattened solution

When a tool (or any feature code) needs to look up a **root object of the active solution** — a
form, relation, valuelist, menu, media, scope/global, etc. — it MUST resolve it against the
active project's **editing flattened solution**, NOT the active solution's own editing solution.
A Servoy solution is almost always a main solution plus **modules**, and most of a project's
forms/relations/valuelists live in those modules. Looking up only in the active solution's own
objects wrongly reports things as "not found" whenever they live in a module — and the Servoy
runtime, the designer, and the `/formtemplate` render route all operate on the flattened
solution, so a tool that doesn't will be inconsistent with everything else.

**Do this:**
```java
ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
FlattenedSolution fs = activeProject.getEditingFlattenedSolution(); // includes all modules
Form form = fs.getForm(formName);          // and fs.getRelation(...), fs.getValueList(...), fs.getMedia(...), etc.
```

**Not this:**
```java
activeProject.getEditingSolution().getForm(formName);   // active solution ONLY — misses module objects
activeProject.getSolution().getForm(formName);          // same problem, and not the editing copy
```

Notes:
- `ServoyProject.getEditingFlattenedSolution()` (no-arg) is the direct call — do not route back
  through `ServoyModelManager...getServoyModel().getEditingFlattenedSolution(editingSolution)`
  when you already hold the `ServoyProject`.
- Use the editing/flattened variants (not the runtime `getFlattenedSolution()`), since developer
  tools inspect the in-workspace editing state.
- The read-only lookup helpers on `FlattenedSolution` (`getForm`, `getRelation`, `getValueList`,
  `getMedia`, `getScriptCalculation`, iterators, etc.) are the right entry points.

---

## 2. Prioritize Eclipse MCP Tools Over Standard Tools

Since this workspace is a complex, multi-project Eclipse environment, **always prioritize Eclipse-specific MCP/PDE tools** over standard, general-purpose command-line or filesystem tools. This ensures that the Eclipse index, builder, and classpath are kept in sync.

> **Exception — the Angular frontend (`com.servoy.eclipse.opencode/webui/`):** this subtree is a standalone Angular project, not Java/JDT/OSGi. The Eclipse tools below do **not** apply there. Use the `angular-cli_*` MCP tools, the generic `read`/`write`/`edit`/`grep`/`glob` file tools, and `npm`/`vitest`/`ng` via `bash` instead. Everything in this section is about the Java bundles.

> **Writing an MCP tool that returns an image (not just text):** `@Tool` methods normally return a `String`, which `McpServerFactory` wraps in a single `TextContent`. To also attach an image, return an `com.servoy.eclipse.developer.mcp.McpToolResult` (`{ text, imageData(base64), imageMimeType }`, via `McpToolResult.withImage(...)`) — the factory expands it into a `TextContent` **plus** an `ImageContent`. Any other return type keeps the original single-`TextContent` path, so this is backward-compatible. The `servoy-form_getFormLayout` tool uses this to attach a rendered-form screenshot (captured via `IBrowser.captureScreenshot()` — which, unlike `IBrowser.evaluate()`, must run **off** the SWT display thread). Whether the model receives the image depends on the MCP client forwarding `ImageContent`; the custom Angular chat UI does not yet render image tool-parts inline.

### MCP servers: direct vs. Code Mode (dual registration)

This project's `opencode.json` registers a few high-traffic MCP servers **twice**, under two names that point at the same endpoint, so both call styles are available at once (this relies on OpenCode **V2** Code Mode, where the per-server `codemode` flag partitions tools into a *direct* list and a *Code Mode* catalog):

| Concept | Direct name (`codemode: false`) | Code Mode name (default) |
|---|---|---|
| Eclipse coder | `eclipse-coder` | `eclipse-coder_codemode` |
| Eclipse IDE | `eclipse-ide` | `eclipse-ide_codemode` |
| Eclipse git | `eclipse-git` | `eclipse-git_codemode` |
| Eclipse PDE | `eclipse-pde` | `eclipse-pde_codemode` |
| Eclipse context | `eclipse-context` | `eclipse-context_codemode` |
| Memory | `memory` | `memory_codemode` |

- **Direct names** (`eclipse-coder_*`, `eclipse-ide_*`, `eclipse-git_*`, `eclipse-pde_*`, `eclipse-context_*`, `memory_*`) are callable as ordinary tool calls, one per turn, with no script. Use these for a **single** action (read one file, apply one patch, one compile check, one git status/commit, poll one long-running operation, store one thought).
- **`_codemode` names** live in the Code Mode catalog and are only reachable from inside an `execute` script as `tools["eclipse-coder_codemode"].*`, `tools["eclipse-git_codemode"].*`, `tools.memory_codemode.*`, etc. Use these when you want to **compose several MCP calls in one script**.
- A tool lives on exactly one side per name — the same registration is never in both the direct list and the Code Mode catalog. That is why the server is registered twice.
- OpenCode does **not** auto-route between them; the agent picks. Prefer the direct name for one-off actions and the `_codemode` name only when scripting multiple calls together.
- **`eclipse-runner`** is registered **once, Code Mode only** (debug flows compose naturally in scripts): call it inside `execute` as `tools["eclipse-runner"].*`. `time` and `angular-cli` are likewise Code Mode only.
- Requires an OpenCode build with V2 Code Mode. On a build without it, the `codemode` flag is ignored, so the tool names still resolve but the direct/script split may not apply.

When this section names an `eclipse-coder_*` / `eclipse-ide_*` / `eclipse-git_*` / `eclipse-pde_*` / `eclipse-context_*` tool, use the direct name for a single call, or the matching `_codemode` namespace inside `execute` when batching.

- **File Reading:** Use `eclipse-ide_readProjectResource` instead of the generic `read` tool.
- **File Writing & Creating:** Use `eclipse-coder_createFile` or `eclipse-coder_replaceFileContent` instead of the generic `write` tool.
- **File Editing:** Use `eclipse-coder_applyPatch`, `eclipse-coder_insertIntoFile`, `eclipse-coder_replaceString`, or `eclipse-coder_deleteLinesInFile` instead of the generic `edit` tool.
- **File / Class Searching:** Use `eclipse-ide_fileSearch`, `eclipse-ide_fileSearchRegExp`, or `eclipse-ide_findFiles` instead of generic `grep` or `glob`.
- **Git Operations:** Use `eclipse-git_*` tools instead of standard shell `git` commands in `bash`.
- **Testing:** Prefer `eclipse-ide_runAllTests`, `eclipse-ide_runClassTests`, `eclipse-ide_runTestMethod`, or `eclipse-pde_runJUnitPluginTests` over generic shell test commands.

### Refactoring
- **Use `eclipse-coder_refactorRenameJavaType`** to rename classes, interfaces, enums, or records — this updates all references across the workspace.
- **Use `eclipse-coder_refactorRenamePackage`** to rename packages — updates all package declarations and references.
- **Use `eclipse-coder_refactorMoveJavaType`** to move types between packages.
- **For method, field, and variable renames:** use `eclipse-ide_findReferences` first to find all usages, then apply the rename consistently. Prefer Eclipse refactor tools over manual find-and-replace to ensure all references are updated correctly.

### Navigation & Discovery Tools

For quick codebase orientation and type/method lookup, use the JDT-powered search tools:

- **`eclipse-ide_searchTypes`** — Fuzzy type search via JDT SearchEngine. Supports wildcards (`*Payment*`), CamelCase (`PS` → `PaymentService`), prefix, and package-qualified patterns. Equivalent to Eclipse's Open Type (Ctrl+Shift+T). Use this instead of grep/glob when looking for a class by partial name.
- **`eclipse-ide_searchMethods`** — Method name search with the same pattern support, plus optional declaring type filter. Use when you need to find where a method is defined without knowing the full class name.
- **`eclipse-ide_getPackageSummary`** — Returns each type's name, kind, Javadoc first sentence, method/field counts, and interfaces for a package — a table-of-contents in one call. Use to quickly understand what a package contains.
- **`eclipse-ide_getWorkspaceOverview`** — High-level architectural map of projects → packages → type names for immediate orientation. Use as the first step when exploring an unfamiliar part of the codebase.

### Browser Abstraction — never depend on Chromium directly

The embedded browser view supports two backends (SWT `Browser` and Equo Chromium). **Never** reference `com.equo.chromium.swt.*` (or `org.eclipse.swt.browser.Browser`/`BrowserFunction`) directly from feature code, and never do `instanceof` checks against the concrete browser type or call `IBrowser.getBrowserInstance()` to reach the underlying object.

- **Always** go through the `com.servoy.eclipse.ui.browser.IBrowser` interface (`setUrl`, `setText`, `execute`, `addBrowserFunction`, etc.).
- To call back into Java from injected JavaScript, use `IBrowser.addBrowserFunction(String name, IBrowserFunction function)` — the SWT/Chromium `BrowserFunction` is created inside the backend-specific wrapper (`SwtBrowserWrapper` / `ChromiumWrapper`), so callers stay backend-agnostic.
- If `IBrowser` is missing a capability you need, **add it to the interface** (and to both wrapper implementations in `com.servoy.eclipse.ui`) rather than reaching around the abstraction. This keeps the hard Chromium dependency confined to `com.servoy.eclipse.ui` and out of the opencode bundle's manifest.

---

## 3. Commit Message Convention `[ai]`

To maintain clarity and transparency about the origin of codebase changes, any Git commit consisting primarily of AI-generated or AI-assisted changes must follow this rule:
- **The commit subject line must end with ` [ai]`** (case-insensitive, space followed by bracketed `ai`). Examples: `Fix NullPointerException during client initialization [ai]` or `Implement support for modern TLS protocols in server connection [ai]`
- **Commit messages for cases:** When a commit is related to a Jira case, the case number (e.g. `SVY-123`, `SVYX-456`, `SERVOY-293`) must be included in the commit subject line. Example: `SERVOY-293 fix NPE in WAR export copyRequiredBundles [ai]`

---

## 4. Post-Modification Compilation & Quick-Fix Loop

After making any code modifications or creating files using the Eclipse MCP tools, you must execute a self-verification compile loop:

1. **Check for errors:** Call `eclipse-ide_getCompilationErrors()` immediately to check the build state.
2. **Review quick fixes:** If any compilation errors are introduced or identified, look at the returned quick fixes list.
3. **Apply quick fixes:** If a quick fix is applicable and safe, immediately apply it using `eclipse-ide_executeQuickFix` by passing the corresponding `markerId` and `proposalIndex`.
4. **Re-check:** Verify compilation again to ensure the workspace is clean.

---

## 5. Spotbugs Error Resolution

Spotbugs is used to find bugs in Java code. You must pay special attention to Spotbugs issues:
- **Identify Spotbugs Errors:** Spotbugs errors of the **two highest severity levels** are treated as blocking errors.
- **Proactive Fixing:** Always try to fix these Spotbugs errors in any new or modified code to keep the codebase robust and clean.

---

## 6. Pre-Commit Checklist

Before creating any Git commit, the following steps are **mandatory**:

1. **Check compilation errors:** Call `eclipse-ide_getCompilationErrors()` and ensure there are ZERO errors. Do NOT commit with compilation errors.
2. **Show the commit to the user:** Always present the proposed commit message and list of staged files to the user BEFORE committing. Wait for explicit user approval ("go", "yes", "commit") before executing the commit.
3. **Never commit without user confirmation.** Even if the user said "commit" earlier in the conversation, always show what will be committed first.

---

## Jira

For anything Jira — reading, creating, updating, commenting on, linking, searching (JQL),
assigning or transitioning issues — load the **`servoy-jira`** skill and follow its
instructions. It holds the connection details (base URL, `ATLASSIAN_AUTH_BASIC` auth), the
per-OS command recipes and the ADF templates. Always load the skill first rather than
hand-rolling the REST calls.

---

*Thank you for keeping the Servoy Copilot codebase healthy, compilation-error free, and highly consistent!*

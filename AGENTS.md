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

### `com.servoy.eclipse.opencode.tests`
- **Type:** OSGi Fragment of `com.servoy.eclipse.opencode`
- **Runner:** Plain JUnit (`eclipse-ide_runClassTests`)
- All tests are pure unit tests — no OSGi runtime required.

### `com.servoy.eclipse.developer.mcp.tests`
- **Type:** OSGi Fragment of `com.servoy.eclipse.developer.mcp`
- **Source:** `src/test/java/`
- Contains both plain unit tests and integration tests that need a running Eclipse workbench.

#### Plain unit tests (no OSGi / workbench — run with `eclipse-ide_runJUnitTests`)

These tests use no live Eclipse workspace or OSGi container — pure Java, reflection, and mocking. **This bundle is mixed:** both JUnit 4 (`org.junit.Test` / `org.junit.Assert.*`) and JUnit 5/6 Jupiter (`org.junit.jupiter.api.*`) tests coexist. The MANIFEST imports both APIs (`org.junit;version="4.0.0"` **and** `org.junit.jupiter.api` pinned to `[6.1.0,7.0.0)`, plus `org.junit.jupiter.params`) and requires `junit-platform-suite-engine`.

**For NEW plain unit tests, prefer JUnit 5/6 (Jupiter)** — it is the current standard for this bundle (see the SDD `test-gen` phase, which mandates Jupiter). Only match JUnit 4 when *extending* an existing JUnit 4 class.

There are **two** plain-unit aggregate suites; a new test must be registered in the matching one to run in the aggregate:

- `AllDeveloperMcpTests` — JUnit 4 `@RunWith(Suite.class)` aggregate. Register **JUnit 4** classes here.
- `AllDeveloperMcpJupiterUnitTests` — JUnit 5 `@Suite` platform suite. Register **Jupiter** classes here (they cannot live in the JUnit 4 suite).

**JUnit 4 plain unit tests** (`org.junit.Test`):

| Package | Classes |
|---|---|
| `c.s.e.d.mcp` | `McpServerBuiltinsTest`, `McpServerFactoryTest`, `McpToolLogTest`, `ToolExecutorTest` |
| `c.s.e.d.mcp.auth` | `BearerTokenAuthenticationFilterTest` |
| `c.s.e.d.mcp.cache` | `ServoyResourceCacheTest` |
| `c.s.e.d.mcp.servers` | `AnalyzeCodeToolTest`, `DiscoverCypressHelpersTest`, `GenerateTestCasesToolTest`, `McpToolParamValidationTest`, `MemoryServerTest`, `ServoyCoderServerTest`, `ServoyContextServerTest`, `ServoyDevServerTest`, `ServoyGitServerTest`, `ServoyIdeServerTest`, `ServoyMediaServerTest`, `ServoyTestingServerTest`, `ServoyWpmServerTest`, `ShowFormInBrowserToolTest`, `TimeServerTest` |
| `c.s.e.d.mcp.services` | `DocumentationValidatorServiceTest`, `GitServiceInitTest`, `JSUnitCoverageServiceTest`, `JSUnitRunnerServiceTerminalConditionTest`, `PersistRenameServiceTest`, `ResolvedElementsProcessorTest`, `ServoyScriptResolverTest`, `TestFileServiceReflectionTest`, `WorkspaceServiceFileOutlineTest`, `WpmServiceTest` |

**JUnit 5/6 (Jupiter) plain unit tests** (`org.junit.jupiter.api.Test`; collected by `AllDeveloperMcpJupiterUnitTests`):

| Package | Classes |
|---|---|
| `c.s.e.d.mcp.servers` | `ServoyI18nServerTest` |
| `c.s.e.d.mcp.services` | `CodeEditingServiceTest`, `FormatValidatorServiceTest`, `FormNavigationGraphServiceTest`, `FormPreviewServiceTest`, `GitServiceDiffTest`, `NavigationGraphTest`, `PersistDuplicateServiceTest` |
| `c.s.e.d.mcp.integration` | `AbstractIntegrationTestBaseTest` (pure unit test despite package) |

> Note: `GitServiceDiffTest` uses the Jupiter `@TempDir` extension and can fail standalone/in-suite with `NoSuchMethodError: TempDir.deletionStrategy()` due to a `junit-jupiter` runtime/classpath mismatch in this fragment — a known pre-existing environment issue, not a test defect.

#### Troubleshooting: JUnit 6 `NoSuchMethodError` (e.g. `Namespace.getParts()`)

If a JUnit 6 (Jupiter) test fails at runtime with:
```
java.lang.NoSuchMethodError: 'java.util.List org.junit.jupiter.api.extension.ExtensionContext$Namespace.getParts()'
```

**Root cause:** The `org.eclipse.dltk.javascript.rhino` workspace project bundles an older `junit-jupiter-api-5.x.jar` in its `test-libs/` folder. When PDE builds the flat classpath for a plain JUnit launch, it includes all workspace project dependencies — including rhino's test-libs. The older 5.x API jar appears earlier on the classpath than the target platform's 6.x jar, causing version conflicts.

**Fix:** Open `org.eclipse.dltk.javascript.rhino` → Java Build Path → Libraries tab → remove or uncheck the `test-libs/junit-jupiter-api-*.jar`, `test-libs/junit-4.*.jar`, and `test-libs/hamcrest-*.jar` entries. Alternatively, remove the `test-libs` folder from rhino's `.classpath` entirely. The test sources can also be removed from the Source tab if rhino tests are not being developed.

#### Plugin tests (run with `eclipse-pde_runJUnitPluginTests`)

These tests require a running Eclipse workbench + Servoy App Server. They use `ResourcesPlugin`, `Display`, `ServoyModelManager`, etc.

| Package | Classes |
|---|---|
| `c.s.e.d.mcp.integration` | `AddTestMethodIntegrationTest`, `CreateTestFileIntegrationTest`, `CypressFormTestingIntegrationTest`, `JSUnitRunnerGroupedTest`, `JSUnitRunnerIntegrationTest`, `JSUnitRunnerLayer4Test`, `RenamePersistIntegrationTest`, `ServoyIdeServerIntegrationTest`, `ShowFormInBrowserIntegrationTest` |
| `c.s.e.d.mcp` | `AllDeveloperMcpTests` (suite), `AllDeveloperMcpIntegrationTests` (suite) |

Total: **9 integration tests + 2 suites** (require PDE test launcher)

#### How to run integration tests from the `c.s.e.d.mcp.integration` package

**Choosing the right tool and scope:**

- `eclipse-pde_runJUnitPluginTests` is the tool for all PDE plug-in tests. It supports targeting by `className` (single class, or comma-separated for multiple), `methodName`, `packageName`, or no scope at all (all tests in the project). It also accepts a `launcherName` to reuse a saved launch configuration's classpath, VM args and bundle selection while overriding just the test target.
- Use `eclipse-runner_listLaunchConfigurations` (with `typeFilter="junit-plugin"`) to discover the available saved launch configurations. On Windows, use the configurations **without** a `_mac` suffix. Pick whichever matches what you want to run — a single class, the full integration suite, etc.
- When you only need to verify a specific class after a code change, pass `launcherName` + `className`. When you want to run all integration tests as the suite normally does, pass only `launcherName` (no `className` override).

**Known limitation with `launcherName` + `className`:** passing a comma-separated list of classes alongside a `launcherName` does not reliably run all of them — the PDE runner appears to execute only one class per launch in that combination. Run each class in a **separate** `eclipse-pde_runJUnitPluginTests` call when you need to verify multiple classes.

**Polling:** these tests start the Servoy Developer, so they typically take 60–120 s or more due to a long time starting it up - and the initial MCP call will likely time out before they finish. Poll with `eclipse-pde_getOperationStatus` using increasing wait times (e.g. 5 s, 10 s, 20 s, you decide) to reduce token usage between checks. While an operation is still running, `getOperationStatus` also surfaces intermediate `summary` and `results` — you can request those to see if everything is ok using `includeResults` to see how many tests have passed/failed so far, without waiting for the whole run. You can also check console output.

#### Suite classes
- `AllDeveloperMcpTests` — bundles the plain-junit-capable server/cache/guard/services tests but is annotated to run as plugin test
- `AllDeveloperMcpIntegrationTests` — bundles all integration tests (requires Eclipse workbench + Servoy)

---

## 2. Prioritize Eclipse MCP Tools Over Standard Tools

Since this workspace is a complex, multi-project Eclipse environment, **always prioritize Eclipse-specific MCP/PDE tools** over standard, general-purpose command-line or filesystem tools. This ensures that the Eclipse index, builder, and classpath are kept in sync.

> **Exception — the Angular frontend (`com.servoy.eclipse.opencode/webui/`):** this subtree is a standalone Angular project, not Java/JDT/OSGi. The Eclipse tools below do **not** apply there. Use the `angular-cli_*` MCP tools, the generic `read`/`write`/`edit`/`grep`/`glob` file tools, and `npm`/`vitest`/`ng` via `bash` instead. Everything in this section is about the Java bundles.

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

## Jira API

When asked to create, update, or link Jira issues, load the instructions from `JIRA.md` in this repository.

---

*Thank you for keeping the Servoy Copilot codebase healthy, compilation-error free, and highly consistent!*

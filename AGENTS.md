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
- **Main Role:** Opencode AI wrapper — installs, configures, and manages the lifecycle of the [opencode](https://opencode.ai) CLI tool embedded in the Servoy Developer IDE.
- **Key Focus:** Downloads and keeps the `opencode-ai` npm package up to date (`~1.15.x`), starts the opencode HTTP server on a free port, hosts it in an embedded browser view, and merges MCP endpoint contributions from other bundles into `opencode.json`.
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

- **State directory:** `{eclipse-state}/opencode/` — contains `package.json`, `node_modules/`, `package_copy.json` (version sentinel), `.fullygenerated` (install marker).
- **Config directory:** `~/.servoy/opencode/` — contains `opencode.json` (MCP + provider config) and extracted skills zip content.
- **Update strategy:** On every startup, if the bundle's `package.json` changed → full clean `npm install`; otherwise → `npm update opencode-ai` to pick up the latest `1.15.x` patch. Both steps are non-fatal.
- **Test bundle:** `com.servoy.eclipse.opencode.tests` — fragment of the opencode bundle, plain JUnit (no OSGi runtime required), tests `McpConfigWriter` and `OpencodeFolderCreatorJob` helpers.

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

#### Plain JUnit tests (run with `eclipse-ide_runJUnitTests`)

These tests use no live Eclipse workspace or OSGi container — pure Java, reflection, and mocking:

| Package | Classes |
|---|---|
| `c.s.e.d.mcp` | `McpServerBuiltinsTest`, `McpServerFactoryTest`, `ToolExecutorTest` |
| `c.s.e.d.mcp.auth` | `BearerTokenAuthenticationFilterTest` |
| `c.s.e.d.mcp.cache` | `ServoyResourceCacheTest` |
| `c.s.e.d.mcp.guard` | `ServoyFileGuardTest` |
| `c.s.e.d.mcp.servers` | `AnalyzeCodeToolTest`, `GenerateTestCasesToolTest`, `MemoryServerTest`, `ServoyCoderServerTest`, `ServoyContextServerTest`, `ServoyDevServerTest`, `ServoyGitServerTest`, `ServoyIdeServerTest`, `ServoyTestingServerTest`, `ServoyWpmServerTest`, `ShowFormInBrowserToolTest` |
| `c.s.e.d.mcp.services` | `FormSpecGeneratorTest`, `FormSpecRunnerTest`, `PersistRenameServiceTest`, `ResolvedElementsProcessorTest`, `TestFileServiceReflectionTest`, `RunCypressFormTestsLauncherTest` |
| `c.s.e.d.mcp.integration` | `ServoyDevServerIntegrationTest` (despite package name, this is a pure unit test) |

| `c.s.e.d.mcp.servers` | `McpToolParamValidationTest` |

Total: **24 plain JUnit tests**

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

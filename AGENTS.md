# AGENTS.md

## Scope Restriction

CRITICAL: Only edit files within the `Servoy-Copilot` project
(i.e. under `/Volumes/ServoyWork/git/master/Servoy-Copilot/`).

Do NOT edit files in any other Eclipse project or workspace location,
even if MCP tools provide access to them.

If a task would require editing another project, stop and explicitly ask
the user for permission before proceeding.

## Git Operations

CRITICAL: Never run any git commands, never stage files, never commit, and never
suggest committing anything in this project. All git operations are fully manual
and at the user's sole discretion.

## Servoy File Format Rule

CRITICAL: Never write Servoy structural files (`.frm`, `.obj`, `.tbl`, `.val`, `.rel`, `.dbi`, `.js` with `@properties`) directly as text/JSON.

Always use the Servoy persistence API to create and modify Servoy artifacts:
- `solution.createNewForm(...)` to create forms
- `form.createNewPart(...)` to add body parts
- `form.setUseCssPosition(...)` / `form.setResponsiveLayout(...)` for layout type
- `servoyProject.saveEditingSolutionNodes(...)` to persist to disk

Writing these files manually as JSON strings bypasses Servoy's internal model,
risks UUID corruption, and produces files that may be incompatible with the
Servoy runtime. It is **forbidden** regardless of how simple the content appears.

## Project Overview

This is the **Servoy AI Copilot** plugin for the Servoy Developer IDE. It is an Eclipse PDE (Plugin Development Environment) project built with Tycho/Maven. The plugin provides AI-assisted development features including code analysis, test generation, knowledge base indexing, and MCP (Model Context Protocol) integration.

## Project Structure

| Path | Type | Description |
|------|------|-------------|
| `bundles/com.servoy.eclipse.servoypilot` | eclipse-plugin | Main plugin: AI assistant UI, tools, chat, completion |
| `bundles/com.servoy.eclipse.servoypilot.langchain4j` | eclipse-plugin | LangChain4j wrapper bundle (AI/LLM integration library) |
| `bundles/com.servoy.eclipse.servoypilot.knowledgebase` | eclipse-plugin | Knowledge base indexing, ONNX embeddings, RAG |
| `bundles/com.servoy.eclipse.developer.mcp` | eclipse-plugin | MCP server for Servoy Developer (port 8183, path `/svymcp`) |
| `bundles/com.servoy.eclipse.servoypilot.assistenttests` | eclipse-test-plugin | Unit + integration tests (Tycho surefire) |
| `features/com.servoy.eclipse.servoypilot.feature` | eclipse-feature | Feature packaging all plugins + platform-specific fragments |
| `repository.site_aiplugin` | eclipse-repository | P2 update site for distribution |
| `launch_target_aiplugin` | target-definition | Target platform definition for building/running |
| `launch_target` | (legacy) | Old target, not used in Maven build |
| `bundles/application_server` | resource | Servoy app server resources for test runtime |
| `bundles/org.eclipse.jface` | patch | JFace patch/overlay — warnings/errors here can always be ignored |

## MCP Endpoints

Two separate MCP servers exist in this workspace — they run in different JVMs:

| JVM | Port | Path prefix | Bundle | Tools |
|-----|------|-------------|--------|-------|
| Eclipse IDE (AssistAI) | 8085 | `/mcp` | `com.servoy.eclipse.servoypilot` | Eclipse IDE tools (JDT, git, runner, PDE) |
| Servoy Developer | 8183 | `/svymcp` | `com.servoy.eclipse.developer.mcp` | Servoy-specific tools (context, coder, ide, git) |

**Critical:** When testing `developer.mcp` endpoints, use project names from the **Servoy Developer workspace** (e.g. `Example_AI_Plugin`), NOT Eclipse IDE workspace projects (e.g. `Servoy-Copilot`).

### developer.mcp endpoints (port 8183)

| Endpoint | Tools |
|----------|-------|
| `/svymcp/servoy-context` | listCachedResources, getCachedResource, getCacheStats, getFileHistory, getFileHistoryContent, compareWithHistory, restoreFileVersion (dummy) |
| `/svymcp/servoy-coder` | createFile, insertIntoFile, replaceString, undoEdit, createDirectories, renameFile, moveResource, deleteFile, replaceFileContent, deleteLinesInFile, applyPatch + 6 JDT dummies |
| `/svymcp/servoy-ide` | listProjects, getProjectLayout, getProjectProperties, readProjectResource, findFiles, fileSearch, fileSearchRegExp, searchAndReplace, getMarkdownOutline, getMarkdownSection, getCurrentlyOpenedFile, getEditorSelection, getConsoleOutput, getCompilationErrors + 20 dummies |
| `/svymcp/servoy-git` | gitStatus, gitLog, gitAdd, gitCommit, gitDiff, gitBranch, gitCreateBranch, gitDeleteBranch, gitCheckout, gitReset, gitStash, gitStashPop, gitStashList, gitStagePatch |

Auth token: `bd6f7df6-2872-4e5c-9387-ae5fae62ca3c`

### developer.mcp architecture

- Bundle: `com.servoy.eclipse.developer.mcp`
- Runs inside Servoy Developer's embedded Tomcat via `IServicesProvider` extension point
- E4 DI: server classes use `@Creatable` + `@Inject`; instantiated via `ContextInjectionFactory.make()`
- `McpServerBuiltins.createServerInstances(IEclipseContext)` — pass context for E4 DI, null for tests
- Testing constructor pattern: each server class has a package-private constructor accepting services directly
- `ServoyFileGuard` — refuses destructive edits on `.frm`, `.obj`, `.tbl`, `.val`, `.rel`, `.dbi`
- `ServoyResourceCache` — singleton, not injectable, accessed via `getInstance()`

## Build

```bash
# Full build (compile + site, skip tests)
mvn clean verify -Dmaven.test.skip=true

# Build with unit + integration tests
mvn clean verify

# Only unit tests (fast, headless)
mvn verify -pl bundles/com.servoy.eclipse.servoypilot.assistenttests -am
```

The build uses **Tycho 4.0.12** and targets **JavaSE-21**.

## Target Platform

The target (`launch_target_aiplugin/com.servoy.eclipse.servoypilot..target`) resolves against:
- `https://build.servoy.com/latest/servoy_release/update_site/` (Servoy feature + Eclipse platform)
- Maven Central + Servoy Maven repo for additional dependencies (ONNX, PDFBox, Tika, MCP SDK, Reactor)

The main Servoy product target is at: https://github.com/Servoy/servoy-eclipse/blob/release/launch_targets/com.servoy.eclipse.target.target

Dependencies already provided by the Servoy target should NOT be duplicated here.

## Testing

Tests are in `bundles/com.servoy.eclipse.servoypilot.assistenttests`:
- `src/.../unit/` — Pure logic unit tests (no OSGi runtime needed)
- `src/.../integration/` — Integration tests requiring full Eclipse workbench + Servoy runtime

Both run via `tycho-surefire-plugin` with `useUIHarness=true` and `useUIThread=true`.

### developer.mcp JUnit tests

Tests are in `bundles/com.servoy.eclipse.developer.mcp/test/`. Run manually via **Run As → JUnit Plugin Test** using `DeveloperMcpTests.launch`. The `eclipse-pde_runJUnitPluginTestClass` MCP tool does NOT work for this bundle.

### Running tests from Eclipse

Use the correct Eclipse MCP tool depending on the test type:

| Test class | Type | Run with |
|---|---|---|
| `...unit.AnalyzeCodeToolTest` | unit | `runClassTests` |
| `...unit.GenerateTestCasesToolTest` | unit | `runClassTests` |
| `...unit.TestFileServiceReflectionTest` | unit | `runClassTests` |
| `...integration.AddTestMethodIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.CreateTestFileIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerGroupedTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerLayer4Test` | integration (PDE) | `runJUnitPluginTestClass` |

- Unit tests (`**/unit/*`): use `runClassTests` or `runTestMethod` — these are plain JUnit, no OSGi needed.
- Integration tests (`**/integration/*`): use `runJUnitPluginTestClass` or `runJUnitPluginTests` — these require the full PDE runtime with workbench.

Project name for all: `com.servoy.eclipse.servoypilot.assistenttests`

## Tool Preferences

When working in this project, prefer Eclipse MCP tools over built-in tools:

| Task | Use (Eclipse MCP) | Avoid (built-in) |
|------|-------------------|-------------------|
| Read Java source | `getSource`, `getFilteredSource`, `getMethodSource` | `read` (for Java files) |
| Class structure | `getClassOutline` | `grep` for class members |
| Find references | `findReferences` | `grep` for usages |
| Search code | `fileSearch`, `fileSearchRegExp` | `grep` |
| Find files | `findFiles` | `glob` |
| Project layout | `getProjectLayout` | `read` directory |
| Compilation errors | `getCompilationErrors` | reading build output |
| Run tests | `runClassTests`, `runAllTests`, `runTestMethod` | `bash mvn test` |
| Organize imports | `organizeImports` | manual edit |
| Format code | `formatFile` | manual formatting |
| Type hierarchy | `getTypeHierarchy` | grep for extends/implements |
| Call hierarchy | `getMethodCallHierarchy` | grep for method name |
| Quick fixes | `executeQuickFix` | manual fix |
| Git operations | `eclipse-git` tools | `bash git` |
| Refactoring (rename/move) | `refactorRenameJavaType`, `refactorMoveJavaType` | manual rename + find/replace |
| Maven build | `runMavenBuild` | `bash mvn` |
| PDE tests | `eclipse-pde` tools | `bash mvn verify` |

Use built-in `read`/`glob`/`grep` only for non-Java files (XML, properties, markdown, pom.xml) or when Eclipse tools are not applicable.

**Exception:** For a full CI/CD-style build (compile + tests + site), use the shell/bash `mvn` command directly. This ensures the build is tested exactly as it runs in CI. Example:

```bash
mvn clean verify -Dmaven.test.skip=true
```

## Lint / Typecheck

There is no separate lint or typecheck command. Compilation is handled by the Eclipse JDT compiler via Tycho. Use `getCompilationErrors` to check for issues after edits.

After making changes, always run `getCompilationErrors` to verify there are no new issues. SpotBugs findings marked as ERROR severity should also be fixed when possible (e.g. unsynchronized singleton getInstance, non-private constructors, potential null pointer dereferences).

This is the **Servoy AI Copilot** plugin for the Servoy Developer IDE. It is an Eclipse PDE (Plugin Development Environment) project built with Tycho/Maven. The plugin provides AI-assisted development features including code analysis, test generation, knowledge base indexing, and MCP (Model Context Protocol) integration.

## Project Structure

| Path | Type | Description |
|------|------|-------------|
| `bundles/com.servoy.eclipse.servoypilot` | eclipse-plugin | Main plugin: AI assistant UI, tools, chat, completion |
| `bundles/com.servoy.eclipse.servoypilot.langchain4j` | eclipse-plugin | LangChain4j wrapper bundle (AI/LLM integration library) |
| `bundles/com.servoy.eclipse.servoypilot.knowledgebase` | eclipse-plugin | Knowledge base indexing, ONNX embeddings, RAG |
| `bundles/com.servoy.eclipse.developer.mcp` | eclipse-plugin | MCP server implementation for Servoy Developer |
| `bundles/com.servoy.eclipse.servoypilot.assistenttests` | eclipse-test-plugin | Unit + integration tests (Tycho surefire) |
| `features/com.servoy.eclipse.servoypilot.feature` | eclipse-feature | Feature packaging all plugins + platform-specific fragments |
| `repository.site_aiplugin` | eclipse-repository | P2 update site for distribution |
| `launch_target_aiplugin` | target-definition | Target platform definition for building/running |
| `launch_target` | (legacy) | Old target, not used in Maven build |
| `bundles/application_server` | resource | Servoy app server resources for test runtime |
| `bundles/org.eclipse.jface` | patch | JFace patch/overlay included only so tests pick it up in their launch classpath. Warnings/errors in this project can always be ignored. |

## Build

```bash
# Full build (compile + site, skip tests)
mvn clean verify -Dmaven.test.skip=true

# Build with unit + integration tests
mvn clean verify

# Only unit tests (fast, headless)
mvn verify -pl bundles/com.servoy.eclipse.servoypilot.assistenttests -am
```

The build uses **Tycho 4.0.12** and targets **JavaSE-21**.

## Target Platform

The target (`launch_target_aiplugin/com.servoy.eclipse.servoypilot..target`) resolves against:
- `https://build.servoy.com/latest/servoy_release/update_site/` (Servoy feature + Eclipse platform)
- Maven Central + Servoy Maven repo for additional dependencies (ONNX, PDFBox, Tika, MCP SDK, Reactor)

The main Servoy product target is at: https://github.com/Servoy/servoy-eclipse/blob/release/launch_targets/com.servoy.eclipse.target.target

Dependencies already provided by the Servoy target should NOT be duplicated here.

## Testing

Tests are in `bundles/com.servoy.eclipse.servoypilot.assistenttests`:
- `src/.../unit/` — Pure logic unit tests (no OSGi runtime needed)
- `src/.../integration/` — Integration tests requiring full Eclipse workbench + Servoy runtime

Both run via `tycho-surefire-plugin` with `useUIHarness=true` and `useUIThread=true`.

### Running tests from Eclipse

Use the correct Eclipse MCP tool depending on the test type:

| Test class | Type | Run with |
|---|---|---|
| `...unit.AnalyzeCodeToolTest` | unit | `runClassTests` |
| `...unit.GenerateTestCasesToolTest` | unit | `runClassTests` |
| `...unit.TestFileServiceReflectionTest` | unit | `runClassTests` |
| `...integration.AddTestMethodIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.CreateTestFileIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerGroupedTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerIntegrationTest` | integration (PDE) | `runJUnitPluginTestClass` |
| `...integration.JSUnitRunnerLayer4Test` | integration (PDE) | `runJUnitPluginTestClass` |

- Unit tests (`**/unit/*`): use `runClassTests` or `runTestMethod` — these are plain JUnit, no OSGi needed.
- Integration tests (`**/integration/*`): use `runJUnitPluginTestClass` or `runJUnitPluginTests` — these require the full PDE runtime with workbench.

Project name for all: `com.servoy.eclipse.servoypilot.assistenttests`

## Tool Preferences

When working in this project, prefer Eclipse MCP tools over built-in tools:

| Task | Use (Eclipse MCP) | Avoid (built-in) |
|------|-------------------|-------------------|
| Read Java source | `getSource`, `getFilteredSource`, `getMethodSource` | `read` (for Java files) |
| Class structure | `getClassOutline` | `grep` for class members |
| Find references | `findReferences` | `grep` for usages |
| Search code | `fileSearch`, `fileSearchRegExp` | `grep` |
| Find files | `findFiles` | `glob` |
| Project layout | `getProjectLayout` | `read` directory |
| Compilation errors | `getCompilationErrors` | reading build output |
| Run tests | `runClassTests`, `runAllTests`, `runTestMethod` | `bash mvn test` |
| Organize imports | `organizeImports` | manual edit |
| Format code | `formatFile` | manual formatting |
| Type hierarchy | `getTypeHierarchy` | grep for extends/implements |
| Call hierarchy | `getMethodCallHierarchy` | grep for method name |
| Quick fixes | `executeQuickFix` | manual fix |
| Git operations | `eclipse-git` tools | `bash git` |
| Refactoring (rename/move) | `refactorRenameJavaType`, `refactorMoveJavaType` | manual rename + find/replace |
| Maven build | `runMavenBuild` | `bash mvn` |
| PDE tests | `eclipse-pde` tools | `bash mvn verify` |

Use built-in `read`/`glob`/`grep` only for non-Java files (XML, properties, markdown, pom.xml) or when Eclipse tools are not applicable.

**Exception:** For a full CI/CD-style build (compile + tests + site), use the shell/bash `mvn` command directly. This ensures the build is tested exactly as it runs in CI. Example:

```bash
mvn clean verify -Dmaven.test.skip=true
```

## Lint / Typecheck

There is no separate lint or typecheck command. Compilation is handled by the Eclipse JDT compiler via Tycho. Use `getCompilationErrors` to check for issues after edits.

After making changes, always run `getCompilationErrors` to verify there are no new issues. SpotBugs findings marked as ERROR severity should also be fixed when possible (e.g. unsynchronized singleton getInstance, non-private constructors, potential null pointer dereferences).

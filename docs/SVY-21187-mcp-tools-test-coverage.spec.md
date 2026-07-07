# Spec: SVY-21187 — Create Integration Tests for the MCP Tools

## 1. Goal

Identify all MCP tools in `com.servoy.eclipse.developer.mcp`, determine which tools lack test coverage, and classify each untested tool as requiring either **plain JUnit tests** (pure logic, no Eclipse/Servoy runtime dependencies) or **PDE plugin integration tests** (requires Eclipse workspace, Servoy model, database servers, or UI thread). The implementation plan provides a prioritized roadmap for adding the missing tests.

## 2. Background

### 2.1 Architecture

The MCP server bundle exposes IDE operations as tools via annotated `@McpServer` classes in `com.servoy.eclipse.developer.mcp.servers`. Each public method annotated with `@Tool` is a callable tool. The test fragment `com.servoy.eclipse.developer.mcp.tests` contains two categories of tests:

- **Plain JUnit** (`src/test/java/.../servers/` and `.../services/`): No OSGi container or Eclipse workspace needed. Tests annotations, parameter validation, pure-logic methods, and error paths via reflection/mocking.
- **PDE Plugin Integration Tests** (`src/test/java/.../integration/`): Require a running Eclipse workbench + Servoy App Server. Use `ResourcesPlugin`, `ServoyModelManager`, `Display`, real projects, and real database connections.

### 2.2 Current Test Coverage Summary

| Server Class | Tools | Plain JUnit | Integration Tests | Coverage Gap |
|---|---|---|---|---|
| **TimeServer** | 2 | NONE | NONE | Full gap — needs plain JUnit |
| **MemoryServer** | 2 | ✓ MemoryServerTest | — | Adequate |
| **ServoyCoderServer** | 11 | ✓ ServoyCoderServerTest (annotations + guardScopePath only) | NONE | Large gap — needs PDE tests for file operations |
| **ServoyContextServer** | 6 | ✓ ServoyContextServerTest (cache + null validation) | NONE | Gap for file history tools — needs PDE |
| **ServoyDevServer** | 35+ | ✓ ServoyDevServerTest (annotations, generateUUID, validation) | ✓ RenamePersistIntegrationTest, PersistDuplicateIntegrationTest | Large gap — many tools untested end-to-end |
| **ServoyGitServer** | 14 | ✓ ServoyGitServerTest (annotations + missing-project) | NONE | Needs PDE for actual git operations |
| **ServoyI18nServer** | 3 | ✓ ServoyI18nServerTest (annotations + validation) | NONE | Needs PDE — all tools require Servoy DB |
| **ServoyIdeServer** | 26+ | ✓ ServoyIdeServerTest (annotations + null checks) | ✓ ServoyIdeServerIntegrationTest (5 tests) | Large gap — most workspace tools untested |
| **ServoyMediaServer** | 1 | ✓ ServoyMediaServerTest (extensive reflection tests) | NONE | Minor gap — could use PDE end-to-end test |
| **ServoyTestingServer** | 16+ | ✓ ServoyTestingServerTest (80+ tests) | ✓ 8 integration test classes | Small gap — a few tools remain |
| **ServoyWpmServer** | 8 | ✓ ServoyWpmServerTest (25+ tests) | ✓ ServoyWpmServerIntegrationTest (11 tests) | Small gap — 3 tools untested |

## 3. Design

### 3.1 Classification Criteria

A tool requires **PDE plugin integration tests** if it:
- Accesses Eclipse workspace APIs (`ResourcesPlugin`, `IProject`, `IFile`, `IFolder`)
- Uses `ServoyModelManager`, `ServoyModel`, or Servoy persistence layer
- Connects to Servoy database servers (`IServerManagerInternal`, `IServerInternal`)
- Uses the Eclipse UI thread (`Display.syncExec`)
- Requires an active Servoy solution to be loaded
- Interacts with JGit through Eclipse's EGit integration

A tool needs only **plain JUnit tests** if it:
- Contains pure computational logic (UUID generation, time conversion, string formatting)
- Can be tested with reflection and/or simple mocking
- Has no runtime dependencies on Eclipse/Servoy infrastructure

### 3.2 Detailed Tool-by-Tool Analysis

#### TimeServer — NO TESTS AT ALL

| Tool | Test Type | Rationale |
|---|---|---|
| `getCurrentTime` | Plain JUnit | Pure `ZonedDateTime.now()` formatting |
| `convertTimeZone` | Plain JUnit | Pure `ZoneId`/`DateTimeFormatter` logic |

#### ServoyCoderServer — Needs PDE Integration Tests

| Tool | Test Type | Rationale |
|---|---|---|
| `createFile` | PDE | `CodeEditingService` → `IProject.getFile()` → `IFile.create()` |
| `insertIntoFile` | PDE | Reads/writes IFile content |
| `replaceString` | PDE | Reads/writes IFile content |
| `undoEdit` | PDE | Restores from backup via IFile |
| `createDirectories` | PDE | `IFolder.create()` |
| `renameFile` | PDE | `IResource.move()` |
| `moveResource` | PDE | `IResource.move()` |
| `deleteFile` | PDE | `IResource.delete()` |
| `replaceFileContent` | PDE | Writes IFile content |
| `deleteLinesInFile` | PDE | Reads/writes IFile content |
| `applyPatch` | PDE | Reads/writes IFile content |

#### ServoyContextServer — Partial Gap

| Tool | Test Type | Status |
|---|---|---|
| `listCachedResources` | Plain JUnit | ✓ Already tested |
| `getCachedResource` | Plain JUnit | ✓ Already tested |
| `getCacheStats` | Plain JUnit | ✓ Already tested |
| `getFileHistory` | PDE | Needs test — uses `IFileState` |
| `getFileHistoryContent` | PDE | Needs test — uses `IFileState` |
| `compareWithHistory` | PDE | Needs test — uses `IFileState` |

#### ServoyDevServer — Large Gap

**Already tested (adequate):** `ping`, `generateUUID`, `renamePersist`, `duplicatePersist`

**Needs PDE integration tests:**

| Tool | Rationale |
|---|---|
| `createForm` | `ServoyArtifactCreationService` + Servoy model |
| `createRelation` | `ServoyArtifactCreationService` + Servoy model |
| `createValueList` | `ServoyArtifactCreationService` + Servoy model |
| `createSolution` | Creates Eclipse projects + Servoy model |
| `activateSolution` | `ServoyModelManager` + UI thread |
| `syncDbiWithDatabase` | `DataModelManager` + DB server |
| `listTables` | `IServerManagerInternal` |
| `getTableInfo` | `IServerManagerInternal` + `ITable` |
| `executeSQL` | `IServerManagerInternal` + JDBC |
| `addColumn` | `IServerManagerInternal` + column types |
| `createTable` | `IServerManagerInternal` |
| `createServer` | `IServerManagerInternal` + DB creation |
| `listUsers` | `WorkspaceUserManager` |
| `createUser` | `WorkspaceUserManager` |
| `changeUserName` | `WorkspaceUserManager` |
| `setUserPassword` | `WorkspaceUserManager` |
| `createPermission` | `WorkspaceUserManager` |
| `getFormSecurity` | `WorkspaceUserManager` + Servoy model |
| `setFormElementAccess` | `WorkspaceUserManager` + Servoy model |
| `setFormSecurityBulk` | `WorkspaceUserManager` + Servoy model |
| `getDocumentationForTypeMember` | `ServoyDocumentationService` (Servoy DLTK) |
| `getAvailableMembersForType` | `ServoyDocumentationService` |
| `getDocumentationForIdentifiers` | `ServoyDocumentationService` |
| `applyDocumentations` | `ServoyDocumentationService` + file write |
| `resolveIdentifierType` | `ScriptContextService` (Servoy workspace) |
| `validateFormElementFormat` | Needs Servoy model for form lookup |

**Needs plain JUnit tests (new):**

| Tool | Rationale |
|---|---|
| `validateFormat` | `FormatValidatorService` is pure logic |
| `validate` | `JsCodeValidatorService` — regex/parse logic |

#### ServoyGitServer — Needs PDE Integration Tests

| Tool | Test Type | Rationale |
|---|---|---|
| `gitStatus` | PDE | JGit `StatusCommand` on real repo |
| `gitLog` | PDE | JGit `LogCommand` |
| `gitAdd` | PDE | JGit `AddCommand` |
| `gitCommit` | PDE | JGit `CommitCommand` |
| `gitDiff` | PDE | JGit `DiffCommand` |
| `gitBranch` | PDE | JGit `ListBranchCommand` |
| `gitCreateBranch` | PDE | JGit `CreateBranchCommand` |
| `gitDeleteBranch` | PDE | JGit `DeleteBranchCommand` |
| `gitCheckout` | PDE | JGit `CheckoutCommand` |
| `gitReset` | PDE | JGit `ResetCommand` |
| `gitStash` | PDE | JGit `StashCreateCommand` |
| `gitStashPop` | PDE | JGit `StashApplyCommand` |
| `gitStashList` | PDE | JGit `StashListCommand` |
| `gitStagePatch` | PDE | JGit add via patch |

#### ServoyI18nServer — Needs PDE Integration Tests

| Tool | Test Type | Rationale |
|---|---|---|
| `i18nListTables` | PDE | `ServoyModelManager` + `IServerManagerInternal` |
| `i18nSearchMessages` | PDE | `ServoyModelManager` + DB query |
| `i18nSetTable` | PDE | `ServoyModelManager` + table creation |

#### ServoyIdeServer — Needs More PDE Integration Tests

**Already has integration tests for:** `getClassOutline`, `getMethodSource`, `getFilteredSource`

**Needs PDE integration tests:**

| Tool | Rationale |
|---|---|
| `readProjectResource` | `ProjectService` → `IFile.getContents()` |
| `getProjectLayout` | `ProjectService` → `IContainer.members()` |
| `getProjectProperties` | `ProjectService` → project natures/settings |
| `findFiles` | Eclipse text search engine |
| `fileSearch` | Eclipse text search engine |
| `fileSearchRegExp` | Eclipse text search engine |
| `searchAndReplace` | Eclipse text search + file write |
| `getCompilationErrors` | `IMarker` from Eclipse builder |
| `executeQuickFix` | Eclipse quick-fix proposals |
| `getCurrentlyOpenedFile` | Eclipse editor state (UI thread) |
| `getEditorSelection` | Eclipse editor state (UI thread) |
| `getConsoleOutput` | Eclipse console manager |
| `openProject` | `ResourcesPlugin` project import |
| `getSource` | `ServoyScriptResolver` (Servoy DLTK) |
| `findReferences` | Eclipse JDT search engine |
| `getTypeHierarchy` | Eclipse JDT type hierarchy |
| `getMethodCallHierarchy` | Eclipse JDT call hierarchy |
| `getMarkdownOutline` | `MarkdownService` → file read |
| `getMarkdownSection` | `MarkdownService` → file read |
| `getFileInfo` | `IFile` metadata |
| `readFileRanges` | `ProjectService` → `IFile.getContents()` |
| `readFileContext` | `ProjectService` → `IFile.getContents()` |
| `getFileOutline` | `FileStructureService` → parsing |
| `readFunction` | `FileStructureService` → parsing |
| `listProjects` | `ResourcesPlugin.getWorkspace()` |

#### ServoyMediaServer — Minor Gap

| Tool | Test Type | Status |
|---|---|---|
| `mediaRename` | PDE | End-to-end with real Servoy project (existing plain JUnit tests are thorough via reflection) |

#### ServoyTestingServer — Small Gap

**Already has integration tests for:** `showFormInBrowser`, `createTestFile`, `addTestMethod`, `runJsUnitTests`, `runTestMethod`, Cypress form testing, `generateCypressE2ETest`

**Missing PDE tests:**

| Tool | Rationale |
|---|---|
| `executeTestSetup` | JDBC insert via Servoy DB |
| `executeTestTeardown` | JDBC delete via Servoy DB |
| `screenshotForm` | NG client + screenshot capture |
| `generateFormSpec` | `FormSpecGenerator` with real forms |
| `getFormNavigationGraph` | With real multi-form solution |
| `findForm` | Servoy model search |

#### ServoyWpmServer — Small Gap

**Already has integration tests for:** `getComponents`, `getComponentSpec`, `getComponentDocs`, `getInstalledPackages`, `uninstallPackage`

**Missing PDE tests:**

| Tool | Rationale |
|---|---|
| `searchPackages` | WPM REST API call |
| `installPackage` | WPM installation + workspace changes |
| `getAvailableWebPackages` | WPM REST API call |

### 3.3 Priority Classification

**Priority 1 — High Value, No Tests at All:**
1. `TimeServer` — 2 tools, zero tests, trivial to add plain JUnit
2. `ServoyCoderServer` file operations — 11 tools, zero integration tests, high-risk area (file corruption)
3. `ServoyDevServer` artifact creation (`createForm`, `createRelation`, `createValueList`) — core Servoy operations with no end-to-end tests

**Priority 2 — High Value, Partial Coverage:**
4. `ServoyGitServer` — 14 tools with zero integration tests despite all requiring git repos
5. `ServoyI18nServer` — 3 tools with zero integration tests
6. `ServoyDevServer` database tools (`listTables`, `getTableInfo`, `executeSQL`, `addColumn`, `createTable`, `createServer`)
7. `ServoyDevServer` security tools (`listUsers`, `createUser`, `createPermission`, etc.)

**Priority 3 — Medium Value:**
8. `ServoyIdeServer` workspace tools (`readProjectResource`, `getProjectLayout`, `findFiles`, `fileSearch`)
9. `ServoyContextServer` file history tools
10. `ServoyDevServer` documentation tools

**Priority 4 — Lower Priority (already has significant coverage):**
11. `ServoyTestingServer` remaining tools
12. `ServoyWpmServer` remaining tools
13. `ServoyMediaServer` end-to-end test

## 4. Implementation Plan

### Phase 1: Plain JUnit Tests (no infrastructure needed)

1. **Create `TimeServerTest.java`** in `src/test/java/.../servers/`
   - Test `getCurrentTime` returns valid ISO format
   - Test `convertTimeZone` with known time zones
   - Test `convertTimeZone` with invalid zone returns error
   - Test `convertTimeZone` with null/blank inputs returns error
   - Test annotation presence and registration in McpServerBuiltins

2. **Create `ServoyDevServer` additional plain JUnit tests** for `validateFormat`
   - Test valid format strings for each data type
   - Test invalid format returns error
   - Test null/blank inputs

### Phase 2: PDE Integration Tests — Artifact Creation

3. **Create `ServoyCoderServerIntegrationTest.java`** in `.../integration/`
   - Setup: Activate test solution, get project reference
   - Test `createFile` creates file in workspace
   - Test `insertIntoFile` inserts at correct line
   - Test `replaceString` replaces content
   - Test `replaceFileContent` overwrites file
   - Test `deleteLinesInFile` removes lines
   - Test `applyPatch` applies unified diff
   - Test `createDirectories` creates folder tree
   - Test `renameFile` renames and preserves content
   - Test `moveResource` moves to new location
   - Test `deleteFile` removes file from workspace
   - Test `undoEdit` restores previous content
   - Teardown: Clean up created files

4. **Create `CreateArtifactsIntegrationTest.java`** in `.../integration/`
   - Test `createForm` with minimal params → form exists in model
   - Test `createForm` with dataSource and events → form configured correctly
   - Test `createForm` with extendsForm → inheritance set up
   - Test `createRelation` → relation exists with correct columns
   - Test `createValueList` custom type → valuelist with custom values
   - Test `createValueList` database type → valuelist with datasource

5. **Create `CreateSolutionIntegrationTest.java`** in `.../integration/`
   - Test `createSolution` creates Eclipse project + solution.json
   - Test `createSolution` with module type and parent
   - Test `activateSolution` switches active solution

### Phase 3: PDE Integration Tests — Database & Git

6. **Create `ServoyGitServerIntegrationTest.java`** in `.../integration/`
   - Setup: Create temp project with git init
   - Test `gitStatus` shows clean/dirty state
   - Test `gitAdd` + `gitCommit` + `gitLog` workflow
   - Test `gitDiff` shows changes
   - Test `gitBranch`/`gitCreateBranch`/`gitCheckout`/`gitDeleteBranch` workflow
   - Test `gitStash`/`gitStashPop`/`gitStashList` workflow
   - Test `gitReset` unstages files
   - Test `gitStagePatch` stages partial changes

7. **Create `DatabaseToolsIntegrationTest.java`** in `.../integration/`
   - Test `listTables` returns known tables
   - Test `getTableInfo` returns column info
   - Test `createTable` + `addColumn` workflow
   - Test `executeSQL` runs SELECT successfully
   - Test `createServer` creates new DB connection

8. **Create `ServoyI18nServerIntegrationTest.java`** in `.../integration/`
   - Test `i18nListTables` returns configured tables
   - Test `i18nSearchMessages` finds messages
   - Test `i18nSetTable` configures i18n table

### Phase 4: PDE Integration Tests — Security & Documentation

9. **Create `SecurityToolsIntegrationTest.java`** in `.../integration/`
   - Test `listUsers` returns users
   - Test `createUser` + `changeUserName` + `setUserPassword` workflow
   - Test `createPermission` + `getFormSecurity` + `setFormElementAccess` workflow
   - Test `setFormSecurityBulk` with multiple elements

10. **Create `DocumentationToolsIntegrationTest.java`** in `.../integration/`
    - Test `getDocumentationForTypeMember` returns JSDoc
    - Test `getAvailableMembersForType` lists members
    - Test `getDocumentationForIdentifiers` resolves from file context
    - Test `applyDocumentations` writes JSDoc to file

### Phase 5: PDE Integration Tests — IDE Operations

11. **Create `ServoyIdeServerWorkspaceIntegrationTest.java`** in `.../integration/`
    - Test `readProjectResource` reads file content
    - Test `getProjectLayout` shows directory structure
    - Test `getProjectProperties` returns project info
    - Test `findFiles` finds by pattern
    - Test `fileSearch` finds by content
    - Test `fileSearchRegExp` finds by regex
    - Test `getCompilationErrors` returns markers
    - Test `getMarkdownOutline` + `getMarkdownSection` with .md file
    - Test `getFileInfo` returns metadata
    - Test `readFileRanges` returns correct ranges
    - Test `readFileContext` returns windowed content
    - Test `getFileOutline` returns structure
    - Test `readFunction` returns function body
    - Test `listProjects` returns workspace projects

12. **Create `ContextServerHistoryIntegrationTest.java`** in `.../integration/`
    - Test `getFileHistory` after file modifications
    - Test `getFileHistoryContent` returns old content
    - Test `compareWithHistory` shows diff

## 5. Acceptance Criteria

- [ ] `TimeServer` has a plain JUnit test class covering both tools (≥6 test methods)
- [ ] `ServoyCoderServer` has a PDE integration test class covering all 11 file operation tools
- [ ] `ServoyDevServer` `createForm`, `createRelation`, `createValueList` have PDE integration tests
- [ ] `ServoyGitServer` has a PDE integration test covering the basic git workflow (add → commit → log → branch → checkout)
- [ ] `ServoyI18nServer` has a PDE integration test covering all 3 tools
- [ ] `ServoyDevServer` database tools (`listTables`, `getTableInfo`, `executeSQL`, `createTable`, `addColumn`) have PDE integration tests
- [ ] `ServoyDevServer` security tools (`listUsers`, `createUser`, `createPermission`, `getFormSecurity`) have PDE integration tests
- [ ] `ServoyIdeServer` workspace tools (`readProjectResource`, `getProjectLayout`, `findFiles`, `fileSearch`) have PDE integration tests
- [ ] All new test classes follow existing patterns (extend nothing or `ServoyRunnerTestBase` for PDE tests)
- [ ] All new plain JUnit tests pass with `eclipse-ide_runClassTests`
- [ ] All new PDE tests pass with `eclipse-pde_runJUnitPluginTestClass`
- [ ] Zero compilation errors introduced
- [ ] No false positives — tests must genuinely verify behaviour, not just pass trivially
- [ ] No use of `Assume` / `assumeTrue` / `assumeThat` — use only `assert*` methods
- [ ] Tests must not be manipulated to be green — if a test fails, it is analysed and discussed before any fix is applied

## 6. Out of Scope

- Modifying existing test classes or production code
- Adding tests for the inactive bundles (`servoypilot`, `langchain4j`, `knowledgebase`)
- Performance or load testing
- Tests requiring external network access (WPM `searchPackages`, `installPackage`, `getAvailableWebPackages` — these hit the internet)
- Tests for `getCurrentlyOpenedFile`, `getEditorSelection` (require interactive UI state that is non-deterministic in test runners)
- Tests for `screenshotForm` (requires running NG client + browser — too complex for automated tests)

## 7. Open Questions (Resolved)

| Question | Decision |
|----------|----------|
| Should `ServoyGitServer` integration tests use a temporary in-memory git repo or a real project in the test workspace? | Temporary in-memory repository |
| For database tools integration tests, should we create a dedicated test DB or reuse existing test infrastructure? | Reuse existing test infrastructure (easier to implement — no DB provisioning needed) |
| Should `screenshotForm` be tested given it requires a running NG client + browser? | No — out of scope |
| Is `ServoyDevServerIntegrationTest` (which currently runs as plain JUnit despite its package name) intended to eventually become a real PDE test? | Keep as-is — "integration test" is the naming convention for PDE plugin tests in this project |
| Should `validateFormat` tests go into `ServoyDevServerTest` or a new `FormatValidatorServiceTest`? | New `FormatValidatorServiceTest` class |

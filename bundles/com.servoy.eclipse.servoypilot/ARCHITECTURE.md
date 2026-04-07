# ServoyPilot - Architecture Reference

**Last Updated:** April 7, 2026
**Purpose:** Technical reference for system design and component structure. Code is the source of truth.

---

## 1. OSGi Bundle Structure

The plugin is split into 3 OSGi bundles:

- `com.servoy.eclipse.servoypilot` — Main plugin: UI, AI assistants, tools, services
- `com.servoy.eclipse.servoypilot.langchain4j` — LangChain4j library wrapper
- `com.servoy.eclipse.servoypilot.knowledgebase` — Knowledge base data: embeddings, rules, system prompts

---

## 2. AI Assistants

### 2.1 Assistant Types (`AssistantType` enum)

All 7 assistant types are defined in `AssistantType.java`:

- `VIBE_CODING` — VibeCoding Assistant (`-vibe`) — ChatView dropdown
- `DOCUMENTATION` — Documentation Assistant (`-documentation`) — ChatView dropdown
- `EXPLAIN` — Explain Assistant (`-explain`) — ChatView dropdown
- `REVIEW` — Review Assistant (`-review`) — ChatView dropdown
- `UNIT_TEST` — Unit Test Assistant (`-unittest`) — ChatView dropdown
- `QUERY_BUILDER` — Query Builder Assistant (`-querybuilder`) — ChatView dropdown
- `QUICKFIX` — QuickFix Assistant (`-quickfix`) — lightbulb/marker only, not in dropdown

### 2.2 `IAssistant` Interface

```java
TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request);
AssistantType getType();
String getDisplayName();
```

Each assistant interface (e.g., `VibeCodingAssistant`, `DocumentationAssistant`) extends `IAssistant` and is a LangChain4j `AiServices`-generated proxy.

### 2.3 Tool Registration per Assistant (`ServoyAiModel`)

All assistants use `ToolComposer`-based registration via their `XxxAssistantTools.getTools()` factory.

```
VibeCoding:     VibeCodingAssistantTools.getTools() → ToolComposer.from(
                  IAnalyzeFileStructureTool, IGetCodeChunkTool, IResolveIdentifierTypeTool,
                  IFileSearchTool, IFileSearchRegExpTool, IFindFilesTool,
                  ISearchAndReplaceTool, IGetProblemsTool,
                  IGetFormsTool, IOpenFormTool, IDeleteFormsTool,
                  IGetRelationsTool, IOpenRelationTool, IDeleteRelationsTool,
                  IGetValueListsTool, IOpenValueListTool, IDeleteValueListsTool,
                  IGetStylesTool, IOpenStyleTool, IDeleteStyleTool,
                  IButtonComponentTool, ILabelComponentTool,
                  IDatabaseTool, ITargetTool, IKnowledgeTool)

Documentation:  DocumentationAssistantTools.getTools() → ToolComposer.from(
                  IAnalyzeFileStructureTool, IGetCodeChunkTool, IResolveIdentifierTypeTool,
                  IGetCurrentSelectionTool, IGetDocumentationForIdentifiersTool,
                  IApplyDocumentationsTool, IGetAvailableMembersForTypeTool,
                  IGetDocumentationForTypeMemberTool,
                  IFileSearchTool, IFileSearchRegExpTool, IFindFilesTool)

Explain:        ExplainAssistantTools.getTools() → ToolComposer.from(
                  IReadFileTool, IReadFileLinesTool, IReadFileContextTool,
                  IReadFileRangesTool, IReadFunctionTool, IGetFileOutlineTool, IGetFileInfoTool,
                  IFileSearchTool, IFileSearchRegExpTool, IFindFilesTool,
                  ISearchAndReplaceTool, IGetProblemsTool,
                  IKnowledgeTool, IWebFetchTool)

Review:         ReviewAssistantTools.getTools() → ToolComposer.from(
                  (same as Explain)

UnitTest:       UnitTestAssistantTools.getTools() → ToolComposer.from(
                  IAnalyzeFileStructureTool, IGetCodeChunkTool, IResolveIdentifierTypeTool,
                  IReadFileTool, IReadFileLinesTool, IReadFileContextTool,
                  IReadFileRangesTool, IReadFunctionTool, IGetFileOutlineTool, IGetFileInfoTool,
                  IFileSearchTool, IFileSearchRegExpTool, IFindFilesTool,
                  ISearchAndReplaceTool, IGetProblemsTool,
                  IAnalyzeCodeForTestingTool, ICreateTestFileTool,
                  IAddTestMethodTool, IGenerateTestCasesTool,
                  IKnowledgeTool)

QueryBuilder:   QueryBuilderAssistantTools.getTools() → ToolComposer.from(
                  IReadFileTool, IReadFileLinesTool, IReadFileContextTool,
                  IReadFileRangesTool, IReadFunctionTool, IGetFileOutlineTool, IGetFileInfoTool,
                  IFileSearchTool, IFileSearchRegExpTool, IFindFilesTool,
                  ISearchAndReplaceTool, IGetProblemsTool,
                  IKnowledgeTool, IWebFetchTool, IDatabaseTool)

QuickFix:       QuickFixAssistantTools.getTools() → ToolComposer.from(
                  ICodeContextTool, IReadPersistFileTool, IValidateQuickFixTool)

Completion:     (no tools — stateless)
```

### 2.4 Memory Configuration

- **Single shared store:** `InMemoryChatMemoryStore` in `ServoyAiModel`
- **ID format:** `<solutionName><assistantSuffix>` (e.g., `"MySolution-vibe"`)
- **Window size:** 40 messages for all assistants, **except Documentation which uses 100**
- `clearAllMemories(solutionName)` iterates all `AssistantType.values()` and deletes each memory ID

### 2.5 Model Providers

Two providers supported, selected in preferences:

- `OPENAI` — `OpenAiStreamingChatModel` / `OpenAiChatModel`
- `GEMINI` — `GoogleAiGeminiStreamingChatModel` / `GoogleAiGeminiChatModel`

Completion assistant uses hardcoded fast models: `gpt-4o-mini` (OpenAI) / `gemini-2.0-flash` (Gemini).
`AIModelTools` provides dynamic model listing (cached) for the preference page.
`AIModelProvider` implements `com.servoy.eclipse.core.ai.AIModelProvider` for integration with Servoy core.

---

## 3. Tool Classes

### 3.1 Overview

All tool classes live under `com.servoy.eclipse.servoypilot.tools`.

**Factory classes** (`tools/`):

- `ToolComposer` — Static factory: builds `Map<ToolSpecification, ToolExecutor>` from tool interfaces via JDK `Proxy` + `MethodHandles`
- `VibeCodingAssistantTools` — `getTools()` → `ToolComposer.from(25 interfaces)`
- `DocumentationAssistantTools` — `getTools()` → `ToolComposer.from(11 interfaces)`
- `ExplainAssistantTools` — `getTools()` → `ToolComposer.from(14 interfaces)`
- `ReviewAssistantTools` — `getTools()` → `ToolComposer.from(14 interfaces)`
- `UnitTestAssistantTools` — `getTools()` → `ToolComposer.from(21 interfaces)`
- `QueryBuilderAssistantTools` — `getTools()` → `ToolComposer.from(15 interfaces)`
- `QuickFixAssistantTools` — `getTools()` → `ToolComposer.from(3 interfaces)`

**Internal services** (`tools/`):
- `ResourceService` — workspace resource service (used by eclipse tool interfaces)
- `SearchService` — text/regex search service (used by eclipse tool interfaces)

**Tool interfaces by package:**

`tools/codeanalysis/` — `IAnalyzeFileStructureTool`, `IGetCodeChunkTool`, `IResolveIdentifierTypeTool`

`tools/documentation/` — `IGetCurrentSelectionTool`, `IGetDocumentationForIdentifiersTool`, `IApplyDocumentationsTool`, `IGetAvailableMembersForTypeTool`, `IGetDocumentationForTypeMemberTool`

`tools/eclipse/` — `IFileSearchTool`, `IFileSearchRegExpTool`, `IFindFilesTool`, `ISearchAndReplaceTool`, `IGetProblemsTool`

`tools/filereading/` — `IReadFileTool`, `IReadFileLinesTool`, `IReadFileContextTool`, `IReadFileRangesTool`, `IReadFunctionTool`, `IGetFileOutlineTool`, `IGetFileInfoTool`

`tools/testgeneration/` — `IAnalyzeCodeForTestingTool`, `ICreateTestFileTool`, `IAddTestMethodTool`, `IGenerateTestCasesTool`

`tools/core/forms/` — `IGetFormsTool`, `IOpenFormTool`, `IDeleteFormsTool`

`tools/core/relation/` — `IGetRelationsTool`, `IOpenRelationTool`, `IDeleteRelationsTool`

`tools/core/valuelist/` — `IGetValueListsTool`, `IOpenValueListTool`, `IDeleteValueListsTool`

`tools/core/style/` — `IGetStylesTool`, `IOpenStyleTool`, `IDeleteStyleTool`

`tools/component/bootstrap/button/` — `IButtonComponentTool` (`listButtons`, `addButton`, `updateButton`, `deleteButton`, `getButtonInfo`)

`tools/component/bootstrap/label/` — `ILabelComponentTool` (`listLabels`, `addLabel`, `updateLabel`, `deleteLabel`, `getLabelInfo`)

`tools/quickfix/` — `ICodeContextTool`, `IReadPersistFileTool`, `IValidateQuickFixTool`

`tools/utility/` — `IDatabaseTool`, `IKnowledgeTool`, `ITargetTool`, `IWebFetchTool`

### 3.2 Tool DTOs (`tools/dto/`)

- `DocumentationItem` — JSDoc item with line ranges for `applyDocumentations`
- `QuickFixResult` — Quick fix result
- `SourceEdit` — Source edit operation DTO

### 3.3 Key Tool Details

**Code analysis interfaces** (`tools/codeanalysis/`) — used by VibeCoding, Documentation, UnitTest:
- `analyzeFileStructure(pathOrName)` — DLTK-cached symbol extraction; accepts form names, scope names, full paths
- `getCodeChunk(pathOrName, symbolName, chunkNumber, startLine, chunkSize)` — Three modes by priority: TARGETED (symbolName) → SEQUENTIAL (chunkNumber, 1-based) → DIRECT (startLine, 0-based). Chunk sizes: `SMALL`=50, `MEDIUM`=100, `LARGE`=200 lines (default)
- `resolveIdentifierType(identifier, pathOrName)` — delegates to `CodeContextService.resolveIdentifierType()`

**Documentation interfaces** (`tools/documentation/`) — exclusive to Documentation assistant:
- `getCurrentSelection()` — requires active editor via `SelectionTracker`; returns FILE, START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH, code with 0-based line numbers
- `getDocumentationForIdentifiers(identifiers[], filePath?)` — optional filePath; works without editor when filePath provided
- `applyDocumentations(filePath, items[])` — line-based INSERT/REPLACE; UUID protection via `DocumentationValidator`; backs up via `FileModificationTracker`; timestamp change detection via `SelectionTracker.getPromptTimestamp()`
- `getAvailableMembersForType(typeName, memberFilter?)` — regex filter, 50-member max
- `getDocumentationForTypeMember(typeName, memberName)` — full docs including all overloads

**Eclipse interfaces** (`tools/eclipse/`) — used by all assistants except QuickFix:
- `fileSearch` / `fileSearchRegExp` — Eclipse text search engine via `SearchService`
- `findFiles` — glob-based file finder via `ResourceService`
- `searchAndReplace` — multi-file search and replace (triggers `FileModificationTracker`)
- `getProblems` — Eclipse Problems view (errors, warnings, info)
- Shared normalization logic in `EclipseToolsHelper` singleton

**File reading interfaces** (`tools/filereading/`) — used by Explain, Review, UnitTest, QueryBuilder:
- `readFile` — full file with line numbers; 100KB size limit
- `readFileLines` — line range (1-based, max 500 lines)
- `readFileContext` — smart window around a center line (default ±30 lines)
- `readFileRanges` — multiple non-contiguous ranges in one call
- `readFunction` — complete function body by name (brace-counting)
- `getFileOutline` — function names with line numbers (regex-based)
- `getFileInfo` — metadata only (size, line count, last modified)
- Shared LRU cache (50 files, timestamp-invalidated) and file resolution in `FileReadingToolsHelper` singleton

**Web fetch interface** (`tools/utility/IWebFetchTool`) — used by Explain, Review, QueryBuilder:
- `fetch_webpage` — fetches `https://docs.servoy.com/` only; 10s timeout, 500KB limit; HTML stripped to plain text

**Test generation interfaces** (`tools/testgeneration/`) — exclusive to UnitTest:
- `analyzeCodeForTesting` — file structure analysis or inline code detection
- `createTestFile` — creates `test_xxx.js` scope file via `TestFileService`
- `addTestMethod` — adds `test_` method with auto-generated UUID `@properties` annotation
- `generateTestCases` — suggests happy path / edge case / error case test names (no file creation)

### 3.4 `ToolComposer` — Interface-Based Tool Registration

`ToolComposer` solves the LangChain4j limitation where `ToolSpecifications.toolSpecificationsFrom()` uses `getDeclaredMethods()` on the registered object — finding only methods in that class's bytecode, not interface default methods.

**`ToolComposer.from(Class<?>... toolInterfaces)` algorithm:**

For each interface:
1. `getDeclaredMethods()` on the **interface itself**
2. Filter methods annotated with `@Tool`
3. Create a JDK `Proxy`; invocation handler dispatches via `MethodHandles.privateLookupIn(iface, lookup()).unreflectSpecial(method, iface).bindTo(proxy).invokeWithArguments(args)`
4. Build `ToolSpecification` via `ToolSpecifications.toolSpecificationFrom(method)`
5. Build `ToolExecutor` via `new DefaultToolExecutor(proxyInstance, method)`
6. Put `(spec, executor)` into the result `Map`

**Single source of truth:** `@Tool`/`@P` annotations live only on the interface default methods. Helper singletons (e.g., `FileReadingToolsHelper`, `EclipseToolsHelper`, `FormToolsHelper`) hold the heavy implementation logic.

---

## 4. Services Layer

All services under `com.servoy.eclipse.servoypilot.services`.

- `CodeContextService` — type resolution, identifier offset, DLTK selection engine, API doc extraction (TypeCreator integration)
- `CodeChunkReader` — reads JS files in SMALL/MEDIUM/LARGE chunks; singleton
- `FileStructureService` — DLTK-backed symbol extraction with JSDoc detection; singleton
- `FilePathResolver` — resolves form/scope names and partial paths to `IFile`; singleton
- `CompareEditorService` — opens Eclipse compare editor for file diffs
- `DatabaseSchemaService` — Servoy database server and table schema access
- `FormService` — form-level operations
- `RelationService` — relation operations
- `ValueListService` — valuelist operations
- `StyleService` — style operations
- `TargetService` — current target (solution/module) management
- `TestFileService` — creates and manages JSUnit test scope files
- `ParserService` — DLTK JS parser wrapper for code validation
- `CodeFormattingService` — code formatting utilities
- `BootstrapComponentService` — Bootstrap component operations
- `InstructionsLoadService` — loads knowledge base from `.servoy/` or bundle
- `InstructionsSaveService` — creates/populates `.servoy/` directory

**Service DTOs** (`services/dto/`): `CodeChunk`, `FileStructure`, `SymbolInfo`

**Documentation service** (`services/documentation/`): `DocumentationValidator` — UUID extraction/restoration + JSDoc syntax validation

### 4.1 `CodeContextService` Details

- `resolveIdentifierType(identifier, IFile)` — three layers: DLTK inference → JSDoc `@type` fallback (ownership-validated) → function/parameter detection
- `extractServoyApiDocumentation(identifier, selection)` — three paths:
  1. Solution functions → `ScriptdocContentAccess` (JSDoc from code)
  2. Servoy API → `ScriptObjectRegistry` (XML-based)
  3. Fallback → `TypeCreator.findType()`

### 4.2 TypeCreator scriptingName Mapping

`mapClassNameToScriptingName()` in `CodeContextService` maps DLTK Java class names to TypeCreator scriptingNames:
`JSApplication`→`application`, `JSDatabaseManager`→`databaseManager`, `JSSecurity`→`security`, `JSI18N`→`i18n`, `JSUtils`→`utils`, `JSForm`→`controller`, `JSEventsManager`→`eventsManager`, `JSSolutionModel`→`solutionModel`

---

## 5. Chat View Architecture

### 5.1 Package: `chatview/parts/`

- `ChatView` — SWT view; HTML/CSS/JS rendering via browser; assistant selector combo
- `ChatViewPresenter` — business logic: messaging, assistant switching, solution events, file tracking
- `FileModificationTracker` — thread-safe singleton; `Map<String, String>` path→originalContent; listener notifications
- `MarkdownParser` — Markdown → HTML with syntax highlighting
- `BrowserFunctionWrapper` — abstracts SWT `BrowserFunction`
- `CodeEditingService` / `ApplyPatchWizardHelper` — patch application
- `FileCompareEditorInput` — Eclipse compare input; reflection-based `DiffNode` creation

### 5.2 Presenter Key Responsibilities

- **`@PostConstruct init()`** — registers `FileModificationTracker` listener; registers `IActiveProjectListener` via reflection (method is on concrete `ServoyModel`, not `IServoyModel`)
- **`onSolutionActivated(name)`** — clears all memories → loads knowledge base → clears UI → shows notification
- **`onSendUserMessage(text)`** — sets prompt timestamp (Documentation only), runs `executeRequest()` in background Job, streams via `onPartialResponse`
- **`refreshViewFromMemory()`** — reads `sharedMemoryStore`; filters to `UserMessage` + `AiMessage` only (skips `SystemMessage` and `ToolExecutionResultMessage`)

### 5.3 Memory Flow

```
User sends message
  → background Job → currentAssistant.executeRequest(currentMemoryId, text)
  → LangChain4j auto-adds to sharedMemoryStore
  → onPartialResponse: accumulate tokens → update UI incrementally

Assistant/solution switch
  → refreshViewFromMemory() reads sharedMemoryStore
  → filters System + ToolResult messages out
  → renders User + AI messages only
```

### 5.4 Modified Files Tracking (GitHub Copilot-style)

`FileModificationTracker` (singleton) → `ChatViewPresenter` (Keep/Undo/KeepAll/UndoAll) → `ChatView` HTML/JS (collapsible section, ✓ ✗ 🗑️ per-file actions)

---

## 6. Context Menu Integration

`ServoyAiContextMenuHandler` handles 6 editor right-click commands:
- `.context.review` → `REVIEW`
- `.context.generateDocs` → `DOCUMENTATION` (special: sends generic message, sets prompt timestamp)
- `.context.generateTests` → `UNIT_TEST`
- `.context.explain` → `EXPLAIN`
- `.context.queryBuilder` → `QUERY_BUILDER`
- `.context.debug` → TODO (not yet implemented)

All use `ChatViewActivator.openAndSwitchToAssistant(AssistantType, message)`.

`ISelectionAIHandler` — inner interface with 4 cases: `viewTextSelection`, `smallTextSelection` (≤100 lines, inline), `largeTextSelection` (>100 lines, reference only), `fileSelection`.

`ChatViewActivator` — `populateAssistantSelector()` → `switchToAssistant()` → 150ms `timerExec` → `onSendUserMessage()`

`SelectionTracker` — singleton; `getCurrentSelection()` → `Optional<SelectionInfo>`, `setPromptTimestamp(long)`

---

## 7. QuickFix Architecture

```
DLTK error marker
  → ServoyAICorrectionProcessor.computeQuickAssistProposals()
  → ServoyAIQuickFixResolution (project, file, offset)
  → User clicks lightbulb
  → QuickFixAssistant.fix() [synchronous ChatModel, not streaming]
  → ICodeContextTool used by AI
  → Structured fix returned and applied to file
```

Components: `ServoyAICorrectionProcessor`, `ServoyAIQuickFixResolution`, `ServoyAIQuickFixGenerator`, `QuickFixPresenter`, `QuickFixRequest`, `InlineQuickFixPreviewManager`

---

## 8. System Prompts

8 prompt files in knowledgebase bundle `resources/system-prompts/`:
`vibe-coding.txt`, `documentation.txt`, `explain.txt`, `review.txt`, `unittest.txt`, `query-builder.txt`, `quickfix.txt`, `completion.txt`

**Loading priority:** solution-specific `.servoy/system-prompts/<name>.txt` overrides bundle default. Override triggers `Activator.clearServoyAiModel()`.

**No template variables** — avoid `{{variable}}` syntax; LangChain4j scans the entire prompt for `{{...}}` patterns.

**UUID Protection (RULE ZERO)** — all 8 prompts forbid AI from modifying UUIDs (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). Also enforced at tool level via `DocumentationValidator`.

---

## 9. Knowledge Base Architecture

RAG with local ONNX embeddings for offline semantic search (knowledgebase bundle).

- `KnowledgeBaseManager` — facade; discovers KB in solution, falls back to bundle default
- `ServoyEmbeddingService` — ONNX vector search (BGE-small-en-v1.5), `InMemoryEmbeddingStore`, similarity threshold 0.8
- `RulesCache` — `Map<String, String>` intent→markdown; `getRules(intent, projectName)` with `{{PROJECT_NAME}}` substitution
- `KnowledgeBaseStartup` — Eclipse startup listener

**Bundle resources layout:**
```
resources/
  embeddings/   — embeddings.list + *.txt per category
  rules/        — rules.list + *.md per category
  system-prompts/ — 8 .txt files
```

---

## 10. Preferences

`PreferenceConstants.ModelKind`: `NONE`, `OPENAI`, `GEMINI`

Keys: `openaiApiKey`, `geminiApiKey`, `openaiModel`, `geminiModel`, `defaultModel`

`AiConfiguration.isValid()` — checks provider selected and API key non-empty.

---

## 11. Configuration Files

- `plugin.xml` — views, preference pages, 6 context menu commands, `ServoyAICorrectionProcessor`, `fragment.e4xmi`
- `fragment.e4xmi` — ChatView part descriptor (ID: `com.servoypilot.chatview`), toolbar, keybindings
- `build.properties` — standard OSGi source/bin/includes

---

## 12. Design Patterns

- **Presenter** — `ChatView` + `ChatViewPresenter`
- **Facade** — `ServoyAiModel`, `KnowledgeBaseManager`
- **Strategy** — AI provider selection (OpenAI / Gemini)
- **Reflection Proxy** — `IActiveProjectListener` via `ServoyModel` (not on `IServoyModel` interface)
- **Dependency Injection** — E4 DI (`@Inject`, `@PostConstruct`, `@PreDestroy`) in `ChatViewPresenter`
- **Job Pattern** — background `Eclipse Job` for all AI calls and file operations
- **Service Layer** — business logic in `services/` package
- **Singleton** — `FileStructureService`, `FilePathResolver`, `CodeChunkReader`, `SelectionTracker`, all tool helpers
- **Single Source of Truth** — one `sharedMemoryStore` for all assistants (ID-based isolation); `@Tool`/`@P` annotations only on interfaces

---

## 13. Threading Model

- **UI Thread (SWT)** — `ChatView` rendering, user input, combo updates
- **Background Job (Eclipse)** — all AI `executeRequest()` calls, streaming callbacks, file operations, KB loading

Synchronization: `UISynchronize.asyncExec()` in presenter; `Display.getDefault().asyncExec()` in `ChatViewActivator`; `Display.timerExec(150, ...)` sequences assistant switch → message send.

---

## 14. Tool → Assistant Matrix

All assistants use ToolComposer-based interfaces.

```
                              Vibe  Docs  Exp  Rev  Unit  QB   QF
Code analysis (3 interfaces)   ✅    ✅               ✅
Documentation (5 interfaces)         ✅
File reading (7 interfaces)               ✅    ✅    ✅    ✅
Eclipse (5 interfaces)         ✅    ✅    ✅    ✅    ✅    ✅
Test generation (4 interfaces)                        ✅
Core: forms/relations/         ✅
  valuelists/styles (12)
Bootstrap components (2)       ✅
IDatabaseTool                  ✅                           ✅
IKnowledgeTool                 ✅         ✅    ✅    ✅    ✅
ITargetTool                    ✅
IWebFetchTool                             ✅    ✅          ✅
QuickFix (3 interfaces)                                          ✅
```

---

## 15. Extending the System

### 15.1 Adding a New Assistant

1. Add value to `AssistantType` with display name and memory suffix
2. Create interface extending `IAssistant`
3. Create `XxxAssistantTools` factory with `getTools()` → `ToolComposer.from(...)`
4. Add getter + `createXxxServices()` in `ServoyAiModel`
5. Add `<name>.txt` to knowledgebase bundle `resources/system-prompts/` and getter in `SystemPrompts`

### 15.2 Adding New Tools

1. Create an interface in the appropriate `tools/` sub-package
2. Declare a `default` method with `@Tool` and `@P` — single source of truth
3. Implement logic inline or by calling a helper singleton
4. Add the interface class to `ToolComposer.from(...)` in the relevant `XxxAssistantTools.getTools()`

### 15.3 Adding a New AI Provider

1. Add value to `PreferenceConstants.ModelKind`
2. Update `AiConfiguration` for API key + model resolution
3. Add builder methods in `ServoyAiModel` for streaming and non-streaming models
4. Add cases to all `switch (conf.getSelectedModel())` blocks
5. Update `ServoyPilotPreferencePage` UI

### 15.4 Solution-Specific Customization

- `.servoy/system-prompts/<name>.txt` — overrides bundle prompts; triggers `Activator.clearServoyAiModel()` on load
- `.servoy/rules/` + `.servoy/embeddings/` — custom knowledge base
- Use `ResetInstructionsHandler` / `RefreshInstructionsHandler` menu actions to manage `.servoy/` directory

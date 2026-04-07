# ServoyPilot - Architecture Reference

**Last Updated:** April 6, 2026
**Purpose:** Technical reference for system design and component structure. Code is the source of truth.

---

## 1. OSGi Bundle Structure

The plugin is split into 3 OSGi bundles:

| Bundle | Purpose |
|--------|---------|
| `com.servoy.eclipse.servoypilot` | Main plugin: UI, AI assistants, tools, services |
| `com.servoy.eclipse.servoypilot.langchain4j` | LangChain4j library wrapper |
| `com.servoy.eclipse.servoypilot.knowledgebase` | Knowledge base data: embeddings, rules, system prompts |

---

## 2. AI Assistants

### 2.1 Assistant Types (`AssistantType` enum)

All 7 assistant types are defined in `AssistantType.java`:

| Enum Value | Display Name | Memory Suffix | Access |
|------------|-------------|---------------|--------|
| `VIBE_CODING` | VibeCoding Assistant | `-vibe` | ChatView dropdown |
| `DOCUMENTATION` | Documentation Assistant | `-documentation` | ChatView dropdown |
| `EXPLAIN` | Explain Assistant | `-explain` | ChatView dropdown |
| `REVIEW` | Review Assistant | `-review` | ChatView dropdown |
| `UNIT_TEST` | Unit Test Assistant | `-unittest` | ChatView dropdown |
| `QUERY_BUILDER` | Query Builder Assistant | `-querybuilder` | ChatView dropdown |
| `QUICKFIX` | QuickFix Assistant | `-quickfix` | Programmatic (inline quick fix lightbulb only) |

`AssistantType.values()` populates the ChatView dropdown — all 7 values are iterated. `QUICKFIX` is accessible only via the lightbulb/marker, not from the dropdown.

### 2.2 `IAssistant` Interface

Common interface for all conversational assistants:

```java
TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request);
AssistantType getType();
String getDisplayName();
```

Each assistant interface (e.g., `VibeCodingAssistant`, `DocumentationAssistant`) extends `IAssistant` and is a LangChain4j `AiServices`-generated proxy.

### 2.3 Tool Registration per Assistant (`ServoyAiModel`)

VibeCoding and Documentation use `ToolComposer`-based registration (see Section 3.4).
All other assistants register monolithic tool class instances directly.

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

Explain:        new FileReadingTools(), new EclipseTools(), new KnowledgeTools(), new WebFetchTools()

Review:         new FileReadingTools(), new EclipseTools(), new KnowledgeTools(), new WebFetchTools()

UnitTest:       new CodeAnalysisTools(), new EclipseTools(), new FileReadingTools(),
                new TestGenerationTools(), new KnowledgeTools()

QueryBuilder:   new FileReadingTools(), new EclipseTools(), new KnowledgeTools(),
                new WebFetchTools(), new DatabaseTools()

QuickFix:       new QuickFixTools()
Completion:     (no tools — stateless)
```

### 2.4 Memory Configuration

- **Single shared store:** `InMemoryChatMemoryStore` in `ServoyAiModel`
- **ID format:** `<solutionName><assistantSuffix>` (e.g., `"MySolution-vibe"`)
- **Window size:** 40 messages for all assistants, **except Documentation which uses 100**
- `clearAllMemories(solutionName)` iterates all `AssistantType.values()` and deletes each memory ID

### 2.5 Model Providers

Two providers supported, selected in preferences:

| Provider | Streaming Model | Non-streaming Model |
|----------|----------------|---------------------|
| `OPENAI` | `OpenAiStreamingChatModel` | `OpenAiChatModel` |
| `GEMINI` | `GoogleAiGeminiStreamingChatModel` | `GoogleAiGeminiChatModel` |

- Completion assistant uses hardcoded fast models: `gpt-4o-mini` (OpenAI) / `gemini-2.0-flash` (Gemini)
- `AIModelTools` provides dynamic model listing (cached) for the preference page
- `AIModelProvider` implements `com.servoy.eclipse.core.ai.AIModelProvider` for integration with Servoy core

---

## 3. Tool Classes

### 3.1 Overview

All tool classes live under `com.servoy.eclipse.servoypilot.tools`.

| Class | Package | Tools |
|-------|---------|-------|
| `ToolComposer` | `tools/` | Static factory: builds `Map<ToolSpecification, ToolExecutor>` from tool interfaces via JDK `Proxy` + `MethodHandles` |
| `VibeCodingAssistantTools` | `tools/` | Static factory: `getTools()` calls `ToolComposer.from(25 interfaces)` |
| `DocumentationAssistantTools` | `tools/` | Static factory: `getTools()` calls `ToolComposer.from(11 interfaces)` |
| `CodeAnalysisTools` | `tools/` | `analyzeFileStructure`, `getCodeChunk`, `resolveIdentifierType` (monolithic, used by Explain/Review/UnitTest/QueryBuilder) |
| `CodeContextTools` | `tools/` | `codeContext` |
| `DocumentationTools` | `tools/` | `getCurrentSelection`, `getDocumentationForIdentifiers`, `applyDocumentations`, `getAvailableMembersForType`, `getDocumentationForTypeMember` (monolithic, legacy — superseded by interfaces in `tools/documentation/`) |
| `EclipseTools` | `tools/` | `fileSearch`, `fileSearchRegExp`, `findFiles`, `searchAndReplace`, `getProblems` (monolithic, used by Explain/Review/UnitTest/QueryBuilder) |
| `FileReadingTools` | `tools/` | `readFile`, `readFileLines`, `getFileInfo` |
| `TestGenerationTools` | `tools/` | `analyzeCodeForTesting`, `createTestFile`, `addTestMethod`, `runTests` |
| `ResourceService` | `tools/` | Internal workspace resource service (used by `EclipseTools`) |
| `SearchService` | `tools/` | Internal text/regex search service (used by `EclipseTools`) |
| `IAnalyzeFileStructureTool` | `tools/codeanalysis/` | `@Tool` interface: `analyzeFileStructure` |
| `IGetCodeChunkTool` | `tools/codeanalysis/` | `@Tool` interface: `getCodeChunk` |
| `IResolveIdentifierTypeTool` | `tools/codeanalysis/` | `@Tool` interface: `resolveIdentifierType` |
| `IGetCurrentSelectionTool` | `tools/documentation/` | `@Tool` interface: `getCurrentSelection` |
| `IGetDocumentationForIdentifiersTool` | `tools/documentation/` | `@Tool` interface: `getDocumentationForIdentifiers` |
| `IApplyDocumentationsTool` | `tools/documentation/` | `@Tool` interface: `applyDocumentations` |
| `IGetAvailableMembersForTypeTool` | `tools/documentation/` | `@Tool` interface: `getAvailableMembersForType` |
| `IGetDocumentationForTypeMemberTool` | `tools/documentation/` | `@Tool` interface: `getDocumentationForTypeMember` |
| `IFileSearchTool` | `tools/eclipse/` | `@Tool` interface: `fileSearch` |
| `IFileSearchRegExpTool` | `tools/eclipse/` | `@Tool` interface: `fileSearchRegExp` |
| `IFindFilesTool` | `tools/eclipse/` | `@Tool` interface: `findFiles` |
| `ISearchAndReplaceTool` | `tools/eclipse/` | `@Tool` interface: `searchAndReplace` |
| `IGetProblemsTool` | `tools/eclipse/` | `@Tool` interface: `getProblems` |
| `IGetFormsTool` | `tools/core/forms/` | `@Tool` interface: `getForms` |
| `IOpenFormTool` | `tools/core/forms/` | `@Tool` interface: `openForm` |
| `IDeleteFormsTool` | `tools/core/forms/` | `@Tool` interface: `deleteForms` |
| `IGetRelationsTool` | `tools/core/relation/` | `@Tool` interface: `getRelations` |
| `IOpenRelationTool` | `tools/core/relation/` | `@Tool` interface: `openRelation` |
| `IDeleteRelationsTool` | `tools/core/relation/` | `@Tool` interface: `deleteRelations` |
| `IGetValueListsTool` | `tools/core/valuelist/` | `@Tool` interface: `getValueLists` |
| `IOpenValueListTool` | `tools/core/valuelist/` | `@Tool` interface: `openValueList` |
| `IDeleteValueListsTool` | `tools/core/valuelist/` | `@Tool` interface: `deleteValueLists` |
| `IGetStylesTool` | `tools/core/style/` | `@Tool` interface: `getStyles` |
| `IOpenStyleTool` | `tools/core/style/` | `@Tool` interface: `openStyle` |
| `IDeleteStyleTool` | `tools/core/style/` | `@Tool` interface: `deleteStyle` |
| `IButtonComponentTool` | `tools/component/bootstrap/button/` | `@Tool` interface: `listButtons`, `addButton`, `updateButton`, `deleteButton`, `getButtonInfo` |
| `ILabelComponentTool` | `tools/component/bootstrap/label/` | `@Tool` interface: `listLabels`, `addLabel`, `updateLabel`, `deleteLabel`, `getLabelInfo` |
| `IDatabaseTool` | `tools/utility/` | `@Tool` interface: `listTables`, `getTableInfo` |
| `ITargetTool` | `tools/utility/` | `@Tool` interface: `getTarget`, `setTarget` |
| `IKnowledgeTool` | `tools/utility/` | `@Tool` interface: `getKnowledge` |
| `DatabaseTools` | `tools/utility/` | `listTables`, `getTableInfo` (monolithic, used by QueryBuilder) |
| `KnowledgeTools` | `tools/utility/` | `getKnowledge` (monolithic, used by Explain/Review/UnitTest/QueryBuilder) |
| `WebFetchTools` | `tools/utility/` | `fetch_webpage` (restricted to `https://docs.servoy.com/`) |

### 3.2 Tool DTOs (`tools/dto/`)

| DTO | Purpose |
|-----|---------|
| `DocumentationItem` | JSDoc item with line ranges for `applyDocumentations` |
| `QuickFixResult` | Quick fix result |
| `SourceEdit` | Source edit operation DTO |

### 3.3 Key Tool Details

**`CodeAnalysisTools`** — Shared across VibeCoding, Documentation, UnitTest:
- `analyzeFileStructure(pathOrName)` — DLTK-cached symbol extraction; accepts form names, scope names, full paths
- `getCodeChunk(pathOrName, symbolName, chunkNumber, startLine, chunkSize)` — Three modes by priority:
  - TARGETED: `symbolName` provided → jumps to symbol
  - SEQUENTIAL: `chunkNumber` provided (1-based) → reads that chunk
  - DIRECT: `startLine` provided (0-based) → reads from that line
  - Chunk sizes: `SMALL`=50 lines, `MEDIUM`=100 lines, `LARGE`=200 lines (default)
- `resolveIdentifierType(identifier, pathOrName)` — Delegates to `CodeContextService.resolveIdentifierType()`

**`DocumentationTools`** — Exclusive to Documentation assistant:
- `getCurrentSelection()` — Requires active editor via `SelectionTracker`; returns FILE, START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH, code with 0-based line numbers
- `getDocumentationForIdentifiers(identifiers[], filePath?)` — Optional filePath; works without editor when filePath provided
- `applyDocumentations(filePath, items[])` — Line-based INSERT/REPLACE; UUID protection via `DocumentationValidator`; backs up via `FileModificationTracker`; timestamp change detection via `SelectionTracker.getPromptTimestamp()`
- `getAvailableMembersForType(typeName, memberFilter?)` — Regex filter, 50-member max, no editor needed
- `getDocumentationForTypeMember(typeName, memberName)` — Full docs including all overloads, no editor needed

**`EclipseTools`** — Workspace search and navigation:
- `fileSearch` / `fileSearchRegExp` — Eclipse text search engine
- `findFiles` — Glob-based file finder
- `searchAndReplace` — Multi-file search and replace (triggers `FileModificationTracker`)
- `getProblems` — Eclipse Problems view (errors, warnings, info)
- Internally delegates to `ResourceService` and `SearchService`

**`WebFetchTools`** — Fetches `https://docs.servoy.com/` only; 10s timeout, 500KB limit

### 3.4 `ToolComposer` — Interface-Based Tool Registration

`ToolComposer` solves the LangChain4j limitation where `ToolSpecifications` uses `getDeclaredMethods()` on the registered object, which only finds methods physically declared in that class's bytecode — not interface default methods.

**How it works:**

```java
// Usage in VibeCodingAssistantTools / DocumentationAssistantTools:
public static Map<ToolSpecification, ToolExecutor> getTools() {
    return ToolComposer.from(IGetFormsTool.class, IOpenFormTool.class, ...);
}

// Registration in ServoyAiModel:
builder.tools(VibeCodingAssistantTools.getTools());
builder.tools(DocumentationAssistantTools.getTools());
```

**`ToolComposer.from(Class<?>... toolInterfaces)` algorithm:**

For each interface:
1. Call `getDeclaredMethods()` on the **interface itself** (not any implementor)
2. Filter methods annotated with `@Tool`
3. Create a JDK `Proxy` instance for the interface; the invocation handler dispatches default methods via `MethodHandles.privateLookupIn(iface, lookup()).unreflectSpecial(method, iface).bindTo(proxy).invokeWithArguments(args)`
4. Build `ToolSpecification` via `ToolSpecifications.toolSpecificationFrom(method)`
5. Build `ToolExecutor` via `new DefaultToolExecutor(proxyInstance, method)`
6. Put `(spec, executor)` into the result `Map`

**Single source of truth:** `@Tool`/`@P` annotations live only on the interface default methods. The helper singletons (e.g., `FormToolsHelper`, `EclipseToolsHelper`) hold the heavy implementation logic, called from the interface defaults.

**Applies to:** `VibeCodingAssistantTools` (25 interfaces) and `DocumentationAssistantTools` (11 interfaces).
All other assistants (Explain, Review, UnitTest, QueryBuilder, QuickFix) use direct monolithic class instances and are unaffected.

---

## 4. Services Layer

All services under `com.servoy.eclipse.servoypilot.services`.

| Service | Purpose |
|---------|---------|
| `CodeContextService` | Type resolution, identifier offset, DLTK selection engine, API doc extraction (TypeCreator integration) |
| `CodeChunkReader` | Reads JS files in SMALL/MEDIUM/LARGE chunks; singleton |
| `FileStructureService` | DLTK-backed symbol extraction with JSDoc detection; singleton |
| `FilePathResolver` | Resolves form/scope names and partial paths to `IFile`; singleton |
| `CompareEditorService` | Opens Eclipse compare editor for file diffs |
| `DatabaseSchemaService` | Servoy database server and table schema access |
| `FormService` | Form-level operations shared by `FormTools` |
| `RelationService` | Relation operations |
| `ValueListService` | ValueList operations |
| `StyleService` | Style operations |
| `TargetService` | Current target (solution/module) management |
| `TestFileService` | Creates and manages JSUnit test scope files |
| `ParserService` | DLTK JS parser wrapper for code validation |
| `CodeFormattingService` | Code formatting utilities |
| `BootstrapComponentService` | Bootstrap component operations |
| `InstructionsLoadService` | Loads knowledge base from `.servoy/` or bundle |
| `InstructionsSaveService` | Creates/populates `.servoy/` directory |

**Service DTOs** (`services/dto/`):
- `CodeChunk` — startLine, endLine, content, chunk progress
- `FileStructure` — file with symbol list; `toFormattedString()`
- `SymbolInfo` — name, type (FUNCTION/VARIABLE), 1-based line number, hasJSDoc

**Documentation service** (`services/documentation/`):
- `DocumentationValidator` — UUID extraction/restoration + JSDoc syntax validation

### 4.1 `CodeContextService` Details

- `resolveIdentifierType(identifier, IFile)` — Three layers: DLTK inference → JSDoc `@type` fallback (with ownership validation) → function/parameter detection
- `getModelElements(filePath, offset)` — Wraps `JavaScriptSelectionEngine2`
- `readWorkspaceFile(filePath)` — File content reader
- `findIdentifierOffset(source, identifier)` — 3-strategy finder
- `extractJSDocType(fileContent, offset)` — Searches backwards for `@type`; validates ownership (checks for intermediate `var` declarations)
- `extractServoyApiDocumentation(identifier, selection)` — Three paths:
  1. Solution functions → `ScriptdocContentAccess` (JSDoc from code)
  2. Servoy API → `ScriptObjectRegistry` (XML-based)
  3. Fallback → `TypeCreator.findType()` (same as code completion)

### 4.2 TypeCreator scriptingName Mapping

DLTK returns Java class names; TypeCreator expects scriptingNames. `mapClassNameToScriptingName()` in `CodeContextService`:

| DLTK Name | TypeCreator Name |
|-----------|-----------------|
| `JSApplication` | `application` |
| `JSDatabaseManager` | `databaseManager` |
| `JSSecurity` | `security` |
| `JSI18N` | `i18n` |
| `JSUtils` | `utils` |
| `JSForm` | `controller` |
| `JSEventsManager` | `eventsManager` |
| `JSSolutionModel` | `solutionModel` |

`controller` is a special case — DLTK already returns the scriptingName directly, no mapping needed.

---

## 5. Chat View Architecture

### 5.1 Package: `chatview/parts/`

| Class | Role |
|-------|------|
| `ChatView` | SWT view; HTML/CSS/JS rendering via browser; assistant selector combo (all `AssistantType.values()`) |
| `ChatViewPresenter` | Business logic: messaging, assistant switching, solution events, file tracking |
| `FileModificationTracker` | Thread-safe singleton; `Map<String, String>` path→originalContent; listener notifications |
| `CompareEditorService` | Opens Eclipse compare editor |
| `CodeEditingService` | Apply patch / code editing |
| `ApplyPatchWizardHelper` | Wizard for patch application |
| `MarkdownParser` | Markdown → HTML with syntax highlighting |
| `BrowserFunctionWrapper` | Abstracts SWT `BrowserFunction` |
| `FileCompareEditorInput` | Eclipse compare input; reflection-based `DiffNode` creation |
| `ChatMessage` / `TextChatMessage` | Chat message DTOs |
| `AssistaiSharedFiles` / `AssistaiSharedFonts` | Shared CSS/font assets |

### 5.2 Presenter Key Responsibilities

- **`@PostConstruct init()`** — Registers `FileModificationTracker` listener; registers `IActiveProjectListener` via reflection (method is on concrete `ServoyModel`, not on `IServoyModel` interface); sets initial `solutionName`/`currentMemoryId` if solution already active
- **`@PreDestroy dispose()`** — Removes listener via reflection
- **`onSolutionActivated(name)`** — Clears all memories → loads knowledge base → clears UI → shows notification
- **`onAssistantChanged(index)`** — Switches `currentAssistant`, updates `currentMemoryId`, refreshes UI
- **`onSendUserMessage(text)`** — Sets prompt timestamp (Documentation only), runs `executeRequest()` in background Job, streams via `onPartialResponse` with token accumulation
- **`populateAssistantSelector()`** — Populates combo from `AssistantType.values()`
- **`refreshViewFromMemory()`** — Reads `sharedMemoryStore.getMessages(currentMemoryId)`; filters to `UserMessage` + `AiMessage` only (skips `SystemMessage` and `ToolExecutionResultMessage`)

### 5.3 Memory Flow (Single Source of Truth)

```
User sends message
  → background Job → currentAssistant.executeRequest(currentMemoryId, text)
  → LangChain4j auto-adds to sharedMemoryStore
  → onPartialResponse: accumulate tokens → update UI incrementally
  → onCompleteResponse: no refresh (streaming already shows full response)

Assistant/solution switch
  → refreshViewFromMemory() reads sharedMemoryStore
  → filters System + ToolResult messages out
  → renders User + AI messages only
```

No refresh after `onCompleteResponse` — avoids unnecessary flickering.

### 5.4 Modified Files Tracking (GitHub Copilot-style)

Three-layer design:
1. `FileModificationTracker` (singleton) — in-memory map; notifies listener on changes
2. `ChatViewPresenter` — handles Keep/Undo/Remove/KeepAll/UndoAll via Eclipse `IFile` API
3. `ChatView` (HTML/JS) — collapsible "Modified files" section; ✓ ✗ 🗑️ per-file actions; Keep All / Undo All

Flow: Tool modifies file → `FileModificationTracker.notifyFileModified()` → listener → `chatView.updateModifiedFilesSection()`

---

## 6. Context Menu Integration

### 6.1 `ServoyAiContextMenuHandler`

Handles 6 editor right-click commands:

| Command ID suffix | Handler | Target Assistant |
|-------------------|---------|----------------|
| `.context.debug` | `handleDebug` | TODO (not yet implemented) |
| `.context.review` | `handleReview` | `REVIEW` |
| `.context.generateDocs` | `handleGenerateDocs` | `DOCUMENTATION` |
| `.context.generateTests` | `handleGenerateTests` | `UNIT_TEST` |
| `.context.explain` | `handleExplain` | `EXPLAIN` |
| `.context.queryBuilder` | `handleQueryBuilder` | `QUERY_BUILDER` |

All use `ChatViewActivator.openAndSwitchToAssistant(AssistantType, message)`.

### 6.2 Selection Handling Strategy (`ISelectionAIHandler`)

Inner interface with 4 cases; each command handler provides its own implementation:
- `viewTextSelection` — selection from Console/Error Log (path starts/ends with `<>`)
- `smallTextSelection` — ≤100 lines → embeds code inline in backticks
- `largeTextSelection` — >100 lines → references file/offset, no inline code
- `fileSelection` — full file selected (`isFullFileSelected` flag)

`handleGenerateDocs` is special: always sends a short generic message (`"Please improve the JSDoc documentation..."`) without embedding any code. Calls `SelectionTracker.setPromptTimestamp()` for change detection in `applyDocumentations`.

### 6.3 `ChatViewActivator`

Utility (`util/ChatViewActivator.java`):
1. Opens/activates ChatView (part ID: `com.servoypilot.chatview`) via `IWorkbenchPage`
2. Retrieves `ChatView` instance via `EPartService` + `MPart`
3. `openAndSwitchToAssistant(type, text)` — `populateAssistantSelector()` → `switchToAssistant()` → 150ms `timerExec` → `onSendUserMessage()`

### 6.4 `SelectionTracker`

Singleton that tracks the active editor's selection. Provides `getCurrentSelection()` → `Optional<SelectionInfo>` and `setPromptTimestamp(long)` for change detection.

---

## 7. QuickFix Architecture

### 7.1 Components (`quickfix/` package)

| Class | Role |
|-------|------|
| `ServoyAICorrectionProcessor` | `IScriptCorrectionProcessor`; DLTK lightbulb entry point |
| `ServoyAIQuickFixResolution` | `IMarkerResolution`; runs the AI fix |
| `ServoyAIQuickFixGenerator` | Builds fix prompt, calls `QuickFixAssistant` |
| `QuickFixPresenter` | Manages fix workflow, applies results |
| `QuickFixProposal` | User-visible proposal |
| `QuickFixRequest` | Fix request DTO |
| `InlineQuickFixPreviewManager` | Inline diff preview |
| `IQuickFixPreviewManager` | Preview manager interface |
| `TextEdit` | Text edit operation DTO |

### 7.2 Flow

```
DLTK error marker
  → ServoyAICorrectionProcessor.computeQuickAssistProposals()
  → ServoyAIQuickFixResolution (project, file, offset)
  → User clicks lightbulb
  → QuickFixAssistant.fix() [non-streaming ChatModel]
  → CodeContextTools.codeContext() used by AI
  → Structured fix returned and applied to file
```

`QuickFixAssistant` uses `ChatModel` (synchronous), not `StreamingChatModel`.

---

## 8. System Prompts

### 8.1 Prompt Files

Located in knowledgebase bundle `resources/system-prompts/`:

| File | Used by |
|------|---------|
| `vibe-coding.txt` | VibeCoding |
| `documentation.txt` | Documentation |
| `explain.txt` | Explain |
| `review.txt` | Review |
| `unittest.txt` | UnitTest |
| `query-builder.txt` | QueryBuilder |
| `quickfix.txt` | QuickFix |
| `completion.txt` | Completion |

### 8.2 Loading Priority

1. **Solution-specific** (highest): `.servoy/system-prompts/<name>.txt`
2. **Bundle default** (fallback): `resources/system-prompts/` in knowledgebase bundle

`SystemPrompts.INSTANCE` is loaded at startup from bundle. `loadFromPath(IFolder)` overrides with solution-specific prompts and calls `Activator.clearServoyAiModel()` to force model recreation.

### 8.3 Static Prompts — No Template Variables

```java
builder.systemMessageProvider(memoryId -> systemPrompt); // static, captured once
```

Avoid `{{variable}}` syntax in prompt text — LangChain4j scans the entire prompt for `{{...}}` patterns. Use `{Object}` in JSDoc examples instead.

### 8.4 UUID Protection (RULE ZERO)

All 8 system prompts contain RULE ZERO: AI must never modify, create, or delete UUIDs (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` format). Also enforced at tool level via `DocumentationValidator`.

---

## 9. Knowledge Base Architecture

### 9.1 Overview (knowledgebase bundle)

RAG with local ONNX embeddings for offline semantic search.

| Component | Purpose |
|-----------|---------|
| `KnowledgeBaseManager` | Facade: discovers KB in solution; falls back to bundle default |
| `ServoyFolderPackageReader` | `IPackageReader` for `.servoy/` folder |
| `ServoyBundlePackageReader` | `IPackageReader` for bundle resources |
| `RulesCache` | In-memory `Map<String, String>` intent → markdown |
| `ServoyEmbeddingService` | ONNX vector search (BGE-small-en-v1.5) |
| `KnowledgeBaseStartup` | Eclipse startup listener |

### 9.2 Loading Strategy

```
Solution activates
  → KnowledgeBaseManager.loadKnowledgeBasesForSolution()
  → discoverKnowledgeBasePackagesInSolution()
      → if .servoy/ exists → ServoyFolderPackageReader
      → else → empty array
  → if empty → loadDefaultKnowledgeBaseFromBundle()
      → ServoyBundlePackageReader("resources")
  → embeddingService.reloadAllKnowledgeBasesFromReaders(readers)
  → RulesCache populated from rules/rules.list
```

### 9.3 Bundle Resources Layout

```
resources/
  embeddings/
    embeddings.list
    forms.txt, relations.txt, valuelists.txt, styles.txt, run_test_embeddings.txt
    bootstrap/buttons.txt, bootstrap/labels.txt
  rules/
    rules.list
    forms.md, relations.md, valuelists.md, styles.md, run_test_embeddings.md
    bootstrap/buttons.md, bootstrap/labels.md
  system-prompts/
    vibe-coding.txt, documentation.txt, explain.txt, review.txt
    unittest.txt, query-builder.txt, quickfix.txt, completion.txt
```

### 9.4 `ServoyEmbeddingService`

- Embedding model: BGE-small-en-v1.5 (ONNX, local, offline)
- Store: `InMemoryEmbeddingStore` (LangChain4j)
- Similarity threshold: 0.8
- `reloadAllKnowledgeBasesFromReaders(readers)` — clears and reloads

### 9.5 `RulesCache`

- Intent key from filename: `forms.md` → `FORMS`, `bootstrap/buttons.md` → `BOOTSTRAP_BUTTONS`
- `getRules(intent)` / `getRules(intent, projectName)` (with `{{PROJECT_NAME}}` substitution)

---

## 10. Preferences

`PreferenceConstants.ModelKind` enum: `NONE`, `OPENAI`, `GEMINI`

| Constant | Key |
|----------|-----|
| `OPENAI_API_KEY` | `"openaiApiKey"` |
| `GEMINI_API_KEY` | `"geminiApiKey"` |
| `OPENAI_MODEL` | `"openaiModel"` |
| `GEMINI_MODEL` | `"geminiModel"` |
| `DEFAULT_MODEL` | `"defaultModel"` |

`AiConfiguration.isValid()` — checks provider selected and API key non-empty.

---

## 11. Configuration Files

### 11.1 `plugin.xml`
- `org.eclipse.ui.views` — ChatView registration
- `org.eclipse.ui.preferencePages` — `ServoyPilotPreferencePage`
- `org.eclipse.core.commands` — 6 context menu commands
- `org.eclipse.ui.menus` — Context menu contributions for editor
- `org.eclipse.dltk.ui.corrections` — `ServoyAICorrectionProcessor`
- `org.eclipse.e4.workbench.model` — `fragment.e4xmi`

### 11.2 `fragment.e4xmi`
- ChatView part descriptor (part ID: `com.servoypilot.chatview`)
- Toolbar contributions and keybindings

### 11.3 `build.properties`
```properties
source.. = src/
output.. = bin/
bin.includes = META-INF/,.,plugin.xml,icons/,css/,js/,fonts/,darkicons/,fragment.e4xmi,src/main/resources/
```

---

## 12. Design Patterns

| Pattern | Where Used |
|---------|-----------|
| Presenter | `ChatView` + `ChatViewPresenter` |
| Facade | `ServoyAiModel`, `KnowledgeBaseManager` |
| Strategy | AI provider selection (OpenAI / Gemini) |
| Reflection Proxy | `IActiveProjectListener` via `ServoyModel.addActiveProjectListener()` (not on interface) |
| Dependency Injection | E4 DI (`@Inject`, `@PostConstruct`, `@PreDestroy`) in `ChatViewPresenter` |
| Job Pattern | Background `Eclipse Job` for all AI calls and file operations |
| Service Layer | Business logic in `services/` package |
| Singleton | Most services (`FileStructureService`, `FilePathResolver`, `CodeChunkReader`, `SelectionTracker`, etc.) |
| Single Source of Truth | One `sharedMemoryStore` for all assistants; ID-based isolation |

---

## 13. Threading Model

| Thread | Responsibilities |
|--------|----------------|
| UI Thread (SWT) | `ChatView` rendering, user input, combo updates, message display |
| Background Job (Eclipse) | All AI `executeRequest()` calls, streaming callbacks, file operations, KB loading |

Synchronization:
- `UISynchronize.asyncExec()` — used in `ChatViewPresenter` to update UI from background jobs
- `Display.getDefault().asyncExec()` — used in `ChatViewActivator` and tracker listener
- `Display.timerExec(150, ...)` — sequences assistant switch → message send in `ChatViewActivator`

---

## 14. Quick Reference: Tool → Assistant Matrix

VibeCoding and Documentation use `ToolComposer`-based interfaces (see Section 3.4). Other assistants use monolithic class instances.

| Tool / Interface group | VibeCoding | Documentation | Explain | Review | UnitTest | QueryBuilder | QuickFix |
|------------------------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Code analysis interfaces (`IAnalyzeFileStructureTool`, `IGetCodeChunkTool`, `IResolveIdentifierTypeTool`) | ✅ | ✅ | | | | | |
| `CodeAnalysisTools` (monolithic) | | | | | ✅ | | |
| Documentation interfaces (`IGetCurrentSelectionTool`, `IApplyDocumentationsTool`, etc.) | | ✅ | | | | | |
| Eclipse interfaces (`IFileSearchTool`, `IFileSearchRegExpTool`, `IFindFilesTool`, `ISearchAndReplaceTool`, `IGetProblemsTool`) | ✅ | ✅ | | | | | |
| `EclipseTools` (monolithic) | | | ✅ | ✅ | ✅ | ✅ | |
| `FileReadingTools` | | | ✅ | ✅ | ✅ | ✅ | |
| Form interfaces (`IGetFormsTool`, `IOpenFormTool`, `IDeleteFormsTool`) | ✅ | | | | | | |
| Relation interfaces (`IGetRelationsTool`, `IOpenRelationTool`, `IDeleteRelationsTool`) | ✅ | | | | | | |
| ValueList interfaces (`IGetValueListsTool`, `IOpenValueListTool`, `IDeleteValueListsTool`) | ✅ | | | | | | |
| Style interfaces (`IGetStylesTool`, `IOpenStyleTool`, `IDeleteStyleTool`) | ✅ | | | | | | |
| `IButtonComponentTool` | ✅ | | | | | | |
| `ILabelComponentTool` | ✅ | | | | | | |
| `IDatabaseTool` | ✅ | | | | | | |
| `DatabaseTools` (monolithic) | | | | | | ✅ | |
| `IKnowledgeTool` | ✅ | | | | | | |
| `KnowledgeTools` (monolithic) | | | ✅ | ✅ | ✅ | ✅ | |
| `ITargetTool` | ✅ | | | | | | |
| `WebFetchTools` | | | ✅ | ✅ | | ✅ | |
| `TestGenerationTools` | | | | | ✅ | | |
| `QuickFixTools` | | | | | | | ✅ |

---

## 15. Extending the System

### 15.1 Adding a New Assistant

1. Add value to `AssistantType` with display name and memory suffix
2. Create interface extending `IAssistant` with `getType()` / `getDisplayName()` defaults
3. Add getter in `ServoyAiModel` with model creation + `AiServices.builder()` + tool registration
4. Add case to `AssistantType.getModel()` switch
5. Add `<name>.txt` to knowledgebase bundle `resources/system-prompts/`
6. Add getter in `SystemPrompts`

The new assistant automatically appears in the ChatView dropdown.

### 15.2 Adding New Tools

**Option A — ToolComposer pattern (VibeCoding / Documentation assistants):**

1. Create an interface in the appropriate `tools/` sub-package (e.g., `tools/core/forms/IMyTool.java`)
2. Declare a `default` method annotated with `@Tool` and `@P` — this is the single source of truth
3. Implement the logic by calling the relevant helper singleton (e.g., `FormToolsHelper.getInstance().myImpl(...)`)
4. Add `IMyTool.class` to the `ToolComposer.from(...)` call in `VibeCodingAssistantTools.getTools()` or `DocumentationAssistantTools.getTools()`

**Option B — Monolithic class pattern (Explain, Review, UnitTest, QueryBuilder, QuickFix):**

1. Add a method annotated with `@Tool` and `@P` directly to the relevant monolithic class (e.g., `EclipseTools`, `FileReadingTools`)
2. The method is found automatically by LangChain4j via `getDeclaredMethods()` on the registered instance

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
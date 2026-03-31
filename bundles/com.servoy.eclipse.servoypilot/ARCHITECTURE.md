# ServoyPilot - Architecture Reference

**Last Updated:** March 31, 2026  
**Purpose:** Complete technical reference for understanding the system design and component structure

**Status:** 
- ✅ **Anti-Hallucination Prompt Rewrite (Mar 31, 2026):**
  - **ROOT CAUSE:** "Announce before calling" pattern in RULE ONE trained model to write fake tool call lines instead of invoking real tools
  - **REMOVED:** `toolName(param1, ...)` pre-call announcement instruction and all transcript-style examples
  - **REMOVED:** RULE ONE (announce) → replaced with RULE ONE (no fabrication) + RULE TWO (post-call reporting)
  - **ADDED:** RULE ONE — explicit `[FORBIDDEN]` on inventing/guessing workspace contents
  - **ADDED:** RULE TWO — post-call plain-language reporting; mandatory error relay
  - **ADDED:** TOOL OUTPUT FORMATS section — verbatim `analyzeFileStructure` and `getCodeChunk` output so model expects structured data
  - **REWRITTEN:** All 4 examples — describe what to call + what tool returns, not fake pre-written transcripts
  - **ADDED:** `System.out.println` to all tools in `CodeAnalysisTools`, `DocumentationTools`, `EclipseTools` for Eclipse console tracing
  - Prompt: ~290 lines

**Status:** 
- ✅ Multi-Assistant View Switcher implemented and functional
- ✅ **Memory Store Refactoring COMPLETE** - Single source of truth (memory store only)
- ✅ **Memory Refactoring VALIDATED** - Testing complete, system working correctly
- ✅ Code Context Gathering complete (Phases 1-4)
- ✅ **CodeAnalysisTools Migration COMPLETE (Mar 24, 2026)**
- ✅ **Documentation Assistant Enhancement - SESSION 3.1 COMPLETE (Mar 23, 2026)**
- ✅ **Documentation Assistant Enhancement - SESSION 3 COMPLETE (Mar 20, 2026)**
- ✅ **Documentation Assistant Enhancement - SESSION 2 COMPLETE (Mar 18, 2026)**
- ✅ **Documentation Assistant Enhancement - SESSION 1 COMPLETE (Mar 17, 2026)**
- ✅ **Documentation Assistant — Timestamp-based Change Detection (Mar 30, 2026):**
  - **REMOVED:** `contentHash` / `expectedHash` parameter from `applyDocumentations()`
  - **ADDED:** `promptTimestamp` field to `SelectionTracker` — set at prompt time
  - **CHANGE DETECTION:** `IFile.getLocalTimeStamp() > SelectionTracker.getPromptTimestamp()` → reject
  - **SET IN:** `ChatViewPresenter.onSendUserMessage()` (Documentation assistant only) + `ServoyAiContextMenuHandler.handleGenerateDocs()`
  - **FALLBACK:** `createSelectionInfoFromFile(filePath)` called when no active editor selection
- ✅ **Documentation Assistant — Tool Annotation Improvements (Mar 30, 2026):**
  - Improved `@Tool` / `@P` annotations for `getCurrentSelection`, `getDocumentationForIdentifiers`, `applyDocumentations`, `getAvailableMembersForType`, `getDocumentationForTypeMember`, `findFiles`
  - **REMOVED:** Tool Reference section from `documentation.txt` (~70 lines) — now covered by annotations
  - **ADDED:** RULE ONE (announce tool calls + always respond), RULE TWO (forbidden tools)
  - **UPDATED:** STEP 1 — intent-based tool selection (model decides from prompt content)
  - **REMOVED:** `KnowledgeTools` from Documentation Assistant tool portfolio
  - Prompt: 305 lines (down from 371)
  - **CREATED:** New `CodeAnalysisTools.java` class with 3 AI tools
  - **MIGRATED TOOLS:** Moved from CodeContextTools to CodeAnalysisTools
    1. `analyzeFileStructure(pathOrName)` - Analyze file structure with JSDoc status
    2. `getCodeChunk(pathOrName, symbolName, chunkNumber, startLine)` - Read code chunks (3 modes)
    3. `resolveIdentifierType(identifier, pathOrName)` - Resolve identifier types
  - **CODECONTEXTSERVICE ENHANCEMENT:**
    - Added `resolveIdentifierType(identifier, file)` - Public method for type resolution
    - Added `readWorkspaceFile(filePath)` - File content reader
    - Added `findIdentifierOffset(source, identifier)` - 3-strategy identifier finder
    - Added `formatTypeInfo(...)` - Type information formatter
    - Added `extractJSDocType(fileContent, offset)` - JSDoc @type extractor
    - Added `getModelElements(filePath, characterOffset)` - DLTK selection engine wrapper (removed unused lineNumber parameter)
    - Added `getFile(filePath)` - IFile resolver
    - Added `SelectionResult` inner class - DTO for model/foreign elements
  - **CODE SEPARATION:**
    - CodeContextTools - Contains `codeContext()` tool and related methods (UNCHANGED)
    - CodeAnalysisTools - Contains file analysis and type resolution tools (NEW)
    - CodeContextService - Shared service methods for both tool classes (ENHANCED)
  - **CODE QUALITY:**
    - Zero compilation errors
    - Positive conditionals throughout
    - Direct imports, no fully qualified names
    - Comprehensive JavaDoc comments
  - **BENEFITS:**
    - Clean separation of concerns
    - Reusable service methods in CodeContextService
    - Easier maintenance and testing
- ✅ **Documentation Assistant Enhancement - SESSION 3.1 COMPLETE (Mar 23, 2026):**
  - **STATUS:** ✅ FULLY FUNCTIONAL - Editor-Independent Documentation Tools
  - **NEW TOOLS (DocumentationTools.java):**
    1. **getDocumentationForIdentifiers(identifiers[], filePath)** - Enhanced with optional filePath parameter
       - Works WITHOUT active editor when filePath provided
       - Creates SelectionInfo programmatically via FilePathResolver
       - Reuses all existing CodeContextService extraction logic
    2. **getAvailableMembersForType(typeName, memberFilter)** - List type members without documentation
       - Returns lightweight signatures: `getName(): String`, `show(window): void`
       - Regex filtering (default "*"), case-insensitive
       - 50-member threshold with auto-truncation and warning
       - Groups: METHODS / PROPERTIES sections
       - Works WITHOUT any file or editor context
    3. **getDocumentationForTypeMember(typeName, memberName)** - Get full docs for specific member
       - Case-insensitive member matching
       - Returns all overloads with "(1 of N)" indicators
       - Complete signature + description + params + return type + deprecation
       - Works WITHOUT any file or editor context
  - **TYPECREATOR INTEGRATION:**
    - All tools use `TypeCreator.findType()` for type resolution
    - Handles @ServoyDocumented scriptingName mappings (JSApplication→application, JSForm→controller)
    - Added `mapClassNameToScriptingName()` helper for DLTK class name to scriptingName conversion
    - Same type resolution path as code completion (Ctrl+Space) for consistency
  - **CODECONTEXTSERVICE ENHANCEMENT:**
    - Added TypeCreator fallback to `extractServoyApiDocumentation()`
    - PRIMARY PATH: ScriptObjectRegistry (XML-based docs)
    - FALLBACK PATH: TypeCreator.findType() for @ServoyDocumented types
    - Comprehensive debug logging for troubleshooting
  - **KEY FEATURES:**
    - ✅ Works without active editor (all three tools)
    - ✅ Handles JSApplication→application mapping automatically
    - ✅ Regex filtering for large type member lists
    - ✅ Auto-truncation prevents token overflow
    - ✅ Case-insensitive member matching
    - ✅ Multiple overload support
  - **CODE QUALITY:**
    - Zero compilation errors
    - Positive conditionals throughout
    - Direct imports, no fully qualified names
    - Comprehensive JavaDoc comments
  - **TESTING:** 8 new tests added to session3-type-resolution.md (Tests 7.1-7.8)
  - **FILES MODIFIED:**
    - DocumentationTools.java (+310 lines) - 3 new tools, 3 helper methods
    - CodeContextService.java (+120 lines) - TypeCreator fallback, debug logging
    - session3-type-resolution.md (+180 lines) - 8 comprehensive tests
- ✅ **Documentation Assistant Enhancement - SESSION 3 COMPLETE (Mar 20, 2026):**
  - **STATUS:** ✅ FULLY FUNCTIONAL - Type resolution with JSDoc fallback and ownership validation
  - **IMPLEMENTATION:** `resolveIdentifierType(identifier, pathOrName)` tool in CodeContextTools
  - **ARCHITECTURE:** Standalone tool (not wrapper) leveraging proven JavaScriptSelectionEngine2 code path
  - **THREE-LAYER TYPE EXTRACTION:**
    1. **DLTK Inference (Primary):** Uses JavaScriptSelectionEngine2 (same as hover tooltips)
       - Extracts type from `ILocalVariable.getType()` for local variables
       - Extracts type from `IRElement.getName()` for Servoy API types
       - Works for assignments: `var fs = foundset;` → JSFoundSet
    2. **JSDoc @type Fallback:** When DLTK returns no type (uninitialized variables)
       - Searches backwards max 300 chars for `@type {TypeName}` pattern
       - **Ownership Validation:** Checks for intermediate `var` declarations
       - Prevents type stealing: `var a; /** @type {X} */ var b; var c;` → c doesn't get X
    3. **Function/Method Handling:** Special formatting for function declarations
       - Returns "Function" type with parameter list
       - Example: `TYPE: Function, PARAMETERS: (event:JSEvent)`
  - **FILE RESOLUTION:** Uses FilePathResolver (accepts form names, scope names, full paths)
  - **TESTING:** 17 comprehensive tests in session3-type-resolution.md
  - **FILES MODIFIED:** CodeContextTools.java (860 lines)
- ✅ **Documentation Assistant Enhancement - SESSION 2 COMPLETE & TESTED (Mar 18, 2026):**
  - **STATUS:** ✅ FULLY FUNCTIONAL - All 15 tests passed
  - **NEW COMPONENTS:** CodeChunkReader service, CodeChunk DTO, getCodeChunk() tool
  - **THREE READING MODES:** TARGETED, DIRECT, SEQUENTIAL
  - **KEY FEATURES:** Max 200 lines per chunk, line number prefixes, chunk progress tracking
  - **TESTING:** 15 comprehensive test cases, all passed
- ✅ **Documentation Assistant Enhancement - SESSION 1 COMPLETE & TESTED (Mar 17, 2026):**
  - **IMPLEMENTED:** FileStructureService, FilePathResolver, CodeAnalysisTools
  - **IMPLEMENTED:** DTOs - FileStructure, SymbolInfo with line numbers
  - **IMPLEMENTED:** Tool registration for VibeCoding & Documentation assistants
  - **IMPLEMENTED:** Console logging - System.out.println for Eclipse console debugging
  - **TESTED:** All 10 core tests passed - session1-file-structure-analysis.md
  - **CODE QUALITY:** All code follows positive conditional pattern, direct imports
  - **COMPILATION:** Zero errors
  - **STATUS:** ✅ FULLY FUNCTIONAL - Ready for SESSION 2
  - **KEY FEATURES:**
    - Accepts simple names: "testCustomers" → auto-resolves to forms/testCustomers.js
    - Uses DLTK API to find scopes anywhere in project
    - Extracts filename from partial paths and searches
    - Shows line numbers (not character offsets) in output
    - Full console logging for debugging and verification
- ✅ **Documentation Assistant CODE CLEANUP (Mar 10, 2026):** Production-ready cleanup
  - **REMOVED:** Debug code block that called `getCodeContext()` twice in `getDocumentationForIdentifiers()`
  - **REMOVED:** 50+ System.out.println statements - replaced with minimal ServoyLog logging
  - **REMOVED:** Old `applyDocumentation()` method (singular, offset-based) - superseded by line-based `applyDocumentations()`
  - **REMOVED:** `JSDocManipulator.java` - Dead code, not referenced anywhere
  - **CLEANED:** Minimal logging strategy - only essential info/error logs remain
  - **FIXED:** System prompt examples (Examples 1-4) updated to show correct line-based format with INSERT/REPLACE modes
  - **FIXED:** LangChain4j template parsing error - removed `{{timeout: Number, retries: Number}}` from documentation.txt (line 246)
  - **ROOT CAUSE:** LangChain4j scans entire system prompt for `{{variable}}` template patterns, even inside JSDoc examples
  - **SOLUTION:** Changed JSDoc examples to use `{Object}` instead of `{{property: Type}}` syntax to avoid template parser
  - **Files Modified:** DocumentationTools.java (571 lines, reduced from 702), documentation.txt (lines 246, 644)
  - **Compilation Status:** ✅ Zero errors
  - **Components Still Used:** DocumentationValidator (UUID protection), CodeContextService (API doc extraction)
- ✅ **Documentation Assistant LINE-BASED REFACTOR (Mar 9, 2026):** Signature matching replaced with line-based positioning
  - **BREAKING CHANGE:** Moved from signature matching to line-based positioning with INSERT/REPLACE semantics
  - **NEW ARCHITECTURE:** Uses Eclipse ITextSelection line numbers (0-based) directly from editor
  - **LINE-BASED POSITIONING:** AI specifies startLine/endLine with validation strings (startSentence/endSentence)
  - **INSERT MODE:** Empty validation strings → inserts JSDoc before specified line
  - **REPLACE MODE:** Validation strings check content → replaces line range with new JSDoc
  - **NO STRING MATCHING:** Eliminates whitespace fragility and multi-line function issues
  - **VARIABLE DOCUMENTATION:** Full support for file-level variables with proper JSDoc format (description first, then @type)
  - **PACKAGE:** `tools/dto/DocumentationItem` updated to line-based structure
  - **TOOLS UPDATED:** `getCurrentSelection()` returns code with line numbers, `applyDocumentations()` uses line ranges
  - **SYSTEM PROMPT REWRITTEN:** documentation.txt updated with line-based format, variable documentation guidance, emoji-free text markers
  - 50-80% token reduction vs. full-file approach (retained from previous refactor)
  - Eliminates signature matching fragility (whitespace, multi-line declarations)
  - System prompt uses text-based markers ([DO], [DON'T], [YES], [NO]) instead of emojis
- ✅ **Documentation Assistant REFACTORED (Mar 5, 2026):** Structured JSDoc insertion with AST matching
  - **BREAKING CHANGE:** Moved from full-file replacement to structured output
  - **NEW ARCHITECTURE:** AI returns JSON list of documentation items (not full code)
  - **AST-BASED MATCHING:** DLTK parser with strict signature matching (DEPRECATED - replaced by line-based Mar 9)
  - **NEW TOOL:** `applyDocumentations(filePath, items)` - accepts structured list
  - **PACKAGE REORGANIZATION:** `services/documentation/` for service logic, `exceptions/` for custom exceptions
  - **TRIPLE VALIDATION:** UUID preservation + JSDoc syntax + auto-restore on failure
  - Brief AI summaries enforced (1-2 sentences, no JSDoc repetition)
- ✅ **Documentation Assistant Pull-Based Refactor (Mar 3, 2026):** Pull-based documentation retrieval
  - **WORKFLOW:** AI retrieves code first, then selectively requests API docs
  - `getCurrentSelection()` returns code only (no embedded documentation)
  - **TOOL:** `getDocumentationForIdentifiers(["id1", "id2"])` for on-demand API doc lookup
  - AI analyzes code and decides which identifiers need documentation
  - PERFORMANCE OPTIMIZED: Only extracts documentation for requested identifiers
  - Soft limit: 20 identifiers per request (prioritization encouraged)
  - Scalable: handles large files without context overflow
- ✅ **Explain Assistant (Feb 25, 2026):** Context menu integration (from Cristi)
  - FileReadingTools for chunked file reading
  - Automatic assistant switching
  - Separate display/AI messages
- ✅ QuickFix Assistant implemented (non-ChatView, stateful)
- ✅ **Browser Abstraction Layer (Feb 25, 2026):** BrowserWrapper + BrowserFunctionWrapper
  - Supports SWT Browser (Windows/Mac) and Chromium (Linux)
  - All BrowserFunctions migrated to wrapper classes
- ✅ **Delete Icon Removed (Feb 20, 2026):** Message deletion feature completely removed due to UUID/msg-N mismatch bug
- ✅ **Modified Files Tracking (Feb 24, 2026):** GitHub Copilot-style tracking with Keep/Undo/Remove actions
- ✅ **Compare Editor Service (Feb 26, 2026):** Centralized compare editor functionality
  - CompareEditorService in services package
  - Reusable across all agents
  - FileCompareEditorInput with reflection-based DiffNode creation
- ✅ **UUID Protection Rules (Feb 26, 2026):** Added to ALL system prompts
  - RULE ZERO in all 5 prompts (vibe-coding, documentation, explain, quickfix, completion)
  - Text-based emphasis (no emoticons for LLM compatibility)
  - Critical protection against UUID modification/creation/deletion
- ✅ **CodeContextService Moved (Feb 26, 2026):** Relocated to services package
  - More logical organization (context analysis is a service)
  - Peer to CompareEditorService and other services
- ✅ **Chat View Fixes (Mar 2, 2026):** Multiple UI and workflow improvements
  - Race condition fix: clearChatView() now synchronous (no async reinitialization)
  - Streaming placeholder changed from duplicate user message to "_Thinking..._"
  - Empty message filtering in refreshViewFromMemory() prevents blank lines
  - Editor selection clearing after documentation application
  - Solution activation notification moved to top notification bar (not chat content)
- ❌ **Form JS CRUD Tools Removed (Feb 25, 2026):** createFormJS, readFormJS, updateFormJS, deleteFormJS removed from EclipseTools

---

## 🔧 TOOL CLASSES ARCHITECTURE (Updated March 24, 2026)

ServoyPilot provides AI tools through specialized tool classes. Each class groups related functionality and is registered with specific assistants.

### Tool Classes Overview

**1. CodeAnalysisTools** (NEW - Mar 24, 2026)
- **Location:** `com.servoy.eclipse.servoypilot.tools.CodeAnalysisTools`
- **Purpose:** File structure analysis and type resolution
- **Registered with:** VibeCoding Assistant, Documentation Assistant
- **Tools:**
  - `analyzeFileStructure(pathOrName)` - List all symbols with JSDoc status
  - `getCodeChunk(pathOrName, symbolName, chunkNumber, startLine)` - Read code in chunks (3 modes)
  - `resolveIdentifierType(identifier, pathOrName)` - Resolve identifier types
- **Dependencies:** Uses CodeContextService for shared functionality
- **Key Features:**
  - Accepts form names, scope names, or full paths via FilePathResolver
  - All tools work without active editor
  - Console logging for debugging

**2. CodeContextTools**
- **Location:** `com.servoy.eclipse.servoypilot.tools.CodeContextTools`
- **Purpose:** Code context extraction for error analysis
- **Registered with:** QuickFix Assistant, potentially others
- **Tools:**
  - `codeContext(filePath, lineNumber, characterOffset)` - Get context around specific line
- **Key Features:**
  - Returns full function if small (≤40 lines)
  - Returns lines around error for large functions
  - Model element and foreign element processing
  - Designed for error-focused workflows

**3. DocumentationTools**
- **Location:** `com.servoy.eclipse.servoypilot.tools.DocumentationTools`
- **Purpose:** JSDoc generation and API documentation lookup
- **Registered with:** Documentation Assistant ONLY
- **Tools:**
  - `getCurrentSelection()` - Get selected code with line numbers (requires editor)
  - `getDocumentationForIdentifiers(identifiers[], filePath?)` - API doc lookup
  - `applyDocumentations(filePath, items[])` - Apply JSDoc with line-based positioning + timestamp change detection
  - `getAvailableMembersForType(typeName, memberFilter?)` - List type members (signatures only)
  - `getDocumentationForTypeMember(typeName, memberName)` - Get full docs for specific member
- **Key Features:**
  - Line-based JSDoc insertion (INSERT/REPLACE modes)
  - UUID protection via DocumentationValidator
  - TypeCreator integration for API types
  - Works with/without active editor (depends on tool)

**4. EclipseTools**
- **Location:** `com.servoy.eclipse.servoypilot.tools.EclipseTools`
- **Purpose:** Eclipse workspace and Servoy project operations
- **Registered with:** VibeCoding Assistant
- **Categories:**
  - Project operations (list, activate, create)
  - Form operations (create, list, properties, events, inheritance)
  - Element operations (add buttons, fields, labels, tab panels)
  - Relation operations (create, list)
  - ValueList operations (create, list)
- **Key Features:**
  - Full Servoy project manipulation
  - Form inheritance support
  - Multiple element types
  - Validation and error handling

**5. FileReadingTools**
- **Location:** `com.servoy.eclipse.servoypilot.tools.FileReadingTools`
- **Purpose:** File reading for Explain Assistant
- **Registered with:** Explain Assistant
- **Tools:**
  - `readFile(filePath)` - Read complete file (100KB limit)
  - `readFileLines(filePath, startLine, endLine)` - Read specific line range (max 500 lines)
  - `getFileInfo(filePath)` - Get file metadata without reading content
- **Key Features:**
  - Chunked reading for large files
  - Line number support
  - Size limits to prevent token overflow

**6. KnowledgeTools**
- **Location:** `com.servoy.eclipse.servoypilot.tools.KnowledgeTools`
- **Purpose:** Knowledge base queries
- **Registered with:** VibeCoding Assistant, potentially others
- **Tools:**
  - `getKnowledge(query)` - Semantic search over knowledge base
- **Key Features:**
  - ONNX-based vector embeddings
  - 80% similarity threshold
  - Offline operation

**7. ResourceService & Related**
- **Location:** `com.servoy.eclipse.servoypilot.tools.ResourceService`
- **Purpose:** Workspace resource search and analysis
- **Registered with:** VibeCoding Assistant
- **Key Features:**
  - File search by patterns
  - Content search with regex
  - Resource type filtering

### Tool Registration Pattern

Tools are registered in `ServoyAiModel` when creating each assistant:

```java
// VibeCoding Assistant (most tools)
builder.tools(
    new EclipseTools(),
    new CodeAnalysisTools(),
    new KnowledgeTools(),
    new ResourceService()
);

// Documentation Assistant (specialized tools only)
builder.tools(
    new DocumentationTools(),
    new CodeAnalysisTools(),
    new EclipseTools()
);

// Explain Assistant (file reading only)
builder.tools(
    new FileReadingTools()
);
```

### Shared Service Layer

**CodeContextService** (Enhanced Mar 24, 2026)
- **Location:** `com.servoy.eclipse.servoypilot.services.CodeContextService`
- **Purpose:** Shared functionality for tool classes
- **Used by:** CodeAnalysisTools, DocumentationTools
- **Key Methods:**
  - `resolveIdentifierType(identifier, file)` - Type resolution logic
  - `getModelElements(filePath, characterOffset)` - DLTK selection engine wrapper
  - `readWorkspaceFile(filePath)` - File content reader
  - `findIdentifierOffset(source, identifier)` - Identifier finder (3 strategies)
  - `formatTypeInfo(...)` - Type information formatter
  - `extractJSDocType(fileContent, offset)` - JSDoc @type extractor
- **Benefits:**
  - Code reuse across tool classes
  - Single source of truth for type resolution
  - Consistent behavior across tools

### Design Principles

1. **Separation of Concerns:**
   - Each tool class has a clear, focused purpose
   - No overlap in functionality
   - Clean boundaries between classes

2. **Service Layer Pattern:**
   - Complex logic lives in service classes (CodeContextService)
   - Tool classes are thin wrappers that handle parameters and formatting
   - Services are reusable across multiple tool classes

3. **Assistant-Specific Registration:**
   - Documentation Assistant gets only documentation tools (prevents misuse)
   - VibeCoding Assistant gets broad set of tools (general development)
   - Explain Assistant gets only file reading tools (focused purpose)

4. **Editor Independence:**
   - Most tools work without active editor (use FilePathResolver)
   - Only tools that truly need editor selection require it
   - Consistent parameter patterns across tools

### Migration History

**March 24, 2026:** CodeAnalysisTools created
- Migrated `analyzeFileStructure`, `getCodeChunk`, `resolveIdentifierType` from CodeContextTools
- Supporting methods moved to CodeContextService
- CodeContextTools now focuses only on context extraction for error analysis
- Clean separation achieved: analysis vs. context extraction

---

## 🐛 KNOWN BUGS (March 2, 2026)

### ✅ **FIXED: Multiple Chat View Issues (Mar 2, 2026)**

**Issues Resolved:**
1. ✅ Race condition causing message history to disappear when switching assistants
2. ✅ Duplicate user message showing during AI response streaming
3. ✅ Empty lines appearing in chat view when switching back to assistant
4. ✅ Editor selection remaining active after documentation application
5. ✅ "New session started" notification appearing in chat content area

**Root Causes & Fixes:**

1. **Race Condition with Message History:**
   - **Problem:** `clearChatView()` was async reinitializing the entire browser, racing with `refreshViewFromMemory()`
   - **Fix:** Changed to synchronous DOM clearing: `browser.execute("...innerHTML = ''")`
   - **File:** `ChatView.java:219`

2. **Duplicate User Message:**
   - **Problem:** Assistant message placeholder was set to user's message text instead of "Thinking..."
   - **Fix:** Changed `setMessageHtml(assistantMsgId, userMessage)` to `setMessageHtml(assistantMsgId, "_Thinking..._")`
   - **File:** `ChatViewPresenter.java:384`

3. **Empty Lines in Chat:**
   - **Problem:** Empty AI messages stored in memory were being rendered as blank divs
   - **Fix:** Added filter `if (text == null || text.trim().isEmpty()) continue;` in `refreshViewFromMemory()`
   - **File:** `ChatViewPresenter.java:338-341`

4. **Editor Selection After Documentation:**
   - **Problem:** Selection remained spanning newly added JSDoc + partial code
   - **Fix:** Added `clearEditorSelection()` method in `DocumentationTools` to reset selection
   - **File:** `DocumentationTools.java:199-247`

5. **Solution Activation Notification:**
   - **Problem:** Green notification HTML was added as chat message instead of top notification bar
   - **Fix:** Changed from `addMessage()` to `showNotification()` with 5-second duration
   - **File:** `ChatViewPresenter.java:663-667`

### ✅ **FIXED: Compare Editor Not Opening on File Click (Feb 26, 2026)**

**Status:** ✅ RESOLVED

**Fix Applied:**
- Created `CompareEditorService` singleton in services package
- `FileCompareEditorInput` uses reflection to create Eclipse DiffNode
- Added `org.eclipse.compare.structuremergeviewer` to Import-Package in MANIFEST.MF
- Fixed DiffNode constructor call (searches for correct signature via reflection)
- ChatViewPresenter now uses CompareEditorService

**Current Behavior:**
- Click file → Opens Eclipse compare editor ✅
- Shows original content (left) vs. modified content (right) ✅
- User can review changes before Keep/Undo ✅

---

## ✅ COMPLETED - MODIFIED FILES TRACKING (February 24, 2026)

**Implementation Complete:** GitHub Copilot-style file modification tracking with Keep/Undo/Remove actions.

### **Architecture: Three-Layer Design**

**Layer 1: Backend Tracking**
- `FileModificationTracker.java` - Thread-safe singleton
- Stores: `Map<String, String>` (filePath → originalContent)
- In-memory tracking (no temp files)
- Workspace-relative paths: `/ProjectName/forms/file.js`
- Listener notifications for UI updates

**Layer 2: Presenter Logic**
- `ChatViewPresenter.java` - Business logic
- 6 handler methods: onFileClick, onKeepFile, onUndoFile, onRemoveFile, onKeepAll, onUndoAll
- File restoration using Eclipse IFile API
- Integration with FileModificationTracker

**Layer 3: UI Display**
- `ChatView.java` - HTML/CSS/JavaScript UI
- Collapsible "Modified files" section
- File list with hover actions (✓ ✗ 🗑️)
- Keep All / Undo All buttons
- Theme-aware styling (light/dark)

### **File Modification Flow**

```
Tool calls (e.g., searchAndReplace, etc.)
  ↓
Capture original content before modification
  ↓
FileModificationTracker.notifyFileModified(path, original)
  ↓
Tracker stores in LinkedHashMap + notifies listeners
  ↓
ChatViewPresenter.onFileModified() called
  ↓
ChatView.updateModifiedFilesSection() called
  ↓
JavaScript updates DOM with file list
  ↓
User sees "Modified files" section appear
```

### **User Interaction Flow**

**Keep File (✓):**
- User clicks ✓ icon
- File removed from tracking
- Changes stay in file

**Undo File (✗):**
- User clicks ✗ icon
- Original content restored via Eclipse IFile API
- File removed from tracking

**Remove File (🗑️):**
- User clicks 🗑️ icon
- File removed from tracking (dismiss)
- Changes stay in file

**Keep All / Undo All:**
- Batch operations on all tracked files
- Same logic as individual actions

### **Key Features**
- ✅ In-memory tracking (no temp files)
- ✅ Thread-safe operations
- ✅ Auto-clear on solution/assistant switch
- ✅ Theme-aware UI (light/dark)
- ✅ File restoration working (Phase 2)
- 🐛 Compare editor not opening (known bug)

---

## ✅ COMPLETED - MEMORY STORE REFACTORING (February 19, 2026)

**Implementation Complete:** Conversation memory now uses single source of truth (LangChain4j memory store). Eliminated dual storage pattern (UI list + memory store).

**Validation Status:** ✅ COMPLETE - Fully tested and working correctly in production.

### **Architecture Changes**

**Before (Dual Storage):**
- `ChatViewPresenter` maintained `List<ChatMessage> contents` for UI display
- LangChain4j maintained separate `InMemoryChatMemoryStore` for AI context
- Two copies of same data → sync issues, complexity

**After (Single Source):**
- **Removed:** `List<ChatMessage> contents` from ChatViewPresenter
- **Single source:** `sharedMemoryStore` in `ServoyAiModel`
- UI displays filtered view of memory store (User + AI messages only)

### **Memory Store Design**

**Single Shared Store:**
```java
private final ChatMemoryStore sharedMemoryStore = new InMemoryChatMemoryStore();
```

**Memory Isolation via IDs:**
- Format: `<solutionName>-<assistantSuffix>`
- VibeCoding: `"MySolution-vibe"`
- Documentation: `"MySolution-documentation"`
- QuickFix: `"MySolution-quickfix"`
- Completion: No memory (stateless)

**MessageWindowChatMemory Configuration:**
```java
MessageWindowChatMemory.builder()
    .id(memoryId)
    .maxMessages(MAX_MESSAGES) // Static constant: 40
    .chatMemoryStore(sharedMemoryStore)
    .build()
```

### **UI Refresh from Memory**

**refreshViewFromMemory() Method:**
1. Reads: `sharedMemoryStore.getMessages(currentMemoryId)`
2. Filters: Skip `SystemMessage` and `ToolExecutionResultMessage`
3. Displays: Only `UserMessage` and `AiMessage`
4. IDs: Generates `msg-0`, `msg-1`, etc. based on filtered index
5. Renders: Markdown → HTML via existing `MarkdownParser`

**Refresh Triggers:**
- Assistant switched
- Solution switched
- Message deleted
- ~~AI response completed~~ (removed to avoid flickering)

### **Streaming Implementation**

**Token Accumulation:**
```java
StringBuilder accumulatedResponse = new StringBuilder();
.onPartialResponse(partial -> {
    accumulatedResponse.append(partial);  // Accumulate tokens
    view.setMessageHtml(assistantMsgId, accumulatedResponse.toString());
})
.onCompleteResponse(fullResponse -> {
    // No refresh - streaming already shows full response
    // LangChain4j auto-adds to store
})
```

**Why no refresh after complete?**
- Streaming already displays full accumulated response
- Refresh causes unnecessary flickering (clear → rebuild UI)
- Messages persist in UI with UUID-based IDs

### **Message Deletion**

**Status:** Feature removed (February 20, 2026)

**Reason for Removal:**
- UUID vs. msg-N ID mismatch made deletion unreliable
- UI/memory sync issues when 40+ messages accumulated without refresh
- Complexity not justified for limited use case

**What Was Removed:**
- Trash icon in message toolbar (HTML/JavaScript)
- `RemoveMessageFunction` BrowserFunction class
- `ChatViewPresenter.onRemoveMessage()` method
- `.message-toolbar` CSS styling (dark and light themes)

**Alternative:**
- Users can clear entire conversation using "Clear" button in toolbar
- Solution switching auto-clears all messages

### **Solution Switching**

**onSolutionActivated() Flow:**
```java
1. clearAllMemories(oldSolutionName)  // Clear all assistant memories
2. Update solutionName
3. Update currentMemoryId = solutionName + assistantSuffix
4. Load knowledge base from .servoy or bundle
5. Clear UI
6. Show "New session started" notification
```

**Memory Clearing:**
```java
public void clearAllMemories(String solutionName) {
    for (AssistantType type : AssistantType.values()) {
        sharedMemoryStore.deleteMessages(solutionName + type.getMemorySuffix());
    }
}
```

### **Benefits Achieved**

✅ **Single source of truth** - No dual storage confusion  
✅ **Automatic sync** - UI always reflects memory state  
✅ **Simplified code** - Removed `contents` list and all operations on it  
✅ **Assistant isolation** - Independent memories via memoryId  
✅ **Smooth streaming** - Token accumulation works correctly  
✅ **No flickering** - Removed unnecessary refresh after streaming  

### **Testing Status**

✅ **VALIDATION COMPLETE (February 23, 2026):**

All memory refactoring features have been tested and validated in production:

**Tested and Working:**
- ✅ Streaming with token accumulation
- ✅ Assistant switching
- ✅ Solution switching
- ✅ Memory isolation per assistant
- ✅ 40 message limit eviction
- ✅ Error handling edge cases
- ✅ Rapid assistant switching
- ✅ Full end-to-end workflow

**Result:** System is stable and performing as designed.

---

## ✅ COMPLETED - MULTI-ASSISTANT VIEW SWITCHER (February 18, 2026)

**Implementation Complete:** Users can now switch between different AI assistants (VibeCoding, Documentation) within the existing ChatView using a dropdown selector.

**What Was Implemented:**

1. **IAssistant Interface** - Common interface for all chat view assistants:
   - `TokenStream executeRequest(String memoryId, String request)` - Unified method for all assistants
   - `void clearMemory(String memoryId)` - Clear conversation memory
   - `AssistantType getType()` - Returns assistant type enum
   - `String getDisplayName()` - Returns display name for UI

2. **AssistantType Enum** - CHAT and DOCUMENTATION values:
   - `getDisplayName()` - "Chat Assistant", "Documentation Assistant"
   - `getMemorySuffix()` - "-chat", "-documentation"
   - `fromIndex(int)` - Helper for combo selection

3. **Assistant Interfaces Updated**:
   - `VibeCodingAssistant` extends `IAssistant` - implements `executeRequest()` for chat functionality
   - `DocumentationAssistant` extends `IAssistant` - implements `executeRequest()` for documentation generation

4. **ChatView UI**:
   - Added `Combo` widget (200px width) for assistant selection
   - Populated dynamically from `availableAssistants` array
   - Selection listener calls `presenter.onAssistantChanged(index)`

5. **ChatViewPresenter**:
   - `IAssistant currentAssistant` - Reference to active assistant
   - `IAssistant[] availableAssistants` - Array of available assistants
   - `String solutionName` - Current solution name (no parsing needed)
   - `onAssistantChanged(int)` - Switches assistant, updates memory ID, clears UI
   - `populateAssistantSelector()` - Populates combo with display names
   - `onSendUserMessage()` - Uses `currentAssistant.executeRequest()` (polymorphic)

6. **Memory Management**:
   - Each assistant maintains independent LangChain4j memory
   - Memory ID format: `solutionName + assistantType.getMemorySuffix()`
   - Example: `"MySolution-chat"`, `"MySolution-documentation"`
   - Solution switch clears all assistant memories

**Architecture Benefits:**
- ✅ Clean polymorphic design (no instanceof checks)
- ✅ Easy to add new assistants (just implement IAssistant)
- ✅ Single view for all conversational assistants
- ✅ Independent memory per assistant type
- ✅ Solution-scoped isolation

**Remaining Work:**
- Documentation Assistant needs context menu integration for code documentation generation
- Future assistants: Debug, Review, Test Generation (framework ready)

---

## ⚠️ IMPORTANT TODO - KNOWLEDGEBASE BUNDLE CLEANUP REQUIRED

**The `com.servoy.eclipse.servoypilot.knowledgebase` bundle contains OBSOLETE architecture:**

- **Original Design:** SPM (Servoy Package Manager) based - designed to support **multiple knowledge base plugins**
- **Current Reality:** Only ONE knowledge base exists (the knowledgebase bundle itself)
- **Problem:** Architecture supports multi-plugin discovery via extension points, but this is unnecessary complexity
- **Impact:** Dead code, over-engineering, maintenance burden

**REQUIRED ACTIONS:**

1. **Remove SPM-based infrastructure:**
   - `IKnowledgeBaseOperations` interface (extension point interface)
   - `KnowledgeBaseOperationsProvider` (extension point provider)
   - `KnowledgeBaseStartup` (startup listener for discovery)
   - Extension point mechanisms in `plugin.xml`

2. **Simplify to direct implementation:**
   - `KnowledgeBaseManager` should directly use `RulesCache` and `ServoyEmbeddingService`
   - Remove package reader abstraction layers if not needed
   - Keep only: `RulesCache`, `ServoyEmbeddingService`, `ServoyBundlePackageReader`, `ServoyFolderPackageReader`

3. **Verify and remove dead code:**
   - Any unused methods related to multi-plugin discovery
   - Unused package reader implementations
   - Obsolete extension point configurations

**Why This Matters:**
- Simpler architecture = easier maintenance
- Less code = fewer bugs
- Clear implementation = better understanding for future developers
- Current complexity was designed for a use case that never materialized

**Current Workaround:**
The system works correctly despite the over-engineering. The obsolete architecture doesn't break functionality, it just adds unnecessary complexity.

✅ **UUID Protection:** Automatic extraction and restoration  
✅ **Change Detection:** Content hash prevents stale modifications  
✅ **Clean Code:** Removed debug logging, old methods, dead code (Mar 10)  
✅ **Template Safety:** Fixed `{{...}}` in examples to avoid LangChain4j parsing errors  

### **Recent Changes (March 10, 2026):**

**Code Cleanup:**
- Removed debug code that called `getCodeContext()` twice
- Removed 50+ System.out.println statements
- Removed old `applyDocumentation()` method (offset-based)
- Removed `JSDocManipulator.java` (dead code)
- Minimal logging with ServoyLog only

**System Prompt Fixes:**
- Updated Examples 1-4 to show correct line-based format
- Fixed LangChain4j template parsing error: changed `{{timeout: Number, retries: Number}}` to `{Object}` (line 246)
- Fixed `{{host: String, port: Number, timeout: Number}}` to `{Object}` (line 644)
- **Root Cause:** LangChain4j scans entire system prompt for `{{variable}}` patterns as template placeholders

**Components:**
- ✅ DocumentationTools.java (571 lines)
- ✅ DocumentationItem.java (DTO)
- ✅ DocumentationValidator.java (UUID protection)
- ✅ CodeContextService.java (API doc extraction)
- ❌ JSDocManipulator.java (REMOVED - not used)

---

## ✅ COMPLETED - DOCUMENTATION ASSISTANT ENHANCEMENT - SESSIONS 1 & 2 (March 17-18, 2026)

**Goal:** Transform Documentation Assistant from selection-based to scope-aware semantic documentation with intelligent multi-file support.

**Implementation Plan:** 5 sessions (6-8 hours total) leveraging existing DLTK infrastructure.

**Status:** ✅ **SESSIONS 1 & 2 COMPLETE** - All tests passed, ready for SESSION 3 (Type Resolution)

**Strategy:** Build lightweight wrappers around proven DLTK APIs (TypeInferencer2, IModelElement, JavaScriptParserUtil) instead of reimplementing from scratch.

---

### ✅ SESSION 1 COMPLETE & TESTED (March 17, 2026): File Structure Wrapper

**Status:** ✅ All 10 tests passed, zero compilation errors, fully functional

**What Was Implemented:**

**1. FileStructureService** (`services/FileStructureService.java` - 217 lines):
- Singleton service wrapping DLTK `IModelElement.getChildren()` API
- `analyzeFile(IFile)` - Extracts all top-level symbols (functions, variables) with JSDoc status
- `hasJSDocComment()` - Detects JSDoc via `/**` pattern in source text before member position
- `findSymbol()` - Helper for symbol lookup by name
- Uses `IDocument.getLineOfOffset()` for accurate line number calculation
- Thin wrapper - DLTK does all parsing and caching (zero custom AST parsing)

**2. FilePathResolver** (`services/FilePathResolver.java` - 420 lines):
- Intelligent file path resolution with multiple strategies
- Handles form names, scope names, workspace-relative paths, partial paths
- Uses DLTK API to find scopes programmatically (not just filesystem)
- Auto-resolves simple names: "testCustomers" → forms/testCustomers.js
- Returns helpful error messages with suggestions when file not found

**3. DTOs Created** (`services/dto/`):
- `FileStructure.java` (90 lines) - Represents file with symbols, provides `getTotalSymbols()`, `getDocumentedCount()`, `getUndocumentedCount()`, `toFormattedString()`
- `SymbolInfo.java` (85 lines) - Represents individual symbol with name, type (FUNCTION/VARIABLE), line number (1-based), hasJSDoc flag

**4. CodeAnalysisTools** (`tools/CodeAnalysisTools.java` - initially 96 lines):
- NEW tool class (separate from DocumentationTools)
- `analyzeFileStructure(filePath)` - @Tool annotated for LangChain4j
- Returns formatted output: FILE, TOTAL SYMBOLS, DOCUMENTED count, UNDOCUMENTED count, symbol list
- Registered for VibeCoding and Documentation assistants (shared analysis capability)

**5. ServoyAiModel Updates** (`ai/ServoyAiModel.java`):
- Added constant: `DOC_ASSISTANT_MAX_MESSAGES = 100` (was 40)
- Updated `createDocumentationServices()` - uses 100-message memory limit
- Registered `CodeAnalysisTools` for VibeCoding + Documentation assistants
- `DocumentationTools` remains exclusive to Documentation Assistant

**6. Test Infrastructure** (`testworkflows/`):
- `README.md` - Testing guidelines and session dependencies
- `session1-file-structure-analysis.md` - 10 comprehensive test cases, all passed

**Session 1 Files Summary:**
- New files: 5 (FileStructureService, FilePathResolver, 2 DTOs, CodeAnalysisTools)
- Modified files: 1 (ServoyAiModel)
- Total new lines: ~902 lines
- Test results: ✅ 10/10 passed

---

### ✅ SESSION 2 COMPLETE & TESTED (March 18, 2026): Adaptive Chunk Reading

**Status:** ✅ All 15 tests passed, zero compilation errors, fully functional

**What Was Implemented:**

**1. CodeChunkReader** (`services/CodeChunkReader.java` - 237 lines):
- Singleton service for reading JavaScript files in manageable chunks
- **Three reading modes:**
  - `readChunk(IFile, int chunkNumber)` - SEQUENTIAL mode: Read by chunk number (0, 1, 2...)
  - `readSymbol(IFile, String symbolName)` - TARGETED mode: Jump to specific symbol using FileStructureService
  - `readFromLine(IFile, int startLine)` - DIRECT mode: Start from specific line number
- Max 200 lines per chunk (token efficiency)
- Line number prefixes on every line (0-based: "250: function loadCustomers() {")
- Calculates total chunks and marks last chunk
- Performance: < 500ms per chunk read

**2. CodeChunk DTO** (`services/dto/CodeChunk.java` - 116 lines):
- Represents a chunk of code read from a file
- Fields: filePath, startLine, endLine, totalChunks, chunkNumber, content, isLast
- `toFormattedString()` - AI-friendly formatted output with chunk progress indicators
- Handles both chunk-based (chunkNumber 0+) and direct mode (chunkNumber -1)

**3. getCodeChunk() Tool** (added to `CodeAnalysisTools.java` - +109 lines):
- Single tool with parameter-driven mode selection
- **Tool signature:**
  ```java
  getCodeChunk(String pathOrName, String symbolName, Integer chunkNumber, Integer startLine)
  ```
- **Mode selection priority:** TARGETED > DIRECT > SEQUENTIAL
  - If `symbolName` provided → TARGETED mode
  - Else if `startLine` provided → DIRECT mode
  - Else → SEQUENTIAL mode (uses chunkNumber, defaults to 0)
- Integration with FilePathResolver (accepts form/scope names)
- Comprehensive error handling (EOF, symbol not found, invalid parameters)
- Full console logging showing mode selection and execution

**4. Tool Registration**:
- `getCodeChunk()` added to CodeAnalysisTools
- Shared across ALL assistants (VibeCoding, Documentation, Explain, QuickFix)
- Works seamlessly with Session 1 tools (analyzeFileStructure → getCodeChunk workflow)

**5. Test Infrastructure** (`testworkflows/`):
- `session2-adaptive-chunk-reading.md` - 15 comprehensive test cases, all passed
- `largeForm.js` - 800-line test file for multi-chunk testing
- `utils.js` template - 300-line test file for TARGETED mode testing

**Session 2 Files Summary:**
- New files: 2 (CodeChunkReader, CodeChunk DTO)
- Modified files: 1 (CodeAnalysisTools - added getCodeChunk tool)
- Total new lines: ~353 lines (237 + 116)
- Modified lines: +109 in CodeAnalysisTools
- Test results: ✅ 15/15 passed

**Combined Session 1 & 2 Statistics:**
- Total new files: 7
- Total modified files: 2
- Total new code lines: ~1,255
- Total tests passed: 25/25 (100%)

---

### Architecture Principles Established:

**1. Documentation Assistant = Single Entry Point**
- ALL documentation operations handled exclusively by Documentation Assistant
- Other assistants can analyze code (CodeAnalysisTools) but NOT apply documentation

**2. Tool Distribution:**
- **CodeAnalysisTools (Shared):** analyzeFileStructure(), getCodeChunk() - Available to ALL assistants
- **DocumentationTools (Exclusive):** getCurrentSelection(), getDocumentationForIdentifiers(), applyDocumentations() - Documentation Assistant only

**3. Session 1 + Session 2 Integration:**
- AI workflow: `analyzeFileStructure()` discovers symbols → `getCodeChunk()` reads code → generates JSDoc
- FilePathResolver used consistently across both sessions
- Line numbers from Session 1 feed into Session 2 TARGETED and DIRECT modes

**Code Quality Compliance:**
- ✅ All code follows positive conditional pattern (happy path flows naturally inside if-blocks)
- ✅ All imports direct (no fully qualified class names)
- ✅ Single return at method end (all error cases converge to final return statement)
- ✅ Zero compilation errors
- ✅ Comprehensive error handling
- ✅ Full console logging for debugging

**Files Modified:**
- ai/ServoyAiModel.java (added DOC_ASSISTANT_MAX_MESSAGES, registered CodeAnalysisTools, added imports)

**Next Steps:**
- [ ] Execute all 25 tests in session3-type-resolution.md
- [ ] Validate editor-independent tools work correctly
- [ ] Test TypeCreator scriptingName mapping for all Servoy API types
- [ ] FUTURE SESSION 4: Multi-File Workflows (solution-wide scanning, progress tracking)
- [ ] FUTURE SESSION 5: System Prompt Updates & Integration Testing

---

## ✅ COMPLETED - DOCUMENTATION ASSISTANT (March 10, 2026)

**Implementation Complete:** Full workflow for generating JSDoc documentation via AI tool using line-based positioning.

**Last Major Update:** March 10, 2026 - Code cleanup and production readiness

### **Architecture Overview:**

**1. Context Menu Handler** (`ServoyAiContextMenuHandler.handleGenerateDocs`):
- Creates generic message: "Please generate JSDoc documentation for the current selection."
- Opens ChatView and switches to Documentation Assistant
- Sends generic message (NO code or documentation included)
- AI retrieves code and documentation dynamically using tools

**2. Documentation Assistant** (`DocumentationAssistant.java`):
- Interface extends `IAssistant`
- Uses streaming chat model for interactive workflow
- Memory: 40 messages, solution-scoped with `-documentation` suffix
- Registered tools: `DocumentationTools` (3 tools only)

**3. Documentation Tools** (`DocumentationTools.java` - 1088 lines):
- **Tool 1:** `getCurrentSelection()` - Returns code with LINE NUMBERS (0-based)
  - Format: FILE, START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH
  - Each line prefixed with line number (e.g., `0: var customers;`)
  - Eliminates need for offset/length calculations
  - Requires active editor via SelectionTracker
- **Tool 2:** `getDocumentationForIdentifiers(identifiers[], filePath)` - On-demand API doc lookup **[ENHANCED Mar 23]**
  - **NEW:** Optional `filePath` parameter - works WITHOUT active editor when provided
  - Soft limit: 20 identifiers (encourages prioritization)
  - Supports nested identifiers: `"plugins.ngdesktop"`, `"elements.button"`
  - Returns formatted XML documentation for requested identifiers
  - Reports "NOT FOUND" for missing identifiers
  - Uses CodeContextService for extraction
  - **Editor-independent:** Creates SelectionInfo from file via FilePathResolver
- **Tool 3:** `applyDocumentations(filePath, contentHash, items)` - LINE-BASED JSDoc application
  - Accepts List<DocumentationItem> with line ranges
  - INSERT mode: empty validation strings, inserts before specified line
  - REPLACE mode: validates with startSentence/endSentence, replaces line range
  - UUID protection via DocumentationValidator
  - Backs up original file (once per file via FileModificationTracker)
  - Clears editor selection after application
  - Returns success/error messages to AI
- **Tool 4:** `getAvailableMembersForType(typeName, memberFilter)` - List type members **[NEW Mar 23]**
  - Returns lightweight signatures WITHOUT full documentation
  - Regex filtering (default "*" = all), case-insensitive
  - 50-member threshold with auto-truncation and warning
  - Groups output: METHODS / PROPERTIES sections
  - Works WITHOUT any file or editor context
  - Uses TypeCreator.findType() with scriptingName fallback (JSApplication→application)
- **Tool 5:** `getDocumentationForTypeMember(typeName, memberName)` - Get full docs for specific member **[NEW Mar 23]**
  - Case-insensitive member name matching
  - Returns all overloads with "(1 of N)" indicators
  - Complete: signature + description + parameters + return type + deprecation
  - Works WITHOUT any file or editor context
  - Uses TypeCreator.findType() with scriptingName fallback

**4. Supporting Components:**
- **DocumentationItem (DTO):** Record with startLine, endLine, startSentence, endSentence, jsdoc
- **DocumentationValidator:** UUID extraction/restoration + JSDoc syntax validation
- **CodeContextService (953→1169 lines):** API documentation extraction for identifiers **[ENHANCED Mar 23]**
  - **PRIMARY PATH:** ScriptObjectRegistry (XML-based documentation) for standard Servoy APIs
  - **FALLBACK PATH:** TypeCreator.findType() for @ServoyDocumented types (e.g., "controller")
  - **SCRIPTINGNAME MAPPING:** Handles DLTK class name → TypeCreator scriptingName mismatch
    - DLTK returns: "JSApplication" (Java class name)
    - TypeCreator expects: "application" (@ServoyDocumented scriptingName)
    - Solution: `mapClassNameToScriptingName()` helper maps JSApplication→application
  - **THREE EXTRACTION PATHS:**
    1. Solution Functions → `ScriptdocContentAccess.getContentReader()` (JSDoc from code)
    2. Servoy API → `ScriptObjectRegistry.getScriptObjectByName()` → IObjectDocumentation (XML)
    3. Web Components/Services + API Fallback → `TypeCreator.findType()` → Type.getMembers() (same as code completion)
  - **COMPREHENSIVE LOGGING:** Debug output shows which path is taken and why
  - **FILES MODIFIED:** CodeContextService.java (+120 lines for TypeCreator fallback and logging)
- ~~**JSDocManipulator:**~~ REMOVED (Mar 10) - Dead code, not used

**5. System Prompt** (`documentation.txt` - 1091 lines):
- **Line-based format (Mar 9, 2026)** - Uses Eclipse ITextSelection line numbers
- **Fixed (Mar 10, 2026)** - Removed `{{...}}` patterns that triggered LangChain4j template parser
- RULE ZERO: UUID protection (comprehensive instructions)
- 6-STEP WORKFLOW:
  - STEP 1: Call `getCurrentSelection()` to get code with line numbers
  - STEP 2: Analyze code and identify what needs documentation
  - STEP 3: Optionally call `getDocumentationForIdentifiers()` for unclear types
  - STEP 4: Generate JSDoc with accurate types
  - STEP 5: Call `applyDocumentations()` with line-based items
  - STEP 6: Provide brief summary (1-2 sentences)
- Detailed guidance on INSERT vs REPLACE modes
- Four complete example workflows with line-based format
- Text-based markers: [DO], [DON'T], [YES], [NO]

### **Complete Workflow (Line-Based):**

```
1. User: Right-click code → "Generate Docs"

2. Handler: 
   - Creates generic message (no code)
   - Opens ChatView, switches to Documentation Assistant
   - Sends: "Please generate JSDoc documentation for the current selection."

3. AI (STEP 1): Calls getCurrentSelection() tool
   - Receives: FILE, START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH
   - Code with line numbers: "0: var customers;\n1: \n2: function onLoad..."

4. AI (STEP 2): Analyzes code
   - Identifies functions/variables to document
   - Notes line numbers for each item
   - Categorizes identifiers: Standard JS vs. Servoy-specific

5. AI (STEP 3 - OPTIONAL): Calls getDocumentationForIdentifiers(["JSFoundSet", "JSEvent"])
   - Only if Servoy types or unclear identifiers present
   - Skips for standard JS (String, Number, Boolean, Array, etc.)
   - Receives formatted XML with API documentation

6. AI (STEP 4): Generates JSDoc documentation
   - Uses retrieved API docs for accurate types
   - Follows Servoy conventions (JSEvent, JSRecord, JSFoundSet, etc.)
   - Preserves UUIDs exactly (RULE ZERO)

7. AI (STEP 5): Calls applyDocumentations(filePath, hash, [items])
   - Items with line ranges: startLine, endLine, startSentence, endSentence, jsdoc
   - INSERT: empty validation strings → insert before line
   - REPLACE: validation strings → replace line range

8. Tool (applyDocumentations):
   - Validates content hash (change detection)
   - FileModificationTracker.notifyFileModified() → Backup original
   - Processes items bottom-to-top (avoids line shifts)
   - Validates startSentence/endSentence for REPLACE mode
   - Extracts and restores UUIDs automatically
   - Validates JSDoc syntax
   - Applies to file
   - Clears editor selection
   - Returns success/error messages

9. UI:
   - File appears in "Modified files" section
   - User can Keep/Undo/Remove changes
   - Click file to see diff in compare editor
```

### **Key Benefits:**

✅ **Line-Based Positioning:** Direct use of Eclipse line numbers, no offset/length fragility  
✅ **No String Matching:** Eliminates whitespace sensitivity issues  
✅ **Multi-line Functions:** Handled naturally by line ranges  
✅ **Token Efficiency:** 50-80% reduction vs. full-file approach  
✅ **UUID Protection:** Automatic extraction and restoration  
✅ **Change Detection:** Content hash prevents stale modifications  
✅ **Clean Code:** Removed debug logging, old methods, dead code (Mar 10)  
✅ **Template Safety:** Fixed `{{...}}` in examples to avoid LangChain4j parsing errors  
✅ **Editor-Independent:** New tools work without active editor (Mar 23)  
✅ **TypeCreator Integration:** Consistent documentation with code completion (Mar 23)  

### **Recent Changes:**

**March 23, 2026 - SESSION 3.1: Editor-Independent Documentation Tools**
- Enhanced `getDocumentationForIdentifiers()` with optional `filePath` parameter
- Added `getAvailableMembersForType(typeName, memberFilter)` - lightweight member listing with regex filtering
- Added `getDocumentationForTypeMember(typeName, memberName)` - full docs for specific member
- Enhanced CodeContextService with TypeCreator fallback for @ServoyDocumented types
- Added `mapClassNameToScriptingName()` for DLTK→TypeCreator type name mapping
- Added `createSelectionInfoFromFile()` to create SelectionInfo without SelectionTracker
- 50-member threshold with auto-truncation prevents token overflow
- Case-insensitive member matching, multiple overload support
- 8 new tests added (total: 25 tests)
- Files: DocumentationTools.java (+310 lines → 1088 total), CodeContextService.java (+120 lines → 1169 total)

**March 10, 2026 - Code Cleanup:**
- Removed debug code that called `getCodeContext()` twice
- Removed 50+ System.out.println statements
- Removed old `applyDocumentation()` method (offset-based)
- Removed `JSDocManipulator.java` (dead code)

**Components:**
- ✅ DocumentationTools.java (1088 lines, 5 tools)
- ✅ DocumentationItem.java (DTO)
- ✅ DocumentationValidator.java (UUID protection)
- ✅ CodeContextService.java (1169 lines, TypeCreator integration)
- ❌ JSDocManipulator.java (REMOVED)

---

## 📚 LANGCHAIN4J TEMPLATE SYSTEM (March 10, 2026)

**Understanding:** How LangChain4j processes system prompts with template variables.

### **The Template Processing Pipeline**

When you configure an assistant with a system prompt:

```java
builder.systemMessageProvider(memoryId -> systemPrompt);
```

LangChain4j does the following:

1. **Loads the entire prompt text** (e.g., from `documentation.txt`)
2. **Scans for `{{variable}}` patterns** across the ENTIRE text
3. **Expects values for ALL found variables** 
4. **Renders the template** by replacing `{{variable}}` with actual values
5. **Sends the rendered text** to the AI model

**Critical Understanding:** The template parser does NOT distinguish between:
- Instructions for the AI
- Code examples
- JSDoc samples
- Literal text

### **The Problem We Encountered**

**System Prompt Example (documentation.txt line 246):**
```javascript
/**
 * Configuration object for database connection.
 * @type {{timeout: Number, retries: Number}}
 */
```

**What We Intended:** JSDoc example for the AI to learn from

**What LangChain4j Saw:** Template variable named `timeout: Number, retries: Number` that needs a value

**Result:** 
```
java.lang.IllegalArgumentException: Value for the variable 'timeout: Number, retries: Number' is missing
```

### **Current Implementation (Static Prompts)**

```java
// In ServoyAiModel.createDocumentationServices()
String systemPrompt = SystemPrompts.INSTANCE.getDocumentationPrompt();
builder.systemMessageProvider(memoryId -> systemPrompt);
```

**Behavior:**
- Prompt loaded ONCE when assistant is created
- Lambda captures the static prompt string
- `memoryId` parameter is available but unused
- EVERY solution gets the SAME static prompt
- No template variable substitution happens

**Why it works:**
- Simple and efficient
- No dynamic content needed
- Consistent behavior across all solutions
- The `memoryId` parameter exists for future extensibility

### **How to Use Template Variables (If Needed)**

**Option 1: Manual String Replacement**
```java
builder.systemMessageProvider(memoryId -> {
    String prompt = SystemPrompts.INSTANCE.getDocumentationPrompt();
    
    // Replace template variables
    prompt = prompt.replace("{{PROJECT_NAME}}", projectName);
    prompt = prompt.replace("{{DATE}}", LocalDate.now().toString());
    
    return prompt;
});
```

**Option 2: LangChain4j PromptTemplate (Proper Way)**
```java
import dev.langchain4j.model.input.PromptTemplate;
import java.util.Map;

builder.systemMessageProvider(memoryId -> {
    String promptText = SystemPrompts.INSTANCE.getDocumentationPrompt();
    
    // Create template
    PromptTemplate template = PromptTemplate.from(promptText);
    
    // Provide variable values
    Map<String, Object> variables = Map.of(
        "PROJECT_NAME", projectName,
        "USER_NAME", userName,
        "DATE", LocalDate.now().toString()
    );
    
    // Render template
    return template.apply(variables).text();
});
```

### **Use Cases for Dynamic Templates**

**Potential scenarios:**
1. **Project-specific context:** `{{PROJECT_NAME}}`, `{{MODULE_NAME}}`
2. **User information:** `{{USER_NAME}}`, `{{TEAM_NAME}}`
3. **Environment info:** `{{ENVIRONMENT}}`, `{{VERSION}}`
4. **Dynamic dates:** `{{DATE}}`, `{{YEAR}}`
5. **Custom coding standards per solution:** `{{CODING_STANDARDS}}`

**Example prompt with templates:**
```markdown
# Documentation Assistant

You are helping with project: {{PROJECT_NAME}}
Current user: {{USER_NAME}}
Date: {{DATE}}

Your task is to generate documentation...
```

### **Best Practices**

**✅ DO:**
- Use static prompts when content doesn't need to change
- Avoid `{{` in examples unless you need actual templates
- Use alternative syntax in examples: `{Object}` instead of `{{property: Type}}`
- Document any template variables you add

**❌ DON'T:**
- Put `{{...}}` in examples unless you provide values
- Assume the AI will see template syntax (it sees rendered output)
- Create templates without clear value sources
- Overcomplicate with unnecessary dynamic content

### **Our Solution**

**Changed in documentation.txt (Mar 10, 2026):**
- Line 246: `{{timeout: Number, retries: Number}}` → `{Object}`
- Line 644: `{{host: String, port: Number, timeout: Number}}` → `{Object}`

**Why this works:**
- Still valid JSDoc syntax
- Still teaches the AI about object types
- Avoids LangChain4j template parsing
- Simple and maintainable

**Lesson learned:** When writing system prompts, avoid `{{` patterns unless you're intentionally using template variables with provided values.

---

## 🔗 TYPECREATOR INTEGRATION ARCHITECTURE (March 23, 2026)

**Purpose:** How ServoyPilot integrates with Servoy's TypeCreator for API documentation that matches code completion.

### **The Problem: DLTK vs TypeCreator Type Name Mismatch**

**DLTK Type Inference** (what hover tooltips and type resolution use):
- Returns Java class names from reflection/Rhino
- Example: `var app = application;` → DLTK returns type `"JSApplication"`

**TypeCreator Type Registration** (what code completion uses):
- Registers types using `@ServoyDocumented` annotation's `scriptingName` attribute
- Example: `@ServoyDocumented(scriptingName="application")` on JSApplication class
- TypeCreator has: `"application"` → Type object with 152 members

**The Mismatch:**
- DLTK gives us: `"JSApplication"`
- TypeCreator expects: `"application"`
- Result: `TypeCreator.findType("JSApplication")` returns null!

### **The Solution: scriptingName Mapping Layer**

**Implementation:**
```java
private String mapClassNameToScriptingName(String className) {
    return switch (className) {
        case "JSApplication" -> "application";
        case "JSDatabaseManager" -> "databaseManager";
        case "JSSecurity" -> "security";
        case "JSI18N" -> "i18n";
        case "JSUtils" -> "utils";
        case "JSForm" -> "controller";
        case "JSEventsManager" -> "eventsManager";
        case "JSSolutionModel" -> "solutionModel";
        default -> null;
    };
}
```

**Usage Pattern:**
```java
// Try direct lookup first
Type type = typeCreator.findType(null, typeName);

// If not found, try scriptingName mapping
if (type == null) {
    String scriptingName = mapClassNameToScriptingName(typeName);
    if (scriptingName != null) {
        type = typeCreator.findType(null, scriptingName);
    }
}
```

### **Why "controller" is Different from "application"**

**Global API Objects** (application, databaseManager, security):
- Registered globally via `ScriptObjectRegistry` in TypeCreator.initialize()
- DLTK returns **class names**: `"JSApplication"`, `"JSDatabaseManager"`
- TypeCreator expects **scriptingNames**: `"application"`, `"databaseManager"`
- **Mapping required** ✅

**Form-Scoped Variables** (controller):
- Injected dynamically into each form's scope
- NOT registered via ScriptObjectRegistry
- DLTK already returns **scriptingName**: `"controller"` (lowercase)
- TypeCreator registered by **scriptingName**: `"controller"`
- **No mapping needed** ✅ Direct lookup works

### **Documentation Extraction: Three Paths**

**Path 1: Solution Functions** (user-defined code)
```
Code → DLTK → IModelElement → ScriptdocContentAccess.getContentReader() → JSDoc from code
```

**Path 2: Servoy API** (application, databaseManager, plugins, etc.)
```
Code → DLTK → ScriptObjectRegistry.getScriptObjectByName() → IObjectDocumentation (XML)
       ↓ (if null)
       TypeCreator.findType() → Type.getMembers() → Member.getDescription()
```

**Path 3: Web Components/Services**
```
Code → DLTK → TypeCreator.findType("RuntimeWebComponent<name>") → Type.getMembers() → Member.getDescription()
```

### **Key Insight: Code Completion Parity**

The TypeCreator fallback ensures **exact same documentation** as code completion (Ctrl+Space):

**Code Completion Flow:**
1. User types `application.` and presses Ctrl+Space
2. JavaScriptCompletionEngine2 calls TypeCreator.findType("application")
3. Gets Type object with 152 members
4. Displays each member with `Member.getDescription()` in popup

**ServoyPilot Documentation Flow:**
1. AI calls `getDocumentationForIdentifiers(["app"])`
2. Finds `var app = application;` → type `"JSApplication"`
3. Maps to `"application"` via `mapClassNameToScriptingName()`
4. Calls TypeCreator.findType("application")
5. Gets SAME Type object with 152 members
6. Extracts documentation from `Member.getDescription()`

**Result:** ServoyPilot documentation = Code completion documentation ✅

### **TypeCreator Components Used**

- **TypeCreator.findType(context, typeName)** - Main entry point for type lookup
- **Type.getMembers()** - Returns EList<Member> (methods and properties)
- **Member.getName()** - Member name (e.g., "closeSolution")
- **Member.getDescription()** - Full documentation text
- **Method.getParameters()** - Parameter list with types
- **Method.getType()** - Return type
- **Property.getType()** - Property type

**Integration Point:**
- `TypeProviderFactory.getTypeProvider().getTypeCreator()` - Singleton access
- Context parameter: `null` for global types, file path for scoped types

---

### 6. Configuration Files

### 6.1 plugin.xml
- Extension: `org.eclipse.ui.views` - Registers ChatView
- Extension: `org.eclipse.ui.preferencePages` - Registers preference page
- Extension: `org.eclipse.e4.workbench.model` - E4 model fragment

### 6.2 fragment.e4xmi
- Defines ChatView part descriptor
- Toolbar contributions
- Keybindings

### 6.3 build.properties
```properties
source.. = src/
output.. = bin/
bin.includes = META-INF/,\
               .,\
               plugin.xml,\
               icons/,\
               css/,\
               js/,\
               fonts/,\
               darkicons/,\
               fragment.e4xmi,\
               src/main/resources/
```
- **Critical:** `src/main/resources/` must be included for system prompt packaging

### 6.4 MANIFEST.MF (key sections)
```
Bundle-SymbolicName: com.servoy.eclipse.servoypilot;singleton:=true
Bundle-Activator: com.servoy.eclipse.servoypilot.Activator
Require-Bundle: org.eclipse.ui,
                org.eclipse.core.runtime,
                org.eclipse.e4.core.di,
                com.servoy.eclipse.servoypilot.langchain4j,
                ...
Import-Package: dev.langchain4j.memory,
                dev.langchain4j.memory.chat,
                dev.langchain4j.store.memory.chat,
                org.eclipse.e4.core.services.events,
                org.osgi.service.event,
                ...
```

---

## 7. System Prompt Philosophy

The system prompts can be **solution-specific** or use **default plugin resources**.

**System Prompt Loading Priority:**
1. **Solution-specific** (highest priority): `.servoy/system-prompts/chat.txt` or `completion.txt` in active solution
2. **Plugin resources** (fallback): Default prompts from knowledgebase bundle (`resources/system-prompts/`)
3. **Final fallback**: Empty/minimal prompt

**Loading Flow (SystemPrompts):**
```java
1. On initialization: Load all prompts from knowledgebase bundle resources/system-prompts/
   → Stores in memory map by filename (e.g., "chat.txt", "completion.txt")
   
2. When solution changes: Can reload from solution's .servoy/system-prompts/ directory
   → Calls SystemPrompts.loadFromPath(IFolder)
   → Overwrites bundle prompts with solution-specific ones
   → Triggers chat model reload via Activator.clearChatModel()
```

**Benefits of Solution-Specific Prompts:**
- Customize AI behavior per project/team
- Include project-specific coding standards
- Add domain-specific instructions
- Override default behavior for special solutions

**Available Prompts:**
- `chat.txt` - For chat conversations
- `completion.txt` - For code completion (if implemented)
- Custom prompts can be added as needed

---

## 8. Extending the System

### 8.1 Adding New Tools

1. Create tool class in `com.servoy.eclipse.servoypilot.tools` (or subdirectory)
2. Annotate methods with `@Tool` (description for LLM)
3. Annotate parameters with `@P` (description for LLM)
4. Register instance in `ServoyAiModel.createChatServices()` or `createCompletionServices()`:
   ```java
   builder.tools(
       // ... existing tools ...
       new MyNewTools()
   );
   ```

**Example:**
```java
public class TextFieldComponentTools {
    @Tool("Add a text field to a form")
    public String addTextField(
        @P("Form name") String formName,
        @P("Field name") String fieldName,
        @P("X position") int x,
        @P("Y position") int y
    ) {
        // Implementation
        return "Text field added successfully";
    }
}
```

### 8.2 Adding New AI Providers

1. Add new enum value to `PreferenceConstants.ModelKind`
2. Update `AiConfiguration` to handle new provider (API key and model methods)
3. Create builder method in `ServoyAiModel`:
   ```java
   private NewProviderStreamingChatModel createNewProviderModel(AiConfiguration conf) {
       return NewProviderStreamingChatModel.builder()
           .apiKey(conf.getApiKey())
           .modelName(conf.getModel())
           .build();
   }
   ```
4. Add case to switch statement in `ServoyAiModel` constructor for both chat and completion models
5. Update preference page UI to show new provider option

### 8.3 Modifying System Prompt

**Option 1: Change default prompts (affects all users)**
1. Edit files in knowledgebase bundle: `resources/system-prompts/chat.txt` or `completion.txt`
2. Rebuild knowledgebase bundle
3. Prompts will be loaded automatically via `SystemPrompts.loadFromBundle()`

**Option 2: Solution-specific prompts (affects only one solution)**
1. Create `.servoy/system-prompts/` directory in your solution
2. Add `chat.txt` and/or `completion.txt` files
3. Prompts will be loaded when solution is activated
4. Only that solution will use the custom prompts

### 8.4 UUID Protection Rules (February 26, 2026)

**Critical System Requirement:** UUIDs in Servoy code MUST NEVER be modified, created, or deleted by AI.

**Why:** UUIDs are Servoy system identifiers that link code to metadata (forms, relations, valueLists, etc.). Modifying them breaks the system with no recovery.

**Implementation:** Added RULE ZERO to ALL system prompts with maximum emphasis.

**Affected Prompts:**
1. `vibe-coding.txt` - Main conversational assistant
2. `documentation.txt` - JSDoc generation assistant
3. `explain.txt` - Code explanation assistant
4. `quickfix.txt` - Quick fix engine
5. `completion.txt` - Code completion engine

**Rule Format (Text-Based, No Emoticons):**
```
# *** CRITICAL RULES - NEVER VIOLATE THESE ***

## !!!!! UUID PROTECTION RULES - ABSOLUTELY MANDATORY !!!!!

### RULE ZERO: NEVER MODIFY OR CREATE UUIDs

**THIS IS THE MOST IMPORTANT RULE. VIOLATION WILL BREAK THE SYSTEM.**

1. **NEVER EVER modify any UUID value in the code**
   - UUIDs look like: `"550e8400-e29b-41d4-a716-446655440000"`
   - UUIDs are typically 36 characters with hyphens: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
   - DO NOT change even a single character of any UUID

2. **NEVER create, generate, or add new UUIDs**
   - You are NOT authorized to create UUIDs
   - UUIDs are system-generated identifiers managed by Servoy

3. **NEVER remove UUIDs from the code**
   - Every UUID serves a critical system purpose

4. **WHAT TO DO when you see UUIDs:**
   - [YES] COPY them EXACTLY as-is
   - [YES] PRESERVE the exact formatting and position
   - [YES] IGNORE them - treat them as read-only system data
   - [NO] NEVER modify, create, remove, or change UUIDs

5. **WHY THIS MATTERS:**
   - UUIDs are database identifiers linking code to Servoy metadata
   - Changing a UUID breaks connections to forms, relations, valueLists, etc.
   - The system WILL FAIL if UUIDs are modified
   - There is NO recovery from UUID corruption
```

**Design Decisions:**
- **Text-based emphasis:** Uses `***`, `!!!!!`, `[YES]`, `[NO]` instead of emoticons (better LLM compatibility)
- **Positioned as RULE ZERO:** Appears before all other rules in each prompt
- **Repeated across all prompts:** Ensures every AI interaction respects this rule
- **Prominent formatting:** All-caps, bold, multiple exclamation marks for maximum visibility
- **Example-driven:** Shows correct behavior with code examples

**Future Enhancement:** Code-level validation to detect UUID modifications in tool outputs (additional safety layer beyond prompts).

### 8.5 Customizing Solution-Specific Knowledge Base

1. Use "Reset Instructions" menu to create `.servoy/` directory in solution
2. Edit files in `.servoy/rules/` or `.servoy/embeddings/` as needed
3. Edit `.servoy/system-prompts/` for custom prompts
4. Knowledge base automatically loads when solution is activated

**Use Cases:**
- Project-specific coding standards
- Custom component usage guidelines
- Solution-specific business rules
- Team-specific best practices

---

## 9. Knowledge Base Architecture

### 9.1 Overview

The knowledge base system uses **Retrieval-Augmented Generation (RAG)** with local ONNX embeddings for fast, offline semantic search.

**Two Loading Sources:**
- **Solution-specific:** `.servoy/` directory (customized knowledge base per solution)
- **Default:** Bundle resources (default knowledge base from knowledgebase bundle)

**Loading Strategy:**
- On solution activation: Check if `.servoy/` exists
  - If YES: Load from `.servoy/` directory (customized)
  - If NO: Load from bundle resources (default)
- Users can create/customize via "Reset Instructions" menu

**Components:**
- **KnowledgeBaseManager**: Facade for knowledge base operations (discovery, loading)
- **ServoyFolderPackageReader**: IPackageReader for reading from `.servoy/` folder (filesystem)
- **ServoyBundlePackageReader**: IPackageReader for reading from bundle resources (default KB)
- **RulesCache**: Stores markdown rule files with intent-based retrieval
- **ServoyEmbeddingService**: Generates and searches vector embeddings using ONNX model
- **InstructionsLoadService**: Orchestrates loading from filesystem or bundle
- **InstructionsSaveService**: Handles `.servoy/` directory creation and copying

### 9.2 KnowledgeBaseManager

**Purpose:** Central manager for knowledge base operations

**Key Method:**
```java
private static IPackageReader[] discoverKnowledgeBasePackagesInSolution(ServoyProject solution) {
    IProject project = solution.getProject();
    IFolder servoyFolder = project.getFolder(".servoy");
    
    if (!servoyFolder.exists()) {
        // No .servoy directory → return empty (will load from bundle instead)
        return new IPackageReader[0];
    }
    
    // Create package reader for .servoy folder
    ServoyFolderPackageReader reader = new ServoyFolderPackageReader(servoyFolder, solutionName);
    return new IPackageReader[] { reader };
}
```

**Workflow:**
1. Check if `.servoy/` folder exists in solution
2. If YES → Create `ServoyFolderPackageReader` for it
3. If NO → Return empty array (ChatViewPresenter will load from bundle)
4. Load embeddings and rules from the reader

**Benefits:**
- Simple, direct file access (no complex package management)
- Fast loading (no bundle scanning)
- Predictable behavior (`.servoy/` or bundle resources)

### 9.3 ServoyFolderPackageReader

**Purpose:** IPackageReader implementation for reading from `.servoy/` folder

**File:** `knowledgebase/ServoyFolderPackageReader.java`

**Key Features:**
- Implements `org.sablo.specification.Package.IPackageReader`
- Reads directly from Eclipse `IFolder` (`.servoy/` directory)
- No MANIFEST.MF required (not an SPM package)
- Returns `File` for `getResource()` method

**Methods:**
- `getName()` / `getPackageName()` - Returns `<solutionName>-knowledge-base`
- `getPackageDisplayname()` - Returns `<solutionName> Knowledge Base`
- `getVersion()` - Returns "1.0.0"
- `getPackageURL()` - Returns URL of `.servoy/` folder
- `getUrlForPath(String)` - Resolves file paths within `.servoy/`
- `readTextFile(String, Charset)` - Reads text file from `.servoy/`
- `getResource()` - Returns `File` for `.servoy/` folder

### 9.3a ServoyBundlePackageReader

**Purpose:** IPackageReader implementation for reading from OSGi bundle resources

**File:** `knowledgebase/ServoyBundlePackageReader.java`

**Key Features:**
- Implements `org.sablo.specification.Package.IPackageReader`
- Reads from OSGi bundle's `resources/` directory
- Used to load default knowledge base when `.servoy/` doesn't exist
- No file system access required

**Methods:**
- `getName()` / `getPackageName()` - Returns bundle symbolic name + "-default"
- `getPackageDisplayname()` - Returns "Default Knowledge Base"
- `getVersion()` - Returns bundle version
- `getPackageURL()` - Returns URL of bundle's base path
- `getUrlForPath(String)` - Resolves resource paths within bundle
- `readTextFile(String, Charset)` - Reads text file from bundle resources
- `getResource()` - Returns null (bundle resources can't be accessed as File objects)

**Usage:**
```java
Bundle knowledgebaseBundle = Platform.getBundle("com.servoy.eclipse.servoypilot.knowledgebase");
IPackageReader bundleReader = new ServoyBundlePackageReader(knowledgebaseBundle, "resources");
RulesCache.loadFromPackageReader(bundleReader);
embeddingService.loadKnowledgeBaseFromReader(bundleReader);
```

### 9.4 RulesCache

**Purpose:** Fast key-based retrieval of rule markdown content

**Storage:** In-memory HashMap (`Map<String, String>`)

**Intent Keys:** Derived from filename
- `forms.md` → `FORMS`
- `bootstrap/buttons.md` → `BOOTSTRAP_BUTTONS`

**Loading:**
- Loads from IPackageReader (either ServoyFolderPackageReader or ServoyBundlePackageReader)
- Reads `rules/rules.list` for file list
- Loads each `.md` file into memory

**Methods:**
- `getRules(String intent)` - Retrieve rules by intent
- `getRules(String intent, String projectName)` - With variable substitution (e.g., `{{PROJECT_NAME}}`)
- `clear()` - Clear all cached rules
- `getRuleCount()` - Get number of loaded rules
- `getAvailableIntents()` - Get list of all loaded intents

### 9.5 ServoyEmbeddingService

**Purpose:** Semantic search over knowledge base using vector embeddings

**Technology:**
- **Embedding Model:** BGE-small-en-v1.5 (ONNX format, local, offline)
- **Vector Store:** In-memory (LangChain4j InMemoryEmbeddingStore)
- **Tokenizer:** ONNX tokenizer (no external dependencies)
- **Similarity Threshold:** 0.8 (80% similarity minimum)

**Loading:**
- Loads from IPackageReader (either ServoyFolderPackageReader or ServoyBundlePackageReader)
- Reads `embeddings/embeddings.list` for file list
- Generates embeddings for each line in `.txt` files

**Methods:**
- `search(String query, int maxResults)` - Semantic search
- `getEmbeddingCount()` - Get number of embeddings loaded
- `hasEmbeddings()` - Check if embeddings exist
- `reloadAllKnowledgeBasesFromReaders(IPackageReader[])` - Clear and reload from package readers

**Search Flow:**
1. User query received
2. Generate query embedding using ONNX model
3. Search vector store for similar embeddings
4. Return matches with score > 0.8
5. LLM uses matched content to answer question

### 9.6 Integration with getKnowledge Tool

**Tool:** `KnowledgeTools.getKnowledge(String query)`

**Workflow:**
1. AI determines it needs domain knowledge
2. Calls `getKnowledge("how to create a form")`
3. Tool performs semantic search via ServoyEmbeddingService
4. Returns top matching content from embeddings
5. AI uses retrieved knowledge to answer user's question

**Benefits:**
- Always up-to-date (can reload at any time)
- Relevant retrieval (semantic search finds best matches)
- Minimal token overhead (only retrieved content sent to LLM)
- Offline operation (no API calls for embeddings)

---

## 10. Design Patterns Used

1. **Presenter Pattern**: ChatView (view) + ChatViewPresenter (logic separation)
2. **Facade Pattern**: ServoyAiModel (hides LangChain4j complexity), KnowledgeBaseManager
3. **Strategy Pattern**: AI provider selection (OpenAI, Gemini), dual-prompt system
4. **Proxy Pattern**: IActiveProjectListener (dynamic proxy via reflection)
5. **Dependency Injection**: E4 DI (@Inject, @PostConstruct, @PreDestroy)
6. **Job Pattern**: Background processing (Eclipse Jobs for AI calls, handlers)
7. **Service Layer Pattern**: Business logic in services package (InstructionsFileService, InstructionsLoaderService, etc.)
8. **Command Pattern**: Handlers for menu actions (SaveInstructionsHandler, LoadInstructionsHandler)

---

## 11. Threading Model

**UI Thread (SWT):**
- ChatView rendering
- User input handling
- Message display updates
- Dialog display (handlers)

**Background Thread (Eclipse Job):**
- AI model calls (OpenAI/Gemini API requests)
- Tool execution
- Streaming response handling
- File operations (Save/Load Instructions handlers)
- Knowledge base loading (RulesCache, ServoyEmbeddingService)

**Synchronization:**
- `UISynchronize.asyncExec()` - Update UI from background thread
- `Display.getDefault().asyncExec()` - Schedule UI work from non-UI thread
- LangChain4j handles streaming callbacks on background threads
- Handlers use `shell.getDisplay().asyncExec()` for dialog display

---

## 12. Security Considerations

1. **API Keys**: Stored in Eclipse secure storage (IPreferenceStore)
2. **File Access**: Tools restricted to workspace (no arbitrary file system access)
3. **Code Execution**: No eval() or dynamic code execution
4. **Input Validation**: Tool parameters validated before execution
5. **Servoy Metadata**: Tools use Servoy APIs (don't corrupt .frm files)
6. **File System Operations**: Restricted to `.servoy/` directories within workspace projects

---

## 13. Performance Considerations

1. **Token Costs**: 2.4K-3.5K system prompt overhead per request (monitor usage)
2. **Memory Usage**: InMemoryChatMemoryStore accumulates across solutions (automatic trimming at 40 messages)
3. **Streaming**: Responses stream incrementally (better UX for long responses)
4. **Lazy Loading**: AI model initialized on first use (not at plugin startup)
5. **Background Jobs**: AI calls and file operations don't block UI (Eclipse Jobs)
6. **ONNX Embeddings**: Local model, no API calls, fast offline semantic search
7. **Knowledge Base**: In-memory storage for fast retrieval (RulesCache + vector store)

---

## 14. Troubleshooting

**Common Issues:**

1. **ClassNotFoundException**: Missing Import-Package in MANIFEST.MF
   - Add package to Import-Package section
   - Ensure langchain4j bundle exports the package

2. **Solution switching not working**: Listener not registered
   - Check console for "Solution activation listener registered SUCCESSFULLY"
   - Verify ServoyModel is available at startup

3. **System prompt not loading from .servoy**: File missing or wrong name
   - Check `.servoy/system-prompts/chat.txt` exists (for chat) or `completion.txt` (for completion)
   - Verify filename is exactly `chat.txt` or `completion.txt`
   - System prompts are loaded when solution activates or via InstructionsLoadService

4. **API calls failing**: Invalid configuration
   - Check API key in preferences
   - Verify model name is correct for provider
   - Check internet connectivity

5. **Save Instructions fails**: Bundle or project issues
   - Verify knowledgebase bundle is loaded and active
   - Check active project exists and is accessible
   - Review console logs for detailed error messages

6. **Knowledge base not loading**: File system or bundle issues
   - Check `.servoy/rules/rules.list` and `.servoy/embeddings/embeddings.list` exist
   - Verify files listed in `.list` files actually exist
   - Check console logs for "Loaded X rules" and "Loaded X embeddings" messages

**Debug Tips:**
- Check Eclipse Error Log view for exceptions
- Monitor console output for ServoyLog messages (prefixed with component name)
- Use `-Dconsole.debug=true` VM argument to enable detailed debug logging
- Check `.servoy/` directory structure matches expected format
- Verify bundle resources are properly packaged in knowledgebase bundle

---

## 15. Feature Summary

**Core Features:**
- ✅ AI-powered chat interface with streaming responses
- ✅ **Multi-assistant view switcher** (Feb 18, 2026) - Switch between VibeCoding and Documentation assistants in ChatView
- ✅ Code completion support via CompletionAssistant (stateless, fast)
- ✅ Quick fix support via QuickFixAssistant (stateful, programmatic usage)
- ✅ 40+ specialized tools for Servoy development (12 tool classes)
- ✅ **Single shared memory store** with ID-based isolation per assistant
- ✅ Solution-specific conversation memory with assistant-scoped IDs (`solution-vibe`, `solution-documentation`, `solution-quickfix`)
- ✅ Automatic reset of all assistant memories on solution switch
- ✅ Solution-specific system prompts (loaded from `.servoy/system-prompts/`)
- ✅ Fallback to bundle default prompts when solution-specific don't exist
- ✅ RAG with local ONNX embeddings (offline, fast)
- ✅ Knowledge base loading from `.servoy/` directory or bundle resources
- ✅ Background jobs for non-blocking operations
- ✅ Code diff viewer and patch application

**Assistant Types (Updated Feb 23, 2026):**
- ✅ **VibeCoding Assistant**: Full conversation with 40+ tools, 40 message memory, streaming responses
  - ✅ Accessible via assistant selector dropdown in ChatView
  - ✅ Independent conversation history (memory ID: `solution-vibe`)
- ✅ **Completion Assistant**: Fast code completion, stateless (no memory), fast models
  - ✅ Inline editor completion (not in ChatView dropdown)
- ✅ **Documentation Assistant**: JSDoc generation, 40 message memory, streaming responses
  - ✅ Accessible via assistant selector dropdown in ChatView
  - ✅ Independent conversation history (memory ID: `solution-documentation`)
  - ✅ Interface and memory management complete
  - ✅ Context menu integration ready
  - ✅ Code context extraction working
  - ⏳ Context menu handler needs implementation for automated doc generation
- ✅ **QuickFix Assistant**: Quick fixes for code issues, 40 message memory, synchronous responses
  - ❌ NOT yet in ChatView dropdown (exists in ServoyAiModel only)
  - ✅ Independent conversation history (memory ID: `solution-quickfix`)
  - ✅ Interface: `String fix(String prompt)` - non-streaming
  - ✅ System prompt: `quickfix.txt`
  - 🔄 **Future:** May be added to ChatView UI for conversational quick fixes

**Multi-Assistant Architecture (Updated Feb 23, 2026):**
- ✅ `IAssistant` interface - Common interface for all conversational assistants
- ✅ `AssistantType` enum - VIBE_CODING, DOCUMENTATION, QUICKFIX with display names and memory suffixes
- ✅ Assistant selector combo in ChatView (200px width) - **Currently shows 2 assistants** (VibeCoding, Documentation)
- ✅ Dynamic assistant switching with memory ID updates
- ✅ Polymorphic message sending (`executeRequest()` method)
- ✅ Clean code - no instanceof checks
- ✅ Easy extensibility - add new assistants by implementing IAssistant
- 🔄 **QuickFix Assistant:** Created in ServoyAiModel but not yet added to ChatView dropdown

**Memory Management (Updated Feb 23, 2026):**
- ✅ Single shared `ChatMemoryStore` used by all assistants
- ✅ Solution-scoped memory IDs: `<solution>-<assistant>` format
- ✅ Independent memory clearing per assistant or all at once
- ✅ Automatic memory reset on solution switch (all assistants cleared)
- ✅ 40 message window per assistant (automatic trimming)
- ✅ `refreshViewFromMemory()` reloads UI from memory store (single source of truth)

**Knowledge Base Features:**
- ✅ **Dual-source loading:** `.servoy/` directory (customized) or bundle resources (default)
- ✅ **ServoyFolderPackageReader:** Direct folder reading for solution-specific KB
- ✅ **ServoyBundlePackageReader:** Bundle resource reading for default KB
- ✅ Solution-specific customization via `.servoy/` directories
- ✅ System prompts, rules, and embeddings all supported
- ✅ Semantic search with 80% similarity threshold
- ✅ Intent-based rule retrieval
- ✅ Variable substitution in rules (e.g., `{{PROJECT_NAME}}`)

**Form Management:**
- ✅ Properties work on both new and existing forms
- ✅ Events work on existing forms with auto-created methods
- ✅ Inheritance (extendsForm) works on existing forms
- ✅ 18 form properties supported
- ✅ 13 form events + 1 command supported

**UI Features:**
- ✅ Markdown rendering with syntax highlighting
- ✅ Menu actions for knowledge base management
- ✅ Progress dialogs for background operations
- ✅ Confirmation dialogs before overwriting
- ✅ Auto-scroll toggle
- ✅ Copy to clipboard
- ⏳ Documentation View (pending implementation)

**Testing & Quality:**
- ✅ Test prompts suite available
- ✅ Debug system with DebugUtils (controlled by `-Dconsole.debug=true`)
- ✅ Comprehensive error handling and logging
- ✅ Positive conditional coding rules followed
- ✅ 25 tests for SESSION 3 (session3-type-resolution.md)
- ✅ 15 tests for SESSION 2 (session2-adaptive-chunk-reading.md)
- ✅ 15 tests for SESSION 1 (session1-file-structure-analysis.md)

---

## 📋 QUICK REFERENCE: Documentation Assistant Tools

**All Tools Available (8 total):**

### Code Analysis Tools (CodeAnalysisTools.java) - **UPDATED Mar 24, 2026**

| Tool | Parameters | Requires Editor? | Purpose |
|------|-----------|------------------|---------|
| `analyzeFileStructure` | pathOrName | ❌ No | List all symbols with JSDoc status |
| `getCodeChunk` | pathOrName, symbolName?, chunkNumber?, startLine? | ❌ No | Read code in 200-line chunks (3 modes) |
| `resolveIdentifierType` | identifier, pathOrName | ❌ No | Get type of identifier |

**Note:** These tools were migrated from CodeContextTools to CodeAnalysisTools on March 24, 2026. The implementation uses CodeContextService for shared functionality.

### Code Context Tools (CodeContextTools.java)

| Tool | Parameters | Requires Editor? | Purpose |
|------|-----------|------------------|---------|
| `codeContext` | filePath, lineNumber, characterOffset | ❌ No | Get code context around specific line |

**Note:** CodeContextTools now focuses on context extraction. File analysis tools moved to CodeAnalysisTools.

### Documentation Tools (DocumentationTools.java)

| Tool | Parameters | Requires Editor? | Purpose |
|------|-----------|------------------|---------|
| `getCurrentSelection` | - | ✅ Yes | Get selected code with line numbers |
| `getDocumentationForIdentifiers` | identifiers[], filePath? | ⚠️ Optional | Extract API docs for identifiers in selection/file |
| `applyDocumentations` | filePath, contentHash, items[] | ❌ No | Apply JSDoc to file with line-based positioning |
| `getAvailableMembersForType` | typeName, memberFilter? | ❌ No | List type members (signatures only, regex filter, 50 max) |
| `getDocumentationForTypeMember` | typeName, memberName | ❌ No | Get full docs for specific member |

**Legend:**
- ✅ Yes = Requires active editor via SelectionTracker
- ❌ No = Works completely standalone, no editor needed
- ⚠️ Optional = Works with SelectionTracker if filePath not provided, otherwise standalone

**Typical Workflows:**

**Workflow 1: Context Menu → Generate Docs**
1. User right-clicks code, selects "Generate Docs"
2. AI calls `getCurrentSelection()` (requires editor ✅)
3. AI optionally calls `getDocumentationForIdentifiers()` for API types
4. AI calls `applyDocumentations()` to apply JSDoc

**Workflow 2: Chat-Based → Explore API**
1. User asks "What methods are available on application?"
2. AI calls `getAvailableMembersForType("application", "*")` (no editor ❌)
3. User asks "Tell me about closeSolution"
4. AI calls `getDocumentationForTypeMember("application", "closeSolution")` (no editor ❌)

**Workflow 3: Chat-Based → Document Closed File**
1. User asks "Generate docs for testServoyGlobals"
2. AI calls `analyzeFileStructure("testServoyGlobals")` (no editor ❌)
3. AI calls `getCodeChunk("testServoyGlobals", symbolName="onLoad")` (no editor ❌)
4. AI calls `getDocumentationForIdentifiers(["app"], "testServoyGlobals")` (no editor ❌)
5. AI calls `applyDocumentations("testServoyGlobals", hash, items)` (no editor ❌)

---

**Architecture Highlights:**
- 3 OSGi bundles (main plugin, langchain4j wrapper, knowledgebase)
- 5 specialized assistants: VibeCoding, Documentation, Explain, QuickFix, Completion
  - **ChatView dropdown:** 2 assistants visible (VibeCoding, Documentation)
  - **Context menu:** Explain (auto-switches to ChatView), Generate Docs (uses tool)
  - **Programmatic only:** QuickFix (not yet in UI)
  - **Inline completion:** Completion (not in ChatView)
- Clean separation: UI (Views) → Presenters → Services → Tools
- Stateless LLM with client-side memory management (single shared store, ID-based isolation)
- Direct listener registration for solution activation events
- Service layer for business logic
- **Browser abstraction:** BrowserWrapper + BrowserFunctionWrapper for cross-platform support
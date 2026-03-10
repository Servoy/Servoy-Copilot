# ServoyPilot - Architecture Reference

**Last Updated:** March 9, 2026  
**Purpose:** Complete technical reference for understanding the system design and component structure

**Status:** 
- ✅ Multi-Assistant View Switcher implemented and functional
- ✅ **Memory Store Refactoring COMPLETE** - Single source of truth (memory store only)
- ✅ **Memory Refactoring VALIDATED** - Testing complete, system working correctly
- ✅ Code Context Gathering complete (Phases 1-4)
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

---

## ✅ COMPLETED - DOCUMENTATION ASSISTANT (February 25, 2026)

**Implementation Complete:** Full workflow for generating JSDoc documentation via AI tool.

### **Architecture Overview:**

### **Architecture Overview:**

**1. Context Menu Handler** (`ServoyAiContextMenuHandler.handleGenerateDocs`):
- Creates generic message: "Please generate JSDoc documentation for the current selection."
- Opens ChatView and switches to Documentation Assistant
- Sends generic message (NO code or documentation included)
- AI will retrieve code and documentation dynamically using tools

**2. Documentation Assistant** (`DocumentationAssistant.java`):
- Interface extends `IAssistant`
- Uses streaming chat model for interactive workflow
- Memory: 40 messages, solution-scoped with `-documentation` suffix
- Registered tools: `DocumentationTools` (3 tools)

**3. Documentation Tools** (`DocumentationTools.java`):
- **Tool 1:** `getCurrentSelection()` - Returns code only (FILE, OFFSET, LENGTH, CODE)
- **Tool 2:** `getDocumentationForIdentifiers(String[] identifiers)` - NEW! On-demand API doc lookup
  - Soft limit: 20 identifiers (logs info if exceeded, still processes)
  - Supports nested identifiers: `"plugins.ngdesktop"`, `"elements.button"`
  - Returns formatted XML documentation for requested identifiers
  - Reports "NOT FOUND" for missing identifiers
- **Tool 3:** `applyDocumentation(filePath, offset, length, modifiedContent)` - Applies JSDoc to file
  - Backs up original file (once per file via FileModificationTracker)
  - Applies content to selection range or full file
  - Clears editor selection after application
  - Returns success/error messages to AI

**4. System Prompt** (`documentation.txt`):
- **Complete rewrite (Mar 3, 2026)** - 5-step workflow with pull-based documentation
- RULE ZERO: UUID protection (comprehensive instructions)
- STEP 1: Call `getCurrentSelection()` to get code only
- STEP 2: Analyze code and identify what needs documentation lookup
- STEP 3: Optionally call `getDocumentationForIdentifiers()` for unclear types
- STEP 4: Generate JSDoc with accurate types
- STEP 5: Call `applyDocumentation()` to apply changes
- Detailed guidance on when to request docs vs. skip (Standard JS vs. Servoy types)
- Three complete example workflows showing different scenarios
- Soft limit awareness and prioritization strategy

### **Complete Workflow (New - Pull-Based):**

```
1. User: Right-click code → "Generate Docs"

2. Handler: 
   - Creates generic message (no code)
   - Opens ChatView, switches to Documentation Assistant
   - Sends: "Please generate JSDoc documentation for the current selection."

3. AI (STEP 1): Calls getCurrentSelection() tool
   - Receives: FILE, OFFSET, LENGTH, CODE (no documentation yet)

4. AI (STEP 2): Analyzes code
   - Identifies functions to document
   - Categorizes identifiers: Standard JS vs. Servoy-specific vs. unclear
   - Decides which identifiers need documentation lookup

5. AI (STEP 3 - OPTIONAL): Calls getDocumentationForIdentifiers(["foundset", "record"])
   - Only if Servoy types or unclear identifiers present
   - Skips for standard JS (String, Number, Boolean, Array, etc.)
   - Receives formatted XML with API documentation
   - Can make multiple calls if needed (prioritizes within 20-identifier soft limit)

6. AI (STEP 4): Generates JSDoc documentation
   - Uses retrieved API docs for accurate types
   - Follows Servoy conventions (JSEvent, JSRecord, JSFoundSet, etc.)
   - Preserves UUIDs exactly (RULE ZERO)
   - Adds JSDoc comments above functions

7. AI (STEP 5): Calls applyDocumentation(filePath, offset, length, documentedCode)

8. Tool:
   - FileModificationTracker.notifyFileModified() → Backup original
   - Apply documented code (replace selection or full file)
   - Clear editor selection
   - Return success message

9. UI:
   - File appears in "Modified files" section
   - User can Keep/Undo/Remove changes
   - Click file to see diff in compare editor
```

### **Key Benefits of New Architecture:**

✅ **Token Efficiency:** 50-80% reduction - only retrieves docs when needed  
✅ **AI Autonomy:** AI decides what it needs vs. force-fed everything  
✅ **Scalability:** Large files (100+ identifiers) don't overflow context  
✅ **Faster Processing:** Less data to parse in initial response  
✅ **Better Focus:** AI prioritizes which docs matter most  
✅ **Iterative Refinement:** AI can request more docs if needed  

### **Key Features:**
- ✅ **Pull-based documentation retrieval** - AI requests docs on-demand (NEW - Mar 3, 2026)
- ✅ **Selective lookup** - Only retrieves documentation for unclear identifiers
- ✅ **Soft limit enforcement** - 20 identifiers per request (encourages prioritization)
- ✅ **Nested identifier support** - Handles `plugins.ngdesktop`, `elements.button`, etc.
- ✅ Handles selection and full file documentation
- ✅ Automatic file backup (only once per file)
- ✅ Thread-safe file modification tracking
- ✅ Workspace-relative paths
- ✅ AI controls documentation workflow
- ✅ Clear error messages returned to AI
- ✅ Integration with Modified Files Tracking
- ✅ UUID protection (RULE ZERO in system prompt)

### **Implementation Status:**
✅ **COMPLETE (Mar 3, 2026)** - Pull-based architecture implemented  
✅ **Code changes:** DocumentationTools.java modified  
✅ **System prompt:** documentation.txt completely rewritten  
✅ **Compilation:** No errors  
⏳ **Testing:** Ready for end-to-end validation  

---
## ⚠️ TODO - AGENTS IMPLEMENTATION USING CODE CONTEXT INFRASTRUCTURE

**CURRENT STATUS (Feb 25, 2026):**
- ✅ **Generate Docs Agent (Documentation Assistant)**: COMPLETE
  - Full workflow implemented
  - DocumentationTools integrated
  - File modification tracking working
  - **Ready for testing**
- ⏳ **Debug Agent**: Not started
- ⏳ **Review Agent**: Not started
- ⏳ **Generate Tests Agent**: Not started

**REQUIRED ACTIONS:**

1. **Test Documentation Assistant**:
   - Select code in JavaScript editor
   - Right-click → "Servoy AI" → "Generate Docs"
   - Verify AI generates and applies JSDoc
   - Verify file appears in "Modified files" section
   - Test Keep/Undo/Remove actions

2. **Implement remaining agents** (after Documentation Assistant validated):
   - Debug Agent: Analyze code for bugs, suggest fixes
   - Review Agent: Code review with best practices
   - Generate Tests Agent: Generate unit tests based on code analysis
   - Each will follow Documentation Assistant pattern (own interface, memory, prompt, tool)

3. **Create knowledge base entries for agents** (Phase 6.2):
   - Add embeddings for code context queries
   - Add rules for using CodeContextTools
   - Examples of context-aware assistance

4. **Update system prompts for agents** (Phase 6.3):
   - Add instructions for using code context
   - Add examples of context-aware responses
   - Update tool usage guidelines

**Dependencies:**
- ✅ Code context extraction complete (getCodeContext tool)
- ✅ XML formatting ready for LLM consumption
- ✅ Context menu infrastructure in place
- ✅ Selection and full-file analysis working
- ✅ Documentation assistant COMPLETE
- ✅ DocumentationTools implemented and registered

**Benefits:**
- Agents will have deep understanding of Servoy APIs being used
- Context-aware suggestions (knows what components, services are in scope)
- Accurate documentation generation with proper type information
- Better debugging with API-specific knowledge

---

## 1. Overview

ServoyPilot is an Eclipse plugin that provides AI-assisted development specifically for the Servoy platform. It provides a specialized assistant that understands Servoy's metadata-driven architecture (Forms, Relations, ValueLists, Components, etc.).

**Key Differentiator:** Unlike generic AI assistants, ServoyPilot uses specialized tools to safely manipulate Servoy objects without corrupting .frm files or other metadata.

**Technology Stack:**
- Eclipse E4 (UI framework)
- LangChain4j 1.10.0+ (AI orchestration)
- OpenAI GPT-4 / Google Gemini (LLM providers)
- SWT/Browser (Chat UI)
- OSGi (Plugin architecture)
- ONNX Runtime (Local embeddings for RAG)

---

## 2. Project Structure

Three Eclipse bundles (OSGi plugins):

```
com.servoy.eclipse.servoypilot/               # Main plugin
|-- src/com/servoy/eclipse/servoypilot/
|   |-- Activator.java                        # Plugin lifecycle
|   |-- ai/
|   |   |-- IAssistant.java                   # Common interface for all conversational assistants (Feb 18, 2026)
|   |   |-- AssistantType.java                # Enum: VIBE_CODING, DOCUMENTATION, QUICKFIX, EXPLAIN with display names and memory suffixes
|   |   |-- VibeCodingAssistant.java          # LangChain4j interface for chat assistant (extends IAssistant)
|   |   |-- DocumentationAssistant.java       # LangChain4j interface for documentation assistant (extends IAssistant)
|   |   |-- ExplainAssistant.java             # LangChain4j interface for explain assistant (extends IAssistant) - NEW Feb 25, 2026
|   |   |-- QuickFixAssistant.java            # LangChain4j interface for quick fix assistant (extends IAssistant)
|   |   |-- CompletionAssistent.java          # LangChain4j interface for code completion (stateless)
|   |   |-- AIModelProvider.java              # Model provider interface
|   |   |-- AIModelTools.java                 # Model-related tools
|   |   +-- ServoyAiModel.java                # AI model initialization, single shared memory store management
|   |-- chatview/parts/
|   |   |-- ChatView.java                     # SWT/BrowserWrapper UI with assistant selector combo
|   |   |-- ChatViewPresenter.java            # Multi-assistant management, conversation logic
|   |   |-- BrowserWrapper.java               # Browser abstraction (SWT/Chromium) - NEW Feb 25, 2026
|   |   |-- BrowserFunctionWrapper.java       # BrowserFunction abstraction - NEW Feb 25, 2026
|   |   |-- FileModificationTracker.java      # File modification tracking singleton
|   |   |-- CodeEditingService.java           # Diff generation (JGit)
|   |   +-- ApplyPatchWizardHelper.java       # Code patch application UI
|   |-- context/                              # Code context gathering
|   |   |-- CodeContextService.java           # Main service: extracts API context from code
|   |   |-- IdentifierCollectingVisitor.java  # AST visitor: collects identifiers with types
|   |   |-- SelectionTracker.java             # Singleton: tracks editor selections
|   |   |-- ServoyAiContextMenu.java          # Dynamic context menu contribution
|   |   |-- ServoyAiContextMenuHandler.java   # Handler for context menu commands
|   |   +-- dto/                              # Data transfer objects
|   |       |-- CodeContext.java              # Complete code context (identifiers + formatting)
|   |       |-- IdentifierContext.java        # Single identifier (name, type, docs, kind)
|   |       +-- SelectionInfo.java            # Selection metadata (file, offset, length, text)
|   |-- preferences/
|   |   |-- AiConfiguration.java              # AI provider settings
|   |   +-- ServoyPilotPreferencePage.java    # Preferences UI
|   |-- services/                             # Business logic services
|   |   |-- InstructionsSaveService.java      # File operations for .servoy/ directory (save)
|   |   |-- InstructionsLoadService.java      # Knowledge base loading/clearing (load)
|   |   |-- BootstrapComponentService.java    # Bootstrap component operations
|   |   |-- TargetService.java                # Solution/module target management
|   |   |-- DatabaseSchemaService.java        # Database schema operations
|   |   |-- FormService.java                  # Form CRUD operations
|   |   |-- RelationService.java              # Relation CRUD operations
|   |   |-- StyleService.java                 # Style operations
|   |   +-- ValueListService.java             # ValueList operations
|   |-- tools/                                # AI Tools (Function Calling)
|   |   |-- EclipseTools.java                 # File search, find, replace
|   |   |-- DocumentationTools.java           # Apply documentation to files - NEW Feb 25, 2026
|   |   |-- FileReadingTools.java             # Chunked file reading for large files - NEW Feb 25, 2026
|   |   |-- core/                             # Servoy core objects
|   |   |   |-- FormTools.java                # Forms: get, open, delete
|   |   |   |-- RelationTools.java            # Relations: get, open, delete
|   |   |   |-- ValueListTools.java           # ValueLists: get, open, delete
|   |   |   +-- StyleTools.java               # Styles: get, open, delete
|   |   |-- component/                        # Servoy components
|   |   |   |-- ButtonComponentTools.java     # Buttons: list, add, update, delete, info
|   |   |   +-- LabelComponentTools.java      # Labels: list, add, update, delete, info
|   |   +-- utility/                          # Utility tools
|   |       |-- DatabaseTools.java            # Database: list tables, get info
|   |       |-- TargetTools.java              # Target: get/set active solution/module
|   |       |-- CodeContextTools.java         # Code context: analyze selected code
|   |       +-- KnowledgeTools.java           # RAG: getKnowledge for rules retrieval
|   |       |-- DatabaseTools.java            # Database: list tables, get info
|   |       |-- TargetTools.java              # Target: get/set active solution/module
|   |       |-- CodeContextTools.java         # Code context: analyze selected code (NEW)
|   |       +-- KnowledgeTools.java           # RAG: getKnowledge for rules retrieval
|   +-- prompts/
|       +-- SystemPrompts.java                # System prompt loading from bundle or .servoy/

com.servoy.eclipse.servoypilot.langchain4j/   # LangChain4j wrapper bundle
+-- libs/                                      # LangChain4j JARs + dependencies

com.servoy.eclipse.servoypilot.knowledgebase/ # Knowledge base (RAG system)
|-- src/com/servoy/eclipse/knowledgebase/
|   |-- Activator.java                        # Bundle lifecycle
|   |-- IKnowledgeBaseOperations.java         # Extension point interface
|   |-- KnowledgeBaseManager.java             # Facade for knowledge base operations
|   |-- KnowledgeBaseOperationsProvider.java  # Extension point provider
|   |-- KnowledgeBaseStartup.java             # Startup listener
|   |-- ServoyFolderPackageReader.java        # IPackageReader for .servoy/ directory (filesystem)
|   |-- ServoyBundlePackageReader.java        # IPackageReader for bundle resources (default KB)
|   |-- service/
|   |   |-- RulesCache.java                   # Rules storage and loading
|   |   +-- ServoyEmbeddingService.java       # Vector embeddings with ONNX model
|   +-- util/
|       +-- DebugUtils.java                   # Debug logging utilities
+-- resources/                                 # Default knowledge base files
    |-- system-prompts/                        # System prompts
    |   |-- chat.txt                           # Default chat system prompt
    |   |-- completion.txt                     # Default completion system prompt
    |   |-- documentation.txt                  # Documentation assistant system prompt (Feb 17, 2026)
    |   +-- quickfix.txt                       # QuickFix assistant system prompt (Feb 23, 2026)
    |-- embeddings/                            # Embedding files for RAG
    |   |-- embeddings.list                    # List of embedding files
    |   |-- forms.txt
    |   |-- relations.txt
    |   |-- valuelists.txt
    |   |-- styles.txt
    |   +-- bootstrap/
    |       |-- buttons.txt
    |       +-- labels.txt
    +-- rules/                                 # Rule markdown files
        |-- rules.list                         # List of rule files
        |-- forms.md
        |-- relations.md
        |-- valuelists.md
        |-- styles.md
        +-- bootstrap/
            |-- buttons.md
            +-- labels.md
```

---

## 3. Core Architecture

### 3.1 User Interface (Chat View)

**Component:** `ChatView.java` (Eclipse E4 View Part)  
**Presenter:** `ChatViewPresenter.java` (handles multi-assistant logic)  
**Technology:** SWT with embedded Browser component for rich HTML/CSS rendering

**UI Layout (Updated Feb 18, 2026):**
```
[Browser - Chat Messages]
─────────────────────────
[Text Input Area]
─────────────────────────
[Assistant Selector ▼] [Clear] [Stop] [Send]
```

**Assistant Selector Combo:**
- Dropdown showing available assistants ("VibeCoding Assistant", "Documentation Assistant")
- 200px width to fit display names
- Switches between assistants dynamically
- Preserves independent conversation history per assistant

**Toolbar:**
```
[≡] [Clear] [Stop] [Send]
 ↓
 ├─ Save Instructions
 └─ Load Instructions
```

**Browser Abstraction Layer (NEW - Feb 25, 2026):**

The ChatView uses `BrowserWrapper` and `BrowserFunctionWrapper` to support both:
- **SWT Browser** (Windows, macOS)
- **Chromium Browser** (Linux - via Equo Chromium dependency)

**BrowserWrapper:**
- Automatically selects browser type based on platform
- Delegates all operations to underlying browser
- Methods: `setText()`, `execute()`, `setUrl()`, `getText()`, `isDisposed()`
- Handles large content (>500KB) via temp files on Chromium

**BrowserFunctionWrapper:**
- Abstract base class for all JavaScript bridge functions
- Subclasses implement `function(Object[] arguments)` method
- Automatically creates correct BrowserFunction type based on wrapped browser

**Migration from direct Browser usage:**
- All BrowserFunction inner classes now extend BrowserFunctionWrapper
- All Browser references changed to BrowserWrapper
- Enables cross-platform compatibility without code changes

**Key Features:**
- **Multi-assistant support** - Switch between Chat and Documentation assistants (NEW - Feb 18, 2026)
- Markdown rendering with syntax highlighting
- Streaming responses (partial updates as AI generates text)
- Message history display (per assistant)
- Code diff viewer integration
- Copy to clipboard functionality
- Hamburger menu (≡) for knowledge base management
  - Save Instructions: Copy knowledge base to `.servoy/` directory
  - Load Instructions: Load knowledge base from `.servoy/` directory

### 3.2 AI Integration (LangChain4j)

**Core Class:** `ServoyAiModel.java`

**Responsibilities:**
- Initialize OpenAI or Gemini streaming chat models based on user preferences
- Configure LangChain4j `AiServices` with tools and memory
- Provide system prompts via `systemMessageProvider` (per assistant type)
- Manage single shared memory store (`sharedMemoryStore`) used by all assistants
- Register tools appropriate for each assistant type

**Five Assistant Types (Updated Feb 25, 2026):**

The system provides five specialized assistants. Four implement the `IAssistant` interface for unified management:

```java
public interface IAssistant {
    TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request);
    void clearMemory(String memoryId);
    AssistantType getType();
    String getDisplayName();
}
```

1. **VibeCoding Assistant** (`VibeCodingAssistant.java extends IAssistant`):
   - Interface: `TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request)`
   - Purpose: General conversation and Servoy development assistance
   - System Prompt: `vibe-coding.txt` (~2.4K tokens)
   - Tools: Full toolset (12 tool classes, 40+ individual tools)
   - Memory: 40 messages max, solution-scoped with `-vibe` suffix
   - UI: ChatView (shared with other assistants)
   - LangChain4j generates implementation for annotated interface method

2. **Completion Assistant** (`CompletionAssistent.java`):
   - Interface: `String complete(String prompt)`
   - Purpose: Fast code completion (autocomplete while typing)
   - System Prompt: `completion.txt`
   - Tools: None (stateless, context-only)
   - Memory: **None** (stateless - each completion is independent)
   - Models: Fast models (gpt-4o-mini / gemini-2.0-flash)
   - UI: Inline editor completion
   - **Note:** Does NOT implement IAssistant (not in ChatView dropdown)

3. **Documentation Assistant** (`DocumentationAssistant.java extends IAssistant`):
   - Interface: `TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request)`
   - Purpose: Generate JSDoc documentation from code context
   - System Prompt: `documentation.txt`
   - Tools: **DocumentationTools** - `applyDocumentation()` for file modification
   - Memory: 40 messages max, solution-scoped with `-documentation` suffix
   - UI: Context menu → "Generate Docs" (not in ChatView dropdown yet)
   - LangChain4j generates implementation for annotated interface method

4. **Explain Assistant** (`ExplainAssistant.java extends IAssistant`) ✨ **NEW Feb 25**:
   - Interface: `TokenStream executeRequest(@MemoryId String memoryId, @UserMessage String request)`
   - Purpose: Explain code with intelligent file reading
   - System Prompt: `explain.txt`
   - Tools: **FileReadingTools** - chunked reading for large files
   - Memory: 40 messages max, solution-scoped with `-explain` suffix
   - UI: Context menu → "Explain" (auto-switches to Explain assistant in ChatView)
   - LangChain4j generates implementation for annotated interface method

5. **QuickFix Assistant** (`QuickFixAssistant.java extends IAssistant`):
   - Interface: `String fix(String prompt)` (non-streaming, synchronous)
   - Purpose: Quick fixes for code issues and errors
   - System Prompt: `quickfix.txt`
   - Tools: None currently
   - Memory: 40 messages max, solution-scoped with `-quickfix` suffix
   - Models: Same as main chat models (OpenAI/Gemini)
   - UI: **Not yet in ChatView dropdown** - Currently accessible only programmatically
   - **Implementation Status:** 
     - ✅ Interface and memory management complete in ServoyAiModel
     - ❌ Not yet added to ChatViewPresenter's `availableAssistants` array
     - Future: May be added to UI for conversational quick fixes

**AssistantType Enum:**
```java
public enum AssistantType {
    VIBE_CODING("VibeCoding Assistant", "-vibe"),
    DOCUMENTATION("Documentation Assistant", "-documentation"),
    EXPLAIN("Explain Assistant", "-explain"),
    QUICKFIX("QuickFix Assistant", "-quickfix");
    
    public String getDisplayName();
    public String getMemorySuffix();
}
```

**System Prompt Strategy:**
- Loads prompts from `resources/system-prompts/` or `.servoy/system-prompts/`
- Provided to LangChain4j via `systemMessageProvider` lambda
- Sent with **every request** (stateless LLM APIs require full context)
- RAG-first approach: Core rules in prompt, detailed rules via `getKnowledge` tool

### 3.3 Conversation Memory Management

**Architecture:** Stateless LLM with Client-Side Memory + Shared Memory Store for All Assistants

LLM APIs (OpenAI, Gemini) are **stateless** - they don't remember conversations. All context must be sent with each request.

**Implementation (Updated Feb 23, 2026):**
- **Single Shared Memory Store:**
  - `sharedMemoryStore`: One `InMemoryChatMemoryStore` instance shared by all assistants
  - VibeCoding, Documentation, and QuickFix assistants all use the same store
  - Completion assistant: **No memory** (stateless)
- `ChatMemory`: `MessageWindowChatMemory` with `maxMessages = 40`
- **Memory ID Format**: `<SolutionName>-<AssistantType.getMemorySuffix()>`
  - VibeCoding: `"MySolution-vibe"`
  - Documentation: `"MySolution-documentation"`
  - QuickFix: `"MySolution-quickfix"`
  - Completion: N/A (no memory)
- **Automatic trimming**: LangChain4j removes oldest messages when limit exceeded
- **System prompt**: Always included via `systemMessageProvider` (not stored in memory)

**Memory Management Methods:**
- `clearMemory(String memoryId)` - Clear specific assistant memory
- `clearAllMemories(String solutionName)` - Clear all assistant memories for a solution (iterates through all AssistantType values)
- `getSharedMemoryStore()` - Access the single shared memory store instance

**Benefits of Shared Memory with ID Isolation:**
- Single memory store simplifies architecture (one instance to manage)
- Memory isolation maintained through unique memory IDs per assistant+solution
- Each assistant has isolated conversation context despite shared store
- VibeCoding conversations don't pollute Documentation or QuickFix contexts
- Efficient memory usage (one store instead of multiple)
- Easy to add new assistants (just add AssistantType enum value)
- `clearAllMemories()` can iterate through all types automatically

**Session Management:**
- **Session = Active Servoy Solution** (not view lifecycle)
- Conversation resets when user switches solutions (all assistants cleared via `clearAllMemories()`)
- Prevents context pollution between different projects
- Memory IDs scoped to solution name + assistant type suffix

**Flow:**
1. User sends message → Added to appropriate assistant's memory with solution-scoped ID
2. LangChain4j collects all messages for `memoryId` (e.g., "MySolution-vibe")
3. System prompt added automatically
4. Full context sent to LLM
5. Response received → Added to memory
6. If solution switches → `clearAllMemories()` called for old solution, new `memoryId`s set for current assistant

### 3.3a Multi-Assistant Management in ChatView (Updated Feb 23, 2026)

**ChatViewPresenter Fields:**
```java
private String solutionName = "default";  // Current solution name
private IAssistant currentAssistant;       // Active assistant reference
private IAssistant[] availableAssistants;  // Array of assistants shown in ChatView dropdown
private String currentMemoryId;            // Format: solutionName + assistantType.getMemorySuffix()
```

**Available Assistants in ChatView:**
Currently only 2 assistants are shown in the ChatView dropdown selector:
```java
availableAssistants = new IAssistant[] { 
    Activator.getDefault().getServoyAiModel().getVibeCodingAssistant(), 
    Activator.getDefault().getServoyAiModel().getDocumentationAssistant()
};
```

**Note:** QuickFix Assistant exists in ServoyAiModel and implements IAssistant, but is NOT yet added to the ChatView dropdown. It's intended for programmatic use (quick fixes) rather than conversational chat. Future enhancement may add it to the UI if needed.

**Assistant Switching Flow:**
1. User selects assistant from combo → `onAssistantChanged(int index)` called
2. `currentAssistant = availableAssistants[index]`
3. Update memory ID: `currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix()`
4. Clear UI (conversation display)
5. Reload messages from new assistant's memory via `refreshViewFromMemory()`
6. Log switch: `"Switched to assistant: {displayName} with memory ID: {memoryId}"`

**Message Sending (Polymorphic):**
```java
public void onSendUserMessage(String text) {
    // Clean polymorphic call - no instanceof checks
    currentAssistant.executeRequest(currentMemoryId, text)
        .onPartialResponse(partial -> updateUI())
        .onCompleteResponse(response -> saveToMemory())
        .start();
}
```

**Solution Switching Integration:**
```java
public void onSolutionActivated(String projectName) {
    // Clear all memories for old solution
    servoyAiModel.clearAllMemories(solutionName);
    
    // Update to new solution
    solutionName = projectName != null ? projectName : "default";
    currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix();
    
    // Load knowledge base for new solution
    // ...
}
```

**Benefits:**
- Single view serves all conversational assistants
- Clean polymorphic design (IAssistant interface)
- Easy to add new assistants (just add to array)
- Independent memory per assistant type
- No complex view management

### 3.4 Solution Activation Integration

**Goal:** Reset all assistant conversations AND manage knowledge base when user switches Servoy projects

**Implementation:**

**ChatViewPresenter.java** registers dynamic proxy listener:
```java
@PostConstruct
public void init() {
    // Register IActiveProjectListener via reflection (avoids compile dependency)
    Class<?> listenerClass = Class.forName("com.servoy.eclipse.core.IActiveProjectListener");
    Object listener = Proxy.newProxyInstance(..., (proxy, method, args) -> {
        if ("activeProjectChanged".equals(method.getName())) {
            ServoyProject project = (ServoyProject) args[0];
            onSolutionActivated(project.getProject().getName());
        }
        return null;
    });
    servoyModel.addActiveProjectListener(listener);
}
```

**onSolutionActivated(String projectName) workflow (Updated Feb 23, 2026):**
1. Clear ALL assistant memories for old solution: `clearAllMemories(solutionName)`
   - Clears VibeCoding assistant memory (`oldSolution-vibe`)
   - Clears Documentation assistant memory (`oldSolution-documentation`)
   - Clears QuickFix assistant memory (`oldSolution-quickfix`)
2. Update solution name: `solutionName = projectName != null ? projectName : "default"`
3. Update memory ID to new solution with current assistant suffix: `currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix()`
4. Clear UI conversation history
5. **Manage knowledge base** from `.servoy/` directory:
   - Clear previous knowledge base
   - If `.servoy/` exists: Load from `.servoy/embeddings/` and `.servoy/rules/`
   - If `.servoy/` NOT exists: Load default from bundle resources
6. Clear chat UI and show "New session started" notification

**Auto-creation logic:**
```java
// No auto-creation - let user explicitly manage knowledge base
loaderService.clearKnowledgeBase();

if (fileService.servoyDirectoryExists(project)) {
    // Load from solution-specific .servoy directory
    loaderService.loadFromFileSystem(project.getFolder(".servoy"));
} else {
    // Load default knowledge base from bundle resources
    loaderService.loadFromBundleResources();
}
```

**Benefits:**
- Each solution gets isolated conversation context (all assistants)
- Knowledge base automatically customized per solution
- Solutions without `.servoy/` directory get default knowledge base from bundle
- No manual setup required - system "just works"
- Switching projects feels like starting fresh chat session

**ChatViewPresenter.java** (Direct Listener Registration)
- Registers `IActiveProjectListener` proxy in `@PostConstruct init()` using reflection (avoids compile-time dependency)
- Listens directly to `ServoyModel.activeProjectChanged` events
- When solution changes, calls `onSolutionActivated(projectName)`:
  - Clears ALL LangChain4j memories: `servoyAiModel.clearAllMemories(solutionName)`
    - Clears VibeCoding, Documentation, and QuickFix assistant memories
  - Updates `solutionName = projectName != null ? projectName : "default"`
  - Updates `currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix()`
  - Clears UI conversation history
  - **Manages knowledge base based on `.servoy/` directory:**
    - Clears previous knowledge base
    - If `.servoy/` exists → Loads from `.servoy/` directory (solution-specific)
    - If `.servoy/` doesn't exist → Loads from bundle resources (default)
  - Shows green notification: "New session started - Solution: {name}"
- Unregisters listener in `@PreDestroy dispose()` when chat view closes

**Event Flow:**
```
User opens ChatView
  ↓
ChatViewPresenter @PostConstruct init()
  ↓
Registers IActiveProjectListener with ServoyModel (via reflection)
  ↓
User switches solution
  ↓
ServoyModel.activeProjectChanged(ServoyProject)
  ↓
ChatViewPresenter's listener proxy receives notification
  ↓
ChatViewPresenter.onSolutionActivated(projectName)
  ↓
1. Clear LangChain4j memories for old solution (all assistants)
2. Update solutionName to new solution
3. Update currentMemoryId to new solution + current assistant suffix
4. Clear UI conversation history
5. Get IProject for new solution
6. Clear knowledge base (rules + embeddings)
7. Check if .servoy/ directory exists
   IF EXISTS:
     - Load knowledge base from .servoy/rules/ and .servoy/embeddings/
     - Log: "Knowledge base loaded from .servoy directory"
   IF NOT EXISTS:
     - Load default knowledge base from bundle resources
     - Log: "Default knowledge base loaded from bundle"
8. Clear chat UI
9. Show "New session started" notification
  ↓
User closes ChatView
  ↓
ChatViewPresenter @PreDestroy dispose()
  ↓
Unregisters listener from ServoyModel
```

**Design Benefits:**
- Listener only active when chat view is open (resource efficient)
- Direct communication (no event bus overhead)
- Simple lifecycle management (register on create, unregister on destroy)
- Automatic solution-specific knowledge base isolation
- **Solutions without .servoy/ automatically get default knowledge base from bundle**
- No cross-contamination of rules/embeddings between solutions

---

### 3.5 Save/Load Instructions Feature

**Purpose:** Allow users to save and load AI instructions (rules and embeddings) to/from solution-specific `.servoy/` directories

**Architecture Principles:**
1. **Solution-Specific Knowledge:** Each Servoy solution can have its own customized knowledge base
2. **File System Storage:** Knowledge stored in `.servoy/` hidden directory within each solution project
3. **User-Initiated Actions:** Knowledge base operations triggered via UI menu (not automatic on first use)
4. **Automatic Loading:** When switching solutions, knowledge base automatically loads from `.servoy/` if it exists

**Directory Structure:**
```
<SolutionProject>/
  .servoy/                          (hidden directory)
    ├── system-prompts/
    │   ├── chat.txt                 (custom chat system prompt for this solution)
    │   └── completion.txt           (custom completion prompt for this solution)
    ├── embeddings/
    │   ├── embeddings.list          (list of embedding files to load)
    │   ├── forms.txt
    │   ├── relations.txt
    │   ├── valuelists.txt
    │   ├── styles.txt
    │   └── bootstrap/
    │       ├── buttons.txt
    │       └── labels.txt
    └── rules/
        ├── rules.list               (list of rule files to load)
        ├── forms.md
        ├── relations.md
        ├── valuelists.md
        ├── styles.md
        └── bootstrap/
            ├── buttons.md
            └── labels.md
```

#### 3.5.1 InstructionsSaveService

**File:** `services/InstructionsSaveService.java`

**Purpose:** File system operations for saving knowledge base to `.servoy/` directory

**Key Methods:**
- `getActiveProject()` - Gets active Servoy solution using ServoyModelFinder
- `servoyDirectoryExists(IProject)` - Checks if `.servoy/` exists
- `copyResourcesToSolution(IProject, IProgressMonitor)` - Copies resources from knowledgebase bundle
- `deleteServoyDirectory(IProject, IProgressMonitor)` - Deletes `.servoy/` completely
- `findKnowledgebaseBundle()` - Locates the knowledgebase OSGi bundle
- `createFolderIfNeeded(IFolder, IProgressMonitor)` - Creates folder if it doesn't exist
- `copyBundleDirectory(...)` - Recursively copies from OSGi bundle
- `copyBundleFile(...)` - Copies individual file from bundle
- `createFolderRecursively(...)` - Creates folder hierarchy

**Implementation:** Uses Eclipse IFolder/IFile APIs, follows positive conditional coding rules, proper error handling

**Refactored (Feb 5, 2026):** All methods now use positive conditionals instead of guard clauses

#### 3.5.2 InstructionsLoadService

**File:** `services/InstructionsLoadService.java`

**Purpose:** Knowledge base loading and clearing operations

**Key Methods:**
- `clearKnowledgeBase()` - Clears RulesCache + ServoyEmbeddingService
- `loadFromFileSystem(IFolder)` - Orchestrates loading from `.servoy/` (system-prompts, rules, embeddings)
- `loadFromBundleResources()` - Loads default knowledge base from knowledgebase bundle's resources/
- `isKnowledgeBaseLoaded()` - Checks if KB has content (rules or embeddings)
- `loadRulesFromFolder(IFolder)` - Loads from `.servoy/rules/`
- `loadEmbeddingsFromFolder(IFolder)` - Loads from `.servoy/embeddings/`

**Implementation:** Uses ServoyLog, converts IFolder to Path, validates structure, proper exception handling

**Refactored (Feb 10, 2026):**
- Added `loadFromBundleResources()` for loading default knowledge base from bundle
- Uses `ServoyBundlePackageReader` to read from OSGi bundle's resources/ directory
- Enables fallback to default KB when `.servoy/` doesn't exist

**Refactored (Feb 5, 2026):** 
- Now handles system-prompts/ directory
- All methods use positive conditionals instead of guard clauses
- Looks for `chat-system-prompt.txt` in system-prompts folder

#### 3.5.3 Knowledge Base File System Loading

**RulesCache.loadFromDirectory(Path):**
- Reads `rules.list` → loads each `.md` file
- Intent key from filename: `forms.md` → `FORMS`, `bootstrap/buttons.md` → `BOOTSTRAP_BUTTONS`
- Returns count of loaded rules

**ServoyEmbeddingService.loadFromDirectory(Path):**
- Reads `embeddings.list` → loads each `.txt` file
- Generates embeddings via ONNX model (BGE-small-en-v1.5)
- Adds to in-memory vector store with category metadata
- Returns count of loaded embeddings

---

### 3.6 Tooling (Function Calling)

The AI is empowered with **14 tool classes** containing **45+ individual tools**. Each tool is a Java method annotated with `@Tool` (LangChain4j).

**Tool Categories:**

1. **Eclipse Integration** (`EclipseTools`)
   - File search (text/regex in workspace)
   - Find files (glob patterns)
   - Search and replace (bulk text replacement)

2. **Documentation Tools** (`DocumentationTools`) - NEW Feb 25, 2026
   - `applyDocumentation(filePath, offset, length, content)` - Apply JSDoc to files
   - Backs up original file automatically
   - Handles selection range or full file replacement
   - Integrates with FileModificationTracker

3. **File Reading Tools** (`FileReadingTools`) - NEW Feb 25, 2026
   - `readFile(filePath)` - Read complete file (100KB limit)
   - `readFileLines(filePath, startLine, endLine)` - Read specific line range (max 500 lines)
   - `getFileInfo(filePath)` - Get file metadata without reading content
   - Used by Explain Assistant for chunked reading

4. **Servoy Core Objects** (`tools/core/`)
   - **FormTools**: `getForms()`, `openForm(...)`, `deleteForms(...)`
   - **RelationTools**: `getRelations()`, `openRelation(...)`, `deleteRelations(...)`
   - **ValueListTools**: `getValueLists()`, `openValueList(...)`, `deleteValueLists(...)`
   - **StyleTools**: `getStyles()`, `openStyle(...)`, `deleteStyle(...)`

5. **Servoy Components** (`tools/component/`)
   - **ButtonComponentTools**: `listButtons()`, `addButton()`, `updateButton()`, `deleteButton()`, `getButtonInfo()`
   - **LabelComponentTools**: `listLabels()`, `addLabel()`, `updateLabel()`, `deleteLabel()`, `getLabelInfo()`

6. **Utility Tools** (`tools/utility/`)
   - **DatabaseTools**: `listTables()`, `getTableInfo()`
   - **TargetTools**: `getTarget()`, `setTarget()` (manages active solution/module)
   - **KnowledgeTools**: `getKnowledge()` (RAG - retrieves rules via embeddings)

7. **Documentation Tools** (`tools/`)
   - **DocumentationTools**: `getCurrentSelection()`, `applyDocumentation()` ✨ **NEW (Feb 26)**
     - `getCurrentSelection()` - Retrieves current editor selection with code and API documentation context
     - Uses CodeContextService (in services package) for identifier extraction
     - Returns formatted output: FILE, OFFSET, LENGTH, CODE, API DOCUMENTATION
     - `applyDocumentation()` - Applies generated JSDoc to file at specified offset/length
     - Integrates with FileModificationTracker for backup and tracking

8. **Services (NOT Tools)** (`services/`)
   - **CodeContextService**: Code analysis and context extraction (moved from context package, Feb 26)
     - Analyzes selected code or entire file (when no selection)
     - Extracts API context: types, documentation for all identifiers
     - Supports: Servoy API, Web Components, Web Services, Solution Functions
     - Returns structured CodeContext with documentation
     - Used by DocumentationTools and context menu handlers
   - **CompareEditorService**: Opens Eclipse compare editors ✨ **NEW (Feb 26)**
     - Singleton service accessible to all agents
     - `openCompareEditor(fileName, original, modified)` - Opens side-by-side comparison
     - Used by ChatViewPresenter for modified files tracking

**Tool Execution Flow:**
1. User asks question in chat
2. LangChain4j sends message to LLM with available tools
3. LLM decides if it needs more information
4. LLM requests tool execution (e.g., "call listTables with serverName='example_data'")
5. LangChain4j executes Java method (e.g., `DatabaseTools.listTables("example_data")`)
6. Result returned to LLM
7. LLM generates final response using tool result
8. Response streamed to chat UI

---

### 3.7 Code Context Gathering ✅ COMPLETE

**Purpose:** Extract API context from selected JavaScript code to provide AI with type information and documentation.

**Architecture:**

```
User Selection → SelectionTracker → CodeContextService → AST Analysis → Documentation Extraction → Formatted Context
```

**Components:**

1. **SelectionTracker (Singleton)**
   - Implements `ISelectionListener` 
   - Monitors editor selections across workspace
   - Returns `SelectionInfo` (file path, offset, length, text, source module)
   - Lifecycle: initialized on first use, disposed on plugin shutdown

2. **CodeContextService** ✅ COMPLETE (Moved to services package Feb 26, 2026)
   - **Location:** `services/CodeContextService.java` (previously in context package)
   - Parses JavaScript using DLTK's `JavaScriptParserUtil`
   - Runs `TypeInferencer2` with custom `IdentifierCollectingVisitor`
   - Extracts context for each identifier (name, type, documentation)
   - Returns `CodeContext` with complete documentation
   - Handles errors gracefully (syntax errors, missing types)
   - **Used by:** DocumentationTools, ServoyAiContextMenuHandler, any agent needing code analysis

3. **IdentifierCollectingVisitor (AST Visitor)**
   - Extends DLTK's `TypeInferencerVisitor`
   - Collects identifiers within selection range
   - Stores: `Map<JSNode, Pair<IValueReference, String>>` for identifiers
   - Handles nested properties (e.g., `plugins.ngdesktop.openFile`)
   - Deduplicates identifiers by name+type

4. **Documentation Extraction** ✅ COMPLETE (Phase 3 - Feb 13, 2026, Enhanced Feb 16, 2026)
   
   - **Servoy API** (`IdentifierKind.SERVOY_API`): ✅ COMPLETE
     - Uses `ScriptObjectRegistry.getScriptObjectByName(typeName)` → `ITypedScriptObject`
     - Accesses `IObjectDocumentation` with complete API metadata
     - Extracts: function signatures, parameters (@param), return types (@return), descriptions
     - **✨ NEW (Feb 16):** Extracts @sample code examples via `fdoc.getSample(ClientSupport.ng)`
     - **✨ NEW (Feb 16):** Extracts deprecation info via `fdoc.isDeprecated()` and `fdoc.getDeprecatedText()`
     - Handles overloaded functions (shows most complete signature)
     - Supports TYPE_FUNCTION, TYPE_PROPERTY, TYPE_CONSTANT
     - Uses `DocumentationUtil.getJavaToJSTypeTranslator()` for type conversion
     - Formats with `ClientSupport.ng` for NG Client compatibility
     - **Documentation Flow:** XML files generated from Java JSDoc → Runtime loaded via ScriptObjectRegistry
   
   - **Solution Functions** (`IdentifierKind.SOLUTION_FUNCTION`): ✅ COMPLETE
     - Detects type == "Function"
     - Locates `IModelElement` using `ReferenceLocation` + visitor pattern
     - Reads ScriptDoc via `ScriptdocContentAccess.getContentReader()`
     - Filters out internal metadata (@properties= lines)
     - Returns clean ScriptDoc with @param, @return, @description tags
   
   - **Web Components** (`IdentifierKind.WEB_COMPONENT`): ✅ COMPLETE
     - Extracts component name from `RuntimeWebComponent<componentName>` (handles `_abs` suffix)
     - **Uses TypeCreator for merged _doc.js + .spec documentation:**
       - Gets `TypeCreator` via `TypeProviderFactory.getTypeProvider().getTypeCreator()`
       - Looks up `Type` via `typeCreator.findType(null, fullTypeName)`
       - Iterates `Type.getMembers()` to extract Method/Property documentation
       - Method.getDescription() contains **merged _doc.js + .spec content**
       - Handles optional parameters with `ParameterKind.OPTIONAL`
       - Formats: `identifier.methodName(param1, param2?)`
   
   - **Web Services** (`IdentifierKind.WEB_SERVICE`): ✅ COMPLETE
     - Extracts service name from `WebService<serviceName>`
     - **Uses TypeCreator for merged _doc.js + .spec documentation** (same as components)
     - Shared implementation via `extractWebObjectDocumentationFromTypeCreator()`

   **Debug Output (Feb 16, 2026):**
   - Consolidated debug logging in `CodeContextService` (closer to extraction code)
   - Output format:
     ```
     === CODE CONTEXT EXTRACTION ===
     File: /path/to/file.js
     Selection: offset=X, length=Y
     Selected text:
     <code snippet>
     --------------------------------
     
     identifier -> documentation with @sample and @deprecated
     
     identifier -> no docs
     
     --------------------------------
     Total: N identifiers
     ================================
     ```
   - Simple, non-duplicated output showing all identifiers with or without documentation

5. **Context Menu Integration**
   - Dynamic menu: "Servoy AI" (always visible in JavaScript editors)
   - Works with selection or full file (when no selection)
   - Commands: Debug, Review, Generate Docs, Generate Tests
   - Handler routes to `CodeContextService` for analysis
   - No circular dependencies (self-contained via Eclipse command pattern)

**Technical Implementation Details:**

**DLTK Integration:**
- Added DLTK bundles to `Require-Bundle` (not `Import-Package`)
- Follows same approach as `com.servoy.eclipse.debug` (TypeCreator's bundle)
- Dependencies: `org.eclipse.dltk.core`, `org.eclipse.dltk.javascript.core`, `org.eclipse.dltk.javascript.ui`, `org.eclipse.dltk.ui`
- Uses DLTK's "internal" Type model (Type, Method, Property, Parameter) - same as TypeCreator
- No forbidden reference errors (bundle-level access bypasses package restrictions)

**New Public APIs Created:**
- `TypeProvider.getTypeCreator()` - exposes TypeCreator instance (added to com.servoy.eclipse.debug)
- `TypeProviderFactory.getTypeProvider()` - static singleton access (added to com.servoy.eclipse.debug)
- Both exported from com.servoy.eclipse.debug bundle

**Data Flow:**

```
1. User selects code in JavaScript editor (or no selection for full file)
2. SelectionTracker captures selection (or creates full file range if no selection)
3. User clicks "Servoy AI → Generate Docs" (or LLM calls getCodeContext())
4. CodeContextService.getCodeContext(selectionInfo):
   a. Parse JavaScript → AST (JavaScriptParserUtil)
   b. Run TypeInferencer2 + IdentifierCollectingVisitor
   c. For each identifier:
      - Determine kind (SERVOY_API, WEB_COMPONENT, WEB_SERVICE, SOLUTION_FUNCTION)
      - Extract documentation from appropriate source:
        * SERVOY_API → ScriptObjectRegistry + IObjectDocumentation
        * SOLUTION_FUNCTION → ScriptdocContentAccess
        * WEB_COMPONENT/WEB_SERVICE → TypeCreator + Type.getMembers()
      - Create IdentifierContext with complete docs
   d. Deduplicate by name+type
   e. Return CodeContext with XML-formatted output
5. Return formatted context to caller (LLM or context menu handler)
```

**Status:**
- ✅ Phase 1: DTOs and SelectionTracker (COMPLETE - Feb 12, 2026)
- ✅ Phase 2: AST Analysis (COMPLETE - Feb 12, 2026)
- ✅ Phase 3: Documentation Extraction (COMPLETE - Feb 13, 2026)
  - ✅ Enhanced with @sample and @deprecated support (Feb 16, 2026)
  - ✅ Consolidated debug output (Feb 16, 2026)
  - ✅ Phase 3.6: XML Formatting (COMPLETE - Feb 13, 2026)
- ✅ Phase 4: LLM Tool Integration (COMPLETE - Feb 13, 2026)
  - ✅ Optimized to single method handling both selection and full file
  - ✅ Removed stub methods (getCodeContextForFile, getCodeContextForSelection)
  - ✅ Context menu now always visible (not selection-dependent)
- ⏳ Phase 5: Testing and Refinement (IN PROGRESS - Feb 16, 2026)
  - Manual testing using TEST_CODE_CONTEXT.md
  - Debug output validation complete
- ⏳ Phase 6: Knowledge Base & Agents (PARTIALLY COMPLETE)
  - ✅ Architecture documentation complete
  - ⏳ Agent implementation pending

**Total Implementation Time:** ~19 hours (including @sample/@deprecated enhancements and debug improvements)

---

### 3.8 Code Context Package - Detailed Reference

**Package:** `com.servoy.eclipse.servoypilot.context`

This package provides code analysis infrastructure for extracting API context from JavaScript code.

#### Core Classes:

**CodeContextService** (Singleton)
- **Purpose:** Main orchestrator for code context extraction
- **Key Method:** `getCodeContext(SelectionInfo) → CodeContext`
- **Responsibilities:**
  - Parse JavaScript using DLTK's JavaScriptParserUtil
  - Run type inference with TypeInferencer2
  - Extract documentation for all identifier types
  - Return formatted CodeContext with XML output
- **Error Handling:** Graceful degradation - returns error context on parse failures
- **Thread Safety:** Uses workspace locks when accessing DLTK model

**SelectionTracker** (Singleton, ISelectionListener)
- **Purpose:** Monitors editor selections across workspace
- **Lifecycle:**
  - Initialized on first `getInstance()` call
  - Registers with `ISelectionService` 
  - Disposed on plugin shutdown via `Activator`
- **Key Method:** `getCurrentSelection() → Optional<SelectionInfo>`
- **Smart Behavior:**
  - If text selected: Returns SelectionInfo for that range
  - If no selection (length=0): Reads entire file via `module.getSource()` and creates full file range
- **Thread Safety:** Synchronized getInstance(), uses LOCK object

**IdentifierCollectingVisitor** (TypeInferencerVisitor)
- **Purpose:** AST visitor that collects identifiers with type information
- **Extends:** DLTK's `TypeInferencerVisitor`
- **Data Structures:**
  - `Map<JSNode, Pair<IValueReference, String>> identifiers` - collected identifiers
  - `Map<JSNode, List<IValueReference>> propertiesOrCalls` - properties/methods on identifiers
- **Key Features:**
  - Only collects identifiers within specified offset/length range
  - Handles nested properties (e.g., `plugins.ngdesktop.openFile`)
  - Deduplicates by name+type combination

**ServoyAiContextMenu** (CompoundContributionItem)
- **Purpose:** Dynamic context menu contribution
- **Visibility:** Always visible in JavaScript editors (checks for `ITextSelection` instanceof)
- **Menu Structure:**
  ```
  Servoy AI
  ├─ Debug
  ├─ Review
  ├─ ─────────
  ├─ Generate Docs
  └─ Generate Tests
  ```

**ServoyAiContextMenuHandler** (AbstractHandler)
- **Purpose:** Handles all context menu command executions
- **Current Status:** Stub implementations with TODOs for agents
- **Pattern:** Routes to CodeContextService for analysis

#### Data Transfer Objects (DTOs):

**SelectionInfo** (Immutable)
- **Fields:** filePath, offset, length, selectedText, sourceModule
- **Factory:** `create(...)` returns `Optional<SelectionInfo>`
- **Validation:** Ensures non-null filePath and sourceModule, non-negative offset/length
- **Supports:** Both text selections and full file ranges

**IdentifierContext** (Immutable)
- **Fields:** name, typeName, documentation, kind (enum)
- **IdentifierKind Enum:** SERVOY_API, WEB_COMPONENT, WEB_SERVICE, SOLUTION_FUNCTION, UNKNOWN
- **Formatting:**
  - `toFormattedString()` - Plain text for display
  - `toFormattedXML()` - XML format: `<type>name: TypeName</type><description>docs</description>`

**CodeContext** (Immutable)
- **Fields:** selectionInfo, identifiers (List), hasError, errorMessage
- **Factory Methods:**
  - `success(SelectionInfo, List<IdentifierContext>)`
  - `error(SelectionInfo, String)`
  - `empty(SelectionInfo)`
- **Output Methods:**
  - `getFormattedXML()` - Concatenated XML from all identifiers
  - `getFormattedPlainText()` - Human-readable format

#### Usage Example:

```java
// From LLM tool
SelectionTracker tracker = SelectionTracker.getInstance();
Optional<SelectionInfo> selection = tracker.getCurrentSelection();

if (selection.isPresent()) {
    CodeContextService service = CodeContextService.getInstance();
    CodeContext context = service.getCodeContext(selection.get());
    
    if (!context.hasError()) {
        String xmlContext = context.getFormattedXML();
        // Send to LLM or use in agent
    }
}
```

#### Extension Points:

To add new identifier types:
1. Add enum value to `IdentifierKind`
2. Add detection logic in `CodeContextService.determineIdentifierKind()`
3. Add extraction method (e.g., `extractMyTypeDocumentation()`)
4. Call from `extractIdentifierContext()`

---

### 3.9 Line-Based Documentation Architecture (March 9, 2026)

**Status:** ✅ IMPLEMENTATION COMPLETE - ✅ TESTED AND WORKING

#### Overview

Refactored Documentation Assistant from **signature-based matching** to **line-based positioning** using Eclipse's ITextSelection API. Eliminates whitespace fragility, handles multi-line functions naturally, and supports variable documentation.

#### Evolution

**March 5, 2026 (Signature-Based):**
- AI returned JSON array with function signatures
- Tool used string matching to find declarations
- Issue: Fragile to whitespace differences
- Issue: Failed on multi-line function declarations

**March 9, 2026 (Line-Based):**
- AI returns JSON array with line numbers
- Tool uses direct line positioning from Eclipse editor
- Robust: No string matching, no whitespace sensitivity
- Natural: Multi-line functions handled automatically
- Enhanced: Full variable documentation support

#### Architecture Components

**Package Structure:**
```
tools/
  DocumentationTools.java           # Main tool with 3 @Tool methods
  dto/
    DocumentationItem.java          # Record: startLine, endLine, startSentence, endSentence, jsdoc

context/dto/
  SelectionInfo.java                # Extended with startLine, endLine fields (0-based)

context/
  SelectionTracker.java             # Captures line numbers from ITextSelection

services/documentation/
  JSDocManipulator.java             # Line-based JSDoc operations (no AST)
  DocumentationValidator.java       # UUID preservation + JSDoc syntax validation

exceptions/
  ValidationException.java          # Custom exception for validation failures
```
**Package Structure:**
```
tools/
  DocumentationTools.java           # Main tool with 3 @Tool methods
  dto/
    DocumentationItem.java          # Record: startLine, endLine, startSentence, endSentence, jsdoc

context/dto/
  SelectionInfo.java                # Extended with startLine, endLine fields (0-based)

context/
  SelectionTracker.java             # Captures line numbers from ITextSelection

services/documentation/
  JSDocManipulator.java             # Line-based JSDoc operations (no AST)
  DocumentationValidator.java       # UUID preservation + JSDoc syntax validation

exceptions/
  ValidationException.java          # Custom exception for validation failures
```

#### Workflow

**6-Step Process (System Prompt):**

1. **Call `getCurrentSelection()`** → Get code with line numbers
2. **Analyze code** → Identify functions/variables to document, note line numbers
3. **Call `getDocumentationForIdentifiers(["id1", "id2"])`** → Pull API docs (optional, if Servoy types)
4. **Generate JSDoc** → Create documentation with accurate types
5. **Return line-based list** → Call `applyDocumentations(filePath, hash, items)` with line numbers
6. **Provide brief summary** → 1-2 sentences to user (no JSDoc repetition)

**getCurrentSelection() Output Format:**
```
FILE: /MyProject/forms/customers.js
START_LINE: 0
END_LINE: 10
TOTAL_LINES: 11
CONTENT_HASH: 123456789

--- CODE ---
0: var customers;
1: 
2: /**
3:  * Old incomplete JSDoc
4:  */
5: function onLoad(event) {
6:     var foundset = databaseManager.getFoundSet('db:/test/table');
7:     return foundset;
8: }
9: 
10: var count = 0;
--- END CODE ---
```

**Structured Output Format (AI Response):**
```json
[
  {
    "startLine": 0,
    "endLine": 0,
    "startSentence": "",
    "endSentence": "",
    "jsdoc": "/**\n * Main customer foundset.\n * @type {JSFoundSet}\n */"
  },
  {
    "startLine": 2,
    "endLine": 4,
    "startSentence": "/**",
    "endSentence": "*/",
    "jsdoc": "/**\n * Handles form load event.\n * @param {JSEvent} event\n * @returns {JSFoundSet}\n */"
  }
]
```

#### Line-Based Positioning

**INSERT Mode (No Existing JSDoc):**
- `startLine == endLine`
- `startSentence == ""` and `endSentence == ""`
- Inserts JSDoc **before** the specified line
- Example: Line 5 has function → insert JSDoc at line 5 (pushes function down)

**REPLACE Mode (Existing JSDoc):**
- `startLine` and `endLine` specify range to replace
- `startSentence` and `endSentence` validate correct location
- Tool checks line startLine starts with startSentence
- Tool checks line endLine ends with endSentence
- If validation passes → replace lines startLine through endLine with new JSDoc
- If validation fails → error message with details

**Example:**
```javascript
// Lines 2-4 have old JSDoc, line 5 has function
2: /**
3:  * Old incomplete JSDoc
4:  */
5: function onLoad(event) {

// AI sends: startLine=2, endLine=4, startSentence="/**", endSentence="*/"
// Tool validates: line 2 starts with "/**" ✓, line 4 ends with "*/" ✓
// Tool replaces lines 2-4 with new JSDoc
```

#### JSDocManipulator (String-Based, No AST)

**Key Methods:**
- `extractIndentation(String line)` → String - Gets leading whitespace
- `linesToString(List<String>)` → String - Joins lines with \n
- `stringToLines(String)` → List<String> - Splits into line list

**Why No AST:**
- Direct line manipulation is simpler and faster
- No parsing overhead
- Handles syntax errors gracefully (can still insert JSDoc)
- Works with any JavaScript code (even incomplete)

#### Validation

**Triple Validation (DocumentationValidator):**

1. **UUID Preservation (CRITICAL):**
   - Extracts all `@UUID` annotations from original content
   - Extracts UUIDs from each replaced JSDoc range
   - Restores original UUIDs in new JSDoc if AI changed them
   - **Silently fixes UUID corruption** - no error, just restoration
   - If final validation fails → error with details

2. **JSDoc Syntax:**
   - Validates JSDoc blocks start with `/**` and end with `*/`
   - Checks basic structure
   - If invalid → error message

3. **Location Validation:**
   - For REPLACE mode: Validates startSentence/endSentence match
   - For INSERT mode: Validates line numbers in bounds
   - If validation fails → detailed error with line numbers

#### Tool Methods

**Tool 1:** `getCurrentSelection()`
- Returns code with 0-based line numbers
- Includes START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH
- Line-numbered code output for easy reference

**Tool 2:** `getDocumentationForIdentifiers(String[] identifiers)`
- Same as before (no changes)
- Returns API documentation for Servoy types

**Tool 3:** `applyDocumentations(String filePath, String expectedHash, List<DocumentationItem> items)`

**Process:**
1. Get file and current content
2. Verify content hash (change detection)
3. Backup original file (FileModificationTracker)
4. Split content into line list
5. Sort items bottom-to-top (avoid line number shifts)
6. For each item:
   - If INSERT mode: Insert JSDoc before specified line
   - If REPLACE mode: Validate + replace specified line range
   - Extract original UUIDs from replaced range
   - Restore UUIDs in new JSDoc
7. Join lines back to string
8. Write modified content
9. Clear editor selection
10. Return success or detailed error

#### Benefits

**Robustness:**
- ✅ Zero whitespace fragility (no string matching)
- ✅ Multi-line functions handled automatically
- ✅ Direct line reference from Eclipse editor
- ✅ Works with syntax errors (no AST parsing)

**Safety:**
- ✅ Zero UUID corruption risk (preservation + restoration)
- ✅ Location validation (startSentence/endSentence)
- ✅ Change detection (content hash)
- ✅ Atomic file operations

**Efficiency:**
- ✅ 50-80% token reduction vs. full-file approach (maintained from previous)
- ✅ No AST parsing overhead
- ✅ Simple string operations

**Maintainability:**
- ✅ Clean package structure (tools/services/exceptions)
- ✅ Simple line-based logic (no complex AST)
- ✅ Easy to understand and debug

**Variable Documentation:**
- ✅ Full support for file-level variables
- ✅ Proper JSDoc format (description first, then @type)
- ✅ Configuration objects supported
- ✅ Constants supported

#### System Prompt Updates

**File:** `resources/system-prompts/documentation.txt`

**Major Changes (March 9, 2026):**
- STEP 1 updated: getCurrentSelection() returns line-numbered code
- STEP 5 rewritten: Return line-based JSON list with INSERT/REPLACE semantics
- STEP 6 kept: Provide brief summaries (1-2 sentences max)
- Tool 1 updated: Document line-numbered output format
- Tool 3 updated: Document line-based applyDocumentations() parameters
- All examples updated: Show line-based approach (with note about old format)
- Error handling updated: Cover line validation scenarios
- **NEW:** "WHAT TO DOCUMENT" section with clear DO/DON'T guidance
- **NEW:** Variable JSDoc format examples (description first, @type second)
- **Text-based markers:** [DO], [DON'T], [YES], [NO] instead of emojis
- **19 Important Rules:** Updated to emphasize line-based positioning

#### Testing Status

✅ **TESTED AND WORKING (March 9, 2026):**
- ✅ Variable documentation with correct JSDoc format
- ✅ INSERT mode (new JSDoc before function/variable)
- ✅ REPLACE mode (replacing existing JSDoc)
- ✅ UUID preservation working correctly
- ✅ Change detection working
- ✅ File modification tracking integration
- ✅ Multi-line not an issue (line-based approach)

**Issues Found & Fixed:**
- ✅ Variable format: Changed from `@description` tag to description-first format
- ✅ Emojis removed: Replaced with text-based markers ([DO], [YES], etc.)

---#### Known Limitations

**Current (MVP):**
- Top-level declarations only (functions and variables)
- No nested function support
- No ES6 class support
- JavaScript only (no TypeScript)

**Future Enhancements:**
- Nested function documentation
- ES6 class methods
- Arrow functions
- TypeScript support
- Batch file documentation

#### Implementation Metrics

- **Development Time:** ~6 hours
- **Files Created:** 9 new classes
- **Files Modified:** 2 (DocumentationTools + system prompt)
- **Lines of Code:** ~1,500 lines
- **Compilation Status:** ✅ Zero errors

---

## 4. Key Workflows

### 4.1 Chat Interaction (with Tool Usage)

```
1. User types message in ChatView
2. ChatViewPresenter creates background Job ("ServoyAI: ...")
3. Job sends message to Assistant (LangChain4j)
4. LangChain4j adds system prompt automatically
5. LangChain4j collects messages from memory (current solution)
6. Full context sent to LLM (OpenAI/Gemini)
7. LLM processes and may request tool execution
   ? If tool needed: LangChain4j calls Java method, returns result to LLM
   ? LLM uses result to refine answer
8. LLM generates final response (streaming)
9. Response chunks sent to ChatViewPresenter
10. UI updates incrementally (streaming effect)
11. Response added to memory for current solution
```

### 4.2 Solution Switching (with Knowledge Base Loading)

```
1. User opens ChatView
2. ChatViewPresenter registers IActiveProjectListener with ServoyModel
3. User clicks different solution in Servoy Solution Explorer
4. ServoyModel fires activeProjectChanged event
5. ChatViewPresenter's listener receives notification directly
6. ChatViewPresenter.onSolutionActivated() executes:
   a. Clears old solution's LangChain4j memory
   b. Updates currentMemoryId = new solution name
   c. Clears UI chat history
   d. Gets IProject for new solution
   e. Clears knowledge base (rules + embeddings)
   f. Checks if .servoy/ directory exists in new solution:
      IF EXISTS:
        - InstructionsLoadService.loadFromFileSystem(.servoy/)
        - Loads solution-specific knowledge base
      IF NOT EXISTS:
        - InstructionsLoadService.loadFromBundleResources()
        - Loads default knowledge base from bundle
   g. Loads system prompts (solution-specific or default)
   h. Shows "New session started" notification
7. Next message uses new memoryId and appropriate knowledge base (isolated per solution)
```

### 4.3 Menu Handlers for Knowledge Base Management

**Refresh Instructions Handler:**
- Loads knowledge base from `.servoy/` directory
- If `.servoy/` doesn't exist, creates it first with defaults from bundle
- Clears and reloads knowledge base
- User-initiated action from menu

**Reset Instructions Handler:**
- Overwrites `.servoy/` directory with fresh defaults from bundle
- Shows confirmation dialog if `.servoy/` already exists
- Deletes old, copies new, then loads into AI
- Useful for restoring default instructions

### 4.4 Configuration

**Preference Page:** `ServoyPilotPreferencePage`

**Settings:**
- AI Provider: OpenAI / Gemini
- API Key (secure storage)
- Model Name (e.g., "gpt-4", "gemini-1.5-pro")

**Storage:** Eclipse `IPreferenceStore` (scoped to plugin)

---

## 5. Dependencies

### 5.1 OSGi Bundle Dependencies

**Requires (MANIFEST.MF):**
- `org.eclipse.ui` - Workbench, views, preferences
- `org.eclipse.core.runtime` - Jobs, preferences, platform services
- `org.eclipse.e4.core.di` - Dependency injection (@Inject, @PostConstruct)
- `org.eclipse.e4.core.services` - IEventBroker (E4 event system)
- `org.eclipse.swt` - UI widgets, Browser component
- `com.servoy.eclipse.model` - ServoyProject, ServoyModel
- `com.servoy.eclipse.core` - Servoy core services (via reflection)
- `com.servoy.eclipse.servoypilot.langchain4j` - LangChain4j libraries

**Import-Package (key entries):**
- `dev.langchain4j.model.*` - LLM models (OpenAI, Gemini)
- `dev.langchain4j.memory.*` - Chat memory interfaces
- `dev.langchain4j.store.memory.chat.*` - Memory storage
- `dev.langchain4j.service` - AIServices, Tool annotations
- `org.osgi.service.event` - Event objects
- `org.sablo.specification` - IPackageReader for SPM package operations
- `com.servoy.eclipse.knowledgebase.service` - RulesCache, ServoyEmbeddingService
- `com.servoy.eclipse.model.util` - ServoyLog for logging

### 5.2 External Libraries (via langchain4j bundle)

- LangChain4j Core 1.10.0+
- LangChain4j OpenAI 1.10.0+
- LangChain4j Google AI Gemini 1.10.0+
- LangChain4j Embeddings (for knowledge base)
- OkHttp, Gson, Retrofit (transitive dependencies)

---

## 6. Configuration Files

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
1. Check if `.servoy/` folder exists in solution root
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

**Architecture Highlights:**
- 3 OSGi bundles (main plugin, langchain4j wrapper, knowledgebase)
- 5 specialized assistants: VibeCoding, Completion, Documentation, Explain, QuickFix
  - **ChatView dropdown:** 2 assistants visible (VibeCoding, Documentation)
  - **Context menu:** Explain (auto-switches to ChatView), Generate Docs (uses tool)
  - **Programmatic only:** QuickFix (not yet in UI)
  - **Inline completion:** Completion (not in ChatView)
- Clean separation: UI (Views) → Presenters → Services → Tools
- Stateless LLM with client-side memory management (single shared store, ID-based isolation)
- Direct listener registration for solution activation events
- Service layer for business logic
- **Browser abstraction:** BrowserWrapper + BrowserFunctionWrapper for cross-platform support

---

## 🧪 TESTING DOCUMENTATION ASSISTANT (February 26, 2026)

### **Updated Workflow (Tool-Based):**
- ✅ Generic user message (NO code in chat)
- ✅ AI calls `getCurrentSelection()` tool to retrieve code
- ✅ AI receives code + API documentation context
- ✅ AI generates JSDoc
- ✅ AI calls `applyDocumentation()` to apply changes
- ✅ Clean chat UI - no huge code blocks

### **Prerequisites:**
1. Valid AI API key configured (OpenAI or Gemini)
2. Active Servoy solution open
3. JavaScript file with functions to document

### **Test Scenario 1: Document Selected Functions**

**Steps:**
1. Open a JavaScript file (e.g., form JS file)
2. Select 1-3 functions (including function body)
3. Right-click → "Servoy AI" → "Generate Docs"
4. **Observe:** ChatView opens with generic message "Please generate JSDoc documentation for the current selection."
5. **Observe:** AI calls `getCurrentSelection()` tool (visible in chat)
6. Wait for AI to process (may take 10-30 seconds)

**Expected Results:**
- ✅ ChatView shows clean message (no code block)
- ✅ AI calls `getCurrentSelection()` tool
- ✅ AI receives code + API documentation
- ✅ AI calls `applyDocumentation` tool
- ✅ File modified with JSDoc comments above functions
- ✅ File appears in "Modified files" section at bottom
- ✅ **Click file → Compare editor opens showing original vs modified** ✨ FIXED (Feb 26)
- ✅ Can click Keep/Undo/Remove buttons

**Verify:**
- JSDoc format is correct (`/** ... */`)
- @param tags match function parameters (uses API context for types)
- @returns tag matches return type
- Descriptions are relevant to code
- UUIDs in code are NEVER modified (RULE ZERO protection)

### **Test Scenario 2: Document Entire File**

**Steps:**
1. Open a JavaScript file
2. Click anywhere (no selection)
3. Right-click → "Servoy AI" → "Generate Docs"
4. **Observe:** AI retrieves entire file content via `getCurrentSelection()`
5. Wait for processing

**Expected Results:**
- ✅ AI documents ALL functions in file
- ✅ File appears in "Modified files" section
- ✅ Can review changes in compare editor

### **Test Scenario 3: Compare Editor (NEW - Feb 26)**

**Steps:**
1. Generate documentation (scenario 1 or 2)
2. Click file name in "Modified files" section
3. Compare editor opens

**Expected Results:**
- ✅ Eclipse compare editor opens (side-by-side view)
- ✅ Left side: Original content (before JSDoc)
- ✅ Right side: Modified content (with JSDoc)
- ✅ Differences highlighted in yellow/green
- ✅ Labels show "Original" vs "Modified"

### **Test Scenario 4: Keep Changes**

**Steps:**
1. Generate documentation
2. Review in compare editor
3. Click [✓] (Keep) icon next to file in "Modified files"

**Expected Results:**
- ✅ File removed from "Modified files" list
- ✅ Changes remain in file
- ✅ Cannot undo anymore (changes kept)

### **Test Scenario 5: Undo Changes**

**Steps:**
1. Generate documentation
2. Click [✗] (Undo) icon next to file

**Expected Results:**
- ✅ Original content restored
- ✅ File removed from "Modified files" list
- ✅ No JSDoc comments in file

### **Test Scenario 6: UUID Protection**

**Steps:**
1. Open a JavaScript file with UUID annotations (e.g., `@UUID xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)
2. Select functions with UUIDs
3. Right-click → "Servoy AI" → "Generate Docs"

**Expected Results:**
- ✅ UUIDs are preserved EXACTLY (not modified)
- ✅ UUIDs are not documented (ignored by AI)
- ✅ JSDoc added AROUND UUIDs, not replacing them
- ✅ System prompt RULE ZERO enforces UUID protection

### **Troubleshooting:**
- **No response:** Check AI API key in preferences
- **Tool not called:** Check console for errors - AI should call `getCurrentSelection()` first
- **File not appearing:** Check FileModificationTracker is working
- **Bad JSDoc format:** Try different selection or adjust prompt
- **Compare editor doesn't open:** Check console for errors, verify CompareEditorService is loaded
- **UUIDs modified:** CRITICAL BUG - report immediately (RULE ZERO violation)

---

**End of Architecture Reference**

**Last Updated:** February 25, 2026  
**Status:** Production Ready
- ✅ Multi-Assistant View Switcher Complete (5 assistants: VibeCoding, Documentation, Explain, QuickFix, Completion)
- ✅ Memory Refactoring Validated (single shared store, ID-based isolation)
- ✅ Code Context Complete (Phases 1-4)
- ✅ **Documentation Assistant COMPLETE** - Ready for testing
- ✅ Explain Assistant Complete (from Cristi)
- ✅ Browser Abstraction Layer (cross-platform support)
- ✅ Modified Files Tracking (Keep/Undo/Remove)

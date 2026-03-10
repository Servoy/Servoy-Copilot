# ServoyPilot - Architecture Reference

**Last Updated:** March 10, 2026  
**Purpose:** Complete technical reference for understanding the system design and component structure

**Status:** 
- ✅ Multi-Assistant View Switcher implemented and functional
- ✅ **Memory Store Refactoring COMPLETE** - Single source of truth (memory store only)
- ✅ **Memory Refactoring VALIDATED** - Testing complete, system working correctly
- ✅ Code Context Gathering complete (Phases 1-4)
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

**3. Documentation Tools** (`DocumentationTools.java` - 571 lines):
- **Tool 1:** `getCurrentSelection()` - Returns code with LINE NUMBERS (0-based)
  - Format: FILE, START_LINE, END_LINE, TOTAL_LINES, CONTENT_HASH
  - Each line prefixed with line number (e.g., `0: var customers;`)
  - Eliminates need for offset/length calculations
- **Tool 2:** `getDocumentationForIdentifiers(String[] identifiers)` - On-demand API doc lookup
  - Soft limit: 20 identifiers (encourages prioritization)
  - Supports nested identifiers: `"plugins.ngdesktop"`, `"elements.button"`
  - Returns formatted XML documentation for requested identifiers
  - Reports "NOT FOUND" for missing identifiers
  - Uses CodeContextService for extraction
- **Tool 3:** `applyDocumentations(filePath, contentHash, items)` - LINE-BASED JSDoc application
  - Accepts List<DocumentationItem> with line ranges
  - INSERT mode: empty validation strings, inserts before specified line
  - REPLACE mode: validates with startSentence/endSentence, replaces line range
  - UUID protection via DocumentationValidator
  - Backs up original file (once per file via FileModificationTracker)
  - Clears editor selection after application
  - Returns success/error messages to AI

**4. Supporting Components:**
- **DocumentationItem (DTO):** Record with startLine, endLine, startSentence, endSentence, jsdoc
- **DocumentationValidator:** UUID extraction/restoration + JSDoc syntax validation
- **CodeContextService:** API documentation extraction for identifiers
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
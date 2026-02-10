# ServoyPilot - Architecture Reference

**Last Updated:** February 10, 2026  
**Purpose:** Complete technical reference for understanding the system design and component structure

**Status:** All features implemented and functional

**Recent Updates (Feb 10, 2026):**
- **Knowledge base loading fixed**: 
  - **REMOVED auto-create** `.servoy/` directory on solution activation
  - Now loads from `.servoy/` directory **IF it exists** (solution-specific customization)
  - Otherwise loads **default knowledge base from bundle resources** (knowledgebase bundle)
  - Created `ServoyBundlePackageReader` for reading from OSGi bundle resources
  - Added `InstructionsLoadService.loadFromBundleResources()` method
  - Users must explicitly use "Reset Instructions" to create `.servoy/` directory with defaults
- **Architecture improvements**:
  - `ChatViewPresenter.onSolutionActivated()` now implements conditional loading logic
  - Cleaner separation between default (bundle) and customized (filesystem) knowledge bases
  - No unwanted file system modifications on solution activation

**Previous Updates (Feb 9, 2026):**
- **Form parent form setting fixed**: FormService.setFormParent() now uses correct API (setExtendsID(String)) - works on all versions
- **Form properties and events enhanced**: 
  - Properties can be set on both NEW and EXISTING forms (removed isNewForm restrictions)
  - Events auto-create methods with skeleton code if they don't exist
  - Added comprehensive examples in forms.md (18 examples)
- **Knowledge base loading refactored**:
  - Now loads ONLY from `.servoy/` directory in solution root (if exists)
  - Removed NGPackageManager dependency
  - Created ServoyFolderPackageReader for direct folder reading
- **Chat memory clearing on Refresh/Reset**:
  - Both "Refresh Instructions" and "Reset Instructions" now clear chat memory
  - Clears chat UI for visual feedback
  - User starts with completely fresh conversation (no prior context)
  - Added Activator.clearChatMemory(memoryId) method
- **Documentation updates**:
  - Updated forms.md with critical rule #12: properties/events/extendsForm work on EXISTING forms
  - Added Example 16 showing how to set inheritance on existing forms
  - Added test prompts for form properties and events (12 test scenarios)

**Previous Updates (Feb 5, 2026):**
- **System Prompts from .servoy directory**: ServoyAiModel now loads chat-system-prompt.txt from active solution's .servoy/system-prompts/ first, with fallback to resources
- **File renamed**: core-system-prompt.txt → chat-system-prompt.txt in knowledgebase bundle
- **Services refactored**: InstructionsLoadService, InstructionsSaveService (formerly InstructionsFileService) now follow positive conditional coding rules
- **System prompts support**: InstructionsLoadService now handles system-prompts/ directory loading
- **DatabaseTools refactored**: Fixed double-call bug in getTableInfo(), added comprehensive debug logging, follows positive conditionals
- **Test prompts for DatabaseTools**: Added database-tools-test-prompts.md with 20 test scenarios
- Added comprehensive form properties support (18 properties)
- Added complete form events support (13 events + 1 command)
- Implemented DebugUtils system (controlled by -Dconsole.debug=true)

---

## 1. Overview

ServoyPilot is an Eclipse plugin that provides AI-assisted development specifically for the Servoy platform. It **replaces GitHub Copilot** with a specialized assistant that understands Servoy's metadata-driven architecture (Forms, Relations, ValueLists, Components, etc.).

**Key Differentiator:** Unlike generic AI assistants, ServoyPilot uses specialized tools to safely manipulate Servoy objects without corrupting .frm files or other metadata.

**Technology Stack:**
- Eclipse E4 (UI framework)
- LangChain4j 1.10.0+ (AI orchestration)
- OpenAI GPT-4 / Google Gemini (LLM providers)
- SWT/Browser (Chat UI)
- OSGi (Plugin architecture)

---

## 2. Project Structure

Three Eclipse bundles (OSGi plugins):

```
com.servoy.eclipse.servoypilot/               # Main plugin
|-- src/com/servoy/eclipse/servoypilot/
|   |-- Activator.java                        # Plugin lifecycle
|   |-- ai/
|   |   |-- Assistant.java                    # LangChain4j AIService interface
|   |   +-- ServoyAiModel.java                # AI model initialization, memory management, system prompt loading
|   |-- chatview/parts/
|   |   |-- ChatView.java                     # SWT/Browser UI component (with hamburger menu)
|   |   |-- ChatViewPresenter.java            # Chat logic, conversation management, solution activation
|   |   |-- CodeEditingService.java           # Diff generation (JGit)
|   |   +-- ApplyPatchWizardHelper.java       # Code patch application UI
|   |-- preferences/
|   |   |-- AiConfiguration.java              # AI provider settings
|   |   +-- ServoyPilotPreferencePage.java    # Preferences UI
|   |-- services/                             # Business logic services
|   |   |-- InstructionsSaveService.java      # File operations for .servoy/ directory (save)
|   |   |-- InstructionsLoadService.java      # Knowledge base loading/clearing (load)
|   |   |-- BootstrapComponentService.java    # Bootstrap component operations
|   |   |-- ContextService.java               # Solution/module context management
|   |   |-- DatabaseSchemaService.java        # Database schema operations
|   |   |-- FormService.java                  # Form CRUD operations
|   |   |-- RelationService.java              # Relation CRUD operations
|   |   |-- StyleService.java                 # Style operations
|   |   +-- ValueListService.java             # ValueList operations
|   |-- tools/                                # AI Tools (Function Calling)
|   |   |-- EclipseTools.java                 # File search, find, replace
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
|   |       |-- ContextTools.java             # Context: get/set active solution/module
|   |       +-- KnowledgeTools.java           # RAG: getKnowledge for rules retrieval
+-- src/main/resources/
    +-- prompts/
        |-- core-system-prompt.txt            # Default system prompt (2.4K tokens, OpenAI)
        +-- core-system-prompt-gemini.txt     # Gemini-specific prompt (3.5K tokens, chain-of-thought)

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
|   +-- service/
|       |-- RulesCache.java                   # Rules storage and loading (bundle + file system)
|       +-- ServoyEmbeddingService.java       # Vector embeddings with ONNX model (bundle + file system)
+-- resources/                                 # Default knowledge base files
    |-- system-prompts/                        # System prompts
    |   +-- chat-system-prompt.txt             # Default chat system prompt
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
**Presenter:** `ChatViewPresenter.java` (handles logic)  
**Technology:** SWT with embedded Browser component for rich HTML/CSS rendering

**Toolbar:**
```
[≡] [Clear] [Stop] [Send]
 ↓
 ├─ Save Instructions
 └─ Load Instructions
```

**Key Features:**
- Markdown rendering with syntax highlighting
- Streaming responses (partial updates as AI generates text)
- Message history display
- Code diff viewer integration
- Copy to clipboard functionality
- Hamburger menu (≡) for knowledge base management
  - Save Instructions: Copy knowledge base to `.servoy/` directory
  - Load Instructions: Load knowledge base from `.servoy/` directory

### 3.2 AI Integration (LangChain4j)

**Core Class:** `ServoyAiModel.java`

**Responsibilities:**
- Initialize OpenAI or Gemini streaming chat model based on user preferences
- Configure LangChain4j `AiServices` with tools and memory
- Provide system prompt via `systemMessageProvider`
- Manage chat memory store (InMemoryChatMemoryStore)
- Register 12 tool classes (40+ individual tools)

**Assistant Interface:** `Assistant.java` (LangChain4j AIService)
- Defines the interaction contract: `chat(UserMessage)`, `chat(List<ChatMessage>)`
- Handles streaming responses via `StreamingResponseHandler`

**System Prompt Strategy:**
- Loads condensed prompt from `/prompts/core-system-prompt.txt` (~2.4K tokens)
- Provided to LangChain4j via `systemMessageProvider` lambda
- Sent with **every request** (stateless LLM APIs require full context)
- RAG-first approach: Core rules in prompt, detailed rules via `getKnowledge` tool

### 3.3 Conversation Memory Management

**Architecture:** Stateless LLM with Client-Side Memory

LLM APIs (OpenAI, Gemini) are **stateless** - they don't remember conversations. All context must be sent with each request.

**Implementation:**
- `ChatMemoryStore`: `InMemoryChatMemoryStore` (LangChain4j)
- `ChatMemory`: `MessageWindowChatMemory` with `maxMessages = 40`
- **Memory ID**: Current Servoy solution name (e.g., "MySolution", "Module_A")
- **Automatic trimming**: LangChain4j removes oldest messages when limit exceeded
- **System prompt**: Always included via `systemMessageProvider` (not stored in memory)

**Session Management:**
- **Session = Active Servoy Solution** (not chat window lifecycle)
- Conversation resets when user switches solutions
- Prevents context pollution between different projects
- Old solution's memory is cleared via `chatMemoryStore.deleteMessages(memoryId)`

**Flow:**
1. User sends message ? Added to memory for current solution
2. LangChain4j collects all messages for `memoryId`
3. System prompt added automatically
4. Full context sent to LLM
5. Response received ? Added to memory
6. If solution switches ? Old memory cleared, new `memoryId` set

### 3.4 Solution Activation Integration

**Goal:** Reset conversation AND manage knowledge base when user switches Servoy projects

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

**onSolutionActivated(String projectName) workflow:**
1. Clear old solution's chat memory: `clearMemory(currentMemoryId)`
2. Update memory ID to new solution: `currentMemoryId = projectName`
3. Clear UI conversation history
4. **Check for `.servoy/` directory in solution root**:
   - If **NOT exists**: Auto-create with default content from knowledgebase bundle (no dialog)
   - If **exists**: Use existing content
5. **Load knowledge base** from `.servoy/` directory:
   - Clear previous knowledge base
   - Load embeddings and rules from `.servoy/embeddings/` and `.servoy/rules/`
6. Clear chat UI and show "New session started" notification

**Auto-creation logic:**
```java
if (!fileService.servoyDirectoryExists(project)) {
    // Auto-create .servoy directory with default content (no dialog)
    fileService.copyResourcesToSolution(project, null);
}
// Load from .servoy (either existing or newly created)
if (fileService.servoyDirectoryExists(project)) {
    loaderService.clearKnowledgeBase();
    loaderService.loadFromFileSystem(project.getFolder(".servoy"));
}
```

**Benefits:**
- Each solution gets isolated conversation context
- Knowledge base automatically customized per solution
- New solutions automatically get default knowledge base
- User never manually manages knowledge base - it "just works"
- Switching projects feels like starting fresh chat session

**ChatViewPresenter.java** (Direct Listener Registration)
- Registers `IActiveProjectListener` proxy in `@PostConstruct init()` using reflection (avoids compile-time dependency)
- Listens directly to `ServoyModel.activeProjectChanged` events
- When solution changes, calls `onSolutionActivated(projectName)`:
  - Clears LangChain4j memory: `servoyAiModel.clearMemory(currentMemoryId)`
  - Updates `currentMemoryId = projectName`
  - Clears UI conversation history
  - **Manages knowledge base based on `.servoy/` directory:**
    - If `.servoy/` exists → Clears and loads knowledge base from directory
    - If `.servoy/` doesn't exist → Clears knowledge base (empty state)
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
1. Clear LangChain4j memory for old solution
2. Update currentMemoryId to new solution
3. Clear UI conversation history
4. Get IProject for new solution
5. Check if .servoy/ directory exists
   IF NOT EXISTS:
     - Auto-create .servoy/ with default content from knowledgebase bundle (no dialog)
     - Log: "Creating .servoy directory with default content"
   (ALWAYS proceeds to loading after this point)
6. Load knowledge base from .servoy/ directory:
     - Clear knowledge base (rules + embeddings)
     - Load from .servoy/rules/ and .servoy/embeddings/
     - Log: "Knowledge base loaded from .servoy directory"
7. Clear chat UI
8. Show "New session started" notification
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
- **Every solution automatically gets knowledge base** (no manual setup required)
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
    │   └── chat-system-prompt.txt  (custom system prompt for this solution)
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

#### 3.5.4 Handlers

**SaveInstructionsHandler:**
1. Get active project
2. Check if `.servoy/` exists → show override confirmation if yes
3. Background Job (3 steps):
   - Delete old `.servoy/` (if override)
   - Copy from knowledgebase bundle
   - Load into AI
4. Show success/error dialog

**LoadInstructionsHandler:**
1. Get active project
2. Background Job (2 steps):
   - Create `.servoy/` if missing (auto-provision)
   - Load into AI
3. Show success/error dialog

**Complete Flow:**
```
User clicks "Save Instructions" (hamburger menu)
  ↓
SaveInstructionsHandler → Get active project
  ↓
Check .servoy/ exists → Override confirmation (if exists)
  ↓
Background Job:
  [1/3] Delete old .servoy/
  [2/3] Copy: system-prompts/, embeddings/, rules/
  [3/3] Load: RulesCache + ServoyEmbeddingService
  ↓
Success: "Instructions saved to <project>/.servoy/"
```

---

### 3.6 Tooling (Function Calling)

The AI is empowered with **12 tool classes** containing **40+ individual tools**. Each tool is a Java method annotated with `@Tool` (LangChain4j).

**Tool Categories:**

1. **Eclipse Integration** (`EclipseTools`)
   - File search (text/regex in workspace)
   - Find files (glob patterns)
   - Search and replace (bulk text replacement)

2. **Servoy Core Objects** (`tools/core/`)
   - **FormTools**: `getForms()`, `openForm(...)`, `deleteForms(...)`
   - **RelationTools**: `getRelations()`, `openRelation(...)`, `deleteRelations(...)`
   - **ValueListTools**: `getValueLists()`, `openValueList(...)`, `deleteValueLists(...)`
   - **StyleTools**: `getStyles()`, `openStyle(...)`, `deleteStyle(...)`

3. **Servoy Components** (`tools/component/`)
   - **ButtonComponentTools**: `listButtons()`, `addButton()`, `updateButton()`, `deleteButton()`, `getButtonInfo()`
   - **LabelComponentTools**: `listLabels()`, `addLabel()`, `updateLabel()`, `deleteLabel()`, `getLabelInfo()`

4. **Utility Tools** (`tools/utility/`)
   - **DatabaseTools**: `listTables()`, `getTableInfo()`
   - **ContextTools**: `getContext()`, `setContext()` (manages active solution/module)
   - **KnowledgeTools**: `getKnowledge()` (RAG - retrieves rules via embeddings)

**Tool Execution Flow:**
1. User asks question in chat
2. LangChain4j sends message to LLM with available tools
3. LLM decides if it needs more information
4. LLM requests tool execution (e.g., "call listTables with serverName='example_data'")
5. LangChain4j executes Java method (e.g., `DatabaseTools.listTables("example_data")`)
6. Result returned to LLM
7. LLM generates final response using tool result
8. Response streamed to chat UI

### 3.7 FormService - Comprehensive Property & Event Support

**File:** `services/FormService.java`

**Purpose:** Business logic for form CRUD operations with extensive property and event handler support

**Recent Enhancements (Feb 5, 2026):**
- Extended from 9 to 18 supported form properties
- Added complete event handler support (13 events + 1 command)
- Added helper methods for parsing selection mode and scrollbars values
- Follows positive conditional coding rules

**Supported Properties (18 total):**

1. **Dimension Properties:**
   - `width`, `height` - Direct dimension setting
   - `useMinWidth`, `useMinHeight` - Minimum dimension constraints

2. **Data & Core Properties:**
   - `dataSource` - Database table binding (`db:/server/table`)
   - `namedFoundSet` - Named foundset ("empty", "separate", or relation name)
   - `initialSort` - Default sort order (e.g., "name asc, id desc")

3. **UI & Display Properties:**
   - `showInMenu` - Show in Window menu (Servoy Client)
   - `styleName` - Servoy style name
   - `styleClass` - CSS class name
   - `titleText` - Form window title
   - `transparent` - Transparent background
   - `navigatorID` - Navigator form ID
   - `scrollbars` - Scrollbar settings ("horizontal", "vertical", "both")

4. **Selection & Behavior:**
   - `selectionMode` - Record selection ("default", "single", "multi")

5. **Metadata:**
   - `deprecated` - Deprecation message/info

**Supported Events (14 total):**

**Lifecycle Events (5):**
- `onLoad` - Form loaded/reloaded from repository
- `onUnLoad` - Form unloaded from repository
- `onShow` - Form displayed
- `onHide` - Form hidden
- `onBeforeHide` - Before form hides (can prevent)

**Record Events (4):**
- `onRecordSelection` - Record selected
- `onBeforeRecordSelection` - Before selection (can prevent)
- `onRecordEditStart` - User starts editing record
- `onRecordEditStop` - Record being saved (can prevent)

**Element Events (3):**
- `onElementDataChange` - Data changed in component
- `onElementFocusGained` - Component gains focus
- `onElementFocusLost` - Component loses focus

**UI Events (1):**
- `onResize` - Form resized

**Commands (1):**
- `onSort` - Sort command triggered

**Key Methods:**

```java
// Create form in specific project
Form createFormInProject(ServoyProject, name, width, height, style, dataSource)

// Apply properties to form
void applyFormProperties(Form, Map<String, Object> properties)

// Apply events to form
void applyFormEvents(Form, Map<String, String> events, ServoyProject)

// Set form inheritance
void setFormParent(Form, parentFormName, ServoyProject)

// Helper methods
int parseSelectionMode(String value)  // "single" → SELECTION_MODE_SINGLE
int parseScrollbars(String value)     // "both" → SCROLLBARS_BOTH
String resolveMethodUUID(Form, String methodName)  // Method name → UUID
```

**Design Approach:**
- **Permissive pattern:** Type checks, null checks, silent ignore on type mismatch
- **Reports only actual setter exceptions** to caller
- **No validation beyond type checking**
- **Method resolution:** Events require method names, silently skipped if method not found

**Example Usage:**
```java
// Create form with properties and events
Map<String, Object> props = new HashMap<>();
props.put("titleText", "Customer Management");
props.put("styleClass", "customer-form");
props.put("selectionMode", "single");
props.put("scrollbars", "vertical");

Map<String, String> events = new HashMap<>();
events.put("onLoad", "initForm");
events.put("onShow", "refreshData");

Form form = FormService.createFormInProject(project, "customerForm", 1024, 768, "css", dataSource);
FormService.applyFormProperties(form, props);
FormService.applyFormEvents(form, events, project);
```

**Coding Rules Compliance:**
- Follows positive conditionals (happy path flows naturally)
- No unnecessary else blocks
- Error handling at method edges
- Clean top-to-bottom flow

---

### 3.8 DatabaseTools - Schema Operations with Bug Fix

**File:** `tools/utility/DatabaseTools.java`

**Purpose:** Database schema operations for listing tables and retrieving detailed table information

**Tools (2 total):**
- `listTables(serverName)` - Lists all tables in a database server
- `getTableInfo(serverName, tableName)` - Retrieves detailed table info (columns, types, primary keys)

**Critical Bug Fixed (Feb 5, 2026):**

**Problem:** `getTableInfo()` had a race condition bug caused by calling `col.getColumnType()` **twice**:
```java
// BEFORE (BUGGY - double call):
String colTypeName = col.getColumnType() != null ? col.getColumnType().toString() : "UNKNOWN";
```

First call checked for null, second call could return different value or throw exception.

**Solution:** Call once and store the result:
```java
// AFTER (FIXED - single call):
Object columnTypeObj = col.getColumnType();
if (columnTypeObj != null)
{
    colTypeName = columnTypeObj.toString();
}
```

**Additional Improvements:**
- Positive conditionals throughout (no guard clauses)
- Try-catch around column type retrieval for safety
- Per-column error handling (failures don't break entire operation)
- Comprehensive debug logging (controlled by `-Dconsole.debug=true`)
- Clean top-to-bottom flow

**Test Prompts:**
- See `testprompts/database-tools-test-prompts.md` (20 test scenarios)

---

### 3.9 DebugUtils - Console Debugging System

**Files:** 
- `com.servoy.eclipse.servoypilot/src/.../util/DebugUtils.java`
- `com.servoy.eclipse.servoypilot.knowledgebase/src/.../util/DebugUtils.java`

**Purpose:** Centralized debug logging controlled by VM argument `-Dconsole.debug=true`

**Activation:**
Add to Eclipse launch configuration or eclipse.ini:
```
-Dconsole.debug=true
```

**API Methods:**
```java
// Simple log message
DebugUtils.log("ComponentName", "Message with details");

// Log method entry with parameters
DebugUtils.logMethodEntry("ClassName", "methodName", param1, param2);

// Log method exit with return value
DebugUtils.logMethodExit("ClassName", "methodName", returnValue);

// Log exceptions with stack trace
DebugUtils.logException("ComponentName", "Error message", exception);

// Add separator for readability
DebugUtils.logSeparator();

// Check if debug is enabled (avoid expensive string operations)
if (DebugUtils.isDebugEnabled()) {
    DebugUtils.log("Component", "Expensive: " + expensiveOp());
}
```

**Output Format:**
```
[ServoyPilot-DEBUG] [ComponentName] Message
[ServoyPilot-DEBUG] [ClassName.methodName] ENTRY - Params: value1, value2
[ServoyPilot-DEBUG] [ClassName.methodName] EXIT - Return: resultValue
[ServoyPilot-DEBUG] [ComponentName] ERROR: Error message
```

**Currently Instrumented:**
- **DatabaseTools:** `listTables()`, `getTableInfo()` - Complete execution trace
- **KnowledgeBaseManager:** `loadKnowledgeBasesForSolution()` - Package discovery, loading stats
- **FormService:** Ready for instrumentation (not yet added)

**Benefits:**
- **Zero overhead when disabled** - Simple boolean check
- **Consistent format** - All messages prefixed `[ServoyPilot-DEBUG]`
- **Easy to filter** - Grep by component or method name
- **Full stack traces** - Exception logging includes printStackTrace
- **Easy to extend** - Add to any class/method as needed

**Example Output:**
```
[ServoyPilot-DEBUG] [DatabaseTools.listTables] ENTRY - Params: example_data
[ServoyPilot-DEBUG] [DatabaseTools] Found 15 tables in example_data
[ServoyPilot-DEBUG] [DatabaseTools.listTables] EXIT - Return: 15 tables

[ServoyPilot-DEBUG] [KnowledgeBaseManager.loadKnowledgeBasesForSolution] ENTRY - Params: ServoyProject@12345
[ServoyPilot-DEBUG] [KnowledgeBaseManager] Solution is ServoyProject: MySolution
[ServoyPilot-DEBUG] [KnowledgeBaseManager] Discovered 1 knowledge base packages
[ServoyPilot-DEBUG] [KnowledgeBaseManager]   Package 1: com.servoy.eclipse.servoypilot.knowledgebase
[ServoyPilot-DEBUG] [KnowledgeBaseManager] Knowledge base loading complete:
[ServoyPilot-DEBUG] [KnowledgeBaseManager]   - Embeddings: 256
[ServoyPilot-DEBUG] [KnowledgeBaseManager]   - Rules: 5
[ServoyPilot-DEBUG] [KnowledgeBaseManager]   - Available intents: FORMS, RELATIONS, VALUELISTS, STYLES, BOOTSTRAP_BUTTONS
```

**Documentation:** See `DEBUG_SYSTEM.md` for complete usage guide

---

### 3.10 Code Editing & Diffing

**Services:**
- `CodeEditingService`: Generates diffs between existing code and AI-proposed code (uses JGit)
- `ApplyPatchWizardHelper`: UI workflow for reviewing and applying patches to workspace files

**Workflow:**
1. AI suggests code changes
2. Service generates unified diff
3. Wizard displays side-by-side comparison
4. User reviews and applies changes

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
        - Logs: "Knowledge base loaded from .servoy directory"
      IF NOT EXISTS:
        - InstructionsLoadService.loadFromBundleResources()
        - Loads default knowledge base from knowledgebase bundle's resources/
        - Logs: "Default knowledge base loaded from bundle"
   g. Clears chat UI
   h. Shows "New session started" notification
7. Next message uses new memoryId and solution-specific or default knowledge base (isolated per solution)
```

**Key Change (Feb 10, 2026):** No auto-creation of `.servoy/` - loads default knowledge base from bundle instead. Users must use "Reset Instructions" menu to create customized `.servoy/` directory.

### 4.3 Save/Load Instructions Workflows

**Refresh Instructions (Feb 9, 2026 - enhanced):**
```
1. User clicks menu → "Refresh Instructions"
2. RefreshInstructionsHandler.execute() runs
3. Handler gets active project
4. Background Job starts:
   Step 1: Create .servoy/ if doesn't exist (with defaults)
   Step 2: Clear KB + Load from .servoy/
   Step 3: Clear chat memory (Activator.clearChatMemory("default"))
   Step 4: Clear chat UI (visual feedback)
5. Success dialog: "Chat history has been cleared - starting fresh conversation"
6. User starts with completely fresh context
```

**Reset Instructions (Feb 9, 2026 - enhanced):**
```
1. User clicks menu → "Reset Instructions"
2. ResetInstructionsHandler.execute() runs
3. Handler gets active project
4. If .servoy/ exists → show confirmation dialog
5. Background Job starts:
   Step 1: Delete old .servoy/ (if overriding)
   Step 2: Copy default resources from knowledgebase bundle to .servoy/
   Step 3: Clear KB + Load from new .servoy/
   Step 4: Clear chat memory (Activator.clearChatMemory("default"))
   Step 5: Clear chat UI (visual feedback)
6. Success dialog: "Chat history has been cleared - starting fresh conversation"
7. User starts with default knowledge base and fresh conversation
```

**Key Enhancement:** Both operations now clear chat memory, ensuring user truly starts fresh with no prior conversation context.

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
1. **Solution-specific** (highest priority): `.servoy/system-prompts/chat-system-prompt.txt` in active solution
2. **Plugin resources** (fallback): Provider-specific defaults from plugin bundle
3. **Final fallback**: Hardcoded minimal prompt

**Loading Flow (ServoyAiModel.loadSystemPrompt()):**
```java
1. Try loadSystemPromptFromSolution()
   → Check active project's .servoy/system-prompts/chat-system-prompt.txt
   → If found: Load and return (solution-specific customization)
   
2. If not found, select from plugin resources based on AI provider:
   → Gemini: /prompts/core-system-prompt-gemini.txt (~3.5K tokens)
   → OpenAI: /prompts/core-system-prompt.txt (~2.4K tokens)
   
3. If resource not found: Return "You are a Servoy development assistant."
```

**Benefits of Solution-Specific Prompts:**
- Customize AI behavior per project/team
- Include project-specific coding standards
- Add domain-specific instructions
- Override default behavior for special solutions

**Default Prompt Contents:**
1. **7 Critical Rules:**
   - User control (respect cancellations)
   - Never guess parameters
   - Tool transparency (announce before/after calls)
   - Forbidden file editing (.frm files)
   - Tool usage restrictions
   - Scope boundaries (stay within current project)
   - Non-Servoy request rejection

2. **Mandatory 5-Step Workflow:**
   - Step 1: Analyze request (is it Servoy-related?)
   - Step 2: Generate action list (2-4 word phrases)
   - **Step 3: CALL getKnowledge TOOL** (mandatory RAG retrieval)
   - Step 4: Create plan (based on retrieved knowledge)
   - Step 5: Execute (with transparency)

3. **Context Management:**
   - **ONLY ONE CONTEXT ACTIVE AT A TIME**
   - Context = where items are created (active solution or specific module)
   - Context persists until explicitly changed via `setContext`

4. **Servoy Basics:**
   - Forms, Relations, ValueLists, Components overview
   - DataSource format: `db:/server_name/table_name`
   - Hierarchy: Forms must exist before adding components

**RAG Strategy:**
- Core prompt: General behavior and workflow
- `getKnowledge` tool: Domain-specific rules retrieved on-demand via embeddings
- Benefits: Reduced token overhead, always up-to-date knowledge, relevant retrieval

**Token Usage:**
- Default prompts: ~2.4K-3.5K tokens sent with every request
- Necessary because OpenAI/Gemini APIs are stateless (no MCP session state)
- 86% reduction from original GitHub Copilot instructions (17K → 2.4K)

---

## 8. Extending the System

### 8.1 Adding New Tools

1. Create tool class in `com.servoy.eclipse.servoypilot.tools` (or subdirectory)
2. Annotate methods with `@Tool` (description for LLM)
3. Annotate parameters with `@P` (description for LLM)
4. Register instance in `ServoyAiModel.createAiServices()`:
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

1. Add new enum value to `AiConfiguration.AiProvider`
2. Create builder method in `ServoyAiModel`:
   ```java
   private AnthropicStreamingChatModel createAnthropicModel(AiConfiguration conf) {
       return AnthropicStreamingChatModel.builder()
           .apiKey(conf.getApiKey())
           .modelName(conf.getModel())
           .build();
   }
   ```
3. Add case to switch statement in `ServoyAiModel` constructor
4. Update preference page UI to show new provider option

### 8.3 Modifying System Prompt

1. Edit `/src/main/resources/prompts/core-system-prompt.txt` or `core-system-prompt-gemini.txt`
2. Rebuild plugin (build.properties ensures file is included)
3. **Warning:** Keep prompt concise (< 3K tokens recommended)
4. Use getKnowledge tool for detailed rules (don't embed everything in prompt)

### 8.4 Customizing Solution-Specific Knowledge Base

1. Click "Save Instructions" to create `.servoy/` directory in solution
2. Edit files in `.servoy/rules/` or `.servoy/embeddings/` as needed
3. Click "Load Instructions" to reload into AI
4. Knowledge base now customized for that specific solution

**Use Cases:**
- Project-specific coding standards
- Custom component usage guidelines
- Solution-specific business rules
- Team-specific best practices

---

## 9. Knowledge Base Architecture (Feb 10, 2026 - Updated)

### 9.1 Overview

The knowledge base system uses **Retrieval-Augmented Generation (RAG)** with local ONNX embeddings for fast, offline semantic search.

**Major Refactoring (Feb 10, 2026):**
- **Conditional Loading:** Loads from `.servoy/` directory IF it exists, otherwise loads from bundle resources
- **No Auto-Creation:** Removed automatic `.servoy/` directory creation on solution activation
- **Bundle-Based Fallback:** New `ServoyBundlePackageReader` reads default KB from knowledgebase bundle
- **Two Loading Paths:** 
  - Solution-specific: `.servoy/` directory (customized knowledge base)
  - Default: Bundle resources (default knowledge base from plugin)

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

**Loading Source (Feb 9, 2026):**
- **File System ONLY** (`.servoy/` directories):
  - `loadFromPackageReader(IPackageReader)` now receives ServoyFolderPackageReader
  - Reads `rules/rules.list` for file list
  - Loads each `.md` file

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

**Loading Source (Feb 9, 2026):**
- **File System ONLY** (`.servoy/` directories):
  - `loadKnowledgeBaseFromReader(IPackageReader)` receives ServoyFolderPackageReader
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
   - Check `.servoy/system-prompts/chat-system-prompt.txt` exists
   - Verify filename is exactly `chat-system-prompt.txt` (with hyphens)
   - Check console for "SYSTEM PROMPT LOADED FROM SOLUTION" message
   - If not found, falls back to plugin resources (expected behavior)

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

7. **Gemini tool calling fails**: Thought signature not supported
   - Known issue: Gemini 2.0+ requires `thought_signature` in function calls
   - LangChain4j 1.10.0 doesn't support this yet
   - Workaround: Use Gemini 1.5 Pro or OpenAI GPT-4

**Debug Tips:**
- Check Eclipse Error Log view for exceptions
- Monitor console output for ServoyLog messages (prefixed with component name)
- Use "Load Instructions" to manually trigger knowledge base loading
- Check `.servoy/` directory structure matches expected format

---

## 15. Feature Summary

**Core Features:**
- ✅ AI-powered chat interface with streaming responses
- ✅ 40+ specialized tools for Servoy development (12 tool classes)
- ✅ Solution-specific conversation memory (automatic reset on switch)
- ✅ Solution-specific system prompts (loaded from `.servoy/system-prompts/chat-system-prompt.txt`)
- ✅ Dual-prompt fallback system (OpenAI GPT-4 / Google Gemini resources)
- ✅ RAG with local ONNX embeddings (offline, fast)
- ✅ **Refresh/Reset Instructions clear chat memory** (fresh conversation guaranteed)
- ✅ **Auto-create `.servoy/` on solution activation** (no manual setup required)
- ✅ Automatic knowledge base loading on solution activation
- ✅ Background jobs for non-blocking operations
- ✅ Code diff viewer and patch application

**Knowledge Base Features (Feb 9, 2026 - Refactored):**
- ✅ **Simplified loading:** ONLY from `.servoy/` directory (no NGPackageManager)
- ✅ **Direct folder reading:** New ServoyFolderPackageReader for fast access
- ✅ **Auto-creation:** Every solution gets default knowledge base automatically
- ✅ Solution-specific customization via `.servoy/` directories
- ✅ System prompts, rules, and embeddings all supported
- ✅ Semantic search with 80% similarity threshold
- ✅ Intent-based rule retrieval
- ✅ Variable substitution in rules (e.g., `{{PROJECT_NAME}}`)

**Form Management (Feb 9, 2026 - Enhanced):**
- ✅ **Properties work on existing forms** (not just new ones)
- ✅ **Events work on existing forms** with auto-created methods
- ✅ **Inheritance (extendsForm) works on existing forms** (no delete/recreate needed)
- ✅ 18 form properties supported (width, height, dataSource, styleClass, etc.)
- ✅ 13 form events + 1 command supported (onLoad, onShow, onRecordSelection, etc.)
- ✅ Fixed setFormParent() to use correct API (setExtendsID(String))

**UI Features:**
- ✅ Markdown rendering with syntax highlighting
- ✅ Refresh/Reset Instructions menus
- ✅ Progress dialogs for background operations
- ✅ Confirmation dialogs before overwriting
- ✅ Auto-scroll toggle
- ✅ Copy to clipboard
- ✅ Clear chat UI on Refresh/Reset for visual feedback

**Testing & Quality:**
- ✅ Test prompts suite (60+ test scenarios)
  - `testprompts/form-tools-test-prompts.md` (30 prompts + 12 properties/events tests)
  - `testprompts/database-tools-test-prompts.md` (20 prompts)
- ✅ Debug system with DebugUtils (controlled by `-Dconsole.debug=true`)
- ✅ Comprehensive error handling and logging
- ✅ Positive conditional coding rules followed throughout

**Architecture Highlights:**
- 3 OSGi bundles (main plugin, langchain4j wrapper, knowledgebase)
- Clean separation: UI (ChatView) → Presenter → Services → Tools
- Stateless LLM with client-side memory management
- Direct listener registration (no event bus)
- Service layer for business logic
- All services refactored to follow Java coding rules (positive conditionals)

---

**End of Architecture Reference**

**Last Updated:** February 9, 2026 (Knowledge base loading refactored, Forms enhanced, Chat memory clearing)
**All Features:** Implemented and Functional  
**Status:** Production Ready

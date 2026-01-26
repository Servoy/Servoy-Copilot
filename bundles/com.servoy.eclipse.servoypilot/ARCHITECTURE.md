# ServoyPilot - Architecture Reference

**Last Updated:** January 26, 2026  
**Purpose:** Technical reference for understanding the system design and component structure

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
|   |   +-- ServoyAiModel.java                # AI model initialization, memory management
|   |-- chatview/parts/
|   |   |-- ChatView.java                     # SWT/Browser UI component
|   |   |-- ChatViewPresenter.java            # Chat logic, conversation management
|   |   |-- CodeEditingService.java           # Diff generation (JGit)
|   |   +-- ApplyPatchWizardHelper.java       # Code patch application UI
|   |-- preferences/
|   |   |-- AiConfiguration.java              # AI provider settings
|   |   +-- ServoyPilotPreferencePage.java    # Preferences UI
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
|   +-- services/                             # Business logic services (used by tools)
+-- src/main/resources/
    +-- prompts/
        +-- core-system-prompt.txt            # System prompt (2.4K tokens)

com.servoy.eclipse.servoypilot.langchain4j/   # LangChain4j wrapper bundle
+-- libs/                                      # LangChain4j JARs + dependencies

com.servoy.eclipse.servoypilot.knowledgebase/ # Knowledge base (RAG embeddings)
+-- src/                                       # Vector stores, rules cache
```

---

## 3. Core Architecture

### 3.1 User Interface (Chat View)

**Component:** `ChatView.java` (Eclipse E4 View Part)  
**Presenter:** `ChatViewPresenter.java` (handles logic)  
**Technology:** SWT with embedded Browser component for rich HTML/CSS rendering

**Key Features:**
- Markdown rendering with syntax highlighting
- Streaming responses (partial updates as AI generates text)
- Message history display
- Code diff viewer integration
- Copy to clipboard functionality

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

**Goal:** Reset conversation when user switches Servoy projects

**Implementation:**

**ChatViewPresenter.java** (Direct Listener Registration)
- Registers `IActiveProjectListener` proxy in `@PostConstruct init()` using reflection (avoids compile-time dependency)
- Listens directly to `ServoyModel.activeProjectChanged` events
- When solution changes, calls `onSolutionActivated(projectName)`:
  - Clears LangChain4j memory: `servoyAiModel.clearMemory(currentMemoryId)`
  - Updates `currentMemoryId = projectName`
  - Clears UI conversation history
  - Shows green notification: "New session started - Solution: {name}"
- Unregisters listener in `@PreDestroy dispose()` when chat view closes

**Event Flow:**
```
User opens ChatView
  ?
ChatViewPresenter @PostConstruct init()
  ?
Registers IActiveProjectListener with ServoyModel (via reflection)
  ?
User switches solution
  ?
ServoyModel.activeProjectChanged(ServoyProject)
  ?
ChatViewPresenter's listener proxy receives notification
  ?
ChatViewPresenter.onSolutionActivated(projectName)
  ?
1. Clear LangChain4j memory for old solution
2. Update currentMemoryId to new solution
3. Clear UI conversation history
4. Show notification message
  ?
User closes ChatView
  ?
ChatViewPresenter @PreDestroy dispose()
  ?
Unregisters listener from ServoyModel
```

**Design Benefits:**
- Listener only active when chat view is open (resource efficient)
- Direct communication (no event bus overhead)
- Simple lifecycle management (register on create, unregister on destroy)
- No event topic format issues

### 3.5 Tooling (Function Calling)

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

### 3.6 Code Editing & Diffing

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

### 4.2 Solution Switching

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
   d. Shows "New session started" notification
7. Next message uses new memoryId (fresh conversation)
```

### 4.3 Configuration

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

The core system prompt (`core-system-prompt.txt`) is **intentionally minimal** (~2.4K tokens).

**Contents:**
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

**Trade-off Accepted:**
- 2.4K tokens sent with every request (vs 17K in original GitHub Copilot instructions)
- Necessary because OpenAI/Gemini APIs are stateless (no MCP session state)
- 86% reduction from original prompt size

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

1. Edit `/src/main/resources/prompts/core-system-prompt.txt`
2. Rebuild plugin (build.properties ensures file is included)
3. **Warning:** Keep prompt concise (< 3K tokens recommended)
4. Use getKnowledge tool for detailed rules (don't embed everything in prompt)

---

## 9. Design Patterns Used

1. **Presenter Pattern**: ChatView (view) + ChatViewPresenter (logic separation)
2. **Facade Pattern**: ServoyAiModel (hides LangChain4j complexity)
3. **Strategy Pattern**: AI provider selection (OpenAI, Gemini)
4. **Proxy Pattern**: IActiveProjectListener (dynamic proxy via reflection)
5. **Dependency Injection**: E4 DI (@Inject, @PostConstruct, @PreDestroy)
6. **Job Pattern**: Background processing (Eclipse Jobs for AI calls)

---

## 10. Threading Model

**UI Thread (SWT):**
- ChatView rendering
- User input handling
- Message display updates

**Background Thread (Eclipse Job):**
- AI model calls (OpenAI/Gemini API requests)
- Tool execution
- Streaming response handling

**Synchronization:**
- `UISynchronize.asyncExec()` - Update UI from background thread
- `Display.getDefault().asyncExec()` - Schedule UI work from non-UI thread
- LangChain4j handles streaming callbacks on background threads

---

## 11. Security Considerations

1. **API Keys**: Stored in Eclipse secure storage (IPreferenceStore)
2. **File Access**: Tools restricted to workspace (no arbitrary file system access)
3. **Code Execution**: No eval() or dynamic code execution
4. **Input Validation**: Tool parameters validated before execution
5. **Servoy Metadata**: Tools use Servoy APIs (don't corrupt .frm files)

---

## 12. Performance Considerations

1. **Token Costs**: 2.4K system prompt overhead per request (monitor usage)
2. **Memory Usage**: InMemoryChatMemoryStore accumulates across solutions (consider cleanup)
3. **Streaming**: Responses stream incrementally (better UX for long responses)
4. **Lazy Loading**: AI model initialized on first use (not at plugin startup)
5. **Background Jobs**: AI calls don't block UI (Eclipse Jobs)

---

## 13. Troubleshooting

**Common Issues:**

1. **ClassNotFoundException**: Missing Import-Package in MANIFEST.MF
   - Add package to Import-Package section
   - Ensure langchain4j bundle exports the package

2. **Solution switching not working**: Listener not registered
   - Check console for "Solution activation listener registered SUCCESSFULLY"
   - Verify ServoyModel is available at startup

3. **System prompt not loading**: Resource not packaged
   - Verify build.properties includes `src/main/resources/`
   - Check JAR contains `/prompts/core-system-prompt.txt`

4. **API calls failing**: Invalid configuration
   - Check API key in preferences
   - Verify model name is correct for provider
   - Check internet connectivity

**Debug Console Output:** See IMPLEMENTATION-STATUS.md for testing checklist

---

**For detailed implementation history and current status, see IMPLEMENTATION-STATUS.md**
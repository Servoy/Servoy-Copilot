# ServoyPilot - Explain Feature Architecture

**Last Updated:** February 24, 2026  
**Author:** Cristian  
**Purpose:** Complete documentation of the Explain context menu feature implementation

**Status:** 
- ✅ Explain Assistant integrated with FileReadingTools
- ✅ Assistant switching mechanism implemented
- ✅ Auto-send message with streaming response
- ✅ Context hiding (sent to AI but not shown in UI)
- ✅ Chunked file reading for large files (>100 lines)
- ✅ UI synchronization with proper timing
- ✅ Tool execution messages hidden from chat history (internal only)
- ⚠️ **AI model behavior limitation:** Progressive streaming not always consistent

---

## Overview

The **Explain** feature allows users to right-click on code in the Eclipse editor and get intelligent explanations of:
- **Selected code snippets** (≤100 lines): Shows code directly in chat
- **Large selections** (>100 lines): Uses chunked reading with progress indicator
- **Whole files**: Uses chunked reading with progress indicator

**Key Innovation:**
- **Separate display vs AI text**: UI shows clean message, AI receives hidden context
- **Chunked file reading**: Large files read in 100-line chunks via FileReadingTools
- **Tool-based approach**: AI uses FileReadingTools to read files instead of receiving code directly
- **Programmatic assistant switching**: Automatically switches to Explain assistant when invoked
- **Clean chat history**: Tool execution messages hidden; only user and AI messages displayed

**Progress Feedback:**
- Initial message shows "Reading file content..." when processing large files
- AI may stream "Reading lines 1-100..." messages as it processes chunks (model-dependent)

---

## Architecture Components

### 1. Assistant Switching Infrastructure

**Files Created:**
- `ChatViewActivator.java` - Utility for opening/activating chat view via E4PartService

**Files Modified:**
- `ChatViewPresenter.java` - Added `switchToAssistant()` method
- `ChatView.java` - Added `setAssistantSelectorIndex()` and `getPresenter()` methods

#### Implementation

**ChatViewActivator.java:**
```java
public class ChatViewActivator {
    private static final String CHAT_VIEW_ID = "com.servoy.eclipse.servoypilot.chatview";
    
    // Open and activate the chat view
    public static boolean openAndActivateChatView() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        
        try {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                page.showView(CHAT_VIEW_ID);
                return true;
            }
        } catch (PartInitException e) {
            // Handle error
        }
        return false;
    }
    
    // Get ChatView instance using E4 PartService
    public static ChatView getChatView() {
        EModelService modelService = window.getService(EModelService.class);
        MPart mPart = modelService.find(CHAT_VIEW_ID, window.getService(MApplication.class));
        
        if (mPart != null) {
            EPartService partService = window.getService(EPartService.class);
            MPart activePart = partService.findPart(CHAT_VIEW_ID);
            if (activePart != null) {
                Object obj = activePart.getObject(); // Returns ChatView instance
                if (obj instanceof ChatView) {
                    return (ChatView) obj;
                }
            }
        }
        return null;
    }
}
```

**Key Learning:** E4PartWrapper doesn't have `getObject()` method. Must use `EPartService.findPart() → MPart.getObject()` to unwrap the view instance.

**ChatViewPresenter.java - switchToAssistant():**
```java
public boolean switchToAssistant(AssistantType assistantType) {
    if (availableAssistants == null || assistantType == null) {
        return false;
    }

    // Find the index of the requested assistant type
    for (int i = 0; i < availableAssistants.length; i++) {
        if (availableAssistants[i].getType() == assistantType) {
            final int index = i;

            // Check if already on the requested assistant
            if (currentAssistant != null && currentAssistant.getType() == assistantType) {
                // Already on this assistant, but ensure UI is synchronized
                applyToView(view -> view.setAssistantSelectorIndex(index));
                return true; // No need to trigger full assistant change
            }

            // Update the UI combo box
            applyToView(view -> view.setAssistantSelectorIndex(index));

            // Trigger the assistant change logic
            onAssistantChanged(index);

            return true;
        }
    }

    return false;
}
```

**Key Learning:** Must update combo box UI even when already on the correct assistant to prevent desynchronization.

**ChatView.java - setAssistantSelectorIndex():**
```java
public void setAssistantSelectorIndex(int index) {
    uiSync.asyncExec(() -> {
        if (assistantSelector != null && !assistantSelector.isDisposed()) {
            assistantSelector.select(index);
        }
    });
}

public ChatViewPresenter getPresenter() {
    return presenter;
}
```

---

### 2. Context Menu Handler with Auto-Send

**File Modified:**
- `ServoyAiContextMenuHandler.java` - `handleExplain()` method

#### Implementation Flow

```java
private void handleExplain(SelectionInfo selection) {
    // 1. Get code context
    CodeContextService service = CodeContextService.getInstance();
    CodeContext context = service.getCodeContext(selection);
    
    if (context.hasError() || context.isEmpty()) {
        return;
    }

    // 2. Build messages (display vs AI)
    String filePath = selection.getFilePath();
    String selectedText = selection.getSelectedText();
    int length = selection.getLength();
    
    StringBuilder displayMessage = new StringBuilder();
    StringBuilder fullMessage = new StringBuilder();
    
    if (length > 0 && selectedText != null && !selectedText.trim().isEmpty()) {
        // Count lines
        int lineCount = selectedText.split("\r\n|\r|\n").length;
        
        if (lineCount > 100) {
            // Large selection - use chunked reading
            displayMessage.append("Please analyze the selected code from `")
                .append(filePath).append("` (").append(lineCount).append(" lines)");
            
            fullMessage.append("<large_file_notice>\n");
            fullMessage.append("Please read and analyze the selected code from `")
                .append(filePath).append("` at offset ")
                .append(selection.getOffset()).append(" (")
                .append(lineCount).append(" lines, ")
                .append(length).append(" characters).\n");
            fullMessage.append("</large_file_notice>");
        } else {
            // Small selection - show code directly
            displayMessage.append("Please explain this code from `")
                .append(filePath).append("`:\n\n");
            displayMessage.append("```javascript\n");
            displayMessage.append(selectedText);
            displayMessage.append("\n```");
            
            fullMessage.append(displayMessage.toString());
        }
    } else {
        // Whole file - use chunked reading
        displayMessage.append("Please analyze the file `").append(filePath).append("`");
        
        fullMessage.append("<large_file_notice>\n");
        fullMessage.append("Please read and analyze the file `")
            .append(filePath).append("`.\n");
        fullMessage.append("</large_file_notice>");
    }
    
    // 3. Add context hints (hidden from UI)
    String contextInfo = context.getFormattedPlainText();
    if (contextInfo != null && !contextInfo.trim().isEmpty() && 
        !contextInfo.contains("No context information")) {
        fullMessage.append("\n\n**Context hints:**\n```\n");
        fullMessage.append(contextInfo);
        fullMessage.append("\n```");
    }
    
    String displayText = displayMessage.toString();
    String fullText = fullMessage.toString();
    
    // 4. Open chat view
    if (!ChatViewActivator.openAndActivateChatView()) {
        return;
    }

    ChatView chatView = ChatViewActivator.getChatView();
    if (chatView == null) {
        return;
    }
    
    // 5. Switch assistant and send message
    Display.getDefault().asyncExec(() -> {
        chatView.getPresenter().populateAssistantSelector();
        chatView.getPresenter().switchToAssistant(AssistantType.EXPLAIN);
        
        // Delay to ensure view initialization
        Display.getCurrent().timerExec(150, () -> {
            chatView.getPresenter().onSendUserMessageWithContext(displayText, fullText);
        });
    });
}
```

**Key Learnings:**
1. **150ms delay required** - First-time view open needs time to initialize before sending message
2. **Display.asyncExec() required** - UI operations must run on SWT UI thread
3. **Two-message approach** - Display text (clean) vs full text (with hidden context)

---

### 3. Separate Display/AI Messages

**File Modified:**
- `ChatViewPresenter.java` - Added `onSendUserMessageWithContext()` method

#### Implementation

```java
public void onSendUserMessage(String text) {
    onSendUserMessageWithContext(text, text);
}

/**
 * Send a message where the displayed text differs from what's sent to the AI.
 * Useful for hiding verbose context from the UI while providing it to the assistant.
 * 
 * @param displayText Text shown in the chat UI
 * @param fullTextForAI Complete text (including hidden context) sent to the AI
 */
public void onSendUserMessageWithContext(String displayText, String fullTextForAI) {
    // Generate temporary IDs for streaming display
    String userMsgId = UUID.randomUUID().toString();
    String assistantMsgId = UUID.randomUUID().toString();

    // Detect if AI will need to read files (for large file analysis)
    boolean willReadFiles = fullTextForAI != null && fullTextForAI.contains("<large_file_notice>");

    // Show user message immediately with displayText only
    applyToView(part -> {
        part.clearUserInput();
        part.addMessage(userMsgId, "user");
        part.setMessageHtml(userMsgId, displayText);
        part.addMessage(assistantMsgId, "assistant");
        // Show different initial message if file reading is expected
        part.setMessageHtml(assistantMsgId, willReadFiles ? "Reading file content..." : "...");
    });

    // Accumulate streaming tokens
    StringBuilder accumulatedResponse = new StringBuilder();

    // LangChain4j automatically adds user message to store before calling LLM
    // Send fullTextForAI (with context) to the assistant
    currentAssistant.executeRequest(currentMemoryId, fullTextForAI)
        .onPartialResponse(partial -> {
            accumulatedResponse.append(partial);
            applyToView(part -> {
                part.setMessageHtml(assistantMsgId, accumulatedResponse.toString());
            });
        })
        .onCompleteResponse(fullResponse -> {
            // LangChain4j automatically added AI response to store
        })
        .onError(error -> {
            applyToView(part -> {
                part.setMessageHtml(assistantMsgId, "Error: " + error.getMessage());
            });
            logger.error("Error getting assistant response", error);
        })
        .start();
}
```

**Benefits:**
- User sees clean, readable message in UI
- AI receives full context with hints for better analysis
- Context hints (identifiers, types, scope) hidden from cluttering the chat
- "Reading file content..." message displayed during large file processing for immediate feedback

---

### 4. FileReadingTools Integration

**File Modified:**
- `ServoyAiModel.java` - Added FileReadingTools to ExplainAssistant

#### Implementation

```java
private ExplainAssistant createExplainServices(StreamingChatModel model) {
    String systemPrompt = SystemPrompts.INSTANCE.getExplainPrompt();

    return AiServices.builder(ExplainAssistant.class)
        .streamingChatModel(model)
        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
            .id(memoryId)
            .alwaysKeepSystemMessageFirst(true)
            .maxMessages(MAX_MESSAGES)
            .chatMemoryStore(sharedMemoryStore)
            .build())
        .systemMessageProvider(memoryId -> systemPrompt)
        .tools(new FileReadingTools()) // Enable file reading for chunked reading
        .build();
}
```

**FileReadingTools capabilities:**
- `readFile(filePath)` - Reads complete file (100KB limit)
- `readFileLines(filePath, startLine, endLine)` - Reads specific line range (max 500 lines)
- `getFileInfo(filePath)` - Gets metadata without reading content

**Note:** Tool execution results (ToolExecutionResultMessage) are stored in chat memory for the AI to use but are NOT displayed in the chat UI. Only user messages and AI responses are shown to maintain a clean conversation view.

---

### 5. Chunked Reading System Prompt

**File Modified:**
- `explain.txt` - System prompt for Explain assistant

#### Key Instructions

```markdown
### For Large Files (seen as `<large_file_notice>`):

When you see a `<large_file_notice>` tag, this is MANDATORY STREAMING BEHAVIOR:

**MANDATORY STREAMING WORKFLOW (DO NOT BATCH):**

You MUST process the file chunk by chunk in this EXACT sequence:

1. Output "Reading lines 1-100...\n\n" (with TWO newlines)
2. Call `readFileLines(filePath, 1, 100)` and wait for result
3. Output "Reading lines 101-200...\n\n" (with TWO newlines)
4. Call `readFileLines(filePath, 101, 200)` and wait for result
5. Continue this pattern until file is fully read
6. ONLY THEN output your analysis

**CRITICAL: This is STREAMING, not BATCHING**
- Output ONE "Reading lines..." message at a time
- Then call the tool for THAT chunk
- Then output the NEXT "Reading lines..." message
- DO NOT output all "Reading lines..." messages at once
- DO NOT batch multiple readFileLines calls together
```

**Challenge:** AI models sometimes batch all progress messages instead of streaming them progressively. This is a model behavior limitation, not a code issue.

**Important Note:** The "Reading lines 1-100..." messages are AI-generated text output (as instructed by the system prompt), NOT tool execution messages displayed by the application. Tool execution messages (ToolExecutionResultMessage) are internal only and are not shown in the chat history. Only user messages and AI responses are displayed.

---

## Message Flow Diagram

```
User Right-Clicks Code
        |
        v
ServoyAiContextMenuHandler.handleExplain()
        |
        +-- Get code context (identifiers, types)
        |
        +-- Check selection size
        |       |
        |       +-- Small (≤100 lines)
        |       |       |
        |       |       +-- Display: Show code in chat
        |       |       +-- AI: Same as display
        |       |
        |       +-- Large (>100 lines) or Whole File
        |               |
        |               +-- Display: "Please analyze file X"
        |               +-- AI: "<large_file_notice>Please read..."
        |
        +-- Add hidden context hints to AI message
        |
        +-- ChatViewActivator.openAndActivateChatView()
        |
        +-- ChatViewActivator.getChatView()
        |
        v
Display.asyncExec(() -> {
    chatView.getPresenter().switchToAssistant(EXPLAIN)
    |
    +-- Find EXPLAIN assistant in array
    |
    +-- Update combo box UI
    |
    +-- onAssistantChanged(index)
    |       |
    |       +-- Switch currentAssistant
    |       +-- Update memory ID
    |       +-- Clear UI
    |       +-- Refresh view from memory
    |
    v
    Display.timerExec(150ms, () -> {
        chatView.getPresenter().onSendUserMessageWithContext(display, full)
        |
        +-- Show display text in UI
        |
        +-- Send full text to AI (with context hints)
        |
        v
        AI sees <large_file_notice> tag
        |
        +-- Triggers chunked reading workflow
        |
        +-- Stream "Reading lines 1-100..."
        |
        +-- Call readFileLines(filePath, 1, 100)
        |
        +-- Stream "Reading lines 101-200..."
        |
        +-- Call readFileLines(filePath, 101, 200)
        |
        +-- Continue until file complete
        |
        v
        Stream final explanation
    })
})
```

---

## Key Technical Challenges & Solutions

### Challenge 1: E4PartWrapper Access

**Problem:** Initial attempt used `E4PartWrapper.getObject()` which doesn't exist.

**Solution:** Use proper E4 API:
```java
EPartService partService = window.getService(EPartService.class);
MPart mPart = partService.findPart(CHAT_VIEW_ID);
Object obj = mPart.getObject(); // Returns ChatView instance
```

---

### Challenge 2: First-Time View Open Timing

**Problem:** When chat view not yet open, sending message immediately resulted in nothing showing.

**Solution:** Add 150ms delay after opening view to allow initialization:
```java
Display.getDefault().asyncExec(() -> {
    chatView.getPresenter().switchToAssistant(AssistantType.EXPLAIN);
    Display.getCurrent().timerExec(150, () -> {
        chatView.getPresenter().onSendUserMessage(message);
    });
});
```

---

### Challenge 3: Context Hints Cluttering UI

**Problem:** Code context (identifiers, types, scope) useful for AI but cluttered chat UI.

**Solution:** Separate display message from AI message:
- Display: Clean "Please explain this code from X"
- AI: Same + hidden context hints section

---

### Challenge 4: Token Limit with Large Files

**Problem:** GPT-3.5-turbo (8K context) exceeded even with chunked reading due to tool results accumulating in conversation.

**Solution:** Switch to larger context model:
- GPT-4-turbo (128K tokens)
- Gemini 1.5 Pro/Flash (1M+ tokens)

---

### Challenge 5: AI Batching Progress Messages

**Problem:** AI sometimes outputs all "Reading lines..." messages at once instead of progressively.

**Solution:** Updated system prompt to emphasize STREAMING behavior:
- "This is STREAMING, not BATCHING"
- Explicit step-by-step workflow
- Clear WRONG vs CORRECT examples

**Limitation:** This is AI model behavior - sometimes it composes entire response before streaming. Cannot be fully controlled from application code.

---

### Challenge 6: Combo Box Desynchronization

**Problem:** After manual combo box change or async operations, `currentAssistant` and combo box selection could mismatch.

**Solution:** Always update combo box UI even when already on correct assistant:
```java
if (currentAssistant != null && currentAssistant.getType() == assistantType) {
    // Already on this assistant, but ensure UI is synchronized
    applyToView(view -> view.setAssistantSelectorIndex(index));
    return true;
}
```

---

### Challenge 7: Tool Execution Messages in History

**Problem:** Initially displayed tool execution messages (ToolExecutionResultMessage) in chat history, but:
- They showed as empty messages due to JSON parsing issues
- They cluttered the conversation history
- They're internal implementation details not useful to users
- The "Reading file content..." initial message was sufficient

**Solution:** Remove tool execution messages from chat history display entirely:
```java
// Skip Tool execution messages - they're internal details not needed in history
if (message instanceof ToolExecutionResultMessage) {
    continue;
}
```

**Result:** 
- Chat history only shows user messages and AI responses
- Clean, uncluttered conversation view
- Initial "Reading file content..." message provides sufficient feedback
- Tool executions remain in memory store but aren't displayed

**Key Learning:** The "Reading lines 1-100..." messages should come from the AI's streamed response (as instructed in the system prompt), NOT from displaying tool execution messages. Tool execution happens before the AI starts streaming, so there's no callback to show real-time progress anyway.

---

## Testing Scenarios

### Scenario 1: Small Code Selection (≤100 lines)
**Input:** Select 50 lines of code  
**Expected:** Code shown directly in chat with markdown formatting  
**Actual:** ✅ Works correctly

### Scenario 2: Large Code Selection (>100 lines)
**Input:** Select 150 lines of code  
**Expected:** "Reading lines..." progress messages, then explanation  
**Actual:** ✅ Works (when AI cooperates with streaming)

### Scenario 3: Whole File
**Input:** Explain file with 871 lines  
**Expected:** Progressive "Reading lines 1-100...", "Reading lines 101-200...", etc.  
**Actual:** ⚠️ Sometimes progressive, sometimes batched (AI model behavior)

### Scenario 4: First-Time Chat View Open
**Input:** Invoke Explain when chat view not yet open  
**Expected:** View opens, switches to Explain, shows message  
**Actual:** ✅ Works with 150ms delay

### Scenario 5: Already on Explain Assistant
**Input:** Invoke Explain twice in a row  
**Expected:** Combo box stays on Explain, sends new message  
**Actual:** ✅ Works correctly

---

## Configuration Requirements

### Model Requirements for Large Files

**Minimum Context:** 32K tokens  
**Recommended Models:**
- OpenAI: `gpt-4-turbo`, `gpt-4-turbo-preview`, `gpt-4-turbo-2024-04-09`
- Google: `gemini-1.5-pro`, `gemini-1.5-flash`

**Not Recommended:**
- `gpt-3.5-turbo` (8K context - too small for chunked reading)
- `gpt-4` (8K context - old model)

### System Prompt Location

**Default (Bundle):**
```
com.servoy.eclipse.servoypilot.knowledgebase/
  resources/system-prompts/explain.txt
```

**Override (Project-Specific):**
```
<workspace>/.servoy/system-prompts/explain.txt
```

---

## Future Improvements

### 1. Enhanced Loading Indicator
Current: Shows "Reading file content..." for large files  
Future: Implement more sophisticated UI indicator with animation:
```java
applyToView(part -> part.showLoadingIndicator("Reading file...", animated: true));
// Then hide after AI starts responding
```

### 2. Better Streaming Control
Explore LangChain4j streaming callbacks to force flush after each tool call:
```java
.onToolExecutionFinish(result -> {
    flushStreamingBuffer(); // Force output before next tool call
})
```

### 3. Reduce Memory Window
Lower MAX_MESSAGES to conserve tokens:
```java
private static final int MAX_MESSAGES = 20; // Down from 40
```

### 4. Smart Chunking
Adjust chunk size based on model context:
- Small context (32K): 50 lines per chunk
- Large context (128K+): 100 lines per chunk

---

## Benefits Achieved

✅ **Seamless UX** - Right-click → automatic assistant switch → message sent → response streams  
✅ **Context-aware** - AI receives code context for better explanations  
✅ **Clean UI** - Context hints hidden from cluttering chat  
✅ **Clean chat history** - Tool execution messages hidden; only user and AI messages displayed  
✅ **Scalable** - Handles files of any size with chunked reading  
✅ **Initial feedback** - "Reading file content..." message shown immediately for large files  
✅ **Progressive reading** - AI may stream "Reading lines..." messages as it processes (model-dependent)  
✅ **Flexible** - Works with multiple AI providers (OpenAI, Gemini)  

---

## Known Limitations

⚠️ **AI Model Streaming Behavior** - Cannot fully control when AI outputs progress messages. Sometimes batches them all at once instead of streaming progressively. This is inherent to how LLMs compose responses.

⚠️ **Token Consumption** - Each tool result stays in conversation history. Large files with many chunks can still exceed context with small models.

⚠️ **150ms Delay** - Hardcoded timing for view initialization. Could be unreliable on slow systems.

---

## Conclusion

The Explain feature successfully provides intelligent code explanation functionality within Eclipse. The implementation leverages:
- Programmatic assistant switching
- Tool-based file reading with chunking
- Separate display/AI messages for clean UX
- Clean chat history (tool messages hidden)
- "Reading file content..." initial feedback for large files
- Progressive streaming (when AI model cooperates)

**Key Learning:** The "Reading lines 1-100..." progress messages should come from the AI's streamed response (as instructed in the system prompt), NOT from displaying tool execution messages. Tool execution happens synchronously before AI response streaming begins, so there's no mechanism to show real-time tool progress anyway. The initial "Reading file content..." message provides sufficient user feedback.

The main challenge is managing AI model behavior for consistent streaming, which is an inherent limitation of current LLM streaming implementations.


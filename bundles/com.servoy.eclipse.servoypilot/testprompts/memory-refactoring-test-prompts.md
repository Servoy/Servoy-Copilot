# Memory Store Refactoring Test Prompts
**Purpose:** Test single source of truth memory architecture with assistant switching
**Date:** February 19, 2026
**Feature:** Memory store as single source, assistant switching, message deletion

---

## TEST 1: Basic Message Flow (VibeCoding)
**Prompt:**
```
What is a Servoy form?
```

**Expected:**
- User message appears immediately
- AI response streams in real-time
- After complete: UI refreshes from store
- Both messages have IDs starting with "msg-"

**Verify:**
- Check message IDs in UI (should be msg-0, msg-1)
- Messages should persist after refresh

---

## TEST 2: Multiple Messages (VibeCoding)
**Prompts (send sequentially):**
```
1. What is a relation in Servoy?
2. How do I create a form?
3. What are valueLists?
```

**Expected:**
- 6 messages total (3 user + 3 AI)
- Message IDs: msg-0 through msg-5
- All messages persist in UI
- Streaming works for all responses

**Verify:**
- Scroll through conversation
- All messages visible
- IDs sequential

---

## TEST 3: Switch to Documentation Assistant (Empty Memory)
**Steps:**
1. After TEST 2, switch assistant selector to "Documentation"
2. Observe UI

**Expected:**
- UI clears completely
- No messages shown (documentation memory is empty)
- Input still enabled
- No errors in console

**Verify:**
- Chat view is empty
- Ready for new conversation

---

## TEST 4: Documentation Assistant Conversation
**Prompt (while in Documentation assistant):**
```
function calculateTotal(price, tax) {
    return price + (price * tax);
}
```

**Expected:**
- User message appears (code snippet)
- AI generates JSDoc comment
- Both messages persist
- Message IDs: msg-0, msg-1

**Verify:**
- Documentation-specific response (JSDoc format)
- Different conversation from VibeCoding

---

## TEST 5: Switch Back to VibeCoding (Memory Persists)
**Steps:**
1. Switch assistant selector back to "VibeCoding"
2. Observe UI

**Expected:**
- Original 6 messages reappear (from TEST 2)
- Message IDs: msg-0 through msg-5
- Messages displayed in correct order
- No Documentation messages shown

**Verify:**
- VibeCoding conversation restored
- Documentation conversation hidden

---

## TEST 6: Switch Back to Documentation (Memory Persists)
**Steps:**
1. Switch assistant selector to "Documentation"
2. Observe UI

**Expected:**
- 2 messages reappear (from TEST 4)
- Code snippet + JSDoc response
- Message IDs: msg-0, msg-1
- VibeCoding messages hidden

**Verify:**
- Documentation conversation restored
- Each assistant has independent memory

---

## TEST 7: Send Message After Assistant Switch
**Prompt (while in Documentation assistant):**
```
function validateEmail(email) {
    return email.includes('@');
}
```

**Expected:**
- New message added to Documentation conversation
- 4 messages total now (2 from TEST 4 + 2 new)
- Message IDs: msg-0 through msg-3
- VibeCoding memory unchanged

**Verify:**
- Switch back to VibeCoding - should still have 6 messages
- Switch to Documentation - should have 4 messages

---

## TEST 8: Message Deletion (Documentation Assistant)
**Steps:**
1. In Documentation assistant (4 messages)
2. Hover over msg-1 (first AI response)
3. Click delete icon

**Expected:**
- Message msg-1 deleted from UI
- 3 messages remain
- Message IDs regenerate: msg-0, msg-1, msg-2
- UI refreshes smoothly

**Verify:**
- Deleted message gone
- Remaining messages intact
- IDs sequential starting from 0

---

## TEST 9: Verify Deletion Persisted in Store
**Steps:**
1. Switch to VibeCoding
2. Switch back to Documentation

**Expected:**
- Still 3 messages (deletion persisted)
- Message IDs: msg-0, msg-1, msg-2
- Deleted message does NOT reappear

**Verify:**
- Store updated correctly
- Deletion permanent

---

## TEST 10: Delete Multiple Messages
**Steps:**
1. In Documentation (3 messages)
2. Delete msg-0 (user message)
3. Delete msg-0 again (next message, IDs shifted)

**Expected:**
- After first delete: 2 messages remain (msg-0, msg-1)
- After second delete: 1 message remains (msg-0)
- UI refreshes after each deletion

**Verify:**
- Only 1 message left
- Store correctly updated

---

## TEST 11: Solution Switch (Clear All Memories)
**Steps:**
1. Note current messages in both assistants
2. Switch to different Servoy solution
3. Observe UI

**Expected:**
- UI clears completely
- System notification: "New session started"
- Both VibeCoding and Documentation memories cleared
- Ready for fresh conversation

**Verify:**
- No old messages visible
- Can start new conversation

---

## TEST 12: New Conversation After Solution Switch
**Prompts:**
```
VibeCoding: What is a dataSource?
Documentation: function test() { return true; }
```

**Expected:**
- Each assistant starts fresh conversation
- VibeCoding: msg-0, msg-1
- Documentation: msg-0, msg-1
- No old messages from previous solution

**Verify:**
- Independent memories per solution
- Clean slate after switch

---

## TEST 13: Clear Button (VibeCoding)
**Steps:**
1. Send 3 messages in VibeCoding
2. Click Clear button
3. Observe UI

**Expected:**
- UI clears immediately
- Memory store cleared for VibeCoding only
- Documentation memory untouched
- Input still enabled

**Verify:**
- VibeCoding memory empty
- Switch to Documentation - messages still there

---

## TEST 14: System/Tool Messages Hidden
**Prompt (VibeCoding):**
```
List all forms in the project
```

**Expected:**
- User message visible (msg-0)
- AI response visible (msg-1)
- Tool execution result NOT visible
- Only 2 messages shown

**Verify:**
- No tool execution messages in UI
- Clean conversation display

---

## TEST 15: Streaming Then Refresh
**Prompt (VibeCoding):**
```
Explain Servoy foundsets in detail
```

**Expected:**
- User message appears (msg-0)
- AI response streams token by token
- After complete: UI refreshes from store
- Final message shows complete response (msg-1)

**Verify:**
- Streaming UX smooth
- No flickering during refresh
- Complete response displayed

---

## TEST 16: 40 Message Limit Test
**Steps:**
1. Send 45 messages in VibeCoding (use simple prompts)
2. Observe message count

**Expected:**
- UI shows messages currently in store
- Oldest messages auto-evicted by LangChain4j
- Max 40 messages displayed
- No errors or crashes

**Verify:**
- Message count doesn't exceed 40
- Eviction happens automatically

---

## TEST 17: Error Handling (Invalid Message ID)
**Steps:**
1. Use browser dev tools to trigger delete with invalid ID
2. Or manually corrupt message ID

**Expected:**
- No crash
- Error logged to console
- UI remains functional

**Verify:**
- Graceful error handling
- No user-visible errors

---

## TEST 18: Rapid Assistant Switching
**Steps:**
1. Switch: VibeCoding → Documentation → VibeCoding → Documentation (fast)
2. Observe UI stability

**Expected:**
- UI refreshes correctly each time
- No race conditions
- Correct messages shown for each assistant
- No duplicates or missing messages

**Verify:**
- Stable under rapid switching
- Memory isolation maintained

---

## TEST 19: Send Message While Streaming
**Steps:**
1. Send long prompt in VibeCoding
2. While streaming, send another message

**Expected:**
- First message completes streaming
- Second message queued or rejected
- No UI corruption
- Both messages eventually processed

**Verify:**
- Handle concurrent requests gracefully

---

## TEST 20: Full Workflow Integration Test
**Steps:**
1. VibeCoding: Send 3 messages
2. Documentation: Send 2 messages
3. VibeCoding: Delete 1 message
4. Documentation: Delete 1 message
5. Switch solution
6. VibeCoding: Send 1 message
7. Documentation: Send 1 message
8. Clear VibeCoding
9. Switch assistants multiple times
10. Verify final state

**Expected:**
- All operations work correctly
- No memory leaks
- No UI corruption
- Clean state management

**Verify:**
- End-to-end workflow functional
- Memory store reliable
- UI always in sync with store

---

## VALIDATION CRITERIA

After running all tests:

✅ **Architecture:**
- Single source of truth (sharedMemoryStore) ✓
- No dual storage issues ✓
- UI always reflects store state ✓

✅ **Memory Isolation:**
- Each assistant has independent memory ✓
- Switching shows correct conversation ✓
- Deletions don't affect other assistants ✓

✅ **Message Management:**
- IDs generated correctly (msg-0, msg-1, etc.) ✓
- Deletion removes from store + UI ✓
- Refresh regenerates IDs ✓

✅ **User Experience:**
- Streaming works smoothly ✓
- No flickering ✓
- Fast assistant switching ✓
- Clear feedback on operations ✓

✅ **Robustness:**
- No crashes ✓
- Graceful error handling ✓
- Handles edge cases ✓

---

**Test Duration:** ~30-45 minutes  
**Prerequisites:** Active Servoy solution, AI model configured  
**Test Environment:** Servoy Developer with ServoyPilot plugin

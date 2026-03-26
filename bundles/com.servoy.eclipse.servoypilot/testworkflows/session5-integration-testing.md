# SESSION 5: Integration Testing — Full End-to-End Documentation Workflows

**Date:** March 26, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** Documentation Assistant (all tools combined)

---

## Overview

This document tests the **complete Documentation Assistant** end-to-end — from right-click context menu trigger through to the final diff view in Eclipse. Tests cover realistic user scenarios rather than individual tool calls.

**What is tested:**
- Right-click → "Generate Docs" workflow on single functions
- Full-file documentation on fresh selection
- Incomplete JSDoc completion (user asks to "improve" existing docs)
- UUID corruption resistance (simulate AI attempting to change a UUID)
- Content hash protection (file modified between read and write)
- Multi-turn conversation within a single file session
- Undo / Keep workflow in the "Modified files" panel
- Edge cases: empty return, overloaded semantics, cross-scope params

**Session context:** After Session 4, all 8 files should be 100% documented. Session 5 re-tests on those files, deliberately degrading some JSDoc to test improvement workflows.

---

## Test Suite Structure

| Test | Scenario | Trigger | Key Validation |
|------|----------|---------|----------------|
| 5.1 | Right-click → single function | Context menu | `getCurrentSelection()` path |
| 5.2 | Right-click → multiple functions selected | Context menu | Line-range selection |
| 5.3 | Chat: "document this file" (no selection) | Chat prompt | Full-file workflow |
| 5.4 | Improve incomplete JSDoc | Chat prompt | REPLACE + description upgrade |
| 5.5 | UUID corruption resistance | Simulated bad AI output | `restoreUUIDs()` safety layer |
| 5.6 | Hash protection — stale write rejected | File edited externally | `applyDocumentations()` rejection |
| 5.7 | Multi-turn: document → question → apply | Multi-turn chat | Memory + context continuity |
| 5.8 | Undo and re-apply | UI workflow | `FileModificationTracker` |
| 5.9 | Cross-file type chain | Chat prompt | `resolveIdentifierType()` + docs |
| 5.10 | Full solution documentation pass | Chat prompt | All 8 files, single session |

---

## Prerequisites

Before running Session 5 tests:
- [ ] Session 4 completed successfully (all 8 files documented)
- [ ] `svyPilotTest` solution open and active in Servoy Developer
- [ ] Documentation Assistant active in ServoyPilot chat
- [ ] Eclipse console visible (for debug output monitoring)

---

## TEST 5.1 — Right-Click Single Function

**Purpose:** Tests the canonical "Generate Docs" trigger path via context menu on a single function.

### Setup
In `globals.js`, manually delete the JSDoc block above `clearState()` — make it bare again.

### Action
1. Place cursor inside `clearState()` body (or select the function)
2. Right-click → "Generate Docs"

### Expected AI workflow
1. Chat view opens and switches to Documentation Assistant
2. AI auto-calls `getCurrentSelection()`
3. Receives selection with only `clearState` visible (or small context window)
4. Recognizes it is a function with no JSDoc
5. Calls `applyDocumentations("globals.js", <hash>, [1 INSERT item])`

**Expected result:**
```javascript
/**
 * Resets all global shared state variables to their initial values.
 * Should be called when navigating back to the main navigation form.
 *
 * @public
 */
function clearState() {
```

**Success criteria:**
- ✅ `getCurrentSelection()` called first (not `analyzeFileStructure`)
- ✅ Exactly 1 item in `applyDocumentations()` call
- ✅ Correct file path used (from `getCurrentSelection()` FILE header)
- ✅ File appears in "Modified files" panel
- ✅ Brief summary: "Applied JSDoc to clearState in globals.js"

---

## TEST 5.2 — Right-Click Multiple Functions Selected

**Purpose:** Tests multi-function selection range. User manually selects 3 consecutive functions before right-clicking.

### Setup
In `mainNav.js`, manually delete JSDoc from `onActionCustomers`, `onActionOrders`, `onActionDashboard`.

### Action
1. Select lines covering all three functions in the editor
2. Right-click → "Generate Docs"

### Expected AI workflow
1. `getCurrentSelection()` returns the 3 bare functions in the selection range
2. AI generates 3 JSDoc blocks
3. Single `applyDocumentations("mainNav.js", <hash>, [3 items])`

**Success criteria:**
- ✅ Only the 3 selected functions documented (not entire file)
- ✅ `onLoad`, `onShow`, `onHide` are NOT modified (outside selection)
- ✅ 3 INSERT items with correct line numbers matching the selection

---

## TEST 5.3 — Chat Prompt: "Document This File"

**Purpose:** Tests the full-file workflow triggered by a chat message (not context menu). No active selection.

### Setup
Open `utils.js` in editor (no selection needed).

### Prompt:
```
Please document all undocumented functions in the currently open file
```

**Expected AI workflow:**
1. Calls `getCurrentSelection()` — returns entire visible area or first function
2. Extracts file path from `FILE:` header
3. Calls `analyzeFileStructure(<filePath>)` to get full picture
4. Calls `getCodeChunk(<filePath>)` for code
5. Generates 6 INSERT items (all bare functions in utils)
6. Calls `applyDocumentations("utils.js", <hash>, [6 items])`

**Success criteria:**
- ✅ AI correctly extracts file path from `getCurrentSelection()` even without text selected
- ✅ Falls back to `analyzeFileStructure()` for full symbol list
- ✅ All 6 functions documented

---

## TEST 5.4 — Improve Incomplete JSDoc

**Purpose:** Tests REPLACE mode when user asks to "improve" existing but incomplete documentation.

### Setup
In `dataUtils.js`, the `getRecord` function has this JSDoc (from Session 4):
```javascript
/**
 * Gets a record from a foundset at the given index.
 *
 * @param {JSFoundSet} foundset - The foundset to read from
 * @param {Number} index - 1-based record index
 * @return {JSRecord} The record at the given index, or null if out of bounds
 */
```
This is complete. To test improvement, add a minimal incomplete JSDoc above `loadRecords` manually:
```javascript
/**
 * Loads records.
 */
function loadRecords(datasource, query) {
```

### Prompt:
```
The JSDoc for loadRecords in dataUtils looks incomplete. Please improve it.
```

**Expected AI workflow:**
1. `analyzeFileStructure("dataUtils")` → `loadRecords` shows `[✓]` (has some JSDoc)
2. `getCodeChunk("dataUtils", symbolName="loadRecords")` — targeted read
3. Sees minimal JSDoc `/** Loads records. */`
4. Builds REPLACE item: `startSentence="/**"`, `endSentence="*/"`, new full JSDoc
5. `applyDocumentations("dataUtils.js", <hash>, [1 REPLACE item])`

**Expected improved JSDoc:**
```javascript
/**
 * Loads records from the specified datasource into a new foundset.
 * Optionally filters by a QBSelect query. Updates lastRecordCount after load.
 *
 * @param {String} datasource - The datasource string (e.g. 'db:/example_data/customers')
 * @param {QBSelect} [query] - Optional query to filter records; loads all if null
 * @return {JSFoundSet} The loaded foundset, or null if datasource is invalid
 */
function loadRecords(datasource, query) {
```

**Success criteria:**
- ✅ REPLACE mode used (not INSERT creating duplicate)
- ✅ Original minimal comment completely replaced
- ✅ New JSDoc includes all params + return type
- ✅ `@param {QBSelect}` lookup triggered via `getDocumentationForIdentifiers()`

---

## TEST 5.5 — UUID Corruption Resistance

**Purpose:** Tests that `DocumentationValidator.restoreUUIDs()` silently fixes AI-corrupted UUIDs.

### Setup
This test simulates what happens if the AI accidentally modifies a UUID. In `customerList.js`, the `selectedCustomer` variable has:
```javascript
/**
 * The currently selected customer record.
 *
 * @type {JSRecord}
 *
 * @properties={typeid:35,uuid:"ACTUAL-UUID-VALUE-HERE"}
 */
var selectedCustomer = null;
```

### Simulation Method
The `DocumentationValidator.restoreUUIDs()` code path is exercised any time `applyDocumentations()` processes a REPLACE item on a block that contains a `@properties` UUID line. The test verifies the silent-restore mechanism works.

### Prompt:
```
The JSDoc for selectedCustomer in customerList seems to be missing a description. Please improve it.
```

**Expected AI workflow:**
1. `getCodeChunk("customerList", symbolName="selectedCustomer")` — targeted read
2. AI generates new JSDoc (may or may not include `@properties` line correctly)
3. `applyDocumentations()` calls `DocumentationValidator.validateUUIDs()` and `restoreUUIDs()` internally
4. File is written with correct UUID preserved

**Expected console output (from DocumentationValidator):**
```
[DocumentationValidator] UUID count original=1, new=1 → OK
```
Or if AI corrupted the UUID:
```
[DocumentationValidator] UUID mismatch detected — restoring original UUIDs
[DocumentationValidator] Restored UUID: ACTUAL-UUID-VALUE-HERE
```

**Success criteria:**
- ✅ Operation completes successfully regardless of whether AI included correct UUID
- ✅ `@properties` line in the file is identical to original after write
- ✅ Console shows UUID validation step ran

---

## TEST 5.6 — Hash Protection: Stale Write Rejected

**Purpose:** Tests that `applyDocumentations()` rejects writes when the file was modified between `getCurrentSelection()` and the write call.

### Setup
1. Open `utils.js` in editor
2. Trigger "Generate Docs" (right-click)
3. **While the AI is generating** (before it calls `applyDocumentations()`): manually edit `utils.js` in a different editor tab and save it

### Expected behavior
When `applyDocumentations()` is called with the old hash:
```
Error: File has been modified since documentation was prepared. Hash mismatch for /svyPilotTest/utils.js. Please try again.
```

**Success criteria:**
- ✅ `applyDocumentations()` returns error message (does NOT write)
- ✅ Error message is clear and instructional ("Please try again")
- ✅ AI surfaces the error to user in chat
- ✅ AI offers to retry (re-read the file with new hash)

---

## TEST 5.7 — Multi-Turn Conversation

**Purpose:** Tests that the Documentation Assistant maintains context across multiple messages in a single session.

### Prompt sequence:
**Turn 1:**
```
What functions in customerEdit are currently undocumented?
```

**Expected:** AI calls `analyzeFileStructure("customerEdit")` and lists bare functions only.

**Turn 2:**
```
Document just the save and validate functions for now
```

**Expected:** AI remembers the file path from Turn 1, calls `getCodeChunk("customerEdit", symbolName="save")` and reads `validate`, applies 2 INSERT items.

**Turn 3:**
```
Now document the rest
```

**Expected:** AI knows which functions remain (from Turn 1 context), calls `applyDocumentations()` for the remaining bare functions without re-surveying.

**Success criteria:**
- ✅ Turn 2 does NOT call `analyzeFileStructure()` again (context from memory)
- ✅ Turn 2 applies exactly 2 items
- ✅ Turn 3 applies the remaining bare functions only
- ✅ No duplicate JSDoc created (AI tracks what was already applied)
- ✅ 100-message memory limit sufficient for 3-turn interaction

---

## TEST 5.8 — Undo and Re-Apply

**Purpose:** Tests the "Modified files" panel Undo workflow and verifies re-documentation works after undo.

### Action sequence:
1. Run TEST 5.1 (document `clearState` in globals)
2. In "Modified files" panel, click `globals.js` → verify diff shows JSDoc addition
3. Click "Undo" for `globals.js`
4. Verify `clearState` is bare again
5. Re-run TEST 5.1

**Success criteria:**
- ✅ Diff view shows correct before/after when clicking file in panel
- ✅ Undo correctly restores original file content
- ✅ After undo, `analyzeFileStructure("globals")` shows `clearState` as undocumented `[ ]`
- ✅ Second run of TEST 5.1 succeeds (new content hash accepted)

---

## TEST 5.9 — Cross-Scope Type Chain Resolution

**Purpose:** Tests type resolution for identifiers that resolve through cross-scope calls.

### Prompt:
```
In customerList.js, what is the type of 'selectedCustomer' and what methods are available on it?
```

**Expected AI workflow:**
1. `resolveIdentifierType("selectedCustomer", "customerList")` → returns `JSRecord` (from `@type` annotation)
2. `getAvailableMembersForType("JSRecord", "get*")` → lists JSRecord getter methods
3. AI describes the type and key methods in response

**Success criteria:**
- ✅ `selectedCustomer` resolved as `JSRecord` (from JSDoc `@type`)
- ✅ `getAvailableMembersForType("JSRecord")` returns member list
- ✅ AI provides useful description of available methods

---

## TEST 5.10 — Full Solution Documentation Pass

**Purpose:** Comprehensive end-to-end test. All 8 files documented in a single chat session from scratch.

### Setup
Reset: Manually remove ALL JSDoc from all 8 files (leave `@properties` lines in place). Leave only variable declarations and function signatures bare.

### Prompt:
```
Please document all 8 files in the svyPilotTest solution completely.
Files to document: globals, utils, dataUtils, mainNav, customerList, customerEdit, orderList, dashboard
```

**Expected AI workflow (high level):**
1. For each file in order:
   a. `analyzeFileStructure(<file>)` — survey
   b. `getCodeChunk(<file>)` — read (multiple chunks if needed)
   c. `getDocumentationForIdentifiers([...])` — Servoy API lookup (selective)
   d. `applyDocumentations(<file>, <hash>, [items])` — write
2. Running summary after each file
3. Final summary: "Documented 8 files, X functions, Y variables total"

**File processing order (expected):**
1. `globals` — foundational scope, most cross-referenced
2. `utils` — pure utilities, no Servoy API
3. `dataUtils` — data layer, Servoy types
4. `mainNav` — simple form, event handlers
5. `customerList` — customer list form
6. `customerEdit` — complex form with validation
7. `orderList` — order list with complex filter
8. `dashboard` — summary form

**Success criteria:**
- ✅ All 8 `applyDocumentations()` calls succeed
- ✅ All files appear in "Modified files" panel
- ✅ Post-write `analyzeFileStructure()` on each file shows 100% coverage
- ✅ No UUID values changed in any file
- ✅ Total API doc lookups ≤ 15 `getDocumentationForIdentifiers()` calls (efficiency)
- ✅ Total chunks read ≤ 12 (all files are small — single chunk each)
- ✅ Final summary message is 3–5 sentences (per system prompt brevity rule)
- ✅ Session completes within 100 message memory limit

---

## Failure Mode Reference

Use this table when a test fails to identify the failing component:

| Failure Symptom | Likely Cause | Component to Check |
|-----------------|--------------|-------------------|
| JSDoc inserted at wrong line | Line number off-by-one | `DocumentationItem.startLine` |
| Duplicate `/**` blocks created | INSERT mode used where REPLACE needed | AI JSDoc detection logic |
| `@properties` UUID changed | UUID restore failed | `DocumentationValidator.restoreUUIDs()` |
| "Hash mismatch" on first write | File read hash wrong | `getCurrentSelection()` CONTENT_HASH |
| "Symbol not found" in targeted read | DLTK cache stale | `FileStructureService` + DLTK index |
| Type documented as `Object` | Type resolution failed | `resolveIdentifierType()` + TypeCreator fallback |
| Missing `@param` on multi-param function | AI truncated param list | System prompt / AI generation |
| `applyDocumentations()` silent fail | Item validation failed | `DocumentationItem` canonical constructor |
| File not in "Modified files" | Tracker not notified | `FileModificationTracker.notifyFileModified()` |
| Memory lost between turns | Memory limit hit | Chat memory 100-message window |

---

## Regression Test Checklist

After any change to `DocumentationTools`, `CodeAnalysisTools`, or `DocumentationValidator`, run this quick regression set:

- [ ] **5.1** — Single function, right-click path still works
- [ ] **5.4** — REPLACE mode still generates improved JSDoc (not duplicate)
- [ ] **5.5** — UUID restore still fires when AI corrupts UUID
- [ ] **5.6** — Hash mismatch still rejected (not silently ignored)
- [ ] **5.10** — Full pass completes without tool errors

These 5 tests cover all major code paths in the Documentation Assistant pipeline.

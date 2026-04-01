# SESSION 5: Integration Testing — Full End-to-End Documentation Workflows

**Date:** April 1, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** Documentation Assistant (all tools combined)

---

## Overview

Tests the **complete Documentation Assistant** end-to-end — from right-click context menu trigger through to the final diff view in Eclipse. Tests cover realistic user scenarios rather than individual tool calls.

**What is tested:**
- Right-click → "Improve docs for file" / "Improve docs for selection" workflow
- Full-file improvement on files with mixed JSDoc quality
- Selective improvement on a code selection
- UUID corruption resistance
- Timestamp protection (file modified between read and write)
- Multi-turn conversation within a single file session
- Undo / Keep workflow in the "Modified files" panel
- Cross-scope type chain documentation
- Full solution documentation pass

**Prerequisites:**
- [ ] `svyPilotTest` solution open and active in Servoy Developer
- [ ] All 8 JS files in `js-content/` reference state (TODO stubs, some complete)
- [ ] Documentation Assistant active in ServoyPilot chat
- [ ] Eclipse console visible

**Reset between tests:** Restore any modified file to its `js-content/` reference state using Git (`git checkout -- <file>`) or manually reverting from the "Modified files" panel.

---

## Test Suite Structure

| Test | Scenario | Trigger | Key Validation |
|------|----------|---------|----------------|
| 5.1 | Right-click no selection — full file | Context menu | `getCurrentSelection()` isFullFileSelected=true |
| 5.2 | Right-click with selection — selected functions only | Context menu | Line-range selection, partial apply |
| 5.3 | Chat: "improve docs for this file" | Chat prompt | Full-file workflow via chat |
| 5.4 | Improve minimal-stub variables | Chat prompt | REPLACE on @type-only stubs |
| 5.5 | UUID corruption resistance | Simulated bad AI output | `restoreUUIDs()` safety layer |
| 5.6 | Timestamp protection — stale write rejected | File edited externally | `applyDocumentations()` rejection |
| 5.7 | Multi-turn: survey → question → apply | Multi-turn chat | Memory + context continuity |
| 5.8 | Undo and re-apply | UI workflow | `FileModificationTracker` |
| 5.9 | Cross-file type chain | Chat prompt | `resolveIdentifierType()` + docs |
| 5.10 | Full solution documentation pass | Chat prompt | All 8 files, single session |

---

## TEST 5.1 — Right-Click, No Selection → Full File (mainNav.js)

**Purpose:** Tests the no-selection path from the context menu. When no text is selected, `ServoyAiContextMenuHandler` sends: `"Please improve the JSDoc documentation for the entire file."`

### Setup
- Open `mainNav.js` in the JavaScript editor
- Place cursor inside the file (do NOT select any text)

### Action
Right-click → **Generate Docs**

### Expected AI workflow
1. Chat view opens, switches to Documentation Assistant
2. Message received: `"Please improve the JSDoc documentation for the entire file."`
3. AI calls `getCurrentSelection()` → `isFullFileSelected=true`, FILE: mainNav.js
4. `analyzeFileStructure("mainNav")` → 7 functions
5. `getCodeChunk("mainNav", chunkNumber=1, chunkSize="LARGE")` → 63 lines, 1 chunk
6. All 7 functions have TODO stubs → 7 REPLACE items
7. `getDocumentationForIdentifiers(["JSEvent"])`
8. `applyDocumentations("/svyPilotTest/forms/mainNav.js", [7 REPLACE items])`

**Success criteria:**
- ✅ Message is `"Please improve the JSDoc documentation for the entire file."` (not "selection")
- ✅ `getCurrentSelection()` called first
- ✅ `isFullFileSelected=true`
- ✅ 7 REPLACE items applied
- ✅ File appears in "Modified files" panel

**Pass/Fail:** _______________

---

## TEST 5.2 — Right-Click With Selection → Selected Functions Only (customerEdit.js)

**Purpose:** Tests the selection path. User selects 2 functions before right-clicking. Message sent: `"Please improve the JSDoc documentation for the current selection."`

### Setup
- Open `customerEdit.js`
- Select lines covering `onActionSave` and `onActionCancel` (both have TODO stubs)

### Action
Right-click → **Generate Docs**

### Expected AI workflow
1. Message received: `"Please improve the JSDoc documentation for the current selection."`
2. `getCurrentSelection()` → `isFullFileSelected=false`, selected lines contain 2 functions
3. AI reads the selection — both have TODO stubs
4. `getDocumentationForIdentifiers(["JSEvent"])`
5. `applyDocumentations("/svyPilotTest/forms/customerEdit.js", [2 REPLACE items])`

**Expected JSDoc for `onActionSave` (REPLACE):**
```javascript
/**
 * Handles the Save button action. Saves the record and navigates to the customer list.
 *
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"2604EFBC-4654-4A08-A059-99F77781C31A"}
 */
function onActionSave(event) {
```

**Success criteria:**
- ✅ Message is `"Please improve the JSDoc documentation for the current selection."` (not "file")
- ✅ `isFullFileSelected=false`
- ✅ Exactly 2 REPLACE items — only `onActionSave` and `onActionCancel`
- ✅ `save()`, `validate()`, `onHide()` NOT in items (outside selection)
- ✅ UUIDs preserved

**Pass/Fail:** _______________

---

## TEST 5.3 — Chat: Improve Docs for File (globals.js)

**Purpose:** Direct chat prompt triggering the full-file improvement workflow.

### Prompt
```
Please improve the JSDoc documentation for globals
```

### Expected AI workflow
1. `analyzeFileStructure("globals")` → 17 symbols
2. `getCodeChunk("globals", chunkNumber=1, chunkSize="LARGE")` → `CHUNK: 1 of 2`
3. `getCodeChunk("globals", chunkNumber=2, chunkSize="LARGE")` → `CHUNK: 2 of 2, LAST CHUNK`
4. Classifies all symbols — identifies ~8 needing improvement
5. `getDocumentationForIdentifiers(["JSRecord", "JSFoundSet", "RuntimeForm"])`
6. `applyDocumentations("/svyPilotTest/scopes/globals.js", [8-9 REPLACE items])`

**Success criteria:**
- ✅ **Both chunks read** before applying (globals.js is 203 lines > 200 LARGE)
- ✅ Complete symbols (`showForm`, `showMessage`, `getCurrentUser`, etc.) NOT in items
- ✅ `setInitialized(value)` gets `@param {Boolean} value`
- ✅ `onSolutionOpen(arg, queryParams)` gets both params documented

**Pass/Fail:** _______________

---

## TEST 5.4 — Improve Minimal-Stub Variables (dashboard.js)

**Purpose:** Verifies the AI detects and replaces minimal JSDoc stubs (only `@type`, no description) on variables.

### Prompt
```
Improve the documentation for dashboard
```

### Setup
`dashboard.js` state:
- `totalCustomers` → complete (description + `@type`) → skip
- `totalOrders` → minimal (`@type {Number}` only) → REPLACE
- `lastRefreshed` → minimal (`@type {Date}` only) → REPLACE

### Expected
`applyDocumentations` items include `totalOrders` and `lastRefreshed` with descriptions added:

```javascript
/**
 * Total number of orders in the system.
 *
 * @type {Number}
 *
 * @properties={typeid:35,uuid:"6CEF9BA1-DF2F-49BC-9471-56D7F994562D",variableType:8}
 */
var totalOrders = 0;
```

**Success criteria:**
- ✅ `totalCustomers` NOT in items (already has description)
- ✅ `totalOrders` and `lastRefreshed` IN items (minimal — description missing)
- ✅ `@type` preserved in replaced JSDoc
- ✅ UUID preserved

**Pass/Fail:** _______________

---

## TEST 5.5 — UUID Corruption Resistance

**Purpose:** Verifies the `DocumentationValidator.restoreUUIDs()` safety layer prevents UUID changes even if the AI generates incorrect UUIDs.

### Setup
Manually send `applyDocumentations` with a deliberately wrong UUID (simulate AI hallucinating):
```json
{
  "functionName": "formatDate",
  "newJSDoc": "/**\n * Formats a date.\n * @param {Date} date\n * @return {String}\n * @properties={typeid:24,uuid:\"00000000-0000-0000-0000-000000000000\"}\n */",
  "startSentence": "/**",
  "endSentence": "*/"
}
```

The original UUID for `formatDate` is `A8F5BDA8-D822-41C7-BE87-7727823970BD`.

### Expected behavior
`DocumentationValidator.restoreUUIDs()` detects the UUID mismatch and silently restores the original:
```javascript
@properties={typeid:24,uuid:"A8F5BDA8-D822-41C7-BE87-7727823970BD"}
```

**Success criteria:**
- ✅ Written file contains original UUID `A8F5BDA8...` not the fake `00000000...`
- ✅ No error thrown — silent restoration
- ✅ Console shows: UUID restored for `formatDate`

**Pass/Fail:** _______________

---

## TEST 5.6 — Timestamp Protection (Stale Write Rejected)

**Purpose:** Verifies that `applyDocumentations()` rejects a write if the file was modified after the prompt timestamp was recorded.

### Steps
1. Open `utils.js`, send prompt: `"Improve the docs for utils"`
2. AI calls `getCodeChunk("utils")` — prompt timestamp recorded
3. **Before AI calls `applyDocumentations()`**, manually edit `utils.js` in the Eclipse editor and save it
4. AI now calls `applyDocumentations("utils.js", [...])`

### Expected behavior
`applyDocumentations()` detects: `fileLastModified > promptTimestamp` → rejects the write

**Expected AI response:**
```
The file utils.js was modified after I started reading it. 
Please re-run the documentation request to ensure I'm working with the latest version.
```

**Success criteria:**
- ✅ Write rejected (no changes applied)
- ✅ File NOT in "Modified files" panel
- ✅ AI acknowledges the rejection and suggests retrying

**Pass/Fail:** _______________

---

## TEST 5.7 — Multi-Turn: Survey → Question → Apply (dataUtils.js)

**Purpose:** Tests memory and context continuity across multiple conversation turns.

### Turn 1
**Prompt:**
```
Analyze the structure of dataUtils
```
AI responds with symbol map.

### Turn 2
**Prompt:**
```
Which functions have TODO stubs?
```
AI reads code (calls `getCodeChunk`) and identifies: `loadRecords`, `buildQuery`, `getDataSet`, `countRecords`.

### Turn 3
**Prompt:**
```
Improve just the buildQuery function
```
AI calls `applyDocumentations("dataUtils.js", [1 REPLACE item])` — only `buildQuery`.

**Success criteria:**
- ✅ AI remembers the file from Turn 1 (does not re-call `analyzeFileStructure`)
- ✅ Turn 2: AI reads code to identify TODO stubs (not guessing)
- ✅ Turn 3: Only 1 item applied — `buildQuery`, not all 4 stubs
- ✅ `@return {QBSelect}` inferred from code

**Pass/Fail:** _______________

---

## TEST 5.8 — Undo and Re-Apply (utils.js)

**Purpose:** Tests the "Modified files" panel Undo/Keep workflow.

### Steps
1. Run Test 4.1 to improve `utils.js` (6 REPLACE items)
2. File appears in "Modified files" panel
3. Click **Undo** on `utils.js`
4. Open `utils.js` — verify it's back to TODO stubs
5. Re-run: `"Improve the docs for utils"`
6. File appears again in "Modified files" panel
7. Click **Keep** on `utils.js`
8. Open `utils.js` — verify improved JSDoc persists

**Success criteria:**
- ✅ After Undo: file restored to TODO stubs
- ✅ After Keep: improved JSDoc written permanently
- ✅ UUIDs identical in both states
- ✅ No duplicate `/**` blocks after re-apply

**Pass/Fail:** _______________

---

## TEST 5.9 — Cross-File Type Chain

**Purpose:** Tests `resolveIdentifierType()` used in the context of documenting a function that uses a cross-scope type.

### Prompt
```
Improve the documentation for the onShow function in orderList. 
The currentCustomer variable has type JSRecord — verify this and use it.
```

### Expected AI workflow
1. `getCodeChunk("orderList", symbolName="onShow", chunkSize="MEDIUM")`
2. `resolveIdentifierType("currentCustomer", "orderList")` → `TYPE: JSRecord`
3. `getDocumentationForIdentifiers(["JSRecord", "JSEvent", "QBSelect"], "orderList")`
4. `applyDocumentations("orderList.js", [1 REPLACE item])`

**Expected JSDoc for `onShow`:**
```javascript
/**
 * Handles the form show event. Loads orders filtered by the active customer,
 * or all orders if no customer is selected.
 *
 * @param {Boolean} firstShow - True if this is the first time the form is shown
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"14BED867-1AC0-4C52-ACEC-CD62D5CC07DC"}
 */
function onShow(firstShow, event) {
```

**Success criteria:**
- ✅ `resolveIdentifierType()` called for `currentCustomer`
- ✅ Returns `JSRecord`
- ✅ JSDoc description references the customer filtering behavior
- ✅ 1 REPLACE item applied

**Pass/Fail:** _______________

---

## TEST 5.10 — Full Solution Documentation Pass

**Purpose:** Document all 8 files in a single session. Tests memory limits, context switching, and consistent UUID handling across all files.

### Prompt
```
Please improve the JSDoc documentation for all files in the svyPilotTest solution: 
utils, globals, dataUtils, mainNav, customerList, customerEdit, orderList, dashboard
```

### Expected AI workflow
For each file (in any order):
1. `analyzeFileStructure(name)`
2. `getCodeChunk(name, chunkSize="LARGE")` (+ chunk 2 for globals)
3. `getDocumentationForIdentifiers([...])` where needed
4. `applyDocumentations(path, [N items])`

### Expected totals

| File | Expected REPLACE items |
|------|----------------------|
| utils | 6 |
| mainNav | 7 |
| dataUtils | 6 |
| globals | 8–9 |
| customerList | 5 |
| customerEdit | 8 |
| orderList | 6 |
| dashboard | 9 |
| **Total** | **55–56** |

**Success criteria:**
- ✅ All 8 files appear in "Modified files" panel
- ✅ No `TODO generated` text remains in any file after the pass
- ✅ No UUID changes detected
- ✅ Session completes within the 100-message memory limit of Documentation Assistant
- ✅ AI does not confuse symbols between files

**Pass/Fail:** _______________

---

## Overall Results

| Test | Scenario | Pass/Fail |
|------|----------|-----------|
| 5.1 | Right-click no selection → full file | |
| 5.2 | Right-click with selection → partial | |
| 5.3 | Chat: improve globals (2 chunks) | |
| 5.4 | Minimal stub variables (dashboard) | |
| 5.5 | UUID corruption resistance | |
| 5.6 | Timestamp protection | |
| 5.7 | Multi-turn context continuity | |
| 5.8 | Undo and re-apply | |
| 5.9 | Cross-file type chain | |
| 5.10 | Full solution pass (all 8 files) | |

**Total:** ___ / 10
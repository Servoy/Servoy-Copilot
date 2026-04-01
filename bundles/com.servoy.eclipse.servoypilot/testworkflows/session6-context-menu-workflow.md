# SESSION 6: Context Menu Workflow — Right-Click "Improve Docs"

**Date:** April 1, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** `ServoyAiContextMenuHandler` → `SelectionTracker` → `DocumentationTools` + `CodeAnalysisTools`

---

## Overview

Tests the **right-click "Generate Docs"** context menu entry end-to-end, covering both trigger modes and the full tool chain that follows.

### How the trigger works

`ServoyAiContextMenuHandler.handleGenerateDocs()` sends one of two static messages depending on whether text is selected:

| Selection state | `SelectionTracker.isFullFileSelected()` | Message sent |
|----------------|----------------------------------------|--------------|
| No text selected (cursor only) | `true` | `"Please improve the JSDoc documentation for the entire file."` |
| Text selected | `false` | `"Please improve the JSDoc documentation for the current selection."` |

The AI receives the message, calls `getCurrentSelection()` as STEP 1, and branches from there.

### Tools available to the Documentation Assistant

| Tool | Source | Role |
|------|--------|------|
| `getCurrentSelection()` | `DocumentationTools` | Entry point — STEP 1 always |
| `analyzeFileStructure()` | `CodeAnalysisTools` | Symbol map when full-file mode |
| `getCodeChunk()` | `CodeAnalysisTools` | Read code — always required after `analyzeFileStructure` or for context |
| `getDocumentationForIdentifiers()` | `DocumentationTools` | Servoy API type lookup |
| `getAvailableMembersForType()` | `DocumentationTools` | Explore type members |
| `getDocumentationForTypeMember()` | `DocumentationTools` | Full docs for specific method |
| `resolveIdentifierType()` | `CodeAnalysisTools` | Resolve variable types |
| `applyDocumentations()` | `DocumentationTools` | Write JSDoc to file |

---

## Test Suite Structure

| Test | Trigger | File | Selection state | Key validation |
|------|---------|------|----------------|----------------|
| 6.1 | No selection | `mainNav.js` | Full file | Message = "entire file", `analyzeFileStructure` → `getCodeChunk(LARGE)` → 7 REPLACE |
| 6.2 | No selection | `dataUtils.js` | Full file | Full file, Servoy type lookup, 6 REPLACE |
| 6.3 | Selection: single function | `utils.js` | ~12-line selection | Selection only, 1 REPLACE, `getCodeChunk` for context |
| 6.4 | Selection: multiple functions | `customerEdit.js` | ~30-line selection | Partial file, 2–3 REPLACE items |
| 6.5 | Selection: mixed JSDoc quality | `globals.js` | ~60-line selection | Classifies complete vs TODO stubs |
| 6.6 | No selection, already-documented file | `utils.js` (after 4.1) | Full file | AI reads code, finds no TODOs, reports complete |
| 6.7 | No selection + `resolveIdentifierType` | `orderList.js` | Full file | `currentCustomer` type resolved before documenting |
| 6.8 | No selection + `getAvailableMembersForType` | `dashboard.js` | Full file | `JSDataSet` member lookup |
| 6.9 | Selection: variable + function | `customerList.js` | ~20-line selection | 2 REPLACE items: 1 var + 1 function |
| 6.10 | No selection, large file (2 chunks) | `globals.js` | Full file | 2 LARGE chunk reads required |

---

## Prerequisites

- [ ] `svyPilotTest` solution open in Servoy Developer
- [ ] All 8 JS files in `js-content/` reference state (TODO stubs present)
- [ ] Documentation Assistant active in ServoyPilot chat
- [ ] Eclipse console visible

---

## TEST 6.1 — No Selection, Full File (mainNav.js)

**Purpose:** Baseline no-selection test. All 7 functions have TODO stubs.

### Setup
- Open `mainNav.js` in editor
- Place cursor inside the file, no text selected

### Action
Right-click → **Generate Docs**

### Expected message
```
"Please improve the JSDoc documentation for the entire file."
```

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected=true, FILE: mainNav.js

2. analyzeFileStructure("mainNav")
   → 7 functions, no variables

3. getCodeChunk("mainNav", chunkNumber=1, chunkSize="LARGE")
   → LINES: 0-62, CHUNK: 1 of 1, LAST CHUNK

4. getDocumentationForIdentifiers(["JSEvent"], "mainNav")

5. applyDocumentations("/svyPilotTest/forms/mainNav.js", [7 REPLACE items])
```

**Success criteria:**
- ✅ Correct message (`"entire file"`, not `"selection"`)
- ✅ `getCurrentSelection()` is STEP 1
- ✅ `analyzeFileStructure()` called after — not before — `getCurrentSelection()`
- ✅ `getCodeChunk()` called after `analyzeFileStructure()` to read actual code
- ✅ 7 REPLACE items (all TODO stubs)
- ✅ `onHide` has `@return {Boolean}`

**Pass/Fail:** _______________

---

## TEST 6.2 — No Selection, Full File (dataUtils.js — Servoy types)

**Purpose:** Full-file no-selection with Servoy type lookup required.

### Setup
- Open `dataUtils.js`, no text selected

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=true, FILE: dataUtils.js
2. analyzeFileStructure("dataUtils") → 10 symbols
3. getCodeChunk("dataUtils", chunkNumber=1, chunkSize="LARGE") → 1 chunk (123 lines)
4. getDocumentationForIdentifiers(["JSFoundSet","JSRecord","JSDataSet","QBSelect","databaseManager.getFoundSet"], "dataUtils")
5. applyDocumentations("/svyPilotTest/scopes/dataUtils.js", [6 REPLACE items])
```

**Success criteria:**
- ✅ `CUSTOMER_DATASOURCE`, `ORDER_DATASOURCE`, `getRecord`, `saveRecord` skipped (complete)
- ✅ `lastQueryResult`, `lastRecordCount` included (minimal — no description)
- ✅ `loadRecords` has `@param {QBSelect}` and `@return {JSFoundSet}`
- ✅ `buildQuery` has `@return {QBSelect}`
- ✅ 6 REPLACE items total

**Pass/Fail:** _______________

---

## TEST 6.3 — Selection: Single Function (utils.js — truncateText)

**Purpose:** Tests the selection path for a single function. The selection is narrow (~12 lines). AI must call `getCodeChunk()` with SMALL size to get context.

### Setup
- Open `utils.js`
- Select lines covering `truncateText(text, maxLength)` — the JSDoc stub + function body

### Action
Right-click → **Generate Docs**

### Expected message
```
"Please improve the JSDoc documentation for the current selection."
```

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=false
   → selection contains only truncateText (12 lines)

2. getCodeChunk("utils", symbolName="truncateText", chunkSize="SMALL")
   → provides context (50-line window)

3. applyDocumentations("/svyPilotTest/scopes/utils.js", [1 REPLACE item])
```

**Expected JSDoc for `truncateText` (REPLACE):**
```javascript
/**
 * Truncates a text string to the specified maximum length, appending '...' if truncated.
 *
 * @param {String} text - The text to truncate
 * @param {Number} maxLength - Maximum number of characters to allow
 * @return {String} Truncated string with '...' appended, or original if within limit
 *
 * @properties={typeid:24,uuid:"40912BC6-21D8-4BE0-97B9-3A1700892665"}
 */
function truncateText(text, maxLength) {
```

**Success criteria:**
- ✅ Correct message (`"current selection"`)
- ✅ `isFullFileSelected=false`
- ✅ `getCodeChunk()` called with SMALL size for context
- ✅ Exactly 1 REPLACE item
- ✅ UUID `40912BC6...` preserved

**Pass/Fail:** _______________

---

## TEST 6.4 — Selection: Multiple Functions (customerEdit.js)

**Purpose:** Selection covers `save()` and `validate()` — both have bare `@properties` only (no TODO stub, no description).

### Setup
- Open `customerEdit.js`
- Select lines covering both `save()` and `validate()` (~30 lines)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=false, ~30-line selection
2. getCodeChunk("customerEdit", symbolName="save", chunkSize="MEDIUM") → context window
3. applyDocumentations("/svyPilotTest/forms/customerEdit.js", [2 REPLACE items])
```

**Success criteria:**
- ✅ 2 REPLACE items: `save()` + `validate()`
- ✅ `save()` has `@return {Boolean}`
- ✅ `validate()` has `@return {Array}`
- ✅ Functions NOT in selection (e.g. `onActionSave`) not included

**Pass/Fail:** _______________

---

## TEST 6.5 — Selection: Mixed JSDoc Quality (globals.js)

**Purpose:** Selection covers both complete and incomplete symbols. AI must classify and skip the complete ones.

### Setup
- Open `globals.js`
- Select ~60 lines covering:
  - `showForm(form, record)` — complete (description + typed params) → skip
  - `showMessage(message, title)` — complete → skip
  - `clearState()` — bare `@properties` only → REPLACE
  - `getCurrentUser()` — complete → skip
  - `isInitialized()` — bare `@properties` only → REPLACE

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=false
2. getCodeChunk("globals", symbolName="clearState", chunkSize="MEDIUM") → context
3. applyDocumentations("/svyPilotTest/scopes/globals.js", [2 REPLACE items])
```

**Success criteria:**
- ✅ `showForm`, `showMessage`, `getCurrentUser` NOT in items
- ✅ 2 REPLACE items: `clearState()` + `isInitialized()`
- ✅ AI correctly skips complete JSDoc even within the selection

**Pass/Fail:** _______________

---

## TEST 6.6 — No Selection, Already-Documented File (utils.js after Session 4)

**Purpose:** Verifies AI reads code and confirms nothing needs improvement — does NOT blindly re-apply.

### Setup
- Run Test 4.1 first (utils.js fully improved)
- Open `utils.js`, no text selected

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=true, FILE: utils.js
2. analyzeFileStructure("utils") → 8 symbols
3. getCodeChunk("utils", chunkNumber=1, chunkSize="LARGE") → reads code
4. AI finds: no TODO stubs, all descriptions present
5. NO applyDocumentations() call
```

**Expected AI response:**
```
All 8 symbols in utils.js already have complete JSDoc documentation. No changes needed.
```

**Success criteria:**
- ✅ `analyzeFileStructure()` + `getCodeChunk()` both called (AI must always verify)
- ✅ `applyDocumentations()` NOT called
- ✅ `utils.js` NOT in "Modified files" panel
- ✅ AI reports all symbols documented (not just "some")

**Pass/Fail:** _______________

---

## TEST 6.7 — No Selection + resolveIdentifierType (orderList.js)

**Purpose:** Verifies `resolveIdentifierType()` used autonomously when the AI encounters a variable reference it needs to type.

### Setup
- Open `orderList.js`, no text selected

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=true, FILE: orderList.js
2. analyzeFileStructure("orderList") → 6 symbols
3. getCodeChunk("orderList", chunkNumber=1, chunkSize="LARGE") → 93 lines
4. resolveIdentifierType("currentCustomer", "orderList") → TYPE: JSRecord
5. getDocumentationForIdentifiers(["JSEvent","JSRecord","QBSelect"], "orderList")
6. applyDocumentations("/svyPilotTest/forms/orderList.js", [6 REPLACE items])
```

**Success criteria:**
- ✅ `resolveIdentifierType()` called for `currentCustomer`
- ✅ `onShow` JSDoc describes the customer-based filtering behavior
- ✅ `onFilterQueryCondition` gets all 5 `@param` tags + `@return {Boolean}`
- ✅ `currentCustomer` description improved (adds "Currently selected customer loaded from globals")

**Pass/Fail:** _______________

---

## TEST 6.8 — No Selection + getAvailableMembersForType (dashboard.js)

**Purpose:** Verifies `getAvailableMembersForType()` called when AI needs to understand how `JSDataSet` is used in `refreshStats`.

### Setup
- Open `dashboard.js`, no text selected

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=true, FILE: dashboard.js
2. analyzeFileStructure("dashboard") → 10 symbols
3. getCodeChunk("dashboard", chunkNumber=1, chunkSize="LARGE") → 107 lines
4. getAvailableMembersForType("JSDataSet", "getMax.*") → confirms getMaxRowIndex() signature
5. getDocumentationForIdentifiers(["JSDataSet","Number","Date"], "dashboard")
6. applyDocumentations("/svyPilotTest/forms/dashboard.js", [9 REPLACE items])
```

**Success criteria:**
- ✅ `getAvailableMembersForType()` used for `JSDataSet` member lookup
- ✅ `refreshStats()` description mentions both customer and order stats
- ✅ `formatSummary(value, label)` has `@return {String}`
- ✅ `totalCustomers` skipped (already complete)

**Pass/Fail:** _______________

---

## TEST 6.9 — Selection: Variable + Function (customerList.js)

**Purpose:** Selection covers one minimal-stub variable + one TODO-stub function.

### Setup
- Open `customerList.js`
- Select ~20 lines covering:
  - `filterText` variable (minimal stub — no description)
  - `onRecordSelection` function (TODO stub)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=false, 20-line selection
2. getCodeChunk("customerList", symbolName="filterText", chunkSize="SMALL") → context
3. getDocumentationForIdentifiers(["JSEvent","JSRecord"])
4. applyDocumentations("/svyPilotTest/forms/customerList.js", [2 REPLACE items])
```

**Success criteria:**
- ✅ 2 REPLACE items: `filterText` (variable) + `onRecordSelection` (function)
- ✅ `filterText` gets description added, `@type {String}` preserved
- ✅ `onRecordSelection` gets `@param {JSEvent} event`
- ✅ Other symbols outside selection not modified

**Pass/Fail:** _______________

---

## TEST 6.10 — No Selection, Large File Requiring 2 Chunks (globals.js)

**Purpose:** Verifies AI reads all chunks before applying — does NOT apply after reading only chunk 1.

### Setup
- Open `globals.js`, no text selected

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection() → isFullFileSelected=true, FILE: globals.js
2. analyzeFileStructure("globals") → 17 symbols
3. getCodeChunk("globals", chunkNumber=1, chunkSize="LARGE") → CHUNK: 1 of 2
4. getCodeChunk("globals", chunkNumber=2, chunkSize="LARGE") → CHUNK: 2 of 2, LAST CHUNK
5. getDocumentationForIdentifiers(["JSRecord","JSFoundSet","RuntimeForm"])
6. applyDocumentations("/svyPilotTest/scopes/globals.js", [8-9 REPLACE items])
```

**Critical check:** AI must NOT call `applyDocumentations()` after chunk 1 — it must read chunk 2 first.

**Success criteria:**
- ✅ **2 `getCodeChunk()` calls** before `applyDocumentations()`
- ✅ Symbols in lines 200–202 (covered only in chunk 2) included if they need improvement
- ✅ Complete symbols across both chunks skipped
- ✅ `getMaxRecords()` (in chunk 2, near line 179) correctly included or excluded based on JSDoc quality

**Pass/Fail:** _______________

---

## Overall Results

| Test | Trigger | File | Pass/Fail |
|------|---------|------|-----------|
| 6.1 | No selection | mainNav — 7 REPLACE | |
| 6.2 | No selection | dataUtils — 6 REPLACE, Servoy types | |
| 6.3 | Selection: 1 function | utils — truncateText | |
| 6.4 | Selection: 2 functions | customerEdit — save + validate | |
| 6.5 | Selection: mixed quality | globals — 2 REPLACE, 3 skipped | |
| 6.6 | No selection, already documented | utils — no apply | |
| 6.7 | No selection + resolveIdentifierType | orderList — 6 REPLACE | |
| 6.8 | No selection + getAvailableMembersForType | dashboard — 9 REPLACE | |
| 6.9 | Selection: var + function | customerList — 2 REPLACE | |
| 6.10 | No selection, 2 chunks | globals — 8–9 REPLACE | |

**Total:** ___ / 10
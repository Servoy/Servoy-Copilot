# SESSION 6: Context Menu Workflow — Right-Click "Generate Docs"

**Date:** March 26, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** `ServoyAiContextMenuHandler` → `SelectionTracker` → `DocumentationTools` + `CodeAnalysisTools`

---

## Overview

This session tests the **right-click "Generate Docs"** context menu entry end-to-end, covering both trigger modes and the full tool chain that follows — including `CodeAnalysisTools` which was added after the original context menu code was written.

### How the trigger works (from source)

`ServoyAiContextMenuHandler.handleGenerateDocs()` always sends the same static message:
```
"Please generate JSDoc documentation for the current selection."
```

It does **no selection-aware branching** — unlike Review, Explain, or Generate Tests which build context-aware messages. The entire intelligence about what to document lives in the AI + tools.

`SelectionTracker.getCurrentSelection()` handles the two cases transparently:
- **Cursor only (no selection, `length == 0`)** → expands to full file content, sets `isFullFileSelected = true`
- **Text selected (`length > 0`)** → returns exact selected line range

The AI receives the static message, calls `getCurrentSelection()` as STEP 1, and branches based on what it gets back.

### Tools available to the Documentation Assistant
| Tool | Source | Role in this session |
|------|--------|---------------------|
| `getCurrentSelection()` | `DocumentationTools` | Entry point — triggered as STEP 1 |
| `analyzeFileStructure()` | `CodeAnalysisTools` | Survey file when full-file mode |
| `getCodeChunk()` | `CodeAnalysisTools` | Read code when selection is large or partial |
| `getDocumentationForIdentifiers()` | `DocumentationTools` | Servoy API type lookup |
| `getAvailableMembersForType()` | `DocumentationTools` | Explore type members |
| `getDocumentationForTypeMember()` | `DocumentationTools` | Full docs for specific method |
| `resolveIdentifierType()` | `CodeAnalysisTools` | Resolve variable types |
| `applyDocumentations()` | `DocumentationTools` | Write JSDoc to file |

---

## Test Suite Structure

| Test | Trigger mode | File | Selection state | Key tool chain |
|------|-------------|------|----------------|----------------|
| 6.1 | No selection (cursor only) | `mainNav.js` | Full file | `getCurrentSelection` → `analyzeFileStructure` → `getCodeChunk(LARGE)` → `apply` |
| 6.2 | No selection (cursor only) | `dataUtils.js` | Full file | `getCurrentSelection` → `analyzeFileStructure` → `getCodeChunk(LARGE)` → type lookup → `apply` |
| 6.3 | Single function selected | `utils.js` | 4-line selection | `getCurrentSelection` → direct `apply` (small, no chunk needed) |
| 6.4 | Multiple functions selected | `customerEdit.js` | ~30-line selection | `getCurrentSelection` → `getCodeChunk(MEDIUM)` for context → `apply` |
| 6.5 | Partial file — mixed JSDoc state | `globals.js` | ~50-line selection | `getCurrentSelection` → INSERT + REPLACE mixed |
| 6.6 | No selection on already-documented file | `utils.js` (after Session 4) | Full file | `getCurrentSelection` → `analyzeFileStructure` → reports 100% → no apply needed |
| 6.7 | No selection + `resolveIdentifierType` | `orderList.js` | Full file | `getCurrentSelection` → `analyzeFileStructure` → `resolveIdentifierType` for `currentCustomer` → `apply` |
| 6.8 | No selection + `getAvailableMembersForType` | `dashboard.js` | Full file | `getCurrentSelection` → `getAvailableMembersForType("JSDataSet")` → `apply` |
| 6.9 | Selection spanning a variable + function | `customerList.js` | 12-line selection | `getCurrentSelection` → INSERT for variable + INSERT for function |
| 6.10 | Cursor-only, large file (multiple chunks) | `customerEdit.js` | Full file | `getCurrentSelection` → `analyzeFileStructure` → `getCodeChunk(LARGE)` × 1 (89 lines fits) |

---

## Prerequisites

- [ ] `svyPilotTest` solution open in Servoy Developer
- [ ] `mainNav.js`, `utils.js`, `dataUtils.js`, `globals.js`, `orderList.js`, `dashboard.js`, `customerList.js`, `customerEdit.js` all have bare functions (reset from Session 4/5 if needed)
- [ ] Documentation Assistant is active in ServoyPilot chat
- [ ] Eclipse console visible

---

## TEST 6.1 — No Selection, Full File (mainNav.js — pure INSERT)

**Purpose:** Baseline test for the no-selection path. `mainNav.js` is 26 lines, all 6 functions bare. Verifies that `SelectionTracker` correctly expands cursor-only to full file and that the AI uses `analyzeFileStructure` + `getCodeChunk` before applying.

### Setup
- Open `mainNav.js` in the JavaScript editor
- Place cursor anywhere inside the file (do NOT select any text)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true
   → FILE: /svyPilotTest/mainNav.js
   → LINES: 0–25 (entire file)
   → CONTENT_HASH: <hash>

2. analyzeFileStructure("mainNav.js")          ← AI surveys before reading
   → 6 bare functions [ ] onLoad, onShow, onActionCustomers,
     onActionOrders, onActionDashboard, onHide

3. getCodeChunk("mainNav", chunkNumber=0, chunkSize="LARGE")
   → CHUNK: 1 of 1 (LAST CHUNK) — 26 lines fits in LARGE

4. getDocumentationForIdentifiers(["JSEvent"])

5. applyDocumentations("/svyPilotTest/mainNav.js", <hash>, [6 INSERT items])
```

**Expected JSDoc for `onHide` (must include `@return`):**
```javascript
/**
 * Handles the form hide event.
 *
 * @param {JSEvent} event - The event that triggered the action
 * @return {Boolean} Always returns true to allow hiding
 */
function onHide(event) {
```

**Success criteria:**
- ✅ `getCurrentSelection()` called first, returns `isFullFileSelected=true`
- ✅ `analyzeFileStructure()` called — AI does not blindly read without surveying
- ✅ `getCodeChunk()` called with `chunkSize="LARGE"` (full-file exploration)
- ✅ 6 INSERT items in one `applyDocumentations()` call
- ✅ All 6 functions documented, `onHide` has `@return {Boolean}`
- ✅ File appears in "Modified files" panel

---

## TEST 6.2 — No Selection, Full File with Servoy Types (dataUtils.js)

**Purpose:** No-selection path on a file containing Servoy types (`JSFoundSet`, `JSDataSet`, `QBSelect`). Verifies the AI uses `getDocumentationForIdentifiers` for type lookup after reading the full file.

### Setup
- Open `dataUtils.js` in the JavaScript editor
- Place cursor anywhere (no selection)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true, FILE: /svyPilotTest/dataUtils.js

2. analyzeFileStructure("dataUtils")
   → [✓] getRecord, [✓] saveRecord (have JSDoc)
   → [ ] loadRecords, [ ] buildQuery, [ ] getDataSet, [ ] countRecords (bare)

3. getCodeChunk("dataUtils", chunkNumber=0, chunkSize="LARGE")
   → CHUNK: 1 of 1 — 86 lines

4. getDocumentationForIdentifiers(["databaseManager.getFoundSet",
     "databaseManager.createSelect", "databaseManager.getDataSetByQuery",
     "JSFoundSet", "JSDataSet", "QBSelect"], "dataUtils.js")

5. applyDocumentations("/svyPilotTest/dataUtils.js", <hash>, [4 INSERT items])
   → Only bare functions — getRecord and saveRecord left untouched
```

**Success criteria:**
- ✅ `analyzeFileStructure()` correctly identifies 2 documented + 4 bare
- ✅ AI only generates INSERT items for bare functions (not REPLACE for existing ones)
- ✅ `QBSelect` type used correctly in `@param` for `buildQuery` and `getDataSet`
- ✅ `@return {JSFoundSet}` on `loadRecords`
- ✅ `getRecord` and `saveRecord` left untouched

---

## TEST 6.3 — Single Function Selected (utils.js — formatDate)

**Purpose:** Text selection path — user explicitly selects one function. Verifies the AI reads only the selection and does not expand to full file.

### Setup
- Open `utils.js` in the JavaScript editor
- Select exactly the `formatDate` function (lines 16–20 in the actual file after `@properties` injection):
  ```
  function formatDate(date, format) {
      if (!date) return '';
      var fmt = format ? format : DEFAULT_DATE_FORMAT;
      return utils.dateFormat(date, fmt);
  }
  ```

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = false
   → START_LINE: 16, END_LINE: 20 (4 lines)
   → Only formatDate visible in code output

2. (No analyzeFileStructure — selection already gives the code)

3. (No getCodeChunk — selection fits in getCurrentSelection output)

4. applyDocumentations("/svyPilotTest/utils.js", <hash>, [1 INSERT item])
```

**Expected JSDoc:**
```javascript
/**
 * Formats a date using the specified format string.
 *
 * @param {Date} date - The date to format
 * @param {String} [format] - Format string; defaults to DEFAULT_DATE_FORMAT
 * @return {String} Formatted date string, or empty string if date is null
 */
function formatDate(date, format) {
```

**Success criteria:**
- ✅ `isFullFileSelected = false` — AI stays within selection
- ✅ Exactly 1 INSERT item (not 6 — other functions not touched)
- ✅ No `analyzeFileStructure()` call (AI already has the code from selection)
- ✅ `@param {String} [format]` — optional parameter syntax used (defaults to `DEFAULT_DATE_FORMAT`)

---

## TEST 6.4 — Multiple Functions Selected (customerEdit.js)

**Purpose:** Text selection spanning multiple bare functions. Verifies the AI documents all selected functions and uses `getCodeChunk` when it needs context beyond the selection window.

### Setup
- Open `customerEdit.js` in the JavaScript editor
- Select lines covering `onActionSave`, `onActionCancel`, `save`, `validate` (4 bare functions, approximately 40 lines)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = false
   → ~40 lines selected, 4 bare functions visible

2. getDocumentationForIdentifiers(["databaseManager.saveData",
     "databaseManager.rollbackEditedRecords"])

3. applyDocumentations("/svyPilotTest/customerEdit.js", <hash>, [4 INSERT items])
```

**Expected JSDoc for `save` (return inferred from `return databaseManager.saveData()`):**
```javascript
/**
 * Validates and saves the current record to the database.
 *
 * @return {Boolean} True if save succeeded, false if validation failed
 */
function save() {
```

**Expected JSDoc for `validate` (return inferred from `return errors`):**
```javascript
/**
 * Validates the current form data.
 *
 * @return {Array} Array of error message strings; empty if valid
 */
function validate() {
```

**Success criteria:**
- ✅ Exactly 4 INSERT items — only the selected functions
- ✅ `onLoad` and `onShow` (above selection) untouched
- ✅ `updateUI` and `onHide` (below selection) untouched
- ✅ `save()` has `@return {Boolean}` inferred from code
- ✅ `validate()` has `@return {Array}` inferred from code

---

## TEST 6.5 — Selection with Mixed JSDoc State (globals.js)

**Purpose:** Selected region contains both documented and bare functions. Verifies INSERT-only mode (no accidental REPLACE of existing good JSDoc).

### Setup
- Open `globals.js` in the JavaScript editor
- Select a region that includes:
  - `showForm()` — already has full JSDoc ✓
  - `showMessage()` — already has full JSDoc ✓
  - `clearState()` — bare ✗
  - `getCurrentUser()` — already has full JSDoc ✓
  - `isInitialized()` — bare ✗
  - `setInitialized()` — bare ✗

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = false, ~80 lines selected

2. (AI analyzes — sees mix of documented and bare)

3. getDocumentationForIdentifiers(["security.getUserName"])

4. applyDocumentations("/svyPilotTest/globals.js", <hash>, [3 INSERT items])
   → Only clearState, isInitialized, setInitialized
```

**Success criteria:**
- ✅ Exactly 3 INSERT items (the 3 bare functions)
- ✅ `showForm`, `showMessage`, `getCurrentUser` completely untouched
- ✅ No REPLACE items generated for already-documented functions
- ✅ `setInitialized` gets `@param {Boolean} value`

---

## TEST 6.6 — No Selection, Already-Documented File

**Purpose:** Verifies the AI correctly reports 100% coverage and skips `applyDocumentations()` when nothing needs to be done. Guards against redundant overwrites.

### Setup
- Ensure `utils.js` is fully documented (run Session 4 Test 4.1 first if needed)
- Open `utils.js`, place cursor inside (no selection)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true

2. analyzeFileStructure("utils")
   → All 8 symbols show [✓]
   → DOCUMENTED: 8 (100%)

3. (No getCodeChunk — nothing to read)
4. (No applyDocumentations — nothing to write)
```

**Expected response:**
```
All 8 symbols in utils.js are already documented (100% coverage). No changes needed.
```

**Success criteria:**
- ✅ `analyzeFileStructure()` called and reports 100% coverage
- ✅ `applyDocumentations()` NOT called
- ✅ File does NOT appear in "Modified files" panel
- ✅ AI response explains coverage is complete (not "Applied 0 items")

---

## TEST 6.7 — No Selection + resolveIdentifierType (orderList.js)

**Purpose:** Verifies `resolveIdentifierType` (CodeAnalysisTools) is used in the context menu workflow when the AI needs to determine the type of a variable that is used as a parameter.

### Setup
- Open `orderList.js`, place cursor inside (no selection)
- `currentCustomer` is declared as `@type {JSRecord}` at the top of the file

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true

2. analyzeFileStructure("orderList")
   → [✓] currentCustomer (VARIABLE)
   → [ ] onShow, onRecordSelection, onCellDoubleClick,
         onFilterQueryCondition, onActionBack (5 bare functions)

3. getCodeChunk("orderList", chunkNumber=0, chunkSize="LARGE")

4. resolveIdentifierType("currentCustomer", "orderList")
   → TYPE: JSRecord

5. getDocumentationForIdentifiers(["JSEvent", "QBSelect"])

6. applyDocumentations("/svyPilotTest/orderList.js", <hash>, [5 INSERT items])
```

**Expected JSDoc for `onShow` (uses `currentCustomer` which resolves to `JSRecord`):**
```javascript
/**
 * Handles the form show event. Loads orders for the active customer, or all orders if none selected.
 *
 * @param {Boolean} firstShow - True if this is the first time the form is shown
 * @param {JSEvent} event - The event that triggered the action
 */
function onShow(firstShow, event) {
```

**Success criteria:**
- ✅ `resolveIdentifierType("currentCustomer", "orderList")` called
- ✅ Result (`JSRecord`) informs documentation of functions that use `currentCustomer`
- ✅ `onCellDoubleClick` gets all 4 `@param` tags including `{JSRecord} record`
- ✅ `onFilterQueryCondition` gets `@param {QBSelect} query` and `@return {Boolean}`

---

## TEST 6.8 — No Selection + getAvailableMembersForType (dashboard.js)

**Purpose:** Verifies `getAvailableMembersForType` (DocumentationTools) is exercised in the context menu path when the AI needs to explore what methods `JSDataSet` offers.

### Setup
- Open `dashboard.js`, place cursor inside (no selection)

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true

2. analyzeFileStructure("dashboard")
   → [✓] totalCustomers, totalOrders, lastRefreshed (VARIABLE)
   → [ ] onLoad, onShow, refreshStats, formatSummary,
         onActionRefresh, onActionGoToCustomers, onActionGoToOrders (7 bare)

3. getCodeChunk("dashboard", chunkNumber=0, chunkSize="LARGE")
   → Sees: orderData.getMaxRowIndex() — AI uncertain about JSDataSet API

4. getAvailableMembersForType("JSDataSet", "get.*")
   → Lists: getMaxRowIndex(), getColumnAsArray(), getValue(), etc.

5. applyDocumentations("/svyPilotTest/dashboard.js", <hash>, [7 INSERT items])
```

**Expected JSDoc for `refreshStats`:**
```javascript
/**
 * Refreshes dashboard statistics by querying customer and order counts.
 * Updates totalCustomers, totalOrders, and lastRefreshed.
 */
function refreshStats() {
```

**Expected JSDoc for `formatSummary`:**
```javascript
/**
 * Formats a numeric value with a descriptive label for dashboard display.
 *
 * @param {Number} value - The numeric value to format
 * @param {String} label - The label prefix
 * @return {String} Formatted summary string (e.g. 'Customers: 42')
 */
function formatSummary(value, label) {
```

**Success criteria:**
- ✅ `getAvailableMembersForType("JSDataSet", ...)` called
- ✅ `refreshStats` documented as void (no `@return`)
- ✅ `formatSummary` returns `{String}`
- ✅ 7 INSERT items in one `applyDocumentations()` call

---

## TEST 6.9 — Selection Spanning Variable + Function (customerList.js)

**Purpose:** Selection contains a mix of a variable declaration and a function. Verifies the AI correctly generates `@type` JSDoc for the variable and `@param`/`@return` JSDoc for the function.

### Setup
- Open `customerList.js`
- Select lines covering `filterText` variable and `onRecordSelection` function:
  ```
  var filterText = null;

  function onRecordSelection(event) {
      selectedCustomer = scopes.dataUtils.getRecord(foundset, foundset.getSelectedIndex());
      scopes.globals.activeCustomer = selectedCustomer;
  }
  ```

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = false, ~8 lines

2. applyDocumentations("/svyPilotTest/customerList.js", <hash>, [2 items])
   → Item 1: INSERT for filterText variable (before var declaration)
   → Item 2: INSERT for onRecordSelection function
```

**Expected JSDoc for `filterText`:**
```javascript
/**
 * Current search filter text entered by the user.
 *
 * @type {String}
 */
var filterText = null;
```

**Expected JSDoc for `onRecordSelection`:**
```javascript
/**
 * Handles the record selection event. Updates the active customer in global scope.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onRecordSelection(event) {
```

**Success criteria:**
- ✅ 2 INSERT items — one variable, one function
- ✅ Variable gets `@type {String}` (inferred from context: used as filter text)
- ✅ Function gets `@param {JSEvent} event`
- ✅ `selectedCustomer` (above selection) untouched

---

## TEST 6.10 — No Selection, customerEdit.js (Complex Mixed State)

**Purpose:** Full-file no-selection trigger on the most complex form file — mixed JSDoc state, 3 documented + 6 bare symbols. Verifies the AI correctly distinguishes INSERT vs REPLACE for all items.

### Setup
- Open `customerEdit.js`, place cursor inside (no selection)
- State: `onLoad` and `onShow` are documented; variables have minimal `@type`-only JSDoc; 6 functions are bare

### Action
Right-click → **Generate Docs**

### Expected tool chain
```
1. getCurrentSelection()
   → isFullFileSelected = true

2. analyzeFileStructure("customerEdit")
   → [✓] isNewRecord, validationErrors, originalCompanyName (VARIABLE — have @type)
   → [✓] onLoad, onShow (FUNCTION — have JSDoc)
   → [ ] onActionSave, onActionCancel, save, validate, updateUI, onHide (bare)

3. getCodeChunk("customerEdit", chunkNumber=0, chunkSize="LARGE")
   → CHUNK: 1 of 1 — 89 lines

4. getDocumentationForIdentifiers(["databaseManager.hasNewRecords",
     "databaseManager.saveData", "databaseManager.rollbackEditedRecords"])

5. applyDocumentations("/svyPilotTest/customerEdit.js", <hash>, [
     3 REPLACE items (improve variable @type-only JSDoc),
     6 INSERT items (bare functions)
   ])
```

**Success criteria:**
- ✅ 3 REPLACE items for variables: `isNewRecord`, `validationErrors`, `originalCompanyName` (improve `@type`-only JSDoc to add description)
- ✅ 6 INSERT items for bare functions
- ✅ `onLoad` and `onShow` completely untouched (already have full JSDoc)
- ✅ `save()` gets `@return {Boolean}` inferred from `return databaseManager.saveData()`
- ✅ `validate()` gets `@return {Array}`
- ✅ `updateUI()` has no `@return` (void)
- ✅ `onHide()` gets `@return {Boolean}` (explicitly returns `true`)
- ✅ UUID in all `@properties` tags survive unchanged

---

## Tool Chain Coverage Map

This table shows which `CodeAnalysisTools` methods are exercised via the context menu path in this session:

| Tool | Tests that exercise it via context menu |
|------|-----------------------------------------|
| `getCurrentSelection()` | All (6.1–6.10) — always STEP 1 |
| `analyzeFileStructure()` | 6.1, 6.2, 6.6, 6.7, 6.8, 6.10 — used in full-file no-selection path |
| `getCodeChunk()` | 6.1, 6.2, 6.7, 6.8, 6.10 — used after survey |
| `getDocumentationForIdentifiers()` | 6.1, 6.2, 6.4, 6.5, 6.7, 6.10 |
| `getAvailableMembersForType()` | 6.8 |
| `getDocumentationForTypeMember()` | — (covered in Session 5.9) |
| `resolveIdentifierType()` | 6.7 |
| `applyDocumentations()` | 6.1–6.5, 6.7–6.10 (not 6.6 — already documented) |

---

## Key Differences vs Session 5

Session 5 tests 5.1 and 5.2 also cover the context menu path but were written before `CodeAnalysisTools` was added. The differences:

| Aspect | Session 5 (5.1, 5.2) | Session 6 |
|--------|---------------------|-----------|
| `analyzeFileStructure` tested via context menu | ✗ | ✅ (6.1, 6.2, 6.6–6.8, 6.10) |
| `getCodeChunk` tested via context menu | ✗ | ✅ (6.1, 6.2, 6.7, 6.8, 6.10) |
| `resolveIdentifierType` tested via context menu | ✗ | ✅ (6.7) |
| `getAvailableMembersForType` tested via context menu | ✗ | ✅ (6.8) |
| No-selection → full-file expansion | 5.1 (single function deleted, minimal) | ✅ Full coverage (6.1, 6.2, 6.6–6.8, 6.10) |
| Selection → partial file | 5.2 (3 functions) | ✅ Variable + function, single function, multi-function (6.3, 6.4, 6.9) |
| Already-documented guard | ✗ | ✅ (6.6) |

---

## Failure Mode Reference

| Symptom | Likely cause |
|---------|-------------|
| `getCurrentSelection()` returns empty | No active editor, or editor not a JavaScript file |
| `isFullFileSelected = false` when no text selected | `SelectionTracker` not picking up cursor position — check `selectionChanged()` registration |
| AI calls `getCodeChunk` without calling `analyzeFileStructure` first | Prompt guidance not followed — check STEP 1 decision logic in `documentation.txt` |
| AI calls `applyDocumentations` on already-documented file | `analyzeFileStructure` not called or its output not checked |
| Partial-selection case documents the entire file | `isFullFileSelected` flag incorrectly set to `true` in `SelectionTracker` |
| REPLACE generated for bare function (no existing JSDoc) | AI misread line numbers; verify `analyzeFileStructure` `[✓]`/`[ ]` status |
| `@return` missing on `onHide` | AI dropped return inference; check `@return` (no `s`) rule in prompt |

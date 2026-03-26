# SESSION 4: Multi-File Workflows — Test Workflows

**Date:** March 26, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** DocumentationTools + CodeAnalysisTools (Documentation Assistant)

---

## Overview

This document tests the Documentation Assistant's ability to work across **multiple files** in a single session — generating, inserting, and replacing JSDoc across 3 scope files and 5 form files of the `svyPilotTest` solution.

**What is tested:**
- Batch documentation across multiple files without losing context
- Cross-scope type resolution (`scopes.utils`, `scopes.dataUtils`, `scopes.globals`)
- INSERT mode on completely bare functions (mainNav, utils)
- REPLACE mode on existing incomplete JSDoc (globals, customerEdit)
- Mixed-mode in a single `applyDocumentations()` call
- UUID preservation across all modified files
- Content hash validation preventing stale writes

**Tools exercised:**
- `analyzeFileStructure(pathOrName)` — survey each file before writing
- `getCodeChunk(pathOrName, symbolName?, chunkNumber?, startLine?, chunkSize?)` — read code (SMALL=50 / MEDIUM=100 / LARGE=200 lines)
- `getDocumentationForIdentifiers(identifiers[], filePath?)` — Servoy API docs
- `applyDocumentations(filePath, contentHash, items[])` — write JSDoc

---

## Test Suite Structure

| Test | File(s) | JSDoc State | Mode | Key Types |
|------|---------|-------------|------|-----------|
| 4.1 | `utils` | None on functions | INSERT only | Standard JS |
| 4.2 | `mainNav` | None on functions | INSERT only | `JSEvent`, `RuntimeForm` |
| 4.3 | `dataUtils` | Mixed (2 documented) | INSERT + REPLACE | `JSFoundSet`, `JSRecord`, `JSDataSet`, `QBSelect` |
| 4.4 | `globals` | Mixed (functions bare) | INSERT + REPLACE | `JSRecord`, `JSFoundSet`, `RuntimeForm` |
| 4.5 | `customerList` | Partial | INSERT + REPLACE | `JSRecord`, `JSFoundSet`, `JSEvent` |
| 4.6 | `customerEdit` | Mixed (2 forms documented) | INSERT + REPLACE | `JSRecord`, `Boolean`, `Array` |
| 4.7 | `orderList` | None on functions | INSERT only | `JSRecord`, `QBSelect`, `JSEvent` |
| 4.8 | `dashboard` | None on functions | INSERT only | `JSDataSet`, `Number`, `Date` |
| 4.9 | Batch: utils + mainNav | None | INSERT only | Mixed |
| 4.10 | Cross-scope verification | Post-write | Read-back check | All |
| 4.11 | Chunk size SMALL (50) | `dataUtils` | TARGETED read | Single symbol |
| 4.12 | Chunk size MEDIUM (100) | `customerEdit` | SEQUENTIAL read | Multi-function |
| 4.13 | Chunk size LARGE (200) | `globals` | SEQUENTIAL read | Full file |
| 4.14 | AI chunk size decision | `orderList` | AI-chosen size | Complex function |

---

## Prerequisites

Before running these tests:
- [ ] `svyPilotTest` solution is open in Servoy Developer
- [ ] All 8 JS files have `@properties` lines (UUID-bearing) — confirms Phase 3 complete
- [ ] Documentation Assistant is the active assistant in ServoyPilot chat

---

## TEST 4.1 — utils.js: Pure INSERT (bare functions, standard JS types)

**Purpose:** Simplest possible multi-function INSERT test. No Servoy API needed. All 6 functions have zero JSDoc.

### Step 1 — Survey the file

**Prompt:**
```
Analyze the file structure of utils
```

**Expected output (abbreviated):**
```
FILE: /svyPilotTest/utils.js
TOTAL SYMBOLS: 8

VARIABLES (2):
  [✓] DEFAULT_DATE_FORMAT (VARIABLE) - line 1
  [✓] DEFAULT_CURRENCY_SYMBOL (VARIABLE) - line 9

FUNCTIONS (6) — all undocumented:
  [ ] formatDate (FUNCTION) - line 17
  [ ] formatCurrency (FUNCTION) - line 22
  [ ] isEmptyString (FUNCTION) - line 27
  [ ] truncateText (FUNCTION) - line 31
  [ ] parseNumber (FUNCTION) - line 36
  [ ] buildErrorMessage (FUNCTION) - line 41
```

**Success criteria:**
- ✅ 2 documented variables, 6 bare functions reported
- ✅ Line numbers match actual file positions

---

### Step 2 — Read and document

**Prompt:**
```
Please generate JSDoc for all undocumented functions in utils
```

**Expected AI workflow:**
1. Calls `getCodeChunk("utils")` — entire file fits in 1 chunk (53 lines)
2. Recognizes standard JS types only — skips `getDocumentationForIdentifiers()`
3. Builds 6 `DocumentationItem` records — all INSERT mode (startSentence="", endSentence="")
4. Calls `applyDocumentations("utils.js", <hash>, [6 items])`

**Expected JSDoc for `formatDate`:**
```javascript
/**
 * Formats a date value using the specified format string.
 *
 * @param {Date} date - The date to format
 * @param {String} [format] - Optional format string (defaults to DEFAULT_DATE_FORMAT)
 * @return {String} Formatted date string, or empty string if date is null
 */
function formatDate(date, format) {
```

**Expected JSDoc for `buildErrorMessage`:**
```javascript
/**
 * Builds a formatted error message string from an array of error strings.
 *
 * @param {Array} errors - Array of error message strings
 * @return {String} Newline-separated error list, or empty string if no errors
 */
function buildErrorMessage(errors) {
```

**Success criteria:**
- ✅ 6 INSERT items in one `applyDocumentations()` call
- ✅ All `@param` types are standard JS (`Date`, `String`, `Number`, `Array`, `Boolean`)
- ✅ File appears in "Modified files" panel
- ✅ UUID lines remain untouched
- ✅ No `@type` or `@properties` lines altered

---

## TEST 4.2 — mainNav.js: Pure INSERT (event handlers, JSEvent)

**Purpose:** Form event handlers with `JSEvent` parameter — tests Servoy type lookup for simple events.

### Prompt:
```
Generate JSDoc for all functions in mainNav
```

**Expected AI workflow:**
1. Calls `analyzeFileStructure("mainNav")` — 6 bare functions, no variables
2. Calls `getCodeChunk("mainNav")` — 26 lines, single chunk
3. Calls `getDocumentationForIdentifiers(["JSEvent"], "mainNav.js")` — looks up `JSEvent`
4. Calls `applyDocumentations("mainNav.js", <hash>, [6 items])`

**Expected JSDoc for `onLoad`:**
```javascript
/**
 * Handles the form load event. Clears the global state on navigation return.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onLoad(event) {
```

**Expected JSDoc for `onActionCustomers`:**
```javascript
/**
 * Navigates to the customer list form.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onActionCustomers(event) {
```

**Expected JSDoc for `onHide`:**
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
- ✅ 6 INSERT items applied in one call
- ✅ `JSEvent` type documented via `getDocumentationForIdentifiers()`
- ✅ `onHide` correctly includes `@return {Boolean}`
- ✅ `onShow` correctly documents `firstShow` as `{Boolean}` parameter

---

## TEST 4.3 — dataUtils.js: Mixed INSERT + REPLACE

**Purpose:** First mixed-mode test. `getRecord` and `saveRecord` have existing JSDoc (REPLACE). Four functions are bare (INSERT).

### Step 1 — Survey

**Prompt:**
```
Analyze dataUtils
```

**Expected output (abbreviated):**
```
VARIABLES (4):
  [✓] CUSTOMER_DATASOURCE (VARIABLE) - line 1
  [✓] ORDER_DATASOURCE (VARIABLE) - line 9
  [✓] lastQueryResult (VARIABLE) - line 17
  [✓] lastRecordCount (VARIABLE) - line 21

FUNCTIONS (6):
  [✓] getRecord (FUNCTION) - line 26      ← has JSDoc — REPLACE candidate
  [ ] loadRecords (FUNCTION) - line 34    ← bare — INSERT
  [✓] saveRecord (FUNCTION) - line 46    ← has JSDoc — REPLACE candidate
  [ ] buildQuery (FUNCTION) - line 53    ← bare — INSERT
  [ ] getDataSet (FUNCTION) - line 57    ← bare — INSERT
  [ ] countRecords (FUNCTION) - line 63  ← bare — INSERT
```

### Step 2 — Document all

**Prompt:**
```
Document all functions in dataUtils. Improve the existing ones if incomplete, add new docs for the bare ones.
```

**Expected AI workflow:**
1. `getCodeChunk("dataUtils")` — 86 lines, single chunk
2. `getDocumentationForIdentifiers(["databaseManager.getFoundSet", "databaseManager.saveData", "databaseManager.createSelect", "databaseManager.getDataSetByQuery"], "dataUtils.js")`
3. Generates 6 items:
   - `getRecord` → REPLACE (lines of existing `/**` to `*/`)
   - `loadRecords` → INSERT (no existing JSDoc)
   - `saveRecord` → REPLACE (improve existing — already documented but check completeness)
   - `buildQuery` → INSERT
   - `getDataSet` → INSERT
   - `countRecords` → INSERT
4. `applyDocumentations("dataUtils.js", <hash>, [6 items])`

**Expected JSDoc for `loadRecords` (INSERT):**
```javascript
/**
 * Loads records from the specified datasource into a new foundset.
 * Optionally filters by a QBSelect query.
 *
 * @param {String} datasource - The datasource string (e.g. 'db:/example_data/customers')
 * @param {QBSelect} [query] - Optional query to filter records; loads all if null
 * @return {JSFoundSet} The loaded foundset, or null if datasource is invalid
 */
function loadRecords(datasource, query) {
```

**Expected JSDoc for `buildQuery` (INSERT):**
```javascript
/**
 * Creates a new QBSelect query builder for the specified datasource.
 *
 * @param {String} datasource - The datasource string to build a query for
 * @return {QBSelect} A new query builder instance
 */
function buildQuery(datasource) {
```

**Success criteria:**
- ✅ 2 REPLACE items: `startSentence="/**"`, `endSentence="*/"`
- ✅ 4 INSERT items: `startSentence=""`, `endSentence=""`
- ✅ All 6 in single `applyDocumentations()` call
- ✅ Items applied bottom-to-top (line 63 first, line 26 last)
- ✅ Servoy types (`JSFoundSet`, `JSRecord`, `JSDataSet`, `QBSelect`) documented

---

## TEST 4.4 — globals.js: REPLACE on Incomplete JSDoc

**Purpose:** Several variables have minimal JSDoc (no description). Several functions are completely bare. Tests both modes in the most important scope file.

### Prompt:
```
Generate documentation for all undocumented or incomplete items in globals
```

**Expected AI workflow:**
1. `analyzeFileStructure("globals")` → identifies mix of documented/bare
2. `getCodeChunk("globals", chunkNumber=0)` — 154 lines fits in single chunk
3. `getDocumentationForIdentifiers(["application.showForm", "security.getUserName", "plugins.dialogs.showInfoDialog"], "globals.js")`
4. Generates items for:
   - `isGridConfigured` → REPLACE (has minimal JSDoc — `@private @type {Boolean}` only)
   - `activeFoundset` → REPLACE (has `@type {JSFoundSet}` only — improve)
   - `currentUserName` → REPLACE (has `@type {String}` only — improve)
   - `clearState` → INSERT (bare function)
   - `isInitialized` → INSERT (bare function)
   - `setInitialized` → INSERT (bare function)

**Expected JSDoc for `clearState` (INSERT):**
```javascript
/**
 * Resets all global shared state variables to their initial values.
 * Should be called when navigating back to the main navigation form.
 *
 * @public
 */
function clearState() {
```

**Expected JSDoc for `setInitialized` (INSERT):**
```javascript
/**
 * Sets the solution initialized flag.
 *
 * @public
 * @param {Boolean} value - True to mark the solution as initialized
 */
function setInitialized(value) {
```

**Expected JSDoc for `activeFoundset` variable (REPLACE):**
```javascript
/**
 * The currently active foundset used across forms.
 *
 * @type {JSFoundSet}
 */
var activeFoundset = null;
```

**Success criteria:**
- ✅ Both INSERT and REPLACE modes used in same call
- ✅ Existing `@properties` UUIDs on variables untouched
- ✅ `onSolutionOpen` and `onSolutionClose` (already well-documented) left alone
- ✅ `showForm` and `showMessage` (already well-documented) left alone

---

## TEST 4.5 — customerList.js: Partial Documentation

**Purpose:** `onLoad` and `onSearchAction` have JSDoc, 4 functions are bare. Tests selective documentation of only the bare ones.

### Prompt:
```
Please add JSDoc to all undocumented functions in customerList
```

**Expected AI workflow:**
1. `analyzeFileStructure("customerList")` → reports 2 documented, 4 bare
2. `getCodeChunk("customerList")` — 59 lines, single chunk
3. `getDocumentationForIdentifiers(["databaseManager.createSelect"], "customerList.js")`
4. Generates 4 INSERT items only (leaves `onLoad` and `onSearchAction` untouched)

**Expected JSDoc for `onRecordSelection` (INSERT):**
```javascript
/**
 * Handles the record selection event. Updates the active customer in global scope.
 *
 * @param {JSEvent} event - The event that triggered the selection
 */
function onRecordSelection(event) {
```

**Expected JSDoc for `onActionEdit` (INSERT):**
```javascript
/**
 * Opens the customer edit form for the currently selected customer.
 * Shows a warning dialog if no customer is selected.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onActionEdit(event) {
```

**Success criteria:**
- ✅ Exactly 4 INSERT items (not 6 — documented ones untouched)
- ✅ `onLoad` and `onSearchAction` left unchanged
- ✅ AI explains which functions were already documented

---

## TEST 4.6 — customerEdit.js: Complex Mixed Scenario

**Purpose:** Most complex single-file test. 3 variables (mixed JSDoc state), 8 functions (2 documented, 6 bare). Validates `@return` inference for non-trivial functions.

### Prompt:
```
Document all undocumented items in customerEdit and improve any incomplete ones
```

**Expected AI workflow:**
1. `analyzeFileStructure("customerEdit")` → 8 symbols needing attention
2. `getCodeChunk("customerEdit")` — 89 lines, single chunk
3. `getDocumentationForIdentifiers(["databaseManager.saveData", "databaseManager.rollbackEditedRecords", "databaseManager.hasNewRecords"], "customerEdit.js")`
4. Generates:
   - `validationErrors` → REPLACE (`@type {Array}` only — add description)
   - `originalCompanyName` → REPLACE (`@type {String}` only — add description)
   - `onActionSave` → INSERT
   - `onActionCancel` → INSERT
   - `save` → INSERT (returns Boolean — inferred from `return databaseManager.saveData()`)
   - `validate` → INSERT (returns Array)
   - `updateUI` → INSERT (no return)
   - `onHide` → INSERT (returns Boolean)

**Expected JSDoc for `save` (INSERT):**
```javascript
/**
 * Validates and saves the current record to the database.
 * Shows a validation error dialog if validation fails.
 *
 * @return {Boolean} True if the record was saved successfully, false if validation failed
 */
function save() {
```

**Expected JSDoc for `validate` (INSERT):**
```javascript
/**
 * Validates the current form data and returns a list of error messages.
 * Checks company name, city (required), and phone length.
 *
 * @return {Array} Array of error message strings; empty array if valid
 */
function validate() {
```

**Expected JSDoc for `updateUI` (INSERT):**
```javascript
/**
 * Updates the form title label based on whether the current record is new or existing.
 */
function updateUI() {
```

**Success criteria:**
- ✅ `save()` and `validate()` have correct `@return` types inferred from code
- ✅ `updateUI()` has no `@return` (void function)
- ✅ `onLoad` and `onShow` (already documented) untouched
- ✅ UUID in `isNewRecord`'s `@properties` tag survives unchanged

---

## TEST 4.7 — orderList.js: INSERT with Complex Event Signatures

**Purpose:** Tests multi-parameter event functions (`onCellDoubleClick`, `onFilterQueryCondition`) and QBSelect chain documentation.

### Prompt:
```
Add JSDoc to all undocumented functions in orderList
```

**Expected AI workflow:**
1. `analyzeFileStructure("orderList")` → 1 documented variable, 5 bare functions
2. `getCodeChunk("orderList")` — 53 lines, single chunk
3. `getDocumentationForIdentifiers(["QBSelect", "databaseManager.createSelect"], "orderList.js")`
4. Generates 5 INSERT items

**Expected JSDoc for `onCellDoubleClick` (INSERT):**
```javascript
/**
 * Handles a double-click on a cell in the orders grid.
 * If the clicked column is 'customer', navigates to the customer edit form.
 *
 * @param {Number} foundsetindex - The foundset index of the clicked row
 * @param {Number} columnindex - The index of the clicked column
 * @param {JSRecord} record - The record for the clicked row
 * @param {JSEvent} event - The event that triggered the action
 */
function onCellDoubleClick(foundsetindex, columnindex, record, event) {
```

**Expected JSDoc for `onFilterQueryCondition` (INSERT):**
```javascript
/**
 * Custom filter query condition handler for the orderStatus column.
 * Builds OR conditions for 'new', 'planned', and 'completed' status values.
 *
 * @param {QBSelect} query - The query builder to add conditions to
 * @param {String} dataprovider - The dataprovider column being filtered
 * @param {String} operator - The filter operator
 * @param {Array} values - The filter values to apply
 * @param {Object} filter - The filter object
 * @return {Boolean} False if the condition was handled, true to use default behavior
 */
function onFilterQueryCondition(query, dataprovider, operator, values, filter) {
```

**Success criteria:**
- ✅ Multi-parameter functions get all `@param` tags
- ✅ `onFilterQueryCondition` correctly returns `{Boolean}`
- ✅ `QBSelect` type used in `@param` tag for `query` parameter

---

## TEST 4.8 — dashboard.js: INSERT with Cross-Scope JSDataSet

**Purpose:** Tests functions that call multiple scopes (`scopes.dataUtils`, `scopes.utils`) and use `JSDataSet`.

### Prompt:
```
Generate documentation for all undocumented functions in dashboard
```

**Expected AI workflow:**
1. `analyzeFileStructure("dashboard")` → 3 documented variables, 7 bare functions
2. `getCodeChunk("dashboard")` — 60 lines, single chunk
3. `getDocumentationForIdentifiers(["JSDataSet.getMaxRowIndex"], "dashboard.js")`
4. Generates 7 INSERT items

**Expected JSDoc for `refreshStats` (INSERT):**
```javascript
/**
 * Refreshes the dashboard statistics by querying customer and order counts.
 * Updates totalCustomers, totalOrders, and lastRefreshed.
 */
function refreshStats() {
```

**Expected JSDoc for `formatSummary` (INSERT):**
```javascript
/**
 * Formats a numeric value with a label for display in the dashboard summary.
 *
 * @param {Number} value - The numeric value to format
 * @param {String} label - The label prefix for the formatted string
 * @return {String} Formatted summary string (e.g. 'Customers: 42')
 */
function formatSummary(value, label) {
```

**Expected JSDoc for `onActionRefresh` (INSERT):**
```javascript
/**
 * Handles the refresh button action. Refreshes stats and shows a confirmation message.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onActionRefresh(event) {
```

**Success criteria:**
- ✅ 7 INSERT items in one call
- ✅ `refreshStats` correctly documented as void (no `@return`)
- ✅ `formatSummary` returns `{String}`
- ✅ `lastRefreshed` date variable (documented as `@type {Date}`) untouched

---

## TEST 4.9 — Batch: Two Files in One Session Turn

**Purpose:** Tests the AI's ability to document two files sequentially in a single conversation turn, maintaining context between them.

### Prompt:
```
In one go, please generate JSDoc for all bare functions in utils and mainNav
```

**Expected AI workflow:**
1. `analyzeFileStructure("utils")` + `analyzeFileStructure("mainNav")` — surveys both
2. `getCodeChunk("utils")` → generates 6 INSERT items → `applyDocumentations("utils.js", ...)`
3. `getCodeChunk("mainNav")` → generates 6 INSERT items → `applyDocumentations("mainNav.js", ...)`
4. Single summary: "Applied JSDoc to 6 functions in utils.js and 6 functions in mainNav.js"

**Success criteria:**
- ✅ Two separate `applyDocumentations()` calls (one per file)
- ✅ Both files appear in "Modified files" panel
- ✅ Total 12 JSDoc blocks inserted
- ✅ Summary covers both files in one message
- ✅ Memory is not confused between the two files

---

## TEST 4.10 — Read-Back Verification

**Purpose:** After completing Tests 4.1–4.9, verify all JSDoc was written correctly by reading back the files.

### Prompt:
```
Analyze the structure of utils, mainNav, dataUtils, customerList and verify the documentation coverage is now complete
```

**Expected output pattern (per file):**
```
FILE: /svyPilotTest/utils.js
TOTAL SYMBOLS: 8
DOCUMENTED: 8 (100%)
UNDOCUMENTED: 0
```

**Success criteria:**
- ✅ All 8 files show 100% or near-100% documentation coverage
- ✅ `analyzeFileStructure()` correctly reports `[✓]` for newly documented symbols
- ✅ No regressions (previously documented items still show `[✓]`)

---

---

## TEST 4.11 — Chunk Size SMALL: Targeted Single Symbol

**Purpose:** Verifies SMALL (50 lines) chunk size works in TARGETED mode. The returned window must fit a single short function without spilling into adjacent symbols.

### Prompt:
```
Read only the buildQuery function from dataUtils using a small chunk size
```

**Expected AI call:**
```
getCodeChunk("dataUtils", symbolName="buildQuery", chunkSize="SMALL")
```

**Expected output header:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/dataUtils.js
LINES: <start>-<end>          ← window ≤ 50 lines
CHUNK SIZE: 50 lines
CHUNK: X of Y                 ← Y is larger than with LARGE (more chunks for same file)
```

**Expected content:** Only `buildQuery` (3 lines) visible inside the 50-line window — neighboring functions may or may not appear depending on position, but the window must not exceed 50 lines.

**Success criteria:**
- ✅ `CHUNK SIZE: 50 lines` in output header
- ✅ `(endLine - startLine + 1) ≤ 50`
- ✅ `buildQuery` body is present in the returned content
- ✅ Total chunk count for this file is `ceil(86 / 50)` = **2** (vs 1 with LARGE)

---

## TEST 4.12 — Chunk Size MEDIUM: Sequential Multi-Function Read

**Purpose:** Verifies MEDIUM (100 lines) chunk size in SEQUENTIAL mode. `customerEdit.js` is 89 lines — must fit in a single MEDIUM chunk (chunk 0, last chunk).

### Prompt:
```
Read customerEdit chunk 0 with medium chunk size
```

**Expected AI call:**
```
getCodeChunk("customerEdit", chunkNumber=0, chunkSize="MEDIUM")
```

**Expected output header:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/customerEdit.js
LINES: 0-88
CHUNK SIZE: 100 lines
CHUNK: 1 of 1
(LAST CHUNK)
```

**Expected content:** All 89 lines of `customerEdit.js` — entire file in one MEDIUM chunk.

**Success criteria:**
- ✅ `CHUNK SIZE: 100 lines` in output header
- ✅ `CHUNK: 1 of 1` — file fits in single chunk at MEDIUM size
- ✅ `(LAST CHUNK)` marker present
- ✅ All functions visible: `onLoad`, `onShow`, `onActionSave`, `onActionCancel`, `save`, `validate`, `updateUI`, `onHide`

### Contrast test — same file with SMALL:

**Prompt:**
```
Now read customerEdit chunk 0 with small chunk size
```

**Expected output header:**
```
CHUNK SIZE: 50 lines
CHUNK: 1 of 2          ← same file now needs 2 chunks
```

**Success criteria:**
- ✅ `CHUNK: 1 of 2` — file now spans two SMALL chunks
- ✅ Content ends at line 49

---

## TEST 4.13 — Chunk Size LARGE: Full File in One Pass

**Purpose:** Verifies LARGE (200 lines) is the correct choice for full-file exploration. `globals.js` is 154 lines — must fit entirely in chunk 0 at LARGE size.

### Prompt:
```
Read the entire globals file in one chunk using large chunk size
```

**Expected AI call:**
```
getCodeChunk("globals", chunkNumber=0, chunkSize="LARGE")
```

**Expected output header:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/globals.js
LINES: 0-153
CHUNK SIZE: 200 lines
CHUNK: 1 of 1
(LAST CHUNK)
```

**Success criteria:**
- ✅ `CHUNK SIZE: 200 lines` in output header
- ✅ `CHUNK: 1 of 1` — entire 154-line file in one read
- ✅ `(LAST CHUNK)` marker present
- ✅ All symbols from `APP_VERSION` through `setInitialized` visible

### Contrast test — same file with SMALL:

**Prompt:**
```
Read globals chunk 0 with small chunk size, then chunk 1, then chunk 2
```

**Expected:**
- Chunk 0: lines 0–49, `CHUNK: 1 of 4`
- Chunk 1: lines 50–99, `CHUNK: 2 of 4`
- Chunk 2: lines 100–149, `CHUNK: 3 of 4`
- Chunk 3: lines 150–153, `CHUNK: 4 of 4`, `(LAST CHUNK)`

**Success criteria:**
- ✅ `ceil(154 / 50)` = **4** total chunks reported
- ✅ Each chunk boundary is exactly 50 lines (except the last)
- ✅ Lines are contiguous — no gaps or overlaps between chunks

---

## TEST 4.14 — AI Chunk Size Decision

**Purpose:** The AI must autonomously select the appropriate chunk size based on the task. No chunk size specified in the prompt — AI decides.

### Sub-test A: Single known symbol → expect SMALL

**Prompt:**
```
Show me just the onFilterQueryCondition function from orderList
```

**Expected AI call:**
```
getCodeChunk("orderList", symbolName="onFilterQueryCondition", chunkSize="SMALL")
```

**Success criteria:**
- ✅ AI picks `SMALL` — it knows exactly which symbol it wants
- ✅ Output window ≤ 50 lines
- ✅ `onFilterQueryCondition` body fully visible

---

### Sub-test B: "Show me the structure of a function with context" → expect MEDIUM

**Prompt:**
```
Show me the save function in customerEdit with some surrounding context
```

**Expected AI call:**
```
getCodeChunk("customerEdit", symbolName="save", chunkSize="MEDIUM")
```

**Success criteria:**
- ✅ AI picks `MEDIUM` — wants the symbol plus context
- ✅ Output window ≤ 100 lines
- ✅ `save` and at least one neighbouring function visible

---

### Sub-test C: Explore entire unknown file → expect LARGE

**Prompt:**
```
I need to understand the full content of dataUtils before documenting it
```

**Expected AI call:**
```
getCodeChunk("dataUtils", chunkNumber=0, chunkSize="LARGE")
```

**Success criteria:**
- ✅ AI picks `LARGE` — full-file exploration before writing
- ✅ `CHUNK: 1 of 1` — entire 86-line file in one read
- ✅ AI does NOT call `getCodeChunk` again for this file (single chunk was enough)

---

## Expected Results Summary

| Test | File | Items Applied | Mode | Key Validation |
|------|------|---------------|------|----------------|
| 4.1 | utils | 6 INSERT | Pure INSERT | Standard JS types |
| 4.2 | mainNav | 6 INSERT | Pure INSERT | JSEvent lookup |
| 4.3 | dataUtils | 4 INSERT + 2 REPLACE | Mixed | JSFoundSet, QBSelect |
| 4.4 | globals | 3 INSERT + 3 REPLACE | Mixed | UUID preservation |
| 4.5 | customerList | 4 INSERT | Selective | Skips documented |
| 4.6 | customerEdit | 6 INSERT + 2 REPLACE | Complex mixed | @return inference |
| 4.7 | orderList | 5 INSERT | Multi-param | QBSelect, JSEvent |
| 4.8 | dashboard | 7 INSERT | INSERT | JSDataSet, cross-scope |
| 4.9 | utils + mainNav | 12 INSERT total | Batch | Two-file single turn |
| 4.10 | All | Read-back | Verification | 100% coverage check |
| 4.11 | dataUtils | SMALL targeted read | `buildQuery` symbol | ≤50 lines, 2 total chunks |
| 4.12 | customerEdit | MEDIUM sequential | Chunk 0 of 1 | 89 lines fits in 100 |
| 4.13 | globals | LARGE sequential | Chunk 0 of 1 | 154 lines fits in 200 |
| 4.14 | orderList / customerEdit / dataUtils | AI-chosen | Sub-tests A/B/C | SMALL / MEDIUM / LARGE |

---

## Known Edge Cases to Watch

1. **`onHide` return value** — Must produce `@return {Boolean}`. The function returns `true` explicitly.
2. **`onFilterQueryCondition` parameters** — 5 parameters, complex types. AI must not truncate the `@param` list.
3. **`save()` in customerEdit** — Bare function name, no event parameter. `@return {Boolean}` must be inferred from `return databaseManager.saveData()`.
4. **UUID lines in variables** — `@properties={typeid:...,uuid:"..."}` lines must survive REPLACE operations unchanged.
5. **Content hash mismatches** — If a file was modified externally between `analyzeFileStructure()` and `applyDocumentations()`, the hash check must reject the write.
6. **Chunk count changes with size** — `analyzeFileStructure()` does not report chunk counts; only `getCodeChunk()` output headers do. Verify `ceil(fileLines / chunkSizeLines)` matches reported total chunks.
7. **TARGETED mode centering** — With SMALL size, the centering window is `symbolLine ± 25`. For a symbol at line 0 the window clamps to 0–49. Verify no negative start lines appear.
8. **SEQUENTIAL chunk number reuse** — Chunk number 0 with SMALL returns different lines than chunk 0 with LARGE. Switching chunk size mid-session resets the effective page boundaries.

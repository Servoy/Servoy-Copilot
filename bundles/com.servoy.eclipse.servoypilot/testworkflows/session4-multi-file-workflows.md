# SESSION 4: Multi-File Documentation Workflows — Test Workflows

**Date:** April 1, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** `DocumentationTools` + `CodeAnalysisTools` (Documentation Assistant)

---

## Overview

Tests the Documentation Assistant's ability to survey, read, and improve JSDoc across all 8 files of the `svyPilotTest` solution.

**Key principle:** In Servoy, every file-level declaration always has an auto-generated JSDoc stub with `@properties={typeid:...,uuid:"..."}`. The AI must:
1. Call `analyzeFileStructure()` → get symbol map (names, line numbers, param names)
2. Call `getCodeChunk()` → read actual code to classify JSDoc quality
3. Use **REPLACE mode only** (startSentence/endSentence match the existing `/** ... */` block)
4. Never INSERT — doing so would create a duplicate `/**` block

**JSDoc quality classification the AI must apply after reading code:**

| Quality | Detection | Action |
|---------|-----------|--------|
| **TODO stub** | Contains `TODO generated` | REPLACE with real docs |
| **Minimal** | Only `@type` or bare `@properties`, no description | REPLACE with full docs |
| **Complete** | Has description + typed params + return where applicable | Skip |

**Tools exercised:**
- `analyzeFileStructure(pathOrName)` — symbol map
- `getCodeChunk(pathOrName, symbolName?, chunkNumber?, chunkSize?)` — read code
- `getDocumentationForIdentifiers(identifiers[], filePath?)` — Servoy API docs
- `applyDocumentations(filePath, items[])` — write JSDoc (REPLACE only in practice)

---

## Test Suite Structure

| Test | File | Lines | LARGE chunks | Items to replace | Key types |
|------|------|-------|-------------|-----------------|-----------|
| 4.1 | `utils` | 96 | 1 | 6 (all function stubs) | Standard JS |
| 4.2 | `mainNav` | 63 | 1 | 7 (all stubs) | `JSEvent` |
| 4.3 | `dataUtils` | 123 | 1 | 6 (stubs + minimal vars) | `JSFoundSet`, `JSRecord`, `JSDataSet`, `QBSelect` |
| 4.4 | `globals` | 203 | 2 | 8 (stubs + minimal vars) | `JSRecord`, `JSFoundSet` |
| 4.5 | `customerList` | 91 | 1 | 6 (stubs + minimal var) | `JSRecord`, `JSEvent` |
| 4.6 | `customerEdit` | 126 | 1 | 8 (stubs + minimal vars) | `JSRecord`, `Boolean`, `Array` |
| 4.7 | `orderList` | 93 | 1 | 6 (stubs + minimal var) | `JSRecord`, `QBSelect`, `JSEvent` |
| 4.8 | `dashboard` | 107 | 1 | 9 (stubs + 2 minimal vars) | `JSDataSet`, `Number`, `Date` |
| 4.9 | Batch: utils + mainNav | — | — | 13 total | Mixed |
| 4.10 | Read-back verification | Post-write | — | Confirm no TODO remains | All |
| 4.11 | Chunk size SMALL | `dataUtils` | — | Targeted single symbol | `QBSelect` |
| 4.12 | Chunk size MEDIUM | `customerEdit` | — | Multi-function read | `Boolean` |
| 4.13 | Chunk size LARGE | `globals` | 2 | Full file, 2 chunks | `JSRecord` |
| 4.14 | AI chunk size autonomy | `orderList` | — | AI picks size | Complex function |

---

## Prerequisites

- [ ] `svyPilotTest` solution open in Servoy Developer
- [ ] All 8 JS files match `js-content/` reference state (TODO stubs present)
- [ ] Documentation Assistant active in ServoyPilot chat

---

## TEST 4.1 — utils.js: 6 TODO stubs (standard JS types only)

**Purpose:** Simplest replace test. 2 variables already have complete JSDoc. 6 functions have TODO stubs. No Servoy API lookup needed.

### Step 1 — Survey

**Prompt:**
```
Analyze the file structure of utils
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/scopes/utils.js
TOTAL SYMBOLS: 8

=== SYMBOLS ===

- DEFAULT_DATE_FORMAT (VARIABLE) at line 1
- DEFAULT_CURRENCY_SYMBOL (VARIABLE) at line 11
- formatDate(date, format) (FUNCTION) at line 22
- formatCurrency(value, symbol) (FUNCTION) at line 34
- isEmptyString(value) (FUNCTION) at line 46
- truncateText(text, maxLength) (FUNCTION) at line 56
- parseNumber(value) (FUNCTION) at line 67
- buildErrorMessage(errors) (FUNCTION) at line 79
```

**Success criteria:**
- ✅ 8 symbols with param names
- ✅ No JSDoc status labels

---

### Step 2 — Read and improve

**Prompt:**
```
Please improve the JSDoc documentation for utils
```

**Expected AI workflow:**
1. `getCodeChunk("utils", chunkNumber=1, chunkSize="LARGE")` — 96 lines, 1 chunk
2. Reads code — finds 2 complete variables, 6 TODO stubs
3. Skips `DEFAULT_DATE_FORMAT` and `DEFAULT_CURRENCY_SYMBOL` (complete)
4. Skips `getDocumentationForIdentifiers()` — standard JS types only
5. `applyDocumentations("/svyPilotTest/scopes/utils.js", [6 REPLACE items])`

**Expected JSDoc for `formatDate` (REPLACE):**
```javascript
/**
 * Formats a date value using the specified format string.
 *
 * @param {Date} date - The date to format
 * @param {String} [format] - Optional format string; defaults to DEFAULT_DATE_FORMAT
 * @return {String} Formatted date string, or empty string if date is null
 *
 * @properties={typeid:24,uuid:"A8F5BDA8-D822-41C7-BE87-7727823970BD"}
 */
function formatDate(date, format) {
```

**Expected JSDoc for `buildErrorMessage` (REPLACE):**
```javascript
/**
 * Builds a formatted error message string from an array of error strings.
 *
 * @param {Array} errors - Array of error message strings
 * @return {String} Newline-separated error list, or empty string if no errors
 *
 * @properties={typeid:24,uuid:"0D4080CB-AFA1-4887-A868-5E3A0F8E2180"}
 */
function buildErrorMessage(errors) {
```

**Success criteria:**
- ✅ `analyzeFileStructure()` then `getCodeChunk()` — both called
- ✅ 6 REPLACE items in one `applyDocumentations()` call
- ✅ `DEFAULT_DATE_FORMAT` and `DEFAULT_CURRENCY_SYMBOL` NOT in items
- ✅ All 6 `@properties` UUIDs preserved unchanged
- ✅ File appears in "Modified files" panel

**Pass/Fail:** _______________

---

## TEST 4.2 — mainNav.js: 7 TODO stubs (JSEvent + one @return)

**Prompt:**
```
Improve the documentation for mainNav
```

**Expected AI workflow:**
1. `analyzeFileStructure("mainNav")` → 7 functions, no variables
2. `getCodeChunk("mainNav", chunkNumber=1, chunkSize="LARGE")` → 63 lines, 1 chunk
3. All 7 functions have TODO stubs → 7 REPLACE items
4. `getDocumentationForIdentifiers(["JSEvent"], "mainNav")` — type lookup
5. `applyDocumentations("/svyPilotTest/forms/mainNav.js", [7 REPLACE items])`

**Expected JSDoc for `onHide` (REPLACE — must include @return):**
```javascript
/**
 * Handles the form hide event.
 *
 * @param {JSEvent} event - The event that triggered the action
 * @return {Boolean} Always returns true to allow hiding
 *
 * @properties={typeid:24,uuid:"AFB034D3-141A-447B-A627-010D5B7EDB81"}
 */
function onHide(event) {
```

**Expected JSDoc for `onShow` (REPLACE — two params):**
```javascript
/**
 * Handles the form show event.
 *
 * @param {Boolean} firstShow - True if this is the first time the form is shown
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"62907B92-8132-4D8E-ABD3-8553ACB0C323"}
 */
function onShow(firstShow, event) {
```

**Success criteria:**
- ✅ 7 REPLACE items
- ✅ `onHide` has `@return {Boolean}` (inferred from `return true`)
- ✅ `onShow` has both `{Boolean} firstShow` and `{JSEvent} event`
- ✅ All UUIDs preserved

**Pass/Fail:** _______________

---

## TEST 4.3 — dataUtils.js: Mixed (2 complete, 2 minimal vars, 4 TODO stubs)

**Purpose:** First mixed state test. `getRecord` and `saveRecord` are complete → skip. `lastQueryResult` and `lastRecordCount` are minimal (no description) → REPLACE. 4 functions have TODO stubs → REPLACE.

**Prompt:**
```
Improve the documentation for dataUtils
```

**Expected AI workflow:**
1. `analyzeFileStructure("dataUtils")` → 10 symbols
2. `getCodeChunk("dataUtils", chunkNumber=1, chunkSize="LARGE")` → 123 lines, 1 chunk
3. Classifies:
   - `CUSTOMER_DATASOURCE`, `ORDER_DATASOURCE` → complete, skip
   - `getRecord`, `saveRecord` → complete, skip
   - `lastQueryResult`, `lastRecordCount` → minimal (no description) → REPLACE
   - `loadRecords`, `buildQuery`, `getDataSet`, `countRecords` → TODO stubs → REPLACE
4. `getDocumentationForIdentifiers(["databaseManager.getFoundSet", "databaseManager.createSelect", "databaseManager.getDataSetByQuery", "JSFoundSet", "JSDataSet", "QBSelect"], "dataUtils")`
5. `applyDocumentations("/svyPilotTest/scopes/dataUtils.js", [6 REPLACE items])`

**Expected JSDoc for `lastQueryResult` (REPLACE — add description):**
```javascript
/**
 * Result of the most recent dataset query, or null if no query has been run.
 *
 * @type {JSDataSet}
 *
 * @properties={typeid:35,uuid:"7F2CB942-B81C-4FFE-8833-49F84C00E000",variableType:-4}
 */
var lastQueryResult = null;
```

**Expected JSDoc for `loadRecords` (REPLACE — TODO stub):**
```javascript
/**
 * Loads records from the given datasource, optionally filtered by a query.
 *
 * @param {String} datasource - The datasource path (e.g. 'db:/example_data/customers')
 * @param {QBSelect} [query] - Optional query to filter records; loads all if null
 * @return {JSFoundSet} The loaded foundset, or null if datasource is invalid
 *
 * @properties={typeid:24,uuid:"3261149A-17F4-40A8-8E20-A8F9A3FC552E"}
 */
function loadRecords(datasource, query) {
```

**Success criteria:**
- ✅ `getRecord` and `saveRecord` NOT in items (complete)
- ✅ `CUSTOMER_DATASOURCE` and `ORDER_DATASOURCE` NOT in items (complete)
- ✅ 6 REPLACE items: 2 minimal vars + 4 TODO stubs
- ✅ `loadRecords` has `@return {JSFoundSet}` and `@param {QBSelect}` optional

**Pass/Fail:** _______________

---

## TEST 4.4 — globals.js: Mixed state (203 lines, 2 LARGE chunks)

**Purpose:** Largest file — requires 2 LARGE chunks. Mixed JSDoc quality across 17 symbols.

**Prompt:**
```
Improve the documentation for globals
```

**Expected AI workflow:**
1. `analyzeFileStructure("globals")` → 17 symbols
2. `getCodeChunk("globals", chunkNumber=1, chunkSize="LARGE")` → lines 0–199 (`CHUNK: 1 of 2`)
3. `getCodeChunk("globals", chunkNumber=2, chunkSize="LARGE")` → lines 200–202 (`CHUNK: 2 of 2, LAST CHUNK`)
4. Classifies all 17 symbols after reading both chunks
5. `getDocumentationForIdentifiers(["JSRecord", "JSFoundSet", "RuntimeForm"], "globals")`
6. `applyDocumentations("/svyPilotTest/scopes/globals.js", [8 REPLACE items])`

**Complete symbols — must NOT be replaced:**
- `APP_VERSION`, `MAX_RECORDS`, `initialized` (have full descriptions)
- `showForm(form, record)`, `showMessage(message, title)` (have full descriptions + typed params)
- `getCurrentUser()`, `onSolutionClose()`, `getVersion()` (have full descriptions)

**Items to replace:**
- `isGridConfigured` — minimal (no description)
- `activeFoundset` — minimal (no description)
- `currentUserName` — minimal (no description)
- `clearState()` — bare `@properties` only
- `isInitialized()` — bare `@properties` only
- `setInitialized(value)` — TODO stub
- `onSolutionOpen(arg, queryParams)` — TODO stub
- `configGrid()` — bare `@properties` only
- `getMaxRecords()` — bare `@properties` only

**Success criteria:**
- ✅ **2 `getCodeChunk()` calls** — AI reads both chunks before applying
- ✅ Complete symbols untouched
- ✅ 8–9 REPLACE items (count depends on how AI classifies bare `@properties`)
- ✅ All UUIDs preserved

**Pass/Fail:** _______________

---

## TEST 4.5 — customerList.js: Mixed (2 complete, 1 minimal var, 6 stubs)

**Prompt:**
```
Improve the documentation for customerList
```

**Expected AI workflow:**
1. `analyzeFileStructure("customerList")` → 9 symbols
2. `getCodeChunk("customerList", chunkNumber=1, chunkSize="LARGE")` → 91 lines, 1 chunk
3. `onLoad` and `onSearchAction` → complete, skip
4. `selectedCustomer` → complete (has description), skip
5. `filterText` → minimal (no description), REPLACE
6. `onRecordSelection`, `onActionEdit`, `onActionNewRecord`, `onActionBack` → TODO stubs, REPLACE
7. `applyDocumentations("/svyPilotTest/forms/customerList.js", [5 REPLACE items])`

**Expected JSDoc for `filterText` (REPLACE — add description):**
```javascript
/**
 * Current search filter text entered by the user.
 *
 * @type {String}
 *
 * @properties={typeid:35,uuid:"05318336-7300-462D-9360-AAA7585DA399"}
 */
var filterText = null;
```

**Success criteria:**
- ✅ `onLoad` and `onSearchAction` skipped (complete)
- ✅ `selectedCustomer` skipped (complete)
- ✅ 5 REPLACE items
- ✅ `onActionEdit` correctly infers no `@return` (void function)

**Pass/Fail:** _______________

---

## TEST 4.6 — customerEdit.js: Mixed (2 complete funcs, 3 minimal vars, 6 stubs)

**Prompt:**
```
Improve the documentation for customerEdit
```

**Expected AI workflow:**
1. `analyzeFileStructure("customerEdit")` → 11 symbols
2. `getCodeChunk("customerEdit", chunkNumber=1, chunkSize="LARGE")` → 126 lines, 1 chunk
3. Classifies:
   - `isNewRecord` → complete (has description + `@type`) → skip
   - `onLoad`, `onShow` → complete → skip
   - `validationErrors`, `originalCompanyName` → minimal (no description) → REPLACE
   - `onActionSave`, `onActionCancel`, `onHide` → TODO stubs → REPLACE
   - `save()`, `validate()`, `updateUI()` → bare `@properties` → REPLACE
4. `getDocumentationForIdentifiers(["databaseManager.hasNewRecords", "databaseManager.saveData", "databaseManager.rollbackEditedRecords", "JSEvent"])`
5. `applyDocumentations("/svyPilotTest/forms/customerEdit.js", [8 REPLACE items])`

**Expected JSDoc for `save()` (REPLACE — return inferred from code):**
```javascript
/**
 * Validates and saves the current record to the database.
 *
 * @return {Boolean} True if save succeeded, false if validation failed
 *
 * @properties={typeid:24,uuid:"71AF6837-5BB5-4311-8F54-8A07CE0F5B5F"}
 */
function save() {
```

**Expected JSDoc for `validate()` (REPLACE — return Array inferred):**
```javascript
/**
 * Validates the current form data.
 *
 * @return {Array} Array of error message strings; empty array if valid
 *
 * @properties={typeid:24,uuid:"B6DD06D5-47BF-4499-97AE-D987665225C4"}
 */
function validate() {
```

**Success criteria:**
- ✅ `isNewRecord`, `onLoad`, `onShow` NOT in items
- ✅ 8 REPLACE items
- ✅ `save()` has `@return {Boolean}`
- ✅ `validate()` has `@return {Array}`
- ✅ `updateUI()` has no `@return` (void)
- ✅ `onHide()` has `@return {Boolean}` (returns `true`)

**Pass/Fail:** _______________

---

## TEST 4.7 — orderList.js: Mixed (1 minimal var, 5 TODO stubs)

**Prompt:**
```
Improve the documentation for orderList
```

**Expected AI workflow:**
1. `analyzeFileStructure("orderList")` → 6 symbols
2. `getCodeChunk("orderList", chunkNumber=1, chunkSize="LARGE")` → 93 lines, 1 chunk
3. `currentCustomer` → minimal (no description) → REPLACE
4. All 5 functions → TODO stubs → REPLACE
5. `getDocumentationForIdentifiers(["JSEvent", "JSRecord", "QBSelect"], "orderList")`
6. `applyDocumentations("/svyPilotTest/forms/orderList.js", [6 REPLACE items])`

**Expected JSDoc for `onFilterQueryCondition` (REPLACE — 5 params + @return):**
```javascript
/**
 * Handles the filter query condition for the order list grid.
 * Applies custom SQL conditions for the orderStatus filter column.
 *
 * @param {QBSelect} query - The query builder object to modify
 * @param {String} dataprovider - The column being filtered
 * @param {String} operator - The filter operator
 * @param {Array} values - The filter values
 * @param {Object} filter - The filter configuration object
 * @return {Boolean} False if the condition was handled; true to apply default filter
 *
 * @properties={typeid:24,uuid:"63D8459A-939F-4243-B55E-BEF656EC93AE"}
 */
function onFilterQueryCondition(query, dataprovider, operator, values, filter) {
```

**Success criteria:**
- ✅ `onFilterQueryCondition` has all 5 `@param` tags
- ✅ `@return {Boolean}` correctly inferred from code
- ✅ `onCellDoubleClick` has all 4 `@param` tags
- ✅ 6 REPLACE items total

**Pass/Fail:** _______________

---

## TEST 4.8 — dashboard.js: Mixed (1 complete var, 2 minimal vars, 7 stubs)

**Prompt:**
```
Improve the documentation for dashboard
```

**Expected AI workflow:**
1. `analyzeFileStructure("dashboard")` → 10 symbols
2. `getCodeChunk("dashboard", chunkNumber=1, chunkSize="LARGE")` → 107 lines, 1 chunk
3. `totalCustomers` → complete (has description + `@type`) → skip
4. `totalOrders`, `lastRefreshed` → minimal (no description) → REPLACE
5. All 7 functions → TODO stubs or bare `@properties` → REPLACE
6. `getDocumentationForIdentifiers(["JSDataSet"], "dashboard")`
7. `applyDocumentations("/svyPilotTest/forms/dashboard.js", [9 REPLACE items])`

**Expected JSDoc for `refreshStats()` (REPLACE — void, no @return):**
```javascript
/**
 * Refreshes dashboard statistics by querying customer and order counts.
 * Updates totalCustomers, totalOrders, and lastRefreshed.
 *
 * @properties={typeid:24,uuid:"C3D977C7-25DC-4348-99B3-DCDC087A0BBF"}
 */
function refreshStats() {
```

**Success criteria:**
- ✅ `totalCustomers` NOT in items (complete)
- ✅ 9 REPLACE items
- ✅ `refreshStats()` has no `@return` (void function)
- ✅ `formatSummary(value, label)` returns `{String}`

**Pass/Fail:** _______________

---

## TEST 4.9 — Batch: utils + mainNav in one session

**Purpose:** Two files documented in a single conversation without losing context.

**Prompt:**
```
Please improve the documentation for both utils and mainNav
```

**Expected AI workflow:**
1. `analyzeFileStructure("utils")` + `getCodeChunk("utils", chunkSize="LARGE")` → 6 REPLACE
2. `applyDocumentations("utils.js", [6 items])`
3. `analyzeFileStructure("mainNav")` + `getCodeChunk("mainNav", chunkSize="LARGE")` → 7 REPLACE
4. `applyDocumentations("mainNav.js", [7 items])`

**Success criteria:**
- ✅ Both files appear in "Modified files" panel
- ✅ 13 total REPLACE items across 2 calls
- ✅ AI does not mix items from the two files
- ✅ File paths correct in each `applyDocumentations()` call

**Pass/Fail:** _______________

---

## TEST 4.10 — Read-back verification (post-write)

**Purpose:** After running 4.1 or 4.2, verify the written JSDoc is correct.

**Prompt (after 4.1):**
```
Re-read utils and confirm all TODO stubs are replaced
```

**Expected AI workflow:**
1. `getCodeChunk("utils", chunkNumber=1, chunkSize="LARGE")` — re-read file
2. Scans for `TODO generated` — finds none
3. Reports: all 6 functions have complete JSDoc

**Success criteria:**
- ✅ No `TODO generated` text found in re-read
- ✅ AI confirms documentation is complete
- ✅ `@properties` UUIDs present and unchanged

**Pass/Fail:** _______________

---

## TEST 4.11 — Chunk size SMALL: targeted single symbol (dataUtils — buildQuery)

**Prompt:**
```
Improve the JSDoc for just the buildQuery function in dataUtils using a small read
```

**Expected AI workflow:**
1. `getCodeChunk("dataUtils", symbolName="buildQuery", chunkSize="SMALL")` → ≤50 lines
2. Reads `buildQuery` and its TODO stub
3. `getDocumentationForIdentifiers(["databaseManager.createSelect", "QBSelect"])`
4. `applyDocumentations("dataUtils.js", [1 REPLACE item])`

**Success criteria:**
- ✅ `chunkSize="SMALL"` used
- ✅ Only 1 REPLACE item (targeted)
- ✅ `@return {QBSelect}` on `buildQuery`

**Pass/Fail:** _______________

---

## TEST 4.12 — Chunk size MEDIUM: read save + validate in customerEdit

**Prompt:**
```
Improve save and validate in customerEdit using medium chunk size
```

**Expected AI workflow:**
1. `getCodeChunk("customerEdit", symbolName="save", chunkSize="MEDIUM")` → ≤100 lines (includes both functions)
2. Reads `save()` and `validate()` with context
3. `applyDocumentations("customerEdit.js", [2 REPLACE items])`

**Success criteria:**
- ✅ `chunkSize="MEDIUM"` used
- ✅ Both `save()` and `validate()` visible in the 100-line window
- ✅ 2 REPLACE items with correct return types

**Pass/Fail:** _______________

---

## TEST 4.13 — Chunk size LARGE: full globals.js (2 chunks required)

**Prompt:**
```
Read all of globals using large chunk size and count how many symbols need documentation improvement
```

**Expected AI workflow:**
1. `getCodeChunk("globals", chunkNumber=1, chunkSize="LARGE")` → `CHUNK: 1 of 2`
2. `getCodeChunk("globals", chunkNumber=2, chunkSize="LARGE")` → `CHUNK: 2 of 2, LAST CHUNK`
3. AI classifies all 17 symbols and reports counts

**Success criteria:**
- ✅ **2 chunk reads** required (203 lines > 200 LARGE limit)
- ✅ AI reads both chunks before reporting
- ✅ AI correctly identifies ~8–9 symbols needing improvement

**Pass/Fail:** _______________

---

## TEST 4.14 — AI autonomous chunk size selection (orderList)

**Prompt:**
```
Improve the docs for onFilterQueryCondition in orderList
```

**Expected AI behavior:**
- Selects `chunkSize="MEDIUM"` or `chunkSize="SMALL"` — not LARGE (targeted request, not full file)
- Uses TARGETED mode: `symbolName="onFilterQueryCondition"`

**Success criteria:**
- ✅ AI does NOT use LARGE for a single-function request
- ✅ TARGETED mode used with symbol name
- ✅ All 5 `@param` tags and `@return {Boolean}` generated

**Pass/Fail:** _______________

---

## Overall Results

| Test | File | Items | Pass/Fail |
|------|------|-------|-----------|
| 4.1 | utils | 6 REPLACE | |
| 4.2 | mainNav | 7 REPLACE | |
| 4.3 | dataUtils | 6 REPLACE | |
| 4.4 | globals | 8–9 REPLACE, 2 chunks | |
| 4.5 | customerList | 5 REPLACE | |
| 4.6 | customerEdit | 8 REPLACE | |
| 4.7 | orderList | 6 REPLACE | |
| 4.8 | dashboard | 9 REPLACE | |
| 4.9 | Batch utils + mainNav | 13 total | |
| 4.10 | Read-back verify (no TODO) | — | |
| 4.11 | SMALL chunk, buildQuery | 1 REPLACE | |
| 4.12 | MEDIUM chunk, save+validate | 2 REPLACE | |
| 4.13 | LARGE chunk, globals 2 reads | — | |
| 4.14 | AI chunk size autonomy | 1 REPLACE | |

**Total:** ___ / 14
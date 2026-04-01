# SESSION 1: File Structure Analysis — Test Workflows

**Date:** April 1, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** `CodeAnalysisTools.analyzeFileStructure()`

---

## Overview

Tests the `analyzeFileStructure()` tool against the 8 real JS files from the `svyPilotTest` solution.

**What the tool returns:**
- Symbol name
- Type: `FUNCTION` or `VARIABLE`
- Line number (1-based)
- For functions: parameter names in signature form — e.g. `formatDate(date, format)`

**What the tool does NOT return** (and why it doesn't matter):
- JSDoc presence/absence — in Servoy every file-level declaration always has an auto-generated `/** ... @properties ... */` stub, so the flag would always be "present" and carry no information
- Parameter types, return types — not available from DLTK for dynamic JavaScript

The AI must always follow `analyzeFileStructure()` with `getCodeChunk()` to read the actual source and JSDoc content.

**Tools tested:** `analyzeFileStructure(pathOrName)`

---

## Servoy Project Structure — Quick Reference

- **Forms:** `forms/<formName>.js` — event handlers, methods, variables
- **Scopes:** `scopes/<scopeName>.js` — utility functions, global variables
- `FilePathResolver` accepts: form name, scope name, partial path, or full workspace path
- All file-level declarations have an auto-generated JSDoc stub with `@properties={typeid:...,uuid:"..."}` — this UUID must never be changed

---

## Test Files (svyPilotTest solution)

All 8 reference files are in `testworkflows/js-content/`:

| File | Type | Lines | Symbols | Notes |
|------|------|-------|---------|-------|
| `scopes/utils.js` | Scope | 96 | 8 | 2 complete vars + 6 TODO-stub functions |
| `scopes/globals.js` | Scope | 203 | 17 | Mixed: some complete, some bare, some TODO |
| `scopes/dataUtils.js` | Scope | 123 | 10 | 2 complete functions + 4 TODO-stub functions + 4 vars |
| `forms/mainNav.js` | Form | 63 | 7 | All 7 functions are TODO stubs, no variables |
| `forms/customerList.js` | Form | 91 | 9 | 2 complete + 5 stubs |
| `forms/customerEdit.js` | Form | 126 | 11 | 2 complete funcs + 3 minimal vars + 6 stubs |
| `forms/orderList.js` | Form | 93 | 6 | 1 minimal var + 5 TODO-stub functions |
| `forms/dashboard.js` | Form | 107 | 10 | 1 complete var + 2 minimal vars + 7 stubs |

---

## Prerequisites

- [ ] `svyPilotTest` solution open in Servoy Developer with all 8 JS files matching `js-content/` reference state
- [ ] Documentation Assistant active in ServoyPilot chat

---

## TEST 1.1 — Scope file: utils.js

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
- ✅ 8 symbols total: 2 variables + 6 functions
- ✅ Functions show parameter names in signature form
- ✅ Variables show no parameters
- ✅ Line numbers match actual file positions
- ✅ No `[JSDOC PRESENT]`/`[JSDOC ABSENT]` labels — those are not in the output

**Pass/Fail:** _______________

---

## TEST 1.2 — Scope file: globals.js (large file, 203 lines)

**Prompt:**
```
Analyze globals
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/scopes/globals.js
TOTAL SYMBOLS: 17

=== SYMBOLS ===

- APP_VERSION (VARIABLE) at line 1
- MAX_RECORDS (VARIABLE) at line 11
- isGridConfigured (VARIABLE) at line 21
- activeCustomer (VARIABLE) at line 27
- activeFoundset (VARIABLE) at line 35
- currentUserName (VARIABLE) at line 42
- initialized (VARIABLE) at line 49
- showForm(form, record) (FUNCTION) at line 58
- showMessage(message, title) (FUNCTION) at line 70
- clearState() (FUNCTION) at line 81
- getCurrentUser() (FUNCTION) at line 91
- isInitialized() (FUNCTION) at line 102
- setInitialized(value) (FUNCTION) at line 109
- onSolutionOpen(arg, queryParams) (FUNCTION) at line 119
- onSolutionClose() (FUNCTION) at line 133
- configGrid() (FUNCTION) at line 140
- getVersion() (FUNCTION) at line 169
- getMaxRecords() (FUNCTION) at line 179
```

> Line numbers are approximate — exact values depend on DLTK offset resolution. The total symbol count and parameter names are the key correctness check.

**Success criteria:**
- ✅ 17 symbols (7 variables + 10+ functions — exact count depends on DLTK resolution of inline declarations)
- ✅ Multi-parameter functions show all param names: `showForm(form, record)`, `onSolutionOpen(arg, queryParams)`
- ✅ Zero-parameter functions show empty parens: `clearState()`, `getVersion()`
- ✅ Variables show no parens

**Pass/Fail:** _______________

---

## TEST 1.3 — Scope file: dataUtils.js

**Prompt:**
```
Analyze dataUtils
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/scopes/dataUtils.js
TOTAL SYMBOLS: 10

=== SYMBOLS ===

- CUSTOMER_DATASOURCE (VARIABLE) at line 1
- ORDER_DATASOURCE (VARIABLE) at line 11
- lastQueryResult (VARIABLE) at line 21
- lastRecordCount (VARIABLE) at line 27
- getRecord(foundset, index) (FUNCTION) at line 33
- loadRecords(datasource, query) (FUNCTION) at line 46
- saveRecord(record) (FUNCTION) at line 58
- buildQuery(datasource) (FUNCTION) at line 70
- getDataSet(query) (FUNCTION) at line 78
- countRecords(foundset) (FUNCTION) at line 89
```

**Success criteria:**
- ✅ 10 symbols: 4 variables + 6 functions
- ✅ `getRecord(foundset, index)` shows both params
- ✅ `saveRecord(record)` shows single param

**Pass/Fail:** _______________

---

## TEST 1.4 — Form file: mainNav.js

**Prompt:**
```
Analyze mainNav
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/forms/mainNav.js
TOTAL SYMBOLS: 7

=== SYMBOLS ===

- onLoad(event) (FUNCTION) at line 6
- onShow(firstShow, event) (FUNCTION) at line 14
- onActionCustomers(event) (FUNCTION) at line 23
- onActionOrders(event) (FUNCTION) at line 31
- onActionDashboard(event) (FUNCTION) at line 39
- onHide(event) (FUNCTION) at line 47
```

> Note: `onShow` takes 2 parameters — verify both are shown.

**Success criteria:**
- ✅ 6 or 7 symbols (DLTK may include an implicit form variable)
- ✅ `onShow(firstShow, event)` — two params visible
- ✅ All standard Servoy form event handlers present

**Pass/Fail:** _______________

---

## TEST 1.5 — Form file: customerEdit.js (complex mixed state)

**Prompt:**
```
Analyze customerEdit
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/forms/customerEdit.js
TOTAL SYMBOLS: 11

=== SYMBOLS ===

- isNewRecord (VARIABLE) at line 1
- validationErrors (VARIABLE) at line 9
- originalCompanyName (VARIABLE) at line 15
- onLoad(event) (FUNCTION) at line 21
- onShow(firstShow, event) (FUNCTION) at line 33
- onActionSave(event) (FUNCTION) at line 43
- onActionCancel(event) (FUNCTION) at line 51
- save() (FUNCTION) at line 61
- validate() (FUNCTION) at line 73
- updateUI() (FUNCTION) at line 88
- onHide(event) (FUNCTION) at line 97
```

**Success criteria:**
- ✅ 11 symbols: 3 variables + 8 functions
- ✅ Zero-parameter functions shown with empty parens: `save()`, `validate()`, `updateUI()`
- ✅ Mixed-param functions: `onShow(firstShow, event)` vs `onLoad(event)`

**Pass/Fail:** _______________

---

## TEST 1.6 — Form file: orderList.js (complex filter handler)

**Prompt:**
```
Analyze orderList
```

**Expected output:**
```
=== FILE STRUCTURE ===

FILE: /svyPilotTest/forms/orderList.js
TOTAL SYMBOLS: 6

=== SYMBOLS ===

- currentCustomer (VARIABLE) at line 1
- onShow(firstShow, event) (FUNCTION) at line 7
- onRecordSelection(event) (FUNCTION) at line 26
- onCellDoubleClick(foundsetindex, columnindex, record, event) (FUNCTION) at line 34
- onFilterQueryCondition(query, dataprovider, operator, values, filter) (FUNCTION) at line 49
- onActionBack(event) (FUNCTION) at line 77
```

**Success criteria:**
- ✅ `onCellDoubleClick` shows all 4 params
- ✅ `onFilterQueryCondition` shows all 5 params — this is the key correctness check for multi-param functions

**Pass/Fail:** _______________

---

## TEST 1.7 — Path resolution variants

**Objective:** Verify `FilePathResolver` accepts various input formats.

**Test cases:**

| Prompt | Expected resolution |
|--------|---------------------|
| `Analyze utils` | → `scopes/utils.js` via DLTK |
| `Analyze mainNav` | → `forms/mainNav.js` |
| `Analyze mainNav.js` | → `forms/mainNav.js` (strips extension) |
| `Analyze forms/mainNav.js` | → `forms/mainNav.js` (partial path) |
| `Analyze /svyPilotTest/forms/mainNav.js` | → direct resolution |
| `Analyze nonExistentForm` | → helpful error, no stack trace |

**Success criteria:**
- ✅ All valid formats resolve without error
- ✅ Non-existent name returns friendly error with solution name and usage tips
- ✅ No "I need the full workspace-relative path" responses

**Pass/Fail:** _______________

---

## TEST 1.8 — AI uses analyzeFileStructure naturally in conversation

**Prompt:**
```
I want to improve the documentation in customerList. What symbols does it have?
```

**Expected AI behavior:**
1. Calls `analyzeFileStructure("customerList")`
2. Reports: 9 symbols — 2 variables + 7 functions
3. Notes it needs to read the code next to assess JSDoc quality

**Success criteria:**
- ✅ AI calls tool without being asked explicitly
- ✅ AI acknowledges that seeing symbol names is only the first step — reading code is needed to assess JSDoc quality
- ✅ Natural conversational response (not raw tool dump)

**Pass/Fail:** _______________

---

## TEST 1.9 — Multiple files in one conversation

**Prompt:**
```
Compare the structure of utils and dataUtils — which has more functions?
```

**Expected AI behavior:**
1. Calls `analyzeFileStructure("utils")` → 6 functions
2. Calls `analyzeFileStructure("dataUtils")` → 6 functions
3. Reports both are equal (6 functions each), with different param complexity

**Success criteria:**
- ✅ Two tool calls in one response
- ✅ Correct symbol counts
- ✅ Meaningful comparison

**Pass/Fail:** _______________

---


## Overall Results

| Test | Description | Pass/Fail |
|------|-------------|-----------|
| 1.1 | utils — 8 symbols, param names | |
| 1.2 | globals — 17 symbols, large file | |
| 1.3 | dataUtils — 10 symbols | |
| 1.4 | mainNav — 7 functions, onShow has 2 params | |
| 1.5 | customerEdit — 11 symbols, mixed params | |
| 1.6 | orderList — onFilterQueryCondition 5 params | |
| 1.7 | Path resolution variants | |
| 1.8 | Natural language trigger | |
| 1.9 | Multiple files in one turn | |
| 1.10 | Compilation clean | |

**Total:** ___ / 10

---

## Known Edge Cases

1. **DLTK symbol count may vary by 1** — DLTK sometimes includes the module-level node or an implicit form variable. Accept ±1 on total counts.
2. **Line numbers are 1-based** in the output (DLTK `getLineOfOffset` + 1). Verify they match by opening the file in Eclipse and checking the line number in the editor status bar.
3. **Parameter names from DLTK** — If `getParameterNames()` returns an empty array for a function (DLTK JS parser limitation), the function will show as `onLoad (FUNCTION)` without parens. This is acceptable — note it as a DLTK limitation, not a bug.
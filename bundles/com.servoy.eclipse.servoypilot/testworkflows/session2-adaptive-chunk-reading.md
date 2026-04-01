# SESSION 2: Adaptive Chunk Reading — Test Workflows

**Date:** April 1, 2026
**Status:** 🧪 READY FOR TESTING
**Implementation:** `CodeAnalysisTools.getCodeChunk()`

---

## Overview

Tests the `getCodeChunk()` tool against the 8 real JS files from `svyPilotTest`. The tool reads source code in configurable chunk sizes with line number prefixes.

**Chunk sizes:**
| Size | Lines | Use case |
|------|-------|---------|
| `SMALL` | 50 | Targeted: read a single known symbol |
| `MEDIUM` | 100 | Read a symbol with surrounding context |
| `LARGE` | 200 | Explore a full file (default) |

**Reading modes (determined by parameters):**
- **TARGETED** — `symbolName` provided → centers window on that symbol's line
- **SEQUENTIAL** — `chunkNumber` provided → reads fixed block by index (0-based)
- **DIRECT** — `startLine` provided → reads from a specific line number

**Key facts about the test files:**

| File | Lines | SMALL chunks | MEDIUM chunks | LARGE chunks |
|------|-------|-------------|---------------|--------------|
| `utils.js` | 96 | 2 | 1 | 1 |
| `globals.js` | 203 | 5 | 3 | 2 |
| `dataUtils.js` | 123 | 3 | 2 | 1 |
| `mainNav.js` | 63 | 2 | 1 | 1 |
| `customerList.js` | 91 | 2 | 1 | 1 |
| `customerEdit.js` | 126 | 3 | 2 | 1 |
| `orderList.js` | 93 | 2 | 1 | 1 |
| `dashboard.js` | 107 | 3 | 2 | 1 |

**Tools tested:** `getCodeChunk(pathOrName, symbolName?, chunkNumber?, startLine?, chunkSize?)`

---

## Prerequisites

- [ ] `svyPilotTest` solution open with all 8 JS files matching `js-content/` reference state
- [ ] Documentation Assistant active in ServoyPilot chat

---

## TEST 2.1 — SEQUENTIAL mode: single-chunk file (utils.js, LARGE)

**Prompt:**
```
Read chunk 1 of utils with large chunk size
```

**Expected AI call:** `getCodeChunk("utils", chunkNumber=1, chunkSize="LARGE")`

**Expected output header:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/scopes/utils.js
LINES: 0-95
CHUNK SIZE: 200 lines
CHUNK: 1 of 1
(LAST CHUNK)
```

**Success criteria:**
- ✅ `CHUNK: 1 of 1` — 96-line file fits in one LARGE chunk
- ✅ `(LAST CHUNK)` marker present
- ✅ All 8 symbols visible in the content
- ✅ Line numbers prefixed on every line (0-based)

**Pass/Fail:** _______________

---

## TEST 2.2 — SEQUENTIAL mode: multi-chunk file (globals.js, LARGE)

**Prompt:**
```
Read globals chunk 1 with large chunk size
```

**Expected AI call:** `getCodeChunk("globals", chunkNumber=1, chunkSize="LARGE")`

**Expected output header:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/scopes/globals.js
LINES: 0-199
CHUNK SIZE: 200 lines
CHUNK: 1 of 2
```

**Follow-up:**
```
Read globals chunk 2 with large chunk size
```

**Expected:**
```
LINES: 200-202
CHUNK SIZE: 200 lines
CHUNK: 2 of 2
(LAST CHUNK)
```

**Success criteria:**
- ✅ `ceil(203/200)` = **2** total chunks
- ✅ Chunk 1 ends at line 199; chunk 2 covers lines 200–202
- ✅ `(LAST CHUNK)` on chunk 2
- ✅ No gap or overlap between chunks

**Pass/Fail:** _______________

---

## TEST 2.3 — SEQUENTIAL mode: MEDIUM chunk size (customerEdit.js)

**Prompt:**
```
Read customerEdit chunk 1 with medium chunk size
```

**Expected AI call:** `getCodeChunk("customerEdit", chunkNumber=1, chunkSize="MEDIUM")`

**Expected output header:**
```
LINES: 0-99
CHUNK SIZE: 100 lines
CHUNK: 1 of 2
```

**Follow-up chunk 2:**
```
LINES: 100-125
CHUNK SIZE: 100 lines
CHUNK: 2 of 2
(LAST CHUNK)
```

**Contrast — same file, LARGE:**
```
LINES: 0-125
CHUNK SIZE: 200 lines
CHUNK: 1 of 1
(LAST CHUNK)
```

**Success criteria:**
- ✅ MEDIUM: `ceil(126/100)` = **2** chunks
- ✅ LARGE: `ceil(126/200)` = **1** chunk
- ✅ Chunk size header matches requested size

**Pass/Fail:** _______________

---

## TEST 2.4 — SEQUENTIAL mode: SMALL chunk size (dataUtils.js)

**Prompt:**
```
Read dataUtils chunk 1 with small chunk size
```

**Expected:**
```
LINES: 0-49
CHUNK SIZE: 50 lines
CHUNK: 1 of 3
```

**Success criteria:**
- ✅ `ceil(123/50)` = **3** total chunks
- ✅ Each chunk ≤ 50 lines

**Pass/Fail:** _______________

---

## TEST 2.5 — TARGETED mode: single symbol (utils.js — formatDate)

**Prompt:**
```
Read only the formatDate function from utils using a small chunk size
```

**Expected AI call:** `getCodeChunk("utils", symbolName="formatDate", chunkSize="SMALL")`

**Expected output:**
```
=== CODE CHUNK ===

FILE: /svyPilotTest/scopes/utils.js
LINES: <start>-<end>    ← window ≤ 50 lines centered on formatDate
CHUNK SIZE: 50 lines
CHUNK: X of Y
```

**Content must include:**
```javascript
function formatDate(date, format) {
    if (!date) return '';
    var fmt = format ? format : DEFAULT_DATE_FORMAT;
    return utils.dateFormat(date, fmt);
}
```

**Success criteria:**
- ✅ `formatDate` body present in returned content
- ✅ Window ≤ 50 lines
- ✅ Symbol is near center of returned window

**Pass/Fail:** _______________

---

## TEST 2.6 — TARGETED mode: 5-parameter function (orderList.js — onFilterQueryCondition)

**Prompt:**
```
Show me the onFilterQueryCondition function from orderList
```

**Expected AI call:** `getCodeChunk("orderList", symbolName="onFilterQueryCondition", chunkSize="SMALL")`

**Content must include:**
```javascript
function onFilterQueryCondition(query, dataprovider, operator, values, filter) {
```

**Success criteria:**
- ✅ Function body fully visible
- ✅ All 5 parameters visible in signature

**Pass/Fail:** _______________

---

## TEST 2.7 — TARGETED mode: zero-parameter function (customerEdit.js — save)

**Prompt:**
```
Show me the save function in customerEdit
```

**Expected AI call:** `getCodeChunk("customerEdit", symbolName="save", chunkSize="SMALL")`

**Content must include:**
```javascript
function save() {
    validationErrors = validate();
```

**Success criteria:**
- ✅ `save()` body present
- ✅ `validate()` call inside visible

**Pass/Fail:** _______________

---

## TEST 2.8 — TARGETED mode: symbol at start of file (utils.js — DEFAULT_DATE_FORMAT)

**Prompt:**
```
Read DEFAULT_DATE_FORMAT from utils using small chunk size
```

**Expected AI call:** `getCodeChunk("utils", symbolName="DEFAULT_DATE_FORMAT", chunkSize="SMALL")`

**Expected:**
- Window starts at line 0 (clamped — cannot go negative)
- Window ends at line 49
- `DEFAULT_DATE_FORMAT` visible in content

**Success criteria:**
- ✅ No negative start line
- ✅ Symbol visible in content

**Pass/Fail:** _______________

---

## TEST 2.9 — DIRECT mode: startLine parameter (globals.js)

**Prompt:**
```
Read globals from line 100
```

**Expected AI call:** `getCodeChunk("globals", startLine=100, chunkSize="LARGE")`

**Expected:**
```
LINES: 100-202   ← from line 100 to end (103 lines — less than LARGE)
(LAST CHUNK)
```

**Success criteria:**
- ✅ Starts at line 100
- ✅ Returns to end of file (not beyond)
- ✅ `(LAST CHUNK)` present

**Pass/Fail:** _______________

---

## TEST 2.10 — Beyond end of file (error handling)

**Prompt:**
```
Read utils chunk 10 with large chunk size
```

**Expected AI call:** `getCodeChunk("utils", chunkNumber=10, chunkSize="LARGE")`

**Expected output:**
```
Error: Chunk 10 is beyond end of file (utils.js has 1 chunk at LARGE size)
```

**Success criteria:**
- ✅ Clear error message
- ✅ No stack trace
- ✅ Mentions file size context

**Pass/Fail:** _______________

---

## TEST 2.11 — AI chunk size decision (autonomous selection)

**Purpose:** The AI must pick the appropriate chunk size based on context — without being told.

### Sub-test A: Single known symbol → expect SMALL

**Prompt:**
```
Show me just the buildQuery function from dataUtils
```

**Expected AI call:** `getCodeChunk("dataUtils", symbolName="buildQuery", chunkSize="SMALL")`

**Success criteria:** ✅ AI picks `SMALL` — single targeted symbol

---

### Sub-test B: Symbol with context → expect MEDIUM

**Prompt:**
```
Show me the save function in customerEdit with surrounding context
```

**Expected AI call:** `getCodeChunk("customerEdit", symbolName="save", chunkSize="MEDIUM")`

**Success criteria:** ✅ AI picks `MEDIUM` — wants surrounding context

---

### Sub-test C: Full file exploration → expect LARGE

**Prompt:**
```
I need to understand the full content of dataUtils before improving its docs
```

**Expected AI call:** `getCodeChunk("dataUtils", chunkNumber=1, chunkSize="LARGE")`

**Success criteria:**
- ✅ AI picks `LARGE` — full-file exploration
- ✅ `CHUNK: 1 of 1` — entire 123-line file in one read
- ✅ AI does not call `getCodeChunk` again (one chunk was sufficient)

**Pass/Fail (A/B/C):** _______________

---

## TEST 2.12 — Integration: analyzeFileStructure → getCodeChunk

**Purpose:** Verifies the two-step workflow that the AI must follow for every documentation task.

**Prompt:**
```
I want to improve the docs in customerList. Analyze it first, then read the code.
```

**Expected AI workflow:**
1. `analyzeFileStructure("customerList")` → symbol map: 9 symbols at known line numbers
2. `getCodeChunk("customerList", chunkNumber=1, chunkSize="LARGE")` → full 91-line file (1 chunk)
3. AI reads actual JSDoc content — identifies TODO stubs vs complete

**Success criteria:**
- ✅ `analyzeFileStructure()` called first (symbol map)
- ✅ `getCodeChunk()` called next (actual code)
- ✅ AI correctly identifies which symbols have TODO stubs vs complete JSDoc
- ✅ AI does NOT skip `getCodeChunk()` after seeing symbol map

**Pass/Fail:** _______________

---

## TEST 2.13 — Line number prefix accuracy

**Purpose:** Verify line number prefixes in chunk output are correct.

**Prompt:**
```
Read utils chunk 1 with large chunk size
```

**Manual verification:**
1. Open `scopes/utils.js` in Eclipse editor
2. Compare chunk line numbers to Eclipse editor line numbers
3. Chunk line 0 = Eclipse line 1 (0-based vs 1-based offset)
4. Check that `var DEFAULT_DATE_FORMAT = 'dd/MM/yyyy';` appears at chunk line 8 (after the 8-line JSDoc block)

**Success criteria:**
- ✅ All line numbers accurate
- ✅ Empty lines preserved with just the line number prefix
- ✅ Indentation preserved

**Pass/Fail:** _______________

---

## Overall Results

| Test | Description | Pass/Fail |
|------|-------------|-----------|
| 2.1 | utils LARGE — 1 chunk | |
| 2.2 | globals LARGE — 2 chunks (boundary at 203 lines) | |
| 2.3 | customerEdit MEDIUM vs LARGE | |
| 2.4 | dataUtils SMALL — 3 chunks | |
| 2.5 | TARGETED formatDate in utils | |
| 2.6 | TARGETED onFilterQueryCondition (5 params) | |
| 2.7 | TARGETED save() zero-param | |
| 2.8 | TARGETED symbol at line 0 (clamp test) | |
| 2.9 | DIRECT startLine=100 in globals | |
| 2.10 | Beyond end of file error | |
| 2.11 | AI autonomous chunk size selection (A/B/C) | |
| 2.12 | analyzeFileStructure → getCodeChunk integration | |
| 2.13 | Line number prefix accuracy | |

**Total:** ___ / 13
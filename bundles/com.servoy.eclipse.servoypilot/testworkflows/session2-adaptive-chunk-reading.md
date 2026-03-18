# SESSION 2: Adaptive Chunk Reading - Test Workflows

**Date:** March 18, 2026  
**Status:** 🧪 READY FOR TESTING  
**Implementation:** CodeAnalysisTools.getCodeChunk()

---

## Overview

This document contains test workflows for SESSION 2 of the Documentation Enhancement project.

**What was implemented:**
- CodeChunkReader service (singleton with 3 reading modes)
- CodeChunk DTO (formatted output for AI)
- getCodeChunk() tool in CodeAnalysisTools with 3 modes:
  - **TARGETED**: Jump to specific symbol by name
  - **DIRECT**: Start from specific line number
  - **SEQUENTIAL**: Read by chunk number
- Integration with FilePathResolver (accepts form/scope names)
- Console logging for debugging

**Key Features:**
- ✅ Max 200 lines per chunk (token efficiency)
- ✅ Line number prefixes on every line (0-based: "250: function loadCustomers() {")
- ✅ Three flexible reading modes
- ✅ Accepts form names, scope names, or full paths
- ✅ Console logging for debugging
- ✅ Chunk progress tracking (CHUNK 2 of 5, LAST CHUNK)

**Tools to test:**
- `getCodeChunk(pathOrName, symbolName?, chunkNumber?, startLine?)` - Read code with 3 modes

---

## Understanding Three Reading Modes

### MODE 1: TARGETED - Jump to Symbol
**Use Case:** AI knows symbol name from Session 1, wants to read that specific code

**Example:**
```
AI knows from analyzeFileStructure():
- loadCustomers (FUNCTION) at line 250

AI calls:
getCodeChunk("testCustomers", symbolName="loadCustomers")

Returns: ~200 lines centered on line 250 (lines 150-350)
```

### MODE 2: DIRECT - Start from Line
**Use Case:** AI knows exact line number, wants to read from there

**Example:**
```
AI calls:
getCodeChunk("testCustomers", startLine=500)

Returns: Lines 500-699 (max 200 lines)
```

### MODE 3: SEQUENTIAL - Explore by Chunks
**Use Case:** AI wants to explore entire file progressively

**Example:**
```
AI calls:
getCodeChunk("testCustomers", chunkNumber=0)  → Lines 0-199
getCodeChunk("testCustomers", chunkNumber=1)  → Lines 200-399
getCodeChunk("testCustomers", chunkNumber=2)  → Lines 400-599
```

---

## Test Preparation

### Create Test Files

Before running tests, create these test files in your active Servoy solution:

**File 1: forms/smallForm.js** (50 lines - Small form for basic testing)
```javascript
/**
 * Handle form load event
 * @param {JSEvent} event
 */
function onLoad(event) {
    application.output("Form loaded");
    initializeData();
}

function initializeData() {
    currentRecord = null;
    dataLoaded = false;
}

/**
 * Save current record
 * @return {Boolean}
 */
function saveRecord() {
    if (!currentRecord) {
        return false;
    }
    databaseManager.saveData(currentRecord);
    return true;
}

function validateData() {
    return true;
}

var currentRecord = null;
var dataLoaded = false;
var MAX_RECORDS = 100;

/**
 * Load data from database
 */
function loadData() {
    var fs = databaseManager.getFoundSet('db:/example_data/customers');
    foundset = fs;
    dataLoaded = true;
}

// Helper function
function resetForm() {
    currentRecord = null;
    dataLoaded = false;
}
```

**File 2: forms/largeForm.js** (800 lines - Large form for chunk testing)
Create a form with:
- Lines 0-199: Variables and initialization code
- Lines 200-399: Data loading functions
- Lines 400-599: Validation and business logic
- Lines 600-799: UI update functions

Use this template and expand:
```javascript
// Variables section (lines 0-50)
var customerID = null;
var orderID = null;
var currentStatus = 'pending';
// ... add more variables ...

// Initialization section (lines 50-200)
function onLoad(event) {
    initializeForm();
    loadCustomers();
}

function initializeForm() {
    customerID = null;
    resetUI();
}
// ... add more init functions ...

// Data loading section (lines 200-400)
function loadCustomers() {
    var fs = databaseManager.getFoundSet('db:/example_data/customers');
    foundset = fs;
}

function loadOrders() {
    var query = "SELECT * FROM orders WHERE customer_id = ?";
    return databaseManager.getDataSetByQuery('example_data', query, [customerID], -1);
}
// ... add more data functions ...

// Validation section (lines 400-600)
function validateCustomer(record) {
    if (!record.name) return false;
    if (!record.email) return false;
    return true;
}

function validateOrder(order) {
    if (!order.customer_id) return false;
    if (order.total < 0) return false;
    return true;
}
// ... add more validation functions ...

// UI update section (lines 600-800)
function updateCustomerUI() {
    elements.customerName.text = currentRecord.name;
    elements.customerEmail.text = currentRecord.email;
}

function updateOrderUI() {
    elements.orderTotal.text = formatCurrency(orderTotal);
}
// ... add more UI functions ...
```

**File 3: scopes/utils.js** (300 lines - Medium scope for symbol testing)
```javascript
/**
 * Format currency
 * @param {Number} amount
 * @return {String}
 */
function formatCurrency(amount) {
    return '$' + amount.toFixed(2);
}

function parseDate(dateStr) {
    return new Date(dateStr);
}

function validateEmail(email) {
    var pattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return pattern.test(email);
}

// Add 20+ more functions to reach 300 lines
// Include mix of documented and undocumented functions
// Place a specific function at line 250 for targeted testing:

function getCustomerType(customerID) {  // This should be at line 250
    var query = "SELECT type FROM customers WHERE id = ?";
    var ds = databaseManager.getDataSetByQuery('example_data', query, [customerID], 1);
    if (ds.getMaxRowIndex() > 0) {
        return ds.getValue(1, 1);
    }
    return null;
}

// Continue with more functions...
```

---

## TEST SUITE

### Test 1: SEQUENTIAL Mode - Basic Chunk Reading

**Objective:** Verify tool reads file sequentially in 200-line chunks

**Assistant:** Documentation Assistant

**Prompt:**
```
Read the first chunk of smallForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("smallForm", chunkNumber=0)`

**Expected Output:**
```
=== CODE CHUNK ===

FILE: /[YourSolution]/forms/smallForm.js
LINES: 0-49
CHUNK: 1 of 1
(LAST CHUNK)

--- CODE ---
0: /**
1:  * Handle form load event
2:  * @param {JSEvent} event
3:  */
4: function onLoad(event) {
5:     application.output("Form loaded");
6:     initializeData();
7: }
8: 
9: function initializeData() {
10:     currentRecord = null;
11:     dataLoaded = false;
12: }
13: 
14: /**
15:  * Save current record
16:  * @return {Boolean}
17:  */
18: function saveRecord() {
19:     if (!currentRecord) {
20:         return false;
21:     }
22:     databaseManager.saveData(currentRecord);
23:     return true;
24: }
...
49: }
--- END CODE ---
```

**Verification Checklist:**
- [ ] Tool accepts just form name "smallForm" without full path
- [ ] Returns exactly 50 lines (0-49) for this small file
- [ ] Each line prefixed with 0-based line number
- [ ] Shows "CHUNK: 1 of 1" (single chunk for small file)
- [ ] Shows "(LAST CHUNK)" indicator
- [ ] File path shows workspace-relative path
- [ ] Line numbers match actual file content
- [ ] Code content readable and complete
- [ ] Response time < 500ms
- [ ] Console shows mode selection: "Using SEQUENTIAL mode"

**Pass/Fail:** _______________

---

### Test 2: SEQUENTIAL Mode - Multi-Chunk Reading (Large File)

**Objective:** Verify tool handles large files with multiple chunks

**Assistant:** Documentation Assistant

**Prompt:**
```
Read chunk 0 of largeForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("largeForm", chunkNumber=0)`

**Expected Output:**
```
=== CODE CHUNK ===

FILE: /[YourSolution]/forms/largeForm.js
LINES: 0-199
CHUNK: 1 of 4

--- CODE ---
0: // Variables section (lines 0-50)
1: var customerID = null;
2: var orderID = null;
...
199: }
--- END CODE ---
```

**Follow-up Prompts:**
```
Read chunk 1 of largeForm
Read chunk 2 of largeForm
Read chunk 3 of largeForm
```

**Expected Chunk Boundaries:**
- Chunk 0: Lines 0-199 (CHUNK: 1 of 4)
- Chunk 1: Lines 200-399 (CHUNK: 2 of 4)
- Chunk 2: Lines 400-599 (CHUNK: 3 of 4)
- Chunk 3: Lines 600-799 (CHUNK: 4 of 4, LAST CHUNK)

**Verification Checklist:**
- [ ] Each chunk returns exactly 200 lines (except last)
- [ ] Chunk numbers sequential (1 of 4, 2 of 4, 3 of 4, 4 of 4)
- [ ] Last chunk shows "(LAST CHUNK)" indicator
- [ ] No gaps between chunks (line 199 → line 200)
- [ ] No overlaps between chunks
- [ ] Line numbers continuous across chunks
- [ ] Total chunks calculation correct (800 lines / 200 = 4 chunks)
- [ ] Each chunk reads independently (no state required)
- [ ] Performance consistent across chunks (< 500ms each)
- [ ] Console shows chunk number selection

**Pass/Fail:** _______________

---

### Test 3: SEQUENTIAL Mode - Beyond End of File

**Objective:** Verify graceful handling when chunk number exceeds file size

**Assistant:** Documentation Assistant

**Prompt:**
```
Read chunk 10 of smallForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("smallForm", chunkNumber=10)`

**Expected Output:**
```
Error: Chunk 10 is beyond end of file
```

**Verification Checklist:**
- [ ] Tool returns clear error message
- [ ] Error mentions chunk number (10)
- [ ] Error mentions "beyond end of file"
- [ ] No stack trace or technical error
- [ ] No NPE or exception thrown
- [ ] AI understands error and responds appropriately
- [ ] Console shows error detection

**Pass/Fail:** _______________

---

### Test 4: TARGETED Mode - Jump to Specific Symbol

**Objective:** Verify tool jumps directly to symbol using FileStructureService

**Setup:** First get file structure to know symbol location

**Assistant:** Documentation Assistant

**Prompt 1:**
```
Analyze the structure of utils
```

**Expected Output:**
```
TOTAL SYMBOLS: 25 (approximate)
...
- getCustomerType (FUNCTION) at line 250 [NEEDS DOCS]
...
```

**Prompt 2:**
```
Now read the code for getCustomerType function in utils
```

**Expected AI Action:**
Tool call: `getCodeChunk("utils", symbolName="getCustomerType")`

**Expected Output:**
```
=== CODE CHUNK ===

FILE: /[YourSolution]/scopes/utils.js
LINES: 150-349
CHUNK: 1 of 2

--- CODE ---
150: // ... preceding code ...
...
250: function getCustomerType(customerID) {  // Target symbol here!
251:     var query = "SELECT type FROM customers WHERE id = ?";
252:     var ds = databaseManager.getDataSetByQuery('example_data', query, [customerID], 1);
253:     if (ds.getMaxRowIndex() > 0) {
254:         return ds.getValue(1, 1);
255:     }
256:     return null;
257: }
...
349: // ... following code ...
--- END CODE ---
```

**Verification Checklist:**
- [ ] Tool accepts scope name "utils" (uses DLTK to find file)
- [ ] Tool accepts just symbol name "getCustomerType"
- [ ] Returns ~200 lines centered on symbol (100 before, 100 after)
- [ ] Target symbol visible in returned chunk
- [ ] Symbol at approximately center of chunk
- [ ] Chunk boundaries reasonable (not cutting mid-function if possible)
- [ ] Line numbers accurate
- [ ] Console shows: "Using TARGETED mode: jumping to symbol 'getCustomerType'"
- [ ] Console shows: "Found symbol 'getCustomerType' at line 251"
- [ ] Response time < 1 second
- [ ] FileStructureService integration working

**Pass/Fail:** _______________

---

### Test 5: TARGETED Mode - Symbol Not Found

**Objective:** Verify error handling when symbol doesn't exist

**Assistant:** Documentation Assistant

**Prompt:**
```
Read the code for nonExistentFunction in smallForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("smallForm", symbolName="nonExistentFunction")`

**Expected Output:**
```
Error: Symbol 'nonExistentFunction' not found in file
```

**Verification Checklist:**
- [ ] Tool returns clear error message
- [ ] Error mentions symbol name ("nonExistentFunction")
- [ ] Error mentions "not found in file"
- [ ] No stack trace or technical error
- [ ] AI understands error and suggests alternatives
- [ ] Console shows: "Symbol 'nonExistentFunction' not found in file structure"

**Pass/Fail:** _______________

---

### Test 6: DIRECT Mode - Start from Specific Line

**Objective:** Verify tool reads from specific line number

**Assistant:** Documentation Assistant

**Prompt:**
```
Read the code from line 400 of largeForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("largeForm", startLine=400)`

**Expected Output:**
```
=== CODE CHUNK ===

FILE: /[YourSolution]/forms/largeForm.js
LINES: 400-599
TOTAL CHUNKS: 4

--- CODE ---
400: // Validation section (lines 400-600)
401: function validateCustomer(record) {
402:     if (!record.name) return false;
403:     if (!record.email) return false;
404:     return true;
405: }
...
599: }
--- END CODE ---
```

**Verification Checklist:**
- [ ] Tool accepts startLine parameter
- [ ] Returns exactly 200 lines from specified start (400-599)
- [ ] First line is line 400
- [ ] Shows "TOTAL CHUNKS: 4" (not chunk number, since direct mode)
- [ ] No "CHUNK: X of Y" (direct mode doesn't use chunk numbering)
- [ ] Line numbers accurate
- [ ] Console shows: "Using DIRECT mode: starting from line 400"
- [ ] Response time < 500ms

**Pass/Fail:** _______________

---

### Test 7: DIRECT Mode - Near End of File

**Objective:** Verify tool handles start line near end of file

**Assistant:** Documentation Assistant

**Prompt:**
```
Read code from line 700 of largeForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("largeForm", startLine=700)`

**Expected Output:**
```
=== CODE CHUNK ===

FILE: /[YourSolution]/forms/largeForm.js
LINES: 700-799
TOTAL CHUNKS: 4
(LAST CHUNK)

--- CODE ---
700: function updateOrderUI() {
...
799: // End of file
--- END CODE ---
```

**Verification Checklist:**
- [ ] Returns only available lines (700-799, not 700-899)
- [ ] Shows "(LAST CHUNK)" indicator
- [ ] No error about insufficient lines
- [ ] Handles end-of-file gracefully
- [ ] Last line number matches actual file end

**Pass/Fail:** _______________

---

### Test 8: DIRECT Mode - Beyond End of File

**Objective:** Verify error handling when start line exceeds file size

**Assistant:** Documentation Assistant

**Prompt:**
```
Read code from line 1000 of smallForm
```

**Expected AI Action:**
Tool call: `getCodeChunk("smallForm", startLine=1000)`

**Expected Output:**
```
Error: Start line 1000 is beyond end of file
```

**Verification Checklist:**
- [ ] Tool returns clear error message
- [ ] Error mentions line number (1000)
- [ ] Error mentions "beyond end of file"
- [ ] No stack trace or exception
- [ ] AI responds appropriately

**Pass/Fail:** _______________

---

### Test 9: Mode Priority - Multiple Parameters Provided

**Objective:** Verify correct mode selection priority when multiple params provided

**Assistant:** Documentation Assistant

**Test Case 9a - TARGETED has priority over SEQUENTIAL:**
```
Read utils with symbolName="formatCurrency" and chunkNumber=0
```

**Expected:** Uses TARGETED mode (symbolName takes priority)

**Test Case 9b - TARGETED has priority over DIRECT:**
```
Read utils with symbolName="formatCurrency" and startLine=100
```

**Expected:** Uses TARGETED mode (symbolName takes priority)

**Test Case 9c - DIRECT has priority over SEQUENTIAL:**
```
Read largeForm with startLine=400 and chunkNumber=2
```

**Expected:** Uses DIRECT mode (startLine takes priority)

**Verification Checklist:**
- [ ] Mode selection follows priority: TARGETED > DIRECT > SEQUENTIAL
- [ ] Console clearly shows which mode was selected
- [ ] Results match selected mode behavior
- [ ] No confusion or ambiguous behavior

**Pass/Fail:** _______________

---

### Test 10: Integration with FilePathResolver

**Objective:** Verify getCodeChunk works with all FilePathResolver input formats

**Assistant:** Documentation Assistant

**Test Cases:**

**10a - Form name only:**
```
Read chunk 0 of smallForm
```
Expected: ✅ Resolves to forms/smallForm.js

**10b - Scope name only:**
```
Read chunk 0 of utils
```
Expected: ✅ Uses DLTK to find utils.js

**10c - Workspace-relative path:**
```
Read chunk 0 of /[YourSolution]/forms/smallForm.js
```
Expected: ✅ Direct resolution

**10d - Form name with .js extension:**
```
Read chunk 0 of smallForm.js
```
Expected: ✅ Strips .js and resolves as form name

**10e - Partial path:**
```
Read chunk 0 of forms/smallForm.js
```
Expected: ✅ Extracts filename and searches

**10f - Non-existent file:**
```
Read chunk 0 of nonExistentForm
```
Expected: Returns helpful error message with:
- "File not found: nonExistentForm"
- Tips for correct usage
- No technical error

**Verification Checklist:**
- [ ] All input formats work correctly
- [ ] FilePathResolver integration seamless
- [ ] Form names auto-resolve
- [ ] Scope names use DLTK API
- [ ] Error messages helpful and clear
- [ ] Console shows resolution strategy

**Pass/Fail:** _______________

---

### Test 11: Line Number Prefix Accuracy

**Objective:** Verify line numbers match actual file content exactly

**Assistant:** Documentation Assistant

**Prompt:**
```
Read the first chunk of smallForm
```

**Manual Verification Steps:**
1. Open `forms/smallForm.js` in Eclipse editor
2. Check line numbers in editor (0-based in output, 1-based in editor)
3. Compare first line: Output "0: ..." should match editor line 1
4. Compare line 10: Output "10: ..." should match editor line 11
5. Compare last line: Output should match editor content

**Verification Checklist:**
- [ ] Line 0 in output = Line 1 in Eclipse editor
- [ ] Line 10 in output = Line 11 in Eclipse editor
- [ ] All line numbers offset by +1 from Eclipse (0-based vs 1-based)
- [ ] Code content matches exactly (no truncation)
- [ ] Special characters preserved (tabs, newlines, quotes)
- [ ] Empty lines preserved with just line number
- [ ] No off-by-one errors

**Pass/Fail:** _______________

---

### Test 12: Empty File Handling

**Objective:** Verify graceful handling of empty JavaScript file

**Setup:** Create empty file: `forms/emptyForm.js` (0 lines)

**Assistant:** Documentation Assistant

**Prompt:**
```
Read chunk 0 of emptyForm
```

**Expected Output:**
```
Error: Chunk 0 is beyond end of file
```
OR
```
=== CODE CHUNK ===

FILE: /[YourSolution]/forms/emptyForm.js
LINES: 0-0
CHUNK: 0 of 0
(LAST CHUNK)

--- CODE ---
--- END CODE ---
```

**Verification Checklist:**
- [ ] No exception or crash
- [ ] Graceful handling (error or empty chunk)
- [ ] Clear response
- [ ] No NPE or index out of bounds

**Pass/Fail:** _______________

---

### Test 13: Performance - Large File Reading

**Objective:** Verify performance remains acceptable for large files

**Assistant:** Documentation Assistant

**Test Sequence:**
```
Read chunk 0 of largeForm
Read chunk 1 of largeForm
Read chunk 2 of largeForm
Read chunk 3 of largeForm
```

**Performance Measurements:**
- Chunk 0 time: _______ ms
- Chunk 1 time: _______ ms
- Chunk 2 time: _______ ms
- Chunk 3 time: _______ ms

**Verification Checklist:**
- [ ] Each chunk reads in < 500ms
- [ ] Performance consistent across chunks
- [ ] No memory leaks (test with 20+ chunks)
- [ ] No degradation on repeated reads
- [ ] File handle properly closed after each read

**Performance Target:** < 500ms per chunk ✅

**Pass/Fail:** _______________

---

### Test 14: Memory Usage - Multiple Files

**Objective:** Verify memory usage reasonable when reading multiple files

**Assistant:** Documentation Assistant

**Test Sequence:**
```
Read chunk 0 of smallForm
Read chunk 0 of largeForm
Read chunk 0 of utils
Read chunk 1 of largeForm
Read chunk 2 of largeForm
```

**Memory Observations:**
- No file content cached unnecessarily
- Each read is independent
- Previous chunk content released
- No accumulation in memory

**Verification Checklist:**
- [ ] Memory usage stays constant (no accumulation)
- [ ] Each read independent (no state)
- [ ] No memory leaks after 50+ reads
- [ ] Garbage collection works normally
- [ ] Eclipse heap usage reasonable

**Pass/Fail:** _______________

---

### Test 15: Full Workflow - Session 1 + Session 2 Integration

**Objective:** Verify Session 1 and Session 2 tools work together seamlessly

**Assistant:** Documentation Assistant

**Complete Workflow:**

**Step 1: Analyze file structure (Session 1)**
```
Analyze the structure of utils
```

**Expected Output:**
```
TOTAL SYMBOLS: 25
DOCUMENTED: 5
UNDOCUMENTED: 20

- formatCurrency (FUNCTION) at line 5 [DOCUMENTED]
- parseDate (FUNCTION) at line 12 [NEEDS DOCS]
- validateEmail (FUNCTION) at line 18 [NEEDS DOCS]
- getCustomerType (FUNCTION) at line 250 [NEEDS DOCS]
...
```

**Step 2: Read specific symbol code (Session 2 - TARGETED)**
```
Now read the code for the getCustomerType function
```

**Expected AI Action:**
Tool call: `getCodeChunk("utils", symbolName="getCustomerType")`

**Expected Output:**
Returns ~200 lines centered on line 250 showing the function

**Step 3: Read from specific line (Session 2 - DIRECT)**
```
Now read from line 100
```

**Expected AI Action:**
Tool call: `getCodeChunk("utils", startLine=100)`

**Expected Output:**
Returns lines 100-299

**Step 4: Read sequentially (Session 2 - SEQUENTIAL)**
```
Now read the first chunk from the beginning
```

**Expected AI Action:**
Tool call: `getCodeChunk("utils", chunkNumber=0)`

**Expected Output:**
Returns lines 0-199

**Verification Checklist:**
- [ ] Both Session 1 and Session 2 tools work together
- [ ] AI correctly uses analyzeFileStructure first
- [ ] AI correctly uses getCodeChunk with appropriate mode
- [ ] File path consistent across both tools
- [ ] Line numbers from Session 1 correctly used in Session 2
- [ ] Symbol names from Session 1 correctly used in Session 2
- [ ] No confusion between tools
- [ ] Seamless workflow experience

**Pass/Fail:** _______________

---

## OVERALL RESULTS

**Tests Completed:** __ / 15

**Passed:** __  
**Failed:** __  
**Success Rate:** ___%

**Performance Summary:**
- Average chunk read time: _______ ms
- Memory usage: _______ MB
- Large file handling: ✅ / ❌

**Ready for SESSION 3:** ☐ YES / ☐ NO

---

## Issues Found

### Issue 1: [Description]
- **Test:** Test #__
- **Severity:** Critical / High / Medium / Low
- **Description:** [What went wrong]
- **Expected:** [What should happen]
- **Actual:** [What actually happened]
- **Fix Required:** [What needs to be changed]

### Issue 2: [Description]
- **Test:** Test #__
- **Severity:** Critical / High / Medium / Low
- **Description:** [What went wrong]
- **Expected:** [What should happen]
- **Actual:** [What actually happened]
- **Fix Required:** [What needs to be changed]

---

## Notes

### Observations:
- [Any observations during testing]
- [Performance notes]
- [Usability notes]

### Recommendations:
- [Improvements for SESSION 3]
- [Documentation updates needed]
- [Additional tests needed]

---

**Test Completed By:** __________________  
**Date:** __________________  
**Time Spent:** ________ hours

---

**END OF SESSION 2 TESTS**

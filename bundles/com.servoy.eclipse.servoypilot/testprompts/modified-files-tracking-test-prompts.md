# Modified Files Tracking Test Prompts
**Purpose:** Test modified files tracking UI and file restoration functionality
**Date:** February 24, 2026
**Feature:** GitHub Copilot-style modified files tracking with Keep/Undo/Remove actions

---

## PHASE 1: DUMMY TOOL TESTING (UI Functionality)

### TEST 1: Single File Modification
**Prompt:**
```
Write a test file with some content
```

**Expected Behavior:**
1. AI calls `writeFile()` tool (dummy implementation)
2. File `/TestProject/dummy1.txt` is tracked
3. "Modified files" section appears above chat input
4. Section shows one file entry: "dummy1.txt"
5. Section is expanded by default (▼ icon)

**UI Elements to Verify:**
- ✅ Section is visible
- ✅ File name displayed correctly
- ✅ Keep All / Undo All buttons visible
- ✅ Hover shows three icons: ✓ ✗ 🗑️

---

### TEST 2: Multiple File Modifications
**Prompt:**
```
Create three test files with different content
```

**Expected Behavior:**
1. AI calls `writeFile()` then `createFile()` then `replaceInFile()` tools
2. Three files tracked: dummy1.txt, dummy2.txt, dummy3.txt
3. All three files shown in modified files section
4. Files listed in order they were modified

**UI Elements to Verify:**
- ✅ Section shows all 3 files
- ✅ Each file has hover actions
- ✅ Files are in insertion order
- ✅ Section remains expanded

---

### TEST 3: Collapsible Behavior
**Setup:** Have 2-3 files in modified section

**Action:**
1. Click toggle icon (▼)
2. Verify section collapses (▶)
3. Click toggle again
4. Verify section expands (▼)

**Expected:**
- ✅ Toggle icon rotates correctly
- ✅ File list hides when collapsed
- ✅ Header remains visible
- ✅ Smooth animation

---

### TEST 4: Keep Single File
**Setup:** Have 2-3 files in modified section

**Action:**
1. Hover over first file
2. Click ✓ (Keep) icon

**Expected:**
- ✅ File immediately removed from list
- ✅ Other files remain
- ✅ Section stays visible (other files present)

---

### TEST 5: Undo Single File (Phase 1 - No Actual Restoration)
**Setup:** Have 2-3 files in modified section

**Action:**
1. Hover over first file
2. Click ✗ (Undo) icon

**Expected:**
- ✅ File immediately removed from list
- ✅ Other files remain
- ✅ Section stays visible (other files present)
- ⚠️ **Phase 1:** No actual file restoration (just removed from tracking)

---

### TEST 6: Remove/Dismiss File
**Setup:** Have 2-3 files in modified section

**Action:**
1. Hover over first file
2. Click 🗑️ (Remove) icon

**Expected:**
- ✅ File immediately removed from list
- ✅ Other files remain
- ✅ Section stays visible (other files present)

---

### TEST 7: Keep All Files
**Setup:** Have 2-3 files in modified section

**Action:**
1. Click "Keep All" button

**Expected:**
- ✅ All files removed from list
- ✅ Section disappears (no files left)
- ✅ Clean UI state

---

### TEST 8: Undo All Files (Phase 1 - No Actual Restoration)
**Setup:** Have 2-3 files in modified section

**Action:**
1. Click "Undo All" button

**Expected:**
- ✅ All files removed from list
- ✅ Section disappears (no files left)
- ✅ Clean UI state
- ⚠️ **Phase 1:** No actual file restoration (just cleared tracking)

---

### TEST 9: File Click (Opens Editor)
**Setup:** Have 1 file in modified section

**Action:**
1. Click on the file name (not icons)

**Expected:**
- ✅ File opens in Eclipse editor
- ⚠️ **Note:** Phase 1 dummy files don't exist, so editor may show error
- ✅ No crash, error logged gracefully

---

### TEST 10: Theme Switching
**Setup:** Have 2-3 files in modified section

**Action:**
1. Verify current theme (light or dark)
2. Switch Eclipse theme
3. Restart chat view or refresh

**Expected:**
- ✅ Light theme: White backgrounds, dark text, blue/red accent colors
- ✅ Dark theme: Dark backgrounds, light text, teal/orange accent colors
- ✅ Hover effects match theme
- ✅ Icons visible in both themes

---

## PHASE 2: FILE RESTORATION TESTING (Real Undo)

### TEST 11: Single File Undo with Real File
**Setup:**
1. Create a real file in workspace: `/TestSolution/test.js`
2. Add content: `var x = 1;`
3. Manually modify using AI or dummy tool

**Action:**
1. File appears in modified section
2. Hover and click ✗ (Undo)

**Expected:**
- ✅ File content restored to original (`var x = 1;`)
- ✅ File removed from tracking
- ✅ If file is open in editor, changes reflected
- ✅ Success logged

---

### TEST 12: Undo All with Real Files
**Setup:**
1. Create 3 real files in workspace
2. Modify all 3 files

**Action:**
1. All 3 files in modified section
2. Click "Undo All" button

**Expected:**
- ✅ All 3 files restored to original content
- ✅ All files removed from tracking
- ✅ Section disappears
- ✅ Success logged for each file

---

### TEST 13: Undo with Missing File
**Setup:**
1. Modify a file so it's tracked
2. Manually delete the file from workspace (outside Eclipse)

**Action:**
1. Try to undo the file

**Expected:**
- ✅ Error logged: "File does not exist"
- ✅ File still removed from tracking
- ✅ No crash
- ✅ Other files unaffected

---

### TEST 14: Undo with Read-Only File
**Setup:**
1. Modify a file so it's tracked
2. Make file read-only in filesystem

**Action:**
1. Try to undo the file

**Expected:**
- ✅ Error logged about write failure
- ✅ File still removed from tracking
- ✅ No crash

---

### TEST 15: File Click Opens for Review
**Setup:**
1. Modify a real file in workspace

**Action:**
1. Click on file name in modified section

**Expected:**
- ✅ File opens in Eclipse editor
- ✅ User can review changes
- ✅ File remains in tracking (click doesn't auto-keep)

**Note:** For full diff, user can right-click → Team → Show Local History

---

## SOLUTION/ASSISTANT SWITCHING TESTS

### TEST 16: Switch Solution Clears Tracking
**Setup:**
1. Modify 2-3 files
2. Modified section shows files

**Action:**
1. Switch to different Servoy solution

**Expected:**
- ✅ Modified files section disappears
- ✅ All tracking cleared
- ✅ No leftover state

---

### TEST 17: Switch Assistant Clears Tracking
**Setup:**
1. In VibeCoding assistant
2. Modify 2-3 files
3. Modified section shows files

**Action:**
1. Switch to Documentation assistant

**Expected:**
- ✅ Modified files section disappears
- ✅ All tracking cleared
- ✅ New assistant starts fresh

---

### TEST 18: Switch Back to VibeCoding
**Setup:**
1. Start in VibeCoding, modify files
2. Switch to Documentation (tracking cleared)
3. Switch back to VibeCoding

**Expected:**
- ✅ Previously tracked files NOT restored (fresh state)
- ✅ Each assistant switch = fresh start
- ✅ This is expected behavior

---

## EDGE CASE TESTS

### TEST 19: Same File Modified Twice
**Prompt:**
```
Write a test file, then modify it again
```

**Expected:**
- ✅ File appears once in modified section
- ✅ Original content = content from FIRST modification
- ✅ Undo restores to very first state

---

### TEST 20: Very Long File Path
**Setup:**
1. Create file with long path: `/TestSolution/deeply/nested/path/structure/file.js`

**Action:**
1. Modify the file

**Expected:**
- ✅ File appears in modified section
- ✅ Long filename truncated with ellipsis (...)
- ✅ Full path shown in tooltip on hover
- ✅ All operations work correctly

---

### TEST 21: Special Characters in Filename
**Setup:**
1. Create file with special chars: `/TestSolution/test-file_2.0.js`

**Action:**
1. Modify the file

**Expected:**
- ✅ Filename displayed correctly
- ✅ No HTML injection
- ✅ No JavaScript errors
- ✅ All operations work

---

### TEST 22: Large File Content
**Setup:**
1. Create file with 10,000+ lines

**Action:**
1. Modify the file
2. Undo the file

**Expected:**
- ✅ No memory issues
- ✅ Undo completes successfully
- ✅ Performance acceptable

---

### TEST 23: Unicode Content
**Setup:**
1. Create file with Unicode: `var name = "日本語 العربية";`

**Action:**
1. Modify and track
2. Undo

**Expected:**
- ✅ Unicode content preserved
- ✅ UTF-8 encoding maintained
- ✅ Content restored correctly

---

### TEST 24: Empty File
**Setup:**
1. Create completely empty file
2. Add content

**Action:**
1. File tracked (original = "")
2. Undo

**Expected:**
- ✅ File restored to empty state
- ✅ No errors

---

### TEST 25: Binary File (Should Not Track)
**Note:** Current implementation tracks text files only

**Setup:**
1. Try to modify a binary file (if supported)

**Expected:**
- ✅ Either: Not tracked (best)
- ✅ Or: Gracefully handled with error

---

## RAPID INTERACTION TESTS

### TEST 26: Rapid Keep/Undo/Remove
**Setup:**
1. Modify 5 files quickly

**Action:**
1. Rapidly click actions on different files
2. Keep, Undo, Remove in quick succession

**Expected:**
- ✅ UI updates correctly
- ✅ No race conditions
- ✅ Final state is consistent
- ✅ No duplicates or missing files

---

### TEST 27: Modify While Section Open
**Setup:**
1. Have 2 files tracked
2. Section is visible

**Action:**
1. Ask AI to modify another file

**Expected:**
- ✅ New file appears in list dynamically
- ✅ Existing files remain
- ✅ No flicker or reload

---

### TEST 28: Collapse/Expand During Modifications
**Setup:**
1. Section is collapsed

**Action:**
1. Ask AI to modify files

**Expected:**
- ✅ Section expands automatically
- ✅ New files visible
- ✅ Or: Section stays collapsed, user can expand to see

---

## ACCESSIBILITY TESTS

### TEST 29: Keyboard Navigation
**Action:**
1. Use Tab key to navigate
2. Use Enter/Space to activate buttons

**Expected:**
- ✅ Can reach all buttons with Tab
- ✅ Buttons activate with Enter/Space
- ✅ Focus indicators visible

---

### TEST 30: Screen Reader Compatibility
**Action:**
1. Use screen reader software

**Expected:**
- ✅ Section title announced
- ✅ File names announced
- ✅ Button labels clear ("Keep All", "Undo All")
- ✅ Icon tooltips read correctly

---

## PERFORMANCE TESTS

### TEST 31: 20+ Files
**Setup:**
1. Modify 20+ files (use loop if needed)

**Action:**
1. Verify UI performance

**Expected:**
- ✅ Section renders smoothly
- ✅ Scrolling works if needed
- ✅ No lag on hover
- ✅ Undo All completes in reasonable time

---

### TEST 32: Frequent Updates
**Setup:**
1. Rapidly modify files (10 in 10 seconds)

**Expected:**
- ✅ UI updates keep up
- ✅ No memory leaks
- ✅ Browser doesn't freeze

---

## SUCCESS CRITERIA

All tests passing means:
- ✅ UI functional and intuitive
- ✅ File tracking accurate
- ✅ Undo restoration works (Phase 2)
- ✅ No crashes or errors
- ✅ Thread-safe operations
- ✅ Theme-aware styling
- ✅ Edge cases handled gracefully
- ✅ Performance acceptable

---

## TESTING SEQUENCE RECOMMENDATION

**Day 1: Basic Functionality**
- Tests 1-10 (UI and dummy tools)

**Day 2: File Restoration**
- Tests 11-15 (Real undo functionality)

**Day 3: Integration**
- Tests 16-18 (Solution/Assistant switching)

**Day 4: Edge Cases**
- Tests 19-25 (Special cases)

**Day 5: Performance & Polish**
- Tests 26-32 (Rapid interactions, accessibility, performance)

---

## KNOWN ISSUES TO WATCH FOR

1. **Browser refresh loses tracking** (Expected - in-memory only)
2. **Eclipse restart loses tracking** (Expected - Phase 1)
3. **Compare Editor simplified** (Opens file instead of full diff)
4. **Dummy files don't exist** (Phase 1 testing limitation)

---

**Testing Status:** Ready for execution
**Last Updated:** February 24, 2026
**Version:** Phase 2 Complete

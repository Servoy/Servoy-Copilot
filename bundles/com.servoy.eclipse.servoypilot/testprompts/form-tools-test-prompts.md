# Form Properties and Events Test Prompts
**Purpose:** Test form creation with properties and auto-created event methods
**Date:** February 9, 2026
**Feature:** Auto-creation of event handler methods with skeleton code

---

## TEST 1: Basic CSS Form with Properties Only
**Prompt:**
```
Create a CSS form named "customerList" with width 1024, height 768, 
styleClass "customer-grid", and single selection mode
```

**Expected:**
- Form created with CSS layout
- Width: 1024, Height: 768
- styleClass: "customer-grid"
- selectionMode: single
- No methods created

---

## TEST 2: CSS Form with Single Event (New Method)
**Prompt:**
```
Create a form "orderEntry" with onLoad event calling method "initializeOrderForm"
```

**Expected:**
- Form created
- Method "initializeOrderForm" auto-created with skeleton:
  ```javascript
  /**
   * @param {JSEvent} event
   */
  function initializeOrderForm(event) {
      // TODO: Initialize form data and setup
  }
  ```
- onLoad event assigned to method

---

## TEST 3: CSS Form with Multiple Lifecycle Events
**Prompt:**
```
Create form "productCatalog" 1200x900 with events: 
onLoad calling "setupCatalog", 
onShow calling "refreshProducts", 
onHide calling "cleanupResources"
```

**Expected:**
- Form created (1200x900)
- 3 methods auto-created with appropriate skeletons:
  - setupCatalog (onLoad skeleton)
  - refreshProducts (onShow skeleton with firstShow parameter)
  - cleanupResources (onHide skeleton)
- All 3 events assigned

---

## TEST 4: CSS Form with Record Events (Return Values)
**Prompt:**
```
Create form "invoiceEdit" with dataSource "db:/accounting/invoices" and events:
onRecordEditStart calling "lockInvoice",
onRecordEditStop calling "validateInvoice",
onBeforeRecordSelection calling "confirmSelection"
```

**Expected:**
- Form created with dataSource
- 3 methods auto-created with `return true;` in skeleton:
  - lockInvoice (onRecordEditStart)
  - validateInvoice (onRecordEditStop - with record parameter)
  - confirmSelection (onBeforeRecordSelection - with oldSelection, newSelection)
- Proper return statements in skeleton code

---

## TEST 5: Comprehensive CSS Form (Properties + Events)
**Prompt:**
```
Create a CSS form "customerManagement" with:
- Size: 1400x1000
- DataSource: db:/crm/customers
- Properties: titleText "Customer Management", styleClass "crm-form", 
  selectionMode "multi", scrollbars "vertical", showInMenu true
- Events: onLoad "initCustomers", onShow "refreshCustomerList", 
  onRecordSelection "selectCustomer", onRecordEditStop "saveCustomer"
```

**Expected:**
- Form created with all properties applied:
  - titleText: "Customer Management"
  - styleClass: "crm-form"
  - selectionMode: multi
  - scrollbars: vertical
  - showInMenu: true
  - dataSource: db:/crm/customers
- 4 methods auto-created with appropriate skeletons
- All 4 events assigned

---

## TEST 6: Update Existing Form - Add Events
**Prompt:**
```
Open form "customerList" and add events: 
onElementDataChange calling "validateField",
onElementFocusGained calling "highlightField",
onElementFocusLost calling "unhighlightField"
```

**Expected:**
- Existing form opened (not recreated)
- 3 new methods auto-created with element event skeletons
- Events assigned to existing form
- Form properties unchanged

---

## TEST 7: CSS Form with Element Events
**Prompt:**
```
Create form "searchPanel" with element interaction events:
onElementDataChange "filterResults",
onElementFocusGained "showHelp",
onElementFocusLost "hideHelp"
```

**Expected:**
- Form created
- 3 methods with element event skeletons (oldValue, newValue parameters for onChange)
- All include `return true;` where needed

---

## TEST 8: CSS Form with All Event Types
**Prompt:**
```
Create form "comprehensiveTest" with all event types:
onLoad "formLoad",
onShow "formShow", 
onHide "formHide",
onRecordSelection "recordSelect",
onRecordEditStop "recordSave",
onElementDataChange "dataChange",
onResize "formResize",
onSort "customSort"
```

**Expected:**
- Form created
- 8 methods auto-created, each with correct skeleton:
  - formLoad (event param)
  - formShow (firstShow, event params)
  - formHide (event param)
  - recordSelect (event param)
  - recordSave (record, event params + return true)
  - dataChange (oldValue, newValue, event params + return true)
  - formResize (event param)
  - customSort (dataProviderID, asc, event params)

---

## TEST 9: CSS Form with Properties and Parent Form
**Prompt:**
```
Create form "customerDetail" extending "baseForm" with:
- Properties: width 800, height 600, transparent true
- Events: onLoad "loadDetails", onRecordSelection "showDetails"
```

**Expected:**
- Form created with inheritance (extendsForm set)
- Properties applied
- 2 methods auto-created
- Events assigned

---

## TEST 10: Method Already Exists - Should Reuse
**Prompt:**
```
First, create form "testReuse" with onLoad event calling "setupForm"
Then, open the form and add onShow event also calling "setupForm"
```

**Expected:**
- First call: Form created, "setupForm" method auto-created, onLoad assigned
- Second call: Form opened, SAME "setupForm" method reused (not recreated), onShow assigned to existing method
- Only ONE "setupForm" method exists in form

---

## TEST 11: Set as Main Form with Events
**Prompt:**
```
Create form "dashboardMain" as main form with:
- Properties: width 1920, height 1080, styleClass "main-dashboard"
- Events: onLoad "initDashboard", onShow "updateWidgets", onResize "layoutWidgets"
```

**Expected:**
- Form created and set as main form
- All properties applied
- 3 methods auto-created
- All events assigned

---

## TEST 12: Responsive Form with Events (Should Work)
**Prompt:**
```
Create responsive form "mobileCustomers" with:
- Events: onLoad "initMobile", onShow "refreshMobile"
```

**Expected:**
- Responsive layout form created
- 2 methods auto-created
- Events assigned (works same as CSS forms)

---

## VALIDATION CHECKLIST

For each test, verify:

**Properties:**
- ✓ All specified properties are applied correctly
- ✓ Properties work on both new and existing forms
- ✓ No properties lost or corrupted

**Event Methods:**
- ✓ Methods auto-created with correct names
- ✓ Skeleton code matches event type
- ✓ JSDoc comments present
- ✓ TODO placeholders included
- ✓ Return statements present where needed (onRecordEditStop, onBeforeHide, etc.)
- ✓ Correct parameter lists per event type

**Event Assignment:**
- ✓ Events assigned to method UUIDs
- ✓ Can verify in Servoy form properties
- ✓ Methods visible in form method list

**Existing Methods:**
- ✓ If method already exists, reused (not duplicated)
- ✓ Existing method code unchanged

**Logging:**
- ✓ Log messages show method auto-creation
- ✓ No errors in console

---

## QUICK SMOKE TEST

**Single Command Test:**
```
Create CSS form "smokeTest" size 1024x768 with styleClass "test" and events: 
onLoad "init", onShow "refresh", onRecordEditStop "validate"
```

**Expected Result:**
- Form created with size and styleClass
- 3 methods auto-created with proper skeletons
- All events assigned
- "validate" method includes `return true;`
- No errors

**Pass Criteria:** All properties applied, all methods created, all events assigned, form opens in editor.

# Form Tools Test Prompts

**Purpose:** Manual testing prompts for FormTools (openForm, getForms, deleteForms) with properties and events support.

**Format:** Each prompt is numbered and describes a specific scenario to test.

---

## Test Prompt 1: Create Simple CSS Form
**Scenario:** Create a basic CSS-positioned form with minimal properties.

**Prompt:**
```
Create a new form called "customers_list" with CSS layout, width 800, height 600, and connect it to the datasource db:/example_data/customers
```

**Expected Result:**
- Form created in current context
- CSS-positioned (useCssPosition = true)
- Width: 800, Height: 600
- DataSource: db:/example_data/customers
- Form opened in editor

---

## Test Prompt 2: Create Form with Properties and Events
**Scenario:** Create a form with multiple properties and event handlers.

**Prompt:**
```
Create a form named "order_details" with the following:
- CSS layout
- Width 1024, height 768
- DataSource: db:/example_data/orders
- Title: "Order Management"
- StyleClass: "custom-order-form"
- Transparent background
- Single selection mode
- Vertical scrollbars
- Add onLoad event handler pointing to method "initializeForm"
- Add onShow event handler pointing to method "refreshData"
- Add onRecordSelection event handler pointing to method "handleSelection"
```

**Expected Result:**
- Responsive form created
- All properties applied correctly
- Three event handlers set (requires methods to exist in form)
- Form opened in editor

---

## Test Prompt 3: Create Responsive Form
**Scenario:** Create a responsive layout form with default settings.

**Prompt:**
```
Create a responsive form called "dashboard" with width 1200 and height 900
```

**Expected Result:**
- Responsive layout form created
- Width: 1200, Height: 900
- Form opened in editor

---

## Test Prompt 4: Create Form with Inheritance
**Scenario:** Create a form that extends a parent form.

**Prompt:**
```
Create a form "customer_detail" that extends "base_form" with width 1024 and height 768
```

**Expected Result:**
- Form created extending base_form (if base_form exists)
- Or error message if base_form doesn't exist
- Width: 1024, Height: 768

---

## Test Prompt 5: List All Forms
**Scenario:** List all forms in the solution and modules.

**Prompt:**
```
Show me all forms in the solution
```

**Expected Result:**
- List of all forms from active solution + modules
- Proper formatting with numbers and origins
- Total count displayed

---

## Test Prompt 6: List Forms in Current Context
**Scenario:** List forms only in the current context.

**Prompt:**
```
What forms are in the current context?
```

**Expected Result:**
- List of forms only from current context
- Proper formatting
- Context name displayed

---

## Test Prompt 7: Create Form with Title and Style
**Scenario:** Create a form with UI styling properties.

**Prompt:**
```
Create a form called "invoice_entry" with title "Invoice Management", styleClass "invoice-form", and show it in the menu
```

**Expected Result:**
- Form created
- titleText: "Invoice Management"
- styleClass: "invoice-form"
- showInMenu: true

---

## Test Prompt 8: Update Form Properties
**Scenario:** Update existing form with new properties.

**Prompt:**
```
Update the "customers_list" form to have a transparent background, multi-selection mode, and both horizontal and vertical scrollbars
```

**Expected Result:**
- Existing form updated
- transparent: true
- selectionMode: multi
- scrollbars: both

---

## Test Prompt 9: Create Form with Named Foundset
**Scenario:** Create a form with separate foundset.

**Prompt:**
```
Create a form "product_search" with a separate foundset and datasource db:/example_data/products
```

**Expected Result:**
- Form created
- namedFoundSet: "separate"
- dataSource: db:/example_data/products

---

## Test Prompt 10: Create Form and Set as Main
**Scenario:** Create a form and set it as the solution's main form.

**Prompt:**
```
Create a form "startup_dashboard" and make it the main form
```

**Expected Result:**
- Form created
- Set as solution's first form (main form)
- Confirmation message

---

## Test Prompt 11: Add Events to Existing Form
**Scenario:** Add event handlers to an existing form.

**Prompt:**
```
Add onLoad, onShow, and onHide events to the "customers_list" form pointing to methods "loadCustomers", "refreshView", and "cleanupView"
```

**Expected Result:**
- Three event handlers set on existing form
- Methods must exist in form or silently skipped
- Form saved

---

## Test Prompt 12: Create Form with Lifecycle Events
**Scenario:** Create a form with all lifecycle events.

**Prompt:**
```
Create a form "order_management" with onLoad pointing to "initOrders", onShow pointing to "displayOrders", onHide pointing to "saveState", and onUnLoad pointing to "cleanup"
```

**Expected Result:**
- Form created
- Four lifecycle events set
- Form opened in editor

---

## Test Prompt 13: Create Form with Record Events
**Scenario:** Create a form with record handling events.

**Prompt:**
```
Create a form "customer_editor" with onRecordSelection pointing to "selectCustomer", onRecordEditStart pointing to "lockRecord", and onRecordEditStop pointing to "validateCustomer"
```

**Expected Result:**
- Form created
- Three record events set
- Form opened in editor

---

## Test Prompt 14: Create Form with Element Events
**Scenario:** Create a form with element interaction events.

**Prompt:**
```
Create a form "search_form" with onElementDataChange pointing to "filterResults", onElementFocusGained pointing to "highlightField", and onElementFocusLost pointing to "validateField"
```

**Expected Result:**
- Form created
- Three element events set
- Form opened in editor

---

## Test Prompt 15: Update Form Size
**Scenario:** Update existing form dimensions.

**Prompt:**
```
Resize the "dashboard" form to 1920x1080
```

**Expected Result:**
- Form width updated to 1920
- Form height updated to 1080

---

## Test Prompt 16: Create Form with Initial Sort
**Scenario:** Create a form with default sorting.

**Prompt:**
```
Create a form "products_list" bound to db:/example_data/products with initial sort "product_name asc, category desc"
```

**Expected Result:**
- Form created
- dataSource: db:/example_data/products
- initialSort: "product_name asc, category desc"

---

## Test Prompt 17: Create Form in Specific Module
**Scenario:** Create a form in a specific module context.

**Prompt:**
```
Create a form "module_dashboard" in Module_A
```

**Expected Result:**
- Context switched to Module_A
- Form created in Module_A
- Confirmation shows module location

---

## Test Prompt 18: Open Existing Form
**Scenario:** Open an existing form without modifications.

**Prompt:**
```
Open the "customers_list" form
```

**Expected Result:**
- Form opened in editor
- No modifications made
- Message shows where form was found

---

## Test Prompt 19: Create Deprecated Form
**Scenario:** Create a form with deprecation notice.

**Prompt:**
```
Create a form "legacy_report" and mark it as deprecated with message "Use new_report instead"
```

**Expected Result:**
- Form created
- deprecated: "Use new_report instead"

---

## Test Prompt 20: Create Form with Navigator
**Scenario:** Create a form with specific navigator setting.

**Prompt:**
```
Create a form "main_view" with navigator set to NONE
```

**Expected Result:**
- Form created
- navigatorID: "NONE"

---

## Test Prompt 21: Delete Single Form
**Scenario:** Delete one form from current context.

**Prompt:**
```
Delete the "old_form" form
```

**Expected Result:**
- If in current context: Form deleted
- If not in current context: Approval request shown

---

## Test Prompt 22: Delete Multiple Forms
**Scenario:** Delete multiple forms at once.

**Prompt:**
```
Delete forms "test_form1", "test_form2", and "test_form3"
```

**Expected Result:**
- Forms in current context deleted immediately
- Forms in other contexts require approval
- Summary of deleted forms

---

## Test Prompt 23: Create Form with All UI Properties
**Scenario:** Create a form with comprehensive UI properties.

**Prompt:**
```
Create a form "advanced_ui" with title "Advanced Interface", styleClass "premium-form", transparent background, single selection, vertical scrollbars, and show in menu
```

**Expected Result:**
- All UI properties applied correctly
- Form created and opened

---

## Test Prompt 24: Create Form with Min Width/Height
**Scenario:** Create a form with minimum dimension constraints.

**Prompt:**
```
Create a form "responsive_panel" with useMinWidth and useMinHeight enabled
```

**Expected Result:**
- Form created
- useMinWidth: true
- useMinHeight: true

---

## Test Prompt 25: Update Form with Multiple Properties
**Scenario:** Update existing form with several properties at once.

**Prompt:**
```
Update "customer_form" with title "Customer Details", styleClass "customer-edit", selection mode single, and initial sort "last_name asc"
```

**Expected Result:**
- All properties updated on existing form
- Form saved

---

## Test Prompt 26: Create Form with onResize Event
**Scenario:** Create a form with resize event handler.

**Prompt:**
```
Create a form "dynamic_layout" with onResize event pointing to "adjustLayout"
```

**Expected Result:**
- Form created
- onResize event set
- Form opened in editor

---

## Test Prompt 27: Create Form with onSort Command
**Scenario:** Create a form with sort command handler.

**Prompt:**
```
Create a form "sortable_grid" with onSort event pointing to "customSort"
```

**Expected Result:**
- Form created
- onSort command set
- Form opened in editor

---

## Test Prompt 28: Comprehensive Form Creation
**Scenario:** Create a fully configured form with all features.

**Prompt:**
```
Create a responsive form "complete_invoice" with width 1200, height 900, datasource db:/example_data/invoices, title "Invoice Management System", styleClass "invoice-main", single selection, both scrollbars, separate foundset, show in menu, initial sort "invoice_date desc", with onLoad pointing to "loadInvoices", onShow pointing to "refreshTotals", onRecordSelection pointing to "selectInvoice", and onRecordEditStop pointing to "validateInvoice"
```

**Expected Result:**
- Responsive form created
- All 14 properties/parameters applied correctly
- All 4 events set
- Form opened in editor

---

## Test Prompt 29: Get Form Properties
**Scenario:** Retrieve detailed properties of an existing form.

**Prompt:**
```
Show me the properties of the "customers_list" form
```

**Expected Result:**
- Detailed property list displayed
- Dimensions, type, datasource, settings shown
- Inheritance info if applicable

---

## Test Prompt 30: Error Handling - Create with Non-Existent Parent
**Scenario:** Attempt to create a form extending a non-existent parent.

**Prompt:**
```
Create a form "child_form" extending "non_existent_parent"
```

**Expected Result:**
- Error message displayed
- List of available forms shown
- No form created
- Suggestion to create parent first or choose existing parent

---


=== SERVOY FORM OPERATIONS ===

**Goal**: Manage Servoy forms using the available MCP tools.

**Current Project**: {{PROJECT_NAME}}  
**Note**: Use only the tools specified below. See copilot-instructions.md for complete tool restrictions and rules.

---

## QUICK REFERENCE: AVAILABLE TOOLS

| Tool | Purpose | Key Parameters | Context-Aware |
|------|---------|----------------|---------------|
| **openForm** | Create/update/open form | name, create, width, height, style | YES (create) |
| **setMainForm** | Set solution's main form | name | NO |
| **listForms** | List forms | scope ("all" or "current") | NO |
| **getFormProperties** | Get form properties | name | NO |

**[CRITICAL] Use ONLY these tools. NO file system or search tools allowed.**

---

## TOOL DETAILS

### openForm
**Create, update, or open forms**

**Dual Behavior:**
- Form exists → Opens it (updates properties if provided)
- Form missing + create=true → Creates it
- Form missing + create=false → Error

**Parameters:**
- `name` (string, required): Form name
- `create` (boolean, optional, default false): Create if doesn't exist
- `width` (int, optional, default 640): Width in pixels
- `height` (int, optional, default 480): Height in pixels
- `style` (string, optional, default "css"): "css" or "responsive"
- `dataSource` (string, optional): Database table (format: `db:/server_name/table_name`)
- `extendsForm` (string, optional): Parent form name for inheritance
- `setAsMainForm` (boolean, optional, default false): Set as solution's first form
- `properties` (object, optional): Property map (see Form Properties section)
- `events` (object, optional): Event handlers map (event name -> method name, see Form Events section)

**Note:** Width/height can be specified directly OR via properties map. Direct parameters preferred for simplicity.

**Examples:**
```javascript
// Open existing
openForm({name: "CustomerForm"})

// Create simple form
openForm({name: "OrderEntry", create: true})

// Create with custom size
openForm({
  name: "OrderEntry",
  create: true,
  width: 1024,
  height: 768
})

// Create responsive form
openForm({
  name: "Dashboard",
  create: true,
  style: "responsive"
})

// Create with datasource
openForm({
  name: "ProductList",
  create: true,
  dataSource: "db:/example_data/products"
})

// Create with inheritance (requires parent form to exist)
openForm({
  name: "CustomerDetail",
  create: true,
  extendsForm: "BaseForm"
})

// Create and set as main form
openForm({
  name: "Dashboard",
  create: true,
  setAsMainForm: true
})

// Update existing form size (direct parameters)
openForm({name: "ExistingForm", width: 1024, height: 768})

// Update via properties (alternative)
openForm({
  name: "ExistingForm",
  properties: {
    "width": 1024,
    "height": 768,
    "showInMenu": true
  }
})

// Update with events (methods must exist in form)
openForm({
  name: "ExistingForm",
  events: {
    "onLoad": "initializeData",
    "onShow": "refreshDisplay"
  }
})

// Update with properties and events combined
openForm({
  name: "ExistingForm",
  properties: {
    "styleClass": "enhanced-form",
    "selectionMode": "single",
    "titleText": "Updated Form"
  },
  events: {
    "onLoad": "setupForm",
    "onRecordSelection": "handleSelection"
  }
})
```

---

### setMainForm
**Set the solution's main/first form**

**Parameters:**
- `name` (string, required): Form name to set as main

**Example:**
```javascript
setMainForm({name: "Dashboard"})
```

**Note:** Main form loads automatically when solution starts.

---

### listForms
**List forms in active solution and/or modules**

**Parameters:**
- `scope` (string, optional, default "all"):
  - `"all"` → Active solution + all modules
  - `"current"` → Current context only (from ContextService)

**Examples:**
```javascript
// List all forms (default)
listForms()
listForms({scope: "all"})

// List only current context
setContext({context: "Module_A"})
listForms({scope: "current"})  // Only Module_A forms

// List only active solution
setContext({context: "active"})
listForms({scope: "current"})  // Only active solution
```

---

### getFormProperties
**Get detailed form properties**

**Parameters:**
- `name` (string, required): Form name

**Example:**
```javascript
getFormProperties({name: "CustomerForm"})
```

**Returns:**
- Dimensions (width, height, min width/height)
- Form type (CSS, responsive, absolute)
- DataSource
- Settings (showInMenu, styleName, navigatorID, initialSort)
- Inheritance (parent form if applicable)
- Main form status

---

## HOW TO PRESENT RESULTS TO USER

### When Listing Forms (listForms output)

**[REQUIRED] Use this EXACT format:**

```
Forms in solution 'MainSolution' and modules (5 total):

1. customerForm (in: active solution) [MAIN FORM]
2. orderEntryForm (in: active solution)
3. productListForm (in: Module_A)
4. categoryForm (in: Module_A)
5. reportForm (in: Module_B)
```

**Formatting Rules:**
- [REQUIRED] Number ONLY the form name line (1., 2., 3.)
- [REQUIRED] One line per form (name + origin + optional [MAIN FORM])
- [REQUIRED] Show total count in header
- [REQUIRED] Add blank line after header
- [FORBIDDEN] DO NOT add extra numbered lines for properties

**When showing details (if asked):**
```
1. customerForm (in: active solution) [MAIN FORM]
   - Width: 800px, Height: 600px
   - DataSource: db:/example_data/customers

2. productListForm (in: Module_A)
   - Width: 1024px, Height: 768px
   - Responsive layout
```

---

## CONTEXT MANAGEMENT

**[CRITICAL] ALWAYS START EVERY RESPONSE WITH CURRENT CONTEXT**

**Required format for EVERY response:**
```
Current context: <context-name>

[rest of your response]
```

**Examples:**
- "Current context: active (MainSolution)"
- "Current context: Module_A"
- "Current context: Module_B"

**Check context at start:** Call `getContext()` if you don't know the current context.

---

### READ Operations - Display ALL Matches, Context NEVER Changes

**Tools affected:** `openForm` (existing forms), `getFormProperties`, `listForms`

**Behavior:**
1. Search current context first
2. If not found → search active solution
3. If still not found → search ALL modules
4. **Display/Open ALL matching forms** (if multiple exist with same name)
5. **Context NEVER changes** for READ operations

**Example 1: Single match in different module**
```javascript
Current context: Module_C
User: "Open productsForm"
// productsForm exists in Module_A

You:
1. Call openForm({name: "productsForm"})
2. Tool opens form from Module_A
3. Tool returns: "Form 'productsForm' opened successfully (from Module_A)
                  [Context remains: Module_C]"
4. Present to user with context info
```

**Example 2: Multiple matches (DISPLAY ALL)**
```javascript
Current context: Module_C
User: "Open customerForm"
// customerForm exists in Module_A AND Module_B

You:
1. Call openForm({name: "customerForm"})
2. Tool opens BOTH forms in separate editor tabs
3. Tool returns: "Form 'customerForm' found in 2 locations. Opened all:
                  - Module_A
                  - Module_B
                  [Context remains: Module_C]"
4. Present ALL to user
```

**[CRITICAL] READ operations do NOT ask user to choose - display ALL matches**

---

### CREATE Operations - Use Current Context

**Tool affected:** `openForm` with `create=true`

**Behavior:**
1. Creates in **current context** (no questions asked)
2. If user specifies different context → call `setContext` FIRST, then create

**Example 1: Create in current context**
```javascript
Current context: Module_C
User: "Create a dashboard form"

You:
1. Call openForm({name: "Dashboard", create: true})
2. Form created in Module_C
3. Context remains: Module_C
```

**Example 2: User specifies explicit context**
```javascript
Current context: Module_C
User: "Create dashboard form in Module_A"

You:
1. Call setContext({context: "Module_A"})  // FIRST!
2. Call openForm({name: "Dashboard", create: true})
3. Form created in Module_A
4. Context now: Module_A
```

---

### UPDATE Operations - Approval Required if Not in Current Context

**Tool affected:** `openForm` with properties/width/height/extendsForm/setAsMainForm on existing form

**Behavior:**
1. If form in current context → Update immediately
2. If form NOT in current context → Tool returns approval request message
3. You ASK user for confirmation
4. If user approves → call `setContext`, then retry operation
5. If user denies → STOP, context unchanged

**Example: Update in different context (approval needed)**
```javascript
Current context: Module_C
User: "Change productsForm width to 1024"
// productsForm exists in Module_A (not in Module_C)

You:
1. Call openForm({name: "productsForm", width: 1024})
2. Tool returns: "Current context: Module_C
                  
                  Form 'productsForm' found in Module_A.
                  Current context is Module_C.
                  
                  To update this form's properties, I need to switch to Module_A.
                  Do you want to proceed?
                  
                  [If yes, I will: setContext({context: 'Module_A'}) then update]"
3. ASK user: "I found 'productsForm' in Module_A. Switch context and update?"
4. If YES:
   - Call setContext({context: "Module_A"})
   - Call openForm({name: "productsForm", width: 1024})
5. If NO:
   - STOP, no changes
```

**[CRITICAL] UPDATE operations require approval before switching context**

**Note:** Forms do not have a delete operation (use Eclipse project explorer)

---

### Default Behavior:
- Context starts as "active" (active solution)
- New forms created in current context
- Context persists until changed or solution activated
- **READ operations search everywhere, display all, never change context**
- **UPDATE operations ask approval if switching context needed**

### When to Check/Set Context:

**User mentions module:**
```javascript
User: "Create form in Module_A"
You: setContext({context: "Module_A"})  // FIRST!
     openForm({name: "Dashboard", create: true})  // Creates in Module_A
```

**Unsure where to create:**
```javascript
getContext()  // Check current context + available options
```

**Multiple operations in same module:**
```javascript
setContext({context: "Module_B"})
openForm({name: "Form1", create: true})  // Created in Module_B
openForm({name: "Form2", create: true})  // Also Module_B (persists)
```

**Return to active solution:**
```javascript
setContext({context: "active"})
```

### Context Response Messages:
- "Form 'customerForm' created in MainSolution (active solution)"
- "Form 'productForm' created in Module_A"
- "Form 'productsForm' opened successfully (from module: Module_A)" ← Found via fallback search

**[REQUIRED] If user says "in Module_X", call setContext FIRST before creating**

---

## FORM PROPERTIES

**The `properties` parameter accepts these keys:**

**Dimension Properties:**
- `width` (int): Width in pixels
- `height` (int): Height in pixels
- `useMinWidth` (boolean): Enable minimum width constraint
- `useMinHeight` (boolean): Enable minimum height constraint

**Data & Core Properties:**
- `dataSource` (string): Database table (format: `db:/server_name/table_name`)
- `namedFoundSet` (string): Named foundset ("empty", "separate", or global relation name)
- `initialSort` (string): Default sort order (e.g., "customer_name asc, id desc")

**UI & Display Properties:**
- `showInMenu` (boolean): Show in Window menu (Servoy Client only)
- `styleName` (string): Servoy style name
- `styleClass` (string): CSS class name applied to the form
- `titleText` (string): Form window title text
- `transparent` (boolean): Enable transparent background
- `navigatorID` (string): Navigator form ID or special values (DEFAULT, NONE, IGNORE)
- `scrollbars` (string): Scrollbar settings - "horizontal", "vertical", or "both"

**Selection & Behavior Properties:**
- `selectionMode` (string): Record selection mode - "default", "single", or "multi"

**Metadata Properties:**
- `deprecated` (string): Deprecation message/info

**Selection Mode Values:**
- `"default"` - Default selection behavior
- `"single"` - Single record selection only
- `"multi"` - Multiple record selection allowed

**Scrollbars Values:**
- `"horizontal"` - Horizontal scrollbar only
- `"vertical"` - Vertical scrollbar only
- `"both"` - Both horizontal and vertical scrollbars

**Examples:**
```javascript
// Simple properties
properties: {
  "width": 1024,
  "height": 768,
  "useMinWidth": true,
  "showInMenu": false,
  "initialSort": "name asc"
}

// UI styling properties
properties: {
  "styleClass": "custom-form",
  "titleText": "Customer Management",
  "transparent": false,
  "scrollbars": "vertical"
}

// Behavior properties
properties: {
  "selectionMode": "single",
  "namedFoundSet": "separate"
}

// Combined properties
properties: {
  "width": 1024,
  "height": 768,
  "styleClass": "order-form",
  "titleText": "Order Details",
  "selectionMode": "single",
  "scrollbars": "both",
  "showInMenu": true,
  "initialSort": "order_date desc"
}
```

---

## FORM EVENTS

**The `events` parameter accepts event name to method name mappings.**

**[CRITICAL] Methods must exist in the form before assigning events. Create methods first using Servoy's method editor.**

**Supported Events:**

**Lifecycle Events:**
- `onLoad` - Form is loaded/reloaded from repository
- `onUnLoad` - Form is unloaded from repository
- `onShow` - Form is displayed (fires every time)
- `onHide` - Form is hidden
- `onBeforeHide` - Form wants to hide (can prevent hiding by returning false)

**Record Events:**
- `onRecordSelection` - Record is selected
- `onBeforeRecordSelection` - Before record selection (can prevent by returning false)
- `onRecordEditStart` - User starts editing a record
- `onRecordEditStop` - Record is being saved (can prevent by returning false)

**Element Events:**
- `onElementDataChange` - Data changed in form component (fires after component's own handler)
- `onElementFocusGained` - Component gains focus
- `onElementFocusLost` - Component loses focus

**UI Events:**
- `onResize` - Form is resized

**Commands:**
- `onSort` - Sort command triggered

**Event Parameter Format:**
```javascript
events: {
  "onLoad": "initializeForm",
  "onShow": "refreshData",
  "onRecordSelection": "handleSelection"
}
```

**[IMPORTANT] Method Resolution:**
- Event values are method names (strings)
- Methods must exist in the form's method collection
- If method not found, event assignment is silently skipped (logged)
- No error thrown if method missing

**Complete Example with Events:**
```javascript
// First, ensure methods exist in form (via Servoy UI):
// - initForm()
// - loadData()
// - handleRecordChange()

// Then assign events:
openForm({
  name: "CustomerForm",
  create: true,
  width: 1024,
  height: 768,
  dataSource: "db:/example_data/customers",
  properties: {
    "styleClass": "customer-form",
    "selectionMode": "single",
    "titleText": "Customer Management"
  },
  events: {
    "onLoad": "initForm",
    "onShow": "loadData",
    "onRecordSelection": "handleRecordChange"
  }
})
```

**Workflow for Adding Events:**
1. Create form (if new)
2. Create methods in form using Servoy method editor
3. Call openForm with events parameter to assign handlers

**Examples:**

```javascript
// Simple lifecycle events
openForm({
  name: "Dashboard",
  events: {
    "onLoad": "setupDashboard",
    "onShow": "refreshWidgets"
  }
})

// Record handling events
openForm({
  name: "OrderEntry",
  events: {
    "onRecordEditStart": "lockRecord",
    "onRecordEditStop": "validateOrder",
    "onRecordSelection": "loadOrderDetails"
  }
})

// Element interaction events
openForm({
  name: "SearchForm",
  events: {
    "onElementDataChange": "filterResults",
    "onElementFocusGained": "highlightField",
    "onElementFocusLost": "validateField"
  }
})

// Combined: properties + events
openForm({
  name: "ProductCatalog",
  properties: {
    "width": 1200,
    "height": 800,
    "styleClass": "catalog-form",
    "selectionMode": "multi",
    "scrollbars": "vertical"
  },
  events: {
    "onLoad": "loadCategories",
    "onShow": "updatePrices",
    "onSort": "customSortHandler"
  }
})
```

**Choose based on target platform:**

**CSS Forms** (`style: "css"`):
- Absolute positioning (x, y coordinates)
- Best for desktop applications
- Traditional Servoy layout
- **Default choice**

**Responsive Forms** (`style: "responsive"`):
- Bootstrap 12-column grid layout
- Best for web and mobile applications
- Fluid layouts adapt to screen size
- Modern responsive design

---

## COMPLETE WORKFLOWS

### Workflow 1: Create Form (User Knows Details)

1. Check if user specified module → If yes: `setContext({context: "Module_X"})`
2. Determine width, height, style (use defaults if not specified)
3. Call `openForm` with create=true
4. Tool reports where created and opens form in designer

**Example:**
```
User: "Create OrderEntry form 1024x768 in Module_A"
→ setContext({context: "Module_A"})
→ openForm({
    name: "OrderEntry",
    create: true,
    width: 1024,
    height: 768
  })
→ Response: "Form 'OrderEntry' created in Module_A (1024x768 pixels)"
```

---

### Workflow 2: Create Form with Inheritance

**[CRITICAL] Parent form MUST exist before creating child form.**

1. Check context if module mentioned
2. **Validate parent exists:** Call `listForms()` and check output
3. If parent NOT found → Display error with available forms, STOP
4. If parent found → Call `openForm` with extendsForm parameter

**Example (parent exists):**
```
User: "Create CustomerDetail extending BaseForm"
→ listForms()  // Check parent exists
→ Output includes "BaseForm" ✓
→ openForm({
    name: "CustomerDetail",
    create: true,
    extendsForm: "BaseForm"
  })
```

**Example (parent missing):**
```
User: "Create CustomerDetail extending BaseForm"
→ listForms()
→ Output: Dashboard, OrderList, ProductView (NO BaseForm)
→ Display: "Error: Parent form 'BaseForm' does not exist in {{PROJECT_NAME}}.
           Available forms: Dashboard, OrderList, ProductView
           Please create BaseForm first or choose an existing parent."
→ STOP (do NOT call openForm, do NOT use other tools)
```

---

### Workflow 3: Update Form Properties

1. Call `openForm` with name + properties (no create=true needed)
2. Tool updates and confirms

**For size updates, prefer direct parameters:**
```javascript
openForm({name: "MyForm", width: 1024, height: 768})
```

**For other properties:**
```javascript
openForm({
  name: "MyForm",
  properties: {"showInMenu": true, "initialSort": "name asc"}
})
```

---

### Workflow 4: List and Filter Forms

**List all:**
```
User: "Show me all forms"
→ listForms()
→ [Format per "How to Present Results" section]
```

**List current context only:**
```
User: "What forms are in Module_A?"
→ setContext({context: "Module_A"})
→ listForms({scope: "current"})
```

**List active solution only:**
```
User: "Show forms in main solution"
→ setContext({context: "active"})
→ listForms({scope: "current"})
```

---

### Workflow 5: Set Main Form

1. Optionally validate form exists: `listForms()`
2. Call `setMainForm({name: "FormName"})`

**Example:**
```
User: "Make Dashboard the main form"
→ setMainForm({name: "Dashboard"})
```

---

## CRITICAL RULES

1. **Form name**: Required, must be valid Servoy identifier
2. **Context**: Check/set BEFORE creating if user mentions module
3. **Default size**: 640x480 if not specified
4. **Default style**: CSS positioning if not specified
5. **DataSource format**: `db:/server_name/table_name` (prefix required for forms)
6. **First form auto-main**: When creating the FIRST form in a solution (no other forms exist), it's AUTOMATICALLY set as main form
7. **Parent validation**: Before creating form with extendsForm, MUST validate parent exists using listForms()
8. **Width/height updates**: Can update on existing forms - use direct parameters (simpler than properties)
9. **listForms is truth**: If listForms() doesn't show a form, it does NOT exist in {{PROJECT_NAME}} - STOP immediately
10. **Tool restrictions**: Use ONLY the 4 tools listed - NO file system or search tools (see copilot-instructions.md RULE 6)
11. **Scope parameter**: Use `scope: "current"` to filter by context, default is `"all"`

---

## COMPREHENSIVE EXAMPLES

**Example 1: Simple create in active solution**
```
User: "Create CustomerList form"
→ openForm({name: "CustomerList", create: true})
```

**Example 2: Create in module with context**
```
User: "Create Dashboard form in Module_A"
→ setContext({context: "Module_A"})
→ openForm({name: "Dashboard", create: true})
```

**Example 3: Create with custom size**
```
User: "Create Orders form 1024x768"
→ openForm({
    name: "Orders",
    create: true,
    width: 1024,
    height: 768
  })
```

**Example 4: Create responsive form**
```
User: "Create responsive Dashboard form"
→ openForm({
    name: "Dashboard",
    create: true,
    style: "responsive"
  })
```

**Example 5: Create with datasource**
```
User: "Create ProductList form bound to products table in example_data"
→ openForm({
    name: "ProductList",
    create: true,
    dataSource: "db:/example_data/products"
  })
```

**Example 6: Create with inheritance (parent exists)**
```
User: "Create CustomerDetail extending BaseForm"
→ listForms()  // Validate parent exists
→ BaseForm found in output ✓
→ openForm({
    name: "CustomerDetail",
    create: true,
    extendsForm: "BaseForm"
  })
```

**Example 7: Create and set as main**
```
User: "Create Invoice form and make it the main form"
→ openForm({
    name: "Invoice",
    create: true,
    setAsMainForm: true
  })
```

**Example 8: Update existing form size**
```
User: "Resize ExistingForm to 1024x768"
→ openForm({name: "ExistingForm", width: 1024, height: 768})
```

**Example 9: Update properties**
```
User: "Make ExistingForm show in menu and set initial sort"
→ openForm({
    name: "ExistingForm",
    properties: {
      "showInMenu": true,
      "initialSort": "customer_name asc"
    }
  })
```

**Example 10: List with scope**
```
User: "Show forms in Module_A only"
→ setContext({context: "Module_A"})
→ listForms({scope: "current"})
→ [Format per presentation rules]
```

**Example 11: Set main form**
```
User: "Make Dashboard the main form"
→ setMainForm({name: "Dashboard"})
```

**Example 12: First form auto-main behavior**
```
User: "Create Dashboard form" (this is the FIRST form in the solution)
→ openForm({name: "Dashboard", create: true})
→ Response: "Form 'Dashboard' created successfully (640x480 pixels). 
            Automatically set as main form (first form in solution)."
→ Note: No need for setAsMainForm=true when it's the first form
```

**Example 13: Parent validation - form missing**
```
User: "Create CustomerDetail extending NonExistent"
→ listForms()
→ NonExistent NOT in output
→ Display: "Error: Parent form 'NonExistent' does not exist in {{PROJECT_NAME}}.
           Available forms: Dashboard, OrderList, ProductView
           Please create NonExistent first or choose an existing parent."
→ STOP (do NOT proceed with openForm)
```

**Example 14: Create form with multiple properties**
```
User: "Create OrderForm with title 'Order Entry', single selection, vertical scrollbars, and custom style class"
→ openForm({
    name: "OrderForm",
    create: true,
    properties: {
      "titleText": "Order Entry",
      "selectionMode": "single",
      "scrollbars": "vertical",
      "styleClass": "order-entry-form"
    }
  })
```

**Example 15: Create form with events (methods must exist)**
```
User: "Create ProductForm with onLoad and onShow events"
→ openForm({
    name: "ProductForm",
    create: true,
    events: {
      "onLoad": "initProducts",
      "onShow": "refreshProductList"
    }
  })
→ Note: Methods "initProducts" and "refreshProductList" must exist in form
```

**Example 16: Update form with new properties**
```
User: "Make CustomerForm transparent with multi-selection"
→ openForm({
    name: "CustomerForm",
    properties: {
      "transparent": true,
      "selectionMode": "multi"
    }
  })
```

**Example 17: Comprehensive form creation**
```
User: "Create InvoiceForm: responsive, 1200x900, bound to invoices table, with custom styling, events, and separate foundset"
→ openForm({
    name: "InvoiceForm",
    create: true,
    width: 1200,
    height: 900,
    style: "responsive",
    dataSource: "db:/example_data/invoices",
    properties: {
      "titleText": "Invoice Management",
      "styleClass": "invoice-form",
      "selectionMode": "single",
      "scrollbars": "both",
      "namedFoundSet": "separate",
      "showInMenu": true
    },
    events: {
      "onLoad": "loadInvoiceSettings",
      "onShow": "updateTotals",
      "onRecordSelection": "selectInvoice",
      "onRecordEditStop": "validateInvoice"
    }
  })
```

---

## LIMITATIONS

**Not yet fully supported (inform user gracefully):**
- Deleting forms
- Adding UI components to forms (use separate component tools)
- Form variables, methods, or calculations
- Complex layout modifications
- Cross-project form operations

**Note:** Forms must exist before UI components can be added. Creating/opening forms is the first step.

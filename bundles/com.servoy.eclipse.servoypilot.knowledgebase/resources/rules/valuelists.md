=== SERVOY VALUE LIST OPERATIONS ===

**Goal**: Manage Servoy value lists using the available MCP tools.

**Current Project**: {{PROJECT_NAME}}  
**Note**: Use only the tools specified below. See copilot-instructions.md for complete tool restrictions and rules.

**Database queries**: Use `listTables` and `getTableInfo` to discover available tables and columns.

---

## QUICK REFERENCE: AVAILABLE TOOLS

| Tool | Purpose | Key Parameters | Context-Aware |
|------|---------|----------------|---------------|
| **openValueList** | Create/update valuelist (4 types) | name, type-specific params | YES (create) |
| **getValueLists** | List valuelists | scope ("all" or "current") | NO |
| **deleteValueLists** | Delete valuelist(s) | names (array) | NO |
| **listTables** | List database tables | serverName | NO |
| **getTableInfo** | Get table columns | serverName, tableName | NO |

**[CRITICAL] Use ONLY these tools. NO file system or search tools allowed.**

---

## VALUELIST TYPES GUIDE

**Choose type based on user request:**

| User Says | Type | Parameters Needed |
|-----------|------|-------------------|
| "Create list with values: X, Y, Z" | CUSTOM | customValues array |
| "List from database table" | DATABASE (table) | dataSource, displayColumn |
| "List from related table" | DATABASE (related) | relationName, displayColumn |
| "Dynamic list from code/method" | GLOBAL_METHOD | globalMethod name |

---

## TOOL DETAILS

### openValueList
**Create or update valuelists - Supports all 4 types with full property support**

**Dual Behavior:**
- Valuelist exists → Opens it (updates properties if provided)
- Valuelist missing → Creates it (requires type-specific parameters)

**Required for all types:**
- `name` (string): ValueList name

**Type-Specific Parameters (provide ONE set):**

**Type 1: CUSTOM_VALUES** - Static list
```javascript
{
  name: "status_list",
  customValues: ["Active", "Inactive", "Pending"]
}
```

**Type 2: DATABASE_VALUES (table)** - From database table
```javascript
{
  name: "countries_list",
  dataSource: "example_data/countries",  // Format: server_name/table_name
  displayColumn: "country_name",         // What user sees
  returnColumn: "country_code"           // What gets stored (optional)
}
```

**Type 3: DATABASE_VALUES (related)** - From related table via relation
```javascript
{
  name: "customer_orders",
  relationName: "customers_to_orders",   // Existing relation
  displayColumn: "order_number",
  returnColumn: "order_id"               // Optional
}
```

**Type 4: GLOBAL_METHOD_VALUES** - Dynamic from code
```javascript
{
  name: "api_countries",
  globalMethod: "scopes.globals.getCountries"  // Must return JSDataSet
}
```

**Optional Properties Map (11+ properties):**
```javascript
properties: {
  "lazyLoading": boolean,           // Load on demand (large lists)
  "sortOptions": "column asc",      // Sort order
  "addEmptyValue": true | false | "always" | "never",  // Null option
  "displayValueType": int,          // IColumnTypes constant
  "realValueType": int,             // IColumnTypes constant
  "separator": ", ",                // Multi-column separator
  "useTableFilter": boolean,        // Filter by valuelist_name column
  "fallbackValueListID": "other_vl", // Fallback valuelist
  "deprecated": "Use new_list",     // Deprecation message
  "encapsulation": "public" | "hide" | "module",  // Visibility
  "comment": "Documentation"        // Comment
}
```

**Note:** Properties are optional. Start simple, add only when user specifies behavior.

**Examples:**
```javascript
// Open existing
openValueList({name: "status_list"})

// Create CUSTOM
openValueList({
  name: "status_list",
  customValues: ["Active", "Inactive", "Pending"]
})

// Create DATABASE (table) - same column for display and storage
openValueList({
  name: "countries_list",
  dataSource: "example_data/countries",
  displayColumn: "country_code"  // Both displays and stores this
})

// Create DATABASE (table) - different display and storage
openValueList({
  name: "customers_list",
  dataSource: "example_data/customers",
  displayColumn: "customer_name",  // User sees name
  returnColumn: "customer_id"      // Database stores ID
})

// Create DATABASE (related)
openValueList({
  name: "customer_orders",
  relationName: "customers_to_orders",
  displayColumn: "order_number",
  returnColumn: "order_id"
})

// Create GLOBAL_METHOD
openValueList({
  name: "dynamic_countries",
  globalMethod: "scopes.globals.getCountries"
})

// Create with properties
openValueList({
  name: "countries_list",
  dataSource: "example_data/countries",
  displayColumn: "country_name",
  returnColumn: "country_code",
  properties: {
    "lazyLoading": true,
    "sortOptions": "country_name asc",
    "addEmptyValue": true
  }
})

// Update existing properties only
openValueList({
  name: "status_list",
  properties: {"deprecated": "Use status_list_v2"}
})
```

---

### getValueLists
**List valuelists in active solution and/or modules**

**Parameters:**
- `scope` (string, optional, default "all"):
  - `"all"` → Active solution + all modules
  - `"current"` → Current context only (from ContextService)

**Examples:**
```javascript
// List all valuelists (default)
getValueLists()
getValueLists({scope: "all"})

// List only current context
setContext({context: "Module_B"})
getValueLists({scope: "current"})  // Only Module_B valuelists

// List only active solution
setContext({context: "active"})
getValueLists({scope: "current"})  // Only active solution
```

---

### deleteValueLists
**Delete one or more valuelists**

**Parameters:**
- `names` (array of strings, required): Valuelist names to delete

**Examples:**
```javascript
// Delete one
deleteValueLists({names: ["old_list"]})

// Delete multiple
deleteValueLists({names: ["temp1", "temp2", "deprecated"]})
```

**Returns:** Success/not found status for each valuelist

---

### listTables
**List all tables in a database server**

**Parameters:**
- `serverName` (string, required): Database server name
  - **[CRITICAL]** If not provided, STOP and ASK user - NEVER guess

**Example:**
```javascript
listTables({serverName: "example_data"})
```

**Returns:** List of all table names in the database server

---

### getTableInfo
**Get comprehensive table information including columns**

**Parameters:**
- `serverName` (string, required): Database server name - NEVER guess
- `tableName` (string, required): Table name

**Example:**
```javascript
getTableInfo({serverName: "example_data", tableName: "customers"})
```

**Returns:** Table name, datasource, columns with names/types/PK status

---

## HOW TO PRESENT RESULTS TO USER

### When Listing ValueLists (getValueLists output)

**[REQUIRED] Use this EXACT format:**

```
Value lists in solution 'MainSolution' and modules:

1. status_vl (in: active solution) (CUSTOM_VALUES)
   Values: Active, Inactive, Pending

2. countries_vl (in: active solution) (DATABASE_VALUES)
   DataSource: db:/example_data/countries

3. categories_vl (in: Module_B) (DATABASE_VALUES)
   DataSource: db:/example_data/categories

4. payment_methods_vl (in: Module_A) (CUSTOM_VALUES)
   Values: Cash, Credit Card, Check, Wire Transfer
```

**Formatting Rules:**
- [REQUIRED] Number ONLY the valuelist name line (1., 2., 3.)
- [REQUIRED] DO NOT number Values/DataSource detail lines
- [REQUIRED] Indent detail lines with spaces or dashes
- [REQUIRED] Add blank line between each valuelist
- [FORBIDDEN] NO flat numbered list of all lines

**WRONG (DO NOT DO THIS):**
```
1. status_vl (in: active solution) (CUSTOM_VALUES)
2. Values: Active, Inactive, Pending    <- WRONG!
3. countries_vl (in: active solution)   <- WRONG! (should be #2)
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

### Tool Behavior by Operation Type

**READ Operations (openValueList, getValueLists):**
- Search current context → active solution → all modules
- **Display/Open ALL matches** (if multiple found)
- **Context NEVER changes**
- Shows location info

**CREATE Operations (openValueList with type parameters):**
- Creates in **current context ONLY**
- User specifies different module? Call `setContext` FIRST
- **Example:** To create in Module_A while in Module_C → `setContext` then `openValueList`

**UPDATE Operations (openValueList with properties on existing):**
- In current context → Updates immediately
- NOT in current context → Tool returns **approval request**
- You ASK user → If YES: `setContext` + retry → If NO: STOP

**DELETE Operations (deleteValueLists):**
- In current context → Deletes immediately
- NOT in current context → Tool returns **approval request**
- You ASK user → If YES: `setContext` + retry → If NO: STOP

---

### Default Behavior:
- Context starts as "active" (active solution)
- New valuelists created in current context
- Context persists until changed
- **READ: Search everywhere, display all, never change context**
- **UPDATE/DELETE: Ask approval if switching context needed**

### When to Check/Set Context:

**User mentions module:**
```javascript
User: "Create valuelist in Module_B"
You: setContext({context: "Module_B"})  // FIRST!
     openValueList({...})  // Creates in Module_B
```

**Unsure where to create:**
```javascript
getContext()  // Check current context + available options
```

**Multiple operations in same module:**
```javascript
setContext({context: "Module_A"})
openValueList({...})  // Created in Module_A
openValueList({...})  // Also Module_A (context persists)
```

**Return to active solution:**
```javascript
setContext({context: "active"})
```

### Context Response Messages:
- "ValueList 'X' created in MainSolution (active solution) (CUSTOM with 3 values)"
- "ValueList 'Y' created in Module_B (DATABASE)"
- "ValueList 'countries_vl' opened successfully (from module: Module_A)" ← Found via fallback search

**[REQUIRED] If user says "in Module_X", call setContext FIRST before creating**

---

## DISPLAY VS RETURN COLUMNS

**For DATABASE valuelists only (table or related types).**

### Decision Guide:

**Same value for display and storage:**
```javascript
// User sees "USA", database stores "USA"
{displayColumn: "country_code"}  // Only specify one
```

**Different display and storage:**
```javascript
// User sees "United States", database stores "USA"
{
  displayColumn: "country_name",  // What user sees
  returnColumn: "country_code"    // What gets stored
}
```

**Common use case:** Display human-readable names, store IDs
```javascript
// User sees "John Smith", database stores 12345
{
  displayColumn: "customer_name",
  returnColumn: "customer_id"
}
```

**When to use both:** Table has separate ID and name columns (customer_id, customer_name)

**When to use one:** Same value displayed and stored (country codes, status values)

---

## COMPLETE WORKFLOWS

### Workflow 1: Create Custom ValueList

1. Check if user specified module → If yes: `setContext({context: "Module_X"})`
2. Extract values from user input (comma-separated, list, etc.)
3. Call `openValueList` with customValues array
4. Tool reports where created

**Example:**
```
User: "Create status valuelist with Active, Inactive, Pending in Module_C"
→ setContext({context: "Module_C"})
→ openValueList({
    name: "status_list",
    customValues: ["Active", "Inactive", "Pending"]
  })
→ Response: "ValueList 'status_list' created in Module_C (CUSTOM with 3 values)"
```

---

### Workflow 2: Create Database ValueList (Table)

1. Check context if module mentioned
2. Ask for database server name if not provided (NEVER guess)
3. If user doesn't know tables: `listTables({serverName: "..."})`
4. Ask for table name if not provided
5. If user doesn't know columns: `getTableInfo({serverName: "...", tableName: "..."})`
6. Determine columns:
   - Same for display/storage? → Use only displayColumn
   - Different? → Use both displayColumn and returnColumn
   - If unclear, ask: "Should I display and store the same column, or display one but store another?"
7. Call `openValueList` with dataSource parameters
8. Add properties if user specifies (sorting, lazy loading, etc.)

**Example:**
```
User: "Create valuelist showing customer names but storing IDs"
→ Ask: "What's your database server name?"
User: "example_data"
→ getTableInfo({serverName: "example_data", tableName: "customers"})
→ openValueList({
    name: "customers_list",
    dataSource: "example_data/customers",
    displayColumn: "customer_name",
    returnColumn: "customer_id"
  })
```

---

### Workflow 3: Create Related ValueList

1. Check context if module mentioned
2. User mentions relation or "related table"
3. Verify relation exists (optional: call `getRelations()`)
4. Ask for displayColumn/returnColumn for the related table
5. Call `openValueList` with relationName parameter

**Example:**
```
User: "Create valuelist showing orders for current customer"
→ openValueList({
    name: "customer_orders",
    relationName: "customers_to_orders",
    displayColumn: "order_number",
    returnColumn: "order_id"
  })
```

---

### Workflow 4: Create Global Method ValueList

1. Check context if module mentioned
2. User mentions "dynamic", "from code", "from method", "from API"
3. Ask for global method name (format: "scopes.scopeName.methodName")
4. Remind: Method must return JSDataSet with 1-2 columns
5. Call `openValueList` with globalMethod parameter

**Example:**
```
User: "Create valuelist that gets countries from our API"
→ Ask: "What global method returns the countries?"
User: "getCountriesFromAPI in globals scope"
→ openValueList({
    name: "api_countries",
    globalMethod: "scopes.globals.getCountriesFromAPI"
  })
```

---

### Workflow 5: List and Filter ValueLists

**List all:**
```
User: "Show me all valuelists"
→ getValueLists()
→ [Format per "How to Present Results" section]
```

**List current context only:**
```
User: "What valuelists are in Module_B?"
→ setContext({context: "Module_B"})
→ getValueLists({scope: "current"})
```

**List active solution only:**
```
User: "Show valuelists in main solution"
→ setContext({context: "active"})
→ getValueLists({scope: "current"})
```

---

### Workflow 6: Update/Delete ValueLists

**Update properties:**
```
User: "Deprecate the old_list"
→ openValueList({
    name: "old_list",
    properties: {"deprecated": "Use new_list instead"}
  })
```

**Delete single:**
```
deleteValueLists({names: ["old_list"]})
```

**Delete multiple:**
```
deleteValueLists({names: ["temp1", "temp2", "deprecated"]})
```

---

## CRITICAL RULES

1. **4 Valuelist Types**: CUSTOM (array), DATABASE table (dataSource), DATABASE related (relationName), GLOBAL_METHOD (globalMethod)
2. **Type selection**: User provides values → CUSTOM; mentions table → DATABASE; mentions relation → RELATED; wants dynamic → GLOBAL_METHOD
3. **Database server name**: If not provided, STOP and ASK user - NEVER guess
4. **DataSource format**: User provides `server_name/table_name` (tool auto-adds `db:/` prefix)
5. **Context**: Check/set BEFORE creating if user mentions module
6. **Display vs Return**: One column (same) vs two columns (display/store different)
7. **Properties**: Optional - start simple, add only when user specifies behavior
8. **Scope parameter**: Use `scope: "current"` to filter by context, default is `"all"`
9. **Global methods**: Must return JSDataSet, format is "scopes.scopeName.methodName"
10. **Tool restrictions**: Use ONLY the 5 tools listed - NO file system/search tools

---

## COMPREHENSIVE EXAMPLES

**Example 1: Simple custom in active solution**
```
User: "Create status list with Active, Inactive, Pending"
→ openValueList({
    name: "status_list",
    customValues: ["Active", "Inactive", "Pending"]
  })
```

**Example 2: Custom in module**
```
User: "Create payment methods list in Module_A: Cash, Credit Card, Check"
→ setContext({context: "Module_A"})
→ openValueList({
    name: "payment_methods",
    customValues: ["Cash", "Credit Card", "Check"]
  })
```

**Example 3: Database (same column for display/storage)**
```
User: "Create countries list from countries table, show country codes"
→ Ask: "What's your database server name?"
User: "example_data"
→ openValueList({
    name: "countries_list",
    dataSource: "example_data/countries",
    displayColumn: "country_code"
  })
```

**Example 4: Database (different display/storage) with properties**
```
User: "Create countries list showing names but storing codes, sorted alphabetically"
→ Ask: "What's your database server name?"
User: "example_data"
→ openValueList({
    name: "countries_list",
    dataSource: "example_data/countries",
    displayColumn: "country_name",
    returnColumn: "country_code",
    properties: {
      "sortOptions": "country_name asc",
      "lazyLoading": true,
      "addEmptyValue": true
    }
  })
```

**Example 5: Related valuelist**
```
User: "Create list showing orders for current customer"
→ openValueList({
    name: "customer_orders",
    relationName: "customers_to_orders",
    displayColumn: "order_number",
    returnColumn: "order_id"
  })
```

**Example 6: Global method valuelist**
```
User: "Create dynamic countries list from our API"
→ Ask: "What global method returns the countries?"
User: "scopes.globals.getCountriesFromAPI"
→ openValueList({
    name: "api_countries",
    globalMethod: "scopes.globals.getCountriesFromAPI"
  })
```

**Example 7: List with scope in module**
```
User: "Show valuelists in Module_B only"
→ setContext({context: "Module_B"})
→ getValueLists({scope: "current"})
→ [Format per presentation rules]
```

**Example 8: Update existing**
```
User: "Deprecate status_list"
→ openValueList({
    name: "status_list",
    properties: {"deprecated": "Use status_list_v2 instead"}
  })
```

**Example 9: Delete multiple**
```
User: "Delete temp_list1, temp_list2, and old_list"
→ deleteValueLists({names: ["temp_list1", "temp_list2", "old_list"]})
```

**Example 10: Discovery workflow**
```
User: "Create valuelist from customers table but I don't know the columns"
→ Ask: "What's your database server name?"
User: "example_data"
→ getTableInfo({serverName: "example_data", tableName: "customers"})
→ Show columns: customer_id, customer_name, company_name, etc.
User: "Show company names but store customer IDs"
→ openValueList({
    name: "customers_list",
    dataSource: "example_data/customers",
    displayColumn: "company_name",
    returnColumn: "customer_id"
  })
```

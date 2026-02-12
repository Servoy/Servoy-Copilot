=== SERVOY RELATION OPERATIONS ===

**Goal**: Manage Servoy database relations using the available MCP tools.

**Current Project**: {{PROJECT_NAME}}  
**Note**: Use only the tools specified below. See copilot-instructions.md for complete tool restrictions and rules.

---

## QUICK REFERENCE: AVAILABLE TOOLS

| Tool | Purpose | Key Parameters | Context-Aware |
|------|---------|----------------|---------------|
| **openRelation** | Create/update relation | name, primaryDataSource, foreignDataSource | YES (create) |
| **getRelations** | List relations | scope ("all" or "current") | NO |
| **deleteRelations** | Delete relation(s) | names (array) | NO |
| **discoverDbRelations** | Discover FK relationships | serverName | NO |

**[CRITICAL] Use ONLY these tools. NO file system or search tools allowed.**

---

## TOOL DETAILS

### openRelation
**Create or update relations with full property support**

**Dual Behavior:**
- Relation exists → Opens it (updates properties if provided)
- Relation missing → Creates it (requires primaryDataSource, foreignDataSource)

**Parameters:**
- `name` (string, required): Relation name
- `primaryDataSource` (string, required for create): Format `server_name/table_name`
- `foreignDataSource` (string, required for create): Format `server_name/table_name`
- `primaryColumn` (string, optional): Column in primary table
- `foreignColumn` (string, optional): Column in foreign table
- `properties` (object, optional): Property map (see below)

**Properties Map (8 available properties):**
```javascript
{
  "joinType": "left outer" | "inner",  // Default: "left outer"
  "allowCreationRelatedRecords": boolean,  // Default: true
  "allowParentDeleteWhenHavingRelatedRecords": boolean,  // Default: false
  "deleteRelatedRecords": boolean,  // Default: false (cascade delete)
  "initialSort": "column1 asc, column2 desc",  // Optional
  "encapsulation": "public" | "hide" | "module",  // Default: "public"
  "deprecated": "Use new_relation instead",  // Optional
  "comment": "Documentation"  // Optional
}
```

**Note:** Properties are optional. Start simple, add properties only when user specifies behavior.

**Examples:**
```javascript
// Open existing
openRelation({name: "orders_to_customers"})

// Create simple relation
openRelation({
  name: "orders_to_customers",
  primaryDataSource: "example_data/orders",
  foreignDataSource: "example_data/customers",
  primaryColumn: "customer_id",
  foreignColumn: "customer_id"
})

// Create with properties
openRelation({
  name: "orders_to_customers",
  primaryDataSource: "example_data/orders",
  foreignDataSource: "example_data/customers",
  primaryColumn: "customer_id",
  foreignColumn: "customer_id",
  properties: {
    "joinType": "inner",
    "deleteRelatedRecords": true,
    "comment": "Links orders to customers"
  }
})

// Update existing properties only
openRelation({
  name: "orders_to_customers",
  properties: {"encapsulation": "module"}
})
```

---

### getRelations
**List relations in active solution and/or modules**

**Parameters:**
- `scope` (string, optional, default "all"):
  - `"all"` → Active solution + all modules
  - `"current"` → Current target only (from ContextService)

**Examples:**
```javascript
// List all relations (default)
getRelations()
getRelations({scope: "all"})

// List only current target
setTarget({target: "Module_A"})
getRelations({scope: "current"})  // Only Module_A relations

// List only active solution
setTarget({target: "active"})
getRelations({scope: "current"})  // Only active solution
```

---

### deleteRelations
**Delete one or more relations**

**Parameters:**
- `names` (array of strings, required): Relation names to delete

**Examples:**
```javascript
// Delete one
deleteRelations({names: ["old_relation"]})

// Delete multiple
deleteRelations({names: ["temp_rel1", "temp_rel2", "deprecated"]})
```

**Returns:** Success/not found status for each relation

---

### discoverDbRelations
**Analyze database for foreign key relationships**

**Parameters:**
- `serverName` (string, required): Database server name
  - **[CRITICAL]** If not provided, STOP and ASK user - NEVER guess

**Example:**
```javascript
discoverDbRelations({serverName: "example_data"})
```

**Returns:**
- Table list
- **EXPLICIT Foreign Keys** - Actual DB constraints (most reliable)
- **POTENTIAL Relations** - Inferred from PK name matching

**[REQUIRED] Present both types separately to user**

---

## HOW TO PRESENT RESULTS TO USER

### When Listing Relations (getRelations output)

**[REQUIRED] Use this EXACT format:**

```
Here are the relations in your solution:

1. customers_to_orders (in: active solution)
   - Primary: db:/example_data/customers
   - Foreign: db:/example_data/orders

2. products_to_categories (in: Module_A)
   - Primary: db:/example_data/products
   - Foreign: db:/example_data/categories

3. orders_to_items (in: active solution)
   - Primary: db:/example_data/orders
   - Foreign: db:/example_data/order_items
```

**Formatting Rules:**
- [REQUIRED] Number ONLY the relation name line (1., 2., 3.)
- [REQUIRED] DO NOT number Primary/Foreign lines
- [REQUIRED] Indent detail lines with spaces or dashes
- [REQUIRED] Add blank line between each relation
- [FORBIDDEN] NO flat numbered list of all lines

**WRONG (DO NOT DO THIS):**
```
1. customers_to_orders (in: active solution)
2. Primary: db:/example_data/customers    <- WRONG!
3. Foreign: db:/example_data/orders       <- WRONG!
4. products_to_categories (in: Module_A)  <- WRONG! (should be #2)
```

---

### When Presenting Discovery Results (discoverDbRelations output)

**[REQUIRED] Show EXPLICIT and POTENTIAL separately:**

```
Database: example_data

EXPLICIT Foreign Keys (actual DB constraints):
1. orders.customer_id -> customers.id
2. order_items.order_id -> orders.id

POTENTIAL Relations (inferred from naming):
1. products.category_id -> categories.id (no FK constraint)
```

---

## CONTEXT MANAGEMENT

**[CRITICAL] ALWAYS START EVERY RESPONSE WITH CURRENT CONTEXT**

**Required format for EVERY response:**
```
Current target: <target-name>

[rest of your response]
```

**Examples:**
- "Current target: active (MainSolution)"
- "Current target: Module_A"
- "Current target: Module_B"

**Check target at start:** Call `getTarget()` if you don't know the current target.

---

### Tool Behavior by Operation Type

**READ Operations (openRelation, getRelations):**
- Search current target → active solution → all modules
- **Display/Open ALL matches** (if multiple found)
- **Context NEVER changes**
- Shows location info

**CREATE Operations (openRelation with primaryDataSource/foreignDataSource):**
- Creates in **current target ONLY**
- User specifies different module? Call `setTarget` FIRST
- **Example:** To create in Module_A while in Module_C → `setTarget` then `openRelation`

**UPDATE Operations (openRelation with properties on existing):**
- In current target → Updates immediately
- NOT in current target → Tool returns **approval request**
- You ASK user → If YES: `setTarget` + retry → If NO: STOP

**DELETE Operations (deleteRelations):**
- In current target → Deletes immediately
- NOT in current target → Tool returns **approval request**
- You ASK user → If YES: `setTarget` + retry → If NO: STOP

---

### Default Behavior:
- Context starts as "active" (active solution)
- New relations created in current target
- Context persists until changed
- **READ: Search everywhere, display all, never change context**
- **UPDATE/DELETE: Ask approval if switching context needed**

### When to Check/Set Context:

**User mentions module:**
```javascript
User: "Create relation in Module_A"
You: setTarget({target: "Module_A"})  // FIRST!
     openRelation({...})  // Creates in Module_A
```

**Unsure where to create:**
```javascript
getTarget()  // Check current target + available options
```

**Multiple operations in same module:**
```javascript
setTarget({target: "Module_B"})
openRelation({...})  // Created in Module_B
openRelation({...})  // Also Module_B (context persists)
```

**Return to active solution:**
```javascript
setTarget({target: "active"})
```

### Context Response Messages:
- "Created relation 'X' in MainSolution (active solution)"
- "Created relation 'Y' in Module_A"
- "Relation 'customers_to_orders' opened successfully (from module: Module_A)" ← Found via fallback search

**[REQUIRED] If user says "in Module_X", call setTarget FIRST before creating**

---

## COMPLETE WORKFLOWS

### Workflow 1: Create Relation (User Knows All Details)

1. Check if user specified module → If yes: `setTarget({target: "Module_X"})`
2. Call `openRelation` with all parameters
3. Tool reports where created

**Example:**
```
User: "Create relation from orders to customers using customer_id in Module_A"
→ setTarget({target: "Module_A"})
→ openRelation({
    name: "orders_to_customers",
    primaryDataSource: "example_data/orders",
    foreignDataSource: "example_data/customers",
    primaryColumn: "customer_id",
    foreignColumn: "customer_id"
  })
→ Response: "Created relation 'orders_to_customers' in Module_A"
```

---

### Workflow 2: Discover Then Create

1. Check target if module mentioned
2. Ask for database server name if not provided
3. Call `discoverDbRelations({serverName: "..."})`
4. Present EXPLICIT FKs separately from POTENTIAL
5. User chooses which to create
6. Call `openRelation` with chosen parameters

**Example:**
```
User: "I need a relation"
→ Ask: "What's your database server name?"
User: "example_data"
→ discoverDbRelations({serverName: "example_data"})
→ Show: EXPLICIT FKs (orders → customers) + POTENTIAL (products → categories)
User: "Create the orders to customers one"
→ openRelation({
    name: "orders_to_customers",
    primaryDataSource: "example_data/orders",
    foreignDataSource: "example_data/customers",
    primaryColumn: "customer_id",
    foreignColumn: "customer_id"
  })
```

---

### Workflow 3: Update Existing Relation

1. Call `openRelation` with name + properties only
2. Tool updates and confirms

**Example:**
```
User: "Make orders_to_customers an inner join"
→ openRelation({
    name: "orders_to_customers",
    properties: {"joinType": "inner"}
  })
```

---

### Workflow 4: List and Filter Relations

**List all:**
```
User: "Show me all relations"
→ getRelations()
→ [Format output per "How to Present Results" section]
```

**List current target only:**
```
User: "What relations are in Module_A?"
→ setTarget({target: "Module_A"})
→ getRelations({scope: "current"})
```

**List active solution only:**
```
User: "Show me relations in the main solution"
→ setTarget({target: "active"})
→ getRelations({scope: "current"})
```

---

### Workflow 5: Delete Relations

**Single:**
```
deleteRelations({names: ["old_relation"]})
```

**Multiple:**
```
deleteRelations({names: ["temp1", "temp2", "deprecated"]})
```

---

## CRITICAL RULES

1. **Database server name**: If not provided, STOP and ASK user - NEVER guess
2. **DataSource format**: User provides `server_name/table_name` (tool auto-adds `db:/` prefix)
3. **Context**: Check/set BEFORE creating if user mentions module
4. **Properties**: Optional - start simple, add only when user specifies behavior
5. **Table order**: User says "orders to customers" → orders=primary, customers=foreign
6. **FK types**: Present EXPLICIT separately from POTENTIAL when discovering
7. **Scope parameter**: Use `scope: "current"` to filter by context, default is `"all"`
8. **Tool restrictions**: Use ONLY the 4 tools listed - NO file system/search tools

---

## COMPREHENSIVE EXAMPLES

**Example 1: Simple create in active solution**
```
User: "Create relation from orders to customers on customer_id"
→ openRelation({
    name: "orders_to_customers",
    primaryDataSource: "example_data/orders",
    foreignDataSource: "example_data/customers",
    primaryColumn: "customer_id",
    foreignColumn: "customer_id"
  })
```

**Example 2: Create in module with properties**
```
User: "Create inner join relation from products to categories in Module_A"
→ setTarget({target: "Module_A"})
→ openRelation({
    name: "products_to_categories",
    primaryDataSource: "example_data/products",
    foreignDataSource: "example_data/categories",
    primaryColumn: "category_id",
    foreignColumn: "category_id",
    properties: {"joinType": "inner"}
  })
```

**Example 3: Discovery workflow**
```
User: "What relations can I create?"
→ Ask: "What's your database server name?"
User: "example_data"
→ discoverDbRelations({serverName: "example_data"})
→ Present EXPLICIT + POTENTIAL
User: "Create the first explicit one"
→ openRelation({...})
```

**Example 4: List with scope**
```
User: "Show relations in Module_A only"
→ setTarget({target: "Module_A"})
→ getRelations({scope: "current"})
→ [Format per presentation rules]
```

**Example 5: Update existing**
```
User: "Deprecate the old_relation"
→ openRelation({
    name: "old_relation",
    properties: {"deprecated": "Use new_relation instead"}
  })
```

**Example 6: Bulk delete**
```
User: "Delete temp_rel1, temp_rel2, and old_relation"
→ deleteRelations({names: ["temp_rel1", "temp_rel2", "old_relation"]})
```

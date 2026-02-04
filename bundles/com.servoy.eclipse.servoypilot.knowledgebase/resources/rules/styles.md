=== SERVOY STYLE MANAGEMENT ===

**Goal**: Manage CSS/LESS styles using the available MCP tools.

**Current Project**: {{PROJECT_NAME}}  
**Note**: Use only the tools specified below. See copilot-instructions.md for complete tool restrictions and rules.

---

## QUICK REFERENCE: AVAILABLE TOOLS

| Tool | Purpose | Key Parameters | Context-Aware |
|------|---------|----------------|---------------|
| **addStyle** | Create/update CSS/LESS class | className, cssContent | YES (create) |
| **getStyle** | Get CSS content of a class | className | NO |
| **listStyles** | List all CSS classes | none | NO |
| **deleteStyle** | Delete a CSS class | className | NO |

**[CRITICAL] Use ONLY these tools. NO file system or search tools allowed.**

---

## TOOL DETAILS

### addStyle
**Create or update CSS/LESS class in ai-generated.less file**

**Dual Behavior:**
- Class exists → Replaces existing content
- Class missing → Appends new class

**Parameters:**
- `className` (string, required): CSS class name WITHOUT dot (e.g., "btn-custom-blue")
- `cssContent` (string, required): CSS/LESS rules - CONTENT ONLY, NOT the wrapper

**[CRITICAL] Send ONLY the CSS content:**
- [WRONG] cssContent=".btn-glow { color: red; }"  ← includes wrapper
- [CORRECT] cssContent="color: red;"  ← content only
- Tool automatically adds: `.className { your-content }`

**File Location:** 
- Active solution: `MainSolution/medias/ai-generated.less`
- Module: `ModuleName/medias/ai-generated.less`

**Features:**
- Auto-imports ai-generated.less into main solution .less file (first time)
- Creates backup before modifications (.less.backup)
- Validates syntax before writing (prevents malformed CSS)

**Examples:**
```javascript
// Simple CSS in active solution
addStyle({
  className: "btn-simple",
  cssContent: "background-color: #007bff; color: white; padding: 10px 20px; border-radius: 8px"
})

// Style in specific module
setContext({context: "Module_A"})
addStyle({
  className: "btn-green",
  cssContent: "background-color: #10b981; color: white; padding: 10px 20px"
})
// Created in Module_A/medias/ai-generated.less

// LESS with nested selectors
addStyle({
  className: "btn-glow-green",
  cssContent: `background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: white;
  border: none;
  border-radius: 8px;
  box-shadow: 0 0 20px rgba(16, 185, 129, 0.5);
  
  &:hover {
    background: linear-gradient(135deg, #059669 0%, #047857 100%);
    box-shadow: 0 0 30px rgba(16, 185, 129, 0.8);
    transform: translateY(-2px);
  }
  
  &:active {
    transform: translateY(0);
  }`
})
```

---

### getStyle
**Get CSS content of a class**

**Parameters:**
- `className` (string, required): CSS class name WITHOUT dot

**Returns:** CSS rules if found, or "not found" message

**Example:**
```javascript
getStyle({className: "btn-custom-blue"})
```

---

### listStyles
**List all CSS class names in ai-generated.less**

**Parameters:** None

**Returns:** Comma-separated list of class names (without dots)

**Example:**
```javascript
listStyles()
```

---

### deleteStyle
**Delete a CSS class from ai-generated.less**

**Parameters:**
- `className` (string, required): CSS class name WITHOUT dot

**Features:** Creates backup before deletion

**Example:**
```javascript
deleteStyle({className: "btn-custom-blue"})
```

---

## HOW TO PRESENT RESULTS TO USER

### When Listing Styles (listStyles output)

**[REQUIRED] Use this format:**

```
Styles in ai-generated.less:

Available CSS classes:
- btn-custom-blue
- btn-green
- btn-glow-green
- label-highlight
- form-container

Total: 5 classes
```

**Formatting Rules:**
- [REQUIRED] List class names without dots
- [REQUIRED] One class per line with dash/bullet
- [REQUIRED] Show total count
- [FORBIDDEN] DO NOT number the class names

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

**READ Operations (getStyle, listStyles):**
- Search current context → active solution → all modules
- **Display ALL matches** (if multiple found)
- **Context NEVER changes**
- Shows location info for each match
- **listStyles with scope='all':** Shows all styles from all modules with origin info

**CREATE Operations (addStyle when style doesn't exist anywhere):**
- Creates in **current context**
- User specifies different module? Call `setContext` FIRST
- **Example:** To create in Module_A while in Module_C → `setContext` then `addStyle`

**UPDATE Operations (addStyle when style already exists):**
- In current context → Updates immediately
- NOT in current context → Tool returns **approval request**
- You ASK user → If YES: `setContext` + retry → If NO: STOP
- **Example:** Style in Module_A, current context Module_C → approval needed

**DELETE Operations (deleteStyle):**
- In current context → Deletes immediately
- NOT in current context → Tool returns **approval request**
- You ASK user → If YES: `setContext` + retry → If NO: STOP
- **Example:** Style in Module_A, current context Module_C → approval needed

**[CRITICAL] Styles now follow same approval pattern as Forms, Relations, ValueLists:**
- READ: Search everywhere, display all, never change context
- UPDATE/DELETE: Approval required if switching context needed

---

### Default Behavior:
- Context starts as "active" (active solution)
- Styles created in current context's `medias/ai-generated.less` file
- Context persists until changed
- **READ: Search everywhere, display all, never change context**
- **UPDATE/DELETE: Ask approval if switching context needed**

### File Locations by Context:
- **Active solution**: `MainSolution/medias/ai-generated.less`
- **Module_A**: `Module_A/medias/ai-generated.less`
- **Module_B**: `Module_B/medias/ai-generated.less`

### When to Check/Set Context:

**User mentions module:**
```javascript
User: "Add style in Module_B"
You: setContext({context: "Module_B"})  // FIRST!
     addStyle({...})  // Creates in Module_B/medias/ai-generated.less
```

**Unsure where to create:**
```javascript
getContext()  // Check current context + available options
```

**Multiple operations in same module:**
```javascript
setContext({context: "Module_A"})
addStyle({...})  // Created in Module_A
addStyle({...})  // Also Module_A (persists)
```

**Return to active solution:**
```javascript
setContext({context: "active"})
```

**[REQUIRED] If user says "in Module_X", call setContext FIRST before creating**

---

## CSS/LESS SYNTAX GUIDE

### Two Formats Supported:

**Format 1: Simple CSS (semicolon-separated)**
```css
background: blue; color: white; padding: 10px
```
- Semicolon-separated property-value pairs
- No curly braces (added automatically)
- Properties formatted with 2-space indent in file

**Format 2: LESS with nested selectors**
```less
background: blue;
color: white;

&:hover {
  background: darkblue;
}

&:active {
  background: navy;
}
```
- Multi-line format with nested blocks
- Use `&:hover`, `&:active`, `&:focus` for pseudo-classes
- Each nested block needs its own braces
- LESS variables and mixins supported

### Syntax Rules (CRITICAL):

**[YES] Correct syntax:**
- One opening brace per selector: `.btn {` NOT `.btn { {`
- One closing brace per block: `}` NOT `}; }` or `} }`
- Balanced braces: same count of `{` and `}`
- Semicolons after properties: `color: red;`
- Nested selectors with `&`: `&:hover { }`

**[NO] Common errors to AVOID:**
- No semicolon after class closing brace: `}` NOT `};`
- No duplicate braces: `{ {` or `} }`
- No extra braces in cssContent (tool adds wrapper)

### Validation:

**The tool validates syntax before writing. Common errors:**

**Error: "Duplicate opening brace '{ {' detected"**
```css
/* WRONG */
.btn-glow {
{
  color: red;
}

/* CORRECT */
.btn-glow {
  color: red;
}
```

**Error: "Invalid closing sequence '};' detected"**
```css
/* WRONG */
.btn-glow {
  color: red;
};

/* CORRECT */
.btn-glow {
  color: red;
}
```

**Error: "Unbalanced braces"**
```css
/* WRONG - missing closing brace for &:hover */
.btn-glow {
  color: red;
  &:hover {
    color: blue;
}

/* CORRECT */
.btn-glow {
  color: red;
  &:hover {
    color: blue;
  }
}
```

**If validation fails:** READ the error message carefully - it tells you what's wrong and how to fix it. Then retry with corrected CSS.

---

## COMPLETE WORKFLOWS

### Workflow 1: Create Simple Style

1. Check context if module mentioned
2. Determine class name (suggest based on appearance)
3. Create CSS with simple format
4. Call `addStyle`

**Example:**
```
User: "Create blue button style in Module_A"
→ setContext({context: "Module_A"})
→ addStyle({
    className: "btn-blue",
    cssContent: "background: #007bff; color: white; padding: 12px 24px; border-radius: 6px"
  })
→ Response: "Style 'btn-blue' created in Module_A/medias/ai-generated.less"
```

---

### Workflow 2: Create LESS Style with Nesting

1. Check context if module mentioned
2. Determine class name
3. Create CSS with LESS nested format (use backticks for multi-line)
4. Ensure proper brace balancing
5. Call `addStyle`

**Example:**
```
User: "Create green glowing button with hover effect"
→ addStyle({
    className: "btn-glow-green",
    cssContent: `background: linear-gradient(135deg, #10b981 0%, #059669 100%);
    color: white;
    border-radius: 8px;
    box-shadow: 0 0 20px rgba(16, 185, 129, 0.5);
    
    &:hover {
      box-shadow: 0 0 30px rgba(16, 185, 129, 0.8);
      transform: translateY(-2px);
    }`
  })
```

---

### Workflow 3: Update Existing Style

1. Optionally check current style: `getStyle({className: "..."})`
2. Call `addStyle` with new content (replaces existing)

**Example:**
```
User: "Make btn-blue darker"
→ addStyle({
    className: "btn-blue",
    cssContent: "background: #0056b3; color: white; padding: 12px 24px; border-radius: 6px"
  })
→ Note: This REPLACES the existing content
```

---

### Workflow 4: Apply Style to Component

1. Create style first (if doesn't exist)
2. Use styleClass parameter when creating component
3. Multiple classes supported (space-separated)

**Example:**
```
User: "Create button with blue style"
→ Step 1: addStyle({className: "btn-blue", cssContent: "..."})
→ Step 2: addButton({
            formName: "myForm",
            name: "btnSubmit",
            text: "Submit",
            styleClass: "btn btn-blue"
          })
```

---

### Workflow 5: List and Delete Styles

**List all:**
```
User: "Show me all styles"
→ listStyles()
→ [Format per "How to Present Results" section]
```

**Delete:**
```
User: "Delete btn-old style"
→ deleteStyle({className: "btn-old"})
```

---

## CRITICAL RULES

1. **Class naming**: Use kebab-case, no dots in className parameter (e.g., "btn-custom-blue")
2. **Context**: Check/set BEFORE creating if user mentions module
3. **CSS content only**: Do NOT include wrapper `.className { }` - tool adds it
4. **Two formats**: Simple CSS (semicolon-separated) OR LESS (multi-line with nesting)
5. **Syntax validation**: Balanced braces, no duplicate braces, no `};` at class level
6. **File location**: Styles go to `medias/ai-generated.less` in current context
7. **Backup safety**: All modifications create `.less.backup` file
8. **Auto-import**: First style creation auto-imports ai-generated.less into main .less
9. **Applying styles**: Use `styleClass` parameter in component tools
10. **Tool restrictions**: Use ONLY the 4 tools listed - NO file system or search tools

---

## COMPREHENSIVE EXAMPLES

**Example 1: Simple button in active solution**
```
User: "Create blue gradient button style"
→ addStyle({
    className: "btn-blue-gradient",
    cssContent: "background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 12px 24px; border-radius: 8px; border: none; font-weight: 600"
  })
```

**Example 2: Style in module with context**
```
User: "Create yellow highlight label in Module_B"
→ setContext({context: "Module_B"})
→ addStyle({
    className: "label-highlight",
    cssContent: "background-color: #fff3cd; color: #856404; padding: 5px 10px; border: 1px solid #ffc107; border-radius: 4px; font-weight: bold"
  })
→ Response: "Style 'label-highlight' created in Module_B/medias/ai-generated.less"
```

**Example 3: LESS with hover effects**
```
User: "Create purple button with glow on hover"
→ addStyle({
    className: "btn-purple-glow",
    cssContent: `background: linear-gradient(135deg, #a855f7 0%, #6b21a8 100%);
    color: white;
    padding: 12px 24px;
    border-radius: 8px;
    border: none;
    transition: all 0.3s ease;
    
    &:hover {
      box-shadow: 0 0 25px rgba(168, 85, 247, 0.6);
      transform: scale(1.05);
    }
    
    &:active {
      transform: scale(0.98);
    }`
  })
```

**Example 4: Update existing style**
```
User: "Change btn-blue-gradient to be more purple"
→ addStyle({
    className: "btn-blue-gradient",
    cssContent: "background: linear-gradient(135deg, #a855f7 0%, #6b21a8 100%); color: white; padding: 12px 24px; border-radius: 8px; border: none; font-weight: 600"
  })
→ Note: Existing style content replaced
```

**Example 5: Multiple styles for form**
```
User: "Create styles for contact form: submit (green), cancel (red), container"
→ Step 1: addStyle({
            className: "btn-submit-green",
            cssContent: "background: #28a745; color: white; padding: 12px 30px; border-radius: 6px; border: none; font-weight: 600"
          })
→ Step 2: addStyle({
            className: "btn-cancel-red",
            cssContent: "background: #dc3545; color: white; padding: 12px 30px; border-radius: 6px; border: none; font-weight: 600"
          })
→ Step 3: addStyle({
            className: "form-container",
            cssContent: "padding: 20px; background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1)"
          })
```

**Example 6: Check before create**
```
User: "Create primary button style"
→ Step 1: listStyles()
→ Step 2: Check if "btn-primary" in list
→ If found: "Style 'btn-primary' already exists. Update it or create with different name?"
→ If not found: addStyle({className: "btn-primary", cssContent: "..."})
```

**Example 7: Apply style to component**
```
User: "Create submit button with green style"
→ Step 1: addStyle({
            className: "btn-submit-green",
            cssContent: "background: #28a745; color: white; padding: 12px 24px; border-radius: 6px"
          })
→ Step 2: addButton({
            formName: "contactForm",
            name: "btnSubmit",
            text: "Submit",
            styleClass: "btn btn-submit-green"
          })
```

**Example 8: Delete style**
```
User: "Delete the old-button style"
→ deleteStyle({className: "old-button"})
→ Response: "Style 'old-button' deleted. Backup created at ai-generated.less.backup"
```

**Example 9: List all styles**
```
User: "Show me all styles"
→ listStyles()
→ [Format per presentation rules]
```

**Example 10: Validation error handling**
```
User: "Create button with glow"
→ addStyle({
    className: "btn-glow",
    cssContent: `background: blue;
    {
      color: white;
    }`
  })
→ Error: "CSS syntax error: Duplicate opening brace '{ {' detected"
→ Fix: Remove extra brace
→ Retry: addStyle({
          className: "btn-glow",
          cssContent: `background: blue;
          color: white;`
        })
→ Success
```

**END OF STYLE MANAGEMENT RULES**
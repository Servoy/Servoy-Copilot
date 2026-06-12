# Spec: SVY-21110 — Edit/generate i18n messages via skill + context (no MCP)

## 1. Goal

Enable AI agents (opencode/skill4servoy) to add, edit, search, and generate i18n messages with
translations — without building a dedicated MCP tool. The solution is a **skill context document**
(like `media-operations.md`) that teaches the agent the i18n file format, location conventions,
and a workflow for AI-assisted key naming and translation generation.

## 2. Background

### 2.1 Current i18n storage

Servoy stores i18n messages as standard Java `.properties` files inside the resources project:

```
<resources_project>/messages/
├── <serverName>.<tableName>.properties              # default language (empty locale)
├── <serverName>.<tableName>.en.properties           # English
├── <serverName>.<tableName>.de.properties           # German
├── <serverName>.<tableName>.fr_CA.properties        # French (Canada)
└── ...
```

- The `<serverName>` and `<tableName>` come from the solution's `i18nDataSource` property
  in `solution_settings.obj` (format: `db:/<serverName>/<tableName>`).
- File format: standard Java `.properties` — one `key=value` per line, sorted alphabetically by key.
- Keys are plain strings (no UUIDs, no structural integrity concerns).
- **Encoding: ISO-8859-1** — Servoy uses `SortedProperties.store(OutputStream)` which per the Java
  spec always writes ISO-8859-1, escaping non-Latin-1 characters as `\uXXXX`. The agent must
  follow this convention when writing values with non-ASCII characters.

### 2.2 Existing AI code in Servoy Developer

The `I18nComposite` class (`com.servoy.eclipse.ui.dialogs.I18nComposite`) already uses a
`ChatModel` to:
1. **Generate i18n key names** from descriptive text (e.g. "Save" → `button.save`)
2. **Generate translations** for a key in target locales (e.g. key "button.save" → German "Speichern")

The system prompt includes existing keys as context so suggestions stay consistent with the
project's naming conventions.

### 2.3 Why no MCP tool is needed

Unlike media files (which have UUIDs tracked in `medias.obj`), i18n `.properties` files have:
- No UUIDs — keys are plain text identifiers
- No structural cross-references that could break on edit
- No rename integrity requirements (renaming a key is just find-and-replace in `.properties` + code)
- A trivially parseable format (key=value lines)

All required operations map directly to file reads/writes:

| Operation | File-based approach |
|-----------|-------------------|
| List keys | Read `.properties` file(s) |
| Search for a key | `fileSearch` in `*.properties` under `messages/` |
| Add a key | Append to `.properties` file (maintain sorted order) |
| Edit a value | `replaceString` in the `.properties` file |
| Delete a key | Remove line from `.properties` file(s) |
| Add a translation | Add/edit the locale-specific `.properties` file |
| Find i18n config | Read `solution_settings.obj` → `i18nDataSource` |

The `media-operations.md` pattern is the closest analogue: a context document that teaches the
agent the file conventions, and operations are performed through standard file-editing tools.

### 2.4 How the agent discovers i18n configuration

1. Call `servoy-model_getTarget` → get active solution name
2. Read `<solutionName>/solution_settings.obj` → extract `i18nDataSource` field
3. Parse datasource URI `db:/<serverName>/<tableName>` → derive file prefix
4. The resources project is always named `resources` (or discoverable via workspace listing)
5. Files live at: `resources/messages/<serverName>.<tableName>[.<locale>].properties`

### 2.5 Available locales discovery

The agent discovers which locales exist by listing files matching the pattern
`<serverName>.<tableName>.*.properties` in the `messages/` directory. Each extra dot-segment
between the table name and `.properties` is the locale code.

### 2.6 I18n datasource resolution order

The Developer uses a two-level fallback when resolving the i18n server/table:

1. **Solution-level**: `activeSolution.getI18nServerName()` / `getI18nTableName()` from `solution_settings.obj`
2. **Global IDE preferences**: `Settings.getInstance().getProperty("defaultMessagesServer")` /
   `Settings.getInstance().getProperty("defaultMessagesTable")` — set in IDE preferences (I18N configuration page)
3. **Not configured**: If both are empty, there is no i18n table available

This pattern is used consistently in `I18nComposite`, `ShowI18NDialogActionDelegate`, and
`I18NConfigurationBlock`.

**For the agent**: The agent should follow this resolution:
1. Read `solution_settings.obj` → check `i18nDataSource`
2. If `i18nDataSource` is not set, i18n is NOT configured — use the MCP tools (see 3.6) to
   list available tables and set one on the solution (optionally creating a new table).
   The existence of `.properties` files in `resources/messages/` does NOT mean i18n is configured.

### 2.7 MCP tools for i18n setup (only when not configured)

Two small MCP tools are needed to handle the "no i18n configured" scenario — since creating
database tables and modifying solution settings with proper model notifications cannot be done
via file editing alone:

1. **`i18n_listTables`** — Lists existing i18n-compatible tables across all servers that the agent
   can choose from (tables with `message_key`, `message_value`, `message_language` columns).
2. **`i18n_setTable`** — Sets the i18n table on the active solution. Parameters:
   - `serverName` (String) — the database server
   - `tableName` (String) — the table name to use
   - `createIfMissing` (boolean) — if `true`, creates the i18n table before setting it

These tools are only needed when `i18nDataSource` is not set on the solution. Once configured,
all day-to-day i18n operations (add/edit/delete keys and translations) are file-based.

## 3. Design

### 3.1 Context document: `i18n-operations.md`

A new file at `/home/gabi/github_master/skill4servoy/.opencode/skills/servoy-platform/context/i18n-operations.md`
(parallel to the existing `media-operations.md`).

This document will contain:
- File location and naming conventions
- The discovery workflow (solution_settings.obj → datasource URI → file paths)
- Operations: list, search, create, update, delete keys/translations
- Key naming best practices and conventions
- Translation generation guidelines
- Sorted-properties format rules
- Examples of each operation

### 3.2 Skill routing update

Add i18n to the routing table in `servoy-platform/SKILL.md`:

```markdown
| Manage i18n (create/read/update/delete keys/translations) | `solution_settings.obj` + files in `messages/` | `context/i18n-operations.md` |
```

### 3.3 AI-assisted key generation (built into context doc)

The context document includes prompt guidance for the agent itself to:
1. **Suggest i18n key names** based on the text being internationalized:
   - Use lowercase with dots for hierarchy
   - Common prefixes: `button.`, `label.`, `message.`, `error.`, `title.`, `tooltip.`, `placeholder.`
   - Maximum 50 characters
   - Match existing project conventions (read a sample of existing keys first)

2. **Generate translations** for new or missing locales:
   - Read existing translations to understand tone/style
   - Generate contextually appropriate translations
   - Flag uncertainty for complex or domain-specific terms

### 3.4 Workflow: creating a new i18n key with translations

When the agent needs to create an i18n entry (e.g., for a button text):

1. **Discover config**: Read `solution_settings.obj` → `i18nDataSource`
2. **If NOT configured** → STOP. Do NOT add keys to any default messages location. Instead:
   a. Call `i18n_listTables` to list available tables
   b. Ask the user which table to use (or create a new one)
   c. Ask the user which languages/locales they want translations for
   d. Call `i18n_setTable` to configure the solution
   e. Create the initial `.properties` files (default + one per requested locale)
3. **Check for existing key**: Call `i18n_searchMessages(searchValue)` — this searches both
   Servoy platform defaults and the solution's i18n keys in one call. If a matching key is
   found (from either source), reuse it instead of creating a new one.
4. **Generate key name**: Only if no existing key matches. Based on context (component type + text), suggest a key
5. **Write default value**: Add `key=value` to the default `.properties` file (sorted position)
6. **Generate translations for ALL existing locales**: For each locale file that exists, generate
   an AI-suggested translation and add it — never skip existing locales
7. **Update component**: Set the component's text property to `i18n:<key>`

### 3.5 No MCP tool needed for day-to-day i18n — rationale summary

| Concern | Resolution |
|---------|-----------|
| Finding i18n config | Read `solution_settings.obj` (already documented in `servoy-platform`) |
| Listing existing keys | Read/grep `.properties` files |
| Writing keys | Standard file editing tools (sorted insert) |
| Eclipse awareness | `servoy-editor_*` tools trigger Eclipse resource events — the model reloads automatically |
| Key naming AI | The LLM agent generates names directly (no separate AI service needed) |
| Translation AI | The LLM agent generates translations directly |
| Sorted format | Documented in context doc; agent inserts at correct sorted position |

### 3.6 MCP tools for i18n setup

Two MCP tools in `com.servoy.eclipse.developer.mcp` handle the one-time setup when no i18n
table is configured on the solution:

#### `i18n_searchMessages`
- **Parameters**:
  - `searchValue` (String, required) — the text/value to search for (case-insensitive substring match)
- **Returns**: Matching key=value pairs from both sources, grouped by origin:
  - **Platform defaults** — from `ResourceBundle.getBundle("com.servoy.j2db.messages")`
  - **Solution keys** — from the active solution's i18n `.properties` files (via `EclipseMessages`)
- **Purpose**: Before creating a new i18n key, check if a suitable key already exists in either
  the platform defaults (e.g., "servoy.button.ok" for "OK") or the solution's own keys.
  One call replaces both a platform search and a file-based search.

#### `i18n_listTables`
- **Parameters**: none
- **Returns**: List of existing i18n-compatible tables (server + table name) across all configured
  database servers. A table is compatible if it has columns `message_key`, `message_value`, and
  `message_language`.
- **Purpose**: Let the agent present options to the user when choosing which table to use.

#### `i18n_setTable`
- **Parameters**:
  - `serverName` (String, required) — the database server name
  - `tableName` (String, required) — the i18n table name
  - `createIfMissing` (boolean, optional, default `false`) — if `true`, creates the table
    (using `I18NMessagesTable.createMessagesTable()`) before setting it on the solution
- **Returns**: Confirmation with the datasource URI that was set (`db://<serverName>/<tableName>`)
- **Side effects**:
  - Sets `i18nDataSource` on the active solution
  - If `createIfMissing=true`, creates the DB table with standard i18n schema
  - Creates the initial empty `.properties` file(s) in `resources/messages/`

#### Usage workflow (agent):
1. Check `solution_settings.obj` → `i18nDataSource` is empty
2. Call `i18n_listTables` → get available tables
3. If suitable table exists: call `i18n_setTable(serverName, tableName)`
4. If no suitable table: ask user for server/table name, call `i18n_setTable(serverName, tableName, createIfMissing=true)`
5. Proceed with file-based i18n operations

## 4. Implementation plan

1. **Create `i18n-operations.md`** context document at
   `/home/gabi/github_master/skill4servoy/.opencode/skills/servoy-platform/context/i18n-operations.md`
   with:
   - File format specification
   - Discovery workflow
   - CRUD operations (with examples)
   - Key naming conventions and AI generation guidance
   - Translation generation guidance
   - Java properties escaping rules

2. **Update `servoy-platform/SKILL.md`** — add i18n row to the task-routing table (Step 1):
   ```
   | Manage i18n (create/read/update/delete keys/translations) | `solution_settings.obj` + files in `messages/` | `context/i18n-operations.md` |
   ```

3. **Update `servoy-developer/SKILL.md`** — add quick rule for i18n:
   - When creating UI components with text, always check if the solution has `i18nDataSource` set
   - If yes, create an i18n key rather than hardcoding text

4. **Handle "no i18n configured" scenario** in the context doc:
   - Document the two-level fallback resolution (solution → global preferences → not configured)
   - If `solution_settings.obj` has no `i18nDataSource`, check for existing `.properties` files
     in `resources/messages/` to infer the configured prefix
   - If nothing found, use `i18n_listTables` and `i18n_setTable` MCP tools to set up

5. **Implement `i18n_searchMessages` MCP tool** in `com.servoy.eclipse.developer.mcp`:
   - Search platform defaults via `ResourceBundle.getBundle("com.servoy.j2db.messages")`
   - Search solution keys via `EclipseMessages.getDatasourceMessages()` using active solution's `i18nDataSource`
   - Return all matches grouped by source (platform / solution)

6. **Implement `i18n_listTables` MCP tool** in `com.servoy.eclipse.developer.mcp`:
   - Iterate all servers, find tables with i18n-compatible columns
   - Return list of `{serverName, tableName}` pairs

6. **Implement `i18n_setTable` MCP tool** in `com.servoy.eclipse.developer.mcp`:
   - Accept `serverName`, `tableName`, `createIfMissing`
   - If `createIfMissing=true`, call `I18NMessagesTable.createMessagesTable()`
   - Set `i18nDataSource` on the active solution via `solution.setI18nDataSource()`
   - Create initial `.properties` file(s) in `resources/messages/`

7. **Test the workflow** — manually verify that an agent can:
   - Discover the i18n configuration
   - Read existing keys
   - Add a new key with translations
   - Edit an existing translation
   - The Servoy Developer recognizes the changes (resource events fire)

## 5. Acceptance criteria

- [ ] A context document `i18n-operations.md` exists in `servoy-platform/context/`
- [ ] The document covers: discovery, file format, CRUD operations, key naming, translation generation
- [ ] The `servoy-platform/SKILL.md` routes i18n tasks to the new context document
- [ ] MCP tool `i18n_searchMessages` searches both platform defaults and solution keys by value
- [ ] MCP tool `i18n_listTables` returns available i18n-compatible tables
- [ ] MCP tool `i18n_setTable` sets (and optionally creates) the i18n table on the solution
- [ ] An AI agent (using only file-based tools + the context doc) can successfully:
  - [ ] Find the i18n configuration for the active solution
  - [ ] List existing i18n keys and their translations
  - [ ] Add a new i18n key with a default value
  - [ ] Add translations for the new key in available locales
  - [ ] Edit an existing translation
  - [ ] Delete an i18n key from all locale files
- [ ] When no i18n is configured, the agent uses `i18n_listTables` + `i18n_setTable` to set it up
- [ ] The Servoy Developer IDE recognizes changes made via file tools (no stale model)
- [ ] No new MCP tool is required for basic day-to-day i18n operations (only for initial setup)

## 6. Out of scope

- Building a dedicated MCP tool for day-to-day i18n key/translation management (file ops suffice)
- Enhancing `getTarget` MCP to include `i18nDataSource` (agent reads `solution_settings.obj` directly)
- Migrating existing `I18nComposite` AI code to use the skill (it uses its own `ChatModel`)
- Import/export of i18n from/to database (existing IDE functionality)
- Batch translation of all missing keys (could be a future enhancement)
- Renaming keys across all code references (complex find-and-replace across `.js`/`.frm` files — not in scope)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the agent auto-detect project locale conventions (e.g., only `de` vs `de_DE`) from existing files? | PM | open — recommend yes, document as "match existing locale granularity" |

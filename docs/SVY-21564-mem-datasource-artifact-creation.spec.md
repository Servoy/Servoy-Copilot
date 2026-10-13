# Spec: SVY-21564 — MCP createRelation / createValueList / createFormWithFields reject mem:-datasources

## 1. Goal

Make the MCP artifact-creation tools accept in-memory (`mem:`) datasources — and, for
consistency, `view:` datasources — the same way the Servoy designer and runtime already do.
Today `servoy-model_createRelation`, `createValueList` (database type) and
`createFormWithFields` all reject a `mem:<table>` datasource with *"Invalid datasource
format"*, even though the platform (`Solution.createNewRelation`, the datasource manager, the
in-memory server) fully supports mem/view datasources. This is a functional gap in the MCP
wrapper only. The fix makes a single internal helper scheme-aware and resolves the form's
table through the existing datasource manager instead of a hardcoded `db:/` string split, so
all three tools work with `db:/`, `mem:` and `view:` datasources.

## 2. Background

### 2.1 Where the restriction lives

All three affected tools funnel through one private helper in the **active**
`com.servoy.eclipse.developer.mcp` bundle:

`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/ServoyArtifactCreationService.java`,
`correctDataSource(String ds)` (lines 461–470):

```java
private String correctDataSource(String ds) throws RepositoryException
{
    if (ds == null || ds.isBlank()) throw new RepositoryException("Datasource cannot be empty");
    if (!ds.startsWith("db:/"))
    {
        if (ds.contains("/")) return "db:/" + ds;
        throw new RepositoryException("Invalid datasource format: '" + ds + "'. Use 'db:/server_name/table_name'");
    }
    return ds;
}
```

A `mem:customers` datasource starts with `mem:` (not `db:/`) and contains no `/`, so it falls
into the `throw` branch. Callers:

- `createRelation` → lines 156–157 (both primary and foreign datasource).
- `createValueList` (database type) → line 223.
- `createFormWithFields` → line 276.

### 2.2 The second, independent `db:/` assumption in `createFormWithFields`

Even if `correctDataSource` is relaxed, `createFormWithFields` has a hardcoded server/table
split (lines 281–300) that can never resolve a `mem:` datasource:

```java
String[] dsParts = dataSource.replace("db:/", "").split("/");
if (dsParts.length < 2)
    throw new RepositoryException("Invalid datasource format: '" + dataSource + "'. Use 'db:/server_name/table_name'");

IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
        .getServer(dsParts[0], false, false);
if (server == null)
    throw new RepositoryException("Database server '" + dsParts[0] + "' not found");

ITable table;
try { table = server.getTable(dsParts[1]); }
catch (Exception e) { throw new RepositoryException("Error loading table '" + dsParts[1] + "': " + e.getMessage()); }
if (table == null)
    throw new RepositoryException("Table '" + dsParts[1] + "' not found in server '" + dsParts[0] + "'");
```

A mem datasource has the shape `mem:tablename` (no server segment); its table lives on the
in-memory server, not a named database server. So the split yields `dsParts.length < 2` and
throws. `dsParts[0]`/`dsParts[1]` are also reused in the no-columns-found error messages
(line 318) and the table-not-found message (line 300).

### 2.3 The platform already supports mem/view — the MCP wrapper is the outlier

- `DataSourceUtils`
  (`servoy-client/servoy_shared/src/com/servoy/j2db/util/DataSourceUtils.java`) defines
  `INMEM_DATASOURCE = "mem"` / `INMEM_DATASOURCE_SCHEME_COLON = "mem:"`,
  `VIEW_DATASOURCE = "view"` / `VIEW_DATASOURCE_SCHEME_COLON = "view:"`, and
  `isDatasourceUri(String)` (line 259) which accepts `db:/…`, `mem:…` and `view:…`.
- `getDataSourceServerName(ds)` (line 164) returns the db server for `db:/…`, `IServer.INMEM_SERVER`
  for `mem:…`, `IServer.VIEW_SERVER` for `view:…`; `getDataSourceTableName(ds)` (line 183)
  returns the table name for all three schemes. These are the right helpers for human-readable
  error messages.
- The datasource manager (`getDataSourceManager().getDataSource(dataSource)`) already resolves
  mem datasources to the in-memory `MemTable` — the `createRelation` column-mapping block
  (lines 170–171) already uses exactly this call. `createFormWithFields` should resolve its
  table the same way instead of a manual string split.
- `Solution.createNewRelation(...)` (servoy-client `Solution.java:479`) stores whatever
  primary/foreign datasource it is given and does no `db:/` validation, so relations with mem
  datasources are already valid once they get past the MCP wrapper.
- `ensureDbiForDataSource(dataSource)` (line 489) already no-ops for any non-`db:/` datasource
  (`if (dataSource == null || !dataSource.startsWith("db:/")) return;`), so it is safe to keep
  calling it for mem/view datasources — it will simply do nothing.

### 2.4 Scope note — the servoypilot copy is dead code

The ticket also names `RelationService.validateAndCorrectDataSource` (lines 244–264) in
`com.servoy.eclipse.servoypilot`. That bundle is marked **reference-only / no longer actively
developed** in `AGENTS.md`, and the live MCP tools do not route through it (the active
implementation is the ported `ServoyArtifactCreationService`). **This fix targets the active
`com.servoy.eclipse.developer.mcp` bundle only** and leaves the servoypilot copy untouched.

## 3. Design

Follow **Approach 1** from the triage report: make the datasource handling scheme-aware and
resolve the form table through the datasource manager.

### 3.1 Make `correctDataSource` scheme-aware

Replace the `db:/`-only check so that:

1. a blank/null datasource still throws (`"Datasource cannot be empty"`);
2. a `mem:…` or `view:…` datasource is passed through **unchanged** (recognised via the
   `DataSourceUtils` scheme constants / `isDatasourceUri`);
3. a `db:/…` datasource is passed through unchanged (existing behaviour);
4. a bare `server/table` (contains `/`, no recognised scheme) is still auto-corrected to
   `db:/server/table` (existing convenience behaviour);
5. anything else still throws `"Invalid datasource format"`, but with a message that mentions
   the supported schemes.

Proposed implementation (uses `DataSourceUtils` scheme constants; import
`com.servoy.j2db.util.DataSourceUtils`):

```java
private String correctDataSource(String ds) throws RepositoryException
{
    if (ds == null || ds.isBlank()) throw new RepositoryException("Datasource cannot be empty");
    // mem: and view: datasources are valid as-is
    if (ds.startsWith(DataSourceUtils.INMEM_DATASOURCE_SCHEME_COLON)
        || ds.startsWith(DataSourceUtils.VIEW_DATASOURCE_SCHEME_COLON))
    {
        return ds;
    }
    if (!ds.startsWith("db:/"))
    {
        if (ds.contains("/")) return "db:/" + ds;
        throw new RepositoryException("Invalid datasource format: '" + ds +
            "'. Use 'db:/server_name/table_name', 'mem:table_name' or 'view:name'");
    }
    return ds;
}
```

This immediately fixes `createRelation` and `createValueList` (database type), which only
depend on `correctDataSource`.

### 3.2 Resolve the table via the datasource manager in `createFormWithFields`

Drop the `dataSource.replace("db:/", "").split("/")` block (lines 281–300) and resolve the
table through the already-used datasource manager, which returns a `MemTable` for `mem:` and
the db table for `db:/`. Derive human-readable server/table names from `DataSourceUtils` for
error messages:

```java
ITable table = ServoyModelFinder.getServoyModel().getDataSourceManager().getDataSource(dataSource);
if (table == null)
{
    String serverName = DataSourceUtils.getDataSourceServerName(dataSource);
    String tableName = DataSourceUtils.getDataSourceTableName(dataSource);
    throw new RepositoryException("Table '" + tableName + "' not found" +
        (serverName != null ? " in server '" + serverName + "'" : "") +
        " for datasource '" + dataSource + "'");
}
```

Notes:
- Keep the `ensureDbiForDataSource(dataSource)` call (line 302) — it already no-ops for
  non-`db:/` datasources.
- The two existing uses of `dsParts[1]` in the "None of the specified columns found in table
  '…'" message (line 318) must be replaced with the `DataSourceUtils.getDataSourceTableName(...)`
  value (compute it once, reuse it). Resolve the table-name string before the column loop so it
  is available for that message.
- `ApplicationServerRegistry` / `IServerInternal` imports become unused in this method; remove
  them only if no other method in the file still uses them (verify with the compiler — see §4).
- The rest of `createFormWithFields` (`table.getColumns()`, `createNewForm(..., dataSource, ...)`,
  component generation) is datasource-agnostic and needs no change; `createNewForm` already
  takes the datasource string directly.

### 3.3 Surface column-mapping failures in `createRelation`

`createRelation` already resolves the key columns through the datasource manager
(`getDataSourceManager().getDataSource(ds)` → `table.getColumn(col)`), introduced in the
original service (commit `35a68804a`). When either table or column resolves to `null`, the
pre-existing code **silently skipped** the mapping: the relation was still saved and the tool
reported `"created successfully"`, but with no `RelationItems`. In the relation editor this
shows as `-unresolved (<col>)-`, with no indication to the caller that the mapping was dropped.

This is confusing specifically for `mem:` datasources, which are the newly-enabled case: a mem
table whose schema the datasource manager cannot resolve at design time yields a "successful"
relation with unresolved columns and no explanation.

Make the skip explicit without changing the save behaviour (a relation with no items is still
valid and editable). Capture the reason the mapping could not be applied and append it as a
`WARNING:` line to the tool's result string:

- table not resolved → `"Could not resolve table(s) for column mapping: table not found for
  '<ds>'. Relation saved without column mapping — add it manually in the relation editor."`
- column not found → `"Could not resolve column(s) for mapping: '<col>' not found in <ds>.
  Relation saved without column mapping — add it manually in the relation editor."`
- exception → the exception message plus the same guidance (still logged via
  `ServoyLog.logWarning`).

The relation is still persisted in every case; only the result text gains the warning. This
keeps `createRelation` honest: the caller is told the mapping was skipped and why, instead of a
misleading unqualified "success".

### 3.4 Git history

From the triage report (confirmed, no regression to revert):

- `correctDataSource` was introduced by commit `35a68804a` (marianvid, 2026-05-29) and has not
  changed since — the `db:/`-only restriction is an original design choice in the ported MCP
  service, not a later regression.
- Recent edits to `ServoyArtifactCreationService.java` (`e392c5e8`, `5b1ee8aa`, `8c3da824`)
  touched skilltest setup and the `createForm` columns/navigator defaults, not datasource
  validation.
- The platform-side mem/view support (`DataSourceUtils.INMEM_DATASOURCE`,
  `Solution.createNewRelation`'s unvalidated datasource handling) is long-standing, confirming
  the MCP wrapper is the outlier.

## 4. Implementation plan

All changes are in the single file
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/ServoyArtifactCreationService.java`.

1. Add the import `com.servoy.j2db.util.DataSourceUtils` (place it with the other
   `com.servoy.j2db.*` imports; mind the MANIFEST already allows `com.servoy.j2db.util`).
2. Rewrite `correctDataSource` to pass `mem:`/`view:` datasources through unchanged, keep the
   `db:/server/table` and bare `server/table` → `db:/…` behaviour, and update the error message
   to list the supported schemes (§3.1).
3. In `createFormWithFields`, replace the `dsParts` split + `ApplicationServerRegistry` server /
   table lookup (lines 281–300) with a `getDataSourceManager().getDataSource(dataSource)` call
   plus a `DataSourceUtils`-derived error message (§3.2).
4. Update the "None of the specified columns found in table '…'" message (line 318) to use the
   `DataSourceUtils.getDataSourceTableName(dataSource)` value instead of `dsParts[1]`.
5. Remove now-unused imports (`ApplicationServerRegistry`, and `IServerInternal` if nothing else
   in the file uses it) — only after the compiler confirms they are unused.
6. Run the compilation / quick-fix loop (`eclipse-ide_getCompilationErrors`) until the bundle is
   error-free; address any top-two-severity SpotBugs findings in the changed code.
7. No MANIFEST or pom.xml change is expected — `com.servoy.j2db.util` and `com.servoy.j2db.persistence`
   are already imported by this bundle (verify `com.servoy.j2db.util` is on the import list; add
   it to `Import-Package` only if the compiler reports it missing).
8. In `createRelation`, replace the silent column-mapping skip with an explicit warning (§3.3):
   capture the table/column resolution failure into a `columnMappingWarning` string and append it
   to the result as a `WARNING:` line. The relation is still saved in all cases. Keep the existing
   `ServoyLog.logWarning` on the exception path.

## 5. Acceptance criteria

- [ ] `servoy-model_createRelation` creates a relation whose primary and/or foreign datasource
      is `mem:<table>` without throwing *"Invalid datasource format"*; the relation is saved and
      (when both columns resolve on the mem tables) the column mapping is added.
- [ ] `createValueList` with a `mem:<table>` datasource (database type) creates a DATABASE_VALUES
      valuelist bound to that datasource without error.
- [ ] `createFormWithFields` with a `mem:<table>` datasource creates a form bound to that
      datasource, resolving the in-memory table and generating fields for its columns (or the
      requested subset).
- [ ] Existing `db:/server/table` and bare `server/table` inputs still work for all three tools
      (no regression), including the `db:/`-only `ensureDbiForDataSource` path.
- [ ] `view:<name>` datasources are accepted by `correctDataSource` (consistency) and, where a
      table resolves, by `createFormWithFields`.
- [ ] A clearly malformed datasource (e.g. `foo` with no scheme and no `/`) still throws an
      "Invalid datasource format" error that names the supported schemes.
- [ ] The `com.servoy.eclipse.developer.mcp` bundle compiles with zero errors; no top-severity
      SpotBugs issues introduced in the changed code.
- [ ] The reference-only `com.servoy.eclipse.servoypilot/RelationService` is left untouched.
- [ ] When `createRelation` cannot resolve the key table(s) or column(s), it still saves the
      relation but appends a `WARNING:` line explaining the mapping was skipped and why
      (instead of reporting an unqualified "success" with silently-unresolved columns).

## 6. Out of scope

- Any change to `com.servoy.eclipse.servoypilot` (`RelationService.validateAndCorrectDataSource`)
  — reference-only / dead code.
- `menu:` datasources — not requested by the ticket and not relevant to relation/valuelist/form
  table binding.
- Changes to platform code (`DataSourceUtils`, `Solution`, the datasource manager) — they
  already support mem/view.
- New public API or new MCP tools — the fix is a localized change to existing internal helpers.
- Creating the actual in-memory table definition — the tools bind to an existing mem/view
  datasource; defining the mem table itself is a separate concern.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| For `createFormWithFields`, if a `mem:` table has no columns yet (empty mem table), should the tool create an empty form or error? Current behaviour errors when the selected-columns list is empty; keeping that is reasonable. | Implementer | open |
| Should the "columns not found" / "table not found" error messages for mem datasources omit the server segment entirely (since mem has no named db server)? The proposed message guards on `serverName != null`; confirm the wording reads well. | Implementer | open |

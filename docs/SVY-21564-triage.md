# Triage Report — SVY-21564

**Verdict:** PROCEED

## Reported problem

The MCP artifact-creation tools reject in-memory (`mem:`) datasources even though the Servoy
IDE and runtime support them:

- `servoy-model_createRelation` fails on a `mem:<table>` relation with *"Invalid datasource
  format"*.
- `createValueList` (database type) hits the same helper and fails identically.
- `createFormWithFields` additionally hardcodes a split on `db:/`, so it can never resolve a
  `mem:` (or `view:`) table.

The ticket points at a shared datasource-validation helper that throws a `RepositoryException`
for any datasource that doesn't start with `db:/` and doesn't contain a `/`.

The ticket proposes no concrete fix beyond identifying the offending code locations.

## Root-cause assessment

The real culprit is **one private helper** in the active MCP bundle:

`com.servoy.eclipse.developer.mcp/.../services/ServoyArtifactCreationService.java`,
`correctDataSource(String ds)` (lines 461-470):

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

A `mem:customers` datasource starts with `mem:` (not `db:/`) and contains no `/`, so it throws.
This helper is called by all three affected tools:

- `createRelation` → lines 156-157 (both primary and foreign datasource).
- `createValueList` (database type) → line 223.
- `createFormWithFields` → line 276.

`createFormWithFields` has a **second, independent** `db:/` assumption at line 281:

```java
String[] dsParts = dataSource.replace("db:/", "").split("/");
if (dsParts.length < 2) throw ... "Invalid datasource format"
IServerInternal server = ...getServer(dsParts[0], ...);
table = server.getTable(dsParts[1]);
```

This hardcoded server/table split can never handle a `mem:` datasource even if
`correctDataSource` is relaxed, because a mem datasource has the shape `mem:tablename` (no
server segment) and its table lives on the in-memory server, not a named database server.

**The platform itself fully supports mem/view datasources — the MCP wrapper is the only thing
being over-restrictive:**

- `DataSourceUtils` (servoy-client `servoy_shared/.../util/DataSourceUtils.java`) defines
  `INMEM_DATASOURCE = "mem"`, `VIEW_DATASOURCE = "view"`, and `isDatasourceUri(...)` (line 259)
  explicitly accepts `db:/…`, `mem:…` and `view:…`.
- `Solution.createNewRelation(...)` (servoy-client `servoy_shared/.../persistence/Solution.java`
  line 479) stores whatever primary/foreign datasource it is given — it does **no** `db:/`
  validation. The ticket's claim here is accurate.
- The datasource manager (`AbstractServoyModel.getDataSource(...)`,
  servoy-eclipse `com.servoy.eclipse.model/.../extensions/AbstractServoyModel.java` line 115)
  already resolves mem datasources: it calls `DataSourceUtils.getInmemDataSourceName(...)` and
  returns the in-memory `MemTable` via `getMemServer(...)`. So the correct way to resolve a
  table for a form is `getDataSourceManager().getDataSource(dataSource)` — which the
  `createRelation` column-mapping block (lines 170-171) already does — not a manual `db:/`
  string split.

**Scope note on the ticket's second cited location.** The ticket also names
`RelationService.validateAndCorrectDataSource` (lines 244-264). That class lives in
`com.servoy.eclipse.servoypilot`, which `AGENTS.md` marks **reference-only / no longer actively
developed**. The live MCP tools do **not** route through it (the active implementation is the
ported `ServoyArtifactCreationService`). The fix should target the MCP bundle only; the
servoypilot copy can be left untouched unless the user explicitly wants the dead code updated.

## Ticket premise check

The ticket's premise holds up. It correctly identifies:

1. the over-restrictive `db:/`-only validation as the root cause, and
2. the separate hardcoded `db:/` split in `createFormWithFields` as an additional blocker, and
3. that the platform (`Solution.createNewRelation`) accepts any datasource.

The only correction: the one location that actually matters is the MCP bundle's
`ServoyArtifactCreationService.correctDataSource` + `createFormWithFields`. The
`servoypilot/RelationService` copy is dead code. There is no need for a new public API — the
fix is to relax/replace an existing internal helper and resolve tables through the existing
datasource manager.

## Approaches considered

1. **Relax `correctDataSource` to accept mem/view datasources, and resolve the form table via
   the datasource manager in `createFormWithFields`.** Use
   `DataSourceUtils.isDatasourceUri(...)` / the mem/view scheme constants instead of a literal
   `db:/` check, and replace the manual `db:/` split in `createFormWithFields` with
   `getDataSourceManager().getDataSource(dataSource)` (which already returns a `MemTable` for
   `mem:` datasources).
   - *Pros:* fixes all three tools at the real root; minimal, localized change; reuses the
     platform's own datasource utilities so behavior matches the designer/runtime; the
     column-mapping path in `createRelation` already resolves tables this way, so it's
     consistent.
   - *Cons:* `createFormWithFields` needs light restructuring (drop the server/table-name split,
     derive the display server/table name from `DataSourceUtils` for error messages); must keep
     the existing `db:/server/table` behavior working.

2. **Only relax `correctDataSource` (accept mem/view), leave `createFormWithFields` split as
   is.**
   - *Pros:* smallest possible change; fixes `createRelation` and `createValueList`.
   - *Cons:* `createFormWithFields` would still fail on `mem:` because of the independent `db:/`
     split at line 281 — the ticket explicitly lists this as a defect, so this is incomplete.

3. **Delete `correctDataSource` validation entirely and pass the raw datasource through.**
   - *Pros:* even less code.
   - *Cons:* loses the useful `server/table` → `db:/server/table` auto-correction and the
     empty-datasource guard; silently accepts garbage; worse UX than a scheme-aware check.

4. **No code change.**
   - *Pros:* none.
   - *Cons:* the tools genuinely reject a datasource shape the IDE/runtime support; this is a
     real functional gap confirmed by the platform code. Rejected.

## Recommendation

**PROCEED with Approach 1.** Make the MCP `ServoyArtifactCreationService` datasource handling
scheme-aware so `mem:` (and, for consistency, `view:`) datasources are accepted:

- Replace the `db:/`-only test in `correctDataSource` with a check that keeps the existing
  `db:/server/table` and bare `server/table` → `db:/…` auto-correction, but passes
  `mem:`/`view:` datasources through unchanged (use the `DataSourceUtils` scheme constants /
  `isDatasourceUri`).
- In `createFormWithFields`, drop the hardcoded `dataSource.replace("db:/", "").split("/")`
  and resolve the table with `ServoyModelFinder.getServoyModel().getDataSourceManager()
  .getDataSource(dataSource)` (which already returns a `MemTable` for `mem:`), using
  `DataSourceUtils.getDataSourceServerName/TableName` only for building human-readable error
  messages. `ensureDbiForDataSource` already no-ops for non-`db:/` datasources, so it is safe.

Target the active `com.servoy.eclipse.developer.mcp` bundle only. Leave the reference-only
`com.servoy.eclipse.servoypilot/RelationService` untouched unless the user asks otherwise.

Alternatives 2 and 3 are rejected as incomplete / lossy; Approach 1 is the complete,
root-cause fix that matches platform behavior.

## Git history findings

- `correctDataSource` was introduced by commit `35a68804a` (marianvid, 2026-05-29) and has not
  changed since — it is an original design choice in the ported MCP service, not a regression
  from a later edit. The `db:/`-only restriction was simply never written to account for
  mem/view datasources.
- Recent history of `ServoyArtifactCreationService.java` (`e392c5e8`, `5b1ee8aa`, `8c3da824`)
  concerns skilltest setup and the `createForm` columns parameter / navigator defaults — none
  touch datasource validation, so no prior decision would be reverted by relaxing it.
- The platform-side support predates this code: `DataSourceUtils.INMEM_DATASOURCE` and
  `Solution.createNewRelation`'s unvalidated datasource handling are long-standing, confirming
  the MCP wrapper is the outlier, not the platform.

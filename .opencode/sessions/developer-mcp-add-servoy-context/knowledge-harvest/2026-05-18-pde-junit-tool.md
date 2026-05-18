## Knowledge Harvest

- **Project-action:** developer-mcp-add-servoy-context
- **Date:** 2026-05-18
- **Domain:** pde-junit-plugin-tests
- **Source:** review of developer-mcp-add-servoy-context implementation

### Existing Skills to Update

**skill: pde-plugin-testing** — Add the following note:

> **`eclipse-pde_runJUnitPluginTestClass` MCP tool forces `uitestapplication`**
> The `eclipse-pde_runJUnitPluginTestClass` MCP tool ignores the `application` attribute in the `.launch` file and always launches with `org.eclipse.pde.junit.runtime.uitestapplication`. This requires a full Eclipse workbench to start. If the target platform has duplicate OSGi bundle versions (e.g. two versions of `org.eclipse.osgi`), the `simpleconfigurator` will fail with `IllegalStateException: The System Bundle was updated` before the workbench starts, and no test results are collected.
>
> **Workaround:** Run tests manually via Run As → JUnit Plugin Test in Eclipse. The `.launch` file with `coretestapplication` works correctly when run manually.
>
> **Do not waste time trying to make `eclipse-pde_runJUnitPluginTestClass` work** if the target platform has duplicate bundle versions — fix the duplicates first or run manually.

### New Skill Candidates

None beyond what is in the servoy-developer-mcp-jvm harvest note.

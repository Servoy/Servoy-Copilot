# Triage Report — SVY-21355

**Verdict:** PROCEED

## Reported problem

MCP tool calls (`getFilteredSource`, `getClassOutline`, `getFileOutline`) fail when the target script lives in a submodule of the active Servoy solution. Two failure modes:

1. **`getFilteredSource` / `getClassOutline`:** Return "Script not found: 'scmmanager/scmmanager' in active solution 'svyCloudLauncher'. Expected locations: forms/\<name\>.js or scopes/\<name\>.js"
2. **`getFileOutline`:** Throws `RuntimeException: Error: File 'scmmanager.js' does not exist in project 'cloudSync'.`

The reporter notes this always happens with submodules and that the `scopes/` mention in error messages further confuses the AI agent into making bad follow-up calls.

## Root-cause assessment

**Primary root cause:** `ServoyScriptResolver.resolveScript()` (`ServoyScriptResolver.java:58-92`) only searches in the single resolved project — either the explicitly named `moduleName` or the active solution. It **never iterates through the modules of the active solution**. When the agent calls `getFilteredSource(name="scmmanager/scmmanager")` without providing `moduleName`, the resolver only searches `svyCloudLauncher` (the active solution) and fails because the form lives in a different module (e.g. `cloudSync`).

This is inconsistent with the established codebase pattern. At least 15+ call sites in the same MCP bundle (`CodeAnalysisService`, `ServoySolutionService`, `MenuService`, `FormNavigationGraphService`, `ServoyDevServer`) use:
```java
for (ServoyProject mod : servoyModel.getModulesOfActiveProject()) { ... }
```

**Secondary issue:** `getFileOutline` (and `readProjectResource`) in `WorkspaceService` takes a literal project-relative path. When the agent passes `resourcePath='scmmanager.js'` instead of `forms/scmmanager.js`, the lookup fails with no Servoy-aware fallback. This is an agent UX issue — the tool could try `forms/<name>` and `scopes/<name>` as fallbacks for `.js` files in Servoy projects.

## Ticket premise check

The ticket does not propose a specific solution — it describes the symptoms. Its observation that "submodules" are always involved and that "scopes/" confuses the agent is accurate and directly points to the root cause. The implicit suggestion that the tools should work for submodules is correct.

## Approaches considered

1. **Extend `ServoyScriptResolver.resolveScript()` to search all modules** — When `moduleName` is null and the script is not found in the active project, iterate through `servoyModel.getModulesOfActiveProject()` and try each module's `forms/` and `scopes/` folders. This matches the established codebase pattern.
   - Pros: Fixes the primary failure mode completely; well-established pattern; minimal code change (~15 lines).
   - Cons: Slightly slower when a script genuinely doesn't exist (searches N modules before returning null). Negligible in practice.

2. **Additionally add Servoy-aware fallback to `WorkspaceService.getFileOutline()`** — When a direct path lookup fails for a `.js` file, try prepending `forms/` and `scopes/`.
   - Pros: Fixes the secondary `getFileOutline` failure; makes the tool more forgiving of agent mistakes.
   - Cons: Slightly blurs the generic workspace tool's semantics; only relevant for Servoy projects.

3. **Improve `buildNotFoundMessage()` to list available modules** — Help the agent self-correct by showing which modules exist.
   - Pros: Low-effort, informative error messages guide the agent to retry with the correct `moduleName`.
   - Cons: Doesn't fix the root problem; the agent still fails on the first call and must retry.

4. **No code change** — Rely on the agent always passing the correct `moduleName` parameter.
   - Pros: No development effort.
   - Cons: The AI agent frequently doesn't know which module a form belongs to, especially in multi-module solutions. The problem persists and causes repeated failures that degrade the user experience.

## Recommendation

**Approach 1** (search all modules) is the recommended primary fix. It aligns with how every other Servoy-aware service in the MCP bundle handles cross-module lookups. **Approach 2** (Servoy-aware fallback in `getFileOutline`) is a valuable secondary improvement that addresses the `getFileOutline` error path.

Both changes should be made together since they address the two distinct failure modes reported in the ticket.

## Git history findings

- `ServoyScriptResolver.java` was introduced in commit `5ba8b6b2` (SVY-21012 "Tool & service refactoring", 2026-05-21). It was a new extraction — no prior implementation existed that searched modules.
- Modified once in `aa548ff9` (SVY-21142) to remove stub tools; the resolver logic was unchanged.
- There is no indication that limiting to a single project was an intentional design decision — it appears to be an oversight during the initial extraction, since the class was created as a simple utility without awareness of the multi-module pattern used elsewhere.

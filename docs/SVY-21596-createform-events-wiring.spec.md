# Spec: SVY-21596 — MCP createForm events= neither wires the form handler nor creates the method

## 1. Goal

The `createForm` MCP tool (servoy-model server) promises in its description that passing
event handlers (e.g. `onShow`, `onRecordSelection`) in a single call will auto-create the
backing methods and wire them to the form. Today that promise is broken: events are silently
dropped whenever `columns` is also supplied, bare event names (without a `:method` suffix) are
discarded without warning, and when a method *is* created its skeleton always has the same
`(event)` parameter list regardless of the event. This spec makes the single-call promise hold:
after `createForm`, the requested handlers are present on the created form, each is backed by a
real method whose skeleton matches that event's real parameter list, and unrecognised event
syntax produces a clear message instead of silent loss. It also retires the drifted private copy
of the event→setter mapping by delegating to the canonical `AiBridge.applyEventMethod`.

## 2. Background

### 2.1 The two-method delegation path

`createForm` is a `@Tool` on
`com.servoy.eclipse.developer.mcp.servers.ServoyDevServer`
(`bundles/com.servoy.eclipse.developer.mcp/.../servers/ServoyDevServer.java`, lines 1574–1632).
It parses its string parameters and delegates to
`com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService`:

- `createForm(name, style, width, height, dataSource, extendsForm, Map<String,String> events)`
  (service lines 53–132) — the "empty form" path. It *does* honour `events`: for each
  `eventName → methodName` entry it calls `resolveOrCreateMethod` then `applyEventMethod`.
- `createFormWithFields(name, dataSource, style, List<String> columns)` (service lines 281–413)
  — the "data form" path. It has **no `events` parameter** at all.

### 2.2 The three defects (from triage)

1. **`columns` short-circuits events entirely.** In `ServoyDevServer.createForm`, when `columns`
   is non-blank the method `return`s `artifactService.createFormWithFields(...)` immediately —
   before the `events` block is reached. Because `createFormWithFields` takes no events, any
   `events` passed alongside `columns` are discarded with no error. This is a regression
   introduced by **SVY-21281** (`5b1ee8a`, Aug 14 2026), which added the `columns` early return
   on top of the pre-existing `events` path.

2. **Strict `event:method` parsing drops bare event names.** The tool parses events as
   `String[] kv = pair.trim().split(":"); if (kv.length == 2) eventMap.put(...)`. A bare name
   (`events=onShow`) yields `kv.length == 1` and is silently skipped. Only exact `event:method`
   pairs wire — so the feature works or fails depending on syntax, with no feedback either way.

3. **One-size method skeleton.** `resolveOrCreateMethod` always emits
   `function <name>(event) { ... }` with a single `@param {JSEvent} event`, regardless of the
   event. Real Servoy form-event signatures differ (record-level, data-change and element events
   carry extra parameters), so the skeleton is wrong for most non-`onShow` events and the agent
   has to correct signatures afterwards.

### 2.3 Drifted duplicate mapping

`ServoyArtifactCreationService.applyEventMethod` (service lines 541–582) is a hand-copied subset
of the canonical `com.servoy.eclipse.core.ai.AiBridge.applyEventMethod`
(servoy-eclipse `com.servoy.eclipse.core`, `src/com/servoy/eclipse/core/ai/AiBridge.java`,
lines 33–90). `AiBridge`'s class Javadoc states it exists so release-specific event-method wiring
lives in one place. The MCP copy has already drifted — it is missing `onBeforeRecordSelection`,
`onElementFocusGained` and `onElementFocusLost`, which the canonical version handles.

The MCP bundle already depends on `com.servoy.eclipse.core`
(`META-INF/MANIFEST.MF` line 50 lists it under `Require-Bundle`), and the `com.servoy.eclipse.core.ai`
package is exported by that bundle (`version="2026.12.0"`). `AiBridge.applyEventMethod` is
`public static`, so the MCP service can call it directly.

## 3. Design

### 3.1 Thread events through the `columns` path

Make events take effect regardless of whether `columns` is supplied. Two viable shapes; the spec
picks **(A)** for a single wiring code path:

- **(A) Apply events after the service returns, in `ServoyDevServer.createForm`.** Parse the
  `events` string into a map *before* the `columns` branch, create the form (via either
  `createFormWithFields` or `createForm`), then — on success, on the just-created form — run the
  same event-wiring loop once, in the service. This keeps a single place that resolves/creates
  methods and wires them, and avoids duplicating the loop across two service methods.

  To do this cleanly, add a small public service method, e.g.
  `applyEventsToForm(String formName, Map<String,String> events)`, that resolves the active
  form from the **editing flattened solution**, runs `resolveOrCreateMethod` + the delegated
  wiring for each entry, and re-saves the form. `ServoyDevServer.createForm` calls it after the
  create step succeeds for both the `columns` and non-`columns` branches.

- (B) Alternatively, give `createFormWithFields` an `events` parameter and call the same internal
  wiring helper there. Rejected in favour of (A) because it spreads the events loop across both
  create methods and risks the two drifting again.

Resolve the just-created form against `activeProject.getEditingFlattenedSolution()` (per the
project convention) rather than the active solution's own `getForm`.

### 3.2 Lenient, validated event parsing

Replace the strict `split(":")` with parsing that:

- Accepts `event:method` (unchanged): `onShow:refreshData` → `{onShow → refreshData}`.
- Accepts a **bare event name**: `onShow` → derives a sensible default method name. Use a stable
  convention, e.g. the event name itself as the method name (`onShow` → method `onShow`), or an
  action-style name — pick one and document it in the tool description. (Recommendation: use the
  event name verbatim as the method name, since it is unambiguous, collision-resistant per form,
  and matches what an agent would read back.)
- **Surfaces a clear message for unrecognised syntax** instead of silently dropping it: an entry
  that is empty, has more than one `:`, or names an event not supported by the wiring table is
  reported back in the tool's result string (e.g. a trailing `"Skipped unrecognised events: …"`
  note) so the caller sees what did not wire and why.

Validate event names against the supported set (the same set `AiBridge.applyEventMethod`
switches on) so an unknown event (`onWhatever:foo`) is reported rather than silently hitting the
`default` branch.

### 3.3 Per-event method skeletons

Replace the single `(event)` skeleton in `resolveOrCreateMethod` with a per-event signature
table so the generated method matches the real parameter list of each supported form event.
Introduce a helper (e.g. `skeletonForEvent(String eventName, String methodName)`) that returns
the correct Javadoc `@param` block and function parameter list per event. Target signatures for
the events the tool accepts (align with Servoy's documented form-event signatures):

| Event | Parameters |
|-------|------------|
| `onLoad`, `onUnLoad`, `onShow`, `onHide`, `onResize` | `(event)` — `{JSEvent} event` |
| `onBeforeHide` | `(event)` returning `Boolean` (vetoable) |
| `onRecordSelection`, `onBeforeRecordSelection` | `(event)` — record-level |
| `onRecordEditStart` | `(event)` |
| `onRecordEditStop` | `(record, event)` — `{JSRecord} record`, `{JSEvent} event`; returns `Boolean` |
| `onElementDataChange` | `(oldValue, newValue, event)` — returns `Boolean` |
| `onElementFocusGained`, `onElementFocusLost` | `(event)` |
| `onSort` | `(dataProviderID, asc, event)` |

The exact per-event signatures must be confirmed against the Servoy form-event spec during
implementation (the designer's generated stubs and `IFormConstants`/form-event metadata are the
source of truth). Any event not in the table falls back to `(event)` and is additionally reported
via the §3.2 "unrecognised" message so the gap is visible rather than silent.

### 3.4 Delegate wiring to the canonical `AiBridge.applyEventMethod`

Remove the private `ServoyArtifactCreationService.applyEventMethod` and call
`com.servoy.eclipse.core.ai.AiBridge.applyEventMethod(form, eventName, methodUUID)` instead. This:

- fixes the drift (gains `onBeforeRecordSelection`, `onElementFocusGained`,
  `onElementFocusLost`), and
- keeps release-specific event-method wiring in the one place `AiBridge` is designed to be.

The supported-event set used for validation in §3.2 and the skeleton table in §3.3 must stay
consistent with the events `AiBridge.applyEventMethod` handles.

### 3.5 Keep the tool description accurate

Update the `@Tool` description on `createForm` so it matches the fixed behaviour: events are
honoured together with `columns`; both `event:method` and bare `event` forms are accepted (state
the default-method-name rule); and the generated method skeleton matches the event's parameter
list. Do not leave the old blanket "auto-created as methods" wording if any supported event is
still only partially wired.

### 3.6 Git history

- The `columns` early return that bypasses events was added by **`5b1ee8a`**
  (SVY-21281, "add columns parameter to createForm for single-call form wizard", Aug 14 2026) —
  the headline defect is a regression from that commit. The `events` wiring
  (`resolveOrCreateMethod`/`applyEventMethod`) predates it and was not touched.
- `35a6880` (SVY-21012) created the service layer; `4e4755a` (SVY-21564) later touched
  `createFormWithFields` for mem datasources — neither added an events path to the columns branch.
- `AiBridge` is `@since 2026.03` and documents itself as the single release-specific home for
  form event-method wiring; the MCP private copy fell behind it. No dependency / target-platform
  version bump is involved.

## 4. Implementation plan

1. **`ServoyArtifactCreationService`** (`.../services/ServoyArtifactCreationService.java`):
   - Add `public String applyEventsToForm(String formName, Map<String,String> events)` (or an
     overload that takes the `Form` directly) that resolves the form from
     `activeProject.getEditingFlattenedSolution()`, loops the events map calling
     `resolveOrCreateMethod` + `AiBridge.applyEventMethod`, re-saves, and returns a summary of
     what was wired / skipped.
   - Change `resolveOrCreateMethod` to generate a per-event skeleton via a new
     `skeletonForEvent(eventName, methodName)` helper (§3.3 table).
   - Delete the private `applyEventMethod`; call
     `com.servoy.eclipse.core.ai.AiBridge.applyEventMethod(...)` instead.
2. **`ServoyDevServer.createForm`** (`.../servers/ServoyDevServer.java`, ~1574–1632):
   - Parse `events` into a map **before** the `columns` branch, using lenient parsing
     (`event:method` and bare `event`, default method name = event name) and collecting
     unrecognised entries.
   - After the create step succeeds (both the `columns` and the empty-form branches), call the
     service's `applyEventsToForm` so events are honoured in one call either way.
   - Append any "unrecognised events" message to the returned result string.
   - Update the `@Tool` description (§3.5).
3. **No MANIFEST change needed** — `com.servoy.eclipse.core` is already on `Require-Bundle` and
   `com.servoy.eclipse.core.ai` is exported; confirm the import resolves after the edit.
4. **Tests** (`com.servoy.eclipse.developer.mcp.tests`): add integration-PDE coverage that
   `createForm` with `columns` + `events` produces both the fields and the wired handler + method;
   that a bare event name wires with the default method name; that an unrecognised event is
   reported in the result; and that a per-event skeleton (e.g. `onRecordEditStop`,
   `onElementDataChange`) carries the correct parameter list. Follow the `integrationpde`
   conventions (full-workbench runner, `PDEIntegrationTestsBaseCopy-<branch>.launch`).
5. **Compile/quick-fix loop + Spotbugs** per AGENTS.md; keep the commit subject with the
   `SVY-21596 … [ai]` convention.

## 5. Acceptance criteria

- [ ] `createForm` with both `columns` and `events` creates the form with its fields **and** with
      every requested event handler present on the form, each backed by a real created method —
      in the single call.
- [ ] `createForm` with `events` and no `columns` continues to wire handlers (no regression).
- [ ] A bare event name (`events=onShow`) wires the handler and creates a method using the
      documented default method-name rule.
- [ ] An unrecognised/unsupported event (`events=onBogus:foo` or malformed `a:b:c`) is **reported
      back in the result string**, not silently dropped.
- [ ] A created method's skeleton matches the real parameter list of its event (verified for at
      least `onShow`, `onRecordEditStop`, and `onElementDataChange`).
- [ ] Event→setter wiring goes through `AiBridge.applyEventMethod`; the private copy in
      `ServoyArtifactCreationService` is removed; `onBeforeRecordSelection`,
      `onElementFocusGained`, `onElementFocusLost` now wire.
- [ ] The `createForm` `@Tool` description accurately describes the fixed behaviour (events honoured
      with `columns`, both event-syntax forms, per-event skeletons).
- [ ] Workspace compiles with zero errors; relevant integration-PDE tests pass.

## 6. Out of scope

- Approach 2 (removing the promise from the tool description) — only the fallback if approach 1
  cannot land; this spec implements approach 1.
- Refactoring unrelated `createForm`/`createFormWithFields` behaviour (field layout, component
  selection, datasource handling) beyond what the events wiring needs.
- Changing `AiBridge` itself in servoy-eclipse (we consume it as-is); only the MCP bundle changes.
- Element-level (non-form) event handlers and non-form artifact events.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Default method name for a bare event — event name verbatim (recommended) vs. an action-style derived name? | Dev/PM | open |
| Exact per-event parameter lists — confirm each against the Servoy form-event spec / designer-generated stubs before implementing the §3.3 table | Dev | open |
| Should an unrecognised-events note be a non-fatal warning appended to the success result, or should it fail the whole call? (Spec assumes non-fatal warning.) | PM | open |

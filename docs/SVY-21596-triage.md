# Triage Report — SVY-21596

**Verdict:** PROCEED

## Reported problem

The `createForm` MCP tool (servoy-model server) advertises in its own description that
passing event handlers (e.g. `onShow`, `onRecordSelection`) will auto-create the matching
methods and wire them to the form in one call. In practice, passing `events` "silently does
nothing": the created form carries none of the requested handlers and no backing method is
created. This is confirmed via the tool's own read-back (reading the form and searching for
the generated method both come back empty), so it is the created artefact that lacks the
handler, not a stale view.

The ticket asks for one of:
- make the single-call promise hold (handler present on the form + real method created), **or**
- remove the promise from the tool description so no caller relies on it;
- and, when a method *is* auto-created, give it a skeleton whose parameter list matches the
  specific event rather than one fixed `(event)` shape for every event.

(These are the *reported problem* and *proposed solutions* — kept separate below.)

## Root-cause assessment

The code involved is the `createForm` tool and the artifact service it delegates to:

- Tool: `com.servoy.eclipse.developer.mcp.servers.ServoyDevServer.createForm(...)`
  (`bundles/com.servoy.eclipse.developer.mcp/.../servers/ServoyDevServer.java`, ~lines 1574-1632)
- Service: `com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService`
  (`.../services/ServoyArtifactCreationService.java`) — `createForm(...)` (lines 53-132),
  `createFormWithFields(...)` (lines 281-413), `resolveOrCreateMethod(...)` (523-539),
  `applyEventMethod(...)` (541-582).

There are **three concrete defects**, all on the createForm wiring path, each matching one of
the ticket's "What we need" bullets:

1. **`columns` short-circuits events entirely (headline bug).** In `ServoyDevServer.createForm`,
   when `columns` is non-blank the method returns `artifactService.createFormWithFields(name,
   dataSource, style, columnList)` **immediately**, before the `events` block is ever reached.
   `createFormWithFields` has **no `events` parameter** — so whenever a caller passes `columns`
   *and* `events` together (the common "build me a data form with an onShow handler" request),
   the events are discarded with no error. No method, no wiring. This is the most likely
   reproduction of the ticket's "silently does nothing".

2. **Event parsing requires strict `event:method` pairs; a bare event name is dropped.** In the
   non-columns branch, events are parsed as:
   ```java
   String[] kv = pair.trim().split(":");
   if (kv.length == 2) eventMap.put(kv[0].trim(), kv[1].trim());
   ```
   A bare event name (`events=onShow`) yields `kv.length == 1` and is silently skipped. The
   ticket's own title — "createForm events=" — and its "passing events to createForm (one
   standard event…)" wording suggest callers try the bare-name form, which produces nothing.
   Only the exact `event:method` syntax actually wires (via `resolveOrCreateMethod` +
   `applyEventMethod`), so the feature *partly* works and *partly* fails depending on syntax —
   which is exactly how a caller ends up believing it "does nothing".

3. **One-size method skeleton.** `resolveOrCreateMethod` always emits:
   ```java
   "/**\n * @param {JSEvent} event\n */\nfunction " + methodName + "(event) {\n\t// TODO: implement\n}\n"
   ```
   regardless of event. Real Servoy form event signatures differ (e.g. `onRecordSelection`,
   data-change and element events carry additional parameters), so the generated skeleton is
   wrong for most non-`onShow` events and the agent has to correct signatures afterwards. This
   is the ticket's third bullet.

**Secondary finding — duplicated, drifted mapping.** `ServoyArtifactCreationService.applyEventMethod`
is a hand-copied subset of the canonical, release-specific
`com.servoy.eclipse.core.ai.AiBridge.applyEventMethod` (servoy-eclipse `com.servoy.eclipse.core`).
The canonical version handles more events (`onBeforeRecordSelection`, `onElementFocusGained`,
`onElementFocusLost`) than the MCP copy, and `AiBridge`'s Javadoc explicitly says it exists so
release differences in event-method wiring live in one place. The MCP bundle's private copy has
already drifted — a maintenance hazard independent of this ticket but worth folding into the fix.

The problem is squarely **in this project's code** (the MCP bundle), not user error or a
third-party dependency: the tool description makes a promise the delegation path does not keep.

## Ticket premise check

The ticket's premise holds up. It correctly separates the two acceptable outcomes (make the
promise hold vs. remove the promise) and correctly identifies the skeleton-signature issue as a
distinct, real defect. It does slightly under-specify the mechanism — it frames the failure as
"events does nothing" in general, whereas the code shows the failure is conditional: it is total
when `columns` is present, and syntax-dependent (bare name vs. `event:method`) when it is not.
That nuance does not change the recommended direction; it just means the fix has to cover both
the `columns` branch and the parser, not only one of them.

## Approaches considered

1. **Make the one-call promise hold (recommended).**
   - Thread `events` through the `columns` path: give `createFormWithFields` an `events`
     parameter (or apply events in `ServoyDevServer.createForm` after the service returns, on the
     just-created form) so `columns` + `events` both take effect in one call.
   - Harden the event parser: accept `event:method` **and** a bare `event` (deriving a sensible
     default method name), and surface a clear message for unrecognised syntax instead of silently
     dropping it.
   - Generate per-event skeletons with the correct parameter list for each supported event
     (reusing the canonical signatures), instead of a single `(event)` shape.
   - Delegate event→setter wiring to the canonical `com.servoy.eclipse.core.ai.AiBridge.applyEventMethod`
     (or re-sync the MCP copy from it) so the mapping stops drifting.
   - *Pros:* delivers the advertised behaviour; removes the biggest agent time-sink described in
     the ticket; de-duplicates the drifted mapping. *Cons:* largest change; needs a correct
     per-event signature table; touches both the tool and the service.

2. **Remove the promise from the tool description (fallback the ticket offers).**
   - Strip "Event handlers are auto-created as methods…" from the `@Tool` description and stop
     accepting `events` on the `columns` path (or reject the combination explicitly).
   - *Pros:* small, safe, immediately stops callers trusting a broken guarantee. *Cons:* leaves
     agents to hand-wire every handler — the exact cost the ticket wants eliminated; a strict
     regression in capability.

3. **Minimal bug-fix only: fix the `columns` short-circuit + parser, keep the fixed skeleton.**
   - *Pros:* smaller than (1); makes the common case work. *Cons:* leaves the ticket's third
     bullet (correct per-event signatures) unaddressed, so agents still correct signatures.

4. **No code change.**
   - *Pros:* none beyond zero effort. *Cons:* the tool description actively misleads callers; the
     ticket documents a measured agent run burning most of ~49 requests on this. Not defensible —
     at minimum the description must change, so "no change" is not a valid outcome.

## Recommendation

**PROCEED with approach 1** — make the single-call promise hold. It is the outcome the ticket
primarily asks for, it eliminates the documented agent cost, and it lets us retire the drifted
duplicate mapping by routing through `AiBridge`. The fix must cover **all three** defects
together (events on the `columns` path, lenient+validated parsing, per-event skeletons), because
fixing only one leaves the tool still partly silently-broken and the description still partly
untrue.

If approach 1 cannot land in the target version, fall back to **approach 2** (remove the promise)
rather than shipping a partial fix that keeps the description's guarantee while the behaviour
stays conditional — a half-fixed promise is worse than an honest "not supported".

Alternatives on record: approach 3 (minimal) if the per-event signature work must be deferred,
and approach 2 as the explicit de-scope.

## Git history findings

- The `columns` parameter — the source of the headline short-circuit — was added in
  **`5b1ee8a` "SVY-21281 add columns parameter to createForm for single-call form wizard [ai]"**
  (Fri Aug 14 2026). That commit introduced the `if (columns != null && !columns.isBlank()) return
  artifactService.createFormWithFields(...)` early return at the top of `ServoyDevServer.createForm`,
  which is precisely what bypasses the pre-existing `events` block. So this is a **regression
  introduced by SVY-21281**: before it, `events` on `createForm` reached the wiring path; after
  it, `events` is dropped whenever `columns` is supplied. The `events` wiring itself
  (`resolveOrCreateMethod`/`applyEventMethod`) predates that commit and was not touched by it.
- `ServoyArtifactCreationService` history: `35a6880` (SVY-21012 tool/service refactoring) created
  the service layer; `5b1ee8a` added `createFormWithFields`; `4e4755a` (SVY-21564) later touched
  `createFormWithFields` for mem datasources. None of these added an `events` path to the columns
  branch — the gap has existed since the columns feature was introduced.
- The canonical `com.servoy.eclipse.core.ai.AiBridge` (servoy-eclipse) is marked `@since 2026.03`
  and documents itself as the single, release-specific home for form event-method wiring; the MCP
  bundle's private `applyEventMethod` copy has already fallen behind it (missing
  `onBeforeRecordSelection`, `onElementFocusGained`, `onElementFocusLost`).
- No dependency/target-platform version bump is involved.

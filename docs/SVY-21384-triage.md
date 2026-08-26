# Triage Report — SVY-21384

**Verdict:** PROCEED

## Reported problem

The opencode server process output (npm/bun stdout/stderr) is written to the "Titanium NG Build Console" via `RunNPMCommand`, mixing AI-related output with unrelated TiNG build output. This makes it hard to diagnose opencode startup, session resume, and shutdown issues. Additionally, opencode Java logging (via `ServoyLog`) goes to the Eclipse `.log` file, splitting diagnostics across two places.

## Root-cause assessment

The root cause is in `RunNPMCommand.runCommand()` (`com.servoy.eclipse.ngclient.ui/src/.../RunNPMCommand.java:113`):

```java
StringOutputStream console = Activator.getInstance().getConsole().outputStream();
```

This hardcodes the output destination to the TiNG Build Console (created in `Activator.getConsole()` as `"Titanium NG Build Console"`). There is no mechanism for callers to provide an alternative output stream.

The opencode bundle uses `IRunNPMCommand` in three places:
1. `OpencodeFolderCreatorJob.run()` — `npm install` (line 142)
2. `OpencodeFolderCreatorJob.run()` — `npm update opencode-ai` (line 164)
3. `RunOpencodeCommand.run()` — `opencode serve` (long-running server, line 109)

All three write to the TiNG console because neither `IRunNPMCommand` nor `RunNPMCommand` supports caller-provided output streams.

## Ticket premise check

The ticket's proposed approach is correct and well-scoped:
1. Create a dedicated console — straightforward Eclipse `MessageConsole` / `EclipseIOConsole` pattern.
2. Extend `IRunNPMCommand` to accept an optional output stream — this is the minimal API change needed.
3. Wire it up in `RunOpencodeCommand` — natural consumer-side change.
4. Redirect Java logging to the same console — a nice-to-have that unifies diagnostics.

The approach holds up. It's backward-compatible (existing callers don't set the stream, so they keep the default TiNG console) and requires minimal changes to the shared `com.servoy.eclipse.ngclient.ui` code.

## Approaches considered

### 1. Add `setOutputStream(StringOutputStream)` to `IRunNPMCommand` (recommended)

Add a setter method to the interface. `RunNPMCommand.runCommand()` checks if a custom stream was provided; if so, uses it instead of `Activator.getInstance().getConsole().outputStream()`. `NoOpNPMCommand` gets a no-op implementation.

**Changes required:**
- `com.servoy.eclipse.ngclient.ui` (servoy-eclipse, 2026.03 branch):
  - `IRunNPMCommand.java` — add `void setOutputStream(StringOutputStream outputStream);`
  - `RunNPMCommand.java` — add field + setter; in `runCommand()` use custom stream if set, else fall back to default
  - `NoOpNPMCommand.java` — add no-op setter
- `com.servoy.eclipse.opencode` (this repo):
  - `Activator.java` — add `getConsole()` method creating a "Servoy AI Console" (same `EclipseIOConsole` pattern)
  - `RunOpencodeCommand.java` — call `serverCommand.setOutputStream(...)` before `runCommand()`
  - `OpencodeFolderCreatorJob.java` — same for install/update commands
  - `META-INF/MANIFEST.MF` — add `org.eclipse.ui.console` to Require-Bundle

**Pros:** Minimal API change, fully backward compatible, clean separation.
**Cons:** Adds one method to a shared interface (very low risk given 15 existing call sites don't need to call it).

### 2. Overload `createNPMCommand` factory with an `IConsole` parameter

Add `createNPMCommand(File, List<String>, IConsole)` to `Activator`. The `RunNPMCommand` stores it and uses it in `runCommand()`.

**Pros:** Console is set at creation time, impossible to forget.
**Cons:** Couples the factory to the console concern; requires a new constructor on `RunNPMCommand`; `IConsole`/`StringOutputStream` types must be visible to callers (already are, but adds conceptual coupling).

### 3. Bypass `RunNPMCommand` — manage the process directly in `RunOpencodeCommand`

**Pros:** No changes to ngclient.ui at all.
**Cons:** Duplicates ~130 lines of process management code (PATH setup, cancellation threads, process lifecycle). Maintenance burden. Loses future improvements to `RunNPMCommand` for free.

### 4. No code change

**Pros:** No effort.
**Cons:** The problem is real and gets worse as more AI diagnostics are added. TiNG console pollution makes debugging opencode issues unnecessarily hard.

## Recommendation

**PROCEED** with Approach 1 (`setOutputStream` on `IRunNPMCommand`).

This requires coordinated changes across two repositories:
1. **servoy-eclipse** (`com.servoy.eclipse.ngclient.ui`, 2026.03 branch) — add the setter to interface + implementation
2. **Servoy-Copilot** (`com.servoy.eclipse.opencode`) — create the console and wire it up

The bonus goal (item 4 in the ticket — redirect Java `ServoyLog` calls to the same console) can be done by adding a helper method in the opencode `Activator` that writes to the console stream, and calling it alongside `ServoyLog` in `RunOpencodeCommand` and `OpencodeFolderCreatorJob`.

## Git history findings

`RunNPMCommand.java` line 113 (the hardcoded console line) was last touched in `14febb667e0` (Johan Compagner, 2026-06-06, "Introduce end-of-line normalization") — a whitespace-only commit. The logic itself dates from the original TiNG build infrastructure. The console design was never intended to support multiple output destinations; it simply wasn't needed until opencode started sharing the same `IRunNPMCommand` mechanism.

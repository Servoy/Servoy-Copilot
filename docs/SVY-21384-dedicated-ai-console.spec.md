# Spec: SVY-21384 — Dedicated Servoy AI Console for opencode process output

## 1. Goal

Redirect all opencode-related process output (npm install, npm update, opencode serve) and Java diagnostic logging to a dedicated "Servoy AI Console" in the Eclipse Console view, instead of mixing it into the "Titanium NG Build Console". This gives developers a single, clean place to monitor opencode startup, session resume, shutdown, and any errors — without TiNG build noise.

## 2. Background

### 2.1 Current architecture

The opencode bundle (`com.servoy.eclipse.opencode`) uses the shared `IRunNPMCommand` interface from `com.servoy.eclipse.ngclient.ui` to run npm commands. The implementation (`RunNPMCommand`) hardcodes the output destination:

```java
// RunNPMCommand.java:113
StringOutputStream console = Activator.getInstance().getConsole().outputStream();
```

This always writes to the "Titanium NG Build Console" — a console created lazily by `com.servoy.eclipse.ngclient.ui.Activator.getConsole()` using `EclipseIOConsole` (which extends `IOConsole` and implements `IConsole`).

The same hardcoded console reference also appears in `RunNPMCommand.canceling()` (the cancel handler).

### 2.2 Affected call sites in the opencode bundle

1. `OpencodeFolderCreatorJob.run()` — `npm install` (fresh installation)
2. `OpencodeFolderCreatorJob.run()` — `npm update opencode-ai` (patch update check)
3. `RunOpencodeCommand.run()` — `npm exec -- opencode serve` (long-running server process)

All three write to the TiNG console because `IRunNPMCommand` offers no way for callers to specify an alternative output stream.

### 2.3 Console infrastructure types

| Type | Package | Role |
|------|---------|------|
| `StringOutputStream` | `c.s.e.ngclient.ui` | Interface: `write(CharSequence)` + `close()` |
| `IConsole` | `c.s.e.ngclient.ui` | Interface: `outputStream()` → `StringOutputStream` |
| `EclipseIOConsole` | `c.s.e.ngclient.ui` | Concrete impl wrapping `IOConsole` from `org.eclipse.ui.console` |

### 2.4 Git history

`RunNPMCommand.java` line 113 was last touched in `14febb667e0` (whitespace-only normalization). The console design dates from the original TiNG infrastructure and was never designed for multiple output destinations.

## 3. Design

### 3.1 Extend `IRunNPMCommand` with `setOutputStream`

Add a single optional setter to the interface:

```java
void setOutputStream(StringOutputStream outputStream);
```

This is backward-compatible: existing callers (15+ call sites for TiNG builds) never call it and continue using the default TiNG console. Only the opencode bundle will call it.

### 3.2 Update `RunNPMCommand` implementation

- Add a `private StringOutputStream customOutputStream` field (initially `null`).
- Add the `setOutputStream(StringOutputStream)` setter.
- In `runCommand()`: if `customOutputStream != null`, use it; otherwise fall back to `Activator.getInstance().getConsole().outputStream()`.
- In `canceling()`: same pattern — use the custom stream if set, otherwise the default.

### 3.3 Update `NoOpNPMCommand`

Add a no-op implementation of `setOutputStream`:

```java
@Override
public void setOutputStream(StringOutputStream outputStream) {
    // no-op: Node.js is not available
}
```

### 3.4 Create "Servoy AI Console" in the opencode `Activator`

Follow the same lazy-singleton pattern as `com.servoy.eclipse.ngclient.ui.Activator.getConsole()`:

- Add a `private IConsole aiConsole` field.
- Add a `public synchronized IConsole getConsole()` method that creates an `EclipseIOConsole` named `"Servoy AI Console"` with the console type `"servoyAiConsole"`, registers it with `ConsolePlugin.getDefault().getConsoleManager()`, and returns it.
- Use the same icon as the Servoy AI perspective (reuse the existing icon resource from the opencode bundle).
- The console does NOT auto-show on creation (unlike the TiNG console) to avoid stealing focus during background startup.

### 3.5 Wire up in `RunOpencodeCommand`

Before calling `serverCommand.runCommand(monitor)`:

```java
Activator activator = Activator.getInstance();
if (activator != null) {
    serverCommand.setOutputStream(activator.getConsole().outputStream());
}
```

### 3.6 Wire up in `OpencodeFolderCreatorJob`

Before calling `install.runCommand(monitor)` and `update.runCommand(monitor)`:

```java
Activator activator = Activator.getInstance();
if (activator != null) {
    install.setOutputStream(activator.getConsole().outputStream());
}
```

Same for the `update` command.

### 3.7 Add `org.eclipse.ui.console` to MANIFEST.MF

The opencode bundle needs access to `org.eclipse.ui.console` for `ConsolePlugin`, `IConsoleManager`, and `IOConsole`. Add it to `Require-Bundle`.

### 3.8 Bonus: unified Java logging to the console

Add a helper method in the opencode `Activator`:

```java
public void logToConsole(String message) {
    IConsole c = getConsole();
    if (c != null) {
        try {
            c.outputStream().write("[Servoy AI] " + message + "\n");
            c.outputStream().close();
        } catch (IOException ignored) { }
    }
}
```

Call this alongside `ServoyLog.logInfo()`/`ServoyLog.logError()` in `RunOpencodeCommand` and `OpencodeFolderCreatorJob` for key lifecycle events (server start, server ready, server exit, retry, npm install/update result). This gives unified diagnostics in the "Servoy AI Console" while preserving the `.log` file entries for standard Eclipse troubleshooting.

## 4. Implementation plan

1. **`com.servoy.eclipse.ngclient.ui` (servoy-eclipse, 2026.03 branch):**
   1. `IRunNPMCommand.java` — add `void setOutputStream(StringOutputStream outputStream);`
   2. `RunNPMCommand.java` — add `private StringOutputStream customOutputStream` field + setter; modify `runCommand()` to prefer the custom stream; modify `canceling()` to prefer the custom stream
   3. `NoOpNPMCommand.java` — add no-op `setOutputStream` implementation

2. **`com.servoy.eclipse.opencode` (this repo):**
   1. `META-INF/MANIFEST.MF` — add `org.eclipse.ui.console` to `Require-Bundle`
   2. `Activator.java` — add `private IConsole aiConsole` field + `getConsole()` lazy factory + `logToConsole(String)` helper
   3. `RunOpencodeCommand.java` — call `serverCommand.setOutputStream(activator.getConsole().outputStream())` before `runCommand()`; add `logToConsole()` calls for key lifecycle events
   4. `OpencodeFolderCreatorJob.java` — call `setOutputStream(...)` on both the `install` and `update` commands; add `logToConsole()` calls for install/update results

## 5. Acceptance criteria

- [ ] All opencode npm output (install, update, serve) appears in "Servoy AI Console" and NOT in "Titanium NG Build Console"
- [ ] Existing TiNG build output (e.g. `ng build`) still goes to "Titanium NG Build Console" (no regression)
- [ ] Callers that do not call `setOutputStream` continue using the default TiNG console (backward compatible)
- [ ] Key Java lifecycle messages (server start, server ready, exit code, retry) appear in "Servoy AI Console"
- [ ] "Servoy AI Console" does not auto-show/steal focus during background startup
- [ ] No compilation errors in either `com.servoy.eclipse.ngclient.ui` or `com.servoy.eclipse.opencode`
- [ ] Existing unit tests in `com.servoy.eclipse.opencode.tests` still pass

## 6. Out of scope

- Replacing `ServoyLog` calls entirely — both destinations continue to be written for now
- Adding colour or structured formatting to the AI console output
- Filtering or log-level control in the console

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the "Servoy AI Console" auto-reveal when an error occurs (e.g. non-zero exit code)? | Product | open |

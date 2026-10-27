# Spec: SVY-21643 — Servoy AI: graceful opencode shutdown on Windows (serve --stdio) and POSIX (SIGTERM process group)

## 1. Goal

Replace the brute-force process kill the `com.servoy.eclipse.opencode` bundle does when
Servoy Developer shuts down (or reloads) with a **graceful-first** shutdown that lets the
embedded opencode server end its running turns cleanly and stop its own MCP server process
trees before it is force-killed. On **Windows** this is done by launching `opencode serve
--stdio` with a piped stdin and closing that stdin lease on teardown (opencode's scoped
server effect ends on stdin EOF, since Windows has no real SIGTERM). On **POSIX** it is done
by launching opencode in its own process group and sending `SIGTERM` to the whole group, with
a short grace window, before escalating to `SIGKILL`. The existing `taskkill /F /T` +
PowerShell orphan-sweep (Windows) and `kill -9` (POSIX) are **kept as a last-resort fallback**,
not removed — so this cannot regress the orphan/port-lock safety net added in `6d66456`. The
payoff: the happy path no longer exercises the brute-force fallback, so leftover
`opencode.exe` / `bun.exe` (or `opencode` / `bun`) processes, bound ports, and the "Folder In
Use" lock largely stop happening, and an in-flight chat turn ends as *cancelled* instead of
reappearing as "still working" after a restart.

## 2. Background

### 2.1 How the server is launched today

`RunOpencodeCommand` (an Eclipse `Job`) resolves the native opencode executable under the
managed install dir and launches it via `RunExecutableCommand` with the Servoy project root as
cwd. The serve args are fixed:

```java
List<String> serveArgs = List.of("serve", "--port", String.valueOf(port), "--hostname", "127.0.0.1");
serverCommand = new RunExecutableCommand(opencodeExe, serveArgs, serverCwd);
```

`RunExecutableCommand` builds the process with `ProcessBuilder`, calls
`builder.redirectErrorStream(true)` (merging stderr into stdout), and **leaves stdin at its
default** — there is no stdin pipe, so there is no lease that can be closed to signal a clean
shutdown. It tracks the launched `Process` and exposes it through `getProcess()`; `cancel()`
calls `p.destroy()`.

Readiness is handled by `RunOpencodeCommand.startWatchdogThread(port)`, which HTTP-polls
`http://127.0.0.1:<port>/` every 500 ms and calls `Activator.serverStarted(port)` on the first
response. It does **not** parse any stdout "server listening" line.

### 2.2 How the server is stopped today

`Activator.stop()` → `Activator.stopServer()`:

1. Cancels the outer `RunOpencodeCommand` job (so the retry guard sees `monitor.isCanceled()`).
2. Calls `serverCommand.cancel()` (which does `process.destroy()`), then
   `killProcessTree(cmd)`.
3. `killProcessTree(cmd)` **immediately** brute-force kills:
   - **Windows:** `taskkill /F /T /PID <pid>`.
   - **POSIX:** `pkill -9 -P <pid>` then `kill -9 <pid>`.
   - Then a Java-API `destroyForcibly()` fallback if the pid is still alive.
   - Then `killOrphansUnderOpencodeDir(isWindows)` — a retrying (5×) PowerShell sweep on
     Windows that `taskkill /F /T`s every process whose command line references the managed
     install dir, or `pkill -9 -f <installDir>` on POSIX.
   - Then an **async daemon thread** (`OpenCode-ProcessStreamCloser`) closes the process
     streams to unblock the reader without risking a hang on IDE shutdown.
4. `stopServer()` also always runs `killOrphansUnderOpencodeDir(...)` a final time as a safety
   net.

There is **no grace window** anywhere — opencode is SIGKILL'd / `taskkill /F`'d the instant
shutdown begins, so it never ends turns or tears down its MCP children itself. That is the root
cause of the orphan/port-lock machinery this bundle carries (confirmed in the triage report).

### 2.3 What opencode 2.1.x provides (the mechanism the fix relies on)

From the triage report (verified against opencode's public `packages/cli/src/server-process.ts`
and recent PRs):

- `opencode serve --stdio` makes the scoped server effect end when **stdin reaches EOF/close**
  (`waitForStdinClose`). A scoped shutdown **cancels running turns and stops MCP servers with
  their process trees** — the same effect SIGTERM has on POSIX. This is the Windows mechanism.
- In **default** (non-stdio) mode the server effect is `Effect.never`, so only a signal ends
  it. opencode's own `SIGTERM`/`SIGINT`/`SIGHUP` handlers run `Instance.disposeAll()` (stopping
  MCP/LSP subprocesses) before exit. This is the POSIX mechanism.
- Under `--stdio`, `OPENCODE_SERVER_PASSWORD` is still honored and the **HTTP port is still
  bound the same way**, so the BFF servlet (`OpencodeChatServlet`) and the HTTP-poll readiness
  watchdog keep working unchanged.
- In `--stdio` mode opencode prints its readiness line as `{"url":"..."}` instead of
  `server listening on <url>`. This does **not** matter here, because the watchdog polls the
  HTTP port, not stdout.

The bundle is already pinned to `@opencode/cli` = `2.1.1` (via `opencode/package.json`), which
is a version that supports this.

## 3. Design

The change is a graceful-first teardown layered **in front of** the existing brute-force path,
with a platform split that mirrors the OpenChamber reference (`888ac0a` / PR #5128):

| Platform | Graceful primary path (new) | Fallback (existing, kept as last resort) |
|---|---|---|
| Windows | `serve --stdio` + piped stdin → close stdin lease, wait ~5 s for clean exit | `taskkill /F /T` + retrying PowerShell orphan-sweep |
| POSIX | launch in its own process group → `SIGTERM` to the group, wait ~2.5 s | `SIGKILL` to the group / `kill -9` + `pkill -9 -f <installDir>` sweep |

### 3.1 `RunOpencodeCommand` — serve args + POSIX process group

- **Windows serve args:** add `--stdio` to the serve args so stdin-close becomes a shutdown
  signal. The ticket and OpenChamber add `--stdio` **only where the stdin lease is actually
  used** — i.e. Windows at minimum. The implementation computes serve args per platform:
  Windows gets `serve --stdio --port <port> --hostname 127.0.0.1`; POSIX keeps `serve --port
  <port> --hostname 127.0.0.1` (it ends via SIGTERM, not stdin). Keep `--port` + `--hostname
  127.0.0.1` unchanged on both so the port is still bound for the watchdog/BFF.
- **POSIX process group:** opencode must be launched as the **leader of a new process group**
  so a single group-wide `SIGTERM`/`SIGKILL` reaches opencode's children (bun, MCP servers).
  The JDK has no first-class "new process group" API, so the implementation must choose one of:
  - wrap the launch in `setsid` (e.g. `setsid <opencode> serve …`) so the launched process
    becomes a new session/group leader whose pgid equals its pid; **or**
  - launch normally and rely on signalling via `kill -- -<pgid>` using the launched pid as the
    group id (only valid if opencode is actually a group leader — which it is not by default,
    so `setsid` is the safer choice).

  Decide in implementation; `setsid`-wrapped launch is recommended because it makes the
  group-leader pid deterministically equal to the tracked pid. **The group-leader pid must be
  the pid that `getProcess()` reports**, so `Activator.stopServer()` signals the right group.
  (`setsid` is present on both Linux and macOS.)
- The fallback-to-`npm exec` branch (when the native executable cannot be resolved) should keep
  working; the graceful niceties are a best-effort layer and must degrade to the existing
  behaviour if the executable path is not taken.

### 3.2 `RunExecutableCommand` — stdin pipe + `closeStdinLease()`

- **Stdin pipe:** change the `ProcessBuilder` so stdin is a real pipe the JVM holds the write
  end of (the default `ProcessBuilder.Redirect.PIPE` already gives `process.getOutputStream()`
  a usable write end — just do **not** redirect stdin to `INHERIT`). Keep
  `redirectErrorStream(true)` for stdout/stderr unchanged.
- **New method `closeStdinLease()`** on `RunExecutableCommand`: closes
  `process.getOutputStream()` (the child's stdin write end), which gives opencode's `--stdio`
  server its EOF and triggers the scoped graceful shutdown. It must be null-safe and
  idempotent (safe to call when the process never started or is already gone), and must not
  throw out of shutdown.
- **POSIX group-aware teardown hook:** `RunExecutableCommand` needs a way to signal the process
  **group** (not just the single pid) on POSIX. Options:
  - add a method like `terminateGroupGracefully()` that runs `kill -TERM -<pgid>` (or
    `process.destroy()` if the launch made the single pid the group leader) and a matching
    `killGroupForcibly()` that runs `kill -KILL -<pgid>`; **or**
  - keep the group-signalling logic in `Activator` and expose only the pid/pgid from
    `RunExecutableCommand`.

  Keep the division of responsibility consistent with how `Activator.killProcessTree` already
  reaches the pid via `getProcess().pid()`. Whichever placement is chosen, the graceful phase
  (close-stdin on Windows / SIGTERM-group on POSIX) must be callable from
  `Activator.stopServer()` **before** `killProcessTree`.
- The constructor/launch must record whatever is needed (the group-leader pid, a flag that a
  stdin lease exists) so the graceful methods work. The existing `cancel()` /
  `process.destroy()` behaviour stays as-is for the non-shutdown (job-cancel) path.
- Because `RunExecutableCommand` is referenced through the `IRunNPMCommand` interface in
  `Activator`/`RunOpencodeCommand`, the new graceful methods either go on that interface
  (preferred, so `stopServer()` stays interface-typed) **or** `stopServer()` does an
  `instanceof RunExecutableCommand` check before calling them. Prefer adding the methods to
  `IRunNPMCommand` as **default no-op methods** so the npm-fallback command and any other
  implementor are unaffected and no call site needs a cast — but note `IRunNPMCommand` lives in
  `com.servoy.eclipse.ngclient.ui` (a different bundle); if touching it is undesirable, fall
  back to the `instanceof` approach inside the opencode bundle. **Decide in implementation and
  record the choice in the PR.**

### 3.3 `Activator.stopServer()` — grace first, kill as fallback

Reorder `stopServer()` so the graceful phase runs first and the brute-force path only runs if
the process is still alive after the grace window:

1. Cancel the outer job (unchanged — keeps the retry guard honest).
2. **Graceful phase (new):**
   - **Windows:** call the stdin-lease close (`closeStdinLease()` on the command), then wait up
     to ~**5 s** for the process to exit on its own (poll `process.isAlive()` /
     `process.waitFor(timeout)`).
   - **POSIX:** send `SIGTERM` to opencode's **process group**, then wait up to ~**2.5 s** for
     exit.
   - Use `ProcessHandle`/`Process.waitFor(timeout, unit)` for the wait, not `Thread.sleep`, so
     a clean exit returns immediately.
3. **Fallback phase (existing, demoted):** if the process is still alive after the grace
   window, run the current `killProcessTree(cmd)` path (`taskkill /F /T` / `kill -9` + the
   orphan sweep + the async stream-closer). If it already exited, **skip** the brute-force kill
   but still run a single final `killOrphansUnderOpencodeDir(...)` safety sweep (cheap, and
   covers a detached child that outlived its parent).
4. Preserve the **async daemon stream-closer** (`OpenCode-ProcessStreamCloser`) semantics so a
   blocked stream close can never hang IDE shutdown — this is explicitly called out in the
   triage notes.
5. Keep all the defensive null-checks and the "bundle is stopping, `getStateLocation()` may
   throw" handling (cached `cachedOpencodeDir`) exactly as they are.

Grace timeouts (~5 s Windows, ~2.5 s POSIX) should be named constants so they are easy to tune
and to reference from tests/docs.

### 3.4 Readiness / BFF / watchdog — untouched

No change to `startWatchdogThread`, `OpencodeChatServlet`, `OpencodeServerState`, or the
`OPENCODE_SERVER_PASSWORD` handling. The port is still bound under `--stdio`, and the watchdog
polls the port, so readiness detection is unaffected. This is a hard constraint, not a
nice-to-have.

### 3.5 Git history

Carried from the triage report (do not re-dig):

- `6d66456` (2026-09-07, "Kill leftover opencode processes on Developer shutdown") introduced
  the retrying PowerShell orphan-sweep and the `taskkill /F /T` / `kill -9` teardown. Its own
  comment documents the detached-child / reparented-shell race as the reason. **This code must
  be kept as the fallback, not deleted.**
- `a2762a3` (SVY-21461, 2026-09-30) introduced `RunExecutableCommand` and the direct-native
  launch (cwd = project root), replacing the `npm exec` shell path. It removed the intermediate
  shell but left the brute-force teardown and did **not** add a stdin pipe or process-group
  launch — so the graceful path does not exist yet.
- `fa23b90` (SVY-24461) upgraded to opencode V2; `339df78` bumped the pin to `2.1.1`, which
  provides the `--stdio` stdin-close shutdown. The bundle is already on a supporting version.

## 4. Implementation plan

All Java changes are in `com.servoy.eclipse.opencode`. Use the Eclipse MCP tools
(`eclipse-coder_*`, `eclipse-ide_*`) — this is a Java/OSGi bundle.

1. **`RunExecutableCommand.java`**
   - Change the `ProcessBuilder` setup so the child's stdin is a JVM-held pipe (do not inherit
     stdin); keep `redirectErrorStream(true)`.
   - Add `closeStdinLease()` — null-safe, idempotent close of `process.getOutputStream()`; never
     throws out.
   - Add the POSIX group-signalling support: record the group-leader pid at launch, and add the
     graceful/forcible group-terminate methods (or expose the pgid so `Activator` does it —
     per §3.2 decision).
   - On POSIX, launch opencode as a new-process-group leader (`setsid` wrapper recommended) so
     the tracked pid is the group leader; preserve the Windows launch shape.
   - Leave `cancel()`/`destroy()` and all the `IRunNPMCommand` no-op members intact.

2. **`RunOpencodeCommand.java`**
   - Compute serve args per platform: add `--stdio` on Windows; leave POSIX args as today.
   - Ensure the POSIX launch puts opencode in its own process group (coordinate with the
     `setsid`/group change in `RunExecutableCommand`).
   - Keep the `npm exec` fallback branch and the watchdog unchanged. Update the class Javadoc to
     mention the `--stdio` / process-group shutdown model.

3. **`Activator.java`**
   - Rewrite `stopServer()` to: cancel job → graceful phase (Windows: close stdin lease + wait
     ~5 s; POSIX: SIGTERM group + wait ~2.5 s) → only if still alive, the existing
     `killProcessTree` fallback; always finish with a single orphan-sweep safety net.
   - Add named grace-timeout constants.
   - Keep `killProcessTree`, `killOrphansUnderOpencodeDir`, the async
     `OpenCode-ProcessStreamCloser`, and the `cachedOpencodeDir` handling as the fallback path
     (demoted, not removed).
   - If adding graceful methods to `IRunNPMCommand` is chosen over `instanceof`, call them
     through the interface; otherwise cast to `RunExecutableCommand` behind an `instanceof`
     guard.

4. **(Optional, per §3.2) `com.servoy.eclipse.ngclient.ui` / `IRunNPMCommand.java`**
   - If the interface route is chosen: add `default` no-op methods (e.g. `closeStdinLease()`,
     group-terminate hooks) so existing implementors compile unchanged. If the `instanceof`
     route is chosen, **do not** touch this bundle.

5. **Compile + quick-fix loop:** after each edit run `eclipse-ide_getCompilationErrors()` and
   apply safe quick fixes; ensure the workspace is error-free and check SpotBugs on the changed
   methods.

6. **Tests** (project `com.servoy.eclipse.opencode.tests`, package
   `...opencode.tests.plainjunit`; mirror the style of `RunOpencodeXdgEnvTest`, JUnit 5/6, no
   `@DisplayName` on the class):
   - A plain unit test for the per-platform serve-args helper (if extracted as a package-visible
     method) — asserts `--stdio` present on Windows args / absent on POSIX args, and that
     `--port`/`--hostname` are preserved. Prefer extracting the arg computation into a
     testable static/package-visible helper taking an `isWindows`/`os.name` input, so it is
     unit-testable without launching a process.
   - A plain unit test for `RunExecutableCommand.closeStdinLease()` being null-safe/idempotent
     when no process is running (does not throw).
   - Keep deep process-group / signal behaviour as **manual** per-platform verification (it
     cannot be CI-covered reliably) — note this in the PR and the acceptance criteria.

## 5. Acceptance criteria

- [ ] On **Windows** quit/reload of Servoy Developer, the graceful path runs first: the stdin
      lease is closed and opencode exits within the grace window **without** `taskkill /F /T`
      or the PowerShell orphan-sweep being exercised in the happy path.
- [ ] On **POSIX** (macOS/Linux) quit/reload, opencode receives a group `SIGTERM` and exits
      within the grace window **without** `kill -9` / `pkill -9 -f` being exercised in the
      happy path.
- [ ] After quit/reload on either platform, the opencode port is released and **no**
      `opencode.exe`/`bun.exe` (Windows) or `opencode`/`bun` (POSIX) processes survive.
- [ ] A chat with an **in-flight turn** ends as *cancelled* (opencode cancels the turn and
      stops its MCP children) rather than reappearing as "still working" after a restart.
- [ ] The brute-force `taskkill`/`kill -9` + orphan-sweep path still exists and still runs as a
      **last-resort fallback** when the graceful phase does not fully terminate the process
      within the grace window (i.e. `6d66456`'s safety net is not regressed).
- [ ] Readiness, the BFF servlet, `OPENCODE_SERVER_PASSWORD` auth, and the HTTP-poll watchdog
      behave exactly as before (the port is still bound under `--stdio`).
- [ ] IDE shutdown never hangs: the async stream-closer semantics are preserved and the grace
      waits use bounded `waitFor(timeout)`.
- [ ] Workspace compiles with zero errors; no new high-severity SpotBugs findings in the
      changed code.
- [ ] Plain unit tests for the per-platform serve-args helper and the idempotent
      `closeStdinLease()` pass.

## 6. Out of scope

- Changing the readiness-detection mechanism or parsing opencode's stdout `{"url":"..."}` line.
- Removing or rewriting the existing `taskkill`/`kill -9`/PowerShell orphan-sweep — it is
  demoted to a fallback, not deleted.
- Any change to `OpencodeChatServlet`, `OpencodeServerState`, `OpenCodeView`, `McpConfigWriter`,
  or the Angular `webui/` frontend.
- The `npm exec` fallback launch path beyond keeping it working (graceful niceties are
  best-effort there).
- Automated/CI coverage of the actual process-group signalling and stdin-EOF shutdown — these
  are manual, per-platform acceptance checks by design.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Expose the graceful hooks on `IRunNPMCommand` as `default` no-op methods (touches the `com.servoy.eclipse.ngclient.ui` bundle) vs. keep them on `RunExecutableCommand` and call via `instanceof` in `Activator`? | implementer | open |
| POSIX new-process-group mechanism: `setsid`-wrapped launch (recommended, deterministic pgid=pid) vs. `kill -- -<pgid>` on the launched pid? | implementer | open |
| Exact grace-window durations — adopt OpenChamber's ~5 s (Windows) / ~2.5 s (POSIX), or tune? | implementer / reviewer | open |

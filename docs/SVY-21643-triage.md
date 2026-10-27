# Triage Report — SVY-21643

**Verdict:** PROCEED

## Reported problem

On shutdown/reload of Servoy Developer, the `com.servoy.eclipse.opencode` bundle stops
its embedded opencode server with a **brute-force kill on every platform**:

- **Windows** (`Activator.killProcessTree` / `killOrphansUnderOpencodeDir`):
  `taskkill /F /T /PID` followed by a PowerShell orphan-sweep loop that enumerates every
  process whose command line references the managed opencode install dir and
  `taskkill /F /T`s each — retried up to 5 times.
- **POSIX** (`Activator.killProcessTree`): immediate `pkill -9 -P <pid>` + `kill -9 <pid>`,
  then a `pkill -9 -f <installDir>` sweep.

The observed consequence is that opencode is never given a chance to end running turns
cleanly or to shut down its own MCP server process trees. Symptoms:

- A chat with a running turn is force-killed mid-turn and can reappear as "still working"
  after restart instead of ending as cancelled.
- Orphaned `opencode.exe` / `bun.exe` (Windows) or `opencode` / `bun` (POSIX) processes
  survive, keep the port bound, and hold file locks on the install dir — the root of the
  heavy orphan/port-lock handling the bundle carries today (the "Folder In Use" error).

## Root-cause assessment

The brute-force teardown is a **symptom-driven accretion**, not a design choice, and it is
the proximate cause of the orphan/port-lock problem it then works around.

**Why the kill is brutal today — git history:**

- The POSIX `kill -9` / Windows `taskkill /F /T` path and the retrying PowerShell
  orphan-sweep were added in commit **`6d66456`** ("Kill leftover opencode processes on
  Developer shutdown", 2026-09-07) and the preceding revert chain (`8010da5`). The sweep
  comment in `Activator.stopServer()` states its own rationale: opencode "launches through
  an intermediate shell and spawns detached children … that Windows no longer tracks as
  descendants once the shell exits or is reparented." So the sweep exists precisely because
  the server was being hard-killed before it could tear its own children down.

- The launch model later changed in **`a2762a3`** (SVY-21461, 2026-09-30), which introduced
  `RunExecutableCommand` and made `RunOpencodeCommand` launch the **native opencode
  executable directly** (no intermediate npm/shell), with the Servoy project root as cwd.
  This removed one source of orphans (the intermediate shell) but kept the `kill -9` /
  `taskkill /F /T` teardown unchanged — so the tracked child is now opencode itself, yet it
  is still SIGKILL'd without a graceful phase.

**Why a clean shutdown is now possible — opencode 2.1.x (verified against public source):**

- opencode's `serve` supports a `--stdio` mode (`packages/cli/src/server-process.ts`). In
  that mode the scoped server effect ends when **stdin reaches EOF/close**
  (`waitForStdinClose`), and a scoped shutdown cancels running turns and stops MCP servers
  with their process trees — the same effect SIGTERM has on POSIX. In **default** (non-stdio)
  mode the server effect is `Effect.never`, so only a signal/kill ends it (confirmed by the
  web search: recent opencode PRs — #14092, #12718 — added `SIGTERM`/`SIGINT`/`SIGHUP`
  handlers that run `Instance.disposeAll()` to stop MCP subprocesses/LSP servers before
  exit). This is exactly why Windows, which has no real SIGTERM, needs the stdin lease
  instead.

- `OPENCODE_SERVER_PASSWORD` is still honored under `--stdio` and the HTTP port is still
  bound the same way, so the BFF servlet (`OpencodeChatServlet`) and the HTTP-poll readiness
  watchdog keep working unchanged.

**Why the current Servoy code cannot do a graceful stop as-is:**

- `RunExecutableCommand` launches opencode with `ProcessBuilder` and **no stdin pipe**
  (`builder.redirectErrorStream(true)` merges stderr→stdout; stdin is left at its default,
  so there is no lease to close). On Windows there is therefore no way to signal a clean
  shutdown — hence the sweep.
- `RunExecutableCommand.cancel()` calls `p.destroy()`. On POSIX `Process.destroy()` sends
  SIGTERM, **but only to the single launched pid, not to opencode's process group**, so
  opencode's own children (bun, MCP servers) are not signalled as a group; and `Activator`
  then immediately escalates to `kill -9` regardless, giving no grace window.
- The launch does **not** put opencode in its own process group, so a group-wide SIGTERM
  (`kill(-pid)`) is not available today.

**The watchdog is unaffected by the mode change.** `RunOpencodeCommand.startWatchdogThread`
HTTP-polls `http://127.0.0.1:<port>/` and does not parse the stdout readiness line, so the
fact that `--stdio` prints `{"url":"..."}` instead of `server listening on <url>` does not
matter.

Conclusion: the problem is real, is in **this** project's code, and is caused by killing
opencode before it can self-terminate. It is not a misconfiguration or a third-party defect.

## Ticket premise check

The ticket separates problem from solution and its proposed solution **holds up** on
inspection:

- The claim that `--stdio` + stdin-close gives a scoped, turn-cancelling, MCP-stopping
  shutdown on Windows is consistent with opencode's `server-process.ts` design and with the
  public PRs found during this triage.
- The claim that default mode needs a signal is consistent with `Effect.never` + the
  SIGTERM/SIGINT handlers opencode added.
- The "touched files" list (`RunOpencodeCommand`, `RunExecutableCommand`, `Activator`) is
  exactly the set that must change: serve args + POSIX process group in `RunOpencodeCommand`;
  stdin pipe + `closeStdinLease()` + group-aware launch in `RunExecutableCommand`; the
  grace-first / kill-as-fallback ordering in `Activator.stopServer()`.
- Keeping the existing taskkill/orphan-sweep as a **last-resort fallback** (not the main
  path) is the right call — it is a cheap safety net against the detached-child race the
  sweep comment documents, and removing it outright would regress `6d66456`.

One caveat worth flagging to the implementer (not a blocker): the ticket says "Windows: no
SIGTERM" — correct — but note that on **POSIX** `Process.destroy()` already sends SIGTERM to
the single pid. The real gap on POSIX is that it is not sent to the **process group** and is
immediately followed by `kill -9` with no grace window. The fix must (a) launch in a new
process group and (b) insert the grace delay before escalating — both are in the ticket's
proposal.

## Approaches considered

1. **Adopt the OpenChamber-style graceful shutdown (ticket's proposal).**
   Windows: launch `serve --stdio` with a piped stdin; on teardown close stdin, wait ~5s,
   then fall back to the existing `taskkill /F /T` + orphan-sweep. POSIX: launch opencode in
   its own process group, send SIGTERM to the group (`kill(-pid)` / `destroy()` on the group
   leader), wait a short grace (~2.5s), then SIGKILL the group. Keep the sweep as a
   last-resort net.
   - **Pros:** Fixes the root cause (opencode self-terminates, stops its MCP trees, cancels
     turns); the happy path no longer exercises the brute-force fallback, so orphans and
     port locks largely stop happening; readiness/BFF unchanged; mirrors a known-good
     OpenChamber implementation (`888ac0a` / PR #5128); retains the safety net so it is not a
     net regression if a corner case leaks a child.
   - **Cons:** Platform-specific process-group handling in Java (POSIX needs
     `kill -- -<pgid>` or a group-aware launch; the JDK has no direct "new process group"
     API, so it needs `setsid`/shell wrapping or `ProcessHandle` group handling — must be
     implemented carefully and is awkward to unit-test); requires per-platform **manual**
     verification (Windows + macOS/Linux), which CI cannot cover; adding a stdin pipe changes
     the `RunExecutableCommand` I/O shape slightly.

2. **POSIX-only: switch the current `kill -9` to SIGTERM-then-grace-then-SIGKILL, leave
   Windows on the sweep.**
   - **Pros:** Smaller change; fixes clean turn/MCP teardown on macOS/Linux where SIGTERM
     works; no stdin-lease plumbing.
   - **Cons:** Leaves Windows — the primary dev platform here, and where the "Folder In Use"
     orphan problem is worst — still on brute-force kill. Half a fix.

3. **No code change.**
   - **Pros:** Zero risk; the current brute-force path plus the retrying orphan sweep does,
     in practice, eventually release the port and kill orphans.
   - **Cons:** Does not fix the user-visible symptom (a running turn reappearing as "still
     working" after restart); keeps the heavy, race-prone orphan-sweep machinery as the main
     path; leaves MCP child process trees to be force-killed rather than stopped cleanly;
     ignores a capability opencode 2.1.x explicitly provides and that the bundle is already
     pinned to.

## Recommendation

**PROCEED with Approach 1** (the ticket's proposal). It addresses the documented root cause
on both platforms, keeps the existing teardown as a last-resort fallback so it cannot
regress `6d66456`, and leaves readiness/BFF/watchdog untouched. Approach 2 is an acceptable
fallback only if the Windows stdin-lease work proves too costly, but it leaves the worst case
(Windows orphans / port locks) unfixed, so it should not be the first choice. Approach 3 is
not appropriate — there is a real user-visible symptom and a clean, supported mechanism to
fix it.

Implementation notes for the next phase:

- Add `--stdio` to the serve args **only** where a stdin lease is actually used (Windows at
  minimum); confirm the HTTP port is still bound under `--stdio` in the pinned 2.1.1 build
  before relying on it (the watchdog depends on the port, not the stdout line).
- For the POSIX process group, the JDK offers no first-class "new process group" API —
  decide between a `setsid`-wrapped launch and signalling via `kill -- -<pgid>`; whichever
  is chosen, the group-leader pid must be the one tracked.
- Preserve the existing async-stream-close safety (the daemon `OpenCode-ProcessStreamCloser`
  thread in `Activator.killProcessTree`) so a blocked close never hangs IDE shutdown.
- Acceptance is manual and per-platform (Windows + macOS/Linux): on quit/reload the port is
  released, no `opencode`/`bun` survive, the brute-force fallback is not exercised in the
  happy path, and an in-flight turn ends as cancelled rather than resurfacing.

## Git history findings

- **`6d66456`** (2026-09-07, "Kill leftover opencode processes on Developer shutdown")
  introduced the retrying PowerShell orphan-sweep and the `taskkill /F /T` / `kill -9`
  teardown in `Activator.stopServer()`. Its own comment documents the detached-child /
  reparented-shell race as the reason. This is the code the ticket proposes to demote to a
  fallback — a fix must keep it, not delete it.
- **`a2762a3`** (SVY-21461, 2026-09-30) introduced `RunExecutableCommand` and the
  direct-native-executable launch (cwd = project root), replacing the `npm exec` shell path.
  It removed the intermediate shell but left the brute-force teardown and did **not** add a
  stdin pipe or process-group launch — so the graceful path the ticket wants does not exist
  yet.
- **`fa23b90`** (SVY-24461) upgraded to opencode V2; **`339df78`** bumped the pin to the
  `2.1.1` that supports the `--stdio`/stdin-close shutdown. The bundle is therefore already
  on a version that provides the mechanism the fix relies on (`opencode/package.json` pins
  `@opencode/cli` = `2.1.1`).
- Public opencode history corroborates the mechanism: PRs #14092 and #12718 added
  `SIGTERM`/`SIGINT`/`SIGHUP` handlers that run `Instance.disposeAll()` (stopping MCP/LSP
  child processes) before exit, and `server-process.ts` implements the `--stdio`
  stdin-close scoped shutdown described in the ticket.

# SVY-21384 review summary: Dedicated Servoy AI Console

**Risk: LOW to MODERATE.** The one real finding is that several opencode failure messages (install failed, server exited with no more retries, server process I/O error) moved from `ServoyLog` to the in-memory "Servoy AI Console" only. They no longer reach `.metadata/.log`, which contradicts spec §6 (both destinations keep being written).

**Scope reviewed:** Servoy-Copilot `master`, commits `b67dd49` (console), `9bd6380` (final URL log). Companion commit in servoy-eclipse: `788a0d3d93` (`IRunNPMCommand.setOutputStream`). Fix Version 2026.9.0 (already released).
`e0feaa6` (shutdown tree kill) and `d6602fb` (Jenkinsfile) were in the range but belong to SVY-21643 and are not covered here.

## Manual test plan

**Verifying the fix**
1. Start Servoy Developer with `GENAI_API_KEY` and `SERVOY_SKILLS_ZIP` set and open the Servoy AI view. A "Servoy AI Console" appears in the Console view with npm install/update output, `opencode serve` output and `[Servoy AI] ...` lifecycle lines (launching, ready, session query, final URL).
2. The "Titanium NG Build Console" does not contain opencode output.
3. Trigger a normal TiNG build. Its output still goes only to the Titanium NG Build Console.
4. Resume a session. The "querying sessions / resuming session / loading url" lines appear in the AI console.

**Regression checks**
1. Force a failure (corrupt `package.json`, or kill opencode repeatedly). Compare the Error Log view with the console for each failure message.
2. Open and close the AI view quickly, and exit Developer mid-startup. Look for NPEs in the Error Log.
3. Leave the server running for a long session and watch the console size (no output cap was verified).
4. Build Servoy-Copilot against each supported servoy-eclipse branch (`release`, `lts_2026`, `master`) to confirm `setOutputStream` exists on all.

**Automated:** `mvn clean verify -pl tests/com.servoy.eclipse.opencode.tests` (plain tests, including `ActivatorConsoleTest` and `RunNPMCommandOutputStreamTest`). These were not run during the review.

## Follow-ups and improvements
- Question posted to the issue (comment 234699): was dropping the `.log` output intentional? If not, restore `ServoyLog.logError` next to the console write.
- `OpenCodeView` calls `Activator.getInstance().logToConsole(...)` without a null check (only reachable around bundle shutdown).
- `OpenCodeView` read timeout went from 30 s to 60 s for the last-session query, and nothing explains why.
- Check whether the console has an output cap, and whether npm/opencode could ever print a secret in verbose output.
- Fixed during review: null guards for `getConsole()` in `RunOpencodeCommand` and `OpencodeFolderCreatorJob` (commit `71ae5ed`).
- Moved to SVY-21643: POSIX tree kill leaves grandchildren, PID-reuse kill without an `isAlive` guard, and a possible Windows hang when closing process streams.

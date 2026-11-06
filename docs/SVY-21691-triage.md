# SVY-21691 — Triage

**Issue:** SVY-21691 — *Keep the extracted skill pack + opencode config off plain disk: in-memory if feasible, else delete on IDE shutdown*
**Type:** New Feature · **Status:** Open · **Priority:** Major · R&D (unreleased Servoy AI product)
**Bundle:** `com.servoy.eclipse.opencode` (`bundles/com.servoy.eclipse.opencode`)
**Related:** SVY-21689 (relates to — SESSION_AUTH_TOKEN rotation)

---

## Verdict: PROCEED

The user has already settled the approach, scope, when, and what-to-keep. Nothing un-investigable remains. The live source confirms every premise the chosen design rests on. No clarifying input needed.

The scope is narrowed from the ticket's two-approach menu to **approach 2 only** (delete-on-exit + next-start safety net), framed explicitly as **lifecycle tidiness after a lost licence, not a security/secrecy measure**. The ticket's own investigation comment already proved client-side secrecy is unachievable; the user accepts that and is not chasing it.

---

## Reported problem

The skill pack and opencode config are extracted to disk at `~/.servoy/opencode/` (by `OpencodeFolderCreatorJob` / `SkillsZipExtractor`) and left there forever. A customer who later finds and reuses the files can claim "it was just a file on my own machine." The ticket asked to either keep the files off plain disk entirely (in-memory / XDG tmpfs — approach 1) or delete them at IDE shutdown with a next-start safety net (approach 2).

**User's authoritative narrowing (do not re-open):**
- This case is now **only** the delete-on-exit cleanup (approach 2). The tmpfs/XDG approach is set aside.
- Framing is **lifecycle tidiness**, not secrecy.
- Delete **only what the skills zip owns and would overwrite on the next extract**: the zip's top-level directories (the `cleanedDirs` set) **plus `opencode.json`**. Keep **all** runtime state — `opencode.db`, `sessions/`, auth, and anything not shipped by the zip.
- **When:** on **exit, always**, plus a **next-start safety net** for a crash / `kill -9` that skips `Activator.stop()`.
- The zip is a URL fetched at job time and **may not be available at shutdown**, so the zip-owned entry set must be **recorded at extract time** (a small manifest/marker file) and **read back at cleanup time** — never re-fetch the zip at exit. Prefer a **shared helper in `SkillsZipExtractor`** so extraction and cleanup compute the same set and cannot drift.

---

## Root-cause assessment (confirmed against live source)

### 1. What `extractToConfigDir` actually does — `SkillsZipExtractor.extractToConfigDir(InputStream, Path configDir)`
`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/SkillsZipExtractor.java:146-194`

- **(a) Does NOT wholesale-delete `configDir`.** It only `Files.createDirectories(configDir)` (line 147). There is no `deleteDirectory(configDir)` call anywhere in the class.
- **(b) Per top-level dir present in the zip, it recursively deletes that subdir before writing.** For each non-`opencode.json`, non-`AGENTS.MD` entry whose relative path has `getNameCount() > 1`, it takes `relative.getName(0)` as `topDir` and, on first encounter (`cleanedDirs.add(topDir)` — the `HashSet<String>` declared at line 149), recursively deletes `configDir.resolve(topDir)` via a reverse-ordered `Files.walk(...).forEach(Files::delete)` (lines 167-182). This is the authoritative `cleanedDirs` set the user refers to.
- **(c) Writes `opencode.json` from the zip** as-is into `configDir` (lines 162-165) and returns `wroteOpencodeJson = true`.
- **(d) Skips `AGENTS.MD`** (line 158-159) — handled separately by `writeOrUpdateAgentsMd` into the *project root*, not `configDir`.
- **(e) Leaves non-zip dirs untouched.** Only top-level dirs that appear *in the zip* are added to `cleanedDirs` and cleaned; `sessions/`, `opencode.db`, auth, etc. are never visited, so they survive. The Javadoc at lines 127-139 states this explicitly.

**→ The zip-owned entry set == `cleanedDirs` (top-level dir names seen in the zip) + `opencode.json` (when `wroteOpencodeJson`).** This is exactly the set the cleanup must delete, and exactly what a manifest must record.

### 2. The config dir path
`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:210`
```java
Path servoyOpencodeCfgDir = Path.of(System.getProperty("user.home"), ".servoy", "opencode");
```
Confirmed: `~/.servoy/opencode/` is built in **exactly one production location**. It is passed to `extractToConfigDir` (line 229) and `McpConfigWriter.mergeConfig(...opencode.json)` (line 251) / `ProviderConfigWriter.mergeProviderConfig(...)` (line 261). (The only other occurrence is `com.servoy.eclipse.opencode.skilltest` `PackCatalogReader.java:102`, a test helper.)

### 3. Shutdown entry point — `Activator.stop()` → `stopServer()`
`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/Activator.java`

- `stop(BundleContext)` (lines 148-153) calls `stopServer()` then nulls `instance`. This is the shutdown entry point.
- `stopServer()` (lines 274-370) tears down the opencode child: **graceful phase** (Windows `closeStdinLease()` / POSIX `terminateGroupGracefully()`, then a bounded `process.waitFor(graceMs)` — `WINDOWS_GRACE_MS=5000`, `POSIX_GRACE_MS=2500`), then a **brute-force fallback** (`killProcessTree` → `taskkill /F /T` / `kill -9`) **only if the process did not exit gracefully**, and **always** a final `killOrphansUnderOpencodeDir(isWindows)` sweep (line 369).
- **Critical ordering constraint:** on Windows a live `opencode.exe` / `bun.exe` holds file locks and keeps the port bound; the existing code's own comments (lines 362-368, 466-479) say leftover children are what cause the "Folder In Use" error when a dir is cleaned. **The new `~/.servoy/opencode/` cleanup must therefore run AFTER the child is confirmed gone — i.e. after the graceful wait + fallback kill + `killOrphansUnderOpencodeDir` sweep, at the very end of `stopServer()`.**
- **Scope note:** everything `stopServer()` touches today is the **INSTALL dir** `{state}/opencode` (`cachedOpencodeDir` / `getOpencodeDir()`, lines 88-92, 497-507) and the processes. The **skills config dir `~/.servoy/opencode/`** is a *different* directory and is a **NEW, separate concern** — there is no overlap and no existing teardown of it.

### 4. Next-start safety net home — `OpencodeFolderCreatorJob.run()` early-return guard
`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpencodeFolderCreatorJob.java:73-81`
```java
String apiKey = ProviderConfigWriter.getApiKey();
if (apiKey == null || apiKey.isBlank() || SkillsZipExtractor.getSkillsZipSource() == null) {
    ServoyLog.logInfo("Servoy AI: not configured (GENAI_API_KEY or SERVOY_SKILLS_ZIP missing), skipping setup.");
    return Status.OK_STATUS;
}
```
Confirmed: the guard uses `ProviderConfigWriter.getApiKey()` (the SVY-21750 change — secure-store-first key accessor, also used by `BearerTokenAuthenticationFilter` line 121 and `OpenCodeView.isServoyAiConfigured()`). This early-return is the **natural home for the next-start safety-net cleanup**: it is the licence-lost / crash case. If the licence/key is gone or the zip source is absent on the next start, the job returns here without re-extracting — so a cleanup call placed just before the `return` (reading the recorded manifest, deleting the zip-owned set) closes the "crash on purpose / `kill -9` left files behind, and the licence is now gone so nothing will ever overwrite them" gap. On the happy path (still licensed), the job proceeds past the guard and `extractToConfigDir` re-cleans + re-writes the same set anyway.

### 5. `shouldSkipExtraction()` interaction
`SkillsZipExtractor.java:88-90` — returns true only when `SERVOY_SKILL_OVERWRITE` is explicitly `"false"`. The job honors it (`OpencodeFolderCreatorJob.java:219-222`: when skipping, it does not extract). The design must stay consistent: if extraction is skipped, the recorded manifest should be left as-is (do not clean on next start just because overwrite is off while still licensed). Cleanup belongs to the exit hook and the *licence-lost* guard path, not the overwrite-skip path.

### 6. No existing cleanup of `~/.servoy/opencode/` anywhere — CONFIRMED
`deleteDirectory(Path)` exists only in `OpencodeFolderCreatorJob` (declared line 415) and is called exactly once (line 120) against the **INSTALL dir** `opencodeDir = {state}/opencode` during a clean reinstall. Nothing deletes or cleans the **config dir** `~/.servoy/opencode/`. The challenge question is answered: the content is *never* cleaned today.

---

## Ticket premise check (challenge)

**Is delete-on-exit the right lever, or a no-op since re-extract overwrites anyway?**
Not a no-op. Re-extract (`extractToConfigDir`) only cleans the top-level dirs **the next zip actually ships** (the `cleanedDirs` set is built from entries *present in that zip*). A user whose licence has lapsed fetches **no new zip** (`getSkillsZipSource()`/the API key gates it), so `extractToConfigDir` never runs and the previously-extracted skill dirs + `opencode.json` **persist on disk forever**. That is precisely the stale-content case the ticket targets. **Cleanup is warranted**, and the only reliable moment is exit (while licensed the files must stay present for the running session), with a next-start net for crashes.

**Secrecy vs tidiness.** The ticket's own investigation comment (Johan Compagner, 2026-11-06) established that neither approach delivers real secrecy — opencode reads the files as the same OS principal, so "visible to opencode but invisible to the user" is impossible, and the only true-secrecy design is server-side skill injection (a different product). The user has accepted this and reframed the case as lifecycle tidiness. **No security claim should be made in the spec or the code comments** — honest framing only.

**Divergence test:** PASS (PROCEED). The user pre-decided scope (zip-owned set + `opencode.json`), when (exit always + next-start net), and keep-set (all runtime state). Live source confirms the hooks, the set definition, and the ordering constraint exist. No contradictions, no missing facts that block design.

---

## Approaches considered

### A — Manifest-at-extract + delete-on-exit in `stopServer()` + next-start safety net in the job guard, via a shared helper  ✅ RECOMMENDED
- **Record:** in `SkillsZipExtractor.extractToConfigDir`, after computing `cleanedDirs` + `wroteOpencodeJson`, write a small manifest/marker file into `configDir` (e.g. a plain list of the zip-owned top-level dir names + `opencode.json`). The set is computed in one place.
- **Shared helper:** add helpers to `SkillsZipExtractor` so extraction *writes* the manifest and cleanup *reads* it and deletes exactly that set — both derive the set from the same code, so they cannot drift (user's explicit requirement).
- **Delete-on-exit:** at the **end of `Activator.stopServer()`**, after the child is confirmed gone (after the graceful wait, the demoted brute-force fallback, and `killOrphansUnderOpencodeDir`), read the manifest from `~/.servoy/opencode/` and recursively delete only the recorded zip-owned dirs + `opencode.json`. Never re-fetch the zip. Non-fatal on error (same error discipline as the rest of `stopServer()`).
- **Next-start safety net:** in `OpencodeFolderCreatorJob.run()`, inside the early-return config guard (before `return Status.OK_STATUS;` at line 80), when the key/zip source is absent (licence gone / crash case), read the manifest and delete the recorded set — closing the `kill -9`/crash gap. On the happy path the job falls through and `extractToConfigDir` re-cleans + re-writes the same set, so no special-casing is needed there.
- **Keep-state set (never touched):** `opencode.db`, `sessions/`, auth, and any dir/file not listed in the manifest. Deletion is driven strictly by the recorded zip-owned set, so runtime state is structurally safe.
- **Why best:** matches the user's decided design exactly; single source of truth for the set; reliable at exit; crash-resilient; respects the Windows file-lock ordering; keeps the bundle's non-fatal error style; testable in the existing `com.servoy.eclipse.opencode.tests` fragment (manifest round-trip + cleanup-deletes-only-zip-owned-set are pure-JUnit `plainjunit` candidates).

### B — Delete the whole `~/.servoy/opencode/` on exit  ❌ REJECTED
Wipes `opencode.db`, `sessions/`, and auth — violates the user's explicit keep-all-runtime-state rule. Also forces a full re-login / lost history on every restart.

### C — No code change  ❌ REJECTED
Stale skill dirs + `opencode.json` persist forever once a licence lapses (re-extract never runs without a fetched zip). Fails the ticket's goal.

### (Out of scope) Approach 1 — in-memory / XDG tmpfs
Set aside by the user; also not natively cross-OS (no tmpfs/`/dev/shm` on Windows) per the ticket's own analysis. Not pursued.

---

## Recommendation

Implement **Approach A**:
1. **Manifest at extract** — `SkillsZipExtractor.extractToConfigDir` writes a marker file into `~/.servoy/opencode/` listing the zip-owned top-level dir names (the `cleanedDirs` set) + `opencode.json` (when written).
2. **Shared helper in `SkillsZipExtractor`** — one place computes/records the set and one place reads/deletes it, so extraction and cleanup cannot drift.
3. **`Activator.stopServer()` hook** — at the very end, after the opencode child is confirmed gone (graceful wait → demoted fallback kill → `killOrphansUnderOpencodeDir`), read the manifest and delete only the recorded zip-owned set from the config dir. Never re-fetch the zip. Non-fatal.
4. **Job-guard next-start safety net** — in `OpencodeFolderCreatorJob.run()`'s early-return config guard (licence/key or zip source absent), read the manifest and delete the recorded set before returning, to cover a crash / `kill -9` that skipped `stop()`.
5. **Keep-state set** — delete strictly from the recorded manifest; `opencode.db`, `sessions/`, auth, and anything not shipped by the zip are never touched.
6. **Framing** — lifecycle tidiness after a lost licence, **not** secrecy. No security claims in spec or code comments.

---

## Git history findings

- `6c0f54d` *"SVY-21175 fix skills zip extraction for flat directory structure [ai]"* (Johan Compagner, 2026-06-11) established the current `extractToConfigDir` design: replaced the old `.opencode/`-only extraction with the general single-pass algorithm, introduced the per-top-level-dir `cleanedDirs` clean (zip is authoritative), preserved dirs not present in the zip (e.g. `sessions/`), and skipped `AGENTS.MD`. This is the commit that created the exact "zip-owned top-level dir" set the cleanup must reuse.
- `ffb2172` added `SERVOY_SKILL_OVERWRITE` (the `shouldSkipExtraction()` gate).
- No commit has ever added cleanup/teardown of `~/.servoy/opencode/`; `deleteDirectory` has only ever targeted the install dir `{state}/opencode`.

---

*No questions for the reporter — verdict is PROCEED.*

*-- triage assistant [ai]*

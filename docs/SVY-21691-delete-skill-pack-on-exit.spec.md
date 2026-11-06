# SVY-21691 — Delete the extracted skill pack (zip-owned set) on IDE exit

**Issue:** SVY-21691 — *Keep the extracted skill pack + opencode config off plain disk: in-memory if feasible, else delete on IDE shutdown*
**Type:** New Feature · **Status:** Open · **Priority:** Major · R&D (unreleased Servoy AI product)
**Bundle:** `com.servoy.eclipse.opencode` (`bundles/com.servoy.eclipse.opencode`)
**Related:** SVY-21689 (relates to — SESSION_AUTH_TOKEN rotation)
**Approved approach:** Approach A (triage `docs/SVY-21691-triage.md`) — manifest-at-extract + shared write/cleanup helpers in `SkillsZipExtractor`; delete-on-exit at the end of `Activator.stopServer()` after the child is gone; next-start safety net in the `OpencodeFolderCreatorJob` config guard; delete strictly the recorded zip-owned set, keep all other state.

---

## 1. Goal

When a user no longer has a working Servoy AI configuration (licence/key or skills-zip source gone), the previously-extracted skill pack and the zip-shipped `opencode.json` must not linger forever under `~/.servoy/opencode/`. Clean up **exactly the entries the skills zip owns** — the zip's top-level directories plus `opencode.json` (when the zip shipped it) — when the IDE exits, with a next-start safety net for crashes that skip the normal shutdown path. **Keep all runtime state** (`opencode.db`, `sessions/`, auth, and anything the zip did not ship).

This is **lifecycle tidiness**, not a security or secrecy measure. The IDE runs as the same OS principal as the opencode process that reads these files, so client-side secrecy is unachievable (established in the ticket's own investigation comment and accepted by the user). **No security claim is made in this spec, and none must appear in the code or code comments.** The only guarantee is: the zip-owned set is removed at the deterministic exit point and backstopped on next start.

## 2. Background

### 2.1 How the content gets on disk today

`OpencodeFolderCreatorJob.run()` resolves the config dir once —
`Path.of(System.getProperty("user.home"), ".servoy", "opencode")` (`OpencodeFolderCreatorJob.java:210`) — and calls `SkillsZipExtractor.extractToConfigDir(zip, configDir)` (`:228`) to materialize the skill pack and (if the zip ships it) `opencode.json`. The config dir `~/.servoy/opencode/` is distinct from the **install dir** `{state}/opencode` that `Activator.stopServer()` already tears down; the two never overlap.

`extractToConfigDir` (`SkillsZipExtractor.java:146-193`) is authoritative about what the zip owns:
- It does **not** wholesale-delete `configDir`; it only `Files.createDirectories(configDir)`.
- For every non-`opencode.json`, non-`AGENTS.MD` entry whose relative path has `getNameCount() > 1`, it takes `relative.getName(0)` as `topDir` and, on first encounter (`cleanedDirs.add(topDir)` on the `HashSet<String>` at `:149`), recursively deletes `configDir.resolve(topDir)` via a reverse-ordered `Files.walk(...).forEach(Files::delete)` (`:170-183`), non-fatal per path.
- It writes `opencode.json` from the zip as-is and returns `wroteOpencodeJson = true` (`:163-165, 192`).
- It skips `AGENTS.MD` (handled separately into the project root, not `configDir`).
- Everything else under `configDir` (`sessions/`, `opencode.db`, auth) is never visited, so it survives.

**→ The zip-owned entry set == `cleanedDirs` (top-level dir names seen in the zip) + `opencode.json` when `wroteOpencodeJson`.** That is exactly the set cleanup must delete and exactly what a manifest must record.

### 2.2 Why delete-on-exit is the right lever, not a no-op

Re-extract only cleans the top-level dirs the **next** zip actually ships, and the next zip is gated behind `ProviderConfigWriter.getApiKey()` + `SkillsZipExtractor.getSkillsZipSource()`. A user whose licence lapses fetches **no** zip, so `extractToConfigDir` never runs again and the previously-extracted skill dirs + zip-shipped `opencode.json` persist forever. Cleanup is warranted; the only safe moment is exit (while licensed the files must stay present for the running session), with a next-start net for crashes.

### 2.3 Why record a manifest instead of re-deriving the set at exit

The zip source is a URL fetched during the job and is **not available at shutdown**; re-fetching at exit is neither possible (licence may be gone) nor allowed. So the zip-owned set must be **recorded when it is known (at extract)** and **read back at cleanup** — never re-fetched. A shared helper pair in `SkillsZipExtractor` guarantees extraction and cleanup use the same set definition and cannot drift (the user's explicit requirement).

### 2.4 Shutdown ordering constraint (Windows file locks)

`Activator.stopServer()` (`Activator.java:272-359`) tears down the opencode child in order: graceful phase (Windows `closeStdinLease()` / POSIX `terminateGroupGracefully()`, then a bounded `process.waitFor(graceMs)` — `WINDOWS_GRACE_MS` / `POSIX_GRACE_MS`), a brute-force fallback (`killProcessTree`) **only if** the child did not exit gracefully, and **always** a final `killOrphansUnderOpencodeDir(isWindows)` sweep (`:358`). On Windows a live `opencode.exe`/`bun.exe` holds file locks; the code's own comments (`:351-357`) say leftover children cause the "Folder In Use" error when a dir is cleaned. **Config-dir cleanup must therefore run AFTER the child is confirmed gone — at the very end of `stopServer()`, after that final orphan sweep.**

## 3. Design

### 3.1 Manifest file — single source of truth

- **Location:** `configDir/.servoy-skills-manifest` (i.e. `~/.servoy/opencode/.servoy-skills-manifest`). Dot-prefixed and clearly internal so it is not mistaken for shipped content.
- **Format:** plain UTF-8, one zip-owned entry name per line (newline-separated, `\n`), no header, no trailing structure. Each line is a config-dir-relative top-level name:
  - every top-level directory name in the zip-owned set (the `cleanedDirs` names), and
  - the literal `opencode.json` **only when `wroteOpencodeJson` is true**.
- Empty lines are ignored on read. The parse is trivial and exact (read lines, trim, drop blanks).
- The manifest lists only **top-level** names (dir names + `opencode.json`); cleanup resolves each against `configDir` and, for a directory, deletes it recursively.

### 3.2 Two shared static helpers on `SkillsZipExtractor`

Added so extraction *writes* the set and cleanup *reads/deletes* the same set, with no drift. Both `public static` so the test fragment can exercise them without OSGi.

```java
/** Name of the internal manifest file written into the config dir. */
public static final String SKILLS_MANIFEST = ".servoy-skills-manifest";

/**
 * Records the zip-owned top-level entry names (zip top-level dir names, plus
 * "opencode.json" when the zip shipped it) into configDir/.servoy-skills-manifest,
 * one name per line (UTF-8). Overwrites any previous manifest. Non-fatal: a write
 * failure is swallowed (lifecycle tidiness, never a reason to fail extraction).
 */
public static void writeManifest(Path configDir, Set<String> zipOwnedEntries) { ... }

/**
 * Reads configDir/.servoy-skills-manifest and recursively deletes each listed
 * top-level entry (a directory is removed with the same reverse-ordered
 * Files.walk + Files.delete style as extractToConfigDir; opencode.json is a single
 * file delete). Everything in configDir not listed in the manifest is left
 * untouched. No manifest -> no-op (quiet return). Per-entry and overall non-fatal:
 * an already-missing entry or a delete failure is swallowed and cleanup continues.
 * After cleaning the listed entries, the manifest file itself is deleted so a stale
 * manifest does not linger.
 */
public static void cleanupZipOwnedEntries(Path configDir) { ... }
```

Design notes:
- `writeManifest` is called at the **end of `extractToConfigDir`**, after `cleanedDirs` and `wroteOpencodeJson` are known, with `zipOwnedEntries = cleanedDirs` ∪ (`{"opencode.json"}` if `wroteOpencodeJson`). The simplest shape: build a `Set<String>` (copy of `cleanedDirs`, add `OPENCODE_JSON` when `wroteOpencodeJson`) and pass it to `writeManifest(configDir, set)` just before the `return wroteOpencodeJson;`.
- `cleanupZipOwnedEntries` reuses the **exact recursive-delete style** already in `extractToConfigDir` (reverse-ordered `Files.walk`, non-fatal per path) so directory removal behaves identically.
- Cleanup **deletes the manifest file itself** after processing the listed entries (recommended and adopted), so a crash-and-cleanup cycle does not leave a stale manifest that would make a later cleanup try to re-delete already-gone entries. (Those re-deletes would be harmless no-ops anyway, but removing the manifest keeps state clean.)
- Deletion is driven **strictly** by the recorded set. There is no code path in which cleanup removes anything not listed in the manifest; the config dir is never blanket-deleted. This is what makes runtime state (`opencode.db`, `sessions/`, auth) structurally safe.

### 3.3 Delete-on-exit hook — `Activator.stopServer()`

- Add **one** call at the **very end** of `stopServer()`, **after** `killOrphansUnderOpencodeDir(isWindows)` (`Activator.java:358`) — i.e. after the graceful wait, the demoted brute-force fallback, and the final orphan sweep, so the child is confirmed gone and no live process holds locks on the config dir.
- The config dir is resolved locally (it is a different path from the install dir `stopServer()` otherwise deals with):
  `Path configDir = Path.of(System.getProperty("user.home"), ".servoy", "opencode");`
  (Optionally via a small private helper, e.g. `getServoyOpencodeConfigDir()`, to keep the literal in one place; a helper is not required.)
- Call `SkillsZipExtractor.cleanupZipOwnedEntries(configDir)`.
- **Guard:** `cleanupZipOwnedEntries` already no-ops when there is no manifest, so an unconditional call is acceptable. A cheap `Files.exists(configDir)` / manifest-exists pre-check is optional.
- **Not conditional on whether this session launched a server.** A previous run may have left a manifest; the hook must still clean it. (The no-op-without-manifest behavior makes this safe.)
- **Non-fatal:** wrap the call in `try/catch` and log via `ServoyLog` on failure, matching `stopServer()`'s existing error discipline. A cleanup failure must never stall or break IDE shutdown.
- Add a short code comment stating the intent as **lifecycle tidiness** (no security wording).

### 3.4 Next-start safety net — `OpencodeFolderCreatorJob.run()` early-return guard

- Inside the existing early-return config guard (`OpencodeFolderCreatorJob.java:76-81`) — reached when `ProviderConfigWriter.getApiKey()` is null/blank **or** `SkillsZipExtractor.getSkillsZipSource()` is null (the licence-lost / crash case) — **before** `return Status.OK_STATUS;`, resolve the same config dir and call `SkillsZipExtractor.cleanupZipOwnedEntries(servoyOpencodeCfgDir)`:
  ```java
  if (apiKey == null || apiKey.isBlank() || SkillsZipExtractor.getSkillsZipSource() == null) {
      ServoyLog.logInfo("Servoy AI: not configured (GENAI_API_KEY or SERVOY_SKILLS_ZIP missing), skipping setup.");
      // Lifecycle tidiness: a crash / kill -9 may have skipped the exit hook and left the
      // previously-extracted zip-owned content behind. With no licence/key/zip it will never
      // be overwritten, so remove exactly the recorded set now. No-op when no manifest exists.
      Path servoyOpencodeCfgDir = Path.of(System.getProperty("user.home"), ".servoy", "opencode");
      SkillsZipExtractor.cleanupZipOwnedEntries(servoyOpencodeCfgDir);
      return Status.OK_STATUS;
  }
  ```
  (The job resolves `servoyOpencodeCfgDir` later at `:210` on the happy path; in the guard it resolves its own local copy of the same `~/.servoy/opencode/` path.)
- This closes the gap where a crash / `kill -9` skipped `Activator.stop()`: on the next start, if the licence/key/zip is now gone, the leftover zip-owned content is removed.
- Keep the call itself non-fatal (the helper is already non-fatal internally; if any checked exception could escape the resolution, guard it so the early return still happens).

### 3.5 Happy-path and overwrite-skip behavior (no extra cleanup)

- **Happy path (still licensed):** nothing special. The job proceeds past the guard; `extractToConfigDir` re-cleans the zip's top-level dirs and re-writes `opencode.json`, then (per 3.2) re-writes the manifest with the current set. No explicit cleanup needed, because extraction already re-establishes the exact set + manifest.
- **Overwrite-skip path (`shouldSkipExtraction()` true while still licensed, `SERVOY_SKILL_OVERWRITE=false`):** **do not** clean. The existing manifest is left as-is; the user has deliberately asked to keep the current extraction. Cleanup belongs only to the exit hook (3.3) and the licence-lost guard (3.4).

### 3.6 Interaction note — `opencode.json` and the job's always-write of it

`extractToConfigDir` reports `wroteOpencodeJson` only when the **zip** shipped `opencode.json`. The job, however, always writes/merges `opencode.json` afterwards via `McpConfigWriter.mergeConfig(...)` (`OpencodeFolderCreatorJob.java:251`) and, when the zip did **not** supply it, `ProviderConfigWriter.mergeProviderConfig(...)` (`:259-261`). So a non-zip `opencode.json` (MCP-merged / provider-merged) can exist on disk even when `wroteOpencodeJson` is false.

Per the user's explicit rule, the manifest records `opencode.json` **only when the zip wrote it** (`wroteOpencodeJson`), so the MCP/provider-merged variant is **kept**, not deleted, on cleanup. This is the chosen behavior: cleanup removes only what the zip owns. (See Open Questions for whether `opencode.json` should always be cleaned regardless of origin — current spec says no.)

## 4. Implementation plan

1. **`SkillsZipExtractor`** — add `public static final String SKILLS_MANIFEST = ".servoy-skills-manifest";`, add `public static void writeManifest(Path configDir, Set<String> zipOwnedEntries)` and `public static void cleanupZipOwnedEntries(Path configDir)` (both non-fatal, reusing the existing reverse-ordered `Files.walk` delete style for directories). Extract the recursive-delete logic into a tiny private helper if it reads cleaner shared between `extractToConfigDir` and `cleanupZipOwnedEntries` — behavior must stay identical.
2. **`SkillsZipExtractor.extractToConfigDir`** — at the end, before `return wroteOpencodeJson;`, build the zip-owned set (`cleanedDirs` plus `OPENCODE_JSON` when `wroteOpencodeJson`) and call `writeManifest(configDir, set)`.
3. **`Activator.stopServer()`** — at the very end, after `killOrphansUnderOpencodeDir(isWindows)`, resolve `~/.servoy/opencode/` and call `SkillsZipExtractor.cleanupZipOwnedEntries(configDir)` inside a `try/catch` that logs via `ServoyLog`. Comment the intent as lifecycle tidiness.
4. **`OpencodeFolderCreatorJob.run()`** — inside the existing licence-lost early-return guard (`:76-81`), before `return Status.OK_STATUS;`, resolve the same config dir and call `SkillsZipExtractor.cleanupZipOwnedEntries(servoyOpencodeCfgDir)`.
5. **Compile-clean loop** — run `eclipse-ide_getCompilationErrors`, apply safe quick fixes, re-check to zero errors. Address the two highest SpotBugs severities in new/changed code.

## 5. Acceptance criteria

Concrete and testable. Those marked *(code review / integration)* are not plain-unit-testable.

1. After `extractToConfigDir` runs, a manifest file `~/.servoy/opencode/.servoy-skills-manifest` exists listing **exactly** the zip's top-level directory names, plus `opencode.json` **iff** the zip shipped `opencode.json`.
2. When the zip does **not** ship `opencode.json`, the manifest does **not** list `opencode.json`.
3. `cleanupZipOwnedEntries(configDir)` deletes **exactly** the top-level dirs and `opencode.json` listed in the manifest, and **nothing else**.
4. A planted `opencode.db`, `sessions/` directory, auth file, and any unlisted directory **survive** `cleanupZipOwnedEntries`.
5. With **no** manifest present, `cleanupZipOwnedEntries` is a **no-op** (does not throw, deletes nothing).
6. `cleanupZipOwnedEntries` is **non-fatal** when a listed entry is already gone (continues and returns normally).
7. After `cleanupZipOwnedEntries` completes, the manifest file itself is deleted.
8. *(code review / integration)* `Activator.stopServer()` calls `cleanupZipOwnedEntries` **only at the very end**, after the graceful wait, the brute-force fallback, and `killOrphansUnderOpencodeDir` — i.e. after the child is confirmed gone.
9. *(code review / integration)* `OpencodeFolderCreatorJob.run()`'s licence-lost early-return guard calls `cleanupZipOwnedEntries` **before** returning `Status.OK_STATUS`.
10. *(code review)* On the happy path, `extractToConfigDir` re-cleans the zip's top-level dirs and re-writes `opencode.json` + a fresh manifest listing the same set; the overwrite-skip path performs **no** cleanup and leaves the existing manifest untouched.
11. *(code review / integration)* A cleanup failure never propagates out of `stopServer()` and never stalls or breaks IDE shutdown (wrapped `try/catch` + `ServoyLog`).

## 6. Test obligations (test phase — do NOT write in the implementation phase)

`SkillsZipExtractorTest` exists in both `...plainjunit` and `...integrationpde` in `com.servoy.eclipse.opencode.tests`. New **pure-logic** cases belong in `plainjunit`, using `@TempDir` for the config dir and JUnit 5/6 Jupiter conventions (`@DisplayName` only on `@Test`/`@Nested` methods, no `public`, no silent skips, no `@Suite`):

- **Manifest round-trip:** `extractToConfigDir` over a crafted zip writes a manifest listing exactly the zip's top-level dirs (+ `opencode.json` when shipped).
- **Cleanup deletes only the zip-owned set:** `cleanupZipOwnedEntries` removes exactly the listed top-level dirs + `opencode.json`, and **keeps** a planted `opencode.db`, `sessions/`, and an unlisted dir.
- **No-manifest no-op:** `cleanupZipOwnedEntries` on a config dir with no manifest deletes nothing and does not throw.
- **Non-fatal on missing entry:** manifest lists an entry that was already removed; cleanup completes without throwing.
- **`wroteOpencodeJson` reflected in manifest:** a zip with no `opencode.json` yields a manifest that does not list `opencode.json`; cleanup therefore keeps a planted (MCP/provider-merged) `opencode.json`.
- **Manifest self-deletion:** after `cleanupZipOwnedEntries`, the manifest file no longer exists.

The `Activator.stopServer()` ordering (AC 8, 11) and the job-guard call (AC 9) are integration-flavored — cover them by code review, or by an `integrationpde` test only if feasible.

## 7. Out of scope

- The tmpfs / in-memory (XDG env-override) approach (ticket's approach 1) — set aside by the user; also not natively cross-OS (no tmpfs/`/dev/shm` on Windows).
- Any secrecy / confidentiality mechanism — client-side secrecy is unachievable and is not pursued; no security claims in spec or code.
- Deleting runtime state: `opencode.db`, `sessions/`, auth — always kept.
- The install dir `{state}/opencode` teardown — unchanged; this case only adds config-dir cleanup.
- `SERVOY_SKILLS_ZIP` relocation / changing where the zip is fetched from.
- SESSION_AUTH_TOKEN rotation (SVY-21689).

## 8. Decisions (resolved)

1. **`opencode.json` is NOT cleaned — skills folders only.** DECIDED: cleanup targets **only the skill pack top-level directories** (the `cleanedDirs` set). `opencode.json` is **not** deleted at all, regardless of whether the zip or the MCP/provider merge wrote it — it holds no sensitive skill content and is cheap to leave. Therefore:
   - The manifest records **only the zip's top-level directory names** (the `cleanedDirs` set). It does **NOT** list `opencode.json`, and `writeManifest` is called with just `cleanedDirs` (drop the `wroteOpencodeJson`-adds-`opencode.json` step from §3.2 / §4.2).
   - `cleanupZipOwnedEntries` consequently never deletes `opencode.json` (it only deletes listed top-level dirs). Supersedes §3.2, §3.6, and AC 1–2/10's `opencode.json` wording: the manifest is "zip-shipped skill directories" only.
   - Net: the "zip-owned set" for this feature = **the zip's top-level skill directories**. `opencode.json`, `opencode.db`, `sessions/`, auth, and everything else are kept.
2. **Single shared helper for the config dir.** DECIDED: add one shared helper that returns `~/.servoy/opencode/` (e.g. `OpenCodeUtil.getServoyOpencodeConfigDir()` returning `Path.of(System.getProperty("user.home"), ".servoy", "opencode")`) and use it in all three sites — the job's happy-path resolution (`OpencodeFolderCreatorJob.java:210`), the job's licence-lost guard (§3.4), and the `Activator.stopServer()` hook (§3.3) — so the literal lives in exactly one place. Replace the inline `Path.of(...)` resolutions accordingly.

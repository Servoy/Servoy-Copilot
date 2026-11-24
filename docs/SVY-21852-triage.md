# Triage Report — SVY-21852

**Verdict:** PROCEED

## Reported problem

SVY-21852 (part 8 of 9 of the Servoy AI chat redesign, blocked by SVY-21845, blocks SVY-21853) wants the status panel to answer "can I work?" at a glance:

- One overall line in words: "Ready - Servoy AI is connected", or the first problem ("No model provider connected", "Could not reach Servoy AI. It may still be starting.").
- Below it: active solution, model in use, and agents working (spinner) or idle.
- MCP servers folded by default: "MCP servers · 12 of 13 online · 1 offline". Unfolded: name plus online/offline only.
- No skills, plugins, agent definitions or tool counts anywhere in the panel.
- Refresh keeps working. Header dot is green (ready), orange (an MCP server offline), red (cannot work).

The ticket's "Today" text (a "Status" button with a popover, and the header keeping only the panel button) is already outdated. Commit 47a74a5 (SVY-21845, on master, not pushed) removed the popover. The panel is now a docked/overlay panel in the shell, with the header toggle and dot already in place. The real work is only the panel content and the state logic.

## Root-cause assessment

This is a feature/UX change, not a defect. The code lives entirely in `webui/src/app`:

- `components/status-panel.component.{ts,html,scss}`:
  - Has `healthy`, `version`, `mcp`, `providers` signals, `mcpRows`, `mcpConnectedCount` and `mcpTotalCount` computeds, and `overall()` returning `'idle'|'ok'|'warn'|'error'`.
  - Fetches health, mcp and providers independently, with a 15 s poll and a `refresh()` method.
  - The template still shows the three sections Server / Providers / MCP, with every MCP server always listed, plus a Refresh button and a close button.
  - There is no text summary of the overall state. The only words are the section-local "Unhealthy" and "No provider is connected", and `loadError` renders "Could not reach Servoy AI. It may still be starting." as a section.
- `app.component.ts`:
  - Holds `statusOpen` (open by default from 1080px, closed below that), `toggleStatus()` (refreshes on open) and `closeStatus()`.
  - `statusDot = computed(() => statusPanel()?.overall() ?? 'idle')`. The panel stays mounted (`[hidden]`), so the dot is live while the panel is closed.
- `app.component.html` has the `.header-leading` and `.header-title` slots and the header dot classes ok/warn/error. The idle (grey) state is the default.
- `services/status.service.ts`:
  - `health()` uses `GET /info`. `mcp()` uses `GET /mcp`, whose response carries `location.directory` (the spec for `status.service.spec.ts` shows it), but only `data` is mapped.
  - `providers()` uses `GET /provider` and returns `{connected, default: {}}`. V2 gives no default model here, so the model is never known to the panel.

Data gaps for the new rows:

- **Solution:** nothing exposes it yet. The most direct source is `location.directory` from `/mcp` or `/info` (basename), or the active session's directory. The Java side has `OpenCodeUtil.getActiveProjectPath()`, but a new endpoint is not needed if the basename of the directory is acceptable. The ticket says "active solution", so this should be confirmed (the project directory name may differ from the solution name).
- **Model in use:** there is no source today. Candidates are the model on the latest user or assistant message of the active session (`{providerID, modelID}`, as seen in the session export shape), or the configured default model from `/config`. The first is simplest and needs no new endpoint, with a fallback of "-" or the first connected provider when there are no messages yet.
- **Agents working or idle:** `ChatStore.streaming` is already a computed over `streamingSessions`. Check whether it covers subagent child sessions, which the "agents" wording implies (sessions tree with nested children exists in the store).

## Ticket premise check

- "Move status from the popover into the side panel": already done by 47a74a5. What remains is only content. The ticket should be read as "rework the panel content" and does not need layout or shell changes.
- The proposed design is sound and simple. The existing `overall()` already encodes the red/orange/green rule the ticket asks for (unhealthy or load error or zero providers = error, MCP offline = warn, otherwise ok). It can be reused as the single source of truth for both the line and the dot. A new `summary` computed (state + text) should be derived next to it so the text and the dot cannot disagree.
- "First problem in words" needs a defined priority order, e.g. could not reach / unhealthy, then no model provider connected, then MCP offline. Note that when only MCP servers are offline the example in the ticket still reads "Ready - Servoy AI is connected" while the dot is orange, so the headline text stays "Ready" and the MCP count line carries the degradation. This is deliberate in the example and should be followed.
- Existing behaviour to preserve:
  - A provider list that has not arrived must not turn the dot red (the "goes green before the heavy provider list arrives" test).
  - Health loads first, and the dot is idle until the first health result.
- "Idle" is currently a grey dot with no wording. The line must say something during `idle` (e.g. "Checking Servoy AI…"). The ticket does not define this, so it is a small spec decision, not a blocker.
- The ticket's statement "No skills, plugins, agent definitions or tool counts": the current panel already shows none of them (only server, providers, MCP), so this is a regression guard (a spec assertion) rather than a removal.
- The version (`v…`) and the per-provider list are not in the ticket's panel contents. Recommend dropping them from the panel, or keeping the version only as muted small text. Confirm in the spec.
- MCP rows: show `online`/`offline` only. The current rows show the raw status (`connected`, `failed`, `needs_auth`, `disabled`) and an error tooltip. The ticket says "nothing more", so map `connected` to online and everything else to offline. Whether `disabled` counts as offline needs a decision (suggest: offline, same as today's `ok = status === 'connected'`).

## Approaches considered

1. **No code change needed.** Rejected. The panel has no overall wording, no solution/model/agent rows and always lists every MCP server, so none of the acceptance criteria are met.
2. **Rework `StatusPanelComponent` in place (recommended).**
   - Add a `summary` computed (state + message) on top of `overall()`.
   - Add Solution, Model and Agents rows, and a collapsed-by-default MCP block (a native `<details>` or a signal-driven toggle with `aria-expanded`).
   - Keep `refresh()`, the polling and the close button. Keep the panel body in a structure where SVY-21853 can add a "Usage · this month" block and a session/context block below the rows.
   - Small `StatusService` additions: expose the directory from the `/mcp` or `/info` response, and read the model from the store.
   - Smallest change, no shell change, and the header dot continues to work unchanged.
3. **Extract a shared `StatusStore` service (signals) holding health/mcp/providers/derived state, with the panel as a pure view.** Cleaner for SVY-21853 (usage, and a context ring in the composer that opens the panel and reads the same data) and for the dot (no `viewChild` reach into the panel). It is larger and touches `app.component`. It is reasonable to defer to SVY-21853, which actually needs to share data with the composer. Not recommended for this case.
4. **Add a new Java/BFF endpoint exposing the solution name, model and agent activity.** Rejected for now. Everything needed is available client-side (directory from the `location` block, model from messages, streaming from the store), and a Java change would require a separate bundle pipeline.

## Recommendation

PROCEED with approach 2, scoped strictly to SVY-21852.

Implementation notes for the spec:

- **State and text:**
  - `summary()` returns `{state, text}`.
  - `idle`: "Checking Servoy AI…".
  - `error`: priority order is load error / unhealthy ("Could not reach Servoy AI. It may still be starting."), then zero providers ("No model provider connected").
  - `warn` and `ok`: "Ready - Servoy AI is connected".
  - The dot and the line must derive from one computed, so they cannot diverge. The header dot should be covered for all four states.
- **Rows:**
  - Solution: basename of the active directory.
  - Model: last-used model id from the active session, with a defined fallback.
  - Agents: spinner when `store.streaming()`, else "Idle".
  - MCP block: collapsed by default, header "MCP servers · X of Y online · Z offline" (omit "· Z offline" when 0, and handle zero servers). When unfolded, list name + online/offline only. Offline entries sorted first as today.
- **Remove from the panel:** the Server/Providers sections and the version. Do not add any skills/plugins/agent definitions/tool counts, and add a spec assertion that these never render.
- **Refresh:** keep the button and `loading` state. `toggleStatus()` still triggers a refresh when opening.
- **Header dot (open points from SVY-21845):**
  - The existing app spec covers the dot classes only partly. Add tests that the dot classes follow `overall()` for idle (grey, no class), ok, warn and error, and that it stays live while the panel is closed (`[hidden]`).
  - The warn state is currently coloured with the accent token. The ticket says orange, so use `--svy-accent` for the dot and `--svy-acc-fg` for any orange text, to keep the ≥ 4.5:1 contrast rule from SVY-21845.
  - The idle state uses `--svy-idle`.
- **Room for SVY-21853:** keep the panel body as a vertical stack of independent blocks (summary, facts, MCP, and later usage and context). Avoid fixed heights. Do not add anything usage-related now. The context ring in the composer will want to open the panel through the shell's `statusOpen` signal, which already exists.
- **Tests (Vitest, zoneless):** extend `status-panel.component.spec.ts` (summary text per state, folded/unfolded MCP, counts, no forbidden terms, refresh) and `app.component.spec.ts` (dot states). Guard NG0600 with `expect(() => fixture.detectChanges()).not.toThrow()` after setting signals. Update the existing specs that assert the removed Server/Providers sections.

Decisions to record in the spec (small, with defaults proposed; none block starting):

1. Solution name = basename of the opencode directory.
2. Model = model of the latest message in the active session, with a "-" fallback.
3. `disabled`/`needs_auth` MCP servers count as offline.
4. Version and per-provider list dropped from the panel.

## Git history findings

- 47a74a5 "SVY-21845 Servoy AI: three-area layout, closable status panel and theme toggle [ai]" (local on master, not pushed):
  - Rewrote `status-panel.component.{html,scss,ts,spec.ts}` into a docked/overlay panel. The popover and toggle were removed and a `closed` output was added.
  - Added `statusOpen` to the shell, the header slots `.header-leading` and `.header-title`, the header status button with the dot, and the light/dark `--svy-*` palettes.
  - Left `overall()` and its logic unchanged. The SVY-21845 spec states "Dot meaning beyond that is SVY-21852" and that the panel body is "the existing Server / Providers / MCP content", so this ticket owns the redesign of that content.
- `docs/SVY-21845-three-area-layout-theme-toggle.spec.md` and `docs/SVY-21845-triage.md` are the prior specs. The dot colours are idle grey, ok green `#1F8A4C`, warn `--svy-accent`, error `--svy-danger`.
- Jira: SVY-21852 is blocked by SVY-21845 (In Review) and blocks SVY-21853 (usage/context, which builds on this panel). The target design is the attachment `ServoyAI Redesign.html` (id 78953) on SVY-21845. I did not download or inspect it here, so exact spacing, labels and the position of rows must be checked against it during the spec. The ticket text is the authority for behaviour.
- The ticket body contains a corrupted character (`�`) where "·" is meant (rendering of the separator in "MCP servers · 12 of 13 online · 1 offline"), and "?" where "€" is meant in SVY-21853. This is an encoding artefact of the Jira text and not a requirement.

## Questions for the reporter (NEEDS_INPUT only)

Not applicable. The verdict is PROCEED.

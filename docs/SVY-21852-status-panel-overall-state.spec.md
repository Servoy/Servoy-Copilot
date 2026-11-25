# Spec: SVY-21852 — Servoy AI: status panel with one overall state and MCP servers folded away

## 1. Goal

Rework the content of the docked status panel (`svy-status-panel`) so it answers "can I work?" at a glance:

- One overall line in words on top: "Ready - Servoy AI is connected", or the first problem ("Could not reach Servoy AI. It may still be starting.", "No model provider connected").
- Below it three facts: Solution (the real active Servoy solution name), Model and Agents (working with spinner / idle), where Model and Agents follow the session the user is currently looking at.
- MCP servers folded by default: "MCP servers · 12 of 13 online · 1 offline". Unfolded: name plus online / offline / disabled only.
- No skills, plugins, agent definitions or tool counts anywhere in the panel.
- Refresh keeps working. Header dot: green (ready), orange (an enabled MCP server offline), red (cannot work), grey (not yet known).

The text line and the header dot must derive from one computed so they can never disagree. Scope is the panel content and its state logic, plus one small read-only Java/BFF endpoint that exposes the real active solution name.

## 2. Background

### 2.1 Ticket

SVY-21852 is part 8 of 9 of the Servoy AI chat redesign (label `servoyai-redesign`). It is blocked by SVY-21845 and blocks SVY-21853 (usage and context blocks in the same panel). The ticket's "Today" text (a "Status" button with popover) is outdated: SVY-21845 already moved the status into a docked/overlay panel. What remains is the panel content. The ticket text is the authority for behaviour; the attachment `ServoyAI Redesign.html` (id 78953 on SVY-21845) is the visual reference.

Ticket example: all well except `servoy-debug`. Header dot orange; panel reads "Ready - Servoy AI is connected", Solution `crm_app`, Model `claude-sonnet-5-5`, Agents idle, folded "MCP servers · 12 of 13 online · 1 offline". Unfolding shows `servoy-debug - offline`. (The Jira text has a corrupted `�` where `·` is meant; the separator is the middle dot U+00B7.)

### 2.2 Current webui code (commit 47a74a5, SVY-21845, local on master, not pushed)

All under `bundles/com.servoy.eclipse.opencode/webui/src/app`:

- `components/status-panel.component.{ts,html,scss}`:
  - signals `healthy`, `version`, `mcp`, `providers`, `loading`, `loadError`, `initialized`; computeds `mcpRows`, `mcpConnectedCount`, `mcpTotalCount`, `connectedProviders`, `hasProviders`, `providerRows`, `overall()` (`'idle'|'ok'|'warn'|'error'`).
  - `refresh()` fetches health, mcp and providers independently (health first paints the dot; the heavy `/provider` catalogue must never block it). A 15 s poll calls `refresh()`.
  - Template: head (title "Status", Refresh button, close button) and three sections Server / Providers / MCP, every MCP server always listed with its raw status and a warning icon. No wording for the overall state.
- `app.component.ts`: `statusOpen` (open from 1080 px), `toggleStatus()` (refreshes when opening), `closeStatus()`, `statusDot = computed(() => statusPanel()?.overall() ?? 'idle')`. The panel stays mounted (`[hidden]`), so the dot is live while the panel is closed.
- `app.component.html` / `.scss`: `.status-dot` with classes `ok|warn|error` (idle = no class): idle `--svy-idle`, ok `--svy-ok`, warn `--svy-accent`, error `--svy-danger`.
- `services/status.service.ts`: `health()` (`GET /info`), `mcp()` (`GET /mcp`, maps `data[]` to `{name -> status}`), `providers()` (`GET /provider`).
- `services/chat-store.service.ts` (see 3.4 for the exact members used).
- Existing behaviour to preserve (tests exist): dot idle until the first health result; a provider list that has not arrived must not turn the dot red; health failure or unhealthy = error; loaded empty provider list = error.
- Tokens in `styles.scss`: `--svy-ok`, `--svy-accent` (#f5a623), `--svy-acc-fg` (#9a5b00 light / #f5b94a dark, the AA-safe orange for text), `--svy-danger`, `--svy-idle`, `--svy-muted`, `--svy-line2`.

### 2.3 Current Java code (`bundles/com.servoy.eclipse.opencode`)

- `tomcat/OpencodeChatServlet` is the BFF mounted at `/servoy_ai/*`. `service()` routes: `isApiRequest(pathInfo)` (`/rest_api/**`) is proxied to opencode (readiness gate, directory injection from `projectPathSupplier`, 409 `{"error":"no active solution"}` when there is no directory); everything else is `serveStatic` (SPA fallback to `index.html`). It is built with suppliers (`portSupplier`, `serverPasswordSupplier`, `serverReadySupplier`, `waitForServer`, `projectPathSupplier`, `resourceResolver`) so it never reaches bundle state directly.
- `tomcat/ServicesProvider` constructs it with `OpenCodeUtil::getActiveProjectPath` for the project path supplier.
- `OpenCodeUtil.getActiveProjectPath()`: `ServoyModelFinder.getServoyModel().getActiveProject()` (a `ServoyProject`) -> `getProject()` (`IProject`) -> `getLocationURI()`, then walks up to the git root (`findGitRoot`). It returns a PATH, not a name. Because it walks up to the git root, the directory (and therefore its basename) can be a parent repository folder, not the solution; the basename of `location.directory` is therefore NOT a reliable solution name. That is why the webui must not derive it.
- `OpenCodeView` knows the active project only to open the page (listener `IActiveProjectListener` for the no-solution case); nothing there is reusable for a name.
- In Servoy the Eclipse project of a solution is named after the solution (the model keys `ServoyProject`s by project name), so `activeProject.getProject().getName()` is the real solution name and needs no new imports (`getSolution().getName()` gives the same value but may be `null` before the solution is loaded and would require importing `com.servoy.j2db.persistence`).
- Tests: `tests/com.servoy.eclipse.opencode.tests/.../plainjunit/tomcat/` has `OpencodeChatServletTest` (route classification and pure helpers), `OpencodeChatServletGuardTest` (dynamic-proxy request/response fakes, no OSGi, no socket) and `ServicesProviderTest`.

### 2.4 What the live opencode API carries for the model (verified)

Sources: the pinned `@opencode/cli` 2.2.0 protocol sources (`packages/schema/src/model.ts`, `session-message.ts`, `packages/protocol/src/groups/message.ts`, `session.ts`), existing webui code (`mapV2Message` spreads the raw message into `MessageWithParts.info`, so every raw field is available as `info.<field>`; `session-export.spec.ts` shows `model: { id, providerID }`), and a read-only `GET` against the running BFF (`/servoy_ai/rest_api/session`, `/session/:id/message?limit=40&order=desc`, `/mcp`; no POST, nothing mutated).

- Common type `Model.Ref` = `{ id: string, providerID: string, variant?: string }`. It is provider-agnostic: a `kiro` model and a `GENAI_API_KEY`/openai-compatible provider model use the same shape; only `providerID` and the `id` string differ. The Model row shows `id` only.
- Messages, field `model`:
  - `type: 'assistant'`: `model: Model.Ref` (live: `{"id":"claude-sonnet-5-5","providerID":"kiro","variant":"medium"}`; subagent session: `claude-haiku-5-5`, variant `default`). Also carries `agent` (e.g. `Researcher`).
  - `type: 'model-switched'` (`Session.Message.ModelSelected`, emitted by `POST /session/:id/model`): `model: Model.Ref` and optional `previous: Model.Ref`.
  - `type: 'user'`: NO model (live keys: `id,time,text,type[,files]`). `type: 'idle'`: NO model (`id,time,type,outcome`).
- Session (`GET /session`, `GET /session/:id`): `model` is optional. Live: present on the subagent child session (`{id:'claude-haiku-5-5',providerID:'kiro',variant:'default'}`, plus `agent`), absent on the parent session. So it cannot be the primary source.
- Agent model config is per agent in `~/.servoy/opencode/opencode.json` (`agent.<name>.model`, e.g. `kiro/claude-sonnet-5-5`); the default `model` is `kiro/auto`. The effective model is what the assistant message reports, not the config.
- Not verified live: a GENAI/openai-compatible provider session (only `kiro` sessions exist on the reachable instance). The schema is provider-agnostic, so the same field paths apply; see open question 1.

### 2.5 Git history carried from triage

- 47a74a5 "SVY-21845 Servoy AI: three-area layout, closable status panel and theme toggle [ai]" (local on master, not pushed): rewrote the panel into the docked/overlay panel, added `statusOpen`, header slots, the status button with dot and the light/dark palettes. It left `overall()` unchanged. The SVY-21845 spec says dot meaning beyond that is SVY-21852 and that the panel body is "the existing Server / Providers / MCP content", so this case owns the content redesign. Nothing is pushed.
- Open points inherited from SVY-21845: header-dot tests only cover ok and error (no idle, no warn); the warn colour must follow the orange rule with AA contrast for text (`--svy-acc-fg`).

### 2.6 Target design (`ServoyAI Redesign.html`, status panel part)

- Panel head: 9 px dot (green with a 3 px translucent ring when ready), then a two-line block: bold 14 px "Ready" over a 12 px muted "Servoy AI is connected"; close button right (36 px, 16 px X icon, `aria-label="Close status"`). Padding `14px 12px 14px 16px`, bottom border `--line2`.
- Core facts block (padding 16, gap 10, bottom border): rows of 13 px text, 8 px dot + label ("Solution", "Model", "Agents"), value right-aligned in monospace 12 px muted (`crm_app`, `claude-sonnet-5-5`, `idle` / `working`). Agents dot orange (`#E39A1C`) with a running indicator when working, `--idle` when idle.
- Then (SVY-21853, not built here) a "Usage · this month" block with session/context tiles.
- MCP block last (container padding 8): a full-width button row, min-height 44, 12 px muted caret, "MCP servers" 13 px weight 600, "12 of 13 online" 12 px muted, "1 offline" right-aligned 12 px in `--acc-fg`, with `aria-expanded`. Unfolded: list with padding `0 6px 8px 32px`, rows min-height 32, bottom border `--soft`, 7 px dot, monospace 12 px name, right-aligned muted 12 px state.
- The design has no Refresh button and no version. The existing Refresh button is kept for now (decision 5).

## 3. Design

### 3.1 Decisions

1. Solution = the real active solution name, exposed by a new read-only BFF endpoint (3.5). No directory basename.
2. Model = model id from the viewed session's latest message that carries a model (3.4), `-` when none.
3. MCP has three states: connected = online, `disabled` = disabled (does not count as offline, never turns the dot orange), anything else = offline (3.3).
4. The version and the per-provider list are dropped from the panel.
5. The Refresh button is kept.
6. Agents, Model and anything else session-specific follow the session the user is currently looking at, including subagent child sessions (3.4).

### 3.2 Summary state and text (single source of truth)

Add to `StatusPanelComponent`:

```ts
readonly summary = computed<{ state: 'idle' | 'ok' | 'warn' | 'error'; text: string; headline: string; detail: string }>(...)
readonly overall = computed(() => this.summary().state)
```

`overall()` keeps its name and contract (the shell and existing specs use it) but is derived from `summary()`. The logic of the current `overall()` moves into `summary()`; only text and the disabled rule are added. Priority, first match wins:

| # | Condition | state | text |
|---|-----------|-------|------|
| 1 | `!initialized()` | idle | `Checking Servoy AI…` |
| 2 | `loadError()` or `healthy() !== true` | error | `Could not reach Servoy AI. It may still be starting.` |
| 3 | providers loaded and zero connected | error | `No model provider connected` |
| 4 | at least one enabled (non-disabled) MCP server offline | warn | `Ready - Servoy AI is connected` |
| 5 | otherwise (including servers that are only disabled) | ok | `Ready - Servoy AI is connected` |

Notes:
- Row 2 covers unreachable and unhealthy (an unhealthy `/info` has no `version`); one message.
- Row 4 deliberately keeps the headline "Ready" (ticket example: orange dot, "Ready" text). The MCP header carries the degradation.
- Provider list not yet arrived (`providers() === null`) skips row 3 (no red flash on startup, existing behaviour).
- Rendering: for rows 4 and 5 `headline` = `Ready`, `detail` = `Servoy AI is connected` (two lines like the design); for rows 1 to 3 `headline` is the full text and `detail` is empty. A visually hidden ` - ` between the spans keeps `textContent` equal to `text`.
- The head dot uses the state class (`ok|warn|error`, none for idle).
- The summary sits in a `role="status"` region so changes are announced.

### 3.3 MCP block: three states

Statuses come from `GET /mcp` (`McpConnectionStatus`: `connected`, `disabled`, `failed`, `pending`, `needs_auth`, `needs_client_registration`, other). Mapping in `mcpRows`:

| `status` | state | dot | label |
|----------|-------|-----|-------|
| `connected` | online | `--svy-ok` | `online` |
| `disabled` | disabled | `--svy-idle` (grey) | `disabled` |
| anything else | offline | `--svy-danger` | `offline` |

Counts (computeds): `mcpOnline`, `mcpOffline`, `mcpDisabled`, `mcpEnabled = online + offline` (Y).

Header text (folded and unfolded), parts joined by ` · `:

- `MCP servers`
- `{{online}} of {{enabled}} online` (always, so zero servers renders `0 of 0 online` with no special handling)
- `{{offline}} offline`, only when `offline > 0`, right-aligned in `--svy-acc-fg`
- `{{disabled}} disabled`, only when `disabled > 0`, muted (`--svy-muted`)

Examples: 13 servers, 1 failed: `MCP servers · 12 of 13 online · 1 offline`. 13 servers, 1 failed, 2 disabled: `MCP servers · 10 of 11 online · 1 offline · 2 disabled`. 2 disabled, rest online: `MCP servers · 11 of 11 online · 2 disabled` (dot green).

Folding:
- `mcpOpen = signal(false)`; `toggleMcp()` flips it. A native `<button type="button">` with `aria-expanded` bound to `mcpOpen()` and `aria-controls="status-mcp-list"`; the list renders only when open. Fold state survives `refresh()` and the poll (plain signal, never touched by refresh), is not persisted.
- Unfolded rows: dot, name (monospace), state label (`online` / `offline` / `disabled`). Nothing else: no raw status, no error text, no tooltip, no warning icon. Order: offline first, then online, then disabled; alphabetical inside each group.
- `McpRow` becomes `{ name, state: 'online' | 'offline' | 'disabled' }`; `status` and `error` are not rendered.
- The caret is a CSS-rotated SVG chevron, `aria-hidden`.

Header dot and summary: orange only when `mcpOffline > 0` (a disabled server never degrades the state).

### 3.4 Facts rows and the viewed session

Rows in `.facts`, in this order, each: dot, label, value (monospace, muted, right-aligned, ellipsis with `title`):

- **Solution**: `solutionName()` or `-` (3.5). Global, not session-specific. Dot `--svy-ok` when known, `--svy-idle` when `-`.
- **Model**: `currentModel()` or `-`. Follows the viewed session. Dot `--svy-ok` when known, else `--svy-idle`.
- **Agents**: spinner and `working` when `store.streaming()`, else `idle`. Follows the viewed session. Dot `--svy-accent` when working, `--svy-idle` when idle. Spinner is a CSS-only 12 px ring, `aria-hidden`, honouring `prefers-reduced-motion`.

What "the session the user is looking at" is in `ChatStore` (verified in `chat-store.service.ts`):

- `activeSessionId` is the selected session. Selecting a session in the sidebar tree, including a nested subagent child (`openSession(id)`), sets it; `activeIsSubagent` is true for a child. `messages` holds the messages of that session only and is reset on every switch.
- `streamingSessions` is a per-session set; entries are added/removed for ANY session from the event bus ("mark that session streaming regardless of whether it is the active one"), so a child session's own turn is tracked under the child's id.
- `streaming = computed(() => id !== null && streamingSessions().has(id))` with `id = activeSessionId()` is therefore exactly "the viewed session has a turn running", for parents and for subagent children alike. It is NOT "any session is running": another session's activity never shows. When the viewer is on a parent session while its subagent runs, the parent's own turn is also streaming (it waits on the subagent tool), so Agents correctly shows `working`.

Decision: Agents = `store.streaming()` unchanged (no store change). Model = computed over `store.messages()` only:

```
currentModel: scan store.messages() from newest to oldest;
  return the first message whose info.model.id is a non-empty string
  (assistant messages and 'model-switched' messages both carry model: { id, providerID, variant });
  user and idle messages have none and are skipped;
  none found, or no active session: null (rendered as '-').
```

Lookup order and fallback are therefore: (1) latest message in the viewed session with `info.model.id` (what really ran, always wins), (2) for a non-subagent session without such a message, the model of the default agent (what the next turn will use, see 3.4a), (3) `-`. The session object's own `model` is deliberately not used: it exists only on child sessions (which always have assistant messages with the same model), and `ChatStore` does not expose the active session object (`allSessions` is private), so using it would need a store change for no gain. Only `id` is shown (no provider, no variant).

#### 3.4a Model before the first message (review feedback, Rene van Veen)

Before the first turn a session has no message, so the row showed `-` with a grey dot although the model is known. The model a new turn uses is that of the DEFAULT AGENT, not the global `model` in the config: in this project `model` is `kiro/auto` but `default_agent` is `Orchestrator`, whose own model is `kiro/claude-sonnet-5-5#medium`. A fallback on the global model would show the wrong model.

Verified against opencode 2.4.1 (tag `v2.4.1`) and the running server:
- Session runner (`packages/core/src/session/runner/model.ts`, `select`): session model, else `agents.select(session.agent).info.model` when that model is available, else the resolver's global fallback. A prompt without an agent runs the default agent, so its model wins over the global one.
- `GET /agent` (`packages/protocol/src/groups/agent.ts`, `agent.list`) returns `Agent.list`, which (`packages/core/src/agent.ts`, `list`) puts the selected default first: the configured `default_agent` when it is a selectable primary and not hidden, else `build`, else the first selectable agent. Live: `data[0]` is `Orchestrator`, `mode: primary`, `model: { id: 'claude-sonnet-5-5', providerID: 'kiro', variant: 'medium' }`; the subagents follow with their own models (Researcher: `claude-haiku-5-5`).
- Not usable: `GET /config` returns the raw documents (global `model` is `kiro/auto`, a different model than the one used); `GET /model/default` returns the global default (`auto`); `GET /provider` carries no default model; there is no `GET /agent/default`.

Design: `StatusService.defaultAgentModel(): Observable<{ agent, model } | null>` reads `GET rest_api/agent` and takes `data[0]`. It returns `null` (row shows `-` with a grey dot, never a guess) when the list is empty, the first entry is a subagent or hidden, or it has no complete `model` (`id` and `providerID` non-empty strings): an agent without its own model inherits the global default, which this endpoint does not reveal. An HTTP error propagates; the panel swallows it and keeps `-`.

Panel: `defaultModel` signal, set from that call in `refresh()`. `GET /agent` is large (every agent's system prompt, about 280 KB here) and its answer rarely changes, so the 15 s background poll only asks for it while the default model is still unknown; the first load, opening the panel and the Refresh button always re-read it (a changed `default_agent` shows up on the next manual refresh). `currentModel` = message model, else (not a subagent session) `defaultModel`, else `null`. A subagent session without a message yet stays `-` because its own agent's model is not known to the panel. The usage store keeps reading the message model only, so the SVY-21853 context numbers are unchanged.

The Model dot is green whenever a model is shown, grey for `-`. Because `messages` resets on switch and streamed assistant messages update it, the row follows both session switches and a model change in the viewed session.

Everything else in the panel is global and intentionally does not depend on the viewed session: summary, Solution, MCP servers, health and providers. SVY-21853's usage/context blocks ("This session", "Context") WILL depend on the viewed session; they must read `activeSessionId`/`messages` the same way (note only; nothing built here).

### 3.5 Solution name: Java/BFF endpoint

Smallest clean option: a BFF-owned, read-only endpoint on the existing servlet, NOT proxied to opencode.

- Route: `GET /servoy_ai/solution` (servlet `pathInfo == "/solution"`). It sits beside `/rest_api/**`, not under it, so it is clearly BFF-owned and never forwarded upstream. `service()` checks it BEFORE the static/SPA fallback (otherwise it would serve `index.html`).
- Response (always `200`, `Content-Type: application/json;charset=UTF-8`, `Cache-Control: no-store`): `{"name":"crm_app"}`, or `{"name":null}` when no solution is active. 200 with a null name (not the 409 used for the proxy) so a normal, expected "no solution" state does not look like an error to the poll. Only `GET`/`HEAD` allowed, any other method `405`. No readiness gate and no opencode port needed (it does not talk to opencode), so it works even while opencode is starting.
- Source of the name: new `OpenCodeUtil.getActiveSolutionName()` using the same path as `getActiveProjectPath()`: `ServoyModelFinder.getServoyModel()` -> `getActiveProject()` -> `getProject().getName()`, returning `null` when there is no model, no active project or no Eclipse project. The project name is the solution name in Servoy and needs no new bundle imports. It must NOT use the git-root directory or its basename (can be a parent repo folder). The pure name-normalising step is a public static `OpenCodeUtil.normalizeSolutionName(String projectName)` (trim; blank to `null`) so it is unit testable without a model.
- Java changes:
  - `OpenCodeUtil`: add `getActiveSolutionName()` and `normalizeSolutionName(String)`.
  - `tomcat/OpencodeChatServlet`: add field `Supplier<String> solutionNameSupplier`; add `public static boolean isSolutionRequest(String pathInfo)` (equals `/solution`, `null` safe); add `SOLUTION_PATH = "/solution"`; in `service()` route `isSolutionRequest` first to a new private `handleSolution(req, resp)`; reuse `jsonEscape` for the name; a `RuntimeException` from the supplier is logged with `ServoyLog.logError` and answered as `{"name":null}`. Add a new 7-argument constructor with the supplier; keep the existing 6-argument constructor delegating with `() -> null` so existing tests and callers compile unchanged.
  - `tomcat/ServicesProvider`: pass `OpenCodeUtil::getActiveSolutionName` to the new constructor.
  - No change to `Activator`, `OpenCodeView`, `McpConfigWriter` or any other class.
- Caching and refresh: the server holds no cache; every request reads the live active project, so it follows solution changes immediately. The webui fetches it on every `refresh()` (initial load, the 15 s poll, Refresh button, opening the panel), so a changed active solution shows within one poll. Note the opencode server is directory-scoped to the project it was started for; a solution switch that restarts opencode is out of scope here, the endpoint just reports the current active solution.
- `StatusService.solution(): Observable<string | null>`: `GET solution` (relative, resolves to `/servoy_ai/solution` against the app base href, like `rest_api/...`), maps `r?.name ?? null`, `catchError(() => of(null))` so a failure shows `-` and never turns the dot red (the name is informational, not a health axis).
- Panel: `solutionName = signal<string | null>(null)`, set in `refresh()` by a fourth independent subscription (errors already mapped to `null`). It does not participate in `summary()` and does not touch `loading`.

### 3.6 Data sources summary

- Health, MCP, providers: unchanged endpoints and mapping (`mcp()` unchanged).
- Solution: `GET /servoy_ai/solution` (3.5).
- Model and Agents: `ChatStore.messages()` and `ChatStore.streaming()` of the viewed session (3.4).
- Removed from the component: `version` (not shown), `providerRows`, `ProviderRow`; unused `forkJoin` import. `hasProviders`/`providers` stay (used by `summary()`).

### 3.7 Refresh

Keep the Refresh button and its `loading` state in the head (label "Refresh" / "Refreshing…", disabled while loading), `refresh()`, the 15 s poll, and `toggleStatus()` refreshing on open. `refresh()` keeps independent subscriptions: health, mcp, providers and now solution. On mcp error: `mcp.set({})`.

### 3.8 Header dot

`statusDot` stays `computed(() => statusPanel()?.overall() ?? 'idle')`; no change in `app.component.{ts,html}`.

- Classes unchanged: none (idle, `--svy-idle`), `ok` (`--svy-ok`), `warn`, `error` (`--svy-danger`).
- Colour rule: the orange dot uses `--svy-accent` (graphical object); any orange text (the "N offline" count) uses `--svy-acc-fg` (≥ 4.5:1 on `--svy-surface` in both themes). No text uses `--svy-accent`. `.status-dot` scss already complies and is unchanged.
- The dot is live while the panel is closed because the panel stays mounted (`[hidden]`); tested.

### 3.9 Room for SVY-21853

The panel body is a vertical stack of independent blocks, each its own element with its own padding and bottom border, no fixed heights, in this order:

1. `.summary` (head: dot, summary line, Refresh, close)
2. `.facts` (Solution, Model, Agents)
3. placeholder position, nothing rendered: future "Usage · this month" and session/context blocks, as in the design
4. `.mcp` (fold block, last)

SVY-21853 inserts its blocks between `.facts` and `.mcp` without touching the others. No usage, token, cost or context markup, signals or styles are added now. The shell's `statusOpen` signal already exists for the future composer context ring.

### 3.10 Accessibility

- MCP header: native `<button>`, `aria-expanded`, `aria-controls`, keyboard operable (Enter/Space), visible focus ring using the app's existing `:focus-visible` outline token.
- Summary line in `role="status"`; the dot is decorative (`aria-hidden="true"`); colour is never the only signal.
- MCP row states: the text label (`online` / `offline` / `disabled`) carries the meaning; the grey dot for disabled is not the only signal, and green/red are not either. The header's `· N disabled` part is plain text.
- Spinner `aria-hidden` with the adjacent text `working`; reduced-motion respected.
- Text colours: values and the disabled count in `--svy-muted`; the offline count in `--svy-acc-fg`; error text in `--svy-danger`. The disabled label uses `--svy-muted` (not `--svy-idle`, which is for non-text dots and is below text contrast).
- Close button keeps `aria-label="Close status"`; Refresh keeps its visible text label.

## 4. Implementation plan

Webui conventions (paths under `bundles/com.servoy.eclipse.opencode/webui/src/app`): standalone, OnPush, signals, `readonly`, `inject()`, single quotes, no new comments, NG0600 safe. Java conventions: tabs, license header, `//$NON-NLS-1$`, no new bundle imports; use the Eclipse MCP tools (`eclipse-ide_*`/`eclipse-coder_*`), not shell, for the Java bundle.

Java (do first so the webui can call it):

1. `OpenCodeUtil.java`: add `getActiveSolutionName()` and `normalizeSolutionName(String)` (3.5).
2. `OpencodeChatServlet.java`: add `SOLUTION_PATH`, `isSolutionRequest`, the `solutionNameSupplier` field, the 7-argument constructor (keep the 6-argument one delegating), the `service()` route (before `isApiRequest`) and `handleSolution` (3.5).
3. `ServicesProvider.java`: pass `OpenCodeUtil::getActiveSolutionName`.
4. Compile check: call `eclipse-ide_getCompilationErrors` (projects `com.servoy.eclipse.opencode` and `com.servoy.eclipse.opencode.tests`) and apply quick fixes via `eclipse-ide_executeQuickFix` until zero errors, per AGENTS.md; also fix any Spotbugs high-severity findings in the new code.
5. Java tests (plain JUnit, no `integrationpde`, per the AGENTS.md test strategy: the new logic is a route classifier, a supplier seam and a pure helper, all reachable without a workbench): see section 5. Run with `eclipse-ide_runJUnitTests`, project `com.servoy.eclipse.opencode.tests`, package `com.servoy.eclipse.opencode.tests.plainjunit.tomcat` (and `OpenCodeUtil` test class), poll with `getOperationStatus`.

Webui:

6. `services/status.service.ts`: add `solution()` (3.5).
7. `components/status-panel.component.ts`: inject `ChatStore`; add `solutionName`, `currentModel`, `mcpOpen`, `toggleMcp()`, `mcpOnline/mcpOffline/mcpDisabled/mcpEnabled`; rewrite `mcpRows` to the three states; add `summary`, redefine `overall`; fourth subscription in `refresh()`; drop `version`, `providerRows`, `ProviderRow`, `forkJoin`; update the stale class doc.
8. `components/status-panel.component.html`: head (dot + two-line summary in `role="status"`, Refresh, close), `.facts`, `.mcp` (toggle + conditional list). Remove Server/Providers sections, version, per-provider list, raw MCP status and error tooltip.
9. `components/status-panel.component.scss`: remove `.section`, `.section-title`, `.count`, `.chips`, `.chip`, `.row-error`; add `.summary`, `.facts`, `.fact`, `.fact-value`, `.spinner` (reduced-motion safe), `.mcp`, `.mcp-toggle`, `.mcp-list`, `.mcp-row`, `.sr-only`, caret rotation; sizes from 2.6, `--svy-*` tokens only.
10. `app.component.{ts,html,scss}`: no change.
11. Specs:
    - `services/status.service.spec.ts`: add `solution()` tests (name returned, `{name:null}` and missing name give `null`, HTTP error gives `null`).
    - `components/status-panel.component.spec.ts`: provide a mock `ChatStore` (`messages`, `streaming` signals); every `flushAll` now also flushes `httpMock.expectOne('solution')` (otherwise `httpMock.verify()` fails). Remove/replace assertions on removed API: the `version` expectation in the `refresh()` test, the `providerRows` test, `connectedProviders` template use, and the `.panel .head .title` check (keep or drop the title consistently with the template). Keep all `overall()` tests and the "goes green before providers arrive" test. Add the tests in section 5.
    - `app.component.spec.ts`: add `solution: vi.fn(() => of('crm_app'))` to the `status` mock (and its type); the call-count assertions for health/mcp/providers stay and a `solution` one is added. Add the header-dot tests (idle, ok, warn, error, live while closed).
12. Verify from `webui/`: `npx ng test --include="**/status-panel.component.spec.ts" --no-watch`, then `app.component.spec.ts`, `status.service.spec.ts`, then full `npx ng test --no-watch` and `npx ng build`. Do not leave a dev bundle in `webui-dist` (production build before any commit). Commits: the Java and webui changes carry SVY-21852 in the subject and end with ` [ai]`; show the staged files and message before committing (AGENTS.md pre-commit checklist); push nothing.

## 5. Acceptance criteria

Panel summary
- [ ] Before the first health result the line reads `Checking Servoy AI…` and the state is idle (no ok/warn/error class on the head dot).
- [ ] Healthy, a provider connected, all enabled MCP online: the line's text content is exactly `Ready - Servoy AI is connected`, state ok.
- [ ] `GET /info` fails or returns no `version`: line reads `Could not reach Servoy AI. It may still be starting.`, state error.
- [ ] Healthy with a loaded empty provider list: line reads `No model provider connected`, state error.
- [ ] Unreachable and no provider at once: the "Could not reach" message wins.
- [ ] An enabled MCP server offline with everything else healthy: line still reads `Ready - Servoy AI is connected`, state warn.
- [ ] Only disabled MCP servers (no offline ones): state ok, not warn.
- [ ] Provider list not yet arrived and a healthy server: state ok (no red flash); once an empty list arrives it becomes error.
- [ ] `summary().state` always equals `overall()` and the head dot class matches it for idle, ok, warn, error.

Facts (viewed session)
- [ ] Solution shows the name returned by `GET /servoy_ai/solution`; `-` when `null`, when the call fails, or before it returns. Changing the returned name is reflected on the next `refresh()`.
- [ ] Model shows `info.model.id` of the newest message in `store.messages()` that has one (assistant and `model-switched` shapes); user/idle messages without `model` are skipped; `-` when none or no active session. It updates when a newer message with another model arrives and when `messages` is replaced by another session's messages.
- [ ] Agents shows a spinner and `working` while `store.streaming()` is true and `idle` (no spinner) otherwise; when the viewed session changes (including to a subagent child session) the row reflects the new session's streaming state only.
- [ ] Rows appear in the order Solution, Model, Agents, directly below the summary.

MCP block
- [ ] Folded by default: no server names in the DOM; the toggle has `aria-expanded="false"`.
- [ ] Header reads `MCP servers · 12 of 13 online · 1 offline` for 13 servers with 1 failed; `· N offline` is absent when 0 offline.
- [ ] `failed`, `needs_auth`, `needs_client_registration` and `pending` count as offline; `disabled` counts as disabled and is excluded from Y.
- [ ] With 2 disabled servers the header shows `· 2 disabled` (muted) after the online/offline parts, and is absent when 0 disabled; 13 servers with 2 disabled and all enabled online reads `MCP servers · 11 of 11 online · 2 disabled`.
- [ ] Clicking the toggle (and Enter/Space) unfolds: `aria-expanded="true"`, one row per server with name and `online` / `offline` / `disabled` only, order offline, online, disabled, alphabetical inside each group; clicking again folds.
- [ ] Unfolded rows contain no raw status text (`connected`, `failed`, ...), no error message and no warning icon.
- [ ] Fold state survives `refresh()` and the poll.
- [ ] Zero servers renders `MCP servers · 0 of 0 online` with the normal toggle (no special wording or handling).

Content guard
- [ ] The rendered panel text never contains `skill`, `plugin`, `agent definition`, tool counts, the version string, or `Providers` / `Server` section titles (negative regex on `textContent` for a populated fixture).

Refresh and shell
- [ ] The Refresh button re-fetches `/info`, `/mcp`, `/provider` and `solution` once each, shows `Refreshing…` and is disabled while loading.
- [ ] Opening the panel from the header still refreshes it; the 15 s poll still runs.
- [ ] The close button still emits `closed` and returns focus to the header status button.

Header dot (open points from SVY-21845)
- [ ] `app.component` spec: no ok/warn/error class when idle (health not yet resolved), `ok` when ready, `warn` with an offline enabled MCP server, `error` when unhealthy or without providers.
- [ ] The dot reflects each state while the panel is closed (`[hidden]`), including a state change after closing.
- [ ] Orange: the warn dot background is `--svy-accent`; the orange "N offline" text uses `--svy-acc-fg`; no text rule uses `--svy-accent` (checked in the scss).

Java (plain JUnit, `com.servoy.eclipse.opencode.tests.plainjunit.tomcat`, no OSGi)
- [ ] `OpencodeChatServletTest`: `isSolutionRequest("/solution")` is true; `null`, `""`, `/`, `/rest_api/solution`, `/solution/x`, `/Solution` are false; `/solution` is not classified as an API request.
- [ ] `OpencodeChatServletGuardTest` (new nested `SolutionEndpoint`, same dynamic-proxy fakes): with a supplier returning `crm_app` a `GET /solution` answers 200, `application/json`, body `{"name":"crm_app"}`; supplier returning `null` gives `{"name":null}`; a name with `"` and `\` is JSON-escaped; a throwing supplier gives 200 `{"name":null}`; `POST /solution` gives 405; the request is answered even when `serverReadySupplier` is false and `portSupplier` returns `-1` (no readiness gate, no upstream call, `waitForServer` never invoked); the 409 no-active-solution guard of `/rest_api/**` is unchanged.
- [ ] The 6-argument constructor still compiles and behaves as before (existing tests untouched); a `/solution` request on it answers `{"name":null}`.
- [ ] `OpenCodeUtilTest` (plainjunit): `normalizeSolutionName` trims, returns `null` for `null`/blank, keeps a normal name. (`getActiveSolutionName()` itself reads the static Servoy model and is only exercised for the null/no-model path via the OSGi-less run; the model walk stays covered by the manual check.)
- [ ] `ServicesProviderTest` still passes (one `ServletInstance` mapped to `/servoy_ai/*`).
- [ ] `eclipse-ide_getCompilationErrors` reports zero errors for both Java projects.

Quality
- [ ] `expect(() => fixture.detectChanges()).not.toThrow()` after setting signals in the new specs (NG0600).
- [ ] No usage, token, cost or context markup or signals added (SVY-21853 untouched).
- [ ] `ng test` (full) and `ng build` pass.
- [ ] Manual check in a running Servoy Developer: `GET http://127.0.0.1:<port>/servoy_ai/solution` returns the active solution's name; activating another solution and refreshing changes the Solution row.

## 6. Out of scope

- Usage, tokens, spend and context blocks and the composer context ring (SVY-21853).
- Any shell, header, layout or theme change (SVY-21845 is done); `app.component.{ts,html,scss}` are not modified, only its spec gains tests.
- Java/BFF is in scope for this ONE endpoint only (`GET /servoy_ai/solution` plus `OpenCodeUtil` name helpers and the constructor/wiring). No change to the opencode proxy, readiness gate, directory injection, `Activator`, `OpenCodeView` or any other Java class, and no new proxied route.
- A shared `StatusStore` extraction (triage approach 3).
- Restarting or re-scoping the opencode server when the active solution changes.
- Per-provider details, default-model catalogue, version display.
- Showing MCP error details or any action on a failed or disabled server (reconnect, enable, login).
- Persisting the MCP fold state.
- Using the session object's own `model` (needs a `ChatStore` change for no gain, see 3.4).
- Pushing: nothing is pushed; work builds on local commit 47a74a5.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Verify a live GENAI_API_KEY (openai-compatible) session: does the assistant message carry the same `model: { id, providerID, variant? }` (expected, the schema `Model.Ref` is provider-agnostic) and is `id` a readable name? Only `kiro` sessions exist on the instance checked. | Developer | Open; check once during implementation, no design change expected |
| Do streamed assistant messages (the live `message.*`/session events handled by `ChatStore`, as opposed to the fetched history verified above) keep `model` on `info` after being merged into `messages`? History pages are verified; the live merge path was not inspected. If not, Model shows `-` until reload. | Developer | Open; verify against a live streaming turn, fix in `ChatStore` merge if it drops the field |
| Review feedback 2: should Model show from the start? Yes: the default agent's model before the first message, the real message model afterwards (3.4a). | Rene van Veen / Developer | Resolved |
| Review feedback 1: the MCP chevron was as small as the old sidebar one. It now uses the shared `svy-chevron` (16 px stroked glyph in a 24 px target, `--svy-fg2`, rotates when open), the same glyph as the session list twisty. The session list keeps its own markup (owned by SVY-21846); switching it to `svy-chevron` is a follow-up. | Rene van Veen / Developer | Resolved here; follow-up for the sidebar |
| A changed `default_agent` while the panel stays open is only picked up on the next manual refresh or panel open (the poll skips the large `GET /agent` once a model is known). Acceptable? | Product | Assumed yes |
| Idle wording "Checking Servoy AI…" is not defined by the ticket. | Product | Assumed as written |
| Two-line headline/detail from the design versus the one-line ticket text. Spec renders two lines but keeps `textContent` equal to the ticket string. | Product / design | Assumed acceptable |

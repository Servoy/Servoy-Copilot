# Triage Report — SVY-21374

**Verdict:** PROCEED

## Reported problem

The reporter's workflow for diagnosing AI-assisted flows (finding where tool/MCP calls went
wrong, building test flows) relies on inspecting a full session transcript: prompts,
responses, tool calls, MCP calls (with args/output and response time), reasoning, subagent
calls, and durations.

- **With other (non-Kiro) clients**, they can just run OpenCode's `/export` in the terminal,
  which produces a full JSON/MD dump of the session including all of the above.
- **In the Servoy Copilot embedded OpenCode view**, there is no terminal and no export
  button — the UI is an embedded browser hosting `opencode serve`'s web frontend
  (`com.servoy.eclipse.opencode.OpenCodeView`), so that shortcut isn't available.
- Their current workaround is to hand-write a script (via AI) that opens
  `opencode.db` directly and joins across tables to reconstruct the same transcript.

**Proposed solution in the ticket:** "create a native Servoy function that can extract this
from the `opencode.db`" — i.e., build custom SQLite-table-crawling logic as a Servoy/MCP
feature.

## Root-cause assessment

The reported gap is real: `com.servoy.eclipse.opencode.OpenCodeView` (confirmed by reading
`OpenCodeView.java` and `RunOpencodeCommand.java`) only ever navigates the embedded browser
to `http://127.0.0.1:<port>/<project>/session[/...]`. There is no code anywhere in
`com.servoy.eclipse.opencode` or `com.servoy.eclipse.developer.mcp` that surfaces session
export, and no MCP tool wraps it either (`grep` for `export`/`Export` across both active
bundles found nothing relevant beyond unrelated WAR/solution "exporter" plugins and the
Cypress `module.exports` string literal).

However, OpenCode itself **already ships this exact feature**, confirmed against the current
OpenCode docs and the pinned version in this repo:

- `bundles/com.servoy.eclipse.opencode/opencode/package.json` pins `"opencode-ai": "~1.18.6"`.
- The official CLI reference (`https://opencode.ai/docs/cli/`) lists a top-level `export`
  command (not just a TUI `/export` slash-command):
  ```
  opencode export [sessionID]      # --sanitize redacts sensitive transcript/file data
  opencode import <file>
  ```
  This has existed since well before 1.14.x and returns exactly the JSON blob the reporter
  describes (session data including tool/MCP calls).
- The OpenCode HTTP server (which `RunOpencodeCommand` already launches via
  `opencode serve --port ... --hostname 127.0.0.1`, confirmed in
  `RunOpencodeCommand.java:108-109`) also exposes `GET /session/:id/message`, returning
  `{ info: Message, parts: Part[] }[]` per message — i.e. tool calls, MCP calls, reasoning
  parts, etc. — over plain HTTP, no DB access needed. `OpenCodeView.findLastSessionId`
  already talks to this same server via HTTP (`OpenCodeView.java:354-388`), so the
  infrastructure to call it is already in the codebase.

So there are two officially-supported ways to get the exact transcript the reporter wants,
neither of which touches `opencode.db`.

## Ticket premise check

The ticket's proposed approach — parsing `opencode.db` table-by-table — does not hold up:

1. **It duplicates a feature OpenCode already ships** (`opencode export`), maintained by the
   upstream project.
2. **It couples Servoy code to OpenCode's private storage schema.** The changelog confirms
   this schema is not a stable contract: v1.2.0 was a *breaking* migration that moved *all*
   session data from flat files into a single SQLite database (`~/.local/share/opencode/opencode.db`),
   with an explicit recovery instruction to `rm -rf` the DB on migration failure. A future
   OpenCode release could restructure the schema again (e.g. to v2) and silently break any
   hand-rolled cross-table joins Servoy ships.
3. **A public interface already exists** — either the CLI `export` command or the running
   server's REST API — so there's no technical reason to reach into internal storage.

The ticket does propose a solution, but it's the wrong one: it solves "how do we get this
JSON" by re-deriving something OpenCode already computes, instead of asking it for that
computed result directly.

## Approaches considered

1. **No code change** — tell users to run `opencode export <sessionID> [--sanitize]`
   manually from a terminal, `cd`'d into the plugin's managed opencode dir
   (`{eclipse-state}/opencode/`) with the same `XDG_*` env overrides
   `RunOpencodeCommand.buildServoyXdgEnv()` sets (since Servoy redirects OpenCode's data dir
   to `~/.servoy`).
   - Pros: zero engineering effort, uses the officially supported and already-installed CLI
     feature exactly as-is.
   - Cons: requires a terminal, requires knowing the session ID and the XDG override, and
     isn't discoverable from inside the Eclipse plugin (this is effectively what the reporter
     is already doing manually and finding painful — it doesn't close the gap).

2. **Add an "Export session" action to `OpenCodeView`** (toolbar button or context menu) that
   shells out to `opencode export <sessionID> --sanitize` using the same
   `IRunNPMCommand`/`ngActivator.createNPMCommand(...)` + `buildServoyXdgEnv()` pattern
   `RunOpencodeCommand` already uses, writing the resulting JSON/MD to a file the user picks
   (for attaching to Jira cases, building test flows, etc. — the reporter's stated end goal).
   - Pros: discoverable in-product, reuses officially-supported OpenCode functionality and
     existing plugin infra (no new dependency on OpenCode internals), inherits the
     `--sanitize` redaction for safe sharing.
   - Cons: needs to resolve "current session ID" for the active view (the existing
     `findLastSessionId` HTTP call already does this) and a small amount of new UI/Job code.

3. **Add an MCP tool that calls the running server's REST API**
   (`GET /session/:id/message`, optionally combined with `/session/:id/diff` and
   `/session/:id/todo`) and assembles the transcript in Java, without spawning a CLI process.
   - Pros: no extra process spin-up; reuses the server that's already running; callable
     directly by an agent (not just a human button-click).
   - Cons: the REST response is the raw message/part list — cost/timing/token metadata that
     `opencode export` bundles is not guaranteed to be reproduced 1:1, so Servoy would be
     re-assembling something close to, but not exactly, what `export` already produces —
     partially re-introducing the duplication problem from approach 4, just against a public
     API instead of the private DB.

4. **Build the custom `opencode.db` cross-table extraction the ticket proposes.**
   - Pros: full control over exact output shape.
   - Cons: duplicates an existing, actively maintained OpenCode feature; couples to an
     internal, versioned, and historically-breaking storage schema; highest maintenance cost
     for no functional benefit over approaches 1–3.

## Recommendation

**Approach 2** — add a discoverable "Export session" action to `OpenCodeView` that wraps the
already-installed `opencode export <sessionID> --sanitize` CLI command via the existing
npm-exec infrastructure (`RunOpencodeCommand`'s `createNPMCommand` + `buildServoyXdgEnv`
pattern), saving the output to a file the user can attach to a Jira case or use to build a
test flow.

This satisfies the reporter's actual goal (a one-click way to get the same rich transcript
they currently reconstruct by hand) while avoiding the ticket's proposed dependency on
OpenCode's private, historically-unstable SQLite schema. Approach 3 (REST-based) is a
reasonable fallback if a future need arises to fetch transcripts *without* spawning a process
(e.g. for a fully server-side/automated flow), but approach 2 is simpler, reuses an
upstream-maintained feature verbatim, and directly matches the reporter's `/export`-based
workflow.

Approach 4, as literally requested in the ticket, is not recommended.

## Git history findings

None relevant — `com.servoy.eclipse.opencode` has no prior commits touching session export;
this is new territory. The relevant prior art is `RunOpencodeCommand.java`'s existing
`npm exec -- opencode ...` invocation pattern and `buildServoyXdgEnv()` XDG override, both of
which the recommended approach reuses directly.
</content>

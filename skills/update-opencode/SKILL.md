---
name: update-opencode
description: "Use when updating the embedded opencode CLI the Servoy AI plugin runs (com.servoy.eclipse.opencode) to a newer release. Finds the latest @opencode/cli version, bumps the pinned version, diffs the opencode HTTP API between the pinned and new version, and checks every endpoint the Angular chat UI (webui) calls for breaking changes before updating AGENTS.md. Triggered by 'update opencode', 'bump opencode', 'new opencode version', 'opencode release', or '/update-opencode'."
---

# Update opencode (pinned, with an API break-check)

The Servoy AI plugin (`bundles/com.servoy.eclipse.opencode`) runs an **embedded
opencode CLI** and talks to its HTTP API from an Angular chat UI (`webui/`). The
opencode version is **pinned on purpose** (`bundles/com.servoy.eclipse.opencode/opencode/package.json`),
because opencode ships **breaking API changes in patch releases**. This skill
updates that pin **safely**: it diffs the API between the version we run now and
the target version, and verifies every endpoint our webui calls still works
BEFORE committing the bump.

> Rule 0 — never auto-float. The version in `opencode/package.json` must stay an
> **exact** version (no `^`, no `~`). The auto-update step was removed from
> `OpencodeFolderCreatorJob` on purpose. This skill is the only sanctioned way
> to move opencode forward.

## What breaks when opencode changes (history)

opencode has already broken us twice; both were patch-level API changes:

- **2.0.26** renamed the prompt attachment field `url` → `uri`
  (`PromptInput.FileAttachment`), so image/file upload failed with
  `400 Missing key ... [files][0][uri]`.
- **2.0.26** removed the ability to archive a session over HTTP: `PATCH
  /session/:id` only honours `title`/`metadata`/`permissions`; the old
  `{ time: { archived } }` is ignored and there is no archive route. The Archive
  action was removed.

Expect more of the same. The whole point of this skill is to catch the NEXT one
before shipping it.

## Inputs you need

1. The **current pinned version** — read it from
   `bundles/com.servoy.eclipse.opencode/opencode/package.json`
   (`dependencies["@opencode/cli"]`).
2. The **target version**:
   - If the user named one, use it.
   - Otherwise find the latest: `npm view @opencode/cli version` (or
     `npm view @opencode/cli versions --json` for the full list). Confirm the
     chosen version with the user before proceeding.
3. The **opencode source checkout** for diffing: `C:/Users/jcomp/git/opencode`
   (v2 branch). Confirm which version/tag it is on with
   `git -C C:/Users/jcomp/git/opencode describe --tags` and
   `Get-Content C:/Users/jcomp/git/opencode/packages/cli/package.json`. Ideally
   check out the tag matching the TARGET version so the diff reflects what we are
   moving to. If the checkout cannot be moved, say so and fall back to reading
   the published package / release notes.

## The endpoints our webui actually calls

These are the ONLY opencode API surfaces we depend on. The break-check must
cover every one. Source of truth in this repo:

- `bundles/com.servoy.eclipse.opencode/webui/src/app/services/opencode-api.service.ts`
- `bundles/com.servoy.eclipse.opencode/webui/src/app/services/status.service.ts`
- `bundles/com.servoy.eclipse.opencode/webui/src/app/services/v2-mapping.ts` (response shape)
- The BFF servlet that injects `location.directory`:
  `bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/tomcat/OpencodeChatServlet.java`

| Call | HTTP | Request shape we send | Response shape we read |
|---|---|---|---|
| `listSessions` | `GET /session` | – | `{ data: Session[] }` |
| `createSession` | `POST /session` | `{}` or `{ title }` (+ BFF injects `location.directory`) | `{ data: Session }` |
| `getSession` | `GET /session/:id` | – | `{ data: Session }` |
| `updateSessionTitle` | `PATCH /session/:id` | `{ title }` | 204 |
| `deleteSession` | `DELETE /session/:id` | – | 204 |
| `listMessagePage` / `listAllMessages` | `GET /session/:id/message` | query `limit`, `cursor` | `{ data: Message[], cursor: { next, previous } }` (newest-first) |
| `sendPrompt` | `POST /session/:id/prompt` | `{ text, files: [{ uri, name? }] }` | – (streams on `/event`) |
| `interrupt` | `POST /session/:id/interrupt` | `{}` (query `resume` optional) | – |
| `findFiles` | `GET /fs/find?query=` | query `query` | `{ data: FileMatch[] }` |
| `readFile` | `GET /fs/read/<path>` | path segment | text |
| `listPendingForms` | `GET /session/:id/form` | – | `{ data: FormInfo[] }` |
| `replyToForm` | `POST /session/:id/form/:formID/reply` | `{ answer }` | 204 |
| `cancelForm` | `DELETE /session/:id/form/:formID` | query `message` optional | 204 |
| `health` | `GET /info` | – | `{ version }` |
| `mcp` | `GET /mcp` | – | `{ data: [{ name, status }] }` |
| `providers` | `GET /provider` | – | `{ data: [{ id, activation }] }` |
| event stream | `GET /event` (SSE) | – | bus events (`session.*`, `form.*`, `message.*`) |

Also depended on, from the live event bus (see `chat-store.service.ts`):
`session.tool.input.started/called/progress/success/error` (tool parts incl.
`metadata.sessionID` for subagents), `session.text.*`, `session.reasoning.*`,
`session.idle/execution.*/error`, `session.created/updated/deleted`,
`form.created/replied/cancelled`.

## Procedure

Work through these steps in order. Do not skip the diff/break-check — that is
the reason this skill exists.

### 1. Determine current + target version
- Read the pinned version from `opencode/package.json`.
- Resolve the target (user-named, or `npm view @opencode/cli version`). Confirm.
- If current == target, stop: nothing to do.

### 2. Diff the opencode API between the two versions
Use the `C:/Users/jcomp/git/opencode` checkout. Prefer a real git diff scoped to
the API surface:

```
git -C C:/Users/jcomp/git/opencode fetch --tags
git -C C:/Users/jcomp/git/opencode diff <current-tag>..<target-tag> -- ^
  packages/server/src/handlers ^
  packages/core/src/session ^
  packages/schema/src/prompt.ts ^
  packages/schema/src/prompt-input.ts ^
  packages/schema/src/filesystem.ts
```

(Tags look like the npm version, e.g. `v2.0.26`; confirm the tag format with
`git -C ... tag --list 'v2.0.*'`.) If exact tags are unavailable, diff the
closest refs and note the imprecision. Read the schema/handler files directly
when a diff is noisy:
- Prompt attachment input: `packages/schema/src/prompt-input.ts`
  (`PromptInput.FileAttachment`) and `packages/core/src/session/prompt.ts`.
- Session routes + payloads: `packages/server/src/handlers/session.ts`
  (read `ctx.payload.*` / `ctx.params.*` / `ctx.query.*` per handler).
- Messages/pagination: `packages/server/src/handlers/message.ts`.
- Filesystem: `packages/server/src/handlers/fs.ts`.
- Response envelope + event names: `packages/protocol` / bus event types.

### 3. Break-check every endpoint we call
For each row in the table above, confirm against the TARGET source:
- the **route** still exists and the path is unchanged;
- the **request fields** we send are still accepted (watch for renamed/removed
  keys — this is how `url`→`uri` bit us);
- the **response shape** we read is unchanged (envelope `{ data }`, `cursor`,
  array item fields);
- for the event bus, the **event names and payload fields** we handle still
  exist (especially tool `metadata.sessionID`).

Produce a short report: for each endpoint, `OK` / `CHANGED (what)` / `REMOVED`.
Call out every CHANGED/REMOVED as an action item with the exact file+line in our
webui to fix. If you can reach the **running** server, validate the highest-risk
calls live through the BFF (`http://127.0.0.1:<port>/servoy_ai/rest_api/...`)
rather than trusting the source alone.

### 4. Apply the version bump
- Set `dependencies["@opencode/cli"]` in `opencode/package.json` to the exact
  target version (no range). Keep the explanatory `comment` field.
- Changing this file changes the install sentinel, so
  `OpencodeFolderCreatorJob.needsInstall()` triggers a clean reinstall on the
  next Servoy Developer start.

### 5. Fix any breaks found in step 3
Edit the webui services (`opencode-api.service.ts`, `status.service.ts`,
`v2-mapping.ts`, `chat-store.service.ts`) and/or the BFF servlet. Add/adjust unit
tests (Vitest) for every changed request/response shape, the way the existing
tests pin `{ uri, name }`, the `{ data, cursor }` pagination, etc.

### 6. Verify
- `cd bundles/com.servoy.eclipse.opencode/webui && npm test` (all green).
- `npm run build` (writes `../webui-dist`).
- If the Java BFF changed, `eclipse-ide_getCompilationErrors` on
  `com.servoy.eclipse.opencode` must be zero, and run its unit tests
  (`com.servoy.eclipse.opencode.tests`).
- Live-smoke the touched features in the Servoy AI view after a reload
  (upload, send, scroll-older, export, forms) when possible.

### 7. Update AGENTS.md
In this repo's root `AGENTS.md`, update the `com.servoy.eclipse.opencode` section
to reflect the new pinned version and any contract change (e.g. the `~1.15.x` /
version strings, the "Update strategy" paragraph which must say the version is
PINNED and updated via this skill, and any renamed field or dropped feature).

### 8. Commit
Follow this repo's convention: subject ends with ` [ai]`, include the Jira case
if there is one. Suggested message:
`SVY-XXXXX update opencode to <version> (API break-check + fixes) [ai]`.
Show the user the staged files + message and wait for approval (per AGENTS.md
pre-commit checklist). Do not push unless asked.

## Guardrails
- Never change the pin to a range, and never re-add an auto-`npm update` step.
- Never claim an endpoint is unaffected without having read the target source
  (or validated live). If the opencode checkout is not on the target version,
  say the diff is approximate.
- A clean `npm test` is necessary but not sufficient — a renamed field the tests
  don't assert can still break at runtime; that is why step 3 reads the source.

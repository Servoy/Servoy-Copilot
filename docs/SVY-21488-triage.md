# Triage Report — SVY-21488

**Verdict:** PROCEED

## Reported problem
When an agent calls opencode's interactive "ask the user to choose" tool (the JSUnit
"run all" ambiguity rule is the trigger: the active solution `cloudSync` is not a
`*_test` solution, so the Orchestrator asks "run as-is or switch to which test
solution?"), the Servoy AI embedded chat never renders the prompt. The agent's
reasoning and a collapsed "Question" tool row appear in the transcript, but no options
and no input are shown, the row hangs with a spinner, and the run never continues. Every
agent flow that legitimately asks a question (JSUnit ambiguity, SDD gates,
"which test type?" route, any "ask when unclear" rail) dead-ends.

## Root-cause assessment
The custom Angular chat UI (`bundles/com.servoy.eclipse.opencode/webui`) has no handling
for opencode's interactive question exchange — neither rendering the prompt nor sending
an answer back. Verified against the current sources:

- `EventStreamService` (`webui/src/app/services/event-stream.service.ts`) opens the SSE
  bus and dispatches raw events; `ChatStore.onEvent` (`services/chat-store.service.ts:288`)
  has `switch` cases only for session lifecycle, streamed text/reasoning, and
  `session.tool.*` / `message.*`. There is no case for the question/form events, so the
  store never learns a question is pending.
- `part-utils.ts` classifies parts as text / reasoning / tool only (`isRenderablePart`,
  `:45`), and `TOOL_DISPLAY_NAMES` (`:60`) has no interactive branch. The `question` tool
  call therefore surfaces as an ordinary collapsed tool row via the
  `session.tool.input.started`/`called` handlers — exactly the stuck "Question" spinner
  observed, because that tool never emits a `session.tool.success` (it is blocked waiting
  on the user).
- `opencode-api.service.ts` has `sendPrompt`, `interrupt`, session CRUD, and file
  helpers — but no call to answer a pending question. So even a rendered prompt has no
  way to submit.
- A grep of `webui/src` for `question`/`permission`/`form`/`reply` finds only unrelated
  matches (a status-panel comment, a `chat-store` spec string). Zero question-handling
  code — confirming the ticket's own diagnosis.

The ticket's protocol spike is answerable from the shipped opencode version rather than
requiring a live capture. The bundle pins `@opencode/cli ~2.0.18`
(`bundles/com.servoy.eclipse.opencode/opencode/package.json`), i.e. opencode v2. In v2
the old v1 `question` tool was **replaced by a typed Form flow** (confirmed by the v2
OpenAPI spec at `https://opencode.ai/v2/openapi.json` and the oh-my-opencode-slim v2
compatibility notes). The exact contract:

- **Bus events** (SSE `rest_api/event`, payload under `data`): `form.created`
  (`{ form: Form.Info }`), `form.replied`, and `form.cancelled` (`{ id, sessionID }`).
  `Form.Info` = `{ id: "frm_…", sessionID, title, fields: Form.Field[] }`.
- **Fields** are a discriminated union on `type`: `string` (optionally with
  `options: {value,label,description}[]` and a `custom: boolean` "type your own" flag),
  `number`, `integer`, `boolean`, `multiselect` (with `options`), and `external` (a link
  the user must acknowledge). Fields carry `required`, `default`, and `when`
  (conditional visibility) clauses.
- **Answer endpoint**: `POST /api/session/{sessionID}/form/{formID}/reply` with body
  `{ answer: { [fieldKey]: Form.Value } }` where `Form.Value` is
  `string | number | boolean | string[]`. `GET /api/session/{id}/form` lists pending
  forms (for reload recovery); `DELETE …/form/{formID}` cancels.
- **Free-text**: supported on a `string` field via `custom: true` alongside `options`
  (pick an option or type your own); a plain `string` field with no `options` is pure
  free text.

This resolves all three of the ticket's open spike questions. It also confirms the fix is
purely front-end + a proxy check, exactly as scoped.

## Ticket premise check
The premise holds and is correct — this is a genuine missing UI capability in the custom
chat front-end, not a skill/agent-config problem. The Orchestrator has `question: allow`,
the skill detects the ambiguity, and the tool is called; other opencode clients (TUI,
OpenChamber) implement the Form exchange and work. The only refinement to the ticket:

- The ticket frames a "protocol spike" as a prerequisite. That spike is already resolved
  above from the pinned opencode v2 OpenAPI spec — v2 has no `question`/`permission`
  event for this; it is the **Form** flow. A live capture can confirm payload framing but
  is not a blocker to speccing.
- The BFF almost certainly needs **no change**. `OpencodeChatServlet` is a generic
  pass-through: `isApiRequest` matches everything under `/rest_api/**`, `toUpstreamPath`
  rewrites `/rest_api/x` → `/api/x`, and `forwardRequest` proxies any method (including
  POST) with the injected directory + auth (`OpencodeChatServlet.java:207,230,430`). So
  `POST /rest_api/session/{id}/form/{formID}/reply` and `GET /rest_api/session/{id}/form`
  already route through unchanged. The spec should still add a servlet test asserting the
  form reply path classifies and maps correctly, but no new servlet code is expected.

## Approaches considered
1. **Implement the Form exchange in the webui (recommended).** Handle `form.created` /
   `form.replied` / `form.cancelled` in `EventStreamService`/`ChatStore` (track a pending
   form per session), render an interactive form component (title + typed fields:
   options, free text, boolean, multiselect, external-link acknowledge, honoring
   `required`/`default`/`when`), and add `replyToForm(sessionID, formID, answer)` /
   `cancelForm(...)` to `opencode-api.service.ts` POSTing through the existing
   `/rest_api/**` proxy. Add a `GET /session/{id}/form` recovery call so a reload
   re-shows a pending form. Model it on OpenChamber's `formCardState.ts` (pure form
   logic: `evaluateForm`, `buildFormAnswer`, `when`-clause visibility) and `FormCard`.
   - Pros: the real fix; unblocks every interactive flow uniformly; matches how opencode
     v2 is designed; the field/answer model is fully specified so no guesswork.
   - Cons: real UI work (a form component + service methods + store wiring + tests). The
     `when`/conditional/`external` semantics need care to match server validation
     (OpenChamber already solved this and is a faithful reference to port, not copy).
2. **Interim skill workaround — ask as plain assistant text instead of the question/form
   tool.** Edit the ambiguous-case rule so the tester/orchestrator returns the choice as a
   normal chat message ("reply with the test solution name, or say 'run it anyway'").
   Plain text renders today.
   - Pros: unblocks the JSUnit case immediately with a tiny skill edit, no UI work.
   - Cons: only covers hand-edited flows; every other question caller still hangs; loses
     the structured picker; free-text parsing is less reliable.
3. **No code change.** Not viable — any agent question hangs, silently breaking the
   ask-when-unclear contract across the whole tool.

## Recommendation
**PROCEED with Approach 1** as the durable fix, optionally applying Approach 2 as an
interim skill unblock for the JSUnit "run all" case while Approach 1 lands. The spec does
not need a separate protocol-spike phase — the opencode v2 Form contract is captured above
(events, field union, reply endpoint, free-text support). Scope for the spec:

- `webui` — `EventStreamService`/`ChatStore` (handle `form.created`/`replied`/`cancelled`,
  hold pending-form state, clear on settle), a new interactive form component (port
  OpenChamber's `formCardState.ts` logic + a Angular/signals `FormCard`), and
  `opencode-api.service.ts` (`replyToForm`, `cancelForm`, `listPendingForms`).
- `OpencodeChatServlet` — confirm-only: the reply/list/cancel paths already proxy through
  `/rest_api/**`; add a servlet unit test asserting classification/upstream mapping for
  `session/{id}/form/{formID}/reply`. Add servlet code only if a gap is found.
- Out of scope: the agent/skill logic that raises questions (already correct); the
  SVY-21414 runner fix.

## Git history findings
Not investigated in depth — the affected files (`event-stream.service.ts`,
`chat-store.service.ts`, `part-utils.ts`, `opencode-api.service.ts`,
`OpencodeChatServlet.java`) were all introduced by the SVY-21461 custom-chat-UI work
(`docs/SVY-21461-custom-chat-ui.spec.md` / `docs/SVY-21461-triage.md`), which explicitly
scoped iteration 1 to streaming chat + the API proxy and did not include the interactive
question/permission exchange. This ticket is the intended follow-up. No prior decision is
being reverted.

# Spec: SVY-21488 — Servoy AI chat UI does not render agent question prompts (implement the opencode v2 Form exchange)

## 1. Goal
The Servoy AI embedded chat (the custom Angular `webui` app) hangs whenever an agent asks the user to decide something. In opencode v2 the old v1 `question` tool was replaced by a typed **Form** flow (`form.created` / `form.replied` / `form.cancelled` bus events plus a REST reply endpoint), and the `webui` app has no handling for it — so the question surfaces only as a stuck "Question" tool spinner and the run dead-ends. This spec implements the full Form exchange in `webui`: detect a pending form from the event bus (and on reload), render an interactive form (title + typed fields honoring `required`/`default`/`when`), and submit or cancel the answer back through the existing `/rest_api/**` BFF proxy. This unblocks every "ask when unclear" agent flow (JSUnit "run all" ambiguity, SDD gates, "which test type?" routes) uniformly.

## 2. Background

### 2.1 The reported failure
When the Orchestrator detects an ambiguity (e.g. the active solution `cloudSync` is not a `*_test` solution) it correctly calls opencode's interactive question tool. Other opencode clients (TUI, OpenChamber) render the prompt and the run continues. In the Servoy AI chat the agent's reasoning and a collapsed "Question" tool row appear, but no options and no input — the row hangs with a spinner because that tool never emits a `session.tool.success` (it is blocked waiting on the user). The agent/skill logic is correct; the gap is purely in the front-end. This is a follow-up to SVY-21461 (the custom chat UI), whose iteration 1 was explicitly scoped to streaming chat + the API proxy and never implemented the interactive exchange.

### 2.2 The opencode v2 Form contract (from the pinned v2 OpenAPI spec)
The bundle pins `@opencode/cli` 2.x (opencode v2). The v2 HTTP API (`https://opencode.ai/v2/openapi.json`) exposes a Form flow under the session:

- `GET  /api/session/{sessionID}/form` — list pending forms → `{ data: Form.Info[] }`
- `GET  /api/session/{sessionID}/form/{formID}` — get one form + state → `{ data: Form.Detail }` (adds `state: { status: 'pending' | 'answered' | 'cancelled', answer? }`)
- `POST /api/session/{sessionID}/form/{formID}/reply` — submit an answer, body `Form.Reply` = `{ answer: Form.Answer }`, returns **204**. Errors: `400 FormInvalidAnswerError`, `404 FormNotFoundError` / `SessionNotFoundError`, `409 FormAlreadySettledError`.
- `DELETE /api/session/{sessionID}/form/{formID}` — cancel a pending form, returns **204**. `409 FormAlreadySettledError` if already settled.
- (There is also a location-scoped `GET /api/form` and `POST /api/session/{sessionID}/form` create; the client only needs the list/get/reply/cancel above.)

`Form.Info` = `{ id: string (^frm_), sessionID: string, title: string, metadata?, fields: Form.Field[] (min 1) }`.

**Live-captured shape (confirmed against the running v2 server, `metadata.kind: "question"`):**
```json
{"data":[{
  "id":"frm_...","sessionID":"ses_...","title":"Questions",
  "metadata":{"kind":"question","tool":{"messageID":"msg_...","id":"toolu_..."}},
  "fields":[{
    "key":"q0","title":"Fruit preference","description":"Which fruit do you prefer?",
    "type":"string",
    "options":[{"value":"Apple","label":"Apple","description":"A crisp, sweet-tart fruit"}, ...],
    "custom":true
  }]
}]}
```
`POST /session/{id}/form/{formID}/reply` with `{"answer":{"q0":"Banana"}}` returned **204** and the agent continued ("You picked banana. 🍌"); `GET …/form` then returned `{"data":[]}`. The answer map is keyed by each field's `key` (`q0`), confirming §2.2's `Form.Answer` shape. `metadata.tool.id` is the `toolu_...` id of the blocked "Question" tool part — the store can use it to correlate/replace the stuck tool row when the form renders.

`Form.Field` is a discriminated union on `type`:

| type | key fields |
|---|---|
| `string` | `title?`, `description?`, `required?`, `hidden?`, `when?`, `format?` (`email`/`uri`/`date`/`date-time`), `minLength?`, `maxLength?`, `pattern?`, `placeholder?`, `default?`, `options?: Form.Option[]`, `custom?: boolean` (allow free text alongside options) |
| `number` | + `minimum?`, `maximum?`, `default?` (number or `"Infinity"`/`"-Infinity"`/`"NaN"`) |
| `integer` | same as number |
| `boolean` | + `default?: boolean` |
| `multiselect` | `options: Form.Option[]` (required), `minItems?`, `maxItems?`, `custom?`, `default?: string[]` |
| `external` | `url: string` (required), `title?`, `description?` — a link the user acknowledges; still carries `key`, `type`, `url` |

All non-`external` fields carry `key` (required), `type` (required), and optional `title`, `description`, `required`, `hidden`, `when`.

`Form.Option` = `{ value: string, label: string, description? }`.

`Form.When` = `{ key: string, op: 'eq' | 'neq', value: string | number | boolean }` — an array of these on a field gates its visibility against other fields' current answers (all clauses must hold; treat as AND).

`Form.Value` = `string | number | boolean | string[]` (numbers may serialize as `"Infinity"`/`"-Infinity"`/`"NaN"`).

`Form.Answer` = `Record<fieldKey, Form.Value>`. `Form.Reply` = `{ answer: Form.Answer }`.

The bus event stream (`GET /api/event`, proxied as `rest_api/event`) is a `text/event-stream` of `{ id, event, data }` frames; the store already reads the flat `{ type, data }` shape. The `form.created` event name was **confirmed live** by reproducing the bug against the running Servoy AI view with `?debug_view=true`: after the agent calls the interactive tool, the SSE overlay shows `session.tool.input.started` → `session.tool.called` (no `session.tool.success`, hence the stuck "Question" spinner) followed immediately by `arrive type=form.created`. The store has no case for it, so it is dispatched and dropped. The companion `form.replied` / `form.cancelled` frames (`data: { id, sessionID }`) come from OpenChamber prior art. The `/api/location/reload` route documents that "pending permissions and forms are cancelled" and emits `location.shutdown`, so a cancel path must exist client-side.

### 2.3 Relevant webui architecture
- `services/event-stream.service.ts` — opens `EventSource('rest_api/event')`, re-enters the Angular zone, and emits parsed `OpencodeEvent` (`{ type, data?, properties? }`).
- `services/chat-store.service.ts` — central signal store. `onEvent(evt)` switch handles session lifecycle, streamed text/reasoning, `session.tool.*`, and V1 `message.*` fallbacks. No form case today, so the store never learns a form is pending. `matchesActiveSession(props)` gates per-session events; `openSession()` seeds from `listMessages`.
- `services/opencode-api.service.ts` — typed wrappers over `rest_api/**` (`base = 'rest_api'`), unwrapping the v2 `{ data }` envelope. Has session CRUD, `sendPrompt`, `interrupt`, `findFiles`, `readFile`. No form methods.
- `services/part-utils.ts` — classifies parts as text/reasoning/tool only (`isRenderablePart`, `TOOL_DISPLAY_NAMES`), which is why the question tool renders as a generic collapsed row.
- `models/opencode.models.ts` — loose interfaces; add the Form types here.
- `components/` — `message-list` → `message-item` (renders parts), `composer`, `status-panel`, etc. All standalone, zoneless, signals, `OnPush`, SCSS. Vitest for tests.
- `app.component.html` / `app.component.ts` — the shell wiring the store to the components.

### 2.4 The BFF proxy (no new code expected)
`OpencodeChatServlet` is a generic pass-through: `isApiRequest` matches everything under `/rest_api/**`, `toUpstreamPath` rewrites `/rest_api/x` → `/api/x`, `injectDirectory` adds the project directory, and `forwardRequest` proxies **any** method (GET/POST/DELETE) with the injected directory + basic auth. So `POST /rest_api/session/{id}/form/{formID}/reply`, `GET /rest_api/session/{id}/form`, and `DELETE …/form/{formID}` already route through unchanged. Only a servlet **unit test** asserting the mapping is needed (no servlet code change is expected).

## 3. Design

### 3.1 Form models (`models/opencode.models.ts`)
Add loose TypeScript interfaces mirroring §2.2: `FormOption`, `FormWhen`, the field union (`FormStringField`, `FormNumberField`, `FormIntegerField`, `FormBooleanField`, `FormMultiselectField`, `FormExternalField`) as `FormField`, `FormInfo` (`{ id, sessionID, title, metadata?, fields: FormField[] }`), `FormValue` (`string | number | boolean | string[]`), `FormAnswer` (`Record<string, FormValue>`), and `FormReply` (`{ answer: FormAnswer }`). Keep them tolerant of unknown fields (`[key: string]: unknown`) like the existing models.

### 3.2 Pure form logic (`services/form-state.ts`) — ported from OpenChamber `formCardState.ts`
A dependency-free module (no Angular) so it is trivially unit-testable, modeled on OpenChamber's `formCardState.ts`:

- `initialAnswer(form: FormInfo): FormAnswer` — seed each field from its `default` (boolean → `false` when absent, multiselect → `[]`, string/number/integer → unset unless `default` present). External fields have no value.
- `isVisible(field: FormField, answer: FormAnswer): boolean` — evaluate the field's `when` clauses (`eq`/`neq`) against current answers; a field with no `when` is always visible; `hidden: true` fields are never rendered.
- `evaluateForm(form, answer)` → `{ visibleFields, errors: Record<key,string>, valid: boolean }` — validate visible, required fields are answered; enforce `minLength`/`maxLength`/`pattern`/`format` (string), `minimum`/`maximum` (number/integer), `minItems`/`maxItems` (multiselect), and external-field acknowledgement. Hidden/invisible fields are skipped and excluded from the answer.
- `buildFormAnswer(form, answer)` → `FormAnswer` — the settled answer containing only visible, non-external fields, coercing numeric strings and dropping unset optional fields. This is the body sent to `/reply`.
- Number edge cases: preserve the `"Infinity"`/`"-Infinity"`/`"NaN"` string sentinels the server uses.

### 3.3 API methods (`services/opencode-api.service.ts`)
Add three wrappers on the existing `base = 'rest_api'`:

- `listPendingForms(sessionID: string): Observable<FormInfo[]>` → `GET rest_api/session/{id}/form`, unwrap `{ data }`.
- `replyToForm(sessionID: string, formID: string, answer: FormAnswer): Observable<void>` → `POST rest_api/session/{id}/form/{formID}/reply` with body `{ answer }` (204 → `void`).
- `cancelForm(sessionID: string, formID: string): Observable<void>` → `DELETE rest_api/session/{id}/form/{formID}` (204 → `void`).

Encode path segments with `encodeURIComponent` as the other methods do.

### 3.4 Store wiring (`services/chat-store.service.ts`)
- New signal `pendingForm = signal<FormInfo | null>(null)` (one pending form per active session is sufficient for the current flows; if the list returns several, show the first pending). Expose it read-only for the shell.
- In `onEvent`, add cases:
  - `form.created` → if `matchesActiveSession` / the form's `sessionID` equals the active session, set `pendingForm` to the `Form.Info` from the payload.
  - `form.replied` / `form.cancelled` → if it matches the current pending form's id, clear `pendingForm` (and let streaming resume from the normal `session.*` events).
- `openSession(id)` → after seeding messages, call `listPendingForms(id)` and set `pendingForm` to the first pending form (reload recovery). Clear `pendingForm` in `newSession()` and `afterRemoval()`.
- `submitForm(answer: FormAnswer)` → guard on `pendingForm()`, call `api.replyToForm(...)`, clear `pendingForm` on success; on `409 FormAlreadySettledError` clear silently (already handled elsewhere); surface other errors via `error`.
- `cancelPendingForm()` → call `api.cancelForm(...)`, clear on success.
- `matchesActiveSession` already reads `sessionID` from `data`; ensure the form payloads expose `sessionID` (directly or nested under `form`).

### 3.5 Interactive form component (`components/form-card.component.ts`) — ported from OpenChamber `FormCard`
A standalone, `OnPush`, signals component modeled on OpenChamber's `FormCard`:

- Input: `form: FormInfo`. Outputs: `submit: FormAnswer`, `cancel: void`.
- Holds an `answer` signal seeded via `initialAnswer`; a `computed` runs `evaluateForm` to derive visible fields, per-field errors, and overall validity (disables submit until valid).
- Renders the title/description, then each **visible** field by type:
  - `string` with `options` → a select/radio of options; when `custom: true` add a "type your own" free-text entry. Plain `string` (no options) → a text input honoring `placeholder`/`format`.
  - `number`/`integer` → a numeric input honoring `minimum`/`maximum`.
  - `boolean` → a checkbox/toggle.
  - `multiselect` → checkbox group over `options` honoring `minItems`/`maxItems`; `custom` allows adding free-text entries.
  - `external` → the `url` rendered as a link the user opens, plus an "I've done this" acknowledge control that satisfies the field.
- Submit builds the body with `buildFormAnswer` and emits it; Cancel emits `cancel`.
- Accessibility: label each control (`for`/`id`), mark required fields, expose validation errors via `aria-describedby`, ensure keyboard operability (Enter submits when valid, focus the first field on render). Match the existing SCSS/theme tokens used by `composer`/`status-panel`.

### 3.6 Shell wiring (`app.component.*`)
Render `svy-form-card` between the message list and the composer when `store.pendingForm()` is non-null, bound to `[form]`, with `(submit)="store.submitForm($event)"` and `(cancel)="store.cancelPendingForm()"`. Disable/soften the composer while a form is pending (the run is blocked on the answer). Ensure `message-list` scroll-to-bottom still works with the form card present.

### 3.7 BFF servlet unit test (confirm-only)
Add assertions to `OpencodeChatServletTest` that `toUpstreamPath("/rest_api/session/ses_x/form/frm_y/reply")` maps to `/api/session/ses_x/form/frm_y/reply`, that `isApiRequest` is true for the form/list/cancel paths, and (optionally) that `injectDirectory` appends the directory to the reply path. No new servlet code unless a mapping gap is found.

## 4. Implementation plan
1. `models/opencode.models.ts` — add the Form model interfaces (§3.1).
2. `services/form-state.ts` — port OpenChamber `formCardState.ts` pure logic (`initialAnswer`, `isVisible`, `evaluateForm`, `buildFormAnswer`) + `form-state.spec.ts` covering `when` visibility, required/default handling, multiselect min/max, external acknowledgement, and numeric sentinels.
3. `services/opencode-api.service.ts` — add `listPendingForms`, `replyToForm`, `cancelForm` (§3.3) + extend `opencode-api.service.spec.ts` asserting the `rest_api/session/{id}/form…` paths, verbs, `{ answer }` body, and `{ data }` unwrap.
4. `services/chat-store.service.ts` — add `pendingForm` signal, `form.created`/`replied`/`cancelled` cases, reload recovery in `openSession`, and `submitForm`/`cancelPendingForm` (§3.4) + extend `chat-store.service.spec.ts` (form.created sets pending for the active session only; replied/cancelled clears; openSession recovers a pending form; submit/cancel call the API and clear).
5. `components/form-card.component.ts` (+ `.html`, `.scss`) — the interactive form (§3.5) + `form-card.component.spec.ts` (renders each field type, honors `when`/`required`/`default`, emits the correct answer on submit, emits cancel).
6. `app.component.html` / `app.component.ts` — render the form card when a form is pending; soften the composer (§3.6).
7. `part-utils.ts` — optionally add a friendlier label for the interactive/question tool row so any residual tool row reads sensibly; not strictly required once the form card renders.
8. `tests/com.servoy.eclipse.opencode.tests/.../OpencodeChatServletTest.java` — add the form reply/list/cancel mapping assertions (§3.7).
9. Run `npm test` (Vitest) in `webui/`; run `OpencodeChatServletTest` via the plain-JUnit path. Do a production `ng build` before considering the UI change complete (per AGENTS.md dev/build loop).

## 5. Acceptance criteria
- [ ] When an agent calls the interactive question/form tool, the chat renders the form title and its typed fields (options, free text where `custom`, boolean, multiselect, number/integer, external-link acknowledge) instead of a stuck "Question" spinner.
- [ ] Field visibility honors `when` clauses; required fields block submit until answered; `default` values pre-fill; numeric/length/pattern constraints are enforced client-side before submit.
- [ ] Submitting POSTs `{ answer }` to `rest_api/session/{id}/form/{formID}/reply`; on success the form clears and the agent run continues.
- [ ] Cancelling DELETEs `rest_api/session/{id}/form/{formID}` and clears the form.
- [ ] Reloading the app while a form is pending re-shows it (via `GET rest_api/session/{id}/form`).
- [ ] `form.created` for a non-active session does not pop a form into the active view; `form.replied`/`form.cancelled` clear the matching pending form.
- [ ] A `409 FormAlreadySettledError` on reply/cancel is handled gracefully (form clears, no error banner spam).
- [ ] Vitest suites for `form-state`, `opencode-api` (form methods), `chat-store` (form events + recovery), and `form-card` pass.
- [ ] `OpencodeChatServletTest` asserts the form reply/list/cancel path classification + upstream mapping and passes; no new servlet code required (or the gap is documented and fixed if found).
- [ ] The form card is keyboard-operable and screen-reader labeled (labels, required markers, error `aria-describedby`).

## 6. Out of scope
- The agent/skill logic that raises questions (Orchestrator `question: allow`, the JSUnit ambiguity rule) — already correct.
- The interim skill workaround (Approach 2: ask as plain chat text) — not part of this spec.
- The SVY-21414 `runJsUnitTests` "run all" runner fix.
- Permission (`permission.*`) prompts, if distinct from forms — only the Form exchange is in scope here.
- opencode's own built-in web UI (the embedded browser uses the custom Angular app).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| `form.created` confirmed live (SSE overlay in the running view). Still confirm the exact payload shape of `form.created` (`data.form` = Form.Info?) and of `form.replied`/`form.cancelled` (`data.{id,sessionID}`) — read the `data` field in the coding phase and align the store parsing to it. | Dev | partially resolved |
| Can more than one form be pending per session at once? Live probe returned a single-element `data` array for a single question; the UI shows the first pending form. (Spec assumes one at a time.) | Product/Dev | mostly resolved |
| Does `external` field acknowledgement submit a specific `Form.Value`, or is opening the link sufficient (no value in the answer)? Not exercised by the live probe (question forms use `string`); confirm against server validation if an `external` field is ever produced. | Dev | open |

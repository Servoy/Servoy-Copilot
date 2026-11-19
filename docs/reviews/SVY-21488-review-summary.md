# Review Summary — SVY-21488: Servoy AI chat UI does not render agent question prompts

**Risk: MODERATE.** A pending question form is cleared only by form events, a successful reply/cancel, or a 409, so Stop, a missed SSE event or a 404 can leave the card up and the composer locked (`webui/src/app/services/chat-store.service.ts`, `abort()`, `submitForm`/`cancelPendingForm`, `session.idle`/`session.error` handlers).

Scope: Servoy-Copilot `master`, commits `dba3888`, `6314ebf`, `9b6a5d5`. Fix Version 2026.12, release-target check MATCH. Front-end only; no production Java changed. Security relevance LOW (no `innerHTML`; an agent-supplied `pattern` regex runs on the main thread, Low).

## Manual test plan

**Verifying the fix**
1. Start Servoy Developer with the AI view. Open a solution that is not a `*_test` solution and run the JSUnit "run all" request, so the agent asks which test solution to use.
2. Confirm the question card appears with the options (and a free-text field where offered), and that the composer is disabled with "Answer the question above to continue."
3. Pick an option and submit. Confirm the card disappears, the composer re-enables when the turn finishes, and the run continues.
4. Open a question, then reload the browser view. Confirm the card reappears.
5. Open a question and press Cancel in the card. Confirm the card clears and the turn stops.
6. If the agent can produce required, number or pattern fields, confirm Submit stays disabled until the input is valid.

**Regression checks**
1. Open a question and press Stop in the composer. Check whether the card and the composer lock clear.
2. With a question open, restart the opencode process, then try to reply or cancel. Check that the card can be dismissed.
3. Open a question, switch to another session and back. Check the card is restored and the composer state is right.
4. With no provider connected, send a prompt and see the error banner. Reload and reopen the session, and check the error is still visible.
5. Send a normal prompt. The user's message should appear immediately, with no duplicate after reload and no stray "thinking" dots in an old transcript.
6. Send a prompt with a bad attachment and check the transcript after the error.
7. In a long session with many idle markers, scroll to the top repeatedly and check older pages still load.
8. Export a session (JSON) and confirm idle markers are still present as before.
9. Open a subagent session and confirm the composer hint is still the subagent text.

Automated: from `bundles/com.servoy.eclipse.opencode/webui`, `npm test`; plain JUnit `OpencodeChatServletTest` and `OpencodeChatServletGuardTest`. Nothing was run by the review agents.

## Possible improvements / follow-ups
- Clear the pending form (and re-enable the composer) on `session.idle` / `session.error` for its session, on Stop, and on a 404 from reply/cancel. Re-list forms after an SSE reconnect. Open: does opencode 2.2.0 emit `form.cancelled` on interrupt?
- The seed/paging filter `isRenderableMessage` ignores `info.error`, so a failed assistant turn with no parts (e.g. "No AI account is connected") disappears on reopen. Keep messages that carry `info.error`; add a unit test.
- Forms raised in a subagent (child) session are dropped when the parent is active, so the parent looks hung. Decide whether to add a cue.
- The optimistic `local-user-<Date.now()>` message stays after a rejected send.
- The form card focuses its first control on render, which can steal focus from the composer.
- Add `GET/POST/DELETE rest_api/session/:id/form[...]` and the `form.*` events to the `update-opencode` break-check list, and document the Form exchange in `AGENTS.md` (currently only in `docs/SVY-21488-form-exchange-webui.spec.md`).
- Low: cap the tested string length before running an agent-supplied `pattern` regex.

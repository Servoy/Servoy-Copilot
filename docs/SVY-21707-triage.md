# Triage Report — SVY-21707

**Verdict:** PROCEED

## Reported problem
"Reasoning shouldn't show by default in Servoy AI." The description states Servoy AI is
"now spamming with reasoning output, that should be collapsed." The observed symptom: the
custom Angular chat UI renders the model's reasoning (thinking) text inline in every
assistant turn, taking up vertical space and cluttering the transcript. The reporter wants
reasoning hidden/collapsed by default rather than shown expanded.

## Root-cause assessment
The behaviour is entirely in this project's code — the custom Angular chat UI in
`com.servoy.eclipse.opencode/webui/`. It is not an opencode-CLI, model, or configuration
issue.

Reasoning parts are rendered inline, always expanded, with no collapse control:

- `webui/src/app/components/message-item.component.html` (lines 21–24) renders each
  reasoning part directly:
  ```html
  } @else if (isReasoning(part)) {
    <div class="reasoning">
      <svy-markdown [content]="part.text ?? ''"></svy-markdown>
    </div>
  }
  ```
  Unlike tool parts (lines 25–48), which have a clickable `tool-row` caret and an
  `isExpanded(...)` gate, reasoning has **no toggle** — its full markdown is always visible.

- `webui/src/app/components/message-item.component.scss` (lines 116–122) only styles
  `.reasoning` as a muted, left-bordered block; nothing collapses it.

- `webui/src/app/services/part-utils.ts` — `isReasoningPart` (line 72) classifies the part
  and `isRenderablePart` (line 112) includes it; reasoning is upserted/streamed into the
  message by `chat-store.service.ts` (`onReasoningStarted/Delta/Ended`, lines 834–855). So
  the data is present and correct — the issue is purely how `message-item` displays it.

The model emits reasoning deltas over the SSE bus; the store accumulates them and the view
renders them in full. The fix belongs in the view layer (make reasoning collapsed by
default, with an opt-in expand), not in the store or the stream.

This matches the ticket's premise: reasoning is shown expanded by default and should be
collapsed.

## Ticket premise check
The ticket proposes no concrete implementation, only the desired outcome ("should be
collapsed" / "shouldn't show by default"). The premise holds up: reasoning genuinely is
rendered expanded with no way to collapse it, and the right place to change that is the
`message-item` component. There is an existing, consistent pattern to reuse — the tool-row
expand/collapse mechanism already in the same component (`expanded` signal, `toggle(...)`,
`isExpanded(...)`, caret row). Reasoning should adopt the same collapsed-by-default row
treatment.

Note: the existing `isBlankReasoningText` / placeholder-"..." dropping in `part-utils.ts`
already removes empty reasoning stubs — that behaviour should be preserved; this change is
only about collapsing *non-empty* reasoning.

## Approaches considered

1. **Collapse reasoning by default with a clickable header row (recommended)** — Give
   reasoning the same collapsed-by-default row + caret treatment the tool parts already use
   in `message-item.component.html`/`.ts`/`.scss`. Default state: a muted one-line
   "Reasoning" (or "Thinking") header, collapsed; click to expand the markdown.
   - Pros: matches the ticket exactly; reuses the component's existing
     `expanded`/`toggle`/`isExpanded` machinery and visual language, so it's small and
     consistent; user can still read reasoning on demand; no store/stream changes.
   - Cons: need to decide the collapsed-while-streaming behaviour (a live reasoning block
     mid-turn) so it doesn't flicker or jump; minor.

2. **Hide reasoning entirely (drop it in `isRenderablePart`)** — Stop rendering reasoning
   parts at all.
   - Pros: simplest; removes clutter completely.
   - Cons: "shouldn't show *by default*" implies it should still be reachable; fully hiding
     removes a feature some users value and over-reads the request. Not recommended.

3. **A global "show reasoning" toggle/setting** — Add a user preference that hides or shows
   all reasoning.
   - Pros: most flexible; persists a per-user choice.
   - Cons: heavier (settings surface + persistence) than the ticket warrants; the ticket
     asks for a sensible default, not a settings framework. Over-engineered for a Minor.

4. **No code change** — Treat the current inline rendering as acceptable.
   - Pros: zero effort.
   - Cons: directly contradicts the ticket; the symptom (clutter) is real and reproducible
     in the current code. Not acceptable.

## Recommendation
PROCEED with **Approach 1**: make reasoning collapsed by default in
`message-item.component`, reusing the existing tool-row expand/collapse pattern (caret +
`expanded` signal + `toggle`/`isExpanded`), with a muted "Reasoning"/"Thinking" header and
the markdown revealed on click. Preserve the existing empty/placeholder-reasoning dropping
in `part-utils.ts`. Decide the streaming case (keep the live block visible or auto-collapse
once the turn completes) during implementation. Alternatives if the team prefers: fully
hide reasoning (Approach 2) or add a user setting (Approach 3) — both are viable but
respectively under- and over-shoot the request.

This is confined to the Angular subtree (`webui/`), so use the generic `read`/`write`/
`edit`/`grep`/`glob` tools and `npm`/`vitest`/`ng` — not the Eclipse JDT/PDE tools.

## Git history findings
The inline reasoning rendering was introduced in commit `b2147d6`
("SVY-21461 feat(opencode-chat): flowing chat transcript rendering [ai]", 2026-09-16), the
commit that replaced opencode's built-in web UI with the custom Angular chat transcript.
That commit deliberately made tool calls collapsible rows but rendered reasoning as a
plain muted inline block — reasoning collapse was simply never part of that first
iteration, not an intentional "always expanded" decision. So collapsing it now refines
that work rather than reverting a considered choice. No prior spec in `docs/` addresses
reasoning visibility. No dependency/version bump is involved; this is not a cross-version
regression.

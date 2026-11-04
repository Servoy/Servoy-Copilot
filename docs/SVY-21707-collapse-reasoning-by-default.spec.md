# Spec: SVY-21707 — Reasoning shouldn't show by default in Servoy AI

## 1. Goal
The Servoy AI chat UI currently renders the model's reasoning (thinking) text inline and fully
expanded in every assistant turn, cluttering the transcript and burying the actual answer. This
change makes reasoning **collapsed by default** — shown as a muted, one-line "Reasoning" header
row with a caret, expandable on click to reveal the full markdown — reusing the exact
expand/collapse pattern the tool rows already use in the same component. The data and streaming
are unchanged; only how `message-item` displays reasoning changes. Users can still read reasoning
on demand, but it no longer dominates the conversation by default.

## 2. Background

### 2.1 Where the behaviour lives
This is entirely a front-end concern in the standalone Angular chat UI under
`com.servoy.eclipse.opencode/webui/`. It is **not** an opencode-CLI, model, or config issue.
The webui is Angular 22, standalone components, zoneless change detection, signals, and
`ChangeDetectionStrategy.OnPush`; tests are **Vitest**. Eclipse JDT/PDE tools do not apply here —
use the generic `read`/`write`/`edit`/`grep`/`glob` file tools and `npm`/`vitest`/`ng` via the shell.

### 2.2 Current reasoning rendering (the problem)
`webui/src/app/components/message-item.component.html` (lines 21–24) renders every reasoning
part directly, always expanded, with no collapse control:

```html
} @else if (isReasoning(part)) {
  <div class="reasoning">
    <svy-markdown [content]="part.text ?? ''"></svy-markdown>
  </div>
}
```

`message-item.component.scss` (lines 116–122) only styles `.reasoning` as a muted, left-bordered
block; nothing collapses it.

### 2.3 The existing tool-row expand/collapse pattern (to reuse)
The same component already collapses tool parts and reveals their output on click. Reasoning
should adopt the identical machinery and visual language:

- **State** — `message-item.component.ts` holds `private readonly expanded = signal<Set<string>>(new Set())`,
  a set of expanded part keys. `partKey(part, index)` returns `part.id ?? '${part.type}-${index}'`.
- **`isExpanded(part, index)`** — `this.expanded().has(this.partKey(part, index))`.
- **`toggle(part, index)`** — adds/removes the key in a new `Set` and calls `this.expanded.set(next)`.
  Note the current `toggle` early-returns unless `canExpand(part)` (tool-specific); the reasoning
  toggle must not be gated by `canExpand` (see §3.1).
- **Markup** — a clickable `.tool-row` with a `.tool-caret` glyph (`▾` when open, `›` when
  collapsed) and a name; the expanded body is rendered only inside `@if (isExpanded(...))`.
- **Styling** — `.tool-row` (flex, pointer cursor via `.tool.expandable`), `.tool-caret` (hidden
  until hover/open, `opacity` transition), and `.tool-output-wrap` / indented-with-left-guide
  body. Reasoning gets an analogous caret row + indented body.

### 2.4 Reasoning data and streaming (unchanged)
`part-utils.ts`:
- `isReasoningPart(part)` (line 72) = `part.type === 'reasoning' && !isBlankReasoningText(part.text)`.
- `isBlankReasoningText` (line 59) drops empty/ellipsis-only (`"..."` / `"…"` / whitespace)
  placeholder reasoning stubs. **This dropping must be preserved** — the change only collapses
  *non-empty* reasoning.
- `isRenderablePart` (line 108/112) includes reasoning parts.

`chat-store.service.ts` streams reasoning over the SSE bus via `onReasoningStarted` /
`onReasoningDelta` / `onReasoningEnded` (lines 834–857), upserting a `reasoning` part (keyed by a
stable part id) and appending deltas. The store and stream are **not** touched by this change.

### 2.5 Git history
The inline, always-expanded reasoning rendering was introduced in commit `b2147d6`
("SVY-21461 feat(opencode-chat): flowing chat transcript rendering [ai]", 2026-09-16) — the commit
that replaced opencode's built-in web UI with the custom Angular transcript. That commit
deliberately made tool calls collapsible rows but rendered reasoning as a plain muted inline block;
reasoning collapse was simply never part of that first iteration, not an intentional
"always expanded" decision. Collapsing it now refines that work rather than reverting a considered
choice. No prior `docs/` spec addresses reasoning visibility, and no dependency/version bump is
involved — this is not a cross-version regression.

## 3. Design

### 3.1 Collapsed-by-default reasoning row (`message-item.component.html` + `.ts`)
Replace the always-expanded `.reasoning` block with a collapsible structure mirroring the tool row:

- A clickable header row — a muted caret glyph (`▾` open / `›` collapsed) plus a "Reasoning" label.
  Clicking it toggles the part's expanded state using the same `expanded` signal and `partKey`.
- The full `<svy-markdown [content]="...">` body is rendered only when the reasoning part is
  expanded (inside an `@if`), inside an indented, left-guide `.reasoning` body so it reads as
  nested under the header (consistent with the tool output's indented body).

Because reasoning parts have no `canExpand` concept (they are always expandable — there is always
text, since blanks are already dropped), the reasoning row must **not** go through the tool-only
`canExpand`-gated `toggle`. Add a dedicated, reasoning-specific toggle path that reuses the shared
`expanded` signal and `partKey` but is unconditionally allowed:

- Add `isReasoningExpanded(part, index): boolean` returning `this.expanded().has(this.partKey(part, index))`.
- Add `toggleReasoning(part, index): void` that flips the key in the `expanded` set
  (the same new-`Set` + `this.expanded.set(next)` body as `toggle`, but with no `canExpand` guard).

(Reusing the same `expanded` set is safe because keys are `part.id`-based and unique per part.)

Do **not** change `isReasoning(part)` / `isReasoningPart` — classification and blank-dropping stay
exactly as they are.

### 3.2 Streaming behaviour (explicit decision)
**Decision: keep the reasoning row visible but collapsed throughout — including while it streams —
and do not auto-expand or auto-collapse on any lifecycle transition.**

Rationale:
- The row is collapsed by default from the first `onReasoningStarted` upsert. As deltas arrive the
  header stays put (collapsed); nothing flickers or jumps because the markdown body is not in the
  DOM while collapsed. This is the simplest, most stable behaviour and matches how tool rows behave
  mid-run.
- The user's expand state is theirs: if the user expands reasoning mid-stream, it stays expanded as
  deltas continue to append (the body re-renders in place). If left collapsed, it stays collapsed
  when the turn completes. There is no auto-collapse-on-complete and no auto-expand-while-streaming —
  the component does not watch `completed()` for reasoning.
- No store/stream change is required to achieve this; the collapsed-by-default state is purely the
  initial value of the `expanded` set (empty), so a freshly-upserted reasoning part is collapsed
  with no extra code.

Edge case: the existing blank-reasoning dropping means a reasoning part that only ever contains
`"..."`/whitespace is never rendered at all, so no empty "Reasoning" header appears. A part that
starts blank and then streams real text will begin rendering (collapsed) as soon as its text is
non-blank, consistent with current `isReasoningPart` behaviour.

### 3.3 Styling (`message-item.component.scss`)
- Add a `.reasoning-row` (header) styled like `.tool-row`: flex, baseline-aligned, small muted
  font, pointer cursor, a caret that is hidden until hover/open (reuse the `.tool-caret`
  opacity/transition approach), and a "Reasoning" label in the muted color.
- Keep/repurpose `.reasoning` for the **expanded body**: muted color, the existing left-border
  guide and left padding, nested-indent spacing consistent with `.tool-output-wrap`
  (`padding-left` + `border-left` guide + small `margin-left`).
- The collapsed state shows only the one-line header row; the body appears on expand.

### 3.4 Label
Use **"Reasoning"** as the header label (not "Thinking") — the component already uses the muted
"thinking" dots animation (`.thinking`) for the *in-flight, nothing-yet* state (§2.4, html lines
71–74), so "Reasoning" for the content row avoids conflating the two concepts. (Open question OQ-1
leaves the final wording to confirm.)

## 4. Implementation plan

1. **`webui/src/app/components/message-item.component.html`** — replace the `@else if (isReasoning(part))`
   block (lines 21–24) with a collapsible structure: a clickable `.reasoning-row` header (caret +
   "Reasoning" label) bound to `toggleReasoning(part, $index)` and `isReasoningExpanded(part, $index)`,
   and the `<svy-markdown>` body wrapped in `@if (isReasoningExpanded(part, $index))` inside an
   indented `.reasoning` body. Caret glyph: `isReasoningExpanded(...) ? '▾' : '›'`.
2. **`webui/src/app/components/message-item.component.ts`** — add `isReasoningExpanded(part, index): boolean`
   and `toggleReasoning(part, index): void` reusing the shared `expanded` signal and `partKey`
   (no `canExpand` guard). Leave `isReasoning`, `toggle`, `isExpanded` unchanged. Update the
   class Javadoc that says reasoning is rendered "as a muted block" to note it is now a
   collapsed-by-default row.
3. **`webui/src/app/components/message-item.component.scss`** — add `.reasoning-row` (header, styled
   like `.tool-row` with a hover/open-revealed caret) and adjust `.reasoning` to be the indented,
   left-guide **expanded body**. Keep the muted palette and existing CSS variables.
4. **`part-utils.ts`** — no change. Confirm `isReasoningPart` / `isBlankReasoningText` are untouched
   (blank/placeholder reasoning is still dropped).
5. **`chat-store.service.ts`** — no change (streaming lifecycle untouched).
6. **Tests — `webui/src/app/components/message-item.component.spec.ts`** (Vitest):
   - Reasoning renders collapsed by default: a non-blank reasoning part shows the `.reasoning-row`
     header but **no** `<svy-markdown>` reasoning body in the DOM.
   - Clicking the reasoning header expands it: after `toggleReasoning(...)` (or a header click +
     `detectChanges()`), the reasoning body / markdown is present; the caret flips (`›` → `▾`).
   - Clicking again collapses it (body removed again).
   - `isReasoningExpanded` defaults to `false` for a reasoning part and reflects the toggle.
   - Blank/placeholder reasoning (`"..."`) still produces no reasoning row at all
     (`isReasoningPart` dropping preserved) — a reasoning part with only ellipsis is not in
     `renderable()` and renders no `.reasoning-row`.
   - The existing tests (renderable filtering, tool expand, attachments) continue to pass.
7. **Build check** — `npm test` (Vitest) from `webui/`, then a clean production build
   `npm run build` before committing (dev watch output must not be left in `webui-dist`).

## 5. Acceptance criteria
- [ ] Non-empty reasoning parts render **collapsed by default** as a muted one-line "Reasoning"
      header row with a caret; the reasoning markdown is not shown until the user expands it.
- [ ] Clicking the reasoning header expands it to reveal the full markdown; clicking again collapses
      it. The caret reflects the state (`▾` open / `›` collapsed).
- [ ] The expand/collapse reuses the existing `expanded` signal + `partKey` pattern from the tool
      rows (no separate per-part component state).
- [ ] Reasoning stays collapsed while it streams (unless the user expands it); there is no flicker,
      jump, auto-expand, or auto-collapse tied to turn completion.
- [ ] Empty/placeholder reasoning (`"..."`/whitespace-only) is still dropped and renders no header
      row (existing `isBlankReasoningText` / `isReasoningPart` behaviour preserved).
- [ ] No changes to `chat-store.service.ts` streaming or to `part-utils.ts` classification.
- [ ] `npm test` (Vitest) passes, including new tests covering the collapsed-by-default, expand,
      collapse, and blank-dropping cases.
- [ ] A clean `npm run build` succeeds.

## 6. Out of scope
- Hiding reasoning entirely / dropping it in `isRenderablePart` (triage Approach 2) — the ticket
  says "by default", implying reasoning must stay reachable.
- A global user "show reasoning" preference/setting with persistence (triage Approach 3) — heavier
  than a Minor warrants.
- Persisting the per-part expand state across reloads or sessions.
- Any change to tool-row rendering, text rendering, attachments, or the subagent link.
- Changes to the store, SSE event handling, or the opencode HTTP/BFF layer.
- Changes to the thinking-dots in-flight indicator (`.thinking`).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| OQ-1: Header label — "Reasoning" vs "Thinking". Spec picks "Reasoning" to avoid clashing with the existing thinking-dots animation; confirm preferred wording. | Implementer / reviewer | open |
| OQ-2: Should expand-on-hover-of-caret-only vs whole-row-click match the tool row exactly (whole row clickable)? Spec assumes whole-row click, matching `.tool-row`. | Implementer | open |

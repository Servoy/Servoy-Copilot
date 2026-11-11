# Spec: SVY-21764 — Always show the "Open subagent" link (no hover needed)

## 1. Goal

Make the **"↗ Open &lt;name&gt; subagent"** link in the chat transcript a
first-class, always-visible affordance. The moment a subagent tool part is
openable (its child session id resolves), the link must be rendered directly in
the link/accent color — discoverable at a glance, with no hover required to
reveal that it is clickable.

The subagent tool **row** and its **chevron** keep doing exactly what they do
today (toggle expand/collapse of the subagent's inline output envelope). This
change is scoped to the separate open link only.

## 2. Background

Opening a subagent from the transcript was added under **SVY-21461** (commit
`5070215`, "SVY-21461 open a subagent from the chat, and keep its link live").
That work introduced two distinct, co-located affordances in
`message-item.component.html`:

1. **The subagent tool row + chevron** (`.tool-row`, `.tool-caret`) — clicking it
   calls `toggle(...)` to expand/collapse the subagent's output. The chevron
   (`.tool-caret`) is hidden until hover/open (`opacity: 0` → `1`) and the row
   name (`.tool-name`) turns accent only on `.tool.expandable .tool-row:hover`.
   This hover-reveal is the behaviour visible in the ticket's screenshots: the
   `›` chevron appears and the label turns accent/orange on hover.

2. **The separate "↗ Open &lt;name&gt; subagent" link** (`.subagent-open`,
   rendered when `isSubagentLink(part)` is true) — clicking it calls
   `openSubagent(part)`, which emits the child session id on the `openSession`
   output so the shell opens that child session (the sidebar tree auto-expands
   and selects it, mirroring a click in the tree).

The reporter's complaint — that you cannot tell the subagent area is clickable
without hovering — stems from the hover-gated row affordance (1) dominating the
first impression. The open affordance (2) is the one that should always announce
itself. The approved fix makes the open link (2) an always-on, accent-colored
link so the jump-into-child-session action is obvious without hovering,
**without** touching the row's expand/collapse behaviour (1).

### Relevant files

- `webui/src/app/components/message-item.component.html` — the subagent open
  link lives at the `@if (isSubagentLink(part))` block (currently ~lines 62–77),
  nested inside the `.tool` element after the expand/collapse output block.
- `webui/src/app/components/message-item.component.scss` — the open link's
  styling is `.subagent-open-wrap` / `.subagent-open` / `.subagent-open-icon`
  (currently ~lines 251–283). The hover-gated row rules are `.tool-caret`
  (`opacity: 0`, revealed by `.tool.expandable:hover .tool-caret` /
  `.tool.expandable.open .tool-caret`) and `.tool.expandable .tool-row:hover
  .tool-name` (currently ~lines 243–298).
- `webui/src/app/components/message-item.component.ts` — `isSubagentLink`,
  `subagentLabel`, `openSubagent`; the `openSession` output.
- `webui/src/app/services/part-utils.ts` — `subagentSessionId(part)` resolver
  (`isSubagentLink` gates on it being non-null).

## 3. Design

The open link element itself is **not** gated behind the row's hover rules: it
is its own `.subagent-open` `<button>` already drawn in the accent color
(`color: var(--svy-accent, #f5a623)`), rendered unconditionally whenever
`isSubagentLink(part)` is true. The design goal is to **guarantee and make
unmistakable** that always-on, accent-colored presentation, independent of the
hover-gated `.tool-caret` / `.tool-name` row rules:

- The open link must have **no opacity gate and no `:hover`-only color change**
  that controls whether it reads as clickable. Its accent color and visibility
  must apply from first paint, the moment `isSubagentLink(part)` is true (i.e.
  `subagentSessionId(part)` resolves). The only hover/focus affordance permitted
  on the link is a secondary emphasis (e.g. `text-decoration: underline`) — it
  must never be the thing that first reveals clickability.
- The `.tool-caret` and `.tool-name` hover rules (the expand/collapse row cues)
  are **left exactly as they are** — they belong to affordance (1).
- The link color uses the same accent token used for links elsewhere in the UI:
  `var(--svy-accent, #f5a623)` (light/dark via the theme service). No new token
  is introduced.
- No change to `isSubagentLink`, `subagentSessionId`, `openSubagent`, the
  `openSession` output chain, or the sidebar auto-expand. The resolver logic
  stays as-is (the running-subagent match-by-child-session fallback from
  OpenChamber is explicitly out of scope — see §6).

The change is CSS/template-only within the message-item component.

## 4. Implementation plan

1. In `message-item.component.scss`, confirm `.subagent-open` renders in the
   accent color with full opacity and no transition that hides it before hover.
   Ensure there is **no** `opacity: 0` / hover-reveal rule applied to
   `.subagent-open` or `.subagent-open-wrap` (directly or inherited). Keep the
   `&:hover, &:focus-visible { text-decoration: underline; }` as a secondary cue
   only. If any part of the open link's clickability color/visibility is
   currently gated on `:hover`, remove that gate so the accent color and
   visibility apply by default.
2. In `message-item.component.html`, confirm the `@if (isSubagentLink(part))`
   open-link block is outside any element whose hover state controls its
   visibility, so it is always visible when `isSubagentLink(part)` is true. No
   change to the surrounding `.tool-row` / expand-collapse markup.
3. Leave `.tool-caret`, `.tool.expandable .tool-row:hover .tool-name`, and the
   `toggle(...)` row behaviour untouched.
4. Add/adjust a Vitest unit test in
   `webui/src/app/components/message-item.component.spec.ts` asserting that when
   a subagent part with a resolvable child session id is rendered, the
   `.subagent-open` link is present and (per computed style / class) not
   hover-gated — i.e. it is visible without a hover event. If a computed-style
   assertion is impractical in the Vitest/JSDOM setup, assert the link element
   exists and has no opacity-0 / hidden state in its initial render.
5. Build the frontend (`ng build` from `webui/`) and run `npm test` (Vitest)
   from `webui/`. For a visual check, run the dev watch build
   (`ng build --watch --configuration development`) and verify in the embedded
   browser that the "↗ Open &lt;name&gt; subagent" link is accent-colored and
   obviously clickable on first paint (before any hover) for both a running and
   a completed subagent, in both light and dark themes.

## 5. Acceptance criteria

- When a subagent tool part is rendered and its child session id resolves
  (`subagentSessionId(part) !== null`, i.e. `isSubagentLink(part)` is true), the
  "↗ Open &lt;name&gt; subagent" link is **visible and rendered in the
  link/accent color without any hover** — it announces itself as clickable from
  first paint.
- Clicking the link still opens the child session (emits `openSession` with the
  child id; sidebar auto-expands and selects it) — unchanged.
- The subagent tool **row** and its **chevron** still toggle expand/collapse of
  the subagent's inline output, with their existing hover-reveal caret/name
  behaviour **unchanged**.
- The whole row is **not** the open target; only the dedicated link opens the
  child session.
- The accent color matches the existing accent token (`--svy-accent`) and works
  in both light and dark themes.
- Frontend builds cleanly; Vitest passes, including the new/updated assertion for
  the always-visible open link.

## 6. Out of scope

- Making the whole subagent tool row the open target (the row already toggles
  expand/collapse and must keep doing so).
- Porting OpenChamber's running-subagent match-by-child-session resolver
  (`resolveRunningTaskChildSessionId`) — the `subagentSessionId` resolver stays
  exactly as-is. When no id resolves, no link is shown (current behaviour).
- The ticket's second, open-ended clause (OpenChamber feature gap analysis /
  follow-up cases) — not part of this change.
- Any change to the expand/collapse chevron (`.tool-caret`) or tool-name
  hover-reveal rules.

## 7. Open questions

- In the current `master` source, `.subagent-open` is already rendered
  unconditionally and already styled in the accent color
  (`var(--svy-accent, #f5a623)`), with only `text-decoration: underline` added on
  hover/focus — i.e. the open link element itself does **not** appear
  hover-gated; the hover-reveal in the ticket screenshots is the tool **row**
  caret/name behaviour. If that is accurate, the concrete code change may reduce
  to (a) a guard test locking the always-visible/accent presentation and (b) any
  minor visual tightening so the link is unmistakably a link (e.g. persistent
  underline or icon emphasis). The spec is written so that the acceptance
  criteria hold regardless; the implementer should confirm against the live
  rendering whether any gate remains to remove or whether only the guard test +
  optional emphasis is needed. (Assumption: the approved intent — "always visible
  in accent, no hover to discover" — governs; match it exactly in the final CSS.)
- Should the link gain a persistent (non-hover) underline to further distinguish
  it from accent-colored non-link text? Assumed **no** by default (accent color
  is the link cue, underline stays a hover/focus emphasis) unless the reviewer
  prefers a persistent underline.

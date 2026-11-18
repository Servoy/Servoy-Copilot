# SVY-21632 Review Summary

**Risk: MODERATE.** The `tone` feature itself is additive and low risk. The side changes alter how untoned badges look in existing solutions: the BUTTON bubble goes from #212529 to #777777, and the link rules lose `!important` and specificity. The commit messages say "no badge changes appearance", which isn't accurate.

Scope: bootstrapextracomponents `0a935d2` + `7132c09` (local, unpushed); skill4servoy `a1aef8d` + `3625255` (on origin/master). Fix Version 2026.12, branch `master` (release-target check not confirmed against the Jira version list).

Outcome: no blocking finding. The issue was resolved (Fixed) after the review, and the package code was left as the author wrote it.

## Why the risk is MODERATE

The new `tone` / `toneStyle` properties on the Badge and Progress Bar are additive and unset by default, so they carry little risk. The rating comes from side changes in the same commits that alter how existing badges look when `tone` is not set. Both were measured in headless Chromium with Bootstrap 5.3.6, on a reduced test page and not a real Servoy theme.

**1. Bubble colour.** The bubble moved to one shared grey: the `bg-dark` class is gone and `.bts-extra-badge .label-badge-class` now applies in every form type.

| Case | Before | After |
|---|---|---|
| BUTTON, absolute form | #212529 | #777777 |
| BUTTON, responsive form | #212529 | #777777 |
| LABEL, absolute form | #777777 | #777777 (unchanged) |
| LABEL, responsive form | #212529 | #777777 |

Solution CSS that targets `.bg-dark` inside a badge no longer matches.

**2. Link look of a LABEL badge.** The package's `!important` on the link colour and underline was removed, and `:where()` lowers the specificity further. In an absolute form, a LABEL badge with a colour class now shows its own colour. For example `.my-magenta` and `text-danger` used to be forced to #337ab7. This follows from the ticket's goal of needing no `!important`. Restoring the old look for a plain class would mean removing `:where()`, which brings back the absolute/responsive difference, and Bootstrap `!important` utilities such as `text-danger` can't be beaten without `!important`.

**Why not higher:** the effects are visual only. The old colours are kept as CSS fallbacks, and the `--svy-badge-bubble-*` variables restore a look without `!important`.

**Why not lower:** the change reaches solutions that never set a tone, and the commit messages say "no badge changes appearance", which isn't accurate for the two points above.

**Open for the author:**
- Is the single-grey bubble intended for existing solutions, and does it need a release note?
- The `0a935d2` commit message still gives the SVY-21631 root cause that was later retracted. The commits are unpushed, so it can still be reworded.
- The legacy `badge/badge.html` hides a numeric `0` bubble (`ng-if="model.badgeText"`), while the Angular layer shows it.

## Manual test plan

**Verifying the fix**
1. In an absolute form and separately a responsive form, place a Badge (LABEL) and a Badge (BUTTON) with `tone` = success, info, warning, danger, neutral. Check the soft colours, then set `toneStyle` = `solid` and check the fill and contrast text.
2. Redefine `--svy-success-soft-bg` and `--svy-success-soft-fg` in the solution `.less` without `!important`. Confirm the badge picks them up.
3. Set `badgeText` to `""`, unset, `"0"` and `0`. Only `"0"` and `0` should render the bubble. No empty bubble on a toned badge.
4. Place a Progress Bar with each `tone`. The track uses `-soft-bg` and the fill uses `-solid`, with no `text-bg-*` class on the fill. With only `type` set (no tone), the old behaviour is unchanged.
5. Set a custom tone such as `attention`, with and without solution-defined variables. Confirm what renders and that the skill guidance matches.

**Regression checks**
1. Existing BUTTON badge with `badgeText` and no tone: compare the bubble colour before and after (now #777777 instead of #212529). Look for solution CSS using `.bg-dark` or `.label-badge-class`.
2. Existing LABEL badge with a colour `styleClass` (for example `text-danger`) in an absolute form: compare link colour, hover underline and focus before and after, in the real solution theme.
3. LABEL badge with no `styleClass`: the link colour should be the same in absolute and responsive forms.
4. Toned BUTTON badge hover: the tone rules tie with Bootstrap `.btn:hover` on specificity, so check the result.
5. Legacy AngularJS runtime, if still supported: repeat the `badgeText` cases (legacy hides numeric `0`) and the progress bar with and without tone.

Automated checks (in `bootstrapextracomponents/bootstrapextracomponents`): `npm run build`, `npx ng lint`, `npm run test`. Not run by the review.

## Possible improvements / follow-ups
- Bubble colour: measured in headless Chromium (reduced page, Bootstrap 5.3.6). Untoned BUTTON bubbles (both form types) and LABEL bubbles in a responsive form move from #212529 to #777777; a LABEL bubble in an absolute form stays #777777. This is the author's deliberate single-grey choice, so the code was left as he wrote it. Worth a release note.
- Link look: a LABEL badge with a colour `styleClass` now shows its own colour in an absolute form (it used to be forced to #337ab7). This follows from the "no `!important`" goal. Restoring the old look for a plain class would mean removing `:where()` (reintroducing the absolute/responsive difference), and Bootstrap `!important` utilities such as `text-danger` can't be beaten without `!important`. The author's code was kept.
- Align the legacy `ng-if="model.badgeText"` (hides numeric `0`) with the Angular `showBadgeText` (shows `0`).
- Document that a custom `tone` name needs the solution to define its own variables and class rules. The package only styles the five core tones.
- Reword the `0a935d2` commit message before pushing. It still gives the SVY-21631 root cause the author later retracted.
- Optional hardening: restrict the `tone` class fragment to `[a-z][a-z0-9_-]*`.
- The Jira comments cite skill commit `1555730`, which doesn't exist locally. The skill commits are `a1aef8d` and `3625255`.

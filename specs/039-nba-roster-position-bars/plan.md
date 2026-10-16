# Implementation Plan: Basketball roster bars filled by position

**Branch**: `039-roster-position-bars` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

## Summary

The segments exist and are sized correctly; only their colour rules are missing for basketball. Add the five basketball rules to both football-only lists in `web/src/styles.css` (bar segments, rank-matrix cells), give unknown groups a hatched fallback, and add a test that walks `POSITIONS_BY_SPORT` (`web/src/positions.ts:17`, the frontend's one per-sport position list) and fails if either list is missing a position.

## Technical Context

- Frontend only: `web/src/styles.css`, one new test file. No backend, no `api.ts` type, no migration.
- Tokens already exist: `--pg/--sg/--sf/--pf/--c` (`styles.css:179-181`), same ones the `.pos.PG` pills use (`styles.css:818`), so pill and segment match by construction (SC-002).
- One theme (dark). Track is `--line` (`oklch(28% …)`); every position token is L 58–68%, so all clear the track.
- Test precedent for reading the stylesheet directly: `ChangeDumbbell.test.tsx:56`, `DraftRoomLayout.test.tsx:168` (vitest stubs CSS imports).

## Constitution Check

No migration, no `api.ts`/Java record, no hand-set constant, no `Map.of`, no commit without asking, no roadmap-difficulty claim relied on. Passes.

## Design

1. After `.analysis-seg.pos-DEF` add `.analysis-seg.pos-PG … pos-C { background: var(--pg) … }`.
2. After `.analysis-cell.pos-DEF` add the five `color-mix(in oklch, var(--x) var(--tint, 0%), var(--panel))` rules.
3. Fallback on the base `.analysis-seg` rule: a diagonal hatch of `--muted` over `--line`. The position rules have higher specificity (two classes vs one) and set `background`, which overrides the hatch. The matrix needs no fallback: its base rule already renders an untinted panel and the rank is printed.
4. `web/src/positionColours.test.ts`: for every position in every sport in `POSITIONS_BY_SPORT`, assert `styles.css` has a `.analysis-seg.pos-<P> {` rule referencing `var(--<p lowercased>)`, and the same for `.analysis-cell.pos-<P>`. DEF→`--def`, C→`--c`: the lowercase mapping holds for all eleven.

## Not being built

- Collapsing the several per-position CSS lists (board cells, pills, chips, segments, cells) into one `--pos-colour` custom property per `.pos-X`. That is the structural fix to the bug class, but it touches the draft board and pills, which work today; the guard test covers the two lists this page owns. Worth a follow-up, not this change.

## Adversarial plan review *(inline pass, same session — stated plainly: not a separate agent)*

Read the plan cold against the code; findings and what they changed:

- **R1 — fallback colliding with K.** A solid `--muted` (`oklch(63% 0.02 250)`) is within 2 L of `--k` (`oklch(65% 0.02 250)`). An unknown group on a football bar would read as kicker. → Fallback changed to a hatch. Spec edge case amended.
- **R2 — "light and dark themes" in the spec.** There is no light theme. → Spec amended visibly.
- **R3 — specificity.** Confirmed `.analysis-seg.pos-X` (0,2,0) beats `.analysis-seg` (0,1,0), and both use the `background` shorthand, so a position rule fully replaces the hatch image. To be checked by computed style in live verification, not assumed.
- **R4 — does a test that greps CSS actually catch the regression?** Only if it iterates the real position list, not a copy. → It imports `POSITIONS_BY_SPORT`; SC-004 is checked by deleting a rule and watching it fail.
- **R5 — `--c` is red-ish (hue 359) and crimson means "mine".** Pre-existing choice already used on pills and the draft board; the "mine" marker on this page is an inset left edge, not a fill. Not changed.
- **R6 — is the NBA payload's `positionGroups` really PG/SG/SF/PF/C?** Screenshot shows those pills, and the pills render from the same `positionGroups` list. Live verification reads the actual payload.

## Verification (acceptance)

- `npx tsc -b`, `npx vitest run` green; the new test fails with one rule removed (SC-004).
- Live: Team strength for the NBA league — every bar's segments have a non-transparent computed background equal to the matching pill's (SC-001/002); matrix cells tinted. NFL league page: computed colours unchanged (SC-003).

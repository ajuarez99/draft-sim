# Implementation Plan: Custom stat calculator with a ranked chart

**Branch**: `032-stat-calculator` (worktree `.claude/worktrees/032-stat-calculator`, from `origin/main` e9f71aa) | **Date**: 2026-10-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/032-stat-calculator/spec.md`

## Summary

This adds a page at `/leagues/:id/stats/calculator`. A member types a formula over the
leaderboard's existing stat columns, for example `(USG + VOR) ÷ TO`, and sees a ranked bar chart
and a table of every qualifying player.

The page reads spec 022's existing leaderboard payload unchanged and evaluates the formula
client-side. The formula's inputs come from the leaderboard's own column definitions, so one
definition serves both pages.

**This is frontend-only.** There is no endpoint, no Java change, no migration and no `api.ts`
type change. New code:

- a small parser and evaluator (`statFormula.ts`);
- the page itself;
- hand-rolled bar and scatter charts;
- URL state;
- a `localStorage` list of saved formulas.

The leaderboard page's file-local controls are extracted so the two pages share them.

## Technical Context

**Language/Version**: TypeScript and React 18 (Vite). No backend work.

**Primary Dependencies**: existing only:

- `statLeaderboard.ts`: `COLUMNS`, `PICKABLE`, `compareRows`, `applyFilters`, `Filters`;
- `statCopy.ts`: the season label, reason sentences and qualification rule;
- `getStatLeaderboard`, `useBlock`, `useLeagueDataVersion` and `PlayerLink`;
- `react-router-dom`'s `useSearchParams`.

No charting library is added (research R9).

**Storage**: browser `localStorage` only (`bk.statCalc.saved.v1`, contract C3). No schema.

**Testing**: Vitest and Testing Library for the grammar, evaluator, z-scores, URL round trip,
storage and the page. Then live verification in a real browser against a real backend, per
`quickstart.md`.

**Target Platform**: the web app, desktop and 375 px phone.

**Project Type**: web application. Only `web/` changes.

**Performance Goals**: SC-006, which is under 1 second to re-render after a season, window or
mode change. Mode and formula changes don't make a request: evaluating ~582 rows is client-side
and trivial. A window change is one leaderboard request, the same cost the leaderboard pays
today.

**Constraints**:

- Values must be exactly the payload's values (FR-005). Evaluation reads `COLUMNS[id].value`,
  and nothing is re-derived.
- The result type makes "0 standing in for undefined" unrepresentable (research R5).
- The page can't scroll sideways at 375 px (FR-014).
- The leaderboard's behaviour is unchanged by the extraction (R13).

**Scale/Scope**: about 582 rows per season, 5 presets, at most 50 saved formulas, 1 new route,
about 6 new source files and 1 refactored page.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Rule (AGENTS.md "Hard rules") | Status |
|---|---|
| Schema migrations append-only | **N/A**: no migration |
| `api.ts` mirrors Java records field-for-field | **N/A**: no record or type changes. The calculator reads existing `LeaderboardRow` fields |
| Never retune a hand-set constant to match a guess | **Pass**: the new hand-set values (top N 15, the 50-entry cap, the unit-class map, the R7 division warning) are labelled as hand-set in the research and assumptions, and none is tuned to a measured target |
| `Map.of` with nulls in a JSON path | **N/A**: no backend response is built |
| Ask before committing | **Pass**: nothing is committed by this command, and the branch is a fresh worktree |
| Planning doc ≠ verified spec | **Pass**: the code was read before planning. That turned up ~~the duplicate `FG%`/`FT%` labels~~ (wrong, per review F5), FGA/FTA not being columns, VOR being null when unqualified, and `destinations.test.ts`'s route rule. Each one amended the spec or the plan (research R2, R11, R5, R12) |

**Post-design re-check**: unchanged. The design added no backend surface.

## Project Structure

### Documentation (this feature)

```text
specs/032-stat-calculator/
├── spec.md
├── plan.md                         # this file
├── research.md                     # R1–R14
├── data-model.md
├── quickstart.md                   # live verification recipe
├── contracts/
│   ├── formula-grammar.md          # C1
│   ├── url-state.md                # C2
│   └── saved-formulas-storage.md   # C3
├── checklists/requirements.md
└── tasks.md                        # /speckit-tasks, not yet created
```

### Source Code

```text
web/src/
├── statFormula.ts            # NEW: lexer, parser, canonical/readable printers, unit inference, evaluator, z-scores
├── statFormula.test.ts       # NEW: the C1 error table, lexing traps, precedence, reasons, an ordering test
├── statCalcUrl.ts            # NEW: C2 read and write of search params
├── statCalcUrl.test.ts
├── statCalcSaved.ts          # NEW: C3, modelled on statChoice.ts
├── statCalcSaved.test.ts
├── statCalcPresets.ts        # NEW: the five presets (research R11)
├── statLeaderboard.ts        # EDIT: unit-class map and formula tokens next to COLUMNS; export the compareRows tiebreak tail; extract boardContext() (F7)
├── statCells.tsx             # EDIT: extract missingReason(); Cell uses it (R15, F3)
├── statCopy.ts               # EDIT: scoringSentence() extracted from draftRoomStats.ts (F8)
├── draftRoomStats.ts         # EDIT: use statCopy.scoringSentence
├── components/LeagueRailSection.tsx  # EDIT: switchTarget keeps /calculator + search for `stats` (F7)
├── destinations.ts           # EDIT: widen the `stats` match to include /calculator (R12)
├── App.tsx                   # EDIT: the calculator route
├── components/
│   ├── statControls.tsx      # NEW (extracted): SeasonPicker(suffix), FilterBar, Segmented, PlayerCell, WINDOWS, MODES
│   ├── RankedBars.tsx        # NEW: horizontal bars, zero baseline, exact values
│   └── StatScatter.tsx       # NEW (US4, P3): SVG scatter
└── pages/
    ├── StatCalculator.tsx    # NEW
    ├── StatCalculator.test.tsx
    └── StatLeaderboard.tsx   # EDIT: import the extracted controls and boardContext; add the "Leaderboard · Calculator" nav (F13)
```

**Structure Decision**: `web/` only. The pure logic goes in flat `web/src/*.ts` modules next to
`statLeaderboard.ts`, `statChoice.ts` and `statCopy.ts`, following spec 022 and 023's layout.
That keeps the rules testable without rendering.

## Build order

1. **US1** (P1): extract the shared controls first, and confirm the leaderboard tests are still
   green. Then `statFormula.ts` and its tests, then `RankedBars`, then the page and route, then
   URL state. US1 requires URL state, because the season switch keeps the query string.
2. **US2** (P2): presets. Small.
3. **US3** (P2): the copy-link button (URL state already exists) and the saved list.
4. **US4** (P3): the scatter. It can be dropped without affecting the rest.

Per AGENTS.md, an adversarial review of this plan comes before `/speckit-tasks`. The two
questions it should answer are at the end of research.md.

## Amended after plan review (2026-10-12)

`plan-review.md` found 13 issues (F1–F13) and 10 notes. All were applied to spec.md, research.md
(R15 and R16 added, plus inline amendments to R2–R14), data-model.md, the three contracts and
quickstart.md. Each change is marked inline. Corrections to this plan's own claims:

- **Constitution-check row "Planning doc ≠ verified spec"**: the "duplicate `FG%`/`FT%` labels"
  discovery it credited was wrong. `PICKABLE` already drops them (F5). The other discoveries
  stand.
- **"VOR being null when unqualified" was half right**: FP/G is not null there, and VOR can be
  null for a qualified player (F3).
- **The structure list** gains `statCells.tsx`, `statCopy.ts`, `draftRoomStats.ts` and
  `LeagueRailSection.tsx` edits, so the leaderboard, draft room and calculator share one
  missing-value rule, one scoring sentence and one fallback context, rather than three copies.
- **Risk to existing pages**: R15 changes `Cell`'s last fallback, so the leaderboard may show a
  more accurate reason where it said "not qualified" before. That is a visible change outside this
  feature, to be listed in the build notes and checked in the live pass.

## Amendments this plan made to the spec

These are recorded in the spec as "amended at plan" notes rather than silently rewritten:

- **SC-002**: the hand-check runs against the payload's values. A check against displayed values
  can legitimately disagree on ratios because of rounding (R4).
- **US2's example preset**: "points per shot attempt" becomes "Efficient volume", because FGA
  and FTA aren't leaderboard columns (R11).
- **FR-015**: filters, qualification and standardization also go in the link. SC-004's "identical
  chart" requires them (R10).
- **Qualification**: on by default for every formula, unlike the leaderboard's per-column rule
  (R6).

## Complexity Tracking

No constitution violations, so this section is empty.

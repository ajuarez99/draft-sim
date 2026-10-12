# Implementation Plan: Schedule swap matrix

**Branch**: `031-schedule-swap` (worktree `.claude/worktrees/031-schedule-swap`, off `origin/main`
`d821e0e`) | **Date**: 2026-10-12 | **Spec**: [spec.md](spec.md)

## Summary

A pure `scheduleSwap(List<Game>)` beside `allPlay`/`median` in `ExpectedWinsService`. It reads
the `Game` list that service already builds for the regular season's final weeks, and it adds a
nullable `scheduleSwap` field to the existing `/api/leagues/{id}/expected-wins` response. The
Expected Wins page gets a section with an N×N W-L grid, a luck column and a difficulty row. No
migration, no new route, no new read.

## Amended after review (2026-10-12)

[plan-review.md](plan-review.md) found two high, three medium and six low findings, plus notes.
The contract below is amended in place, and the sections it replaced are kept as a record.

| # | Finding | Disposition |
|---|---|---|
| F1 | "Schedule luck" is exactly (N−1)/N × the existing wins-above-expected with no byes (error ≤ 3.6e-15, 7 leagues). It's not a second measure | **Dropped the luck column.** The matrix's value is the per-cell readout ("your record on their schedule"). The identity becomes a test (the mean over the other N−1 schedules = winsAboveExpected, no byes). The page says the row's spread is the existing Luck number seen schedule by schedule. The design doc gets an amended note |
| F2 | `ExpectedWinsController.body()` builds JSON by hand; `LeagueAnalyticsContractTest` constructs `Result` positionally | **Fixed.** Both are added to the file list. The controller emits `scheduleSwap`, and the tests are updated mechanically |
| F3 | Difficulty and the existing Schedule column disagree, in opposite directions | **Fixed.** Difficulty is shown as the **average win rate on that schedule** (higher = easier). A sentence says how it differs from Schedule (opponents' average points, which ignores your own score each week). Ties: every tied team is named |
| F4 | NBA 2025 diagonal differs from Sleeper's record for 4 rosters (two stored games scored the other way) | **Out of scope, recorded.** The existing Luck page already shows the same mismatch. It's a follow-up to diagnose against Sleeper's matchups, not hidden |
| F5 | Invented "< 2 weeks" rule | **Fixed.** Reuse the page's `early` (< 4 weeks). While early, name no toughest or easiest schedule and keep a "so far" label (N2) |
| F6 | Diagonal vs a single number; duplicate roster-week | **Fixed.** Assert the full W-L-T against the real records. A roster twice in one week throws (0 cases today) |
| F7 | Invariant 3 wrong | **Fixed.** With no byes, the win rates average exactly 0.5. Asserted |
| F8 | Byes make column means incomparable | **Fixed** by F3: win **rate** = (W + ½T)/games, comparable with byes |
| F9 | `weeks` duplicates `weeksScored` | **Removed** |
| F10 | TS optional field; join by rosterId | **Fixed.** `scheduleSwap?: ScheduleSwap \| null`, and rows are joined by `rosterId` |
| F11 | Design doc's acceptance 2 and "second luck measure" | **Amended** visibly in `claude/schedule-swap.md` |

Notes taken: N1 (the citation is dropped, and the rule is justified by the exact diagonal and
F1's identity), N2 ("so far" in season), N3 (one named assumption per league, skip count read),
N4 (cost negligible), N5 (opaque sticky column in both themes), N6 (rows and columns in the
teams order).

**Amended contract:**

```java
public record ScheduleSwap(List<Integer> rosterIds,              // teams order
                           List<List<WinLossTie>> cells,          // cells.get(row).get(col)
                           List<Double> difficultyWinRate) {}     // per column: mean over rows of (W+½T)/games, 3 dp
// Result.scheduleSwap: ScheduleSwap, null when unavailable or < 2 rosters
```

The hand-worked 4×3 fixture in plan-review.md is the unit-test fixture.

## Research (measured 2026-10-12, local DB)

- **R1. Shape.** Regular-season `league_matchup` rows (week < `playoff_week_start`):
  - (Foot) Ball Knowers 2025: 14 weeks, 12 rosters, 0 byes;
  - West Coast FF 2025: 14 / 12 / 0;
  - NBA 2025: 18 / 12 / 0;
  - NBA 2024: 21 / 12 / 0.

  So today no cell has missing weeks. The bye rules (spec scenarios 3–4) are written down and
  tested anyway, because a league with an odd roster count would have byes.
- **R2. Reuse.**
  - `ExpectedWinsService.Game(week, aRosterId, aPoints, bRosterId, bPoints)` (`:63`) carries
    everything needed.
  - `scoresByWeek(games)` (`:255`) gives each roster's weekly score.
  - `actualWins(games)` (`:172`) is the real record. The diagonal must equal it, and the test
    asserts that.
  - `WinLossTie` (`:75`) is the cell type.
  - The regular-season bound and final weeks are already applied to `games` before the pure
    functions run, so the matrix inherits "regular season only" (design doc) without restating
    it.
- **R3. The self-pairing rule (reasoned).** It follows the common "schedule swap" convention
  (StatsGuy and ESPN-style write-ups), and the design doc names it. Otherwise T would face
  itself. With it, the diagonal is exact: on its own schedule, T never meets itself.

## Contract (added to `ExpectedWinsService.Result`)

```java
public record ScheduleSwap(List<Integer> rosterIds,          // row and column order (same order as teams)
                           List<List<WinLossTie>> cells,      // cells.get(row).get(col)
                           List<Double> luck,                 // per row: actualWins − mean over columns
                           List<Double> difficulty,           // per column: mean wins over rows (ties = ½)
                           int weeks) {}                      // regular-season weeks in the games list
// Result gains: ScheduleSwap scheduleSwap   (null when unavailable or fewer than 2 rosters)
```

TS mirror in `web/src/api.ts`: `ScheduleSwap` and `scheduleSwap: ScheduleSwap | null` on the
expected-wins type, field for field. Luck and difficulty are rounded to 2 dp in the payload.

Invariants:
1. `cells[i][i]` equals the real record (`actualWins`) for every i.
2. For every cell, W+L+T = the column roster's game count, minus weeks the row roster had no score.
3. Mean luck ≈ 0 isn't an invariant, so don't assert it.

## Constitution Check (AGENTS.md gates)

| Gate | Status |
|---|---|
| Honesty | ✅ Each cell has its exact W-L. Luck and difficulty are labelled with their sign. The rule is printed |
| Two implementations of one rule | ✅ Same `Game` list, same `scoresByWeek`, same tie rule as `actualWins`. The diagonal is tested against `actualWins` |
| Optional params that encode rules | ✅ The self-pairing rule is explicit and named in a test |
| Ordering/scoring tests | ✅ A hand-worked 4×3 test and the diagonal IT |
| `api.ts` mirrors records | ✅ Same change |
| Migrations | ✅ None |
| Concurrent sessions | ✅ Own worktree. Ask before committing |

## Files

```text
backend/src/main/java/com/ballknowers/draftsim/engine/ExpectedWinsService.java   # ScheduleSwap record + scheduleSwap() + wire into compute()
backend/src/test/java/com/ballknowers/draftsim/engine/ExpectedWinsServiceTest.java  # (existing class) hand-worked 4×3, self-pairing, byes
backend/src/test/java/com/ballknowers/draftsim/engine/ScheduleSwapIT.java          # diagonal = real record on all completed seasons
backend/src/main/java/com/ballknowers/draftsim/api/ExpectedWinsController.java     # emit scheduleSwap in body() (F2)
backend/src/test/java/.../LeagueAnalyticsContractTest.java                        # positional Result constructors (F2)
web/src/api.ts, web/src/pages/ExpectedWins.tsx (+ test), web/src/styles.css
```

## Build order

1. Tests first: the 4×3 worked example, self-pairing, the column-team bye, the row-team missing week.
2. The pure function, then wire it into `compute()`, then the IT.
3. Web section + Vitest.
4. Live: the page for NFL 2025 and NBA 2025 at desktop and 375px. Then the review and verification.

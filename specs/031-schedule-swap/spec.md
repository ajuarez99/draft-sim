# Feature Specification: Schedule swap matrix

**Feature Branch**: `031-schedule-swap`

**Created**: 2026-10-12

**Status**: Draft, amended after review 2026-10-12 ([plan-review.md](plan-review.md); dispositions in plan.md). Schedule luck dropped (F1), difficulty shown as a win rate (F3, F8), the page's existing `early` rule reused (F5).

**Input**: "keep going". This is roadmap Phase 2 item **2.6**
([claude/schedule-swap.md](../../claude/schedule-swap.md)), size S. It's the last small item
in Phase 2.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - My record under everyone else's schedule (Priority: P1)

On the Expected Wins page, a manager sees an N×N grid. Row = team, column = whose schedule.
Each cell is that team's W-L(-T) if it had played the column team's opponents week by week,
with its own weekly scores. The diagonal is the real record. *(Amended after review F1/F3.)* Each column shows the **average win rate on that schedule**,
how every team would have fared on it (higher = easier). There's no separate luck column: a row's
average over the other schedules is exactly the team's expected wins (review F1).

**Why this priority**: it's the whole feature. It's the question "was I unlucky, or just
bad?" asked in a way every manager in the league can check against their own week-by-week
scores.

**Independent Test**: on "(Foot) Ball Knowers" 2025, the diagonal equals each team's record
from the stored weekly scores, and each cell's W+L+T is 14 (no byes that season).

**Acceptance Scenarios**:

1. **Given** a completed season, **When** the matrix is built, **Then** cell (T, T) equals T's
   real regular-season record, for every team.
2. **Given** S's opponent in week W is T itself, **When** T takes S's schedule, **Then** T
   plays S that week (T's score vs S's score). The page states this rule under the grid.
3. **Given** a week where the column team had no game (a bye), **When** counted, **Then** that
   week isn't played in that cell. W+L+T = the column team's games.
4. **Given** a week where the row team had no score, **When** counted, **Then** it's skipped
   for that row and doesn't count as a loss.
5. **Given** the page's `early` flag (< 4 scored weeks), **When** opened, **Then** the grid shows,
   labelled "so far", and no easiest or toughest schedule is named (review F5).

### Edge Cases

- Equal scores are a tie, the same as the real record (`Double.compare`, as `actualWins` does).
- A roster that never played: no row and no column.
- Basketball: one matchup per week, so it works unchanged.

> **Amended after code review (B1), 2026-10-12, measured:** for NBA 2025, Sleeper's **current**
> `/matchups/{week}` points differ from the stored `starters_points` for 6–12 rosters in every week
> 1–20, and 14 matchups now come out the other way. The stored scores matched Sleeper's season
> totals when checked 2026-09-19, and they still agree with Sleeper's final standings for 8 of 12
> rosters. So Sleeper appears to have recomputed past matchups. The diagonal is labelled as the
> record from the stored weekly scores, which every number on the page shares, not as Sleeper's
> standings. Diagnosing the drift is a named follow-up, out of this spec.

## Requirements *(mandatory)*

- **FR-001**: A pure function `scheduleSwap(List<Game>)` over the **same** `Game` list the page
  already uses (the regular season, final weeks). There's no second read and no second
  regular-season rule.
- **FR-002**: The self-pairing rule (scenario 2) is written as code with a named test, and stated
  on the page. It's not a silent default.
- **FR-003** *(amended after review F1/F3/F8)*: Difficulty = the column's mean **win rate**,
  (W + ½T)/games, over all rows (comparable with byes), labelled "higher = easier". There's no
  schedule-luck column.
- **FR-004**: Each cell prints the exact W-L(-T). Colour only repeats it (the label-the-axis
  rule). The diagonal is marked "actual".
- **FR-005**: The response is records. `web/src/api.ts` is mirrored in the same change.
- **FR-006**: At phone width the grid scrolls inside its card, with a sticky first column, and
  there's no page scroll.

## Success Criteria *(mandatory)*

- **SC-001**: The diagonal equals the record from the stored weekly scores (full W-L-T) for every completed ingested season (an IT
  over NFL 2025 ×2, NBA 2024, NBA 2025).
- **SC-002**: A hand-worked 4-team, 3-week test passes.
- **SC-003**: Live on the real page for NFL 2025 "(Foot) Ball Knowers".

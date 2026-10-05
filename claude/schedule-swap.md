# Schedule swap: your record under everyone else's schedule

Status: **design, not built.** 2026-10-05. Gap #4 in
[competitor-gap-research.md](competitor-gap-research.md).

Competitor: StatsGuy "Schedule Matrix", which replays records against all
opponent schedules, with luck and strength of schedule.

## What's there now

- `ExpectedWinsService` (`engine/ExpectedWinsService.java`) already has every
  game as `Game(week, aRosterId, aPoints, bRosterId, bPoints)` (line 63) and
  computes the all-play record. All-play answers "how good were you against
  everyone". It doesn't answer "what if you'd had *their* schedule".
- `/leagues/:id/expected-wins` → `ExpectedWins.tsx`.
- The LUCKIEST/UNLUCKIEST superlatives (`SeasonSuperlativesService.Kind`, line
  84) already rest on the all-play gap.

## Proposed design

An N×N matrix. Cell (row = team T, column = schedule of team S) is T's W-L if T
had played S's opponents week by week, with T's own weekly scores.

The one rule to get right: when S's opponent in week W **is T itself**, T would
have been playing S. Use the standard convention (T plays S in that week) and
state it under the matrix. It's a rule, so it's written down, not defaulted
silently (feedback: optional params that encode rules).

Derived numbers:
- **Schedule luck** = actual wins − mean wins across all N schedules. This is a
  second luck measure beside all-play, and the page says how they differ.
- **Toughest / easiest schedule** = column mean.

Regular season only, consistent with Expected Wins (memory: expected wins
regular-season only).

### Surface

A section on the existing Expected Wins page. Its own route isn't needed, since
it reads the same `Game` list. The diagonal is highlighted as the real record.
Each cell shows the exact W-L, not just a color (label-the-axis rule).

NBA: one matchup per week (memory: that's correct league format), so the matrix
works unchanged.

## Not building

- Playoff schedules, which aren't comparable across teams.
- Projected rest-of-season schedule strength, which is projection-bound.

## Acceptance criteria

1. The diagonal equals every team's real regular-season record, for every
   ingested completed season. This is an assertion, not an eyeball check.
2. Each row sums with no ties lost: total wins + losses + ties per cell =
   regular-season weeks.
3. A unit test with a hand-built 4-team, 3-week league where the expected matrix
   is worked out on paper.
4. Live check on 2025 NFL "(Foot) Ball Knowers".

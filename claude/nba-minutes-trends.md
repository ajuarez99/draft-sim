# NBA minutes and role trends

Status: **design, not built.** 2026-10-05. Gap #6 in
[competitor-gap-research.md](competitor-gap-research.md).

Competitor: Hashtag Basketball "Playing Time Trends", which tracks minutes and
role over time. In basketball, minutes come before points. A bench player whose
minutes jump from 18 to 32 is the waiver add, and his points just haven't caught
up yet.

## Correction, up front

This session's first gap summary (2026-10-05) said this needed "box-score data
beyond what Sleeper's fantasy points give you, so a new data source." **Wrong.**
`player_game.stats` already carries `sp` (seconds played) on all 57,941 NBA
rows for 2024 and 2025 (local DB, measured 2026-10-05). The data has been
ingested since spec 005. Nothing reads it.

## What's there now

- `player_game` (V20): one row per player-game with `game_date`, `opponent`,
  `is_away`, and `stats` including `sp`, `pts`, `reb`, `ast`, `fga`, `fta`,
  `tpa`, `to`, quarter splits, `plus_minus`.
- `GameScoringService.score` gives league points per game.
- `player_absence` (V22) classifies missed games (the Embiid award), so a
  zero-minute game can be told apart from a DNP-injury.

## Proposed design

Per player, rolling windows (last 3 / last 7 / season) of:

- **Minutes per game** = `sp / 60`.
- **Usage proxy** = (`fga` + 0.44·`fta` + `to`) per minute. This is a standard
  approximation, labelled as one. True usage needs team totals Sleeper doesn't
  give.
- **League points per minute.**

**Role-change flag:** last-3 minutes minus season minutes ≥ X, where X is
hand-set in `weights.yml` and labelled. Exclude games classified as absences
from both windows, so a return from injury doesn't read as a role surge.

### Surface

- A "Minutes" sparkline on the player card everywhere it appears for NBA.
- A "Role changes" list: biggest minutes risers and fallers this week, rostered
  and unrostered, split. This feeds streaming in
  [nba-schedule-grid-and-streaming.md](nba-schedule-grid-and-streaming.md).

## Not building

- Lineup, on/off or rotation data. Sleeper doesn't provide it.
- Any projection of future minutes. That's projection-bound.
- A football snap-share equivalent. Sleeper NFL stats don't carry snaps in the
  stored rows (0 NFL rows have `sp`; snap keys unverified). Check before
  claiming either way.

## Acceptance criteria

1. Minutes for one known game (pick any stored Jokić game) match the NBA box
   score to the second.
2. The role-change test: synthetic 10 games at 18 min then 3 at 32 must flag.
   Ten at 30 with one injury-shortened game at 8 must not.
3. Absence-classified games are excluded from the windows (test).
4. Live check against 2025 data before the 2026 season has games.

# Data model: Football Time Machine (spec 056)

> **Renumbered 2026-11-06:** this feature's migration is now `V46__historical_nfl.sql` (057 keeps V45, Allan's call; 057 merges first). **Amended 2026-11-08:** 056 merged first after all, since 057 was still uncommitted; 057 renumbers to V47. See plan.md. "V45" below means this migration under its old number.

Migration **`V45__historical_nfl.sql`**. V44 is the highest anywhere: `V44__roster_fpts_snapshot.sql`
is on branch 055, re-checked 2026-11-05 across every local and remote branch. V43 belongs
to this branch's base, 053. **Re-check at build** with the same loop. If another branch has
taken V45 by then, renumber (lessons #43).

V45 **alters** two V43 tables and adds two. V43 is unmerged but applied on the 053 scratch
DB (5438), so it is not edited (append-only rule).

## Altered tables

### `historical_season`
| Change | Why |
|---|---|
| `add column sport text not null default 'NBA' check (sport in ('NBA','NFL'))` | The NFL year 2005 collides with NBA 2005-06 on `season_start` |
| drop the PK `(season_start)`, add the PK `(sport, season_start)` | the same collision |
| widen the `source` check to `('KAGGLE','SLEEPER','NFLVERSE')` | the new source |
| `add column expected_game_count int` (null for NBA) | FR-014 / SC-002: the real schedule's count from config |
| `add column dropped_rows int not null default 0` | FR-014: source rows with no player id |
| `add column source_sha256 text` (null for NBA) | R1: traceable provenance in place of a pin |
| `alter column opening_night drop not null`, `alter column last_counted_date drop not null` | the NFL stats files carry no dates (R4) |

NFL rows: `label` = `"2007"`, `counted_stats` = all 13 scoring keys, `excluded_stats` = `{}`,
`stat_coverage` = `{}`, `ranking_source` = `PRIOR_SEASON_POINTS`, and `week_count` = the max
REG week.

### `historical_game`
| Change | Why |
|---|---|
| `add column sport text not null default 'NBA'` | FR-015, plus the FK below |
| drop the FK `season_start → historical_season`, add the FK `(sport, season_start) → historical_season (sport, season_start)` | the new season key |

The constraint names come from V43's implicit naming (`historical_season_pkey`,
`historical_game_season_start_fkey`). **T-migrate must verify these names against
`pg_constraint` on a V43 database before writing the `drop constraint` lines.** That is a
bug class-2 risk: plausible SQL that only execution can confirm.

Existing basketball rows get `sport = 'NBA'` through the default, so SC-008 holds with
no backfill.

### `historical_player`
- Widen the `source` check to include `'NFLVERSE'`. `source_key` = the GSIS `player_id`.
  Upserted, never deleted (053 F9).

## New tables

### `historical_nfl_player_week`
One player's REG game in one week. **Only QB/RB/WR/TE rows** are kept (FB/HB → RB), and
rows with a blank id are counted in `dropped_rows`, not stored.

| column | type | note |
|---|---|---|
| player_id | bigint → historical_player | |
| season | int | |
| week | int | the source's week number (R4) |
| game_id | text | e.g. `2005_01_ARI_NYG` |
| team, opponent | text | |
| position | text | the source's listing, after the FB/HB → RB map (R3) |
| pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td, fum_lost, pass_2pt, rush_2pt, rec_2pt, st_td | numeric not null default 0 | the source's NA becomes 0. Football has no era gaps (R6) |
| src_ppr | numeric | the source's `fantasy_points_ppr`, for SC-004 only, never scored |
| PK | (player_id, season, week) | **the import fails loudly** if a player has two REG rows in one week |

Index `(season, week)`.

### `historical_nfl_player_season`
The prior-season card line, aggregated from the week table at import (R5).

| column | type |
|---|---|
| player_id, season | PK |
| team | text (last REG row's) |
| games | int (weeks with a row) |
| positions | text[] (one value) |
| the same 13 stat columns, summed | numeric |

`age` is not stored. The card shows age as null for football.

## Java shapes (not tables)

- `StatLine`: `playerId(), seasonStart(), team(), age(), games(), positions(), Double stat(String)`.
  Implemented by the existing `SeasonLine` (its `stat` = the current
  `HistoricalBoardBuilder.total` switch, moved) and by the new `NflSeasonLine`.
- `WeekStatLine`: `playerId(), week(), gameDate() (nullable), Double stat(String)`.
  Implemented by `GameLine` (the current `SeasonReplay.statMap` switch, moved) and by
  `NflWeekLine`.
- `HistoricalSport` (one bean per sport): `sport()`, `settings` (teams, rounds, roster,
  reversal round, scoring, `RankConfig`), `countingKeys()`, `label(int)`,
  `settingsSource()`, `offeredSeasons()`, `findSeason(int)`, `candidates(int)`,
  `lines(int)`, `playedWeeks(int, ids)`, `weekStarts(int)`.

## State on the wire

- `HistoricalGameState` gains **`sport`** (`"NBA"|"NFL"`).
- `SeasonView` gains **`dataNotes`**: a list of human strings, empty when there is nothing
  to say, e.g. `"246 of 248 regular-season games are in the data"`,
  `"17 source rows had no player id and were left out"`. It also gains **`attribution`**
  (nullable, the nflverse CC-BY line for NFL, null for NBA).
- `GameSummary` gains `sport`.

Mirror all of these in `web/src/api.ts` in the same task (hard rule).

## Amended after review (2026-11-05)

These supersede the tables above where they differ.

- **Base**: 053 is on main. V43 and V44 are applied in prod. V45 is still free: re-checked
  on the shared 5433 DB (highest applied = 44) and across every branch. cf84da0's brief
  `V45__historical_draft_game.sql` rename was reverted the same morning (ff6711f) and was
  never applied anywhere.
- **F2, statement order** (probed in a rolled-back transaction by the reviewer):
  1. `alter table historical_season add column sport text not null default 'NBA' check (sport in ('NBA','NFL'))`
  2. `alter table historical_game add column sport text not null default 'NBA'`
  3. `alter table historical_game drop constraint historical_game_season_start_fkey`
  4. `alter table historical_season drop constraint historical_season_pkey`
  5. `alter table historical_season add primary key (sport, season_start)`
  6. `alter table historical_game add foreign key (sport, season_start) references historical_season (sport, season_start)`
  7. replace `historical_season_source_check` and `historical_player_source_check` with versions that also allow `'NFLVERSE'`
  8. drop not null on `opening_night` and `last_counted_date`, then add the `expected_game_count`, `dropped_rows` and `source_sha256` columns
  9. **F8**: `alter column sport drop default` on both tables

  No CASCADE anywhere.
- **F7**: `dropped_rows` counts only blank-id rows with a skill position or a nonzero scored stat.
- **F14, new table `historical_nfl_draft_pick`**: `(player_id bigint → historical_player,
  season int, round int, pick int, position text)`, PK `(player_id)`. This is a player's
  first NFL draft. Loaded from `draft_picks.csv` (nflverse, CC-BY) for skill positions,
  only for `gsis_id`s that already exist as `historical_player` rows. Re-run after every
  season import.
- **F16, card**: `Prior.perGame` keeps the yardage/TD/rec keys. The new `Prior.totals`
  carries the season totals of `st_td`, `fum_lost`, `pass_2pt`, `rush_2pt` and `rec_2pt`
  when nonzero (an empty map otherwise, never null). `HistoricalPlayerCard` gains a
  top-level `rookiePick` (Integer, nullable): the overall NFL draft pick of a rookie with
  no prior line, otherwise null. A rookie's `prior` stays null.
- **F9**: `dataNotes`/`attribution` are read from the board snapshot with `has()`. A
  053-shape snapshot reads as `[]`/null.

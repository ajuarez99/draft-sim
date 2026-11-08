# Research: Football Time Machine (spec 056)

Every number below was **measured** on 2026-11-05 unless it says otherwise. The probe
scripts are in the session scratchpad, not in the repo. The measurements need re-running
at build (task T-measure), not trusting.

## R1. Data source: nflverse `stats_player` weekly files

- **Decision**: one source for every season, 2000–2025 drafted plus 1999 as prior-season
  lines: `https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_week_{season}.csv`.
- **Measured**:
  - The `nflverse/nflverse-data` repository licence is **CC-BY-4.0** (GitHub licence API).
  - Files exist for 1999–2025, about 7–8.7 MB of CSV each, with a `.csv.gz` about 1 MB each.
  - The columns include `player_id` (GSIS id), `player_display_name`, `position`, `season`,
    `week`, `season_type` (REG/POST), `game_id`, `team`, `opponent_team`, the
    passing/rushing/receiving counting stats, the `*_2pt_conversions`, the `*_fumbles_lost`,
    `special_teams_tds`, `fantasy_points` and `fantasy_points_ppr`.
  - REG game_ids per season: 2000 has **246** (the real season had 248), 2005 has 256,
    2015 has 256, and 2022 has **271**. The real 2022 count is 271 because Bills–Bengals
    week 17 was cancelled, which is a real gap and not a data fault.
  - Rows with a blank `player_id`: 0 in 2000 and **17** in 2005.
  - Display names shared by different ids: 2 in 2000, 6 in 2005. The id keeps them apart.
- **Rationale**: Sleeper's NFL weekly stats begin in 2009. Week 1 returned 0 rows for 1999
  and 2005, and 565 rows for 2009 (measured). Sleeper cannot serve 2000–2008, and mixing
  sources would bring back 053's whole cross-source identity problem. nflverse ids are
  stable GSIS ids, so **none of 053's name/alias/same-name layer is needed**.
- **Alternatives**:
  - Sleeper for 2010+ plus nflverse before. Rejected: two sources and an identity seam.
  - Pro-Football-Reference. Its terms forbid bulk copies, the same reason 053 rejected
    Basketball-Reference.
- **Not pinned by hash**: nflverse republishes files (it fixes stats after the fact), so
  a SHA-256 pin like 053's Kaggle pin would break on every refresh. The import instead
  **records** each file's SHA-256 and byte size in the import report, plus a
  `source_sha256` per season, so a game's provenance is traceable. A finished game is
  still frozen (FR-010), whatever a later re-import changes.

## R2. Scoring: FR-003's formula against nflverse's own PPR

- **Measured**: on QB/RB/WR/TE/FB REG rows, FR-003's formula as first written (pass yd
  0.04, pass TD 4, INT −2, rush/rec yd 0.1, rush/rec TD 6, rec 1, fumbles lost −2,
  2-pt 2) matched `fantasy_points_ppr` within 0.01 on:

  | Season | Formula as first written | With special-teams TDs at 6 |
  |---|---|---|
  | 2000 | 99.56% | **100.00%** (5260/5260) |
  | 2005 | 99.70% | **100.00%** |
  | 2015 | 99.66% | **100.00%** |
  | 2022 | 99.90% | **100.00%** (5918/5918) |

  All the misses were return TDs (Troy Brown, Dante Hall, Devin Duvernay…).
- **Decision**: add `st_td: 6` (return/special-teams TDs) to the scoring. Sleeper's
  standard PPR also scores return TDs for offensive players. The spec's FR-003 gets an
  "amended at planning" note. Fumbles lost = `sack_fumbles_lost + rushing_fumbles_lost +
  receiving_fumbles_lost`. 2-pt = the sum of the three `*_2pt_conversions`.
- **Scoring keys use Sleeper's NFL vocabulary** (`pass_yd, pass_td, pass_int, rush_yd,
  rush_td, rec, rec_yd, rec_td, fum_lost, pass_2pt, rush_2pt, rec_2pt, st_td`). That lets
  `GameScoringService.score` and the SC-005 Sleeper cross-check use one key set, and
  `pass_2pt/rush_2pt/rec_2pt` stay separate the way Sleeper keeps them.

## R3. Positions are today's, not that season's

- **Measured**: `position` in the 2012, 2015 and 2022 files is the player's **current**
  listing. Terrelle Pryor is "WR" in 2012, when he was Oakland's QB. Cordarrelle
  Patterson is "RB" in 2015, when he was a WR. Taysom Hill is "TE" in 2022.
- **Decision**: use the listed position, with `FB`/`HB` mapped to `RB`. Rows at any other
  position (OL, DB, K…) are not imported. This is the same "mild documented leak"
  053 accepted for its Sleeper seasons, now stated with the measured examples. Deriving
  a position from the stat profile would be a new hand-set heuristic. It is an open item
  for Allan (plan O2), not built.

## R4. Weeks, byes and game counts

- **Decision**: the replay's week = the source's `week` on REG rows, and `week_count` =
  the season's max REG week (17 through 2020, 18 from 2021). A player with no row in a
  week scores 0 (bye, inactive, or no recorded stat).
- **Real game counts** come from a hand-written table in config: 248 for 2000–2001, 256
  for 2002–2020, 272 from 2021, plus `2022: 271`. These are facts from the schedule, not
  fitted values. The loaded count is compared with the table at import and the gap is
  stored (FR-014).
- **2001**: the postponed week 2 was played after week 17. Whether nflverse numbers it
  week 2 or week 18 is **unmeasured**. T-measure reads the 2001 file. Either way the
  replay just follows the source's numbers, and `week_count` is the max week.
- **Dates**: the stats file has no dates. Football weeks are shown as "Week N" with no
  date. `weekStarts` is empty for football, and `SeasonReplay.monday()` already returns
  null for a missing week start.

## R5. Prior-season lines are aggregated from the weekly rows

- **Decision**: a prior-season line = the sum of that season's REG weekly rows per
  player. `games` = the number of REG weeks he has a row in, which is **games with a
  recorded stat**, not games active. The card says "G" with that meaning, and the
  difference is small for fantasy-relevant players. `team` = the team of his last REG row.
  `age` = null, because the stats file has no birth date and adding a second dataset
  isn't worth it for a card field.
- **Ranking (FR-006)**: PPR per game × `rank-standard-games: 17`, with a
  `rank-min-games: 6` floor and a `rank-lookback-seasons: 3` window, newest qualifying
  season first (053's rule). All three are **hand-set**, chosen by analogy with 053
  (72/20 ≈ 3.6 there, 17/6 ≈ 2.8 here) and labeled as guesses. Bonuses don't exist in
  this scoring, so the card's points and the ranking use every scored key.

## R6. What generalizes from spec 053 (code read 2026-11-05)

Read: `HistoricalGameService`, `SeasonReplay`, `HistoricalBoardBuilder`,
`HistoricalDataRepository`, `HistoricalGameState`, `StatOrder`, `LeagueShape`, `Position`,
and the four web files.

- **Sport-neutral already**: `MockDraftEngine`, `DraftContextFactory`, `PickDecider`,
  `GameScoringService.score(Map, Map)`, `RealizedLineupService.bestLineup(List<ScoredPlayer>, …)`,
  `SportRulesRegistry` (football rules with FLEX exist), the circle-method schedule,
  standings, `Comparisons` (it works on player-week values), and `HistoricalPlayerCard`
  (its `perGame` is a key→value map).
- **NBA-hardcoded (six call sites)**: `HistoricalGameService` lines 148
  (`LeagueShape.NBA_ROSTER`), 309 (`PositionalPriors.uniform(Sport.NBA)`), 318
  (`LeagueSettings(Sport.NBA…)`), 379 (`rulesRegistry.get(Sport.NBA)`), and
  `HistoricalBoardBuilder` lines 109 (the position-sport check) and 183 (`new Player(…
  Sport.NBA…)`).
- **NBA-shaped by type**: `SeasonLine` and `GameLine` are records with NBA stat fields,
  read through two `switch`es (`HistoricalBoardBuilder.total`, `SeasonReplay.statMap`).
  `HistoricalBoardBuilder.COUNTING` and `label()` are NBA too. `StatOrder.ORDER` holds NBA
  keys. The schema ties `historical_season` and `historical_game` to `season_start` alone,
  so NFL 2005 and NBA 2005-06 **collide on the primary key**.
- **Decision**: generalize, don't fork.
  - (a) A small `StatLine` interface (`Double stat(String key)` plus the line fields),
    implemented by the existing NBA records and by the new NFL records. The two switches
    move onto the NBA records unchanged, so NBA scoring is byte-identical (SC-008).
  - (b) A `HistoricalSport` strategy per sport: config, roster, label, counting keys, the
    data access for candidates/lines/games/weeks, `Sport` and the settings-source text.
  - (c) A `sport` column on `historical_season` and `historical_game`, with V45 rebuilding
    their keys.
  - (d) Separate NFL fact tables, because the stat columns differ entirely.
- **Not reused**: `KaggleHistoricalSource`, `KaggleIdentity`, the alias and same-name
  CSVs, `EraScoring` (football has no era gaps in these columns, so every season counts
  every key and `excluded_stats` is `{}`), and `SleeperHistoricalSource`.

## R7. Rookies rank last (carried over from 053, flagged)

053 accepted "no prior line → ranked after everyone, seeded order". In football this
leaves every rookie (e.g. a 2018 Saquon Barkley) to the bots' last rounds, so a user who
knows the season can farm rookies. nflverse publishes `draft_picks` (also CC-BY-4.0),
which could order rookies by real NFL draft slot. That changes how the game plays, so it
is **open item O1 for Allan**. The plan keeps 053's rule until he answers.

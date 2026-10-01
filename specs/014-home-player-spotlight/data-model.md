# Data model: Player spotlight on the league home

One new migration, `V26__sport_trending.sql` (research R12), with two tables. Everything else is read from
tables that already exist.

## New: `sport_trending_fetch` — one row per sport

The state of the most recent trending fetch for a sport. It is separate from the rows, so "fetched, and
the list was empty" and "never fetched" stay different claims.

| column | type | notes |
|---|---|---|
| `sport` | text, PK | `nba` / `nfl` |
| `fetched_at` | timestamptz, nullable | last **successful** fetch; null = never |
| `lookback_hours` | int, not null | window the counts cover; 24 (stored, not assumed by the reader) |
| `league_season` | int, nullable | Sleeper `state.league_season` at fetch time (R8) |
| `season_start_date` | date, nullable | Sleeper `state.season_start_date` at fetch time (R7); null = unknown |
| `last_failure_at` | timestamptz, nullable | |
| `last_failure` | text, nullable | short reason, for the page's "unavailable" state and for logs |

## New: `sport_trending` — the list itself

| column | type | notes |
|---|---|---|
| `sport` | text | FK → `sport_trending_fetch.sport` |
| `rank` | int | 1-based, Sleeper's order |
| `sleeper_player_id` | text | Sleeper's id, the same key `player_game` uses |
| `add_count` | int | platform-wide adds over `lookback_hours` |
| PK | (`sport`, `rank`) | |

**Write rule**: a successful fetch deletes the sport's rows and inserts the new list **in one
transaction**, together with updating `fetched_at`, `lookback_hours`, `league_season` and
`season_start_date`. A failed fetch touches only `last_failure_at`/`last_failure` and leaves the previous
list readable, with its true age shown (FR-011).

**JDBC traps (lesson class 3)**: bind `fetched_at` as `OffsetDateTime`, not `java.sql.Timestamp`
(`timestamptz`), and `season_start_date` as `LocalDate`. Exercise the real write path in an integration
test against Postgres, not a narrower check.

## Read, unchanged

| table | used for |
|---|---|
| `player_game` | every night/week performance, in both sports (R1) |
| `player_absence` | not read directly. "Did not play" is the absence of a `player_game` row (R3) |
| `sport_week_stats` | night completeness: `fetched_at` of the week containing the night (R6) |
| `player` | name, positions, current team, `years_exp` (rookie, R4) |
| `league` | `scoring_json`, sport, season |
| `roster_week_points` | ownership from the latest stored week's `players_points` keys (R10) |
| `roster_season`, `league_member`, `manager` | owner display name and the viewer's "this is yours" flag, exactly as `WeeklyReportService` builds them |

## Derived entities (in memory, per request)

### Night
- `date` (LocalDate), `gamesCount` (distinct `game_id`s that date), `complete` (R6).
- **Selection**: the latest `game_date` in the current season whose week's `sport_week_stats.fetched_at`
  is at or after `date + 1 day` at `NIGHT_COMPLETE_HOUR_UTC` (10). A later date with rows that fails the
  test is reported as `laterNightInProgress`.
- Basketball only. Chosen because the sport plays several games per scoring period
  (`SportRules.playsMultipleGamesPerScoringPeriod()`), never by sport name (FR-013).

### Period
What a section is scored against: either `{kind: NIGHT, date}` or `{kind: WEEK, week, weekFinal}`.
For a week, `week` comes from the shared resolver extracted from `WeeklyReportService.forWeek`
(latest final, else latest stored), so the spotlight and the home's latest-matchup block cannot name
different weeks (R9).

### Performance
`playerId, name, position, nflOrNbaTeam, opponent, isAway, points, ownership`.
- `points` = `GameScoringService.score(league.scoring_json, stats)`. For a week, it is the sum over the
  week's rows (one for football; basketball does not use week periods here).
- Never built for a player without a `player_game` row.

### TrendingEntry
`rank, playerId, name, position, team, addCount, ownership, outcome`, where `outcome` is one of:
- `PLAYED` with `points`, `opponent`, `isAway`
- `DID_NOT_PLAY`: no row for the period
- `NO_GAME`: no row, his current team appears as no row's `opponent` in the period, **and** the period is settled (a NIGHT, or a WEEK with `weekFinal`). Renamed from `BYE` after review (research R3 amendment)
- `NO_PERIOD`: there is no night/week to score against yet (pre-season)

Unresolvable ids (not in `player`) are dropped and counted in `omittedUnknownPlayers`.

### Ownership
`{ rostered: false }` or `{ rostered: true, teamName, isMe }`. Taken from the latest stored week (R10).
Built with a mutable map, never `Map.of`, because `teamName` can be null for a roster whose member row is
missing (hard rule: `Map.of` throws on null).

### Rookie
`player.years_exp == 0`. Null is never a rookie (FR-007).

## Validation rules (from the spec's FRs)

- No entry in any section carries `points` unless a `player_game` row backs it (FR-005).
- Ordering: points descending, then `playerId`, then `date`. This is the same tiebreak as
  `WeeklyReportService.rankNights` and reuses it where the shape allows (FR-014). Trending keeps
  Sleeper's rank order.
- Limits: top-of-night 10, rookie watch 10, trending 10 (presentation constants, labelled as such).

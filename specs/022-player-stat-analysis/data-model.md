# Data model: Player stat analysis (spec 022)

**No new tables or migrations.** Everything below is computed at read time from stored rows
(research R1). The highest migration stays V29.

## Stored inputs (existing, read-only)

| Source | What this feature reads |
|---|---|
| `player_game` (V20) | Player rows and `TEAM_xxx` rows for one sport-season: `stats`, `game_id`, `game_date`, `opponent`, `week`, `fetched_at` |
| `sport_schedule` (V27) | A night's scheduled games and their `status`, for night completeness |
| `league` | `scoring_json`, `roster_positions`, `total_rosters`, `status`, and the `previous_league_id` chain |
| `player` | Name, `positions`, current `team` |
| `roster_season.players` (V28) | Current-season ownership |
| `roster_week_points.players_points` | Past-week ownership; the keys are the roster (research R8) |
| `draft`, `draft_pick` | The season's draft: pick, round, slot, manager |
| `adp_snapshot` | `blend` source only, as of the draft date (R10) |
| `DraftGradesService` | `valueOverSlot` per pick (R9) |

## Shared building blocks

These are extracted or extended in build step 1. None of them is a second implementation of a
rule.

### `NbaGameLines` (new; logic moved from `PlayerTrendsService.prepare`)

Input: one season's `SeasonGame` list and `TeamGame` list.

Output: `Map<sleeperPlayerId, List<Line>>`, oldest first. Each `Line` carries:

- `gameId`, `date`, `week`;
- `team` (the player's team code that night) and `opponent`;
- `isHome`, read from the schedule, nullable when unknown;
- `minutes`, as `sp / 60`;
- `stats`, a read-only `Map` view;
- `teamRow` and `oppRow`, both nullable.

Rules, moved verbatim from Trends:

- `TEAM_` rows are never lines.
- The bare `TEAM_` row and any game whose opponent is not a season team code (the All-Star game)
  are dropped.
- `sp <= 0` is not a game.
- The team row is the one in the same game whose code ≠ the opponent. The opponent row is the one
  whose code = the opponent.
- Ordering is by `(date, gameId)`.

It also outputs `teamGames: Map<teamCode, List<TeamGame>>` and the season's team codes.

### `SeasonBoxCache` (new, research R6)

- **Key**: `(sport, season)`.
- **Value**: the `NbaGameLines` result, built from a compact store. Each game holds interned key
  indexes and `float` values.
- **Validity token**: `(count(*), max(fetched_at))` for that sport-season. It is checked on every
  `get`, and a reload happens when the token changes.
- **Single flight**: at most one reload per key at a time.
- **No eviction**: there are at most 3 NBA seasons. V9 measures the memory.

### `GameScoringService.contributions(scoring, stats) → Map<String, Double>` (new method)

It iterates the scoring keys, as `score` does. `score(s, x)` becomes
`round2(sum(contributions(s, x).values()))`.

**Invariant I1**: on every 2025 NBA game, `score` before the refactor equals `score` after it.

### `AdvancedStats` (new, pure; `usage` moved here from Trends)

`AdvancedStats.window(List<Line>)` returns a `Window`. Sums are pooled over the window's games,
using each game's own team and opponent rows (research R5). Every rate is a `Rate`:

```text
Rate { Double value; String reason }   // reason ∈ {null, NO_ATTEMPTS, NO_MINUTES, NO_TEAM_ROW}
```

Exactly one of `value` and `reason` is non-null. This is invariant I2.

`Window` holds:

- `games`, `minutes` and `minutesPerGame`;
- counting totals, per game and per 36: pts, reb, oreb, dreb, ast, stl, blk, tov, pf, fgm, fga,
  tpm, tpa, ftm, fta;
- `fgPct`, `tpPct`, `ftPct`;
- `ts`, `efg`, `ftr`, `tpar`;
- `usg` and `minutesShare` (MP / (TmMP/5) per game, pooled);
- `astPct`, `orbPct`, `drbPct`, `trbPct`, `stlPct`, `blkPct`, `tovPct`;
- `gameScorePerGame`;
- `plusMinusPerGame`;
- `smallSample`: minutes < `small-sample-minutes`.

`AdvancedStats.gameScore(Line)` gives the game score for one game.

The windows are `SEASON`, `LAST_10` and `LAST_5`. A last-N window covers `min(N, games)` games and
states that count (FR-006).

## League layer (per league-season)

### Qualification (R12)

A player is `qualified` in a window when both hold:

- `games ≥ ceil(rank-min-games-share × maxTeamGamesSoFar)`, where `maxTeamGamesSoFar` is the most
  team games any team has played in the same window span;
- `minutesPerGame ≥ rank-min-minutes-per-game`.

### Fantasy

- **`fpPerGame`**: the mean over the window's games of `GameScoringService.score(league.scoring,
  line.stats)`.
- **`breakdown`**: for the season window only, the sum of `contributions` over the games, as
  `List<{key, points, share}>`, sorted by points descending, negatives included. Invariant I3:
  `|sum(points) − sum of per-game scores| ≤ 0.01 × games`.

### Ranks (FR-027)

These are taken over the qualified players in the window.

- `leagueRank`: rank by `fpPerGame`, descending.
- `positionRank`: the same rank, within the player's first listed position.
- `pointsRank`: rank by `pts` per game, over the same group.
- `rankMove`: `pointsRank − leagueRank`.
- `groupSize` and `positionGroupSize`.

Ties are broken by value descending, then games descending, then name ascending, then sleeper id.
These are competition ranks: tied players share the lower number. Invariant I4: two reads give
identical ranks. An unqualified player gets null ranks and the reason `NOT_QUALIFIED`.

### Percentiles (FR-018, FR-030)

For each advanced rate there are two groups:

- **`NBA_POSITION`**: qualified players with the same first position, across all players.
- **`LEAGUE_ROSTERED`**: qualified players owned in this league at the ownership point (R8).

`percentile = 100 × (count of group values strictly below + 0.5 × count of ties excluding self) /
(n − 1)`. For TOV%, lower is better, so the percentile is inverted and labelled as such. A group
with n < 2, or a player outside the group, gives null with a reason. Every percentile carries
`{group, n}`.

### Replacement (FR-033, R11)

- **Starting slots**: `roster_positions` with `BN`, `IR` and `TAXI` removed, ordered
  single-position slots first, then `G`/`F`, then `UTIL`.
- **Fill**: `teams = league.total_rosters`. For each slot in that order, `teams` times, take the
  best remaining qualified player for whom `BasketballRules.isEligible(player, slot)` is true.
- **`replacementLevel[position]`**: the best unplaced player eligible at that position. It is null
  when none remain.
- **`valueOverReplacement`**: `max` over the player's eligible positions of `fpPerGame −
  replacementLevel[position]`. The position used is reported.

### Ownership (R8)

`Ownership { state, rosterId, ownerName, avatarId, isMe, asOf }`.

`state` is one of:

- `ROSTERED` or `FREE_AGENT`;
- `NOT_DRAFTED`, while the league is `pre_draft` or `drafting`;
- `UNAVAILABLE`, when nothing is stored for the point in time.

`asOf` is either:

- `{kind: CURRENT, fetchedAt}`, for the current season (V28);
- `{kind: WEEK, week}`, for a past season's last stored week, or a past night's week.

### Draft and ADP (FR-031, FR-032, FR-034)

- **`draft`**: `{pickNo, round, managerName}`, `UNDRAFTED`, or a draft state of `NOT_HAPPENED` (the
  league is not `complete`, or there is no draft row).
- **`adp`**: `{value, source: "blend", capturedOn}`, or `NO_ADP_STORED`.
- **`draftValue`**: `DraftGradesService`'s `valueOverSlot` for that pick, and null for undrafted
  players. It is labelled "per counted week, from Draft Grades", with that page's link.

## Night (US3)

`Night` is computed for `(leagueSeason, date)`:

- **`games`**: one entry per scheduled game on that date, with home and away teams, both scores
  from the team rows' `pts`, and status.
- **`complete`**: every game has schedule status `complete` and both team rows. `missingGames`
  counts the ones that don't (R13).
- **`topByGameScore` and `topByFantasy`**: two separate, labelled lists of the night's lines, with
  exact values and ties broken as in the ranks.
- **`mine`**: the requester's roster for the night's week (R8). It holds `played: List<line>` and
  `didNotPlay: List<player>`, where a player is in `didNotPlay` if his team played that night and
  he has no line.
- **`standouts`**: `List<{line, rule, values}>`. A line can carry more than one rule. The rules,
  with thresholds from R12, are:
  - `SEASON_HIGH_{PTS|REB|AST|MIN}`: the value is greater than every earlier game this season, and
    the player has at least `standout-min-prior-games` earlier games. `values = {tonight,
    previousHigh}`.
  - `TS_ABOVE` / `TS_BELOW`: the night's TS% differs from his season TS% (before tonight) by at
    least `standout-ts-delta` points, with attempts ≥ `standout-ts-min-attempts`. `values =
    {tonight, season}`.
  - `MINUTES_JUMP_FREE_AGENT`: ownership is `FREE_AGENT` and tonight's minutes are at least
    `standout-minutes-jump` above his last-5 mean before tonight. `values = {tonight, last5}`.
    This rule is not evaluated when ownership is `UNAVAILABLE`.
- **`dates`**: every stored game date of the season, for the picker.

## Invariants (each one a test)

- **I1**: `score` is unchanged by the `contributions` refactor, checked over all 2025 games.
- **I2**: a `Rate` has exactly one of `value` and `reason`.
- **I3**: the breakdown sums to the season fantasy total (±0.01 × games).
- **I4**: ordering is deterministic, including exact ties.
- **I5**: no `TEAM_` id appears in any player list, leaderboard row, rank group or percentile
  group.
- **I6**: the same player, season and window give identical real-basketball values on the player
  page and the leaderboard, and in two leagues with different scoring (FR-004, FR-025).
- **I7**: a value moving the right way moves the ranking the right way (lessons bug class #1).
  These are preference-ordering tests: more FP/G ranks higher, a lower replacement level gives a
  higher value over replacement, and a higher TOV% gets a lower percentile.
- **I8**: an undrafted player never gets a draft value, and a past season never shows current
  ownership.

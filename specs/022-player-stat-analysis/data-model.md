# Data model: Player stat analysis (spec 022)

**No new tables or migrations.** Everything below is computed at read time from stored rows
(research R1). The highest migration stays V29.

> **Amended after review, 2026-10-07** ([plan-review.md](plan-review.md)). The sections below are
> rewritten in place, and each changed rule is tagged with the finding that changed it:
>
> | Section | Findings |
> |---|---|
> | NbaGameLines | F7 |
> | SeasonBoxCache | F7, F8, N2 |
> | AdvancedStats windows | F9 |
> | Qualification | F9 |
> | Fantasy | N8 |
> | Ranks | F13 |
> | Percentiles | F13 |
> | Ownership | F2, F5, F6, N6 |
> | Draft and ADP | F1, F12 |
> | Night | F3, N7 |
> | Team games missed (new) | F10 |
> | Invariants | I8 extended |
>
> The superseded wording is quoted in plan-review.md.

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

Input: one season's `SeasonGame` list and `TeamGame` list. `SeasonGame` gains `Boolean isAway`,
from `player_game.is_away` (F7).

Output: `Map<sleeperPlayerId, List<Line>>`, oldest first. The lists are **unmodifiable** (F8).
Each `Line` carries:

- `gameId`, `date`, `week`;
- `team` (the player's team code that night) and `opponent`;
- `isHome`, as `!isAway`, null when `is_away` is null (F7; the schedule is not used);
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
- **Value**: the season's **raw** rows (F7): `List<SeasonGame>` (with `isAway`) and
  `List<TeamGame>`, each game's stats held as interned key indexes plus `double` values (N2), with
  a read-only `Map` view. Also the `NbaGameLines` result computed over them once per load. Trends
  gets the raw lists for `oneGameShare` (which keeps the All-Star row, unchanged) and the lines for
  the rest.
- **Validity token**: `(count(*), max(fetched_at))` for that sport-season. It is read **before**
  the rows (F8). It is checked on every `get`; a changed token reloads, unless a refresh of that
  season is in flight, in which case the current entry is served.
- **Explicit invalidation** (F8): `PlayerGameIngestService.refreshSportSeason` calls
  `invalidate(sport, season)` on completion, and the token is the safety net.
- **Single flight** (F8): lock-free, on the `refresh/SingleFlight` pattern (a `ConcurrentHashMap`
  of `CompletableFuture`s, as its own instance). Never `synchronized`, because of virtual-thread
  pinning on Java 21.
- **Immutable values**: every list and map in an entry is unmodifiable, and callers that sort take
  a copy (Trends' `prepare` sorted in place at `:446`).
- **No eviction**: there are at most 3 NBA seasons. V9 measures the memory; 31 keys per row on
  average (N2).

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
states that count (FR-006). Every window carries `firstGameDate` and `lastGameDate` (F9).

`score` is summed naively, left to right in scoring-key order, over the `LinkedHashMap` that
`LeagueRepository.scoringOf` returns. It never uses `DoubleStream.sum()`, which compensates and can
differ in the last ulp (N3).

### Team games missed (F10; named `teamGamesMissed`, not Trends' `missedTeamGames`)

For each team the player played for this season, count that team's games from his first game with
the team to his last, minus the games he played for it. Sum across his teams.

- Games before he joined a team, and after he left it, are not missed.
- Trends' `missedTeamGames` is a different quantity (the consecutive most-recent run), so the names
  stay distinct.
- Tested with a traded player and a mid-season signing.

## League layer (per league-season)

### Qualification (R12)

A player is `qualified` in a window when the rule for that window holds (amended F9):

- **SEASON**:
  - `games ≥ ceil(rank-min-games-share × maxTeamGames)`, where `maxTeamGames` is the most games
    any team has played this season;
  - and `minutesPerGame ≥ rank-min-minutes-per-game`.
- **LAST_N**:
  - the window covers all N games;
  - `minutesPerGame ≥ rank-min-minutes-per-game`;
  - and `lastGameDate` is within `recency-days` of the season's latest stored game date.
- **A stale player** (one failing only the recency rule) gets `NOT_QUALIFIED_STALE`, and the page
  says "hasn't played since {date}". He is excluded from LAST_N ranks, percentiles, replacement and
  leaders.

### Fantasy

- **`fpPerGame`**: the mean over the window's games of `GameScoringService.score(league.scoring,
  line.stats)`.
- **`breakdown`**: for the season window only, the sum of `contributions` over the games, as
  `List<{key, points, share}>`, sorted by points descending, negatives included. Invariant I3:
  `|sum(points) − sum of per-game scores| ≤ 0.01 × games`.
  - The keys are the league's **scoring** keys, including `dd`, `td`, `ff`, `tf` and the `bonus_*`
    keys, and each needs a web label (N8).
  - `share = points / seasonTotal`, and it is null when `seasonTotal ≤ 0` (N8).

### Ranks (FR-027)

These are taken over the qualified players in the window.

- `leagueRank`: rank by `fpPerGame`, descending.
- `positionRank`: the same rank, within the player's first listed position.
- `pointsRank`: rank by `pts` per game, over the same group.
- `rankMove`: `pointsRank − leagueRank`.
- `groupSize` and `positionGroupSize`.

Amended F13:

- **Ranks** are competition ranks on the **wire-rounded** value (2 decimals). Players whose shown
  values are equal share the better (lower) number, so a rank never differs between two visibly
  equal values.
- **Display order** within a tie is games descending, then name ascending, then sleeper id. That
  ordering affects display only, never the rank number.
- Invariant I4: two reads give identical ranks and order.
- An unqualified player gets null ranks and the reason `NOT_QUALIFIED` or `NOT_QUALIFIED_STALE`.

### Percentiles (FR-018, FR-030)

For each advanced rate there are two groups:

- **`NBA_POSITION`**: qualified players with the same first position, across all players.
- **`LEAGUE_ROSTERED`**: qualified players owned in this league at the ownership point (R8).

`percentile = 100 × (count of group values strictly below + 0.5 × count of ties excluding self) /
(n − 1)`, where `n` counts the group **excluding the player himself** when he is a member.

- **TOV%**: lower is better, so the percentile is inverted and labelled as such.
- **A small group**: n < 2 gives null with `GROUP_TOO_SMALL`.
- **Not in the group** (amended F13): a player's value is still ranked **against** the group. A
  free agent's `LEAGUE_ROSTERED` percentile answers "where he'd sit among rostered players", and
  needs no "not in group" code.
- **No ownership**: when ownership is `UNAVAILABLE` or `NOT_DRAFTED`, `LEAGUE_ROSTERED` is null
  with `OWNERSHIP_UNAVAILABLE`.
- **Unqualified players**: their own percentile is null with `NOT_QUALIFIED`.
- **Per window** (F13): percentiles are computed for every window, using that window's
  qualification.
- Every percentile carries `{group, n}`.

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

Amended (F2, F5, F6, N6). This replaces the `asOf` rules above:

| Point in time | Source | `asOf` |
|---|---|---|
| Current season, page view | V28 `rosteredPlayers`, with draft gating | `CURRENT` |
| Completed season, page view | `players_points` of week `playoff_week_start − 1` (2025: 18; 2024: 21) | `WEEK`, labelled "end of regular season (week N)" |
| A night in a **scored** week | that week's `players_points` | `WEEK` |
| A night in the league's **current, unscored** week | V28 current rosters, with draft gating | `CURRENT` |
| A night after the league's last week | none | `UNAVAILABLE`, with "no roster covers this night" |

- **A week with an empty roster** (N6): if any roster's `players_points` is `{}` that week, the
  whole week is `UNAVAILABLE`.
- **`currentOwnership`** (F6): present only when `requestedSeason != null`. It is the requested
  (newer) season's `CURRENT` ownership, labelled separately. `ownership` never uses it (I8).

### Draft and ADP (FR-031, FR-032, FR-034)

Amended F1, F12:

- **`draft.state`** reads **`draft.status`**, never `league.status`. `complete` gives `COMPLETE`, a
  draft row in any other status gives `NOT_HAPPENED`, and no draft row gives `NONE`.
  `DraftGradesService.read` gates on the same field (`:176`).
- **Per player**: `{pickNo, round, managerName}`, or null, which means undrafted when the state is
  `COMPLETE`. `managerName` uses **Draft Grades' rule** (slot → manager → display name,
  `DraftGradesService.java:310-329`), so it matches the page it links to.
- **`adp`**: `{value, source: "blend", capturedOn}` from the new
  `BoardRepository.latestBefore(NBA, "blend", draft.startTime)`, with `NO_ADP_STORED` when there is
  no capture on or before that date.
  - When `start_time` is null, it gives `NO_DRAFT_DATE`.
  - `start_time` is the scheduled time, and a capture made on draft day counts.
- **`draftGrades`**: `{available, reason, gradesEarly, weeksCounted}`, copied from that draft's
  `DraftGradesService` result, so a null `draftValue` can always be told apart from "Draft Grades
  unavailable or early".
- **`draftValue`**: that pick's `valueOverSlot`, labelled "per counted week, from Draft Grades" and
  linked to that page. It is null for undrafted players, and when `draftGrades.available` is false.
- **The Draft Grades read** is memoised on the season token (V9 times it).

## Night (US3)

`Night` is computed for `(leagueSeason, date)`:

- **`games`** (amended F3): one entry per **stored** game on that date, built from its two team
  rows, already All-Star-filtered by `NbaGameLines`. Each entry has the home and away teams (from
  `is_away`) and both scores from the team rows' `pts`. This works for 2024, which has no schedule.
- **`complete`** (amended F3): the shared night rule extracted from
  `PlayerSpotlightService.isComplete` (every week the night's rows belong to was fetched at or after
  D+1 10:00 UTC). It is the same rule Spotlight uses, so a "Top of the night" link never lands on a
  night the report calls incomplete.
- **`missingGames`**: real scheduled games on that date with no stored team rows. "Real" is
  `ScheduleGridService`'s rule (postponed, canceled and exhibition excluded), called, not copied.
  It is null with `NO_SCHEDULE` when the season has no schedule rows.
- **Default date** (F3): the latest complete night, by the shared rule.
- **`topByGameScore` and `topByFantasy`**: two separate, labelled lists of the night's lines, with
  exact values and ties broken as in the ranks.
- **`mine`**: the requester's roster for the night's week (R8). It holds `played: List<line>` and
  `didNotPlay: List<player>`. Amended N7: `didNotPlay` comes from game-level `player_absence` rows
  (`ENTRY_WITHOUT_PLAY`, with `team` and `game_id`) for that night's games, the source Trends
  already reads. It does not use `player.team`, which is today's team.
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
- **I8**: an undrafted player never gets a draft value, and a past season's `ownership` never shows
  current rosters. Only the separately labelled `currentOwnership` may.
- **I9** (added after review): an in-season league whose draft is `complete` shows
  `draft.state COMPLETE` (F1). From any chain season, `seasons` lists every chain season (F4).

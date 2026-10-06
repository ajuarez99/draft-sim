# Data model: NBA minutes trends and streaming candidates

> **Amended after code review, 2026-10-06** ([code-review.md](code-review.md)):
> - **B1:** `missedTeamGames` counts the player's most recent consecutive team games that have an
>   absence row **with a `game_id`** for him, of either basis. Basketball's per-game misses are
>   stored as `ENTRY_WITHOUT_PLAY`. `TEAM_PLAYED_NO_ENTRY` is week-level with `game_id` null. The
>   label says "missed", never "benched".
> - **B2:** when ownership is known, risers and fallers carry the top `list-size` **per ownership
>   group** (free agents, rostered). The totals are per list and per group.
> - **B3:** `excludedStale` / `excludedNoTeam` count only players who'd otherwise be in a list
>   (role RISER/FALLER, or streaming-eligible). The payload carries `staleReferenceDate`, the data
>   season's last game.
> - **B4:** for a **completed, non-fallback** season, a row's team is the team of his last game
>   that season (from the `TEAM_*` pairing), and teamless players aren't excluded. Live seasons and
>   fallbacks keep `player.team` with the exclusion (F10).
> - **B5:** the one-game share scores each season's weeks with **that season's league row's**
>   scoring.
> - **B6:** the UI shows `ptsPerMin` on trend rows and "rosters as of {rostersFetchedAt}".
> - **B7:** with a null league status, fetched rosters that are all empty give `NOT_DRAFTED`.
> - Note: a completed season's role lists are labelled "end of the {season} season" too.

> **Amended after review, 2026-10-06** ([plan-review.md](plan-review.md); dispositions in
> [plan.md](plan.md#amended-after-review-2026-10-06)). Rewritten in place, with the changes
> listed here:
> - **Season cutover per list** (F1).
> - **A recency gate, a no-team exclusion and "missed team games"** (F2).
> - **`SEASON_COMPLETE`** (F3).
> - **`rostered` nullable** (F4).
> - **`NOT_DRAFTED` from league status** (F5).
> - **Streaming ranked by season mean** (F7).
> - **One-game note by code, gated per league** (F8).
> - **All-Star excluded** (F9).
> - **`player.team` only** (F10).
> - **Shared owner naming** (F11).
> - **Pooled usage, and missing keys = 0** (F12).
> - **Sport filter on `TEAM_`** (N2), **LIKE escaping** (N3), **`createArrayOf` via `JdbcTemplate`** (N4),
>   **week lookup by number** (N8), **min fetch time** (N9), **its own streaming size** (N11),
>   **a health flag** (N13).

## Migration V28 (append-only; re-check the highest V at build time, V27 on 2026-10-06)

```sql
alter table roster_season add column players text[];
alter table roster_season add column players_fetched_at timestamptz;
```

- **Null** means never fetched since V28. An **empty array** means fetched, with an empty roster.
  Sleeper sends `[]` pre-draft (N5).
- Written by `LeagueHistoryIngestService.ingestStandings` (`:178`) from the `/rosters` response it
  already has: the de-duplicated union of `players`, `reserve` and `taxi`. Each may be null or
  absent. `reserve` is already inside `players`, measured; the union is harmless.
- `players_fetched_at` is the refresh time.
- `RosterSeasonRepository.Upsert` gains `List<String> players` (nullable). It has a single
  producer (measured).
- The repository gains a `JdbcTemplate` so the upsert can bind
  `connection.createArrayOf("text", …)`, as `LeagueRepository:54` does (N4). An IT reads the
  value back (lessons bug class #3).
- **Completed seasons** are skipped by the refresh (`ingestChain:99`), so they keep `players`
  null. That's intended (F3).

New read: `RosterSeasonRepository.rosteredPlayers(long leagueId)` returns `Optional<Rostered>`,
where `Rostered(Map<String sleeperId, Integer rosterId> byPlayer, OffsetDateTime fetchedAt)`.
`fetchedAt` is the **minimum** `players_fetched_at` across rows (N9). It's empty when any row's
`players` is null.

## Owner naming, one implementation (F11)

Extract `static Map<Integer, RosterOwner> ownerNames(List<StandingRow>, List<MemberRow>, Long callerManagerId)`
(team name, then manager name, then "Roster N"; plus avatar and isMe) into a new
`engine/RosterOwners.java`. `SpotlightOwnership.build` calls it, and so does
`PlayerTrendsService`. A test asserts both produce the same name for the same roster.
`WeeklyReportService#forWeek`'s copy is left alone, and that's a named follow-up.

## Season reads

- `PlayerGameRepository.TEAM_ID_PREFIX = "TEAM_"`. Every player read filters by **sport** and
  excludes ids with that prefix. In SQL use `left(sleeper_player_id, 5) <> 'TEAM_'` (no LIKE
  escaping: N3).
- `seasonPlayerGames(sport, season)`: player rows, projecting only `sleeper_player_id`,
  `game_id`, `game_date`, `opponent` and `stats`.
- `seasonTeamGames(sport, season)`: the `TEAM_*` rows, by `game_id`.
- **All-Star exclusion (F9):** `teamCodes` = the suffixes of the season's `TEAM_xxx` ids with a
  non-empty suffix. Player games whose `opponent` isn't in `teamCodes` are dropped (2025's
  `STP`/`STR`, 2024's `CHK`/`SHQ`). The bare `TEAM_` row is never a team.
- **Missing stat keys count as 0** (F12). The turnover key is `to`.

## Which season each list reads (F1)

`dataSeason(list)`:

- the league's season, when at least **half** of `teamCodes` have played at least `cutover(list)`
  games in it (counted from the season's `TEAM_*` rows);
- otherwise the previous season, with `fallback = true`.

`cutover(streaming) = form-min-games (3)` and `cutover(roles) = min-season-games (5)`. One
player's windows never mix seasons. Measured on 2025's opening: half the teams reached 3 games by
10-26 and 5 by 10-29 (review F1). So the fallback lasts the first week-plus, and that's labelled.
The payload carries `streamingSeason`, `rolesSeason` and `fallback` per list.

## Derived values (pure core `PlayerTrendsService.compute(Input)`)

```
games(p)        = p's played, non-All-Star games in the list's data season, by game_date
min(g)          = sp / 60
recentMin       = median(min over the last 3 games)                            null if < 3
seasonMin       = mean(min over all games)                                     null if < min-season-games
minDelta        = recentMin − seasonMin
role            = RISER ≥ +role-threshold, FALLER ≤ −role-threshold, else STEADY (null if minDelta null)
usage(window)   = 100 · Σ_g (FGA+0.44·FTA+TO)·(TmMIN/5) / Σ_g MIN·(TmFGA+0.44·TmFTA+TmTO)   pooled (F12)
                  over games with a team row (team = the TEAM_* row in the game whose suffix ≠ opponent)
pts(g)          = GameScoringService.score(this league's scoring, stats)        (N6: the league's own scoring)
seasonPts       = mean(pts over all games)                                     null if < form-min-games
formPts         = mean(pts over the last form-games (5))                        null if < form-min-games
ptsPerMin       = Σ pts / Σ min
lastGameDate    = date of the last game
stale           = lastGameDate < (latest game date in the data season − recency-days (14))
missedTeamGames = the count of p's team's most recent consecutive games with a
                  TEAM_PLAYED_NO_ENTRY absence for p (player_absence), shown, not ranked (F2)
```

## Lists

- **Eligibility for every list:** not `stale`, and `player.team` non-null (F2, F10). Excluded
  counts are reported: `excludedStale`, `excludedNoTeam`.
- **risers / fallers:** sorted by minDelta (ties → higher recentMin, then id). Top `list-size`,
  plus `risersTotal` / `fallersTotal`.
- **streaming:** players with `rostered == false`, sorted by **seasonPts** desc (ties → higher
  formPts, then id), top `streaming-size`. That's F7: season-to-date mean measured as the better
  predictor (r 0.517 vs 0.476 in 2025, 0.494 vs 0.474 in 2024). Each row also shows formPts,
  minDelta/role, `gamesThisWeek`, `gamesNextWeek` and `missedTeamGames`.
- **`rostered`** (F4) is a `Boolean`, **null** when ownership is unknown (`streamingReason` non-null),
  else whether the id is in `rosteredPlayers`. `rosteredBy` comes from `RosterOwners`.
- **Team and games (F10, N7, N8):** team = `player.team` only (Sleeper's current team, refreshed
  daily; up to a day of lag after a trade). Games come from `ScheduleGridService.forLeague`,
  looking up the column whose `weeks[i].week` equals the current or next week. They're null when
  the league season is complete or the grid is unavailable. The current week's count includes
  games already played, and the label says so.

## Streaming availability (F3, F5)

| Check, in order | streamingReason |
|---|---|
| league status is `complete` | `SEASON_COMPLETE` (no refresh is promised) |
| league status is `pre_draft` or `drafting` | `NOT_DRAFTED` |
| `rosteredPlayers` empty (never fetched) | `ROSTERS_NOT_LOADED` (in season, the next visit refresh fills it) |
| otherwise | null, so streaming is available |

## One-game note, gated per league (F8)

`oneGameCredit`: computed from **this league's** stored weeks, or the chain's previous season if
it has none. It's the share of multi-game starter-weeks whose credited `players_points` equals
exactly one of that week's game scores. When ≥ 90% (hand-set) it sends
`{ code: "ONE_GAME_CREDITED", share, seasonMeasured }`, else null. The web renders the sentence.
The figure for "how much extra games are worth" is one measured constant, placed once in the web
copy with its provenance: "+4% for 4 vs 2 scheduled games, same player, 2025, Ball Knowers"
(review F6). It's shown only when `oneGameCredit` is non-null.

## Config (`draftsim.player-trends`, all ARBITRARY)

`role-threshold-minutes: 6`, `min-season-games: 5`, `form-games: 5`, `form-min-games: 3`,
`recency-days: 14`, `list-size: 10`, `streaming-size: 20`, `one-game-share: 0.9`.
`PlayerTrendsProperties` is registered in `DraftSimApplication`. When the block is absent, the
field is null and the response is `NOT_CONFIGURED`. `/api/health` adds `playerTrendsLoaded` (N13).

## States

| Check, in order | available | reason |
|---|---|---|
| league not visible | — | 404 |
| config absent | false | `NOT_CONFIGURED` |
| not basketball (`playsMultipleGamesPerScoringPeriod()` false) | false | `NOT_BASKETBALL` |
| no player games in the league's season or the previous one | false | `NO_GAMES` |
| otherwise | true | null |

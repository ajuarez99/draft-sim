# Research: NBA minutes trends and streaming candidates

All against the local DB (Postgres 17 on 5433) on 2026-10-06, `origin/main` @ `787abd9`, unless
noted. Each item is **measured** or **reasoned**.

## R1. Is `sp` seconds played? (measured)

All 28,798 (2024) and 29,143 (2025) NBA rows carry `sp`. None carry `min`. Jokić, sleeper id
1658, 2025-10-23 at GSW: `sp` 2450 → 40.83 min, 21 pts, 13 reb, 10 ast, 23 FGA. The published box
score has 40:50, 21 pts on 8-23, 13 reb, 10 ast ([TeamRankings](https://www.teamrankings.com/nba/matchup/nuggets-warriors-2025-10-23/box-score)).
**Decision:** minutes = `sp / 60`. This satisfies design-doc acceptance #1.

## R2. `TEAM_*` rows (measured)

`sp` percentiles over 2025 rows: p5 221, p25 961, **p50 1,481 (24.7 min)**, p75 1,944, **p95
14,400, p99 14,400**. The 14,400 rows are 2,462 rows for 30 ids `TEAM_ATL`…`TEAM_WAS`, about 82
per team. They're team box-score totals (`TEAM_DEN` 2025-10-23: `sp` 15,900 = 265 min with
overtime, FGA 95, FTA 14, TO 13, 131 pts; it really was a 137–131 OT loss). They share `game_id`
with the players' rows. 1,231 of 1,232 games have both teams' rows.

*Amended after review (N1):* the reviewer counted **31** `TEAM_*` ids and 2,463 rows. The 31st is a
bare `TEAM_` from the All-Star game (F9). NFL has `TEAM_*` rows too (N2).

Existing features already keep them out: the Weekly Report payload for NBA 2025 week 5 has no
`TEAM_` string anywhere (measured), because rows without a player record drop out. Nothing in
`backend/src/main` names `TEAM_`. **Decision:** the new reads exclude
`sleeper_player_id like 'TEAM\_%'` explicitly (one named constant), and keep the team rows only
for usage.

**Correction to the design doc (shown, not hidden):** "True usage needs team totals Sleeper
doesn't give" is wrong. The team totals are stored. This branch adds an amended note to
`claude/nba-minutes-trends.md`.

## R3. Which game the league credits, and what game count is worth (measured)

NBA 2025 (league 211), multi-game starter-weeks: credited = the week's **best** game in 1,507 of
2,171 (69%), the worst in 235. On average 0.42 games beat the credited one. The likely rule is
"best game among the days he was in the lineup", but that's a hypothesis. Daily lineups aren't
stored, so it can't be checked.

Credited points per starter-week by games played:

| Games | Weeks | Credited | Avg game | Best game | Credited = best |
|---|---|---|---|---|---|
| 1 | 89 | 26.4 | 26.4 | 26.4 | 100% |
| 2 | 458 | 25.7 | 22.7 | 27.2 | 76% |
| 3 | 966 | 26.8 | 22.3 | 28.7 | 71% |
| 4 | 736 | 27.1 | 21.8 | 29.6 | 63% |

So a 4-game week is worth ~+5% over a 2-game week (27.1 vs 25.7). This isn't controlled for
player quality, and it's one league season.

> **Amended after review (F6), measured by the reviewer:** grouped by the team's **scheduled**
> games (the variable the page shows) and paired **within player**, 2025 gives **+4.4%** for 4 vs 2
> and −1.6% for 4 vs 3. Grouped by games played and within player: 2025 +6.7%, 2024 +10.9%. 2024 has
> no stored schedule. The unpaired table above slightly understates it, because 4-game weeks had
> marginally weaker players. The conclusion stands (extra games help a little). The number the page
> quotes is the scheduled, within-player 2025 one.

**Decision:** streaming ranks by recent per-game form, not by game count. Games this week and
next are shown beside it as context. The page states this measurement in one sentence. The design
doc's "≥ 4 games" filter is dropped, with an amended note on
`claude/nba-schedule-grid-and-streaming.md` view 4.

Side finding for spec 018 (not changed here): NBA "weekly best game" tracks credited points
better than spec 018's "weekly average game" (r 0.993 vs 0.987 in 2025, 0.987 vs 0.983 in
2024; ratio to credited 1.07 vs 0.84). It's a candidate refinement of draft-grade production,
for Allan to decide.

## R4. Where "rostered" comes from (read, plus one measurement)

- `SpotlightOwnership` (spec 014) reads the latest stored scoring week's `players_points` keys.
  Before the first scored week, that's empty, so everyone reads as unrostered.
- `LeagueHistoryIngestService.ingestStandings:178` calls `sleeper.rosters(id)` on every refresh
  and keeps only `owner_id`, wins/losses/points and the champion flag. The `players`, `reserve` and
  `taxi` arrays are thrown away.
- NBA 2026 (league 210) already has 12 `roster_season` rows, so the refresh reaches that loop for
  a pre-season league (measured).

**Decision:** V28 adds `roster_season.players text[]` and `roster_season.players_fetched_at
timestamptz`, written in that same loop as the union of `players ∪ reserve ∪ taxi`. Null means
never fetched. An empty array means the roster really holds no players (pre-draft). Streaming
reads it. Spotlight's switch to it is a named follow-up, not this spec.

## R5. Windows and thresholds (reasoned; one measurement)

> **Amended after review (F2, F7, N12):** the counts below were taken over players with ≥ 8
> games, not the rule's ≥ 5. Streaming now ranks by season mean, not last-5 form (F7: r 0.517 vs
> 0.476 next-week credited, 2025; 0.494 vs 0.474, 2024). There's a recency gate of 14 days (F2).

- **Recent minutes** = the median of the last 3 games played, so one injury-shortened game
  doesn't make a faller (design-doc acceptance #2).
- **Season minutes** = the mean of the season's games played, needing ≥ 5.
- **Role change** = recent − season, with flags at ≥ +6 / ≤ −6 (ARBITRARY, `weights.yml`).
  Measured on 2025: as of 11-15, 30 risers / 16 fallers among 314 players with ≥ 8 games. As of
  12-20, 41 / 33. As of 02-10, 54 / 50. As of 03-20, 58 / 70. So the page shows the top 10 each way
  by size, plus the count beyond, not every flag.
- **Streaming form** = the mean league points over the last 5 games played, needing ≥ 3
  (ARBITRARY).

## R6. Season fallback (reasoned)

> **Amended after review (F1):** "until the season has a game" would empty every list on 10-21.
> Now it's per list: switch once half the teams have played that list's minimum (3 for
> streaming, 5 for roles). In 2025 that was 10-26 and 10-29.

NBA 2026 has no games until 2026-10-20. Week-1 pickups happen 10-11…10-19. **Decision:** if the
league's season has no `player_game` rows for the sport, read the previous season, and say so in
the payload (`dataSeason`, `fallback: true`). The UI labels it "2025 season (2026 hasn't
started)". This is the only cross-season read, and once the season has a game it stops.

## R7. Team for "games this week" (reasoned)

> **Amended after review (F10, N8):** `player.team` only. 139 of 582 players changed teams since
> 2025 and 90 have none. Grid columns are looked up by week number, not by index.

A player's current NBA team is `player.team` (refreshed daily from Sleeper's player list). Games
this week and next come from `sport_schedule` via spec 017's `SportSchedule.counts` rule, through
the same code path `ScheduleGridService` uses, so the numbers can't disagree with the grid
(SC-004). A free agent with no team (null) shows "—".

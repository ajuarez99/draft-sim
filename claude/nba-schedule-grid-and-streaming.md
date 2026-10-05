# NBA schedule grid and streaming

Status: **design, not built.** 2026-10-05. Gap #2 in
[competitor-gap-research.md](competitor-gap-research.md). It's ranked high
because the NBA season starts **2026-10-20** (measured: first game date in
Sleeper's 2026 schedule).

Competitors: Hashtag Basketball (Advanced Schedule Grid, Playoff Schedule,
Schedule By Day, Streaming Grid), Basketball Monster schedule grid, FanScout
schedule grid. Every basketball site has one. No football site does, because an
NFL team plays once a week.

## Why it matters more in these leagues

In a points league with one matchup per week (memory: NBA one game per week is
correct), a player's weekly value is roughly per-game value × games played. A
team with 4 games in a week is worth ~2× one with 2. Week 1 of 2026, measured:
5 teams play 2 games, 24 play 3, 1 plays 4.

## What's there now

- `SleeperPlayerStatsClient.schedule(sport, season)`
  (`ingest/SleeperPlayerStatsClient.java:71`) already calls
  `GET /schedule/{sport}/regular/{season}`.
- Its only caller is `PlayerGameIngestService` (line 153), which parses it to
  map games to weeks during stats ingest and then discards it. **The schedule
  isn't stored.**
- Measured 2026-10-05: `/schedule/nba/regular/2026` → 200, 153 KB, 1,200 games,
  weeks 1–25, each row `{game_id, date, week, home.team, away.team, status}`.
- Rosters: `roster_week_points.players_points` gives who's rostered, as of the
  last scored week. Current-week rosters come from Sleeper's `/rosters`, which
  ingest already reads.

## Proposed design

### Store the schedule

`V27__sport_schedule.sql` (append-only; check the highest V first, since
concurrent sessions exist): `sport_schedule(sport, season, week, game_id,
game_date, home, away, status)`, natural key `(sport, season, game_id)`. It's
refreshed by the existing spec 009 refresh path, since schedules move
(postponements).

The **week** comes from Sleeper's `week` field, not from date arithmetic.
Fantasy weeks and calendar weeks diverge around the All-Star break, so this is
one implementation of the rule, and it's Sleeper's.

### Views

1. **Schedule grid**: 30 teams × upcoming weeks, cell = games that week, colored
   by count with the number printed. Sortable by "games over the next N weeks".
2. **Playoff weeks**: the same grid filtered to the league's playoff weeks
   (`settings.playoff_week_start` onward). This is the most-asked basketball
   planning question, and it costs nothing extra.
3. **Your roster's games this week**: per rostered player, games this week and
   the off-nights. Needs the "which team is yours" join
   ([my-team-dashboard.md](my-team-dashboard.md)).
4. **Streaming candidates**: unrostered players on teams with ≥ 4 games this
   week, ranked by recent per-game production (`player_game` ×
   `GameScoringService`, last N games). This is **realized** data (recent
   form). With projections it would become the waiver assistant, which is
   [projection-tools.md](projection-tools.md)'s job, not this one's.

### Surface

A new league route `/leagues/:id/schedule`, NBA only. Add the gate in
`LEAGUE_DESTINATIONS` / `destinationsFor` (`web/src/destinations.ts:124`,
`:336`). Each destination already carries its `sports`, which is how Analysis is
football-only. Don't restate the rule anywhere else;
`LeagueRailSection.tsx:173` explains why.

## Not building

- Category-league tools (punt analyzer, z-scores). Every ingested NBA league is
  points-scored, and categories would be a different product.
- Daily lineup setting. Sleeper NBA locks weekly in these leagues (unverified
  per league; check `settings` before relying on it).
- An NFL version. A bye-week grid is the only analogue, and it's trivial; add it
  only if asked.

## Acceptance criteria

1. Stored schedule row count = 1,200 for nba/2026, and every team's season total
   = 82 (assert).
2. Grid week-1 counts match the measurement above (5×2, 24×3, 1×4), or the doc
   is amended if Sleeper's schedule changed.
3. Streaming candidates exclude every player rostered in that league
   (cross-check against Sleeper `/rosters`).
4. Live check before 2026-10-20 on the real NBA "Ball Knowers" 2026 league.

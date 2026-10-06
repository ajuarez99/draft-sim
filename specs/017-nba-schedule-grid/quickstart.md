# Quickstart: validating spec 017

This repo's bar: a green suite is necessary, not sufficient. Record each check as
**run** (with output) or **not run** (and why). Expected values below were measured
2026-10-05. If Sleeper has changed them by the time this runs, report the new number
and amend the doc. Don't edit the expectation silently.

## Prerequisites

- Windows dev machine. Postgres on `localhost:5433` (`draftsim`, trust auth), **not**
  5432. Check that it's listening before trusting the backend suite (memory: ITs skip
  silently, so read the skip count).
- Restart `bootRun` after any Java change (no hot reload). Hard-refresh browser tabs
  after restarting vite.
- Leagues: NBA "Ball Knowers" 2026 `1339351318115946496` (2025:
  `1229352720222134272`). NFL "(Foot) Ball Knowers" 2026 `1346366555759341568`. Reader
  `X-Sleeper-User: 1122386008709910528`.

## V1. Unit + integration suite

```bash
cd backend && ./gradlew test
```

Expect green, **and** a skip count that doesn't include the new
`SportScheduleRepositoryIT`. Then:

```bash
cd web && npx tsc -b && npm test -- --run && npm run build
```

## V2. Schedule is stored by the normal refresh (SC-001)

Trigger the NBA 2026 league's refresh the way a visit does (open `/leagues/1339351318115946496`
or `POST /api/leagues/1339351318115946496/refresh`), then:

> **Amended after review (F11):** the POST needs `X-Sleeper-User` (no header → 404). It
> only *starts* a run if the last success is over 1 h old (`STALE_AFTER`), and it's
> asynchronous. So:
> ```bash
> curl -s -X POST -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1339351318115946496/refresh
> curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1339351318115946496/refresh
> ```
> Repeat the GET until `state` isn't `RUNNING`. If the answer is FRESH from a run
> before the build, wait out the hour or use V4's admin route for season 2026.
> Then run the SQL.

```sql
select count(*), count(distinct game_id), min(week), max(week), max(fetched_at)
from sport_schedule where sport = 'nba' and season = 2026;
```

Expect `1200 | 1200 | 1 | 25 | <just now>`. Cross-check against a fresh fetch of
`https://api.sleeper.app/schedule/nba/regular/2026` (row count equal).

## V3. Empty answer doesn't wipe (FR-002)

Covered by `SportScheduleRepositoryIT` / an ingest unit test with a stubbed client
returning `[]`. Stored count is unchanged afterwards, and a warning is logged.

## V4. 2025 counting rule on real data (SC-003)

The fixture test asserts it. Live as well. *Amended after review (F11):* admin routes
fail closed when `ADMIN_TOKEN` is blank (`application.yml:35-38`), and
`.claude/launch.json`'s `bootRun` doesn't set it. Start the backend with `ADMIN_TOKEN`
set and use the same value as `$ADMIN`. Also expect `seasonOver: true` and
`lastLeagueWeek: 21` for this finished league (F2).

```bash
curl -s -X POST -H "X-Admin-Token: $ADMIN" "localhost:8080/api/ingest/player-games/1229352720222134272?season=2025"
curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1229352720222134272/schedule
```

Expect 30 teams, `seasonTotal` 82 for all except NYK and SAS (83), no STP/STR, and
`excluded` = `{postponed: 3, canceled: 1}`.

## V5. Week-1 grid (SC-002) and playoff window (SC-004)

```bash
curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1339351318115946496/schedule
```

Week index 0: 5 teams with 2, 24 with 3, 1 with 4. `currentWeek` 1.
`playoff` = `{startWeek: 20, endWeek: 22, reason: null}`. The 2025 league gives 19–21.

## V6. Schedule week = league leg (spec Assumption, direct check)

> **Amended after review (F6):** the query that used to be here compares Sleeper's
> stats-entry week with its schedule week. Both are the NBA calendar's numbering, so
> it can't catch a league week that covers a different schedule week. The real check
> compares **league** weeks to schedule weeks. For NBA 2025 (league 211 locally),
> every player who scored in league week *w* should have a game in schedule week *w*:
>
> ```sql
> with scored as (
>   select rwp.week, p.key as pid
>   from roster_week_points rwp, jsonb_each_text(rwp.players_points::jsonb) p
>   where rwp.league_id = 211 and p.value::numeric > 0
> )
> select count(*) filter (where not exists (
>          select 1 from player_game pg
>          join sport_schedule s on s.sport = 'nba' and s.season = 2025 and s.game_id = pg.game_id
>          where pg.sport = 'nba' and pg.season = 2025
>            and pg.sleeper_player_id = scored.pid and s.week = scored.week)) as mismatched,
>        count(*) as total
> from scored;
> ```
>
> Expect `mismatched = 0`, or a small count explained player by player. Check the
> `roster_week_points` column names and the `players_points` type when it's run; this
> is written from the review, not executed. The query below stays as a **separate
> sanity check**: it only shows that the stats week and the schedule week agree.

```sql
select count(*) filter (where s.week <> pg.week) as mismatched, count(*) as total
from player_game pg
join sport_schedule s on s.sport = 'nba' and s.season = 2025 and s.game_id = pg.game_id
where pg.sport = 'nba' and pg.season = 2025;
```

Expect `mismatched = 0`. The column names were checked against `V20__player_game.sql`.
The query itself hasn't been run: nothing stores `sport_schedule` yet.

## V7. Next matchup (US3, SC-005)

```bash
curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1339351318115946496/next-matchup
curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:8080/api/leagues/1346366555759341568/next-matchup
```

- NBA 2026 before pairings are published: `available: false`, week 1, the "aren't out
  yet" reason.
- NFL: the opponent equals Sleeper's
  `/v1/league/1346366555759341568/matchups/{leg}` partner for the reader's roster, and
  equals what NFL league home showed **before** this change (take a screenshot first).
- No header: 404.
- *Added after review (F1):* NBA 2026 reports `season: 2026` and `week: 1`, not 2025's
  "regular season is over", even though 2025 is the newest season with scores.
- *Added after review (F7), NFL:* run before *and* after on a mid-week day **and on a
  Tuesday**. Until R6's boundary measurement is recorded, NFL home stays on its old
  block, so this compares the endpoint's answer with the block.
- *Added after review (F11): dated post-draft check, 2026-10-11 to 10-19.* The NBA
  draft starts 2026-10-10 21:15 UTC (measured). After a visit refresh, `league_matchup`
  holds week 1 for league 210, and next-matchup names the same partner as
  `https://api.sleeper.app/v1/league/1339351318115946496/matchups/1`. Known
  pre-existing gap (N12): `ingestRemainingFixtures` skips weeks already paired, so a
  commissioner regenerating the schedule before week 1 leaves stored pairings stale.
  If they disagree, check that first.

## V8. Browser (SC-006, bug class #6)

Through vite (`npm run dev`), signed in as the reader (real header path, not curl):

1. NBA league rail shows "Schedule grid". NFL league rail doesn't.
2. The grid renders 30 rows. Week-1 cells print counts. Sorting "Next 1 week" puts
   **PHI** first (the one 4-game team, measured; amended after review F11). The
   "Playoff weeks" view shows 20–22 with totals. Columns stop at week 22 (F2).
   - *Added after review (F8):* on a first visit with an empty `sport_schedule`, the
     grid goes from "not loaded yet" to filled **without a manual reload** once the
     visit's refresh finishes.
3. At phone width (375px): no page-level horizontal scroll. The table scrolls inside
   its container, and the team column stays visible.
4. Dark and light themes are both legible.
5. NBA league home shows the next-opponent block (the "not out yet" text today). Its
   link goes to the Schedule grid, not Team strength (F9). NFL league home is
   unchanged, with the projected line still present.
6. Console: no errors, no CORS failures.

Repeat 1–2 on production after deploy and before **2026-10-20**.

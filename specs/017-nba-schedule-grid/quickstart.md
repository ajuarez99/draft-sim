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

The fixture test asserts it. Live as well:

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

## V8. Browser (SC-006, bug class #6)

Through vite (`npm run dev`), signed in as the reader (real header path, not curl):

1. NBA league rail shows "Schedule grid". NFL league rail doesn't.
2. The grid renders 30 rows. Week-1 cells print counts. Sorting "Next 1 week" puts the
   one 4-game team first. The "Playoff weeks" view shows 20–22 with totals.
3. At phone width (375px): no page-level horizontal scroll. The table scrolls inside
   its container, and the team column stays visible.
4. Dark and light themes are both legible.
5. NBA league home shows the next-opponent block (the "not out yet" text today). NFL
   league home is unchanged, with the projected line still present.
6. Console: no errors, no CORS failures.

Repeat 1–2 on production after deploy and before **2026-10-20**.

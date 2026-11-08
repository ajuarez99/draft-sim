# Quickstart / validation guide: spec 056

> **Renumbered 2026-11-06:** this feature's migration is now `V46__historical_nfl.sql` (057 keeps V45, Allan's call; 057 merges first). See plan.md. "V45" below means this migration under its old number.

## Prerequisites
- A scratch Postgres for this branch: docker `draftsim-pg-056` on **5439**, trust auth, db/user
  `draftsim`. Never use 5432, and don't use the shared 5433 (V45 alters 053 tables).
- Launch entries `056-api` (port 8156, `ADMIN_TOKEN=local-056`, datasource → 5439) and `056-web`
  (5256, proxy → 8156) in the **main checkout's** `.claude/launch.json`. preview_start reads
  main's file (memory: worktree preview serves main), so check the bootRun classpath points
  at this worktree.

## 1. Import
```bash
curl -s -X POST localhost:8156/api/admin/historical/import -H 'X-Admin-Token: local-056' -H 'Content-Type: application/json' -d '{"part":"NFLVERSE"}'
```
Poll `GET /api/admin/historical/import/{jobId}` until DONE. Expected: 27 seasons
(1999–2025). 2000 shows `games 246 / expectedGames 248`, 2022 shows `271 / 271`, and 2005
shows `droppedRows 17`.

Also run the NBA import from 053's quickstart, or a synthetic NBA season, so SC-008 can be
checked on the same database.

## 2. Play a football game (US1)
`POST /api/history/games {"sport":"NFL","userSlot":4}` (seasonOverride through the test
hook for a fixed year). In the browser: `/history`, choose Football, draft, then auto-finish.
Check:
- the board has only QB/RB/WR/TE, labeled "ranked by last season's points", and no "ADP" anywhere (SC-006)
- the cards show the prior season only
- the attribution is visible, and the 2000 data note is shown on a 2000 game
- the results have 17 weeks (18 from 2021), standings, and the hindsight/bust/steal

## 3. Hand checks
- **SC-003**: for one team-week in seasons ≤2009, 2010–2020 and ≥2021, recompute the best
  lineup by hand from `historical_nfl_player_week` and compare it with
  `result.lineups[slot][week]`.
- **SC-004**: SQL comparing computed PPR against `src_ppr` per row for three seasons, then
  report the share within 0.01.
- **SC-005**: one 2015+ season's per-player season PPR against Sleeper's
  `/v1/stats/nfl/regular/{season}` `pts_ppr`, for players with 50+ points. Report the share
  within 1 point and list the misses.
- **SC-007**: for every same-name pair found at import, the board holds two cards with
  different ids.
- **SC-008**: an NBA game created before V45 reopens with byte-identical `result`, and a new
  NBA game still plays.

## Deploy order (amended after code review B1/N6/N7)
**Web first, then backend, then the NFLVERSE import. Do not reverse the first two.**
1. **Web.** Safe against today's 053 backend: the new pages read a missing `sport` as basketball, a missing
   `dataNotes`/`attribution` as none, and show **no Football choice** (the seasons request for NFL comes back as
   NBA rows with no `sport`, which don't count). Covered by `web/src/pages/HistoricalOldBackend.test.tsx`.
2. **Backend.** V45 runs on boot and `sport` becomes required (no default, on purpose: a defaulted sport would
   assert a rule). The OLD web cannot start a game against the new backend (`GET /api/history/seasons` with no
   sport and `POST /api/history/games` with no sport are both 400, measured), so the backend must not go first.
   For the few seconds a rolling deploy runs the old container after V45, its `insert into historical_game` (no
   `sport`) fails NOT NULL: one 500 on a create, nothing worse (N6).
3. **Import.** Run `{"part":"NFLVERSE"}` (step 1 above). Football appears in the web only once
   `GET /api/history/seasons?sport=NFL` returns at least one row that says `"sport":"NFL"` (the seasons gate), so
   there is no window where the Football choice is live but starting a game answers 409 (N7).
Before V45, count `historical_game` rows in prod (SC-008).

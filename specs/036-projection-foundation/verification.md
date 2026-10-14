# Verification: spec 036, US1 build pass (T001-T013)

Date: 2026-10-14. Everything below was **run** unless it says "assumed". Database: `draftsim_036`
on the 5433 cluster (never the shared `draftsim`). Command: `DB_URL=jdbc:postgresql://localhost:5433/draftsim_036 ./gradlew.bat test`
(see quickstart §0). Migration applied there: V36 `projection snapshot`.

## Tests (run)

| Run | Tests | Failed | Skipped |
|---|---|---|---|
| New/touched only: `ProjectionSnapshotServiceTest` (9), `ProjectionSnapshotRepositoryIT` (4), `DailyRefreshServiceTest` (18), `IngestControllerTest` (12) | 43 | 0 | 0 |
| Full suite, `draftsim_036` **empty** (migrated only) | 1762 | 12 | 34 |
| Full suite, `draftsim_036` **populated** with a data-only copy of the shared DB's rows | 1764 | 1 | 1 |

The empty-DB run is why the skip count matters: 34 ITs skipped or failed purely because there was no
board, league or player_game data (`TargetControllerIT` 8, `PlayerStats*ReadIT`, `ScheduleSwapIT`, ...).
Populating the DB (read-only copy through `COPY`, sequences reset) took it to 1 skipped, 1 failed.

The populated run's remaining items, both **unrelated to this spec** (assumed, not proven on main):
- `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries` hard-codes
  `2026-10-10T19:15:08Z` and reads `2026-10-10T21:16:19Z` from the copied data (the draft's stored
  `start_time` changed after the test was written). Data-dependent.
- `PlayerStatsLeaderboardReadIT.aSeasonThatFellBackReadsTheRequestedSeasonsDraft...` skips: "the 2026 draft has been held since this was written".

`RefreshControllerDailyIT` failed on the first run (expected 7 steps, got 9): the new step. Fixed; it also
now mocks `ProjectionSnapshotService` so the IT can't hit Sleeper.

The IT freeze proofs (`ProjectionSnapshotRepositoryIT`, ran, 0 skipped): late insert rejected by the CHECK
(raw SQL and through the repository); an open row refreshed in place with `cutoff_at` unchanged; a frozen
row unchanged by a direct `UPDATE` (0 rows affected, also when the update tries to move `cutoff_at`) and by
`upsertPreGame` (reported `frozenSkipped=1`, stats unchanged); a batch with a repeated key deduplicated.

## Live capture against real Sleeper (run, `draftsim_036`, server on port 8136, stopped afterwards)

`POST /api/ingest/projection-snapshot?sport=...`:

| sport | season | weeks | rowsWritten | rowsFrozenSkipped | rowsNotPreGame | time |
|---|---|---|---|---|---|---|
| nba | 2026 | [1] | 936 | 0 | 0 | 0.65 s |
| nfl | 2026 | [6] | 402 | 0 | 0 | 0.22 s |

Re-run the same minute: identical counts, table stays at 936 + 402 rows (refreshed in place, no growth).

```sql
select sport, count(*), min(game_date), max(game_date), bool_and(fetched_at < cutoff_at) from projection_snapshot group by sport;
-- nba  936  2026-10-20  2026-10-25  t   (43 games, 326 distinct players)
-- nfl  402  2026-10-15  2026-10-19  t   (14 games)
```

Cutoffs, checked in America/New_York: every NBA row at 12:00 ET (six dates, 10-20..10-25), every NFL row at
09:00 ET (10-15, 10-18, 10-19). 0 rows with an `adp_` key in `stats`.

## Found during build

1. **Plan S2's "529 of 936 players have a season average" compared unlike things.** 529 is the row count
   of `player_season_projection` (players); 936 is player-GAME rows in NBA week 1. Measured: those 936 rows
   are 326 distinct players, and **936 of 936 rows join to a season-average line**. So `withoutSeasonAverage`
   will read 0 at launch, not ~400. (Matters for US2 tests/copy; nothing in US1 depended on it.)
2. **Interrupted-test litter can hijack the season gate.** `ProjectionSeason.current` takes the newest season
   of any league of the sport (the same rule spec 030 already used). Spec 014's IT leaves leagues with
   season 2099/2098 in the DB (`it-014-nba-league`, `it-014-nfl-league`); on the first live call the capture
   answered `season 2099, weeks []` and wrote nothing. Not a prod problem (prod has no such rows), and
   pre-existing for `SEASON_PROJECTIONS`. I deleted those 3 rows from `draftsim_036` and re-ran. Not fixed in
   code: changing "newest season" semantics is outside US1.
3. **Test-datasource facts the plan didn't state.** ITs read `System.getenv("DB_URL")` for reachability and
   Spring reads the same `DB_URL`, so the env override alone is enough. Several ITs hard-code
   `localhost:5433/draftsim` only for the reachability probe (e.g. `RefreshControllerDailyIT`), which does not
   redirect their real datasource.
4. Shared DB drift (informational): the shared `draftsim` has V34 applied (a column on `league_transaction`
   that this worktree's tree doesn't have); copying its rows needed an explicit column list for that table.
   This worktree has no V34/V35 file; the numbering gap (33, 36) is legal for Flyway.

## Not done / assumed

- Not measured: `POST /api/refresh/daily` wall-clock (plan N3 / T033). The step's own cost is the two
  captures above (~0.9 s together), so the 180 s curl timeout concern looks remote, but the whole `runAll`
  was not timed.
- The `PROJECTION_SNAPSHOT` daily step was verified by unit test and the mocked IT, not by a real
  `/api/refresh/daily` call.
- A late-cutoff capture (`now` past a cutoff) is proven by unit test (counted `rowsNotPreGame`, never sent)
  and IT (table refuses), not by the live route, which always uses the real clock.
- Prod `flyway_schema_history` not inspected (plan Risk 1); the V36 number is unconfirmed for merge.

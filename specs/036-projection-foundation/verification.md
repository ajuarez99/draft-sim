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

---

# Verification: spec 036, second build pass (T014-T033)

Date: 2026-10-14. Same database rule: `draftsim_036` on 5433 (never the shared `draftsim`), server on port 8136
with local-only `ADMIN_TOKEN` / `REFRESH_SECRET` env values, stopped afterwards. Everything below was **run**
unless it says "assumed" or "not run".

## Tests (run)

| Run | Tests | Failed | Skipped |
|---|---|---|---|
| New backend: `ProjectionAccuracyServiceTest` 14, `ProjectionParityTest` 6, `LeagueAnalysisExactScoringTest` 3, `ProjectionAccuracyReadIT` 4, `IngestControllerTest` 13 | 40 | 0 | 0 |
| **Full backend suite**, `draftsim_036` populated with the data copy | 1792 | 1 | 1 |
| Web `npx tsc -b` | clean | | |
| Web `npm run build` | built, 4.9 s | | |
| Web `npx vitest run` (125 files) | 2118 | 0 | 0 |

The backend failure and skip are the **same two items as the first pass**: `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`
(hard-coded start time vs the copied data) is still the only failure, and `PlayerStatsLeaderboardReadIT` still skips 1 ("the 2026 draft has been held"). 1792 tests ran against a
populated DB, so the ITs were not silently skipped (skip count 1, not 34).

**Mutation check on the mandatory ordering test (bug class #1):** replacing `ProjectedCategories.restrict(scoring, projectedKeys)` with the full
scoring map in `ProjectionAccuracyService.compute` made `aBonusCategoryNoProjectionCarriesDoesNotBuildRunsLowIntoTheLean` fail (it expects lean 0.0; without
the restriction the sample scores projected 25 vs actual 27, lean -2.0). The restriction was restored and the test passes.

`ProjectionAccuracyReadIT` (T015 / SC-003) seeds season 2020 of nba under its own league, deletes it afterwards, and checks every figure against the hand computation in
its class comment: GAME overall n=4, MAE 4.75, lean -1.75; SEASON_AVERAGE MAE 7.25, lean -2.75; PG n=3 MAE 4.67 lean -0.67; 0-20 band n=2 MAE 5.5 lean +0.5;
DNP 1, pending 1, voided 1, withoutSeasonAverage 1; excluded `bonus_pt_40p`; scoredThrough and snapshotsFrom. The `forAccuracy` SQL was also run by hand
(`explain analyze`) before it was wired in. It passed on the first run, so the comment's numbers are the hand ones, not copied from output.

## T031: football parity, NFL 2026 weeks 7 and 8 (run)

`POST /api/ingest/projections?sport=nfl&season=2026&fromWeek=7&toWeek=8&force=true` stored 856 rows (now with `stats`). Then
`GET /api/ingest/projection-parity`. Scoring source (automatic pick, lowest id with `rec = 1.0`): league `1391509063170293760` ("fantasy", 2026). The tolerance per row
is the S6 rule, fixed before the run.

| Week | rowsWithStats | rowsWithoutStats | withinTolerance | beyondTolerance | max abs diff |
|---|---|---|---|---|---|
| 7 | 424 | 47 | 396 | 28 | 2.04 |
| 8 | 432 | 36 | 404 | 28 | 2.04 |

**Diagnosis of the beyond-tolerance rows (run, not assumed): all 28 in each week are kickers.** Sleeper's projected K line carries a combined
`fgm_50p` bucket (e.g. 0.4 made 50+ yard field goals). Every stored NFL league's scoring map uses `fgm_50_59` and `fgm_60p` instead and has no `fgm_50p`
key, so `GameScoringService` (which iterates the scoring keys) never scores that stat. Adding `5 * fgm_50p` back to each of the 28 week-7 rows leaves residuals
of -0.04..+0.04 (rounding), so a weight of 5 for `fgm_50p` explains the gap. That weight is **fitted to explain the rows, not an independent check**.
Consequence for the flag: with `nfl-exact-scoring` on, every projected kicker would lose roughly 2 points against the PPR column pick, because a long field goal the
league does score under another key name is projected under a key it doesn't know. Every non-K row (396 and 404) matched within its rounding tolerance.
**The flag was left false.** Whether to flip it (with a K key mapping, or with kickers kept on the column pick) is the parent's decision.

`rowsWithoutStats` (47 and 36) are `player_projection` rows from before this pass that Sleeper's feed no longer lists: the forced re-ingest only upserts what the feed returns
and never deletes the rest. That is pre-existing behaviour (a player who drops off the feed keeps his old projection), noticed here and not changed.

## T033: costs (run)

- `POST /api/refresh/daily` against `draftsim_036`, real Sleeper: **4.59 s total**, HTTP 200, 9 steps, both `PROJECTION_SNAPSHOT` steps `DONE`
  (nfl `rowsWritten=402`, nba `rowsWritten=936`, `rowsNotPreGame=0`). The snapshot step is about 0.3 s for NBA by log timestamps. A "before" run without the steps was
  **not run**, so the added cost is read from the log, not from a before/after. Well under the plan's 30 s trigger and the workflow's 180 s `--max-time`.
- `/analysis` (warm, 3 calls each): NBA league 210 about 0.08-0.10 s (first call 0.43 s), NFL league 4 about 0.09 s (first call 0.23 s), including the accuracy summary, which at
  today's 936 / 402 snapshots reads TOO_EARLY n=0. `/projection-accuracy` alone: NBA 0.036-0.055 s, NFL 0.020-0.022 s.
- **Full-season scale (synthetic):** I inserted 29,143 snapshot rows for NBA 2025 shaped to `player_game` (a timing fixture, not an accuracy fixture; deleted afterwards)
  and read `/projection-accuracy` for league 211: **1.4-1.6 s per read uncached, 1.9 s for a cold first call**. That is over both the plan's 1 s goal and its 200 ms
  trigger, so the cache the plan reserved for this case **was added** (`ProjectionAccuracyService.forLeague`): keyed by league, fingerprinted by
  `ProjectionSnapshotRepository.stamp` (max fetched_at and count of snapshots and `player_game`, max fetched_at of `sport_schedule`) plus the scoring map's hash; 64 entries, LRU.
  Cached read: **0.029-0.032 s**. The fingerprint query costs about 30 ms in psql. The IT proves a stat correction invalidates it. Not stamped: a player's position changing.
  The cold read after any data change still costs 1.4-1.9 s at full-season size; with the cache that happens once per change, not once per page view.
- 404 for a non-member on `/projection-accuracy`: run (`X-Sleeper-User: nobody` gives 404).
- `/season-start` strength block: `accuracy` is present and equals the Analysis page's summary for the same league (NBA: TOO_EARLY, n 0, minSamples 500, SEASON_AVERAGE).

## Plan points found wrong or needing a decision (with evidence)

1. **Exact scoring does not reproduce `pts_ppr` for kickers under any stored league's key names** (above). The "0 mismatches or every mismatch explained" rule is met only through that explanation.
2. **Plan N3's 200 ms cache trigger fired** (1.4-1.9 s at 29k samples). The plan said "decided in verification"; decided: cache.
3. **An empty season is a real object** (`NO_SNAPSHOTS`, or `TOO_EARLY` with n=0), not null. The UI renders `NO_SNAPSHOTS` and null identically ("not measured").
   `contracts/accuracy-api.md` said null only on failure; that still holds.
4. `AccuracySummary` gained `kind` and `label` (plan S1: "the summary's label says which series it is"); `contracts/accuracy-api.md` listed neither.
5. No NFL `player_game` rows exist in the local copy, so football accuracy ran live only to the pending stage (402 pending, TOO_EARLY n=0). Football realized grading is covered by a unit
   sample and the ordering tests, **not** by real football box scores. Whether prod has NFL `player_game` was not checked.

## Not run / assumed

- No browser check of the new panel or the source lines (the parent session owns the live UI pass). Vitest renders the panel and the source line in jsdom only.
- The "See the accuracy breakdown" link is a plain `#projection-accuracy` anchor under the basketball notes. Football's Analysis page has no notes block (spec 030), so it has no link, only the panel.
- `README.md` / `HANDOFF.md` not touched (T034 skipped on request).
- Prod `flyway_schema_history` still not inspected (plan Risk 1).

## Review findings (parent session, 2026-10-15)

- **F1 (bug, fixed): bucket families were graded partially.** `ProjectedCategories` kept a scoring key only if some projection line carried it. A projected NFL DEF line
  carries exactly one bucket at 1.0 (e.g. `"pts_allow_21_27": 1.0, "yds_allow_350_399": 1.0`), chosen from its mean. League 1346366555759341568 scores `pts_allow_0`=10,
  `pts_allow_1_6`=7, `pts_allow_7_13`=4, `pts_allow_14_20`=1, `pts_allow_28_34`=-1, `pts_allow_35p`=-4 (measured in `draftsim_036`). Only 14_20 and 28_34 appeared on any
  projected line, so those two were graded and a realized shutout's +10 was not: DEF error depended on which buckets this season's projections happened to touch. Kickers:
  projections carry `fgm_50p`; leagues score `fgm_50_59` and `fgm_60p`. Fix: keys matching `pts_allow_*`, `yds_allow_*`, `fgm_<digit>*`, `fgmiss_<digit>*` form a family; a
  family is graded only if every nonzero-weight member the league scores is carried by some line, otherwise every member is excluded and listed in "Not graded". Plain
  `pts_allow`, `yds_allow`, `fgm`, `fgmiss`, `fgm_yds` are not members. Non-family keys and all NBA output unchanged (existing tests pass unchanged). Tests: `ProjectedCategoriesTest`
  (partial family, full family, kicker) and `ProjectionAccuracyServiceTest` (DEF 14_20 projected vs realized shutout: lean 0; pre-fix it would have been +1.0).
- **F2 (bug, fixed): FR-010/SC-006.** The football Analysis page rendered no `ProjectionNotes`, so it named no projection source. It now renders for football too; football's
  notes are the source/accuracy line only (football's `unprojectedCategories` is always empty). Test in `LeagueAnalysis.test.tsx`.
- **F3 (nit, fixed):** the panel said "Projections kept since Oct 20", but `snapshotsFrom` is the earliest GAME date covered, not when capture began (captures began Oct 14).
  Now "Covers games from Oct 20".
- Contract `accuracy-api.md` updated to list `kind`/`label` on the summary and `withoutSeasonAverage`, `voided`, `excludedCategories` on the payload (this closes item 4 above).

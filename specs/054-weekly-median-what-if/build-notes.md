# Build notes: spec 054 (weekly median what-if)

Scratch Postgres for this spec: `docker run -d --name draftsim-pg-054 ... -p 5439:5432 postgres:16`
(trust auth, db/user `draftsim`). Backend runs use `DB_URL=jdbc:postgresql://localhost:5439/draftsim`.
Existing ITs that hardcode `localhost:5433` (e.g. LeagueHistoryCanCommissionIT) still SKIP; ITs that
read `DB_URL` run. `web/node_modules` was absent in the worktree: `npm ci` was run there.

## T001 baseline (before any change)

- `cd backend && DB_URL=jdbc:postgresql://localhost:5439/draftsim ./gradlew test --tests '*ExpectedWins*' --tests '*LeagueHistory*'`
  BUILD SUCCESSFUL. tests=36 failures=0 errors=0 **skipped=7**
  (LeagueHistoryCanCommissionIT 3/3, ExpectedWinsServiceBoundTest 3/3, LeagueHistoryContaminationIT 1/1: all hardcode 5433 / no DB_URL).
- `cd web && npx tsc -b && npx vitest run src/pages/LeagueHistory.test.tsx`: tsc clean; 1 file, 29 passed, 0 failed.

## Deviations from tasks.md / plan

- `MedianWhatIfService.whatIf` takes `Optional<PlayoffFormat>`; an absent format is treated as `NO_REGULAR_SEASON_BOUND` (the plan did not say).
- `gamesCounted` is the number of DISTINCT WEEKS a roster appears in, not its number of games, so invariant 1
  (`median W+L+T == gamesCounted`) holds even in the contrived odd-roster test where one roster is in two games a week.
  The repository's `pairedWithScores` never produces that shape; the unit test builds it by hand.
- `playoffTeams` on the block is null whenever the line is withheld (including `ORDER_DISAGREES`), per data-model "null when not modelable".
- `ORDER_DISAGREES` treats "no stored bracket / incomplete placements" as disagreement (cannot be confirmed), per plan amendment #4.
- `driftGames` is 0 under `COUNTED_GAMES` (drift is defined against the official record, only used under `OFFICIAL`).
- `LeagueHistoryMedianIT` reads `DB_URL` (like WeeklyPotControllerIT) rather than hard-coding 5433, so it can run against the 5439 scratch DB.
- `PowerComputeFinalityTest` constructs `LeagueHistoryController` positionally; one `null` was appended for the new `MedianWhatIfService` argument.
- `StandingsTable` lost its `season` prop (nothing used it once the expected-wins season match went away).
- Web: `web/node_modules` was absent in the worktree; `npm ci` was run (untracked/ignored output).

## T016 final results

- `cd backend && DB_URL=jdbc:postgresql://localhost:5439/draftsim ./gradlew test` (full suite):
  **BUILD FAILED**, 2545 tests, **5 failed**, **330 skipped**.
  - The 3 new/changed-by-this-spec test classes pass: `MedianWhatIfServiceTest` (all), `LeagueHistoryMedianIT` (3/3, ran, not skipped), `ExpectedWinsServiceTest` + `EngineOutputFrozenTest` (green).
  - The 5 failures are all in files this spec did not touch, all environmental on the empty scratch DB:
    - `PlayerSpotlightTopOfNightIT`, `PlayerSpotlightWeekIT`: hard-code `localhost:5433` and ERROR (not skip) when it is down ("Connection to localhost:5433 refused").
    - `LeagueRepositorySuccessorIT` (2 tests): expects real ingested NBA leagues (2024-2026) the scratch DB does not have; re-run alone, still fails the same way.
    - `EspnRefreshOnVisitIT`: failed in the full run on a cleanup FK violation (`draft_pick_manager_id_fkey`, leftover rows from another IT sharing the DB); re-run alone it PASSES, so it is order/state dependent.
    - Not compared against a pre-change full run (no baseline full run was done; switching branches/stashing was forbidden), so "pre-existing" is inferred from the causes above, not measured.
  - The 330 skipped are ITs that hard-code `localhost:5433` for their reachability check (listed per suite in the run output); they did NOT run. Only ITs that read `DB_URL` ran against 5439.
- `cd web && npx tsc -b && npm run build && npx vitest run`: tsc clean; build OK; vitest 156 files, 2733 tests, all passed, 0 failed.
  `LeagueHistory.test.tsx`: 38 tests (29 before; the 3 expected-wins tests rewritten against `season.median`, 12 added).

# T001 / T002: local data and baselines (2026-11-17)

## Database

- The Postgres 14 cluster on `localhost:5433` was down and was started (data dir per the memory note). The Postgres on 5432 was not touched.
- **This feature uses its own database, `draftsim_074`**, cloned from `draftsim` with `createdb -T draftsim`. Reason: V50 applied to the shared `draftsim` would make Flyway refuse to start for every other session whose branch has no V50. Run the backend for this feature with `DB_URL=jdbc:postgresql://localhost:5433/draftsim_074`.
- Measured in the clone:
  - `historical_season`: NBA 47 seasons, 1970 to 2016. **No NFL seasons.**
  - Flyway at V49.
  - `historical_game`: 3 rows, all COMPLETE.

## What this means for verification

- Basketball season pages can be checked locally for 1970-71 to 2016-17 only (production offers more).
- Football season pages cannot be checked locally until an NFL historical import is run into `draftsim_074`. Until then anything said about NFL pages is from the fixture or from code, and is labelled so.

## Baselines (untouched branch, origin/main 81c780e)

- Web: 201 test files, 3295 tests pass (run by the plan reviewer, 2026-11-17).
- Backend, run 2026-11-17 against draftsim_074 on untouched code: **2974 tests, 12 failed, 48 skipped** (BUILD FAILED). The 12 failures exist before this feature and are the baseline to compare against, not regressions: AccessControlMvcIT.draftRoutesAre404WithNoIdentity, AdminDigestControllerIT.anExplicitDateIsAccepted, eight in TargetControllerIT, two in LeagueRepositorySuccessorIT. Why they fail on this database was not investigated (likely missing local league data; that is a guess).

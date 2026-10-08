# Verification log: spec 022

Results are recorded as they are measured, with the date. **Measured** means the step was run and
the number is shown. **Not run** means it wasn't, and the reason is given.

## T005: baselines before any refactor (2026-10-07, measured)

- **Backend**: this worktree's code (`draft-sim-022`, branch `022-player-stat-analysis` at
  `0c31c3c`, code identical to `origin/main` `2c72ead`).
  - Run with launch entry `draft-sim-022-api-8092` (port 8092, `REFRESH_ONVISIT_ENABLED=false`, so
    no refresh rewrites data between baseline and gate).
  - Classpath checked in the boot log:
    `C:\Users\allan\source\draft-sim-022\backend\build\classes\java\main`.
- **Backend suite**: `./gradlew test` gave BUILD SUCCESSFUL. Summed from the JUnit XML: **1,346
  tests, 0 skipped, 0 failures, 0 errors.** Postgres on 5433 was up, so no IT was silently
  skipped.
- **Trends baselines**: captured with `X-Sleeper-User: 1122386008709910528`, a member of NBA 2025
  and 2026, then normalised. `rostersFetchedAt`, `currentWeek` and `staleReferenceDate` were
  deleted and keys sorted recursively (N13).

| League | Season | HTTP | Raw size | Time | Content |
|---|---|---|---|---|---|
| `1339351318115946496` | 2026 | 200 | 8,076 B | 1.55 s | roles/streaming fall back to 2025; 10 risers, 10 fallers; streaming `NOT_DRAFTED` |
| `1229352720222134272` | 2025 | 200 | 8,168 B | 1.58 s | 10 risers, 10 fallers; streaming `SEASON_COMPLETE` |

  - Saved to `baseline/player-trends-2026.json` and `baseline/player-trends-2025.json`.
  - The 1.55–1.58 s per request is Trends' cost **before** the cache. It is a pre-change reference
    for V9, not a target.

## After T002–T014: interim gate (2026-10-07, measured)

- **Suite**: 1,380 tests, 0 skipped, 0 failures, 0 errors (reported by the T008–T014 agent; the
  final full count is re-taken at T017).
- **Scoring parity (I1)**: `GameScoringParityIT` made 78,572 comparisons with **0 mismatches**,
  covering NBA 2025 (26,680 games × 1 league) and NFL 2025 (25,946 games × 2 leagues). Equality is
  exact (`Double.compare`).
- **Trends output (V1)**: after normalisation, both leagues' `player-trends` JSON is
  **byte-identical** to the T005 baselines (`cmp`).
- **Trends timing, with the cache**:

| League | Cold | Warm | Before the cache (T005) |
|---|---|---|---|
| 2025 | 1.03 s | **0.40 s** | 1.58 s |
| 2026 | 2.70 s | not re-measured | 1.55 s |

  - The 2026 cold figure is the first full load after boot (both seasons).
  - The first 2026 attempt returned `000` (server still starting) and is excluded.
  - These are single local samples, not a benchmark. V9 does the real measurement.

## T017: foundation gate (2026-10-07, measured by the parent session)

- **Suite**: run fresh with `./gradlew cleanTest test`. An earlier plain `test` was all
  up-to-date, so its counts were the agent's run, not a fresh one. Result: **1,394 tests, 0
  skipped, 0 failures, 0 errors** (1,346 before the foundation, so +48).
- **I1**: `GameScoringParityIT` made 78,572 comparisons with **0 mismatches**.
- **T016**: `LeagueRepositorySuccessorIT` ran.
  - From the 2024 id, `seasons` is [2026, 2025, 2024].
  - 2026 `hasGames` is false locally, because there are no 2026 regular-season `player_game` rows
    yet.
- **Health**: `playerStatsLoaded: true`.
- **V1**: both leagues' `player-trends` JSON is **byte-identical** to the T005 baselines.
- **Timing** (single local samples, not a benchmark):

| League | Cold | Warm | Before the cache |
|---|---|---|---|
| 2026 | 1.44 s | 0.24 s | 1.55 s |
| 2025 | 0.89 s | 0.29 s | 1.58 s |

**Gate: passed.** No user-story work had started before this point.

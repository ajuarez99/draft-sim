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

## US1 backend: T018–T026 (2026-10-08)

**Suite** (agent's `cleanTest test` run): 1,439 tests, 0 skipped, 0 failures.

**SC-002**, from `PlayerStatsReadIT` against the real 2025 rows (agent-reported):
- The sample was 10 players: the 9 with the most games, plus Luke Kennard (id 1777, traded ATL → LAL, 78 games).
- Every per-game average and FG/3P/FT% equalled a direct SQL recomputation, with **0 mismatches**.
- The comparison is exact to 1e-9, and also checked at 1 decimal.

**Live C1 check** (parent session, port 8092, 2025 league `1229352720222134272`, measured).
These are the app's real `GameScoringService` figures, replacing the 2026-10-07 SQL estimate:

| Player | GP | PPG | FP/G | League rank | Position rank | Points rank | Move | Biggest contributions | Ownership |
|---|---|---|---|---|---|---|---|---|---|
| Rudy Gobert | 76 | 10.9 | 22.56 | 40 | 12 | 152 | **+112** | reb 872, pts 415, blk 248 | ROSTERED, week 18 |
| Donovan Clingan | 77 | 12.1 | 24.52 | 29 | 8 | 129 | **+100** | reb 892, pts 467, blk 260 | ROSTERED, week 18 |
| Dillon Brooks | 56 | 20.2 | 16.55 | 112 | 31 | 35 | **−77** | pts 566, reb 203, stl 114 | FREE_AGENT, week 18 |

- **Rank group**: 299 qualified players.
- **Earlier estimate, corrected**: the 2026-10-07 SQL estimate put Gobert at 139 → 37 and Clingan at 118 → 26. The real scorer gives 152 → 40 and 129 → 29. Same direction, different numbers. The estimate used a ≥50-games group and its own stat×weight sum. **The estimate was wrong in detail. Nothing was tuned to match it.**
- **Ownership** reads week 18, which is `playoff_week_start − 1` (F5).
- **Fallback** (2026 league `1339351318115946496`, Gobert):
  - `season` 2025, `requestedSeason` 2026.
  - `seasons` is [2026 (no games), 2025, 2024] (F4).
  - `ownership` is the 2025 week-18 roster, and `currentOwnership` is `NOT_DRAFTED` (F6; the draft is 10-10).
- **Errors**: an unknown player id gives 404, and no identity gives 404.
- **Timing**: 1.39 s cold (first request after boot), then about 0.10 s.

## T034: US1 live verification, first pass (2026-10-08)

**Setup**: web on `draft-sim-022-web-5192`, proxied to the backend `draft-sim-022-api-8092`, both
from this worktree. Signed in through the app's own screen as `popsharky`.

**Results (measured in the browser)**

| Check | Result |
|---|---|
| Player page renders (Gobert, 2025) | ✅ Header with photo, team, position, ownership as "End of 2025–26 regular season (week 18)". Season / last-10 / last-5 table with real-vs-fantasy split. Ranks #40 of 299, C #12 of 81, ▲112. Breakdown with negatives. 76-game log, newest first, team per night. |
| V5, links on each page (2025 league unless noted) | ✅ Trends 20 links (clicked Branden Carlson → his page); Weekly Report 10; Roster Management 2,059; Superlatives 8; Home spotlight (2026 league) 5. The 2025 home has no spotlight, as before this spec. |
| NFL names stay plain | ✅ NFL 2026 league `1389361939561332736`: 0 player links on Weekly Report and Roster Management. |
| V6, season picker from 2024 | ✅ Year links and season tabs both reach 2026, 2025 and 2024 on the same player. 2024 ownership reads "week 21" (F4, F5). |
| Fallback (2026 league) | ✅ API: season 2025, requested 2026, `currentOwnership` NOT_DRAFTED (see the T018–T026 entry). |
| Phone, 375 px | ✅ Page width 375, no page-level sideways scroll. Both tables (680 px and 751 px) scroll inside their own boxes (`overflow-x: auto`). |
| SC-005, zero attempts (Deandre Ayton, 0 3PA in 72 games) | ⚠️ Shows "—", not a false 0%. But FR-006 needs a stated "no attempts". Fix 2 below. |

**Problems found, to fix before US1 ships**

1. **FR-011**: the season line shows shooting % without makes and attempts. Gobert's "0.0%" 3P is 0 of 5, which the reader can't see.
2. **FR-006**: a zero-attempt rate shows a bare "—". It needs a stated reason.
3. **Rank sentence**: "Ranked #152 by season total points…" is wrong. `pointsRank` is by points **per game**.
4. **Freshness label**: "Stats through Sep 28, 2026" is `dataAsOf`, the last refresh, not the last game covered. The 2025 season ended in April.
5. **Minutes format**: "34" sits next to "33.4". Use one decimal throughout.
6. **Minus sign**: the breakdown mixes "−12.0" with "-0.7%".

## T034: second pass after fixes (2026-10-08, measured in the browser)

Checked on Deandre Ayton (2025, 0 3PA in 72 games). All six fixes hold:

1. **Makes and attempts**: shooting cells show them, e.g. "67.1% / 403-601", "64.5% / 91-141". 403/601 is 67.05%, which is correct.
2. **Zero attempts**: renders "no att.", with the full sentence as `title` and `aria-label`. The sentence was reworded from "No attempts yet…" to "No attempts in these games…", because "yet" is wrong for a finished season.
3. **Rank sentence**: "By real points per game he's #119 of 299; in this league's scoring he's #98 — 21 places higher."
4. **Freshness label**: the footer reads "Data refreshed Sep 28, 2026."
5. **Minutes**: one decimal throughout.
6. **Minus signs**: "−" (U+2212) throughout.

**Web**: `tsc -b` clean, vitest 89 files / 1,131 tests passing, `npm run build` OK.

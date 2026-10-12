# Spec 030 build notes

## T001 baseline (2026-10-11, worktree at 943b303 + spec docs only)

- Backend `./gradlew test`: **1574 tests, 0 failures, 0 errors, 0 skipped** (from the
  JUnit XML, not the console). Postgres 5433 was up, so the ITs actually ran.
- Web: `npx tsc -b` clean; `npx vitest run` **110 files, 1729 tests passed**.

## T002 migration number

No `V31+` migration exists on any local branch or on `origin/*`, checked 2026-10-11 after
`git fetch`. `origin/main` is d821e0e. V31 is free.

## T003 / T042 NFL parity

The before capture was taken from this worktree's backend **before any Java change**, so its
code is `main` 943b303. The main checkout is on stale branch 017, so it wasn't used. League:
"(Foot) Ball Knowers" NFL 2026 `1346366555759341568` (48 stored weeks locally).

Captured: `parity/analysis-1346366555759341568-before.json` (NFL: projections available, 12
rosters, window weeks 5–14, 4 scored) and `parity/analysis-1339351318115946496-before.json`
(NBA: `projections.available=false`, "aren't built for nba").

**Caveat for T042:** `LineupPass` reads rosters **live from Sleeper**, so a waiver move
between the before and after captures changes the NFL JSON without any code change. T042
must re-capture a fresh "before" from a `main` build right next to the "after" if the diff
shows roster differences, rather than blaming the code.

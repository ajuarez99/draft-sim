# Verification: spec 054 (2026-11-02)

Run against the real server (`bootRun` from this worktree) on a scratch Postgres
(`draftsim-pg-054`, localhost:5439), with the requested league ingested from Sleeper via
`POST /api/ingest/all/1339351318115946496` and `/api/ingest/league-history/...` (local admin token).
Web: `vite` from this worktree on :5174, signed in as popsharky, real browser pane.

**Data caveat (measured before, memory `project_sleeper_nba_2025_matchup_drift`):** Sleeper's
*live* `/matchups` serves degraded scores for finished NBA seasons, so this local re-ingest of
2024/2025 holds worse weekly scores than prod's stored ones. What follows verifies the mechanism on
real-shaped data; the exact 2024/2025 numbers must be re-read on prod after deploy.

| Check | Result | How |
|---|---|---|
| V1 / SC-001: a `median` block on every season, nothing dashed where data exists | **PASS** | 2026 `ALREADY_ON` (12 teams), 2025 `WHAT_IF` (18 wks, `OFFICIAL`), 2024 `WHAT_IF` (21 wks, `OFFICIAL`); all 36 rows filled. |
| V2 / SC-002: invariants 1–3 on every row | **PASS** | Script over the response: 0 violations across 3 seasons. Official real W-L equals the standings' W/L for every `OFFICIAL` row. |
| V3 / SC-003: hand check | **PASS (mechanism)** | Same script recomputes with-median = real + median and both permutations. Hand recompute against prod's stored scores is owed after deploy. |
| V4: 2026 official W = stored head-to-head + median W | **8 / 12 match; MEASURED, NOT A RULE FAILURE** | Head-to-head matches Sleeper for all 12. The 4 median mismatches contradict the stored score order (roster 10 outscored roster 12 in BOTH weeks, yet Sleeper credits 12 with more median wins), so the stored live-week scores are not the ones Sleeper decided on. No ordering of these scores yields Sleeper's record, so the median rule isn't what's being tested. Re-check on prod. |
| V5 / SC-004: parity with the Luck page | **PASS** | `/expected-wins` (season 2026, 2 wks) vs history block: 0 mismatching rosters on median and all-play. |
| V6: UI | **PASS** | 2024/2025 show the "If median had been on (N final wks)" group: With median, Median games `+N W / +M L`, Place `a → b ±N`; 2026 shows "This season played a median game every week…" with no what-if columns. Playoff line checked against the stored bracket for both seasons (not withheld); no manager crosses the 6-team line in either season, so no MADE/MISSED tags, which is the actual answer. Drift sentence shows 6 (2025) / 9 (2024), inflated by the degraded local re-ingest. Phone width 375: document scrollWidth 375, tables scroll inside `.table-wrap`. |
| V7 / SC-006: no more than 1 s slower | **PASS** | Whole history response 0.05–0.11 s (6 runs). No pre-change timing on the same DB was taken; the full response is far below the 1 s added-cost budget. |

## Backend full suite (same scratch DB on 5433, so the hard-coded ITs run)

| Tree | Tests | Failures | Skipped |
|---|---|---|---|
| this branch (before the code-review fix pass) | 2551 | 14 | 50 |
| `origin/main` ad1d031 | 2524 | 14 | 49 |

13 failures are identical on both (TargetControllerIT ×8, LeagueRepositorySuccessorIT ×2,
AccessControlMvcIT, AdminDigestControllerIT, EspnRefreshOnVisitIT): pre-existing, measured.
`NflScoringParityIT` (branch only, "no NFL leagues in the database") and `DraftGradesReadIT` (main
only) depend on rows other ITs leave behind. After the fix pass the targeted suites
(`*MedianWhatIf*`, `*LeagueHistory*`, `*ExpectedWins*`) are 0 failed, 0 skipped. Web: tsc clean,
vitest 2733 passed, build OK.

## Owed after deploy

- Re-read `GET /api/leagues/1339351318115946496/history` on prod: 2024/2025 drift should be ≈4 / 0
  (the 2026-10-12 measurement), and spot-check two managers' median records against prod's stored
  scores.
- Repeat V4 on prod's stored 2026 scores once week 3 is final.

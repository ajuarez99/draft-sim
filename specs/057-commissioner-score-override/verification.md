# Verification: Commissioner score override (057)

2026-11-05. Live, local. Labels: **[measured]** = executed and read today; **[not verified]** = not run.

## Setup

- Scratch DB `draftsim_057` on localhost:5433, not the shared `draftsim`. Backend from this worktree on :8091
  (`CORS_ORIGINS=http://localhost:5187`), vite on :5187 proxied to it.
- NBA "Ball Knowers" chain ingested fresh from Sleeper (2024, 2025, 2026).
- **Feed drift found while setting up [measured]:** today's `/matchups/1` gives popsharky (roster 1) 236.5 and
  jstrobe (roster 5) 231.5. So a fresh ingest does **not** show the bug. Prod froze an earlier read: jstrobe 233.0,
  popsharky 227.0 (prod `/weekly-report/1`, api.ballknowers.co, read-only GET).
- To make local match prod, the scratch DB's NBA 2026 weeks 1–2 `starters_points` were overwritten with prod's
  stored values. This is the "before" state.
- **The week-1 values entered below are TEST values** (today's `/matchups/1` numbers, which happen to put popsharky
  ahead). They are not the official scores; Allan still has to type those from the Sleeper app.

## Results

| Check | Result |
|---|---|
| SC-001 Awards, API: before | CLOSE_WINS week 1 lists jstrobe 233.0 over popsharky 227.0 [measured] |
| SC-001 Awards, API: after week-1 entry | CLOSE_WINS week 1 = popsharky 236.5 over jstrobe 231.5; jstrobe moves to CLOSE_LOSSES; `scoreSources` = [1 COMMISSIONER, 2 FEED] [measured] |
| SC-001 Awards, browser | "Closest wins" lists popsharky week 1. "Closest losses" lists jstrobe week 1. The page says "Week 1 uses scores the commissioner entered from the Sleeper app." (page text; screenshots timed out because the pane was not drawing) [measured] |
| SC-003 one winner across pages | Week 1, popsharky beats jstrobe on: weekly report matchups (236.50 vs 231.50, records 2-0 / 1-1), head-to-head `/managers/1/versus/4` (now 3-3, was 2-4), Awards close wins/losses. Lowest week and biggest blowout also moved to entered values. Weekly pot labels week 1 COMMISSIONER [measured] |
| SC-004 non-commissioner | PUT as jstrobe → 403 "only this league's Sleeper commissioner may enter weekly scores" [measured] |
| Validation | partial week → 400 naming missing rosters; 3 decimals → 400; week 3 (not final) → 400 [measured]; 1e400 / 1,000,000 → 400 (IT) |
| UI save | changed one team to 239.25 in the panel, Save → PUT 200, report re-rendered 239.25 [measured] |
| UI clear | Clear → week 1 back to feed (popsharky 227.00), commissioner note gone [measured] |
| FR-013 checks | GET on the feed: totals MISMATCH (e.g. roster 1: 430.0 in use vs Sleeper 475.5), record MISMATCH (roster 1: 1-3 computed vs 2-2 Sleeper). Both are consistent with the feed being wrong [measured]. A MATCH with real official numbers is **[not verified]**: needs Allan's real scores (SC-006) |
| SC-005 no-override seasons | NBA 2024 and 2025: all captured payloads identical before/after, ignoring the new fields. 39 endpoints, 3 seasons [measured]. NFL not ingested in the scratch DB **[not verified]** |
| Performance (review M5) | 2026 Awards ~0.10–0.12 s warm, with an override present [measured] |
| Power rankings | 2026 `/power` unchanged after the override. Stored rankings update on the commissioner's recompute; not exercised **[not verified]** |

## Tests

- Backend full suite after the fix pass, on shared `draftsim`: 2713 tests, 53 skipped, 14 failed [measured].
  - None of the 14 is in score code.
  - 13 also fail on `origin/main` against the same DB [measured]: `TargetControllerIT` ×8, `AccessControlMvcIT`,
    `AdminDigestControllerIT`, `LeagueRepositorySuccessorIT` ×2, and `DraftGradesReadIT` ("invariant 1: expected 180
    but was 0").
  - The 14th, `EspnRefreshOnVisitIT`, fails in the full run on a leftover `draft_pick` FK row and passes when run alone
    [measured].
  - Afterwards, V45 (table + Flyway row) was removed from the shared `draftsim` DB. It stays in `draftsim_057`.
- All 057 tests run with 0 skips: `WeekScoreResolverTest` 9, `WeekScoreOverrideIT` 7, `ScoreOverrideControllerIT` 7,
  plus the weekly report and pot classes.
- Web: `tsc -b` clean, `npm run build` OK, vitest 160 files / 2776 tests passing [measured].
- On `origin/main`, 12 of the 14 failures the build agent saw also fail
  (`TargetControllerIT` ×8, `AccessControlMvcIT`, `AdminDigestControllerIT`, `LeagueRepositorySuccessorIT` ×2).
- The other two (`NflScoringParityIT`, `EspnRefreshOnVisitIT`) pass on this branch on re-run, so the earlier failure
  came from shared-DB state [measured].
- `LeagueRecordIT` skips 3 of 6: it needs the NFL chain, which isn't ingested in the shared DB. Same on main. The
  extremes tiebreak is covered by `WeekScoreOverrideIT` instead.

## Owed

- Allan types the real week-1 and week-2 scores, in prod after deploy. Both FR-013 checks should then read MATCH for
  all 12 rosters (SC-006). That is the decisive check.
- Review M3, Allan's call: should the OFFICIAL tier apply to every page now, or only to the pot until 055's week-3
  check (after 2026-11-10)?

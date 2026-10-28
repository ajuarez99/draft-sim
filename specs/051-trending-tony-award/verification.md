# Verification: The Trending Tony Award (spec 051)

**Date:** 2026-10-27 to 10-28. The machine slept overnight between the first pass and the re-check after fixes.

**Setup:**
- Code: worktree `051-trending-tony-award`, off main at 7168753, uncommitted.
- Database: a throwaway Postgres 14 on `localhost:5433`, a fresh cluster in the session scratchpad, migrated V1–V42.
- Real data: both real leagues, ingested from Sleeper and refreshed: "(Foot) Ball Knowers" (NFL 2026 and 2025) and "Ball Knowers" (NBA 2026, 2025 and 2024).
- Servers: the backend on port 8085 and vite on 5186, both from the worktree. I checked the classpath line in the bootRun log (`…\051-trending-tony-award\backend\build\classes`), so these checks did not run against main's checkout.

**Labels.** **REAL** means real Sleeper data end to end. **SEEDED** means hand-inserted trending snapshots against real pickups. No real trending history existed before this feature, so the winner path can only be seeded locally.

| # | Check | Kind | Result |
|---|---|---|---|
| V1 | A real fetch writes a snapshot | REAL | **PASS.** One snapshot per sport (nfl id 18, nba id 36). `entry_count` is 25, `fetched_at` matches `sport_trending_fetch.fetched_at`, the 25 entries equal `sport_trending` row for row (0 mismatches on rank, id and count), and the current-list table is unchanged in shape. |
| V2 | Coverage on a live season | REAL | **PASS.** NFL 2026 through week 7, with real snapshots only: available, 1 judged and 117 not judged out of 118, "nobody has chased the trending list twice yet". Hand count in SQL: 142 completed WAIVER/FREE_AGENT adds in weeks 1–7, minus 24 team defenses, gives **118**, which matches (SC-002, F9). The gap against 171 transactions is drop-only moves, which add nobody. |
| V2b | Is the live NBA week inside `throughWeek`? (F9, previously inferred) | REAL | **Measured: no.** NBA 2026 reports `throughWeek = 1` while week 2 is being played, so current-week pickups enter every bucket only once that week is scored. They are judged by their own timestamps then, so nothing is lost; the card just lags by up to a week. |
| V2c | Live season where nothing can be judged yet | REAL | **PASS after fix.** NBA 2026 (history began 10-27, after every week-1 pickup). The first build said "Trending history wasn't being recorded yet for this season.", which reads as *never* on a season still in play; I found this live. After the fix (spec A5) it says "Nothing could be judged yet: trending history began on 2026-10-27 (UTC), and no pickup since has a list within a day of it." |
| V3 | Past season | REAL | **PASS.** NFL 2025 shows the card unavailable: "Trending history wasn't being recorded yet for this season." It has no winner and is never shown as "nobody qualified" (SC-003). NBA 2025 and 2024 returned 404 for this user's identity. That is the existing per-season membership scope, not this feature, and I did not investigate it. |
| V4 | Winner path | **SEEDED** | **PASS, every value predicted before reading it back.** Six snapshots, ids 1001–1006, were dated around real NFL 2026 pickups. Roster 1 won with 3: Tank Dell #1, judged by the snapshot *after* his pickup even though one 22.8h *before* exists and doesn't list him (pins the after-first rule); Bigsby #3; Young #14. Vele #20 and Badie #16 were correctly not counted (the top-15 boundary). Roster 2 came second with 2: Allen #2, and Shakir #5, judged by the snapshot *before* his pickup because the one after was empty and was skipped (F13). Standings rank all 12 rosters, zeros included. The Players list is ordered by rank. **The seeded rows were deleted afterwards** (by explicit id; the cascade removed their entries; 0 orphans; only ids 18 and 36 remain). |
| V4-trade | A trade acquisition doesn't count (F1) | not live | NFL 2026 has no trades through week 7, so this could not be exercised live. It is covered by `TrendingPickupsTest` and `SeasonSuperlativesTrendingTonyGatherTest`. |
| V5 | No other award moved (SC-004) | REAL | **PASS.** Unmodified main (a temporary detached worktree at `origin/main`, port 8086) ran against the **same** DB; Flyway ignores V42 as a future migration. For NFL 2026, NBA 2026 and NFL 2025, the branch payload minus `TRENDING_TONY` and the `trendingCoverage` keys is **byte-identical** to main's. |
| SC-005 | Load time | REAL | **PASS.** Six warm requests each. NFL 2026: main 70–92 ms, branch 70–132 ms. NBA 2026: 33–49 ms on both. The budget is +0.5 s. With only 1–7 snapshots stored, production density (up to 24 per day) is bounded by the header-plus-filtered-entries query, but is **not measured here**. |
| SC-006 | Snapshots per day in production | — | **Not measurable locally.** The local server was not running overnight. This is owed as a production check one day after deploy. |
| V6 | ESPN league | not live | No ESPN league is ingested locally. Covered by `SeasonSuperlativesTrendingTonyGatherTest` (ESPN returns the reason and the repository is untouched). |
| V7 | Real browser, desktop and 375px | REAL plus SEEDED | **PASS.** The card renders last ("The Trending Tony", "3 trending pickups", pickup lines "#1 trending · week 1 · Free agent", the coverage line naming its exclusions). "See all" shows the Teams view with "3 of 8 judged pickups" / "3 trending pickups" and the Players view with "#1 trending" … "#14 trending", never "N trending pickups" (F6). "How this works" uses the after-first wording (B4) and credits Sleeper. At 375px, `scrollWidth` equals `innerWidth` (375), so there is no horizontal scroll. The Hall of Fame trophy case for NFL 2025 lists the 13 existing awards and hides the unavailable Trending Tony (F8). |

## Automated suites, run on the final tree

- **Backend, `./gradlew test` with real data loaded:** 2524 tests, **1 failed**, 28 skipped.
  - The single failure is `PlayerTrendsReadIT.nba2026ReadsLastSeasonUntilItHasGames` (expected 2025, got 2026). It **fails identically on unmodified main** against the same DB (`origin/main` worktree, 2 tests, 1 failed). It is date-dependent: NBA 2026 now has games. It is not related to this change.
  - The build agent's earlier 13 failures (TargetControllerIT, AccessControlMvcIT, AdminDigestControllerIT, NflScoringParityIT, LeagueRepositorySuccessorIT) came from running on an **empty** DB. With real data they pass on main (39/39) and on this branch.
  - 28 skipped: I did not classify each one. The new ITs (`SportTrendingRepositoryIT`, 14/14) ran with 0 skipped.
- **Web:** `npx tsc -b` is clean, `npx vitest run` gives **152 files / 2650 tests passed**, and `npm run build` succeeds.

## Things that went wrong during verification (recorded, not hidden)

- **My seed script set the snapshot id sequence to 999.** The repository IT then inserted ids 1001 onward and collided with my seeded rows (`DuplicateKeyException`, 6 tests in the fix agent's first run). The failed inserts used up sequence values, so the sequence is now past the seeds; nothing was lost and the rerun passed. Lesson: seed with ids from the sequence, never a hand-picked range.
- **Line-ending noise:** a blanket Git Bash `sed -i` in the frontend build touched about 85 files, CRLF to LF, with no content change. Every file with no real diff was restored with `git checkout`; only the 12 real web changes remain.

## Not verified

- The production snapshot cadence (SC-006) and a real winner from real trending history: both need deployed time.
- The ESPN path and the trade exclusion, live: covered by unit tests only, as noted above.

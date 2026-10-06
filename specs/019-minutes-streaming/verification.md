# Verification: spec 019

Each check is **run** (with output) or **not run** (why).

## Build (T002–T016)

**Run, 2026-10-06.**
- Backend: full `./gradlew test` gives **1,234 tests, 0 skipped, 0 failures** (backend agent, XML).
  T009/T010 were seen failing before implementation.
- Web: `tsc -b` clean, vitest **1,053 passed** (84 files), build OK (frontend agent).

Accepted deviations:
- tasks.md T009 said "+14" for 10×18 then 3×32. The data-model rule (season mean over all games)
  gives **+10.77**. My arithmetic in tasks.md was wrong, and the test follows data-model.
- `SeasonGame` carries `week` (for the one-game share). `windows` is never null.
- A teamless player is counted as no-team before stale.

## V1 / T017: V28 roster players

**Run, 2026-10-06**, backend from this worktree on :8086 (PID 1624, classpath
`worktrees\019-minutes-streaming\backend`).
- NBA 2026 (pre-draft), after a member refresh: 12 `roster_season` rows, each `players = {}`, with
  `players_fetched_at` 18:23:04Z.
- Non-empty arrays: (Foot) Ball Knowers NFL 2026 after a refresh. All 12 stored rosters
  **equal** Sleeper `/rosters` players ∪ reserve ∪ taxi, with sizes 14–16. The `text[]` bind round-trips.
- **Not run yet (dated):** NBA 2026 after its 10-10 draft. Compare with `/rosters` between 10-11
  and 10-19.

## V3–V6: endpoint

**Run, 2026-10-06**, as `1122386008709910528`.

| League | HTTP / time | Result |
|---|---|---|
| NBA 2025 | 200 / 1.52 s | roles 2025, no fallback. `streamingReason` SEASON_COMPLETE, streaming []. risersTotal 84, fallersTotal 59, excludedStale 67, excludedNoTeam 90. oneGameCredit 0.99 (2025) |
| NBA 2026 | 200 / 0.77 s | both lists fall back to 2025. `streamingReason` NOT_DRAFTED, currentWeek 1, rostersFetchedAt set, oneGameCredit 0.99 |
| NFL 2026 | 200 / 0.01 s | `available: false`, `NOT_BASKETBALL` |
| no header | 404 | ✅ |

Across all rows: 0 `TEAM_` ids, 0 null teams, `rostered` null everywhere (invariant 3, because a
streaming reason is set). Top 2025 risers: Branden Carlson POR 11.6 → 37.4, Rayan Rupert PHI
16.8 → 40.0, Leonard Miller CHI 15.6 → 38.7. These are end-of-season (April) rotation changes,
and the page labels them "end of the 2025 season" (N15).

**Not run:** the streaming table with real free agents, and the grid agreement for games (SC-004).
No league is drafted and in season. Covered by unit tests. Dated check after 10-10.

Timing: 0.8–1.5 s locally (F13). Production timing is owed.

## V7: UI

**Run**, Vite :5186 → :8086, signed in at the local gate as `popsharky`.
- `/leagues/1339351318115946496/trends`:
  - Streaming shows "2025 season (2026 is too early: fewer than half the teams have played 3
    games)" and "This league hasn't drafted yet…".
  - Risers/fallers show the "…5 games" fallback, "end of the 2025 season, last 3 games vs
    season", the 6-minute threshold, and the unsplit-lists explanation.
  - Rows show "37.4 recent · 11.6 season +25.8 min" and "usage rate (pooled over the window)".
  - "+74 more". "67 not shown: no game in the last 14 days. 90 without an NBA team."
- 375px: document width 375 = viewport. Both tables are `overflow-x: auto` (655/665 px of
  content in 341 px).
- Console: two 403s, both `POST /api/leagues/1339351318115946496/refresh` (the page-visit
  refresh). curl with the same member identity got 200. Probably the dev server's non-default
  port isn't in the backend's allowed origins (unconfirmed). It doesn't touch this feature, and
  spec 018 saw the same on :5185.
- Screenshot not taken: the pane was hidden and capture timed out. Checked through page text and
  DOM measurements instead.

## Code review fixes (T021a), re-verified live

**Run, 2026-10-06.** [code-review.md](code-review.md) found B1 high, B2–B3 medium and B4–B7 low.
All are fixed per data-model's "Amended after code review". Backend **1,243 tests, 0 skipped**,
web **1,055** + tsc + build (fix agent). Restarted :8086 (PID 2912, classpath confirmed as this
worktree), then:

| League | Time | streamingReason | risersTotal | excludedStale / NoTeam | missedTeamGames > 0 (examples) |
|---|---|---|---|---|---|
| NBA 2025 | 1.58 s | SEASON_COMPLETE | 95 | 15 / 0 | Herbert Jones 3, Nikola Jović 4, Grayson Allen 1 |
| NBA 2026 | 0.81 s | NOT_DRAFTED | 84 | 10 / 18 | same (2025 fallback) |
| NBA 2024 | 0.62 s | SEASON_COMPLETE | 125 | 16 / 0 | Vasilije Micić 5, Jaylen Brown 3 |

B4: a completed season shows the last-game team (2025: Carlson OKC, Rupert MEM). The 2026 page
shows current teams (Carlson POR, Rupert PHI). B1 was the plan review's wrong count (corrected
visibly in plan-review.md F2).

## Not run / owed

- **NBA 2026 after its 10-10 draft** (between 10-11 and 10-19, dated): rosters stored vs `/rosters`,
  the streaming list excludes every drafted player (SC-003), and games match the grid (SC-004).
- **Per-list cutover dates** in 10-20 … 10-29 (F1).
- **Production timing** (F13) and production V4 before 10-20.
- **The UI after the fixes** isn't re-checked in a browser. Unit tests cover the split and labels.

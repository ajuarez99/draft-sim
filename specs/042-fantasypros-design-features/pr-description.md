## Spec 042: ownership marks, player page header + tabs, lineup check

Three things borrowed from a signed-out look at FantasyPros (2026-10-20). User stories US1–US3 only. US4 (two-player compare) and US5 (multi-league summary) are deferred.

- **US1, whose player it is.** One `OwnershipMark` across the stat leaders, Trends and the player page. Trends rows gain `rosteredByMe`. A "My roster" filter uses the existing `own=` URL encoding (`own=mine`), shared with the stat calculator.
- **US2, player page header.** Shows injury or suspension status with its refresh time, the next game (date only, since no time is stored), and position rank. The content is split into `?tab=` tabs.
  - Next game comes from the URL's league and the player's current team. That's plan-review F1: otherwise it would have been blank on opening night.
- **US3, lineup check.** New endpoint `GET /api/leagues/{id}/lineup-check`. It reads live Sleeper starters and flags empty slots, out (`isUnavailable` or suspended), listed tags, and no game in the week, each with eligible bench options.
  - It never issues a verdict.
  - It replaces the projected idle-starters note when live starters load.
  - ESPN leagues are gated by a `lineupCheck` availability key.

No migration.

### Verified
- **Live checks** against real Sleeper with the local DB, on NBA opening night. Details in `specs/042-fantasypros-design-features/verification.md`.
  - Starters match Sleeper's live `/rosters`.
  - A real empty FLEX slot and two real NFL byes (DET, CIN) are flagged correctly.
  - SGA's next game is "@ SAS tonight", matching FantasyPros.
  - The endpoint returns 404 to anonymous callers, and the CORS preflight allows `x-sleeper-user`.
  - The lineup check takes 0.07–0.56 s.
- **SC-004.** The existing fields on 9 player pages, the leaderboard and Trends are identical to `origin/main`.
- **Tests.**
  - Backend: 2109 tests, 1 failed, 1 skipped. The failure, `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`, also fails on clean `origin/main` (it depends on local DB data).
  - Web: `tsc -b` clean, vitest 2295/2295 passed, build OK.

### Not verified or still open
- A player with no 2026 games yet doesn't get the tabs, so the Schedule tab doesn't render.
- The injury refresh time doesn't show when only bench options carry tags.
- Layout at 375 px wasn't checked, and I didn't see a real NBA "out" flag (nobody had one tonight).
- Injury status refreshes once a day (11:00 UTC). A second refresh before tip-off needs `DailyRefreshService.players`' once-per-day skip changed. That's your call.

Review trail: `plan-review.md` (1 blocker, 10 should-fix, all taken), `code-review.md` (2 bugs, 8 should-fix, fixed), `t001-measurements.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

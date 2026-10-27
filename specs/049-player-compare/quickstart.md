# Quickstart / validation: Compare two players (spec 049)

## Prerequisites

- Postgres on `localhost:5433` (see `AGENTS.md`), backend from **this worktree** (`bootRun`; check the classpath,
  memory "worktree preview serves main"), `vite dev` from this worktree's `web/`.
- The real NBA league "Ball Knowers" (2026) ingested, with 2026 box scores and the 2026 schedule stored.

## Scenarios

1. **Figures equal the player pages (SC-001).** For 10 pairs, open `/leagues/<nba>/compare?a=X&b=Y` and each
   player's own page; every shown figure matches. Scripted: fetch both player JSONs and the rendered page text,
   diff the numbers.
2. **Marks (SC-002).** Same 10 pairs: every mark is on the higher value (lower for TOV); none on a row where a
   window is a small sample or part-filled (early season: expect most Last 10 rows unmarked; record how many).
3. **Entry and chooser (SC-003).** From a player page: Compare → type 3 letters → pick. Time it.
4. **Shared address (SC-004).** Copy the URL into a new tab: same two players.
5. **Phone (SC-005).** 375 px: no horizontal page scroll (`document.documentElement.scrollWidth <= 375`).
6. **No verdict (SC-006).** vitest scan, plus a manual read of the live page.
7. **Football.** `/leagues/<nfl>/compare` shows the basketball-only line; an NFL player page has no Compare link.
8. **One side failing.** `?b=not-a-player`: column A renders, B says not found, no marks.
9. **This week.** *(Amended after review 2026-10-26, F3.)* `thisWeek.week` equals the week the lock-ins live
   calls use (`/lock-ins` live response), not the schedule page's `currentWeek` (`leg`, a different rule on a
   Monday). Record the league's `leg` and when it was last ingested. `played + remaining` equals the team's
   counted games in that week from the schedule grid.
11. **Shooting and turnovers (F4/F5).** A pair where one player is 1-of-1 from three: 3P% is unmarked, and
    made-attempted is shown. TOV per game is unmarked, and turnover rate is marked lower.
10. **Latency (SC-007).** Warm pair, measured in the browser's network panel, local then deployed.

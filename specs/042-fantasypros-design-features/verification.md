# Verification: spec 042 (2026-10-20, NBA opening night)

The 042 worktree ran on `bootRun` :8142 and `vite` :5242 against local Postgres 5433, with **live Sleeper**.
NBA and NFL players were re-ingested at 23:47 UTC. Clean `origin/main` ran on :8143 for diffs. The browser
was the built-in pane, signed in to the local app as popsharky.

## Verified by running

- **Lineup check, NBA "Ball Knowers" 1339351318115946496.** Allan's 9 live starters match Sleeper `/rosters`.
  - With stale local statuses it raised 5 LISTED flags. After the fresh ingest it raised 0, and the UI says "All starters are active".
  - None of the 12 rosters has a tagged starter tonight, so NBA OUT/LISTED flags were not seen live (fixtures and tests only).
  - The page shows both as-of times in EDT.
- **Lineup check, NFL "West Coast" 1389361939561332736.**
  - EMPTY_SLOT at FLEX is real: Sleeper starters carry `"0"` there.
  - Gibbs (DET) and McPherson (CIN) show "no game in week 6". Both are byes, confirmed in `sport_schedule`.
  - A Questionable bench option keeps its tag (B1 fix).
- **Auth and CORS.**
  - Anonymous request gets 404.
  - The CORS preflight from :5242 allows `x-sleeper-user`.
  - Latency was 0.07–0.56 s per call (S7).
- **SC-004.** The old fields on 9 real player pages are identical to `origin/main`. The leaderboard and Trends payloads are identical apart from the new field.
- **F1 blocker.** On opening night, SGA's next game is "Tue Oct 20 · @ SAS", the same as FantasyPros' Wembanyama page.
  - Collins played tonight (vs BOS, complete), so his next game is correctly Fri Oct 23.
  - The Schedule tab deep-links (`?tab=schedule`) and says "dates only".
- **US1.** `?own=mine` selects "My roster" on reload. Rows are tinted and carry a "Yours" pill.
  - Only 1 row showed. That's correct: 2026 has 39 players with games, and only one is Allan's.

## Found, not fixed

1. A player with no 2026 games keeps the old "hasn't played" page, so the tabs and the Schedule tab don't render. That's exactly the opening-week case.
2. The status as-of line doesn't appear when the only tags are on bench options (NFL page).
3. The stored league `leg` lags Sleeper until a refresh (West Coast read 4, live was 6). It's pre-existing and shared with the next-matchup block.
4. On Tuesday after MNF, Sleeper's leg is still the finished week, so the bye flags describe a week that's over.

## Not verified

- SC-003 at 375×812: screenshots failed with the pane hidden.
- A real NBA OUT flag in the UI.
- The full backend suite after the fix pass was green apart from the known `BoardRepositoryLatestBeforeIT` failure, which also fails on clean `origin/main` (data-dependent).

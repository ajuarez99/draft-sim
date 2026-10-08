# Quickstart: verifying spec 022

Each step is labelled with the chunk it verifies: F (foundation), US1, US2, US4 or US3. Every
result is recorded in `verification.md` as **measured**, with the number, or as **not run**, with
the reason.

## Prerequisites

- Postgres on `localhost:5433` (`draftsim`), with NBA 2024, 2025 and 2026 ingested. Check:
  `select season, count(*) from player_game where sport='nba' group by 1`. Expect about 29k rows
  each for 2024 and 2025.
- The backend runs from **this worktree's** classpath. Memory "Worktree preview serves main":
  `preview_start` from a worktree runs main's `launch.json`, so check the bootRun working
  directory before trusting a live check.
- A member identity for the NBA league, sent as the `X-Sleeper-User` header.
- The backend suite must show **0 skipped** ITs. Memory "Backend suite skips ITs silently": if
  Postgres is down, the suite reports BUILD SUCCESSFUL with ITs skipped.

## F: foundation (before any page)

**V1. The extractions leave existing behaviour unchanged.**

- `./gradlew test`: Player Trends' existing tests pass unchanged after the `NbaGameLines`,
  `AdvancedStats.usage` and `contributions` extractions.
- Invariant I1: `score` is identical on every 2025 NBA game.
- `GET /api/leagues/{nba2026}/player-trends` gives JSON identical to before the change. Compare
  with `jq -S`.

**V2. The cache is correct and gets invalidated.**

- Read a season twice: the second read does no reload. Log the reload count.
- Touch one row's `fetched_at` in a local DB copy: the next read reloads.
- No `TEAM_` id appears in any line list (I5).

## US1: player page

**V3. Traditional stats match the stored rows (SC-002).** For 10 players with full 2025 seasons:

- every per-game average and shooting percentage in C1 equals a direct SQL recomputation over
  their rows, excluding `TEAM_` rows and the All-Star game;
- the result must have zero mismatches;
- one of the 10 must be a player traded mid-season (scenario 3).

**V4. Fantasy figures match the app's scorer (SC-008).** For the same 10 players:

- `fpPerGame` and the per-game fantasy points in the game log equal `GameScoringService.score`
  over the same games;
- the breakdown sums to the total (I3);
- the league rank of Gobert and Clingan should sit far above their points rank. Record the actual
  numbers; the 2026-10-07 SQL estimate (rank 37 vs. 139) is not the target.

**V5. Linking works in the browser (SC-001).** In a real browser, click one player name each on:

- the spotlight;
- the Weekly Report;
- Trends;
- Roster Management;
- League Analysis.

Each must land on that player's page in the same league-season.

**V6. The season picker and fallback.**

- Before 2026 has games, the 2026 league's player page opens on 2025 and says so
  (`requestedSeason`).
- Picking 2024 shows the 2024 league's scoring and week-21 ownership, never current rosters (I8).

## US2: advanced stats

**V7. Rates, edge cases and preference ordering.**

- **Zero denominators**: a player with 0 three-point attempts in a window shows a
  "no attempts" reason, not 0% (SC-005).
- **Small samples**: a fewer-than-5-game player shows "last 5 (3 games)" and the small-sample
  label.
- **Ordering tests** (I7): a higher TOV% gets a lower percentile, and more FP/G ranks higher.
- **Ownership not loaded**: on the "rostered in this league" group for NBA 2026 before the draft,
  the reason is `OWNERSHIP_UNAVAILABLE` or `NOT_DRAFTED`, and nothing is ranked against an empty
  group.

## US4: leaderboard

**V8. Draft, ADP, replacement and the phone layout.**

- **2025 draft** (SC-009): every one of the 168 picks matches `draft_pick`, and no undrafted player
  carries a pick.
- **2025 ADP** reads `NO_ADP_STORED`. **2026 ADP** reads the latest blend capture on or before the
  draft date, and names it.
- **Draft value** equals Draft Grades' `valueOverSlot` for 5 picks.
- **Replacement levels** per position are listed. Hand-check one position's fill: the 12 × 9
  starters are placed and the next best player at that position is shown.
- **Values match the player page**: TS% and FP/G for 5 rows equal C1's values for the same window
  (I6, FR-025).
- **Phones**: at 375 px (`resize_window`), all five column groups keep the name pinned and the
  page never scrolls sideways (SC-010). Take a screenshot.

## Performance (all chunks)

**V9. Sizes and timings (SC-006, research R6 and R14).** Measure and record:

- the cache's cold-load time and its heap size, from a heap histogram or the before/after used heap;
- the validity-token query time;
- the warm response time and gzipped JSON size of C1, C2 (SEASON) and C3, locally and in
  production;
- Railway's container memory, from the dashboard.

The 2-second target is checked against these numbers. Compression or a smaller payload is added
only if the measurements call for it.

## US3: nightly report

**V10. Three past nights (SC-004).** For 3 past 2025 nights, including one with an overtime game:

- the game list and scores match the team rows;
- the top three by game score match a hand computation;
- every standout flag meets its rule;
- the member's roster lines match the week's `players_points` keys.

## Manual check against Basketball Reference

**V11. Cross-check (SC-003).** In a browser, read Basketball Reference's 2025-26 season:

- the advanced page at `NBA_2026`, or the 5 players' own pages;
- record TS%, eFG%, USG% and TRB% next to ours, with the actual differences;
- tolerances: 0.5, 0.5, 1.0 and 1.0 percentage points;
- a stat outside tolerance is reported with its difference and the R5 explanation. The formula is
  not changed to match.

Nothing from the page is stored.

## After opening night

**V12. Box score delay (FR-036).** On the first regular-season nights from 2026-10-21, record for
5 games:

- the game's final time, from the schedule's status change;
- the time a refresh first stored both team rows and every player row;
- the time Sleeper's per-game endpoint first returned the complete box.

That sets US3's "incomplete night" behaviour. If the delay is long, the ESPN fallback goes back to
a decision; it is not adopted here.

# 09 — History's Rank column shows a week-1 power rank on the live season, under "standings"

**Severity: Medium. Verified locally** (league 1346366555759341568, History page). The root
cause below comes from reading the code and has not been run under a test yet.

## What's there now

- The page subtitle is "Standings as Sleeper reports them" (`web/src/pages/LeagueHistory.tsx:457`).
  The column header is "Rank", with a tooltip "End-of-season power rank" (`:348`).
- For 2026 the payload has `rankStatus: RANKED, finalRankWeek: 1`. Rows are ordered by W then PF,
  so jpelwell (2-0, 2nd row) shows 7 and kieriskash shows 1. The only hint is a hover
  "Through week 1" (`:305`).
- **Cause:** `PowerRankingService.finalRankForSeason` (`engine/PowerRankingService.java:371-379`)
  checks for a snapshot *before* it checks whether the season is live. `finalRealizedRanks`
  returns the latest REALIZED snapshot of any week above 0 (see `PowerRankingFinalRankIT`). An
  in-season snapshot exists as soon as week 1 is scored, so a live season gets RANKED, and the
  `IN_PROGRESS` branch is reachable only when no snapshot exists yet.
- The caller already passes the right fact: `!league.complete()`, the V21 status
  (`api/LeagueHistoryController.java:108`). The flag is correct; the order of the checks is wrong.
- The unit test for IN_PROGRESS (`PowerRankingServiceTest.java:281`) mocks an empty snapshot
  list, so it never covered a live season that has a snapshot.
- `specs/002-league-history-record-book/quickstart.md:111` already expects the 2026 rows to be
  `IN_PROGRESS` with `finalRank: null`. The code drifted from the spec.
- **Same class as "Champion stamped on a live season":** an end-of-season value written or
  read for a season that hasn't ended. `backfillFinalRanks` (`:414`) still uses the positional
  `i == 0` proxy that T028 removed from the controller.

## Fix (recommended: IN_PROGRESS wins)

1. In `finalRankForSeason`, return `IN_PROGRESS` when `isHeadOfChain` (really "not complete")
   before reading snapshots. Rename the parameter to `seasonComplete` or `inProgress`, because
   it's no longer about position in the chain. The existing `IN_PROGRESS` cell already renders
   "season in progress". No frontend change and no `api.ts` change are needed.
2. In `backfillFinalRanks`, replace `boolean head = i == 0` with `!row.complete()`.
3. Add a test: a snapshot exists and the season isn't complete, so the status is `IN_PROGRESS`
   and `byRoster` is empty. Keep the existing RANKED test for complete seasons.

**Rejected options:**

- **Relabel the column "Power rank (wk N)".** It's honest, but it puts an app-computed opinion
  in a table whose subtitle promises Sleeper's facts. The page copy says the app's opinions
  live on the manager page.
- **Show Sleeper's standings order.** The rows are already sorted W/PF, so this would only
  repeat the row index.
- The live power ranks already have their own page (League analysis).

## Minor: popsharky 2026 PF 216.99 (History) vs 217.00 (Roster management)

- **History** reads `roster_season.fpts`, which is Sleeper `/rosters` `settings.fpts +
  fpts_decimal/100` (`ingest/LeagueHistoryIngestService.java:190,331-337`). That's an exact
  two-decimal composition, not a rounding step.
- **Roster management** sums `roster_week_points.starters_points` per week, which come from
  Sleeper `/matchups/{w}` `points` (`engine/RosterManagementService.java:187`, `round2` at `:212`).
- **Not rounding.** Checked against live Sleeper just now, three weeks scored: all 12 rosters'
  `fpts` equal the summed matchup points to the cent (popsharky 364.54 = 157.40 + 59.60 + 147.54).
- **Likely cause (not confirmed; the local DB wasn't read):** the two endpoints were ingested
  at different moments around a Sleeper stat correction. That is a staleness gap between two
  sources, and re-ingest should close it. If it recurs on fresh data, reopen it.

## Acceptance criteria

- [ ] In the browser against a real `bootRun`: the 2026 rows' Rank cell reads "season in
      progress". No number in that column can be read as standings.
- [ ] Complete seasons (2025, `finalRankWeek: 17`) render exactly the same as before. Diff the
      JSON for one completed season before and after.
- [ ] The new unit test fails on the current code and passes after the fix.
- [ ] After re-ingest, History PF and Roster management totals match for every 2026 roster.

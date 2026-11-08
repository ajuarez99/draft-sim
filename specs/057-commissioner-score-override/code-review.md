# Commissioner score override — bug-hunting code review

2026-11-05. Reviews the uncommitted spec-057 change in worktree `claude/peaceful-panini-70c6a7` (base `5a47471`):
`git diff` plus the untracked files. The review is against `spec.md` and `plan.md`, where the plan's
"Amended after review" section is the authority. This is not a style pass. No source files were edited.

Labels: **[measured]** = executed or queried today. **[code]** = read from the diff or the tree.
**[inferred]** = reasoned, not run.

## What was run

- **Backend unit tests**, all passing with 0 skipped [measured]:
  - `WeekScoreResolverTest` 9/9
  - `WeeklyPotServiceTest` 20/20
  - `WeeklyReportServiceLiveWeekTest` 19/19
- **ITs were NOT run by this review.** `WeekScoreOverrideIT`, `ScoreOverrideControllerIT` and `LeagueRecordIT` write
  seed rows into the shared `draftsim` DB on 5433, and this pass was limited to read-only SQL. V45 is already in
  `draftsim`'s `flyway_schema_history`, so some session has run them. Their results are not in `build/test-results`
  (this run's filter cleared that folder). The verification pass must run them and confirm the skip count is 0.
- **Frontend:** `npx tsc -b` is clean. `vitest` passes 170/170 on CommissionerScoresPanel, scoreSource, WeeklyReport,
  Superlatives and WeeklyPot [measured].
- **Scratch DB `draftsim_057`**, read-only [measured]:
  - League 1 (NBA 2026) holds a week-1 override for all 12 rosters (Σ 2635.50, feed Σ 2651.00). Roster 1 is 236.50
    over a 227.00 feed, and roster 5 is 231.50 over a 233.00 feed.
  - There is one snapshot, `through_week = 2` (Σ 5773.50).
  - `last_scored_leg = 2`, `league_average_match = 1`, `playoff_week_start = 20`.

## Precedence and consumer coverage: confirmed

All of these are [code]:

- `Resolution.points` returns the override value whenever the week is COMMISSIONER and has that roster. The feed
  never beats an override.
- A COMMISSIONER week that lacks a feed roster resolves the whole week as FEED, with a warning (L1).
- An OFFICIAL week needs every feed roster, no negative values, and drift of 25% or less.
- Every caller of `pairedWithScores`, `allScores`, `extremes` and `through` gets resolved numbers. These are
  ExpectedWins (luck, all-play, median), LeagueRecordService, HeadToHead, Rivalry, HallOfFame, PowerRanking PPG,
  LeagueAnalysis, PlayoffOdds strengths, MedianWhatIf/ScheduleSwap (via ExpectedWins), the pot and the report.
  `grep` finds no other team-total SQL outside the store package.
- `scheduledPairs` carries no points.
- `breakdownsFor` totals are resolved only in the superlatives HIGHEST/LOWEST standings and the report's
  `pointsByRoster`. Bench, lock-ins, roster-management totals, trends and stats stay on the feed (FR-012).
- The HIGHEST/LOWEST winners (`extremes`) and the standings (`resolvedBreakdowns`) now read the same resolved
  numbers.
- The `extremes` sort in Java reproduces the old SQL order exactly:
  - points (BigDecimal `compareTo`, scale-insensitive), then season desc, week asc, roster asc;
  - `.reversed()` applies only to the points and season comparators.
- `through()` converts double → `BigDecimal.valueOf` → double. That round-trips exactly for the feed, and a
  2-dp override converts to the nearest double. There is no precision loss that matters.
- api.ts mirrors every new wire field: `WeeklyReport.scoreSource`, `SuperlativesResponse.scoreSources`
  (`{week, source}`, as the controller builds it), `WeeklyPotWeek.scoreSource` + COMMISSIONER, and
  `ScoreOverrideView`/`Team`/`Checks`/`Totals`/`Records` field for field. `isFinal` is asserted by name in
  `ScoreOverrideControllerIT:164` (not run here).
- The panel uses `data.sleeperLeagueId` (M4).
- Responses use records or `LinkedHashMap`, so no `Map.of` holds a nullable value.

No HIGH findings.

## Findings

### MEDIUM

**M1. The panel prefills every box with the in-use score. A partly typed week is therefore saved "whole", and
untouched feed values become "entered by the commissioner from the Sleeper app".**

- **Where:** `web/src/components/CommissionerScoresPanel.tsx:111-116` (`adopt` sets
  `inputs[rosterId] = inUsePoints.toFixed(2)`), with `buildScores:72-85`.
- **Scenario:**
  1. Allan types 11 of the 12 week-1 scores from the Sleeper app and misses one row.
  2. He clicks Save.
  3. `buildScores` sends all 12. The 12th is the feed's number, which is not official.
  4. The server's whole-week rule (FR-003) passes, so the week is stored as COMMISSIONER.
  5. Every page now labels the feed value as a hand-entered official score.
  6. Pot money moves on it.
- **Why it matters:** FR-003's "a partial submission is refused, so no week mixes sources" is defeated in the
  client, and the mix is hidden behind the COMMISSIONER label. That is the honesty rule this feature exists to keep.
  The FR-013 totals check would usually show MISMATCH for that roster. But it only warns, and it is UNAVAILABLE
  whenever no snapshot covers the week.
- **Fix:** leave the inputs empty. Show the in-use number as `placeholder` or in its own column, and make
  `buildScores` refuse a blank box, as it already does. Alternatively, mark each row as touched and refuse to save
  while any row is untouched.
- [code]

**M2. On every OFFICIAL week the weekly report omits SELF_INFLICTED_WOUND, including NFL weeks whose official
scores equal the feed.**

- **Where:** `WeeklyReportService.java` around line 371: `if (scoreSource != FEED) omitted.add(... SCORES_NOT_FROM_FEED)`.
  The resolver returns OFFICIAL for any plausible snapshot week, whether or not a value differs.
- **Scenario:**
  - 055 measured that the NFL league's `fpts` equals the feed cumulatively.
  - Once prod holds snapshots at legs N-1 and N, NFL week N resolves OFFICIAL.
  - From then on, every NFL weekly report drops the award. The page explains this with "this week's team scores are
    not Sleeper's public feed", which is false there, since the numbers are identical.
  - The same week also shows "Scores are Sleeper's official totals."
  - SC-005 was restated to NBA 2024/2025 (review M2), so nothing in the acceptance list catches this.
  - [inferred]: it depends on prod having consecutive NFL snapshots. That is expected from 055's capture on every
    regular-season leg, but not checked here.
- **Fix:** decide the omission by whether the week's resolved totals differ from the raw ones (any roster where
  `pointsByRoster != rawPointsByRoster`), not by the label. If the award is only omitted where it would really mix
  sources, the gate is honest. Review M2's "label only when a value differs" is the matching fix for the note. That
  one is a product call, so raise it with Allan alongside the open M3 question.

### LOW

- **L1. The FR-013 checks always use the latest snapshot week.**
  - **Where:** `ScoreOverrideService.checks:149`, `totals:155-181`, `records:188-214`.
  - **Scenario:** a later week between the typed weeks and N falls to FEED: an implausible official week, or a
    missing snapshot at N-1 from a capture gap. Both checks then read MISMATCH even when weeks 1–2 were typed
    exactly. The panel says "these do not match Sleeper" with no week named.
  - The records check also only tests `latestFinal >= through`. Plan H1 asked for "when that record is through the
    same week". A snapshot that lags `roster_season` (055's CDN-skew case) compares W-L through N with Sleeper's
    record through N+1.
  - **Fix:** pick the largest N whose weeks 1..N are all non-FEED or overridden. Or list the sources of the weeks in
    the sum, and require `last_scored_leg == through` for the record check.
  - [code]
- **L2. Persisted outputs are not refreshed by a save.** Realized power rankings (`rankings.save`) and playoff odds
  (`odds.save`) are written only by the commissioner's recompute (`LeagueHistoryController:964-969`). After an
  override they keep the feed-derived numbers until the next recompute. The panel copy says the scores "replace the
  public feed for this week on every page" (`CommissionerScoresPanel.tsx:173-174`). Either say so in the copy, or
  note it in verification. [code]
- **L3. A JSON number with more than about 17 significant digits is rounded silently, and `1e400` gives a 500.**
  Jackson's default parses a JSON float into a `DoubleNode`, and `decimalValue()` is `BigDecimal.valueOf(double)`.
  So `239.00000000000000001` arrives as `239.0` and is accepted. `1e400` becomes Infinity, and `BigDecimal.valueOf`
  throws NumberFormatException. The client never sends either. To make "refused, never rounded" literal, read with
  `USE_BIG_DECIMAL_FOR_FLOATS`, or check `isFinite`. [inferred, from Jackson's documented defaults; not run]
- **L4. Two concurrent saves of one week can 500.**
  - Under READ COMMITTED, B's `delete` waits on A's row locks, then sees A's committed inserts as new rows it did not
    delete.
  - B's inserts then hit the PK and throw `DuplicateKeyException`.
  - This needs a commissioner double-click or two tabs. Rollback is never tested either.
  - **Fix:** `insert … on conflict (league_id, week, roster_id) do update`, after the delete. [inferred]
- **L5. Clear is disabled whenever the source isn't COMMISSIONER** (`CommissionerScoresPanel.tsx:267`). In the L1
  read-time fallback (override rows present, resolved FEED), the rows can't be cleared from the UI. The view does
  return `overridePoints`, so gate on `teams.some(t => t.overridePoints != null)`. [code]
- **L6. The records-check UNAVAILABLE copy is wrong in two of its three causes.** "the weeks Sleeper's stored record
  covers are not all final yet" (`CommissionerScoresPanel.tsx:234`) is also shown when there is no snapshot at all
  (`checks:146-148`) or a roster has null wins. [code]
- **L7. N+1 resolves.** Each `resolve()` is a probe plus four queries for any league with a snapshot or override,
  which since 055 is every current Sleeper league, NFL included.
  - One Awards load calls it about 7 times, so about 35 extra queries:
    - `scoreResolver.resolve`;
    - `pairedWithScores`;
    - `extremes` ×2 via `allScores`;
    - `biggestBlowouts` and `closestMatchups` via `pairedWithScores`;
    - ExpectedWins `finalGames`.
  - The weekly report calls it twice.
  - Rivalry `chainGames` and HeadToHead resolve once per league in the chain. Past seasons cost only the probe.
  - HallOfFame calls `chainGames` twice per chain, plus `through` per league.
  - This is plan M5, accepted, with a measurement owed in verification. [code]
- **L8. The totals check is tautological over an all-OFFICIAL prefix.** It reads MATCH by construction, since Σ of
  `fpts(n) - fpts(n-1)` equals `fpts(N)`. It is meaningful only while some week 1..N is COMMISSIONER or FEED. It is
  correct for NBA 2026 weeks 1–2, but the UI's "every team matches Sleeper" overstates what was checked on an NFL
  week. [code]
- **L9. `onePlayerCarry` prints "X of Y" with the raw feed total Y** next to a matchup card that shows the resolved
  total. This is by design (FR-012), and `PER_PLAYER_FEED_NOTE` covers it in words. It is noted so nobody files it
  later. [code]

## Plan conformance

The code matches the amended plan:

- D1–D6;
- H1 (checks, warn-only);
- H3 (the four repository reads resolve, the pot tests are re-homed to `WeekScoreResolverTest`, and the
  `WeekScoreOverrideIT` regression exists);
- M1 (raw totals for the efficiency ratio and the carry, SELF_INFLICTED omitted on non-FEED weeks);
- M4;
- L1–L3.

The open item is M3 (the OFFICIAL tier on every page before 055's week-3 check). It is still Allan's call. Finding M2
above is a concrete consequence of it.

## Resolution (2026-11-05, fix pass)

- **M1, fixed.** The panel starts empty, with the in-use score as a placeholder. It prefills only from scores already
  entered and refuses blank boxes, naming the teams.
- **M2, fixed.** Self-inflicted wound is omitted only when some roster's resolved total differs from its raw total.
  An OFFICIAL week whose totals equal the feed keeps the award (unit test added).
- **LOWs fixed:**
  - The panel copy now says stored power rankings and playoff odds update on the commissioner's next recompute.
  - Clear is enabled whenever override rows exist.
  - The records-unavailable wording is now true for all three causes.
  - Non-finite and out-of-range points return 400. Bound: `numeric(8,2)` holds 999,999.99. The reviewer's request
    said 99,999.99, and that figure was wrong.
  - `replaceWeek` uses on-conflict upsert inside its transaction.
  - Both checks carry `feedWeeks`, and the panel explains that a mismatch is expected while those weeks are on the
    feed.
- **Left as documented:** the Jackson double precision past 17 significant digits; a guaranteed MATCH when every
  covered week is official; the one-player carry using the feed total; and the resolver cost (measured at about
  0.1 s for a warm Awards load, see verification.md).
- **Found in live verification:** a UI save returned 403 "Invalid CORS request". The cause was the dev port (5187)
  missing from `CORS_ORIGINS`, not this code. With the origin allowed, the same save returns 200. The panel's
  "now using entered by the commissioner" wording was also fixed.

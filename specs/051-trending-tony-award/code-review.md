# Code review: spec 051 "The Trending Tony Award" (bug hunt)

*2026-10-27. Reviewer pass on the uncommitted tree of worktree `051-trending-tony-award` (base main 7168753).
This was a hunt for defects, not a style pass. The tree was reviewed cold against data-model.md (authoritative),
spec.md, contracts/superlatives-api.md and plan-review.md.*

## What was executed (not assumed)

- `./gradlew test --tests '*TrendingPickupsTest' --tests '*SeasonSuperlativesTrendingTonyTest' --tests '*SuperlativeRebuildersTest' --tests '*SportTrendingRepositoryIT' --tests '*MostAdded*'`
  gave BUILD SUCCESSFUL: TrendingPickupsTest 13/13, SeasonSuperlativesTrendingTonyTest 11/11, SuperlativeRebuildersTest 3/3,
  SportTrendingRepositoryIT 14/14 (**0 skipped**, run against Postgres on 5433), MostAddedPlayersTest 9/9,
  MostAddedPlayersTopTest 10/10, SeasonSuperlativesMostAddedTest 3/3.
- `./gradlew test --tests '*SuperlativesControllerIT' --tests '*SuperlativesStandingsIT' --tests '*NoSportNameInSuperlativesTest' --tests '*SuperlativeSportRulesTest' --tests '*SeasonSuperlatives*'`
  gave BUILD SUCCESSFUL. Every suite passed with 0 skipped: ControllerIT 16, StandingsIT 8, the rest as listed in the XML.
- `cd web && npx tsc -b` exits 0. `npx vitest run` on the 5 changed test files: 180/180.
- Migration collision check: no `V4[2-9]__*` on any local or remote branch, or in any sibling worktree, except this
  one's V42. On 5433, `flyway_schema_history` shows V42 applied (11:04:15 today).
- 5433 data shape: `league_transaction.type` is uppercase (`FREE_AGENT` 2481, `WAIVER` 468, `COMMISSIONER` 28,
  `TRADE` 26). The `adds` keys are bare Sleeper ids (`{"4039": 3}`), and `sport_trending.sleeper_player_id` uses the
  same form (`13296`, `JAX`). So the `snapshotRanks` player filter and the `CompletedAdd.playerId` lookup use the
  same key.

## Findings

### B1 (MEDIUM): one unavailable reason states a falsehood when pickups after history began went unjudged

`SeasonSuperlativesService.java`, `trendingTonyFromJudged`, the `judgedCount == 0` branch:

```java
boolean noHistoryYet = earliestSnapshot.isEmpty()
        || (lastPickupAt != null
            && earliestSnapshot.get().toInstant().isAfter(lastPickupAt.plus(TRENDING_MATCH_WINDOW)));
return unavailable(Kind.TRENDING_TONY, noHistoryYet
        ? "Trending history wasn't being recorded yet for this season."
        : "No pickups since trending history began on " + ... + " (UTC).");
```

The second reason is chosen whenever *any* snapshot exists at or before the last pickup + 24h. That is not the
same as "there were no pickups after history began". Inputs that print a false sentence:

- **Gap.** History began 2026-10-28. Trending fetches failed, or Sleeper returned empty lists, so there are only
  `entry_count = 0` snapshots near a 2026-11-05 pickup. That pickup is NOT_JUDGED. `earliest` (10-28) is before
  `last + 24h` (11-06), so the card reads "No pickups since trending history began on 2026-10-28 (UTC)" although a
  pickup on 11-05 is right there. Empty snapshots count toward `earliestSnapshotAt`, which reads every row, but
  `judge` skips them. That combination is what makes this case reachable.
- **Undated pickups.** Every counted pickup has `createdAt == null`, so `lastPickupAt` is null. With any snapshot
  on file, the card says "No pickups since…" although undated pickups exist.

Status: verified by reading the branch logic. No test was written. The existing test
`nothingJudgedWithHistoryThatBeganGivesTheSinceDateReason` covers only the honest case. This is the project's
central rule: the output claims something it didn't measure.

Fix: base the second reason on the pickups themselves. If any counted pickup has
`createdAt >= earliest - 24h`, say something like "No trending list was recorded within a day of this season's
pickups since <date>". Use "No pickups since…" only when no counted pickup falls after that point. Treat
`lastPickupAt == null` with history present as its own case, or as "couldn't be judged", never "no pickups". Add a
test for the gap case.

### B2 (MEDIUM): the gathering half of the award has no test that touches a stored snapshot

`SeasonSuperlativesService.trendingTonySuperlative` (the ESPN gate, the `txRows.isEmpty` gate, the counted-pickup
filter, the `[first-24h, last+24h]` range, `pickedUp` ids, `league.sport().code()`, and the ranks map assembly) is
reached only by `SuperlativesControllerIT` and `SuperlativesStandingsIT`. Neither seeds a
`sport_trending_snapshot` row or asserts anything about `TRENDING_TONY`. Grepping `src/test` for
`TRENDING_TONY|trendingCoverage` finds only the two pure tests. All of these mistakes would pass every suite:

- passing `league.sport().name()` (`"NBA"`) instead of `.code()`
- building the range from `first` alone
- passing `rosterIds` instead of player ids to `snapshotRanks`
- dropping the ESPN gate

The code is correct today (checked: `Sport.code()` is lowercase and matches `TrendingRefresh`'s `sport.code()`
write). However, AGENTS.md bug classes 3 and 6 are exactly "correct by reading, never executed on this path".

Status: verified (grep, plus the IT list above).

Fix: add one IT, in either the controller or the standings IT. Seed a Sleeper league-season with three or four
WAIVER/FREE_AGENT transactions and two snapshots for its sport, one inside the window and one outside it. Assert:

- the TRENDING_TONY holder and value
- `trendingCoverage.judged`, `notJudged` and `totalPickups`
- one `TRENDING_PICKUP` detail row's `trendingRank` and `snapshotAt`

The live-verification stage can stand in for this, but a regression test should exist.

### B3 (LOW): the loaded header range is a fixed 24h, but the judge uses each snapshot's stored lookback

The range is built with `TRENDING_MATCH_WINDOW = Duration.ofHours(24)` (`SeasonSuperlativesService.java`, spec 051
block), while `TrendingPickups.judgingSnapshot` matches on `Duration.ofHours(h.lookbackHours())`. The data model
says the lookback is "stored, never assumed by the reader", yet the reader assumes it when it picks the range. The
two agree today only because `TrendingRefresh.LOOKBACK_HOURS = 24` is the only value ever written. If the lookback
ever becomes 48, a snapshot 30h after a pickup qualifies in the judge but is never loaded, so the pickup turns
NOT_JUDGED without any signal. The same constant also drives the `noHistoryYet` comparison.

Status: inferred (latent, cannot fail with today's data).

Fix: either widen the range to the maximum stored lookback (cheap: `select max(lookback_hours)`), or reference
`TrendingRefresh.LOOKBACK_HOURS` and assert in a test that the two are equal.

### B4 (LOW): the "How this works" text describes the matching rule wrongly

`web/src/pages/Superlatives.tsx` says: "Each pickup is judged by the recorded list nearest to it within a day,
preferring one taken after the pickup." The rule (FR-002/A3, `TrendingPickups.judgingSnapshot`) is *earliest
after, else latest before*. A list 23h after the pickup wins over one taken 1 minute before it. That is not
"nearest", and the doc's own A3 note argues the point at length. FR-011 requires the copy to describe the rule.

Status: verified by reading.

Fix: "…by the first list recorded in the 24 hours after it (whose window covers the pickup), or, if there is none,
the last one recorded in the 24 hours before."

### B5 (LOW): the "Judged since <date>" line can postdate pickups it judged

`judgingSinceAt` is the earliest *judging snapshot's* time (`min(snapshotAt)`), and the rule is after-first. Take a
pickup at 10-27 23:00 judged by the 10-28 11:00 list: the card reads "Judged since 10/28" while a 10-27 pickup is
counted. This matches data-model.md as written, so it is not a deviation, but the line reads as the first pickup
date that could be judged.

Status: verified by reading.

Fix (optional): carry `min(createdAt)` of judged pickups as the "since" date, or reword to "Trending lists used
from <date>".

### B6 (LOW): the holder detail is unbounded

`trendingTonyFromJudged` emits a `TrendingPickupDetail` for *every* trending pickup of each holder.
`AwardWinners.holderDetailLines` prints them all under the holder, on the Awards card and in the Hall of Fame
trophy case. A season-long chaser could have 15–30 lines. WAIVER_WIRE_WARRIOR caps holder lines at 3, as the
backend comment there notes. The full list already lives in "See all" → Players.

Status: inferred (no real season has history yet).

Fix: cap the detail at 3–5 per holder, the same way the waiver award does, and leave the full list to
`playerContributions`.

### B7 (LOW): the Hall of Fame and the Awards page now colour the same award differently

`TrophyCase.tsx` now calls `hueForIndex(i, shown.length)` with the unavailable TRENDING_TONY filtered out (13 kinds
shown), while `Superlatives.tsx` uses `data.superlatives.length` (14). Before this change both used 13, so each
award had the same hue on both pages. For every past season they now differ.

Status: verified by reading. Cosmetic.

Fix: in TrophyCase, compute the hue from the index in the unfiltered `d.superlatives`.

### B8 (LOW, process): tasks.md shows every task unchecked

All tasks from T001 onward are still `- [ ]` (`grep -n "\- \[ \]" tasks.md`), although the work exists in the tree.
That includes T001, the migration-number re-check that lesson #43 requires before writing V42. This review re-ran
the check (no collision). The file should record that the check was done.

Status: verified.

## Note for live verification (not a code defect)

The shared 5433 DB holds hand-made `nfl` snapshots: ids 1001–1006, fetched 2026-09-05 … 10-09, entry counts
1/2/3/1/1/0. They sit beside two real ones (ids 18, 36, from 11:07 today). `SportTrendingRepositoryIT` cleans only
its own `it-trending` sport, so these came from someone else, probably a verification fixture. On this DB,
`earliestSnapshotAt('nfl')` = 2026-09-05, and every local NFL league will be judged against those fixtures. Whoever
runs verification.md must either own them or delete them before trusting a local NFL card.

## Checked and fine

- **After-first selection** (`TrendingPickups.judgingSnapshot`):
  - "after" is inclusive `[P, P+lookback]` (`!s.isBefore(p)` and `!s.isAfter(p+lb)`), and a snapshot exactly at P
    counts as after.
  - "before" is `[P-lookback, P)`.
  - Earliest-after and latest-before are chosen independent of the caller's order.
  - `entryCount == 0` headers are removed before selection.
  - `createdAt == null` gives NOT_JUDGED.
  - Comparisons are all `Instant` (`fetchedAt().toInstant()`), so there is no offset mix-up.
- **Range vs window**: the union of the per-pickup windows is exactly `[first-24h, last+24h]` over the same filtered
  set the judge counts. The filter is the same predicate (`isCountedPickup && week <= throughWeek && eligible`), and
  `first/last` skip null createdAt. `snapshotHeaders` bounds are inclusive `>=`/`<=`, matching the judge. A needed
  snapshot cannot fall outside the range while lookback is 24 (see B3).
- **`snapshotRanks`**: filters `rank <= TOP_N` and the picked-up ids. The `bigint[]`/`text[]` array binds via
  `createArrayOf` passed a real-Postgres IT (`snapshotRanksFiltersByRankAndPlayerIdsThroughRealArrayBinds`). It
  short-circuits on empty input. A player absent from the 25 stored rows is NOT_TRENDING, which is sound because
  25 > 15.
- **Buckets**: `total = judged.size()` and `notJudged` is counted, so `judged + notJudged == total` by
  construction. `tally` counts TRENDING+NOT_TRENDING as judged. Test `theBucketsSumToTheCountedPickups` covers it.
- **Bug class 1 (preference ordering)**: covered by `aWinnerWithFourBeatsAManagerWithTwo` and
  `moreTrendingPickupsOutranksMoreTotalPickups`. Standings use `SuperlativeStandings.rank(entries, false)`
  (descending), and the TOP_N boundary (15 in, 16 out) is tested.
- **Standings**: every roster in `rosterIds` (orphans included), a zero row noted "no judged pickups", and
  competition ranks. In contributions, competition rank resets only when `trendingRank` changes, and the order is
  rank → week → roster → player. Ties name all top rosters, sorted by id.
- **MostAddedPlayers refactor**: `!isCountedPickup(a) || a.week() > throughWeek` is logically identical to the old
  two lines (type set, `week >= 1`, uppercase literals that match the stored types). Its 22 tests pass.
- **Rebuilders**: `withStandings` and `withUsernames` both pass `s.trendingCoverage()` through the 15-arg canonical
  constructor. Every other `new Superlative(...)` in the service builds a fresh non-TT card, where null is correct.
  No other class constructs `Superlative` (grep).
- **Controller**:
  - `trendingCoverage` is emitted on every superlative (null otherwise).
  - The `TRENDING_PICKUP` branch uses `LinkedHashMap`, so the nullable `position` is safe.
  - Instants serialize as ISO `Instant.toString()`.
  - `api.ts` mirrors every key and type: `judgingSinceAt`, `judged`, `notJudged`, `totalPickups`, `type`,
    `playerId`, `playerName`, `position`, `rosterId`, `week`, `addType`, `trendingRank`, `addCount`, `snapshotAt`.
    The `unit` union gains `'PICKUPS'` and the kind union gains `'TRENDING_TONY'`.
- **`replace()`**:
  - It is still one `@Transactional` method called through the Spring proxy from `TrendingRefresh`.
  - The snapshot rows use the same `fetchedAt`, the same filtered `entries` and the same `i+1` ranks as the current
    list.
  - `entry_count = entries.size()`.
  - The sport code is `sport.code()` on both write (`TrendingRefresh`) and read (`league.sport().code()`), and both
    are lowercase.
  - `recordFailure` writes no snapshot (tested).
- **Frontend**:
  - `contributionFigure={… : undefined}` falls back to the `figure` default as intended.
  - Fragment lines in `holderDetailLines` are wrapped in keyed `<span key={li}>`, so there is no key warning.
  - The coverage line renders only when `available && trendingCoverage`, and the NFL-only "team defenses excluded"
    clause tests `sport === 'nfl'` (the `Sport` union is lowercase).
  - The TrophyCase filter hides only an *unavailable* TT.
  - LeagueHome picks the first available award with holders, so TT (last) never pre-empts others.
  - Sleeper is credited (FR-012) in How this works.
- **SC-004 (no other award changes)**: TT is appended last in `Kind`, reuses the already-loaded `txRows` (no second
  transactions query), and touches no shared state. All existing superlatives suites pass unchanged.

---

## Fix status (2026-10-28)

| Finding | Status |
|---|---|
| B1 MEDIUM | **Fixed.** Reason selection now uses the season's completeness (spec A5). The same bug class was also found live on NBA 2026 (verification V2c). Pure tests cover a live season, a complete season, a fetch gap, all-null times, and no snapshot at all. |
| B2 MEDIUM | **Fixed by refactor, not an IT.** `trendingTonySuperlative` is static and package-private and takes the repository. `SeasonSuperlativesTrendingTonyGatherTest` (4 tests, Mockito) asserts the sport code, the ±48h range, the ids passed, and that ESPN or empty transactions never touch the repository. The SQL itself remains covered by `SportTrendingRepositoryIT`. |
| B3 LOW | **Fixed.** The header margin is now 48h. |
| B4 LOW | **Fixed.** The wording in "How this works" now describes the after-first rule. Checked in the browser. |
| B5 LOW | **Fixed.** `judgingSinceAt` is now the earliest judged pickup. |
| B6 LOW | **Fixed.** At most 3 detail rows per holder. `playerContributions` keeps all of them. |
| B7 LOW | **Accepted.** The trophy case renumbers hues over the cards it shows, so a card's hue there can differ from the Awards page. Cosmetic. |
| B8 LOW | **Fixed.** tasks.md checkboxes are ticked, and T001's migration re-check was run by the build agent (V42 free). |
| Live-found | The singular "0 of 1 judged pickup" is fixed. |

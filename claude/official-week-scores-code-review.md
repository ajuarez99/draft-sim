# Official week scores — bug-hunting code review

2026-11-03. Reviews the uncommitted change in worktree `055-official-week-scores` (base `0446524`), against
`claude/official-week-scores.md`. This is not a style pass. No source files were edited.

Labels: **[measured]** = executed or read live today. **[code]** = read from the diff or the tree.
**[inferred]** = reasoned, not run.

## What was run

- **Backend tests**, against a throwaway Postgres 14 on `localhost:5441`, not the shared 5433, which was
  down. Flyway applied through V44 cleanly. All of these passed:
  - `RosterFptsSnapshotRepositoryIT` 1/1, **not skipped**. This covers the null `fpts_against` bind to
    `numeric` and the upsert.
  - `RosterFptsSnapshotDerivationTest` 3/3
  - `FptsSnapshotLegTest` 2/2
  - `WeeklyPotServiceTest` 20/20
  - `LeagueHistoryIngestServiceTest` 16/16
  - `ChampionOnlyWhenCompleteTest` 2/2
  - `PartialWeekHealsIT` **skipped**: it hard-codes `localhost:5433`. [measured]
- **Frontend:** `npx tsc -b` is clean, and `vitest WeeklyPot.test.tsx` passes 13/13. [measured]
- **Sleeper reads** (public, read-only). The script is at
  `C:\Users\allan\AppData\Local\Temp\claude\C--Users-allan-source-draft-sim\ea47ece6-9103-4487-8c59-009b9a26a313\scratchpad\fpts_check.py`.
  Results:
  - **NFL 2026** (`1346366555759341568`), `leg 8 / last_scored_leg 8`: `fpts` equals the cumulative
    `/matchups` total through week 8 for **12/12** rosters. [measured]
  - **NFL 2025** (`1254190892974084096`, complete, `playoff_week_start 15`): `fpts` equals the cumulative
    total through **week 14** for 12/12 rosters, and through week 17 for 0/12. So `fpts` is
    **regular-season only**: it stops moving once the playoffs start. The design doc lists this as
    unmeasured. It is now measured for NFL, and the design's choice to capture regular-season legs only is
    correct. [measured]
  - **NBA 2025** (`1229352720222134272`, complete, `psw 19`): Σ`fpts` is 52016.0, the regular-season
    `/matchups` sum through week 18 is 51589.5, and the sum through week 21 is 59924.5. This is consistent
    with regular-season-only `fpts` plus the known feed drift. [measured]
  - **NBA 2026:** `leg 3 / last_scored_leg 2 / psw 20 / league_average_match 1`. Today's `/rosters`
    values equal the drift doc's ~15:10 UTC table exactly, so `fpts` holds still within a leg.
    Σ`fpts` = Σ`fpts_against` = 5773.5, so the median game adds nothing to either. `fpts_decimal` is
    present (including `0`) and is in cents. [measured]
  - **CDN caching:** `/league/{id}` and `/league/{id}/rosters` are each served with
    `cache-control: public, s-maxage=300, stale-while-revalidate=300, stale-if-error=600`
    (Cloudflare, `cf-cache-status: HIT`). The two are cached **independently**: in the same second
    their `age` headers read 33 and 16. [measured]

## Findings

### H1 — HIGH: the "seconds" race is really up to ~10 min of CDN skew, and the bad direction is never healed

**Where:**
- `LeagueHistoryIngestService.java:216-246`: `snapshotFpts` keys the row on `last_scored_leg` from the
  `league` map that `sleeper.leagueChain` fetched at the start of the pass, then writes `fpts` from a
  separate `/rosters` call.
- `RosterFptsSnapshotRepository.officialWeekScores`: no plausibility check on the result.

**Mechanism.** The two endpoints are cached independently for up to 300 s, plus 300 s of
stale-while-revalidate (600 s on error). So a single pass can pair a **stale** `/league`
(`last_scored_leg = N`) with a **fresh** `/rosters` (`fpts` already includes week N+1). The design doc
calls this a seconds-long window that "the next pass in the same leg overwrites". In the bad direction,
**nothing overwrites it**:

1. Sleeper processes week N+1 at time T.
2. A refresh at T + a few minutes writes `through_week = N` with `fpts(N+1)`.
3. The next refresh is at least an hour later (`RefreshProperties.STALE_AFTER`). It sees
   `last_scored_leg = N+1` and writes the N+1 row. The N row is now frozen at the wrong value.

**Concrete failure** (N = 3, NBA 2026, 12 rosters):
- Week 3 official = `fpts(4) − fpts(2)`, which is **two weeks of points**. The pot pays the two-week leader.
- Week 4 official = `fpts(4) − fpts(4)` = **0.00 for every roster**. The feed is non-zero, so the week is
  DECIDED (not NO_SCORES). `WeeklyPotLedger` then makes it a 12-way tie with 0 payers and $0. The page
  lists 12 names with a "Tie" pill.
- Both weeks are labelled **OFFICIAL**, with no asterisk.

**The opposite direction** (fresh `/league`, stale `/rosters` on the first read of leg N+1) writes
`fpts(N)` into row N+1. That one is healed by any later read in the same leg, but only if one happens.

**Unmeasured, and possibly wider:** whether Sleeper's origin moves `last_scored_leg` and `fpts` atomically.
If it does not, the window is whatever that lag is, on top of the CDN skew.

**Status:** CDN headers [measured]; failure path [code + inferred]. Not reproduced end-to-end, because that
needs a live week transition (the next one is ~2026-11-09/10).

**Suggested fix** (any one of these helps; the first is cheap and catches both directions):
- **Plausibility gate in the derivation.** Treat a week as FEED (not OFFICIAL) when any roster's
  difference is negative, when every roster's difference is 0 while the feed is non-zero, or when
  |Σofficial − Σfeed| / Σfeed is beyond a stated bound. The drift is about 8%; a doubled week is about
  100%. Label the bound as hand-set.
- Re-read `/league` after `/rosters` and skip the capture if `last_scored_leg` differs between the two
  reads. This narrows the window but does not close it, because both reads can be stale.
- **Owed on the 11-09/10 transition:** poll `/league` and `/rosters` every couple of minutes across week 3's
  processing to measure the real skew.

### M1 — MEDIUM: the capture path itself has no test; the acceptance criterion "IT: the capture writes rows keyed by `last_scored_leg` …" is not met

**Where:** `LeagueHistoryIngestServiceTest.java:46` and `ChampionOnlyWhenCompleteTest.java:56` pass a bare
`mock(RosterFptsSnapshotRepository.class)` and never verify it. `FptsSnapshotLegTest` checks only the static
predicate, and `RosterFptsSnapshotRepositoryIT` checks only the repository.

**Untested at any level:**
- that `ingestStandings` calls `upsertAll` with `last_scored_leg`, not `leg`;
- that a roster with null `fpts` is skipped;
- the whole-plus-cents conversion (`points()` → `cents()`);
- the playoff skip as wired, for example a swapped argument order in the `fptsSnapshotLeg(...)` call.

**Concrete failure:** a refactor that passes `leg` instead of `last_scored_leg`. For NBA, `leg` is
`last_scored_leg + 1` mid-week, so every row would be shifted one week and every OFFICIAL week would be
off by one. The whole suite would stay green.

**Suggested test:** `verify(fptsSnapshots).upsertAll(eq(leagueId), eq(2), argThat(...))` with a league map
that has `leg 3 / last_scored_leg 2`, plus one case with `last_scored_leg = playoff_week_start` asserting
`never()`. [code]

### M2 — MEDIUM (process): V44 is above an unmerged V43, so merge order matters

**Where:** `V44__roster_fpts_snapshot.sql`. `origin/main` stops at V42. V43 is
`V43__historical_draft_game.sql` on branch `053-historical-draft-game` (PR #53, unmerged).

**Concrete failure:** if 055 deploys before 053, prod records V44. When 053 later ships V43, Flyway's
default validation (`outOfOrder=false`; `application.yml` sets only `enabled` and `locations`) fails at
startup with "resolved migration not applied", and the backend does not boot.

**Fix:** merge 053 first, or renumber at merge time. AGENTS.md lists this as a stop-for-Allan item.
[code; Flyway behaviour inferred from its defaults, not run]

### L1 — LOW: one week can mix official and feed scores

**Where:** `WeeklyPotService.java:171-174`,
`officialWeek.getOrDefault(r.rosterId(), r.points())`.

`officialWeek` is keyed on `roster_season` roster ids, but rows come from `roster_week_points`. A feed
roster with no `roster_season` row would keep its feed score inside an OFFICIAL week, so official and feed
scores would be compared directly. Both tables come from the same `/rosters`/`/matchups` pass, so this is
very unlikely today.

**Safer:** if any feed roster is missing from `officialWeek`, fall back the whole week to FEED, which
matches the doc's "never half-filled" rule. [code + inferred]

### L2 — LOW: one roster without `fpts` turns the whole league's weeks to FEED

**Where:** `snapshotFpts` skips `r.pointsFor() == null`, and `officialWeekScores` requires every
`roster_season` roster.

**Failure:** if Sleeper ever omits `settings.fpts` for a roster (for example an orphan at 0; Sleeper is
known to omit zero stat fields elsewhere), no week in that span is ever OFFICIAL. This fails safe and is
labelled FEED, so nobody is paid wrongly.

Today all 12 NBA rosters carry `fpts`, and `fpts_decimal: 0` is sent rather than omitted [measured].
Whether `fpts: 0` is omitted is not measured. **Suggested:** treat a missing `fpts` as 0.00 only when the
roster also has 0 games/wins/losses, or leave it as is and note it. [inferred]

### L3 — LOW: a stat correction that lands after the leg moves is booked in the wrong week, silently

**Where:** this is documented in the design doc. The new point is that a downward correction to week N−1
makes week N's difference smaller, and possibly **negative** for a low scorer. Nothing rejects a negative
weekly score. The H1 plausibility gate would also cover this. [inferred]

### L4 — LOW: the upsert is per-row with no transaction

**Where:** `RosterFptsSnapshotRepository.upsertAll`.

**Failure:** a failure partway through leaves a leg holding some rosters from this read and some from an
earlier one. Separately, `IngestController`'s manual ingest is not single-flighted with the on-visit
refresh, so two passes can interleave rows from different `/rosters` reads. Both heal on the next pass in
the leg, with the same last-read caveat as H1. A `@Transactional` or a single multi-row upsert would make
each leg's snapshot one read. [code + inferred]

### L5 — LOW (docs): a cited file is not on this branch

`WeeklyPotService.java` (the `ScoreSource` javadoc), `RosterFptsSnapshotRepository.java`, the V44 header
and `claude/official-week-scores.md` all cite `claude/nba-2026-score-drift.md`. That file is not in this
worktree. It exists on branch `fix-weekly-report-median-record` (commit `3aa2bb9`) and as an untracked file
in the main checkout. Bring it onto this branch, or merge that branch first, or the references dangle on
main. [measured]

### L6 — LOW (UX): the footnote covers only the week list

**Where:** `WeeklyPot.tsx:204-250`.

The asterisk and note appear only in "Week by week". The balances table, the settle-up debts, and
`CommissionerPotPanel`'s header all include FEED-scored money with no mark. Also, "Weeks without a * use
the official totals" is not true of NOT_FINAL, MISSING or NO_SCORES rows, which also carry no asterisk.
Those rows show no totals, so the risk is small. A hover `title` is invisible on touch, but the footnote
covers that case. [code]

## Checked and found correct

- **Leg key:** `last_scored_leg` is the right key for both sports.
  - NFL `fpts` at `last_scored_leg = 8` equals weeks 1..8 exactly [measured].
  - NBA `fpts` excludes the live week (drift doc [measured]) and did not move within leg 2 today
    [measured].
  - Both the week index in `roster_week_points` and the capture key are Sleeper legs [code].
- **Playoff exclusion:** `fpts` is regular-season only (NFL 2025 [measured]), so skipping legs at or after
  `playoff_week_start` loses nothing. A completed previous season in the chain has
  `last_scored_leg >= psw` (17 ≥ 15, 21 ≥ 19 [measured]), so it is never captured. Seasons in the `skip`
  set never read `/rosters` [code].
- **Median game:** `fpts` carries no median adjustment (Σ`fpts` = Σ`fpts_against` [measured]).
- **Other `/rosters` readers:** `LeagueIngestService.ingestChain` does not write `roster_season`. The ESPN
  path writes `roster_season` without snapshots, so ESPN weeks are always FEED, and the pot refuses ESPN
  anyway [code].
- **Ties:** `WeeklyPotLedger` compares with `compareTo`, so official scale-2 and feed scale-1 values tie
  correctly. No `equals` is used on scores [code].
- **NOT_FINAL / NO_SCORES:** both are still decided from the feed before the official swap. That is
  consistent, because week N's snapshot exists exactly when the feed can call week N final
  (`WeekFinality.isFinal`: `last_scored_leg >= N` and `leg > N`) [code].
- **JDBC:** the null `BigDecimal` bind to `numeric` via `JdbcClient.params` works against real Postgres
  (IT, [measured]). The `(RowCallbackHandler)` cast only disambiguates the overload [measured by the IT].
- **Migration:** append-only, with the FK cascade on `league(id)`. The PK `(league_id, roster_id,
  through_week)` covers the `where league_id = ?` read [code].
- **Constructor and record consumers:** the only Java constructors of `WeeklyPotService` and
  `LeagueHistoryIngestService` are Spring and the three updated tests. The Spring ITs mock the beans. No
  other Java code constructs `WeeklyPotService.Week`. On the frontend, `WeeklyPotWeek.scoreSource` is
  read only in `WeeklyPot.tsx`. `CommissionerPotPanel` and `analytics.ts` do not read weeks [code].
- **TS type vs Java record:** `scoreSource: 'OFFICIAL' | 'FEED' | null` matches `Week`'s nullable enum
  field for field. Jackson has no `non_null` inclusion set, so null is serialised as `null`, not omitted
  [code].

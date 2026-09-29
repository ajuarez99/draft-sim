# 08 — Power rankings: "Your ballot" reads the wrong week, and the #1 card says "pts" for a rank

**Severity: Medium. Verified locally** on league 1346366555759341568, signed in as popsharky.
Both are the "count and label from one source" lesson: a number printed beside data from a
different week, or under a unit it doesn't have.

## What's there now

**A. Wrong week.** The League-vote ladder shows the latest week with ballots (`tableWeek`,
`web/src/pages/PowerRankings.tsx:602-604`; week 1 here). The "Your ballot" column reads
`myRankByRoster` (`:617`), built from `ballot`, fetched by `getBallot(sleeperLeagueId)` with
no week (`:429`). `GET /leagues/{id}/ballot` defaults to the **open** week
(`LeagueHistoryController.java:631`), here week 3. popsharky has no week-3 ballot, so every
row says "No ballot" (`:1011`). But `ranking_ballot` has his week-1 row, and the Homers panel
(which reads week 1, `:665`) shows his -1.
- Same bug, second place: the "You voted yourself Nth" line (`:651`, `:811-812`) sits in the
  hero, which reads `heroWeek`, but it takes its rank from the open-week ballot.
- `spaceStatsFor(e, gridRows, myRank)` (`:963`) also gets the wrong-week `myRank`, so the
  "mine" marker and the delta pill are wrong too.
- No backend change is needed. The GET already takes `?week=` (`:620`), and
  `api.ts:1539` `getBallot(id, week?)` already passes it.

**B. Wrong unit.** The #1 card prints `{scoreLabel(story.top)} pts` (`:768`). `story.top`
always comes from MEMBER rows (`heroRows`, `:623-628`). The hero is fixed to League vote
whatever tab is selected. So `score` there is `avgRank` (`MemberRankingService.java:171`),
and "3.40 pts" means an average ballot rank of 3.4. `scoreLabel` (`:713-717`) knows nothing
about the kind. What `score` means depends on the kind:
- `COMPUTED_REALIZED`, week ≥ 1: average starter points per week
  (`PowerRankingService.java:238`). "pts" is correct here.
- `COMPUTED_REALIZED`, week 0: `startingLineupValue`, a board value, not points (`:171`).
- `COMMISSIONER`: `null` (`:300`), so the card would print "-- pts".
- `MEMBER`: average rank, where lower is better.

`scoreLabel` is also used bare at `:1008` (the ladder's average column) and at `:1155`
(focus rows across all kinds), with no unit.

## Fix

1. **A:** keep the current `ballot` fetch. It still drives the open-week editor
   (`ballotSeed` `:599`, `ballotBlockState` `:674`, the "N of M in · week 3 open" pill
   `:750-753`), which is correct. Add a second state, `viewedBallot`, fetched with
   `getBallot(id, heroWeek)` when `heroWeek !== currentWeek`, and reuse `ballot` when they
   match. Use `===`, since week 0 is real. Build `myRankByRoster` and `myBallotRank` from
   `viewedBallot`. The "No ballot" / "—" choice at `:1011` must read the same object. The
   column only renders in MEMBER mode, where `tableWeek === heroWeek`. If that changes,
   key the fetch to `tableWeek`. Clear it on failure, the same as `ballot` (`:438-441`).
2. **B:** replace `scoreLabel(e)` with `scoreLabel(e, kind)`, which returns value and unit
   together from one switch:
   - MEMBER → `avg rank 3.40`
   - REALIZED week ≥ 1 → `112.40 pts`
   - REALIZED week 0 → `lineup value 1234`
   - COMMISSIONER or null → nothing, so the card drops the segment rather than print `--`

   The card stops appending a literal `pts`. Export the helper and have `:1008` and
   `:1155` call it too.

## Not in scope

- Letting a member back-date or edit a past-week ballot. The POST stays open-week only
  (`:729-735`).
- Making the hero follow the selected tab.

## Acceptance criteria

- [ ] In the browser on 1346366555759341568 as popsharky, the League-vote column shows his
      week-1 ranks, not "No ballot". "You voted yourself Nth" matches his week-1 ballot, and
      the open-week editor still seeds from week 3.
- [ ] The #1 card reads "avg rank 3.40". The hero is MEMBER-only, so check it on all three
      tabs: its unit must not change. The `:1155` focus rows show `pts`, `avg rank` or
      nothing, matching each row's kind.
- [ ] A vitest (next to `PowerRankings.week.test.tsx`) mocks the week-1 ballot and a week-3
      `mine: null`, then asserts the column renders ranks, not "No ballot". Also assert
      that `getBallot` was called with `heroWeek`.
- [ ] A unit test of `scoreLabel(e, kind)` covers all four cases above, including COMMISSIONER
      null.

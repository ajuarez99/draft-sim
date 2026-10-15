# Code review: spec 037 rivalry week pick (bug hunt, 2026-10-15)

This is the bug-hunting review stage. It is not a style pass. It reviews the uncommitted diff in
worktree `037-rivalry-week-pick`:
- `RivalryWeekPicker` (new)
- `RivalryMatching`
- `RivalryService`
- `RivalryProperties`
- `weights.yml`
- the three new test classes
- `api.ts`, `Rivalries.tsx`, its test and `styles.css`

**What was run:**
- `./gradlew test --tests "*Rivalry*"`: 121 tests, 0 failed, 0 skipped, `RivalryParityIT`
  included.
- `npx vitest run src/pages/Rivalries.test.tsx`: 27/27 passed.
- `npx tsc -b`: clean.
- Two throwaway probes, kept in the session scratchpad and not in the repo, compiled against
  `build/classes` and the test runtime classpath:
  - `Probe037` drives the real `RivalryService` with Mockito repositories.
  - `Fuzz037` runs `RivalryWeekPicker.pick` on 20,000 random leagues: 4–10 managers, 6–13
    weeks, scores from the real `RivalryMatching.score` formula, cap 2.

**Labels:**
- **CONFIRMED** means I demonstrated it with a run.
- **PLAUSIBLE** means it was found by reading the code and not executed.

## Bugs

### B1: Every runner-up week that keeps all the best pairs claims to be "the earliest" (CONFIRMED)

- **Where:** `RivalryService.java:805-810` (`weekReason`), rendered for each runner-up at
  `Rivalries.tsx` "Other weeks" (`<p className="muted small">{o.reason.text}</p>`).
- **What fails:** `tiedWith` is the size of the tie group, so every member of the group gets
  the sentence " It's the earliest of N weeks where they all fit." Only the recommended week
  (rank 0) is the earliest. The runner-ups are later weeks in the same group, so the sentence
  is false for every one of them.
- **Evidence:** `Probe037` scenario A uses 4 managers, weeks 1–5, and puts the history-best set
  {11-12, 13-14} on the schedule once. That makes weeks 2–5 all `ALL_BEST_FIT`. The payload
  has:
  - `REC wk2`: "... It's the earliest of 4 weeks where they all fit."
  - `OTHER wk3`, `OTHER wk4` and `OTHER wk5`: each has the identical "It's the earliest of 4
    weeks where they all fit."

  In football the best set "probably fits most weeks" (plan R7), so this is the normal case
  for the "Other weeks" disclosure, not a corner case.
- **Fix:** Pass the candidate's rank within its tie group, or just "is the recommended one",
  into `weekReason`.
  - Only the first of the group says "It's the earliest of N".
  - A later member says something true, e.g. "One of N weeks where they all fit; week {W0} is
    earlier." Put `W0` in `numbers` too.
  - Add a service test asserting that no `others[]` reason contains "earliest".

## Should fix

### S1: The reason and status text hard-codes "third", but the cap is configurable (1–5) and a pair can already be over it (CONFIRMED)

- **Where:**
  - `RivalryService.java:807`: "without anyone meeting a third time"
  - `RivalryService.java:813` and `:819`: "would be a third meeting in week W"
  - `Rivalries.tsx:280`: NO_WEEK_FITS, "play the same manager a third time"
- **What fails:**
  - **(a) A cap other than 2.** `weights.yml` documents the range as 1–5, and
    `RivalryProperties` accepts it. With `max-regular-season-meetings: 3`, the page says
    "without anyone meeting a third time" while the rule line right under it says "more than
    3 times".
  - **(b) A pair already over the cap (F6).** These pairs are disallowed in every week, so they
    show up as `heldBack`. For such a pair the count would become `reg + 1` (a fourth
    meeting), or stay at `reg` when W is one of their own weeks. The text says "a third
    meeting in week W" in both cases.

    The UI's held-back sentence has the same problem. `NTH[cap + 1]` prints "they'd play a
    third time" from the cap, not from the pair's real count. It also lists `meetsInWeeks`
    including W itself ("already meet in weeks 1, 3 and 5" for W = 3).

    `ALL_BEST_FIT`'s "without anyone meeting a third time" is also false whenever
    `overCapNote` is set, because that note sits a few lines away saying a pair meets 3 times.
- **Evidence:** `Probe037`:
  - **Scenario B** (cap 3): `REC wk3 ALL_BEST_FIT :: ... without anyone meeting a third
    time.`, and `wk2 BEST_UNDER_CAP :: 2 of the best pairings would be a third meeting`. Under
    cap 3 the correct word is "fourth".
  - **Scenario C** (cap 2, where 11-12 and 13-14 meet 3 times): `wk2 :: 2 of the best pairings
    would be a third meeting in week 2`. Week 2 is not one of their weeks, so it would be the
    fourth meeting. `heldBack.meetsInWeeks=[1, 3, 5]` is also printed for `OTHER wk3`, where
    week 3 is their own week.

  Today's cap is 2 and R2 measured no over-cap pairs, so (a) and (b) are latent. They are not
  hit on the three real leagues as configured.
- **Fix:**
  - Word the backend sentences from the cap: "would put them over the {cap}-meeting limit in
    week W", or ordinal(cap+1). Put `cap` in `reason.numbers`, since it is now a number the
    text depends on.
  - For held-back pairs, carry the would-be count (`reg - inW + 1`) on `HeldBack` and let the
    UI print that, rather than `cap + 1`.
  - Exclude W from `meetsInWeeks`, or say "including week W".
  - Make NO_WEEK_FITS read `week.cap` the same way.

### S2: A tie on rank keys 1 and 2 is silent unless the week is ALL_BEST_FIT (CONFIRMED)

- **Where:** `RivalryService.java:811-822`.
- **What fails:** `tiedWeeks` goes into `numbers`, but the `EQUAL_UNDER_CAP` and
  `BEST_UNDER_CAP` text never mentions it. When several weeks tie on total and on kept-best
  pairs, the recommended week is just the earliest. The reason doesn't say so, so the reader
  can't tell why week 2 was chosen over weeks 3–5. FR-006 asks for a concrete reason, and plan
  R7's mitigation is "saying it's a tie".
- **Evidence:** In `Probe037` scenario C, the recommended week 2 has `tiedWeeks=4`. Its text is
  "2 of the best pairings would be a third meeting in week 2, so they're swapped out. This set
  scores 1 of a possible 8.333." Weeks 3, 4 and 5 carry the identical score.
- **Fix:** Append the same tie clause, worded for this case, when `tiedWith > 1`, e.g. "Weeks
  {...} score the same; this is the earliest." It should apply only to the first of the group,
  as B1 requires.

### S3: "as of week {asOfWeek}" prints the raw `leg`, but eligibility uses `max(leg, lastScoredWeek)` (PLAUSIBLE)

- **Where:**
  - `RivalryService.java:688` sets `asOf` to the raw leg.
  - `:710` computes `boundary = max(leg, lastScored)`.
  - `Rivalries.tsx:343` prints it.
- **What fails:** The build notes deliberately use the max so that a stale `leg` can't offer a
  played week, and `aStaleLegBelowTheLastScoredWeekDoesNotOfferAPlayedWeek` covers that case.
  In the same case, though, the panel prints "as of week 1" while it offers weeks 4+ because
  week 3 is scored. The printed week doesn't match the cutoff the reader is shown.
- **Fix:**
  - Either send the boundary actually used (e.g. `asOfWeek = boundary`, or a second field
    `eligibleAfterWeek`) and print that,
  - or print "after week {boundary}".

  Keep `api.ts` in step either way.

## Notes (no change required)

- **N1, lead 1: EQUAL_UNDER_CAP with `heldBack == 0` (REFUTED in practice).**
  - **Argument:** With every best edge allowed, the constrained DP walks the same `choice`
    path as the unconstrained one. Along that path the candidate values are identical, and
    every other candidate's value can only drop, so the first-found-within-EPS winner can't
    change. The only exception is float noise of about 1e-15 sitting right at the EPS
    boundary.
  - **Measured:** `Fuzz037` over 20,000 random leagues found `heldZeroNotSame=0` and
    `heldButSame=0`. So "0 of the best pairings would be a third meeting" and the `held == 0`
    BEST_UNDER_CAP branch (`:817-818`) are effectively unreachable.
  - **Suggestion:** a cheap guard would be to make the `held == 0` case use the ALL_BEST_FIT
    wording. It is not required.
- **N2, lead 3: picker invariants hold.** `Fuzz037` checks every feasible candidate:
  - **Cap violations:** for every chosen pair, `reg - inW + 1 <= cap`; 0 violations.
  - **Displaced and alreadyIn:** they are disjoint and together equal the scheduled pairs that
    week; 0 failures.
  - **Recommended:** `recommended == candidates[0]`; 0 failures.

  More detail:
  - `RANK` and `tieKey` both key on `round3(total)` plus `keptBest`, so they agree.
  - `keptBest == best.size()` can only mean the same set, because every matching of the same
    managers has `floor(n/2)` pairs. That makes `tiedWith` for an ALL_BEST_FIT week exactly
    the number of weeks where the whole best set fits.
  - `binarySearch` for held-back ids can't miss, because best pairs come from the same
    `managerIds`.
  - Infeasible candidates carry `heldBack` and `displaced`, but only their week reaches the
    wire (`unfitWeeks`).
- **N3, lead 4: the matching overload is consistent.**
  - `RivalryWeekPicker` builds `allowed[i][j]` over `managerIds` sorted ascending.
    `RivalryMatching.solve` sorts the same list the same way, so the indices line up.
  - `f[0] == -inf` is the correct infeasibility test. In the odd-league path, an infeasible
    sit-out candidate (`-inf > -inf + EPS`) is never taken.
  - `unpairedBestScore` respects `allowed`, which matches the "best available pairing"
    wording.
  - The untouched `RivalryMatchingTest` still passes.
- **N4, lead 5: the F4 published check.** `standings` is `rosterSeasons.forLeague(league.id())`
  for the requested season, orphans included, and `perWeek = floor(rosters/2)`. With an odd
  roster count, the bye roster has no partner row in `scheduledPairs` (the self-join needs a
  second roster with the same `matchup_id`), so `floor` is right. Playoff weeks in `scheduled`
  are ignored by the `1..regularEnd` loop and by the `week > regularEnd` filter. Median games
  aren't rows in `scheduledPairs`. That last point was confirmed by reading the code (R3 says
  the same); it was not measured here.
- **N5, lead 6: eligibility.** `boundary = max(leg, lastScored)`, `complete()` empties the
  list, and the range is `boundary+1..playoffWeekStart-1`. Displaced `met` and `still` use the
  same boundary. A displaced pair whose only other game is in week `leg` (in progress) reads
  "already met in week leg". That's acceptable, and the build notes record it.
- **N6, lead 7: frontend.**
  - `rec.week != null` is a loose guard, and it is tested with `week` deleted.
  - `WEEK_STATUS_TEXT` covers all six non-OK statuses.
  - The thin headline counts the shown pairings.
  - `api.ts` matches every Java record field by name, nullability and nesting:
    `WeekPairing.pairing`, `Displaced.metInWeeks`/`stillMeetsInWeeks`, `asOfWeek`, and no
    `note`.
  - List keys are unique: pairs by `a:b`, others by week.
  - `nameOf` falls back to "Manager {id}".
  - Cosmetic: in "Other weeks", `.rv-pair` cards (`--panel`) sit on an `.rv-details` that is
    also `--panel`, so the nested cards rely on the inset line alone for contrast.
- **N7, lead 8: spec 034 regressions.**
  - `rec.pairings`, `unpaired` and `thinCount` come from the unchanged path. The refactor into
    `pairingsOf`/`unpairedOf` is behaviour-preserving, and `RivalryServiceWeekTest` asserts
    equality with the cap absent.
  - `seasonGrid` gets the same `leg` value it used to read itself.
  - `RivalryControllerTest`, `RivalryServiceTest` and `RivalryParityIT` all pass.
- **N8: wording edge cases.**
  - In a 2–3 manager league, ALL_BEST_FIT prints "All 1 of the best pairings".
  - Ranking uses `round3` while the reason code uses raw EPS. Two totals that differ by less
    than 0.0005 can tie in rank but read "BEST_UNDER_CAP … scores 8.333 of a possible 8.333".
    That needs denominators beyond about 40 in the closeness fractions, so it is rare.

## Fix pass (2026-10-15)

All findings below are fixed. Pinning tests are in `RivalryServiceWeekTest.java` and `Rivalries.test.tsx`.

- **B1 (fixed).** `RivalryService.weekRecommendation` records the first week of each tie group
  (candidates arrive in rank order, so the first seen per `RivalryWeekPicker.tieKey` is the
  earliest) and passes it to `weekReason`. Only that week says "It's the earliest of N ...". Later
  weeks say "Ties with week F on every count; week F comes first." and carry `firstTiedWeek` in
  `numbers`. `tieKey` is now package-private.
  - Test: `onlyTheFirstWeekOfAnAllBestFitTieGroupSaysEarliest` (cap 3, weeks 3/4/5 all
    ALL_BEST_FIT: week 3 says "earliest", 4 and 5 never do).
- **S2 (fixed).** `EQUAL_UNDER_CAP` and `BEST_UNDER_CAP` get "It's the earliest of N equally strong
  weeks." on the first week of the group only, and the same runner-up sentence on the others.
  - Test: `equalAndBestUnderCapTiesAlsoNameTheEarliestOnlyOnTheFirstWeek` (`assertTieWording`;
    checked in a throwaway run that weeks 3 and 5 really form a BEST_UNDER_CAP group).
- **S1 (fixed).** No "third" is hard-coded.
  - The text now reads "without any pair meeting more than {cap} times" (or "more than once" for
    cap 1) and "would go over the {cap}-meeting limit in week W". `cap` is in `reason.numbers`.
  - `HeldBack` gets `wouldMeet` (= regular-season count if paired that week, `reg - inW + 1`), and
    `meetsInWeeks` now excludes the candidate week. Both are in `weekOption`. `api.ts`
    `RivalryHeldBack` mirrors the new field.
  - `Rivalries.tsx` prints `NTH[h.wouldMeet]`. The NO_WEEK_FITS sentence comes from
    `noWeekFitsText(week.cap)`, with generic wording when `cap` is null.
  - Tests: `theCapWordsTheReasonAndHeldBackCarriesTheRealMeetingCount` (cap 3: wouldMeet 4,
    "3-meeting limit", "more than 3 times", no "third"),
    `anOverCapPairPrintsItsRealCountAndNeverCountsThisWeekInMeetsInWeeks` (wouldMeet 4 and 3,
    own week excluded). Frontend: "prints the held-back pair own would-be count" (fourth) and
    "NO_WEEK_FITS names the real cap, and stays generic without one".
- **S3 (fixed).** `asOfWeek` is now `max(leg, lastScoredWeek)`, the same boundary eligibility
  uses. It is null only when there is no leg and nothing is scored. Javadoc on `WeekRecommendation`
  and the `api.ts` comment say "the latest week counted as played or under way".
  - Tests: `asOfWeekIsTheEligibilityBoundaryNotTheRawLeg` (leg 1, scored 3 gives 3, weeks 4 and 5),
    and `aNullLegFallsBackToTheLastScoredWeekAlone` now expects 1, and null when nothing is scored.
- **N8 (fixed).**
  - (a) One best pairing reads "The best pairing fits week W ...". Test:
    `aSingleBestPairingIsWordedInTheSingular`.
  - (b) EQUAL vs BEST is decided on the `round3` totals, the same as the ranking, so "scores 8.333
    of a possible 8.333" cannot print. A `held == 0` EQUAL week (unreachable per N1) now reads
    "An equally strong set fits week W."

**Not changed:** spec 034's pairings and score; N1 to N7 (no change required). The spec text in
plan.md/contracts still quotes the old "third meeting" wording; code and tests are now the source of
truth for the sentences.

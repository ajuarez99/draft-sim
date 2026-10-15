# Adversarial plan review: spec 037

This review read spec.md, plan.md, research.md, data-model.md, contracts/api.md, quickstart.md
and tasks.md cold on 2026-10-15. It checked them against the code in worktree
`037-rivalry-week-pick` (code identical to `main` 70159ea), the shared Postgres on 5433, and live
rivalries payloads. No code for 037 had been written.

Every item is labelled **measured** (run today, output quoted), **read** (from code, not
executed) or **inferred**.

**How the payloads were measured.** `GET /api/leagues/{id}/rivalries` with `X-Sleeper-User`
(popsharky), against the server already listening on **8081**. That's another session's server,
running spec 034's code (8080 is an older main without the route, so it returned 404). The calls
were read-only GETs, taking 70–130 ms. A Python re-implementation of the planned picker (R3's
allowed rule, R5's eligibility, the same bitmask DP with `EPS = 1e-9` and the same first-found
tie rule) then ran on each payload's own `seasonGrid.cells` and `matrix.pairs`. It reproduces the
served history-best set exactly on all three leagues that have history (`served == sim`). Its
playoff bonus counts PLAYOFF weeks, not bracket matches. That's the same thing for round type 0,
and it matched the served set anyway. Scripts are in the session scratchpad, not the repo.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| R1: the full regular season is stored | `league_matchup` max week (measured) | ✅ 3/4/9465: 14, 210: 19. Each equals `playoff_week_start − 1` |
| R3: median games aren't rows | 210 has `league_average_match = 1` (measured) | ✅ Every `(league, week, matchup_id)` group in 3/4/210/9465 has exactly 2 rosters: 98/84/114/98 groups. 210 is 6 × 19 = 114. **Now measured, not just a code reading.** |
| No byes, no null managers, no manager with two rosters today | `league_matchup` null `matchup_id` rows, `roster_season` (measured) | ✅ 0 null rows. Rosters equal managers equal distinct managers: 14/12/12/14. The schema still allows both (see F5) |
| `leg` is the current week, not the last completed one | `settings_json` (measured): league 4 `leg = 6`, `last_scored_leg = 5`, on Thu 2026-10-15, the day NFL week 6 kicks off | ✅ R5's reading is right. `leg` is the week being played or about to be |
| Regular season = `week < playoff_week_start` in both sports | `RivalryGameKind.isPlayoffWeek:51-53` (read); `PlayoffFormat` coalesces a missing start to 0 (`LeagueRepository.playoffFormat`, read) | ✅ The same predicate the grid uses, so `NO_SEASON_FORMAT` for `< 2` is consistent |
| `scheduledPairs` has no score join, so future weeks are included | `LeagueMatchupRepository.scheduledPairs:160-176` (read) | ✅ `a.roster_id < b.roster_id`, both `matchup_id`s non-null, managers via a LEFT join, so they can be null (F5) |
| `lastScoredWeek` only counts scored weeks | `pairedWithScores` INNER-joins `roster_week_points` (read). `RivalryService:222-223` takes the max over this league's games | ✅ Local league 4 has scores through week 4 and `leg = 6`, so `max(leg, lastScored) = 6` (measured) |
| DP overload approach (T004) | `RivalryMatching.match:91-137` (read) | ✅ Correct, with one point now made explicit. Reconstruction from an infeasible state reads `choice = -2`, which gives `ids[-2]` and an AIOOBE. That's why T004's "return empty when `f[0]` is −inf, never walk `choice`" is load-bearing, not tidiness. The sit-out branch needs no −inf guard (`−inf > −inf + EPS` is false). If every unconstrained pair is allowed, the constrained DP returns the same set (the sim confirms it: West Coast week 14 has 0 changes) |
| Cost (R6) | arithmetic plus the sim | ✅ Negligible at n = 12–14. Today's endpoint takes 70–130 ms (measured on 8081). See F12 for the one hot-loop detail |

## Measured outcome of the planned algorithm, per league

This is the number research R2 left open and R7 worried about. The result is per eligible week:
the best total under the cap, and how many of the history-best pairs changed.

| League | n | Best total | History-best pairs that already meet twice | Eligible weeks | Per-week totals | Planned pick (earliest of max) |
|---|---|---|---|---|---|---|
| "(Foot) Ball Knowers" 2026 (4) | 12 | 17.25 | **5 of 6**, met in weeks {1,12} {2,13} {3,14} {3,14} {1,12} | 7–14 | wk 7–11: 13.333; 12: 15.0; **13: 16.917**; 14: 15.167 | **Week 13**, no tie, 4 pairs changed |
| NBA "Ball Knowers" 2026 (210) | 12 | 30.286 | **5 of 6**, met in weeks {8,19} ×4 and {5,16} | 2–19 | most 22.4–24.0; **8 and 19: 27.667** | **Week 8**, tied with 19, 5 changed |
| West Coast 2026 (9465) | 14 | 16.9 | 3 of 7, all in weeks {1,14} | 5–14 | **every week 16.9** | **Week 5**, a 10-way tie, 4 changed (3 held back, 1 knock-on). Week 14 changes 0 |
| fantasy😍 2026 (3) | 14 | — | — | — | — | `recommendation.status = NO_HISTORY`, so `week` is null |

Why football is hit hard: the history score counts **this season's** played meetings. Sleeper's
schedules wrap around, so the 12-team, 14-week league replays weeks 1–3 in weeks 12–14, and the
14-team league replays week 1 in week 14. The pairs that have already met this season score
higher, and they're exactly the pairs scheduled to meet again. This is inferred from the
measured week sets above, and it's consistent with all three leagues.

## Findings

These are ranked by severity.

**F1 (blocker). `ALL_BEST_FIT` is keyed on total equality, so it prints a false sentence when an
equal-scoring but different set wins.**
T011 sets `ALL_BEST_FIT` when `|total − bestTotal| ≤ EPS`, with the text "All {k} of the best
pairings fit week {W} without anyone meeting a third time". On West Coast, week 5 totals 16.9,
equal to the best. But it holds back 3 history-best pairs, (11,12), (3,9) and (14,4333), and
rearranges a 4th (measured, table above). The page would say all of them fit while showing a
different set and three held-back sentences. The same thing happens wherever zero- or low-score
"filler" pairs can be swapped at no cost, which is common: 43 of West Coast's 91 pairs have 0
meetings (measured).
- **Amendment:**
  - Decide the code by **set equality**: `ALL_BEST_FIT` iff `heldBack` is empty **and**
    `matching.pairs == unconstrained.pairs`.
  - Add a third code, `EQUAL_UNDER_CAP`, for an equal total with a different set. Text: "{h} of
    the best pairings would be a third meeting in week {W}; an equally strong set replaces them."
  - Make `BEST_UNDER_CAP` the case where the total is lower.
  - Pin it with a T007/T009 test: an equal-total, different-set fixture must not get
    `ALL_BEST_FIT`.

**F2 (should-fix; needs Allan's call). R7's verdict: the tie risk is real, but not in the shape
the plan predicted. The fix is a tie-break order, not a weight.**
- **Measured:**
  - Football did **not** default to "next week". "(Foot) Ball Knowers" picks week 13 outright.
  - NBA ties weeks 8 and 19, and earliest gives 8, which is reasonable.
  - West Coast ties **all 10** eligible weeks, and earliest picks week 5. That's the week that
    disturbs the history-best set the most, while week 14 keeps it intact (0 changes).
- So with FR-005 as written, the tool recommends the more disruptive of two equal options. On a
  league where totals are mostly flat, the reason text (even after F1) gets dominated by
  held-back sentences that the tie-break could have avoided.
- **Amendment** (lexicographic; it adds no constant, so the "no new weight" principle holds):
  1. total, descending
  2. history-best pairs kept (`|matching ∩ unconstrained|`), descending
  3. week, ascending
- Measured effect: West Coast → week 14, "(Foot) Ball Knowers" → 13 (unchanged), NBA → 8
  (unchanged; weeks 8 and 19 both change 5).
- This amends **FR-005**, so put it to Allan before T007, with this table. If he declines, keep
  FR-005, and have F1's `EQUAL_UNDER_CAP` text name the tie ("the earliest of 10 equally strong
  weeks").
- Either way, `tiedWeeks` must count ties under the **full** key, and T007 case 4 needs a
  sibling case for key 2.

**F3 (should-fix). R2's labelled guess is wrong, and the plan's "football is lightly affected"
framing is too.**
R2 guessed "football loses none in most weeks". Measured: "(Foot) Ball Knowers" loses 4–5
history-best pairs in **every** eligible week, and West Coast loses 3 in 9 of 10. The
pairs-meeting-twice counts in R2's table are right, but the conclusion drawn from them isn't:
the history-best set is concentrated in the twice column, for the wraparound reason above.
- **Amendment:**
  - Add a visible "corrected after plan review" note under R2 with the per-league row above
    (the AGENTS.md convention).
  - Rewrite plan.md R7's premise.
  - T024 still re-measures this on the built code. The values above are a re-implementation,
    not the Java path.
- Consequence for the UI: held-back sentences will be the **norm** on the real leagues, not an
  edge case. US2's panel is the main view, not a footnote. Size the design for 4–5 held-back
  lines.

**F4 (should-fix). A partly published schedule silently breaks the cap.**
- R1 measured that all four leagues are fully stored today. But `scheduledWeeks`' javadoc
  (`LeagueMatchupRepository:43-48`, read) records that Sleeper answers unscheduled weeks with
  every `matchup_id` null, and ingest stores what it gets.
- If any regular-season week is missing or short, `reg` undercounts. A pair then looks allowed
  that will in fact meet a third time, and a week with no stored pairs gets an empty `inW`.
- The plan has `NOT_PUBLISHED` only for `scheduled.isEmpty()` (T018).
- **Amendment:** `NOT_PUBLISHED` unless **every** week `1..playoffWeekStart−1` has exactly
  `floor(rosters/2)` stored pairs. Add a T018 case with week 9 missing.

**F5 (should-fix). Null-manager rosters and two-roster managers: the model doesn't hold, and
data-model contradicts itself.**
- data-model says pairs with a null manager are **dropped** from `schedule`. It also says
  `displaced` counts "pairs with a manager outside `managerIds`". The picker can't report what
  it never received.
- Worse, the rivalry set is per **manager**, while Sleeper's week is per **roster**:
  - An orphan roster gets no rivalry game.
  - A manager with two rosters gets one game for two rosters.
  - Their self-pair (dropped) still occupies a real week-W slot.
- Spec 034 shows a `ROSTER_NOTE` for this case (`RivalryService:71-74, 175-180`). The week pick
  can't express it at all.
- Measured: none of the four leagues has either case. The schema allows both (034 review F15).
- **Amendment:**
  - New week status `UNSUPPORTED_ROSTERS`. Use it when any roster this season has a null
    manager, or any manager has two rosters (`twoRosters`, already computed at line 175).
  - Sentence: "A week can't be recommended because not every team has exactly one manager this
    season."
  - Remove the contradictory `displaced` clause from data-model.
  - Add a T018 case, plus the `api.ts` union.

**F6 (should-fix). A pair already above the cap: the spec, R3's formula, SC-001, data-model and
T007 case 9 disagree.**
- With `reg = 3` and W one of their weeks, R3 gives `3 − 1 + 1 = 3 > 2`, so they're
  **disallowed in every week, including their own**.
- The spec's edge case says pairing them "in any week other than one of those meetings makes it
  worse, so it's not allowed". That implies their own weeks *are* allowed.
- T007 case 9 says "never chosen outside its own weeks", which is ambiguous.
- SC-001 says "0 pairs above 2 for any week the page offers". data-model's invariant exempts an
  over-cap pair that isn't chosen. Under SC-001 as written, a week that isn't one of the pair's
  meetings can never pass.
- **Amendment:**
  - Keep R3's formula (simplest, and never raises a count above the cap).
  - Fix the spec edge case to "not allowed in any week".
  - Restate SC-001 as "no pair's count rises, and no pair ends above the cap unless it was
    already above it in the published schedule".
  - T007 case 9 asserts "never chosen in any eligible week, including its own".
  - `overCapNote` says so. No live data has this (R2), so it stays unit-test-only.

**F7 (should-fix). R8 makes a missing cap blank spec 034's pairings too.**
- `RivalryProperties.loaded()` gates the whole recommendation (`RivalryService:581`). R8 and T005
  add the cap to `loaded()`.
- So a weights.yml without the new key turns spec 034's working pairings into `CONFIG_MISSING`.
  That covers a mounted weights volume, which the Dockerfile explicitly invites ("can be replaced
  by a mounted volume or a config map", `Dockerfile:16-19`, read). That's a regression in a
  shipped feature, caused by an additive one, and it contradicts FR-010's "still show spec 034's
  pairings".
- **Amendment:**
  - Leave `loaded()` as it is. Add `weekLoaded()`: `loaded() && maxRegularSeasonMeetings != null`.
  - A missing cap gives `week.status = "CONFIG_MISSING"`. Spec 034's pairings render under it.
  - `WeekRecommendation.cap` becomes `Integer`.
  - Fix quickstart §3's expectation: `recommendation.status` stays OK, and `week.status` is
    CONFIG_MISSING.

**F8 (should-fix). Eligibility trusts `leg`, which can be stale. Quickstart's eligibility check
is circular.**
- Measured on 5433: leagues 3 and 9465 have `leg = 4`, `last_scored_leg = 3`, while league 4,
  same sport and same day, has `leg = 6`.
- The 3/9465 rows are from about two weeks ago (inferred from the NFL calendar). A payload built
  from them offers weeks 5 and 6, which in reality are played or under way.
- Quickstart runs on a scratch copy with on-visit refresh **off**, then checks "no offered week
  ≤ `seasonGrid.currentWeek`". That's the same stale `leg` the engine used, so it can't catch
  this. SC-004 ("never a played week") is untestable as written.
- **Amendment:**
  - (a) Quickstart §2.2 compares offered weeks against Sleeper's live state
    (`/v1/state/{sport}` `week`, or the league's live `settings.leg`), not the payload.
    Alternatively, re-ingest the scratch copy first and record when.
  - (b) The panel prints "as of week {currentWeek}" next to the recommendation, so a stale
    payload is visible rather than confident.
  - (c) `league.complete()` (`LeagueRow`, used the same way by `NextMatchupService:61`) forces
    `NO_ELIGIBLE_WEEK` regardless of `leg`, so a finished season with unscored gaps can't offer
    a week.

**F9 (should-fix). UI holes the tasks don't cover.**
- **The thin headline describes the wrong set.** `RivalryWeekPanel` prints "Most of these
  pairings ({thinCount} of {n}) rest on thin history" from `rec.thinCount`
  (`Rivalries.tsx` ~285, read). Once the week's pairings replace `rec.pairings` (T013), that
  count is for a set no longer shown.
  - Compute it from the shown `pairing.thin` flags, or add `thinCount` to `WeekOption`.
- **Split deploys.** The web and backend services deploy independently and have drifted before
  (memory: deployed to Railway). A new web bundle against an old backend sees `rec.week`
  **undefined**, not null.
  - Guard with `rec.week == null` (loose) or `?.`, and add a T012 test with `week` absent.
- **Existing fixtures.** The `recommendation` fixture in `Rivalries.test.tsx:82` has no `week`.
  Once `api.ts` makes it a required field, `tsc -b` fails on the test files.
  - Add the fixture update to T010's "same change".
- **The note renders twice.** `week.note` duplicates `rec.note`, and C3 renders `rec.note`.
  - Drop `week.note` from the record, or render only one.

**F10 (note). T011's "pass the already-loaded `currentLeg`": it isn't loaded where
`recommendation()` is called.**
`leagues.currentLeg` is called inside `seasonGrid()` (`RivalryService:441`). `forLeague()` never
holds it. Hoist one `currentLeg` read into `forLeague()` and pass it to both `seasonGrid` and
`recommendation`. Don't add a second query. Likewise, `fmt` is
`formatByLeague.get(league.id())`, and `scheduled` and `lastScoredWeek` are locals of
`forLeague()`. The `recommendation(...)` signature grows by four parameters. Say so in T011.

**F11 (note). The ranking sort and the EPS tie rule can disagree.**
T008 picks the best with an EPS rule, then "ranks by total descending, then week ascending" with
a plain double comparison. Two totals differing by 1e-13 (different pair sums) would sort by
total, not week, and `tiedWith` would count them as tied. That's inconsistent. Sort on a key
rounded to 1e-6 (or `round3`, which is what's displayed anyway), and compute ties on the same
key.

**F12 (note). Performance details.**
- Precompute `allowed` into a `boolean[n][n]` over the sorted ids before the DP. T004 as worded
  calls the predicate in the inner loop: up to 2^16 · 16 · 18 ≈ 19M calls, each doing a
  `PairKey` lookup plus boxing.
- Pass the already-computed unconstrained `Matching` into the picker rather than solving it
  twice (`RivalryService:593`, plus T008's own call).
- Neither changes correctness. Both keep R6's "negligible" true at `max-managers: 16`.
- Payload size: up to 17 runner-ups × 6–8 pairings × a reason each is tens of KB. That's fine,
  but quickstart §2 should record it alongside latency.

**F13 (note). `stillMeetsInWeeks` mixes past and future weeks.**
For a week-13 rivalry, a displaced pair that met in week 1 would read "still meet in week 1".
- Either split the field into `metInWeeks` (≤ last scored or ≤ `leg`) and `stillMeetsInWeeks`,
  or have the UI word past weeks as "already met in week 1".
- The same applies to `HeldBack.meetsInWeeks`. "Already meet in weeks 3 and 11" reads fine
  either way, so only `Displaced` needs it.

**F14 (note). Quickstart §2 includes league 3, which has no history.**
fantasy😍 2026's `recommendation.status` is `NO_HISTORY` (measured), so `week` is null, and
§2's items 1–5 are vacuous there. Keep it as a `week == null` check, and run the invariants on
4, 210 and 9465. Also update SC-001/SC-002's league list, which already names exactly those
three, consistently.

**F15 (note). R5's verdict: accept, but amend the spec's wording visibly.**
- Measured: `leg` is the current or about-to-start week (league 4 `leg = 6` on week 6's kickoff
  day).
- Excluding it costs NBA week 1 before tip-off (`leg = 1`, measured) and, in football, the week
  that starts within days. Neither is a realistic rivalry week to set up by hand.
- But FR-002 and the "current week" edge case say only weeks "not yet started" are eligible.
  Week 1 before tip-off *hasn't* started, so R5 contradicts the spec's own words.
- **Amendment:** FR-002 becomes "a regular-season week after the week Sleeper reports as current
  (`leg`), and after the last scored week". Add an "amended after plan review" line.

**F16 (note). Test gaps.**
- No test asserts that `rec.pairings` (spec 034's set) is **unchanged** on the OK path. T018
  checks it only for non-OK. Add it to T009, since that's the proof 037 didn't move 034's
  answer.
- T007 has no fixture where the DP must **skip a disallowed edge and still find a feasible full
  set by rearranging** (a knock-on swap). Add one, because that's the West Coast shape.
- T003(d) "on every existing fixture" should name the fixtures. It has to be a loop over the
  existing test inputs, not a new hand-picked one.

## Verdicts on the plan's named risks

- **R7 (earliest-week tie):** the predicted failure ("next week, almost every time") **did not
  occur** on any real league. A different one did: a 10-way tie on West Coast where "earliest"
  picks the most disruptive equal week. Use F2's tie-break order (needs Allan) plus F1's honest
  reason code. Shipping runner-ups alone isn't enough, because the headline is still the worse
  of two equal answers.
- **R5 (`leg` excluded):** **acceptable** (F15), with the spec wording amended. The bigger `leg`
  risk is staleness (F8), not exclusion.
- **R3's median assumption:** **confirmed by measurement** (table above).
- **Headline swap:** acceptable, but F3 means it will happen on every real league with a week,
  and F9's thin-count headline must follow the swap.
- **Concurrent sessions:** T001 covers it. 8081 is serving another session's build. Quickstart's
  "check 8081/5174 aren't another session's" matters today (measured: both are listening).

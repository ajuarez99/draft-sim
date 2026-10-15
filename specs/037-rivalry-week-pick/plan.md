# Implementation Plan: Rivalry week recommends which week, under a two-meeting cap

**Branch**: `037-rivalry-week-pick` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/037-rivalry-week-pick/spec.md`

## Summary

Spec 034's rivalry week names pairings but no week, and it never reads this season's schedule.
This change adds a recommended week, picked jointly with its pairings, under Allan's rule:

> No pair of managers meets more than twice in the regular season once rivalry week is in.

For each regular-season week that hasn't started, the existing exact matching runs with pairs
that would hit a third meeting removed. The week whose best set scores highest under the
**unchanged** spec 034 score wins, and ties go to the earliest week. The page shows:
- the week and why
- pairs the cap held back, and when they already meet
- the original matchups it replaces
- the other weeks, each with its own pairings

Everything comes from data the page already loads. The full regular season is stored
(measured, research R1). There's no migration and no ingest.

## Amended after review (plan-review.md, 2026-10-15)

All 16 findings are accepted. **Where these lines conflict with research.md, data-model.md,
contracts/api.md, quickstart.md or tasks.md, they win.** The build reads this section first.

- **F1.** The week reason code is decided by set comparison, not by comparing totals:
  - **`ALL_BEST_FIT`** when `heldBack` is empty and the week's pairs equal the unconstrained
    pairs.
  - **`EQUAL_UNDER_CAP`** when the total is equal (within EPS) but the set differs. Text: "{h}
    of the best pairings would be a third meeting in week {W}; an equally strong set replaces
    them."
  - **`BEST_UNDER_CAP`** when the total is lower.

  A test pins that an equal-total, different-set week never gets `ALL_BEST_FIT`.
- **F2 (Allan chose this, 2026-10-15).** The rank key is (1) total, descending; (2)
  history-best pairs kept, meaning `|matching.pairs ∩ unconstrained.pairs|`, descending; (3)
  week, ascending. `tiedWeeks` counts weeks equal on keys 1 **and** 2. T007 gets a case for key
  2. Spec FR-005 is amended.
- **F3.** R2's football guess was wrong (correction note in research.md). Held-back sentences
  are the normal case, so design the panel for 4–5 of them.
- **F4.** `NOT_PUBLISHED` unless **every** week `1..playoffWeekStart−1` has exactly
  `floor(rosters/2)` stored pairs. T018 gets a "week 9 missing" case.
- **F5.** There's a new week status, **`UNSUPPORTED_ROSTERS`**, for when any roster this season
  has a null manager, or any manager has two rosters (`twoRosters`, already computed in
  `forLeague`). Sentence: "A week can't be recommended because not every team has exactly one
  manager this season." Drop data-model's clause that counts out-of-list managers in
  `displaced`.
- **F6.** A pair already above the cap is disallowed in **every** week, including its own
  (R3's formula as is). T007 case 9 asserts exactly that. Spec edge case and SC-001 are amended.
- **F7.** `loaded()` is **unchanged**. Add `weekLoaded() = loaded() && maxRegularSeasonMeetings
  != null`. A missing cap gives `week.status = "CONFIG_MISSING"`, and spec 034's pairings still
  render. `WeekRecommendation.cap` is `Integer` (nullable). Quickstart §3 expects
  `recommendation.status == OK` and `week.status == CONFIG_MISSING`.
- **F8.**
  - (a) Quickstart §2.2 checks offered weeks against Sleeper's **live** state. Fetch
    `https://api.sleeper.app/v1/state/{nfl|nba}` `week`, or re-ingest the scratch DB first and
    record when.
  - (b) Add `asOfWeek: Integer` (the `leg` used, nullable) to `WeekRecommendation`. The panel
    prints "as of week {asOfWeek}".
  - (c) If `league.complete()` is true, the status is `NO_ELIGIBLE_WEEK` regardless of `leg`.
- **F9.**
  - The thin-history headline counts the **shown** pairings' `thin` flags, not `rec.thinCount`,
    when a week is shown.
  - The UI guards `rec.week == null` with loose equality, so an old backend (field undefined)
    falls back to spec 034's rendering. Test it with `week` absent.
  - The existing `Rivalries.test.tsx` fixture gets `week: null` in the same change as `api.ts`.
  - **Drop `note` from `WeekRecommendation`.** `rec.note` is rendered once.
- **F10.** Hoist a single `leagues.currentLeg(league.id())` read into `forLeague()`, and pass it
  to both `seasonGrid(...)` and `recommendation(...)`. `recommendation` also takes `scheduled`,
  `lastScoredWeek`, `fmt`, the league row (for `complete()`), the roster checks (F5) and the
  roster count (F4).
- **F11.** Rank and count ties on `round3(total)`, the displayed value. That makes the sort and
  the tie rule agree.
- **F12.**
  - Precompute `allowed` as `boolean[n][n]` over the sorted ids. The DP overload takes the
    matrix (or a predicate the picker builds into a matrix once) and does no map lookups in the
    inner loop.
  - The picker receives the already-computed unconstrained `Matching` rather than solving it
    again.
  - Quickstart records payload size.
- **F13.** Split `Displaced` into `metInWeeks` (weeks `≤ asOfWeek`, or `≤ lastScoredWeek` when
  `leg` is null) and `stillMeetsInWeeks` (later weeks, excluding W). The UI words them "already
  met in week X" and "still meet in week X". "They wouldn't meet otherwise this season" means
  both lists are empty.
- **F14.** Quickstart §2 runs on leagues 4, 210 and 9465. League 3 is only checked for
  `week == null` (it's `NO_HISTORY`).
- **F15.** R5 is accepted. Spec FR-002 is amended visibly.
- **F16.**
  - T009 asserts `rec.pairings` is identical with and without the week pick (034's answer is
    unmoved).
  - T007 gets a knock-on fixture: a disallowed edge where the DP must rearrange other pairs to
    stay feasible.
  - T003(d) loops over every existing `RivalryMatchingTest` input.

**Final week status union:** `OK | CONFIG_MISSING | NOT_PUBLISHED | UNSUPPORTED_ROSTERS |
NO_SEASON_FORMAT | NO_ELIGIBLE_WEEK | NO_WEEK_FITS`. Checks run in this order (T019's order is
superseded):
1. CONFIG_MISSING
2. NO_SEASON_FORMAT. F4's completeness check needs `playoffWeekStart`, so this comes first.
3. NOT_PUBLISHED (empty, or incomplete per F4)
4. UNSUPPORTED_ROSTERS
5. NO_ELIGIBLE_WEEK (includes `complete()`)
6. NO_WEEK_FITS

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5) backend; TypeScript + React (Vite) frontend

**Primary Dependencies**: Existing only. Spring JDBC `JdbcClient`, React. Nothing new.

**Storage**: PostgreSQL, read-only for this feature. Reads `league_matchup`, `roster_season`
and `league.settings_json` through existing repository methods. No migration.

**Testing**: JUnit 5 (pure engine tests with no Spring, plus the existing service/controller
tests); Vitest + Testing Library for `Rivalries.test.tsx`; a payload-invariant script and a
browser pass (quickstart.md)

**Target Platform**: Railway (Linux container) in prod; Windows dev machine locally

**Project Type**: Web application (backend + web)

**Performance Goals**: The rivalries endpoint stays in spec 034's measured 25–250 ms range on
the real leagues. The added DP work is estimated as negligible (research R6), and that gets
measured, not assumed.

**Constraints**:
- exact matching, no approximation
- deterministic output
- the cap is a hard rule
- the score is unchanged and never retuned

**Scale/Scope**: 12–14-manager leagues today (DP cap 16); up to 18 eligible weeks (NBA)

## Constitution Check

*GATE: checked before Phase 0, re-checked after Phase 1.*

| Rule | Status |
|---|---|
| Migrations append-only | **Pass.** No migration. |
| `api.ts` mirrors Java records in the same change | **Pass, planned.** Five new types plus `week` on `RivalryRecommendation`, in the same task as the Java records (contracts C1). |
| Never retune a hand-set constant to match a guess | **Pass.** Spec 034's score and weights are untouched. The cap is Allan's rule, labelled as one. R2's prediction is labelled a guess, and quickstart §2 records the real number against it. |
| `Map.of` null trap | **Pass, planned.** The nullable fields (`week`, `recommended`, `unpaired`, `overCapNote`) are record fields. `reason.numbers` is built mutably, as spec 034 does. |
| Ask before committing | **Pass.** Nothing gets committed without Allan's go-ahead. The work is in its own worktree. |
| Planning doc ≠ verified spec | **Pass.** The schedule's completeness (R1) and the cap's bite (R2) were measured, not taken from spec 034's text. |

Re-check after design: no new violations. **Complexity Tracking: none.**

## Project Structure

### Documentation (this feature)

```text
specs/037-rivalry-week-pick/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R8 (R1, R2 measured)
├── data-model.md
├── quickstart.md
├── contracts/api.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code

```text
config/weights.yml                                   # + draftsim.rivalry.max-regular-season-meetings: 2
backend/src/main/java/com/ballknowers/draftsim/
├── config/RivalryProperties.java                    # + maxRegularSeasonMeetings (1-5), required by loaded()
├── engine/RivalryMatching.java                      # + match(..., allowed, ...) -> Optional<Matching>
├── engine/RivalryWeekPicker.java                    # new, pure: eligibility input, allowed rule, per-week DP, ranking
└── engine/RivalryService.java                       # build schedule/eligible weeks, call picker, map to wire records
backend/src/test/java/com/ballknowers/draftsim/
├── config/RivalryPropertiesTest.java                # + missing/out-of-range cap
├── engine/RivalryMatchingTest.java                  # + allowed-predicate cases; existing cases unchanged
├── engine/RivalryWeekPickerTest.java                # new: SC-003 constraint + ordering, odd n, infeasible, over-cap
└── engine/RivalryServiceTest.java                   # + eligibility (leg, lastScored, playoff start), statuses
web/src/
├── api.ts                                           # + RivalryWeek* types, status union
├── pages/Rivalries.tsx                              # RivalryWeekPanel per contracts C3
├── pages/Rivalries.test.tsx                         # + OK, NO_WEEK_FITS, NO_ELIGIBLE_WEEK renders
└── (rivalries css)                                  # tags + disclosures, phone width
```

**Structure Decision**: the existing two-project layout. `RivalryWeekPicker` is pure, like
`RivalryMatching`, so the SC-003 ordering and constraint tests need no DB fixture.
`RivalryService` only adapts: it builds `schedule` and `eligibleWeeks` from what it already
loads (`scheduled`, `currentLeg`, `lastScoredWeek`, `PlayoffFormat`) and maps names into
reasons.

## Build order

1. **`RivalryMatching` overload and its tests.** The existing tests must stay green
   untouched. That's the proof the overload didn't move spec 034.
2. **`RivalryWeekPicker` and `RivalryWeekPickerTest`, tests first.** Pin the off-by-one in
   R3: a pair whose second meeting is W itself is allowed.
3. **`RivalryProperties` and `weights.yml`**, in one change (R8).
4. **`RivalryService` wiring.** Eligibility per R5, the statuses, and reason text with
   numbers.
5. **Java records and `api.ts`**, in one change.
6. **`RivalryWeekPanel` and its tests.**
7. **Quickstart §1–§4**, written up in `verification.md`. Wrong guesses get corrected visibly.

## Risks and open questions for the adversarial review

- **R7, the earliest-week tie.** In football the history-best set probably fits most weeks,
  so "earliest" usually means next week, which may be too soon to set up by hand. It's
  mitigated by saying it's a tie and by shipping every runner-up's pairings. The review
  decides whether that's enough, or whether to ask Allan for a tie-break.
- **R5, `leg` excluded.** Before the NBA's tip-off, week 1 isn't offered. That's
  conservative. Is it acceptable?
- **R3's median assumption.** That median games never appear in `scheduledPairs` is a code
  reading, not a measurement.
- **The UI swaps the headline set.** When a week is recommended, the page shows that week's
  pairings rather than the history-best set. A reader comparing with yesterday's page will see
  different pairs. Held-back sentences explain the cap-driven ones. Knock-on swaps (a pair
  that's allowed but rearranged to make room) get only the total comparison, not a
  per-pair explanation.
- **Concurrent sessions.** `RivalryService.java` and `Rivalries.tsx` are spec 034's files on
  main. Check that no other open branch touches them before building (the 033/034 rename
  collision is lessons #43's precedent).

## Complexity Tracking

None.

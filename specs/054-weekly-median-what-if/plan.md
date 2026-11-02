# Implementation Plan: Weekly median what-if on League history

**Branch**: `054-weekly-median-what-if` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/054-weekly-median-what-if/spec.md`

## Summary

The League history page's median column only fills the one season the Luck page covers. This
adds a per-season `median` block to the history response, computed from each season's own final
regular-season games with the **existing** median and all-play functions, plus a what-if for
seasons that didn't play median games: real + median combined record, wins gained, actual vs
what-if regular-season place, and playoff-line crossings. On the requested league that is
2024 and 2025; 2026 already has Sleeper's median setting on and is labelled as such (research R1,
measured).

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)
**Primary Dependencies**: existing only, no new libraries
**Storage**: PostgreSQL, read-only for this feature; **no migration**
**Testing**: JUnit 5 (unit + Testcontainers/real-Postgres ITs), Vitest + Testing Library
**Target Platform**: Railway (two services: API and web), deploy independently
**Project Type**: web application (`backend/` + `web/`)
**Performance Goals**: history response no more than 1 s slower (SC-006); expected far less (R7, a guess)
**Constraints**: one median rule (FR-002); the what-if never alters real figures (FR-009)
**Scale/Scope**: a chain of ~2-4 seasons × 10-14 rosters × ≤ 24 weeks per request

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | N/A: no migration. |
| `api.ts` mirrors Java records in the same change | Planned: `SeasonMedian`/`SeasonMedianTeam` types added in the same task as the backend records (contract file). |
| No retuning hand-set constants | N/A: no constants. The tie-break is a stated rule, not a tunable. |
| No `Map.of` in a nullable JSON path | Planned: the `median` block and team maps are built mutably; most what-if fields are null for `ALREADY_ON`. |
| Ask before committing | Followed: nothing committed without Allan's go-ahead. |
| Planning doc ≠ verified spec | Followed: the median mechanism was confirmed in code (R3) and the league's actual settings measured (R1) before designing. |

Post-design re-check: passes, no violations, Complexity Tracking empty.

## Design

### Backend

1. **`ExpectedWinsService`**: extract the game-loading loop of `compute()` (`:398-409`) into
   `List<Game> finalGames(LeagueRow league, WeekBound bound)` (package-visible); `compute()` calls
   it. Behaviour unchanged; `ExpectedWinsServiceTest` and `EngineOutputFrozenTest`-style checks
   must stay green.
2. **New `engine/MedianWhatIfService`** (`@Service`): `SeasonMedian forSeason(LeagueRow league)`.
   - games = `expectedWins.finalGames(league, expectedWins.regularSeasonBound(league))`
   - median/allPlay via `ExpectedWinsService.median/allPlay` (static, same package)
   - format = `leagues.playoffFormat(league.id())` → status, `regularSeasonWeeks`, playoff line
   - real half per research R4 (official from `rosterSeasons.forLeague` when every regular-season
     week is final, else counted with `ExpectedWinsService` head-to-head over the same games; add a
     static `headToHead(List<Game>) → Map<Integer, WinLossTie>` next to `actualWins`, since
     `actualWins` returns a half-win double and can't give W-L-T)
   - places via one comparator (R5); `playoffCross` only when `format.modelable()`
   - Pure computation split into a static `whatIf(...)` taking plain inputs, so the unit tests
     need no Spring and no DB.
3. **`LeagueHistoryController.history()`**: per season, `season.put("median", medianBlock(...))`,
   wrapped in try/catch → `UNAVAILABLE` + `log.warn` (FR-012). Mutable maps throughout.

### Frontend

4. **`api.ts`**: `SeasonMedian`, `SeasonMedianTeam`, `WinLossTie` reuse if one exists; `SeasonHistory.median?`.
5. **`LeagueHistory.tsx` `StandingsTable`**: read `median`/`allPlay` from `season.median` instead
   of the expected-wins response; drop the `getExpectedWins` fetch from this page if nothing else
   on it uses it (it doesn't: it only feeds these two columns, `:393-415`). Header span text comes
   from `weeksCounted`.
   - `WHAT_IF` seasons: add columns **With median** (W-L, ties when non-zero), **Gained** (`+N`),
     **Place** (`actual → what-if` with ▲/▼ movement, and a "made/missed playoffs" tag on a
     crossing). Visually grouped under a "If median had been on" header group, tinted apart from
     the real columns (FR-009; design memory: tinted groups, not flat cards).
   - `ALREADY_ON` seasons: one muted line above the table: "This season already plays a median game
     each week — it's in the W-L above." No what-if columns.
   - `UNAVAILABLE`/missing block: dashes, as today.
6. **How-this-works** copy rewritten per FR-011, including the tie-break and "this app's ordering,
   not Sleeper's seeding; playoffs not replayed".

### Tests

- `MedianWhatIfServiceTest` (unit, static `whatIf`): invariants 1–5 of data-model.md, including a
  **preference-ordering** case (lessons bug class 1); odd roster count (median team ties every
  week, winsGained counts wins only); ALREADY_ON nulls; COUNTED_GAMES vs SLEEPER_OFFICIAL selection
  at the "every regular-season week final" boundary; playoff crossing both directions; withheld line
  for divisions.
- `ExpectedWinsServiceTest`: existing, unchanged, green after the extraction.
- `LeagueHistoryMedianIT` (real Postgres): a seeded two-season chain, one with median on; asserts
  the wire shape, that `median` is present on every season, and parity with `/expected-wins` for the
  current season (SC-004).
- `LeagueHistory.test.tsx`: renders older-season median figures (the bug as reported), the what-if
  group for WHAT_IF, the already-on line, dashes for a missing block.

## Project Structure

### Documentation (this feature)

```text
specs/054-weekly-median-what-if/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/league-history-median.md
├── checklists/requirements.md
└── tasks.md            # next: /speckit-tasks
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/ExpectedWinsService.java        # extract finalGames(), add headToHead()
├── engine/MedianWhatIfService.java        # NEW
└── api/LeagueHistoryController.java       # per-season "median" block
backend/src/test/java/com/ballknowers/draftsim/
├── engine/MedianWhatIfServiceTest.java    # NEW
└── api/LeagueHistoryMedianIT.java         # NEW
web/src/
├── api.ts                                 # SeasonMedian types
├── pages/LeagueHistory.tsx                # columns, copy
├── pages/LeagueHistory.test.tsx
└── styles.css                             # what-if column group tint
```

**Structure Decision**: existing `backend/` + `web/` layout; one new engine service beside the one
it reuses.

## Complexity Tracking

None.

## Amended after review (2026-11-02)

[plan-review.md](plan-review.md) read this plan cold against the code: 0 blockers, 8 should-fix, 7
nits. Every finding is taken; where it changes an earlier section, that section is left as written
and this list is the override. Re-checked by the parent session: `ScoredWeeks.of`
(`ScoredWeeks.java:56-69`) treats every stored week as final for a `loaded_complete` season, so
"final" there does not mean "every roster has a score" (review #1 holds).

1. **(#1) Decide the real half from the games used, not from `ScoredWeeks`.** `OFFICIAL` is used
   only when every roster in the season has exactly `regularSeasonWeeks` counted games. Each team
   gets `gamesCounted`; invariant 1 becomes `median.w+l+t == gamesCounted` per team, and
   `weeksCounted` is the season's distinct weeks. Spec SC-002 restated per team.
2. **(#2) Official W-L must prove it is regular-season only.** Extra runtime condition for
   `OFFICIAL`: every roster's official `W+L+T == regularSeasonWeeks` (and `2 × weeks` is reported,
   not assumed, for an `ALREADY_ON` season; verification V4 records it). `SLEEPER_OFFICIAL` is
   renamed `OFFICIAL` because ESPN rows go through the same path.
3. **(#3) Drift is surfaced.** The block carries `driftGames`: rosters whose re-counted head-to-head
   differs from the official record. When it is above 0 under `OFFICIAL`, the page says "N
   managers' stored scores differ from Sleeper's record by a game; the median half uses the stored
   scores".
4. **(#4) The playoff marker is cross-checked.** For a `complete` season, the actual place's top
   `playoffTeams` must be the same set as the rosters with `final_placement ≤ playoffTeams` or who
   appear in the winners bracket; if it can't be confirmed or disagrees,
   `playoffLineWithheld = "ORDER_DISAGREES"`. For a season in progress, the marker reads "would be
   in / out" against this app's current order, and the copy says so.
5. **(#5) No week bound means no what-if.** `playoff_week_start < 2` → status `NO_REGULAR_SEASON_BOUND`;
   median/all-play still shown over all final weeks with that caveat.
6. **(#6) Nulls and mismatched roster sets.** A null official W-L/PF for any roster, or a roster in
   one set and not the other, forces `COUNTED_GAMES` (never an exception). Places are assigned over
   the counted-games roster set only.
7. **(#7) Stored-data measurement is owed, not done.** Neither local DB holds this league (5433 down,
   5438 is spec 053's empty scratch DB). The verification pass ingests it into a scratch DB and
   records, per season, the roster × week coverage of stored scores. Spec Assumption left as stated.
8. **(#8) Show both sides of the extra games.** The column is "Median games" as `+9 W / +5 L`
   (ties when non-zero), and the place movement says whether that helped. Spec FR-004 restated.

Nits:
- Tie-break settled as **win share (W + ½T) / games, then points for, then roster id**, in the spec's
  Assumptions too (it previously said wins, then fewer losses, then PF).
- Status enum, one list everywhere: `WHAT_IF`, `ALREADY_ON`, `NO_FINAL_WEEKS`,
  `NO_REGULAR_SEASON_BOUND`, `UNAVAILABLE`. `playoffLineWithheld` is a code: `DIVISIONS`,
  `SEED_TYPE`, `NO_PLAYOFF_TEAMS`, `ORDER_DISAGREES`; the page maps codes to sentences.
- `league_average_match > 0` = "one median game every week" is an assumption; V4's `2 × weeks`
  check is what tests it.
- `LeagueHistory.test.tsx:451-466` expected-wins tests are rewritten against `season.median`, not
  left to pass for the wrong reason.
- FR-011 also covers the `:432` tooltip and the `:661-662` "Luck page's calculation, one season at a
  time" sentences.
- `MedianWhatIfService.forSeason` takes the standings rows and `PlayoffFormat` that `history()`
  already loads, rather than re-querying them.
- Preference-ordering test made strict: equal real records, roster A higher PF, roster B more median
  wins → B's what-if place beats A's, movement signs are opposite, and a MADE/MISSED pair at the
  line.

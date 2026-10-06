# Implementation Plan: NFL scoring check and draft grades

**Branch**: `018-draft-grades` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/018-draft-grades/spec.md`. That's roadmap
Phase 2 items 2.1 + 2.2 (`claude/competitor-gap-roadmap.md`). Scope chosen by Allan on
2026-10-05. No hard deadline.

## Summary

Check NFL scoring with the real Java against real rows (US1). Then grade a finished draft
on read: per-pick production over the league's final weeks, value over the median of
neighbouring picks, the points that counted for the drafting roster, and a team value
graded with spec 013's existing curve (US2–US4). The baseline is positional
(amended after review, F1). One new endpoint and a toggle on the
existing completed-draft board. No migration, nothing stored.

Measuring before planning changed four things from the design doc:

- **The scoring check already passes in SQL**: 5,334 / 5,334 starter-weeks exact across
  five NFL leagues (R1). The Java check is still owed. It's a second implementation
  otherwise.
- **Basketball production can't be "sum the games".** These leagues credit one game per
  starter per week (540 / 540 roster-weeks). Production uses the week's average game,
  labelled as such, which tracks credited points at r ≈ 0.98–0.99 (R2).
- **No NBA draft and no 2025 NFL draft has draft-time ADP** (R10). The existing reach view
  stays hidden for them, so the production view is the only one those boards will have.
- **"Five weeks old" is three scored weeks** for NFL 2026, so every NFL 2026 grade is
  early today (R11).

Side finding, outside scope: spec 017's "a 4-game week is worth ~2×" contradicts R2. It gets
an amended note in `claude/nba-schedule-grid-and-streaming.md` in this branch, and a
question for Allan. The grid itself isn't changed here.

## Amended after review (2026-10-06)

[plan-review.md](plan-review.md) read this plan cold and found 11 problems plus 17 notes.
The local DB was down during the review, so it re-measured R1, R2, R4 and R6 from the
live Sleeper API instead, and they held. Each finding is disposed of below. data-model and
the contract were rewritten to match, with dated notes at the top. The other docs carry
inline amendments.

| # | Finding | Disposition |
|---|---|---|
| F1 | In football, value over slot vs. all positions makes late QBs the steals (NFL 2025: top 5 all QBs, QB mean +94.9) | **Fixed: positional neighbours** (Allan's choice, 2026-10-06). The baseline is the median of the `n` same-position picks drafted just before and after (n = 3, ARBITRARY), in both sports. New ordering test with positions: a late QB at QB-typical points is not the top steal over a WR who outscored every WR drafted ahead of him. Verification re-runs NFL 2025's top 5 |
| F2 | NBA counted-for-you (credited) > production (average) for 44 of 168 picks | **Fixed.** `countedForYou` is on production's basis, and invariant #8 says `countedForYou ≤ production`. Sleeper's exact sum is a separate `creditedForYou`, labelled as a different scale. US3's test is restated |
| F3 | Counted-for-you includes playoff weeks with no matchup | **Fixed.** A started week counts only if `league_matchup.matchup_id` is non-null. Test with a bye week |
| F4 | Team value isn't centred (NBA 2025 sums to −3,600, 8/12 teams negative) | **Fixed.** `draftValue` = raw − the draft's average team, labelled "vs. the average team in this draft". The raw average is sent (`averageTeamRawValue`) |
| F5 | NBA label asserts a per-league rule measured in two leagues; "avg game per week" next to a season sum | **Fixed.** The wording states what was measured ("in the basketball leagues measured so far…"). The column is "season points, counting each week's average game" |
| F6 | `PlayerGameRepository.forPlayers` already exists; the overload invites array-bind bugs | **Fixed.** Reuse `forPlayers(sport, season, ids)` (`:171`), filter weeks in Java, read scoring via `LeagueRepository.scoringOf`. Build step 3 removed |
| F7 | A final league week with no per-game data reads as "everyone didn't play" | **Fixed.** Counted weeks = `finalWeeks` ∩ weeks in `sport_week_stats`. The gap is named in `weeksMissingGameData`. Test |
| F8 | Two "independent" toggles fight over one cell, and `tintPercent` saturates on points | **Fixed.** The views are exclusive (segmented control). Value over slot gets its own tint scale, relative to the draft's spread. Vitest |
| F9 | Clipped windows bias the ends (NBA picks 1–6 +51.9) | **Fixed.** The window shifts inward to keep 2n picks. It's moot in part now that windows are positional, and the end bias is re-checked in verification |
| F10 | Null starters / `{}` points would count as 0 | **Fixed.** Counted as `weeksUnknownForYou`, never 0. Test |
| F11 | `AccessControlMvcIT` lists routes by hand | **Fixed.** `/grades` is added for no header, blank, stranger (404) and member (200) |

**Amended during build (2026-10-06):** F1's positional neighbours rule, measured on real data
after it was built, made the first player at each position a steal by construction (NBA
picks 1–6 +106.7; Jokić and Dončić in the top 4). Allan chose a **per-position log fit**
(`a + b·ln(pickNo)` per position, min 8 picks) over keeping it or a rank-matched rule. The
measurements are in research R5's note, and the rule is in data-model.

Notes taken: N1 (ambiguous or missing roster → null + `unmappedPicks`; `slot_to_roster_id`
is named as a known gap), N2 (signatures corrected), N3 (`@EnableConfigurationProperties`;
the field is null, not the bean), N4 (Vitest over the reason sentences), N5 (`gradesEarly` +
`earlyThresholdWeeks`), N6 (`countedWeeks` list), N7 (even median defined), N8 (busts exclude
steals), N9 (no graded picks → null, unranked), N10 (`DraftGradeStrip`, new class), N11
(empty positions → null, not WR), N12 (moot: the window is now a count, not a fraction),
N13 (check order defined), N14 (named in data-model and R2), N15 (one line in R3), N16
(the DB-only claims are re-run in verification V0), N17 (verification lists the 0-week
picks by name).

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5 (backend), TypeScript + React + Vite (web).

**Primary Dependencies**: existing only. `GameScoringService`, `ScoredWeeks`,
`LetterGrades`, `SeasonWindow`, `SportRulesRegistry`, `PlayerGameRepository`,
`RosterWeekPointsRepository.breakdownsFor`, `RosterSeasonRepository.forLeague`,
`DraftRepository`, `LeagueMembership.visibleDraft`; web `GradeChip`, `GradesEarlyBadge`,
`DraftBoard`, `PlayerCard`, `stealsReaches.ts`.

**Storage**: Postgres, read only. No migration and no new repository method (data-model;
review F6 found the read already exists).

**Testing**: JUnit 5. A pure parity test on a committed real fixture (can't skip). A DB IT
over every stored starter-week (skips without Postgres, so check the skip count). Pure
`DraftGradesService` tests including preference ordering. Vitest for the board toggle and
labels. Live checks per [quickstart.md](quickstart.md).

**Target Platform**: Railway (backend + web services deploy separately, so deploy both).
Local dev on Windows, Postgres on 5433.

**Project Type**: web application.

**Performance Goals**: no target. R9 measured the main read at 22 ms for ~9.8k rows (NBA
2025). V3 times the real endpoint, and that's reported, not asserted.

**Constraints**: no new Sleeper call. No new stored data. No second grade curve or early
threshold. No server-side reach. No defaulted sport rule. Reasons are codes, and the UI's
sentences contain no ingest route (`NoIngestHintsInMessagesTest`). Phone width with no
page-level horizontal scroll.

**Scale/Scope**: 1 service, 1 controller, 1 properties record, a weights block, a health field, `api.ts` types, a board toggle + team strip + PlayerCard
section, and 4–5 test classes. Guess: ~2 sessions (the roadmap's "S + M", also a guess).

No NEEDS CLARIFICATION remain. R1–R11 resolve each open choice.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so the gates are
`AGENTS.md`'s working rules, as in plans 009–017.

| Gate | Status |
|---|---|
| Honesty over apparent confidence | ✅ NBA production is labelled "average game per week played", never "league points" (R2). Counted-for-you is the only exact league number and is labelled so. Unknowns stay null (unmapped roster, no ADP). Early is led with, not footnoted. |
| Verified vs assumed kept apart | ✅ research.md marks each item measured or reasoned. The SQL scoring check is called a reimplementation, not a pass of the Java (R1). The ~0.86 NBA scale and the "stat corrections" explanation are labelled. |
| Migrations append-only | ✅ No migration. |
| `api.ts` mirrors Java records | ✅ The contract carries the TS mirror. Same change (FR-011). |
| `Map.of` with nullable values | ✅ The response is records. Nullable fields are listed in the contract. Health's `Map.of` gains only a boolean. |
| Two implementations of one rule | ✅ One scoring rule (`GameScoringService`). The SQL copy is replaced by Java checks. One production rule for both sports (mean of the week's games). One early rule (`SeasonWindow`). One grade curve. Reach stays in `stealsReaches.ts` only. |
| Optional params that encode rules | ✅ Basis from the existing no-default `playsMultipleGamesPerScoringPeriod()`. A missing config block is a visible `NOT_CONFIGURED`, not a default fraction. |
| Don't retune constants to match guesses | ✅ The one new constant (3 neighbours per side, amended after review) is labelled ARBITRARY. Nothing is tuned to make a list of names come out. F1 changed the *rule*, because the review showed what it measured, not to fit an expected answer. |
| Scoring/ranking changes need ordering tests | ✅ Pick 10 > picks 1–9 → top steal (SC-003), a positional ordering test (late QB vs. WR, F1), and a "self excluded" test. |
| Corrections shown, not hidden | ✅ Dated amended notes on `claude/draft-grades.md` (production in basketball, ADP coverage), `competitor-gap-roadmap.md` (2.1 measured, "five weeks" → three), and `nba-schedule-grid-and-streaming.md` (the "~2×" premise). |
| Planning doc ≠ verified spec | ✅ Measurement overturned "sum every game" and "five weeks". It also found 2.1 already passes in SQL, so it's smaller than its "S". |
| Live verification is the bar | ✅ quickstart V3–V7 drive the real endpoint and the real UI. |
| Ask before committing; concurrent sessions | ✅ Own worktree `.claude/worktrees/018-draft-grades` off `origin/main` @ `c450f67` (017 merged). Nothing committed without asking. |
| Coding subagents run on Sonnet | Applies at `/speckit-implement`. |

**Post-design re-check**: unchanged. The design added no layer and no table. The only new
config is the one hand-set fraction, which is labelled.

## Project Structure

### Documentation (this feature)

```text
specs/018-draft-grades/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R11
├── data-model.md        # reads, derived values, states, config
├── quickstart.md        # V1–V8
├── contracts/
│   └── api.md           # C1 /grades, TS mirror, invariants, UI contract
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code (repository root)

```text
config/weights.yml                                   # + draft-grades.neighbors-per-side (ARBITRARY)

backend/src/main/java/com/ballknowers/draftsim/
├── DraftSimApplication.java                         # register DraftGradeProperties (N3)
├── config/DraftGradeProperties.java                 # new: field null when absent, validated when present
├── engine/DraftGradesService.java                   # new: pure core (grade(...)) + read(draft)
├── api/DraftGradesController.java                   # new: C1, visibleDraft-scoped
└── api/HealthController.java                        # + draftGradesLoaded

backend/src/test/java/com/ballknowers/draftsim/
├── engine/NflScoringParityTest.java                 # new: pure, real fixture (US1)
├── engine/NflScoringParityIT.java                   # new: every stored starter-week (US1)
├── engine/DraftGradesServiceTest.java               # new: ordering (incl. positional), baselines, basis, nulls, ties, byes, unknown weeks, missing game weeks
├── config/DraftGradePropertiesTest.java             # new: absent → null, bad → throws
├── api/DraftGradesControllerTest.java               # new: unavailable reasons, check order
└── api/AccessControlMvcIT.java                      # + /grades: no header, blank, stranger → 404; member → 200 (F11)
backend/src/test/resources/sleeper/
└── nfl-scoring-parity-2025.json                     # trimmed real rows: scoring, stat lines, credited points

web/src/
├── api.ts                                           # types + getDraftGrades
├── draftGrades.ts (+ .test.ts)                      # new: labels/legend, reason sentences (no ingest route, N4), tint scale
├── pages/CompletedDraftBoard.tsx (+ .test.tsx)      # exclusive value-view control (F8)
├── components/DraftGradeStrip.tsx                   # new; not TeamStrip, which exists (N10)
├── components/DraftBoard.tsx                        # cell shows value over slot, own tint scale (F8)
└── components/PlayerCard.tsx                        # labelled grade section for a completed pick

claude/draft-grades.md                               # amended note
claude/competitor-gap-roadmap.md                     # Phase 2 status line + amended note
claude/nba-schedule-grid-and-streaming.md            # amended note on the "~2×" premise
```

**Structure Decision**: the existing web-application layout, with the `store/` → `engine/`
→ `api/` split every league page uses.

## Build order

*Amended after review (2026-10-06): step 3 removed (F6), and the review's tests folded in.*

Each step can be tested alone. US1 can ship by itself.

0. **Re-run the DB-only research claims** that the review couldn't (N16): `weeksCounted`
   21 via `ScoredWeeks`, `adp_at_time` coverage, D/ST rows, R1's other three leagues, R9's
   timing, and R6's 168/168. Also check that `sport_week_stats` has rows for every final
   week of NBA 2024/2025 and NFL 2025/2026 (F7's intersection depends on them).
1. **US1 first, and stop if it fails.** Build the parity fixture from real DB rows (one 2025
   league's `scoring_json`, ~40 starter-weeks across QB/RB/WR/TE/K/DEF plus a 0-point
   inactive, with Sleeper's credited points). Write `NflScoringParityTest` and
   `NflScoringParityIT`. Run V1, including the mutation check. If anything mismatches, the
   rest of this plan waits.
2. **Config.** `DraftGradeProperties` + registration in `DraftSimApplication` + the test +
   the weights block + `draftGradesLoaded`.
3. **`DraftGradesServiceTest`, then the pure core.** Write the tests first and see them
   fail:
   - ordering (SC-003), and positional ordering (F1);
   - self excluded, the window shifted inward at the ends (F9), and the even median (N7);
   - NBA mean vs. NFL single game; non-final weeks ignored; final weeks without game data
     excluded and named (F7);
   - unmapped and ambiguous manager → null (N1); a bye week not counted (F3); unknown weeks
     counted, not zeroed (F10); `countedForYou ≤ production` (F2);
   - centring sums to ~0 (F4); a team with no graded picks → null and unranked (N9); tied
     teams share a grade; busts exclude steals (N8); empty positions → null (N11).

   Then the pure `grade(...)` over plain inputs.
4. **`read(draft)` + controller (C1)**, reading the league row by `draft.league_id`, never
   the season resolver. Follow the check order in data-model. Add the `AccessControlMvcIT`
   routes (F11). Run V3–V5. → *backend done.*
5. **DB IT for the read path**: real NBA 2025 or a seeded slice, checking contract
   invariants 1–9, read back, not just counted.
6. **Web**: `api.ts` types + `getDraftGrades`; `draftGrades.ts` (labels, reason sentences,
   tint scale) + tests; the exclusive value-view control; `DraftGradeStrip`; cell value;
   the PlayerCard section. Vitest: no "How it played out" for an incomplete draft, the NBA
   legend wording, the unavailable sentence, switching views (F8), reason sentences free of
   ingest routes (N4). Run V7.
7. **HANDOFF status.** The doc amendments went in with this plan. Update them only if the
   build overturns something.
8. **Bug-hunting review** (separate pass), then **live verification** V0–V8, each recorded
   as run or not run in `verification.md`. Deploy both Railway services, then repeat V3 and
   V7.2 in production.

## Complexity Tracking

No gate violations.

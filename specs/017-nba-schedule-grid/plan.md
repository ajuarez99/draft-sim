# Implementation Plan: NBA schedule grid and next opponent for basketball

**Branch**: `017-nba-schedule-grid` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/017-nba-schedule-grid/spec.md`. That's
Phase 1 (1.1–1.3) of `claude/competitor-gap-roadmap.md`. **Deadline: before the NBA
season on 2026-10-20.**

## Summary

Store the schedule the per-game refresh already fetches and discards (R2). Lift its
parser to one shared class with one "does this game count" rule (R3). Serve a
basketball-only team × week grid with a playoff-weeks view (R5, R8). Give league home a
next-opponent endpoint that doesn't depend on projections, for both sports (R6, R7).

Measuring before planning changed three things from the design doc:

- Every team has **80** games today, not 82 (R1).
- Raw row counts are wrong both ways in 2025: postponements and their makeups are
  separate rows, and there's a canceled All-Star game (R1).
- "Next week" can't be "last stored + 1" for basketball, because NBA stores the
  current week partially. Sleeper's `leg` is used instead (R6).

## Amended after review (2026-10-05)

[plan-review.md](plan-review.md) read this plan cold against `78d86ad` and found 11
problems. Each one is taken as below. The other docs carry matching dated notes, and
the original text is corrected in place where it was wrong, not rewritten silently.

| # | Finding | Disposition |
|---|---|---|
| F1 | Neither service said which season it reads. Copying neighbours (`LeagueSeasonResolver`) would answer about 2025 until 2026 scores a week | **Fixed.** Both services read the league row the URL names (`leagues.bySleeperId`), never the resolver. New two-season-chain test (data-model, research R6) |
| F2 | The contract couldn't say "season over". A finished league would show weeks 21–25, and "Next N" would sum weeks after the league's playoffs | **Fixed.** C1 gains server-computed `seasonOver` and `lastLeagueWeek`. Default columns and Next-N stop at `lastLeagueWeek` |
| F3 | A storage failure at step 1 would skip per-game stats, the rest of the chain and trending, and a duplicate `game_id` would make that permanent | **Fixed.** Store *after* the week loop. Catch the failure, set `Result.scheduleStoreFailed`, and have `refreshChain` throw after the loop and trending. Rows come from the parser's de-duplicated map, and the insert upserts on conflict |
| F4 | "Existing tests unchanged" can't hold | **Fixed.** Step 2's proof is restated, with three named files and mechanical edits only |
| F5 | "Other round types aren't measured" was false (West Coast FF uses round type 1). Round type 0 has three data points, not one | **Corrected text.** **Decision:** still refuse round type ≠ 0. That's now a choice: no NBA league uses 1, and one NFL league isn't enough to encode it. A test row covers (15, 6, 1) → refused |
| F6 | V6 compared two NBA numberings, not league week vs schedule week | **Fixed.** V6 now compares league weeks to schedule weeks. The old query is kept as a separate, correctly labelled check |
| F7 | NFL home switching to `leg` is unmeasured at the Tue/Wed boundary | **Open, blocks step 8 for NFL only.** Curl on 10-06 and 10-07 and record the result in R6. Until then, NFL league home keeps its current analysis-backed block, and only basketball uses the new endpoint |
| F8 | The page and block didn't refetch after the visit's refresh | **Fixed.** Both key on `useLeagueDataVersion`, with a Vitest |
| F9 | The NBA block would link to football-only Analysis | **Fixed.** That link only shows when `analysisOffered(sport)`. Basketball links to the schedule destination via `destinationsFor` |
| F10 | Bye rule and name source were underspecified | **Fixed** in data-model: the bye rule, `LeagueMemberRepository` for team names, the name fallback stays client-side, and two rosters → lowest roster id |
| F11 | Quickstart steps wouldn't run | **Fixed:** V2 header/1 h gate/poll, V4 `ADMIN_TOKEN`, V7 dated post-draft check, V8.2 expects PHI |

Lower-severity notes taken: N1 (fixtures go in `src/test/resources/sleeper/`, nested
shape kept), N2 (`OffsetDateTime`/`LocalDate` via `setObject`, `@Transactional`, and the
IT reads values back), N3 (`store/` returns a code, `engine/` writes the sentence), N4
(`DestinationKey` union), N5 (look weeks up by number), N6 (a playoff week missing from
the schedule is shown as missing), N7 (R4 text corrected: exhibitions *were* observed),
N8 (the current-week column is labelled "including played"), N10 (season-scoped link
is spec 011's rule, not a bug), N11 (R5 contradiction removed). N9 is already covered
(`seasonTotal` is in the contract). N12 is out of scope, but it's mentioned in V7.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5 (backend), TypeScript + React + Vite
(web).

**Primary Dependencies**: existing only. `SleeperPlayerStatsClient.schedule`,
`PlayerGameIngestService`, `LeagueRepository`, `LeagueMatchupRepository`,
`RosterSeasonRepository`, `ManagerRepository.idsBySleeperUserId`, `LeagueMembership`,
`destinations.ts`, `useBlock`.

**Storage**: Postgres. One new table `sport_schedule` (V27, append-only; re-check the
highest V at build time). Two new reads from the existing `league.settings_json`
(`leg`, `playoff_round_type`).

**Testing**: JUnit 5. Pure tests against real trimmed 2025/2026 fixtures (can't be
skipped silently), a DB IT for replace and the empty guard (skips without Postgres, so
check the count), and Vitest for the page, destinations and LeagueHome. Live checks
per [quickstart.md](quickstart.md).

**Target Platform**: Railway (backend + web services deploy separately, so deploy
both). Local dev on Windows, Postgres on 5433.

**Project Type**: web application.

**Performance Goals**: the grid endpoint reads ≤ ~1,250 rows and aggregates in memory.
No target beyond "not noticeably slower than other league pages". That's unmeasured,
so measure it in V5 and report.

**Constraints**: no new Sleeper call (R2). No hand-maintained team list (R4). No
defaulted `leg` or `playoff_round_type` (R5, R6). User-facing reasons mustn't contain
ingest routes (`NoIngestHintsInMessagesTest` already scans `engine/` and `api/`).
Phone width without page-level horizontal scroll.

**Scale/Scope**: about 1 migration, 1 lifted class, 1 repository, 2 services,
2 controllers, 1 page, plus small edits to `PlayerGameIngestService`,
`LeagueRepository`, `LeagueHome`, `destinations.ts`, `App.tsx` and `api.ts`. Guess:
~3 sessions ("M" in the roadmap, a guess there too).

No NEEDS CLARIFICATION remain. The open choices were resolved in research R2–R8.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so the gates are
`AGENTS.md`'s working rules, as in plans 009–016.

| Gate | Status |
|---|---|
| Honesty over apparent confidence | ✅ The page states the fetch time and that the NBA changes the schedule. Excluded games are counted and reported (`excluded`), not hidden. Playoff weeks are refused for unmeasured formats rather than guessed (R5). The Cup explanation is kept out of the UI because it's inferred (R1). |
| Verified vs assumed kept apart | ✅ research.md labels each item measured or reasoned. Week = leg is an assumption with a direct check (quickstart V6). Sizes and the fixture size are labelled guesses. |
| Migrations append-only | ✅ V27, new table only. The data model says to re-check the number at build time. |
| `api.ts` mirrors Java records | ✅ The contract includes the TS mirror (FR-011), same change. |
| `Map.of` with nullable values | ✅ Responses are records. Nullable fields (`fetchedAt`, `currentWeek`, `teamName`, `opponent`…) are listed in the contract. |
| Two implementations of one rule | ✅ One parser and one counting predicate (R3). One "current week" rule, `leg` (R6). The analysis week keeps a different meaning and is never shown contradicting it (FR-010). One sport gate (`sports` on the destination). |
| Optional params that encode rules | ✅ `leg` and `playoff_round_type` are nullable reads, explicitly not coalesced (R5, R6). This is the trap memory records three times. |
| Don't retune constants to match guesses | ✅ The design doc's "82" is corrected in the open, not made true by filtering. N = 1–4 is labelled a UI choice. |
| Scoring/ranking changes need ordering tests | ✅ The sort gets a preference-ordering test (R9). |
| Corrections shown, not hidden | ✅ An amended note goes on `claude/nba-schedule-grid-and-streaming.md` acceptance #1 (80, not 82, and changes in season). |
| Planning doc ≠ verified spec | ✅ Measurement overturned the doc's 82 and its "week-1 then done" framing. Reading code found the fetch already happening, so 1.1 is smaller than "S". |
| Live verification is the bar | ✅ quickstart V7–V8 drive the real server and a real browser, plus production before 10-20. |
| Ask before committing; concurrent sessions | ⚠️ Branch `017-nba-schedule-grid` off `origin/main` @ `fffce72`, in the main checkout (not a worktree). Diff before every commit, and don't commit without asking. |
| Coding subagents run on Sonnet | Applies at `/speckit-implement`. |

**Post-design re-check**: unchanged. The design added nothing that needs justifying.
The one structural change (lifting `Schedule` out of the ingest) removes a would-be
duplicate rather than adding a layer.

## Project Structure

### Documentation (this feature)

```text
specs/017-nba-schedule-grid/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R9
├── data-model.md        # sport_schedule, SportSchedule, derived rules
├── quickstart.md        # V1–V8
├── plan-review.md       # adversarial review, F1–F11 + N1–N12
├── contracts/
│   └── api.md           # C1 /schedule, C2 /next-matchup, TS mirror, invariants
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
└── V27__sport_schedule.sql                      # new

backend/src/main/java/com/ballknowers/draftsim/
├── ingest/SportSchedule.java                    # new: lifted from PlayerGameIngestService.Schedule + counts()
├── ingest/PlayerGameIngestService.java          # use SportSchedule; store after parse (empty guard)
├── store/SportScheduleRepository.java           # new: replaceSeason, forSeason, fetchedAt
├── store/LeagueRepository.java                  # PlayoffFormat.playoffRoundType (nullable) + lastPlayoffWeek() (code, not text); currentLeg()
├── engine/ScheduleGridService.java              # new: pure core + read
├── engine/NextMatchupService.java               # new
├── api/ScheduleController.java                  # new: C1
└── api/NextMatchupController.java               # new: C2 (or one controller for both; build-time choice)

backend/src/test/java/com/ballknowers/draftsim/
├── ingest/SportScheduleTest.java                # parse both shapes, counts()
├── ingest/PlayerGameWeekIngestTest.java        # mechanical rename only (F4); + stores after the loop, [] doesn't wipe, store failure keeps per-game rows (F3)
├── engine/ScheduleGridServiceTest.java          # 2025 fixture → SC-003; 2026 fixture → SC-002; sort ordering
├── engine/NextMatchupServiceTest.java           # every state-table row + two-season chain (F1)
├── store/PlayoffWindowTest.java                 # round type 0/null/1/2, teams 6/4/2/1, (15,6,1) refused (F5)
└── store/SportScheduleRepositoryIT.java         # replace, empty guard, rollback
backend/src/test/resources/sleeper/      # N1: where existing Sleeper fixtures live
├── nba-schedule-2025.json                       # trimmed real payload
└── nba-schedule-2026.json

web/src/
├── api.ts                                       # types + getLeagueSchedule, getNextMatchup
├── destinations.ts                              # `schedule` destination, sports: ['nba']
├── App.tsx                                      # route /leagues/:sleeperLeagueId/schedule
├── pages/ScheduleGrid.tsx (+ .test.tsx)         # new
└── pages/LeagueHome.tsx (+ .test.tsx)           # NextOpponentBlock reads next-matchup for both sports

claude/nba-schedule-grid-and-streaming.md       # amended note on acceptance #1
```

**Structure Decision**: the existing web-application layout. New backend code follows
the `store/` → `engine/` → `api/` split that every league page uses.

## Build order

Ordered so each step is testable alone, and so US1 can ship by itself if time runs out.

1. **Fixtures first.** Save trimmed real 2025 and 2026 payloads in
   `src/test/resources/sleeper/` (six keys, nested `home`/`away` kept, ~158 KB and
   ~153 KB measured). Write `SportScheduleTest` and `ScheduleGridServiceTest`
   asserting SC-002 and SC-003, and watch them fail (no class yet).
2. **Lift `SportSchedule`** with `counts()` and a de-duplicated `games()`.
   *Amended (F4):* existing tests may change only mechanically:
   - `PlayerGameWeekIngestTest` (`Schedule` → `SportSchedule` at :438, :450,
     :510–513; one extra mock constructor argument at :119)
   - `PlayoffOddsServiceTest` (`PlayoffFormat` arity at :51, :116, :128, in step 4)
   - no assertion may change in either diff. That's the proof the lift changed no
     behavior.
3. **V27 + `SportScheduleRepository`** + the IT (values read back, not just counts).
   *Amended (F3):* wire the store into `doRefresh` **after the week loop**. Add the
   empty guard. A storage failure is caught, logged and returned as
   `Result.scheduleStoreFailed`. `refreshChain` throws for it after the season loop
   and after trending, like `weeksFailed`. Run V2 and V3.
4. **`LeagueRepository`**: `currentLeg`, nullable `playoffRoundType`, and
   `lastPlayoffWeek()` returning a refusal *code*, not a sentence (N3). Plus
   `PlayoffWindowTest`, including (15, 6, 1) → refused (F5).
5. **`ScheduleGridService` + controller (C1)**. It reads the URL's league row, never
   the resolver (F1), and computes `seasonOver`/`lastLeagueWeek` (F2). Run V4 and V5.
   → *US1/US2 backend done.*
6. **Web: `api.ts` types, `DestinationKey` + destination, route, `ScheduleGrid` page**,
   keyed on `useLeagueDataVersion` (F8). Run V8.1–4. → *US1/US2 shippable.*
7. **`NextMatchupService` + controller (C2)** + tests. It uses the URL's league row
   (F1), the bye rule and `LeagueMemberRepository` names (F10). Run V7.
8. **`LeagueHome` NextOpponentBlock.** Basketball reads C2. The link is gated on
   `analysisOffered`, so basketball links to Schedule (F9), and the block keys on the
   version (F8). *NFL stays on the analysis block until F7's boundary measurement
   (10-06/10-07) is recorded in R6.* Only then switch NFL to C2, with the projected
   line only when weeks agree. Run V8.5.
9. **HANDOFF/roadmap status lines.** The design-doc amendment is already in
   `78d86ad`.
10. **Bug-hunting review** (separate pass), then **live verification** V1–V8, each
    recorded as run or not run. Deploy **both** Railway services and repeat V8.1–2 in
    production before 2026-10-20.

The adversarial plan review (this repo's pipeline stage 2) should happen **before**
step 1, reading this plan cold.

## Complexity Tracking

No gate violations. The one ⚠️ (main checkout, not a worktree) is a process caution,
not a design violation.

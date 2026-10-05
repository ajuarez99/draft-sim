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
├── store/LeagueRepository.java                  # PlayoffFormat.playoffRoundType (nullable) + lastPlayoffWeek(); currentLeg()
├── engine/ScheduleGridService.java              # new: pure core + read
├── engine/NextMatchupService.java               # new
├── api/ScheduleController.java                  # new: C1
└── api/NextMatchupController.java               # new: C2 (or one controller for both; build-time choice)

backend/src/test/java/com/ballknowers/draftsim/
├── ingest/SportScheduleTest.java                # parse both shapes, counts()
├── ingest/PlayerGameIngestServiceTest.java      # stores the schedule; [] doesn't wipe
├── engine/ScheduleGridServiceTest.java          # 2025 fixture → SC-003; 2026 fixture → SC-002; sort ordering
├── engine/NextMatchupServiceTest.java           # every state-table row
├── store/PlayoffWindowTest.java                 # round type 0/null/1/2, teams 6/4/2/1
└── store/SportScheduleRepositoryIT.java         # replace, empty guard, rollback
backend/src/test/resources/fixtures/
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

1. **Fixtures first.** Save trimmed real 2025 and 2026 payloads. Write
   `SportScheduleTest` and `ScheduleGridServiceTest` asserting SC-002 and SC-003, and
   watch them fail (no class yet).
2. **Lift `SportSchedule`** with `counts()`. The existing `PlayerGameIngestService`
   tests must stay green, unchanged. That's the proof the lift changed no behavior.
3. **V27 + `SportScheduleRepository`** + the IT. Wire the store into `doRefresh`
   with the empty guard. Run V2 and V3.
4. **`LeagueRepository`**: `currentLeg`, `playoffRoundType`, `lastPlayoffWeek` +
   `PlayoffWindowTest`.
5. **`ScheduleGridService` + controller (C1)**. Run V4 and V5. → *US1/US2 backend done.*
6. **Web: `api.ts` types, destination, route, `ScheduleGrid` page**. Run V8.1–4. →
   *US1/US2 shippable.*
7. **`NextMatchupService` + controller (C2)** + tests. Run V7.
8. **`LeagueHome` NextOpponentBlock** on C2 for both sports. NFL projected line only
   when weeks agree. Run V8.5.
9. **Doc amendment** on the design doc (and HANDOFF/roadmap status lines).
10. **Bug-hunting review** (separate pass), then **live verification** V1–V8, each
    recorded as run or not run. Deploy **both** Railway services and repeat V8.1–2 in
    production before 2026-10-20.

The adversarial plan review (this repo's pipeline stage 2) should happen **before**
step 1, reading this plan cold.

## Complexity Tracking

No gate violations. The one ⚠️ (main checkout, not a worktree) is a process caution,
not a design violation.

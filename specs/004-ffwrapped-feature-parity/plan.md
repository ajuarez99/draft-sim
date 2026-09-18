# Implementation Plan: Down the ffwrapped list, in both sports

**Branch**: `004-ffwrapped-feature-parity` | **Date**: 2026-09-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-ffwrapped-feature-parity/spec.md`

## Summary

Work down ffwrapped's nineteen-view sidebar, closing the gaps against Ball Knowers, with NBA as a
first-class acceptance criterion rather than a later pass.

The plan is sequenced by **data dependency**, not by how impressive each view looks, because the survey
in [research.md](research.md) found the cost curve is not where it appears to be:

- One small, load-bearing fix unblocks basketball for every backward-looking view at once
  (`BasketballRules.startingLineup`, R4).
- Three substantial views — Roster Management, Expected Wins, most of Weekly Report — need **no new
  ingest at all**, because `roster_week_points.players_points` is already stored for every scored week
  of every ingested season, in both sports (R2).
- Two more — Season Forecast and Playoffs — are already *computed* by `PlayoffOddsSimulator` and thrown
  away at serialization (R8).
- Only one committed story, transactions (US6), needs a genuinely new ingest pipeline (R7).
- Five views are projection-bound and stay explicitly NFL-only, with a declared seam rather than an
  implementation (R9).

So the ordering is: make the sport seam honest, then spend the data that is already in the database,
then expose the simulation that is already running, then add the one new pipeline.

## Technical Context

**Language/Version**: Java 21 (Spring Boot backend), TypeScript + React (Vite frontend)

**Primary Dependencies**: Spring Boot, JDBC/Flyway, Postgres 17; React, React Router, Vite, Vitest

**Storage**: PostgreSQL 17. Existing tables in play: `roster_week_points`, `roster_season`,
`league_matchup`, `playoff_odds`, `league`, `manager`, `league_member`, `player`. One new table
(`league_transaction`) and one new column (`roster_week_points.starters`) are introduced, in US6 and US5
respectively — not before.

**Testing**: JUnit + Spring Boot integration tests (Postgres-backed) for the backend; Vitest +
Testing Library for the frontend. Note the known trap: the backend suite reports `BUILD SUCCESSFUL`
with integration tests **skipped** when Postgres is down — the skip count must be read, not the build
result.

**Target Platform**: Web. Deployed on Railway at ballknowers.co as split services that deploy
independently.

**Project Type**: Web application (backend + frontend in one repo).

**Performance Goals**: Each new read endpoint answers in well under a second for a 12-team league with a
full season stored. Potential points is O(weeks x rosters x roster size log roster size); for 18 weeks x
12 rosters that is trivial. Nothing new is computed on a page load that is not already loaded from
storage.

**Constraints**:
- Nothing is computed on page load for snapshot-backed figures (existing rule, preserved).
- No sport branch in the service layer; sport-specific behaviour goes through `SportRules`.
- A new per-week column requires a working backfill path, because the ingest skip gate is keyed on rows
  existing rather than columns being populated (R6).
- Several Claude sessions share this tree, DB and server; measure defensively rather than assuming the
  DB is in the state a previous step left it.

**Scale/Scope**: 5 leagues, up to ~53 managers, 12-team leagues, up to 18 weeks per season. Six user
stories; four new league-scoped pages; one new table; one new column.

**NEEDS CLARIFICATION**: NBA lineup cadence — whether the target NBA leagues set lineups weekly or
daily, and whether any ingested NBA league yet has scored weeks to verify against (R10). Confined to the
first task of US1; blocks neither the football work nor the shared code path. Mitigation in place: NBA
acceptance runs against a fixture derived from a real Sleeper NBA matchup payload.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: NOT APPLICABLE — no ratified constitution.**

`.specify/memory/constitution.md` is the unmodified Spec Kit template; every principle is still a
`[PRINCIPLE_N_NAME]` placeholder (R11). There are no project gates to evaluate, so this gate cannot fail
and cannot meaningfully pass.

In its place, this plan is checked against the conventions the repo actually enforces, all drawn from
existing code comments and prior specs:

| Convention | Where it comes from | How this plan satisfies it |
|---|---|---|
| One declaration per rule | `destinations.ts` header; `FootballRules.startingLineup` javadoc | FR-001, FR-002, FR-012; US1 removes the throwing default outright |
| Honest refusal over a confident wrong number | `PlayoffOddsService`; `LeagueAnalysisService.MIN_SCORED_WEEKS` | FR-007, FR-009; US2.4, US4.3, US4.4, US5.4 |
| Explicit sport lists, never defaulted | `LeagueDestination.sports` javadoc; memory | FR-005, R9 |
| Verify by running, not by building | Backend suite skip-count trap | quickstart.md verification steps |
| Label the axis, spell out the number | memory; prior feedback | FR-011; US2.2, US6.2 |

**Post-Phase 1 re-check**: unchanged. The design adds no sport branch outside `SportRules`, adds no
second computation path for playoff figures, and introduces storage only in the two stories that
require it.

## Project Structure

### Documentation (this feature)

```text
specs/004-ffwrapped-feature-parity/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output — the measured findings
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output — how to verify each story
├── contracts/           # Phase 1 output — API contracts
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── sport/
│   ├── SportRules.java                  # US1: drop the throwing default
│   ├── BasketballRules.java             # US1: implement startingLineup; delegate value to it
│   └── FootballRules.java               # US1: unchanged behaviour, asserted by test
├── engine/
│   ├── RealizedLineupService.java       # NEW  US2: per-week optimal lineup from players_points
│   ├── RosterManagementService.java     # NEW  US2: total / potential / efficiency
│   ├── ExpectedWinsService.java         # NEW  US3: all-play, SoS, swing weeks
│   ├── WeeklyReportService.java         # NEW  US5: matchups, awards, top performers
│   ├── TransactionAnalysisService.java  # NEW  US6: counts by type, trade + add grading
│   ├── PlayoffOddsSimulator.java        # US4: emit seed + win distributions
│   └── PlayoffOddsService.java          # US4: store and serve them
├── ingest/
│   ├── LeagueHistoryIngestService.java  # US5: skip gate reads the new column; US6: call transactions
│   ├── TransactionIngestService.java    # NEW  US6
│   └── SleeperClient.java               # US6: existing transactions() finally gets a caller
├── store/
│   ├── RosterWeekPointsRepository.java  # US5: starters column
│   ├── LeagueTransactionRepository.java # NEW  US6
│   └── PlayoffOddsRepository.java       # US4: distributions
└── api/
    └── LeagueAnalyticsController.java   # NEW: the four new read endpoints

backend/src/main/resources/db/migration/
├── V17__roster_week_starters.sql        # US5
├── V18__playoff_odds_distributions.sql  # US4
└── V19__league_transaction.sql          # US6

web/src/
├── destinations.ts                      # every new page declared here, with explicit sports
├── pages/
│   ├── RosterManagement.tsx             # NEW  US2
│   ├── ExpectedWins.tsx                 # NEW  US3
│   ├── SeasonForecast.tsx               # NEW  US4
│   └── WeeklyReport.tsx                 # NEW  US5 (+ US6 sections)
└── components/                          # shared chart + table pieces
```

**Structure decision**: Web application layout, matching the existing repo. New analysis logic goes in
`engine/` beside `LeagueAnalysisService` and `PowerRankingService`, which are the closest existing
neighbours and already establish the read-only-service-plus-controller shape. `RealizedLineupService` is
split out from `RosterManagementService` deliberately: three stories (US2, US5, and any later
efficiency-based award) need "what was this roster's best possible lineup in week N", and that must be
one implementation, not three.

## Phase sequencing

Each phase is independently shippable and leaves the app working.

| Phase | Story | New storage | Sports | Depends on |
|---|---|---|---|---|
| 1 | US1 — basketball starting lineup | none | both | — |
| 2 | US2 — Roster Management | none | both | US1 |
| 3 | US3 — Expected Wins | none | both | — (parallel with US2) |
| 4 | US4 — Season Forecast + Playoffs | `V18` columns | both | — (parallel) |
| 5 | US5 — Weekly Report | `V17` + backfill | both | US1, US2 |
| 6 | US6 — Transactions | `V19` table + ingest | both | US2 |

US3 and US4 touch nothing US2 touches and can run in parallel with it. US1 gates US2 and US5 only.

## Complexity Tracking

No constitutional violations to justify (no constitution). Two deliberate complexity choices worth
recording:

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| Compute potential points per week through `SportRules` rather than reading `roster_season.points_possible` | Per-week values are required by the chart, the awards and the bad-week exclusion; and it keeps one definition of "starting lineup" across the app | Sleeper's `ppts` is a season aggregate under Sleeper's rules — adopting it makes efficiency disagree with every other lineup-based view, especially in basketball where eligibility is a matroid problem (R3) |
| A separate `RealizedLineupService` rather than a method on `RosterManagementService` | Three stories need the same per-week optimal lineup | Inlining it would produce the exact "two implementations of one rule" bug this repo has shipped three times (R4) |

## Risks

1. **NBA verification data may not exist yet** (R10). Mitigated by fixture-based acceptance; the risk is
   that a live NBA league later reveals a cadence assumption the fixture did not encode. Accepted, and
   confined by refusing daily-lineup leagues explicitly rather than computing a number for them.
2. **The `starters` backfill silently no-ops** (R6). This has already happened once in this repo. Mitigated
   by extending the skip gate in the same change as the migration, and by a quickstart step that counts
   populated rows instead of trusting the build result.
3. **Backend integration tests skip silently when Postgres is down.** Mitigated by reading the skip count
   in every verification step, per quickstart.
4. **Concurrent sessions share this tree, DB and server.** Mitigated by measuring current state before
   each verification rather than assuming a prior step's result still holds.

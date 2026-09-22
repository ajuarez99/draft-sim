# Implementation Plan: A history deep enough to argue with

**Branch**: `006-deeper-history-both-sports` | **Date**: 2026-09-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-deeper-history-both-sports/spec.md`

## Summary

Take the manager page from a W/L table to ffwrapped's Manager Profiles — career record, win rate,
efficiency, wins above expected, titles, ranks, head-to-head — and deepen the league record book, for
both sports.

Two measured facts decide the shape of the work, and both are corrections rather than additions:

1. **A manager's seasons currently mix football and basketball in one table with no sport on any row**
   ([research.md](research.md) R1). Eleven of twelve Ball Knowers managers play both leagues, so every
   populated manager page in the app is showing a summed record that spans two sports. Nothing built on
   top of that is worth computing until it is fixed, which makes it P1.

2. **A season one week old already has a champion** (R2). The ingest reads
   `metadata.latest_league_winner_roster_id`, which on an in-season league names the *previous*
   season's winner — verified against Sleeper directly. Two of the six stored placements are wrong,
   the 🏆 renders on an in-progress season, and every career "titles" figure inherits the error.

After those, the profile itself is mostly aggregation over data already stored for both sports: the
ingest blockers that shaped 002 and 004 are gone — pairings are backfilled to 196–280 rows per season
(002 measured 8), `starters` is populated on every stored week, and transactions are ingested for both
sports.

The one genuine design decision is R3: **there are already three different efficiencies for one
manager-season** (92.7% from the stored `points_possible`, 91.6% from the optimal-lineup service,
87.9% from ffwrapped), and the cheap path is the wrong one. The career average is built from the same
computation the Roster Management view uses, and the page names the weeks it covers.

## Technical Context

**Language/Version**: Java 21 (Spring Boot backend), TypeScript + React (Vite frontend)

**Primary Dependencies**: Spring Boot, JDBC/Flyway, Postgres 17; React, React Router, Vite, Vitest

**Storage**: PostgreSQL 17. **One new column, no new tables**: `league.status`, Sleeper's own league
status, which is currently dropped by `LeagueMapper` and is not present in `settings_json` (verified:
`settings_json ? 'status'` is false for all nine league rows). Latest existing migration is `V20`, so
this feature takes **V21**. Existing tables read: `roster_season` (record, points, placement),
`roster_week_points` (`starters_points`, `players_points`), `league_matchup` (pairings),
`league_transaction` (waivers, FAAB bids), `league` (`sport`, `settings_json.waiver_type`,
`settings_json.waiver_budget`), `manager`, `league_member`.

**Testing**: JUnit + Spring Boot integration tests (Postgres-backed) for the backend; Vitest + Testing
Library for the frontend. The known trap applies: the backend suite prints `BUILD SUCCESSFUL` with
integration tests **skipped** when Postgres is down — read the skip count from
`backend/build/test-results/test/*.xml`, never the build result.

**Target Platform**: Web. Deployed on Railway at ballknowers.co as split services that deploy
independently.

**Project Type**: Web application (backend + frontend in one repo).

**Performance Goals**: the manager profile answers in under ~250 ms warm. Measured inputs (R4):
`roster-management` costs 29–120 ms per league-season and `expected-wins` 15–27 ms; the deepest manager
has six roster-seasons across three leagues, so a naive loop is 300–600 ms. The fix is named and
bounded — `RosterManagementService.forLeague` reloads `players.findAll(sport)` (4386 NFL / 2066 NBA
rows) on every call, and hoisting that map across the loop is the whole optimisation.

**Constraints**:

- No sport branch in a service or component; sport-specific behaviour reaches services only through
  `SportRules` (FR-012), as `RosterManagementService`'s javadoc already requires of itself.
- Exactly one implementation of potential points. `roster_season.points_possible` stays stored and
  stays unread by any service that reports efficiency (FR-006, SC-004).
- Every career figure states the season count it rests on (FR-007, SC-008); with one or two played
  seasons per chain, a figure that does not say so is a season record dressed as a career one.
- Every rank names its population (FR-008). A bare `#2` is not a rank.
- A figure that cannot be attributed is omitted with its reason, never zero (FR-011) — trades, today.
- Charts carry the exact value beside the mark and a labelled axis; one encoding per mark (FR-014).
- Several Claude sessions share this tree, DB and server; measure current state before verifying rather
  than trusting what an earlier step left behind.

**Scale/Scope**: 3 league chains (2 football, 1 basketball), 6 played league-seasons, 12–14 managers
each, 11 managers active in both sports. Six user stories; two of them are corrections to shipped
behaviour and ship first.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is **the unedited Spec Kit template** — every principle is still a
`[PRINCIPLE_N_NAME]` placeholder, and the governance section is `[GOVERNANCE_RULES]`. There are
therefore no ratified project gates to evaluate, and this section cannot honestly report a pass against
principles that have never been written. That is stated rather than silently skipped.

In their place, this plan is checked against the constraints the repository actually enforces, which
live in `AGENTS.md`, `claude/lessons.md` and the four shipped specs:

| Enforced rule | Where it comes from | How this plan meets it |
|---|---|---|
| One implementation per rule | 004's `startingLineup` work; `RealizedLineupService`'s javadoc | FR-006 / SC-004: efficiency has one source, and a test asserts `points_possible` stays unread |
| No sport branch in a service | 004 FR-004; multi-sport landmines | FR-012; every new figure is computed through existing sport-agnostic services |
| No defaulted parameter that encodes a rule | `reversalRound`, `LeagueDestination.sports` | FR-013; the comparison page declares its sports explicitly |
| A cache gate in front of a column never repairs it | `adp_at_time`; 002's fixture gate | R2: the standings upsert must be able to *clear* a placement, not skip it |
| Count and label from one source | `feedback_count_and_label_same_source` | FR-007 / SC-008: the season count beside a figure is the count that produced it |
| One encoding per mark, exact value beside it | `feedback_label_the_axis_spell_out_the_number` | FR-014 |
| Empty states state a reason | 002's record book | US4.3, US6.2, FR-011 |

**Gate result**: no violations to justify; see [Complexity Tracking](#complexity-tracking).

Re-checked after Phase 1 design: the design adds one column and no tables, adds no second computation
of an existing rule, and introduces no sport branch. Still no violations.

## Project Structure

### Documentation (this feature)

```text
specs/006-deeper-history-both-sports/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── manager-profile-api.md
│   └── head-to-head-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
backend/src/main/
├── java/com/ballknowers/draftsim/
│   ├── api/
│   │   ├── LeagueHistoryController.java      # managerHistory() gains the profile; history() the deeper book
│   │   └── ManagerComparisonController.java  # NEW — US4
│   ├── engine/
│   │   ├── ManagerCareerService.java         # NEW — US3, the per-sport rollup
│   │   ├── HeadToHeadService.java            # NEW — US4
│   │   ├── LeagueRecordService.java          # extended — US5 streaks and all-time leaders
│   │   ├── RosterManagementService.java      # player map hoisted out of forLeague (R4)
│   │   └── ExpectedWinsService.java          # reused unchanged
│   ├── ingest/
│   │   ├── LeagueHistoryIngestService.java   # champion gated on status (US2)
│   │   └── LeagueMapper.java                 # carries status through (US2)
│   └── store/
│       ├── RosterSeasonRepository.java       # StandingRow gains sport + league name (US1)
│       ├── LeagueRepository.java             # LeagueRow gains status
│       ├── LeagueMatchupRepository.java      # pair lookup for head-to-head
│       └── LeagueTransactionRepository.java  # per-manager waiver/FAAB aggregation (US6)
└── resources/db/migration/
    └── V21__league_status.sql                # NEW

backend/src/test/java/com/ballknowers/draftsim/
├── engine/ManagerCareerServiceTest.java
├── engine/HeadToHeadServiceTest.java
├── engine/OneEfficiencyImplementationTest.java   # SC-004
├── ingest/ChampionOnlyWhenCompleteTest.java      # SC-003
└── store/ManagerHistorySportIT.java              # SC-001

web/src/
├── pages/
│   ├── ManagerHistory.tsx        # per-sport blocks + career profile (US1, US3)
│   ├── ManagerComparison.tsx     # NEW — US4
│   └── LeagueHistory.tsx         # deeper record book (US5)
├── destinations.ts               # comparison page registered, sports declared explicitly
└── api.ts                        # types for the new payloads
```

**Structure Decision**: the existing web-application layout (`backend/` + `web/`), unchanged. This
feature adds one migration, three backend services, one controller, one page, and extends four existing
files. No new module, no new deployment unit.

## Phased delivery

Each phase is independently shippable and independently verifiable, in the spec's priority order.

| Phase | Story | Ships | Proves |
|---|---|---|---|
| 1 | US1 | `sport` on every manager season row; per-sport blocks on the page | SC-001 |
| 2 | US2 | V21 `league.status`; champion gated on complete; placement clearable | SC-003 |
| 3 | US3 | `ManagerCareerService` + the profile panel, per sport, ranked | SC-002, SC-004, SC-005, SC-008 |
| 4 | US4 | `HeadToHeadService` + the comparison page | SC-006 |
| 5 | US5 | all-time leaders and streaks on League History | — |
| 6 | US6 | waiver/FAAB tendencies, with format exclusions stated | SC-007 |

Phase 2 must land before Phase 3 reports titles. Phase 1 must land before any phase reports a career
figure. Nothing after Phase 3 is a prerequisite for anything else.

## Complexity Tracking

> No Constitution Check violations to justify. The two judgement calls worth recording are below.

| Decision | Why | Simpler alternative rejected because |
|---|---|---|
| Add `league.status` (V21) rather than read Sleeper's status only at ingest | FR-007 needs a per-season completeness flag readable at query time; the only current proxy is positional (`i == 0` in `history()`), which silently breaks when a chain's newest season completes | Gating the write alone fixes the champion but leaves every reader unable to ask whether a season finished, which every career average depends on |
| Reuse the per-league services in a loop rather than write one career SQL rollup | A rollup can produce record, points and win rate but not efficiency or wins above expected, so half the profile would come from one rule and half from another | The performance argument for the rollup does not survive measurement: the loop's cost is one repeated `players.findAll(sport)`, which is hoisted (R4) |

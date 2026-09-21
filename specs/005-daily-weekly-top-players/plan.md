# Implementation Plan: Best nights, beside best weeks

**Branch**: `005-daily-weekly-top-players` | **Date**: 2026-09-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-daily-weekly-top-players/spec.md`

## Summary

Give the Weekly Report two rankings for basketball where it has one: the week's best **single-game**
performances, each naming the night and the opponent, and beside it the best **full-week** totals across
every game a player played. Football's page is left exactly as it is.

The shape of the work is decided by one measured fact ([research.md](research.md) R1): Sleeper returns a
whole season of per-game NBA detail — date, opponent, box score — in **one call per player**. So the
expensive-sounding half is a bounded backfill of ~331 calls per league-season, and the rest is
arithmetic this repo can already do.

The other decisive finding is R5. This league's scoring counts one game per player per week, and the
rule it uses to pick that game is **not understood** — not first, last, or highest. That forecloses the
tempting cheap design (enrich the counted game with a date) because it would rank a set chosen by an
unexplainable rule. Best Nights therefore ranks every game in the week, and the page says what the
figures do and do not represent.

## Technical Context

**Language/Version**: Java 21 (Spring Boot backend), TypeScript + React (Vite frontend)

**Primary Dependencies**: Spring Boot, JDBC/Flyway, Postgres 17; React, React Router, Vite, Vitest

**Storage**: PostgreSQL 17. One new table for per-player-per-game stat lines, **not league-scoped**,
following `V15__player_projection.sql`'s precedent (research R4). Latest existing migration is `V19`, so
this feature takes `V20`. Existing tables read: `league` (for `scoring_json`), `roster_week_points` (to
choose which players to fetch and to attribute ownership), `player`.

**Testing**: JUnit + Spring Boot integration tests (Postgres-backed) for the backend; Vitest + Testing
Library for the frontend. The known trap applies: the backend suite prints `BUILD SUCCESSFUL` with
integration tests **skipped** when Postgres is down — read the skip count from
`backend/build/test-results/test/*.xml`, never the build result.

**Target Platform**: Web. Deployed on Railway at ballknowers.co as split services that deploy
independently.

**Project Type**: Web application (backend + frontend in one repo).

**Performance Goals**: The weekly report answers in well under a second. Scoring a week in memory is
~600 game rows × ~15 scoring keys for a 12-team league — trivial. **Nothing new is fetched on a page
load** (FR-008); the per-game backfill is a separate, deliberate batch operation.

**Constraints**:

- No sport branch in a service or component. Which form renders follows from a new non-defaulted
  `SportRules` method (FR-004, research R7).
- Football's Weekly Report must be unchanged in what it shows (SC-004), which is cheapest to guarantee
  by not touching its code path.
- A figure that includes games the league's scoring did not count MUST say so (FR-005).
- Ordering must be deterministic including for exact ties (FR-009).
- Several Claude sessions share this tree, DB and server; measure current state before verifying rather
  than trusting what an earlier step left behind.

**Scale/Scope**: 2 NBA league-seasons ingested today (331 and 280 distinct players). Three user stories,
one new table, one new `SportRules` method, no new page — the existing Weekly Report gains two sections
and loses one for basketball only.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: NOT APPLICABLE — no ratified constitution.**

`.specify/memory/constitution.md` is the unmodified Spec Kit template; every principle is still a
`[PRINCIPLE_N_NAME]` placeholder. There are no project gates to evaluate, so this gate cannot fail and
cannot meaningfully pass.

In its place, this plan is checked against the conventions the repo actually enforces:

| Convention | Where it comes from | How this plan satisfies it |
|---|---|---|
| One declaration per rule | `destinations.ts` header; `FootballRules.startingLineup` javadoc | FR-004; the cadence rule is one non-defaulted `SportRules` method, not a check per call site |
| Explicit rules, never defaulted | memory: "a defaulted reversalRound/sport asserts a rule, not a value" | research R7; the new method has no default implementation |
| A cache shared by leagues stores facts, not one league's reading | `V15__player_projection.sql` comment | research R4; raw stat lines stored once, scored per reading league |
| Honest refusal over a confident wrong number | `PlayoffOddsService`; `LeagueAnalysisService.MIN_SCORED_WEEKS` | FR-006, FR-010; missing per-game detail says so rather than substituting the stored single-game value |
| Label the axis, spell out the number | memory; prior feedback | FR-007; every entry carries its exact value beside the name |
| Verify by running, not by building | backend suite skip-count trap | quickstart.md reads the XML skip count |
| Count and label from one source | memory (2026-09-17 ballot tally) | FR-002; a week total states how many games it covers |

**Post-Phase 1 re-check**: unchanged. The design adds no sport branch outside `SportRules`, introduces
one table at a grain nothing else occupies, and adds no second computation of any figure the app already
shows — Best Week is explicitly a different measure from the league's scored total, and FR-005 requires
it to say so.

## Project Structure

### Documentation (this feature)

```text
specs/005-daily-weekly-top-players/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output — the measured findings
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output — how to verify each story
├── contracts/           # Phase 1 output — API contracts
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── sport/
│   ├── SportRules.java                  # US3: new non-defaulted cadence rule
│   ├── BasketballRules.java             # US3: answers "more than once per period" = true
│   └── FootballRules.java               # US3: answers false; no other change
├── ingest/
│   ├── PlayerGameIngestService.java     # NEW  US1: per-player season walk, one call per player
│   └── SleeperClient.java               # US1: new per-game stats call
├── store/
│   └── PlayerGameRepository.java        # NEW  US1: upsert by game natural key, read by week
├── engine/
│   ├── GameScoringService.java          # NEW  US1: raw stats x league scoring_json
│   └── WeeklyReportService.java         # US1/US2: assemble bestNights and bestWeek
└── api/
    ├── WeeklyReportController.java      # US1/US2: same endpoint, new fields
    └── IngestController.java            # US1: expose the per-game backfill

backend/src/main/resources/db/migration/
└── V20__player_game.sql                 # US1

web/src/
├── api.ts                               # US1/US2: response types for the new sections
└── pages/
    ├── WeeklyReport.tsx                 # US1/US2/US3: the pair, or today's list unchanged
    └── WeeklyReport.test.tsx            # all three stories
```

**Structure decision**: Web application layout, matching the existing repo.

`GameScoringService` is split from `WeeklyReportService` deliberately. "What is this game worth under
this league's scoring" is needed by both sections and by anything later that reads a game — a season
best-nights view, an award, a player page. Inlining it would produce the repo's recurring defect, the
one `FootballRules.startingLineup`'s javadoc names: *two implementations of one rule*.

The ingest is a **separate service rather than a step in the existing chain**. `LeagueHistoryIngestService`
now walks transactions (feature 004, T125) because those are week-level data on the same cadence as
weekly points. Per-game stats are not: they are player-level, cost ~331 calls per season, and belong to
no single league. Putting them in the chain would make every routine league ingest hundreds of calls
slower for data most leagues do not use.

## Phase sequencing

Each phase is independently shippable and leaves the app working.

| Phase | Story | New storage | Sports | Depends on |
|---|---|---|---|---|
| 1 | US1 — Best Nights | `V20` table + backfill | basketball | — |
| 2 | US2 — Best Week beside it | none | basketball | US1 |
| 3 | US3 — football untouched, by rule | none | both | — (parallel with US1) |

US3 touches only `SportRules` and the page's branch, so it can be built and tested in parallel with US1;
it is priced last in the spec because it is satisfied by *not* changing football's output.

US2 needs no new storage because US1's table already holds every game — Best Week is a different
aggregation of the same rows.

## Complexity Tracking

No constitutional violations to justify (no constitution). Three deliberate choices worth recording:

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| Rank all games in the week, not the league-counted game (R5) | The league's game-selection rule is not understood, so "the counted game" is a set nobody can explain | Enriching the counted game with a date is far cheaper, but ranks an arbitrary subset and makes Best Nights and Best Week trivially related |
| Store raw stat lines, score at read time (R4) | Two leagues in a sport share every game and differ only in scoring | Precomputing per league duplicates every row and bakes a league setting into a shared cache — the exact argument V15 already makes |
| A separate backfill service, not part of the league ingest chain | ~331 calls per league-season, for data most leagues never read | Adding it to `/ingest/league-history` would silently make every routine ingest hundreds of calls slower |

## Risks

1. **Stat corrections move settled games** (research risk 1). Measured: two rosters differ from Sleeper's
   own season `fpts` by ~25–50 points, consistent with corrections after ingest. Mitigated by making the
   backfill re-runnable per season and refetching the most recent scored week, matching
   `roster_week_points`'s existing discipline.
2. **The per-player endpoint is undocumented** and may change shape (research risk 3). Mitigated the way
   V15 mitigated the same exposure for projections: store what it returns and fail visibly, not silently.
3. **Findings rest on one league-season.** R3 and R6 are measured on `1229352720222134272`. Both must be
   re-asserted against `1141438340626231296` before the backfill is trusted — a task, not an assumption.
4. **Best Week is a number that never decided a matchup.** Jokić's 182.0 week never touched a standing.
   FR-005 makes disclosure a requirement rather than a design nicety, because a large unexplained number
   beside real scoring is exactly how this app would lose a reader's trust.
5. **Several sessions share this tree, DB and server.** Measure current state before each verification.

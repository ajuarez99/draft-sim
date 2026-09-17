# Implementation Plan: A league history worth scrolling

**Branch**: `002-league-history-record-book` | **Date**: 2026-09-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-league-history-record-book/spec.md`

## Summary

Turn `/leagues/:id/history` from a stack of standings tables into a record book: highest and lowest
weekly scores across every ingested season, closest matchups and biggest blowouts, and each
season's end-of-season power rank carried on its own standings row.

The technical approach is decided by a measurement rather than a preference (see
[research.md](./research.md)). All three panels read tables that already exist; none of them needs
a new ingest of scores. But the three differ sharply in what is *stored today*:

| Panel | Source table | Stored today? | Work required |
|---|---|---|---|
| Score extremes | `roster_week_points` | **Complete** — 17/21/24 weeks per played season | Read + render only |
| Season final rank | `power_ranking` | **Almost empty** — 1 of 4 played seasons | Backfill (pure compute over existing rows) |
| Matchup margins | `league_matchup` | **Broken** — one week per past season | Ingest gate fix, then re-ingest |

So the plan is sequenced by that column, not by the spec's story order: US1 ships with zero
backend risk, US2 needs a compute path, US3 needs an ingest repair first. Each is independently
shippable and independently valuable.

The one structural decision: **extend `GET /api/leagues/{sleeperId}/history` rather than add new
endpoints.** That endpoint already walks the league chain (`LeagueRepository#chainBySleeperId`) and
already holds each season's internal `league.id` — which is exactly the key every one of these
panels needs, and exactly what a new endpoint would have to re-derive. The alternative and why it
was rejected is in research.md.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.7 (frontend)

**Primary Dependencies**: Spring Boot 3.5.5 (`starter-web`, `starter-jdbc` — `JdbcClient`, no JPA),
Flyway; React 18.3 + React Router 6.30, Vite 6

**Storage**: PostgreSQL 17. Existing tables only — `roster_week_points`, `league_matchup`,
`power_ranking` / `power_ranking_entry`, `roster_season`, `league`, `manager`. Current migration
head is `V15__player_projection.sql`.

**Testing**: JUnit via `spring-boot-starter-test` (unit tests plus `*IT` integration tests that
require Postgres); Vitest + Testing Library for the frontend

**Target Platform**: Web. Deployed on Railway as split services (see `DEPLOY.md`)

**Project Type**: Web application — `backend/` (Spring Boot) + `web/` (React SPA)

**Performance Goals**: League History must stay a single round trip. The record book adds three
aggregate queries over tables whose largest per-league population measured here is 288 rows
(NBA 2024), so the page load budget is unchanged in practice.

**Constraints**:
- No Sleeper call on page load (FR-002). Sleeper is touched only by the explicit ingest and the
  explicit compute, both user-triggered.
- No hardcoded week counts, season lengths or playoff starts (FR-011) — NFL and NBA leagues in this
  DB run 17 and 24 weeks respectively.
- Backfill and ingest repair must be re-runnable without duplicating or corrupting stored rows.

**Scale/Scope**: One page, one controller method extended, one ingest method repaired, one compute
path added. 5 leagues / 9 league-seasons in the local DB today; a real league chain is ~3–10
seasons.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: NOT ENFORCEABLE — reported, not silently passed.**

`.specify/memory/constitution.md` is the unmodified template. Every principle is a literal
placeholder (`[PRINCIPLE_1_NAME]`, `[PRINCIPLE_1_DESCRIPTION]`, …) and the version line still reads
`[CONSTITUTION_VERSION]`. There are no ratified principles to gate against, so this check cannot
pass or fail on its own terms.

Rather than record a vacuous pass, this plan is gated against the conventions the repository
actually enforces in code review and in `AGENTS.md`, which are observable and were checked:

| Convention (observed in repo) | Where it binds this feature | Status |
|---|---|---|
| Never print an endpoint for the reader to run — ship the button | FR-006's missing-rank affordance; `LeagueHistory.tsx` already carries this lesson in a comment | Honoured by design |
| An empty panel must state its reason, never render blank | FR-010; the existing "No standings for {season} yet" guard | Honoured by design |
| No optional parameter that silently encodes a sport's rule | FR-011; the repo records this bug shipping three times | Honoured by design |
| Verify against live/stored data, don't assert | SC-001/SC-002/SC-003 are all queries | Honoured by design |
| Match layout to the shape of the content | Records are ranked lists, margins are paired cards — two different shapes, deliberately | Honoured by design |

**Action for the user**: running `/speckit-constitution` would make this gate real. This plan does
not block on it.

**Post-design re-check (after Phase 1)**: re-evaluated against the same conventions once
`data-model.md`, `contracts/` and `quickstart.md` were written. No new violations. Two design
choices tightened rather than loosened the table above: the `rankStatus` enum exists specifically so
no rank cell can render an unexplained `—` (FR-005/FR-010), and `quickstart.md` B3 requires the
backfill to be verified *through the page's own button*, so a curl-only verification counts as a
failure of FR-006 rather than a pass. Complexity Tracking remains empty.

No entries in Complexity Tracking — this feature adds no new project, no new persistence layer, no
new endpoint, and no new dependency.

## Project Structure

### Documentation (this feature)

```text
specs/002-league-history-record-book/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output — the measurements that set scope
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output — how to verify each slice
├── contracts/
│   └── league-history-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── api/
│   └── LeagueHistoryController.java      # EXTEND: history() gains records + margins + finalRank
├── engine/
│   ├── PowerRankingService.java          # EXTEND: final-rank-for-a-season compute/backfill
│   └── LeagueRecordService.java          # NEW: score extremes + margin records (pure, over stored rows)
├── ingest/
│   └── LeagueHistoryIngestService.java   # FIX: fixture backfill must not be gated on cached week points
└── store/
    ├── RosterWeekPointsRepository.java   # EXTEND: extremes query across a set of league ids
    ├── LeagueMatchupRepository.java      # EXTEND: weeks missing pairings; paired-with-scores query
    └── PowerRankingRepository.java       # EXTEND: final snapshot lookup per (league, kind)

backend/src/test/java/com/ballknowers/draftsim/
├── engine/LeagueRecordServiceTest.java         # NEW: ties, byes, unowned rosters, empty seasons
├── ingest/LeagueHistoryIngestServiceTest.java  # EXTEND: the skip-gate repair
└── store/LeagueRecordIT.java                   # NEW: aggregates against real Postgres

web/src/
├── api.ts                                # EXTEND: LeagueHistory types gain records/margins/finalRank
├── pages/
│   ├── LeagueHistory.tsx                 # EXTEND: record book + margins + rank column
│   └── LeagueHistory.test.tsx            # NEW: empty-reason states, missing rank, tie rendering
└── styles.css                            # EXTEND: record list + margin card styles
```

**Structure Decision**: The existing `backend/` + `web/` split is kept unchanged. Within the
backend, the record aggregation goes in a **new** `engine/LeagueRecordService` rather than into
`LeagueHistoryController`, because that controller is already ~600 lines carrying five endpoints,
and because the aggregation is pure over stored rows and therefore unit-testable without Postgres.
Repository changes are query additions on the three repositories that already own these tables — no
new repository, since no new table is introduced.

## Implementation Sequence

Three independently shippable slices, ordered by risk rather than by spec priority:

**Slice A — Score extremes (US1, P1).** No migration, no ingest, no Sleeper. Add an extremes query
to `RosterWeekPointsRepository` spanning the chain's league ids, aggregate in `LeagueRecordService`,
attach to `history()`, render two ranked lists. Ships alone and is the MVP.

**Slice B — Season final rank (US2, P2).** Add a "final `COMPUTED_REALIZED` snapshot for this
season" lookup, attach the rank to each standings row, and add a backfill path that computes a
completed season's final rank from `roster_week_points` (complete for every played season). The
frontend must distinguish *in progress* / *never computed* / *computed*, and must not mistake the
week-0 baseline for a final rank — the measured data contains exactly that trap.

**Slice C — Matchup margins (US3, P3).** Repair the ingest gate first (`ingestWeeklyPoints` skips
cached weeks and takes the fixture upsert down with it), re-ingest, verify pairings by query, then
build the margins panel. Do not build the panel before the query proves the pairings landed.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified.

None. No new project, persistence layer, endpoint, or dependency is introduced.

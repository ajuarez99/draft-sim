# Feature Specification: Stop saying basketball has no projection source

**Feature Branch**: `016-honest-nba-projection-message`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "start phase 0 roadmap". That's Phase 0 of
`claude/competitor-gap-roadmap.md`: replace the false "no nba equivalent"
projection message.

## Background

On 2026-10-05, `GET https://api.sleeper.app/projections/nba/2026/{week}` (with
basketball positions) returned 200 with a full per-game stat line for 945–1,172
players in each of weeks 1, 2, 10 and 20 (`company: "rotowire"`). See
`claude/projection-tools.md`. Four places in this repo say the opposite, two of
them to a reader at runtime:

| Where | Reaches a reader? | Says |
|---|---|---|
| `LeagueAnalysisService.java:445-451` (`projections.reason`) | Yes: `GET /api/leagues/{nba id}/analysis`, rendered by `LeagueAnalysis.tsx`'s `NotYet` | "…Sleeper's own weekly points (pts_ppr and friends), which has no nba equivalent." |
| `IngestController.java:142-147` (400 body) | Yes: `POST /api/ingest/projections?sport=nba` | "…have no basketball equivalent." |
| `web/src/destinations.ts:177-181` | No, a comment. It's the stated *reason* Analysis is football-only. | "…has no basketball equivalent." |
| `FootballRulesTest.java:269` | No, a comment | "basketball has no projection source -- true, and still true" |

### Correction to the roadmap (shown, not hidden)

`claude/competitor-gap-roadmap.md` Phase 0 said the message is "a false
statement on a live page". **Overstated.** Analysis is `sports: ['nfl']` in
`destinations.ts`, so no basketball league links to it from the rail, the
switcher or `LeagueHome` (which checks `analysisOffered`). The message only
reaches someone who types `/leagues/{nba id}/analysis` or calls the API. It's
still a false sentence the app will say. It's just not one anybody is likely to
read today. The roadmap gets an amended note saying so.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A basketball reader is told the truth about projections (Priority: P1)

Someone opens the Analysis page for a basketball league directly, or calls the
analysis endpoint. They're told that roster projections aren't wired up for
basketball **yet**, not that no source exists.

**Why this priority**: it's the only story. The project's value is honesty
about what it knows (AGENTS.md), and this sentence is false.

**Independent Test**: call the analysis endpoint for an NBA league and read
`projections.reason`.

**Acceptance Scenarios**:

1. **Given** an ingested NBA league with a regular season left to project,
   **When** `GET /api/leagues/{id}/analysis` is called, **Then**
   `projections.available` is `false` and `projections.reason` says roster
   projections are not wired up for basketball yet. It does not contain
   "no … equivalent" or claim that no source exists.
2. **Given** the same league, **When** `/leagues/{id}/analysis` is opened in the
   browser, **Then** the projection blocks show that same reason.
3. **Given** `POST /api/ingest/projections?sport=nba…`, **When** it's called,
   **Then** it still refuses with 400 (behavior unchanged) and the body says
   basketball projection ingest isn't built yet, not that Sleeper has no
   basketball equivalent.
4. **Given** an NFL league, **When** either endpoint is called, **Then** nothing
   changes.

### Edge Cases

- An NBA league whose regular season is over or has no `playoff_week_start`.
  Those checks run *before* the sport check
  (`LeagueAnalysisService.java:431-439`) and keep their own reasons. Unchanged.
- A future third sport. The reason should name the sport it was asked about
  (it already interpolates `league.sport().code()`), and must not assert
  anything about a sport nobody has measured.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The analysis projection reason for a non-NFL league MUST state
  that roster projections are not wired up for that sport yet, and MUST NOT
  state or imply that no projection source exists for it.
- **FR-002**: The projection-ingest 400 for a non-NFL sport MUST say the same
  thing in its own words, and keep refusing.
- **FR-003**: The reader-facing text MUST stay actionable: no instruction to run
  an endpoint that will refuse. That's the rule `league-analysis.md:204-207`
  already set.
- **FR-004**: Comments that state the false premise (`destinations.ts`,
  `FootballRulesTest.java`) MUST be corrected. The football-only gate stays.
  Its stated reason becomes "not wired up", which is still a sufficient reason.
- **FR-005**: Earlier docs that stated the premise get an "amended" note, not a
  silent edit. That's `claude/league-analysis.md` (non-goal line 252), spec
  004's out-of-scope entry, and the roadmap's Phase 0 framing.
- **FR-006**: No behavior change: no endpoint, record, type, migration, route
  or sport gate changes. Text only.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A backend test asserts the NBA analysis reason contains "not wired
  up" (or equivalent agreed wording) and does not contain "equivalent". The test
  fails on today's code.
- **SC-002**: Grepping `backend/src` and `web/src` for "no basketball
  equivalent", "no nba equivalent" and "has no " + sport + " equivalent" finds
  nothing.
- **SC-003**: Live check: the real backend, an ingested NBA league, the
  endpoint response, and the browser page all show the new reason.
- **SC-004**: The full backend suite passes, with the skip count reported
  (memory: the suite skips ITs silently when Postgres is down).

## Assumptions

- Wiring NBA projections for real is Phase 3 (`claude/projection-tools.md`),
  not this feature.
- Wording is a judgment call. The plan proposes exact strings in
  `contracts/messages.md` for review.

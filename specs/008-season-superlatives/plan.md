# Implementation Plan: Season superlatives, so far

**Branch**: `008-season-superlatives` | **Date**: 2026-09-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-season-superlatives/spec.md`

## Summary

A new **Superlatives** page (`/leagues/:id/superlatives`, rail entry after "Weekly report") showing
twelve season-to-date superlatives for the league's regular season.

- Every superlative carries its team(s), exact figure, weeks, coverage and an early-season caveat
  where the spec requires one.
- One backend service, `SeasonSuperlativesService`, composes numbers the app already computes and
  adds three genuinely new measures:
  - **Extremes** (highest/lowest week, blowout, closest game) come from `LeagueRecordService`
    bounded to one season's regular season.
  - **Luck** comes from `ExpectedWinsService` with the same bound.
  - **Bench points** come from `RealizedLineupService.bestLineup`, the call the Weekly Report uses.
  - **New**: close-game records per team, **Waiver Wire Warrior** (starting-lineup points from
    players whose latest arrival was a waiver or free-agent add), and the **Joel Embiid Award**
    (fantasy weeks a regular played no game while his real team did).
  - **New**: the **Unethical Award** (suspensions captured from Sleeper from ship date, plus a
    commissioner-kept list).
- New stored data is limited to what can't be derived: missed games (currently fetched and
  discarded), weekly suspension captures, and the commissioner's list. One migration, V22.

Research changed three things the spec assumed (spec.md "Amended after planning"):
1. **Football can measure missed games.** Sleeper marks a DNP week distinctly from a bye.
2. **The close-game margin is per sport because the margin distributions differ.** Score totals
   aren't the reason; the spreads are nearly equal.
3. **The page is its own route**, not a section of League analysis.

**Decisions from Allan (2026-09-23)**:
- **Regular-season bound approved** (research R3). The existing Expected wins table almost certainly
  counts playoff games (inferred from Sleeper data and the ingest code; the database hasn't been
  checked). Both it and the superlatives are bounded to the regular season, which changes the
  Expected wins page's numbers for every completed season. That was accepted.
- **Embiid counts every missed game** (research R10, amended): it isn't limited to weeks with no
  game played, because a manager wants the player for every game.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads), TypeScript (React + Vite, strict)

**Primary Dependencies**: existing only. Spring JDBC `JdbcClient`, Flyway, `RestClient` to Sleeper.
Nothing new.

**Storage**: PostgreSQL. Migration `V22__season_superlatives.sql` adds `player_absence`,
`status_capture`, `player_suspension` and `league_conduct_entry` (see [data-model.md](data-model.md)).

**Testing**:
- JUnit plus the Postgres-backed integration tests (read the skip count, per the memory "backend
  suite skips ITs silently").
- Vitest for the page.
- Live verification per [quickstart.md](quickstart.md).

**Target Platform**: the existing web app. Local dev on Windows, prod on Railway (ballknowers.co).

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**: the superlatives endpoint returns in about the time Expected wins plus one
Weekly Report takes today. It's computed per request over one league-season, ≤ 18 weeks × ≤ 14
rosters. This is a guess, not measured; measure it in verification and report the number.

**Constraints**:
- No sport-name comparisons outside `SportRules`.
- No defaulted rule parameters.
- No second implementation of any figure another page shows.
- `api.ts` mirrors the Java records in the same change.

**Scale/Scope**:
- 1 page, 3 endpoints (1 read, and 1 read + 2 writes for the list), 1 new service.
- 3 changed ingest/engine classes, 3 new `SportRules` methods, 1 migration.
- Player-games ingest now also runs for football leagues: ~250–330 Sleeper calls per league-season,
  estimated from spec 005's basketball timing.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: NOT APPLICABLE — no ratified constitution.** `.specify/memory/constitution.md` is the
unmodified Spec Kit template. As in specs 004 and 005, the plan is checked against the conventions
this repo actually enforces:

| Convention | Source | How this plan satisfies it |
|---|---|---|
| One declaration per rule | memory: multi-sport landmines ("two implementations of one rule") | Played, suspended and close-game margin are each one non-defaulted `SportRules` method (R5, R9, R11). `canCommission`/`visibleLeague` move to `LeagueMembership` instead of being copied (R12) |
| Rule parameters are never defaulted | memory: optional params that encode rules | The new week ceiling on `LeagueRecordService`/`ExpectedWinsService` is explicit at every call site, including the record book's "all weeks" (R4) |
| No second computation of a shown figure | spec FR-004; memory: count and label, one source | Luck read from `ExpectedWinsService`, extremes from `LeagueRecordService`, bench from `bestLineup` (R3, R4, R7) |
| Honest refusal over a confident wrong number | `PowerRankingService.realizedGap`; spec 005 `SectionUnavailable` | Every kind is always present, as available, empty-with-reason or unavailable-with-reason; unclassified football weeks are reported, not guessed (R9) |
| Hand-set constants labelled arbitrary | AGENTS.md | Margins 10/15, early threshold 4, regular-contributor ½ and 2 weeks, reason ≤ 140 chars, each javadoc'd with where it came from (R5, R6, R10) |
| Migrations append-only | AGENTS.md hard rule | One new V22; no existing migration touched |
| `api.ts` mirrors Java records | AGENTS.md hard rule | Same change; component test asserts coverage renders (R15) |
| `Map.of` throws on null | AGENTS.md hard rule | Payload has many legitimately-null fields (`reason`, `coverage`, `gamesMissed`, `regularSeasonEnd`); build response maps mutably, as `ExpectedWinsController` does |
| Real-browser check for new writes | lessons #6 | The conduct list's POST/DELETE are driven from the page (quickstart §4) |
| Verified vs assumed kept apart | AGENTS.md | research.md tags every entry measured/read/decided; quickstart §1 lists the measurements still owed |

**Post-Phase 1 re-check**: passes. The design adds three per-sport rules, all inside `SportRules`.
Two shared services each gain one explicit parameter, and neither gets a copy. The only behaviour
change to an existing page is R3's, and it's surfaced as a decision rather than slipped in.

## Project Structure

### Documentation (this feature)

```text
specs/008-season-superlatives/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R15
├── data-model.md        # V22 tables + computed payload
├── quickstart.md        # live verification guide, incl. measurements still owed
├── contracts/
│   └── superlatives-api.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
└── V22__season_superlatives.sql                 # new

backend/src/main/java/com/ballknowers/draftsim/
├── sport/
│   ├── SportRules.java                          # + playedIn, isSuspended, closeGameMargin (no defaults)
│   ├── FootballRules.java                       # gp > 0; injury_status Sus; 10.0
│   └── BasketballRules.java                     # stats non-empty; status SUS; 15.0
├── ingest/
│   ├── PlayerGameIngestService.java             # played via SportRules; writes player_absence; team-week set for football None weeks
│   └── PlayerIngestService.java                 # + status_capture / player_suspension after upsert
├── store/
│   ├── LeagueMembership.java                    # + visibleLeague, canCommission (moved, not copied)
│   ├── PlayerAbsenceRepository.java             # new
│   ├── StatusCaptureRepository.java             # new (captures + suspensions)
│   ├── LeagueConductRepository.java             # new
│   ├── RosterWeekPointsRepository.java          # extremes(...) takes an explicit week ceiling
│   └── LeagueMatchupRepository.java             # pairedWithScores(...) week ceiling, if the margin query lives here
├── engine/
│   ├── SeasonSuperlativesService.java           # new: composes the twelve kinds
│   ├── ExpectedWinsService.java                 # explicit regular-season bound (R3; approved)
│   └── LeagueRecordService.java                 # explicit week ceiling threaded through
└── api/
    ├── SuperlativesController.java              # new: GET superlatives, GET/POST/DELETE conduct-list
    ├── ExpectedWinsController.java              # passes the bound
    └── LeagueHistoryController.java             # calls LeagueMembership for visibleLeague/canCommission; record book passes "all weeks"

backend/src/test/java/com/ballknowers/draftsim/...
├── engine/SeasonSuperlativesServiceTest.java    # ordering tests: most close wins wins, luckiest = max WAE, ties name all
├── engine/WaiverWarriorRuleTest.java            # drafted / waiver / FA / traded-after-add / re-add / commissioner
├── engine/AbsenceCostTest.java                  # stash never counts; each missed night costs, even in a week he played
├── ingest/PlayerGameIngestServiceTest.java      # Embiid-shaped NBA entries, McCaffrey/Mahomes-shaped NFL entries (fixtures from research R9)
└── api/SuperlativesControllerIT.java            # scoping 404, commissioner 403, cross-league entry id 404

web/src/
├── api.ts                                       # + Superlatives*, ConductList* types (mirror Java)
├── destinations.ts                              # + "Superlatives" after "Weekly report"
├── App.tsx                                      # + route
└── pages/
    ├── Superlatives.tsx                         # new
    └── Superlatives.test.tsx                    # new
```

**Structure Decision**: the existing web-application layout. One page per analytic (R1), one
controller per page, and engine services that compose existing ones rather than re-querying.

### Build order (for /speckit-tasks)

1. **Foundation**: migration V22, the three `SportRules` methods, and the `LeagueMembership` move.
   The move is behaviour-preserving; the ballot tests must stay green.
2. **US1 (P1, MVP)**: week ceiling on `LeagueRecordService`; close-game records; service and
   endpoint with only US1 kinds live, the rest `available: false, reason: "not built yet"`; page.
3. **US2**: R3 bound on `ExpectedWinsService` (approved), then luck and bench kinds.
4. **US3**: waiver warrior rule and tests.
5. **US4**: player-game ingest changes (`playedIn`, `player_absence`, football team-week set), then
   the cost rule.
6. **US6**: suspension capture in player ingest; conduct list endpoints and UI; the award.
7. **Verification**: quickstart end to end, including §1's owed measurements.

US5 (trophy case) isn't built (research R13).

## Complexity Tracking

No convention violated. Two costs worth naming:

| Cost | Why accepted | Simpler alternative rejected because |
|---|---|---|
| Changing `ExpectedWinsService`'s output for completed seasons | FR-004 requires one shared figure, and a playoff-inclusive "schedule luck" isn't the league's schedule | A private bounded copy for superlatives is the two-implementations bug this repo has shipped before |
| Football player-games ingest (~1.5 min per league-season, estimated) | The only per-player source that distinguishes DNP from bye (measured, R9) | Inferring absences from zero weekly points can't tell a bye, a DNP and a real 0.0 apart |

## Handoff notes

- **Not run**: nothing in this plan was executed against the app's database; it wasn't running on
  2026-09-22. Every Sleeper measurement above was run live, on that date.
- **Out of scope, worth its own look**: `ExpectedWinsController` doesn't check league membership
  (research R14). Read in code, not tested.
- **Commits**: spec and plan committed on 2026-09-23 with Allan's go-ahead.

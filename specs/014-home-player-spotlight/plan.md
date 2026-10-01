# Implementation Plan: Player spotlight on the league home

**Branch**: `014-home-player-spotlight` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-home-player-spotlight/spec.md`

## Summary

Give the league home three player sections: **Top players of the night** (basketball) or **of the week**
(football), **Trending** (Sleeper's most-added, with what each player actually scored), and **Rookie
watch**. Every section is scored under the viewing league's own settings.

Most of this is a new **reading** of stored data, not new data. Measured in [research.md](research.md):
- R1: per-game stat lines already exist for **every** player in both sports.
- R2: scoring those lines reproduces Sleeper's own football points exactly. That is 1,686 player-weeks,
  0 mismatches. It matters because most trending and rookie players are unrostered, so Sleeper's own
  figure doesn't exist for them.
- R9: football's top-players list already exists in a payload the home already fetches.

The one genuinely new input is **trending**: one new table, filled by a best-effort, sport-wide step of the
existing automatic refresh.

Three findings shape the design more than the feature list does:
- **R6**: "the latest date with stored games" is *not* "a completed night". Rows for a US evening slate
  become storable at 8 pm Eastern.
- **R4**: rookie status is today's value, so the spotlight applies only to the current season (R8).
- **R3**: football byes are not stored, so "bye" is inferred only where it can be shown true. Otherwise
  the entry says "did not play".

NBA 2026 has not started (tip-off 2026-10-20). **The basketball sections ship into their pre-season
state**, and the first real-night check (quickstart V6) is owed after 2026-10-21.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5 backend, virtual threads), TypeScript + React (Vite)

**Primary Dependencies**: Spring Boot, JDBC + Flyway, Postgres; React, React Router, Vite, Vitest +
Testing Library. No new dependencies.

**Storage**: PostgreSQL. **New**: `V26__sport_trending.sql`, with `sport_trending_fetch` (one row per sport)
and `sport_trending` (the ranked list) — [data-model.md](data-model.md). **Read, unchanged**:
`player_game`, `sport_week_stats`, `player`, `league`, `roster_week_points`, `roster_season`,
`league_member`, `manager`.

**Testing**: JUnit unit tests (ranking, period selection, bye inference, the no-zero rule) and
Postgres-backed integration tests (the V26 write path, the endpoint). Vitest for the home's sections. Known
trap: the backend suite prints `BUILD SUCCESSFUL` with ITs **skipped** when Postgres is down. Read the skip
count.

**Target Platform**: Web. Railway (ballknowers.co); the backend and web services deploy separately, so the
web change must tolerate a backend without the endpoint (a 404 there renders as nothing, not an error).

**Project Type**: Web application (backend + web).

**Performance Goals**: the spotlight endpoint reads one season's rows for one night or week (around 1–2k
rows for an NFL week, around 250 for an NBA night) and is not on the critical path of the existing home
blocks (SC-007).

**Constraints**: no Sleeper call at request time (FR-016). Membership-scoped (404 for non-members). Never
`Map.of` with a nullable value. `api.ts` types mirror the Java records field for field, in the same change.

**Scale/Scope**: ~10 leagues, 2 sports, around 12 concurrent users at peak (spec multi-user audit). One new
endpoint, one refresh step, one migration, and one home-page section group.

## Constitution Check

`.specify/memory/constitution.md` is an **unfilled template**: there are no ratified principles to gate
on. The binding rules are `AGENTS.md`'s hard rules and conventions, checked here instead:

| Rule (AGENTS.md) | Status |
|---|---|
| Migrations append-only; next is V(n+1) | ✅ V26, new file; re-check at merge (R12) |
| `api.ts` mirrors Java records in the same change | ✅ contract names the type; a task pairs them |
| `Map.of` throws on null | ✅ ownership/opponent are nullable, so mutable maps are mandated in data-model |
| Don't present guesses as measurements | ✅ the R5 cadence and the R6 cutoff are labelled *assumed*; V6 owed |
| Surface uncertainty, don't drop it | ✅ `stale`, `laterNightInProgress`, `omittedUnknownPlayers`, per-section `unavailable` reasons |
| Scoring/ranking changes need a preference-ordering test (lesson 1) | ✅ required for top-of-night, rookie and week ordering |
| Run the SQL (lesson 2) and the real JDBC binds (lesson 3) | ✅ ITs on the real write and read paths |
| Feature-sized work follows plan → adversarial review → build (Sonnet) → bug-hunt review → live verification | ⏭ next stages; this is the plan |
| Ask before committing | ✅ nothing committed |

**Post-design re-check**: unchanged. The design adds no violation; there is nothing to put in Complexity
Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/014-home-player-spotlight/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R12, measured vs assumed
├── data-model.md
├── quickstart.md        # V1–V6 + browser pass
├── contracts/
│   └── player-spotlight-api.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
└── V26__sport_trending.sql                         # new

backend/src/main/java/com/ballknowers/draftsim/
├── ingest/SleeperClient.java                       # + trendingAdds(sport, lookbackHours, limit)
├── store/SportTrendingRepository.java              # new: replace-in-one-transaction, read, record failure
├── refresh/TrendingRefresh.java                    # new: sport-wide, best-effort step, STALE_AFTER-gated
├── refresh/LeagueRefreshService.java               # call TrendingRefresh beside refreshSportSeason; never fails the chain
├── refresh/DailyRefreshService.java                # same step on the daily route
├── engine/WeeklyReportService.java                 # extract the default-week rule into a shared static (R9)
├── engine/PlayerSpotlightService.java              # new: period, sections, ownership, outcomes
└── api/PlayerSpotlightController.java              # new: GET /api/leagues/{id}/player-spotlight, membership-scoped

backend/src/test/java/com/ballknowers/draftsim/
├── engine/PlayerSpotlightServiceTest.java          # ordering, no-zero, bye inference, night completeness, past season
├── engine/NoSportNameInPlayerSpotlightTest.java    # mirrors NoSportNameInWeeklyReportTest (FR-013)
├── store/SportTrendingRepositoryIT.java            # the real V26 write/read path, timestamptz/date binds
└── api/PlayerSpotlightControllerIT.java            # scoping 404s, shape, week == weekly-report week

web/src/
├── api.ts                                          # + PlayerSpotlight types + getPlayerSpotlight()
├── pages/LeagueHome.tsx                            # + one useBlock for the spotlight; football top list from `weekly`
├── components/PlayerSpotlight.tsx                  # new: the three sections (reuses PlayerFace, Avatar)
└── components/PlayerSpotlight.test.tsx             # empty states, did-not-play wording, isMe mark, labels
```

**Structure Decision**: the existing backend/web split. The spotlight is one new service and endpoint
beside `WeeklyReportService`, which it borrows from (scoring, ranking tiebreak, owner naming, default
week). It does not fold into it: the Weekly Report is a per-week page, and the spotlight is "now".
Trending storage sits under `store/` and its fetch under `refresh/`, because spec 009 put all
upstream-fetch timing there.

## Design notes the tasks must carry

1. **One week rule, two callers.** Extract `defaultWeek(ScoredWeeks.Snapshot)` from
   `WeeklyReportService.forWeek`. The spotlight calls it, and an IT asserts
   `spotlight.period.week == weeklyReport(0).week`.
2. **Night completeness** (R6) is a named constant `NIGHT_COMPLETE_HOUR_UTC = 10`, labelled an assumption
   in its Javadoc with a pointer to quickstart V6.
3. **Outcome, not zero.** `TrendingEntry.outcome` is an enum. `points` is serialized only for `PLAYED`.
   A unit test asserts no non-`PLAYED` entry carries `points`.
4. **Bye inference** (R3) needs the player's *current* team and the period's opponent set. If the team
   is null, the outcome is `DID_NOT_PLAY`, never `BYE`.
5. **Sport routing** goes through `SportRules.playsMultipleGamesPerScoringPeriod()` only: NIGHT period
   and `topOfNight` for multi-game sports, WEEK period for the rest. A source-scan test forbids sport
   names in the service and component, like spec 005's.
6. **Home integration**: one `useBlock(getPlayerSpotlight)`, keyed on `[id, version]` so a finished
   refresh refetches it. For football, the top list renders `weekly.data.topPerformers`. While
   `weekly` loads it shows its own skeleton, and on error its own message. `applies: false` or a 404
   renders nothing.
7. **Sleeper credit** on the Trending section ("Trending data from Sleeper"), plus a plain note that
   counts are adds across all Sleeper leagues, not this one.
8. **Visual rules** (memory: label the axis, avoid flat uniform cards): exact points beside each name,
   one encoding per mark, headshots via `PlayerFace`, a tinted "yours" mark rather than a color-only
   cue, and a list layout matched to the content, not identical cards.

## Spec amendments made during planning (shown, not hidden)

- **US4 / FR-008**: the football top list is **starters only**, because it is the Weekly Report's own list
  (research R9). The spec said "rostered players". Matching the Weekly Report wins.
- **New FR-017**: the Trending section credits Sleeper, which Sleeper's API documentation requests
  (R5).
- **Edge cases**: "the most recent night" now carries the completeness rule (R6), and "bye" is shown
  only when inferable (R3).
- **Assumptions**: ownership comes from the latest stored week rather than a live roster call (R10). The
  spotlight applies to the current season only (R8).

## Complexity Tracking

None. No gate violations.

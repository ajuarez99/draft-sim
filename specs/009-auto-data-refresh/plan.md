# Implementation Plan: Automatic data refresh

**Branch**: `009-auto-data-refresh` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-auto-data-refresh/spec.md`

## Summary

The app refreshes its own data from Sleeper, so no page ever tells a manager to run an ingest.
There are two mechanisms, both chosen to keep Railway's serverless mode, which puts current usage
at about $1.20 a month:

1. **Refresh on visit.**
   - The site-wide rail already knows the league of every league page. When the league changes it
     calls `POST /api/leagues/{id}/refresh`.
   - The backend starts a background refresh of each stale season in the league's chain (one at a
     time per season, and a completed, fully loaded season never again) and returns immediately.
   - The rail shows "Updating…". When the refresh finishes, it bumps a per-league data version
     that each page's fetch effect depends on, so the page refetches without a reload.
2. **A daily GitHub Actions workflow** calls `POST /api/refresh/daily` with its own secret. That
   refreshes the only data Sleeper doesn't keep: the player list (once a day, which also captures
   suspensions), ADP, and the board built from them.

**Research changed the plan in one big way (R6).** Sleeper's per-game data comes one player at a
time, and each call returns that player's whole season. So the spec's "fetch only new games" can't
reduce calls. But Sleeper also has a **per-week** endpoint (measured):
- It returns the same entries for every player in the sport, and the season schedule supplies
  home/away and byes.
- Per-game ingest is rebuilt on those two endpoints: about 23 calls per sport-season, shared by
  every league, instead of 331 per league.
- Refreshing an active season costs the latest one or two weeks.
- The rebuild has a hard **parity gate**: identical rows for rostered players, and spec 008's
  verified Embiid figures unchanged.

Also in scope:
- Plain wording for the 9 backend strings that name endpoints, plus a guard test that keeps them
  out.
- One migration, V24.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads), TypeScript (React + Vite,
strict). GitHub Actions YAML for the workflow.

**Primary Dependencies**: existing only. `JdbcClient`, Flyway and `RestClient` to Sleeper.
`java.util.concurrent` for single-flight. `curl` in the workflow. Nothing new.

**Storage**: PostgreSQL. `V24__data_refresh.sql` adds `league_refresh`, `sport_week_stats` and
`daily_capture` ([data-model.md](data-model.md)). `player_game` grows from rostered-only to every
player in the sport: about 26 MB per NBA season, an estimate to be measured.

**Testing**:
- **JUnit** for the pure rules: staleness, finality, the once-a-day skip and single-flight.
- **Postgres-backed ITs** for the refresh state and the daily endpoint's 401/404. Read the skip
  count, which must be 0.
- **Vitest** for the rail indicator and the data-version refetch.
- **The per-game parity diff** (quickstart §2), the gate for replacing the ingest.
- **Live verification** per [quickstart.md](quickstart.md), including production after deploy.

**Target Platform**: the existing web app. Local dev on Windows, prod on Railway (backend
`api.ballknowers.co`, serverless on), with a GitHub-hosted runner for the daily job.

**Project Type**: web application (`backend/` + `web/`), plus one workflow file.

**Performance Goals** (from the spec):
- A stale page shows fresh data within 30 s (SC-003).
- An up-to-date per-game refresh takes under 10% of 101 s (SC-004).
- The month's compute rises by under $1 (SC-005).

**Constraints**:
- **Nothing inside the app runs on a timer** (FR-013), so the backend can still sleep.
- Sleeper: under 1,000 calls a minute, and the full player list at most once a day.
- One implementation per rule: the per-game ingest is replaced, not duplicated.
- `api.ts` mirrors the Java records in the same change.
- No `Map.of` with nullable values on response paths.

**Scale/Scope**:
- **Backend**: 3 new endpoints (visit refresh POST and GET, the daily POST), 1 new refresh service,
  1 rebuilt ingest service, 1 migration, 9 string edits and 1 guard test.
- **Web**: 1 rail indicator, 1 context, and a one-line change on each of 8 league pages.
- **Workflow**: 1 file.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: NOT APPLICABLE — no ratified constitution.** `.specify/memory/constitution.md` is still
the Spec Kit template. As in specs 004–008, the plan is checked against the conventions this repo
enforces:

| Convention | Source | How this plan satisfies it |
|---|---|---|
| One implementation per rule | memory: multi-sport landmines | The per-game ingest is **replaced** behind the same manual endpoint (R9). The refresh reuses `ingestChain`, which already includes transactions (R2), rather than adding a second transaction walk |
| No defaulted rule parameters | memory: optional params that encode rules | Staleness (1 h), retry-after-failure (10 min), week finality (48 h) and concurrency (2) are each one named, javadoc'd constant marked arbitrary. None is a method default |
| Honest refusal over a confident wrong number | spec 008; AGENTS.md | A failed refresh keeps stored data with its age (FR-006). Backend strings say what's missing, never "updating" (contract); only the rail, which knows, says that |
| Verified vs assumed kept apart | AGENTS.md | research.md tags each entry measured, read or decided. ADP and board costs, cold-start time and storage growth are listed as owed measurements (quickstart §1, §2, §6) |
| Hand-set constants labelled arbitrary | AGENTS.md | As above, plus the 11:00 UTC schedule |
| Migrations append-only | AGENTS.md | New V24 only |
| `api.ts` mirrors Java | AGENTS.md | Same change; the refresh status type is new |
| `Map.of` throws on null | AGENTS.md | `lastSuccessAt`, `lastFailureAt` and step `detail` are nullable, so maps are built mutably |
| Real-browser check for new calls | lessons #6 | The rail's POST from a real browser (CORS preflight) is in quickstart §3 |
| Don't retune to match a guess | AGENTS.md | The parity gate reports differences for a decision. It doesn't adjust the ingest until the diff is empty |

**Post-Phase 1 re-check**: passes. The one existing behaviour that changes is `player_game` storing
every player, and it's gated on parity and reader checks (R6). The ingest endpoint's behaviour is
kept for development (FR-015).

## Project Structure

### Documentation (this feature)

```text
specs/009-auto-data-refresh/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R13
├── data-model.md        # V24 tables, per-game row changes, refresh state machine
├── quickstart.md        # live verification, incl. the parity gate and production checks
├── contracts/
│   └── refresh-api.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code (repository root)

```text
.github/workflows/
└── daily-refresh.yml                            # new: cron 0 11 * * *, workflow_dispatch, one curl with retries

backend/src/main/resources/db/migration/
└── V24__data_refresh.sql                        # new: league_refresh, sport_week_stats, daily_capture

backend/src/main/java/com/ballknowers/draftsim/
├── refresh/                                     # new package
│   ├── RefreshProperties.java                   # on-visit enabled flag, secret; constants documented arbitrary
│   ├── LeagueRefreshService.java                # stale check, chain seasons, single-flight, concurrency cap, state writes
│   ├── DailyRefreshService.java                 # players / ADP / board per sport, once-a-day skip
│   └── RefreshController.java                   # POST+GET /api/leagues/{id}/refresh, POST /api/refresh/daily
├── store/
│   ├── LeagueRefreshRepository.java             # new
│   ├── SportWeekStatsRepository.java            # new
│   └── DailyCaptureRepository.java              # new
├── ingest/
│   ├── PlayerGameIngestService.java             # rebuilt: per-week stats + schedule (R6); same manual endpoint
│   └── SleeperPlayerStatsClient.java            # + week(sport, season, week), schedule(sport, season)
├── api/
│   ├── ApiTokenFilter.java                      # exempt /api/refresh/daily (R8)
│   └── LeagueController.java                    # string edit (R10)
└── engine/
    ├── LeagueAnalysisService.java               # string edits
    ├── PowerRankingService.java                 # string edit
    └── SeasonSuperlativesService.java           # string edits

backend/src/test/java/com/ballknowers/draftsim/
├── refresh/LeagueRefreshRulesTest.java          # stale / complete / retry-after-failure / chain seasons
├── refresh/SingleFlightTest.java                # concurrent triggers → one run; cap of 2
├── refresh/DailyRefreshServiceTest.java         # once-a-day skip; one step failing doesn't stop the others; 500 on any failure
├── refresh/RefreshControllerIT.java             # 404 no secret, 401 wrong secret, exempt from API_TOKEN, membership 404
├── ingest/PlayerGameWeekIngestTest.java         # fixtures cut from the measured week/schedule payloads (R6)
└── api/NoIngestHintsInMessagesTest.java         # guard: no /api/ingest in string literals

web/src/
├── api.ts                                       # + refreshLeague, getLeagueRefresh, RefreshStatus type
├── leagueDataVersion.tsx                        # new: context + useLeagueDataVersion
├── components/LeagueRailSection.tsx             # trigger on league change, poll while RUNNING, indicator
└── pages/{LeagueHistory,PowerRankings,LeagueAnalysis,RosterManagement,
          ExpectedWins,SeasonForecast,WeeklyReport,Superlatives}.tsx
                                                 # + useLeagueDataVersion in each fetch effect's deps
```

**Structure Decision**:
- The existing web-application layout.
- A new `refresh/` package, because the refresh orchestrates several existing ingest services and
  belongs to none of them.
- The rail is the trigger point (R1), not the pages.

### Build order (for /speckit-tasks)

1. **Capture first** (quickstart §1): the per-game exports, Superlatives payloads and ADP/board
   costs. These can't be recovered once the ingest is replaced.
2. **Foundation**: V24, the three repositories and `RefreshProperties`.
3. **US5 (per-game rebuild)**:
   - the client's week and schedule calls;
   - the rebuilt ingest;
   - **then the parity gate (quickstart §2) before anything else depends on it**.

   This comes early because refresh on visit calls it, and SC-003 needs it to be cheap.
4. **US1 (refresh on visit)**: service, endpoints, rail trigger and indicator, data-version
   context, the eight pages.
5. **US2 (plain wording)**: the string edits and the guard test. This can run alongside US1.
6. **US4 (new league loads once)**: the setup flow triggers one refresh when it finishes. This is
   mostly US1's chain rule (R2).
7. **US3 (daily job)**: service, endpoint, filter exemption, workflow, secrets. Production checks
   run after deploy.
8. **Verification**: quickstart end to end, then production §6 and §8. Month-later §9 goes in
   HANDOFF.

## Complexity Tracking

No convention violated. Costs worth naming:

| Cost | Why accepted | Simpler alternative rejected because |
|---|---|---|
| Rebuilding the per-game ingest (R6) | Refresh on visit can't meet SC-003/SC-004 with 331 calls per league per refresh, and every new league multiplies that cost | Parallelizing the per-player walk cuts wall time but not calls. A daily-only per-game refresh leaves pages up to a day stale |
| `player_game` stores every player in the sport | The per-week payload contains them all, and it covers a mid-season pickup's earlier games (US5 scenario 3) | Filtering to rostered players would need a refetch whenever a league adds a player |
| In-memory single-flight assumes one backend instance | `railway.toml` sets no replicas (read). One instance is the deployment | A database lock adds complexity for a problem that doesn't exist yet. If replicas are added, overlapping refreshes are wasteful but safe (R11) |
| Eight one-line page edits for the data version | Pages must refetch when a refresh completes (FR-004) | A full-page reload on completion would lose scroll and UI state |

## Handoff notes

- **Measured on 2026-09-28**: the Sleeper payload shapes and sizes (R6), repo visibility (R7), and
  every CPU and cost figure in the spec's Budget.
- **Read, not run**: the ingest internals, the rail and the filter.
- **Owed measurements**: ADP and board cost, cold-start time on Railway, and storage growth under
  per-week ingest (quickstart §1, §2, §6).
- **Not diagnosed**: why production NBA 2025 transactions are partial. The fix doesn't depend on
  knowing (R3).
- **Secrets to set at deploy**: `REFRESH_SECRET` on the Railway backend service and as a GitHub
  repository secret. The workflow does nothing useful until both exist.

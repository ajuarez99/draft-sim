# Implementation Plan: FantasyPros design and feature borrowings

**Branch**: `042-fantasypros-design-features` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/042-fantasypros-design-features/spec.md` (US1–US3 only, per Clarifications)

## Summary

Three in-season borrowings from FantasyPros, in both sports wherever the page exists:

1. **US1, ownership marks (much smaller than the spec assumed).** The code already resolves "yours /
   theirs / free agent" (`engine/PlayerOwnership.java`, spec 022). The stat leaderboard already has an
   Availability filter, and the player page already shows a "Yours" badge. What's left: one shared mark
   component across the three in-scope pages (today there are three different renderings), a
   **"My roster"** shortcut, the filter in the URL so it survives a reload, and `isMe` on Trends rows,
   which today show only the owner's name.
2. **US2, player page header + tabs.** Add injury status (with its refresh time) and the next game
   (date, opponent, home/away, from `sport_schedule`) to the header. Split the stacked page into
   URL-addressable tabs. **No tip-off time:** `sport_schedule.game_date` is a date, and no time is stored.
3. **US3, lineup check on league home.** A new read-only endpoint. It reads the viewer's **live** Sleeper
   roster `starters`, which `LeagueRosterSource.rosters` already fetches and nobody reads, plus
   `player.injury_status` and (NBA) the games left this week from `sport_schedule`.

**No migration.** Everything reads tables and live calls that already exist.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite, strict)

**Primary Dependencies**: existing only. Nothing new.

**Storage**: Postgres. Reads `player`, `status_capture`, `daily_capture`, `sport_schedule`, `roster_season`.
**No new tables or columns.** *(Amended after review 2026-10-20, F11: V38 is on main, since 040 was merged as
PR #38. The branch was fast-forwarded to `origin/main` @ `c987b92`. The next free number is V39, and none is
needed.)*

**Testing**: JUnit (`./gradlew test`, and check the skip count per memory: ITs skip silently without
Postgres), Vitest + Testing Library for the web.

**Target Platform**: the existing web app, desktop 1366×900 and phone 375×812.

**Project Type**: web (backend + web).

**Performance Goals**: The lineup check adds one live Sleeper `/rosters` call per league-home load, the
same call `LeagueAnalysisService` already makes. Its added latency is **not measured; measure it in
verification**.

**Constraints**: honesty rules (AGENTS.md). Every status shows its as-of time, and no system-issued
verdict appears (SC-006).

**Scale/Scope**: 3 pages touched (StatLeaderboard, PlayerTrends, PlayerPage), 1 section added to
LeagueHome, 1 new endpoint, 2 payload extensions.

## Constitution Check

| Rule | How this plan meets it |
|---|---|
| Migrations append-only | None added. |
| `api.ts` mirrors Java records | `PlayerStatsPage` (+`status`, +`nextGame`, +`upcoming`), `TrendRow` (+`rosteredByMe`), the new `LineupCheck`. Each is mirrored in the same task as its Java change (tasks enforce it). |
| Don't retune constants | Nothing is tuned. The flagged-status set is a labelled list in `weights.yml` (see research R3), not a fitted number. |
| `Map.of` throws on null | `injuryStatus`, `nextGame`, `opponent` and `homeAway` are all nullable. They go in records, not `Map.of`. |
| Ask before committing | Holds. The pipeline stops at commit. |
| Roadmap scope isn't a spec | Applied already. US1 shrank after reading `PlayerOwnership` and `FilterBar` (spec amended, see spec "Amended after planning"). |

**Gate: PASS.** Re-checked after Phase 1: still PASS (no migration, no new dependency).

## Project Structure

### Documentation (this feature)

```text
specs/042-fantasypros-design-features/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R7
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── player-page-c1-delta.md
│   ├── trends-delta.md
│   └── lineup-check.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/LineupCheckService.java          # NEW: US3, pure rule + loader split like PlayerOwnership
├── engine/PlayerStatsService.java          # US2: status + nextGame + upcoming on PlayerStatsPage
├── engine/PlayerTrendsService.java         # US1: rosteredByMe on TrendRow
├── engine/NextGames.java                   # NEW: pure "next games for team from sport_schedule" (shared by US2/US3)
├── api/LineupCheckController.java          # NEW: GET /api/leagues/{id}/lineup-check
└── config/weights.yml (resources)          # US3: lineupCheck.flaggedStatuses (labelled)
web/src/
├── api.ts                                  # mirrors
├── components/OwnershipMark.tsx            # NEW: one mark, three pages
├── components/statControls.tsx             # FilterBar: "My roster" + URL state
├── pages/StatLeaderboard.tsx, PlayerTrends.tsx, PlayerPage.tsx
└── components/LineupCheck.tsx              # NEW: league-home section
```

**Structure Decision**: the existing backend/web split. The new logic follows the house pattern of a pure
resolver plus a thin loader (`PlayerOwnership`/`Facts`), so the rule is unit-testable without a database.

## Phases

- **Phase 0**: [research.md](research.md). Every unknown is resolved or turned into a measured build task.
- **Phase 1**: [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

## Amended after review (2026-10-20)

`plan-review.md` (one BLOCKER, ten SHOULD-FIX, NITs) was read, and F1, F2, F3, F4, F5, F7, F9 and F11 were
spot-checked in code before being accepted. All are taken, in place, in research/data-model/contracts:

| Finding | Change |
|---|---|
| F1 BLOCKER | Next game and status come from the URL's league and `player.team`, not `requestedSeason`. Opening-night test added to tasks. |
| F2 | As-of is the greatest of `status_capture` and the daily PLAYERS capture. The second-refresh follow-up needs the skip-if-today gate changed. |
| F3 | Eligibility is `SportRules.isEligible`. `SLOT_UNSUPPORTED` for unknown slots. |
| F4 | One "out" rule: `isUnavailable` or `isSuspended`. The weights.yml list is withdrawn. |
| F5 | `NextGames` reuses `SportSchedule`/`ScheduleGridService` rules. **The lineup check replaces `idleStartersNote` when live starters load**, and the note stays only as the fallback. |
| F6 | Flag "no game in the week", not "no game left". GAMES_LEFT ordering dropped. Lock cadence measured in T001. |
| F7 | Anonymous and strangers get 404. NOT_MEMBER is narrowed. |
| F8 | "Mine" is NextMatchupService's rule (manager id, lowest roster id). |
| F9 | Reuse `own=` (plus `mine`) across the leaderboard and calculator. |
| F10 | One leaderboard read per request. Bench averages carry season and game count. |
| F11 | Fast-forwarded to `origin/main`. |
| F12 | Taxi excluded. LeagueAvailability `lineupCheck` key. 4 new PlayerStatsPage fields. The header reuses `Ranks.positionRank`. Print the as-of time. SC-001 compared at a known as-of. `useBlock` keyed on `version`. FR-003 scope narrowed (spec amended). |

Source tree corrections: `config/weights.yml` is at the repo root and is **not touched** now (F4).
`engine/NextGames.java` becomes `ingest/NextGames.java`, beside `SportSchedule` (F5a).

## Complexity Tracking

None. No constitution violations.

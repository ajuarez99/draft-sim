# Implementation Plan: Player stat analysis, nightly and season-long

**Branch**: `022-player-stat-analysis` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: spec.md, including its three clarifications from 2026-10-07. The work ships as four
chunks: US1, then US2, then US4, then US3. US3 goes last because it waits on an opening-night
measurement (FR-036).

## Summary

The feature adds a league-scoped player page (traditional stats, advanced stats, fantasy figures),
a leaderboard with stat leaders and draft value, and a nightly report. It covers basketball only.

Everything is computed at read time from data already stored:

- Sleeper's per-game rows, including the `TEAM_xxx` total rows;
- the league chain;
- weekly rosters;
- drafts, ADP snapshots and Draft Grades.

There is no new data source and no migration. Basketball Reference is a definitions reference and a
manual cross-check only.

The plan reuses existing code rather than writing parallel versions. Measuring before planning
found four rules the spec would otherwise have duplicated:

- **The game-to-team join** is in `PlayerTrendsService.prepare`: team rows, the All-Star filter,
  and the player's own team row. It is extracted to `NbaGameLines` (research R2).
- **Usage rate** already exists as the Basketball Reference formula. It moves to `AdvancedStats`
  (R3).
- **Fantasy scoring** has one implementation. A `contributions` method is added and `score` is
  rebuilt on it, which gives the breakdown with no second scorer (R4).
- **"How did this pick do"** already exists in Draft Grades. The leaderboard reuses its
  `valueOverSlot` instead of a new rank-vs-pick rule (R9). spec.md FR-034 gets a dated amendment.

The other measurements that shaped the design:

- **A full season is ~0.65 s and 14.6 MB** to read, and nothing is cached today. That leads to a
  compact per-season `SeasonBoxCache` with a validity token (R6).
- **Past ownership.** Past seasons have no season-end rosters, but every week's roster is stored.
  Ownership is therefore stated by week, and never borrowed from today (R8).
- **ADP has no season column.** "Preseason ADP" is the blend as of the season's draft date (R10).
  For NBA 2025 that is nothing, and the page says so.
- **Seasons.** The season picker is the existing league chain, and the resolver gets an explicit
  "has stored games" rule (R7).

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5; TypeScript + React + Vite.

**Primary Dependencies**: existing only.

- **Data**: `PlayerGameRepository`, `RosterSeasonRepository`, `RosterWeekPointsRepository`,
  `DraftRepository`, `BoardRepository`.
- **Engine**: `GameScoringService`, `LeagueSeasonResolver`, `DraftGradesService`, `RosterOwners`.
- **Sport and access**: `SportRulesRegistry` (`BasketballRules.isEligible`),
  `LeagueMembership`.
- **Web**: `destinations.ts`, `PlayerFace`.

**Storage**: none new. There are no migrations, and the highest stays V29.

**Testing**:

- **JUnit**: pure cores over synthetic games, plus real-row fixtures from 2025.
- **ITs**: the repository token query, the controllers in `AccessControlMvcIT`, and the cache
  invalidation.
- **Vitest**: the comparator, column groups and reason sentences.
- **Live quickstart**: V1–V12.

**Target Platform**: Railway (backend and web services deploy separately); browsers at 375 px and
up.

**Performance Goals**: SC-006, with each page usable within 2 s on the deployed site. Estimated:
cache-warm requests are well under that, and a cold load is ~1 s plus parsing. These are
**unmeasured**; V9 measures them.

**Constraints**:

- No second implementation of a rule (R2, R3, R4, R9, R11).
- No defaulted rule parameters: the window is required, and the resolver's rule is explicit.
- Basketball-only gating goes through the sport rules. The client never checks `sport == 'nba'`
  directly, beyond the destination's `sports` list.
- Hand-set numbers are ARBITRARY and live in `weights.yml` (R12).
- Reasons are codes, and the sentences live in the web client.
- Responses are records, not `Map.of`.
- No fetch from Basketball Reference or ESPN.

**Scale/Scope**:

- **Backend**: 5 new engine classes (`NbaGameLines`, `SeasonBoxCache`, `AdvancedStats`,
  `PlayerStatsService`, and `NightlyReportService` in US3), 1 properties record, 2 controllers,
  and changes to Trends, `GameScoringService` and `LeagueSeasonResolver`.
- **Web**: `api.ts`; 3 pages; `PlayerLink`, a shared component; `statLeaderboard.ts`; 2
  destinations, 3 routes, and links in 5 existing pages.
- **Effort**: a guess of 4–6 sessions across the four chunks.

## Constitution Check

The constitution (`.specify/memory/constitution.md`, ratified 2026-10-07) is the six AGENTS.md hard
rules. The AGENTS.md working rules are checked alongside it, as in plans 009–021.

| Gate | Pre-design | Post-design |
|---|---|---|
| Migrations append-only | ✅ No migration | ✅ Unchanged |
| `api.ts` mirrors records | ✅ Planned in the same change | ✅ Contract C1–C3 lists every field, and the nullability is stated |
| Never retune a constant to match a guess | ✅ R12's constants are new and labelled. SC-003 explicitly forbids changing a formula to chase Basketball Reference | ✅ V4 records Gobert's actual rank, not the 10-07 SQL estimate |
| `Map.of` with nulls | ✅ Records throughout; `Rate`, `Pct` and `Ownership` are nullable-by-design records | ✅ |
| Ask before committing | ✅ Own worktree (`draft-sim-022`); nothing committed | ✅ |
| A planning doc's stated difficulty isn't a spec | ✅ The code was read first: four "new" rules turned out to exist (R2–R4, R9) | ✅ |
| Honesty over apparent confidence | ✅ Small-sample labels, stated groups and sizes, ownership by week, ADP composition named, replacement rule labelled a simplification | ✅ |
| Verified vs. assumed | ✅ Every research item is labelled measured, read or decision. The cache's memory and token cost are marked unmeasured | ✅ |
| Two implementations of one rule | ✅ Addressed by extraction (R2, R3, R4) and reuse (R9, R11) | ✅ Invariant I1 guards the scorer refactor |
| Optional params encoding rules | ✅ `window` is required (C2); the resolver rule is an explicit enum (R7) | ✅ |
| Ordering tests for rankings | ✅ I4 and I7 | ✅ |
| Corrections shown | ✅ The FR-034 amendment is dated in spec.md | ✅ |
| Live verification | ✅ quickstart V3–V12, with V5 and V8 in a real browser | ✅ |
| Coding on Sonnet | At implement | |

**Result**: no violations, and no complexity tracking needed.

## Project Structure

### Documentation (this feature)

```text
specs/022-player-stat-analysis/
├── spec.md          # with Clarifications (2026-10-07) and the FR-034 amendment
├── plan.md          # this file
├── research.md      # R1–R15
├── data-model.md    # computed shapes, formulas, invariants I1–I8
├── quickstart.md    # V1–V12
├── contracts/api.md # C1 player page, C2 leaderboard, C3 nightly
├── checklists/requirements.md
└── tasks.md         # next: /speckit-tasks
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/NbaGameLines.java            # NEW: join + All-Star + team rows, moved from PlayerTrendsService.prepare
├── engine/SeasonBoxCache.java          # NEW: compact per-(sport, season) lines, validity token, single flight
├── engine/AdvancedStats.java           # NEW: pure; pooled rates (usage moved here), game score, windows
├── engine/PlayerStatsService.java      # NEW: league layer: fantasy, ranks, percentiles, replacement, ownership, draft/ADP
├── engine/NightlyReportService.java    # NEW (US3): night, completeness, standouts, "mine"
├── engine/GameScoringService.java      # + contributions(); score() rebuilt on it
├── engine/PlayerTrendsService.java     # calls NbaGameLines, AdvancedStats.usage, SeasonBoxCache
├── engine/LeagueSeasonResolver.java    # + resolve(id, Rule) with PLAYED_WEEKS | STORED_GAMES
├── store/PlayerGameRepository.java     # + seasonToken(sport, season)
├── config/PlayerStatsProperties.java   # NEW: draftsim.player-stats (ARBITRARY), registered
├── api/PlayerStatsController.java      # NEW: C1 + C2
├── api/NightlyReportController.java    # NEW (US3): C3
└── api/HealthController.java           # + playerStatsLoaded
config/weights.yml                      # draftsim.player-stats block

web/src/
├── api.ts                              # C1–C3 types
├── destinations.ts                     # 'stats' (season group), 'nightly' (thisWeek), nba only
├── App.tsx                             # /leagues/:id/players/:playerId, /stats, /nightly
├── components/PlayerLink.tsx           # NEW: one link rule; used by 5 pages (FR-014)
├── pages/PlayerPage.tsx                # NEW (US1 + US2)
├── pages/StatLeaderboard.tsx           # NEW (US4): column groups, pinned name, stat leaders
├── pages/NightlyReport.tsx             # NEW (US3)
├── statLeaderboard.ts                  # NEW: the single comparator + column-group definitions
├── statCopy.ts                         # NEW: definitions (FR-017) + reason/rule sentences
└── components/PlayerSpotlight.tsx, pages/{WeeklyReport,PlayerTrends,RosterManagement,LeagueAnalysis}.tsx  # names → PlayerLink
```

**Structure decision**: the existing `backend/` + `web/` layout. Engine classes are pure cores with
a thin `read()` that does the I/O, matching Trends and Draft Grades.

## Build order (four shippable chunks)

Each chunk ends with the repo's pipeline: a bug-hunting code review, live verification, then ask,
merge and deploy.

**0. Foundation** (ships with US1):

1. Extract `NbaGameLines` and `AdvancedStats.usage`, then switch Trends over. V1 must stay green.
2. Add `GameScoringService.contributions`, rebuilt under I1.
3. Add `SeasonBoxCache` and `seasonToken`, then switch Trends to the cache. Run V2.
4. Extend the resolver rule (R7), and add `PlayerStatsProperties` and the health flag.

**1. US1, the player page**:

- C1's identity, season line, game log, fantasy, ranks and breakdown;
- `PlayerPage` and `PlayerLink` in the 5 pages;
- the route and the season picker;
- run V3–V6.

**2. US2, advanced stats**:

- the full `AdvancedStats` rates, windows and percentiles (both groups);
- the Advanced view, with definitions from `statCopy.ts`;
- run V7 and V11.

**3. US4, the leaderboard**:

- C2 with replacement, the draft/ADP/Draft Grades join and ownership;
- `StatLeaderboard` with column groups, the pinned name and the stat leaders;
- the `stats` destination;
- run V8 and V9.

**4. US3, the nightly report**: built after V12 has a measured number.

- `NightlyReportService` and C3;
- `NightlyReport`, with the spotlight's "Top of the night" link and the `nightly` destination;
- run V10.

The adversarial plan review reads this plan cold before step 0, as in specs 017–021.

## Complexity Tracking

No violations.

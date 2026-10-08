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

## Amended after review (2026-10-07)

[plan-review.md](plan-review.md) read the plan cold and re-measured against the DB and code. About
18 claims held. It found 13 findings and 17 notes. The four high findings were re-checked by the
parent session before acceptance:

- **F1**: the NFL 2026 leagues are `in_season` with `complete` drafts.
- **F3**: 2024 has no `sport_schedule` rows; there are three postponed January games and a
  `canceled` STP–STR All-Star game.
- **F4**: `chainBySleeperId` follows `previous_league_id` only.

All 13 findings are accepted. The docs are amended in place, each with a dated note.

| # | Finding | Disposition |
|---|---|---|
| F1 | Draft state keyed on `league.status` | **Fixed.** It reads `draft.status`: `complete` gives `COMPLETE`, any other draft row `NOT_HAPPENED`, and no row `NONE`. This is the field `DraftGradesService.read` already gates on. T041 adds an `in_season` + `complete` case |
| F2 | The current unscored week has no `roster_week_points` row | **Fixed.** A night in the league's current, unscored week uses V28 current rosters (`asOf CURRENT`, same draft gating). A scored week uses its `players_points`. Only weeks after the league's last week are `UNAVAILABLE` |
| F3 | Night completeness duplicated Spotlight's rule, and breaks on postponed games, the All-Star game and 2024 | **Fixed.** Spotlight's night rule (`isComplete`/`choosePeriod`) is extracted and shared. The game list is built from stored team rows, already All-Star filtered. The schedule is used only for `missingGames`, through `ScheduleGridService`'s real-game rule. T054 adds postponed, All-Star and 2024 cases |
| F4 | The picker can't move forward | **Fixed.** `seasons` is built from the chain head: a new successor lookup (`where previous_league_id = ?`) walks forward, then the chain walks back. T023 tests from the 2024 id |
| F5 | "End of regular season, week N" was the wrong week | **Fixed, rule chosen.** A completed season's ownership is week `playoff_week_start − 1` (2025: week 18, 2024: week 21), labelled "end of regular season (week N)". NBA games after the league's last week say no roster covers them. V6 corrected |
| F6 | The fallback season showed March owners right after the draft | **Fixed.** When `requestedSeason != null`, C1 and C2 also carry `currentOwnership` for the requested season, labelled separately. `ownership` stays the shown season's, so I8 holds |
| F7 | The cache switch changed Trends' inputs; `isHome` had no input | **Fixed.** The cache stores raw rows (`SeasonGame` + `TeamGame`, with `isAway` added from `player_game.is_away`, which is 100% populated). `NbaGameLines` is applied over them. Trends' `oneGameShare` keeps the All-Star row in this spec so V1 stays byte-identical; excluding it is a named follow-up. `SeasonGame` gains a field, and the test helpers are updated (not "unchanged") |
| F8 | Cache lock, read order and reloads unspecified | **Fixed.** Uses a lock-free single flight on the `refresh/SingleFlight` pattern (its own instance), never `synchronized` (virtual-thread pinning on 21). The token is read **before** the rows. `PlayerGameIngestService.refreshSportSeason` calls `invalidate(sport, season)` when it finishes, with the token as a safety net, and no reload starts while a refresh of that season is in flight. Cached lists and maps are unmodifiable, with a T013 case |
| F9 | LAST_N windows had no recency | **Fixed.** `WindowStats` carries `firstGameDate`/`lastGameDate`. A player whose last game is more than `recency-days` (new key, 14, ARBITRARY) before the season's latest game is excluded from LAST_N ranks, percentiles and leaders, and labelled "hasn't played since {date}". LAST_N qualification is defined as playing all N games, with the minutes rule. Test with a 60-day-old last game |
| F10 | `missedTeamGames` undefined, and the name collides with Trends' field | **Fixed.** Renamed `teamGamesMissed`. Rule: for each team he played for, that team's games between his first and last game with it, minus the games he played. Traded-player test |
| F11 | League Analysis is NFL-only; the pages had no sport rule or league id | **Fixed.** Superlatives replaces League Analysis in FR-014, SC-001 and V5 (dated amendment). One gate, `playerPagesFor(sport)`, is derived from a `players` destination row with `inRail: false` (N5). `PlayerSpotlight` receives `sleeperLeagueId` |
| F12 | Draft-value and ADP join gaps | **Fixed.** C2 gains `draftGrades {available, reason, gradesEarly, weeksCounted}`. `DraftRow` gains `startTime`, and a null start time gives `NO_DRAFT_DATE`. A new `BoardRepository.latestBefore(sport, source, date)` returns the capture date with its rows, with an IT on 2025 (empty) and 2026 (09-28). Drafting-manager names use Draft Grades' rule. The Draft Grades read is memoised on the season token, and V9 times it |
| F13 | Rank, percentile and contract contradictions | **Fixed.** Ranks are competition ranks on the **wire-rounded** value (2 decimals); games, name and id set display order only. A free agent's `LEAGUE_ROSTERED` percentile ranks his value against the rostered group, so no "not in group" code is needed. Percentiles are per window in C1. `Ownership` carries `rosterId` |

Notes taken:

- **Counts and data**:
  - **N1**: player rows are 26,680 (2025) and 26,335 (2024), corrected in R1.
  - **N2**: store `double`; there are 31 keys per row on average.
  - **N3**: naive left-to-right sum in scoring-key order, and I1 covers NFL 2025 too.
  - **N6**: any empty roster makes the week `UNAVAILABLE`.
  - **N7**: `didNotPlay` comes from game-level `player_absence` `ENTRY_WITHOUT_PLAY` rows, not
    `player.team`.
  - **N8**: every scoring key gets a label, and `share` is null when the season total ≤ 0.
  - **N11**: `start_time` is the scheduled time, stated in R10.
  - **N14**: C2 is ~0.75 MB per window uncompressed; compression is expected and V9 decides it.
  - **N17**: the resolver shares the cache's token.
- **Client and routes**:
  - **N4**: the player route is keyed on both ids.
  - **N5**: the `players` destination row has `inRail: false`.
  - **N15**: the fallback note *is* the "explicitly picked current season" statement; spec edge
    case reworded.
  - **N16**: one line on the fallback note about Trends' different season switch.
- **Codes and tests**:
  - **N9**: `NO_PLAYER_GAMES` for a player with no games in a season that has games.
  - **N10**: opening night is 10-20.
  - **N12**: Trends' usage tests stay in place as its guard, and direct `AdvancedStats.usage` tests
    are added.
  - **N13**: two baselines (2026 and 2025 leagues), with the time-varying fields stripped.

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

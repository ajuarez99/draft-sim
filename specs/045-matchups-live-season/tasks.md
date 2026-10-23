---
description: "Tasks for spec 045: Matchups shows the live season, then lock-ins on Matchups"
---

# Tasks: Matchups shows the live season, then lock-ins on Matchups

**Input**: `specs/045-matchups-live-season/`: spec.md, plan.md (its "Amended after plan review" section
wins over "Design"), research.md, data-model.md, contracts/, quickstart.md, plan-review.md

**Tests**: required. FR-008 asks for a pinned opening-week test, A2/A4/A10 name specific tests, and
SC-003/SC-004 need a before-and-after capture.

**Paths**: `B` = `backend/src/main/java/com/ballknowers/draftsim`, `BT` =
`backend/src/test/java/com/ballknowers/draftsim`, `W` = `web/src`. Everything is relative to the worktree
`.claude/worktrees/045-matchups-live-season`.

**Shared server/DB warning**: other sessions share Postgres 5433 and run a server on 8080. Never stop
8080. Use port **8082** for this spec's servers, and pass `--refresh.on-visit.enabled=false`.

## Phase 1: Setup (baseline before any code changes)

- [X] T001 Write `scripts/045/capture.sh <base-url> <out-dir>` (plan A15). It curls with
  `X-Sleeper-User` = a real member of each league (read one from `league_member`/`manager` in the local DB)
  and writes one `jq -S` file per endpoint:
  - `weekly-report/{1..N}` for every stored week of NBA 2024 (`1141438340626231296`), NBA 2025
    (`1229352720222134272`), NFL 2025 (`1254190892974084096`, `1262506916429430784`) and NFL 2026 (`1389361939561332736`,
    `1346366555759341568`, `1391509063170293760`)
  - `weekly-report/0` for each of those
  - Expected wins, Playoff odds, Roster management, Superlatives, refresh status and recap view for
    the NBA 2026 league and NFL 2026 `1391509063170293760` (grep the controllers in `B/api` and `B/recap`
    for the exact routes)
  - `/lock-ins` and `/lock-ins?player=<a 2025 locked player id>` on NBA 2025
  - Also add `scripts/045/diff.sh <a> <b>`, which ignores exactly `live`, `liveWeek` and `sleeperLeagueId`
    on `weekly-report*` files, and nothing elsewhere.
- [X] T002 Baseline. Build this worktree **before any code change** (it's `origin/main` 5512cd7) and run
  it on 8082 with refresh off. Run `capture.sh` into `specs/045-matchups-live-season/baseline/run1`.
  Restart the JVM, capture again into `run2`, then diff run1 against run2, and record which keys are
  nondeterministic in `baseline/README.md`. Stop the 8082 server afterwards.

## Phase 2: Foundational (blocks every story)

- [X] T003 Extract the stored-pairing rule into one shared static helper. Use it from
  `LeagueMatchupRepository.pairedWithScores` (`B/store/LeagueMatchupRepository.java:110-126`), or put
  it alongside as a pure function over `(rosterId, matchupId, points)` rows. The rule: lower `roster_id`
  is home, ordered by `a.roster_id`, null `matchup_id` skipped, and a 3-roster `matchup_id` yields the same
  pairs as SQL (A12). Add a unit test in `BT/store/` (or `BT/engine/`) that pins the 3-roster and null
  cases against the existing `ScheduleSwapTest` fixture shape.

## Phase 3: User Story 2: every caller names its rule (P1)

**Goal**: delete the defaulted overload. Behaviour stays identical everywhere (FR-006/007, SC-005).
**Independent test**: it compiles, the suite is green, and after T008 the capture diff is empty for
non-Matchups pages.

- [X] T004 [US2] In `B/engine/LeagueSeasonResolver.java`, delete `resolve(String)` and add `Rule.LIVE_WEEK`
  with javadoc. "qualifies when `PLAYED_WEEKS`, **or** all of: not complete, `status = 'in_season'`,
  stored `settings_json.leg` ≥ 1, `STORED_GAMES`" (data-model). Use `LeagueRepository.currentLeg` and
  `LeagueRow.status()`/`complete()`.
- [X] T005 [US2] Switch every one-argument call to `resolve(id, Rule.PLAYED_WEEKS)`:
  - `B/engine/ExpectedWinsService.java:361,373`
  - `B/engine/PlayoffOddsService.java:311`
  - `B/engine/RosterManagementService.java:140`
  - `B/engine/SeasonSuperlativesService.java:254`
  - `B/recap/RecapService.java:113,205`
  - `B/recap/AdminRecapController.java:45,54,66,79`
  - `B/refresh/LeagueRefreshService.java:162`

  Leave `WeeklyReportService:188` for T010.
- [X] T006 [US2] Fix the test callers (A10): `ScheduleSwapTest:160`, `AdminRecapControllerTest:56`,
  `RecapServiceTest:290,692`. Stub with `eq(LeagueSeasonResolver.Rule.PLAYED_WEEKS)`, never `any()`.
  Delete the default-overload test at `LeagueSeasonResolverTest:86`.
- [X] T007 [P] [US2] In `BT/engine/LeagueSeasonResolverTest.java`, add `LIVE_WEEK` cases.
  - Qualifies: games + `in_season` + leg 1 + no weeks.
  - Does not qualify: status null; `complete`; leg absent; no games; status `in_season` with no games
    (drafted, preseason).
  - `PLAYED_WEEKS` and `STORED_GAMES` keep their existing assertions.
- [X] T008 [US2] `./gradlew compileJava compileTestJava`, then run the resolver, recap and schedule-swap
  tests.

## Phase 4: User Story 1: Matchups shows this season's live week (P1) 🎯 MVP

**Goal**: FR-001–005 and FR-015–019. **Independent test**: the opening-week unit test, plus the live league
on 8082 matching Sleeper's `/matchups/{leg}`.

- [X] T009 [US1] Create `B/engine/LiveWeekSource.java` (a `@Component`, plan A4/A6, data-model "LiveWeekSource.Live").
  - API: `Optional<Live> current(LeagueRow)`, with `Live(int week, Status status, List<Row> rows, Instant at)`,
    `Row(int rosterId, Integer matchupId, double points)`, `Status {OK, FAILED}`.
  - Returns empty for: an ESPN league, `league.complete()`, Sleeper `leg < 1`, a Sleeper 404, or a null
    league body.
  - Reads `sleeper.league(id)` and then `sleeper.matchups(id, leg)` inside
    `CompletableFuture.supplyAsync(..., virtual-thread executor).orTimeout(timeoutMs)`. Timeout or
    exception gives `FAILED`.
  - Cache per sleeper id with an injected `Clock`. Success and empty entries last `LockInProperties.liveTtlSeconds`.
    `FAILED` entries last `liveFailureTtlSeconds`. Use a bounded synchronized LinkedHashMap, like `LockInService.liveCache`.
  - Add `draftsim.live-week.timeout-ms: 2500` to `backend/src/main/resources/application.yml`, with a
    comment that it's hand-set and arbitrary, plus a small properties record or `@Value`.
- [X] T010 [US1] In `B/engine/WeeklyReportService.java`:
  - `forWeek(String, int, String, Rule)` is the full form. The existing 2- and 3-argument forms are
    removed; callers pass the rule (A1).
  - Resolve with the given rule.
  - Get `live = liveWeeks.current(league)` only when `rule == LIVE_WEEK`.
  - **"Started" (A11)**: some live row has `points > 0`, or a `player_game` row is dated in that week. Find
    the date helper used by the existing week/game mapping in this service (`weekGameRows`), and don't
    invent a second week-to-dates rule.
  - `L` = live week when OK, started, and not in `scoredWeeks.of(...)` stored weeks.
  - Live path when (`requestedWeek <= 0 && scored.latestStored() == 0 && L != null`) or
    `requestedWeek == L`.
  - Explicit request for an unstarted live week: `Result.unavailable("week L hasn't started")`.
  - `FAILED`: `Result.unavailable("live scores unavailable from Sleeper right now", …)`, with the current
    season, the stored latest weeks, and `liveWeek` = Sleeper's week.
  - Otherwise use today's path, plus `liveWeek = L`.
  - `defaultWeek` is unchanged.
- [X] T011 [US1] The live result builder in the same file (research R4):
  - Names, usernames, avatars and `isMe` come from the existing helpers.
  - `record` is the record **before** this week: `recordAfter(..., week - 1, ...)`, or "0-0" when week 1.
  - Matchups come from the live rows via T003's helper.
  - Ranking lists are empty (`topPerformers`, or `bestNights`/`bestWeek`, by the existing sport rule), and
    so are `awards` and `awardsOmitted`.
  - `weekFinal=false`, `live=true`, `requestedSeason=null`.
- [X] T012 [US1] `Result` gains `boolean live, Integer liveWeek, String sleeperLeagueId`, plus a back-compat
  constructor with today's 18-argument signature defaulting `false, null, null` (A9). `unavailable(...)`
  gets an overload that takes `liveWeek` and `sleeperLeagueId`. Every path fills `sleeperLeagueId` with the
  resolved row's `sleeperId()`.
- [X] T013 [US1] In `B/api/WeeklyReportController.java`, `body()` puts `live`, `liveWeek` and
  `sleeperLeagueId` **before** the `!available` early return, on a `LinkedHashMap` (nullable values, no
  `Map.of`). The controller calls `forWeek(..., Rule.LIVE_WEEK)`. Update `BT/api/WeeklyReportShapeTest`
  so every shape carries the three keys.
- [X] T014 [US1] Recap (A1, FR-015): `B/recap/RecapService.java` and `AdminRecapController.java` call
  `forWeek(..., Rule.PLAYED_WEEKS)`. Generation and preview refuse a result whose `live()` is true, with
  the existing not-available state. Update `RecapFixtures`/`RecapServiceTest` to match, and add a test
  that a live result is refused.
- [X] T015 [P] [US1] `BT/engine/LiveWeekSourceTest.java` covers:
  - OK path
  - 404 → empty
  - null body → empty
  - complete → empty without calling Sleeper
  - leg 0 → empty
  - exception → FAILED
  - slow stub beyond the timeout → FAILED within roughly timeout + 500 ms
  - TTL hit, and expiry with the clock advanced
  - FAILED cached only for the failure TTL
- [X] T016 [US1] `BT/engine/WeeklyReportServiceTest.java` (FR-008) covers:
  - **Opening week**: games stored, `in_season`, leg 1, no weeks, a stubbed live source with 12 rows / 6
    matchups. Expect season = current, `live`, `requestedSeason` null, 6 matchups paired by the shared
    rule, "0-0" records, empty rankings and awards.
  - **Mid-season**: final weeks 1..3, live 4. The default is still 3, with `liveWeek` 4. An explicit week 4
    is live.
  - **Unstarted** live week (all points 0, no games that week): no `liveWeek`, and an explicit request
    answers "hasn't started".
  - **FAILED**: current season unavailable, never last season.
  - **Stored partial current week**: today's stored path, not live.
  - **Offseason** (no games): falls back as today.
- [X] T017 [US1] `W/api.ts`: `WeeklyReport` gains `live: boolean`, `liveWeek: number | null` and
  `sleeperLeagueId: string | null`, each with a doc comment. The record mirror rule applies.
- [X] T018 [US1] `W/pages/WeeklyReport.tsx`:
  - Week bounds = `Math.max(latestScoredWeek, liveWeek ?? 0)` at the URL clamp (`:76-82`), `goToWeek`
    (`:88`) and the next button (`:118`) (A2).
  - The "Week N in progress →" link also shows when `liveWeek != null && liveWeek > week`.
  - When `live`: an "In progress, live from Sleeper" label on the scoreboard. Rankings and awards are
    replaced by one note: "Rankings and awards come once week N is final."
  - `SeasonFallbackNote` behaviour is unchanged (it's driven by `requestedSeason`).
- [X] T019 [US1] The league home and football spotlight (A3):
  - Check `W/pages/LeagueHome.tsx` `scoredThisSeason` (`:223-228`) and `MatchupBlock` (`:525-537`) render a
    `live` response sensibly. A `!weekFinal` week is already handled, so adjust only if needed.
  - `W/components/HomeSpotlight.tsx:112` / `PlayerSpotlight.tsx:300-312` show "week in progress" when
    `live` is true, instead of an empty top list.
- [X] T020 [P] [US1] `W/pages/WeeklyReport.test.tsx` covers:
  - Live opening week: label shown, no rankings, no fallback note.
  - Mid-season `liveWeek = latest + 1`: the link navigates to the live week and isn't clamped back (A2).
  - Unavailable live failure: shows the reason.

## Phase 5: User Story 3: Lock or wait, per matchup, this week (P2)

**Goal**: FR-009–011, FR-013–014. **Independent test**: on 8082, every card's calls equal `/lock-ins`
`live.calls` for its two rosters. No backend change.

- [X] T021 [US3] `W/pages/WeeklyReport.tsx`, separate effect (A16): when `data.sport === 'nba'` and
  `!data.weekFinal` and `data.sleeperLeagueId`, call `fetchLockIns(data.sleeperLeagueId)` once.
  - The matchups render first. Calls show a loading state.
  - Hide everything on status `NOT_SLEEPER` / `NOT_NBA` / `GAME_MODE`.
  - Render calls only when `report.live?.week === data.week` (A5). Otherwise say the lock-in week
    differs.
  - If `live.status !== 'OK'`, say why in words (`NO_HISTORY`, `ROSTERS_FAILED`, `NOT_LIVE`).
- [X] T022 [US3] Per side, under each `ScoreSide`, a compact call list grouped by `rosterId`.
  - Each entry: the call chip (Lock / Wait / Too early), player, latest game points, margin % with the
    baseline kind always named (projection / stand-in / last season), games left, and the bar for that
    many games left.
  - One caption per lock-ins area: "Locks are inferred from credited points; bars are learned from past
    weeks."
  - Match the existing lock-ins styling (League analysis / player page `lock-ins` tab components), and
    reuse its components if they're exported.
- [X] T023 [P] [US3] Vitest cases:
  - Calls paired to the right side.
  - Football has no lock-in content.
  - `GAME_MODE` status hides it.
  - `ROSTERS_FAILED` shows the reason with scores still visible.
  - Live week mismatch.

## Phase 6: User Story 4: who locked what, in a past week (P3)

**Goal**: FR-012–014. **Independent test**: `/lock-ins?week=5` on NBA 2025 equals 044's provable locks
for week 5. `/lock-ins` and `?player=` are byte-identical to the T002 baseline.

- [X] T024 [US4] `B/engine/LockInService.java`:
  - `Computed` gains `Set<Integer> weeks`, set where the computation is cached.
  - `LockInReport` gains a last component `@JsonInclude(JsonInclude.Include.NON_NULL) Boolean weekCovered`
    (A8). Every existing construction passes null.
  - New `viewWeek(Computed c, int week)`: `locks` = `allLocks` with `week == N`. `managers`, `players` and
    `payoff` are emptied the way the `player` form does. `live` is null.
    `weekCovered = STATUS_MEASURED.equals(status) && weeks.contains(N)` (A7).
  - `report(league, player, week, live)`: when `week != null`, return `viewWeek` plus thresholds, with no
    live calls.
- [X] T025 [US4] `B/api/LockInController.java`:
  - Add a `week` param (nullable Integer).
  - After the membership 404, `player` and `week` together give a 400 with the existing
    `Map.of("message", …)` body (A17).
  - Pass `week` through. With `week` given, `live` defaults to false.
- [X] T026 [P] [US4] Tests in `BT/engine/LockInServiceTest.java` (or a new `LockInWeekViewTest`):
  - The week view equals `allLocks` filtered by week.
  - `weekCovered` true for a computed week, false for a non-computed week and for a refusal.
  - No ride-out ever appears.
  - Plus a controller test: the 400 for both params, and the 404 for a non-member still comes first.
- [X] T027 [US4] `W/api.ts`: `LockInReport` gains `weekCovered?: boolean`, and `fetchLockIns` gains an
  optional `week`.
- [X] T028 [US4] `W/pages/WeeklyReport.tsx`:
  - For a final NBA week, call `fetchLockIns(sleeperLeagueId, { week })` once, in a separate effect.
  - Per side, list its locks: "Mitchell +54% stand-in (2 later games)" (A14). Show the projection margin
    when present, else the stand-in margin, always labelled and never pooled.
  - A side with no locks shows "no locks".
  - `weekCovered === false` shows "can't tell for this week".
  - The same caption as T022.
- [X] T029 [P] [US4] Vitest: locks grouped per side, "no locks", "can't tell", and the labelled baseline
  kind.

## Phase 7: Polish & verification inputs

- [X] T030 Backend `./gradlew test`: report total, failed and **skipped** counts (memory: ITs skip
  silently when Postgres is down). Known pre-existing:
  `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`, plus 6
  `PlayerStatsLeaderboardReadIT` skips.
- [X] T031 `cd web && npx tsc -b && npm run build && npx vitest run`
- [X] T032 Branch capture (also time Matchups warm, for SC-006, and count Sleeper roster reads per view, for FR-010, from the logs; analyze C1): build the branch, run it on 8082 with refresh off, `capture.sh` into
  `specs/045-matchups-live-season/after/`. `diff.sh baseline/run1 after` must be empty except for the
  documented nondeterministic keys from T002 (SC-003, SC-004, FR-012).
- [X] T033 Grep: no `resolve(` call with one argument remains (SC-005, also enforced by the compiler), and
  no `Map.of(` was added in a response path with nullable values.

## Dependencies & Execution Order

- T001 → T002 must finish **before any code edit** (the baseline is the unchanged tree).
- T003 (Foundational) → US1. T004–T008 (US2) → US1 (US1 needs `Rule.LIVE_WEEK` and the rule parameter).
- US1 → US3 (needs `live`, `sleeperLeagueId` and the live week on the page).
- US4 depends only on T012/T017 (`sleeperLeagueId`) and can run alongside US3. They both touch
  `WeeklyReport.tsx`, so serialize those two tasks (T021/T022 then T028).
- Phase 7 is last.

### Parallel opportunities

- T007 ∥ T005/T006 (different files). T015 ∥ T016 once T009 and T010 exist. T020, T023 and T029 are
  separate test cases, but they're in one test file, so serialize them in practice. T024–T026 (backend
  US4) ∥ T018–T022 (frontend).

## Implementation Strategy

- **MVP = US2 + US1**: the bug fix. That's shippable alone, and it's what Allan asked for first.
- Then US3 (live calls), then US4 (past locks). Each is an increment the page shows independently.
- Coding subagents run on Sonnet (AGENTS.md). The parent reads every diff before marking a task done.

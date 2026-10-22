---
description: "Task list for spec 044, lock-in threshold"
---

# Tasks: Lock-in threshold

**Input**: `specs/044-lock-in-threshold/` (spec.md, plan.md *including "Amended after plan review"*, research.md
R2 v2 / R5 v2 / R8–R10, data-model.md, contracts/lock-in-api.md, quickstart.md)

**Tests**: Included. The success criteria are hand-computation equalities (SC-001/003/007/008), and plan review
F7/F9/F14 asks for specific guard tests. Where the plan's amendment section and its Design disagree, **the
amendment wins**.

Paths are relative to the worktree root. `B` = `backend/src/main/java/com/ballknowers/draftsim`, and
`BT` = `backend/src/test/java/com/ballknowers/draftsim`.

## Phase 1: Setup

- [X] T001 Record the `PlayerTrendsService.oneGameShare` values for leagues 211 and 212 **before** any code change
  (F9). Read them by calling the method from a scratch test **in this worktree**, against the local DB, before
  T008. Don't use the main checkout's server, which runs a stale 017 branch (analyze U1), and write both numbers, with their full precision, to
  `specs/044-lock-in-threshold/baseline/one-game-share-before.txt`.
- [X] T002 [P] Add a `draftsim.lock-in` block to `config/weights.yml` with `min-locks: 10`, `min-cell: 15`,
  `min-prior-games: 5`, `margin-bands: [0, 0.10, 0.25, 0.50]`, `games-left-cap: 3`, `threshold-cap: 4` and
  `live-ttl-seconds: 60`. Give each value a comment saying it is hand-set and arbitrary, and why it exists (see
  research R7 / plan amendment item 9).
- [X] T003 [P] Create `B/config/LockInProperties.java`, a `@ConfigurationProperties("draftsim.lock-in")` record
  that mirrors T002. Follow the pattern of `B/config/ProjectionAccuracyProperties.java`, and register it wherever
  that class is registered.

## Phase 2: Foundational (blocks every story)

- [X] T004 Add guard cases to `BT/engine/PlayerTrendsServiceTest.java` **before** the rewrite (F9). Add one test
  with a tie (two games in one week scoring the credited value), one with an All-Star-opponent game row, and one
  with a zero credit and no zero game. Assert today's `oneGameShare` output for each, then run them green against
  the unchanged code.
- [X] T005 Extract the All-Star rule (an opponent that is not a season team code) from `B/engine/NbaGameLines.java`
  into a public static, for example `NbaGameLines.seasonTeamCodes(List<TeamGame>)`, together with a predicate
  `isTeamGame(SeasonGame, Set<String> codes)`. `NbaGameLines.of` must call it, so there is one implementation of
  the rule. Run `NbaGameLinesTest` unchanged and confirm it is green.
- [X] T006 Create `B/engine/CreditedGames.java`, a pure static class. `match(double credited, List<Double>
  weekScoresInDateOrder)` returns a classification: `LOCK(index) | RODE_OUT | RODE_OUT_MISSED | TIE_NON_LAST |
  TIE_WITH_LAST | UNMATCHED | SINGLE_GAME | NO_GAMES`.
  - A match means `|score − credited| < 0.005`.
  - `RODE_OUT_MISSED` means credited == 0 and no game scored 0.
  - The method takes the list as given and does no filtering of its own (F9).
  - Its javadoc cites research R2 v2 and plan review F5.
- [X] T007 [P] Create `BT/engine/CreditedGamesTest.java`. Cover every classification. Assert the invariant (the
  class counts sum to the multi-game total) over a mixed fixture. Include the 0.004 vs 0.006 tolerance boundary.
- [X] T008 Rewrite `PlayerTrendsService.oneGameShare` (`B/engine/PlayerTrendsService.java:535`) to call
  `CreditedGames.match`. Its numerator stays "any game matches", which is `LOCK + RODE_OUT + TIE_*`, so
  `RODE_OUT_MISSED` and `UNMATCHED` stay unmatched there. Its inputs stay raw games and all weeks. T004's tests and
  the existing tests must pass unchanged.
- [X] T009 Add `LeagueRepository.gameMode(long leagueId)` to `B/store/LeagueRepository.java`. It returns
  `OptionalInt` read from `settings_json->>'game_mode'`, and is never defaulted (memory: optional params that
  encode rules). Add a repository IT case beside the existing `LeagueRepository` IT tests.
- [X] T010 Create `B/engine/LockThresholds.java`, a pure static class.
  - `fit(double[] margins, double pMissed, int cap)` returns `double[cap + 1]`, where
    `V1 = (1 − p)·mean(m) + p·(−1)` and `V(k+1) = mean(max(m, Vk))`, as in research R8.
  - `quantiles(double[])` returns q1/median/q3 using the **exclusive** method (Python's
    `statistics.quantiles(n=4)` default).
  - Its javadoc explains why the naive break-even was rejected (research R8).
- [X] T011 [P] Create `BT/engine/LockThresholdsTest.java`.
  - Run the recursion on a 5-value known-answer fixture computed by hand in a comment.
  - Check that `p = 0` reduces V1 to the mean.
  - Check that the thresholds are monotone non-decreasing in k.
  - Pin the exclusive quantiles at n = 10 and n = 11 against values from Python's `statistics.quantiles` (F14).

**Checkpoint**: the shared matcher, the threshold maths and the gate accessor exist and are tested, and the
`oneGameShare` output is unchanged.

## Phase 3: User Story 1, manager lock margins (P1) — MVP

**Goal**: The `/lock-ins` endpoint and the manager table on League analysis.

**Independent test**: 2025 (league 211). One manager's median and n equal `measure_v2.py`'s numbers (SC-001), and
the totals equal research R2 v2's table (SC-002).

- [X] T012 [US1] Create `B/engine/LockInService.java` with the records from data-model.md, including its amendment.
  Mirror every record field-for-field in `web/src/api.ts` in T018. `report(LeagueRow league, String playerFilter,
  boolean live)` does the following:
  - **Gate (F8)**. It returns these statuses in order: `NOT_SLEEPER` (`!league.isSleeper()`); `NOT_NBA`;
    `GAME_MODE` when `gameMode` is empty or != 1; `NO_SCORING` when `scoringOf` is empty; `NO_SCORED_WEEKS`.
    Then it computes this report's own share and returns `SHARE_BELOW_THRESHOLD` when it falls below
    `draftsim.player-trends.one-game-share`. Read the existing property; don't copy the 0.9.
  - **Games (F1/F2/F3)**. Use `SeasonBoxCache.get(NBA, season).games()`, drop non-team-opponent games via T005,
    and **do not** apply `weights.yml` excluded-game-ids (the Cup final is kept). Order by `(gameDate, gameId)`.
  - **Weeks (F7)**. Use `ScoredWeeks.of(league.id()).finalWeeks()`. When the league is not `complete()`, also drop
    weeks ≥ `currentLeg`.
  - **Starters** come from `RosterWeekPointsRepository.breakdownsFor`, via `WeeklyReportService.startersOf`.
  - **Manager** comes from `roster_season` (roster → manager_id). Names come from `RosterOwners.ownerNames`, which
    is nullable (F14).
  - **Stand-in**. It is the mean league score of the player's same-season games dated before the lock date, and it
    requires `min-prior-games`. Otherwise count `noBaselineStandIn`. When the baseline is ≤ 0, there is no margin.
  - **Projection baseline**. Use a `projection_snapshot` row for that `game_id`, with **both sides** scored on the
    `ProjectedCategories` restricted map for the sport-season. Re-score the locked game's own stats; do not use
    `players_points`. The excluded categories go into the payload.
  - **Lock fields**: `laterGames`, `lastGame`, `bestLater`, `beatenByLast`, `beatenLater`, and `playoff` from
    `LeagueRepository.playoffFormat`.
  - **Aggregates**. Build per-manager and per-player summaries per baseline kind (`n`, median, q1, q3 via T010).
    Medians are null below `min-locks`. Include the `rodeOut` and `tieNonLast` counts, and the `totals` of every
    class.
  - **Caveats**, in this order: inferred locks; the default is the last scheduled game; a later game means a
    played game; the stand-in is not a projection; describes past weeks.
  - **No `Map.of` on nullable values.**
- [X] T013 [US1] Cache `LockInService` per league id on the F6 stamp: the box-cache token; a
  `roster_week_points` digest query (add `RosterWeekPointsRepository.digest(leagueId)` returning
  `count(*)||sum(starters_points)||md5(string_agg(players_points::text, ',' order by week, roster_id))`); the
  `ScoredWeeks` final-weeks set; `ProjectionSnapshotRepository.stamp`; the scoring hash; the gameMode and
  playoffFormat; and a `roster_season` manager digest. Bound it to 64 entries like `ProjectionAccuracyService`.
- [X] T014 [P] [US1] Create `BT/engine/LockInServiceTest.java` with fakes or fixtures for the repositories.
  - Run the gate through every status.
  - An All-Star game must not create a lock.
  - A Cup final game is matchable.
  - A non-complete league with no POINTS rows must not publish its `currentLeg` week (F7).
  - A stand-in is not computed with fewer than 5 prior games.
  - The projection margin uses the restricted map on both sides.
  - STAND_IN and PROJECTION figures are never combined.
  - Assert the totals invariant.
- [X] T015 [US1] Create `B/api/LockInController.java` serving `GET /api/leagues/{sleeperId}/lock-ins` with the
  query params `player` and `live` (default true). Copy `ProjectionAccuracyController`'s lookup and its
  `LeagueMembership.canSee` 404.
- [X] T016 [P] [US1] Create `BT/api/LockInControllerIT.java`. Cover the 404 for an unknown or invisible league, a
  200 with `status MEASURED` on a seeded NBA league, and `NOT_NBA` on a football league. The test needs Postgres:
  **check the skip count** in the Gradle output (memory: suite skips ITs silently).
- [X] T017 [US1] Run `/lock-ins` for 211 and 212 against the local DB, compare them with `measure_v2.py`'s output,
  and write the comparison to `specs/044-lock-in-threshold/baseline/hand-check-us1.md` (SC-001/SC-002). Every
  mismatch is a bug to fix, not a tolerance to widen.
- [X] T018 [US1] Add the `LockInReport` types to `web/src/api.ts`, plus all the child types (Totals, LockSummary,
  BaselineFigure, PayoffCell, Lock, LockThresholds, LiveCalls, LiveCall). They must match the Java records
  field-for-field, nullable fields as `| null`. Add `fetchLockIns(sleeperId, opts?: {player?: string; live?:
  boolean})`.
- [X] T019 [US1] Create `web/src/components/LockInPanel.tsx` with the Managers block. Each row has the avatar
  (existing avatar component), the median margin as a signed %, an IQR bar with the exact q1/q3 values printed
  beside it (memory: label the axis), the lock n, and the rode-out n.
  - Below `minLocks`, the row shows "n locks — too few".
  - A baseline toggle (Season-average stand-in by default, Projection) switches **every** block in the panel.
  - The caveats and the excluded counts render from the payload.
  - Use the existing card and pill styles in `web/src/styles.css` (memory: avoid flat uniform cards).
- [X] T020 [US1] Mount `LockInPanel` in `web/src/pages/LeagueAnalysis.tsx` next to `ProjectionAccuracyPanel`. Fetch
  with `live: true`. Render nothing when `status` is `NOT_NBA`, `NOT_SLEEPER` or `GAME_MODE`. For the other
  non-MEASURED statuses, render a one-line reason.
- [X] T021 [P] [US1] Create `web/src/components/LockInPanel.test.tsx`.
  - A manager row shows its baseline kind and n (SC-004).
  - "too few" shows below the minimum.
  - The toggle swaps all blocks.
  - A football league renders no panel.

**Checkpoint**: US1 works end to end on 2024 and 2025.

## Phase 4: User Story 2, player lock margins (P2)

**Independent test**: one 2025 player's median and n equal the hand check, and his lock list's length equals n.

- [X] T022 [US2] In `LockInService`, make the `?player=` response carry **only** that player's summary, `locks`
  (with manager attribution), `thresholds` and caveats (F14). Extend `LockInServiceTest` with a player locked by two
  managers, from the cross-manager fixture.
- [X] T023 [US2] Add the Players block to `LockInPanel.tsx`. Make it sortable by median and by n, with
  below-minimum rows always after the others (spec US2 scenario 3). Add a test in `LockInPanel.test.tsx`.
- [X] T024 [US2] Add a `lock-ins` tab to `TABS` in `web/src/pages/PlayerPage.tsx` (spec 042 tabs), shown only for
  NBA lock-in leagues. It fetches `fetchLockIns(id, {player, live: false})` and shows the median per baseline kind
  and the lock list: week, manager, points, baseline, margin, later games and a playoff badge. With no locks, it
  says "No provable locks for this player". Add a test in `web/src/pages/PlayerPage.test.tsx`, or create that file
  if it doesn't exist.
- [X] T025 [US2] Hand-check one 2025 player with ≥ 10 locks and one locked by two managers. Append the results to
  `baseline/hand-check-us1.md`.

## Phase 5: User Story 4, live Lock/Wait calls (P2)

**Independent test**: a fixture week gives LOCK at +30% and WAIT at +20% against a +23% two-left threshold, and
the thresholds equal a hand computation (SC-007).

- [X] T026 [US4] Add thresholds to `LockInService`.
  - Collect margins from **every completed league** in `chainBySleeperId(league.sleeperId())` (`complete()`). The
    population is every starter's played game in a final multi-game week with ≥ `min-prior-games` prior games,
    **locked or not** (research R8).
  - Set `pMissed = Σ rodeOutMissed / Σ (rodeOut + rodeOutMissed)` over the same seasons.
  - Call `LockThresholds.fit`, and publish `{status, from, pMissed, byGamesLeft[{gamesLeft, threshold, n}]}`. When
    there is no completed season, the status is `NO_HISTORY`.
- [X] T027 [US4] Add `liveCalls(LeagueRow, LocalDate asOf)` to `LockInService` (plan amendment item 6).
  - The league must be non-complete with a `currentLeg`, otherwise the status is `NOT_LIVE`.
  - Starters come from the live Sleeper `/league/{id}/rosters` via `SleeperClient`, exactly as
    `LineupCheckService` does it. On failure, set `ROSTERS_FAILED`, log it and never throw.
  - Each call judges the **most recent** played game this week (`latestGame`); earlier games can't be locked
    (analyze F1).
  - Games left are `sport_schedule` rows for `player.team` in that week, dated after `asOf`, whose status is not
    complete or postponed.
  - The baseline is the stand-in, else `LAST_SEASON` (the previous chain season's full average, ≥
    `min-prior-games`), else the call is `TOO_EARLY`.
  - The call is `LOCK` iff margin ≥ threshold[min(gamesLeft, threshold-cap)], otherwise `WAIT`. With
    gamesLeft == 0 there is no entry.
  - The cache key is (league id, asOf) with a TTL of `live-ttl-seconds`.
- [X] T028 [P] [US4] Add US4 tests to `LockInServiceTest`.
  - The LOCK and WAIT boundary at exactly the threshold.
  - The `LAST_SEASON` fallback and its label.
  - `TOO_EARLY`.
  - `ROSTERS_FAILED` doesn't throw.
  - `NO_HISTORY` when the chain has no complete season.
  - A threshold equal to `LockThresholds.fit` over the fixture's margins.
- [X] T029 [US4] Add a "This week's calls" block to `LockInPanel.tsx`. Each entry shows the player, manager, latest
  game points, baseline (labelled "season avg" or "last season's avg"), margin, games left, the threshold and its
  n, and a **Lock** or **Wait** pill. Add a "Lock thresholds" strip listing one value per games-left count, with n
  and the seasons used. Show the US4 scenario 4 caveat sentence. Add tests in `LockInPanel.test.tsx`.
- [X] T030 [US4] Hand-check the thresholds (SC-007). Recompute them from 211 + 212 in a fixed scratch script, and
  compare them with the endpoint for league 210 (2026), whose chain is 2025 + 2024. Record the result in
  `baseline/hand-check-us4.md`.

## Phase 6: User Story 3, payoff table (P3)

- [X] T031 [US3] Add the payoff grid to `LockInService`, per baseline kind. Build it as band × min(laterGames,
  games-left-cap), each cell `{band, gamesLeft, n, rateLast, rateAny}`, with the rates null below `min-cell`. Use
  the band labels from config edges: `<0, 0-10, 10-25, 25-50, 50+`. Add tests to `LockInServiceTest`.
- [X] T032 [US3] Add a "When locking paid off" grid to `LockInPanel.tsx`. The headline in each cell is "riding it
  out beat it X% (n)", with "in hindsight Y%" smaller beside it. The caption says it describes past weeks, not a
  prediction, and that a later game means a game the player actually played. Add a test.
- [X] T033 [US3] Hand-count one cell (SC-003) and record it in `baseline/hand-check-us1.md`.

## Phase 7: User Story 5, digest tweet card (P3)

- [X] T034 [US5] Add `lockInCalls: List<LockInCard>` to `AdminDigestService.SportDigest`
  (`B/engine/AdminDigestService.java`). It is NBA only, and an empty list for NFL.
  - Create one card per current-season Sleeper NBA league that passes the F8 gate.
  - Each card comes from `LockInService.liveCalls(league, night + 1 day)`, filtered to LOCK entries whose latest game
    is on `night`, and sorted by margin descending.
  - The card status is one of `OK | NONE_TONIGHT | NO_HISTORY | ROSTERS_FAILED`.
  - The tweet is built server-side and truncated by **whole entries** to ≤ 280 characters.
  - Wrap the card in the digest's existing per-section failure isolation.
  - Mirror the new fields in `web/src/api.ts`.
- [X] T035 [P] [US5] Extend `BT/engine/AdminDigestServiceTest.java`. Check the card's entries against a fixture
  night (SC-008), the tweet length ≤ 280 with many entries, `NONE_TONIGHT`, the empty list for NFL, and that a
  throwing LockInService does not fail the digest.
- [X] T036 [US5] Render the card in `web/src/pages/AdminDigest.tsx` with the same copy-button pattern as the other
  cards, showing the status reason when it isn't `OK`. Add a test in `web/src/pages/AdminDigest.test.tsx`.

## Phase 8: Polish and verification hand-off

- [X] T037 Re-measure `oneGameShare` for 211 and 212 after the change, and assert it is byte-equal to T001's file.
  Record the result in `baseline/one-game-share-after.txt` (F9).
- [X] T038 [P] Run the full backend suite with `cd backend && ./gradlew test`. Record the pass, fail and **skip**
  counts. Also time `/lock-ins` cold and warm for 2024 and the analysis page load (SC-005, < 2 s; analyze C1). Postgres must be up; a skip count above the known baseline is a failure to report, not to ignore.
- [X] T039 [P] Run `cd web && npx tsc -b && npm run build && npx vitest run` and record the result.
- [ ] T040 Add a README feature line, a HANDOFF entry (what is verified and what is assumed, in those words) and,
  if the review or verification teaches something durable, a `claude/lessons.md` entry. The likely one is "the
  Cup final is a real-basketball exclusion, not a fantasy one".

## Dependencies

- Phase 1 → Phase 2 → US1 (Phase 3). US1 is the MVP, and the rest build on its `LockInService`.
- US2 (Phase 4), US4 (Phase 5) and US3 (Phase 6) each depend only on US1. They touch the same service and panel
  files, so build them **sequentially** in the order listed; there are no parallel writers on one file.
- US5 (Phase 7) depends on US4's `liveCalls`.
- Phase 8 comes after all of them.

## Parallel examples

- Phase 1: T002 and T003 run together.
- Phase 2: T007 and T011 (test files) run alongside T008–T010 once T006 and T010 exist.
- US1: T014, T016 and T021 run in parallel once T012, T015 and T019 exist.

## Implementation strategy

1. **MVP**: Phases 1–3 (US1). This already answers Allan's original question, "what % above do people lock in",
   per manager.
2. Then US2 (per player), US4 ("you should lock this"), US3 (the evidence grid) and US5 (the tweet card).
3. Then bug-hunting code review → `code-review.md`, then live verification → `verification.md`, driving the real
   server, the UI and the real Sleeper rosters call. Commit, push and PR wait for Allan.

## Build notes (2026-10-22)

Phases 1-3 (T001-T021, US1) built. Where the build departs from the task text, or decided something the text left open:

- **T001 / T008 / T037.** `oneGameShare` before: 211 (2025) 0.9935513588208199, 212 (2024) 0.9818855496088925, read by a
  throwaway Spring test in this worktree before any change (`baseline/one-game-share-before.txt`). After the rewrite onto
  `CreditedGames.match` the same test gave byte-identical output (`baseline/one-game-share-after.txt`, `diff` clean). That
  is T037's measurement, taken early; T037 stays open for the final pass. The scratch test was deleted.
- **T004** guard cases pass on the unchanged code (tie, All-Star row, zero credit with and without a zero game).
- **T005.** `NbaGameLines.seasonTeamCodes` has two overloads, `(teamRows)` for this feature and `(teamRows, excludedGameIds)`
  which `of` uses, so Cup-final exclusion in the Lines read is unchanged. `isTeamGame(SeasonGame, codes)` is the shared predicate.
- **T010.** `LockThresholds.fit` returns `double[cap + 1]` with index 0 = `NaN` (no games left, no decision).
- **T012 shape.** The report carries every field the later stories need, but only US1 fills them: `payoff` is
  `{STAND_IN: [], PROJECTION: []}` (US3), `thresholds` and `live` are null (US4). The `live` argument of `report` is accepted and
  ignored. `?player=` is already narrowed to that player's summary and locks (F14), so T022's service half exists; T022 stays open
  for the cross-manager test and the rest of US2.
- **Fields beyond data-model.md**, mirrored in `api.ts`: `Totals.nonPositiveProjection` (locks whose projection scored <= 0, so no
  margin; otherwise silently dropped), `LockSummary.avatarId` (the row's avatar), `LockInReport.minPriorGames` and
  `weeksCovered`. Java names `ProvableLock` / `LockInThresholds` (TS the same) avoid `Lock` (a DOM global in TS) and the
  `LockThresholds` maths class. `LockSummary.rodeOut` counts `RODE_OUT` only, so the managers' counts sum to `totals.rodeOut`.
- **Gate (F8).** A null `draftsim.player-trends.one-game-share`, or no multi-game starter-weeks at all, answers
  `SHARE_BELOW_THRESHOLD` with a reason saying which, rather than skipping the rule. The report keeps `totals` on that refusal.
- **`lock-ins` joins the `stats` route list in `LeagueAvailability.ROUTES`.** An ESPN league therefore gets the platform 422 from
  `PlatformGuard` before the controller (the plan said "under the stats route gate", F14); `NOT_SLEEPER` is still returned by
  the service for any caller that reaches it directly. `LeagueAvailabilityIT` walks the list and passes with it.
- **T013 digest** also md5s the `starters` list, not only `players_points`, because the credited starter set is an input too.
  New `ProjectionSnapshotRepository.lines(sport, season)` reads the frozen lines (the key union and the locked game's line).
- **T020.** `LockInPanel` is mounted after the projection-accuracy block but NOT inside its `projections.available` condition:
  lock-ins are retrospective, and a completed season has no available projections. `LeagueAnalysis.test.tsx`'s `../api` mock
  gained a `fetchLockIns` stub (a missing export on a `vi.mock` factory throws).
- **T017.** Hand check in `baseline/hand-check-us1.md`: every total, per-manager n / median / q1 / q3 / rodeOut / tieNonLast,
  pooled median and IQR, and players-with-10+ count equal `measure_v2.py` for 2024 and 2025. The PROJECTION path is
  covered by unit tests only (no snapshots locally for those seasons). Cold-request timing was not captured.
- **Verified vs not.** Run: backend `./gradlew.bat test` (2175 tests, 1 failed, 6 skipped; the failure is
  `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`, a draft start-time assertion in code
  this work does not touch, not checked against main; the 6 skips are `PlayerStatsLeaderboardReadIT`, none of the new ITs),
  `npx tsc -b`, `npx vitest run` (137 files, 2345 tests) and `npm run build`. Not run: the panel in a real browser, the
  real Sleeper rosters call (US4), and any prod-shaped data.

## Build notes (2026-10-22, second pass: T022-T039)

Phases 4-7 and the verification tasks T037-T039 built. T040 (docs) left to the parent. Decisions the task text left open, and departures:

- **Which seasons the thresholds pool (T026).** Every completed MEASURED lock-in season in the chain, **the league's own included when it
  is complete**; `thresholds.from` publishes them. 210 and 211 therefore share one set of numbers (2024 + 2025); 212 learns from 2024 alone.
  A chain season whose own report is not MEASURED (gate refusal, share below threshold) contributes nothing.
- **Threshold population is per game, not per week.** A game with fewer than 5 prior games is skipped on its own, where `stopping.py`
  (R8's source) dropped the whole week. n is 7018 / 6192 against R8's 6885 / 6052 and the thresholds sit ~0.001-0.002 lower; the "weeks"
  rule reproduces R8 exactly in `baseline/threshold-hand-check.py`. See `baseline/hand-check-us4.md`.
- **Live gate (T027).** A first-week league has no scored week of its own to run F8's share check on, so the live calls need only the
  static gate (Sleeper, NBA, game_mode 1, scoring stored) plus thresholds status OK (a completed MEASURED season in the chain vouches for
  it). `NO_SCORED_WEEKS` reports therefore still carry `thresholds` and `live`, and the panel draws them under the reason line.
  `live` is null for a complete league, when `live=false`, or when the league has no `currentLeg`.
- **`asOf` semantics (T027/T034).** "Games left" = schedule rows for the player's team in the week, on or after `asOf`, not settled
  (reuses `NextGames.forTeam`), and after the latest judged game's date. The task text said "dated after asOf", but the digest passes
  night + 1 day, where "after" would drop the next day's game. Judged games are those through `playedThrough`: `liveCalls(league, asOf)`
  uses asOf for both (the live endpoint, today in Eastern); the digest uses the 3-argument form `(night + 1, night)`, so a player's later
  games in the same week never replace the night's game as his "latest". A digest for an explicit past date will mostly say
  NONE_TONIGHT, because the schedule rows of past games are all settled; the card is meaningful for "last night".
- **Baseline for a live call** reads the previous chain season's box and scores it under THIS league's scoring (so the margin compares
  like with like). The nearest earlier season in the chain is used; with none, or fewer than 5 games, the call is TOO_EARLY.
- **Cache (T027).** Live results are cached per (league, asOf, playedThrough) for `live-ttl-seconds` against an injected `Clock`;
  a ROSTERS_FAILED answer is not cached, so the next request retries Sleeper.
- **`LockInService` constructor** gained `SportScheduleRepository`, `SleeperClient` and `Clock`; the old 11-argument constructor stays
  package-private for the pure tests. `Computed` gained a `Pool` (threshold margins, rodeOut, rodeOutMissed); `view(...)` is now
  package-private so the `?player=` narrowing is unit-tested. `isLockInLeague(league)` and `thresholds(league)` are public (the digest uses them).
- **Payoff (T031).** Cells for every band x games-left 1..cap are always present (n = 0 included): 15 per baseline kind. The
  PROJECTION grid is built from projection margins only and is empty of locks where no snapshot exists (all of 2024/2025 locally).
- **US2 list length.** `locks` on a `?player=` answer holds every provable lock (with a null margin where no stand-in exists), so it can
  be longer than `n`; verified on Mitchell (16 vs 15). See `baseline/hand-check-us1.md`.
- **Player tab (T024).** Shown only after `fetchLockIns(id, {player, live: false})` returns MEASURED; it is fetched once the page is
  known to be an available NBA page, so a non-lock-in league just has no such tab and `?tab=lock-ins` falls back to Overview.
  `PlayerPage.test.tsx`'s `../api` mock gained `fetchLockIns` (default: a GAME_MODE refusal).
- **Digest (T034).** `SportDigest.lockInCalls` is the 16th field. Cards exist only when the night is READY (otherwise the list is empty
  and the source is never asked). There was no pre-existing per-section try/catch around the digest's cards to reuse (the only catch
  guards the trending read), so the lock-in source gets its own, plus a per-league one. The seam is `AdminDigestService.LockInCards`;
  the 3-argument constructor the existing tests use defaults it to an empty list (SC-006). The tweet is "Lock-in calls after Oct 20:"
  plus one line per entry, `Name 37.5 pts, +75% vs avg, 1 left`, cut by whole entries at 280 characters (`String.length`, not Twitter's
  weighted length).
- **Verified vs not.** Run: backend `./gradlew.bat test` (2202 tests, 1 failed, 6 skipped; the failure is the same
  `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries` as before, in code this work does not touch; the
  6 skips are the same `PlayerStatsLeaderboardReadIT`), `npx tsc -b`, `npm run build`, `npx vitest run` (137 files, 2372 tests);
  `/lock-ins` for 210, 211 and 212 against a server on port 8144 with the real Sleeper rosters call for 210; the payoff grid, the
  thresholds and two players against independent scripts; T037 byte-equal. Not run: the new panel blocks, the player tab and the digest
  card in a real browser; the digest endpoint's lock-in card on real data (the local 2026-10-20 night is NIGHT_INCOMPLETE and no
  refresh was run, so the endpoint returned `lockInCalls: []`; the card text for 2026-10-20 was produced by a throwaway Spring test
  calling `liveCalls(210, 10-21, 10-20)` + `cardOf`, since deleted); the out-of-sample threshold check; any prod-shaped data.

- **T038 timings** (fresh `bootRun` on port 8144, local Postgres, `curl` total time): `/lock-ins?live=false` for league 212 (2024) cold
  1.56 s, warm 0.09-0.10 s; league 211 (2025) cold 1.09 s (2024 already cached), warm 0.11 s; league 210 with the live Sleeper call
  0.64 s cold (both earlier seasons cached), 0.09 s warm. The analysis page's own load was not timed in a browser (SC-005 is the
  `/lock-ins` read plus the page).

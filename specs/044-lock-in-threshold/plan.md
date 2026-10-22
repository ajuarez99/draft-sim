# Implementation Plan: Lock-in threshold

**Branch**: `044-lock-in-threshold` | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/044-lock-in-threshold/spec.md`

## Summary

Infer which game each NBA starter's credited weekly points came from. Classify each credited game as a provable lock
(a later played game existed that week) or something else. Measure each lock's margin above a baseline: the frozen
pre-game projection where one exists, and otherwise the player's season-to-date average as a labelled stand-in.
Aggregate per manager and per player, plus a payoff grid of how often a later game beat a lock made at a given
margin. It's read-only and computed on read, cached by input fingerprint, and served by one new endpoint. It's shown
on League analysis and the player page. There's no migration. Research (R2–R5) measured the inference on 2024/2025
before any code.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)

**Primary Dependencies**: The existing `GameScoringService`, `ProjectedCategories`, `ProjectionSnapshotRepository`,
`PlayerGameRepository.seasonPlayerGames`, `RosterWeekPointsRepository.breakdownsFor`, and `LeagueRepository`
(scoring, settings, roster → manager)

**Storage**: Postgres, read-only for this feature. No new tables, so no migration (highest on main is V38).

**Testing**: JUnit 5 unit tests for the pure classifier and aggregator, a MockMvc/IT test for the endpoint (needs
Postgres; check the skip count, per memory `project-backend-suite-skips-its-silently`), and Vitest for the panel

**Target Platform**: The Railway web service plus the SPA

**Performance Goals**: SC-005 is the analysis page under 2 s. A season is ~2.4k multi-game starter-weeks over
~29k games. That's one pass, cached on a fingerprint like spec 036.

**Constraints**: One implementation of "which game was credited" (shared with `PlayerTrendsService.oneGameShare`).
`api.ts` must mirror the records in the same change. No `Map.of` on nullable fields (baseline, median and team are
nullable).

**Scale/Scope**: 3 NBA league rows (2024/2025/2026) and 12 managers, with ~1.2–1.4k provable locks per completed
season

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | Pass. No migration. |
| `api.ts` mirrors Java records | Planned. `LockInReport` and its children are added to `api.ts` in the same change (task T0xx). |
| Never retune a constant to match a guess | Pass. The bands and minimums are hand-set before the payoff table was read, and commented arbitrary. SC-002's bar was amended after measurement, and the amendment is shown visibly, not silent (research R2). |
| `Map.of` with nullables | Watch item. `baseline`, `median`, `q1`/`q3`, `rate` and `manager` can be null. Use records or mutable maps. |
| Ask before committing | Pass. Commit, push and PR wait for Allan. |
| Planning docs aren't specs | Pass. The premise was measured (R2/R3), not assumed from the memory note. |

Post-design re-check: there are no violations. The one judgment call that changes a success criterion is SC-002 (R2),
and it's flagged for Allan.

## Design

### Backend

1. **`CreditedGames` (new, engine, pure static)**: the shared matcher. Given a starter-week's credited value and that
   player's games that week (date-ordered, each with its league-scored points), it returns a `Classification`:
   `LOCK(index)`, `RODE_OUT`, `TIE_NON_LAST`, `TIE_WITH_LAST`, `UNMATCHED`, `SINGLE_GAME` or `NO_GAMES`.
   The 0.005 tolerance is lifted from `PlayerTrendsService.oneGameShare`. `oneGameShare` is then rewritten to call
   `CreditedGames.match`, so its output doesn't change (its existing test is the guard). Game order is
   `(game_date, game_id)`, the same as the research script.
2. **`LockInService` (new, engine)**: `report(LeagueRow, String playerFilter)`.
   - Gate: `sport != NBA` or `settings.game_mode != 1` returns `status NOT_LOCK_IN` with a reason (FR-011). No
     scoring stored returns `NO_SCORING`.
   - Inputs: `breakdownsFor(leagueId, season)`, `seasonPlayerGames(NBA, season)` minus the
     `weights.yml` excluded-game-ids (the same filter as other `player_game` readers), the snapshots for the season,
     and the roster → manager map (`roster_season` for the league).
   - Weeks: only scored weeks. Same rule as `ScoredWeeks`: the current/unscored week is excluded.
   - For each LOCK, it computes the stand-in baseline (the mean league score of the player's same-season games dated
     before the lock's date) and the projection baseline (a snapshot for that `game_id`; both sides scored on
     the restricted map of `ProjectedCategories` for the sport-season). Margin is `(x − b) / b`. It's null when
     `b ≤ 0` or absent, and counted `noBaseline`.
   - `laterGames` is the count of later played games, and `beatenLater` is whether any later game's league score is
     greater than the credited value.
   - Aggregates: per manager and per player, per baseline kind, it gives `n`, median, q1 and q3 (the same quantile
     definition as Python's `statistics.quantiles(n=4)`, exclusive method, so a hand check matches; pin it in a
     test), `rodeOut`, `tieNonLast`, plus league totals of each classification (FR-010). Payoff is per baseline kind
     and per band × `min(laterGames, cap)` cell, with `n` and `rate`, where `rate` is null below `min-cell`.
   - Playoff weeks: included, with each lock carrying `playoff: boolean` from the league's `playoff_week_start`.
     They aren't split out in v1 (spec edge case, decided here).
   - Cache: keyed by league id, on a stamp of `(player_game seasonToken, roster_week_points count + max week,
     snapshot stamp, scoring hash, settings hash)`.
3. **`LockInController` (new, api)**: `GET /api/leagues/{sleeperId}/lock-ins[?player=]`. It's scoped the way
   `ProjectionAccuracyController` is (the same league-access check) and returns 404 for an unknown league.
4. **`LockInProperties`**: `draftsim.lock-in.{min-locks, min-cell, margin-bands, games-left-cap}` in
   `config/weights.yml`, each commented "hand-set, arbitrary".

### Frontend

- `api.ts`: the `LockInReport` types and `fetchLockIns(sleeperId, player?)`.
- `LockInPanel.tsx` on `LeagueAnalysis.tsx`, rendered only when `status !== 'NOT_LOCK_IN'`. It has three blocks:
  - **Managers**: avatar, median margin, IQR bar with the exact values beside it (memory: label the axis), locks n,
    rode-out n.
  - **Players**: sortable by median or n. Below `minLocks`, the row shows "n locks — too few" and sorts last.
  - **When locking paid off**: a band × games-left grid, with "beaten by a later game X% (n)" in each cell.
  - A baseline toggle (Projection / Season-average stand-in) switches the whole panel. It's never mixed. The stand-in
    is the default, because the projection series is TOO_EARLY for most of the first weeks.
  - The caveat lines come from the payload: inferred locks, the excluded counts, "later game = played game only",
    and "describes past weeks, not a prediction".
- The player page has a "Lock-ins" block: the median per baseline kind and a lock list (week, manager, points,
  baseline, margin, later games, playoff badge). When there are none, it says "No provable locks".

## Project Structure

### Documentation (this feature)

```text
specs/044-lock-in-threshold/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/lock-in-api.md
├── plan-review.md        (adversarial review, next stage)
├── tasks.md              (/speckit-tasks)
├── code-review.md, verification.md
└── checklists/requirements.md
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/CreditedGames.java          (new; shared matcher)
├── engine/LockInService.java          (new)
├── engine/PlayerTrendsService.java    (oneGameShare delegates to CreditedGames)
├── config/LockInProperties.java       (new)
└── api/LockInController.java          (new)
config/weights.yml                     (draftsim.lock-in block)
backend/src/test/java/.../engine/CreditedGamesTest.java, LockInServiceTest.java
backend/src/test/java/.../api/LockInControllerIT.java
web/src/api.ts
web/src/components/LockInPanel.tsx (+ .test.tsx)
web/src/pages/LeagueAnalysis.tsx, the player page component
```

**Structure Decision**: The existing backend/web split, with the new code beside its spec 036 siblings.

## Complexity Tracking

None.

---

## Amended after plan review (2026-10-21)

`plan-review.md` (F1–F14, no blockers) was taken in full. Allan's answers to the follow-up questions
added US4 (live Lock/Wait calls) and US5 (a digest tweet card). Research R2/R5 were re-run under the
final rules (research "Amended after plan review"). **Where this section and the Design above
disagree, this section wins.**

| # | Disposition |
|---|---|
| F1 | **Accepted.** The game set drops games whose opponent isn't a season team code, using `NbaGameLines`' rule. That rule is extracted into a shared static so it has one implementation, rather than copied. |
| F2 | **Accepted.** The Cup final is kept: `weights.yml` excluded-game-ids are **not** applied anywhere in this feature, including the stand-in. The spec edge case was amended. |
| F3 | **Accepted.** Games come from `SeasonBoxCache.get(NBA, season).games()`. Cold/warm timing is measured in verification. |
| F4 | **Resolved by Allan**, via US4. The payoff headline is "riding it out beat it", with "in hindsight" (any later game) beside it. Advice comes from R8's thresholds, not from the payoff grid. |
| F5 | **Accepted.** New class `RODE_OUT_MISSED` (credited 0, no 0-point game), separate from `UNMATCHED` (now measured at 0). The default is documented as the last scheduled game, in R3 and in the caveats. |
| F6 | **Accepted.** Stamp = box-cache token + a `roster_week_points` digest (count, sum of starters_points, md5 of the ordered players_points text) + `ScoredWeeks` final weeks + snapshot stamp + scoring hash + game_mode/playoff format + `roster_season` manager digest. Constants are startup-bound and not stamped. |
| F7 | **Accepted.** `ScoredWeeks.of(...).finalWeeks()`, and when the league isn't complete, also drop weeks ≥ `currentLeg`. There's a test for a non-complete league with no POINTS rows. |
| F8 | **Accepted.** The gate needs `isSleeper()`, NBA, `LeagueRepository.gameMode(id) == 1` (new, `OptionalInt`, never defaulted), **and** this report's own share ≥ `draftsim.player-trends.one-game-share`. Each has its own reason: `NOT_SLEEPER`, `NOT_NBA`, `GAME_MODE`, `SHARE_BELOW_THRESHOLD`. |
| F9 | **Accepted.** `CreditedGames.match` takes the list it's given. `oneGameShare` keeps raw `games()` and all weeks, and its numerator stays "any game matches", so a zero credit with no zero game stays unmatched there and the share stays byte-equal. Add tie and All-Star test cases **before** the rewrite, and record 211/212 shares before and after; they must be equal. |
| F10 | **Accepted.** `draftsim.lock-in.min-prior-games: 5` (arbitrary). The measured effect is in R2 v2 (130 / 127 excluded). |
| F11 | **Accepted.** Re-ran as `measure_v2.py`. The hand checks in quickstart use v2, which now matches the planned rules. |
| F12 | **Accepted.** The classes are disjoint. The UI shows "provable locks = lock + tieNonLast (the latter have no margin)". |
| F13 | **Accepted as expected behaviour.** `min-locks` stays at 10. |
| F14 | **Accepted.** The player page gets a new tab, `lock-ins` (TABS in `PlayerPage.tsx`), under the `stats` route gate. `?player=` returns only that player's summary + locks + thresholds, not the whole report. There's one status vocabulary (`n` + null median, never a "TOO_EARLY" string for history). The playoff flag comes from `playoffFormat`. Names come from `RosterOwners.ownerNames` (nullable). The quantile method is pinned (exclusive, tested at n = 10 and 11). The mid-season ownership and scoring-change assumptions are in the spec. |

### Design additions for US4 and US5

5. **`LockThresholds` (engine, pure)**: `fit(List<Double> margins, double pMissed, int cap)`
   returns V1..V_cap (cap 4), as in research R8. It's computed by `LockInService` from **every
   completed season** in `chainBySleeperId` (league `complete()`), pooling the margins and the
   counts behind p. It publishes `{gamesLeft, threshold, n}` and `seasonsUsed`. With no completed
   season, the threshold status is `NO_HISTORY`.
6. **Live calls**: `LockInService.liveCalls(LeagueRow, LocalDate asOf)`, for a non-complete league
   with a `currentLeg`.
   - Starters come live from Sleeper `/league/{id}/rosters` through the existing `SleeperClient`, the
     way `LineupCheckService` does. A failure gives the status `ROSTERS_FAILED` and is never thrown.
   - This week's played games come from the box cache for the current season. Each call judges the
     **most recent** played game this week. ~~best-margin game so far~~ (amended after /speckit-analyze F1,
     2026-10-22: only the latest game can be locked).
   - Games left are `sport_schedule` rows for `player.team` in that week, dated after `asOf`, that
     aren't complete or postponed.
   - The baseline is the stand-in (≥ `min-prior-games` this season, before the judged game), else
     the previous season's full average (`baselineKind: LAST_SEASON`), else `TOO_EARLY`.
   - The call is `LOCK` iff margin ≥ threshold[min(gamesLeft, cap)], otherwise `WAIT`. With
     gamesLeft = 0, no call is made.
   - The result is cached per (league, asOf) for a short TTL (60 s, arbitrary), so the analysis page
     can't hammer Sleeper.
7. **Endpoint**: `GET /api/leagues/{sleeperId}/lock-ins` gains `thresholds` and, for a live league,
   `live: {week, asOf, status, calls[]}`. `?live=false` skips the Sleeper call. The player tab passes
   it.
8. **Digest (US5)**: `AdminDigestService.SportDigest` gains `lockInCalls: List<LockInCard>` (NBA only,
   empty for NFL; mirrored in `api.ts`). There's one card per current-season Sleeper NBA league that
   passes F8's gate, built from `liveCalls(league, digestNight + 1 day)` and restricted to `LOCK`
   entries whose latest game was **on the digest's night**. The tweet text is built server-side and
   truncated by whole entries to ≤ 280 characters. The card status is one of
   `OK | NONE_TONIGHT | NO_HISTORY | ROSTERS_FAILED`. The card goes through the digest's existing
   per-section failure isolation, so a broken card can't take down the digest.
9. **Config**: `draftsim.lock-in` = `{min-locks: 10, min-cell: 15, min-prior-games: 5,
   margin-bands: [0, 0.10, 0.25, 0.50], games-left-cap: 3, threshold-cap: 4, live-ttl-seconds: 60}`,
   every value commented "hand-set, arbitrary". games-left-cap (payoff columns) and threshold-cap
   (the recursion) are separate on purpose: 4+-game weeks are too few for their own payoff column
   but cheap for the recursion.

### Verification additions

- **Out-of-sample threshold check**: fit on 2024 alone, apply to 2025's starter-weeks, and report the
  rule's mean against actual credited points. Report the number, whatever it is. It is not a gate.
- **oneGameShare byte-equality** on 211/212 before and after (F9).
- **The live calls**, on real 2026 week 1 data (210) with the real Sleeper rosters call, plus the
  digest card for the 2026-10-20 night.

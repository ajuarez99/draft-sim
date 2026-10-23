# Code review: spec 044 lock-in threshold (bug hunt)

**Date**: 2026-10-22. **Scope**: the uncommitted tree in `.claude/worktrees/044-lock-in-threshold` compared with `origin/main`
(every modified and untracked file listed in `git status`). This was a bug hunt, not a style pass.

**How it was checked**:
- I read every new and changed file.
- I ran a throwaway JUnit test, `ZzReviewScratchTest`. It cloned `LockInLiveTest`'s fixture and was deleted afterwards.
- I started this worktree's `bootRun` on port 8145 against the local Postgres on 5433 and curled `/lock-ins` for 210, 211
  and 212. The 210 request made the real Sleeper rosters call. I stopped the server afterwards.
- I ran read-only SQL.

Each finding says whether it was **measured** (I ran it) or **read** (inferred from the code).

## Findings

### B1. High: live calls judge an in-progress game's partial box score as "the latest game"

- **Where**: `engine/LockInService.java:470-472` (`computeLive`: `latest` is the last box row dated on or before
  `playedThrough`, with no check that the game is over).
- **Evidence (measured)**: The local `player_game` for 2026 holds game `1392938570821406720` (NYK at PHI, 2026-10-20). Its
  17 rows have at most 10.07 minutes and 48 points between them. That's a box fetched mid-game (`sport_schedule` still says
  `in_game` for it). `GET /lock-ins` for 210 (asOf 2026-10-22, real Sleeper rosters) served 6 calls on that game, all
  `WAIT`:
  - Embiid 7.0 pts, −74% against last season's average
  - Maxey 9.0, −70%
  - J. Brown 5.5, −80%
  - LeBron 3.0, −88%
  - KAT 3.0, −89%
  - Anunoby 3.0, −85%

  Each one compares a quarter of a game against a full-game average.
- **Failure scenario**: This happens on any game night, whenever the box ingest runs while games are live. The panel tells a
  manager to Wait on a game that isn't finished. It can also tell him to Lock on a hot first half. It also lands in the
  digest path if the digest's own READY gate ever passes a night with a partial game (that gate checks the night, not
  each game). Today's calls are all WAIT only by luck of the numbers.
- **Fix**: Only judge a game that is over. Require its `sport_schedule` row (by `game_id`) to be `complete`, or fall back to
  "dated before `asOf` and the box was fetched after the night ended". Otherwise skip the player, or emit `TOO_EARLY`
  with a reason. Add a `LockInLiveTest` case with an `in_game` row for the judged game.

### B2. High: a call is made on a game that can no longer be locked (stale or DNP latest game)

- **Where**: `engine/LockInService.java:472-481`. Games left are `NextGames.forTeam(..., asOf, ...)` filtered to the week and
  after `latest.date()`. Nothing checks whether the team played (or is playing) a game between `latest.date()` and now.
- **Evidence (measured)**: A throwaway test on `LockInLiveTest`'s fixture:
  - **Scenario A.** LAL has `complete` games on day 20 and day 21, and `pre_game` games on days 22 and 23. Player `pa` has
    a box row only on day 20 (+100%). He sat out day 21, or day 21 isn't ingested yet. `liveCalls(league, DAY22)` returns
    `LOCK` on the **day-20** game with `gamesLeft=2`.
  - **Scenario B.** The day-21 game is `in_game` and asOf is day 21. The result is `LOCK` on the day-20 game with
    `gamesLeft=2`, and the in-progress game is counted as "left".
- **Failure scenario**: The spec's rule (analyze F1) is that only the latest game can be locked. Once the team's next game
  has tipped, the earlier score is gone, and the player's current game is the DNP 0 or the game in progress. The panel
  still prints "Lock" on a dead game.
  - The DNP case is permanent for the rest of the week: a player who sat out his team's last game keeps a call on his
    previous one.
  - The not-yet-ingested case lasts from tip-off until the box ingest.
  - Scenario B is every game night, because `in_game` isn't settled, so it counts as left.
- **Fix**:
  1. In `computeLive`, find the team's schedule rows in the week dated after `latest.date()` and on or before `asOf`. If
     any of them is settled or `in_game`, or is dated before `asOf` at all, the latest box game is no longer lockable,
     so skip it (or give a distinct call such as `TOO_LATE`).
  2. Don't count an `in_game` row as "left".

  The digest form (`playedThrough = night`, `asOf = night + 1`) is only safe if this check is done against `asOf` rather
  than `playedThrough`. Pin it with scenarios A and B as tests.

### S1. Medium: `?player=` with the default `live=true` returns the whole league's live calls

- **Where**: `engine/LockInService.java:278-292` (`report` attaches `liveCalls` after `view` narrowed the report), and
  `view` at `:587-595`.
- **Evidence (measured)**: `GET /lock-ins?player=<id>` on 210 returned `players: []` and `live.calls` with 11 entries,
  covering every roster's starters.
- **Failure scenario**: This breaks contract F14 ("only that player's summary, locks, thresholds and caveats"). It also
  makes a Sleeper roster call that a player-scoped request has no use for. Nothing leaks, because scoping is the same, but
  any future caller that passes `player` without `live=false` gets a league payload and a Sleeper round-trip. The player
  page does pass `live:false`, so it isn't affected today. Also, `totals` on a `?player=` answer are the league's totals,
  not the player's (read). That's undocumented in the contract.
- **Fix**: When `playerFilter` is set, either skip live, or filter `live.calls` to that player. Document `totals` as
  league-wide, or zero them.

### S2. Medium: the "<0" payoff row is labelled "below average" under the Projection baseline

- **Where**: `web/src/components/LockInPanel.tsx:189`.
- **Evidence (read)**: `band === '<0' ? 'below average' : ...` is not keyed on `kind`. The paragraph above the grid does
  switch ("season average" or "projection").
- **Failure scenario**: With the Projection toggle on, the bottom row says "below average". The panel's own rule (FR-004,
  the file's header comment) is that the two baselines never share a label.
- **Fix**: Use `kind === 'STAND_IN' ? 'below average' : 'below projection'`. Optionally render "50+" as "50%+" rather
  than "50+%".

### S3. Low-medium: a failed roster read is never cached, so a Sleeper outage turns every page load into a Sleeper call

- **Where**: `engine/LockInService.java:424-425`.
- **Evidence (read)**: `ROSTERS_FAILED` (and an empty roster list) skips `liveCache.put`. The build notes say this is
  deliberate.
- **Failure scenario**: When Sleeper rate-limits or times out, every analysis-page load by a league member retries
  synchronously inside the `/lock-ins` request. That adds Sleeper's timeout to the panel each time, and it amplifies the
  load that caused the failure. The success path is capped at 1 call per 60 s per league, but the failure path isn't capped.
- **Fix**: Cache failures with a short negative TTL (for example 10–15 s, labelled arbitrary). That keeps "the next minute
  retries" without one retry per request.

### S4. Low: the live week is `currentLeg`, not the week the dates belong to

- **Where**: `engine/LockInService.java:411-415` and `:470`.
- **Evidence (read)**: `week = leagues.currentLeg(id)`, read from `settings_json.leg`, which is only written by
  ingest/refresh. Box rows and schedule rows are then filtered to that week.
- **Failure scenario**: On a Monday before the league row is refreshed, `leg` still names last week. The panel then says
  "No starter has played this week with games still to come" for the whole first day of the new week. That's a silent
  empty state, not a wrong call.
- **Fix**: Take the week from the schedule row containing `asOf` (`sport_schedule.week` where `game_date = asOf`, or the
  next one), and fall back to `currentLeg`. At minimum, put `week` in the empty-state sentence so a stale leg is visible.

### N1. The cold `/lock-ins` for the 2026 league measured 2.49 s on a fresh JVM

- **Evidence (measured)**: On a fresh `bootRun`, the first request was 210 with live. That computes 212 and 211 cold for the
  thresholds, plus the Sleeper call. Warm it was 0.07 s.
- **Impact**: The panel fetches on its own, so the page isn't held. But the first visitor after a deploy waits about
  2.5 s for the panel, which is over SC-005's 2 s if that criterion is read per request.
- **Fix**: Optionally warm the chain on startup. At minimum, record this number in verification.md next to the T038
  figures (which were taken with the earlier seasons already cached).

### N2. The tweet can be header-only while the card says OK

- **Where**: `engine/AdminDigestService.java` `tweet()`.
- **Evidence (measured)**: With a 260-character name, the tweet is `"Lock-in calls after Oct 20:"` (27 characters) and the
  status is `OK`.
- **Other measured cases**:
  - 30 realistic entries produce 271 characters (4 entries), so the limit holds.
  - A margin of −0.004 prints "0% vs avg", which is fine.
- **Fix**: If not even one entry fits, truncate the name instead of dropping the entry, or answer `NONE_TONIGHT`. This is
  not realistic with real names.

### N3. The admin entry line prints the bar as `-5%`

- **Where**: `web/src/pages/AdminDigest.tsx`, the `LockInCardBox` entry line: `bar ${Math.round(threshold*100)}%`.
- **Evidence (read)**: The rest of the feature uses `signedPct` (a real minus sign, and `+` on positives). This line uses a
  hyphen and no `+`. It's cosmetic.

### N4. The panel keeps the previous league's report on screen while a new league loads

- **Where**: `LockInPanel.tsx:456-470`.
- **Evidence (read)**: `setR(null)` isn't called when `sleeperLeagueId` changes, so the old league's managers show until the
  new response arrives. This only matters if the route reuses the component across leagues.

## Checked and found correct

- **`api.ts` against the Java records** (read): checked field by field, including nullability, for these records: `Totals`
  (12), `BaselineFigure`, `LockSummary`, `PayoffCell`, `StandInFigure`, `ProjectionFigure`, `Lock` (17), `ThresholdPoint`,
  `LockInThresholds`, `LatestGame`, `LiveCall` (13), `LiveCalls`, `LockInReport` (19), `LockInCard` and
  `SportDigest.lockInCalls`. There's no drift. The status strings match the constants.
- **NaN and Infinity** (measured and read): the JSON for 210, 211 and 212 contains none. `bestLater` can't be −∞ for a
  LOCK, because a later game always exists. `fit` only runs with `total > 0`.
- **Invariant** (measured): `lock + rodeOut + rodeOutMissed + tieNonLast + tieWithLast + unmatched == multiGame` holds for
  211 and 212. The totals match R2 v2 (2025: 1188 / 894 / 14 / 10 / 62 / 0; 2024: 1351 / 949 / 44 / 14 / 70 / 0).
- **Thresholds** (measured): 210 pools [2024, 2025] with n = 13,210, which is 7018 + 6192, matching the build notes. 212
  pools [2024] only. `chainBySleeperId` walks backwards only (read), so a later season never feeds an earlier one. The
  recursion and the use of `V[min(left, cap)]` match R8. The quantiles match Python's exclusive method line by line.
- **Scoping** (measured): anonymous requests get 404 (`canSee(null)` is admin only), an unknown league gets 404,
  `live=nope` gets 400, and a member gets 200. This is the same check as projection-accuracy. Anonymous users can't
  trigger Sleeper calls, and members are capped at 1 per 60 s per league on success (see S3 for failures).
- **Cache stamp (F6)** (read): every input `compute` reads is stamped: box token, the rwp digest (which covers rewrites in
  place), scorable weeks (which cover `complete` and `currentLeg`), snapshot stamp, scoring, mode, playoff start, the
  roster-to-manager map and owner names. Player display names aren't stamped, which only affects display. Both caches are
  synchronized access-order LHMs, and concurrent misses only duplicate work.
- **`Map.of` with nullable values**: none in the new code. Every `Map.of` has constant or non-null values.
- **Gate order and the 210 path** (measured): 210 is `NO_SCORED_WEEKS` (leg 1 drops week ≥ 1) and still carries thresholds
  and live, as the build notes intend.
- **Digest**:
  - Read: the lock-in block runs only when `ready && NBA`, it's wrapped in its own try/catch per source and per league,
    and the 3-argument constructor defaults to an empty list (SC-006).
  - Measured: the tweet stays ≤ 280 characters.
- **`oneGameShare` delegation** (read): the numerator is unchanged (`matchedAGame` covers LOCK, RODE_OUT and both tie
  kinds, the same as "any game matches"). The byte-equality was measured by the build (T037) and wasn't re-run here.
- **LAST_SEASON baseline** (read): uses the nearest earlier chain season, rescored under this league's scoring, and needs
  at least min-prior-games games.
- **Traded player** (read): the box games are his own, and "left" uses his current team's schedule, which is the right team
  for the games still to come.

## Verdict

**Not ready to ship US4 (the live calls) as is.** B1 and B2 both make the live panel give Lock/Wait advice on a game that
isn't the one that can actually be locked: either a partial in-progress box score (measured on real local data today) or
a game already superseded by a later tip-off or a DNP (measured with a fixture). Both fixes are small, local to
`computeLive`, and testable with the existing `LockInLiveTest` fixture.

US1–US3 (the retrospective report, the player tab, the payoff grid) and US5's digest card (protected by the READY-night
gate) showed no correctness defects. S2 is a one-line label fix. S1, S3 and S4 should be fixed but don't block.

## Fixes (2026-10-22)

Each fix was written test-first and run red before the fix (7 red in `LockInLiveTest`, 3 in vitest; the controller test
failed to compile against the old `boolean live` signature, so its "red" is a compile failure, not an assertion).

- **B1 Fixed.** `LockInService.lockableLatest` requires the judged game's `sport_schedule` row to be `complete`
  (matched by game id, else by team and date). A game still `in_game` gets no entry (omitted, not TOO_EARLY: TOO_EARLY
  means "no baseline yet", a different thing; no `api.ts` change). Test: `LockInLiveTest.aGameStillInProgressIsNeverJudged`.
- **B2 Fixed.** The same method returns false when the team has a `complete` or `in_game` game dated after the judged
  one and on or before `asOf` (box row or not). `gamesLeft` now skips `in_game`. Omitted entry, documented in
  `contracts/lock-in-api.md`. Tests: `aPlayerWhoseTeamHasSincePlayedAnotherGameIsNotJudgedOnTheEarlierOne` (scenario A),
  `aNextGameInProgressSupersedesTheEarlierOneAndIsNotCountedAsLeft` (scenario B), `gamesLeftCountsOnlyGamesNotYetStarted`.
- **S1 Fixed.** `live` defaults to false when `player` is set; with explicit `live=true`, `live.calls` is narrowed to that
  player. `totals` stay league-wide, now documented (contract amendment dated 2026-10-22). Tests:
  `LockInLiveTest.withAPlayerFilterTheLiveBlockCarriesOnlyThatPlayersCall`, `LockInControllerLiveDefaultTest` (3).
- **S2 Fixed.** Row label follows the baseline ("below projection"). Test: `LockInPanel.test.tsx` "labels the bottom row by the selected baseline".
- **S3 Fixed.** Failed roster reads are cached for `live-failure-ttl-seconds` (15, in `weights.yml`, commented hand-set/arbitrary,
  mirrored in `LockInProperties`, validated). Test: `aFailedReadIsCachedBrieflySoAnOutageIsNotOneSleeperCallPerRequestThenRetries`
  (replaces the old "not cached" test, which asserted the behaviour being changed).
- **S4 Fixed.** `liveWeek`: the schedule week of the first counted, non-exhibition game on or after the judged date
  (`playedThrough`), falling back to `leg` with no schedule. Test: `theLiveWeekIsTheScheduleWeekOfTheDateNotAStaleCurrentLeg`.
- **N1 Not fixed.** Cold-JVM 2.5 s is a measurement, not a defect; a startup warm-up is a scope call. Re-measured this pass at
  2.7 s on the first 210 request after boot.
- **N2 Not fixed.** Needs a ~260-character player name; not realistic, and the tweet stays within 280.
- **N3 Fixed.** The admin entry line uses `signedPct` (`bar +7%`). Test: `AdminDigest.test.tsx` lock-in card (assertion updated to `bar +7%`).
- **N4 Fixed.** `LockInPanel` calls `setR(null)` on league change. Test: "clears the previous league's report while the next league loads".

**Results**: backend `./gradlew.bat test`: 2211 tests, 1 failed (the known `BoardRepositoryLatestBeforeIT`), 6 skipped.
Web: `tsc -b` clean, vitest 2374/2374, `npm run build` OK.
**Live, league 210 (member session via `X-Sleeper-User`, real Sleeper rosters, port 8146)**: live `OK`, week 1, 5 calls
(1 LOCK, 4 WAIT), all on the completed DET at BOS game (Paul George LOCK; Thompson, White, Cunningham, Tatum WAIT). The
in-progress NYK at PHI game is no longer judged (before: 6 WAIT calls on it). `?player=` now returns `live: null`.

## Live-verification polish (2026-10-22)

- **P1 Fixed** (projection baseline, no projection-backed locks -> one empty-state line, Managers/Players/payoff hidden, left-out counts kept): `LockInPanel.test.tsx` "P1: a season with no projection-backed locks shows one empty-state line instead of 0-lock rows". The payload carries no snapshot-date field, so the generic wording is used (no hard-coded date).
- **P2 Fixed** (`50+%` -> `50%+`, `0-10` -> `0–10%`): the server labels carry no unit ("0-10", "50+"), so the fix is the frontend formatter `bandLabel`; Java and `api.ts` unchanged. Test: "P2: the top band reads 50%+ and a middle band 0–10%".
- **P3 Fixed** (strip note "Thresholds use the season-average stand-in..." when Projection is selected; threshold maths untouched): "P3: with the Projection toggle on, the threshold strip says it still uses the stand-in".
- **P4 Fixed**: `LockInService.lockableLatest` treats a counted schedule row dated strictly before asOf as started whatever its stored status; B1 (judged game must be `complete`) unchanged. Games-left counting already excluded dates before asOf via `NextGames.forTeam`, now pinned by a test. Tests (`LockInLiveTest`): `aGameDatedBeforeAsOfCountsAsStartedEvenIfItsStoredStatusIsStillPreGame` (red before the fix, green after), `aStalePreGameRowDoesNotCountAsAGameLeft`.
- **P5 Fixed**: tweet wording is "+42% vs season avg" (STAND_IN) / "+184% vs last season's avg" (LAST_SEASON); the card's entry list in `AdminDigest.tsx` now shows the margin and the same baseline name. Whole-entry truncation at 280 unchanged. Tests: `AdminDigestServiceTest.theCardKeepsOnlyLockCallsFromTheNightByMarginDescending` (updated), `theTweetNamesLastSeasonWhenThatIsTheBaseline`; `AdminDigest.test.tsx` lock-in card tests (updated). Note: the P5 Java test and fix were written together, so its red run was not observed separately.

**Results**: backend `./gradlew.bat test`: 2214 tests, 1 failed (the known `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`), 6 skipped. Web: `tsc -b` clean, vitest 2377/2377, `npm run build` OK.

# Code review: spec 043 (morning tweet digest), bug hunt

**Date**: 2026-10-20 · **Scope**: the uncommitted build in worktree `043-tweet-digest-admin`
(`git status` at review time: 7 modified, 8 new files). This was a bug hunt, not a style pass.
No source file was edited.

## What was run (verified) vs read (inferred)

- `./gradlew test --tests '*AdminDigest*'`: `AdminDigestServiceTest` 19/19 and
  `AdminDigestControllerIT` 4/4 passed, **0 skipped**. I read the skip count in the XML reports, not
  just "BUILD SUCCESSFUL".
- `npx vitest run src/digest src/pages/AdminDigest.test.tsx`: 30/30 passed. `npx tsc --noEmit -p tsconfig.json`
  was clean.
- I ran the new SQL against the real 5433 DB:
  - `topRanked`, both sports: the newest capture is taken **per sport and source**
    (nba 2026-10-20 with 959 rows; nfl 2026-10-20 with 2002 rows). Ranks run 1..N with no ties
    (`count(distinct adp) = count`). The unique key `(player_id, source, captured_on)` rules out
    duplicate player rows. The top 15 come back sensible for both sports.
  - `seasonsOn`: 2026-04-01 gives NBA 2025, 2025-04-13 gives NBA 2024, 2026-10-20 gives NBA 2026,
    and 2025-11-02 gives NFL 2025. All correct.
- Data checks, measured:
  - Team codes agree across `sport_schedule`, `player.team` and `player_game.opponent` for both
    sports. The only extras are All-Star codes STP/STR/SHQ/CHK.
  - No `player_game` row is a non-game. Every NFL row has `gp > 0`, and every NBA row has `sp` or
    `min > 0`. So "has a row" really does mean "played".
  - NFL DEF and K rows carry `pts_ppr` (184/186 and 183/186), so a DEF or K among the trending
    adds scores correctly.
- Not run: I did not hit the live server. The 8080 instance rejected every local admin token I
  tried, and I was told to leave it alone. Every finding below about service behaviour comes from
  reading the code against real data rows, and each one says so.

The `api.ts` types match the Java records field for field: names, nullability, `Instant`/`LocalDate`
as strings, `NO_PERIOD` in the outcome union, and `trending: … | null`. Jackson has no
`non_null` inclusion configured, so nulls are sent as `null`. `Map.of` appears only with non-null
constants. The response maps are built mutably. The controller's 400 body, its per-sport error
isolation in refresh-stats, and the gating (`/api/admin/**` interceptor; CORS allows POST and
`X-Admin-Token`) all look correct.

---

## B — bugs, must fix

### B1. The NFL "Week N leaders" post states one night's leaders as the week's leaders
`web/src/digest/tweetText.ts:199`; the data comes from `AdminDigestService.java:275`
(`positionLeaders(played)`, where `played` holds only the covered **night's** rows).

**Failure**: on Tuesday 2026-10-20, the digest covers MNF 2026-10-19, which is one game
(`player_game` NFL week 6 on that date: 1 game). The card posts
`🏈 Week 6 leaders: QB <MNF QB> … · RB … · WR … · TE …`. Sunday 10-18 had 12 games and
Thursday 10-15 had one more, and none of them are in it. Even on Monday morning, a "Week 6 leaders"
post leaves out Thursday and MNF. So the post makes a false claim to followers almost every week.
This is bug class 5: the numbers are right, but what they're labelled as is wrong. The spec's
example header (`Week 7 leaders`) carries the same flaw, so this isn't a builder slip, but the
text is still wrong.

**Status**: CONFIRMED by reading the code against the real NFL date and game counts above. The
rendered text wasn't captured live.

**Fix (choose one)**: (a) label the night: `🏈 Week 6, Monday night leaders:` or
`🏈 Leaders last night (Week 6):`, built from `sd.date` and `when`; or (b) build week-level
leaders from `forWeek` rows. Option (a) is the small one and fits the rest of the page. Add a
test asserting that the header names the night.

---

## S — should fix

### S1. `nextGameDate` skips games tonight
`AdminDigestService.java:217-218` and `:330` (`!g.date().isAfter(reference)`, with
`reference = today`).

**Failure**: Thursday 2026-10-22 morning (NY), NFL: yesterday 10-21 had no games, so the status
is `NO_GAMES_LAST_NIGHT`. The schedule has a game on 10-22 (TNF, 1 game) and the next on 10-25
(12 games). Because `isAfter(today)` excludes today, the page says
"No games last night. … Next games: Oct 25." while there's a game tonight. The same happens to
NBA on any morning after an off night. The contract does say "after today", so the code follows
it, but the contract is the thing that's wrong.

**Status**: CONFIRMED by reading the code against the real `sport_schedule` rows (10-22: 1, 10-25: 12).
The unit test fixture can't catch it, because no test schedules a game on "today".

**Fix**: in default mode, use `!g.date().isBefore(today)` (today counts). Keep "after `date`"
for `?date=`. Amend the contract line, and add a test that puts a game on today.

### S2. Out-of-order loads and stale cards while a reload runs
`web/src/pages/AdminDigest.tsx:254-271` and `:352-356`.

**Failure**:
1. Nothing discards a superseded response, because there's no request id or AbortController.
   Say a user picks a heavy READY past date, then clicks "Last night" before it returns. If the
   past-date response lands second, it overwrites the default digest. The cards take their labels
   from `sd.date`, so they say "on Apr 12". But the toolbar shows no date, and `statusMessage`
   uses the *current* `target`. Chrome also fires `onChange` for each year digit typed
   (0002 → 0020 → 0202 → 2026), so several requests race on every date edit.
2. While a new date loads, the old date's cards stay on screen with Copy enabled
   (`loading && !digest` only shows "Loading…" when there's no digest at all). Meanwhile
   `target` has already switched, so a status line can name the new date over the old data.

**Status**: INFERRED from the code. No browser repro was run.

**Fix**: keep a `latestRequest` ref or counter and drop a response that isn't the latest; clear
`digest` (or dim it and disable Copy) while `loading`. Optionally debounce the date input, or
only load on blur or Enter.

### S3. A past date with no stored rows falls back to the *current* season's schedule
`AdminDigestService.java:179-183`, `:203` and `:208-213`.

**Failure**: `?date=` with no `player_game` rows uses `season = current`, so the schedule
lookup and `nextGameDate` use the wrong season. Examples:
- On prod, or after a partial ingest, take a 2025 NBA date whose week was never fetched (NBA 2025
  regular season, schedule stored). The page answers `NO_GAMES_ON_DATE`
  ("No games on Mar 1. Next games: Oct 20"), when the right answer is `NOT_FETCHED`.
- NFL 2025 dates with no rows (no NFL 2025 schedule is stored at all) say
  "Next games: Sep 9" (2026) after, for example, Wed 2025-11-05.

Locally every scheduled NBA date has rows (checked), so it doesn't show up here. Prod coverage
differs (see the memory notes on partial last weeks).

**Status**: INFERRED; the code path was read. The unit fixture's `schedule(sport, season)`
ignores `season` (`AdminDigestServiceTest.java:130`), so no test can see this.

**Fix**: when `seasonsOn` is empty, resolve the season from `sport_schedule` (the season whose
schedule contains the date). If that's empty too, return `NO_GAMES_ON_DATE` with
`nextGameDate = null`, rather than a different season's next date. Also make the fake schedule
season-aware.

### S4. "Fetch latest stats" reports success when every week failed
`AdminDigestController.java:94-98`, `web/src/pages/AdminDigest.tsx:289-297`.

**Failure**: if Sleeper is down, `refreshSportSeason` catches each week's failure
(`weeksFailed++`) and returns normally. The page then shows "NBA: 0 weeks fetched, 0 games",
which reads as "nothing new", not "it failed". `weeksFailed` is sent but never shown, and
`scheduleStoreFailed` is dropped entirely. Under this repo's honesty rule, a failure must not
look like an empty result.

**Status**: INFERRED, from reading `PlayerGameIngestService.doRefresh` (it catches per week and
increments `failed`).

**Fix**: show `, N week(s) failed` when `weeksFailed > 0`. Pass `scheduleStoreFailed` through
(and into `DigestRefreshResult` in `api.ts`, same change), or fold it into `error`.

---

## L — low

- **L1. A future `?date=` says `NOT_FETCHED`** (`AdminDigestService.java:208-210`). For
  2026-12-01, the page says "stats haven't been fetched yet. Use Fetch latest stats", and fetching
  can't help. Suggest a `NOT_PLAYED_YET` wording when `target >= today`, or reject future dates.
  INFERRED.
- **L2. `NO_SEASON` drops trending** (`:184-187` passes `trending = null`). Contract F16 says
  default-mode trending rides along "whenever a snapshot exists". This matters only when
  `sport_trending_fetch.league_season` is null, which in practice means trending was never
  fetched either. INFERRED.
- **L3. `Player.primary()` returns WR for an empty position list** (`domain/Player.java:19`). The
  digest's NFL filters (`:414`, `:430`) would then count an unmapped NFL player (LB/DL/OL) as a WR.
  It's latent: I MEASURED that no stored NFL or NBA player who has game rows has an empty position
  list (IDP rows don't resolve to `player` at all). Guard with `positions().isEmpty() → skip` if
  the player table ever widens.
- **L4. `spotlightCard` isn't fitted to 280** (`tweetText.ts:174-180`). I CONFIRMED with a scratch
  probe that a 300-character name gives 331. Real names can't reach that, and the counter turns
  red, but FR-004 says "as generated".
- **L5. `fitToLimit` can return a header-only card** (`shown: 0`, `tweetText.ts:112-116`) that is
  still copyable as a tweet. I CONFIRMED this with `fitToLimit('h', [])`. It's unreachable with
  real line lengths. Suggest returning null when `kept === 0 && lines.length > 0`.
- **L6. Key-player notes print raw doubles** (`AdminDigest.tsx:152`, `${k.entry.points}`), while
  the cards use `formatPoints`. They're single stored values today, so it's cosmetic.
- **L7. The copy-failed hint vanishes after 2 s** (`AdminDigest.tsx:86` resets `copyState` to
  idle). The "select it by hand" advice disappears before it's useful. Keep `failed` until the
  next click.
- **L8. The edit-reset comment doesn't match the behaviour** (`AdminDigest.tsx:21` says reloading
  restores the generated text). Cards are keyed by their generated text, so a reload (Fetch latest
  stats) that produces the same text **keeps** the edits, and one that changes the text silently
  throws them away. Both are defensible; make the comment match.
- **L9. `topRanked` doesn't join on sport** (`BoardRepository.java:150`:
  `join player p on p.id = s.player_id` with no `and p.sport = s.sport`). It's harmless today:
  a mismatched row would fail to resolve in `byIds(sport, …)` and drop out. It's still cheap
  defence.
- **L10. A free agent among the trending adds becomes "DNP"**, inherited from
  `PlayerSpotlightService.trending`: `p.team() == null` falls through to `DID_NOT_PLAY`. The tweet
  then says "— DNP" for a player with no team. It's rare (none in today's snapshots).
- **L11. Test gaps** (bug class 1):
  - The fake's schedule is season-blind (`AdminDigestServiceTest.java:130`).
  - No test covers `nextGameDate` with a game today (S1), a superseded load (S2), or the leaders
    header naming its night (B1).
  - Ordering tests are otherwise good: top performers by points then id, true trending ranks,
    and key-player rank order.

---

## Fixes applied (2026-10-20, after this review)

| Finding | Status | Fix |
|---|---|---|
| B1 | fixed | `positionLeadersCard` header now names the night from `sd.date`: `🏈 Sunday's leaders (Wk 7):`, `🏈 Monday night leaders (Wk 6):`, `🏈 Leaders:` with no date. Weekend days use `'s`, other days use `night`. No other header implies a week: the other cards use `when` ("last night" / "on Apr 12"). Tests: `names the night…`, `says "night" for a Monday game…`. |
| S1 | fixed | `nextGameDate(..., inclusive)`: default mode counts today's games; `?date=` stays strictly after. Contract amended visibly ("Amended after code review"). Test: `aGameTonightIsTheNextGameInDefaultModeButNotAfterAnExplicitDate`. |
| S2 | fixed | `AdminDigest.tsx`: a request counter drops superseded responses and failures. The digest is cleared while a load runs (no stale cards or Copy). The digest and its requested date are stored together, so status text always names the date of the data on screen. Test: out-of-order load in `AdminDigest.test.tsx`. Not done: debouncing the date input; the counter makes it unnecessary for correctness. |
| S3 | fixed | `seasonForDateWithoutRows`: current or previous season, whichever schedule lists a game that day (→ `NOT_FETCHED`), else whichever schedule's span covers it, else the gap before the current opener → current. If no known schedule covers the date, `nextGameDate` is null. The unit fake's schedule is now keyed by sport and season. Tests: three new service tests. |
| S4 | fixed | The page shows `N week(s) FAILED` and `schedule not saved`. `PlayerGameIngestService.Result.scheduleStoreFailed` exists, so the controller now passes it through (`scheduleStoreFailed` in the refresh-stats row, `DigestRefreshResult` in `api.ts`, contract). Test: S4 fetch-note test (web). The controller IT has no refresh-stats test, and the real refresh calls Sleeper, so the controller path is **not run**; it is read-only plumbing of one boolean. |
| L1 | fixed | New status `DATE_IN_FUTURE` (service, `api.ts`, contract, page message "hasn't happened yet"). It applies only when no rows exist and the date is after today ET. Test added in the service and web tests. |
| L2 | fixed | `NO_SEASON` returns the trending card in default mode (or `TRENDING_NOT_HISTORICAL` for a chosen date). Test added. |
| L3 | fixed (trivial) | The NFL top and leader filters skip players with an empty position list. Test added. `Player.primary()` itself is unchanged. |
| L4 | not fixed | The spotlight card is a single sentence that can't shrink. The counter turns red, and no real name approaches the limit. |
| L5 | fixed | `fitToLimit` and `positionLeadersCard` return null instead of a header-only card, and the page shows a "Nothing to post for …" note. Tests added. |
| L6 | fixed | Key-player notes use `toFixed(1)` for points. |
| L7 | fixed | The "Copy was blocked" hint stays until the next copy or edit; only "Copied" fades. Test added. |
| L8 | fixed | The comment now describes the keyed-by-text behaviour. |
| L9 | fixed (trivial) | `topRanked` joins `player` with `and p.sport = s.sport`. Exercised by `AdminDigestControllerIT` (4/4), which passed after the change. |
| L10 | not fixed | Inherited from `PlayerSpotlightService.trending`, which the live spotlight shares. Changing it here would alter another feature. No free agent is in today's snapshots. |
| L11 | fixed | The season-aware fake and the new tests are listed in the rows above. |

Run after the fixes: `./gradlew test --tests '*AdminDigest*'` gave 29 tests (25 service, 4 IT), 0 skipped, 0 failures.
The full backend suite gave 2138 tests, 1 failed (`BoardRepositoryLatestBeforeIT`, known and unrelated) and 6 skipped.
Web: `npx tsc -b` clean; `npx vitest run` 136 files and 2333 tests passed; `npm run build` succeeded.
Not run: no live server or browser check of the changes. The 8080 and 5173 servers were left untouched, and the running
bootRun still serves the pre-fix bytecode.

# Bug-hunting code review: 078 Redraft game of the day running number

**Date**: 2026-11-21 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `specs/078-redraft-daily-number/design.md`, AGENTS.md, the uncommitted diff against c00a846 (16 modified files),
the untracked `DailyNumber.java` and `DailyNumberTest.java`, and the code around the diff: every construction site of
`HistoricalGameState`, `GameSummary`, `DailyView` and `YesterdayDaily`, `RedraftPublicController`,
`HistoricalGameController`, `PublicGame`, and every web reader of `dailyDate` / `dayLabel` / "game of the day".

**What I ran** (nothing in the project was changed except this file):
- Backend: `./gradlew.bat test --tests 'com.ballknowers.draftsim.historical.*' --tests 'com.ballknowers.draftsim.api.Historical*' --tests 'com.ballknowers.draftsim.api.Redraft*'`.
  BUILD SUCCESSFUL, **370 tests, 0 failures, 0 errors, 2 skipped**. The 2 skips are `HistoricalRealSeasonIT`
  ("season 2023 is not imported here") and `TimeMachineBotStrengthHarnessIT`, both data/harness assumptions, not
  Postgres being down. `RedraftPublicMvcIT` (8/8), `HistoricalDailyMvcIT` (9/9) and `DailyNumberTest` (2/2) ran
  with 0 skipped, so the new IT assertions ran against the 5433 database.
- Web: `npx tsc -b` clean. Full `npx vitest run`: 213/214 files, 3389/3390 tests, plus 4 "failed to start forks
  worker" errors (timeouts while the backend suite ran alongside). Re-ran the 12 affected files on their own
  (HistoricalRoom, HistoricalRoomSeasonReveal, SharedResult.room, HistoricalDaily, historicalDaily, historicalShare,
  tmSite.wording, App.combined.redraft, App.site, redraftPages.app, HistoricalResults, usageVocabulary):
  249/250. The one failure is the known CRLF `usageVocabulary.test.ts`. `npm run build` succeeds.
- I checked `DailyNumberTest`'s expected values by hand: 11-14 to 12-01 is 17 days, so #18. To 2027-01-01 is 48
  days, so #49. To 2027-11-14 is 365 days, so #366. All three are correct.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | LOW | tests | No test asserts a non-null `dailyNumber` on `DailyView` or `GameSummary`, or a numbered render at any of the five web call sites. Only the helpers and two ITs pin it. |
| CR-02 | LOW | web | Two more number formatters sit beside `dailyName`: `sharedDailyTail`, and the inline `!= null` branch in `DailyCards`. Each is correct today, but the rule can drift. |
| CR-03 | LOW | data | `FIRST_DAY` was not checked against prod `min(daily_date)` (the design says so). Git puts 071 on main at 2026-11-14 16:43 ET, so that ET day is consistent. Any earlier row falls back cleanly. |

No HIGH or MEDIUM findings. Specific things I checked and found correct:

- **Construction sites.** There are exactly four record constructors: `HistoricalGameService:280` (GameSummary),
  `:306` (the reduced public copy, which passes `full.dailyNumber()`), `:663` (`toState`, used by own, public
  unreduced, claim and guest paths), and `HistoricalDailyService:91/160`. No other Java builds or deserializes these
  records (no `.class`/`TypeReference` reads). A swap would compile silently between `GameSummary.draftScore`
  (Integer) and `dailyNumber` (Integer), and in `DailyView` between `dailyNumber` and `draftScore`. I checked both
  argument lists and both are in the right order.
- **Day zone.** `DailyNumber.of` takes only a `LocalDate`. Every caller passes a date that is already US Eastern:
  `today()` uses `clock.withZone(America/New_York)`, yesterday is `today().minusDays(1)`, and `row.dailyDate()`
  is the stored ET day. The server's own zone never comes into it. Free play has `dailyDate` null, so the number
  is null.
- **api.ts mirror.** `dailyNumber?: number | null` is on all four TS types (`HistoricalGameState`,
  `HistoricalGameSummary`, `HistoricalDaily`, `YesterdayDaily`), and Java's `Integer` is nullable on all four
  records. The name and type are the same everywhere, which is what the `redraft-share-preview` session relies on.
  No `Map.of` is involved.
- **Fallback wording.** With null or undefined, `dailyName('Daily', d, null)` gives "Daily · Nov 14",
  `dailyName('game of the day', d, null, ', ')` gives "game of the day, Nov 14", and `dailyName('', d, null)`
  gives "Nov 14". `sharedDailyTail(day, null)` gives "Game of the day, Nov 14", the share title gives
  "Ball Knowers Redraft daily · Nov 14", and DailyCards uses the old `dayLabel` branch. All of these match the
  pre-078 strings exactly. The old-string tests (HistoricalDaily.test.tsx:197/199,
  HistoricalRoomSeasonReveal.test.tsx:235, SharedResult.room.test.tsx:226, historicalShare.test.ts:83) still pass
  because their fixtures send no `dailyNumber`.
- **No call site left behind.** Every web place that printed `dayLabel(dailyDate)` now goes through the number.
  The places that still mention "game of the day" are static copy, analytics labels or comments.
- **Reduced copy leak.** The reduced public game exposes `dailyNumber`. It can be derived from `dailyDate`, which
  was already exposed, so nothing new leaks.

## Findings

### CR-01 (LOW, REASONED) - the wiring of two records and five renders is untested

Where: `HistoricalDailyService.java:160` (DailyView), `HistoricalGameService.java:280` (GameSummary), and the web
call sites `DailyCards.tsx:189`, `DailyPage.tsx:65`, `HistoricalDraft.tsx:309`, `HistoricalRoom.tsx:353` and
`SharedResult.tsx:83`.

What happens: the ITs assert `dailyNumber` on the yesterday route, on the reduced public game, and null on an own
1895 game. Nothing asserts it on `GET /api/history/daily` or `GET /api/history/games`. Every web component test
uses a fixture with no `dailyNumber`, so only the fallback branch is exercised at each call site. A later edit
could pass `draftScore` where `dailyNumber` goes (same Java type), or drop the third argument at a web call site.
Either change would compile, pass every test, and quietly show the pre-078 text.

Fix: add assertions, not code.
1. In `HistoricalDailyMvcIT`, or a unit test that drives `status(owner, date)` with a date on or after 2026-11-14,
   assert `dailyNumber == DailyNumber.of(date)` and assert it is not the draft score. Do the same for a
   `listGames` row whose `daily_date` is 2026-11-21: expect 8.
2. In the web tests, add one numbered fixture each to `HistoricalDaily.test.tsx` (expect "Daily #8 · Nov 21"),
   `HistoricalRoomSeasonReveal.test.tsx` (expect "You drafted 1995-96 · game of the day #8, Nov 21"),
   `SharedResult.room.test.tsx` (expect /Game of the day #8, Nov 21/), and a DailyCards/DailyPage test (expect
   "Daily #8, Nov 21: the same season…" and "Basketball · #7 · Nov 20").

### CR-02 (LOW, REASONED) - the "#n" rule has three formatters

Where: `web/src/redraftCopy.ts:238` (`sharedDailyTail` writes its own `typeof n === 'number'` branch) and
`web/src/components/DailyCards.tsx:189` (`daily[0].dailyNumber != null ? dailyName(…) : dayLabel(…)`, which
repeats the null handling `dailyName` already has).

What happens: nothing wrong today. The outputs agree, and `!= null` and `typeof === 'number'` behave the same for
`number | null | undefined`. But the format ("#n" placement, the separator) is now defined in three places. The
design says the helper is the one place, and this repo has paid for second implementations of a rule before.

Fix: `sharedDailyTail = (day, n) => dailyName('Game of the day', …)`, which would need a date rather than a
pre-formatted day. Simpler, have SharedResult call `dailyName('Game of the day', game.dailyDate,
game.dailyNumber, ', ')` and drop `sharedDailyTail`. In DailyCards, call `dailyName(daily[0].dailyNumber != null ?
'Daily' : '', daily[0].date, daily[0].dailyNumber, ', ')`, or keep the branch with a comment. This is optional.

### CR-03 (LOW, REASONED) - day 1 is not checked against prod

Where: `DailyNumber.java:22`.

What happens: `git log main` shows c4c08bd ("game of the day … (071)") at 2026-11-14 15:43 -0600, which is 16:43
ET on 11-14. Auto-deploy within the hour also lands on 11-14 ET, so the earliest possible prod puzzle is #1, as
intended. If prod had a row dated earlier (it should not), that game would get `null` and render the pre-078 text.
That is a graceful outcome, not a wrong number. The remaining open point is the one the design already names:
whether prod's first dealt day is 11-14.

Fix: when prod DB access is available, run `select sport, min(daily_date) from historical_daily group by sport`
and record the answer in `verification.md`. No code change.

## Resolution (2026-11-21, amended after review)

- **CR-01:** fixed on the web side. `HistoricalDaily.test.tsx` now has a numbered games-list row
  ("Daily #8 · Nov 21"), and `SharedResult.room.test.tsx`'s reduced daily sends `dailyNumber: 99` and expects
  "Game of the day #99, Jan 1". On the backend, the ITs already assert a non-null number on the reduced public
  game and on the yesterday route. No IT asserts it on `GET /api/history/daily`, because the ITs use 1895 days;
  the live check read `dailyNumber` from that route instead (verification.md).
- **CR-02:** reduced to two. `sharedDailyTail` is gone. The shared kicker uses
  `dailyName(copy.sharedDailyHead, …, ', ')`. `DailyCards` keeps its inline check on purpose: with no number its
  note has no "Daily" prefix, which is the pre-078 text.
- **CR-03:** no change. It is already stated in design.md.

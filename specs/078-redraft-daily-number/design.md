# 078: Redraft game of the day gets a running number

Date: 2026-11-21. Branch `claude/redraft-daily-number-1df2f8`, off main `c00a846`. Small change: a design note
instead of the full speckit pipeline, per the brief.

## Why

The daily should spread by word of mouth (Allan, 2026-11-21). A number ("Redraft daily #8") turns the day into
a series people can name: "did you do #8?". The date alone does not do that.

## What's there now

- Game of the day is spec 071 (`claude/time-machine-daily.md`; there is no `specs/071-*` folder). Migration
  V49 stores one dealt puzzle per sport per day in `historical_daily`, plus `historical_game.daily_date`.
- `HistoricalDailyService.DailyView` (`GET /api/history/daily`) and `YesterdayDaily`
  (`GET /api/history/public/daily/yesterday`) carry the date. So do `HistoricalGameState` and `GameSummary`,
  as `dailyDate`.
- Web: `shareText` (`web/src/historicalShare.ts`) titles a daily "Ball Knowers Redraft daily · Nov 14". The
  shared result, room heading, games list, daily cards and yesterday rows all print `dayLabel(date)`.

## Definition (the one decision)

**Day 1 is 2026-11-14 for both sports**, and every US Eastern calendar day after it is the next number.
2026-11-21 is #8. The code is `DailyNumber.of(LocalDate)` in `backend/.../historical/DailyNumber.java`, the only
definition. Everything else reads the number from the server.

- **Why that date:** spec 071 was pushed to main on 2026-11-14 at 15:43 CST, and auto-deploy picks that up.
  It is the earliest day a production puzzle could exist.
- **Not verified against prod.** Reading production's `min(daily_date)` was blocked (no prod DB access from
  this session). If prod's first dealt row is 11-15 or 11-16, day 1 is still the launch date, by choice. A
  launch-date count reads like Wordle's.
- **Why one date for both sports:** both dailies launched together, and two numbers on one page ("NBA #8, NFL #8")
  would only be confusing if they ever differed.
- **From the date, not from the table:** the number is the same on every server and database (local scratch
  DBs have test rows going back to 1895). A day nobody asked for still takes its number, so the count never
  depends on traffic.
- A date before day 1 has no number: `null`. In practice that is only test fixtures (the ITs use 1895).

## Wire shape

`dailyNumber: Integer`, nullable, next to the date. It appears on:

| Record | Null when |
| --- | --- |
| `HistoricalGameState` (own game, and the public `/games/{id}` copy, reduced or not) | free play |
| `HistoricalGameState.GameSummary` | free play |
| `DailyView` | never in practice (before day 1 only) |
| `YesterdayDaily` | never in practice; set even when no puzzle was dealt that day |

The fields are mirrored in `web/src/api.ts` as optional (`?: number | null`) because the web can meet an older
backend in a deploy window. The field name is fixed: the `redraft-share-preview` session reads `dailyNumber`.
No migration is needed.

## Where it shows

A helper, `dailyName(prefix, date, n, sep)` in `web/src/historicalDaily.ts`. With no number it reads exactly
as it did before 078, so an old backend changes nothing.

| Place | With a number | Without |
| --- | --- | --- |
| Share text title | `Ball Knowers Redraft daily #8 · Nov 21 · 2020 NFL` | `… daily · Nov 21 · …` |
| Shared result kicker | `… · Game of the day #8, Nov 21` | `… · Game of the day, Nov 21` |
| Daily cards note (/daily, landing) | `Daily #8, Nov 21: the same season for everyone…` | `Nov 21: the same…` |
| Yesterday rows (/daily) | `Basketball · #7 · Nov 20` | `Basketball · Nov 20` |
| Room heading | `You drafted 1995-96 · game of the day #8, Nov 21` | `… game of the day, Nov 21` |
| Your games list | `Daily #8 · Nov 21` | `Daily · Nov 21` |

The date is kept everywhere. It is short, and a share read days later still says which day it was.

## Not built

- The static `/daily` HTML made at deploy time names no number: it cannot know today's. The number appears once
  the daily cards load.
- Link previews and og tags for shared games belong to `redraft-share-preview`.
- No "you've played N dailies" count.

## Acceptance

- Backend: `DailyNumberTest` checks exact values (day 1, #8, month end, year end, before day 1). The ITs assert
  `dailyNumber` on the yesterday route and on a reduced public game, and null for an 1895 day.
- Web: `dailyName` and `shareText` unit tests cover numbered and unnumbered output. Run `tsc -b`, the build and
  the full vitest suite.
- Live: the share text, the daily cards and a shared result show the number in the browser pane.

## Verification

See `verification.md`, written after the live check.

# Time Machine: game of the day, landing stats, season reveal (071)

Date: 2026-11-14. Branch `071-time-machine-daily`, off main `e0eab7e`. Follows
`claude/time-machine-game-feel.md` (070), whose "Not built" list this works through.

## What Allan asked for

"Let's work on these things now", about the four gaps 070 left: a season reveal, a game of the day, a shorter
game, and landing-page stats. His answers to the format questions (2026-11-14):

- Shorter game: **skip for now**.
- Daily draft slot: **random for each player** (not the same slot for all).
- Daily games per day: **one per sport**, each with its own streak.
- Day boundary: **midnight US Eastern**.

## What is there now

- A game is created on a random season with a random seed and a random slot
  (`HistoricalGameService.create`, `historical/HistoricalGameService.java`). The seed orders the unranked tail of
  the board and drives the bots and the replay, so two games on the same season and seed face the same board and
  the same bot temperament.
- `GET /api/history/games` returns `GameSummary` rows with no points, so the landing page cannot show a score.
- The results page appears whole the moment the last pick lands (`pages/HistoricalRoom.tsx`, the
  `complete && state.result` branch).

## Design

### Game of the day

- **V49.** `historical_daily (sport, daily_date, season_start, rng_seed)`, one row per sport per day, written the
  first time anyone asks for that day and never changed. `historical_game.daily_date` (null for free play) with a
  partial unique index on `(sleeper_user_id, sport, daily_date)`: one daily game per person per sport per day.
- **Choosing the season.** Derived from the sport and the date alone (a seeded draw through the sport's existing
  `SeasonSkew`), skipping any season used by that sport's previous 30 daily rows when that still leaves a choice.
  It is stored, so a later import that changes the offered list cannot change a day already dealt.
- **What is shared and what is not.** Everyone gets the same season, the same board order and the same seed. The
  slot is random per player (Allan's call), so the bots' picks differ from player to player and **two daily
  scores are not a like-for-like comparison**: a draft from pick 1 and a draft from pick 12 are different
  problems. The page says "same season for everyone", never "same draft".
- **The day.** `America/New_York`, from the shared `Clock` bean. The response carries the instant the next day
  starts, so the countdown needs no time-zone arithmetic in the browser.
- **Routes.** `GET /api/history/daily` (one row per sport that has offered seasons: the date, the season label,
  your game if you started one, your draft score if you finished it, your streak, the next rollover).
  `POST /api/history/daily {sport}` starts today's game, or returns the one you already started (201 new, 200
  existing). A daily game cannot be abandoned (409): the day's attempt is the day's attempt.
- **Streak.** Consecutive days with a COMPLETE daily game for that sport, ending today, or ending yesterday when
  today's is not finished yet. Keyed on the puzzle's date, not on when the last pick landed, so finishing after
  midnight still counts for the day it was dealt.

### Landing-page stats

- `GameSummary` gains `draftScore` and `dailyDate`. The score is computed in Java (`DraftScore.of`) from the two
  totals in the frozen result, with the same expression and rounding as the web's `draftScore`. That is two
  implementations of one rule (the class `claude/lessons.md` warns about); both are pinned to the same three
  vectors (2479.0 / 3108.7 = 79, 3300 / 3000 = 110, a zero divisor = none).
- The landing page shows games finished, best draft score and average draft score, computed from that list.
  Games with no score (old results) are left out of the average and the count beside it says how many it covers.

### Season reveal

- Web only. When a game finishes in the room you are sitting in, the season plays out before the results: the
  regular-season weeks tick by (your score, the opponent's, the running record), then the seed, then each
  playoff round you were in, then the finish. "Skip to results" is always there.
- A reopened game goes straight to results, with a "Replay the season" button.
- With reduced motion on, there is no timed reveal: results appear at once, as today.
- The award panel mounts only after the reveal, so XP and badge toasts cannot give the ending away.
- The steps are a pure function of the frozen result (`web/src/historicalReveal.ts`), so the record it counts up
  to can be tested against the standings row.

## Not built, and why

- **A shorter game.** Allan: skip for now.
- **Comparing daily scores between people** (a daily leaderboard, "you beat 60% of players"). With random slots
  the scores are not comparable, and the app has no multi-user surface for Time Machine.
- **Playing signed out**, and a share image. Unchanged from 070.
- **Streak freezes, reminders, notifications.** Nothing here sends anything.

## Acceptance for the verification pass

1. Two users starting the same sport's daily game on the same day get the same season and the same board order.
2. A second `POST /api/history/daily` for the same sport and day returns the first game, not a new one.
3. Free play is unchanged: `POST /api/history/games` still draws a random season and can be abandoned.
4. The landing page's best and average scores match the scores on the games' own results pages.
5. The reveal's final record equals the standings row, for football and basketball.
6. Reopening a finished game shows results at once.

## What was built (2026-11-14)

- Backend: `V49__time_machine_daily.sql`; `DailyPuzzle`, `DailyStreak`, `DraftScore`, `HistoricalDailyService`;
  `GET` and `POST /api/history/daily` on `HistoricalGameController`; `GameSummary.draftScore` and `.dailyDate`,
  `HistoricalGameState.dailyDate`; abandon refuses a daily game.
- Web: the game-of-the-day cards, the three numbers and the daily tag on the landing page
  (`pages/HistoricalDraft.tsx`, `historicalDaily.ts`); the season reveal (`historicalReveal.ts`,
  `components/HistoricalSeasonReveal.tsx`, wired in `pages/HistoricalRoom.tsx`) with "Replay the season" on the
  results; the share text names the day for a daily game. `web/src/api.ts` mirrors the new fields.
- Built in one pass by one session. There was no separate adversarial plan review and no separate bug-hunting
  review, which this repo's convention asks for on a feature this size.

## Verification (2026-11-14)

Run for real:

- Web: `npx tsc -b` clean, `npx vitest run` 183 files / 3,023 tests pass (re-run as a whole after the last
  edit), `npm run build` passes.
- Backend, Time Machine tests alone against a scratch database (`draftsim071_it`, V49 applied by Flyway):
  308 tests, 0 failed, 5 skipped (the ones that need a real imported season). That includes the new
  `HistoricalDailyMvcIT` (9) and `DailyGameRulesTest` (11).
- Live, this worktree's backend (8171, scratch copy `draftsim071` with real imported seasons) and vite (5271):
  - `GET /api/history/daily` dealt basketball 1984-85 and football 2021 for 2026-11-14, next at 05:00 UTC.
  - Played the football daily in the browser at 375px: game 86, auto-finished, the reveal ran week by week to
    11-3, the 2 seed, a bye, two playoff wins and "Champion"; the results page then showed 11-3 and draft score
    78. No results and no toasts were on screen during the reveal.
  - A second `POST` as the same user returned game 86 with 200. A different user got game 87 (201) on the same
    season with the same seed and a byte-identical board snapshot (md5 compared in SQL), from a different slot.
  - Abandoning the daily game answered 409; a preflight for the route answered 200; no identity answered 401.
  - Reopening game 86 went straight to results. "Replay the season" on a basketball game (83, 19 weeks) ran to
    11-8, the same record as its standings line, with no sideways scroll at 375px.
  - Landing page: finished 6, best 82, average 74, which is the mean of the six listed scores; the football
    card read "Done. Draft score 78" with a 1-day streak; the list row carried "Daily · Nov 14".

Not run:

- **The whole backend suite did not pass in this environment**: 2,919 tests, 15 failed, 291 skipped. The 15 are
  outside Time Machine (draft board not built in the small scratch database, no leagues in it, and two contexts
  that could not get a connection: "too many clients already" on the shared 5433 cluster). None was compared
  against a run of main, so "not caused by this change" is read from the messages, not measured.
- A day rolling over while the page is open, a streak across real days, and two real people on the same day.
- A reveal for a team that missed the playoffs or lost in them, in the browser (covered by unit tests only).
- Reduced motion in a real browser (unit test only), a screen reader, any browser but the built-in pane.
- Production. V49 has not been applied anywhere but the two scratch databases.

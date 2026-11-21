# 078 verification (2026-11-21)

## Setup

- Backend: this worktree's `bootRun` on port 8178, against `draftsim078`. That database is a scratch copy of
  `draftsim_074` on 5433, with Flyway taking it to V51. Proof it ran this branch's code:
  `/api/history/public/daily/yesterday` answered `"dailyNumber":7`, a field only this branch has.
- Web: this worktree's vite on 5278 with `VITE_SITE=timemachine`, proxied to 8178. Checked in the browser pane
  as a guest.
- The scratch database has NBA seasons only, so football was not seen live.

## Run, with what it showed

| Check | Result |
| --- | --- |
| Landing (`/`) and `/daily` daily cards | "Daily #8, Nov 21: the same season for everyone, from a random draft slot." |
| Played today's NBA daily (2003-04) and auto-finished it; room heading | "You drafted 2003-04 · game of the day #8, Nov 21" |
| Share button: the clipboard text captured in the page | `Ball Knowers Redraft daily #8 · Nov 21 · 2003-04 NBA` / `You lost in the semifinal · 13-6` / `Draft score 79%` / squares / link |
| `/game/1172` with a different guest id: the reduced shared result of today's daily | h1 "Shared result · 2003-04 NBA Redraft · Game of the day #8, Nov 21" |
| `/daily` yesterday row | "Basketball · #7 · Nov 20" (after inserting a 2026-11-20 NBA puzzle row into the scratch DB; football correctly said none was dealt) |

## Suites

- Web: `tsc -b` is clean and `npm run build` passes. Vitest: 3,610 passed, 1 failed. The failure is
  `usageVocabulary.test.ts`, which compares `usage-vocabulary.json` byte for byte. It fails on this Windows
  checkout because `core.autocrlf=true` gives the file CRLF line endings. It is unrelated to 078 and not touched
  by it.
- Backend: `./gradlew test` ran 3,072 tests: 12 failed, 48 skipped. The same 12 fail on a clean `main`
  (`c00a846`, a detached worktree, the same four classes): AccessControlMvcIT 1, AdminDigestControllerIT 1,
  TargetControllerIT 8, LeagueRepositorySuccessorIT 2. They come from league and board data in the shared
  `draftsim` database ("the draft board for this sport hasn't been built yet"), not from 078. All Redraft and
  daily tests passed, including the new `DailyNumberTest` and the new IT assertions.

## Not run

- Football daily live: no football seasons in the scratch DB. Unit tests cover it, and the code path is the same.
- The signed-in games list ("Daily #8 · Nov 21") was not opened in a browser. The `dailyName` unit test covers
  the string, and the list renders it the same way the other places do.
- Production: nothing was deployed, and prod's first `historical_daily` date was not read (see design.md).

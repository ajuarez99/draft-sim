# Live verification: 064 Time Machine achievements and levels

**Date**: 2026-11-13 · **Run by**: Claude (built-in browser pane) ·
**API**: launch entry `064-api`, port 8164, `bootRun` from this worktree. Confirmed to be this tree's code three ways:
the java process's classpath is `...\worktrees\064-time-machine-achievements\backend\build\classes\java\main`;
`GET /api/history/profile` answers 401 without a header and 200 with one (404 on main); Flyway logged "now at version
v48" on the new database. `/api/health` had `weightsLoaded: true`. ·
**Web**: Vite dev server on port 5264 from this worktree, proxied to 8164. **Not started with `preview_start`**: the
launch entry `064-web` exists, but the tool refused it ("Maximum 5 dev servers per folder reached; 4 belong to other
chats"), so it ran as a background shell (`npm run dev -- --port 5264 --strictPort`) and was killed at the end. ·
**Database**: `draftsim064`, created for this run on the shared Postgres 14 cluster at `localhost:5433` (never 5432,
never another session's database). Historical data imported through `/api/admin/historical/import`: `KAGGLE`
(81.7 s, NBA 1970-71 to 2015-16) and `NFLVERSE` 2015 to 2022 (9.8 s). The `SLEEPER` part (NBA 2016+) was **not**
imported. ·
**Identity**: made-up local ids written to `localStorage['bk.user.v1']` (`v064-alpha`, `v064-beta`, `v064-gamma`,
`v064-phone`) and sent as `X-Sleeper-User`; no real Sleeper account.

**Games played**: 81 completed (42 NBA, 39 NFL) plus 1 abandoned. Three were drafted in the browser (games 1, 2, 27);
the other 78 were created and finished through the same API routes with a scratch script (`tm.py`, in the session
scratchpad, not in the repo), 72 of them each under its own fresh user id so a badge's once-per-user rule cannot hide
a firing.

Automated suites at verification time: backend 2,872 ran / 15 failed / 272 skipped (none of the 15 is a Time Machine
test; see "Suites") · web vitest 176 files, 2,954 passed, 0 failed, 0 skipped · `tsc -b` exit 0 · `npm run build` built.

## Results

| # | Scenario | Sport / game | Result | Evidence |
|---|---|---|---|---|
| 1a | Draft in the UI with hand picks and deliberate reaches, finish, results show the award panel | NBA 1975-76, game 1 (slot 7): hand-picked Nate Thurmond at 1.07 (board rank 30), Fred Brown at 2.06 (rank 37), Jamaal Wilkes at 3.07 (rank 43), then Auto-finish | PASS | Panel: Game finished +100, 4 regular-season wins +40, five badges, Total +440. Payload `xp` 140 = 100 + 40; `total` 440 = 140 + 50+50+50+100+50. Screenshots `verification/01-...`, `02-...`. |
| 1b | Same, football | NFL 2018, game 2 (slot 11): hand-picked Patrick Mahomes at 1.11 (board rank 546), Auto-pick for rounds 2 to 15 | PASS | Panel: +100, 11 regular-season wins +110, 1 playoff win (50 XP each) +50, three badges, Total +560. `xp` 260, `total` 560. Screenshots `04-...`, `05-...`, `09-...`. |
| 1c | Toasts for new badges, moments and a level-up; at most three on screen | NFL game 2 | PASS | In-page log (ms): 79,432 three badge toasts (10 wins, Made the bracket, Survive and advance); 85,440 "Level up / Level 5: Veteran" and "Moment / Called it / Patrick Mahomes at pick 11 ... finished 1st of 180"; 91,453 none left. Never more than three. |
| 1d | Nothing is requested while the paced reveal runs | NFL game 2, speed Slow, last pick 15.11 | PASS (one 2-second window) | Final `POST /games/2/auto` at 77,030; reveal showed "Team 12 is on the clock" at 77,352; results heading at 79,399; first `GET /award` and `GET /profile` at 79,400. No `/award`, `/profile` or `/profile/seen` before the results view. Also none during the 40 s of mid-draft reveal logged before it. |
| 1e | CR-01: the first bust or reach does not pop twice | NBA 1995-96, game 27, fresh user, hand-picked Carlos Rogers at 1.02 (rank 160) | PASS | Ten toasts in order: First trip back, First win, 10 wins / Made the bracket, Survive and advance, Champion / Best record, Swing and a miss, Galaxy brain / Level 5. One toast each for BIG_BUST and REACH_MISS, no "Moment" toast for the same pick. `momentsGameId: 27` was still posted (at mount). |
| 1f | Round-1 reach can earn "Called it" (CR-03 fix) | NFL game 2 | PASS | Mahomes at pick 11 finished 1st: REACH_HIT moment fired. The badge itself had unlocked in game 1, so game 2 raised a moment only (A4). |
| 2 | Reload the results page; reopen an older game | Games 2 and 1 | PASS | After reload of game 2 and then opening game 1: 0 toasts in 5 s each (MutationObserver), no `POST /profile/seen`. DB: all eight badges `seen_at` set, both awards `moments_seen_at` set, `seen_level` 5. |
| 3 | Landing page level card and trophy case agree with the API and the database | `v064-alpha` after game 1, and after game 2 | PASS | After game 1: card "Level 3 Starter, 140 of 300 XP to level 4", progressbar `aria-valuenow` 140 / `aria-valuemax` 300, bar width 46.67%; API `xp` 440, `levelStartXp` 300, `nextLevelXp` 600. Totals 1 / 4 / 0 / 0 match. Trophy case "5 of 21 earned"; earned tiles carry "Nov 13, 2026 · See the game" linking `/history/1`; locked tiles show the rule; the two locked NEW_GAMES tiles add "Only from games finished after achievements were added." After game 2: API `xp` 1000; SQL `sum(award.xp) + sum(achievement.xp)` = 400 + 600 = 1000. Screenshot `03-...`. |
| 4 | Quiet backfill (SC-004), **simulated by SQL** | Game 27, `v064-beta` | PASS | See "How the backfill was simulated". Landing: "Level 5 Veteran, 340 of 500 XP to level 6 ... 1 game counted from saved results"; 8 stored badges (REACH_MISS, a NEW_GAMES badge, correctly absent), dated Nov 10, 2026 (the game's `completed_at`, CR-04); 0 toasts on the landing page and 0 on game 27's results page in 6 s; panel ends "Counted from this game's saved result. Pick-by-pick badges need a game finished after achievements were added." Total 1,340 = 590 + 750. Screenshot `06-...`. |
| 5 | Concurrent reads do not double count (SC-003) | `v064-delta`, games 28 (NBA) and 29 (NFL), unsettled | PASS | 16 parallel requests (award x2 games, profile) on 16 threads: all 200, every profile answered `xp` 770, `games` 2. SQL: 2 award rows (230, 190), 5 achievement rows / 5 distinct codes, awards + badges = 770. |
| 6 | A game that cannot settle | `v064-gamma`, games 30, 31, 32; game 31's `result.hindsight.userBeatIt` set to a string in SQL | PASS | Profile: `settleFailures` 1, `games` 2, `xp` 580 (the other two counted). `GET /games/31/award`: 200, `settled: false`, zero XP. UI: landing "1 game could not be counted"; game 31's results page renders in full with "This game's awards could not be worked out."; no console error. Server log: one WARN line per attempt, no stack trace (CR-08). Screenshots `10-...`, `11-...`. |
| 7a | Phone width 375: landing, results, toasts | `v064-phone` game 33 (NBA), `v064-alpha` game 2 (NFL) | PASS | `scrollWidth` 375 = `clientWidth` 375 on the landing page and on both results pages. Toasts span 16 to 359 px, the lowest ends at y = 740 of 812; no fixed element sits under them on these pages (there is no bottom bar on Time Machine pages). Screenshots `07-...`, `08-...`, `09-...`. |
| 7b | Dark theme legibility | all | PASS (by eye) | The app has one theme (dark; no `prefers-color-scheme` rules). Toast, panel, level card and locked tiles read clearly in every screenshot. No contrast ratio was measured. |
| 7c | Reduced motion | - | NOT RUN in a browser | The pane cannot emulate `prefers-reduced-motion`. The CSS guard exists (`styles.css` ~7142: `.ach-toast { animation: none }`). |
| 8 | Console errors and failed requests | whole run | PASS | `read_console_messages` (errors only) was empty at every check. Every `/api/history/*` request answered 2xx. The network tool tags each `POST /profile/seen` "204 No Content [FAILED: net::ERR_ABORTED]"; the 204 arrived and the rows were written (`seen_at`, `moments_seen_at`, `seen_level` all set), so this is how the tool reports a bodyless 204, not a failed request. Server log: no ERROR lines. |
| 9 | Badge and moment counts | 81 games | DONE | See "Fire counts". |
| 10 | Suites | - | DONE | See "Suites". |
| - | Access: no header, blank, whitespace, stranger, owner (curl against 8164) | `/profile`, `/games/1/award`, `POST /profile/seen` | PASS | No header, `""` and `"   "`: 401 on all three. Stranger: profile 200 (their own, empty), award 404, seen 204 with no effect on alpha (`seen_level` still 5). Owner: 200 / 200 / 204. `seen` with `level: 99` did not raise anyone past their real level. |
| - | CORS preflight (curl, real socket) | `OPTIONS /api/history/profile/seen`, Origin `http://localhost:5264` | PASS | 200, `Access-Control-Allow-Origin: http://localhost:5264`, methods include POST, headers `content-type, x-sleeper-user`. In the browser the calls go through the Vite proxy (same origin), so no browser preflight was exercised. |
| - | Odd states | DRAFTING, ABANDONED, unknown game, user with no games | PASS | Award: 409 "is DRAFTING, so it has no award yet"; 409 after abandon; 404 unknown. Empty profile: level 1 Rookie, 0 of 100, all totals 0; the landing page showed that card with all 21 badges locked. |

### How the backfill was simulated (item 4)

No game in `draftsim064` predates V48, so a pre-migration game was faked. After `v064-beta` finished game 27 in the
browser (and saw its ten toasts), this ran against `draftsim064`:

```sql
delete from historical_game_award where game_id = 27;
delete from historical_achievement where sleeper_user_id = 'v064-beta';
delete from historical_profile where sleeper_user_id = 'v064-beta';
update historical_game set pre_achievements = true, pick_facts = null,
       completed_at = completed_at - interval '3 days' where id = 27;
```

That is the state V48 leaves an old COMPLETE game in. It is not a real upgrade of a database that held games before
the migration; the migration's own `update` was only exercised on an empty table here.

### Measured timings (from this run, not estimates)

- Settle: `GET /games/{id}/award` on a just-finished, unsettled game took 0.01 s in all 72 scripted games (wall
  clock at the client, rounded to 10 ms).
- `finishDraft` including the pick-facts capture (CR-09): server log "replayed season ... in N ms": NFL 213 to 264 ms,
  NBA 202 to 901 ms over 81 games.

## Fire counts (item 9)

**These are counts over the games actually played, not rates.** 72 scripted games, each a fresh user, so every badge
the game could unlock for a first-time player is listed. "Auto" = the whole draft auto-finished. "Reach" = the script
hand-picked, in each of rounds 1 to 6, the best-ranked player at least one full round behind the pick, then
auto-finished. NBA seasons are Kaggle only (1970-71 to 2015-16); NFL 2016 to 2022.

Games in which the badge fired:

| Badge | NBA (36: 28 auto, 8 reach) | NFL (36: 28 auto, 8 reach) |
|---|---|---|
| CLEAN_SHEET | **0** | **0** |
| BEAT_HINDSIGHT | **0** | **0** |
| BIG_BUST | 13 | 25 |
| REACH_HIT | 3 (all in reach games) | 6 (all in reach games) |
| REACH_MISS | 8 (all 8 reach games) | 8 (all 8 reach games) |
| LATE_GEM | 1 | 7 |
| GONE_EARLY | 1 | 0 |
| PLAYOFFS / PLAYOFF_WIN / CHAMPION | 20 / 13 / 5 | 27 / 16 / 7 |
| TOP_SEED | 5 | 3 |
| PERFECT_SEASON | 0 | 1 |
| WOODEN_SPOON | 1 | 2 |
| FIRST_GAME / WINS_1 / WINS_10 | 36 / 36 / 19 | 36 / 35 / 16 |

Moment instances in those 72 games (a game can have several):

| Moment | NBA auto (28) | NBA reach (8) | NFL auto (28) | NFL reach (8) |
|---|---|---|---|---|
| BIG_BUST | 12 | 4 | 23 | 7 |
| REACH_HIT | 0 | 6 | 0 | 11 |
| REACH_MISS | 0 | 24 | 0 | 21 |
| LATE_GEM | 1 | 0 | 7 | 1 |
| GONE_EARLY | 1 | 0 | 0 | 0 |

Over all 80 settled games (the 72 above plus the browser and check games), from `historical_game_award.moments`:
NBA BIG_BUST 18 instances in 15 games, GONE_EARLY 1, LATE_GEM 1, REACH_HIT 7 in 4 games, REACH_MISS 25 in 9 games;
NFL BIG_BUST 31 in 26 games, LATE_GEM 8 in 7, REACH_HIT 12 in 7, REACH_MISS 21 in 8. The other badges for the nine
non-scripted games: BEAT_HINDSIGHT 0, CLEAN_SHEET 0.

What the counts show, as observations for Allan (hand-set thresholds; nothing here was retuned):

- **CLEAN_SHEET and BEAT_HINDSIGHT did not fire once in 81 games**, and no game had a null `biggestBust`. Amendment
  A6 guessed they may be close to unobtainable; in this sample they were.
- **BIG_BUST fired in 25 of 36 football games and 13 of 36 basketball games**, including games where the bots drafted
  for the fan. In football it is closer to a participation badge than a rare one.
- A one-round reach (the smallest reach the rule accepts) was a REACH_MISS on 45 of the 96 scripted reach picks and
  a REACH_HIT on 17.
- The fan won the title in 12 of 72 scripted games and one football game was a perfect regular season.

**GONE_EARLY, the one firing**: game 3, NBA 2010-11, Yao Ming, pick 23 (round 2): "his last box score in our data
came in week 3". The imported data has 5 box scores for him, 2010-10-26 to 2010-11-10. That matches the real season
(he played five games and did not return), so this is a real early exit, not an import gap. One firing says nothing
about how often the Kaggle gaps of plan A7 would trigger it; in this import 257 NBA player-seasons and 116 NFL
player-seasons have a last week of 3 or earlier, and none of those was checked against the real record.

## Suites (item 10)

- **Web**: `npx tsc -b` exit 0; `npx vitest run` 176 files, **2,954 passed, 0 failed, 0 skipped**; `npm run build` built.
- **Backend, full suite on this branch** against the database the ITs normally use (`draftsim` on 5433, application
  defaults), forced with `cleanTest test --continue`, XML timestamps 21:02:55Z to 21:04:34Z:
  **2,872 ran, 15 failed, 272 skipped.** The 15:
  - `AccessControlMvcIT` 1, `AdminDigestControllerIT` 1, `TargetControllerIT` 8, `LeagueRepositorySuccessorIT` 2,
    `DraftGradesReadIT` 1: league data this throwaway cluster does not hold ("The draft board for this sport hasn't
    been built yet", empty season lists).
  - `CredentialLeakIT` 2: the Spring context failed to load with `FATAL: sorry, too many clients already`. The shared
    cluster (`max_connections` 100) was serving several sessions plus this run's own bootRun.
- **The same suite on current `origin/main` (8de24a7)**, in a temporary detached worktree (removed afterwards), same
  database, with `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:future` because `draftsim` is already at V48:
  **2,831 ran, 16 failed, 250 skipped.** The same 13 data failures, the same 2 `CredentialLeakIT` failures, plus
  `EspnRefreshOnVisitIT` 1; all three context failures are the same "too many clients" error. So **every one of the
  branch's 15 failures also fails on main**, checked by running it.
- **Rerun on the branch** of `CredentialLeakIT`, `EspnRefreshOnVisitIT` and every `*Historical*`, `*Achievement*`,
  `*Levels*`, `*Nfl*` test in one smaller run: **196 ran, 0 failed, 5 skipped.** `HistoricalAchievementMvcIT` 8/8,
  `HistoricalAchievementSavepointIT` 1/1, `HistoricalAchievementWireIT` 3/3, `AchievementRulesTest` 28/28,
  `LevelsTest` 5/5, `CredentialLeakIT` 2/2, `EspnRefreshOnVisitIT` 1/1.
- An earlier full run against an empty scratch database (`draftsim064_it`) gave 2,872 ran, 15 failed, 291 skipped
  (the 12 data failures without `DraftGradesReadIT`, plus `CredentialLeakIT` 2 and `EspnRefreshOnVisitIT` 1 on the
  same connection error). It is recorded only because it was run.
- The skip count is not comparable between branch and main: the shared `draftsim` database was being written by other
  sessions between the two runs, and several data-dependent classes flipped between "all skipped" and "all ran" in
  both directions (`NflGameFlowIT`, `HistoricalRepositoryIT`, `ScoredWeeksIT` skipped on the branch run and ran on
  main; `RefreshControllerDailyIT`, `BackfillAdpAtTimeNearestSnapshotIT` the reverse). `NflGameFlowIT` 8/8 passed in
  the smaller rerun.
- A fully green backend suite was **not** observed, on the branch or on main, on this machine.

## Defects found during verification

No BLOCKER or SHOULD defect was found. Three NITs, none fixed (the parent owns fixes):

- **V-01 (NIT) - a first big game is a long toast queue.** Game 27 (first game, a title) raised ten toasts, three at
  a time, six seconds each: 24 seconds from results to the level-up toast, which comes last. At 375 px the stack of
  three covers roughly y = 471 to 740 of an 812 px screen while the results headline is underneath. Reproduce: fresh
  user, finish a game that makes the playoffs. Each toast is dismissible; nothing is lost (all are in the panel).
- **V-02 (NIT) - the dismiss button is 32 x 32 px** at phone width (`.ach-toast-close`, measured), under the 44 px
  touch target the rest of the phone UI aims for.
- **V-03 (NIT) - level card totals wrap 3 + 1 at 375 px**: Games, Weekly wins, Playoff wins on one row and Titles
  alone on the next (screenshot `08-...`).

Not defects, but the parent should know:

- **`origin/main` has moved past this branch's base.** The branch is on ff24e61; main is now 8de24a7 (PR #62
  midnight-indigo and the 067 rookie-filter merge). Main changed `web/src/api.ts` and `web/src/styles.css`, both of
  which this branch also edits. Main's highest migration is still V47, so V48 does not collide today. The merge was
  not attempted here.
- **Toasts are invisible in a tab that is not being painted.** While the browser pane was hidden, `requestAnimationFrame`
  ran twice a second and the 180 ms entrance animation stayed on its first frame (computed opacity 0) although the
  six-second timers ran and seen was posted (screenshot `12-...`). `document.hidden` was false, so the visibility
  guard added for CR-05 did not apply. A real background tab reports `document.hidden = true` and is covered by that
  guard; this state is specific to an occluded embedded pane. The phone toast screenshot (`07-...`) was taken after
  calling `finish()` on the animations from the console, for that reason.
- A stranger's `POST /profile/seen` creates an empty `historical_profile` row for that id (seen_level 1).

## Not run / not verified

- **A real pre-migration database.** Item 4 was simulated by SQL (above). V48's `update ... where status = 'COMPLETE'`
  never touched a real row here.
- **The paced reveal check for basketball.** The NBA browser games ended with Auto-finish, which skips the reveal;
  the "no request during the reveal" timing was measured on the NFL game only, over a 2-second final reveal.
- **Two real browser tabs.** Concurrency was 16 parallel HTTP requests, not two tabs.
- **Reduced motion** in a browser, a **screen reader** (CR-02's live region was only checked to exist, empty, with
  `role="status" aria-live="polite"`), **keyboard-only** dismissal, and **hover/focus pausing** of the toast clock.
- **Contrast ratios** (legibility was judged by eye) and any theme other than dark (there is none).
- **A browser CORS preflight**: the dev setup is same-origin through the Vite proxy. Curl preflight only.
- **NBA seasons from the Sleeper import (2016+)** and NFL seasons before 2016: not imported, so no game used them.
- **Old-format results** (no `format`, spec 062 and earlier; FR-008): no such game exists here. Covered by unit tests only.
- **Titles in both sports for one user, TITLES_3, WINS_25/50/100**: never reached by one user in this run.
- **The GONE_EARLY import-gap question (A7)**: one firing, and it was real. The read-only production count per
  source that A7 says is owed was not done (no production access was asked for or used).
- **The mock draft and live draft rooms** were not opened (this database has no league).
- **Merge with current main, deploy order, production**: nothing merged, nothing deployed, production not touched.
- **Long manager names / 12-team league layout**: a long display name was set for two test users, but the Time
  Machine pages do not print it; every game here is 12 teams.

## Left behind

- Databases `draftsim064` (81 games, imported seasons) and `draftsim064_it` (empty, migrated to V48) on the 5433
  cluster. Drop with `dropdb -h localhost -p 5433 -U draftsim draftsim064` (and `draftsim064_it`) when not needed.
- Two entries appended to the main checkout's `.claude/launch.json`: `064-api` (8164) and `064-web` (5264). Nothing
  else in that file was changed.
- Both servers are stopped; ports 8164 and 5264 are free. The comparison worktree of `origin/main` was removed.
- `web/dist` from `npm run build` (ignored by git). Screenshots under `specs/064-time-machine-achievements/verification/`.
- No project code was edited and nothing was committed.

# Verification notes: automatic data refresh

Every entry says whether it was **run** or **read**, and against what. Local means Postgres 5433
(`docker compose`) with the backend from `.claude/launch.json`. Production means
`api.ballknowers.co`, reached read-only.

## Before the change (T001–T003), 2026-09-28

Backend on the unchanged tree (branch `009-auto-data-refresh` = `origin/main` plus docs), still
running the per-player walk.

**Ingests run first**:

| League | Transactions | Per-game (per-player walk) |
|---|---|---|
| NBA 2025 | `stored: 91` | 331 players walked, 19,428 games, 7,383 absences, 0 failed |
| NFL 2025 | `stored: 19` | 315 players walked, 4,421 games, 936 absences, 0 failed |

**Baseline exports** are saved as TSVs in the session scratchpad, restricted to players rostered
in any league of the sport-season and sorted deterministically. Excluded columns: `id` and
`fetched_at`.

| Table | NBA 2025 | NFL 2025 |
|---|---|---|
| `player_game` rows | 19,428 | 4,421 |
| `player_absence` rows | 7,383 | 936 |

Whole tables: `player_game` 41,289 rows, 40 MB; `player_absence` 8,360 rows, 1.9 MB.

**Superlatives payloads** for both leagues are saved as JSON (T002). Top Embiid absences:
- NBA 2025: Sabonis 37, Embiid 26, Garland 30 (holder total 3,980.55);
- NFL 2025: Daniels 6, Pearsall 6, Jones 4 (218.85).

**ADP and board cost** (T003), measured with the Budget's method (Java process CPU before and
after):

| Call | Wall | Backend CPU |
|---|---|---|
| `adp?sport=nfl` | 0.5 s | 0.19 s |
| `adp?sport=nba` | 0.0 s | 0.00 s (football-only, skipped) |
| `board?sport=nfl` | 0.5 s | 0.14 s |
| `board?sport=nba` | 0.2 s | 0.05 s |

So the daily job's board step costs well under 1 CPU-second a day for both sports.

## Production, read-only (during analysis), 2026-09-28

- **Cold start**: `/api/health` answered 502 for about 4.7 minutes after the service had been idle,
  then 200. That's why the workflow's retry window was widened (research R7).
- **NBA 2025 week-by-week against Sleeper** (research R14):
  - Adds: weeks 1–9 match; week 10 has 24 of 53; weeks 11–21 have 0.
  - Points: all match except week 8 (off by up to 20.5) and week 9 (up to 31). Weeks 19 and 21
    list 8 of 12 teams, not yet explained.

## Foundation and the per-game rebuild (T004–T015)

Built by a Sonnet subagent (AGENTS.md). The parent session read the migration and the report,
then verified it by running the parity gate below, not by reading. The subagent's
`./gradlew cleanTest test` was 706 tests, 0 failures, 0 skipped.

Decisions the subagent made, accepted and recorded:
- **Postponed and canceled games count as settled for week finality.** Measured NBA 2025: three
  postponed games (weeks 12, 14) and one canceled (week 17) stay in Sleeper's schedule forever.
  Requiring `complete` would refetch those weeks on every run.
- **`Result.playersFailed` became `weeksFailed`**, alongside `playersWalked` becoming
  `weeksFetched`. Nothing outside the service read either.
- **The 48 h window starts at the end of the week's last game date (UTC).** That's the
  conservative reading.
- **"Team played"** now means a `complete` scheduled game that week, instead of any walked
  player's entry.

## Parity gate (T016), run 2026-09-28

The backend was restarted on the new code, then `truncate player_game, player_absence,
sport_week_stats`, then `POST /api/ingest/player-games/{id}` for both leagues.

| Rebuild | Wall | Result |
|---|---|---|
| NBA 2025 | 83 s | 25 weeks fetched (+1 schedule call), 29,143 games, 16,852 absences, 0 failed |
| NFL 2025 | 69 s | 18 weeks fetched (+1 schedule call), 26,490 games, 14,979 absences, 0 failed |

That's 26 and 19 Sleeper calls, against the old walk's 331 and 315. Wall time is now mostly
single-row writes, not Sleeper.

**Diff against T001's baseline, rostered players only:**

| Table | Result |
|---|---|
| `player_absence`, NBA 2025 | **identical** (7,383 rows) |
| `player_absence`, NFL 2025 | **identical** (936 rows), restricted to players of the league the old walk covered |
| `player_game`, NFL 2025 | **identical** (4,421 rows), same restriction |
| `player_game`, NBA 2025 | same 19,428 keys; 654 rows differ in one stat key only |

- **The 654 NBA rows**: the per-week payload carries `"plus_minus": 0.0` where the per-player
  payload omits it. Points and every other stat are identical. **Nothing reads `plus_minus`**
  (search of `backend/src/main`, `web/src` and `config`), and no league's scoring includes it.
  Accepted as a harmless source difference.
- **NFL extra rows** (253 games, 70 absences): all belong to players of the *other* NFL 2025
  league in this database, which the old walk (called for one league) never covered. None is a
  player of the league it was called for. That's the intended behaviour (research R6).
- **Superlatives**: both leagues' payloads are **byte-identical** to T002's, including every Embiid
  figure (NBA: Sabonis 37, Embiid 26, Garland 30).

**Pass.**

## SC-004 and storage (T017)

- **Re-running NBA 2025** fetched no weeks (all 25 final) and took **0.77 s**, against 10.1 s
  (10% of 101 s). NFL 2025 took 0.95 s. **Pass.**
- **A no-fetch re-run leaves `player_absence` byte-identical**: 31,831 rows, same md5 before and
  after. The no-entry pass rewrites existing rows but changes nothing.
- **Storage**: NBA 2025 has 29,143 `player_game` rows (research R6 estimated ~26k) and NFL 2025
  has 26,490. The two seasons take 41 MB together (~0.75 KB a row), so about 20–22 MB per
  season per sport. That's in line with the estimate, and about $0.003 a month each at Railway's
  volume price.

## Refresh on visit (T048–T052, T018–T028): build and review

Built by a Sonnet subagent: backend 734 tests, 0 failures, 0 skipped; web 556 tests passed.
**T052 failed on the unchanged ingest** (Mockito `ArgumentsAreDifferent`: week 10 never refetched
after leg 11) and passes after T050/T051, where week 10 goes from 24 to 53 moves and is recorded
final.

Accepted judgement calls:
- The chain flight and the per-sport flight are separate `SingleFlight` instances. A chain blocks
  on the per-sport run, so one shared semaphore of 2 could deadlock.
- A per-game week that fails to fetch fails the whole chain refresh, so `loaded_complete` can
  never freeze a gap.
- The LeagueHistory and LeagueAnalysis pages now reset their state only when the league id
  changes. Otherwise a refresh-triggered refetch would blank the page.

**Fixed by the parent session, found in review and live verification:**

1. **Completion ignored per-game finality** (flagged by the subagent itself).
   `completeAfter(status, succeeded)` could mark a season `loaded_complete` within 48 h of its
   last game. The per-game weeks would then never be refetched, losing Sleeper's stat
   corrections. It now also requires every `sport_week_stats` week of the sport-season to be
   final. Tests: `LeagueRefreshRulesTest`, plus a new `RefreshControllerIT` case (complete
   league, non-final per-game week, stays not complete).
2. **RUNNING was invisible from an older season's page** (found live).
   - The chain key was the *newest* season. `chainBySleeperId` only walks backwards from the id
     it's given, so the 2025 page built a different key from a run started from 2026.
   - Polled from the 2025 page, status answered FRESH while the chain was still running: seen at
     18:21:16, and the run finished at 18:22:22. The rail would stop polling and refetch stale
     data (FR-004).
   - The key is now the chain's **oldest** season. New IT:
     `aRunStartedFromTheNewerSeasonShowsRunningOnTheOlderSeasonsPage`.

Refresh-related suites after both fixes: 26 tests, 0 failed, 0 skipped.

## T029: live check (local, real browser), 2026-09-28

**A finding before the check.** `scripts/check-weeks-vs-sleeper.py` (T054) run against the
*local* database showed weeks 1, 8 and 9 of NBA 2025 with points differing from Sleeper's
current figures. Weeks 8 and 9 differed the same way production does.
- So Sleeper revised those weeks after the fact, and every copy of the data taken earlier kept
  the old scores.
- Spec 008's locally verified NBA figures (highest week, luck, bench) were computed on those
  scores. This feature's first refresh corrects them.

**§3.1, stale → refresh → heal**, as `popsharky` on `localhost:5173`:
- **Setup**: NBA 2025's `league_refresh` and `league_week_fetch` rows deleted (the pre-feature
  state), week 12's transactions deleted (85 rows), and roster 3's week 9 points raised by 25.
  The check script then failed on weeks 1, 8, 9 and 12.
- **Open** `/leagues/1229352720222134272/superlatives`: the rail shows "Updating…".
- **Polled from the same page's id**:
  - with the chain-key fix, RUNNING for the whole run, then COMPLETE for both 2025 and 2024;
  - before the fix, FRESH mid-run (fix 2 above).
- **The rail then showed "Updated just now"**, and the page refetched without a reload. The
  Jabari Smith Jr. Award read "Jake LaRavia (SF) · LAL · 14 adds by 8 teams".
- **The week check script now gives PASS for all 18 regular-season weeks**, adds and every
  team's points.
- **`loaded_complete`**: true for 2025 and 2024; false for 2026 (pre_draft).
- **First run, from the pre-feature state**: about 2 min 20 s. Most of it was rebuilding NBA
  2024's per-game data, which T016's truncate had cleared (25 weeks, 28,798 games). With per-game
  weeks already final, the same chain took about 10 s.

**§3.3, single-flight**: two simultaneous POSTs for NFL 2026 produced one run (the backend log
shows one `refresh: chain 1346366555759341568`).

**§3.7, SC-003 on NFL 2026 (local)**:
- First-ever load: 23 s. That's every week of both seasons, because nothing was final yet, plus
  3 per-game weeks.
- **Ordinary stale visit** (last success set 2 h back): **5.4 s**, against a 30 s target. NFL
  2025 was skipped as complete, and only the non-final current week was refetched.
- **Pass (local).** Production is measured in T046.

**§3.4, failure** (backend on 8083 with both Sleeper base URLs pointed at `127.0.0.1:9`, web on
5182):
- The status becomes FAILED. `lastSuccessAt` keeps the older success, and the stored data is
  untouched: the page still shows all 13 superlative cards.
- The reason is stored: `ResourceAccessException: I/O error on GET … http://127.0.0.1:9/league/…`.
- A second POST within 10 minutes starts no new attempt (`last_failure_at` unchanged).
- **Rail, in the browser**: "Couldn't reach Sleeper — data from 2 h ago".
- **Test-setup note**: a first attempt returned 403 on the POST, because 5182 wasn't in
  `CORS_ORIGINS`. The launch config now sets it. Production's CORS for the POST (which has a
  preflight) is checked at deploy (T046).

**§3.2 / §3.5**: re-opening a completed season returns `COMPLETE` with no Sleeper fetch (the
chain log shows no run).

**§3.6 (resolver, before week 1)**: **not run**. No league is currently before its week 1. The
chain-key IT covers the mechanism a resolver fall-back relies on: a run started from one season
being visible from another's page.

**FR-014 (call count), estimated, not measured**: HTTP calls to Sleeper aren't logged. From the
code path, an ordinary stale NFL 2026 visit makes about 10 calls:
- league, users and rosters for the chain seasons;
- matchups and transactions for the non-final week;
- the schedule;
- the one non-final per-game week.

That's far under 1,000 a minute.

## US2, US3, US4: build and review (T030–T032, T036–T041, T053, T055, T034)

Built by a Sonnet subagent: backend 752 tests, 0 failures, 0 skipped; web 557 passed.
`NoIngestHintsInMessagesTest` **failed before T031 on exactly the 9 expected strings**, and passes
after.

Accepted as the subagent built them:
- The guard skips Spring `@…Mapping("…")` lines.
- `BOARD` isn't skipped by a same-day capture; only `PLAYERS` is.
- `/api/refresh/players` returns 500 on `FAILED`, as setup's player step did before.
- The "Loading past seasons" step swallows a failed kick-off, because the next visit retries.

**Changed by the parent session:**
1. **The workflow used `curl --retry … --retry-all-errors`** (as the contract said). curl treats
   500 as retryable, and `--retry-all-errors` retries everything, so a genuine step failure
   would have re-run the whole daily job up to 30 times, with `-f` hiding the error body. It's
   now a shell loop that retries **only** `000/502/503/504`, prints the body, and fails unless
   the final status is 200. Contract amended.
2. **`/api/ingest/all` still composed ADP, board and profiles itself.** It now calls
   `BoardRefresh`, so all three paths share one implementation (R15).
3. **The guard test was widened, and 11 more developer-facing strings reworded.** Found by T033:
   two responses still said "ingested" without naming an endpoint. A literal that reads as a
   sentence and uses "ingest" now fails the build (bare JSON keys like `"ingested"` and `log.`
   lines are exempt). It caught 8 backend strings: "not ingested", "re-run league ingest",
   "run ingest first" and similar. The frontend had 5 more: PowerRankings ×4, LeagueHistory and
   ManagerTendencies. All now use plain wording.
   - The PowerRankings commissioner text now says the commissioner "is read from Sleeper whenever
     the league refreshes". That's verified by reading: the chain walk records `is_owner` at
     `LeagueHistoryIngestService.java:149–150`.

## T033: no developer text, in the browser, 2026-09-28

League `fantasy😍` (`1391509063170293760`, NFL 2026) had nothing loaded: no transactions, weeks or
refresh row.
- **Before any visit**, plain GETs, which don't trigger a refresh, on every league endpoint. None
  names `/api/` or uses "ingest". The wording seen:
  - "no week of this season has been scored yet";
  - "No pairings for week 1 yet. Sleeper publishes a week's schedule shortly before it starts.";
  - "Transactions for this league haven't loaded yet.";
  - "week 1 has not been scored for this league".
- **In the browser**: opening the first page (history) triggered the refresh, which loaded the
  whole league within seconds. All 8 league pages were then read in the browser: **no line
  matched `/api/` or `ingest`** on any of them. Superlatives' rail read "Updated just now".

## T042: the daily endpoint, local, 2026-09-28

| Call | Result |
|---|---|
| Backend without `REFRESH_SECRET` (new code) | `POST /api/refresh/daily` → **404** |
| With `REFRESH_SECRET=local-test-secret`, no header | **401** |
| Wrong secret | **401** |
| Right secret, first call | **200** |
| Right secret, second call the same day | **200** |

- **First call**: NFL `PLAYERS` DONE (4,386 players, 2 suspended), and a `status_capture` row for
  nfl 2026 week 3 was written. NFL `BOARD` DONE (843 entries, 29 ADP rows, 44 profiles). NBA
  `PLAYERS` was `SKIPPED_ALREADY_TODAY`, because the setup flow's `POST /api/refresh/players?sport=nba`
  had already captured it today; the once-a-day gate is shared (FR-009). NBA `BOARD` DONE (0 ADP
  rows: FFC is football-only).
- **Second call**: both `PLAYERS` steps skipped, and both boards rebuilt.
- **Not run live** (covered by tests that run the real code): the `API_TOKEN` bypass is covered
  by `RefreshControllerDailyIT` with a token configured, and an FFC failure giving
  `DONE_ADP_FAILED_BEST_EFFORT` by `DailyRefreshServiceTest`.

## T035: a new league loads completely, once, 2026-09-28

1. West Coast Fantasy Football (NFL 2026 `1389361939561332736` and 2025 `1262506916429430784`)
   was deleted from the local database, then added through the picker's "Set up" as `popsharky`.
2. The network log shows the five setup steps in order: `POST /api/refresh/players?sport=nfl`,
   `/api/ingest/league/…`, `/api/ingest/adp`, `/api/ingest/board`, then
   `POST /api/leagues/1389361939561332736/refresh`.
3. **With no league page opened**, the chain was RUNNING, and 12 s later the 2025 season was
   `COMPLETE` (`loaded_complete = true`) and 2026 was `FRESH`.
4. **Week check on the 2025 season: PASS**, all 14 regular-season weeks (adds and every team's
   points).

## T043: suites, 2026-09-28

After every edit above: backend `./gradlew cleanTest test` gave **752 tests, 0 failures, 0 errors,
0 skipped** (Postgres up). One stale assertion (`DraftContextFactoryTest` expected the old
"ingest" hint) was fixed before that run. Web: `tsc -b` clean, build OK, vitest **46 files, 557
passed**.

## T044: bug-hunting review, 2026-09-28

A separate reviewer, on the session's model (AGENTS.md: review stages don't run on Sonnet), read
the whole diff including the untracked files. It reported 9 findings, all fixed by a Sonnet
subagent, each with a test that failed before its fix where one was feasible:

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | High | A league reading `complete` before its per-game weeks were final refreshed on **every** visit. The "complete → START" shortcut assumed the load would set `loaded_complete`, which my own completion fix had made wait for per-game finality | The shortcut applies only before the first success; after that, the normal 1 h staleness applies |
| 2 | Medium | When the shown season was complete (NBA offseason, NFL before week 1), the rail never saw RUNNING or bumped the page, and "Updated N ago" read a timestamp that never moved | Top-level `state` is RUNNING whenever the chain runs, and `lastSuccessAt` is the newest across the chain. **Verified live**: the NBA page (shown season 2025, complete) read RUNNING while 2026 refreshed, then COMPLETE with `lastSuccessAt` updated |
| 3 | Medium, plausible | An empty per-week payload for a week the schedule says was played would count as fetched, could be marked final, and would write "team played, no entry" absences for every rostered player | Counted as a failed week: not recorded, and excluded from the absence pass |
| 4 | Medium-low, plausible | `WeekFinality` treated league status `complete` as final, freezing the championship week before stat corrections | Final only if `last_scored_leg > week`. A complete season stops refreshing only at `loaded_complete`, which waits for per-game finality (≥48 h after the last real game) |
| 5 | Low-medium | League Analysis: after a refresh the chosen week's tab stayed highlighted but the content showed the default week | The refetch passes the chosen week |
| 6 | Low | An `Error`, or an exception inside the failure handler, was swallowed silently | Logged |
| 7 | Low | The check script printed PASS having checked zero weeks when `playoff_week_start` was missing | Exits 2, INCONCLUSIVE, and never PASS on zero weeks |
| 8 | Low | `MockDraftService` still said "not ingested" | Reworded, and `mock/` added to the guard's scan |

**Whole-tree run of the guard** (an experiment, not left in place): it also flagged two
`BoardService` messages that can reach a user ("no blended board — run /api/ingest/board", "no
search_rank snapshot — ingest players first"). The parent reworded both. Two operator-only
messages (`LeagueIngestService`, `LiveDraftPoller`) were left as they are.

**Suites after the fixes**: backend **754 tests, 0 failures, 0 errors, 0 skipped**; web **46 files,
558 passed**.

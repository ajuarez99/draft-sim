# Verification log: spec 022

Results are recorded as they are measured, with the date. **Measured** means the step was run and
the number is shown. **Not run** means it wasn't, and the reason is given.

## T005: baselines before any refactor (2026-10-07, measured)

- **Backend**: this worktree's code (`draft-sim-022`, branch `022-player-stat-analysis` at
  `0c31c3c`, code identical to `origin/main` `2c72ead`).
  - Run with launch entry `draft-sim-022-api-8092` (port 8092, `REFRESH_ONVISIT_ENABLED=false`, so
    no refresh rewrites data between baseline and gate).
  - Classpath checked in the boot log:
    `C:\Users\allan\source\draft-sim-022\backend\build\classes\java\main`.
- **Backend suite**: `./gradlew test` gave BUILD SUCCESSFUL. Summed from the JUnit XML: **1,346
  tests, 0 skipped, 0 failures, 0 errors.** Postgres on 5433 was up, so no IT was silently
  skipped.
- **Trends baselines**: captured with `X-Sleeper-User: 1122386008709910528`, a member of NBA 2025
  and 2026, then normalised. `rostersFetchedAt`, `currentWeek` and `staleReferenceDate` were
  deleted and keys sorted recursively (N13).

| League | Season | HTTP | Raw size | Time | Content |
|---|---|---|---|---|---|
| `1339351318115946496` | 2026 | 200 | 8,076 B | 1.55 s | roles/streaming fall back to 2025; 10 risers, 10 fallers; streaming `NOT_DRAFTED` |
| `1229352720222134272` | 2025 | 200 | 8,168 B | 1.58 s | 10 risers, 10 fallers; streaming `SEASON_COMPLETE` |

  - Saved to `baseline/player-trends-2026.json` and `baseline/player-trends-2025.json`.
  - The 1.55–1.58 s per request is Trends' cost **before** the cache. It is a pre-change reference
    for V9, not a target.

## After T002–T014: interim gate (2026-10-07, measured)

- **Suite**: 1,380 tests, 0 skipped, 0 failures, 0 errors (reported by the T008–T014 agent; the
  final full count is re-taken at T017).
- **Scoring parity (I1)**: `GameScoringParityIT` made 78,572 comparisons with **0 mismatches**,
  covering NBA 2025 (26,680 games × 1 league) and NFL 2025 (25,946 games × 2 leagues). Equality is
  exact (`Double.compare`).
- **Trends output (V1)**: after normalisation, both leagues' `player-trends` JSON is
  **byte-identical** to the T005 baselines (`cmp`).
- **Trends timing, with the cache**:

| League | Cold | Warm | Before the cache (T005) |
|---|---|---|---|
| 2025 | 1.03 s | **0.40 s** | 1.58 s |
| 2026 | 2.70 s | not re-measured | 1.55 s |

  - The 2026 cold figure is the first full load after boot (both seasons).
  - The first 2026 attempt returned `000` (server still starting) and is excluded.
  - These are single local samples, not a benchmark. V9 does the real measurement.

## T017: foundation gate (2026-10-07, measured by the parent session)

- **Suite**: run fresh with `./gradlew cleanTest test`. An earlier plain `test` was all
  up-to-date, so its counts were the agent's run, not a fresh one. Result: **1,394 tests, 0
  skipped, 0 failures, 0 errors** (1,346 before the foundation, so +48).
- **I1**: `GameScoringParityIT` made 78,572 comparisons with **0 mismatches**.
- **T016**: `LeagueRepositorySuccessorIT` ran.
  - From the 2024 id, `seasons` is [2026, 2025, 2024].
  - 2026 `hasGames` is false locally, because there are no 2026 regular-season `player_game` rows
    yet.
- **Health**: `playerStatsLoaded: true`.
- **V1**: both leagues' `player-trends` JSON is **byte-identical** to the T005 baselines.
- **Timing** (single local samples, not a benchmark):

| League | Cold | Warm | Before the cache |
|---|---|---|---|
| 2026 | 1.44 s | 0.24 s | 1.55 s |
| 2025 | 0.89 s | 0.29 s | 1.58 s |

**Gate: passed.** No user-story work had started before this point.

## US1 backend: T018–T026 (2026-10-08)

**Suite** (agent's `cleanTest test` run): 1,439 tests, 0 skipped, 0 failures.

**SC-002**, from `PlayerStatsReadIT` against the real 2025 rows (agent-reported):
- The sample was 10 players: the 9 with the most games, plus Luke Kennard (id 1777, traded ATL → LAL, 78 games).
- Every per-game average and FG/3P/FT% equalled a direct SQL recomputation, with **0 mismatches**.
- The comparison is exact to 1e-9, and also checked at 1 decimal.

**Live C1 check** (parent session, port 8092, 2025 league `1229352720222134272`, measured).
These are the app's real `GameScoringService` figures, replacing the 2026-10-07 SQL estimate:

| Player | GP | PPG | FP/G | League rank | Position rank | Points rank | Move | Biggest contributions | Ownership |
|---|---|---|---|---|---|---|---|---|---|
| Rudy Gobert | 76 | 10.9 | 22.56 | 40 | 12 | 152 | **+112** | reb 872, pts 415, blk 248 | ROSTERED, week 18 |
| Donovan Clingan | 77 | 12.1 | 24.52 | 29 | 8 | 129 | **+100** | reb 892, pts 467, blk 260 | ROSTERED, week 18 |
| Dillon Brooks | 56 | 20.2 | 16.55 | 112 | 31 | 35 | **−77** | pts 566, reb 203, stl 114 | FREE_AGENT, week 18 |

- **Rank group**: 299 qualified players.
- **Earlier estimate, corrected**: the 2026-10-07 SQL estimate put Gobert at 139 → 37 and Clingan at 118 → 26. The real scorer gives 152 → 40 and 129 → 29. Same direction, different numbers. The estimate used a ≥50-games group and its own stat×weight sum. **The estimate was wrong in detail. Nothing was tuned to match it.**
- **Ownership** reads week 18, which is `playoff_week_start − 1` (F5).
- **Fallback** (2026 league `1339351318115946496`, Gobert):
  - `season` 2025, `requestedSeason` 2026.
  - `seasons` is [2026 (no games), 2025, 2024] (F4).
  - `ownership` is the 2025 week-18 roster, and `currentOwnership` is `NOT_DRAFTED` (F6; the draft is 10-10).
- **Errors**: an unknown player id gives 404, and no identity gives 404.
- **Timing**: 1.39 s cold (first request after boot), then about 0.10 s.

## T034: US1 live verification, first pass (2026-10-08)

**Setup**: web on `draft-sim-022-web-5192`, proxied to the backend `draft-sim-022-api-8092`, both
from this worktree. Signed in through the app's own screen as `popsharky`.

**Results (measured in the browser)**

| Check | Result |
|---|---|
| Player page renders (Gobert, 2025) | ✅ Header with photo, team, position, ownership as "End of 2025–26 regular season (week 18)". Season / last-10 / last-5 table with real-vs-fantasy split. Ranks #40 of 299, C #12 of 81, ▲112. Breakdown with negatives. 76-game log, newest first, team per night. |
| V5, links on each page (2025 league unless noted) | ✅ Trends 20 links (clicked Branden Carlson → his page); Weekly Report 10; Roster Management 2,059; Superlatives 8; Home spotlight (2026 league) 5. The 2025 home has no spotlight, as before this spec. |
| NFL names stay plain | ✅ NFL 2026 league `1389361939561332736`: 0 player links on Weekly Report and Roster Management. |
| V6, season picker from 2024 | ✅ Year links and season tabs both reach 2026, 2025 and 2024 on the same player. 2024 ownership reads "week 21" (F4, F5). |
| Fallback (2026 league) | ✅ API: season 2025, requested 2026, `currentOwnership` NOT_DRAFTED (see the T018–T026 entry). |
| Phone, 375 px | ✅ Page width 375, no page-level sideways scroll. Both tables (680 px and 751 px) scroll inside their own boxes (`overflow-x: auto`). |
| SC-005, zero attempts (Deandre Ayton, 0 3PA in 72 games) | ⚠️ Shows "—", not a false 0%. But FR-006 needs a stated "no attempts". Fix 2 below. |

**Problems found, to fix before US1 ships**

1. **FR-011**: the season line shows shooting % without makes and attempts. Gobert's "0.0%" 3P is 0 of 5, which the reader can't see.
2. **FR-006**: a zero-attempt rate shows a bare "—". It needs a stated reason.
3. **Rank sentence**: "Ranked #152 by season total points…" is wrong. `pointsRank` is by points **per game**.
4. **Freshness label**: "Stats through Sep 28, 2026" is `dataAsOf`, the last refresh, not the last game covered. The 2025 season ended in April.
5. **Minutes format**: "34" sits next to "33.4". Use one decimal throughout.
6. **Minus sign**: the breakdown mixes "−12.0" with "-0.7%".

## T034: second pass after fixes (2026-10-08, measured in the browser)

Checked on Deandre Ayton (2025, 0 3PA in 72 games). All six fixes hold:

1. **Makes and attempts**: shooting cells show them, e.g. "67.1% / 403-601", "64.5% / 91-141". 403/601 is 67.05%, which is correct.
2. **Zero attempts**: renders "no att.", with the full sentence as `title` and `aria-label`. The sentence was reworded from "No attempts yet…" to "No attempts in these games…", because "yet" is wrong for a finished season.
3. **Rank sentence**: "By real points per game he's #119 of 299; in this league's scoring he's #98 — 21 places higher."
4. **Freshness label**: the footer reads "Data refreshed Sep 28, 2026."
5. **Minutes**: one decimal throughout.
6. **Minus signs**: "−" (U+2212) throughout.

**Web**: `tsc -b` clean, vitest 89 files / 1,131 tests passing, `npm run build` OK.

## US2: T035–T041 (2026-10-08)

### Build

- **Backend**: `cleanTest test` gives 1,472 tests, 0 skipped, 0 failures.
- **Web**: `tsc` clean, 1,151 tests passing, build OK.
- **Java vs independent SQL** (`PlayerStatsReadIT`, same pooled definitions): Dončić, Jokić and
  Gobert agree to 3 decimals on TS, USG, TRB and AST.
- **Live**: Dončić's Advanced view renders the season, last-10 and last-5 windows, with game counts
  and date spans, a group toggle, and exact values beside the percentile bars. Season TS is 61.6%,
  87th percentile among 87 other point guards.
- **Timing**: warm C1 with percentiles is 0.18 s locally (1.53 s for the first request after
  boot).

### V11 / SC-003: Basketball Reference cross-check (manual, measured)

Read 2026-10-08 in the browser from Basketball Reference's 2025-26 season. Sleeper's 2025 is
Basketball Reference's `NBA_2026`. Two page loads: `NBA_2026_advanced.html` (TS, USG, TRB, AST)
and `NBA_2026_per_game.html` (eFG). The values were read off the rendered tables. **Nothing was
stored or ingested.** The tolerances are TS and eFG ±0.5 points, USG and TRB ±1.0 point.

| Player | GP ours / BR | TS ours / BR (Δ) | eFG ours / BR (Δ) | USG ours / BR (Δ) | TRB ours / BR (Δ) | AST ours / BR (Δ, no tolerance set) |
|---|---|---|---|---|---|---|
| Luka Dončić | 64 / 64 | 61.6 / 61.6 (0.0) | 56.3 / 56.3 (0.0) | 38.4 / 38.1 (+0.3) | 12.6 / 12.7 (−0.1) | 40.5 / 40.5 (0.0) |
| Nikola Jokić | 65 / 65 | 67.0 / 67.0 (0.0) | 61.8 / 61.8 (0.0) | 30.2 / 30.4 (−0.2) | 20.8 / 20.7 (+0.1) | 48.6 / 50.3 (**−1.7**) |
| Rudy Gobert | 76 / 76 | 66.4 / 66.4 (0.0) | 68.2 / 68.2 (0.0) | 13.0 / 12.9 (+0.1) | 20.0 / 20.1 (−0.1) | 7.3 / 7.2 (+0.1) |
| Shai Gilgeous-Alexander | 68 / 68 | 66.5 / 66.5 (0.0) | 59.7 / 59.7 (0.0) | 33.8 / 33.4 (+0.4) | 7.0 / 7.0 (0.0) | 35.1 / 35.0 (+0.1) |
| Jalen Brunson | **75 / 74** | 57.8 / 58.0 (−0.2) | 53.1 / 53.3 (−0.2) | 30.8 / 30.4 (+0.4) | 5.3 / 5.3 (0.0) | 31.5 / 31.3 (+0.2) |

**SC-003 passes**: every toleranced stat is within tolerance for all 5 players. The largest gaps
are 0.4 (USG) and 0.2 (TS/eFG). Nothing was tuned.

Two differences to note:

- **Jokić AST% (−1.7)** has no tolerance in SC-003. It is consistent with research R5's known
  pooling deviation: we pair his games with those games' team totals, while Basketball Reference
  uses whole-season team totals, and he missed 17 games. Reported, not adjusted.
- **Brunson GP 75 vs 74** is a real data finding, below.

### Finding: the NBA Cup final is stored and counted as a regular-season game (measured)

- **The game**: id `1305814461864501248`, 2025-12-16, NYK vs SAS. It is the only game on that date
  in `sport_schedule`, with status `complete`.
- **Team counts**: `TEAM_NYK` and `TEAM_SAS` have **83** games in 2025; every other team has 82.
  The All-Star filter doesn't catch it, because both teams are real team codes.
- **Effect**: the NBA (and Basketball Reference) exclude the Cup final from regular-season stats.
  Ours counts it, so:
  - Brunson shows 75 games, against 74 there;
  - every NYK and SAS player's season averages, rates, `teamGamesMissed` and `maxTeamGames`
    include it (82 vs 83 changes `minGames` from 41 to 42);
  - Trends (spec 019) does the same today. **This predates spec 022.**
- **Fantasy**: Brunson's week 9 was credited 31.0, his Dec 18 game, not the Cup final (23.0). That
  can't tell whether Sleeper treats the final as fantasy-eligible.
- **Status**: not fixed. It is a decision for Allan, because the fix changes Trends output, an
  existing feature, and needs an identification rule Sleeper doesn't supply.

### Cup final decision (2026-10-08): **exclude everywhere** (Allan's choice)

- **The rule**: NBA Cup championship games are dropped in the shared game-lines rule, so the
  player page, leaderboard, nightly report and Trends all match official regular-season stats.
  - The games are identified by a hand-maintained, labelled list,
    `draftsim.nba-games.excluded-game-ids` in `weights.yml`, because Sleeper doesn't flag them.
  - Measured ids: 2024 `20241217_OKC_MIL` (MIL and OKC had 83 games) and 2025
    `1305814461864501248` (NYK and SAS had 83).
  - Trends' `oneGameShare` still reads raw rows, per the earlier F7 decision.
- **Live (measured)**:
  - Brunson shows **74 GP**, matching Basketball Reference.
  - The Cup final is gone from his game log.
  - `qualification` reads `maxTeamGames` 82 and `minGames` 41.
- **Trends**: both leagues' `player-trends` JSON is still **byte-identical** to the T005 baselines.
  None of the listed risers, fallers or streaming players is affected, so the baselines stay valid.
  This is a property of today's lists, not a guarantee that Trends can never change.
- **Backend**: 1,477 tests, 0 skipped.

## US4: T042–T056 live check (2026-10-08, measured)

### Build

- **Backend**: 1,509 tests, 0 skipped. `BoardRepositoryLatestBeforeIT` and
  `PlayerStatsLeaderboardReadIT` ran against the real DB (agent-reported).
- **Web**: 1,214 tests, `tsc` and build clean.

### C2 checks (local backend on 8092)

| Check | Result |
|---|---|
| `GET …/stats?window=SEASON` (2025) | 200. Cold 1.83 s, warm **0.28 s**; LAST_10 warm 0.16 s. |
| Missing window / `window=season` (lowercase) | **400** / **400** |
| Rows | 582 for 2025 SEASON; 303 qualified (rule from the payload: ≥ 41 games of 82, 15+ MPG) |
| Payload size | **1,417,811 B uncompressed**, and the server does not compress. gzip would make it 326,523 B. It is re-fetched on every window switch. → **V9 calls for compression** (fix 2). |
| SC-009, the 2025 draft | All 168 picks, rounds and managers match `draft_pick` and Draft Grades. 167 are on the board, because one drafted player had no 2025 game (IT). |
| ADP 2025 | "no ADP stored for this season" ✅. 2026 `latestBefore` returns 2026-09-28 with 553 rows (IT). |
| Replacement levels (2025 SEASON) | PG 16.5 · SG 16.9 · SF 16.9 · PF 16.6 · C 16.6, stated on the page as a simplification ✅ |

### Browser checks (`http://localhost:5192/leagues/1229352720222134272/stats`)

- **Table**: renders, Fantasy group by default, sorted by FP/G: Jokić 42.73, Dončić 36.63,
  Wembanyama 35.28.
- **Notes**: qualification note and ownership as-of (week 18) are shown.
- **Draft value group**: pick, round and "Drafted by" are right. Bruce Brown reads "undrafted".
- **Stat leaders**: the 9 categories render. Points: Dončić 33.5, SGA 31.1, Edwards 28.8.
- **Phone (375 px)**: no page-level sideways scroll. The table is 862 px inside a 307 px box. The
  name cell (`th`, `position: sticky`) stays at x=34 after scrolling the box 300 px, while the Owner
  column moves to x=−79 (SC-010; checked on the Fantasy group).

### Problems found

1. **Mislabelled draft value (bug class #5; the error was in the data model).** Draft Value says
   "per counted week". Draft Grades' `production` is Σ over counted weeks
   (`DraftGradesService.java:382`; spec 018 data-model "production(p) = Σ_{w ∈ counted}
   weekValue"), so `valueOverSlot` is a **season total over counted weeks**, not a per-week
   figure. Dončić's +77.9 against Jokić's +35.9 is consistent with that: Jokić was pick 1, with a
   higher slot baseline.
2. **No compression**: the C2 payload is 1.4 MB per window and not compressed (above).
3. **Unreadable button**: the active "Stat leaders" view button has teal text on a teal
   background.
4. **Misleading toggle**: the Stat leaders view still shows the "Qualified players only" toggle,
   but the leaders ignore it, because they always use qualified, non-stale rows.

### US4 fixes re-checked live (2026-10-08, measured)

Ten fixes (4 from the live check, V1–V6 from review, and the memo doc gap) were made in a Sonnet
pass. The parent session re-checked them.

**Compression**: `server.compression` is on, `application/json` only.
- C2 for 2025 SEASON is **327,043 B on the wire** with `Content-Encoding: gzip`, down from
  1,417,811 B. That holds both directly on 8092 and through the Vite proxy on 5192.
- The live-draft SSE stream (`text/event-stream`) is deliberately not in `mime-types`, because
  compressing it would buffer events.
- The `ResponseCompressionIT` test was added.

**2026 fallback (league `1339351318115946496`)**:
- API: `season` 2025, `requestedSeason` 2026. Draft is `{NOT_HAPPENED, draftSeason 2026}` and ADP
  `{blend, capturedOn 2026-09-28}`. `draftGrades` is unavailable (`DRAFT_NOT_COMPLETE`). All 582
  rows carry `currentOwnership` (V1, V2).
- Page:
  - the header reads "Owner (now 2026–27)", and rows show "not drafted";
  - the filters are disabled, with the reason "Nobody is on a roster yet (now 2026–27)…";
  - Board ADP shows the 2026 values, e.g. Podziemski 124.0;
  - the fallback banner reads "2026–27 has no games yet; showing 2025–26".

**Other fixes**:
- The "Stat leaders" button is readable: background `oklch(0.72 0.14 175)`, text
  `oklch(0.14 0.025 255)`.
- In that view the qualification toggle is hidden, and the rule is stated instead.
- Draft value is relabelled as a season total over counted weeks; data-model and C2 are amended
  with dated notes.
- Rank move shows whole places.
- The qualified-only filter applies to rate and rank sorts (FR-025).
- Rank headers name their window.

**Tests**:
- Backend: 1,513 tests, 0 skipped.
- Web: 1,228 tests, with `tsc` and build clean.

**SC-006 (local only)**: player page ≈ 0.1–0.2 s warm; leaderboard 0.28 s warm. Measured with
compression on; the first request after a restart is 1.5–2.0 s. Production is not measured yet.

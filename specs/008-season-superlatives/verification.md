# Verification notes: season superlatives

Live verification record for spec 008. **Verified** means the check below was actually run, against
the real server, database or Sleeper, on the date given. Anything not run is marked so.

## Environment (2026-09-23)

- Postgres 17 in Docker (`draftsim-pg`, 5433). Docker Desktop had to be started first.
- Backend `bootRun` on 8080, from branch `008-season-superlatives` at `286d972`, before any 008 code.
- The DB already held all three reference leagues, so `ingest/all` was **not** re-run. It rebuilds
  the shared draft board (memory: adp_at_time footgun), and nothing here needed that.
  - `1346366555759341568` (NFL 2026): 24 weekly rows = weeks 1–2; Sleeper `last_scored_leg` 2.
  - `1254190892974084096` (NFL 2025): 204 rows = weeks 1–17.
  - `1229352720222134272` (NBA 2025): 252 rows = weeks 1–21.
  - `player_game`: 19,428 NBA 2025 rows, 16,909 NBA 2024 rows, **0 NFL rows**.

## Before the change (T003)

### R3: expected wins counts playoff games. Verified.

Stored pairings in playoff weeks:

| League | Week | Rows | With matchup_id |
|---|---|---|---|
| NFL 2025 | 15 | 12 | 8 |
| NFL 2025 | 16 | 12 | 12 |
| NFL 2025 | 17 | 12 | 8 |
| NBA 2025 | 19 | 12 | 8 |
| NBA 2025 | 20 | 12 | 12 |
| NBA 2025 | 21 | 12 | 8 |

`GET /api/leagues/{id}/expected-wins` before the change:

| League | weeksScored | Top three by wins above expected (actual / expected / WAE) |
|---|---|---|
| NFL 2025 | **17** (playoff start 15) | jpelwell 11.0 / 6.23 / +4.77; FatDeebo's D 10.0 / 8.29 / +1.71; She Sutton on … 9.0 / 7.92 / +1.08 |
| NBA 2025 | **21** (playoff start 19) | Chetinyahu's 11.0 / 8.47 / +2.53; THSaHomBBwiTHa 12.0 / 10.57 / +1.43; The Retirement 13.0 / 11.86 / +1.14 |

So playoff and consolation games are in the page's schedule luck today. Full payloads were saved to
the session scratchpad (`before-ew-<league>.json`).

### Career luck before the change (analysis H1). Verified.

`GET /api/managers/7/history` (popsharky), the career `winsAboveExpected`:

| Sport | Seasons | winsAboveExpected |
|---|---|---|
| nfl | 3 | 0.40 |
| nba | 2 | -4.91 |

### R8: waiver `leg` vs first started week. Verified.

Across NFL 2026 and NFL 2025, for every completed WAIVER/FREE_AGENT add, the first week the player
was started by the adding roster minus the add's stored week:

| Difference | Adds |
|---|---|
| −1 | 2 |
| 0 | 123 |
| +1 | 71 |
| +2 or more | 34 |

- **The 123 same-week cases** confirm that an add's `week` is the week he can first be started, so
  research R8's rule `add week <= started week` is right.
- **The two −1 cases** are both **re-adds**: GB on roster 3, and player 8123 on roster 10 in NFL
  2025. The team had held the player earlier, dropped him, and re-added him, so the earlier start
  belongs to the earlier stint. "Most recent arrival" handles this.

### R11: are IR-slot players keys in `players_points`? Verified, with a caveat.

Sleeper `/league/{id}/rosters` → `reserve`, checked against the latest scored week's matchup:

| League | IR players now | In `players_points` |
|---|---|---|
| NFL 2026 `1346366555759341568` | 7 | 7 of 7 |
| NFL 2026 `1389361939561332736` | 7 | 7 of 7 |
| NBA 2025 `1229352720222134272` | 12 | 8 checked, 8 of 8 (incl. Embiid, 1511, on roster 8) |

**Caveat**: this compares *today's* IR slots with the latest scored week, not a week-by-week IR
history (Sleeper doesn't expose one). For NBA 2025 (complete, week 21 last) the two coincide. For
NFL 2026, a player moved to IR after week 2 would pass without ever having been in an IR slot for a
scored week. That's the weakest part of the evidence.

## Membership decision (T019)

**Keep the planned rule**: roster membership for the Embiid and Unethical awards = keys in that
roster's weekly `players_points`. IR-slot players are keys (R11 above), so no roster-tenure
reconstruction is needed. The caveat above stays attached to this decision.

## US1: extremes and close games (T026). Verified 2026-09-23.

Backend restarted on the 008 code (V22 applied by Flyway). The endpoint responded in 0.12 s (NFL
2026, first call), 0.04 s (NFL 2025) and 0.04 s (NBA 2025).

Every US1 figure was recounted independently in SQL (scores ≤ the regular-season end, paired games
joined by `matchup_id`, margin strictly under the sport's threshold). **All agree:**

| League | Through | Highest | Lowest | Blowout | Closest | Close wins | Close losses |
|---|---|---|---|---|---|---|---|
| NFL 2026 | wk 2 | 164.96 (w1, r10) | 59.60 (w2, r1) | 99.68 | 9.50 | r1: 1 | r7: 1 |
| NFL 2025 | wk 14 | 205.04 (w8, r7) | 73.14 (w2, r4) | 118.86 | 0.36 | r3, r7, r8, r9: 3 each (4-way tie, all named) | r2, r12: 4 each |
| NBA 2025 | wk 18 | 311.50 (w7, r11) | 163.00 (w17, r2) | 102.50 | 0.50 (3 games tied, 3 holders) | r10: 6 | r8, r12: 4 each |

- **The bounded SQL path ran on Postgres**, not just in mocks: NFL 2025 counted 84 games (14 weeks ×
  6) and NBA 2025 108 (18 × 6), so playoff weeks 15–17 and 19–21 are excluded. `throughWeek` reads
  14 and 18.
- **Ties**: each tied holder carries its own detail rows (NFL 2025 close wins: 12 rows, 3 per holder).
- **Scoping**: a stranger's `X-Sleeper-User` gets 404. No header gets 200, which is
  `LeagueMembership.canSee`'s existing anonymous passthrough, the same as every other league page.
- **Browser** (`/leagues/1346366555759341568/superlatives`, signed in by seeding `bk.user.v1` in
  the dev tab's localStorage):
  - "Superlatives" sits directly after "Weekly report" in the rail.
  - All twelve cards render, and the six unbuilt ones say "not built yet".
  - At 375 px the document width is 375, so there's no horizontal scroll.
  - Screenshots timed out (app window hidden), so the checks were made via DOM text and layout
    queries.
- **Found and sent back for fixing**:
  - (1) Single-event cards didn't show the week without expanding, which misses FR-002.
  - (2) The close-losses card read "1 game" instead of "1 loss".
- **Both fixed, rechecked live** on NBA 2025:
  - "311.50 points · week 7".
  - Three tied closest-game holders, each with its own opponent and week.
  - "4 losses by under 15 points".
  - Frontend suite: 524 tests passing.

## US2: luck and bench (T036). Verified 2026-09-23, except the bench figures.

Backend restarted on the US2+US3 code.

**SC-002 holds.** On all three leagues, every `LUCK` detail row's actualWins, expectedWins and
winsAboveExpected equals the Expected wins endpoint's row for that roster, exactly, including ties.
NFL 2026 has 2-way ties for both luckiest (+0.64) and unluckiest (−1.00).

**Expected wins, before vs after the regular-season bound** (the approved change):

| League | weeksScored before → after | Luckiest before | Luckiest after |
|---|---|---|---|
| NFL 2025 | 17 → **14** | jpelwell +4.77 | jpelwell +4.18 |
| NBA 2025 | 21 → **18** | Chetinyahu's +2.53 | THSaHomBBwiTHaM +2.27 (the luckiest team changes) |
| NFL 2026 | 2 → 2 | (unchanged: no playoff weeks yet) | |

**Career profiles, before vs after** (analysis H1; this change was *not* in what Allan approved,
so it's flagged to him). `GET /api/managers/7/history` (popsharky):

| Sport | Career winsAboveExpected before | after |
|---|---|---|
| nfl | +0.40 | **−0.15** (the sign flips: career "lucky" becomes "unlucky") |
| nba | −4.91 | −4.64 |

**Bench points: not independently verified.**
- Holders: NFL 2025 Republic of Azerbaijan 274.08 over 14 weeks; NBA 2025 Jocky Want Boing Boing
  280.00 over 18.
- The figure comes from the same `RealizedLineupService.bestLineup` call the Weekly Report uses, but
  no hand-built optimal lineup was compared.
- `early: true` is set on all three US2 kinds for NFL 2026 (2 weeks < 4), and false for the complete
  seasons.

## US3: Waiver Wire Warrior (T041). Verified 2026-09-23.

**SC-003 holds.** The rule was re-implemented in SQL: each started player per week, with the latest
completed transaction by (week, created_at) at or before that week whose `adds` put him on that
roster, counted when WAIVER or FREE_AGENT. Top 3 by total:

| League | SQL | Endpoint |
|---|---|---|
| NFL 2025 | r4 522.20, r7 470.40, r1 454.22 | She Sutton on … (r4) **522.20** |
| NBA 2025 | r6 1587.50, r3 1507.00, r9 1433.00 | KATastrophe Kr… (r6) **1587.50** |
| NFL 2026 | r12 19.00, r4 10.00, r3 9.00 | Torta Pounder (r12) **19.00** (Tyler Loop, K, FA week 1) |

- **R8's `leg` question** is settled by T003's measurement: 123 adds were first started in their own
  week, and the only earlier starts were re-adds.
- **Gap found**: `PICKUP` rows don't carry `rosterId`, so tied holders can't be matched to their own
  pickups. Being fixed (backend emits it, frontend groups by it).

## US4 backend: absences, AbsenceCost, live ingest (T042-T048). Verified 2026-09-23.

**Real bug found and fixed during the live run (T045), not by inspection.** The very first live run
against real Sleeper data threw `ClassCastException` on every NFL player. Research R9 described
football as carrying "one entry per week"; measured directly against
`GET /stats/nfl/player/4034?season_type=regular&season=2025&grouping=week`, a football week's raw
JSON value is a **bare object**, not a one-element array like basketball's (`"1":{...}` vs
`"1":[{...}]`). `PlayerGameIngestService`'s walk (and the pre-existing `SleeperPlayerStatsClient`
type declaration) assumed every sport's week value was a JSON array. Fixed by normalizing each week's
raw value through a new `asEntryList` helper that accepts a bare `Map` as a one-entry week, a `List`
as-is, and anything else (including `null`, a `None` week) as unclassifiable-by-default. A dedicated
regression test (`aFootballWeekValueThatIsABareObjectNotWrappedInAListIsHandledCorrectly`) pins the
real shape directly; the other NFL tests written against the list-wrapped form are still valid and
were kept, since `asEntryList` accepts both.

**Live ingest run** (`PlayerGameIngestService.ingest(...)`, driven from a one-off `@SpringBootTest`
manual check, NOT the 8080 dev server, which is still on pre-008 code; the check class was deleted
after this run per the task's own instruction):

| League | Wall-clock | playersWalked | gamesStored | playersFailed | absencesStored | weeksUnclassified |
|---|---|---|---|---|---|---|
| NFL 2025 (`1254190892974084096`) | 14.5 s (first run after the bare-object fix; **7.9 s and 0 games stored on the broken first attempt**) | 315 | 4421 | 0 | 936 (418 TEAM_PLAYED_NO_ENTRY, 518 ENTRY_WITHOUT_PLAY) | 0 |
| NBA 2025 (`1229352720222134272`) | 43.4 s (75.4 s on the same-day first run, before Sleeper's own response caching warmed) | 331 | 19428 | 0 | 7383 (160 TEAM_PLAYED_NO_ENTRY, 7223 ENTRY_WITHOUT_PLAY) | 0 |

Both wall-clock times are well under research R9's ~1.5-minute estimate for the football walk.

**Hand-checks** (queried directly against `player_absence` and `player_game`, NFL 2025, `league.id=5`):

- **A known injured NFL regular**: Joe Mixon (`sleeper_id 4018`, `injury_status = "Out"`) has **17
  `ENTRY_WITHOUT_PLAY` absence rows**, weeks 1-5 and 7-18 (week 6 is HOU's bye and correctly has no
  row), and **zero `player_game` rows** for the season. Matches "never dressed all year."
- **A rostered player's bye week has no row**: Christian McCaffrey (`sleeper_id 4034`) has **zero**
  `player_absence` rows for 2025, and his `player_game` weeks are 1-13, 15-18 -- week 14 (SF's actual
  2025 bye, confirmed live against Sleeper directly) is simply absent from both tables, not written as
  either a game or an absence.
- **NFL `player_game` has no row for a week not played**: confirmed by both cases above (Mixon: zero
  rows all season; McCaffrey: week 14 missing).

**T050's M1 scoring check (football), done here since it gates whether US4 can ship for football at
all**: for Christian McCaffrey, NFL 2025, weeks 1 and 2, `GameScoringService.score(league.scoring,
stats)` computed by hand against the league's own `scoring_json` (`rec: 1.0, rec_yd: 0.1, rush_yd:
0.1, rec_td: 6.0`, …):
- Week 1: `rec 9 * 1.0 + rec_yd 73 * 0.1 + rush_yd 69 * 0.1 = 9.0 + 7.3 + 6.9 = 23.2`. Stored
  `players_points["4034"] = 23.2`. **Match.**
- Week 2: `rec 6 * 1.0 + rec_yd 52 * 0.1 + rec_td 1 * 6.0 + rush_yd 55 * 0.1 = 6.0 + 5.2 + 6.0 + 5.5 =
  22.7`. Stored `players_points["4034"] = 22.7`. **Match.**

**No mismatch.** `GameScoringService`'s output equals the stored weekly `players_points` for football,
same as the NBA agreement research R10 already assumed -- this is not a blocker for US4.

**Backend suite**: `./gradlew.bat cleanTest test` -- **616 tests, 0 failures, 0 errors, 0 skipped**
(summed from `backend/build/test-results/test/*.xml`). The normal suite does not call Sleeper (the
manual live-check class was deleted, not merely disabled).

**Not done in this pass** (out of scope for T042-T048): `JOEL_EMBIID`'s frontend card (T049) and the
browser live-check against the NBA 2025 page (T050's non-M1 parts) -- both explicitly skipped per the
task brief. T048's wiring is unverified against a real browser render; only the engine-level numbers
above were hand-checked.

**Amended 2026-09-23 (coordinator follow-up, same day).** The `JOEL_EMBIID` coverage note above
originally shipped as a standing, league-level caveat ("some bye/DNP weeks may be unclassified…")
gated only on the sport, with no number behind it -- exactly the thing this repo refuses to ship
(AGENTS.md: label a guess as a guess, or measure it). It was wrong twice over: the live run measured
**0** unclassified weeks for both reference leagues, and the note would have shown even then. Fixed:

- New `V23__player_absence_unclassified.sql` (append-only after V22; dropped and re-added the
  **`player_absence_basis_check`** constraint to also allow `'UNCLASSIFIED'`). Applied live: Flyway
  reports version 23 current, and `pg_get_constraintdef` on the live DB confirms the three-value
  `ANY (ARRAY[...])` form.
- `PlayerGameIngestService` now writes an `UNCLASSIFIED` `player_absence` row (`game_id`/`game_date`/
  `team` all null) for every unclassifiable `None` week, instead of only incrementing a counter that
  was never persisted.
- `AbsenceCost` still has no concept of `basis` at all -- `SeasonSuperlativesService.absenceSuperlative`
  filters `UNCLASSIFIED` rows out before ever building an `AbsenceCost.Absence`, so they cost nothing
  by construction. Pinned from both sides: `AbsenceCostTest.aWeekWithNoAbsenceEntryAtAllNeverCostsAnything`
  (an absence that's never passed in costs nothing) and the ingest test above (the row is written with
  `UNCLASSIFIED`, not folded into a cost-bearing basis).
- `JOEL_EMBIID` coverage is now a per-holder, per-week COUNT: "roster N: K weeks couldn't be classified
  as a bye or a missed game" for whichever holder(s) have an `UNCLASSIFIED` row among a *regular
  contributor's* rostered weeks -- no coverage entry at all when that count is 0 for every holder.
  Extracted as a pure `SeasonSuperlativesService.unclassifiedCoverage` for direct testing
  (`SeasonSuperlativesAbsenceCoverageTest`, 5 cases: zero produces null, a count produces the right
  wording and pluralization, only holders with a nonzero count get a reason, and a non-holder roster's
  unclassified weeks are ignored).
- Re-ran `./gradlew.bat cleanTest test`: **622 tests, 0 failures, 0 errors, 0 skipped** (up from 616;
  +6: 1 new `AbsenceCostTest` case, 5 new `SeasonSuperlativesAbsenceCoverageTest` cases; the
  `PlayerGameIngestServiceTest` unclassified case was renamed and strengthened in place, not added).

Files touched by this follow-up: `backend/src/main/resources/db/migration/V23__player_absence_unclassified.sql`
(new), `backend/src/main/java/.../ingest/PlayerGameIngestService.java`,
`backend/src/main/java/.../engine/SeasonSuperlativesService.java`,
`backend/src/test/java/.../ingest/PlayerGameIngestServiceTest.java`,
`backend/src/test/java/.../engine/AbsenceCostTest.java`,
`backend/src/test/java/.../engine/SeasonSuperlativesAbsenceCoverageTest.java` (new). No `web/` file and
no `specs/008-season-superlatives/contracts/` file was touched.

## US4: the Joel Embiid Award (T045, T050). Verified 2026-09-23.

**Live ingest** (the build agent ran the new ingest once against real Sleeper and this DB):

| League | Time | Players walked | Games stored | Failed | Absences (ENTRY_WITHOUT_PLAY / TEAM_PLAYED_NO_ENTRY) | Unclassified |
|---|---|---|---|---|---|---|
| NFL 2025 | 14.5 s | 315 | 4,421 | 0 | 518 / 418 | 0 |
| NBA 2025 | 43.4 s | 331 | 19,428 | 0 | 7,223 / 160 | 0 |

- **Research R9's shape was wrong, and live ingest caught it.** A football week is a bare object, not
  a list. The first run threw on every NFL player and stored nothing. Now fixed and amended in
  research.md.
- **Football estimate is ~14.5 s**, not the ~1.5 min estimated. R9's 300 ms/call came from basketball;
  football responses are much smaller.
- **Hand-checked (build agent)**:
  - Joe Mixon (4018): 17 ENTRY_WITHOUT_PLAY weeks and 0 player_game rows.
  - Christian McCaffrey (4034): week 14, SF's 2025 bye, has no row in either table.
- **M1 football scoring check (build agent)**: `GameScoringService` gives the same value as the
  stored `players_points` for McCaffrey weeks 1–2 (23.2, 22.7). Not a blocker.
- **SC-004 hand count (parent)**: NBA 2025 holder FentMachines5 (roster 3), top absence Jokić
  (1658). SQL counts **16** absence rows in weeks ≤ 18 while he was a key in roster 3's
  `players_points`, across **5** fantasy weeks (weeks 11–15, 2025-12-31 to 2026-01-29). The payload
  says `gamesMissed: 16, weeksAffected: 5`. **Matches.** `pointsPerGame` (42.73) wasn't hand-scored
  for basketball; spec 005 verified the same scorer on this league.
- **NFL 2026**: `JOEL_EMBIID` is unavailable with the reason "per-game records not ingested — run
  POST /api/ingest/player-games/1346366555759341568". Correct; that ingest hasn't been run.
- **Found and fixed**: a standing caveat "some bye/DNP weeks may be unclassified" appeared although 0
  were measured. The count is now stored (V23 allows an `UNCLASSIFIED` basis) and reported per
  holder only when non-zero.

## US6: the Unethical Award (T061). Verified 2026-09-23, with fixes pending re-check.

- **Capture**: `POST /api/ingest/players?sport=nfl` returned `suspensionCaptured: true,
  suspendedCount: 2` and upserted `status_capture (nfl, 2026, 3)`.
  - The quickstart expected 10 (every `Sus` tag). Only **2** are fantasy-eligible (Jeshaun Jones and
    Brock Rechsteiner, both WR); the other 8 are defensive players, which ingest never writes and
    which can't be on these rosters.
  - The quickstart's expectation was wrong, not the code.
- **Commissioner flow**:
  - **Driven from the page** as popsharky (commissioner of NFL 2026): added Tyler Loop (12711),
    reason `TEST entry <b>bold move</b>`, applies from week 2.
    - POST 200.
    - The reason renders as literal text; there's no `<b>` element in the DOM.
    - The API then credits roster 12 with **week 2 only** (week 1 excluded, FR-018) and
      `source: COMMISSIONER`.
  - **As a non-commissioner member** (1092366167651483648):
    - The page shows the list with no controls.
    - `GET` returns `canEdit: false`.
    - `POST` and `DELETE` return **403** `{message, commissionerKnown: true}`.
  - **As the commissioner**:
    - DELETE of this entry through the *2025* league's URL returns **404**.
    - A 141-char reason returns **400** "reason must be 1-140 chars, trimmed".
- **CORS**: in dev, Vite proxies `/api`, so the browser never preflights. Checked directly instead:
  `OPTIONS` with `Origin` and `Access-Control-Request-Method: POST` / `DELETE` plus
  `content-type, x-sleeper-user, authorization` returns 200 with all three allowed.
- **Found and sent back for fixing**:
  1. **Connection leak (critical).** After about 10 page loads the superlatives endpoint hung, while
     `/api/health` stayed fine. `pg_stat_activity` showed all 10 pool connections idle-held after
     `select distinct sleeper_player_id from player_game …`.
     - Cause: `PlayerGameRepository.playersWithGames` returns
       `.query(String.class).stream().collect(...)`, and JdbcClient's stream holds its connection
       until closed.
     - It was **dead code on main** (spec 005) until superlatives called it.
     - The 4 other `.stream().collect` sites in `store/` all go through `.list()` first, so they're
       safe.
     - Structural tests never saw it: each test makes one call. Only repeated real requests did.
  2. `suspensionWeeksObserved` listed week 3 while the season is scored through week 2, so the page
     read "tracking began week 3".
  3. The empty reason said "no suspension has been captured yet" when a capture existed and simply
     found no rostered suspended player.
  4. After a save, the award card kept saying "the list is empty" until a reload.
  5. Commissioner-source rows didn't show their weeks (FR-002).
  6. The form's labels weren't associated with their inputs (unnamed textboxes).

### US6 fixes, rechecked live (2026-09-23)

- **Leak fixed**:
  - `playersWithGames` now uses `.set()`.
  - The regression IT fails on the old code (a 3-connection pool is exhausted by about the 4th
    call) and passes on the new.
  - Live: 25 consecutive superlatives calls all returned 200, more than twice the pool size of 10.
- **Unscored weeks dropped**: `suspensionWeeksObserved` is now `[]` for NFL 2026, because week 3 is
  captured but not scored.
- **Empty reason**: "no rostered player has been suspended in a tracked week, and the commissioner's
  list is empty".
- **Browser**:
  - The commissioner row shows "· week 2".
  - Form inputs have accessible names (the accessibility tree lists "textbox Reason").
  - Remove → DELETE 204, the row is gone from the DB, and the award card refreshed without a reload.
  - The browser logged the 204 as `net::ERR_ABORTED`: an artefact of a bodiless response. The delete
    itself succeeded.
- **Test data**: the test entry was removed through the UI, and `league_conduct_entry` is now empty.

## Polish (T062–T066). 2026-09-23.

- **T062**: `NoSportNameInSuperlativesTest` guards 5 files. No offending lines were found.
- **T063**: every `Map.of`/`List.of` in the new and changed files is empty, all-literal or
  null-guarded; response maps are built with `LinkedHashMap`. No changes needed.
- **T064, backend suite**: read from a fresh run's XML — **650 tests, 0 failures, 0 errors,
  0 skipped** (97 files).
- **T065, frontend**: `tsc -b` clean, `npm run build` OK, **541 tests passing** (46 files).
- **T066, browser**:
  - Both leagues were read with all twelve cards live; none says "not built yet".
  - NFL 2026 shows the early caveat on the season-long kinds, and the Embiid card correctly says
    per-game data hasn't been ingested.
  - At 375 px: document width 375, **0** elements overflowing `main`.
  - Screenshots couldn't be taken (the app window was hidden, so the pane never drew). Every
    browser check here was made through DOM text and the accessibility tree instead.
- **Endpoint timing** (curl, warm server):

  | League | Before the Embiid and Unethical kinds | After |
  |---|---|---|
  | NFL 2026 | 0.11 s | 0.17–0.21 s |
  | NFL 2025 | 0.10 s | 0.31 s |
  | NBA 2025 | 0.11 s | 0.56–0.60 s |

  The plan's performance goal was a guess; these are the measured numbers. NBA is the slowest
  because it scores about 19k game rows to get each regular contributor's points per game.
- **Found**: the "suspension tracking hasn't covered a scored week yet" sentence printed twice when
  nothing is tracked. Sent back for fixing.

## Code review (T067), 2026-09-23

A bug-hunting review (`/code-review high`) over the full branch diff produced **9 findings, all
fixed** and all rechecked live. Backend suite after fixes: **667 tests, 0 failures, 0 errors,
0 skipped**. Frontend: **546 tests passing**.

The build agent was explicit that items 1, 2, 5, 7 and 8 each had a test confirmed failing before
the fix. Items 3, 4, 6 and 9 went in one combined edit, with regression tests added after, not run
failing first.

| # | Finding | Live recheck after the fix |
|---|---|---|
| 1 | "Regular contributor" divided by all rostered weeks, not weeks he played (research R10), which dropped long-injured stars | NBA 2025 holder is now Shaolintonio Spurs (3,980.55). **Joel Embiid** now appears (26 games, 14 weeks, ~26.26/game); a hand count of `player_absence` on roster 8 gives 26 and 14. |
| 2 | Re-ingest never removed stale absence/game rows, and future-dated empty-stat games were written as absences | Unit and IT tests. Not re-run live: needs a mid-season NBA ingest, and no NBA season is live today. |
| 3 | Embiid availability was sport-season-wide, so a second league got a silently partial award | Second NFL 2025 league `1262506916429430784`: now carries "18 rostered players have no per-game records — run POST …/player-games/1262506916429430784". |
| 4 | Points per game included playoff and unscored weeks | Bounded to the scored window; the unit test covers it. |
| 5 | Capture used `/state` `week`; measured `/state/nba` = `{"week":1,"leg":0,"season_type":"pre"}` | `POST /api/ingest/players?sport=nba` → `suspensionCaptured: false`. NFL → captured (nfl, 2026, **3**) = leg 3. |
| 6 | Four full loads of players and weekly rows per request | NBA 2025 endpoint went from 0.56–0.60 s to **0.43 s**; NFL 2025 from 0.31 s to 0.22 s. |
| 7 | N+1 player lookups in the conduct list | Batch test. |
| 8 | An edit overwrote "added by" | `LeagueConductRepositoryIT`. |
| 9 (from finding 4) | Award and commissioner list could be different seasons | `/leagues/1339351318115946496/superlatives` (NBA 2026, unscored) shows 2025 with `leagueSleeperId` = 2025's id. The list requests go to the 2025 league and the page says why. Spec amendment 15 records the trade-off. |
